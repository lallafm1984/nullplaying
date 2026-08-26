#!/usr/bin/env python3

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from serve import (
    HitSettingsValidationError,
    SpritePathValidationError,
    app_hit_weights,
    load_hit_settings,
    resolve_sprite_path,
    reveal_sprite_in_finder,
    save_hit_setting,
)


class HitSettingsTest(unittest.TestCase):
    def test_save_is_atomic_app_ready_and_preserves_other_skills(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "skill-hit-overrides.json"
            valid_ids = {"warrior_t01_c01", "warrior_t06_c05"}

            _, first = save_hit_setting(
                {
                    "catalogId": "warrior_t01_c01",
                    "hitCount": 2,
                    "hitTimingsMillis": [280, 500],
                },
                path,
                valid_ids,
            )
            save_hit_setting(
                {
                    "catalogId": "warrior_t06_c05",
                    "hitCount": 6,
                    "hitTimingsMillis": [63, 125, 188, 250, 313, 500],
                },
                path,
                valid_ids,
            )

            stored = load_hit_settings(path, valid_ids)
            self.assertEqual(1, stored["schemaVersion"])
            self.assertEqual(2, len(stored["skills"]))
            self.assertEqual([48, 52], first["hitWeights"])
            self.assertEqual([13, 13, 15, 15, 15, 29], stored["skills"]["warrior_t06_c05"]["hitWeights"])
            self.assertFalse(path.with_name(f".{path.name}.tmp").exists())
            self.assertEqual(stored, json.loads(path.read_text(encoding="utf-8")))

    def test_validation_rejects_app_incompatible_values(self) -> None:
        valid_ids = {"warrior_t01_c01"}
        invalid_rows = [
            {"catalogId": "unknown", "hitCount": 1, "hitTimingsMillis": [500]},
            {"catalogId": "warrior_t01_c01", "hitCount": 0, "hitTimingsMillis": []},
            {"catalogId": "warrior_t01_c01", "hitCount": 2, "hitTimingsMillis": [500]},
            {"catalogId": "warrior_t01_c01", "hitCount": 2, "hitTimingsMillis": [500, 500]},
            {"catalogId": "warrior_t01_c01", "hitCount": 1, "hitTimingsMillis": [901]},
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "settings.json"
            for row in invalid_rows:
                with self.subTest(row=row), self.assertRaises(HitSettingsValidationError):
                    save_hit_setting(row, path, valid_ids)

    def test_weights_match_android_distribution_contract(self) -> None:
        for hit_count in range(1, 13):
            with self.subTest(hit_count=hit_count):
                weights = app_hit_weights(hit_count)
                self.assertEqual(hit_count, len(weights))
                self.assertEqual(100, sum(weights))
                self.assertTrue(all(weight > 0 for weight in weights))


class SpriteFinderTest(unittest.TestCase):
    def test_reveal_uses_finder_for_valid_catalog_sprite_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lab = Path(directory)
            sprite = lab / "custom-assets/reviewed/warrior_t01_c01.png"
            sprite.parent.mkdir(parents=True)
            sprite.write_bytes(b"png")
            commands: list[list[str]] = []

            def fake_opener(command: list[str], **_: object) -> None:
                commands.append(command)

            resolved = reveal_sprite_in_finder(
                "warrior_t01_c01",
                "custom-assets/reviewed/warrior_t01_c01.png?v=1",
                {"warrior_t01_c01"},
                lab,
                fake_opener,
            )

            self.assertEqual(sprite.resolve(), resolved)
            self.assertEqual([["open", "-R", str(sprite.resolve())]], commands)

    def test_reveal_rejects_unknown_mismatched_or_escaped_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lab = Path(directory)
            valid = lab / "custom-assets/reviewed/warrior_t01_c01.png"
            valid.parent.mkdir(parents=True)
            valid.write_bytes(b"png")
            invalid_rows = [
                ("unknown", "custom-assets/reviewed/warrior_t01_c01.png"),
                ("warrior_t01_c01", "custom-assets/reviewed/other.png"),
                ("warrior_t01_c01", "../warrior_t01_c01.png"),
                ("warrior_t01_c01", "https://example.com/warrior_t01_c01.png"),
            ]
            for catalog_id, sprite_path in invalid_rows:
                with self.subTest(catalog_id=catalog_id, sprite_path=sprite_path), self.assertRaises(SpritePathValidationError):
                    resolve_sprite_path(catalog_id, sprite_path, {"warrior_t01_c01"}, lab)


if __name__ == "__main__":
    unittest.main()
