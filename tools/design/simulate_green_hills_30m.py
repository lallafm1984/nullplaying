#!/usr/bin/env python3
"""Generate a deterministic 30-minute Green Hills text playthrough."""

from __future__ import annotations

import argparse
import json
import math
import random
from collections import Counter, deque
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from simulate_deadpan_stats import STAT_NAMES, choose_for_class, roll_set
from validate_green_hills_catalog import (
    DEFAULT_CATALOG,
    base_index,
    is_compatible,
    load_catalog,
    modifier_index,
    validate_catalog,
)


DEFAULT_REPORT = Path("artifacts/design/green-hills-30m-simulation-v0.1.md")
DEFAULT_VISIBLE_LOG = Path("artifacts/design/green-hills-30m-visible-log-v0.1.txt")
RUN_SECONDS = 30 * 60


@dataclass(frozen=True)
class Skill:
    base_name: str
    modifier_id: str
    display_name: str
    effects: dict[str, int]


@dataclass(frozen=True)
class LootItem:
    name: str
    base_id: str
    modifier_id: str
    slots: int
    sale_value: int


@dataclass(frozen=True)
class BattleRecord:
    index: int
    monster_id: str
    monster_name: str
    modifier_id: str
    modifier_category: str
    skill_name: str
    move_seconds: float
    combat_seconds: float
    cleanup_seconds: float
    attack_count: int
    energies: tuple[int, ...]
    damages: tuple[int, ...]
    acquired_names: tuple[str, ...]
    pair_key: str
    detailed_lines: tuple[str, ...]


@dataclass(frozen=True)
class RunResult:
    seed: int
    class_name: str
    primary_stat: str
    roll_sets: tuple[tuple[int, ...], ...]
    selected_stats: tuple[int, ...]
    effective_stats: tuple[int, ...]
    loadout_names: tuple[str, ...]
    skills: tuple[Skill, ...]
    elapsed_seconds: float
    battles: tuple[BattleRecord, ...]
    town_visits: int
    loot_acquired: int
    loot_sold: int
    bag_slots_used: int
    bag_capacity: int
    gold: int
    quest_completions: int
    discovery_completions: int
    curiosities: tuple[str, ...]
    xp: int
    visible_lines: tuple[str, ...]


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def format_time(seconds: float) -> str:
    total = max(0, int(round(seconds)))
    return f"{total // 60:02d}:{total % 60:02d}"


def effect_sum(effects: list[dict[str, Any]], key: str) -> int:
    return sum(int(effect.get(key, 0)) for effect in effects)


def apply_loadout(
    catalog: dict[str, Any],
    selected_stats: tuple[int, ...],
) -> tuple[tuple[int, ...], dict[str, int], tuple[str, ...]]:
    modifiers = modifier_index(catalog)
    equipment = base_index(catalog, "equipmentBases")
    stat_deltas = {stat: 0 for stat in STAT_NAMES}
    utility: Counter[str] = Counter()
    names: list[str] = []

    for item in catalog["representativeLoadout"]:
        base = equipment[item["equipmentId"]]
        display_prefixes = []
        for modifier_id in item["modifierIds"]:
            modifier = modifiers[modifier_id]
            display_prefixes.append(modifier["displayKo"])
            effects = modifier.get("effects", {})
            if "stat" in effects:
                stat_deltas[effects["stat"]] += int(effects["delta"])
            for stat, delta in effects.get("statDeltas", {}).items():
                stat_deltas[stat] += int(delta)
            for key, value in effects.items():
                if key not in {"stat", "delta", "statDeltas"}:
                    utility[key] += int(value)
        names.append(f"{' '.join(display_prefixes)} {base['nameKo']}")

    effective = tuple(
        int(clamp(selected_stats[index] + stat_deltas[stat], 3, 24))
        for index, stat in enumerate(STAT_NAMES)
    )
    return effective, dict(utility), tuple(names)


