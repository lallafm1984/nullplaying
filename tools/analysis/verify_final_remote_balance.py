#!/usr/bin/env python3
"""Validate the approved 480/20 replay and recompute calendar scenarios at 20m.

This is local analysis only. It never imports Android, Firebase, or a DB client.
"""
import hashlib
import itertools
import json
from datetime import datetime

import numpy as np
import pandas as pd

from run_stat_bonus_probe import ROOT

AUDIT = ROOT / 'docs/audits/2026-09-01-final-stat-bonus-implementation'
OUT = AUDIT / 'final-480-20-production'
CLASSES = ['WARRIOR', 'ROGUE', 'RANGER', 'MAGE', 'CLERIC', 'PALADIN']
KEYS = ['class', 'seed_index', 'level']


def read_replay(directory):
    path = directory / 'production-46-4-L100.csv'
    receipt = json.loads(path.with_suffix('.csv.execution.json').read_text())
    assert hashlib.sha256(path.read_bytes()).hexdigest() == receipt['output_sha256']
    data = pd.read_csv(path)
    assert len(data) == 2400 and not data.duplicated(KEYS).any()
    assert not data.isna().any().any()
    index = pd.MultiIndex.from_product([CLASSES, range(46, 50), range(1, 101)], names=KEYS)
    return data.set_index(KEYS).loc[index].reset_index()


def main():
    for name, digest in json.loads((OUT / 'source-sha256.json').read_text()).items():
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest, name
    data = read_replay(OUT)
    old = read_replay(AUDIT / 'remote-base-production')
    stable = [column for column in data if column != 'bonus_cap_h']
    pd.testing.assert_frame_equal(data[stable], old[stable], check_exact=True)
    x = ((data.level - 1) / 99).clip(0, 1)
    expected_capacity = 8 + 2 * (data.con / 150).clip(0, 1) ** 1.2 * (.15 + .85 * x ** 1.2)
    assert (np.abs(data.bonus_cap_h - expected_capacity) * 3_600_000 <= .50001).all()
    assert data.bonus_cap_h.between(8, 10).all()
    assert data.bonus_search_s.between(3.5, 5).all()
    assert data.bonus_sale_pct.between(0, 15).all()
    assert (data.bonus_proc_pct <= np.where(data['class'] == 'MAGE', 35, 30)).all()

    def cube(field):
        return data[field].to_numpy().reshape(6, 4, 100)

    capacity = cube('bonus_cap_h')
    elapsed = np.diff(cube('seconds'), axis=2, prepend=0)
    gold = cube('sale_gold')
    rows = []
    details = []
    for gap, session in itertools.product([4, 6, 8, 9, 10, 12, 16, 24], [1, 5, 12, 20]):
        foreground = session / 60
        factor = (gap + foreground) / (np.minimum(gap, capacity * min(session / 20, 1)) + foreground)
        left = np.concatenate([factor[:, :, :1], factor[:, :, :-1]], axis=2)
        lower = np.cumsum(elapsed * factor, axis=2) / 86400
        upper = np.cumsum(elapsed * left, axis=2) / 86400
        assert np.all(lower <= upper + 1e-9)
        days = ((lower + upper) / 2).mean(axis=1)
        income = gold.mean(axis=1) / np.where(days > 0, days, np.nan)
        for level in range(20, 101):
            a, b = days[:, level - 1], income[:, level - 1]
            rows.append(dict(level=level, gap_h=gap, session_min=session,
                growth_spread_pct=float((a.max() / a.min() - 1) * 100),
                sale_spread_pct=float((b.max() / b.min() - 1) * 100),
                fastest=CLASSES[int(a.argmin())], slowest=CLASSES[int(a.argmax())],
                highest_sale=CLASSES[int(b.argmax())]))
            if level == 100:
                for i, hero_class in enumerate(CLASSES):
                    details.append(dict(hero_class=hero_class, gap_h=gap, session_min=session,
                        calendar_days=float(a[i]), sale_gold_per_day=float(b[i])))
    scenarios = pd.DataFrame(rows)
    scenarios.to_csv(OUT / 'calendar-20min.csv', index=False)
    pd.DataFrame(details).to_csv(OUT / 'calendar-level100-20min.csv', index=False)
    result = dict(checked_at=datetime.now().astimezone().isoformat(),
        base_minutes=480, charge_minutes=20, games=24, rows=len(data), seed_range=[46, 49],
        live_source_hash_check='PASS', formulas_and_bounds='PASS',
        continuous_progression_vs_720_12='All non-capacity columns exactly match at every level',
        max_growth_spread_20_100=float(scenarios.growth_spread_pct.max()),
        max_sale_spread_20_100=float(scenarios.sale_spread_pct.max()),
        level100_effects=data[data.level == 100].groupby('class')[
            ['bonus_cap_h', 'bonus_proc_pct', 'bonus_search_s', 'bonus_sale_pct']].mean().to_dict('index'),
        key_scenarios=scenarios[(scenarios.level == 100) & scenarios.gap_h.isin([8, 12]) &
            scenarios.session_min.isin([1, 12, 20])].to_dict('records'),
        caveats=['Four deterministic seeds per class, not a player experiment.',
            'Calendar values approximate recurring sessions using adjacent level capacities.',
            'Foreground progress is simulated continuously; partial charging is a separate calendar model.',
            'The user-selected class-identity design does not meet the old 6% equal-efficiency threshold.'])
    (OUT / 'VALIDATION.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
