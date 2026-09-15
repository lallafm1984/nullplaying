# Current project handoff — 2026-09-15

## Current source and release boundary

The repository root is **0.5.3 / code 28**, including trait rules v3, the Mythic Hall,
and WebP resource optimization. The latest source additionally contains the approved equipment
odds (+4: 0.1%, +5: 0.05%, conditional on an equipment drop) and a correction that rebases the
v2/v3 trait evidence, formation-check and retention clocks together with trusted time.
These changes are included in the locally verified 0.5.3-28 AAB. This build does not publish a new
Play release, install on a device or change the live database.

Current validation and level-by-level equipment evidence are recorded in
`releases/2026-09-15-source-verification/README.md`. Use that report for current source verification;
the dated build, device and server records below describe their respective historical actions.

## Root integration background — 2026-09-10

The repository root is now the current implementation and release build source. Version **0.5.1 (25)**
includes the reviewed v24 MP change plus the mage ice icon mapping from task
`01a08a69-2f05-7473-8f17-3c29074ece89`. See `releases/0.5.1-25/README.md` for the final build result.
The previous `output/preintegration/adventure-20260906/project` is a historical v24 reference.
Do not reapply it wholesale: it would remove the v25 icon mapping/version updates.

The v24 release manifest was integrated file by file. Existing replaced files and retired
correspondence/condition helpers were backed up under `output/main-integration-20260910/`.
Unrelated VFX-lab and AI-evaluation work stays in the working tree and is outside this release commit.

## Latest gameplay and UI decisions

- Arena uses the character level and raw character ability profile, with class-specific derived combat
  values. Arena-level/XP progression was removed; skill points come from character level.
- Skills retain individual rank-5/rank-10 milestones and class-specific effects. No zero-turn cooldown.
  Deterministic replay engine remains `arena-stat-identity-v5`; current catalog/rules are version 13 /
  `arena-skill-tree-v13`. Historical replay skill snapshots keep their original costs.
- Latest MP change: current attack/support skill costs are 60% of the preceding formula, rounded to
  an integer, minimum 1. Across 180 skills × 10 ranks, aggregate costs fell 39.980029% (42,061 → 25,245).
  Max MP, regeneration, cooldowns, damage/effects, stats and matchmaking were not changed by this patch.
- Mage ice icons use the existing atlas's unused blue tiles: attack index 1 (Ice Spear) → tile 20;
  attack index 6 (Frost Storm) → tile 21. Other attack mappings/artwork remain unchanged.
- Local arena fallback opponents use the player's level and a bounded combat-power profile. Actual
  server opponents and recent-record matching remain separate policies; do not force wins/losses.
- Arena holds 5 tickets, up to 10 daily entries, 10-minute recovery. A rewarded refill is offered at
  zero tickets and grants the smaller of 5 or the remaining daily allowance, at most once per day.
  Display the count without `/5`; show the daily reset when the entry cap is reached.
- Rewarded ads use separate arena/offline ad units. Arena preloading begins when tickets first reach 1.
  Ads, trusted time and ranking contracts are included in the release regression set.
- Adventure incidents, relationships and adventure traits are implemented; discovery/action follow the
  automatic event flow. The correspondence system is retired. Do not revive it from old design docs.
- Update popup keeps the existing Korean/English/Japanese content and layout, dismisses only with its
  close action, and remembers dismissal for this update. v25 changes its update identity only.
- Local persistence: Room 16 and game JSON schema 46. Preserve existing game and account state.

## Verification evidence

v24 gameplay checks (unchanged mechanics in v25) are recorded in `releases/0.5.1-24/`:
234 release regression tests; 4,992,000 class/preset/level simulated battles; 0 aborts; class average
win rates 45.58–53.94%. Paired high-level mana analysis is stored separately from win-rate evidence.
This is simulation evidence, not a promise of a real player's win rate.

v25 root tests, release bundle, lint, signing and artifact readback are recorded in
`releases/0.5.1-25/`; complete local logs/artifacts are in `output/release-qa/0.5.1-25/` and
`output/releases/0.5.1-25/`. Initial root build overlap corrupted KSP cache; generated build output
was cleaned and a serial build was used. Source and production-input hashes guard against drift.
Local PGlite database verification applied 13 migrations, passed 17/17 pgTAP contract checks plus
moderation/cache/ownership fixtures, and made no live database writes.

## Delivery boundaries

- Play Console readback on 2026-09-10: **0.5.1 (24)** was published to internal testers at 17:01 KST.
- **0.5.1 (25) was published to internal testers at 17:43 KST on 2026-09-10, release 10.**
  Console explicitly showed “내부 테스터에게 제공됨”. No production rollout was performed.
- Phone SM-S931N has the standalone MP-relief QA app `com.nullplaying.arenalab`,
  **0.5.1-arena-qa-mp40 (23)**. Its MP mechanics match v24/v25. It was overwrite-installed with
  preferences preserved; the user plays the phone. The production Play app was not overwritten.
- The arena QA app supports six classes, levels 10–100, actual rolled/growth ability generation,
  manual ability editing and unlimited entries; no Internet permission or server credentials.
- Do not operate the user's emulator or phone while they are testing. No new device installation
  or Play release is implied by a request to build the AAB.

## Server and maintenance

Reviewed shared-player, arena-ranking and moderation migrations 004–010 were applied to the live
server in earlier work. This Git/build handoff does not reapply them or change the live database.
Fresh status/dry-run/readback is required before future migrations; do not infer current remote
state from historical documents. `supabase/SHARED_PLAYER_DEPLOYMENT.md` provides the contract.

