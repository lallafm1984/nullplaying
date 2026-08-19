#!/usr/bin/env python3
"""Fail-closed QA for the reviewed warrior non-slash vfx7 repair pack."""

from __future__ import annotations

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/simple/res/drawable-nodpi"
CANVAS = 1254
EXPECTED = (
    "vfx7_warrior_charge_anticipation.webp",
    "vfx7_warrior_charge_contact.webp",
    "vfx7_warrior_charge_debris.webp",
    "vfx7_warrior_charge_echo.webp",
    "vfx7_warrior_earth_anticipation.webp",
)


def main() -> None:
    signatures: set[bytes] = set()
    for name in EXPECTED:
        path = ASSETS / name
        if not path.is_file():
            raise SystemExit(f"missing reviewed warrior asset: {name}")
        with Image.open(path) as source:
            if source.size != (CANVAS, CANVAS) or source.mode != "RGBA":
                raise SystemExit(f"{name}: expected {CANVAS}px RGBA, got {source.size} {source.mode}")
            image = source.copy()

        alpha = image.getchannel("A")
        bbox = alpha.getbbox()
        if bbox is None:
            raise SystemExit(f"{name}: transparent asset")
        left, top, right, bottom = bbox
        bleed = min(left, top, CANVAS - right, CANVAS - bottom)
        if bleed < 40:
            raise SystemExit(f"{name}: effect touches the canvas edge ({bleed}px bleed)")

        alpha_values = list(alpha.getdata())
        coverage = sum(value > 24 for value in alpha_values) / len(alpha_values)
        if not .02 <= coverage <= .15:
            raise SystemExit(f"{name}: implausible visible coverage {coverage:.3%}")
        corners = ((0, 0), (CANVAS - 1, 0), (0, CANVAS - 1), (CANVAS - 1, CANVAS - 1))
        if any(alpha.getpixel(point) != 0 for point in corners):
            raise SystemExit(f"{name}: non-transparent corner")

        visible = [pixel for pixel in image.getdata() if pixel[3] > 24]
        greenish = sum(
            1 for red, green, blue, _ in visible
            if green > red * 1.4 and green > blue * 1.2
        ) / len(visible)
        if greenish > .01:
            raise SystemExit(f"{name}: chroma spill ratio {greenish:.3%}")

        signature = alpha.tobytes()
        if signature in signatures:
            raise SystemExit(f"{name}: duplicate alpha silhouette")
        signatures.add(signature)

    print("PASS: 5 warrior vfx7 assets are RGBA, padded, sparse, chroma-clean, and silhouette-distinct")


if __name__ == "__main__":
    main()
