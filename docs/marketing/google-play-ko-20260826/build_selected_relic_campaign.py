#!/usr/bin/env python3
"""Build the selected Korean "Relic of Time" Google Play campaign.

Generated art is used only as scenery. Korean marketing copy and the real emulator
screens are composited locally so store text and in-game UI remain exact.
"""

from pathlib import Path
from shutil import copy2

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont


ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
SOURCE = ROOT / "source" / "phone-relic-selected"
OUTPUT = ROOT / "final" / "selected-ko-relic-v3"
AUDIT = ROOT / "audit"

PHONE_SIZE = (1080, 1920)
AD_CROP_TOP = 158
SCREEN_X = 60
SCREEN_Y = 588
SCREEN_WIDTH = 960
SCREEN_SOURCE_HEIGHT = 1500
HEADLINE_SPACING = 18
SCREEN_FADE_START = 310
SCREEN_FADE_END = 640

HEADLINE_FONT = "/System/Library/Fonts/Supplemental/AppleMyungjo.ttf"
BODY_FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
BODY_REGULAR_INDEX = 0
BODY_BOLD_INDEX = 6

IVORY = (255, 249, 239, 255)
MUTED = (220, 210, 220, 255)
GOLD = (229, 184, 80, 255)
GOLD_DARK = (127, 88, 30, 255)


ASSETS = (
    {
        "background": "01-hourglass-background.png",
        "screen": "lv50-clean-main.png",
        "screen_crop_top": 0,
        "fade_damage_top": False,
        "headline": "아무것도 안 했는데,\n모험은 계속됩니다",
        "subline": "당신이 쉬는 동안에도 영웅은 스스로 움직입니다",
        "output": "02-phone-auto-adventure-ko-1080x1920.png",
    },
    {
        "background": "02-growth-astrolabe-background.png",
        "screen": "lv50-clean-character.png",
        "screen_crop_top": 360,
        "fade_damage_top": True,
        "headline": "돌아왔을 뿐인데,\n더 강해졌습니다",
        "subline": "능력치와 스킬 숙련도까지 차곡차곡",
        "output": "03-phone-growth-ko-1080x1920.png",
    },
    {
        "background": "03-equipment-orbit-background.png",
        "screen": "lv50-clean-equipment.png",
        "screen_crop_top": 360,
        "fade_damage_top": True,
        "headline": "장비까지\n알아서 바꿉니다",
        "subline": "전리품 비교부터 교체까지 자동",
        "output": "04-phone-equipment-ko-1080x1920.png",
    },
    {
        "background": "04-chronicle-background.png",
        "screen": "lv50-clean-quest.png",
        "screen_crop_top": 380,
        "fade_damage_top": True,
        "headline": "쌓인 시간은\n이야기가 됩니다",
        "subline": "완료한 모험과 다음 이야기를 한눈에",
        "output": "05-phone-chronicle-ko-1080x1920.png",
    },
)


