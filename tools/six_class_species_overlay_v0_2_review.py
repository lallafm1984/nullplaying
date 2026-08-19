#!/usr/bin/env python3
"""Provisional six-class x six-species overlay audit for AlarmQuest.

This design-only harness combines the species-OFF six-class v1.5 candidate
with the bounded v0.1 species effects. It does not touch live Kotlin, Room,
Compose, skills, items, rewards, or existing saves.
"""

from __future__ import annotations

import argparse
import statistics
from dataclasses import replace

import base_combat_six_classes_v1_5_review as six
import base_combat_v1_4_long_term_review as v14
import species_traits_v0_1_review as species


SPECIES = {
    "HUMAN": ("HUMAN_PRECISION", "HUMAN_VIGILANCE"),
    "ELF": ("ELF",),
    "DWARF": ("DWARF",),
    "ORC": ("ORC",),
    "HALFLING": ("HALFLING",),
    # The current combat model has no elemental damage tag. Matching lineage
    # is therefore a deliberately conservative synthetic ceiling.
    "DRAGONKIN": ("DRAGONKIN_MATCH",),
}
SPECIES_IDS = {
    "HUMAN": "aq.species.human",
    "ELF": "aq.species.elf",
    "DWARF": "aq.species.dwarf",
    "ORC": "aq.species.orc",
    "HALFLING": "aq.species.halfling",
    "DRAGONKIN": "aq.species.dragonkin",
}
VARIANTS = tuple(variant for variants in SPECIES.values() for variant in variants)
DISPLAY_LEVELS = (1, 10, 28, 58, 100, v14.DISPLAY_LEVEL_MAX)
DIAGNOSTIC_LEVELS = (58, v14.DISPLAY_LEVEL_MAX)
BUILD_BANDS = ("BASE", "STRESS_ALL3")
PROFILE_WEIGHTS = {
    "STANDARD": 0.40,
    "SWIFT": 0.25,
    "ARMORED": 0.20,
    "SPELLCASTER": 0.15,
}


def combatants(
    hero_class: str,
    display_level: int,
    profile: str,
    monster_rank: str,
    band: str,
):
    source = six.source_for_display(hero_class, display_level, band)
    return six.derive_hero(source).combatant, six.monster_for(source, profile, monster_rank)


def measure(
    hero_class: str,
    display_level: int,
    profile: str,
    monster_rank: str,
    band: str,
    variant: str,
    seeds: int,
    seed_offset: int,
    rng_monster_name: str | None = None,
):
    hero, monster = combatants(hero_class, display_level, profile, monster_rank, band)
    if rng_monster_name is not None:
        monster = replace(monster, name=rng_monster_name)
    return species.measure_species_cell(
        hero,
        monster,
        variant,
        seeds,
        seed_offset,
    )


def assert_contracts() -> None:
    six.run_invariants()
    assert set(SPECIES) == {
        "HUMAN",
        "ELF",
        "DWARF",
        "ORC",
        "HALFLING",
        "DRAGONKIN",
    }
    assert set(SPECIES_IDS) == set(SPECIES)
    assert len(set(SPECIES_IDS.values())) == 6
    assert all(value.startswith("aq.species.") for value in SPECIES_IDS.values())
    assert len(six.CLASSES) * len(SPECIES) == 36
    assert len(VARIANTS) == 7
    assert set(VARIANTS).issubset(set(species.SPECIES_VARIANTS))
    assert len(species.PERSISTED_TRAIT_CHOICE_IDS) == 6
    assert len(set(species.PERSISTED_TRAIT_CHOICE_IDS.values())) == 6
    assert all(
        value.startswith("aq.trait_choice.") and value == value.lower()
        for value in species.PERSISTED_TRAIT_CHOICE_IDS.values()
    )

    # Neutral overlay must preserve the underlying battle result and RNG stream.
    for hero_class in six.CLASSES:
        source = six.source_for_display(hero_class, 58, "BASE")
        hero = six.derive_hero(source).combatant
        monster = six.monster_for(source, "STANDARD", "NORMAL")
        for seed in (0, 1, 777, 9_999):
            expected = six.v13.core.battle(hero, monster, seed=seed)
            actual = species.species_battle(hero, monster, "NEUTRAL", seed=seed)
            assert (
                actual.outcome,
                actual.rounds,
                actual.hero_hp,
                actual.hero_attempts,
                actual.hero_hits,
                actual.hero_crits,
                actual.monster_attempts,
                actual.monster_hits,
                actual.monster_crits,
            ) == (
                expected.outcome,
                expected.rounds,
                expected.hero_hp,
                expected.hero_attempts,
                expected.hero_hits,
                expected.hero_crits,
                expected.monster_attempts,
                expected.monster_hits,
                expected.monster_crits,
            )
            assert actual.added_damage == 0
            assert actual.prevented_damage == 0
            assert actual.activations == 0
    print(
        "[overlay invariants] structural_combinations=36 numeric_branches=42 "
        "neutral_rng_compat_six=PASS extra_action=0 reroll=0"
    )


