# 0.5.3 (28) — production AAB build

Built from the current repository root on 2026-09-15. This bundle includes the approved
equipment-drop odds (+4: 0.1%, +5: 0.05%, conditional on an equipment drop), the trusted-time
rebase correction for adventure trait clocks, and the previously integrated trait/Mythic Hall work.
Existing unrelated worktree changes were preserved. No Git commit, Play upload, production rollout,
device installation or server mutation was performed by this build request.

- Release regression: 452 tests / 50 suites; 0 failures, errors or skips.
- Release APK/AAB with R8 and resource shrinking: PASS.
- Lint: 93 warnings and 7 hints; no errors or fatal findings.
- Package `com.nullplaying`, version name `0.5.3`, code `28`, target SDK 36, non-debuggable.
- Bundletool validation, existing upload certificate, signature and 16-KiB native alignment: PASS.
- Production service flags/configuration enabled; QA flags and QA activities excluded.
- Mythic discovery, trait retention, arena identity and localized update-notice markers verified in bundled DEX.
- All 759 recorded source hashes still match the built source.
- Existing 0.5.1 in-app update-notice content remains unchanged; only the requested application version
  metadata was advanced.

AAB: `output/releases/0.5.3-28/NULL-PLAYING-0.5.3-28.aab`
Size: 58,530,913 bytes (58.53 MB).
SHA-256: `d1db72b6687b4100ef597c0293582e71447b8d1c0ea7a4cfffadcf699af324c7`.
Mapping: `output/releases/0.5.3-28/mapping.txt`.
Local detailed evidence: `output/release-qa/0.5.3-28/`.
Store-facing localized notes: `play-release-notes.txt`.

## Mandatory update registration — 2026-09-15

After production deployment was confirmed, migration
`202609150001_force_android_version_28_update.sql` was applied through the PC Supabase SQL Editor.
Separate SQL Editor and public PostgREST readbacks confirmed latest version 0.5.3 (28), minimum
supported code 28, `force_update=true` and `enabled=true`. The exact migration receipt was added to
the remote ledger. Existing localized copy and Play destination were preserved. Codes 27 and below
must update on the next policy check; code 28 and above are excluded. The two unrelated historical
local-only migrations were not applied.
