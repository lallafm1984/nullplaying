#!/usr/bin/env python3
"""Static audit for the applied shared-player runtime-function hotfix."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "supabase/migrations/202609070004_shared_player_snapshots.sql"
HOTFIX = ROOT / "supabase/migrations/202609070005_fix_shared_player_runtime_functions.sql"


def function_block(source: str, signature: str) -> str:
    start = source.index(signature)
    end = source.index("\n$$;", start) + len("\n$$;")
    return source[start:end]


base = BASE.read_text(encoding="utf-8")
hotfix = HOTFIX.read_text(encoding="utf-8")
signatures = (
    "create function shared_player_private.is_valid_snapshot(p_snapshot jsonb)",
    "create function public.sync_public_player_snapshots(p_snapshots jsonb)",
    "create function public.get_daily_public_player_roster(\n",
)

for signature in signatures:
    expected = function_block(base, signature)
    expected = expected.replace("create function ", "create or replace function ", 1)
    expected = expected.replace("pg_catalog.least", "least")
    expected = expected.replace("pg_catalog.greatest", "greatest")
    assert expected in hotfix, f"hotfix body drifted for {signature}"

assert hotfix.count("create or replace function") == 3
assert "pg_catalog.least" not in hotfix
assert "pg_catalog.greatest" not in hotfix
assert hotfix.count("least(") == 3
assert hotfix.count("greatest(") == 6
assert "grant execute on function public.sync_public_player_snapshots(jsonb) to authenticated;" in hotfix
assert "grant execute on function public.get_daily_public_player_roster(uuid, integer) to authenticated;" in hotfix
assert "shared_player_private.is_valid_snapshot(v_sample)" in hotfix
assert "raise exception 'shared-player valid snapshot smoke check failed'" in hotfix

print(f"PASS {HOTFIX}")
print("PASS exact function replacement, ACL restatement, and executable validator smoke contract")
