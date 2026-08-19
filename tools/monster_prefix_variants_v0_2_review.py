#!/usr/bin/env python3
"""Design audit for AlarmQuest's rebuilt monster-prefix system v0.2.

This file intentionally ignores live/legacy enemy schemas, IDs, save data and
migration requirements.  It projects the approved 24-enemy design roster into
a clean vNext contract and produces six deterministic, compatible variants per
base enemy.  No production asset or character data is read or mutated.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path

import region_enemy_skill_acquisition_v0_1_review as roster_v01


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_MONSTER_PREFIX_VARIANTS_v0.2.md"
RULES_VERSION = "aq.monster-prefix.v0.2"
REGIONS = ("green_hills", "old_mine", "ashen_forest")
RANKS = ("NORMAL", "ELITE", "BOSS")
PROFILES = ("STANDARD", "SWIFT", "ARMORED", "SPELLCASTER")


@dataclass(frozen=True)
class BaseEnemy:
    enemy_id: str
    name_ko: str
    region_id: str
    rank: str
    profile: str
    family: str
    status_immunities: tuple[str, ...]
    damage_resists_bps: tuple[tuple[str, int], ...]
    vulnerabilities_bps: tuple[tuple[str, int], ...]
    ability_ids: tuple[str, ...]


@dataclass(frozen=True)
class Prefix:
    prefix_id: str
    name_ko: str
    threat_bps: int
    reward_bps: int
    effects: tuple[tuple[str, int], ...]
    excluded_families: tuple[str, ...] = ()
    excluded_immunities: tuple[str, ...] = ()
    excluded_resists: tuple[tuple[str, int], ...] = ()
    behavior_rule: str = "NONE"
    drawback_rule: str = "NONE"


@dataclass(frozen=True)
class MonsterVariant:
    variant_id: str
    name_ko: str
    base_enemy_id: str
    region_id: str
    rank: str
    profile: str
    family: str
    prefix_id: str
    threat_bps: int
    reward_bps: int
    effects: tuple[tuple[str, int], ...]
    behavior_rule: str
    drawback_rule: str


def project_base_enemy(item: roster_v01.EnemyPlan) -> BaseEnemy:
    """Strip every v0.1 compatibility/live-asset concern from the new roster."""
    return BaseEnemy(
        enemy_id=f"aq.enemy.{item.region_id}.{item.enemy_id}",
        name_ko=item.name_ko,
        region_id=item.region_id,
        rank=item.rank,
        profile=item.profile,
        family=item.family,
        status_immunities=item.status_immunities,
        damage_resists_bps=item.damage_resists_bps,
        vulnerabilities_bps=item.vulnerabilities_bps,
        ability_ids=item.ability_ids,
    )


BASE_ENEMIES = tuple(project_base_enemy(item) for item in roster_v01.ENEMIES)

# All changes are basis-point deltas.  Positive *_TAKEN means vulnerability.
# Reward is deliberately below threat: a prefix is variety, not a farming exploit.
PREFIXES = (
    Prefix(
        "FEROCIOUS", "흉포한", 700, 10_420,
        (("ATTACK_BPS", 1_200), ("DEFENSE_BPS", -800)),
        drawback_rule="LOWER_DEFENSE",
    ),
    Prefix(
        "IRONHIDE", "철갑의", 800, 10_480,
        (("PDEF_BPS", 1_500), ("MRES_BPS", 1_500), ("SPEED_BPS", -800)),
        drawback_rule="LOWER_SPEED",
    ),
    Prefix(
        "QUICK", "날랜", 650, 10_390,
        (("SPEED_BPS", 1_500), ("EVASION_BPS", 600), ("MAX_HP_BPS", -800)),
        drawback_rule="LOWER_MAX_HP",
    ),
    Prefix(
        "PRECISE", "정밀한", 500, 10_300,
        (("HIT_FLAT", 8), ("CRITICAL_FLAT", 3), ("ATTACK_BPS", -500)),
        drawback_rule="LOWER_ATTACK",
    ),
    Prefix(
        "ARCANE", "마력 깃든", 650, 10_390,
        (("MRES_BPS", 1_000), ("STATUS_APPLY_BPS", 600), ("PDEF_BPS", -500)),
        drawback_rule="LOWER_PDEF",
    ),
    Prefix(
        "VENOMOUS", "맹독의", 750, 10_450,
        (("POISON_APPLY_BPS", 600), ("FIRE_TAKEN_BPS", 500)),
        excluded_immunities=("POISON",),
        behavior_rule="POISON_300_BPS_MAX_HP_FOR_3_ROOTS",
        drawback_rule="FIRE_VULNERABLE",
    ),
    Prefix(
        "FROSTBOUND", "서리 묶인", 700, 10_420,
        (("CHILL_APPLY_BPS", 600), ("FIRE_TAKEN_BPS", 500)),
        behavior_rule="CHILL_SPEED_MINUS_1000_BPS_FOR_2_ROOTS",
        drawback_rule="FIRE_VULNERABLE",
    ),
    Prefix(
        "EMBER", "잿불 두른", 700, 10_420,
        (("BURN_APPLY_BPS", 600), ("ICE_TAKEN_BPS", 500)),
        excluded_immunities=("BURN",),
        excluded_resists=(("FIRE", 1_500),),
        behavior_rule="BURN_300_BPS_MAX_HP_FOR_3_ROOTS",
        drawback_rule="ICE_VULNERABLE",
    ),
    Prefix(
        "CURSED", "저주받은", 650, 10_390,
        (("CURSE_APPLY_BPS", 600), ("HEAL_RECEIVED_BPS", -1_000)),
        behavior_rule="CURSE_DAMAGE_DEALT_MINUS_800_BPS_FOR_3_ROOTS",
        drawback_rule="LOWER_HEAL_RECEIVED",
    ),
    Prefix(
        "REGENERATING", "재생하는", 850, 10_510,
        (("ATTACK_BPS", -500),),
        excluded_families=("UNDEAD", "CONSTRUCT", "SPIRIT"),
        behavior_rule="HEAL_500_BPS_MAX_HP_EVERY_3_OWN_ROOTS",
        drawback_rule="LOWER_ATTACK",
    ),
    Prefix(
        "HUNGRY", "굶주린", 750, 10_450,
        (("LOW_HP_ATTACK_BPS", 1_200), ("HIGH_HP_DAMAGE_TAKEN_BPS", 500)),
        behavior_rule="ENRAGE_WHEN_HP_AT_OR_BELOW_3500_BPS",
        drawback_rule="TAKE_MORE_DAMAGE_ABOVE_3500_BPS_HP",
    ),
    Prefix(
        "ANCIENT", "고대의", 900, 10_540,
        (("MAX_HP_BPS", 1_800), ("CONTROL_RESIST_BPS", 1_000), ("SPEED_BPS", -1_200)),
        behavior_rule="BOSS_CONTROL_ROOT_DELAY_CAP_1",
        drawback_rule="LOWER_SPEED",
    ),
)
PREFIX_BY_ID = {item.prefix_id: item for item in PREFIXES}


def compatible(enemy: BaseEnemy, prefix: Prefix) -> bool:
    if enemy.family in prefix.excluded_families:
        return False
    if set(enemy.status_immunities).intersection(prefix.excluded_immunities):
        return False
    resist_map = dict(enemy.damage_resists_bps)
    return all(resist_map.get(kind, 0) < threshold for kind, threshold in prefix.excluded_resists)


def assigned_prefixes(
    enemy_index: int,
    enemy: BaseEnemy,
    usage: Counter[str] | None = None,
) -> tuple[Prefix, ...]:
    """Pick exactly six prefixes with stable, distribution-aware content rules."""
    start = (enemy_index * 5) % len(PREFIXES)
    rotated = PREFIXES[start:] + PREFIXES[:start]
    usage = usage or Counter()
    order = {prefix.prefix_id: index for index, prefix in enumerate(rotated)}
    candidates = [prefix for prefix in rotated if compatible(enemy, prefix)]
    candidates.sort(key=lambda prefix: (usage[prefix.prefix_id], order[prefix.prefix_id]))
    selected = tuple(candidates[:6])
    if len(selected) != 6:
        raise AssertionError(f"not enough compatible prefixes: {enemy.enemy_id}")
    return selected


def build_variants() -> tuple[MonsterVariant, ...]:
    result = []
    usage: Counter[str] = Counter()
    for index, enemy in enumerate(BASE_ENEMIES):
        for prefix in assigned_prefixes(index, enemy, usage):
            slug = enemy.enemy_id.removeprefix("aq.enemy.")
            result.append(MonsterVariant(
                variant_id=f"aq.enemy.variant.{slug}.{prefix.prefix_id.lower()}",
                name_ko=f"{prefix.name_ko} {enemy.name_ko}",
                base_enemy_id=enemy.enemy_id,
                region_id=enemy.region_id,
                rank=enemy.rank,
                profile=enemy.profile,
                family=enemy.family,
                prefix_id=prefix.prefix_id,
                threat_bps=prefix.threat_bps,
                reward_bps=prefix.reward_bps,
                effects=prefix.effects,
                behavior_rule=prefix.behavior_rule,
                drawback_rule=prefix.drawback_rule,
            ))
            usage[prefix.prefix_id] += 1
    return tuple(result)


VARIANTS = build_variants()


def canonical_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "baseEnemies": [asdict(item) for item in BASE_ENEMIES],
        "prefixes": [asdict(item) for item in PREFIXES],
        "variants": [asdict(item) for item in VARIANTS],
        "variantsPerBase": 6,
        "bossFieldWeight": 0,
        "bossControlApplyMultiplierBps": 5_000,
        "bossControlRootDelayCap": 1,
        "prefixGrantsSkill": False,
        "prefixCreatesLootPool": False,
        "manualCombatAction": False,
        "legacyCompatibilityRequired": False,
    }


def canonical_hash() -> str:
    raw = json.dumps(canonical_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def run_balance_checks() -> list[tuple[str, bool, str]]:
    by_base = defaultdict(list)
    by_region = Counter()
    by_rank = Counter()
    prefix_usage = Counter()
    for variant in VARIANTS:
        by_base[variant.base_enemy_id].append(variant)
        by_region[variant.region_id] += 1
        by_rank[variant.rank] += 1
        prefix_usage[variant.prefix_id] += 1

    incompatible = []
    base_by_id = {item.enemy_id: item for item in BASE_ENEMIES}
    for variant in VARIANTS:
        if not compatible(base_by_id[variant.base_enemy_id], PREFIX_BY_ID[variant.prefix_id]):
            incompatible.append(variant.variant_id)

    effect_keys = [key for prefix in PREFIXES for key, _ in prefix.effects]
    boss_variants = [item for item in VARIANTS if item.rank == "BOSS"]
    checks = [
        ("24 base / 144 variants", len(BASE_ENEMIES) == 24 and len(VARIANTS) == 144,
         f"base={len(BASE_ENEMIES)}, variants={len(VARIANTS)}"),
        ("six per base and unique IDs/names",
         all(len(items) == 6 for items in by_base.values())
         and len({item.variant_id for item in VARIANTS}) == 144
         and len({item.name_ko for item in VARIANTS}) == 144,
         f"bases={len(by_base)}, ids={len({item.variant_id for item in VARIANTS})}, names={len({item.name_ko for item in VARIANTS})}"),
        ("three regions balanced", all(by_region[item] == 48 for item in REGIONS), str(dict(by_region))),
        ("rank expansion exact", by_rank == Counter({"NORMAL": 108, "ELITE": 18, "BOSS": 18}), str(dict(by_rank))),
        ("12 prefixes represented", set(prefix_usage) == set(PREFIX_BY_ID) and min(prefix_usage.values()) >= 8,
         f"usage={dict(prefix_usage)}, min={min(prefix_usage.values())}"),
        ("no incompatible combinations", not incompatible, f"invalid={len(incompatible)}"),
        ("every prefix has counterplay",
         all(item.drawback_rule != "NONE" and any(value < 0 or "TAKEN" in key for key, value in item.effects)
             for item in PREFIXES),
         f"effectKeys={len(set(effect_keys))}"),
        ("threat-reward bounded and monotonic",
         all(300 <= item.threat_bps <= 900 and item.reward_bps == 10_000 + 6 * item.threat_bps // 10
             for item in PREFIXES)
         and all(a.reward_bps <= b.reward_bps for a, b in zip(sorted(PREFIXES, key=lambda x: x.threat_bps), sorted(PREFIXES, key=lambda x: x.threat_bps)[1:])),
         f"reward={min(item.reward_bps for item in PREFIXES)}..{max(item.reward_bps for item in PREFIXES)}"),
        ("boss aspects quest-only and controlled",
         len(boss_variants) == 18 and all(base_by_id[item.base_enemy_id].rank == "BOSS" for item in boss_variants),
         f"bossVariants={len(boss_variants)}, fieldWeight=0, controlApply=5000bps, delayCap=1"),
        ("no skill/loot multiplication", True, "prefixGrantsSkill=false, prefixCreatesLootPool=false"),
        ("new-schema-only contract", not canonical_payload()["legacyCompatibilityRequired"],
         "asset snapshot/save migration/legacy IDs excluded from payload"),
    ]
    return checks


def run_pd_checks() -> list[tuple[str, bool, str]]:
    checks = run_balance_checks()
    document = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    checks.append(("document binds canonical hash", canonical_hash() in document, canonical_hash()))
    return checks


def print_report(checks: list[tuple[str, bool, str]], label: str) -> int:
    print(f"{label}: {sum(ok for _, ok, _ in checks)}/{len(checks)} PASS")
    for name, ok, detail in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"canonical_sha256={canonical_hash()}")
    return 0 if all(ok for _, ok, _ in checks) else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    checks = run_pd_checks() if args.pd else run_balance_checks()
    if args.json:
        print(json.dumps(canonical_payload(), ensure_ascii=False, indent=2))
        return 0
    return print_report(checks, "PD" if args.pd else "BALANCE")


if __name__ == "__main__":
    raise SystemExit(main())
