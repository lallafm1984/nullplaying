#!/usr/bin/env python3
"""Long-horizon planning audit for AlarmQuest bounded-stat combat v1.4.

This is a design-only calculator. It does not mutate live saves, content, or the
production resolver. The deterministic battle harness and the bounded hero
calculator are reused from the v1.3 review; v1.4 replaces raw display level in
combat with CombatRank and removes quadratic monster equipment growth.
"""

from __future__ import annotations

import argparse
import math
import statistics
from dataclasses import replace

import base_combat_v1_3_review as v13


DISPLAY_LEVEL_MAX = 2**63 - 1
LONG_LEVELS = (100, 200, 500, 1_000, 5_000, 1_000_000, 1_000_000_000, DISPLAY_LEVEL_MAX)

ROUND_TARGETS = {
    "STANDARD": (4, 10),
    "SWIFT": (4, 8),
    "ARMORED": (7, 15),
    "SPELLCASTER": (4, 10),
}

HP_TARGETS = {
    "WARRIOR": {
        "STANDARD": (12, 25),
        "SWIFT": (8, 22),
        "ARMORED": (20, 38),
        "SPELLCASTER": (12, 30),
    },
    "ROGUE": {
        "STANDARD": (14, 28),
        "SWIFT": (10, 24),
        "ARMORED": (22, 40),
        "SPELLCASTER": (15, 32),
    },
    "MAGE": {
        "STANDARD": (18, 33),
        "SWIFT": (14, 30),
        "ARMORED": (24, 42),
        "SPELLCASTER": (17, 35),
    },
}


def combat_rank(display_level: int) -> int:
    """Map a practically unbounded display level to a bounded-growth rank."""
    safe_level = max(1, min(DISPLAY_LEVEL_MAX, int(display_level)))
    if safe_level <= 100:
        return safe_level
    return v13.round_positive(
        100.0 + 100.0 * math.log1p((safe_level - 100) / 100.0)
    )


def reference_gear_power(rank: int) -> int:
    """Post-100 validation anchor, not a guaranteed live drop value."""
    return max(1, v13.round_positive(1.05 * max(1, rank)))


def reference_source(hero_class: str, display_level: int, band: str = "BASE") -> v13.V13Source:
    rank = combat_rank(display_level)
    source = v13.anchor_source(
        hero_class=hero_class,
        level=rank,
        world_tier=5,
        weapon_power=reference_gear_power(rank),
        armor_power=reference_gear_power(rank),
        band=band,
    )
    return replace(source, label=f"{hero_class[0]}-{band}-L{display_level}-C{rank}")


def hero_combatant(source: v13.V13Source) -> v13.core.Combatant:
    # Every v1.3 "level" term receives CombatRank through source.level.
    return v13.hero_combatant(source, "v1.4")