def pct(value: float) -> str:
    return f"{value:.2f}%"


def run_paired_drift(seeds: int) -> tuple[list[str], float, int, int]:
    """Compare C100 and Long.MAX with an identical event RNG key and seeds."""
    drift_failures: list[str] = []
    max_drift = 0.0
    drift_seeds = min(5_000, max(1_000, seeds * 4))
    for variant in VARIANTS:
        for hero_class in six.CLASSES:
            for band in BUILD_BANDS:
                for profile in six.v13.PROFILES:
                    seed_offset = (
                        8_800_000
                        + six.CLASSES.index(hero_class) * 100_000
                        + BUILD_BANDS.index(band) * 10_000
                        + tuple(six.v13.PROFILES).index(profile) * 1_000
                    )
                    rng_monster_name = (
                        f"PAIRED-DRIFT/{hero_class}/{band}/{profile}/NORMAL"
                    )
                    low = measure(
                        hero_class,
                        100,
                        profile,
                        "NORMAL",
                        band,
                        variant,
                        drift_seeds,
                        seed_offset,
                        rng_monster_name,
                    )
                    high = measure(
                        hero_class,
                        v14.DISPLAY_LEVEL_MAX,
                        profile,
                        "NORMAL",
                        band,
                        variant,
                        drift_seeds,
                        seed_offset,
                        rng_monster_name,
                    )
                    drift = abs(high.mean_contribution_pct - low.mean_contribution_pct)
                    max_drift = max(max_drift, drift)
                    if drift > 1.0:
                        drift_failures.append(
                            f"{variant}/{hero_class}/{band}/{profile}: drift={drift:.2f}"
                        )
    drift_battles = (
        len(VARIANTS)
        * len(six.CLASSES)
        * len(BUILD_BANDS)
        * len(six.v13.PROFILES)
        * 2
        * drift_seeds
    )
    return drift_failures, max_drift, drift_seeds, drift_battles


