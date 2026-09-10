#!/usr/bin/env python3
"""Network-free structural audit for the immutable daily ranking boundary."""

from pathlib import Path
import re


PROJECT = Path(__file__).resolve().parents[2]
MIGRATION = PROJECT / "supabase/migrations/202609070007_daily_ranking_cache_and_read_boundary.sql"
sql = MIGRATION.read_text(encoding="utf-8")


def require(pattern: str, reason: str) -> None:
    if re.search(pattern, sql, flags=re.IGNORECASE | re.DOTALL) is None:
        raise AssertionError(reason)


for table in (
    "daily_leaderboard_snapshots",
    "daily_leaderboard_state",
    "daily_leaderboard_rows",
    "daily_ranking_before_images",
):
    require(rf"create\s+table\s+ranking_private\.{table}", f"missing private {table}")
    require(rf"alter\s+table\s+ranking_private\.{table}\s+enable\s+row\s+level\s+security",
            f"RLS must be enabled on {table}")

require(r"revoke\s+all\s+on\s+table[\s\S]+?from\s+public,\s*anon,\s*authenticated,\s*service_role",
        "daily ranking storage must not be directly exposed")
require(r"pg_advisory_xact_lock_shared\(20260905,\s*1\)",
        "every ranking mutation needs the shared cutoff barrier")
require(r"pg_advisory_xact_lock\(20260905,\s*1\)",
        "publication needs the exclusive cutoff barrier")
require(r"statement_timestamp\(\)[\s\S]+?daily_admitted_at",
        "ranking admission must use server statement time")
require(r"capture_daily_ranking_before_image[\s\S]+?tg_op\s*<>\s*'INSERT'[\s\S]+?old\.daily_admitted_at",
        "the first post-cutoff mutation needs a bounded before-image")
require(r"where\s+r\.snapshot_id\s*=\s*v_snapshot_id\s+and\s+r\.list_index\s*<\s*1000",
        "the stored public top list must be capped by deterministic list index")
require(r"snapshot_id\s+not\s+in\s*\([\s\S]+?order\s+by\s+keep\.settled_at\s+desc\s+limit\s+2",
        "only two completed daily snapshots may be retained")

require(r"create\s+function\s+public\.get_daily_leaderboard\(p_known_snapshot_id\s+text",
        "the conditional daily read RPC is required")
require(r"p_known_snapshot_id\s*=\s*v_snapshot\.snapshot_id[\s\S]+?return\s+v_response",
        "an unchanged snapshot response must omit the large bodies")
require(r"create\s+or\s+replace\s+function\s+public\.get_leaderboard\([\s\S]+?v_limit\s+integer\s*:=\s*least",
        "the row compatibility RPC needs a hard caller-independent cap")
require(r"r\.list_index\s*<\s*v_limit\s*-\s*case[\s\S]+?limit\s+v_limit",
        "the own row must consume a slot inside the absolute row cap")
require(r"ranking-public-v1:", "other accounts' raw Auth UUIDs must be pseudonymized")

read_definitions_at = min(
    sql.lower().find("create function public.get_daily_leaderboard"),
    sql.lower().find("create or replace function public.get_leaderboard_v2"),
    sql.lower().find("create or replace function public.get_leaderboard("),
)
direct_revoke_at = sql.lower().find("revoke select on table public.ranking_entries")
if not (0 <= read_definitions_at < direct_revoke_at):
    raise AssertionError("compatibility RPCs must be installed before direct ranking SELECT is revoked")

require(r"cron\.schedule\(\s*'alarmquest-daily-leaderboard',\s*'0 \* \* \* \*'",
        "daily publication must use the hourly idempotent retry schedule")
require(r"revoke\s+select\s+on\s+table\s+public\.ranking_entries\s+from\s+public,\s*anon,\s*authenticated",
        "live ranking rows must be RPC-only")

lower = sql.lower()
for forbidden in (
    "client_timestamp",
    "p_result",
    "score_delta",
    "ticket_count",
    "reward_payload",
    "battle_private.",
):
    if forbidden in lower:
        raise AssertionError(f"local arena authority crossed into daily ranking SQL: {forbidden}")

if sql.count("$$") % 2:
    raise AssertionError("unbalanced PostgreSQL dollar quotes")

print(f"PASS {MIGRATION}")
print("PASS UTC cutoff/private-cache/absolute-1000/conditional-read/hourly-retry contracts")
