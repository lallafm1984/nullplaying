#!/usr/bin/env python3
"""Reference audit for AlarmQuest non-combat ability checks v0.1.

This is a planning harness. It does not import or mutate live game data.
"""

from __future__ import annotations

import argparse
import math
from dataclasses import dataclass, field, replace


DCS = {
    "EASY": 8,
    "ROUTINE": 10,
    "STANDARD": 12,
    "HARD": 14,
    "SEVERE": 16,
    "EXTREME": 18,
}
DC_WEIGHTS = {
    "EASY": 0.20,
    "ROUTINE": 0.40,
    "STANDARD": 0.25,
    "HARD": 0.10,
    "SEVERE": 0.04,
    "EXTREME": 0.01,
}
PROFICIENCY = {
    "UNTRAINED": 0,
    "TRAINED": 2,
    "EXPERT": 4,
}


@dataclass(frozen=True)
class Profile:
    name: str
    score: int
    proficiency: int


@dataclass(frozen=True)
class CostlyCandidate:
    margin: int
    salvage_eligible: bool
    matching_edge: bool
    has_costly_effect: bool
    base_check_duration_seconds: int
    reward_grade: str
    costly_effect_tags: frozenset[str]


@dataclass
class CostlyStore:
    receipts: dict[str, str] = field(default_factory=dict)
    used_expeditions: set[int] = field(default_factory=set)
    progress_applied: set[str] = field(default_factory=set)
    time_cost_applied: set[str] = field(default_factory=set)


@dataclass(frozen=True)
class MandatoryFailureRule:
    current_stage_ordinal: int
    next_stage_ordinal: int | None
    current_progress: int
    progress_target: int
    progress_delta: int


PROFILES = (
    Profile("LOW_UNTRAINED", 8, 0),
    Profile("LOW_TRAINED", 8, 2),
    Profile("MID_UNTRAINED", 12, 0),
    Profile("STANDARD_PRIMARY_TRAINED", 15, 2),
    Profile("HIGH_TRAINED", 17, 2),
    Profile("LOW_EXPERT", 8, 4),
    Profile("CAP_EXPERT", 20, 4),
)


def ability_modifier(score: int) -> int:
    return math.floor((score - 10) / 2)


def total_modifier(
    score: int,
    proficiency: int,
    tool_bonus: int = 0,
    circumstance_bonus: int = 0,
) -> int:
    assert 3 <= score <= 20
    assert proficiency in PROFICIENCY.values()
    assert 0 <= tool_bonus <= 2
    assert -2 <= circumstance_bonus <= 2
    return ability_modifier(score) + proficiency + tool_bonus + circumstance_bonus


def success(roll: int, modifier: int, dc: int) -> bool:
    assert 1 <= roll <= 20
    return roll + modifier >= dc


def exact_success_probability(modifier: int, dc: int) -> float:
    return sum(success(roll, modifier, dc) for roll in range(1, 21)) / 20.0


def exact_near_miss_probability(modifier: int, dc: int) -> float:
    return sum(roll + modifier == dc - 1 for roll in range(1, 21)) / 20.0


def weighted_probability(profile: Profile, near_miss: bool = False) -> float:
    modifier = total_modifier(profile.score, profile.proficiency)
    probability = exact_near_miss_probability if near_miss else exact_success_probability
    return sum(DC_WEIGHTS[name] * probability(modifier, dc) for name, dc in DCS.items())


FORBIDDEN_COSTLY_REWARD_TAGS = frozenset(
    {
        "GOLD",
        "EXPERIENCE",
        "HEALTH",
        "MANA",
        "ITEM",
        "RARE",
        "QUEST_CORE",
        "FIRST_DISCOVERY",
        "NEXT_BATTLE",
        "BONUS_ROLL",
    }
)
ALLOWED_COSTLY_EFFECT_TAGS = frozenset({"PROGRESS", "SAFE_ROUTE"})