def assign_skills(
    catalog: dict[str, Any],
    class_id: str,
    rng: random.Random,
) -> tuple[Skill, ...]:
    class_entry = next(entry for entry in catalog["classes"] if entry["id"] == class_id)
    modifier_pool = list(catalog["modifiers"]["skill"])
    selected_modifiers = rng.sample(modifier_pool, len(class_entry["skills"]))
    return tuple(
        Skill(
            base_name=base_name,
            modifier_id=modifier["id"],
            display_name=f"{modifier['displayKo']} {base_name}",
            effects={key: int(value) for key, value in modifier.get("effects", {}).items()},
        )
        for base_name, modifier in zip(class_entry["skills"], selected_modifiers)
    )


def choose_monster(
    catalog: dict[str, Any],
    rng: random.Random,
    recent_pairs: deque[str],
) -> tuple[dict[str, Any], dict[str, Any] | None, str]:
    weights = catalog["monsterModifierWeights"]
    categories = tuple(weights)
    modifier_groups = {
        "scene": catalog["modifiers"]["monsterScene"],
        "mechanical": catalog["modifiers"]["monsterMechanical"],
        "rare": catalog["modifiers"]["monsterRare"],
    }
    for _ in range(200):
        monster = rng.choice(catalog["monsterBases"])
        category = rng.choices(categories, weights=[weights[key] for key in categories], k=1)[0]
        modifier = None if category == "none" else rng.choice(modifier_groups[category])
        modifier_id = "none" if modifier is None else modifier["id"]
        pair_key = f"{monster['id']}+{modifier_id}"
        if pair_key not in recent_pairs:
            recent_pairs.append(pair_key)
            return monster, modifier, category
    raise RuntimeError("could not satisfy recent monster pair exclusion")


def choose_loot_modifier(
    catalog: dict[str, Any],
    loot_base: dict[str, Any],
    rng: random.Random,
    *,
    allow_rare: bool,
) -> dict[str, Any]:
    tags = set(loot_base["tags"])
    candidates = [
        modifier
        for modifier in catalog["modifiers"]["loot"]
        if is_compatible(modifier, tags, allow_rare=allow_rare)
    ]
    if not candidates:
        raise RuntimeError(f"no compatible loot modifier for {loot_base['id']}")
    return rng.choice(candidates)


def battle_duration(
    effective_stats: tuple[int, ...],
    primary_index: int,
    gear_utility: dict[str, int],
    monster_effects: dict[str, int],
    skill_effects: dict[str, int],
) -> tuple[float, float, float]:
    dexterity = effective_stats[1]
    constitution = effective_stats[2]
    primary = effective_stats[primary_index]
    move_speed_bps = int(clamp((dexterity - 10) * 200, -1400, 2800))
    maintenance_bps = int(clamp((constitution - 10) * 200, -1400, 2800))
    combat_speed_bps = int(clamp((primary - 10) * 150, -1050, 2100))
    move_speed_bps += gear_utility.get("moveSpeedBps", 0)
    maintenance_bps += gear_utility.get("maintenanceSpeedBps", 0)
    combat_speed_bps += gear_utility.get("combatSpeedBps", 0)

    move_seconds = (
        8.0
        * (1.0 + monster_effects.get("moveTimeBps", 0) / 10_000.0)
        / (1.0 + move_speed_bps / 10_000.0)
    )
    combat_seconds = (
        12.0
        * (1.0 + monster_effects.get("combatTimeBps", 0) / 10_000.0)
        * (1.0 + skill_effects.get("combatTimeBps", 0) / 10_000.0)
        / (1.0 + combat_speed_bps / 10_000.0)
    )
    cleanup_seconds = 2.0 / (1.0 + maintenance_bps / 10_000.0)
    return move_seconds, combat_seconds, cleanup_seconds


