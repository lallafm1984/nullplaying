#!/usr/bin/env python3
"""QA-only black-box preview for the shared-player publication and roster RPCs."""

from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
import re
import ssl
import sys
import time
from typing import Any, Mapping
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, HTTPSHandler, ProxyHandler, Request, build_opener
import uuid


PRODUCTION_PROJECT_REF = "rlrmaynzdwulbuvymxfa"
AUTH_SIGNUP_PATH = "/auth/v1/signup"
SYNC_SNAPSHOTS_PATH = "/rest/v1/rpc/sync_public_player_snapshots"
GET_ROSTER_PATH = "/rest/v1/rpc/get_daily_public_player_roster"
ALLOWED_PATHS = frozenset((AUTH_SIGNUP_PATH, SYNC_SNAPSHOTS_PATH, GET_ROSTER_PATH))
MAX_RESPONSE_BYTES = 1_048_576
ROSTER_RETRY_WAIT_SECONDS = 5.5
RULES_VERSION = 1
SNAPSHOT_VERSION = 1
LEVEL = 10
REQUESTER_CHARACTER_ID = "a1100000-0000-4000-8000-000000000001"
OPPONENT_CHARACTER_ID = "b2200000-0000-4000-8000-000000000002"
REQUESTER_NAME = "PreviewAlpha"
OPPONENT_NAME = "PreviewBeta"

SNAPSHOT_FIELDS = frozenset(
    (
        "projection_id",
        "display_name",
        "hero_class",
        "level",
        "combat_power",
        "rules_version",
        "snapshot_version",
        "stats",
        "adventure_trait_ids",
    )
)
UPLOAD_FIELDS = (SNAPSHOT_FIELDS - {"projection_id"}) | {"character_id"}
STAT_FIELDS = frozenset(
    (
        "strength",
        "constitution",
        "dexterity",
        "intelligence",
        "wisdom",
        "charisma",
        "max_health",
        "max_mana",
    )
)
ROSTER_FIELDS = frozenset(
    (
        "roster_id",
        "roster_date_utc",
        "requester_level",
        "rules_version",
        "generated_at",
        "valid_until",
        "server_now",
        "snapshots",
    )
)
HERO_CLASSES = frozenset(("WARRIOR", "ROGUE", "RANGER", "MAGE", "CLERIC", "PALADIN"))
FORBIDDEN_WIRE_KEYS = frozenset(
    (
        "equipment",
        "equipped_items",
        "skills",
        "learned_skills",
        "mastery",
        "mastery_level",
        "usage_count",
        "gold",
        "inventory",
        "items",
        "economy",
        "relationships",
        "relationship_score",
        "contacts",
    )
)


class PreviewError(RuntimeError):
    """Expected configuration, transport, or contract failure without secret material."""


@dataclass(frozen=True, repr=False)
class PreviewConfig:
    base_url: str
    publishable_key: str
    timeout_seconds: float = 20.0


@dataclass(frozen=True, repr=False)
class AnonymousSession:
    access_token: str
    user_id: str


class _RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        raise PreviewError(f"Unexpected redirect from fixed path (HTTP {code})")


