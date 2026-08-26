#!/usr/bin/env python3
"""Restore unattenuated generated frames while preserving approved locked cells."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

from PIL import Image


FRAME_WIDTH = 361
FRAME_HEIGHT = 160
COLUMNS = 4
ROWS = 4
EXPECTED_SIZE = (FRAME_WIDTH * COLUMNS, FRAME_HEIGHT * ROWS)
REPLACED_FRAME_INDICES = range(9, 15)  # F10-F15
LOCKED_FRAME_INDICES = (*range(9), 15)  # F01-F09 and F16
BOUNDARY_CLEAR_PX = 2


def frame_box(index: int) -> tuple[int, int, int, int]:
    left = (index % COLUMNS) * FRAME_WIDTH
    top = (index // COLUMNS) * FRAME_HEIGHT
    return left, top, left + FRAME_WIDTH, top + FRAME_HEIGHT


def frame_digest(frame: Image.Image) -> str:
    return hashlib.sha256(frame.tobytes()).hexdigest()


def mean_alpha_pct(frame: Image.Image) -> float:
    values = frame.getchannel("A").getdata()
    return 100.0 * sum(values) / (255.0 * FRAME_WIDTH * FRAME_HEIGHT)


def clear_cell_boundary(frame: Image.Image) -> Image.Image:
    cleaned = frame.copy()
    alpha = cleaned.getchannel("A")
    pixels = alpha.load()
    for y in range(FRAME_HEIGHT):
        for x in range(FRAME_WIDTH):
            if (
                x < BOUNDARY_CLEAR_PX
                or x >= FRAME_WIDTH - BOUNDARY_CLEAR_PX
                or y < BOUNDARY_CLEAR_PX
                or y >= FRAME_HEIGHT - BOUNDARY_CLEAR_PX
            ):
                pixels[x, y] = 0
    cleaned.putalpha(alpha)
    return cleaned


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("approved", type=Path, help="approved 1444x640 production sheet")
    parser.add_argument("generated", type=Path, help="unattenuated generated RGBA source sheet")
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()

    with Image.open(args.approved) as opened:
        approved = opened.convert("RGBA")
    if approved.size != EXPECTED_SIZE:
        raise SystemExit(f"approved sheet: expected {EXPECTED_SIZE}, got {approved.size}")

    with Image.open(args.generated) as opened:
        generated = opened.convert("RGBA")
    if generated.size != EXPECTED_SIZE:
        generated = generated.resize(EXPECTED_SIZE, Image.Resampling.LANCZOS)

    approved_frames = [approved.crop(frame_box(index)) for index in range(COLUMNS * ROWS)]
    generated_frames = [generated.crop(frame_box(index)) for index in range(COLUMNS * ROWS)]
    locked_digests = {
        index: frame_digest(approved_frames[index]) for index in LOCKED_FRAME_INDICES
    }

    revised_frames = list(approved_frames)
    for index in REPLACED_FRAME_INDICES:
        revised_frames[index] = clear_cell_boundary(generated_frames[index])
        print(
            f"F{index + 1:02d}: approved meanAlpha "
            f"{mean_alpha_pct(approved_frames[index]):.2f}% -> "
            f"intrinsic {mean_alpha_pct(revised_frames[index]):.2f}%"
        )

    revised = Image.new("RGBA", EXPECTED_SIZE, (0, 0, 0, 0))
    for index, frame in enumerate(revised_frames):
        revised.paste(frame, frame_box(index)[:2])

    for index, expected_digest in locked_digests.items():
        actual_digest = frame_digest(revised.crop(frame_box(index)))
        if actual_digest != expected_digest:
            raise SystemExit(f"locked F{index + 1:02d} changed unexpectedly")

    args.destination.parent.mkdir(parents=True, exist_ok=True)
    revised.save(args.destination, optimize=True)
    print(
        f"saved={args.destination}; F10-F15 use intrinsic source alpha; "
        "F01-F09 and F16 preserved exactly"
    )


if __name__ == "__main__":
    main()
