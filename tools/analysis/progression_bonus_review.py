#!/usr/bin/env python3
"""Planning arithmetic and replay review; deliberately no DB or network imports."""
from pathlib import Path
import itertools
import json
import hashlib
import numpy as np
import pandas as pd
from final_class_bonus_review import CLASSES, LABELS, ordered, effects as old_effects
from run_progression_bonus_probe import OUT, ROOT

BASELINE = ROOT/'docs/audits/2026-08-31-stat-bonus-balance/final-all-classes/all-heldout.csv'
FIELDS = ['cap_h','raw_proc_pct','search_s','sale_bonus_pct','bag','hp','mp']
GAPS = [4,6,8,9,10,12,16,24]
SESSIONS = [1,5,12]


def effects(d, mode='level_envelope', mage_threshold=8000.):
    x = d.copy()
    t = ((x.level-1)/99).clip(0,1)
    r_hp = (x.hp/6000).clip(0,1)
    r_dex = (x.dex/150).clip(0,1)
    r_cha = (x.cha/150).clip(0,1)
    mage = x['class']=='MAGE'
    r_mp = (x.mp/np.where(mage & (mode!='baseline'),mage_threshold,6000)).clip(0,1)
    extra = np.where(mage,15.,10.)
    h = r_hp**.8
    if mode == 'stat_power':
        h=r_hp**1.15; r_dex=r_dex**1.35; r_cha=r_cha**1.35; r_mp=r_mp**1.15
    elif mode != 'baseline':
        r_mp=np.where(mage,r_mp**1.15,r_mp)
    if mode == 'level_ramp':
        ramp=.4+.6*t**1.3
        h*=ramp; r_mp*=ramp; r_dex*=ramp; r_cha*=ramp
    if mode == 'level_envelope':
        h=np.minimum(h,.02+.98*t**1.8)
        ceiling=.12+.88*t**1.4
        r_dex=np.minimum(r_dex,ceiling);r_cha=np.minimum(r_cha,ceiling)
    x['cap_h']=8+2*h
    x['raw_proc_pct']=np.minimum(20+extra,x.proc_pct+extra*r_mp)
    x['search_s']=5-r_dex
    x['sale_bonus_pct']=20*r_cha
    return x


def caps(level):
    t=np.clip((np.asarray(level)-1)/99,0,1)
    return 8+2*(.02+.98*t**1.8),5-(.12+.88*t**1.4),20*(.12+.88*t**1.4)


def check_replay(d, seeds, mode='level_envelope', mage_threshold=8000.):
    d=ordered(d[d.level<=100].copy(),seeds)
    x=effects(d,mode,mage_threshold)
    for a,b in [('cap_h','bonus_cap_h'),('raw_proc_pct','bonus_proc_pct'),('sale_bonus_pct','bonus_sale_pct')]:
        assert np.allclose(x[a],d[b],atol=1e-10,rtol=0),(a,float(abs(x[a]-d[b]).max()))
    assert np.allclose(5-np.floor((5-x.search_s)*1000+.5)/1000,d.bonus_search_s,atol=1e-12)
    for field in ['cap_h','raw_proc_pct','sale_bonus_pct']:
        assert (x.groupby(['class','seed_index'])[field].diff().dropna()>=-1e-10).all(),field
    assert x.cap_h.between(8,10).all() and x.search_s.between(4,5).all()
    assert x.sale_bonus_pct.between(0,20).all()
    assert (x.loc[x['class']!='MAGE','raw_proc_pct']<=30).all()
    assert (x.raw_proc_pct<=35).all()
    if mode=='level_envelope':
        y=x[x.level<100]
        assert (y.cap_h<10).all() and (y.search_s>4).all() and (y.sale_bonus_pct<20).all()
    return x


