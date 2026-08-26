#!/usr/bin/env python3
"""Create web-ready 16-frame signature sheets without mutating generated sources."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


CELL_WIDTH = 361
CELL_HEIGHT = 160
COLUMNS = 4
ROWS = 4
EXPECTED_SIZE = (CELL_WIDTH * COLUMNS, CELL_HEIGHT * ROWS)
LATE_ALPHA_FACTORS = (0.62, 0.44, 0.30, 0.18, 0.10, 0.04, 0.0)
BOUNDARY_CLEAR_PX = 2
VISIBLE_ALPHA_THRESHOLD = 12


def split_frames(sheet: Image.Image) -> list[Image.Image]:
    return [
        sheet.crop(
            (
                (index % COLUMNS) * CELL_WIDTH,
                (index // COLUMNS) * CELL_HEIGHT,
                (index % COLUMNS + 1) * CELL_WIDTH,
                (index // COLUMNS + 1) * CELL_HEIGHT,
            )
        )
        for index in range(COLUMNS * ROWS)
    ]


def multiply_alpha(frame: Image.Image, factor: float) -> Image.Image:
    red, green, blue, alpha = frame.split()
    alpha = alpha.point(lambda value: round(value * factor))
    return Image.merge("RGBA", (red, green, blue, alpha))


def clear_cell_boundary(frame: Image.Image) -> Image.Image:
    cleaned = frame.copy()
    alpha = cleaned.getchannel("A")
    pixels = alpha.load()
    for y in range(CELL_HEIGHT):
        for x in range(CELL_WIDTH):
            if (
                x < BOUNDARY_CLEAR_PX
                or x >= CELL_WIDTH - BOUNDARY_CLEAR_PX
                or y < BOUNDARY_CLEAR_PX
                or y >= CELL_HEIGHT - BOUNDARY_CLEAR_PX
            ):
                pixels[x, y] = 0
    cleaned.putalpha(alpha)
    return cleaned


def alpha_metrics(frame: Image.Image) -> tuple[float, float]:
    values = list(frame.getchannel("A").getdata())
    area = len(values)
    mean = sum(values) / (255.0 * area)
    occupancy = sum(value > VISIBLE_ALPHA_THRESHOLD for value in values) / area
    return mean, occupancy


def attenuate_until(
    frame: Image.Image,
    *,
    max_mean: float,
    max_occupancy: float,
) -> Image.Image:
    adjusted = frame
    for _ in range(24):
        mean, occupancy = alpha_metrics(adjusted)
        if mean < max_mean and occupancy <= max_occupancy:
            return adjusted
        adjusted = multiply_alpha(adjusted, 0.86)
    return adjusted


def compose(frames: list[Image.Image]) -> Image.Image:
    sheet = Image.new("RGBA", EXPECTED_SIZE, (0, 0, 0, 0))
    for index, frame in enumerate(frames):
        sheet.alpha_composite(
            frame,
            ((index % COLUMNS) * CELL_WIDTH, (index // COLUMNS) * CELL_HEIGHT),
        )
    return sheet


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--expected-count", type=int, default=20)
    args = parser.parse_args()

    sources = sorted(args.source_dir.glob(f"{args.prefix}_t??_c??.png"))
    if len(sources) != args.expected_count:
        raise SystemExit(
            f"expected {args.expected_count} source sheets for {args.prefix}, found {len(sources)}"
        )
    args.output_dir.mkdir(parents=True, exist_ok=True)

    for source in sources:
        with Image.open(source) as opened:
            sheet = opened.convert("RGBA")
        if sheet.size != EXPECTED_SIZE:
            sheet = sheet.resize(EXPECTED_SIZE, Image.Resampling.LANCZOS)
        frames = [clear_cell_boundary(frame) for frame in split_frames(sheet)]
        frames[0] = multiply_alpha(frames[0], 0.10)
        peak_mean, peak_occupancy = alpha_metrics(frames[8])
        for build_index in range(1, 8):
            frames[build_index] = attenuate_until(
                frames[build_index],
                max_mean=peak_mean * 0.985,
                max_occupancy=peak_occupancy + 0.02,
            )
        for frame_index, factor in enumerate(LATE_ALPHA_FACTORS, start=9):
            frames[frame_index] = multiply_alpha(frames[frame_index], factor)
        previous_mean = peak_mean * 0.65
        previous_occupancy = peak_occupancy + 0.03
        for frame_index in range(9, 16):
            frames[frame_index] = attenuate_until(
                frames[frame_index],
                max_mean=previous_mean,
                max_occupancy=previous_occupancy,
            )
            current_mean, current_occupancy = alpha_metrics(frames[frame_index])
            previous_mean = current_mean
            previous_occupancy = current_occupancy
        destination = args.output_dir / source.name
        compose(frames).save(destination, optimize=True)
        print(
            f"{source.stem}: {sheet.size}; F01 alpha x0.10; "
            f"F10-F16 decay {LATE_ALPHA_FACTORS}; boundary {BOUNDARY_CLEAR_PX}px"
        )


if __name__ == "__main__":
    main()
