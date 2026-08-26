#!/usr/bin/env python3
"""Remove superseded 100-candidate planning batches while preserving the final 120 sheets."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LAB = ROOT / "tools/vfx-lab"
CUSTOM_ASSETS = LAB / "custom-assets"
ANDROID_DRAWABLE = ROOT / "app/src/simple/res/drawable-nodpi"
DATA = LAB / "data"

FINAL_ASSET_DIRECTORIES = {
    "warrior-t01-blade-slash-snap-impact-revision",
    "warrior-t02-steel-slice-attached-image",
    "warrior-t03-shatter-strike-attached-image",
    "warrior-t04-earth-cleave-slash-then-rift-imagegen-v1",
    "warrior-t05-cross-slash-horizontal-vertical-afterimage-imagegen-v1",
    "warrior-t06-storm-slash-six-gale-cascade-imagegen-v1",
    "warrior-t07-t20-intuitive-skills-imagegen-v1",
    "rogue-signature-t01-t20-intuitive-imagegen-refined-v1",
    "ranger-signature-t01-t20-intuitive-imagegen-refined-v1",
    "mage-signature-t01-t20-intuitive-imagegen-refined-v1",
    "cleric-signature-t01-t20-intuitive-imagegen-refined-v1",
    "paladin-signature-t01-t20-intuitive-imagegen-refined-v1",
}

FINAL_PROMPT_MANIFESTS = {
    "warrior-t01-blade-slash-snap-impact-revision-sprite-prompts.json",
    "warrior-t07-t20-intuitive-skills-v1-sprite-prompts.json",
    "rogue-signature-t01-t20-intuitive-v1-sprite-prompts.json",
    "ranger-signature-t01-t20-intuitive-v1-sprite-prompts.json",
    "mage-signature-t01-t20-intuitive-v1-sprite-prompts.json",
    "cleric-signature-t01-t20-intuitive-v1-sprite-prompts.json",
    "paladin-signature-t01-t20-intuitive-v1-sprite-prompts.json",
}

FINAL_LAB_EVIDENCE = {
    "ranger-signature-t01-t20-intuitive-refined-v1",
    "mage-signature-t01-t20-intuitive-refined-v1",
    "cleric-signature-t01-t20-intuitive-refined-v1",
    "paladin-signature-t01-t20-intuitive-refined-v1",
}

OBSOLETE_EXACT_FILES = {
    "paused-future-class-jobs.json",
    "pause_future_class_sprites.mjs",
    "resume_paused_class_sprites.mjs",
    "queue_charge_sprites.mjs",
    "sync_charge_sprites.mjs",
    "sync_warrior_sprites_to_android.py",
    "clean_warrior_sprite_artifacts.mjs",
    "refine_warrior_signature_20.py",
    "stabilize_topdown_slash.py",
    "verify_all_class_sprite_program.mjs",
    "verify_remaining_vfx9_resource_pack.py",
    "verify_vfx10_resource_pack.py",
    "verify_vfx6_resource_pack.py",
    "verify_warrior_vfx7_resource_pack.py",
    "verify_warrior_vfx8_resource_pack.py",
}


def tree_size(path: Path) -> int:
    if path.is_file():
        return path.stat().st_size
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())


def obsolete_targets() -> list[Path]:
    if ROOT.name != "AlarmQuest" or not (ROOT / "app/src/simple").is_dir():
        raise SystemExit(f"refusing cleanup outside the AlarmQuest checkout: {ROOT}")
    targets: set[Path] = set()
    for child in CUSTOM_ASSETS.iterdir():
        if child.name not in FINAL_ASSET_DIRECTORIES:
            targets.add(child)

    for child in ANDROID_DRAWABLE.glob("vfx*"):
        if child.is_file() and not child.name.startswith("vfx_sheet_"):
            targets.add(child)

    for child in DATA.glob("android-export*"):
        if child.name != "android-export":
            targets.add(child)

    for child in LAB.iterdir():
        name = child.name
        if not child.is_file():
            continue
        if name in OBSOLETE_EXACT_FILES:
            targets.add(child)
        elif name.startswith("build_") and name != "build_signature_skill_manifest.mjs":
            targets.add(child)
        elif name.endswith("-sprite-jobs.json") or name.endswith("-sprite-prompts.jobs.json"):
            targets.add(child)
        elif name.endswith("-sprite-prompts.json") and name not in FINAL_PROMPT_MANIFESTS:
            targets.add(child)

    lab_evidence = LAB / "evidence"
    if lab_evidence.is_dir():
        for child in lab_evidence.iterdir():
            if child.name not in FINAL_LAB_EVIDENCE:
                targets.add(child)

    root_evidence = ROOT / "evidence"
    if root_evidence.is_dir():
        for child in root_evidence.iterdir():
            if child.name != "rogue-signature-t01-t20-intuitive-refined-v1":
                targets.add(child)
    return sorted(targets)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="perform the reviewed deletion set")
    args = parser.parse_args()

    targets = obsolete_targets()
    total_bytes = sum(tree_size(path) for path in targets)
    print(f"mode={'apply' if args.apply else 'dry-run'}")
    print(f"targets={len(targets)}")
    print(f"bytes={total_bytes}")
    for path in targets:
        print(path.relative_to(ROOT))
    if not args.apply:
        return
    for path in targets:
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()


if __name__ == "__main__":
    main()
