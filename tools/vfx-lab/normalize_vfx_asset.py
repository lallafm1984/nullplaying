#!/usr/bin/env python3
"""Normalize generated VFX art into Android's lossless 768px alpha contract."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


CANVAS = 768
SAFE_BLEED = 72


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--max-width", type=int, default=CANVAS - SAFE_BLEED * 2)
    parser.add_argument("--max-height", type=int, default=CANVAS - SAFE_BLEED * 2)
    args = parser.parse_args()

    image = Image.open(args.input).convert("RGBA")
    alpha = image.getchannel("A")
    bbox = alpha.getbbox()
    if bbox is None:
        raise SystemExit("input has no visible pixels")
    image = image.crop(bbox)
    scale = min(args.max_width / image.width, args.max_height / image.height)
    size = (
        max(1, round(image.width * scale)),
        max(1, round(image.height * scale)),
    )
    image = image.resize(size, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.alpha_composite(image, ((CANVAS - size[0]) // 2, (CANVAS - size[1]) // 2))
    alpha = canvas.getchannel("A")
    final_bbox = alpha.getbbox()
    if final_bbox is None:
        raise SystemExit("normalization removed every visible pixel")
    left, top, right, bottom = final_bbox
    bleed = min(left, top, CANVAS - right, CANVAS - bottom)
    if bleed < SAFE_BLEED:
        raise SystemExit(f"safe bleed failed: {bleed}px < {SAFE_BLEED}px")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(args.output, format="WEBP", lossless=True, method=6)
    print(f"{args.output}: size={CANVAS}x{CANVAS} bbox={final_bbox} bleed={bleed}")


if __name__ == "__main__":
    main()
