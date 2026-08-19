#!/usr/bin/env python3
"""Design-only audit for AlarmQuest's six-class basic-combat candidate v1.5.

The existing Warrior/Rogue/Mage equations are preserved. Ranger/Cleric/
Paladin are layered into the same bounded-stat and CombatRank contracts with
species, skills, items effects, live Room, and production resolvers disabled.
"""

from __future__ import annotations

import argparse
import math
import statistics
from dataclasses import dataclass, replace

import base_combat_v1_3_review as v13
import base_combat_v1_4_long_term_review as v14


CLASSES = ("WARRIOR", "ROGUE", "RANGER", "MAGE", "CLERIC", "PALADIN")
NEW_CLASSES = ("RANGER", "CLERIC", "PALADIN")
CLASS_IDS = {
    "WARRIOR": "aq.class.warrior",
    "ROGUE": "aq.class.rogue",
    "RANGER": "aq.class.ranger",
    "MAGE": "aq.class.mage",
    "CLERIC": "aq.class.cleric",
    "PALADIN": "aq.class.paladin",
}
ABILITIES = ("STR", "DEX", "CON", "INT", "WIS", "CHA")
ABILITY_INDEX = {ability: index for index, ability in enumerate(ABILITIES)}

CLASS_MULTIPLIERS = {
    "WARRIOR": dict(hp=1.15, mp=0.65, patk=1.05, matk=0.80, pdef=1.10, mres=0.95),
    "ROGUE": dict(hp=1.10, mp=0.85, patk=1.02, matk=0.90, pdef=0.95, mres=1.00),
    "RANGER": dict(hp=1.18, mp=0.95, patk=0.99, matk=0.90, pdef=1.09, mres=1.10),
    "MAGE": dict(hp=1.15, mp=1.25, patk=0.80, matk=1.08, pdef=1.00, mres=1.10),
    "CLERIC": dict(hp=1.12, mp=1.10, patk=0.88, matk=1.00, pdef=1.08, mres=1.12),
    "PALADIN": dict(hp=1.20, mp=0.90, patk=1.00, matk=0.92, pdef=1.08, mres=1.60),
}

CLASS_BONUSES = {
    "WARRIOR": dict(speed=0, evasion=0, pacc=0, macc=0, critical=0),
    "ROGUE": dict(speed=12, evasion=8, pacc=4, macc=0, critical=6),
    "RANGER": dict(speed=8, evasion=4, pacc=6, macc=0, critical=2),
    "MAGE": dict(speed=4, evasion=2, pacc=0, macc=2, critical=0),
    "CLERIC": dict(speed=0, evasion=0, pacc=0, macc=4, critical=0),
    "PALADIN": dict(speed=-2, evasion=0, pacc=1, macc=0, critical=0),
}

PRIMARY = {
    "WARRIOR": "STR",
    "ROGUE": "DEX",
    "RANGER": "DEX",
    "MAGE": "INT",
    "CLERIC": "WIS",
    "PALADIN": "STR",
}

SECONDARY = {
    "WARRIOR": "CON",
    "ROGUE": "WIS",
    "RANGER": "WIS",
    "MAGE": "WIS",
    "CLERIC": "CON",
    "PALADIN": "CHA",
}

RESOURCE_ABILITY = {
    "WARRIOR": "INT",
    "ROGUE": "INT",
    "RANGER": "WIS",
    "MAGE": "INT",
    "CLERIC": "WIS",
    "PALADIN": "CHA",
}

ATTACK_TYPE = {
    "WARRIOR": "PHYSICAL",
    "ROGUE": "PHYSICAL",
    "RANGER": "PHYSICAL",
    "MAGE": "MAGIC",
    "CLERIC": "MAGIC",
    "PALADIN": "PHYSICAL",
}

AQ_CHARACTER_V1_ARRAYS = {
    # Standard array 15/14/13/12/10/8 plus the recommended background's
    # bounded +2/+1 assignment. Every new-character fixture totals 75.
    "WARRIOR": (17, 13, 15, 8, 12, 10),
    "ROGUE": (10, 17, 13, 12, 15, 8),
    "RANGER": (12, 17, 13, 10, 15, 8),
    "MAGE": (8, 12, 13, 17, 15, 10),
    "CLERIC": (12, 8, 15, 10, 17, 13),
    "PALADIN": (17, 10, 13, 8, 12, 15),
}

START_GROWTH = {
    "WARRIOR": (6, 5),
    "ROGUE": (6, 5),
    "RANGER": (6, 5),
    "MAGE": (5, 6),
    "CLERIC": (6, 6),
    "PALADIN": (7, 5),
}

LEVEL_GROWTH = {
    "WARRIOR": (4, 1),
    "ROGUE": (3, 2),
    "RANGER": (3, 2),
    "MAGE": (2, 4),
    "CLERIC": (3, 3),
    "PALADIN": (4, 2),
}

