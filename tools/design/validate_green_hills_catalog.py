#!/usr/bin/env python3
"""Validate the standalone Green Hills modifier catalog."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


DEFAULT_CATALOG = Path("tools/design/data/green_hills_catalog_v0_1.json")
DEFAULT_OUTPUT = Path("artifacts/design/green-hills-catalog-validation-v0.1.md")
EXPECTED_COUNTS = {
    "monsterScene": 24,
    "monsterMechanical": 6,
    "equipmentStat": 12,
    "equipmentProgress": 6,
    "equipmentTrade": 6,
    "loot": 16,
    "skill": 8,
    "curiosity": 12,
}
EXPECTED_PRIMARY = {
    "warrior": "STR",
    "paladin": "STR",
    "rogue": "DEX",
    "ranger": "DEX",
    "mage": "INT",
    "cleric": "WIS",
}


def load_catalog(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def modifier_index(catalog: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {
        modifier["id"]: modifier
        for group in catalog["modifiers"].values()
        for modifier in group
    }


def base_index(catalog: dict[str, Any], key: str) -> dict[str, dict[str, Any]]:
    return {entry["id"]: entry for entry in catalog[key]}


def is_compatible(
    modifier: dict[str, Any],
    tags: set[str],
    *,
    allow_rare: bool = False,
) -> bool:
    allowed = set(modifier.get("allowedTags", []))
    forbidden = set(modifier.get("forbiddenTags", []))
    rare_only = set(modifier.get("rareOnlyTags", []))
    if allowed and not allowed.intersection(tags):
        return False
    if forbidden.intersection(tags):
        return False
    if not allow_rare and rare_only.intersection(tags):
        return False
    return True


def validate_catalog(catalog: dict[str, Any]) -> tuple[list[str], dict[str, int]]:
    errors: list[str] = []
    counts: dict[str, int] = {}
    modifiers = catalog.get("modifiers", {})

    if catalog.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")
    if sum(catalog.get("monsterModifierWeights", {}).values()) != 100:
        errors.append("monster modifier weights must total 100")

    for group, expected in EXPECTED_COUNTS.items():
        actual = len(modifiers.get(group, []))
        counts[group] = actual
        if actual != expected:
            errors.append(f"{group}: expected {expected}, got {actual}")

    classes = {entry["id"]: entry for entry in catalog.get("classes", [])}
    for class_id, primary in EXPECTED_PRIMARY.items():
        actual = classes.get(class_id, {}).get("primaryStat")
        if actual != primary:
            errors.append(f"{class_id}: primaryStat must be {primary}, got {actual}")
    if classes.get("warrior", {}).get("primaryStat") != classes.get("paladin", {}).get("primaryStat"):
        errors.append("warrior and paladin must share STR primary stat")

    all_modifiers = [entry for group in modifiers.values() for entry in group]
    ids = [entry.get("id", "") for entry in all_modifiers]
    duplicates = sorted({entry_id for entry_id in ids if ids.count(entry_id) > 1})
    if duplicates:
        errors.append(f"duplicate modifier ids: {', '.join(duplicates)}")
    for modifier in all_modifiers:
        if not modifier.get("id") or not modifier.get("displayKo", "").strip():
            errors.append(f"blank modifier id or display: {modifier!r}")
        if "  " in modifier.get("displayKo", ""):
            errors.append(f"double space in display: {modifier['id']}")

    known_tags = {
        tag
        for key in ("monsterBases", "lootBases", "equipmentBases")
        for entry in catalog.get(key, [])
        for tag in entry.get("tags", [])
    }
    for modifier in all_modifiers:
        referenced_tags = set(modifier.get("allowedTags", []))
        referenced_tags.update(modifier.get("forbiddenTags", []))
        referenced_tags.update(modifier.get("rareOnlyTags", []))
        unknown = sorted(referenced_tags - known_tags)
        if unknown:
            errors.append(f"{modifier['id']}: unknown tags {', '.join(unknown)}")

    loot_modifiers = modifiers.get("loot", [])
    loot_bases = catalog.get("lootBases", [])
    compatible_loot_pairs = 0
    for loot_base in loot_bases:
        candidates = [
            modifier
            for modifier in loot_modifiers
            if is_compatible(modifier, set(loot_base["tags"]))
        ]
        compatible_loot_pairs += len(candidates)
        if not candidates:
            errors.append(f"{loot_base['id']}: no compatible loot modifier")
    for modifier in loot_modifiers:
        if not any(is_compatible(modifier, set(base["tags"])) for base in loot_bases):
            errors.append(f"{modifier['id']}: no compatible loot base")
    counts["compatibleLootPairs"] = compatible_loot_pairs

    equipment_modifiers = (
        modifiers.get("equipmentStat", [])
        + modifiers.get("equipmentProgress", [])
        + modifiers.get("equipmentTrade", [])
        + modifiers.get("equipmentScene", [])
    )
    equipment_bases = catalog.get("equipmentBases", [])
    compatible_equipment_pairs = 0
    for equipment_base in equipment_bases:
        compatible_equipment_pairs += sum(
            is_compatible(modifier, set(equipment_base["tags"]))
            for modifier in equipment_modifiers
        )
    for modifier in equipment_modifiers:
        if not any(is_compatible(modifier, set(base["tags"])) for base in equipment_bases):
            errors.append(f"{modifier['id']}: no compatible equipment base")
    counts["compatibleEquipmentPairs"] = compatible_equipment_pairs

    modifier_by_id = modifier_index(catalog)
    equipment_by_id = base_index(catalog, "equipmentBases")
    for item in catalog.get("representativeLoadout", []):
        if item.get("equipmentId") not in equipment_by_id:
            errors.append(f"unknown loadout equipment: {item.get('equipmentId')}")
            continue
        tags = set(equipment_by_id[item["equipmentId"]]["tags"])
        for modifier_id in item.get("modifierIds", []):
            modifier = modifier_by_id.get(modifier_id)
            if modifier is None:
                errors.append(f"unknown loadout modifier: {modifier_id}")
            elif not is_compatible(modifier, tags):
                errors.append(f"incompatible loadout pair: {item['equipmentId']} + {modifier_id}")

    loot_by_id = base_index(catalog, "lootBases")
    for monster in catalog.get("monsterBases", []):
        for loot_id in monster.get("lootIds", []):
            if loot_id not in loot_by_id:
                errors.append(f"{monster['id']}: unknown loot id {loot_id}")

    counts["modifierIds"] = len(ids)
    counts["uniqueModifierIds"] = len(set(ids))
    return errors, counts


def render_report(catalog: dict[str, Any], errors: list[str], counts: dict[str, int]) -> str:
    lines = [
        "# 초록빛 언덕 수식어 카탈로그 검증 v0.1",
        "",
        f"- 콘텐츠 버전: `{catalog.get('contentVersion')}`",
        f"- 검사 결과: **{'PASS' if not errors else 'FAIL'}**",
        "",
        "## 수량",
        "",
        "| 그룹 | 목표 | 실제 |",
        "|---|---:|---:|",
    ]
    for group, expected in EXPECTED_COUNTS.items():
        lines.append(f"| {group} | {expected} | {counts.get(group, 0)} |")
    lines.extend(
        [
            "",
            "## 조합 검사",
            "",
            f"- 고유 수식어 ID: `{counts.get('uniqueModifierIds', 0)}/{counts.get('modifierIds', 0)}`",
            f"- 일반 생성 가능한 전리품 조합: `{counts.get('compatibleLootPairs', 0)}`",
            f"- 생성 가능한 장비 조합: `{counts.get('compatibleEquipmentPairs', 0)}`",
            "- 전사·성기사 전투 주능력 STR 일치",
            "",
            "## 오류",
            "",
        ]
    )
    lines.extend([f"- {error}" for error in errors] or ["- 없음"])
    lines.extend(["", f"final result: {'passed' if not errors else 'failed'}", ""])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    catalog = load_catalog(args.catalog)
    errors, counts = validate_catalog(catalog)
    report = render_report(catalog, errors, counts)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report, encoding="utf-8")
    print(args.output)
    if errors:
        raise SystemExit("catalog validation failed")


if __name__ == "__main__":
    main()
