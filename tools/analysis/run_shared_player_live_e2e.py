#!/usr/bin/env python3
"""Live-safe shared-player RPC E2E with exact-scope cleanup.

This script intentionally targets the AlarmQuest production project only. It is inert unless the
operator supplies the exact project acknowledgement, a publishable key, and an Auth Admin secret.
Every created identity is journaled locally; all-in-one and finalization modes delete it after use.
Owner audits prove identity before deletion and prove that no run-tagged data remains afterward.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import subprocess
import sys
import tempfile
import time
from typing import Any, Callable, Mapping, Sequence
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, HTTPSHandler, ProxyHandler, Request, build_opener

import run_shared_player_preview_e2e as preview


PRODUCTION_PROJECT_REF = preview.PRODUCTION_PROJECT_REF
PRODUCTION_BASE_URL = f"https://{PRODUCTION_PROJECT_REF}.supabase.co"
LIVE_ACK_ENV = "ALARMQUEST_LIVE_QA_ACK"
PUBLISHABLE_KEY_ENV = "ALARMQUEST_LIVE_QA_PUBLISHABLE_KEY"
SERVICE_ROLE_KEY_ENV = "ALARMQUEST_LIVE_QA_SERVICE_ROLE_KEY"
RUN_TAG_ENV = "ALARMQUEST_LIVE_QA_RUN_TAG"
SUPABASE_CLI_PATH = Path("/Users/lim/Desktop/프로젝트/AlarmQuest/node_modules/.bin/supabase")
LINKED_CLI_WORKDIR = Path("/tmp/aq-shared-player-dryrun-20260907")
LIVE_LEVEL = 10_000
REQUESTER_CHARACTER_ID = "a1100000-0000-4000-8000-000000000001"
OPPONENT_CHARACTER_ID = "b2200000-0000-4000-8000-000000000002"
MAX_MANAGEMENT_RESPONSE_BYTES = 262_144
RUN_TAG_PATTERN = re.compile(r"[A-Z0-9]{8,14}")
UTC_BOUNDARY_MARGIN_SECONDS = 30 * 60
EXPECTED_SHARED_PRIVATE_TABLES = 5
EXPECTED_RANKING_PRIVATE_TABLES = 4
EXPECTED_ARENA_PRIVATE_TABLES = 8
ADMIN_CREATE_USER_PATH = "/auth/v1/admin/users"
PASSWORD_SIGN_IN_PATH = "/auth/v1/token?grant_type=password"
SYNC_PROFILE_PATH = "/rest/v1/rpc/sync_player_network_profile"
GET_ROSTER_PATH = "/rest/v1/rpc/get_daily_public_player_roster"
GET_DAILY_LEADERBOARD_PATH = "/rest/v1/rpc/get_daily_leaderboard"
SYNC_ARENA_RANKING_PATH = "/rest/v1/rpc/sync_arena_ranking_entry"
GET_DAILY_ARENA_LEADERBOARD_PATH = "/rest/v1/rpc/get_daily_arena_leaderboard"
ALLOWED_RPC_PATHS = frozenset((
    SYNC_PROFILE_PATH,
    GET_ROSTER_PATH,
    GET_DAILY_LEADERBOARD_PATH,
    SYNC_ARENA_RANKING_PATH,
    GET_DAILY_ARENA_LEADERBOARD_PATH,
))
RPC_RESPONSE_LIMITS = {
    SYNC_PROFILE_PATH: 32 * 1024,
    GET_ROSTER_PATH: 32 * 1024,
    GET_DAILY_LEADERBOARD_PATH: 512 * 1024,
    SYNC_ARENA_RANKING_PATH: 32 * 1024,
    GET_DAILY_ARENA_LEADERBOARD_PATH: 512 * 1024,
}
PROFILE_FIELDS = preview.UPLOAD_FIELDS | {"slot_id"}
GENERAL_DAILY_BASE_FIELDS = frozenset((
    "snapshot_id", "settled_at", "next_settlement_at", "generated_at", "server_now",
    "is_bootstrap", "unchanged", "t",
))
GENERAL_DAILY_ROW_FIELDS = frozenset(("r", "i", "c", "n", "h", "l", "p", "a"))
ARENA_DAILY_BASE_FIELDS = GENERAL_DAILY_BASE_FIELDS | {"season_id", "rules_version"}
ARENA_DAILY_ROW_FIELDS = frozenset((
    "r", "i", "u", "c", "n", "h", "l", "p", "b", "w", "x", "d", "a",
))
DEFAULT_JOURNAL_PATH = Path(tempfile.gettempdir()) / (
    f"alarmquest-live-shared-player-e2e-{PRODUCTION_PROJECT_REF}.json"
)
DEFAULT_HANDOFF_PATH = Path("/tmp/arena_live_qa_handoff.json")


class LiveSafetyError(RuntimeError):
    """A deliberately non-sensitive production QA safety failure."""


@dataclass(frozen=True, repr=False)
class LiveConfig:
    public: preview.PreviewConfig
    service_role_key: str
    supabase_cli: Path
    linked_cli_workdir: Path


@dataclass(frozen=True)
class PlannedIdentity:
    role: str
    character_id: str
    display_name: str
    hero_class: str
    email: str


@dataclass(frozen=True)
class CapturedIdentity:
    planned: PlannedIdentity
    user_id: str


@dataclass(frozen=True)
class LivePlan:
    run_tag: str
    requester: PlannedIdentity
    opponent: PlannedIdentity

    @property
    def identities(self) -> tuple[PlannedIdentity, PlannedIdentity]:
        return (self.requester, self.opponent)


@dataclass(frozen=True)
class LiveRunSummary:
    run_tag: str
    public_candidates: int
    general_daily_entries: int
    arena_daily_entries: int
    deleted_auth_users: int


class _RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        raise LiveSafetyError(f"Management API redirected unexpectedly (HTTP {code})")


class LiveSupabaseClient:
    """Fixed production Auth paths plus the five reviewed 006-008 public RPCs."""

    def __init__(self, config: LiveConfig):
        self._config = config
        self._opener = build_opener(
            ProxyHandler({}),
            HTTPSHandler(context=ssl.create_default_context()),
            _RejectRedirects(),
        )

    def create_qa_user(self, identity: PlannedIdentity, password: str, run_tag: str) -> str:
        response = self._post_auth(
            ADMIN_CREATE_USER_PATH,
            {
                "email": identity.email,
                "password": password,
                "email_confirm": True,
                "user_metadata": {
                    "alarmquest_live_qa": True,
                    "run_tag": run_tag,
                    "qa_role": identity.role,
                },
            },
            api_key=self._config.service_role_key,
            bearer=self._config.service_role_key,
        )
        candidate = response.get("id")
        if not preview.is_canonical_uuid(candidate):
            user = response.get("user")
            candidate = user.get("id") if isinstance(user, Mapping) else None
        if not preview.is_canonical_uuid(candidate):
            raise LiveSafetyError("Auth Admin create-user response omitted its identity")
        return str(candidate).lower()

    def sign_in(self, identity: PlannedIdentity, password: str, expected_user_id: str) -> preview.AnonymousSession:
        response = self._post_auth(
            PASSWORD_SIGN_IN_PATH,
            {"email": identity.email, "password": password},
            api_key=self._config.public.publishable_key,
            bearer=self._config.public.publishable_key,
        )
        token = response.get("access_token")
        user = response.get("user")
        user_id = user.get("id") if isinstance(user, Mapping) else None
        if not isinstance(token, str) or not token or not preview.is_canonical_uuid(user_id):
            raise LiveSafetyError("Password sign-in response omitted its session")
        if str(user_id).lower() != expected_user_id:
            raise LiveSafetyError("Password sign-in returned a different Auth identity")
        return preview.AnonymousSession(access_token=token, user_id=expected_user_id)

    def sync_profile(
        self,
        session: preview.AnonymousSession,
        profile: Mapping[str, Any],
    ) -> dict[str, Any]:
        validate_live_profile(profile)
        response = self._post_rpc(
            SYNC_PROFILE_PATH,
            {"p_characters": [dict(profile)]},
            session,
        )
        validate_profile_receipt(response)
        return response

    def get_roster(self, session: preview.AnonymousSession, character_id: str) -> dict[str, Any]:
        if not preview.is_canonical_uuid(character_id):
            raise LiveSafetyError("Roster request character ID is invalid")
        return self._post_rpc(
            GET_ROSTER_PATH,
            {"p_character_id": character_id, "p_rules_version": preview.RULES_VERSION},
            session,
        )

    def get_daily_leaderboard(
        self,
        session: preview.AnonymousSession,
        known_snapshot_id: str | None = None,
    ) -> dict[str, Any]:
        validate_known_snapshot_id(known_snapshot_id)
        return self._post_rpc(
            GET_DAILY_LEADERBOARD_PATH,
            {"p_known_snapshot_id": known_snapshot_id},
            session,
        )

    def sync_arena_ranking(
        self,
        session: preview.AnonymousSession,
        character_id: str,
        standing: Mapping[str, int],
    ) -> dict[str, Any]:
        validate_arena_standing(character_id, standing)
        response = self._post_rpc(
            SYNC_ARENA_RANKING_PATH,
            {
                "p_character_id": character_id,
                "p_score": standing["score"],
                "p_completed_battles": standing["completed_battles"],
                "p_wins": standing["wins"],
                "p_losses": standing["losses"],
                "p_draws": standing["draws"],
                "p_rules_version": preview.RULES_VERSION,
            },
            session,
        )
        validate_arena_sync_receipt(response)
        return response

    def get_daily_arena_leaderboard(
        self,
        session: preview.AnonymousSession,
        known_snapshot_id: str | None = None,
    ) -> dict[str, Any]:
        validate_known_snapshot_id(known_snapshot_id)
        return self._post_rpc(
            GET_DAILY_ARENA_LEADERBOARD_PATH,
            {"p_known_snapshot_id": known_snapshot_id},
            session,
        )

    def _rpc_url_for(self, path: str) -> str:
        if path not in ALLOWED_RPC_PATHS:
            raise LiveSafetyError("Refused a non-allowlisted production RPC path")
        return PRODUCTION_BASE_URL + path

    def _post_rpc(
        self,
        path: str,
        body: Mapping[str, Any],
        session: preview.AnonymousSession,
    ) -> dict[str, Any]:
        url = self._rpc_url_for(path)
        encoded = json.dumps(body, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
        request = Request(
            url=url,
            data=encoded,
            method="POST",
            headers={
                "apikey": self._config.public.publishable_key,
                "Authorization": f"Bearer {session.access_token}",
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "AlarmQuest-Live-Player-Network-QA/2",
            },
        )
        maximum = RPC_RESPONSE_LIMITS[path]
        try:
            with self._opener.open(request, timeout=self._config.public.timeout_seconds) as response:
                raw = response.read(maximum + 1)
                status = response.status
        except HTTPError as error:
            error_code = "unknown"
            try:
                payload = json.loads(error.read(min(maximum, 32 * 1024)).decode("utf-8"))
                candidate = payload.get("code") if isinstance(payload, Mapping) else None
                if isinstance(candidate, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", candidate):
                    error_code = candidate
            except Exception:
                pass
            raise LiveSafetyError(
                f"Fixed production RPC {path} returned HTTP {error.code} ({error_code})"
            ) from None
        except (URLError, TimeoutError, OSError) as error:
            raise LiveSafetyError(
                f"Fixed production RPC {path} was unreachable ({type(error).__name__})"
            ) from None
        if status < 200 or status >= 300:
            raise LiveSafetyError(f"Fixed production RPC {path} returned HTTP {status}")
        if len(raw) > maximum:
            raise LiveSafetyError(f"Fixed production RPC {path} returned an oversized response")
        try:
            decoded = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise LiveSafetyError(f"Fixed production RPC {path} returned invalid JSON") from None
        if not isinstance(decoded, dict):
            raise LiveSafetyError(f"Fixed production RPC {path} returned a non-object payload")
        return decoded

    def _post_auth(
        self,
        path: str,
        body: Mapping[str, Any],
        *,
        api_key: str,
        bearer: str,
    ) -> dict[str, Any]:
        if path not in (ADMIN_CREATE_USER_PATH, PASSWORD_SIGN_IN_PATH):
            raise LiveSafetyError("Refused a non-allowlisted production Auth path")
        encoded = json.dumps(body, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
        request = Request(
            PRODUCTION_BASE_URL + path,
            data=encoded,
            method="POST",
            headers={
                "apikey": api_key,
                "Authorization": f"Bearer {bearer}",
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "AlarmQuest-Live-SharedPlayer-QA/1",
            },
        )
        try:
            with self._opener.open(request, timeout=self._config.public.timeout_seconds) as response:
                raw = response.read(preview.MAX_RESPONSE_BYTES + 1)
                status = response.status
        except HTTPError as error:
            raise LiveSafetyError(
                f"Fixed production Auth endpoint returned HTTP {error.code}; no body was shown"
            ) from None
        except (URLError, TimeoutError, OSError) as error:
            raise LiveSafetyError(
                f"Fixed production Auth endpoint was unreachable ({type(error).__name__})"
            ) from None
        if status < 200 or status >= 300:
            raise LiveSafetyError(f"Fixed production Auth endpoint returned HTTP {status}")
        if len(raw) > preview.MAX_RESPONSE_BYTES:
            raise LiveSafetyError("Fixed production Auth endpoint returned an oversized response")
        try:
            decoded = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise LiveSafetyError("Fixed production Auth endpoint returned invalid JSON") from None
        if not isinstance(decoded, dict):
            raise LiveSafetyError("Fixed production Auth endpoint returned a non-object payload")
        return decoded


class ManagementDatabaseClient:
    """Fixed linked Supabase CLI owner-query wrapper with ephemeral 0600 SQL files."""

    def __init__(
        self,
        cli_path: Path,
        linked_workdir: Path,
        *,
        runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
        validate_paths: bool = True,
    ):
        self._cli_path = cli_path
        self._linked_workdir = linked_workdir
        self._runner = runner
        if validate_paths:
            validate_linked_cli(cli_path, linked_workdir)

    def query(
        self,
        sql: str,
        parameters: Sequence[Any],
        *,
        read_only: bool,
    ) -> list[Mapping[str, Any]]:
        rendered = render_constrained_sql(sql, parameters)
        validate_rendered_sql(rendered, read_only=read_only)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix="alarmquest-live-qa-",
            suffix=".sql",
            dir=tempfile.gettempdir(),
            text=True,
        )
        temporary = Path(temporary_name)
        try:
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                handle.write(rendered)
                if not rendered.endswith("\n"):
                    handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            command = [
                str(self._cli_path),
                "db",
                "query",
                "--linked",
                "--file",
                str(temporary),
                "--output-format",
                "json",
                "--agent",
                "no",
            ]
            environment = _safe_cli_environment(os.environ)
            completed = self._runner(
                command,
                cwd=self._linked_workdir,
                env=environment,
                capture_output=True,
                text=True,
                timeout=45,
                check=False,
            )
        except subprocess.TimeoutExpired:
            raise LiveSafetyError("Linked Supabase owner query timed out; output was suppressed") from None
        except OSError as error:
            raise LiveSafetyError(
                f"Linked Supabase owner query could not start ({type(error).__name__})"
            ) from None
        finally:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass
        if completed.returncode != 0:
            raise LiveSafetyError(
                f"Linked Supabase owner query failed with exit {completed.returncode}; output was suppressed"
            )
        if len(completed.stdout.encode("utf-8")) > MAX_MANAGEMENT_RESPONSE_BYTES:
            raise LiveSafetyError("Linked Supabase owner query returned oversized output")
        try:
            decoded = json.loads(completed.stdout)
        except json.JSONDecodeError:
            raise LiveSafetyError("Linked Supabase owner query returned invalid JSON") from None
        rows = _extract_management_rows(decoded)
        if not all(isinstance(row, Mapping) for row in rows):
            raise LiveSafetyError("Linked Supabase owner query returned invalid rows")
        return rows


class CleanupJournal:
    """Crash-recovery journal. The file is always owner-readable only and never printed."""

    def __init__(self, path: Path = DEFAULT_JOURNAL_PATH):
        self.path = path

    def create(self, plan: LivePlan, started_at: str) -> None:
        payload = _journal_payload(plan, started_at, ())
        try:
            descriptor = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            raise LiveSafetyError(
                "A pending live-QA cleanup journal exists; run --recover-cleanup before a new test"
            ) from None
        try:
            encoded = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
            os.write(descriptor, encoded)
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    def update(
        self,
        plan: LivePlan,
        started_at: str,
        captured: Sequence[CapturedIdentity],
    ) -> None:
        payload = _journal_payload(plan, started_at, captured)
        temporary = self.path.with_name(self.path.name + ".next")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            encoded = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
            os.write(descriptor, encoded)
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        os.replace(temporary, self.path)
        os.chmod(self.path, 0o600)

    def load(self) -> tuple[LivePlan, str, list[CapturedIdentity]]:
        try:
            stat = self.path.stat()
            if stat.st_mode & 0o077:
                raise LiveSafetyError("Pending live-QA cleanup journal permissions are unsafe")
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except LiveSafetyError:
            raise
        except Exception:
            raise LiveSafetyError("Pending live-QA cleanup journal is unreadable") from None
        return _parse_journal_payload(payload)

    def remove(self) -> None:
        for candidate in (self.path, self.path.with_name(self.path.name + ".next")):
            try:
                candidate.unlink()
            except FileNotFoundError:
                pass


class EmulatorCredentialHandoff:
    """One requester credential for ADB injection; never logs or embeds its content."""

    def __init__(self, path: Path = DEFAULT_HANDOFF_PATH):
        self.path = path

    def create(self, plan: LivePlan, requester_password: str) -> None:
        requester_snapshot = build_live_snapshot(plan.requester)
        payload = {
            "version": 1,
            "project_ref": PRODUCTION_PROJECT_REF,
            "run_tag": plan.run_tag,
            "requester": {
                "email": plan.requester.email,
                "password": requester_password,
                **requester_snapshot,
            },
        }
        try:
            descriptor = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            raise LiveSafetyError("A pending emulator credential handoff already exists") from None
        try:
            encoded = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
            os.write(descriptor, encoded)
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    def remove(self) -> None:
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass


def load_live_config(environ: Mapping[str, str]) -> LiveConfig:
    acknowledgement = environ.get(LIVE_ACK_ENV, "").strip()
    if acknowledgement != PRODUCTION_PROJECT_REF:
        raise LiveSafetyError(
            f"Live QA is inert unless {LIVE_ACK_ENV} exactly equals the production project ref"
        )
    publishable = environ.get(PUBLISHABLE_KEY_ENV, "").strip()
    if not publishable:
        raise LiveSafetyError(f"Missing live QA environment: {PUBLISHABLE_KEY_ENV}")
    service_role = environ.get(SERVICE_ROLE_KEY_ENV, "").strip()
    if not service_role:
        raise LiveSafetyError(f"Missing live QA environment: {SERVICE_ROLE_KEY_ENV}")
    publishable = preview.validate_publishable_key(publishable)
    service_role = validate_service_role_key(service_role)
    if secrets.compare_digest(publishable, service_role):
        raise LiveSafetyError("Live QA credentials must be distinct")
    validate_linked_cli(SUPABASE_CLI_PATH, LINKED_CLI_WORKDIR)
    return LiveConfig(
        public=preview.PreviewConfig(PRODUCTION_BASE_URL, publishable),
        service_role_key=service_role,
        supabase_cli=SUPABASE_CLI_PATH,
        linked_cli_workdir=LINKED_CLI_WORKDIR,
    )


def validate_service_role_key(value: str) -> str:
    if len(value) < 20 or any(character.isspace() for character in value):
        raise LiveSafetyError("Supabase secret/service-role key format is invalid")
    if value.startswith("sb_secret_"):
        return value
    parts = value.split(".")
    if len(parts) == 3:
        try:
            import base64

            padded = parts[1] + "=" * (-len(parts[1]) % 4)
            payload = json.loads(base64.urlsafe_b64decode(padded).decode("utf-8"))
        except Exception:
            payload = None
        if isinstance(payload, Mapping) and payload.get("role") == "service_role":
            return value
    raise LiveSafetyError("A Supabase secret/service-role key is required for Auth Admin creation")


def make_run_tag(now: datetime | None = None, random_suffix: str | None = None) -> str:
    instant = now or datetime.now(tz=timezone.utc)
    suffix = (random_suffix or secrets.token_hex(2)).upper()
    return validate_run_tag(instant.strftime("%y%m%d%H%M") + suffix)


def validate_run_tag(value: str) -> str:
    normalized = value.strip().upper()
    if not RUN_TAG_PATTERN.fullmatch(normalized):
        raise LiveSafetyError("Live QA run tag must contain 8-14 uppercase letters or digits")
    return normalized


def make_plan(run_tag: str) -> LivePlan:
    tag = validate_run_tag(run_tag)

    def identity(role: str, prefix: str, hero_class: str) -> PlannedIdentity:
        character_id = (
            REQUESTER_CHARACTER_ID if role == "requester" else OPPONENT_CHARACTER_ID
        )
        return PlannedIdentity(
            role=role,
            character_id=character_id,
            display_name=f"AQ-{prefix}-{tag}",
            hero_class=hero_class,
            email=f"aq-live-{tag.lower()}-{prefix.lower()}@example.invalid",
        )

    requester = identity("requester", "A", "WARRIOR")
    opponent = identity("opponent", "B", "RANGER")
    if any(
        len(item.display_name) > 24
        or not item.display_name.isascii()
        or not item.email.isascii()
        for item in (requester, opponent)
    ):
        raise LiveSafetyError("Generated live QA names violate the public wire contract")
    return LivePlan(tag, requester, opponent)


def build_live_snapshot(identity: PlannedIdentity) -> dict[str, Any]:
    base_stat = (LIVE_LEVEL * 2 + 100) // 6
    snapshot = {
        "character_id": identity.character_id,
        "slot_id": 1,
        "display_name": identity.display_name,
        "hero_class": identity.hero_class,
        "level": LIVE_LEVEL,
        "combat_power": 1 + (LIVE_LEVEL - 1) * 5,
        "rules_version": preview.RULES_VERSION,
        "snapshot_version": preview.SNAPSHOT_VERSION,
        "stats": {
            "strength": base_stat,
            "constitution": base_stat,
            "dexterity": base_stat,
            "intelligence": base_stat,
            "wisdom": base_stat,
            "charisma": base_stat,
            "max_health": LIVE_LEVEL * 20,
            "max_mana": LIVE_LEVEL * 10,
        },
        "adventure_trait_ids": [],
    }
    validate_live_profile(snapshot)
    return snapshot


def validate_live_profile(profile: Mapping[str, Any]) -> None:
    if set(profile) != PROFILE_FIELDS:
        raise LiveSafetyError("Outgoing unified profile differs from the reviewed 006 contract")
    slot_id = profile.get("slot_id")
    if not isinstance(slot_id, int) or isinstance(slot_id, bool) or slot_id not in range(1, 4):
        raise LiveSafetyError("Outgoing unified profile slot is invalid")
    legacy_projection = {key: value for key, value in profile.items() if key != "slot_id"}
    try:
        preview.validate_upload_snapshot(legacy_projection, expected_level=LIVE_LEVEL)
    except preview.PreviewError as error:
        raise LiveSafetyError(str(error)) from None


def validate_profile_receipt(payload: Mapping[str, Any]) -> None:
    expected = {
        "accepted", "profile_version", "rules_version", "synced_count",
        "ranking_synced_count", "server_now", "deduplicated",
    }
    if set(payload) != expected:
        raise LiveSafetyError("Unified profile receipt differs from the reviewed 006 contract")
    if payload.get("accepted") is not True or payload.get("profile_version") != 1:
        raise LiveSafetyError("Unified profile publication was not accepted")
    if payload.get("rules_version") != preview.RULES_VERSION:
        raise LiveSafetyError("Unified profile receipt has an unsupported rules version")
    if payload.get("synced_count") != 1 or payload.get("ranking_synced_count") != 1:
        raise LiveSafetyError("Unified profile receipt counts do not match the complete profile")
    if payload.get("deduplicated") is not False:
        raise LiveSafetyError("Fresh unified profile publication was unexpectedly deduplicated")
    if not preview.positive_int(payload.get("server_now")):
        raise LiveSafetyError("Unified profile receipt is missing verified server time")


def build_live_arena_standing(identity: PlannedIdentity) -> dict[str, int]:
    if identity.role == "requester":
        standing = {
            "score": 1040,
            "completed_battles": 10,
            "wins": 6,
            "losses": 3,
            "draws": 1,
        }
    elif identity.role == "opponent":
        standing = {
            "score": 960,
            "completed_battles": 10,
            "wins": 4,
            "losses": 5,
            "draws": 1,
        }
    else:
        raise LiveSafetyError("Arena QA standing role is not allowlisted")
    validate_arena_standing(identity.character_id, standing)
    return standing


def validate_arena_standing(character_id: str, standing: Mapping[str, int]) -> None:
    expected = {"score", "completed_battles", "wins", "losses", "draws"}
    if not preview.is_canonical_uuid(character_id) or set(standing) != expected:
        raise LiveSafetyError("Outgoing arena aggregate differs from the reviewed 008 contract")
    if any(not isinstance(value, int) or isinstance(value, bool) for value in standing.values()):
        raise LiveSafetyError("Outgoing arena aggregate contains a non-integer")
    battles = standing["completed_battles"]
    if battles not in range(10, 1_000_001):
        raise LiveSafetyError("Outgoing arena completed-battle count is invalid")
    if standing["wins"] + standing["losses"] + standing["draws"] != battles:
        raise LiveSafetyError("Outgoing arena W/L/D counters do not sum to completed battles")
    if not 0 <= standing["score"] <= 25_000_000:
        raise LiveSafetyError("Outgoing arena score is invalid")
    if not max(0, 1000 - 24 * battles) <= standing["score"] <= 1000 + 24 * battles:
        raise LiveSafetyError("Outgoing arena score movement is implausible")


def validate_arena_sync_receipt(payload: Mapping[str, Any]) -> None:
    expected = {"accepted", "season_id", "rules_version", "server_now", "deduplicated"}
    if set(payload) != expected:
        raise LiveSafetyError("Arena ranking receipt differs from the reviewed 008 contract")
    if payload.get("accepted") is not True or payload.get("season_id") != "1":
        raise LiveSafetyError("Arena ranking aggregate was not accepted for season 1")
    if payload.get("rules_version") != preview.RULES_VERSION:
        raise LiveSafetyError("Arena ranking receipt has an unsupported rules version")
    if payload.get("deduplicated") is not False:
        raise LiveSafetyError("Fresh arena QA aggregate was unexpectedly deduplicated")
    if not preview.positive_int(payload.get("server_now")):
        raise LiveSafetyError("Arena ranking receipt is missing verified server time")


def validate_known_snapshot_id(value: str | None) -> None:
    if value is None:
        return
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9:_-]{1,128}", value):
        raise LiveSafetyError("Conditional daily snapshot ID is invalid")


def _validate_daily_base(
    payload: Mapping[str, Any],
    base_fields: frozenset[str],
) -> tuple[str, bool]:
    unchanged = payload.get("unchanged")
    expected = set(base_fields) if unchanged is True else set(base_fields) | {"e", "o"}
    if set(payload) != expected or not isinstance(unchanged, bool):
        raise LiveSafetyError("Daily leaderboard envelope differs from its reviewed contract")
    snapshot_id = payload.get("snapshot_id")
    validate_known_snapshot_id(snapshot_id if isinstance(snapshot_id, str) else "")
    if not isinstance(payload.get("is_bootstrap"), bool):
        raise LiveSafetyError("Daily leaderboard bootstrap marker is invalid")
    total = payload.get("t")
    if not isinstance(total, int) or isinstance(total, bool) or total < 0:
        raise LiveSafetyError("Daily leaderboard participant count is invalid")
    timestamps = [payload.get(key) for key in (
        "settled_at", "next_settlement_at", "generated_at", "server_now",
    )]
    if not all(preview.positive_int(value) for value in timestamps):
        raise LiveSafetyError("Daily leaderboard timestamps are invalid")
    settled_at, next_at, generated_at, server_now = timestamps
    if settled_at >= next_at or generated_at < settled_at or server_now < generated_at:
        raise LiveSafetyError("Daily leaderboard time ordering is invalid")
    return snapshot_id, unchanged


def _assert_no_planned_identity(row: Mapping[str, Any], plan: LivePlan) -> None:
    planned_names = {identity.display_name for identity in plan.identities}
    planned_characters = {identity.character_id for identity in plan.identities}
    if row.get("n") in planned_names or row.get("c") in planned_characters:
        raise LiveSafetyError("A just-created QA identity entered an immutable daily snapshot")


def validate_general_daily_leaderboard(
    payload: Mapping[str, Any],
    plan: LivePlan,
) -> int:
    _, unchanged = _validate_daily_base(payload, GENERAL_DAILY_BASE_FIELDS)
    if unchanged:
        return 0
    entries = payload.get("e")
    own = payload.get("o")
    if not isinstance(entries, list) or len(entries) > 1000:
        raise LiveSafetyError("General daily leaderboard entries are invalid")
    if not isinstance(own, list) or len(own) > 3:
        raise LiveSafetyError("General daily leaderboard own entries are invalid")
    for row in entries + own:
        if not isinstance(row, Mapping):
            raise LiveSafetyError("General daily leaderboard row is not an object")
        allowed = set(GENERAL_DAILY_ROW_FIELDS) | {"s"}
        if not GENERAL_DAILY_ROW_FIELDS.issubset(row) or not set(row).issubset(allowed):
            raise LiveSafetyError("General daily leaderboard row differs from the compact contract")
        if not preview.positive_int(row.get("r")):
            raise LiveSafetyError("General daily leaderboard rank is invalid")
        if not isinstance(row.get("i"), int) or isinstance(row.get("i"), bool) or row["i"] < 0:
            raise LiveSafetyError("General daily leaderboard list index is invalid")
        if not preview.is_canonical_uuid(row.get("c")):
            raise LiveSafetyError("General daily leaderboard character ID is invalid")
        if row.get("h") not in preview.HERO_CLASSES or not isinstance(row.get("n"), str):
            raise LiveSafetyError("General daily leaderboard public identity is invalid")
        if not isinstance(row.get("l"), int) or row["l"] not in range(20, LIVE_LEVEL + 1):
            raise LiveSafetyError("General daily leaderboard level is invalid")
        if not isinstance(row.get("p"), int) or isinstance(row.get("p"), bool) or row["p"] < 0:
            raise LiveSafetyError("General daily leaderboard power is invalid")
        if not preview.positive_int(row.get("a")):
            raise LiveSafetyError("General daily leaderboard achievement time is invalid")
        if "s" in row and (not isinstance(row["s"], str) or len(row["s"]) > 64):
            raise LiveSafetyError("General daily leaderboard system code is invalid")
        _assert_no_planned_identity(row, plan)
    return len(entries) + len(own)


def validate_arena_daily_leaderboard(
    payload: Mapping[str, Any],
    plan: LivePlan,
) -> int:
    _, unchanged = _validate_daily_base(payload, ARENA_DAILY_BASE_FIELDS)
    if payload.get("season_id") != "1" or payload.get("rules_version") != preview.RULES_VERSION:
        raise LiveSafetyError("Arena daily leaderboard season or rules version is invalid")
    if unchanged:
        return 0
    entries = payload.get("e")
    own = payload.get("o")
    if not isinstance(entries, list) or not isinstance(own, list):
        raise LiveSafetyError("Arena daily leaderboard entry arrays are invalid")
    if len(own) > 3 or len(entries) + len(own) > 1000:
        raise LiveSafetyError("Arena daily leaderboard exceeded its bounded row contract")
    for row in entries + own:
        if not isinstance(row, Mapping) or set(row) != set(ARENA_DAILY_ROW_FIELDS):
            raise LiveSafetyError("Arena daily leaderboard row differs from the compact contract")
        if not preview.positive_int(row.get("r")):
            raise LiveSafetyError("Arena daily leaderboard rank is invalid")
        if not isinstance(row.get("i"), int) or isinstance(row.get("i"), bool) or row["i"] < 0:
            raise LiveSafetyError("Arena daily leaderboard list index is invalid")
        if not preview.is_canonical_uuid(row.get("u")) or not preview.is_canonical_uuid(row.get("c")):
            raise LiveSafetyError("Arena daily leaderboard public identity is invalid")
        if row.get("h") not in preview.HERO_CLASSES or not isinstance(row.get("n"), str):
            raise LiveSafetyError("Arena daily leaderboard name or class is invalid")
        if not isinstance(row.get("l"), int) or row["l"] not in range(10, LIVE_LEVEL + 1):
            raise LiveSafetyError("Arena daily leaderboard level is invalid")
        integer_fields = ("p", "b", "w", "x", "d")
        if any(not isinstance(row.get(key), int) or isinstance(row[key], bool) for key in integer_fields):
            raise LiveSafetyError("Arena daily leaderboard aggregate is invalid")
        if row["b"] < 10 or row["w"] + row["x"] + row["d"] != row["b"]:
            raise LiveSafetyError("Arena daily leaderboard W/L/D counters are invalid")
        if not 0 <= row["p"] <= 25_000_000 or not preview.positive_int(row.get("a")):
            raise LiveSafetyError("Arena daily leaderboard score or achievement time is invalid")
        _assert_no_planned_identity(row, plan)
    return len(entries) + len(own)


def assert_conditional_daily_snapshot(
    first: Mapping[str, Any],
    second: Mapping[str, Any],
    base_fields: frozenset[str],
) -> None:
    if second.get("unchanged") is not True or set(second) != set(base_fields):
        raise LiveSafetyError("Conditional daily leaderboard response was not metadata-only")
    stable = set(base_fields) - {"server_now", "unchanged"}
    if any(first.get(key) != second.get(key) for key in stable):
        raise LiveSafetyError("Conditional daily leaderboard snapshot metadata changed")
    if not preview.positive_int(second.get("server_now")) or second["server_now"] < first["server_now"]:
        raise LiveSafetyError("Conditional daily leaderboard server time regressed")


def preflight_audit(client: ManagementDatabaseClient, plan: LivePlan) -> dict[str, int]:
    sql = """
