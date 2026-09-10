# AlarmQuest / NULL PLAYING working context

Read `docs/SESSION_HANDOFF.md` first when continuing this project. It records the current source,
release, QA device, and live-server boundaries; historical design files may describe retired systems.

- The repository root is the current build source. The old
  `output/preintegration/adventure-20260906/project` overlay is an archived v24 reference.
  Do not copy it wholesale onto this checkout or build a newer release from it.
- Preserve unrelated dirty changes. Stage reviewed paths explicitly; never reset/clean the shared
  checkout. Do not run simultaneous Gradle builds against this root's `app/build` directory.
- Keep release credentials, signing keys, database backups, auth receipts and device saves out of Git.
  `output/` is local evidence only. Reusable summaries belong under `docs/releases/`.
- Routine QA uses offline variants. `arenaLab` is the standalone six-class, editable-stat arena QA app;
  it has no production connectivity and no entry limit. The user operates the wireless phone.
- Live database changes require the user's current scope, migration status/dry-run and readback.
  The user asks database mutations to be performed through the PC console. Never run a destructive
  cleanup merely to validate it. The maintenance SQL aborts if another arena user exists.
- Building, device installation, Play upload, Play release and database application are distinct actions;
  report only what was actually completed. Reuse the existing upload signing identity.
