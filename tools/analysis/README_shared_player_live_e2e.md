# Player-network live E2E

`run_shared_player_live_e2e.py` is the production-only companion to the preview harness for
migrations 006-008. It targets only the reviewed AlarmQuest project and remains inert unless
`ALARMQUEST_LIVE_QA_ACK` exactly matches `rlrmaynzdwulbuvymxfa`.

The harness creates two confirmed, non-anonymous email/password QA users through the Auth Admin
API. It never uses anonymous signup. This keeps `admin_companion_capture_signup` out of the flow;
both `admin_subscribers` and `admin_events` must remain unchanged. Supabase documents that Admin
`createUser` accepts `email_confirm: true` without sending a confirmation email:
<https://supabase.com/docs/reference/javascript/auth-admin-createuser>.

Every run has one exact run tag and these fixed public identities:

- level: `10000`
- requester character: `a1100000-0000-4000-8000-000000000001`, slot `1`
- opponent character: `b2200000-0000-4000-8000-000000000002`, slot `1`
- names: `AQ-A-{run_tag}` and `AQ-B-{run_tag}`
- email addresses and Auth metadata containing that same run tag

The public client can call only five fixed RPC paths:

- `sync_player_network_profile(p_characters)` publishes each complete 006 profile once. The exact
  row contains `slot_id` plus the reviewed shared snapshot fields; equipment and skill payloads are
  absent.
- `get_daily_public_player_roster(p_character_id,p_rules_version)` verifies self-exclusion, the
  level band, public-only fields, and same-day roster freezing.
- `get_daily_leaderboard(p_known_snapshot_id)` verifies the 007 immutable general ranking, then
  repeats with its snapshot ID and requires a metadata-only `unchanged=true` response.
- `sync_arena_ranking_entry(...)` sends only the 008 aggregate score and W/L/D counters for an
  owned eligible profile. It sends no battle, opponent, result, ticket, history, reward, or client
  timestamp.
- `get_daily_arena_leaderboard(p_known_snapshot_id)` verifies the bounded compact 008 snapshot and
  its metadata-only conditional response.

The RPC transport rejects every other path. Profile/roster/arena-write responses are capped at
32 KiB; daily ranking reads are capped at 512 KiB. Before creating users, owner preflight requires
all five exact RPC signatures, authenticated-only execute grants, private-table read denial, the
four 007 tables, the eight 008 tables, and both reviewed cron jobs (`0 * * * *` general and
`17 * * * *` arena).

Daily snapshots are immutable and intentionally have no Auth cascade. The harness therefore uses
database `statement_timestamp()` as its clock and refuses to begin within 30 minutes on either
side of 00:00 UTC. Preflight also proves the fixed IDs, names, and run tag are absent from general
and arena before-images, daily rows, and snapshot JSON. Pre-cleanup repeats those checks. Use the
all-in-one mode unless emulator work can be finalized before the next blocked UTC window; never
retain a staged handoff across 00:00 UTC.

Database preflight, owner audit, deletion, and remnant audit use only this reviewed linked
Supabase CLI context:

- binary: `/Users/lim/Desktop/프로젝트/AlarmQuest/node_modules/.bin/supabase`
- working directory: `/tmp/aq-shared-player-dryrun-20260907`
- linked project ref: `rlrmaynzdwulbuvymxfa`

The wrapper creates an owner-only mode-`0600` temporary SQL file and runs only
`db query --linked --file` with JSON output. It suppresses raw CLI output and removes the SQL file.
Values are rendered only after UUID, email, run-tag, timestamp, role, name, and integer validation.
Read-only queries reject mutations. Cleanup accepts exactly one mutation: a parameterized delete
of the captured `auth.users` rows after a fresh ownership audit.

That Auth deletion cascades through the mutable shared profile/roster guards, public
`ranking_entries`, arena `season_standings`, server-managed `season_character_bindings`, and arena
call guards. The final audit requires zero
Auth, shared-player, general-ranking, arena-ranking, administrator-telemetry, embedded roster-name,
before-image, daily-row, and immutable-snapshot remnants. Cleanup stops and retains the protected
journal if identity ownership or any immutable-daily invariant differs from the reviewed contract.

The production publishable key and service-role key are read only from process environment. They
are never placed in the cleanup journal, SQL text, command arguments, stdout, or stderr. The
service-role key is used only for the fixed Auth Admin create-user endpoint; the five app RPCs use
the two users' password-authenticated JWTs.

Before any online run, execute the network-free checks:

```bash
PYTHONPYCACHEPREFIX=/tmp/aq-pycache python3 -m py_compile \
  tools/analysis/run_shared_player_live_e2e.py \
  tools/analysis/test_run_shared_player_live_e2e.py
PYTHONPYCACHEPREFIX=/tmp/aq-pycache \
  python3 tools/analysis/run_shared_player_live_e2e.py --self-test
PYTHONPYCACHEPREFIX=/tmp/aq-pycache \
  python3 tools/analysis/test_run_shared_player_live_e2e.py
```

An online run additionally requires migrations 006, 007, and 008 to have passed their separate
deployment review and these three values to be set without embedding secrets in shell history:

```text
ALARMQUEST_LIVE_QA_ACK
ALARMQUEST_LIVE_QA_PUBLISHABLE_KEY
ALARMQUEST_LIVE_QA_SERVICE_ROLE_KEY
```

The normal command provisions both users, exercises the five RPCs, audits the exact rows, deletes
the two captured Auth IDs, and proves zero remnants in one process:

```bash
python3 tools/analysis/run_shared_player_live_e2e.py
```

For a short emulator QA run, retain the two users between two explicit stages:

```bash
python3 tools/analysis/run_shared_player_live_e2e.py --prepare-emulator
# Copy /tmp/arena_live_qa_handoff.json into the QA app's private files directory without printing it.
# Run the emulator match and capture evidence before the next UTC cutoff safety window.
python3 tools/analysis/run_shared_player_live_e2e.py --finalize-cleanup
```

The prepare stage writes two owner-only files:

- cleanup journal: `/tmp/alarmquest-live-shared-player-e2e-rlrmaynzdwulbuvymxfa.json`
- requester credential handoff: `/tmp/arena_live_qa_handoff.json`

The handoff contains only the requester's email/password and exact reviewed public profile,
including the required numeric `slot_id: 1`. It has no service-role key, publishable key, Auth user
ID, or Management credential. Finalize rechecks the two-user ownership and all mutable/immutable
row counts before deleting the users and both files.

If preparation is interrupted, do not start another run. The journal blocks it. Use the recovery
path, which resolves only the two run-tagged email identities, applies the same ownership guard,
deletes captured users, proves zero remnants, and removes both files:

```bash
python3 tools/analysis/run_shared_player_live_e2e.py --recover-cleanup
```

The self-test and unit-test commands perform no live network or database operation.
