#!/usr/bin/env python3
"""Serve the source-backed NULL PLAYING VFX lab without copying Android assets."""

from __future__ import annotations

import json
import mimetypes
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

from generate_data import ASSETS, OUT, generate, load_frame_cache, load_presentation_cache, write_payload_atomically


LAB = Path(__file__).resolve().parent
HOST = "127.0.0.1"
PORT = 4173

mimetypes.add_type("image/webp", ".webp")

FRAME_CACHE: dict[str, dict[str, list[dict]]] = {}
PRESENTATION_CACHE: dict[str, dict[str, list[dict]]] = {}


class VfxLabHandler(SimpleHTTPRequestHandler):
    def do_GET(self) -> None:
        parsed = urlparse(self.path)
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
    print(f"NULL PLAYING VFX Lab: http://{HOST}:{PORT}")
    print(f"Android assets: {ASSETS}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
