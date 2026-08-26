#!/usr/bin/env python3
"""Serve the source-backed NULL PLAYING VFX lab without copying Android assets."""

from __future__ import annotations

import json
import mimetypes
import subprocess
import threading
from datetime import datetime, timezone
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Collection
from urllib.parse import parse_qs, unquote, urlparse

from generate_data import ASSETS, OUT, generate, load_frame_cache, load_presentation_cache, write_payload_atomically


LAB = Path(__file__).resolve().parent
HOST = "0.0.0.0"
PORT = 4173
HIT_SETTINGS_PATH = LAB / "data/skill-hit-overrides.json"
HIT_SETTINGS_SCHEMA_VERSION = 1
MAX_HIT_COUNT = 12
MAX_HIT_TIMING_MILLIS = 900
MAX_JSON_REQUEST_BYTES = 64 * 1024

mimetypes.add_type("image/webp", ".webp")

FRAME_CACHE: dict[str, dict[str, list[dict]]] = {}
PRESENTATION_CACHE: dict[str, dict[str, list[dict]]] = {}
HIT_SETTINGS_LOCK = threading.Lock()


class HitSettingsValidationError(ValueError):
    pass


class HitSettingsStoreError(RuntimeError):
    pass


class SpritePathValidationError(ValueError):
    pass


class SpriteRevealError(RuntimeError):
    pass


