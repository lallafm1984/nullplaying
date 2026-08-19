#!/usr/bin/env python3
"""Fail-closed balance/PD overlay for P6u Ore Memory.

This design-only verifier binds the frozen Registry/P5w/P6t contracts, checks
the production monster-profile projection, exercises the proposed root-scoped
ledger, and runs paired passive ON/OFF battles.  It never enables content or
mutates Kotlin, the resolver, saves, items, monsters, or skill definitions.
"""

from __future__ import annotations

import argparse
import base64
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass, field
from decimal import Decimal, ROUND_HALF_UP
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics

import base_combat_six_classes_v1_5_review as six
import monster_prefix_variants_v0_2_review as monsters
import skill_wave1_active5_passive3_v0_1_review as wave1


ROOT = Path(__file__).resolve().parents[1]
REGISTRY_RESOURCE = (
    ROOT
    / "game-engine/src/main/resources/com/alarmquest/engine/vnext/skill_registry_242_v0_3.json.gz.b64"
)
SOURCE_PATHS = {
    "REGISTRY": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/registry/VNextSkillRegistry242.kt",
    "P5W": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPassiveEventCatalogP5w.kt",
    "P6T": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextElementalResidueRuntimeP6t.kt",
    "P6U": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextOreMemoryRuntimeP6u.kt",
    "P6U_TEST": ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextOreMemoryRuntimeP6uTest.kt",
    "MONSTER": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextMonsterRegistry.kt",
    "SIX_CLASS_REVIEW": ROOT / "tools/base_combat_six_classes_v1_5_review.py",
    "WAVE1_REVIEW": ROOT / "tools/skill_wave1_active5_passive3_v0_1_review.py",
    "MONSTER_REVIEW": ROOT / "tools/monster_prefix_variants_v0_2_review.py",
}

# Registry/P5w/P6t are explicitly frozen.  The production monster source and
# simulation dependencies are also bound because they determine profile
# identity, CombatRank defenses, and the paired baseline.
EXPECTED_SOURCE_SHA256 = {
    "REGISTRY": "75d7cf39f8d79d47791b4688730d7decf791d557f0209589e701ae436f2ccf0f",
    "P5W": "d372626a96828f65694863303766ec1255255b5497d9eea7c31e48da4cb0b4fc",
    "P6T": "0ae43a776e2125f261189049a7217550fd7ab79947a5e6784fda5e369f801d94",
    "P6U": "191f864378372bb06d6e479039b6e7ad912c8cac02c8136f577b936b1b726c89",
    "P6U_TEST": "49dfb74792242edb4128c637163325c76af5c65278b7926cf1b04bc320523268",
    "MONSTER": "adf5a1fba934a33044bf1c6dc5e5867009286818b39205edefc8ecb5de3a87c8",
    "SIX_CLASS_REVIEW": "ed4af773eddf7aa175bcd063a9a047513a7b2b8a9716ef15a802bd71b4b6b597",
    "WAVE1_REVIEW": "5bef0e4c4174b8ac02b48a637a131493e58f860981d00567a4b7081a0409c9be",
    "MONSTER_REVIEW": "a4113d2184554058b5c34517d6e700eed5eb096cf0e5254fc941f041ce8cd174",
}
EXPECTED_CONSTANTS = {
    "REGISTRY": {
        "V_NEXT_REGISTRY_242_RULES_VERSION": "aq.skill-registry.v0.3",
        "V_NEXT_REGISTRY_242_CONTENT_HASH": "cda98479494d5d2b3d1279e695c26f923ce46249c8835e3e76996fccd67fbdca",
    },
    "P5W": {
        "V_NEXT_P5W_PASSIVE_EVENT_RULES_VERSION": "aq.passive-event-catalog.p5w.v0.1",
        "V_NEXT_P5W_PASSIVE_EVENT_CONTENT_HASH": "d68d4d94a01256b1289685991bd589545a8d30f02891d1119b9a5d9f8522b511",
    },
    "P6T": {
        "V_NEXT_P6T_ELEMENTAL_RESIDUE_RULES_VERSION": "aq.elemental-residue.p6t.v0.1",
        "V_NEXT_P6T_ELEMENTAL_RESIDUE_CONTENT_HASH": "d72837046be0bf48b5375ef97619551f3c549a4c75f07988dc77359c68675abf",
    },
    "P6U": {
        "V_NEXT_P6U_ORE_MEMORY_RULES_VERSION": "aq.ore-memory.p6u.v0.1",
        "V_NEXT_P6U_ORE_MEMORY_CONTENT_HASH": "dcb4cc93f92e0612bd7bf09e59fd62fa861980f1a130dca53e52765467f774ab",
    },
    "MONSTER": {
        "V_NEXT_MONSTER_RULES_VERSION": "aq.monster-registry.v0.1",
        "MONSTER_PREFIX_DESIGN_HASH": "08497905db406f0e0acdb82fbc25fc11f055710733aefe3e3c5bac430fcee4b6",
        "V_NEXT_MONSTER_CONTENT_HASH": "d928642af719d5b7077e311a38ca4878aeb0f9bfbca8a1d5ede321c944004055",
    },
}
EXPECTED_WAVE1_CANONICAL_SHA256 = "02246661576f1b664c902e80a09f6b39b7789b5bd2751b0da5e3c416abd0ebc3"
EXPECTED_MONSTER_CANONICAL_SHA256 = "08497905db406f0e0acdb82fbc25fc11f055710733aefe3e3c5bac430fcee4b6"

ORE_MEMORY_ID = "aq.skill.world.w7.orememory"
PRECISE_STRIKE = "aq.skill.common.w2.precisestrike"
DRAGON_SCALE = "aq.skill.external.w2.dragonscale"
GLASS_DESERT_HEAT = "aq.skill.world.w8.glassdesertheat"
DRAGON_PRIDE = "aq.skill.boss.w7.dragonpride"
UNDYING_OBSESSION = "aq.skill.boss.w8.undyingobsession"

ANCHOR_LEVELS = (1, 25, 50, 75, 100)
ORE_ANCHORS = (100, 200, 300, 400, 500)
DISPLAY_LEVELS = (58, 9_999)
CLASSES = tuple(six.CLASSES)
BEHAVIORS = tuple(wave1.legacy.c1.BEHAVIOR_MODES)
MONSTER_RANKS = ("NORMAL", "BOSS")
PROFILES = ("STANDARD", "SWIFT", "ARMORED", "SPELLCASTER")
SEQUENCES = (
    "BUILD3_DIRECT",
    "MISS_RESET",
    "TARGET_SWAP",
    "NONDIRECT_RESET",
    "NONARMORED_ZERO",
    "MULTIPACKET_BARRIER",
)

STACK_COUNT_CAP = 3
ORE_GLOBAL_PDEF_SHRED_CAP_BPS = 1_500
EXPECTED_P6U_HANDLED_AFTER_OUTGOING_IDS = frozenset(
    {
        "aq.skill.common.w8.elementalresidue",
        "aq.skill.common.w8.failureanalysis",
        "aq.skill.paladin.p3.othecho",
        "aq.skill.ranger.p2.distancesense",
        "aq.skill.rogue.p3.failurestudy",
        "aq.skill.rogue.w6.vanishedtrace",
        "aq.skill.rogue.w8.greedrhythm",
        "aq.skill.warrior.p3.shieldbreath",
        "aq.skill.cleric.p1.heardprayer",
        ORE_MEMORY_ID,
    }
)
EXPECTED_P6U_DEFERRED_AFTER_OUTGOING_IDS = frozenset(
    {
        "aq.skill.cleric.p2.faithecho",
        "aq.skill.mage.w4.overload",
        "aq.skill.paladin.w5.mercylimit",
        "aq.skill.warrior.w2.painconversion",
        "aq.skill.world.w8.desertadaptation",
        "aq.skill.world.w8.wellecho",
    }
)
EXPECTED_VARIANT_PROFILE_COUNTS = {
    "STANDARD": 36,
    "SWIFT": 42,
    "ARMORED": 36,
    "SPELLCASTER": 30,
}
PROFILE_WEIGHTS = {
    profile: count / 144.0 for profile, count in EXPECTED_VARIANT_PROFILE_COUNTS.items()
}