def make_loot(
    catalog: dict[str, Any],
    monster: dict[str, Any],
    monster_effects: dict[str, int],
    skill: Skill,
    sale_bonus_bps: int,
    rng: random.Random,
    *,
    allow_rare: bool,
) -> LootItem:
    loot_by_id = base_index(catalog, "lootBases")
    loot_base = loot_by_id[rng.choice(monster["lootIds"])]
    modifier = choose_loot_modifier(catalog, loot_base, rng, allow_rare=allow_rare)
    effects = modifier.get("effects", {})
    reward_sale_bps = monster_effects.get("rewardBps", 0) + monster_effects.get("goldCompensationBps", 0)
    total_sale_bps = sale_bonus_bps + skill.effects.get("saleBps", 0)
    sale_value = max(
        1,
        math.floor(
            loot_base["basePrice"]
            * effects.get("priceBps", 10_000)
            / 10_000.0
            * (1.0 + total_sale_bps / 10_000.0)
            * (1.0 + reward_sale_bps / 10_000.0)
        ),
    )
    return LootItem(
        name=f"{modifier['displayKo']} {loot_base['nameKo']}",
        base_id=loot_base["id"],
        modifier_id=modifier["id"],
        slots=max(1, int(effects.get("bagSlots", 1))),
        sale_value=sale_value,
    )


def settle_bag(items: list[LootItem], pending_coupon_bps: int) -> tuple[int, int]:
    gold = 0
    coupon_bps = pending_coupon_bps
    for item in items:
        sale_value = item.sale_value
        if coupon_bps:
            sale_value = max(1, math.floor(sale_value * (1.0 + coupon_bps / 10_000.0)))
            coupon_bps = 0
        gold += sale_value
        if item.modifier_id == "loot_coupon":
            coupon_bps = 200
    return gold, coupon_bps


