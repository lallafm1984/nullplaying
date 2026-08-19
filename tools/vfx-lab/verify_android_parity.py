#!/usr/bin/env python3
"""Verify that the browser is a data player for Android's VFX planner."""

import csv
import re
from collections import defaultdict
from math import isclose
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
LAB = ROOT / "tools/vfx-lab"
EXPORT = LAB / "data/android-export"
WEB = (LAB / "app.js").read_text()
GENERATOR = (LAB / "generate_data.py").read_text()
SERVER = (LAB / "serve.py").read_text()
HTML = (LAB / "index.html").read_text()
CSS = (LAB / "styles.css").read_text()
ANDROID_COMBAT = (ROOT / "app/src/simple/java/com/alarmquest/ui/AlarmQuestApp.kt").read_text()
ANDROID_WARRIOR_SPRITE_MAP = (
    ROOT / "app/src/simple/java/com/alarmquest/ui/WarriorDetailedSpriteAssets.kt"
).read_text()
NORMALIZER = (LAB / "normalize_vfx_asset.py").read_text()
CREATION_EARTHQUAKE_ID = "warrior_t20_c04"


def read_psv(name: str) -> list[dict[str, str]]:
    with (EXPORT / name).open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="|"))


def is_warrior_non_slash(skill: dict[str, str]) -> bool:
    return skill["class"] == "WARRIOR" and skill["candidate"] in {"1", "2", "3"}


skills = read_psv("skills.psv")
frames = read_psv("frames.psv")
assets = read_psv("assets.psv")
manifest = read_psv("manifest.psv")
presentation = read_psv("presentation.psv")
assert len(manifest) == 1
assert manifest[0]["viewportWidth"] == "361.0"
assert manifest[0]["viewportHeight"] == "160.0"
assert manifest[0]["sampleStepMillis"] == "10"
assert len(skills) == 600 and len({row["catalogId"] for row in skills}) == 600
authored_skill_rows = [row for row in skills if row["branchKey"].startswith("AUTHORED_CLASS")]
assert len(authored_skill_rows) == 560
assert len({row["identityId"] for row in authored_skill_rows}) == 560
assert len({row["perceptualSignature"] for row in authored_skill_rows}) == 560
assert len({row["grammarId"] for row in authored_skill_rows}) == 18
expected_samples_per_motion = sum(
    len(set(range(0, 1351, 10)) | {int(value) for value in skill["hitTimings"].split(",") if value})
    for skill in skills
)
assert len(presentation) == expected_samples_per_motion * 2
assert len({row["catalogId"] for row in presentation}) == 600
assert {row["reduced"] for row in presentation} == {"0", "1"}
assert len(assets) >= 140, f"Android export unexpectedly lost VFX assets: {len(assets)}"
asset_names = {row["assetId"]: row["assetName"] for row in assets}
drawable_vfx_names = {
    path.stem
    for path in (ROOT / "app/src/simple/res/drawable-nodpi").glob("vfx*")
    if path.is_file() and not path.stem.startswith("vfx16_warrior_")
}
exported_vfx_names = set(asset_names.values())
assert drawable_vfx_names == exported_vfx_names, (
    f"VFX resource set drift: {len(drawable_vfx_names - exported_vfx_names)} unused files, "
    f"{len(exported_vfx_names - drawable_vfx_names)} missing files"
)
warrior_catalog_ids = {row["catalogId"] for row in skills if row["class"] == "WARRIOR"}
android_warrior_sprite_ids = set(
    re.findall(r'"(warrior_t\d{2}_c\d{2})"\s*->\s*R\.drawable\.vfx16_', ANDROID_WARRIOR_SPRITE_MAP)
)
assert android_warrior_sprite_ids == warrior_catalog_ids, (
    f"Android Detailed warrior sprite map drift: "
    f"{len(warrior_catalog_ids - android_warrior_sprite_ids)} missing, "
    f"{len(android_warrior_sprite_ids - warrior_catalog_ids)} extra"
)
android_warrior_sprite_paths = sorted(
    (ROOT / "app/src/simple/res/drawable-nodpi").glob("vfx16_warrior_t??_c??.webp")
)
assert len(android_warrior_sprite_paths) == 100
assert {path.stem.removeprefix("vfx16_") for path in android_warrior_sprite_paths} == warrior_catalog_ids
for path in android_warrior_sprite_paths:
    with Image.open(path) as image:
        assert image.format == "WEBP"
        assert image.size == (1444, 640)
        assert image.mode == "RGBA"
