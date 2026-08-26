#!/usr/bin/env python3
"""Recover transparent VFX from a baked light-neutral checkerboard preview."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


def extract(source: Path, destination: Path) -> None:
    with Image.open(source) as opened:
        rgba = opened.convert("RGBA")

    rgb = np.asarray(rgba, dtype=np.float32)[..., :3]
    maximum = rgb.max(axis=2)
    minimum = rgb.min(axis=2)
    chroma = maximum - minimum
    luminance = rgb.mean(axis=2)

    # The generated preview checker uses nearly neutral values around 240/253.
    # Saturated cyan/gold and dark indigo VFX therefore separate cleanly.
    color_signal = np.clip((chroma - 2.0) / 50.0, 0.0, 1.0)
    dark_signal = np.clip((238.0 - luminance) / 118.0, 0.0, 1.0)
    alpha = np.maximum(color_signal, dark_signal)

    # Recover narrow neutral-white blade cores enclosed by colored edge light.
    seed = Image.fromarray(np.uint8(np.clip(alpha * 255.0, 0.0, 255.0)))
    support = seed.filter(ImageFilter.MaxFilter(7)).filter(ImageFilter.GaussianBlur(1.1))
    support_array = np.asarray(support, dtype=np.float32) / 255.0
    alpha = np.maximum(alpha, support_array * 0.74)
    alpha[alpha < 0.045] = 0.0

    result = np.dstack((rgb, np.uint8(np.clip(alpha * 255.0, 0.0, 255.0))))
    destination.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.uint8(result)).save(destination, optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    extract(args.source, args.destination)
    print(f"extracted={args.destination}")


if __name__ == "__main__":
    main()
