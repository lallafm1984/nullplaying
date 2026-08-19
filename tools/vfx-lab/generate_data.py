#!/usr/bin/env python3
"""Build the browser catalogue only from Android's authoritative VFX export.

The web lab deliberately contains no motion, tier, direction, or asset-selection
formula. Android exports those decisions from the same pure frame planner that
the Compose Canvas consumes; this module only converts the PSV metadata to a
small JSON catalogue and prepares per-skill frame rows for the local API.
"""

from __future__ import annotations

import csv
import json
from collections import defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
LAB = Path(__file__).resolve().parent
EXPORT = LAB / "data/android-export"
OUT = LAB / "data/skills.json"
ASSETS = ROOT / "app/src/simple/res/drawable-nodpi"
R_SYMBOLS = ROOT / "app/build/intermediates/runtime_symbol_list/debug/processDebugResources/R.txt"

CLASS_LABELS = {
    "WARRIOR": "전사",
    "ROGUE": "도적",
    "RANGER": "순찰자",
    "MAGE": "마법사",
    "CLERIC": "성직자",
    "PALADIN": "성기사",
}


def _read_psv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        raise SystemExit(
            f"Missing Android VFX export: {path}\n"
            "Run the CombatMotionTest export before starting the lab."
        )
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="|"))


def _ints(value: str) -> list[int]:
    return [int(item) for item in value.split(",") if item]


def _asset_filename(asset_name: str) -> str:
    matches = [path for path in ASSETS.glob(f"{asset_name}.*") if path.suffix.lower() in {".webp", ".png"}]
    if len(matches) != 1:
        raise SystemExit(f"Expected one drawable for {asset_name}, found {matches}")
    return matches[0].name


def _asset_ids(value: str) -> list[int]:
    return _ints(value)


def _resource_asset_names() -> dict[str, str]:
    if not R_SYMBOLS.exists():
        return {}
    names: dict[str, str] = {}
    for raw in R_SYMBOLS.read_text(encoding="utf-8").splitlines():
        columns = raw.split()
        if len(columns) == 4 and columns[:2] == ["int", "drawable"] and columns[2].startswith("vfx"):
            names[str(int(columns[3], 16))] = _asset_filename(columns[2])
    return names


def generate() -> dict[str, Any]:
    manifest_rows = _read_psv(EXPORT / "manifest.psv")
    if len(manifest_rows) != 1:
        raise SystemExit(f"Expected one Android VFX manifest row, found {len(manifest_rows)}")
    manifest = manifest_rows[0]
    skill_rows = _read_psv(EXPORT / "skills.psv")
    asset_rows = _read_psv(EXPORT / "assets.psv")
    assets_by_id = _resource_asset_names()
    assets_by_id.update({
        row["assetId"]: _asset_filename(row["assetName"])
        for row in asset_rows
    })

    skills: list[dict[str, Any]] = []
    for row in skill_rows:
        timings = _ints(row["hitTimings"])
        weights = _ints(row["hitWeights"])
        presentation_timings = _ints(row["presentationHitTimings"])
        presentation_groups = []
        for encoded in filter(None, row["presentationGroups"].split(";")):
            timing, indices, weight = encoded.split(":")
            presentation_groups.append(
                {
                    "timing": int(timing),
                    "sourceIndices": _ints(indices),
                    "weight": int(weight),
                }
            )
        if len(timings) != len(weights):
            raise SystemExit(f"Timing/weight drift for {row['catalogId']}")

        role_asset_ids = {
            "primary": _asset_ids(row["primaryAsset"]),
            "primaryVariants": _asset_ids(row["primaryAssets"]),
            "secondary": _asset_ids(row["secondaryAsset"]),
            "impact": _asset_ids(row["impactAssets"]),
            "debris": _asset_ids(row["debrisAssets"]),
            "residual": _asset_ids(row["residualAsset"]),
            "finisherRing": _asset_ids(row["finisherRingAsset"]),
            "finisherEcho": _asset_ids(row["finisherEchoAsset"]),
        }
        role_assets = {
            role: [assets_by_id[str(asset_id)] for asset_id in asset_ids]
            for role, asset_ids in role_asset_ids.items()
        }
        skills.append(
            {
                "id": row["catalogId"],
                "heroClass": row["class"],
                "classLabel": CLASS_LABELS[row["class"]],
                "level": int(row["level"]),
                "candidate": int(row["candidate"]),
                "name": row["name"],
                "branchKey": row["branchKey"],
                "action": row["action"],
                "flow": row["flow"],
                "impactStyle": row["impactStyle"],
                "path": row["path"],
                "growthBand": int(row["growthBand"]),
                "tierVariant": int(row["tierVariant"]),
                "identitySchema": int(row["identitySchema"]),
                "identityId": row["identityId"],
                "grammarId": row["grammarId"],
                "growthStage": row["growthStage"],
                "hitBand": row["hitBand"],
                "perceptualSignature": row["perceptualSignature"],
                "roleGrammar": row["roleGrammar"],
                "element": row["element"],
                "hits": len(timings),
                "timings": timings,
                "weights": weights,
                "presentationTimings": presentation_timings,
                "presentationGroups": presentation_groups,
                "legacy": not row["branchKey"].startswith("AUTHORED_CLASS"),
                "legacyRecipe": row["legacyRecipe"],
                "actualLayers": int(row["normalRoleCount"]),
                "normalPeakConcurrent": int(row["normalPeakConcurrent"]),
                "minimumPeakConcurrent": int(row["minimumPeakConcurrent"]),
                "reducedMaxConcurrent": int(row["reducedMaxConcurrent"]),
                "roleAssets": role_assets,
            }
        )

    if len(skills) != 600 or len({skill["id"] for skill in skills}) != 600:
        raise SystemExit(f"Expected 600 unique skills, found {len(skills)}")

    return {
        "meta": {
            "generatedFrom": str((EXPORT / "skills.psv").relative_to(ROOT)),
            "frameSource": "Android authored/legacy frame export at 10 ms samples plus exact damage landmarks",
            "assetRoot": "app/src/simple/res/drawable-nodpi",
            "viewportWidth": float(manifest["viewportWidth"]),
            "viewportHeight": float(manifest["viewportHeight"]),
            "durationMillis": int(manifest["presentationDurationMillis"]),
            "vfxEndMillis": int(manifest["vfxEndMillis"]),
            "attackBoundaryMillis": int(manifest["attackBoundaryMillis"]),
            "sampleStepMillis": int(manifest["sampleStepMillis"]),
            "skillCount": len(skills),
            "assetsById": assets_by_id,
        },
        "skills": skills,
    }


