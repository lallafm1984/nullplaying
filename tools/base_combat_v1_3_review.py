#!/usr/bin/env python3
"""Reproducible planning audit for the bounded-stat AlarmQuest v1.3 proposal.

The live resolver, saves, and content are never mutated.  This script reuses the
deterministic round/battle harness from base_combat_pd_review.py, but replaces
hero source normalization and monster generation with the v1.3 candidates.
"""

from __future__ import annotations

import argparse
import math
import statistics
from dataclasses import dataclass, replace

import base_combat_pd_review as core


@dataclass(frozen=True)
class V13Source:
    label: str
    hero_class: str
    level: int
    world_tier: int
    strength: float
    dexterity: float
    constitution: float
    intelligence: float
    wisdom: float
    health_growth: float
    mana_growth: float
    weapon_power: float
    armor_power: float
    ability_bonuses: tuple[float, float, float, float, float] = (0, 0, 0, 0, 0)
    set_scores: tuple[float, float, float, float, float] = (0, 0, 0, 0, 0)
    artifact_cap: bool = False


CLASS_MULTIPLIERS = {
    "WARRIOR": dict(hp=1.15, mp=0.65, patk=1.05, matk=0.80, pdef=1.10, mres=0.95),
    "ROGUE": dict(hp=1.10, mp=0.85, patk=1.02, matk=0.90, pdef=0.95, mres=1.00),
    "MAGE": dict(hp=1.15, mp=1.25, patk=0.80, matk=1.08, pdef=1.00, mres=1.10),
}

CLASS_BONUSES = {
    "WARRIOR": dict(speed=0, evasion=0, pacc=0, macc=0, critical=0),
    "ROGUE": dict(speed=12, evasion=8, pacc=4, macc=0, critical=6),
    "MAGE": dict(speed=4, evasion=2, pacc=0, macc=2, critical=0),
}

PRIMARY_INDEX = {"WARRIOR": 0, "ROGUE": 1, "MAGE": 3}
SECONDARY_INDEX = {"WARRIOR": 2, "ROGUE": 4, "MAGE": 4}

PROFILES = {
    "STANDARD": dict(
        offsets=(1, 0, 1, -2, 0), signature=(0, 2), attack="PHYSICAL",
        hp=1.00, armor=1.00, ward=1.00, coeff=3_500,
    ),
    "SWIFT": dict(
        offsets=(-1, 4, -2, -2, 1), signature=(1, 4), attack="PHYSICAL",
        hp=0.85, armor=0.75, ward=0.90, coeff=3_400,
    ),
    "ARMORED": dict(
        offsets=(1, -3, 5, -3, 0), signature=(0, 2), attack="PHYSICAL",
        hp=1.25, armor=1.35, ward=0.90, coeff=3_800,
    ),
    "SPELLCASTER": dict(
        offsets=(-3, 0, -1, 3, 1), signature=(3, 4), attack="MAGIC",
        hp=0.90, armor=0.80, ward=1.25, coeff=3_500,
    ),
}

RANKS = {
    "NORMAL": dict(hp=1.00, coeff=1.00, defense=1.00, stat_bonus=0, stat_cap=20),
    "ELITE": dict(hp=1.50, coeff=1.20, defense=1.05, stat_bonus=1, stat_cap=22),
    "BOSS": dict(hp=2.20, coeff=1.35, defense=1.10, stat_bonus=2, stat_cap=24),
}

BASE_ARRAYS = {
    "WARRIOR": (16, 10, 15, 8, 11),
    "ROGUE": (10, 16, 12, 9, 13),
    "MAGE": (7, 10, 9, 16, 14),
}

START_GROWTH = {"WARRIOR": (6, 5), "ROGUE": (6, 5), "MAGE": (5, 6)}
LEVEL_GROWTH = {"WARRIOR": (4, 1), "ROGUE": (3, 2), "MAGE": (2, 4)}

# Level-100 equipment is an explicit planning anchor, not measured live data.
ANCHORS = (
    (1, 1, 2, 0.22),
    (10, 1, 13, 13),
    (28, 2, 31, 31),
    (58, 3, 61, 61),
    (100, 5, 105, 105),
)


def round_positive(value: float) -> int:
    return math.floor(value + 0.5)


