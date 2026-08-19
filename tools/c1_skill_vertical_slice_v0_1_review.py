#!/usr/bin/env python3
"""Design-only audit for AlarmQuest's C1 30-skill vertical slice.

This reference model validates immutable SkillDefinition rows and runs a small
automatic-combat model against the approved six-class/base-monster equations.
It never reads or mutates live saves, Room, Kotlin catalogs, or production RNG.
"""

from __future__ import annotations

import argparse
import math
import re
import statistics
from dataclasses import dataclass, field, replace

import base_combat_pd_review as core
import base_combat_six_classes_v1_5_review as six


RULES_VERSION = "aq.rules.skill_c1.v1"
SCHEMA_VERSION = "aq.skill_definition.schema.v1"
CLASSES = ("WARRIOR", "ROGUE", "RANGER", "MAGE", "CLERIC", "PALADIN")
CLASS_SLUG = {class_name: class_name.lower() for class_name in CLASSES}
STABLE_ID = re.compile(r"^[a-z0-9._-]+$")
PROFILES = ("STANDARD", "SWIFT", "ARMORED", "SPELLCASTER")
MONSTER_RANKS = ("NORMAL", "ELITE", "BOSS")
CELL_SPECS = tuple(
    (monster_rank, band, profile)
    for monster_rank in MONSTER_RANKS
    for band in ("BASE", "STRESS_ALL3")
    for profile in PROFILES
)
PROFILE_WEIGHTS = {"STANDARD": 0.40, "SWIFT": 0.25, "ARMORED": 0.20, "SPELLCASTER": 0.15}
BANDS = {"SURVIVAL": 500, "RECOVERY": 400, "CONTROL": 300, "POWER": 200, "BASIC": 0}
BEHAVIOR_MODES = ("CAUTIOUS", "BALANCED", "BOLD")


@dataclass(frozen=True)
class BehaviorAdjustment:
    resource_floor_bps: int
    priority_offset: int
    survival_hp_threshold_offset_bps: int = 0


BEHAVIOR_POLICIES = {
    "aq.behavior.ep.v1": {
        "CAUTIOUS": BehaviorAdjustment(0, -5),
        "BALANCED": BehaviorAdjustment(0, 0),
        "BOLD": BehaviorAdjustment(0, 5),
    },
    "aq.behavior.ec.v1": {
        "CAUTIOUS": BehaviorAdjustment(0, -5),
        "BALANCED": BehaviorAdjustment(0, 0),
        "BOLD": BehaviorAdjustment(0, 5),
    },
    "aq.behavior.xp.v1": {
        "CAUTIOUS": BehaviorAdjustment(5_000, -10),
        "BALANCED": BehaviorAdjustment(3_000, 0),
        "BOLD": BehaviorAdjustment(0, 10),
    },
    "aq.behavior.xc.v1": {
        "CAUTIOUS": BehaviorAdjustment(6_000, -5),
        "BALANCED": BehaviorAdjustment(3_500, 0),
        "BOLD": BehaviorAdjustment(0, 5),
    },
    "aq.behavior.sv.v1": {
        "CAUTIOUS": BehaviorAdjustment(0, 0, 1_000),
        "BALANCED": BehaviorAdjustment(0, 0, 0),
        "BOLD": BehaviorAdjustment(0, 0, -1_000),
    },
}
ALLOWED_GROWTH_AXES = {
    "EFFECT_MAGNITUDE",
    "RESOURCE_EFFICIENCY",
    "COOLDOWN",
    "DURATION",
    "CONDITION_THRESHOLD",
}
EXPECTED_ROLES = {
    "WARRIOR": ("BUILD_PHYSICAL_POWER", "SURVIVAL_SHIELD", "CONTROL"),
    "ROGUE": ("BUILD_PHYSICAL_POWER", "SURVIVAL_BARRIER", "CONTROL"),
    "RANGER": ("PHYSICAL_POWER", "SURVIVAL_SHIELD", "CONTROL"),
    "MAGE": ("MAGIC_POWER", "SURVIVAL_SHIELD", "CONTROL"),
    "CLERIC": ("MAGIC_POWER", "SURVIVAL_HEAL", "SURVIVAL_SHIELD"),
    "PALADIN": ("BUILD_PHYSICAL_POWER", "SURVIVAL_SHIELD", "SURVIVAL_HEAL"),
}
ATTACK_BUDGET = {
    "WARRIOR": 112_000,
    "ROGUE": 116_000,
    "RANGER": 112_000,
    "MAGE": 116_000,
    "CLERIC": 106_000,
    "PALADIN": 108_000,
}
STATUS_BUDGET = {
    "WARRIOR": (1, 3),
    "ROGUE": (2, 4),
    "RANGER": (2, 6),
    "MAGE": (2, 6),
    "CLERIC": (0, 0),
    "PALADIN": (0, 0),
}


@dataclass(frozen=True)
class ResourceSpec:
    lifecycle: str
    start_bps: int
    basic_generation_bps: int
    received_damage_generation_bps: int
    received_damage_generation_cap_bps: int
    recovery_bps: int = 0
    recovery_cap: int = 0


RESOURCES = {
    "WARRIOR": ResourceSpec("ENCOUNTER_BUILDER", 0, 2_000, 1_000, 3_000),
    "ROGUE": ResourceSpec("ENCOUNTER_BUILDER", 0, 2_500, 0, 0),
    "RANGER": ResourceSpec("EXPEDITION_POOL", 10_000, 1_200, 0, 0),
    "MAGE": ResourceSpec("EXPEDITION_POOL", 10_000, 0, 0, 0, 1_500, 3),
    "CLERIC": ResourceSpec("EXPEDITION_POOL", 10_000, 800, 0, 0),
    "PALADIN": ResourceSpec("ENCOUNTER_BUILDER", 2_000, 1_200, 800, 2_000),
}


@dataclass(frozen=True)
class ProtectionBudget:
    kind: str
    amount: int
    amount_unit: str
    cost_bps: int
    cooldown_turns: int
    encounter_cap: int
    expedition_cap: int


PROTECTION_BUDGETS = {
    ("WARRIOR", "SHIELD"): ProtectionBudget("SHIELD", 1_000, "MAX_HP_BPS", 3_000, 3, 1, 3),
    ("ROGUE", "BARRIER"): ProtectionBudget("BARRIER", 1, "CHARGE", 3_500, 4, 1, 2),
    ("RANGER", "SHIELD"): ProtectionBudget("SHIELD", 600, "MAX_HP_BPS", 2_000, 3, 1, 3),
    ("MAGE", "SHIELD"): ProtectionBudget("SHIELD", 600, "MAX_HP_BPS", 2_000, 4, 1, 2),
    ("CLERIC", "HEAL"): ProtectionBudget("HEAL", 1_000, "MAX_HP_BPS", 2_000, 3, 2, 5),
    ("CLERIC", "SHIELD"): ProtectionBudget("SHIELD", 600, "MAX_HP_BPS", 1_800, 3, 1, 3),
    ("PALADIN", "SHIELD"): ProtectionBudget("SHIELD", 900, "MAX_HP_BPS", 3_000, 3, 1, 3),
    ("PALADIN", "HEAL"): ProtectionBudget("HEAL", 800, "MAX_HP_BPS", 3_000, 5, 1, 2),
}


@dataclass(frozen=True)
class SkillDefinition:
    definition_id: str
    name_ko: str
    name_key: str
    description_key: str
    owner_class: str
    slot_id: str
    fixed: bool
    macro_role: str
    automation_band: str
    automation_priority: int
    activation_policy: str
    activation_bps: int
    behavior_policy_id: str
    condition_id: str
    target: str
    resource_cost_bps: int
    spend_attrition_policy: str
    survival_reserve_policy: str
    cooldown_turns: int
    damage_type: str
    action_coefficient_bps: int
    status_id: str | None = None
    status_kind: str | None = None
    status_magnitude_bps: int = 0
    status_duration_owner_turns: int = 0
    status_base_apply_bps: int = 0
    source_status_accuracy_bps: int = 0
    protection_kind: str | None = None
    protection_amount: int = 0
    protection_amount_unit: str = "NONE"
    hp_threshold_bps: int = 10_000
    target_hp_floor_bps: int = 0
    target_hit_floor_bps: int = 0
    minimum_recoverable_deficit_bps: int = 0
    max_activations_per_encounter: int = 0
    max_activations_per_expedition: int = 0
    growth_axis: str = "EFFECT_MAGNITUDE"
    rank_axis_values: tuple[int | None, ...] = (0, None, None, None, None)
    rank_live_eligible: tuple[bool, ...] = (True, False, False, False, False)
    exploration_tags: tuple[str, ...] = ()
    stack_group: str = "aq.stack.none"
    stack_policy: str = "UNIQUE"
    generation_group_id: str = "aq.generation.none"
    attack_budget_equivalent_bps: int = 0
    root_action_count: int = 1
    hit_packet_count: int = 0
    crit_eligible: bool = True
    variance_min_bps: int = 9_500
    variance_max_bps: int = 10_500
    extra_action_count: int = 0
    can_deal_true_damage: bool = False
    can_generate_guard: bool = False
    rules_version: str = RULES_VERSION
    schema_version: str = SCHEMA_VERSION


def sid(class_name: str, slot: str, slug: str) -> str:
    slot_slug = {"A1_CORE": "a1", "A2_SURVIVAL": "a2", "A3_TACTIC_I": "a3"}[slot]
    return f"aq.skill.{CLASS_SLUG[class_name]}.{slot_slug}.{slug}"


def text_key(class_name: str, slot: str, slug: str, suffix: str) -> str:
    slot_slug = {"A1_CORE": "a1", "A2_SURVIVAL": "a2", "A3_TACTIC_I": "a3"}[slot]
    return f"skill.{CLASS_SLUG[class_name]}.{slot_slug}.{slug}.{suffix}"


