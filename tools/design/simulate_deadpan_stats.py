#!/usr/bin/env python3
"""Deterministic balance simulation for the deadpan auto-hero stat design."""

from __future__ import annotations

import argparse
import random
from dataclasses import dataclass
from math import floor
from pathlib import Path


STAT_NAMES = ("STR", "DEX", "CON", "INT", "WIS", "CHA")
CLASS_PRIMARY = {
    "전사": 0,
    "성기사": 0,
    "도적": 1,
    "사냥꾼": 1,
    "마법사": 3,
    "성직자": 4,
}
DURATIONS = {"30분": 30 * 60, "24시간": 24 * 60 * 60}


@dataclass(frozen=True)
class ProgressResult:
    battles: int
    quests: int
    discoveries: int
    gold: int


def roll_stat(rng: random.Random) -> int:
    return sum(rng.randint(1, 6) for _ in range(3))


def roll_set(rng: random.Random) -> tuple[int, ...]:
    return tuple(roll_stat(rng) for _ in STAT_NAMES)


def choose_for_total(candidates: list[tuple[int, ...]]) -> tuple[int, ...]:
    return max(candidates, key=lambda stats: (sum(stats), max(stats), stats))


def choose_for_class(
    candidates: list[tuple[int, ...]],
    primary_index: int,
) -> tuple[int, ...]:
    return max(
        candidates,
        key=lambda stats: (stats[primary_index], sum(stats), max(stats), stats),
    )


