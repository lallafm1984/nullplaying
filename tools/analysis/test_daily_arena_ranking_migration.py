#!/usr/bin/env python3
"""Network-free structural audit for the compact daily arena ranking boundary."""

from pathlib import Path
import re


PROJECT = Path(__file__).resolve().parents[2]
MIGRATION = PROJECT / "supabase/migrations/202609070008_daily_arena_ranking_boundary.sql"
FIXTURE = PROJECT / "supabase/tests/daily_arena_ranking.sql"
sql = MIGRATION.read_text(encoding="utf-8")
fixture = FIXTURE.read_text(encoding="utf-8")
lower = sql.lower()


def require(pattern: str, reason: str) -> None:
    if re.search(pattern, sql, flags=re.IGNORECASE | re.DOTALL) is None:
        raise AssertionError(reason)


for table in (
    "daily_arena_leaderboard_snapshots",
    "daily_arena_leaderboard_state",
    "season_standings",
    "season_character_bindings",
    "account_call_limits",
    "entry_sync_limits",
    "daily_arena_leaderboard_rows",
    "daily_arena_before_images",
):
    require(rf"create\s+table\s+arena_ranking_private\.{table}", f"missing private {table}")
    require(
        rf"alter\s+table\s+arena_ranking_private\.{table}\s+enable\s+row\s+level\s+security",
        f"RLS must be enabled on {table}",
    )

require(
    r"revoke\s+all\s+on\s+table[\s\S]+?from\s+public,\s*anon,\s*authenticated,\s*service_role",
    "arena ranking storage must not be directly exposed",
)
require(
    r"create\s+function\s+public\.sync_arena_ranking_entry\(\s*"
    r"p_character_id\s+uuid,\s*p_score\s+integer,\s*p_completed_battles\s+integer,\s*"
    r"p_wins\s+integer,\s*p_losses\s+integer,\s*p_draws\s+integer,\s*"
    r"p_rules_version\s+integer\s+default\s+1\s*\)",
    "arena write must be one fixed-size scalar aggregate",
)
if "public.ranking_entries" in lower:
    raise AssertionError("Lv10 arena ranking must use shared snapshots, not the Lv20 general ranking table")
require(
    r"user_id\s+uuid\s+not\s+null\s+references\s+auth\.users\(id\)\s+on\s+delete\s+cascade",
    "arena standing must survive profiles but cascade with its Auth account",
)
if re.search(
    r"foreign\s+key\s*\(user_id,\s*character_id\)\s*references\s+"
    r"shared_player_private\.snapshots",
    sql,
    flags=re.IGNORECASE | re.DOTALL,
):
    raise AssertionError("shared-profile expiry/removal must not erase arena anti-cheat history")
require(
    r"create\s+index\s+arena_season_standings_profile_idx\s+on\s+"
    r"arena_ranking_private\.season_standings\s*\(user_id,\s*character_id\)",
    "profile mirror and Auth cascade need a user-character standing index",
)
require(
    r"create\s+index\s+arena_season_character_bindings_user_idx\s+on\s+"
    r"arena_ranking_private\.season_character_bindings\s*\(user_id,\s*season_id,\s*binding_slot\)",
    "binding Auth cascade and account probes need a user-leading index",
)
require(
    r"snapshot\.user_id\s*=\s*v_user_id[\s\S]+?snapshot\.character_id\s*=\s*p_character_id"
    r"[\s\S]+?snapshot\.level\s+between\s+10\s+and\s+10000"
    r"[\s\S]+?snapshot\.expires_at\s*>\s*v_now",
    "server must derive an unexpired Lv10+ identity owned by auth.uid()",
)
require(
    r"after\s+insert\s+or\s+update\s+of\s+display_name,\s*hero_class,\s*level,\s*expires_at\s+"
    r"on\s+shared_player_private\.snapshots[\s\S]+?mirror_shared_snapshot_identity",
    "profile publication and identity changes must reactivate/mirror without an arena match",
)
require(
    r"if\s+tg_op\s*=\s*'DELETE'\s+then[\s\S]+?update\s+"
    r"arena_ranking_private\.season_standings[\s\S]+?profile_expires_at\s*=\s*"
    r"pg_catalog\.statement_timestamp\(\)[\s\S]+?return\s+old",
    "profile deletion must expire rather than erase arena anti-cheat history",
)
require(
    r"after\s+delete\s+on\s+shared_player_private\.snapshots[\s\S]+?"
    r"mirror_shared_snapshot_identity",
    "profile deletion expiry trigger is missing",
)
mirror_start = lower.find("create function arena_ranking_private.mirror_shared_snapshot_identity()")
mirror_end = lower.find(
    "revoke all on function arena_ranking_private.mirror_shared_snapshot_identity()",
    mirror_start,
)
mirror_definition = lower[mirror_start:mirror_end]
mirror_daily_lock_at = mirror_definition.find("pg_advisory_xact_lock_shared(20260908, 8)")
mirror_account_lock_at = mirror_definition.find("arena-ranking-account:")
mirror_standing_write_at = mirror_definition.find("update arena_ranking_private.season_standings")
if not (0 <= mirror_daily_lock_at < mirror_account_lock_at < mirror_standing_write_at):
    raise AssertionError("profile mirror lock order must be daily barrier, account advisory, standing row")

