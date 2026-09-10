# 0.5.1 (25) — ice icons and reviewed MP relief

Built from the repository root on 2026-09-10. Includes the v24 gameplay implementation and the
mage Ice Spear/Frost Storm blue-icon mapping from task `01a08a69-2f05-7473-8f17-3c29074ece89`.
Existing update-popup content and layout are unchanged; its update identity is v25.

- Release regression: 234 tests / 36 suites, 0 failures, errors or skips.
- R8 release APK/AAB and lint passed; lint: 0 errors, 89 warnings, 7 informational findings.
- Bundletool 1.18.1 validation, upload signature, package/version, production client configuration,
  embedded ads, disabled QA flags, multilingual notice and 16-KiB native alignment passed.
- Source freshness: all 750 recorded input hashes match the built source.
- Published replay engine remains V5; current skill catalog is 13. Existing MP-relief gameplay QA is
  recorded under `../0.5.1-24/` and was not rerun as a new balance change for the icon update.
- KSP cache corruption from overlapping builds was resolved with a clean serial build; production
  input files and signing credentials were hash-checked unchanged.

Local AAB: `output/releases/0.5.1-25/NULL-PLAYING-0.5.1-25.aab` (94,460,150 bytes).
SHA-256: `0f27606df40d68e8ddde756cc06a395ef11599e582cefba2f8aa1ff8f02c16da`.

Play internal-test upload and publication completed at **2026-09-10 17:43 KST**. Console confirms
**25 (0.5.1), available to internal testers**, release 10. Readback is recorded in
`play-internal-release.json`. No blocking errors or support-device reduction; the single advisory is
missing native debug symbols. Production rollout and a new device installation were not performed.

Evidence files contain no private signing inputs, live database backup or device save data.
