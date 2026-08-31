#!/usr/bin/env python3
"""Validate held-out exact engine replays and compute explicit login scenarios."""
from pathlib import Path
import hashlib
import json
import numpy as np
import pandas as pd
from stat_bonus_balance import OUT, ROOT, CLASSES, LEVELS, bonuses, evaluate, prepare

KEYS = ['class', 'seed_index', 'level']


def calendar_scenarios(d, params, variant, base_cap=8):
    rows = []
    for (hero, seed), trajectory in d.groupby(['class', 'seed_index']):
        t = trajectory.sort_values('level').copy()
        caps = bonuses(t, params)[0].to_numpy() if variant == 'selected' else np.full(len(t),base_cap)
        dt = np.diff(t.seconds, prepend=0)
        for gap in [4.,8.,12.,24.]:
            for session in [1.,5.,12.]:
                fg = session/60
                bank = caps * min(session/12, 1)
                active_fraction = (np.minimum(gap,bank) + fg)/(gap+fg)
                inverse = 1/active_fraction
                left = np.r_[inverse[0], inverse[:-1]]
                # Monotonic HP caps bound elapsed calendar time between endpoint
                # Riemann sums. Trapezoid estimate explicitly approximates the
                # unrecorded within-segment capacity path; not a login replay.
                low = np.cumsum(dt*inverse)/86400
                high = np.cumsum(dt*left)/86400
                days = (low+high)/2
                assert np.all(low <= high+1e-9)
                for i, (_,r) in enumerate(t.iterrows()):
                    if r.level == 1: continue
                    rows.append(dict(variant=variant,hero_class=hero,seed_index=int(seed),
                        level=int(r.level),gap_h=gap,session_min=session,cap_h=caps[i],
                        calendar_days=days[i],calendar_days_low=low[i],calendar_days_high=high[i],
                        sale_gold=r.sale_gold,sale_gold_day=r.sale_gold/days[i],
                        online_days=r.seconds/86400,kills=r.kills,casts=r.casts,attacks=r.attacks))
    return pd.DataFrame(rows)