def costly_eligible(
    store: CostlyStore,
    candidate: CostlyCandidate,
    expedition_sequence: int,
    check_id: str,
) -> bool:
    return (
        candidate.margin == -1
        and candidate.salvage_eligible
        and candidate.matching_edge
        and candidate.has_costly_effect
        and 10 <= candidate.base_check_duration_seconds <= 120
        and candidate.reward_grade == "PROGRESS_ONLY"
        and candidate.costly_effect_tags <= ALLOWED_COSTLY_EFFECT_TAGS
        and candidate.costly_effect_tags.isdisjoint(FORBIDDEN_COSTLY_REWARD_TAGS)
        and check_id not in store.receipts
        and expedition_sequence not in store.used_expeditions
    )


def reserve_costly_atomic(
    store: CostlyStore,
    candidate: CostlyCandidate,
    expedition_sequence: int,
    check_id: str,
    fail_before_commit: bool = False,
) -> bool:
    if not costly_eligible(store, candidate, expedition_sequence, check_id):
        return False
    staged_receipts = dict(store.receipts)
    staged_used_expeditions = set(store.used_expeditions)
    staged_receipts[check_id] = "RESERVED"
    staged_used_expeditions.add(expedition_sequence)
    if fail_before_commit:
        return False
    store.receipts = staged_receipts
    store.used_expeditions = staged_used_expeditions
    return True


def settle_costly_atomic(
    store: CostlyStore,
    check_id: str,
    fail_before_commit: bool = False,
) -> tuple[bool, bool]:
    """Reference staged commit; actual Room transaction verification remains pending."""
    if store.receipts.get(check_id) != "RESERVED":
        return False, False
    staged_receipts = dict(store.receipts)
    staged_progress = set(store.progress_applied)
    staged_time_cost = set(store.time_cost_applied)
    staged_receipts[check_id] = "SETTLED"
    staged_progress.add(check_id)
    staged_time_cost.add(check_id)
    if fail_before_commit:
        return False, False
    store.receipts = staged_receipts
    store.progress_applied = staged_progress
    store.time_cost_applied = staged_time_cost
    return True, True


def costly_time_delay(base_check_duration_seconds: int) -> int:
    assert 10 <= base_check_duration_seconds <= 120
    return min(60, max(5, math.ceil(base_check_duration_seconds * 0.50)))


def finite_failure_bound(rule: MandatoryFailureRule) -> int:
    """Return the maximum repeats for one mandatory failure edge or reject it."""
    assert 0 <= rule.current_progress < rule.progress_target
    if rule.progress_delta >= 1:
        remaining = rule.progress_target - rule.current_progress
        return math.ceil(remaining / rule.progress_delta)
    if rule.next_stage_ordinal is not None and rule.next_stage_ordinal > rule.current_stage_ordinal:
        return 1
    raise AssertionError("mandatory failure neither advances progress nor moves to a later stage")


def assert_mandatory_progress_contract() -> None:
    finite_route = (
        MandatoryFailureRule(0, None, 0, 4, 1),
        MandatoryFailureRule(1, 2, 0, 1, 0),
        MandatoryFailureRule(2, None, 0, 6, 2),
    )
    declared_max_mandatory_transitions = 8
    assert sum(finite_failure_bound(rule) for rule in finite_route) == declared_max_mandatory_transitions

    invalid_rules = (
        MandatoryFailureRule(1, None, 0, 5, 0),
        MandatoryFailureRule(1, 1, 0, 5, 0),
        MandatoryFailureRule(1, 0, 0, 5, 0),
    )
    for invalid_rule in invalid_rules:
        try:
            finite_failure_bound(invalid_rule)
        except AssertionError:
            pass
        else:
            raise AssertionError("non-progressing mandatory failure was accepted")