ANCHORS = {level: (tier, weapon, armor) for level, tier, weapon, armor in v13.ANCHORS}
DISPLAY_LEVELS = (1, 10, 28, 58, 100, v14.DISPLAY_LEVEL_MAX)
LONG_LEVELS = (100, 5_000, 1_000_000_000, v14.DISPLAY_LEVEL_MAX)
BUILD_BANDS = ("BASE", "STRESS_ALL3", "STRESS_ALL18")


@dataclass(frozen=True)
class SixClassSource:
    label: str
    hero_class: str
    display_level: int
    combat_rank: int
    world_tier: int
    natural_stats: tuple[float, float, float, float, float, float]
    health_growth: float
    mana_growth: float
    weapon_power: float
    armor_power: float
    ability_bonuses: tuple[float, float, float, float, float, float] = (0, 0, 0, 0, 0, 0)
    set_scores: tuple[float, float, float, float, float, float] = (0, 0, 0, 0, 0, 0)
    artifact_cap: bool = False


@dataclass(frozen=True)
class DerivedHero:
    combatant: v13.core.Combatant
    combat_stats: tuple[float, float, float, float, float, float]
    max_mp: int
    status_resistance: int


@dataclass(frozen=True)
class CellMetrics:
    win_rate: float
    loss_rate: float
    retreat_rate: float
    mean_rounds: float
    median_rounds: float
    p90_rounds: int
    mean_hp_loss: float
    p90_hp_loss: float
    hero_hit_rate: float
    hero_crit_rate_on_hit: float
    kill_throughput: float


def round_positive(value: float) -> int:
    return math.floor(value + 0.5)


def source_for_display(hero_class: str, display_level: int, band: str = "BASE") -> SixClassSource:
    if hero_class not in CLASSES:
        raise ValueError(hero_class)
    if band not in BUILD_BANDS:
        raise ValueError(band)
    safe_display = max(1, min(v14.DISPLAY_LEVEL_MAX, int(display_level)))
    rank = v14.combat_rank(safe_display)
    if safe_display in ANCHORS:
        tier, weapon_power, armor_power = ANCHORS[safe_display]
    elif safe_display > 100:
        tier = 5
        weapon_power = v14.reference_gear_power(rank)
        armor_power = weapon_power
    else:
        raise ValueError("Only anchor display levels or levels above 100 are supported")

    if band == "STRESS_ALL3":
        natural = [3.0] * 6
    elif band == "STRESS_ALL18":
        natural = [18.0] * 6
    else:
        natural = list(AQ_CHARACTER_V1_ARRAYS[hero_class])
    primary_index = ABILITY_INDEX[PRIMARY[hero_class]]
    natural[primary_index] = min(20, natural[primary_index] + max(0, tier - 1))

    start_hp, start_mp = START_GROWTH[hero_class]
    hp_growth, mp_growth = LEVEL_GROWTH[hero_class]
    return SixClassSource(
        label=f"{hero_class}-{band}-L{safe_display}-C{rank}",
        hero_class=hero_class,
        display_level=safe_display,
        combat_rank=rank,
        world_tier=tier,
        natural_stats=tuple(natural),
        health_growth=start_hp + hp_growth * (rank - 1),
        mana_growth=start_mp + mp_growth * (rank - 1),
        weapon_power=weapon_power,
        armor_power=armor_power,
    )


def six_source_from_legacy(legacy: v13.V13Source, display_level: int) -> SixClassSource:
    """Lift a five-ability legacy fixture without changing its stored inputs."""
    return SixClassSource(
        label=f"LEGACY_PQ_V1-{legacy.label}",
        hero_class=legacy.hero_class,
        display_level=display_level,
        combat_rank=legacy.level,
        world_tier=legacy.world_tier,
        natural_stats=(
            legacy.strength,
            legacy.dexterity,
            legacy.constitution,
            legacy.intelligence,
            legacy.wisdom,
            10,
        ),
        health_growth=legacy.health_growth,
        mana_growth=legacy.mana_growth,
        weapon_power=legacy.weapon_power,
        armor_power=legacy.armor_power,
        ability_bonuses=(*legacy.ability_bonuses, 0),
        set_scores=(*legacy.set_scores, 0),
        artifact_cap=legacy.artifact_cap,
    )


def legacy_source_for_anchor(hero_class: str, display_level: int, band: str) -> SixClassSource:
    """Convert an approved v1.3 fixture without changing its stored inputs."""
    tier, weapon_power, armor_power = ANCHORS[display_level]
    legacy = v13.anchor_source(
        hero_class,
        display_level,
        tier,
        weapon_power,
        armor_power,
        band,
    )
    return six_source_from_legacy(legacy, display_level)


def training_floors(world_tier: int) -> tuple[int, int, int]:
    offset = max(0, world_tier - 1)
    return (
        min(12, 8 + offset),
        min(20, 12 + 2 * offset),
        min(18, 10 + 2 * offset),
    )


