#!/usr/bin/env python3
"""Design-only audit for AlarmQuest skill collection and long progression v0.1.

This tool does not mutate production saves, Room, Kotlin, or live content. It
checks the planning constants, Active 5 + Passive 3 contract, SkillXP curve,
receipt replay behavior, and the 240-entry concept bank.
"""

from __future__ import annotations

import argparse
import math
import re
from dataclasses import dataclass, field
from decimal import Decimal, ROUND_HALF_UP, getcontext
from pathlib import Path

import c1_skill_rank_growth_v0_1_review as legacy_c1_growth


getcontext().prec = 50

DISPLAY_LEVEL_CAP = 9_999
REFERENCE_DAYS_TO_CAP = Decimal("1095")
REFERENCE_XP_PER_DAY = 1_000_000
TOTAL_CHARACTER_XP = 1_095_000_000
CHARACTER_XP_EXPONENT = Decimal("1.15")

ACTIVE_SLOT_CAP = 5
PASSIVE_SLOT_CAP = 3

SKILL_LEVEL_CAP = 100
TOTAL_SKILL_XP = 20_000_000
SKILL_XP_EXPONENT = Decimal("1.50")
SKILL_XP_SHARE_BPS = 1_000
CATCH_UP_PER_LEVEL_BPS = 100
CATCH_UP_CAP_BPS = 5_000

CLASS_COUNT = 6
CLASS_UNIQUE_PER_CLASS = 6
CLASS_FIXED_LEVEL_GRANTS_PER_CLASS = 31
CLASS_CHOICE_MILESTONES_PER_CLASS = 10
CLASS_QUEST_PER_CLASS = 6
COMMON_LAUNCH_TARGET = 72
WORLD_QUEST_LAUNCH_TARGET = 72
REGION_MONSTER_LAUNCH_TARGET = 30
BOSS_LAUNCH_TARGET = 18
ACHIEVEMENT_SECRET_LAUNCH_TARGET = 18
THREE_YEAR_CATALOG_TARGET = 900

SKILL_ANCHOR_LEVELS = (1, 25, 50, 75, 100)
LEVEL_SKILL_MILESTONES = (
    2, 3, 5, 7, 10, 15, 20, 30, 40, 50, 65, 80, 100,
    150, 200, 300, 400, 500, 650, 800, 1_000,
    1_250, 1_500, 1_750, 2_000, 2_500, 3_000, 3_500,
    4_000, 4_500, 5_000, 5_500, 6_000, 6_500, 7_000,
    7_500, 8_000, 8_500, 9_000, 9_500, 9_999,
)
LEVEL_CHOICE_MILESTONES = (10, 50, 100, 500, 1_000, 2_000, 3_000, 5_000, 7_500, 9_999)


