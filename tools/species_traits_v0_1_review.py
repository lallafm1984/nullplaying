#!/usr/bin/env python3
"""Deterministic planning audit for AlarmQuest species-trait candidates v0.1.

This is a design-only harness. It layers bounded species effects over the
approved v1.3/v1.4 basic-combat calculators without mutating live Kotlin,
Room, onboarding, content, or saves.
"""

from __future__ import annotations

import argparse
import math
import statistics
from dataclasses import dataclass

import base_combat_v1_3_review as v13
import base_combat_v1_4_long_term_review as v14
import character_creation_v0_1_review as creation


SPECIES_VARIANTS = (
    "NEUTRAL",
    "HUMAN_PRECISION",
    "HUMAN_VIGILANCE",
    "ELF",
    "DWARF",
    "ORC",
    "HALFLING",
    "DRAGONKIN_MATCH",
)

# Persist these stable IDs, never the uppercase audit-harness labels above.
PERSISTED_TRAIT_CHOICE_IDS = {
    "HUMAN_PRECISION": "aq.trait_choice.human.precision",
    "HUMAN_VIGILANCE": "aq.trait_choice.human.vigilance",
    "DRAGONKIN_FLAME": "aq.trait_choice.dragonkin.flame",
    "DRAGONKIN_FROST": "aq.trait_choice.dragonkin.frost",
    "DRAGONKIN_STORM": "aq.trait_choice.dragonkin.storm",
    "DRAGONKIN_VOID": "aq.trait_choice.dragonkin.void",
}

DISPLAY_LEVELS = (1, 58, 100, v14.DISPLAY_LEVEL_MAX)
DIAGNOSTIC_LEVELS = (58, v14.DISPLAY_LEVEL_MAX)
BUILD_BANDS = ("BASE", "LOW")

HUMAN_HIT_DELTA_BPS = 200
ELF_OPENING_HIT_DELTA_BPS = 500
DWARF_DAMAGE_REDUCTION_BPS = 2_000
DWARF_MAX_HP_CAP_BPS = 300
ORC_THRESHOLD_HP_BPS = 3_000
ORC_GUARD_MAX_HP_BPS = 400
HALFLING_HIT_WINDOW_BPS = 400
HALFLING_MAX_HP_CAP_BPS = 300
DRAGONKIN_DAMAGE_REDUCTION_BPS = 2_000
DRAGONKIN_MAX_HP_CAP_BPS = 400


@dataclass
class TraitState:
    elf_opener_available: bool = True
    dwarf_guard_available: bool = True
    orc_guard_available: bool = True
    halfling_luck_available: bool = True
    dragon_absorbed: int = 0
    orc_guard: int = 0
    added_damage: int = 0
    prevented_damage: int = 0
    activations: int = 0


@dataclass(frozen=True)
class SpeciesBattleResult:
    outcome: str
    rounds: int
    hero_hp: int
    hero_attempts: int
    hero_hits: int
    hero_crits: int
    monster_attempts: int
    monster_hits: int
    monster_crits: int
    added_damage: int
    prevented_damage: int
    activations: int


@dataclass(frozen=True)
class SpeciesCellMetrics:
    win_rate: float
    median_rounds: float
    mean_hp_loss: float
    mean_contribution_pct: float
    p90_contribution_pct: float
    activation_rate: float
    kill_throughput: float


def round_positive(value: float) -> int:
    return math.floor(value + 0.5)


def effective_prevented_hp(current_hp: int, damage: int, reduction: int) -> int:
    """Count only the HP loss actually avoided, excluding lethal overkill."""
    before_loss = min(current_hp, max(0, damage))
    after_loss = min(current_hp, max(0, damage - reduction))
    return max(0, before_loss - after_loss)


def damage_for_rolls(
    attacker: v13.core.Combatant,
    defender: v13.core.Combatant,
    critical_roll: int,
    variance_bps: int,
) -> tuple[int, bool]:
    critical = critical_roll < v13.core.critical_bps(attacker, defender)
    attack_power = (
        attacker.physical_attack
        if attacker.attack_type == "PHYSICAL"
        else attacker.magical_attack
    )
    damage = v13.core.multiply_bps(attack_power, attacker.action_coefficient_bps)
    damage = v13.core.multiply_bps(damage, 15_000 if critical else 10_000)
    damage = v13.core.multiply_bps(damage, variance_bps)
    damage = max(
        1,
        v13.core.multiply_bps(
            damage,
            10_000 - v13.core.mitigation_bps(attacker, defender),
        ),
    )
    return damage, critical