def load_frame_cache() -> dict[str, dict[str, list[dict[str, Any]]]]:
    """Return compact frame samples grouped by skill and reduced-motion flag."""
    grouped: dict[str, dict[str, list[dict[str, Any]]]] = defaultdict(lambda: {"0": [], "1": []})
    for row in _read_psv(EXPORT / "frames.psv"):
        grouped[row["catalogId"]][row["reduced"]].append(
            {
                "t": int(row["elapsed"]),
                "role": row["role"],
                "asset": int(row["assetId"]),
                "instance": int(row["instance"]),
                "hit": int(row["hitIndex"]),
                "x": float(row["x"]),
                "y": float(row["y"]),
                "w": float(row["width"]),
                "h": float(row["height"]),
                "r": float(row["rotation"]),
                "a": float(row["alpha"]),
                "reveal": float(row["reveal"]),
                "mirror": float(row["mirror"]),
                "start": int(row["start"]),
                "end": int(row["end"]),
                "drawMode": row["drawMode"],
                "tintArgb": row["tintArgb"],
                "safeAlphaCap": float(row["safeAlphaCap"]) if row["safeAlphaCap"] else None,
            }
        )
    if len(grouped) != 600:
        raise SystemExit(f"Expected exported frames for 600 skills, found {len(grouped)}")
    return dict(grouped)


def load_presentation_cache() -> dict[str, dict[str, list[dict[str, Any]]]]:
    """Exact Android damage, gauge, label and camera samples for the browser player."""
    grouped: dict[str, dict[str, list[dict[str, Any]]]] = defaultdict(lambda: {"0": [], "1": []})
    for row in _read_psv(EXPORT / "presentation.psv"):
        grouped[row["catalogId"]][row["reduced"]].append(
            {
                "t": int(row["elapsed"]),
                "damageVisible": row["damageVisible"] == "1",
                "damage": int(row["damage"]),
                "damageAlpha": float(row["damageAlpha"]),
                "damageScale": float(row["damageScale"]),
                "damageY": float(row["damageY"]),
                "damageFinal": row["damageFinal"] == "1",
                "energy": float(row["energy"]),
                "labelAlpha": float(row["labelAlpha"]),
                "cameraX": float(row["cameraX"]),
                "cameraY": float(row["cameraY"]),
                "cameraScale": float(row["cameraScale"]),
            }
        )
    if len(grouped) != 600:
        raise SystemExit(f"Expected presentation samples for 600 skills, found {len(grouped)}")
    return dict(grouped)


def write_payload_atomically(payload: dict[str, Any]) -> None:
    temporary = OUT.with_name(f".{OUT.name}.tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    temporary.replace(OUT)


if __name__ == "__main__":
    OUT.parent.mkdir(parents=True, exist_ok=True)
    payload = generate()
    write_payload_atomically(payload)
    print(f"Wrote {len(payload['skills'])} Android-authored skills to {OUT}")
