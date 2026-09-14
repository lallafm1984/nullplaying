#!/usr/bin/env python3
"""Aggregate observed loadouts without treating finite samples as probability guarantees."""
import collections
import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "output/verification/2026-09-15/equipment"
DEST = ROOT / "docs/releases/2026-09-15-source-verification"


def read(name):
    with (SOURCE / name).open() as stream:
        return list(csv.DictReader(stream))


def main():
    rows, characters, full_sets = read("levels.csv"), read("characters.csv"), read("full-sets.csv")
    assert len(characters) == 36
    assert len({(r["cohort"], r["class"], r["seed"], r["start_level"]) for r in characters}) == 36
    assert len(rows) == 24 * 150 + 12 * 6
    grouped = collections.defaultdict(list)
    for row in rows:
        grouped[(row["cohort"], int(row["level"]))].append(row)
    DEST.mkdir(parents=True, exist_ok=True)
    aggregates = []
    for (cohort, level), samples in sorted(grouped.items()):
        n = len(samples)
        durations = [sum(int(r[f"ms_{slot}"]) for r in samples) for slot in range(7)]
        aggregates.append(dict(cohort=cohort, level=level, characters=n,
            entry_mean_plus4=round(sum(int(r["entry_plus4"]) for r in samples) / n, 4),
            entry_mean_plus5=round(sum(int(r["entry_plus5"]) for r in samples) / n, 4),
            max_high_slots=max(int(r["max_high_slots"]) for r in samples),
            characters_full_ever=sum(r["full_observed"] == "true" for r in samples),
            actions=sum(int(r["actions"]) for r in samples),
            observed_millis=sum(durations), full_set_millis=durations[6],
            full_set_time_percent=round(100 * durations[6] / sum(durations), 6) if sum(durations) else ""))
    with (DEST / "equipment-by-level.csv").open("w") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(aggregates[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(aggregates)
    summary = {}
    for cohort in ("natural", "synthetic_high"):
        sample = [r for r in characters if r["cohort"] == cohort]
        levels = [r for r in aggregates if r["cohort"] == cohort]
        summary[cohort] = dict(characters=len(sample), actions=sum(int(r["actions"]) for r in sample),
            kills=sum(int(r["kills"]) for r in sample),
            max_high_slots=max(int(r["max_high_slots"]) for r in sample),
            characters_full_ever=sum(int(r["full_levels"]) > 0 for r in sample),
            levels_with_full_sets=[r["level"] for r in levels if r["characters_full_ever"]],
            full_set_time_percent=100 * sum(r["full_set_millis"] for r in levels) / sum(r["observed_millis"] for r in levels))
    # One example per character/level; these do not enumerate later within-level permutations.
    summary["examples_all_plus4"] = sum(r["items"].count(":전설:") == 6 for r in full_sets)
    summary["examples_all_plus5"] = sum(r["items"].count(":신화:") == 6 for r in full_sets)
    # These examples contain synthetic seeds and gear only, never account/device data.
    for name in ["characters.csv", "full-sets.csv"]:
        (DEST / name).write_bytes((SOURCE / name).read_bytes())
    (DEST / "equipment-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
