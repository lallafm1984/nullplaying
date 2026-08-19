#!/usr/bin/env python3
"""Audit fixed-contract FrameSmith sprite sheets frame by frame.

The script never mutates source sheets. It exports checkerboard-backed frame
montages and a JSON report with alpha/brightness centroid, coverage, symmetry,
and edge occupancy. Metrics are triage signals; visual review stays authoritative.
"""

from __future__ import annotations

import argparse
import json
from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


FRAME_WIDTH = 361
FRAME_HEIGHT = 160
COLUMNS = 4
ROWS = 4
FRAME_COUNT = COLUMNS * ROWS


def checkerboard(size: tuple[int, int], cell: int = 12) -> Image.Image:
    board = Image.new("RGBA", size, (42, 45, 54, 255))
    draw = ImageDraw.Draw(board)
    for y in range(0, size[1], cell):
        for x in range(0, size[0], cell):
            if (x // cell + y // cell) % 2:
                draw.rectangle((x, y, x + cell - 1, y + cell - 1), fill=(64, 68, 79, 255))
    return board


def weighted_centroid(frame: Image.Image, brightness_only: bool) -> tuple[float | None, float | None]:
    rgba = frame.convert("RGBA")
    pixels = rgba.load()
    samples: list[tuple[float, int, int]] = []
    for y in range(FRAME_HEIGHT):
        for x in range(FRAME_WIDTH):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
            weight = (a / 255.0) * (luminance if brightness_only else 1.0)
            samples.append((weight, x, y))
    if not samples:
        return None, None
    if brightness_only:
        samples.sort(reverse=True)
        samples = samples[: max(1, int(len(samples) * 0.15))]
    total = sum(weight for weight, _, _ in samples)
    if total == 0:
        return None, None
    x = sum(weight * px for weight, px, _ in samples) / total
    y = sum(weight * py for weight, _, py in samples) / total
    return round(100.0 * x / (FRAME_WIDTH - 1), 2), round(100.0 * y / (FRAME_HEIGHT - 1), 2)


def edge_occupancy(frame: Image.Image, edge_height: int = 4) -> tuple[float, float]:
    alpha = frame.getchannel("A")
    top = alpha.crop((0, 0, FRAME_WIDTH, edge_height))
    bottom = alpha.crop((0, FRAME_HEIGHT - edge_height, FRAME_WIDTH, FRAME_HEIGHT))
    area = FRAME_WIDTH * edge_height
    top_count = sum(1 for value in top.getdata() if value > 12)
    bottom_count = sum(1 for value in bottom.getdata() if value > 12)
    return round(100.0 * top_count / area, 2), round(100.0 * bottom_count / area, 2)


def alpha_metrics(frame: Image.Image) -> dict[str, float]:
    alpha = frame.getchannel("A")
    values = list(alpha.getdata())
    visible = [value > 12 for value in values]
    visible_count = sum(visible)
    area = FRAME_WIDTH * FRAME_HEIGHT
    xs: list[int] = []
    ys: list[int] = []
    for index, is_visible in enumerate(visible):
        if not is_visible:
            continue
        xs.append(index % FRAME_WIDTH)
        ys.append(index // FRAME_WIDTH)
    occupied_width = 0.0 if not xs else 100.0 * (max(xs) - min(xs) + 1) / FRAME_WIDTH
    occupied_height = 0.0 if not ys else 100.0 * (max(ys) - min(ys) + 1) / FRAME_HEIGHT
    bbox_area = 0 if not xs else (max(xs) - min(xs) + 1) * (max(ys) - min(ys) + 1)
    bbox_fill = 0.0 if bbox_area == 0 else 100.0 * visible_count / bbox_area
    axis_aligned_panel = (
        visible_count / area >= 0.20
        and bbox_fill >= 90.0
        and (
            (occupied_height >= 98.0 and 25.0 <= occupied_width <= 80.0)
            or (occupied_width >= 98.0 and 25.0 <= occupied_height <= 80.0)
        )
    )

    difference = 0
    denominator = 0
    pixels = alpha.load()
    for y in range(FRAME_HEIGHT):
        for x in range(FRAME_WIDTH // 2):
            left = pixels[x, y]
            right = pixels[FRAME_WIDTH - 1 - x, y]
            difference += abs(left - right)
            denominator += max(left, right)
    symmetry = 100.0 if denominator == 0 else 100.0 * (1.0 - difference / denominator)
    return {
        "alphaOccupancyPct": round(100.0 * visible_count / area, 2),
        "meanAlphaPct": round(100.0 * sum(values) / (255.0 * area), 2),
        "occupiedWidthPct": round(occupied_width, 2),
        "occupiedHeightPct": round(occupied_height, 2),
        "alphaBBoxFillPct": round(bbox_fill, 2),
        "axisAlignedAlphaPanelCandidate": axis_aligned_panel,
        "leftRightAlphaSymmetryPct": round(symmetry, 2),
    }


def opaque_dark_component_metrics(frame: Image.Image) -> dict[str, float | bool]:
    """Find panel-like, nearly opaque dark connected regions.

    Legitimate dark shards and voids tend to have irregular bounding boxes with
    low fill ratios. A generation-error backdrop is usually one large connected
    component that fills most of a rectangular bounding box.
    """
    pixels = frame.convert("RGBA").load()
    mask = bytearray(FRAME_WIDTH * FRAME_HEIGHT)
    for y in range(FRAME_HEIGHT):
        for x in range(FRAME_WIDTH):
            r, g, b, a = pixels[x, y]
            luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if a >= 242 and luminance <= 28.0:
                mask[y * FRAME_WIDTH + x] = 1

    visited = bytearray(len(mask))
    largest = None
    for start, enabled in enumerate(mask):
        if not enabled or visited[start]:
            continue
        visited[start] = 1
        queue = deque([start])
        count = 0
        min_x = FRAME_WIDTH
        max_x = -1
        min_y = FRAME_HEIGHT
        max_y = -1
        while queue:
            index = queue.popleft()
            y, x = divmod(index, FRAME_WIDTH)
            count += 1
            min_x = min(min_x, x)
            max_x = max(max_x, x)
            min_y = min(min_y, y)
            max_y = max(max_y, y)
            for neighbor in (index - 1, index + 1, index - FRAME_WIDTH, index + FRAME_WIDTH):
                if neighbor < 0 or neighbor >= len(mask) or visited[neighbor] or not mask[neighbor]:
                    continue
                neighbor_y, neighbor_x = divmod(neighbor, FRAME_WIDTH)
                if abs(neighbor_x - x) + abs(neighbor_y - y) != 1:
                    continue
                visited[neighbor] = 1
                queue.append(neighbor)

        bbox_width = max_x - min_x + 1
        bbox_height = max_y - min_y + 1
        bbox_area = bbox_width * bbox_height
        candidate = {
            "count": count,
            "bboxArea": bbox_area,
            "bboxWidth": bbox_width,
            "bboxHeight": bbox_height,
            "bboxFillPct": 100.0 * count / bbox_area,
        }
        if largest is None or candidate["count"] > largest["count"]:
            largest = candidate

    area = FRAME_WIDTH * FRAME_HEIGHT
    if largest is None:
        largest = {"count": 0, "bboxArea": 0, "bboxWidth": 0, "bboxHeight": 0, "bboxFillPct": 0.0}
    component_pct = 100.0 * largest["count"] / area
    bbox_pct = 100.0 * largest["bboxArea"] / area
    width_pct = 100.0 * largest["bboxWidth"] / FRAME_WIDTH
    height_pct = 100.0 * largest["bboxHeight"] / FRAME_HEIGHT
    panel_candidate = (
        component_pct >= 8.0
        and bbox_pct >= 12.0
        and largest["bboxFillPct"] >= 82.0
        and width_pct >= 25.0
        and height_pct >= 20.0
    )
    return {
        "largestOpaqueDarkComponentPct": round(component_pct, 2),
        "largestOpaqueDarkBBoxPct": round(bbox_pct, 2),
        "largestOpaqueDarkBBoxFillPct": round(largest["bboxFillPct"], 2),
        "largestOpaqueDarkBBoxWidthPct": round(width_pct, 2),
        "largestOpaqueDarkBBoxHeightPct": round(height_pct, 2),
        "opaqueDarkPanelCandidate": panel_candidate,
    }


def frame_crop(sheet: Image.Image, index: int) -> Image.Image:
    x = (index % COLUMNS) * FRAME_WIDTH
    y = (index // COLUMNS) * FRAME_HEIGHT
    return sheet.crop((x, y, x + FRAME_WIDTH, y + FRAME_HEIGHT))


def audit_sheet(source: Path, output_dir: Path) -> dict:
    sheet = Image.open(source).convert("RGBA")
    expected_size = (FRAME_WIDTH * COLUMNS, FRAME_HEIGHT * ROWS)
    if sheet.size != expected_size:
        raise ValueError(f"{source}: expected {expected_size}, got {sheet.size}")

    label_height = 24
    gutter = 8
    montage = Image.new(
        "RGBA",
        (FRAME_WIDTH * 2 + gutter * 3, (FRAME_HEIGHT + label_height) * 8 + gutter * 9),
        (22, 24, 30, 255),
    )
    draw = ImageDraw.Draw(montage)
    font = ImageFont.load_default()
    records = []
    previous_bright_x = None

    for index in range(FRAME_COUNT):
        frame = frame_crop(sheet, index)
        alpha_x, alpha_y = weighted_centroid(frame, brightness_only=False)
        bright_x, bright_y = weighted_centroid(frame, brightness_only=True)
        top_edge, bottom_edge = edge_occupancy(frame)
        coverage = alpha_metrics(frame)
        dark_component = opaque_dark_component_metrics(frame)
        delta = None if previous_bright_x is None or bright_x is None else round(bright_x - previous_bright_x, 2)
        if bright_x is not None:
            previous_bright_x = bright_x
        records.append(
            {
                "frame": index + 1,
                "alphaCentroidPct": {"x": alpha_x, "y": alpha_y},
                "bright15CentroidPct": {"x": bright_x, "y": bright_y},
                "brightXDeltaPct": delta,
                **coverage,
                **dark_component,
                "top4pxAlphaOccupancyPct": top_edge,
                "bottom4pxAlphaOccupancyPct": bottom_edge,
            }
        )

        column = index % 2
        row = index // 2
        x = gutter + column * (FRAME_WIDTH + gutter)
        y = gutter + row * (FRAME_HEIGHT + label_height + gutter)
        background = checkerboard((FRAME_WIDTH, FRAME_HEIGHT))
        background.alpha_composite(frame)
        montage.alpha_composite(background, (x, y + label_height))
        center_x = x + FRAME_WIDTH // 2
        draw.line((center_x, y + label_height, center_x, y + label_height + FRAME_HEIGHT), fill=(44, 255, 182, 210), width=1)
        draw.rectangle((x, y + label_height, x + FRAME_WIDTH - 1, y + label_height + FRAME_HEIGHT - 1), outline=(240, 244, 255, 210), width=1)
        label = f"F{index + 1:02d} brightX={bright_x} dX={delta} occ={coverage['alphaOccupancyPct']}% sym={coverage['leftRightAlphaSymmetryPct']}%"
        draw.text((x + 3, y + 5), label, fill=(235, 238, 245, 255), font=font)

    output_dir.mkdir(parents=True, exist_ok=True)
    montage_path = output_dir / f"{source.stem}-frame-audit.png"
    montage.save(montage_path)
    first = records[0]
    peak_main = records[7]
    peak_max = records[8]
    final = records[15]
    qa_signals = {
        "startEndResidue": any(
            record["meanAlphaPct"] > 5.0 or record["alphaOccupancyPct"] > 20.0
            for record in (first, final)
        ),
        "peakUnderfill": any(
            record["occupiedWidthPct"] < 90.0 or record["occupiedHeightPct"] < 82.0
            for record in (peak_main, peak_max)
        ),
        "peakMirrorPriority": any(
            record["leftRightAlphaSymmetryPct"] > 80.0
            for record in (peak_main, peak_max)
        ),
        "peakBrightCoreDrift": any(
            record["bright15CentroidPct"]["x"] is None
            or abs(record["bright15CentroidPct"]["x"] - 50.0) > 8.0
            for record in (peak_main, peak_max)
        ),
        "peakOrderViolation": (
            peak_max["meanAlphaPct"] <= peak_main["meanAlphaPct"]
            or peak_max["alphaOccupancyPct"] < peak_main["alphaOccupancyPct"] - 2.0
        ),
        "postPeakOverrun": any(
            record["meanAlphaPct"] >= peak_max["meanAlphaPct"]
            or record["alphaOccupancyPct"] > peak_max["alphaOccupancyPct"] + 3.0
            for record in records[9:12]
        ),
        "lateEnergyRegrowth": any(
            later["meanAlphaPct"] - earlier["meanAlphaPct"] > 5.0
            or later["alphaOccupancyPct"] - earlier["alphaOccupancyPct"] > 7.0
            or (
                later["meanAlphaPct"] - earlier["meanAlphaPct"] > 1.0
                and later["leftRightAlphaSymmetryPct"] - earlier["leftRightAlphaSymmetryPct"] > 12.0
            )
            or (
                later["meanAlphaPct"] > earlier["meanAlphaPct"]
                and later["alphaOccupancyPct"] - earlier["alphaOccupancyPct"] > 0.75
            )
            for earlier, later in zip(records[9:15], records[10:16])
        ),
        "opaqueDarkPanelPriority": any(
            record["opaqueDarkPanelCandidate"]
            for record in records
        ),
        "axisAlignedAlphaPanelPriority": any(
            record["axisAlignedAlphaPanelCandidate"]
            for record in records
        ),
    }
    return {
        "source": str(source),
        "size": list(sheet.size),
        "frameSize": [FRAME_WIDTH, FRAME_HEIGHT],
        "montage": str(montage_path),
        "qaSignals": qa_signals,
        "visualReviewPriority": any(qa_signals.values()),
        "frames": records,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sources", nargs="+", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    reports = [audit_sheet(source, args.output_dir) for source in args.sources]
    report_path = args.output_dir / "sprite-frame-audit.json"
    report_path.write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(report_path)


if __name__ == "__main__":
    main()
