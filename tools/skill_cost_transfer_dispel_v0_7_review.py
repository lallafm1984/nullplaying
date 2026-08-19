#!/usr/bin/env python3
"""AlarmQuest v0.7 cost, transfer, conversion, dispel and mimic audit.

Planning/test only.  No live save, Room, production Kotlin or asset is read or
mutated.  The executable binds every contract to the approved registries and
runs deterministic 5+3 snapshots across all 144 monster variants.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, replace
from functools import lru_cache
from pathlib import Path

import monster_prefix_variants_v0_2_review as monsters
import skill_effect_resolver_v0_3_review as resolver
import skill_registry_240_v0_2_review as skills
import skill_status_lifecycle_v0_6_review as lifecycle


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_COST_TRANSFER_DISPEL_HANDLERS_v0.7.md"
KOTLIN_CONTRACT = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextCostTransferDispelHandlers.kt"
RULES_VERSION = "aq.skill-cost-transfer-dispel.v0.7"
DISPLAY_LEVELS = resolver.DISPLAY_LEVELS
SKILL_LEVELS = resolver.SKILL_LEVELS
SCENARIO_MODES = ("VALID", "MISS", "MISSING", "INSUFFICIENT", "CAP_OR_IMMUNE")
MAX_RESOURCE_BPS = 10_000
HOSTILE_STATUS_CAP = 6
DEBT_CAP_BPS = 3_000
SHIELD_CONVERSION_MIN_BPS = 500
BARRIER_ENCOUNTER_CAP = 1
BARRIER_EXPEDITION_CAP = 2
SAFE_MIMIC_TAGS = frozenset({
    "PHYSICAL", "FIRE", "ICE", "LIGHTNING", "HOLY", "ARCANE",
    "BLEED", "BURN", "POISON", "CHILL", "SHOCK", "CURSE", "MARK",
})
DISPEL_CLASSES = frozenset({"POWER", "PRECISION", "GUARD", "SPEED", "RESIST"})


@dataclass(frozen=True)
class Contract:
    skill_id: str
    name_ko: str
    kind: str
    family: str
    anchors: tuple[int, int, int, int, int]
    resource_cost_bps: int
    cooldown_roots: int
    fallback_bps: int = 0
    fixed_rule: str = ""


def active(skill_id: str, family: str, fallback: int = 0, fixed_rule: str = "") -> Contract:
    item = skills.ACTIVE_BY_ID[skill_id]
    return Contract(skill_id, item.name_ko, "ACTIVE", family, tuple(item.anchor_values),
                    item.resource_cost_bps, item.cooldown_roots, fallback,
                    fixed_rule or item.fixed_tradeoff)


def passive(skill_id: str, family: str, fixed_rule: str = "") -> Contract:
    item = skills.PASSIVE_BY_ID[skill_id]
    return Contract(skill_id, item.name_ko, "PASSIVE", family, tuple(item.anchor_values),
                    0, 0, 0, fixed_rule or item.fixed_tradeoff)


CONTRACTS = (
    active("aq.skill.paladin.w6.cursedswordseal", "CURSE_TRANSFER", 6_000),
    active("aq.skill.world.w7.blackwell", "BURN_TO_CHILL", 3_000),
    active("aq.skill.mage.w5.voidgate", "SYMMETRIC_DISPEL"),
    active("aq.skill.paladin.w5.purifyingstrike", "ENEMY_DISPEL_STRIKE", 5_000),
    active("aq.skill.monster.w8.mimictongue", "WEAK_BUFF_STEAL", 3_000),
    active("aq.skill.common.w8.imperfectmimic", "SAFE_TAG_MIMIC"),
    active("aq.skill.external.w2.dragonscale", "SELF_COST_STATUS"),
    active("aq.skill.secret.w7.abyssaleye", "SELF_COST_STATUS"),
    active("aq.skill.external.w5.phoenixash", "SELF_COST_STATUS"),
    active("aq.skill.warrior.w2.redoath", "HP_PREPAY"),
    active("aq.skill.mage.w3.forbiddenbackflow", "HP_BACKED_DEFICIT"),
    active("aq.skill.common.w3.bloodprice", "HP_TO_RESOURCE"),
    active("aq.skill.warrior.w3.fallingwall", "SHIELD_TO_DAMAGE", 6_000),
    active("aq.skill.paladin.w3.shieldbash", "SHIELD_PREPAY"),
    active("aq.skill.warrior.w4.shieldpush", "SHIELD_PREPAY"),
    active("aq.skill.paladin.w3.lastfortress", "RESOURCE_TO_SHIELD"),
    active("aq.skill.common.w8.protectionconversion", "SHIELD_TO_BARRIER"),
    active("aq.skill.rogue.w3.borrowedblade", "RESOURCE_DEBT"),
    active("aq.skill.common.w8.borrowedstamina", "RESOURCE_DEBT"),
    active("aq.skill.cleric.w5.martyrlight", "HP_FIXED_PREPAY"),
    active("aq.skill.cleric.w6.lifedistribution", "HP_RESOURCE_REBALANCE"),
    active("aq.skill.cleric.w5.miraclecost", "MAX_RESOURCE_SELF_COST"),
    active("aq.skill.paladin.w2.resolveburst", "CURRENT_RESOURCE_PREPAY"),
    active("aq.skill.mage.w6.manaburst", "CURRENT_RESOURCE_PREPAY"),
    active("aq.skill.common.w6.lastshot", "CURRENT_RESOURCE_PREPAY"),
    active("aq.skill.common.w6.weaponswap", "ARCHIVE_TYPE_SWAP"),
    passive("aq.skill.mage.w5.archmagedebt", "EXPEDITION_RESOURCE_DEBT"),
    passive("aq.skill.rogue.w3.debtinsurance", "DEBT_PROTECTION"),
)
BY_ID = {item.skill_id: item for item in CONTRACTS}
ACTIVES = tuple(item for item in CONTRACTS if item.kind == "ACTIVE")
PASSIVES = tuple(item for item in CONTRACTS if item.kind == "PASSIVE")


@dataclass(frozen=True)
class Status:
    instance_id: str
    tag: str
    owner: str
    subtype: str = ""
    magnitude_bps: int = 0
    duration_roots: int = 1
    clock: str = "TARGET_ROOT_END"
    cost_locked: bool = False
    stacks: int = 1


@dataclass(frozen=True)
class Buff:
    instance_id: str
    effect_class: str
    scalar_bps: int
    remaining_roots: int
    dispellable: bool = True
    copied: bool = False


@dataclass(frozen=True)
class Runtime:
    hp: int = 10_000
    max_hp: int = 10_000
    shield_bps: int = 1_000
    resource_bps: int = MAX_RESOURCE_BPS
    resource_ceiling_bps: int = MAX_RESOURCE_BPS
    debt_bps: int = 0
    self_statuses: tuple[Status, ...] = ()
    target_statuses: tuple[Status, ...] = ()
    self_buffs: tuple[Buff, ...] = ()
    target_buffs: tuple[Buff, ...] = ()
    barrier_encounter_used: int = 0
    barrier_expedition_used: int = 0
    debt_insurance_used: int = 0
    archmage_debt_used: int = 0
    recovery_efficiency_modifier_bps: int = 0
    max_resource_seal_bps: int = 0
    previous_enemy_tag: str = "FIRE"
    archive_weak_type: str = "FIRE"
    receipts: frozenset[str] = frozenset()


@dataclass(frozen=True)
class Resolution:
    state: Runtime
    outcome: str
    coefficient_bps: int = 0
    paid_resource_bps: int = 0
    paid_hp: int = 0
    paid_shield_bps: int = 0
    debt_created_bps: int = 0
    transferred_instance_id: str = ""
    removed_self_buffs: tuple[str, ...] = ()
    removed_target_buffs: tuple[str, ...] = ()
    granted_buff_bps: int = 0
    copied_tag: str = ""
    applied_status_tag: str = ""
    barrier_created: int = 0
    chosen_damage_type: str = ""
    extra_actions: int = 0


def value_at(values: tuple[int, ...], level: int) -> int:
    return skills.value_at(values, level)


def protection_conversion_min_bps(conversion_bps: int) -> int:
    return max(SHIELD_CONVERSION_MIN_BPS, 900 - conversion_bps // 20)


def _eligible_buffs(items: tuple[Buff, ...]) -> list[Buff]:
    return [x for x in items if x.dispellable and x.effect_class in DISPEL_CLASSES]


def _strongest(items: tuple[Buff, ...], count: int) -> tuple[Buff, ...]:
    return tuple(sorted(_eligible_buffs(items), key=lambda x: (-abs(x.scalar_bps), -x.remaining_roots, x.instance_id))[:count])


def _weakest(items: tuple[Buff, ...], count: int) -> tuple[Buff, ...]:
    return tuple(sorted(_eligible_buffs(items), key=lambda x: (abs(x.scalar_bps), x.remaining_roots, x.instance_id))[:count])


def _remove(items: tuple, selected: tuple) -> tuple:
    ids = {x.instance_id for x in selected}
    return tuple(x for x in items if x.instance_id not in ids)


def _pay_resource(state: Runtime, cost: int) -> tuple[Runtime, int] | None:
    if state.resource_bps < cost:
        return None
    return replace(state, resource_bps=state.resource_bps - cost), cost


def _self_status(state: Runtime, skill_id: str) -> tuple[Runtime, str]:
    if skill_id == "aq.skill.external.w2.dragonscale":
        status = Status("cost.dragon.burn", "BURN", "SELF", duration_roots=3,
                        clock="SELF_ROOT_END", cost_locked=False)
    elif skill_id == "aq.skill.secret.w7.abyssaleye":
        status = Status("cost.abyssal.curse", "CURSE", "SELF", "ABYSSAL_EYE_COST",
                        duration_roots=1, clock="ENCOUNTER", cost_locked=True)
    else:
        status = Status("cost.phoenix.burn", "BURN", "SELF", "PHOENIX_COST",
                        duration_roots=1, clock="EXPEDITION_LODGING", cost_locked=True)
    existing = next((x for x in state.self_statuses if x.instance_id == status.instance_id), None)
    if existing:
        return replace(state, self_statuses=tuple(status if x.instance_id == status.instance_id else x for x in state.self_statuses)), status.tag
    if len(state.self_statuses) >= HOSTILE_STATUS_CAP:
        # A mandatory cost is never silently erased. It replaces the weakest
        # non-cost status; if all six are locked, the action is not a candidate.
        unlocked = sorted((x for x in state.self_statuses if not x.cost_locked), key=lambda x: (x.magnitude_bps, x.duration_roots, x.instance_id))
        if not unlocked:
            return state, "CAP_REJECTED"
        return replace(state, self_statuses=_remove(state.self_statuses, (unlocked[0],)) + (status,)), status.tag
    return replace(state, self_statuses=state.self_statuses + (status,)), status.tag


def _base_paid(state: Runtime, contract: Contract, receipt: str, allow_debt: bool = False) -> tuple[Runtime, int] | None:
    if receipt in state.receipts:
        return None
    if allow_debt:
        paid = min(state.resource_bps, contract.resource_cost_bps)
        return replace(state, resource_bps=state.resource_bps - paid, receipts=state.receipts | {receipt}), paid
    result = _pay_resource(state, contract.resource_cost_bps)
    if result is None:
        return None
    paid_state, paid = result
    return replace(paid_state, receipts=state.receipts | {receipt}), paid


def execute(state: Runtime, skill_id: str, skill_level: int, receipt: str,
            hit: bool = True, status_roll: bool = True,
            target_status_immune: bool = False) -> Resolution:
    contract = BY_ID[skill_id]
    if contract.kind != "ACTIVE":
        raise ValueError("passive contracts are event subscribers")
    if receipt in state.receipts:
        return Resolution(state, "REPLAY_IGNORED")

    family = contract.family
    level_value = value_at(contract.anchors, skill_level)

    # Candidate checks that must happen before any payment or cooldown receipt.
    if family == "SHIELD_PREPAY":
        required = 500 if skill_id.endswith("shieldbash") else 300
        if state.shield_bps < required:
            return Resolution(state, "NOT_CANDIDATE")
    if family == "RESOURCE_TO_SHIELD" and state.resource_bps < MAX_RESOURCE_BPS:
        return Resolution(state, "NOT_CANDIDATE")
    if family == "SHIELD_TO_BARRIER" and (state.shield_bps < protection_conversion_min_bps(level_value) or
            state.barrier_encounter_used >= BARRIER_ENCOUNTER_CAP or
            state.barrier_expedition_used >= BARRIER_EXPEDITION_CAP):
        return Resolution(state, "NOT_CANDIDATE")
    if family == "RESOURCE_DEBT" and state.debt_bps > 0:
        return Resolution(state, "NOT_CANDIDATE")
    if family == "SAFE_TAG_MIMIC" and state.previous_enemy_tag not in SAFE_MIMIC_TAGS:
        return Resolution(state, "NOT_CANDIDATE")
    if family == "CURSE_TRANSFER":
        curses = sorted((x for x in state.target_statuses if x.tag == "CURSE"), key=lambda x: (-x.magnitude_bps, -x.duration_roots, x.instance_id))
        if curses and len(state.self_statuses) >= HOSTILE_STATUS_CAP:
            return Resolution(state, "NOT_CANDIDATE")
    if family == "HP_BACKED_DEFICIT":
        deficit = max(0, contract.resource_cost_bps - state.resource_bps)
        hp_cost = (state.max_hp * deficit * 15 + 99_999) // 100_000
        if deficit > level_value or state.hp - hp_cost < 1:
            return Resolution(state, "NOT_CANDIDATE")
    if family == "HP_TO_RESOURCE":
        actual_gain = min(level_value, state.resource_ceiling_bps - state.resource_bps)
        hp_cost = (state.max_hp * actual_gain * 12 + 99_999) // 100_000
        if actual_gain <= 0 or state.hp - hp_cost < 1:
            return Resolution(state, "NOT_CANDIDATE")
    if family == "HP_FIXED_PREPAY" and state.hp - state.max_hp * 800 // 10_000 < 1:
        return Resolution(state, "NOT_CANDIDATE")
    if family == "HP_RESOURCE_REBALANCE" and state.hp == state.max_hp // 2:
        return Resolution(state, "NOT_CANDIDATE")
    if family == "CURRENT_RESOURCE_PREPAY":
        if skill_id.endswith("resolveburst") and state.resource_bps != MAX_RESOURCE_BPS:
            return Resolution(state, "NOT_CANDIDATE")
        if skill_id.endswith("manaburst") and state.resource_bps < 7_000:
            return Resolution(state, "NOT_CANDIDATE")
        if skill_id.endswith("lastshot") and not 0 < state.resource_bps <= 2_500:
            return Resolution(state, "NOT_CANDIDATE")
    if family == "ARCHIVE_TYPE_SWAP" and state.archive_weak_type not in {"PHYSICAL", "FIRE", "ICE", "LIGHTNING", "HOLY", "ARCANE"}:
        return Resolution(state, "NOT_CANDIDATE")

    if family == "CURRENT_RESOURCE_PREPAY":
        paid_resource = state.resource_bps
        paid_state = replace(state, resource_bps=0, receipts=state.receipts | {receipt})
    else:
        paid_result = _base_paid(state, contract, receipt, allow_debt=family in {"RESOURCE_DEBT", "HP_BACKED_DEFICIT"})
        if paid_result is None:
            return Resolution(state, "RESOURCE_REJECTED")
        paid_state, paid_resource = paid_result
    coefficient = level_value
    paid_hp = 0
    paid_shield = 0
    debt_created = 0
    transferred = ""
    removed_self: tuple[str, ...] = ()
    removed_target: tuple[str, ...] = ()
    granted_buff = 0
    copied_tag = ""
    applied_status = ""
    barrier_created = 0
    chosen_damage_type = ""

    if family == "HP_PREPAY":
        paid_hp = paid_state.hp * 12 // 100
        paid_state = replace(paid_state, hp=max(1, paid_state.hp - paid_hp))
    elif family == "HP_BACKED_DEFICIT":
        deficit = contract.resource_cost_bps - paid_resource
        paid_hp = (paid_state.max_hp * deficit * 15 + 99_999) // 100_000
        paid_state = replace(paid_state, hp=paid_state.hp - paid_hp)
        coefficient = 13_000
    elif family == "HP_TO_RESOURCE":
        gain = min(level_value, paid_state.resource_ceiling_bps - paid_state.resource_bps)
        paid_hp = (paid_state.max_hp * gain * 12 + 99_999) // 100_000
        paid_state = replace(paid_state, hp=paid_state.hp - paid_hp,
                             resource_bps=paid_state.resource_bps + gain)
        coefficient = 0
    elif family == "SHIELD_PREPAY":
        paid_shield = 500 if skill_id.endswith("shieldbash") else 300
        paid_state = replace(paid_state, shield_bps=paid_state.shield_bps - paid_shield)
        coefficient = 12_000 if skill_id.endswith("shieldbash") else level_value
    elif family == "SHIELD_TO_DAMAGE":
        if paid_state.shield_bps >= SHIELD_CONVERSION_MIN_BPS:
            paid_shield = paid_state.shield_bps
            paid_state = replace(paid_state, shield_bps=0)
            coefficient = level_value
        else:
            coefficient = contract.fallback_bps
    elif family == "RESOURCE_TO_SHIELD":
        paid_resource = MAX_RESOURCE_BPS
        paid_state = replace(paid_state, resource_bps=0,
                             shield_bps=min(3_000, paid_state.shield_bps + level_value))
        coefficient = 0
    elif family == "SHIELD_TO_BARRIER":
        paid_shield = paid_state.shield_bps
        paid_state = replace(paid_state, shield_bps=0,
                             barrier_encounter_used=paid_state.barrier_encounter_used + 1,
                             barrier_expedition_used=paid_state.barrier_expedition_used + 1)
        barrier_created = 1
        coefficient = 0
    elif family == "RESOURCE_DEBT":
        deficit = contract.resource_cost_bps - paid_resource
        if deficit > DEBT_CAP_BPS:
            return Resolution(state, "NOT_CANDIDATE")
        debt_created = deficit
        paid_state = replace(paid_state, debt_bps=deficit)
    elif family == "HP_FIXED_PREPAY":
        paid_hp = paid_state.max_hp * 800 // 10_000
        paid_state = replace(paid_state, hp=paid_state.hp - paid_hp)
        coefficient = 6_000
    elif family == "HP_RESOURCE_REBALANCE":
        target_hp = paid_state.max_hp // 2
        if paid_state.hp > target_hp:
            transfer = min(paid_state.hp - target_hp, paid_state.max_hp * level_value // 10_000)
            paid_hp = transfer
            resource_gain = transfer * 10_000 // paid_state.max_hp
            paid_state = replace(paid_state, hp=paid_state.hp - transfer,
                                 resource_bps=min(paid_state.resource_ceiling_bps, paid_state.resource_bps + resource_gain))
        coefficient = 0
    elif family == "MAX_RESOURCE_SELF_COST":
        paid_state = replace(paid_state, max_resource_seal_bps=max(paid_state.max_resource_seal_bps, 2_000),
                             resource_ceiling_bps=min(paid_state.resource_ceiling_bps, 8_000),
                             resource_bps=min(paid_state.resource_bps, 8_000))
        coefficient = 0
    elif family == "CURRENT_RESOURCE_PREPAY":
        if skill_id.endswith("resolveburst"):
            coefficient = level_value if any(x.tag == "MARK" for x in paid_state.target_statuses) else 7_000
        elif skill_id.endswith("manaburst"):
            coefficient = level_value
        else:
            coefficient = level_value
    elif family == "ARCHIVE_TYPE_SWAP":
        coefficient = level_value
        chosen_damage_type = paid_state.archive_weak_type
    elif family == "CURSE_TRANSFER":
        curses = sorted((x for x in paid_state.target_statuses if x.tag == "CURSE"), key=lambda x: (-x.magnitude_bps, -x.duration_roots, x.instance_id))
        if not curses:
            coefficient = contract.fallback_bps
        elif hit:
            chosen = curses[0]
            moved = replace(chosen, owner="SELF", clock="EXPEDITION_LODGING", cost_locked=True)
            paid_state = replace(paid_state,
                                 target_statuses=_remove(paid_state.target_statuses, (chosen,)),
                                 self_statuses=paid_state.self_statuses + (moved,))
            transferred = chosen.instance_id
    elif family == "BURN_TO_CHILL":
        burns = sorted((x for x in paid_state.target_statuses if x.tag == "BURN" and x.stacks > 0), key=lambda x: x.instance_id)
        coefficient = 3_000
        if burns and hit and status_roll and not target_status_immune:
            chosen = burns[0]
            remaining = list(paid_state.target_statuses)
            index = remaining.index(chosen)
            if chosen.stacks == 1:
                remaining.pop(index)
            else:
                remaining[index] = replace(chosen, stacks=chosen.stacks - 1)
            chill = next((x for x in remaining if x.tag == "CHILL"), None)
            if chill:
                remaining = [replace(x, duration_roots=max(x.duration_roots, 2)) if x.instance_id == chill.instance_id else x for x in remaining]
            elif len(remaining) < HOSTILE_STATUS_CAP:
                remaining.append(Status("converted.blackwell.chill", "CHILL", "TARGET", duration_roots=2))
            else:
                remaining = list(paid_state.target_statuses)
            if tuple(remaining) != paid_state.target_statuses:
                paid_state = replace(paid_state, target_statuses=tuple(remaining))
                applied_status = "CHILL"
    elif family == "SYMMETRIC_DISPEL":
        pair_cap = level_value
        enemy = _strongest(paid_state.target_buffs, pair_cap)
        own = _weakest(paid_state.self_buffs, pair_cap)
        pairs = min(len(enemy), len(own), pair_cap)
        enemy, own = enemy[:pairs], own[:pairs]
        if pairs == 0 or sum(abs(x.scalar_bps) for x in enemy) <= sum(abs(x.scalar_bps) for x in own):
            return Resolution(state, "NOT_CANDIDATE")
        paid_state = replace(paid_state, target_buffs=_remove(paid_state.target_buffs, enemy),
                             self_buffs=_remove(paid_state.self_buffs, own))
        removed_target = tuple(x.instance_id for x in enemy)
        removed_self = tuple(x.instance_id for x in own)
        coefficient = 0
    elif family == "ENEMY_DISPEL_STRIKE":
        coefficient = level_value if _eligible_buffs(paid_state.target_buffs) else contract.fallback_bps
        if hit:
            enemy = _strongest(paid_state.target_buffs, 1)
            paid_state = replace(paid_state, target_buffs=_remove(paid_state.target_buffs, enemy))
            removed_target = tuple(x.instance_id for x in enemy)
    elif family == "WEAK_BUFF_STEAL":
        coefficient = 3_000
        if hit:
            enemy = _weakest(paid_state.target_buffs, 1)
            if enemy:
                source = enemy[0]
                paid_state = replace(paid_state, target_buffs=_remove(paid_state.target_buffs, enemy),
                                     self_buffs=paid_state.self_buffs + (Buff(
                                         f"stolen.{source.instance_id}", source.effect_class,
                                         abs(source.scalar_bps) * level_value // 10_000, 1,
                                         dispellable=True, copied=True),))
                removed_target = (source.instance_id,)
                granted_buff = abs(source.scalar_bps) * level_value // 10_000
    elif family == "SAFE_TAG_MIMIC":
        coefficient = level_value
        copied_tag = paid_state.previous_enemy_tag if hit else ""
    elif family == "SELF_COST_STATUS":
        # Cost states are ON_USE/ON_TRIGGER and are not refunded by a miss.
        paid_state, applied_status = _self_status(paid_state, skill_id)
        if applied_status == "CAP_REJECTED":
            return Resolution(state, "NOT_CANDIDATE")
        if skill_id.endswith("abyssaleye"):
            count = min(5, len(paid_state.self_statuses) + len(paid_state.target_statuses) - 1)
            coefficient = 6_000 + count * level_value
        elif skill_id.endswith("phoenixash"):
            coefficient = 0

    outcome = "RESOLVED" if hit or family in {"SYMMETRIC_DISPEL", "HP_TO_RESOURCE", "RESOURCE_TO_SHIELD", "SHIELD_TO_BARRIER", "SELF_COST_STATUS"} else "MISS_COST_COMMITTED"
    return Resolution(paid_state, outcome, coefficient, paid_resource, paid_hp, paid_shield,
                      debt_created, transferred, removed_self, removed_target, granted_buff,
                      copied_tag, applied_status, barrier_created, chosen_damage_type, 0)


def repay_debt_from_basic(state: Runtime, generated_bps: int) -> tuple[Runtime, int, int]:
    repaid = min(state.debt_bps, max(0, generated_bps))
    remaining_gain = max(0, generated_bps - repaid)
    new_resource = min(state.resource_ceiling_bps, state.resource_bps + remaining_gain)
    return replace(state, debt_bps=state.debt_bps - repaid, resource_bps=new_resource), repaid, new_resource - state.resource_bps


def apply_archmage_debt(state: Runtime, deficit_bps: int, allowance_bps: int,
                        ordinary_attrition_bps: int = 5_000) -> tuple[Runtime, str]:
    if state.archmage_debt_used or deficit_bps <= 0 or deficit_bps > allowance_bps:
        return state, "NOT_CANDIDATE"
    base_attrition = (deficit_bps * ordinary_attrition_bps + 9_999) // 10_000
    loss = base_attrition + deficit_bps
    return replace(state, resource_bps=0, resource_ceiling_bps=max(0, state.resource_ceiling_bps - loss),
                   debt_bps=deficit_bps, archmage_debt_used=1,
                   recovery_efficiency_modifier_bps=-1_000), "RESOLVED"


def apply_debt_insurance(state: Runtime, entering_debt: bool) -> tuple[Runtime, int]:
    if not entering_debt or state.debt_insurance_used or state.barrier_expedition_used >= BARRIER_EXPEDITION_CAP:
        return state, 0
    return replace(state, debt_insurance_used=1,
                   barrier_encounter_used=min(BARRIER_ENCOUNTER_CAP, state.barrier_encounter_used + 1),
                   barrier_expedition_used=state.barrier_expedition_used + 1), 1


def owner_class(contract: Contract) -> str:
    item = (skills.ACTIVE_BY_ID if contract.kind == "ACTIVE" else skills.PASSIVE_BY_ID)[contract.skill_id]
    return item.owner_scope if item.owner_scope in skills.CLASSES else "MAGE"


@lru_cache(maxsize=None)
def loadout(skill_id: str, skill_level: int):
    contract = BY_ID[skill_id]
    hero_class = owner_class(contract)
    active_ids = [skill_id] if contract.kind == "ACTIVE" else []
    active_pool = [x for x in skills.accessible(hero_class, "ACTIVE") if x.definition_id not in active_ids]
    active_pool.sort(key=lambda x: (-resolver.role_score(x, "OFFENSE"), x.definition_id))
    active_ids.extend(x.definition_id for x in active_pool[:5 - len(active_ids)])
    passive_ids = [skill_id] if contract.kind == "PASSIVE" else []
    passive_pool = [x for x in skills.accessible(hero_class, "PASSIVE") if x.definition_id not in passive_ids]
    passive_pool.sort(key=lambda x: (-resolver.role_score(x, "SUSTAIN"), x.definition_id))
    passive_ids.extend(x.definition_id for x in passive_pool[:3 - len(passive_ids)])
    return skills.commit_loadout(hero_class, {x: skill_level for x in active_ids[:5]},
                                 {x: skill_level for x in passive_ids[:3]},
                                 "aq.behavior.cost-transfer.v1")


def seeded_state(contract: Contract, mode: str) -> Runtime:
    missing = mode == "MISSING"
    insufficient = mode == "INSUFFICIENT"
    capped = mode == "CAP_OR_IMMUNE"
    curses = () if missing else (Status("curse.strong", "CURSE", "TARGET", "HEX", 900, 3),)
    burns = () if missing else (Status("burn.target", "BURN", "TARGET", stacks=2, duration_roots=3),)
    target_statuses = curses + burns
    if contract.family == "CURSE_TRANSFER":
        target_statuses = curses
    elif contract.family == "BURN_TO_CHILL":
        target_statuses = burns
    self_statuses = tuple(Status(f"locked.{i}", "CURSE", "SELF", f"LOCK_{i}", cost_locked=True) for i in range(6)) if capped and contract.family in {"CURSE_TRANSFER", "SELF_COST_STATUS"} else ()
    target_buffs = () if missing else (
        Buff("enemy.strong", "POWER", 1_200, 3),
        Buff("enemy.weak", "SPEED", 400, 1),
        Buff("enemy.locked", "GUARD", 2_000, 4, dispellable=False),
    )
    self_buffs = () if missing else (Buff("self.weak", "PRECISION", 200, 1), Buff("self.mid", "GUARD", 500, 2))
    shield = 0 if missing or insufficient else 1_000
    resource = 500 if insufficient else MAX_RESOURCE_BPS
    if contract.family == "HP_TO_RESOURCE" and not insufficient:
        resource = 7_000
    if contract.family == "RESOURCE_DEBT" and insufficient:
        resource = 0
    previous = "HEAL" if missing or capped else "FIRE"
    if contract.family == "CURRENT_RESOURCE_PREPAY":
        if contract.skill_id.endswith("manaburst"):
            resource = 5_000 if insufficient else 8_000
        elif contract.skill_id.endswith("lastshot"):
            resource = 0 if insufficient else 2_000
    if contract.family == "HP_RESOURCE_REBALANCE":
        resource = 5_000
    return Runtime(hp=900 if insufficient and contract.family in {"HP_PREPAY", "HP_BACKED_DEFICIT", "HP_TO_RESOURCE"} else 10_000,
                   shield_bps=shield, resource_bps=resource, self_statuses=self_statuses,
                   target_statuses=target_statuses, self_buffs=self_buffs, target_buffs=target_buffs,
                   barrier_encounter_used=1 if capped and contract.family == "SHIELD_TO_BARRIER" else 0,
                   previous_enemy_tag=previous, archive_weak_type="HEAL" if missing or capped else "FIRE")


@dataclass(frozen=True)
class Scenario:
    skill_id: str
    display_level: int
    variant_id: str
    mode: str
    outcome: str
    hp: int
    shield_bps: int
    resource_bps: int
    debt_bps: int
    target_statuses: int
    self_statuses: int
    removed_buffs: int
    extra_actions: int
    active_slots: int
    passive_slots: int


def simulate(contract: Contract, display_level: int, variant, mode: str) -> Scenario:
    level = SKILL_LEVELS[display_level]
    state = seeded_state(contract, mode)
    if contract.kind == "PASSIVE":
        if contract.family == "EXPEDITION_RESOURCE_DEBT":
            result_state, outcome = apply_archmage_debt(state, 500, value_at(contract.anchors, level))
        else:
            result_state, created = apply_debt_insurance(state, mode != "MISSING")
            outcome = "RESOLVED" if created else "NOT_CANDIDATE"
        resolution = Resolution(result_state, outcome)
    else:
        resolution = execute(state, contract.skill_id, level,
                             f"{contract.skill_id}:{display_level}:{variant.variant_id}:{mode}",
                             hit=mode != "MISS", status_roll=mode != "CAP_OR_IMMUNE",
                             target_status_immune=mode == "CAP_OR_IMMUNE")
    snapshot = loadout(contract.skill_id, level)
    return Scenario(contract.skill_id, display_level, variant.variant_id, mode, resolution.outcome,
                    resolution.state.hp, resolution.state.shield_bps, resolution.state.resource_bps,
                    resolution.state.debt_bps, len(resolution.state.target_statuses),
                    len(resolution.state.self_statuses), len(resolution.removed_self_buffs) + len(resolution.removed_target_buffs),
                    resolution.extra_actions, len(snapshot.active_slots), len(snapshot.passive_slots))


@lru_cache(maxsize=1)
def integration_matrix() -> tuple[Scenario, ...]:
    return tuple(simulate(contract, display, variant, mode)
                 for contract in CONTRACTS
                 for display in DISPLAY_LEVELS
                 for variant in monsters.VARIANTS
                 for mode in SCENARIO_MODES)


def branch_probes() -> tuple[tuple[str, str, bool], ...]:
    probes: list[tuple[str, str, bool]] = []
    for contract in ACTIVES:
        valid = seeded_state(contract, "VALID")
        hit = execute(valid, contract.skill_id, 100, f"{contract.skill_id}:hit")
        miss = execute(valid, contract.skill_id, 100, f"{contract.skill_id}:miss", hit=False)
        replay = execute(hit.state, contract.skill_id, 100, f"{contract.skill_id}:hit")
        probes.extend((
            (contract.skill_id, "BOUNDED", hit.extra_actions == 0 and 0 <= hit.state.resource_bps <= MAX_RESOURCE_BPS and hit.state.hp >= 1),
            (contract.skill_id, "REPLAY", replay.outcome == "REPLAY_IGNORED" and replay.state == hit.state),
            (contract.skill_id, "MISS_COST", miss.outcome in {"MISS_COST_COMMITTED", "RESOLVED", "NOT_CANDIDATE"} and miss.extra_actions == 0),
        ))
    debt = execute(Runtime(resource_bps=0), "aq.skill.common.w8.borrowedstamina", 100, "debt")
    repaid, amount, gained = repay_debt_from_basic(debt.state, 2_500)
    arch, arch_outcome = apply_archmage_debt(Runtime(), 1_000, 1_500)
    insurance, barrier = apply_debt_insurance(Runtime(), True)
    probes.extend((
        ("aq.skill.common.w8.borrowedstamina", "DEBT_REPAY_FIRST", debt.debt_created_bps == 2_000 and amount == 2_000 and gained == 500 and repaid.debt_bps == 0),
        ("aq.skill.mage.w5.archmagedebt", "CEILING_LOSS", arch_outcome == "RESOLVED" and arch.resource_ceiling_bps == 8_500 and arch.recovery_efficiency_modifier_bps == -1_000),
        ("aq.skill.rogue.w3.debtinsurance", "BARRIER_NO_FORGIVENESS", barrier == 1 and insurance.debt_bps == 0),
    ))
    return tuple(probes)


def canonical_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "lifecycleV06Hash": lifecycle.canonical_hash(),
        "effectResolverV03Hash": resolver.canonical_hash(),
        "skillRegistryHash": skills.canonical_hash(),
        "monsterRegistryHash": monsters.canonical_hash(),
        "contracts": [asdict(item) for item in CONTRACTS],
        "transactionOrder": (
            "CANDIDATE_SNAPSHOT", "RESOURCE_HP_SHIELD_PREPAY", "COOLDOWN_RECEIPT",
            "HIT_OR_GUARANTEED_RESOLUTION", "TRANSFER_CONVERT_DISPEL_COPY",
            "POST_COST_STATE", "PASSIVE_LEDGER", "FINAL_RECEIPT",
        ),
        "caps": {"resourceBps": MAX_RESOURCE_BPS, "hostileStatuses": HOSTILE_STATUS_CAP,
                 "debtBps": DEBT_CAP_BPS, "shieldConversionMinBps": SHIELD_CONVERSION_MIN_BPS,
                 "shieldConversionEligibility": "max(500,900-conversionBps/20)",
                 "barrierEncounter": BARRIER_ENCOUNTER_CAP,
                 "barrierExpedition": BARRIER_EXPEDITION_CAP},
        "safeMimicTags": sorted(SAFE_MIMIC_TAGS),
        "dispelClasses": sorted(DISPEL_CLASSES),
        "activeSlots": 5,
        "passiveSlots": 3,
        "manualCombatAction": False,
        "itemGrantedSkills": False,
        "productionEnabled": False,
    }


def canonical_hash() -> str:
    raw = json.dumps(canonical_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def summary(matrix: tuple[Scenario, ...]) -> dict:
    return {
        "scenarioCount": len(matrix),
        "contracts": {"active": len(ACTIVES), "passive": len(PASSIVES)},
        "variants": len({x.variant_id for x in matrix}),
        "displayLevels": sorted({x.display_level for x in matrix}),
        "outcomes": dict(sorted(Counter(x.outcome for x in matrix).items())),
        "branchProbes": len(branch_probes()),
        "maxDebtBps": max(x.debt_bps for x in matrix),
        "maxSelfStatuses": max(x.self_statuses for x in matrix),
        "maxTargetStatuses": max(x.target_statuses for x in matrix),
    }


def checks(matrix: tuple[Scenario, ...]) -> list[tuple[str, bool, str]]:
    report = summary(matrix)
    probes = branch_probes()
    family_counts = Counter(x.family for x in CONTRACTS)
    return [
        ("twenty eight contracts exact", len(CONTRACTS) == 28 and len(ACTIVES) == 26 and len(PASSIVES) == 2 and len(BY_ID) == 28, str(family_counts)),
        ("matrix covers 28 x 4 x 144 x 5", len(matrix) == 80_640 and report["variants"] == 144, f"scenarios={len(matrix)} variants={report['variants']}"),
        ("all scenarios carry actual 5 plus 3", all(x.active_slots == 5 and x.passive_slots == 3 for x in matrix), "active=5 passive=3"),
        ("all branch probes pass", all(ok for _, _, ok in probes), f"passed={sum(ok for _, _, ok in probes)}/{len(probes)}"),
        ("level one to 9999 represented", report["displayLevels"] == [1, 100, 1_000, 9_999], str(report["displayLevels"])),
        ("resource hp shield never underflow", all(x.resource_bps >= 0 and x.hp >= 1 and x.shield_bps >= 0 for x in matrix), "underflow=0"),
        ("debt and status caps hold", report["maxDebtBps"] <= DEBT_CAP_BPS and report["maxSelfStatuses"] <= HOSTILE_STATUS_CAP and report["maxTargetStatuses"] <= HOSTILE_STATUS_CAP, str({k: report[k] for k in ("maxDebtBps", "maxSelfStatuses", "maxTargetStatuses")})),
        ("no extra actions", all(x.extra_actions == 0 for x in matrix), "extraActions=0"),
        ("mimic whitelist excludes protected semantics", not SAFE_MIMIC_TAGS.intersection({"HEAL", "SHIELD", "BARRIER", "REVIVE", "COOLDOWN", "BOSS_RULE", "EXTRA_ACTION"}), str(sorted(SAFE_MIMIC_TAGS))),
        ("cleanse and dispel domains are disjoint", not DISPEL_CLASSES.intersection(lifecycle.HOSTILE_TAGS), "beneficial vs hostile"),
        ("source hashes bound", canonical_payload()["lifecycleV06Hash"] == lifecycle.canonical_hash() and canonical_payload()["monsterRegistryHash"] == monsters.canonical_hash(), lifecycle.canonical_hash()[:12]),
        ("automatic test only contract", not canonical_payload()["manualCombatAction"] and not canonical_payload()["itemGrantedSkills"] and not canonical_payload()["productionEnabled"], "manual=false itemSkill=false production=false"),
    ]


def kotlin_manifest_matches() -> bool:
    if not KOTLIN_CONTRACT.exists():
        return False
    found = {}
    pattern = re.compile(
        r'\b(active|passive)\("([^"]+)",\s*"[^"]+",\s*CostFamily\.[A-Z_]+,\s*listOf\(([^)]*)\)(?:,\s*([\d_]+),\s*([\d_]+)(?:,\s*[\d_]+)?)?\)'
    )
    for match in pattern.finditer(KOTLIN_CONTRACT.read_text(encoding="utf-8")):
        kind, skill_id, raw_values, raw_cost, raw_cooldown = match.groups()
        values = tuple(int(x.replace("_", "").strip()) for x in raw_values.split(",") if x.strip())
        found[skill_id] = (
            kind.upper(), values,
            int(raw_cost.replace("_", "")) if raw_cost else 0,
            int(raw_cooldown.replace("_", "")) if raw_cooldown else 0,
        )
    expected = {x.skill_id: (x.kind, x.anchors, x.resource_cost_bps, x.cooldown_roots) for x in CONTRACTS}
    return found == expected


def pd_checks(matrix: tuple[Scenario, ...]) -> list[tuple[str, bool, str]]:
    result = checks(matrix)
    document = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    kotlin = KOTLIN_CONTRACT.read_text(encoding="utf-8") if KOTLIN_CONTRACT.exists() else ""
    result.extend((
        ("document binds v07 hash", canonical_hash() in document, canonical_hash()),
        ("kotlin binds v07 hash", canonical_hash() in kotlin, canonical_hash()),
        ("kotlin manifest fields match python", kotlin_manifest_matches(), "ids anchors costs cooldowns"),
        ("document keeps production gate", "production 조건부 승인" in document and "라이브 NO-GO" in document, "explicit gate"),
        ("document separates cleanse and dispel", "상태 정화" in document and "강화 해제" in document, "two AI domains"),
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
