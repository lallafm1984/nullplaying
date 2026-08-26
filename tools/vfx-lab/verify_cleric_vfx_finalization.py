#!/usr/bin/env python3
"""Verify the twenty finalized Cleric web sprite sheets and active routing."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image


LAB = Path(__file__).resolve().parent
REGISTRY = LAB / "data/cleric-vfx-finalized.json"
RECOVERY = LAB / "data/cleric-vfx-recovery-archive.json"
SIGNATURE_MANIFEST = LAB / "data/signature-skills.json"
REMAINING_MANIFEST = LAB / "data/remaining-signature-skills.json"
APEX_MANIFEST = LAB / "data/apex-class-rework-variants.json"
FINAL_DIRECTORY = "custom-assets/cleric-finalized-vfx"
EXPECTED_SIZE = (1444, 640)
FRAME_SIZE = (361, 160)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def frame_alpha(sheet: Image.Image, index: int) -> Image.Image:
    left = (index % 4) * FRAME_SIZE[0]
    top = (index // 4) * FRAME_SIZE[1]
    return sheet.getchannel("A").crop(
        (left, top, left + FRAME_SIZE[0], top + FRAME_SIZE[1])
    )


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    registry = load_json(REGISTRY)
    recovery = load_json(RECOVERY)
    signature = load_json(SIGNATURE_MANIFEST)
    remaining = load_json(REMAINING_MANIFEST)
    apex = load_json(APEX_MANIFEST)

    expected_ids = {
        f"cleric_t{tier:02d}_c{suffix}"
        for tier, suffix in [
            (1, "01"), (2, "02"), (3, "03"), (4, "04"), (5, "05"),
            (6, "03"), (7, "01"), (8, "03"), (9, "05"), (10, "04"),
            (11, "02"), (12, "05"), (13, "03"), (14, "04"), (15, "03"),
            (16, "01"), (17, "05"), (18, "03"), (19, "02"), (20, "05"),
        ]
    }
    selections = registry.get("reviewSelections", {})
    if registry.get("status") != "twenty-finalized" or set(selections) != expected_ids:
        raise ValueError("Cleric registry must contain exactly twenty finalized selections")
    non_original = {key: value for key, value in selections.items() if value != "original"}
    if non_original:
        raise ValueError(f"all Cleric selections must remain original: {non_original}")

    entries = registry.get("finalized", [])
    by_id = {entry["catalogId"]: entry for entry in entries}
    if len(entries) != 20 or set(by_id) != expected_ids:
        raise ValueError("finalized entries must match all twenty Cleric IDs")

    final_dir = LAB / FINAL_DIRECTORY
    actual_files = {path.name for path in final_dir.glob("*.png")}
    expected_files = {f"{catalog_id}.png" for catalog_id in expected_ids}
    if actual_files != expected_files:
        raise ValueError(
            f"finalized directory mismatch: missing={sorted(expected_files - actual_files)}, "
            f"extra={sorted(actual_files - expected_files)}"
        )

    for catalog_id, entry in by_id.items():
        if entry["variant"] != "original":
            raise ValueError(f"{catalog_id}: finalized variant must be original")
        final_path = final_dir / f"{catalog_id}.png"
        selection_path = LAB / entry["selectionSource"]
        with Image.open(final_path) as opened:
            if opened.format != "PNG" or opened.mode != "RGBA" or opened.size != EXPECTED_SIZE:
                raise ValueError(f"{catalog_id}: invalid finalized PNG contract")
            sheet = opened.copy()
        if sha256(final_path) != entry["sha256"]:
            raise ValueError(f"{catalog_id}: registry SHA-256 mismatch")
        if final_path.read_bytes() != selection_path.read_bytes():
            raise ValueError(f"{catalog_id}: finalized bytes differ from the original source")
        if frame_alpha(sheet, 14).getbbox() is None:
            raise ValueError(f"{catalog_id}: F15 must remain visible")
        if frame_alpha(sheet, 15).getbbox() is not None:
            raise ValueError(f"{catalog_id}: F16 must be transparent")

    signature_cleric = [skill for skill in signature["skills"] if skill["heroClass"] == "CLERIC"]
    remaining_cleric = [skill for skill in remaining["skills"] if skill["heroClass"] == "CLERIC"]
    if len(signature_cleric) != 20 or len(remaining_cleric) != 20:
        raise ValueError("both active manifests must contain twenty Cleric skills")
    for skill in signature_cleric:
        expected = f"{FINAL_DIRECTORY}/{skill['id']}.png"
        if skill["source"] != expected:
            raise ValueError(f"{skill['id']}: signature source is not finalized")
    for skill in remaining_cleric:
        expected = f"{FINAL_DIRECTORY}/{skill['id']}.png?v=cleric-finalized-20260825-v1"
        if skill["spritePath"] != expected:
            raise ValueError(f"{skill['id']}: web source is not finalized")

    active_review_ids = set(apex.get("skills", []))
    finalized_layout_ids = set(apex.get("finalizedSkillLayouts", []))
    if active_review_ids & expected_ids:
        raise ValueError("finalized Cleric skills must not remain in Apex review choices")
    if finalized_layout_ids & expected_ids:
        raise ValueError("original Cleric skills must not retain Apex candidate timing")
    if recovery.get("activeDirectory") != FINAL_DIRECTORY:
        raise ValueError("recovery registry does not point to the finalized directory")
    if recovery.get("candidateStatus") != "retained-in-workspace-unregistered":
        raise ValueError("Cleric candidate recovery status is invalid")

    print(json.dumps({
        "ok": True,
        "finalized": len(entries),
        "originalSelections": len(selections),
        "reviewSelectorsRemoved": True,
        "candidateStatus": recovery["candidateStatus"],
        "scope": registry["scope"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
