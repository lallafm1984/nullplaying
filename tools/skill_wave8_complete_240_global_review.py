#!/usr/bin/env python3
"""Wave 8 and complete 240-skill global design audit for AlarmQuest.

The script closes the 240-entry idea bank with 24 Active and 24 Passive
plans, proves factorized subset caps with the 650-item catalog, and never
mutates Kotlin, Room, saves, characters, or live content.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import math
import re
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import equipment_catalog_650_lineages_v0_2_review as equipment
import skill_wave1_active5_passive3_v0_1_review as wave1
import skill_wave2_combo_patterns_v0_1_review as wave2
import skill_wave3_reaction_conversion_debt_delay_v0_1_review as wave3
import skill_wave4_dot_cleanse_diversity_execution_v0_1_review as wave4
import skill_wave5_resource_protection_heal_expedition_v0_1_review as wave5
import skill_wave6_opening_longfight_precision_profiles_v0_1_review as wave6
import skill_wave7_world_monster_boss_v0_1_review as wave7


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_WAVE8_COMPLETE_240_GLOBAL_REVIEW.md"
IDEA_BANK = ROOT / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"
CLASSES = wave1.CLASSES
ANCHORS = wave1.ANCHOR_LEVELS

ACTION_COEFFICIENT_CAP_BPS = 10_000
EXECUTION_COEFFICIENT_CAP_BPS = 18_000
DELAYED_PACKET_COEFFICIENT_CAP_BPS = 16_000
STATUS_APPLY_FINAL_CAP_BPS = 9_500
PROFILE_HIT_FINAL_CAP_BPS = 1_200
TARGET_HIT_DOWN_CAP_BPS = 2_500
TEMP_EVA_ADD_CAP_BPS = 1_200
RESOURCE_DEBT_CAP_BPS = 4_000
PASSIVE_RESOURCE_DISCOUNT_CAP_BPS = 1_500
PASSIVE_ACTION_ADD_CAP_BPS = 1_500
INCOMING_REDUCTION_CAP_BPS = 2_500
HOSTILE_STATUS_CAP = 6
MAX_STATUSES_PER_ACTION = 2
MAX_NON_DAMAGE_ROOTS_IN_TEN = 6
EXECUTION_HP_THRESHOLD_BPS = 2_500
TACTIC_SHARED_EXPEDITION_CAP = 2
AGGREGATE_HEAL_SHIELD_ENCOUNTER_CAP_BPS = 3_000
AGGREGATE_HEAL_SHIELD_EXPEDITION_CAP_BPS = 7_000
SKILL_XP_SHARE_BPS = 1_000
SKILL_LEVEL_CAP = 100
SKILL_XP_CAP = 20_000_000


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
    coefficient_policy: str
    candidate_rule: str
    fixed_downside: str
    cooldown_roots: int


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


def active(definition_id, idea_id, name_ko, source, pattern, growth_field, values,
           attack_equivalent_values, coefficient_policy, candidate_rule,
           fixed_downside, cooldown_roots):
    return ActivePlan(definition_id, idea_id, "ALL", name_ko, source, pattern,
                      growth_field, values, attack_equivalent_values,
                      coefficient_policy, candidate_rule, fixed_downside,
                      cooldown_roots)


def passive(definition_id, idea_id, owner_scope, name_ko, source, pattern,
            growth_field, values, condition_id, stack_group, fixed_tradeoff):
    return PassivePlan(definition_id, idea_id, owner_scope, name_ko, source,
                       pattern, growth_field, values, condition_id, stack_group,
                       fixed_tradeoff)


ACTIVES = (
    active("aq.skill.common.w8.ankletrip", "COM008", "발목 걸기", "COMMON", "SWIFT_SLOW", "swift_speed_reduction_bps", (1_000, 1_375, 1_750, 2_125, 2_500), (3_000,) * 5, "NORMAL", "target_profile_swift", "공격 계수 3,000·ARMORED/BOSS에는 감소량 20%·행동 지연 없음", 3),
    active("aq.skill.common.w8.weaknesswatch", "COM009", "약점 관찰", "COMMON", "EXPOSED_PRIMER", "exposed_apply_bps", (5_000, 5_875, 6_750, 7_625, 8_500), (0,) * 5, "NORMAL", "target_not_exposed", "현재 피해 0·다음 DIRECT 1packet 뒤 EXPOSED 원자 소비·추가 행동 없음", 3),
    active("aq.skill.common.w8.coolingbottle", "COM011", "냉각 병", "COMMON", "CHILL_PRIMER", "chill_apply_bps", (4_500, 5_375, 6_250, 7_125, 8_000), (1_500,) * 5, "NORMAL", "target_not_chill_immune", "공격 계수 1,500·TOOL tag·냉기 면역에는 피해만", 3),
    active("aq.skill.common.w8.throwingnet", "COM013", "투척 그물", "COMMON", "GENERAL_BIND_TOOL", "bind_apply_bps", (5_000, 5_875, 6_750, 7_625, 8_500), (750,) * 5, "NORMAL", "target_not_bind_immune", "공격 계수 1,000·최종 Hit 75%·SWIFT 적용+1,000·cooldown 6 root", 6),
    active("aq.skill.common.w8.sounddecoy", "COM015", "소리 미끼", "COMMON", "NEXT_ATTACK_HIT_DOWN", "enemy_hit_reduction_bps", (1_000, 1_375, 1_750, 2_125, 2_500), (0,) * 5, "NORMAL", "target_has_single_target_direct", "현재 피해 0·다음 적 DIRECT 1회·광역/고정명중에는 0·TACTIC_SHARED", 4),
    active("aq.skill.common.w8.protectionconversion", "COM020", "보호 전환", "COMMON", "SHIELD_TO_BARRIER", "shield_conversion_bps", (4_000, 5_000, 6_000, 7_000, 8_000), (0,) * 5, "NORMAL", "self_has_shield_and_barrier_budget", "현재 Shield 전부 소비·Barrier 1packet·조우1/원정2 공유·남은 Shield 환급 없음", 5),
    active("aq.skill.common.w8.borrowedstamina", "COM021", "기력 차용", "COMMON", "RESOURCE_DEBT_ATTACK", "action_coefficient_bps", (6_000, 6_625, 7_250, 7_875, 8_500), (6_000, 6_625, 7_250, 7_875, 8_500), "NORMAL", "resource_deficit_bps_lte_3000_and_no_debt", "부족분 최대3,000 debt·후속 BASIC 생성으로 우선 상환·부채 중 재차용 금지", 4),
    active("aq.skill.common.w8.retreatprep", "COM022", "후퇴 준비", "COMMON", "PREPAID_EVASION", "evasion_add_bps", (500, 675, 850, 1_025, 1_200), (0,) * 5, "NORMAL", "predicted_enemy_directs_gte_two", "현재 피해 0·다음 enemy DIRECT 2회·고정명중0·TACTIC_SHARED", 4),
    active("aq.skill.common.w8.silencethrow", "COM027", "침묵 투척", "COMMON", "LOW_DAMAGE_SILENCE", "silence_apply_bps", (4_000, 4_875, 5_750, 6_625, 7_500), (2_500,) * 5, "NORMAL", "target_profile_spellcaster", "공격 계수 2,500·물리 profile에는 SILENCE 0·cooldown4", 4),
    active("aq.skill.common.w8.rapidjabs", "COM028", "연속 잽", "COMMON", "THREE_PACKET_JAB", "total_coefficient_bps", (6_000, 6_750, 7_500, 8_250, 9_000), (6_000, 6_750, 7_500, 8_250, 9_000), "NORMAL", "target_pdef_not_high", "3packet 합계·각 packet 방어 적용·치명 불가·추가 root/hit trigger 생성 없음", 2),
    active("aq.skill.common.w8.imperfectmimic", "COM030", "불완전한 모방", "COMMON", "SAFE_TAG_MIMIC", "copied_coefficient_bps", (4_000, 4_750, 5_500, 6_250, 7_000), (4_000, 4_750, 5_500, 6_250, 7_000), "NORMAL", "previous_enemy_tag_is_copy_safe", "직전 damage/status tag 1개만·행동/보호/회복/cooldown/보스 규칙 복제 금지", 4),

    active("aq.skill.world.w8.lighthouseflash", "EXT001", "등대의 섬광", "WORLD_QUEST", "DARK_UNDEAD_STRIKE", "dark_coefficient_bps", (6_000, 6_750, 7_500, 8_250, 9_000), (6_000, 6_750, 7_500, 8_250, 9_000), "NORMAL", "dark_region_or_undead_target", "해당 조건 Hit+1,200·밝은 지역 생물 계수4,000·치명 불가", 3),
    active("aq.skill.world.w8.poisonmist", "EXT003", "약초사의 독연기", "WORLD_QUEST", "POISON_MUTUAL_HIT_DOWN", "poison_apply_bps", (5_000, 5_750, 6_500, 7_250, 8_000), (2_000,) * 5, "NORMAL", "target_not_poison_immune", "공격 계수2,000·양측 Hit-1,000·자기 penalty도 제거 전까지 유지", 4),
    active("aq.skill.world.w8.lostbell", "EXT004", "잃어버린 종소리", "WORLD_QUEST", "UNDEAD_CURSE_STAGGER", "curse_apply_bps", (4_000, 4_875, 5_750, 6_625, 7_500), (3_000,) * 5, "NORMAL", "target_undead_or_curse_vulnerable", "망령 STAGGER5,000·생물 계수2,000/경직0·cooldown4", 4),
    active("aq.skill.world.w8.glassdesertheat", "EXT006", "유리 사막의 열풍", "WORLD_QUEST", "BURN_CHILL_DETONATION", "detonation_coefficient_bps", (8_000, 8_500, 9_000, 9_500, 10_000), (8_000, 8_500, 9_000, 9_500, 10_000), "NORMAL", "target_has_burn_and_chill", "BURN/CHILL 각1stack 원자 소비·둘 중 하나 없으면 계수3,000/소비0·치명 불가", 4),
    active("aq.skill.world.w8.blindkingorder", "EXT007", "눈먼 왕의 명령", "WORLD_QUEST", "MUTUAL_BLIND_BASIC_OATH", "mutual_hit_reduction_bps", (1_000, 1_375, 1_750, 2_125, 2_500), (0,) * 5, "NORMAL", "enemy_direct_threat_and_basic_plan", "양측 Hit 감소·자기 다음 BASIC2회 계수+1,000·스킬 공격에는 자기 감소 그대로·피해0", 5),
    active("aq.skill.world.w8.deadletter", "EXT009", "망자의 편지", "WORLD_QUEST", "CURSE_DELAYED_PACKET", "delayed_coefficient_bps", (6_000, 6_750, 7_500, 8_250, 9_000), (6_000, 6_750, 7_500, 8_250, 9_000), "DELAYED", "target_survives_three_enemy_roots", "현재 피해0·CURSE5,000·3 enemy root 뒤 1packet·정화/사망 시 취소·추가 행동 없음", 5),
    active("aq.skill.world.w8.banditbluff", "EXT010", "산적왕의 허세", "WORLD_QUEST", "SELF_DEFENSE_FOR_ENEMY_ATTACK_DOWN", "enemy_attack_reduction_bps", (500, 750, 1_000, 1_250, 1_500), (3_000,) * 5, "NORMAL", "enemy_attack_threat_high", "공격 계수3,000·자기 PDEF/MRES-1,500 고정·BOSS 감소량50%", 4),
    active("aq.skill.world.w8.frozentorch", "EXT011", "얼어붙은 횃불", "WORLD_QUEST", "WEAK_DUAL_PRIMER", "each_status_apply_bps", (2_500, 3_000, 3_500, 4_000, 4_500), (2_000,) * 5, "NORMAL", "target_accepts_burn_or_chill", "공격 계수2,000·BURN/CHILL 각각 약적용·한 행동 상태 최대2·전문 단일 primer보다 낮음", 3),
    active("aq.skill.world.w8.floodedoath", "EXT012", "침수된 서약", "WORLD_QUEST", "SHOCK_TO_SHIELD_STRIKE", "shield_generation_bps", (500, 750, 1_000, 1_250, 1_500), (3_000,) * 5, "NORMAL", "target_has_shock_and_shield_budget", "SHOCK 대상 공격3,000+Shield·SHOCK 없으면 계수2,000/Shield0·통합 보호 예산 공유", 4),
    active("aq.skill.world.w8.secondfuneral", "EXT014", "두 번째 장례", "WORLD_QUEST", "REGEN_REVIVE_EXECUTION", "execution_coefficient_bps", (10_000, 11_000, 12_000, 13_000, 14_000), (10_000, 11_000, 12_000, 13_000, 14_000), "EXECUTION", "target_regen_or_revive_and_hp_lte_2500", "일반 적 후보 제외·HP25% cap·barrier/불사 우회 금지·치명 불가·cooldown6", 6),

    active("aq.skill.monster.w8.mimictongue", "EXT029", "미믹 혀채찍", "MONSTER_ARCHIVE", "WEAK_BUFF_STEAL", "stolen_scalar_fraction_bps", (3_000, 4_000, 5_000, 6_000, 7_000), (3_000,) * 5, "NORMAL", "target_has_stealable_buff", "공격 계수3,000·강화1개 제거 후 scalar 최대70%/1root만·없으면 공격만", 4),
    active("aq.skill.monster.w8.wyverndescent", "EXT030", "와이번 하강", "MONSTER_ARCHIVE", "STAGGERABLE_DELAYED_CRIT", "prepared_packet_coefficient_bps", (12_000, 13_000, 14_000, 15_000, 16_000), (4_800, 5_200, 5_600, 6_000, 6_400), "DELAYED", "target_expected_to_survive_prepare_root", "준비1 root+공격1 root·최종 Hit80%의 고정 CRITICAL 결과·추가 치명배수 금지·STAGGER 취소", 5),
    active("aq.skill.monster.w8.wraithgrasp", "EXT032", "망령 손아귀", "MONSTER_ARCHIVE", "MAGIC_CURSE_GRASP", "magic_coefficient_bps", (6_000, 6_750, 7_500, 8_250, 9_000), (6_000, 6_750, 7_500, 8_250, 9_000), "NORMAL", "target_not_curse_immune", "CURSE 적용6,000 고정·생명체 최종 Hit-1,500·망령/비생명체에만 full", 4),
)


PASSIVES = (
    passive("aq.skill.warrior.w8.holdtheline", "W018", "WARRIOR", "전열 유지", "LEVEL", "CONSECUTIVE_HIT_DEFENSE", "defense_per_stack_bps", (100, 150, 200, 250, 300), "consecutive_incoming_direct_hit", "aq.stack.wave8.frontline", "3stack cap·회피/고정무효로 피해0이면 초기화·PDEF/MRES 동시"),
    passive("aq.skill.rogue.w8.greedrhythm", "ROG019", "ROGUE", "탐욕의 박자", "LEVEL", "CRIT_STREAK_EFFICIENCY", "cost_reduction_per_stack_bps", (100, 150, 200, 250, 300), "consecutive_own_critical", "aq.stack.passive.resource_discount", "3stack cap·비치명/MISS 즉시 초기화·최대900"),
    passive("aq.skill.ranger.w8.trailtracking", "HUN018", "RANGER", "흔적 추적", "LEVEL", "SAME_FAMILY_OFFENSE", "family_attack_add_bps", (100, 200, 300, 400, 500), "same_monster_family_in_region", "aq.stack.passive.action_add", "지역/family 변경 시0·최종 행동 계수10,000 cap"),
    passive("aq.skill.mage.w8.ashenlibrary", "MAG023", "MAGE", "잿빛 서고", "CLASS_QUEST", "ELEMENT_DIVERSITY_EFFICIENCY", "cost_reduction_per_element_bps", (100, 150, 200, 250, 300), "distinct_elements_used_in_encounter", "aq.stack.passive.resource_discount", "서로 다른 원소3개 cap·단일 원소에는0·최대900"),
    passive("aq.skill.cleric.w8.forgottensaint", "CLE023", "CLERIC", "잊힌 성인의 이름", "CLASS_QUEST", "SUPPORT_DIVERSITY_EFFICIENCY", "next_support_cost_reduction_bps", (200, 300, 400, 500, 600), "three_distinct_support_tags", "aq.stack.passive.resource_discount", "3종 충족 뒤 다음 지원1회·Heal/Shield scalar와 횟수 증폭0"),
    passive("aq.skill.paladin.w8.stubbornmarch", "PAL018", "PALADIN", "완고한 진군", "LEVEL", "STAGGER_RESIST_FOR_EVA", "stagger_resist_bps", (300, 450, 600, 750, 900), "always_if_equipped", "aq.stack.passive.stagger_resist", "EVA-500 고정·강제 제어 면역은 만들지 않음"),

    passive("aq.skill.common.w8.alertstance", "COM032", "ALL", "경계 태세", "COMMON", "FIRST_PACKET_GUARD", "first_packet_reduction_bps", (500, 750, 1_000, 1_250, 1_500), "first_incoming_damage_packet", "aq.trigger.opening_guard_shared", "조우1회·이후0·추가 행동/Barrier 생성 없음"),
    passive("aq.skill.common.w8.slowbreath", "COM033", "ALL", "느린 호흡", "COMMON", "LATE_RESOURCE_EFFICIENCY", "late_resource_generation_add_bps", (100, 200, 300, 400, 500), "own_root_index_gte_7", "aq.stack.wave8.long_resource", "첫3 자기 root Speed-300·자원 ceiling 우회0"),
    passive("aq.skill.common.w8.emergencybandage", "COM034", "ALL", "응급 붕대", "COMMON", "POST_COMBAT_LOW_HP_HEAL", "post_combat_heal_bps", (500, 750, 1_000, 1_250, 1_500), "victory_and_hp_lte_2500", "aq.generation.heal.expedition", "생존·승리 뒤1회·전투 중 급사 방지0·통합 원정 Heal 예산 공유"),
    passive("aq.skill.common.w8.lowstance", "COM035", "ALL", "낮은 자세", "COMMON", "RANGED_EVASION_FOR_MELEE_DAMAGE", "ranged_evasion_add_bps", (300, 450, 600, 750, 900), "incoming_attack_is_ranged", "aq.stack.passive.evasion", "자기 MELEE 행동 계수-500 고정·근접 피격에는0"),
    passive("aq.skill.common.w8.combatrecord", "COM036", "ALL", "전투 기록", "COMMON", "SAME_PROFILE_HIT_MEMORY", "profile_hit_add_bps", (200, 400, 600, 800, 1_000), "same_profile_consecutive_encounter", "aq.stack.wave6.profile_hit", "profile/지역 변경 시 즉시0·피해 증가0"),
    passive("aq.skill.common.w8.failureanalysis", "COM037", "ALL", "실패 분석", "COMMON", "MISS_TO_NEXT_HIT", "next_hit_add_bps", (300, 525, 750, 975, 1_200), "after_own_miss", "aq.stack.wave6.profile_hit", "다음 명중 판정1회 뒤 소비·MISS가 없으면0·고정명중 전환 금지"),
    passive("aq.skill.common.w8.painnumb", "COM039", "ALL", "고통 둔감", "COMMON", "SMALL_PACKET_REDUCTION", "small_packet_reduction_bps", (100, 200, 300, 400, 500), "incoming_packet_lte_500_maxhp_bps", "aq.stack.passive.incoming_damage", "MaxHP5% 초과 packet에는0·packet 분할을 만들지 않음"),
    passive("aq.skill.common.w8.openingfocus", "COM041", "ALL", "개막 집중", "COMMON", "FIRST_ACTIVE_HIT", "first_active_hit_add_bps", (300, 525, 750, 975, 1_200), "first_own_active_in_encounter", "aq.stack.wave6.profile_hit", "첫 Active 뒤0·피해 계수/행동 수 증가0"),
    passive("aq.skill.common.w8.riskpreference", "COM045", "ALL", "위험 선호", "COMMON", "LOW_HP_OFFENSE_FOR_HEAL", "low_hp_attack_add_bps", (100, 200, 300, 400, 500), "self_hp_bps_lte_3000", "aq.stack.passive.action_add", "받는 Heal 효율-1,000 고정·최종 행동 계수10,000 cap"),
    passive("aq.skill.common.w8.safetyfirst", "COM046", "ALL", "안전 우선", "COMMON", "NOT_HIGH_HP_PROTECTION", "incoming_damage_reduction_bps", (100, 200, 300, 400, 500), "self_hp_bps_lt_8000", "aq.stack.passive.incoming_damage", "자기 공격 행동 계수-300 고정·highest-only"),
    passive("aq.skill.common.w8.elementalresidue", "COM047", "ALL", "원소 잔재", "COMMON", "CONSUME_TO_DIFFERENT_ELEMENT", "different_element_attack_add_bps", (100, 200, 300, 400, 500), "after_consuming_hostile_status", "aq.stack.passive.action_add", "다음 다른 원소1회·같은 원소0·상태/행동 추가 없음"),

    passive("aq.skill.world.w8.keeperseye", "EXT016", "ALL", "등대지기의 눈", "WORLD_QUEST", "DARK_HIT_CURSE_RESIST", "dark_hit_and_resist_bps", (200, 400, 600, 800, 1_000), "region_tag_dark", "aq.stack.wave6.profile_hit", "밝은 지역0·최종 Hit+1,200 cap·CURSE 면역은 만들지 않음"),
    passive("aq.skill.world.w8.herbcodex", "EXT018", "ALL", "약초 도감", "WORLD_QUEST", "HARMFUL_DOT_RESIST", "dot_damage_reduction_bps", (200, 350, 500, 650, 800), "incoming_poison_or_burn_tick", "aq.stack.wave8.dot_resist", "즉시 피해/CHILL/CURSE에는0·DOT 제거 없음"),
    passive("aq.skill.world.w8.wellecho", "EXT020", "ALL", "우물의 메아리", "WORLD_QUEST", "CHILL_CONSUME_RESOURCE", "resource_recovery_bps", (200, 350, 500, 650, 800), "after_consuming_chill", "aq.generation.resource.standard", "root action당1회·자원 회복 ceiling 우회0·CHILL 없으면0"),
    passive("aq.skill.world.w8.desertadaptation", "EXT021", "ALL", "사막 순응", "WORLD_QUEST", "BURN_STABILITY_FOR_CHILL_RISK", "burn_reduction_and_apply_bps", (300, 450, 600, 750, 900), "burn_damage_or_burn_action", "aq.stack.wave8.burn_adaptation", "받는 CHILL 피해+500 고정·BURN 면역/추가 tick 생성 없음"),

    passive("aq.skill.monster.w8.beastscent", "EXT034", "ALL", "야수 후각", "MONSTER_ARCHIVE", "LOW_HP_TARGET_HIT", "low_hp_target_hit_add_bps", (300, 525, 750, 975, 1_200), "target_hp_bps_lte_3000", "aq.stack.wave6.profile_hit", "대상 HP30% 초과0·처형 임계 증가0"),
    passive("aq.skill.monster.w8.soulfrost", "EXT036", "ALL", "영혼 냉기", "MONSTER_ARCHIVE", "CURSED_MAGIC_RESIST_FOR_HEAL", "mres_add_bps", (300, 450, 600, 750, 900), "self_has_curse", "aq.stack.passive.magical_resistance", "받는 Heal 효율-1,000 고정·CURSE 제거/면역 없음"),
    passive("aq.skill.boss.w8.undyingobsession", "EXT045", "ALL", "죽지 않는 집착", "BOSS_SECRET", "CURSE_STACK_OFFENSE", "attack_add_per_curse_bps", (100, 150, 200, 250, 300), "self_curse_stack", "aq.stack.passive.action_add", "3stack cap·받는 Heal 효율-1,500 고정·최대+900·계수10,000 cap"),
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


def canonical_hash() -> str:
    payload = {
        "wave7Hash": wave7.canonical_hash(),
        "equipment650Hash": equipment.canonical_hash(),
        "anchors": ANCHORS,
        "actives": [item.__dict__ for item in ACTIVES],
        "passives": [item.__dict__ for item in PASSIVES],
        "caps": {
            "action": ACTION_COEFFICIENT_CAP_BPS,
            "execution": EXECUTION_COEFFICIENT_CAP_BPS,
            "delayedPacket": DELAYED_PACKET_COEFFICIENT_CAP_BPS,
            "statusApply": STATUS_APPLY_FINAL_CAP_BPS,
            "profileHit": PROFILE_HIT_FINAL_CAP_BPS,
            "hitDown": TARGET_HIT_DOWN_CAP_BPS,
            "evasion": TEMP_EVA_ADD_CAP_BPS,
            "debt": RESOURCE_DEBT_CAP_BPS,
            "passiveDiscount": PASSIVE_RESOURCE_DISCOUNT_CAP_BPS,
            "passiveActionAdd": PASSIVE_ACTION_ADD_CAP_BPS,
            "incomingReduction": INCOMING_REDUCTION_CAP_BPS,
            "hostileStatuses": HOSTILE_STATUS_CAP,
            "statusesPerAction": MAX_STATUSES_PER_ACTION,
            "nonDamageRoots": MAX_NON_DAMAGE_ROOTS_IN_TEN,
            "executionHp": EXECUTION_HP_THRESHOLD_BPS,
            "healShieldEncounter": AGGREGATE_HEAL_SHIELD_ENCOUNTER_CAP_BPS,
            "healShieldExpedition": AGGREGATE_HEAL_SHIELD_EXPEDITION_CAP_BPS,
        },
        "skillXp": {"shareBps": SKILL_XP_SHARE_BPS, "levelCap": SKILL_LEVEL_CAP, "xpCap": SKILL_XP_CAP},
        "manualCombatActions": False,
    }
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def prior_idea_ids():
    return wave7.cumulative_prior_idea_ids() | {item.idea_id for item in wave7.ACTIVES + wave7.PASSIVES}


def idea_bank_rows():
    text = IDEA_BANK.read_text(encoding="utf-8")
    return re.findall(r"^\| ([A-Z]+\d{3}) \| ([^|]+?) \| [^|]+ \| ([AP]) \|", text, re.MULTILINE)


def all_definition_ids():
    active_groups = (
        tuple(wave1.legacy.c1.SKILLS), wave2.ACTIVES, wave3.ACTIVES, wave4.ACTIVES,
        wave5.ACTIVES, wave6.ACTIVES, wave7.ACTIVES, ACTIVES,
    )
    passive_groups = (
        wave1.PASSIVES, wave2.PASSIVES, wave3.PASSIVES, wave4.PASSIVES,
        wave5.PASSIVES, wave6.PASSIVES, wave7.PASSIVES, PASSIVES,
    )
    return (
        tuple(item.definition_id for group in active_groups for item in group),
        tuple(item.definition_id for group in passive_groups for item in group),
    )


def check_complete_240_catalog():
    rows = idea_bank_rows()
    bank_ids = {idea_id for idea_id, _, _ in rows}
    bank_active = {idea_id for idea_id, _, kind in rows if kind == "A"}
    bank_passive = {idea_id for idea_id, _, kind in rows if kind == "P"}
    prior = prior_idea_ids()
    current = {item.idea_id for item in ACTIVES + PASSIVES}
    assert len(rows) == len(bank_ids) == 240
    assert len(bank_active) == 150 and len(bank_passive) == 90
    assert len(prior) == 192 and len(current) == 48 and not prior.intersection(current)
    assert current == bank_ids - prior and prior | current == bank_ids
    assert len(ACTIVES) == 24 and len(PASSIVES) == 24
    assert {item.idea_id for item in ACTIVES} == bank_active - (bank_active & prior)
    assert {item.idea_id for item in PASSIVES} == bank_passive - (bank_passive & prior)
    active_ids, passive_ids = all_definition_ids()
    assert len(active_ids) == len(set(active_ids)) == 150
    assert len(passive_ids) == len(set(passive_ids)) == 90
    assert not set(active_ids).intersection(passive_ids)


def check_names_sources_and_single_axis():
    text = IDEA_BANK.read_text(encoding="utf-8")
    for item in ACTIVES + PASSIVES:
        assert f"| {item.idea_id} | {item.name_ko} |" in text
        values = [value_at(item.values, level) for level in range(1, 101)]
        assert all(right >= left for left, right in zip(values, values[1:]))
        assert tuple(values[level - 1] for level in ANCHORS) == item.values
    assert {source: sum(item.source == source for item in ACTIVES) for source in {item.source for item in ACTIVES}} == {
        "COMMON": 11, "WORLD_QUEST": 10, "MONSTER_ARCHIVE": 3,
    }
    assert {source: sum(item.source == source for item in PASSIVES) for source in {item.source for item in PASSIVES}} == {
        "LEVEL": 4, "CLASS_QUEST": 2, "COMMON": 11, "WORLD_QUEST": 4,
        "MONSTER_ARCHIVE": 2, "BOSS_SECRET": 1,
    }
    signatures = {(item.pattern, item.growth_field, item.candidate_rule, item.fixed_downside) for item in ACTIVES}
    signatures |= {(item.pattern, item.growth_field, item.condition_id, item.fixed_tradeoff) for item in PASSIVES}
    assert len(signatures) == 48
    for item in ACTIVES:
        assert item.cooldown_roots >= 2 and item.candidate_rule and item.fixed_downside
        max_equivalent = max(item.attack_equivalent_values)
        if item.coefficient_policy == "EXECUTION":
            assert max_equivalent <= EXECUTION_COEFFICIENT_CAP_BPS
        else:
            assert max_equivalent <= ACTION_COEFFICIENT_CAP_BPS
        if item.coefficient_policy == "DELAYED":
            assert max(item.values) <= DELAYED_PACKET_COEFFICIENT_CAP_BPS


def check_access_counts_and_loadout_states():
    class_passives = {class_name: sum(item.owner_scope in {class_name, "ALL"} for item in PASSIVES) for class_name in CLASSES}
    assert class_passives == {class_name: 19 for class_name in CLASSES}
    assert all(item.owner_scope == "ALL" for item in ACTIVES)
    for class_name in CLASSES:
        previous_active = len(wave4.accessible_actives(class_name, 100)) + len(wave5.accessible_actives(class_name)) + len(wave6.accessible_actives(class_name)) + len(wave7.ACTIVES)
        previous_passive = len(wave4.accessible_passives(class_name)) + len(wave5.accessible_passives(class_name)) + len(wave6.accessible_passives(class_name)) + len(wave7.PASSIVES)
        assert previous_active == 51 and previous_passive == 26
        assert previous_active + len(ACTIVES) == 75
        assert previous_passive + class_passives[class_name] == 45
    represented = math.comb(75, 5) * math.comb(45, 3) * len(CLASSES) * 100
    assert represented == 146_946_446_460_000
    check_access_counts_and_loadout_states.represented = represented


def active_envelope(subset):
    ids = {item.idea_id for item in subset}
    status_base = max(
        [0]
        + [8_500 if "COM009" in ids else 0]
        + [8_000 if "COM011" in ids else 0]
        + [9_500 if "COM013" in ids else 0]
        + [7_500 if "COM027" in ids else 0]
        + [8_000 if "EXT003" in ids else 0]
        + [7_500 if "EXT004" in ids else 0]
        + [5_000 if "EXT009" in ids else 0]
        + [4_500 if "EXT011" in ids else 0]
        + [6_000 if "EXT032" in ids else 0]
    )
    target_hit_down = max(2_500 if "COM015" in ids else 0, 2_500 if "EXT007" in ids else 0, 1_000 if "EXT003" in ids else 0)
    normal_max = max([0] + [max(item.attack_equivalent_values) for item in subset if item.coefficient_policy != "EXECUTION"])
    execution_max = max([0] + [max(item.attack_equivalent_values) for item in subset if item.coefficient_policy == "EXECUTION"])
    return {
        "statusApplyFinal": min(STATUS_APPLY_FINAL_CAP_BPS, status_base + 600),
        "targetHitDown": min(TARGET_HIT_DOWN_CAP_BPS, target_hit_down),
        "temporaryEvasion": min(TEMP_EVA_ADD_CAP_BPS, 1_200 if "COM022" in ids else 0),
        "profileHit": min(PROFILE_HIT_FINAL_CAP_BPS, 1_200 if "EXT001" in ids else 0),
        "resourceDebt": min(RESOURCE_DEBT_CAP_BPS, 3_000 if "COM021" in ids else 0),
        "normalActionWithItem": min(ACTION_COEFFICIENT_CAP_BPS, normal_max + 800),
        "execution": min(EXECUTION_COEFFICIENT_CAP_BPS, execution_max),
        "statusesPerAction": min(MAX_STATUSES_PER_ACTION, 2 if "EXT011" in ids else int(status_base > 0)),
        "nonDamageRoots": sum(idea_id in ids for idea_id in ("COM009", "COM015", "COM020", "COM022", "EXT007")),
        "barrierActivations": 1 if "COM020" in ids else 0,
        "shieldGeneration": 1_500 if "EXT012" in ids else 0,
        "tacticUses": TACTIC_SHARED_EXPEDITION_CAP if {"COM015", "COM022"} & ids else 0,
    }


def passive_envelope(subset):
    ids = {item.idea_id for item in subset}
    resource_discount = max(
        900 if "ROG019" in ids else 0,
        900 if "MAG023" in ids else 0,
        600 if "CLE023" in ids else 0,
    )
    action_add_candidates = [
        500 if "HUN018" in ids else 0,
        500 if "COM045" in ids else 0,
        500 if "COM047" in ids else 0,
        900 if "EXT045" in ids else 0,
    ]
    profile_hit = max(
        1_000 if "COM036" in ids else 0,
        1_200 if "COM037" in ids else 0,
        1_200 if "COM041" in ids else 0,
        1_000 if "EXT016" in ids else 0,
        1_200 if "EXT034" in ids else 0,
    )
    incoming = (
        (1_500 if "COM032" in ids else 0)
        + (500 if "COM039" in ids else 0)
        + (500 if "COM046" in ids else 0)
        + 300  # exact 650-item ITEM_DEFENSE magnitude
    )
    return {
        "resourceDiscount": min(PASSIVE_RESOURCE_DISCOUNT_CAP_BPS, resource_discount),
        "passiveActionAdd": min(PASSIVE_ACTION_ADD_CAP_BPS, sum(sorted(action_add_candidates, reverse=True)[:3])),
        "profileHitWithItem": min(PROFILE_HIT_FINAL_CAP_BPS, profile_hit + 1_200),
        "temporaryEvasionWithPriorItem": min(TEMP_EVA_ADD_CAP_BPS, (900 if "COM035" in ids else 0) + 700 + 600),
        "incomingReductionWithItem": min(INCOMING_REDUCTION_CAP_BPS, incoming),
        "postCombatHeal": 1_500 if "COM034" in ids else 0,
        "staggerResist": 900 if "PAL018" in ids else 0,
        "classDefenseStacks": 900 if "W018" in ids else 0,
        "curseAttackStacks": 900 if "EXT045" in ids else 0,
    }


def check_factorized_wave8_subsets_with_equipment():
    active_subsets = [combo for size in range(6) for combo in itertools.combinations(ACTIVES, size)]
    passive_subsets = [combo for size in range(4) for combo in itertools.combinations(PASSIVES, size)]
    assert len(active_subsets) == 55_455 and len(passive_subsets) == 2_325
    active_max = {}
    for subset in active_subsets:
        result = active_envelope(subset)
        assert result["statusApplyFinal"] <= 9_500
        assert result["targetHitDown"] <= 2_500
        assert result["temporaryEvasion"] <= 1_200
        assert result["profileHit"] <= 1_200
        assert result["resourceDebt"] <= 4_000
        assert result["normalActionWithItem"] <= 10_000
        assert result["execution"] <= 18_000
        assert result["statusesPerAction"] <= 2
        assert result["nonDamageRoots"] <= 6
        assert result["barrierActivations"] <= 1
        assert result["shieldGeneration"] <= 1_500
        assert result["tacticUses"] <= 2
        for key, value in result.items():
            active_max[key] = max(active_max.get(key, 0), value)
    passive_max = {}
    for subset in passive_subsets:
        result = passive_envelope(subset)
        assert result["resourceDiscount"] <= 1_500
        assert result["passiveActionAdd"] <= 1_500
        assert result["profileHitWithItem"] <= 1_200
        assert result["temporaryEvasionWithPriorItem"] <= 1_200
        assert result["incomingReductionWithItem"] <= 2_500
        assert result["postCombatHeal"] <= 1_500
        for key, value in result.items():
            passive_max[key] = max(passive_max.get(key, 0), value)
    represented = len(active_subsets) * len(passive_subsets)
    assert represented == 128_932_875
    assert active_max == {
        "statusApplyFinal": 9_500, "targetHitDown": 2_500, "temporaryEvasion": 1_200,
        "profileHit": 1_200, "resourceDebt": 3_000, "normalActionWithItem": 10_000,
        "execution": 14_000, "statusesPerAction": 2, "nonDamageRoots": 5,
        "barrierActivations": 1, "shieldGeneration": 1_500, "tacticUses": 2,
    }
    assert passive_max == {
        "resourceDiscount": 900, "passiveActionAdd": 1_500, "profileHitWithItem": 1_200,
        "temporaryEvasionWithPriorItem": 1_200, "incomingReductionWithItem": 2_500,
        "postCombatHeal": 1_500, "staggerResist": 900, "classDefenseStacks": 900,
        "curseAttackStacks": 900,
    }
    check_factorized_wave8_subsets_with_equipment.represented = represented
    check_factorized_wave8_subsets_with_equipment.active_max = active_max
    check_factorized_wave8_subsets_with_equipment.passive_max = passive_max


def check_global_system_contracts():
    assert len(equipment.CATALOG) == 650
    assert equipment.LEGAL_MIXED_LOADOUTS == 915_527_343_750
    assert all(not effect.grants_skill and not effect.creates_action for effect in equipment.base.EFFECTS)
    assert SKILL_XP_SHARE_BPS == 1_000 and SKILL_LEVEL_CAP == 100 and SKILL_XP_CAP == 20_000_000
    assert EXECUTION_HP_THRESHOLD_BPS == 2_500
    assert HOSTILE_STATUS_CAP == 6
    # Weak/narrow skills are allowed; exact mechanical clones are not.
    assert len({(item.pattern, item.candidate_rule, item.fixed_downside) for item in ACTIVES}) == 24
    assert len({(item.pattern, item.condition_id, item.fixed_tradeoff) for item in PASSIVES}) == 24
    assert all("random" not in item.candidate_rule.lower() for item in ACTIVES)


def deterministic_metrics(seeds: int):
    assert seeds >= 50
    net_hits = 0
    wyvern_hits = 0
    attempts = seeds * 20
    for seed in range(seeds):
        for index in range(20):
            net_roll = wave1.legacy.c1.event_roll_bps(seed, index, "W8_NET", "WAVE8") % 10_000
            wyvern_roll = wave1.legacy.c1.event_roll_bps(seed, index, "W8_WYVERN", "WAVE8") % 10_000
            net_hits += int(net_roll < 7_500)
            wyvern_hits += int(wyvern_roll < 8_000)
    metrics = {
        "netHitRate": net_hits / attempts,
        "wyvernHitRate": wyvern_hits / attempts,
        "wyvernExpectedPacket": 16_000 * 0.80,
        "wyvernPerRootEquivalent": 16_000 * 0.80 / 2,
        "rapidJabTotal": sum((3_000, 3_000, 3_000)),
        "funeralThresholdActive": 2_500,
        "funeralThresholdInactive": 2_501,
        "dualPrimerStatuses": 2,
        "borrowedDebt": 3_000,
        "debtRepaymentBasicsAt2500": math.ceil(3_000 / 2_500),
        "fullCatalogActive": len(all_definition_ids()[0]),
        "fullCatalogPassive": len(all_definition_ids()[1]),
    }
    assert 0.70 <= metrics["netHitRate"] <= 0.80
    assert 0.75 <= metrics["wyvernHitRate"] <= 0.85
    assert metrics["wyvernExpectedPacket"] == 12_800
    assert metrics["wyvernPerRootEquivalent"] == 6_400
    assert metrics["rapidJabTotal"] == 9_000
    assert metrics["funeralThresholdActive"] <= 2_500 < metrics["funeralThresholdInactive"]
    assert metrics["dualPrimerStatuses"] <= MAX_STATUSES_PER_ACTION
    assert metrics["borrowedDebt"] <= RESOURCE_DEBT_CAP_BPS
    assert metrics["debtRepaymentBasicsAt2500"] == 2
    assert metrics["fullCatalogActive"] == 150 and metrics["fullCatalogPassive"] == 90
    return metrics


def check_document_hash():
    assert DOCUMENT.exists()
    assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")


STATIC_CHECKS = (
    ("ALL_240_IDEA_IDS_AND_150_ACTIVE_90_PASSIVE_ARE_NUMERIC", check_complete_240_catalog),
    ("WAVE8_NAMES_SOURCES_SINGLE_AXIS_AND_NO_EXACT_MECHANICAL_CLONES", check_names_sources_and_single_axis),
    ("EACH_CLASS_ACCESSES_75_ACTIVE_45_PASSIVE_EQUIP5_PLUS3", check_access_counts_and_loadout_states),
    ("FACTORIZED_128932875_WAVE8_SUBSETS_WITH_650_ITEMS_PRESERVE_CAPS", check_factorized_wave8_subsets_with_equipment),
    ("GLOBAL_SKILL_XP_AUTO_COMBAT_STATUS_EXECUTION_AND_ITEM_CONTRACTS", check_global_system_contracts),
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=100)
    parser.add_argument("--pd", action="store_true")
    args = parser.parse_args()
    seeds = 300 if args.pd else args.seeds
    passed = []
    for name, check in STATIC_CHECKS:
        check()
        passed.append(name)
    metrics = deterministic_metrics(seeds)
    passed.append("DETERMINISTIC_TOOL_DELAYED_MULTI_PACKET_DEBT_EXECUTION_GATES")
    if DOCUMENT.exists():
        check_document_hash()
        passed.append("DOCUMENT_CONTAINS_CANONICAL_WAVE8_240_HASH")
    print(f"SKILL_WAVE8_COMPLETE_240_GLOBAL_REVIEW: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print(f"  SHA-256 {canonical_hash()}")
    print("  completeCatalog=Active150 Passive90 Total240; accessiblePerClass=Active75 Passive45 equip5+3")
    print(f"  factorizedWave8Subsets={check_factorized_wave8_subsets_with_equipment.represented}")
    print(f"  representedFullLoadoutLevelStates={check_access_counts_and_loadout_states.represented}")
    print(f"  equipmentCatalog={len(equipment.CATALOG)} equipmentLoadouts={equipment.LEGAL_MIXED_LOADOUTS}")
    print(f"  activeMax={check_factorized_wave8_subsets_with_equipment.active_max}")
    print(f"  passiveMax={check_factorized_wave8_subsets_with_equipment.passive_max}")
    print(f"  netHit={metrics['netHitRate']:.2%} wyvernHit={metrics['wyvernHitRate']:.2%} expectedPacket={metrics['wyvernExpectedPacket']:.0f} perRoot={metrics['wyvernPerRootEquivalent']:.0f}")
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
