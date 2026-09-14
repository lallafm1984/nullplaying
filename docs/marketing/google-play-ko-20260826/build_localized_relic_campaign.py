#!/usr/bin/env python3
"""Build English and Japanese variants of the selected Relic campaign.

The approved Korean art direction remains unchanged. Textless campaign art is
reused, while language-specific copy and real localized emulator captures are
composited locally so every exported word stays deterministic.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

import build_selected_relic_campaign as base


ROOT = Path(__file__).resolve().parent
RAW_LOCALIZED = ROOT / "raw" / "localized"
SELECTED_KO = ROOT / "final" / "selected-ko-relic-v3"
FEATURE_BG = ROOT / "raw" / "feature-bg-gate-imagegen.png"
LOGO = ROOT.parents[2] / "app/src/simple/res/drawable-nodpi/title_logo_null_playing_v2.webp"
AUDIT = ROOT / "audit"

FEATURE_SIZE = (1024, 500)
FEATURE_GOLD = (232, 187, 79, 255)
FEATURE_IVORY = (255, 248, 232, 255)

FONT_DIR = Path("/System/Library/Fonts")
EN_PHONE_HEADLINE = FONT_DIR / "NewYork.ttf"
EN_BODY = FONT_DIR / "HelveticaNeue.ttc"
EN_FEATURE_HEADLINE = FONT_DIR / "Supplemental/Arial Bold.ttf"
JA_PHONE_HEADLINE = next(FONT_DIR.glob("*明朝*"))
JA_BODY = next(FONT_DIR.glob("*角*W6*"))
JA_FEATURE_HEADLINE = next(FONT_DIR.glob("*角*W7*"))


LANGUAGES = {
    "en": {
        "phone_headline_font": EN_PHONE_HEADLINE,
        "body_font": EN_BODY,
        "body_index": 0,
        "body_bold_index": 1,
        "feature_headline_font": EN_FEATURE_HEADLINE,
        "feature_headline": "JUST WATCH IT GROW",
        "feature_subline": "YOUR HERO GROWS ON THEIR OWN",
        "assets": (
            {
                "background": "01-hourglass-background.png",
                "screen": "lv50-clean-main.png",
                "screen_crop_top": 0,
                "fade_damage_top": False,
                "headline": "DO NOTHING.\nTHE ADVENTURE CONTINUES.",
                "subline": "Even while you rest, your hero keeps moving.",
                "output": "02-phone-auto-adventure-en-1080x1920.png",
            },
            {
                "background": "02-growth-astrolabe-background.png",
                "screen": "lv50-clean-character.png",
                "screen_crop_top": 360,
                "fade_damage_top": True,
                "headline": "COME BACK.\nTHEY'RE STRONGER.",
                "subline": "Stats and skill mastery keep stacking up.",
                "output": "03-phone-growth-en-1080x1920.png",
            },
            {
                "background": "03-equipment-orbit-background.png",
                "screen": "lv50-clean-equipment.png",
                "screen_crop_top": 360,
                "fade_damage_top": True,
                "headline": "GEAR UPGRADES\nITSELF.",
                "subline": "Loot comparison and upgrades happen automatically.",
                "output": "04-phone-equipment-en-1080x1920.png",
            },
            {
                "background": "04-chronicle-background.png",
                "screen": "lv50-clean-quest.png",
                "screen_crop_top": 380,
                "fade_damage_top": True,
                "headline": "YOUR TIME\nBECOMES A STORY.",
                "subline": "See completed adventures and what comes next.",
                "output": "05-phone-chronicle-en-1080x1920.png",
            },
        ),
    },
    "ja": {
        "phone_headline_font": JA_PHONE_HEADLINE,
        "body_font": JA_BODY,
        "body_index": 0,
        "body_bold_index": 0,
        "feature_headline_font": JA_FEATURE_HEADLINE,
        "feature_headline": "見守るだけ",
        "feature_subline": "英雄は自ら成長します",
        "assets": (
            {
                "background": "01-hourglass-background.png",
                "screen": "lv50-clean-main.png",
                "screen_crop_top": 0,
                "fade_damage_top": False,
                "headline": "何もしなくても、\n冒険は続いていく",
                "subline": "休んでいる間も、英雄は自ら進み続けます",
                "output": "02-phone-auto-adventure-ja-1080x1920.png",
            },
            {
                "background": "02-growth-astrolabe-background.png",
                "screen": "lv50-clean-character.png",
                "screen_crop_top": 360,
                "fade_damage_top": True,
                "headline": "戻ってきたら、\nもっと強くなっていた",
                "subline": "能力値もスキル熟練度も、少しずつ積み上がる",
                "output": "03-phone-growth-ja-1080x1920.png",
            },
            {
                "background": "03-equipment-orbit-background.png",
                "screen": "lv50-clean-equipment.png",
                "screen_crop_top": 360,
                "fade_damage_top": True,
                "headline": "装備まで、\n自動で入れ替わる",
                "subline": "戦利品の比較から交換まで自動",
                "output": "04-phone-equipment-ja-1080x1920.png",
            },
            {
                "background": "04-chronicle-background.png",
                "screen": "lv50-clean-quest.png",
                "screen_crop_top": 380,
                "fade_damage_top": True,
                "headline": "積み重ねた時間が、\n物語になる",
                "subline": "完了した冒険も、次の物語もひと目で",
                "output": "05-phone-chronicle-ja-1080x1920.png",
            },
        ),
    },
}


def font_that_fits(
    draw: ImageDraw.ImageDraw,
    text: str,
    font_path: Path,
    max_width: int,
    start: int,
    stop: int,
    index: int = 0,
) -> ImageFont.FreeTypeFont:
    for size in range(start, stop - 1, -2):
        face = ImageFont.truetype(str(font_path), size=size, index=index)
        bbox = draw.textbbox((0, 0), text, font=face)
        if bbox[2] - bbox[0] <= max_width:
            return face
    return ImageFont.truetype(str(font_path), size=stop, index=index)


def left_textless_feature_base() -> Image.Image:
    selected = Image.open(
        SELECTED_KO / "01-feature-graphic-ko-1024x500.png"
    ).convert("RGBA")
    textless = base.cover(Image.open(FEATURE_BG), FEATURE_SIZE)

    mask = Image.new("L", FEATURE_SIZE, 0)
    pixels = mask.load()
    for x in range(FEATURE_SIZE[0]):
        if x <= 620:
            alpha = 255
        elif x >= 760:
            alpha = 0
        else:
            t = (x - 620) / 140
            smooth = t * t * (3 - 2 * t)
            alpha = round(255 * (1 - smooth))
        for y in range(FEATURE_SIZE[1]):
            pixels[x, y] = alpha

    return Image.composite(textless, selected, mask)


def draw_feature_gradient_text(
    canvas: Image.Image,
    text: str,
    xy: tuple[int, int],
    font: ImageFont.FreeTypeFont,
) -> tuple[int, int, int, int]:
    mask = Image.new("L", FEATURE_SIZE, 0)
    draw = ImageDraw.Draw(mask)
    draw.text(xy, text, font=font, fill=255, stroke_width=1, stroke_fill=255)

    shadow = mask.filter(ImageFilter.GaussianBlur(6))
    shifted = Image.new("L", FEATURE_SIZE, 0)
    shifted.paste(shadow, (2, 4))
    canvas.alpha_composite(
        Image.composite(
            Image.new("RGBA", FEATURE_SIZE, (0, 0, 0, 210)),
            Image.new("RGBA", FEATURE_SIZE),
            shifted,
        )
    )

    gradient = Image.new("RGBA", FEATURE_SIZE, FEATURE_IVORY)
    gp = gradient.load()
    for y in range(max(0, xy[1] - 8), min(FEATURE_SIZE[1], xy[1] + 100)):
        t = min(1.0, max(0.0, (y - xy[1]) / 82))
        color = (
            round(FEATURE_IVORY[0] + (FEATURE_GOLD[0] - FEATURE_IVORY[0]) * t),
            round(FEATURE_IVORY[1] + (FEATURE_GOLD[1] - FEATURE_IVORY[1]) * t),
            round(FEATURE_IVORY[2] + (FEATURE_GOLD[2] - FEATURE_IVORY[2]) * t),
            255,
        )
        for x in range(FEATURE_SIZE[0]):
            gp[x, y] = color
    canvas.alpha_composite(
        Image.composite(gradient, Image.new("RGBA", FEATURE_SIZE), mask)
    )
    bbox = mask.getbbox()
    if bbox is None:
        raise ValueError("Feature headline did not render")
    return bbox


def build_feature(language: str, config: dict[str, object]) -> Image.Image:
    canvas = left_textless_feature_base()
    logo = Image.open(LOGO).convert("RGBA")
    logo.thumbnail((382, 58), Image.Resampling.LANCZOS)
    canvas.alpha_composite(logo, (78, 104))

    draw = ImageDraw.Draw(canvas)
    headline = str(config["feature_headline"])
    feature_face = font_that_fits(
        draw,
        headline,
        Path(config["feature_headline_font"]),
        max_width=600,
        start=70 if language == "en" else 76,
        stop=52,
    )
    bbox = draw_feature_gradient_text(canvas, headline, (54, 205), feature_face)

    rule_y = max(301, bbox[3] + 12)
    rule = Image.new("RGBA", FEATURE_SIZE, (0, 0, 0, 0))
    rule_draw = ImageDraw.Draw(rule)
    rule_draw.line((40, rule_y, 608, rule_y), fill=(184, 121, 24, 255), width=2)
    glow = Image.new("L", FEATURE_SIZE, 0)
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse((592, rule_y - 9, 610, rule_y + 9), fill=255)
    blur = glow.filter(ImageFilter.GaussianBlur(8))
    rule.alpha_composite(
        Image.composite(
            Image.new("RGBA", FEATURE_SIZE, (255, 196, 53, 210)),
            Image.new("RGBA", FEATURE_SIZE),
            blur,
        )
    )
    rule_draw.ellipse((598, rule_y - 3, 604, rule_y + 3), fill=(255, 250, 220, 255))
    canvas.alpha_composite(rule)

    subline = str(config["feature_subline"])
    sub_face = font_that_fits(
        draw,
        subline,
        Path(config["body_font"]),
        max_width=570,
        start=31 if language == "en" else 29,
        stop=23,
        index=int(config["body_bold_index"]),
    )
    draw.text((72, rule_y + 24), subline, font=sub_face, fill=(246, 239, 249, 255))
    return canvas.convert("RGB")


def build_phone(language: str, asset: dict[str, object], config: dict[str, object]) -> Image.Image:
    base.HEADLINE_FONT = str(config["phone_headline_font"])
    base.BODY_FONT = str(config["body_font"])
    base.BODY_REGULAR_INDEX = int(config["body_index"])
    base.BODY_BOLD_INDEX = int(config["body_bold_index"])

    canvas = base.cover(Image.open(base.SOURCE / str(asset["background"])), base.PHONE_SIZE)
    base.add_copy_readability(canvas)
    headline_bottom = base.draw_gradient_headline(canvas, str(asset["headline"]))

    draw = ImageDraw.Draw(canvas)
    subline_y = min(headline_bottom + 34, 342)
    subline = str(asset["subline"])
    sub_face = font_that_fits(
        draw,
        subline,
        Path(config["body_font"]),
        max_width=850,
        start=31,
        stop=23,
        index=int(config["body_bold_index"]),
    )
    draw.text((58, subline_y), subline, font=sub_face, fill=base.MUTED)
    rule_y = subline_y + 59
    draw.rounded_rectangle((58, rule_y, 254, rule_y + 5), 2, fill=base.GOLD)
    draw.ellipse((260, rule_y - 3, 270, rule_y + 7), fill=base.GOLD)

    source = base.ad_free_screen(RAW_LOCALIZED / language / str(asset["screen"]))
    crop_top = int(asset["screen_crop_top"])
    screen = source.crop((0, crop_top, 1080, crop_top + base.SCREEN_SOURCE_HEIGHT))
    base.place_gameplay_portal(canvas, screen, bool(asset["fade_damage_top"]))
    return canvas.convert("RGB")


def make_contact_sheet(language: str, output: Path, phone_paths: list[Path]) -> Path:
    sheet = Image.new("RGB", (1280, 1030), (10, 7, 15))
    feature = Image.open(output / f"01-feature-graphic-{language}-1024x500.png").convert("RGB")
    sheet.paste(feature, (128, 36))
    for x, path in zip((52, 358, 664, 970), phone_paths):
        thumb = Image.open(path).convert("RGB").resize((258, 459), Image.Resampling.LANCZOS)
        sheet.paste(thumb, (x, 555))
    audit_path = AUDIT / f"selected-{language}-relic-v3-contact-sheet.png"
    sheet.save(audit_path, "PNG", optimize=True)
    return audit_path


def main() -> None:
    AUDIT.mkdir(parents=True, exist_ok=True)
    for language, config in LANGUAGES.items():
        output = ROOT / "final" / f"selected-{language}-relic-v3"
        output.mkdir(parents=True, exist_ok=True)

        feature = build_feature(language, config)
        feature.save(
            output / f"01-feature-graphic-{language}-1024x500.png",
            "PNG",
            optimize=True,
        )

        phone_paths: list[Path] = []
        for asset in config["assets"]:
            image = build_phone(language, asset, config)
            output_path = output / str(asset["output"])
            image.save(output_path, "PNG", optimize=True)
            phone_paths.append(output_path)
        make_contact_sheet(language, output, phone_paths)


if __name__ == "__main__":
    main()