def round_half_up(value: float | Decimal) -> int:
    decimal_value = value if isinstance(value, Decimal) else Decimal(str(value))
    return int(decimal_value.quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def decimal_power_ratio(numerator: int, denominator: int, exponent: Decimal) -> Decimal:
    if numerator <= 0:
        return Decimal(0)
    ratio = Decimal(numerator) / Decimal(denominator)
    return getcontext().power(ratio, exponent)


def cumulative_character_xp(level: int) -> int:
    safe_level = max(1, min(DISPLAY_LEVEL_CAP, int(level)))
    if safe_level == 1:
        return 0
    ratio_power = decimal_power_ratio(safe_level - 1, DISPLAY_LEVEL_CAP - 1, CHARACTER_XP_EXPONENT)
    return round_half_up(Decimal(TOTAL_CHARACTER_XP) * ratio_power)


def reference_days(level: int) -> Decimal:
    return Decimal(cumulative_character_xp(level)) / Decimal(REFERENCE_XP_PER_DAY)


def combat_rank(display_level: int) -> int:
    safe_level = max(1, min(DISPLAY_LEVEL_CAP, int(display_level)))
    if safe_level <= 100:
        return safe_level
    return round_half_up(100.0 + 100.0 * math.log1p((safe_level - 100) / 100.0))


def cumulative_skill_xp(skill_level: int) -> int:
    safe_level = max(1, min(SKILL_LEVEL_CAP, int(skill_level)))
    if safe_level == 1:
        return 0
    ratio_power = decimal_power_ratio(safe_level - 1, SKILL_LEVEL_CAP - 1, SKILL_XP_EXPONENT)
    return round_half_up(Decimal(TOTAL_SKILL_XP) * ratio_power)


def interpolated_legacy_skill_value(plan: legacy_c1_growth.RankPlan, skill_level: int) -> int:
    safe_level = max(1, min(SKILL_LEVEL_CAP, int(skill_level)))
    for anchor_index, anchor_level in enumerate(SKILL_ANCHOR_LEVELS):
        if safe_level == anchor_level:
            return plan.values[anchor_index]
        if safe_level < anchor_level:
            left_level = SKILL_ANCHOR_LEVELS[anchor_index - 1]
            left_value = plan.values[anchor_index - 1]
            right_value = plan.values[anchor_index]
            progress = Decimal(safe_level - left_level) / Decimal(anchor_level - left_level)
            return round_half_up(Decimal(left_value) + Decimal(right_value - left_value) * progress)
    return plan.values[-1]


def skill_level_for_xp(skill_xp: int) -> int:
    safe_xp = max(0, min(TOTAL_SKILL_XP, int(skill_xp)))
    low, high = 1, SKILL_LEVEL_CAP
    while low < high:
        middle = (low + high + 1) // 2
        if cumulative_skill_xp(middle) <= safe_xp:
            low = middle
        else:
            high = middle - 1
    return low


def skill_xp_grant(eligible_combat_xp: int, target_level: int, mentor_level: int) -> int:
    base = max(0, int(eligible_combat_xp)) * SKILL_XP_SHARE_BPS // 10_000
    gap = max(0, int(mentor_level) - int(target_level))
    catch_up_bps = 10_000 + min(CATCH_UP_CAP_BPS, CATCH_UP_PER_LEVEL_BPS * gap)
    return base * catch_up_bps // 10_000


def validate_loadout(active_ids: tuple[str, ...], passive_ids: tuple[str, ...]) -> bool:
    if len(active_ids) > ACTIVE_SLOT_CAP or len(passive_ids) > PASSIVE_SLOT_CAP:
        return False
    if len(active_ids) != len(set(active_ids)) or len(passive_ids) != len(set(passive_ids)):
        return False
    if set(active_ids) & set(passive_ids):
        return False
    return True


@dataclass
class SkillXpLedger:
    xp_by_skill: dict[str, int] = field(default_factory=dict)
    receipts: set[str] = field(default_factory=set)

    def settle(
        self,
        receipt_id: str,
        active_ids: tuple[str, ...],
        passive_ids: tuple[str, ...],
        eligible_combat_xp: int,
        level_by_skill: dict[str, int],
        mentor_level: int,
    ) -> bool:
        if receipt_id in self.receipts:
            return False
        if not validate_loadout(active_ids, passive_ids):
            raise ValueError("invalid 5+3 loadout")
        resolved = active_ids + passive_ids
        grants = {
            skill_id: skill_xp_grant(eligible_combat_xp, level_by_skill[skill_id], mentor_level)
            for skill_id in resolved
        }
        for skill_id, grant in grants.items():
            self.xp_by_skill[skill_id] = min(
                TOTAL_SKILL_XP,
                self.xp_by_skill.get(skill_id, 0) + grant,
            )
        self.receipts.add(receipt_id)
        return True


def read_catalog(root: Path) -> list[tuple[str, str, str]]:
    path = root / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"
    rows: list[tuple[str, str, str]] = []
    pattern = re.compile(r"^\| ((?:W|ROG|HUN|MAG|CLE|PAL|COM|EXT)\d{3}) \| .*? \| (.*?) \| ([AP]) \|")
    for line in path.read_text(encoding="utf-8").splitlines():
        match = pattern.match(line)
        if match:
            rows.append((match.group(1), match.group(2), match.group(3)))
    return rows


def run_review(root: Path, verbose: bool = False) -> list[str]:
    checks: list[str] = []

    character_thresholds = [cumulative_character_xp(level) for level in range(1, DISPLAY_LEVEL_CAP + 1)]
    assert character_thresholds[0] == 0
    assert character_thresholds[-1] == TOTAL_CHARACTER_XP
    assert all(right > left for left, right in zip(character_thresholds, character_thresholds[1:]))
    assert reference_days(DISPLAY_LEVEL_CAP) == REFERENCE_DAYS_TO_CAP
    checks.append("character_level_curve_1_to_9999_monotone_and_1095_days")

    expected_ranks = {1: 1, 100: 100, 200: 169, 500: 261, 1_000: 330, 5_000: 491, 9_999: 561}
    assert {level: combat_rank(level) for level in expected_ranks} == expected_ranks
    assert all(combat_rank(level + 1) >= combat_rank(level) for level in range(1, DISPLAY_LEVEL_CAP))
    checks.append("combat_rank_clamped_at_c561")

    assert len(LEVEL_SKILL_MILESTONES) == 41
    assert len(set(LEVEL_SKILL_MILESTONES)) == 41
    assert tuple(sorted(LEVEL_SKILL_MILESTONES)) == LEVEL_SKILL_MILESTONES
    assert set(LEVEL_CHOICE_MILESTONES) < set(LEVEL_SKILL_MILESTONES)
    checks.append("display_level_skill_milestones_41_with_10_choices")

    fixed_milestones = len(LEVEL_SKILL_MILESTONES) - len(LEVEL_CHOICE_MILESTONES)
    level_definitions_per_class = fixed_milestones + 3 * len(LEVEL_CHOICE_MILESTONES)
    class_definitions_per_class = (
        CLASS_UNIQUE_PER_CLASS + level_definitions_per_class + CLASS_QUEST_PER_CLASS
    )
    launch_catalog_target = (
        CLASS_COUNT * class_definitions_per_class
        + COMMON_LAUNCH_TARGET
        + WORLD_QUEST_LAUNCH_TARGET
        + REGION_MONSTER_LAUNCH_TARGET
        + BOSS_LAUNCH_TARGET
        + ACHIEVEMENT_SECRET_LAUNCH_TARGET
    )
    assert fixed_milestones == CLASS_FIXED_LEVEL_GRANTS_PER_CLASS
    assert len(LEVEL_CHOICE_MILESTONES) == CLASS_CHOICE_MILESTONES_PER_CLASS
    assert level_definitions_per_class == 61
    assert class_definitions_per_class == 73
    assert launch_catalog_target == 648
    assert THREE_YEAR_CATALOG_TARGET - launch_catalog_target == 252
    checks.append("launch_catalog_648_from_all_level_choice_candidates")

    assert validate_loadout(tuple(f"A{i}" for i in range(5)), tuple(f"P{i}" for i in range(3)))
    assert not validate_loadout(tuple(f"A{i}" for i in range(6)), ())
    assert not validate_loadout((), tuple(f"P{i}" for i in range(4)))
    assert not validate_loadout(("X", "X"), ())
    assert not validate_loadout(("X",), ("X",))
    checks.append("active5_passive3_free_selection_limits")

    skill_thresholds = [cumulative_skill_xp(level) for level in range(1, SKILL_LEVEL_CAP + 1)]
    assert skill_thresholds[0] == 0
    assert skill_thresholds[-1] == TOTAL_SKILL_XP
    assert all(right > left for left, right in zip(skill_thresholds, skill_thresholds[1:]))
    expected_skill_xp = {
        1: 0,
        2: 20_304,
        5: 162_430,
        10: 548_202,
        20: 1_681_542,
        30: 3_170_839,
        50: 6_964_201,
        75: 12_924_828,
        90: 17_047_559,
        99: 19_697_736,
        100: 20_000_000,
    }
    assert {level: cumulative_skill_xp(level) for level in expected_skill_xp} == expected_skill_xp
    checks.append("skill_level_curve_1_to_100_monotone")

    for level in range(1, SKILL_LEVEL_CAP + 1):
        threshold = cumulative_skill_xp(level)
        assert skill_level_for_xp(threshold) == level
        if level < SKILL_LEVEL_CAP:
            assert skill_level_for_xp(cumulative_skill_xp(level + 1) - 1) == level
    checks.append("skill_level_threshold_boundaries")

    assert skill_xp_grant(1_000_000, 50, 50) == 100_000
    assert skill_xp_grant(1_000_000, 100, 1) == 100_000
    assert skill_xp_grant(1_000_000, 1, 100) == 150_000
    assert skill_xp_grant(0, 1, 100) == 0
    checks.append("combat_xp_10_percent_and_catchup_cap_50_percent")

    ids = tuple(f"A{i}" for i in range(5)) + tuple(f"P{i}" for i in range(3))
    levels = {skill_id: 1 for skill_id in ids}
    ledger = SkillXpLedger()
    assert ledger.settle("battle-1", ids[:5], ids[5:], 1_000_000, levels, 1)
    assert set(ledger.xp_by_skill) == set(ids)
    assert set(ledger.xp_by_skill.values()) == {100_000}
    before = dict(ledger.xp_by_skill)
    assert not ledger.settle("battle-1", ids[:5], ids[5:], 1_000_000, levels, 1)
    assert ledger.xp_by_skill == before
    checks.append("eight_equipped_skills_gain_once_and_replay_noop")

    split_ledger = SkillXpLedger()
    for index in range(10):
        assert split_ledger.settle(f"split-{index}", ids[:5], ids[5:], 100_000, levels, 1)
    assert split_ledger.xp_by_skill == ledger.xp_by_skill
    checks.append("split_and_batched_skill_xp_equivalence")

    assert SKILL_ANCHOR_LEVELS == (1, 25, 50, 75, 100)
    assert all(cumulative_skill_xp(right) > cumulative_skill_xp(left) for left, right in zip(SKILL_ANCHOR_LEVELS, SKILL_ANCHOR_LEVELS[1:]))
    checks.append("legacy_rank1_to_rank5_anchor_mapping")

    assert len(legacy_c1_growth.RANK_PLANS) == 30
    for plan in legacy_c1_growth.RANK_PLANS:
        interpolated = [interpolated_legacy_skill_value(plan, level) for level in range(1, 101)]
        assert tuple(interpolated[level - 1] for level in SKILL_ANCHOR_LEVELS) == plan.values
        if plan.direction == "INCREASE":
            assert all(right >= left for left, right in zip(interpolated, interpolated[1:]))
        else:
            assert plan.direction == "DECREASE"
            assert all(right <= left for left, right in zip(interpolated, interpolated[1:]))
    checks.append("legacy_c1_30_all_skill_levels_integer_interpolated")

    rows = read_catalog(root)
    assert len(rows) == 240
    catalog_ids = [row[0] for row in rows]
    assert len(set(catalog_ids)) == 240
    checks.append("idea_bank_240_unique_ids")

    active_count = sum(slot == "A" for _, _, slot in rows)
    passive_count = sum(slot == "P" for _, _, slot in rows)
    assert (active_count, passive_count) == (150, 90)
    checks.append("idea_bank_active150_passive90")

    for prefix in ("W", "ROG", "HUN", "MAG", "CLE", "PAL"):
        class_rows = [row for row in rows if row[0].startswith(prefix)]
        assert len(class_rows) == 24
        assert sum(slot == "A" for _, _, slot in class_rows) == 15
        assert sum(slot == "P" for _, _, slot in class_rows) == 9
    checks.append("six_classes_each_24_with_15_active_9_passive")

    common_rows = [row for row in rows if row[0].startswith("COM")]
    external_rows = [row for row in rows if row[0].startswith("EXT")]
    assert len(common_rows) == 48 and sum(row[2] == "A" for row in common_rows) == 30
    assert len(external_rows) == 48 and sum(row[2] == "A" for row in external_rows) == 30
    checks.append("common48_and_external48_source_banks")

    sources = {source for _, source, _ in rows}
    required_source_labels = {"고유", "레벨", "직퀘", "공용", "월드퀘", "몬스터", "보스", "비밀"}
    assert required_source_labels <= sources
    checks.append("all_required_acquisition_sources_present")

    if verbose:
        print(f"characterCapXP={TOTAL_CHARACTER_XP} capDays={reference_days(DISPLAY_LEVEL_CAP)}")
        print(f"displayLevel9999=CombatRank{combat_rank(DISPLAY_LEVEL_CAP)}")
        print(f"skillCapXP={TOTAL_SKILL_XP} baseDailySkillXP={skill_xp_grant(1_000_000, 50, 50)}")
        print(f"catalog={len(rows)} active={active_count} passive={passive_count}")
    return checks


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    checks = run_review(root, verbose=args.verbose)
    print(f"skill_collection_level9999_v0_1=PASS checks={len(checks)}/{len(checks)}")
    for check in checks:
        print(f"PASS {check}")


if __name__ == "__main__":
    main()
