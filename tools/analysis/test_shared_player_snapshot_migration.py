#!/usr/bin/env python3
"""Offline structural contract checks for the staged shared-player migration."""

from pathlib import Path
import re

PROJECT = Path(__file__).resolve().parents[2]
MIGRATION = PROJECT / "supabase/migrations/202609070004_shared_player_snapshots.sql"
sql = MIGRATION.read_text(encoding="utf-8")

def require(pattern: str, reason: str) -> None:
    if re.search(pattern, sql, flags=re.IGNORECASE | re.DOTALL) is None:
        raise AssertionError(reason)

require(r"auth\.uid\(\)", "RPCs must bind writes and reads to the authenticated user")
require(r"enable\s+row\s+level\s+security", "private tables must have RLS enabled")
require(r"revoke\s+all\s+on\s+table[\s\S]+from\s+public,\s*anon,\s*authenticated,\s*service_role",
        "clients must not access private tables directly")
require(r"security\s+definer[\s\S]+set\s+search_path\s*=\s*''", "RPCs need a pinned search path")
require(r"other\.user_id\s*<>\s*v_user_id", "all caller-owned characters must be excluded")
require(r"v_requester\.level\s*\+\s*\(\(\(v_level_rotation\s*\+\s*step\)\s*%\s*3\)\s*-\s*1\)",
        "three +/-1 levels must rotate")
require(r"other\.level\s*=\s*levels\.target_level", "each seek must stay inside one indexed level")
require(r"other\.projection_id\s*>=\s*levels\.cursor_id", "seek must start at deterministic cursor")
require(r"other\.projection_id\s*<\s*levels\.cursor_id", "seek must wrap before its cursor")
require(r"shared_player_snapshots_seek_idx[\s\S]+rules_version,\s*level,\s*projection_id",
        "roster seek needs a matching ordered index")
if re.search(r"order\s+by\s+(?:pg_catalog\.)?md5", sql, flags=re.IGNORECASE):
    raise AssertionError("roster selection must not hash-sort every candidate row")
require(r"primary\s+key\s*\(user_id,\s*requester_character_id,\s*rules_version\)",
        "daily roster storage must be bounded to one row per requester contract")
require(r"delete\s+from\s+shared_player_private\.daily_rosters\s+as\s+roster[\s\S]+roster\.user_id\s*=\s*v_user_id[\s\S]+roster\.requester_character_id",
        "omitted owned characters must not leave per-account roster rows behind")
require(r"roster_date_utc\s*=\s*v_date", "same UTC day must reuse its frozen roster")
require(r"requester_level\s*=\s*v_requester\.level", "level change must invalidate the cached band")
require(r"'requester_level',\s*v_roster\.requester_level", "wire envelope must return requester level")
if re.search(r"'published_at'\s*,", sql, flags=re.IGNORECASE):
    raise AssertionError("public roster rows must not expose an opponent's exact activity timestamp")
require(r"on\s+conflict\s*\(user_id,\s*character_id\)\s+do\s+update", "projection IDs must survive updates")
require(r"v_stats\s*->>\s*'max_health'\)::numeric\s*<\s*1", "max_health must be positive")
require(r"v_max_base\s*:=\s*v_level\s*\*\s*2\s*\+\s*16", "per-stat level cap is required")
require(r"v_base_total\s*>\s*v_max_total", "aggregate base-stat cap is required")
require(r"v_power\s+not\s+between\s+v_min_power\s+and\s+v_max_power",
        "raw stats and combat power need a coherence check")
for field in ("strength", "constitution", "dexterity", "intelligence", "wisdom", "charisma",
              "max_health", "max_mana", "adventure_trait_ids"):
    require(rf"'{field}'", f"missing validated public field: {field}")
for forbidden_wire in ("learned_skills", "usage_count", "mastery", "equipped_items",
                       "acquired_at_level", "rarity"):
    if forbidden_wire in sql.lower():
        raise AssertionError(f"forbidden public wire field remains: {forbidden_wire}")
require(r"interval\s+'15 minutes'", "server-side publication rate limit is required")
require(r"pg_column_size\(p_snapshots\)\s*>\s*65536", "batch size must be rejected before expansion")
require(r"pg_column_size\(p_snapshot\)\s*>\s*8192", "individual snapshot size cap is required")
require(r"last_called_at\s*<\s*interval\s+'5 seconds'", "sync RPC needs a call-level guard")
require(r"roster_call_limits[\s\S]+retry_after_seconds',\s*5",
        "roster RPC needs a committed account-level call guard")
require(r"create\s+table\s+shared_player_private\.roster_call_limits\s*\(\s*user_id\s+uuid\s+primary\s+key",
        "roster guard storage must stay bounded to one row per account")
require(r"create\s+table\s+shared_player_private\.roster_daily_issuance_limits\s*\(\s*user_id\s+uuid\s+primary\s+key",
        "daily issuance storage must stay bounded to one row per account")