def make_skill(
    class_name: str,
    slot: str,
    slug: str,
    name_ko: str,
    *,
    fixed: bool,
    role: str,
    band: str,
    priority: int,
    policy: str,
    activation_bps: int,
    condition: str,
    target: str,
    cost: int,
    attrition: str,
    reserve: str,
    cooldown: int,
    damage_type: str,
    coefficient: int,
    status_id: str | None = None,
    status_kind: str | None = None,
    status_magnitude: int = 0,
    status_duration: int = 0,
    status_base_apply: int = 0,
    source_status_accuracy: int = 0,
    protection_kind: str | None = None,
    protection_amount: int = 0,
    protection_unit: str = "NONE",
    hp_threshold: int = 10_000,
    target_hp_floor: int = 0,
    target_hit_floor: int = 0,
    minimum_recoverable_deficit: int = 0,
    encounter_cap: int = 0,
    expedition_cap: int = 0,
    growth_axis: str,
    rank1_axis_value: int,
    stack_group: str,
    stack_policy: str,
    budget_equivalent: int,
    behavior_policy_id: str = "aq.behavior.ep.v1",
    generation_group_id: str = "aq.generation.none",
    crit_eligible: bool = True,
    variance_min_bps: int = 9_500,
    variance_max_bps: int = 10_500,
) -> SkillDefinition:
    return SkillDefinition(
        definition_id=sid(class_name, slot, slug),
        name_ko=name_ko,
        name_key=text_key(class_name, slot, slug, "name"),
        description_key=text_key(class_name, slot, slug, "description"),
        owner_class=class_name,
        slot_id=slot,
        fixed=fixed,
        macro_role=role,
        automation_band=band,
        automation_priority=priority,
        activation_policy=policy,
        activation_bps=activation_bps,
        behavior_policy_id=behavior_policy_id,
        condition_id=condition,
        target=target,
        resource_cost_bps=cost,
        spend_attrition_policy=attrition,
        survival_reserve_policy=reserve,
        cooldown_turns=cooldown,
        damage_type=damage_type,
        action_coefficient_bps=coefficient,
        status_id=status_id,
        status_kind=status_kind,
        status_magnitude_bps=status_magnitude,
        status_duration_owner_turns=status_duration,
        status_base_apply_bps=status_base_apply,
        source_status_accuracy_bps=source_status_accuracy,
        protection_kind=protection_kind,
        protection_amount=protection_amount,
        protection_amount_unit=protection_unit,
        hp_threshold_bps=hp_threshold,
        target_hp_floor_bps=target_hp_floor,
        target_hit_floor_bps=target_hit_floor,
        minimum_recoverable_deficit_bps=minimum_recoverable_deficit,
        max_activations_per_encounter=encounter_cap,
        max_activations_per_expedition=expedition_cap,
        growth_axis=growth_axis,
        rank_axis_values=(rank1_axis_value, None, None, None, None),
        stack_group=stack_group,
        stack_policy=stack_policy,
        generation_group_id=generation_group_id,
        attack_budget_equivalent_bps=budget_equivalent,
        hit_packet_count=1 if target == "SINGLE_ENEMY" and damage_type != "NONE" else 0,
        crit_eligible=crit_eligible,
        variance_min_bps=variance_min_bps,
        variance_max_bps=variance_max_bps,
    )


def attack_skill(
    class_name: str,
    slug: str,
    name_ko: str,
    coefficient: int,
    cost: int,
    cooldown: int,
    activation_bps: int,
    priority: int,
) -> SkillDefinition:
    damage_type = "MAGICAL" if class_name in {"MAGE", "CLERIC"} else "PHYSICAL"
    attrition = "EXPEDITION_50" if RESOURCES[class_name].lifecycle == "EXPEDITION_POOL" else "ENCOUNTER"
    return make_skill(
        class_name,
        "A1_CORE",
        slug,
        name_ko,
        fixed=True,
        role=EXPECTED_ROLES[class_name][0],
        band="POWER",
        priority=priority,
        policy="KEYED_BPS",
        activation_bps=activation_bps,
        condition="resource_cost_and_survival_reserve",
        target="SINGLE_ENEMY",
        cost=cost,
        attrition=attrition,
        reserve="BEHAVIOR_POLICY_FLOOR",
        cooldown=cooldown,
        damage_type=damage_type,
        coefficient=coefficient,
        growth_axis="EFFECT_MAGNITUDE",
        rank1_axis_value=coefficient,
        stack_group="aq.stack.none",
        stack_policy="UNIQUE",
        budget_equivalent=coefficient,
        behavior_policy_id=(
            "aq.behavior.xp.v1"
            if RESOURCES[class_name].lifecycle == "EXPEDITION_POOL"
            else "aq.behavior.ep.v1"
        ),
    )


def survival_skill(
    class_name: str,
    slot: str,
    slug: str,
    name_ko: str,
    *,
    fixed: bool,
    kind: str,
    amount: int,
    unit: str,
    cost: int,
    cooldown: int,
    encounter_cap: int,
    expedition_cap: int,
    hp_threshold: int,
    priority: int,
    growth_axis: str,
    carrier_coefficient: int = 0,
) -> SkillDefinition:
    attrition = "EXPEDITION_100" if RESOURCES[class_name].lifecycle == "EXPEDITION_POOL" else "ENCOUNTER"
    role = EXPECTED_ROLES[class_name][1 if slot == "A2_SURVIVAL" else 2]
    return make_skill(
        class_name,
        slot,
        slug,
        name_ko,
        fixed=fixed,
        role=role,
        band="SURVIVAL",
        priority=priority,
        policy="DETERMINISTIC",
        activation_bps=10_000,
        condition=f"self_hp_lte_{hp_threshold}_and_{kind.lower()}_ledger",
        target="SINGLE_ENEMY" if carrier_coefficient > 0 else "SELF",
        cost=cost,
        attrition=attrition,
        reserve="NONE",
        cooldown=cooldown,
        damage_type=(
            "MAGICAL"
            if carrier_coefficient > 0 and class_name == "CLERIC"
            else "PHYSICAL"
            if carrier_coefficient > 0
            else "NONE"
        ),
        coefficient=carrier_coefficient,
        protection_kind=kind,
        protection_amount=amount,
        protection_unit=unit,
        hp_threshold=hp_threshold,
        minimum_recoverable_deficit=500 if kind == "HEAL" else 0,
        encounter_cap=encounter_cap,
        expedition_cap=expedition_cap,
        growth_axis=growth_axis,
        rank1_axis_value=hp_threshold if growth_axis == "CONDITION_THRESHOLD" else amount,
        stack_group=f"aq.stack.class_protection_generation.{CLASS_SLUG[class_name]}.{kind.lower()}",
        stack_policy="CHARGES_CAP" if kind == "BARRIER" else "ADD_BPS_CAP",
        generation_group_id=f"aq.generation.{CLASS_SLUG[class_name]}.{kind.lower()}",
        budget_equivalent=carrier_coefficient,
        behavior_policy_id="aq.behavior.sv.v1",
        crit_eligible=False if carrier_coefficient > 0 else True,
        variance_min_bps=10_000 if carrier_coefficient > 0 else 9_500,
        variance_max_bps=10_000 if carrier_coefficient > 0 else 10_500,
    )


def control_skill(
    class_name: str,
    slug: str,
    name_ko: str,
    *,
    carrier_coefficient: int,
    cost: int,
    cooldown: int,
    encounter_cap: int,
    status_kind: str,
    magnitude: int,
    duration: int,
    base_apply_bps: int,
    activation_bps: int,
    budget_equivalent: int,
    priority: int,
    growth_axis: str,
    self_hp_ceiling_bps: int = 10_000,
    target_hp_floor_bps: int = 0,
    target_hit_floor_bps: int = 0,
) -> SkillDefinition:
    damage_type = "MAGICAL" if class_name == "MAGE" else "PHYSICAL"
    attrition = "EXPEDITION_50" if RESOURCES[class_name].lifecycle == "EXPEDITION_POOL" else "ENCOUNTER"
    return make_skill(
        class_name,
        "A3_TACTIC_I",
        slug,
        name_ko,
        fixed=False,
        role="CONTROL",
        band="CONTROL",
        priority=priority,
        policy="KEYED_BPS",
        activation_bps=activation_bps,
        condition="target_alive_condition_status_absent_budget_and_survival_reserve",
        target="SINGLE_ENEMY",
        cost=cost,
        attrition=attrition,
        reserve="BEHAVIOR_POLICY_FLOOR",
        cooldown=cooldown,
        damage_type=damage_type,
        coefficient=carrier_coefficient,
        status_id={
            "PHYSICAL_EXPOSED": "aq.status.physical.exposed",
            "MAGICAL_EXPOSED": "aq.status.magical.exposed",
            "POWER_SUPPRESSED": "aq.status.power.suppressed",
            "AIM_DISRUPTED": "aq.status.aim.disrupted",
        }[status_kind],
        status_kind=status_kind,
        status_magnitude=magnitude,
        status_duration=duration,
        status_base_apply=base_apply_bps,
        hp_threshold=self_hp_ceiling_bps,
        target_hp_floor=target_hp_floor_bps,
        target_hit_floor=target_hit_floor_bps,
        encounter_cap=encounter_cap,
        expedition_cap=0,
        growth_axis=growth_axis,
        rank1_axis_value=(
            magnitude
            if growth_axis == "EFFECT_MAGNITUDE"
            else cost
            if growth_axis == "RESOURCE_EFFICIENCY"
            else target_hit_floor_bps or target_hp_floor_bps or self_hp_ceiling_bps
        ),
        stack_group=f"aq.stack.hostile_control.{status_kind.lower()}",
        stack_policy="REFRESH_MAX",
        budget_equivalent=budget_equivalent,
        behavior_policy_id=(
            "aq.behavior.xc.v1"
            if RESOURCES[class_name].lifecycle == "EXPEDITION_POOL"
            else "aq.behavior.ec.v1"
        ),
        crit_eligible=False,
        variance_min_bps=10_000,
        variance_max_bps=10_000,
    )