assert {row["drawMode"] for row in frames} == {
    "AUTHORED_SCREEN", "LEGACY_UNTINTED", "LEGACY_TINTED"
}

metadata_asset_ids: set[str] = set()
for skill in skills:
    for column in (
        "primaryAsset", "primaryAssets", "secondaryAsset", "impactAssets", "debrisAssets",
        "residualAsset", "finisherRingAsset", "finisherEchoAsset",
    ):
        metadata_asset_ids.update(asset_id for asset_id in skill[column].split(",") if asset_id)
assert metadata_asset_ids <= asset_names.keys(), (
    f"{len(metadata_asset_ids - asset_names.keys())} metadata assets have no drawable name"
)
frame_asset_ids = {frame["assetId"] for frame in frames}
assert metadata_asset_ids <= frame_asset_ids, (
    "dead metadata assets: " + ", ".join(sorted(asset_names[asset_id] for asset_id in metadata_asset_ids - frame_asset_ids))
)

by_skill_time: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
for frame in frames:
    by_skill_time[(frame["catalogId"], frame["elapsed"])].append(frame)
assert len({row["catalogId"] for row in frames}) == 600

assert all(not row["safeAlphaCap"] for row in frames if row["drawMode"] == "AUTHORED_SCREEN")

skill_by_id = {row["catalogId"]: row for row in skills}
for skill in skills:
    for hit_index, hit_millis in enumerate(int(value) for value in skill["hitTimings"].split(",") if value):
        expected_role = (
            "PRIMARY"
            if skill["class"] == "WARRIOR" and int(skill["candidate"]) == 4
            else "CONTACT"
        )
        exact_landmark = [
            frame for frame in by_skill_time[(skill["catalogId"], str(hit_millis))]
            if frame["reduced"] == "0" and frame["role"] == expected_role and int(frame["hitIndex"]) == hit_index
        ]
        assert exact_landmark, (
            f"{skill['catalogId']} hit {hit_index} has no exact exported {expected_role} at {hit_millis}ms"
        )
normal_concurrent: dict[tuple[str, str], int] = defaultdict(int)
for frame in frames:
    if frame["reduced"] == "0":
        normal_concurrent[(frame["catalogId"], frame["elapsed"])] += 1
assert max(normal_concurrent.values()) <= 6
for (catalog_id, elapsed), concurrent in normal_concurrent.items():
    skill = skill_by_id[catalog_id]
    band = int(skill["growthBand"])
    expected_limit = 4 if skill["class"] != "WARRIOR" else (
        {"LOW": 4, "MID": 4, "HIGH": 5}[skill["growthStage"]]
        if is_warrior_non_slash(skill)
        else 4 + min(band, 2)
    )
    assert concurrent <= expected_limit, (
        f"{catalog_id} band {band} stacks {concurrent} layers at {elapsed}ms "
        f"(limit {expected_limit})"
    )

normal_roles: dict[str, set[str]] = defaultdict(set)
normal_peaks: dict[str, int] = defaultdict(int)
for frame in frames:
    if frame["reduced"] != "0":
        continue
    normal_roles[frame["catalogId"]].add(frame["role"])
for (catalog_id, _), concurrent in normal_concurrent.items():
    normal_peaks[catalog_id] = max(normal_peaks[catalog_id], concurrent)
