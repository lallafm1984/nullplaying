#!/usr/bin/env python3
"""Local-only, source-calibrated candidate screening, not a population forecast.

The renewal/DPS model screens candidates; the separate Kotlin replay validates
the selected parameters with actual RNG, mastery, shop and quest feedback.
No credentials, app bootstrap, database driver, or network imports.
"""
from pathlib import Path
import itertools
import json
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/audits/2026-08-31-stat-bonus-balance'
CLASSES = ['WARRIOR', 'ROGUE', 'RANGER', 'MAGE', 'CLERIC', 'PALADIN']
LEVELS = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]
GAPS = np.array([4., 8., 12., 24.])


def effective_proc(p):
    return p / (-np.expm1(16 * np.log1p(-p)))


def bonuses(d, p):
    def fraction(values, threshold):
        if p.get('curve') == 'linear_cap': return (values/threshold).clip(0,1)
        return values/(values+threshold)
    return (8 + p['hp_hours'] * fraction(d.hp,p['hp_k']) ** p.get('hp_power',1.0),
            np.minimum((20+p['mp_pp'])/100, d.proc_pct / 100 + p['mp_pp'] / 100 * fraction(d.mp,p['mp_k'])),
            p['dex_max'] * fraction(d.dex,p['dex_k']),
            p['cha_max'] * fraction(d.cha,p['cha_k']))


def prepare():
    raw = pd.read_csv(OUT / 'engine_trajectories.csv').sort_values(['class', 'seed_index', 'level'])
    assert not raw.duplicated(['class', 'seed_index', 'level']).any()
    assert len(raw) == 6 * raw.seed_index.nunique() * 12
    # Segment baseline retains actual RNG/quest/loot timing. Marginal bonus model
    # holds equipment/mastery/quest mix at the checkpoint: an explicit approximation.
    g = raw.groupby(['class', 'seed_index'])
    raw['cycle_s'] = g.seconds.diff() / g.kills.diff()
    d = raw[raw.level.isin(LEVELS)].groupby(['level', 'class']).mean(numeric_only=True)
    d = d.reindex(pd.MultiIndex.from_product([LEVELS, CLASSES], names=['level', 'class'])).reset_index()
    return raw, d


def evaluate(d, params, details=False, charge_minutes=12, combat_scale=1.0):
    cap, prob, dex, cha = [np.asarray(x) for x in bonuses(d, params)]
    p0 = d.proc_pct.to_numpy() / 100
    skill = d.skill_damage_pct.to_numpy() / 100
    damage0 = .5 + (skill - .5) * effective_proc(p0)
    damage1 = .5 + (skill - .5) * effective_proc(prob)
    # 7s encounter, 1.6s victory, 3s loot, one 1s sale/item, town fixed
    # overhead approx 12/bag. First attack is at reveal, not after another 1.4s.
    fixed = 12.6 + 12 / d.bag.to_numpy()
    combat = np.maximum(1.4, d.cycle_s.to_numpy() - fixed)
    cycle0 = fixed + combat * combat_scale
    cycle1 = fixed - 5 * dex + combat * combat_scale * damage0 / damage1
    rate0, rate1 = 3600 / cycle0, 3600 / cycle1
    # Steady state after repeated gaps. Full refill requires 12m or ad; short
    # foreground sessions start from depleted banks once the gap exceeds charge.
    bank0 = 8 * min(charge_minutes / 12, 1)
    bank1 = cap * min(charge_minutes / 12, 1)
    covered0 = np.minimum(GAPS[None, :], bank0)
    covered1 = np.minimum(GAPS[None, :], bank1[:, None])
    daily0 = rate0[:, None] * covered0 * (24 / GAPS)
    daily1 = rate1[:, None] * covered1 * (24 / GAPS)
    # Same-level kill-rate proxy, NOT exact XP/day. Quest XP and grade mix
    # can vary, so real replay separately reports time to equal level.
    daily0 = daily0.reshape(len(LEVELS), 6, 4)
    daily1 = daily1.reshape(len(LEVELS), 6, 4)
    sale1 = daily1 * (1 + cha.reshape(len(LEVELS), 6, 1))
    spread = lambda a: (a.max(axis=1) / a.min(axis=1) - 1)
    gap0, gap1, goldgap = spread(daily0), spread(daily1), spread(sale1)
    uplift = daily1 / daily0 - 1
    # Minimax over level and login scenario; economic spread is a distinct,
    # half-weighted guardrail, not converted into XP or counted as XP again.
    score = gap1.max() + .5 * goldgap.max() + .25 * gap1.mean()
    result = dict(params, score=float(score), max_progress_gap=float(gap1.max()),
                  mean_progress_gap=float(gap1.mean()), max_sale_gap=float(goldgap.max()),
                  baseline_max_gap=float(gap0.max()), min_uplift=float(uplift.min()),
                  max_uplift=float(uplift.max()))
    if details:
        rows = []
        for i, row in d.iterrows():
            li, ci = divmod(i, 6)
            for gi, gap in enumerate(GAPS):
                rows.append(dict(level=int(row.level), hero_class=row['class'], gap_h=gap,
                    cap_h=cap[i], raw_proc_pct=prob[i]*100,
                    effective_proc_pct=effective_proc(prob[i])*100,
                    search_s=5*(1-dex[i]), sale_bonus_pct=cha[i]*100,
                    baseline_kills_day=daily0[li,ci,gi], kills_day=daily1[li,ci,gi],
                    uplift_pct=uplift[li,ci,gi]*100,
                    sale_rate_index=sale1[li,ci,gi], charge_minutes=charge_minutes))
        return result, pd.DataFrame(rows)
    return result


