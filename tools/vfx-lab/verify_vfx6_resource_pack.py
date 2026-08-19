#!/usr/bin/env python3
"""Fail-closed QA for every vfx6 sprite referenced by the production resolver."""

from __future__ import annotations

from pathlib import Path
import re

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/simple/res/drawable-nodpi"
CANVAS = 512
MIN_BLEED = 48
ROLE_MAPPING = ROOT / "app/src/simple/java/com/alarmquest/ui/SkillVfxAssetsBandRoles.kt"


def expected_names() -> list[str]:
    referenced = set(re.findall(r"R\.drawable\.(vfx6_[a-z0-9_]+)", ROLE_MAPPING.read_text()))
    return sorted(f"{name}.webp" for name in referenced)


def main() -> None:
    expected = expected_names()
    if not expected:
        raise SystemExit("production resolver references no vfx6 assets")
    missing = [name for name in expected if not (ASSETS / name).is_file()]
    unexpected = sorted(path.name for path in ASSETS.glob("vfx6_*.webp") if path.name not in expected)
    if missing or unexpected:
        raise SystemExit(f"vfx6 closure failed: missing={missing}, unexpected={unexpected}")

    visible_signatures: dict[tuple[str, str], bytes] = {}
    center_stats: list[tuple[str, float, float]] = []
    for name in expected:
        path = ASSETS / name
        with Image.open(path) as source:
            if source.size != (CANVAS, CANVAS) or source.mode != "RGBA":
                raise SystemExit(f"{name}: expected 512x512 RGBA, got {source.size} {source.mode}")
            image = source.copy()
        alpha = image.getchannel("A")
        bbox = alpha.getbbox()
        if bbox is None:
            raise SystemExit(f"{name}: transparent asset")
        left, top, right, bottom = bbox
        bleed = min(left, top, CANVAS - right, CANVAS - bottom)
        if bleed < MIN_BLEED:
            raise SystemExit(f"{name}: bleed {bleed}px < {MIN_BLEED}px")

        role = name.removesuffix(".webp").rsplit("_", 1)[1]
        # Damage is rendered above VFX. Assets may cross the center naturally, but never with a
        # dense matte or the former exact hard-cleared rectangle, which looked like a square frame.
        safe_box = (round(CANVAS * .34), round(CANVAS * .36), round(CANVAS * .66), round(CANVAS * .72))
        center = alpha.crop(safe_box)
        center_values = list(center.getdata())
        center_mean = sum(center_values) / len(center_values)
        center_coverage = sum(value > 24 for value in center_values) / len(center_values)
        center_stats.append((name, center_mean, center_coverage))

        # Reject a rectangular hard hole: an exact all-zero center combined with visible pixels
        # immediately across every side is the signature left by the retired center-hole tool.
        left, top, right, bottom = safe_box
        border = 8
        strips = (
            alpha.crop((left - border, top, right + border, top + border)),
            alpha.crop((left - border, bottom - border, right + border, bottom)),
            alpha.crop((left - border, top, left + border, bottom)),
            alpha.crop((right - border, top, right + border, bottom)),
        )
        if center.getbbox() is None and all(strip.getbbox() is not None for strip in strips):
            raise SystemExit(f"{name}: rectangular hard-cleared damage-safe hole")

        # Chroma-key/matte cleanup: fully transparent pixels must not retain a dark RGB plate.
        rgba = image.load()
        dark_hidden = 0
        hidden = 0
        for y in range(CANVAS):
            for x in range(CANVAS):
                red, green, blue, pixel_alpha = rgba[x, y]
                if pixel_alpha <= 2:
                    hidden += 1
                    if max(red, green, blue) <= 8 and red + green + blue > 0:
                        dark_hidden += 1
        if hidden and dark_hidden / hidden > .01:
            raise SystemExit(f"{name}: hidden black matte ratio {dark_hidden / hidden:.3%}")

        # Long axis-aligned alpha runs indicate a crop/frame edge rather than an organic effect.
        threshold = 20
        max_horizontal = max(
            (sum(1 for x in range(CANVAS) if alpha.getpixel((x, y)) > threshold) for y in range(CANVAS)),
            default=0,
        )
        max_vertical = max(
            (sum(1 for y in range(CANVAS) if alpha.getpixel((x, y)) > threshold) for x in range(CANVAS)),
            default=0,
        )
        if max(max_horizontal, max_vertical) > CANVAS - MIN_BLEED * 2:
            raise SystemExit(f"{name}: hard internal axis line h={max_horizontal} v={max_vertical}")

        family_key = "_".join(name.removesuffix(".webp").split("_")[1:3])
        signature = alpha.tobytes()
        key = (family_key, role)
        same_family_role = next(
            (
                existing
                for existing, value in visible_signatures.items()
                if existing[0] == family_key and value == signature
            ),
            None,
        )
        if same_family_role is not None:
            raise SystemExit(f"{name}: alpha silhouette duplicates {same_family_role}")
        visible_signatures[key] = signature

    densest = max(center_stats, key=lambda value: value[1])
    print(
        f"PASS: {len(expected)} active vfx6 assets are 512 RGBA with bleed, no matte/frame, and role uniqueness; "
        f"center occupancy is informational only (max {densest[0]} mean={densest[1]:.2f} coverage={densest[2]:.3%})",
    )


if __name__ == "__main__":
    main()
