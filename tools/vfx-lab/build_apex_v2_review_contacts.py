#!/usr/bin/env python3
"""Build compact F09/F15 review contacts for the apex rework variants."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


FRAME_WIDTH = 361
FRAME_HEIGHT = 160
THUMB_WIDTH = 180
THUMB_HEIGHT = 80
LABEL_HEIGHT = 22
CLASSES = ("rogue", "ranger", "mage", "cleric", "paladin")


def checkerboard() -> Image.Image:
    image = Image.new("RGBA", (FRAME_WIDTH, FRAME_HEIGHT), (38, 41, 49, 255))
    draw = ImageDraw.Draw(image)
    for y in range(0, FRAME_HEIGHT, 12):
        for x in range(0, FRAME_WIDTH, 12):
            if (x // 12 + y // 12) % 2:
                draw.rectangle((x, y, x + 11, y + 11), fill=(59, 63, 73, 255))
    return image


def frame(sheet: Image.Image, index: int) -> Image.Image:
    x = (index % 4) * FRAME_WIDTH
    y = (index // 4) * FRAME_HEIGHT
    result = checkerboard()
    result.alpha_composite(sheet.crop((x, y, x + FRAME_WIDTH, y + FRAME_HEIGHT)))
    return result.resize((THUMB_WIDTH, THUMB_HEIGHT), Image.Resampling.LANCZOS)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    font = ImageFont.load_default()

    for class_name in CLASSES:
        skills = sorted((args.source_dir / "variant-1").glob(f"{class_name}_*.png"))
        if len(skills) != 5:
            raise SystemExit(f"{class_name}: expected 5 skills, found {len(skills)}")
        width = THUMB_WIDTH * 8
        row_height = THUMB_HEIGHT + LABEL_HEIGHT
        contact = Image.new("RGBA", (width, row_height * 5), (20, 22, 28, 255))
        draw = ImageDraw.Draw(contact)
        for row, skill in enumerate(skills):
            for variant in range(1, 5):
                source = args.source_dir / f"variant-{variant}" / skill.name
                with Image.open(source) as opened:
                    sheet = opened.convert("RGBA")
                for pair_index, frame_index in enumerate((8, 14)):
                    column = (variant - 1) * 2 + pair_index
                    x = column * THUMB_WIDTH
                    y = row * row_height
                    label = f"{skill.stem}  {variant}안  F{frame_index + 1:02d}"
                    draw.text((x + 4, y + 5), label, fill=(235, 238, 245, 255), font=font)
                    contact.alpha_composite(frame(sheet, frame_index), (x, y + LABEL_HEIGHT))
        destination = args.output_dir / f"{class_name}-f09-f15-contact.png"
        contact.save(destination, optimize=True)
        print(destination)


if __name__ == "__main__":
    main()