def main():
    params=json.loads((OUT/'selected_parameters.json').read_text())
    base=pd.read_csv(OUT/'holdout_baseline.csv').sort_values(KEYS)
    selected=pd.read_csv(OUT/'holdout_selected.csv').sort_values(KEYS)
    for d in [base,selected]:
        assert d.shape == (288,26)
        assert not d.isna().any().any()
        assert not d.duplicated(KEYS).any()
        assert set(d.seed_index)=={2,3,4,5}
        assert set(d['class'])==set(CLASSES)
        assert (d.groupby(['class','seed_index'])['level'].count()==12).all()
        for col in ['seconds','kills','hp','mp','casts','attacks','sale_gold']:
            assert (d.groupby(['class','seed_index'])[col].diff().dropna()>=0).all(),col
    assert base[KEYS].equals(selected[KEYS])
    assert (base.tales.to_numpy()==selected.tales.to_numpy()).all()
    # No game-source edits introduced by analytical instrumentation.
    hashes=json.loads((OUT/'engine_source_sha256.json').read_text())
    for name,expected in hashes.items():
        if name.startswith('app/'):
            assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==expected,name
    selected_calendar=selected
    dense_path=OUT/'holdout_selected_dense.csv'
    if dense_path.exists():
        dense=pd.read_csv(dense_path).sort_values(KEYS)
        assert len(dense)==2400 and not dense.isna().any().any()
        assert not dense.duplicated(KEYS).any()
        check=selected.merge(dense,on=KEYS,suffixes=('_sparse','_dense'),validate='one_to_one')
        assert len(check)==288
        for col in selected.columns.difference(KEYS):
            assert np.allclose(check[col+'_sparse'],check[col+'_dense']),col
        selected_calendar=dense
    scenarios=pd.concat([calendar_scenarios(base,params,'baseline8'),
                         calendar_scenarios(base,params,'old12',12),
                         calendar_scenarios(selected_calendar,params,'selected')],ignore_index=True)
    scenarios.to_csv(OUT/'validated_calendar_scenarios.csv',index=False)
    group=['variant','level','gap_h','session_min','hero_class']
    # Pool equal-level completion time across equal-size seed cohorts. Do NOT
    # average arbitrary per-run rates or mix different completion levels.
    ag=scenarios.groupby(group).agg(calendar_days=('calendar_days','mean'),
        days_min=('calendar_days','min'),days_max=('calendar_days','max'),
        sale_gold=('sale_gold','mean'),online_days=('online_days','mean'),n=('seed_index','size')).reset_index()
    ag['progress_rate']=1/ag.calendar_days
    ag['sale_gold_day']=ag.sale_gold/ag.calendar_days
    paired=ag[ag.variant=='baseline8'].merge(ag[ag.variant=='selected'],
        on=['level','gap_h','session_min','hero_class'],suffixes=('_base','_selected'),validate='one_to_one')
    paired['progress_uplift_pct']=(paired.calendar_days_base/paired.calendar_days_selected-1)*100
    paired['sale_uplift_pct']=(paired.sale_gold_day_selected/paired.sale_gold_day_base-1)*100
    paired['days_saved_pct']=(1-paired.calendar_days_selected/paired.calendar_days_base)*100
    paired.to_csv(OUT/'validated_class_comparison.csv',index=False)
    gap_rows=[]
    for key,g in ag.groupby(['variant','level','gap_h','session_min']):
        gap_rows.append(dict(zip(['variant','level','gap_h','session_min'],key),
            progress_gap_pct=(g.progress_rate.max()/g.progress_rate.min()-1)*100,
            sale_gap_pct=(g.sale_gold_day.max()/g.sale_gold_day.min()-1)*100))
    gaps=pd.DataFrame(gap_rows)
    gaps.to_csv(OUT/'validated_class_gaps.csv',index=False)
    # Sensitivity is screening-model robustness, not more independent engine
    # trials. Vary each selected constant by +-20%, plus combat timing +-25%.
    _,train=prepare()
    sensitivity=[]
    for key in params:
        for scale in [.8,1.2]:
            q={**params,key:params[key]*scale}
            sensitivity.append(dict(change=f'{key} x{scale}',**evaluate(train,q)))
    for scale in [.75,1.25]:
        sensitivity.append(dict(change=f'combat_time x{scale}',**evaluate(train,params,combat_scale=scale)))
    pd.DataFrame(sensitivity).to_csv(OUT/'sensitivity.csv',index=False)
    # Holdout screening error diagnostic: transfer coefficients without retuning.
    b=base.copy()
    b['cycle_s']=b.groupby(['class','seed_index']).seconds.diff()/b.groupby(['class','seed_index']).kills.diff()
    b=b[b.level.isin(LEVELS)].groupby(['level','class']).mean(numeric_only=True)
    b=b.reindex(pd.MultiIndex.from_product([LEVELS,CLASSES],names=['level','class'])).reset_index()
    holdout_proxy=evaluate(b,params)
    # Boundary/monotonic property tests, including far beyond studied levels.
    stats=np.r_[0,np.geomspace(1,1e18,2000)]
    extremes=pd.DataFrame(dict(hp=stats,mp=stats,dex=stats,cha=stats,proc_pct=np.full(len(stats),20.)))
    cap,prob,dex,cha=bonuses(extremes,params)
    for a in [cap,prob,dex,cha]: assert (np.diff(a)>=-1e-12).all()
    assert cap.min()==8 and cap.max()<=8+params['hp_hours']
    assert prob.min()==.2 and prob.max()<=.28
    assert dex.min()==0 and dex.max()<=params['dex_max']
    assert cha.min()==0 and cha.max()<=params['cha_max']
    assert np.all(5*(1-dex)>=5*(1-params['dex_max']))
    # Current-engine seed control retains exact old behavior with hooks disabled.
    control_path=OUT/'engine_control.csv'
    control_ok=None
    if control_path.exists():
        old=pd.read_csv(OUT/'engine_trajectories.csv')
        ctrl=pd.read_csv(control_path)
        common=old.merge(ctrl,on=KEYS,suffixes=('_old','_ctrl'),validate='one_to_one')
        assert len(common)==24
        for col in old.columns.difference(KEYS):
            assert np.allclose(common[col+'_old'],common[col+'_ctrl']),col
        control_ok=True
    stat_data=pd.concat([pd.read_csv(OUT/'engine_trajectories.csv'),base],ignore_index=True)
    stat_bands=stat_data.groupby(['level','class']).agg(
        hp_min=('hp','min'),hp_mean=('hp','mean'),hp_max=('hp','max'),
        mp_min=('mp','min'),mp_mean=('mp','mean'),mp_max=('mp','max'),n=('seed_index','size')).reset_index()
    stat_bands.to_csv(OUT/'hp_mp_sample_ranges.csv',index=False)
    means=selected.groupby(['level','class']).mean(numeric_only=True).reset_index()
    # Exact nonlinear bonus means: calculate per simulated character then average.
    per=selected.copy()
    per['cap_h'],per['raw_proc'],per['dex_bonus'],per['cha_bonus']=bonuses(per,params)
    per['raw_proc_pct']=per.raw_proc*100
    per['search_s']=5*(1-per.dex_bonus)
    per['sale_bonus_pct']=100*per.cha_bonus
    effects=per.groupby(['level','class'])[['hp','mp','dex','cha','cap_h','raw_proc_pct','search_s','sale_bonus_pct']].mean().reset_index()
    effects.to_csv(OUT/'recommended_effects.csv',index=False)
    full=gaps[(gaps.session_min==12)&gaps.level.isin([20,50,100])]
    end=paired[(paired.level==100)&(paired.session_min==12)]
    report=dict(status='Share with caveats',engine_control_exact_match=control_ok,
        holdout_games=48,holdout_kills=int(base[base.level==100].kills.sum()+selected[selected.level==100].kills.sum()),
        train_games=12,train_seeds=[0,1],holdout_seeds=[2,3,4,5],parameters=params,
        maximum_calendar_integration_bound_pct=float(((scenarios.calendar_days_high/scenarios.calendar_days_low-1)*100).max()),
        level100_integration_bound_pct=float(((scenarios[scenarios.level==100].calendar_days_high/scenarios[scenarios.level==100].calendar_days_low-1)*100).max()),
        holdout_proxy=holdout_proxy,level100_full_charge=end[['gap_h','hero_class','progress_uplift_pct','days_saved_pct','sale_uplift_pct']].to_dict('records'),
        checkpoint_gaps=full.to_dict('records'),
        checks=['row completeness/uniqueness/nulls','disjoint train/holdout seeds','source hashes unchanged',
                'monotonic capped bonuses 0..1e18','calendar endpoint integration bounds','separate old12/baseline8',
                'pooled numerator/denominator aggregation','no post-holdout parameter retuning'])
    (OUT/'validation_summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Validation:',report['status'],'control=',control_ok)
    print('Integration bound Lv100',report['level100_integration_bound_pct'])
    print(full[full.gap_h.isin([8,12])].round(3).to_string(index=False))
    print(end[end.gap_h==12][['hero_class','calendar_days_base','calendar_days_selected','progress_uplift_pct','sale_uplift_pct']].round(3).to_string(index=False))


if __name__=='__main__': main()