def progress_for(
    stats: tuple[int, ...],
    primary_index: int,
    duration_seconds: int,
) -> ProgressResult:
    strength, dexterity, constitution, intelligence, wisdom, charisma = stats
    capacity = 12 + strength // 2

    move_bonus = (dexterity - 10) * 0.02
    maintenance_bonus = (constitution - 10) * 0.02
    combat_bonus = (stats[primary_index] - 10) * 0.015
    quest_bonus = (intelligence - 10) * 0.02
    discovery_bonus = (wisdom - 10) * 0.025
    sale_bonus = (charisma - 10) * 0.02

    encounter_seconds = (
        8.0 / (1.0 + move_bonus)
        + 12.0 / (1.0 + combat_bonus)
        + 2.0 / (1.0 + maintenance_bonus)
    )
    town_seconds = 20.0 / (1.0 + maintenance_bonus)
    cycle_seconds = capacity * encounter_seconds + town_seconds

    completed_cycles = int(duration_seconds // cycle_seconds)
    remaining_seconds = duration_seconds - completed_cycles * cycle_seconds
    partial_battles = min(capacity, int(remaining_seconds // encounter_seconds))
    battles = completed_cycles * capacity + partial_battles
    sold_items = completed_cycles * capacity

    return ProgressResult(
        battles=battles,
        quests=floor(battles * 10.0 * (1.0 + quest_bonus) / 100.0),
        discoveries=floor(battles * 1_000.0 * (1.0 + discovery_bonus) / 10_000.0),
        gold=floor(sold_items * 10.0 * (1.0 + sale_bonus)),
    )


def quantile(values: list[int], fraction: float) -> int:
    ordered = sorted(values)
    return ordered[int((len(ordered) - 1) * fraction)]


def triplet(values: list[int]) -> tuple[int, int, int]:
    return quantile(values, 0.10), quantile(values, 0.50), quantile(values, 0.90)


def ratio(values: list[int]) -> float:
    low, _, high = triplet(values)
    return high / max(low, 1)


def distribution_rows(samples: int, seed: int) -> list[tuple[int, tuple[int, int, int], tuple[int, int, int]]]:
    rows = []
    for choice_count in range(1, 5):
        rng = random.Random(seed + choice_count * 101)
        totals: list[int] = []
        selected_stats: list[int] = []
        for _ in range(samples):
            selected = choose_for_total([roll_set(rng) for _ in range(choice_count)])
            totals.append(sum(selected))
            selected_stats.extend(selected)
        rows.append((choice_count, triplet(totals), triplet(selected_stats)))
    return rows


def class_rows(
    samples: int,
    seed: int,
) -> dict[str, dict[str, object]]:
    output: dict[str, dict[str, object]] = {}
    for class_offset, (class_name, primary_index) in enumerate(CLASS_PRIMARY.items()):
        rng = random.Random(seed + 10_000 + class_offset * 1_003)
        totals: list[int] = []
        primaries: list[int] = []
        duration_values = {
            duration_name: {
                "battles": [],
                "quests": [],
                "discoveries": [],
                "gold": [],
            }
            for duration_name in DURATIONS
        }
        for _ in range(samples):
            selected = choose_for_class([roll_set(rng) for _ in range(4)], primary_index)
            totals.append(sum(selected))
            primaries.append(selected[primary_index])
            for duration_name, duration_seconds in DURATIONS.items():
                result = progress_for(selected, primary_index, duration_seconds)
                duration_values[duration_name]["battles"].append(result.battles)
                duration_values[duration_name]["quests"].append(result.quests)
                duration_values[duration_name]["discoveries"].append(result.discoveries)
                duration_values[duration_name]["gold"].append(result.gold)

        output[class_name] = {
            "primary_index": primary_index,
            "total": triplet(totals),
            "primary": triplet(primaries),
            "durations": duration_values,
        }
    return output


def render_report(samples: int, seed: int) -> str:
    distributions = distribution_rows(samples, seed)
    classes = class_rows(samples, seed)
    metric_labels = {
        "battles": "전투",
        "quests": "퀘스트",
        "discoveries": "발견",
        "gold": "골드",
    }

    lines = [
        "# AlarmQuest 3d6 능력치 진행 시뮬레이션 v0.1",
        "",
        f"- 시드: `{seed}`",
        f"- 표본: 직업별 `{samples:,}`명",
        "- 규칙: 최초 3d6 세트 + 전체 재굴림 3회, 이전 결과 보존",
        "- 클래스 선택 모델: 네 세트 중 직업 주요 능력치 우선, 동률이면 총합 우선",
        "- 장비·몬스터·스킬 수식어: OFF",
        "",
        "## 기준 시간",
        "",
        "- 이동 8초",
        "- 일반 전투 12초",
        "- 전투 후 정리 2초",
        "- 가방 가득 참 이후 마을 정비 20초",
        "- 전투당 퀘스트 진행 10, 발견 게이지 1,000, 전리품 1개",
        "",
        "## 재굴림 분포",
        "",
        "| 선택 가능한 세트 | 합계 p10/p50/p90 | 선택 세트 능력치 p10/p50/p90 |",
        "|---:|---:|---:|",
    ]
    for choice_count, total_values, stat_values in distributions:
        lines.append(
            f"| {choice_count} | {'/'.join(map(str, total_values))} | {'/'.join(map(str, stat_values))} |"
        )

    lines.extend(["", "## 직업별 결과", ""])
    for duration_name in DURATIONS:
        lines.extend(
            [
                f"### {duration_name}",
                "",
                "| 직업 | 주요 능력 | 주요 능력 p10/p50/p90 | 전투 | 퀘스트 | 발견 | 골드 | 최대 p90/p10 |",
                "|---|---|---:|---:|---:|---:|---:|---:|",
            ]
        )
        for class_name, data in classes.items():
            primary_index = int(data["primary_index"])
            values = data["durations"][duration_name]
            triplets = {metric: triplet(metric_values) for metric, metric_values in values.items()}
            ratios = [ratio(metric_values) for metric_values in values.values()]
            lines.append(
                "| "
                + " | ".join(
                    [
                        class_name,
                        STAT_NAMES[primary_index],
                        "/".join(map(str, data["primary"])),
                        "/".join(map(str, triplets["battles"])),
                        "/".join(map(str, triplets["quests"])),
                        "/".join(map(str, triplets["discoveries"])),
                        "/".join(map(str, triplets["gold"])),
                        f"{max(ratios):.3f}",
                    ]
                )
                + " |"
            )
        lines.append("")

    medians_24h = {
        class_name: triplet(data["durations"]["24시간"]["battles"])[1]
        for class_name, data in classes.items()
    }
    median_spread = max(medians_24h.values()) / min(medians_24h.values())
    all_ratios = [
        ratio(metric_values)
        for data in classes.values()
        for duration_values in data["durations"].values()
        for metric_values in duration_values.values()
    ]
    pass_distribution = max(all_ratios) <= 1.35
    pass_class_spread = median_spread <= 1.05

    lines.extend(
        [
            "## 판정",
            "",
            f"- 직업 내부 p90/p10 최대값: `{max(all_ratios):.3f}` / 기준 `≤ 1.350` — {'PASS' if pass_distribution else 'FAIL'}",
            f"- 24시간 전투 중앙값 직업 간 격차: `{median_spread:.3f}` / 기준 `≤ 1.050` — {'PASS' if pass_class_spread else 'FAIL'}",
            f"- 전사와 성기사는 모두 STR 주요 능력 규칙 사용 — {'PASS' if classes['전사']['primary_index'] == classes['성기사']['primary_index'] == 0 else 'FAIL'}",
            "- 이 판정은 수식어를 끈 능력치 기준선이다. 다음 단계에서 장비·몬스터·스킬 수식어를 순서대로 결합한다.",
            "",
            f"final result: {'passed' if pass_distribution and pass_class_spread else 'failed'}",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=100_000)
    parser.add_argument("--seed", type=int, default=20_260_809)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("artifacts/design/deadpan-stat-simulation-v0.1.md"),
    )
    args = parser.parse_args()
    if args.samples <= 0:
        raise SystemExit("--samples must be positive")
    report = render_report(args.samples, args.seed)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report, encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