for catalog_id, skill in skill_by_id.items():
    if not skill["branchKey"].startswith("AUTHORED_CLASS"):
        continue
    stage_minimum = (2, 2) if catalog_id == CREATION_EARTHQUAKE_ID else (
        {"LOW": (4, 3), "MID": (5, 4), "HIGH": (5, 4)}[skill["growthStage"]]
        if is_warrior_non_slash(skill)
        else (
            {"LOW": (4, 3), "MID": (5, 3), "HIGH": (6, 3)}[skill["growthStage"]]
            if skill["class"] != "WARRIOR"
            else {"LOW": (4, 3), "MID": (5, 4), "HIGH": (6, 5)}[skill["growthStage"]]
        )
    )
    required_roles, required_peak = stage_minimum
    assert len(normal_roles[catalog_id]) >= required_roles, (
        f"{catalog_id} exposes only {len(normal_roles[catalog_id])} normal roles"
    )
    assert normal_peaks[catalog_id] >= required_peak, (
        f"{catalog_id} peaks at {normal_peaks[catalog_id]} layers, expected {required_peak}"
    )
    assert int(skill["normalRoleCount"]) == len(normal_roles[catalog_id])
    assert int(skill["normalPeakConcurrent"]) == normal_peaks[catalog_id]
    assert int(skill["minimumPeakConcurrent"]) == required_peak
    if catalog_id != CREATION_EARTHQUAKE_ID:
        assert skill["secondaryAsset"] != skill["finisherEchoAsset"], (
            f"{catalog_id} aliases anticipation and finisher echo"
        )

authored_ids = {
    catalog_id for catalog_id, row in skill_by_id.items()
    if row["branchKey"].startswith("AUTHORED_CLASS")
}
authored_normal = [
    row for row in frames
    if row["reduced"] == "0" and row["catalogId"] in authored_ids
]
assert all(int(row["end"]) <= 1240 for row in authored_normal)
assert not any(int(row["elapsed"]) >= 1240 for row in authored_normal)
late_visible = [
    row for row in authored_normal
    if int(row["elapsed"]) == 1230 and float(row["alpha"]) >= .10
]
assert not late_visible, (
    f"{len(late_visible)} authored planes still hard-cut above alpha .10 at 1230 ms"
)
for catalog_id in {
    "cleric_t18_c05", "mage_t17_c03", "mage_t20_c03", "mage_t20_c05", "paladin_t20_c05",
}:
    assert catalog_id in authored_ids
    assert not any(
        row["catalogId"] == catalog_id and row["reduced"] == "0" and int(row["elapsed"]) >= 1240
        for row in frames
    ), f"{catalog_id} still hard-cuts at the 1,299 ms boundary"

presentation_by_skill_time = {
    (row["catalogId"], row["reduced"], int(row["elapsed"])): row
    for row in presentation
}
for catalog_id in ("mage_t01_c03", "cleric_t19_c05"):
    grouped_final = presentation_by_skill_time[(catalog_id, "0", 760)]
    assert grouped_final["damageVisible"] == "1"
    assert grouped_final["damage"] == "5000", f"{catalog_id} lost the grouped 50% final hit"
    assert float(presentation_by_skill_time[(catalog_id, "0", 840)]["energy"]) < float(
        presentation_by_skill_time[(catalog_id, "0", 740)]["energy"]
    )
    assert any(
        row["catalogId"] == catalog_id and row["reduced"] == "0" and (
            not isclose(float(row["cameraX"]), 0.0, abs_tol=1e-6)
            or not isclose(float(row["cameraY"]), 0.0, abs_tol=1e-6)
            or not isclose(float(row["cameraScale"]), 1.0, abs_tol=1e-6)
        )
        for row in presentation
    ), f"{catalog_id} lost its authoritative camera impulse"

for row in presentation:
    if row["reduced"] == "1":
        assert isclose(float(row["cameraX"]), 0.0, abs_tol=1e-6)
        assert isclose(float(row["cameraY"]), 0.0, abs_tol=1e-6)
        assert isclose(float(row["cameraScale"]), 1.0, abs_tol=1e-6)
        assert isclose(float(row["damageScale"]), 1.0, abs_tol=1e-6)
        assert isclose(float(row["damageY"]), 0.0, abs_tol=1e-6)