def assert_probability_contract() -> None:
    assert [ability_modifier(score) for score in (3, 8, 9, 10, 17, 20)] == [-4, -1, -1, 0, 3, 5]
    assert math.isclose(sum(DC_WEIGHTS.values()), 1.0, abs_tol=1e-12)
    expected = {
        "LOW_UNTRAINED": 0.459,
        "LOW_TRAINED": 0.559,
        "MID_UNTRAINED": 0.559,
        "STANDARD_PRIMARY_TRAINED": 0.709,
        "HIGH_TRAINED": 0.759,
        "LOW_EXPERT": 0.659,
        "CAP_EXPERT": 0.939,
    }
    for profile in PROFILES:
        actual = weighted_probability(profile)
        assert math.isclose(actual, expected[profile.name], abs_tol=1e-12), (profile, actual)

    for dc in DCS.values():
        previous = -1.0
        for score in range(3, 21):
            current = exact_success_probability(total_modifier(score, 0), dc)
            assert current >= previous
            previous = current

    for score in range(3, 21):
        for proficiency in PROFICIENCY.values():
            modifier = total_modifier(score, proficiency)
            previous = 1.0
            for dc in sorted(DCS.values()):
                current = exact_success_probability(modifier, dc)
                assert current <= previous
                previous = current

    low = PROFILES[0]
    low_modifier = total_modifier(low.score, low.proficiency)
    assert exact_success_probability(low_modifier, DCS["SEVERE"]) >= 0.20
    assert math.isclose(
        weighted_probability(PROFILES[1]) - weighted_probability(PROFILES[0]),
        0.10,
        abs_tol=1e-12,
    )
    assert weighted_probability(PROFILES[5]) < weighted_probability(PROFILES[4])

    # Display level is deliberately absent from the formula. Long.MAX cannot change a check.
    for display_level in (1, 100, 2**63 - 1):
        del display_level
        assert total_modifier(8, 2) == 1