def run_simulation(catalog: dict[str, Any], seed: int) -> RunResult:
    class_id = "paladin"
    class_entry = next(entry for entry in catalog["classes"] if entry["id"] == class_id)
    primary_index = STAT_NAMES.index(class_entry["primaryStat"])
    roll_rng = random.Random(seed)
    content_rng = random.Random(seed + 1)
    visual_rng = random.Random(seed + 2)

    roll_sets = tuple(roll_set(roll_rng) for _ in range(4))
    selected_stats = choose_for_class(list(roll_sets), primary_index)
    effective_stats, gear_utility, loadout_names = apply_loadout(catalog, selected_stats)
    skills = assign_skills(catalog, class_id, content_rng)
    capacity = 12 + effective_stats[0] // 2 + gear_utility.get("bagSlots", 0)
    sale_stat_bps = int(clamp((effective_stats[5] - 10) * 200, -1400, 2800))
    sale_bonus_bps = sale_stat_bps + gear_utility.get("saleBps", 0)
    quest_stat_bps = int(clamp((effective_stats[3] - 10) * 200, -1400, 2800))
    discovery_stat_bps = int(clamp((effective_stats[4] - 10) * 250, -1750, 3500))

    elapsed = 0.0
    battle_index = 0
    town_visits = 0
    loot_acquired = 0
    loot_sold = 0
    gold = 0
    xp = 0.0
    quest_progress = 0.0
    discovery_gauge = 0.0
    pending_coupon_bps = 0
    bag: list[LootItem] = []
    curiosities: list[str] = []
    visible_lines: list[str] = []
    battles: list[BattleRecord] = []
    recent_pairs: deque[str] = deque(maxlen=10)

    while True:
        monster, modifier, category = choose_monster(catalog, content_rng, recent_pairs)
        monster_effects = {} if modifier is None else {
            key: int(value)
            for key, value in modifier.get("effects", {}).items()
            if isinstance(value, (int, float))
        }
        modifier_id = "none" if modifier is None else modifier["id"]
        modifier_name = "" if modifier is None else f"{modifier['displayKo']} "
        monster_name = f"{modifier_name}{monster['nameKo']}"
        skill = skills[battle_index % len(skills)]
        move_seconds, combat_seconds, cleanup_seconds = battle_duration(
            effective_stats,
            primary_index,
            gear_utility,
            monster_effects,
            skill.effects,
        )
        event_seconds = move_seconds + combat_seconds + cleanup_seconds
        if elapsed + event_seconds > RUN_SECONDS:
            break

        battle_index += 1
        battle_start = elapsed + move_seconds
        battle_end = battle_start + combat_seconds
        attack_count = int(clamp(round(combat_seconds / 3.0) + monster_effects.get("attackDelta", 0), 2, 8))
        energies = tuple([100] + [round(100 * remaining / attack_count) for remaining in range(attack_count - 1, -1, -1)])
        visual_power = 2 + 3 + effective_stats[primary_index] * 3
        damage_multiplier = 1.0 + skill.effects.get("displayDamageBps", 0) / 10_000.0
        damages = tuple(
            max(1, round(visual_power * damage_multiplier * visual_rng.uniform(0.9, 1.1)))
            for _ in range(attack_count)
        )

        loot_count = 1 + monster_effects.get("extraLoot", 0)
        if skill.effects.get("extraLootGaugeBps", 0) and content_rng.randrange(10_000) < skill.effects["extraLootGaugeBps"]:
            loot_count += 1
        acquired = [
            make_loot(
                catalog,
                monster,
                monster_effects,
                skill,
                sale_bonus_bps,
                content_rng,
                allow_rare=category == "rare",
            )
            for _ in range(loot_count)
        ]
        bag.extend(acquired)
        loot_acquired += len(acquired)

        loot_modifiers = modifier_index(catalog)
        loot_effects = [loot_modifiers[item.modifier_id].get("effects", {}) for item in acquired]
        quest_bps = (
            quest_stat_bps
            + gear_utility.get("questBps", 0)
            + monster_effects.get("questBps", 0)
            + skill.effects.get("questBps", 0)
            + effect_sum(loot_effects, "questBps")
        )
        discovery_bps = (
            discovery_stat_bps
            + gear_utility.get("discoveryBps", 0)
            + monster_effects.get("discoveryBps", 0)
            + skill.effects.get("discoveryBps", 0)
            + effect_sum(loot_effects, "discoveryBps")
        )
        reward_bps = monster_effects.get("rewardBps", 0) + skill.effects.get("rewardBps", 0)
        xp += 10.0 * (1.0 + reward_bps / 10_000.0)
        quest_progress += 10.0 * (1.0 + quest_bps / 10_000.0)
        discovery_gauge += 1_000.0 * (1.0 + discovery_bps / 10_000.0)

        for item, effects in zip(acquired, loot_effects):
            chance = int(effects.get("curiosityChanceBps", 0))
            if chance and content_rng.randrange(10_000) < chance:
                curiosity = content_rng.choice(catalog["modifiers"]["curiosity"])
                curiosities.append(f"{curiosity['displayKo']} {item.name}")
        while discovery_gauge >= 10_000.0:
            discovery_gauge -= 10_000.0
            curiosity = catalog["modifiers"]["curiosity"][len(curiosities) % len(catalog["modifiers"]["curiosity"])]
            curiosities.append(f"{curiosity['displayKo']} 초원 기념품")

        detailed = [
            f"[{format_time(battle_start)}] 전투 · {monster_name} · 몬스터 에너지(빨강) 100%"
        ]
        for attack_number, damage in enumerate(damages, start=1):
            attack_time = battle_start + combat_seconds * attack_number / attack_count
            detailed.append(
                f"[{format_time(attack_time)}] {skill.display_name} · {damage} 피해 · "
                f"에너지 {energies[attack_number - 1]}%→{energies[attack_number]}%"
            )
        for item in acquired:
            detailed.append(f"[{format_time(battle_end)}] 획득: {item.name}")
        visible_lines.extend(detailed)

        pair_key = f"{monster['id']}+{modifier_id}"
        battles.append(
            BattleRecord(
                index=battle_index,
                monster_id=monster["id"],
                monster_name=monster_name,
                modifier_id=modifier_id,
                modifier_category=category,
                skill_name=skill.display_name,
                move_seconds=move_seconds,
                combat_seconds=combat_seconds,
                cleanup_seconds=cleanup_seconds,
                attack_count=attack_count,
                energies=energies,
                damages=damages,
                acquired_names=tuple(item.name for item in acquired),
                pair_key=pair_key,
                detailed_lines=tuple(detailed),
            )
        )
        elapsed += event_seconds

        bag_slots_used = sum(item.slots for item in bag)
        if bag_slots_used >= capacity:
            constitution = effective_stats[2]
            maintenance_bps = int(clamp((constitution - 10) * 200, -1400, 2800))
            maintenance_bps += gear_utility.get("maintenanceSpeedBps", 0)
            town_seconds = 20.0 / (1.0 + maintenance_bps / 10_000.0)
            if elapsed + town_seconds > RUN_SECONDS:
                break
            elapsed += town_seconds
            town_visits += 1
            sold_gold, pending_coupon_bps = settle_bag(bag, pending_coupon_bps)
            gold += sold_gold
            loot_sold += len(bag)
            bag.clear()
            visible_lines.append(f"[{format_time(elapsed)}] 가방을 비우고 바로 다시 출발했다.")

    return RunResult(
        seed=seed,
        class_name=class_entry["nameKo"],
        primary_stat=class_entry["primaryStat"],
        roll_sets=roll_sets,
        selected_stats=selected_stats,
        effective_stats=effective_stats,
        loadout_names=loadout_names,
        skills=skills,
        elapsed_seconds=elapsed,
        battles=tuple(battles),
        town_visits=town_visits,
        loot_acquired=loot_acquired,
        loot_sold=loot_sold,
        bag_slots_used=sum(item.slots for item in bag),
        bag_capacity=capacity,
        gold=gold,
        quest_completions=math.floor(quest_progress / 100.0),
        discovery_completions=len(curiosities),
        curiosities=tuple(curiosities),
        xp=math.floor(xp),
        visible_lines=tuple(visible_lines),
    )


