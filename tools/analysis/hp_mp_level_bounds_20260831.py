#!/usr/bin/env python3
"""Read-only arithmetic audit of AlarmQuest HP/MP, as inspected 2026-08-31.

Run: python3 tools/analysis/hp_mp_level_bounds_20260831.py
No Android, network, database, device registration, or file writes are used.

The main table is a formula envelope, NOT certified attainable extrema of the
current StableRng. Expectations use independent fair dice/draws, no reroll
selection, and no Adventure Tale bonus. The separate RNG audit demonstrates
why these assumptions must not be presented as measured current-game values.

Source: app/src/simple/java/com/nullplaying/engine/SimpleGameEngine.kt
rollStats, initialMaxHealth, initialMaxMana, manaBaseAttribute,
applyClassGuidedGrowth, advanceTaleOnVictory, and StableRng.
Class indices: app/src/simple/java/com/nullplaying/model/SimpleGameModels.kt.
"""

from collections import Counter
from itertools import product
from math import isclose, sqrt
from random import Random
import sys


MAX_LEVEL = 100
CHECKPOINTS = (1, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
CLASSES = {
    "Warrior": {"hp_base": 10, "mp_base": 4, "focus": (0, 1),
                "con_delta": {0: 5 / 12, 1: 1 / 2, 2: 1 / 12},
                "sum_delta": {0: 2 / 3, 1: 1 / 3}},
    "Mage": {"hp_base": 6, "mp_base": 10, "focus": (3, 4),
             "con_delta": {0: 5 / 6, 1: 1 / 6},
             "sum_delta": {1: 2 / 3, 2: 1 / 3}},
}


def convolve(left, right):
    result = Counter()
    for x, px in left.items():
        for y, py in right.items():
            result[x + y] += px * py
    return dict(result)


def mean(dist, fn):
    return sum(prob * fn(value) for value, prob in dist.items())


def initial_hp(con, hp_base):
    return max(1, hp_base + (con - 10) // 2)


def initial_mp(total, mp_base):
    return max(1, mp_base + (total // 2 - 10) // 2)


def formula_table(class_name):
    spec = CLASSES[class_name]
    initial_counts = Counter(map(sum, product(range(1, 7), repeat=3)))
    con_dist = {value: count / 216 for value, count in initial_counts.items()}
    sum_dist = convolve(con_dist, con_dist)
    hp_mean = mean(con_dist, lambda c: initial_hp(c, spec["hp_base"]))
    mp_mean = mean(sum_dist, lambda s: initial_mp(s, spec["mp_base"]))
    hp_low = initial_hp(3, spec["hp_base"])
    hp_high = initial_hp(18, spec["hp_base"])
    mp_low = initial_mp(6, spec["mp_base"])
    mp_high = initial_mp(36, spec["mp_base"])
    rows = {}
    for level in range(1, MAX_LEVEL + 1):
        row = (hp_low, hp_mean, hp_high, mp_low, mp_mean, mp_high)
        assert hp_low <= hp_mean <= hp_high
        assert mp_low <= mp_mean <= mp_high
        assert isclose(sum(con_dist.values()), 1, abs_tol=1e-12)
        assert isclose(sum(sum_dist.values()), 1, abs_tol=1e-12)
        rows[level] = row
        # k growth events have already happened BEFORE this growth's HP/MP.
        k = level - 1
        hp_low += 3 // 3 + 1
        max_con = 18 + (2 if class_name == "Warrior" else 1) * k
        hp_high += max_con // 3 + 4
        min_sum = 6 + (k if class_name == "Mage" else 0)
        max_sum = 36 + (2 if class_name == "Mage" else 1) * k
        mp_low += min_sum // 6 + 1
        mp_high += max_sum // 6 + 4
        hp_mean += mean(con_dist, lambda c: c // 3 + 2.5)
        mp_mean += mean(sum_dist, lambda s: s // 6 + 2.5)
        # Stats are increased only AFTER HP/MP in the actual source.
        con_dist = convolve(con_dist, spec["con_delta"])
        sum_dist = convolve(sum_dist, spec["sum_delta"])
    return rows


def independent_simulation_check(class_name, table, samples=20000):
    """Independent implementation of the nominal probability model."""
    rng = Random(20260831)
    spec = CLASSES[class_name]
    totals = [0, 0]
    squares = [0, 0]
    for _ in range(samples):
        stats = [sum(rng.randint(1, 6) for _ in range(3)) for _ in range(6)]
        hp = initial_hp(stats[1], spec["hp_base"])
        mp = initial_mp(stats[3] + stats[4], spec["mp_base"])
        for level in range(2, MAX_LEVEL + 1):
            hp += stats[1] // 3 + 1 + rng.randrange(4)
            mp += ((stats[3] + stats[4]) // 2) // 3 + 1 + rng.randrange(4)
            stats[spec["focus"][rng.randrange(2)]] += 1
            stats[rng.randrange(6)] += 1
            loh, _, hih, lom, _, him = table[level]
            assert loh <= hp <= hih and lom <= mp <= him
        for i, value in enumerate((hp, mp)):
            totals[i] += value
            squares[i] += value * value
    for i, measure in enumerate(("HP", "MP")):
        observed = totals[i] / samples
        variance = (squares[i] - totals[i] ** 2 / samples) / (samples - 1)
        stderr = sqrt(variance / samples)
        expected = table[MAX_LEVEL][1 if i == 0 else 4]
        assert abs(observed - expected) < 5 * stderr
        print(f"QA {class_name} {measure}: exact-model={expected:.6f}, "
              f"simulation={observed:.6f}, SE={stderr:.6f}, n={samples}")


def current_rng_audit(samples=100000):
    """Replicate current 64-bit StableRng; do not modify or call the game."""
    a, c, mask = 6364136223846793005, 1442695040888963407, (1 << 64) - 1
    rng = Random(20260831)
    mins, maxs = [100] * 6, [0] * 6
    resource_ranges = {name: [100, 0, 100, 0] for name in CLASSES}
    for _ in range(samples):
        state = rng.getrandbits(64) or 1
        stats = []
        for index in range(6):
            score = 3
            for _ in range(3):
                state = (state * a + c) & mask
                score += (state >> 1) % 6
            stats.append(score)
            mins[index] = min(mins[index], score)
            maxs[index] = max(maxs[index], score)
        for name, spec in CLASSES.items():
            hp = initial_hp(stats[1], spec["hp_base"])
            mp = initial_mp(stats[3] + stats[4], spec["mp_base"])
            bounds = resource_ranges[name]
            bounds[0] = min(bounds[0], hp)
            bounds[1] = max(bounds[1], hp)
            bounds[2] = min(bounds[2], mp)
            bounds[3] = max(bounds[3], mp)
    # Algebraic support check: nextInt(6) parity follows the low two state bits.
    # A mod 4 == 1, C mod 4 == 3: three consecutive draws cannot all be even
    # or all odd, so threeDice() cannot produce either 3 or 18.
    assert a % 4 == 1 and c % 4 == 3
    for residue in range(4):
        parity = []
        for _ in range(3):
            residue = (a * residue + c) % 4
            parity.append(residue >> 1)
        assert 0 < sum(parity) < 3
    assert mins == [4] * 6 and maxs == [17] * 6
    print(f"Current RNG initial-stat audit n={samples}: min={mins}, max={maxs}")
    print(f"Observed Lv.1 resource ranges [HP min,max,MP min,max]: {resource_ranges}")
    print("NOTE: nominal 3..18 model table is not a certified current-RNG range/mean.")


def main():
    tables = {name: formula_table(name) for name in CLASSES}
    # Initial and Lv.2 boundary checks, including integer rounding and MP clamp.
    assert tables["Warrior"][1][0] == 6 and tables["Warrior"][1][2] == 14
    assert tables["Warrior"][1][3] == 1 and tables["Warrior"][1][5] == 8
    assert tables["Mage"][1][0] == 2 and tables["Mage"][1][2] == 10
    assert tables["Warrior"][2][0] == 8 and tables["Warrior"][2][2] == 24
    assert tables["Mage"][2][3] == 8 and tables["Mage"][2][5] == 24
    for name, table in tables.items():
        print(f"\n{name}: nominal independent-draw model, no tales, no reroll selection")
        print("level | HP lower | HP expected | HP upper | MP lower | MP expected | MP upper")
        for level in (range(1, MAX_LEVEL + 1) if "--all" in sys.argv else CHECKPOINTS):
            a, b, c, d, e, f = table[level]
            print(f"{level} | {a} | {b:.1f} | {c} | {d} | {e:.1f} | {f}")
        independent_simulation_check(name, table)
    current_rng_audit()


if __name__ == "__main__":
    main()
