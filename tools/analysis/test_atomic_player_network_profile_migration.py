#!/usr/bin/env python3
"""Offline structural audit for the unified ranking/shared-player RPC migration."""

from pathlib import Path
import re


PROJECT = Path(__file__).resolve().parents[2]
MIGRATION = PROJECT / "supabase/migrations/202609070006_atomic_player_network_profile_sync.sql"
sql = MIGRATION.read_text(encoding="utf-8")


def require(pattern: str, reason: str) -> None:
    if re.search(pattern, sql, flags=re.IGNORECASE | re.DOTALL) is None:
        raise AssertionError(reason)


require(r"create\s+function\s+public\.sync_player_network_profile\(p_characters\s+jsonb\)",
        "one unified public RPC is required")
require(r"security\s+definer\s+set\s+search_path\s*=\s*''",
        "the authenticated bridge needs a pinned definer context")
require(r"v_user_id\s+uuid\s*:=\s*auth\.uid\(\)",
        "the RPC must bind all mutations to Supabase Auth")
require(r"pg_column_size\(p_characters\)\s*>\s*65536",
        "attacker-controlled input must be size-bounded before expansion")
require(r"jsonb_array_length\(p_characters\)\s*>\s*3",
        "a profile may contain no more than three character slots")
require(r"item\.value\s*-\s*'slot_id'\)",
        "the existing shared snapshot validator must receive the exact legacy shape")
require(r"shared_player_private\.is_valid_snapshot",
        "the stronger existing snapshot validator must be reused")
require(r"count\(distinct\s+item\.value\s*->>\s*'character_id'\)",
        "duplicate character identities must be rejected")
require(r"count\(distinct\s+item\.value\s*->>\s*'slot_id'\)",
        "duplicate local slots must be rejected")
require(r"where\s+\(item\.value\s*->>\s*'level'\)::bigint\s*>=\s*20",
        "only level-20+ rows belong in the existing ranking contract")

preflight_call = sql.lower().find(
    "v_ranking_preflight := ranking_private.sync_ranking_entries_outcome"
)
shared_call = sql.lower().find("v_shared_result := public.sync_public_player_snapshots")
soft_reject = sql.lower().find("if coalesce((v_shared_result ->> 'synced_count')::integer, -1) < 0")
ranking_apply = sql.lower().find(
    "v_ranking_result := ranking_private.sync_ranking_entries_outcome",
    shared_call,
)
if not (0 <= preflight_call < shared_call < soft_reject < ranking_apply):
    raise AssertionError(
        "ranking soft limits must be preflighted before shared publication and applied only after its acceptance"
    )

require(r"'accepted',\s*false[\s\S]+?'ranking_synced_count',\s*0",
        "a shared soft rejection must report that ranking was not changed")
require(r"'accepted',\s*true[\s\S]+?'ranking_synced_count',\s*v_ranking_count",
        "an atomic success acknowledgement must include its ranking subset count")
require(r"revoke\s+all\s+on\s+function\s+public\.sync_player_network_profile\(jsonb\)[\s\S]+?from\s+public,\s*anon,\s*authenticated,\s*service_role",
        "default and non-app execution must be revoked explicitly")
require(r"grant\s+execute\s+on\s+function\s+public\.sync_player_network_profile\(jsonb\)\s+to\s+authenticated",
        "only authenticated app sessions may call the bridge")

require(r"ranking_last_called_at[\s\S]+ranking_last_synced_at[\s\S]+ranking_payload_hash",
        "the bounded existing account guard must cover legacy ranking publication")
require(r"profile_contract_version\s+smallint\s+not\s+null\s+default\s+0",
        "upgraded accounts need a persistent strong-contract marker")
require(r"statement_timestamp\(\)[\s\S]+interval\s+'5 seconds'[\s\S]+interval\s+'5 minutes'",
        "legacy ranking writes need server-time burst and changed-payload limits")
burst_at = sql.lower().find("v_now - v_guard.ranking_last_called_at < interval '5 seconds'")
same_hash_at = sql.lower().find("v_guard.ranking_payload_hash = v_payload_hash")
if not (0 <= burst_at < same_hash_at):
    raise AssertionError("the five-second database guard must precede unchanged-payload dedupe")
require(r"ranking_payload_hash\s*=\s*v_payload_hash[\s\S]+?set\s+ranking_last_called_at\s*=\s*v_now[\s\S]+?return",
        "unchanged ranking retries must be idempotent")
require(r"create\s+function\s+ranking_private\.sync_ranking_entries_outcome\("
        r"[\s\S]+?ranking_last_synced_at[\s\S]+?interval\s+'5 minutes'[\s\S]+?"
        r"if\s+p_apply\s+then[\s\S]+?set\s+ranking_last_called_at\s*=\s*v_now[\s\S]+?"
        r"'accepted',\s*false",
        "the shared helper must return a strict cooldown outcome and record applied rejections")
require(r"create\s+or\s+replace\s+function\s+ranking_private\.sync_ranking_entries\(p_entries\s+jsonb\)"
        r"[\s\S]+?sync_ranking_entries_outcome\(p_entries,\s*true\)"
        r"[\s\S]+?set_config\('response\.status',\s*'429',\s*true\)",
        "the released void writer must preserve committed PostgREST 429 behavior")
cooldown_at = sql.lower().find("v_now - v_guard.ranking_last_synced_at < interval '5 minutes'")
strong_contract_at = sql.lower().find("profile_contract_version >= 1")
if not (0 <= cooldown_at < strong_contract_at):
    raise AssertionError("cooldown rejection must run before strong snapshot joins")
