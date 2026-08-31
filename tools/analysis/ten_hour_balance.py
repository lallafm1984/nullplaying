#!/usr/bin/env python3
"""Bounded HP-curve search and independent pure-engine validation; no DB/network."""
from pathlib import Path
import argparse
import hashlib
import itertools
import json
import numpy as np
import pandas as pd
from stat_bonus_balance import OUT, ROOT, CLASSES, bonuses

KEYS=['class','seed_index','level']
GAPS=[4.,8.,10.,12.,24.]
SESSIONS=[1.,5.,12.]
LABELS=dict(zip(CLASSES,['전사','도적','레인저','마법사','성직자','팔라딘']))
CAP_TARGETS=[('cap_h',['WARRIOR'],10),('raw_proc_pct',['MAGE','CLERIC'],30),
             ('search_s',['ROGUE','RANGER'],4),('sale_bonus_pct',['PALADIN'],20)]


def load(name,seeds):
    d=pd.read_csv(OUT/name).sort_values(KEYS)
    assert len(d)==6*len(seeds)*100 and not d.isna().any().any()
    assert set(d.seed_index)==set(seeds) and set(d['class'])==set(CLASSES)
    assert not d.duplicated(KEYS).any()
    assert (d.groupby(['class','seed_index'])['level'].count()==100).all()
    for f in ['level','seconds','hp','mp','dex','cha','kills','sale_gold']:
        assert (d.groupby(['class','seed_index'])[f].diff().dropna()>=0).all(),f
    order=pd.MultiIndex.from_product([CLASSES,seeds,range(1,101)],names=KEYS)
    return d.set_index(KEYS).reindex(order).reset_index()


def calendar(d,threshold=6000.,power=1.,amplitude=2.):
    n=d.seed_index.nunique()
    def cube(f): return d[f].to_numpy().reshape(6,n,100)
    hp=cube('hp')
    cap=8+amplitude*np.minimum(hp/threshold,1.)**power
    dt=np.diff(cube('seconds'),axis=2,prepend=0.)
    day_cubes=[]; low_cubes=[]; high_cubes=[]
    for gap,session in itertools.product(GAPS,SESSIONS):
        foreground=session/60
        bank=cap*min(session/12,1.)
        inverse=(gap+foreground)/(np.minimum(gap,bank)+foreground)
        left=np.concatenate([inverse[:,:,:1],inverse[:,:,:-1]],axis=2)
        low=np.cumsum(dt*inverse,axis=2)/86400
        high=np.cumsum(dt*left,axis=2)/86400
        assert np.all(low<=high+1e-9)
        day_cubes.append((low+high)/2)
        low_cubes.append(low);high_cubes.append(high)
    days=np.stack(day_cubes,axis=3)
    lows=np.stack(low_cubes,axis=3);highs=np.stack(high_cubes,axis=3)
    pooled=days.mean(axis=1)
    valid=pooled[:,1:,:]
    progress_gap=(valid.max(axis=0)/valid.min(axis=0)-1)*100
    sale=cube('sale_gold').mean(axis=1)[:,1:,None]/valid
    sale_gap=(sale.max(axis=0)/sale.min(axis=0)-1)*100
    return dict(days=days,lows=lows,highs=highs,pooled=pooled,cap=cap,
                progress_gap=progress_gap,sale_gap=sale_gap)


