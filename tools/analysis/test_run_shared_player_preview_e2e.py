#!/usr/bin/env python3
"""Offline tests for run_shared_player_preview_e2e.py; never opens a network connection."""

import unittest

import run_shared_player_preview_e2e as preview


class SharedPlayerPreviewE2ETest(unittest.TestCase):
    def test_missing_environment_skips_or_fails_clearly(self) -> None:
        self.assertIsNone(preview.load_config({}, require_env=False))
        with self.assertRaisesRegex(preview.PreviewError, "Missing QA environment"):
            preview.load_config({}, require_env=True)

    def test_only_nonproduction_official_qa_origin_is_accepted(self) -> None:
        safe = "https://abcdefghijklmnopqrst.supabase.co/"
        self.assertEqual(safe.rstrip("/"), preview.validate_qa_url(safe))
        rejected = (
            f"https://{preview.PRODUCTION_PROJECT_REF}.supabase.co",
            "http://abcdefghijklmnopqrst.supabase.co",
            "https://abcdefghijklmnopqrst.supabase.co/rest/v1",
            "https://abcdefghijklmnopqrst.supabase.co:notaport",
            "https://example.com",
        )
        for value in rejected:
            with self.subTest(value=value), self.assertRaises(preview.PreviewError):
                preview.validate_qa_url(value)

    def test_secret_and_service_role_keys_are_rejected(self) -> None:
        self.assertEqual(
            "sb_publishable_offline_test_value",
            preview.validate_publishable_key("sb_publishable_offline_test_value"),
        )
        with self.assertRaises(preview.PreviewError):
            preview.validate_publishable_key("sb_secret_offline_test_value")

    def test_client_builds_only_three_fixed_paths(self) -> None:
        config = preview.PreviewConfig(
            "https://abcdefghijklmnopqrst.supabase.co",
            "sb_publishable_offline_test_value",
        )
        client = preview.FixedPathSupabaseClient(config)
        for path in preview.ALLOWED_PATHS:
            self.assertEqual(config.base_url + path, client._url_for(path))
        with self.assertRaises(preview.PreviewError):
            client._url_for("/rest/v1/private_table")

    def test_outgoing_preview_is_minimal_and_forbidden_fields_fail(self) -> None:
        snapshot = preview.preview_snapshot(
            preview.REQUESTER_CHARACTER_ID,
            preview.REQUESTER_NAME,
            "WARRIOR",
        )
        self.assertEqual(preview.UPLOAD_FIELDS, set(snapshot))
        with self.assertRaises(preview.PreviewError):
            preview.validate_upload_snapshot(dict(snapshot, equipment=[]))

    def test_roster_requires_public_candidate_privacy_and_frozen_replay(self) -> None:
        row = preview.preview_snapshot(
            preview.OPPONENT_CHARACTER_ID,
            preview.OPPONENT_NAME,
            "RANGER",
        )
        row["projection_id"] = "c3300000-0000-4000-8000-000000000003"
        del row["character_id"]
        first = {
            "roster_id": "d4400000-0000-4000-8000-000000000004",
            "roster_date_utc": "2026-09-07",
            "requester_level": preview.LEVEL,
            "rules_version": preview.RULES_VERSION,
            "generated_at": 1_788_739_200_000,
            "valid_until": 1_788_825_600_000,
            "server_now": 1_788_739_201_000,
            "snapshots": [row],
        }
        self.assertEqual(1, len(preview.validate_roster(first, preview.REQUESTER_NAME)))
        second = dict(first, server_now=first["server_now"] + 6_000)
        preview.assert_frozen_roster(first, second)
        changed = dict(second, roster_id="e5500000-0000-4000-8000-000000000005")
        with self.assertRaises(preview.PreviewError):
            preview.assert_frozen_roster(first, changed)
        self_row = dict(row, display_name=preview.REQUESTER_NAME)
        with self.assertRaises(preview.PreviewError):
            preview.validate_roster(dict(first, snapshots=[self_row]), preview.REQUESTER_NAME)


if __name__ == "__main__":
    unittest.main()
