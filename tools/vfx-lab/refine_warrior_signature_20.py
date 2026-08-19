#!/usr/bin/env python3
"""Create deterministic, web-ready refinements for the warrior signature set.

Generated FrameSmith sources stay untouched. Every sheet is copied to a separate
directory, and only explicitly reviewed frame defects are corrected here.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


CELL_WIDTH = 361
CELL_HEIGHT = 160
COLUMNS = 4
ROWS = 4
EXPECTED_SIZE = (CELL_WIDTH * COLUMNS, CELL_HEIGHT * ROWS)

ALPHA_DECAY = {
    # Frame 10 must separate immediately from the unique F09 impact peak.
    "warrior_t08_c02": (0.72, 0.55, 0.42, 0.30, 0.18, 0.08, 0.0),
    "warrior_t11_c02": (0.72, 0.55, 0.42, 0.30, 0.18, 0.08, 0.0),
}


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


def compose(frames: list[Image.Image]) -> Image.Image:
    sheet = Image.new("RGBA", EXPECTED_SIZE, (0, 0, 0, 0))
    for index, frame in enumerate(frames):
        sheet.alpha_composite(
            frame,
            ((index % COLUMNS) * CELL_WIDTH, (index // COLUMNS) * CELL_HEIGHT),
        )
    return sheet


def multiply_alpha(frame: Image.Image, factor: float) -> Image.Image:
    red, green, blue, alpha = frame.split()
    alpha = alpha.point(lambda value: round(value * factor))
    return Image.merge("RGBA", (red, green, blue, alpha))


def translate(frame: Image.Image, x: int, y: int) -> Image.Image:
    translated = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    translated.alpha_composite(frame, (x, y))
    return translated


def refine(catalog_id: str, frames: list[Image.Image]) -> list[str]:
    notes: list[str] = []

    if catalog_id == "warrior_t04_c03":
        # F05 contained a detached generator residue touching the cell floor.
        frame = frames[4].copy()
        frame.paste((0, 0, 0, 0), (0, CELL_HEIGHT - 10, CELL_WIDTH, CELL_HEIGHT))
        frames[4] = frame
        notes.append("F05 bottom 10 px residue cleared")

    if catalog_id == "warrior_t05_c02":
        # Generated F06-F07 jumped back to the upper-right after the descending
        # F05 arc. Continue the reviewed F05 silhouette toward the F08 impact.
        frames[5] = translate(frames[4], 18, 12)
        frames[6] = translate(frames[4], 36, 24)
        notes.append("F06-F07 replaced with continuous descending translations")

    factors = ALPHA_DECAY.get(catalog_id)
    if factors:
        for frame_index, factor in enumerate(factors, start=9):
            frames[frame_index] = multiply_alpha(frames[frame_index], factor)
        notes.append("F10-F16 alpha decay tightened")

    return notes


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    sources = sorted(args.source_dir.glob("warrior_*.png"))
    if len(sources) != 19:
        raise SystemExit(f"expected 19 source sheets, found {len(sources)}")

    for source in sources:
        catalog_id = source.stem
        sheet = Image.open(source).convert("RGBA")
        if sheet.size != EXPECTED_SIZE:
            raise SystemExit(f"{source}: expected {EXPECTED_SIZE}, found {sheet.size}")
        frames = split_frames(sheet)
        notes = refine(catalog_id, frames)
        destination = args.output_dir / source.name
        compose(frames).save(destination, optimize=True)
        print(f"{catalog_id}: {', '.join(notes) if notes else 'copied unchanged'}")


if __name__ == "__main__":
    main()