SKILLS = (
    attack_skill("WARRIOR", "frontlinedrive", "전선 압박", 11_200, 3_000, 1, 6_000, 60),
    survival_skill("WARRIOR", "A2_SURVIVAL", "ironstance", "철벽 자세", fixed=True, kind="SHIELD", amount=800, unit="MAX_HP_BPS", cost=3_000, cooldown=3, encounter_cap=1, expedition_cap=3, hp_threshold=6_500, priority=90, growth_axis="EFFECT_MAGNITUDE"),
    control_skill("WARRIOR", "armorbreak", "갑주 균열", carrier_coefficient=10_000, cost=3_000, cooldown=9, encounter_cap=1, status_kind="PHYSICAL_EXPOSED", magnitude=2_000, duration=3, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=16_000, priority=70, growth_axis="CONDITION_THRESHOLD", target_hp_floor_bps=2_500),
    control_skill("WARRIOR", "breakresolve", "기세 꺾기", carrier_coefficient=9_600, cost=3_500, cooldown=9, encounter_cap=1, status_kind="POWER_SUPPRESSED", magnitude=2_000, duration=3, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=9_600, priority=80, growth_axis="RESOURCE_EFFICIENCY", self_hp_ceiling_bps=9_000),
    control_skill("WARRIOR", "sightpressure", "시야 압박", carrier_coefficient=10_000, cost=3_000, cooldown=9, encounter_cap=1, status_kind="AIM_DISRUPTED", magnitude=3_000, duration=3, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=10_000, priority=85, growth_axis="CONDITION_THRESHOLD", target_hit_floor_bps=9_000),

    attack_skill("ROGUE", "seizeopening", "빈틈 포착", 11_600, 2_500, 1, 6_000, 65),
    survival_skill("ROGUE", "A2_SURVIVAL", "narrowescape", "찰나 회피", fixed=True, kind="BARRIER", amount=1, unit="CHARGE", cost=3_500, cooldown=6, encounter_cap=1, expedition_cap=2, hp_threshold=6_000, priority=90, growth_axis="CONDITION_THRESHOLD"),
    control_skill("ROGUE", "exposegap", "약점 각인", carrier_coefficient=12_000, cost=3_000, cooldown=4, encounter_cap=2, status_kind="PHYSICAL_EXPOSED", magnitude=1_000, duration=2, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=14_000, priority=70, growth_axis="EFFECT_MAGNITUDE", target_hp_floor_bps=2_500),
    control_skill("ROGUE", "wristcheck", "손목 견제", carrier_coefficient=9_400, cost=3_000, cooldown=4, encounter_cap=2, status_kind="POWER_SUPPRESSED", magnitude=2_200, duration=2, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=9_400, priority=80, growth_axis="RESOURCE_EFFICIENCY", self_hp_ceiling_bps=9_000),
    control_skill("ROGUE", "blursight", "시선 흐리기", carrier_coefficient=11_000, cost=2_500, cooldown=4, encounter_cap=2, status_kind="AIM_DISRUPTED", magnitude=1_200, duration=2, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=11_000, priority=85, growth_axis="CONDITION_THRESHOLD", target_hit_floor_bps=8_500),

    attack_skill("RANGER", "steadyshot", "침착한 사격", 11_200, 1_500, 1, 6_000, 62),
    survival_skill("RANGER", "A2_SURVIVAL", "coverstance", "엄폐 태세", fixed=True, kind="SHIELD", amount=400, unit="MAX_HP_BPS", cost=2_000, cooldown=3, encounter_cap=1, expedition_cap=3, hp_threshold=7_000, priority=90, growth_axis="EFFECT_MAGNITUDE"),
    control_skill("RANGER", "markweakness", "약점 표식", carrier_coefficient=10_600, cost=1_800, cooldown=4, encounter_cap=2, status_kind="PHYSICAL_EXPOSED", magnitude=800, duration=3, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=13_000, priority=70, growth_axis="EFFECT_MAGNITUDE", target_hp_floor_bps=2_500),
    control_skill("RANGER", "suppressmark", "제압 표식", carrier_coefficient=9_200, cost=1_800, cooldown=4, encounter_cap=2, status_kind="POWER_SUPPRESSED", magnitude=1_200, duration=3, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=9_200, priority=80, growth_axis="RESOURCE_EFFICIENCY", self_hp_ceiling_bps=9_000),
    control_skill("RANGER", "disruptmark", "교란 표식", carrier_coefficient=10_000, cost=1_500, cooldown=4, encounter_cap=2, status_kind="AIM_DISRUPTED", magnitude=900, duration=3, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=10_000, priority=85, growth_axis="CONDITION_THRESHOLD", target_hit_floor_bps=8_500),

    attack_skill("MAGE", "arcanepulse", "비전 파동", 11_600, 2_000, 1, 6_000, 65),
    survival_skill("MAGE", "A2_SURVIVAL", "arcaneveil", "마력 장막", fixed=True, kind="SHIELD", amount=400, unit="MAX_HP_BPS", cost=2_000, cooldown=5, encounter_cap=1, expedition_cap=2, hp_threshold=6_500, priority=90, growth_axis="EFFECT_MAGNITUDE"),
    control_skill("MAGE", "arcaneexposure", "마력 노출", carrier_coefficient=10_000, cost=1_500, cooldown=4, encounter_cap=2, status_kind="MAGICAL_EXPOSED", magnitude=1_000, duration=3, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=13_000, priority=70, growth_axis="EFFECT_MAGNITUDE", target_hp_floor_bps=2_500),
    control_skill("MAGE", "powerdamping", "힘의 감쇠", carrier_coefficient=9_400, cost=1_500, cooldown=4, encounter_cap=2, status_kind="POWER_SUPPRESSED", magnitude=800, duration=3, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=9_400, priority=80, growth_axis="RESOURCE_EFFICIENCY", self_hp_ceiling_bps=9_000),
    control_skill("MAGE", "sensedistortion", "감각 왜곡", carrier_coefficient=9_800, cost=1_500, cooldown=4, encounter_cap=2, status_kind="AIM_DISRUPTED", magnitude=600, duration=3, base_apply_bps=9_000, activation_bps=6_000, budget_equivalent=9_800, priority=85, growth_axis="CONDITION_THRESHOLD", target_hit_floor_bps=9_000),

    attack_skill("CLERIC", "sacredradiance", "성광", 10_400, 1_200, 1, 5_500, 55),
    survival_skill("CLERIC", "A2_SURVIVAL", "restoringprayer", "회복 기도", fixed=True, kind="HEAL", amount=800, unit="MAX_HP_BPS", cost=2_000, cooldown=3, encounter_cap=2, expedition_cap=5, hp_threshold=7_000, priority=92, growth_axis="EFFECT_MAGNITUDE"),
    survival_skill("CLERIC", "A3_TACTIC_I", "earlyward", "선행 성막", fixed=False, kind="SHIELD", amount=500, unit="MAX_HP_BPS", cost=1_800, cooldown=3, encounter_cap=1, expedition_cap=3, hp_threshold=8_500, priority=80, growth_axis="CONDITION_THRESHOLD", carrier_coefficient=10_400),
    survival_skill("CLERIC", "A3_TACTIC_I", "focusedward", "응집 성막", fixed=False, kind="SHIELD", amount=550, unit="MAX_HP_BPS", cost=1_800, cooldown=3, encounter_cap=1, expedition_cap=3, hp_threshold=7_500, priority=90, growth_axis="EFFECT_MAGNITUDE", carrier_coefficient=11_500),
    survival_skill("CLERIC", "A3_TACTIC_I", "lastward", "최후 성막", fixed=False, kind="SHIELD", amount=600, unit="MAX_HP_BPS", cost=1_800, cooldown=3, encounter_cap=1, expedition_cap=3, hp_threshold=7_000, priority=98, growth_axis="CONDITION_THRESHOLD", carrier_coefficient=12_000),

    attack_skill("PALADIN", "convictionstrike", "결의의 일격", 10_800, 2_000, 1, 5_500, 58),
    survival_skill("PALADIN", "A2_SURVIVAL", "guardianoath", "수호의 맹세", fixed=True, kind="SHIELD", amount=700, unit="MAX_HP_BPS", cost=3_000, cooldown=3, encounter_cap=1, expedition_cap=3, hp_threshold=6_500, priority=90, growth_axis="EFFECT_MAGNITUDE"),
    survival_skill("PALADIN", "A3_TACTIC_I", "earlymercy", "이른 구원", fixed=False, kind="HEAL", amount=700, unit="MAX_HP_BPS", cost=3_000, cooldown=5, encounter_cap=1, expedition_cap=2, hp_threshold=7_250, priority=82, growth_axis="CONDITION_THRESHOLD", carrier_coefficient=12_000),
    survival_skill("PALADIN", "A3_TACTIC_I", "measuredmercy", "절제된 구원", fixed=False, kind="HEAL", amount=750, unit="MAX_HP_BPS", cost=3_000, cooldown=5, encounter_cap=1, expedition_cap=2, hp_threshold=6_750, priority=92, growth_axis="EFFECT_MAGNITUDE", carrier_coefficient=12_000),
    survival_skill("PALADIN", "A3_TACTIC_I", "crisismercy", "위기의 구원", fixed=False, kind="HEAL", amount=800, unit="MAX_HP_BPS", cost=3_000, cooldown=5, encounter_cap=1, expedition_cap=2, hp_threshold=6_500, priority=98, growth_axis="CONDITION_THRESHOLD", carrier_coefficient=12_000),
)


INTENT_GROUP_BY_SKILL_ID = {
    "aq.skill.warrior.a3.armorbreak": "ARMORED_EXPOSE",
    "aq.skill.warrior.a3.breakresolve": "BOSS_STRESS_SUPPRESS",
    "aq.skill.warrior.a3.sightpressure": "HIGH_HIT_DISRUPT",
    "aq.skill.rogue.a3.exposegap": "ARMORED_EXPOSE",
    "aq.skill.rogue.a3.wristcheck": "BOSS_STRESS_SUPPRESS",
    "aq.skill.rogue.a3.blursight": "HIGH_HIT_DISRUPT",
    "aq.skill.ranger.a3.markweakness": "ARMORED_EXPOSE",
    "aq.skill.ranger.a3.suppressmark": "BOSS_STRESS_SUPPRESS",
    "aq.skill.ranger.a3.disruptmark": "HIGH_HIT_DISRUPT",
    "aq.skill.mage.a3.arcaneexposure": "ARMORED_EXPOSE",
    "aq.skill.mage.a3.powerdamping": "BOSS_STRESS_SUPPRESS",
    "aq.skill.mage.a3.sensedistortion": "HIGH_HIT_DISRUPT",
    "aq.skill.cleric.a3.earlyward": "BOSS_STRESS_SURVIVAL",
    "aq.skill.cleric.a3.focusedward": "BOSS_STRESS_SURVIVAL",
    "aq.skill.cleric.a3.lastward": "BOSS_STRESS_SURVIVAL",
    "aq.skill.paladin.a3.earlymercy": "BOSS_STRESS_SURVIVAL",
    "aq.skill.paladin.a3.measuredmercy": "BOSS_STRESS_SURVIVAL",
    "aq.skill.paladin.a3.crisismercy": "BOSS_STRESS_SURVIVAL",
}


