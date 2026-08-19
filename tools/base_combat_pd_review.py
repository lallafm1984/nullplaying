#!/usr/bin/env python3
"""Reproducible planning audit for AlarmQuest basic-combat candidates.

This is intentionally independent from the live resolver. It mirrors the proposed formulas,
uses AlarmQuest's SplitMix64 event generator, and compares v1.1 against the PD-review revision.
It never mutates saves or production content.
"""

from __future__ import annotations

import argparse
import math
import statistics
from dataclasses import dataclass, replace
from typing import Iterable


MASK_64 = (1 << 64) - 1
GOLDEN_GAMMA = (-7046029254386353131) & MASK_64
MIX_1 = (-4658895280553007687) & MASK_64
MIX_2 = (-7723592293110705685) & MASK_64
ARMOR_WEIGHTS = (0.18, 0.10, 0.22, 0.08, 0.08, 0.06, 0.06, 0.10, 0.07, 0.05)


def round_positive(value: float) -> int:
    return math.floor(value + 0.5)


def clamp(value: int, minimum: int, maximum: int) -> int:
    return max(minimum, min(maximum, value))


def rotate_left(value: int, bits: int) -> int:
    value &= MASK_64
    return ((value << bits) | (value >> (64 - bits))) & MASK_64


def stable_hash(value: str) -> int:
    result = (-3750763034362895579) & MASK_64
    for char in value:
        result ^= ord(char)
        result = (result * 1099511628211) & MASK_64
    return result


class DeterministicRandom:
    def __init__(self, state: int):
        self.state = state & MASK_64

    @classmethod
    def for_event(cls, seed: int, event_index: int, event_type: str, region_id: str):
        mixed = (seed & MASK_64) ^ rotate_left(event_index, 21)
        mixed ^= rotate_left(stable_hash(event_type), 11)
        mixed ^= rotate_left(stable_hash(region_id), 37)
        return cls(mixed)

    def next_long(self) -> int:
        self.state = (self.state + GOLDEN_GAMMA) & MASK_64
        value = self.state
        value = ((value ^ (value >> 30)) * MIX_1) & MASK_64
        value = ((value ^ (value >> 27)) * MIX_2) & MASK_64
        return (value ^ (value >> 31)) & MASK_64

    def next_double(self) -> float:
        return (self.next_long() >> 11) / 9_007_199_254_740_992.0

    def next_int(self, start: int, stop: int) -> int:
        if start >= stop:
            raise ValueError("start must be less than stop")
        return start + min(int(self.next_double() * (stop - start)), stop - start - 1)

    def chance(self, probability: float) -> bool:
        return self.next_double() < max(0.0, min(1.0, probability))


@dataclass(frozen=True)
class SourceStats:
    label: str
    hero_class: str
    level: int
    strength: float
    dexterity: float
    constitution: float
    intelligence: float
    wisdom: float
    health_growth: float
    mana_growth: float
    weapon_power: float
    armor_power: float


@dataclass(frozen=True)
class Combatant:
    name: str
    level: int
    max_hp: int
    physical_attack: int
    magical_attack: int
    physical_defense: int
    magical_resistance: int
    physical_accuracy: int
    magical_accuracy: int
    evasion: int
    critical: int
    critical_resistance: int
    speed: int
    attack_type: str
    action_coefficient_bps: int


@dataclass(frozen=True)
class BattleResult:
    outcome: str
    rounds: int
    hero_hp: int
    hero_attempts: int
    hero_hits: int
    hero_crits: int
    monster_attempts: int
    monster_hits: int
    monster_crits: int


@dataclass(frozen=True)
class CellMetrics:
    win_rate: float
    loss_rate: float
    retreat_rate: float
    median_rounds: float
    p90_rounds: int
    median_hp_loss: float
    p90_hp_loss: float
    hero_hit_rate: float
    hero_crit_rate_on_hit: float


CLASS_MULTIPLIERS = {
    "WARRIOR": dict(hp=1.15, mp=0.65, patk=1.05, matk=0.80, pdef=1.10, mres=0.95),
    "ROGUE": dict(hp=1.05, mp=0.85, patk=1.02, matk=0.90, pdef=0.95, mres=1.00),
    "MAGE": dict(hp=1.05, mp=1.25, patk=0.80, matk=1.08, pdef=1.00, mres=1.10),
}