def combat_attributes(source: SixClassSource) -> tuple[float, float, float, float, float, float]:
    natural = [v13.clamp_float(value, 3, 20) for value in source.natural_stats]
    general, primary_floor, secondary_floor = training_floors(source.world_tier)
    trained = [max(value, general) for value in natural]
    trained[ABILITY_INDEX[PRIMARY[source.hero_class]]] = max(
        trained[ABILITY_INDEX[PRIMARY[source.hero_class]]],
        primary_floor,
    )
    trained[ABILITY_INDEX[SECONDARY[source.hero_class]]] = max(
        trained[ABILITY_INDEX[SECONDARY[source.hero_class]]],
        secondary_floor,
    )

    item_cap = 24 if source.artifact_cap else 22
    result = []
    for trained_value, raw_bonus, set_score in zip(
        trained,
        source.ability_bonuses,
        source.set_scores,
    ):
        bonus = v13.clamp_float(raw_bonus, -4, 2)
        result.append(v13.clamp_float(max(trained_value + bonus, set_score), 3, item_cap))
    return tuple(result)


def physical_attribute_term(hero_class: str, stats: tuple[float, ...]) -> float:
    strength, dexterity, _, _, wisdom, charisma = stats
    if hero_class == "ROGUE":
        return 1.00 * dexterity + 0.35 * strength
    if hero_class == "RANGER":
        return 0.95 * dexterity + 0.35 * wisdom
    if hero_class == "PALADIN":
        return 1.15 * strength + 0.35 * dexterity
    return 1.15 * strength + 0.35 * dexterity


def magical_attribute_term(hero_class: str, stats: tuple[float, ...]) -> float:
    _, _, _, intelligence, wisdom, _ = stats
    if hero_class == "CLERIC":
        return 1.05 * wisdom + 0.45 * intelligence
    return 1.15 * intelligence + 0.35 * wisdom


def derive_hero(source: SixClassSource) -> DerivedHero:
    hero_class = source.hero_class
    level = source.combat_rank
    stats = combat_attributes(source)
    strength, dexterity, constitution, intelligence, wisdom, charisma = stats
    multipliers = CLASS_MULTIPLIERS[hero_class]
    bonuses = CLASS_BONUSES[hero_class]

    physical_weapon_factor = {
        "WARRIOR": 1.00,
        "ROGUE": 1.00,
        "RANGER": 1.00,
        "MAGE": 0.50,
        "CLERIC": 0.50,
        "PALADIN": 1.00,
    }[hero_class]
    magical_weapon_factor = {
        "WARRIOR": 0.25,
        "ROGUE": 0.30,
        "RANGER": 0.35,
        "MAGE": 1.00,
        "CLERIC": 1.00,
        "PALADIN": 0.45,
    }[hero_class]

    physical_attack = round_positive(
        (
            10
            + 1.4 * level
            + physical_attribute_term(hero_class, stats)
            + 1.8 * source.weapon_power * physical_weapon_factor
        )
        * multipliers["patk"]
    )
    magical_attack = round_positive(
        (
            10
            + 1.4 * level
            + magical_attribute_term(hero_class, stats)
            + 1.8 * source.weapon_power * magical_weapon_factor
        )
        * multipliers["matk"]
    )
    physical_defense = round_positive(
        (4 + 0.55 * constitution + 0.20 * strength + 2.4 * source.armor_power)
        * multipliers["pdef"]
    )
    equipment_ward = source.armor_power * 0.60
    if hero_class == "PALADIN":
        resistance_attribute_term = 0.35 * wisdom + 0.10 * intelligence + 0.35 * charisma
    else:
        resistance_attribute_term = 0.60 * wisdom + 0.20 * intelligence
    magical_resistance = round_positive(
        (4 + resistance_attribute_term + 2.0 * equipment_ward)
        * multipliers["mres"]
    )

    if hero_class == "RANGER":
        physical_accuracy_attributes = 1.25 * dexterity + 0.35 * wisdom
    else:
        physical_accuracy_attributes = 1.25 * dexterity + 0.35 * strength
    physical_accuracy = round_positive(
        10 + physical_accuracy_attributes + 0.50 * level + bonuses["pacc"]
    )

    if hero_class == "CLERIC":
        magical_accuracy_attributes = 1.05 * wisdom + 0.55 * intelligence
    else:
        magical_accuracy_attributes = 1.00 * intelligence + 0.60 * wisdom
    magical_accuracy = round_positive(
        10 + magical_accuracy_attributes + 0.50 * level + bonuses["macc"]
    )

    evasion = round_positive(
        8 + 1.35 * dexterity + 0.25 * wisdom + 0.45 * level + bonuses["evasion"]
    )
    primary_stat = stats[ABILITY_INDEX[PRIMARY[hero_class]]]
    critical = round_positive(
        5 + 1.10 * dexterity + 0.20 * primary_stat + bonuses["critical"]
    )
    critical_resistance = round_positive(5 + 0.65 * constitution + 0.45 * wisdom)
    status_resistance = round_positive(5 + 0.75 * wisdom + 0.25 * constitution)
    speed = round_positive(60 + 2.0 * dexterity + bonuses["speed"])

    max_hp = round_positive(
        (
            80
            + 8 * level
            + 6 * constitution
            + 4 * source.health_growth
        )
        * multipliers["hp"]
    )
    resource_stat = stats[ABILITY_INDEX[RESOURCE_ABILITY[hero_class]]]
    max_mp = max(
        0,
        round_positive(
            (
                12
                + 3 * level
                + 3 * resource_stat
                + 4 * source.mana_growth
            )
            * multipliers["mp"]
        ),
    )

    combatant = v13.core.Combatant(
        name=source.label,
        level=level,
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
        attack_type=ATTACK_TYPE[hero_class],
        action_coefficient_bps=10_000,
    )
    return DerivedHero(
        combatant=combatant,
        combat_stats=stats,
        max_mp=max_mp,
        status_resistance=status_resistance,
    )