def cover(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    image = image.convert("RGBA")
    scale = max(size[0] / image.width, size[1] / image.height)
    resized = image.resize(
        (round(image.width * scale), round(image.height * scale)),
        Image.Resampling.LANCZOS,
    )
    left = (resized.width - size[0]) // 2
    top = (resized.height - size[1]) // 2
    return resized.crop((left, top, left + size[0], top + size[1]))


def ad_free_screen(path: Path) -> Image.Image:
    source = Image.open(path).convert("RGBA")
    if source.size != (1080, 2340):
        raise ValueError(f"Unexpected emulator screenshot size: {source.size} ({path})")
    return source.crop((0, AD_CROP_TOP, source.width, source.height))


def body_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(
        BODY_FONT,
        size=size,
        index=BODY_BOLD_INDEX if bold else BODY_REGULAR_INDEX,
    )


def headline_font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(HEADLINE_FONT, size=size)


def fit_headline(draw: ImageDraw.ImageDraw, text: str, max_width: int) -> ImageFont.FreeTypeFont:
    for size in range(82, 59, -2):
        face = headline_font(size)
        bbox = draw.multiline_textbbox((0, 0), text, font=face, spacing=HEADLINE_SPACING)
        if bbox[2] - bbox[0] <= max_width:
            return face
    return headline_font(58)


def add_copy_readability(canvas: Image.Image) -> None:
    overlay = Image.new("RGBA", PHONE_SIZE, (0, 0, 0, 0))
    pixels = overlay.load()
    for y in range(0, 485):
        vertical = max(0.0, 1.0 - y / 520)
        for x in range(0, 840):
            horizontal = max(0.0, 1.0 - x / 860)
            alpha = round(224 * (horizontal ** 1.65) * (0.64 + 0.36 * vertical))
            pixels[x, y] = (3, 2, 8, alpha)
    canvas.alpha_composite(overlay)


def draw_gradient_headline(
    canvas: Image.Image,
    text: str,
    xy: tuple[int, int] = (54, 74),
) -> int:
    probe = ImageDraw.Draw(canvas)
    face = fit_headline(probe, text, max_width=720)
    mask = Image.new("L", PHONE_SIZE, 0)
    mask_draw = ImageDraw.Draw(mask)
    mask_draw.multiline_text(
        xy,
        text,
        font=face,
        fill=255,
        spacing=HEADLINE_SPACING,
        stroke_width=1,
        stroke_fill=255,
    )

    shadow = mask.filter(ImageFilter.GaussianBlur(10))
    shadow_layer = Image.new("RGBA", PHONE_SIZE, (0, 0, 0, 210))
    shifted = Image.new("L", PHONE_SIZE, 0)
    shifted.paste(shadow, (3, 6))
    canvas.alpha_composite(Image.composite(shadow_layer, Image.new("RGBA", PHONE_SIZE), shifted))

    gradient = Image.new("RGBA", PHONE_SIZE, IVORY)
    gp = gradient.load()
    top, bottom = 65, 265
    for y in range(top, min(bottom, PHONE_SIZE[1])):
        t = (y - top) / max(1, bottom - top)
        color = (
            round(255 + (224 - 255) * t),
            round(249 + (181 - 249) * t),
            round(239 + (83 - 239) * t),
            255,
        )
        for x in range(PHONE_SIZE[0]):
            gp[x, y] = color
    canvas.alpha_composite(Image.composite(gradient, Image.new("RGBA", PHONE_SIZE), mask))

    bbox = mask.getbbox()
    return bbox[3] if bbox else xy[1]


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def screen_fade_mask(size: tuple[int, int]) -> Image.Image:
    fade = Image.new("L", size, 255)
    draw = ImageDraw.Draw(fade)
    for y in range(min(SCREEN_FADE_END, size[1])):
        if y <= SCREEN_FADE_START:
            alpha = 0
        else:
            t = (y - SCREEN_FADE_START) / (SCREEN_FADE_END - SCREEN_FADE_START)
            smooth = t * t * (3 - 2 * t)
            alpha = round(255 * smooth)
        draw.line((0, y, size[0], y), fill=alpha)
    return fade


def global_screen_fade(local_fade: Image.Image) -> Image.Image:
    fade = Image.new("L", PHONE_SIZE, 255)
    draw = ImageDraw.Draw(fade)
    for y in range(SCREEN_Y):
        draw.line((0, y, PHONE_SIZE[0], y), fill=0)
    for local_y in range(local_fade.height):
        canvas_y = SCREEN_Y + local_y
        if canvas_y >= PHONE_SIZE[1]:
            break
        alpha = local_fade.getpixel((0, local_y))
        draw.line((0, canvas_y, PHONE_SIZE[0], canvas_y), fill=alpha)
    return fade


def place_gameplay_portal(canvas: Image.Image, screen: Image.Image, fade_top: bool) -> None:
    scale = SCREEN_WIDTH / screen.width
    resized = screen.resize(
        (SCREEN_WIDTH, round(screen.height * scale)),
        Image.Resampling.LANCZOS,
    ).filter(ImageFilter.UnsharpMask(radius=1.0, percent=115, threshold=3))

    radius = 34
    mask = rounded_mask(resized.size, radius)
    global_fade = None
    if fade_top:
        local_fade = screen_fade_mask(resized.size)
        mask = ImageChops.multiply(mask, local_fade)
        global_fade = global_screen_fade(local_fade)

    glow_mask = Image.new("L", PHONE_SIZE, 0)
    glow_draw = ImageDraw.Draw(glow_mask)
    glow_draw.rounded_rectangle(
        (SCREEN_X - 5, SCREEN_Y - 5, SCREEN_X + resized.width + 5, SCREEN_Y + resized.height + 5),
        radius + 5,
        outline=180,
        width=9,
    )
    glow = glow_mask.filter(ImageFilter.GaussianBlur(18))
    if global_fade is not None:
        glow = ImageChops.multiply(glow, global_fade)
    canvas.alpha_composite(
        Image.composite(Image.new("RGBA", PHONE_SIZE, (160, 95, 225, 150)), Image.new("RGBA", PHONE_SIZE), glow)
    )

    shadow_mask = Image.new("L", PHONE_SIZE, 0)
    shadow_draw = ImageDraw.Draw(shadow_mask)
    shadow_draw.rounded_rectangle(
        (SCREEN_X - 8, SCREEN_Y + 10, SCREEN_X + resized.width + 8, SCREEN_Y + resized.height + 24),
        radius + 8,
        fill=220,
    )
    shadow = shadow_mask.filter(ImageFilter.GaussianBlur(24))
    if global_fade is not None:
        shadow = ImageChops.multiply(shadow, global_fade)
    canvas.alpha_composite(
        Image.composite(Image.new("RGBA", PHONE_SIZE, (0, 0, 0, 220)), Image.new("RGBA", PHONE_SIZE), shadow)
    )

    portal = Image.new("RGBA", resized.size, (0, 0, 0, 0))
    portal.paste(resized, (0, 0), mask)
    canvas.alpha_composite(portal, (SCREEN_X, SCREEN_Y))

    border_layer = Image.new("RGBA", PHONE_SIZE, (0, 0, 0, 0))
    draw = ImageDraw.Draw(border_layer)
    rect = (SCREEN_X, SCREEN_Y, SCREEN_X + resized.width - 1, SCREEN_Y + resized.height - 1)
    draw.rounded_rectangle(rect, radius, outline=GOLD_DARK, width=5)
    draw.rounded_rectangle(
        (SCREEN_X + 3, SCREEN_Y + 3, SCREEN_X + resized.width - 4, SCREEN_Y + resized.height - 4),
        radius - 3,
        outline=(238, 199, 101, 190),
        width=2,
    )

    center = SCREEN_X + resized.width // 2
    draw.line((SCREEN_X + 42, SCREEN_Y - 1, center - 18, SCREEN_Y - 1), fill=GOLD, width=2)
    draw.line((center + 18, SCREEN_Y - 1, SCREEN_X + resized.width - 42, SCREEN_Y - 1), fill=GOLD, width=2)
    draw.polygon(
        ((center, SCREEN_Y - 9), (center + 9, SCREEN_Y), (center, SCREEN_Y + 9), (center - 9, SCREEN_Y)),
        outline=GOLD,
        fill=(22, 13, 31, 255),
    )
    if global_fade is not None:
        border_alpha = border_layer.getchannel("A")
        border_layer.putalpha(ImageChops.multiply(border_alpha, global_fade))
    canvas.alpha_composite(border_layer)


def build_phone(asset: dict[str, object]) -> Image.Image:
    canvas = cover(Image.open(SOURCE / str(asset["background"])), PHONE_SIZE)
    add_copy_readability(canvas)

    headline_bottom = draw_gradient_headline(canvas, str(asset["headline"]))
    draw = ImageDraw.Draw(canvas)
    subline_y = min(headline_bottom + 34, 342)
    draw.text((58, subline_y), str(asset["subline"]), font=body_font(31, True), fill=MUTED)
    rule_y = subline_y + 59
    draw.rounded_rectangle((58, rule_y, 254, rule_y + 5), 2, fill=GOLD)
    draw.ellipse((260, rule_y - 3, 270, rule_y + 7), fill=GOLD)

    source = ad_free_screen(RAW / str(asset["screen"]))
    crop_top = int(asset["screen_crop_top"])
    screen = source.crop((0, crop_top, 1080, crop_top + SCREEN_SOURCE_HEIGHT))
    place_gameplay_portal(canvas, screen, bool(asset["fade_damage_top"]))
    return canvas.convert("RGB")


def make_contact_sheet(phone_paths: list[Path]) -> None:
    sheet = Image.new("RGB", (1280, 1030), (10, 7, 15))
    feature = Image.open(OUTPUT / "01-feature-graphic-ko-1024x500.png").convert("RGB")
    sheet.paste(feature, (128, 36))
    x_positions = (52, 358, 664, 970)
    for x, path in zip(x_positions, phone_paths):
        thumb = Image.open(path).convert("RGB").resize((258, 459), Image.Resampling.LANCZOS)
        sheet.paste(thumb, (x, 555))
    sheet.save(AUDIT / "selected-ko-relic-v3-contact-sheet.png", "PNG", optimize=True)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    AUDIT.mkdir(parents=True, exist_ok=True)

    feature_source = ROOT / "final" / "01-feature-watch-only-ko-v2-1024x500.png"
    feature_output = OUTPUT / "01-feature-graphic-ko-1024x500.png"
    copy2(feature_source, feature_output)

    phone_paths: list[Path] = []
    for asset in ASSETS:
        image = build_phone(asset)
        output_path = OUTPUT / str(asset["output"])
        image.save(output_path, "PNG", optimize=True)
        phone_paths.append(output_path)

    make_contact_sheet(phone_paths)


if __name__ == "__main__":
    main()