def species_battle(
    hero: v13.core.Combatant,
    monster: v13.core.Combatant,
    species_variant: str,
    seed: int,
    sequence: int = 91,
) -> SpeciesBattleResult:
    """Resolve one battle while consuming the exact baseline combat RNG stream."""
    if species_variant not in SPECIES_VARIANTS:
        raise ValueError(species_variant)

    random = v13.core.DeterministicRandom.for_event(
        seed,
        sequence,
        "TURN_BATTLE_V5",
        monster.name,
    )
    tie_hero = random.chance(0.5)
    if hero.speed > monster.speed:
        order = ("hero", "monster")
    elif hero.speed < monster.speed:
        order = ("monster", "hero")
    else:
        order = ("hero", "monster") if tie_hero else ("monster", "hero")

    hero_hp = hero.max_hp
    monster_hp = monster.max_hp
    state = TraitState()
    hero_attempts = hero_hits = hero_crits = 0
    monster_attempts = monster_hits = monster_crits = 0

    for round_index in range(1, 31):
        for side in order:
            if hero_hp <= 0 or monster_hp <= 0:
                break

            attacker = hero if side == "hero" else monster
            defender = monster if side == "hero" else hero
            base_hit_bps = v13.core.hit_bps(attacker, defender)
            effective_hit_bps = base_hit_bps

            if side == "hero" and species_variant == "HUMAN_PRECISION":
                effective_hit_bps = min(9_900, base_hit_bps + HUMAN_HIT_DELTA_BPS)
            elif side == "monster" and species_variant == "HUMAN_VIGILANCE":
                effective_hit_bps = max(6_500, base_hit_bps - HUMAN_HIT_DELTA_BPS)
            elif side == "hero" and species_variant == "ELF" and state.elf_opener_available:
                effective_hit_bps = min(
                    9_900,
                    base_hit_bps + ELF_OPENING_HIT_DELTA_BPS,
                )
                state.elf_opener_available = False

            # Every action always consumes hit, critical, and variance in the
            # baseline order. Species never reroll or request another draw.
            hit_roll = random.next_int(0, 10_000)
            critical_roll = random.next_int(0, 10_000)
            variance_bps = random.next_int(9_500, 10_501)
            base_hit = hit_roll < base_hit_bps
            actual_hit = hit_roll < effective_hit_bps
            rolled_damage, critical = damage_for_rolls(
                attacker,
                defender,
                critical_roll,
                variance_bps,
            )

            if side == "hero":
                hero_attempts += 1
                hero_hits += int(actual_hit)
                hero_crits += int(actual_hit and critical)
                if actual_hit and not base_hit:
                    state.added_damage += min(rolled_damage, monster_hp)
                    state.activations += 1
                if actual_hit:
                    monster_hp = max(0, monster_hp - rolled_damage)
                continue

            monster_attempts += 1
            monster_hits += int(actual_hit)
            monster_crits += int(actual_hit and critical)
            if base_hit and not actual_hit:
                state.prevented_damage += min(rolled_damage, hero_hp)
                state.activations += 1
                continue
            if not actual_hit:
                continue

            hp_damage = rolled_damage

            if species_variant == "DWARF" and state.dwarf_guard_available \
                    and monster.attack_type == "PHYSICAL":
                reduction = min(
                    v13.core.multiply_bps(hp_damage, DWARF_DAMAGE_REDUCTION_BPS),
                    v13.core.multiply_bps(hero.max_hp, DWARF_MAX_HP_CAP_BPS),
                )
                if reduction > 0:
                    effective_reduction = effective_prevented_hp(
                        hero_hp,
                        hp_damage,
                        reduction,
                    )
                    hp_damage -= reduction
                    state.prevented_damage += effective_reduction
                    state.activations += 1
                    state.dwarf_guard_available = False

            if species_variant == "HALFLING" and state.halfling_luck_available:
                is_marginal_hit = (
                    base_hit_bps - HALFLING_HIT_WINDOW_BPS
                    <= hit_roll
                    < base_hit_bps
                )
                if is_marginal_hit:
                    reduction = min(
                        hp_damage,
                        v13.core.multiply_bps(hero.max_hp, HALFLING_MAX_HP_CAP_BPS),
                    )
                    if reduction > 0:
                        effective_reduction = effective_prevented_hp(
                            hero_hp,
                            hp_damage,
                            reduction,
                        )
                        hp_damage -= reduction
                        state.prevented_damage += effective_reduction
                        state.activations += 1
                        state.halfling_luck_available = False

            if species_variant == "DRAGONKIN_MATCH":
                remaining_cap = max(
                    0,
                    v13.core.multiply_bps(
                        hero.max_hp,
                        DRAGONKIN_MAX_HP_CAP_BPS,
                    ) - state.dragon_absorbed,
                )
                reduction = min(
                    v13.core.multiply_bps(
                        hp_damage,
                        DRAGONKIN_DAMAGE_REDUCTION_BPS,
                    ),
                    remaining_cap,
                )
                if reduction > 0:
                    effective_reduction = effective_prevented_hp(
                        hero_hp,
                        hp_damage,
                        reduction,
                    )
                    hp_damage -= reduction
                    state.dragon_absorbed += reduction
                    state.prevented_damage += effective_reduction
                    state.activations += 1

            if species_variant == "ORC" and state.orc_guard > 0:
                reduction = min(hp_damage, state.orc_guard)
                effective_reduction = effective_prevented_hp(
                    hero_hp,
                    hp_damage,
                    reduction,
                )
                hp_damage -= reduction
                state.orc_guard -= reduction
                state.prevented_damage += effective_reduction
                if reduction > 0:
                    state.activations += 1

            previous_hp = hero_hp
            hero_hp = max(0, hero_hp - hp_damage)

            if species_variant == "ORC" and state.orc_guard_available:
                threshold = v13.core.multiply_bps(hero.max_hp, ORC_THRESHOLD_HP_BPS)
                crossed_alive = previous_hp > threshold and 0 < hero_hp <= threshold
                if crossed_alive:
                    state.orc_guard = v13.core.multiply_bps(
                        hero.max_hp,
                        ORC_GUARD_MAX_HP_BPS,
                    )
                    state.orc_guard_available = False

        if monster_hp <= 0:
            outcome = "WIN"
            break
        if hero_hp <= 0:
            outcome = "LOSS"
            break
    else:
        round_index = 30
        outcome = "RETREAT"

    return SpeciesBattleResult(
        outcome=outcome,
        rounds=round_index,
        hero_hp=hero_hp,
        hero_attempts=hero_attempts,
        hero_hits=hero_hits,
        hero_crits=hero_crits,
        monster_attempts=monster_attempts,
        monster_hits=monster_hits,
        monster_crits=monster_crits,
        added_damage=state.added_damage,
        prevented_damage=state.prevented_damage,
        activations=state.activations,
    )