def monster_for(source: SixClassSource, profile: str, monster_rank: str) -> v13.core.Combatant:
    if source.combat_rank <= 100:
        return v13.monster_combatant(source.combat_rank, profile, monster_rank, "v1.3")
    return v14.monster_combatant(source.combat_rank, profile, monster_rank)


def measure_cell(
    source: SixClassSource,
    profile: str,
    monster_rank: str,
    seeds: int,
    seed_offset: int,
    rng_monster_name: str | None = None,
) -> CellMetrics:
    hero = derive_hero(source).combatant
    monster = monster_for(source, profile, monster_rank)
    if rng_monster_name is not None:
        monster = replace(monster, name=rng_monster_name)
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
    wins = sum(result.outcome == "WIN" for result in results)
    return CellMetrics(
        win_rate=wins / seeds,
        loss_rate=sum(result.outcome == "LOSS" for result in results) / seeds,
        retreat_rate=sum(result.outcome == "RETREAT" for result in results) / seeds,
        mean_rounds=statistics.fmean(result.rounds for result in results),
        median_rounds=statistics.median(result.rounds for result in results),
        p90_rounds=int(v13.core.percentile([result.rounds for result in results], 0.90)),
        mean_hp_loss=statistics.fmean(hp_losses),
        p90_hp_loss=v13.core.percentile(hp_losses, 0.90),
        hero_hit_rate=hits / max(1, attempts),
        hero_crit_rate_on_hit=crits / max(1, hits),
        kill_throughput=100.0 * wins / max(1, attempts),
    )


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
    assert len(CLASSES) == len(set(CLASSES)) == 6
    assert set(CLASS_IDS) == set(CLASSES)
    assert len(set(CLASS_IDS.values())) == 6
    assert all(value.startswith("aq.class.") for value in CLASS_IDS.values())
    assert set(CLASS_MULTIPLIERS) == set(CLASS_BONUSES) == set(CLASSES)
    assert set(PRIMARY) == set(SECONDARY) == set(RESOURCE_ABILITY) == set(CLASSES)
    assert set(ATTACK_TYPE) == set(AQ_CHARACTER_V1_ARRAYS) == set(CLASSES)
    assert set(START_GROWTH) == set(LEVEL_GROWTH) == set(CLASSES)

    for hero_class in CLASSES:
        assert PRIMARY[hero_class] in ABILITIES
        assert SECONDARY[hero_class] in ABILITIES
        assert PRIMARY[hero_class] != SECONDARY[hero_class]
        assert RESOURCE_ABILITY[hero_class] in ABILITIES
        assert len(AQ_CHARACTER_V1_ARRAYS[hero_class]) == 6
        assert sum(AQ_CHARACTER_V1_ARRAYS[hero_class]) == 75

    # Preserve every approved v1.3 numeric field for the original classes.
    for level, tier, weapon, armor in v13.ANCHORS:
        for hero_class in ("WARRIOR", "ROGUE", "MAGE"):
            for band in ("LOW", "BASE", "HIGH"):
                legacy = v13.anchor_source(hero_class, level, tier, weapon, armor, band)
                expected = v13.hero_combatant(legacy, "v1.3")
                candidate = derive_hero(
                    legacy_source_for_anchor(hero_class, level, band)
                ).combatant
                assert numeric_fields(candidate) == numeric_fields(expected)
                assert candidate.attack_type == expected.attack_type
                for profile in v13.PROFILES:
                    monster = v13.monster_combatant(level, profile, "NORMAL", "v1.3")
                    for seed in (0, 1, 777, 9_999):
                        assert v13.core.battle(expected, monster, seed=seed) == v13.core.battle(
                            candidate,
                            monster,
                            seed=seed,
                        )

    # Preserve the approved v1.4 long-horizon formula and deterministic battle
    # stream when the exact same legacy source snapshot is supplied.
    for display_level in (100, 5_000, 1_000_000_000, v14.DISPLAY_LEVEL_MAX):
        for hero_class in ("WARRIOR", "ROGUE", "MAGE"):
            for band in ("BASE", "LOW"):
                legacy = v14.reference_source(hero_class, display_level, band)
                expected = v14.hero_combatant(legacy)
                candidate = derive_hero(
                    six_source_from_legacy(legacy, display_level)
                ).combatant
                assert numeric_fields(candidate) == numeric_fields(expected)
                assert candidate.attack_type == expected.attack_type
                for profile in v13.PROFILES:
                    monster = v14.monster_combatant(legacy.level, profile, "NORMAL")
                    for seed in (0, 777):
                        assert v13.core.battle(expected, monster, seed=seed) == v13.core.battle(
                            candidate,
                            monster,
                            seed=seed,
                        )

    # Primary/secondary TrainingFloor must rescue all-3 for every class.
    for hero_class in CLASSES:
        low = source_for_display(hero_class, 100, "STRESS_ALL3")
        stats = combat_attributes(low)
        assert stats[ABILITY_INDEX[PRIMARY[hero_class]]] >= 20
        assert stats[ABILITY_INDEX[SECONDARY[hero_class]]] >= 18

    # WIS and CHA have explicit, bounded combat/resource meaning in new classes.
    sensitivity = {}
    for hero_class, ability in (("RANGER", "WIS"), ("CLERIC", "WIS"), ("PALADIN", "CHA")):
        base = source_for_display(hero_class, 1, "BASE")
        index = ABILITY_INDEX[ability]
        raised_stats = list(base.natural_stats)
        raised_stats[index] = min(20, raised_stats[index] + 2)
        raised = replace(base, natural_stats=tuple(raised_stats))
        before = derive_hero(base)
        after = derive_hero(raised)
        sensitivity[hero_class] = (
            numeric_fields(after.combatant) != numeric_fields(before.combatant)
            and after.max_mp > before.max_mp
        )
    assert all(sensitivity.values())

    # Any single source-stat or reference-Power increase is monotone: it may be
    # hidden by a TrainingFloor/cap, but it can never lower a derived field.
    for hero_class in CLASSES:
        for display_level in (1, 100, v14.DISPLAY_LEVEL_MAX):
            base = source_for_display(hero_class, display_level, "BASE")
            before = derive_hero(base)
            before_fields = (
                *numeric_fields(before.combatant),
                before.max_mp,
                before.status_resistance,
            )
            for index, value in enumerate(base.natural_stats):
                raised_stats = list(base.natural_stats)
                raised_stats[index] = min(20, value + 1)
                after = derive_hero(replace(base, natural_stats=tuple(raised_stats)))
                after_fields = (
                    *numeric_fields(after.combatant),
                    after.max_mp,
                    after.status_resistance,
                )
                assert all(new >= old for old, new in zip(before_fields, after_fields))
            powered = derive_hero(
                replace(
                    base,
                    weapon_power=base.weapon_power + 1,
                    armor_power=base.armor_power + 1,
                )
            )
            powered_fields = (
                *numeric_fields(powered.combatant),
                powered.max_mp,
                powered.status_resistance,
            )
            assert all(new >= old for old, new in zip(before_fields, powered_fields))

    # Long.MAX fields remain signed-Int safe and strictly positive where required.
    max_field = 0
    for hero_class in CLASSES:
        derived = derive_hero(source_for_display(hero_class, v14.DISPLAY_LEVEL_MAX))
        max_field = max(max_field, derived.max_mp, *numeric_fields(derived.combatant))
    assert max_field < 2_147_483_647

    print(
        "[invariants] class_ids=PASS aq_character_v1_budget=PASS "
        "legacy_three_class_formula_exact=PASS legacy_battle_rng_exact=PASS "
        "training_floor_six=PASS wis_cha_sensitivity=PASS "
        "derived_monotonicity=PASS long_int_safe=PASS"
    )


