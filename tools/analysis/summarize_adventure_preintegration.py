#!/usr/bin/env python3
"""Read existing isolated QA CSVs; no simulation, builds, database or network access."""
import argparse
import collections
import csv
import gzip
import json
import statistics
from pathlib import Path


def main():
    qa = Path(__file__).resolve().parents[3] / "qa"
    assert qa.parent.name == "adventure-20260906" and qa.parent.parent.name == "preintegration"
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", default=".", choices=[".", "before-trait-lifecycle-stability"])
    args = parser.parse_args()
    source = (qa / args.source).resolve()
    assert source == qa or source.parent == qa
    rows = list(csv.DictReader((source / "adventure-final-economy.csv").open()))
    traits = list(csv.DictReader((source / "adventure-final-natural-traits.csv").open()))
    assert len(rows) == 384 and len(traits) == 4992
    assert len({r["seed"] for r in rows}) == 16
    metrics = ["xp_per_hour", "items_per_hour", "equipment_per_hour", "cash_earned_per_hour",
               "earned_plus_ending_bag_value", "ending_bag_base_value", "acquired_candidate_base_value",
               "sale_base_gold", "sale_actual_gold", "sale_trait_gold_delta", "direct_event_gold", "other_gold",
               "purchase_gold", "ending_gold", "start_cp", "end_cp", "equipment_value_change", "loot_upgrades",
               "purchases", "returns", "events", "relationships", "trait_acquisitions", "trait_losses",
               "owned_traits_at_end", "extra_shop_purchases", "all_recent_events", "all_trait_changes",
               "recent_300_count", "recent_300_trait_changes", "recent_300_trait_activations",
               "event_equipment", "event_reward_experience", "event_reward_gold", "event_reward_item",
               "event_reward_route", "event_reward_unspecified", "event_failures",
               "route_reward_results_awarded", "route_reward_uses_awarded",
               "route_shortening_millis_awarded", "route_reward_uses_remaining",
               "route_shortening_millis_remaining"] + [f"route_reward_uses_{uses}" for uses in range(2, 11)]
    metrics = [key for key in metrics if key in rows[0]]
    summaries = []
    for scenario in sorted({r["scenario"] for r in rows}):
        for hero_class in ["ALL"] + sorted({r["class"] for r in rows}):
            group = [r for r in rows if r["scenario"] == scenario and (hero_class == "ALL" or r["class"] == hero_class)]
            result = {"scenario": scenario, "class": hero_class, "metrics": {}}
            for metric in metrics:
                before, after = [statistics.mean(float(r[metric]) for r in group if r["mode"] == mode) for mode in ["OFF", "ON"]]
                result["metrics"][metric] = {"off": before, "on": after, "delta": after - before,
                                             "percent": (after / before - 1) * 100 if before else None}
            result["cp_gain"] = {mode: statistics.mean(float(r["end_cp"]) - float(r["start_cp"]) for r in group if r["mode"] == mode) for mode in ["OFF", "ON"]}
            if "recent_300_count" in group[0]:
                on = [r for r in group if r["mode"] == "ON"]
                denominator = sum(int(r["recent_300_count"]) for r in on)
                result["recent_300_trait_change_fraction"] = sum(int(r["recent_300_trait_changes"]) for r in on) / denominator if denominator else 0
            summaries.append(result)
    trait_summary = []
    for trait in sorted({r["trait"] for r in traits}):
        group = [r for r in traits if r["mode"] == "ON" and r["trait"] == trait]
        first = [float(r["first_acquired_hour"]) for r in group if r["first_acquired_hour"]]
        result = {"trait": trait, "cohorts_acquired": len(first), "cohorts_owned_at_end": sum(r["owned_at_end"] == "true" for r in group)}
        result.update({key: sum(float(r[key]) for r in group) for key in ["held_hours", "opportunities", "passed_rolls", "actual_effects", "acquired", "lost_or_replaced", "weakened", "recovered"]})
        result["first_acquired_hours"] = {"min": min(first), "median": statistics.median(first), "max": max(first)} if first else None
        trait_summary.append(result)
    sources = collections.defaultdict(lambda: {"count": 0, "value": 0, "equipped": 0, "rarity": collections.Counter(), "slot": collections.Counter()})
    changes = collections.defaultdict(list)
    for row in csv.DictReader(gzip.open(source / "adventure-final-economy-ledger.csv.gz", "rt")):
        if row["operation"] == "ACQUIRE":
            label = row["source"]
            category = label.split(":")[0].upper() + "_" + label.split("|")[-1] if "|" in label else label
            group = sources[(row["scenario"], row["mode"], category)]
            group["count"] += 1
            group["value"] += int(row["base_sale_value"])
            group["equipped"] += row["equipped"] == "true"
            group["rarity"][row["rarity"]] += 1
            group["slot"][row["slot"] or "TROPHY"] += 1
        if row["operation"] in ["TRAIT_WEAKENED", "TRAIT_RECOVERED"]:
            changes[(row["scenario"], row["class"], row["seed"], row["name"])].append((int(row["at"]), row["source"]))
    churn = []
    for trait in trait_summary:
        sequences = [v for key, v in changes.items() if key[-1] == trait["trait"]]
        gaps = [(b[0] - a[0]) / 1000 for values in sequences for a, b in zip(values, values[1:])]
        same = sum(a[1] == b[1] for values in sequences for a, b in zip(values, values[1:]))
        count = int(trait["weakened"] + trait["recovered"])
        churn.append({"trait": trait["trait"], "changes": count, "changes_per_held_hour": count / trait["held_hours"] if trait["held_hours"] else None,
                      "same_source_repeats": same, "gaps": len(gaps), "gap_min_seconds": min(gaps) if gaps else None,
                      "gap_median_seconds": statistics.median(gaps) if gaps else None,
                      "gap_p95_seconds": sorted(gaps)[int(.95 * (len(gaps) - 1))] if gaps else None,
                      "gaps_under_60_seconds": sum(gap < 60 for gap in gaps)})
    for row in rows:
        assert int(row["bag_input_base_value"]) == int(row["sale_base_gold"]) + int(row["ending_bag_base_value"])
        assert int(row["ending_gold"]) == int(row["sale_actual_gold"]) + int(row["direct_event_gold"]) + int(row["other_gold"]) - int(row["purchase_gold"])
    enabled_rows = [row for row in rows if row["mode"] == "ON"]
    active_hours = sum(float(row["active_hours"]) for row in enabled_rows)
    completed_events = sum(int(row["events"]) for row in enabled_rows)
    reward_counts = {
        kind: sum(int(row[f"event_reward_{kind}"]) for row in enabled_rows)
        for kind in ["experience", "gold", "item", "route", "unspecified"]
    }
    assert sum(reward_counts[kind] for kind in ["experience", "gold", "item", "route"]) == completed_events
    assert reward_counts["unspecified"] == 0
    route_histogram = {
        str(uses): sum(int(row[f"route_reward_uses_{uses}"]) for row in enabled_rows)
        for uses in range(2, 11)
    }
    route_awards = sum(int(row["route_reward_results_awarded"]) for row in enabled_rows)
    route_uses = sum(int(row["route_reward_uses_awarded"]) for row in enabled_rows)
    assert sum(route_histogram.values()) == route_awards
    assert sum(int(uses) * count for uses, count in route_histogram.items()) == route_uses
    event_equipment = sum(int(row["event_equipment"]) for row in enabled_rows)
    event_policy = {
        "active_hours": active_hours,
        "events": completed_events,
        "events_per_hour": completed_events / active_hours,
        "minutes_per_event": active_hours * 60 / completed_events,
        "events_per_six_hours": {
            "min": min(int(row["events"]) for row in enabled_rows),
            "median": statistics.median(int(row["events"]) for row in enabled_rows),
            "mean": statistics.mean(int(row["events"]) for row in enabled_rows),
            "max": max(int(row["events"]) for row in enabled_rows),
        },
        "event_equipment": event_equipment,
        "hours_per_event_equipment": active_hours / event_equipment,
        "reward_counts": reward_counts,
        "reward_share_percent": {kind: count * 100 / completed_events for kind, count in reward_counts.items()},
        "failures": sum(int(row["event_failures"]) for row in enabled_rows),
        "route_awards": route_awards,
        "route_use_histogram": route_histogram,
        "route_uses_awarded": route_uses,
        "mean_route_uses_per_award": route_uses / route_awards,
        "route_shortening_millis_awarded": sum(int(row["route_shortening_millis_awarded"]) for row in enabled_rows),
        "route_uses_remaining": sum(int(row["route_reward_uses_remaining"]) for row in enabled_rows),
        "route_shortening_millis_remaining": sum(int(row["route_shortening_millis_remaining"]) for row in enabled_rows),
    }
    result = {"source": str(source), "rows": len(rows), "trait_rows": len(traits), "summary": summaries,
              "event_policy": event_policy, "traits": trait_summary, "churn": churn,
              "sources": [dict(scenario=key[0], mode=key[1], source=key[2], **value) for key, value in sorted(sources.items())]}
    marginal_path = source / "adventure-trait-marginal.csv"
    if marginal_path.is_file():
        marginal = list(csv.DictReader(marginal_path.open()))
        assert len(marginal) == 96
        assert all(row["events_enabled"] == "true" and row["relationships_enabled"] == "true" for row in marginal)
        marginal_summary = []
        for scenario in sorted({row["scenario"] for row in marginal}):
            for hero_class in ["ALL"] + sorted({row["class"] for row in marginal}):
                group = [row for row in marginal if row["scenario"] == scenario and (hero_class == "ALL" or row["class"] == hero_class)]
                entry = {"scenario": scenario, "class": hero_class, "metrics": {}}
                for metric in ["xp_per_hour", "items_per_hour", "cash_earned_per_hour", "earned_plus_ending_bag_base_value",
                               "end_cp", "loot_upgrades", "purchases", "returns", "events", "relationships", "owned_traits_at_end", "trait_actual_effects"]:
                    before, after = [statistics.mean(float(row[metric]) for row in group if row["mode"] == mode)
                                     for mode in ["STAGE2_TRAITS_OFF", "STAGE3_TRAITS_ON"]]
                    entry["metrics"][metric] = {"off": before, "on": after, "delta": after - before,
                                                 "percent": (after / before - 1) * 100 if before else None}
                marginal_summary.append(entry)
        result["trait_marginal_rows"] = len(marginal)
        result["trait_marginal"] = marginal_summary
    output = source / "adventure-final-analysis.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    flat = []
    for row in summaries:
        entry = {"scenario": row["scenario"], "class": row["class"]}
        for metric, values in row["metrics"].items():
            entry.update({metric + "_" + key: value for key, value in values.items()})
        flat.append(entry)
    with (source / "adventure-final-economy-class-summary.csv").open("w") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(flat[0]))
        writer.writeheader()
        writer.writerows(flat)
    print(output)


if __name__ == "__main__":
    main()
