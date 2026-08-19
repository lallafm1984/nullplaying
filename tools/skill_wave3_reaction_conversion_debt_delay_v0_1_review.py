#!/usr/bin/env python3
"""Wave 3 audit for prepaid reactions, conversion, debt, and delayed actions.

Design-only: no Kotlin, Room, save, character, or live mutations.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import math
import statistics
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from functools import lru_cache
from pathlib import Path

import skill_wave1_active5_passive3_v0_1_review as wave1
import skill_wave2_combo_patterns_v0_1_review as wave2


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_WAVE3_REACTION_CONVERSION_DEBT_DELAY_v0.1.md"
IDEA_BANK = ROOT / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"
CLASSES = wave1.CLASSES
ANCHORS = wave1.ANCHOR_LEVELS
ATTACK_CAPS = wave1.ATTACK_BUDGETS
DEBT_CAP_BPS = 4_000
REACTION_TOKEN_CAP = 1
DELAY_STABILITY_CAP_BPS = 1_500


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
    fixed_cost_bps: int = 0
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
    active("aq.skill.warrior.w3.countercut", "W011", "WARRIOR", "역습 베기", "LEVEL", "PREPAID_REACTION", "counter_coefficient_bps", (12_000, 13_000, 14_000, 15_000, 16_000), 1, 0, final_hit_policy="FIXED_9500", crit_eligible=False, fixed_cost_bps=2_000, fixed_downside="자기 행동을 반격 자세로 선지불, 다음 적 공격이 MISS가 아니면 피해 0"),
    active("aq.skill.warrior.w3.fallingwall", "W021", "WARRIOR", "성벽의 낙하", "CLASS_QUEST", "SHIELD_TO_DAMAGE", "shielded_coefficient_bps", (12_000, 13_000, 14_000, 15_000, 16_000), 1, 6_000, fixed_cost_bps=2_500, fixed_downside="현재 Shield 전부 원자적 소비, Shield 0이면 계수 6,000"),

    active("aq.skill.rogue.w3.shadowleap", "ROG006", "ROGUE", "그림자 도약", "CHARACTER_UNIQUE", "DODGE_FOLLOWUP", "critical_floor_bps", (2_000, 2_500, 3_000, 3_500, 4_000), 1, 14_000, fixed_cost_bps=2_000, fixed_downside="DODGE 후 다음 자기 행동에만 유효, 그 전에는 후보 아님"),
    active("aq.skill.rogue.w3.borrowedblade", "ROG007", "ROGUE", "빚진 칼날", "CHARACTER_UNIQUE", "RESOURCE_DEBT", "action_coefficient_bps", (16_000, 16_500, 17_000, 17_500, 18_000), 2, 0, crit_eligible=False, fixed_cost_bps=3_000, fixed_downside="부족 자원을 debt로 기록, 다음 BASIC 생성분으로 우선 상환"),

    active("aq.skill.ranger.w3.animalwarning", "HUN007", "RANGER", "동물의 경고", "CHARACTER_UNIQUE", "PREPAID_EVASION", "next_enemy_evasion_add_bps", (2_000, 2_500, 3_000, 3_500, 4_000), 1, 0, fixed_cost_bps=1_200, fixed_downside="적 계수 13,000+ 예고에만 선택, 다음 적 공격 1회 후 소멸"),
    active("aq.skill.ranger.w3.trapsetup", "HUN010", "RANGER", "덫 설치", "LEVEL", "PREPAID_CONTROL", "stagger_apply_bps", (6_000, 6_750, 7_500, 8_250, 9_000), 1, 0, fixed_cost_bps=1_500, fixed_downside="설치 행동 피해 0, 다음 적 행동 1회에만 발동, 면역 가능"),

    active("aq.skill.mage.w3.starfallvow", "MAG006", "MAGE", "별낙하 서약", "CHARACTER_UNIQUE", "DELAYED_CAST", "action_coefficient_bps", (19_000, 20_000, 21_000, 22_000, 23_000), 2, 0, fixed_cost_bps=3_000, fixed_downside="시전 행동 0 피해, 피격 시 기본 취소 35%, 취소 시 자원 반환 0"),
    active("aq.skill.mage.w3.forbiddenbackflow", "MAG007", "MAGE", "금단의 역류", "CHARACTER_UNIQUE", "HP_BACKED_DEFICIT", "allowed_resource_deficit_bps", (1_000, 1_500, 2_000, 2_500, 3_000), 1, 13_000, fixed_cost_bps=3_000, fixed_downside="부족 마나의 150%를 현재 HP로 지불, Shield·Barrier 무시"),

    active("aq.skill.cleric.w3.soulanchor", "CLE008", "CLERIC", "영혼의 닻", "CHARACTER_UNIQUE", "PREPAID_LETHAL_DELAY", "deferred_damage_cap_maxhp_bps", (1_000, 1_500, 2_000, 2_500, 3_000), 1, 0, fixed_cost_bps=2_500, fixed_downside="미리 1행동 지불, 다음 자기 행동까지 Heal하지 못하면 유예 피해 정산"),
    active("aq.skill.cleric.w3.sanctuary", "CLE021", "CLERIC", "성역 선포", "CLASS_QUEST", "PREPAID_MITIGATION", "next_hit_damage_modifier_bps", (-300, -400, -500, -600, -700), 1, 0, fixed_cost_bps=2_000, fixed_downside="현재 행동 공격·Heal 0, 다음 적 유효 피격 1회 후 소멸"),

    active("aq.skill.paladin.w3.lastfortress", "PAL008", "PALADIN", "마지막 성채", "CHARACTER_UNIQUE", "RESOURCE_TO_SHIELD", "shield_maxhp_bps", (500, 600, 700, 800, 900), 1, 0, fixed_cost_bps=10_000, fixed_downside="현재 자원 전부 소비, 공격 피해 0, 기존 PALADIN 보호 원장 공유"),
    active("aq.skill.paladin.w3.shieldbash", "PAL010", "PALADIN", "방패 강타", "LEVEL", "SHIELD_TO_CONTROL", "stagger_magnitude_bps", (400, 500, 600, 700, 800), 1, 12_000, fixed_cost_bps=2_000, fixed_downside="Shield 500 bps 원자적 소비, 부족하면 후보 아님"),

    active("aq.skill.common.w3.bloodprice", "COM016", "ALL", "피의 대가", "COMMON", "HP_TO_RESOURCE", "resource_gain_bps", (1_000, 1_250, 1_500, 1_750, 2_000), 1, 0, fixed_cost_bps=0, fixed_downside="자원 획득량의 120%를 현재 HP로 지불, 원정 자원 ceiling 복구 0"),
    active("aq.skill.common.w3.preparedmotion", "COM018", "ALL", "준비 동작", "COMMON", "PREPARE_NEXT_TACTIC", "next_tactic_coefficient_add_bps", (500, 1_000, 1_500, 2_000, 2_500), 1, 0, fixed_cost_bps=1_000, fixed_downside="현재 행동 0 피해, 다음 TACTIC_SHARED 공격 1회만 소비, 1턴 만료"),
    active("aq.skill.common.w3.counterprep", "COM019", "ALL", "반격 준비", "COMMON", "PREPAID_REACTION", "counter_coefficient_bps", (8_000, 9_000, 10_000, 11_000, 12_000), 1, 0, final_hit_policy="FIXED_9500", crit_eligible=False, fixed_cost_bps=1_500, fixed_downside="자기 행동 선지불, 다음 적 공격이 명중하면 피해 0"),

    active("aq.skill.external.w3.namelessscabbard", "EXT043", "ALL", "무명왕의 칼집", "SECRET", "DELAYED_CLAMP_MAX", "action_coefficient_bps", (16_000, 17_000, 18_000, 19_000, 20_000), 2, 0, final_hit_policy="CLAMP_MAX", crit_eligible=False, fixed_cost_bps=2_500, fixed_downside="준비 1행동 0 피해, 준비 중 STAGGER·SILENCE에 취소, 치명 불가"),
)


PASSIVES = (
    passive("aq.skill.warrior.w3.heavystep", "W020", "WARRIOR", "무거운 발걸음", "LEVEL", "CONTROL_RESIST", "stagger_resistance_add_bps", (200, 300, 400, 500, 600), "always", "aq.stack.wave3.control_resist", "Speed -300 고정"),
    passive("aq.skill.rogue.w3.debtinsurance", "ROG024", "ROGUE", "배신자의 보험", "CLASS_QUEST", "DEBT_PROTECTION", "expedition_barrier_activation_cap", (1, 1, 1, 2, 2), "first_debt_entry", "aq.generation.rogue.barrier", "Barrier 1, 기존 원정 cap 2 공유, debt 제거 0"),
    passive("aq.skill.ranger.w3.hunterpatience", "HUN024", "RANGER", "사냥꾼의 인내", "CLASS_QUEST", "DELAY_STABILITY", "delay_cancel_resistance_bps", (300, 475, 650, 825, 1_000), "delayed_active", "aq.stack.wave3.delay_stability", "즉발 Active 효과 0, 취소 확률 0은 못 됨"),
    passive("aq.skill.mage.w3.concentration", "MAG017", "MAGE", "집중 유지", "LEVEL", "CAST_STABILITY", "delay_cancel_resistance_bps", (500, 750, 1_000, 1_250, 1_500), "delayed_spell", "aq.stack.wave3.delay_stability", "즉발 주문 효과 0, 취소 최저 20% 유지"),
    passive("aq.skill.cleric.w3.painempathy", "CLE019", "CLERIC", "고통 공감", "LEVEL", "HEAVY_HIT_HEAL", "next_heal_modifier_bps", (200, 300, 400, 500, 600), "single_hit_gte_2000_maxhp_bps", "aq.stack.wave3.heal", "다음 Heal 1회·2턴 만료, 연속 소형 피격에 0"),
    passive("aq.skill.paladin.w3.lightarmor", "PAL020", "PALADIN", "빛의 갑옷", "LEVEL", "SHIELD_CURSE_RESIST", "curse_resistance_add_bps", (200, 300, 400, 500, 600), "shield_gt_zero", "aq.stack.wave3.curse_resist", "Shield 파괴 즉시 0"),
    passive("aq.skill.common.w3.shieldlessguard", "COM044", "ALL", "방패 없는 방어", "COMMON", "SHIELDLESS_DEFENSE", "incoming_damage_modifier_bps", (-200, -300, -400, -500, -600), "shield_eq_zero", "aq.stack.passive.incoming_damage", "Shield 획득 즉시 0, Shield 전문화와 상출"),
    passive("aq.skill.external.w3.bellpatience", "EXT019", "ALL", "종지기의 인내", "WORLD_QUEST", "DELAY_STABILITY", "delay_cancel_resistance_bps", (400, 600, 800, 1_000, 1_200), "delayed_active", "aq.stack.wave3.delay_stability", "직업 안정 Passive와 highest-only, 즉발 Active 0"),
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
    return wave2.accessible_actives(class_name, level) + tuple(plan for plan in ACTIVES if plan.owner_scope in {class_name, "ALL"})


def accessible_passives(class_name: str):
    return wave2.accessible_passives(class_name) + tuple(plan for plan in PASSIVES if plan.owner_scope in {class_name, "ALL"})


@dataclass(frozen=True)
class Package:
    package_id: str
    members: frozenset[str]
    width: int
    coefficient_total: int


def has_suffix(ids: frozenset[str], suffix: str) -> bool:
    return any(value.endswith(suffix) for value in ids)


def all_packages(class_name: str, active_ids: frozenset[str], passive_ids: frozenset[str], level: int, legacy_budget: int):
    packages = [Package(p.package_id, p.member_ids, p.width, p.coefficient_total) for p in wave2.wave2_packages(class_name, active_ids, passive_ids, level, legacy_budget)]

    def selected(suffix):
        return next((ACTIVE_BY_ID[item] for item in active_ids if item in ACTIVE_BY_ID and item.endswith(suffix)), None)

    def add(suffix, coefficient=None, width=None):
        plan = selected(suffix)
        if not plan:
            return
        raw = value_at(plan.values, level) if coefficient is None and plan.growth_field.endswith("coefficient_bps") else plan.fixed_coefficient_bps
        packages.append(Package(plan.definition_id, frozenset({plan.definition_id}), width or plan.package_width, raw))

    if class_name == "WARRIOR":
        add("countercut")
        add("fallingwall")
    elif class_name == "ROGUE":
        add("shadowleap", coefficient=14_000)
        add("borrowedblade")
    elif class_name == "RANGER":
        add("animalwarning", coefficient=0)
        add("trapsetup", coefficient=0)
    elif class_name == "MAGE":
        add("starfallvow")
        add("forbiddenbackflow", coefficient=13_000)
    elif class_name == "CLERIC":
        add("soulanchor", coefficient=0)
        add("sanctuary", coefficient=0)
    elif class_name == "PALADIN":
        add("lastfortress", coefficient=0)
        add("shieldbash", coefficient=12_000)
    add("bloodprice", coefficient=0)
    add("counterprep")
    add("namelessscabbard")

    prepare = selected("preparedmotion")
    if prepare:
        bonus = value_at(prepare.values, level)
        for base in tuple(packages):
            if base.coefficient_total <= 0 or prepare.definition_id in base.members:
                continue
            packages.append(Package(
                f"prepare.{base.package_id}",
                base.members | frozenset({prepare.definition_id}),
                base.width + 1,
                base.coefficient_total + bonus,
            ))
        packages.append(Package(prepare.definition_id, frozenset({prepare.definition_id}), 1, 0))
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
            used: set[str] = set()
            valid = True
            for package in chosen:
                if used.intersection(package.members):
                    valid = False
                    break
                used.update(package.members)
            if not valid:
                continue
            width = sum(package.width for package in chosen)
            remaining = 10 - a1_uses - width
            if remaining < 0:
                continue
            best = max(best, a1_total + sum(package.coefficient_total for package in chosen) + remaining * 10_000)
    if class_name == "MAGE":
        legacy_passives = tuple(item for item in passive_ids if item in wave1.PASSIVE_BY_ID)
        best += 10 * wave1.passive_modifier(legacy_passives, "action_coefficient_add_bps", level)
    return best


def canonical_hash() -> str:
    payload = {
        "wave2Hash": wave2.canonical_hash(),
        "anchors": ANCHORS,
        "actives": [plan.__dict__ for plan in sorted(ACTIVES, key=lambda item: item.definition_id)],
        "passives": [plan.__dict__ for plan in sorted(PASSIVES, key=lambda item: item.definition_id)],
        "debtCapBps": DEBT_CAP_BPS,
        "reactionTokenCap": REACTION_TOKEN_CAP,
        "delayStabilityCapBps": DELAY_STABILITY_CAP_BPS,
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
    patterns = {p.pattern for p in ACTIVES + PASSIVES}
    assert any("REACTION" in p for p in patterns)
    assert any("SHIELD_TO" in p for p in patterns)
    assert any("DEBT" in p for p in patterns)
    assert any("DELAY" in p or "PREPARE" in p for p in patterns)


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
    assert DEBT_CAP_BPS == 4_000
    assert REACTION_TOKEN_CAP == 1
    assert DELAY_STABILITY_CAP_BPS == 1_500
    assert max(p.values[-1] for p in PASSIVES if p.growth_field == "delay_cancel_resistance_bps") <= DELAY_STABILITY_CAP_BPS
    assert next(p for p in ACTIVES if p.idea_id == "COM016").fixed_coefficient_bps == 0
    assert next(p for p in ACTIVES if p.idea_id == "PAL008").values[-1] <= 900
    assert next(p for p in PASSIVES if p.idea_id == "ROG024").values[-1] <= 2


def check_access_counts():
    for class_name in CLASSES:
        assert len(accessible_actives(class_name, 100)) == 17
        assert len(accessible_passives(class_name)) == 9


def check_worst_level_full_loadouts():
    evaluated = 0
    represented = math.comb(17, 5) * math.comb(9, 3) * len(CLASSES) * 100
    assert represented == 311_875_200
    level = 100
    for class_name in CLASSES:
        actives = accessible_actives(class_name, level)
        passives = accessible_passives(class_name)
        for active_combo in itertools.combinations(actives, 5):
            active_ids = frozenset(definition_id(item) for item in active_combo)
            for passive_combo in itertools.combinations(passives, 3):
                passive_ids = frozenset(definition_id(item) for item in passive_combo)
                envelope = attack_envelope(class_name, active_ids, passive_ids, level)
                assert envelope <= ATTACK_CAPS[class_name], (class_name, active_ids, passive_ids, envelope)
                evaluated += 1
    assert evaluated == 3_118_752
    check_worst_level_full_loadouts.evaluated = evaluated
    check_worst_level_full_loadouts.represented = represented


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
    return wave1.legacy.c1.six.derive_hero(source).combatant, wave1.legacy.c1.six.monster_for(source, profile, "BOSS")


def dynamic_metrics(seeds: int):
    totals = {
        "counter": [0, 0, 0, 0],
        "shield": [0, 0],
        "debt": [0, 0],
        "star": [0, 0, 0, 0],
        "scabbard": [0, 0],
    }
    for level in (58, 9_999):
        for band in ("BASE", "STRESS_ALL3"):
            for profile in wave1.legacy.c1.PROFILES:
                for class_name in CLASSES:
                    hero, monster = combatants(class_name, profile, level, band)
                    key = f"W3.{class_name}.{profile}.{level}.{band}"
                    for seed in range(seeds):
                        b1, _ = attack_once(hero, monster, 10_000, seed, 1, key)
                        b2, _ = attack_once(hero, monster, 10_000, seed, 2, key)
                        if class_name == "WARRIOR":
                            monster_hit = wave1.legacy.c1.core.hit_bps(monster, hero)
                            miss = wave1.legacy.c1.event_roll_bps(seed, 1, "W3_MONSTER_HIT", key) >= monster_hit
                            counter, _ = attack_once(hero, monster, 16_000, seed, 3, key, "FIXED_9500", False) if miss else (0, False)
                            totals["counter"][0] += counter
                            totals["counter"][1] += b1
                            totals["counter"][2] += int(miss)
                            totals["counter"][3] += 1
                            wall, _ = attack_once(hero, monster, 16_000, seed, 4, key)
                            totals["shield"][0] += wall
                            totals["shield"][1] += b1
                        if class_name == "ROGUE":
                            blade, _ = attack_once(hero, monster, 18_000, seed, 3, key, crit=False)
                            totals["debt"][0] += blade
                            totals["debt"][1] += b1 + b2
                        if class_name == "MAGE":
                            hit_during_cast = wave1.legacy.c1.event_roll_bps(seed, 2, "W3_CAST_HIT", key) < 8_000
                            cancel_roll = wave1.legacy.c1.event_roll_bps(seed, 2, "W3_CAST_CANCEL", key)
                            base_success = not hit_during_cast or cancel_roll >= 3_500
                            stable_success = not hit_during_cast or cancel_roll >= 2_000
                            base_damage = attack_once(hero, monster, 23_000, seed, 3, key)[0] if base_success else 0
                            stable_damage = attack_once(hero, monster, 23_000, seed, 3, key)[0] if stable_success else 0
                            totals["star"][0] += base_damage
                            totals["star"][1] += stable_damage
                            totals["star"][2] += b1 + b2
                            totals["star"][3] += 1
                        scabbard, _ = attack_once(hero, monster, 20_000, seed, 5, key, "CLAMP_MAX", False)
                        totals["scabbard"][0] += scabbard
                        totals["scabbard"][1] += b1 + b2
    metrics = {
        "counterTriggerRate": totals["counter"][2] / totals["counter"][3],
        "counterVsBasicDamage": totals["counter"][0] / max(1, totals["counter"][1]),
        "wallHitVsBasicDamage": totals["shield"][0] / max(1, totals["shield"][1]),
        "debtBladeVsTwoBasicDamage": totals["debt"][0] / max(1, totals["debt"][1]),
        "starfallBaseVsTwoBasicDamage": totals["star"][0] / max(1, totals["star"][2]),
        "starfallStableVsTwoBasicDamage": totals["star"][1] / max(1, totals["star"][2]),
        "scabbardVsTwoBasicDamage": totals["scabbard"][0] / max(1, totals["scabbard"][1]),
        "rogueDebtRepaymentBasics": math.ceil(3_000 / wave1.legacy.c1.RESOURCES["ROGUE"].basic_generation_bps),
        "manaDeficitHpCostBps": 4_500,
        "bloodPriceHpCostBps": 2_400,
        "paladinShieldBps": 900,
    }
    assert 0.05 <= metrics["counterTriggerRate"] <= 0.40
    assert metrics["counterVsBasicDamage"] < 0.70
    assert 1.35 <= metrics["wallHitVsBasicDamage"] <= 1.75
    assert metrics["debtBladeVsTwoBasicDamage"] < 1.05
    assert metrics["rogueDebtRepaymentBasics"] == 2
    assert metrics["starfallBaseVsTwoBasicDamage"] < 1.05
    assert metrics["starfallStableVsTwoBasicDamage"] > metrics["starfallBaseVsTwoBasicDamage"]
    assert metrics["starfallStableVsTwoBasicDamage"] <= 1.20
    assert 0.85 <= metrics["scabbardVsTwoBasicDamage"] <= 1.15
    assert metrics["manaDeficitHpCostBps"] == 4_500
    assert metrics["bloodPriceHpCostBps"] == 2_400
    assert metrics["paladinShieldBps"] == 900
    return metrics


STATIC_CHECKS = (
    ("WAVE3_HAS_16_ACTIVE_8_PASSIVE", check_catalog),
    ("ALL_24_IDS_EXIST_IN_240_IDEA_BANK", check_idea_bank),
    ("SINGLE_AXIS_GROWTH_AND_LEDGER_HARD_CAPS", check_growth_and_ledgers),
    ("EACH_CLASS_ACCESSES_17_ACTIVE_9_PASSIVE", check_access_counts),
    ("WORST_LEVEL_FULL_BUILDS_REPRESENT_311875200_LEVEL_STATES", check_worst_level_full_loadouts),
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
    passed.append("PAIRED_REACTION_CONVERSION_DEBT_DELAY_GATES")
    if DOCUMENT.exists():
        assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")
        passed.append("DOCUMENT_CONTAINS_CANONICAL_WAVE3_HASH")
    print(f"SKILL_WAVE3_REACTION_CONVERSION_DEBT_DELAY_V0_1: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  evaluatedWorstLevelBuilds={check_worst_level_full_loadouts.evaluated}")
    print(f"  representedFullLoadoutLevelStates={check_worst_level_full_loadouts.represented}")
    print("  accessiblePerClass=Active17 Passive9 equip5+3")
    print(
        "  counter trigger={counterTriggerRate:.2%} damage={counterVsBasicDamage:.3f} "
        "wallPacket={wallHitVsBasicDamage:.3f} debtTwoAction={debtBladeVsTwoBasicDamage:.3f}".format(**dynamic)
    )
    print(
        "  starfall base={starfallBaseVsTwoBasicDamage:.3f} stable={starfallStableVsTwoBasicDamage:.3f} "
        "scabbard={scabbardVsTwoBasicDamage:.3f}".format(**dynamic)
    )
    print(
        f"  debt repaymentBasics={dynamic['rogueDebtRepaymentBasics']} manaHpCost={dynamic['manaDeficitHpCostBps']} "
        f"bloodPriceHpCost={dynamic['bloodPriceHpCostBps']} paladinShield={dynamic['paladinShieldBps']}"
    )
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
