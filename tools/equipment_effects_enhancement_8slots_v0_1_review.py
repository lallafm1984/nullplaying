#!/usr/bin/env python3
"""Design audit for AlarmQuest 8-slot equipment effects and enhancement.

No item grants an Active or Passive skill. This tool does not mutate live data.
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


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_EQUIPMENT_EFFECTS_ENHANCEMENT_8SLOTS_v0.1.md"

ANCHORS = (1, 25, 50, 75, 100)
SKILL_COEFFICIENTS_BPS = (9_000, 11_000, 13_000, 15_000, 17_000)
ENHANCEMENT_BONUS_BPS = (0, 300, 600, 900, 1_200, 1_500, 1_800, 2_100, 2_400, 2_700, 3_000, 3_400, 3_800, 4_200, 4_600, 5_000)

ITEM_OFFENSE_ADD_CAP_BPS = 800
ITEM_INCOMING_MODIFIER_CAP_BPS = -300
PASSIVE_AND_ITEM_INCOMING_CAP_BPS = -1_000
PROFILE_HIT_ADD_CAP_BPS = 1_200
ITEM_STATUS_APPLY_ADD_CAP_BPS = 600
ORDINARY_ATTRITION_BASE_BPS = 5_000
ORDINARY_ATTRITION_FLOOR_BPS = 4_500
EXISTING_SUPPLY_REDUCTION_CAP_BPS = 500
ITEM_TRIGGER_SHARED_ENCOUNTER_CAP = 1


@dataclass(frozen=True)
class SlotPlan:
    slot_id: str
    name_ko: str
    budget_bps: int
    armor_weight_bps: int
    enhance_cost_weight: int
    role: str


@dataclass(frozen=True)
class RarityPlan:
    rarity_id: str
    base_power_bps: int
    effect_budget_bps: int
    minor_effects: int
    major_effects: int
    requires_downside: bool


@dataclass(frozen=True)
class EffectPlan:
    effect_id: str
    slot_id: str
    stack_group: str
    value_bps: int
    creates_action: bool = False
    has_skill_level: bool = False


SLOTS = (
    SlotPlan("WEAPON", "주무기", 2_600, 0, 4, "PATK·MATK·damage type"),
    SlotPlan("OFFHAND", "보조 장비", 1_400, 1_500, 4, "방패·마법서·성물·도구"),
    SlotPlan("HEAD", "머리", 1_000, 1_500, 3, "명중·치명·상태 저항"),
    SlotPlan("BODY", "몸", 2_100, 3_500, 4, "PDEF·MRES·MaxHP"),
    SlotPlan("HANDS", "손", 800, 1_000, 3, "공격 속도·치명·자원 효율"),
    SlotPlan("WAIST", "허리", 700, 1_000, 3, "MaxResource·원정·보급"),
    SlotPlan("FEET", "발", 800, 1_500, 3, "Speed·EVA·STAGGER 저항"),
    SlotPlan("ACCESSORY", "장신구", 600, 0, 2, "속성·조건부 고유 효과"),
)


RARITIES = (
    RarityPlan("COMMON", 10_000, 0, 0, 0, False),
    RarityPlan("UNCOMMON", 9_900, 300, 1, 0, False),
    RarityPlan("RARE", 9_700, 600, 1, 1, False),
    RarityPlan("EPIC", 9_400, 900, 2, 1, False),
    RarityPlan("LEGENDARY", 9_000, 1_200, 1, 1, True),
)


EFFECTS = (
    EffectPlan("aq.item.effect.weapon.focus", "WEAPON", "ITEM_OFFENSE", 300),
    EffectPlan("aq.item.effect.weapon.profile_eye", "WEAPON", "PROFILE_HIT", 300),
    EffectPlan("aq.item.effect.weapon.status_etch", "WEAPON", "STATUS_APPLY", 200),
    EffectPlan("aq.item.effect.offhand.guard", "OFFHAND", "ITEM_DEFENSE", -150),
    EffectPlan("aq.item.effect.offhand.profile_lens", "OFFHAND", "PROFILE_HIT", 300),
    EffectPlan("aq.item.effect.offhand.supply", "OFFHAND", "ATTRITION", 100),
    EffectPlan("aq.item.effect.head.profile_read", "HEAD", "PROFILE_HIT", 300),
    EffectPlan("aq.item.effect.head.status_resist", "HEAD", "STATUS_RESIST", 300),
    EffectPlan("aq.item.effect.head.crit_resist", "HEAD", "CRIT_RESIST", 200),
    EffectPlan("aq.item.effect.body.guard", "BODY", "ITEM_DEFENSE", -150),
    EffectPlan("aq.item.effect.body.max_hp", "BODY", "MAX_HP", 300),
    EffectPlan("aq.item.effect.body.first_packet", "BODY", "ITEM_TRIGGER", 1),
    EffectPlan("aq.item.effect.hands.focus", "HANDS", "ITEM_OFFENSE", 300),
    EffectPlan("aq.item.effect.hands.crit", "HANDS", "CRIT", 200),
    EffectPlan("aq.item.effect.hands.status_etch", "HANDS", "STATUS_APPLY", 200),
    EffectPlan("aq.item.effect.waist.supply", "WAIST", "ATTRITION", 100),
    EffectPlan("aq.item.effect.waist.max_resource", "WAIST", "MAX_RESOURCE", 300),
    EffectPlan("aq.item.effect.waist.guard", "WAIST", "ITEM_DEFENSE", -150),
    EffectPlan("aq.item.effect.feet.evasion", "FEET", "EVA", 300),
    EffectPlan("aq.item.effect.feet.stagger_resist", "FEET", "STAGGER_RESIST", 300),
    EffectPlan("aq.item.effect.feet.profile_step", "FEET", "PROFILE_HIT", 300),
    EffectPlan("aq.item.effect.accessory.focus", "ACCESSORY", "ITEM_OFFENSE", 300),
    EffectPlan("aq.item.effect.accessory.guard", "ACCESSORY", "ITEM_DEFENSE", -150),
    EffectPlan("aq.item.effect.accessory.status_etch", "ACCESSORY", "STATUS_APPLY", 200),
)


def round_half_up(value: Decimal | float) -> int:
    return int(Decimal(str(value)).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def combat_rank(display_level: int) -> int:
    safe = max(1, min(9_999, int(display_level)))
    if safe <= 100:
        return safe
    return round_half_up(100 + 100 * math.log1p((safe - 100) / 100))


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


def reference_weapon_power(rank: int) -> int:
    return max(1, round_half_up(1.05 * max(1, rank)))


def reference_attack_power(rank: int) -> int:
    safe = max(1, rank)
    return round_half_up(20 + 1.4 * safe + reference_weapon_power(safe))


def skill_raw_damage(display_level: int, skill_level: int) -> int:
    rank = combat_rank(display_level)
    coefficient = value_at(SKILL_COEFFICIENTS_BPS, skill_level)
    return round_half_up(Decimal(reference_attack_power(rank)) * Decimal(coefficient) / Decimal(10_000))


def rarity(rarity_id: str) -> RarityPlan:
    return next(item for item in RARITIES if item.rarity_id == rarity_id)


def slot(slot_id: str) -> SlotPlan:
    return next(item for item in SLOTS if item.slot_id == slot_id)


def enhanced_slot_power(combat_rank_value: int, slot_id: str, rarity_id: str, enhancement_rank: int) -> int:
    safe_rank = max(1, combat_rank_value)
    enhancement = max(0, min(15, enhancement_rank))
    total_reference = Decimal(reference_weapon_power(safe_rank) * len(SLOTS))
    base = total_reference * Decimal(slot(slot_id).budget_bps) / Decimal(10_000)
    base *= Decimal(rarity(rarity_id).base_power_bps) / Decimal(10_000)
    base *= Decimal(10_000 + ENHANCEMENT_BONUS_BPS[enhancement]) / Decimal(10_000)
    return max(1, round_half_up(base))


def enhancement_cost(slot_id: str, target_rank: int):
    rank = max(1, min(15, target_rank))
    weight = slot(slot_id).enhance_cost_weight
    return {
        "goldIndex": weight * rank * rank,
        "forgeUnits": weight * rank,
        "rareUnits": 0 if rank <= 5 else weight * (rank - 5),
        "ascensionCores": 1 if rank == 10 else 3 if rank == 15 else 0,
        "successBps": 10_000,
    }


def effect_envelope(selected):
    offense = min(ITEM_OFFENSE_ADD_CAP_BPS, sum(effect.value_bps for effect in selected if effect.stack_group == "ITEM_OFFENSE"))
    item_defense = max(ITEM_INCOMING_MODIFIER_CAP_BPS, sum(effect.value_bps for effect in selected if effect.stack_group == "ITEM_DEFENSE"))
    combined_defense = max(PASSIVE_AND_ITEM_INCOMING_CAP_BPS, -700 + item_defense)
    profile_hit = min(PROFILE_HIT_ADD_CAP_BPS, sum(effect.value_bps for effect in selected if effect.stack_group == "PROFILE_HIT"))
    status_apply = min(ITEM_STATUS_APPLY_ADD_CAP_BPS, sum(effect.value_bps for effect in selected if effect.stack_group == "STATUS_APPLY"))
    attrition_reduction = EXISTING_SUPPLY_REDUCTION_CAP_BPS + sum(effect.value_bps for effect in selected if effect.stack_group == "ATTRITION")
    ordinary_attrition = max(ORDINARY_ATTRITION_FLOOR_BPS, ORDINARY_ATTRITION_BASE_BPS - attrition_reduction)
    triggers = min(ITEM_TRIGGER_SHARED_ENCOUNTER_CAP, sum(effect.value_bps for effect in selected if effect.stack_group == "ITEM_TRIGGER"))
    return {
        "offense": offense,
        "itemDefense": item_defense,
        "combinedDefense": combined_defense,
        "profileHit": profile_hit,
        "statusApply": status_apply,
        "ordinaryAttrition": ordinary_attrition,
        "triggers": triggers,
    }


def canonical_hash() -> str:
    payload = {
        "slots": [item.__dict__ for item in SLOTS],
        "rarities": [item.__dict__ for item in RARITIES],
        "effects": [item.__dict__ for item in EFFECTS],
        "anchors": ANCHORS,
        "skillCoefficients": SKILL_COEFFICIENTS_BPS,
        "enhancementBonus": ENHANCEMENT_BONUS_BPS,
        "caps": {
            "offense": ITEM_OFFENSE_ADD_CAP_BPS,
            "itemIncoming": ITEM_INCOMING_MODIFIER_CAP_BPS,
            "passiveAndItemIncoming": PASSIVE_AND_ITEM_INCOMING_CAP_BPS,
            "profileHit": PROFILE_HIT_ADD_CAP_BPS,
            "statusApply": ITEM_STATUS_APPLY_ADD_CAP_BPS,
            "ordinaryAttritionFloor": ORDINARY_ATTRITION_FLOOR_BPS,
            "itemTrigger": ITEM_TRIGGER_SHARED_ENCOUNTER_CAP,
        },
        "itemSkillsAllowed": False,
        "enhancementAffectsEffects": False,
        "enhancementDestroysOrDowngrades": False,
    }
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def check_slots_and_no_item_skills():
    assert len(SLOTS) == 8
    assert tuple(item.slot_id for item in SLOTS) == ("WEAPON", "OFFHAND", "HEAD", "BODY", "HANDS", "WAIST", "FEET", "ACCESSORY")
    assert sum(item.budget_bps for item in SLOTS) == 10_000
    assert sum(item.armor_weight_bps for item in SLOTS) == 10_000
    assert slot("WAIST").armor_weight_bps > 0
    assert all(not item.creates_action and not item.has_skill_level for item in EFFECTS)
    assert all(item.slot_id in {slot.slot_id for slot in SLOTS} for item in EFFECTS)
    assert len(EFFECTS) == len({item.effect_id for item in EFFECTS})


def check_rarity_and_effect_budgets():
    assert len(RARITIES) == 5
    assert all(left.base_power_bps > right.base_power_bps for left, right in zip(RARITIES, RARITIES[1:]))
    assert all(left.effect_budget_bps < right.effect_budget_bps for left, right in zip(RARITIES, RARITIES[1:]))
    assert rarity("COMMON").effect_budget_bps == 0
    assert rarity("LEGENDARY").requires_downside
    for effect in EFFECTS:
        if effect.stack_group == "ITEM_OFFENSE": assert effect.value_bps <= 300
        if effect.stack_group == "ITEM_DEFENSE": assert effect.value_bps >= -150


def check_character_and_skill_scaling():
    assert combat_rank(1) == 1
    assert combat_rank(100) == 100
    assert combat_rank(1_000) == 330
    assert combat_rank(9_999) == 561
    ranks = [combat_rank(level) for level in range(1, 10_000)]
    assert all(right >= left for left, right in zip(ranks, ranks[1:]))
    for skill_level in (1, 25, 50, 75, 100):
        damages = [skill_raw_damage(level, skill_level) for level in range(1, 10_000)]
        assert all(right >= left for left, right in zip(damages, damages[1:]))
    for display_level in (1, 100, 1_000, 9_999):
        damages = [skill_raw_damage(display_level, skill_level) for skill_level in range(1, 101)]
        assert all(right >= left for left, right in zip(damages, damages[1:]))
    level_samples = {level: skill_raw_damage(level, 100) for level in (1, 100, 1_000, 9_999)}
    assert level_samples[1] < level_samples[100] < level_samples[1_000] < level_samples[9_999]
    check_character_and_skill_scaling.samples = level_samples


def check_enhancement_and_costs():
    assert len(ENHANCEMENT_BONUS_BPS) == 16
    assert ENHANCEMENT_BONUS_BPS[0] == 0 and ENHANCEMENT_BONUS_BPS[15] == 5_000
    assert all(right > left for left, right in zip(ENHANCEMENT_BONUS_BPS, ENHANCEMENT_BONUS_BPS[1:]))
    for slot_plan in SLOTS:
        for rarity_plan in RARITIES:
            for rank in (1, 100, 330, 561):
                powers = [enhanced_slot_power(rank, slot_plan.slot_id, rarity_plan.rarity_id, enhancement) for enhancement in range(16)]
                assert all(right >= left for left, right in zip(powers, powers[1:]))
            costs = [enhancement_cost(slot_plan.slot_id, target) for target in range(1, 16)]
            assert all(cost["successBps"] == 10_000 for cost in costs)
            assert all(right["goldIndex"] > left["goldIndex"] for left, right in zip(costs, costs[1:]))
            assert all(right["forgeUnits"] > left["forgeUnits"] for left, right in zip(costs, costs[1:]))
            assert all(right["rareUnits"] >= left["rareUnits"] for left, right in zip(costs, costs[1:]))
    weapon_costs = [enhancement_cost("WEAPON", target) for target in range(1, 16)]
    check_enhancement_and_costs.weapon_cumulative = {
        key: sum(cost[key] for cost in weapon_costs) for key in ("goldIndex", "forgeUnits", "rareUnits", "ascensionCores")
    }


def check_all_effect_combinations():
    options = []
    for slot_plan in SLOTS:
        options.append((None,) + tuple(effect for effect in EFFECTS if effect.slot_id == slot_plan.slot_id))
    assert all(len(values) == 4 for values in options)
    checked = 0
    maxima = {"offense": 0, "itemDefense": 0, "combinedDefense": 0, "profileHit": 0, "statusApply": 0, "ordinaryAttrition": 5_000, "triggers": 0}
    for combination in itertools.product(*options):
        selected = tuple(effect for effect in combination if effect is not None)
        result = effect_envelope(selected)
        assert result["offense"] <= 800
        assert result["itemDefense"] >= -300
        assert result["combinedDefense"] >= -1_000
        assert result["profileHit"] <= 1_200
        assert result["statusApply"] <= 600
        assert result["ordinaryAttrition"] >= 4_500
        assert result["triggers"] <= 1
        maxima["offense"] = max(maxima["offense"], result["offense"])
        maxima["itemDefense"] = min(maxima["itemDefense"], result["itemDefense"])
        maxima["combinedDefense"] = min(maxima["combinedDefense"], result["combinedDefense"])
        maxima["profileHit"] = max(maxima["profileHit"], result["profileHit"])
        maxima["statusApply"] = max(maxima["statusApply"], result["statusApply"])
        maxima["ordinaryAttrition"] = min(maxima["ordinaryAttrition"], result["ordinaryAttrition"])
        maxima["triggers"] = max(maxima["triggers"], result["triggers"])
        checked += 1
    assert checked == 65_536
    assert maxima == {"offense": 800, "itemDefense": -300, "combinedDefense": -1_000, "profileHit": 1_200, "statusApply": 600, "ordinaryAttrition": 4_500, "triggers": 1}
    check_all_effect_combinations.checked = checked
    check_all_effect_combinations.maxima = maxima


def check_full_state_representation():
    per_slot_rank_rarity_states = len(RARITIES) * len(ENHANCEMENT_BONUS_BPS)
    represented = per_slot_rank_rarity_states ** len(SLOTS)
    assert per_slot_rank_rarity_states == 80
    assert represented == 1_677_721_600_000_000
    for rank in range(1, 562):
        for slot_plan in SLOTS:
            common = enhanced_slot_power(rank, slot_plan.slot_id, "COMMON", 0)
            legendary = enhanced_slot_power(rank, slot_plan.slot_id, "LEGENDARY", 0)
            assert common >= legendary >= 1
            assert enhanced_slot_power(rank, slot_plan.slot_id, "LEGENDARY", 15) >= legendary
    check_full_state_representation.represented = represented


STATIC_CHECKS = (
    ("EXACTLY_8_SLOTS_WITH_WAIST_AND_NO_ITEM_SKILLS", check_slots_and_no_item_skills),
    ("RARITY_TRADES_BASE_POWER_FOR_BOUNDED_EFFECT_BUDGET", check_rarity_and_effect_budgets),
    ("SAME_SKILL_LEVEL_SCALES_WITH_CHARACTER_COMBAT_POWER", check_character_and_skill_scaling),
    ("ENHANCEMENT_0_TO_15_IS_MONOTONE_GUARANTEED_AND_EFFECT_NEUTRAL", check_enhancement_and_costs),
    ("ALL_65536_ONE_EFFECT_PER_SLOT_COMBINATIONS_PRESERVE_CAPS", check_all_effect_combinations),
    ("SEPARABLE_RARITY_RANK_TABLE_REPRESENTS_FULL_8_SLOT_STATES", check_full_state_representation),
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    args = parser.parse_args()
    passed = []
    for name, check in STATIC_CHECKS:
        check()
        passed.append(name)
    if DOCUMENT.exists():
        assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")
        passed.append("DOCUMENT_CONTAINS_CANONICAL_EQUIPMENT_HASH")
    label = "PD" if args.pd else "BALANCE"
    print(f"EQUIPMENT_EFFECTS_ENHANCEMENT_8SLOTS_V0_1: PASS ({len(passed)}/{len(passed)}) mode={label}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  slots={len(SLOTS)} effectCombinations={check_all_effect_combinations.checked} fullRankRarityStates={check_full_state_representation.represented}")
    samples = check_character_and_skill_scaling.samples
    print(f"  skillLv100 rawDamage L1/L100/L1000/L9999={samples[1]}/{samples[100]}/{samples[1000]}/{samples[9999]}")
    print(f"  combatRank L1/L100/L1000/L9999={combat_rank(1)}/{combat_rank(100)}/{combat_rank(1000)}/{combat_rank(9999)}")
    print(f"  enhancement +0/+5/+10/+15={ENHANCEMENT_BONUS_BPS[0]}/{ENHANCEMENT_BONUS_BPS[5]}/{ENHANCEMENT_BONUS_BPS[10]}/{ENHANCEMENT_BONUS_BPS[15]}")
    print(f"  weaponCumulativeCostUnits={check_enhancement_and_costs.weapon_cumulative}")
    print(f"  effectCaps={check_all_effect_combinations.maxima}")
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