def calendar(x):
    n=x.seed_index.nunique()
    def cube(field):return x[field].to_numpy().reshape(6,n,100)
    cap=cube('cap_h');seconds=cube('seconds');gold=cube('sale_gold')
    dt=np.diff(seconds,axis=2,prepend=0)
    results=[];rows=[]
    for gap,session in itertools.product(GAPS,SESSIONS):
        foreground=session/60
        inverse=(gap+foreground)/(np.minimum(gap,cap*min(session/12,1))+foreground)
        left=np.concatenate([inverse[:,:,:1],inverse[:,:,:-1]],axis=2)
        low=np.cumsum(dt*inverse,axis=2)/86400;high=np.cumsum(dt*left,axis=2)/86400
        assert np.all(low<=high+1e-9)
        days=(low+high)/2;mean=days.mean(axis=1)
        income=gold.mean(axis=1)/np.where(mean>0,mean,np.nan)
        for lvl in range(20,101):
            a=mean[:,lvl-1];b=income[:,lvl-1]
            results.append(dict(level=lvl,gap_h=gap,session_min=session,
                growth_spread_pct=float((a.max()/a.min()-1)*100),
                sale_spread_pct=float((b.max()/b.min()-1)*100),
                fastest=CLASSES[int(a.argmin())],slowest=CLASSES[int(a.argmax())],
                highest_sale=CLASSES[int(b.argmax())],lowest_sale=CLASSES[int(b.argmin())]))
            for i,cls in enumerate(CLASSES):
                rows.append(dict(level=lvl,gap_h=gap,session_min=session,hero_class=cls,
                    calendar_days=float(a[i]),sale_gold_per_day=float(b[i])))
    return pd.DataFrame(results),pd.DataFrame(rows)


def projection_screen():
    d=ordered(pd.read_csv(BASELINE),list(range(14,30)))
    rows=[]
    for mode in ['baseline','stat_power','level_ramp','level_envelope']:
        z=effects(d,mode)
        for level in [1,20,40,60,80,90,100]:
            for cls,g in z[z.level==level].groupby('class'):
                rows.append(dict(mode=mode,level=level,hero_class=cls,**{f:float(g[f].mean()) for f in FIELDS}))
    pd.DataFrame(rows).to_csv(OUT/'candidate-formula-projections.csv',index=False)
    trials=[]
    for threshold in [7200,7500,7800,8000,8500,9000]:
        for power in [1.,1.1,1.15,1.2,1.3]:
            z=d[(d['class']=='MAGE') & (d.level==100)]
            p=z.proc_pct+15*np.minimum(z.mp/threshold,1)**power
            trials.append(dict(mp_threshold=threshold,power=power,mean_pct=float(p.mean()),
                min_pct=float(p.min()),max_pct=float(p.max()),at_cap=int((p>=35-1e-9).sum()),samples=len(p)))
    pd.DataFrame(trials).to_csv(OUT/'mage-threshold-projections.csv',index=False)
    return trials


def review(name,seeds,mode='level_envelope',mage_threshold=8000.):
    d=pd.read_csv(OUT/name)
    x=check_replay(d,seeds,mode,mage_threshold)
    aggregates=x.groupby(['level','class'])[FIELDS].agg(['mean','min','max'])
    aggregates.to_csv(OUT/(name+'.effects.csv'))
    scenarios,details=calendar(x)
    scenarios.to_csv(OUT/(name+'.scenarios.csv'),index=False)
    details.to_csv(OUT/(name+'.calendar.csv'),index=False)
    means=x.groupby(['class','level'])[FIELDS].mean()
    hits=[]
    for cls,field,target in [('WARRIOR','cap_h',10),('MAGE','raw_proc_pct',35),
                            ('ROGUE','search_s',4),('RANGER','search_s',4),
                            ('CLERIC','sale_bonus_pct',20),('PALADIN','sale_bonus_pct',20)]:
        y=effects(d[d['class']==cls],mode,mage_threshold)
        first=y[np.isclose(y[field],target,atol=1e-10,rtol=0)].groupby('seed_index')['level'].min()
        hits.append(dict(hero_class=cls,samples=len(seeds),reached_by100=int((first<=100).sum()),
            reached_by_end=int(len(first)),max_level=int(y.level.max()),
            first_min=int(first.min()) if len(first) else None,first_max=int(first.max()) if len(first) else None,
            first_median=float(first.median()) if len(first) else None))
    summary=dict(file=name,rows=len(d),games=int(d.groupby(['class','seed_index']).ngroups),
        evaluated_levels='20..100',checked_hook_rows=len(x),seed_indices=seeds,
        level100=means.xs(100,level='level').to_dict('index'),
        level_checkpoints={str(lvl):means.xs(lvl,level='level').to_dict('index') for lvl in [1,20,40,60,80,90,100]},
        cap_hits=hits,max_growth_spread_pct=float(scenarios.growth_spread_pct.max()),
        max_sale_spread_pct=float(scenarios.sale_spread_pct.max()),
        key_scenarios=scenarios[(scenarios.level==100)&scenarios.gap_h.isin([8,12])&scenarios.session_min.isin([1,12])].to_dict('records'))
    (OUT/(name+'.summary.json')).write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    return summary


if __name__=='__main__':
    projection_screen()
    name='envelope-matched.csv'
    if (OUT/(name+'.execution.json')).exists():
        print(json.dumps(review(name,list(range(14,30))),ensure_ascii=False,indent=2))