def pct_ratio(value: float) -> str:
    return f"{value * 100:.1f}%"


def run_normal_matrix(seeds: int) -> list[str]:
    print("\n[six-class species-OFF NORMAL core]")
    print("class band levels winMin roundsRange hpLossP90Max hitRange critRange")
    failures = []
    round_targets = {
        "STANDARD": (4, 10),
        "SWIFT": (4, 8),
        "ARMORED": (7, 15),
        "SPELLCASTER": (4, 10),
    }
    for hero_class in CLASSES:
        for band in BUILD_BANDS:
            values: list[tuple[str, int, CellMetrics]] = []
            for display_level in DISPLAY_LEVELS:
                source = source_for_display(hero_class, display_level, band)
                for profile in v13.PROFILES:
                    metrics = measure_cell(
                        source,
                        profile,
                        "NORMAL",
                        seeds,
                        seed_offset=3_000_000 + source.combat_rank,
                    )
                    values.append((profile, display_level, metrics))
                    round_min, round_max = round_targets[profile]
                    hit_min = 0.82 if profile == "SWIFT" else 0.875
                    if not (
                        metrics.win_rate >= 0.995
                        and metrics.retreat_rate < 0.001
                        and round_min <= metrics.median_rounds <= round_max
                        and metrics.p90_rounds <= 16
                        and metrics.p90_hp_loss <= 55.0
                        and hit_min <= metrics.hero_hit_rate <= 0.995
                    ):
                        failures.append(f"{hero_class}/{band}/L{display_level}/{profile}")
            print(
                f"{hero_class:7} {band:12} "
                f"{pct_ratio(min(value.win_rate for _, _, value in values)):>7} "
                f"{min(value.median_rounds for _, _, value in values):.0f}-"
                f"{max(value.median_rounds for _, _, value in values):.0f} "
                f"{max(value.p90_hp_loss for _, _, value in values):5.1f}% "
                f"{pct_ratio(min(value.hero_hit_rate for _, _, value in values))}-"
                f"{pct_ratio(max(value.hero_hit_rate for _, _, value in values))} "
                f"{pct_ratio(min(value.hero_crit_rate_on_hit for _, _, value in values))}-"
                f"{pct_ratio(max(value.hero_crit_rate_on_hit for _, _, value in values))}"
            )
    print(f"normal_gate_failures={len(failures)}/{len(CLASSES) * len(BUILD_BANDS) * len(DISPLAY_LEVELS) * 4}")
    return failures


