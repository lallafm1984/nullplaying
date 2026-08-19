#!/usr/bin/env python3
"""Design audit for AlarmQuest equipment catalog/economy vertical slice.

Expands 40 rarity-slot families into 130 concrete item definitions and checks
the p10/p50/p90 acquisition/enhancement targets. It never mutates live data.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import math
from dataclasses import asdict, dataclass
from pathlib import Path

from equipment_effects_enhancement_8slots_v0_1_review import (
    ENHANCEMENT_BONUS_BPS,
    RARITIES,
    SLOTS,
    enhanced_slot_power,
    enhancement_cost,
)


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_EQUIPMENT_CATALOG_ECONOMY_VERTICAL_SLICE_v0.1.md"

RARITY_IDS = tuple(item.rarity_id for item in RARITIES)
SLOT_IDS = tuple(item.slot_id for item in SLOTS)
RARITY_INDEX = {rarity_id: index for index, rarity_id in enumerate(RARITY_IDS)}
RARITY_EFFECT_BUDGET = {item.rarity_id: item.effect_budget_bps for item in RARITIES}
RARITY_EFFECT_COUNTS = {
    "COMMON": 0,
    "UNCOMMON": 1,
    "RARE": 2,
    "EPIC": 3,
    "LEGENDARY": 2,
}

THEMES = {
    "COMMON": "개척자",
    "UNCOMMON": "솔바람",
    "RARE": "철등불",
    "EPIC": "은회",
    "LEGENDARY": "별잠",
}

ACQUISITION_SOURCES = {
    "COMMON": "STARTER_OR_MARKET",
    "UNCOMMON": "GREEN_HILLS_FIELD_CRAFT",
    "RARE": "OLD_MINE_REGION_CRAFT",
    "EPIC": "ASHEN_FOREST_ELITE_CRAFT",
    "LEGENDARY": "THREE_REGION_RELIC_CRAFT",
}


@dataclass(frozen=True)
class ClassProfile:
    class_id: str
    name_ko: str
    weapon_suffix: str
    offhand_suffix: str
    armor_profiles: tuple[str, ...]
    default_armor_profile: str
    offhand_stats: tuple[tuple[str, int], ...]


@dataclass(frozen=True)
class EffectDefinition:
    effect_id: str
    name_ko: str
    slot_id: str
    stack_group: str
    scalar: int
    budget_cost: int
    condition: str
    description: str
    fixed_tradeoff: str = ""
    creates_action: bool = False
    grants_skill: bool = False


@dataclass(frozen=True)
class ItemDefinition:
    definition_id: str
    name_ko: str
    slot_id: str
    rarity_id: str
    equip_profile: str
    allowed_class_ids: tuple[str, ...]
    base_power_bps: int
    stat_split: tuple[tuple[str, int], ...]
    effect_ids: tuple[str, ...]
    fixed_downside: str
    acquisition_source: str


@dataclass(frozen=True)
class EconomyCohort:
    cohort_id: str
    field_marks_per_day: float
    region_sigils_per_day: float
    elite_crests_per_day: float
    boss_cores_per_day: float
    relic_traces_per_day: float
    legend_seals_per_day: float
    gold_index_per_day: float
    forge_units_per_day: float
    rare_units_per_day: float
    ascension_cores_per_day: float


CLASSES = (
    ClassProfile(
        "aq.class.warrior", "전사", "장검", "원형 방패", ("MEDIUM", "HEAVY"), "HEAVY",
        (("PDEF", 5_500), ("MRES", 2_000), ("MAX_HP", 2_500)),
    ),
    ClassProfile(
        "aq.class.rogue", "도적", "기교 단검", "자물쇠 도구", ("LIGHT",), "LIGHT",
        (("PRIMARY_ATTACK", 4_000), ("EVA", 3_500), ("HIT", 2_500)),
    ),
    ClassProfile(
        "aq.class.ranger", "사냥꾼", "장궁", "추적 화살통", ("LIGHT", "MEDIUM"), "MEDIUM",
        (("PRIMARY_ATTACK", 4_500), ("HIT", 3_500), ("MAX_RESOURCE", 2_000)),
    ),
    ClassProfile(
        "aq.class.mage", "마법사", "마력봉", "룬 마법서", ("LIGHT",), "LIGHT",
        (("PRIMARY_ATTACK", 4_500), ("MRES", 3_000), ("MAX_RESOURCE", 2_500)),
    ),
    ClassProfile(
        "aq.class.cleric", "성직자", "성철퇴", "순례 성물", ("MEDIUM",), "MEDIUM",
        (("PRIMARY_ATTACK", 3_500), ("MRES", 4_000), ("MAX_HP", 2_500)),
    ),
    ClassProfile(
        "aq.class.paladin", "성기사", "서약검", "수호 방패", ("MEDIUM", "HEAVY"), "HEAVY",
        (("PDEF", 4_000), ("MRES", 4_000), ("MAX_HP", 2_000)),
    ),
)

CLASS_BY_ID = {item.class_id: item for item in CLASSES}
ALL_CLASS_IDS = tuple(item.class_id for item in CLASSES)

ARMOR_SUFFIXES = {
    "HEAD": {"LIGHT": "두건", "MEDIUM": "사슬투구", "HEAVY": "면갑"},
    "BODY": {"LIGHT": "로브", "MEDIUM": "사슬갑옷", "HEAVY": "판금갑옷"},
    "HANDS": {"LIGHT": "손등싸개", "MEDIUM": "사슬장갑", "HEAVY": "철건틀릿"},
    "FEET": {"LIGHT": "장화", "MEDIUM": "각반화", "HEAVY": "판금장화"},
}

ARMOR_STAT_SPLITS = {
    "HEAD": {
        "LIGHT": (("MRES", 4_000), ("HIT", 3_000), ("STATUS_RESIST", 3_000)),
        "MEDIUM": (("PDEF", 3_500), ("MRES", 3_000), ("HIT", 2_000), ("STATUS_RESIST", 1_500)),
        "HEAVY": (("PDEF", 5_000), ("MRES", 2_500), ("MAX_HP", 2_500)),
    },
    "BODY": {
        "LIGHT": (("PDEF", 2_000), ("MRES", 4_500), ("MAX_HP", 3_500)),
        "MEDIUM": (("PDEF", 4_500), ("MRES", 3_000), ("MAX_HP", 2_500)),
        "HEAVY": (("PDEF", 6_000), ("MRES", 1_500), ("MAX_HP", 2_500)),
    },
    "HANDS": {
        "LIGHT": (("PRIMARY_ATTACK", 3_000), ("HIT", 3_000), ("MRES", 2_000), ("PDEF", 2_000)),
        "MEDIUM": (("PRIMARY_ATTACK", 2_500), ("PDEF", 3_500), ("HIT", 2_000), ("MRES", 2_000)),
        "HEAVY": (("PDEF", 4_500), ("MAX_HP", 2_500), ("PRIMARY_ATTACK", 1_500), ("HIT", 1_500)),
    },
    "FEET": {
        "LIGHT": (("EVA", 3_500), ("SPEED", 3_000), ("PDEF", 1_500), ("MRES", 2_000)),
        "MEDIUM": (("PDEF", 3_000), ("MRES", 2_500), ("EVA", 2_500), ("SPEED", 2_000)),
        "HEAVY": (("PDEF", 5_000), ("MRES", 2_000), ("MAX_HP", 2_000), ("STAGGER_RESIST", 1_000)),
    },
}

UNIVERSAL_STAT_SPLITS = {
    "WAIST": (("MAX_RESOURCE", 4_000), ("MAX_HP", 2_000), ("PDEF", 2_000), ("MRES", 2_000)),
    "ACCESSORY": (("HIT", 2_500), ("STATUS_POWER", 2_500), ("STATUS_RESIST", 2_500), ("PRIMARY_ATTACK", 2_500)),
}


EFFECTS = (
    EffectDefinition("aq.item.fx.weapon_tuned", "정련된 날", "WEAPON", "ITEM_OFFENSE", 100, 300, "ALWAYS", "공격 행동 계수 +100 BPS"),
    EffectDefinition("aq.item.fx.weapon_profile", "갑주 판독", "WEAPON", "PROFILE_HIT", 300, 300, "ARMORED_OR_BOSS", "해당 profile 명중 +300 BPS"),
    EffectDefinition("aq.item.fx.weapon_status", "상태 각인", "WEAPON", "STATUS_APPLY", 200, 300, "STATUS_ACTION", "상태 적용 +200 BPS"),
    EffectDefinition("aq.item.fx.weapon_boss_vow", "별잠의 맹세", "WEAPON", "ITEM_OFFENSE", 300, 900, "BOSS", "BOSS 대상 공격 행동 계수 +300 BPS"),
    EffectDefinition("aq.item.fx.offhand_guard", "보조 방호", "OFFHAND", "ITEM_DEFENSE", -100, 300, "ALWAYS", "받는 피해 -100 BPS"),
    EffectDefinition("aq.item.fx.offhand_profile", "전술 렌즈", "OFFHAND", "PROFILE_HIT", 300, 300, "ARMORED_OR_SWIFT", "해당 profile 명중 +300 BPS"),
    EffectDefinition("aq.item.fx.offhand_resist", "봉인 안감", "OFFHAND", "STATUS_RESIST", 300, 300, "ALWAYS", "상태 저항 +300 BPS"),
    EffectDefinition("aq.item.fx.offhand_first_packet", "첫 충격 굴절", "OFFHAND", "ITEM_TRIGGER", 0, 900, "FIRST_DAMAGE_PACKET", "조우 첫 피해 packet 20% 완화, 조우 1회"),
    EffectDefinition("aq.item.fx.head_resist", "맑은 안감", "HEAD", "STATUS_RESIST", 300, 300, "ALWAYS", "상태 저항 +300 BPS"),
    EffectDefinition("aq.item.fx.head_low_stat", "약점 보정 눈금", "HEAD", "PROFILE_HIT", 300, 300, "PRIMARY_RAW_STAT_LE_12", "낮은 주능력치일 때 명중 +300 BPS, 치명 -200 BPS", fixed_tradeoff="CRIT_MINUS_200_BPS"),
    EffectDefinition("aq.item.fx.head_crit_resist", "급소 보호", "HEAD", "CRIT_RESIST", 200, 300, "ALWAYS", "피치명 보정 +200 BPS"),
    EffectDefinition("aq.item.fx.head_clear_mind", "고요한 별빛", "HEAD", "STATUS_RESIST", 600, 900, "CONTROL_STATUS", "제어 상태 저항 +600 BPS"),
    EffectDefinition("aq.item.fx.body_hp", "생명 직조", "BODY", "MAX_HP", 300, 300, "ALWAYS", "MaxHP +300 BPS"),
    EffectDefinition("aq.item.fx.body_guard", "갑주 결속", "BODY", "ITEM_DEFENSE", -100, 300, "ALWAYS", "받는 피해 -100 BPS"),
    EffectDefinition("aq.item.fx.body_resist", "마력 누빔", "BODY", "STATUS_RESIST", 300, 300, "ALWAYS", "상태 저항 +300 BPS"),
    EffectDefinition("aq.item.fx.body_first_packet", "별빛 완충", "BODY", "ITEM_TRIGGER", 0, 900, "FIRST_DAMAGE_PACKET", "조우 첫 피해 packet 25% 완화, 공유 조우 1회"),
    EffectDefinition("aq.item.fx.hands_crit", "정밀 손놀림", "HANDS", "CRIT", 200, 300, "ALWAYS", "치명 +200 BPS"),
    EffectDefinition("aq.item.fx.hands_offense", "집중 타격", "HANDS", "ITEM_OFFENSE", 200, 300, "ALWAYS", "공격 행동 계수 +200 BPS"),
    EffectDefinition("aq.item.fx.hands_status", "각인 손끝", "HANDS", "STATUS_APPLY", 200, 300, "STATUS_ACTION", "상태 적용 +200 BPS"),
    EffectDefinition("aq.item.fx.hands_low_resource", "마지막 집중", "HANDS", "ITEM_OFFENSE", 200, 900, "RESOURCE_LE_25_PERCENT", "저자원 공격 행동 계수 +200 BPS"),
    EffectDefinition("aq.item.fx.waist_resource", "넉넉한 수납", "WAIST", "MAX_RESOURCE", 300, 300, "ALWAYS", "MaxResource +300 BPS"),
    EffectDefinition("aq.item.fx.waist_supply", "보급 절약", "WAIST", "ATTRITION", 200, 300, "EXPEDITION", "일반 원정 attrition -200 BPS"),
    EffectDefinition("aq.item.fx.waist_guard", "허리 중심", "WAIST", "ITEM_DEFENSE", -100, 300, "ALWAYS", "받는 피해 -100 BPS"),
    EffectDefinition("aq.item.fx.waist_reserve", "별잠 예비대", "WAIST", "MAX_RESOURCE", 600, 900, "ALWAYS", "MaxResource +600 BPS"),
    EffectDefinition("aq.item.fx.feet_eva", "가벼운 발", "FEET", "EVA", 300, 300, "ALWAYS", "회피 +300 BPS"),
    EffectDefinition("aq.item.fx.feet_stagger", "뿌리 박기", "FEET", "STAGGER_RESIST", 300, 300, "ALWAYS", "경직 저항 +300 BPS"),
    EffectDefinition("aq.item.fx.feet_profile", "추적 보폭", "FEET", "PROFILE_HIT", 300, 300, "SWIFT", "SWIFT 명중 +300 BPS"),
    EffectDefinition("aq.item.fx.feet_mist", "안개걸음", "FEET", "EVA", 600, 900, "FIRST_3_ROUNDS", "첫 3라운드 회피 +600 BPS"),
    EffectDefinition("aq.item.fx.accessory_resist", "저항 부적", "ACCESSORY", "STATUS_RESIST", 300, 300, "ALWAYS", "상태 저항 +300 BPS"),
    EffectDefinition("aq.item.fx.accessory_offense", "집중 부적", "ACCESSORY", "ITEM_OFFENSE", 200, 300, "AFFLICTED_TARGET", "상태 대상 공격 행동 계수 +200 BPS"),
    EffectDefinition("aq.item.fx.accessory_status", "각인 부적", "ACCESSORY", "STATUS_APPLY", 200, 300, "STATUS_ACTION", "상태 적용 +200 BPS"),
    EffectDefinition("aq.item.fx.accessory_harmony", "별잠 공명", "ACCESSORY", "ITEM_OFFENSE", 200, 900, "AFFLICTED_TARGET", "상태 대상 공격 행동 계수 +200 BPS"),
)

EFFECT_BY_ID = {item.effect_id: item for item in EFFECTS}

EFFECT_LOADOUTS = {
    "WEAPON": {
        "COMMON": (),
        "UNCOMMON": ("aq.item.fx.weapon_tuned",),
        "RARE": ("aq.item.fx.weapon_profile", "aq.item.fx.weapon_status"),
        "EPIC": ("aq.item.fx.weapon_tuned", "aq.item.fx.weapon_profile", "aq.item.fx.weapon_status"),
        "LEGENDARY": ("aq.item.fx.weapon_tuned", "aq.item.fx.weapon_boss_vow"),
    },
    "OFFHAND": {
        "COMMON": (),
        "UNCOMMON": ("aq.item.fx.offhand_guard",),
        "RARE": ("aq.item.fx.offhand_profile", "aq.item.fx.offhand_resist"),
        "EPIC": ("aq.item.fx.offhand_guard", "aq.item.fx.offhand_profile", "aq.item.fx.offhand_resist"),
        "LEGENDARY": ("aq.item.fx.offhand_guard", "aq.item.fx.offhand_first_packet"),
    },
    "HEAD": {
        "COMMON": (),
        "UNCOMMON": ("aq.item.fx.head_resist",),
        "RARE": ("aq.item.fx.head_low_stat", "aq.item.fx.head_crit_resist"),
        "EPIC": ("aq.item.fx.head_resist", "aq.item.fx.head_low_stat", "aq.item.fx.head_crit_resist"),
        "LEGENDARY": ("aq.item.fx.head_low_stat", "aq.item.fx.head_clear_mind"),
    },
    "BODY": {
        "COMMON": (),
        "UNCOMMON": ("aq.item.fx.body_hp",),
        "RARE": ("aq.item.fx.body_guard", "aq.item.fx.body_resist"),
        "EPIC": ("aq.item.fx.body_hp", "aq.item.fx.body_guard", "aq.item.fx.body_resist"),
        "LEGENDARY": ("aq.item.fx.body_hp", "aq.item.fx.body_first_packet"),
    },
    "HANDS": {
        "COMMON": (),
        "UNCOMMON": ("aq.item.fx.hands_crit",),
        "RARE": ("aq.item.fx.hands_offense", "aq.item.fx.hands_status"),
        "EPIC": ("aq.item.fx.hands_crit", "aq.item.fx.hands_offense", "aq.item.fx.hands_status"),
        "LEGENDARY": ("aq.item.fx.hands_crit", "aq.item.fx.hands_low_resource"),
    },
    "WAIST": {
        "COMMON": (),
        "UNCOMMON": ("aq.item.fx.waist_resource",),
        "RARE": ("aq.item.fx.waist_supply", "aq.item.fx.waist_guard"),
        "EPIC": ("aq.item.fx.waist_resource", "aq.item.fx.waist_supply", "aq.item.fx.waist_guard"),
        "LEGENDARY": ("aq.item.fx.waist_resource", "aq.item.fx.waist_reserve"),
    },
    "FEET": {
        "COMMON": (),
        "UNCOMMON": ("aq.item.fx.feet_eva",),
        "RARE": ("aq.item.fx.feet_stagger", "aq.item.fx.feet_profile"),
        "EPIC": ("aq.item.fx.feet_eva", "aq.item.fx.feet_stagger", "aq.item.fx.feet_profile"),
        "LEGENDARY": ("aq.item.fx.feet_profile", "aq.item.fx.feet_mist"),
    },
    "ACCESSORY": {
        "COMMON": (),
        "UNCOMMON": ("aq.item.fx.accessory_resist",),
        "RARE": ("aq.item.fx.accessory_offense", "aq.item.fx.accessory_status"),
        "EPIC": ("aq.item.fx.accessory_resist", "aq.item.fx.accessory_offense", "aq.item.fx.accessory_status"),
        "LEGENDARY": ("aq.item.fx.accessory_status", "aq.item.fx.accessory_harmony"),
    },
}

LEGENDARY_DOWNSIDES = {
    "WEAPON": "NON_BOSS_HIT_MINUS_200_BPS",
    "OFFHAND": "MAX_RESOURCE_MINUS_500_BPS",
    "HEAD": "CRIT_MINUS_200_BPS",
    "BODY": "SPEED_MINUS_300_BPS",
    "HANDS": "FULL_RESOURCE_HIT_MINUS_200_BPS",
    "WAIST": "SPEED_MINUS_200_BPS",
    "FEET": "PDEF_MINUS_300_BPS",
    "ACCESSORY": "UNAFFLICTED_TARGET_INCOMING_PLUS_200_BPS",
}

COHORTS = (
    EconomyCohort("P10", 40, 30, 60, 0.25, 40, 0.06, 180, 30, 8, 0.06),
    EconomyCohort("P50", 80, 60, 120, 0.50, 80, 0.12, 360, 55, 18, 0.12),
    EconomyCohort("P90", 120, 90, 180, 0.75, 120, 0.20, 600, 90, 30, 0.20),
)

ACQUISITION_COSTS = {
    "COMMON": {},
    "UNCOMMON": {"field_marks": 40},
    "RARE": {"region_sigils": 240},
    "EPIC": {"elite_crests": 900, "boss_cores": 1},
    "LEGENDARY": {"relic_traces": 4_000, "legend_seals": 3},
}


def base_power_bps(rarity_id: str) -> int:
    return next(item.base_power_bps for item in RARITIES if item.rarity_id == rarity_id)


def stat_split_for_hand(slot_id: str, class_profile: ClassProfile) -> tuple[tuple[str, int], ...]:
    if slot_id == "WEAPON":
        return (("PRIMARY_ATTACK", 10_000),)
    return class_profile.offhand_stats


def build_catalog() -> tuple[ItemDefinition, ...]:
    catalog: list[ItemDefinition] = []
    for rarity_id in RARITY_IDS:
        theme = THEMES[rarity_id]
        downside_by_slot = LEGENDARY_DOWNSIDES if rarity_id == "LEGENDARY" else {}
        for slot_id in ("WEAPON", "OFFHAND"):
            for class_profile in CLASSES:
                suffix = class_profile.weapon_suffix if slot_id == "WEAPON" else class_profile.offhand_suffix
                catalog.append(ItemDefinition(
                    definition_id=f"aq.item.vs.{rarity_id.lower()}.{slot_id.lower()}.{class_profile.class_id.rsplit('.', 1)[-1]}",
                    name_ko=f"{theme} {suffix}",
                    slot_id=slot_id,
                    rarity_id=rarity_id,
                    equip_profile=class_profile.class_id,
                    allowed_class_ids=(class_profile.class_id,),
                    base_power_bps=base_power_bps(rarity_id),
                    stat_split=stat_split_for_hand(slot_id, class_profile),
                    effect_ids=EFFECT_LOADOUTS[slot_id][rarity_id],
                    fixed_downside=downside_by_slot.get(slot_id, ""),
                    acquisition_source=ACQUISITION_SOURCES[rarity_id],
                ))
        for slot_id in ("HEAD", "BODY", "HANDS", "FEET"):
            for armor_profile in ("LIGHT", "MEDIUM", "HEAVY"):
                allowed = tuple(item.class_id for item in CLASSES if armor_profile in item.armor_profiles)
                catalog.append(ItemDefinition(
                    definition_id=f"aq.item.vs.{rarity_id.lower()}.{slot_id.lower()}.{armor_profile.lower()}",
                    name_ko=f"{theme} {ARMOR_SUFFIXES[slot_id][armor_profile]}",
                    slot_id=slot_id,
                    rarity_id=rarity_id,
                    equip_profile=armor_profile,
                    allowed_class_ids=allowed,
                    base_power_bps=base_power_bps(rarity_id),
                    stat_split=ARMOR_STAT_SPLITS[slot_id][armor_profile],
                    effect_ids=EFFECT_LOADOUTS[slot_id][rarity_id],
                    fixed_downside=downside_by_slot.get(slot_id, ""),
                    acquisition_source=ACQUISITION_SOURCES[rarity_id],
                ))
        for slot_id, suffix in (("WAIST", "장비띠"), ("ACCESSORY", "부적")):
            catalog.append(ItemDefinition(
                definition_id=f"aq.item.vs.{rarity_id.lower()}.{slot_id.lower()}.universal",
                name_ko=f"{theme} {suffix}",
                slot_id=slot_id,
                rarity_id=rarity_id,
                equip_profile="UNIVERSAL",
                allowed_class_ids=ALL_CLASS_IDS,
                base_power_bps=base_power_bps(rarity_id),
                stat_split=UNIVERSAL_STAT_SPLITS[slot_id],
                effect_ids=EFFECT_LOADOUTS[slot_id][rarity_id],
                fixed_downside=downside_by_slot.get(slot_id, ""),
                acquisition_source=ACQUISITION_SOURCES[rarity_id],
            ))
    return tuple(catalog)


CATALOG = build_catalog()
CATALOG_BY_ID = {item.definition_id: item for item in CATALOG}


def item_for(class_id: str, slot_id: str, rarity_id: str) -> ItemDefinition:
    class_profile = CLASS_BY_ID[class_id]
    candidates = [
        item for item in CATALOG
        if item.slot_id == slot_id and item.rarity_id == rarity_id and class_id in item.allowed_class_ids
    ]
    if slot_id in ("WEAPON", "OFFHAND"):
        return next(item for item in candidates if item.equip_profile == class_id)
    if slot_id in ARMOR_SUFFIXES:
        return next(item for item in candidates if item.equip_profile == class_profile.default_armor_profile)
    return next(item for item in candidates if item.equip_profile == "UNIVERSAL")


def cumulative_enhancement_cost(slot_ids: tuple[str, ...], target_rank: int) -> dict[str, int]:
    totals = {"goldIndex": 0, "forgeUnits": 0, "rareUnits": 0, "ascensionCores": 0}
    for slot_id in slot_ids:
        for rank in range(1, target_rank + 1):
            cost = enhancement_cost(slot_id, rank)
            for key in totals:
                totals[key] += cost[key]
    return totals


def days_for_requirements(requirements: dict[str, int], rates: dict[str, float]) -> int:
    if not requirements:
        return 0
    return max(math.ceil(amount / rates[key]) for key, amount in requirements.items())


def acquisition_days(cohort: EconomyCohort, rarity_id: str, pieces: int) -> int:
    rates = {
        "field_marks": cohort.field_marks_per_day,
        "region_sigils": cohort.region_sigils_per_day,
        "elite_crests": cohort.elite_crests_per_day,
        "boss_cores": cohort.boss_cores_per_day,
        "relic_traces": cohort.relic_traces_per_day,
        "legend_seals": cohort.legend_seals_per_day,
    }
    requirements = {key: value * pieces for key, value in ACQUISITION_COSTS[rarity_id].items()}
    return days_for_requirements(requirements, rates)


def enhancement_days(cohort: EconomyCohort, slot_ids: tuple[str, ...], target_rank: int) -> int:
    costs = cumulative_enhancement_cost(slot_ids, target_rank)
    rates = {
        "goldIndex": cohort.gold_index_per_day,
        "forgeUnits": cohort.forge_units_per_day,
        "rareUnits": cohort.rare_units_per_day,
        "ascensionCores": cohort.ascension_cores_per_day,
    }
    return days_for_requirements(costs, rates)


def effect_totals(items: tuple[ItemDefinition, ...]) -> dict[str, int]:
    totals = {
        "ITEM_OFFENSE": 0,
        "ITEM_DEFENSE": 0,
        "PROFILE_HIT": 0,
        "STATUS_APPLY": 0,
        "ATTRITION": 0,
        "ITEM_TRIGGER": 0,
    }
    trigger_present = False
    for item in items:
        for effect_id in item.effect_ids:
            effect = EFFECT_BY_ID[effect_id]
            if effect.stack_group == "ITEM_TRIGGER":
                trigger_present = True
            elif effect.stack_group in totals:
                totals[effect.stack_group] += effect.scalar
    totals["ITEM_TRIGGER"] = 1 if trigger_present else 0
    return totals


def canonical_hash() -> str:
    payload = {
        "catalog": [asdict(item) for item in CATALOG],
        "effects": [asdict(item) for item in EFFECTS],
        "cohorts": [asdict(item) for item in COHORTS],
        "acquisitionCosts": ACQUISITION_COSTS,
        "enhancementBonus": ENHANCEMENT_BONUS_BPS,
        "transferGoldBps": 1_500,
        "transferConsumesSource": True,
        "itemSkillsAllowed": False,
    }
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def check_catalog_count_and_coverage():
    assert len(CATALOG) == 130
    assert len(CATALOG_BY_ID) == len(CATALOG)
    assert len({item.name_ko for item in CATALOG}) == len(CATALOG)
    counts = {slot_id: sum(item.slot_id == slot_id for item in CATALOG) for slot_id in SLOT_IDS}
    assert counts == {"WEAPON": 30, "OFFHAND": 30, "HEAD": 15, "BODY": 15, "HANDS": 15, "WAIST": 5, "FEET": 15, "ACCESSORY": 5}
    for rarity_id in RARITY_IDS:
        assert sum(item.rarity_id == rarity_id for item in CATALOG) == 26


def check_proficiency_and_complete_loadouts():
    for item in CATALOG:
        assert item.allowed_class_ids
        assert set(item.allowed_class_ids).issubset(ALL_CLASS_IDS)
        if item.slot_id in ("WEAPON", "OFFHAND"):
            assert item.allowed_class_ids == (item.equip_profile,)
        if item.slot_id in ARMOR_SUFFIXES:
            assert all(item.equip_profile in CLASS_BY_ID[class_id].armor_profiles for class_id in item.allowed_class_ids)
    for class_id in ALL_CLASS_IDS:
        for rarity_id in RARITY_IDS:
            loadout = tuple(item_for(class_id, slot_id, rarity_id) for slot_id in SLOT_IDS)
            assert len(loadout) == 8
            assert {item.slot_id for item in loadout} == set(SLOT_IDS)


def check_power_and_stat_budgets():
    for item in CATALOG:
        assert sum(value for _, value in item.stat_split) == 10_000
        assert len({stat for stat, _ in item.stat_split}) == len(item.stat_split)
        assert item.base_power_bps == base_power_bps(item.rarity_id)
        assert enhanced_slot_power(100, item.slot_id, item.rarity_id, 0) > 0
    primary_shares = {}
    for class_id in ALL_CLASS_IDS:
        weapon = item_for(class_id, "WEAPON", "COMMON")
        offhand = item_for(class_id, "OFFHAND", "COMMON")
        weapon_primary = dict(weapon.stat_split).get("PRIMARY_ATTACK", 0)
        offhand_primary = dict(offhand.stat_split).get("PRIMARY_ATTACK", 0)
        share = 2_600 * weapon_primary // 10_000 + 1_400 * offhand_primary // 10_000
        assert 2_600 <= share <= 3_230
        primary_shares[class_id] = share
    check_power_and_stat_budgets.primary_shares = primary_shares


def check_effect_budgets_and_no_item_skills():
    for effect in EFFECTS:
        assert effect.slot_id in SLOT_IDS
        assert not effect.creates_action and not effect.grants_skill
        assert effect.budget_cost in (300, 900)
    low_stat = EFFECT_BY_ID["aq.item.fx.head_low_stat"]
    assert low_stat.condition == "PRIMARY_RAW_STAT_LE_12"
    assert low_stat.scalar == 300 and low_stat.fixed_tradeoff == "CRIT_MINUS_200_BPS"
    for item in CATALOG:
        effects = tuple(EFFECT_BY_ID[effect_id] for effect_id in item.effect_ids)
        assert len(effects) == RARITY_EFFECT_COUNTS[item.rarity_id]
        assert sum(effect.budget_cost for effect in effects) == RARITY_EFFECT_BUDGET[item.rarity_id]
        assert all(effect.slot_id == item.slot_id for effect in effects)
        assert bool(item.fixed_downside) == (item.rarity_id == "LEGENDARY")


def check_all_mixed_rarity_loadouts_preserve_caps():
    representative_class = ALL_CLASS_IDS[0]
    worst = {"ITEM_OFFENSE": 0, "ITEM_DEFENSE": 0, "PROFILE_HIT": 0, "STATUS_APPLY": 0, "ATTRITION": 0, "ITEM_TRIGGER": 0}
    checked = 0
    for rarity_indexes in itertools.product(range(5), repeat=8):
        items = tuple(
            item_for(representative_class, slot_id, RARITY_IDS[rarity_index])
            for slot_id, rarity_index in zip(SLOT_IDS, rarity_indexes)
        )
        totals = effect_totals(items)
        worst["ITEM_OFFENSE"] = max(worst["ITEM_OFFENSE"], totals["ITEM_OFFENSE"])
        worst["ITEM_DEFENSE"] = min(worst["ITEM_DEFENSE"], totals["ITEM_DEFENSE"])
        worst["PROFILE_HIT"] = max(worst["PROFILE_HIT"], totals["PROFILE_HIT"])
        worst["STATUS_APPLY"] = max(worst["STATUS_APPLY"], totals["STATUS_APPLY"])
        worst["ATTRITION"] = max(worst["ATTRITION"], totals["ATTRITION"])
        worst["ITEM_TRIGGER"] = max(worst["ITEM_TRIGGER"], totals["ITEM_TRIGGER"])
        checked += 1
    assert checked == 5 ** 8
    assert worst == {"ITEM_OFFENSE": 800, "ITEM_DEFENSE": -300, "PROFILE_HIT": 1_200, "STATUS_APPLY": 600, "ATTRITION": 200, "ITEM_TRIGGER": 1}
    check_all_mixed_rarity_loadouts_preserve_caps.worst = worst
    check_all_mixed_rarity_loadouts_preserve_caps.represented = checked * len(CLASSES)


def check_acquisition_economy():
    results = {}
    for cohort in COHORTS:
        results[cohort.cohort_id] = {
            rarity_id: acquisition_days(cohort, rarity_id, 8) for rarity_id in RARITY_IDS
        }
    assert results["P10"] == {"COMMON": 0, "UNCOMMON": 8, "RARE": 64, "EPIC": 120, "LEGENDARY": 800}
    assert results["P50"] == {"COMMON": 0, "UNCOMMON": 4, "RARE": 32, "EPIC": 60, "LEGENDARY": 400}
    assert results["P90"] == {"COMMON": 0, "UNCOMMON": 3, "RARE": 22, "EPIC": 40, "LEGENDARY": 267}
    assert results["P10"]["LEGENDARY"] <= 1_095
    assert results["P90"]["LEGENDARY"] >= 240
    check_acquisition_economy.results = results


def check_enhancement_economy_and_transfer():
    results = {}
    for cohort in COHORTS:
        results[cohort.cohort_id] = {
            "weapon+5": enhancement_days(cohort, ("WEAPON",), 5),
            "weapon+10": enhancement_days(cohort, ("WEAPON",), 10),
            "weapon+15": enhancement_days(cohort, ("WEAPON",), 15),
            "full+5": enhancement_days(cohort, SLOT_IDS, 5),
            "full+10": enhancement_days(cohort, SLOT_IDS, 10),
            "full+15": enhancement_days(cohort, SLOT_IDS, 15),
        }
    assert results["P10"] == {"weapon+5": 2, "weapon+10": 17, "weapon+15": 67, "full+5": 13, "full+10": 134, "full+15": 534}
    assert results["P50"] == {"weapon+5": 2, "weapon+10": 9, "weapon+15": 34, "full+5": 8, "full+10": 67, "full+15": 267}
    assert results["P90"] == {"weapon+5": 1, "weapon+10": 5, "weapon+15": 20, "full+5": 5, "full+10": 40, "full+15": 160}
    assert results["P10"]["full+15"] <= 600
    assert results["P90"]["full+15"] >= 150
    for slot_id in SLOT_IDS:
        cumulative = cumulative_enhancement_cost((slot_id,), 15)["goldIndex"]
        transfer_gold = math.ceil(cumulative * 1_500 / 10_000)
        assert 0 < transfer_gold < cumulative
    check_enhancement_economy_and_transfer.results = results


def check_document_hash():
    assert DOCUMENT.exists()
    assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")


CHECKS = (
    ("CATALOG_EXPANDS_40_FAMILIES_TO_130_UNIQUE_CONCRETE_ITEMS", check_catalog_count_and_coverage),
    ("SIX_CLASSES_HAVE_LEGAL_8_SLOT_LOADOUTS_AT_ALL_FIVE_RARITIES", check_proficiency_and_complete_loadouts),
    ("ALL_BASE_POWER_AND_STAT_SPLITS_PRESERVE_APPROVED_BUDGETS", check_power_and_stat_budgets),
    ("RARITY_EFFECT_COUNTS_BUDGETS_DOWNSIDES_AND_NO_ITEM_SKILLS", check_effect_budgets_and_no_item_skills),
    ("ALL_2343750_MIXED_RARITY_CLASS_LOADOUTS_PRESERVE_EFFECT_CAPS", check_all_mixed_rarity_loadouts_preserve_caps),
    ("P10_P50_P90_FULL_SET_ACQUISITION_TARGETS", check_acquisition_economy),
    ("P10_P50_P90_ENHANCEMENT_AND_SAFE_TRANSFER_TARGETS", check_enhancement_economy_and_transfer),
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true", help="also require the document's canonical hash")
    args = parser.parse_args()
    checks = list(CHECKS)
    if args.pd:
        checks.append(("DOCUMENT_CONTAINS_CANONICAL_CATALOG_ECONOMY_HASH", check_document_hash))
    failures = []
    for name, check in checks:
        try:
            check()
        except Exception as exc:  # pragma: no cover - CLI audit output
            failures.append((name, repr(exc)))
    mode = "PD" if args.pd else "BALANCE"
    status = "PASS" if not failures else "FAIL"
    reference_power = {
        slot_id: enhanced_slot_power(100, slot_id, "COMMON", 0) for slot_id in SLOT_IDS
    }
    print(f"EQUIPMENT_CATALOG_ECONOMY_VERTICAL_SLICE_V0_1: {status} ({len(checks) - len(failures)}/{len(checks)}) mode={mode}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  catalog={len(CATALOG)} families=40 representedMixedLoadouts={6 * 5 ** 8}")
    print(f"  referenceC100BasePower={reference_power}")
    if hasattr(check_power_and_stat_budgets, "primary_shares"):
        print(f"  classPrimaryOffenseShares={check_power_and_stat_budgets.primary_shares}")
    if hasattr(check_all_mixed_rarity_loadouts_preserve_caps, "worst"):
        print(f"  effectWorst={check_all_mixed_rarity_loadouts_preserve_caps.worst}")
    if hasattr(check_acquisition_economy, "results"):
        print(f"  acquisitionFullSetDays={check_acquisition_economy.results}")
    if hasattr(check_enhancement_economy_and_transfer, "results"):
        print(f"  enhancementDays={check_enhancement_economy_and_transfer.results}")
    for name, _ in checks:
        failure = next((detail for failed_name, detail in failures if failed_name == name), None)
        print(f"  {'FAIL' if failure else 'PASS'} {name}{' ' + failure if failure else ''}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