def run_overlay(seeds: int) -> list[str]:
    rows = {variant: [] for variant in VARIANTS}
    failures: list[str] = []

    for hero_class in six.CLASSES:
        for band in BUILD_BANDS:
            for display_level in DISPLAY_LEVELS:
                for profile in six.v13.PROFILES:
                    seed_offset = (
                        8_000_000
                        + six.CLASSES.index(hero_class) * 100_000
                        + display_level % 100_000
                    )
                    baseline = measure(
                        hero_class,
                        display_level,
                        profile,
                        "NORMAL",
                        band,
                        "NEUTRAL",
                        seeds,
                        seed_offset,
                    )
                    label = f"{hero_class}/{band}/L{display_level}/{profile}/NORMAL"
                    for variant in VARIANTS:
                        metrics = measure(
                            hero_class,
                            display_level,
                            profile,
                            "NORMAL",
                            band,
                            variant,
                            seeds,
                            seed_offset,
                        )
                        rows[variant].append((label, baseline, metrics))
                        win_delta = 100.0 * (metrics.win_rate - baseline.win_rate)
                        rounds_gain = baseline.median_rounds - metrics.median_rounds
                        hp_gain = baseline.mean_hp_loss - metrics.mean_hp_loss
                        if (
                            metrics.mean_contribution_pct > 5.0
                            or win_delta > 0.5
                            or rounds_gain > 1.0
                            or hp_gain > 5.0
                        ):
                            failures.append(
                                f"{label}/{variant}: contribution="
                                f"{metrics.mean_contribution_pct:.2f} winDelta={win_delta:.2f} "
                                f"roundsGain={rounds_gain:.1f} hpGain={hp_gain:.2f}"
                            )

    print("\n[six-class species NORMAL overlay]")
    print(
        "variant medianContribution maxContribution maxP90 "
        "maxWinDelta maxHpGain activationMedian"
    )
    for variant, values in rows.items():
        contributions = [metrics.mean_contribution_pct for _, _, metrics in values]
        p90s = [metrics.p90_contribution_pct for _, _, metrics in values]
        win_deltas = [
            100.0 * (metrics.win_rate - baseline.win_rate)
            for _, baseline, metrics in values
        ]
        hp_gains = [
            baseline.mean_hp_loss - metrics.mean_hp_loss
            for _, baseline, metrics in values
        ]
        activations = [100.0 * metrics.activation_rate for _, _, metrics in values]
        print(
            f"{variant:20} {pct(statistics.median(contributions)):>18} "
            f"{pct(max(contributions)):>15} {pct(max(p90s)):>8} "
            f"{pct(max(win_deltas)):>11} {pct(max(hp_gains)):>9} "
            f"{pct(statistics.median(activations)):>16}"
        )

    # Drift uses the same seed range and the same event RNG key at both ranks.
    drift_failures, max_drift, drift_seeds, drift_battles = run_paired_drift(seeds)

    # Conservative long-run comparison: both human choices and the synthetic
    # matching Dragonkin ceiling compete as separate numeric branches.
    throughput_failures: list[str] = []
    print("\n[Long.MAX weighted kill-throughput spread by class]")
    print("class neutral minVariant maxVariant spreadVsNeutral")
    for hero_class in six.CLASSES:
        neutral_weighted = 0.0
        variant_weighted = {variant: 0.0 for variant in VARIANTS}
        for profile, weight in PROFILE_WEIGHTS.items():
            seed_offset = 9_000_000 + six.CLASSES.index(hero_class) * 100_000
            baseline = measure(
                hero_class,
                v14.DISPLAY_LEVEL_MAX,
                profile,
                "NORMAL",
                "BASE",
                "NEUTRAL",
                seeds,
                seed_offset,
            )
            neutral_weighted += weight * baseline.kill_throughput
            for variant in VARIANTS:
                metrics = measure(
                    hero_class,
                    v14.DISPLAY_LEVEL_MAX,
                    profile,
                    "NORMAL",
                    "BASE",
                    variant,
                    seeds,
                    seed_offset,
                )
                variant_weighted[variant] += weight * metrics.kill_throughput
        minimum_variant = min(variant_weighted, key=variant_weighted.get)
        maximum_variant = max(variant_weighted, key=variant_weighted.get)
        spread = (
            100.0
            * (variant_weighted[maximum_variant] - variant_weighted[minimum_variant])
            / max(0.0001, neutral_weighted)
        )
        if spread > 5.0:
            throughput_failures.append(f"{hero_class}: spread={spread:.2f}")
        print(
            f"{hero_class:7} {neutral_weighted:.2f} "
            f"{minimum_variant}:{variant_weighted[minimum_variant]:.2f} "
            f"{maximum_variant}:{variant_weighted[maximum_variant]:.2f} "
            f"{spread:.2f}%"
        )

    required_candidate_cells = (
        len(six.CLASSES)
        * len(BUILD_BANDS)
        * len(DISPLAY_LEVELS)
        * len(six.v13.PROFILES)
        * len(VARIANTS)
    )
    normal_battles = (
        len(six.CLASSES)
        * len(BUILD_BANDS)
        * len(DISPLAY_LEVELS)
        * len(six.v13.PROFILES)
        * (len(VARIANTS) + 1)
        * seeds
    )
    throughput_battles = (
        len(six.CLASSES)
        * len(six.v13.PROFILES)
        * (len(VARIANTS) + 1)
        * seeds
    )
    print(
        f"\nnormal_candidate_cells={required_candidate_cells} "
        f"normal_drift_throughput_battles="
        f"{normal_battles + drift_battles + throughput_battles:,}"
    )
    print(f"normal_failures={len(failures)}")
    print(
        f"asymptotic_drift_failures={len(drift_failures)} "
        f"pairedSeeds={drift_seeds} maxPairedDrift={max_drift:.2f}%p"
    )
    print(f"long_throughput_spread_failures={len(throughput_failures)}")
    combined = failures + drift_failures + throughput_failures
    for failure in combined[:30]:
        print(f"FAIL {failure}")
    return combined