CLASS_PRIMARY_ACTIVE = {
    "WARRIOR": "aq.skill.warrior.a1.frontlinedrive",
    "ROGUE": "aq.skill.rogue.a1.seizeopening",
    "RANGER": "aq.skill.ranger.a1.steadyshot",
    "MAGE": "aq.skill.mage.a1.arcanepulse",
    "CLERIC": "aq.skill.cleric.a1.sacredradiance",
    "PALADIN": "aq.skill.paladin.a1.convictionstrike",
}
CLASS_PRIMARY_PACKET = {
    "WARRIOR": "PHYSICAL",
    "ROGUE": "PHYSICAL",
    "RANGER": "PHYSICAL",
    "MAGE": "MAGIC",
    "CLERIC": "MAGIC",
    "PALADIN": "PHYSICAL",
}
CLASS_LOADOUTS = {
    "WARRIOR": {
        "actives": (
            CLASS_PRIMARY_ACTIVE["WARRIOR"], DRAGON_SCALE, GLASS_DESERT_HEAT,
            "aq.skill.warrior.w4.bloodwhirl", PRECISE_STRIKE,
        ),
        "passives": (DRAGON_PRIDE, UNDYING_OBSESSION, ORE_MEMORY_ID),
    },
    "ROGUE": {
        "actives": (
            CLASS_PRIMARY_ACTIVE["ROGUE"], DRAGON_SCALE, GLASS_DESERT_HEAT,
            "aq.skill.rogue.w2.twinknifeflurry", PRECISE_STRIKE,
        ),
        "passives": (DRAGON_PRIDE, UNDYING_OBSESSION, ORE_MEMORY_ID),
    },
    "RANGER": {
        "actives": (
            CLASS_PRIMARY_ACTIVE["RANGER"], DRAGON_SCALE,
            "aq.skill.ranger.w2.rapidfire", "aq.skill.ranger.w4.stormpierce", PRECISE_STRIKE,
        ),
        "passives": (DRAGON_PRIDE, UNDYING_OBSESSION, ORE_MEMORY_ID),
    },
    "MAGE": {
        "actives": (
            CLASS_PRIMARY_ACTIVE["MAGE"], DRAGON_SCALE,
            "aq.skill.mage.w4.chainlightning", "aq.skill.mage.w4.unseal", PRECISE_STRIKE,
        ),
        "passives": (DRAGON_PRIDE, UNDYING_OBSESSION, ORE_MEMORY_ID),
    },
    "CLERIC": {
        "actives": (
            CLASS_PRIMARY_ACTIVE["CLERIC"], DRAGON_SCALE, GLASS_DESERT_HEAT,
            "aq.skill.cleric.w2.sacredchain", PRECISE_STRIKE,
        ),
        "passives": (DRAGON_PRIDE, UNDYING_OBSESSION, ORE_MEMORY_ID),
    },
    "PALADIN": {
        "actives": (
            CLASS_PRIMARY_ACTIVE["PALADIN"], DRAGON_SCALE, GLASS_DESERT_HEAT,
            "aq.skill.paladin.w2.resolveburst", PRECISE_STRIKE,
        ),
        "passives": (DRAGON_PRIDE, UNDYING_OBSESSION, ORE_MEMORY_ID),
    },
}

