#!/usr/bin/env python3
"""Build the localized NULL PLAYING Google Ads image campaign.

ImageGen supplies textless campaign scenery. Exact brand typography, localized
headlines, and real emulator UI are composited locally for deterministic output.
"""

from pathlib import Path
from typing import Optional

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageFont


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "source" / "google-ads-20260828"
OUTPUT = ROOT / "final" / "google-ads-20260828"
AUDIT = ROOT / "audit"
RAW = ROOT / "raw"
RAW_LOCALIZED = RAW / "localized"
LOGO = ROOT.parents[2] / "app/src/simple/res/drawable-nodpi/title_logo_null_playing_v2.webp"
SELECTED_KO_FEATURE = ROOT / "final" / "01-feature-watch-only-ko-v2-1024x500.png"
FEATURE_TEXTLESS_LEFT = RAW / "feature-bg-gate-imagegen.png"
PLAY_BADGE = ROOT / "source" / "google-play-badge" / "en_badge_web_generic.png"

AD_CROP_TOP = 158

LANDSCAPE_SIZE = (1200, 628)
SQUARE_SIZE = (1200, 1200)
PORTRAIT_SIZE = (1200, 1500)

FONT_DIR = Path("/System/Library/Fonts")
KO_FONT = FONT_DIR / "AppleSDGothicNeo.ttc"
EN_FONT = FONT_DIR / "NewYork.ttf"
EN_BODY_FONT = FONT_DIR / "Avenir Next.ttc"
CONTACT_FONT = FONT_DIR / "Supplemental/Arial Bold.ttf"
JA_FONT = next(FONT_DIR.glob("*角*W7*"))
JA_BODY_FONT = next(FONT_DIR.glob("*角*W4*"))

IVORY = (255, 249, 239, 255)
GOLD = (229, 184, 80, 255)
GOLD_DARK = (125, 84, 28, 255)
PURPLE_GLOW = (158, 92, 224, 155)


LANGUAGES = {
    "ko": {
        "font": KO_FONT,
        "font_index": 6,
        "screens": RAW,
        "headlines": {
            "landscape": "아무것도 하지 않는 RPG",
            "square": "지켜보기만 하는 RPG",
            "portrait": "손대지 않아도 성장하는 RPG",
        },
    },
    "en": {
        "font": EN_FONT,
        "font_index": 0,
        "font_variation": "Semibold",
        "screens": RAW_LOCALIZED / "en",
        "headlines": {
            "landscape": "Just watch.",
            "square": "Just watch.",
            "portrait": "Just watch.",
        },
    },
    "ja": {
        "font": JA_FONT,
        "font_index": 0,
        "screens": RAW_LOCALIZED / "ja",
        "headlines": {
            "landscape": "何もしないRPG",
            "square": "見守るだけのRPG",
            "portrait": "操作しなくても育つRPG",
        },
    },
}