def run_rank_diagnostics(seeds: int) -> list[str]:
    cap_failures: list[str] = []
    win_warnings: list[str] = []
    warning_deltas: list[float] = []
    max_win_delta = 0.0
    max_win_delta_label = "NONE"
    max_contribution = 0.0
    for hero_class in six.CLASSES:
        for display_level in DIAGNOSTIC_LEVELS:
            for profile in six.v13.PROFILES:
                for monster_rank in ("ELITE", "BOSS"):
                    seed_offset = (
                        10_000_000
                        + six.CLASSES.index(hero_class) * 100_000
                        + display_level % 100_000
                    )
                    baseline = measure(
                        hero_class,
                        display_level,
                        profile,
                        monster_rank,
                        "BASE",
                        "NEUTRAL",
                        seeds,
                        seed_offset,
                    )
                    for variant in VARIANTS:
                        metrics = measure(
                            hero_class,
                            display_level,
                            profile,
                            monster_rank,
                            "BASE",
                            variant,
                            seeds,
                            seed_offset,
                        )
                        max_contribution = max(
                            max_contribution,
                            metrics.mean_contribution_pct,
                        )
                        label = (
                            f"{hero_class}/L{display_level}/{profile}/"
                            f"{monster_rank}/{variant}"
                        )
                        if metrics.mean_contribution_pct > 10.0:
                            cap_failures.append(
                                f"{label}: contribution={metrics.mean_contribution_pct:.2f}"
                            )
                        win_delta = 100.0 * (metrics.win_rate - baseline.win_rate)
                        if win_delta > max_win_delta:
                            max_win_delta = win_delta
                            max_win_delta_label = label
                        threshold = 3.0 if monster_rank == "ELITE" else 5.0
                        if win_delta > threshold:
                            win_warnings.append(f"{label}: winDelta={win_delta:.2f}")
                            warning_deltas.append(win_delta)
    diagnostic_cells = (
        len(six.CLASSES)
        * len(DIAGNOSTIC_LEVELS)
        * len(six.v13.PROFILES)
        * 2
        * len(VARIANTS)
    )
    diagnostic_battles = (
        len(six.CLASSES)
        * len(DIAGNOSTIC_LEVELS)
        * len(six.v13.PROFILES)
        * 2
        * (len(VARIANTS) + 1)
        * seeds
    )
    print("\n[skillless ELITE/BOSS diagnostic]")
    print(
        f"diagnostic_candidate_cells={diagnostic_cells} "
        f"diagnostic_battles={diagnostic_battles:,} "
        f"max_direct_contribution={max_contribution:.2f}%"
    )
    print(f"direct_cap_failures={len(cap_failures)}")
    warning_p90 = (
        six.v13.core.percentile(warning_deltas, 0.90) if warning_deltas else 0.0
    )
    print(
        f"win_threshold_warnings={len(win_warnings)} "
        f"warningP90={warning_p90:.2f}%p maxWinDelta={max_win_delta:.2f}%p"
    )
    print(f"maxWinDeltaCell={max_win_delta_label}")
    for warning in win_warnings[:12]:
        print(f"WARN {warning}")
    return cap_failures


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=250)
    parser.add_argument(
        "--section",
        choices=("all", "normal", "drift", "ranks"),
        default="all",
    )
    parser.add_argument(
        "--pd",
        action="store_true",
        help="Use 250 NORMAL seeds, 1000 paired drift seeds, and 500 rank seeds",
    )
    args = parser.parse_args()
    if not 100 <= args.seeds <= 10_000:
        raise SystemExit("--seeds must be in 100..10000")

    normal_seeds = max(250, args.seeds) if args.pd else args.seeds
    print(
        "AlarmQuest six-class species overlay v0.2 review; "
        f"seeds_per_normal_cell={normal_seeds} "
        f"profile={'PD' if args.pd else 'EXPLORE'}"
    )
    assert_contracts()
    failures: list[str] = []
    if args.section in ("all", "normal"):
        failures.extend(run_overlay(normal_seeds))
    if args.section == "drift":
        drift_failures, max_drift, drift_seeds, drift_battles = run_paired_drift(
            args.seeds
        )
        print("\n[paired C100 vs Long.MAX species contribution drift]")
        print(
            f"paired_battles={drift_battles:,} pairedSeeds={drift_seeds} "
            f"maxPairedDrift={max_drift:.2f}%p failures={len(drift_failures)}"
        )
        for failure in drift_failures[:30]:
            print(f"FAIL {failure}")
        failures.extend(drift_failures)
    if args.section in ("all", "ranks"):
        rank_seeds = max(500, args.seeds // 2) if args.pd else max(100, args.seeds // 2)
        failures.extend(run_rank_diagnostics(rank_seeds))
    if failures:
        raise SystemExit(f"PD_GATE=FAIL failures={len(failures)}")
    print(
        "\nPD_GATE=PASS six_class_species=PROVISIONAL_GO "
        "skills_items_content_weights=OFF"
    )


if __name__ == "__main__":
    main()
