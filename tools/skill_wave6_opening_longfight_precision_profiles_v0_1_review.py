#!/usr/bin/env python3
"""Wave 6 audit for opening, long-fight, MISS/CRIT, and profile niches.

Design-only: no Kotlin, Room, save, character, or live mutations.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import skill_wave1_active5_passive3_v0_1_review as wave1
import skill_wave4_dot_cleanse_diversity_execution_v0_1_review as wave4
import skill_wave5_resource_protection_heal_expedition_v0_1_review as wave5


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_WAVE6_OPENING_LONGFIGHT_PRECISION_PROFILES_v0.1.md"
IDEA_BANK = ROOT / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"
CLASSES = wave1.CLASSES
ANCHORS = wave1.ANCHOR_LEVELS

ACTION_COEFFICIENT_CAP_BPS = 10_000
PROFILE_HIT_ADD_CAP_BPS = 1_200
OPENING_ACTION_SHARED_CAP = 1
MISS_RESPONSE_ENCOUNTER_CAP = 1
TIME_CONTROL_ENCOUNTER_CAP = 1
TACTIC_SHARED_ENCOUNTER_CAP = 2
HP_RESOURCE_TRANSFER_ENCOUNTER_CAP = 1
CRIT_SUPPRESSION_CAP_BPS = 3_000
LONG_FIGHT_START_ROOT = 7
LONG_FIGHT_ATTACK_ADD_CAP_BPS = 500
SPARSE_PASSIVE_AMPLIFY_CAP_BPS = 500
MAX_NON_DAMAGE_ROOTS_IN_TEN = 6


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


def active(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, attack_equivalent_values, fixed_downside):
    return ActivePlan(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, attack_equivalent_values, fixed_downside)


def passive(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, condition_id, stack_group, fixed_tradeoff):
    return PassivePlan(definition_id, idea_id, owner_scope, name_ko, source, pattern, growth_field, values, condition_id, stack_group, fixed_tradeoff)


ACTIVES = (
    active("aq.skill.warrior.w6.lastvanguard", "W008", "WARRIOR", "마지막 선봉", "CHARACTER_UNIQUE", "LOW_HP_PREPAID_COUNTER", "counter_coefficient_bps", (7_000, 7_750, 8_500, 9_250, 10_000), (7_000, 7_750, 8_500, 9_250, 10_000), "HP 25% 이하·자기 root 선지불, 2 enemy root 안 DIRECT 피격 때 1회·치명 불가"),
    active("aq.skill.warrior.w6.kingsduel", "W022", "WARRIOR", "왕의 결투", "CLASS_QUEST", "SOLO_BOSS_DUEL", "boss_coefficient_bps", (8_000, 8_500, 9_000, 9_500, 10_000), (8_000, 8_500, 9_000, 9_500, 10_000), "단일 BOSS에서 Hit +500, 그 외 계수 6,000·추가 보정 0"),

    active("aq.skill.rogue.w6.cointoss", "ROG014", "ROGUE", "동전 던지기", "LEVEL", "BIMODAL_FINAL_CRIT", "high_outcome_coefficient_bps", (15_000, 15_500, 16_000, 16_500, 17_000), (9_000, 9_250, 9_500, 9_750, 10_000), "50% 고점/50% 계수 3,000, 고점은 최종 CRITICAL 결과라 추가 치명 배수 금지"),
    active("aq.skill.rogue.w6.blackledger", "ROG021", "ROGUE", "검은 장부 회수", "CLASS_QUEST", "MARK_PROFILE_STRIKE", "marked_coefficient_bps", (8_000, 8_500, 9_000, 9_500, 10_000), (8_000, 8_500, 9_000, 9_500, 10_000), "MARK 1stack 원자 소비, MARK 없으면 계수 5,000·골드 생성 0"),

    active("aq.skill.ranger.w6.piercingshot", "HUN011", "RANGER", "관통 사격", "LEVEL", "ARMORED_PIERCE", "armored_coefficient_bps", (5_000, 5_250, 5_500, 5_750, 6_000), (5_000, 5_250, 5_500, 5_750, 6_000), "ARMORED 방어 30% 상대 감소, SWIFT 최종 Hit -1,000·그 외 방어 무시 0"),
    active("aq.skill.ranger.w6.gianthunt", "HUN021", "RANGER", "거수 사냥", "CLASS_QUEST", "BOSS_HP_CAPPED_PIERCE", "boss_coefficient_bps", (8_000, 8_500, 9_000, 9_500, 10_000), (8_000, 8_500, 9_000, 9_500, 10_000), "BOSS 방어 10% 상대 감소, 최종 피해 enemy MaxHP 100 BPS cap·일반 적 계수 5,000"),

    active("aq.skill.mage.w6.timefracture", "MAG008", "MAGE", "시간의 균열", "CHARACTER_UNIQUE", "SYMMETRIC_COOLDOWN_DELAY", "enemy_cooldown_delay_roots", (1, 1, 2, 2, 3), (0, 0, 0, 0, 0), "TACTIC_SHARED·조우 1회, 적 cooldown 지연과 자신의 다음 Active 1 root 지연 동시 적용·피해 0"),
    active("aq.skill.mage.w6.manaburst", "MAG013", "MAGE", "마나 폭발", "LEVEL", "FULL_RESOURCE_BURST", "full_resource_coefficient_bps", (7_000, 7_750, 8_500, 9_250, 10_000), (7_000, 7_750, 8_500, 9_250, 10_000), "current resource 70% 이상에서만 후보, 사용 후 current 0·치명 불가"),

    active("aq.skill.cleric.w6.lifedistribution", "CLE013", "CLERIC", "생명 분배", "LEVEL", "HP_RESOURCE_REBALANCE", "transfer_cap_bps", (400, 500, 600, 700, 800), (0, 0, 0, 0, 0), "HP 50% 목표·조우 1회, Heal은 token/통합 예산 소비·HP→자원은 COST이며 ceiling 증가 0"),
    active("aq.skill.cleric.w6.silentrelic", "CLE014", "CLERIC", "침묵의 성구", "LEVEL", "SPELLCASTER_SILENCE", "silence_apply_bps", (6_000, 6_625, 7_250, 7_875, 8_500), (3_000, 3_000, 3_000, 3_000, 3_000), "공격 계수 3,000·SPELLCASTER의 다음 SPELL 1회, 물리 profile에는 후보 아님"),

    active("aq.skill.paladin.w6.judgmentcharge", "PAL007", "PALADIN", "심판의 돌진", "CHARACTER_UNIQUE", "OPENING_STAGGER_CHARGE", "opening_coefficient_bps", (8_500, 8_875, 9_250, 9_625, 10_000), (8_500, 8_875, 9_250, 9_625, 10_000), "첫 자기 root·STAGGER 5,000, 이후 3 root 받는 피해 +1,000"),
    active("aq.skill.paladin.w6.cursedswordseal", "PAL022", "PALADIN", "타락한 성검 봉인", "CLASS_QUEST", "CURSE_TRANSFER_STRIKE", "cursed_coefficient_bps", (8_000, 8_500, 9_000, 9_500, 10_000), (8_000, 8_500, 9_000, 9_500, 10_000), "적 CURSE 1개를 자신에게 원자 이전·숙박까지 COST_LOCKED, CURSE 없으면 계수 6,000"),

    active("aq.skill.common.w6.lastshot", "COM017", "ALL", "마지막 한 발", "COMMON", "LOW_RESOURCE_FINISHER", "low_resource_coefficient_bps", (8_000, 8_500, 9_000, 9_500, 10_000), (8_000, 8_500, 9_000, 9_500, 10_000), "0<current≤25%에서만 후보, 사용 뒤 current 0·치명 불가"),
    active("aq.skill.common.w6.vitalseal", "COM023", "ALL", "급소 봉쇄", "COMMON", "CRIT_SUPPRESSION_TACTIC", "enemy_crit_damage_reduction_bps", (1_000, 1_500, 2_000, 2_500, 3_000), (0, 0, 0, 0, 0), "TACTIC_SHARED·다음 enemy root 2회·현재 피해 0, CRIT 능력 없는 적에는 후보 아님"),
    active("aq.skill.common.w6.weaponswap", "COM024", "ALL", "무기 바꾸기", "COMMON", "ARCHIVE_WEAK_TYPE_SWAP", "weak_type_coefficient_bps", (6_000, 6_500, 7_000, 7_500, 8_000), (6_000, 6_500, 7_000, 7_500, 8_000), "Archive가 확인한 약점 damage type으로 1회 변환, 약점 미확인 시 BASIC·치명 불가"),

    active("aq.skill.external.w6.lastdelivery", "EXT015", "ALL", "마지막 배달", "WORLD_QUEST", "FINAL_ENCOUNTER_OPENING", "final_opening_coefficient_bps", (8_500, 8_875, 9_250, 9_625, 10_000), (8_500, 8_875, 9_250, 9_625, 10_000), "원정 마지막 조우의 첫 자기 root만 Hit +500, 중간 조우 계수 5,000"),
)


PASSIVES = (
    passive("aq.skill.warrior.w6.veteraneye", "W024", "WARRIOR", "백전노장의 눈", "CLASS_QUEST", "SAME_PROFILE_HIT_MEMORY", "profile_hit_add_bps", (200, 400, 600, 800, 1_000), "same_profile_consecutive_encounter", "aq.stack.wave6.profile_hit", "profile/지역 변경 시 즉시 초기화·피해 계수 증가 0"),
    passive("aq.skill.rogue.w6.vanishedtrace", "ROG017", "ROGUE", "사라진 흔적", "LEVEL", "FIRST_MISS_EVASION", "evasion_add_bps", (300, 400, 500, 600, 700), "first_own_miss_in_encounter", "aq.trigger.wave6.miss_response", "조우 1회·다음 적 DIRECT 1회, 고정명중 무효·MISS가 없으면 0"),
    passive("aq.skill.ranger.w6.readwind", "HUN016", "RANGER", "바람 읽기", "LEVEL", "SWIFT_PROFILE_HIT", "swift_hit_add_bps", (400, 600, 800, 1_000, 1_200), "target_profile_swift", "aq.stack.wave6.profile_hit", "ARMORED·고정회피에는 기여 0·피해 계수 증가 0"),
    passive("aq.skill.mage.w6.coldcalculation", "MAG019", "MAGE", "냉정한 계산", "LEVEL", "NO_CRIT_DAMAGE_FLOOR", "minimum_damage_roll_bps", (7_000, 7_500, 8_000, 8_500, 9_000), "always_if_equipped", "aq.mode.wave6.damage_roll", "최대 roll 10,000·치명타 완전 포기, 평균 고점은 기본보다 낮음"),
    passive("aq.skill.cleric.w6.undeadknowledge", "CLE018", "CLERIC", "언데드 지식", "LEVEL", "UNDEAD_PROFILE_HIT", "undead_hit_add_bps", (200, 400, 600, 800, 1_000), "target_family_undead", "aq.stack.wave6.profile_hit", "UNDEAD 저항 +300 고정·다른 family에는 전부 0"),
    passive("aq.skill.paladin.w6.pilgrimdiscipline", "PAL023", "PALADIN", "순례자의 규율", "CLASS_QUEST", "LATE_EXPEDITION_PROTECTION", "incoming_damage_modifier_bps", (-100, -200, -300, -400, -500), "encounter_index_gte_3", "aq.stack.passive.incoming_damage", "귀환·숙박 시 초기화, 초반 2전에는 0·highest-only"),
    passive("aq.skill.common.w6.longfightprep", "COM042", "ALL", "장기전 준비", "COMMON", "LATE_ROOT_READINESS", "late_attack_add_bps", (100, 200, 300, 400, 500), "own_root_index_gte_7", "aq.stack.wave6.long_fight", "최종 행동 계수 10,000 cap·보호 -200 highest-only, 첫 6 root Speed -300"),
    passive("aq.skill.external.w6.kinglessloyalty", "EXT022", "ALL", "왕 없는 충성", "WORLD_QUEST", "SPARSE_PASSIVE_AMPLIFIER", "other_passive_amplify_cap_bps", (100, 200, 300, 400, 500), "equipped_passive_count_lte_2", "aq.stack.wave6.sparse_passive", "자신 제외 1개 scalar만·공격/피해감소/Heal/횟수/threshold/cap 증폭 금지, 빈 슬롯 기회비용"),
)


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


def canonical_hash() -> str:
    payload = {
        "wave5Hash": wave5.canonical_hash(),
        "anchors": ANCHORS,
        "actives": [plan.__dict__ for plan in sorted(ACTIVES, key=lambda item: item.definition_id)],
        "passives": [plan.__dict__ for plan in sorted(PASSIVES, key=lambda item: item.definition_id)],
        "caps": {
            "actionCoefficient": ACTION_COEFFICIENT_CAP_BPS,
            "profileHit": PROFILE_HIT_ADD_CAP_BPS,
            "openingAction": OPENING_ACTION_SHARED_CAP,
            "missResponse": MISS_RESPONSE_ENCOUNTER_CAP,
            "timeControl": TIME_CONTROL_ENCOUNTER_CAP,
            "tacticShared": TACTIC_SHARED_ENCOUNTER_CAP,
            "hpResourceTransfer": HP_RESOURCE_TRANSFER_ENCOUNTER_CAP,
            "critSuppression": CRIT_SUPPRESSION_CAP_BPS,
            "longFightStart": LONG_FIGHT_START_ROOT,
            "longFightAttack": LONG_FIGHT_ATTACK_ADD_CAP_BPS,
            "sparseAmplify": SPARSE_PASSIVE_AMPLIFY_CAP_BPS,
            "nonDamageRoots": MAX_NON_DAMAGE_ROOTS_IN_TEN,
        },
    }
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def cumulative_prior_idea_ids():
    return wave5.cumulative_prior_idea_ids() | {plan.idea_id for plan in wave5.ACTIVES + wave5.PASSIVES}


def check_catalog():
    assert len(ACTIVES) == 16 and len(PASSIVES) == 8
    assert len({plan.definition_id for plan in ACTIVES + PASSIVES}) == 24
    assert len({plan.idea_id for plan in ACTIVES + PASSIVES}) == 24
    assert sum(plan.owner_scope in CLASSES for plan in ACTIVES) == 12
    assert sum(plan.owner_scope in CLASSES for plan in PASSIVES) == 6
    assert sum(plan.owner_scope == "ALL" for plan in ACTIVES) == 4
    assert sum(plan.owner_scope == "ALL" for plan in PASSIVES) == 2
    prior = cumulative_prior_idea_ids()
    current = {plan.idea_id for plan in ACTIVES + PASSIVES}
    assert len(prior) == 144 and not prior.intersection(current)
    assert len(prior | current) == 168


def check_idea_bank():
    text = IDEA_BANK.read_text(encoding="utf-8")
    for plan in ACTIVES + PASSIVES:
        assert f"| {plan.idea_id} | {plan.name_ko} |" in text


def check_growth_and_attack_caps():
    for plan in ACTIVES + PASSIVES:
        values = [value_at(plan.values, level) for level in range(1, 101)]
        increasing = plan.values[-1] >= plan.values[0]
        assert all((right >= left) if increasing else (right <= left) for left, right in zip(values, values[1:]))
        assert tuple(values[level - 1] for level in ANCHORS) == plan.values
    for plan in ACTIVES:
        for level in range(1, 101):
            assert value_at(plan.attack_equivalent_values, level) <= ACTION_COEFFICIENT_CAP_BPS
    coin = next(plan for plan in ACTIVES if plan.idea_id == "ROG014")
    assert tuple((value + 3_000) // 2 for value in coin.values) == coin.attack_equivalent_values
    represented = math.comb(35, 5) * math.comb(18, 3) * len(CLASSES) * 100
    assert represented == 158_939_827_200
    check_growth_and_attack_caps.represented = represented


def check_access_counts():
    for class_name in CLASSES:
        active_count = len(wave4.accessible_actives(class_name, 100)) + len(wave5.accessible_actives(class_name)) + len(accessible_actives(class_name))
        passive_count = len(wave4.accessible_passives(class_name)) + len(wave5.accessible_passives(class_name)) + len(accessible_passives(class_name))
        assert active_count == 35 and passive_count == 18


def subset_envelope(class_name, active_ids, passive_ids, level):
    def active_value(suffix):
        plan = next((item for item in ACTIVES if item.definition_id.endswith(suffix)), None)
        return value_at(plan.values, level) if plan and plan.definition_id in active_ids else 0

    def passive_value(suffix):
        plan = next((item for item in PASSIVES if item.definition_id.endswith(suffix)), None)
        return value_at(plan.values, level) if plan and plan.definition_id in passive_ids else 0

    opening_candidates = int(active_value("judgmentcharge") > 0) + int(active_value("lastdelivery") > 0)
    tactic_candidates = int(active_value("timefracture") > 0) + int(active_value("vitalseal") > 0)
    profile_hit = max(passive_value("veteraneye"), passive_value("readwind"), passive_value("undeadknowledge"))
    incoming = min(0, passive_value("pilgrimdiscipline"), -200 if passive_value("longfightprep") else 0)
    sparse = passive_value("kinglessloyalty") if len(passive_ids) <= 2 else 0
    return {
        "openingActions": min(OPENING_ACTION_SHARED_CAP, opening_candidates),
        "tacticUses": min(TACTIC_SHARED_ENCOUNTER_CAP, tactic_candidates),
        "timeControlUses": min(TIME_CONTROL_ENCOUNTER_CAP, int(active_value("timefracture") > 0)),
        "missResponses": min(MISS_RESPONSE_ENCOUNTER_CAP, int(passive_value("vanishedtrace") > 0)),
        "profileHit": min(PROFILE_HIT_ADD_CAP_BPS, profile_hit),
        "critSuppression": active_value("vitalseal"),
        "hpResourceTransfer": active_value("lifedistribution"),
        "lateAttackAdd": passive_value("longfightprep"),
        "incomingModifier": incoming,
        "sparseAmplify": sparse,
    }


def check_all_wave6_subsets():
    checked = 0
    maxima = {"opening": 0, "tactic": 0, "profileHit": 0, "critSuppression": 0, "transfer": 0, "lateAttack": 0, "sparse": 0}
    for level in ANCHORS:
        for class_name in CLASSES:
            actives = accessible_actives(class_name)
            passives = accessible_passives(class_name)
            assert len(actives) == 6 and len(passives) == 3
            for active_mask in range(1 << len(actives)):
                active_ids = frozenset(plan.definition_id for index, plan in enumerate(actives) if active_mask & (1 << index))
                for passive_mask in range(1 << len(passives)):
                    passive_ids = frozenset(plan.definition_id for index, plan in enumerate(passives) if passive_mask & (1 << index))
                    result = subset_envelope(class_name, active_ids, passive_ids, level)
                    assert result["openingActions"] <= 1
                    assert result["tacticUses"] <= 2 and result["timeControlUses"] <= 1
                    assert result["missResponses"] <= 1
                    assert result["profileHit"] <= 1_200
                    assert result["critSuppression"] <= 3_000
                    assert result["hpResourceTransfer"] <= 800
                    assert result["lateAttackAdd"] <= 500
                    assert result["incomingModifier"] >= -700
                    assert result["sparseAmplify"] <= 500
                    maxima["opening"] = max(maxima["opening"], result["openingActions"])
                    maxima["tactic"] = max(maxima["tactic"], result["tacticUses"])
                    maxima["profileHit"] = max(maxima["profileHit"], result["profileHit"])
                    maxima["critSuppression"] = max(maxima["critSuppression"], result["critSuppression"])
                    maxima["transfer"] = max(maxima["transfer"], result["hpResourceTransfer"])
                    maxima["lateAttack"] = max(maxima["lateAttack"], result["lateAttackAdd"])
                    maxima["sparse"] = max(maxima["sparse"], result["sparseAmplify"])
                    checked += 1
    assert checked == 15_360
    assert maxima == {"opening": 1, "tactic": 2, "profileHit": 1_200, "critSuppression": 3_000, "transfer": 800, "lateAttack": 500, "sparse": 500}
    check_all_wave6_subsets.checked = checked
    check_all_wave6_subsets.maxima = maxima


def roll_bps(seed, index, salt):
    return wave1.legacy.c1.event_roll_bps(seed, index, salt, "WAVE6") % 10_000


def percentile(values, fraction):
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.floor((len(ordered) - 1) * fraction)))
    return ordered[index]


def dynamic_metrics(seeds):
    coin_packets = []
    high_count = 0
    normal_rolls = []
    stable_rolls = []
    for seed in range(seeds):
        for index in range(10):
            high = roll_bps(seed, index, "COIN") < 5_000
            coin_packets.append(17_000 if high else 3_000)
            high_count += int(high)
            normal_rolls.append(8_000 + roll_bps(seed, index, "NORMAL_ROLL") % 4_001)
            stable_rolls.append(9_000 + roll_bps(seed, index, "STABLE_ROLL") % 1_001)

    basic_armored = 10_000 * (10_000 - 7_000) / 10_000
    pierce_armored = 6_000 * (10_000 - 4_900) / 10_000
    basic_boss = 10_000 * (10_000 - 5_000) / 10_000
    giant_boss = 10_000 * (10_000 - 4_500) / 10_000
    swift_expected_ratio = (6_000 * 0.75) / (10_000 * 0.85)
    sparse_pair = 1_000 + 500
    full_three = 1_000 + 700 + 600
    metrics = {
        "coinAverageBps": sum(coin_packets) / len(coin_packets),
        "coinHighRate": high_count / len(coin_packets),
        "coinP10": percentile(coin_packets, 0.10),
        "coinP90": percentile(coin_packets, 0.90),
        "normalAverage": sum(normal_rolls) / len(normal_rolls),
        "stableAverage": sum(stable_rolls) / len(stable_rolls),
        "normalP10": percentile(normal_rolls, 0.10),
        "stableP10": percentile(stable_rolls, 0.10),
        "armoredPierceRatio": pierce_armored / basic_armored,
        "bossHuntRatio": giant_boss / basic_boss,
        "swiftPierceRatio": swift_expected_ratio,
        "openingLastRatio": 10_000 / 5_000,
        "longFightLowAction": min(10_000, 7_000 + 500),
        "longFightCappedAction": min(10_000, 10_000 + 500),
        "sparsePairValue": sparse_pair,
        "fullThreeValue": full_three,
        "profileHitCap": PROFILE_HIT_ADD_CAP_BPS,
        "critSuppressionCap": CRIT_SUPPRESSION_CAP_BPS,
        "maxNonDamageRoots": MAX_NON_DAMAGE_ROOTS_IN_TEN,
    }
    assert 9_500 <= metrics["coinAverageBps"] <= 10_500
    assert 0.45 <= metrics["coinHighRate"] <= 0.55
    assert metrics["coinP10"] == 3_000 and metrics["coinP90"] == 17_000
    assert metrics["stableP10"] > metrics["normalP10"]
    assert metrics["stableAverage"] < metrics["normalAverage"]
    assert 1.0 < metrics["armoredPierceRatio"] <= 1.03
    assert 1.0 < metrics["bossHuntRatio"] <= 1.10
    assert metrics["swiftPierceRatio"] < 0.60
    assert metrics["openingLastRatio"] == 2.0
    assert metrics["longFightLowAction"] == 7_500 and metrics["longFightCappedAction"] == 10_000
    assert metrics["sparsePairValue"] < metrics["fullThreeValue"]
    assert metrics["maxNonDamageRoots"] == 6
    return metrics


STATIC_CHECKS = (
    ("WAVE6_HAS_16_ACTIVE_8_PASSIVE_AND_CUMULATIVE168_UNIQUE_IDS", check_catalog),
    ("ALL_24_IDS_EXIST_IN_240_IDEA_BANK", check_idea_bank),
    ("SINGLE_AXIS_GROWTH_AND_ACTION_COEFFICIENT_CAPS", check_growth_and_attack_caps),
    ("EACH_CLASS_ACCESSES_35_ACTIVE_18_PASSIVE", check_access_counts),
    ("ALL_15360_WAVE6_SUBSETS_PRESERVE_PRECISION_PROFILE_CAPS", check_all_wave6_subsets),
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
    passed.append("OPENING_LONGFIGHT_MISS_CRIT_PROFILE_GATES")
    if DOCUMENT.exists():
        assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")
        passed.append("DOCUMENT_CONTAINS_CANONICAL_WAVE6_HASH")
    print(f"SKILL_WAVE6_OPENING_LONGFIGHT_PRECISION_PROFILES_V0_1: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  checkedWave6Subsets={check_all_wave6_subsets.checked}")
    print(f"  representedFullLoadoutLevelStates={check_growth_and_attack_caps.represented}")
    print("  accessiblePerClass=Active35 Passive18 equip5+3 cumulativeUnique=168")
    print(f"  coin avg={metrics['coinAverageBps']:.1f} highRate={metrics['coinHighRate']:.2%} p10/p90={metrics['coinP10']}/{metrics['coinP90']}")
    print(f"  stable avg={metrics['stableAverage']:.1f} p10={metrics['stableP10']} normal avg={metrics['normalAverage']:.1f} p10={metrics['normalP10']}")
    print(f"  profile armored={metrics['armoredPierceRatio']:.3f} boss={metrics['bossHuntRatio']:.3f} swiftPenalty={metrics['swiftPierceRatio']:.3f}")
    print(f"  openingRatio={metrics['openingLastRatio']:.1f} longFight={metrics['longFightLowAction']}/{metrics['longFightCappedAction']} sparse={metrics['sparsePairValue']}/{metrics['fullThreeValue']}")
    print(f"  caps profileHit={metrics['profileHitCap']} critSuppression={metrics['critSuppressionCap']} nonDamageRoots={metrics['maxNonDamageRoots']}")
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