def measure_species_cell(
    hero: v13.core.Combatant,
    monster: v13.core.Combatant,
    species_variant: str,
    seeds: int,
    seed_offset: int,
) -> SpeciesCellMetrics:
    results = [
        species_battle(hero, monster, species_variant, seed_offset + seed)
        for seed in range(seeds)
    ]
    hp_losses = [
        (hero.max_hp - result.hero_hp) * 100.0 / hero.max_hp
        for result in results
    ]
    contributions = [
        100.0 * (
            result.added_damage / monster.max_hp
            + result.prevented_damage / hero.max_hp
        )
        for result in results
    ]
    return SpeciesCellMetrics(
        win_rate=sum(result.outcome == "WIN" for result in results) / seeds,
        median_rounds=statistics.median(result.rounds for result in results),
        mean_hp_loss=statistics.fmean(hp_losses),
        mean_contribution_pct=statistics.fmean(contributions),
        p90_contribution_pct=v13.core.percentile(contributions, 0.90),
        activation_rate=sum(result.activations > 0 for result in results) / seeds,
        kill_throughput=(
            100.0 * sum(result.outcome == "WIN" for result in results)
            / max(1, sum(result.hero_attempts for result in results))
        ),
    )


def level_one_source(hero_class: str, band: str) -> v13.V13Source:
    class_def = next(value for value in creation.CLASSES if value.live_archetype == hero_class)
    if band == "BASE":
        background = creation.BACKGROUND_BY_ID[class_def.recommended_background]
        scores = creation.apply_background(class_def, background)
    elif band == "LOW":
        background = creation.background_without_roles(class_def)
        scores = creation.apply_background(
            class_def,
            background,
            creation.low_primary_assignment(class_def),
        )
    else:
        raise ValueError(band)
    return creation.source_for(class_def, scores, f"{hero_class}-CC-V01-{band}")