select pg_catalog.jsonb_build_object(
  'server_now_epoch_millis', (
    extract(epoch from pg_catalog.statement_timestamp()) * 1000
  )::bigint,
  'utc_seconds_since_midnight', pg_catalog.floor(pg_catalog.date_part(
    'epoch', pg_catalog.statement_timestamp() -
      (pg_catalog.date_trunc('day', pg_catalog.statement_timestamp() at time zone 'UTC') at time zone 'UTC')
  ))::integer,
  'utc_seconds_until_midnight', pg_catalog.ceil(pg_catalog.date_part(
    'epoch',
    ((pg_catalog.date_trunc('day', pg_catalog.statement_timestamp() at time zone 'UTC') + interval '1 day') at time zone 'UTC')
      - pg_catalog.statement_timestamp()
  ))::integer,
  'shared_private_tables', (
    select pg_catalog.count(*) from information_schema.tables
     where table_schema = 'shared_player_private'
       and table_name in (
         'snapshots','daily_rosters','sync_limits','roster_call_limits',
         'roster_daily_issuance_limits'
       )
  ),
  'ranking_private_tables', (
    select pg_catalog.count(*) from information_schema.tables
     where table_schema = 'ranking_private'
       and table_name in (
         'daily_leaderboard_snapshots','daily_leaderboard_state',
         'daily_leaderboard_rows','daily_ranking_before_images'
       )
  ),
  'arena_private_tables', (
    select pg_catalog.count(*) from information_schema.tables
     where table_schema = 'arena_ranking_private'
       and table_name in (
         'daily_arena_leaderboard_snapshots','daily_arena_leaderboard_state',
         'season_standings','season_character_bindings','account_call_limits','entry_sync_limits',
         'daily_arena_leaderboard_rows','daily_arena_before_images'
       )
  ),
  'snapshot_identity_collisions', (
    select pg_catalog.count(*) from shared_player_private.snapshots
     where character_id in ($1::uuid, $2::uuid)
        or display_name in ($3::text, $4::text)
  ),
  'ranking_identity_collisions', (
    select pg_catalog.count(*) from public.ranking_entries
     where character_id in ($1::uuid, $2::uuid)
        or display_name in ($3::text, $4::text)
  ),
  'arena_identity_collisions', (
    select pg_catalog.count(*) from arena_ranking_private.season_standings
     where character_id in ($1::uuid, $2::uuid)
        or display_name in ($3::text, $4::text)
  ),
  'arena_guard_character_collisions', (
    select pg_catalog.count(*) from arena_ranking_private.entry_sync_limits
     where character_id in ($1::uuid, $2::uuid)
  ),
  'arena_binding_character_collisions', (
    select pg_catalog.count(*) from arena_ranking_private.season_character_bindings
     where character_id in ($1::uuid, $2::uuid)
  ),
  'roster_name_remnants', (
    select pg_catalog.count(*)
      from shared_player_private.daily_rosters as roster
     where exists (
       select 1 from pg_catalog.jsonb_array_elements(roster.snapshots) as item(value)
        where item.value ->> 'display_name' in ($3::text, $4::text)
     )
  ),
  'ranking_before_image_remnants', (
    select pg_catalog.count(*) from ranking_private.daily_ranking_before_images
     where row_data ->> 'character_id' in ($1::text, $2::text)
        or row_data ->> 'display_name' in ($3::text, $4::text)
  ),
  'ranking_daily_row_remnants', (
    select pg_catalog.count(*) from ranking_private.daily_leaderboard_rows
     where character_id in ($1::uuid, $2::uuid)
        or display_name in ($3::text, $4::text)
  ),
  'ranking_snapshot_name_remnants', (
    select pg_catalog.count(*)
      from ranking_private.daily_leaderboard_snapshots as snapshot
     where exists (
       select 1 from pg_catalog.jsonb_array_elements(snapshot.top_entries) as item(value)
        where item.value ->> 'c' in ($1::text, $2::text)
           or item.value ->> 'n' in ($3::text, $4::text)
     )
  ),
  'arena_before_image_remnants', (
    select pg_catalog.count(*) from arena_ranking_private.daily_arena_before_images
     where row_data ->> 'character_id' in ($1::text, $2::text)
        or row_data ->> 'display_name' in ($3::text, $4::text)
  ),
  'arena_daily_row_remnants', (
    select pg_catalog.count(*) from arena_ranking_private.daily_arena_leaderboard_rows
     where character_id in ($1::uuid, $2::uuid)
        or display_name in ($3::text, $4::text)
  ),
  'arena_snapshot_name_remnants', (
    select pg_catalog.count(*)
      from arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
     where exists (
       select 1 from pg_catalog.jsonb_array_elements(snapshot.top_entries) as item(value)
        where item.value ->> 'c' in ($1::text, $2::text)
           or item.value ->> 'n' in ($3::text, $4::text)
     )
  ),
  'auth_email_collisions', (
    select pg_catalog.count(*) from auth.users
     where email in ($5::text, $6::text)
  ),
  'auth_run_tag_collisions', (
    select pg_catalog.count(*) from auth.users
     where raw_user_meta_data ->> 'run_tag' = $7::text
  ),
  'public_rpcs', (
    select pg_catalog.count(*) from (values
      ('public.sync_player_network_profile(jsonb)'::pg_catalog.regprocedure),
      ('public.get_daily_public_player_roster(uuid,integer)'::pg_catalog.regprocedure),
      ('public.get_daily_leaderboard(text)'::pg_catalog.regprocedure),
      ('public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'::pg_catalog.regprocedure),
      ('public.get_daily_arena_leaderboard(text)'::pg_catalog.regprocedure)
    ) as required(function_oid)
  ),
  'rpc_acl_violations', (
    select pg_catalog.count(*) from (values
      ('public.sync_player_network_profile(jsonb)'),
      ('public.get_daily_public_player_roster(uuid,integer)'),
      ('public.get_daily_leaderboard(text)'),
      ('public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'),
      ('public.get_daily_arena_leaderboard(text)')
    ) as required(signature)
     where not pg_catalog.has_function_privilege('authenticated', required.signature, 'EXECUTE')
        or pg_catalog.has_function_privilege('anon', required.signature, 'EXECUTE')
  ),
  'private_select_acl_violations', (
    select pg_catalog.count(*) from (values
      ('shared_player_private.snapshots'),
      ('shared_player_private.daily_rosters'),
      ('shared_player_private.sync_limits'),
      ('shared_player_private.roster_call_limits'),
      ('shared_player_private.roster_daily_issuance_limits'),
      ('ranking_private.daily_leaderboard_snapshots'),
      ('ranking_private.daily_leaderboard_state'),
      ('ranking_private.daily_leaderboard_rows'),
      ('ranking_private.daily_ranking_before_images'),
      ('arena_ranking_private.daily_arena_leaderboard_snapshots'),
      ('arena_ranking_private.daily_arena_leaderboard_state'),
      ('arena_ranking_private.season_standings'),
      ('arena_ranking_private.season_character_bindings'),
      ('arena_ranking_private.account_call_limits'),
      ('arena_ranking_private.entry_sync_limits'),
      ('arena_ranking_private.daily_arena_leaderboard_rows'),
      ('arena_ranking_private.daily_arena_before_images')
    ) as required(relation)
     where pg_catalog.has_table_privilege('authenticated', required.relation, 'SELECT')
        or pg_catalog.has_table_privilege('anon', required.relation, 'SELECT')
  ),
  'cron_jobs', (
    select pg_catalog.count(*) from cron.job
     where (
       jobname = 'alarmquest-daily-leaderboard'
       and schedule = '0 * * * *'
       and command = 'select ranking_private.publish_daily_leaderboard()'
     ) or (
       jobname = 'alarmquest-daily-arena-leaderboard'
       and schedule = '17 * * * *'
       and command = 'select arena_ranking_private.publish_daily_arena_leaderboard()'
     )
  )
) as audit
"""
    params = [
        plan.requester.character_id,
        plan.opponent.character_id,
        plan.requester.display_name,
        plan.opponent.display_name,
        plan.requester.email,
        plan.opponent.email,
        plan.run_tag,
    ]
    return _audit_row(client.query(sql, params, read_only=True))


def scoped_audit(
    client: ManagementDatabaseClient,
    captured: Sequence[CapturedIdentity],
    started_at: str,
) -> dict[str, int]:
    target_sql, params = _target_values(captured)
    params.append(started_at)
    started_placeholder = f"${len(params)}"
    sql = f"""
