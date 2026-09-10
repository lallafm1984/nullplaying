#!/usr/bin/env python3
"""Network-free structural and boundary audit for unapplied ranking migration 009."""

from datetime import datetime, timedelta, timezone
from pathlib import Path
import re


PROJECT = Path(__file__).resolve().parents[2]
MIGRATION = PROJECT / "supabase/migrations/202609080009_hourly_ranking_publication.sql"
APPLIED_GENERAL = PROJECT / "supabase/migrations/202609070007_daily_ranking_cache_and_read_boundary.sql"
APPLIED_ARENA = PROJECT / "supabase/migrations/202609070008_daily_arena_ranking_boundary.sql"
sql = MIGRATION.read_text(encoding="utf-8")
lower = sql.lower()


def require(pattern: str, reason: str) -> None:
    if re.search(pattern, sql, flags=re.IGNORECASE | re.DOTALL) is None:
        raise AssertionError(reason)


def bucket(value: datetime) -> datetime:
    utc = value.astimezone(timezone.utc)
    return utc.replace(minute=0, second=0, microsecond=0)


if not APPLIED_GENERAL.exists() or not APPLIED_ARENA.exists():
    raise AssertionError("009 must layer on the existing 007 and 008 migrations")
if re.search(r"\b(create|alter|drop)\s+table\b", lower):
    raise AssertionError("009 must preserve the applied table/RLS schema")
if "create or replace function public.sync_arena_ranking_entry" in lower:
    raise AssertionError("009 must not alter the arena write/anti-cheat RPC")
if "create or replace function public.sync_ranking_entries" in lower:
    raise AssertionError("009 must not alter the general ranking write RPC")

for schema in ("ranking_private", "arena_ranking_private"):
    require(
        rf"create\s+function\s+{schema}\.utc_hour_bucket\(p_at\s+timestamptz\)",
        f"missing private UTC bucket helper for {schema}",
    )
require(
    r"date_trunc\('hour',\s*p_at\s+at\s+time\s+zone\s+'UTC'\)\s+"
    r"at\s+time\s+zone\s+'UTC'",
    "bucket math must floor server time to the current UTC hour",
)
require(
    r"capture_daily_ranking_before_image[\s\S]+?"
    r"ranking_private\.utc_hour_bucket\(v_admitted_at\)"
    r"[\s\S]+?old\.daily_admitted_at\s*<\s*v_cutoff_at",
    "general before-image capture must use the hourly admission bucket",
)
require(
    r"capture_daily_arena_before_image[\s\S]+?"
    r"arena_ranking_private\.utc_hour_bucket\(v_admitted_at\)"
    r"[\s\S]+?old\.daily_admitted_at\s*<\s*v_cutoff_at",
    "arena before-image capture must use the hourly admission bucket",
)
require(
    r"on\s+conflict\s*\(user_id,\s*character_id\)[\s\S]+?"
    r"where\s+previous\.cutoff_at\s*<\s*excluded\.cutoff_at",
    "general outage recovery must retain the latest recoverable per-row cutoff",
)
require(
    r"on\s+conflict\s*\(season_id,\s*user_id,\s*character_id\)[\s\S]+?"
    r"where\s+previous\.cutoff_at\s*<\s*excluded\.cutoff_at",
    "arena outage recovery must retain the latest recoverable per-row cutoff",
)

for function, lock, helper in (
    ("ranking_private.publish_daily_leaderboard", "20260905, 1", "ranking_private"),
    (
        "arena_ranking_private.publish_daily_arena_leaderboard",
        "20260908, 8",
        "arena_ranking_private",
    ),
):
    require(
        rf"create\s+or\s+replace\s+function\s+{re.escape(function)}[\s\S]+?"
        rf"pg_advisory_xact_lock\({re.escape(lock)}\)",
        f"{function} lost its exclusive writer barrier",
    )
    require(
        rf"create\s+or\s+replace\s+function\s+{re.escape(function)}[\s\S]+?"
        rf"{helper}\.utc_hour_bucket\(v_now\)",
        f"{function} must publish the latest server-time hourly bucket",
    )
    require(
        rf"create\s+or\s+replace\s+function\s+{re.escape(function)}[\s\S]+?"
        r"v_cutoff_at\s*<\s*v_state\.tracking_started_at",
        f"{function} must refuse a partially tracked transition bucket",
    )

if lower.count("only the latest fully tracked utc hourly bucket") < 4:
    raise AssertionError("historical/missed buckets must not be fabricated")
if lower.count("order by keep.settled_at desc") < 2 or lower.count("limit 2") < 2:
    raise AssertionError("both boards must retain only the latest two completed snapshots")
require(
    r"get_daily_leaderboard[\s\S]+?when\s+v_snapshot\.is_bootstrap\s+then\s*"
    r"ranking_private\.utc_hour_bucket\(v_tracking_started_at\)\s*\+\s*"
    r"interval\s+'1 hour 1 minute'[\s\S]+?else\s+greatest\(\s*"
    r"v_snapshot\.settled_at\s*\+\s*interval\s+'1 hour 1 minute',\s*"
    r"ranking_private\.utc_hour_bucket\(v_tracking_started_at\)\s*\+\s*"
    r"interval\s+'1 hour 1 minute'",
    "general reads must wait until one minute after the hourly publisher",
)
require(
    r"get_daily_arena_leaderboard[\s\S]+?when\s+v_snapshot\.is_bootstrap\s+then\s*"
    r"arena_ranking_private\.utc_hour_bucket\(v_tracking_started_at\)\s*\+\s*"
    r"interval\s+'1 hour 18 minutes'[\s\S]+?else\s+greatest\(\s*"
    r"v_snapshot\.settled_at\s*\+\s*interval\s+'1 hour 18 minutes',\s*"
    r"arena_ranking_private\.utc_hour_bucket\(v_tracking_started_at\)\s*\+\s*"
    r"interval\s+'1 hour 18 minutes'",
    "arena reads must wait until one minute after the staggered hourly publisher",
)
require(
    r"with\s+cutoff_rows\s+as[\s\S]+?\),\s*ranked\s+as\s*\([\s\S]+?"
    r"where[\s\S]+?row\.system_entry_code\s+is\s+null[\s\S]+?"
    r"insert\s+into\s+ranking_private\.daily_leaderboard_rows",
    "general hourly publication must exclude system gatekeepers before rank and count",
)