unified_sql = sql.lower().split(
    "create function public.sync_player_network_profile(p_characters jsonb)", 1
)[1]
ranking_lock = unified_sql.find("hashtextextended(v_user_id::text, 0)")
shared_lock = unified_sql.find(
    "hashtextextended('shared-player-account:' || v_user_id::text, 0)"
)
preflight_lock = unified_sql.find(
    "v_ranking_preflight := ranking_private.sync_ranking_entries_outcome"
)
if not (0 <= ranking_lock < shared_lock < preflight_lock):
    raise AssertionError(
        "the unified path must lock ranking then shared account before the helper locks sync_limits"
    )
require(r"profile_contract_version\s*>=\s*1[\s\S]+?ranking profile does not match validated public snapshots",
        "legacy ranking writes from upgraded accounts must match strong shared snapshots")
require(r"sync_ranking_entries_outcome\([\s\S]+?v_ranking_entries,[\s\S]+?true[\s\S]+?"
        r"ranking apply was rejected after successful locked preflight[\s\S]+?"
        r"set\s+profile_contract_version\s*=\s*1",
        "the strong-contract marker must be set only after both projections succeed")

require(r"create\s+or\s+replace\s+function\s+public\.get_leaderboard\([\s\S]+?security\s+definer",
        "legacy bounded ranking reads must be replaced before direct-table revocation")
require(r"create\s+or\s+replace\s+function\s+public\.get_leaderboard_v2\([\s\S]+?security\s+definer",
        "compact bounded ranking reads must be replaced before direct-table revocation")
require(r"ranked\.list_index\s*<\s*params\.limit_count[\s\S]+?limit\s*\(select\s+limit_count\s+from\s+params\)",
        "the legacy row RPC needs an absolute 1,000-row response cap even when ranks tie")
require(r"visible\.user_id\s*=\s*params\.current_user_id[\s\S]+?ranking-public-v1:",
        "the legacy row RPC must not disclose other accounts' auth UUIDs")
require(r"revoke\s+select\s+on\s+table\s+public\.ranking_entries\s+from\s+public,\s*anon,\s*authenticated",
        "authenticated clients must not scrape the ranking table directly")
require(r"drop\s+policy\s+if\s+exists\s+\"authenticated users can read rankings\"",
        "the old allow-all ranking policy must be removed")

require(r"daily_rosters_snapshots_wire_size_v2_check[\s\S]+?pg_column_size\(snapshots\)\s*<=\s*24576[\s\S]+?not\s+valid",
        "new and changed roster rows need a deployment-safe 24 KiB constraint")
require(r"before\s+insert\s+or\s+update\s+of\s+snapshots[\s\S]+?enforce_daily_roster_wire_size_v2",
        "roster generation must fail before storing an oversized payload")
require(r"if\s+not\s+exists[\s\S]+?pg_column_size\(roster\.snapshots\)\s*>\s*24576[\s\S]+?validate\s+constraint",
        "the tighter constraint should validate immediately when legacy rows permit")
roster_function = sql.lower().split(
    "create or replace function public.get_daily_public_player_roster", 1
)[1].split("\n$$;", 1)[0]
if "limit 24" in roster_function or roster_function.count("limit 20") != 3:
    raise AssertionError("new roster generation must cap both seeks and the final aggregate at 20")

for cleanup_index in (
    r"roster_call_limits\s*\(last_called_at,\s*user_id\)",
    r"roster_daily_issuance_limits\s*\(last_issued_at,\s*user_id\)",
    r"shared_player_sync_limits_cleanup_idx[\s\S]+?greatest\([\s\S]+?user_id",
):
    require(cleanup_index, "stale-guard cleanup needs an ordered supporting index")

require(r"create\s+or\s+replace\s+function\s+shared_player_private\.cleanup_expired_shared_player_data[\s\S]+?interval\s+'90 days'",
        "the owner cleanup must retire abandoned bounded guard rows")
for guard_table in ("roster_call_limits", "roster_daily_issuance_limits", "sync_limits"):
    require(rf"delete\s+from\s+shared_player_private\.{guard_table}",
            f"owner cleanup must cover {guard_table}")
require(r"not\s+exists\s*\([\s\S]+?public\.ranking_entries[\s\S]+?stale_sync_guards_deleted",
        "a sync/ranking guard must survive while its ranking row exists")
if sql.lower().count("for update of guard skip locked") < 3:
    raise AssertionError("stale guard cleanup must skip concurrent client rows")
require(r"revoke\s+all\s+on\s+function\s+shared_player_private\.cleanup_expired_shared_player_data\(integer\)[\s\S]+?service_role",
        "the cleanup hook must remain unavailable to app and service roles")

for forbidden in (
    r"create\s+table",
    r"create\s+(?:or\s+replace\s+)?function\s+public\.battle_",
    r"battle_private\.",
    r"insert\s+into\s+public\.battle_",
):
    if re.search(forbidden, sql, flags=re.IGNORECASE):
        raise AssertionError(f"forbidden legacy battle coupling: {forbidden}")

lower = sql.lower()
for forbidden_result in (
    "p_results", "p_outcome", "score_delta", "ticket_count", "reward_payload",
):
    if forbidden_result in lower:
        raise AssertionError(f"local arena result crossed the server trust boundary: {forbidden_result}")

if sql.count("$$") % 2:
    raise AssertionError("unbalanced PostgreSQL dollar quotes")

print(f"PASS {MIGRATION}")
print("PASS auth/strict-shape/atomic-sync/rate/RPC-only-read/24KiB/no-arena-authority contracts")
