#!/usr/bin/env python3
"""Fail-closed QA for the final class-cohesion vfx10 replacement pack."""

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/simple/res/drawable-nodpi"
EXPECTED = (
    "vfx10_ranger_volley_contact.webp",
    "vfx10_ranger_volley_tail.webp",
    "vfx10_ranger_volley_anticipation.webp",
    "vfx10_cleric_light_tail.webp",
    "vfx10_cleric_exorcism_tail.webp",
    "vfx10_paladin_judgment_tail.webp",
    "vfx10_paladin_judgment_anticipation.webp",
    "vfx10_paladin_judgment_echo.webp",
)


def main() -> None:
    missing = [name for name in EXPECTED if not (ASSETS / name).is_file()]
    if missing:
        raise SystemExit(f"vfx10 pack missing: {missing}")
    signatures: dict[bytes, str] = {}
    for name in EXPECTED:
        with Image.open(ASSETS / name) as source:
            if source.mode != "RGBA":
                raise SystemExit(f"{name}: expected RGBA, got {source.mode}")
            if source.size != (768, 768):
                raise SystemExit(f"{name}: unexpected canvas {source.size}")
            image = source.copy()
        alpha = image.getchannel("A")
        bbox = alpha.getbbox()
        if bbox is None:
            raise SystemExit(f"{name}: fully transparent")
        width, height = image.size
        coverage = sum(value > 20 for value in alpha.getdata()) / (width * height)
        if not .003 <= coverage <= .35:
            raise SystemExit(f"{name}: suspicious alpha coverage {coverage:.3%}")
        border = 8
        strips = (
            alpha.crop((0, 0, width, border)),
            alpha.crop((0, height - border, width, height)),
            alpha.crop((0, 0, border, height)),
            alpha.crop((width - border, 0, width, height)),
        )
        if any(strip.getbbox() is not None for strip in strips):
            raise SystemExit(f"{name}: visible pixels touch the canvas edge")
        signature = alpha.tobytes()
        if signature in signatures:
            raise SystemExit(f"{name}: alpha silhouette duplicates {signatures[signature]}")
        signatures[signature] = name
    print(f"PASS: {len(EXPECTED)} vfx10 assets are transparent 768px sprites with safe margins and unique silhouettes")


if __name__ == "__main__":
    main()
