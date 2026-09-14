#!/usr/bin/env python3
"""Build Google Play feature graphics and phone screenshots from verified emulator captures."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont


ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
FINAL = ROOT / "final"

FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
FONT_REGULAR_INDEX = 0
FONT_BOLD_INDEX = 6

INK = (249, 245, 239, 255)
MUTED = (194, 184, 201, 255)
GOLD = (229, 184, 80, 255)
GOLD_SOFT = (171, 132, 57, 255)
PURPLE = (182, 104, 224, 255)
DARK = (13, 10, 20, 255)

FEATURE_SIZE = (1024, 500)
PHONE_SIZE = (1080, 2160)
AD_CROP_TOP = 158


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(
        FONT,
        size=size,
        index=FONT_BOLD_INDEX if bold else FONT_REGULAR_INDEX,
    )


def cover(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    source = image.convert("RGBA")
    scale = max(size[0] / source.width, size[1] / source.height)
    resized = source.resize(
        (round(source.width * scale), round(source.height * scale)),
        Image.Resampling.LANCZOS,
    )
    left = (resized.width - size[0]) // 2
    top = (resized.height - size[1]) // 2
    return resized.crop((left, top, left + size[0], top + size[1]))


def fit_width(image: Image.Image, width: int) -> Image.Image:
    height = round(image.height * width / image.width)
    return image.resize((width, height), Image.Resampling.LANCZOS)


def ad_free_screen(path: Path) -> Image.Image:
    source = Image.open(path).convert("RGBA")
    if source.size != (1080, 2340):
        raise ValueError(f"Unexpected emulator screenshot size: {source.size} ({path})")
    # Remove the complete 320x50dp banner region before any asset uses the screenshot.
    return source.crop((0, AD_CROP_TOP, source.width, source.height))


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def paste_card(
    canvas: Image.Image,
    image: Image.Image,
    xy: tuple[int, int],
    radius: int,
    border_width: int = 3,
    border_color: tuple[int, int, int, int] = GOLD_SOFT,
) -> None:
    shadow_pad = 22
    shadow = Image.new("RGBA", (image.width + shadow_pad * 2, image.height + shadow_pad * 2), (0, 0, 0, 0))
    shadow_mask = rounded_mask(image.size, radius)
    shadow_layer = Image.new("RGBA", image.size, (0, 0, 0, 205))
    shadow.paste(shadow_layer, (shadow_pad, shadow_pad), shadow_mask)
    shadow = shadow.filter(ImageFilter.GaussianBlur(14))
    canvas.alpha_composite(shadow, (xy[0] - shadow_pad, xy[1] - shadow_pad + 9))

    card = Image.new("RGBA", image.size, (0, 0, 0, 0))
    card.paste(image, (0, 0), rounded_mask(image.size, radius))
    card_draw = ImageDraw.Draw(card)
    card_draw.rounded_rectangle(
        (1, 1, image.width - 2, image.height - 2),
        radius,
        outline=border_color,
        width=border_width,
    )
    canvas.alpha_composite(card, xy)


def paste_logo(canvas: Image.Image, xy: tuple[int, int], width: int) -> None:
    logo = Image.open(ROOT.parents[2] / "app/src/simple/res/drawable-nodpi/title_logo_null_playing_v2.webp").convert("RGBA")
    logo = fit_width(logo, width)
    canvas.alpha_composite(logo, xy)


def add_left_readability(canvas: Image.Image, reach: int, max_alpha: int = 190) -> None:
    overlay = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    pixels = overlay.load()
    for x in range(min(reach, canvas.width)):
        alpha = round(max_alpha * (1 - x / reach) ** 1.7)
        for y in range(canvas.height):
            pixels[x, y] = (5, 4, 11, alpha)
    canvas.alpha_composite(overlay)


def draw_text_with_shadow(
    draw: ImageDraw.ImageDraw,
    xy: tuple[int, int],
    text: str,
    face: ImageFont.FreeTypeFont,
    fill: tuple[int, int, int, int],
    spacing: int = 4,
) -> None:
    shadow_xy = (xy[0] + 2, xy[1] + 3)
    draw.multiline_text(shadow_xy, text, font=face, fill=(0, 0, 0, 185), spacing=spacing)
    draw.multiline_text(xy, text, font=face, fill=fill, spacing=spacing)


def feature_gate() -> Image.Image:
    background = cover(Image.open(RAW / "feature-bg-gate-imagegen.png"), FEATURE_SIZE)
    add_left_readability(background, reach=690, max_alpha=205)
    draw = ImageDraw.Draw(background)
    paste_logo(background, (48, 52), 390)
    draw.text((50, 139), "AUTO PROGRESS RPG", font=font(18, True), fill=GOLD)
    draw_text_with_shadow(draw, (47, 174), "지켜보기만 하세요", font(45, True), INK)
    draw.text((50, 238), "영웅은 스스로 싸우고 성장합니다", font=font(22, False), fill=MUTED)
    draw.rounded_rectangle((49, 298, 416, 347), 24, fill=(29, 21, 40, 222), outline=GOLD_SOFT, width=2)
    draw.text((74, 310), "아무것도 하지 않아도 모험은 계속됩니다", font=font(16, True), fill=GOLD)
    return background


def feature_chronicle() -> Image.Image:
    background = cover(Image.open(RAW / "feature-bg-labyrinth-imagegen.png"), FEATURE_SIZE)
    add_left_readability(background, reach=720, max_alpha=220)
    draw = ImageDraw.Draw(background)
    paste_logo(background, (44, 42), 360)
    draw_text_with_shadow(draw, (43, 132), "당신이 없어도\n모험은 계속됩니다", font(36, True), INK, spacing=3)
    draw.text((46, 237), "전투 · 장비 · 기술 · 이야기까지", font=font(19, True), fill=GOLD)
    draw.text((46, 269), "모든 성장이 자동으로 이어집니다", font=font(18, False), fill=MUTED)
    draw.rounded_rectangle((45, 327, 262, 371), 22, fill=(32, 23, 43, 230), outline=GOLD_SOFT, width=2)
    draw.text((70, 337), "실제 LV.50 플레이", font=font(16, True), fill=GOLD)

    screen = ad_free_screen(RAW / "lv50-clean-quest.png")
    focus = screen.crop((0, 0, 1080, 1515))
    panel = cover(focus, (306, 442)).filter(ImageFilter.UnsharpMask(radius=1.2, percent=125, threshold=3))
    paste_card(background, panel, (686, 28), radius=24, border_width=3)
    return background


def phone_asset(
    source_name: str,
    background_name: str,
    headline: str,
    subline: str,
) -> Image.Image:
    background = cover(Image.open(RAW / background_name), PHONE_SIZE)
    background = background.filter(ImageFilter.GaussianBlur(11))
    background.alpha_composite(Image.new("RGBA", PHONE_SIZE, (6, 4, 13, 182)))
    draw = ImageDraw.Draw(background)

    paste_logo(background, (58, 44), 340)
    draw.rounded_rectangle((775, 53, 1021, 104), 25, fill=(28, 20, 39, 225), outline=GOLD_SOFT, width=2)
    draw.text((813, 65), "실제 LV.50 플레이", font=font(16, True), fill=GOLD)
    draw_text_with_shadow(draw, (57, 126), headline, font(44, True), INK)
    draw.text((60, 194), subline, font=font(24, False), fill=MUTED)
    draw.rounded_rectangle((59, 247, 218, 253), 3, fill=GOLD)

    screen = ad_free_screen(RAW / source_name)
    screen = fit_width(screen, 900).filter(ImageFilter.UnsharpMask(radius=1.1, percent=120, threshold=3))
    paste_card(background, screen, (90, 285), radius=34, border_width=3)
    return background


def save_png(image: Image.Image, name: str) -> None:
    path = FINAL / name
    image.convert("RGB").save(path, "PNG", optimize=True)


def main() -> None:
    FINAL.mkdir(parents=True, exist_ok=True)
    for old in FINAL.glob("*.png"):
        old.unlink()

    save_png(feature_gate(), "01-feature-watch-only-1024x500.png")
    save_png(feature_chronicle(), "02-feature-auto-chronicle-1024x500.png")
    save_png(
        phone_asset(
            "lv50-clean-character.png",
            "feature-bg-gate-imagegen.png",
            "레벨 50, 스스로 쌓인 성장",
            "능력치와 스킬 숙련도까지 자동",
        ),
        "03-phone-growth-lv50-1080x2160.png",
    )
    save_png(
        phone_asset(
            "lv50-clean-equipment.png",
            "feature-bg-gate-imagegen.png",
            "장비는 알아서 비교하고 교체",
            "더 강한 선택을 모험가가 스스로",
        ),
        "04-phone-equipment-lv50-1080x2160.png",
    )
    save_png(
        phone_asset(
            "lv50-clean-quest.png",
            "feature-bg-labyrinth-imagegen.png",
            "모험은 한 편의 연대기가 됩니다",
            "완료 · 진행 · 다음 이야기를 한눈에",
        ),
        "05-phone-chronicle-lv50-1080x2160.png",
    )


if __name__ == "__main__":
    main()
