# Google Ads localized image prompt record — 2026-08-28

The three textless scenes were created with Codex built-in ImageGen. Exact
localized copy, localized brand typography, and real localized emulator UI were
then composited locally so typography and game information remain accurate.
Korean and Japanese use the official raster wordmark; English uses the
sentence-case `Null Playing` treatment for capitalization-policy safety.
The English brand and headline treatment uses New York Semibold for a refined
cinematic serif character instead of the earlier heavy Arial treatment.
The `Null Playing` brand title is deliberately larger than the campaign
headline, with added vertical separation so the two text roles remain distinct.
The English 1.91:1, 1:1, and 4:5 ads reuse the approved Korean hero, golden path,
and gate composition. Their Korean copy is replaced with `Just watch.` and
`Your hero grows on their own.` in New York Semibold and Avenir Next. The UI
screenshots are removed from the 1:1 and 4:5 ads, and the official English
Google Play download badge is placed without alteration at the bottom.
In the 1.91:1 hierarchy, `Null Playing` is a clean two-tone ivory-and-gold New
York wordmark without a decorative rule, while `Just watch.` uses Avenir Next
Demi Bold. The supporting line uses the lighter Avenir Next Medium at a smaller
size. This separates the product name from the campaign copy without a broken
line motif at reduced ad sizes.

## Visual references

- `source/feature-watch-only-ko-selected-v2-1794x876.png`
- `source/phone-relic-selected/01-hourglass-background.png`

## 1.91:1 landscape scene

> Create a premium dark-fantasy mobile RPG advertising key-art BACKGROUND in an exact 1.91:1 landscape composition. Match the referenced NULL PLAYING campaign: near-black charcoal and deep royal violet, antique-gold light, cinematic mist, subtle magical particles, elegant high-end game advertising finish. Place exactly one hooded hero, one monumental hourglass/time relic, and a glowing gothic gate together in the right 48% of the frame. Keep the left 45% deliberately dark, uncluttered, and low-detail as safe negative space for a logo and one short localized headline to be added later. The hero must be a single person with no duplicate, echo, ghost, afterimage, or extra silhouette. No readable text, no logo, no lettering, no UI, no screenshot, no ad banner, no badges, no border, no pseudo-text. Output only the textless background artwork.

Generated source: `source/google-ads-20260828/01-landscape-textless-imagegen.png`

## 1:1 square scene

> Create a premium dark-fantasy mobile RPG advertising BACKGROUND in an exact 1:1 square composition, visually consistent with the referenced NULL PLAYING purple-and-antique-gold Relic of Time campaign. Put one monumental glowing hourglass and exactly one hooded hero in the upper-right quadrant. Preserve the upper-left as dark, low-detail copy-safe negative space. Design the lower 52% as a deep-violet magical portal/insertion zone with restrained gold filigree and mist, suitable for later compositing of a real game screenshot; keep it readable and not visually busy. Exactly one hero only, with no duplicate, reflection, afterimage, ghost, or extra human silhouette. Cinematic, sophisticated, crisp, premium app-ad art. No readable text, no logo, no UI, no screenshot, no ad banner, no badge, no border, no pseudo-text. Output only the textless background artwork.

Generated source: `source/google-ads-20260828/02-square-textless-imagegen.png`

## 4:5 portrait scene

> Create a premium dark-fantasy mobile RPG advertising BACKGROUND in an exact 4:5 portrait composition, matching the referenced NULL PLAYING campaign with near-black violet shadows, antique-gold time magic, cinematic fog, and refined high-end mobile-game key art. Place a giant luminous hourglass/time relic and exactly one hooded hero in the upper-right region. Preserve the upper-left as calm dark copy space. Build the lower 58% as a deep-violet magical portal/insertion area, framed by elegant gold arcs and soft mist, ready for a real gameplay screenshot to be composited later. Exactly one hero; no duplicate, echo, reflection, afterimage, ghost, or second silhouette. No readable text, no logo, no lettering, no UI, no screenshot, no ad banner, no badges, no border, no pseudo-text. Output only the textless background artwork.

Generated source: `source/google-ads-20260828/03-portrait-textless-imagegen.png`

## Localized headline map

| Locale | 1.91:1 | 1:1 | 4:5 |
|---|---|---|---|
| Korean | 아무것도 하지 않는 RPG | 지켜보기만 하는 RPG | 손대지 않아도 성장하는 RPG |
| English | Just watch. | Just watch. | Just watch. |
| Japanese | 何もしないRPG | 見守るだけのRPG | 操作しなくても育つRPG |

## Deterministic rebuild

Run:

`python3 docs/marketing/google-play-ko-20260826/build_google_ads_campaign.py`

The build removes the full 158 px emulator banner-ad strip before any gameplay
crop is used. The English square export uses the approved Korean campaign art
without a UI screenshot, while the other localized square exports retain their
localized equipment screens. The portrait export uses the localized growth/stat screen. To avoid automated
capitalization-policy false positives, the English exports use the sentence-case
brand treatment `Null Playing` instead of the official all-caps raster wordmark.
The standard genre acronym `RPG` remains uppercase inside otherwise
sentence-case headlines.