def run_low_edge_probes(seeds: int) -> list[str]:
    """Recheck the two STRESS_ALL3/L1 cells closest to the 55% HP-loss gate."""
    print("\n[STRESS_ALL3/L1/ARMORED edge probes]")
    print("class seeds win retreat roundsMedian/P90 hpLossP90 hit")
    failures = []
    for hero_class in ("ROGUE", "PALADIN"):
        source = source_for_display(hero_class, 1, "STRESS_ALL3")
        metrics = measure_cell(
            source,
            "ARMORED",
            "NORMAL",
            seeds,
            seed_offset=3_900_000,
        )
        passed = (
            metrics.win_rate >= 0.995
            and metrics.retreat_rate < 0.001
            and 7 <= metrics.median_rounds <= 15
            and metrics.p90_rounds <= 16
            and metrics.p90_hp_loss <= 55.0
            and 0.875 <= metrics.hero_hit_rate <= 0.995
        )
        if not passed:
            failures.append(hero_class)
        print(
            f"{hero_class:7} {seeds:5} {pct_ratio(metrics.win_rate)} "
            f"{pct_ratio(metrics.retreat_rate)} "
            f"{metrics.median_rounds:.0f}/{metrics.p90_rounds} "
            f"{metrics.p90_hp_loss:.2f}% {pct_ratio(metrics.hero_hit_rate)}"
        )
    print(f"low_edge_failures={len(failures)}/2")
    return failures


def run_long_drift(seeds: int) -> list[str]:
    print("\n[six-class paired C100 vs Long.MAX drift; BASE/NORMAL]")
    print("class profile rounds100/MAX hpLoss100/MAX hit100/MAX")
    failures = []
    for hero_class in CLASSES:
        low_source = source_for_display(hero_class, 100, "BASE")
        high_source = source_for_display(hero_class, v14.DISPLAY_LEVEL_MAX, "BASE")
        for profile in v13.PROFILES:
            rng_monster_name = f"PAIRED-DRIFT/{hero_class}/BASE/{profile}/NORMAL"
            low = measure_cell(
                low_source,
                profile,
                "NORMAL",
                seeds,
                4_000_000,
                rng_monster_name,
            )
            high = measure_cell(
                high_source,
                profile,
                "NORMAL",
                seeds,
                4_000_000,
                rng_monster_name,
            )
            passed = (
                abs(high.median_rounds - low.median_rounds) <= 1
                and abs(high.mean_hp_loss - low.mean_hp_loss) <= 5.0
                and abs(high.hero_hit_rate - low.hero_hit_rate) <= 0.02
            )
            if not passed:
                failures.append(f"{hero_class}/{profile}")
            print(
                f"{hero_class:7} {profile:11} "
                f"{low.median_rounds:.0f}/{high.median_rounds:.0f} "
                f"{low.mean_hp_loss:.1f}/{high.mean_hp_loss:.1f}% "
                f"{pct_ratio(low.hero_hit_rate)}/{pct_ratio(high.hero_hit_rate)}"
            )
    print(f"long_drift_failures={len(failures)}/24")
    return failures


