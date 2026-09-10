#!/usr/bin/env python3
"""Network-free tests for the production-gated shared-player E2E harness."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import tempfile
import unittest
import subprocess

import run_shared_player_live_e2e as live
import run_shared_player_preview_e2e as preview


USER_A = "11111111-1111-4111-8111-111111111111"
USER_B = "22222222-2222-4222-8222-222222222222"
SERVER_NOON = 1_788_782_400_000


def config() -> live.LiveConfig:
    return live.LiveConfig(
        public=preview.PreviewConfig(
            live.PRODUCTION_BASE_URL,
            "sb_publishable_offline_live_qa_value",
        ),
        service_role_key="sb_secret_offline_live_qa_value",
        supabase_cli=live.SUPABASE_CLI_PATH,
        linked_cli_workdir=live.LINKED_CLI_WORKDIR,
    )


def completed_counts() -> dict[str, int]:
    return {
        "auth_users": 2,
        "fresh_exact_qa_users": 2,
        "auth_identities_for_users": 2,
        "auth_sessions_for_users": 2,
        "auth_refresh_tokens_for_users": 2,
        "snapshots_for_users": 2,
        "expected_snapshots": 2,
        "unexpected_snapshots_for_users": 0,
        "ranking_entries_for_users": 2,
        "expected_ranking_entries": 2,
        "unexpected_ranking_entries_for_users": 0,
        "foreign_snapshot_identity_collisions": 0,
        "foreign_ranking_identity_collisions": 0,
        "foreign_arena_identity_collisions": 0,
        "daily_rosters_for_users": 1,
        "sync_limits_for_users": 2,
        "profile_contract_users": 2,
        "roster_limits_for_users": 1,
        "issuance_limits_for_users": 1,
        "ranking_before_images_for_users": 0,
        "ranking_daily_rows_for_users": 0,
        "ranking_snapshot_qa_rows": 0,
        "arena_standings_for_users": 2,
        "arena_bindings_for_users": 2,
        "expected_arena_standings": 2,
        "unexpected_arena_standings_for_users": 0,
        "arena_account_limits_for_users": 2,
        "arena_entry_limits_for_users": 2,
        "arena_before_images_for_users": 0,
        "arena_daily_rows_for_users": 0,
        "arena_snapshot_qa_rows": 0,
        "admin_subscribers_for_users": 0,
        "admin_events_for_users": 0,
        "embedded_qa_rows": 1,
        "foreign_embedded_qa_rows": 0,
    }


def preflight_counts(server_now: int = SERVER_NOON) -> dict[str, int]:
    since = (server_now // 1000) % 86_400
    return {
        "server_now_epoch_millis": server_now,
        "utc_seconds_since_midnight": since,
        "utc_seconds_until_midnight": 86_400 - since,
        "shared_private_tables": 5,
        "ranking_private_tables": 4,
        "arena_private_tables": 8,
        "snapshot_identity_collisions": 0,
        "ranking_identity_collisions": 0,
        "arena_identity_collisions": 0,
        "arena_guard_character_collisions": 0,
        "arena_binding_character_collisions": 0,
        "roster_name_remnants": 0,
        "ranking_before_image_remnants": 0,
        "ranking_daily_row_remnants": 0,
        "ranking_snapshot_name_remnants": 0,
        "arena_before_image_remnants": 0,
        "arena_daily_row_remnants": 0,
        "arena_snapshot_name_remnants": 0,
        "auth_email_collisions": 0,
        "auth_run_tag_collisions": 0,
        "public_rpcs": 5,
        "rpc_acl_violations": 0,
        "private_select_acl_violations": 0,
        "cron_jobs": 2,
    }


class FakePublicClient:
    def __init__(self) -> None:
        self.created: list[str] = []
        self.profiles: list[dict] = []
        self.roster_calls = 0
        self.general_calls: list[str | None] = []
        self.arena_calls: list[str | None] = []
        self.arena_syncs: list[tuple[str, dict[str, int]]] = []

    def create_qa_user(self, identity, password, run_tag):  # noqa: ANN001
        self.created.append(identity.role)
        self.assert_secret_shape(password, run_tag)
        return USER_A if identity.role == "requester" else USER_B

    @staticmethod
    def assert_secret_shape(password: str, run_tag: str) -> None:
        if len(password) < 32 or not live.RUN_TAG_PATTERN.fullmatch(run_tag):
            raise AssertionError("invalid generated test credential")

    def sign_in(self, identity, password, expected_user_id):  # noqa: ANN001
        self.assert_secret_shape(password, identity.display_name.rsplit("-", 1)[-1])
        return preview.AnonymousSession("token-kept-in-memory", expected_user_id)

    def sync_profile(self, session, profile):  # noqa: ANN001
        self.profiles.append(dict(profile))
        return {
            "accepted": True,
            "profile_version": 1,
            "rules_version": 1,
            "synced_count": 1,
            "ranking_synced_count": 1,
            "server_now": SERVER_NOON,
            "deduplicated": False,
        }

    def get_roster(self, session, character_id):  # noqa: ANN001
        self.roster_calls += 1
        opponent = dict(self.profiles[1])
        del opponent["character_id"]
        del opponent["slot_id"]
        opponent["projection_id"] = "33333333-3333-4333-8333-333333333333"
        server_now = 1_788_739_201_000 + (self.roster_calls - 1) * 6_000
        return {
            "roster_id": "44444444-4444-4444-8444-444444444444",
            "roster_date_utc": "2026-09-07",
            "requester_level": live.LIVE_LEVEL,
            "rules_version": preview.RULES_VERSION,
            "generated_at": 1_788_739_200_000,
            "valid_until": 1_788_825_600_000,
            "server_now": server_now,
            "snapshots": [opponent],
        }

    def get_daily_leaderboard(self, session, known_snapshot_id=None):  # noqa: ANN001
        self.general_calls.append(known_snapshot_id)
        base = {
            "snapshot_id": "utc:1788739200000",
            "settled_at": 1_788_739_200_000,
            "next_settlement_at": 1_788_825_600_000,
            "generated_at": 1_788_739_201_000,
            "server_now": SERVER_NOON + len(self.general_calls),
            "is_bootstrap": False,
            "unchanged": known_snapshot_id == "utc:1788739200000",
            "t": 1,
        }
        if base["unchanged"]:
            return base
        return {
            **base,
            "e": [{
                "r": 1,
                "i": 0,
                "c": "33333333-3333-4333-8333-333333333333",
                "n": "ExistingRanker",
                "h": "MAGE",
                "l": 100,
                "p": 1000,
                "a": 1_788_700_000_000,
                "s": "GATE",
            }],
            "o": [],
        }

    def sync_arena_ranking(self, session, character_id, standing):  # noqa: ANN001
        self.arena_syncs.append((character_id, dict(standing)))
        return {
            "accepted": True,
            "season_id": "1",
            "rules_version": 1,
            "server_now": SERVER_NOON,
            "deduplicated": False,
        }

    def get_daily_arena_leaderboard(self, session, known_snapshot_id=None):  # noqa: ANN001
        self.arena_calls.append(known_snapshot_id)
        snapshot_id = "arena:s1:r1:utc:1788739200000"
        base = {
            "snapshot_id": snapshot_id,
            "season_id": "1",
            "rules_version": 1,
            "settled_at": 1_788_739_200_000,
            "next_settlement_at": 1_788_825_600_000,
            "generated_at": 1_788_739_202_000,
            "server_now": SERVER_NOON + len(self.arena_calls),
            "is_bootstrap": False,
            "unchanged": known_snapshot_id == snapshot_id,
            "t": 1,
        }
        if base["unchanged"]:
            return base
        return {
            **base,
            "e": [{
                "r": 1,
                "i": 0,
                "u": "44444444-4444-4444-8444-444444444444",
                "c": "55555555-5555-4555-8555-555555555555",
                "n": "ExistingArena",
                "h": "PALADIN",
                "l": 100,
                "p": 1100,
                "b": 10,
                "w": 6,
                "x": 3,
                "d": 1,
                "a": 1_788_700_000_000,
            }],
            "o": [],
        }


class FakeManagementClient:
    def __init__(self) -> None:
        self.deleted = False
        self.cleanup_sql = ""
        self.cleanup_parameters: list[object] = []

    def query(self, sql, parameters, *, read_only):  # noqa: ANN001
        if "information_schema.tables" in sql:
            return [{"audit": preflight_counts()}]
        if "deleted as (" in sql:
            if read_only:
                raise AssertionError("cleanup was marked read-only")
            self.cleanup_sql = sql
            self.cleanup_parameters = list(parameters)
            self.deleted = True
            target_count = int(parameters[-1])
            return [{"audit": {
                "requested_auth_users": target_count,
                "eligible_auth_users": target_count,
                "deleted_auth_users": target_count,
            }}]
        if "jsonb_build_object" in sql:
            values = {key: 0 for key in completed_counts()} if self.deleted else completed_counts()
            return [{"audit": values}]
        raise AssertionError("unexpected fake SQL")


class SharedPlayerLiveE2ETest(unittest.TestCase):
    def test_production_requires_exact_ack_and_three_distinct_credentials(self) -> None:
        good = {
            live.LIVE_ACK_ENV: live.PRODUCTION_PROJECT_REF,
            live.PUBLISHABLE_KEY_ENV: "sb_publishable_offline_live_qa_value",
            live.SERVICE_ROLE_KEY_ENV: "sb_secret_offline_live_qa_value",
        }
        loaded = live.load_live_config(good)
        self.assertEqual(live.PRODUCTION_BASE_URL, loaded.public.base_url)
        for changed in (
            {**good, live.LIVE_ACK_ENV: "wrong"},
            {key: value for key, value in good.items() if key != live.SERVICE_ROLE_KEY_ENV},
            {**good, live.SERVICE_ROLE_KEY_ENV: good[live.PUBLISHABLE_KEY_ENV]},
        ):
            with self.assertRaises((live.LiveSafetyError, preview.PreviewError)):
                live.load_live_config(changed)

    def test_plan_uses_fixed_ids_ascii_run_names_and_isolated_level(self) -> None:
        first = live.make_plan("260907ABCD")
        second = live.make_plan("260907EFGH")
        self.assertEqual(live.REQUESTER_CHARACTER_ID, first.requester.character_id)
        self.assertEqual(live.OPPONENT_CHARACTER_ID, first.opponent.character_id)
        self.assertNotEqual(first.requester.display_name, second.requester.display_name)
        self.assertTrue(first.requester.display_name.isascii())
        snapshot = live.build_live_snapshot(first.requester)
        self.assertEqual(10_000, snapshot["level"])
        self.assertEqual(set(live.PROFILE_FIELDS), set(snapshot))
        self.assertEqual(1, snapshot["slot_id"])

    def test_public_client_has_only_the_five_reviewed_006_to_008_rpc_paths(self) -> None:
        client = live.LiveSupabaseClient(config())
        self.assertEqual(5, len(live.ALLOWED_RPC_PATHS))
        for path in live.ALLOWED_RPC_PATHS:
            self.assertEqual(live.PRODUCTION_BASE_URL + path, client._rpc_url_for(path))
        self.assertNotIn(preview.SYNC_SNAPSHOTS_PATH, live.ALLOWED_RPC_PATHS)
        with self.assertRaises(live.LiveSafetyError):
            client._rpc_url_for("/rest/v1/rpc/sync_ranking_entries")

    def test_unified_and_arena_outgoing_contracts_are_exact(self) -> None:
        plan = live.make_plan("260907ABCD")
        profile = live.build_live_snapshot(plan.requester)
        live.validate_live_profile(profile)
        with self.assertRaises(live.LiveSafetyError):
            live.validate_live_profile({**profile, "equipment": []})
        standing = live.build_live_arena_standing(plan.requester)
        live.validate_arena_standing(plan.requester.character_id, standing)
        with self.assertRaises(live.LiveSafetyError):
            live.validate_arena_standing(
                plan.requester.character_id,
                {**standing, "wins": standing["wins"] + 1},
            )

    def test_server_utc_cutoff_margin_is_fail_closed(self) -> None:
        started_at = live.assert_preflight_clean(preflight_counts())
        self.assertTrue(started_at.endswith("+00:00"))
        for seconds in (60, 86_400 - 60):
            unsafe = preflight_counts(1_788_739_200_000 + seconds * 1000)
            with self.assertRaisesRegex(live.LiveSafetyError, "UTC daily cutoff"):
                live.assert_preflight_clean(unsafe)

    def test_daily_contracts_are_bounded_conditional_and_reject_qa_leaks(self) -> None:
        plan = live.make_plan("260907ABCD")
        public = FakePublicClient()
        session = preview.AnonymousSession("offline", USER_A)
        general = public.get_daily_leaderboard(session)
        self.assertEqual(1, live.validate_general_daily_leaderboard(general, plan))
        general_unchanged = public.get_daily_leaderboard(session, general["snapshot_id"])
        self.assertEqual(0, live.validate_general_daily_leaderboard(general_unchanged, plan))
        live.assert_conditional_daily_snapshot(
            general,
            general_unchanged,
            live.GENERAL_DAILY_BASE_FIELDS,
        )
        arena = public.get_daily_arena_leaderboard(session)
        self.assertEqual(1, live.validate_arena_daily_leaderboard(arena, plan))
        arena_unchanged = public.get_daily_arena_leaderboard(session, arena["snapshot_id"])
        live.assert_conditional_daily_snapshot(
            arena,
            arena_unchanged,
            live.ARENA_DAILY_BASE_FIELDS,
        )
        leaked = dict(general)
        leaked["e"] = [{**general["e"][0], "n": plan.requester.display_name}]
        with self.assertRaisesRegex(live.LiveSafetyError, "immutable"):
            live.validate_general_daily_leaderboard(leaked, plan)

    def test_journal_and_handoff_are_owner_only_and_never_contain_management_secrets(self) -> None:
        plan = live.make_plan("260907ABCD")
        captured = [live.CapturedIdentity(plan.requester, USER_A)]
        with tempfile.TemporaryDirectory() as directory:
            journal = live.CleanupJournal(Path(directory) / "journal.json")
            handoff = live.EmulatorCredentialHandoff(Path(directory) / "handoff.json")
            started = datetime.now(tz=timezone.utc).isoformat()
            journal.create(plan, started)
            journal.update(plan, started, captured)
            handoff.create(plan, "requester-password-kept-in-file")
            self.assertEqual(0, journal.path.stat().st_mode & 0o077)
            self.assertEqual(0, handoff.path.stat().st_mode & 0o077)
            restored_plan, restored_started, restored = journal.load()
            self.assertEqual(plan, restored_plan)
            self.assertEqual(started, restored_started)
            self.assertEqual(captured, restored)
            handoff_payload = json.loads(handoff.path.read_text())
            self.assertEqual(plan.run_tag, handoff_payload["run_tag"])
            self.assertEqual(plan.requester.character_id, handoff_payload["requester"]["character_id"])
            self.assertIs(type(handoff_payload["requester"]["slot_id"]), int)
            self.assertEqual(1, handoff_payload["requester"]["slot_id"])
            self.assertNotIn("user_id", handoff_payload["requester"])
            self.assertNotIn(config().service_role_key, handoff.path.read_text())

    def test_all_in_one_flow_cleans_only_parameterized_captured_ids(self) -> None:
        plan = live.make_plan("260907ABCD")
        public = FakePublicClient()
        database = FakeManagementClient()
        with tempfile.TemporaryDirectory() as directory:
            journal = live.CleanupJournal(Path(directory) / "journal.json")
            handoff = live.EmulatorCredentialHandoff(Path(directory) / "handoff.json")
            summary = live.run_live(
                config(),
                plan=plan,
                journal=journal,
                handoff=handoff,
                public_client=public,
                management_client=database,
                sleep_fn=lambda _seconds: None,
            )
            self.assertEqual(2, summary.deleted_auth_users)
            self.assertEqual(1, summary.general_daily_entries)
            self.assertEqual(1, summary.arena_daily_entries)
            self.assertFalse(journal.path.exists())
            self.assertFalse(handoff.path.exists())
        self.assertEqual(2, len(public.profiles))
        self.assertEqual([None, "utc:1788739200000"], public.general_calls)
        self.assertEqual(2, len(public.arena_syncs))
        self.assertEqual([None, "arena:s1:r1:utc:1788739200000"], public.arena_calls)
        self.assertNotIn(USER_A, database.cleanup_sql)
        self.assertNotIn(USER_B, database.cleanup_sql)
        self.assertIn(USER_A, database.cleanup_parameters)
        self.assertIn(USER_B, database.cleanup_parameters)
        self.assertIn("delete from auth.users", database.cleanup_sql.lower())
        rendered_cleanup = live.render_constrained_sql(
            database.cleanup_sql,
            database.cleanup_parameters,
        )
        live.validate_rendered_sql(rendered_cleanup, read_only=False)

    def test_cli_owner_wrapper_uses_fixed_flags_0600_sql_and_suppresses_raw_output(self) -> None:
        observed: dict[str, object] = {}

        def runner(command, **kwargs):  # noqa: ANN001
            sql_path = Path(command[command.index("--file") + 1])
            observed["command"] = list(command)
            observed["mode"] = sql_path.stat().st_mode & 0o777
            observed["sql"] = sql_path.read_text()
            observed["cwd"] = kwargs["cwd"]
            self.assertNotIn(live.SERVICE_ROLE_KEY_ENV, kwargs["env"])
            return subprocess.CompletedProcess(
                command,
                0,
                stdout='[{"audit":{"n":1}}]',
                stderr="suppressed",
            )

        with tempfile.TemporaryDirectory() as directory:
            client = live.ManagementDatabaseClient(
                Path("/reviewed/supabase"),
                Path(directory),
                runner=runner,
                validate_paths=False,
            )
            rows = client.query(
                "select pg_catalog.jsonb_build_object('n',$1::integer) as audit",
                [1],
                read_only=True,
            )
        self.assertEqual([{"audit": {"n": 1}}], rows)
        self.assertEqual(0o600, observed["mode"])
        command = observed["command"]
        self.assertIn("--linked", command)
        self.assertIn("--output-format", command)
        self.assertIn("json", command)
        self.assertIn("--agent", command)
        self.assertIn("no", command)
        self.assertNotIn("--include-all", command)
        sql_path = Path(command[command.index("--file") + 1])
        self.assertFalse(sql_path.exists())

    def test_owner_audits_cover_006_to_008_mutable_and_immutable_surfaces(self) -> None:
        class RecordingOwner:
            def __init__(self) -> None:
                self.queries: list[str] = []

            def query(self, sql, parameters, *, read_only):  # noqa: ANN001
                self.queries.append(sql)
                if not read_only:
                    raise AssertionError("audit unexpectedly requested a mutation")
                if "information_schema.tables" in sql:
                    return [{"audit": preflight_counts()}]
                return [{"audit": completed_counts()}]

        plan = live.make_plan("260907ABCD")
        owner = RecordingOwner()
        live.preflight_audit(owner, plan)
        live.scoped_audit(
            owner,
            [
                live.CapturedIdentity(plan.requester, USER_A),
                live.CapturedIdentity(plan.opponent, USER_B),
            ],
            "2026-09-07T12:00:00+00:00",
        )
        audited_sql = "\n".join(owner.queries)
        for required in (
            "sync_player_network_profile(jsonb)",
            "get_daily_leaderboard(text)",
            "sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)",
            "get_daily_arena_leaderboard(text)",
            "public.ranking_entries",
            "shared_player_private.sync_limits",
            "ranking_private.daily_ranking_before_images",
            "ranking_private.daily_leaderboard_snapshots",
            "arena_ranking_private.season_standings",
            "arena_ranking_private.season_character_bindings",
            "arena_ranking_private.account_call_limits",
            "arena_ranking_private.entry_sync_limits",
            "arena_ranking_private.daily_arena_before_images",
            "arena_ranking_private.daily_arena_leaderboard_snapshots",
        ):
            self.assertIn(required, audited_sql)
        self.assertIn("select ranking_private.publish_daily_leaderboard()", audited_sql)
        self.assertIn("select arena_ranking_private.publish_daily_arena_leaderboard()", audited_sql)

    def test_owner_sql_renderer_rejects_credentials_and_non_auth_mutations(self) -> None:
        with self.assertRaises(live.LiveSafetyError):
            live.render_constrained_sql("select $1::text", ["sb_secret_forbidden_value"])
        with self.assertRaises(live.LiveSafetyError):
            live.validate_rendered_sql("delete from public.admin_events", read_only=False)

    def test_staged_emulator_flow_retains_then_finalizes_both_protected_files(self) -> None:
        plan = live.make_plan("260907ABCD")
        public = FakePublicClient()
        database = FakeManagementClient()
        with tempfile.TemporaryDirectory() as directory:
            journal = live.CleanupJournal(Path(directory) / "journal.json")
            handoff = live.EmulatorCredentialHandoff(Path(directory) / "handoff.json")
            prepared = live.run_live(
                config(),
                plan=plan,
                journal=journal,
                handoff=handoff,
                retain_for_emulator=True,
                public_client=public,
                management_client=database,
                sleep_fn=lambda _seconds: None,
            )
            self.assertEqual(0, prepared.deleted_auth_users)
            self.assertTrue(journal.path.exists())
            self.assertTrue(handoff.path.exists())
            deleted = live.recover_cleanup(
                config(),
                journal=journal,
                handoff=handoff,
                management_client=database,
                require_completed_contract=True,
            )
            self.assertEqual(2, deleted)
            self.assertFalse(journal.path.exists())
            self.assertFalse(handoff.path.exists())

    def test_unexpected_counts_fail_before_cleanup_contract_is_claimed(self) -> None:
        bad = completed_counts()
        bad["admin_events_for_users"] = 1
        with self.assertRaisesRegex(live.LiveSafetyError, "pre-cleanup ownership"):
            live.assert_completed_audit(bad)
        with self.assertRaisesRegex(live.LiveSafetyError, "administrator telemetry"):
            live.assert_cleanup_identity_safe(bad, 2)


if __name__ == "__main__":
    unittest.main()