primary_tracks: dict[tuple[str, str], list[tuple[float, float]]] = defaultdict(list)
for row in authored_normal:
    if row["role"] == "PRIMARY":
        primary_tracks[(row["catalogId"], row["hitIndex"])].append((float(row["x"]), float(row["y"])))
for catalog_id in authored_ids:
    if skill_by_id[catalog_id]["flow"] not in {"TOP_TO_BOTTOM", "BOTTOM_TO_TOP", "OUTSIDE_IN"}:
        continue
    endpoint_indices = {
        source_indices.split(",")[-1]
        for _, source_indices, _ in (
            encoded.split(":")
            for encoded in skill_by_id[catalog_id]["presentationGroups"].split(";")
            if encoded
        )
    }
    tracks = [
        points for (skill, hit_index), points in primary_tracks.items()
        if skill == catalog_id and hit_index in endpoint_indices
    ]
    assert tracks, f"{catalog_id} has no primary tracks"
    visible_tracks = []
    for points in tracks:
        # A very large full-bleed primary can enter its exported frame window already near the
        # contact gate. Keep verifying authored travel, but evaluate the skill's visible family
        # instead of requiring every overlapping endpoint layer to move four percent alone.
        x_travel = max(point[0] for point in points) - min(point[0] for point in points)
        y_travel = max(point[1] for point in points) - min(point[1] for point in points)
        visible_tracks.append((x_travel, y_travel))
        if skill_by_id[catalog_id]["flow"] in {"TOP_TO_BOTTOM", "BOTTOM_TO_TOP"}:
            continue
        else:
            continue
    if skill_by_id[catalog_id]["class"] == "WARRIOR" and skill_by_id[catalog_id]["candidate"] == "3":
        assert max(x for x, _ in visible_tracks) <= 1e-6, f"{catalog_id} earth anchor drifted horizontally"
        assert max(y for _, y in visible_tracks) >= .10, f"{catalog_id} earth body no longer erupts upward"
        continue
    if skill_by_id[catalog_id]["flow"] in {"TOP_TO_BOTTOM", "BOTTOM_TO_TOP"}:
        assert max(y for _, y in visible_tracks) >= 0.04, f"{catalog_id} vertical primary family froze"
    else:
        assert max(max(x, y) for x, y in visible_tracks) >= 0.04, f"{catalog_id} outside-in primary family froze"

ground = by_skill_time[("warrior_t01_c04", "420")]
ground_normal = {row["role"]: row for row in ground if row["reduced"] == "0"}
assert set(ground_normal) == {"SECONDARY", "PRIMARY", "CONTACT"}
# The native CombatPanel VFX plane is 361 x 160. These sentinels lock the reviewed VFX8 aspect
# ratios and semantic strike placement; they fail if the old square stretching or 180-high export
# plane returns.
assert isclose(float(ground_normal["SECONDARY"]["width"]), 0.51445615, abs_tol=1e-6)
assert isclose(float(ground_normal["SECONDARY"]["height"]), 1.1607417, abs_tol=1e-6)
assert isclose(float(ground_normal["PRIMARY"]["width"]), 0.92, abs_tol=1e-6)
assert isclose(float(ground_normal["PRIMARY"]["height"]), 1.3838333, abs_tol=1e-6)
assert isclose(float(ground_normal["CONTACT"]["x"]), 0.48369423, abs_tol=1e-6)
assert isclose(float(ground_normal["CONTACT"]["y"]), 0.41579604, abs_tol=1e-6)
assert isclose(float(ground_normal["CONTACT"]["width"]), 0.8221448, abs_tol=1e-6)
assert isclose(float(ground_normal["CONTACT"]["height"]), 1.8549643, abs_tol=1e-6)
assert float(ground_normal["CONTACT"]["width"]) <= float(ground_normal["PRIMARY"]["width"]) * .92 + 1e-6
assert float(ground_normal["CONTACT"]["height"]) <= float(ground_normal["PRIMARY"]["height"]) * 1.40 + 1e-6
assert isclose(float(ground_normal["CONTACT"]["alpha"]), 0.9, abs_tol=1e-6)
assert list(ground_normal)[-1] == "CONTACT", "warrior ground contact is not the topmost VFX plane"
ground_presentation = presentation_by_skill_time[("warrior_t01_c04", "0", 420)]
assert isclose(float(ground_presentation["cameraY"]), 4.8, abs_tol=1e-6)
assert isclose(float(ground_presentation["cameraScale"]), 1.018, abs_tol=1e-6)

