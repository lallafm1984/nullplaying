#!/usr/bin/env python3
"""Verify a twenty-skill all-original Ranger or Paladin web finalization."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

from PIL import Image


LAB = Path(__file__).resolve().parent
EXPECTED_SIZE = (1444, 640)
CONFIG = {
    "RANGER": {
        "slug": "ranger",
        "cache": "ranger-finalized-20260825-v1",
        "source_root": "custom-assets/ranger-signature-t01-t20-intuitive-imagegen-refined-v1",
    },
    "PALADIN": {
        "slug": "paladin",
        "cache": "paladin-finalized-20260825-v1",
        "source_root": "custom-assets/paladin-signature-t01-t20-intuitive-imagegen-refined-v1",
    },
}


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(hero_class: str) -> dict:
    config = CONFIG[hero_class]
    slug = config["slug"]
    final_directory = f"custom-assets/{slug}-finalized-vfx"
    registry = load_json(LAB / f"data/{slug}-vfx-finalized.json")
    recovery = load_json(LAB / f"data/{slug}-vfx-recovery-archive.json")
    signature = load_json(LAB / "data/signature-skills.json")
    remaining = load_json(LAB / "data/remaining-signature-skills.json")
    apex = load_json(LAB / "data/apex-class-rework-variants.json")

    signature_skills = [skill for skill in signature["skills"] if skill["heroClass"] == hero_class]
    remaining_skills = [skill for skill in remaining["skills"] if skill["heroClass"] == hero_class]
    expected_ids = {skill["id"] for skill in signature_skills}
    if len(signature_skills) != 20 or len(remaining_skills) != 20 or len(expected_ids) != 20:
        raise ValueError(f"{hero_class}: active manifests must contain twenty unique skills")

    selections = registry.get("reviewSelections", {})
    if registry.get("status") != "twenty-finalized" or set(selections) != expected_ids:
        raise ValueError(f"{hero_class}: finalized registry must contain all twenty skills")
    if Counter(selections.values()) != {"original": 20}:
        raise ValueError(f"{hero_class}: every selection must be original")

    entries = registry.get("finalized", [])
    by_id = {entry["catalogId"]: entry for entry in entries}
    if len(entries) != 20 or set(by_id) != expected_ids:
        raise ValueError(f"{hero_class}: finalized entries do not match the catalog")

    final_dir = LAB / final_directory
    expected_files = {f"{catalog_id}.png" for catalog_id in expected_ids}
    actual_files = {path.name for path in final_dir.glob("*.png")}
    if actual_files != expected_files:
        raise ValueError(f"{hero_class}: finalized directory file set differs from the catalog")

    for catalog_id, entry in by_id.items():
        if entry["variant"] != "original":
            raise ValueError(f"{catalog_id}: non-original selection found")
        final_path = final_dir / f"{catalog_id}.png"
        source_path = LAB / entry["selectionSource"]
        if not entry["selectionSource"].startswith(f"{config['source_root']}/"):
            raise ValueError(f"{catalog_id}: selection source is not the original source root")
        with Image.open(final_path) as image:
            if image.format != "PNG" or image.mode != "RGBA" or image.size != EXPECTED_SIZE:
                raise ValueError(f"{catalog_id}: invalid finalized PNG contract")
        if final_path.read_bytes() != source_path.read_bytes():
            raise ValueError(f"{catalog_id}: finalized bytes differ from the original source")
        if sha256(final_path) != entry["sha256"]:
            raise ValueError(f"{catalog_id}: registry SHA-256 mismatch")

    for skill in signature_skills:
        expected = f"{final_directory}/{skill['id']}.png"
        if skill["source"] != expected:
            raise ValueError(f"{skill['id']}: Android promotion source is not finalized")
    for skill in remaining_skills:
        expected = f"{final_directory}/{skill['id']}.png?v={config['cache']}"
        if skill["spritePath"] != expected:
            raise ValueError(f"{skill['id']}: web source is not finalized")

    if set(apex.get("skills", [])) & expected_ids:
        raise ValueError(f"{hero_class}: finalized skills remain registered for comparison")
    if set(apex.get("finalizedSkillLayouts", [])) & expected_ids:
        raise ValueError(f"{hero_class}: original skills retain candidate timing")
    if recovery.get("activeDirectory") != final_directory:
        raise ValueError(f"{hero_class}: recovery registry points at another active directory")
    if recovery.get("candidateStatus") != "retained-in-workspace-unregistered":
        raise ValueError(f"{hero_class}: candidate recovery status is invalid")

    return {
        "ok": True,
        "heroClass": hero_class,
        "finalized": len(entries),
        "originalSelections": len(selections),
        "reviewSelectorsRemoved": True,
        "candidateStatus": recovery["candidateStatus"],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("hero_class", choices=sorted(CONFIG))
    args = parser.parse_args()
    print(json.dumps(verify(args.hero_class), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
