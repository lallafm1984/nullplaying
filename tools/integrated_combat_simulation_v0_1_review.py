#!/usr/bin/env python3
"""Integrated vNext combat planning simulation for AlarmQuest.

Enumerates every combination of six classes, three starting-stat bands, five
equipment lineages, three representative Active-5/Passive-3 build envelopes,
four long-progression anchors, and all 144 monster variants.  It deliberately
does not read or preserve live/legacy content or save schemas.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, replace
from pathlib import Path

import base_combat_six_classes_v1_5_review as combat
import equipment_catalog_650_lineages_v0_2_review as equipment
import monster_prefix_variants_v0_2_review as monsters
import skill_collection_level9999_v0_1_review as progression
import skill_wave1_active5_passive3_v0_1_review as wave1


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_INTEGRATED_COMBAT_SIMULATION_v0.1.md"
RULES_VERSION = "aq.integrated-combat.v0.1"
DISPLAY_LEVELS = (1, 100, 1_000, 9_999)
SKILL_LEVELS = {1: 1, 100: 50, 1_000: 85, 9_999: 100}
STAT_BANDS = ("STRESS_ALL3", "BASE", "STRESS_ALL18")
LINEAGES = ("BALANCED", "VANGUARD", "BASTION", "SEEKER", "TRAILBLAZER")
BUILDS = ("OFFENSE", "BALANCED", "SUSTAIN")
RANK_CHALLENGE = {
    "NORMAL": {"attack_bps": 300, "hp_bps": 300, "defense_bps": 0},
    "ELITE": {"attack_bps": 2_500, "hp_bps": 3_500, "defense_bps": 1_000},
    "BOSS": {"attack_bps": 3_000, "hp_bps": 3_000, "defense_bps": 800},
}
EARLY_RANK_NORMALIZER = {
    # C1 training floors and starter equipment otherwise make upper ranks easier
    # than their long-horizon equivalents. This is an encounter-rank term only.
    1: {"attack_bps": 1_000, "hp_bps": 1_000, "defense_bps": 300},
}
COUNTER_BONUS = {
    "BALANCED": {"attack_bps": 200, "defense_bps": 200},
    "VANGUARD": {"attack_bps": 1_000, "defense_bps": 0},
    "BASTION": {"attack_bps": 0, "defense_bps": 1_000},
    "SEEKER": {"attack_bps": 1_500, "defense_bps": 0},
    "TRAILBLAZER": {"attack_bps": 0, "defense_bps": 1_000},
}
PREFIX_BEHAVIOR_PROXY = {
    "VENOMOUS": {"action_add_bps": 500, "hp_bps": 0, "attack_bps": 0},
    "EMBER": {"action_add_bps": 500, "hp_bps": 0, "attack_bps": 0},
    "FROSTBOUND": {"action_add_bps": 350, "hp_bps": 0, "attack_bps": 0},
    "CURSED": {"action_add_bps": 350, "hp_bps": 0, "attack_bps": 0},
    "REGENERATING": {"action_add_bps": 0, "hp_bps": 1_200, "attack_bps": 0},
    "HUNGRY": {"action_add_bps": 0, "hp_bps": -300, "attack_bps": 500},
}


@dataclass(frozen=True)
class LineageEnvelope:
    lineage_id: str
    attack_bps: int
    defense_bps: int
    hp_bps: int
    accuracy_flat: int
    evasion_bps: int
    speed_bps: int
    resource_roots: int
    counter_tags: tuple[str, ...]


@dataclass(frozen=True)
class BuildEnvelope:
    build_id: str
    attack_bps_lv1: int
    attack_bps_lv100: int
    defense_bps_lv1: int
    defense_bps_lv100: int
    hp_bps: int
    accuracy_flat: int
    resource_roots: int
    downside: str


@dataclass(frozen=True)
class Sample:
    display_level: int
    combat_rank: int
    skill_level: int
    hero_class: str
    stat_band: str
    lineage: str
    build: str
    variant_id: str
    prefix_id: str
    profile: str
    monster_rank: str
    outcome: str
    rounds: int
    hp_loss_bps: int
    hit_bps: int
    resource_pressure: bool
    failure_reason: str


LINEAGE_ENVELOPES = {
    "BALANCED": LineageEnvelope("BALANCED", 300, 400, 300, 2, 200, 0, 1, ("STANDARD", "PRECISE")),
    "VANGUARD": LineageEnvelope("VANGUARD", 1_200, -600, 0, 0, 0, 100, 0, ("ARMORED", "IRONHIDE", "REGENERATING", "ANCIENT")),
    "BASTION": LineageEnvelope("BASTION", -400, 1_500, 1_000, 0, 0, -300, 2, ("SPELLCASTER", "FEROCIOUS", "VENOMOUS", "FROSTBOUND", "EMBER", "CURSED")),
    "SEEKER": LineageEnvelope("SEEKER", 200, -100, 0, 8, 800, 600, 1, ("SWIFT", "QUICK")),
    "TRAILBLAZER": LineageEnvelope("TRAILBLAZER", 0, 600, 700, 2, 200, 200, 4, ("BOSS", "HUNGRY", "REGENERATING", "CURSED")),
}

BUILD_ENVELOPES = {
    # The envelope represents five Actives plus three Passives, not a new item skill.
    "OFFENSE": BuildEnvelope("OFFENSE", 800, 1_200, -500, -500, 0, 0, 8, "INCOMING_DAMAGE_PLUS_5_PERCENT"),
    "BALANCED": BuildEnvelope("BALANCED", 500, 900, 400, 600, 300, 2, 12, "NONE"),
    "SUSTAIN": BuildEnvelope("SUSTAIN", 200, 600, 800, 1_200, 800, 0, 16, "LOWER_BURST"),
}


def lerp_lv(value1: int, value100: int, skill_level: int) -> int:
    return round(value1 + (value100 - value1) * (skill_level - 1) / 99)


def mul(value: int, modifier_bps: int) -> int:
    return max(1, combat.v13.core.multiply_bps(value, 10_000 + modifier_bps))


def apply_hero_envelopes(hero, lineage_id: str, build_id: str, skill_level: int, tags: set[str]):
    lineage = LINEAGE_ENVELOPES[lineage_id]
    build = BUILD_ENVELOPES[build_id]
    counter_match = bool(tags.intersection(lineage.counter_tags))
    counter_attack = COUNTER_BONUS[lineage_id]["attack_bps"] if counter_match else 0
    counter_defense = COUNTER_BONUS[lineage_id]["defense_bps"] if counter_match else 0
    attack_bps = lineage.attack_bps + lerp_lv(build.attack_bps_lv1, build.attack_bps_lv100, skill_level) + counter_attack
    defense_bps = lineage.defense_bps + lerp_lv(build.defense_bps_lv1, build.defense_bps_lv100, skill_level) + counter_defense
    hp_bps = lineage.hp_bps + build.hp_bps
    accuracy = lineage.accuracy_flat + build.accuracy_flat
    return replace(
        hero,
        max_hp=mul(hero.max_hp, hp_bps),
        physical_attack=mul(hero.physical_attack, attack_bps),
        magical_attack=mul(hero.magical_attack, attack_bps),
        physical_defense=mul(hero.physical_defense, defense_bps),
        magical_resistance=mul(hero.magical_resistance, defense_bps),
        physical_accuracy=max(1, hero.physical_accuracy + accuracy),
        magical_accuracy=max(1, hero.magical_accuracy + accuracy),
        evasion=mul(hero.evasion, lineage.evasion_bps),
        speed=mul(hero.speed, lineage.speed_bps),
        action_coefficient_bps=10_000,
    )


def base_resistance_modifier(enemy: monsters.BaseEnemy, attack_type: str) -> int:
    damage_kind = "PHYSICAL" if attack_type == "PHYSICAL" else "MAGIC"
    resist = dict(enemy.damage_resists_bps).get(damage_kind, 0)
    vulnerable = dict(enemy.vulnerabilities_bps).get(damage_kind, 0)
    return resist - vulnerable


def apply_monster_variant(base, enemy: monsters.BaseEnemy, variant: monsters.MonsterVariant, hero_attack_type: str, display_level: int):
    changes = dict(variant.effects)
    rank_challenge = RANK_CHALLENGE[variant.rank]
    early = EARLY_RANK_NORMALIZER.get(display_level, {"attack_bps": 0, "hp_bps": 0, "defense_bps": 0})
    attack_bps = changes.get("ATTACK_BPS", 0) + rank_challenge["attack_bps"] + early["attack_bps"]
    defense_bps = changes.get("DEFENSE_BPS", 0) + rank_challenge["defense_bps"] + early["defense_bps"]
    hp_bps = changes.get("MAX_HP_BPS", 0) + rank_challenge["hp_bps"] + early["hp_bps"]
    speed_bps = changes.get("SPEED_BPS", 0)
    evasion_bps = changes.get("EVASION_BPS", 0)
    pdef_bps = defense_bps + changes.get("PDEF_BPS", 0)
    mres_bps = defense_bps + changes.get("MRES_BPS", 0)
    accuracy_flat = changes.get("HIT_FLAT", 0)
    critical_flat = changes.get("CRITICAL_FLAT", 0)
    proxy = PREFIX_BEHAVIOR_PROXY.get(variant.prefix_id, {})
    action_add = proxy.get("action_add_bps", 0)
    hp_bps += proxy.get("hp_bps", 0)
    attack_bps += proxy.get("attack_bps", 0)

    resistance = base_resistance_modifier(enemy, hero_attack_type)
    if hero_attack_type == "PHYSICAL":
        pdef_bps += resistance
    else:
        mres_bps += resistance

    return replace(
        base,
        name=variant.variant_id,
        max_hp=mul(base.max_hp, hp_bps),
        physical_attack=mul(base.physical_attack, attack_bps),
        magical_attack=mul(base.magical_attack, attack_bps),
        physical_defense=mul(base.physical_defense, pdef_bps),
        magical_resistance=mul(base.magical_resistance, mres_bps),
        physical_accuracy=max(1, base.physical_accuracy + accuracy_flat),
        magical_accuracy=max(1, base.magical_accuracy + accuracy_flat),
        evasion=mul(base.evasion, evasion_bps),
        critical=max(1, base.critical + critical_flat),
        speed=mul(base.speed, speed_bps),
        action_coefficient_bps=max(1, base.action_coefficient_bps + action_add),
    )


def stable_seed(*values: str) -> int:
    joined = "|".join(values)
    return combat.v13.core.stable_hash(joined) & 0x7FFF_FFFF


def failure_reason(result, hit_bps_value: int, prefix_id: str, monster_attack_type: str, resource_pressure: bool) -> str:
    if result.outcome == "WIN":
        return "NONE"
    if resource_pressure:
        return "RESOURCE_EMPTY"
    if hit_bps_value < 7_200:
        return "MISS_CHAIN"
    if prefix_id in {"VENOMOUS", "FROSTBOUND", "EMBER", "CURSED"}:
        return "STATUS_COLLAPSE"
    if result.outcome == "RETREAT":
        return "TTK_TIMEOUT"
    return "DEFEAT_MAGIC" if monster_attack_type == "MAGIC" else "DEFEAT_PHYSICAL"


def run_matrix() -> tuple[Sample, ...]:
    samples = []
    enemy_by_id = {item.enemy_id: item for item in monsters.BASE_ENEMIES}
    for display_level in DISPLAY_LEVELS:
        skill_level = SKILL_LEVELS[display_level]
        for hero_class in combat.CLASSES:
            for stat_band in STAT_BANDS:
                source = combat.source_for_display(hero_class, display_level, stat_band)
                raw_hero = combat.derive_hero(source).combatant
                for lineage_id in LINEAGES:
                    lineage = LINEAGE_ENVELOPES[lineage_id]
                    for build_id in BUILDS:
                        build = BUILD_ENVELOPES[build_id]
                        for variant in monsters.VARIANTS:
                            enemy = enemy_by_id[variant.base_enemy_id]
                            tags = {variant.profile, variant.rank, variant.prefix_id}
                            hero = apply_hero_envelopes(raw_hero, lineage_id, build_id, skill_level, tags)
                            raw_monster = combat.monster_for(source, variant.profile, variant.rank)
                            monster = apply_monster_variant(raw_monster, enemy, variant, hero.attack_type, display_level)
                            seed = stable_seed(str(display_level), hero_class, stat_band, lineage_id, build_id, variant.variant_id)
                            result = combat.v13.core.battle(hero, monster, seed=seed)
                            attempts = max(1, result.hero_attempts)
                            observed_hit_bps = 10_000 * result.hero_hits // attempts
                            capacity = build.resource_roots + lineage.resource_roots
                            pressure = result.hero_attempts > capacity
                            samples.append(Sample(
                                display_level=display_level,
                                combat_rank=source.combat_rank,
                                skill_level=skill_level,
                                hero_class=hero_class,
                                stat_band=stat_band,
                                lineage=lineage_id,
                                build=build_id,
                                variant_id=variant.variant_id,
                                prefix_id=variant.prefix_id,
                                profile=variant.profile,
                                monster_rank=variant.rank,
                                outcome=result.outcome,
                                rounds=result.rounds,
                                hp_loss_bps=max(0, 10_000 * (hero.max_hp - result.hero_hp) // hero.max_hp),
                                hit_bps=observed_hit_bps,
                                resource_pressure=pressure,
                                failure_reason=failure_reason(result, observed_hit_bps, variant.prefix_id, monster.attack_type, pressure),
                            ))
    return tuple(samples)


def rate(items: list[Sample] | tuple[Sample, ...], predicate) -> float:
    return sum(bool(predicate(item)) for item in items) / max(1, len(items))


def grouped_win(samples: tuple[Sample, ...], field: str, filters=None) -> dict[str | int, float]:
    groups = defaultdict(list)
    for item in samples:
        if filters is None or filters(item):
            groups[getattr(item, field)].append(item)
    return {key: rate(items, lambda row: row.outcome == "WIN") for key, items in groups.items()}


def counter_gain(samples: tuple[Sample, ...]) -> dict[str, float]:
    result = {}
    for lineage_id, envelope in LINEAGE_ENVELOPES.items():
        matched = [item for item in samples if item.stat_band == "BASE" and item.lineage == lineage_id
                   and ({item.profile, item.monster_rank, item.prefix_id} & set(envelope.counter_tags))]
        same_context = {(item.display_level, item.hero_class, item.build, item.variant_id) for item in matched}
        baseline = [item for item in samples if item.stat_band == "BASE" and item.lineage == "BALANCED"
                    and (item.display_level, item.hero_class, item.build, item.variant_id) in same_context]
        result[lineage_id] = rate(matched, lambda row: row.outcome == "WIN") - rate(baseline, lambda row: row.outcome == "WIN")
    return result


def summary(samples: tuple[Sample, ...]) -> dict:
    base = [item for item in samples if item.stat_band == "BASE"]
    low = [item for item in samples if item.stat_band == "STRESS_ALL3"]
    high = [item for item in samples if item.stat_band == "STRESS_ALL18"]
    rank_wins = grouped_win(tuple(base), "monster_rank")
    class_wins = grouped_win(tuple(base), "hero_class")
    lineage_wins = grouped_win(tuple(base), "lineage")
    build_wins = grouped_win(tuple(base), "build")
    level_wins = grouped_win(tuple(base), "display_level")
    prefix_wins = grouped_win(tuple(base), "prefix_id")
    return {
        "sampleCount": len(samples),
        "winByStatBand": {
            "LOW": rate(low, lambda x: x.outcome == "WIN"),
            "BASE": rate(base, lambda x: x.outcome == "WIN"),
            "HIGH": rate(high, lambda x: x.outcome == "WIN"),
        },
        "winByRank": rank_wins,
        "winByClass": class_wins,
        "winByLineage": lineage_wins,
        "winByBuild": build_wins,
        "winByDisplayLevel": level_wins,
        "winByPrefix": prefix_wins,
        "counterGain": counter_gain(samples),
        "resourcePressure": {build: rate([x for x in base if x.build == build], lambda x: x.resource_pressure) for build in BUILDS},
        "failureReasons": dict(Counter(item.failure_reason for item in samples if item.failure_reason != "NONE")),
        "medianRounds": statistics.median(item.rounds for item in samples),
        "p90HpLossBps": combat.v13.core.percentile([item.hp_loss_bps for item in samples], 0.90),
        "minObservedHitBpsP01": combat.v13.core.percentile([item.hit_bps for item in samples], 0.01),
    }


def canonical_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "monsterPrefixHash": monsters.canonical_hash(),
        "equipment650Hash": equipment.canonical_hash(),
        "wave1SkillHash": wave1.canonical_hash(),
        "displayLevels": DISPLAY_LEVELS,
        "combatRanks": {level: progression.combat_rank(level) for level in DISPLAY_LEVELS},
        "skillLevels": SKILL_LEVELS,
        "classes": combat.CLASSES,
        "statBands": STAT_BANDS,
        "lineages": [asdict(LINEAGE_ENVELOPES[key]) for key in LINEAGES],
        "builds": [asdict(BUILD_ENVELOPES[key]) for key in BUILDS],
        "rankChallenge": RANK_CHALLENGE,
        "earlyRankNormalizer": EARLY_RANK_NORMALIZER,
        "counterBonus": COUNTER_BONUS,
        "prefixBehaviorProxy": PREFIX_BEHAVIOR_PROXY,
        "activeSlots": 5,
        "passiveSlots": 3,
        "monsterVariants": 144,
        "legacyCompatibilityRequired": False,
        "manualCombatAction": False,
        "lossDeletesCharacter": False,
    }


def canonical_hash() -> str:
    raw = json.dumps(canonical_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def balance_checks(samples: tuple[Sample, ...]) -> list[tuple[str, bool, str]]:
    report = summary(samples)
    rank = report["winByRank"]
    stat = report["winByStatBand"]
    classes = report["winByClass"]
    levels = report["winByDisplayLevel"]
    lineages = report["winByLineage"]
    builds = report["winByBuild"]
    low_normal = [x for x in samples if x.stat_band == "STRESS_ALL3" and x.monster_rank == "NORMAL"]
    base_normal = [x for x in samples if x.stat_band == "BASE" and x.monster_rank == "NORMAL"]
    simulated_variants = {item.variant_id for item in samples}
    return [
        ("full matrix exact", len(samples) == 6 * 3 * 5 * 3 * 4 * 144, f"samples={len(samples)}"),
        ("all 144 variants simulated", simulated_variants == {item.variant_id for item in monsters.VARIANTS}, f"variants={len(simulated_variants)}"),
        ("rank difficulty ordered", rank["NORMAL"] >= rank["ELITE"] >= rank["BOSS"], str(rank)),
        ("normal automation reliable", rate(base_normal, lambda x: x.outcome == "WIN") >= 0.94,
         f"baseNormal={rate(base_normal, lambda x: x.outcome == 'WIN'):.3f}"),
        ("low-start recoverable", rate(low_normal, lambda x: x.outcome == "WIN") >= 0.88 and stat["LOW"] >= stat["BASE"] - 0.12,
         f"lowNormal={rate(low_normal, lambda x: x.outcome == 'WIN'):.3f}, bands={stat}"),
        ("high stats helpful but bounded", stat["HIGH"] >= stat["BASE"] and stat["HIGH"] - stat["LOW"] <= 0.18, str(stat)),
        ("six-class spread bounded", max(classes.values()) - min(classes.values()) <= 0.22, str(classes)),
        ("C1-C561 normalized stability", max(levels.values()) - min(levels.values()) <= 0.16, str(levels)),
        ("no dead lineage", max(lineages.values()) - min(lineages.values()) <= 0.16, str(lineages)),
        ("no dominant build", max(builds.values()) - min(builds.values()) <= 0.16, str(builds)),
        ("counter recommendations measurable", all(value >= 0 for value in report["counterGain"].values()), str(report["counterGain"])),
        ("sustain reduces resource pressure", report["resourcePressure"]["SUSTAIN"] < report["resourcePressure"]["BALANCED"] < report["resourcePressure"]["OFFENSE"], str(report["resourcePressure"])),
        ("bounded hit floor", report["minObservedHitBpsP01"] >= 5_000, f"p01={report['minObservedHitBpsP01']}"),
        ("loss always safe-return contract", canonical_payload()["lossDeletesCharacter"] is False, "deletion=0, return receipt required"),
        ("character and skill scaling separated", tuple(progression.combat_rank(x) for x in DISPLAY_LEVELS) == (1, 100, 330, 561)
         and tuple(SKILL_LEVELS[x] for x in DISPLAY_LEVELS) == (1, 50, 85, 100),
         f"rank={[progression.combat_rank(x) for x in DISPLAY_LEVELS]}, skill={[SKILL_LEVELS[x] for x in DISPLAY_LEVELS]}"),
        ("new schema only", canonical_payload()["legacyCompatibilityRequired"] is False, "legacy compatibility excluded"),
    ]


def pd_checks(samples: tuple[Sample, ...]) -> list[tuple[str, bool, str]]:
    checks = balance_checks(samples)
    document = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    checks.append(("document binds canonical hash", canonical_hash() in document, canonical_hash()))
    return checks


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()
    samples = run_matrix()
    if args.summary:
        print(json.dumps(summary(samples), ensure_ascii=False, indent=2, sort_keys=True))
        print(f"canonical_sha256={canonical_hash()}")
        return 0
    checks = pd_checks(samples) if args.pd else balance_checks(samples)
    print(f"{'PD' if args.pd else 'BALANCE'}: {sum(ok for _, ok, _ in checks)}/{len(checks)} PASS")
    for name, ok, detail in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"canonical_sha256={canonical_hash()}")
    return 0 if all(ok for _, ok, _ in checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
