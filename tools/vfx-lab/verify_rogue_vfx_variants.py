#!/usr/bin/env python3
"""Verify the twenty finalized Rogue web sheets and the cleaned workspace."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

from PIL import Image


LAB = Path(__file__).resolve().parent
REGISTRY = LAB / "data/rogue-vfx-variants.json"
SIGNATURE_MANIFEST = LAB / "data/signature-skills.json"
EXPECTED_SIZE = (1444, 640)
FRAME_SIZE = (361, 160)
SILENT_EXECUTION_ID = "rogue_t12_c05"
VALID_VARIANTS = {"1", "2", "3", "4"}
ORIGINAL_SELECTION_IDS = {
    "rogue_t16_c04",
    "rogue_t17_c05",
    "rogue_t18_c01",
    "rogue_t19_c02",
    "rogue_t20_c05",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_sheet(path: Path, label: str) -> Image.Image:
    with Image.open(path) as opened:
        if opened.format != "PNG" or opened.mode != "RGBA" or opened.size != EXPECTED_SIZE:
            raise ValueError(
                f"{label}: expected PNG RGBA {EXPECTED_SIZE}, "
                f"got {opened.format} {opened.mode} {opened.size}"
            )
        return opened.copy()


def frame(sheet: Image.Image, index: int) -> Image.Image:
    column = index % 4
    row = index // 4
    left = column * FRAME_SIZE[0]
    top = row * FRAME_SIZE[1]
    return sheet.crop((left, top, left + FRAME_SIZE[0], top + FRAME_SIZE[1]))


def parse_app_defaults(app_text: str) -> dict[str, str]:
    block = re.search(
        r"const DEFAULT_ROGUE_VFX_SELECTIONS = new Map\(\[(.*?)\]\);",
        app_text,
        re.DOTALL,
    )
    if block is None:
        raise ValueError("app.js DEFAULT_ROGUE_VFX_SELECTIONS was not found")
    return dict(re.findall(
        r'\["(rogue_t\d{2}_c\d{2})", "(original|[1-4])"\]',
        block.group(1),
    ))


def verify_finalized(registry: dict, app_text: str) -> list[dict]:
    if registry.get("status") != "twenty-finalized" or "pendingReview" in registry:
        raise ValueError("registry must contain the twenty-finalized state and no pending review")
    if registry.get("activeOverrides") or registry.get("reviewCollections"):
        raise ValueError("finalized registry must not retain active overrides or review collections")

    selections = registry["reviewSelections"]
    if len(selections) != 20:
        raise ValueError("reviewSelections must contain twenty active selections")
    invalid_originals = {
        catalog_id for catalog_id, selection in selections.items()
        if selection == "original" and catalog_id not in ORIGINAL_SELECTION_IDS
    }
    invalid_variants = {
        catalog_id for catalog_id, selection in selections.items()
        if selection != "original" and selection not in VALID_VARIANTS
    }
    missing_originals = {
        catalog_id for catalog_id in ORIGINAL_SELECTION_IDS
        if selections.get(catalog_id) != "original"
    }
    if invalid_originals or invalid_variants or missing_originals:
        raise ValueError(
            "invalid finalized selections: "
            f"invalid_originals={sorted(invalid_originals)}, "
            f"invalid_variants={sorted(invalid_variants)}, "
            f"missing_originals={sorted(missing_originals)}"
        )

    entries = registry["finalized"]
    by_id = {entry["catalogId"]: entry for entry in entries}
    if len(entries) != 20 or set(by_id) != set(selections):
        raise ValueError("finalized entries must exactly match all twenty confirmed selections")

    output_dir = LAB / registry["finalizedDirectory"]
    expected_files = {f"{catalog_id}.png" for catalog_id in selections}
    actual_files = {path.name for path in output_dir.iterdir() if path.is_file()}
    actual_directories = [path.name for path in output_dir.iterdir() if path.is_dir()]
    if actual_files != expected_files or actual_directories:
        raise ValueError(
            f"finalized directory mismatch: missing={sorted(expected_files - actual_files)}, "
            f"extra={sorted(actual_files - expected_files)}, directories={actual_directories}"
        )

    results: list[dict] = []
    for catalog_id in sorted(by_id):
        entry = by_id[catalog_id]
        selection = selections[catalog_id]
        if entry["variant"] != selection:
            raise ValueError(f"{catalog_id}: finalized variant does not match reviewSelections")
        path = output_dir / f"{catalog_id}.png"
        sheet = load_sheet(path, f"finalized {catalog_id}")
        actual_hash = sha256(path)
        if actual_hash != entry["sha256"]:
            raise ValueError(f"{catalog_id}: SHA-256 mismatch")
        if frame(sheet, 14).getchannel("A").getbbox() is None:
            raise ValueError(f"{catalog_id}: F15 must remain visible for runtime fade")
        results.append({
            "catalogId": catalog_id,
            "activeSelection": selection,
            "preservedVariant": entry["variant"],
            "sha256": actual_hash,
        })

    silent_sheet = load_sheet(output_dir / f"{SILENT_EXECUTION_ID}.png", "finalized Lv55")
    if any(frame(silent_sheet, index).getchannel("A").getbbox() is None for index in range(9, 15)):
        raise ValueError("Lv55 F10-F15 must remain visible")
    if frame(silent_sheet, 13).tobytes() != frame(silent_sheet, 14).tobytes():
        raise ValueError("Lv55 F15 must repeat F14")
    if frame(silent_sheet, 15).getchannel("A").getbbox() is not None:
        raise ValueError("Lv55 F16 must be transparent")

    if parse_app_defaults(app_text) != selections:
        raise ValueError("app.js fixed selections do not match the registry")
    directory = registry["finalizedDirectory"]
    if f'const ROGUE_FINALIZED_VFX_DIRECTORY = "{directory}";' not in app_text:
        raise ValueError("app.js does not point Rogue skills at the finalized directory")
    rejected_markers = [
        "rogue-t12-silent-execution-v4-local-sequence",
        "ROGUE_VFX_VARIANT_LABELS",
        "rogueVfxChoices",
        "rogueVfxSelector",
        "selectRogueVfxVariant",
    ]
    active_rejected = [marker for marker in rejected_markers if marker in app_text]
    if active_rejected:
        raise ValueError(f"app.js still contains review-only paths or UI: {active_rejected}")
    return results


def verify_manifest(registry: dict) -> None:
    manifest = json.loads(SIGNATURE_MANIFEST.read_text(encoding="utf-8"))
    rogue = [skill for skill in manifest["skills"] if skill["heroClass"] == "ROGUE"]
    if len(rogue) != 20:
        raise ValueError("signature manifest must contain twenty Rogue skills")
    for skill in rogue:
        expected = f"custom-assets/rogue-finalized-vfx/{skill['id']}.png"
        if skill["source"] != expected:
            raise ValueError(f"{skill['id']}: signature manifest source is not the active selection")


def verify_registered_workspace(registry: dict) -> dict:
    custom_assets = LAB / "custom-assets"
    rogue_asset_directories = sorted(
        path.name for path in custom_assets.iterdir()
        if path.is_dir() and path.name.startswith("rogue")
    )
    expected_paths = {
        LAB / registry["finalizedDirectory"] / f"{catalog_id}.png"
        for catalog_id in registry["reviewSelections"]
    }
    actual_paths = set(custom_assets.rglob("rogue_t*.png"))
    missing = sorted(str(path.relative_to(LAB)) for path in expected_paths - actual_paths)
    extra = sorted(str(path.relative_to(LAB)) for path in actual_paths - expected_paths)
    if missing or extra:
        raise ValueError(f"Rogue workspace mismatch: missing={missing}, extra={extra}")
    if rogue_asset_directories != ["rogue-finalized-vfx"]:
        raise ValueError(
            "only rogue-finalized-vfx may remain as an active Rogue asset directory: "
            f"{rogue_asset_directories}"
        )
    return {
        "activeRogueAssetDirectories": rogue_asset_directories,
        "registeredRogueFiles": sorted(str(path.relative_to(LAB)) for path in expected_paths),
    }


def main() -> None:
    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    app_text = (LAB / "app.js").read_text(encoding="utf-8")
    finalized = verify_finalized(registry, app_text)
    verify_manifest(registry)
    workspace = verify_registered_workspace(registry)
    print(json.dumps({
        "ok": True,
        "finalizedVariants": len(finalized),
        "silentExecutionVariant": registry["reviewSelections"][SILENT_EXECUTION_ID],
        "workspace": workspace,
        "scope": registry["scope"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
