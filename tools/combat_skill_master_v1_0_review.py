#!/usr/bin/env python3
"""AlarmQuest integrated combat/skill master balance harness v1.0.

This is a planning and test-only executable.  It binds Registry242 full 5+3
loadouts to the existing vNext combat, equipment, monster and v0.9 automatic
behavior contracts, enumerates the exact 466,560-cell core matrix, and proves
4+2, 0+0, SkillXP, offline replay and regional settlement boundaries.

The skill effects are represented by a bounded Registry242-derived combat
envelope.  This does not claim that all 242 definitions are wired to the live
production resolver, Room schema, UI, or settlement path.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, replace
from functools import lru_cache
from pathlib import Path

import auto_battle_ai_loadout_v0_9_review as auto_ai
import base_combat_six_classes_v1_5_review as combat
import equipment_catalog_650_lineages_v0_2_review as equipment
import integrated_combat_simulation_v0_1_review as integrated
import monster_prefix_variants_v0_2_review as monsters
import skill_collection_level9999_v0_1_review as progression
import skill_effect_resolver_v0_3_review as resolver
import skill_registry_242_v0_3_review as registry242


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_COMBAT_SKILL_MASTER_v1.0.md"
KOTLIN_CONTRACT = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextCombatSkillMaster.kt"
KOTLIN_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextCombatSkillMasterTest.kt"

RULES_VERSION = "aq.combat-skill-master.v1.0"
DISPLAY_LEVELS = (1, 100, 1_000, 9_999)
SKILL_LEVELS = {1: 1, 100: 50, 1_000: 85, 9_999: 100}
STAT_TIERS = ("STRESS_ALL3", "BASE", "STRESS_ALL18")
EQUIPMENT_LINEAGES = ("BALANCED", "VANGUARD", "BASTION", "SEEKER", "TRAILBLAZER")
BUILD_PROVENANCES = ("OFFENSE", "CONTROL", "SUSTAIN")
BEHAVIOR_POLICIES = ("CAUTIOUS", "BALANCED", "BOLD")
FULL_LAYOUT = (5, 3)
NEW_LAYOUT = (4, 2)
EMPTY_LAYOUT = (0, 0)
MAX_ROUNDS = 30
MAX_ACTIONS = 60
CORE_SCENARIO_COUNT = 6 * 3 * 5 * 3 * 3 * 4 * 144

REGISTRY_242_HASH = registry242.canonical_hash()
AUTO_AI_V09_HASH = auto_ai.canonical_hash()
AUTO_AI_V09_INTEROP_HASH = auto_ai.interop_hash()
INTEGRATED_V01_HASH = integrated.canonical_hash()
MONSTER_HASH = monsters.canonical_hash()
EQUIPMENT_HASH = equipment.canonical_hash()

# Build provenance determines only which stable 5+3 IDs are equipped.  There
# is deliberately no OFFENSE/CONTROL/SUSTAIN runtime-stat table.  The generic
# handler proxy below reads the selected definitions and applies one universal,
# capped conversion until the production handlers are connected.
HANDLER_PROXY_CAPS = {
    "attackAdjustmentBps": (-300, 600),
    "defenseAdjustmentBps": (0, 900),
    "hpAdjustmentBps": (0, 700),
    "accuracyFlat": (0, 8),
    "evasionAdjustmentBps": (0, 500),
    "resourceRoots": (6, 20),
}

# Behavior is only an AI priority/resource-reserve axis.  These values are
# copied from v0.9 and never enter hero or monster combat statistics.
BEHAVIOR_RESOURCE_FLOORS = auto_ai.RESOURCE_FLOORS


@dataclass(frozen=True)
class BuildProvenance:
    hero_class: str
    build_id: str
    active_ids: tuple[str, ...]
    passive_ids: tuple[str, ...]
    selection_digest: str


@dataclass(frozen=True)
class RegistryHandlerEnvelope:
    hero_class: str
    build_id: str
    skill_level: int
    provenance_digest: str
    handler_counts: tuple[tuple[str, int], ...]
    active_attack_signal_bps: int
    active_defense_signal_bps: int
    active_heal_signal_bps: int
    active_precision_count: int
    active_control_count: int
    active_resource_count: int
    passive_attack_signal_bps: int
    passive_defense_signal_bps: int
    passive_precision_signal_bps: int
    passive_resource_signal_bps: int
    attack_bps: int
    defense_bps: int
    hp_bps: int
    accuracy_flat: int
    evasion_bps: int
    resource_roots: int


@dataclass(frozen=True)
class CellResult:
    display_level: int
    skill_level: int
    hero_class: str
    stat_tier: str
    equipment_lineage: str
    build_provenance: str
    behavior_policy: str
    variant_id: str
    region_id: str
    monster_rank: str
    seed: int
    loadout_digest: str
    outcome: str
    rounds: int
    hp_loss_bps: int
    hit_bps: int
    resource_pressure: bool
    failure_reason: str


@dataclass(frozen=True)
class MatrixReport:
    scenario_count: int
    matrix_digest: str
    unique_seed_count: int
    variant_counts: tuple[tuple[str, int], ...]
    region_counts: tuple[tuple[str, int], ...]
    outcomes: tuple[tuple[str, int], ...]
    wins_by_axis: tuple[tuple[str, tuple[tuple[str, int], ...]], ...]
    totals_by_axis: tuple[tuple[str, tuple[tuple[str, int], ...]], ...]
    failure_reasons: tuple[tuple[str, int], ...]
    resource_pressure_by_build: tuple[tuple[str, tuple[int, int]], ...]
    resource_pressure_by_behavior: tuple[tuple[str, tuple[int, int]], ...]
    hit_histogram: tuple[tuple[int, int], ...]
    max_rounds: int
    max_actions: int


@dataclass(frozen=True)
class MasterSettlementReceipt:
    receipt_id: str
    region_id: str
    variant_id: str
    outcome: str
    eligible_combat_xp: int
    reward_units: int


@dataclass(frozen=True)
class MasterSettlementState:
    character_xp: int = 0
    skill_xp_by_id: tuple[tuple[str, int], ...] = ()
    reward_by_region: tuple[tuple[str, int], ...] = ()
    rare_material_eligible_receipts: tuple[str, ...] = ()
    seen_receipt_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class MasterSettlementApply:
    status: str
    state: MasterSettlementState
    skill_grants: tuple[tuple[str, int], ...] = ()
    character_xp_grant: int = 0
    reward_grant: int = 0
    rare_material_eligible: bool = False


def canonical_json(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_payload(value) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def clamp(value: int, minimum: int, maximum: int) -> int:
    return max(minimum, min(maximum, value))


def stable_seed(*values: str) -> int:
    raw = "|".join(values).encode("utf-8")
    return int.from_bytes(hashlib.sha256(raw).digest()[:8], "big") & 0x7FFF_FFFF_FFFF_FFFF


@lru_cache(maxsize=None)
def build_provenance(hero_class: str, build_id: str) -> BuildProvenance:
    if hero_class not in registry242.CLASSES or build_id not in BUILD_PROVENANCES:
        raise ValueError((hero_class, build_id))
    active_pool = sorted(
        registry242.accessible(hero_class, "ACTIVE"),
        key=lambda item: (-resolver.role_score(item, build_id), item.definition_id),
    )
    active = []
    for desired_handler in resolver.ACTIVE_SLOT_HANDLERS[build_id]:
        candidate = next(
            (item for item in active_pool if item not in active and resolver.handler_id(item) == desired_handler),
            None,
        )
        if candidate is None:
            candidate = next(item for item in active_pool if item not in active)
        active.append(candidate)

    passive_pool = sorted(
        registry242.accessible(hero_class, "PASSIVE"),
        key=lambda item: (-resolver.role_score(item, build_id), item.definition_id),
    )
    passive = []
    for desired_role in resolver.PASSIVE_SLOT_ROLES[build_id]:
        candidate = next(
            (item for item in passive_pool if item not in passive and desired_role in item.roles),
            None,
        )
        if candidate is None:
            candidate = next(item for item in passive_pool if item not in passive)
        passive.append(candidate)

    active_ids = tuple(item.definition_id for item in active)
    passive_ids = tuple(item.definition_id for item in passive)
    digest = sha256_payload({
        "registry242Hash": REGISTRY_242_HASH,
        "heroClass": hero_class,
        "buildId": build_id,
        "activeIds": active_ids,
        "passiveIds": passive_ids,
    })
    return BuildProvenance(hero_class, build_id, active_ids, passive_ids, digest)


@lru_cache(maxsize=None)
def committed_loadout(hero_class: str, build_id: str, behavior: str, skill_level: int):
    provenance = build_provenance(hero_class, build_id)
    snapshot = registry242.commit_loadout(
        hero_class,
        {skill_id: skill_level for skill_id in provenance.active_ids},
        {skill_id: skill_level for skill_id in provenance.passive_ids},
        behavior_policy_id=f"aq.behavior.{behavior.lower()}.v1",
    )
    if len(snapshot.active_slots) != FULL_LAYOUT[0] or len(snapshot.passive_slots) != FULL_LAYOUT[1]:
        raise AssertionError("core matrix requires Registry242 full 5+3")
    if snapshot.content_hash != REGISTRY_242_HASH:
        raise AssertionError("loadout snapshot is not bound to Registry242")
    return snapshot


def passive_role_signal(passive_ids: tuple[str, ...], role: str, skill_level: int) -> int:
    values = [
        abs(registry242.value_at(registry242.PASSIVE_BY_ID[skill_id].anchor_values, skill_level))
        for skill_id in passive_ids
        if role in registry242.PASSIVE_BY_ID[skill_id].roles
    ]
    return sum(values)


@lru_cache(maxsize=None)
def registry_handler_envelope(hero_class: str, build_id: str, skill_level: int) -> RegistryHandlerEnvelope:
    provenance = build_provenance(hero_class, build_id)
    active_items = [registry242.ACTIVE_BY_ID[skill_id] for skill_id in provenance.active_ids]
    active_values = [registry242.value_at(item.attack_equivalent_values, skill_level) for item in active_items]
    active_attack = sum(active_values) // len(active_values)
    handler_counts = Counter(resolver.handler_id(item) for item in active_items)
    active_defense = sum(
        abs(registry242.value_at(item.anchor_values, skill_level))
        for item in active_items if "DEFENSE" in item.roles
    )
    active_heal = sum(
        abs(registry242.value_at(item.anchor_values, skill_level))
        for item in active_items if "HEAL" in item.roles
    )
    active_precision_count = sum("PRECISION" in item.roles for item in active_items)
    active_control_count = sum("CONTROL" in item.roles or "STATUS" in item.roles for item in active_items)
    active_resource_count = sum("RESOURCE" in item.roles for item in active_items)
    passive_attack = passive_role_signal(provenance.passive_ids, "ATTACK", skill_level)
    passive_defense = passive_role_signal(provenance.passive_ids, "DEFENSE", skill_level)
    passive_precision = passive_role_signal(provenance.passive_ids, "PRECISION", skill_level)
    passive_resource = passive_role_signal(provenance.passive_ids, "RESOURCE", skill_level)
    # Universal conversion from selected handler outputs. The archetype ID is
    # never consulted here, except as provenance needed to resolve those IDs.
    attack_adjust = clamp(
        (active_attack - 8_000) // 18 + passive_attack // 35,
        *HANDLER_PROXY_CAPS["attackAdjustmentBps"],
    )
    defense_adjust = clamp(
        active_defense // 20 + passive_defense // 8,
        *HANDLER_PROXY_CAPS["defenseAdjustmentBps"],
    )
    hp_adjust = clamp(
        active_heal // 20 + passive_role_signal(provenance.passive_ids, "HEAL", skill_level) // 8,
        *HANDLER_PROXY_CAPS["hpAdjustmentBps"],
    )
    accuracy = clamp(
        active_precision_count * 2 + passive_precision // 400,
        *HANDLER_PROXY_CAPS["accuracyFlat"],
    )
    evasion = clamp(
        active_control_count * 100 + passive_defense // 10,
        *HANDLER_PROXY_CAPS["evasionAdjustmentBps"],
    )
    resource_roots = clamp(
        6 + active_resource_count * 3 + passive_resource // 250,
        *HANDLER_PROXY_CAPS["resourceRoots"],
    )
    return RegistryHandlerEnvelope(
        hero_class=hero_class,
        build_id=build_id,
        skill_level=skill_level,
        provenance_digest=provenance.selection_digest,
        handler_counts=tuple(sorted(handler_counts.items())),
        active_attack_signal_bps=active_attack,
        active_defense_signal_bps=active_defense,
        active_heal_signal_bps=active_heal,
        active_precision_count=active_precision_count,
        active_control_count=active_control_count,
        active_resource_count=active_resource_count,
        passive_attack_signal_bps=passive_attack,
        passive_defense_signal_bps=passive_defense,
        passive_precision_signal_bps=passive_precision,
        passive_resource_signal_bps=passive_resource,
        attack_bps=attack_adjust,
        defense_bps=defense_adjust,
        hp_bps=hp_adjust,
        accuracy_flat=accuracy,
        evasion_bps=evasion,
        resource_roots=resource_roots,
    )


def apply_master_hero(raw_hero, lineage_id: str, handler: RegistryHandlerEnvelope,
                      variant):
    lineage = integrated.LINEAGE_ENVELOPES[lineage_id]
    tags = {variant.profile, variant.rank, variant.prefix_id}
    counter_match = bool(tags.intersection(lineage.counter_tags))
    counter_attack = integrated.COUNTER_BONUS[lineage_id]["attack_bps"] if counter_match else 0
    counter_defense = integrated.COUNTER_BONUS[lineage_id]["defense_bps"] if counter_match else 0
    attack_bps = lineage.attack_bps + handler.attack_bps + counter_attack
    defense_bps = lineage.defense_bps + handler.defense_bps + counter_defense
    hp_bps = lineage.hp_bps + handler.hp_bps
    accuracy = lineage.accuracy_flat + handler.accuracy_flat
    evasion_bps = lineage.evasion_bps + handler.evasion_bps
    return replace(
        raw_hero,
        max_hp=integrated.mul(raw_hero.max_hp, hp_bps),
        physical_attack=integrated.mul(raw_hero.physical_attack, attack_bps),
        magical_attack=integrated.mul(raw_hero.magical_attack, attack_bps),
        physical_defense=integrated.mul(raw_hero.physical_defense, defense_bps),
        magical_resistance=integrated.mul(raw_hero.magical_resistance, defense_bps),
        physical_accuracy=max(1, raw_hero.physical_accuracy + accuracy),
        magical_accuracy=max(1, raw_hero.magical_accuracy + accuracy),
        evasion=integrated.mul(raw_hero.evasion, evasion_bps),
        speed=integrated.mul(raw_hero.speed, lineage.speed_bps),
        action_coefficient_bps=10_000,
    )


@lru_cache(maxsize=None)
def source_and_hero(display_level: int, hero_class: str, stat_tier: str):
    source = combat.source_for_display(hero_class, display_level, stat_tier)
    return source, combat.derive_hero(source).combatant


ENEMY_BY_ID = {item.enemy_id: item for item in monsters.BASE_ENEMIES}
VARIANT_BY_ID = {item.variant_id: item for item in monsters.VARIANTS}


def simulate_cell(display_level: int, hero_class: str, stat_tier: str,
                  lineage_id: str, build_id: str, behavior_id: str, variant) -> CellResult:
    skill_level = SKILL_LEVELS[display_level]
    source, raw_hero = source_and_hero(display_level, hero_class, stat_tier)
    snapshot = committed_loadout(hero_class, build_id, behavior_id, skill_level)
    envelope = registry_handler_envelope(hero_class, build_id, skill_level)
    hero = apply_master_hero(raw_hero, lineage_id, envelope, variant)
    enemy = ENEMY_BY_ID[variant.base_enemy_id]
    raw_monster = combat.monster_for(source, variant.profile, variant.rank)
    monster = integrated.apply_monster_variant(raw_monster, enemy, variant, hero.attack_type, display_level)
    # Common random numbers across behavior policies isolate the policy axis
    # from power. Behavior can alter candidate priority/reserve, never stats.
    seed = stable_seed(
        str(display_level), hero_class, stat_tier, lineage_id, build_id,
        variant.variant_id,
    )
    result = combat.v13.core.battle(hero, monster, seed=seed)
    attempts = max(1, result.hero_attempts)
    hit_bps = 10_000 * result.hero_hits // attempts
    lineage = integrated.LINEAGE_ENVELOPES[lineage_id]
    lifecycle = auto_ai.RESOURCE_DEFINITIONS[hero_class][0]
    reserve_roots = BEHAVIOR_RESOURCE_FLOORS[behavior_id][lifecycle] // 1_000
    usable_roots = max(0, envelope.resource_roots + lineage.resource_roots - reserve_roots)
    resource_pressure = result.hero_attempts > usable_roots
    failure = integrated.failure_reason(
        result, hit_bps, variant.prefix_id, monster.attack_type, resource_pressure,
    )
    return CellResult(
        display_level=display_level,
        skill_level=skill_level,
        hero_class=hero_class,
        stat_tier=stat_tier,
        equipment_lineage=lineage_id,
        build_provenance=build_id,
        behavior_policy=behavior_id,
        variant_id=variant.variant_id,
        region_id=variant.region_id,
        monster_rank=variant.rank,
        seed=seed,
        loadout_digest=envelope.provenance_digest,
        outcome=result.outcome,
        rounds=result.rounds,
        hp_loss_bps=max(0, 10_000 * (hero.max_hp - result.hero_hp) // hero.max_hp),
        hit_bps=hit_bps,
        resource_pressure=resource_pressure,
        failure_reason=failure,
    )


def _axis_items(cell: CellResult):
    return (
        ("statTier", cell.stat_tier),
        ("heroClass", cell.hero_class),
        ("displayLevel", str(cell.display_level)),
        ("equipmentLineage", cell.equipment_lineage),
        ("buildProvenance", cell.build_provenance),
        ("behaviorPolicy", cell.behavior_policy),
        ("monsterRank", cell.monster_rank),
    )


@lru_cache(maxsize=1)
def run_matrix() -> MatrixReport:
    scenario_count = 0
    matrix_hasher = hashlib.sha256()
    seeds = set()
    variants = Counter()
    regions = Counter()
    outcomes = Counter()
    wins_by_axis = defaultdict(Counter)
    totals_by_axis = defaultdict(Counter)
    failures = Counter()
    resource = {build: [0, 0] for build in BUILD_PROVENANCES}
    resource_by_behavior = {behavior: [0, 0] for behavior in BEHAVIOR_POLICIES}
    hit_histogram = Counter()
    max_rounds = 0
    max_actions = 0

    for display_level in DISPLAY_LEVELS:
        for hero_class in registry242.CLASSES:
            for stat_tier in STAT_TIERS:
                for lineage_id in EQUIPMENT_LINEAGES:
                    for build_id in BUILD_PROVENANCES:
                        for behavior_id in BEHAVIOR_POLICIES:
                            for variant in monsters.VARIANTS:
                                cell = simulate_cell(
                                    display_level, hero_class, stat_tier, lineage_id,
                                    build_id, behavior_id, variant,
                                )
                                scenario_count += 1
                                seeds.add(cell.seed)
                                variants[cell.variant_id] += 1
                                regions[cell.region_id] += 1
                                outcomes[cell.outcome] += 1
                                for axis, key in _axis_items(cell):
                                    totals_by_axis[axis][key] += 1
                                    wins_by_axis[axis][key] += cell.outcome == "WIN"
                                if cell.failure_reason != "NONE":
                                    failures[cell.failure_reason] += 1
                                resource[cell.build_provenance][0] += cell.resource_pressure
                                resource[cell.build_provenance][1] += 1
                                resource_by_behavior[cell.behavior_policy][0] += cell.resource_pressure
                                resource_by_behavior[cell.behavior_policy][1] += 1
                                hit_histogram[cell.hit_bps] += 1
                                max_rounds = max(max_rounds, cell.rounds)
                                max_actions = max(max_actions, cell.rounds * 2)
                                matrix_hasher.update(canonical_json(asdict(cell)).encode("utf-8"))
                                matrix_hasher.update(b"\n")

    def nested(counter_map):
        return tuple(
            (axis, tuple(sorted(values.items())))
            for axis, values in sorted(counter_map.items())
        )

    return MatrixReport(
        scenario_count=scenario_count,
        matrix_digest=matrix_hasher.hexdigest(),
        unique_seed_count=len(seeds),
        variant_counts=tuple(sorted(variants.items())),
        region_counts=tuple(sorted(regions.items())),
        outcomes=tuple(sorted(outcomes.items())),
        wins_by_axis=nested(wins_by_axis),
        totals_by_axis=nested(totals_by_axis),
        failure_reasons=tuple(sorted(failures.items())),
        resource_pressure_by_build=tuple(
            (build, (counts[0], counts[1])) for build, counts in sorted(resource.items())
        ),
        resource_pressure_by_behavior=tuple(
            (behavior, (counts[0], counts[1]))
            for behavior, counts in sorted(resource_by_behavior.items())
        ),
        hit_histogram=tuple(sorted(hit_histogram.items())),
        max_rounds=max_rounds,
        max_actions=max_actions,
    )


def nested_dict(value) -> dict[str, dict[str, int]]:
    return {axis: dict(items) for axis, items in value}


def rate_map(report: MatrixReport, axis: str) -> dict[str, float]:
    wins = nested_dict(report.wins_by_axis)[axis]
    totals = nested_dict(report.totals_by_axis)[axis]
    return {key: wins[key] / totals[key] for key in totals}


def weighted_percentile(histogram: tuple[tuple[int, int], ...], quantile: float) -> int:
    total = sum(count for _, count in histogram)
    target = max(1, int(total * quantile + 0.999999))
    seen = 0
    for value, count in histogram:
        seen += count
        if seen >= target:
            return value
    return histogram[-1][0]


def summary(report: MatrixReport) -> dict:
    pressure = {
        build: count / total for build, (count, total) in report.resource_pressure_by_build
    }
    behavior_pressure = {
        behavior: count / total
        for behavior, (count, total) in report.resource_pressure_by_behavior
    }
    return {
        "coreScenarioCount": report.scenario_count,
        "matrixDigest": report.matrix_digest,
        "uniqueSeedCount": report.unique_seed_count,
        "variantCount": len(report.variant_counts),
        "variantRepetitions": sorted(set(dict(report.variant_counts).values())),
        "regionCells": dict(report.region_counts),
        "outcomes": dict(report.outcomes),
        "winByStatTier": rate_map(report, "statTier"),
        "winByClass": rate_map(report, "heroClass"),
        "winByDisplayLevel": rate_map(report, "displayLevel"),
        "winByEquipmentLineage": rate_map(report, "equipmentLineage"),
        "winByBuildProvenance": rate_map(report, "buildProvenance"),
        "winByBehaviorPolicy": rate_map(report, "behaviorPolicy"),
        "winByMonsterRank": rate_map(report, "monsterRank"),
        "resourcePressureByBuild": pressure,
        "resourcePressureByBehavior": behavior_pressure,
        "failureReasons": dict(report.failure_reasons),
        "observedHitBpsP01": weighted_percentile(report.hit_histogram, 0.01),
        "maxRounds": report.max_rounds,
        "maxActions": report.max_actions,
    }


def snapshot_for_layout(hero_class: str, build_id: str, behavior: str,
                        skill_level: int, layout: tuple[int, int]):
    provenance = build_provenance(hero_class, build_id)
    active_count, passive_count = layout
    return registry242.commit_loadout(
        hero_class,
        {skill_id: skill_level for skill_id in provenance.active_ids[:active_count]},
        {skill_id: skill_level for skill_id in provenance.passive_ids[:passive_count]},
        behavior_policy_id=f"aq.behavior.{behavior.lower()}.v1",
    )


def snapshot_ids(snapshot) -> tuple[str, ...]:
    return tuple(entry.skill_id for entry in snapshot.active_slots + snapshot.passive_slots)


def permitted_fallback_actions(snapshot) -> tuple[str, ...]:
    # v0.9 permits bounded Mage system recovery even at 0+0. Neither fallback
    # is a skill action and both remain inside the automatic action loop.
    if snapshot.hero_class == "MAGE":
        return ("BASIC", "SYSTEM_RECOVERY")
    return ("BASIC",)


def settlement_state_digest(state: MasterSettlementState) -> str:
    return sha256_payload(asdict(state))


def apply_settlement(state: MasterSettlementState, receipt: MasterSettlementReceipt,
                     snapshot, mentor_level: int) -> MasterSettlementApply:
    if receipt.variant_id not in VARIANT_BY_ID:
        return MasterSettlementApply("UNKNOWN_VARIANT", state)
    variant = VARIANT_BY_ID[receipt.variant_id]
    if receipt.region_id not in monsters.REGIONS:
        return MasterSettlementApply("UNKNOWN_REGION", state)
    if variant.region_id != receipt.region_id:
        return MasterSettlementApply("REGION_MISMATCH", state)
    if receipt.receipt_id in state.seen_receipt_ids:
        return MasterSettlementApply("REPLAY_NOOP", state)

    seen = tuple(sorted((*state.seen_receipt_ids, receipt.receipt_id)))
    if receipt.outcome not in {"VICTORY", "DEFEAT", "RETREAT"}:
        return MasterSettlementApply("UNKNOWN_OUTCOME", state)
    if receipt.outcome != "VICTORY":
        return MasterSettlementApply(
            "NON_VICTORY_ZERO", replace(state, seen_receipt_ids=seen),
        )

    levels = {entry.skill_id: entry.skill_level for entry in snapshot.active_slots + snapshot.passive_slots}
    grants = {
        skill_id: progression.skill_xp_grant(
            receipt.eligible_combat_xp, target_level, mentor_level,
        )
        for skill_id, target_level in levels.items()
    }
    skill_xp = dict(state.skill_xp_by_id)
    for skill_id, grant in grants.items():
        skill_xp[skill_id] = min(
            progression.TOTAL_SKILL_XP, skill_xp.get(skill_id, 0) + grant,
        )
    character_grant = max(0, int(receipt.eligible_combat_xp))
    reward_grant = max(0, int(receipt.reward_units)) * variant.reward_bps // 10_000
    rewards = dict(state.reward_by_region)
    rewards[receipt.region_id] = rewards.get(receipt.region_id, 0) + reward_grant
    next_state = MasterSettlementState(
        character_xp=state.character_xp + character_grant,
        skill_xp_by_id=tuple(sorted(skill_xp.items())),
        reward_by_region=tuple(sorted(rewards.items())),
        rare_material_eligible_receipts=tuple(sorted(
            (*state.rare_material_eligible_receipts, receipt.receipt_id),
        )),
        seen_receipt_ids=seen,
    )
    return MasterSettlementApply(
        "APPLIED_VICTORY", next_state, tuple(sorted(grants.items())),
        character_grant, reward_grant, True,
    )


def apply_batch(initial: MasterSettlementState, receipts, snapshot,
                mentor_level: int) -> MasterSettlementState:
    state = initial
    for receipt in receipts:
        state = apply_settlement(state, receipt, snapshot, mentor_level).state
    return state


def boundary_proofs() -> tuple[tuple[str, bool, str], ...]:
    full = snapshot_for_layout("WARRIOR", "OFFENSE", "BALANCED", 50, FULL_LAYOUT)
    newcomer = snapshot_for_layout("WARRIOR", "OFFENSE", "BALANCED", 50, NEW_LAYOUT)
    empty = snapshot_for_layout("MAGE", "CONTROL", "CAUTIOUS", 50, EMPTY_LAYOUT)
    variants_by_region = {
        region: next(item for item in monsters.VARIANTS if item.region_id == region)
        for region in monsters.REGIONS
    }
    green = variants_by_region["green_hills"]
    win = MasterSettlementReceipt("master-win", green.region_id, green.variant_id, "VICTORY", 10_000, 100)
    full_result = apply_settlement(MasterSettlementState(), win, full, 50)
    newcomer_result = apply_settlement(
        MasterSettlementState(), replace(win, receipt_id="master-new"), newcomer, 50,
    )
    empty_result = apply_settlement(
        MasterSettlementState(), replace(win, receipt_id="master-empty"), empty, 50,
    )
    receipts = tuple(
        MasterSettlementReceipt(
            f"offline-{region}", region, variant.variant_id, "VICTORY", 10_000, 100,
        )
        for region, variant in variants_by_region.items()
    )
    batch = apply_batch(MasterSettlementState(), receipts, full, 50)
    reordered = apply_batch(MasterSettlementState(), reversed(receipts), full, 50)
    sequential = MasterSettlementState()
    for receipt in receipts:
        sequential = apply_settlement(sequential, receipt, full, 50).state
    replay = apply_settlement(batch, receipts[0], full, 50)
    bad_region = apply_settlement(
        MasterSettlementState(), replace(win, receipt_id="bad-region", region_id="old_mine"), full, 50,
    )
    defeat = apply_settlement(
        MasterSettlementState(), replace(win, receipt_id="defeat", outcome="DEFEAT"), full, 50,
    )
    retreat = apply_settlement(
        MasterSettlementState(), replace(win, receipt_id="retreat", outcome="RETREAT"), full, 50,
    )
    return (
        ("Registry242 full 5+3 committed", len(full.active_slots) == 5 and len(full.passive_slots) == 3 and full.content_hash == REGISTRY_242_HASH, full.content_hash),
        ("newcomer 4+2 committed separately", len(newcomer.active_slots) == 4 and len(newcomer.passive_slots) == 2 and newcomer.content_hash == REGISTRY_242_HASH, f"active={len(newcomer.active_slots)} passive={len(newcomer.passive_slots)}"),
        ("empty 0+0 committed separately", not empty.active_slots and not empty.passive_slots and empty.content_hash == REGISTRY_242_HASH and permitted_fallback_actions(empty) == ("BASIC", "SYSTEM_RECOVERY"), "BASIC/SYSTEM_RECOVERY only; no skill action"),
        ("full SkillXP grants ten percent to all eight without splitting", len(full_result.skill_grants) == 8 and set(dict(full_result.skill_grants).values()) == {1_000}, str(full_result.skill_grants)),
        ("newcomer SkillXP grants ten percent to all six", len(newcomer_result.skill_grants) == 6 and set(dict(newcomer_result.skill_grants).values()) == {1_000}, str(newcomer_result.skill_grants)),
        ("empty SkillXP grants none", not empty_result.skill_grants, str(empty_result.skill_grants)),
        ("offline batch and sequential are deterministic", settlement_state_digest(batch) == settlement_state_digest(sequential) == settlement_state_digest(reordered), settlement_state_digest(batch)),
        ("duplicate receipt is no-op", replay.status == "REPLAY_NOOP" and replay.state == batch, replay.status),
        ("region mismatch is rejected before receipt consumption", bad_region.status == "REGION_MISMATCH" and not bad_region.state.seen_receipt_ids, bad_region.status),
        ("defeat and retreat grant zero", all(result.status == "NON_VICTORY_ZERO" and not result.skill_grants and result.character_xp_grant == 0 and result.reward_grant == 0 and not result.rare_material_eligible for result in (defeat, retreat)), f"{defeat.status}/{retreat.status}"),
        ("victory gates regional reward and rare-material eligibility", full_result.status == "APPLIED_VICTORY" and full_result.reward_grant > 0 and full_result.rare_material_eligible, f"reward={full_result.reward_grant}"),
        ("monster registry is 48 per region", {region: sum(item.region_id == region for item in monsters.VARIANTS) for region in monsters.REGIONS} == {region: 48 for region in monsters.REGIONS}, str(monsters.REGIONS)),
    )


def build_provenance_payload() -> list[dict]:
    return [
        asdict(build_provenance(hero_class, build_id))
        for hero_class in registry242.CLASSES
        for build_id in BUILD_PROVENANCES
    ]


def canonical_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "dependencies": {
            "skillRegistry242Hash": REGISTRY_242_HASH,
            "skillStatusStructureHash": registry242.status_structure_hash(),
            "autoBattleAiV09Hash": AUTO_AI_V09_HASH,
            "autoBattleAiV09InteropHash": AUTO_AI_V09_INTEROP_HASH,
            "integratedCombatV01Hash": INTEGRATED_V01_HASH,
            "monsterPrefixV02Hash": MONSTER_HASH,
            "equipment650V02Hash": EQUIPMENT_HASH,
        },
        "coreMatrix": {
            "classes": list(registry242.CLASSES),
            "statTiers": list(STAT_TIERS),
            "equipmentLineages": list(EQUIPMENT_LINEAGES),
            "buildProvenances": list(BUILD_PROVENANCES),
            "behaviorPolicies": list(BEHAVIOR_POLICIES),
            "displayLevels": list(DISPLAY_LEVELS),
            "skillLevels": SKILL_LEVELS,
            "monsterVariants": len(monsters.VARIANTS),
            "scenarioCount": CORE_SCENARIO_COUNT,
            "layout": {"active": 5, "passive": 3},
            "seedContract": "sha256(first-64-bit-positive) over six combat axes; common across behavior policy",
            "seedsPerCell": 1,
        },
        "buildProvenance": build_provenance_payload(),
        "registryHandlerProxy": {
            "caps": HANDLER_PROXY_CAPS,
            "archetypeRuntimeStatBonus": False,
            "behaviorRuntimeStatBonus": False,
            "behaviorResourceFloors": BEHAVIOR_RESOURCE_FLOORS,
            "commonRandomSeedAcrossBehaviors": True,
            "productionSemanticResolver": False,
        },
        "separateBoundaryLayouts": {
            "full": {"active": 5, "passive": 3},
            "newcomer": {"active": 4, "passive": 2},
            "empty": {
                "active": 0,
                "passive": 0,
                "mageFallbackActions": ["BASIC", "SYSTEM_RECOVERY"],
                "skillActions": 0,
            },
        },
        "limits": {"rounds": MAX_ROUNDS, "actions": MAX_ACTIONS},
        "skillXp": {
            "perEquippedSkillBps": progression.SKILL_XP_SHARE_BPS,
            "splitAcrossLoadout": False,
            "catchUpPerLevelBps": progression.CATCH_UP_PER_LEVEL_BPS,
            "catchUpCapBps": progression.CATCH_UP_CAP_BPS,
            "cap": progression.TOTAL_SKILL_XP,
            "victoryOnly": True,
        },
        "settlement": {
            "regions": {region: 48 for region in monsters.REGIONS},
            "receiptReplay": "IDEMPOTENT_NOOP",
            "defeatReward": 0,
            "retreatReward": 0,
            "rareMaterialEligibility": "VICTORY_AND_REGION_MATCH_ONLY",
            "productionPathConnected": False,
        },
        "testOnly": True,
        "productionEnabled": False,
        "liveEnabled": False,
    }


def canonical_hash() -> str:
    return sha256_payload(canonical_payload())


def _spread(values: dict[str, float]) -> float:
    return max(values.values()) - min(values.values())


def balance_checks(report: MatrixReport) -> list[tuple[str, bool, str]]:
    result = summary(report)
    stat = result["winByStatTier"]
    ranks = result["winByMonsterRank"]
    pressures = result["resourcePressureByBuild"]
    behavior_pressures = result["resourcePressureByBehavior"]
    base_normal_wins = nested_dict(report.wins_by_axis)["monsterRank"]["NORMAL"]
    base_normal_total = nested_dict(report.totals_by_axis)["monsterRank"]["NORMAL"]
    provenance = build_provenance_payload()
    boundaries = boundary_proofs()
    return [
        ("core matrix exact 6x3x5x3x3x4x144", report.scenario_count == CORE_SCENARIO_COUNT == 466_560, f"scenarios={report.scenario_count}"),
        ("all 144 variants repeat exact 3240 times", len(report.variant_counts) == 144 and set(dict(report.variant_counts).values()) == {3_240}, f"variants={len(report.variant_counts)} repetitions={result['variantRepetitions']}"),
        ("one common-random deterministic seed per nonbehavior context", report.unique_seed_count == report.scenario_count // len(BEHAVIOR_POLICIES), f"uniqueSeeds={report.unique_seed_count}"),
        ("eighteen Registry242 full loadout provenances", len(provenance) == 18 and all(len(item["active_ids"]) == 5 and len(item["passive_ids"]) == 3 and len(item["selection_digest"]) == 64 for item in provenance), f"provenances={len(provenance)}"),
        ("every core snapshot is Registry242 content-bound", all(committed_loadout(hero_class, build, behavior, SKILL_LEVELS[level]).content_hash == REGISTRY_242_HASH for hero_class in registry242.CLASSES for build in BUILD_PROVENANCES for behavior in BEHAVIOR_POLICIES for level in DISPLAY_LEVELS), REGISTRY_242_HASH),
        ("all separate boundary proofs pass", all(ok for _, ok, _ in boundaries), f"passed={sum(ok for _, ok, _ in boundaries)}/{len(boundaries)}"),
        ("rank difficulty ordered", ranks["NORMAL"] >= ranks["ELITE"] >= ranks["BOSS"], str(ranks)),
        ("normal automation remains broadly reliable", base_normal_wins / base_normal_total >= 0.88, f"normal={base_normal_wins / base_normal_total:.4f}"),
        ("low initial stats remain recoverable", stat["STRESS_ALL3"] >= stat["BASE"] - 0.14, str(stat)),
        ("high initial stats help without hard dominance", stat["STRESS_ALL18"] >= stat["BASE"] and stat["STRESS_ALL18"] - stat["STRESS_ALL3"] <= 0.20, str(stat)),
        ("six class spread bounded", _spread(result["winByClass"]) <= 0.22, str(result["winByClass"])),
        ("long progression anchors bounded", _spread(result["winByDisplayLevel"]) <= 0.18, str(result["winByDisplayLevel"])),
        ("no dead equipment lineage", _spread(result["winByEquipmentLineage"]) <= 0.18, str(result["winByEquipmentLineage"])),
        ("no dominant Registry242 build provenance", _spread(result["winByBuildProvenance"]) <= 0.18, str(result["winByBuildProvenance"])),
        ("behavior policy adds zero combat power", _spread(result["winByBehaviorPolicy"]) == 0, str(result["winByBehaviorPolicy"])),
        ("resource pressure remains a bounded handler diagnostic", all(0 <= value <= 1 for value in pressures.values()), str(pressures)),
        ("v09 reserve policy orders pressure without stat bonuses", behavior_pressures["CAUTIOUS"] >= behavior_pressures["BALANCED"] >= behavior_pressures["BOLD"], str(behavior_pressures)),
        ("observed hit floor p01 bounded", result["observedHitBpsP01"] >= 5_000, f"p01={result['observedHitBpsP01']}"),
        ("round and action loops bounded", report.max_rounds <= MAX_ROUNDS and report.max_actions <= MAX_ACTIONS, f"rounds={report.max_rounds} actions={report.max_actions}"),
        ("deterministic anchor replays", deterministic_replay_check(), "48 fixed cells"),
        ("v09 forbids archetype runtime score", auto_ai.canonical_payload()["buildAffectsRuntimeScore"] is False, AUTO_AI_V09_HASH),
        ("master remains test-only", canonical_payload()["testOnly"] and not canonical_payload()["productionEnabled"] and not canonical_payload()["liveEnabled"] and not canonical_payload()["settlement"]["productionPathConnected"], "testOnly=true production=false live=false"),
    ]


def deterministic_replay_check() -> bool:
    variants = monsters.VARIANTS[:: max(1, len(monsters.VARIANTS) // 4)][:4]
    cells = []
    for index, variant in enumerate(variants):
        level = DISPLAY_LEVELS[index % len(DISPLAY_LEVELS)]
        for hero_class in registry242.CLASSES:
            for behavior in ("CAUTIOUS", "BOLD"):
                cell = simulate_cell(
                    level, hero_class, STAT_TIERS[index % 3],
                    EQUIPMENT_LINEAGES[index % 5], BUILD_PROVENANCES[index % 3],
                    behavior, variant,
                )
                replay = simulate_cell(
                    level, hero_class, STAT_TIERS[index % 3],
                    EQUIPMENT_LINEAGES[index % 5], BUILD_PROVENANCES[index % 3],
                    behavior, variant,
                )
                cells.append(asdict(cell) == asdict(replay))
    return len(cells) == 48 and all(cells)


def pd_checks(report: MatrixReport) -> list[tuple[str, bool, str]]:
    checks = balance_checks(report)
    document = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    kotlin = KOTLIN_CONTRACT.read_text(encoding="utf-8") if KOTLIN_CONTRACT.exists() else ""
    kotlin_test = KOTLIN_TEST.read_text(encoding="utf-8") if KOTLIN_TEST.exists() else ""
    current_hash = canonical_hash()
    checks.extend((
        ("document binds master canonical hash", current_hash in document, current_hash),
        ("Kotlin binds master canonical hash", current_hash in kotlin, current_hash),
        ("document binds Registry242 and v09 hashes", all(value in document for value in (REGISTRY_242_HASH, AUTO_AI_V09_HASH, AUTO_AI_V09_INTEROP_HASH)), f"registry={REGISTRY_242_HASH} v09={AUTO_AI_V09_HASH}"),
        ("Kotlin binds Registry242 v09 interop and integrated hashes", all(value in kotlin for value in (REGISTRY_242_HASH, AUTO_AI_V09_HASH, AUTO_AI_V09_INTEROP_HASH, INTEGRATED_V01_HASH)), "four dependency hashes"),
        ("Kotlin tests exact core and boundaries", all(token in kotlin_test for token in ("466_560L", "FULL_5_3", "NEW_4_2", "EMPTY_0_0", "REPLAY_NOOP", "REGION_MISMATCH")), "test tokens"),
        ("document states honest release gates", all(token in document for token in ("test-only GO", "production 조건부 승인", "라이브 NO-GO", "466,560")), "explicit gates"),
        ("no unresolved hash placeholder", "__MASTER_HASH__" not in kotlin and "__REGISTRY_HASH__" not in kotlin and "__MASTER_HASH__" not in document, "placeholders=0"),
    ))
    return checks


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--summary", action="store_true")
    parser.add_argument("--hash-only", action="store_true")
    parser.add_argument("--boundaries", action="store_true")
    args = parser.parse_args()
    if args.hash_only:
        print(canonical_hash())
        return 0
    if args.boundaries:
        checks = boundary_proofs()
        print(f"BOUNDARY: {sum(ok for _, ok, _ in checks)}/{len(checks)} PASS")
        for name, ok, detail in checks:
            print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
        print(f"canonical_sha256={canonical_hash()}")
        return 0 if all(ok for _, ok, _ in checks) else 1

    report = run_matrix()
    if args.summary:
        print(json.dumps(summary(report), ensure_ascii=False, indent=2, sort_keys=True))
        print(f"canonical_sha256={canonical_hash()}")
        return 0
    checks = pd_checks(report) if args.pd else balance_checks(report)
    print(f"{'PD' if args.pd else 'BALANCE'}: {sum(ok for _, ok, _ in checks)}/{len(checks)} PASS")
    for name, ok, detail in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"matrix_sha256={report.matrix_digest}")
    print(f"canonical_sha256={canonical_hash()}")
    return 0 if all(ok for _, ok, _ in checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
