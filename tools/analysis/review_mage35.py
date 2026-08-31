#!/usr/bin/env python3
"""Reproducible Mage 30/35 comparison, using only local simulation outputs."""
from pathlib import Path
import hashlib
import itertools
import json
import numpy as np
import pandas as pd
import ten_hour_balance as balance

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'docs/audits/2026-08-31-stat-bonus-balance/cleric-wis-cha'
OUT = BASE.parent / 'mage-35-review'
SEEDS = list(range(6, 14))


def review():
    provenance = json.loads((BASE / 'engine_source_sha256.json').read_text())
    for name, digest in provenance.items():
        if name.startswith('app/'):
            assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest, name
    before = pd.read_csv(BASE / 'ten_hour_independent_dense.csv')
    mage = pd.read_csv(OUT / 'mage35_dense.csv')
    assert len(before) == 4800 and len(mage) == 800
    assert set(mage['class']) == {'MAGE'} and set(mage.seed_index) == set(SEEDS)
    after = pd.concat([before[before['class'] != 'MAGE'], mage], ignore_index=True)
    order = pd.MultiIndex.from_product([balance.CLASSES, SEEDS, range(1, 101)], names=balance.KEYS)
    results, effects, late, curves, cubes = [], [], [], [], {}
    for variant, d in [('mage30', before), ('mage35', after)]:
        assert not d.duplicated(balance.KEYS).any() and not d.isna().any().any()
        d = d.set_index(balance.KEYS).reindex(order).reset_index()
        assert not d.isna().any().any()
        c = balance.calendar(d, 6000, .8, 2)
        cubes[variant] = c
        for j, (gap, session) in enumerate(itertools.product(balance.GAPS, balance.SESSIONS)):
            for level in [20, 30, 40, 50, 60, 70, 80, 90, 100]:
                days = c['pooled'][:, level-1, j]
                for i, cls in enumerate(balance.CLASSES):
                    results.append(dict(variant=variant, level=level, gap_h=gap, session_min=session,
                        hero_class=cls, calendar_days=float(days[i]),
                        speed_vs_paladin_pct=float((days[-1] / days[i]-1)*100)))
        x = d[d.level == 100].copy()
        bonus = np.where((variant == 'mage35') & (x['class'] == 'MAGE'), 15, 10)
        x['raw_proc_pct'] = np.minimum(20+bonus, x.proc_pct+bonus*np.minimum(x.mp/6000, 1))
        x['effective_proc_pct'] = x.raw_proc_pct / (1-(1-x.raw_proc_pct/100)**16)
        x['cap_h'] = 8+2*np.minimum(x.hp/6000, 1)**.8
        x['search_s'] = 5-np.minimum(x.dex/150, 1)
        x['sale_bonus_pct'] = 20*np.minimum(x.cha/150, 1)
        for cls, g in x.groupby('class'):
            effects.append(dict(variant=variant, hero_class=cls,
                **g[['raw_proc_pct','effective_proc_pct','cap_h','search_s','sale_bonus_pct','skill_damage_pct']].mean().to_dict()))
        # Late-game actual counters avoid confusing Lv1-100 averages with Lv100 probability.
        counters = ['seconds','kills','attacks','casts','sale_gold']
        z = d[d['class']=='MAGE'].set_index(['seed_index','level'])[counters]
        diff = z.xs(100, level='level')-z.xs(90, level='level')
        assert (diff > 0).all().all()
        sums = diff.sum()
        late.append(dict(variant=variant, pooled_cast_pct=float(100*sums.casts/sums.attacks),
            pooled_kills_per_hour=float(3600*sums.kills/sums.seconds),
            pooled_casts_per_hour=float(3600*sums.casts/sums.seconds),
            mean_segment_seconds=float(diff.seconds.mean()),
            mean_total_seconds=float(d[(d['class']=='MAGE')&(d.level==100)].seconds.mean())))
        for level in [20,40,60,80,100]:
            row = d[(d['class']=='MAGE') & (d.level == level)]
            mp_bonus = 15 if variant == 'mage35' else 10
            curves.append(dict(variant=variant,level=level,
                raw_proc_pct=float(np.minimum(20+mp_bonus,row.proc_pct+mp_bonus*np.minimum(row.mp/6000,1)).mean())))
    result = pd.DataFrame(results)
    late = pd.DataFrame(late)
    comparisons = []
    for gap in [8.,12.]:
        j = list(itertools.product(balance.GAPS,balance.SESSIONS)).index((gap,12.))
        b = cubes['mage30']['days'][3,:,-1,j]
        a = cubes['mage35']['days'][3,:,-1,j]
        # Paired bootstrap quantiles describe sampled seeds, not live players.
        idx = np.random.default_rng(350831).integers(0,8,(4000,8))
        boot = (b[idx].mean(axis=1)/a[idx].mean(axis=1)-1)*100
        low, high = np.quantile(boot,[.025,.975])
        for variant in ['mage30','mage35']:
            times = cubes[variant]['pooled'][:,-1,j]
            comparisons.append(dict(variant=variant,gap_h=gap,
                class_growth_spread_pct=float((times.max()/times.min()-1)*100),
                fastest=balance.CLASSES[int(np.argmin(times))],slowest=balance.CLASSES[int(np.argmax(times))]))
        comparisons.append(dict(variant='mage_change',gap_h=gap,
            mage_growth_speed_gain_pct=float((b.mean()/a.mean()-1)*100),
            paired_seed_quantile_low_pct=float(low),paired_seed_quantile_high_pct=float(high)))
    # Independent row-wise calendar spot-check, distinct from tensor implementation.
    for variant, d in [('mage30',before),('mage35',after)]:
        for seed in SEEDS:
            row = d[(d['class']=='MAGE')&(d.seed_index==seed)].sort_values('level')
            hp = row.hp.to_numpy(); times = row.seconds.to_numpy()
            inv = 12.2/(8+2*np.minimum(hp/6000,1)**.8+.2)
            dt = np.diff(times,prepend=0)
            days = sum(float(dt[k])*(float(inv[k])+float(inv[max(0,k-1)]))/2 for k in range(100))/86400
            j = list(itertools.product(balance.GAPS,balance.SESSIONS)).index((12.,12.))
            assert np.isclose(days,cubes[variant]['days'][3,seed-6,-1,j],rtol=1e-12)
    assert (after[after['class']!='MAGE'].reset_index(drop=True) == before[before['class']!='MAGE'].reset_index(drop=True)).all().all()
    summary = dict(mage_effects=[r for r in effects if r['hero_class']=='MAGE'],
        all_effects=effects, late_game=late.to_dict('records'), comparisons=comparisons,
        probability_curve=curves, input_seeds=SEEDS,app_source_unchanged=True,
        other_class_paths_unchanged=True,calendar_spot_check_passed=True,
        theoretical_fixed_proc=[dict(raw_pct=p,effective_pct=p/(1-(1-p/100)**16)) for p in [30,35]])
    result.to_csv(OUT/'growth_comparison.csv',index=False)
    pd.DataFrame(effects).to_csv(OUT/'level100_effects.csv',index=False)
    (OUT/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    return result, summary


if __name__ == '__main__':
    _, summary = review()
    print(json.dumps({k:v for k,v in summary.items() if k!='all_effects'},ensure_ascii=False,indent=2))