def _utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def app_hit_weights(hit_count: int) -> list[int]:
    """Mirror SkillCatalog.weightsFor so saved rows can be applied to Android directly."""
    fixed = {
        1: [100],
        2: [48, 52],
        3: [28, 32, 40],
        4: [20, 22, 25, 33],
        5: [15, 17, 18, 20, 30],
    }
    if hit_count in fixed:
        return fixed[hit_count].copy()
    weights = [100 // hit_count] * hit_count
    for offset in range(100 - sum(weights)):
        weights[hit_count - 1 - (offset % hit_count)] += 1
    finisher_boost = min(12, (hit_count - 1) * (weights[0] - 1))
    for offset in range(finisher_boost):
        weights[offset % (hit_count - 1)] -= 1
        weights[-1] += 1
    return weights


def validate_hit_setting(payload: Any, valid_catalog_ids: Collection[str]) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise HitSettingsValidationError("저장 데이터는 JSON 객체여야 합니다.")
    catalog_id = payload.get("catalogId")
    if not isinstance(catalog_id, str) or not catalog_id:
        raise HitSettingsValidationError("catalogId가 필요합니다.")
    if catalog_id not in valid_catalog_ids:
        raise HitSettingsValidationError(f"알 수 없는 catalogId입니다: {catalog_id}")

    hit_count = payload.get("hitCount")
    if isinstance(hit_count, bool) or not isinstance(hit_count, int):
        raise HitSettingsValidationError("타수는 정수여야 합니다.")
    if hit_count not in range(1, MAX_HIT_COUNT + 1):
        raise HitSettingsValidationError(f"타수는 1~{MAX_HIT_COUNT}여야 합니다.")

    timings = payload.get("hitTimingsMillis")
    if not isinstance(timings, list) or len(timings) != hit_count:
        raise HitSettingsValidationError("타이밍 개수는 타수와 같아야 합니다.")
    if any(isinstance(value, bool) or not isinstance(value, int) for value in timings):
        raise HitSettingsValidationError("각 타이밍은 정수(ms)여야 합니다.")
    if any(value < 0 or value > MAX_HIT_TIMING_MILLIS for value in timings):
        raise HitSettingsValidationError(
            f"각 타이밍은 0~{MAX_HIT_TIMING_MILLIS}ms 범위여야 합니다."
        )
    if any(current >= following for current, following in zip(timings, timings[1:])):
        raise HitSettingsValidationError("타이밍은 앞에서부터 엄격히 커져야 합니다.")

    return {
        "catalogId": catalog_id,
        "hitCount": hit_count,
        "hitWeights": app_hit_weights(hit_count),
        "hitTimingsMillis": timings,
    }


def empty_hit_settings() -> dict[str, Any]:
    return {"schemaVersion": HIT_SETTINGS_SCHEMA_VERSION, "updatedAt": None, "skills": {}}


def resolve_sprite_path(
    catalog_id: Any,
    sprite_path: Any,
    valid_catalog_ids: Collection[str],
    lab: Path = LAB,
) -> Path:
    if not isinstance(catalog_id, str) or catalog_id not in valid_catalog_ids:
        raise SpritePathValidationError("알 수 없는 catalogId입니다.")
    if not isinstance(sprite_path, str) or not sprite_path:
        raise SpritePathValidationError("현재 스프라이트 경로가 없습니다.")

    parsed = urlparse(sprite_path)
    if parsed.scheme or parsed.netloc:
        raise SpritePathValidationError("로컬 스프라이트 경로만 열 수 있습니다.")
    relative = Path(unquote(parsed.path.lstrip("/")))
    if not relative.parts or relative.parts[0] != "custom-assets":
        raise SpritePathValidationError("검토된 custom-assets 경로만 열 수 있습니다.")

    allowed_root = (lab / "custom-assets").resolve()
    candidate = (lab / relative).resolve()
    if allowed_root not in candidate.parents:
        raise SpritePathValidationError("허용된 스프라이트 경로를 벗어났습니다.")
    if candidate.stem != catalog_id:
        raise SpritePathValidationError("catalogId와 스프라이트 파일명이 다릅니다.")
    if not candidate.is_file():
        raise SpritePathValidationError("스프라이트 파일을 찾을 수 없습니다.")
    return candidate


def reveal_sprite_in_finder(
    catalog_id: Any,
    sprite_path: Any,
    valid_catalog_ids: Collection[str],
    lab: Path = LAB,
    opener: Any = None,
) -> Path:
    candidate = resolve_sprite_path(catalog_id, sprite_path, valid_catalog_ids, lab)
    run = subprocess.run if opener is None else opener
    try:
        run(["open", "-R", str(candidate)], check=True, capture_output=True, timeout=5)
    except (OSError, subprocess.SubprocessError) as error:
        raise SpriteRevealError(f"Finder에서 스프라이트를 열지 못했습니다: {error}") from error
    return candidate


def load_hit_settings(
    path: Path = HIT_SETTINGS_PATH,
    valid_catalog_ids: Collection[str] | None = None,
) -> dict[str, Any]:
    if not path.exists():
        return empty_hit_settings()
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise HitSettingsStoreError(f"타격 설정 파일을 읽을 수 없습니다: {error}") from error
    if not isinstance(payload, dict) or payload.get("schemaVersion") != HIT_SETTINGS_SCHEMA_VERSION:
        raise HitSettingsStoreError("타격 설정 스키마 버전이 올바르지 않습니다.")
    stored_skills = payload.get("skills")
    if not isinstance(stored_skills, dict):
        raise HitSettingsStoreError("타격 설정의 skills는 JSON 객체여야 합니다.")
    if valid_catalog_ids is not None:
        try:
            for catalog_id, setting in stored_skills.items():
                if not isinstance(setting, dict) or setting.get("catalogId") != catalog_id:
                    raise HitSettingsValidationError(f"저장 키와 catalogId가 다릅니다: {catalog_id}")
                validate_hit_setting(setting, valid_catalog_ids)
        except HitSettingsValidationError as error:
            raise HitSettingsStoreError(str(error)) from error
    return payload


def save_hit_setting(
    payload: Any,
    path: Path = HIT_SETTINGS_PATH,
    valid_catalog_ids: Collection[str] = (),
) -> tuple[dict[str, Any], dict[str, Any]]:
    setting = validate_hit_setting(payload, valid_catalog_ids)
    settings = load_hit_settings(path, valid_catalog_ids)
    timestamp = _utc_timestamp()
    saved_setting = {**setting, "updatedAt": timestamp}
    settings["updatedAt"] = timestamp
    settings["skills"][setting["catalogId"]] = saved_setting
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        temporary.write_text(
            json.dumps(settings, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        temporary.replace(path)
    except OSError as error:
        raise HitSettingsStoreError(f"타격 설정을 저장하지 못했습니다: {error}") from error
    return settings, saved_setting


class VfxLabHandler(SimpleHTTPRequestHandler):
    def send_json(self, status: int, body: dict[str, Any]) -> None:
        payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/hit-settings":
            try:
                settings = load_hit_settings(HIT_SETTINGS_PATH, FRAME_CACHE.keys())
            except HitSettingsStoreError as error:
                self.send_json(500, {"ok": False, "error": str(error)})
                return
            self.send_json(200, settings)
            return
        if parsed.path == "/api/frames":
            query = parse_qs(parsed.query)
            catalog_id = query.get("skill", [""])[0]
            reduced = query.get("reduced", ["0"])[0]
            rows = FRAME_CACHE.get(catalog_id, {}).get(reduced)
            if rows is None:
                self.send_error(404, "Unknown skill or motion mode")
                return
            payload = json.dumps({"catalogId": catalog_id, "reduced": reduced == "1", "frames": rows}, separators=(",", ":")).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        if parsed.path == "/api/presentation":
            query = parse_qs(parsed.query)
            catalog_id = query.get("skill", [""])[0]
            reduced = query.get("reduced", ["0"])[0]
            rows = PRESENTATION_CACHE.get(catalog_id, {}).get(reduced)
            if rows is None:
                self.send_error(404, "Unknown skill or motion mode")
                return
            payload = json.dumps(
                {"catalogId": catalog_id, "reduced": reduced == "1", "samples": rows},
                separators=(",", ":"),
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        super().do_GET()

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path not in {"/api/hit-settings", "/api/reveal-sprite"}:
            self.send_error(404, "Unknown API endpoint")
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_json(400, {"ok": False, "error": "Content-Length가 올바르지 않습니다."})
            return
        if content_length <= 0 or content_length > MAX_JSON_REQUEST_BYTES:
            self.send_json(400, {"ok": False, "error": "저장 요청 크기가 올바르지 않습니다."})
            return
        try:
            request_payload = json.loads(self.rfile.read(content_length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.send_json(400, {"ok": False, "error": "올바른 JSON을 보내주세요."})
            return
        if parsed.path == "/api/reveal-sprite":
            try:
                candidate = reveal_sprite_in_finder(
                    request_payload.get("catalogId") if isinstance(request_payload, dict) else None,
                    request_payload.get("spritePath") if isinstance(request_payload, dict) else None,
                    FRAME_CACHE.keys(),
                )
            except SpritePathValidationError as error:
                self.send_json(400, {"ok": False, "error": str(error)})
                return
            except SpriteRevealError as error:
                self.send_json(500, {"ok": False, "error": str(error)})
                return
            self.send_json(
                200,
                {
                    "ok": True,
                    "catalogId": request_payload["catalogId"],
                    "relativePath": candidate.relative_to(LAB).as_posix(),
                },
            )
            return
        try:
            with HIT_SETTINGS_LOCK:
                _, saved_setting = save_hit_setting(
                    request_payload,
                    HIT_SETTINGS_PATH,
                    FRAME_CACHE.keys(),
                )
        except HitSettingsValidationError as error:
            self.send_json(400, {"ok": False, "error": str(error)})
            return
        except HitSettingsStoreError as error:
            self.send_json(500, {"ok": False, "error": str(error)})
            return
        self.send_json(200, {"ok": True, "setting": saved_setting})

    def translate_path(self, path: str) -> str:
        request_path = Path(unquote(urlparse(path).path.lstrip("/")))
        if request_path.parts and request_path.parts[0] == "assets":
            relative = Path(*request_path.parts[1:])
            candidate = (ASSETS / relative).resolve()
            if ASSETS.resolve() not in candidate.parents:
                return str(ASSETS / "__invalid__")
            return str(candidate)
        candidate = (LAB / request_path).resolve()
        if LAB.resolve() not in candidate.parents and candidate != LAB.resolve():
            return str(LAB / "__invalid__")
        return str(candidate)

    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        super().end_headers()


def sync_data() -> None:
    global FRAME_CACHE, PRESENTATION_CACHE
    OUT.parent.mkdir(parents=True, exist_ok=True)
    payload = generate()
    write_payload_atomically(payload)
    FRAME_CACHE = load_frame_cache()
    PRESENTATION_CACHE = load_presentation_cache()


if __name__ == "__main__":
    sync_data()
    server = ThreadingHTTPServer((HOST, PORT), VfxLabHandler)
    print(f"NULL PLAYING VFX Lab (Mac): http://127.0.0.1:{PORT}")
    print(f"NULL PLAYING VFX Lab (LAN): http://<Mac LAN IPv4>:{PORT}")
    print(f"Android assets: {ASSETS}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