def monster_combatant(
    combat_rank_value: int,
    profile_name: str,
    rank_name: str,
) -> v13.core.Combatant:
    rank_value = max(1, combat_rank_value)
    x = rank_value - 1
    profile = v13.PROFILES[profile_name]
    monster_rank = v13.RANKS[rank_name]
    unit = min(18.0, 10.0 + 0.125 * x)

    attributes = []
    for index, offset in enumerate(profile["offsets"]):
        rank_bonus = monster_rank["stat_bonus"] if index in profile["signature"] else 0
        value = v13.round_positive(unit + offset + rank_bonus)
        attributes.append(max(3, min(monster_rank["stat_cap"], value)))
    strength, dexterity, constitution, intelligence, wisdom = attributes

    health_growth = v13.round_positive(2.50 * x)
    # Preserve every v1.3 value through CombatRank 100. The post-100 branch is
    # continuous at C100 (weapon 100, ward 80) and removes only the long-term
    # quadratic divergence.
    if rank_value <= 100:
        weapon_power = 1 + v13.round_positive(0.80 * x + 0.0020 * x * x)
        ward_base = 1 + v13.round_positive(0.65 * x + 0.0015 * x * x)
    else:
        weapon_power = 1 + v13.round_positive(1.00 * x)
        ward_base = 1 + v13.round_positive(0.80 * x)
    armor_power = weapon_power * profile["armor"]
    ward_power = ward_base * profile["ward"]

    physical_attack = v13.round_positive(
        10 + 1.4 * rank_value + 1.15 * strength + 0.35 * dexterity + 1.8 * weapon_power
    )
    magical_attack = v13.round_positive(
        10 + 1.4 * rank_value + 1.15 * intelligence + 0.35 * wisdom + 1.8 * weapon_power
    )
    physical_defense = v13.round_positive(
        (4 + 0.55 * constitution + 0.20 * strength + 2.4 * armor_power)
        * monster_rank["defense"]
    )
    magical_resistance = v13.round_positive(
        (4 + 0.60 * wisdom + 0.20 * intelligence + 2.0 * ward_power)
        * monster_rank["defense"]
    )
    physical_accuracy = v13.round_positive(
        10 + 1.25 * dexterity + 0.35 * strength + 0.50 * rank_value
    )
    magical_accuracy = v13.round_positive(
        10 + 1.00 * intelligence + 0.60 * wisdom + 0.50 * rank_value
    )
    evasion = v13.round_positive(
        8 + 1.35 * dexterity + 0.25 * wisdom + 0.45 * rank_value
    )
    attack_stat = strength if profile["attack"] == "PHYSICAL" else intelligence
    critical = v13.round_positive(5 + 1.10 * dexterity + 0.20 * attack_stat)
    critical_resistance = v13.round_positive(5 + 0.65 * constitution + 0.45 * wisdom)
    speed = v13.round_positive(60 + 2.0 * dexterity)
    max_hp = v13.round_positive(
        (80 + 8 * rank_value + 6 * constitution + 4 * health_growth)
        * profile["hp"]
        * monster_rank["hp"]
    )

    return v13.core.Combatant(
        name=f"{profile_name}-{rank_name}-C{rank_value}",
        level=rank_value,
        max_hp=max(1, max_hp),
        physical_attack=max(1, physical_attack),
        magical_attack=max(1, magical_attack),
        physical_defense=max(0, physical_defense),
        magical_resistance=max(0, magical_resistance),
        physical_accuracy=max(1, physical_accuracy),
        magical_accuracy=max(1, magical_accuracy),
        evasion=max(1, evasion),
        critical=max(1, critical),
        critical_resistance=max(1, critical_resistance),
        speed=max(1, speed),
        attack_type=profile["attack"],
        action_coefficient_bps=v13.round_positive(
            profile["coeff"] * monster_rank["coeff"]
        ),
    )


def measure_cell(
    source: v13.V13Source,
    profile: str,
    monster_rank: str,
    seeds: int,
    enemy_rank_offset: int = 0,
    seed_offset: int = 0,
) -> v13.core.CellMetrics:
    hero = hero_combatant(source)
    monster = monster_combatant(source.level + enemy_rank_offset, profile, monster_rank)
    results = [
        v13.core.battle(hero, monster, seed=seed_offset + seed)
        for seed in range(seeds)
    ]
    hp_losses = [
        (hero.max_hp - result.hero_hp) * 100.0 / hero.max_hp
        for result in results
    ]
    attempts = sum(result.hero_attempts for result in results)
    hits = sum(result.hero_hits for result in results)
    crits = sum(result.hero_crits for result in results)
    return v13.core.CellMetrics(
        win_rate=sum(result.outcome == "WIN" for result in results) / seeds,
        loss_rate=sum(result.outcome == "LOSS" for result in results) / seeds,
        retreat_rate=sum(result.outcome == "RETREAT" for result in results) / seeds,
        median_rounds=statistics.median(result.rounds for result in results),
        p90_rounds=int(v13.core.percentile([result.rounds for result in results], 0.90)),
        median_hp_loss=statistics.median(hp_losses),
        p90_hp_loss=v13.core.percentile(hp_losses, 0.90),
        hero_hit_rate=hits / max(1, attempts),
        hero_crit_rate_on_hit=crits / max(1, hits),
    )


def pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def numeric_fields(combatant: v13.core.Combatant) -> tuple[int, ...]:
    return (
        combatant.level,
        combatant.max_hp,
        combatant.physical_attack,
        combatant.magical_attack,
        combatant.physical_defense,
        combatant.magical_resistance,
        combatant.physical_accuracy,
        combatant.magical_accuracy,
        combatant.evasion,
        combatant.critical,
        combatant.critical_resistance,
        combatant.speed,
    )


