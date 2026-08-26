#!/usr/bin/env python3
"""Reorder fixed 4x4 sprite cells without changing their rendered pixels."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


CELL_WIDTH = 361
CELL_HEIGHT = 160
COLUMNS = 4
ROWS = 4
FRAME_COUNT = COLUMNS * ROWS
EXPECTED_SIZE = (CELL_WIDTH * COLUMNS, CELL_HEIGHT * ROWS)


def parse_order(raw: str) -> list[int]:
    order = [int(value.strip()) for value in raw.split(",") if value.strip()]
    if len(order) != FRAME_COUNT:
        raise argparse.ArgumentTypeError(f"order must contain exactly {FRAME_COUNT} entries")
    if any(index < 1 or index > FRAME_COUNT for index in order):
        raise argparse.ArgumentTypeError("order entries must be between 1 and 16")
    return order


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--order", required=True, type=parse_order)
    args = parser.parse_args()

    with Image.open(args.source) as opened:
        sheet = opened.convert("RGBA")
    if sheet.size != EXPECTED_SIZE:
        raise SystemExit(f"expected {EXPECTED_SIZE}, found {sheet.size}")

    frames = [
        sheet.crop(
            (
                (index % COLUMNS) * CELL_WIDTH,
                (index // COLUMNS) * CELL_HEIGHT,
                (index % COLUMNS + 1) * CELL_WIDTH,
                (index // COLUMNS + 1) * CELL_HEIGHT,
            )
        )
        for index in range(FRAME_COUNT)
    ]
    output = Image.new("RGBA", EXPECTED_SIZE, (0, 0, 0, 0))
    for destination_index, source_index in enumerate(args.order):
        output.alpha_composite(
            frames[source_index - 1],
            (
                (destination_index % COLUMNS) * CELL_WIDTH,
                (destination_index // COLUMNS) * CELL_HEIGHT,
            ),
        )

    args.destination.parent.mkdir(parents=True, exist_ok=True)
    output.save(args.destination, optimize=True)
    print(f"retimed={args.destination} order={','.join(map(str, args.order))}")


if __name__ == "__main__":
    main()