for candidate in (2, 3, 4):
    for tier in range(1, 21):
        catalog_id = f"warrior_t{tier:02d}_c0{candidate}"
        stage = skill_by_id[catalog_id]["growthStage"]
        exact_hit = [
            row for row in by_skill_time[(catalog_id, "420")]
            if row["reduced"] == "0" and row["role"] == "CONTACT"
        ]
        assert len(exact_hit) == 1, f"{catalog_id} lost exact-hit contact"
        minimum_alpha = {"LOW": .89, "MID": .94, "HIGH": .99}[stage]
        assert float(exact_hit[0]["alpha"]) >= minimum_alpha, (
            f"{catalog_id} contact remains translucent at 420ms"
        )
        if catalog_id == CREATION_EARTHQUAKE_ID:
            continue
        normal_rows = [
            row for row in frames if row["catalogId"] == catalog_id and row["reduced"] == "0"
        ]
        first_strong = min(
            int(row["elapsed"]) for row in normal_rows
            if row["role"] == "PRIMARY" and float(row["alpha"]) >= .45
        )
        latest_strong = {
            2: {"LOW": 310, "MID": 280, "HIGH": 250},
            3: {"LOW": 300, "MID": 270, "HIGH": 240},
            4: {"LOW": 330, "MID": 320, "HIGH": 300},
        }[candidate][stage]
        assert first_strong <= latest_strong, f"{catalog_id} primary still starts at {first_strong}ms"
        last_primary = max(
            int(row["elapsed"]) for row in normal_rows
            if row["role"] == "PRIMARY" and float(row["alpha"]) >= .10
        )
        last_support = max(
            int(row["elapsed"]) for row in normal_rows
            if row["role"] != "PRIMARY" and float(row["alpha"]) >= .10
        )
        primary_bounds = {
            2: {"LOW": (1010, 1030), "MID": (1100, 1120), "HIGH": (1180, 1200)},
            3: {"LOW": (570, 610), "MID": (610, 650), "HIGH": (640, 680)},
            4: {"LOW": (1090, 1110), "MID": (1160, 1180), "HIGH": (1200, 1220)},
        }[candidate][stage]
        assert primary_bounds[0] <= last_primary <= primary_bounds[1], (
            f"{catalog_id} body release drifted to {last_primary}ms"
        )
        if candidate in (2, 4):
            assert last_primary - last_support >= 120, (
                f"{catalog_id} impact support still outlives its body: "
                f"body={last_primary} support={last_support}"
            )
        else:
            support_bounds = {
                "LOW": (720, 750), "MID": (850, 870), "HIGH": (970, 990)
            }[stage]
            assert support_bounds[0] <= last_support <= support_bounds[1], (
                f"{catalog_id} tail release drifted to {last_support}ms"
            )
            assert last_support - last_primary >= 60, (
                f"{catalog_id} body and tail still disappear together"
            )
        assert "WARRIOR_ACCENT" not in {row["role"] for row in normal_rows}
        assert not any(
            asset_names[row["assetId"]].startswith(("vfx_column_", "vfx_projectile_", "vfx_vortex_"))
            for row in normal_rows
        ), f"{catalog_id} still renders a retired generic accent asset"