def run_low_stat_long(seeds: int) -> list[str]:
    print("\n[six-class all-3 long rescue; STANDARD/NORMAL]")
    print("class displayLevels winMin roundsRange hpLossP90Max")
    failures = []
    for hero_class in CLASSES:
        values = []
        for display_level in LONG_LEVELS:
            source = source_for_display(hero_class, display_level, "STRESS_ALL3")
            metrics = measure_cell(
                source,
                "STANDARD",
                "NORMAL",
                seeds,
                seed_offset=5_000_000 + source.combat_rank,
            )
            values.append(metrics)
            if not (
                metrics.win_rate >= 0.95
                and metrics.retreat_rate < 0.001
                and metrics.p90_hp_loss <= 55.0
            ):
                failures.append(f"{hero_class}/L{display_level}")
        print(
            f"{hero_class:7} "
            f"{pct_ratio(min(value.win_rate for value in values)):>7} "
            f"{min(value.median_rounds for value in values):.0f}-"
            f"{max(value.median_rounds for value in values):.0f} "
            f"{max(value.p90_hp_loss for value in values):5.1f}%"
        )
    print(f"low_stat_long_failures={len(failures)}/24")
    return failures


def run_role_diagnostics(seeds: int) -> list[str]:
    print("\n[new-class role diagnostics at C100; BASE/NORMAL]")
    metrics = {
        hero_class: {
            profile: measure_cell(
                source_for_display(hero_class, 100, "BASE"),
                profile,
                "NORMAL",
                seeds,
                seed_offset=6_000_000,
            )
            for profile in v13.PROFILES
        }
        for hero_class in CLASSES
    }
    failures = []
    weights = {
        "STANDARD": 0.40,
        "SWIFT": 0.25,
        "ARMORED": 0.20,
        "SPELLCASTER": 0.15,
    }
    weighted_throughput = {
        hero_class: sum(
            weights[profile] * metrics[hero_class][profile].kill_throughput
            for profile in weights
        )
        for hero_class in CLASSES
    }
    throughput_mean = statistics.fmean(weighted_throughput.values())

    # Ranger: less critical burst than Rogue, but at least as accurate against SWIFT.
    if not (
        metrics["RANGER"]["SWIFT"].hero_hit_rate >= metrics["ROGUE"]["SWIFT"].hero_hit_rate
        and metrics["RANGER"]["STANDARD"].hero_crit_rate_on_hit
        < metrics["ROGUE"]["STANDARD"].hero_crit_rate_on_hit
        and metrics["RANGER"]["ARMORED"].mean_hp_loss
        <= metrics["ROGUE"]["ARMORED"].mean_hp_loss - 3.0
        and metrics["RANGER"]["ARMORED"].mean_rounds
        >= metrics["ROGUE"]["ARMORED"].mean_rounds
    ):
        failures.append("RANGER_ROLE")

    # Cleric: slower or equal kill pace than Mage, but lower HP loss in both damage types.
    if not (
        metrics["CLERIC"]["STANDARD"].mean_rounds >= metrics["MAGE"]["STANDARD"].mean_rounds
        and metrics["CLERIC"]["STANDARD"].mean_hp_loss
        <= metrics["MAGE"]["STANDARD"].mean_hp_loss - 3.0
        and metrics["CLERIC"]["SPELLCASTER"].mean_hp_loss
        <= metrics["MAGE"]["SPELLCASTER"].mean_hp_loss - 3.0
    ):
        failures.append("CLERIC_ROLE")

    # Paladin: slower or equal kill pace than Warrior, with better magical durability.
    if not (
        metrics["PALADIN"]["STANDARD"].mean_rounds >= metrics["WARRIOR"]["STANDARD"].mean_rounds
        and metrics["PALADIN"]["SPELLCASTER"].mean_hp_loss
        <= metrics["WARRIOR"]["SPELLCASTER"].mean_hp_loss * 0.90
        and metrics["PALADIN"]["ARMORED"].mean_rounds
        >= metrics["WARRIOR"]["ARMORED"].mean_rounds + 0.5
    ):
        failures.append("PALADIN_ROLE")

    for hero_class, value in weighted_throughput.items():
        if not 0.85 * throughput_mean <= value <= 1.15 * throughput_mean:
            failures.append(f"{hero_class}_THROUGHPUT")

    ranger_rogue_gap = abs(
        weighted_throughput["RANGER"] - weighted_throughput["ROGUE"]
    ) / weighted_throughput["ROGUE"]
    cleric_mage_gap = (
        weighted_throughput["MAGE"] - weighted_throughput["CLERIC"]
    ) / weighted_throughput["MAGE"]
    paladin_warrior_gap = (
        weighted_throughput["WARRIOR"] - weighted_throughput["PALADIN"]
    ) / weighted_throughput["WARRIOR"]
    if ranger_rogue_gap > 0.05:
        failures.append("RANGER_ROGUE_THROUGHPUT_PAIR")
    if not 0.05 <= cleric_mage_gap <= 0.15:
        failures.append("CLERIC_MAGE_THROUGHPUT_PAIR")
    if not 0.00 <= paladin_warrior_gap <= 0.10:
        failures.append("PALADIN_WARRIOR_THROUGHPUT_PAIR")

    for hero_class in NEW_CLASSES:
        derived = derive_hero(source_for_display(hero_class, 100, "BASE"))
        print(
            f"{hero_class:7} stats="
            f"{'/'.join(str(round_positive(value)) for value in derived.combat_stats)} "
            f"hp/mp={derived.combatant.max_hp}/{derived.max_mp} "
            f"atk={derived.combatant.physical_attack}/{derived.combatant.magical_attack} "
            f"def={derived.combatant.physical_defense}/{derived.combatant.magical_resistance} "
            f"acc={derived.combatant.physical_accuracy}/{derived.combatant.magical_accuracy} "
            f"eva/crit/spd={derived.combatant.evasion}/{derived.combatant.critical}/{derived.combatant.speed} "
            f"weightedKillThroughput={weighted_throughput[hero_class]:.2f}"
        )
    print(
        "weightedKillThroughput="
        + ",".join(f"{hero_class}:{weighted_throughput[hero_class]:.2f}" for hero_class in CLASSES)
    )
    print(f"role_contract_failures={len(failures)}")
    return failures