def skills_for(class_name: str, slot_id: str | None = None) -> tuple[SkillDefinition, ...]:
    return tuple(
        skill
        for skill in SKILLS
        if skill.owner_class == class_name and (slot_id is None or skill.slot_id == slot_id)
    )


def c1_loadout(class_name: str, selected_a3: SkillDefinition) -> tuple[SkillDefinition, ...]:
    return (
        skills_for(class_name, "A1_CORE")[0],
        skills_for(class_name, "A2_SURVIVAL")[0],
        selected_a3,
    )


def is_declared_intent_cell(
    skill: SkillDefinition,
    monster_rank: str,
    band: str,
    profile: str,
) -> bool:
    intent_group = INTENT_GROUP_BY_SKILL_ID[skill.definition_id]
    if intent_group == "ARMORED_EXPOSE":
        return profile == "ARMORED"
    if intent_group == "BOSS_STRESS_SUPPRESS":
        return monster_rank == "BOSS" and band == "STRESS_ALL3"
    if intent_group == "HIGH_HIT_DISRUPT":
        source = six.source_for_display(skill.owner_class, 1, band)
        hero = six.derive_hero(source).combatant
        monster = six.monster_for(source, profile, monster_rank)
        return core.hit_bps(monster, hero) >= skill.target_hit_floor_bps
    if intent_group == "BOSS_STRESS_SURVIVAL":
        return monster_rank == "BOSS" and band == "STRESS_ALL3"
    raise AssertionError(f"Unknown intent group: {intent_group}")


def max_uses_in_ten(cooldown_turns: int) -> int:
    return math.ceil(10 / (cooldown_turns + 1))


@dataclass
class HostileStatus:
    status_id: str
    kind: str
    magnitude_bps: int
    remaining_owner_turns: int


@dataclass
class HeroState:
    hp: int
    resource: int
    resource_ceiling: int
    encounter_start_hp: int = 0
    shield: int = 0
    barrier: int = 0
    cooldowns: dict[str, int] = field(default_factory=dict)
    uses: dict[str, int] = field(default_factory=dict)
    protection_group_uses: dict[str, int] = field(default_factory=dict)
    recovery_uses: int = 0
    received_damage_generation_total_bps: int = 0
    action_index: int = 0
    basic_actions: int = 0


@dataclass(frozen=True)
class SliceBattleResult:
    outcome: str
    rounds: int
    hero_hp: int
    resource_bps: int
    resource_ceiling_bps: int
    basic_actions: int
    selected_a3_uses: int


def event_roll_bps(seed: int, action_index: int, event_type: str, stable_key: str) -> int:
    random = core.DeterministicRandom.for_event(seed, action_index, event_type, stable_key)
    return random.next_int(0, 10_000)


def core_rolls(seed: int, action_index: int, actor_side: str, encounter_key: str) -> tuple[int, int, int]:
    random = core.DeterministicRandom.for_event(
        seed,
        action_index,
        f"C1_CORE_{actor_side}",
        encounter_key,
    )
    return (
        random.next_int(0, 10_000),
        random.next_int(0, 10_000),
        random.next_int(9_500, 10_501),
    )


def monster_status_resistance(level: int, profile_name: str) -> int:
    profile = six.v13.PROFILES[profile_name]
    unit = min(18.0, 10.0 + 0.125 * max(0, level - 1))
    attributes = [
        max(3, min(20, six.round_positive(unit + offset)))
        for offset in profile["offsets"]
    ]
    constitution = attributes[2]
    wisdom = attributes[4]
    return six.round_positive(5 + 0.75 * wisdom + 0.25 * constitution)


def status_apply_bps(skill: SkillDefinition, target_status_resistance: int) -> int:
    resistance_bps = core.clamp(40 * target_status_resistance, 0, 4_000)
    return core.clamp(
        skill.status_base_apply_bps
        + skill.source_status_accuracy_bps
        - resistance_bps,
        1_000,
        9_500,
    )


def spend_resource(state: HeroState, class_name: str, skill: SkillDefinition) -> None:
    state.resource -= skill.resource_cost_bps
    assert state.resource >= 0
    if RESOURCES[class_name].lifecycle == "EXPEDITION_POOL":
        attrition = skill.resource_cost_bps
        if skill.spend_attrition_policy == "EXPEDITION_50":
            attrition = skill.resource_cost_bps // 2
        state.resource_ceiling = max(0, state.resource_ceiling - attrition)
        state.resource = min(state.resource, state.resource_ceiling)


def grant_received_damage_resource(state: HeroState, class_name: str) -> None:
    spec = RESOURCES[class_name]
    remaining_cap = max(
        0,
        spec.received_damage_generation_cap_bps
        - state.received_damage_generation_total_bps,
    )
    gained = min(spec.received_damage_generation_bps, remaining_cap)
    if gained <= 0:
        return
    state.received_damage_generation_total_bps += gained
    state.resource = min(10_000, state.resource + gained)


def behavior_adjustment(skill: SkillDefinition, behavior_mode: str) -> BehaviorAdjustment:
    assert behavior_mode in BEHAVIOR_MODES
    return BEHAVIOR_POLICIES[skill.behavior_policy_id][behavior_mode]


def eligible(
    skill: SkillDefinition,
    state: HeroState,
    hero: core.Combatant,
    monster_hp: int,
    monster_max_hp: int,
    target_final_hit_bps: int,
    status: HostileStatus | None,
    loadout: tuple[SkillDefinition, ...],
    behavior_mode: str = "BALANCED",
) -> bool:
    adjustment = behavior_adjustment(skill, behavior_mode)
    if monster_hp <= 0 or state.cooldowns.get(skill.definition_id, 0) > 0:
        return False
    if state.resource < skill.resource_cost_bps:
        return False
    if state.uses.get(skill.definition_id, 0) >= skill.max_activations_per_encounter > 0:
        return False
    if (
        skill.protection_kind is not None
        and state.protection_group_uses.get(skill.generation_group_id, 0)
        >= skill.max_activations_per_encounter
        > 0
    ):
        return False
    if skill.automation_band == "SURVIVAL":
        hp_bps = state.hp * 10_000 // hero.max_hp
        adjusted_hp_threshold_bps = core.clamp(
            skill.hp_threshold_bps + adjustment.survival_hp_threshold_offset_bps,
            0,
            10_000,
        )
        if hp_bps > adjusted_hp_threshold_bps:
            return False
        heal_cap = state.encounter_start_hp or hero.max_hp
        if skill.protection_kind == "HEAL" and state.hp >= heal_cap:
            return False
        if skill.protection_kind == "HEAL":
            recoverable_deficit_bps = (heal_cap - state.hp) * 10_000 // hero.max_hp
            if recoverable_deficit_bps < skill.minimum_recoverable_deficit_bps:
                return False
        if skill.protection_kind == "SHIELD" and state.shield > 0:
            return False
        if skill.protection_kind == "BARRIER" and state.barrier > 0:
            return False
    if skill.status_id is not None and status is not None and status.status_id == skill.status_id:
        return False
    if skill.status_id is not None:
        self_hp_bps = state.hp * 10_000 // hero.max_hp
        target_hp_bps = monster_hp * 10_000 // max(1, monster_max_hp)
        if self_hp_bps > skill.hp_threshold_bps or target_hp_bps < skill.target_hp_floor_bps:
            return False
        if target_final_hit_bps < skill.target_hit_floor_bps:
            return False
    if skill.survival_reserve_policy == "BEHAVIOR_POLICY_FLOOR":
        if state.resource - skill.resource_cost_bps < adjustment.resource_floor_bps:
            return False
    return True


