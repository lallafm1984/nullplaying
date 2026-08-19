#!/usr/bin/env python3
"""Wave 2 numerical design audit: 24 skills and four build-around patterns.

This is a design-only reference model.  It extends the Wave 1 catalogue without
mutating Kotlin, Room, saves, or live content.  Every new Active consumes the
class TACTIC_SHARED ledger.  A successful primer may create exactly one atomic
follow-up token so its matching detonator uses the next root action without a
second group-use charge; the pair must still fit the old tactic+basic budget.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import statistics
import sys
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from functools import lru_cache
from pathlib import Path

import skill_wave1_active5_passive3_v0_1_review as wave1


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_WAVE2_COMBO_PATTERNS_v0.1.md"
IDEA_BANK = ROOT / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"
CLASSES = wave1.CLASSES
ANCHORS = wave1.ANCHOR_LEVELS
ATTACK_CAPS = wave1.ATTACK_BUDGETS


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
    action_width: int
    fallback_coefficient_bps: int
    final_hit_policy: str = "NORMAL"
    crit_eligible: bool = True
    applies_tag: str | None = None
    consumes_tag: str | None = None
    chain_group_id: str | None = None
    fixed_self_hp_cost_bps: int = 0
    fixed_resource_cost_bps: int = 0
    hit_packets: int = 1
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
    host_scope: str
    stack_group: str
    fixed_tradeoff: str


def active(
    definition_id: str,
    idea_id: str,
    owner_scope: str,
    name_ko: str,
    source: str,
    pattern: str,
    growth_field: str,
    values: tuple[int, int, int, int, int],
    action_width: int,
    fallback_coefficient_bps: int,
    **kwargs,
) -> ActivePlan:
    return ActivePlan(
        definition_id,
        idea_id,
        owner_scope,
        name_ko,
        source,
        pattern,
        growth_field,
        values,
        action_width,
        fallback_coefficient_bps,
        **kwargs,
    )


def passive(
    definition_id: str,
    idea_id: str,
    owner_scope: str,
    name_ko: str,
    source: str,
    pattern: str,
    growth_field: str,
    values: tuple[int, int, int, int, int],
    condition_id: str,
    host_scope: str,
    stack_group: str,
    fixed_tradeoff: str,
) -> PassivePlan:
    return PassivePlan(
        definition_id,
        idea_id,
        owner_scope,
        name_ko,
        source,
        pattern,
        growth_field,
        values,
        condition_id,
        host_scope,
        stack_group,
        fixed_tradeoff,
    )


ACTIVES = (
    active("aq.skill.warrior.w2.redoath", "W006", "WARRIOR", "붉은 서약", "CHARACTER_UNIQUE", "SELF_DAMAGE_BURST", "action_coefficient_bps", (14_000, 14_300, 14_600, 14_900, 15_200), 1, 15_200, fixed_self_hp_cost_bps=1_200, fixed_resource_cost_bps=2_500, fixed_downside="현재 HP 12%를 Shield 무시 비용으로 지불"),
    active("aq.skill.warrior.w2.preparedsmash", "W014", "WARRIOR", "준비된 강타", "LEVEL", "DELAYED_BURST", "action_coefficient_bps", (18_000, 18_500, 19_000, 19_500, 20_000), 2, 20_000, final_hit_policy="CLAMP_MIN", fixed_resource_cost_bps=3_000, fixed_downside="준비 1행동은 피해 0, 준비 해제시 실패"),

    active("aq.skill.rogue.w2.poisoncoat", "ROG010", "ROGUE", "독 묻히기", "LEVEL", "PRIMER_POISON", "action_coefficient_bps", (5_500, 5_625, 5_750, 5_875, 6_000), 1, 6_000, applies_tag="POISON", chain_group_id="aq.chain.poison.rogue", fixed_resource_cost_bps=1_500, fixed_downside="POISON 면역에는 즉시 피해만 남음"),
    active("aq.skill.rogue.w2.twinknifeflurry", "ROG011", "ROGUE", "쌍단검 난무", "LEVEL", "DETONATOR_POISON", "detonated_total_coefficient_bps", (16_000, 16_500, 17_000, 17_500, 18_000), 1, 8_000, consumes_tag="POISON", chain_group_id="aq.chain.poison.rogue", fixed_resource_cost_bps=2_500, hit_packets=4, fixed_downside="POISON 없으면 총 계수 8,000"),

    active("aq.skill.ranger.w2.poisonarrow", "HUN012", "RANGER", "독화살", "LEVEL", "PRIMER_POISON", "action_coefficient_bps", (6_500, 6_625, 6_750, 6_875, 7_000), 1, 7_000, applies_tag="POISON", chain_group_id="aq.chain.poison.ranger", fixed_resource_cost_bps=1_200, fixed_downside="POISON 면역과 짧은 전투에 약함"),
    active("aq.skill.ranger.w2.rapidfire", "HUN013", "RANGER", "속사", "LEVEL", "DETONATOR_POISON", "detonated_total_coefficient_bps", (14_000, 14_500, 15_000, 15_500, 16_000), 1, 9_000, consumes_tag="POISON", chain_group_id="aq.chain.poison.ranger", fixed_resource_cost_bps=1_800, hit_packets=3, fixed_downside="POISON 없으면 총 계수 9,000, 3타 개별 방어 영향"),

    active("aq.skill.mage.w2.fireball", "MAG010", "MAGE", "화염구", "LEVEL", "PRIMER_BURN", "action_coefficient_bps", (7_200, 7_325, 7_450, 7_575, 7_700), 1, 7_700, applies_tag="BURN", chain_group_id="aq.chain.burn", fixed_resource_cost_bps=1_800, fixed_downside="즉시 피해가 BASIC보다 낮음"),
    active("aq.skill.mage.w2.frostspear", "MAG011", "MAGE", "서리창", "LEVEL", "HIGH_HIT_LOW_POWER", "action_coefficient_bps", (7_500, 7_750, 8_000, 8_250, 8_500), 1, 8_500, final_hit_policy="FIXED_9500", crit_eligible=False, applies_tag="CHILL", fixed_resource_cost_bps=1_500, fixed_downside="최종 명중 95%, 치명타 불가, 낮은 계수"),

    active("aq.skill.cleric.w2.blessing", "CLE011", "CLERIC", "축복", "LEVEL", "ACCURACY_PRIMER", "next_two_hit_floor_bps", (8_000, 8_375, 8_750, 9_125, 9_500), 1, 0, applies_tag="BLESSING", chain_group_id="aq.chain.blessing", fixed_resource_cost_bps=1_500, hit_packets=0, fixed_downside="현재 행동의 피해·회복 0, 2 stack 후 소멸"),
    active("aq.skill.cleric.w2.sacredchain", "CLE012", "CLERIC", "신성 사슬", "LEVEL", "DETONATOR_BLESSING", "blessed_coefficient_bps", (14_000, 14_500, 15_000, 15_500, 16_000), 1, 7_000, final_hit_policy="FIXED_9500", consumes_tag="BLESSING", chain_group_id="aq.chain.blessing", fixed_resource_cost_bps=1_800, fixed_downside="축복 없으면 계수 7,000, 물리 적 SILENCE 실패"),

    active("aq.skill.paladin.w2.sacredbrand", "PAL011", "PALADIN", "신성 낙인", "LEVEL", "PRIMER_MARK", "action_coefficient_bps", (6_500, 6_625, 6_750, 6_875, 7_000), 1, 7_000, applies_tag="MARK", chain_group_id="aq.chain.mark.paladin", fixed_resource_cost_bps=2_000, fixed_downside="후속 신성 행동 없으면 낮은 효율"),
    active("aq.skill.paladin.w2.resolveburst", "PAL014", "PALADIN", "결의 폭발", "LEVEL", "DETONATOR_MARK", "marked_coefficient_bps", (12_000, 12_500, 13_000, 13_500, 14_000), 1, 7_000, consumes_tag="MARK", chain_group_id="aq.chain.mark.paladin", fixed_resource_cost_bps=10_000, fixed_downside="FULL_RESOURCE 필요·사용 후 자원 0, MARK 없으면 계수 7,000"),

    active("aq.skill.common.w2.precisestrike", "COM001", "ALL", "정확한 찌르기", "COMMON", "CLAMP_MAX_LOW_POWER", "action_coefficient_bps", (8_000, 8_250, 8_500, 8_750, 9_000), 1, 9_000, final_hit_policy="CLAMP_MAX", crit_eligible=False, fixed_resource_cost_bps=1_000, fixed_downside="최종 명중 99%, 치명타 불가, BASIC보다 낮은 계수"),
    active("aq.skill.common.w2.recklesssmash", "COM002", "ALL", "무모한 강타", "COMMON", "HIGH_POWER_CLAMP_MIN", "action_coefficient_bps", (16_000, 16_500, 17_000, 17_500, 18_000), 2, 18_000, final_hit_policy="CLAMP_MIN", crit_eligible=False, fixed_resource_cost_bps=2_500, fixed_downside="최종 명중 65%, 사용 후 회복 행동 1회, 치명타 불가"),
    active("aq.skill.common.w2.battlecry", "COM026", "ALL", "전투 함성", "COMMON", "BASIC_DOCTRINE", "next_two_basic_coefficient_add_bps", (3_500, 3_750, 4_000, 4_250, 4_500), 3, 0, applies_tag="BASIC_FOCUS", fixed_resource_cost_bps=1_500, hit_packets=0, fixed_downside="현재 행동 피해 0, 다음 BASIC 2회만 강화, Active에는 0"),

    active("aq.skill.external.w2.dragonscale", "EXT037", "ALL", "용의 역린", "BOSS", "DETONATOR_BURN", "burn_detonated_coefficient_bps", (13_000, 13_425, 13_850, 14_275, 14_700), 1, 6_000, consumes_tag="BURN", chain_group_id="aq.chain.burn", fixed_resource_cost_bps=3_000, fixed_downside="BURN 없으면 계수 6,000, 사용 후 자신 BURN 1 stack"),
)


PASSIVES = (
    passive("aq.skill.warrior.w2.painconversion", "W019", "WARRIOR", "고통 전환", "LEVEL", "SELF_DAMAGE_FOLLOWUP", "stored_next_basic_add_bps", (400, 500, 600, 700, 800), "after_unshieldable_self_damage", "NEXT_BASIC", "aq.stack.wave2.pain", "1회 소비·2턴 만료, 저장 상한 800"),
    passive("aq.skill.rogue.w2.poisonresistance", "ROG016", "ROGUE", "독 내성", "LEVEL", "POISON_STABILITY", "own_poison_apply_add_bps", (100, 175, 250, 325, 400), "poison_active_equipped", "OWN_POISON_PRIMER", "aq.stack.wave2.status_apply", "받는 POISON 피해 -500 고정, 비독 전투 공격 기여 0"),
    passive("aq.skill.ranger.w2.ammosaving", "HUN019", "RANGER", "탄약 절약", "LEVEL", "RANGED_RESOURCE", "fourth_ranged_cost_modifier_bps", (-200, -300, -400, -500, -600), "every_fourth_ranged_active", "CURRENT_RANGED_ACTIVE", "aq.stack.passive.resource_discount", "추가 RNG 없이 4번째만 적용, 근접·마법 0"),
    passive("aq.skill.mage.w2.elementalaffinity", "MAG015", "MAGE", "원소 친화", "LEVEL", "ELEMENT_SPECIALIZATION", "selected_element_coefficient_add_bps", (100, 150, 200, 250, 300), "selected_element_in_burn_chill_shock", "SELECTED_ELEMENT_ACTIVE", "aq.stack.passive.magic_coefficient", "비선택 원소 계수 -300, ARCANE 선택 불가"),
    passive("aq.skill.cleric.w2.calmbreath", "CLE020", "CLERIC", "평온한 호흡", "LEVEL", "LONG_FIGHT_RESOURCE", "round6plus_cost_modifier_bps", (-200, -300, -400, -500, -600), "owner_round_gte_6", "CURRENT_ACTIVE", "aq.stack.passive.resource_discount", "1~5턴 효과 0, 속공 조합에서 죽은 슬롯"),
    passive("aq.skill.paladin.w2.justiceweight", "PAL016", "PALADIN", "정의의 무게", "LEVEL", "BOSS_NICHE", "boss_action_coefficient_add_bps", (100, 200, 300, 400, 500), "monster_rank_boss", "PALADIN_ROOT_ACTION", "aq.stack.wave2.boss", "NORMAL·ELITE 효과 0"),
    passive("aq.skill.common.w2.statushunter", "COM043", "ALL", "흔들린 적 추적", "COMMON", "STATUS_ACCURACY", "statused_target_hit_add_bps", (100, 200, 300, 400, 500), "target_has_hostile_status", "CURRENT_ROOT_ACTION", "aq.stack.passive.hit", "무상태 적 효과 0, 최종 Hit 합산 cap +1,200"),
    passive("aq.skill.external.w2.crownvacancy", "EXT048", "ALL", "왕관의 공백", "SECRET", "COMMON_BUILD_AROUND", "common_effect_modifier_bps", (500, 750, 1_000, 1_250, 1_500), "no_character_unique_or_boss_active", "COMMON_ACTIVE_DECLARED_EFFECT", "aq.stack.wave2.common", "고유·보스 Active 1개라도 장착하면 효과 0"),
)


ACTIVE_BY_ID = {plan.definition_id: plan for plan in ACTIVES}
PASSIVE_BY_ID = {plan.definition_id: plan for plan in PASSIVES}


def round_half_up(value: Decimal) -> int:
    return int(value.quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def value_at(values: tuple[int, int, int, int, int], level: int) -> int:
    safe = max(1, min(100, int(level)))
    for index, right_level in enumerate(ANCHORS):
        if safe == right_level:
            return values[index]
        if safe < right_level:
            left_level = ANCHORS[index - 1]
            left_value = values[index - 1]
            ratio = Decimal(safe - left_level) / Decimal(right_level - left_level)
            return round_half_up(Decimal(left_value) + Decimal(values[index] - left_value) * ratio)
    return values[-1]


@lru_cache(maxsize=100)
def legacy_catalog(skill_level: int) -> tuple[object, ...]:
    return wave1.active_catalog(skill_level)


def accessible_actives(class_name: str, skill_level: int) -> tuple[object, ...]:
    legacy = wave1.class_actives(legacy_catalog(skill_level), class_name)
    new = tuple(plan for plan in ACTIVES if plan.owner_scope in {class_name, "ALL"})
    return legacy + new


def accessible_passives(class_name: str) -> tuple[object, ...]:
    legacy = wave1.class_passives(class_name)
    new = tuple(plan for plan in PASSIVES if plan.owner_scope in {class_name, "ALL"})
    return legacy + new


def definition_id(item: object) -> str:
    return item.definition_id


@dataclass(frozen=True)
class Package:
    package_id: str
    member_ids: frozenset[str]
    width: int
    coefficient_total: int


def has_id(ids: frozenset[str], suffix: str) -> bool:
    return any(value.endswith(suffix) for value in ids)


def crown_enabled(active_ids: frozenset[str], passive_ids: frozenset[str]) -> bool:
    if not has_id(passive_ids, "crownvacancy"):
        return False
    for definition in active_ids:
        plan = ACTIVE_BY_ID.get(definition)
        if plan is not None and plan.source in {"CHARACTER_UNIQUE", "BOSS"}:
            return False
    return True


def common_modified(value: int, active_ids: frozenset[str], passive_ids: frozenset[str], level: int) -> int:
    if not crown_enabled(active_ids, passive_ids):
        return value
    crown = next(plan for plan in PASSIVES if plan.definition_id.endswith("crownvacancy"))
    modifier = value_at(crown.values, level)
    return round_half_up(Decimal(value) * Decimal(10_000 + modifier) / Decimal(10_000))


def wave2_packages(
    class_name: str,
    active_ids: frozenset[str],
    passive_ids: frozenset[str],
    level: int,
    legacy_tactic_budget: int,
) -> tuple[Package, ...]:
    packages: list[Package] = []
    group_cap = wave1.TACTIC_GROUPS[class_name].encounter_cap
    if legacy_tactic_budget > 0:
        for use in range(group_cap):
            packages.append(Package(f"legacy.{use}", frozenset({f"legacy.{use}"}), 1, legacy_tactic_budget))

    def selected(suffix: str) -> ActivePlan | None:
        return next((ACTIVE_BY_ID[item] for item in active_ids if item in ACTIVE_BY_ID and item.endswith(suffix)), None)

    def add_single(suffix: str, width: int | None = None, coefficient: int | None = None) -> None:
        plan = selected(suffix)
        if plan is None:
            return
        raw = value_at(plan.values, level) if coefficient is None else coefficient
        if plan.source == "COMMON":
            raw = common_modified(raw, active_ids, passive_ids, level)
        packages.append(Package(plan.definition_id, frozenset({plan.definition_id}), width or plan.action_width, raw))

    # Class packages.  Paired packages share one group-use and consume the next root action.
    if class_name == "WARRIOR":
        red = selected("redoath")
        if red:
            red_value = value_at(red.values, level)
            if has_id(passive_ids, "painconversion"):
                pain = next(plan for plan in PASSIVES if plan.definition_id.endswith("painconversion"))
                packages.append(Package("warrior.redoath.pain", frozenset({red.definition_id}), 2, red_value + 10_000 + value_at(pain.values, level)))
            else:
                add_single("redoath")
        add_single("preparedsmash")
    elif class_name == "ROGUE":
        primer, det = selected("poisoncoat"), selected("twinknifeflurry")
        if primer and det:
            packages.append(Package("rogue.poison.chain", frozenset({primer.definition_id, det.definition_id}), 2, value_at(primer.values, level) + value_at(det.values, level)))
        add_single("poisoncoat")
        if det:
            add_single("twinknifeflurry", coefficient=det.fallback_coefficient_bps)
    elif class_name == "RANGER":
        primer, det = selected("poisonarrow"), selected("rapidfire")
        if primer and det:
            packages.append(Package("ranger.poison.chain", frozenset({primer.definition_id, det.definition_id}), 2, value_at(primer.values, level) + value_at(det.values, level)))
        add_single("poisonarrow")
        if det:
            add_single("rapidfire", coefficient=det.fallback_coefficient_bps)
    elif class_name == "MAGE":
        fire, dragon = selected("fireball"), selected("dragonscale")
        affinity = next((plan for plan in PASSIVES if plan.definition_id.endswith("elementalaffinity") and plan.definition_id in passive_ids), None)
        element_add = value_at(affinity.values, level) if affinity else 0
        if fire and dragon:
            packages.append(Package("mage.burn.chain", frozenset({fire.definition_id, dragon.definition_id}), 2, value_at(fire.values, level) + value_at(dragon.values, level) + 2 * element_add))
        if fire:
            add_single("fireball", coefficient=value_at(fire.values, level) + element_add)
        frost = selected("frostspear")
        if frost:
            add_single("frostspear", coefficient=value_at(frost.values, level) + element_add)
        if dragon:
            add_single("dragonscale", coefficient=dragon.fallback_coefficient_bps + element_add)
    elif class_name == "CLERIC":
        bless, chain = selected("blessing"), selected("sacredchain")
        if bless and chain:
            packages.append(Package("cleric.blessing.chain", frozenset({bless.definition_id, chain.definition_id}), 2, value_at(chain.values, level)))
        add_single("blessing", coefficient=0)
        if chain:
            add_single("sacredchain", coefficient=chain.fallback_coefficient_bps)
    elif class_name == "PALADIN":
        brand, burst = selected("sacredbrand"), selected("resolveburst")
        boss = next((plan for plan in PASSIVES if plan.definition_id.endswith("justiceweight") and plan.definition_id in passive_ids), None)
        boss_add = value_at(boss.values, level) if boss else 0
        if brand and burst:
            packages.append(Package("paladin.mark.chain", frozenset({brand.definition_id, burst.definition_id}), 2, value_at(brand.values, level) + value_at(burst.values, level) + 2 * boss_add))
        if brand:
            add_single("sacredbrand", coefficient=value_at(brand.values, level) + boss_add)
        if burst:
            add_single("resolveburst", coefficient=burst.fallback_coefficient_bps + boss_add)

    # Common packages and external fallback.  Their width includes forced recovery/setup actions.
    add_single("precisestrike")
    add_single("recklesssmash")
    cry = selected("battlecry")
    if cry:
        bonus = common_modified(value_at(cry.values, level), active_ids, passive_ids, level)
        packages.append(Package(cry.definition_id, frozenset({cry.definition_id}), 3, 2 * (10_000 + bonus)))
    dragon = selected("dragonscale")
    if dragon and class_name != "MAGE":
        add_single("dragonscale", coefficient=dragon.fallback_coefficient_bps)
    return tuple(packages)


def wave2_attack_envelope(class_name: str, active_ids: frozenset[str], passive_ids: frozenset[str], level: int) -> int:
    catalog = legacy_catalog(level)
    legacy_selected = [skill for skill in wave1.class_actives(catalog, class_name) if skill.definition_id in active_ids]
    a1 = next((skill for skill in legacy_selected if skill.slot_id == "A1_CORE"), None)
    a1_uses = wave1.legacy.c1.max_uses_in_ten(a1.cooldown_turns) if a1 else 0
    a1_total = a1_uses * a1.attack_budget_equivalent_bps if a1 else 0
    legacy_tactics = [skill for skill in legacy_selected if skill.slot_id == "A3_TACTIC_I"]
    legacy_budget = max((skill.attack_budget_equivalent_bps for skill in legacy_tactics), default=0)
    packages = wave2_packages(class_name, active_ids, passive_ids, level, legacy_budget)
    cap = wave1.TACTIC_GROUPS[class_name].encounter_cap
    best = a1_total + (10 - a1_uses) * 10_000
    for count in range(1, min(cap, len(packages)) + 1):
        for chosen in itertools.combinations(packages, count):
            used_members: set[str] = set()
            valid = True
            for package in chosen:
                if used_members.intersection(package.member_ids):
                    valid = False
                    break
                used_members.update(package.member_ids)
            if not valid:
                continue
            width = sum(package.width for package in chosen)
            remaining = 10 - a1_uses - width
            if remaining < 0:
                continue
            total = a1_total + sum(package.coefficient_total for package in chosen) + remaining * 10_000
            best = max(best, total)

    # Wave 1 Mage passives keep their old conservative ten-root allowance.
    if class_name == "MAGE":
        legacy_passives = tuple(item for item in passive_ids if item in wave1.PASSIVE_BY_ID)
        best += 10 * wave1.passive_modifier(legacy_passives, "action_coefficient_add_bps", level)
    return best


def canonical_hash() -> str:
    payload = {
        "wave1Hash": wave1.canonical_hash(),
        "anchors": ANCHORS,
        "actives": [plan.__dict__ for plan in sorted(ACTIVES, key=lambda item: item.definition_id)],
        "passives": [plan.__dict__ for plan in sorted(PASSIVES, key=lambda item: item.definition_id)],
        "sharedLedger": "TACTIC_SHARED",
        "primerFollowupCap": 1,
        "primerFollowupExpiryOwnerActions": 1,
        "hitClampBps": (6_500, 9_900),
        "costReductionCapBps": wave1.PASSIVE_COST_REDUCTION_CAP_BPS,
    }
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def check_catalog() -> None:
    assert len(ACTIVES) == 16 and len(PASSIVES) == 8
    assert len({plan.definition_id for plan in ACTIVES + PASSIVES}) == 24
    assert len({plan.idea_id for plan in ACTIVES + PASSIVES}) == 24
    assert sum(plan.owner_scope in CLASSES for plan in ACTIVES) == 12
    assert sum(plan.owner_scope in CLASSES for plan in PASSIVES) == 6
    assert sum(plan.owner_scope == "ALL" for plan in ACTIVES) == 4
    assert sum(plan.owner_scope == "ALL" for plan in PASSIVES) == 2
    assert {plan.pattern for plan in ACTIVES} >= {
        "HIGH_POWER_CLAMP_MIN", "CLAMP_MAX_LOW_POWER", "BASIC_DOCTRINE",
        "PRIMER_POISON", "DETONATOR_POISON", "DETONATOR_BURN",
    }


def check_idea_bank() -> None:
    text = IDEA_BANK.read_text(encoding="utf-8")
    for plan in ACTIVES + PASSIVES:
        assert f"| {plan.idea_id} | {plan.name_ko} |" in text


def check_growth() -> None:
    for plan in ACTIVES + PASSIVES:
        values = [value_at(plan.values, level) for level in range(1, 101)]
        increasing = plan.values[-1] >= plan.values[0]
        pairs = zip(values, values[1:])
        assert all(right >= left for left, right in pairs) if increasing else all(right <= left for left, right in pairs)
        assert tuple(values[level - 1] for level in ANCHORS) == plan.values
    assert next(plan for plan in ACTIVES if plan.idea_id == "COM002").final_hit_policy == "CLAMP_MIN"
    assert next(plan for plan in ACTIVES if plan.idea_id == "COM001").final_hit_policy == "CLAMP_MAX"
    assert not next(plan for plan in ACTIVES if plan.idea_id == "COM001").crit_eligible


def check_chain_contract() -> None:
    for tag in ("POISON", "BURN", "BLESSING", "MARK"):
        assert any(plan.applies_tag == tag for plan in ACTIVES)
        assert any(plan.consumes_tag == tag for plan in ACTIVES)
    for plan in ACTIVES:
        if plan.consumes_tag:
            assert plan.chain_group_id
            assert plan.fallback_coefficient_bps < plan.values[-1]
    assert max(plan.hit_packets for plan in ACTIVES) == 4
    assert all(plan.action_width >= 1 for plan in ACTIVES)


def check_accessible_counts() -> None:
    for class_name in CLASSES:
        assert len(accessible_actives(class_name, 100)) == 11
        assert len(accessible_passives(class_name)) == 6


def check_all_loadouts() -> None:
    checked = 0
    # Every subset at five anchors.
    for level in ANCHORS:
        for class_name in CLASSES:
            actives = accessible_actives(class_name, level)
            passives = accessible_passives(class_name)
            for active_mask in range(1 << len(actives)):
                active_ids = frozenset(definition_id(item) for index, item in enumerate(actives) if active_mask & (1 << index))
                if len(active_ids) > 5:
                    continue
                for passive_mask in range(1 << len(passives)):
                    passive_ids = frozenset(definition_id(item) for index, item in enumerate(passives) if passive_mask & (1 << index))
                    if len(passive_ids) > 3:
                        continue
                    envelope = wave2_attack_envelope(class_name, active_ids, passive_ids, level)
                    assert envelope <= ATTACK_CAPS[class_name], (class_name, level, active_ids, passive_ids, envelope)
                    checked += 1
    # Full 5+3 builds at all 100 levels cover every interpolated value.  Upper-envelope
    # monotonicity means smaller builds were already bounded by the anchor subset sweep.
    full_checked = 0
    for level in range(1, 101):
        for class_name in CLASSES:
            actives = accessible_actives(class_name, level)
            passives = accessible_passives(class_name)
            for active_combo in itertools.combinations(actives, 5):
                active_ids = frozenset(definition_id(item) for item in active_combo)
                for passive_combo in itertools.combinations(passives, 3):
                    passive_ids = frozenset(definition_id(item) for item in passive_combo)
                    assert wave2_attack_envelope(class_name, active_ids, passive_ids, level) <= ATTACK_CAPS[class_name]
                    full_checked += 1
    assert checked == 1_290_240
    assert full_checked == 5_544_000
    check_all_loadouts.checked = checked + full_checked


def target_hit_modifier(attacker, defender, policy: str) -> int:
    base = wave1.legacy.c1.core.hit_bps(attacker, defender)
    target = {"CLAMP_MIN": 6_500, "CLAMP_MAX": 9_900, "FIXED_9500": 9_500}.get(policy)
    return 0 if target is None else target - base


def attack_once(hero, monster, coefficient: int, seed: int, action: int, key: str, policy: str = "NORMAL", crit: bool = True) -> tuple[int, bool]:
    return wave1.legacy.c1.resolved_attack(
        hero,
        monster,
        coefficient,
        wave1.legacy.c1.core_rolls(seed, action, "HERO", key),
        hit_modifier_bps=target_hit_modifier(hero, monster, policy),
        crit_eligible=crit,
    )


def combatants(class_name: str, profile: str, display_level: int, band: str):
    source = wave1.legacy.c1.six.source_for_display(class_name, display_level, band)
    hero = wave1.legacy.c1.six.derive_hero(source).combatant
    monster = wave1.legacy.c1.six.monster_for(source, profile, "BOSS")
    return source, hero, monster


def common_pattern_metrics(seeds: int) -> dict[str, float]:
    exact_hits = reckless_hits = total = 0
    exact_damage = reckless_damage = basic_one_damage = basic_two_damage = 0
    reckless_hit_damage: list[float] = []
    for class_name in CLASSES:
        for profile in wave1.legacy.c1.PROFILES:
            source, hero, monster = combatants(class_name, profile, 58, "STRESS_ALL3")
            key = f"W2.COMMON.{class_name}.{profile}"
            for seed in range(seeds):
                basic1, _ = attack_once(hero, monster, 10_000, seed, 1, key)
                basic2, _ = attack_once(hero, monster, 10_000, seed, 2, key)
                exact, eh = attack_once(hero, monster, 9_000, seed, 3, key, "CLAMP_MAX", False)
                reckless, rh = attack_once(hero, monster, 18_000, seed, 4, key, "CLAMP_MIN", False)
                total += 1
                exact_hits += int(eh)
                reckless_hits += int(rh)
                exact_damage += exact
                reckless_damage += reckless
                basic_one_damage += basic1
                basic_two_damage += basic1 + basic2
                if rh and basic1 > 0:
                    reckless_hit_damage.append(reckless / basic1)
    return {
        "exactHitRate": exact_hits / total,
        "recklessHitRate": reckless_hits / total,
        "exactVsOneBasicDamage": exact_damage / max(1, basic_one_damage),
        "recklessVsTwoBasicDamage": reckless_damage / max(1, basic_two_damage),
        "recklessHitVsBasicMedian": statistics.median(reckless_hit_damage),
    }


COMBO_SPECS = {
    "ROGUE": (6_000, 18_000, 8_000, 9_000, 400, "POISON"),
    "RANGER": (7_000, 16_000, 9_000, 9_000, 0, "POISON"),
    "MAGE": (7_700, 14_700, 6_000, 9_000, 0, "BURN"),
    "PALADIN": (7_000, 14_000, 7_000, 9_000, 0, "MARK"),
}


def combo_metrics(class_name: str, seeds: int, immune: bool) -> dict[str, float]:
    primer_coeff, det_coeff, fallback, base_apply, passive_apply, tag = COMBO_SPECS[class_name]
    total_combo = total_basic = successes = trials = 0
    for display_level in (58, 9_999):
        for band in ("BASE", "STRESS_ALL3"):
            for profile in wave1.legacy.c1.PROFILES:
                source, hero, monster = combatants(class_name, profile, display_level, band)
                resistance = wave1.legacy.c1.monster_status_resistance(source.combat_rank, profile)
                apply_bps = max(1_000, min(9_500, base_apply + passive_apply - 40 * resistance))
                key = f"W2.COMBO.{class_name}.{display_level}.{band}.{profile}.{immune}"
                for seed in range(seeds):
                    basic1, _ = attack_once(hero, monster, 10_000, seed, 1, key)
                    basic2, _ = attack_once(hero, monster, 10_000, seed, 2, key)
                    primer, primer_hit = attack_once(hero, monster, primer_coeff, seed, 3, key)
                    status_roll = wave1.legacy.c1.event_roll_bps(seed, 3, "W2_STATUS", f"{key}.{tag}")
                    applied = primer_hit and not immune and status_roll < apply_bps
                    detonator, _ = attack_once(hero, monster, det_coeff if applied else fallback, seed, 4, key)
                    total_basic += basic1 + basic2
                    total_combo += primer + detonator
                    successes += int(applied)
                    trials += 1
    return {"damageRatio": total_combo / max(1, total_basic), "statusSuccess": successes / trials}


def doctrine_metrics() -> dict[str, int]:
    base_bonus = value_at(next(plan for plan in ACTIVES if plan.idea_id == "COM026").values, 100)
    crown = value_at(next(plan for plan in PASSIVES if plan.idea_id == "EXT048").values, 100)
    boosted = round_half_up(Decimal(base_bonus) * Decimal(10_000 + crown) / Decimal(10_000))
    return {
        "threeBasics": 30_000,
        "battleCryOnly": 2 * (10_000 + base_bonus),
        "battleCryCrown": 2 * (10_000 + boosted),
    }


def warrior_cleric_metrics(seeds: int) -> dict[str, dict[str, float]]:
    warrior_basic_one = warrior_basic_two = red_damage = prepared_damage = pain_damage = 0
    red_hits = prepared_hits = trials = 0
    prepared_packet_ratios: list[float] = []
    cleric_basic_two = cleric_blessed_damage = 0
    cleric_normal_hits = cleric_blessed_hits = 0
    for display_level in (58, 9_999):
        for band in ("BASE", "STRESS_ALL3"):
            for profile in wave1.legacy.c1.PROFILES:
                for class_name in ("WARRIOR", "CLERIC"):
                    _, hero, monster = combatants(class_name, profile, display_level, band)
                    key = f"W2.CLASS.{class_name}.{display_level}.{band}.{profile}"
                    for seed in range(seeds):
                        basic1, _ = attack_once(hero, monster, 10_000, seed, 1, key)
                        basic2, _ = attack_once(hero, monster, 10_000, seed, 2, key)
                        if class_name == "WARRIOR":
                            red, red_hit = attack_once(hero, monster, 15_200, seed, 3, key)
                            prepared, prepared_hit = attack_once(hero, monster, 20_000, seed, 4, key, "CLAMP_MIN")
                            pain, _ = attack_once(hero, monster, 10_800, seed, 5, key)
                            warrior_basic_one += basic1
                            warrior_basic_two += basic1 + basic2
                            red_damage += red
                            prepared_damage += prepared
                            pain_damage += red + pain
                            red_hits += int(red_hit)
                            prepared_hits += int(prepared_hit)
                            trials += 1
                            if prepared_hit and basic1 > 0:
                                prepared_packet_ratios.append(prepared / basic1)
                        else:
                            normal, normal_hit = attack_once(hero, monster, 16_000, seed, 3, key)
                            blessed, blessed_hit = attack_once(hero, monster, 16_000, seed, 4, key, "FIXED_9500")
                            cleric_basic_two += basic1 + basic2
                            cleric_blessed_damage += blessed
                            cleric_normal_hits += int(normal_hit)
                            cleric_blessed_hits += int(blessed_hit)
    warrior = {
        "redHitRate": red_hits / trials,
        "redVsOneBasicDamage": red_damage / max(1, warrior_basic_one),
        "redSelfHpCostRate": 0.12,
        "preparedHitRate": prepared_hits / trials,
        "preparedVsTwoBasicDamage": prepared_damage / max(1, warrior_basic_two),
        "preparedHitPacketMedian": statistics.median(prepared_packet_ratios),
        "redPainVsTwoBasicDamage": pain_damage / max(1, warrior_basic_two),
    }
    cleric_trials = trials
    cleric = {
        "normalHitRate": cleric_normal_hits / cleric_trials,
        "blessedHitRate": cleric_blessed_hits / cleric_trials,
        "blessingChainVsTwoBasicDamage": cleric_blessed_damage / max(1, cleric_basic_two),
    }
    return {"WARRIOR": warrior, "CLERIC": cleric}


def dynamic_review(seeds: int) -> dict:
    common = common_pattern_metrics(seeds)
    assert 0.97 <= common["exactHitRate"] <= 1.0
    assert 0.62 <= common["recklessHitRate"] <= 0.68
    assert common["exactVsOneBasicDamage"] < 1.0
    assert common["recklessVsTwoBasicDamage"] < 0.80
    assert common["recklessHitVsBasicMedian"] >= 1.60

    combos = {}
    for class_name in COMBO_SPECS:
        normal = combo_metrics(class_name, seeds, False)
        immune = combo_metrics(class_name, seeds, True)
        assert 0.75 <= normal["damageRatio"] <= 1.25, (class_name, normal)
        assert normal["statusSuccess"] >= 0.50, (class_name, normal)
        assert immune["damageRatio"] <= 0.85, (class_name, immune)
        assert normal["damageRatio"] > immune["damageRatio"] + 0.10, (class_name, normal, immune)
        combos[class_name] = {"normal": normal, "immune": immune}

    doctrine = doctrine_metrics()
    assert doctrine["battleCryOnly"] < doctrine["threeBasics"]
    assert doctrine["battleCryCrown"] > doctrine["threeBasics"]
    assert doctrine["battleCryCrown"] <= 30_400
    class_patterns = warrior_cleric_metrics(seeds)
    warrior = class_patterns["WARRIOR"]
    cleric = class_patterns["CLERIC"]
    assert 1.25 <= warrior["redVsOneBasicDamage"] <= 1.70
    assert warrior["redSelfHpCostRate"] == 0.12
    assert 0.45 <= warrior["preparedVsTwoBasicDamage"] <= 0.90
    assert warrior["preparedHitPacketMedian"] >= 1.80
    assert warrior["redPainVsTwoBasicDamage"] <= 1.35
    assert cleric["blessedHitRate"] >= 0.92
    assert cleric["blessedHitRate"] >= cleric["normalHitRate"] + 0.03
    assert cleric["blessingChainVsTwoBasicDamage"] <= 0.95
    return {"common": common, "combos": combos, "doctrine": doctrine, "classPatterns": class_patterns}


STATIC_CHECKS = (
    ("WAVE2_HAS_16_ACTIVE_8_PASSIVE", check_catalog),
    ("ALL_24_IDS_EXIST_IN_240_IDEA_BANK", check_idea_bank),
    ("ALL_24_HAVE_MONOTONE_SINGLE_AXIS_LEVELS", check_growth),
    ("PRIMER_DETONATOR_ATOMIC_CONSUMPTION_CONTRACT", check_chain_contract),
    ("EACH_CLASS_ACCESSES_11_ACTIVE_6_PASSIVE", check_accessible_counts),
    ("ALL_6834240_WAVE1_WAVE2_LOADOUT_LEVEL_CHECKS_KEEP_CAPS", check_all_loadouts),
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=100)
    parser.add_argument("--pd", action="store_true")
    args = parser.parse_args()
    seeds = 200 if args.pd else args.seeds
    assert seeds >= 50

    passed: list[str] = []
    for name, check in STATIC_CHECKS:
        check()
        passed.append(name)
    dynamic = dynamic_review(seeds)
    passed.append("PAIRED_PATTERN_MICRO_ROTATIONS_PASS_GATES")
    if DOCUMENT.exists():
        assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")
        passed.append("DOCUMENT_CONTAINS_CANONICAL_WAVE2_HASH")

    print(f"SKILL_WAVE2_COMBO_PATTERNS_V0_1: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  loadoutLevelChecks={check_all_loadouts.checked}")
    print("  accessiblePerClass=Active11 Passive6 equip5+3")
    common = dynamic["common"]
    print(
        "  common exactHit={:.2%} exactDamage={:.3f} recklessHit={:.2%} recklessTwoActionDamage={:.3f} recklessHitPacket={:.3f}".format(
            common["exactHitRate"], common["exactVsOneBasicDamage"], common["recklessHitRate"],
            common["recklessVsTwoBasicDamage"], common["recklessHitVsBasicMedian"],
        )
    )
    for class_name, values in dynamic["combos"].items():
        print(
            f"  {class_name:8} comboDamage={values['normal']['damageRatio']:.3f} "
            f"status={values['normal']['statusSuccess']:.2%} immuneDamage={values['immune']['damageRatio']:.3f}"
        )
    warrior = dynamic["classPatterns"]["WARRIOR"]
    cleric = dynamic["classPatterns"]["CLERIC"]
    print(
        f"  WARRIOR  redDamage={warrior['redVsOneBasicDamage']:.3f} selfHp={warrior['redSelfHpCostRate']:.2%} "
        f"preparedTwoAction={warrior['preparedVsTwoBasicDamage']:.3f} painPackage={warrior['redPainVsTwoBasicDamage']:.3f}"
    )
    print(
        f"  CLERIC   normalHit={cleric['normalHitRate']:.2%} blessedHit={cleric['blessedHitRate']:.2%} "
        f"blessingChainDamage={cleric['blessingChainVsTwoBasicDamage']:.3f}"
    )
    doctrine = dynamic["doctrine"]
    print(f"  doctrine basics={doctrine['threeBasics']} cryOnly={doctrine['battleCryOnly']} cryCrown={doctrine['battleCryCrown']}")
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
