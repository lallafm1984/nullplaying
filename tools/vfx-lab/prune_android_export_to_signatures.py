#!/usr/bin/env python3
"""Remove obsolete candidate rows from the browser's Android export cache."""

from __future__ import annotations

import csv
import json
from pathlib import Path


LAB = Path(__file__).resolve().parent
EXPORT = LAB / "data/android-export"
MANIFEST = LAB / "data/signature-skills.json"


def signature_ids() -> set[str]:
    payload = json.loads(MANIFEST.read_text(encoding="utf-8"))
    ids = {skill["id"] for skill in payload.get("skills", [])}
    if len(ids) != 120:
        raise SystemExit(f"Expected 120 signature IDs, found {len(ids)}")
    return ids


def filter_psv(path: Path, allowed: set[str]) -> tuple[int, int]:
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="|")
        rows = list(reader)
        fieldnames = reader.fieldnames
    if not fieldnames or "catalogId" not in fieldnames:
        raise SystemExit(f"Missing catalogId column: {path}")
    kept = [row for row in rows if row["catalogId"] in allowed]
    found = {row["catalogId"] for row in kept}
    if found != allowed:
        raise SystemExit(f"{path.name} is missing {len(allowed - found)} signature IDs")
    temporary = path.with_suffix(".tmp.psv")
    with temporary.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames, delimiter="|", lineterminator="\n")
        writer.writeheader()
        writer.writerows(kept)
    temporary.replace(path)
    return len(rows), len(kept)


def main() -> None:
    allowed = signature_ids()
    for filename in ("skills.psv", "presentation.psv"):
        before, after = filter_psv(EXPORT / filename, allowed)
        print(f"{filename}: {before} -> {after} rows")


if __name__ == "__main__":
    main()
