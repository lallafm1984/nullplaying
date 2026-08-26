#!/usr/bin/env python3
"""Verify 1:1 parity between the 120 reviewed web sheets and Android Q85 assets."""

from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
LAB = ROOT / "tools/vfx-lab"
DRAWABLE = ROOT / "app/src/simple/res/drawable-nodpi"
MANIFEST = LAB / "data/signature-skills.json"
WEB_CATALOG = LAB / "data/skills.json"
REMAINING = LAB / "data/remaining-signature-skills.json"
KOTLIN_MAP = ROOT / "app/src/simple/java/com/alarmquest/ui/DetailedSpriteAssets.kt"
SKILL_CATALOG = ROOT / "app/src/simple/java/com/alarmquest/engine/SkillCatalog.kt"
WARRIOR_FINALIZED = LAB / "data/warrior-vfx-finalized.json"


def load_skills(path: Path) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8"))["skills"]


manifest = load_skills(MANIFEST)
web = load_skills(WEB_CATALOG)
remaining = load_skills(REMAINING)
manifest_by_id = {skill["id"]: skill for skill in manifest}
manifest_ids = set(manifest_by_id)
warrior_finalized = json.loads(WARRIOR_FINALIZED.read_text(encoding="utf-8"))["finalized"]

assert len(manifest) == len(manifest_ids) == 120
assert Counter(skill["heroClass"] for skill in manifest) == {
    "WARRIOR": 20,
    "ROGUE": 20,
    "RANGER": 20,
    "MAGE": 20,
    "CLERIC": 20,
    "PALADIN": 20,
}
assert [skill["unlockLevel"] for skill in manifest[:20]] == [1, *range(5, 100, 5)]
assert sum(skill["frameCount"] == 16 and skill["rows"] == 4 for skill in manifest) == 119
assert sum(skill["frameCount"] == 12 and skill["rows"] == 3 for skill in manifest) == 1
for finalized in warrior_finalized:
    assert manifest_by_id[finalized["catalogId"]]["source"] == finalized["finalSource"]

late_impact_warrior_ids = {
    "warrior_t14_c03",
    "warrior_t17_c01",
    "warrior_t18_c02",
    "warrior_t19_c02",
    "warrior_t20_c01",
}
for catalog_id in late_impact_warrior_ids:
    skill = manifest_by_id[catalog_id]
    assert skill["impactFrameIndex"] == 8
    assert skill["impactMillis"] == 800
    assert skill["postImpactFrameDurationMillis"] == 70
    assert skill["finalFrameHoldMillis"] == 0
    assert skill["fadeOutMillis"] == 40

web_by_id = {skill["id"]: skill for skill in web}
assert len(web) == len(web_by_id) == 120
assert set(web_by_id) == manifest_ids
for catalog_id, signature in manifest_by_id.items():
    row = web_by_id[catalog_id]
    assert row["name"] == signature["name"]
    assert row["level"] == signature["unlockLevel"]

remaining_ids = {skill["id"] for skill in remaining}
expected_remaining_ids = {
    skill["id"] for skill in manifest if skill["heroClass"] in {"RANGER", "MAGE", "CLERIC", "PALADIN"}
}
assert len(remaining) == 80
assert remaining_ids == expected_remaining_ids
for skill in remaining:
    assert skill["level"] == manifest_by_id[skill["id"]]["unlockLevel"]

catalog_text = SKILL_CATALOG.read_text(encoding="utf-8")
catalog_rows = set(re.findall(r"^\s*([a-z]+_t\d{2}_c\d{2})\|", catalog_text, re.MULTILINE))
assert catalog_rows == manifest_ids

map_text = KOTLIN_MAP.read_text(encoding="utf-8")
map_rows = dict(re.findall(
    r'"([a-z]+_t\d{2}_c\d{2})"\s*->\s*DetailedSpriteSheetSpec\(R\.drawable\.(vfx_sheet_[a-z]+_t\d{2}_c\d{2})',
    map_text,
))
assert set(map_rows) == manifest_ids

android_paths = sorted(DRAWABLE.glob("vfx_sheet_*_t??_c??.webp"))
legacy_paths = sorted(DRAWABLE.glob("vfx16_*_t??_c??.webp"))
assert not legacy_paths
assert len(android_paths) == 120
assert {path.stem.removeprefix("vfx_sheet_") for path in android_paths} == manifest_ids

source_bytes = 0
android_bytes = 0
for catalog_id, skill in manifest_by_id.items():
    source = LAB / skill["source"]
    android = DRAWABLE / f"{map_rows[catalog_id]}.webp"
    expected_size = (skill["columns"] * 361, skill["rows"] * 160)
    with Image.open(source) as source_image, Image.open(android) as android_image:
        assert source_image.format == "PNG"
        assert android_image.format == "WEBP"
        assert source_image.mode == android_image.mode == "RGBA"
        assert source_image.size == android_image.size == expected_size
        assert source_image.getchannel("A").tobytes() == android_image.getchannel("A").tobytes()
    source_bytes += source.stat().st_size
    android_bytes += android.stat().st_size

print("PASS: 120 signature skills are identical across catalog, web data, and Android routing")
print("PASS: 119 4x4 sheets + 1 approved 4x3 sheet; exact alpha parity for every Q85 WebP")
print("PASS: finalized Warrior sources and F09 800ms late-impact timing mirror the web tester")
print(f"source_png_bytes={source_bytes}")
print(f"android_q85_webp_bytes={android_bytes}")
print(f"q85_ratio={android_bytes / source_bytes:.6f}")
