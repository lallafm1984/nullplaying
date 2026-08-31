# Google Play Korean marketing assets

## Localized Google Ads image set — 2026-08-28

Upload-ready Google Ads creative is in `final/google-ads-20260828/`. Korean,
English, and Japanese each have the same three designs:

- `01-landscape-<locale>-1200x628.png` — 1.91:1 landscape key art
- `02-square-<locale>-1200x1200.png` — 1:1 equipment-progress creative
- `03-portrait-<locale>-1200x1500.png` — 4:5 growth/stat creative

Locale folders are `ko/`, `en/`, and `ja/`, for nine final RGB PNG files.
The scenery was created with built-in ImageGen; official logo pixels, exact
localized headlines, and real localized Lv.50 UI were composited locally.

Verification boundaries:

- the complete 158 px banner-ad region is removed before screenshot reuse
- combat damage numbers are excluded from the visible UI crops
- each key-art scene contains exactly one hero with no ghost or afterimage
- final dimensions are 1200x628, 1200x1200, and 1200x1500
- the combined review sheet is `audit/google-ads-20260828-contact-sheet.png`
- exact ImageGen prompts are recorded in `PROMPTS_GOOGLE_ADS_20260828.md`

Rebuild all nine images with:

`python3 docs/marketing/google-play-ko-20260826/build_google_ads_campaign.py`

## Localized upload sets — English and Japanese

The approved Korean `Relic of Time` art direction is now reproduced in two
additional upload-ready sets:

- `final/selected-en-relic-v3/` — English feature graphic plus four phone assets
- `final/selected-ja-relic-v3/` — Japanese feature graphic plus four phone assets

Each localized directory contains one 1024×500 RGB feature graphic and four
1080×1920 RGB phone images. The textless relic backgrounds, one-hero rule,
headline spacing, screenshot portal, and top fade match the selected Korean set.
All in-game text comes from real localized Lv.50 emulator captures. The full
158 px banner-ad region is removed before composition.

Localized listing documents:

- `PLAY_LISTING_EN.md` — English app name, short description, full description, and image copy
- `PLAY_LISTING_JA.md` — Japanese app name, short description, full description, and image copy
- `GOOGLE_ADS_APP_CAMPAIGN_COPY.md` — Korean, English, and Japanese Google Ads App campaign text assets

Review sheets:

- `audit/selected-en-relic-v3-contact-sheet.png`
- `audit/selected-ja-relic-v3-contact-sheet.png`

Rebuild both localized image sets with:

`python3 docs/marketing/google-play-ko-20260826/build_localized_relic_campaign.py`

## Current selected Korean upload set — Relic campaign v3

Upload-ready files are collected in `final/selected-ko-relic-v3/`:

- `01-feature-graphic-ko-1024x500.png` — approved 1024×500 RGB feature graphic
- `02-phone-auto-adventure-ko-1080x1920.png` — hourglass / unattended adventure
- `03-phone-growth-ko-1080x1920.png` — arcane astrolabe / automatic growth
- `04-phone-equipment-ko-1080x1920.png` — orbital relic / automatic equipment
- `05-phone-chronicle-ko-1080x1920.png` — enchanted chronicle / automatic story progress

The four phone images use one coherent `Relic of Time` visual system. Generated
art is limited to scenery, one hero, and the symbolic relic objects. Exact Korean
copy and real emulator screenshots are composited locally with
`build_selected_relic_campaign.py`.

Phone campaign copy:

- `아무것도 안 했는데, / 모험은 계속됩니다`
- `돌아왔을 뿐인데, / 더 강해졌습니다`
- `장비까지 / 알아서 바꿉니다`
- `쌓인 시간은 / 이야기가 됩니다`

Verification boundaries:

