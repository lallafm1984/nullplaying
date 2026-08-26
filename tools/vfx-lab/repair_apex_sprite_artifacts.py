#!/usr/bin/env python3
"""Repair a few visually confirmed rectangular impact crops.

The replacements reuse adjacent frames from the same direct-generated sheet;
they do not touch generation originals or any Android asset.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image


CELL_WIDTH = 361
CELL_HEIGHT = 160
REFINED_ROOT = Path("tools/vfx-lab/custom-assets/apex-class-rework-imagegen-refined-v1")
ALPHA_ROOT = Path("tools/vfx-lab/custom-assets/apex-class-rework-imagegen-alpha-v1")


def frame(sheet: Image.Image, index: int) -> Image.Image:
    x = (index % 4) * CELL_WIDTH
    y = (index // 4) * CELL_HEIGHT
    return sheet.crop((x, y, x + CELL_WIDTH, y + CELL_HEIGHT))


def place(sheet: Image.Image, index: int, replacement: Image.Image) -> None:
    x = (index % 4) * CELL_WIDTH
    y = (index // 4) * CELL_HEIGHT
    sheet.alpha_composite(replacement, (x, y))


def centered_scaled(source: Image.Image, scale: float, alpha_factor: float) -> Image.Image:
    size = (round(CELL_WIDTH * scale), round(CELL_HEIGHT * scale))
    enlarged = source.resize(size, Image.Resampling.LANCZOS)
    if alpha_factor != 1.0:
        alpha = enlarged.getchannel("A").point(
            lambda value: min(255, round(value * alpha_factor))
        )
        enlarged.putalpha(alpha)
    result = Image.new("RGBA", (CELL_WIDTH, CELL_HEIGHT), (0, 0, 0, 0))
    result.alpha_composite(
        enlarged,
        ((CELL_WIDTH - enlarged.width) // 2, (CELL_HEIGHT - enlarged.height) // 2),
    )
    return result


def replace_from_alpha(variant: int, name: str, target: int, source: int) -> None:
    refined_path = REFINED_ROOT / f"variant-{variant}" / name
    alpha_path = ALPHA_ROOT / f"variant-{variant}" / name
    with Image.open(refined_path) as opened:
        refined = opened.convert("RGBA")
    with Image.open(alpha_path) as opened:
        alpha_sheet = opened.convert("RGBA")
    replacement = centered_scaled(frame(alpha_sheet, source), 1.06, 1.18)
    x = (target % 4) * CELL_WIDTH
    y = (target // 4) * CELL_HEIGHT
    refined.paste((0, 0, 0, 0), (x, y, x + CELL_WIDTH, y + CELL_HEIGHT))
    place(refined, target, replacement)
    refined.save(refined_path, optimize=True)


def replace_from_refined(
    variant: int,
    name: str,
    target: int,
    source: int,
    *,
    scale: float = 0.86,
    alpha_factor: float = 0.58,
) -> None:
    path = REFINED_ROOT / f"variant-{variant}" / name
    with Image.open(path) as opened:
        sheet = opened.convert("RGBA")
    replacement = centered_scaled(frame(sheet, source), scale, alpha_factor)
    x = (target % 4) * CELL_WIDTH
    y = (target // 4) * CELL_HEIGHT
    sheet.paste((0, 0, 0, 0), (x, y, x + CELL_WIDTH, y + CELL_HEIGHT))
    place(sheet, target, replacement)
    sheet.save(path, optimize=True)


def main() -> None:
    # F10 rectangular flash -> decaying form of F09.
    replace_from_refined(3, "rogue_t16_c04.png", target=9, source=8)
    # F09 rectangular flashes -> reinforced forms of the clean F10 follow-up.
    replace_from_alpha(3, "rogue_t17_c05.png", target=8, source=9)
    replace_from_alpha(3, "rogue_t18_c01.png", target=8, source=9)
    replace_from_alpha(4, "ranger_t17_c03.png", target=8, source=9)
    replace_from_refined(4, "rogue_t16_c04.png", target=9, source=8)
    replace_from_refined(
        4,
        "rogue_t16_c04.png",
        target=10,
        source=9,
        scale=0.84,
        alpha_factor=0.45,
    )
    replace_from_refined(
        4,
        "rogue_t19_c02.png",
        target=10,
        source=9,
        scale=0.82,
        alpha_factor=0.68,
    )
    print("repaired=7")


if __name__ == "__main__":
    main()