def run_invariants() -> None:
    expected = {
        1: 1,
        100: 100,
        200: 169,
        500: 261,
        1_000: 330,
        5_000: 491,
        1_000_000: 1_021,
        1_000_000_000: 1_712,
        DISPLAY_LEVEL_MAX: 4_006,
    }
    assert {level: combat_rank(level) for level in expected} == expected

    previous = 0
    for level in range(1, 100_001):
        current = combat_rank(level)
        assert current >= previous
        previous = current

    for rank_value in (1, 10, 28, 58, 100):
        for profile in v13.PROFILES:
            for monster_rank in v13.RANKS:
                legacy = v13.monster_combatant(rank_value, profile, monster_rank)
                candidate = monster_combatant(rank_value, profile, monster_rank)
                assert numeric_fields(candidate) == numeric_fields(legacy)
                assert candidate.attack_type == legacy.attack_type
                assert candidate.action_coefficient_bps == legacy.action_coefficient_bps

    max_observed = 0
    max_source_level = combat_rank(DISPLAY_LEVEL_MAX)
    for hero_class in v13.CLASS_MULTIPLIERS:
        hero = hero_combatant(reference_source(hero_class, DISPLAY_LEVEL_MAX))
        max_observed = max(max_observed, *numeric_fields(hero))
    for profile in v13.PROFILES:
        for monster_rank in v13.RANKS:
            monster = monster_combatant(max_source_level, profile, monster_rank)
            max_observed = max(max_observed, *numeric_fields(monster))
    assert max_observed < 2**31 - 1

    source = reference_source("MAGE", 1_000_000_000)
    first = v13.core.battle(
        hero_combatant(source), monster_combatant(source.level, "STANDARD", "NORMAL"), 777
    )
    second = v13.core.battle(
        hero_combatant(source), monster_combatant(source.level, "STANDARD", "NORMAL"), 777
    )
    assert first == second
    print(
        "[invariants] rank_mapping=PASS monotonic=PASS c1_to_c100_compat=PASS "
        "deterministic=PASS "
        f"int32_safety=PASS maxCombatRank={max_source_level} maxField={max_observed}"
    )


def print_long_normal(seeds: int) -> list[str]:
    print("\n[v1.4 long NORMAL core]")
    print("displayLevel combatRank class profile win rounds/p90 hpLoss/p90 hit")
    failures = []
    for display_level in LONG_LEVELS:
        rank_value = combat_rank(display_level)
        for hero_class in v13.CLASS_MULTIPLIERS:
            source = reference_source(hero_class, display_level)
            for profile in v13.PROFILES:
                metrics = measure_cell(
                    source,
                    profile,
                    "NORMAL",
                    seeds,
                    seed_offset=100_000 + rank_value * 17,
                )
                round_min, round_max = ROUND_TARGETS[profile]
                hp_min, hp_max = HP_TARGETS[hero_class][profile]
                hit_min, hit_max = (0.82, 0.96) if profile == "SWIFT" else (0.875, 0.995)
                passed = (
                    0.95 <= metrics.win_rate <= 1.0
                    and round_min <= metrics.median_rounds <= round_max
                    and metrics.p90_rounds <= 16
                    and hp_min <= metrics.median_hp_loss <= hp_max
                    and hit_min <= metrics.hero_hit_rate <= hit_max
                    and metrics.retreat_rate < 0.001
                )
                if not passed:
                    failures.append(
                        f"L{display_level}/C{rank_value}/{hero_class}/{profile}"
                    )
                print(
                    f"{display_level:19d} {rank_value:10d} {hero_class:7} {profile:11} "
                    f"{pct(metrics.win_rate):>7} {metrics.median_rounds:4.1f}/{metrics.p90_rounds:2} "
                    f"{metrics.median_hp_loss:5.1f}/{metrics.p90_hp_loss:5.1f}% "
                    f"{pct(metrics.hero_hit_rate):>6}"
                )
    print(f"long_normal_gate_failures={len(failures)}/{len(LONG_LEVELS) * 12}")
    return failures