with targets(user_id, character_id, display_name, email, qa_role, run_tag) as (values {target_sql})
select pg_catalog.jsonb_build_object(
  'auth_users', (
    select pg_catalog.count(*) from auth.users as account
    join targets on targets.user_id = account.id
  ),
  'fresh_exact_qa_users', (
    select pg_catalog.count(*) from auth.users as account
    join targets on targets.user_id = account.id
     where account.is_anonymous is false
       and account.created_at >= {started_placeholder}::timestamptz
       and account.created_at < {started_placeholder}::timestamptz + interval '10 minutes'
       and account.email = targets.email
       and account.raw_user_meta_data ->> 'alarmquest_live_qa' = 'true'
       and account.raw_user_meta_data ->> 'run_tag' = targets.run_tag
       and account.raw_user_meta_data ->> 'qa_role' = targets.qa_role
  ),
  'auth_identities_for_users', (
    select pg_catalog.count(*) from auth.identities as identity
    join targets on targets.user_id = identity.user_id
  ),
  'auth_sessions_for_users', (
    select pg_catalog.count(*) from auth.sessions as session
    join targets on targets.user_id = session.user_id
  ),
  'auth_refresh_tokens_for_users', (
    select pg_catalog.count(*) from auth.refresh_tokens as token
    join targets on targets.user_id::text = token.user_id
  ),
  'snapshots_for_users', (
    select pg_catalog.count(*) from shared_player_private.snapshots as snapshot
    join targets on targets.user_id = snapshot.user_id
  ),
  'expected_snapshots', (
    select pg_catalog.count(*) from shared_player_private.snapshots as snapshot
    join targets on targets.user_id = snapshot.user_id
                and targets.character_id = snapshot.character_id
                and targets.display_name = snapshot.display_name
  ),
  'unexpected_snapshots_for_users', (
    select pg_catalog.count(*) from shared_player_private.snapshots as snapshot
    join targets on targets.user_id = snapshot.user_id
     where snapshot.character_id <> targets.character_id
        or snapshot.display_name <> targets.display_name
  ),
  'ranking_entries_for_users', (
    select pg_catalog.count(*) from public.ranking_entries as ranking
    join targets on targets.user_id = ranking.user_id
  ),
  'expected_ranking_entries', (
    select pg_catalog.count(*) from public.ranking_entries as ranking
    join targets on targets.user_id = ranking.user_id
                and targets.character_id = ranking.character_id
                and targets.display_name = ranking.display_name
  ),
  'unexpected_ranking_entries_for_users', (
    select pg_catalog.count(*) from public.ranking_entries as ranking
    join targets on targets.user_id = ranking.user_id
     where ranking.character_id <> targets.character_id
        or ranking.display_name <> targets.display_name
  ),
  'foreign_snapshot_identity_collisions', (
    select pg_catalog.count(*) from shared_player_private.snapshots as snapshot
     where exists (
       select 1 from targets
        where targets.character_id = snapshot.character_id
           or targets.display_name = snapshot.display_name
     )
       and not exists (
       select 1 from targets
        where targets.user_id = snapshot.user_id
          and targets.character_id = snapshot.character_id
          and targets.display_name = snapshot.display_name
     )
  ),
  'foreign_ranking_identity_collisions', (
    select pg_catalog.count(*) from public.ranking_entries as ranking
     where exists (
       select 1 from targets
        where targets.character_id = ranking.character_id
           or targets.display_name = ranking.display_name
     )
       and not exists (
       select 1 from targets
        where targets.user_id = ranking.user_id
          and targets.character_id = ranking.character_id
          and targets.display_name = ranking.display_name
     )
  ),
  'foreign_arena_identity_collisions', (
    select pg_catalog.count(*) from arena_ranking_private.season_standings as standing
     where exists (
       select 1 from targets
        where targets.character_id = standing.character_id
           or targets.display_name = standing.display_name
     )
       and not exists (
       select 1 from targets
        where targets.user_id = standing.user_id
          and targets.character_id = standing.character_id
          and targets.display_name = standing.display_name
     )
  ),
  'daily_rosters_for_users', (
    select pg_catalog.count(*) from shared_player_private.daily_rosters as roster
    join targets on targets.user_id = roster.user_id
  ),
  'sync_limits_for_users', (
    select pg_catalog.count(*) from shared_player_private.sync_limits as guard
    join targets on targets.user_id = guard.user_id
  ),
  'profile_contract_users', (
    select pg_catalog.count(*) from shared_player_private.sync_limits as guard
    join targets on targets.user_id = guard.user_id
     where guard.profile_contract_version = 1
  ),
  'roster_limits_for_users', (
    select pg_catalog.count(*) from shared_player_private.roster_call_limits as guard
    join targets on targets.user_id = guard.user_id
  ),
  'issuance_limits_for_users', (
    select pg_catalog.count(*) from shared_player_private.roster_daily_issuance_limits as guard
    join targets on targets.user_id = guard.user_id
  ),
  'ranking_before_images_for_users', (
    select pg_catalog.count(*) from ranking_private.daily_ranking_before_images as saved
    join targets on targets.user_id = saved.user_id
  ),
  'ranking_daily_rows_for_users', (
    select pg_catalog.count(*) from ranking_private.daily_leaderboard_rows as row
    join targets on targets.user_id = row.user_id
  ),
  'ranking_snapshot_qa_rows', (
    select pg_catalog.count(*)
      from ranking_private.daily_leaderboard_snapshots as snapshot
      cross join lateral pg_catalog.jsonb_array_elements(snapshot.top_entries) as item(value)
     where exists (
       select 1 from targets
        where targets.character_id::text = item.value ->> 'c'
           or targets.display_name = item.value ->> 'n'
     )
  ),
  'arena_standings_for_users', (
    select pg_catalog.count(*) from arena_ranking_private.season_standings as standing
    join targets on targets.user_id = standing.user_id
  ),
  'arena_bindings_for_users', (
    select pg_catalog.count(*) from arena_ranking_private.season_character_bindings as binding
    join targets on targets.user_id = binding.user_id
  ),
  'expected_arena_standings', (
    select pg_catalog.count(*) from arena_ranking_private.season_standings as standing
    join targets on targets.user_id = standing.user_id
                and targets.character_id = standing.character_id
                and targets.display_name = standing.display_name
  ),
  'unexpected_arena_standings_for_users', (
    select pg_catalog.count(*) from arena_ranking_private.season_standings as standing
    join targets on targets.user_id = standing.user_id
     where standing.character_id <> targets.character_id
        or standing.display_name <> targets.display_name
  ),
  'arena_account_limits_for_users', (
    select pg_catalog.count(*) from arena_ranking_private.account_call_limits as guard
    join targets on targets.user_id = guard.user_id
  ),
  'arena_entry_limits_for_users', (
    select pg_catalog.count(*) from arena_ranking_private.entry_sync_limits as guard
    join targets on targets.user_id = guard.user_id
                and targets.character_id = guard.character_id
  ),
  'arena_before_images_for_users', (
    select pg_catalog.count(*) from arena_ranking_private.daily_arena_before_images as saved
    join targets on targets.user_id = saved.user_id
  ),
  'arena_daily_rows_for_users', (
    select pg_catalog.count(*) from arena_ranking_private.daily_arena_leaderboard_rows as row
    join targets on targets.user_id = row.user_id
  ),
  'arena_snapshot_qa_rows', (
    select pg_catalog.count(*)
      from arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
      cross join lateral pg_catalog.jsonb_array_elements(snapshot.top_entries) as item(value)
     where exists (
       select 1 from targets
        where targets.character_id::text = item.value ->> 'c'
           or targets.display_name = item.value ->> 'n'
     )
  ),
  'admin_subscribers_for_users', (
    select pg_catalog.count(*) from public.admin_subscribers as subscriber
    join targets on targets.user_id = subscriber.user_id
  ),
  'admin_events_for_users', (
    select pg_catalog.count(*) from public.admin_events as event
     where exists (
       select 1 from targets
        where targets.user_id = event.subject_user_id
           or targets.user_id::text = event.payload ->> 'user_id'
     )
  ),
  'embedded_qa_rows', (
    select pg_catalog.count(*)
      from shared_player_private.daily_rosters as roster
      cross join lateral pg_catalog.jsonb_array_elements(roster.snapshots) as item(value)
     where exists (
       select 1 from targets where targets.display_name = item.value ->> 'display_name'
     )
  ),
  'foreign_embedded_qa_rows', (
    select pg_catalog.count(*)
      from shared_player_private.daily_rosters as roster
      cross join lateral pg_catalog.jsonb_array_elements(roster.snapshots) as item(value)
     where exists (
       select 1 from targets where targets.display_name = item.value ->> 'display_name'
     )
       and not exists (select 1 from targets where targets.user_id = roster.user_id)
  )
) as audit
"""
    return _audit_row(client.query(sql, params, read_only=True))


def resolve_created_user(
    client: ManagementDatabaseClient,
    planned: PlannedIdentity,
    run_tag: str,
    started_at: str,
) -> str | None:
    """Recover an Auth ID after an uncertain Admin response without exposing it."""
    sql = """
