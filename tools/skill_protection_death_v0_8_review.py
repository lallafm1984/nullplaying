#!/usr/bin/env python3
"""AlarmQuest v0.8 protection, recovery and death-pipeline audit.

Planning/test only.  It makes lethal ordering, aggregate sustain budgets and
replay behavior executable without touching production or live data.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from dataclasses import asdict, dataclass, replace
from functools import lru_cache
from pathlib import Path

import monster_prefix_variants_v0_2_review as monsters
import skill_cost_transfer_dispel_v0_7_review as cost_v07
import skill_effect_resolver_v0_3_review as resolver
import skill_prepared_reaction_v0_4_review as prepared
import skill_registry_240_v0_2_review as skills


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_PROTECTION_DEATH_PIPELINE_v0.8.md"
KOTLIN_CONTRACT = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextProtectionDeathPipeline.kt"
RULES_VERSION = "aq.skill-protection-death.v0.8"
DISPLAY_LEVELS = resolver.DISPLAY_LEVELS
SKILL_LEVELS = resolver.SKILL_LEVELS
SCENARIO_MODES = ("DIRECT", "DOT", "DELAYED", "COST", "BARRIER", "REPLAY")

HEAL_SHIELD_ENCOUNTER_CAP_BPS = 3_000
HEAL_SHIELD_EXPEDITION_CAP_BPS = 7_000
BARRIER_ENCOUNTER_CAP = 1
BARRIER_EXPEDITION_CAP = 2
LAST_STAND_EXPEDITION_CAP = 1
COMMON_SURVIVAL_ENCOUNTER_CAP = 1
COMMON_SURVIVAL_EXPEDITION_CAP = 2
FIELD_TREATMENT_EXPEDITION_CAP = 3
MAX_NON_DAMAGE_ROOTS_PER_TEN = 6
EXECUTION_BASE_THRESHOLD_BPS = 2_000
EXECUTION_THRESHOLD_CAP_BPS = 2_500
PHOENIX_MAX_HP_SEAL_BPS = 2_500
INCOMING_PASSIVE_FLOOR_BPS = -700


@dataclass(frozen=True)
class Contract:
    skill_id: str
    name_ko: str
    kind: str
    family: str
    anchors: tuple[int, int, int, int, int]
    fixed_rule: str


def active(skill_id: str, family: str) -> Contract:
    item = skills.ACTIVE_BY_ID[skill_id]
    return Contract(skill_id, item.name_ko, "ACTIVE", family, tuple(item.anchor_values), item.fixed_tradeoff)


def passive(skill_id: str, family: str) -> Contract:
    item = skills.PASSIVE_BY_ID[skill_id]
    return Contract(skill_id, item.name_ko, "PASSIVE", family, tuple(item.anchor_values), item.fixed_tradeoff)


CONTRACTS = (
    active("aq.skill.common.w4.certainfinish", "EXECUTION"),
    active("aq.skill.rogue.w4.silentexecution", "EXECUTION"),
    active("aq.skill.boss.w7.executionbell", "EXECUTION"),
    active("aq.skill.world.w8.secondfuneral", "REVIVE_SUPPRESS_EXECUTION"),
    passive("aq.skill.common.w4.finisherobsession", "EXECUTION_THRESHOLD"),
    active("aq.skill.external.w5.phoenixash", "LAST_STAND"),
    passive("aq.skill.warrior.w5.indomitable", "LETHAL_MITIGATION"),
    active("aq.skill.cleric.w3.soulanchor", "LETHAL_DELAY"),
    active("aq.skill.cleric.w5.martyrlight", "SELF_COST_HEAL_ATTACK"),
    active("aq.skill.cleric.w5.miraclecost", "DOUBLE_TOKEN_HEAL"),
    active("aq.skill.paladin.w5.coverally", "SHIELD_INTERCEPT"),
    active("aq.skill.common.w5.defensestance", "COMMON_SHIELD"),
    active("aq.skill.common.w5.firstaid", "COMMON_HEAL"),
    active("aq.skill.common.w5.focusedbreathing", "RESOURCE_RECOVERY"),
    passive("aq.skill.ranger.w5.fieldtreatment", "POST_ENCOUNTER_HEAL"),
    passive("aq.skill.paladin.w5.mercylimit", "OVERHEAL_TO_RESOURCE"),
    passive("aq.skill.common.w5.supplysaving", "ATTRITION_REDUCTION"),
    passive("aq.skill.rogue.w5.shallowwound", "INCOMING_REDUCTION"),
    passive("aq.skill.cleric.w5.abstinencevow", "INCOMING_REDUCTION"),
    passive("aq.skill.external.w5.guardletter", "INCOMING_REDUCTION"),
    # Existing class_protection_generation providers share the same aggregate ledger.
    active("aq.skill.warrior.a2.ironstance", "BASE_CLASS_SHIELD"),
    active("aq.skill.rogue.a2.narrowescape", "BASE_CLASS_BARRIER"),
    active("aq.skill.ranger.a2.coverstance", "BASE_CLASS_SHIELD"),
    active("aq.skill.mage.a2.arcaneveil", "BASE_CLASS_SHIELD"),
    active("aq.skill.cleric.a2.restoringprayer", "BASE_CLASS_HEAL"),
    active("aq.skill.cleric.a3.earlyward", "BASE_CLASS_SHIELD"),
    active("aq.skill.cleric.a3.focusedward", "BASE_CLASS_SHIELD"),
    active("aq.skill.cleric.a3.lastward", "BASE_CLASS_SHIELD"),
    active("aq.skill.paladin.a2.guardianoath", "BASE_CLASS_SHIELD"),
    active("aq.skill.paladin.a3.earlymercy", "BASE_CLASS_HEAL"),
    active("aq.skill.paladin.a3.measuredmercy", "BASE_CLASS_HEAL"),
    active("aq.skill.paladin.a3.crisismercy", "BASE_CLASS_HEAL"),
    # Cross-boundary adapters; these are not extra execution definitions.
    active("aq.skill.common.w6.lastshot", "LOW_RESOURCE_DIRECT"),
    active("aq.skill.cleric.w3.sanctuary", "PRE_BARRIER_MITIGATION"),
    active("aq.skill.mage.w5.spellreversal", "PRE_BARRIER_REFLECT"),
    active("aq.skill.warrior.w6.lastvanguard", "POST_SURVIVAL_COUNTER"),
    passive("aq.skill.warrior.w4.brokenbanner", "FIRST_LOW_HP_SHIELD"),
    passive("aq.skill.cleric.w4.protectiveprayer", "SHIELD_BREAK_RESIST"),
    passive("aq.skill.paladin.w4.protectedname", "FIRST_LOW_HP_SUPPORT"),
    active("aq.skill.paladin.w3.lastfortress", "V07_TO_SHIELD_ADAPTER"),
    active("aq.skill.common.w8.protectionconversion", "V07_TO_BARRIER_ADAPTER"),
    active("aq.skill.cleric.w6.lifedistribution", "V07_REBALANCE_HEAL_ADAPTER"),
    passive("aq.skill.rogue.w3.debtinsurance", "V07_DEBT_BARRIER_ADAPTER"),
    active("aq.skill.world.w8.floodedoath", "SHOCK_TO_SHIELD"),
    passive("aq.skill.common.w8.emergencybandage", "POST_COMBAT_LOW_HP_HEAL"),
)
BY_ID = {x.skill_id: x for x in CONTRACTS}
ACTIVES = tuple(x for x in CONTRACTS if x.kind == "ACTIVE")
PASSIVES = tuple(x for x in CONTRACTS if x.kind == "PASSIVE")
EXECUTION_IDS = frozenset(x.skill_id for x in CONTRACTS if x.family in {"EXECUTION", "REVIVE_SUPPRESS_EXECUTION"})


@dataclass(frozen=True)
class CombatState:
    hp: int = 10_000
    base_max_hp: int = 10_000
    effective_max_hp: int = 10_000
    encounter_start_hp: int = 10_000
    shield: int = 0
    barrier_charges: int = 0
    alive: bool = True
    indomitable_used: int = 0
    indomitable_modifier_bps: int = 0
    soul_anchor_cap_bps: int = 0
    soul_anchor_armed: bool = False
    deferred_cost: int = 0
    last_stand_armed: bool = False
    last_stand_hp_bps: int = 0
    last_stand_used: int = 0
    revive_charges: int = 0
    revive_hp_bps: int = 1_000
    heal_tokens: int = 2
    shield_tokens: int = 2
    common_survival_encounter_used: int = 0
    common_survival_expedition_used: int = 0
    field_treatment_used: int = 0
    heal_shield_encounter_spent_bps: int = 0
    heal_shield_expedition_spent_bps: int = 0
    resource_bps: int = 5_000
    resource_ceiling_bps: int = 10_000
    max_resource_seal_bps: int = 0
    phoenix_burn_cost: bool = False
    locked_self_statuses: int = 0
    safe_returned: bool = False
    safe_return_reason: str = ""
    reward_eligible: bool = True
    death_receipt: str = ""
    receipts: frozenset[str] = frozenset()


@dataclass(frozen=True)
class Packet:
    damage: int
    delivery: str
    receipt: str
    origin: str = "NORMAL"
    revive_suppressing: bool = False


@dataclass(frozen=True)
class PacketResult:
    state: CombatState
    outcome: str
    barrier_consumed: int = 0
    shield_absorbed: int = 0
    hp_damage: int = 0
    indomitable_triggered: int = 0
    soul_anchor_triggered: int = 0
    deferred_added: int = 0
    last_stand_triggered: int = 0
    revive_triggered: int = 0
    death_finalized: int = 0
    extra_actions: int = 0


def value_at(values: tuple[int, ...], level: int) -> int:
    return skills.value_at(values, level)


def execution_threshold_bps(passive_skill_level: int | None) -> int:
    bonus = value_at(BY_ID["aq.skill.common.w4.finisherobsession"].anchors, passive_skill_level) if passive_skill_level is not None else 0
    return min(EXECUTION_THRESHOLD_CAP_BPS, EXECUTION_BASE_THRESHOLD_BPS + bonus)


def execution_candidate(skill_id: str, target_hp: int, target_max_hp: int, active_skill_level: int,
                        passive_skill_level: int | None = None, target_has_regen_or_revive: bool = False,
                        target_is_normal: bool = False) -> bool:
    if skill_id not in EXECUTION_IDS or target_hp <= 0:
        return False
    if skill_id in {"aq.skill.boss.w7.executionbell", "aq.skill.world.w8.secondfuneral"}:
        threshold = 2_500
    else:
        threshold = execution_threshold_bps(passive_skill_level)
    if target_hp * 10_000 > target_max_hp * threshold:
        return False
    if skill_id == "aq.skill.world.w8.secondfuneral" and (target_is_normal or not target_has_regen_or_revive):
        return False
    return True


def apply_packet(state: CombatState, packet: Packet) -> PacketResult:
    if packet.receipt in state.receipts:
        return PacketResult(state, "REPLAY_IGNORED")
    if not state.alive:
        return PacketResult(state, "DEAD_IGNORED")
    current = replace(state, receipts=state.receipts | {packet.receipt})
    damage = max(0, packet.damage)

    # COST is a payment/settlement, not a hit.  It bypasses every combat
    # protection and cannot create another reaction.
    if packet.delivery == "COST":
        hp_damage = min(current.hp, damage)
        hp = current.hp - damage
        if hp <= 0:
            current = replace(current, hp=0, alive=False, death_receipt=packet.receipt)
            return PacketResult(current, "DEATH", hp_damage=hp_damage, death_finalized=1)
        return PacketResult(replace(current, hp=hp), "SURVIVED", hp_damage=hp_damage)

    barrier_consumed = 0
    if packet.delivery == "DIRECT" and current.barrier_charges > 0:
        current = replace(current, barrier_charges=current.barrier_charges - 1)
        return PacketResult(current, "BARRIER", barrier_consumed=1)

    shield_absorbed = min(current.shield, damage)
    current = replace(current, shield=current.shield - shield_absorbed)
    hp_damage = damage - shield_absorbed
    indomitable = 0
    if (packet.delivery == "DIRECT"
            and current.indomitable_used == 0 and current.indomitable_modifier_bps < 0
            and hp_damage >= current.hp):
        reduced = hp_damage * (10_000 + current.indomitable_modifier_bps) // 10_000
        hp_damage = max(0, reduced)
        current = replace(current, indomitable_used=1)
        indomitable = 1

    anchor = 0
    deferred = 0
    if current.soul_anchor_armed and hp_damage >= current.hp:
        cap = current.base_max_hp * current.soul_anchor_cap_bps // 10_000
        deferred = min(hp_damage, cap)
        hp_damage -= deferred
        current = replace(current, soul_anchor_armed=False)
        anchor = 1

    hp = current.hp - hp_damage
    if hp > 0 and deferred > 0:
        current = replace(current, deferred_cost=current.deferred_cost + deferred)
    elif hp <= 0:
        deferred = 0
    current = replace(current, hp=max(0, hp))
    if hp > 0:
        return PacketResult(current, "SURVIVED", barrier_consumed, shield_absorbed, hp_damage,
                            indomitable, anchor, deferred)

    last_stand = 0
    if (current.last_stand_armed and current.last_stand_used < LAST_STAND_EXPEDITION_CAP
            and current.locked_self_statuses < 6):
        restored = max(1, current.base_max_hp * current.last_stand_hp_bps // 10_000)
        sealed_max = max(1, current.base_max_hp * (10_000 - PHOENIX_MAX_HP_SEAL_BPS) // 10_000)
        current = replace(current, hp=min(restored, sealed_max), effective_max_hp=sealed_max,
                          last_stand_used=1, last_stand_armed=False, phoenix_burn_cost=True,
                          locked_self_statuses=current.locked_self_statuses + 1)
        last_stand = 1
        return PacketResult(current, "LAST_STAND", 0, shield_absorbed, hp_damage,
                            indomitable, anchor, deferred, last_stand)

    revive = 0
    if current.revive_charges > 0 and not packet.revive_suppressing:
        revived_hp = max(1, current.effective_max_hp * current.revive_hp_bps // 10_000)
        current = replace(current, hp=revived_hp, revive_charges=current.revive_charges - 1)
        revive = 1
        return PacketResult(current, "REVIVED", 0, shield_absorbed, hp_damage,
                            indomitable, anchor, deferred, 0, revive)

    current = replace(current, hp=0, alive=False, death_receipt=packet.receipt)
    return PacketResult(current, "DEATH", 0, shield_absorbed, hp_damage,
                        indomitable, anchor, deferred, 0, 0, 1)


def settle_soul_anchor(state: CombatState, receipt: str) -> PacketResult:
    if state.deferred_cost <= 0:
        return PacketResult(state, "NO_DEFERRED")
    amount = state.deferred_cost
    cleared = replace(state, deferred_cost=0)
    return apply_packet(cleared, Packet(amount, "COST", receipt, origin="SOUL_ANCHOR_SETTLEMENT"))


@dataclass(frozen=True)
class SustainResult:
    state: CombatState
    outcome: str
    nominal_bps: int = 0
    actual_amount: int = 0
    overheal: int = 0
    resource_gain_bps: int = 0


def grant_sustain(state: CombatState, kind: str, nominal_bps: int, receipt: str,
                  common_shared: bool = False) -> SustainResult:
    if receipt in state.receipts:
        return SustainResult(state, "REPLAY_IGNORED")
    if not state.alive or state.hp <= 0:
        return SustainResult(state, "DEAD_TARGET_REJECTED")
    if nominal_bps <= 0 or state.heal_shield_encounter_spent_bps + nominal_bps > HEAL_SHIELD_ENCOUNTER_CAP_BPS or state.heal_shield_expedition_spent_bps + nominal_bps > HEAL_SHIELD_EXPEDITION_CAP_BPS:
        return SustainResult(state, "BUDGET_REJECTED")
    if common_shared and (state.common_survival_encounter_used >= COMMON_SURVIVAL_ENCOUNTER_CAP or state.common_survival_expedition_used >= COMMON_SURVIVAL_EXPEDITION_CAP):
        return SustainResult(state, "LEDGER_REJECTED")
    current = replace(state,
                      heal_shield_encounter_spent_bps=state.heal_shield_encounter_spent_bps + nominal_bps,
                      heal_shield_expedition_spent_bps=state.heal_shield_expedition_spent_bps + nominal_bps,
                      common_survival_encounter_used=state.common_survival_encounter_used + (1 if common_shared else 0),
                      common_survival_expedition_used=state.common_survival_expedition_used + (1 if common_shared else 0),
                      receipts=state.receipts | {receipt})
    nominal = current.base_max_hp * nominal_bps // 10_000
    if kind == "HEAL":
        ceiling = min(current.effective_max_hp, current.encounter_start_hp)
        actual = min(max(0, ceiling - current.hp), nominal)
        overheal = nominal - actual
        return SustainResult(replace(current, hp=current.hp + actual), "RESOLVED", nominal_bps, actual, overheal)
    actual = nominal
    return SustainResult(replace(current, shield=current.shield + actual), "RESOLVED", nominal_bps, actual)


def focused_breathing(state: CombatState, recovery_bps: int, receipt: str) -> SustainResult:
    if receipt in state.receipts:
        return SustainResult(state, "REPLAY_IGNORED")
    if not state.alive or state.hp <= 0:
        return SustainResult(state, "DEAD_TARGET_REJECTED")
    actual = min(recovery_bps, max(0, state.resource_ceiling_bps - state.resource_bps))
    if actual <= 0:
        return SustainResult(state, "NOT_CANDIDATE")
    return SustainResult(replace(state, resource_bps=state.resource_bps + actual,
                                 receipts=state.receipts | {receipt}), "RESOLVED", actual_amount=actual)


def field_treatment(state: CombatState, heal_bps: int, victory: bool, receipt: str) -> SustainResult:
    if not victory or state.hp * 10_000 >= state.effective_max_hp * 5_000 or state.field_treatment_used >= FIELD_TREATMENT_EXPEDITION_CAP:
        return SustainResult(state, "NOT_CANDIDATE")
    result = grant_sustain(state, "HEAL", heal_bps, receipt)
    return replace(result, state=replace(result.state, field_treatment_used=state.field_treatment_used + (1 if result.outcome == "RESOLVED" else 0)))


def overheal_to_resource(state: CombatState, overheal: int, gain_cap_bps: int) -> tuple[CombatState, int]:
    if overheal <= 0:
        return state, 0
    gain = min(gain_cap_bps, state.resource_ceiling_bps - state.resource_bps)
    return replace(state, resource_bps=state.resource_bps + gain), gain


def ordinary_attrition_bps(passive_value_bps: int) -> int:
    return max(4_500, 5_000 - max(0, passive_value_bps))


def safe_return(state: CombatState, receipt: str, reason: str = "DEFEAT") -> tuple[CombatState, str]:
    if receipt in state.receipts:
        return state, "REPLAY_IGNORED"
    if reason not in {"DEFEAT", "RETREAT", "INVALID_PLAN"}:
        return state, "NOT_CANDIDATE"
    if reason == "DEFEAT" and (state.alive or not state.death_receipt):
        return state, "NOT_CANDIDATE"
    if reason == "RETREAT" and not state.alive:
        return state, "NOT_CANDIDATE"
    return replace(state, shield=0, barrier_charges=0, soul_anchor_armed=False,
                   deferred_cost=0, last_stand_armed=False, safe_returned=True,
                   safe_return_reason=reason, reward_eligible=False,
                   receipts=state.receipts | {receipt}), "SAFE_RETURNED"


def apply_packets(state: CombatState, packets: tuple[Packet, ...]) -> tuple[CombatState, int]:
    current = state
    resolved = 0
    for packet in packets:
        if not current.alive:
            break
        result = apply_packet(current, packet)
        current = result.state
        resolved += 1
    return current, resolved


def owner_class(contract: Contract) -> str:
    item = (skills.ACTIVE_BY_ID if contract.kind == "ACTIVE" else skills.PASSIVE_BY_ID)[contract.skill_id]
    return item.owner_scope if item.owner_scope in skills.CLASSES else "CLERIC"


@lru_cache(maxsize=None)
def loadout(skill_id: str, skill_level: int):
    contract = BY_ID[skill_id]
    hero_class = owner_class(contract)
    active_ids = [skill_id] if contract.kind == "ACTIVE" else []
    pool = [x for x in skills.accessible(hero_class, "ACTIVE") if x.definition_id not in active_ids]
    pool.sort(key=lambda x: (-resolver.role_score(x, "SUSTAIN"), x.definition_id))
    active_ids.extend(x.definition_id for x in pool[:5 - len(active_ids)])
    passive_ids = [skill_id] if contract.kind == "PASSIVE" else []
    ppool = [x for x in skills.accessible(hero_class, "PASSIVE") if x.definition_id not in passive_ids]
    ppool.sort(key=lambda x: (-resolver.role_score(x, "SUSTAIN"), x.definition_id))
    passive_ids.extend(x.definition_id for x in ppool[:3 - len(passive_ids)])
    return skills.commit_loadout(hero_class, {x: skill_level for x in active_ids[:5]},
                                 {x: skill_level for x in passive_ids[:3]},
                                 "aq.behavior.protection-death.v1")


@dataclass(frozen=True)
class Scenario:
    skill_id: str
    display_level: int
    variant_id: str
    mode: str
    outcome: str
    alive: bool
    hp: int
    shield: int
    barrier: int
    last_stand_used: int
    revive_charges: int
    death_receipt: str
    extra_actions: int
    active_slots: int
    passive_slots: int


def simulate(contract: Contract, display_level: int, variant, mode: str) -> Scenario:
    level = SKILL_LEVELS[display_level]
    state = CombatState(hp=1_500, shield=500, barrier_charges=1 if mode == "BARRIER" else 0)
    if contract.family == "LETHAL_MITIGATION":
        state = replace(state, indomitable_modifier_bps=value_at(contract.anchors, level))
    if contract.family == "LETHAL_DELAY":
        state = replace(state, soul_anchor_armed=True, soul_anchor_cap_bps=value_at(contract.anchors, level))
    if contract.family == "LAST_STAND":
        state = replace(state, last_stand_armed=True, last_stand_hp_bps=value_at(contract.anchors, level))
    if contract.family == "REVIVE_SUPPRESS_EXECUTION":
        state = replace(state, revive_charges=1)
    delivery = {"DOT": "PERIODIC", "COST": "COST"}.get(mode, "DIRECT")
    origin = "DELAYED" if mode == "DELAYED" else ("EXECUTION" if contract.family in {"EXECUTION", "REVIVE_SUPPRESS_EXECUTION"} else "NORMAL")
    suppress = contract.family == "REVIVE_SUPPRESS_EXECUTION"
    result = apply_packet(state, Packet(3_000, delivery, f"{contract.skill_id}:{display_level}:{variant.variant_id}:{mode}", origin, suppress))
    if mode == "REPLAY":
        result = apply_packet(result.state, Packet(3_000, delivery, f"{contract.skill_id}:{display_level}:{variant.variant_id}:{mode}", origin, suppress))
    snapshot = loadout(contract.skill_id, level)
    return Scenario(contract.skill_id, display_level, variant.variant_id, mode, result.outcome,
                    result.state.alive, result.state.hp, result.state.shield, result.state.barrier_charges,
                    result.state.last_stand_used, result.state.revive_charges,
                    result.state.death_receipt, result.extra_actions,
                    len(snapshot.active_slots), len(snapshot.passive_slots))


@lru_cache(maxsize=1)
def integration_matrix() -> tuple[Scenario, ...]:
    return tuple(simulate(contract, display, variant, mode)
                 for contract in CONTRACTS
                 for display in DISPLAY_LEVELS
                 for variant in monsters.VARIANTS
                 for mode in SCENARIO_MODES)


def branch_probes() -> tuple[tuple[str, bool], ...]:
    probes: list[tuple[str, bool]] = []
    barrier = apply_packet(CombatState(hp=100, barrier_charges=1, shield=100), Packet(999_999, "DIRECT", "barrier"))
    dot = apply_packet(CombatState(hp=100, barrier_charges=1, shield=50), Packet(75, "PERIODIC", "dot"))
    cost = apply_packet(CombatState(hp=100, barrier_charges=1, shield=100, last_stand_armed=True, last_stand_hp_bps=700), Packet(100, "COST", "cost"))
    indomitable = apply_packet(CombatState(hp=1_000, indomitable_modifier_bps=-3_500), Packet(1_200, "DIRECT", "indomitable"))
    anchor = apply_packet(CombatState(hp=1_000, soul_anchor_armed=True, soul_anchor_cap_bps=3_000), Packet(1_500, "DIRECT", "anchor"))
    anchor_lethal = apply_packet(CombatState(hp=1_000, soul_anchor_armed=True, soul_anchor_cap_bps=3_000), Packet(5_000, "DIRECT", "anchor:lethal"))
    anchor_death = settle_soul_anchor(anchor.state, "anchor:settle")
    phoenix = apply_packet(CombatState(hp=100, last_stand_armed=True, last_stand_hp_bps=700, revive_charges=1), Packet(200, "DIRECT", "phoenix"))
    second = apply_packet(CombatState(hp=100, revive_charges=1), Packet(200, "DIRECT", "second", origin="EXECUTION", revive_suppressing=True))
    normal_revive = apply_packet(CombatState(hp=100, revive_charges=1), Packet(200, "DIRECT", "revive"))
    replay = apply_packet(second.state, Packet(200, "DIRECT", "second", origin="EXECUTION", revive_suppressing=True))
    heal = grant_sustain(CombatState(hp=5_000, encounter_start_hp=8_000), "HEAL", 1_000, "heal")
    blocked = grant_sustain(replace(heal.state, heal_shield_encounter_spent_bps=2_500), "SHIELD", 600, "over-budget")
    probes.extend((
        ("barrier before shield", barrier.outcome == "BARRIER" and barrier.state.shield == 100),
        ("dot skips barrier and uses shield", dot.state.barrier_charges == 1 and dot.shield_absorbed == 50 and dot.state.hp == 75),
        ("cost bypasses all reactions", cost.outcome == "DEATH" and cost.state.barrier_charges == 1 and cost.state.shield == 100 and cost.last_stand_triggered == 0),
        ("indomitable mitigates but does not guarantee one hp", indomitable.indomitable_triggered == 1 and indomitable.state.hp == 220),
        ("soul anchor defers bounded damage", anchor.soul_anchor_triggered == 1 and anchor.deferred_added == 1_500 and anchor.state.hp == 1_000),
        ("immediate lethal still kills despite anchor", anchor_lethal.soul_anchor_triggered == 1 and not anchor_lethal.state.alive),
        ("deferred cost creates no new reaction", anchor_death.outcome == "DEATH" and anchor_death.last_stand_triggered == 0 and anchor_death.revive_triggered == 0),
        ("phoenix precedes ordinary revive", phoenix.outcome == "LAST_STAND" and phoenix.state.revive_charges == 1 and phoenix.state.effective_max_hp == 7_500),
        ("second funeral suppresses current revive", second.outcome == "DEATH" and second.state.revive_charges == 1),
        ("ordinary lethal may consume one revive", normal_revive.outcome == "REVIVED" and normal_revive.state.revive_charges == 0),
        ("death replay is idempotent", replay.outcome == "REPLAY_IGNORED" and replay.state == second.state),
        ("heal capped by encounter start hp", heal.actual_amount == 1_000 and heal.state.hp == 6_000),
        ("nominal budget rejects partial grant", blocked.outcome == "BUDGET_REJECTED" and blocked.actual_amount == 0),
        ("execution threshold boundary", execution_candidate("aq.skill.common.w4.certainfinish", 2_500, 10_000, 100, 100) and not execution_candidate("aq.skill.common.w4.certainfinish", 2_501, 10_000, 100, 100)),
        ("second funeral target gate", execution_candidate("aq.skill.world.w8.secondfuneral", 2_500, 10_000, 100, target_has_regen_or_revive=True) and not execution_candidate("aq.skill.world.w8.secondfuneral", 2_500, 10_000, 100, target_has_regen_or_revive=False)),
        ("incoming passive hard floor", max(INCOMING_PASSIVE_FLOOR_BPS, -600 + -500) == -700),
        ("ordinary attrition floor", ordinary_attrition_bps(500) == 4_500 and ordinary_attrition_bps(999) == 4_500),
        ("dead target cannot be healed", grant_sustain(replace(cost.state, receipts=frozenset()), "HEAL", 500, "dead:heal").outcome == "DEAD_TARGET_REJECTED"),
        ("multi packet stops at terminal death", apply_packets(CombatState(hp=100), (Packet(200, "DIRECT", "multi:1"), Packet(200, "DIRECT", "multi:2")))[1] == 1),
        ("defeat return cancels pending without reward", (lambda x: x[1] == "SAFE_RETURNED" and x[0].safe_return_reason == "DEFEAT" and not x[0].reward_eligible and x[0].deferred_cost == 0)(safe_return(replace(cost.state, deferred_cost=100), "return"))),
        ("living retreat preserves hp resource but clears transient", (lambda x: x[1] == "SAFE_RETURNED" and x[0].hp == 777 and x[0].resource_bps == 333 and x[0].shield == 0 and x[0].barrier_charges == 0 and x[0].safe_return_reason == "RETREAT")(safe_return(CombatState(hp=777, resource_bps=333, shield=99, barrier_charges=1), "retreat", "RETREAT"))),
        ("dead resource recovery rejected", focused_breathing(cost.state, 1_000, "dead:resource").outcome == "DEAD_TARGET_REJECTED"),
        ("phoenix cost status increments atomically", phoenix.state.phoenix_burn_cost and phoenix.state.locked_self_statuses == 1),
    ))
    return tuple(probes)


def canonical_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "costTransferV07Hash": cost_v07.canonical_hash(),
        "preparedV04Hash": prepared.canonical_hash(),
        "skillRegistryHash": skills.canonical_hash(),
        "monsterRegistryHash": monsters.canonical_hash(),
        "contracts": [asdict(x) for x in CONTRACTS],
        "deathOrder": ("RECEIPT", "COST_BYPASS_OR_HIT", "PRE_BARRIER_MODIFIER", "BARRIER", "SHIELD", "HP_FILTER", "LETHAL_MITIGATION", "SOUL_ANCHOR", "HP", "LAST_STAND", "FUTURE_REVIVE_FIXTURE", "DEATH"),
        "caps": {"healShieldEncounterBps": HEAL_SHIELD_ENCOUNTER_CAP_BPS,
                 "healShieldExpeditionBps": HEAL_SHIELD_EXPEDITION_CAP_BPS,
                 "barrierEncounter": BARRIER_ENCOUNTER_CAP,
                 "barrierExpedition": BARRIER_EXPEDITION_CAP,
                 "lastStandExpedition": LAST_STAND_EXPEDITION_CAP,
                 "nonDamageRootsPerTen": MAX_NON_DAMAGE_ROOTS_PER_TEN,
                 "executionThresholdBps": EXECUTION_THRESHOLD_CAP_BPS},
        "deliveries": ("DIRECT", "PERIODIC", "COST"),
        "origins": ("NORMAL", "DELAYED", "EXECUTION", "SOUL_ANCHOR_SETTLEMENT"),
        "productionReviveProviderCount": 0,
        "syntheticFutureReviveFixture": True,
        "activeSlots": 5,
        "passiveSlots": 3,
        "manualCombatAction": False,
        "itemGrantedSkills": False,
        "lossDeletesCharacter": False,
        "productionEnabled": False,
    }


def canonical_hash() -> str:
    raw = json.dumps(canonical_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def summary(matrix: tuple[Scenario, ...]) -> dict:
    return {"scenarioCount": len(matrix), "contracts": {"active": len(ACTIVES), "passive": len(PASSIVES)},
            "variants": len({x.variant_id for x in matrix}), "displayLevels": sorted({x.display_level for x in matrix}),
            "outcomes": dict(sorted(Counter(x.outcome for x in matrix).items())),
            "branchProbes": len(branch_probes()), "deaths": sum(not x.alive for x in matrix),
            "lastStands": sum(x.last_stand_used for x in matrix)}


def checks(matrix: tuple[Scenario, ...]) -> list[tuple[str, bool, str]]:
    report = summary(matrix)
    probes = branch_probes()
    return [
        ("forty five contracts exact", len(CONTRACTS) == 45 and len(ACTIVES) == 32 and len(PASSIVES) == 13 and len(BY_ID) == 45, f"active={len(ACTIVES)} passive={len(PASSIVES)}"),
        ("matrix covers 45 x 4 x 144 x 6", len(matrix) == 155_520 and report["variants"] == 144, f"scenarios={len(matrix)} variants={report['variants']}"),
        ("actual five plus three loadouts", all(x.active_slots == 5 and x.passive_slots == 3 for x in matrix), "active=5 passive=3"),
        ("all branch probes pass", all(ok for _, ok in probes), f"passed={sum(ok for _, ok in probes)}/{len(probes)}"),
        ("levels one to 9999", report["displayLevels"] == [1, 100, 1_000, 9_999], str(report["displayLevels"])),
        ("alive state is coherent", all((x.alive and x.hp >= 1 and not x.death_receipt) or (not x.alive and x.hp == 0 and bool(x.death_receipt)) or x.outcome == "REPLAY_IGNORED" for x in matrix), "incoherent=0"),
        ("protection never underflows", all(x.shield >= 0 and x.barrier >= 0 and x.extra_actions == 0 for x in matrix), "underflow=0 extra=0"),
        ("execution set exact", len(EXECUTION_IDS) == 4 and "aq.skill.world.w8.secondfuneral" in EXECUTION_IDS, str(sorted(EXECUTION_IDS))),
        ("base class protection providers exact", sum(x.family.startswith("BASE_CLASS_") for x in CONTRACTS) == 12, "providers=12"),
        ("v07 positive adapters exact", sum("V07_" in x.family for x in CONTRACTS) == 4, "adapters=4"),
        ("sustain budgets fixed", HEAL_SHIELD_ENCOUNTER_CAP_BPS == 3_000 and HEAL_SHIELD_EXPEDITION_CAP_BPS == 7_000 and MAX_NON_DAMAGE_ROOTS_PER_TEN == 6, "3000/7000/6"),
        ("source hashes bound", canonical_payload()["costTransferV07Hash"] == cost_v07.canonical_hash() and canonical_payload()["monsterRegistryHash"] == monsters.canonical_hash(), cost_v07.canonical_hash()[:12]),
        ("automatic safe return test only", not canonical_payload()["manualCombatAction"] and not canonical_payload()["itemGrantedSkills"] and not canonical_payload()["lossDeletesCharacter"] and not canonical_payload()["productionEnabled"], "manual=false itemSkill=false delete=false production=false"),
    ]


def kotlin_manifest_matches() -> bool:
    if not KOTLIN_CONTRACT.exists():
        return False
    found = {}
    pattern = re.compile(
        r'contract\("([^"]+)",\s*"[^"]+",\s*"(ACTIVE|PASSIVE)",\s*"([A-Z0-9_]+)",\s*listOf\(([^)]*)\)\)'
    )
    for skill_id, kind, family, raw_values in pattern.findall(KOTLIN_CONTRACT.read_text(encoding="utf-8")):
        values = tuple(int(x.replace("_", "").strip()) for x in raw_values.split(",") if x.strip())
        found[skill_id] = (kind, family, values)
    expected = {x.skill_id: (x.kind, x.family, x.anchors) for x in CONTRACTS}
    return found == expected


def pd_checks(matrix: tuple[Scenario, ...]) -> list[tuple[str, bool, str]]:
    result = checks(matrix)
    doc = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    kotlin = KOTLIN_CONTRACT.read_text(encoding="utf-8") if KOTLIN_CONTRACT.exists() else ""
    result.extend((
        ("document binds v08 hash", canonical_hash() in doc, canonical_hash()),
        ("kotlin binds v08 hash", canonical_hash() in kotlin, canonical_hash()),
        ("kotlin manifest fields match python", kotlin_manifest_matches(), "ids families anchors"),
        ("document keeps production gate", "production 조건부 승인" in doc and "라이브 NO-GO" in doc, "explicit gate"),
        ("document keeps safe return", "안전 귀환" in doc and "캐릭터 삭제" in doc, "loss is not deletion"),
    ))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()
    matrix = integration_matrix()
    if args.summary:
        print(json.dumps(summary(matrix), ensure_ascii=False, indent=2, sort_keys=True))
        print(f"canonical_sha256={canonical_hash()}")
        return 0
    result = pd_checks(matrix) if args.pd else checks(matrix)
    print(f"{'PD' if args.pd else 'BALANCE'}: {sum(ok for _, ok, _ in result)}/{len(result)} PASS")
    for name, ok, detail in result:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"canonical_sha256={canonical_hash()}")
    return 0 if all(ok for _, ok, _ in result) else 1


if __name__ == "__main__":
    raise SystemExit(main())
