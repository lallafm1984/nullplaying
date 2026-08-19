#!/usr/bin/env python3
"""Wave 5 audit for resource, protection, heal, and expedition sustain.

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
from pathlib import Path

import skill_wave1_active5_passive3_v0_1_review as wave1
import skill_wave2_combo_patterns_v0_1_review as wave2
import skill_wave3_reaction_conversion_debt_delay_v0_1_review as wave3
import skill_wave4_dot_cleanse_diversity_execution_v0_1_review as wave4


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_WAVE5_RESOURCE_PROTECTION_HEAL_EXPEDITION_v0.1.md"
IDEA_BANK = ROOT / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"
CLASSES = wave1.CLASSES
ANCHORS = wave1.ANCHOR_LEVELS

COMMON_SURVIVAL_ENCOUNTER_CAP = 1
COMMON_SURVIVAL_EXPEDITION_CAP = 2
COMMON_RECOVERY_ENCOUNTER_CAP = 1
FIELD_TREATMENT_EXPEDITION_CAP = 3
LAST_STAND_EXPEDITION_CAP = 1
AGGREGATE_HEAL_SHIELD_ENCOUNTER_CAP_BPS = 3_000
AGGREGATE_HEAL_SHIELD_EXPEDITION_CAP_BPS = 7_000
ORDINARY_ATTRITION_BASE_BPS = 5_000
ORDINARY_ATTRITION_FLOOR_BPS = 4_500
SURVIVAL_ATTRITION_BPS = 10_000
RESOURCE_RECOVERY_CEILING_BYPASS = 0
MAX_NON_DAMAGE_ROOTS_IN_TEN = 6
SHIELD_ALLOWED_CLASSES = frozenset({"WARRIOR", "RANGER", "MAGE", "CLERIC", "PALADIN"})


BASE_PROTECTION_INSTANCES = {
    "WARRIOR": {"E": (1_000,), "X": (1_000, 1_000, 1_000)},
    "ROGUE": {"E": (), "X": ()},
    "RANGER": {"E": (600,), "X": (600, 600, 600)},
    "MAGE": {"E": (600,), "X": (600, 600)},
    "CLERIC": {"E": (1_000, 1_000, 600), "X": (1_000, 1_000, 1_000, 1_000, 1_000, 600, 600, 600)},
    "PALADIN": {"E": (800, 900), "X": (800, 800, 900, 900, 900)},
}


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
    attack_width: int
    attack_equivalent_values: tuple[int, int, int, int, int]
    fixed_downside: str


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


def active(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, attack_width, attack_equivalent_values, fixed_downside):
    return ActivePlan(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, attack_width, attack_equivalent_values, fixed_downside)


def passive(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, condition_id, stack_group, fixed_tradeoff):
    return PassivePlan(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, condition_id, stack_group, fixed_tradeoff)


ACTIVES = (
    active("aq.skill.warrior.w5.earthsplitter", "W007", "WARRIOR", "대지 가르기", "CHARACTER_UNIQUE", "CONTROL_STAGGER_ARMORED", "stagger_apply_bps", (6_000, 6_750, 7_500, 8_250, 9_000), 1, (9_000,) * 5, "ARMORED 전용 STAGGER, SWIFT 상대 최종 Hit -1,200"),
    active("aq.skill.warrior.w5.tauntcry", "W012", "WARRIOR", "도발의 함성", "LEVEL", "RISK_SUPPORT", "target_incoming_damage_add_bps", (300, 400, 500, 600, 700), 3, (20_600, 20_800, 21_000, 21_200, 21_400), "현재 피해 0, 적이 받는 피해 증가와 주는 DIRECT 피해 +800이 2턴 동시 적용"),

    active("aq.skill.rogue.w5.illusionstab", "ROG013", "ROGUE", "허상 찌르기", "LEVEL", "TWO_ROOT_FEINT", "next_attack_coefficient_bps", (11_000, 11_750, 12_500, 13_250, 14_000), 2, (14_000, 14_750, 15_500, 16_250, 17_000), "첫 root 계수 3,000, 다음 root 강제 소비, 첫 공격만으로 끝나면 손해"),
    active("aq.skill.rogue.w5.moonlessnight", "ROG022", "ROGUE", "달 없는 밤", "CLASS_QUEST", "OPENING_FIXED_HIT", "opening_coefficient_bps", (8_000, 8_500, 9_000, 9_500, 10_000), 1, (8_000, 8_500, 9_000, 9_500, 10_000), "조우 첫 root 1회, Hit 판정 없음·치명 불가, 이후 조우 DIRECT 피해 -700"),

    active("aq.skill.ranger.w5.retreatshot", "HUN014", "RANGER", "후퇴 사격", "LEVEL", "ATTACK_THEN_EVA", "evasion_add_bps", (400, 550, 700, 850, 1_000), 1, (7_000,) * 5, "공격 계수 7,000, 다음 적 DIRECT 1회에만 EVA, 고정명중에는 무효"),
    active("aq.skill.ranger.w5.firstarrow", "HUN006", "RANGER", "황야의 첫 화살", "CHARACTER_UNIQUE", "OPENING_PRECISION", "opening_coefficient_bps", (8_500, 8_875, 9_250, 9_625, 10_000), 1, (8_500, 8_875, 9_250, 9_625, 10_000), "조우 첫 root만 Hit +1,000·Crit +500, 이후 효과 0"),

    active("aq.skill.mage.w5.spellreversal", "MAG014", "MAGE", "주문 반전", "LEVEL", "PREPAID_SPELL_REFLECT", "reflected_coefficient_cap_bps", (4_000, 5_000, 6_000, 7_000, 8_000), 1, (4_000, 5_000, 6_000, 7_000, 8_000), "자기 root 선지불, 다음 SPELL DIRECT -3,000·반사, 물리 공격이면 피해·보호 0"),
    active("aq.skill.mage.w5.voidgate", "MAG022", "MAGE", "공허문", "CLASS_QUEST", "SYMMETRIC_DISPEL", "max_effect_pairs", (1, 1, 2, 2, 3), 1, (0,) * 5, "적 beneficial과 자기 beneficial을 같은 수만큼 제거, 순이익이 양수일 때만 자동 후보"),

    active("aq.skill.cleric.w5.martyrlight", "CLE006", "CLERIC", "순교자의 빛", "CHARACTER_UNIQUE", "SELF_COST_HEAL_ATTACK", "heal_maxhp_bps", (600, 700, 800, 900, 1_000), 1, (6_000,) * 5, "현재 HP 800 BPS를 Shield 불가 COST로 지불, Heal token 1개·공격 계수 6,000"),
    active("aq.skill.cleric.w5.miraclecost", "CLE022", "CLERIC", "기적의 대가", "CLASS_QUEST", "DOUBLE_TOKEN_HEAL", "heal_maxhp_bps", (1_400, 1_500, 1_600, 1_700, 1_800), 1, (0,) * 5, "Heal token 2개 원자 소비·원정 1회, MaxResource 2,000 BPS를 숙박까지 봉인"),

    active("aq.skill.paladin.w5.purifyingstrike", "PAL012", "PALADIN", "정화의 일격", "LEVEL", "ENEMY_DISPEL_STRIKE", "buffed_target_coefficient_bps", (7_000, 7_500, 8_000, 8_500, 9_000), 1, (7_000, 7_500, 8_000, 8_500, 9_000), "적 beneficial 1개 제거, 제거 대상이 없으면 계수 5,000"),
    active("aq.skill.paladin.w5.coverally", "PAL013", "PALADIN", "동료 감싸기", "LEVEL", "SHIELD_INTERCEPT", "shield_maxhp_bps", (300, 400, 500, 600, 700), 1, (0,) * 5, "class Shield token 1개, 솔로에서는 자기 Shield 50%만 적용"),

    active("aq.skill.common.w5.defensestance", "COM003", "ALL", "방어 자세", "COMMON", "COMMON_SHIELD", "shield_maxhp_bps", (200, 250, 300, 350, 400), 1, (0,) * 5, "COMMON_SURVIVAL_SHARED 소비, 현재 피해 0, Shield 0 cap 직업은 후보 아님"),
    active("aq.skill.common.w5.firstaid", "COM004", "ALL", "응급 처치", "COMMON", "COMMON_HEAL", "heal_maxhp_bps", (150, 188, 225, 263, 300), 1, (0,) * 5, "COMMON_SURVIVAL_SHARED 소비, encounterStartHp까지만 Heal"),
    active("aq.skill.common.w5.focusedbreathing", "COM006", "ALL", "집중 호흡", "COMMON", "COMMON_RESOURCE_RECOVERY", "resource_recovery_bps", (800, 900, 1_000, 1_100, 1_200), 1, (0,) * 5, "현재 root 피해·보호 0, 조우 1회, 낮아진 recovery ceiling을 복구하지 않음"),

    active("aq.skill.external.w5.phoenixash", "EXT042", "ALL", "피닉스 재", "SECRET", "EXPEDITION_LAST_STAND", "post_trigger_hp_bps", (300, 400, 500, 600, 700), 1, (0,) * 5, "원정 1회, MaxHP 2,500 BPS 봉인·자신 BURN, 두 번째 치명 피해에는 무효"),
)


PASSIVES = (
    passive("aq.skill.warrior.w5.indomitable", "W016", "WARRIOR", "불굴", "LEVEL", "LETHAL_PACKET_MITIGATION", "lethal_packet_modifier_bps", (-1_500, -2_000, -2_500, -3_000, -3_500), "first_lethal_direct_packet", "aq.stack.wave5.lethal_packet", "조우 1회, HP 1 보장·부활 아님, 큰 packet에는 그대로 사망"),
    passive("aq.skill.rogue.w5.shallowwound", "ROG020", "ROGUE", "얕은 상처", "LEVEL", "SMALL_PACKET_MITIGATION", "incoming_damage_modifier_bps", (-200, -300, -400, -500, -600), "incoming_direct_lte_0800_maxhp_bps", "aq.stack.passive.incoming_damage", "큰 단일 피해에는 0, 다른 받는 피해 Passive와 highest-only"),
    passive("aq.skill.ranger.w5.fieldtreatment", "HUN017", "RANGER", "야전 치료", "LEVEL", "POST_ENCOUNTER_HEAL", "heal_maxhp_bps", (200, 300, 400, 500, 600), "victory_and_hp_below_5000", "aq.generation.wave5.field_treatment", "전투 중 급사 방지 0, encounterStartHp 상한·원정 3회"),
    passive("aq.skill.mage.w5.archmagedebt", "MAG024", "MAGE", "대마법사의 부채", "CLASS_QUEST", "EXPEDITION_RESOURCE_DEBT", "deficit_allowance_bps", (500, 750, 1_000, 1_250, 1_500), "first_insufficient_spell", "aq.generation.wave5.resource_debt", "원정 1회, ceiling에 기본 attrition+deficit 차감·회복 효율 -1,000"),
    passive("aq.skill.cleric.w5.abstinencevow", "CLE024", "CLERIC", "금욕의 서약", "CLASS_QUEST", "NO_ITEM_PROTECTION", "incoming_damage_modifier_bps", (-100, -200, -300, -400, -500), "no_consumable_used_this_expedition", "aq.stack.passive.incoming_damage", "물약 Heal -1,500, 다른 받는 피해 Passive와 highest-only"),
    passive("aq.skill.paladin.w5.mercylimit", "PAL019", "PALADIN", "자비의 제한", "LEVEL", "OVERHEAL_TO_RESOURCE", "resource_gain_cap_bps", (100, 200, 300, 400, 500), "actual_overheal_gt_zero", "aq.generation.wave5.overheal_resource", "root action당 1회·ceiling 증가 0·최대 Heal -500 고정"),
    passive("aq.skill.common.w5.supplysaving", "COM038", "ALL", "보급 절약", "COMMON", "ORDINARY_ATTRITION_REDUCTION", "attrition_reduction_bps", (100, 200, 300, 400, 500), "expedition_pool_ordinary_spend", "aq.stack.wave5.attrition", "일반 소비만 최소 4,500, Heal·Shield·Barrier attrition 10,000은 감소 불가"),
    passive("aq.skill.external.w5.guardletter", "EXT024", "ALL", "편지를 지킨 마음", "WORLD_QUEST", "LATE_EXPEDITION_PROTECTION", "incoming_damage_modifier_bps", (-200, -300, -400, -500, -600), "encounter_index_gte_5_and_resource_lte_2500", "aq.stack.passive.incoming_damage", "원정 초반·조우형 자원 직업에는 0, 다른 받는 피해 Passive와 highest-only"),
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


def accessible_actives(class_name: str):
    return tuple(plan for plan in ACTIVES if plan.owner_scope in {class_name, "ALL"})


def accessible_passives(class_name: str):
    return tuple(plan for plan in PASSIVES if plan.owner_scope in {class_name, "ALL"})


def has_suffix(ids, suffix):
    return any(value.endswith(suffix) for value in ids)


def canonical_hash() -> str:
    payload = {
        "wave4Hash": wave4.canonical_hash(),
        "anchors": ANCHORS,
        "actives": [plan.__dict__ for plan in sorted(ACTIVES, key=lambda item: item.definition_id)],
        "passives": [plan.__dict__ for plan in sorted(PASSIVES, key=lambda item: item.definition_id)],
        "commonSurvivalCaps": (COMMON_SURVIVAL_ENCOUNTER_CAP, COMMON_SURVIVAL_EXPEDITION_CAP),
        "commonRecoveryEncounterCap": COMMON_RECOVERY_ENCOUNTER_CAP,
        "fieldTreatmentExpeditionCap": FIELD_TREATMENT_EXPEDITION_CAP,
        "lastStandExpeditionCap": LAST_STAND_EXPEDITION_CAP,
        "aggregateHealShieldCaps": (AGGREGATE_HEAL_SHIELD_ENCOUNTER_CAP_BPS, AGGREGATE_HEAL_SHIELD_EXPEDITION_CAP_BPS),
        "ordinaryAttrition": (ORDINARY_ATTRITION_BASE_BPS, ORDINARY_ATTRITION_FLOOR_BPS),
        "survivalAttritionBps": SURVIVAL_ATTRITION_BPS,
        "resourceRecoveryCeilingBypass": RESOURCE_RECOVERY_CEILING_BYPASS,
        "maxNonDamageRootsInTen": MAX_NON_DAMAGE_ROOTS_IN_TEN,
        "shieldAllowedClasses": sorted(SHIELD_ALLOWED_CLASSES),
    }
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def cumulative_prior_idea_ids():
    wave1_active_ids = {f"{prefix}{index:03d}" for prefix in ("W", "ROG", "HUN", "MAG", "CLE", "PAL") for index in range(1, 6)}
    return (
        wave1_active_ids
        | {p.idea_id for p in wave1.PASSIVES}
        | {p.idea_id for p in wave2.ACTIVES + wave2.PASSIVES}
        | {p.idea_id for p in wave3.ACTIVES + wave3.PASSIVES}
        | {p.idea_id for p in wave4.ACTIVES + wave4.PASSIVES}
    )


def check_catalog():
    assert len(ACTIVES) == 16 and len(PASSIVES) == 8
    assert len({p.definition_id for p in ACTIVES + PASSIVES}) == 24
    assert len({p.idea_id for p in ACTIVES + PASSIVES}) == 24
    assert sum(p.owner_scope in CLASSES for p in ACTIVES) == 12
    assert sum(p.owner_scope in CLASSES for p in PASSIVES) == 6
    assert sum(p.owner_scope == "ALL" for p in ACTIVES) == 4
    assert sum(p.owner_scope == "ALL" for p in PASSIVES) == 2
    prior = cumulative_prior_idea_ids()
    current = {p.idea_id for p in ACTIVES + PASSIVES}
    assert len(prior) == 120 and not prior.intersection(current)
    assert len(prior | current) == 144
    patterns = {p.pattern for p in ACTIVES + PASSIVES}
    assert any("HEAL" in pattern for pattern in patterns)
    assert any("SHIELD" in pattern for pattern in patterns)
    assert any("RESOURCE" in pattern for pattern in patterns)
    assert any("EXPEDITION" in pattern for pattern in patterns)


def check_idea_bank():
    text = IDEA_BANK.read_text(encoding="utf-8")
    for plan in ACTIVES + PASSIVES:
        assert f"| {plan.idea_id} | {plan.name_ko} |" in text


def check_growth_and_attack_dominance():
    for plan in ACTIVES + PASSIVES:
        values = [value_at(plan.values, level) for level in range(1, 101)]
        increasing = plan.values[-1] >= plan.values[0]
        assert all((b >= a) if increasing else (b <= a) for a, b in zip(values, values[1:]))
        assert tuple(values[level - 1] for level in ANCHORS) == plan.values
    for plan in ACTIVES:
        for level in range(1, 101):
            equivalent = value_at(plan.attack_equivalent_values, level)
            assert equivalent <= plan.attack_width * 10_000, (plan.idea_id, level, equivalent)
    assert all(plan.growth_field not in {"action_coefficient_add_bps", "attack_modifier_bps"} for plan in PASSIVES)
    non_damage_roots = {
        "WARRIOR": 1 + 1 + 1,
        "ROGUE": 1 + 1 + 2,
        "RANGER": 1 + 1 + 2,
        "MAGE": 1 + 3 + 2,
        "CLERIC": 3 + 1 + 1,
        "PALADIN": 2 + 1 + 1,
    }
    assert max(non_damage_roots.values()) == MAX_NON_DAMAGE_ROOTS_IN_TEN
    represented = math.comb(29, 5) * math.comb(15, 3) * len(CLASSES) * 100
    assert represented == 32_420_115_000
    check_growth_and_attack_dominance.represented = represented


def check_access_counts():
    for class_name in CLASSES:
        assert len(wave4.accessible_actives(class_name, 100)) + len(accessible_actives(class_name)) == 29
        assert len(wave4.accessible_passives(class_name)) + len(accessible_passives(class_name)) == 15


def max_subset_sum_under_cap(amounts, cap):
    reachable = {0}
    for amount in amounts:
        reachable |= {value + amount for value in tuple(reachable) if value + amount <= cap}
    return max(reachable)


def utility_envelope(class_name, active_ids, passive_ids, level):
    def active_value(suffix):
        plan = next((plan for plan in ACTIVES if plan.definition_id.endswith(suffix)), None)
        return value_at(plan.values, level) if plan and plan.definition_id in active_ids else 0

    def passive_value(suffix):
        plan = next((plan for plan in PASSIVES if plan.definition_id.endswith(suffix)), None)
        return value_at(plan.values, level) if plan and plan.definition_id in passive_ids else 0

    common_shield = active_value("defensestance") if class_name in SHIELD_ALLOWED_CLASSES else 0
    common_amount = max(common_shield, active_value("firstaid"))
    encounter_instances = list(BASE_PROTECTION_INSTANCES[class_name]["E"])
    expedition_instances = list(BASE_PROTECTION_INSTANCES[class_name]["X"])
    if common_amount:
        encounter_instances += [common_amount] * COMMON_SURVIVAL_ENCOUNTER_CAP
        expedition_instances += [common_amount] * COMMON_SURVIVAL_EXPEDITION_CAP
    field_treatment = passive_value("fieldtreatment")
    if field_treatment:
        encounter_instances.append(field_treatment)
        expedition_instances += [field_treatment] * FIELD_TREATMENT_EXPEDITION_CAP
    encounter_generation = max_subset_sum_under_cap(encounter_instances, AGGREGATE_HEAL_SHIELD_ENCOUNTER_CAP_BPS)
    expedition_generation = max_subset_sum_under_cap(expedition_instances, AGGREGATE_HEAL_SHIELD_EXPEDITION_CAP_BPS)

    martyr = active_value("martyrlight")
    miracle = active_value("miraclecost")
    cover = active_value("coverally")
    assert martyr <= 1_000
    assert miracle <= 2_000
    assert math.ceil(cover * 0.5) <= 900

    incoming_modifiers = [
        passive_value("shallowwound"),
        passive_value("abstinencevow"),
        passive_value("guardletter"),
    ]
    incoming_modifier = min([value for value in incoming_modifiers if value < 0], default=0)
    attrition_reduction = passive_value("supplysaving")
    ordinary_attrition = max(ORDINARY_ATTRITION_FLOOR_BPS, ORDINARY_ATTRITION_BASE_BPS - attrition_reduction)
    return {
        "encounterGeneration": encounter_generation,
        "expeditionGeneration": expedition_generation,
        "incomingModifier": incoming_modifier,
        "ordinaryAttrition": ordinary_attrition,
        "commonRecovery": active_value("focusedbreathing"),
        "mageDebt": passive_value("archmagedebt"),
        "paladinOverheal": passive_value("mercylimit"),
        "phoenixHp": active_value("phoenixash"),
    }


def check_utility_subsets():
    checked = 0
    maxima = {"E": 0, "X": 0, "recovery": 0, "debt": 0, "overheal": 0}
    for level in ANCHORS:
        for class_name in CLASSES:
            actives = accessible_actives(class_name)
            passives = accessible_passives(class_name)
            assert len(actives) == 6 and len(passives) == 3
            for active_mask in range(1 << len(actives)):
                active_ids = frozenset(plan.definition_id for index, plan in enumerate(actives) if active_mask & (1 << index))
                for passive_mask in range(1 << len(passives)):
                    passive_ids = frozenset(plan.definition_id for index, plan in enumerate(passives) if passive_mask & (1 << index))
                    envelope = utility_envelope(class_name, active_ids, passive_ids, level)
                    assert envelope["encounterGeneration"] <= AGGREGATE_HEAL_SHIELD_ENCOUNTER_CAP_BPS
                    assert envelope["expeditionGeneration"] <= AGGREGATE_HEAL_SHIELD_EXPEDITION_CAP_BPS
                    assert envelope["incomingModifier"] >= -700
                    assert ORDINARY_ATTRITION_FLOOR_BPS <= envelope["ordinaryAttrition"] <= ORDINARY_ATTRITION_BASE_BPS
                    assert envelope["commonRecovery"] <= 1_200
                    assert envelope["mageDebt"] <= 1_500
                    assert envelope["paladinOverheal"] <= 500
                    assert envelope["phoenixHp"] <= 700
                    maxima["E"] = max(maxima["E"], envelope["encounterGeneration"])
                    maxima["X"] = max(maxima["X"], envelope["expeditionGeneration"])
                    maxima["recovery"] = max(maxima["recovery"], envelope["commonRecovery"])
                    maxima["debt"] = max(maxima["debt"], envelope["mageDebt"])
                    maxima["overheal"] = max(maxima["overheal"], envelope["paladinOverheal"])
                    checked += 1
    assert checked == 15_360
    assert maxima == {"E": 3_000, "X": 7_000, "recovery": 1_200, "debt": 1_500, "overheal": 500}
    check_utility_subsets.checked = checked
    check_utility_subsets.maxima = maxima


def spend_expedition(current, ceiling, cost, attrition_bps):
    if current < cost:
        return current, ceiling, False
    current -= cost
    ceiling = max(0, ceiling - math.ceil(cost * attrition_bps / 10_000))
    current = min(current, ceiling)
    return current, ceiling, True


def recover_to_ceiling(current, ceiling, amount):
    return min(ceiling, current + amount)


def simulate_pool_expedition(attrition_bps):
    current = ceiling = 10_000
    spends = 0
    for _ in range(12):
        current, ceiling, spent = spend_expedition(current, ceiling, 1_500, attrition_bps)
        spends += int(spent)
        current = recover_to_ceiling(current, ceiling, 1_200)
        assert 0 <= current <= ceiling <= 10_000
    return current, ceiling, spends


def simulate_ranger_sustain(seed):
    current_hp = 10_000
    field_uses = common_uses = recovered = 0
    for encounter in range(8):
        start_hp = current_hp
        damage = 600 + wave1.legacy.c1.event_roll_bps(seed, encounter, "W5_EXPEDITION_DAMAGE", "RANGER") % 1_201
        current_hp = max(1, current_hp - damage)
        if field_uses < FIELD_TREATMENT_EXPEDITION_CAP and current_hp < 5_000:
            gain = min(600, start_hp - current_hp)
            current_hp += gain
            recovered += gain
            field_uses += 1
        if common_uses < COMMON_SURVIVAL_EXPEDITION_CAP and current_hp < 4_000:
            gain = min(300, start_hp - current_hp)
            current_hp += gain
            recovered += gain
            common_uses += 1
        assert current_hp <= start_hp
    return current_hp, recovered, field_uses, common_uses


def dynamic_metrics(seeds):
    baseline = simulate_pool_expedition(ORDINARY_ATTRITION_BASE_BPS)
    supplied = simulate_pool_expedition(ORDINARY_ATTRITION_FLOOR_BPS)
    hp_total = recovered_total = field_total = common_total = 0
    for seed in range(seeds):
        hp, recovered, field_uses, common_uses = simulate_ranger_sustain(seed)
        hp_total += hp
        recovered_total += recovered
        field_total += field_uses
        common_total += common_uses

    mage_current, mage_ceiling = 500, 4_000
    spell_cost = 2_000
    deficit = spell_cost - mage_current
    assert deficit == 1_500
    mage_current = 0
    mage_ceiling -= math.ceil(spell_cost * ORDINARY_ATTRITION_BASE_BPS / 10_000) + deficit
    mage_current = recover_to_ceiling(mage_current, mage_ceiling, 1_500)

    paladin_current = 2_000
    paladin_ceiling = 10_000
    actual_overheal = 800
    paladin_current = min(paladin_ceiling, paladin_current + min(500, actual_overheal))

    warrior_hp = 1_000
    lethal_packet = 3_000
    reduced_packet = math.ceil(lethal_packet * 6_500 / 10_000)
    indomitable_survives = warrior_hp > reduced_packet

    phoenix_triggers = 0
    phoenix_hp = 1_000
    if phoenix_hp <= 1_500 and phoenix_triggers < LAST_STAND_EXPEDITION_CAP:
        phoenix_triggers += 1
        phoenix_hp = 700
    phoenix_hp = max(0, phoenix_hp - 1_500)

    metrics = {
        "baselineFinalCeiling": baseline[1],
        "supplyFinalCeiling": supplied[1],
        "baselineSpends": baseline[2],
        "supplySpends": supplied[2],
        "rangerAverageFinalHpBps": hp_total / seeds,
        "rangerAverageRecoveredBps": recovered_total / seeds,
        "rangerAverageFieldUses": field_total / seeds,
        "rangerAverageCommonUses": common_total / seeds,
        "mageDebtFinalCurrent": mage_current,
        "mageDebtFinalCeiling": mage_ceiling,
        "paladinOverhealResource": paladin_current - 2_000,
        "indomitableGuaranteedSurvival": indomitable_survives,
        "phoenixTriggers": phoenix_triggers,
        "phoenixSurvivesSecondLethal": phoenix_hp > 0,
        "commonSurvivalEncounterCap": COMMON_SURVIVAL_ENCOUNTER_CAP,
        "commonSurvivalExpeditionCap": COMMON_SURVIVAL_EXPEDITION_CAP,
        "maxNonDamageRootsInTen": MAX_NON_DAMAGE_ROOTS_IN_TEN,
    }
    assert 0 <= metrics["baselineFinalCeiling"] < metrics["supplyFinalCeiling"] <= 10_000
    assert metrics["supplyFinalCeiling"] == 1_900 and metrics["baselineFinalCeiling"] == 1_000
    assert metrics["baselineSpends"] == metrics["supplySpends"] == 12
    assert 0 <= metrics["rangerAverageFinalHpBps"] <= 10_000
    assert metrics["rangerAverageRecoveredBps"] <= 2_400
    assert metrics["rangerAverageFieldUses"] <= 3 and metrics["rangerAverageCommonUses"] <= 2
    assert 0 <= metrics["mageDebtFinalCurrent"] <= metrics["mageDebtFinalCeiling"] == 1_500
    assert metrics["paladinOverhealResource"] == 500
    assert not metrics["indomitableGuaranteedSurvival"]
    assert metrics["phoenixTriggers"] == 1 and not metrics["phoenixSurvivesSecondLethal"]
    assert metrics["commonSurvivalEncounterCap"] == 1 and metrics["commonSurvivalExpeditionCap"] == 2
    assert metrics["maxNonDamageRootsInTen"] == 6
    return metrics


STATIC_CHECKS = (
    ("WAVE5_HAS_16_ACTIVE_8_PASSIVE_AND_CUMULATIVE144_UNIQUE_IDS", check_catalog),
    ("ALL_24_IDS_EXIST_IN_240_IDEA_BANK", check_idea_bank),
    ("SINGLE_AXIS_GROWTH_AND_NEW_ATTACK_PACKAGES_DOMINATED_BY_BASIC", check_growth_and_attack_dominance),
    ("EACH_CLASS_ACCESSES_29_ACTIVE_15_PASSIVE", check_access_counts),
    ("ALL_15360_WAVE5_UTILITY_SUBSETS_PRESERVE_HARD_CAPS", check_utility_subsets),
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
    metrics = dynamic_metrics(seeds)
    passed.append("RESOURCE_PROTECTION_HEAL_EXPEDITION_GATES")
    if DOCUMENT.exists():
        assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")
        passed.append("DOCUMENT_CONTAINS_CANONICAL_WAVE5_HASH")
    print(f"SKILL_WAVE5_RESOURCE_PROTECTION_HEAL_EXPEDITION_V0_1: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  checkedUtilitySubsets={check_utility_subsets.checked}")
    print(f"  representedFullLoadoutLevelStates={check_growth_and_attack_dominance.represented}")
    print("  accessiblePerClass=Active29 Passive15 equip5+3 cumulativeUnique=144")
    print(
        f"  expedition ceiling baseline={metrics['baselineFinalCeiling']} supply={metrics['supplyFinalCeiling']} "
        f"spends={metrics['baselineSpends']}/{metrics['supplySpends']}"
    )
    print(
        f"  ranger finalHp={metrics['rangerAverageFinalHpBps']:.1f} recovered={metrics['rangerAverageRecoveredBps']:.1f} "
        f"fieldUses={metrics['rangerAverageFieldUses']:.2f} commonUses={metrics['rangerAverageCommonUses']:.2f}"
    )
    print(
        f"  mage debt current/ceiling={metrics['mageDebtFinalCurrent']}/{metrics['mageDebtFinalCeiling']} "
        f"paladinOverheal={metrics['paladinOverhealResource']} indomitableGuaranteed={metrics['indomitableGuaranteedSurvival']}"
    )
    print(
        f"  phoenixTriggers={metrics['phoenixTriggers']} survivesSecond={metrics['phoenixSurvivesSecondLethal']} "
        f"supportCaps=E{metrics['commonSurvivalEncounterCap']}/X{metrics['commonSurvivalExpeditionCap']} nonDamageRoots={metrics['maxNonDamageRootsInTen']}"
    )
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
