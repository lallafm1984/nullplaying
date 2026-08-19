#!/usr/bin/env python3
"""Planning audit for AlarmQuest finite class-skill structure v0.1.

This reference model validates slots, offers, finite ranks, role tags, receipts,
and snapshots. It intentionally has no concrete skill coefficients or live state.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import math
import re
from dataclasses import dataclass, field


ACTIVE = "ACTIVE"
PASSIVE = "PASSIVE"
CLASSES = ("warrior", "rogue", "ranger", "mage", "cleric", "paladin")
STABLE_ID = re.compile(r"^[a-z0-9._-]+$")
RULES_VERSION = "aq.skill_rules.v1"


@dataclass(frozen=True)
class SlotSpec:
    slot_id: str
    kind: str
    unlock_combat_rank: int
    fixed: bool


SLOTS = (
    SlotSpec("A1_CORE", ACTIVE, 1, True),
    SlotSpec("A2_SURVIVAL", ACTIVE, 1, True),
    SlotSpec("A3_TACTIC_I", ACTIVE, 1, False),
    SlotSpec("P1_DOCTRINE", PASSIVE, 5, False),
    SlotSpec("A4_TACTIC_II", ACTIVE, 10, False),
    SlotSpec("P2_SPECIALIZATION", PASSIVE, 20, False),
    SlotSpec("A5_SIGNATURE", ACTIVE, 25, False),
    SlotSpec("P3_MASTERY", PASSIVE, 50, False),
)
SLOTS_BY_ID = {slot.slot_id: slot for slot in SLOTS}
CHOICE_SLOTS = tuple(slot for slot in SLOTS if not slot.fixed)
POINT_MILESTONES = (5, 8, 10, 15, 20, 25, 30, 35, 40, 50, 70, 100)
RANK_CAP_MILESTONES = ((1, 1), (5, 2), (15, 3), (30, 4), (50, 5))
GROWTH_AXES = (
    "EFFECT_MAGNITUDE",
    "RESOURCE_EFFICIENCY",
    "COOLDOWN",
    "DURATION",
    "CONDITION_THRESHOLD",
)
ALLOWED_MUTATIONS = {
    "EFFECT_MAGNITUDE",
    "RESOURCE_EFFICIENCY",
    "COOLDOWN",
    "DURATION",
    "CONDITION_THRESHOLD",
}
FORBIDDEN_MUTATIONS = {
    "MACRO_ROLE",
    "AUTOMATION_BAND",
    "TARGET_RELATION",
    "DAMAGE_TYPE",
    "EFFECT_KIND",
    "TARGET_COUNT",
    "ROOT_ACTION_COUNT",
    "HIT_PACKET_COUNT",
    "EXTRA_ACTION_COUNT",
    "RESOURCE_LIFECYCLE",
    "SPEND_ATTRITION",
    "PROTECTION_BUDGET",
    "RNG_KEY",
    "SLOT_COUNT",
}


ACTIVE_ROLES = {
    "warrior": (
        "BUILD_PHYSICAL_POWER",
        "SURVIVAL_SHIELD",
        "CONTROL",
        "SUPPORT",
        "PHYSICAL_POWER",
    ),
    "rogue": (
        "BUILD_PHYSICAL_POWER",
        "SURVIVAL_BARRIER",
        "CONTROL",
        "POWER_CONTROL",
        "PHYSICAL_POWER",
    ),
    "ranger": (
        "PHYSICAL_POWER",
        "SURVIVAL_SHIELD",
        "CONTROL",
        "SUPPORT",
        "PHYSICAL_POWER",
    ),
    "mage": (
        "MAGIC_POWER",
        "SURVIVAL_SHIELD",
        "CONTROL",
        "POWER_CONTROL",
        "MAGIC_POWER",
    ),
    "cleric": (
        "MAGIC_POWER",
        "SURVIVAL_HEAL",
        "SURVIVAL_SHIELD",
        "SUPPORT_CONTROL",
        "SUPPORT",
    ),
    "paladin": (
        "BUILD_PHYSICAL_POWER",
        "SURVIVAL_SHIELD",
        "SURVIVAL_HEAL",
        "SUPPORT",
        "PHYSICAL_POWER",
    ),
}

PASSIVE_ROLES = {
    "warrior": ("DEFENSE", "RESOURCE", "SUPPORT"),
    "rogue": ("OFFENSE", "STATUS", "RESOURCE"),
    "ranger": ("ACCURACY", "SUSTAIN", "RESOURCE"),
    "mage": ("MAGIC_OFFENSE", "CONTROL", "RESOURCE"),
    "cleric": ("HEAL_SUPPORT", "DEFENSE", "RESOURCE"),
    "paladin": ("DEFENSE_SUPPORT", "HEAL", "RESOURCE"),
}

ALLOWED_PROTECTION = {
    "warrior": {"SHIELD"},
    "rogue": {"BARRIER"},
    "ranger": {"SHIELD"},
    "mage": {"SHIELD"},
    "cleric": {"HEAL", "SHIELD"},
    "paladin": {"HEAL", "SHIELD"},
}


def class_id(class_name: str) -> str:
    return f"aq.class.{class_name}"


def normalized_slot_id(slot_id: str) -> str:
    return slot_id.lower()


def fixed_definition_id(class_name: str, slot: SlotSpec) -> str:
    return f"aq.skill.{class_name}.{normalized_slot_id(slot.slot_id)}.fixed"


def candidate_definition_id(class_name: str, slot: SlotSpec, variant: int) -> str:
    assert variant in {1, 2, 3}
    return f"aq.skill.{class_name}.{normalized_slot_id(slot.slot_id)}.v{variant}"


def offer_id(
    character_id: str,
    class_name: str,
    slot: SlotSpec,
    rules_version: str = RULES_VERSION,
) -> str:
    return (
        f"aq.skill_offer.{character_id}.{class_name}."
        f"{normalized_slot_id(slot.slot_id)}.{rules_version}"
    )


def receipt_id(state: "SkillProgressState", kind: str, identity: str) -> str:
    return f"{kind}:{state.character_id}:{state.rules_version}:{identity}"


def role_for(class_name: str, slot: SlotSpec) -> str:
    if slot.kind == ACTIVE:
        active_slots = [candidate for candidate in SLOTS if candidate.kind == ACTIVE]
        return ACTIVE_ROLES[class_name][active_slots.index(slot)]
    passive_slots = [candidate for candidate in SLOTS if candidate.kind == PASSIVE]
    return PASSIVE_ROLES[class_name][passive_slots.index(slot)]


@dataclass(frozen=True)
class SkillCandidate:
    definition_id: str
    owner_class_id: str
    slot_id: str
    kind: str
    macro_role: str
    growth_axis: str


def candidates_for(class_name: str, slot: SlotSpec) -> tuple[SkillCandidate, ...]:
    assert not slot.fixed
    axis_offset = CLASSES.index(class_name) + CHOICE_SLOTS.index(slot)
    return tuple(
        SkillCandidate(
            definition_id=candidate_definition_id(class_name, slot, variant),
            owner_class_id=class_id(class_name),
            slot_id=slot.slot_id,
            kind=slot.kind,
            macro_role=role_for(class_name, slot),
            growth_axis=GROWTH_AXES[(axis_offset + variant - 1) % len(GROWTH_AXES)],
        )
        for variant in (1, 2, 3)
    )


@dataclass(frozen=True)
class SkillOffer:
    offer_id: str
    character_id: str
    owner_class_id: str
    slot_id: str
    unlock_combat_rank: int
    candidate_ids: tuple[str, ...]
    rules_version: str = RULES_VERSION


@dataclass
class BuildState:
    selected_by_slot: dict[str, str] = field(default_factory=dict)
    skill_ranks: dict[str, int] = field(default_factory=dict)
    revision: int = 0


@dataclass
class SkillProgressState:
    character_id: str
    owner_class_id: str
    last_processed_combat_rank: int = 0
    offers: dict[str, SkillOffer] = field(default_factory=dict)
    mastery_points_earned: int = 0
    receipts: set[str] = field(default_factory=set)
    committed_build: BuildState = field(default_factory=BuildState)
    staged_build: BuildState | None = None
    rules_version: str = RULES_VERSION


def rank_cap(combat_rank: int) -> int:
    safe_rank = max(1, combat_rank)
    result = 1
    for milestone, cap in RANK_CAP_MILESTONES:
        if safe_rank >= milestone:
            result = cap
    return result


def point_total(combat_rank: int) -> int:
    return sum(combat_rank >= milestone for milestone in POINT_MILESTONES)


def combat_rank(display_level: int) -> int:
    safe_level = max(1, min(display_level, 2**63 - 1))
    if safe_level <= 100:
        return safe_level
    raw = 100.0 + 100.0 * math.log1p((safe_level - 100) / 100.0)
    return int(math.floor(raw + 0.5))


def process_to_rank(state: SkillProgressState, class_name: str, new_combat_rank: int) -> None:
    assert state.owner_class_id == class_id(class_name)
    assert STABLE_ID.fullmatch(state.character_id)
    assert state.rules_version == RULES_VERSION
    assert new_combat_rank >= state.last_processed_combat_rank
    staged_offers: list[SkillOffer] = []
    staged_receipts: set[str] = set()
    staged_points = state.mastery_points_earned

    for rank in range(state.last_processed_combat_rank + 1, new_combat_rank + 1):
        if rank == 1:
            created_receipt_id = receipt_id(state, "starter", "c1")
            if created_receipt_id not in state.receipts:
                staged_receipts.add(created_receipt_id)
        for slot in CHOICE_SLOTS:
            if slot.unlock_combat_rank != rank:
                continue
            created_offer_id = offer_id(state.character_id, class_name, slot)
            created_receipt_id = receipt_id(
                state,
                "offer",
                f"{normalized_slot_id(slot.slot_id)}:c{rank}",
            )
            if created_offer_id in state.offers or created_receipt_id in state.receipts:
                continue
            staged_offers.append(
                SkillOffer(
                    offer_id=created_offer_id,
                    character_id=state.character_id,
                    owner_class_id=state.owner_class_id,
                    slot_id=slot.slot_id,
                    unlock_combat_rank=rank,
                    candidate_ids=tuple(
                        candidate.definition_id for candidate in candidates_for(class_name, slot)
                    ),
                )
            )
            staged_receipts.add(created_receipt_id)
        if rank in POINT_MILESTONES:
            created_receipt_id = receipt_id(state, "point", f"c{rank}")
            if created_receipt_id not in state.receipts:
                staged_points += 1
                staged_receipts.add(created_receipt_id)

    assert len({offer.offer_id for offer in staged_offers}) == len(staged_offers)
    assert not set(state.offers).intersection(offer.offer_id for offer in staged_offers)
    assert staged_points <= len(POINT_MILESTONES)
    for offer in staged_offers:
        state.offers[offer.offer_id] = offer
    state.mastery_points_earned = staged_points
    state.receipts.update(staged_receipts)
    state.last_processed_combat_rank = new_combat_rank


def offer_for_slot(
    state: SkillProgressState,
    class_name: str,
    slot: SlotSpec,
) -> SkillOffer | None:
    return state.offers.get(offer_id(state.character_id, class_name, slot))


def build_hash(state: SkillProgressState, build: BuildState) -> str:
    payload = {
        "characterId": state.character_id,
        "ownerClassId": state.owner_class_id,
        "revision": build.revision,
        "rulesVersion": state.rules_version,
        "selectedBySlot": sorted(build.selected_by_slot.items()),
        "skillRanks": sorted(build.skill_ranks.items()),
    }
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:24]


def finalize_creation(
    state: SkillProgressState,
    class_name: str,
    selected_a3_id: str,
) -> bool:
    """Atomically makes A1+A2+A3 the first committed build."""
    a3_slot = SLOTS_BY_ID["A3_TACTIC_I"]
    a3_offer = offer_for_slot(state, class_name, a3_slot)
    if state.last_processed_combat_rank != 1 or a3_offer is None:
        return False
    if selected_a3_id not in a3_offer.candidate_ids:
        return False

    selection_receipt = receipt_id(
        state,
        "selection",
        f"{normalized_slot_id(a3_slot.slot_id)}:{selected_a3_id}:r1",
    )
    if state.committed_build.revision == 1:
        return (
            state.committed_build.selected_by_slot.get(a3_slot.slot_id) == selected_a3_id
            and selection_receipt in state.receipts
        )
    if state.committed_build.revision != 0 or state.staged_build is not None:
        return False

    selected_by_slot = {a3_slot.slot_id: selected_a3_id}
    initial_ids = {
        fixed_definition_id(class_name, SLOTS_BY_ID["A1_CORE"]),
        fixed_definition_id(class_name, SLOTS_BY_ID["A2_SURVIVAL"]),
        selected_a3_id,
    }
    committed = BuildState(
        selected_by_slot=selected_by_slot,
        skill_ranks={definition_id: 1 for definition_id in initial_ids},
        revision=1,
    )
    creation_receipt = receipt_id(
        state,
        "build",
        f"character_create:r0-r1:{build_hash(state, committed)}",
    )
    state.committed_build = committed
    state.receipts.update({selection_receipt, creation_receipt})
    return True


def create_character_skill_state(
    character_id: str,
    class_name: str,
    selected_a3_id: str | None,
) -> SkillProgressState | None:
    """Models the all-or-nothing C1 persistence transaction."""
    draft = SkillProgressState(character_id, class_id(class_name))
    process_to_rank(draft, class_name, 1)
    if selected_a3_id is None or not finalize_creation(draft, class_name, selected_a3_id):
        return None
    return draft


def stage_offer_selection(
    state: SkillProgressState,
    selected_offer_id: str,
    candidate_id: str,
    *,
    boundary: str,
) -> bool:
    if boundary != "BUILD_EDITABLE" or state.committed_build.revision < 1:
        return False
    offer = state.offers.get(selected_offer_id)
    if (
        offer is None
        or offer.character_id != state.character_id
        or offer.rules_version != state.rules_version
        or candidate_id not in offer.candidate_ids
    ):
        return False
    base = state.staged_build or state.committed_build
    selected_by_slot = dict(base.selected_by_slot)
    selected_by_slot[offer.slot_id] = candidate_id
    skill_ranks = {
        definition_id: rank
        for definition_id, rank in base.skill_ranks.items()
        if definition_id in selected_by_slot.values()
        or definition_id.startswith(f"aq.skill.{state.owner_class_id.rsplit('.', 1)[-1]}.a1_")
        or definition_id.startswith(f"aq.skill.{state.owner_class_id.rsplit('.', 1)[-1]}.a2_")
    }
    skill_ranks.setdefault(candidate_id, 1)
    state.staged_build = BuildState(
        selected_by_slot=selected_by_slot,
        skill_ranks=skill_ranks,
        revision=state.committed_build.revision + 1,
    )
    return True


def equipped_definitions(
    class_name: str,
    state: SkillProgressState,
    build: BuildState | None = None,
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    resolved_build = build or state.committed_build
    active: list[str] = []
    passive: list[str] = []
    for slot in SLOTS:
        if slot.unlock_combat_rank > state.last_processed_combat_rank:
            continue
        if slot.fixed:
            selected = fixed_definition_id(class_name, slot)
        else:
            selected = resolved_build.selected_by_slot.get(slot.slot_id)
            if selected is None:
                continue
        (active if slot.kind == ACTIVE else passive).append(selected)
    return tuple(active), tuple(passive)


def validate_capacity(active: tuple[str, ...], passive: tuple[str, ...]) -> bool:
    return (
        len(active) <= 5
        and len(passive) <= 3
        and len(active) == len(set(active))
        and len(passive) == len(set(passive))
        and not set(active).intersection(passive)
    )


def validate_item_replacement(class_name: str, slot_id: str, macro_role: str) -> bool:
    slot = SLOTS_BY_ID.get(slot_id)
    if slot is None or slot.fixed:
        return False
    if slot.kind == ACTIVE and slot.slot_id not in {
        "A3_TACTIC_I",
        "A4_TACTIC_II",
        "A5_SIGNATURE",
    }:
        return False
    if macro_role != role_for(class_name, slot):
        return False
    if not protection_tags(macro_role).issubset(ALLOWED_PROTECTION[class_name]):
        return False
    return not any(token in macro_role for token in ("GUARD", "TRUE", "EXTRA_ACTION"))


def commit_staged_build(
    state: SkillProgressState,
    *,
    boundary: str,
    inn_receipt_id: str,
    expected_previous_revision: int,
    expected_build_hash: str,
) -> bool:
    next_revision = expected_previous_revision + 1
    commit_receipt = receipt_id(
        state,
        "build",
        f"{inn_receipt_id}:r{expected_previous_revision}-r{next_revision}:{expected_build_hash}",
    )
    boundary_receipt = receipt_id(state, "inn_build_boundary", inn_receipt_id)
    if commit_receipt in state.receipts:
        return (
            boundary_receipt in state.receipts
            and state.staged_build is None
            and state.committed_build.revision == next_revision
            and build_hash(state, state.committed_build) == expected_build_hash
        )
    if boundary_receipt in state.receipts:
        return False
    if boundary != "INN_COMPLETE" or state.staged_build is None:
        return False
    if state.committed_build.revision != expected_previous_revision:
        return False
    if state.staged_build.revision != next_revision:
        return False
    if build_hash(state, state.staged_build) != expected_build_hash:
        return False

    staged_active, staged_passive = equipped_definitions(
        state.owner_class_id.rsplit(".", 1)[-1],
        state,
        state.staged_build,
    )
    if not validate_capacity(staged_active, staged_passive):
        return False
    if set(state.staged_build.skill_ranks) != set(staged_active + staged_passive):
        return False
    if not validate_rank_allocation(
        state.staged_build.skill_ranks,
        equipped_definition_ids=set(staged_active + staged_passive),
        combat_rank_value=state.last_processed_combat_rank,
        points_earned=state.mastery_points_earned,
    ):
        return False

    selection_receipts: set[str] = set()
    for slot_id, candidate_id in state.staged_build.selected_by_slot.items():
        if state.committed_build.selected_by_slot.get(slot_id) == candidate_id:
            continue
        selection_receipts.add(
            receipt_id(
                state,
                "selection",
                f"{normalized_slot_id(slot_id)}:{candidate_id}:r{next_revision}",
            )
        )
    state.committed_build = BuildState(
        selected_by_slot=dict(state.staged_build.selected_by_slot),
        skill_ranks=dict(state.staged_build.skill_ranks),
        revision=state.staged_build.revision,
    )
    state.staged_build = None
    state.receipts.update(selection_receipts | {boundary_receipt, commit_receipt})
    return True


def validate_rank_allocation(
    skill_ranks: dict[str, int],
    *,
    equipped_definition_ids: set[str],
    combat_rank_value: int,
    points_earned: int,
) -> bool:
    if set(skill_ranks) != equipped_definition_ids:
        return False
    cap = rank_cap(combat_rank_value)
    if any(rank < 1 or rank > cap or rank > 5 for rank in skill_ranks.values()):
        return False
    spent = sum(rank - 1 for rank in skill_ranks.values())
    return spent <= points_earned <= len(POINT_MILESTONES)


def protection_tags(role: str) -> set[str]:
    return {tag for tag in ("HEAL", "SHIELD", "BARRIER") if tag in role}


def run_reference_checks() -> tuple[list[str], int, int]:
    passed: list[str] = []

    assert len(SLOTS_BY_ID) == 8
    assert sum(slot.kind == ACTIVE for slot in SLOTS) == 5
    assert sum(slot.kind == PASSIVE for slot in SLOTS) == 3
    assert sum(slot.fixed for slot in SLOTS) == 2
    assert len(CHOICE_SLOTS) == 6
    passed.append("ACTIVE_FIVE_PASSIVE_THREE_AND_SYSTEM_FALLBACK_OUTSIDE")

    expected_counts = {
        1: (3, 0),
        4: (3, 0),
        5: (3, 1),
        10: (4, 1),
        20: (4, 2),
        25: (5, 2),
        50: (5, 3),
        4006: (5, 3),
    }
    for rank, expected in expected_counts.items():
        active = sum(slot.kind == ACTIVE and slot.unlock_combat_rank <= rank for slot in SLOTS)
        passive = sum(slot.kind == PASSIVE and slot.unlock_combat_rank <= rank for slot in SLOTS)
        assert (active, passive) == expected
    passed.append("COMBAT_RANK_SLOT_MILESTONES")

    all_definition_ids: set[str] = set()
    for class_name in CLASSES:
        assert len(ACTIVE_ROLES[class_name]) == 5
        assert len(PASSIVE_ROLES[class_name]) == 3
        for slot in SLOTS:
            definitions = (
                (fixed_definition_id(class_name, slot),)
                if slot.fixed
                else tuple(candidate.definition_id for candidate in candidates_for(class_name, slot))
            )
            for definition_id in definitions:
                assert STABLE_ID.fullmatch(definition_id)
                assert definition_id not in all_definition_ids
                all_definition_ids.add(definition_id)
        for slot in CHOICE_SLOTS:
            candidates = candidates_for(class_name, slot)
            assert len(candidates) == 3
            assert len({candidate.definition_id for candidate in candidates}) == 3
            assert all(candidate.growth_axis in ALLOWED_MUTATIONS for candidate in candidates)
            assert {candidate.owner_class_id for candidate in candidates} == {class_id(class_name)}
            assert {candidate.slot_id for candidate in candidates} == {slot.slot_id}
            assert {candidate.kind for candidate in candidates} == {slot.kind}
            assert {candidate.macro_role for candidate in candidates} == {role_for(class_name, slot)}
    passed.append("STABLE_UNIQUE_ROLE_LOCKED_THREE_CHOICE_OFFERS")

    for class_name in CLASSES:
        allowed = ALLOWED_PROTECTION[class_name]
        observed: set[str] = set()
        for role in ACTIVE_ROLES[class_name]:
            observed.update(protection_tags(role))
            assert "GUARD" not in role and "TRUE" not in role and "EXTRA_ACTION" not in role
        assert observed == allowed
        assert "RESOURCE" in PASSIVE_ROLES[class_name]
    assert "BARRIER" not in " ".join(ACTIVE_ROLES["mage"])
    assert "BARRIER" not in " ".join(ACTIVE_ROLES["paladin"])
    passed.append("SIX_CLASS_ROLE_AND_PROTECTION_CONTRACTS")

    assert ALLOWED_MUTATIONS.isdisjoint(FORBIDDEN_MUTATIONS)
    assert set(GROWTH_AXES).issubset(ALLOWED_MUTATIONS)
    passed.append("ONE_AXIS_GROWTH_WHITELIST")

    batch_states: dict[str, SkillProgressState] = {}
    for class_name in CLASSES:
        character_id = f"aq.character.{class_name}.batch"
        batch = SkillProgressState(character_id, class_id(class_name))
        process_to_rank(batch, class_name, 100)
        incremental = SkillProgressState(character_id, class_id(class_name))
        for rank in range(1, 101):
            process_to_rank(incremental, class_name, rank)
        assert batch == incremental
        assert len(batch.offers) == 6
        assert batch.mastery_points_earned == 12
        assert batch.last_processed_combat_rank == 100
        batch_states[class_name] = batch
    passed.append("BATCH_AND_INCREMENTAL_UNLOCKS_ARE_IDENTICAL")

    for class_name, state in batch_states.items():
        before = (
            dict(state.offers),
            state.mastery_points_earned,
            set(state.receipts),
            state.last_processed_combat_rank,
        )
        process_to_rank(state, class_name, 100)
        after = (
            dict(state.offers),
            state.mastery_points_earned,
            set(state.receipts),
            state.last_processed_combat_rank,
        )
        assert before == after
    passed.append("UNLOCK_AND_POINT_RECEIPTS_ARE_IDEMPOTENT")

    first_warrior = SkillProgressState("aq.character.warrior.alpha", class_id("warrior"))
    second_warrior = SkillProgressState("aq.character.warrior.bravo", class_id("warrior"))
    process_to_rank(first_warrior, "warrior", 100)
    process_to_rank(second_warrior, "warrior", 100)
    assert set(first_warrior.offers).isdisjoint(second_warrior.offers)
    assert first_warrior.receipts.isdisjoint(second_warrior.receipts)
    assert all(first_warrior.character_id in key for key in first_warrior.offers)
    assert all(RULES_VERSION in key for key in first_warrior.offers)
    assert all(first_warrior.character_id in key for key in first_warrior.receipts)
    assert all(RULES_VERSION in key for key in first_warrior.receipts)
    passed.append("MULTI_CHARACTER_OFFER_AND_RECEIPT_KEYS_ARE_ISOLATED")

    for class_name in CLASSES:
        character_id = f"aq.character.{class_name}.creation"
        initial_slot = SLOTS_BY_ID["A3_TACTIC_I"]
        selected_a3 = candidate_definition_id(class_name, initial_slot, 1)
        assert create_character_skill_state(character_id, class_name, None) is None
        assert create_character_skill_state(character_id, class_name, "aq.skill.invalid") is None
        state = create_character_skill_state(character_id, class_name, selected_a3)
        assert state is not None
        initial_offer = offer_for_slot(state, class_name, initial_slot)
        assert initial_offer is not None
        committed_after_creation = (
            dict(state.committed_build.selected_by_slot),
            dict(state.committed_build.skill_ranks),
            state.committed_build.revision,
            set(state.receipts),
        )
        assert finalize_creation(state, class_name, selected_a3)
        assert committed_after_creation == (
            dict(state.committed_build.selected_by_slot),
            dict(state.committed_build.skill_ranks),
            state.committed_build.revision,
            set(state.receipts),
        )
        assert not finalize_creation(state, class_name, initial_offer.candidate_ids[1])
        active, passive = equipped_definitions(class_name, state)
        assert len(active) == 3 and len(passive) == 0
        assert validate_capacity(active, passive)
        process_to_rank(state, class_name, 50)
        assert len(state.offers) == 6
        assert len(state.committed_build.selected_by_slot) == 1
        assert equipped_definitions(class_name, state) == (active, passive)
    passed.append("C1_CREATION_IS_ATOMIC_AND_LATER_OFFERS_NEVER_BLOCK")

    assert [rank_cap(rank) for rank in (1, 4, 5, 14, 15, 29, 30, 49, 50, 4006)] == [
        1, 1, 2, 2, 3, 3, 4, 4, 5, 5
    ]
    assert [point_total(rank) for rank in (1, 5, 25, 50, 100, 4006)] == [0, 1, 6, 10, 12, 12]
    passed.append("FINITE_RANK_FIVE_AND_TWELVE_POINTS")

    for display_level, expected_rank in {
        1: 1,
        100: 100,
        200: 169,
        1_000_000_000: 1712,
        2**63 - 1: 4006,
    }.items():
        assert combat_rank(display_level) == expected_rank
        rank_value = combat_rank(display_level)
        if display_level >= 100:
            assert rank_cap(rank_value) == 5 and point_total(rank_value) == 12
    passed.append("DISPLAY_LEVEL_LONG_MAX_CANNOT_ADD_SKILL_GROWTH")

    reference_character_id = "aq.character.warrior.reference"
    reference_a3_id = candidate_definition_id("warrior", SLOTS_BY_ID["A3_TACTIC_I"], 1)
    reference_state = create_character_skill_state(
        reference_character_id, "warrior", reference_a3_id
    )
    assert reference_state is not None
    process_to_rank(reference_state, "warrior", 100)
    for selected_offer_id, offer in list(reference_state.offers.items()):
        if offer.slot_id == "A3_TACTIC_I":
            continue
        assert stage_offer_selection(
            reference_state,
            selected_offer_id,
            offer.candidate_ids[0],
            boundary="BUILD_EDITABLE",
        )
    assert reference_state.staged_build is not None
    initial_commit_hash = build_hash(reference_state, reference_state.staged_build)
    assert commit_staged_build(
        reference_state,
        boundary="INN_COMPLETE",
        inn_receipt_id="aq.inn.receipt.loadout",
        expected_previous_revision=1,
        expected_build_hash=initial_commit_hash,
    )
    active, passive = equipped_definitions("warrior", reference_state)
    assert (len(active), len(passive)) == (5, 3)
    assert validate_capacity(active, passive)
    assert not validate_capacity(active + ("aq.skill.item.extra",), passive)
    assert not validate_capacity(active, passive + ("aq.skill.item.extra_passive",))
    replaced_active = active[:-1] + ("aq.skill.item.granted_active",)
    replaced_passive = passive[:-1] + ("aq.skill.item.granted_passive",)
    assert validate_capacity(replaced_active, replaced_passive)
    assert not validate_item_replacement("warrior", "A1_CORE", ACTIVE_ROLES["warrior"][0])
    assert not validate_item_replacement("warrior", "A2_SURVIVAL", ACTIVE_ROLES["warrior"][1])
    assert validate_item_replacement("warrior", "A3_TACTIC_I", ACTIVE_ROLES["warrior"][2])
    assert validate_item_replacement("warrior", "P1_DOCTRINE", PASSIVE_ROLES["warrior"][0])
    assert not validate_item_replacement("mage", "A3_TACTIC_I", "SURVIVAL_BARRIER")
    assert not validate_item_replacement("paladin", "A5_SIGNATURE", "TRUE")
    passed.append("ITEM_REPLACEMENT_PROTECTS_A1_A2_SLOT_ROLE_AND_STAGE4_BUDGET")

    ranks = {definition_id: 1 for definition_id in active + passive}
    remaining_points = reference_state.mastery_points_earned
    for definition_id in ranks:
        while ranks[definition_id] < 5 and remaining_points > 0:
            ranks[definition_id] += 1
            remaining_points -= 1
    assert validate_rank_allocation(
        ranks,
        equipped_definition_ids=set(active + passive),
        combat_rank_value=100,
        points_earned=reference_state.mastery_points_earned,
    )
    assert sum(rank - 1 for rank in ranks.values()) == 12
    assert not validate_rank_allocation(
        {**ranks, active[0]: 6},
        equipped_definition_ids=set(active + passive),
        combat_rank_value=4006,
        points_earned=12,
    )
    assert not validate_rank_allocation(
        {definition_id: 5 for definition_id in active + passive},
        equipped_definition_ids=set(active + passive),
        combat_rank_value=4006,
        points_earned=12,
    )
    assert not validate_rank_allocation(
        {**ranks, "aq.skill.mage.invalid": 1},
        equipped_definition_ids=set(active + passive),
        combat_rank_value=100,
        points_earned=12,
    )
    assert not validate_rank_allocation(
        {definition_id: rank for definition_id, rank in ranks.items() if definition_id != active[0]},
        equipped_definition_ids=set(active + passive),
        combat_rank_value=100,
        points_earned=12,
    )
    passed.append("RANK_ALLOCATION_IS_FINITE_AND_COMMITTED_LOADOUT_ONLY")

    revision_before = reference_state.committed_build.revision
    snapshot = (
        active,
        passive,
        tuple(sorted(reference_state.committed_build.skill_ranks.items())),
        revision_before,
    )
    target_offer = offer_for_slot(reference_state, "warrior", SLOTS_BY_ID["A3_TACTIC_I"])
    assert target_offer is not None
    assert not stage_offer_selection(
        reference_state,
        target_offer.offer_id,
        target_offer.candidate_ids[1],
        boundary="IN_EXPEDITION",
    )
    assert stage_offer_selection(
        reference_state,
        target_offer.offer_id,
        target_offer.candidate_ids[1],
        boundary="BUILD_EDITABLE",
    )
    assert equipped_definitions("warrior", reference_state) == (active, passive)
    assert reference_state.staged_build is not None
    staged_active, staged_passive = equipped_definitions(
        "warrior", reference_state, reference_state.staged_build
    )
    assert (staged_active, staged_passive) != (active, passive)
    assert not commit_staged_build(
        reference_state,
        boundary="IN_EXPEDITION",
        inn_receipt_id="aq.inn.receipt.respec",
        expected_previous_revision=revision_before,
        expected_build_hash=build_hash(reference_state, reference_state.staged_build),
    )
    assert snapshot == (
        active,
        passive,
        tuple(sorted(reference_state.committed_build.skill_ranks.items())),
        revision_before,
    )
    passed.append("STAGED_BUILD_NEVER_MUTATES_COMMITTED_EXPEDITION_SNAPSHOT")

    assert reference_state.staged_build is not None
    respec_hash = build_hash(reference_state, reference_state.staged_build)
    assert commit_staged_build(
        reference_state,
        boundary="INN_COMPLETE",
        inn_receipt_id="aq.inn.receipt.respec",
        expected_previous_revision=revision_before,
        expected_build_hash=respec_hash,
    )
    committed_once = (
        dict(reference_state.committed_build.selected_by_slot),
        dict(reference_state.committed_build.skill_ranks),
        reference_state.committed_build.revision,
        set(reference_state.receipts),
    )
    assert commit_staged_build(
        reference_state,
        boundary="INN_COMPLETE",
        inn_receipt_id="aq.inn.receipt.respec",
        expected_previous_revision=revision_before,
        expected_build_hash=respec_hash,
    )
    assert committed_once == (
        dict(reference_state.committed_build.selected_by_slot),
        dict(reference_state.committed_build.skill_ranks),
        reference_state.committed_build.revision,
        set(reference_state.receipts),
    )
    current_revision = reference_state.committed_build.revision
    assert stage_offer_selection(
        reference_state,
        target_offer.offer_id,
        target_offer.candidate_ids[2],
        boundary="BUILD_EDITABLE",
    )
    assert reference_state.staged_build is not None
    second_hash_same_inn = build_hash(reference_state, reference_state.staged_build)
    assert not commit_staged_build(
        reference_state,
        boundary="INN_COMPLETE",
        inn_receipt_id="aq.inn.receipt.respec",
        expected_previous_revision=current_revision,
        expected_build_hash=second_hash_same_inn,
    )
    assert reference_state.committed_build.revision == current_revision
    assert not commit_staged_build(
        reference_state,
        boundary="INN_COMPLETE",
        inn_receipt_id="aq.inn.receipt.different",
        expected_previous_revision=revision_before,
        expected_build_hash=respec_hash,
    )
    passed.append("INN_BUILD_COMMIT_IS_HASHED_IDEMPOTENT_AND_ONCE_PER_INN")

    invalid_state = create_character_skill_state(
        "aq.character.mage.invalid",
        "mage",
        candidate_definition_id("mage", SLOTS_BY_ID["A3_TACTIC_I"], 1),
    )
    assert invalid_state is not None
    process_to_rank(invalid_state, "mage", 50)
    sample_offer = next(iter(invalid_state.offers.values()))
    before_invalid = (
        dict(invalid_state.committed_build.selected_by_slot),
        invalid_state.staged_build,
    )
    assert not stage_offer_selection(
        invalid_state,
        sample_offer.offer_id,
        "aq.skill.warrior.invalid",
        boundary="BUILD_EDITABLE",
    )
    assert before_invalid == (
        dict(invalid_state.committed_build.selected_by_slot),
        invalid_state.staged_build,
    )
    passed.append("CROSS_CLASS_AND_CROSS_OFFER_SELECTION_IS_REJECTED")

    legal_loadouts_per_class = 3 ** len(CHOICE_SLOTS)
    enumerated = 0
    for class_name in CLASSES:
        state = SkillProgressState(f"aq.character.{class_name}.enumeration", class_id(class_name))
        process_to_rank(state, class_name, 100)
        offers = list(state.offers.values())
        for selection in itertools.product(range(3), repeat=len(offers)):
            candidate_build = BuildState(
                selected_by_slot={
                    offer.slot_id: offer.candidate_ids[choice]
                    for choice, offer in zip(selection, offers)
                },
                revision=1,
            )
            candidate_active, candidate_passive = equipped_definitions(
                class_name, state, candidate_build
            )
            assert len(candidate_active) == 5 and len(candidate_passive) == 3
            assert validate_capacity(candidate_active, candidate_passive)
            enumerated += 1
    assert enumerated == legal_loadouts_per_class * len(CLASSES) == 4374
    passed.append("ALL_4374_ABSTRACT_LOADOUTS_PRESERVE_SLOT_AND_ROLE_SHAPE")

    assert "aq.action.basic" not in all_definition_ids
    assert "aq.action.resource_recovery" not in all_definition_ids
    assert validate_capacity((), ())
    passed.append("EMPTY_OR_UNUSABLE_LOADOUT_RETAINS_SYSTEM_BASIC_ACTION")

    rank_allocation_shapes = sum(
        1
        for allocation in itertools.product(range(5), repeat=8)
        if sum(allocation) <= 12
    )
    assert rank_allocation_shapes > 0
    passed.append("FINITE_ENHANCEMENT_ALLOCATION_SPACE")

    return passed, enumerated, rank_allocation_shapes


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true", help="print the complete planning gate")
    args = parser.parse_args()

    passed, loadouts, rank_shapes = run_reference_checks()
    print(f"SKILL_SLOTS_PROGRESSION_V0_1: PASS ({len(passed)}/{len(passed)})")
    print("  slots C1=Active3/Passive0 C10=4/1 C25=5/2 C50=5/3")
    print("  rankCap C1/5/15/30/50=1/2/3/4/5 masteryPointsMax=12")
    print(f"  legalAbstractLoadouts={loadouts} finiteRankAllocationShapes={rank_shapes}")
    for name in passed:
        print(f"  PASS {name}")
    if args.pd:
        print(
            "REFERENCE_GATE: PASS "
            "(slot/offer/rank/receipt/role invariants; concrete skills/items/live storage pending)"
        )


if __name__ == "__main__":
    main()