General ranking/session tables remain distinct from `shared_player_private.snapshots` and the
private `arena_ranking_private` tables. Shared snapshots omit equipment and skill arrays; clients
build the battle projection locally. Submitted combat power/results are not a fully authoritative
server simulation. Auth, validation, rate/cap limits, trusted time and moderation reduce abuse;
they do not prove client-submitted battle outcomes.

Both ranking publications were configured hourly by migration 009; Firebase Remote Config controls
the app refresh interval with a minimum of one hour. Both screens use consistent interval copy;
own ranks can be calculated locally against the received edition as requested. Do not mistake a
changed local rank for publication of a new server edition.

Developer arena data was cleaned once on 2026-09-10 at 16:25 KST after checking for other users;
Auth, ordinary ranking, shared player and session data were retained. The user subsequently reported
re-registration. A normal server-connected app can upload its saved arena record again.
`supabase/maintenance/delete_developer_arena.sql` is the reviewed reusable cleanup template:
set the verified Auth UUID first; it refuses the placeholder or any other arena participants,
removes developer arena rows and rebuilds an empty snapshot atomically. The follow-up deletion
SQL was locally tested and supplied to the user, **not executed against live again**.
Never commit actual developer identifiers, tokens or database backups.

## Follow-up

1. Internal testers can update to v25. Use the v25 report and actual Play version when diagnosing tests; do not reuse v24.
2. The user-authorized v25 internal-test publication is complete. Production rollout and device installation remain separate requests.
3. If another session changes source after this build, increment/rebuild as requested and regenerate
   source hashes; do not label an older bundle as containing later changes.
4. Main-branch handoff should preserve the separate dirty VFX/AI work. Check Git status before edits.

## Local trait acquisition update — 2026-09-14

The root now implements adventure trait acquisition rules v2. Existing owned traits remain intact;
mandatory combat grade/XP evidence no longer forms universal traits. Acquisition compares the full
eligible pool with bounded evidence, active-time checks and deterministic weighted selection.
See `design/ADVENTURE_TRAIT_ACQUISITION_V2_20260914.md` for all 40 conditions and offline validation.
This is a source change only: no release bundle, device install, Play publication or live DB change.
The older release/device readbacks above are historical and do not describe deployment of this fix.

## Local retention and capacity update — 2026-09-14

Adventure trait rules v3 supersede v2 ownership limits: retain each acquired trait for at least
72 active-adventure hours before loss or opposite replacement; cap new ownership at 7 traits.
Legacy overflow is not forcibly deleted; further formation is blocked until natural losses free capacity.
The arena guide no longer mentions local challenger difficulty in Korean, English or Japanese.
See `design/ADVENTURE_TRAIT_RETENTION_CAP_V3_20260914.md`. This source change is not deployed.

## Mythic Hall — 2026-09-14

Implemented Items → equipped-gear right-aligned **신화의 전당** entry with title only, the shared
ranking transition/top bar, and no top/my-rank jumps. Automatic +5 acquisition history covers equipped
and bag drops, survives gear sale/replacement, and retries from persisted character records. The server
publishes the newest 100 eligible discoveries by hourly admission cutoff with no extra pages.
Migration `202609140001_mythic_discoveries` was applied through the PC SQL Editor and read back;
CLI status confirms the new local/remote version. Historical missing migration receipts were untouched.
KO/EN/JA entry and populated popup QA, acquisition/isolation tests, local SQL contracts and offline APK
build passed. No production test records or Play/device release. Details: `design/MYTHIC_HALL_20260914.md`.

## 0.5.2 (27) production AAB build — 2026-09-14

Current root version is 0.5.2 / code 27. Signed AAB generated and independently verified at
`output/releases/0.5.2-27/NULL-PLAYING-0.5.2-27.aab`. Release tests: 317 passed; lint, signature,
bundle validation, production configuration, QA exclusion and 16-KiB alignment passed.
No Play upload/rollout or device installation. See `releases/0.5.2-27/README.md`.

## 0.5.2 (27) mandatory update — 2026-09-14

User confirmed production deployment. Android update policy was advanced through the PC Supabase
SQL Editor using `202609140002_force_android_version_27_update.sql`; independent readback confirmed
latest 27 / 0.5.2, minimum supported 27, force_update=true and enabled=true, with one migration receipt.
Server updated_at: 2026-09-13 23:52:22.245896+00 (2026-09-14 08:52:22 KST).
Existing update copy and Play URL were preserved; historical missing migration receipts were untouched.
Clients at code 26 or below require updating at their next policy check; code 27+ is excluded.
Play deployment is user-reported; no physical-device update-popup verification was performed.

## 0.5.3 (28) production AAB build — 2026-09-15

Current root version is 0.5.3 / code 28. Signed AAB generated and independently verified at
`output/releases/0.5.3-28/NULL-PLAYING-0.5.3-28.aab`. Release tests: 452 passed; lint, signature,
bundle validation, production configuration, QA exclusion, source freshness and 16-KiB alignment passed.
No Git commit, Play upload/rollout, device installation or server mutation. See
`releases/0.5.3-28/README.md`.

## 0.5.3 (28) mandatory update — 2026-09-15

Production deployment was confirmed by the user. Android update policy was advanced through the PC
Supabase SQL Editor using `202609150001_force_android_version_28_update.sql`. SQL Editor and public
PostgREST readbacks confirmed latest 28 / 0.5.3, minimum supported 28, `force_update=true` and
`enabled=true`; CLI status confirms the exact migration receipt. Existing localized copy and Play URL
were preserved. Codes 27 and below are now blocked at their next policy check. Unrelated historical
local-only migrations `202609040001` and `202609050001` were not applied.