def run_rank_diagnostics(seeds: int) -> None:
    print("\n[new-class ELITE/BOSS diagnostic at C100; not final skill-integrated gate]")
    print("class rank winMin/winMax roundsMax hpLossMax")
    for hero_class in NEW_CLASSES:
        source = source_for_display(hero_class, 100, "BASE")
        for monster_rank in ("ELITE", "BOSS"):
            values = [
                measure_cell(source, profile, monster_rank, seeds, 7_000_000)
                for profile in v13.PROFILES
            ]
            print(
                f"{hero_class:7} {monster_rank:6} "
                f"{pct_ratio(min(value.win_rate for value in values))}/"
                f"{pct_ratio(max(value.win_rate for value in values))} "
                f"{max(value.median_rounds for value in values):.0f} "
                f"{max(value.mean_hp_loss for value in values):.1f}%"
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=500)
    parser.add_argument(
        "--section",
        choices=("all", "normal", "edge", "drift", "low", "roles", "ranks"),
        default="all",
    )
    parser.add_argument(
        "--pd",
        action="store_true",
        help="Use the documented minimum seed count for each PD gate section",
    )
    args = parser.parse_args()
    if not 100 <= args.seeds <= 10_000:
        raise SystemExit("--seeds must be in 100..10000")

    section_seeds = {
        "normal": max(args.seeds, 1_000) if args.pd else args.seeds,
        "edge": max(args.seeds, 10_000) if args.pd else args.seeds,
        "drift": max(args.seeds, 5_000) if args.pd else args.seeds,
        "low": max(args.seeds, 2_000) if args.pd else args.seeds,
        "roles": max(args.seeds, 5_000) if args.pd else args.seeds,
        "ranks": max(args.seeds, 1_000) if args.pd else args.seeds,
    }
    rank_diagnostic_seeds = max(100, section_seeds["ranks"] // 2)
    print(
        f"AlarmQuest six-class base combat v1.5 review; "
        f"profile={'PD' if args.pd else 'EXPLORE'} requested_seeds={args.seeds} "
        f"effective_seeds=normal:{section_seeds['normal']},"
        f"edge:{section_seeds['edge']},drift:{section_seeds['drift']},"
        f"low:{section_seeds['low']},roles:{section_seeds['roles']},"
        f"ranks:{rank_diagnostic_seeds}"
    )
    run_invariants()
    failures = []
    if args.section in ("all", "normal"):
        failures.extend(run_normal_matrix(section_seeds["normal"]))
    if args.section in ("all", "edge"):
        failures.extend(run_low_edge_probes(section_seeds["edge"]))
    if args.section in ("all", "drift"):
        failures.extend(run_long_drift(section_seeds["drift"]))
    if args.section in ("all", "low"):
        failures.extend(run_low_stat_long(section_seeds["low"]))
    if args.section in ("all", "roles"):
        failures.extend(run_role_diagnostics(section_seeds["roles"]))
    if args.section in ("all", "ranks"):
        run_rank_diagnostics(rank_diagnostic_seeds)

    if failures:
        for failure in failures[:30]:
            print(f"FAIL {failure}")
        raise SystemExit(f"PD_GATE=FAIL failures={len(failures)}")
    print("\nPD_GATE=PASS six_class_base=CONDITIONAL_GO species=OFF skills_items=OFF")


if __name__ == "__main__":
    main()
