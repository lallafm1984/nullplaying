#!/usr/bin/env python3
"""Static fail-closed audit for the forward-only public ranking moderation migration."""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "supabase/migrations/202609080010_public_ranking_moderation.sql"
HOURLY_PATH = ROOT / "supabase/migrations/202609080009_hourly_ranking_publication.sql"
sql = PATH.read_text(encoding="utf-8")
lower = sql.lower()
hourly_lower = HOURLY_PATH.read_text(encoding="utf-8").lower()


def require(pattern: str, message: str) -> None:
    if re.search(pattern, lower, re.S) is None:
        raise AssertionError(message)


require(r"create\s+table\s+ranking_private\.public_ranking_moderation\s*\(",
        "missing private moderation table")
require(r"replacement_display_name\s+text", "missing administrator replacement nickname column")
require(r"hidden_from_public_rankings\s+boolean\s+not\s+null", "missing cheat hide flag")
require(r"alter\s+table\s+ranking_private\.public_ranking_moderation\s+enable\s+row\s+level\s+security",
        "moderation table must enable RLS")
require(r"revoke\s+all\s+on\s+table\s+ranking_private\.public_ranking_moderation[\s\S]+?service_role",
        "moderation table must have no direct role grants")
require(r"create\s+function\s+public\.admin_set_ranking_moderation", "missing admin mutation RPC")
require(r"grant\s+execute\s+on\s+function\s+public\.admin_set_ranking_moderation[\s\S]+?to\s+service_role",
        "service role must own the only mutation grant")
require(r"create\s+function\s+ranking_private\.public_ranking_cache_token", "missing cache token helper")
require(r"octet_length\(p_base_snapshot_id\s*\|\|\s*':m'", "cache token must enforce 128-byte boundary")

for function_name, cap, next_check in (
    ("get_daily_leaderboard", 1000, "1 hour 1 minute"),
    ("get_daily_arena_leaderboard", 997, "1 hour 18 minutes"),
):
    start = lower.index(f"create or replace function public.{function_name}")
    end = lower.index("$$;", lower.index("as $$", start)) + 3
    body = lower[start:end]
    if "public_ranking_moderation_state" not in body or "public_ranking_cache_token" not in body:
        raise AssertionError(f"{function_name} does not invalidate by moderation revision")
    if "if not v_has_active_moderation then" not in body or "'e', v_snapshot.top_entries" not in body:
        raise AssertionError(f"{function_name} lost the zero-active-policy precomputed fast path")
    if "where not annotated.is_hidden" not in body:
        raise AssertionError(f"{function_name} does not filter hidden rows")
    if "rank() over" not in body or "row_number() over" not in body:
        raise AssertionError(f"{function_name} does not recalculate public rank and index")
    if f"limit {cap}" not in body:
        raise AssertionError(f"{function_name} lost its {cap}-row public cap")
    if "'n', reranked.public_display_name" not in body:
        raise AssertionError(f"{function_name} does not publish replacement nicknames")
    if "'n', adjusted.display_name" not in body:
        raise AssertionError(f"{function_name} does not preserve owner nickname")
    if "case when owned.is_hidden then owned.rank_number" not in body:
        raise AssertionError(f"{function_name} does not preserve a hidden owner's raw rank")
    if body.count(f"interval '{next_check}'") != 3:
        raise AssertionError(f"{function_name} overwrote the hourly post-publisher cache boundary")

general_publish_start = hourly_lower.index(
    "create or replace function ranking_private.publish_daily_leaderboard"
)
general_publish_end = hourly_lower.index(
    "create or replace function arena_ranking_private.publish_daily_arena_leaderboard"
)
general_publish = hourly_lower[general_publish_start:general_publish_end]
if "row.system_entry_code is null" not in general_publish:
    raise AssertionError("009 does not exclude adventurer gatekeepers before hourly ranking")
if "create or replace function ranking_private.publish_daily_leaderboard" in lower:
    raise AssertionError("010 must not overwrite the gatekeeper-filtered hourly publisher")

legacy_start = lower.index("create or replace function public.get_leaderboard(")
legacy_end = lower.index("$$;", lower.index("as $$", legacy_start)) + 3
legacy = lower[legacy_start:legacy_end]
if "where not annotated.is_hidden" not in legacy or "rank() over" not in legacy:
    raise AssertionError("legacy leaderboard does not independently apply moderated public ranks")
if "row.user_id = v_user_id" not in legacy or "row.character_id = p_character_id" not in legacy:
    raise AssertionError("legacy leaderboard reserves a character without proving account ownership")
if "ranking-public-v1:" not in legacy:
    raise AssertionError("legacy reader stopped pseudonymizing other account identifiers")
if "limit v_limit" not in legacy:
    raise AssertionError("legacy reader lost the absolute response cap")

if "update ranking_private.daily_leaderboard_rows" in lower or \
        "update arena_ranking_private.daily_arena_leaderboard_rows" in lower:
    raise AssertionError("migration must never rewrite immutable raw snapshot rows")

print("PASS private moderation table, audit fields, RLS and service-role-only admin RPC")
print("PASS general/arena hide, post-filter window rank, replacement name and owner-original contract")
print("PASS moderation revision cache token, caps, legacy reader and immutable source snapshots")
