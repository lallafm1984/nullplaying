#!/usr/bin/env python3
"""Planning reference audit for AlarmQuest class resources and auto roles v0.1.

This model is independent from the live Kotlin resolver. It validates the proposed
resource lifecycles, stable automation ordering, and role-budget invariants only.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field, replace


ENCOUNTER_BUILDER = "ENCOUNTER_BUILDER"
EXPEDITION_POOL = "EXPEDITION_POOL"
BANDS = {
    "SURVIVAL": 500,
    "RECOVERY": 400,
    "CONTROL": 300,
    "POWER": 200,
    "BUILD": 100,
    "BASIC": 0,
}


@dataclass(frozen=True)
class ResourceDefinition:
    class_id: str
    resource_id: str
    lifecycle: str
    start_bps: int
    spend_min_bps: int
    spend_max_bps: int
    basic_gain_bps: int = 0
    hp_damage_gain_bps: int = 0
    per_round_gain_cap_bps: int = 0
    recovery_action_gain_bps: int = 0
    pool_spend_attrition_bps: int = 0


DEFINITIONS = (
    ResourceDefinition("aq.class.warrior", "aq.resource.warrior.vigor", ENCOUNTER_BUILDER, 0, 3000, 6000, 2000, 1000, 3000),
    ResourceDefinition("aq.class.rogue", "aq.resource.rogue.edge", ENCOUNTER_BUILDER, 0, 2500, 5000, 2500),
    ResourceDefinition("aq.class.ranger", "aq.resource.ranger.focus", EXPEDITION_POOL, 10000, 1500, 4000, 1200, pool_spend_attrition_bps=5000),
    ResourceDefinition("aq.class.mage", "aq.resource.mage.mana", EXPEDITION_POOL, 10000, 1000, 3000, recovery_action_gain_bps=1500, pool_spend_attrition_bps=5000),
    ResourceDefinition("aq.class.cleric", "aq.resource.cleric.faith", EXPEDITION_POOL, 10000, 1200, 2800, 800, pool_spend_attrition_bps=5000),
    ResourceDefinition("aq.class.paladin", "aq.resource.paladin.conviction", ENCOUNTER_BUILDER, 2000, 2000, 5000, 1200, 800, 2000),
)


@dataclass
class ResourceState:
    current: int
    maximum: int
    encounter_start: int
    recovery_ceiling: int
    generated: int = 0
    spent: int = 0
    generated_this_round: int = 0
    consecutive_recovery_actions: int = 0
    recovery_actions_this_encounter: int = 0


def amount_from_bps(maximum: int, bps: int) -> int:
    assert maximum >= 0 and 0 <= bps <= 10000
    if maximum == 0 or bps == 0:
        return 0
    return max(1, (maximum * bps + 9999) // 10000)


def begin_encounter(definition: ResourceDefinition, maximum: int, persisted: int | None = None) -> ResourceState:
    assert maximum >= 1
    if definition.lifecycle == ENCOUNTER_BUILDER:
        current = amount_from_bps(maximum, definition.start_bps)
    else:
        current = maximum if persisted is None else max(0, min(maximum, persisted))
    return ResourceState(current, maximum, current, current if definition.lifecycle == EXPEDITION_POOL else maximum)


def grow_persistent_ratio(current: int, old_maximum: int, new_maximum: int) -> int:
    assert old_maximum >= 1 and new_maximum >= 1 and 0 <= current <= old_maximum
    return (new_maximum * current) // old_maximum


def bps_of_amount(amount: int, bps: int) -> int:
    assert amount >= 0 and 0 <= bps <= 10000
    if amount == 0 or bps == 0:
        return 0
    return max(1, (amount * bps + 9999) // 10000)


def spend(
    definition: ResourceDefinition,
    state: ResourceState,
    amount: int,
    *,
    attrition_bps: int | None = None,
) -> bool:
    assert amount >= 0
    if amount > state.current:
        return False
    state.current -= amount
    state.spent += amount
    if definition.lifecycle == EXPEDITION_POOL:
        resolved_attrition_bps = (
            definition.pool_spend_attrition_bps if attrition_bps is None else attrition_bps
        )
        attrition = bps_of_amount(amount, resolved_attrition_bps)
        state.recovery_ceiling = max(state.current, state.recovery_ceiling - attrition)
    return True


def generate(
    definition: ResourceDefinition,
    state: ResourceState,
    amount: int,
    *,
    enforce_round_cap: bool = False,
) -> int:
    assert amount >= 0
    allowed = amount
    if enforce_round_cap and definition.per_round_gain_cap_bps > 0:
        round_cap = amount_from_bps(state.maximum, definition.per_round_gain_cap_bps)
        allowed = min(allowed, max(0, round_cap - state.generated_this_round))
    ceiling = state.recovery_ceiling if definition.lifecycle == EXPEDITION_POOL else state.maximum
    applied = min(allowed, max(0, ceiling - state.current))
    state.current += applied
    state.generated += applied
    state.generated_this_round += applied
    return applied


def settle_persisted(definition: ResourceDefinition, state: ResourceState) -> int:
    return 0 if definition.lifecycle == ENCOUNTER_BUILDER else state.current


@dataclass(frozen=True)
class Candidate:
    action_id: str
    source_instance_id: str
    band: str
    priority: int
    cost_bps: int
    activation_bps: int
    eligible: bool = True
    deterministic: bool = False
    display_name: str = ""


MASK_64 = (1 << 64) - 1
GOLDEN_GAMMA = -7046029254386353131 & MASK_64
MIX_1 = -4658895280553007687 & MASK_64
MIX_2 = -7723592293110705685 & MASK_64


def rotate_left_64(value: int, bits: int) -> int:
    value &= MASK_64
    return ((value << bits) | (value >> (64 - bits))) & MASK_64


def stable_hash(value: str) -> int:
    assert value.isascii()
    result = -3750763034362895579 & MASK_64
    for char in value:
        result ^= ord(char)
        result = (result * 1099511628211) & MASK_64
    return result


def deterministic_event_roll_bps(seed: int, event_index: int, event_type: str, region_id: str) -> int:
    """Match DeterministicRandom.forEvent(...).nextDouble() using ASCII stable IDs."""
    mixed = (seed & MASK_64) ^ rotate_left_64(event_index, 21)
    mixed ^= rotate_left_64(stable_hash(event_type), 11)
    mixed ^= rotate_left_64(stable_hash(region_id), 37)
    state = (mixed + GOLDEN_GAMMA) & MASK_64
    value = state
    value = ((value ^ (value >> 30)) * MIX_1) & MASK_64
    value = ((value ^ (value >> 27)) * MIX_2) & MASK_64
    value ^= value >> 31
    return ((value >> 11) * 10000) // (1 << 53)


def keyed_roll_bps(
    *,
    seed: int,
    rules_version: str,
    encounter_id: str,
    actor_action_index: int,
    actor_id: str,
    action_id: str,
    source_instance_id: str,
    slot: str,
) -> int:
    for stable_id in (
        rules_version,
        encounter_id,
        actor_id,
        action_id,
        source_instance_id,
        slot,
    ):
        assert stable_id.isascii() and ":" not in stable_id and "|" not in stable_id
    event_type = f"{slot}:{rules_version}:{actor_id}:{action_id}"
    region_id = f"{encounter_id}:{source_instance_id}"
    return deterministic_event_roll_bps(seed, actor_action_index, event_type, region_id)


def choose_action(
    *,
    seed: int,
    rules_version: str,
    encounter_id: str,
    actor_action_index: int,
    actor_id: str,
    current_resource_bps: int,
    candidates: list[Candidate],
) -> str:
    basic = Candidate("aq.action.basic", "system.basic", "BASIC", 0, 0, 10000, deterministic=True)
    stable_keys = [(candidate.action_id, candidate.source_instance_id) for candidate in candidates]
    assert len(stable_keys) == len(set(stable_keys))
    assert all(candidate.action_id != basic.action_id for candidate in candidates)
    passed: list[Candidate] = []
    for candidate in candidates + [basic]:
        assert candidate.band in BANDS
        assert -100 <= candidate.priority <= 100
        assert 0 <= candidate.cost_bps <= 10000
        assert 0 <= candidate.activation_bps <= (10000 if candidate.deterministic else 6000)
        assert not candidate.deterministic or candidate.band in {"SURVIVAL", "RECOVERY", "BASIC"}
        if not candidate.eligible or candidate.cost_bps > current_resource_bps:
            continue
        if candidate.deterministic or keyed_roll_bps(
            seed=seed,
            rules_version=rules_version,
            encounter_id=encounter_id,
            actor_action_index=actor_action_index,
            actor_id=actor_id,
            action_id=candidate.action_id,
            source_instance_id=candidate.source_instance_id,
            slot="AUTOMATION_V1",
        ) < candidate.activation_bps:
            passed.append(candidate)
    selected = min(
        passed,
        key=lambda candidate: (
            -BANDS[candidate.band],
            -candidate.priority,
            candidate.action_id,
            candidate.source_instance_id,
        ),
    )
    return selected.action_id


@dataclass
class ResourceLedger:
    receipts: set[str] = field(default_factory=set)


def apply_resource_receipt_once(
    ledger: ResourceLedger,
    receipt_id: str,
    definition: ResourceDefinition,
    state: ResourceState,
    cost: int,
    gain: int,
    *,
    attrition_bps: int | None = None,
) -> bool:
    if receipt_id in ledger.receipts:
        return False
    if cost > state.current:
        return False
    staged_current = state.current - cost
    staged_ceiling = state.recovery_ceiling
    if definition.lifecycle == EXPEDITION_POOL:
        resolved_attrition_bps = (
            definition.pool_spend_attrition_bps if attrition_bps is None else attrition_bps
        )
        staged_ceiling = max(
            staged_current,
            staged_ceiling - bps_of_amount(cost, resolved_attrition_bps),
        )
    else:
        staged_ceiling = state.maximum
    staged_gain = min(gain, max(0, staged_ceiling - staged_current))
    state.current = staged_current + staged_gain
    state.recovery_ceiling = staged_ceiling
    state.spent += cost
    state.generated += staged_gain
    ledger.receipts.add(receipt_id)
    return True


@dataclass(frozen=True)
class ProtectionBudget:
    class_id: str
    kind: str
    max_activations_per_encounter: int
    max_activations_per_expedition: int
    max_amount_per_activation: int
    max_amount_per_encounter: int
    max_amount_per_expedition: int
    resource_cost_bps: int
    min_cooldown_turns: int
    spend_attrition_bps: int


PROTECTION_BUDGETS = (
    ProtectionBudget("aq.class.warrior", "SHIELD", 1, 3, 1000, 1000, 3000, 3000, 3, 0),
    ProtectionBudget("aq.class.rogue", "BARRIER", 1, 2, 1, 1, 2, 3500, 4, 0),
    ProtectionBudget("aq.class.ranger", "SHIELD", 1, 3, 600, 600, 1800, 2000, 3, 10000),
    ProtectionBudget("aq.class.mage", "SHIELD", 1, 2, 600, 600, 1200, 2000, 4, 10000),
    ProtectionBudget("aq.class.cleric", "HEAL", 2, 5, 1000, 2000, 5000, 2000, 3, 10000),
    ProtectionBudget("aq.class.cleric", "SHIELD", 1, 3, 600, 600, 1800, 1800, 3, 10000),
    ProtectionBudget("aq.class.paladin", "HEAL", 1, 2, 800, 800, 1600, 3000, 5, 0),
    ProtectionBudget("aq.class.paladin", "SHIELD", 1, 3, 900, 900, 2700, 3000, 3, 0),
)


OFFENSE_CAPS = {
    # rolling coefficient, hostile applications/turn-units, friendly applications/turn-units
    "aq.class.warrior": (112000, 1, 3, 2, 4),
    "aq.class.rogue": (116000, 2, 4, 1, 2),
    "aq.class.ranger": (112000, 2, 6, 2, 4),
    "aq.class.mage": (116000, 2, 6, 1, 2),
    "aq.class.cleric": (106000, 1, 3, 3, 8),
    "aq.class.paladin": (108000, 1, 3, 3, 6),
}


def should_recover(state: ResourceState, cheapest_cost: int) -> bool:
    if (
        state.current >= state.recovery_ceiling
        or state.consecutive_recovery_actions >= 3
        or state.recovery_actions_this_encounter >= 3
    ):
        return False
    if state.consecutive_recovery_actions > 0 and state.current * 2 < state.maximum:
        return True
    return state.current * 5 <= state.maximum or state.current < cheapest_cost


def simulate_greedy(
    definition: ResourceDefinition,
    turns: int = 30,
    maximum: int = 10000,
) -> tuple[int, int, int, int]:
    state = begin_encounter(definition, maximum)
    minimum_cost = amount_from_bps(maximum, definition.spend_min_bps)
    basic_gain = amount_from_bps(maximum, definition.basic_gain_bps)
    recovery_gain = amount_from_bps(maximum, definition.recovery_action_gain_bps)
    spends = basics = recoveries = 0
    for _ in range(turns):
        state.generated_this_round = 0
        if spend(definition, state, minimum_cost):
            spends += 1
            state.consecutive_recovery_actions = 0
        elif recovery_gain > 0 and should_recover(state, minimum_cost):
            generate(definition, state, recovery_gain)
            state.consecutive_recovery_actions += 1
            state.recovery_actions_this_encounter += 1
            recoveries += 1
        else:
            generate(definition, state, basic_gain)
            state.consecutive_recovery_actions = 0
            basics += 1
        assert 0 <= state.current <= state.maximum
    return spends, basics, recoveries, state.current


def apply_combat_heal(current_hp: int, encounter_start_hp: int, max_hp: int, amount: int) -> int:
    assert 0 <= current_hp <= encounter_start_hp <= max_hp and amount >= 0
    return min(encounter_start_hp, current_hp + amount)


@dataclass
class ProtectionLedger:
    encounter_activations: dict[tuple[str, str, int], int] = field(default_factory=dict)
    expedition_activations: dict[tuple[str, str], int] = field(default_factory=dict)
    encounter_amounts: dict[tuple[str, str, int], int] = field(default_factory=dict)
    expedition_amounts: dict[tuple[str, str], int] = field(default_factory=dict)
    last_turn: dict[tuple[str, str, int], int] = field(default_factory=dict)


def apply_protection_action(
    *,
    budget: ProtectionBudget,
    definition: ResourceDefinition,
    state: ResourceState,
    ledger: ProtectionLedger,
    encounter_index: int,
    actor_turn: int,
    nominal_amount: int,
) -> bool:
    encounter_key = (budget.class_id, budget.kind, encounter_index)
    expedition_key = (budget.class_id, budget.kind)
    if nominal_amount <= 0 or nominal_amount > budget.max_amount_per_activation:
        return False
    if ledger.encounter_activations.get(encounter_key, 0) >= budget.max_activations_per_encounter:
        return False
    if ledger.expedition_activations.get(expedition_key, 0) >= budget.max_activations_per_expedition:
        return False
    if ledger.encounter_amounts.get(encounter_key, 0) + nominal_amount > budget.max_amount_per_encounter:
        return False
    if ledger.expedition_amounts.get(expedition_key, 0) + nominal_amount > budget.max_amount_per_expedition:
        return False
    previous_turn = ledger.last_turn.get(encounter_key)
    if previous_turn is not None and actor_turn - previous_turn < budget.min_cooldown_turns:
        return False
    cost = amount_from_bps(state.maximum, budget.resource_cost_bps)
    if not spend(definition, state, cost, attrition_bps=budget.spend_attrition_bps):
        return False
    ledger.encounter_activations[encounter_key] = ledger.encounter_activations.get(encounter_key, 0) + 1
    ledger.expedition_activations[expedition_key] = ledger.expedition_activations.get(expedition_key, 0) + 1
    ledger.encounter_amounts[encounter_key] = ledger.encounter_amounts.get(encounter_key, 0) + nominal_amount
    ledger.expedition_amounts[expedition_key] = ledger.expedition_amounts.get(expedition_key, 0) + nominal_amount
    ledger.last_turn[encounter_key] = actor_turn
    return True


def simulate_standard_expedition(
    definition: ResourceDefinition,
    encounters: int = 30,
) -> tuple[dict[int, int], dict[int, int]]:
    """Two minimum-cost attempts in each synthetic ten-action encounter."""
    assert definition.lifecycle == EXPEDITION_POOL
    persisted = 10000
    snapshots: dict[int, int] = {}
    spenders: dict[int, int] = {}
    for encounter_index in range(1, encounters + 1):
        state = begin_encounter(definition, 10000, persisted=persisted)
        cost = amount_from_bps(state.maximum, definition.spend_min_bps)
        basic_gain = amount_from_bps(state.maximum, definition.basic_gain_bps)
        recovery_gain = amount_from_bps(state.maximum, definition.recovery_action_gain_bps)
        used = 0
        for actor_turn in range(10):
            state.generated_this_round = 0
            if actor_turn in {0, 5} and spend(definition, state, cost):
                used += 1
                state.consecutive_recovery_actions = 0
            elif recovery_gain > 0 and should_recover(state, cost):
                generate(definition, state, recovery_gain)
                state.consecutive_recovery_actions += 1
                state.recovery_actions_this_encounter += 1
            else:
                generate(definition, state, basic_gain)
                state.consecutive_recovery_actions = 0
        persisted = settle_persisted(definition, state)
        spenders[encounter_index] = used
        if encounter_index in {1, 5, 12, 30}:
            snapshots[encounter_index] = persisted
    return snapshots, spenders


def run_reference_checks() -> tuple[
    list[str],
    dict[str, tuple[int, int, int, int]],
    dict[str, dict[int, int]],
]:
    passed: list[str] = []
    definitions_by_class = {definition.class_id: definition for definition in DEFINITIONS}

    assert len(DEFINITIONS) == 6
    assert len(definitions_by_class) == 6
    assert len({definition.resource_id for definition in DEFINITIONS}) == 6
    assert all(definition.resource_id.startswith("aq.resource.") for definition in DEFINITIONS)
    assert all(definition.lifecycle in {ENCOUNTER_BUILDER, EXPEDITION_POOL} for definition in DEFINITIONS)
    assert all(
        definition.pool_spend_attrition_bps == (5000 if definition.lifecycle == EXPEDITION_POOL else 0)
        for definition in DEFINITIONS
    )
    passed.append("SIX_CLASS_STABLE_RESOURCE_IDS")

    assert amount_from_bps(1, 1) == 1
    assert amount_from_bps(1000, 0) == 0
    assert amount_from_bps(1000, 2500) == 250
    assert amount_from_bps(2_000_000_000, 10000) == 2_000_000_000
    assert bps_of_amount(1, 5000) == 1
    assert bps_of_amount(1500, 5000) == 750
    passed.append("BPS_CONVERSION_IS_INTEGER_AND_BOUNDED")

    for definition in DEFINITIONS:
        state = begin_encounter(definition, 1000, persisted=370)
        if definition.lifecycle == ENCOUNTER_BUILDER:
            assert state.current == amount_from_bps(1000, definition.start_bps)
            assert settle_persisted(definition, state) == 0
        else:
            assert state.current == 370 and state.recovery_ceiling == 370
            applied = generate(definition, state, 999)
            assert applied == 0 and state.current == 370
            assert settle_persisted(definition, state) == 370
    passed.append("ENCOUNTER_AND_EXPEDITION_LIFECYCLES")

    ranger = definitions_by_class["aq.class.ranger"]
    attrition_state = begin_encounter(ranger, 10000)
    assert spend(ranger, attrition_state, 1500)
    assert (attrition_state.current, attrition_state.recovery_ceiling) == (8500, 9250)
    assert generate(ranger, attrition_state, 9999) == 750
    support_state = begin_encounter(ranger, 10000)
    assert spend(ranger, support_state, 2000, attrition_bps=10000)
    assert (support_state.current, support_state.recovery_ceiling) == (8000, 8000)
    assert generate(ranger, support_state, 9999) == 0
    passed.append("POOL_ATTRITION_AND_NONRECOVERABLE_SUPPORT_COSTS")

    assert grow_persistent_ratio(250, 1000, 1600) == 400
    assert grow_persistent_ratio(333, 1000, 2000) == 666
    assert grow_persistent_ratio(0, 1000, 1600) == 0
    passed.append("POOL_GROWTH_PRESERVES_SPENT_RATIO")

    warrior = definitions_by_class["aq.class.warrior"]
    warrior_state = begin_encounter(warrior, 1000)
    first_gain = generate(warrior, warrior_state, 200, enforce_round_cap=True)
    second_gain = generate(warrior, warrior_state, 200, enforce_round_cap=True)
    assert first_gain == 200 and second_gain == 100 and warrior_state.current == 300
    passed.append("ROOT_AND_ROUND_GENERATION_CAPS")

    candidates = [
        Candidate("aq.action.power.z", "instance.z", "POWER", 20, 2000, 6000, display_name="강공"),
        Candidate("aq.action.power.a", "instance.a", "POWER", 20, 2000, 6000, display_name="연격"),
        Candidate("aq.action.control", "instance.c", "CONTROL", 10, 1000, 6000, display_name="속박"),
    ]
    choose_args = dict(
        seed=77,
        rules_version="aq.rules.v1",
        encounter_id="encounter.1",
        actor_action_index=3,
        actor_id="hero.1",
        current_resource_bps=10000,
    )
    selected = choose_action(candidates=candidates, **choose_args)
    permuted = choose_action(
        candidates=[
            replace(candidates[2], display_name="拘束"),
            replace(candidates[0], display_name="Power"),
            replace(candidates[1], display_name="連撃"),
        ],
        **choose_args,
    )
    assert selected == permuted
    passed.append("AUTO_SELECTION_IGNORES_ORDER_AND_LOCALIZATION")

    automation_roll = keyed_roll_bps(
        seed=77,
        rules_version="aq.rules.v1",
        encounter_id="encounter.1",
        actor_action_index=3,
        actor_id="hero.1",
        action_id="aq.action.power.a",
        source_instance_id="instance.a",
        slot="AUTOMATION_V1",
    )
    assert automation_roll == 4024
    assert keyed_roll_bps(
        seed=77,
        rules_version="aq.rules.v1",
        encounter_id="encounter.1",
        actor_action_index=3,
        actor_id="hero.1",
        action_id="aq.action.power.a",
        source_instance_id="instance.b",
        slot="AUTOMATION_V1",
    ) == 6399
    core_before = deterministic_event_roll_bps(77, 3, "CORE_HIT_V1:hero.1:root.3", "encounter.1:core")
    assert core_before == 1640
    with_ineligible = choose_action(
        candidates=candidates + [
            Candidate("aq.action.ineligible", "instance.x", "SURVIVAL", 100, 0, 6000, eligible=False)
        ],
        **choose_args,
    )
    core_after = deterministic_event_roll_bps(77, 3, "CORE_HIT_V1:hero.1:root.3", "encounter.1:core")
    assert selected == with_ineligible and core_before == core_after
    duplicate_rejected = False
    try:
        choose_action(candidates=candidates + [candidates[0]], **choose_args)
    except AssertionError:
        duplicate_rejected = True
    assert duplicate_rejected
    passed.append("AUTOMATION_KEYS_ARE_UNIQUE_AND_CORE_INDEPENDENT")

    assert choose_action(
        seed=1,
        rules_version="aq.rules.v1",
        encounter_id="encounter.zero",
        actor_action_index=1,
        actor_id="hero.zero",
        current_resource_bps=0,
        candidates=[Candidate("aq.action.costly", "instance.costly", "POWER", 100, 1, 6000)],
    ) == "aq.action.basic"
    passed.append("ZERO_RESOURCE_ALWAYS_HAS_BASIC_ACTION")

    mage = definitions_by_class["aq.class.mage"]
    mage_state = begin_encounter(mage, 1000)
    mage_state.current = 0
    recovery_gain = amount_from_bps(1000, mage.recovery_action_gain_bps)
    recovery_count = 0
    while should_recover(mage_state, 300):
        generate(mage, mage_state, recovery_gain)
        mage_state.consecutive_recovery_actions += 1
        mage_state.recovery_actions_this_encounter += 1
        recovery_count += 1
    assert recovery_count == 3 and mage_state.current == 450
    mage_state.current = 0
    mage_state.consecutive_recovery_actions = 0
    assert not should_recover(mage_state, 300)
    depleted_state = begin_encounter(mage, 1000, persisted=0)
    assert not should_recover(depleted_state, 300)
    passed.append("RECOVERY_ACTION_HAS_CONSECUTIVE_AND_ENCOUNTER_CAPS")

    ledger = ResourceLedger()
    ranger_state = begin_encounter(ranger, 1000)
    assert apply_resource_receipt_once(
        ledger,
        "encounter.2:turn.4",
        ranger,
        ranger_state,
        150,
        150,
    )
    after_first = (
        ranger_state.current,
        ranger_state.recovery_ceiling,
        ranger_state.spent,
        ranger_state.generated,
    )
    assert after_first == (925, 925, 150, 75)
    assert not apply_resource_receipt_once(
        ledger,
        "encounter.2:turn.4",
        ranger,
        ranger_state,
        150,
        150,
    )
    assert (
        ranger_state.current,
        ranger_state.recovery_ceiling,
        ranger_state.spent,
        ranger_state.generated,
    ) == after_first
    assert not apply_resource_receipt_once(
        ledger,
        "encounter.2:turn.insufficient",
        ranger,
        ranger_state,
        2000,
        0,
    )
    assert "encounter.2:turn.insufficient" not in ledger.receipts
    passed.append("RESOURCE_RECEIPTS_ARE_ATOMIC_AND_IDEMPOTENT")

    class_ids = set(definitions_by_class)
    assert set(OFFENSE_CAPS) == class_ids
    for coefficient, hostile_apps, hostile_turns, friendly_apps, friendly_turns in OFFENSE_CAPS.values():
        assert 100000 <= coefficient <= 116000
        assert hostile_apps <= 2 and hostile_turns <= 6
        assert friendly_apps <= 3 and friendly_turns <= 8
    assert OFFENSE_CAPS["aq.class.mage"][0] == OFFENSE_CAPS["aq.class.rogue"][0] == 116000
    assert OFFENSE_CAPS["aq.class.cleric"][0] < OFFENSE_CAPS["aq.class.mage"][0]
    assert OFFENSE_CAPS["aq.class.paladin"][0] < OFFENSE_CAPS["aq.class.warrior"][0]
    passed.append("OFFENSE_CONTROL_AND_SUPPORT_ROLE_CAPS")

    numeric_encounter_totals = {class_id: 0 for class_id in class_ids}
    numeric_expedition_totals = {class_id: 0 for class_id in class_ids}
    barrier_encounter_totals = {class_id: 0 for class_id in class_ids}
    barrier_expedition_totals = {class_id: 0 for class_id in class_ids}
    assert len({(budget.class_id, budget.kind) for budget in PROTECTION_BUDGETS}) == len(PROTECTION_BUDGETS)
    for budget in PROTECTION_BUDGETS:
        assert budget.class_id in class_ids
        assert budget.kind in {"HEAL", "SHIELD", "BARRIER"}
        assert 1 <= budget.max_activations_per_encounter <= budget.max_activations_per_expedition
        assert 0 < budget.max_amount_per_encounter <= (
            budget.max_activations_per_encounter * budget.max_amount_per_activation
        )
        assert 0 < budget.max_amount_per_expedition <= (
            budget.max_activations_per_expedition * budget.max_amount_per_activation
        )
        assert 0 < budget.resource_cost_bps <= 10000 and budget.min_cooldown_turns >= 1
        definition = definitions_by_class[budget.class_id]
        expected_attrition = 10000 if definition.lifecycle == EXPEDITION_POOL else 0
        assert budget.spend_attrition_bps == expected_attrition
        if budget.kind == "BARRIER":
            barrier_encounter_totals[budget.class_id] += budget.max_amount_per_encounter
            barrier_expedition_totals[budget.class_id] += budget.max_amount_per_expedition
        else:
            numeric_encounter_totals[budget.class_id] += budget.max_amount_per_encounter
            numeric_expedition_totals[budget.class_id] += budget.max_amount_per_expedition

        runtime_ledger = ProtectionLedger()
        runtime_state = begin_encounter(definition, 10000)
        for activation_index in range(budget.max_activations_per_expedition):
            encounter_index = activation_index // budget.max_activations_per_encounter + 1
            actor_turn = (
                activation_index % budget.max_activations_per_encounter
            ) * budget.min_cooldown_turns + 1
            if definition.lifecycle == ENCOUNTER_BUILDER:
                runtime_state.current = runtime_state.maximum
            assert apply_protection_action(
                budget=budget,
                definition=definition,
                state=runtime_state,
                ledger=runtime_ledger,
                encounter_index=encounter_index,
                actor_turn=actor_turn,
                nominal_amount=budget.max_amount_per_activation,
            )
        before_reject = (runtime_state.current, runtime_state.recovery_ceiling, runtime_state.spent)
        assert not apply_protection_action(
            budget=budget,
            definition=definition,
            state=runtime_state,
            ledger=runtime_ledger,
            encounter_index=99,
            actor_turn=99,
            nominal_amount=budget.max_amount_per_activation,
        )
        assert (runtime_state.current, runtime_state.recovery_ceiling, runtime_state.spent) == before_reject

    assert max(numeric_encounter_totals.values()) <= 3000
    assert max(numeric_expedition_totals.values()) <= 7000
    assert max(barrier_encounter_totals.values()) <= 1
    assert max(barrier_expedition_totals.values()) <= 2
    assert {budget.class_id for budget in PROTECTION_BUDGETS if budget.kind == "BARRIER"} == {
        "aq.class.rogue"
    }
    assert all(budget.kind != "GUARD" for budget in PROTECTION_BUDGETS)
    passed.append("PROTECTION_BUDGETS_EXECUTE_COST_COOLDOWN_AND_TWO_SCOPES")

    cleric_heal = next(
        budget for budget in PROTECTION_BUDGETS
        if budget.class_id == "aq.class.cleric" and budget.kind == "HEAL"
    )
    cleric = definitions_by_class["aq.class.cleric"]
    cooldown_state = begin_encounter(cleric, 10000)
    cooldown_ledger = ProtectionLedger()
    assert apply_protection_action(
        budget=cleric_heal,
        definition=cleric,
        state=cooldown_state,
        ledger=cooldown_ledger,
        encounter_index=1,
        actor_turn=1,
        nominal_amount=1000,
    )
    before_cooldown_reject = (cooldown_state.current, cooldown_state.recovery_ceiling)
    assert not apply_protection_action(
        budget=cleric_heal,
        definition=cleric,
        state=cooldown_state,
        ledger=cooldown_ledger,
        encounter_index=1,
        actor_turn=2,
        nominal_amount=1000,
    )
    assert (cooldown_state.current, cooldown_state.recovery_ceiling) == before_cooldown_reject
    passed.append("PROTECTION_REJECTION_DOES_NOT_SPEND_RESOURCE")

    expedition_hp = 600
    for _ in range(30):
        encounter_start_hp = expedition_hp
        expedition_hp = max(0, expedition_hp - 100)
        expedition_hp = apply_combat_heal(expedition_hp, encounter_start_hp, 1000, 1000)
    assert expedition_hp == 600
    passed.append("COMBAT_HEAL_CANNOT_REPAIR_PRIOR_ENCOUNTER_WOUNDS")

    metrics = {definition.class_id: simulate_greedy(definition) for definition in DEFINITIONS}
    assert all(sum(metric[:3]) == 30 for metric in metrics.values())
    reference_start_capacities = {
        "aq.class.warrior": 38,
        "aq.class.rogue": 60,
        "aq.class.ranger": 76,
        "aq.class.mage": 113,
        "aq.class.cleric": 99,
        "aq.class.paladin": 72,
    }
    for definition in DEFINITIONS:
        baseline = metrics[definition.class_id]
        reference_start = simulate_greedy(
            definition,
            maximum=reference_start_capacities[definition.class_id],
        )
        assert sum(reference_start[:3]) == 30
        assert abs(reference_start[0] - baseline[0]) <= 1
        for maximum in (1_000_000, 2_000_000_000):
            scaled = simulate_greedy(definition, maximum=maximum)
            assert scaled[:3] == baseline[:3]
            assert scaled[3] == baseline[3] * (maximum // 10000)
        rounded = simulate_greedy(definition, maximum=997)
        assert abs(rounded[0] - baseline[0]) <= 1
    passed.append("REFERENCE_START_AND_LARGE_CAPACITY_STABILITY")

    expedition_metrics: dict[str, dict[int, int]] = {}
    for definition in DEFINITIONS:
        if definition.lifecycle != EXPEDITION_POOL:
            continue
        snapshots, spenders = simulate_standard_expedition(definition)
        expedition_metrics[definition.class_id] = snapshots
        minimum_cost = amount_from_bps(10000, definition.spend_min_bps)
        assert snapshots[1] >= snapshots[5] >= snapshots[12] >= snapshots[30] >= 0
        assert snapshots[5] >= minimum_cost
        assert snapshots[12] < minimum_cost
        assert snapshots[30] == snapshots[12]
        assert sum(spenders[index] for index in range(1, 6)) >= 5
        assert all(spenders[index] == 0 for index in range(13, 31))
    passed.append("CONNECTED_FIVE_TWELVE_THIRTY_EXPEDITION_TRACE")

    monster_definition = ResourceDefinition(
        "aq.enemy.archetype.spellcaster",
        "aq.resource.enemy.spell_pool",
        ENCOUNTER_BUILDER,
        10000,
        1500,
        3000,
    )
    monster_state = begin_encounter(monster_definition, 400)
    assert monster_state.current == 400
    assert choose_action(
        seed=9,
        rules_version="aq.rules.v1",
        encounter_id="enemy.encounter",
        actor_action_index=1,
        actor_id="enemy.1",
        current_resource_bps=0,
        candidates=[],
    ) == "aq.action.basic"
    assert settle_persisted(monster_definition, monster_state) == 0
    passed.append("MONSTERS_SHARE_RESOURCE_AND_BASIC_FALLBACK_CONTRACT")

    return passed, metrics, expedition_metrics


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true", help="run and print the complete reference gate")
    args = parser.parse_args()

    passed, metrics, expedition_metrics = run_reference_checks()
    print(f"CLASS_RESOURCES_AUTO_ROLES_V0_1: PASS ({len(passed)}/{len(passed)})")
    for class_id, (spenders, basics, recoveries, final_resource) in metrics.items():
        print(
            f"  {class_id:24s} 30turn spend={spenders:2d} basic={basics:2d} "
            f"recover={recoveries:2d} final={final_resource:5d}/10000"
        )
    for class_id, snapshots in expedition_metrics.items():
        print(
            f"  {class_id:24s} expedition resource "
            f"E1={snapshots[1]:5d} E5={snapshots[5]:5d} "
            f"E12={snapshots[12]:5d} E30={snapshots[30]:5d}"
        )
    for name in passed:
        print(f"  PASS {name}")
    if args.pd:
        print("REFERENCE_GATE: PASS (resource/automation/budget invariants; live skills/content/economy/storage pending)")


if __name__ == "__main__":
    main()