- every phone export is 1080×1920 RGB PNG
- the complete top banner-ad region (`y=0..157`, 158 px) is removed before reuse
- `LV.50` is never added as marketing copy or a badge; it only remains inside the real game UI
- every key-art scene contains exactly one hero and no afterimage
- phone headlines use expanded two-line spacing and a larger gap before supporting copy
- growth, equipment, and chronicle screens fade in from the generated background; the combat-damage number is fully hidden before the real information panel becomes crisp
- the rejected wrapped `도마뱀` capture is not used; the selected quest capture keeps the name on one line
- the combined review sheet is `audit/selected-ko-relic-v3-contact-sheet.png`
- exact built-in ImageGen prompts are recorded in `PROMPTS_SELECTED_RELIC_V3.md`

Rebuild with:

`python3 docs/marketing/google-play-ko-20260826/build_selected_relic_campaign.py`

## Selected Korean feature-graphic master

- `source/feature-watch-only-ko-selected-v2-1794x876.png` — approved high-resolution source from the final Option 1 refinement
- `final/01-feature-watch-only-ko-v2-1024x500.png` — Google Play upload master, 1024×500 RGB PNG

Selected design characteristics:

- exactly one cloaked hero; no hero afterimages or additional human silhouettes
- enlarged `NULL PLAYING` title treatment
- enlarged single-line `지켜보기만 하세요` headline
- enlarged, brighter `영웅은 스스로 성장합니다` supporting copy
- no screenshot, banner ad, level number, ranking, badge, or call-to-action
- built with the built-in ImageGen workflow, then exported locally to the exact Google Play dimensions

## Final exports

- `final/01-feature-watch-only-1024x500.png` — previous feature graphic A draft, retained for comparison
- `final/02-feature-auto-chronicle-1024x500.png` — feature graphic B, automatic chronicle positioning with a real Lv.50 UI crop
- `final/03-phone-growth-lv50-1080x2160.png` — Lv.50 stats and skill mastery
- `final/04-phone-equipment-lv50-1080x2160.png` — automatically selected equipment
- `final/05-phone-chronicle-lv50-1080x2160.png` — automatically progressing quest chronicle

All phone screenshots are real emulator captures from a naturally simulated Lv.50 Mage state. Before any screenshot was reused, the complete top banner-ad region (`y=0..157`, 158 px) was removed. No final export contains an ad label or empty ad slot.

The capture in which `도마뱀` wrapped across two lines was rejected and is not used by any final export.

## Built-in ImageGen background prompts

### Gate variant

> Create a premium horizontal dark-fantasy game store feature-graphic BACKGROUND inspired by the referenced AlarmQuest title screen. Exact target composition is ultra-wide 1024:500 (2.048:1). A monumental gothic gate/portal with warm antique-gold light stands in the right third, surrounded by twisted black roots, distant ruined spires, violet-black mist, a subtle eclipse halo and sparse magical motes. Keep the entire left 48% deliberately dark, calm, and low-detail as negative space for later Korean headline and official logo compositing. Cinematic, richly textured, sophisticated mobile RPG key art, matching the violet, charcoal, muted gold palette of the reference. Strong depth, crisp but not noisy, no people. No lettering, no logos, no UI, no frames, no ads, no symbols that resemble text. Output only the background artwork.

### Labyrinth variant

> Create a second premium horizontal dark-fantasy mobile RPG store feature-graphic BACKGROUND, exact target composition 1024:500 (2.048:1), visually consistent with the referenced AlarmQuest title and level-50 quest screens. Show an ancient subterranean memory-tree labyrinth: enormous dark roots form a vaulted tunnel, an old descending stone stair and faint violet glass-like veins lead toward a restrained gold light in the right third. Add subtle chronicle-page motifs only as abstract particles and layered depth, never readable marks. Preserve the left 48% as deep charcoal-violet low-detail negative space for later official logo and Korean copy. Sophisticated, moody, cinematic, premium key art, crisp focal depth, restrained gold/violet palette. No characters, no lettering, no logos, no UI, no frames, no ads, no pseudo-text.

## Rebuild

Run `python3 docs/marketing/google-play-ko-20260826/build_assets.py` from the repository root.