def clamp_float(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def training_floors(hero_class: str, world_tier: int) -> tuple[int, int, int]:
    tier_offset = max(0, world_tier - 1)
    general = min(12, 8 + tier_offset)
    primary = min(20, 12 + 2 * tier_offset)
    secondary = min(18, 10 + 2 * tier_offset)
    return general, primary, secondary


def combat_attributes(source: V13Source) -> tuple[float, float, float, float, float]:
    natural = [
        clamp_float(value, 3, 20)
        for value in (
            source.strength,
            source.dexterity,
            source.constitution,
            source.intelligence,
            source.wisdom,
        )
    ]
    general, primary_floor, secondary_floor = training_floors(source.hero_class, source.world_tier)
    trained = [max(value, general) for value in natural]
    trained[PRIMARY_INDEX[source.hero_class]] = max(
        trained[PRIMARY_INDEX[source.hero_class]], primary_floor
    )
    trained[SECONDARY_INDEX[source.hero_class]] = max(
        trained[SECONDARY_INDEX[source.hero_class]], secondary_floor
    )

    # Ordinary equipped ability bonuses share a +2 stacking cap.  Curses may
    # reduce a score, while a set-to effect supplies only a floor, never a sum.
    item_cap = 24 if source.artifact_cap else 22
    result = []
    for trained_value, raw_bonus, set_score in zip(
        trained, source.ability_bonuses, source.set_scores
    ):
        bonus = clamp_float(raw_bonus, -4, 2)
        result.append(clamp_float(max(trained_value + bonus, set_score), 3, item_cap))
    return tuple(result)


def hero_combatant(source: V13Source, _revision: str = "v1.3") -> core.Combatant:
    level = max(1, source.level)
    strength, dexterity, constitution, intelligence, wisdom = combat_attributes(source)
    multipliers = CLASS_MULTIPLIERS[source.hero_class]
    bonuses = CLASS_BONUSES[source.hero_class]

    physical_weapon = source.weapon_power
    magical_weapon = source.weapon_power * {
        "WARRIOR": 0.25,
        "ROGUE": 0.30,
        "MAGE": 1.00,
    }[source.hero_class]
    if source.hero_class == "MAGE":
        physical_weapon = source.weapon_power * 0.50

    if source.hero_class == "ROGUE":
        physical_attribute_term = 1.00 * dexterity + 0.35 * strength
    else:
        physical_attribute_term = 1.15 * strength + 0.35 * dexterity

    physical_attack = round_positive(
        (10 + 1.4 * level + physical_attribute_term + 1.8 * physical_weapon)
        * multipliers["patk"]
    )
    magical_attack = round_positive(
        (10 + 1.4 * level + 1.15 * intelligence + 0.35 * wisdom + 1.8 * magical_weapon)
        * multipliers["matk"]
    )
    physical_defense = round_positive(
        (4 + 0.55 * constitution + 0.20 * strength + 2.4 * source.armor_power)
        * multipliers["pdef"]
    )
    equipment_ward = source.armor_power * 0.60
    magical_resistance = round_positive(
        (4 + 0.60 * wisdom + 0.20 * intelligence + 2.0 * equipment_ward)
        * multipliers["mres"]
    )
    physical_accuracy = round_positive(
        10 + 1.25 * dexterity + 0.35 * strength + 0.50 * level + bonuses["pacc"]
    )
    magical_accuracy = round_positive(
        10 + 1.00 * intelligence + 0.60 * wisdom + 0.50 * level + bonuses["macc"]
    )
    evasion = round_positive(
        8 + 1.35 * dexterity + 0.25 * wisdom + 0.45 * level + bonuses["evasion"]
    )
    primary = (strength, dexterity, intelligence)[
        {"WARRIOR": 0, "ROGUE": 1, "MAGE": 2}[source.hero_class]
    ]
    critical = round_positive(5 + 1.10 * dexterity + 0.20 * primary + bonuses["critical"])
    critical_resistance = round_positive(5 + 0.65 * constitution + 0.45 * wisdom)
    speed = round_positive(60 + 2.0 * dexterity + bonuses["speed"])
    max_hp = round_positive(
        (80 + 8 * level + 6 * constitution + 4 * source.health_growth) * multipliers["hp"]
    )

    return core.Combatant(
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
        attack_type="MAGIC" if source.hero_class == "MAGE" else "PHYSICAL",
        action_coefficient_bps=10_000,
    )


def monster_combatant(
    level: int,
    profile_name: str,
    rank_name: str,
    _revision: str = "v1.3",
) -> core.Combatant:
    level = max(1, level)
    x = level - 1
    profile = PROFILES[profile_name]
    rank = RANKS[rank_name]
    unit = min(18.0, 10.0 + 0.125 * x)

    attributes = []
    for index, offset in enumerate(profile["offsets"]):
        rank_bonus = rank["stat_bonus"] if index in profile["signature"] else 0
        value = round_positive(unit + offset + rank_bonus)
        attributes.append(max(3, min(rank["stat_cap"], value)))
    strength, dexterity, constitution, intelligence, wisdom = attributes

    health_growth = round_positive(2.50 * x)
    weapon_power = 1 + round_positive(0.80 * x + 0.0020 * x * x)
    armor_power = weapon_power * profile["armor"]
    ward_power = (1 + round_positive(0.65 * x + 0.0015 * x * x)) * profile["ward"]

    physical_attack = round_positive(
        10 + 1.4 * level + 1.15 * strength + 0.35 * dexterity + 1.8 * weapon_power
    )
    magical_attack = round_positive(
        10 + 1.4 * level + 1.15 * intelligence + 0.35 * wisdom + 1.8 * weapon_power
    )
    physical_defense = round_positive(
        (4 + 0.55 * constitution + 0.20 * strength + 2.4 * armor_power) * rank["defense"]
    )
    magical_resistance = round_positive(
        (4 + 0.60 * wisdom + 0.20 * intelligence + 2.0 * ward_power) * rank["defense"]
    )
    physical_accuracy = round_positive(
        10 + 1.25 * dexterity + 0.35 * strength + 0.50 * level
    )
    magical_accuracy = round_positive(
        10 + 1.00 * intelligence + 0.60 * wisdom + 0.50 * level
    )
    evasion = round_positive(8 + 1.35 * dexterity + 0.25 * wisdom + 0.45 * level)
    attack_stat = strength if profile["attack"] == "PHYSICAL" else intelligence
    critical = round_positive(5 + 1.10 * dexterity + 0.20 * attack_stat)
    critical_resistance = round_positive(5 + 0.65 * constitution + 0.45 * wisdom)
    speed = round_positive(60 + 2.0 * dexterity)
    max_hp = round_positive(
        (80 + 8 * level + 6 * constitution + 4 * health_growth)
        * profile["hp"]
        * rank["hp"]
    )

    return core.Combatant(
        name=f"{profile_name}-{rank_name}-L{level}",
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
        attack_type=profile["attack"],
        action_coefficient_bps=round_positive(profile["coeff"] * rank["coeff"]),
    )


# The legacy audit module owns the deterministic battle and sampling harness.
core.PROFILES = PROFILES
core.RANKS = RANKS
core.hero_combatant = hero_combatant
core.monster_combatant = monster_combatant


def anchor_source(
    hero_class: str,
    level: int,
    world_tier: int,
    weapon_power: float,
    armor_power: float,
    band: str = "BASE",
) -> V13Source:
    if band == "LOW":
        natural = [3, 3, 3, 3, 3]
    elif band == "HIGH":
        natural = [18, 18, 18, 18, 18]
    else:
        natural = list(BASE_ARRAYS[hero_class])

    # One permanent class-primary point is granted at each world-tier promotion.
    primary_index = PRIMARY_INDEX[hero_class]
    natural[primary_index] = min(20, natural[primary_index] + max(0, world_tier - 1))
    start_hp, start_mp = START_GROWTH[hero_class]
    per_level_hp, per_level_mp = LEVEL_GROWTH[hero_class]
    return V13Source(
        label=f"{hero_class[0]}-{band}-L{level}",
        hero_class=hero_class,
        level=level,
        world_tier=world_tier,
        strength=natural[0],
        dexterity=natural[1],
        constitution=natural[2],
        intelligence=natural[3],
        wisdom=natural[4],
        health_growth=start_hp + per_level_hp * (level - 1),
        mana_growth=start_mp + per_level_mp * (level - 1),
        weapon_power=weapon_power,
        armor_power=armor_power,
    )


def pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def print_core_matrix(seeds: int) -> None:
    print("\n[v1.3 bounded-stat NORMAL core]")
    print("source profile win rounds/p90 hpLoss/p90 hit crit")
    round_targets = {
        "STANDARD": (4, 10),
        "SWIFT": (4, 8),
        "ARMORED": (7, 15),
        "SPELLCASTER": (4, 10),
    }
    hp_targets = {
        "WARRIOR": {
            "STANDARD": (12, 25), "SWIFT": (8, 22),
            "ARMORED": (20, 38), "SPELLCASTER": (12, 30),
        },
        "ROGUE": {
            "STANDARD": (14, 28), "SWIFT": (10, 24),
            "ARMORED": (22, 40), "SPELLCASTER": (15, 32),
        },
        "MAGE": {
            "STANDARD": (18, 33), "SWIFT": (14, 30),
            "ARMORED": (24, 42), "SPELLCASTER": (17, 35),
        },
    }
    failures = []
    for level, tier, weapon, armor in ANCHORS:
        for hero_class in CLASS_MULTIPLIERS:
            source = anchor_source(hero_class, level, tier, weapon, armor)
            for profile in PROFILES:
                metrics = core.measure_cell(source, profile, "NORMAL", "v1.3", seeds, seed_offset=10_000)
                round_min, round_max = round_targets[profile]
                hp_min, hp_max = hp_targets[hero_class][profile]
                hit_min, hit_max = (0.82, 0.96) if profile == "SWIFT" else (0.875, 0.995)
                crit_min, crit_max = (0.06, 0.15) if hero_class == "ROGUE" else (0.018, 0.12)
                passed = (
                    0.95 <= metrics.win_rate <= 1.0
                    and round_min <= metrics.median_rounds <= round_max
                    and hp_min <= metrics.median_hp_loss <= hp_max
                    and hit_min <= metrics.hero_hit_rate <= hit_max
                    and crit_min <= metrics.hero_crit_rate_on_hit <= crit_max
                    and metrics.retreat_rate < 0.001
                )
                if not passed and level > 1:
                    failures.append((source.label, profile, metrics))
                print(
                    f"{source.label:12} {profile:11} {pct(metrics.win_rate):>7} "
                    f"{metrics.median_rounds:4.1f}/{metrics.p90_rounds:2} "
                    f"{metrics.median_hp_loss:5.1f}/{metrics.p90_hp_loss:5.1f}% "
                    f"{pct(metrics.hero_hit_rate):>6} {pct(metrics.hero_crit_rate_on_hit):>6}"
                )
    print(f"non_starter_normal_gate_failures={len(failures)}/48")
    for label, profile, metrics in failures:
        print(
            f"FAIL {label}/{profile}: win={pct(metrics.win_rate)} "
            f"rounds={metrics.median_rounds} hpLoss={metrics.median_hp_loss:.1f}% "
            f"hit={pct(metrics.hero_hit_rate)} crit={pct(metrics.hero_crit_rate_on_hit)}"
        )


def print_catchup_matrix(seeds: int) -> None:
    print("\n[v1.3 catch-up stress: all-3 vs representative vs all-18]")
    print("level class band primary combatStats hp win rounds hpLoss/p90 hit expedition(p10/med/p90)")
    for level, tier, weapon, armor in (ANCHORS[0], ANCHORS[3], ANCHORS[4]):
        for hero_class in CLASS_MULTIPLIERS:
            for band in ("LOW", "BASE", "HIGH"):
                source = anchor_source(hero_class, level, tier, weapon, armor, band)
                stats = combat_attributes(source)
                hero = hero_combatant(source)
                metrics = core.measure_cell(
                    source, "STANDARD", "NORMAL", "v1.3", seeds, seed_offset=90_000
                )
                expedition_median, expedition_p10, expedition_p90 = core.measure_expedition(
                    source, "v1.3", max(500, seeds // 2)
                )
                primary = stats[PRIMARY_INDEX[hero_class]]
                print(
                    f"{level:5} {hero_class:7} {band:4} {primary:7.1f} "
                    f"{'/'.join(str(round_positive(value)) for value in stats):>14} "
                    f"{hero.max_hp:4} {pct(metrics.win_rate):>7} {metrics.median_rounds:6.1f} "
                    f"{metrics.median_hp_loss:5.1f}/{metrics.p90_hp_loss:5.1f}% "
                    f"{pct(metrics.hero_hit_rate):>6} "
                    f"{expedition_p10:2}/{expedition_median:4.1f}/{expedition_p90:2}"
                )


def onboarding_source(hero_class: str, seed: int) -> V13Source:
    random = core.DeterministicRandom.for_event(seed, 0, "ONBOARDING_ROLL_V13", hero_class)
    rolled = [3 + sum(random.next_int(0, 6) for _ in range(3)) for _ in range(6)]
    strength, constitution, dexterity, intelligence, wisdom, _charisma = rolled
    return V13Source(
        label=f"{hero_class}-ROLL-{seed}",
        hero_class=hero_class,
        level=1,
        world_tier=1,
        strength=strength,
        dexterity=dexterity,
        constitution=constitution,
        intelligence=intelligence,
        wisdom=wisdom,
        health_growth=random.next_int(0, 8) + constitution // 6,
        mana_growth=random.next_int(0, 8) + intelligence // 6,
        weapon_power=2,
        armor_power=0.22,
    )


def print_onboarding_audit(seeds: int) -> None:
    print("\n[v1.3 onboarding: actual 3d6 and green_hills profile mix]")
    print("class win/loss/retreat rounds(p50/p90) hpLoss(p50/p90/p99) expedition(p10/p50/p90)")
    profile_names = tuple(PROFILES)
    starter_coefficients = {"STANDARD": 3_200, "SWIFT": 3_400, "ARMORED": 3_000}
    count = max(2_000, seeds)
    for hero_class in CLASS_MULTIPLIERS:
        results = []
        hp_losses = []
        expedition_wins = []
        for seed in range(count):
            source = onboarding_source(hero_class, seed)
            hero = hero_combatant(source)
            selector = core.DeterministicRandom.for_event(seed, 1, "STARTER_PROFILE_V13", hero_class)
            roll = selector.next_double()
            profile = profile_names[0] if roll < 0.25 else profile_names[1] if roll < 0.75 else profile_names[2]
            monster = replace(
                monster_combatant(1, profile, "NORMAL"),
                action_coefficient_bps=starter_coefficients[profile],
            )
            result = core.battle(hero, monster, seed=70_000 + seed)
            results.append(result)
            hp_losses.append((hero.max_hp - result.hero_hp) * 100.0 / hero.max_hp)

            hp = hero.max_hp
            wins = 0
            for encounter in range(30):
                pick = core.DeterministicRandom.for_event(
                    seed, 100 + encounter, "STARTER_EXPEDITION_V13", hero_class
                ).next_double()
                expedition_profile = (
                    profile_names[0] if pick < 0.25 else profile_names[1] if pick < 0.75 else profile_names[2]
                )
                expedition_monster = replace(
                    monster_combatant(1, expedition_profile, "NORMAL"),
                    action_coefficient_bps=starter_coefficients[expedition_profile],
                )
                expedition_result = core.battle(
                    hero,
                    expedition_monster,
                    seed=80_000 + seed * 31 + encounter,
                    initial_hero_hp=hp,
                    sequence=191 + encounter,
                )
                hp = expedition_result.hero_hp
                if expedition_result.outcome != "WIN":
                    break
                wins += 1
            expedition_wins.append(wins)

        print(
            f"{hero_class:7} "
            f"{pct(sum(result.outcome == 'WIN' for result in results) / count):>7}/"
            f"{pct(sum(result.outcome == 'LOSS' for result in results) / count):>6}/"
            f"{pct(sum(result.outcome == 'RETREAT' for result in results) / count):>6} "
            f"{statistics.median(result.rounds for result in results):4.1f}/"
            f"{int(core.percentile([result.rounds for result in results], 0.90)):2} "
            f"{statistics.median(hp_losses):5.1f}/"
            f"{core.percentile(hp_losses, 0.90):5.1f}/"
            f"{core.percentile(hp_losses, 0.99):5.1f}% "
            f"{int(core.percentile(expedition_wins, 0.10)):2}/"
            f"{statistics.median(expedition_wins):4.1f}/"
            f"{int(core.percentile(expedition_wins, 0.90)):2}"
        )


def print_rank_matrix(seeds: int) -> None:
    print("\n[v1.3 rank skeleton: level 58 STANDARD]")
    print("class rank win/loss/retreat rounds/p90 hpLoss/p90")
    for hero_class in CLASS_MULTIPLIERS:
        source = anchor_source(hero_class, 58, 3, 61, 61)
        for rank in RANKS:
            metrics = core.measure_cell(
                source, "STANDARD", rank, "v1.3", seeds, seed_offset=150_000
            )
            print(
                f"{hero_class:7} {rank:6} "
                f"{pct(metrics.win_rate):>7}/{pct(metrics.loss_rate):>6}/{pct(metrics.retreat_rate):>6} "
                f"{metrics.median_rounds:4.1f}/{metrics.p90_rounds:2} "
                f"{metrics.median_hp_loss:5.1f}/{metrics.p90_hp_loss:5.1f}%"
            )


def print_item_rescue(seeds: int) -> None:
    print("\n[v1.3 low-primary equipment rescue: level 1 STANDARD]")
    print("class setup combatPrimary win rounds hpLoss/p90")
    for hero_class in CLASS_MULTIPLIERS:
        source = anchor_source(hero_class, 1, 1, 2, 0.22, "LOW")
        primary = PRIMARY_INDEX[hero_class]
        setups = [("NONE", source)]
        plus_two = list(source.ability_bonuses)
        plus_two[primary] = 2
        setups.append(("PLUS2", replace(source, ability_bonuses=tuple(plus_two))))
        set_sixteen = list(source.set_scores)
        set_sixteen[primary] = 16
        setups.append(("SET16", replace(source, set_scores=tuple(set_sixteen))))
        for label, candidate in setups:
            metrics = core.measure_cell(
                candidate, "STANDARD", "NORMAL", "v1.3", seeds, seed_offset=180_000
            )
            combat_primary = combat_attributes(candidate)[primary]
            print(
                f"{hero_class:7} {label:6} {combat_primary:13.1f} "
                f"{pct(metrics.win_rate):>7} {metrics.median_rounds:6.1f} "
                f"{metrics.median_hp_loss:5.1f}/{metrics.p90_hp_loss:5.1f}%"
            )


def run_invariants() -> None:
    source = anchor_source("MAGE", 58, 3, 61, 61)
    hero = hero_combatant(source)
    monster = monster_combatant(58, "STANDARD", "NORMAL")
    assert core.battle(hero, monster, 777) == core.battle(hero, monster, 777)
    assert 6_500 <= core.hit_bps(hero, monster) <= 9_900
    assert 200 <= core.critical_bps(hero, monster) <= 2_000
    assert 0 <= core.mitigation_bps(hero, monster) <= 7_000

    for hero_class in CLASS_MULTIPLIERS:
        previous = None
        for score in range(3, 21):
            values = list(BASE_ARRAYS[hero_class])
            values[PRIMARY_INDEX[hero_class]] = score
            candidate = replace(
                source,
                hero_class=hero_class,
                strength=values[0],
                dexterity=values[1],
                constitution=values[2],
                intelligence=values[3],
                wisdom=values[4],
            )
            current = combat_attributes(candidate)[PRIMARY_INDEX[hero_class]]
            assert previous is None or current >= previous
            previous = current

        low = anchor_source(hero_class, 100, 5, 105, 105, "LOW")
        stats = combat_attributes(low)
        assert stats[PRIMARY_INDEX[hero_class]] == 20
        assert stats[SECONDARY_INDEX[hero_class]] >= 18
        assert min(stats) >= 12

    capped = replace(
        source,
        intelligence=20,
        ability_bonuses=(0, 0, 0, 99, 0),
    )
    assert combat_attributes(capped)[3] == 22
    artifact_scores = list(capped.set_scores)
    artifact_scores[3] = 24
    artifact = replace(capped, artifact_cap=True, set_scores=tuple(artifact_scores))
    assert combat_attributes(artifact)[3] == 24
    print(
        "\n[invariants] deterministic=PASS bounds=PASS monotonic_stats=PASS "
        "tier5_catchup=PASS item_caps=PASS"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=2_000)
    parser.add_argument(
        "--section",
        choices=("all", "core", "catchup", "onboarding", "rank", "items"),
        default="all",
    )
    args = parser.parse_args()
    if not 100 <= args.seeds <= 20_000:
        raise SystemExit("--seeds must be in 100..20000")
    print(f"AlarmQuest bounded-stat combat v1.3 review; seeds_per_cell={args.seeds}")
    run_invariants()
    if args.section in ("all", "core"):
        print_core_matrix(args.seeds)
    if args.section in ("all", "catchup"):
        print_catchup_matrix(args.seeds)
    if args.section in ("all", "onboarding"):
        print_onboarding_audit(args.seeds)
    if args.section in ("all", "rank"):
        print_rank_matrix(args.seeds)
    if args.section in ("all", "items"):
        print_item_rescue(args.seeds)


if __name__ == "__main__":
    main()
