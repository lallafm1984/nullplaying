#!/usr/bin/env python3
"""Recover generated VFX from neutral mattes and normalize a 4x4 sheet.

The generated source is preserved.  Each source cell is extracted separately,
scaled with one sheet-wide scale factor, and centered in the web VFX contract
cell so non-standard generator canvas ratios do not stretch the artwork.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


COLUMNS = 4
ROWS = 4
CELL_WIDTH = 361
CELL_HEIGHT = 160
OUTPUT_SIZE = (CELL_WIDTH * COLUMNS, CELL_HEIGHT * ROWS)
SOURCE_EDGE_CLEAR = 3
OUTPUT_BOUNDARY_CLEAR = 6
OUTPUT_PADDING = 8


def edge_samples(rgb: np.ndarray) -> np.ndarray:
    height, width, _ = rgb.shape
    thickness = max(4, min(height, width) // 100)
    return np.concatenate(
        (
            rgb[:thickness].reshape(-1, 3),
            rgb[-thickness:].reshape(-1, 3),
            rgb[:, :thickness].reshape(-1, 3),
            rgb[:, -thickness:].reshape(-1, 3),
        )
    )


def recover_alpha(rgb: np.ndarray) -> tuple[np.ndarray, str, float]:
    edge = edge_samples(rgb)
    edge_luminance = edge.mean(axis=1)
    edge_chroma = edge.max(axis=1) - edge.min(axis=1)
    neutral_edge = edge_luminance[edge_chroma <= 5.0]
    if neutral_edge.size == 0:
        neutral_edge = edge_luminance
    background = float(np.median(neutral_edge))

    maximum = rgb.max(axis=2)
    minimum = rgb.min(axis=2)
    chroma = maximum - minimum
    luminance = rgb.mean(axis=2)

    if background < 45.0:
        threshold = float(np.clip(np.percentile(neutral_edge, 90) + 3.0, 6.0, 52.0))
        signal = np.maximum(maximum - threshold, chroma * 1.35)
        normalized = np.clip(signal / (255.0 - threshold), 0.0, 1.0)
        alpha = np.power(normalized, 0.56)
        alpha[(maximum <= threshold) & (chroma <= 3.0)] = 0.0
        # Generated black-matte previews are not guaranteed to contain truly
        # premultiplied RGB. Full un-premultiplication turned dark violet smoke
        # into a white fringe. Lift it only modestly and preserve its hue.
        color_lift = np.maximum(np.power(alpha, 0.35), 0.45)
        straight_rgb = np.clip(rgb / color_lift[..., None], 0.0, 255.0)
        straight_rgb[alpha == 0.0] = 0.0
        return np.dstack((straight_rgb, alpha * 255.0)), "dark-matte", background

    dark_reference = float(np.percentile(neutral_edge, 18))
    color_signal = np.clip((chroma - 2.0) / 50.0, 0.0, 1.0)
    dark_span = max(42.0, dark_reference * 0.48)
    dark_signal = np.clip((dark_reference - 3.0 - luminance) / dark_span, 0.0, 1.0)
    # Neutral dark pixels cannot seed their own alpha: generated previews often
    # contain a black rectangular impact matte inside an otherwise light
    # checkerboard. Restore dark material only near genuinely colored pixels.
    seed = Image.fromarray(np.uint8(np.clip(color_signal * 255.0, 0.0, 255.0)))
    core_support = seed.filter(ImageFilter.MaxFilter(7)).filter(ImageFilter.GaussianBlur(1.1))
    wide_support = seed.filter(ImageFilter.MaxFilter(19)).filter(ImageFilter.GaussianBlur(2.0))
    core_support_array = np.asarray(core_support, dtype=np.float32) / 255.0
    wide_support_array = np.asarray(wide_support, dtype=np.float32) / 255.0
    alpha = np.maximum(color_signal, core_support_array * 0.74)
    alpha = np.maximum(alpha, dark_signal * wide_support_array * 0.90)
    alpha[alpha < 0.045] = 0.0
    return np.dstack((rgb, alpha * 255.0)), "neutral-checker", background


def split_cells(rgba: Image.Image) -> list[Image.Image]:
    width, height = rgba.size
    cells: list[Image.Image] = []
    for index in range(COLUMNS * ROWS):
        column = index % COLUMNS
        row = index // COLUMNS
        left = round(column * width / COLUMNS)
        right = round((column + 1) * width / COLUMNS)
        top = round(row * height / ROWS)
        bottom = round((row + 1) * height / ROWS)
        cell = rgba.crop((left, top, right, bottom))
        alpha = np.asarray(cell.getchannel("A"), dtype=np.uint8).copy()
        alpha[:SOURCE_EDGE_CLEAR, :] = 0
        alpha[-SOURCE_EDGE_CLEAR:, :] = 0
        alpha[:, :SOURCE_EDGE_CLEAR] = 0
        alpha[:, -SOURCE_EDGE_CLEAR:] = 0
        # Some generated previews place their grid rule several pixels inside
        # the mathematical cell edge. Remove long rules only in the outer
        # band; centered horizontal/vertical skill strokes remain untouched.
        height_band = max(SOURCE_EDGE_CLEAR, round(alpha.shape[0] * 0.18))
        width_band = max(SOURCE_EDGE_CLEAR, round(alpha.shape[1] * 0.18))
        edge_rows = list(range(height_band)) + list(
            range(alpha.shape[0] - height_band, alpha.shape[0])
        )
        edge_columns = list(range(width_band)) + list(
            range(alpha.shape[1] - width_band, alpha.shape[1])
        )
        for y in edge_rows:
            if np.mean(alpha[y] > 8) > 0.55:
                alpha[max(0, y - 2) : min(alpha.shape[0], y + 3), :] = 0
        for x in edge_columns:
            if np.mean(alpha[:, x] > 8) > 0.55:
                alpha[:, max(0, x - 2) : min(alpha.shape[1], x + 3)] = 0

        # Hide generator crop boundaries without carving a hard bar through an
        # effect that legitimately reaches the source cell edge.
        feather_x = max(4, round(alpha.shape[1] * 0.025))
        feather_y = max(4, round(alpha.shape[0] * 0.05))
        x_fade = np.minimum(
            np.clip(np.arange(alpha.shape[1]) / feather_x, 0.0, 1.0),
            np.clip((alpha.shape[1] - 1 - np.arange(alpha.shape[1])) / feather_x, 0.0, 1.0),
        )
        y_fade = np.minimum(
            np.clip(np.arange(alpha.shape[0]) / feather_y, 0.0, 1.0),
            np.clip((alpha.shape[0] - 1 - np.arange(alpha.shape[0])) / feather_y, 0.0, 1.0),
        )
        alpha = np.uint8(alpha.astype(np.float32) * np.outer(y_fade, x_fade))

        # A dense axis-aligned colored rectangle is another common preview
        # artifact around the impact frame. Feather its own bounding box so the
        # retained energy reads as an organic burst rather than a pasted panel.
        ys, xs = np.where(alpha > 12)
        if xs.size:
            left, right = int(xs.min()), int(xs.max() + 1)
            top, bottom = int(ys.min()), int(ys.max() + 1)
            box_width = right - left
            box_height = bottom - top
            fill = xs.size / (box_width * box_height)
            if (
                fill > 0.55
                and box_width > alpha.shape[1] * 0.35
                and box_height > alpha.shape[0] * 0.35
            ):
                inner_x = max(4, round(box_width * 0.14))
                inner_y = max(4, round(box_height * 0.16))
                local_x = np.minimum(
                    np.clip(np.arange(box_width) / inner_x, 0.0, 1.0),
                    np.clip((box_width - 1 - np.arange(box_width)) / inner_x, 0.0, 1.0),
                )
                local_y = np.minimum(
                    np.clip(np.arange(box_height) / inner_y, 0.0, 1.0),
                    np.clip((box_height - 1 - np.arange(box_height)) / inner_y, 0.0, 1.0),
                )
                alpha[top:bottom, left:right] = np.uint8(
                    alpha[top:bottom, left:right].astype(np.float32)
                    * np.outer(local_y, local_x)
                )
        cell.putalpha(Image.fromarray(alpha))
        cells.append(cell)
    return cells


def visible_box(cell: Image.Image) -> tuple[int, int, int, int] | None:
    alpha = np.asarray(cell.getchannel("A"))
    ys, xs = np.where(alpha > 8)
    if xs.size == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max() + 1), int(ys.max() + 1)


def normalize_cells(cells: list[Image.Image]) -> Image.Image:
    boxes = [visible_box(cell) for cell in cells]
    widths = [box[2] - box[0] for box in boxes if box]
    heights = [box[3] - box[1] for box in boxes if box]
    if not widths or not heights:
        return Image.new("RGBA", OUTPUT_SIZE, (0, 0, 0, 0))

    scale = min(
        (CELL_WIDTH - OUTPUT_PADDING * 2) / max(widths),
        (CELL_HEIGHT - OUTPUT_PADDING * 2) / max(heights),
    )
    sheet = Image.new("RGBA", OUTPUT_SIZE, (0, 0, 0, 0))
    for index, (cell, box) in enumerate(zip(cells, boxes)):
        if box is None:
            continue
        effect = cell.crop(box)
        size = (
            max(1, round(effect.width * scale)),
            max(1, round(effect.height * scale)),
        )
        effect = effect.resize(size, Image.Resampling.LANCZOS)
        cell_left = (index % COLUMNS) * CELL_WIDTH
        cell_top = (index // COLUMNS) * CELL_HEIGHT
        x = cell_left + (CELL_WIDTH - effect.width) // 2
        y = cell_top + (CELL_HEIGHT - effect.height) // 2
        sheet.alpha_composite(effect, (x, y))
    return clear_output_boundaries(sheet)


def clear_output_boundaries(sheet: Image.Image) -> Image.Image:
    alpha = np.asarray(sheet.getchannel("A"), dtype=np.uint8).copy()
    for column in range(COLUMNS):
        left = column * CELL_WIDTH
        right = (column + 1) * CELL_WIDTH
        alpha[:, left : left + OUTPUT_BOUNDARY_CLEAR] = 0
        alpha[:, right - OUTPUT_BOUNDARY_CLEAR : right] = 0
    for row in range(ROWS):
        top = row * CELL_HEIGHT
        bottom = (row + 1) * CELL_HEIGHT
        alpha[top : top + OUTPUT_BOUNDARY_CLEAR, :] = 0
        alpha[bottom - OUTPUT_BOUNDARY_CLEAR : bottom, :] = 0
    sheet.putalpha(Image.fromarray(alpha))
    return sheet


def normalize(source: Path, destination: Path) -> tuple[str, float]:
    with Image.open(source) as opened:
        rgb = np.asarray(opened.convert("RGB"), dtype=np.float32)
    recovered, method, background = recover_alpha(rgb)
    rgba = Image.fromarray(np.uint8(np.clip(recovered, 0.0, 255.0)))
    expected_aspect = OUTPUT_SIZE[0] / OUTPUT_SIZE[1]
    source_aspect = rgba.width / rgba.height
    if abs(source_aspect / expected_aspect - 1.0) <= 0.04:
        result = rgba.resize(OUTPUT_SIZE, Image.Resampling.LANCZOS)
        result = clear_output_boundaries(result)
    else:
        result = normalize_cells(split_cells(rgba))
    destination.parent.mkdir(parents=True, exist_ok=True)
    result.save(destination, optimize=True)
    return method, background


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    method, background = normalize(args.source, args.destination)
    print(
        f"normalized={args.destination} size={OUTPUT_SIZE} mode=RGBA "
        f"matte={method} edge_luminance={background:.1f}"
    )


if __name__ == "__main__":
    main()