def combatants(
    hero_class: str,
    display_level: int,
    profile: str,
    monster_rank: str,
    band: str = "BASE",
) -> tuple[v13.core.Combatant, v13.core.Combatant]:
    if display_level == 1:
        source = level_one_source(hero_class, band)
        hero = v13.hero_combatant(source, "v1.3")
        monster = v13.monster_combatant(1, profile, monster_rank, "v1.3")
        return hero, monster
    source = v14.reference_source(hero_class, display_level, band)
    return (
        v14.hero_combatant(source),
        v14.monster_combatant(source.level, profile, monster_rank),
    )


def assert_neutral_compatibility() -> None:
    assert len(PERSISTED_TRAIT_CHOICE_IDS) == 6
    assert len(set(PERSISTED_TRAIT_CHOICE_IDS.values())) == 6
    assert all(
        value.startswith("aq.trait_choice.") and value == value.lower()
        for value in PERSISTED_TRAIT_CHOICE_IDS.values()
    )
    assert 0 < HUMAN_HIT_DELTA_BPS <= 500
    assert 0 < ELF_OPENING_HIT_DELTA_BPS <= 500
    assert 0 < DWARF_DAMAGE_REDUCTION_BPS <= 2_000
    assert 0 < DWARF_MAX_HP_CAP_BPS <= 500
    assert 0 < ORC_THRESHOLD_HP_BPS < 10_000
    assert 0 < ORC_GUARD_MAX_HP_BPS <= 500
    assert 0 < HALFLING_HIT_WINDOW_BPS <= 500
    assert 0 < HALFLING_MAX_HP_CAP_BPS <= 500
    assert 0 < DRAGONKIN_DAMAGE_REDUCTION_BPS <= 2_000
    assert 0 < DRAGONKIN_MAX_HP_CAP_BPS <= 500
    for hero_class in v13.CLASS_MULTIPLIERS:
        hero, monster = combatants(hero_class, 58, "STANDARD", "NORMAL", "BASE")
        for seed in (0, 1, 777, 9_999):
            expected = v13.core.battle(hero, monster, seed=seed)
            actual = species_battle(hero, monster, "NEUTRAL", seed=seed)
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
            assert actual.added_damage == actual.prevented_damage == actual.activations == 0


def pct(value: float) -> str:
    return f"{value:.2f}%"