select account.id::text as user_id
  from auth.users as account
 where account.email = $1::text
   and account.is_anonymous is false
   and account.created_at >= $2::timestamptz
   and account.created_at < $2::timestamptz + interval '10 minutes'
   and account.raw_user_meta_data ->> 'alarmquest_live_qa' = 'true'
   and account.raw_user_meta_data ->> 'run_tag' = $3::text
   and account.raw_user_meta_data ->> 'qa_role' = $4::text
 limit 2
"""
    rows = client.query(sql, [planned.email, started_at, run_tag, planned.role], read_only=True)
    if not rows:
        return None
    if len(rows) != 1 or set(rows[0]) != {"user_id"}:
        raise LiveSafetyError("Auth Admin creation recovery found an unexpected identity count")
    user_id = rows[0]["user_id"]
    if not preview.is_canonical_uuid(user_id):
        raise LiveSafetyError("Auth Admin creation recovery returned an invalid identity")
    return str(user_id).lower()


def resolve_missing_captures(
    client: ManagementDatabaseClient,
    journal: CleanupJournal,
    plan: LivePlan,
    started_at: str,
    captured: list[CapturedIdentity],
) -> None:
    captured_roles = {item.planned.role for item in captured}
    for identity in plan.identities:
        if identity.role in captured_roles:
            continue
        recovered = resolve_created_user(client, identity, plan.run_tag, started_at)
        if recovered is not None:
            if any(item.user_id == recovered for item in captured):
                raise LiveSafetyError("Auth Admin recovery found a duplicate identity")
            captured.append(CapturedIdentity(identity, recovered))
            captured_roles.add(identity.role)
            journal.update(plan, started_at, captured)


def delete_captured_auth_users(
    client: ManagementDatabaseClient,
    captured: Sequence[CapturedIdentity],
    started_at: str,
) -> dict[str, int]:
    if not captured or len(captured) > 2:
        raise LiveSafetyError("Cleanup target count must be one or two captured Auth users")
    ids = [item.user_id for item in captured]
    for value in ids:
        if not preview.is_canonical_uuid(value):
            raise LiveSafetyError("Cleanup target identity is invalid")
    values: list[str] = []
    params: list[Any] = []
    for index, item in enumerate(captured):
        offset = index * 4
        values.append(
            f"(${offset + 1}::uuid,${offset + 2}::text,"
            f"${offset + 3}::text,${offset + 4}::text)"
        )
        params.extend((
            item.user_id,
            item.planned.email,
            item.planned.role,
            item.planned.display_name.rsplit("-", 1)[-1],
        ))
    values_sql = ",".join(values)
    started_placeholder = f"${len(params) + 1}"
    expected_placeholder = f"${len(params) + 2}"
    sql = f"""
