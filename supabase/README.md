# Supabase setup

1. Create a Supabase project and enable **Authentication > Providers > Anonymous Sign-Ins**.
2. Run the SQL files in `migrations/` in filename order in the SQL editor.
3. Add the following non-service credentials to the untracked root `local.properties`:

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
```

Never put a `service_role` or secret key in the Android app. The publishable key is expected to
be extractable from an APK; authorization is enforced by Auth plus the RLS policies in the migration.

The app creates one persistent anonymous Supabase user per install and upserts one ranking row per
eligible character slot. Ranking uploads are change-only, are limited to one successful upload per
five minutes, and are never sent merely because the activity moved to the background. The compact
top-1,000 leaderboard response is cached on disk for one hour; local combat-power changes are
re-ranked immediately against that snapshot without another download.

On process start, foreground return, and immediately before an upload, the app validates the stored
anonymous session against Auth. If the user or session was deleted, the app discards the previous
identity's pending remote logs, ranking queue, and ranking cache, creates a new anonymous user, and
continues without deleting local game progress. Continued play can therefore create a new data
identifier and send newly generated usage data after an earlier deletion request was completed.

A move to the background is first recorded locally. Returning in less than five minutes continues
the same session and performs no session-log write. A background stay of at least five minutes ends
the session at the time it entered the background. Completed events are kept in a bounded durable
queue and retried on a later foreground, because Android does not guarantee an app-killed callback.

After authentication, `user_profiles` is upserted with the device-locale country code, language,
timezone, app/Android version, manufacturer, model, and last-seen time. `country_source` explicitly
records `device_locale`; this is a settings-based estimate and not an IP-derived physical location.

Ranking timestamps are assigned by Postgres. Authenticated clients cannot write `ranking_entries`
directly; they submit a complete, validated roster through `sync_ranking_entries`, whose privileged
implementation is outside the exposed API schema. An empty roster removes all of that user's ranking
rows, and a partial roster removes characters that are no longer present before compacted slots are
upserted atomically.

Combat power is still calculated and submitted by the offline client, so this remains a social
leaderboard rather than a fully server-authoritative anti-cheat leaderboard. The app places the
current local character power into the downloaded server ranking and presents that recalculated
order with the normal ranking UI. The sync RPC rejects malformed rows, levels above 10,000, and
combat power above the exact level-specific displayed maximum derived from the engine's 135% stat
soft-cap limit and strongest possible mythic equipment. A value equal to the maximum remains valid;
only a value above it is treated as tampered.
The leaderboard query also excludes implausible rows left by older clients. This blocks grossly
impossible submissions, but plausible forged values still require
server-authoritative progression or signed, server-verifiable gameplay events to detect.

Migration `202608240001` adds the compact `get_leaderboard_v2` RPC and a daily lifecycle rollup.
`pg_cron` runs the rollup once per day at 03:17 UTC, keeps detailed `app_session_logs` for 30 days,
and keeps `app_session_daily` aggregates for 90 days. The daily table is backend-only: anonymous
and authenticated app clients have no table privileges.

## Direct CLI workflow

The repository pins Supabase CLI in `package.json`. Install it once with `npm install`. For an
interactive setup, log in through the browser and enter the DB password only when link prompts.
For non-interactive use, create the gitignored `.env.supabase.local` from
`supabase/remote.env.example`.

```sh
npm run supabase:login
npm run supabase:link
npm run supabase:adopt-existing
npm run supabase:dry-run
npm run supabase:push
npm run supabase:status
```

`adopt-existing` is a one-time command for this project: migration `202608230001` was applied in the
SQL Editor before CLI migration history was introduced. Always inspect `dry-run` before `push`.
The CLI Personal Access Token and database password are administrative secrets; unlike the Android
publishable key, neither belongs in `local.properties` or the APK.

## Android update policy

Migration `202608230003` creates `app_updates` without inserting a row, so update prompts remain off.
When a release is live, insert or upsert the single `platform = 'android'` row. The app shows a
dismissible prompt when `latest_version_code` is newer. It becomes blocking when `force_update` is
true or the installed version is below `minimum_supported_version_code`.

## Localized title-screen announcements

Migration `202608250001` creates `app_announcements`. The Android client can only read an enabled
announcement during its active time window; publishing and editing remain administrator-only. After
the startup version check succeeds, the app requests the highest-priority row matching its in-app
language (`ko`, `en`, or `ja`) and installed `versionCode`. A required update blocks this request.
The announcement is shown only on the title screen, and its body grows with the text up to a safe
maximum height before becoming scrollable.

`display_type` controls how often each logical announcement is shown:

- `once_after_install`: once for that `announcement_key` on the current installation
- `every_launch`: once whenever a new app process is launched
- `once_per_day`: once per local calendar day for that `announcement_key`

Translations that share an `announcement_key` also share the local display history. Use a new key
when a new one-time announcement must be shown to users who already saw an older one.

Use the same `announcement_key` for each translation. This example publishes one announcement in
all currently supported languages:

```sql
insert into public.app_announcements (
  announcement_key,
  language_code,
  title,
  message,
  minimum_version_code,
  maximum_version_code,
  display_type,
  priority,
  starts_at,
  ends_at,
  enabled
)
values
  ('service_notice_001', 'ko', '공지', '한국어 공지 내용', 1, null, 'once_per_day', 100, now(), null, true),
  ('service_notice_001', 'en', 'Notice', 'English announcement text', 1, null, 'once_per_day', 100, now(), null, true),
  ('service_notice_001', 'ja', 'お知らせ', '日本語のお知らせ本文', 1, null, 'once_per_day', 100, now(), null, true)
on conflict (announcement_key, language_code) do update set
  title = excluded.title,
  message = excluded.message,
  minimum_version_code = excluded.minimum_version_code,
  maximum_version_code = excluded.maximum_version_code,
  display_type = excluded.display_type,
  priority = excluded.priority,
  starts_at = excluded.starts_at,
  ends_at = excluded.ends_at,
  enabled = excluded.enabled;
```

Disable every translation of one announcement with:

```sql
update public.app_announcements
set enabled = false
where announcement_key = 'service_notice_001';
```
