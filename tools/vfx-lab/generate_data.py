#!/usr/bin/env python3
"""Build the 120-skill browser catalogue from the retained Android presentation export.

The web lab draws the reviewed PNG sheets directly. This module keeps the compact
skill metadata and damage/camera presentation samples that drive the test UI.
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
SIGNATURE_MANIFEST = LAB / "data/signature-skills.json"
ASSETS = ROOT / "app/src/simple/res/drawable-nodpi"

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


def _signature_skills() -> dict[str, dict[str, Any]]:
    payload = json.loads(SIGNATURE_MANIFEST.read_text(encoding="utf-8"))
    skills = payload.get("skills", [])
    by_id = {skill["id"]: skill for skill in skills}
    if len(skills) != 120 or len(by_id) != 120:
        raise SystemExit(f"Expected 120 unique signature skills, found {len(skills)}/{len(by_id)}")
    return by_id


def _ints(value: str) -> list[int]:
    return [int(item) for item in value.split(",") if item]


def generate() -> dict[str, Any]:
    signatures_by_id = _signature_skills()
    manifest_rows = _read_psv(EXPORT / "manifest.psv")
    if len(manifest_rows) != 1:
        raise SystemExit(f"Expected one Android VFX manifest row, found {len(manifest_rows)}")
    manifest = manifest_rows[0]
    skill_rows = _read_psv(EXPORT / "skills.psv")
    skills: list[dict[str, Any]] = []
    for row in skill_rows:
        signature = signatures_by_id.get(row["catalogId"])
        if signature is None:
            continue
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

        role_assets = {role: [] for role in (
            "primary", "primaryVariants", "secondary", "impact", "debris",
            "residual", "finisherRing", "finisherEcho",
        )}
        skills.append(
            {
                "id": row["catalogId"],
                "heroClass": row["class"],
                "classLabel": CLASS_LABELS[row["class"]],
                "level": int(signature["unlockLevel"]),
                "candidate": int(row["candidate"]),
                "name": signature["name"],
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

    if len(skills) != 120 or {skill["id"] for skill in skills} != signatures_by_id.keys():
        raise SystemExit(f"Expected all 120 signature skills, found {len(skills)}")

    return {
        "meta": {
            "generatedFrom": str((EXPORT / "skills.psv").relative_to(ROOT)),
            "frameSource": "Reviewed signature sheets plus Android presentation samples",
            "assetRoot": "app/src/simple/res/drawable-nodpi",
            "viewportWidth": float(manifest["viewportWidth"]),
            "viewportHeight": float(manifest["viewportHeight"]),
            "durationMillis": int(manifest["presentationDurationMillis"]),
            "vfxEndMillis": int(manifest["vfxEndMillis"]),
            "attackBoundaryMillis": int(manifest["attackBoundaryMillis"]),
            "sampleStepMillis": int(manifest["sampleStepMillis"]),
            "skillCount": len(skills),
            "assetsById": {},
        },
        "skills": skills,
    }


def load_frame_cache() -> dict[str, dict[str, list[dict[str, Any]]]]:
    """Every production skill now renders its reviewed sprite sheet directly."""
    return {catalog_id: {"0": [], "1": []} for catalog_id in _signature_skills()}


def load_presentation_cache() -> dict[str, dict[str, list[dict[str, Any]]]]:
    """Exact Android damage, gauge, label and camera samples for the browser player."""
    grouped: dict[str, dict[str, list[dict[str, Any]]]] = defaultdict(lambda: {"0": [], "1": []})
    signature_ids = _signature_skills().keys()
    for row in _read_psv(EXPORT / "presentation.psv"):
        if row["catalogId"] not in signature_ids:
            continue
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
    if len(grouped) != 120:
        raise SystemExit(f"Expected presentation samples for 120 signature skills, found {len(grouped)}")
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