CLASS_BONUSES = {
    "WARRIOR": dict(speed=0, evasion=0, pacc=0, macc=0, critical=0),
    "ROGUE": dict(speed=12, evasion=8, pacc=4, macc=0, critical=6),
    "MAGE": dict(speed=4, evasion=2, pacc=0, macc=2, critical=0),
}

PROFILES = {
    "STANDARD": dict(weights=(1.10, 1.00, 1.10, 0.80, 1.00), attack="PHYSICAL", hp=1.00, armor=1.00, ward=1.00, coeff=5000),
    "SWIFT": dict(weights=(0.90, 1.40, 0.80, 0.80, 1.10), attack="PHYSICAL", hp=0.85, armor=0.75, ward=0.90, coeff=4800),
    "ARMORED": dict(weights=(1.05, 0.70, 1.50, 0.70, 1.05), attack="PHYSICAL", hp=1.25, armor=1.35, ward=0.90, coeff=5500),
    "SPELLCASTER": dict(weights=(0.65, 0.95, 0.85, 1.45, 1.10), attack="MAGIC", hp=0.90, armor=0.80, ward=1.25, coeff=5500),
}

RANKS = {
    "NORMAL": dict(hp=1.00, coeff=1.00, defense=1.00),
    "ELITE": dict(hp=1.50, coeff=1.20, defense=1.05),
    "BOSS": dict(hp=2.20, coeff=1.35, defense=1.10),
}


def weighted_armor(values: Iterable[int]) -> float:
    return sum(value * weight for value, weight in zip(values, ARMOR_WEIGHTS))


# These are read-only source snapshots measured through SettlementEngine on seed 4281.
# Level 1 entries use the same representative starting arrays as CombatProgressionAuditCli.
SOURCES = (
    SourceStats("W-L1", "WARRIOR", 1, 16, 10, 15, 8, 11, 6, 5, 2, 0.22),
    SourceStats("W-L10", "WARRIOR", 10, 21, 14, 23, 12, 14, 90, 52, 13, weighted_armor((13, 12, 13, 13, 12, 13, 12, 13, 13, 13))),
    SourceStats("W-L28", "WARRIOR", 28, 32, 16, 37, 15, 19, 305, 176, 31, weighted_armor((31, 31, 31, 31, 30, 31, 31, 30, 31, 31))),
    SourceStats("W-L58", "WARRIOR", 58, 193, 99, 211, 111, 134, 1194, 669, 61, 61),
    SourceStats("R-L1", "ROGUE", 1, 10, 16, 12, 9, 13, 6, 5, 2, 0.22),
    SourceStats("R-L10", "ROGUE", 10, 12, 27, 15, 14, 15, 76, 55, 12, weighted_armor((13, 12, 12, 12, 12, 12, 12, 12, 12, 13))),
    SourceStats("R-L27", "ROGUE", 27, 16, 44, 22, 20, 22, 224, 189, 30, 30),
    SourceStats("R-L58", "ROGUE", 58, 90, 234, 115, 106, 134, 824, 744, 61, 61),
    SourceStats("M-L1", "MAGE", 1, 7, 10, 9, 16, 14, 5, 6, 2, 0.22),
    SourceStats("M-L11", "MAGE", 11, 9, 15, 10, 23, 22, 61, 88, 14, weighted_armor((13, 14, 14, 13, 14, 14, 14, 13, 13, 14))),
    SourceStats("M-L26", "MAGE", 26, 11, 22, 13, 33, 31, 159, 258, 29, weighted_armor((29, 28, 29, 29, 28, 29, 28, 29, 29, 29))),
    SourceStats("M-L58", "MAGE", 58, 62, 99, 72, 338, 193, 562, 1501, 61, 61),
)


def saturation(raw: float, knee: float) -> float:
    """Continuous, monotonic soft cap; approaches 2*knee without a hard cutoff."""
    if raw <= knee:
        return raw
    return knee + knee * (raw - knee) / raw


def effective_attribute(raw: float, level: int, revision: str) -> float:
    if revision == "v1.1":
        return raw
    return saturation(raw, 18.0 + 0.8 * (level - 1))


def effective_growth(raw: float, level: int, revision: str) -> float:
    if revision == "v1.1":
        return raw
    return saturation(raw, 8.0 + 5.0 * (level - 1))


