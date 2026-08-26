#!/usr/bin/env python3
"""Recover straight-alpha RGBA VFX from a uniform black generation matte."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image


def extract(source: Path, destination: Path, threshold: float, opacity_gamma: float) -> None:
    with Image.open(source) as opened:
        rgb_image = opened.convert("RGB")

    rgb = np.asarray(rgb_image, dtype=np.float32)
    signal = rgb.max(axis=2)
    normalized = np.clip((signal - threshold) / (255.0 - threshold), 0.0, 1.0)
    alpha = np.power(normalized, opacity_gamma)
    alpha[signal <= threshold] = 0.0

    # Convert black-premultiplied RGB back to straight color. Re-compositing the
    # result over black reproduces the generated colors without white fringes.
    safe_alpha = np.maximum(alpha, 1.0 / 255.0)
    straight_rgb = np.clip(rgb / safe_alpha[..., None], 0.0, 255.0)
    straight_rgb[alpha == 0.0] = 0.0

    rgba = np.dstack((straight_rgb, np.uint8(np.clip(alpha * 255.0, 0.0, 255.0))))
    destination.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.uint8(rgba)).save(destination, optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--threshold", type=float, default=8.0)
    parser.add_argument("--opacity-gamma", type=float, default=0.68)
    args = parser.parse_args()
    extract(args.source, args.destination, args.threshold, args.opacity_gamma)
    print(
        f"extracted={args.destination} threshold={args.threshold:g} "
        f"opacity_gamma={args.opacity_gamma:g}"
    )


if __name__ == "__main__":
    main()
