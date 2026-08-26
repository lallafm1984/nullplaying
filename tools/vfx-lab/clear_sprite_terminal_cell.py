#!/usr/bin/env python3
"""Clear F16 in a fixed AlarmQuest 4x4 sprite sheet for runtime-owned fading."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


CELL_WIDTH = 361
CELL_HEIGHT = 160
EXPECTED_SIZE = (CELL_WIDTH * 4, CELL_HEIGHT * 4)
TERMINAL_BOX = (CELL_WIDTH * 3, CELL_HEIGHT * 3, CELL_WIDTH * 4, CELL_HEIGHT * 4)
FRAME_14_BOX = (CELL_WIDTH, CELL_HEIGHT * 3, CELL_WIDTH * 2, CELL_HEIGHT * 4)
FRAME_15_ORIGIN = (CELL_WIDTH * 2, CELL_HEIGHT * 3)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument(
        "--repeat-f14-as-f15",
        action="store_true",
        help="copy F14 into F15 so the runtime fade holds an opaque authored frame",
    )
    args = parser.parse_args()

    with Image.open(args.source) as opened:
        sheet = opened.convert("RGBA")
    if sheet.size != EXPECTED_SIZE:
        raise SystemExit(f"expected {EXPECTED_SIZE}, found {sheet.size}")

    if args.repeat_f14_as_f15:
        sheet.paste(sheet.crop(FRAME_14_BOX), FRAME_15_ORIGIN)
    sheet.paste((0, 0, 0, 0), TERMINAL_BOX)
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(args.destination, optimize=True)
    hold = "; F15=F14" if args.repeat_f14_as_f15 else ""
    print(f"terminal-cleared={args.destination}; F16=transparent{hold}")


if __name__ == "__main__":
    main()
