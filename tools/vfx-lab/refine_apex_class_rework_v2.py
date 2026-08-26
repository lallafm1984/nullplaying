#!/usr/bin/env python3
"""Refine the rejected apex-class batch against the Lv.75-95 warrior rhythm.

The generated source and matte-recovered alpha source remain untouched.  This
pass moves the generated unique impact to F09, keeps a broad residual readable
through F15, clears F16, and suppresses neutral outer halos.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


CELL_WIDTH = 361
CELL_HEIGHT = 160
COLUMNS = 4
ROWS = 4
EXPECTED_SIZE = (CELL_WIDTH * COLUMNS, CELL_HEIGHT * ROWS)
BOUNDARY_CLEAR_PX = 6
VISIBLE_ALPHA = 12
LATE_MEAN_RATIOS = (0.62, 0.58, 0.54, 0.50, 0.46, 0.42)
PEAK_WIDTH_RANGE = (0.86, 0.96)
PEAK_HEIGHT_RANGE = (0.76, 0.92)


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


def mean_alpha(frame: Image.Image) -> float:
    return float(np.asarray(frame.getchannel("A"), dtype=np.float32).mean() / 255.0)


def visible_occupancy(frame: Image.Image) -> float:
    alpha = np.asarray(frame.getchannel("A"), dtype=np.uint8)
    return float(np.mean(alpha > VISIBLE_ALPHA))


def visible_box(frame: Image.Image) -> tuple[int, int, int, int] | None:
    alpha = np.asarray(frame.getchannel("A"), dtype=np.uint8)
    ys, xs = np.where(alpha > VISIBLE_ALPHA)
    if xs.size == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max() + 1), int(ys.max() + 1)


def multiply_alpha(frame: Image.Image, factor: float) -> Image.Image:
    result = frame.copy()
    alpha = np.asarray(result.getchannel("A"), dtype=np.float32)
    result.putalpha(Image.fromarray(np.uint8(np.clip(alpha * factor, 0.0, 255.0))))
    return result


def match_mean_alpha(frame: Image.Image, target: float) -> Image.Image:
    if target <= 0.0 or mean_alpha(frame) <= 0.0:
        return Image.new("RGBA", frame.size, (0, 0, 0, 0))
    low = 0.0
    high = 1.0
    while mean_alpha(multiply_alpha(frame, high)) < target and high < 64.0:
        high *= 2.0
    for _ in range(18):
        middle = (low + high) / 2.0
        if mean_alpha(multiply_alpha(frame, middle)) < target:
            low = middle
        else:
            high = middle
    return multiply_alpha(frame, high)


def clear_boundary(frame: Image.Image) -> Image.Image:
    result = frame.copy()
    alpha = np.asarray(result.getchannel("A"), dtype=np.uint8).copy()
    alpha[:BOUNDARY_CLEAR_PX, :] = 0
    alpha[-BOUNDARY_CLEAR_PX:, :] = 0
    alpha[:, :BOUNDARY_CLEAR_PX] = 0
    alpha[:, -BOUNDARY_CLEAR_PX:] = 0
    result.putalpha(Image.fromarray(alpha))
    return result


def suppress_neutral_halo(frame: Image.Image) -> Image.Image:
    result = frame.copy()
    rgba = np.asarray(result, dtype=np.uint8).copy()
    rgb = rgba[:, :, :3].astype(np.int16)
    alpha = rgba[:, :, 3]
    eroded = np.asarray(
        Image.fromarray(alpha).filter(ImageFilter.MinFilter(5)), dtype=np.uint8
    )
    outer_edge = (alpha > 0) & (eroded < 8)
    neutral_bright = (rgb.min(axis=2) > 185) & (
        rgb.max(axis=2) - rgb.min(axis=2) < 52
    )
    rgba[:, :, 3][outer_edge & neutral_bright] = 0
    return Image.fromarray(rgba)


def scale_about_center(frame: Image.Image, scale_x: float, scale_y: float) -> Image.Image:
    width = max(1, round(CELL_WIDTH * scale_x))
    height = max(1, round(CELL_HEIGHT * scale_y))
    scaled = frame.resize((width, height), Image.Resampling.LANCZOS)
    result = Image.new("RGBA", (CELL_WIDTH, CELL_HEIGHT), (0, 0, 0, 0))
    if width <= CELL_WIDTH and height <= CELL_HEIGHT:
        result.alpha_composite(
            scaled, ((CELL_WIDTH - width) // 2, (CELL_HEIGHT - height) // 2)
        )
        return result
    left = max(0, (width - CELL_WIDTH) // 2)
    top = max(0, (height - CELL_HEIGHT) // 2)
    cropped = scaled.crop((left, top, left + CELL_WIDTH, top + CELL_HEIGHT))
    result.alpha_composite(cropped)
    return result


def normalize_peak_framing(frames: list[Image.Image]) -> list[Image.Image]:
    box = visible_box(frames[8])
    if box is None:
        return frames
    width = (box[2] - box[0]) / CELL_WIDTH
    height = (box[3] - box[1]) / CELL_HEIGHT
    scale_x = 1.0
    scale_y = 1.0
    if width < PEAK_WIDTH_RANGE[0]:
        # Generated paladin cuts are often tall but too icon-like.  Stretch the
        # attack direction without increasing vertical coverage or painting a
        # full-cell panel.
        scale_x = min(2.20, PEAK_WIDTH_RANGE[0] / max(width, 0.01))
    elif width > PEAK_WIDTH_RANGE[1]:
        scale_x = PEAK_WIDTH_RANGE[1] / width
    if height < PEAK_HEIGHT_RANGE[0]:
        scale_y = min(1.18, PEAK_HEIGHT_RANGE[0] / max(height, 0.01))
    elif height > PEAK_HEIGHT_RANGE[1]:
        scale_y = PEAK_HEIGHT_RANGE[1] / height
    if abs(scale_x - 1.0) < 0.005 and abs(scale_y - 1.0) < 0.005:
        return frames
    return [scale_about_center(frame, scale_x, scale_y) for frame in frames]


def enforce_energy_order(frames: list[Image.Image]) -> list[Image.Image]:
    """Keep thresholded occupancy from reversing the authored energy curve."""
    result = list(frames)
    peak = result[8]
    while (
        mean_alpha(result[7]) >= mean_alpha(peak)
        or visible_occupancy(result[7]) > visible_occupancy(peak) + 0.015
    ):
        result[7] = multiply_alpha(result[7], 0.90)

    previous = peak
    for index in range(9, 15):
        target_mean = mean_alpha(result[index])
        if visible_occupancy(result[index]) > visible_occupancy(previous) + 0.005:
            result[index] = match_mean_alpha(previous, target_mean)
        while visible_occupancy(result[index]) > visible_occupancy(previous) + 0.005:
            result[index] = multiply_alpha(result[index], 0.94)
        previous = result[index]
    return result


def residual_sources(frames: list[Image.Image], peak_index: int) -> list[Image.Image]:
    candidates = [
        frame
        for frame in frames[peak_index + 1 : 15]
        if mean_alpha(frame) > 0.001 and visible_box(frame) is not None
    ]
    if not candidates:
        candidates = [frames[peak_index]]
    while len(candidates) < 4:
        candidates.append(candidates[-1])
    return [
        candidates[0],
        candidates[min(1, len(candidates) - 1)],
        candidates[min(2, len(candidates) - 1)],
        candidates[min(3, len(candidates) - 1)],
        candidates[min(3, len(candidates) - 1)],
        candidates[min(3, len(candidates) - 1)],
    ]


def refine(source: Path, destination: Path) -> tuple[int, float]:
    with Image.open(source) as opened:
        sheet = opened.convert("RGBA")
    if sheet.size != EXPECTED_SIZE:
        raise ValueError(f"unexpected source size for {source}: {sheet.size}")

    source_frames = [
        suppress_neutral_halo(clear_boundary(frame)) for frame in split_frames(sheet)
    ]
    search_indices = range(2, 12)
    peak_index = max(search_indices, key=lambda index: mean_alpha(source_frames[index]))
    peak = source_frames[peak_index]
    while visible_occupancy(peak) > 0.74:
        peak = multiply_alpha(peak, 0.90)
    peak_mean = mean_alpha(peak)
    if peak_mean <= 0.002:
        raise ValueError(f"generated impact is effectively empty: {source}")

    build_indices = [
        round(step * max(0, peak_index - 1) / 7) for step in range(8)
    ]
    result: list[Image.Image] = []
    result.append(
        match_mean_alpha(
            source_frames[build_indices[0]], min(peak_mean * 0.025, 0.0025)
        )
    )
    for index in build_indices[1:7]:
        frame = source_frames[index]
        if mean_alpha(frame) >= peak_mean * 0.92:
            frame = match_mean_alpha(frame, peak_mean * 0.86)
        result.append(frame)

    pre_peak_index = build_indices[7]
    pre_peak = source_frames[pre_peak_index]
    if mean_alpha(pre_peak) >= peak_mean * 0.94:
        pre_peak = match_mean_alpha(pre_peak, peak_mean * 0.90)
    while visible_occupancy(pre_peak) > visible_occupancy(peak) + 0.015:
        pre_peak = multiply_alpha(pre_peak, 0.90)
    result.append(pre_peak)
    result.append(peak)

    previous_late_frame = peak
    for source_frame, ratio in zip(
        residual_sources(source_frames, peak_index), LATE_MEAN_RATIOS
    ):
        target_mean = peak_mean * ratio
        late_frame = match_mean_alpha(source_frame, target_mean)
        if visible_occupancy(late_frame) > visible_occupancy(previous_late_frame) + 0.005:
            late_frame = match_mean_alpha(previous_late_frame, target_mean)
        result.append(late_frame)
        previous_late_frame = late_frame

    result.append(Image.new("RGBA", (CELL_WIDTH, CELL_HEIGHT), (0, 0, 0, 0)))
    if len(result) != 16:
        raise AssertionError(f"refiner produced {len(result)} frames")
    result = enforce_energy_order(normalize_peak_framing(result))

    output = Image.new("RGBA", EXPECTED_SIZE, (0, 0, 0, 0))
    for index, frame in enumerate(result):
        output.alpha_composite(
            clear_boundary(frame),
            ((index % COLUMNS) * CELL_WIDTH, (index // COLUMNS) * CELL_HEIGHT),
        )
    destination.parent.mkdir(parents=True, exist_ok=True)
    output.save(destination, optimize=True)
    return peak_index + 1, peak_mean


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--expected-count", type=int, required=True)
    args = parser.parse_args()

    sources = sorted(args.source_dir.glob("*.png"))
    if len(sources) != args.expected_count:
        raise SystemExit(
            f"expected {args.expected_count} source sheets, found {len(sources)}"
        )
    for source in sources:
        destination = args.output_dir / source.name
        peak_frame, peak_mean = refine(source, destination)
        print(
            f"refined={destination} sourcePeak=F{peak_frame:02d} "
            f"sourcePeakMean={peak_mean:.4f}"
        )


if __name__ == "__main__":
    main()