require(r"completed_battles\s+integer\s+not\s+null\s+check\s*\(completed_battles\s+between\s+10",
        "ten placement battles are required")
require(r"wins::bigint\s*\+\s*losses::bigint\s*\+\s*draws::bigint\s*=\s*completed_battles::bigint",
        "W/L/D must equal completed battles")
require(r"1000::bigint\s*-\s*24::bigint\s*\*\s*completed_battles::bigint",
        "global score lower plausibility bound is missing")
require(r"1000::bigint\s*\+\s*24::bigint\s*\*\s*completed_battles::bigint",
        "global score upper plausibility bound is missing")
require(r"p_completed_battles\s*<\s*v_existing\.completed_battles[\s\S]+?'record_decrease'",
        "aggregate records must be monotonic")
require(r"abs\(p_score::bigint\s*-\s*v_existing\.score::bigint\)\s*>\s*24::bigint\s*\*\s*v_completed_delta",
        "score movement must be bounded by newly completed battles")
require(r"p_completed_battles::bigint\s*-\s*v_daily_base::bigint\s*>\s*20",
        "server UTC daily match growth must be capped at twenty")
require(r"call_count\s+smallint[\s\S]+?between\s+1\s+and\s+24",
        "account non-burst calls must be capped at twenty-four per UTC day")
require(r"interval\s+'5 seconds'", "account burst cooldown is missing")
require(r"interval\s+'15 minutes'", "changed standing cooldown is missing")
require(
    r"select\s+\*\s+into\s+v_existing[\s\S]+?for\s+update;\s*"
    r"v_has_existing\s*:=\s*found;[\s\S]+?if\s+v_has_sync_limit\s+"
    r"and\s+v_has_existing\s+and\s+v_sync_limit\.payload_hash\s*=\s*v_payload_hash",
    "hash dedupe must lock and require the corresponding arena standing",
)
require(
    r"v_now\s*-\s*v_sync_limit\.last_synced_at\s*<\s*interval\s+'15 minutes'\s+"
    r"and\s+v_sync_limit\.payload_hash\s+is\s+distinct\s+from\s+v_payload_hash",
    "an identical previously accepted aggregate must be able to restore a missing standing",
)
require(r"auth\.users[\s\S]+?created_at[\s\S]+?v_account_max_battles",
        "first-publication account-age plausibility bound is missing")
require(
    r"binding_slot\s+smallint\s+not\s+null\s+check\s*\(binding_slot\s+between\s+1\s+and\s+3\)"
    r"[\s\S]+?primary\s+key\s*\(season_id,\s*user_id,\s*binding_slot\)"
    r"[\s\S]+?unique\s*\(season_id,\s*user_id,\s*character_id\)",
    "the server must own exactly three reusable character slots per account-season",
)
require(
    r"from\s*\(values\s*\(1::smallint\),\s*\(2::smallint\),\s*\(3::smallint\)\)"
    r"[\s\S]+?order\s+by\s+candidate\.binding_slot[\s\S]+?limit\s+1",
    "new characters must fill the lowest empty server slot first",
)
require(
    r"previous\.profile_expires_at\s*<=\s*v_now[\s\S]+?not\s+exists\s*\("
    r"[\s\S]+?shared_player_private\.snapshots\s+as\s+active_profile"
    r"[\s\S]+?active_profile\.expires_at\s*>\s*v_now[\s\S]+?"
    r"for\s+update\s+of\s+binding,\s*previous",
    "only an expired slot without an active shared profile may be replaced under row locks",
)
require(
    r"if\s+p_completed_battles\s*<>\s*10\s+then[\s\S]+?"
    r"'error_code',\s*'replacement_requires_placement'",
    "replacement characters must submit the exact ten-match placement checkpoint",
)

old_standing_delete_at = lower.find("delete from arena_ranking_private.season_standings")
old_guard_delete_at = lower.find("delete from arena_ranking_private.entry_sync_limits")
binding_reassign_at = lower.find("update arena_ranking_private.season_character_bindings")
if not (0 <= old_standing_delete_at < old_guard_delete_at < binding_reassign_at):
    raise AssertionError("replacement must delete old standing, delete old guard, then reassign one slot")