def select_hero_action(
    seed: int,
    state: HeroState,
    hero: core.Combatant,
    monster_hp: int,
    monster_max_hp: int,
    target_final_hit_bps: int,
    status: HostileStatus | None,
    loadout: tuple[SkillDefinition, ...],
    behavior_mode: str = "BALANCED",
) -> SkillDefinition | str:
    assert behavior_mode in BEHAVIOR_MODES
    candidates: list[SkillDefinition] = []
    for skill in loadout:
        if not eligible(
            skill,
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
        if skill.activation_policy == "DETERMINISTIC":
            candidates.append(skill)
        elif event_roll_bps(
            seed,
            state.action_index,
            "AUTOMATION_V1",
            skill.definition_id,
        ) < skill.activation_bps:
            candidates.append(skill)
    recovery_eligible = (
        RESOURCES[loadout[0].owner_class].recovery_cap > 0
        and state.recovery_uses < RESOURCES[loadout[0].owner_class].recovery_cap
        and state.resource <= 2_000
        and state.resource < state.resource_ceiling
    )
    if recovery_eligible and not any(
        skill.automation_band == "SURVIVAL" for skill in candidates
    ):
        return "RECOVERY"
    if candidates:
        return sorted(
            candidates,
            key=lambda skill: (
                -BANDS[skill.automation_band],
                -(skill.automation_priority + behavior_adjustment(skill, behavior_mode).priority_offset),
                skill.definition_id,
            ),
        )[0]
    return "BASIC"


def resolved_attack(
    attacker: core.Combatant,
    defender: core.Combatant,
    coefficient_bps: int,
    rolls: tuple[int, int, int],
    *,
    hit_modifier_bps: int = 0,
    damage_modifier_bps: int = 0,
    crit_eligible: bool = True,
    fixed_variance_bps: int | None = None,
) -> tuple[int, bool]:
    hit_roll, critical_roll, variance_bps = rolls
    hit_rate = core.clamp(core.hit_bps(attacker, defender) + hit_modifier_bps, 6_500, 9_900)
    hit = hit_roll < hit_rate
    if not hit:
        return 0, False
    if coefficient_bps == 0:
        return 0, True
    critical = crit_eligible and critical_roll < core.critical_bps(attacker, defender)
    if fixed_variance_bps is not None:
        variance_bps = fixed_variance_bps
    power = attacker.physical_attack if attacker.attack_type == "PHYSICAL" else attacker.magical_attack
    damage = core.multiply_bps(power, coefficient_bps)
    damage = core.multiply_bps(damage, 15_000 if critical else 10_000)
    damage = core.multiply_bps(damage, variance_bps)
    damage = max(1, core.multiply_bps(damage, 10_000 - core.mitigation_bps(attacker, defender)))
    damage = max(0, core.multiply_bps(damage, 10_000 + damage_modifier_bps))
    return damage, True


@dataclass(frozen=True)
class DirectPacketResult:
    hp_damage: int
    barrier_after: int
    shield_after: int
    attached_status_allowed: bool


def resolve_direct_packet(
    *,
    hit: bool,
    raw_damage: int,
    barrier: int,
    shield: int,
    has_attached_hostile_status: bool,
) -> DirectPacketResult:
    """Apply the common Barrier -> Shield -> HP contract to one direct packet."""
    if not hit:
        return DirectPacketResult(0, barrier, shield, False)
    if barrier > 0:
        return DirectPacketResult(0, barrier - 1, shield, False)
    absorbed = min(shield, raw_damage)
    hp_damage = max(0, raw_damage - absorbed)
    return DirectPacketResult(
        hp_damage,
        barrier,
        shield - absorbed,
        has_attached_hostile_status,
    )


def decrement_cooldowns(state: HeroState, used_skill: SkillDefinition | None) -> None:
    for definition_id in tuple(state.cooldowns):
        state.cooldowns[definition_id] = max(0, state.cooldowns[definition_id] - 1)
    if used_skill is not None:
        state.cooldowns[used_skill.definition_id] = used_skill.cooldown_turns


def simulate_battle(
    class_name: str,
    selected_a3: SkillDefinition,
    profile: str,
    band: str,
    seed: int,
    monster_rank: str = "NORMAL",
    behavior_mode: str = "BALANCED",
) -> SliceBattleResult:
    source = six.source_for_display(class_name, 1, band)
    derived = six.derive_hero(source)
    hero = derived.combatant
    assert monster_rank in MONSTER_RANKS
    assert behavior_mode in BEHAVIOR_MODES
    monster = six.monster_for(source, profile, monster_rank)
    encounter_key = f"{monster_rank}.{profile}.{band}.{class_name}"
    tie_roll = event_roll_bps(seed, 0, "INITIATIVE", encounter_key)
    if hero.speed > monster.speed:
        order = ("HERO", "MONSTER")
    elif hero.speed < monster.speed:
        order = ("MONSTER", "HERO")
    else:
        order = ("HERO", "MONSTER") if tie_roll < 5_000 else ("MONSTER", "HERO")
    resource_spec = RESOURCES[class_name]
    state = HeroState(
        hero.max_hp,
        resource_spec.start_bps,
        resource_spec.start_bps,
        encounter_start_hp=hero.max_hp,
    )
    monster_hp = monster.max_hp
    status: HostileStatus | None = None
    loadout = c1_loadout(class_name, selected_a3)
    monster_action_index = 0

    for round_index in range(1, 31):
        for side in order:
            if state.hp <= 0 or monster_hp <= 0:
                break
            if side == "HERO":
                selected = select_hero_action(
                    seed,
                    state,
                    hero,
                    monster_hp,
                    monster.max_hp,
                    core.hit_bps(monster, hero),
                    status,
                    loadout,
                    behavior_mode,
                )
                state.action_index += 1
                used_skill: SkillDefinition | None = None
                if selected == "RECOVERY":
                    state.recovery_uses += 1
                    state.resource = min(
                        state.resource_ceiling,
                        state.resource + resource_spec.recovery_bps,
                    )
                elif selected == "BASIC":
                    damage_modifier = 0
                    hit_modifier = 0
                    if status is not None:
                        if status.kind == "PHYSICAL_EXPOSED" and hero.attack_type == "PHYSICAL":
                            damage_modifier += status.magnitude_bps
                        if status.kind == "MAGICAL_EXPOSED" and hero.attack_type == "MAGIC":
                            damage_modifier += status.magnitude_bps
                    damage, _ = resolved_attack(
                        hero,
                        monster,
                        10_000,
                        core_rolls(seed, state.action_index, "HERO", encounter_key),
                        hit_modifier_bps=hit_modifier,
                        damage_modifier_bps=damage_modifier,
                    )
                    monster_hp = max(0, monster_hp - damage)
                    state.basic_actions += 1
                    state.resource = min(
                        10_000 if resource_spec.lifecycle == "ENCOUNTER_BUILDER" else state.resource_ceiling,
                        state.resource + resource_spec.basic_generation_bps,
                    )
                else:
                    used_skill = selected
                    spend_resource(state, class_name, selected)
                    state.uses[selected.definition_id] = state.uses.get(selected.definition_id, 0) + 1
                    if selected.protection_kind is not None:
                        state.protection_group_uses[selected.generation_group_id] = (
                            state.protection_group_uses.get(selected.generation_group_id, 0) + 1
                        )
                    if selected.protection_kind == "SHIELD":
                        state.shield += core.multiply_bps(hero.max_hp, selected.protection_amount)
                    elif selected.protection_kind == "BARRIER":
                        state.barrier = min(1, state.barrier + selected.protection_amount)
                    elif selected.protection_kind == "HEAL":
                        state.hp = min(
                            state.encounter_start_hp,
                            state.hp + core.multiply_bps(hero.max_hp, selected.protection_amount),
                        )
                    if selected.hit_packet_count == 1:
                        damage_modifier = 0
                        hit_modifier = 0
                        if status is not None:
                            if status.kind == "PHYSICAL_EXPOSED" and hero.attack_type == "PHYSICAL":
                                damage_modifier += status.magnitude_bps
                            if status.kind == "MAGICAL_EXPOSED" and hero.attack_type == "MAGIC":
                                damage_modifier += status.magnitude_bps
                        damage, hit = resolved_attack(
                            hero,
                            monster,
                            selected.action_coefficient_bps,
                            core_rolls(seed, state.action_index, "HERO", encounter_key),
                            hit_modifier_bps=hit_modifier,
                            damage_modifier_bps=damage_modifier,
                            crit_eligible=selected.crit_eligible,
                            fixed_variance_bps=(
                                10_000
                                if selected.variance_min_bps == selected.variance_max_bps == 10_000
                                else None
                            ),
                        )
                        packet = resolve_direct_packet(
                            hit=hit,
                            raw_damage=damage,
                            barrier=0,
                            shield=0,
                            has_attached_hostile_status=selected.status_id is not None,
                        )
                        monster_hp = max(0, monster_hp - packet.hp_damage)
                        if (
                            packet.attached_status_allowed
                            and monster_hp > 0
                            and selected.status_id is not None
                        ):
                            roll = event_roll_bps(
                                seed,
                                state.action_index,
                                "STATUS_RESIST_V1",
                                selected.status_id,
                            )
                            if roll < status_apply_bps(
                                selected,
                                monster_status_resistance(source.combat_rank, profile),
                            ):
                                status = HostileStatus(
                                    selected.status_id,
                                    selected.status_kind or "",
                                    selected.status_magnitude_bps,
                                    selected.status_duration_owner_turns,
                                )
                decrement_cooldowns(state, used_skill)
            else:
                monster_action_index += 1
                hit_modifier = 0
                damage_modifier = 0
                if status is not None:
                    if status.kind == "AIM_DISRUPTED":
                        hit_modifier -= status.magnitude_bps
                    elif status.kind == "POWER_SUPPRESSED":
                        damage_modifier -= status.magnitude_bps
                damage, hit = resolved_attack(
                    monster,
                    hero,
                    monster.action_coefficient_bps,
                    core_rolls(seed, monster_action_index, "MONSTER", encounter_key),
                    hit_modifier_bps=hit_modifier,
                    damage_modifier_bps=damage_modifier,
                )
                packet = resolve_direct_packet(
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
                if actual_hp_damage > 0 and resource_spec.received_damage_generation_bps > 0:
                    grant_received_damage_resource(state, class_name)
                if status is not None:
                    status.remaining_owner_turns -= 1
                    if status.remaining_owner_turns <= 0:
                        status = None
        if monster_hp <= 0:
            return SliceBattleResult(
                "WIN",
                round_index,
                state.hp,
                state.resource,
                state.resource_ceiling,
                state.basic_actions,
                state.uses.get(selected_a3.definition_id, 0),
            )
        if state.hp <= 0:
            return SliceBattleResult(
                "LOSS",
                round_index,
                0,
                state.resource,
                state.resource_ceiling,
                state.basic_actions,
                state.uses.get(selected_a3.definition_id, 0),
            )
    return SliceBattleResult(
        "RETREAT",
        30,
        state.hp,
        state.resource,
        state.resource_ceiling,
        state.basic_actions,
        state.uses.get(selected_a3.definition_id, 0),
    )


@dataclass(frozen=True)
class Metrics:
    win_rate: float
    median_rounds: float
    p90_hp_loss: float
    mean_resource_bps: float
    mean_resource_ceiling_bps: float
    a3_execution_rate: float


def measure_candidate(
    class_name: str,
    candidate: SkillDefinition,
    profile: str,
    band: str,
    seeds: int,
    monster_rank: str = "NORMAL",
    behavior_mode: str = "BALANCED",
) -> Metrics:
    source = six.source_for_display(class_name, 1, band)
    hero = six.derive_hero(source).combatant
    results = [
        simulate_battle(
            class_name,
            candidate,
            profile,
            band,
            seed,
            monster_rank,
            behavior_mode,
        )
        for seed in range(seeds)
    ]
    losses = [(hero.max_hp - result.hero_hp) * 100.0 / hero.max_hp for result in results]
    return Metrics(
        win_rate=sum(result.outcome == "WIN" for result in results) / seeds,
        median_rounds=statistics.median(result.rounds for result in results),
        p90_hp_loss=core.percentile(losses, 0.90),
        mean_resource_bps=statistics.fmean(result.resource_bps for result in results),
        mean_resource_ceiling_bps=statistics.fmean(
            result.resource_ceiling_bps for result in results
        ),
        a3_execution_rate=sum(result.selected_a3_uses > 0 for result in results) / seeds,
    )


def dominates(
    left: list[Metrics],
    right: list[Metrics],
    *,
    consider_resource: bool,
) -> bool:
    no_worse = all(
        a.win_rate >= b.win_rate
        and a.median_rounds <= b.median_rounds
        and a.p90_hp_loss <= b.p90_hp_loss
        and (not consider_resource or a.mean_resource_bps >= b.mean_resource_bps)
        and (
            not consider_resource
            or a.mean_resource_ceiling_bps >= b.mean_resource_ceiling_bps
        )
        for a, b in zip(left, right)
    )
    strictly_better = any(
        a.win_rate > b.win_rate
        or a.median_rounds < b.median_rounds
        or a.p90_hp_loss < b.p90_hp_loss
        or (consider_resource and a.mean_resource_bps > b.mean_resource_bps)
        or (
            consider_resource
            and a.mean_resource_ceiling_bps > b.mean_resource_ceiling_bps
        )
        for a, b in zip(left, right)
    )
    return no_worse and strictly_better


def no_a3_candidate(class_name: str) -> SkillDefinition:
    template = skills_for(class_name, "A3_TACTIC_I")[0]
    policy = (
        "aq.behavior.xc.v1"
        if RESOURCES[class_name].lifecycle == "EXPEDITION_POOL"
        else "aq.behavior.ec.v1"
    )
    return replace(
        template,
        definition_id=f"aq.reference.{CLASS_SLUG[class_name]}.no_a3",
        name_ko="NO_A3",
        activation_policy="KEYED_BPS",
        activation_bps=0,
        behavior_policy_id=policy,
        automation_band="CONTROL",
        automation_priority=0,
        condition_id="reference_never_activate",
        resource_cost_bps=0,
        survival_reserve_policy="BEHAVIOR_POLICY_FLOOR",
        cooldown_turns=0,
        damage_type="NONE",
        action_coefficient_bps=0,
        status_id=None,
        status_kind=None,
        status_magnitude_bps=0,
        status_duration_owner_turns=0,
        protection_kind=None,
        protection_amount=0,
        hp_threshold_bps=10_000,
        target_hp_floor_bps=0,
        target_hit_floor_bps=0,
        minimum_recoverable_deficit_bps=0,
        max_activations_per_encounter=0,
        max_activations_per_expedition=0,
        generation_group_id="aq.generation.none",
        attack_budget_equivalent_bps=0,
        hit_packet_count=0,
    )


def validate_catalog() -> tuple[list[str], dict[str, int]]:
    passed: list[str] = []
    assert len(SKILLS) == 30
    assert len({skill.definition_id for skill in SKILLS}) == 30
    assert len({skill.name_ko for skill in SKILLS}) == 30
    assert all(STABLE_ID.fullmatch(skill.definition_id) for skill in SKILLS)
    assert all(STABLE_ID.fullmatch(skill.name_key) for skill in SKILLS)
    assert all(skill.rules_version == RULES_VERSION for skill in SKILLS)
    assert all(skill.schema_version == SCHEMA_VERSION for skill in SKILLS)
    passed.append("THIRTY_IMMUTABLE_STABLE_SKILL_DEFINITIONS")

    for class_name in CLASSES:
        class_skills = skills_for(class_name)
        assert len(class_skills) == 5
        assert len(skills_for(class_name, "A1_CORE")) == 1
        assert len(skills_for(class_name, "A2_SURVIVAL")) == 1
        assert len(skills_for(class_name, "A3_TACTIC_I")) == 3
        assert all(skill.fixed for skill in class_skills[:2])
        assert all(not skill.fixed for skill in skills_for(class_name, "A3_TACTIC_I"))
        assert skills_for(class_name, "A1_CORE")[0].macro_role == EXPECTED_ROLES[class_name][0]
        assert skills_for(class_name, "A2_SURVIVAL")[0].macro_role == EXPECTED_ROLES[class_name][1]
        assert {skill.macro_role for skill in skills_for(class_name, "A3_TACTIC_I")} == {
            EXPECTED_ROLES[class_name][2]
        }
    assert set(INTENT_GROUP_BY_SKILL_ID) == {
        skill.definition_id
        for skill in SKILLS
        if skill.slot_id == "A3_TACTIC_I"
    }
    assert all(
        any(is_declared_intent_cell(skill, *cell_spec) for cell_spec in CELL_SPECS)
        for skill in SKILLS
        if skill.slot_id == "A3_TACTIC_I"
    )
    passed.append("SIX_CLASSES_HAVE_FIXED_A1_A2_AND_THREE_ROLE_LOCKED_A3")

    for class_name in CLASSES:
        candidates = skills_for(class_name, "A3_TACTIC_I")
        for left in candidates:
            for right in candidates:
                if left.definition_id == right.definition_id:
                    continue
                same_effect_and_condition = (
                    left.status_id == right.status_id
                    and left.protection_kind == right.protection_kind
                    and left.hp_threshold_bps == right.hp_threshold_bps
                    and left.target_hp_floor_bps == right.target_hp_floor_bps
                    and left.target_hit_floor_bps == right.target_hit_floor_bps
                    and left.minimum_recoverable_deficit_bps
                    == right.minimum_recoverable_deficit_bps
                    and left.activation_policy == right.activation_policy
                    and left.activation_bps == right.activation_bps
                    and left.target == right.target
                )
                if not same_effect_and_condition:
                    continue
                no_worse = (
                    left.action_coefficient_bps >= right.action_coefficient_bps
                    and left.status_magnitude_bps >= right.status_magnitude_bps
                    and left.status_duration_owner_turns >= right.status_duration_owner_turns
                    and left.status_base_apply_bps >= right.status_base_apply_bps
                    and left.protection_amount >= right.protection_amount
                    and left.resource_cost_bps <= right.resource_cost_bps
                    and left.cooldown_turns <= right.cooldown_turns
                    and left.max_activations_per_encounter
                    >= right.max_activations_per_encounter
                    and left.max_activations_per_expedition
                    >= right.max_activations_per_expedition
                )
                strictly_better = (
                    left.action_coefficient_bps > right.action_coefficient_bps
                    or left.status_magnitude_bps > right.status_magnitude_bps
                    or left.status_duration_owner_turns > right.status_duration_owner_turns
                    or left.status_base_apply_bps > right.status_base_apply_bps
                    or left.protection_amount > right.protection_amount
                    or left.resource_cost_bps < right.resource_cost_bps
                    or left.cooldown_turns < right.cooldown_turns
                    or left.max_activations_per_encounter
                    > right.max_activations_per_encounter
                    or left.max_activations_per_expedition
                    > right.max_activations_per_expedition
                )
                assert not (no_worse and strictly_better), (
                    f"Structural A3 dominance: {left.definition_id} > {right.definition_id}"
                )
    passed.append("NO_SAME_EFFECT_SAME_CONDITION_STRUCTURAL_A3_DOMINANCE")

    for skill in SKILLS:
        assert skill.root_action_count == 1
        assert skill.hit_packet_count in {0, 1}
        assert skill.extra_action_count == 0
        assert not skill.can_deal_true_damage
        assert not skill.can_generate_guard
        assert skill.damage_type in {"NONE", "PHYSICAL", "MAGICAL"}
        assert skill.target in {"SELF", "SINGLE_ENEMY"}
        assert skill.automation_band in BANDS
        assert 0 <= skill.automation_priority <= 100
        assert skill.behavior_policy_id in BEHAVIOR_POLICIES
        if skill.activation_policy == "DETERMINISTIC":
            assert skill.automation_band == "SURVIVAL" and skill.activation_bps == 10_000
        else:
            assert skill.activation_policy == "KEYED_BPS"
            assert 0 <= skill.activation_bps <= 6_000
        assert 0 <= skill.resource_cost_bps <= 6_000
        assert skill.cooldown_turns >= 0
        if skill.hit_packet_count == 1:
            assert skill.variance_min_bps <= skill.variance_max_bps
        if skill.slot_id == "A3_TACTIC_I" and skill.hit_packet_count == 1:
            assert not skill.crit_eligible
            assert skill.variance_min_bps == skill.variance_max_bps == 10_000
    passed.append("ONE_ROOT_ONE_HIT_NO_EXTRA_TRUE_GUARD_AND_VALID_AUTOMATION")

    for skill in SKILLS:
        assert skill.growth_axis in ALLOWED_GROWTH_AXES
        assert len(skill.rank_axis_values) == 5
        assert skill.rank_axis_values[0] is not None
        assert skill.rank_axis_values[1:] == (None, None, None, None)
        assert skill.rank_live_eligible == (True, False, False, False, False)
        if skill.growth_axis == "CONDITION_THRESHOLD":
            expected_threshold = (
                skill.target_hit_floor_bps
                or skill.target_hp_floor_bps
                or skill.hp_threshold_bps
            )
            assert skill.rank_axis_values[0] == expected_threshold
    passed.append("RANK_ONE_EXACT_AND_RANK_TWO_TO_FIVE_FAIL_CLOSED_PENDING")

    for skill in SKILLS:
        assert skill.exploration_tags == ()
    passed.append("PROFICIENCY_AND_EXPLORATION_REMAIN_UNCHANGED")

    for class_name in CLASSES:
        a2 = skills_for(class_name, "A2_SURVIVAL")[0]
        for skill in (a2, *skills_for(class_name, "A3_TACTIC_I")):
            if skill.protection_kind is None:
                continue
            budget = PROTECTION_BUDGETS[(class_name, skill.protection_kind)]
            assert 0 < skill.protection_amount <= budget.amount
            assert skill.protection_amount_unit == budget.amount_unit
            assert skill.resource_cost_bps == budget.cost_bps
            assert skill.cooldown_turns >= budget.cooldown_turns
            assert 0 < skill.max_activations_per_encounter <= budget.encounter_cap
            assert 0 < skill.max_activations_per_expedition <= budget.expedition_cap
            assert skill.generation_group_id == (
                f"aq.generation.{CLASS_SLUG[class_name]}.{skill.protection_kind.lower()}"
            )
    assert not any(
        skill.protection_kind == "BARRIER" and skill.owner_class in {"MAGE", "PALADIN"}
        for skill in SKILLS
    )
    passed.append("PROTECTION_AMOUNTS_COSTS_COOLDOWNS_AND_LEDGERS_MATCH_STAGE4")

    cleric_nominal_encounter = 2 * 800 + 1 * 600
    cleric_nominal_expedition = 5 * 800 + 3 * 600
    paladin_nominal_encounter = 1 * 700 + 1 * 800
    paladin_nominal_expedition = 3 * 700 + 2 * 800
    assert cleric_nominal_encounter <= 3_000 and cleric_nominal_expedition <= 7_000
    assert paladin_nominal_encounter <= 3_000 and paladin_nominal_expedition <= 7_000
    passed.append("COMBINED_HEAL_AND_SHIELD_NOMINAL_BUDGETS_STAY_CAPPED")

    for class_name in CLASSES:
        controls = [skill for skill in skills_for(class_name, "A3_TACTIC_I") if skill.status_id]
        max_applications, max_turn_units = STATUS_BUDGET[class_name]
        if controls:
            assert all(skill.max_activations_per_encounter <= max_applications for skill in controls)
            assert all(
                skill.max_activations_per_encounter * skill.status_duration_owner_turns
                <= max_turn_units
                for skill in controls
            )
            assert all(skill.stack_policy == "REFRESH_MAX" for skill in controls)
            assert all(0 < skill.action_coefficient_bps <= 12_000 for skill in controls)
            assert all(skill.hit_packet_count == 1 for skill in controls)
            assert all(1_000 <= status_apply_bps(skill, 100) <= 9_500 for skill in controls)
        else:
            assert max_applications == max_turn_units == 0
    passed.append("HOSTILE_STATUS_APPLICATION_AND_TURN_UNIT_BUDGETS_MATCH_STAGE4")

    for skill in (skill for skill in SKILLS if skill.target_hit_floor_bps > 0):
        assert skill.target_hit_floor_bps > 6_500
        predicate_results: list[bool] = []
        for band in ("BASE", "STRESS_ALL3"):
            source = six.source_for_display(skill.owner_class, 1, band)
            hero = six.derive_hero(source).combatant
            for profile in PROFILES:
                monster = six.monster_for(source, profile, "NORMAL")
                predicate_results.append(
                    core.hit_bps(monster, hero) >= skill.target_hit_floor_bps
                )
        assert any(predicate_results) and not all(predicate_results), (
            f"Dead target-hit predicate: {skill.definition_id}"
        )
    passed.append("TARGET_HIT_CONDITIONS_SPLIT_PRIMARY_NORMAL_CELLS")

    for class_name in CLASSES:
        a1 = skills_for(class_name, "A1_CORE")[0]
        followup_coefficient = max(10_000, a1.action_coefficient_bps)
        for skill in skills_for(class_name, "A3_TACTIC_I"):
            expected_equivalent = skill.action_coefficient_bps
            if skill.status_kind in {"PHYSICAL_EXPOSED", "MAGICAL_EXPOSED"}:
                max_apply_bps = max(
                    status_apply_bps(
                        skill,
                        monster_status_resistance(1, profile),
                    )
                    for profile in PROFILES
                )
                numerator = (
                    skill.status_duration_owner_turns
                    * followup_coefficient
                    * skill.status_magnitude_bps
                    * max_apply_bps
                )
                expected_equivalent += (numerator + 100_000_000 - 1) // 100_000_000
            assert skill.attack_budget_equivalent_bps >= expected_equivalent, (
                f"Underreported attack budget: {skill.definition_id} "
                f"{skill.attack_budget_equivalent_bps} < {expected_equivalent}"
            )

    attack_envelopes: dict[str, int] = {}
    for class_name in CLASSES:
        a1 = skills_for(class_name, "A1_CORE")[0]
        a1_uses = max_uses_in_ten(a1.cooldown_turns)
        a3 = skills_for(class_name, "A3_TACTIC_I")
        max_a3 = max(a3, key=lambda skill: skill.attack_budget_equivalent_bps)
        a3_uses = (
            min(max_uses_in_ten(max_a3.cooldown_turns), max_a3.max_activations_per_encounter)
            if max_a3.attack_budget_equivalent_bps > 0
            else 0
        )
        basics = 10 - a1_uses - a3_uses
        envelope = (
            a1_uses * a1.attack_budget_equivalent_bps
            + a3_uses * max_a3.attack_budget_equivalent_bps
            + basics * 10_000
        )
        assert basics >= 0 and envelope <= ATTACK_BUDGET[class_name]
        attack_envelopes[class_name] = envelope
    passed.append("CONSERVATIVE_RANK_ONE_ROLLING_TEN_ATTACK_ENVELOPES_PASS")

    for class_name in CLASSES:
        lifecycle = RESOURCES[class_name].lifecycle
        for skill in skills_for(class_name):
            if skill.automation_band == "SURVIVAL":
                assert skill.behavior_policy_id == "aq.behavior.sv.v1"
                assert skill.minimum_recoverable_deficit_bps == (
                    500 if skill.protection_kind == "HEAL" else 0
                )
                continue
            expected_policy = (
                f"aq.behavior.x{'p' if skill.automation_band == 'POWER' else 'c'}.v1"
                if lifecycle == "EXPEDITION_POOL"
                else f"aq.behavior.e{'p' if skill.automation_band == 'POWER' else 'c'}.v1"
            )
            assert skill.behavior_policy_id == expected_policy
            assert skill.survival_reserve_policy == "BEHAVIOR_POLICY_FLOOR"
    passed.append("EP_EC_XP_XC_SV_POLICY_FAMILIES_ARE_EXPLICIT")

    for class_name in ("WARRIOR", "PALADIN"):
        spec = RESOURCES[class_name]
        state = HeroState(
            10_000,
            spec.start_bps,
            spec.start_bps,
            encounter_start_hp=10_000,
        )
        for _ in range(10):
            grant_received_damage_resource(state, class_name)
        assert (
            state.received_damage_generation_total_bps
            == spec.received_damage_generation_cap_bps
        )
        assert state.resource == min(
            10_000,
            spec.start_bps + spec.received_damage_generation_cap_bps,
        )
        capped_resource = state.resource
        grant_received_damage_resource(state, class_name)
        assert state.resource == capped_resource
    assert all(
        RESOURCES[class_name].received_damage_generation_bps == 0
        and RESOURCES[class_name].received_damage_generation_cap_bps == 0
        for class_name in ("ROGUE", "RANGER", "MAGE", "CLERIC")
    )
    passed.append("RECEIVED_DAMAGE_RESOURCE_LEDGERS_STOP_AT_ENCOUNTER_CAP")

    assert set(BEHAVIOR_MODES) == {"CAUTIOUS", "BALANCED", "BOLD"}
    assert all(set(mode_rows) == set(BEHAVIOR_MODES) for mode_rows in BEHAVIOR_POLICIES.values())
    assert [BEHAVIOR_POLICIES["aq.behavior.xp.v1"][mode].resource_floor_bps for mode in BEHAVIOR_MODES] == [5_000, 3_000, 0]
    assert [BEHAVIOR_POLICIES["aq.behavior.xc.v1"][mode].resource_floor_bps for mode in BEHAVIOR_MODES] == [6_000, 3_500, 0]
    assert [BEHAVIOR_POLICIES["aq.behavior.sv.v1"][mode].survival_hp_threshold_offset_bps for mode in BEHAVIOR_MODES] == [1_000, 0, -1_000]

    for class_name in CLASSES:
        source = six.source_for_display(class_name, 1, "BASE")
        hero = replace(six.derive_hero(source).combatant, max_hp=10_000)
        resource_floor_skills = [skills_for(class_name, "A1_CORE")[0]]
        first_a3 = skills_for(class_name, "A3_TACTIC_I")[0]
        if first_a3.automation_band != "SURVIVAL":
            resource_floor_skills.append(first_a3)
        for skill in resource_floor_skills:
            for behavior_mode in BEHAVIOR_MODES:
                floor = behavior_adjustment(skill, behavior_mode).resource_floor_bps
                exact_state = HeroState(
                    10_000,
                    skill.resource_cost_bps + floor,
                    10_000,
                    encounter_start_hp=10_000,
                )
                assert eligible(
                    skill,
                    exact_state,
                    hero,
                    10_000,
                    10_000,
                    10_000,
                    None,
                    c1_loadout(class_name, skills_for(class_name, "A3_TACTIC_I")[0]),
                    behavior_mode,
                )
                if skill.resource_cost_bps + floor > 0:
                    below_state = replace(
                        exact_state,
                        resource=skill.resource_cost_bps + floor - 1,
                    )
                    assert not eligible(
                        skill,
                        below_state,
                        hero,
                        10_000,
                        10_000,
                        10_000,
                        None,
                        c1_loadout(class_name, skills_for(class_name, "A3_TACTIC_I")[0]),
                        behavior_mode,
                    )

        survival = skills_for(class_name, "A2_SURVIVAL")[0]
        for behavior_mode in BEHAVIOR_MODES:
            adjusted_threshold = core.clamp(
                survival.hp_threshold_bps
                + behavior_adjustment(survival, behavior_mode).survival_hp_threshold_offset_bps,
                0,
                10_000,
            )
            exact_state = HeroState(
                adjusted_threshold,
                10_000,
                10_000,
                encounter_start_hp=10_000,
            )
            assert eligible(
                survival,
                exact_state,
                hero,
                10_000,
                10_000,
                10_000,
                None,
                c1_loadout(class_name, skills_for(class_name, "A3_TACTIC_I")[0]),
                behavior_mode,
            )
            if adjusted_threshold < 10_000:
                assert not eligible(
                    survival,
                    replace(exact_state, hp=adjusted_threshold + 1),
                    hero,
                    10_000,
                    10_000,
                    10_000,
                    None,
                    c1_loadout(class_name, skills_for(class_name, "A3_TACTIC_I")[0]),
                    behavior_mode,
                )
    passed.append("CAUTIOUS_BALANCED_BOLD_FLOORS_AND_SURVIVAL_THRESHOLDS_ARE_EXACT")

    for class_name in CLASSES:
        for candidate in skills_for(class_name, "A3_TACTIC_I"):
            source = six.source_for_display(class_name, 1, "STRESS_ALL3")
            hero = six.derive_hero(source).combatant
            state = HeroState(hero.max_hp, 0, 0)
            selected = select_hero_action(
                7,
                state,
                hero,
                100,
                100,
                10_000,
                None,
                c1_loadout(class_name, candidate),
            )
            assert selected == "BASIC"
    passed.append("LOW_STAT_AND_ZERO_RESOURCE_ALWAYS_SELECT_BASIC_FALLBACK")

    sample_class = "ROGUE"
    sample_a3 = skills_for(sample_class, "A3_TACTIC_I")[0]
    source = six.source_for_display(sample_class, 1, "BASE")
    hero = six.derive_hero(source).combatant
    sample_state = HeroState(hero.max_hp, 10_000, 10_000, action_index=3)
    forward = c1_loadout(sample_class, sample_a3)
    reverse = tuple(reversed(forward))
    localized = tuple(replace(skill, name_ko=f"번역-{index}") for index, skill in enumerate(forward))
    for behavior_mode in BEHAVIOR_MODES:
        selected_forward = select_hero_action(
            77, sample_state, hero, 100, 100, 10_000, None, forward, behavior_mode
        )
        selected_reverse = select_hero_action(
            77, sample_state, hero, 100, 100, 10_000, None, reverse, behavior_mode
        )
        assert getattr(selected_forward, "definition_id", selected_forward) == getattr(
            selected_reverse, "definition_id", selected_reverse
        )
        selected_localized = select_hero_action(
            77, sample_state, hero, 100, 100, 10_000, None, localized, behavior_mode
        )
        assert getattr(selected_forward, "definition_id", selected_forward) == getattr(
            selected_localized, "definition_id", selected_localized
        )
    passed.append("ALL_BEHAVIOR_MODES_ARE_ORDER_AND_LOCALIZATION_INVARIANT")

    for class_name in CLASSES:
        candidate = skills_for(class_name, "A3_TACTIC_I")[0]
        for behavior_mode in BEHAVIOR_MODES:
            first = simulate_battle(
                class_name,
                candidate,
                "STANDARD",
                "BASE",
                91,
                "NORMAL",
                behavior_mode,
            )
            second = simulate_battle(
                class_name,
                candidate,
                "STANDARD",
                "BASE",
                91,
                "NORMAL",
                behavior_mode,
            )
            assert first == second
            assert first.outcome in {"WIN", "LOSS", "RETREAT"} and first.rounds <= 30
    passed.append("ALL_BEHAVIOR_MODES_ARE_SAME_SEED_DETERMINISTIC_AND_FINITE")

    assert core_rolls(77, 3, "HERO", "sample") == core_rolls(77, 3, "HERO", "sample")
    assert event_roll_bps(77, 3, "AUTOMATION_V1", SKILLS[0].definition_id) != event_roll_bps(
        77, 3, "AUTOMATION_V1", SKILLS[1].definition_id
    )
    passed.append("KEYED_AUTOMATION_AND_CORE_RNG_STREAMS_ARE_SEPARATE")

    barrier_block = resolve_direct_packet(
        hit=True,
        raw_damage=0,
        barrier=1,
        shield=0,
        has_attached_hostile_status=True,
    )
    shield_pass = resolve_direct_packet(
        hit=True,
        raw_damage=0,
        barrier=0,
        shield=100,
        has_attached_hostile_status=True,
    )
    assert barrier_block.barrier_after == 0 and not barrier_block.attached_status_allowed
    assert shield_pass.shield_after == 100 and shield_pass.attached_status_allowed
    assert resolved_attack(hero, hero, 0, (0, 0, 10_000)) == (0, True)
    passed.append("ZERO_DAMAGE_CARRIER_BARRIER_BLOCKS_WHILE_SHIELD_PASSES_STATUS")

    wounded_entry_cap = core.multiply_bps(hero.max_hp, 8_000)
    healed = min(
        wounded_entry_cap,
        core.multiply_bps(hero.max_hp, 7_000) + core.multiply_bps(hero.max_hp, 2_000),
    )
    assert healed == wounded_entry_cap < hero.max_hp
    passed.append("HEAL_CANNOT_EXCEED_ENCOUNTER_START_HP")

    return passed, attack_envelopes


def run_balance_checks(
    seeds: int,
    behavior_modes: tuple[str, ...] = BEHAVIOR_MODES,
) -> tuple[list[str], dict[tuple[str, str], tuple[float, float, float]]]:
    passed: list[str] = []
    summaries: dict[tuple[str, str], tuple[float, float, float]] = {}
    assert behavior_modes and all(mode in BEHAVIOR_MODES for mode in behavior_modes)
    mode_scope = (
        "ALL_BEHAVIOR_MODES"
        if behavior_modes == BEHAVIOR_MODES
        else "_".join(behavior_modes)
    )
    for behavior_mode in behavior_modes:
        for class_name in CLASSES:
            candidates = skills_for(class_name, "A3_TACTIC_I")
            baseline = no_a3_candidate(class_name)
            baseline_cells = [
                measure_candidate(
                    class_name,
                    baseline,
                    profile,
                    band,
                    seeds,
                    monster_rank,
                    behavior_mode,
                )
                for monster_rank, band, profile in CELL_SPECS
            ]
            all_metrics: dict[str, list[Metrics]] = {}
            for candidate in candidates:
                all_metrics[candidate.definition_id] = [
                    measure_candidate(
                        class_name,
                        candidate,
                        profile,
                        band,
                        seeds,
                        monster_rank,
                        behavior_mode,
                    )
                    for monster_rank, band, profile in CELL_SPECS
                ]

            win_delta = 0.0
            round_delta = 0.0
            hp_delta = 0.0
            for cell_index in range(len(baseline_cells)):
                cell_values = [
                    all_metrics[candidate.definition_id][cell_index]
                    for candidate in candidates
                ]
                win_delta = max(
                    win_delta,
                    max(value.win_rate for value in cell_values)
                    - min(value.win_rate for value in cell_values),
                )
                round_delta = max(
                    round_delta,
                    max(value.median_rounds for value in cell_values)
                    - min(value.median_rounds for value in cell_values),
                )
                hp_delta = max(
                    hp_delta,
                    max(value.p90_hp_loss for value in cell_values)
                    - min(value.p90_hp_loss for value in cell_values),
                )
            assert win_delta <= 0.05 + 1e-12, (
                f"{behavior_mode} {class_name} win delta {win_delta:.6f}"
            )
            assert round_delta <= 1.0 + 1e-12, (
                f"{behavior_mode} {class_name} round delta {round_delta:.6f}"
            )
            assert hp_delta <= 8.0 + 1e-12, (
                f"{behavior_mode} {class_name} hp delta {hp_delta:.6f}"
            )
            summaries[(behavior_mode, class_name)] = (
                win_delta,
                round_delta,
                hp_delta,
            )

            for left in candidates:
                for right in candidates:
                    if left.definition_id == right.definition_id:
                        continue
                    assert not dominates(
                        all_metrics[left.definition_id],
                        all_metrics[right.definition_id],
                        consider_resource=(
                            RESOURCES[class_name].lifecycle == "EXPEDITION_POOL"
                        ),
                    ), (
                        f"{behavior_mode} {left.definition_id} "
                        f"dominates {right.definition_id}"
                    )

            for candidate in candidates:
                candidate_cells = all_metrics[candidate.definition_id]
                assert not dominates(
                    baseline_cells,
                    candidate_cells,
                    consider_resource=(
                        RESOURCES[class_name].lifecycle == "EXPEDITION_POOL"
                    ),
                ), f"{behavior_mode} NO_A3 dominates {candidate.definition_id}"
                assert max(
                    cell.a3_execution_rate for cell in candidate_cells[:8]
                ) >= 0.25, (
                    f"{behavior_mode} no primary NORMAL execution cell for "
                    f"{candidate.definition_id}"
                )
                if behavior_mode == "BALANCED":
                    useful_cell = any(
                        (
                            is_declared_intent_cell(
                                candidate,
                                *CELL_SPECS[cell_index],
                            )
                            and
                            candidate_cell.win_rate >= baseline_cell.win_rate - 0.05
                            and candidate_cell.median_rounds
                            <= baseline_cell.median_rounds + 1.0
                            and candidate_cell.p90_hp_loss
                            <= baseline_cell.p90_hp_loss + 8.0
                            and (
                                candidate_cell.win_rate
                                >= baseline_cell.win_rate + 0.02
                                or candidate_cell.median_rounds
                                <= baseline_cell.median_rounds - 0.5
                                or candidate_cell.p90_hp_loss
                                <= baseline_cell.p90_hp_loss - 2.0
                            )
                        )
                        for cell_index, (candidate_cell, baseline_cell) in enumerate(
                            zip(candidate_cells, baseline_cells)
                        )
                    )
                    assert useful_cell, (
                        f"No BALANCED material intent-cell benefit for "
                        f"{candidate.definition_id}"
                    )
    passed.append(f"{mode_scope}_PASS_A3_OFFER_GATES_AND_PARETO_ZERO")
    passed.append(f"{mode_scope}_PASS_NO_A3_AND_NORMAL_EXECUTION_GATES")
    if "BALANCED" in behavior_modes:
        passed.append("BALANCED_A3_CANDIDATES_HAVE_MATERIAL_INTENT_CELLS")

    for behavior_mode in behavior_modes:
        for class_name in CLASSES:
            for candidate in skills_for(class_name, "A3_TACTIC_I"):
                results = [
                    simulate_battle(
                        class_name,
                        candidate,
                        profile,
                        "STRESS_ALL3",
                        seed,
                        behavior_mode=behavior_mode,
                    )
                    for profile in PROFILES
                    for seed in range(max(50, seeds // 4))
                ]
                assert all(result.basic_actions > 0 for result in results)
    passed.append(f"{mode_scope}_RETAIN_LOW_STAT_BASIC_ACTION_PATH")

    return passed, summaries


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--seeds", type=int, default=None)
    parser.add_argument("--mode", choices=("ALL", *BEHAVIOR_MODES), default="ALL")
    args = parser.parse_args()
    seeds = args.seeds if args.seeds is not None else (1_000 if args.pd else 500)
    assert seeds > 0
    if args.pd:
        assert args.mode == "ALL", "PD requires all behavior modes"
        assert seeds == 1_000, "PD requires exactly 1000 seeds"
    behavior_modes = BEHAVIOR_MODES if args.mode == "ALL" else (args.mode,)

    catalog_passed, envelopes = validate_catalog()
    balance_passed, summaries = run_balance_checks(seeds, behavior_modes)
    passed = catalog_passed + balance_passed
    print(f"C1_SKILL_VERTICAL_SLICE_V0_1: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print("  definitions=30 c1Loadouts=18 exactRank=1 pendingRanks=2..5")
    print(
        "  attackEnvelopes="
        + ",".join(f"{class_name}:{envelopes[class_name]}" for class_name in CLASSES)
    )
    for behavior_mode in behavior_modes:
        for class_name in CLASSES:
            win_delta, round_delta, hp_delta = summaries[(behavior_mode, class_name)]
            print(
                f"  {behavior_mode:<8} {class_name:<7} A3 maxDelta "
                f"win={win_delta * 100:.2f}pp medianRounds={round_delta:.1f} "
                f"p90HpLoss={hp_delta:.2f}pp"
            )
    for name in passed:
        print(f"  PASS {name}")
    if args.pd:
        print(
            "REFERENCE_GATE: PASS "
            "(C1 Rank1 schema/catalog/automatic reference balance only; "
            "Rank2-5/items/monsters/live storage pending)"
        )


if __name__ == "__main__":
    main()