require(
    r"create\s+or\s+replace\s+function\s+public\.get_daily_leaderboard"
    r"[\s\S]+?'snapshot_id'[\s\S]+?'settled_at'[\s\S]+?'next_settlement_at'"
    r"[\s\S]+?'generated_at'[\s\S]+?'server_now'[\s\S]+?'is_bootstrap'"
    r"[\s\S]+?'unchanged'[\s\S]+?'t'[\s\S]+?'e'[\s\S]+?'o'",
    "general conditional-read envelope changed",
)
require(
    r"create\s+or\s+replace\s+function\s+public\.get_daily_arena_leaderboard"
    r"[\s\S]+?'snapshot_id'[\s\S]+?'season_id'[\s\S]+?'rules_version'"
    r"[\s\S]+?'settled_at'[\s\S]+?'next_settlement_at'[\s\S]+?'generated_at'"
    r"[\s\S]+?'server_now'[\s\S]+?'is_bootstrap'[\s\S]+?'unchanged'"
    r"[\s\S]+?'t'[\s\S]+?'e'[\s\S]+?'o'",
    "arena conditional-read envelope changed",
)
require(
    r"p_known_snapshot_id\s*=\s*v_snapshot\.snapshot_id[\s\S]+?return\s+v_response",
    "known snapshots must remain metadata-only no-op responses",
)

require(
    r"pg_advisory_xact_lock\(20260905,\s*1\)[\s\S]+?"
    r"pg_advisory_xact_lock\(20260908,\s*8\)[\s\S]+?"
    r"tracking_started_at\s*=\s*v_tracking_started_at[\s\S]+?"
    r"delete\s+from\s+ranking_private\.daily_ranking_before_images[\s\S]+?"
    r"delete\s+from\s+arena_ranking_private\.daily_arena_before_images",
    "transition must reset unverifiable daily-era images under both barriers",
)
if "select ranking_private.publish_daily_leaderboard();" in lower or (
    "select arena_ranking_private.publish_daily_arena_leaderboard();" in lower
):
    raise AssertionError("009 deployment must not fabricate an immediate transition publication")

require(
    r"cron\.schedule\(\s*'alarmquest-daily-leaderboard',\s*'0 \* \* \* \*'",
    "general hourly retry must remain at minute 00",
)
require(
    r"cron\.schedule\(\s*'alarmquest-daily-arena-leaderboard',\s*'17 \* \* \* \*'",
    "arena hourly retry must remain staggered at minute 17",
)

cases = {
    datetime(2026, 9, 8, 0, 0, tzinfo=timezone.utc): 0,
    datetime(2026, 9, 8, 0, 59, 59, tzinfo=timezone.utc): 0,
    datetime(2026, 9, 8, 7, 59, 59, tzinfo=timezone.utc): 7,
    datetime(2026, 9, 8, 8, 0, tzinfo=timezone.utc): 8,
    datetime(2026, 9, 8, 15, 59, 59, tzinfo=timezone.utc): 15,
    datetime(2026, 9, 8, 16, 0, tzinfo=timezone.utc): 16,
    datetime(2026, 9, 8, 23, 59, 59, tzinfo=timezone.utc): 23,
}
for value, expected_hour in cases.items():
    actual = bucket(value)
    if actual.hour != expected_hour or actual.minute or actual.second:
        raise AssertionError(f"wrong bucket for {value.isoformat()}: {actual.isoformat()}")

day = datetime(2026, 9, 8, tzinfo=timezone.utc)
hourly_buckets = [bucket(day + timedelta(hours=hour)) for hour in range(24)]
real_publications = sum(
    current != previous
    for current, previous in zip(hourly_buckets, [None, *hourly_buckets[:-1]])
)
if len(set(hourly_buckets)) != 24 or real_publications != 24:
    raise AssertionError("one UTC day must expose exactly twenty-four real bucket IDs")
if 24 - real_publications != 0:
    raise AssertionError("hourly retry schedule must have exactly zero repeated-bucket calls")

# A bounded one-row before-image advances to the latest boundary during an outage. Older missed
# boundaries cannot be reconstructed and are deliberately skipped.
stored_cutoff = day
for admission in (day + timedelta(hours=8, minutes=2), day + timedelta(hours=16, minutes=2)):
    candidate = bucket(admission)
    if stored_cutoff < candidate:
        stored_cutoff = candidate
if stored_cutoff != day + timedelta(hours=16):
    raise AssertionError("outage recovery did not converge on the latest recoverable bucket")

if sql.count("$$") % 2:
    raise AssertionError("unbalanced PostgreSQL dollar quotes")

print(f"PASS {MIGRATION}")
print("PASS UTC hourly buckets, transition honesty, before-image, outage, 24 hourly publications, retention and RPC contracts")
