#!/usr/bin/env python3
"""Build a temporally stable top-down slash sheet from a FrameSmith source.

The FrameSmith sheet supplies the authored layered steel texture and final impact.
Frames 2-15 reuse one canonical geometry and animate only a descending alpha
window, preventing frame-to-frame redraw wobble. Frame 16 adds only the lower
contact region from the generated impact frame.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image


FRAME_WIDTH = 361
FRAME_HEIGHT = 160
COLUMNS = 4
ROWS = 4
FRAME_COUNT = COLUMNS * ROWS


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def smoothstep(edge0: float, edge1: float, value: float) -> float:
    if edge0 == edge1:
        return 1.0 if value >= edge1 else 0.0
    unit = clamp((value - edge0) / (edge1 - edge0))
    return unit * unit * (3.0 - 2.0 * unit)


def split_frames(sheet: Image.Image) -> list[Image.Image]:
    return [
        sheet.crop(
            (
                (index % COLUMNS) * FRAME_WIDTH,
                (index // COLUMNS) * FRAME_HEIGHT,
                (index % COLUMNS + 1) * FRAME_WIDTH,
                (index // COLUMNS + 1) * FRAME_HEIGHT,
            )
        )
        for index in range(FRAME_COUNT)
    ]


def descending_window(
    canonical: Image.Image,
    *,
    head_y: float,
    trail_length: float,
    strength: float,
) -> Image.Image:
    output = canonical.copy()
    source_alpha = canonical.getchannel("A")
    mask = Image.new("L", canonical.size, 0)
    mask_pixels = mask.load()
    alpha_pixels = source_alpha.load()
    trailing_edge = head_y - trail_length

    for y in range(FRAME_HEIGHT):
        trail_in = smoothstep(trailing_edge, trailing_edge + 12.0, float(y))
        tip_out = 1.0 - smoothstep(head_y - 2.0, head_y + 5.0, float(y))
        row_weight = clamp(trail_in * tip_out * strength)
        for x in range(FRAME_WIDTH):
            mask_pixels[x, y] = round(alpha_pixels[x, y] * row_weight)

    output.putalpha(mask)
    return output


def lower_impact_patch(impact: Image.Image) -> Image.Image:
    output = impact.copy()
    source_alpha = impact.getchannel("A")
    mask = Image.new("L", impact.size, 0)
    mask_pixels = mask.load()
    alpha_pixels = source_alpha.load()
    center_x = FRAME_WIDTH * 0.61
    center_y = FRAME_HEIGHT * 0.69

    for y in range(FRAME_HEIGHT):
        vertical = smoothstep(FRAME_HEIGHT * 0.51, FRAME_HEIGHT * 0.64, float(y))
        for x in range(FRAME_WIDTH):
            ellipse = ((x - center_x) / (FRAME_WIDTH * 0.24)) ** 2 + (
                (y - center_y) / (FRAME_HEIGHT * 0.34)
            ) ** 2
            radial = 1.0 - smoothstep(0.72, 1.0, ellipse)
            mask_pixels[x, y] = round(alpha_pixels[x, y] * vertical * radial)

    output.putalpha(mask)
    return output


def compose_sheet(frames: list[Image.Image]) -> Image.Image:
    sheet = Image.new("RGBA", (FRAME_WIDTH * COLUMNS, FRAME_HEIGHT * ROWS))
    for index, frame in enumerate(frames):
        sheet.alpha_composite(
            frame,
            ((index % COLUMNS) * FRAME_WIDTH, (index // COLUMNS) * FRAME_HEIGHT),
        )
    return sheet


def stabilize(source: Path, output: Path) -> dict[str, object]:
    sheet = Image.open(source).convert("RGBA")
    expected_size = (FRAME_WIDTH * COLUMNS, FRAME_HEIGHT * ROWS)
    if sheet.size != expected_size:
        raise ValueError(f"expected {expected_size}, got {sheet.size}")

    frames = split_frames(sheet)
    canonical = frames[11]
    impact = frames[15]
    stabilized: list[Image.Image] = [Image.new("RGBA", canonical.size)]

    for index in range(1, 15):
        progress = index / 14.0
        head_y = FRAME_HEIGHT * (0.08 + 0.82 * progress)
        trail_length = FRAME_HEIGHT * (0.22 + 0.24 * min(1.0, progress * 2.1))
        strength = 0.58 + 0.42 * progress
        stabilized.append(
            descending_window(
                canonical,
                head_y=head_y,
                trail_length=trail_length,
                strength=strength,
            )
        )

    final_motion = descending_window(
        canonical,
        head_y=FRAME_HEIGHT * 0.94,
        trail_length=FRAME_HEIGHT * 0.56,
        strength=1.0,
    )
    final_motion.alpha_composite(lower_impact_patch(impact))
    stabilized.append(final_motion)

    result = compose_sheet(stabilized)
    output.parent.mkdir(parents=True, exist_ok=True)
    result.save(output, format="PNG", optimize=True)
    return {
        "source": str(source),
        "output": str(output),
        "size": list(result.size),
        "mode": result.mode,
        "canonicalFrame": 12,
        "impactFrame": 16,
        "method": "fixed F12 geometry plus descending alpha window; lower F16 impact patch only",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(stabilize(args.source, args.output), ensure_ascii=False))


if __name__ == "__main__":
    main()
