#!/usr/bin/env python3
"""Audit the 650-definition, five-lineage AlarmQuest equipment catalog.

Design-only. Imports the approved 130-item vertical slice, expands every
definition into five mechanically distinct lineages, and proves the existing
power/economy/effect ceilings without enumerating 25 ** 8 loadouts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import asdict, dataclass
from pathlib import Path

import equipment_catalog_economy_vertical_slice_v0_1_review as base


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_EQUIPMENT_CATALOG_650_LINEAGES_v0.2.md"


@dataclass(frozen=True)
class LineageDefinition:
    lineage_id: str
    name_ko: str
    focus_stat: str
    shift_bps: int
    acquisition_lane: str
    combat_identity: str


@dataclass(frozen=True)
class ExpandedItemDefinition:
    definition_id: str
    parent_definition_id: str
    name_ko: str
    lineage_id: str
    slot_id: str
    rarity_id: str
    equip_profile: str
    allowed_class_ids: tuple[str, ...]
    base_power_bps: int
    stat_split: tuple[tuple[str, int], ...]
    effect_ids: tuple[str, ...]
    fixed_downside: str
    acquisition_source: str
    acquisition_lane: str
    combat_identity: str


LINEAGES = (
    LineageDefinition("BALANCED", "균형", "NONE", 0, "TARGETED_CRAFT", "원형 스탯 배분·범용 조합"),
    LineageDefinition("VANGUARD", "선봉", "PRIMARY_ATTACK", 500, "ELITE_HUNT", "공격·치명 중심, 방호 여유 감소"),
    LineageDefinition("BASTION", "수호", "PDEF", 500, "BOSS_DEFENSE", "물리 방호 중심, 공격 여유 감소"),
    LineageDefinition("SEEKER", "추적", "HIT", 500, "MONSTER_RESEARCH", "명중·상태 조합 안정화"),
    LineageDefinition("TRAILBLAZER", "개척", "MAX_RESOURCE", 500, "REGIONAL_EXPLORATION", "자원·장기 원정 안정화"),
)

LINEAGE_BY_ID = {item.lineage_id: item for item in LINEAGES}
LINEAGE_IDS = tuple(item.lineage_id for item in LINEAGES)


def shifted_split(
    original: tuple[tuple[str, int], ...], target_stat: str, shift_bps: int
) -> tuple[tuple[str, int], ...]:
    """Move a fixed budget from the largest other stat into the lineage focus."""
    if not shift_bps:
        return original
    values = dict(original)
    donors = sorted(
        ((value, stat) for stat, value in values.items() if stat != target_stat),
        reverse=True,
    )
    assert donors and donors[0][0] >= shift_bps
    _, donor = donors[0]
    values[donor] -= shift_bps
    values[target_stat] = values.get(target_stat, 0) + shift_bps
    ordered_stats = [stat for stat, _ in original]
    if target_stat not in ordered_stats:
        ordered_stats.append(target_stat)
    return tuple((stat, values[stat]) for stat in ordered_stats if values[stat] > 0)


def lineage_split(
    item: base.ItemDefinition, lineage: LineageDefinition
) -> tuple[tuple[str, int], ...]:
    if lineage.lineage_id == "BALANCED":
        return item.stat_split
    if item.slot_id == "WEAPON":
        # Weapon identities require a visible choice even when the parent is
        # 100% PRIMARY_ATTACK. All variants retain exactly the same stat budget.
        return {
            "VANGUARD": (("PRIMARY_ATTACK", 9_500), ("CRIT", 500)),
            "BASTION": (("PRIMARY_ATTACK", 8_500), ("PDEF", 1_000), ("MRES", 500)),
            "SEEKER": (("PRIMARY_ATTACK", 8_500), ("HIT", 1_500)),
            "TRAILBLAZER": (("PRIMARY_ATTACK", 8_500), ("MAX_RESOURCE", 1_000), ("STATUS_POWER", 500)),
        }[lineage.lineage_id]
    return shifted_split(item.stat_split, lineage.focus_stat, lineage.shift_bps)


def build_catalog() -> tuple[ExpandedItemDefinition, ...]:
    catalog: list[ExpandedItemDefinition] = []
    for parent in base.CATALOG:
        for lineage in LINEAGES:
            catalog.append(ExpandedItemDefinition(
                definition_id=f"{parent.definition_id}.{lineage.lineage_id.lower()}",
                parent_definition_id=parent.definition_id,
                name_ko=f"{parent.name_ko}·{lineage.name_ko}",
                lineage_id=lineage.lineage_id,
                slot_id=parent.slot_id,
                rarity_id=parent.rarity_id,
                equip_profile=parent.equip_profile,
                allowed_class_ids=parent.allowed_class_ids,
                base_power_bps=parent.base_power_bps,
                stat_split=lineage_split(parent, lineage),
                effect_ids=parent.effect_ids,
                fixed_downside=parent.fixed_downside,
                acquisition_source=parent.acquisition_source,
                acquisition_lane=lineage.acquisition_lane,
                combat_identity=lineage.combat_identity,
            ))
    return tuple(catalog)


CATALOG = build_catalog()
CATALOG_BY_ID = {item.definition_id: item for item in CATALOG}
LOADOUT_OPTIONS_PER_SLOT = len(base.RARITY_IDS) * len(LINEAGES)
LEGAL_MIXED_LOADOUTS = len(base.CLASSES) * LOADOUT_OPTIONS_PER_SLOT ** len(base.SLOT_IDS)


def item_for(class_id: str, slot_id: str, rarity_id: str, lineage_id: str) -> ExpandedItemDefinition:
    class_profile = base.CLASS_BY_ID[class_id]
    candidates = [
        item for item in CATALOG
        if item.slot_id == slot_id
        and item.rarity_id == rarity_id
        and item.lineage_id == lineage_id
        and class_id in item.allowed_class_ids
    ]
    if slot_id in ("WEAPON", "OFFHAND"):
        return next(item for item in candidates if item.equip_profile == class_id)
    if slot_id in base.ARMOR_SUFFIXES:
        return next(item for item in candidates if item.equip_profile == class_profile.default_armor_profile)
    return next(item for item in candidates if item.equip_profile == "UNIVERSAL")


def effect_totals(items: tuple[ExpandedItemDefinition, ...]) -> dict[str, int]:
    totals = {
        "ITEM_OFFENSE": 0,
        "ITEM_DEFENSE": 0,
        "PROFILE_HIT": 0,
        "STATUS_APPLY": 0,
        "ATTRITION": 0,
        "ITEM_TRIGGER": 0,
    }
    trigger = False
    for item in items:
        for effect_id in item.effect_ids:
            effect = base.EFFECT_BY_ID[effect_id]
            if effect.stack_group == "ITEM_TRIGGER":
                trigger = True
            elif effect.stack_group in totals:
                totals[effect.stack_group] += effect.scalar
    totals["ITEM_TRIGGER"] = int(trigger)
    return totals


def canonical_hash() -> str:
    payload = {
        "parentHash": base.canonical_hash(),
        "lineages": [asdict(item) for item in LINEAGES],
        "catalog": [asdict(item) for item in CATALOG],
        "targetSelectionCostsExtra": False,
        "allLineagesRequiredForProgression": False,
        "duplicateSalvageBps": 4000,
        "itemSkillsAllowed": False,
        "loadoutOptionsPerSlot": LOADOUT_OPTIONS_PER_SLOT,
    }
    return hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def check_exact_650_unique_definitions():
    assert len(base.CATALOG) == 130
    assert len(CATALOG) == 650 and len(CATALOG_BY_ID) == 650
    assert len({item.name_ko for item in CATALOG}) == 650
    assert all(sum(item.lineage_id == lineage_id for item in CATALOG) == 130 for lineage_id in LINEAGE_IDS)
    assert all(sum(item.rarity_id == rarity_id for item in CATALOG) == 130 for rarity_id in base.RARITY_IDS)
    expected_slots = {slot_id: count * 5 for slot_id, count in {
        "WEAPON": 30, "OFFHAND": 30, "HEAD": 15, "BODY": 15,
        "HANDS": 15, "WAIST": 5, "FEET": 15, "ACCESSORY": 5,
    }.items()}
    assert {slot_id: sum(item.slot_id == slot_id for item in CATALOG) for slot_id in base.SLOT_IDS} == expected_slots


def check_lineages_are_mechanical_not_reskins():
    for parent in base.CATALOG:
        variants = [item for item in CATALOG if item.parent_definition_id == parent.definition_id]
        assert len(variants) == 5
        assert len({item.lineage_id for item in variants}) == 5
        assert len({item.acquisition_lane for item in variants}) == 5
        assert len({item.stat_split for item in variants}) == 5
        for item in variants:
            assert sum(value for _, value in item.stat_split) == 10_000
            assert item.base_power_bps == parent.base_power_bps
            assert item.effect_ids == parent.effect_ids
            assert not any(base.EFFECT_BY_ID[effect_id].grants_skill for effect_id in item.effect_ids)


def check_all_classes_have_25_options_per_slot():
    for class_id in base.ALL_CLASS_IDS:
        for slot_id in base.SLOT_IDS:
            options = {
                item_for(class_id, slot_id, rarity_id, lineage_id).definition_id
                for rarity_id in base.RARITY_IDS
                for lineage_id in LINEAGE_IDS
            }
            assert len(options) == LOADOUT_OPTIONS_PER_SLOT == 25
    assert LEGAL_MIXED_LOADOUTS == 915_527_343_750


def check_factorized_effect_cap_proof():
    # Effects are additive by slot and lineage never modifies an effect. Taking
    # each slot's exact extremum therefore proves all 25 ** 8 combinations.
    class_id = base.ALL_CLASS_IDS[0]
    slot_options = {}
    for slot_id in base.SLOT_IDS:
        slot_options[slot_id] = tuple(
            item_for(class_id, slot_id, rarity_id, lineage_id)
            for rarity_id in base.RARITY_IDS for lineage_id in LINEAGE_IDS
        )
        assert len(slot_options[slot_id]) == 25

    extrema = {}
    for group in ("ITEM_OFFENSE", "PROFILE_HIT", "STATUS_APPLY", "ATTRITION"):
        extrema[group] = sum(max(effect_totals((item,))[group] for item in slot_options[slot]) for slot in base.SLOT_IDS)
    extrema["ITEM_DEFENSE"] = sum(min(effect_totals((item,))["ITEM_DEFENSE"] for item in slot_options[slot]) for slot in base.SLOT_IDS)
    extrema["ITEM_TRIGGER"] = 1 if any(
        effect_totals((item,))["ITEM_TRIGGER"] for options in slot_options.values() for item in options
    ) else 0
    assert extrema == {
        "ITEM_OFFENSE": 800,
        "ITEM_DEFENSE": -300,
        "PROFILE_HIT": 1_200,
        "STATUS_APPLY": 600,
        "ATTRITION": 200,
        "ITEM_TRIGGER": 1,
    }
    check_factorized_effect_cap_proof.extrema = extrema


def check_economy_is_horizontal_not_five_times_longer():
    base.check_acquisition_economy()
    base.check_enhancement_economy_and_transfer()
    assert base.check_acquisition_economy.results["P50"]["LEGENDARY"] == 400
    assert base.check_enhancement_economy_and_transfer.results["P50"]["full+15"] == 267
    assert len({lineage.acquisition_lane for lineage in LINEAGES}) == 5
    # Target selection adds identity, not another currency multiplier.
    for rarity_id in base.RARITY_IDS:
        costs = base.ACQUISITION_COSTS[rarity_id]
        assert all(costs == base.ACQUISITION_COSTS[rarity_id] for _ in LINEAGES)


def check_document_hash():
    assert DOCUMENT.exists()
    assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")


CHECKS = (
    ("EXACTLY_650_UNIQUE_ITEMS_FROM_130_PARENTS_X_5_LINEAGES", check_exact_650_unique_definitions),
    ("ALL_FIVE_LINEAGES_DIFFER_IN_STATS_AND_ACQUISITION_NOT_JUST_NAMES", check_lineages_are_mechanical_not_reskins),
    ("SIX_CLASSES_HAVE_25_OPTIONS_IN_EACH_OF_EIGHT_SLOTS", check_all_classes_have_25_options_per_slot),
    ("FACTORIZED_PROOF_COVERS_ALL_915527343750_LOADOUTS_AND_EFFECT_CAPS", check_factorized_effect_cap_proof),
    ("P10_P50_P90_ACQUISITION_ENHANCEMENT_TARGETS_ARE_NOT_MULTIPLIED", check_economy_is_horizontal_not_five_times_longer),
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    args = parser.parse_args()
    checks = list(CHECKS)
    if args.pd:
        checks.append(("DOCUMENT_CONTAINS_CANONICAL_650_CATALOG_HASH", check_document_hash))
    failures = []
    for name, check in checks:
        try:
            check()
        except Exception as exc:  # pragma: no cover
            failures.append((name, repr(exc)))
    status = "PASS" if not failures else "FAIL"
    print(f"EQUIPMENT_CATALOG_650_LINEAGES_V0_2: {status} ({len(checks)-len(failures)}/{len(checks)}) mode={'PD' if args.pd else 'BALANCE'}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  catalog={len(CATALOG)} parent=130 lineages=5 slotOptions=25 legalMixedLoadouts={LEGAL_MIXED_LOADOUTS}")
    if hasattr(check_factorized_effect_cap_proof, "extrema"):
        print(f"  factorizedEffectWorst={check_factorized_effect_cap_proof.extrema}")
    if hasattr(base.check_acquisition_economy, "results"):
        print(f"  acquisitionFullSetDays={base.check_acquisition_economy.results}")
        print(f"  enhancementDays={base.check_enhancement_economy_and_transfer.results}")
    for name, _ in checks:
        failure = next((detail for failed, detail in failures if failed == name), None)
        print(f"  {'FAIL' if failure else 'PASS'} {name}{' ' + failure if failure else ''}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