def qa_checks(catalog: dict[str, Any], result: RunResult, repeat: RunResult) -> dict[str, bool]:
    battle_pairs = [battle.pair_key for battle in result.battles]
    recent_pair_ok = all(
        pair not in battle_pairs[max(0, index - 10):index]
        for index, pair in enumerate(battle_pairs)
    )
    energy_ok = all(
        battle.energies[0] == 100
        and battle.energies[-1] == 0
        and len(battle.energies) == battle.attack_count + 1
        and all(left > right for left, right in zip(battle.energies, battle.energies[1:]))
        for battle in result.battles
    )
    no_incompatible_loot = True
    loot_by_id = base_index(catalog, "lootBases")
    modifiers = modifier_index(catalog)
    for battle in result.battles:
        for acquired_name in battle.acquired_names:
            matching_base = next((base for base in loot_by_id.values() if acquired_name.endswith(base["nameKo"])), None)
            matching_modifier = next((modifier for modifier in catalog["modifiers"]["loot"] if acquired_name.startswith(modifier["displayKo"])), None)
            if matching_base is None or matching_modifier is None or not is_compatible(
                matching_modifier,
                set(matching_base["tags"]),
                allow_rare=battle.modifier_category == "rare",
            ):
                no_incompatible_loot = False
    forbidden_visible_tokens = ("XP", "퀘스트 포인트", "골드", "남은 시간", "주인공 HP", "기록목록", "예정 목록", "상태 목록")
    visible_text = "\n".join(result.visible_lines)
    no_hidden_metrics = not any(token in visible_text for token in forbidden_visible_tokens)
    result_lines_ok = all(
        "획득:" in line
        for line in result.visible_lines
        if "획득" in line
    )

    damage_independent = True
    primary_index = STAT_NAMES.index(result.primary_stat)
    _, gear_utility, _ = apply_loadout(catalog, result.selected_stats)
    for battle in result.battles:
        modifier = modifiers.get(battle.modifier_id)
        monster_effects = {} if modifier is None else modifier.get("effects", {})
        skill = next(skill for skill in result.skills if skill.display_name == battle.skill_name)
        without_damage = dict(skill.effects)
        without_damage["displayDamageBps"] = without_damage.get("displayDamageBps", 0) + 99_999
        original = battle_duration(result.effective_stats, primary_index, gear_utility, monster_effects, skill.effects)
        changed = battle_duration(result.effective_stats, primary_index, gear_utility, monster_effects, without_damage)
        if original != changed:
            damage_independent = False

    deterministic = json.dumps(asdict(result), ensure_ascii=False, sort_keys=True) == json.dumps(
        asdict(repeat), ensure_ascii=False, sort_keys=True
    )
    return {
        "30분 이내 완료 사건만 정산": result.elapsed_seconds <= RUN_SECONDS and bool(result.battles),
        "몬스터 에너지 100%→0% 단조 감소": energy_ok,
        "표시 피해와 완료 시간 완전 분리": damage_independent,
        "최근 10회 몬스터·수식어 조합 중복 없음": recent_pair_ok,
        "전리품 금지 태그 조합 없음": no_incompatible_loot,
        "전투 노출문에 내부 XP·퀘스트·골드 없음": no_hidden_metrics,
        "결과 노출은 획득 아이템명 형식": result_lines_ok,
        "동일 시드 재실행 결과 일치": deterministic,
        "성기사 전투 주능력 STR": result.class_name == "성기사" and result.primary_stat == "STR",
    }


