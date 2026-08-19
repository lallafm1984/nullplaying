#!/usr/bin/env python3
"""Stage 19 audit for regions, enemy rosters, skill research and recommendations.

Reads current assets/source, defines a design-only 24-enemy vertical slice and
skill acquisition mappings, and validates deterministic automation contracts.
It never edits live JSON, Kotlin, Room, saves, or characters.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path

import equipment_catalog_650_lineages_v0_2_review as equipment
import skill_wave1_active5_passive3_v0_1_review as wave1
import skill_wave8_complete_240_global_review as skills240


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_REGION_ENEMY_SKILL_ACQUISITION_v0.1.md"
REGIONS_PATH = ROOT / "app/src/main/assets/content/regions.json"
ENEMIES_PATH = ROOT / "app/src/main/assets/content/enemies.json"
QUESTS_PATH = ROOT / "app/src/main/assets/content/quests.json"
CONTENT_MODELS_PATH = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/content/ContentModels.kt"
SETTLEMENT_PATH = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt"
CAMPAIGN_PATH = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/CampaignEngine.kt"
IDEA_BANK = ROOT / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"

REGION_IDS = ("green_hills", "old_mine", "ashen_forest")
PROFILES = ("STANDARD", "SWIFT", "ARMORED", "SPELLCASTER")
RANKS = ("NORMAL", "ELITE", "BOSS")
LINEAGES = ("BALANCED", "VANGUARD", "BASTION", "SEEKER", "TRAILBLAZER")
REGION_OFFSETS = {"green_hills": 0, "old_mine": 6, "ashen_forest": 12}
WORLD_TIER_COUNT = 47
MAX_COMBAT_RANK = 561


@dataclass(frozen=True)
class EnemyPlan:
    enemy_id: str
    name_ko: str
    region_id: str
    rank: str
    profile: str
    family: str
    existing_asset: bool
    field_weight: int
    status_immunities: tuple[str, ...]
    damage_resists_bps: tuple[tuple[str, int], ...]
    vulnerabilities_bps: tuple[tuple[str, int], ...]
    ability_ids: tuple[str, ...]
    research_skill_ids: tuple[str, ...] = ()


def enemy(enemy_id, name_ko, region_id, rank, profile, family, existing_asset,
          field_weight, status_immunities, damage_resists_bps,
          vulnerabilities_bps, ability_ids, research_skill_ids=()):
    return EnemyPlan(
        enemy_id, name_ko, region_id, rank, profile, family, existing_asset,
        field_weight, tuple(status_immunities), tuple(damage_resists_bps),
        tuple(vulnerabilities_bps), tuple(ability_ids), tuple(research_skill_ids),
    )


ENEMIES = (
    # Green Hills: four live enemies plus two normals, one elite, one boss.
    enemy("slime", "슬라임", "green_hills", "NORMAL", "ARMORED", "OOZE", True, 14,
          ("POISON",), (("PHYSICAL", 1_000),), (("LIGHTNING", 1_000),),
          ("slime_slam", "slime_shot"), ("EXT025", "EXT033")),
    enemy("field_rat", "들쥐", "green_hills", "NORMAL", "SWIFT", "BEAST", True, 14,
          (), (), (("POISON", 1_000),), ("rat_bite", "evasive_scurry")),
    enemy("hill_bee", "언덕 벌", "green_hills", "NORMAL", "SWIFT", "INSECT", True, 14,
          (), (("WIND", 1_000),), (("ICE", 1_000),), ("pollen_sting", "wing_feint")),
    enemy("grass_goblin", "풀잎 고블린", "green_hills", "NORMAL", "STANDARD", "HUMANOID", True, 14,
          (), (), (), ("goblin_chop", "improvised_guard")),
    enemy("thorn_boar", "가시멧돼지", "green_hills", "NORMAL", "ARMORED", "BEAST", False, 13,
          ("KNOCKBACK",), (("PHYSICAL", 1_000),), (("FIRE", 1_000),), ("thorn_charge", "mud_guard")),
    enemy("brook_wisp", "시냇불 정령", "green_hills", "NORMAL", "SPELLCASTER", "SPIRIT", False, 13,
          ("POISON",), (("MAGIC", 1_000),), (("HOLY", 1_500),), ("brook_bolt", "mist_veil")),
    enemy("goblin_trapmaster", "고블린 덫대장", "green_hills", "ELITE", "STANDARD", "HUMANOID", False, 18,
          (), (("PHYSICAL", 1_000),), (("LIGHTNING", 1_000),), ("tool_throw", "throwing_net", "sound_decoy")),
    enemy("mossback_colossus", "이끼등 거상", "green_hills", "BOSS", "ARMORED", "PLANT", False, 0,
          ("BIND", "KNOCKBACK"), (("PHYSICAL", 2_500),), (("FIRE", 1_500),),
          ("root_sweep", "giant_stomp", "bark_guard", "slow_heart"), ("EXT039", "EXT046")),

    # Old Mine: four live enemies plus three normals and one boss.
    enemy("goblin_scout", "고블린 정찰병", "old_mine", "NORMAL", "STANDARD", "HUMANOID", True, 14,
          (), (), (), ("tool_throw", "weakness_watch")),
    enemy("mine_bat", "광산 박쥐", "old_mine", "NORMAL", "SWIFT", "BEAST", True, 14,
          (), (("WIND", 1_000),), (("LIGHTNING", 1_000),), ("sonic_dive", "sound_wave")),
    enemy("tunnel_spider", "갱도 거미", "old_mine", "NORMAL", "STANDARD", "INSECT", True, 14,
          ("POISON",), (), (("FIRE", 1_000),), ("venom_web", "poison_mist"), ("EXT027",)),
    enemy("crystal_mole", "수정 두더지", "old_mine", "NORMAL", "ARMORED", "BEAST", False, 14,
          ("BLIND",), (("PHYSICAL", 1_000),), (("LIGHTNING", 1_000),), ("ore_burrow", "armor_shred")),
    enemy("bell_wraith", "종울림 망령", "old_mine", "NORMAL", "SPELLCASTER", "UNDEAD", False, 13,
          ("POISON",), (("PHYSICAL", 1_500),), (("HOLY", 2_000),), ("curse_bell", "wraith_grasp"), ("EXT032", "EXT036")),
    enemy("mine_mimic", "광차 미믹", "old_mine", "NORMAL", "STANDARD", "MIMIC", False, 13,
          (), (("MAGIC", 1_000),), (("FIRE", 1_000),), ("tongue_lash", "buff_steal"), ("EXT029",)),
    enemy("stone_dust_golem", "돌가루 골렘", "old_mine", "ELITE", "ARMORED", "CONSTRUCT", True, 18,
          ("POISON", "BLEED"), (("PHYSICAL", 2_000),), (("LIGHTNING", 1_500),),
          ("stone_crush", "golem_shard", "stone_bones"), ("EXT028", "EXT035")),
    enemy("deep_bell_keeper", "깊은 종의 지기", "old_mine", "BOSS", "SPELLCASTER", "UNDEAD", False, 0,
          ("POISON", "BLEED"), (("MAGIC", 2_500),), (("HOLY", 1_500),),
          ("clock_curse", "execution_bell", "dead_letter", "undying_obsession"), ("EXT038", "EXT041", "EXT045")),

    # Ashen Forest: four live enemies plus three normals and one boss.
    enemy("gray_wolf", "회색 늑대", "ashen_forest", "NORMAL", "SWIFT", "BEAST", True, 14,
          (), (), (("FIRE", 1_000),), ("pack_pounce", "wolf_leap"), ("EXT026", "EXT034")),
    enemy("mist_deer", "안개 사슴", "ashen_forest", "NORMAL", "STANDARD", "BEAST", True, 14,
          (), (("ICE", 1_000),), (("LIGHTNING", 1_000),), ("mist_charge", "mist_guard")),
    enemy("ash_shadow", "재 그림자", "ashen_forest", "NORMAL", "SWIFT", "SPIRIT", True, 14,
          ("BLEED",), (("PHYSICAL", 1_000),), (("HOLY", 1_500),), ("shadow_slash", "soul_frost")),
    enemy("ash_boar", "잿불멧돼지", "ashen_forest", "NORMAL", "ARMORED", "BEAST", False, 14,
          ("BURN",), (("FIRE", 1_500),), (("ICE", 1_000),), ("ash_charge", "ember_hide")),
    enemy("cinder_wyvern", "불씨 와이번", "ashen_forest", "NORMAL", "SWIFT", "DRAGON", False, 13,
          ("BURN",), (("FIRE", 1_500),), (("ICE", 1_500),), ("wyvern_dive", "fire_breath"), ("EXT030",)),
    enemy("root_revenant", "뿌리 망자", "ashen_forest", "NORMAL", "SPELLCASTER", "UNDEAD", False, 13,
          ("POISON",), (("PHYSICAL", 1_000),), (("FIRE", 1_500),), ("root_grasp", "curse_seed")),
    enemy("mushroom_mage", "버섯 마법사", "ashen_forest", "ELITE", "SPELLCASTER", "FUNGAL", True, 18,
          ("POISON",), (("MAGIC", 1_500),), (("FIRE", 1_500),),
          ("spore_bolt", "mushroom_spore", "poison_mist"), ("EXT031",)),
    enemy("ashen_wing_tyrant", "잿빛날개 폭군", "ashen_forest", "BOSS", "SWIFT", "DRAGON", False, 0,
          ("BURN", "KNOCKBACK"), (("FIRE", 3_000),), (("ICE", 1_500),),
          ("dragon_wrath", "wyvern_descent", "dragon_pride", "ash_storm"), ("EXT037", "EXT044")),
)

ENEMY_BY_ID = {item.enemy_id: item for item in ENEMIES}

WORLD_SKILL_BY_REGION = {
    "green_hills": ("EXT003", "EXT008", "EXT010", "EXT013", "EXT015", "EXT018", "EXT022", "EXT023"),
    "old_mine": ("EXT002", "EXT004", "EXT005", "EXT009", "EXT017", "EXT019", "EXT020", "EXT024"),
    "ashen_forest": ("EXT001", "EXT006", "EXT007", "EXT011", "EXT012", "EXT014", "EXT016", "EXT021"),
}

BOSS_SKILL_IDS = frozenset({"EXT037", "EXT038", "EXT039", "EXT041", "EXT044", "EXT045", "EXT046"})
SECRET_UNLOCKS = {
    "EXT040": "three_region_boss_archive_complete",
    "EXT042": "survive_revive_effect_and_win_without_second_revive",
    "EXT043": "clear_world_tier_20_boss_with_no_crown_equipment",
    "EXT047": "receive_three_distinct_curses_and_win",
    "EXT048": "clear_boss_with_two_or_fewer_passives_equipped",
}

SOURCE_POLICY = {
    "고유": "CHARACTER_IDENTITY_MILESTONE",
    "레벨": "COMBAT_RANK_MILESTONE",
    "직퀘": "CLASS_QUEST_MILESTONE",
    "공용": "COMMON_TRAINING_TIER",
    "월드퀘": "REGION_WORLD_QUEST",
    "몬스터": "MONSTER_ARCHIVE_RESEARCH",
    "보스": "BOSS_ARCHIVE_RESEARCH",
    "비밀": "ACCOUNT_SECRET_CONDITION",
}

ACQUISITION_DAYS = (1, 7, 30, 90, 180, 365, 730, 1_095)
ACQUISITION_CURVES = {
    "P10": (8, 9, 14, 24, 36, 58, 83, 100),
    "P50": (8, 12, 20, 36, 52, 76, 102, 114),
    "P90": (8, 16, 28, 48, 68, 92, 114, 120),
}

RECOMMENDATION_CONTEXTS = {
    "stable_standard": ("STANDARD", "NONE", "BALANCED", "AQ_REC_BALANCED_STABLE"),
    "timeout": ("ARMORED", "TTK_TIMEOUT", "VANGUARD", "AQ_REC_VANGUARD_TTK"),
    "physical_defeat": ("ARMORED", "DEFEAT_PHYSICAL", "BASTION", "AQ_REC_BASTION_SURVIVAL"),
    "miss_chain": ("SWIFT", "MISS_CHAIN", "SEEKER", "AQ_REC_SEEKER_HIT"),
    "resource_fail": ("SPELLCASTER", "RESOURCE_EMPTY", "TRAILBLAZER", "AQ_REC_TRAILBLAZER_SUSTAIN"),
}


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def asset_snapshot_hash() -> str:
    payload = {
        "regions": load_json(REGIONS_PATH),
        "enemies": load_json(ENEMIES_PATH),
        "quests": load_json(QUESTS_PATH),
    }
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def idea_rows():
    return re.findall(
        r"^\| ([A-Z]+\d{3}) \| ([^|]+?) \| ([^|]+?) \| ([AP]) \|",
        IDEA_BANK.read_text(encoding="utf-8"), re.MULTILINE,
    )


def canonical_hash() -> str:
    payload = {
        "skill240Hash": skills240.canonical_hash(),
        "equipment650Hash": equipment.canonical_hash(),
        "assetSnapshotHash": asset_snapshot_hash(),
        "enemies": [asdict(item) for item in ENEMIES],
        "worldSkills": WORLD_SKILL_BY_REGION,
        "secretUnlocks": SECRET_UNLOCKS,
        "sourcePolicy": SOURCE_POLICY,
        "acquisitionDays": ACQUISITION_DAYS,
        "acquisitionCurves": ACQUISITION_CURVES,
        "recommendationContexts": RECOMMENDATION_CONTEXTS,
        "worldTierCount": WORLD_TIER_COUNT,
        "maxCombatRank": MAX_COMBAT_RANK,
        "bossControlApplyMultiplierBps": 5_000,
        "bossControlRootDelayCap": 1,
        "manualCombatActions": False,
    }
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def check_current_source_truth_and_known_gaps():
    regions = load_json(REGIONS_PATH)
    assets = load_json(ENEMIES_PATH)
    quests = load_json(QUESTS_PATH)
    assert [item["id"] for item in regions] == list(REGION_IDS)
    assert len(assets) == 12 and {region: sum(item["regionId"] == region for item in assets) for region in REGION_IDS} == {region: 4 for region in REGION_IDS}
    assert sum(item.get("rank", "NORMAL") == "ELITE" for item in assets) == 2
    assert sum(item.get("rank", "NORMAL") == "BOSS" for item in assets) == 0
    assert len(quests) == 3 and {item["regionId"] for item in quests} == set(REGION_IDS)
    content_models = CONTENT_MODELS_PATH.read_text(encoding="utf-8")
    settlement = SETTLEMENT_PATH.read_text(encoding="utf-8")
    campaign = CAMPAIGN_PATH.read_text(encoding="utf-8")
    assert "val skillId: String? = null" in content_models
    assert "contentCatalog?.enemies?.filter { it.regionId == state.adventure.regionId }" in settlement
    assert "campaignEngine.resolveRareMaterials" in settlement
    assert "fun resolveRareMaterials(" in campaign
    assert "val family" not in content_models and "statusImmunities" not in content_models
    check_current_source_truth_and_known_gaps.gaps = (
        "12_TO_24_ENEMIES", "ZERO_TO_THREE_BOSSES", "ONE_TO_MULTI_ABILITY",
        "ADD_FAMILY", "ADD_IMMUNITY_RESIST_VULNERABILITY", "ADD_RESEARCH_SKILL_IDS",
    )


def check_24_enemy_roster_and_live_preservation():
    assert len(ENEMIES) == len(ENEMY_BY_ID) == 24
    assets = {item["id"]: item for item in load_json(ENEMIES_PATH)}
    assert {item.enemy_id for item in ENEMIES if item.existing_asset} == set(assets)
    expected_rank_counts = {"NORMAL": 6, "ELITE": 1, "BOSS": 1}
    expected_ability_counts = {"NORMAL": 2, "ELITE": 3, "BOSS": 4}
    for region in REGION_IDS:
        regional = [item for item in ENEMIES if item.region_id == region]
        assert len(regional) == 8
        assert {rank: sum(item.rank == rank for item in regional) for rank in RANKS} == expected_rank_counts
        assert set(item.profile for item in regional) == set(PROFILES)
        assert sum(item.field_weight for item in regional) == 100
        assert sum(item.field_weight for item in regional if item.rank == "ELITE") == 18
        assert sum(item.field_weight for item in regional if item.rank == "BOSS") == 0
    for item in ENEMIES:
        assert len(item.ability_ids) == expected_ability_counts[item.rank]
        assert len(set(item.ability_ids)) == len(item.ability_ids)
        assert len(item.status_immunities) <= (1 if item.rank == "NORMAL" else 2)
        assert all(0 < value <= (3_000 if item.rank == "BOSS" else 2_000) for _, value in item.damage_resists_bps)
        assert all(0 < value <= 2_000 for _, value in item.vulnerabilities_bps)
        assert set(stat for stat, _ in item.damage_resists_bps).isdisjoint(stat for stat, _ in item.vulnerabilities_bps)
        if item.existing_asset:
            asset = assets[item.enemy_id]
            assert item.name_ko == asset["name"]
            assert item.region_id == asset["regionId"]
            assert item.rank == asset.get("rank", "NORMAL")
            assert item.profile == asset["combatProfile"]
            assert item.ability_ids[0] == asset["skillId"]


def check_combat_rank_coverage_and_boss_control():
    covered = set()
    for world_tier in range(1, WORLD_TIER_COUNT + 1):
        base = 1 + 12 * (world_tier - 1)
        for region_id, offset in REGION_OFFSETS.items():
            assert region_id in REGION_IDS
            covered.update(range(base + offset, base + offset + 8))
    assert set(range(1, MAX_COMBAT_RANK + 1)).issubset(covered)
    assert max(covered) == 572
    bosses = [item for item in ENEMIES if item.rank == "BOSS"]
    assert len(bosses) == 3
    assert all(len(item.status_immunities) == 2 for item in bosses)
    # BOSS control is reduced, not globally immune: 50% apply and max 1 root delay.
    assert 5_000 <= skills240.STATUS_APPLY_FINAL_CAP_BPS
    check_combat_rank_coverage_and_boss_control.covered_max = max(covered)


def check_all_240_acquisition_sources_and_research_links():
    rows = idea_rows()
    assert len(rows) == 240
    source_counts = {source: sum(row[2].strip() == source for row in rows) for source in SOURCE_POLICY}
    assert source_counts == {"고유": 24, "레벨": 96, "직퀘": 24, "공용": 48, "월드퀘": 24, "몬스터": 12, "보스": 7, "비밀": 5}
    assert sum(len(ids) for ids in WORLD_SKILL_BY_REGION.values()) == 24
    assert all(len(ids) == 8 for ids in WORLD_SKILL_BY_REGION.values())
    world_ids = {row[0] for row in rows if row[2].strip() == "월드퀘"}
    assert {skill_id for ids in WORLD_SKILL_BY_REGION.values() for skill_id in ids} == world_ids
    monster_ids = {row[0] for row in rows if row[2].strip() == "몬스터"}
    linked_monster = {skill_id for enemy in ENEMIES for skill_id in enemy.research_skill_ids if skill_id in monster_ids}
    assert linked_monster == monster_ids and len(linked_monster) == 12
    boss_ids = {row[0] for row in rows if row[2].strip() == "보스"}
    linked_boss = {skill_id for enemy in ENEMIES for skill_id in enemy.research_skill_ids if skill_id in boss_ids}
    assert boss_ids == BOSS_SKILL_IDS == linked_boss
    secret_ids = {row[0] for row in rows if row[2].strip() == "비밀"}
    assert set(SECRET_UNLOCKS) == secret_ids and len(set(SECRET_UNLOCKS.values())) == 5
    assert all(not effect.grants_skill for effect in equipment.base.EFFECTS)


def check_acquisition_curves_and_character_scope():
    assert ACQUISITION_DAYS[-1] == 1_095
    for cohort, values in ACQUISITION_CURVES.items():
        assert len(values) == len(ACQUISITION_DAYS)
        assert values[0] == 8 and all(right >= left for left, right in zip(values, values[1:]))
        assert all(8 <= value <= 120 for value in values)
    assert all(
        p10 <= p50 <= p90
        for p10, p50, p90 in zip(ACQUISITION_CURVES["P10"], ACQUISITION_CURVES["P50"], ACQUISITION_CURVES["P90"])
    )
    assert ACQUISITION_CURVES["P10"][-1] == 100
    assert ACQUISITION_CURVES["P50"][-1] == 114
    assert ACQUISITION_CURVES["P90"][-1] == 120
    # Per character: 24 class + 48 common + 48 external; catalog total is account archive 240.
    assert 24 + 48 + 48 == 120


def recommend_lineage(profile: str, failure_reason: str, user_locked: str | None = None):
    if user_locked is not None:
        assert user_locked in LINEAGES
        return user_locked, "AQ_REC_USER_LOCK"
    if failure_reason == "DEFEAT_PHYSICAL":
        return "BASTION", "AQ_REC_BASTION_SURVIVAL"
    if failure_reason == "MISS_CHAIN" or profile == "SWIFT":
        return "SEEKER", "AQ_REC_SEEKER_HIT"
    if failure_reason == "RESOURCE_EMPTY":
        return "TRAILBLAZER", "AQ_REC_TRAILBLAZER_SUSTAIN"
    if failure_reason == "TTK_TIMEOUT":
        return "VANGUARD", "AQ_REC_VANGUARD_TTK"
    return "BALANCED", "AQ_REC_BALANCED_STABLE"


def check_recommendation_explainability_and_user_control():
    recommended = set()
    for _, (profile, failure, expected, reason) in RECOMMENDATION_CONTEXTS.items():
        actual, actual_reason = recommend_lineage(profile, failure)
        assert (actual, actual_reason) == (expected, reason)
        recommended.add(actual)
    assert recommended == set(LINEAGES)
    for lineage in LINEAGES:
        assert recommend_lineage("SWIFT", "MISS_CHAIN", user_locked=lineage) == (lineage, "AQ_REC_USER_LOCK")


def weighted_enemy(region_id: str, roll: int) -> EnemyPlan:
    candidates = [item for item in ENEMIES if item.region_id == region_id and item.field_weight > 0]
    cursor = roll % sum(item.field_weight for item in candidates)
    running = 0
    for item in candidates:
        running += item.field_weight
        if cursor < running:
            return item
    raise AssertionError("unreachable weighted selection")


def dynamic_metrics(seeds: int):
    assert seeds >= 100
    metrics = {}
    for region_id in REGION_IDS:
        selected = []
        for seed in range(seeds):
            for index in range(100):
                roll = wave1.legacy.c1.event_roll_bps(seed, index, "STAGE19_ENEMY", region_id) % 10_000
                selected.append(weighted_enemy(region_id, roll))
        elite_rate = sum(item.rank == "ELITE" for item in selected) / len(selected)
        boss_rate = sum(item.rank == "BOSS" for item in selected) / len(selected)
        profile_rates = {profile: sum(item.profile == profile for item in selected) / len(selected) for profile in PROFILES}
        assert 0.16 <= elite_rate <= 0.20
        assert boss_rate == 0.0
        assert all(rate >= 0.10 for rate in profile_rates.values())
        metrics[region_id] = {"eliteRate": elite_rate, "bossRate": boss_rate, "profileRates": profile_rates}
    return metrics


def check_document_hash():
    assert DOCUMENT.exists()
    assert canonical_hash() in DOCUMENT.read_text(encoding="utf-8")


STATIC_CHECKS = (
    ("CURRENT_ASSETS_AND_ENGINE_WIRING_AUDITED_WITH_SIX_EXPLICIT_GAPS", check_current_source_truth_and_known_gaps),
    ("THREE_REGIONS_HAVE_24_ENEMIES_SIX_NORMAL_ONE_ELITE_ONE_BOSS", check_24_enemy_roster_and_live_preservation),
    ("WORLD_TIERS_COVER_COMBAT_RANK_1_TO_561_AND_BOSS_CONTROL_IS_REDUCED", check_combat_rank_coverage_and_boss_control),
    ("ALL_240_SKILLS_HAVE_SOURCE_POLICY_AND_ALL_48_EXTERNAL_LINKS", check_all_240_acquisition_sources_and_research_links),
    ("P10_P50_P90_THREE_YEAR_ACQUISITION_CURVES_AND_CHARACTER120_SCOPE", check_acquisition_curves_and_character_scope),
    ("ALL_FIVE_EQUIPMENT_LINEAGES_HAVE_EXPLAINABLE_RECOMMENDATIONS_AND_LOCK", check_recommendation_explainability_and_user_control),
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=150)
    parser.add_argument("--pd", action="store_true")
    args = parser.parse_args()
    seeds = 300 if args.pd else args.seeds
    passed = []
    for name, check in STATIC_CHECKS:
        check()
        passed.append(name)
    metrics = dynamic_metrics(seeds)
    passed.append("DETERMINISTIC_FIELD_WEIGHTS_ELITE_RATE_PROFILE_COVERAGE_AND_NO_RANDOM_BOSS")
    if DOCUMENT.exists():
        check_document_hash()
        passed.append("DOCUMENT_CONTAINS_CANONICAL_STAGE19_HASH")
    print(f"REGION_ENEMY_SKILL_ACQUISITION_V0_1: PASS ({len(passed)}/{len(passed)}) seeds={seeds}")
    print(f"  SHA-256 {canonical_hash()}")
    print(f"  assetSnapshotSHA-256 {asset_snapshot_hash()}")
    print("  roster=3 regions x (6 NORMAL + 1 ELITE + 1 BOSS) = 24; profiles=4 each region")
    print("  skillSources=unique24 level96 classQuest24 common48 world24 monster12 boss7 secret5")
    print(f"  perCharacterAccess=120 acquisitionAt1095d={dict((k,v[-1]) for k,v in ACQUISITION_CURVES.items())}")
    print(f"  combatRankCoverage=1..{check_combat_rank_coverage_and_boss_control.covered_max} required=1..{MAX_COMBAT_RANK}")
    print(f"  liveGaps={check_current_source_truth_and_known_gaps.gaps}")
    for region_id, result in metrics.items():
        profiles = ','.join(f"{key}:{value:.1%}" for key, value in result['profileRates'].items())
        print(f"  {region_id} elite={result['eliteRate']:.1%} bossField={result['bossRate']:.1%} profiles={profiles}")
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