GATES = {
    "maxCellWinDeltaPp": 20.0,
    "maxMedianRoundDelta": 3.0,
    "maxP90HpLossDeltaPp": 18.0,
    "maxWeightedWinGainPp": 8.0,
    "maxPassiveCausedLossPp": 1.0,
}
FAST_REFERENCE_SEEDS = 24
PD_AUTHORITATIVE_SEEDS = 200


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_json_hash(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return sha256_bytes(encoded)


def load_registry() -> dict:
    encoded = "".join(REGISTRY_RESOURCE.read_text(encoding="utf-8").split())
    return json.loads(gzip.decompress(base64.b64decode(encoded)))


def extract_string_constant(source: str, name: str) -> str:
    match = re.search(rf"\b{re.escape(name)}(?:\s*:\s*String)?\s*=\s*\"([^\"]+)\"", source)
    if match is None:
        raise AssertionError(f"missing Kotlin constant: {name}")
    return match.group(1)


def registry_definition(registry: dict, kind: str, definition_id: str) -> dict:
    matches = [row for row in registry[kind] if row["definitionId"] == definition_id]
    if len(matches) != 1:
        raise AssertionError((kind, definition_id, len(matches)))
    return matches[0]


def value_at(values: tuple[int, ...] | list[int], skill_level: int) -> int:
    if len(values) != len(ANCHOR_LEVELS):
        raise AssertionError("five anchor values required")
    safe = max(1, min(100, int(skill_level)))
    for index, right_level in enumerate(ANCHOR_LEVELS):
        if safe == right_level:
            return int(values[index])
        if safe < right_level:
            left_level = ANCHOR_LEVELS[index - 1]
            ratio = Decimal(safe - left_level) / Decimal(right_level - left_level)
            resolved = Decimal(values[index - 1]) + Decimal(values[index] - values[index - 1]) * ratio
            return int(resolved.quantize(Decimal("1"), rounding=ROUND_HALF_UP))
    return int(values[-1])


def round_half_up_ratio(numerator: int, denominator: int) -> int:
    if numerator < 0 or denominator <= 0:
        raise AssertionError((numerator, denominator))
    return (numerator * 2 + denominator) // (denominator * 2)


def rounded_bps(value: int, bps: int) -> int:
    if value < 0 or bps < 0:
        raise AssertionError((value, bps))
    return round_half_up_ratio(value * bps, 10_000)


def check_registry_and_loadouts() -> None:
    registry = load_registry()
    assert canonical_json_hash(registry) == EXPECTED_CONSTANTS["REGISTRY"]["V_NEXT_REGISTRY_242_CONTENT_HASH"]
    assert registry["rulesVersion"] == "aq.skill-registry.v0.3"
    assert registry["anchors"] == list(ANCHOR_LEVELS)
    assert registry["slots"] == {"active": 5, "passive": 3}
    assert registry["skillLevelCap"] == 100
    assert registry["manualCombatActions"] is False
    expected_ore = {
        "anchorValues": list(ORE_ANCHORS),
        "conditionId": "same_armored_target_direct_hit",
        "definitionId": ORE_MEMORY_ID,
        "designWave": 7,
        "equipmentGrantedSkillIds": [],
        "fixedTradeoff": "3stack cap·MISS/대상 변경/비DIRECT에서 초기화·비ARMORED 0",
        "growthField": "pdef_shred_per_stack_bps",
        "hostScope": "SELF_OR_HOST_ACTION",
        "ideaId": "EXT017",
        "legacyAdapterId": "",
        "nameKo": "광맥의 기억",
        "ownerScope": "ALL",
        "pattern": "CONSECUTIVE_ARMORED_SHRED",
        "provisional": False,
        "roles": ["UTILITY"],
        "source": "WORLD_QUEST",
        "stackCapBps": ORE_GLOBAL_PDEF_SHRED_CAP_BPS,
        "stackGroup": "aq.stack.wave7.armor_shred",
        "stackPolicy": "ADD_THEN_CLAMP",
    }
    assert registry_definition(registry, "passives", ORE_MEMORY_ID) == expected_ore
    for class_name, loadout in CLASS_LOADOUTS.items():
        assert len(loadout["actives"]) == 5 and len(set(loadout["actives"])) == 5
        assert len(loadout["passives"]) == 3 and len(set(loadout["passives"])) == 3
        assert ORE_MEMORY_ID in loadout["passives"]
        assert CLASS_PRIMARY_ACTIVE[class_name] in loadout["actives"]
        assert PRECISE_STRIKE in loadout["actives"]
        assert all(
            registry_definition(registry, "actives", definition_id)["ownerScope"] in {"ALL", class_name}
            for definition_id in loadout["actives"]
        )
        assert all(
            registry_definition(registry, "passives", definition_id)["ownerScope"] in {"ALL", class_name}
            for definition_id in loadout["passives"]
        )


def check_exact_source_bindings() -> None:
    for source_name, path in SOURCE_PATHS.items():
        raw = path.read_bytes()
        assert sha256_bytes(raw) == EXPECTED_SOURCE_SHA256[source_name], source_name
        if source_name in EXPECTED_CONSTANTS:
            source = raw.decode("utf-8")
            for constant, expected in EXPECTED_CONSTANTS[source_name].items():
                assert extract_string_constant(source, constant) == expected, (source_name, constant)

    p5w_source = SOURCE_PATHS["P5W"].read_text(encoding="utf-8")
    p6t_source = SOURCE_PATHS["P6T"].read_text(encoding="utf-8")
    assert '"same_armored_target_direct_hit"' in p5w_source
    after_outgoing_block = p5w_source[p5w_source.index('"actual_overheal_gt_zero"'):]
    assert after_outgoing_block.index('"same_armored_target_direct_hit"') < after_outgoing_block.index(
        ") -> P5wPassiveEvent.AFTER_OUTGOING_PACKET"
    )
    assert "V_NEXT_P5W_PASSIVE_SLOT_CAP = 3" in p5w_source
    assert "val liveReady: Boolean get() = false" in p5w_source
    assert "V_NEXT_P6T_IMPLEMENTED_PASSIVE_DEFINITION_COUNT = 54" in p6t_source
    assert "V_NEXT_P6T_DEFERRED_AFTER_OUTGOING_COUNT = 7" in p6t_source
    assert "deferredAfterOutgoingIds = catalogIds.minus(handled).sorted()" in p6t_source
    assert wave1.canonical_hash() == EXPECTED_WAVE1_CANONICAL_SHA256
    assert monsters.canonical_hash() == EXPECTED_MONSTER_CANONICAL_SHA256


def production_variant_profile_counts() -> dict[str, int]:
    source = SOURCE_PATHS["MONSTER"].read_text(encoding="utf-8")
    base_start = source.index("    val baseMonsters: List<BaseMonsterDefinition> = listOf(")
    prefix_start = source.index("    val prefixes: List<MonsterPrefixDefinition> = listOf(", base_start)
    base_block = source[base_start:prefix_start]
    profiles = re.findall(r"\benemy\([^\n]+?MonsterProfile\.(STANDARD|SWIFT|ARMORED|SPELLCASTER)", base_block)
    assert len(profiles) == 24
    base_counts = {profile: profiles.count(profile) for profile in PROFILES}
    assert "value.variants.size == 144" in source
    assert "value.variants.groupingBy { it.baseMonsterId }.eachCount().values.all { it == 6 }" in source
    assert "profile = base.profile" in source
    counts = {profile: count * 6 for profile, count in base_counts.items()}
    return counts


def check_monster_profile_projection() -> None:
    counts = production_variant_profile_counts()
    assert counts == EXPECTED_VARIANT_PROFILE_COUNTS
    assert sum(counts.values()) == 144
    assert counts["ARMORED"] == 36
    assert sum(count for profile, count in counts.items() if profile != "ARMORED") == 108
    review_counts = {profile: sum(variant.profile == profile for variant in monsters.VARIANTS) for profile in PROFILES}
    assert review_counts == counts
    assert len(monsters.VARIANTS) == 144
    assert math.isclose(sum(PROFILE_WEIGHTS.values()), 1.0, abs_tol=1e-12)


def check_anchor_interpolation() -> None:
    assert tuple(value_at(ORE_ANCHORS, level) for level in ANCHOR_LEVELS) == ORE_ANCHORS
    values = tuple(value_at(ORE_ANCHORS, level) for level in range(1, 101))
    assert values[0] == 100 and values[-1] == 500
    assert all(left <= right for left, right in zip(values, values[1:]))
    assert value_at(ORE_ANCHORS, 58) == 332
    assert value_at(ORE_ANCHORS, 9_999) == 500
    assert max(values) * STACK_COUNT_CAP == ORE_GLOBAL_PDEF_SHRED_CAP_BPS


@dataclass
class OreLedger:
    target_identity: str | None = None
    stack_count: int = 0
    processed_root_receipts: set[str] = field(default_factory=set)
    committed_outcomes: int = 0
    resets: int = 0

    def snapshot(self) -> tuple[object, ...]:
        return (
            self.target_identity,
            self.stack_count,
            tuple(sorted(self.processed_root_receipts)),
            self.committed_outcomes,
            self.resets,
        )

    def assert_valid(self) -> None:
        if not 0 <= self.stack_count <= STACK_COUNT_CAP:
            raise AssertionError(self.stack_count)
        if self.stack_count == 0 and self.target_identity is not None:
            raise AssertionError((self.target_identity, self.stack_count))
        if self.stack_count > 0 and not self.target_identity:
            raise AssertionError((self.target_identity, self.stack_count))


@dataclass(frozen=True)
class OreResolution:
    stack_before: int
    stack_after: int
    nominal_shred_bps: int
    packet_shred_bps: tuple[int, ...]
    effective_pdef: tuple[int, ...]
    stack_built: int
    reset: bool
    extra_actions: int = 0
    resource_delta_bps: int = 0
    status_mutations: int = 0
    cooldown_delta_roots: int = 0


def resolve_ore_root(
    ledger: OreLedger,
    *,
    root_receipt_id: str,
    passive_equipped: bool,
    anchor_bps: int,
    target_identity: str,
    target_profile: str,
    direct: bool,
    landed: bool,
    packet_kinds: tuple[str, ...],
    canonical_pdef: int,
) -> OreResolution:
    """Apply the old stack to this root, then commit at most one new stack."""
    if not packet_kinds or any(kind not in {"PHYSICAL", "MAGIC", "TRUE"} for kind in packet_kinds):
        raise AssertionError(packet_kinds)
    if not 0 <= anchor_bps <= ORE_ANCHORS[-1] or canonical_pdef < 0:
        raise AssertionError((anchor_bps, canonical_pdef))
    ledger.assert_valid()
    before_snapshot = ledger.snapshot()
    if not passive_equipped:
        zeroes = (0,) * len(packet_kinds)
        assert ledger.snapshot() == before_snapshot
        return OreResolution(0, 0, 0, zeroes, (canonical_pdef,) * len(packet_kinds), 0, False)
    if root_receipt_id in ledger.processed_root_receipts:
        raise AssertionError("P6U_ROOT_RECEIPT_REPLAY")
    ledger.processed_root_receipts.add(root_receipt_id)

    qualifying_context = target_profile == "ARMORED" and direct
    same_target = qualifying_context and ledger.target_identity == target_identity
    stack_before = ledger.stack_count if same_target else 0
    nominal = min(ORE_GLOBAL_PDEF_SHRED_CAP_BPS, stack_before * anchor_bps)
    applied = tuple(
        nominal if landed and kind == "PHYSICAL" and qualifying_context else 0
        for kind in packet_kinds
    )
    effective = tuple(
        rounded_bps(canonical_pdef, 10_000 - shred_bps) for shred_bps in applied
    )

    stack_built = 0
    reset = False
    if qualifying_context and landed:
        next_stack = min(STACK_COUNT_CAP, stack_before + 1)
        stack_built = int(next_stack > stack_before)
        ledger.target_identity = target_identity
        ledger.stack_count = next_stack
    else:
        reset = ledger.stack_count > 0 or ledger.target_identity is not None
        ledger.target_identity = None
        ledger.stack_count = 0
        ledger.resets += int(reset)
    ledger.committed_outcomes += 1
    ledger.assert_valid()
    return OreResolution(
        stack_before=stack_before,
        stack_after=ledger.stack_count,
        nominal_shred_bps=nominal,
        packet_shred_bps=applied,
        effective_pdef=effective,
        stack_built=stack_built,
        reset=reset,
    )


def check_branch_matrix() -> None:
    pdef = 1_003
    state = OreLedger()
    rows = []
    for root in range(1, 5):
        rows.append(resolve_ore_root(
            state,
            root_receipt_id=f"build-{root}",
            passive_equipped=True,
            anchor_bps=500,
            target_identity="variant-a#1",
            target_profile="ARMORED",
            direct=True,
            landed=True,
            packet_kinds=("PHYSICAL",),
            canonical_pdef=pdef,
        ))
    assert [row.packet_shred_bps[0] for row in rows] == [0, 500, 1_000, 1_500]
    assert [row.stack_after for row in rows] == [1, 2, 3, 3]
    assert state.stack_count == 3 and pdef == 1_003
    assert rows[-1].effective_pdef == (853,)

    miss = resolve_ore_root(
        state,
        root_receipt_id="miss",
        passive_equipped=True,
        anchor_bps=500,
        target_identity="variant-a#1",
        target_profile="ARMORED",
        direct=True,
        landed=False,
        packet_kinds=("PHYSICAL",),
        canonical_pdef=pdef,
    )
    assert miss.packet_shred_bps == (0,) and miss.stack_after == 0 and miss.reset

    target_state = OreLedger()
    resolve_ore_root(
        target_state, root_receipt_id="ta1", passive_equipped=True, anchor_bps=500,
        target_identity="variant-a#1", target_profile="ARMORED", direct=True, landed=True,
        packet_kinds=("PHYSICAL",), canonical_pdef=pdef,
    )
    resolve_ore_root(
        target_state, root_receipt_id="ta2", passive_equipped=True, anchor_bps=500,
        target_identity="variant-a#1", target_profile="ARMORED", direct=True, landed=True,
        packet_kinds=("PHYSICAL",), canonical_pdef=pdef,
    )
    swapped = resolve_ore_root(
        target_state, root_receipt_id="tb1", passive_equipped=True, anchor_bps=500,
        target_identity="variant-a#2", target_profile="ARMORED", direct=True, landed=True,
        packet_kinds=("PHYSICAL",), canonical_pdef=pdef,
    )
    assert swapped.stack_before == 0 and swapped.packet_shred_bps == (0,) and swapped.stack_after == 1

    nondirect = resolve_ore_root(
        target_state, root_receipt_id="nondirect", passive_equipped=True, anchor_bps=500,
        target_identity="variant-a#2", target_profile="ARMORED", direct=False, landed=True,
        packet_kinds=("PHYSICAL",), canonical_pdef=pdef,
    )
    assert nondirect.packet_shred_bps == (0,) and nondirect.stack_after == 0 and nondirect.reset

    nonarmored = resolve_ore_root(
        target_state, root_receipt_id="nonarmored", passive_equipped=True, anchor_bps=500,
        target_identity="variant-high-pdef#1", target_profile="STANDARD", direct=True, landed=True,
        packet_kinds=("PHYSICAL",), canonical_pdef=pdef,
    )
    assert nonarmored.packet_shred_bps == (0,) and nonarmored.stack_after == 0

    magic_state = OreLedger()
    magic = resolve_ore_root(
        magic_state, root_receipt_id="magic", passive_equipped=True, anchor_bps=500,
        target_identity="variant-a#1", target_profile="ARMORED", direct=True, landed=True,
        packet_kinds=("MAGIC",), canonical_pdef=pdef,
    )
    physical_after_magic = resolve_ore_root(
        magic_state, root_receipt_id="physical", passive_equipped=True, anchor_bps=500,
        target_identity="variant-a#1", target_profile="ARMORED", direct=True, landed=True,
        packet_kinds=("PHYSICAL",), canonical_pdef=pdef,
    )
    assert magic.packet_shred_bps == (0,) and magic.stack_after == 1
    assert physical_after_magic.packet_shred_bps == (500,) and physical_after_magic.stack_after == 2

    barrier_state = OreLedger()
    barrier = resolve_ore_root(
        barrier_state, root_receipt_id="barrier", passive_equipped=True, anchor_bps=500,
        target_identity="variant-a#1", target_profile="ARMORED", direct=True, landed=True,
        packet_kinds=("PHYSICAL",), canonical_pdef=pdef,
    )
    assert barrier.stack_after == 1 and barrier.stack_built == 1

    multi_state = OreLedger()
    resolve_ore_root(
        multi_state, root_receipt_id="multi-primer", passive_equipped=True, anchor_bps=500,
        target_identity="variant-a#1", target_profile="ARMORED", direct=True, landed=True,
        packet_kinds=("PHYSICAL",), canonical_pdef=pdef,
    )
    multi = resolve_ore_root(
        multi_state, root_receipt_id="multi", passive_equipped=True, anchor_bps=500,
        target_identity="variant-a#1", target_profile="ARMORED", direct=True, landed=True,
        packet_kinds=("PHYSICAL", "PHYSICAL"), canonical_pdef=pdef,
    )
    assert multi.packet_shred_bps == (500, 500) and multi.stack_before == 1 and multi.stack_after == 2
    assert multi.stack_built == 1

    off_state = OreLedger(target_identity="sentinel#1", stack_count=2)
    off_before = off_state.snapshot()
    off = resolve_ore_root(
        off_state, root_receipt_id="off", passive_equipped=False, anchor_bps=500,
        target_identity="variant-a#1", target_profile="ARMORED", direct=True, landed=True,
        packet_kinds=("PHYSICAL", "MAGIC", "TRUE"), canonical_pdef=pdef,
    )
    assert off_state.snapshot() == off_before
    assert off.packet_shred_bps == (0, 0, 0) and off.effective_pdef == (pdef, pdef, pdef)
    assert all(
        row.extra_actions == row.resource_delta_bps == row.status_mutations == row.cooldown_delta_roots == 0
        for row in (*rows, miss, swapped, nondirect, nonarmored, magic, physical_after_magic, barrier, multi, off)
    )
    try:
        resolve_ore_root(
            multi_state, root_receipt_id="multi", passive_equipped=True, anchor_bps=500,
            target_identity="variant-a#1", target_profile="ARMORED", direct=True, landed=True,
            packet_kinds=("PHYSICAL",), canonical_pdef=pdef,
        )
    except AssertionError as error:
        assert str(error) == "P6U_ROOT_RECEIPT_REPLAY"
    else:
        raise AssertionError("root receipt replay was accepted")


def kotlin_set_ids(source: str, constant_name: str) -> frozenset[str]:
    match = re.search(
        rf"private val {re.escape(constant_name)} = setOf\((.*?)\n\)",
        source,
        flags=re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"missing Kotlin set: {constant_name}")
    values = set(re.findall(r'"([^"]+)"', match.group(1)))
    if re.search(r"\bORE_MEMORY\b", match.group(1)):
        values.add(ORE_MEMORY_ID)
    return frozenset(values)


def inspect_p6u_runtime_binding() -> dict[str, object]:
    path = SOURCE_PATHS["P6U"]
    test_path = SOURCE_PATHS["P6U_TEST"]
    source = path.read_text(encoding="utf-8")
    test_source = test_path.read_text(encoding="utf-8")
    rules = extract_string_constant(source, "V_NEXT_P6U_ORE_MEMORY_RULES_VERSION")
    content = extract_string_constant(source, "V_NEXT_P6U_ORE_MEMORY_CONTENT_HASH")
    assert rules == EXPECTED_CONSTANTS["P6U"]["V_NEXT_P6U_ORE_MEMORY_RULES_VERSION"]
    assert content == EXPECTED_CONSTANTS["P6U"]["V_NEXT_P6U_ORE_MEMORY_CONTENT_HASH"]
    assert kotlin_set_ids(source, "P6U_EXPECTED_HANDLED_AFTER_OUTGOING_IDS") == (
        EXPECTED_P6U_HANDLED_AFTER_OUTGOING_IDS
    )
    assert kotlin_set_ids(source, "P6U_EXPECTED_DEFERRED_AFTER_OUTGOING_IDS") == (
        EXPECTED_P6U_DEFERRED_AFTER_OUTGOING_IDS
    )
    required_anchors = {
        "definition one": "V_NEXT_P6U_ORE_MEMORY_DEFINITION_COUNT = 1",
        "implemented 55": "V_NEXT_P6U_IMPLEMENTED_PASSIVE_DEFINITION_COUNT = 55",
        "total 90": "V_NEXT_P6U_TOTAL_PASSIVE_DEFINITION_COUNT = 90",
        "off 35": "V_NEXT_P6U_OFF_PASSIVE_DEFINITION_COUNT = 35",
        "handled 10": "V_NEXT_P6U_HANDLED_AFTER_OUTGOING_COUNT = 10",
        "deferred 6": "V_NEXT_P6U_DEFERRED_AFTER_OUTGOING_COUNT = 6",
        "definition": ORE_MEMORY_ID,
        "anchors": "source.anchorValues != listOf(100, 200, 300, 400, 500)",
        "stack cap": "V_NEXT_P6U_STACK_COUNT_CAP = 3",
        "shred cap": "V_NEXT_P6U_PDEF_SHRED_CAP_BPS = 1_500",
        "single target": "V_NEXT_P6U_ENCOUNTER_TARGET_COUNT = 1",
        "profile": "targetProfile != MonsterProfile.ARMORED",
        "direct": "delivery != VNextRuntimeCarrierDeliveryV2.DIRECT",
        "physical": "damageChannel == VNextRuntimeDamageChannelV2.PHYSICAL",
        "old stack": "order=PREPARE_OLD_STACK_THEN_COMMIT_NEW_STACK",
        "barrier": "barrierHit=INCREMENT",
        "multi root": "multipacket=ONCE_PER_ROOT",
        "canonical pdef": '"CANONICAL_PDEF_NOT_RESTORED"',
        "ore only": '"P6U_V01_OTHER_PDEF_CONTRIBUTOR_UNSUPPORTED"',
        "handled coverage": "handledAfterOutgoingIds.toSet() == P6U_EXPECTED_HANDLED_AFTER_OUTGOING_IDS",
        "deferred coverage": "deferredAfterOutgoingIds.toSet() == P6U_EXPECTED_DEFERRED_AFTER_OUTGOING_IDS",
        "content coverage": "contentHash == V_NEXT_P6U_ORE_MEMORY_CONTENT_HASH",
        "live off": "val liveReady: Boolean get() = false",
    }
    missing = [name for name, anchor in required_anchors.items() if anchor not in source]
    assert not missing, missing
    assert "aq.skill.world.w7.minerspick" not in source
    assert "implemented=55/90|off=35" in source
    assert "event=AFTER_OUTGOING_PACKET|handled=10|deferred=6exact" in source
    test_anchors = (
        "coverage_descriptor_ownership_and_single_target_gate_are_exact",
        "five_skill_anchors_and_character_level_are_independent",
        "old_stack_applies_first_zero_and_fourth_level100_cap_1500",
        "barrier_shield_zero_hp_and_multi_packet_increment_once_per_root",
        "miss_swap_nonarmored_support_nondirect_execution_and_system_reset_exactly",
        "delayed_resolution_monster_tick_and_ai_preview_retain_exact_ledger",
        "magic_and_true_direct_build_stack_but_never_apply_pdef_shred",
        "nominal_composer_effective_delta_half_up_zero_and_long_are_separate",
        "forged_stale_replay_equal_sum_and_pdef_restore_fail_closed",
        "passive_absent_is_exact_no_op_without_receipt_or_ids",
    )
    assert all(anchor in test_source for anchor in test_anchors)
    return {
        "present": True,
        "path": str(path.relative_to(ROOT)),
        "testPath": str(test_path.relative_to(ROOT)),
        "rulesVersion": rules,
        "contentHash": content,
        "sourceSha256": sha256_bytes(path.read_bytes()),
        "testSha256": sha256_bytes(test_path.read_bytes()),
        "handledAfterOutgoingIds": sorted(EXPECTED_P6U_HANDLED_AFTER_OUTGOING_IDS),
        "deferredAfterOutgoingIds": sorted(EXPECTED_P6U_DEFERRED_AFTER_OUTGOING_IDS),
        "anchorsVerified": True,
    }


STATIC_CHECKS = (
    ("REGISTRY_EXACT_ORE_MEMORY_AND_ACTIVE5_PASSIVE3_ALL_CLASSES", check_registry_and_loadouts),
    ("REGISTRY_P5W_P6T_P6U_TEST_MONSTER_AND_SIMULATION_EXACT_SOURCE_BINDINGS", check_exact_source_bindings),
    ("PRODUCTION_144_VARIANTS_ARMORED36_NONARMORED108_PROFILE_ONLY", check_monster_profile_projection),
    ("SKILL_LEVEL_ANCHORS_L58_L9999_AND_DUAL_CAP", check_anchor_interpolation),
    ("OLD_STACK_FIRST_RESET_MAGIC_BARRIER_MULTIPACKET_REPLAY_BRANCH_MATRIX", check_branch_matrix),
)


@dataclass(frozen=True)
class CombatFixture:
    hero_max_hp: int
    monster_max_hp: int
    hero_physical_power: int
    hero_magical_power: int
    monster_power: int
    hero_physical_hit_bps: int
    hero_magical_hit_bps: int
    monster_hit_bps: int
    hero_crit_bps: int
    monster_crit_bps: int
    hero_mitigation_bps: int
    monster_pdef: int
    monster_mres: int
    combat_rank: int
    hero_speed: int
    monster_speed: int
    monster_coefficient_bps: int


@dataclass(frozen=True)
class RootAction:
    definition_id: str
    target_identity: str
    target_profile: str
    direct: bool
    forced_miss: bool
    packet_kinds: tuple[str, ...]
    barrier_blocks_all: bool


@dataclass(frozen=True)
class BattleResult:
    outcome: str
    rounds: int
    hp_loss_pct: float
    hero_damage: int
    hero_roots: int
    stack_builds: int
    physical_shred_packets: int
    magic_direct_builds: int
    barrier_landed_builds: int
    max_stack: int
    max_shred_bps: int
    canonical_pdef_unchanged: bool
    extra_actions: int = 0
    resource_delta_bps: int = 0
    status_mutations: int = 0
    cooldown_delta_roots: int = 0


@dataclass(frozen=True)
class Metrics:
    win_rate: float
    median_rounds: float
    p90_hp_loss: float


def make_combat_fixture(class_name: str, display_level: int, profile: str, monster_rank: str) -> CombatFixture:
    source = six.source_for_display(class_name, display_level, "BASE")
    hero = six.derive_hero(source).combatant
    monster = six.monster_for(source, profile, monster_rank)
    core = six.v13.core
    physical_hero = hero.__class__(**{**hero.__dict__, "attack_type": "PHYSICAL"})
    magical_hero = hero.__class__(**{**hero.__dict__, "attack_type": "MAGIC"})
    monster_power = monster.physical_attack if monster.attack_type == "PHYSICAL" else monster.magical_attack
    return CombatFixture(
        hero_max_hp=hero.max_hp,
        monster_max_hp=monster.max_hp,
        hero_physical_power=hero.physical_attack,
        hero_magical_power=hero.magical_attack,
        monster_power=monster_power,
        hero_physical_hit_bps=core.hit_bps(physical_hero, monster),
        hero_magical_hit_bps=core.hit_bps(magical_hero, monster),
        monster_hit_bps=core.hit_bps(monster, hero),
        hero_crit_bps=core.critical_bps(hero, monster),
        monster_crit_bps=core.critical_bps(monster, hero),
        hero_mitigation_bps=core.mitigation_bps(monster, hero),
        monster_pdef=monster.physical_defense,
        monster_mres=monster.magical_resistance,
        combat_rank=source.combat_rank,
        hero_speed=hero.speed,
        monster_speed=monster.speed,
        monster_coefficient_bps=monster.action_coefficient_bps,
    )


def root_action(class_name: str, sequence: str, root_index: int, fixture_profile: str) -> RootAction:
    slot = root_index % 5
    primary_kind = CLASS_PRIMARY_PACKET[class_name]
    if slot == 3:
        # The fourth landed root is the common low-power DIRECT host.  This
        # still exercises the full three-stack value without pretending that
        # every slot in a real Active5 loadout repeats the class A1 coefficient.
        packet_kinds = ("PHYSICAL",)
        definition_id = PRECISE_STRIKE
    elif primary_kind == "MAGIC" and slot <= 2:
        packet_kinds = ("MAGIC",)
        definition_id = CLASS_PRIMARY_ACTIVE[class_name]
    else:
        packet_kinds = ("PHYSICAL",)
        definition_id = CLASS_PRIMARY_ACTIVE[class_name] if primary_kind == "PHYSICAL" else PRECISE_STRIKE
    target = "encounter-variant#1"
    target_profile = fixture_profile
    # The fifth slot is the production-shaped utility/support boundary.  The
    # first four roots can demonstrate 0/1/2/3-stack application, then the
    # non-DIRECT slot resets the streak instead of assuming permanent cap uptime.
    direct = slot != 4
    forced_miss = False
    barrier = False
    if sequence == "MISS_RESET":
        forced_miss = slot == 2
    elif sequence == "TARGET_SWAP":
        target = "encounter-variant#1" if slot <= 1 else "encounter-variant#2"
    elif sequence == "NONDIRECT_RESET":
        direct = slot not in {2, 4}
    elif sequence == "NONARMORED_ZERO":
        target_profile = "STANDARD"
    elif sequence == "MULTIPACKET_BARRIER":
        definition_id = PRECISE_STRIKE
        packet_kinds = ("PHYSICAL", "PHYSICAL")
        barrier = slot == 0
    elif sequence != "BUILD3_DIRECT":
        raise AssertionError(sequence)
    return RootAction(
        definition_id=definition_id,
        target_identity=target,
        target_profile=target_profile,
        direct=direct,
        forced_miss=forced_miss,
        packet_kinds=packet_kinds,
        barrier_blocks_all=barrier,
    )


MASK_64 = (1 << 64) - 1


def mix64(value: int) -> int:
    value = (value + 0x9E3779B97F4A7C15) & MASK_64
    value = ((value ^ (value >> 30)) * 0xBF58476D1CE4E5B9) & MASK_64
    value = ((value ^ (value >> 27)) * 0x94D049BB133111EB) & MASK_64
    return value ^ (value >> 31)


def keyed_roll(seed_key: int, round_index: int, side: int, channel: int, stop: int) -> int:
    key = seed_key ^ (round_index * 0xD6E8FEB86659FD93) ^ (side * 0xA5A3564E27F8862B)
    key ^= channel * 0x9E3779B185EBCA87
    return mix64(key & MASK_64) % stop


def mitigation_from_defense(defense: int, combat_rank: int) -> int:
    scale = 40 + 5 * combat_rank
    return min(7_000, round_half_up_ratio(10_000 * defense, defense + scale))


def packet_damage(
    power: int,
    coefficient_bps: int,
    mitigation_bps: int,
    hit: bool,
    crit_roll: int,
    crit_bps: int,
    variance_bps: int,
    barrier_blocked: bool,
) -> int:
    if not hit:
        return 0
    damage = rounded_bps(power, coefficient_bps)
    damage = rounded_bps(damage, 15_000 if crit_roll < crit_bps else 10_000)
    damage = rounded_bps(damage, variance_bps)
    damage = max(1, rounded_bps(damage, 10_000 - mitigation_bps))
    return 0 if barrier_blocked else damage


def active_coefficient_bps(registry: dict, definition_id: str, skill_level: int) -> int:
    definition = registry_definition(registry, "actives", definition_id)
    return value_at(definition["attackEquivalentValues"], skill_level)


def simulate_battle(
    fixture: CombatFixture,
    *,
    class_name: str,
    active_coefficients: dict[str, int],
    ore_anchor_bps: int,
    behavior: str,
    sequence: str,
    fixture_profile: str,
    seed_key: int,
    ore_equipped: bool,
) -> BattleResult:
    hero_hp = fixture.hero_max_hp
    monster_hp = fixture.monster_max_hp
    canonical_pdef = fixture.monster_pdef
    ledger = OreLedger()
    behavior_coefficient = {"CAUTIOUS": -300, "BALANCED": 0, "BOLD": 300}[behavior]
    hero_roots = 0
    hero_damage = 0
    stack_builds = 0
    shred_packets = 0
    magic_builds = 0
    barrier_builds = 0
    max_stack = 0
    max_shred = 0

    tie_hero = keyed_roll(seed_key, 0, 0, 0, 2) == 0
    hero_first = fixture.hero_speed > fixture.monster_speed or (
        fixture.hero_speed == fixture.monster_speed and tie_hero
    )
    order = (0, 1) if hero_first else (1, 0)

    for round_index in range(1, 31):
        for side in order:
            if hero_hp <= 0 or monster_hp <= 0:
                break
            if side == 0:
                action = root_action(class_name, sequence, hero_roots, fixture_profile)
                total_coefficient = max(1, active_coefficients[action.definition_id] + behavior_coefficient)
                if len(action.packet_kinds) == 2:
                    coefficients = (total_coefficient // 2, total_coefficient - total_coefficient // 2)
                else:
                    coefficients = (total_coefficient,)
                hit_bps = (
                    fixture.hero_physical_hit_bps
                    if action.packet_kinds[0] == "PHYSICAL"
                    else fixture.hero_magical_hit_bps
                )
                hit_roll = keyed_roll(seed_key, round_index, 0, 1, 10_000)
                landed = not action.forced_miss and hit_roll < hit_bps
                resolution = resolve_ore_root(
                    ledger,
                    root_receipt_id=f"hero-root-{hero_roots + 1}",
                    passive_equipped=ore_equipped,
                    anchor_bps=ore_anchor_bps,
                    target_identity=action.target_identity,
                    target_profile=action.target_profile,
                    direct=action.direct,
                    landed=landed,
                    packet_kinds=action.packet_kinds,
                    canonical_pdef=canonical_pdef,
                )
                root_damage = 0
                for packet_index, (kind, coefficient, effective_pdef) in enumerate(
                    zip(action.packet_kinds, coefficients, resolution.effective_pdef)
                ):
                    crit_roll = keyed_roll(seed_key, round_index, 0, 2 + packet_index * 2, 10_000)
                    variance = 9_500 + keyed_roll(seed_key, round_index, 0, 3 + packet_index * 2, 1_001)
                    if kind == "PHYSICAL":
                        power = fixture.hero_physical_power
                        mitigation = mitigation_from_defense(effective_pdef, fixture.combat_rank)
                    elif kind == "MAGIC":
                        power = fixture.hero_magical_power
                        mitigation = mitigation_from_defense(fixture.monster_mres, fixture.combat_rank)
                    else:
                        power = fixture.hero_physical_power
                        mitigation = 0
                    root_damage += packet_damage(
                        power,
                        coefficient,
                        mitigation,
                        landed,
                        crit_roll,
                        fixture.hero_crit_bps,
                        variance,
                        action.barrier_blocks_all,
                    )
                monster_hp = max(0, monster_hp - root_damage)
                hero_damage += root_damage
                hero_roots += 1
                stack_builds += resolution.stack_built
                shred_packets += sum(value > 0 for value in resolution.packet_shred_bps)
                magic_builds += int(
                    landed and action.direct and action.target_profile == "ARMORED" and
                    all(kind == "MAGIC" for kind in action.packet_kinds) and resolution.stack_built > 0
                )
                barrier_builds += int(action.barrier_blocks_all and landed and resolution.stack_built > 0)
                max_stack = max(max_stack, resolution.stack_before, resolution.stack_after)
                max_shred = max(max_shred, resolution.nominal_shred_bps, *resolution.packet_shred_bps)
                assert canonical_pdef == fixture.monster_pdef
                assert resolution.extra_actions == resolution.resource_delta_bps == 0
                assert resolution.status_mutations == resolution.cooldown_delta_roots == 0
            else:
                hit_roll = keyed_roll(seed_key, round_index, 1, 1, 10_000)
                crit_roll = keyed_roll(seed_key, round_index, 1, 2, 10_000)
                variance = 9_500 + keyed_roll(seed_key, round_index, 1, 3, 1_001)
                damage = packet_damage(
                    fixture.monster_power,
                    fixture.monster_coefficient_bps,
                    fixture.hero_mitigation_bps,
                    hit_roll < fixture.monster_hit_bps,
                    crit_roll,
                    fixture.monster_crit_bps,
                    variance,
                    False,
                )
                hero_hp = max(0, hero_hp - damage)

        if monster_hp <= 0:
            outcome = "WIN"
            break
        if hero_hp <= 0:
            outcome = "LOSS"
            break
    else:
        round_index = 30
        outcome = "RETREAT"

    ledger.assert_valid()
    return BattleResult(
        outcome=outcome,
        rounds=round_index,
        hp_loss_pct=(fixture.hero_max_hp - hero_hp) * 100.0 / fixture.hero_max_hp,
        hero_damage=hero_damage,
        hero_roots=hero_roots,
        stack_builds=stack_builds,
        physical_shred_packets=shred_packets,
        magic_direct_builds=magic_builds,
        barrier_landed_builds=barrier_builds,
        max_stack=max_stack,
        max_shred_bps=max_shred,
        canonical_pdef_unchanged=canonical_pdef == fixture.monster_pdef,
    )


def percentile_nearest_rank(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def metrics(results: list[BattleResult]) -> Metrics:
    return Metrics(
        win_rate=sum(result.outcome == "WIN" for result in results) / len(results),
        median_rounds=float(statistics.median(result.rounds for result in results)),
        p90_hp_loss=percentile_nearest_rank([result.hp_loss_pct for result in results], 0.90),
    )


def stable_cell_salt(parts: tuple[object, ...]) -> int:
    encoded = "|".join(map(str, parts)).encode("utf-8")
    return int.from_bytes(hashlib.sha256(encoded).digest()[:8], "big")


def measure_class_overlay(class_name: str, seeds: int) -> dict:
    if class_name not in CLASSES:
        raise AssertionError(class_name)
    registry = load_registry()
    relevant_active_ids = {CLASS_PRIMARY_ACTIVE[class_name], PRECISE_STRIKE}
    active_coefficients_by_level = {
        skill_level: {
            definition_id: active_coefficient_bps(registry, definition_id, skill_level)
            for definition_id in relevant_active_ids
        }
        for skill_level in ANCHOR_LEVELS
    }
    ore_anchor_by_level = {level: value_at(ORE_ANCHORS, level) for level in ANCHOR_LEVELS}
    fixture_cache: dict[tuple[str, int, str, str], CombatFixture] = {}
    max_cell_win = 0.0
    max_rounds = 0.0
    max_hp = 0.0
    max_weighted_gain = 0.0
    max_passive_loss = 0.0
    worst_cell: tuple[object, ...] | None = None
    exact_no_effect_pairs = 0
    exact_no_effect_cells = 0
    paired_battles = 0
    cells = 0
    stack_builds = 0
    shred_packets = 0
    magic_builds = 0
    barrier_builds = 0
    on_hero_roots = 0
    max_stack = 0
    max_shred = 0
    sequence_shred_packets = {sequence: 0 for sequence in SEQUENCES}

    for display_level in DISPLAY_LEVELS:
        for skill_level in ANCHOR_LEVELS:
            for behavior in BEHAVIORS:
                for monster_rank in MONSTER_RANKS:
                    for sequence in SEQUENCES:
                        weighted_off = 0.0
                        weighted_on = 0.0
                        for profile in PROFILES:
                            fixture_key = (class_name, display_level, profile, monster_rank)
                            if fixture_key not in fixture_cache:
                                fixture_cache[fixture_key] = make_combat_fixture(*fixture_key)
                            fixture = fixture_cache[fixture_key]
                            salt = stable_cell_salt(
                                (class_name, display_level, skill_level, behavior, monster_rank, sequence, profile)
                            )
                            off_results = []
                            on_results = []
                            expected_exact_zero = profile != "ARMORED" or sequence == "NONARMORED_ZERO"
                            for seed in range(seeds):
                                seed_key = mix64(salt ^ ((seed + 1) * 0xD1342543DE82EF95))
                                kwargs = dict(
                                    fixture=fixture,
                                    class_name=class_name,
                                    active_coefficients=active_coefficients_by_level[skill_level],
                                    ore_anchor_bps=ore_anchor_by_level[skill_level],
                                    behavior=behavior,
                                    sequence=sequence,
                                    fixture_profile=profile,
                                    seed_key=seed_key,
                                )
                                off = simulate_battle(**kwargs, ore_equipped=False)
                                on = simulate_battle(**kwargs, ore_equipped=True)
                                assert off.extra_actions == on.extra_actions == 0
                                assert off.resource_delta_bps == on.resource_delta_bps == 0
                                assert off.status_mutations == on.status_mutations == 0
                                assert off.cooldown_delta_roots == on.cooldown_delta_roots == 0
                                assert off.stack_builds == off.physical_shred_packets == 0
                                assert off.max_stack == off.max_shred_bps == 0
                                assert off.canonical_pdef_unchanged and on.canonical_pdef_unchanged
                                assert on.max_stack <= STACK_COUNT_CAP
                                assert on.max_shred_bps <= ORE_GLOBAL_PDEF_SHRED_CAP_BPS
                                if expected_exact_zero:
                                    assert (off.outcome, off.rounds, off.hp_loss_pct, off.hero_damage) == (
                                        on.outcome, on.rounds, on.hp_loss_pct, on.hero_damage,
                                    )
                                    exact_no_effect_pairs += 1
                                off_results.append(off)
                                on_results.append(on)
                                stack_builds += on.stack_builds
                                shred_packets += on.physical_shred_packets
                                magic_builds += on.magic_direct_builds
                                barrier_builds += on.barrier_landed_builds
                                on_hero_roots += on.hero_roots
                                max_stack = max(max_stack, on.max_stack)
                                max_shred = max(max_shred, on.max_shred_bps)
                                sequence_shred_packets[sequence] += on.physical_shred_packets
                            off_metric = metrics(off_results)
                            on_metric = metrics(on_results)
                            cell_win_delta = abs(on_metric.win_rate - off_metric.win_rate) * 100.0
                            if cell_win_delta > max_cell_win:
                                max_cell_win = cell_win_delta
                                worst_cell = (
                                    class_name, display_level, skill_level, behavior,
                                    monster_rank, sequence, profile,
                                )
                            max_rounds = max(max_rounds, abs(on_metric.median_rounds - off_metric.median_rounds))
                            max_hp = max(max_hp, abs(on_metric.p90_hp_loss - off_metric.p90_hp_loss))
                            weighted_off += off_metric.win_rate * PROFILE_WEIGHTS[profile]
                            weighted_on += on_metric.win_rate * PROFILE_WEIGHTS[profile]
                            exact_no_effect_cells += int(expected_exact_zero)
                            cells += 1
                            paired_battles += seeds
                        max_weighted_gain = max(max_weighted_gain, (weighted_on - weighted_off) * 100.0)
                        max_passive_loss = max(max_passive_loss, (weighted_off - weighted_on) * 100.0)

    expected_cells = (
        len(DISPLAY_LEVELS) * len(ANCHOR_LEVELS) * len(BEHAVIORS) * len(MONSTER_RANKS) *
        len(SEQUENCES) * len(PROFILES)
    )
    expected_zero_cells = (
        len(DISPLAY_LEVELS) * len(ANCHOR_LEVELS) * len(BEHAVIORS) * len(MONSTER_RANKS) *
        (len(SEQUENCES) * 3 + 1)
    )
    assert cells == expected_cells
    assert paired_battles == expected_cells * seeds
    assert exact_no_effect_cells == expected_zero_cells
    assert exact_no_effect_pairs == expected_zero_cells * seeds
    assert stack_builds > 0 and shred_packets > 0
    return {
        "className": class_name,
        "cells": cells,
        "pairedBattles": paired_battles,
        "maxCellWinDeltaPp": max_cell_win,
        "maxMedianRoundDelta": max_rounds,
        "maxP90HpLossDeltaPp": max_hp,
        "maxWeightedWinGainPp": max_weighted_gain,
        "maxPassiveCausedLossPp": max_passive_loss,
        "worstCell": worst_cell,
        "exactNoEffectCells": exact_no_effect_cells,
        "exactNoEffectPairs": exact_no_effect_pairs,
        "stackBuilds": stack_builds,
        "physicalShredPackets": shred_packets,
        "magicDirectBuilds": magic_builds,
        "barrierLandedBuilds": barrier_builds,
        "onHeroRoots": on_hero_roots,
        "maxStack": max_stack,
        "maxShredBps": max_shred,
        "sequenceShredPackets": sequence_shred_packets,
        "reachable": stack_builds > 0 and shred_packets > 0,
    }


def measure_class_overlay_args(args: tuple[str, int]) -> dict:
    return measure_class_overlay(*args)


def measure_overlay(seeds: int, workers: int) -> dict:
    if workers > 1:
        with ProcessPoolExecutor(max_workers=workers) as executor:
            rows = list(executor.map(measure_class_overlay_args, ((name, seeds) for name in CLASSES)))
    else:
        rows = [measure_class_overlay(class_name, seeds) for class_name in CLASSES]

    worst_row = max(rows, key=lambda row: row["maxCellWinDeltaPp"])
    cell_win_quantum_pp = 100.0 / seeds
    cell_win_gate_pp = (
        GATES["maxCellWinDeltaPp"]
        if seeds >= PD_AUTHORITATIVE_SEEDS
        else math.ceil(GATES["maxCellWinDeltaPp"] / cell_win_quantum_pp - 1e-12) * cell_win_quantum_pp
    )
    summary = {
        "cells": sum(row["cells"] for row in rows),
        "pairedBattles": sum(row["pairedBattles"] for row in rows),
        "maxCellWinDeltaPp": max(row["maxCellWinDeltaPp"] for row in rows),
        "maxMedianRoundDelta": max(row["maxMedianRoundDelta"] for row in rows),
        "maxP90HpLossDeltaPp": max(row["maxP90HpLossDeltaPp"] for row in rows),
        "maxWeightedWinGainPp": max(row["maxWeightedWinGainPp"] for row in rows),
        "maxPassiveCausedLossPp": max(row["maxPassiveCausedLossPp"] for row in rows),
        "worstCell": worst_row["worstCell"],
        "exactNoEffectCells": sum(row["exactNoEffectCells"] for row in rows),
        "exactNoEffectPairs": sum(row["exactNoEffectPairs"] for row in rows),
        "maxStack": max(row["maxStack"] for row in rows),
        "maxShredBps": max(row["maxShredBps"] for row in rows),
        "sequenceShredPackets": {
            sequence: sum(row["sequenceShredPackets"][sequence] for row in rows)
            for sequence in SEQUENCES
        },
        "classReachability": {
            row["className"]: {
                "stackBuilds": row["stackBuilds"],
                "physicalShredPackets": row["physicalShredPackets"],
                "magicDirectBuilds": row["magicDirectBuilds"],
                "barrierLandedBuilds": row["barrierLandedBuilds"],
                "heroRoots": row["onHeroRoots"],
                "buildRatePct": row["stackBuilds"] * 100.0 / max(1, row["onHeroRoots"]),
                "useRatePct": row["physicalShredPackets"] * 100.0 / max(1, row["onHeroRoots"]),
                "reachable": row["reachable"],
            }
            for row in rows
        },
        "extraActions": 0,
        "resourceDeltaBps": 0,
        "statusMutations": 0,
        "cooldownDeltaRoots": 0,
        "canonicalPdefUnchanged": True,
        "workers": workers,
        "cellWinGatePp": cell_win_gate_pp,
        "authoritativePd": seeds >= PD_AUTHORITATIVE_SEEDS,
    }
    assert summary["maxCellWinDeltaPp"] <= summary["cellWinGatePp"] + 1e-12, summary
    assert summary["maxMedianRoundDelta"] <= GATES["maxMedianRoundDelta"] + 1e-12, summary
    assert summary["maxP90HpLossDeltaPp"] <= GATES["maxP90HpLossDeltaPp"] + 1e-12, summary
    assert summary["maxWeightedWinGainPp"] <= GATES["maxWeightedWinGainPp"] + 1e-12, summary
    assert summary["maxPassiveCausedLossPp"] <= GATES["maxPassiveCausedLossPp"] + 1e-12, summary
    assert summary["maxStack"] == STACK_COUNT_CAP
    assert summary["maxShredBps"] == ORE_GLOBAL_PDEF_SHRED_CAP_BPS
    assert summary["sequenceShredPackets"]["NONARMORED_ZERO"] == 0
    assert all(
        summary["sequenceShredPackets"][sequence] > 0
        for sequence in SEQUENCES if sequence != "NONARMORED_ZERO"
    )
    assert all(row["reachable"] for row in summary["classReachability"].values())

    expected_cells = (
        len(CLASSES) * len(DISPLAY_LEVELS) * len(ANCHOR_LEVELS) * len(BEHAVIORS) *
        len(MONSTER_RANKS) * len(SEQUENCES) * len(PROFILES)
    )
    expected_zero_cells = (
        len(CLASSES) * len(DISPLAY_LEVELS) * len(ANCHOR_LEVELS) * len(BEHAVIORS) *
        len(MONSTER_RANKS) * (len(SEQUENCES) * 3 + 1)
    )
    assert summary["cells"] == expected_cells == 8_640
    assert summary["pairedBattles"] == expected_cells * seeds
    assert summary["exactNoEffectCells"] == expected_zero_cells == 6_840
    assert summary["exactNoEffectPairs"] == expected_zero_cells * seeds
    return summary


def canonical_hash(runtime_binding: dict[str, object] | None = None) -> str:
    runtime = runtime_binding or inspect_p6u_runtime_binding()
    payload = {
        "rulesVersion": "aq.production-p6u.ore-memory.v0.1",
        "sourceSha256": EXPECTED_SOURCE_SHA256,
        "contentBindings": EXPECTED_CONSTANTS,
        "p6uRuntimeContentBinding": {
            "present": runtime["present"],
            "path": runtime["path"],
            "rulesVersion": runtime["rulesVersion"],
            "contentHash": runtime["contentHash"],
            "sourceSha256": runtime["sourceSha256"],
            "testSha256": runtime["testSha256"],
            "handledAfterOutgoingIds": runtime["handledAfterOutgoingIds"],
            "deferredAfterOutgoingIds": runtime["deferredAfterOutgoingIds"],
            "anchorsVerified": runtime["anchorsVerified"],
            "sourceShaPolicy": "EXACT_FROZEN_RUNTIME_AND_TEST",
        },
        "wave1CanonicalSha256": EXPECTED_WAVE1_CANONICAL_SHA256,
        "monsterCanonicalSha256": EXPECTED_MONSTER_CANONICAL_SHA256,
        "definitionId": ORE_MEMORY_ID,
        "classLoadouts": CLASS_LOADOUTS,
        "classPrimaryPacket": CLASS_PRIMARY_PACKET,
        "skillAnchorLevels": ANCHOR_LEVELS,
        "oreAnchors": ORE_ANCHORS,
        "displayLevels": DISPLAY_LEVELS,
        "classes": CLASSES,
        "behaviors": BEHAVIORS,
        "monsterRanks": MONSTER_RANKS,
        "profiles": PROFILES,
        "variantProfileCounts": EXPECTED_VARIANT_PROFILE_COUNTS,
        "profileWeights": PROFILE_WEIGHTS,
        "sequences": SEQUENCES,
        "stackCountCap": STACK_COUNT_CAP,
        "globalPdefShredCapBps": ORE_GLOBAL_PDEF_SHRED_CAP_BPS,
        "externalShredSources": [],
        "identityPolicy": "ENCOUNTER_LOCAL_TARGET_INSTANCE_ID_NOT_NAME_ABILITY_OR_PDEF",
        "profilePolicy": "ENCOUNTER_SNAPSHOT_PROFILE_ARMORED_ONLY",
        "ordering": "PREPARE_OLD_STACK_THEN_PACKET_THEN_COMMIT_ONE_ROOT_OUTCOME",
        "packetPolicy": "PHYSICAL_DIRECT_APPLIES_MAGIC_DIRECT_MAY_BUILD_TRUE_AND_NONDIRECT_ZERO",
        "barrierPolicy": "LANDED_DIRECT_BUILDS_EVEN_WHEN_ACTUAL_HP_DAMAGE_ZERO",
        "gates": GATES,
        "fastSeeds": FAST_REFERENCE_SEEDS,
        "pdSeeds": PD_AUTHORITATIVE_SEEDS,
        "cellWinGatePolicy": {
            "fast24": "SMOKE_ONLY_ONE_OVER_24_QUANTIZED_CEILING_20_84PP_NO_GO",
            "pd200": "AUTHORITATIVE_STRICT_20_00PP",
        },
        "extraActions": 0,
        "resourceDeltaBps": 0,
        "statusMutations": 0,
        "cooldownDeltaRoots": 0,
        "liveEnablement": False,
    }
    return canonical_json_hash(payload)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=24, help="paired seeds per cell in fast mode")
    parser.add_argument("--pd", action="store_true", help="run the frozen 200-seed PD matrix")
    parser.add_argument(
        "--workers",
        type=int,
        default=0,
        help="worker processes (0=auto: one in fast mode, up to six in PD mode)",
    )
    args = parser.parse_args()
    seeds = PD_AUTHORITATIVE_SEEDS if args.pd else args.seeds
    if seeds < 20:
        parser.error("--seeds must be at least 20")
    if args.workers < 0:
        parser.error("--workers must be zero or positive")
    workers = args.workers or (min(len(CLASSES), os.cpu_count() or 1) if args.pd else 1)
    workers = min(len(CLASSES), workers)

    passed = []
    for name, check in STATIC_CHECKS:
        check()
        passed.append(name)
    runtime_binding = inspect_p6u_runtime_binding()
    passed.append("P6U_RUNTIME_TEST_RULES_CONTENT_HANDLED_DEFERRED_EXACT_BINDING")
    dynamic = measure_overlay(seeds, workers)
    passed.extend(
        (
            "PAIRED_ON_OFF_8640_CELL_CLASS_LEVEL_SKILL_BEHAVIOR_RANK_PROFILE_SEQUENCE_MATRIX",
            "STACK3_SHRED1500_FIRST_HIT_TARGET_RESET_AND_ROOT_ONCE_INVARIANTS",
            "PHYSICAL_ONLY_NONARMORED_NONDIRECT_MAGIC_AND_PASSIVE_OFF_ZERO_INVARIANTS",
            "ALL_SIX_CLASSES_ACTIVE5_PASSIVE3_CONTROLLED_REACHABILITY",
            "CELL_ROUND_HP_WEIGHTED_GAIN_AND_PASSIVE_LOSS_GATES",
            "NO_CANONICAL_PDEF_ACTION_RESOURCE_STATUS_OR_COOLDOWN_MUTATION",
        )
    )

    tool_hash = sha256_bytes(Path(__file__).read_bytes())
    print(
        f"PRODUCTION_P6U_ORE_MEMORY_V0_1: PASS ({len(passed)}/{len(passed)}) "
        f"seeds={seeds} cells={dynamic['cells']} pairedBattles={dynamic['pairedBattles']} "
        f"workers={dynamic['workers']}"
    )
    print(f"  canonical_sha256={canonical_hash(runtime_binding)}")
    print(f"  tool_sha256={tool_hash}")
    print(
        "  bindings="
        + ",".join(
            f"{name}:{constants[next(key for key in constants if key.endswith('CONTENT_HASH'))][:12]}"
            for name, constants in EXPECTED_CONSTANTS.items()
        )
    )
    print(
        f"  runtimeP6u present={str(runtime_binding['present']).lower()} "
        f"path={runtime_binding['path']} rules={runtime_binding['rulesVersion']} "
        f"content={runtime_binding['contentHash']} sourceSha={runtime_binding['sourceSha256']} "
        f"testSha={runtime_binding['testSha256']} "
        f"handled={len(runtime_binding['handledAfterOutgoingIds'])} "
        f"deferred={len(runtime_binding['deferredAfterOutgoingIds'])} "
        f"anchorsVerified={str(runtime_binding['anchorsVerified']).lower()}"
    )
    print(
        "  gates "
        f"cellWinAbs={dynamic['maxCellWinDeltaPp']:.2f}pp/{dynamic['cellWinGatePp']:.2f}pp "
        f"cellWinNominal=20.00pp authority={'PD_STRICT' if dynamic['authoritativePd'] else 'SMOKE_ONLY_NO_GO'} "
        f"rounds={dynamic['maxMedianRoundDelta']:.1f}/3.0 "
        f"p90Hp={dynamic['maxP90HpLossDeltaPp']:.2f}pp/18.00pp "
        f"weightedGain={dynamic['maxWeightedWinGainPp']:.2f}pp/8.00pp "
        f"passiveLoss={dynamic['maxPassiveCausedLossPp']:.2f}pp/1.00pp"
    )
    print(
        "  gateStatus=10/10 "
        "[cellWin,medianRound,p90Hp,weightedGain,passiveLoss,stackCap,shredCap,"
        "canonicalPdef,noExtraEconomy,allClassReach]"
    )
    counts = production_variant_profile_counts()
    print(
        "  variants total=144 "
        + " ".join(f"{profile}={counts[profile]}" for profile in PROFILES)
        + " nonARMORED=108 profileOnly=true"
    )
    print(
        f"  invariants stackMax={dynamic['maxStack']} shredMaxBps={dynamic['maxShredBps']} "
        f"exactNoEffectCells={dynamic['exactNoEffectCells']} "
        f"exactNoEffectPairs={dynamic['exactNoEffectPairs']} canonicalPdefUnchanged=true "
        "extraActions=0 resourceDeltaBps=0 statusMutations=0 cooldownDeltaRoots=0"
    )
    print(
        "  physicalShredPacketsBySequence="
        + ",".join(f"{name}:{count}" for name, count in dynamic["sequenceShredPackets"].items())
    )
    for class_name in CLASSES:
        reach = dynamic["classReachability"][class_name]
        print(
            f"  {class_name:8} builds={reach['stackBuilds']} ({reach['buildRatePct']:.2f}%) "
            f"uses={reach['physicalShredPackets']} ({reach['useRatePct']:.2f}%) "
            f"magicBuilds={reach['magicDirectBuilds']} barrierBuilds={reach['barrierLandedBuilds']} "
            f"reachable={str(reach['reachable']).lower()}"
        )
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