def quantile_triplet(values: list[int]) -> tuple[int, int, int]:
    ordered = sorted(values)
    return tuple(ordered[int((len(ordered) - 1) * fraction)] for fraction in (0.10, 0.50, 0.90))


def batch_summary(
    catalog: dict[str, Any],
    seed: int,
    samples: int,
) -> dict[str, dict[str, Any]]:
    collected = {key: [] for key in ("battles", "gold", "quests", "discoveries")}
    for offset in range(samples):
        result = run_simulation(catalog, seed + offset)
        collected["battles"].append(len(result.battles))
        collected["gold"].append(result.gold)
        collected["quests"].append(result.quest_completions)
        collected["discoveries"].append(result.discovery_completions)
    summary: dict[str, dict[str, Any]] = {}
    for key, values in collected.items():
        triplet = quantile_triplet(values)
        summary[key] = {
            "triplet": triplet,
            "ratio": triplet[2] / max(triplet[0], 1),
        }
    return summary


def render_report(
    result: RunResult,
    checks: dict[str, bool],
    visible_log_path: Path,
    batch: dict[str, dict[str, Any]],
    batch_samples: int,
) -> str:
    category_counts = Counter(battle.modifier_category for battle in result.battles)
    mechanical = next((battle for battle in result.battles if battle.modifier_category == "mechanical"), None)
    rare = next((battle for battle in result.battles if battle.modifier_category == "rare"), None)
    samples = [result.battles[0]]
    for candidate in (mechanical, rare):
        if candidate is not None and candidate.index not in {sample.index for sample in samples}:
            samples.append(candidate)
    passed = all(checks.values())

    lines = [
        "# AlarmQuest 초록빛 언덕 30분 텍스트 시뮬레이션 v0.1",
        "",
        f"- 시드: `{result.seed}`",
        f"- 직업: `{result.class_name}` / 전투 주능력 `{result.primary_stat}`",
        "- 규칙: 실제 HP 없음, 시간이 되면 처치, 표시 피해는 연출만 담당",
        f"- 전체 노출 로그: `{visible_log_path.as_posix()}`",
        "",
        "## 캐릭터",
        "",
        "| 구분 | STR | DEX | CON | INT | WIS | CHA |",
        "|---|---:|---:|---:|---:|---:|---:|",
        f"| 주사위 선택 | {' | '.join(map(str, result.selected_stats))} |",
        f"| 장비 적용 | {' | '.join(map(str, result.effective_stats))} |",
        "",
        "주사위 네 세트:",
        "",
    ]
    for index, stats in enumerate(result.roll_sets, start=1):
        chosen = " ← 선택" if stats == result.selected_stats else ""
        lines.append(f"- {index}: `{'/'.join(map(str, stats))}`{chosen}")
    lines.extend(["", "장비:", ""])
    lines.extend(f"- {name}" for name in result.loadout_names)
    lines.extend(["", "스킬:", ""])
    lines.extend(f"- {skill.display_name}" for skill in result.skills)

    lines.extend(
        [
            "",
            "## 30분 내부 정산 요약",
            "",
            "이 표는 밸런스 QA용이며 게임 전투 결과에는 노출하지 않는다.",
            "",
            "| 항목 | 결과 |",
            "|---|---:|",
            f"| 정산된 시간 | {format_time(result.elapsed_seconds)} / 30:00 |",
            f"| 전투 완료 | {len(result.battles)} |",
            f"| 마을 정비 | {result.town_visits} |",
            f"| 전리품 획득 / 판매 | {result.loot_acquired} / {result.loot_sold} |",
            f"| 가방 | {result.bag_slots_used} / {result.bag_capacity}칸 |",
            f"| 골드 | {result.gold} |",
            f"| 퀘스트 완료 | {result.quest_completions} |",
            f"| 발견 완료 | {result.discovery_completions} |",
            f"| XP | {result.xp} |",
            "",
            "몬스터 수식어 분포:",
            "",
            f"- 없음 {category_counts['none']} / 상황 {category_counts['scene']} / 기계 {category_counts['mechanical']} / 지역 희귀 {category_counts['rare']}",
            "",
            f"## {batch_samples:,}회 배치 편차",
            "",
            "| 지표 | p10 / p50 / p90 | p90/p10 |",
            "|---|---:|---:|",
            f"| 전투 | {' / '.join(map(str, batch['battles']['triplet']))} | {batch['battles']['ratio']:.3f} |",
            f"| 골드 | {' / '.join(map(str, batch['gold']['triplet']))} | {batch['gold']['ratio']:.3f} |",
            f"| 퀘스트 | {' / '.join(map(str, batch['quests']['triplet']))} | {batch['quests']['ratio']:.3f} |",
            f"| 발견 | {' / '.join(map(str, batch['discoveries']['triplet']))} | {batch['discoveries']['ratio']:.3f} |",
            "",
            f"- 최대 p90/p10: `{max(metric['ratio'] for metric in batch.values()):.3f}` / 기준 `≤1.350`",
            "",
            "## 노출 텍스트 표본",
            "",
            "남은 시간·기록 목록·예정 목록·상태 목록은 표시하지 않는다.",
            "",
        ]
    )
    for sample in samples:
        lines.extend([f"### 전투 {sample.index}", "", "```text"])
        lines.extend(sample.detailed_lines)
        lines.extend(["```", ""])

    lines.extend(["## QA", "", "| 검사 | 결과 |", "|---|---|"])
    for name, check_passed in checks.items():
        lines.append(f"| {name} | {'PASS' if check_passed else 'FAIL'} |")
    lines.extend(["", f"final result: {'passed' if passed else 'failed'}", ""])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--seed", type=int, default=20_260_809)
    parser.add_argument("--batch-samples", type=int, default=500)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--visible-log", type=Path, default=DEFAULT_VISIBLE_LOG)
    args = parser.parse_args()

    catalog = load_catalog(args.catalog)
    errors, _ = validate_catalog(catalog)
    if errors:
        raise SystemExit("catalog validation failed: " + "; ".join(errors))
    result = run_simulation(catalog, args.seed)
    repeat = run_simulation(catalog, args.seed)
    if args.batch_samples <= 0:
        raise SystemExit("--batch-samples must be positive")
    batch = batch_summary(catalog, args.seed, args.batch_samples)
    checks = qa_checks(catalog, result, repeat)
    checks["30분 배치 p90/p10 1.35 이하"] = max(
        metric["ratio"] for metric in batch.values()
    ) <= 1.35
    checks["전투 카드 몬스터명 18자 이하"] = all(
        len(battle.monster_name) <= 18 for battle in result.battles
    )
    report = render_report(result, checks, args.visible_log, batch, args.batch_samples)

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.visible_log.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(report, encoding="utf-8")
    args.visible_log.write_text("\n".join(result.visible_lines) + "\n", encoding="utf-8")
    print(args.report)
    print(args.visible_log)
    if not all(checks.values()):
        raise SystemExit("simulation QA failed")


if __name__ == "__main__":
    main()
