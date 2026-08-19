#!/usr/bin/env python3
"""Validate the cohesive warrior VFX8 raster pack before Android export."""

from __future__ import annotations

import hashlib
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
DRAWABLE = ROOT / "app/src/simple/res/drawable-nodpi"
ASSETS = (
    "vfx8_warrior_slash_primary.webp",
    "vfx8_warrior_slash_contact.webp",
    "vfx8_warrior_slash_echo.webp",
    "vfx8_warrior_heavy_primary.webp",
    "vfx8_warrior_heavy_anticipation.webp",
    "vfx8_warrior_charge_primary.webp",
    "vfx8_warrior_earth_primary.webp",
)


def main() -> None:
    alpha_digests: set[str] = set()
    for name in ASSETS:
        path = DRAWABLE / name
        if not path.is_file():
            raise SystemExit(f"missing warrior VFX8 asset: {path}")
        image = Image.open(path).convert("RGBA")
        width, height = image.size
        if min(width, height) < 900:
            raise SystemExit(f"{name}: undersized {width}x{height}")

        alpha = image.getchannel("A")
        extrema = alpha.getextrema()
        if extrema[0] != 0 or extrema[1] != 255:
            raise SystemExit(f"{name}: expected transparent and opaque pixels, got {extrema}")
        bbox = alpha.getbbox()
        if bbox is None:
            raise SystemExit(f"{name}: empty alpha plane")
        left, top, right, bottom = bbox
        padding = min(left, top, width - right, height - bottom)
        if padding < 12:
            raise SystemExit(f"{name}: alpha touches crop edge (minimum padding {padding}px)")

        pixels = list(image.getdata())
        visible = [pixel for pixel in pixels if pixel[3] >= 16]
        coverage = len(visible) / len(pixels)
        if not 0.025 <= coverage <= 0.50:
            raise SystemExit(f"{name}: implausible visible coverage {coverage:.3f}")
        green_spill = sum(
            1 for red, green, blue, pixel_alpha in visible
            if pixel_alpha >= 64 and green > red * 1.45 and green > blue * 1.25 and green > 120
        ) / max(1, len(visible))
        if green_spill > 0.01:
            raise SystemExit(f"{name}: chroma spill {green_spill:.3%}")

        digest = hashlib.sha256(alpha.tobytes()).hexdigest()
        if digest in alpha_digests:
            raise SystemExit(f"{name}: duplicate alpha silhouette")
        alpha_digests.add(digest)
        print(
            f"PASS {name}: {width}x{height}, coverage={coverage:.3f}, "
            f"padding={padding}px, green_spill={green_spill:.3%}"
        )

    print(f"PASS warrior VFX8 pack: {len(ASSETS)} cohesive RGBA assets")


if __name__ == "__main__":
    main()