def cover(image: Image.Image, size: tuple[int, int], align_y: float = 0.5) -> Image.Image:
    image = image.convert("RGBA")
    scale = max(size[0] / image.width, size[1] / image.height)
    resized = image.resize(
        (round(image.width * scale), round(image.height * scale)),
        Image.Resampling.LANCZOS,
    )
    left = max(0, (resized.width - size[0]) // 2)
    extra_y = max(0, resized.height - size[1])
    top = round(extra_y * align_y)
    return resized.crop((left, top, left + size[0], top + size[1]))


def contain(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    image = image.convert("RGBA")
    scale = min(size[0] / image.width, size[1] / image.height)
    return image.resize(
        (round(image.width * scale), round(image.height * scale)),
        Image.Resampling.LANCZOS,
    )


def selected_korean_feature_base() -> Image.Image:
    """Remove only the Korean copy while preserving the approved Korean art."""
    selected = Image.open(SELECTED_KO_FEATURE).convert("RGBA")
    textless_left = cover(Image.open(FEATURE_TEXTLESS_LEFT), selected.size)

    mask = Image.new("L", selected.size, 0)
    pixels = mask.load()
    for x in range(selected.width):
        if x <= 620:
            alpha = 255
        elif x >= 760:
            alpha = 0
        else:
            t = (x - 620) / 140
            smooth = t * t * (3 - 2 * t)
            alpha = round(255 * (1 - smooth))
        for y in range(selected.height):
            pixels[x, y] = alpha
    return Image.composite(textless_left, selected, mask)


def selected_korean_focused_base(
    canvas_size: tuple[int, int],
    art_box: tuple[int, int, int, int],
    source_left: int,
    blur_radius: int,
    brightness: float,
) -> Image.Image:
    feature = selected_korean_feature_base()
    backdrop = cover(feature, canvas_size).filter(ImageFilter.GaussianBlur(blur_radius))
    canvas = ImageEnhance.Brightness(backdrop).enhance(brightness)

    x0, y0, x1, y1 = art_box
    focused_source = feature.crop((source_left, 0, feature.width, feature.height))
    sharp = cover(focused_source, (x1 - x0, y1 - y0))

    fade = Image.new("L", sharp.size, 255)
    fade_draw = ImageDraw.Draw(fade)
    edge_fade = min(130, sharp.height // 7)
    left_fade = min(170, sharp.width // 4)
    for y in range(edge_fade):
        t = y / max(1, edge_fade - 1)
        smooth = t * t * (3 - 2 * t)
        alpha = round(255 * smooth)
        fade_draw.line((0, y, sharp.width, y), fill=alpha)
        fade_draw.line(
            (0, sharp.height - 1 - y, sharp.width, sharp.height - 1 - y),
            fill=alpha,
        )
    for x in range(left_fade):
        t = x / max(1, left_fade - 1)
        smooth = t * t * (3 - 2 * t)
        column = Image.new("L", (1, sharp.height), round(255 * smooth))
        current = fade.crop((x, 0, x + 1, sharp.height))
        fade.paste(ImageChops.multiply(current, column), (x, 0))

    sharp.putalpha(fade)
    canvas.alpha_composite(sharp, (x0, y0))
    return canvas


def selected_korean_square_base() -> Image.Image:
    return selected_korean_focused_base(
        SQUARE_SIZE,
        art_box=(420, 130, 1200, 1060),
        source_left=500,
        blur_radius=30,
        brightness=0.25,
    )


def selected_korean_portrait_base() -> Image.Image:
    return selected_korean_focused_base(
        PORTRAIT_SIZE,
        art_box=(290, 170, 1200, 1390),
        source_left=555,
        blur_radius=34,
        brightness=0.23,
    )


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size[0] - 1, size[1] - 1), radius, fill=255
    )
    return mask


def add_copy_readability(canvas: Image.Image, width: int, height: int) -> None:
    overlay = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    pixels = overlay.load()
    for y in range(min(height, canvas.height)):
        vertical = max(0.0, 1.0 - y / max(1, height))
        for x in range(min(width, canvas.width)):
            horizontal = max(0.0, 1.0 - x / max(1, width))
            alpha = round(220 * (horizontal**1.55) * (0.68 + 0.32 * vertical))
            pixels[x, y] = (3, 2, 8, alpha)
    canvas.alpha_composite(overlay)


def add_logo(canvas: Image.Image, xy: tuple[int, int], max_size: tuple[int, int]) -> None:
    logo = Image.open(LOGO).convert("RGBA")
    logo.thumbnail(max_size, Image.Resampling.LANCZOS)
    canvas.alpha_composite(logo, xy)


def add_brand(
    canvas: Image.Image,
    language: str,
    xy: tuple[int, int],
    max_size: tuple[int, int],
) -> None:
    if language != "en":
        add_logo(canvas, xy, max_size)
        return

    # Google Ads can interpret the official all-caps raster wordmark as
    # excessive capitalization. Preserve the brand name while rendering a
    # natural title-case treatment for the English ad assets.
    text = "Null Playing"
    face = font_that_fits(
        text,
        EN_FONT,
        0,
        max_size[0],
        86,
        60,
        variation_name="Semibold",
    )
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    bbox = draw.textbbox(xy, text, font=face, stroke_width=1)
    shadow = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(shadow).text(xy, text, font=face, fill=225, stroke_width=1)
    shadow = shadow.filter(ImageFilter.GaussianBlur(8))
    shadow_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow_layer.putalpha(shadow)
    canvas.alpha_composite(shadow_layer, (3, 5))
    draw.text(
        xy,
        text,
        font=face,
        fill=(245, 224, 174, 255),
        stroke_width=1,
        stroke_fill=(105, 69, 23, 255),
    )
    rule_y = bbox[3] + 8
    draw.line((bbox[0], rule_y, bbox[2], rule_y), fill=GOLD_DARK, width=2)
    canvas.alpha_composite(layer)


def draw_campaign_subline(
    canvas: Image.Image,
    text: str,
    xy: tuple[int, int],
    max_width: int,
    start: int = 32,
    font_index: int = 0,
    font_path: Path = EN_BODY_FONT,
) -> tuple[int, int, int, int]:
    face = font_that_fits(text, font_path, font_index, max_width, start, 24)
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    bbox = draw.textbbox(xy, text, font=face)
    draw.text(xy, text, font=face, fill=(239, 232, 242, 245))
    canvas.alpha_composite(layer)
    return bbox


def add_google_play_badge(
    canvas: Image.Image,
    xy: tuple[int, int],
    max_size: tuple[int, int] = (190, 74),
) -> None:
    badge = Image.open(PLAY_BADGE).convert("RGBA")
    badge.thumbnail(max_size, Image.Resampling.LANCZOS)
    canvas.alpha_composite(badge, xy)


def draw_landscape_wordmark(
    canvas: Image.Image,
    xy: tuple[int, int] = (64, 48),
    max_width: int = 470,
    start: int = 88,
    stop: int = 68,
) -> tuple[int, int, int, int]:
    face = font_that_fits(
        "Null Playing",
        EN_FONT,
        0,
        max_width,
        start,
        stop,
        variation_name="Semibold",
    )
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    full_text = "Null Playing"
    bbox = draw.textbbox(xy, full_text, font=face, stroke_width=1)

    shadow = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(shadow).text(xy, full_text, font=face, fill=220, stroke_width=1)
    shadow = shadow.filter(ImageFilter.GaussianBlur(8))
    shadow_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow_layer.putalpha(shadow)
    canvas.alpha_composite(shadow_layer, (3, 5))

    null_text = "Null "
    draw.text(
        xy,
        null_text,
        font=face,
        fill=(246, 240, 231, 255),
        stroke_width=1,
        stroke_fill=(64, 45, 29, 255),
    )
    playing_x = round(xy[0] + draw.textlength(null_text, font=face))
    draw.text(
        (playing_x, xy[1]),
        "Playing",
        font=face,
        fill=(229, 184, 80, 255),
        stroke_width=1,
        stroke_fill=(82, 52, 19, 255),
    )

    canvas.alpha_composite(layer)
    return bbox


def draw_campaign_headline(
    canvas: Image.Image,
    text: str,
    xy: tuple[int, int] = (64, 228),
    max_width: int = 560,
    start: int = 76,
    stop: int = 58,
    font_path: Path = EN_BODY_FONT,
    font_index: int = 2,
) -> tuple[int, int, int, int]:
    face = font_that_fits(text, font_path, font_index, max_width, start, stop)
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    bbox = draw.textbbox(xy, text, font=face)

    shadow = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(shadow).text(xy, text, font=face, fill=235)
    shadow = shadow.filter(ImageFilter.GaussianBlur(9))
    shadow_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow_layer.putalpha(shadow)
    canvas.alpha_composite(shadow_layer, (3, 6))

    draw.text(xy, text, font=face, fill=IVORY)
    if text.endswith("."):
        core = text[:-1]
        period_x = round(xy[0] + draw.textlength(core, font=face))
        draw.text((period_x, xy[1]), ".", font=face, fill=GOLD)
    canvas.alpha_composite(layer)
    return bbox


def font_that_fits(
    text: str,
    font_path: Path,
    font_index: int,
    max_width: int,
    start: int,
    stop: int,
    variation_name: Optional[str] = None,
) -> ImageFont.FreeTypeFont:
    probe = Image.new("L", (max_width + 400, start * 3), 0)
    draw = ImageDraw.Draw(probe)
    for size in range(start, stop - 1, -2):
        face = ImageFont.truetype(str(font_path), size=size, index=font_index)
        if variation_name is not None:
            face.set_variation_by_name(variation_name.encode("ascii"))
        bbox = draw.textbbox((0, 0), text, font=face, stroke_width=1)
        if bbox[2] - bbox[0] <= max_width:
            return face
    face = ImageFont.truetype(str(font_path), size=stop, index=font_index)
    if variation_name is not None:
        face.set_variation_by_name(variation_name.encode("ascii"))
    return face


def draw_gradient_headline(
    canvas: Image.Image,
    text: str,
    font_path: Path,
    font_index: int,
    xy: tuple[int, int],
    max_width: int,
    start: int,
    stop: int,
    variation_name: Optional[str] = None,
) -> tuple[int, int, int, int]:
    face = font_that_fits(
        text,
        font_path,
        font_index,
        max_width,
        start,
        stop,
        variation_name=variation_name,
    )
    mask = Image.new("L", canvas.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.text(xy, text, font=face, fill=255, stroke_width=1, stroke_fill=255)

    shadow = mask.filter(ImageFilter.GaussianBlur(9))
    shifted = Image.new("L", canvas.size, 0)
    shifted.paste(shadow, (4, 7))
    canvas.alpha_composite(
        Image.composite(
            Image.new("RGBA", canvas.size, (0, 0, 0, 220)),
            Image.new("RGBA", canvas.size),
            shifted,
        )
    )

    bbox = mask.getbbox()
    if bbox is None:
        raise ValueError(f"Headline did not render: {text}")

    gradient = Image.new("RGBA", canvas.size, IVORY)
    gp = gradient.load()
    top, bottom = bbox[1], max(bbox[1] + 1, bbox[3])
    for y in range(top, bottom):
        t = (y - top) / max(1, bottom - top)
        color = (
            round(IVORY[0] + (GOLD[0] - IVORY[0]) * t),
            round(IVORY[1] + (GOLD[1] - IVORY[1]) * t),
            round(IVORY[2] + (GOLD[2] - IVORY[2]) * t),
            255,
        )
        for x in range(bbox[0], min(canvas.width, bbox[2] + 2)):
            gp[x, y] = color
    canvas.alpha_composite(
        Image.composite(gradient, Image.new("RGBA", canvas.size), mask)
    )

    rule = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    rule_draw = ImageDraw.Draw(rule)
    rule_y = bbox[3] + 24
    rule_end = min(canvas.width - 70, bbox[0] + max(280, bbox[2] - bbox[0]))
    rule_draw.line((bbox[0], rule_y, rule_end, rule_y), fill=GOLD_DARK, width=3)
    glow_mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(glow_mask).ellipse(
        (rule_end - 11, rule_y - 11, rule_end + 11, rule_y + 11), fill=255
    )
    glow = glow_mask.filter(ImageFilter.GaussianBlur(10))
    rule.alpha_composite(
        Image.composite(
            Image.new("RGBA", canvas.size, (255, 196, 54, 205)),
            Image.new("RGBA", canvas.size),
            glow,
        )
    )
    rule_draw.ellipse(
        (rule_end - 3, rule_y - 3, rule_end + 3, rule_y + 3),
        fill=(255, 250, 220, 255),
    )
    canvas.alpha_composite(rule)
    return bbox


def ad_free_screen(path: Path) -> Image.Image:
    source = Image.open(path).convert("RGBA")
    if source.size != (1080, 2340):
        raise ValueError(f"Unexpected emulator screenshot size: {source.size} ({path})")
    return source.crop((0, AD_CROP_TOP, source.width, source.height))


def place_gameplay_portal(
    canvas: Image.Image,
    screen: Image.Image,
    box: tuple[int, int, int, int],
    crop_top: int,
) -> None:
    x0, y0, x1, y1 = box
    size = (x1 - x0, y1 - y0)
    source_aspect = size[0] / size[1]
    crop_height = round(screen.width / source_aspect)
    crop_top = min(crop_top, max(0, screen.height - crop_height))
    cropped = screen.crop(
        (0, crop_top, screen.width, min(screen.height, crop_top + crop_height))
    )
    resized = cover(cropped, size).filter(
        ImageFilter.UnsharpMask(radius=0.9, percent=112, threshold=3)
    )

    radius = 34
    mask = rounded_mask(size, radius)
    fade = Image.new("L", size, 255)
    fade_draw = ImageDraw.Draw(fade)
    fade_height = min(190, size[1] // 3)
    for y in range(fade_height):
        t = y / max(1, fade_height - 1)
        smooth = t * t * (3 - 2 * t)
        fade_draw.line((0, y, size[0], y), fill=round(255 * smooth))
    mask = ImageChops.multiply(mask, fade)

    shadow_mask = Image.new("L", canvas.size, 0)
    shadow_draw = ImageDraw.Draw(shadow_mask)
    shadow_draw.rounded_rectangle(
        (x0 - 12, y0 + 14, x1 + 12, y1 + 28), radius + 10, fill=225
    )
    shadow = shadow_mask.filter(ImageFilter.GaussianBlur(28))
    canvas.alpha_composite(
        Image.composite(
            Image.new("RGBA", canvas.size, (0, 0, 0, 225)),
            Image.new("RGBA", canvas.size),
            shadow,
        )
    )

    glow_mask = Image.new("L", canvas.size, 0)
    glow_draw = ImageDraw.Draw(glow_mask)
    glow_draw.rounded_rectangle(
        (x0 - 5, y0 - 5, x1 + 5, y1 + 5), radius + 5, outline=205, width=10
    )
    glow = glow_mask.filter(ImageFilter.GaussianBlur(18))
    canvas.alpha_composite(
        Image.composite(
            Image.new("RGBA", canvas.size, PURPLE_GLOW),
            Image.new("RGBA", canvas.size),
            glow,
        )
    )

    portal = Image.new("RGBA", size, (0, 0, 0, 0))
    portal.paste(resized, (0, 0), mask)
    canvas.alpha_composite(portal, (x0, y0))

    border = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(border)
    draw.rounded_rectangle((x0, y0, x1 - 1, y1 - 1), radius, outline=GOLD_DARK, width=5)
    draw.rounded_rectangle(
        (x0 + 4, y0 + 4, x1 - 5, y1 - 5),
        radius - 4,
        outline=(238, 199, 101, 185),
        width=2,
    )
    border_alpha = border.getchannel("A")
    global_fade = Image.new("L", canvas.size, 255)
    global_fade.paste(fade, (x0, y0))
    border.putalpha(ImageChops.multiply(border_alpha, global_fade))
    canvas.alpha_composite(border)


def build_landscape(language: str, config: dict[str, object]) -> Image.Image:
    if language == "en":
        canvas = cover(selected_korean_feature_base(), LANDSCAPE_SIZE)
        add_copy_readability(canvas, width=760, height=LANDSCAPE_SIZE[1])
        draw_landscape_wordmark(canvas)
        draw_campaign_headline(
            canvas,
            str(config["headlines"]["landscape"]),
        )
        draw_campaign_subline(
            canvas,
            "Your hero grows on their own.",
            (66, 344),
            max_width=540,
            start=28,
            font_index=5,
        )
        add_google_play_badge(canvas, (48, 462), (206, 80))
        return canvas.convert("RGB")

    canvas = cover(Image.open(SOURCE / "01-landscape-textless-imagegen.png"), LANDSCAPE_SIZE)
    add_copy_readability(canvas, width=700, height=590)
    add_brand(canvas, language, (64, 82), (455, 70))
    draw_gradient_headline(
        canvas,
        str(config["headlines"]["landscape"]),
        Path(config["font"]),
        int(config["font_index"]),
        (64, 230 if language == "en" else 218),
        max_width=590,
        start=59 if language != "en" else 52,
        stop=39,
        variation_name=config.get("font_variation"),
    )
    return canvas.convert("RGB")


def build_square(language: str, config: dict[str, object]) -> Image.Image:
    if language in {"en", "ko", "ja"}:
        canvas = selected_korean_square_base()
        add_copy_readability(canvas, width=570, height=610)
        draw_landscape_wordmark(
            canvas,
            (54, 54),
            max_width=516,
            start=98,
            stop=77,
        )
        if language == "ko":
            headline = "아무것도 하지 않는 RPG"
            headline_width, headline_start, headline_stop = 540, 64, 48
            headline_font, headline_index = KO_FONT, 6
            subline = "영웅은 스스로 성장합니다."
            subline_width, subline_start = 360, 28
            subline_font, subline_index = KO_FONT, 4
        elif language == "ja":
            headline = "何もしないRPG"
            headline_width, headline_start, headline_stop = 520, 68, 52
            headline_font, headline_index = JA_FONT, 0
            subline = "英雄は自ら成長します"
            subline_width, subline_start = 360, 30
            subline_font, subline_index = JA_BODY_FONT, 0
        else:
            headline = str(config["headlines"]["square"])
            headline_width, headline_start, headline_stop = 390, 70, 54
            headline_font, headline_index = EN_BODY_FONT, 2
            subline = "Your hero grows on their own."
            subline_width, subline_start = 350, 26
            subline_font, subline_index = EN_BODY_FONT, 5
        draw_campaign_headline(
            canvas,
            headline,
            (56, 232),
            max_width=headline_width,
            start=headline_start,
            stop=headline_stop,
            font_path=headline_font,
            font_index=headline_index,
        )
        draw_campaign_subline(
            canvas,
            subline,
            (58, 334),
            max_width=subline_width,
            start=subline_start,
            font_index=subline_index,
            font_path=subline_font,
        )
        add_google_play_badge(canvas, (48, 1074), (214, 82))
        return canvas.convert("RGB")

    canvas = cover(Image.open(SOURCE / "02-square-textless-imagegen.png"), SQUARE_SIZE)
    add_copy_readability(canvas, width=870, height=500)
    add_brand(canvas, language, (64, 64), (455, 70))
    draw_gradient_headline(
        canvas,
        str(config["headlines"]["square"]),
        Path(config["font"]),
        int(config["font_index"]),
        (64, 208 if language == "en" else 176),
        max_width=700,
        start=67 if language != "en" else 58,
        stop=38,
        variation_name=config.get("font_variation"),
    )
    screen = ad_free_screen(Path(config["screens"]) / "lv50-clean-equipment.png")
    # Start below the combat layer so the equipment rows breathe inside the
    # portal and no floating damage number survives the fade.
    place_gameplay_portal(canvas, screen, (90, 540, 1110, 1160), crop_top=880)
    return canvas.convert("RGB")


def build_portrait(language: str, config: dict[str, object]) -> Image.Image:
    if language in {"en", "ko", "ja"}:
        canvas = selected_korean_portrait_base()
        add_copy_readability(canvas, width=600, height=650)
        draw_landscape_wordmark(
            canvas,
            (54, 58),
            max_width=528,
            start=101,
            stop=79,
        )
        if language == "ko":
            headline = "아무것도 하지 않는 RPG"
            headline_width, headline_start, headline_stop = 540, 66, 50
            headline_font, headline_index = KO_FONT, 6
            subline = "영웅은 스스로 성장합니다."
            subline_width, subline_start = 370, 29
            subline_font, subline_index = KO_FONT, 4
        elif language == "ja":
            headline = "何もしないRPG"
            headline_width, headline_start, headline_stop = 520, 68, 52
            headline_font, headline_index = JA_FONT, 0
            subline = "英雄は自ら成長します"
            subline_width, subline_start = 370, 31
            subline_font, subline_index = JA_BODY_FONT, 0
        else:
            headline = str(config["headlines"]["portrait"])
            headline_width, headline_start, headline_stop = 405, 72, 56
            headline_font, headline_index = EN_BODY_FONT, 2
            subline = "Your hero grows on their own."
            subline_width, subline_start = 370, 27
            subline_font, subline_index = EN_BODY_FONT, 5
        draw_campaign_headline(
            canvas,
            headline,
            (56, 246),
            max_width=headline_width,
            start=headline_start,
            stop=headline_stop,
            font_path=headline_font,
            font_index=headline_index,
        )
        draw_campaign_subline(
            canvas,
            subline,
            (58, 352),
            max_width=subline_width,
            start=subline_start,
            font_index=subline_index,
            font_path=subline_font,
        )
        add_google_play_badge(canvas, (48, 1358), (222, 86))
        return canvas.convert("RGB")

    canvas = cover(
        Image.open(SOURCE / "03-portrait-textless-imagegen.png"),
        PORTRAIT_SIZE,
        align_y=0.0,
    )
    add_copy_readability(canvas, width=900, height=570)
    add_brand(canvas, language, (64, 62), (455, 70))
    draw_gradient_headline(
        canvas,
        str(config["headlines"]["portrait"]),
        Path(config["font"]),
        int(config["font_index"]),
        (64, 210 if language == "en" else 178),
        max_width=825,
        start=68 if language != "en" else 60,
        stop=42,
        variation_name=config.get("font_variation"),
    )
    screen = ad_free_screen(Path(config["screens"]) / "lv50-clean-character.png")
    place_gameplay_portal(canvas, screen, (80, 500, 1120, 1460), crop_top=800)
    return canvas.convert("RGB")


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "PNG", optimize=True)


def make_contact_sheet(paths: dict[str, dict[str, Path]]) -> Path:
    canvas = Image.new("RGB", (1960, 1560), (8, 5, 13))
    draw = ImageDraw.Draw(canvas)
    label_font = ImageFont.truetype(str(CONTACT_FONT), size=28)
    locale_font = ImageFont.truetype(str(CONTACT_FONT), size=34)

    columns = {
        "landscape": (190, 590, 310),
        "square": (825, 420, 420),
        "portrait": (1360, 336, 420),
    }
    labels = {
        "landscape": "1.91:1   1200 x 628",
        "square": "1:1   1200 x 1200",
        "portrait": "4:5   1200 x 1500",
    }
    for key, (x, width, _) in columns.items():
        draw.text((x, 34), labels[key], font=label_font, fill=(230, 193, 105))

    locale_labels = {"ko": "KOREA", "en": "ENGLISH", "ja": "JAPAN"}
    for row, language in enumerate(("ko", "en", "ja")):
        row_y = 105 + row * 480
        draw.text((24, row_y + 180), locale_labels[language], font=locale_font, fill=(244, 238, 247))
        for key, (x, width, height) in columns.items():
            thumb = contain(Image.open(paths[language][key]), (width, height)).convert("RGB")
            px = x + (width - thumb.width) // 2
            py = row_y + (height - thumb.height) // 2
            canvas.paste(thumb, (px, py))

    audit_path = AUDIT / "google-ads-20260828-contact-sheet.png"
    save_png(canvas, audit_path)
    return audit_path


def main() -> None:
    built: dict[str, dict[str, Path]] = {}
    for language, config in LANGUAGES.items():
        language_output = OUTPUT / language
        language_output.mkdir(parents=True, exist_ok=True)
        items = {
            "landscape": (
                build_landscape(language, config),
                language_output / f"01-landscape-{language}-1200x628.png",
            ),
            "square": (
                build_square(language, config),
                language_output / f"02-square-{language}-1200x1200.png",
            ),
            "portrait": (
                build_portrait(language, config),
                language_output / f"03-portrait-{language}-1200x1500.png",
            ),
        }
        built[language] = {}
        for key, (image, path) in items.items():
            save_png(image, path)
            built[language][key] = path
    make_contact_sheet(built)


if __name__ == "__main__":
    main()