class FixedPathSupabaseClient:
    def __init__(self, config: PreviewConfig):
        self._config = config
        self._opener = build_opener(
            ProxyHandler({}),
            HTTPSHandler(context=ssl.create_default_context()),
            _RejectRedirects(),
        )

    def create_anonymous_session(self) -> AnonymousSession:
        response = self._post(AUTH_SIGNUP_PATH, {}, access_token=None)
        token = response.get("access_token")
        user = response.get("user")
        if not isinstance(token, str) or not token or not isinstance(user, dict):
            raise PreviewError("Anonymous signup response is missing its session")
        if not is_canonical_uuid(user.get("id")):
            raise PreviewError("Anonymous signup response has an invalid user identity")
        return AnonymousSession(access_token=token, user_id=str(user["id"]).lower())

    def sync_snapshot(
        self,
        session: AnonymousSession,
        snapshot: Mapping[str, Any],
        *,
        expected_level: int = LEVEL,
    ) -> None:
        validate_upload_snapshot(snapshot, expected_level=expected_level)
        response = self._post(
            SYNC_SNAPSHOTS_PATH,
            {"p_snapshots": [dict(snapshot)]},
            access_token=session.access_token,
        )
        if response.get("rate_limited") is True or response.get("invalid_level") is True:
            raise PreviewError("Snapshot publication was rejected by the QA rate or level guard")
        if response.get("rules_version") != RULES_VERSION or response.get("synced_count") != 1:
            raise PreviewError("Snapshot publication receipt does not match the request")
        if not positive_int(response.get("server_now")):
            raise PreviewError("Snapshot publication receipt is missing server time")

    def get_roster(self, session: AnonymousSession, character_id: str) -> dict[str, Any]:
        if not is_canonical_uuid(character_id):
            raise PreviewError("Roster request character ID is invalid")
        response = self._post(
            GET_ROSTER_PATH,
            {"p_character_id": character_id, "p_rules_version": RULES_VERSION},
            access_token=session.access_token,
        )
        if response.get("rate_limited") is True:
            raise PreviewError("Roster request remained rate limited after the bounded wait")
        return response

    def _url_for(self, path: str) -> str:
        if path not in ALLOWED_PATHS:
            raise PreviewError("Refused non-allowlisted Supabase path")
        return self._config.base_url + path

    def _post(
        self,
        path: str,
        body: Mapping[str, Any],
        access_token: str | None,
    ) -> dict[str, Any]:
        url = self._url_for(path)
        encoded = json.dumps(body, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
        bearer = access_token or self._config.publishable_key
        request = Request(
            url=url,
            data=encoded,
            method="POST",
            headers={
                "apikey": self._config.publishable_key,
                "Authorization": f"Bearer {bearer}",
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "AlarmQuest-SharedPlayer-QA-Preview/1",
            },
        )
        try:
            with self._opener.open(
                request,
                timeout=self._config.timeout_seconds,
            ) as response:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                status = response.status
        except HTTPError as error:
            error_code = "unknown"
            try:
                payload = json.loads(error.read(MAX_RESPONSE_BYTES).decode("utf-8"))
                candidate = payload.get("code") if isinstance(payload, dict) else None
                if isinstance(candidate, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", candidate):
                    error_code = candidate
            except Exception:
                pass
            raise PreviewError(f"Fixed endpoint {path} returned HTTP {error.code} ({error_code})") from None
        except (URLError, TimeoutError, OSError) as error:
            raise PreviewError(f"Fixed endpoint {path} could not be reached ({type(error).__name__})") from None
        if status < 200 or status >= 300:
            raise PreviewError(f"Fixed endpoint {path} returned HTTP {status}")
        if len(raw) > MAX_RESPONSE_BYTES:
            raise PreviewError(f"Fixed endpoint {path} returned an oversized response")
        try:
            decoded = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise PreviewError(f"Fixed endpoint {path} returned invalid JSON") from None
        if not isinstance(decoded, dict):
            raise PreviewError(f"Fixed endpoint {path} returned a non-object payload")
        return decoded


def load_config(environ: Mapping[str, str], require_env: bool = False) -> PreviewConfig | None:
    url = environ.get("ALARMQUEST_QA_SUPABASE_URL", "").strip()
    publishable = environ.get("ALARMQUEST_QA_SUPABASE_PUBLISHABLE_KEY", "").strip()
    legacy_anon = environ.get("ALARMQUEST_QA_SUPABASE_ANON_KEY", "").strip()
    if publishable and legacy_anon and publishable != legacy_anon:
        raise PreviewError("QA publishable and anon key variables disagree")
    key = publishable or legacy_anon
    missing = []
    if not url:
        missing.append("ALARMQUEST_QA_SUPABASE_URL")
    if not key:
        missing.append("ALARMQUEST_QA_SUPABASE_PUBLISHABLE_KEY (or ALARMQUEST_QA_SUPABASE_ANON_KEY)")
    if missing:
        if require_env:
            raise PreviewError("Missing QA environment: " + ", ".join(missing))
        return None
    return PreviewConfig(validate_qa_url(url), validate_publishable_key(key))


def validate_qa_url(value: str) -> str:
    parsed = urlparse(value)
    host = (parsed.hostname or "").lower()
    try:
        port = parsed.port
    except ValueError:
        raise PreviewError("QA Supabase URL has an invalid port") from None
    if parsed.scheme != "https" or parsed.username or parsed.password or port not in (None, 443):
        raise PreviewError("QA Supabase URL must be a credential-free HTTPS origin")
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise PreviewError("QA Supabase URL must not contain a path, query, or fragment")
    if not re.fullmatch(r"[a-z0-9]{20}\.supabase\.co", host):
        raise PreviewError("QA Supabase URL must use an official project-ref host")
    project_ref = host.split(".", 1)[0]
    if project_ref == PRODUCTION_PROJECT_REF:
        raise PreviewError("Refused the production Supabase project")
    return f"https://{host}"


def validate_publishable_key(value: str) -> str:
    if len(value) < 20 or any(character.isspace() for character in value):
        raise PreviewError("QA publishable/anon key format is invalid")
    lowered = value.lower()
    if lowered.startswith("sb_secret_") or "service_role" in lowered:
        raise PreviewError("Secret or service-role credentials are forbidden")
    parts = value.split(".")
    if len(parts) == 3:
        try:
            padded = parts[1] + "=" * (-len(parts[1]) % 4)
            payload = json.loads(base64.urlsafe_b64decode(padded).decode("utf-8"))
        except Exception:
            payload = None
        if isinstance(payload, dict) and payload.get("role") == "service_role":
            raise PreviewError("Service-role JWT credentials are forbidden")
    return value


def preview_snapshot(character_id: str, display_name: str, hero_class: str) -> dict[str, Any]:
    snapshot = {
        "character_id": character_id,
        "display_name": display_name,
        "hero_class": hero_class,
        "level": LEVEL,
        "combat_power": 92,
        "rules_version": RULES_VERSION,
        "snapshot_version": SNAPSHOT_VERSION,
        "stats": {
            "strength": 15,
            "constitution": 15,
            "dexterity": 15,
            "intelligence": 15,
            "wisdom": 15,
            "charisma": 15,
            "max_health": 200,
            "max_mana": 100,
        },
        "adventure_trait_ids": [],
    }
    validate_upload_snapshot(snapshot)
    return snapshot


def validate_upload_snapshot(snapshot: Mapping[str, Any], expected_level: int = LEVEL) -> None:
    if set(snapshot) != UPLOAD_FIELDS:
        raise PreviewError("Outgoing snapshot is not the reviewed minimal wire contract")
    if not is_canonical_uuid(snapshot.get("character_id")):
        raise PreviewError("Outgoing snapshot character ID is invalid")
    if snapshot.get("level") != expected_level or snapshot.get("rules_version") != RULES_VERSION:
        raise PreviewError("Outgoing snapshot level or rules version is invalid")
    if snapshot.get("snapshot_version") != SNAPSHOT_VERSION:
        raise PreviewError("Outgoing snapshot version is invalid")
    if snapshot.get("hero_class") not in HERO_CLASSES:
        raise PreviewError("Outgoing snapshot class is invalid")
    if not isinstance(snapshot.get("display_name"), str) or not snapshot["display_name"]:
        raise PreviewError("Outgoing snapshot display name is invalid")
    stats = snapshot.get("stats")
    if not isinstance(stats, dict) or set(stats) != STAT_FIELDS:
        raise PreviewError("Outgoing snapshot stats are not minimal")
    if snapshot.get("adventure_trait_ids") != []:
        raise PreviewError("Preview snapshots must not carry traits")
    assert_no_forbidden_wire_keys(snapshot)


def validate_roster(
    payload: Mapping[str, Any],
    requester_name: str,
    expected_level: int = LEVEL,
) -> list[dict[str, Any]]:
    if set(payload) != ROSTER_FIELDS:
        raise PreviewError("Roster envelope differs from the reviewed wire contract")
    if not is_canonical_uuid(payload.get("roster_id")):
        raise PreviewError("Roster ID is invalid")
    if payload.get("requester_level") != expected_level or payload.get("rules_version") != RULES_VERSION:
        raise PreviewError("Roster requester level or rules version is invalid")
    generated_at = payload.get("generated_at")
    valid_until = payload.get("valid_until")
    server_now = payload.get("server_now")
    if not all(positive_int(value) for value in (generated_at, valid_until, server_now)):
        raise PreviewError("Roster timestamps are invalid")
    if not generated_at <= server_now < valid_until:
        raise PreviewError("Roster time window is invalid")
    expected_date = datetime.fromtimestamp(server_now / 1000, tz=timezone.utc).date().isoformat()
    if payload.get("roster_date_utc") != expected_date:
        raise PreviewError("Roster UTC date does not match server time")
    snapshots = payload.get("snapshots")
    if not isinstance(snapshots, list) or not snapshots:
        raise PreviewError("PUBLIC_ROSTER has no eligible candidate")
    seen_ids: set[str] = set()
    for row in snapshots:
        if not isinstance(row, dict) or set(row) != SNAPSHOT_FIELDS:
            raise PreviewError("PUBLIC_ROSTER candidate differs from the minimal wire contract")
        projection_id = row.get("projection_id")
        if not is_canonical_uuid(projection_id) or projection_id in seen_ids:
            raise PreviewError("PUBLIC_ROSTER candidate identity is invalid or duplicated")
        seen_ids.add(projection_id)
        if row.get("display_name") == requester_name:
            raise PreviewError("PUBLIC_ROSTER contains the requesting anonymous user")
        if row.get("level") not in range(expected_level - 1, expected_level + 2):
            raise PreviewError("PUBLIC_ROSTER candidate is outside the +/-1 level band")
        if row.get("hero_class") not in HERO_CLASSES:
            raise PreviewError("PUBLIC_ROSTER candidate class is invalid")
        if row.get("rules_version") != RULES_VERSION or row.get("snapshot_version") != SNAPSHOT_VERSION:
            raise PreviewError("PUBLIC_ROSTER candidate version is invalid")
        if not positive_int(row.get("combat_power")):
            raise PreviewError("PUBLIC_ROSTER candidate power is invalid")
        stats = row.get("stats")
        if not isinstance(stats, dict) or set(stats) != STAT_FIELDS:
            raise PreviewError("PUBLIC_ROSTER candidate stats are not minimal")
        traits = row.get("adventure_trait_ids")
        if not isinstance(traits, list) or not all(isinstance(value, str) for value in traits):
            raise PreviewError("PUBLIC_ROSTER candidate trait IDs are invalid")
        assert_no_forbidden_wire_keys(row)
    return snapshots


def assert_frozen_roster(first: Mapping[str, Any], second: Mapping[str, Any]) -> None:
    stable_fields = ROSTER_FIELDS - {"server_now"}
    if any(first.get(field) != second.get(field) for field in stable_fields):
        raise PreviewError("Same-day roster changed between allowed calls")
    if not positive_int(second.get("server_now")) or second["server_now"] < first["server_now"]:
        raise PreviewError("Second roster response has invalid server time")


def assert_no_forbidden_wire_keys(value: Any) -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            if str(key).lower() in FORBIDDEN_WIRE_KEYS:
                raise PreviewError(f"Forbidden public wire field detected: {key}")
            assert_no_forbidden_wire_keys(child)
    elif isinstance(value, list):
        for child in value:
            assert_no_forbidden_wire_keys(child)


def is_canonical_uuid(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)).lower() == value.lower()
    except (ValueError, AttributeError):
        return False


def positive_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def run_online(config: PreviewConfig) -> None:
    client = FixedPathSupabaseClient(config)
    requester_session = client.create_anonymous_session()
    opponent_session = client.create_anonymous_session()
    client.sync_snapshot(
        requester_session,
        preview_snapshot(REQUESTER_CHARACTER_ID, REQUESTER_NAME, "WARRIOR"),
    )
    client.sync_snapshot(
        opponent_session,
        preview_snapshot(OPPONENT_CHARACTER_ID, OPPONENT_NAME, "RANGER"),
    )
    first = client.get_roster(requester_session, REQUESTER_CHARACTER_ID)
    first_candidates = validate_roster(first, REQUESTER_NAME)
    if not any(candidate.get("display_name") == OPPONENT_NAME for candidate in first_candidates):
        raise PreviewError("The second QA anonymous user was not present in PUBLIC_ROSTER")
    time.sleep(ROSTER_RETRY_WAIT_SECONDS)
    second = client.get_roster(requester_session, REQUESTER_CHARACTER_ID)
    validate_roster(second, REQUESTER_NAME)
    assert_frozen_roster(first, second)
    print("PASS anonymous QA sessions created: 2")
    print("PASS minimal Lv10 snapshots published: 2")
    print(f"PASS PUBLIC_ROSTER candidates: {len(first_candidates)}")
    print("PASS self-exclusion, +/-1 level band, privacy fields, and same-day freeze")


def run_offline_self_test() -> None:
    safe_ref = "abcdefghijklmnopqrst"
    config = PreviewConfig(
        validate_qa_url(f"https://{safe_ref}.supabase.co"),
        validate_publishable_key("sb_publishable_offline_self_test_value"),
    )
    client = FixedPathSupabaseClient(config)
    assert client._url_for(SYNC_SNAPSHOTS_PATH).endswith(SYNC_SNAPSHOTS_PATH)
    try:
        client._url_for("/rest/v1/anything_else")
        raise AssertionError("unknown path should fail")
    except PreviewError:
        pass
    try:
        validate_qa_url(f"https://{PRODUCTION_PROJECT_REF}.supabase.co")
        raise AssertionError("production project should fail")
    except PreviewError:
        pass
    requester = preview_snapshot(REQUESTER_CHARACTER_ID, REQUESTER_NAME, "WARRIOR")
    opponent = preview_snapshot(OPPONENT_CHARACTER_ID, OPPONENT_NAME, "RANGER")
    row = dict(opponent)
    row["projection_id"] = "c3300000-0000-4000-8000-000000000003"
    del row["character_id"]
    envelope = {
        "roster_id": "d4400000-0000-4000-8000-000000000004",
        "roster_date_utc": "2026-09-07",
        "requester_level": LEVEL,
        "rules_version": RULES_VERSION,
        "generated_at": 1_788_739_200_000,
        "valid_until": 1_788_825_600_000,
        "server_now": 1_788_739_201_000,
        "snapshots": [row],
    }
    candidates = validate_roster(envelope, requester["display_name"])
    repeated = dict(envelope, server_now=envelope["server_now"] + 6_000)
    assert_frozen_roster(envelope, repeated)
    assert len(candidates) == 1
    assert load_config({}, require_env=False) is None
    print("PASS offline shared-player preview self-test (network not used)")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="run offline contract checks only")
    parser.add_argument(
        "--require-env",
        action="store_true",
        help="exit with an error instead of SKIP when QA environment variables are missing",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    try:
        if args.self_test:
            run_offline_self_test()
            return 0
        config = load_config(os.environ, require_env=args.require_env)
        if config is None:
            print("SKIP QA Supabase URL/key environment is not configured")
            return 0
        run_online(config)
        return 0
    except PreviewError as error:
        print(f"FAIL {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