def screen():
    d=load('level100_caps_dense.csv',[2,3,4,5])
    rows=[]
    for threshold,power in itertools.product(range(5000,6251,250),np.round(np.arange(.5,2.001,.1),2)):
        c=calendar(d,threshold,power)
        cap=c['cap'];late_bonus=(cap[:,:,-1]-8)*60
        # Design guardrails, not inferred player preferences: retain a visible
        # off-stat benefit without erasing the specialist's two-hour identity.
        caps_ok=np.allclose(cap[0,:,-1],10) and (cap[1:,:,-1]<10).all()
        off_ok=late_bonus[1:].min()>=30 and late_bonus[1:].max()<=75
        early_ok=(cap[0,:,19].mean()-8)*60>=3 and (cap[0,:,49].mean()-8)*60>=15
        rows.append(dict(hp_k=int(threshold),hp_power=float(power),
            feasible=bool(caps_ok and off_ok and early_ok),
            max_growth_gap_pct=float(c['progress_gap'][18:,:].max()),
            max_sale_gap_pct=float(c['sale_gap'][18:,:].max()),
            level100_gap12=float(c['progress_gap'][-1,list(itertools.product(GAPS,SESSIONS)).index((12.,12.))]),
            off_bonus_min_minutes=float(late_bonus[1:].min()),off_bonus_max_minutes=float(late_bonus[1:].max()),
            warrior_level20_minutes=float((cap[0,:,19].mean()-8)*60),
            warrior_level50_minutes=float((cap[0,:,49].mean()-8)*60)))
    grid=pd.DataFrame(rows).sort_values(['max_growth_gap_pct','hp_k','hp_power'])
    grid.to_csv(OUT/'ten_hour_candidate_grid.csv',index=False)
    feasible=grid[grid.feasible]
    assert len(feasible)>0
    best=feasible.iloc[0]
    near=feasible[feasible.max_growth_gap_pct<=best.max_growth_gap_pct+.25].copy()
    # Prefer less complexity if its worst gap is within 0.25 percentage points.
    near['power_complexity']=(near.hp_power!=1).astype(int)
    near['threshold_change']=abs(near.hp_k-6000)
    chosen=near.sort_values(['power_complexity','threshold_change','max_growth_gap_pct']).iloc[0]
    p=json.loads((OUT/'ten_hour_parameters.json').read_text())
    p.update(hp_k=float(chosen.hp_k),hp_power=float(chosen.hp_power))
    (OUT/'ten_hour_selected_parameters.json').write_text(json.dumps(p,indent=2)+'\n')
    summary=dict(training_seeds=[2,3,4,5],candidate_count=len(grid),feasible_count=len(feasible),
        evaluated_levels='20..100',comparison='max/min of class-pooled equal-level completion rates',
        cap_and_identity_guardrails='HP cap 10h; non-specialist bonus 30..75m at Lv100; warrior mean bonus >=3m at Lv20 and >=15m at Lv50',
        simplest_within_best_pp=.25,best=best.to_dict(),selected=chosen.drop(['power_complexity','threshold_change']).to_dict())
    (OUT/'ten_hour_selection.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2))


def validate():
    p=json.loads((OUT/'ten_hour_selected_parameters.json').read_text())
    d=load('ten_hour_independent_dense.csv',list(range(6,14)))
    train=load('level100_caps_dense.csv',[2,3,4,5])
    variants={'previous_12h':(6000,1,4),'linear_10h':(6000,1,2),
              'recommended_10h':(p['hp_k'],p['hp_power'],2)}
    metrics=[];effects=[];curves=[];details=[];full={};caps_results={}
    for cohort,data in [('calibration',train),('independent',d)]:
        for name,args in variants.items():
            c=calendar(data,*args)
            full[(cohort,name)]=c
            for j,(gap,session) in enumerate(itertools.product(GAPS,SESSIONS)):
                for level in range(20,101):
                    metrics.append(dict(cohort=cohort,variant=name,level=level,gap_h=gap,session_min=session,
                        progress_gap_pct=c['progress_gap'][level-2,j],sale_gap_pct=c['sale_gap'][level-2,j]))
                    for i,cls in enumerate(CLASSES):
                        details.append(dict(cohort=cohort,variant=name,level=level,gap_h=gap,session_min=session,
                            hero_class=cls,calendar_days=c['pooled'][i,level-1,j]))
    metrics=pd.DataFrame(metrics);metrics.to_csv(OUT/'ten_hour_gaps.csv',index=False)
    details=pd.DataFrame(details);details.to_csv(OUT/'ten_hour_class_days.csv',index=False)
    c=full[('independent','recommended_10h')]
    from validate_stat_bonus_balance import calendar_scenarios
    reference=calendar_scenarios(d,p,'selected')
    for _,r in reference[reference.level==100].iterrows():
        i=CLASSES.index(r.hero_class);k=int(r.seed_index)-6
        j=list(itertools.product(GAPS,SESSIONS)).index((r.gap_h,r.session_min))
        assert np.isclose(r.calendar_days,c['days'][i,k,-1,j],rtol=1e-12)
    x=d.copy()
    x['cap_h']=c['cap'].reshape(-1)
    _,prob,dex,cha=bonuses(x,p)
    x['raw_proc_pct']=prob*100;x['search_s']=5*(1-dex);x['sale_bonus_pct']=cha*100
    fields=['cap_h','raw_proc_pct','search_s','sale_bonus_pct']
    effects=x.groupby(['level','class'])[fields].agg(['mean','min','max']).round(6)
    effects.to_csv(OUT/'ten_hour_class_effects.csv')
    raw_at100=x[x.level==100]
    bounds={}
    for field,classes,cap in CAP_TARGETS:
        main=raw_at100['class'].isin(classes)
        reached=np.isclose(raw_at100[field],cap)
        caps_results[field]=dict(specialist_at_cap=int((main&reached).sum()),specialist_count=int(main.sum()),
             other_at_cap=int((~main&reached).sum()),other_count=int((~main).sum()))
        assert not (reached&~main).any()
    # Independent bootstrap resamples paired seed indexes across all six classes.
    # It describes this simulation seed sample, not population player behavior.
    rng=np.random.default_rng(3081006)
    indices=rng.integers(0,8,(4000,8))
    bootstrap=[]
    for gap in [8.,12.,24.]:
        j=list(itertools.product(GAPS,SESSIONS)).index((gap,12.))
        a=c['days'][:,:,-1,j]
        sampled=a[:,indices].mean(axis=2)
        gap_samples=(sampled.max(axis=0)/sampled.min(axis=0)-1)*100
        lo,hi=np.quantile(gap_samples,[.025,.975])
        bootstrap.append(dict(gap_h=gap,low_pct=float(lo),high_pct=float(hi)))
    cap_boundary=np.unique(np.r_[0,1,p['hp_k']-1,p['hp_k'],p['hp_k']+1,np.geomspace(1,1e18,300)])
    boundary=8+2*np.minimum(cap_boundary/p['hp_k'],1)**p['hp_power']
    assert boundary.min()==8 and boundary.max()==10 and (np.diff(boundary)>=0).all()
    for name,digest in json.loads((OUT/'engine_source_sha256.json').read_text()).items():
        if name.startswith('app/'): assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest
    late=metrics[(metrics.cohort=='independent')&(metrics.level==100)&(metrics.session_min==12)&metrics.gap_h.isin([8,12,24])]
    max_bounds=(c['highs'][:,:,-1,:]/c['lows'][:,:,-1,:]-1)*100
    hp_minutes=(c['cap']-8)*60
    # Report which class is fastest; do not mistake a spread for a specific class advantage.
    fastest=[]
    for gap in [8.,12.]:
        j=list(itertools.product(GAPS,SESSIONS)).index((gap,12.))
        days=c['pooled'][:,-1,j]
        fastest.append(dict(gap_h=gap,fastest=CLASSES[int(np.argmin(days))],slowest=CLASSES[int(np.argmax(days))]))
    summary=dict(selected_parameters=p,independent_seeds=list(range(6,14)),independent_games=48,
        cap_checks=caps_results,level100_effects=raw_at100.groupby('class')[fields].mean().round(6).to_dict('index'),
        level100_gaps=late.to_dict('records'),bootstrap_gap_intervals=bootstrap,fastest_slowest=fastest,
        max_gap20_to100=metrics[metrics.cohort=='independent'].groupby('variant').progress_gap_pct.max().to_dict(),
        level100_calendar_bound_max_pct=float(max_bounds.max()),app_source_hash_unchanged=True,
        calendar_independent_implementation_match=True,
        independent_hp_guardrails=dict(other_bonus_min_minutes=float(hp_minutes[1:,:,-1].min()),
          other_bonus_max_minutes=float(hp_minutes[1:,:,-1].max()),
          paths_exceeding_75m=int((hp_minutes[1:,:,-1]>75).sum()),
          warrior_level20_minutes=float(hp_minutes[0,:,19].mean()),
          warrior_level50_minutes=float(hp_minutes[0,:,49].mean())),
        caveats=['Parameter search changed HP curve only; other three cap curves retained',
          'HP calendar benefits are time-bank conversions, not logged player sessions',
          'Paired seed bootstrap is simulation uncertainty, not a player-population confidence interval',
          'Specialist-only reaching cap verified in samples, not all possible RNG outcomes'])
    (OUT/'ten_hour_validation.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2))
    return metrics,effects,summary


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['screen','validate'])
    args=parser.parse_args()
    if args.mode=='screen':screen()
    else:validate()