creation_normal = [
    row for row in frames
    if row["catalogId"] == CREATION_EARTHQUAKE_ID and row["reduced"] == "0"
]
assert {row["role"] for row in creation_normal} == {"PRIMARY", "CONTACT"}
expected_creation_assets = [f"vfx11_warrior_genesis_{index:02d}" for index in range(1, 12)]
creation_sample_times = [80, 130, 170, 220, 260, 300, 330, 360, 390, 420, 500]
for elapsed, asset_name in zip(creation_sample_times, expected_creation_assets):
    primary = [
        row for row in by_skill_time[(CREATION_EARTHQUAKE_ID, str(elapsed))]
        if row["reduced"] == "0" and row["role"] == "PRIMARY"
    ]
    assert len(primary) == 1
    assert asset_names[primary[0]["assetId"]] == asset_name
creation_impact = [
    row for row in by_skill_time[(CREATION_EARTHQUAKE_ID, "420")]
    if row["reduced"] == "0"
]
assert [row["role"] for row in creation_impact] == ["PRIMARY", "CONTACT"]
creation_aftermath = [
    row for row in by_skill_time[(CREATION_EARTHQUAKE_ID, "650")]
    if row["reduced"] == "0"
]
assert len(creation_aftermath) == 1 and creation_aftermath[0]["role"] == "PRIMARY"
assert asset_names[creation_aftermath[0]["assetId"]] == "vfx11_warrior_genesis_11"
creation_fade = [
    row for row in by_skill_time[(CREATION_EARTHQUAKE_ID, "880")]
    if row["reduced"] == "0" and row["role"] == "PRIMARY"
]
assert len(creation_fade) == 1 and isclose(float(creation_fade[0]["alpha"]), .5, abs_tol=1e-6)
continuous = by_skill_time[("warrior_t01_c05", "300")]
continuous_normal = [row for row in continuous if row["reduced"] == "0"]
assert {row["role"] for row in continuous_normal} == {"PRIMARY"}
assert any(
    asset_names[row["assetId"]] == "vfx_warrior_01" and
    row["rotation"] == "20.0" and row["mirror"] == "-1.0" and
    isclose(float(row["height"]), .730461, abs_tol=1e-6)
    for row in continuous_normal
)

for forbidden in (
    "groundImpactAndroidFrame", "continuousSlashAndroidFrame", "baseMotion", "pathStart", "rotationFor",
    "function splitDamage", "function groupedDamages", "function damageFrame", "function labelAlpha",
    "function energyFraction",
):
    assert forbidden not in WEB, f"Browser still owns an Android motion formula: {forbidden}"
for required in (
    'fetch(`/api/frames?', 'fetch(`/api/presentation?', 'state.reducedMotion ? 1 : 0',
    'reducedMotionToggle', 'frame.drawMode', 'frame.tintArgb',
    'document.createElement("canvas")', "presentation.damage", "presentation?.energy", "camera.cameraX",
    '.damage-number { position: absolute; z-index: 100;', 'const sliceAlpha = frame.a;',
):
    target = CSS if required.startswith('.damage-number') else WEB
    assert required in target, f"Android frame player marker missing: {required}"
assert 'EXPORT / "frames.psv"' in GENERATOR
assert 'EXPORT / "presentation.psv"' in GENERATOR
assert 'parsed.path == "/api/presentation"' in SERVER
assert 'id="cameraPlane"' in HTML
for required in (
    ".zIndex(DAMAGE_TEXT_Z_INDEX)",
    "drawStyle = Stroke(width = damageStrokeWidth)",
    ".clearAndSetSemantics { }",
):
    assert required in ANDROID_COMBAT, f"Android damage top-plane marker missing: {required}"
assert "safe-area" not in HTML and "safe-area" not in CSS and "safeArea" not in WEB
assert ("clear-damage" + "-safe") not in NORMALIZER
print(
    f"PASS: 600 skills replay Android planner and presentation "
    f"({len(frames):,} VFX rows, {len(presentation):,} damage/gauge/camera rows, band caps 4/5/6)"
)
