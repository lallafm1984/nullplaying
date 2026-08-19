#!/usr/bin/env python3
"""Atomic status-consumption/detonation audit for AlarmQuest v0.5.

This is a planning and test executable.  It binds the twelve remaining
consumer definitions to explicit reservation, commit, rollback, fallback,
multi-packet, passive-trigger, replay, and monster-immunity rules.  It never
reads or mutates live characters, saves, Room data, or production assets.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, replace
from functools import lru_cache
from pathlib import Path

import monster_prefix_variants_v0_2_review as monsters
import skill_effect_resolver_v0_3_review as resolver
import skill_prepared_reaction_v0_4_review as prepared
import skill_registry_240_v0_2_review as skills


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_STATUS_CONSUMPTION_HANDLERS_v0.5.md"
KOTLIN_CONTRACT = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusConsumptionHandlers.kt"
RULES_VERSION = "aq.skill-status-consumption.v0.5"
DISPLAY_LEVELS = resolver.DISPLAY_LEVELS
SKILL_LEVELS = resolver.SKILL_LEVELS
SCENARIO_MODES = ("FULL_HIT", "FULL_MISS", "EMPTY_OR_IMMUNE", "CLEANSED_BEFORE", "TARGET_DIES_MID_PACKET")
HOSTILE_STATUS_CAP = 6
SHOCK_STACK_CAP = 2
CURSE_SUBTYPE_CAP = 3
NEW_STATUS_PER_ACTION_CAP = 2
RESOURCE_MAX_BPS = 10_000
RESOURCE_GENERATION_LEDGER_CAP_BPS = 2_400
ELEMENTAL_RESIDUE_TOKEN_CAP = 1
NO_EXTRA_ACTIONS = 0
ELEMENTAL_ATTACK_TAGS = frozenset({"FIRE", "ICE", "LIGHTNING", "HOLY", "ARCANE"})


@dataclass(frozen=True)
class ConsumerContract:
    skill_id: str
    name_ko: str
    kind: str
    handler_id: str
    owner: str
    required: tuple[str, ...]
    consume_mode: str
    values: tuple[int, int, int, int, int]
    fallback_bps: int
    base_bps: int
    packets: int
    resource_cost_bps: int
    cooldown_roots: int
    hit_policy: str
    crit_eligible: bool
    element: str
    condition_policy: str
    post_use: str = "NONE"
    ledger_id: str = "NONE"
    ledger_cap_bps: int = 0


def active(skill_id, name, handler, owner, required, consume_mode, values, fallback, base,
           packets, cost, cooldown, hit="NORMAL", crit=True, element="NONE",
           condition="FALLBACK", post_use="NONE"):
    return ConsumerContract(skill_id, name, "ACTIVE", handler, owner, tuple(required), consume_mode,
                            tuple(values), fallback, base, packets, cost, cooldown, hit, crit,
                            element, condition, post_use)


def passive(skill_id, name, handler, values, ledger, cap):
    return ConsumerContract(skill_id, name, "PASSIVE", handler, "SELF", (), "TRIGGER_ONLY",
                            tuple(values), 0, 0, 0, 0, 0, "NONE", False, "NONE",
                            "HOST_CONSUMPTION", "NONE", ledger, cap)


CONTRACTS = (
    active("aq.skill.cleric.w2.sacredchain", "신성 사슬", "DETONATOR_BLESSING", "SELF", ("BLESSING",), "ONE", (14_000, 14_500, 15_000, 15_500, 16_000), 7_000, 0, 1, 1_800, 4, "FIXED_9500", True, "HOLY"),
    active("aq.skill.external.w2.dragonscale", "용의 역린", "DETONATOR_BURN", "TARGET", ("BURN",), "ONE", (13_000, 13_425, 13_850, 14_275, 14_700), 6_000, 0, 1, 3_000, 4, element="FIRE", post_use="SELF_BURN_ONE_UNRESISTABLE"),
    active("aq.skill.mage.w4.chainlightning", "연쇄 번개", "SHOCK_STACK_CONSUMER", "TARGET", ("SHOCK",), "ALL_STACKS_UP_TO_TWO", (1_400, 1_625, 1_850, 2_075, 2_300), 8_000, 8_000, 1, 1_600, 3, element="LIGHTNING"),
    active("aq.skill.mage.w4.unseal", "봉인 해제", "CURSE_DIVERSITY_CONSUMER", "TARGET", ("CURSE_3_DISTINCT",), "THREE_DISTINCT_SUBTYPES", (11_000, 11_500, 12_000, 12_500, 13_000), 0, 0, 1, 2_500, 3, "FIXED_9500", False, "ARCANE", "NOT_CANDIDATE"),
    active("aq.skill.paladin.w2.resolveburst", "결의 폭발", "DETONATOR_MARK", "TARGET", ("MARK",), "ONE", (12_000, 12_500, 13_000, 13_500, 14_000), 7_000, 0, 1, 10_000, 2, element="HOLY"),
    active("aq.skill.ranger.w2.rapidfire", "속사", "DETONATOR_POISON", "TARGET", ("POISON",), "ONE", (14_000, 14_500, 15_000, 15_500, 16_000), 9_000, 0, 3, 1_800, 4, element="PHYSICAL"),
    active("aq.skill.ranger.w4.stormpierce", "폭풍 꿰기", "CONSUME_SHOCK", "TARGET", ("SHOCK",), "ONE_STACK", (11_000, 11_500, 12_000, 12_500, 13_000), 7_000, 0, 1, 2_500, 3, element="LIGHTNING"),
    active("aq.skill.rogue.w2.twinknifeflurry", "쌍단검 난무", "DETONATOR_POISON", "TARGET", ("POISON",), "ONE", (16_000, 16_500, 17_000, 17_500, 18_000), 8_000, 0, 4, 2_500, 4, element="PHYSICAL"),
    active("aq.skill.warrior.w4.bloodwhirl", "피의 회전", "CONSUME_BLEED", "TARGET", ("BLEED",), "ALL_INSTANCES", (12_000, 12_500, 13_000, 13_500, 14_000), 7_000, 0, 1, 2_500, 3, element="PHYSICAL"),
    active("aq.skill.world.w8.glassdesertheat", "유리 사막의 열풍", "BURN_CHILL_DETONATION", "TARGET", ("BURN", "CHILL"), "ONE_EACH_ATOMIC", (8_000, 8_500, 9_000, 9_500, 10_000), 3_000, 0, 1, 2_000, 4, crit=False, element="FIRE"),
    passive("aq.skill.common.w8.elementalresidue", "원소 잔재", "CONSUME_TO_DIFFERENT_ELEMENT", (100, 200, 300, 400, 500), "aq.stack.passive.action_add", 1_500),
    passive("aq.skill.world.w8.wellecho", "우물의 메아리", "CHILL_CONSUME_RESOURCE", (200, 350, 500, 650, 800), "aq.generation.resource.standard", RESOURCE_GENERATION_LEDGER_CAP_BPS),
)
CONTRACT_BY_ID = {item.skill_id: item for item in CONTRACTS}
ACTIVES = tuple(item for item in CONTRACTS if item.kind == "ACTIVE")
PASSIVES = tuple(item for item in CONTRACTS if item.kind == "PASSIVE")
BASE_BY_ID = {item.enemy_id: item for item in monsters.BASE_ENEMIES}


@dataclass(frozen=True)
class Status:
    instance_id: str
    tag: str
    owner: str
    subtype: str = ""
    stacks: int = 1


@dataclass(frozen=True)
class Runtime:
    statuses: tuple[Status, ...]
    resource_bps: int = RESOURCE_MAX_BPS
    residue_element: str = ""
    receipts: frozenset[str] = frozenset()


@dataclass(frozen=True)
class Resolution:
    state: Runtime
    outcome: str
    coefficient_bps: int
    packet_coefficients: tuple[int, ...]
    packets_resolved: int
    any_hit: bool
    consumed: tuple[str, ...]
    rollback: bool
    fallback: bool
    resource_spent_bps: int
    resource_recovered_bps: int
    residue_add_bps: int
    self_burn_applied: bool
    extra_actions: int
    receipt: str


def value_at(values, level):
    return skills.value_at(tuple(values), level)


def split_exact(total: int, packets: int) -> tuple[int, ...]:
    if packets <= 0:
        return ()
    quotient, remainder = divmod(total, packets)
    return tuple(quotient + (1 if index < remainder else 0) for index in range(packets))


def status_element(tag: str) -> str:
    return {"BURN": "FIRE", "CHILL": "ICE", "SHOCK": "LIGHTNING"}.get(tag, "NONE")


def select_reservation(contract: ConsumerContract, statuses: tuple[Status, ...]) -> tuple[Status, ...]:
    pool = [item for item in statuses if item.owner == contract.owner]
    if contract.consume_mode == "THREE_DISTINCT_SUBTYPES":
        curses = sorted((item for item in pool if item.tag == "CURSE"), key=lambda item: (item.subtype, item.instance_id))
        distinct = {}
        for item in curses:
            distinct.setdefault(item.subtype, item)
        return tuple(distinct[key] for key in sorted(distinct)[:3]) if len(distinct) >= 3 else ()
    if contract.consume_mode == "ONE_EACH_ATOMIC":
        chosen = []
        for tag in contract.required:
            item = next((candidate for candidate in pool if candidate.tag == tag and candidate.stacks > 0), None)
            if item is None:
                return ()
            chosen.append(replace(item, stacks=1))
        return tuple(chosen)
    tag = contract.required[0]
    candidates = sorted((item for item in pool if item.tag == tag and item.stacks > 0), key=lambda item: item.instance_id)
    if not candidates:
        return ()
    if contract.consume_mode == "ALL_INSTANCES":
        return tuple(candidates)
    if contract.consume_mode == "ALL_STACKS_UP_TO_TWO":
        return (replace(candidates[0], stacks=min(SHOCK_STACK_CAP, candidates[0].stacks)),)
    if contract.consume_mode == "ONE_STACK":
        return (replace(candidates[0], stacks=1),)
    return (replace(candidates[0], stacks=candidates[0].stacks),)


def remove_reserved(statuses: tuple[Status, ...], reserved: tuple[Status, ...]) -> tuple[Status, ...]:
    remaining = list(statuses)
    for token in reserved:
        index = next((i for i, item in enumerate(remaining) if item.instance_id == token.instance_id), None)
        if index is None:
            raise AssertionError("reserved status disappeared inside an atomic action")
        current = remaining[index]
        if token.stacks >= current.stacks:
            remaining.pop(index)
        else:
            remaining[index] = replace(current, stacks=current.stacks - token.stacks)
    return tuple(remaining)


def apply_self_burn(statuses: tuple[Status, ...]) -> tuple[tuple[Status, ...], bool]:
    existing = next((item for item in statuses if item.owner == "SELF" and item.tag == "BURN"), None)
    if existing:
        return tuple(replace(item, stacks=1) if item.instance_id == existing.instance_id else item for item in statuses), True
    self_hostile = sum(item.owner == "SELF" and item.tag != "BLESSING" for item in statuses)
    if self_hostile >= HOSTILE_STATUS_CAP:
        return statuses, False
    return statuses + (Status("post.self.burn", "BURN", "SELF"),), True


def coefficient(contract: ConsumerContract, skill_level: int, reserved: tuple[Status, ...]) -> tuple[int, bool]:
    if not reserved:
        return contract.fallback_bps, True
    if contract.handler_id == "SHOCK_STACK_CONSUMER":
        stacks = sum(item.stacks for item in reserved)
        return contract.base_bps + value_at(contract.values, skill_level) * stacks, False
    return value_at(contract.values, skill_level), False


def execute(state: Runtime, skill_id: str, skill_level: int, hit_results: tuple[bool, ...],
            receipt: str, target_dies_after_packet: int = 0,
            cleansed_before: bool = False, barrier_blocks_all: bool = False,
            elemental_residue: bool = True, well_echo: bool = True) -> Resolution:
    contract = CONTRACT_BY_ID[skill_id]
    assert contract.kind == "ACTIVE"
    if receipt in state.receipts:
        return Resolution(state, "REPLAY_IGNORED", 0, (), 0, False, (), False, False, 0, 0, 0, False, 0, receipt)
    statuses = state.statuses
    if cleansed_before:
        statuses = tuple(item for item in statuses if not (item.owner == contract.owner and (
            item.tag in contract.required or "CURSE_3_DISTINCT" in contract.required and item.tag == "CURSE")))
    candidate_state = replace(state, statuses=statuses)
    reserved = select_reservation(contract, statuses)
    condition_met = bool(reserved)
    if contract.condition_policy == "NOT_CANDIDATE" and not condition_met:
        return Resolution(candidate_state, "NOT_CANDIDATE", 0, (), 0, False, (), False, False, 0, 0, 0, False, 0, receipt)
    if state.resource_bps < contract.resource_cost_bps:
        return Resolution(candidate_state, "RESOURCE_REJECTED", 0, (), 0, False, (), False, False, 0, 0, 0, False, 0, receipt)

    paid = replace(candidate_state, resource_bps=state.resource_bps - contract.resource_cost_bps,
                   receipts=state.receipts | {receipt})
    post_statuses = paid.statuses
    self_burn = False
    if contract.post_use == "SELF_BURN_ONE_UNRESISTABLE":
        post_statuses, self_burn = apply_self_burn(post_statuses)
        paid = replace(paid, statuses=post_statuses)

    total, fallback = coefficient(contract, skill_level, reserved)
    packets = split_exact(total, contract.packets)
    results = tuple(hit_results[index] if index < len(hit_results) else False for index in range(contract.packets))
    resolved_count = min(contract.packets, target_dies_after_packet) if target_dies_after_packet > 0 else contract.packets
    any_hit = any(results[:resolved_count])
    consumed = ()
    rollback = bool(reserved) and not any_hit
    after = paid
    recovered = 0
    residue_add = 0

    # A previously armed residue token modifies this action before a new token is created.
    if (elemental_residue and paid.residue_element
            and contract.element in ELEMENTAL_ATTACK_TAGS
            and contract.element != paid.residue_element):
        residue_add = value_at(CONTRACT_BY_ID["aq.skill.common.w8.elementalresidue"].values, skill_level)
        after = replace(after, residue_element="")

    if reserved and any_hit:
        after = replace(after, statuses=remove_reserved(after.statuses, reserved))
        consumed = tuple(item.tag for item in reserved for _ in range(item.stacks))
        hostile_consumed = [item for item in reserved if item.owner == "TARGET"]
        if elemental_residue and hostile_consumed:
            after = replace(after, residue_element=status_element(hostile_consumed[0].tag))
        chill_count = sum(item.tag == "CHILL" and item.owner == "TARGET" for item in reserved)
        if well_echo and chill_count:
            recovered = min(
                value_at(CONTRACT_BY_ID["aq.skill.world.w8.wellecho"].values, skill_level),
                RESOURCE_GENERATION_LEDGER_CAP_BPS,
                RESOURCE_MAX_BPS - after.resource_bps,
            )
            after = replace(after, resource_bps=after.resource_bps + recovered)

    # Barrier changes damage, never hit success or committed status consumption.
    _ = barrier_blocks_all
    outcome = "COMMITTED" if any_hit else "MISS_ROLLBACK" if reserved else "FALLBACK_MISS"
    return Resolution(after, outcome, total, packets, resolved_count, any_hit, consumed, rollback,
                      fallback, contract.resource_cost_bps, recovered, residue_add, self_burn,
                      NO_EXTRA_ACTIONS, receipt)


def seed_statuses(contract: ConsumerContract, immune: set[str], empty: bool = False) -> tuple[Status, ...]:
    if empty:
        return ()
    if contract.owner == "SELF":
        return (Status("self.blessing", "BLESSING", "SELF"),)
    if contract.consume_mode == "THREE_DISTINCT_SUBTYPES":
        if "CURSE" in immune:
            return ()
        return tuple(Status(f"target.curse.{index}", "CURSE", "TARGET", f"TYPE_{index}") for index in range(1, 4))
    result = []
    for tag in contract.required:
        if tag in immune:
            continue
        stacks = 2 if tag == "SHOCK" and contract.consume_mode == "ALL_STACKS_UP_TO_TWO" else 1
        result.append(Status(f"target.{tag.lower()}", tag, "TARGET", stacks=stacks))
    return tuple(result)


def owner_class(contract: ConsumerContract) -> str:
    item = (skills.ACTIVE_BY_ID if contract.kind == "ACTIVE" else skills.PASSIVE_BY_ID)[contract.skill_id]
    return item.owner_scope if item.owner_scope != "ALL" else "MAGE"


@lru_cache(maxsize=None)
def loadout(skill_id: str, skill_level: int):
    contract = CONTRACT_BY_ID[skill_id]
    hero_class = owner_class(contract)
    active_ids = [skill_id] if contract.kind == "ACTIVE" else []
    active_pool = [item for item in skills.accessible(hero_class, "ACTIVE") if item.definition_id not in active_ids]
    active_pool.sort(key=lambda item: (-resolver.role_score(item, "OFFENSE"), item.definition_id))
    active_ids.extend(item.definition_id for item in active_pool[:5 - len(active_ids)])
    passive_ids = [item.skill_id for item in PASSIVES if item.skill_id != skill_id and skills.PASSIVE_BY_ID[item.skill_id].owner_scope in {"ALL", hero_class}]
    if contract.kind == "PASSIVE":
        passive_ids.insert(0, skill_id)
    passive_pool = [item for item in skills.accessible(hero_class, "PASSIVE") if item.definition_id not in passive_ids]
    passive_pool.sort(key=lambda item: (-resolver.role_score(item, "SUSTAIN"), item.definition_id))
    passive_ids.extend(item.definition_id for item in passive_pool[:3 - len(passive_ids)])
    return skills.commit_loadout(hero_class, {item: skill_level for item in active_ids[:5]},
                                 {item: skill_level for item in passive_ids[:3]},
                                 "aq.behavior.status-consumer.v1")


@dataclass(frozen=True)
class Scenario:
    skill_id: str
    display_level: int
    skill_level: int
    variant_id: str
    mode: str
    condition_met: bool
    outcome: str
    coefficient_bps: int
    consumed_count: int
    rollback: bool
    fallback: bool
    resource_recovered_bps: int
    residue_add_bps: int
    extra_actions: int
    active_slots: int
    passive_slots: int


def simulate(contract: ConsumerContract, display_level: int, variant, mode: str) -> Scenario:
    level = SKILL_LEVELS[display_level]
    # Glass Desert Heat is the deterministic passive host because one successful
    # transaction consumes CHILL and another elemental status without adding a
    # new action.  It therefore exercises both passive contracts together.
    host = CONTRACT_BY_ID["aq.skill.world.w8.glassdesertheat"] if contract.kind == "PASSIVE" else contract
    base = BASE_BY_ID[variant.base_enemy_id]
    immune = set(base.status_immunities)
    statuses = seed_statuses(host, immune, empty=mode == "EMPTY_OR_IMMUNE")
    condition_met = bool(select_reservation(host, statuses))
    hits = tuple(False for _ in range(host.packets)) if mode == "FULL_MISS" else tuple(True for _ in range(host.packets))
    runtime = Runtime(
        statuses,
        resource_bps=5_000 if contract.kind == "PASSIVE" else RESOURCE_MAX_BPS,
        residue_element="ICE" if contract.skill_id == "aq.skill.common.w8.elementalresidue" else "",
    )
    result = execute(runtime, host.skill_id, level, hits,
                     f"{contract.skill_id}:{display_level}:{variant.variant_id}:{mode}",
                     target_dies_after_packet=1 if mode == "TARGET_DIES_MID_PACKET" else 0,
                     cleansed_before=mode == "CLEANSED_BEFORE",
                     elemental_residue=contract.skill_id == "aq.skill.common.w8.elementalresidue",
                     well_echo=contract.skill_id == "aq.skill.world.w8.wellecho")
    snapshot = loadout(contract.skill_id, level)
    return Scenario(contract.skill_id, display_level, level, variant.variant_id, mode,
                    condition_met, result.outcome, result.coefficient_bps, len(result.consumed),
                    result.rollback, result.fallback, result.resource_recovered_bps,
                    result.residue_add_bps, result.extra_actions,
                    len(snapshot.active_slots), len(snapshot.passive_slots))


@lru_cache(maxsize=1)
def integration_matrix() -> tuple[Scenario, ...]:
    return tuple(simulate(contract, display, variant, mode)
                 for contract in CONTRACTS
                 for display in DISPLAY_LEVELS
                 for variant in monsters.VARIANTS
                 for mode in SCENARIO_MODES)


def branch_probes() -> tuple[tuple[str, str, bool], ...]:
    probes = []
    for contract in ACTIVES:
        statuses = seed_statuses(contract, set())
        hit = execute(Runtime(statuses), contract.skill_id, 100, (True,) * contract.packets, f"{contract.skill_id}:hit")
        miss = execute(Runtime(statuses), contract.skill_id, 100, (False,) * contract.packets, f"{contract.skill_id}:miss")
        barrier = execute(Runtime(statuses), contract.skill_id, 100, (True,) * contract.packets, f"{contract.skill_id}:barrier", barrier_blocks_all=True)
        replay = execute(hit.state, contract.skill_id, 100, (True,) * contract.packets, f"{contract.skill_id}:hit")
        probes.extend((
            (contract.skill_id, "HIT_COMMITS_ONCE", (not statuses or bool(hit.consumed)) and not hit.rollback),
            (contract.skill_id, "MISS_ROLLS_BACK", not statuses or miss.rollback or miss.outcome == "NOT_CANDIDATE"),
            (contract.skill_id, "BARRIER_IS_STILL_HIT", barrier.any_hit and (not statuses or bool(barrier.consumed))),
            (contract.skill_id, "REPLAY_IDEMPOTENT", replay.outcome == "REPLAY_IGNORED" and replay.state == hit.state),
        ))
    # Passive-specific probes use a CHILL consumption host so both can be observed.
    glass = CONTRACT_BY_ID["aq.skill.world.w8.glassdesertheat"]
    board = Runtime(seed_statuses(glass, set()), resource_bps=5_000, residue_element="ICE")
    combined = execute(board, glass.skill_id, 100, (True,), "passive:combined")
    probes.extend((
        ("aq.skill.common.w8.elementalresidue", "DIFFERENT_ELEMENT_ONCE", combined.residue_add_bps == 500 and combined.state.residue_element == "FIRE"),
        ("aq.skill.common.w8.elementalresidue", "SAME_ELEMENT_ZERO", execute(replace(board, residue_element="FIRE"), glass.skill_id, 100, (True,), "passive:same").residue_add_bps == 0),
        ("aq.skill.common.w8.elementalresidue", "TOKEN_CAP_ONE", bool(combined.state.residue_element)),
        ("aq.skill.common.w8.elementalresidue", "NO_EXTRA_ACTION", combined.extra_actions == 0),
        ("aq.skill.world.w8.wellecho", "CHILL_COMMIT_RECOVERS", combined.resource_recovered_bps == 800),
        ("aq.skill.world.w8.wellecho", "MISS_RECOVERS_ZERO", execute(board, glass.skill_id, 100, (False,), "passive:miss").resource_recovered_bps == 0),
        ("aq.skill.world.w8.wellecho", "CEILING_RESPECTED", combined.state.resource_bps <= RESOURCE_MAX_BPS),
        ("aq.skill.world.w8.wellecho", "ROOT_LEDGER_CAP", combined.resource_recovered_bps <= RESOURCE_GENERATION_LEDGER_CAP_BPS),
    ))
    return tuple(probes)


def canonical_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "preparedV04Hash": prepared.canonical_hash(),
        "effectResolverV03Hash": resolver.canonical_hash(),
        "skillRegistryHash": skills.canonical_hash(),
        "monsterRegistryHash": monsters.canonical_hash(),
        "contracts": [asdict(item) for item in CONTRACTS],
        "transactionOrder": ("CANDIDATE_SNAPSHOT", "COST_COOLDOWN_COMMIT", "STATUS_RESERVE", "PACKETS", "CONSUME_COMMIT_OR_ROLLBACK", "PASSIVE_TRIGGER", "RECEIPT"),
        "caps": {"hostileStatuses": HOSTILE_STATUS_CAP, "shockStacks": SHOCK_STACK_CAP,
                 "curseSubtypes": CURSE_SUBTYPE_CAP, "newStatusesPerAction": NEW_STATUS_PER_ACTION_CAP,
                 "elementalResidueTokens": ELEMENTAL_RESIDUE_TOKEN_CAP,
                 "resourceGenerationLedgerBps": RESOURCE_GENERATION_LEDGER_CAP_BPS},
        "barrierCountsAsHit": True,
        "allMissRollsBack": True,
        "elementalAttackTags": sorted(ELEMENTAL_ATTACK_TAGS),
        "manualCombatAction": False,
        "itemGrantedSkills": False,
        "productionEnabled": False,
    }


def canonical_hash() -> str:
    raw = json.dumps(canonical_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def summary(matrix):
    outcomes = Counter(row.outcome for row in matrix)
    return {
        "scenarioCount": len(matrix),
        "branchProbeCount": len(branch_probes()),
        "contracts": {"active": len(ACTIVES), "passive": len(PASSIVES)},
        "variants": len({row.variant_id for row in matrix}),
        "displayLevels": sorted({row.display_level for row in matrix}),
        "outcomes": dict(sorted(outcomes.items())),
        "committedConsumptionScenarios": sum(row.consumed_count > 0 for row in matrix),
        "rollbackScenarios": sum(row.rollback for row in matrix),
        "fallbackScenarios": sum(row.fallback for row in matrix),
        "maxCoefficientBps": max(row.coefficient_bps for row in matrix),
        "maxResourceRecoveryBps": max(row.resource_recovered_bps for row in matrix),
        "maxResidueAddBps": max(row.residue_add_bps for row in matrix),
    }


def checks(matrix):
    probes = branch_probes()
    report = summary(matrix)
    by_skill = defaultdict(list)
    for row in matrix:
        by_skill[row.skill_id].append(row)
    glass = CONTRACT_BY_ID["aq.skill.world.w8.glassdesertheat"]
    chain = CONTRACT_BY_ID["aq.skill.mage.w4.chainlightning"]
    expected_ids = {
        item.skill_id for item in resolver.COVERAGE
        if set(item.dedicated_tokens).intersection({"CONSUME", "DETONAT"})
    }
    return [
        ("twelve contracts exact", set(CONTRACT_BY_ID) == expected_ids and len(ACTIVES) == 10 and len(PASSIVES) == 2, f"active={len(ACTIVES)} passive={len(PASSIVES)}"),
        ("matrix covers 12 x 4 x 144 x 5", len(matrix) == 34_560 and report["variants"] == 144, f"scenarios={len(matrix)} variants={report['variants']}"),
        ("all scenarios carry actual 5 plus 3 loadout", all(row.active_slots == 5 and row.passive_slots == 3 for row in matrix), "5 active + 3 passive"),
        ("all forty eight branch probes pass", len(probes) == 48 and all(ok for _, _, ok in probes), f"passed={sum(ok for _, _, ok in probes)}/{len(probes)}"),
        ("miss never consumes and reserved status rolls back", all(row.consumed_count == 0 for row in matrix if row.mode == "FULL_MISS") and any(row.rollback for row in matrix if row.mode == "FULL_MISS"), f"rollbacks={report['rollbackScenarios']}"),
        ("cleanse before action cannot consume stale status", all(row.consumed_count == 0 for row in matrix if row.mode == "CLEANSED_BEFORE"), "staleConsumption=0"),
        ("mid packet death commits at most once", all(row.consumed_count <= 3 and row.extra_actions == 0 for row in matrix if row.mode == "TARGET_DIES_MID_PACKET"), "duplicateConsumption=0"),
        ("multi packet totals are exact", all(sum(split_exact(value_at(item.values, 100), item.packets)) == value_at(item.values, 100) for item in ACTIVES if item.packets > 1), "rapid=3 twin=4"),
        ("chain lightning consumes at most two shock", chain.consume_mode == "ALL_STACKS_UP_TO_TWO" and max(row.consumed_count for row in by_skill[chain.skill_id]) <= 2, "shockCap=2"),
        ("glass heat is atomic and critical forbidden", glass.consume_mode == "ONE_EACH_ATOMIC" and not glass.crit_eligible and all(row.consumed_count in {0, 2} for row in by_skill[glass.skill_id]), "consume=0-or-2 crit=false"),
        ("unseal remains unavailable below three distinct curses", CONTRACT_BY_ID["aq.skill.mage.w4.unseal"].condition_policy == "NOT_CANDIDATE" and any(row.outcome == "NOT_CANDIDATE" for row in by_skill["aq.skill.mage.w4.unseal"]), "candidate gate"),
        ("resource and passive caps hold", report["maxResourceRecoveryBps"] <= 800 <= RESOURCE_GENERATION_LEDGER_CAP_BPS and report["maxResidueAddBps"] <= 500, f"recovery={report['maxResourceRecoveryBps']} residue={report['maxResidueAddBps']}"),
        ("source hashes bound", canonical_payload()["preparedV04Hash"] == prepared.canonical_hash() and canonical_payload()["monsterRegistryHash"] == monsters.canonical_hash(), prepared.canonical_hash()[:12]),
        ("automatic test only contract", not canonical_payload()["manualCombatAction"] and not canonical_payload()["itemGrantedSkills"] and not canonical_payload()["productionEnabled"], "manual=false itemSkill=false production=false"),
    ]


def pd_checks(matrix):
    result = checks(matrix)
    document = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    result.extend((
        ("document binds v05 hash", canonical_hash() in document, canonical_hash()),
        ("kotlin contract binds v05 hash", KOTLIN_CONTRACT.exists() and canonical_hash() in KOTLIN_CONTRACT.read_text(encoding="utf-8"), canonical_hash()),
        ("document keeps conditional production gate", "production 조건부 승인" in document and "라이브 NO-GO" in document, "explicit gate"),
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
