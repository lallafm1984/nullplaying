#!/usr/bin/env python3
"""Design review for AlarmQuest C1 skill Rank2-5 growth rows v0.1.

The approved C1 Rank1 numerical catalog remains unchanged. Six growth-axis tags are
amended before live implementation because their original EFFECT_MAGNITUDE axis has
no legal Rank5 headroom under the Stage4 rolling-ten attack budgets.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import itertools
import json
import statistics
import sys
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
DOCUMENT = ROOT / "PRODUCT_MEETING_C1_SKILL_RANK_GROWTH_v0.1.md"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


c1 = load_module("c1_rank1_reference", TOOLS / "c1_skill_vertical_slice_v0_1_review.py")


@dataclass(frozen=True)
class RankPlan:
    definition_id: str
    growth_axis: str
    field: str
    values: tuple[int, int, int, int, int]
    direction: str
    amended_from_axis: str | None = None


def plan(
    definition_id: str,
    growth_axis: str,
    field: str,
    values: tuple[int, int, int, int, int],
    direction: str,
    amended_from_axis: str | None = None,
) -> RankPlan:
    return RankPlan(definition_id, growth_axis, field, values, direction, amended_from_axis)


RANK_PLANS = (
    plan("aq.skill.warrior.a1.frontlinedrive", "RESOURCE_EFFICIENCY", "resource_cost_bps", (3000, 2900, 2800, 2700, 2600), "DECREASE", "EFFECT_MAGNITUDE"),
    plan("aq.skill.warrior.a2.ironstance", "EFFECT_MAGNITUDE", "protection_amount", (800, 850, 900, 950, 1000), "INCREASE"),
    plan("aq.skill.warrior.a3.armorbreak", "CONDITION_THRESHOLD", "target_hp_floor_bps", (2500, 2625, 2750, 2875, 3000), "INCREASE"),
    plan("aq.skill.warrior.a3.breakresolve", "RESOURCE_EFFICIENCY", "resource_cost_bps", (3500, 3475, 3450, 3425, 3400), "DECREASE"),
    plan("aq.skill.warrior.a3.sightpressure", "CONDITION_THRESHOLD", "target_hit_floor_bps", (9000, 8937, 8875, 8812, 8750), "DECREASE"),

    plan("aq.skill.rogue.a1.seizeopening", "RESOURCE_EFFICIENCY", "resource_cost_bps", (2500, 2425, 2350, 2275, 2200), "DECREASE", "EFFECT_MAGNITUDE"),
    plan("aq.skill.rogue.a2.narrowescape", "CONDITION_THRESHOLD", "hp_threshold_bps", (6000, 6250, 6500, 6750, 7000), "INCREASE"),
    plan("aq.skill.rogue.a3.exposegap", "CONDITION_THRESHOLD", "target_hp_floor_bps", (2500, 2625, 2750, 2875, 3000), "INCREASE", "EFFECT_MAGNITUDE"),
    plan("aq.skill.rogue.a3.wristcheck", "RESOURCE_EFFICIENCY", "resource_cost_bps", (3000, 2962, 2925, 2887, 2850), "DECREASE"),
    plan("aq.skill.rogue.a3.blursight", "CONDITION_THRESHOLD", "target_hit_floor_bps", (8500, 8450, 8400, 8350, 8300), "DECREASE"),

    plan("aq.skill.ranger.a1.steadyshot", "RESOURCE_EFFICIENCY", "resource_cost_bps", (1500, 1450, 1400, 1350, 1300), "DECREASE", "EFFECT_MAGNITUDE"),
    plan("aq.skill.ranger.a2.coverstance", "EFFECT_MAGNITUDE", "protection_amount", (400, 450, 500, 550, 600), "INCREASE"),
    plan("aq.skill.ranger.a3.markweakness", "CONDITION_THRESHOLD", "target_hp_floor_bps", (2500, 2625, 2750, 2875, 3000), "INCREASE", "EFFECT_MAGNITUDE"),
    plan("aq.skill.ranger.a3.suppressmark", "RESOURCE_EFFICIENCY", "resource_cost_bps", (1800, 1775, 1750, 1725, 1700), "DECREASE"),
    plan("aq.skill.ranger.a3.disruptmark", "CONDITION_THRESHOLD", "target_hit_floor_bps", (8500, 8450, 8400, 8350, 8300), "DECREASE"),

    plan("aq.skill.mage.a1.arcanepulse", "EFFECT_MAGNITUDE", "action_coefficient_bps", (11600, 11612, 11625, 11637, 11650), "INCREASE"),
    plan("aq.skill.mage.a2.arcaneveil", "EFFECT_MAGNITUDE", "protection_amount", (400, 450, 500, 550, 600), "INCREASE"),
    plan("aq.skill.mage.a3.arcaneexposure", "CONDITION_THRESHOLD", "target_hp_floor_bps", (2500, 2562, 2625, 2687, 2750), "INCREASE", "EFFECT_MAGNITUDE"),
    plan("aq.skill.mage.a3.powerdamping", "EFFECT_MAGNITUDE", "status_magnitude_bps", (800, 837, 875, 912, 950), "INCREASE", "RESOURCE_EFFICIENCY"),
    plan("aq.skill.mage.a3.sensedistortion", "EFFECT_MAGNITUDE", "status_magnitude_bps", (600, 675, 750, 825, 900), "INCREASE", "CONDITION_THRESHOLD"),

    plan("aq.skill.cleric.a1.sacredradiance", "EFFECT_MAGNITUDE", "action_coefficient_bps", (10400, 10500, 10600, 10700, 10800), "INCREASE"),
    plan("aq.skill.cleric.a2.restoringprayer", "EFFECT_MAGNITUDE", "protection_amount", (800, 850, 900, 950, 1000), "INCREASE"),
    plan("aq.skill.cleric.a3.earlyward", "CONDITION_THRESHOLD", "hp_threshold_bps", (8500, 8562, 8625, 8687, 8750), "INCREASE"),
    plan("aq.skill.cleric.a3.focusedward", "EFFECT_MAGNITUDE", "protection_amount", (550, 562, 575, 587, 600), "INCREASE"),
    plan("aq.skill.cleric.a3.lastward", "CONDITION_THRESHOLD", "hp_threshold_bps", (7000, 6937, 6875, 6812, 6750), "DECREASE"),

    plan("aq.skill.paladin.a1.convictionstrike", "EFFECT_MAGNITUDE", "action_coefficient_bps", (10800, 10900, 11000, 11100, 11200), "INCREASE"),
    plan("aq.skill.paladin.a2.guardianoath", "EFFECT_MAGNITUDE", "protection_amount", (700, 750, 800, 850, 900), "INCREASE"),
    plan("aq.skill.paladin.a3.earlymercy", "CONDITION_THRESHOLD", "hp_threshold_bps", (7250, 7312, 7375, 7437, 7500), "INCREASE"),
    plan("aq.skill.paladin.a3.measuredmercy", "EFFECT_MAGNITUDE", "protection_amount", (750, 756, 762, 768, 775), "INCREASE"),
    plan("aq.skill.paladin.a3.crisismercy", "CONDITION_THRESHOLD", "hp_threshold_bps", (6500, 6525, 6550, 6575, 6600), "INCREASE"),
)

PLAN_BY_ID = {row.definition_id: row for row in RANK_PLANS}
BASE_BY_ID = {skill.definition_id: skill for skill in c1.SKILLS}


POINT_MILESTONES = (5, 8, 10, 15, 20, 25, 30, 35, 40, 50, 70, 100)


def rank_cap(combat_rank: int) -> int:
    if combat_rank < 5:
        return 1
    if combat_rank < 15:
        return 2
    if combat_rank < 30:
        return 3
    if combat_rank < 50:
        return 4
    return 5


def points_earned(combat_rank: int) -> int:
    return sum(combat_rank >= milestone for milestone in POINT_MILESTONES)


def canonical_hash() -> str:
    payload = [
        {
            "definitionId": row.definition_id,
            "growthAxis": row.growth_axis,
            "field": row.field,
            "values": row.values,
            "direction": row.direction,
            "amendedFromAxis": row.amended_from_axis,
        }
        for row in sorted(RANK_PLANS, key=lambda candidate: candidate.definition_id)
    ]
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()
    return hashlib.sha256(encoded).hexdigest()


def skill_at_rank(skill, rank: int):
    assert 1 <= rank <= 5
    row = PLAN_BY_ID[skill.definition_id]
    value = row.values[rank - 1]
    changes = {
        "growth_axis": row.growth_axis,
        "rank_axis_values": row.values,
        row.field: value,
    }
    if row.field == "action_coefficient_bps":
        changes["attack_budget_equivalent_bps"] = value
    return replace(skill, **changes)


def ranked_catalog(ranks: dict[str, int]):
    return tuple(skill_at_rank(skill, ranks.get(skill.definition_id, 1)) for skill in c1.SKILLS)


def class_skills(catalog: Iterable, class_name: str, slot_id: str | None = None):
    return tuple(
        skill
        for skill in catalog
        if skill.owner_class == class_name and (slot_id is None or skill.slot_id == slot_id)
    )


def attack_envelope(catalog: tuple, class_name: str) -> int:
    a1 = class_skills(catalog, class_name, "A1_CORE")[0]
    a1_uses = c1.max_uses_in_ten(a1.cooldown_turns)
    candidates = class_skills(catalog, class_name, "A3_TACTIC_I")
    max_a3 = max(candidates, key=lambda skill: skill.attack_budget_equivalent_bps)
    a3_uses = min(
        c1.max_uses_in_ten(max_a3.cooldown_turns),
        max_a3.max_activations_per_encounter,
    )
    basics = 10 - a1_uses - a3_uses
    assert basics >= 0
    return (
        a1_uses * a1.attack_budget_equivalent_bps
        + a3_uses * max_a3.attack_budget_equivalent_bps
        + basics * 10_000
    )


def legal_c1_allocations(combat_rank: int) -> tuple[tuple[int, int, int], ...]:
    cap = rank_cap(combat_rank)
    points = points_earned(combat_rank)
    return tuple(
        ranks
        for ranks in itertools.product(range(1, cap + 1), repeat=3)
        if sum(rank - 1 for rank in ranks) <= points
    )


def assert_rank1_value_matches(row: RankPlan, skill) -> None:
    assert getattr(skill, row.field) == row.values[0], (
        row.definition_id,
        row.field,
        getattr(skill, row.field),
        row.values[0],
    )


def check_catalog_and_amendments() -> None:
    assert len(RANK_PLANS) == len(c1.SKILLS) == 30
    assert len(PLAN_BY_ID) == 30
    assert set(PLAN_BY_ID) == set(BASE_BY_ID)
    amended = [row for row in RANK_PLANS if row.amended_from_axis]
    assert len(amended) == 8
    assert {row.definition_id for row in amended} == {
        "aq.skill.warrior.a1.frontlinedrive",
        "aq.skill.rogue.a1.seizeopening",
        "aq.skill.ranger.a1.steadyshot",
        "aq.skill.rogue.a3.exposegap",
        "aq.skill.ranger.a3.markweakness",
        "aq.skill.mage.a3.arcaneexposure",
        "aq.skill.mage.a3.powerdamping",
        "aq.skill.mage.a3.sensedistortion",
    }
    for row in RANK_PLANS:
        skill = BASE_BY_ID[row.definition_id]
        assert_rank1_value_matches(row, skill)
        assert len(row.values) == 5 and len(set(row.values)) == 5
        assert row.growth_axis in c1.ALLOWED_GROWTH_AXES
        assert row.direction in {"INCREASE", "DECREASE"}
        if row.direction == "INCREASE":
            assert list(row.values) == sorted(row.values)
        else:
            assert list(row.values) == sorted(row.values, reverse=True)
        if row.amended_from_axis:
            assert skill.growth_axis == row.amended_from_axis
        else:
            assert skill.growth_axis == row.growth_axis
    document = DOCUMENT.read_text(encoding="utf-8")
    assert canonical_hash() in document
    assert "PD 3성향·500시드 8/8 최종 GO" in document
    table_lines = [line for line in document.splitlines() if line.startswith("|")]
    for row in RANK_PLANS:
        name = BASE_BY_ID[row.definition_id].name_ko
        assert any(
            name in line and all(f"{value:,}" in line for value in row.values)
            for line in table_lines
        ), f"document rank row mismatch: {row.definition_id}"


def check_axis_changes_only_one_gameplay_field() -> None:
    gameplay_fields = (
        "resource_cost_bps",
        "action_coefficient_bps",
        "protection_amount",
        "hp_threshold_bps",
        "target_hp_floor_bps",
        "target_hit_floor_bps",
        "status_magnitude_bps",
    )
    for row in RANK_PLANS:
        base = skill_at_rank(BASE_BY_ID[row.definition_id], 1)
        for rank in range(2, 6):
            ranked = skill_at_rank(BASE_BY_ID[row.definition_id], rank)
            changed = [field for field in gameplay_fields if getattr(ranked, field) != getattr(base, field)]
            assert changed == [row.field], (row.definition_id, rank, changed)
            immutable = (
                "macro_role",
                "automation_band",
                "target",
                "damage_type",
                "status_kind",
                "status_duration_owner_turns",
                "activation_bps",
                "cooldown_turns",
                "max_activations_per_encounter",
                "max_activations_per_expedition",
                "root_action_count",
                "hit_packet_count",
                "extra_action_count",
                "can_deal_true_damage",
                "can_generate_guard",
                "rules_version",
                "schema_version",
            )
            assert all(getattr(ranked, field) == getattr(base, field) for field in immutable)


def check_rank_caps_and_finite_points() -> None:
    assert [rank_cap(rank) for rank in (1, 5, 15, 30, 50, 100, 101, 10**9)] == [1, 2, 3, 4, 5, 5, 5, 5]
    assert [points_earned(rank) for rank in (1, 5, 10, 25, 50, 100, 101, 10**9)] == [0, 1, 3, 6, 10, 12, 12, 12]
    assert legal_c1_allocations(1) == ((1, 1, 1),)
    assert (5, 5, 5) in legal_c1_allocations(100)
    assert all(sum(rank - 1 for rank in allocation) <= 12 for allocation in legal_c1_allocations(10**9))


def check_all_legal_allocations_static() -> None:
    checked = 0
    for combat_rank in (1, 5, 8, 10, 15, 20, 25, 30, 35, 40, 50, 70, 100):
        for class_name in c1.CLASSES:
            base_a1 = BASE_BY_ID[class_skills(c1.SKILLS, class_name, "A1_CORE")[0].definition_id]
            base_a2 = BASE_BY_ID[class_skills(c1.SKILLS, class_name, "A2_SURVIVAL")[0].definition_id]
            for a3 in class_skills(c1.SKILLS, class_name, "A3_TACTIC_I"):
                for allocation in legal_c1_allocations(combat_rank):
                    ranks = {
                        base_a1.definition_id: allocation[0],
                        base_a2.definition_id: allocation[1],
                        a3.definition_id: allocation[2],
                    }
                    catalog = ranked_catalog(ranks)
                    assert attack_envelope(catalog, class_name) <= c1.ATTACK_BUDGET[class_name]
                    a2 = class_skills(catalog, class_name, "A2_SURVIVAL")[0]
                    selected = next(skill for skill in catalog if skill.definition_id == a3.definition_id)
                    for skill in (a2, selected):
                        if skill.protection_kind is None:
                            continue
                        budget = c1.PROTECTION_BUDGETS[(class_name, skill.protection_kind)]
                        assert skill.protection_amount <= budget.amount
                        assert skill.max_activations_per_encounter <= budget.encounter_cap
                        assert skill.max_activations_per_expedition <= budget.expedition_cap
                    if class_name == "CLERIC" and selected.protection_kind == "SHIELD":
                        assert 2 * a2.protection_amount + selected.protection_amount <= 3000
                        assert 5 * a2.protection_amount + 3 * selected.protection_amount <= 7000
                    if class_name == "PALADIN" and selected.protection_kind == "HEAL":
                        assert a2.protection_amount + selected.protection_amount <= 3000
                        assert 3 * a2.protection_amount + 2 * selected.protection_amount <= 7000
                    checked += 1
    assert checked > 0
    check_all_legal_allocations_static.checked = checked


def check_rank5_envelopes_and_floors() -> None:
    catalog = ranked_catalog({skill.definition_id: 5 for skill in c1.SKILLS})
    expected = {
        "WARRIOR": 112000,
        "ROGUE": 116000,
        "RANGER": 112000,
        "MAGE": 114250,
        "CLERIC": 106000,
        "PALADIN": 108000,
    }
    assert {class_name: attack_envelope(catalog, class_name) for class_name in c1.CLASSES} == expected
    for skill in catalog:
        row = PLAN_BY_ID[skill.definition_id]
        if row.field == "resource_cost_bps":
            assert skill.resource_cost_bps >= 1300
        if skill.target_hit_floor_bps:
            assert 6500 < skill.target_hit_floor_bps <= 9500
        assert skill.root_action_count == 1 and skill.extra_action_count == 0
        assert not skill.can_deal_true_damage and not skill.can_generate_guard


def check_committed_snapshot_rank_isolation() -> None:
    committed = {"a1": 1, "a2": 1, "a3": 1, "revision": 1}
    expedition_snapshot = dict(committed)
    staged = {"a1": 5, "a2": 5, "a3": 5, "revision": 2}
    assert expedition_snapshot == committed
    committed = dict(staged)
    assert expedition_snapshot["revision"] == 1
    assert committed["revision"] == 2
    assert sum(committed[key] - 1 for key in ("a1", "a2", "a3")) == 12


def install_catalog(catalog: tuple):
    previous = c1.SKILLS
    c1.SKILLS = catalog
    return previous


def check_rank5_low_stat_zero_resource_basic() -> None:
    catalog = ranked_catalog({skill.definition_id: 5 for skill in c1.SKILLS})
    previous = install_catalog(catalog)
    try:
        for class_name in c1.CLASSES:
            source = c1.six.source_for_display(class_name, 58, "STRESS_ALL3")
            hero = c1.six.derive_hero(source).combatant
            for candidate in class_skills(catalog, class_name, "A3_TACTIC_I"):
                for behavior in c1.BEHAVIOR_MODES:
                    state = c1.HeroState(hero.max_hp, 0, 0, encounter_start_hp=hero.max_hp)
                    selected = c1.select_hero_action(
                        7,
                        state,
                        hero,
                        100,
                        100,
                        10_000,
                        None,
                        c1.c1_loadout(class_name, candidate),
                        behavior,
                    )
                    assert selected == "BASIC"
    finally:
        c1.SKILLS = previous


def performance_cells(skill) -> tuple[tuple[str, str, str], ...]:
    cells = []
    for profile in c1.PROFILES:
        cells.append(("NORMAL", "BASE", profile))
        cells.append(("NORMAL", "STRESS_ALL3", profile))
        cells.append(("BOSS", "STRESS_ALL3", profile))
    return tuple(cells)


def measure_rank5(seeds: int) -> dict[tuple[str, str], tuple[float, float, float, float]]:
    catalog = ranked_catalog({skill.definition_id: 5 for skill in c1.SKILLS})
    previous = install_catalog(catalog)
    original_source_for_display = c1.six.source_for_display

    def rank5_anchor_source(class_name: str, _ignored_rank: int, band: str):
        # L58/C58 is the first approved six-class equipment anchor above the C50 Rank5 unlock.
        return original_source_for_display(class_name, 58, band)

    c1.six.source_for_display = rank5_anchor_source
    summary: dict[tuple[str, str], tuple[float, float, float, float]] = {}
    try:
        for behavior in c1.BEHAVIOR_MODES:
            for class_name in c1.CLASSES:
                candidates = class_skills(catalog, class_name, "A3_TACTIC_I")
                cell_metrics: dict[str, list] = {candidate.definition_id: [] for candidate in candidates}
                for candidate in candidates:
                    for monster_rank, band, profile in performance_cells(candidate):
                        cell_metrics[candidate.definition_id].append(
                            c1.measure_candidate(
                                class_name,
                                candidate,
                                profile,
                                band,
                                seeds,
                                monster_rank,
                                behavior,
                            )
                        )
                win_rows = list(
                    zip(*[[metric.win_rate for metric in cell_metrics[c.definition_id]] for c in candidates])
                )
                max_win = max(max(values) - min(values) for values in win_rows)
                max_rounds = max(
                    max(values) - min(values)
                    for values in zip(*[[metric.median_rounds for metric in cell_metrics[c.definition_id]] for c in candidates])
                )
                max_hp = max(
                    max(values) - min(values)
                    for values in zip(*[[metric.p90_hp_loss for metric in cell_metrics[c.definition_id]] for c in candidates])
                )
                win_gate = 0.25 if seeds < 100 else 0.08
                worst_win_index = max(range(len(win_rows)), key=lambda index: max(win_rows[index]) - min(win_rows[index]))
                assert max_win <= win_gate + 1e-12, (
                    behavior,
                    class_name,
                    "win",
                    max_win,
                    performance_cells(candidates[0])[worst_win_index],
                    {candidate.name_ko: win_rows[worst_win_index][index] for index, candidate in enumerate(candidates)},
                )
                max_weighted_delta = 0.0
                for group_offset in range(3):
                    indices = tuple(range(group_offset, len(performance_cells(candidates[0])), 3))
                    weighted = []
                    for candidate in candidates:
                        metrics = cell_metrics[candidate.definition_id]
                        weighted.append(
                            sum(
                                metrics[index].win_rate * c1.PROFILE_WEIGHTS[performance_cells(candidate)[index][2]]
                                for index in indices
                            )
                        )
                    weighted_delta = max(weighted) - min(weighted)
                    max_weighted_delta = max(max_weighted_delta, weighted_delta)
                    assert weighted_delta <= 0.05 + 1e-12, (
                        behavior,
                        class_name,
                        "weighted_win",
                        group_offset,
                        weighted,
                    )
                assert max_rounds <= 2.0, (behavior, class_name, "rounds", max_rounds)
                assert max_hp <= 8.0, (behavior, class_name, "hp", max_hp)
                if seeds >= 100:
                    for left, right in itertools.permutations(candidates, 2):
                        assert not c1.dominates(
                            cell_metrics[left.definition_id],
                            cell_metrics[right.definition_id],
                            consider_resource=c1.RESOURCES[class_name].lifecycle == "EXPEDITION_POOL",
                        ), (behavior, class_name, left.definition_id, right.definition_id)
                summary[(behavior, class_name)] = (
                    max_win * 100.0,
                    max_weighted_delta * 100.0,
                    max_rounds,
                    max_hp,
                )
    finally:
        c1.six.source_for_display = original_source_for_display
        c1.SKILLS = previous
    return summary


STATIC_CHECKS = (
    ("THIRTY_COMPLETE_RANK_ROWS_AND_EIGHT_EXPLICIT_AXIS_AMENDMENTS", check_catalog_and_amendments),
    ("EACH_RANK_CHANGES_EXACTLY_ONE_GAMEPLAY_FIELD", check_axis_changes_only_one_gameplay_field),
    ("FINITE_RANK_CAPS_AND_TWELVE_POINTS_AT_LONG_MAX", check_rank_caps_and_finite_points),
    ("ALL_LEGAL_C1_ALLOCATIONS_PRESERVE_ATTACK_PROTECTION_AND_COMBINED_BUDGETS", check_all_legal_allocations_static),
    ("RANK5_EXACT_ATTACK_ENVELOPES_AND_RESOURCE_CONDITION_FLOORS", check_rank5_envelopes_and_floors),
    ("STAGED_RANKS_NEVER_MUTATE_ACTIVE_EXPEDITION_SNAPSHOT", check_committed_snapshot_rank_isolation),
    ("RANK5_LOW_STAT_ZERO_RESOURCE_ALWAYS_RETAINS_BASIC", check_rank5_low_stat_zero_resource_basic),
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=100)
    parser.add_argument("--pd", action="store_true")
    args = parser.parse_args()
    seeds = 500 if args.pd else args.seeds
    assert seeds >= 50

    passed = []
    for name, check in STATIC_CHECKS:
        check()
        passed.append(name)
    summary = measure_rank5(seeds)
    passed.append("RANK5_THREE_BEHAVIORS_PASS_OFFER_GATES_AND_PARETO_ZERO")

    print(f"C1_SKILL_RANK_GROWTH_V0_1: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  legalAllocationChecks={check_all_legal_allocations_static.checked}")
    catalog = ranked_catalog({skill.definition_id: 5 for skill in c1.SKILLS})
    print("  rank5AttackEnvelopes=" + ",".join(f"{name}:{attack_envelope(catalog, name)}" for name in c1.CLASSES))
    for (behavior, class_name), (cell_win, weighted_win, rounds, hp) in summary.items():
        print(
            f"  {behavior:8} {class_name:8} maxDelta "
            f"cellWin={cell_win:.2f}pp weightedWin={weighted_win:.2f}pp "
            f"rounds={rounds:.1f} hp={hp:.2f}pp"
        )
    for name in passed:
        print(f"  PASS {name}")
    print("REFERENCE_GATE: PASS (Rank2-5 design rows only; remaining skills/items/monsters/live pending)")


if __name__ == "__main__":
    main()