def hero_combatant(source: SourceStats, revision: str) -> Combatant:
    level = source.level
    strength = effective_attribute(source.strength, level, revision)
    dexterity = effective_attribute(source.dexterity, level, revision)
    constitution = effective_attribute(source.constitution, level, revision)
    intelligence = effective_attribute(source.intelligence, level, revision)
    wisdom = effective_attribute(source.wisdom, level, revision)
    health_growth = effective_growth(source.health_growth, level, revision)
    multipliers = CLASS_MULTIPLIERS[source.hero_class]
    bonuses = CLASS_BONUSES[source.hero_class]
    physical_weapon = source.weapon_power
    magical_weapon = source.weapon_power * {"WARRIOR": 0.25, "ROGUE": 0.30, "MAGE": 1.00}[source.hero_class]
    if source.hero_class == "MAGE":
        physical_weapon = source.weapon_power * 0.50

    if revision == "v1.2" and source.hero_class == "ROGUE":
        physical_attribute_term = 1.00 * dexterity + 0.35 * strength
    else:
        physical_attribute_term = 1.15 * strength + 0.35 * dexterity

    physical_attack = round_positive(
        (10 + 1.4 * level + physical_attribute_term + 1.8 * physical_weapon) * multipliers["patk"]
    )
    magical_attack = round_positive(
        (10 + 1.4 * level + 1.15 * intelligence + 0.35 * wisdom + 1.8 * magical_weapon)
        * multipliers["matk"]
    )
    physical_defense = round_positive(
        (4 + 0.55 * constitution + 0.20 * strength + 2.4 * source.armor_power) * multipliers["pdef"]
    )
    equipment_ward = source.armor_power * 0.60
    magical_resistance = round_positive(
        (4 + 0.60 * wisdom + 0.20 * intelligence + 2.0 * equipment_ward) * multipliers["mres"]
    )
    physical_accuracy = round_positive(
        10 + 1.25 * dexterity + 0.35 * strength + 0.50 * level + bonuses["pacc"]
    )
    magical_accuracy = round_positive(
        10 + 1.00 * intelligence + 0.60 * wisdom + 0.50 * level + bonuses["macc"]
    )
    evasion = round_positive(8 + 1.35 * dexterity + 0.25 * wisdom + 0.45 * level + bonuses["evasion"])
    if revision == "v1.2":
        primary_attack_stat = {"WARRIOR": strength, "ROGUE": dexterity, "MAGE": intelligence}[source.hero_class]
    else:
        primary_attack_stat = strength if source.hero_class != "MAGE" else intelligence
    critical = round_positive(5 + 1.10 * dexterity + 0.20 * primary_attack_stat + bonuses["critical"])
    critical_resistance = round_positive(5 + 0.65 * constitution + 0.45 * wisdom)
    speed = round_positive(60 + 2.0 * dexterity + bonuses["speed"])
    max_hp = round_positive(
        (80 + 8 * level + 6 * constitution + 4 * health_growth) * multipliers["hp"]
    )
    return Combatant(
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


def monster_combatant(level: int, profile_name: str, rank_name: str, revision: str) -> Combatant:
    level = max(1, level)
    profile = PROFILES[profile_name]
    rank = RANKS[rank_name]
    if revision == "v1.1":
        attribute_unit = 9 + round_positive(0.55 * (level - 1))
        health_growth = 2 * (level - 1)
        weapon_power = 1 + round_positive(0.65 * (level - 1))
        armor_power = 1 + round_positive(0.55 * (level - 1))
        ward_power = 1 + round_positive(0.45 * (level - 1))
    else:
        level_offset = level - 1
        # The measured hero curve is gentle through the 20s, then accelerates because current
        # stat rewards reinforce already-high values. Quadratic source curves follow that shape
        # without making early monsters as strong as long-term ones.
        attribute_unit = 10 + round_positive(0.35 * level_offset + 0.0105 * level_offset * level_offset)
        health_growth = round_positive(2.0 * level_offset + 0.055 * level_offset * level_offset)
        weapon_power = 1 + round_positive(0.55 * level_offset + 0.008 * level_offset * level_offset)
        armor_power = 1 + round_positive(0.55 * level_offset + 0.008 * level_offset * level_offset)
        ward_power = 1 + round_positive(0.45 * level_offset + 0.005 * level_offset * level_offset)

    raw_attributes = [max(1, round_positive(attribute_unit * weight)) for weight in profile["weights"]]
    strength, dexterity, constitution, intelligence, wisdom = [
        effective_attribute(value, level, revision) for value in raw_attributes
    ]
    health_growth = effective_growth(health_growth, level, revision)
    armor_power *= profile["armor"]
    ward_power *= profile["ward"]
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
    physical_accuracy = round_positive(10 + 1.25 * dexterity + 0.35 * strength + 0.50 * level)
    magical_accuracy = round_positive(10 + 1.00 * intelligence + 0.60 * wisdom + 0.50 * level)
    evasion = round_positive(8 + 1.35 * dexterity + 0.25 * wisdom + 0.45 * level)
    critical = round_positive(5 + 1.10 * dexterity + 0.20 * (strength if profile["attack"] == "PHYSICAL" else intelligence))
    critical_resistance = round_positive(5 + 0.65 * constitution + 0.45 * wisdom)
    speed = round_positive(60 + 2.0 * dexterity)
    base_hp = 80 + 8 * level + 6 * constitution + 4 * health_growth
    max_hp = round_positive(base_hp * profile["hp"] * rank["hp"])
    return Combatant(
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


def hit_bps(attacker: Combatant, defender: Combatant) -> int:
    accuracy = attacker.physical_accuracy if attacker.attack_type == "PHYSICAL" else attacker.magical_accuracy
    delta = round_positive(3000 * (accuracy - defender.evasion) / max(1, accuracy + defender.evasion))
    return clamp(9000 + delta, 6500, 9900)


def critical_bps(attacker: Combatant, defender: Combatant) -> int:
    delta = round_positive(
        2000 * (attacker.critical - defender.critical_resistance)
        / max(1, attacker.critical + defender.critical_resistance)
    )
    return clamp(500 + delta, 200, 2000)


def mitigation_bps(attacker: Combatant, defender: Combatant) -> int:
    defense = defender.physical_defense if attacker.attack_type == "PHYSICAL" else defender.magical_resistance
    scale = 40 + 5 * attacker.level
    return min(7000, round_positive(10_000 * defense / (defense + scale)))


def multiply_bps(value: int, bps: int) -> int:
    return round_positive(value * bps / 10_000)


def attack(attacker: Combatant, defender: Combatant, random: DeterministicRandom) -> tuple[int, bool, bool]:
    hit_roll = random.next_int(0, 10_000)
    critical_roll = random.next_int(0, 10_000)
    variance_bps = random.next_int(9_500, 10_501)
    hit = hit_roll < hit_bps(attacker, defender)
    critical = critical_roll < critical_bps(attacker, defender)
    if not hit:
        return 0, False, False
    attack_power = attacker.physical_attack if attacker.attack_type == "PHYSICAL" else attacker.magical_attack
    damage = multiply_bps(attack_power, attacker.action_coefficient_bps)
    damage = multiply_bps(damage, 15_000 if critical else 10_000)
    damage = multiply_bps(damage, variance_bps)
    damage = max(1, multiply_bps(damage, 10_000 - mitigation_bps(attacker, defender)))
    return damage, True, critical


def battle(
    hero: Combatant,
    monster: Combatant,
    seed: int,
    initial_hero_hp: int | None = None,
    sequence: int = 91,
) -> BattleResult:
    random = DeterministicRandom.for_event(seed, sequence, "TURN_BATTLE_V5", monster.name)
    tie_hero = random.chance(0.5)
    if hero.speed > monster.speed:
        order = ("hero", "monster")
    elif hero.speed < monster.speed:
        order = ("monster", "hero")
    else:
        order = ("hero", "monster") if tie_hero else ("monster", "hero")
    hero_hp = hero.max_hp if initial_hero_hp is None else clamp(initial_hero_hp, 0, hero.max_hp)
    monster_hp = monster.max_hp
    hero_attempts = hero_hits = hero_crits = 0
    monster_attempts = monster_hits = monster_crits = 0
    for round_index in range(1, 31):
        for side in order:
            if hero_hp <= 0 or monster_hp <= 0:
                break
            if side == "hero":
                damage, hit, critical = attack(hero, monster, random)
                hero_attempts += 1
                hero_hits += int(hit)
                hero_crits += int(critical)
                monster_hp = max(0, monster_hp - damage)
            else:
                damage, hit, critical = attack(monster, hero, random)
                monster_attempts += 1
                monster_hits += int(hit)
                monster_crits += int(critical)
                hero_hp = max(0, hero_hp - damage)
        if monster_hp <= 0:
            outcome = "WIN"
            break
        if hero_hp <= 0:
            outcome = "LOSS"
            break
    else:
        round_index = 30
        outcome = "RETREAT"
    return BattleResult(
        outcome,
        round_index,
        hero_hp,
        hero_attempts,
        hero_hits,
        hero_crits,
        monster_attempts,
        monster_hits,
        monster_crits,
    )


def percentile(values: list[float], probability: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, math.ceil(probability * len(ordered)) - 1)
    return ordered[index]


def measure_cell(
    source: SourceStats,
    profile: str,
    rank: str,
    revision: str,
    seeds: int,
    enemy_level_offset: int = 0,
    start_hp_ratio: float = 1.0,
    seed_offset: int = 0,
) -> CellMetrics:
    hero = hero_combatant(source, revision)
    monster = monster_combatant(source.level + enemy_level_offset, profile, rank, revision)
    results = [
        battle(
            hero,
            monster,
            seed=seed_offset + seed,
            initial_hero_hp=round_positive(hero.max_hp * start_hp_ratio),
        )
        for seed in range(seeds)
    ]
    hp_losses = [(round_positive(hero.max_hp * start_hp_ratio) - result.hero_hp) * 100.0 / hero.max_hp for result in results]
    hero_attempts = sum(result.hero_attempts for result in results)
    hero_hits = sum(result.hero_hits for result in results)
    hero_crits = sum(result.hero_crits for result in results)
    return CellMetrics(
        win_rate=sum(result.outcome == "WIN" for result in results) / seeds,
        loss_rate=sum(result.outcome == "LOSS" for result in results) / seeds,
        retreat_rate=sum(result.outcome == "RETREAT" for result in results) / seeds,
        median_rounds=statistics.median(result.rounds for result in results),
        p90_rounds=int(percentile([result.rounds for result in results], 0.90)),
        median_hp_loss=statistics.median(hp_losses),
        p90_hp_loss=percentile(hp_losses, 0.90),
        hero_hit_rate=hero_hits / max(1, hero_attempts),
        hero_crit_rate_on_hit=hero_crits / max(1, hero_hits),
    )


def scaled_source(source: SourceStats, multiplier: float, label_suffix: str) -> SourceStats:
    return replace(
        source,
        label=f"{source.label}-{label_suffix}",
        strength=max(1, source.strength * multiplier),
        dexterity=max(1, source.dexterity * multiplier),
        constitution=max(1, source.constitution * multiplier),
        intelligence=max(1, source.intelligence * multiplier),
        wisdom=max(1, source.wisdom * multiplier),
        health_growth=max(0, source.health_growth * multiplier),
        mana_growth=max(0, source.mana_growth * multiplier),
        weapon_power=max(0, source.weapon_power * multiplier),
        armor_power=max(0, source.armor_power * multiplier),
    )


def measure_expedition(source: SourceStats, revision: str, seeds: int) -> tuple[float, int, int]:
    hero = hero_combatant(source, revision)
    wins: list[int] = []
    profile_names = tuple(PROFILES)
    for seed in range(seeds):
        hp = hero.max_hp
        won = 0
        expedition_random = DeterministicRandom.for_event(seed, 7_001, "BASE_COMBAT_EXPEDITION_V1", source.label)
        for encounter in range(100):
            roll = expedition_random.next_double()
            if roll < 0.40:
                profile = profile_names[0]
            elif roll < 0.65:
                profile = profile_names[1]
            elif roll < 0.85:
                profile = profile_names[2]
            else:
                profile = profile_names[3]
            monster = monster_combatant(source.level, profile, "NORMAL", revision)
            result = battle(hero, monster, seed=seed * 101 + encounter, initial_hero_hp=hp, sequence=91 + encounter)
            hp = result.hero_hp
            if result.outcome != "WIN":
                break
            won += 1
        wins.append(won)
    return statistics.median(wins), int(percentile(wins, 0.10)), int(percentile(wins, 0.90))


def pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def print_source_audit() -> None:
    print("\n[Source normalization: measured raw -> v1.2 effective primary / effective growth]")
    print("source rawPrimary effPrimary rawGrowth effGrowth maxHP PATK/MATK")
    for source in SOURCES:
        hero = hero_combatant(source, "v1.2")
        raw_primary = {"WARRIOR": source.strength, "ROGUE": source.dexterity, "MAGE": source.intelligence}[source.hero_class]
        effective_primary = effective_attribute(raw_primary, source.level, "v1.2")
        active_attack = hero.magical_attack if hero.attack_type == "MAGIC" else hero.physical_attack
        print(
            f"{source.label:6} {raw_primary:10.1f} {effective_primary:10.1f} "
            f"{source.health_growth:9.1f} {effective_growth(source.health_growth, source.level, 'v1.2'):9.1f} "
            f"{hero.max_hp:5} {active_attack:9}"
        )


def print_revision_comparison(seeds: int) -> None:
    print("\n[v1.1 vs v1.2: STANDARD NORMAL]")
    print("source revision win rounds hpLoss p90Loss hit crit expedition(p10/med/p90)")
    for source in SOURCES:
        for revision in ("v1.1", "v1.2"):
            metrics = measure_cell(source, "STANDARD", "NORMAL", revision, seeds)
            expedition_median, expedition_p10, expedition_p90 = measure_expedition(source, revision, max(500, seeds // 2))
            print(
                f"{source.label:6} {revision:8} {pct(metrics.win_rate):>7} {metrics.median_rounds:6.1f} "
                f"{metrics.median_hp_loss:6.1f}% {metrics.p90_hp_loss:7.1f}% "
                f"{pct(metrics.hero_hit_rate):>6} {pct(metrics.hero_crit_rate_on_hit):>6} "
                f"{expedition_p10:2}/{expedition_median:4.1f}/{expedition_p90:2}"
            )


def print_profile_matrix(seeds: int) -> None:
    print("\n[v1.2 NORMAL profile matrix]")
    print("source profile win rounds/p90 hpLoss/p90 hit crit")
    failures = []
    round_targets = {
        "STANDARD": (4, 10),
        "SWIFT": (4, 8),
        "ARMORED": (7, 15),
        "SPELLCASTER": (4, 10),
    }
    hp_targets = {
        "WARRIOR": {
            "STANDARD": (12, 25), "SWIFT": (8, 22), "ARMORED": (20, 38), "SPELLCASTER": (12, 30),
        },
        "ROGUE": {
            "STANDARD": (14, 28), "SWIFT": (10, 24), "ARMORED": (22, 40), "SPELLCASTER": (15, 32),
        },
        "MAGE": {
            "STANDARD": (18, 32), "SWIFT": (14, 30), "ARMORED": (24, 40), "SPELLCASTER": (18, 34),
        },
    }
    for source in SOURCES:
        for profile in PROFILES:
            metrics = measure_cell(source, profile, "NORMAL", "v1.2", seeds, seed_offset=10_000)
            round_min, round_max = round_targets[profile]
            hp_min, hp_max = hp_targets[source.hero_class][profile]
            # The theoretical hard maximum is 99%; a finite empirical sample may land slightly above it.
            hit_min, hit_max = (0.82, 0.96) if profile == "SWIFT" else (0.875, 0.995)
            crit_min, crit_max = (0.06, 0.15) if source.hero_class == "ROGUE" else (0.018, 0.12)
            passed = (
                0.95 <= metrics.win_rate <= 1.0
                and round_min <= metrics.median_rounds <= round_max
                and hp_min <= metrics.median_hp_loss <= hp_max
                and hit_min <= metrics.hero_hit_rate <= hit_max
                and crit_min <= metrics.hero_crit_rate_on_hit <= crit_max
                and metrics.retreat_rate < 0.001
            )
            # Level 1 uses explicit starter-region coefficients and is audited separately.
            if not passed and source.level > 1:
                failures.append((source.label, profile, metrics))
            print(
                f"{source.label:6} {profile:11} {pct(metrics.win_rate):>7} "
                f"{metrics.median_rounds:4.1f}/{metrics.p90_rounds:2} "
                f"{metrics.median_hp_loss:5.1f}/{metrics.p90_hp_loss:5.1f}% "
                f"{pct(metrics.hero_hit_rate):>6} {pct(metrics.hero_crit_rate_on_hit):>6}"
            )
    non_starter_cells = sum(source.level > 1 for source in SOURCES) * len(PROFILES)
    print(f"non_starter_normal_gate_failures={len(failures)}/{non_starter_cells}")
    for label, profile, metrics in failures:
        print(
            f"FAIL {label}/{profile}: win={pct(metrics.win_rate)} rounds={metrics.median_rounds} "
            f"loss={metrics.median_hp_loss:.1f}% retreat={pct(metrics.retreat_rate)}"
        )


def print_rank_and_boundary_matrix(seeds: int) -> None:
    print("\n[v1.2 rank matrix: latest measured source, STANDARD]")
    print("source rank win/loss/retreat rounds/p90 hpLoss/p90")
    latest = [source for source in SOURCES if source.label.endswith("L58")]
    for source in latest:
        for rank in RANKS:
            metrics = measure_cell(source, "STANDARD", rank, "v1.2", seeds, seed_offset=20_000)
            print(
                f"{source.label:6} {rank:6} {pct(metrics.win_rate):>7}/{pct(metrics.loss_rate):>6}/{pct(metrics.retreat_rate):>6} "
                f"{metrics.median_rounds:4.1f}/{metrics.p90_rounds:2} "
                f"{metrics.median_hp_loss:5.1f}/{metrics.p90_hp_loss:5.1f}%"
            )

    print("\n[v1.2 boundary matrix: latest measured source, STANDARD NORMAL]")
    print("source enemyDelta startHP win/loss/retreat rounds hpLoss")
    for source in latest:
        for delta in (-3, 0, 3):
            for start_ratio in (1.0, 0.5, 0.25):
                metrics = measure_cell(
                    source,
                    "STANDARD",
                    "NORMAL",
                    "v1.2",
                    seeds,
                    enemy_level_offset=delta,
                    start_hp_ratio=start_ratio,
                    seed_offset=30_000,
                )
                print(
                    f"{source.label:6} {delta:+3} {start_ratio:6.0%} "
                    f"{pct(metrics.win_rate):>7}/{pct(metrics.loss_rate):>6}/{pct(metrics.retreat_rate):>6} "
                    f"{metrics.median_rounds:4.1f} {metrics.median_hp_loss:6.1f}%"
                )


def print_sensitivity(seeds: int) -> None:
    print("\n[v1.2 source sensitivity: latest measured source, mixed NORMAL expedition]")
    print("source band standardHP armoredHP expedition(p10/med/p90)")
    latest = [source for source in SOURCES if source.label.endswith("L58")]
    for source in latest:
        for band, multiplier in (("LOW", 0.80), ("BASE", 1.00), ("HIGH", 1.20)):
            candidate = scaled_source(source, multiplier, band)
            standard = measure_cell(candidate, "STANDARD", "NORMAL", "v1.2", seeds, seed_offset=40_000)
            armored = measure_cell(candidate, "ARMORED", "NORMAL", "v1.2", seeds, seed_offset=50_000)
            median_wins, p10_wins, p90_wins = measure_expedition(candidate, "v1.2", max(500, seeds // 2))
            print(
                f"{source.label:6} {band:4} {standard.median_hp_loss:9.1f}% {armored.median_hp_loss:9.1f}% "
                f"{p10_wins:2}/{median_wins:4.1f}/{p90_wins:2}"
            )

    print("\n[v1.2 weapon-only sensitivity: latest source, current armor retained]")
    print("source weaponBand standardRounds standardHP expedition(p10/med/p90)")
    for source in latest:
        for band, multiplier in (("LOW", 0.70), ("BASE", 1.00), ("CRAFT", 2.20)):
            candidate = replace(
                source,
                label=f"{source.label}-WEAPON-{band}",
                weapon_power=source.weapon_power * multiplier,
            )
            standard = measure_cell(candidate, "STANDARD", "NORMAL", "v1.2", seeds, seed_offset=60_000)
            median_wins, p10_wins, p90_wins = measure_expedition(candidate, "v1.2", max(500, seeds // 2))
            print(
                f"{source.label:6} {band:5} {standard.median_rounds:14.1f} {standard.median_hp_loss:9.1f}% "
                f"{p10_wins:2}/{median_wins:4.1f}/{p90_wins:2}"
            )


def onboarding_source(hero_class: str, seed: int) -> SourceStats:
    random = DeterministicRandom.for_event(seed, 0, "ONBOARDING_ROLL_AUDIT", hero_class)
    values = [3 + sum(random.next_int(0, 6) for _ in range(3)) for _ in range(6)]
    strength, constitution, dexterity, intelligence, wisdom, _charisma = values
    return SourceStats(
        label=f"{hero_class}-ROLL-{seed}",
        hero_class=hero_class,
        level=1,
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
    print("\n[v1.2 onboarding distribution: actual green_hills profile mix and explicit starter coefficients]")
    print("class win/loss/retreat rounds(p50/p90) hpLoss(p50/p90/p99) expedition(p10/p50/p90)")
    profiles = tuple(PROFILES)
    for hero_class in CLASS_MULTIPLIERS:
        results = []
        losses = []
        expedition_wins = []
        for seed in range(max(2_000, seeds)):
            source = onboarding_source(hero_class, seed)
            hero = hero_combatant(source, "v1.2")
            selector = DeterministicRandom.for_event(seed, 1, "STARTER_PROFILE_AUDIT", hero_class)
            roll = selector.next_double()
            # Current green_hills content: STANDARD 1/4, SWIFT 2/4, ARMORED 1/4.
            profile = profiles[0] if roll < 0.25 else profiles[1] if roll < 0.75 else profiles[2]
            monster = monster_combatant(1, profile, "NORMAL", "v1.2")
            starter_coefficients = {"STANDARD": 3_200, "SWIFT": 3_400, "ARMORED": 3_000}
            monster = replace(monster, action_coefficient_bps=starter_coefficients[profile])
            result = battle(hero, monster, seed=70_000 + seed)
            results.append(result)
            losses.append((hero.max_hp - result.hero_hp) * 100.0 / hero.max_hp)
            hp = hero.max_hp
            wins = 0
            for encounter in range(30):
                expedition_selector = DeterministicRandom.for_event(
                    seed,
                    100 + encounter,
                    "STARTER_EXPEDITION_PROFILE_AUDIT",
                    hero_class,
                )
                expedition_roll = expedition_selector.next_double()
                expedition_profile = (
                    profiles[0]
                    if expedition_roll < 0.25
                    else profiles[1]
                    if expedition_roll < 0.75
                    else profiles[2]
                )
                expedition_monster = monster_combatant(1, expedition_profile, "NORMAL", "v1.2")
                expedition_monster = replace(
                    expedition_monster,
                    action_coefficient_bps=starter_coefficients[expedition_profile],
                )
                expedition_result = battle(
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
        count = len(results)
        print(
            f"{hero_class:7} "
            f"{pct(sum(result.outcome == 'WIN' for result in results) / count):>7}/"
            f"{pct(sum(result.outcome == 'LOSS' for result in results) / count):>6}/"
            f"{pct(sum(result.outcome == 'RETREAT' for result in results) / count):>6} "
            f"{statistics.median(result.rounds for result in results):4.1f}/"
            f"{int(percentile([result.rounds for result in results], 0.90)):2} "
            f"{statistics.median(losses):5.1f}/{percentile(losses, 0.90):5.1f}/{percentile(losses, 0.99):5.1f}% "
            f"{int(percentile(expedition_wins, 0.10)):2}/"
            f"{statistics.median(expedition_wins):4.1f}/"
            f"{int(percentile(expedition_wins, 0.90)):2}"
        )


def run_invariants() -> None:
    source = SOURCES[-1]
    hero = hero_combatant(source, "v1.2")
    monster = monster_combatant(source.level, "STANDARD", "NORMAL", "v1.2")
    assert battle(hero, monster, 777) == battle(hero, monster, 777)
    assert 6500 <= hit_bps(hero, monster) <= 9900
    assert 200 <= critical_bps(hero, monster) <= 2000
    assert 0 <= mitigation_bps(hero, monster) <= 7000
    for raw in range(1, 2_000):
        assert effective_attribute(raw + 1, 58, "v1.2") >= effective_attribute(raw, 58, "v1.2")
        assert effective_growth(raw + 1, 58, "v1.2") >= effective_growth(raw, 58, "v1.2")
    # Fixed random budget: a miss still advances three attack rolls.
    first = DeterministicRandom.for_event(123, 9, "TURN_BATTLE_V5", "budget")
    second = DeterministicRandom.for_event(123, 9, "TURN_BATTLE_V5", "budget")
    attack(hero, replace(monster, evasion=10_000), first)
    second.next_int(0, 10_000)
    second.next_int(0, 10_000)
    second.next_int(9_500, 10_501)
    assert first.next_long() == second.next_long()
    print("\n[invariants] deterministic=PASS bounds=PASS monotonic_softcaps=PASS fixed_attack_rng_budget=PASS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=2_000)
    parser.add_argument(
        "--section",
        choices=("all", "sources", "comparison", "core", "onboarding", "rank", "sensitivity"),
        default="all",
    )
    args = parser.parse_args()
    if not 100 <= args.seeds <= 20_000:
        raise SystemExit("--seeds must be in 100..20000")
    print(f"AlarmQuest base-combat PD review; seeds_per_cell={args.seeds}")
    run_invariants()
    if args.section in ("all", "sources"):
        print_source_audit()
    if args.section in ("all", "comparison"):
        print_revision_comparison(args.seeds)
    if args.section in ("all", "core"):
        print_profile_matrix(args.seeds)
    if args.section in ("all", "onboarding"):
        print_onboarding_audit(args.seeds)
    if args.section in ("all", "rank"):
        print_rank_and_boundary_matrix(args.seeds)
    if args.section in ("all", "sensitivity"):
        print_sensitivity(args.seeds)


if __name__ == "__main__":
    main()