account_guard_at = lower.find("insert into arena_ranking_private.account_call_limits")
semantic_return_at = lower.find("'error_code', 'invalid_standing'")
if not (0 <= account_guard_at < semantic_return_at):
    raise AssertionError("expected invalid calls must commit the account guard before normal rejection")
for code in (
    "invalid_standing",
    "eligible_owned_profile_required",
    "invalid_account_time",
    "account_age_limit",
    "record_decrease",
    "score_movement_limit",
    "daily_match_limit",
    "season_character_limit",
    "replacement_requires_placement",
):
    if f"'error_code', '{code}'" not in lower:
        raise AssertionError(f"missing bounded normal rejection code: {code}")

require(r"pg_advisory_xact_lock_shared\(20260908,\s*8\)",
        "every arena standing statement needs the shared cutoff barrier")
require(r"pg_advisory_xact_lock\(20260908,\s*8\)",
        "arena publication needs its distinct exclusive cutoff barrier")
if "pg_advisory_xact_lock(20260905, 1)" in lower:
    raise AssertionError("arena publication must not reuse the general ranking advisory key")
require(r"for\s+each\s+statement\s+execute\s+function\s+arena_ranking_private\.lock_daily_arena_write",
        "the cutoff barrier must be acquired before row locks, including FK cascades")
require(r"capture_daily_arena_before_image[\s\S]+?old\.daily_admitted_at\s*<\s*v_cutoff_at",
        "the first post-cutoff mutation needs a bounded before-image")
require(r"snapshot_id\s+not\s+in\s*\([\s\S]+?order\s+by\s+keep\.settled_at\s+desc[\s\S]+?limit\s+2",
        "only the latest two arena snapshots may be retained")

require(r"rank\(\)\s+over\s*\(\s*order\s+by\s+standing\.score\s+desc,\s*standing\.wins\s+desc,"
        r"\s*standing\.losses\s+asc,\s*standing\.draws\s+desc",
        "rank ties must use score, wins, losses and draws")
require(r"row_number\(\)\s+over\s*\([\s\S]+?standing\.score_achieved_at\s+asc,"
        r"\s*standing\.character_id,[\s\S]+?arena-ranking-public-v1:[\s\S]+?standing\.user_id",
        "list order needs server time and the same public account tie-breaker as Android")
require(r"row\.list_index\s*<\s*997", "the precomputed global list must stop at 997")
require(r"jsonb_array_length\(top_entries\)\s*<=\s*997", "stored global list count cap is missing")
require(r"pg_column_size\(top_entries\)\s*<=\s*491520", "stored compact payload byte cap is missing")
require(r"order\s+by\s+row\.list_index\s+limit\s+3", "own snapshot rows must be capped at three")
require(r"arena-ranking-public-v1:", "raw Auth UUIDs must be replaced with stable pseudonyms")
require(r"p_known_snapshot_id\s*=\s*v_snapshot\.snapshot_id[\s\S]+?return\s+v_response",
        "known snapshots must return metadata only")

bad_season = re.search(r"'season_id'\s*,\s*v_(?:season_id|snapshot\.season_id)(?!::text)", sql)
if bad_season:
    raise AssertionError("every arena JSON receipt must encode season_id as a string")
for forbidden_parameter in (
    "p_client_time",
    "p_timestamp",
    "p_match",
    "p_result",
    "p_ticket",
    "p_reward",
    "p_equipment",
    "p_skill",
):
    if forbidden_parameter in lower:
        raise AssertionError(f"local arena authority crossed into the server signature: {forbidden_parameter}")

require(r"cron\.schedule\(\s*'alarmquest-daily-arena-leaderboard',\s*'17 \* \* \* \*'",
        "arena publication needs its staggered hourly idempotent retry")
if "cron.unschedule(jobid)\n  from cron.job\n where jobname = 'alarmquest-daily-leaderboard'" in lower:
    raise AssertionError("008 must not replace or unschedule the 007 general ranking job")
if sql.count("$$") % 2:
    raise AssertionError("unbalanced PostgreSQL dollar quotes")
for snippet in (
    "profile deletion did not preserve and expire the arena anti-cheat standing",
    "same aggregate did not reactivate the preserved standing",
    "republished profile did not reactivate its preserved arena standing",
    "Auth account deletion did not cascade arena/profile state",
    "first empty slot rejected a valid legacy aggregate above placement",
    "non-placement replacement changed its inactive server slot",
    "placement replacement was not an atomic bounded slot reuse",
    "fourth active character unexpectedly evicted an arena slot",
):
    if snippet not in fixture:
        raise AssertionError(f"missing cascade recovery fixture assertion: {snippet}")

print(f"PASS {MIGRATION}")
print("PASS Lv10/legacy-first-slot/exact10-replacement/reusable3-slot/rate/plausibility/cascade-recovery/UTC/top997+own3/RPC-only contracts")
