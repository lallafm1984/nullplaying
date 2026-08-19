#!/usr/bin/env python3
"""Wave 1 design audit for six complete Active 5 + Passive 3 loadouts.

The tool reuses the approved 30 C1 Active definitions as Skill Lv.1/25/50/75/100
anchors, adds 18 numerical Passive plans, gives the three former A3 alternatives
one shared activation/cooldown/use ledger, exhaustively checks every loadout subset
at every Skill Level, and runs paired deterministic battle stress samples.

It is design-only. It does not mutate Kotlin, Room, saves, or live content.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import statistics
from dataclasses import dataclass, field, replace
from decimal import Decimal
from pathlib import Path

import c1_skill_rank_growth_v0_1_review as legacy
import skill_collection_level9999_v0_1_review as collection


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_WAVE1_ACTIVE5_PASSIVE3_v0.1.md"
IDEA_BANK = ROOT / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"

CLASSES = legacy.c1.CLASSES
ANCHOR_LEVELS = collection.SKILL_ANCHOR_LEVELS
PASSIVE_COST_REDUCTION_CAP_BPS = -1_500
FINAL_RESOURCE_COST_FLOOR_BPS = 1_000


@dataclass(frozen=True)
class PassivePlan:
    definition_id: str
    idea_id: str
    owner_class: str
    name_ko: str
    role: str
    condition_id: str
    host_scope: str
    effect_field: str
    values: tuple[int, int, int, int, int]
    direction: str
    stack_group: str
    fixed_downside_field: str | None = None
    fixed_downside_value: int = 0


@dataclass(frozen=True)
class TacticGroup:
    owner_class: str
    group_id: str
    activation_bps: int
    cooldown_turns: int
    encounter_cap: int
    expedition_cap: int


def passive(
    definition_id: str,
    idea_id: str,
    owner_class: str,
    name_ko: str,
    role: str,
    condition_id: str,
    host_scope: str,
    effect_field: str,
    values: tuple[int, int, int, int, int],
    direction: str,
    stack_group: str,
    fixed_downside_field: str | None = None,
    fixed_downside_value: int = 0,
) -> PassivePlan:
    return PassivePlan(
        definition_id,
        idea_id,
        owner_class,
        name_ko,
        role,
        condition_id,
        host_scope,
        effect_field,
        values,
        direction,
        stack_group,
        fixed_downside_field,
        fixed_downside_value,
    )


PASSIVES = (
    passive("aq.skill.warrior.p1.scarmedal", "W009", "WARRIOR", "상처의 훈장", "RESOURCE", "damaging_hits_taken_gte_2", "POWER_ACTIVE", "resource_cost_modifier_bps", (-300, -375, -450, -525, -600), "DECREASE", "aq.stack.passive.resource_discount"),
    passive("aq.skill.warrior.p2.heavyarmormastery", "W015", "WARRIOR", "중갑 숙련", "DEFENSE", "heavy_armor_equipped", "SELF", "physical_defense_modifier_bps", (200, 300, 400, 500, 600), "INCREASE", "aq.stack.passive.physical_defense", "speed_modifier_bps", -300),
    passive("aq.skill.warrior.p3.shieldbreath", "W017", "WARRIOR", "방패 호흡", "RESOURCE", "after_shield_active", "NEXT_NON_SURVIVAL_ACTIVE", "resource_cost_modifier_bps", (-200, -300, -400, -500, -600), "DECREASE", "aq.stack.passive.resource_discount"),

    passive("aq.skill.rogue.p1.sixthsense", "ROG009", "ROGUE", "도둑의 여섯째 감각", "DEFENSE", "first_incoming_coefficient_gte_11000", "SELF", "incoming_damage_modifier_bps", (-300, -400, -500, -600, -700), "DECREASE", "aq.stack.passive.incoming_damage"),
    passive("aq.skill.rogue.p2.lightfootwork", "ROG015", "ROGUE", "경갑 발놀림", "EVASION", "light_armor_equipped", "SELF", "evasion_modifier_bps", (200, 275, 350, 425, 500), "INCREASE", "aq.stack.passive.evasion", "physical_defense_modifier_bps", -300),
    passive("aq.skill.rogue.p3.failurestudy", "ROG018", "ROGUE", "실패의 학습", "ACCURACY", "after_own_miss", "NEXT_ACTIVE", "hit_modifier_bps", (300, 425, 550, 675, 800), "INCREASE", "aq.stack.passive.hit"),

    passive("aq.skill.ranger.p1.roadinstinct", "HUN009", "RANGER", "길 위의 본능", "SUSTAIN", "first_effective_hit_after_region_entry", "SELF", "incoming_damage_modifier_bps", (-200, -250, -300, -350, -400), "DECREASE", "aq.stack.passive.incoming_damage"),
    passive("aq.skill.ranger.p2.distancesense", "HUN015", "RANGER", "거리 감각", "ACCURACY", "consecutive_hits_gte_2", "RANGED_ATTACK", "hit_modifier_bps", (100, 150, 200, 250, 300), "INCREASE", "aq.stack.passive.hit"),
    passive("aq.skill.ranger.p3.isolatedprey", "HUN020", "RANGER", "고립된 사냥감", "RESOURCE", "single_marked_enemy", "MARK_HOST_ACTIVE", "resource_cost_modifier_bps", (-200, -300, -400, -500, -600), "DECREASE", "aq.stack.passive.resource_discount"),

    passive("aq.skill.mage.p1.crackedcore", "MAG009", "MAGE", "깨진 마도핵", "MAGIC_OFFENSE", "resource_lte_2500", "MAGIC_ROOT_ACTION", "action_coefficient_add_bps", (25, 37, 50, 62, 75), "INCREASE", "aq.stack.passive.magic_coefficient", "recovery_efficiency_modifier_bps", -500),
    passive("aq.skill.mage.p2.manaecho", "MAG018", "MAGE", "마나 잔향", "RESOURCE", "after_cost_gte_2000", "NEXT_MAGIC_ACTIVE", "resource_cost_modifier_bps", (-200, -300, -400, -500, -600), "DECREASE", "aq.stack.passive.resource_discount"),
    passive("aq.skill.mage.p3.glasscannon", "MAG020", "MAGE", "유리 대포", "MAGIC_OFFENSE", "always", "MAGIC_ROOT_ACTION", "action_coefficient_add_bps", (50, 62, 75, 87, 100), "INCREASE", "aq.stack.passive.magic_coefficient", "incoming_damage_modifier_bps", 300),

    passive("aq.skill.cleric.p1.heardprayer", "CLE009", "CLERIC", "들리는 기도", "RESOURCE", "after_overheal", "NEXT_SHIELD_ACTIVE", "resource_cost_modifier_bps", (-200, -300, -400, -500, -600), "DECREASE", "aq.stack.passive.resource_discount"),
    passive("aq.skill.cleric.p2.faithecho", "CLE015", "CLERIC", "신앙의 여운", "HEAL_SUPPORT", "after_heal_active", "NEXT_SHIELD_ACTIVE", "hp_threshold_modifier_bps", (100, 175, 250, 325, 400), "INCREASE", "aq.stack.passive.survival_threshold"),
    passive("aq.skill.cleric.p3.healingrestraint", "CLE016", "CLERIC", "치유 절제", "RESOURCE", "predicted_overheal_gte_25pct", "HEAL_ACTIVE", "resource_cost_modifier_bps", (-200, -300, -400, -500, -600), "DECREASE", "aq.stack.passive.resource_discount"),

    passive("aq.skill.paladin.p1.twokingdomsoath", "PAL009", "PALADIN", "두 왕국의 서약", "DEFENSE_SUPPORT", "alternating_offense_protection", "NEXT_ALTERNATING_ACTIVE", "resource_cost_modifier_bps", (-150, -225, -300, -375, -450), "DECREASE", "aq.stack.passive.resource_discount"),
    passive("aq.skill.paladin.p2.plateprayer", "PAL015", "PALADIN", "판금 기도", "DEFENSE", "heavy_armor_equipped", "SELF", "magical_resistance_modifier_bps", (200, 275, 350, 425, 500), "INCREASE", "aq.stack.passive.magical_resistance", "speed_modifier_bps", -300),
    passive("aq.skill.paladin.p3.othecho", "PAL017", "PALADIN", "맹세의 잔향", "ACCURACY", "after_heal_active", "NEXT_OFFENSE_ACTIVE", "hit_modifier_bps", (200, 300, 400, 500, 600), "INCREASE", "aq.stack.passive.hit"),
)

PASSIVE_BY_ID = {plan.definition_id: plan for plan in PASSIVES}

TACTIC_GROUPS = {
    "WARRIOR": TacticGroup("WARRIOR", "aq.shared.warrior.tactic", 6_000, 9, 1, 0),
    "ROGUE": TacticGroup("ROGUE", "aq.shared.rogue.tactic", 6_000, 4, 2, 0),
    "RANGER": TacticGroup("RANGER", "aq.shared.ranger.tactic", 6_000, 4, 2, 0),
    "MAGE": TacticGroup("MAGE", "aq.shared.mage.tactic", 6_000, 4, 2, 0),
    "CLERIC": TacticGroup("CLERIC", "aq.shared.cleric.tactic", 10_000, 3, 1, 3),
    "PALADIN": TacticGroup("PALADIN", "aq.shared.paladin.tactic", 10_000, 5, 1, 2),
}

ATTACK_BUDGETS = legacy.c1.ATTACK_BUDGET


def interpolate_values(values: tuple[int, int, int, int, int], level: int) -> int:
    safe_level = max(1, min(100, int(level)))
    for index, anchor_level in enumerate(ANCHOR_LEVELS):
        if safe_level == anchor_level:
            return values[index]
        if safe_level < anchor_level:
            left_level = ANCHOR_LEVELS[index - 1]
            left_value = values[index - 1]
            progress = Decimal(safe_level - left_level) / Decimal(anchor_level - left_level)
            return collection.round_half_up(
                Decimal(left_value) + Decimal(values[index] - left_value) * progress
            )
    return values[-1]


def passive_value(plan: PassivePlan, level: int) -> int:
    return interpolate_values(plan.values, level)


def active_catalog(level: int) -> tuple:
    resolved = []
    for skill in legacy.c1.SKILLS:
        plan = legacy.PLAN_BY_ID[skill.definition_id]
        value = collection.interpolated_legacy_skill_value(plan, level)
        changes = {
            "growth_axis": plan.growth_axis,
            "rank_axis_values": plan.values,
            plan.field: value,
        }
        if plan.field == "action_coefficient_bps":
            changes["attack_budget_equivalent_bps"] = value
        resolved.append(replace(skill, **changes))
    return tuple(resolved)


def class_actives(catalog: tuple, class_name: str) -> tuple:
    return tuple(skill for skill in catalog if skill.owner_class == class_name)


def class_passives(class_name: str) -> tuple[PassivePlan, ...]:
    return tuple(plan for plan in PASSIVES if plan.owner_class == class_name)


def passive_modifier(passive_ids: tuple[str, ...], field: str, level: int) -> int:
    return sum(
        passive_value(PASSIVE_BY_ID[definition_id], level)
        for definition_id in passive_ids
        if PASSIVE_BY_ID[definition_id].effect_field == field
    )


def worst_cost_modifier(passive_ids: tuple[str, ...], level: int) -> int:
    raw = passive_modifier(passive_ids, "resource_cost_modifier_bps", level)
    return max(PASSIVE_COST_REDUCTION_CAP_BPS, raw)


def resolved_resource_cost(base_cost: int, modifier_bps: int) -> int:
    if base_cost <= 0:
        return 0
    return max(
        FINAL_RESOURCE_COST_FLOOR_BPS,
        collection.round_half_up(Decimal(base_cost) * Decimal(10_000 + modifier_bps) / Decimal(10_000)),
    )


def attack_envelope(
    catalog: tuple,
    class_name: str,
    active_ids: tuple[str, ...],
    passive_ids: tuple[str, ...],
    skill_level: int,
) -> int:
    equipped = [skill for skill in class_actives(catalog, class_name) if skill.definition_id in active_ids]
    a1 = next((skill for skill in equipped if skill.slot_id == "A1_CORE"), None)
    tactics = [skill for skill in equipped if skill.slot_id == "A3_TACTIC_I"]
    a1_uses = legacy.c1.max_uses_in_ten(a1.cooldown_turns) if a1 else 0
    tactic_uses = 0
    tactic_budget = 0
    if tactics:
        group = TACTIC_GROUPS[class_name]
        tactic_uses = min(legacy.c1.max_uses_in_ten(group.cooldown_turns), group.encounter_cap)
        tactic_budget = max(skill.attack_budget_equivalent_bps for skill in tactics)
    basic_uses = 10 - a1_uses - tactic_uses
    assert basic_uses >= 0
    total = basic_uses * 10_000
    if a1:
        total += a1_uses * a1.attack_budget_equivalent_bps
    total += tactic_uses * tactic_budget
    if class_name == "MAGE":
        total += 10 * passive_modifier(passive_ids, "action_coefficient_add_bps", skill_level)
    return total


def protection_totals(catalog: tuple, class_name: str, active_ids: tuple[str, ...]) -> tuple[int, int]:
    equipped = [skill for skill in class_actives(catalog, class_name) if skill.definition_id in active_ids]
    totals = {"HEAL_E": 0, "HEAL_X": 0, "SHIELD_E": 0, "SHIELD_X": 0, "BARRIER_E": 0, "BARRIER_X": 0}
    for slot_id in ("A1_CORE", "A2_SURVIVAL"):
        for skill in equipped:
            if skill.slot_id != slot_id or skill.protection_kind is None:
                continue
            key = skill.protection_kind
            totals[f"{key}_E"] += skill.protection_amount * skill.max_activations_per_encounter
            totals[f"{key}_X"] += skill.protection_amount * skill.max_activations_per_expedition
    tactics = [skill for skill in equipped if skill.slot_id == "A3_TACTIC_I" and skill.protection_kind]
    if tactics:
        group = TACTIC_GROUPS[class_name]
        for kind in {skill.protection_kind for skill in tactics}:
            amount = max(skill.protection_amount for skill in tactics if skill.protection_kind == kind)
            totals[f"{kind}_E"] += amount * group.encounter_cap
            totals[f"{kind}_X"] += amount * group.expedition_cap
    encounter = totals["HEAL_E"] + totals["SHIELD_E"]
    expedition = totals["HEAL_X"] + totals["SHIELD_X"]
    assert totals["BARRIER_E"] <= 1 and totals["BARRIER_X"] <= 2
    return encounter, expedition


def canonical_hash() -> str:
    payload = {
        "activeAnchorHash": legacy.canonical_hash(),
        "activeSkillLevels": ANCHOR_LEVELS,
        "passives": [
            {
                "definitionId": plan.definition_id,
                "ideaId": plan.idea_id,
                "ownerClass": plan.owner_class,
                "nameKo": plan.name_ko,
                "role": plan.role,
                "conditionId": plan.condition_id,
                "hostScope": plan.host_scope,
                "effectField": plan.effect_field,
                "values": plan.values,
                "direction": plan.direction,
                "stackGroup": plan.stack_group,
                "fixedDownsideField": plan.fixed_downside_field,
                "fixedDownsideValue": plan.fixed_downside_value,
            }
            for plan in sorted(PASSIVES, key=lambda item: item.definition_id)
        ],
        "tacticGroups": [group.__dict__ for group in sorted(TACTIC_GROUPS.values(), key=lambda item: item.owner_class)],
        "passiveCostReductionCapBps": PASSIVE_COST_REDUCTION_CAP_BPS,
        "finalResourceCostFloorBps": FINAL_RESOURCE_COST_FLOOR_BPS,
    }
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()
    return hashlib.sha256(encoded).hexdigest()


@dataclass
class PassiveRuntime:
    damaging_hits_taken: int = 0
    sixth_sense_available: bool = True
    road_instinct_available: bool = True
    shield_breath_ready: bool = False
    rogue_miss_ready: bool = False
    ranger_consecutive_hits: int = 0
    mana_echo_ready: bool = False
    cleric_heard_prayer_ready: bool = False
    cleric_faith_echo_ready: bool = False
    paladin_previous_role: str | None = None
    paladin_oath_echo_ready: bool = False


@dataclass(frozen=True)
class FullBattleResult:
    outcome: str
    rounds: int
    hero_hp: int
    hero_max_hp: int
    resource_bps: int
    tactic_uses: int


@dataclass(frozen=True)
class FullMetrics:
    win_rate: float
    retreat_rate: float
    median_rounds: float
    p90_hp_loss: float


def has(passive_ids: tuple[str, ...], suffix: str) -> bool:
    return any(definition_id.endswith(suffix) for definition_id in passive_ids)


def apply_always_on_stats(hero, class_name: str, passive_ids: tuple[str, ...], level: int):
    physical_defense_modifier = 0
    magical_resistance_modifier = 0
    evasion_modifier = 0
    speed_modifier = 0
    for definition_id in passive_ids:
        plan = PASSIVE_BY_ID[definition_id]
        value = passive_value(plan, level)
        if plan.effect_field == "physical_defense_modifier_bps":
            physical_defense_modifier += value
        elif plan.effect_field == "magical_resistance_modifier_bps":
            magical_resistance_modifier += value
        elif plan.effect_field == "evasion_modifier_bps":
            evasion_modifier += value
        if plan.fixed_downside_field == "physical_defense_modifier_bps":
            physical_defense_modifier += plan.fixed_downside_value
        elif plan.fixed_downside_field == "speed_modifier_bps":
            speed_modifier += plan.fixed_downside_value
    return replace(
        hero,
        physical_defense=max(0, legacy.c1.core.multiply_bps(hero.physical_defense, 10_000 + physical_defense_modifier)),
        magical_resistance=max(0, legacy.c1.core.multiply_bps(hero.magical_resistance, 10_000 + magical_resistance_modifier)),
        evasion=max(1, legacy.c1.core.multiply_bps(hero.evasion, 10_000 + evasion_modifier)),
        speed=max(1, legacy.c1.core.multiply_bps(hero.speed, 10_000 + speed_modifier)),
    )


def skill_role(skill) -> str:
    return "PROTECTION" if skill.protection_kind is not None else "OFFENSE"


def effective_skill_for_selection(
    skill,
    state,
    runtime: PassiveRuntime,
    passive_ids: tuple[str, ...],
    skill_level: int,
    hero,
    status,
):
    cost_modifiers = []
    hp_threshold_modifier = 0
    if has(passive_ids, "scarmedal") and runtime.damaging_hits_taken >= 2 and skill.automation_band == "POWER":
        cost_modifiers.append(passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("scarmedal")), skill_level))
    if has(passive_ids, "shieldbreath") and runtime.shield_breath_ready and skill.automation_band != "SURVIVAL":
        cost_modifiers.append(passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("shieldbreath")), skill_level))
    if has(passive_ids, "isolatedprey") and status is not None and status.status_id.endswith("markweakness"):
        cost_modifiers.append(passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("isolatedprey")), skill_level))
    if has(passive_ids, "manaecho") and runtime.mana_echo_ready and skill.owner_class == "MAGE":
        cost_modifiers.append(passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("manaecho")), skill_level))
    if has(passive_ids, "heardprayer") and runtime.cleric_heard_prayer_ready and skill.protection_kind == "SHIELD":
        cost_modifiers.append(passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("heardprayer")), skill_level))
    if has(passive_ids, "faithecho") and runtime.cleric_faith_echo_ready and skill.protection_kind == "SHIELD":
        hp_threshold_modifier += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("faithecho")), skill_level)
    if has(passive_ids, "healingrestraint") and skill.protection_kind == "HEAL":
        nominal = legacy.c1.core.multiply_bps(hero.max_hp, skill.protection_amount)
        deficit = max(0, state.encounter_start_hp - state.hp)
        predicted_overheal_bps = max(0, nominal - deficit) * 10_000 // max(1, nominal)
        if predicted_overheal_bps >= 2_500:
            cost_modifiers.append(passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("healingrestraint")), skill_level))
    if has(passive_ids, "twokingdomsoath") and runtime.paladin_previous_role is not None:
        if skill_role(skill) != runtime.paladin_previous_role:
            cost_modifiers.append(passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("twokingdomsoath")), skill_level))
    total_cost_modifier = max(PASSIVE_COST_REDUCTION_CAP_BPS, sum(cost_modifiers))
    return replace(
        skill,
        resource_cost_bps=resolved_resource_cost(skill.resource_cost_bps, total_cost_modifier),
        hp_threshold_bps=legacy.c1.core.clamp(skill.hp_threshold_bps + hp_threshold_modifier, 0, 9_500),
    )


def select_full_action(
    seed: int,
    state,
    runtime: PassiveRuntime,
    hero,
    monster_hp: int,
    monster_max_hp: int,
    target_final_hit_bps: int,
    status,
    loadout: tuple,
    passive_ids: tuple[str, ...],
    skill_level: int,
    behavior_mode: str,
):
    class_name = loadout[0].owner_class
    group = TACTIC_GROUPS[class_name]
    normal_candidates = []
    tactic_candidates = []
    effective_by_id = {}
    for base_skill in loadout:
        effective = effective_skill_for_selection(
            base_skill, state, runtime, passive_ids, skill_level, hero, status
        )
        effective_by_id[effective.definition_id] = effective
        if base_skill.slot_id == "A3_TACTIC_I":
            if state.cooldowns.get(group.group_id, 0) > 0:
                continue
            if state.uses.get(group.group_id, 0) >= group.encounter_cap:
                continue
        if not legacy.c1.eligible(
            effective,
            state,
            hero,
            monster_hp,
            monster_max_hp,
            target_final_hit_bps,
            status,
            loadout,
            behavior_mode,
        ):
            continue
        if base_skill.slot_id == "A3_TACTIC_I":
            tactic_candidates.append(effective)
        elif effective.activation_policy == "DETERMINISTIC" or legacy.c1.event_roll_bps(
            seed, state.action_index, "AUTOMATION_V1", effective.definition_id
        ) < effective.activation_bps:
            normal_candidates.append(effective)
    if tactic_candidates:
        group_passes = group.activation_bps >= 10_000 or legacy.c1.event_roll_bps(
            seed, state.action_index, "AUTOMATION_GROUP_V1", group.group_id
        ) < group.activation_bps
        if group_passes:
            tactic_candidates.sort(
                key=lambda skill: (
                    -legacy.c1.BANDS[skill.automation_band],
                    -(skill.automation_priority + legacy.c1.behavior_adjustment(skill, behavior_mode).priority_offset),
                    skill.definition_id,
                )
            )
            normal_candidates.append(tactic_candidates[0])
    recovery_eligible = (
        legacy.c1.RESOURCES[class_name].recovery_cap > 0
        and state.recovery_uses < legacy.c1.RESOURCES[class_name].recovery_cap
        and state.resource <= 2_000
        and state.resource < state.resource_ceiling
    )
    if recovery_eligible and not any(skill.automation_band == "SURVIVAL" for skill in normal_candidates):
        return "RECOVERY", None
    if not normal_candidates:
        return "BASIC", None
    normal_candidates.sort(
        key=lambda skill: (
            -legacy.c1.BANDS[skill.automation_band],
            -(skill.automation_priority + legacy.c1.behavior_adjustment(skill, behavior_mode).priority_offset),
            skill.definition_id,
        )
    )
    selected = normal_candidates[0]
    return selected, next(skill for skill in loadout if skill.definition_id == selected.definition_id)


def decrement_full_cooldowns(state, used_base, group: TacticGroup) -> None:
    for definition_id in tuple(state.cooldowns):
        state.cooldowns[definition_id] = max(0, state.cooldowns[definition_id] - 1)
    if used_base is not None:
        state.cooldowns[used_base.definition_id] = used_base.cooldown_turns
        if used_base.slot_id == "A3_TACTIC_I":
            state.cooldowns[group.group_id] = group.cooldown_turns


def simulate_full_battle(
    class_name: str,
    profile: str,
    band: str,
    seed: int,
    monster_rank: str,
    behavior_mode: str,
    display_level: int,
    passive_ids: tuple[str, ...],
    skill_level: int = 100,
) -> FullBattleResult:
    catalog = active_catalog(skill_level)
    loadout = class_actives(catalog, class_name)
    assert len(loadout) == 5
    source = legacy.c1.six.source_for_display(class_name, display_level, band)
    derived = legacy.c1.six.derive_hero(source)
    hero = apply_always_on_stats(derived.combatant, class_name, passive_ids, skill_level)
    monster = legacy.c1.six.monster_for(source, profile, monster_rank)
    encounter_key = f"WAVE1.{display_level}.{monster_rank}.{profile}.{band}.{class_name}"
    tie_roll = legacy.c1.event_roll_bps(seed, 0, "INITIATIVE", encounter_key)
    order = (
        ("HERO", "MONSTER")
        if hero.speed > monster.speed or (hero.speed == monster.speed and tie_roll < 5_000)
        else ("MONSTER", "HERO")
    )
    resource_spec = legacy.c1.RESOURCES[class_name]
    state = legacy.c1.HeroState(
        hero.max_hp,
        resource_spec.start_bps,
        resource_spec.start_bps,
        encounter_start_hp=hero.max_hp,
    )
    runtime = PassiveRuntime()
    group = TACTIC_GROUPS[class_name]
    monster_hp = monster.max_hp
    status = None
    monster_action_index = 0

    for round_index in range(1, 31):
        for side in order:
            if state.hp <= 0 or monster_hp <= 0:
                break
            if side == "HERO":
                selected, used_base = select_full_action(
                    seed,
                    state,
                    runtime,
                    hero,
                    monster_hp,
                    monster.max_hp,
                    legacy.c1.core.hit_bps(monster, hero),
                    status,
                    loadout,
                    passive_ids,
                    skill_level,
                    behavior_mode,
                )
                state.action_index += 1
                if selected == "RECOVERY":
                    state.recovery_uses += 1
                    recovery = resource_spec.recovery_bps
                    if has(passive_ids, "crackedcore"):
                        recovery = legacy.c1.core.multiply_bps(recovery, 9_500)
                    state.resource = min(state.resource_ceiling, state.resource + recovery)
                elif selected == "BASIC":
                    coefficient = 10_000
                    resource_before = state.resource
                    if class_name == "MAGE":
                        if has(passive_ids, "glasscannon"):
                            coefficient += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("glasscannon")), skill_level)
                        if has(passive_ids, "crackedcore") and resource_before <= 2_500:
                            coefficient += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("crackedcore")), skill_level)
                    hit_modifier = 0
                    if has(passive_ids, "distancesense") and runtime.ranger_consecutive_hits >= 2:
                        hit_modifier += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("distancesense")), skill_level)
                    damage_modifier = 0
                    if status is not None:
                        if status.kind == "PHYSICAL_EXPOSED" and hero.attack_type == "PHYSICAL":
                            damage_modifier += status.magnitude_bps
                        if status.kind == "MAGICAL_EXPOSED" and hero.attack_type == "MAGIC":
                            damage_modifier += status.magnitude_bps
                    damage, hit = legacy.c1.resolved_attack(
                        hero,
                        monster,
                        coefficient,
                        legacy.c1.core_rolls(seed, state.action_index, "HERO", encounter_key),
                        hit_modifier_bps=hit_modifier,
                        damage_modifier_bps=damage_modifier,
                    )
                    monster_hp = max(0, monster_hp - damage)
                    state.basic_actions += 1
                    state.resource = min(
                        10_000 if resource_spec.lifecycle == "ENCOUNTER_BUILDER" else state.resource_ceiling,
                        state.resource + resource_spec.basic_generation_bps,
                    )
                    runtime.ranger_consecutive_hits = runtime.ranger_consecutive_hits + 1 if hit else 0
                    runtime.rogue_miss_ready = not hit
                    if class_name == "PALADIN":
                        runtime.paladin_previous_role = "OFFENSE"
                else:
                    oath_echo_ready_at_action_start = runtime.paladin_oath_echo_ready
                    shield_breath_ready_at_action_start = runtime.shield_breath_ready
                    resource_before = state.resource
                    legacy.c1.spend_resource(state, class_name, selected)
                    state.uses[selected.definition_id] = state.uses.get(selected.definition_id, 0) + 1
                    if used_base.slot_id == "A3_TACTIC_I":
                        state.uses[group.group_id] = state.uses.get(group.group_id, 0) + 1
                    if selected.protection_kind is not None:
                        state.protection_group_uses[selected.generation_group_id] = state.protection_group_uses.get(selected.generation_group_id, 0) + 1
                    nominal_protection = legacy.c1.core.multiply_bps(hero.max_hp, selected.protection_amount)
                    hp_before = state.hp
                    if selected.protection_kind == "SHIELD":
                        state.shield += nominal_protection
                    elif selected.protection_kind == "BARRIER":
                        state.barrier = min(1, state.barrier + selected.protection_amount)
                    elif selected.protection_kind == "HEAL":
                        state.hp = min(state.encounter_start_hp, state.hp + nominal_protection)
                    if selected.protection_kind == "HEAL":
                        actual_heal = state.hp - hp_before
                        if has(passive_ids, "heardprayer") and actual_heal < nominal_protection:
                            runtime.cleric_heard_prayer_ready = True
                        if has(passive_ids, "faithecho"):
                            runtime.cleric_faith_echo_ready = True
                    coefficient = selected.action_coefficient_bps
                    if class_name == "MAGE":
                        if has(passive_ids, "glasscannon"):
                            coefficient += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("glasscannon")), skill_level)
                        if has(passive_ids, "crackedcore") and resource_before <= 2_500:
                            coefficient += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("crackedcore")), skill_level)
                    if selected.hit_packet_count == 1:
                        hit_modifier = 0
                        if has(passive_ids, "failurestudy") and runtime.rogue_miss_ready:
                            hit_modifier += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("failurestudy")), skill_level)
                        if has(passive_ids, "distancesense") and runtime.ranger_consecutive_hits >= 2:
                            hit_modifier += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("distancesense")), skill_level)
                        if has(passive_ids, "othecho") and oath_echo_ready_at_action_start:
                            hit_modifier += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("othecho")), skill_level)
                            runtime.paladin_oath_echo_ready = False
                        damage_modifier = 0
                        if status is not None:
                            if status.kind == "PHYSICAL_EXPOSED" and hero.attack_type == "PHYSICAL":
                                damage_modifier += status.magnitude_bps
                            if status.kind == "MAGICAL_EXPOSED" and hero.attack_type == "MAGIC":
                                damage_modifier += status.magnitude_bps
                        damage, hit = legacy.c1.resolved_attack(
                            hero,
                            monster,
                            coefficient,
                            legacy.c1.core_rolls(seed, state.action_index, "HERO", encounter_key),
                            hit_modifier_bps=hit_modifier,
                            damage_modifier_bps=damage_modifier,
                            crit_eligible=selected.crit_eligible,
                            fixed_variance_bps=(10_000 if selected.variance_min_bps == selected.variance_max_bps == 10_000 else None),
                        )
                        monster_hp = max(0, monster_hp - damage)
                        runtime.rogue_miss_ready = not hit
                        runtime.ranger_consecutive_hits = runtime.ranger_consecutive_hits + 1 if hit else 0
                        if hit and monster_hp > 0 and selected.status_id is not None:
                            roll = legacy.c1.event_roll_bps(seed, state.action_index, "STATUS_RESIST_V1", selected.status_id)
                            if roll < legacy.c1.status_apply_bps(selected, legacy.c1.monster_status_resistance(source.combat_rank, profile)):
                                status = legacy.c1.HostileStatus(
                                    selected.status_id,
                                    selected.status_kind or "",
                                    selected.status_magnitude_bps,
                                    selected.status_duration_owner_turns,
                                )
                    if selected.protection_kind == "HEAL" and has(passive_ids, "othecho"):
                        runtime.paladin_oath_echo_ready = True
                    if has(passive_ids, "shieldbreath") and shield_breath_ready_at_action_start and selected.automation_band != "SURVIVAL":
                        runtime.shield_breath_ready = False
                    if selected.protection_kind == "SHIELD" and has(passive_ids, "shieldbreath"):
                        runtime.shield_breath_ready = True
                    if has(passive_ids, "manaecho"):
                        consumed_echo = runtime.mana_echo_ready
                        runtime.mana_echo_ready = selected.resource_cost_bps >= 2_000 and not consumed_echo
                    if selected.protection_kind == "SHIELD":
                        runtime.cleric_heard_prayer_ready = False
                        runtime.cleric_faith_echo_ready = False
                    if class_name == "PALADIN":
                        runtime.paladin_previous_role = skill_role(selected)
                decrement_full_cooldowns(state, used_base, group)
            else:
                monster_action_index += 1
                hit_modifier = 0
                damage_modifier = 0
                if status is not None:
                    if status.kind == "AIM_DISRUPTED":
                        hit_modifier -= status.magnitude_bps
                    elif status.kind == "POWER_SUPPRESSED":
                        damage_modifier -= status.magnitude_bps
                road_instinct_applies = (
                    has(passive_ids, "roadinstinct") and runtime.road_instinct_available
                )
                if road_instinct_applies:
                    damage_modifier += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("roadinstinct")), skill_level)
                if has(passive_ids, "glasscannon"):
                    damage_modifier += next(plan.fixed_downside_value for plan in PASSIVES if plan.definition_id.endswith("glasscannon"))
                sixth_sense_applies = (
                    has(passive_ids, "sixthsense")
                    and runtime.sixth_sense_available
                    and monster.action_coefficient_bps >= 11_000
                )
                if sixth_sense_applies:
                    damage_modifier += passive_value(next(plan for plan in PASSIVES if plan.definition_id.endswith("sixthsense")), skill_level)
                damage, hit = legacy.c1.resolved_attack(
                    monster,
                    hero,
                    monster.action_coefficient_bps,
                    legacy.c1.core_rolls(seed, monster_action_index, "MONSTER", encounter_key),
                    hit_modifier_bps=hit_modifier,
                    damage_modifier_bps=damage_modifier,
                )
                if sixth_sense_applies and hit:
                    runtime.sixth_sense_available = False
                if road_instinct_applies and hit:
                    runtime.road_instinct_available = False
                packet = legacy.c1.resolve_direct_packet(
                    hit=hit,
                    raw_damage=damage,
                    barrier=state.barrier,
                    shield=state.shield,
                    has_attached_hostile_status=False,
                )
                state.barrier = packet.barrier_after
                state.shield = packet.shield_after
                actual_hp_damage = min(state.hp, packet.hp_damage)
                if actual_hp_damage > 0:
                    state.hp -= actual_hp_damage
                    runtime.damaging_hits_taken += 1
                if actual_hp_damage > 0 and resource_spec.received_damage_generation_bps > 0:
                    legacy.c1.grant_received_damage_resource(state, class_name)
                if status is not None:
                    status.remaining_owner_turns -= 1
                    if status.remaining_owner_turns <= 0:
                        status = None
        if monster_hp <= 0 or state.hp <= 0:
            break
    outcome = "WIN" if monster_hp <= 0 else "LOSS" if state.hp <= 0 else "RETREAT"
    return FullBattleResult(
        outcome,
        min(30, round_index),
        max(0, state.hp),
        hero.max_hp,
        state.resource,
        state.uses.get(group.group_id, 0),
    )


def measure_full_build(
    class_name: str,
    profile: str,
    band: str,
    monster_rank: str,
    behavior_mode: str,
    display_level: int,
    passive_ids: tuple[str, ...],
    seeds: int,
) -> FullMetrics:
    results = [
        simulate_full_battle(
            class_name,
            profile,
            band,
            seed,
            monster_rank,
            behavior_mode,
            display_level,
            passive_ids,
        )
        for seed in range(seeds)
    ]
    losses = [
        (result.hero_max_hp - result.hero_hp) * 100.0 / result.hero_max_hp
        for result in results
    ]
    return FullMetrics(
        win_rate=sum(result.outcome == "WIN" for result in results) / seeds,
        retreat_rate=sum(result.outcome == "RETREAT" for result in results) / seeds,
        median_rounds=statistics.median(result.rounds for result in results),
        p90_hp_loss=legacy.c1.core.percentile(losses, 0.90),
    )


def check_catalog() -> None:
    assert len(legacy.c1.SKILLS) == 30
    assert len(PASSIVES) == len(PASSIVE_BY_ID) == 18
    assert len({plan.idea_id for plan in PASSIVES}) == 18
    assert len({plan.name_ko for plan in PASSIVES}) == 18
    bank = IDEA_BANK.read_text(encoding="utf-8")
    for class_name in CLASSES:
        assert len(class_actives(active_catalog(100), class_name)) == 5
        assert len(class_passives(class_name)) == 3
    for plan in PASSIVES:
        assert plan.idea_id in bank and plan.name_ko in bank
        assert len(plan.values) == 5
        assert plan.direction in {"INCREASE", "DECREASE"}
        if plan.direction == "INCREASE":
            assert list(plan.values) == sorted(plan.values)
        else:
            assert list(plan.values) == sorted(plan.values, reverse=True)
        assert plan.condition_id != "always" or plan.fixed_downside_field is not None


def check_active_anchor_migration() -> None:
    for level, anchor_index in zip(ANCHOR_LEVELS, range(5)):
        catalog = active_catalog(level)
        for skill in catalog:
            plan = legacy.PLAN_BY_ID[skill.definition_id]
            assert getattr(skill, plan.field) == plan.values[anchor_index]
    for level in range(1, 101):
        catalog = active_catalog(level)
        for skill in catalog:
            plan = legacy.PLAN_BY_ID[skill.definition_id]
            assert getattr(skill, plan.field) == collection.interpolated_legacy_skill_value(plan, level)


def check_shared_tactic_groups() -> None:
    catalog = active_catalog(100)
    for class_name in CLASSES:
        tactics = [skill for skill in class_actives(catalog, class_name) if skill.slot_id == "A3_TACTIC_I"]
        group = TACTIC_GROUPS[class_name]
        assert len(tactics) == 3
        assert {skill.cooldown_turns for skill in tactics} == {group.cooldown_turns}
        assert group.activation_bps == max(skill.activation_bps for skill in tactics)
        assert group.encounter_cap == max(skill.max_activations_per_encounter for skill in tactics)
        if any(skill.max_activations_per_expedition for skill in tactics):
            assert group.expedition_cap == max(skill.max_activations_per_expedition for skill in tactics)
        else:
            assert group.expedition_cap == 0


def check_all_loadout_subsets() -> None:
    checked = 0
    for skill_level in range(1, 101):
        catalog = active_catalog(skill_level)
        for class_name in CLASSES:
            actives = class_actives(catalog, class_name)
            passives = class_passives(class_name)
            for active_mask in range(1 << len(actives)):
                active_ids = tuple(
                    skill.definition_id for index, skill in enumerate(actives) if active_mask & (1 << index)
                )
                for passive_mask in range(1 << len(passives)):
                    passive_ids = tuple(
                        plan.definition_id for index, plan in enumerate(passives) if passive_mask & (1 << index)
                    )
                    assert len(active_ids) <= 5 and len(passive_ids) <= 3
                    envelope = attack_envelope(catalog, class_name, active_ids, passive_ids, skill_level)
                    assert envelope <= ATTACK_BUDGETS[class_name], (
                        class_name,
                        skill_level,
                        active_ids,
                        passive_ids,
                        envelope,
                    )
                    encounter_protection, expedition_protection = protection_totals(
                        catalog, class_name, active_ids
                    )
                    assert encounter_protection <= 3_000
                    assert expedition_protection <= 7_000
                    cost_modifier = worst_cost_modifier(passive_ids, skill_level)
                    assert PASSIVE_COST_REDUCTION_CAP_BPS <= cost_modifier <= 0
                    for skill in actives:
                        assert resolved_resource_cost(skill.resource_cost_bps, cost_modifier) >= FINAL_RESOURCE_COST_FLOOR_BPS
                    assert passive_modifier(passive_ids, "hit_modifier_bps", skill_level) <= 1_200
                    assert passive_modifier(passive_ids, "action_coefficient_add_bps", skill_level) <= 175
                    checked += 1
    assert checked == 153_600
    check_all_loadout_subsets.checked = checked


def check_exact_full_loadout_caps() -> None:
    catalog = active_catalog(100)
    expected = {
        "WARRIOR": 112_000,
        "ROGUE": 116_000,
        "RANGER": 112_000,
        "MAGE": 116_000,
        "CLERIC": 106_000,
        "PALADIN": 108_000,
    }
    actual = {}
    for class_name in CLASSES:
        active_ids = tuple(skill.definition_id for skill in class_actives(catalog, class_name))
        passive_ids = tuple(plan.definition_id for plan in class_passives(class_name))
        actual[class_name] = attack_envelope(catalog, class_name, active_ids, passive_ids, 100)
    assert actual == expected


def check_passive_caps_and_downsides() -> None:
    assert PASSIVE_COST_REDUCTION_CAP_BPS == -1_500
    assert FINAL_RESOURCE_COST_FLOOR_BPS == 1_000
    for plan in PASSIVES:
        values = [passive_value(plan, level) for level in range(1, 101)]
        if plan.direction == "INCREASE":
            assert all(right >= left for left, right in zip(values, values[1:]))
        else:
            assert all(right <= left for left, right in zip(values, values[1:]))
        assert tuple(values[level - 1] for level in ANCHOR_LEVELS) == plan.values
    assert max(passive_value(plan, 100) for plan in PASSIVES if plan.effect_field == "hit_modifier_bps") <= 800
    assert min(passive_value(plan, 100) for plan in PASSIVES if plan.effect_field == "incoming_damage_modifier_bps") >= -700
    assert max(passive_value(plan, 100) for plan in PASSIVES if plan.effect_field in {"physical_defense_modifier_bps", "magical_resistance_modifier_bps", "evasion_modifier_bps"}) <= 600


def check_skill_xp_and_snapshot() -> None:
    assert collection.cumulative_skill_xp(100) == 20_000_000
    ids = tuple(skill.definition_id for skill in class_actives(active_catalog(100), "WARRIOR")) + tuple(
        plan.definition_id for plan in class_passives("WARRIOR")
    )
    levels = {definition_id: 1 for definition_id in ids}
    ledger = collection.SkillXpLedger()
    assert ledger.settle("wave1-battle", ids[:5], ids[5:], 1_000_000, levels, 1)
    assert len(ledger.xp_by_skill) == 8 and set(ledger.xp_by_skill.values()) == {100_000}
    before = dict(ledger.xp_by_skill)
    assert not ledger.settle("wave1-battle", ids[:5], ids[5:], 1_000_000, levels, 1)
    assert ledger.xp_by_skill == before
    committed = {"active": ids[:5], "passive": ids[5:], "revision": 1}
    expedition = dict(committed)
    staged = {"active": ids[:4], "passive": ids[5:7], "revision": 2}
    assert expedition == committed and staged != committed


STATIC_CHECKS = (
    ("WAVE1_HAS_30_ACTIVE_AND_18_PASSIVE_DEFINITIONS", check_catalog),
    ("LEGACY_ACTIVE_ANCHORS_MIGRATE_TO_ALL_100_SKILL_LEVELS", check_active_anchor_migration),
    ("THREE_TACTICS_SHARE_ONE_ACTIVATION_COOLDOWN_AND_USE_LEDGER", check_shared_tactic_groups),
    ("ALL_153600_LOADOUT_LEVEL_SUBSETS_PRESERVE_HARD_BUDGETS", check_all_loadout_subsets),
    ("FULL_ACTIVE5_PASSIVE3_LEVEL100_ATTACK_CAPS_ARE_EXACT", check_exact_full_loadout_caps),
    ("PASSIVE_SINGLE_AXIS_CAPS_AND_FIXED_DOWNSIDES", check_passive_caps_and_downsides),
    ("SKILL_XP_EIGHT_WAY_RECEIPT_AND_EXPEDITION_SNAPSHOT", check_skill_xp_and_snapshot),
)


def measure_dynamic(seeds: int, pd: bool) -> dict:
    levels = (58, 9_999) if pd else (58,)
    bands = ("BASE", "STRESS_ALL3") if pd else ("BASE",)
    monster_ranks = ("NORMAL", "BOSS") if pd else ("NORMAL",)
    profiles = tuple(legacy.c1.PROFILES)
    behaviors = legacy.c1.BEHAVIOR_MODES
    summary = {}
    for class_name in CLASSES:
        all_passives = tuple(plan.definition_id for plan in class_passives(class_name))
        max_win_delta = 0.0
        max_round_delta = 0.0
        max_hp_delta = 0.0
        worst_weighted_loss = 0.0
        for display_level in levels:
            for band in bands:
                for monster_rank in monster_ranks:
                    for behavior in behaviors:
                        empty_metrics = []
                        full_metrics = []
                        for profile in profiles:
                            empty = measure_full_build(
                                class_name,
                                profile,
                                band,
                                monster_rank,
                                behavior,
                                display_level,
                                (),
                                seeds,
                            )
                            full = measure_full_build(
                                class_name,
                                profile,
                                band,
                                monster_rank,
                                behavior,
                                display_level,
                                all_passives,
                                seeds,
                            )
                            empty_metrics.append(empty)
                            full_metrics.append(full)
                            max_win_delta = max(max_win_delta, abs(full.win_rate - empty.win_rate))
                            max_round_delta = max(max_round_delta, abs(full.median_rounds - empty.median_rounds))
                            max_hp_delta = max(max_hp_delta, abs(full.p90_hp_loss - empty.p90_hp_loss))
                        empty_weighted = sum(
                            metric.win_rate * legacy.c1.PROFILE_WEIGHTS[profile]
                            for profile, metric in zip(profiles, empty_metrics)
                        )
                        full_weighted = sum(
                            metric.win_rate * legacy.c1.PROFILE_WEIGHTS[profile]
                            for profile, metric in zip(profiles, full_metrics)
                        )
                        worst_weighted_loss = max(worst_weighted_loss, empty_weighted - full_weighted)
        win_gate = 0.30 if seeds < 100 else 0.20
        assert max_win_delta <= win_gate + 1e-12, (class_name, "win", max_win_delta)
        assert max_round_delta <= 3.0, (class_name, "rounds", max_round_delta)
        assert max_hp_delta <= 18.0, (class_name, "hp", max_hp_delta)
        assert worst_weighted_loss <= 0.08 + 1e-12, (class_name, "weighted_loss", worst_weighted_loss)
        summary[class_name] = {
            "maxCellWinDeltaPp": max_win_delta * 100.0,
            "maxMedianRoundDelta": max_round_delta,
            "maxP90HpLossDeltaPp": max_hp_delta,
            "worstWeightedWinLossPp": worst_weighted_loss * 100.0,
        }
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=50)
    parser.add_argument("--pd", action="store_true")
    args = parser.parse_args()
    seeds = 200 if args.pd else args.seeds
    assert seeds >= 20

    passed = []
    for name, check in STATIC_CHECKS:
        check()
        passed.append(name)
    dynamic = measure_dynamic(seeds, args.pd)
    passed.append("FULL_5X3_PAIRED_DETERMINISTIC_BATTLE_STRESS")

    if DOCUMENT.exists():
        document = DOCUMENT.read_text(encoding="utf-8")
        assert canonical_hash() in document
        passed.append("DOCUMENT_CONTAINS_CANONICAL_WAVE1_HASH")

    print(f"SKILL_WAVE1_ACTIVE5_PASSIVE3_V0_1: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  loadoutLevelChecks={check_all_loadout_subsets.checked}")
    print("  level100AttackEnvelopes=" + ",".join(f"{class_name}:{ATTACK_BUDGETS[class_name]}" for class_name in CLASSES))
    for class_name, values in dynamic.items():
        print(
            f"  {class_name:8} maxDelta cellWin={values['maxCellWinDeltaPp']:.2f}pp "
            f"rounds={values['maxMedianRoundDelta']:.1f} hp={values['maxP90HpLossDeltaPp']:.2f}pp "
            f"weightedLoss={values['worstWeightedWinLossPp']:.2f}pp"
        )
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