def run_matrix(seeds: int) -> None:
    assert_neutral_compatibility()
    print(
        "[invariants] neutral_rng_compat=PASS raw_ability_bonus=NONE "
        "extra_action=0 reroll=0 level_multiplier=NONE"
    )

    rows: dict[str, list[tuple[str, SpeciesCellMetrics, SpeciesCellMetrics]]] = {
        variant: [] for variant in SPECIES_VARIANTS if variant != "NEUTRAL"
    }
    required_failures: list[str] = []
    diagnostic_failures: list[str] = []
    diagnostic_win_warnings: list[str] = []

    for hero_class in v13.CLASS_MULTIPLIERS:
        for band in BUILD_BANDS:
            for display_level in DISPLAY_LEVELS:
                for profile in v13.PROFILES:
                    hero, monster = combatants(
                        hero_class, display_level, profile, "NORMAL", band
                    )
                    seed_offset = 1_000_000 + display_level % 100_000
                    baseline = measure_species_cell(
                        hero, monster, "NEUTRAL", seeds, seed_offset
                    )
                    label = f"{hero_class}/{band}/L{display_level}/{profile}/NORMAL"
                    for variant in rows:
                        metrics = measure_species_cell(
                            hero, monster, variant, seeds, seed_offset
                        )
                        rows[variant].append((label, baseline, metrics))
                        win_delta = (metrics.win_rate - baseline.win_rate) * 100.0
                        rounds_gain = baseline.median_rounds - metrics.median_rounds
                        hp_gain = baseline.mean_hp_loss - metrics.mean_hp_loss
                        if (
                            metrics.mean_contribution_pct > 5.0
                            or win_delta > 0.5
                            or rounds_gain > 1.0
                            or hp_gain > 5.0
                        ):
                            required_failures.append(
                                f"{label}/{variant}: contribution={metrics.mean_contribution_pct:.2f} "
                                f"winDelta={win_delta:.2f} roundsGain={rounds_gain:.1f} hpGain={hp_gain:.2f}"
                            )

    diagnostic_seeds = max(100, seeds // 2)
    for hero_class in v13.CLASS_MULTIPLIERS:
        for display_level in DIAGNOSTIC_LEVELS:
            for profile in v13.PROFILES:
                for monster_rank in ("ELITE", "BOSS"):
                    hero, monster = combatants(
                        hero_class, display_level, profile, monster_rank, "BASE"
                    )
                    seed_offset = 2_000_000 + display_level % 100_000
                    baseline = measure_species_cell(
                        hero, monster, "NEUTRAL", diagnostic_seeds, seed_offset
                    )
                    label = f"{hero_class}/L{display_level}/{profile}/{monster_rank}"
                    for variant in rows:
                        metrics = measure_species_cell(
                            hero, monster, variant, diagnostic_seeds, seed_offset
                        )
                        rows[variant].append((label, baseline, metrics))
                        win_delta = (metrics.win_rate - baseline.win_rate) * 100.0
                        win_cap = 3.0 if monster_rank == "ELITE" else 5.0
                        if metrics.mean_contribution_pct > 10.0:
                            diagnostic_failures.append(
                                f"{label}/{variant}: contribution={metrics.mean_contribution_pct:.2f}"
                            )
                        if win_delta > win_cap:
                            diagnostic_win_warnings.append(
                                f"{label}/{variant}: winDelta={win_delta:.2f}"
                            )

    print("\n[species candidate summary]")
    print(
        "variant normalMedian normalMax allRankMax normalWinDeltaMax "
        "normalHpGainMax activationMedian"
    )
    for variant, values in rows.items():
        normal_values = [value for value in values if value[0].endswith("/NORMAL")]
        contributions = [value[2].mean_contribution_pct for value in normal_values]
        all_contributions = [value[2].mean_contribution_pct for value in values]
        win_deltas = [
            (metrics.win_rate - baseline.win_rate) * 100.0
            for _, baseline, metrics in normal_values
        ]
        hp_gains = [
            baseline.mean_hp_loss - metrics.mean_hp_loss
            for _, baseline, metrics in normal_values
        ]
        activation_rates = [metrics.activation_rate * 100.0 for _, _, metrics in normal_values]
        print(
            f"{variant:20} "
            f"{pct(statistics.median(contributions)):>12} "
            f"{pct(max(contributions)):>9} "
            f"{pct(max(all_contributions)):>10} "
            f"{pct(max(win_deltas)):>17} "
            f"{pct(max(hp_gains)):>15} "
            f"{pct(statistics.median(activation_rates)):>16}"
        )

    drift_failures = []
    for variant, values in rows.items():
        lookup = {label: metrics for label, _, metrics in values}
        for hero_class in v13.CLASS_MULTIPLIERS:
            for band in BUILD_BANDS:
                for profile in v13.PROFILES:
                    low = lookup[f"{hero_class}/{band}/L100/{profile}/NORMAL"]
                    high = lookup[
                        f"{hero_class}/{band}/L{v14.DISPLAY_LEVEL_MAX}/{profile}/NORMAL"
                    ]
                    if abs(high.mean_contribution_pct - low.mean_contribution_pct) > 2.0:
                        drift_failures.append(f"{variant}/{hero_class}/{band}/{profile}")

    print(
        f"\nrequired_normal_cells={3 * 2 * 4 * 4 * 7} "
        f"diagnostic_elite_boss_cells={3 * 2 * 4 * 2 * 7} "
        f"simulated_battles={3 * 2 * 4 * 4 * 8 * seeds + 3 * 2 * 4 * 2 * 8 * diagnostic_seeds:,}"
    )
    print(f"required_failures={len(required_failures)}")
    print(f"diagnostic_cap_failures={len(diagnostic_failures)}")
    print(f"diagnostic_win_threshold_warnings={len(diagnostic_win_warnings)}")
    print(f"asymptotic_drift_failures={len(drift_failures)}")
    for failure in (required_failures + diagnostic_failures + drift_failures)[:20]:
        print(f"FAIL {failure}")
    for warning in diagnostic_win_warnings[:10]:
        print(f"WARN {warning}")
    if required_failures or diagnostic_failures or drift_failures:
        raise SystemExit("PD_GATE=FAIL")
    print("PD_GATE=PASS species_numbers=PROVISIONAL_THREE_CLASS_GO")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=1_000)
    args = parser.parse_args()
    if not 250 <= args.seeds <= 10_000:
        raise SystemExit("--seeds must be in 250..10000")
    print(f"AlarmQuest species traits v0.1 review; seeds_per_normal_cell={args.seeds}")
    run_matrix(args.seeds)


if __name__ == "__main__":
    main()