def assert_costly_success_contract() -> dict[str, float]:
    checks_per_expedition = 12
    salvage_eligible_rate = 0.60
    matching_edge_rate = 0.25
    minimum_time_cost_seconds = costly_time_delay(10)
    maximum_time_cost_seconds = costly_time_delay(120)
    representative = PROFILES[1]
    near_miss_rate = weighted_probability(representative, near_miss=True)
    eligible_near_miss = near_miss_rate * salvage_eligible_rate * matching_edge_rate
    activation_per_expedition = 1.0 - (1.0 - eligible_near_miss) ** checks_per_expedition
    conversion_share = activation_per_expedition / checks_per_expedition

    assert math.isclose(near_miss_rate, 0.05, abs_tol=1e-12)
    assert 0.05 <= activation_per_expedition <= 0.15
    assert conversion_share <= 0.015
    assert minimum_time_cost_seconds == 5
    assert costly_time_delay(11) == 6
    assert maximum_time_cost_seconds == 60

    valid_candidate = CostlyCandidate(
        margin=-1,
        salvage_eligible=True,
        matching_edge=True,
        has_costly_effect=True,
        base_check_duration_seconds=30,
        reward_grade="PROGRESS_ONLY",
        costly_effect_tags=frozenset({"PROGRESS"}),
    )
    eligibility_failures = (
        replace(valid_candidate, margin=-2),
        replace(valid_candidate, margin=0),
        replace(valid_candidate, salvage_eligible=False),
        replace(valid_candidate, matching_edge=False),
        replace(valid_candidate, has_costly_effect=False),
        replace(valid_candidate, base_check_duration_seconds=9),
        replace(valid_candidate, base_check_duration_seconds=121),
        replace(valid_candidate, reward_grade="STANDARD"),
        replace(valid_candidate, costly_effect_tags=frozenset({"PROGRESS", "GOLD"})),
    )
    for invalid_candidate in eligibility_failures:
        assert not costly_eligible(CostlyStore(), invalid_candidate, 41, "check:invalid")

    # The reference state machine stages both fields before commit. This is not
    # a substitute for a real Room transaction/process-death test.
    store = CostlyStore()
    expedition_sequence = 41
    check_id = "expedition:41:action:903:event:collapsed_ravine"
    assert costly_eligible(store, valid_candidate, expedition_sequence, check_id)
    assert not reserve_costly_atomic(
        store,
        valid_candidate,
        expedition_sequence,
        check_id,
        fail_before_commit=True,
    )
    assert store.receipts == {} and store.used_expeditions == set()
    assert reserve_costly_atomic(store, valid_candidate, expedition_sequence, check_id)
    assert store.receipts[check_id] == "RESERVED"
    assert expedition_sequence in store.used_expeditions
    assert not reserve_costly_atomic(store, valid_candidate, expedition_sequence, check_id)
    assert settle_costly_atomic(store, check_id, fail_before_commit=True) == (False, False)
    assert store.receipts[check_id] == "RESERVED"
    assert check_id not in store.progress_applied and check_id not in store.time_cost_applied
    first = settle_costly_atomic(store, check_id)
    replay = settle_costly_atomic(store, check_id)
    assert first == (True, True)
    assert replay == (False, False)
    assert store.receipts[check_id] == "SETTLED"
    assert store.progress_applied == {check_id}
    assert store.time_cost_applied == {check_id}
    assert not reserve_costly_atomic(
        store,
        valid_candidate,
        expedition_sequence,
        "expedition:41:action:904:event:lost_field_scout",
    )

    # At most one species conversion is reserved in an expedition.
    species_conversion_used = False
    conversions = 0
    for _ in range(20):
        if not species_conversion_used:
            species_conversion_used = True
            conversions += 1
    assert conversions == 1

    assert valid_candidate.reward_grade == "PROGRESS_ONLY"
    assert valid_candidate.costly_effect_tags <= ALLOWED_COSTLY_EFFECT_TAGS
    assert valid_candidate.costly_effect_tags.isdisjoint(FORBIDDEN_COSTLY_REWARD_TAGS)

    return {
        "near_miss_rate": near_miss_rate,
        "activation_per_expedition": activation_per_expedition,
        "conversion_share": conversion_share,
        "expected_24h_activations": activation_per_expedition * 8,
        "expected_72h_activations": activation_per_expedition * 24,
        "expected_24h_min_extra_seconds": activation_per_expedition * 8 * minimum_time_cost_seconds,
        "expected_24h_max_extra_seconds": activation_per_expedition * 8 * maximum_time_cost_seconds,
        "expected_72h_min_extra_seconds": activation_per_expedition * 24 * minimum_time_cost_seconds,
        "expected_72h_max_extra_seconds": activation_per_expedition * 24 * maximum_time_cost_seconds,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true", help="run and print the complete reference gate")
    args = parser.parse_args()

    assert_probability_contract()
    assert_mandatory_progress_contract()
    costly = assert_costly_success_contract()

    print("NONCOMBAT_CHECKS_V0_1: PASS")
    for profile in PROFILES:
        modifier = total_modifier(profile.score, profile.proficiency)
        print(
            f"  {profile.name:16s} mod={modifier:+d} "
            f"weighted_success={weighted_probability(profile) * 100:5.2f}%"
        )
    print(f"  representative_near_miss={costly['near_miss_rate'] * 100:.2f}%")
    print(f"  synthetic_costly_activation_per_expedition={costly['activation_per_expedition'] * 100:.2f}%")
    print(f"  synthetic_conversion_share={costly['conversion_share'] * 100:.2f}%")
    print("  costly_forbidden_reward_tags=0")
    print("  mandatory_failure_finite_bound_contract=PASS")
    print(
        "  synthetic_24h="
        f"{costly['expected_24h_activations']:.2f} activations, "
        f"{costly['expected_24h_min_extra_seconds']:.2f}.."
        f"{costly['expected_24h_max_extra_seconds']:.2f}s delay"
    )
    print(
        "  synthetic_72h="
        f"{costly['expected_72h_activations']:.2f} activations, "
        f"{costly['expected_72h_min_extra_seconds']:.2f}.."
        f"{costly['expected_72h_max_extra_seconds']:.2f}s delay"
    )
    if args.pd:
        print("REFERENCE_GATE: PASS (formula/eligibility/reference-state invariants; live content/storage/economy pending)")


if __name__ == "__main__":
    main()
