# 0.5.2 (27) — production AAB build

Built from the current repository root on 2026-09-14. Includes trait acquisition diversity,
72 active-adventure-hour minimum retention and 7-trait acquisition cap, arena guide copy cleanup,
and the localized Mythic Hall UI with automatic +5 discovery uploads and hourly latest-100 viewing.
Existing unrelated worktree changes were preserved. No Git commit, Play upload, production rollout,
device installation or additional server mutation was performed by this build request.

- Release regression: 317 tests / 45 suites; 0 failures, errors or skips.
- Release APK/AAB with R8 and resource shrinking: PASS.
- Lint: {'Warning': 93, 'Hint': 7} (no errors/fatal findings).
- Package `com.nullplaying`, version name `0.5.2`, code `27`, target SDK 36, non-debuggable.
- Bundletool validation, existing upload certificate, signature and 16-KiB native alignment: PASS.
- Production service flags/configuration enabled; QA flags and QA activities excluded.
- Mythic discovery RPC paths and persisted discovery/trait-retention fields verified in bundled DEX.
- All 758 recorded source hashes still match the built source.
- Existing 0.5.1 in-app update-notice content remains unchanged; only requested application version
  metadata was bumped. Mythic Hall server deployment was already verified in the preceding task.

AAB: `output/releases/0.5.2-27/NULL-PLAYING-0.5.2-27.aab`
Size: 58,529,281 bytes (58.53 MB).
SHA-256: `8dbbae2f52ebbb3aae2a89a303cfafb89d10c3f386a2dcae0290232b2d4433a9`.
Mapping: `output/releases/0.5.2-27/mapping.txt`.
Local detailed evidence: `output/release-qa/0.5.2-27/`.

## Mandatory update registration — 2026-09-14

After the user confirmed production deployment, migration `202609140002_force_android_version_27_update`
was applied through the PC Supabase SQL Editor. Separate server readback confirmed latest version
0.5.2 (27), minimum supported code 27, force_update=true, enabled=true, and one migration receipt.
Server updated_at: 2026-09-13 23:52:22.245896+00. Existing copy and Play destination were preserved.
Codes 26 and below require updating on the next policy check; code 27 and above do not.
Historical pending migrations were not applied. No physical-device popup test was performed.