with targets(user_id, email, qa_role, run_tag) as (values {values_sql}),
eligible as materialized (
  select account.id from auth.users as account
  join targets on targets.user_id = account.id
   where account.is_anonymous is false
     and account.created_at >= {started_placeholder}::timestamptz
     and account.created_at < {started_placeholder}::timestamptz + interval '10 minutes'
     and account.email = targets.email
     and account.raw_user_meta_data ->> 'alarmquest_live_qa' = 'true'
     and account.raw_user_meta_data ->> 'run_tag' = targets.run_tag
     and account.raw_user_meta_data ->> 'qa_role' = targets.qa_role
),
deleted as (
  delete from auth.users as account
   using eligible
   where account.id = eligible.id
     and (select pg_catalog.count(*) from eligible) = {expected_placeholder}::integer
  returning account.id
)
select pg_catalog.jsonb_build_object(
  'requested_auth_users', {expected_placeholder}::integer,
  'eligible_auth_users', (select pg_catalog.count(*) from eligible),
  'deleted_auth_users', (select pg_catalog.count(*) from deleted)
) as audit
"""
    params.extend((started_at, len(ids)))
    return _audit_row(client.query(sql, params, read_only=False))


def assert_preflight_clean(audit: Mapping[str, int]) -> str:
    values = dict(audit)
    server_now = values.pop("server_now_epoch_millis", None)
    since_midnight = values.pop("utc_seconds_since_midnight", None)
    until_midnight = values.pop("utc_seconds_until_midnight", None)
    if not preview.positive_int(server_now):
        raise LiveSafetyError("Production preflight did not return trusted server time")
    if not isinstance(since_midnight, int) or not isinstance(until_midnight, int):
        raise LiveSafetyError("Production preflight did not return UTC boundary distance")
    if since_midnight < UTC_BOUNDARY_MARGIN_SECONDS or until_midnight < UTC_BOUNDARY_MARGIN_SECONDS:
        raise LiveSafetyError("Live QA is blocked within 30 minutes of the UTC daily cutoff")
    expected_since = (server_now // 1000) % 86_400
    if abs(expected_since - since_midnight) > 1 or abs(86_400 - expected_since - until_midnight) > 1:
        raise LiveSafetyError("Production preflight UTC boundary values are inconsistent")
    expected = {
        "shared_private_tables": EXPECTED_SHARED_PRIVATE_TABLES,
        "ranking_private_tables": EXPECTED_RANKING_PRIVATE_TABLES,
        "arena_private_tables": EXPECTED_ARENA_PRIVATE_TABLES,
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
    _assert_exact_counts("production preflight", values, expected)
    return datetime.fromtimestamp(server_now / 1000, tz=timezone.utc).isoformat()


def assert_completed_audit(
    audit: Mapping[str, int],
    *,
    allow_additional_sessions: bool = False,
) -> None:
    session_counts = {
        "auth_sessions_for_users": audit.get("auth_sessions_for_users"),
        "auth_refresh_tokens_for_users": audit.get("auth_refresh_tokens_for_users"),
    }
    maximum = 6 if allow_additional_sessions else 2
    if any(not isinstance(value, int) or value not in range(2, maximum + 1) for value in session_counts.values()):
        raise LiveSafetyError("pre-cleanup Auth session counts differed from the reviewed contract")
    expected = {
        "auth_users": 2,
        "fresh_exact_qa_users": 2,
        "auth_identities_for_users": 2,
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
    stable_audit = {key: value for key, value in audit.items() if key not in session_counts}
    _assert_exact_counts("pre-cleanup ownership", stable_audit, expected)


def assert_cleanup_identity_safe(audit: Mapping[str, int], target_count: int) -> None:
    if audit.get("auth_users") != target_count:
        raise LiveSafetyError("Cleanup refused because captured Auth user count changed")
    if audit.get("fresh_exact_qa_users") != target_count:
        raise LiveSafetyError("Cleanup refused because a captured Auth identity is not the exact fresh QA user")
    if any(audit.get(key) != 0 for key in (
        "foreign_snapshot_identity_collisions",
        "foreign_ranking_identity_collisions",
        "foreign_arena_identity_collisions",
    )):
        raise LiveSafetyError("Cleanup refused because a run identity belongs to another Auth user")
    if audit.get("admin_subscribers_for_users") != 0 or audit.get("admin_events_for_users") != 0:
        raise LiveSafetyError("Cleanup refused because a QA user entered administrator telemetry")
    if audit.get("foreign_embedded_qa_rows") != 0:
        raise LiveSafetyError("Cleanup refused because another user's frozen roster captured QA data")
    if any(audit.get(key) != 0 for key in (
        "ranking_before_images_for_users",
        "ranking_daily_rows_for_users",
        "ranking_snapshot_qa_rows",
        "arena_before_images_for_users",
        "arena_daily_rows_for_users",
        "arena_snapshot_qa_rows",
    )):
        raise LiveSafetyError("Cleanup refused because QA data entered an immutable daily boundary")


def assert_zero_remnants(audit: Mapping[str, int]) -> None:
    expected = {key: 0 for key in (
        "auth_users",
        "fresh_exact_qa_users",
        "auth_identities_for_users",
        "auth_sessions_for_users",
        "auth_refresh_tokens_for_users",
        "snapshots_for_users",
        "expected_snapshots",
        "unexpected_snapshots_for_users",
        "ranking_entries_for_users",
        "expected_ranking_entries",
        "unexpected_ranking_entries_for_users",
        "foreign_snapshot_identity_collisions",
        "foreign_ranking_identity_collisions",
        "foreign_arena_identity_collisions",
        "daily_rosters_for_users",
        "sync_limits_for_users",
        "profile_contract_users",
        "roster_limits_for_users",
        "issuance_limits_for_users",
        "ranking_before_images_for_users",
        "ranking_daily_rows_for_users",
        "ranking_snapshot_qa_rows",
        "arena_standings_for_users",
        "arena_bindings_for_users",
        "expected_arena_standings",
        "unexpected_arena_standings_for_users",
        "arena_account_limits_for_users",
        "arena_entry_limits_for_users",
        "arena_before_images_for_users",
        "arena_daily_rows_for_users",
        "arena_snapshot_qa_rows",
        "admin_subscribers_for_users",
        "admin_events_for_users",
        "embedded_qa_rows",
        "foreign_embedded_qa_rows",
    )}
    _assert_exact_counts("post-cleanup remnant", audit, expected)


def cleanup_captured(
    client: ManagementDatabaseClient,
    captured: Sequence[CapturedIdentity],
    started_at: str,
) -> int:
    before = scoped_audit(client, captured, started_at)
    assert_cleanup_identity_safe(before, len(captured))
    receipt = delete_captured_auth_users(client, captured, started_at)
    expected_receipt = {
        "requested_auth_users": len(captured),
        "eligible_auth_users": len(captured),
        "deleted_auth_users": len(captured),
    }
    _assert_exact_counts("Auth cleanup receipt", receipt, expected_receipt)
    after = scoped_audit(client, captured, started_at)
    assert_zero_remnants(after)
    return len(captured)


def run_live(
    config: LiveConfig,
    *,
    plan: LivePlan | None = None,
    journal: CleanupJournal | None = None,
    handoff: EmulatorCredentialHandoff | None = None,
    retain_for_emulator: bool = False,
    public_client: LiveSupabaseClient | None = None,
    management_client: ManagementDatabaseClient | None = None,
    sleep_fn: Callable[[float], None] = time.sleep,
) -> LiveRunSummary:
    selected_plan = plan or make_plan(os.environ.get(RUN_TAG_ENV, "") or make_run_tag())
    selected_journal = journal or CleanupJournal()
    selected_handoff = handoff or EmulatorCredentialHandoff()
    public_api = public_client or LiveSupabaseClient(config)
    database = management_client or ManagementDatabaseClient(
        config.supabase_cli,
        config.linked_cli_workdir,
    )
    captured: list[CapturedIdentity] = []
    primary_failure: BaseException | None = None
    cleanup_failure: BaseException | None = None
    completed_candidates = 0
    completed_general_entries = 0
    completed_arena_entries = 0
    deleted_users = 0
    retain_success = False
    auth_creation_attempted = False
    requester_password = secrets.token_urlsafe(32) + "Aa1!"
    opponent_password = secrets.token_urlsafe(32) + "Bb2!"

    if selected_journal.path.exists():
        raise LiveSafetyError(
            "A pending live-QA cleanup journal exists; run --recover-cleanup before a new test"
        )
    if retain_for_emulator and selected_handoff.path.exists():
        raise LiveSafetyError("A pending emulator credential handoff already exists")
    started_at = assert_preflight_clean(preflight_audit(database, selected_plan))
    selected_journal.create(selected_plan, started_at)
    try:
        def create_and_sign_in(
            identity: PlannedIdentity,
            password: str,
        ) -> preview.AnonymousSession:
            nonlocal auth_creation_attempted
            auth_creation_attempted = True
            try:
                user_id = public_api.create_qa_user(identity, password, selected_plan.run_tag)
            except BaseException:
                recovered = resolve_created_user(
                    database,
                    identity,
                    selected_plan.run_tag,
                    started_at,
                )
                if recovered is not None:
                    captured.append(CapturedIdentity(identity, recovered))
                    selected_journal.update(selected_plan, started_at, captured)
                raise
            captured.append(CapturedIdentity(identity, user_id))
            selected_journal.update(selected_plan, started_at, captured)
            return public_api.sign_in(identity, password, user_id)

        requester_session = create_and_sign_in(selected_plan.requester, requester_password)
        opponent_session = create_and_sign_in(selected_plan.opponent, opponent_password)
        if opponent_session.user_id == requester_session.user_id:
            raise LiveSafetyError("Auth Admin returned duplicate identities")

        public_api.sync_profile(requester_session, build_live_snapshot(selected_plan.requester))
        public_api.sync_profile(opponent_session, build_live_snapshot(selected_plan.opponent))

        general_first = public_api.get_daily_leaderboard(requester_session)
        completed_general_entries = validate_general_daily_leaderboard(general_first, selected_plan)
        general_second = public_api.get_daily_leaderboard(
            requester_session,
            str(general_first["snapshot_id"]),
        )
        validate_general_daily_leaderboard(general_second, selected_plan)
        assert_conditional_daily_snapshot(
            general_first,
            general_second,
            GENERAL_DAILY_BASE_FIELDS,
        )

        first = public_api.get_roster(requester_session, selected_plan.requester.character_id)
        candidates = preview.validate_roster(
            first,
            selected_plan.requester.display_name,
            expected_level=LIVE_LEVEL,
        )
        if not any(
            candidate.get("display_name") == selected_plan.opponent.display_name
            for candidate in candidates
        ):
            raise LiveSafetyError("The second live-QA account was absent from PUBLIC_ROSTER")
        sleep_fn(preview.ROSTER_RETRY_WAIT_SECONDS)
        second = public_api.get_roster(requester_session, selected_plan.requester.character_id)
        preview.validate_roster(
            second,
            selected_plan.requester.display_name,
            expected_level=LIVE_LEVEL,
        )
        preview.assert_frozen_roster(first, second)

        public_api.sync_arena_ranking(
            requester_session,
            selected_plan.requester.character_id,
            build_live_arena_standing(selected_plan.requester),
        )
        public_api.sync_arena_ranking(
            opponent_session,
            selected_plan.opponent.character_id,
            build_live_arena_standing(selected_plan.opponent),
        )
        arena_first = public_api.get_daily_arena_leaderboard(requester_session)
        completed_arena_entries = validate_arena_daily_leaderboard(arena_first, selected_plan)
        arena_second = public_api.get_daily_arena_leaderboard(
            requester_session,
            str(arena_first["snapshot_id"]),
        )
        validate_arena_daily_leaderboard(arena_second, selected_plan)
        assert_conditional_daily_snapshot(
            arena_first,
            arena_second,
            ARENA_DAILY_BASE_FIELDS,
        )

        assert_completed_audit(scoped_audit(database, captured, started_at))
        completed_candidates = len(candidates)
        if retain_for_emulator:
            selected_handoff.create(selected_plan, requester_password)
            retain_success = True
    except BaseException as error:  # finally cleanup also runs for Ctrl-C/SystemExit
        primary_failure = error
    finally:
        if auth_creation_attempted and not retain_success:
            try:
                resolve_missing_captures(
                    database,
                    selected_journal,
                    selected_plan,
                    started_at,
                    captured,
                )
            except BaseException as error:
                cleanup_failure = error
        if captured and not retain_success:
            if cleanup_failure is None:
                try:
                    deleted_users = cleanup_captured(database, captured, started_at)
                    selected_journal.remove()
                    selected_handoff.remove()
                except BaseException as error:
                    cleanup_failure = error
        elif not captured and cleanup_failure is None:
            selected_journal.remove()

    if cleanup_failure is not None:
        raise LiveSafetyError(
            "Live QA cleanup did not complete; the protected recovery journal was retained"
        ) from None
    if primary_failure is not None:
        raise primary_failure
    return LiveRunSummary(
        selected_plan.run_tag,
        completed_candidates,
        completed_general_entries,
        completed_arena_entries,
        deleted_users,
    )


def recover_cleanup(
    config: LiveConfig,
    journal: CleanupJournal | None = None,
    handoff: EmulatorCredentialHandoff | None = None,
    management_client: ManagementDatabaseClient | None = None,
    *,
    require_completed_contract: bool = False,
) -> int:
    selected_journal = journal or CleanupJournal()
    selected_handoff = handoff or EmulatorCredentialHandoff()
    if not selected_journal.path.exists():
        raise LiveSafetyError("No pending live-QA cleanup journal exists")
    plan, started_at, captured = selected_journal.load()
    database = management_client or ManagementDatabaseClient(
        config.supabase_cli,
        config.linked_cli_workdir,
    )
    resolve_missing_captures(database, selected_journal, plan, started_at, captured)
    if not captured:
        selected_journal.remove()
        selected_handoff.remove()
        if require_completed_contract:
            raise LiveSafetyError("Emulator finalization found no captured QA users")
        return 0
    contract_failure: BaseException | None = None
    if require_completed_contract:
        try:
            if len(captured) != 2:
                raise LiveSafetyError("Emulator finalization requires exactly two captured QA users")
            assert_completed_audit(
                scoped_audit(database, captured, started_at),
                allow_additional_sessions=True,
            )
        except BaseException as error:
            contract_failure = error
    deleted = cleanup_captured(database, captured, started_at)
    selected_journal.remove()
    selected_handoff.remove()
    if contract_failure is not None:
        raise contract_failure
    return deleted


def run_offline_self_test() -> None:
    plan = make_plan("260907ABCD")
    assert plan.requester.character_id != plan.opponent.character_id
    assert plan.requester.display_name == "AQ-A-260907ABCD"
    assert plan.opponent.display_name == "AQ-B-260907ABCD"
    profile = build_live_snapshot(plan.requester)
    assert profile["level"] == LIVE_LEVEL
    assert profile["slot_id"] == 1
    validate_profile_receipt({
        "accepted": True,
        "profile_version": 1,
        "rules_version": preview.RULES_VERSION,
        "synced_count": 1,
        "ranking_synced_count": 1,
        "server_now": 1_788_782_400_000,
        "deduplicated": False,
    })
    standing = build_live_arena_standing(plan.requester)
    validate_arena_sync_receipt({
        "accepted": True,
        "season_id": "1",
        "rules_version": preview.RULES_VERSION,
        "server_now": 1_788_782_400_000,
        "deduplicated": False,
    })
    validate_arena_standing(plan.requester.character_id, standing)

    daily_base = {
        "snapshot_id": "utc:1788739200000",
        "settled_at": 1_788_739_200_000,
        "next_settlement_at": 1_788_825_600_000,
        "generated_at": 1_788_739_201_000,
        "server_now": 1_788_782_400_000,
        "is_bootstrap": False,
        "unchanged": False,
        "t": 1,
    }
    general = {
        **daily_base,
        "e": [{
            "r": 1,
            "i": 0,
            "c": "33333333-3333-4333-8333-333333333333",
            "n": "ExistingRanker",
            "h": "MAGE",
            "l": 100,
            "p": 1000,
            "a": 1_788_700_000_000,
        }],
        "o": [],
    }
    assert validate_general_daily_leaderboard(general, plan) == 1
    general_unchanged = {
        **{key: value for key, value in daily_base.items() if key != "unchanged"},
        "unchanged": True,
        "server_now": daily_base["server_now"] + 1,
    }
    assert_conditional_daily_snapshot(general, general_unchanged, GENERAL_DAILY_BASE_FIELDS)

    arena_base = {
        **daily_base,
        "snapshot_id": "arena:s1:r1:utc:1788739200000",
        "season_id": "1",
        "rules_version": preview.RULES_VERSION,
    }
    arena = {
        **arena_base,
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
    assert validate_arena_daily_leaderboard(arena, plan) == 1
    arena_unchanged = {
        **{key: value for key, value in arena_base.items() if key != "unchanged"},
        "unchanged": True,
        "server_now": arena_base["server_now"] + 1,
    }
    assert_conditional_daily_snapshot(arena, arena_unchanged, ARENA_DAILY_BASE_FIELDS)
    assert preview.SYNC_SNAPSHOTS_PATH not in ALLOWED_RPC_PATHS
    try:
        load_live_config({})
        raise AssertionError("missing acknowledgement must fail")
    except LiveSafetyError:
        pass
    print("PASS offline live-safe shared-player E2E self-test (network not used)")


def _extract_management_rows(decoded: Any) -> list[Mapping[str, Any]]:
    if isinstance(decoded, list):
        return decoded
    if isinstance(decoded, dict):
        for key in ("result", "data", "rows"):
            candidate = decoded.get(key)
            if isinstance(candidate, list):
                return candidate
    raise LiveSafetyError("Management database query returned an unknown response envelope")


def validate_linked_cli(cli_path: Path, linked_workdir: Path) -> None:
    try:
        if cli_path.resolve(strict=True) != SUPABASE_CLI_PATH.resolve(strict=True):
            raise LiveSafetyError("Live QA refused an unreviewed Supabase CLI binary")
        if not os.access(cli_path, os.X_OK):
            raise LiveSafetyError("Reviewed Supabase CLI binary is not executable")
        if linked_workdir.resolve(strict=True) != LINKED_CLI_WORKDIR.resolve(strict=True):
            raise LiveSafetyError("Live QA refused an unreviewed linked CLI work directory")
        linked_ref = (
            linked_workdir / "supabase" / ".temp" / "project-ref"
        ).read_text(encoding="utf-8").strip()
    except LiveSafetyError:
        raise
    except Exception:
        raise LiveSafetyError("Reviewed linked Supabase CLI context is unavailable") from None
    if linked_ref != PRODUCTION_PROJECT_REF:
        raise LiveSafetyError("Linked Supabase CLI project ref does not match the live acknowledgement")
    if not (linked_workdir / "supabase" / "config.toml").is_file():
        raise LiveSafetyError("Linked Supabase CLI context is missing config.toml")


def render_constrained_sql(sql: str, parameters: Sequence[Any]) -> str:
    placeholder_numbers = {int(value) for value in re.findall(r"\$(\d+)", sql)}
    expected = set(range(1, len(parameters) + 1))
    if placeholder_numbers != expected:
        raise LiveSafetyError("Owner query placeholders do not match constrained parameters")
    rendered = sql
    for index in range(len(parameters), 0, -1):
        rendered = re.sub(
            rf"\${index}(?!\d)",
            _constrained_sql_literal(parameters[index - 1]),
            rendered,
        )
    if re.search(r"\$\d+", rendered):
        raise LiveSafetyError("Owner query retained an unresolved parameter")
    return rendered


def _constrained_sql_literal(value: Any) -> str:
    if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 10_000:
        return str(value)
    if not isinstance(value, str) or len(value) not in range(1, 161):
        raise LiveSafetyError("Owner query received a non-constrained parameter")
    if value.startswith(("sb_", "sbp_")):
        raise LiveSafetyError("Owner query refused credential-like parameter material")
    if not re.fullmatch(r"[A-Za-z0-9@._:+-]+", value):
        raise LiveSafetyError("Owner query parameter contains unreviewed characters")
    return "'" + value + "'"


def validate_rendered_sql(sql: str, *, read_only: bool) -> None:
    normalized = sql.lower()
    if not normalized.lstrip().startswith(("select", "with")):
        raise LiveSafetyError("Owner query must start with SELECT or WITH")
    mutations = re.findall(
        r"\b(delete\s+from|insert\s+into|update|drop|truncate|alter|create|grant|revoke|copy)\b",
        normalized,
    )
    if read_only:
        if mutations:
            raise LiveSafetyError("Read-only owner audit contains a mutation")
        return
    deletes = re.findall(r"\bdelete\s+from\s+([a-z0-9_.]+)", normalized)
    if deletes != ["auth.users"] or any(item != "delete from" for item in mutations):
        raise LiveSafetyError("Cleanup owner query may delete only from auth.users")


def _safe_cli_environment(environ: Mapping[str, str]) -> dict[str, str]:
    allowed = (
        "HOME",
        "PATH",
        "LANG",
        "LC_ALL",
        "TMPDIR",
        "SUPABASE_ACCESS_TOKEN",
        "SUPABASE_DB_PASSWORD",
        "SSL_CERT_FILE",
        "SSL_CERT_DIR",
    )
    result = {key: environ[key] for key in allowed if environ.get(key)}
    result["SUPABASE_TELEMETRY_DISABLED"] = "true"
    return result


def _audit_row(rows: Sequence[Mapping[str, Any]]) -> dict[str, int]:
    if len(rows) != 1 or set(rows[0]) != {"audit"} or not isinstance(rows[0]["audit"], Mapping):
        raise LiveSafetyError("Management database audit returned an unexpected shape")
    result: dict[str, int] = {}
    for key, value in rows[0]["audit"].items():
        if not isinstance(key, str) or not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise LiveSafetyError("Management database audit returned an invalid count")
        result[key] = value
    return result


def _assert_exact_counts(label: str, actual: Mapping[str, int], expected: Mapping[str, int]) -> None:
    if dict(actual) != dict(expected):
        raise LiveSafetyError(f"{label} counts differed from the reviewed contract")


def _target_values(captured: Sequence[CapturedIdentity]) -> tuple[str, list[Any]]:
    if not captured or len(captured) > 2:
        raise LiveSafetyError("Audit target count must be one or two captured Auth users")
    placeholders: list[str] = []
    params: list[Any] = []
    for index, item in enumerate(captured):
        if not preview.is_canonical_uuid(item.user_id):
            raise LiveSafetyError("Captured Auth user identity is invalid")
        offset = index * 6
        placeholders.append(
            f"(${offset + 1}::uuid,${offset + 2}::uuid,${offset + 3}::text,"
            f"${offset + 4}::text,${offset + 5}::text,${offset + 6}::text)"
        )
        run_tag = item.planned.display_name.rsplit("-", 1)[-1]
        params.extend((
            item.user_id,
            item.planned.character_id,
            item.planned.display_name,
            item.planned.email,
            item.planned.role,
            run_tag,
        ))
    return ",".join(placeholders), params


def _journal_payload(
    plan: LivePlan,
    started_at: str,
    captured: Sequence[CapturedIdentity],
) -> dict[str, Any]:
    return {
        "version": 1,
        "project_ref": PRODUCTION_PROJECT_REF,
        "run_tag": plan.run_tag,
        "started_at": started_at,
        "captured": [
            {"role": item.planned.role, "user_id": item.user_id}
            for item in captured
        ],
    }


def _parse_journal_payload(payload: Any) -> tuple[LivePlan, str, list[CapturedIdentity]]:
    if not isinstance(payload, dict) or set(payload) != {
        "version", "project_ref", "run_tag", "started_at", "captured"
    }:
        raise LiveSafetyError("Pending live-QA cleanup journal has an invalid shape")
    if payload["version"] != 1 or payload["project_ref"] != PRODUCTION_PROJECT_REF:
        raise LiveSafetyError("Pending live-QA cleanup journal targets a different contract")
    plan = make_plan(str(payload["run_tag"]))
    started_at = payload["started_at"]
    try:
        parsed_started = datetime.fromisoformat(started_at)
    except (TypeError, ValueError):
        raise LiveSafetyError("Pending live-QA cleanup journal has invalid time metadata") from None
    if parsed_started.tzinfo is None:
        raise LiveSafetyError("Pending live-QA cleanup journal time must include UTC offset")
    rows = payload["captured"]
    if not isinstance(rows, list) or len(rows) > 2:
        raise LiveSafetyError("Pending live-QA cleanup journal has invalid target count")
    planned_by_role = {item.role: item for item in plan.identities}
    captured: list[CapturedIdentity] = []
    seen_roles: set[str] = set()
    seen_users: set[str] = set()
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"role", "user_id"}:
            raise LiveSafetyError("Pending live-QA cleanup journal has an invalid target")
        role = row["role"]
        user_id = row["user_id"]
        if role not in planned_by_role or role in seen_roles or not preview.is_canonical_uuid(user_id):
            raise LiveSafetyError("Pending live-QA cleanup journal target did not validate")
        if user_id in seen_users:
            raise LiveSafetyError("Pending live-QA cleanup journal contains duplicate targets")
        seen_roles.add(role)
        seen_users.add(user_id)
        captured.append(CapturedIdentity(planned_by_role[role], user_id))
    return plan, started_at, captured


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--self-test", action="store_true", help="run network-free checks only")
    modes.add_argument(
        "--prepare-emulator",
        action="store_true",
        help="provision and validate two QA users, then retain a protected emulator handoff",
    )
    modes.add_argument(
        "--finalize-cleanup",
        action="store_true",
        help="re-audit a completed emulator run, delete its two exact QA users, and prove zero remnants",
    )
    modes.add_argument(
        "--recover-cleanup",
        action="store_true",
        help="delete only identities captured in the protected pending journal",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    try:
        if args.self_test:
            run_offline_self_test()
            return 0
        config = load_live_config(os.environ)
        if args.recover_cleanup:
            deleted = recover_cleanup(config)
            print(f"PASS recovered cleanup for captured Auth users: {deleted}")
            print("PASS verified player-network, ranking, arena, and daily-snapshot QA remnants: 0")
            return 0
        if args.finalize_cleanup:
            deleted = recover_cleanup(config, require_completed_contract=True)
            print(f"PASS finalized cleanup for captured Auth users: {deleted}")
            print("PASS verified player-network, ranking, arena, and daily-snapshot QA remnants: 0")
            return 0
        summary = run_live(config, retain_for_emulator=args.prepare_emulator)
        print(f"PASS live QA run tag: {summary.run_tag}")
        print("PASS isolated email/password QA sessions and unified profiles: 2")
        print(f"PASS immutable general daily entries checked: {summary.general_daily_entries}")
        print(f"PASS immutable arena daily entries checked: {summary.arena_daily_entries}")
        print(f"PASS PUBLIC_ROSTER candidates: {summary.public_candidates}")
        print("PASS roster freeze and both metadata-only conditional reads")
        print("PASS arena aggregate syncs accepted without match, ticket, result, or reward payloads")
        if args.prepare_emulator:
            print("PASS protected emulator credential handoff prepared")
            print("PENDING emulator test and --finalize-cleanup")
            return 0
        print(f"PASS deleted captured Auth users: {summary.deleted_auth_users}")
        print("PASS verified player-network, ranking, arena, and daily-snapshot QA remnants: 0")
        return 0
    except (LiveSafetyError, preview.PreviewError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        return 2
    except Exception as error:
        print(f"FAIL unexpected live-QA error ({type(error).__name__}); no details were shown", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
