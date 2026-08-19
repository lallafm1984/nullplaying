#!/usr/bin/env python3
"""Build and audit the formal AlarmQuest 242-skill registry v0.3.

Registry v0.2 remains immutable. This module promotes the BLEED and SHOCK
primers approved by status lifecycle v0.6, projects all 51 player status
sources into explicit structural fields, and records the 23 formerly implicit
status contracts in a deterministic repair ledger. It is planning/test-only:
it does not read or mutate live characters, Room, assets, or equipment.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import skill_registry_240_v0_2_review as v02
import skill_status_lifecycle_v0_6_review as lifecycle


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_REGISTRY_242_v0.3.md"
KOTLIN_CONTRACT = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextSkillRegistry242.kt"
KOTLIN_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextSkillRegistry242Test.kt"

RULES_VERSION = "aq.skill-registry.v0.3"
ANCHORS = v02.ANCHORS
CLASSES = v02.CLASSES
ROLES = v02.ROLES
PASSIVE_RESOURCE_DISCOUNT_CAP_BPS = v02.PASSIVE_RESOURCE_DISCOUNT_CAP_BPS
PASSIVE_ACTION_ADD_CAP_BPS = v02.PASSIVE_ACTION_ADD_CAP_BPS
INCOMING_REDUCTION_CAP_BPS = v02.INCOMING_REDUCTION_CAP_BPS
FINAL_HIT_ADD_CAP_BPS = v02.FINAL_HIT_ADD_CAP_BPS
TEMP_EVASION_CAP_BPS = v02.TEMP_EVASION_CAP_BPS
STATUS_APPLY_FINAL_CAP_BPS = v02.STATUS_APPLY_FINAL_CAP_BPS
ACTIVE_COST_FLOOR_BPS = v02.ACTIVE_COST_FLOOR_BPS
DERIVED_ACTIVE_COST_CAP_BPS = v02.DERIVED_ACTIVE_COST_CAP_BPS
MAX_RESOURCE_COST_BPS = v02.MAX_RESOURCE_COST_BPS

PRIMER_IDS = frozenset({
    "aq.skill.common.v06.bleedingcut",
    "aq.skill.common.v06.staticflask",
})

# Exact v0.6 rows whose semantic status contract existed while Registry v0.2
# still had an empty appliesTags field.
STATUS_STRUCTURE_REPAIR_IDS = (
    "aq.skill.boss.w7.giantstomp",
    "aq.skill.cleric.w6.silentrelic",
    "aq.skill.common.w7.pushback",
    "aq.skill.common.w8.coolingbottle",
    "aq.skill.common.w8.silencethrow",
    "aq.skill.common.w8.throwingnet",
    "aq.skill.common.w8.weaknesswatch",
    "aq.skill.monster.w7.mushroomspore",
    "aq.skill.monster.w7.slimeshot",
    "aq.skill.monster.w7.spiderweb",
    "aq.skill.monster.w8.wraithgrasp",
    "aq.skill.paladin.w3.shieldbash",
    "aq.skill.paladin.w6.judgmentcharge",
    "aq.skill.warrior.w5.earthsplitter",
    "aq.skill.world.w7.blackwell",
    "aq.skill.world.w8.deadletter",
    "aq.skill.world.w8.frozentorch",
    "aq.skill.world.w8.lostbell",
    "aq.skill.world.w8.poisonmist",
    "aq.skill.external.w5.phoenixash",
    "aq.skill.secret.w7.abyssaleye",
    "aq.skill.paladin.w6.cursedswordseal",
    "aq.skill.external.w2.dragonscale",
)

REPAIRED_STATUS_FIELDS = (
    "appliesTags",
    "subtype",
    "durationRoots",
    "clock",
    "barrierPolicy",
)

BEHAVIOR_POLICY_IDS = frozenset({
    "aq.behavior.cautious.v1",
    "aq.behavior.balanced.v1",
    "aq.behavior.bold.v1",
})


@dataclass(frozen=True)
class StatusApplicationDefinition:
    tag: str
    owner: str
    apply_values_bps: tuple[int, int, int, int, int]
    mode: str
    duration_roots: int
    clock: str
    stack_policy: str
    subtype: str
    periodic_values_bps: tuple[int, int, int, int, int]
    periodic_ticks: int
    periodic_mode: str
    magnitude_values_bps: tuple[int, int, int, int, int]
    cost_locked: bool
    linked_group: str


@dataclass(frozen=True)
class StatusSourceDefinition:
    source_kind: str
    possible_tags: tuple[str, ...]
    hit_required: bool
    barrier_policy: str
    candidate_rule_id: str
    apply_modifier_rule: str
    applications: tuple[StatusApplicationDefinition, ...]
    fixed_rule: str


@dataclass(frozen=True)
class ActiveSkillDefinition:
    definition_id: str
    idea_id: str
    name_ko: str
    owner_scope: str
    source: str
    design_wave: int
    roles: tuple[str, ...]
    pattern: str
    growth_field: str
    anchor_values: tuple[int, int, int, int, int]
    attack_equivalent_values: tuple[int, int, int, int, int]
    resource_cost_bps: int
    cost_policy: str
    cooldown_roots: int
    cooldown_policy: str
    action_width: int
    hit_packets: int
    final_hit_policy: str
    crit_eligible: bool
    candidate_rule_id: str
    applies_tags: tuple[str, ...]
    consumes_tags: tuple[str, ...]
    duration_roots: int
    fixed_tradeoff: str
    status_source: StatusSourceDefinition | None
    minimum_shield_requirement_bps: tuple[int, ...]
    suppress_revive_for_current_death_window: bool
    suppress_revive_consumes_charge: bool
    suppress_revive_carries_over: bool
    equipment_granted_skill_ids: tuple[str, ...]
    provisional: bool
    legacy_adapter_id: str


@dataclass(frozen=True)
class PassiveSkillDefinition:
    definition_id: str
    idea_id: str
    name_ko: str
    owner_scope: str
    source: str
    design_wave: int
    roles: tuple[str, ...]
    pattern: str
    growth_field: str
    anchor_values: tuple[int, int, int, int, int]
    condition_id: str
    host_scope: str
    stack_group: str
    stack_policy: str
    stack_cap_bps: int
    fixed_tradeoff: str
    equipment_granted_skill_ids: tuple[str, ...]
    provisional: bool
    legacy_adapter_id: str


@dataclass(frozen=True)
class StatusStructureRepairEntry:
    skill_id: str
    prior_applies_tags_count: int
    post_applies_tags_count: int
    repaired_fields: tuple[str, ...]
    structured_tags: tuple[str, ...]
    application_count: int
    source_hash: str


@dataclass(frozen=True)
class SkillLevelEntry:
    skill_id: str
    skill_level: int


@dataclass(frozen=True)
class SkillLoadoutSnapshot:
    snapshot_version: int
    rules_version: str
    content_hash: str
    hero_class: str
    active_slots: tuple[SkillLevelEntry, ...]
    passive_slots: tuple[SkillLevelEntry, ...]
    behavior_policy_id: str


def _tuple5(values) -> tuple[int, int, int, int, int]:
    result = tuple(int(value) for value in values)
    if len(result) != 5:
        raise AssertionError(result)
    return result


def _application(value) -> StatusApplicationDefinition:
    return StatusApplicationDefinition(
        tag=value.tag,
        owner=value.owner,
        apply_values_bps=_tuple5(value.apply_values),
        mode=value.mode,
        duration_roots=int(value.duration),
        clock=value.clock,
        stack_policy=value.stack_policy,
        subtype=value.subtype,
        periodic_values_bps=_tuple5(value.periodic_values),
        periodic_ticks=int(value.periodic_ticks),
        periodic_mode=value.periodic_mode,
        magnitude_values_bps=_tuple5(value.magnitude_values),
        cost_locked=bool(value.cost_locked),
        linked_group=value.linked_group,
    )


def _possible_tags(source) -> tuple[str, ...]:
    tags = [application.tag for application in source.applications]
    if source.apply_modifier_rule == "POISON_ELSE_AIM":
        tags.append("AIM_DISRUPTED")
    return tuple(sorted(set(tags)))


def _status_source(source) -> StatusSourceDefinition:
    return StatusSourceDefinition(
        # v0.6 provisional primers are formal COMMON skills in v0.3.
        source_kind="PLAYER",
        possible_tags=_possible_tags(source),
        hit_required=bool(source.hit_required),
        barrier_policy=source.barrier_policy,
        candidate_rule_id=source.candidate_rule,
        apply_modifier_rule=source.apply_modifier_rule,
        applications=tuple(_application(value) for value in source.applications),
        fixed_rule=source.fixed_rule,
    )


STATUS_SOURCES = tuple(sorted(
    ((source.skill_id, _status_source(source)) for source in lifecycle.SOURCES),
    key=lambda item: item[0],
))
STATUS_SOURCE_BY_ID = dict(STATUS_SOURCES)


def _promote_active(item: v02.ActiveSkillDefinition) -> ActiveSkillDefinition:
    status_source = STATUS_SOURCE_BY_ID.get(item.definition_id)
    structured_tags = status_source.possible_tags if status_source else ()
    applies_tags = tuple(dict.fromkeys((*item.applies_tags, *structured_tags)))
    duration_roots = item.duration_roots
    if status_source and duration_roots == 0:
        duration_roots = max(application.duration_roots for application in status_source.applications)

    growth_field = item.growth_field
    minimum_shield_requirement_bps: tuple[int, ...] = ()
    if item.definition_id == "aq.skill.common.w8.protectionconversion":
        growth_field = "minimum_shield_requirement_reduction"
        minimum_shield_requirement_bps = tuple(
            max(500, 900 - value // 20) for value in item.anchor_values
        )

    suppress_revive = item.definition_id == "aq.skill.world.w8.secondfuneral"
    return ActiveSkillDefinition(
        definition_id=item.definition_id,
        idea_id=item.idea_id,
        name_ko=item.name_ko,
        owner_scope=item.owner_scope,
        source=item.source,
        design_wave=item.design_wave,
        roles=item.roles,
        pattern=item.pattern,
        growth_field=growth_field,
        anchor_values=item.anchor_values,
        attack_equivalent_values=item.attack_equivalent_values,
        resource_cost_bps=item.resource_cost_bps,
        cost_policy=item.cost_policy,
        cooldown_roots=item.cooldown_roots,
        cooldown_policy=item.cooldown_policy,
        action_width=item.action_width,
        hit_packets=item.hit_packets,
        final_hit_policy=item.final_hit_policy,
        crit_eligible=item.crit_eligible,
        candidate_rule_id=item.candidate_rule_id,
        applies_tags=applies_tags,
        consumes_tags=item.consumes_tags,
        duration_roots=duration_roots,
        fixed_tradeoff=item.fixed_tradeoff,
        status_source=status_source,
        minimum_shield_requirement_bps=minimum_shield_requirement_bps,
        suppress_revive_for_current_death_window=suppress_revive,
        suppress_revive_consumes_charge=False,
        suppress_revive_carries_over=False,
        equipment_granted_skill_ids=(),
        provisional=False,
        legacy_adapter_id="",
    )


def _primer(
    *,
    definition_id: str,
    idea_id: str,
    name_ko: str,
    pattern: str,
    growth_field: str,
    anchor_values: tuple[int, int, int, int, int],
    attack_equivalent_values: tuple[int, int, int, int, int],
    resource_cost_bps: int,
    candidate_rule_id: str,
    fixed_tradeoff: str,
) -> ActiveSkillDefinition:
    status_source = STATUS_SOURCE_BY_ID[definition_id]
    return ActiveSkillDefinition(
        definition_id=definition_id,
        idea_id=idea_id,
        name_ko=name_ko,
        owner_scope="ALL",
        source="COMMON",
        design_wave=9,
        roles=("ATTACK", "STATUS"),
        pattern=pattern,
        growth_field=growth_field,
        anchor_values=anchor_values,
        attack_equivalent_values=attack_equivalent_values,
        resource_cost_bps=resource_cost_bps,
        cost_policy="EXPLICIT",
        cooldown_roots=3,
        cooldown_policy="EXPLICIT",
        action_width=1,
        hit_packets=1,
        final_hit_policy="NORMAL",
        crit_eligible=False,
        candidate_rule_id=candidate_rule_id,
        applies_tags=status_source.possible_tags,
        consumes_tags=(),
        duration_roots=max(value.duration_roots for value in status_source.applications),
        fixed_tradeoff=fixed_tradeoff,
        status_source=status_source,
        minimum_shield_requirement_bps=(),
        suppress_revive_for_current_death_window=False,
        suppress_revive_consumes_charge=False,
        suppress_revive_carries_over=False,
        equipment_granted_skill_ids=(),
        provisional=False,
        legacy_adapter_id="",
    )


PRIMERS = (
    _primer(
        definition_id="aq.skill.common.v06.bleedingcut",
        idea_id="LIF001",
        name_ko="출혈 베기",
        pattern="BLEED_PRIMER_DOT",
        growth_field="periodic_total_coefficient_bps",
        anchor_values=(3_000, 3_500, 4_000, 4_500, 5_000),
        attack_equivalent_values=(8_000, 8_500, 9_000, 9_500, 10_000),
        resource_cost_bps=1_500,
        candidate_rule_id="target_accepts_bleed_and_existing_payload_not_stronger",
        fixed_tradeoff="carrier 5,000·BLEED 적용 8,500·3tick·정화/면역 시 carrier만·치명 불가",
    ),
    _primer(
        definition_id="aq.skill.common.v06.staticflask",
        idea_id="LIF002",
        name_ko="정전기 병",
        pattern="SHOCK_STACK_PRIMER",
        growth_field="shock_apply_bps",
        anchor_values=(4_500, 5_375, 6_250, 7_125, 8_000),
        attack_equivalent_values=(4_000, 4_000, 4_000, 4_000, 4_000),
        resource_cost_bps=1_200,
        candidate_rule_id="target_accepts_shock_and_stack_or_duration_can_improve",
        fixed_tradeoff="carrier 4,000·SHOCK 최대2stack·세 번째 성공은 기간만 갱신·치명 불가",
    ),
)


def _promote_passive(item: v02.PassiveSkillDefinition) -> PassiveSkillDefinition:
    return PassiveSkillDefinition(
        definition_id=item.definition_id,
        idea_id=item.idea_id,
        name_ko=item.name_ko,
        owner_scope=item.owner_scope,
        source=item.source,
        design_wave=item.design_wave,
        roles=item.roles,
        pattern=item.pattern,
        growth_field=item.growth_field,
        anchor_values=item.anchor_values,
        condition_id=item.condition_id,
        host_scope=item.host_scope,
        stack_group=item.stack_group,
        stack_policy=item.stack_policy,
        stack_cap_bps=item.stack_cap_bps,
        fixed_tradeoff=item.fixed_tradeoff,
        equipment_granted_skill_ids=(),
        provisional=False,
        legacy_adapter_id="",
    )


ACTIVES = tuple(sorted(
    (*(_promote_active(item) for item in v02.ACTIVES), *PRIMERS),
    key=lambda item: item.definition_id,
))
PASSIVES = tuple(sorted((_promote_passive(item) for item in v02.PASSIVES), key=lambda item: item.definition_id))
ACTIVE_BY_ID = {item.definition_id: item for item in ACTIVES}
PASSIVE_BY_ID = {item.definition_id: item for item in PASSIVES}


def _camel_status_application(value: StatusApplicationDefinition) -> dict:
    return {
        "tag": value.tag,
        "owner": value.owner,
        "applyValuesBps": list(value.apply_values_bps),
        "mode": value.mode,
        "durationRoots": value.duration_roots,
        "clock": value.clock,
        "stackPolicy": value.stack_policy,
        "subtype": value.subtype,
        "periodicValuesBps": list(value.periodic_values_bps),
        "periodicTicks": value.periodic_ticks,
        "periodicMode": value.periodic_mode,
        "magnitudeValuesBps": list(value.magnitude_values_bps),
        "costLocked": value.cost_locked,
        "linkedGroup": value.linked_group,
    }


def _camel_status_source(value: StatusSourceDefinition | None) -> dict | None:
    if value is None:
        return None
    return {
        "sourceKind": value.source_kind,
        "possibleTags": list(value.possible_tags),
        "hitRequired": value.hit_required,
        "barrierPolicy": value.barrier_policy,
        "candidateRuleId": value.candidate_rule_id,
        "applyModifierRule": value.apply_modifier_rule,
        "applications": [_camel_status_application(item) for item in value.applications],
        "fixedRule": value.fixed_rule,
    }


def _camel_active(value: ActiveSkillDefinition) -> dict:
    return {
        "definitionId": value.definition_id,
        "ideaId": value.idea_id,
        "nameKo": value.name_ko,
        "ownerScope": value.owner_scope,
        "source": value.source,
        "designWave": value.design_wave,
        "roles": list(value.roles),
        "pattern": value.pattern,
        "growthField": value.growth_field,
        "anchorValues": list(value.anchor_values),
        "attackEquivalentValues": list(value.attack_equivalent_values),
        "resourceCostBps": value.resource_cost_bps,
        "costPolicy": value.cost_policy,
        "cooldownRoots": value.cooldown_roots,
        "cooldownPolicy": value.cooldown_policy,
        "actionWidth": value.action_width,
        "hitPackets": value.hit_packets,
        "finalHitPolicy": value.final_hit_policy,
        "critEligible": value.crit_eligible,
        "candidateRuleId": value.candidate_rule_id,
        "appliesTags": list(value.applies_tags),
        "consumesTags": list(value.consumes_tags),
        "durationRoots": value.duration_roots,
        "fixedTradeoff": value.fixed_tradeoff,
        "statusSource": _camel_status_source(value.status_source),
        "minimumShieldRequirementBps": list(value.minimum_shield_requirement_bps),
        "suppressReviveForCurrentDeathWindow": value.suppress_revive_for_current_death_window,
        "suppressReviveConsumesCharge": value.suppress_revive_consumes_charge,
        "suppressReviveCarriesOver": value.suppress_revive_carries_over,
        "equipmentGrantedSkillIds": list(value.equipment_granted_skill_ids),
        "provisional": value.provisional,
        "legacyAdapterId": value.legacy_adapter_id,
    }


def _camel_passive(value: PassiveSkillDefinition) -> dict:
    return {
        "definitionId": value.definition_id,
        "ideaId": value.idea_id,
        "nameKo": value.name_ko,
        "ownerScope": value.owner_scope,
        "source": value.source,
        "designWave": value.design_wave,
        "roles": list(value.roles),
        "pattern": value.pattern,
        "growthField": value.growth_field,
        "anchorValues": list(value.anchor_values),
        "conditionId": value.condition_id,
        "hostScope": value.host_scope,
        "stackGroup": value.stack_group,
        "stackPolicy": value.stack_policy,
        "stackCapBps": value.stack_cap_bps,
        "fixedTradeoff": value.fixed_tradeoff,
        "equipmentGrantedSkillIds": list(value.equipment_granted_skill_ids),
        "provisional": value.provisional,
        "legacyAdapterId": value.legacy_adapter_id,
    }


def _canonical_json(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def status_structure_hash() -> str:
    payload = [
        {"skillId": skill_id, "statusSource": _camel_status_source(source)}
        for skill_id, source in STATUS_SOURCES
    ]
    return _sha256(_canonical_json(payload))


def _repair_entry(skill_id: str) -> StatusStructureRepairEntry:
    old = v02.ACTIVE_BY_ID[skill_id]
    current = ACTIVE_BY_ID[skill_id]
    source = current.status_source
    if source is None:
        raise AssertionError(skill_id)
    return StatusStructureRepairEntry(
        skill_id=skill_id,
        prior_applies_tags_count=len(old.applies_tags),
        post_applies_tags_count=len(current.applies_tags),
        repaired_fields=REPAIRED_STATUS_FIELDS,
        structured_tags=source.possible_tags,
        application_count=len(source.applications),
        source_hash=_sha256(_canonical_json(_camel_status_source(source))),
    )


STATUS_STRUCTURE_REPAIR_LEDGER = tuple(_repair_entry(skill_id) for skill_id in STATUS_STRUCTURE_REPAIR_IDS)


def _camel_repair(value: StatusStructureRepairEntry) -> dict:
    return {
        "skillId": value.skill_id,
        "priorAppliesTagsCount": value.prior_applies_tags_count,
        "postAppliesTagsCount": value.post_applies_tags_count,
        "repairedFields": list(value.repaired_fields),
        "structuredTags": list(value.structured_tags),
        "applicationCount": value.application_count,
        "sourceHash": value.source_hash,
    }


def canonical_payload() -> dict:
    all_skills = (*ACTIVES, *PASSIVES)
    return {
        "rulesVersion": RULES_VERSION,
        "sourceRegistry240Hash": v02.canonical_hash(),
        "sourceStatusLifecycleV06Hash": lifecycle.canonical_hash(),
        "statusStructureHash": status_structure_hash(),
        "anchors": list(ANCHORS),
        "roles": list(ROLES),
        "actives": [_camel_active(item) for item in ACTIVES],
        "passives": [_camel_passive(item) for item in PASSIVES],
        "statusRepairLedger": [_camel_repair(item) for item in STATUS_STRUCTURE_REPAIR_LEDGER],
        "caps": {
            "passiveResourceDiscountBps": PASSIVE_RESOURCE_DISCOUNT_CAP_BPS,
            "passiveActionAddBps": PASSIVE_ACTION_ADD_CAP_BPS,
            "incomingReductionBps": INCOMING_REDUCTION_CAP_BPS,
            "finalHitAddBps": FINAL_HIT_ADD_CAP_BPS,
            "temporaryEvasionBps": TEMP_EVASION_CAP_BPS,
            "statusApplyFinalBps": STATUS_APPLY_FINAL_CAP_BPS,
            "activeCostFloorBps": ACTIVE_COST_FLOOR_BPS,
            "derivedActiveCostCapBps": DERIVED_ACTIVE_COST_CAP_BPS,
            "maxResourceCostBps": MAX_RESOURCE_COST_BPS,
            "statusApplicationsPerAction": lifecycle.NEW_STATUS_PER_ACTION_CAP,
            "shockStacks": lifecycle.SHOCK_STACK_CAP,
            "curseSubtypes": lifecycle.CURSE_SUBTYPE_CAP,
        },
        "slots": {"active": 5, "passive": 3},
        "skillLevelCap": 100,
        "skillXpShareBps": 1_000,
        "skillXpCap": 20_000_000,
        "statusSourceCount": len(STATUS_SOURCES),
        "statusStructureRepairCount": len(STATUS_STRUCTURE_REPAIR_LEDGER),
        "equipmentGrantedSkillFieldCount": sum(len(item.equipment_granted_skill_ids) for item in all_skills),
        "provisionalDefinitionCount": sum(item.provisional for item in all_skills),
        "legacyAdapterCount": sum(bool(item.legacy_adapter_id) for item in all_skills),
        "manualCombatActions": False,
    }


def canonical_hash() -> str:
    return _sha256(_canonical_json(canonical_payload()))


def round_half_up(value: Decimal) -> int:
    return int(value.quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def value_at(values: tuple[int, ...], level: int) -> int:
    safe = max(1, min(100, int(level)))
    for index, right in enumerate(ANCHORS):
        if safe == right:
            return values[index]
        if safe < right:
            left = ANCHORS[index - 1]
            ratio = Decimal(safe - left) / Decimal(right - left)
            return round_half_up(
                Decimal(values[index - 1])
                + Decimal(values[index] - values[index - 1]) * ratio
            )
    return values[-1]


def resolved_resource_cost(skill: ActiveSkillDefinition, level: int) -> int:
    if skill.growth_field == "resource_cost_bps":
        return value_at(skill.anchor_values, level)
    return skill.resource_cost_bps


def final_resource_cost(skill: ActiveSkillDefinition, level: int, discount_bps: int) -> int:
    base = resolved_resource_cost(skill, level)
    if base <= 0:
        return 0
    bounded_discount = min(PASSIVE_RESOURCE_DISCOUNT_CAP_BPS, max(0, int(discount_bps)))
    discounted = round_half_up(Decimal(base) * Decimal(10_000 - bounded_discount) / Decimal(10_000))
    return max(ACTIVE_COST_FLOOR_BPS, discounted)


def accessible(hero_class: str, kind: str):
    catalog = ACTIVES if kind == "ACTIVE" else PASSIVES
    return tuple(item for item in catalog if item.owner_scope in {hero_class, "ALL"})


def commit_loadout(
    hero_class: str,
    active_levels: dict[str, int],
    passive_levels: dict[str, int],
    behavior_policy_id: str = "aq.behavior.balanced.v1",
) -> SkillLoadoutSnapshot:
    if hero_class not in CLASSES:
        raise ValueError(hero_class)
    if len(active_levels) > 5 or len(passive_levels) > 3:
        raise ValueError("Active 0..5 / Passive 0..3 slot cap")
    if set(active_levels).intersection(passive_levels):
        raise ValueError("same skill cannot occupy both kinds")
    if behavior_policy_id not in BEHAVIOR_POLICY_IDS:
        raise ValueError("unknown behavior policy")
    active_access = {item.definition_id for item in accessible(hero_class, "ACTIVE")}
    passive_access = {item.definition_id for item in accessible(hero_class, "PASSIVE")}
    if not set(active_levels).issubset(active_access) or not set(passive_levels).issubset(passive_access):
        raise ValueError("class cannot access selected skill")
    if not all(1 <= level <= 100 for level in (*active_levels.values(), *passive_levels.values())):
        raise ValueError("skill level out of range")
    return SkillLoadoutSnapshot(
        snapshot_version=2,
        rules_version=RULES_VERSION,
        content_hash=canonical_hash(),
        hero_class=hero_class,
        active_slots=tuple(SkillLevelEntry(skill_id, level) for skill_id, level in sorted(active_levels.items())),
        passive_slots=tuple(SkillLevelEntry(skill_id, level) for skill_id, level in sorted(passive_levels.items())),
        behavior_policy_id=behavior_policy_id,
    )


def _direction_ok(values: tuple[int, ...]) -> bool:
    return list(values) == sorted(values) or list(values) == sorted(values, reverse=True)


def checks() -> list[tuple[str, bool, str]]:
    all_skills = ACTIVES + PASSIVES
    protection = ACTIVE_BY_ID["aq.skill.common.w8.protectionconversion"]
    funeral = ACTIVE_BY_ID["aq.skill.world.w8.secondfuneral"]
    bleeding = ACTIVE_BY_ID["aq.skill.common.v06.bleedingcut"]
    shock = ACTIVE_BY_ID["aq.skill.common.v06.staticflask"]
    access = {
        hero_class: (len(accessible(hero_class, "ACTIVE")), len(accessible(hero_class, "PASSIVE")))
        for hero_class in CLASSES
    }
    loadouts = [
        commit_loadout(
            hero_class,
            {item.definition_id: 100 for item in accessible(hero_class, "ACTIVE")[:5]},
            {item.definition_id: 100 for item in accessible(hero_class, "PASSIVE")[:3]},
        )
        for hero_class in CLASSES
    ]
    empty = commit_loadout(CLASSES[0], {}, {})
    structured = [item for item in ACTIVES if item.status_source is not None]
    suppressors = [item.definition_id for item in ACTIVES if item.suppress_revive_for_current_death_window]
    payload = canonical_payload()
    return [
        ("242 catalog exact", len(ACTIVES) == 152 and len(PASSIVES) == 90 and len(all_skills) == 242, f"active={len(ACTIVES)} passive={len(PASSIVES)}"),
        ("all IDs ideas and names unique", len({item.definition_id for item in all_skills}) == len({item.idea_id for item in all_skills}) == len({item.name_ko for item in all_skills}) == 242, "expected=242"),
        ("registry 240 preserved and primers promoted", {item.definition_id for item in v02.ACTIVES}.issubset(ACTIVE_BY_ID) and {item.definition_id for item in v02.PASSIVES} == set(PASSIVE_BY_ID) and set(ACTIVE_BY_ID) - {item.definition_id for item in v02.ACTIVES} == PRIMER_IDS, ",".join(sorted(PRIMER_IDS))),
        ("bleed primer exact", bleeding.anchor_values == (3_000, 3_500, 4_000, 4_500, 5_000) and bleeding.attack_equivalent_values == (8_000, 8_500, 9_000, 9_500, 10_000) and bleeding.resource_cost_bps == 1_500 and bleeding.cooldown_roots == 3 and bleeding.applies_tags == ("BLEED",), str(bleeding.anchor_values)),
        ("shock primer exact", shock.anchor_values == (4_500, 5_375, 6_250, 7_125, 8_000) and shock.attack_equivalent_values == (4_000,) * 5 and shock.resource_cost_bps == 1_200 and shock.cooldown_roots == 3 and shock.applies_tags == ("SHOCK",), str(shock.anchor_values)),
        ("all 51 status sources are structural", len(STATUS_SOURCES) == len(structured) == 51 and all(source.source_kind == "PLAYER" and source.barrier_policy and source.candidate_rule_id and source.applications and all(application.tag and application.owner in {"SELF", "TARGET"} and len(application.apply_values_bps) == 5 and application.duration_roots >= 1 and application.clock and application.stack_policy for application in source.applications) for _, source in STATUS_SOURCES), f"statusSources={len(STATUS_SOURCES)}"),
        ("23 missing status contracts repaired with ledger", len(STATUS_STRUCTURE_REPAIR_LEDGER) == 23 and {item.skill_id for item in STATUS_STRUCTURE_REPAIR_LEDGER} == set(STATUS_STRUCTURE_REPAIR_IDS) and all(item.prior_applies_tags_count == 0 and item.post_applies_tags_count > 0 and item.repaired_fields == REPAIRED_STATUS_FIELDS and item.application_count > 0 and len(item.source_hash) == 64 for item in STATUS_STRUCTURE_REPAIR_LEDGER), f"ledger={len(STATUS_STRUCTURE_REPAIR_LEDGER)}"),
        ("protection conversion growth is executable", protection.growth_field == "minimum_shield_requirement_reduction" and protection.anchor_values == (4_000, 5_000, 6_000, 7_000, 8_000) and protection.minimum_shield_requirement_bps == (700, 650, 600, 550, 500), str(protection.minimum_shield_requirement_bps)),
        ("second funeral current death window suppression is structural", suppressors == ["aq.skill.world.w8.secondfuneral"] and funeral.suppress_revive_for_current_death_window and not funeral.suppress_revive_consumes_charge and not funeral.suppress_revive_carries_over, str(suppressors)),
        ("item skill provisional and legacy adapter fields are zero", payload["equipmentGrantedSkillFieldCount"] == 0 and payload["provisionalDefinitionCount"] == 0 and payload["legacyAdapterCount"] == 0 and all(not item.equipment_granted_skill_ids and not item.provisional and not item.legacy_adapter_id for item in all_skills), "equipment=0 provisional=0 legacy=0"),
        ("active and passive operational fields complete", all(item.resource_cost_bps in range(0, MAX_RESOURCE_COST_BPS + 1) and item.cooldown_roots >= 1 and item.action_width >= 1 and item.candidate_rule_id and item.fixed_tradeoff and len(item.anchor_values) == 5 and _direction_ok(item.anchor_values) for item in ACTIVES) and all(item.condition_id and item.stack_group and item.stack_policy and item.stack_cap_bps > 0 and item.fixed_tradeoff and len(item.anchor_values) == 5 and _direction_ok(item.anchor_values) for item in PASSIVES), "all definitions complete"),
        ("per class access 77 plus 45", all(value == (77, 45) for value in access.values()), str(access)),
        ("zero to five plus zero to three snapshots content bound", not empty.active_slots and not empty.passive_slots and empty.content_hash == canonical_hash() and all(len(snapshot.active_slots) == 5 and len(snapshot.passive_slots) == 3 and snapshot.content_hash == canonical_hash() for snapshot in loadouts), f"snapshots={len(loadouts) + 1}"),
        ("cost floor after max passive discount", all(final_resource_cost(item, level, PASSIVE_RESOURCE_DISCOUNT_CAP_BPS) == 0 or final_resource_cost(item, level, PASSIVE_RESOURCE_DISCOUNT_CAP_BPS) >= ACTIVE_COST_FLOOR_BPS for item in ACTIVES for level in ANCHORS), "positive final cost floor=1000"),
        ("source and structure hashes are bound", payload["sourceRegistry240Hash"] == v02.canonical_hash() and payload["sourceStatusLifecycleV06Hash"] == lifecycle.canonical_hash() and payload["statusStructureHash"] == status_structure_hash(), status_structure_hash()),
    ]


def _kotlin_bound_hash() -> str:
    if not KOTLIN_CONTRACT.exists():
        return ""
    match = re.search(r'V_NEXT_SKILL_242_CONTENT_HASH\s*=\s*"([0-9a-f]{64})"', KOTLIN_CONTRACT.read_text(encoding="utf-8"))
    return match.group(1) if match else ""


def pd_checks() -> list[tuple[str, bool, str]]:
    result = checks()
    document = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    kotlin = KOTLIN_CONTRACT.read_text(encoding="utf-8") if KOTLIN_CONTRACT.exists() else ""
    kotlin_test = KOTLIN_TEST.read_text(encoding="utf-8") if KOTLIN_TEST.exists() else ""
    current_hash = canonical_hash()
    result.extend((
        ("document binds registry 242 hash", current_hash in document, current_hash),
        ("kotlin contract binds registry 242 hash", _kotlin_bound_hash() == current_hash, _kotlin_bound_hash()),
        ("kotlin models every critical actual field", all(token in kotlin for token in ("statusRepairLedger", "statusSource", "minimumShieldRequirementBps", "suppressReviveForCurrentDeathWindow", "equipmentGrantedSkillIds", "provisionalDefinitionCount", "legacyAdapterCount")), "critical fields"),
        ("kotlin test compares typed actual field hash", "typedPayloadContentHash" in kotlin_test and "pythonCanonicalHash" in kotlin_test and "assertEquals(pythonCanonicalHash, typedPayloadContentHash)" in kotlin_test, "typed Python/Kotlin comparison"),
    ))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--contract-json", action="store_true")
    parser.add_argument("--hash-only", action="store_true")
    args = parser.parse_args()
    if args.contract_json:
        print(_canonical_json(canonical_payload()))
        return 0
    if args.json:
        print(json.dumps(canonical_payload(), ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    if args.hash_only:
        print(canonical_hash())
        return 0
    result = pd_checks() if args.pd else checks()
    print(f"{'PD' if args.pd else 'BALANCE'}: {sum(ok for _, ok, _ in result)}/{len(result)} PASS")
    for name, ok, detail in result:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"canonical_sha256={canonical_hash()}")
    return 0 if all(ok for _, ok, _ in result) else 1


if __name__ == "__main__":
    raise SystemExit(main())