require(r"roster_daily_issuance_limits\s*\([\s\S]+user_id\s+uuid\s+primary\s+key\s+references\s+auth\.users\(id\)\s+on\s+delete\s+cascade",
        "daily issuance row must cascade with its authenticated account")
require(r"alter\s+table\s+shared_player_private\.roster_daily_issuance_limits\s+enable\s+row\s+level\s+security",
        "daily issuance storage must have RLS enabled")
require(r"roster_daily_issuance_limits[\s\S]+issued_count\s+smallint[\s\S]+between\s+1\s+and\s+6",
        "new roster issuance must have a conservative per-account UTC-day ceiling")
require(r"budget\.roster_date_utc\s*<>\s*excluded\.roster_date_utc[\s\S]+budget\.issued_count\s*<\s*6",
        "daily budget must reset at UTC date change and refuse a seventh issuance")
require(r"'rate_limited',\s*true[\s\S]+?'daily_limit',\s*true",
        "daily issuance exhaustion must return an explicit rate-limited response")
require(r"v_hash\s*=\s*v_limit\.payload_hash", "same-payload server dedupe is required")
require(r"entry\.level\s*<\s*saved\.level", "accepted levels must never move backwards")
require(r"date_part\('epoch',\s*v_now\s*-\s*saved\.published_at\)\s*/\s*900",
        "accepted level advance must be bounded by elapsed publication windows")
require(r"'invalid_level',\s*true[\s\S]+?'level_change_out_of_range'",
        "implausible level changes must return an explicit invalid-level response")
if sql.lower().count("'shared-player-account:' || v_user_id::text") < 2:
    raise AssertionError("sync and roster RPCs must share one account lock")
roster_rpc = sql.lower().split("create function public.get_daily_public_player_roster", 1)[1]
lock_at = roster_rpc.find("'shared-player-account:' || v_user_id::text")
requester_at = roster_rpc.find("select * into v_requester")
cache_at = roster_rpc.find("select * into v_roster")
budget_at = roster_rpc.find("insert into shared_player_private.roster_daily_issuance_limits")
if not (0 <= lock_at < requester_at < cache_at < budget_at):
    raise AssertionError("account lock and cached-roster lookup must precede daily budget consumption")
require(r"limit\s+500", "opportunistic expiry cleanup must be bounded")
require(r"shared_player_snapshots_expiry_idx", "snapshot expiry cleanup index is required")
require(r"shared_player_daily_rosters_expiry_idx", "roster expiry cleanup index is required")
require(r"create\s+function\s+shared_player_private\.cleanup_expired_shared_player_data\(p_limit\s+integer\s+default\s+5000\)[\s\S]+security\s+invoker",
        "owner-scheduled expiry cleanup must remain a bounded private invoker function")
require(r"p_limit\s+is\s+null\s+or\s+p_limit\s+not\s+between\s+1\s+and\s+10000",
        "a NULL cleanup limit must not turn the bounded sweep into an unlimited delete")
require(r"revoke\s+all\s+on\s+function\s+shared_player_private\.cleanup_expired_shared_player_data\(integer\)[\s\S]+from\s+public,\s*anon,\s*authenticated,\s*service_role",
        "cleanup execution must not be available to app or service roles")
if re.search(r"grant\s+execute\s+on\s+function\s+shared_player_private\.cleanup_expired_shared_player_data",
             sql, flags=re.IGNORECASE):
    raise AssertionError("owner cleanup must not receive an app or service-role execute grant")
require(r"level\s+between\s+10\s+and\s+10000", "publication must start at level 10")
require(r"rules_version[^\n]+(?:=|is\s+distinct\s+from)\s*1", "rules version must be pinned")
require(r"never\s+authorize\s+XP,\s*gold,\s*loot\s+or\s+ranking",
        "non-economic trust boundary must be documented")

for forbidden in ("service_role key", "SUPABASE_SERVICE", "secret key", "apikey"):
    if forbidden.lower() in sql.lower():
        raise AssertionError(f"migration must not contain an app/server credential: {forbidden}")
if sql.count("$$") % 2:
    raise AssertionError("unbalanced PostgreSQL dollar quotes")
for ranking_mutation in (
    r"alter\s+table\s+(?:public\.)?ranking",
    r"(?:insert\s+into|update|delete\s+from)\s+(?:public\.)?ranking",
    r"create\s+or\s+replace\s+function\s+public\.get_leaderboard",
):
    if re.search(ranking_mutation, sql, flags=re.IGNORECASE):
        raise AssertionError("shared-player migration must not mutate existing ranking objects")

print(f"PASS {MIGRATION}")
print("PASS auth/RLS/self-exclusion/stable-ID/daily-freeze/+/-1/seek-wrap/rate/level-guard/expiry/privacy/minimal-wire contracts")