def print_drift_audit(seeds: int) -> list[str]:
    print("\n[v1.4 asymptotic drift: display level 100 vs Long.MAX_VALUE]")
    print("class profile rounds100/max hpLoss100/max hit100/max verdict")
    failures = []
    for hero_class in v13.CLASS_MULTIPLIERS:
        low_source = reference_source(hero_class, 100)
        high_source = reference_source(hero_class, DISPLAY_LEVEL_MAX)
        for profile in v13.PROFILES:
            low = measure_cell(low_source, profile, "NORMAL", seeds, seed_offset=300_000)
            high = measure_cell(high_source, profile, "NORMAL", seeds, seed_offset=300_000)
            passed = (
                abs(high.median_rounds - low.median_rounds) <= 1
                and abs(high.median_hp_loss - low.median_hp_loss) <= 5.0
                and abs(high.hero_hit_rate - low.hero_hit_rate) <= 0.02
            )
            if not passed:
                failures.append(f"{hero_class}/{profile}")
            print(
                f"{hero_class:7} {profile:11} "
                f"{low.median_rounds:4.1f}/{high.median_rounds:4.1f} "
                f"{low.median_hp_loss:5.1f}/{high.median_hp_loss:5.1f}% "
                f"{pct(low.hero_hit_rate):>6}/{pct(high.hero_hit_rate):>6} "
                f"{'PASS' if passed else 'FAIL'}"
            )
    print(f"asymptotic_drift_failures={len(failures)}/12")
    return failures


def print_low_stat_audit(seeds: int) -> list[str]:
    print("\n[v1.4 low-stat catch-up at long horizons: STANDARD/NORMAL]")
    print("displayLevel combatRank class band combatStats win rounds hpLoss/p90")
    failures = []
    for display_level in (100, 5_000, 1_000_000_000):
        for hero_class in v13.CLASS_MULTIPLIERS:
            for band in ("LOW", "BASE", "HIGH"):
                source = reference_source(hero_class, display_level, band)
                stats = v13.combat_attributes(source)
                metrics = measure_cell(
                    source,
                    "STANDARD",
                    "NORMAL",
                    seeds,
                    seed_offset=500_000 + combat_rank(display_level),
                )
                passed = metrics.win_rate >= 0.95 and metrics.retreat_rate < 0.001
                if not passed:
                    failures.append(f"L{display_level}/{hero_class}/{band}")
                print(
                    f"{display_level:12d} {source.level:10d} {hero_class:7} {band:4} "
                    f"{'/'.join(str(v13.round_positive(value)) for value in stats):>14} "
                    f"{pct(metrics.win_rate):>7} {metrics.median_rounds:4.1f} "
                    f"{metrics.median_hp_loss:5.1f}/{metrics.p90_hp_loss:5.1f}%"
                )
    print(f"low_stat_long_gate_failures={len(failures)}/27")
    return failures


def print_rank_diagnostic(seeds: int) -> None:
    print("\n[v1.4 rank diagnostic; not a skill-integrated approval gate]")
    print("displayLevel class profile rank win/loss/retreat rounds hpLoss")
    for display_level in (100, 1_000_000_000):
        for hero_class in v13.CLASS_MULTIPLIERS:
            source = reference_source(hero_class, display_level)
            for profile in v13.PROFILES:
                for monster_rank in ("ELITE", "BOSS"):
                    metrics = measure_cell(
                        source,
                        profile,
                        monster_rank,
                        seeds,
                        seed_offset=700_000 + source.level,
                    )
                    print(
                        f"{display_level:12d} {hero_class:7} {profile:11} {monster_rank:6} "
                        f"{pct(metrics.win_rate):>7}/{pct(metrics.loss_rate):>6}/"
                        f"{pct(metrics.retreat_rate):>6} {metrics.median_rounds:4.1f} "
                        f"{metrics.median_hp_loss:5.1f}%"
                    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=2_000)
    parser.add_argument(
        "--section",
        choices=("all", "normal", "drift", "low", "ranks"),
        default="all",
    )
    args = parser.parse_args()
    if not 500 <= args.seeds <= 20_000:
        raise SystemExit("--seeds must be in 500..20000")

    print(f"AlarmQuest long-term combat v1.4 review; seeds_per_cell={args.seeds}")
    run_invariants()
    failures = []
    if args.section in ("all", "normal"):
        failures.extend(print_long_normal(args.seeds))
    if args.section in ("all", "drift"):
        failures.extend(print_drift_audit(args.seeds))
    if args.section in ("all", "low"):
        failures.extend(print_low_stat_audit(args.seeds))
    if args.section in ("all", "ranks"):
        print_rank_diagnostic(args.seeds)

    if failures:
        raise SystemExit(f"PD_GATE=FAIL required_failures={len(failures)}")
    print("\nPD_GATE=PASS required_long_horizon_gates=0_failures")


if __name__ == "__main__":
    main()
