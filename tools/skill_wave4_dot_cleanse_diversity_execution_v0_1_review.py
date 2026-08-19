#!/usr/bin/env python3
"""Wave 4 audit for DOT, cleanse, status diversity, and execution.

Design-only: no Kotlin, Room, save, character, or live mutations.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import math
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from functools import lru_cache
from pathlib import Path

import skill_wave1_active5_passive3_v0_1_review as wave1
import skill_wave2_combo_patterns_v0_1_review as wave2
import skill_wave3_reaction_conversion_debt_delay_v0_1_review as wave3


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_WAVE4_DOT_CLEANSE_DIVERSITY_EXECUTION_v0.1.md"
IDEA_BANK = ROOT / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"
CLASSES = wave1.CLASSES
ANCHORS = wave1.ANCHOR_LEVELS
ATTACK_CAPS = wave1.ATTACK_BUDGETS

HOSTILE_STATUS_CAP = 6
DOT_DISTINCT_CAP = 3
CURSE_SUBTYPE_CAP = 3
SHOCK_STACK_CAP = 2
EXECUTION_BASE_THRESHOLD_BPS = 2_000
EXECUTION_THRESHOLD_CAP_BPS = 2_500
EXECUTION_COEFFICIENT_CAP_BPS = 18_000
STATUS_DIVERSITY_APPLY_CAP_BPS = 800
DOT_DECLARED_TOTAL_CAP_BPS = 12_000
CLEANSE_PER_ROOT_CAP = 2


@dataclass(frozen=True)
class ActivePlan:
    definition_id: str
    idea_id: str
    owner_scope: str
    name_ko: str
    source: str
    pattern: str
    growth_field: str
    values: tuple[int, int, int, int, int]
    package_width: int
    fixed_coefficient_bps: int
    final_hit_policy: str = "NORMAL"
    crit_eligible: bool = True
    applies_tags: tuple[str, ...] = ()
    consumes_tags: tuple[str, ...] = ()
    duration_turns: int = 0
    cleanse_count_cap: int = 0
    fixed_downside: str = ""


@dataclass(frozen=True)
class PassivePlan:
    definition_id: str
    idea_id: str
    owner_scope: str
    name_ko: str
    source: str
    pattern: str
    growth_field: str
    values: tuple[int, int, int, int, int]
    condition_id: str
    stack_group: str
    fixed_tradeoff: str


def active(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, package_width, fixed_coefficient_bps, **kwargs):
    return ActivePlan(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, package_width, fixed_coefficient_bps, **kwargs)


def passive(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, condition_id, stack_group, fixed_tradeoff):
    return PassivePlan(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, condition_id, stack_group, fixed_tradeoff)


ACTIVES = (
    active("aq.skill.warrior.w4.shieldpush", "W010", "WARRIOR", "방패 밀치기", "LEVEL", "STATUS_STAGGER_SHIELD", "stagger_apply_bps", (6_000, 6_750, 7_500, 8_250, 9_000), 1, 6_000, applies_tags=("STAGGER",), consumes_tags=("SELF_SHIELD",), duration_turns=1, fixed_downside="Shield MaxHP 3%를 선소비, 저항돼도 환불 없음, carrier 계수 6,000"),
    active("aq.skill.warrior.w4.bloodwhirl", "W013", "WARRIOR", "피의 회전", "LEVEL", "CONSUME_BLEED", "consumed_coefficient_bps", (12_000, 12_500, 13_000, 13_500, 14_000), 1, 7_000, consumes_tags=("BLEED",), fixed_downside="BLEED 없으면 계수 7,000, 적 BLEED를 원자적으로 전부 소비"),

    active("aq.skill.rogue.w4.silentexecution", "ROG008", "ROGUE", "무음 처형", "CHARACTER_UNIQUE", "EXECUTION", "execution_coefficient_bps", (12_000, 12_500, 13_000, 13_500, 14_000), 1, 0, crit_eligible=False, fixed_downside="적 HP 20% 이하에서만 후보, MISS·Barrier 차단 시 다음 명중 -1,000"),
    active("aq.skill.rogue.w4.smokescreen", "ROG012", "ROGUE", "연막", "LEVEL", "DUAL_AIM_STATUS", "aim_disrupted_magnitude_bps", (500, 675, 850, 1_025, 1_200), 1, 0, applies_tags=("AIM_DISRUPTED",), duration_turns=2, fixed_downside="적과 자신에게 같은 명중 저하, 고정명중에는 무효"),

    active("aq.skill.ranger.w4.starreadingshot", "HUN008", "RANGER", "별을 읽는 사격", "CHARACTER_UNIQUE", "STATUS_SILENCE_SPELLCASTER", "silence_apply_bps", (6_500, 7_125, 7_750, 8_375, 9_000), 1, 8_000, applies_tags=("SILENCE",), duration_turns=1, fixed_downside="SPELLCASTER 외 계수 5,000·SILENCE 시도 없음"),
    active("aq.skill.ranger.w4.stormpierce", "HUN022", "RANGER", "폭풍 꿰기", "CLASS_QUEST", "CONSUME_SHOCK", "shock_consumed_coefficient_bps", (11_000, 11_500, 12_000, 12_500, 13_000), 1, 7_000, consumes_tags=("SHOCK",), fixed_downside="SHOCK 없으면 계수 7,000, stack 1개 원자 소비"),

    active("aq.skill.mage.w4.chainlightning", "MAG012", "MAGE", "연쇄 번개", "LEVEL", "SHOCK_STACK_CONSUMER", "per_shock_bonus_bps", (1_400, 1_625, 1_850, 2_075, 2_300), 1, 8_000, consumes_tags=("SHOCK",), fixed_downside="SHOCK 최대 2stack을 전부 소비, 무상태 계수 8,000"),
    active("aq.skill.mage.w4.unseal", "MAG021", "MAGE", "봉인 해제", "CLASS_QUEST", "CURSE_DIVERSITY_CONSUMER", "three_curse_coefficient_bps", (11_000, 11_500, 12_000, 12_500, 13_000), 1, 0, final_hit_policy="FIXED_9500", crit_eligible=False, consumes_tags=("CURSE_3_DISTINCT",), fixed_downside="서로 다른 CURSE 3종을 원자 소비, 조건 전에는 후보 아님, 치명 불가"),

    active("aq.skill.cleric.w4.dawnjudgment", "CLE007", "CLERIC", "새벽 심판", "CHARACTER_UNIQUE", "CURSE_UNDEAD_PUNISH", "favored_coefficient_bps", (8_000, 9_000, 10_000, 11_000, 12_000), 1, 7_000, crit_eligible=False, fixed_downside="UNDEAD 또는 CURSE 대상 외 계수 7,000, 치명 불가"),
    active("aq.skill.cleric.w4.cleanse", "CLE010", "CLERIC", "정화", "LEVEL", "CLEANSE_ONE", "cleanse_power_bps", (7_000, 7_625, 8_250, 8_875, 9_500), 1, 0, cleanse_count_cap=1, fixed_downside="해로운 상태 1개만 제거, 피해·Heal 0, COST_LOCKED 제거 불가"),

    active("aq.skill.paladin.w4.sunoath", "PAL006", "PALADIN", "태양의 맹세", "CHARACTER_UNIQUE", "SELF_CLEANSE_STRIKE", "cleansed_coefficient_bps", (9_000, 9_750, 10_500, 11_250, 12_000), 1, 6_500, consumes_tags=("SELF_BURN", "SELF_CURSE"), cleanse_count_cap=2, fixed_downside="정화할 BURN/CURSE가 없으면 계수 6,500, COST_LOCKED 제거 불가"),
    active("aq.skill.paladin.w4.kinglessknight", "PAL021", "PALADIN", "왕좌 없는 기사", "CLASS_QUEST", "MERCY_LOW_HP_CONTROL", "low_hp_suppression_bps", (800, 1_000, 1_200, 1_400, 1_600), 1, 6_000, applies_tags=("SUPPRESSED",), duration_turns=2, crit_eligible=False, fixed_downside="적 HP 20% 이하에서만 후보, 처형하지 않고 계수 6,000·공격 약화"),

    active("aq.skill.common.w4.emberbottle", "COM010", "ALL", "잔불 병", "COMMON", "DOT_BURN", "dot_total_coefficient_bps", (4_000, 4_750, 5_500, 6_250, 7_000), 1, 3_000, applies_tags=("BURN",), duration_turns=3, crit_eligible=False, fixed_downside="carrier 3,000+3턴 DOT, 정화·BURN 면역 시 carrier만 남음"),
    active("aq.skill.common.w4.poisonneedle", "COM012", "ALL", "독침", "COMMON", "DOT_POISON", "dot_total_coefficient_bps", (3_500, 4_250, 5_000, 5_750, 6_500), 1, 2_500, applies_tags=("POISON",), duration_turns=4, crit_eligible=False, fixed_downside="carrier 2,500+4턴 DOT, 정화·POISON 면역 시 carrier만 남음"),
    active("aq.skill.common.w4.certainfinish", "COM029", "ALL", "확실한 마무리", "COMMON", "EXECUTION", "execution_coefficient_bps", (8_800, 9_200, 9_600, 10_000, 10_400), 1, 0, final_hit_policy="FIXED_9500", crit_eligible=False, fixed_downside="적 HP 20% 이하에서만 후보, 치명 불가, Barrier·Shield를 우회하지 않음"),

    active("aq.skill.external.w4.lichclock", "EXT038", "ALL", "리치의 시계", "BOSS", "DELAYED_CURSE_DOT", "delayed_coefficient_bps", (8_000, 9_000, 10_000, 11_000, 12_000), 1, 0, applies_tags=("CURSE",), duration_turns=3, crit_eligible=False, fixed_downside="3번째 대상 턴 시작에 폭발, 그 전에 정화되면 피해 0, 치명 불가"),
)


PASSIVES = (
    passive("aq.skill.warrior.w4.brokenbanner", "W023", "WARRIOR", "부서진 군기의 맹세", "CLASS_QUEST", "FIRST_LOW_HP_SHIELD", "shield_maxhp_bps", (300, 400, 500, 600, 700), "first_low_hp_entry", "aq.generation.wave4.low_hp_shield", "원정 1회, 회복 후 LOW_HP 재진입에도 재발동 없음"),
    passive("aq.skill.rogue.w4.guildsecret", "ROG023", "ROGUE", "길드의 비밀", "CLASS_QUEST", "STATUS_DIVERSITY", "status_apply_add_bps", (100, 200, 300, 400, 500), "target_distinct_hostile_status_gte_three", "aq.stack.wave4.diversity_apply", "서로 다른 상태 3종 미만·면역 보스에서는 0"),
    passive("aq.skill.ranger.w4.oldmap", "HUN023", "RANGER", "오래된 지도", "CLASS_QUEST", "ARCHIVE_WEAKNESS_STABILITY", "known_weakness_apply_add_bps", (100, 200, 300, 400, 500), "archive_weakness_known", "aq.stack.wave4.archive_apply", "미발견 적에게 수치 0·조합 힌트만 표시, 발견 완료 지역은 보정 cap 100"),
    passive("aq.skill.mage.w4.overload", "MAG016", "MAGE", "과부하", "LEVEL", "DOT_AMPLIFY_OVERHEAT", "burn_shock_modifier_bps", (200, 300, 400, 500, 600), "burn_or_shock_effect", "aq.stack.wave4.dot_modifier", "사용 root마다 OVERHEAT 1, 3stack에서 다음 원소 Active 봉쇄"),
    passive("aq.skill.cleric.w4.protectiveprayer", "CLE017", "CLERIC", "보호 기도", "LEVEL", "SHIELD_BREAK_STATUS_RESIST", "status_resistance_add_bps", (200, 300, 400, 500, 600), "shield_broken", "aq.stack.wave4.status_resist", "Shield가 남거나 없던 경우 효과 0, 다음 hostile apply 1회"),
    passive("aq.skill.paladin.w4.protectedname", "PAL024", "PALADIN", "지켜낸 이름", "CLASS_QUEST", "FIRST_LOW_HP_COMBINED_SUPPORT", "shield_heal_modifier_bps", (100, 200, 300, 400, 500), "first_low_hp_shield_or_heal", "aq.stack.wave4.low_hp_support", "원정 1회, 직접 Shield·Heal 생성 없음, 첫 결합 지원에만 적용"),
    passive("aq.skill.common.w4.finisherobsession", "COM040", "ALL", "마무리 집착", "COMMON", "EXECUTION_THRESHOLD", "execution_threshold_add_bps", (100, 200, 300, 400, 500), "execution_active_equipped", "aq.stack.wave4.execute_threshold", "적 HP 50% 초과에서 DIRECT 피해 -500 고정"),
    passive("aq.skill.external.w4.abyssgaze", "EXT047", "ALL", "심연 응시", "SECRET", "STATUS_DIVERSITY", "status_apply_add_bps", (100, 200, 300, 400, 500), "target_distinct_hostile_status_gte_four", "aq.stack.wave4.diversity_apply", "자신에게 붙는 해로운 상태 지속 +1턴, 길드의 비밀과 highest-only"),
)


ACTIVE_BY_ID = {plan.definition_id: plan for plan in ACTIVES}
PASSIVE_BY_ID = {plan.definition_id: plan for plan in PASSIVES}


def round_half_up(value: Decimal) -> int:
    return int(value.quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def value_at(values, level: int) -> int:
    safe = max(1, min(100, int(level)))
    for index, right in enumerate(ANCHORS):
        if safe == right:
            return values[index]
        if safe < right:
            left = ANCHORS[index - 1]
            ratio = Decimal(safe - left) / Decimal(right - left)
            return round_half_up(Decimal(values[index - 1]) + Decimal(values[index] - values[index - 1]) * ratio)
    return values[-1]


def definition_id(item) -> str:
    return item.definition_id


def accessible_actives(class_name: str, level: int):
    return wave3.accessible_actives(class_name, level) + tuple(plan for plan in ACTIVES if plan.owner_scope in {class_name, "ALL"})


def accessible_passives(class_name: str):
    return wave3.accessible_passives(class_name) + tuple(plan for plan in PASSIVES if plan.owner_scope in {class_name, "ALL"})


@dataclass(frozen=True)
class Package:
    package_id: str
    members: frozenset[str]
    width: int
    coefficient_total: int


def has_suffix(ids: frozenset[str], suffix: str) -> bool:
    return any(value.endswith(suffix) for value in ids)


def active_source(definition: str) -> str | None:
    for catalog in (ACTIVE_BY_ID, wave3.ACTIVE_BY_ID, wave2.ACTIVE_BY_ID):
        plan = catalog.get(definition)
        if plan is not None:
            return plan.source
    return None


def cumulative_crown_enabled(active_ids: frozenset[str], passive_ids: frozenset[str]) -> bool:
    if not has_suffix(passive_ids, "crownvacancy"):
        return False
    return not any(active_source(definition) in {"CHARACTER_UNIQUE", "BOSS"} for definition in active_ids)


def common_modified(value: int, active_ids: frozenset[str], passive_ids: frozenset[str], level: int) -> int:
    if not cumulative_crown_enabled(active_ids, passive_ids):
        return value
    crown = next(plan for plan in wave2.PASSIVES if plan.definition_id.endswith("crownvacancy"))
    return round_half_up(Decimal(value) * Decimal(10_000 + wave2.value_at(crown.values, level)) / Decimal(10_000))


def mage_element_add(passive_ids: frozenset[str], level: int) -> int:
    affinity = next((plan for plan in wave2.PASSIVES if plan.definition_id.endswith("elementalaffinity") and plan.definition_id in passive_ids), None)
    return wave2.value_at(affinity.values, level) if affinity else 0


def mage_overload_modifier(passive_ids: frozenset[str], level: int) -> int:
    plan = next((plan for plan in PASSIVES if plan.definition_id.endswith("overload") and plan.definition_id in passive_ids), None)
    return value_at(plan.values, level) if plan else 0


def all_packages(class_name: str, active_ids: frozenset[str], passive_ids: frozenset[str], level: int, legacy_budget: int):
    inherited_passives = passive_ids
    if has_suffix(passive_ids, "crownvacancy") and not cumulative_crown_enabled(active_ids, passive_ids):
        inherited_passives = frozenset(value for value in passive_ids if not value.endswith("crownvacancy"))
    packages = [Package(p.package_id, p.members, p.width, p.coefficient_total) for p in wave3.all_packages(class_name, active_ids, inherited_passives, level, legacy_budget)]

    def selected(suffix):
        return next((ACTIVE_BY_ID[item] for item in active_ids if item in ACTIVE_BY_ID and item.endswith(suffix)), None)

    def add(suffix, coefficient=None):
        plan = selected(suffix)
        if not plan:
            return
        raw = value_at(plan.values, level) if coefficient is None else coefficient
        if plan.source == "COMMON":
            raw = common_modified(raw, active_ids, passive_ids, level)
        packages.append(Package(plan.definition_id, frozenset({plan.definition_id}), plan.package_width, raw))

    if class_name == "WARRIOR":
        add("shieldpush", 6_000)
        add("bloodwhirl")
    elif class_name == "ROGUE":
        add("silentexecution")
        add("smokescreen", 0)
    elif class_name == "RANGER":
        add("starreadingshot", 8_000)
        add("stormpierce")
    elif class_name == "MAGE":
        element = mage_element_add(passive_ids, level)
        add("chainlightning", 8_000 + 2 * value_at(selected("chainlightning").values, level) + element if selected("chainlightning") else 0)
        add("unseal", value_at(selected("unseal").values, level) if selected("unseal") else 0)
    elif class_name == "CLERIC":
        add("dawnjudgment")
        add("cleanse", 0)
    elif class_name == "PALADIN":
        add("sunoath")
        add("kinglessknight", 6_000)

    burn = selected("emberbottle")
    if burn:
        dot = value_at(burn.values, level)
        if class_name == "MAGE":
            dot = round_half_up(Decimal(dot) * Decimal(10_000 + mage_overload_modifier(passive_ids, level)) / Decimal(10_000))
            dot += mage_element_add(passive_ids, level)
        total = burn.fixed_coefficient_bps + dot
        packages.append(Package(burn.definition_id, frozenset({burn.definition_id}), 1, common_modified(total, active_ids, passive_ids, level)))
    poison = selected("poisonneedle")
    if poison:
        total = poison.fixed_coefficient_bps + value_at(poison.values, level)
        packages.append(Package(poison.definition_id, frozenset({poison.definition_id}), 1, common_modified(total, active_ids, passive_ids, level)))
    add("certainfinish")
    add("lichclock")
    return tuple(packages)


def attack_envelope(class_name: str, active_ids: frozenset[str], passive_ids: frozenset[str], level: int) -> int:
    catalog = wave2.legacy_catalog(level)
    legacy_selected = [skill for skill in wave1.class_actives(catalog, class_name) if skill.definition_id in active_ids]
    a1 = next((skill for skill in legacy_selected if skill.slot_id == "A1_CORE"), None)
    a1_uses = wave1.legacy.c1.max_uses_in_ten(a1.cooldown_turns) if a1 else 0
    a1_total = a1_uses * a1.attack_budget_equivalent_bps if a1 else 0
    legacy_tactics = [skill for skill in legacy_selected if skill.slot_id == "A3_TACTIC_I"]
    legacy_budget = max((skill.attack_budget_equivalent_bps for skill in legacy_tactics), default=0)
    packages = all_packages(class_name, active_ids, passive_ids, level, legacy_budget)
    cap = wave1.TACTIC_GROUPS[class_name].encounter_cap
    best = a1_total + (10 - a1_uses) * 10_000
    for count in range(1, min(cap, len(packages)) + 1):
        for chosen in itertools.combinations(packages, count):
            members: set[str] = set()
            valid = True
            for package in chosen:
                if members.intersection(package.members):
                    valid = False
                    break
                members.update(package.members)
            if not valid:
                continue
            width = sum(package.width for package in chosen)
            remaining = 10 - a1_uses - width
            if remaining >= 0:
                best = max(best, a1_total + sum(package.coefficient_total for package in chosen) + remaining * 10_000)
    if class_name == "MAGE":
        legacy_passives = tuple(item for item in passive_ids if item in wave1.PASSIVE_BY_ID)
        best += 10 * wave1.passive_modifier(legacy_passives, "action_coefficient_add_bps", level)
    return best


def canonical_hash() -> str:
    payload = {
        "wave3Hash": wave3.canonical_hash(),
        "anchors": ANCHORS,
        "actives": [plan.__dict__ for plan in sorted(ACTIVES, key=lambda item: item.definition_id)],
        "passives": [plan.__dict__ for plan in sorted(PASSIVES, key=lambda item: item.definition_id)],
        "hostileStatusCap": HOSTILE_STATUS_CAP,
        "dotDistinctCap": DOT_DISTINCT_CAP,
        "curseSubtypeCap": CURSE_SUBTYPE_CAP,
        "shockStackCap": SHOCK_STACK_CAP,
        "executionThresholdCapBps": EXECUTION_THRESHOLD_CAP_BPS,
        "executionCoefficientCapBps": EXECUTION_COEFFICIENT_CAP_BPS,
        "statusDiversityApplyCapBps": STATUS_DIVERSITY_APPLY_CAP_BPS,
        "cleansePerRootCap": CLEANSE_PER_ROOT_CAP,
        "sharedLedger": "TACTIC_SHARED",
    }
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def check_catalog():
    assert len(ACTIVES) == 16 and len(PASSIVES) == 8
    assert len({p.definition_id for p in ACTIVES + PASSIVES}) == 24
    assert len({p.idea_id for p in ACTIVES + PASSIVES}) == 24
    assert sum(p.owner_scope in CLASSES for p in ACTIVES) == 12
    assert sum(p.owner_scope in CLASSES for p in PASSIVES) == 6
    assert sum(p.owner_scope == "ALL" for p in ACTIVES) == 4
    assert sum(p.owner_scope == "ALL" for p in PASSIVES) == 2
    wave1_active_ids = {
        f"{prefix}{index:03d}"
        for prefix in ("W", "ROG", "HUN", "MAG", "CLE", "PAL")
        for index in range(1, 6)
    }
    prior_ids = (
        wave1_active_ids
        | {p.idea_id for p in wave1.PASSIVES}
        | {p.idea_id for p in wave2.ACTIVES + wave2.PASSIVES}
        | {p.idea_id for p in wave3.ACTIVES + wave3.PASSIVES}
    )
    current_ids = {p.idea_id for p in ACTIVES + PASSIVES}
    assert len(prior_ids) == 96
    assert not prior_ids.intersection(current_ids)
    assert len(prior_ids | current_ids) == 120
    patterns = {p.pattern for p in ACTIVES + PASSIVES}
    assert any("DOT" in pattern for pattern in patterns)
    assert any("CLEANSE" in pattern for pattern in patterns)
    assert any("DIVERSITY" in pattern for pattern in patterns)
    assert any("EXECUTION" in pattern for pattern in patterns)


def check_idea_bank():
    text = IDEA_BANK.read_text(encoding="utf-8")
    for plan in ACTIVES + PASSIVES:
        assert f"| {plan.idea_id} | {plan.name_ko} |" in text


def check_growth_and_ledgers():
    for plan in ACTIVES + PASSIVES:
        values = [value_at(plan.values, level) for level in range(1, 101)]
        increasing = plan.values[-1] >= plan.values[0]
        assert all((b >= a) if increasing else (b <= a) for a, b in zip(values, values[1:]))
        assert tuple(values[level - 1] for level in ANCHORS) == plan.values
    assert HOSTILE_STATUS_CAP == 6 and DOT_DISTINCT_CAP == 3
    assert CURSE_SUBTYPE_CAP == 3 and SHOCK_STACK_CAP == 2
    assert EXECUTION_BASE_THRESHOLD_BPS == 2_000 and EXECUTION_THRESHOLD_CAP_BPS == 2_500
    assert max(p.values[-1] for p in ACTIVES if p.pattern == "EXECUTION") <= EXECUTION_COEFFICIENT_CAP_BPS
    assert max(p.cleanse_count_cap for p in ACTIVES) <= CLEANSE_PER_ROOT_CAP
    assert max(p.values[-1] for p in PASSIVES if p.pattern == "STATUS_DIVERSITY") <= STATUS_DIVERSITY_APPLY_CAP_BPS
    assert all(p.duration_turns in {3, 4} for p in ACTIVES if p.pattern.startswith("DOT_"))
    assert all(p.fixed_coefficient_bps + p.values[-1] <= DOT_DECLARED_TOTAL_CAP_BPS for p in ACTIVES if p.pattern.startswith("DOT_"))


def check_access_counts():
    for class_name in CLASSES:
        assert len(accessible_actives(class_name, 100)) == 23
        assert len(accessible_passives(class_name)) == 12


RELEVANT_PASSIVE_SUFFIXES = {
    "WARRIOR": ("painconversion", "crownvacancy"),
    "ROGUE": ("crownvacancy",),
    "RANGER": ("crownvacancy",),
    "MAGE": ("crackedcore", "glasscannon", "elementalaffinity", "crownvacancy", "overload"),
    "CLERIC": ("crownvacancy",),
    "PALADIN": ("justiceweight", "crownvacancy"),
}


def passive_signatures(class_name: str):
    available = tuple(definition_id(plan) for plan in accessible_passives(class_name))
    relevant = tuple(value for value in available if any(value.endswith(suffix) for suffix in RELEVANT_PASSIVE_SUFFIXES[class_name]))
    signatures = []
    for count in range(0, min(3, len(relevant)) + 1):
        signatures.extend(frozenset(combo) for combo in itertools.combinations(relevant, count))
    assert all(len(signature) <= 3 for signature in signatures)
    assert len(available) - len(relevant) >= 3
    return tuple(signatures)


def check_representative_full_loadouts():
    evaluated = 0
    for class_name in CLASSES:
        actives = accessible_actives(class_name, 100)
        for active_combo in itertools.combinations(actives, 5):
            active_ids = frozenset(definition_id(item) for item in active_combo)
            for passive_ids in passive_signatures(class_name):
                envelope = attack_envelope(class_name, active_ids, passive_ids, 100)
                assert envelope <= ATTACK_CAPS[class_name], (class_name, active_ids, passive_ids, envelope)
                evaluated += 1
    represented = math.comb(23, 5) * math.comb(12, 3) * len(CLASSES) * 100
    assert evaluated == 1_345_960
    assert represented == 4_441_668_000
    check_representative_full_loadouts.evaluated = evaluated
    check_representative_full_loadouts.represented = represented


def target_hit_modifier(attacker, defender, policy):
    target = {"CLAMP_MIN": 6_500, "CLAMP_MAX": 9_900, "FIXED_9500": 9_500}.get(policy)
    return 0 if target is None else target - wave1.legacy.c1.core.hit_bps(attacker, defender)


def attack_once(hero, monster, coefficient, seed, action, key, policy="NORMAL", crit=True):
    return wave1.legacy.c1.resolved_attack(
        hero, monster, coefficient,
        wave1.legacy.c1.core_rolls(seed, action, "HERO", key),
        hit_modifier_bps=target_hit_modifier(hero, monster, policy),
        crit_eligible=crit,
    )


def combatants(class_name, profile, level, band):
    source = wave1.legacy.c1.six.source_for_display(class_name, level, band)
    return source, wave1.legacy.c1.six.derive_hero(source).combatant, wave1.legacy.c1.six.monster_for(source, profile, "BOSS")


def periodic_damage(attacker, defender, coefficient):
    core = wave1.legacy.c1.core
    power = attacker.physical_attack if attacker.attack_type == "PHYSICAL" else attacker.magical_attack
    damage = core.multiply_bps(power, coefficient)
    return max(1, core.multiply_bps(damage, 10_000 - core.mitigation_bps(attacker, defender)))


def status_apply_bps(combat_rank, profile, add_bps=0):
    core = wave1.legacy.c1.core
    resistance = wave1.legacy.c1.monster_status_resistance(combat_rank, profile)
    return core.clamp(9_000 + add_bps - core.clamp(40 * resistance, 0, 4_000), 1_000, 9_500)


def execute_candidate(current_hp, max_hp, threshold_add_bps):
    threshold = min(EXECUTION_THRESHOLD_CAP_BPS, EXECUTION_BASE_THRESHOLD_BPS + threshold_add_bps)
    return current_hp * 10_000 <= max_hp * threshold


def cleanse_priority(statuses):
    return min(
        statuses,
        key=lambda item: (
            -int(item["projected_lethal"]),
            -int(item["blocks_root"]),
            -item["remaining_coefficient_bps"],
            -item["remaining_turns"],
            item["status_id"],
        ),
    )["status_id"]


def dynamic_metrics(seeds: int):
    totals = {
        "burn": [0, 0, 0],
        "poison": [0, 0, 0],
        "execute": [0, 0],
        "commonExecute": [0, 0],
        "lich": [0, 0, 0],
        "apply": [0, 0],
    }
    for level in (58, 9_999):
        for band in ("BASE", "STRESS_ALL3"):
            for profile in wave1.legacy.c1.PROFILES:
                for class_name in CLASSES:
                    source, hero, monster = combatants(class_name, profile, level, band)
                    key = f"W4.{class_name}.{profile}.{level}.{band}"
                    apply_bps = status_apply_bps(source.combat_rank, profile)
                    for seed in range(seeds):
                        basic, _ = attack_once(hero, monster, 10_000, seed, 1, key)
                        burn_carrier, burn_hit = attack_once(hero, monster, 3_000, seed, 2, key, crit=False)
                        burn_applied = burn_hit and wave1.legacy.c1.event_roll_bps(seed, 2, "W4_BURN_APPLY", key) < apply_bps
                        burn_total = burn_carrier + (periodic_damage(hero, monster, 7_000) if burn_applied else 0)
                        poison_carrier, poison_hit = attack_once(hero, monster, 2_500, seed, 3, key, crit=False)
                        poison_applied = poison_hit and wave1.legacy.c1.event_roll_bps(seed, 3, "W4_POISON_APPLY", key) < apply_bps
                        poison_total = poison_carrier + (periodic_damage(hero, monster, 6_500) if poison_applied else 0)
                        common_execute, _ = attack_once(hero, monster, 10_400, seed, 4, key, "FIXED_9500", False)
                        lich_applied = wave1.legacy.c1.event_roll_bps(seed, 5, "W4_LICH_APPLY", key) < apply_bps
                        lich_full = periodic_damage(hero, monster, 12_000) if lich_applied else 0
                        cleanse = wave1.legacy.c1.event_roll_bps(seed, 6, "W4_LICH_CLEANSE", key) < 3_500
                        lich_expected = 0 if cleanse else lich_full
                        totals["burn"][0] += burn_total
                        totals["burn"][1] += burn_carrier
                        totals["burn"][2] += basic
                        totals["poison"][0] += poison_total
                        totals["poison"][1] += poison_carrier
                        totals["poison"][2] += basic
                        totals["commonExecute"][0] += common_execute
                        totals["commonExecute"][1] += basic
                        totals["lich"][0] += lich_full
                        totals["lich"][1] += lich_expected
                        totals["lich"][2] += basic
                        totals["apply"][0] += int(burn_applied)
                        totals["apply"][1] += 1
                        if class_name == "ROGUE":
                            execution, _ = attack_once(hero, monster, 14_000, seed, 7, key, crit=False)
                            totals["execute"][0] += execution
                            totals["execute"][1] += basic
    statuses = [
        {"status_id": "aq.status.burn", "projected_lethal": True, "blocks_root": False, "remaining_coefficient_bps": 4_667, "remaining_turns": 2},
        {"status_id": "aq.status.stagger", "projected_lethal": False, "blocks_root": True, "remaining_coefficient_bps": 0, "remaining_turns": 1},
        {"status_id": "aq.status.curse.fragility", "projected_lethal": False, "blocks_root": False, "remaining_coefficient_bps": 0, "remaining_turns": 3},
    ]
    metrics = {
        "burnFullVsBasic": totals["burn"][0] / max(1, totals["burn"][2]),
        "burnImmuneVsBasic": totals["burn"][1] / max(1, totals["burn"][2]),
        "poisonFullVsBasic": totals["poison"][0] / max(1, totals["poison"][2]),
        "poisonImmuneVsBasic": totals["poison"][1] / max(1, totals["poison"][2]),
        "statusApplyRate": totals["apply"][0] / totals["apply"][1],
        "rogueExecuteVsBasic": totals["execute"][0] / max(1, totals["execute"][1]),
        "commonExecuteVsBasic": totals["commonExecute"][0] / max(1, totals["commonExecute"][1]),
        "lichFullVsBasic": totals["lich"][0] / max(1, totals["lich"][2]),
        "lichAfterCleanseVsBasic": totals["lich"][1] / max(1, totals["lich"][2]),
        "cleanseFirst": cleanse_priority(statuses),
        "executeAtCap": execute_candidate(2_500, 10_000, 500),
        "executeAboveCap": execute_candidate(2_501, 10_000, 500),
        "diversityHighestOnlyBps": 500,
        "hostileStatusCap": HOSTILE_STATUS_CAP,
    }
    assert 0.55 <= metrics["statusApplyRate"] <= 0.95
    assert metrics["burnImmuneVsBasic"] < metrics["burnFullVsBasic"] <= 1.10
    assert metrics["poisonImmuneVsBasic"] < metrics["poisonFullVsBasic"] <= 1.05
    assert 1.05 <= metrics["rogueExecuteVsBasic"] <= 1.65
    assert 0.95 <= metrics["commonExecuteVsBasic"] <= 1.45
    assert 0.80 <= metrics["lichFullVsBasic"] <= 1.60
    assert metrics["lichAfterCleanseVsBasic"] < metrics["lichFullVsBasic"]
    assert metrics["cleanseFirst"] == "aq.status.burn"
    assert metrics["executeAtCap"] and not metrics["executeAboveCap"]
    assert metrics["diversityHighestOnlyBps"] <= STATUS_DIVERSITY_APPLY_CAP_BPS
    assert metrics["hostileStatusCap"] == 6
    return metrics


STATIC_CHECKS = (
    ("WAVE4_HAS_16_ACTIVE_8_PASSIVE", check_catalog),
    ("ALL_24_IDS_EXIST_IN_240_IDEA_BANK", check_idea_bank),
    ("SINGLE_AXIS_GROWTH_AND_STATUS_LEDGER_HARD_CAPS", check_growth_and_ledgers),
    ("EACH_CLASS_ACCESSES_23_ACTIVE_12_PASSIVE", check_access_counts),
    ("PASSIVE_EQUIVALENCE_CLASSES_REPRESENT_4441668000_LEVEL_STATES", check_representative_full_loadouts),
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=100)
    parser.add_argument("--pd", action="store_true")
    args = parser.parse_args()
    seeds = 200 if args.pd else args.seeds
    assert seeds >= 50
    passed = []
    for name, check in STATIC_CHECKS:
        check()
        passed.append(name)
    dynamic = dynamic_metrics(seeds)
    passed.append("DOT_CLEANSE_DIVERSITY_EXECUTION_GATES")
    if DOCUMENT.exists():
        assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")
        passed.append("DOCUMENT_CONTAINS_CANONICAL_WAVE4_HASH")
    print(f"SKILL_WAVE4_DOT_CLEANSE_DIVERSITY_EXECUTION_V0_1: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  evaluatedPassiveEquivalenceBuilds={check_representative_full_loadouts.evaluated}")
    print(f"  representedFullLoadoutLevelStates={check_representative_full_loadouts.represented}")
    print("  accessiblePerClass=Active23 Passive12 equip5+3")
    print(
        "  apply={statusApplyRate:.2%} burn={burnFullVsBasic:.3f}/immune={burnImmuneVsBasic:.3f} "
        "poison={poisonFullVsBasic:.3f}/immune={poisonImmuneVsBasic:.3f}".format(**dynamic)
    )
    print(
        "  execute rogue={rogueExecuteVsBasic:.3f} common={commonExecuteVsBasic:.3f} "
        "lich={lichFullVsBasic:.3f}/cleanse={lichAfterCleanseVsBasic:.3f}".format(**dynamic)
    )
    print(
        f"  cleanseFirst={dynamic['cleanseFirst']} thresholdCap={EXECUTION_THRESHOLD_CAP_BPS} "
        f"statusCap={dynamic['hostileStatusCap']} diversityHighestOnly={dynamic['diversityHighestOnlyBps']}"
    )
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
