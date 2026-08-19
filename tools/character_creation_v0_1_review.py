#!/usr/bin/env python3
"""Planning audit for AlarmQuest D&D-like character creation v0.1.

The calculator validates the proposed species/class/background combinatorics,
ability-source rules, and the three currently modeled combat classes. It does
not mutate live content, onboarding, Room, or the production resolver.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass

import base_combat_v1_3_review as v13


ABILITIES = ("STR", "DEX", "CON", "INT", "WIS", "CHA")
STANDARD_ARRAY = (15, 14, 13, 12, 10, 8)


@dataclass(frozen=True)
class SpeciesDef:
    id: str
    name: str
    identity: str


@dataclass(frozen=True)
class ClassDef:
    id: str
    name: str
    primary: str
    secondary: str
    assignment: dict[str, int]
    recommended_background: str
    live_archetype: str | None


@dataclass(frozen=True)
class BackgroundDef:
    id: str
    name: str
    eligible_abilities: tuple[str, str, str]
    proficiencies: tuple[str, str]


SPECIES = (
    SpeciesDef("aq.species.human", "인간", "적응과 선택"),
    SpeciesDef("aq.species.elf", "엘프", "감각과 긴 휴식"),
    SpeciesDef("aq.species.dwarf", "드워프", "강인함과 제작"),
    SpeciesDef("aq.species.orc", "오크", "생존력과 운반"),
    SpeciesDef("aq.species.halfling", "하플링", "행운과 위기 회피"),
    SpeciesDef("aq.species.dragonkin", "용혈족", "원소 혈통과 저항"),
)

BACKGROUNDS = (
    BackgroundDef("aq.background.frontier_guard", "국경 수비대", ("STR", "DEX", "CON"), ("ATHLETICS", "INTIMIDATION")),
    BackgroundDef("aq.background.trail_guide", "황야 길잡이", ("DEX", "CON", "WIS"), ("SURVIVAL", "PERCEPTION")),
    BackgroundDef("aq.background.archive_apprentice", "기록관 견습", ("CON", "INT", "WIS"), ("ARCANA", "INVESTIGATION")),
    BackgroundDef("aq.background.guild_artisan", "길드 장인", ("STR", "INT", "CHA"), ("CRAFTING", "APPRAISAL")),
    BackgroundDef("aq.background.sanctuary_acolyte", "성소 봉사자", ("CON", "WIS", "CHA"), ("INSIGHT", "LORE")),
    BackgroundDef("aq.background.street_survivor", "뒷골목 생존자", ("DEX", "WIS", "CHA"), ("STEALTH", "DECEPTION")),
)

CLASSES = (
    ClassDef(
        "aq.class.warrior", "전사", "STR", "CON",
        {"STR": 15, "DEX": 13, "CON": 14, "INT": 8, "WIS": 12, "CHA": 10},
        "aq.background.frontier_guard", "WARRIOR",
    ),
    ClassDef(
        "aq.class.rogue", "도적", "DEX", "WIS",
        {"STR": 10, "DEX": 15, "CON": 13, "INT": 12, "WIS": 14, "CHA": 8},
        "aq.background.street_survivor", "ROGUE",
    ),
    ClassDef(
        "aq.class.ranger", "사냥꾼", "DEX", "WIS",
        {"STR": 12, "DEX": 15, "CON": 13, "INT": 10, "WIS": 14, "CHA": 8},
        "aq.background.trail_guide", None,
    ),
    ClassDef(
        "aq.class.mage", "마법사", "INT", "WIS",
        {"STR": 8, "DEX": 12, "CON": 13, "INT": 15, "WIS": 14, "CHA": 10},
        "aq.background.archive_apprentice", "MAGE",
    ),
    ClassDef(
        "aq.class.cleric", "성직자", "WIS", "CON",
        {"STR": 12, "DEX": 8, "CON": 14, "INT": 10, "WIS": 15, "CHA": 13},
        "aq.background.sanctuary_acolyte", None,
    ),
    ClassDef(
        "aq.class.paladin", "성기사", "STR", "CHA",
        {"STR": 15, "DEX": 10, "CON": 13, "INT": 8, "WIS": 12, "CHA": 14},
        "aq.background.guild_artisan", None,
    ),
)

BACKGROUND_BY_ID = {background.id: background for background in BACKGROUNDS}


def apply_background(
    class_def: ClassDef,
    background: BackgroundDef,
    base_scores: dict[str, int] | None = None,
) -> dict[str, int]:
    """Apply +2/+1 automatically using class-aware, deterministic priority."""
    scores = dict(base_scores or class_def.assignment)
    priority = (
        class_def.primary,
        class_def.secondary,
        "CON",
        "DEX",
        "WIS",
        "INT",
        "STR",
        "CHA",
    )
    eligible = list(background.eligible_abilities)
    plus_two = next(ability for ability in priority if ability in eligible)
    plus_one = next(
        ability for ability in priority
        if ability in eligible and ability != plus_two
    )
    scores[plus_two] = min(17, scores[plus_two] + 2)
    scores[plus_one] = min(17, scores[plus_one] + 1)
    return scores


def low_primary_assignment(class_def: ClassDef) -> dict[str, int]:
    result = {class_def.primary: 8, class_def.secondary: 10}
    remaining_scores = iter((15, 14, 13, 12))
    for ability in ABILITIES:
        if ability not in result:
            result[ability] = next(remaining_scores)
    return result


def background_without_roles(class_def: ClassDef) -> BackgroundDef:
    return next(
        background for background in BACKGROUNDS
        if class_def.primary not in background.eligible_abilities
        and class_def.secondary not in background.eligible_abilities
    )


def validate_combinatorics() -> None:
    assert len(SPECIES) == len({value.id for value in SPECIES}) == 6
    assert len(CLASSES) == len({value.id for value in CLASSES}) == 6
    assert len(BACKGROUNDS) == len({value.id for value in BACKGROUNDS}) == 6
    assert all(value.id.startswith("aq.species.") for value in SPECIES)
    assert all(value.id.startswith("aq.class.") for value in CLASSES)
    assert all(value.id.startswith("aq.background.") for value in BACKGROUNDS)
    assert len(SPECIES) * len(CLASSES) * len(BACKGROUNDS) == 216

    coverage = {ability: 0 for ability in ABILITIES}
    for background in BACKGROUNDS:
        assert len(set(background.eligible_abilities)) == 3
        assert set(background.eligible_abilities) <= set(ABILITIES)
        assert len(set(background.proficiencies)) == 2
        for ability in background.eligible_abilities:
            coverage[ability] += 1
    assert min(coverage.values()) >= 2

    for class_def in CLASSES:
        assert tuple(sorted(class_def.assignment.values(), reverse=True)) == STANDARD_ARRAY
        assert class_def.assignment[class_def.primary] == 15
        assert class_def.assignment[class_def.secondary] >= 14
        recommended = apply_background(
            class_def,
            BACKGROUND_BY_ID[class_def.recommended_background],
        )
        assert recommended[class_def.primary] == 17
        assert recommended[class_def.secondary] >= 15
        assert sum(recommended.values()) == 75
        assert max(recommended.values()) <= 17
        assert min(recommended.values()) >= 8

        for background in BACKGROUNDS:
            scores = apply_background(class_def, background)
            assert sum(scores.values()) == 75
            assert all(3 <= score <= 17 for score in scores.values())

    print(
        "[creation invariants] unique_ids=PASS combinations=216 "
        "id_namespace=PASS species_stat_bonus=NONE background_coverage=PASS "
        "standard_array=PASS creation_cap17=PASS"
    )


def source_for(
    class_def: ClassDef,
    scores: dict[str, int],
    label: str,
) -> v13.V13Source:
    assert class_def.live_archetype is not None
    start_hp, start_mp = v13.START_GROWTH[class_def.live_archetype]
    return v13.V13Source(
        label=label,
        hero_class=class_def.live_archetype,
        level=1,
        world_tier=1,
        strength=scores["STR"],
        dexterity=scores["DEX"],
        constitution=scores["CON"],
        intelligence=scores["INT"],
        wisdom=scores["WIS"],
        health_growth=start_hp,
        mana_growth=start_mp,
        weapon_power=2,
        armor_power=0.22,
    )


def pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def audit_current_combat_classes(seeds: int) -> list[str]:
    print("\n[v0.1 creation builds on current three-class v1.3 base combat]")
    print("class build background naturalStats combatStats winMin roundsRange hpLossP90Max")
    failures = []
    for class_def in CLASSES:
        if class_def.live_archetype is None:
            continue
        recommended_background = BACKGROUND_BY_ID[class_def.recommended_background]
        low_background = background_without_roles(class_def)
        builds = (
            ("RECOMMENDED", recommended_background, apply_background(class_def, recommended_background)),
            (
                "LOW_PRIMARY",
                low_background,
                apply_background(class_def, low_background, low_primary_assignment(class_def)),
            ),
        )
        for build_name, background, scores in builds:
            source = source_for(class_def, scores, f"{class_def.id}-{build_name}")
            metrics = [
                v13.core.measure_cell(
                    source,
                    profile,
                    "NORMAL",
                    "v1.3",
                    seeds,
                    seed_offset=910_000,
                )
                for profile in v13.PROFILES
            ]
            passed = all(
                value.win_rate >= 0.995
                and value.retreat_rate < 0.001
                and value.p90_hp_loss <= 55.0
                for value in metrics
            )
            if not passed:
                failures.append(f"{class_def.id}/{build_name}")
            combat_stats = v13.combat_attributes(source)
            print(
                f"{class_def.name:4} {build_name:11} {background.name:9} "
                f"{'/'.join(str(scores[a]) for a in ABILITIES):>17} "
                f"{'/'.join(str(v13.round_positive(value)) for value in combat_stats):>14} "
                f"{pct(min(value.win_rate for value in metrics)):>7} "
                f"{min(value.median_rounds for value in metrics):.0f}-"
                f"{max(value.median_rounds for value in metrics):.0f} "
                f"{max(value.p90_hp_loss for value in metrics):5.1f}%"
            )
    print(f"current_class_creation_gate_failures={len(failures)}/6")
    print("future_class_formula_status=RANGER,CLERIC,PALADIN_PENDING")
    return failures


def print_recommended_builds() -> None:
    print("\n[v0.1 recommended creation presets]")
    print("class primary/secondary background STR DEX CON INT WIS CHA")
    for class_def in CLASSES:
        background = BACKGROUND_BY_ID[class_def.recommended_background]
        scores = apply_background(class_def, background)
        print(
            f"{class_def.name:4} {class_def.primary:3}/{class_def.secondary:3} "
            f"{background.name:9} "
            + " ".join(f"{scores[ability]:3}" for ability in ABILITIES)
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=5_000)
    args = parser.parse_args()
    if not 500 <= args.seeds <= 20_000:
        raise SystemExit("--seeds must be in 500..20000")

    print(f"AlarmQuest character creation v0.1 review; seeds_per_cell={args.seeds}")
    validate_combinatorics()
    print_recommended_builds()
    failures = audit_current_combat_classes(args.seeds)
    if failures:
        raise SystemExit(f"PD_GATE=FAIL failures={len(failures)}")
    print("\nPD_GATE=PASS creation_foundation=CONDITIONAL_GO")


if __name__ == "__main__":
    main()