def main():
    raw, d = prepare()
    # Explicit product assumptions, not inferred player preferences:
    # Lv1 cap bonus <=15m, raw proc bonus <=0.3pp;
    # Lv20 cap bonus 10..60m, raw proc bonus <=2pp;
    # Lv100 every class gains >=1.5h capacity, >=2pp raw proc,
    # >=8% search reduction, >=8% sale value. No zero-bonus "winner".
    # Upper amplitudes protect the planned 8h direction from simply returning
    # everyone to 12h; candidates with up to 4h still included for comparison.
    early = raw[raw.level == 1].groupby('class').mean(numeric_only=True)
    mid = d[d.level == 20]
    late = d[d.level == 100]
    hp = [(a,k) for a,k in itertools.product([2., 2.5, 3., 4.], [200.,350.,500.,800.,1200.,2000.])
          if (a*early.hp/(early.hp+k)).max() <= .25
          and (a*mid.hp/(mid.hp+k)).min() >= 1/6
          and (a*mid.hp/(mid.hp+k)).max() <= 1
          and (a*late.hp/(late.hp+k)).min() >= 1.5]
    mp = [(a,k) for a,k in itertools.product([3.,4.,5.,6.,8.], [250.,500.,750.,1000.,1500.,2000.])
          if (a*early.mp/(early.mp+k)).max() <= .3
          and (a*mid.mp/(mid.mp+k)).max() <= 2
          and (a*late.mp/(late.mp+k)).min() >= 2]
    dex = [(a,k) for a,k in itertools.product([.15,.20,.25,.30], [20.,35.,50.,75.,100.])
           if (a*late.dex/(late.dex+k)).min() >= .08]
    cha = [(a,k) for a,k in itertools.product([.15,.20,.25,.30], [20.,35.,50.,75.,100.])
           if (a*late.cha/(late.cha+k)).min() >= .08]
    results = []
    for h,m,x,c in itertools.product(hp,mp,dex,cha):
        p = dict(hp_hours=h[0], hp_k=h[1], mp_pp=m[0], mp_k=m[1],
                 dex_max=x[0], dex_k=x[1], cha_max=c[0], cha_k=c[1])
        results.append(evaluate(d,p))
    grid = pd.DataFrame(results).sort_values(['score','hp_hours','mp_pp','dex_max','cha_max'])
    grid.to_csv(OUT/'candidate_grid.csv',index=False)
    selected = {k:float(grid.iloc[0][k]) for k in ['hp_hours','hp_k','mp_pp','mp_k','dex_max','dex_k','cha_max','cha_k']}
    (OUT/'selected_parameters.json').write_text(json.dumps(selected,indent=2)+'\n')
    result, detail = evaluate(d,selected,True)
    detail.to_csv(OUT/'screening_scenarios.csv',index=False)
    summary = dict(candidates=len(grid), feasible_options=dict(hp=len(hp),mp=len(mp),dex=len(dex),cha=len(cha)),
                   selected=result, runner_up=grid.head(10).to_dict('records'))
    (OUT/'screening_summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2))


if __name__ == '__main__':
    main()
