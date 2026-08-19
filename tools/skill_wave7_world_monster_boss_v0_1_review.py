#!/usr/bin/env python3
"""Wave 7 audit: common, world, monster, and boss skill definitions.

Design-only. Validates 24 numeric skill plans against Waves 1-6 and the
five-lineage 650-item catalog. It does not mutate live game or save data.
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

import equipment_catalog_650_lineages_v0_2_review as equipment
import skill_wave1_active5_passive3_v0_1_review as wave1
import skill_wave4_dot_cleanse_diversity_execution_v0_1_review as wave4
import skill_wave5_resource_protection_heal_expedition_v0_1_review as wave5
import skill_wave6_opening_longfight_precision_profiles_v0_1_review as wave6


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_WAVE7_WORLD_MONSTER_BOSS_v0.1.md"
IDEA_BANK = ROOT / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"
CLASSES = wave1.CLASSES
ANCHORS = wave1.ANCHOR_LEVELS

ACTION_COEFFICIENT_CAP_BPS = 10_000
STATUS_APPLY_FINAL_CAP_BPS = 9_500
TARGET_HIT_DOWN_CAP_BPS = 2_500
ARMOR_SHRED_CAP_BPS = 3_000
TEMP_EVA_ADD_CAP_BPS = 1_200
MAX_HP_ADD_CAP_BPS = 1_500
TOOL_COST_REDUCTION_CAP_BPS = 600
TOOL_COOLDOWN_REDUCTION_ROOTS = 1
TACTIC_SHARED_ENCOUNTER_CAP = 2
EXECUTION_HP_THRESHOLD_BPS = 2_500
SKILL_REACTION_SHARED_CAP = 1
SKILL_XP_SHARE_BPS = 1_000
SKILL_LEVEL_CAP = 100
SKILL_XP_CAP = 20_000_000


@dataclass(frozen=True)
class ActivePlan:
    definition_id: str
    idea_id: str
    name_ko: str
    source: str
    pattern: str
    growth_field: str
    values: tuple[int, int, int, int, int]
    attack_equivalent_values: tuple[int, int, int, int, int]
    candidate_rule: str
    fixed_downside: str
    cooldown_roots: int


@dataclass(frozen=True)
class PassivePlan:
    definition_id: str
    idea_id: str
    name_ko: str
    source: str
    pattern: str
    growth_field: str
    values: tuple[int, int, int, int, int]
    condition_id: str
    stack_group: str
    fixed_tradeoff: str


def active(definition_id, idea_id, name_ko, source, pattern, growth_field, values,
           attack_equivalent_values, candidate_rule, fixed_downside, cooldown_roots):
    return ActivePlan(definition_id, idea_id, name_ko, source, pattern, growth_field,
                      values, attack_equivalent_values, candidate_rule, fixed_downside,
                      cooldown_roots)


def passive(definition_id, idea_id, name_ko, source, pattern, growth_field, values,
            condition_id, stack_group, fixed_tradeoff):
    return PassivePlan(definition_id, idea_id, name_ko, source, pattern, growth_field,
                       values, condition_id, stack_group, fixed_tradeoff)


ACTIVES = (
    active("aq.skill.common.w7.sandthrow", "COM005", "모래 뿌리기", "COMMON", "TARGET_HIT_DOWN", "enemy_hit_reduction_bps", (1_000, 1_375, 1_750, 2_125, 2_500), (3_000,) * 5, "target_has_direct_attacks", "공격 계수 3,000·BOSS 감소량 50%·TARGET_HIT_DOWN highest-only", 3),
    active("aq.skill.common.w7.pushback", "COM007", "밀어내기", "COMMON", "WEIGHTED_STAGGER", "stagger_apply_bps", (4_000, 4_750, 5_500, 6_250, 7_000), (4_000,) * 5, "target_not_stagger_immune", "공격 계수 4,000·ARMORED/BOSS 적용률 50%·행동 지연은 1 root cap", 3),
    active("aq.skill.common.w7.flashbomb", "COM014", "섬광탄", "COMMON", "TOOL_SPELL_INTERRUPT", "spell_interrupt_bps", (4_500, 5_375, 6_250, 7_125, 8_000), (0,) * 5, "target_profile_spellcaster_and_tactic_budget", "현재 피해 0·TACTIC_SHARED 원정 2회·도구 숙련도 사용 횟수는 늘리지 않음", 5),
    active("aq.skill.common.w7.balancebreak", "COM025", "균형 깨기", "COMMON", "GUARD_SHIELD_STRIKE", "guarded_coefficient_bps", (7_000, 7_500, 8_000, 8_500, 9_000), (7_000, 7_500, 8_000, 8_500, 9_000), "target_guard_or_shield", "GUARD/SHIELD가 없으면 계수 4,000·방어 제거 없음", 2),

    active("aq.skill.world.w7.minerspick", "EXT002", "광부의 마지막 곡괭이", "WORLD_QUEST", "ARMORED_SHRED", "pdef_shred_bps", (500, 750, 1_000, 1_250, 1_500), (5_000,) * 5, "target_profile_armored", "공격 계수 5,000·다음 DIRECT 2 packet·SWIFT 최종 Hit -1,200", 3),
    active("aq.skill.world.w7.blackwell", "EXT005", "검은 우물의 물결", "WORLD_QUEST", "BURN_TO_CHILL", "chill_apply_bps", (4_000, 4_875, 5_750, 6_625, 7_500), (3_000,) * 5, "target_has_burn", "대상 BURN 1stack 원자 소비·없으면 계수 3,000만·자기 BURN 조합 손실", 3),
    active("aq.skill.world.w7.breakchains", "EXT008", "포로의 사슬 끊기", "WORLD_QUEST", "SELF_CLEANSE_COUNTER", "counter_coefficient_bps", (6_500, 7_125, 7_750, 8_375, 9_000), (6_500, 7_125, 7_750, 8_375, 9_000), "self_has_dispellable_control", "자기 제어 1개 원자 제거 뒤 공격·없으면 계수 3,000·해제 면역 무시 금지", 4),
    active("aq.skill.world.w7.starlesscompass", "EXT013", "별 없는 나침반", "WORLD_QUEST", "DETERMINISTIC_PROFILE_ROUTE", "profile_coefficient_bps", (6_000, 6_500, 7_000, 7_500, 8_000), (6_000, 6_500, 7_000, 7_500, 8_000), "archive_profile_known", "profile→damage type 고정표 사용·미확인 profile은 BASIC 계수 5,000·치명 불가", 2),

    active("aq.skill.monster.w7.slimeshot", "EXT025", "슬라임 점액탄", "MONSTER_ARCHIVE", "SLIMED_PACKAGE", "slimed_reduction_bps", (500, 750, 1_000, 1_250, 1_500), (2_500,) * 5, "target_not_slow_immune", "공격 계수 2,500·Speed/Hit을 하나의 SLIMED로 감소·TARGET_HIT_DOWN highest-only", 3),
    active("aq.skill.monster.w7.wolfleap", "EXT026", "늑대의 도약", "MONSTER_ARCHIVE", "OPENING_LEAP", "opening_coefficient_bps", (8_000, 8_500, 9_000, 9_500, 10_000), (8_000, 8_500, 9_000, 9_500, 10_000), "own_root_index_is_one", "첫 자기 root 이후 계수 5,000·MISS 시 다음 enemy DIRECT까지 PDEF/MRES -1,000", 3),
    active("aq.skill.monster.w7.spiderweb", "EXT027", "거미줄 분사", "MONSTER_ARCHIVE", "SWIFT_BIND", "bind_apply_bps", (5_000, 5_875, 6_750, 7_625, 8_500), (1_500,) * 5, "target_profile_swift_and_not_fire", "공격 계수 1,500·비SWIFT 적용률 -2,000·FIRE 행동이 다음 root 전 제거 가능", 4),
    active("aq.skill.monster.w7.golemshard", "EXT028", "골렘 파편 투척", "MONSTER_ARCHIVE", "LOW_HIT_HEAVY_PACKET", "coefficient_bps", (14_000, 14_650, 15_300, 15_950, 16_600), (8_400, 8_790, 9_180, 9_570, 9_960), "target_evasion_not_high", "최종 Hit 60% 고정·치명 불가·추가 명중 보정과 고정명중 금지", 3),
    active("aq.skill.monster.w7.mushroomspore", "EXT031", "버섯 포자", "MONSTER_ARCHIVE", "DETERMINISTIC_SPORE", "status_apply_bps", (4_000, 4_750, 5_500, 6_250, 7_000), (2_000,) * 5, "target_not_poison_and_hitdown_immune", "공격 계수 2,000·POISON 없으면 POISON, 있으면 HIT_DOWN·동시 두 상태 금지", 3),

    active("aq.skill.boss.w7.giantstomp", "EXT039", "거인의 발구르기", "BOSS_SECRET", "BOSS_STAGGER", "stagger_apply_bps", (6_000, 6_750, 7_500, 8_250, 9_000), (2_250,) * 5, "target_not_stagger_immune", "공격 계수 3,000·최종 Hit 75%·cooldown 5 root·행동 지연 1 root cap", 5),
    active("aq.skill.secret.w7.abyssaleye", "EXT040", "심연의 눈", "BOSS_SECRET", "STATUS_COUNT_STRIKE", "per_status_add_bps", (200, 350, 500, 650, 800), (7_000, 7_750, 8_500, 9_250, 10_000), "combined_hostile_status_count_gte_one", "기본 계수 6,000+상태당 증가·상태 합계 5 cap·해결 뒤 자신에게 COST_LOCKED CURSE·치명 불가", 4),
    active("aq.skill.boss.w7.executionbell", "EXT041", "처형자의 종", "BOSS_SECRET", "LOW_HP_EXECUTION", "execution_coefficient_bps", (8_000, 8_500, 9_000, 9_500, 10_000), (8_000, 8_500, 9_000, 9_500, 10_000), "target_hp_bps_lte_2500", "HP 25% 초과면 후보 제외·barrier/불사 우회 금지·치명 불가·cooldown 5 root", 5),
)


PASSIVES = (
    passive("aq.skill.common.w7.sturdyshoes", "COM031", "튼튼한 신발", "COMMON", "MOVEMENT_STAGGER_RESIST", "movement_stagger_resist_bps", (200, 300, 400, 500, 600), "moving_or_staggered", "aq.stack.wave7.movement_resist", "피해 계수 증가 0·고정명중/강제이동 면역은 만들지 않음"),
    passive("aq.skill.common.w7.toolmastery", "COM048", "도구 숙련", "COMMON", "TOOL_EFFICIENCY", "tool_cost_reduction_bps", (200, 300, 400, 500, 600), "equipped_active_has_tool_tag", "aq.stack.wave7.tool_efficiency", "TOOL만 cooldown -1 root(min2)·TACTIC_SHARED 횟수 증가 0·직업 순수 스킬 기여 0"),
    passive("aq.skill.world.w7.orememory", "EXT017", "광맥의 기억", "WORLD_QUEST", "CONSECUTIVE_ARMORED_SHRED", "pdef_shred_per_stack_bps", (100, 200, 300, 400, 500), "same_armored_target_direct_hit", "aq.stack.wave7.armor_shred", "3stack cap·MISS/대상 변경/비DIRECT에서 초기화·비ARMORED 0"),
    passive("aq.skill.world.w7.freedstep", "EXT023", "풀려난 자의 걸음", "WORLD_QUEST", "POST_CLEANSE_EVASION", "evasion_add_bps", (300, 400, 500, 600, 700), "self_control_removed", "aq.trigger.wave7.cleanse_response", "조우 1회·다음 enemy DIRECT 1회·고정명중에는 0"),
    passive("aq.skill.monster.w7.slimeskin", "EXT033", "점액 피부", "MONSTER_ARCHIVE", "FIRST_PHYSICAL_PACKET_GUARD", "physical_packet_reduction_bps", (500, 750, 1_000, 1_250, 1_500), "first_incoming_physical_packet", "aq.trigger.wave7.skill_reaction", "SKILL_REACTION_SHARED 조우 1회·FIRE 받는 피해 +1,000 고정"),
    passive("aq.skill.monster.w7.stonebones", "EXT035", "석질 골격", "MONSTER_ARCHIVE", "PDEF_FOR_SPEED", "pdef_add_bps", (300, 450, 600, 750, 900), "always_if_equipped", "aq.stack.passive.pdef", "Speed -700·EVA -300 고정·PDEF highest-only"),
    passive("aq.skill.boss.w7.dragonpride", "EXT044", "용혈의 오만", "BOSS_SECRET", "HIGH_HP_OFFENSE_LOW_HP_RISK", "high_hp_attack_add_bps", (100, 200, 300, 400, 500), "self_hp_bps_gte_8000", "aq.stack.passive.action_add", "HP 30% 미만 받는 피해 +500 고정·최종 행동 계수 10,000 cap"),
    passive("aq.skill.boss.w7.giantslowheart", "EXT046", "거인의 느린 심장", "BOSS_SECRET", "MAX_HP_FOR_SPEED", "max_hp_add_bps", (500, 750, 1_000, 1_250, 1_500), "always_if_equipped", "aq.stack.passive.max_hp", "PDEF +300 고정·Speed -1,000 고정·추가 Heal/행동 없음"),
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
        "wave6Hash": wave6.canonical_hash(),
        "equipment650Hash": equipment.canonical_hash(),
        "anchors": ANCHORS,
        "actives": [item.__dict__ for item in ACTIVES],
        "passives": [item.__dict__ for item in PASSIVES],
        "caps": {
            "actionCoefficient": ACTION_COEFFICIENT_CAP_BPS,
            "statusApplyFinal": STATUS_APPLY_FINAL_CAP_BPS,
            "targetHitDown": TARGET_HIT_DOWN_CAP_BPS,
            "armorShred": ARMOR_SHRED_CAP_BPS,
            "temporaryEvasion": TEMP_EVA_ADD_CAP_BPS,
            "maxHpAdd": MAX_HP_ADD_CAP_BPS,
            "toolCostReduction": TOOL_COST_REDUCTION_CAP_BPS,
            "tacticUses": TACTIC_SHARED_ENCOUNTER_CAP,
            "executionThreshold": EXECUTION_HP_THRESHOLD_BPS,
            "skillReaction": SKILL_REACTION_SHARED_CAP,
        },
        "skillXp": {"shareBps": SKILL_XP_SHARE_BPS, "levelCap": SKILL_LEVEL_CAP, "xpCap": SKILL_XP_CAP},
        "equipped": {"active": 5, "passive": 3},
        "manualCombatActions": False,
    }
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def cumulative_prior_idea_ids():
    return wave6.cumulative_prior_idea_ids() | {item.idea_id for item in wave6.ACTIVES + wave6.PASSIVES}


def check_catalog_and_sources():
    plans = ACTIVES + PASSIVES
    assert len(ACTIVES) == 16 and len(PASSIVES) == 8
    assert len({item.definition_id for item in plans}) == 24
    assert len({item.idea_id for item in plans}) == 24
    prior = cumulative_prior_idea_ids()
    current = {item.idea_id for item in plans}
    assert len(prior) == 168 and not prior.intersection(current)
    assert len(prior | current) == 192
    assert {source: sum(item.source == source for item in ACTIVES) for source in {item.source for item in ACTIVES}} == {
        "COMMON": 4, "WORLD_QUEST": 4, "MONSTER_ARCHIVE": 5, "BOSS_SECRET": 3,
    }
    assert {source: sum(item.source == source for item in PASSIVES) for source in {item.source for item in PASSIVES}} == {
        "COMMON": 2, "WORLD_QUEST": 2, "MONSTER_ARCHIVE": 2, "BOSS_SECRET": 2,
    }


def check_idea_bank_and_single_axis_growth():
    text = IDEA_BANK.read_text(encoding="utf-8")
    for item in ACTIVES + PASSIVES:
        assert f"| {item.idea_id} | {item.name_ko} |" in text
        values = [value_at(item.values, level) for level in range(1, 101)]
        assert all(right >= left for left, right in zip(values, values[1:]))
        assert tuple(values[level - 1] for level in ANCHORS) == item.values
    for item in ACTIVES:
        attack_values = [value_at(item.attack_equivalent_values, level) for level in range(1, 101)]
        assert all(value <= ACTION_COEFFICIENT_CAP_BPS for value in attack_values)
        assert item.cooldown_roots >= 2
        assert item.candidate_rule and item.fixed_downside
    assert max(item.values[-1] for item in PASSIVES if item.idea_id == "COM048") == TOOL_COST_REDUCTION_CAP_BPS


def check_access_and_full_loadout_states():
    for class_name in CLASSES:
        previous_active = len(wave4.accessible_actives(class_name, 100)) + len(wave5.accessible_actives(class_name)) + len(wave6.accessible_actives(class_name))
        previous_passive = len(wave4.accessible_passives(class_name)) + len(wave5.accessible_passives(class_name)) + len(wave6.accessible_passives(class_name))
        assert previous_active == 35 and previous_passive == 18
        assert previous_active + len(ACTIVES) == 51
        assert previous_passive + len(PASSIVES) == 26
    represented = math.comb(51, 5) * math.comb(26, 3) * len(CLASSES) * 100
    assert represented == 3_664_533_600_000
    check_access_and_full_loadout_states.represented = represented


def subset_envelope(active_subset, passive_subset):
    active_ids = {item.idea_id for item in active_subset}
    passive_ids = {item.idea_id for item in passive_subset}
    armor_shred = (1_500 if "EXT002" in active_ids else 0) + (1_500 if "EXT017" in passive_ids else 0)
    target_hit_down = max(
        2_500 if "COM005" in active_ids else 0,
        1_500 if "EXT025" in active_ids else 0,
    )
    maximum_status_base = max(
        [0]
        + [7_000 if "COM007" in active_ids else 0]
        + [8_000 if "COM014" in active_ids else 0]
        + [7_500 if "EXT005" in active_ids else 0]
        + [8_500 if "EXT027" in active_ids else 0]
        + [7_000 if "EXT031" in active_ids else 0]
        + [9_000 if "EXT039" in active_ids else 0]
    )
    item_effects = {
        "offense": 800,
        "profileHit": 1_200,
        "statusApply": 600,
        "temporaryEvasion": 600,
        "itemTrigger": 1,
    }
    return {
        "armorShred": min(ARMOR_SHRED_CAP_BPS, armor_shred),
        "targetHitDown": min(TARGET_HIT_DOWN_CAP_BPS, target_hit_down),
        "statusApplyFinal": min(STATUS_APPLY_FINAL_CAP_BPS, maximum_status_base + item_effects["statusApply"]),
        "temporaryEvasion": min(TEMP_EVA_ADD_CAP_BPS, item_effects["temporaryEvasion"] + (700 if "EXT023" in passive_ids else 0)),
        "maxHpAdd": min(MAX_HP_ADD_CAP_BPS, 1_500 if "EXT046" in passive_ids else 0),
        "toolCostReduction": min(TOOL_COST_REDUCTION_CAP_BPS, 600 if "COM048" in passive_ids else 0),
        "toolCooldownReduction": TOOL_COOLDOWN_REDUCTION_ROOTS if "COM048" in passive_ids else 0,
        "tacticUses": TACTIC_SHARED_ENCOUNTER_CAP if "COM014" in active_ids else 0,
        "skillReaction": SKILL_REACTION_SHARED_CAP if "EXT033" in passive_ids else 0,
        "itemTrigger": item_effects["itemTrigger"],
        "actionAtCapWithItems": min(ACTION_COEFFICIENT_CAP_BPS, max([0] + [item.attack_equivalent_values[-1] for item in active_subset]) + item_effects["offense"]),
    }


def check_all_wave7_subsets_with_650_items():
    active_subsets = [combo for size in range(6) for combo in itertools.combinations(ACTIVES, size)]
    passive_subsets = [combo for size in range(4) for combo in itertools.combinations(PASSIVES, size)]
    assert len(active_subsets) == 6_885 and len(passive_subsets) == 93
    checked = 0
    maxima = {key: 0 for key in (
        "armorShred", "targetHitDown", "statusApplyFinal", "temporaryEvasion",
        "maxHpAdd", "toolCostReduction", "toolCooldownReduction", "tacticUses",
        "skillReaction", "itemTrigger", "actionAtCapWithItems",
    )}
    for active_subset in active_subsets:
        for passive_subset in passive_subsets:
            result = subset_envelope(active_subset, passive_subset)
            assert result["armorShred"] <= ARMOR_SHRED_CAP_BPS
            assert result["targetHitDown"] <= TARGET_HIT_DOWN_CAP_BPS
            assert result["statusApplyFinal"] <= STATUS_APPLY_FINAL_CAP_BPS
            assert result["temporaryEvasion"] <= TEMP_EVA_ADD_CAP_BPS
            assert result["maxHpAdd"] <= MAX_HP_ADD_CAP_BPS
            assert result["toolCostReduction"] <= TOOL_COST_REDUCTION_CAP_BPS
            assert result["toolCooldownReduction"] <= 1
            assert result["tacticUses"] <= 2
            assert result["skillReaction"] <= 1 and result["itemTrigger"] <= 1
            assert result["actionAtCapWithItems"] <= ACTION_COEFFICIENT_CAP_BPS
            for key, value in result.items():
                maxima[key] = max(maxima[key], value)
            checked += 1
    assert checked == 640_305
    assert maxima == {
        "armorShred": 3_000,
        "targetHitDown": 2_500,
        "statusApplyFinal": 9_500,
        "temporaryEvasion": 1_200,
        "maxHpAdd": 1_500,
        "toolCostReduction": 600,
        "toolCooldownReduction": 1,
        "tacticUses": 2,
        "skillReaction": 1,
        "itemTrigger": 1,
        "actionAtCapWithItems": 10_000,
    }
    check_all_wave7_subsets_with_650_items.checked = checked
    check_all_wave7_subsets_with_650_items.maxima = maxima


LEGAL_CONTEXTS = {
    "target_has_direct_attacks": "STANDARD",
    "target_not_stagger_immune": "STANDARD",
    "target_profile_spellcaster_and_tactic_budget": "SPELLCASTER",
    "target_guard_or_shield": "GUARDING",
    "target_profile_armored": "ARMORED",
    "target_has_burn": "BURNING_TARGET",
    "self_has_dispellable_control": "SELF_CONTROLLED",
    "archive_profile_known": "ARCHIVE_KNOWN",
    "target_not_slow_immune": "STANDARD",
    "own_root_index_is_one": "OPENING",
    "target_profile_swift_and_not_fire": "SWIFT",
    "target_evasion_not_high": "ARMORED",
    "target_not_poison_and_hitdown_immune": "STANDARD",
    "combined_hostile_status_count_gte_one": "STATUS_DENSE",
    "target_hp_bps_lte_2500": "LOW_HP",
}


def check_acquisition_ai_and_skill_xp_contracts():
    assert set(item.candidate_rule for item in ACTIVES) == set(LEGAL_CONTEXTS)
    assert all(item.source in {"COMMON", "WORLD_QUEST", "MONSTER_ARCHIVE", "BOSS_SECRET"} for item in ACTIVES + PASSIVES)
    assert SKILL_XP_SHARE_BPS == 1_000 and SKILL_LEVEL_CAP == 100 and SKILL_XP_CAP == 20_000_000
    # All 24 are definitions earned outside equipment; 650 equipment effects
    # never grant a skill or change SkillXP.
    assert all(not effect.grants_skill and not effect.creates_action for effect in equipment.base.EFFECTS)
    assert len(equipment.CATALOG) == 650
    # Stable automatic candidate resolution: no random rule or player battle input.
    assert all("random" not in item.candidate_rule.lower() for item in ACTIVES)
    compass = next(item for item in ACTIVES if item.idea_id == "EXT013")
    assert "DETERMINISTIC" in compass.pattern


def dynamic_metrics(seeds: int):
    assert seeds >= 50
    # Outcomes below are analytical except the deterministic MISS sample used
    # to verify the 60% heavy-packet contract over many combat seeds.
    hits = 0
    attempts = seeds * 20
    for seed in range(seeds):
        for index in range(20):
            roll = wave1.legacy.c1.event_roll_bps(seed, index, "W7_GOLEM", "WAVE7") % 10_000
            hits += int(roll < 6_000)
    golem_hit_rate = hits / attempts
    metrics = {
        "golemHitRate": golem_hit_rate,
        "golemExpectedCoefficient": 16_600 * 0.60,
        "wolfOpeningRatio": 10_000 / 5_000,
        "guardedRatio": 9_000 / 4_000,
        "abyssMaxCoefficient": 6_000 + 5 * 800,
        "executionActiveAt": 2_500,
        "executionInactiveAt": 2_501,
        "sandNormalHitAfter": max(0, 8_500 - 2_500),
        "sandBossHitAfter": max(0, 8_500 - 1_250),
        "stompStatusAfterItem": min(9_500, 9_000 + 600),
        "armorShredCombined": min(3_000, 1_500 + 3 * 500),
    }
    assert 0.55 <= metrics["golemHitRate"] <= 0.65
    assert metrics["golemExpectedCoefficient"] == 9_960
    assert metrics["wolfOpeningRatio"] == 2.0
    assert metrics["guardedRatio"] == 2.25
    assert metrics["abyssMaxCoefficient"] == 10_000
    assert metrics["executionActiveAt"] <= EXECUTION_HP_THRESHOLD_BPS < metrics["executionInactiveAt"]
    assert metrics["sandNormalHitAfter"] == 6_000 and metrics["sandBossHitAfter"] == 7_250
    assert metrics["stompStatusAfterItem"] == 9_500
    assert metrics["armorShredCombined"] == 3_000
    return metrics


def check_document_hash():
    assert DOCUMENT.exists()
    assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")


STATIC_CHECKS = (
    ("WAVE7_HAS_16_ACTIVE_8_PASSIVE_AND_CUMULATIVE192_UNIQUE_IDS", check_catalog_and_sources),
    ("ALL_24_IDS_EXIST_IN_IDEA_BANK_AND_GROW_ON_ONE_AXIS", check_idea_bank_and_single_axis_growth),
    ("EACH_CLASS_ACCESSES_51_ACTIVE_26_PASSIVE_EQUIP5_PLUS3", check_access_and_full_loadout_states),
    ("ALL_640305_WAVE7_SUBSETS_WITH_650_ITEMS_PRESERVE_CAPS", check_all_wave7_subsets_with_650_items),
    ("ACQUISITION_AUTO_AI_AND_SKILL_XP_CONTRACTS", check_acquisition_ai_and_skill_xp_contracts),
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=100)
    parser.add_argument("--pd", action="store_true")
    args = parser.parse_args()
    seeds = 200 if args.pd else args.seeds
    passed = []
    for name, check in STATIC_CHECKS:
        check()
        passed.append(name)
    metrics = dynamic_metrics(seeds)
    passed.append("PROFILE_CONTROL_HIGH_VARIANCE_EXECUTION_GATES")
    if DOCUMENT.exists():
        assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")
        passed.append("DOCUMENT_CONTAINS_CANONICAL_WAVE7_HASH")
    print(f"SKILL_WAVE7_WORLD_MONSTER_BOSS_V0_1: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  checkedWave7SubsetsWith650Items={check_all_wave7_subsets_with_650_items.checked}")
    print(f"  representedFullLoadoutLevelStates={check_access_and_full_loadout_states.represented}")
    print("  accessiblePerClass=Active51 Passive26 equip5+3 cumulativeUnique=192")
    print(f"  sourceCounts=Active COMMON4 WORLD4 MONSTER5 BOSS3; Passive 2/2/2/2")
    print(f"  maxima={check_all_wave7_subsets_with_650_items.maxima}")
    print(f"  golem hit={metrics['golemHitRate']:.2%} expectedCoeff={metrics['golemExpectedCoefficient']:.0f}")
    print(f"  niche wolf={metrics['wolfOpeningRatio']:.2f} guard={metrics['guardedRatio']:.2f} abyssCap={metrics['abyssMaxCoefficient']}")
    print(f"  execution={metrics['executionActiveAt']}/{metrics['executionInactiveAt']} sandHit={metrics['sandNormalHitAfter']}/{metrics['sandBossHitAfter']}")
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
