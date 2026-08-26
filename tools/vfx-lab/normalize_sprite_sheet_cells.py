#!/usr/bin/env python3
"""Recompose a FrameSmith sheet from exact cells and remove detached edge residue."""

from __future__ import annotations

import sys
from collections import deque
from pathlib import Path

from PIL import Image


FRAME_WIDTH = 361
FRAME_HEIGHT = 160
COLUMNS = 4
ROWS = 4
EDGE_VERTICAL = 8
EDGE_HORIZONTAL = 4


def detached_edge_pixels(alpha: Image.Image) -> list[tuple[int, int]]:
    pixels = alpha.load()
    visited: set[tuple[int, int]] = set()
    clear: list[tuple[int, int]] = []
    for start_y in range(FRAME_HEIGHT):
        for start_x in range(FRAME_WIDTH):
            if pixels[start_x, start_y] == 0 or (start_x, start_y) in visited:
                continue
            queue: deque[tuple[int, int]] = deque([(start_x, start_y)])
            component: list[tuple[int, int]] = []
            visited.add((start_x, start_y))
            min_x = max_x = start_x
            min_y = max_y = start_y
            while queue:
                x, y = queue.popleft()
                component.append((x, y))
                min_x, max_x = min(min_x, x), max(max_x, x)
                min_y, max_y = min(min_y, y), max(max_y, y)
                for next_x, next_y in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                    if not (0 <= next_x < FRAME_WIDTH and 0 <= next_y < FRAME_HEIGHT):
                        continue
                    if pixels[next_x, next_y] == 0 or (next_x, next_y) in visited:
                        continue
                    visited.add((next_x, next_y))
                    queue.append((next_x, next_y))
            in_vertical_edge = max_y < EDGE_VERTICAL or min_y >= FRAME_HEIGHT - EDGE_VERTICAL
            in_horizontal_edge = max_x < EDGE_HORIZONTAL or min_x >= FRAME_WIDTH - EDGE_HORIZONTAL
            if in_vertical_edge or in_horizontal_edge:
                clear.extend(component)
    return clear


def normalize(path: Path) -> int:
    with Image.open(path) as source:
        sheet = source.convert("RGBA")
    expected_size = (FRAME_WIDTH * COLUMNS, FRAME_HEIGHT * ROWS)
    if sheet.size != expected_size:
        raise ValueError(f"{path} is {sheet.size}, expected {expected_size}")
    normalized = Image.new("RGBA", expected_size, (0, 0, 0, 0))
    removed = 0
    for row in range(ROWS):
        for column in range(COLUMNS):
            left = column * FRAME_WIDTH
            top = row * FRAME_HEIGHT
            frame = sheet.crop((left, top, left + FRAME_WIDTH, top + FRAME_HEIGHT))
            alpha = frame.getchannel("A")
            edge_pixels = detached_edge_pixels(alpha)
            if edge_pixels:
                rgba = frame.load()
                for x, y in edge_pixels:
                    rgba[x, y] = (0, 0, 0, 0)
                removed += len(edge_pixels)
            normalized.alpha_composite(frame, (left, top))
    normalized.save(path)
    return removed


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: normalize_sprite_sheet_cells.py <sprite-sheet.png>")
    path = Path(sys.argv[1])
    print(f"normalized={path} removedEdgePixels={normalize(path)}")


if __name__ == "__main__":
    main()
