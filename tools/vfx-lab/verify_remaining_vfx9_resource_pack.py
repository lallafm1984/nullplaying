#!/usr/bin/env python3
"""Fail-closed QA for the focused remaining-class vfx9 replacement pack."""

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/simple/res/drawable-nodpi"
EXPECTED = (
    "vfx9_rogue_blade_primary_a.webp",
    "vfx9_rogue_blade_primary_b.webp",
    "vfx9_rogue_blade_contact.webp",
    "vfx9_rogue_wire_contact.webp",
    "vfx9_ranger_precision_anticipation.webp",
    "vfx9_ranger_precision_contact.webp",
    "vfx9_ranger_precision_tail.webp",
    "vfx9_ranger_celestial_contact.webp",
    "vfx9_paladin_judgment_contact.webp",
)


def main() -> None:
    missing = [name for name in EXPECTED if not (ASSETS / name).is_file()]
    if missing:
        raise SystemExit(f"vfx9 pack missing: {missing}")
    signatures: dict[bytes, str] = {}
    for name in EXPECTED:
        with Image.open(ASSETS / name) as source:
            if source.mode != "RGBA":
                raise SystemExit(f"{name}: expected RGBA, got {source.mode}")
            if min(source.size) < 900:
                raise SystemExit(f"{name}: undersized canvas {source.size}")
            image = source.copy()
        alpha = image.getchannel("A")
        bbox = alpha.getbbox()
        if bbox is None:
            raise SystemExit(f"{name}: fully transparent")
        width, height = image.size
        coverage = sum(value > 20 for value in alpha.getdata()) / (width * height)
        if not .003 <= coverage <= .48:
            raise SystemExit(f"{name}: suspicious alpha coverage {coverage:.3%}")
        edge = 2
        border = (
            alpha.crop((0, 0, width, edge)),
            alpha.crop((0, height - edge, width, height)),
            alpha.crop((0, 0, edge, height)),
            alpha.crop((width - edge, 0, width, height)),
        )
        border_pixels = sum(sum(value > 20 for value in strip.getdata()) for strip in border)
        if border_pixels > (width + height) * .08:
            raise SystemExit(f"{name}: excessive visible edge pixels {border_pixels}")
        signature = alpha.tobytes()
        if signature in signatures:
            raise SystemExit(f"{name}: alpha silhouette duplicates {signatures[signature]}")
        signatures[signature] = name
    print(f"PASS: {len(EXPECTED)} vfx9 assets are large RGBA sprites with organic margins and unique silhouettes")


if __name__ == "__main__":
    main()
