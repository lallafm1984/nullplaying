#!/usr/bin/env python3
"""Final held-out class benefits, growth, economy and boundary validation."""
from pathlib import Path
import hashlib
import itertools
import json
import numpy as np
import pandas as pd
import ten_hour_balance as bank

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'docs/audits/2026-08-31-stat-bonus-balance/final-all-classes'
CLASSES=bank.CLASSES
LABELS=dict(zip(CLASSES,['전사','도적','레인저','마법사','성직자','팔라딘']))
PAIRS={'WARRIOR':('str','con'),'ROGUE':('dex','str'),'RANGER':('dex','wis'),
       'MAGE':('int','wis'),'CLERIC':('wis','cha'),'PALADIN':('str','cha')}
SEEDS=list(range(14,30))
GAPS=[4.,6.,8.,9.,10.,12.,16.,24.]
SESSIONS=[1.,5.,12.]
FIELDS=['cap_h','raw_proc_pct','search_s','sale_bonus_pct','bag','hp','mp']


def effects(d):
    x=d.copy()
    x['cap_h']=8+2*np.minimum(x.hp/6000,1)**.8
    extra=np.where(x['class']=='MAGE',15.,10.)
    x['raw_proc_pct']=np.minimum(20+extra,x.proc_pct+extra*np.minimum(x.mp/6000,1))
    x['effective_proc_pct']=x.raw_proc_pct/(1-(1-x.raw_proc_pct/100)**16)
    x['search_s']=5-np.minimum(x.dex/150,1)
    x['sale_bonus_pct']=20*np.minimum(x.cha/150,1)
    return x


def ordered(d,seeds):
    assert len(d)==6*len(seeds)*100 and not d.duplicated(bank.KEYS).any()
    assert set(d.seed_index)==set(seeds) and set(d['class'])==set(CLASSES)
    index=pd.MultiIndex.from_product([CLASSES,seeds,range(1,101)],names=bank.KEYS)
    d=d.set_index(bank.KEYS).reindex(index).reset_index()
    assert not d.isna().any().any()
    for f in ['seconds','kills','sale_gold','hp','mp','tales','attacks','casts']:
        assert (d.groupby(['class','seed_index'])[f].diff().dropna()>=0).all(),f
    assert (d[d.level==100].tales==129).all()
    base=15+(d.level-1)*3//5
    assert np.array_equal(d.bag,base+np.minimum(d.str//2,base-1))
    for cls,(a,b) in PAIRS.items():
        x=d[d['class']==cls]
        calculated=(8+((7*x[a]+3*x[b])//10)//6).clip(8,20)
        assert np.array_equal(x.proc_pct,calculated),cls
    return d


def review():
    for name,digest in json.loads((OUT/'engine_source_sha256.json').read_text()).items():
        if name.startswith('app/'):
            assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,name
    d=ordered(pd.concat([pd.read_csv(OUT/'mage-heldout.csv'),pd.read_csv(OUT/'others-heldout.csv')]),SEEDS)
    d.to_csv(OUT/'all-heldout.csv',index=False)
    x=effects(d)
    assert x.cap_h.between(8,10).all() and x.search_s.between(4,5).all()
    assert x.sale_bonus_pct.between(0,20).all() and x.raw_proc_pct.between(8,35).all()
    assert (x.loc[x['class']!='MAGE','raw_proc_pct']<=30).all()
    stats=x.groupby(['level','class'])[FIELDS].agg(['mean','min','max'])
    stats.to_csv(OUT/'benefits-by-level.csv')
    cap_conditions={'hp_10h':x.cap_h>=10-1e-9,'mage_35':x.raw_proc_pct>=35-1e-9,
                    'search_4s':x.search_s<=4+1e-9,'sale_20pct':x.sale_bonus_pct>=20-1e-9,
                    'bag_max':x.bag==2*(15+(x.level-1)*3//5)-1}
    cap_by_class=[]
    for cls in CLASSES:
        mask=(x['class']==cls)&(x.level==100)
        cap_by_class.append(dict(hero_class=cls,samples=int(mask.sum()),
            **{key:int(cond[mask].sum()) for key,cond in cap_conditions.items()}))
    bank.GAPS=GAPS;bank.SESSIONS=SESSIONS
    c=bank.calendar(d,6000,.8,2)
    rows=[];scenario_summary=[]
    for j,(gap,session) in enumerate(itertools.product(GAPS,SESSIONS)):
        for level in range(20,101):
            times=c['pooled'][:,level-1,j]
            gold=d[d.level==level].groupby('class').sale_gold.mean().reindex(CLASSES).to_numpy()/times
            if level==100:
                scenario_summary.append(dict(gap_h=gap,session_min=session,
                    growth_spread_pct=float((times.max()/times.min()-1)*100),
                    sale_spread_pct=float((gold.max()/gold.min()-1)*100),
                    fastest=CLASSES[int(times.argmin())],slowest=CLASSES[int(times.argmax())],
                    highest_sale=CLASSES[int(gold.argmax())],lowest_sale=CLASSES[int(gold.argmin())]))
            for i,cls in enumerate(CLASSES):
                rows.append(dict(level=level,gap_h=gap,session_min=session,hero_class=cls,
                    calendar_days=float(times[i]),sale_gold_per_day=float(gold[i]),
                    growth_advantage_pct=float((times.max()/times[i]-1)*100),
                    sale_advantage_pct=float((gold[i]/gold.min()-1)*100)))
    comparisons=pd.DataFrame(rows)
    comparisons.to_csv(OUT/'scenario-comparison.csv',index=False)
    # Independently recompute every class/seed at Lv100, 12h gap, full charge.
    j=list(itertools.product(GAPS,SESSIONS)).index((12.,12.))
    for i,cls in enumerate(CLASSES):
        for k,seed in enumerate(SEEDS):
            z=d[(d['class']==cls)&(d.seed_index==seed)]
            hp=z.hp.to_numpy();t=z.seconds.to_numpy()
            inverse=12.2/(8+2*np.minimum(hp/6000,1)**.8+.2)
            dt=np.diff(t,prepend=0)
            independent=sum(float(dt[n])*(float(inverse[n])+float(inverse[max(0,n-1)]))/2 for n in range(100))/86400
            assert np.isclose(independent,c['days'][i,k,-1,j],rtol=1e-12)
    late=[]
    for cls in CLASSES:
        z=d[d['class']==cls].set_index(['seed_index','level'])[['seconds','kills','sale_gold','attacks','casts']]
        change=z.xs(100,level='level')-z.xs(90,level='level')
        summed=change.sum()
        late.append(dict(hero_class=cls,kills_per_hour=float(summed.kills/summed.seconds*3600),
            sale_gold_per_hour=float(summed.sale_gold/summed.seconds*3600),
            casts_per_hour=float(summed.casts/summed.seconds*3600),
            actual_cast_pct=float(summed.casts/summed.attacks*100)))
    tails=pd.read_csv(OUT/'growth-tails-iid.csv')
    assert len(tails)==60_000 and not tails.duplicated(['class','seed_index']).any()
    tails_rows=[]
    for cls,z in tails.groupby('class'):
        tails_rows.append(dict(hero_class=cls,samples=len(z),
            hp_cap_pct=float((z.hp>=6000).mean()*100),mp_threshold_pct=float((z.mp>=6000).mean()*100),
            dex_cap_pct=float((z.dex>=150).mean()*100),cha_cap_pct=float((z.cha>=150).mean()*100),
            hp_min=int(z.hp.min()),hp_max=int(z.hp.max()),mp_min=int(z.mp.min()),mp_max=int(z.mp.max())))
    # HP boundary/monotonic checks supplement the exact Kotlin hook checks.
    values=np.unique(np.r_[0,1,149,150,5999,6000,6001,np.geomspace(1,2**63-1,1000)])
    hp=8+2*np.minimum(values/6000,1)**.8
    assert hp.min()==8 and hp.max()==10 and (np.diff(hp)>=0).all()
    # Prior eight-seed cohort is used only as an out-of-sample stability check.
    earlier=pd.read_csv(OUT.parent/'cleric-wis-cha/ten_hour_independent_dense.csv')
    earlier=pd.concat([earlier[earlier['class']!='MAGE'],pd.read_csv(OUT.parent/'mage-35-review/mage35_dense.csv')])
    earlier=ordered(earlier,list(range(6,14)))
    prev=bank.calendar(earlier,6000,.8,2)
    stability=[]
    for gap in [8.,12.]:
        j=list(itertools.product(GAPS,SESSIONS)).index((gap,12.))
        for cohort,cc in [('prior8',prev),('fresh16',c)]:
            times=cc['pooled'][:,-1,j]
            stability.append(dict(cohort=cohort,gap_h=gap,growth_spread_pct=float((times.max()/times.min()-1)*100),fastest=CLASSES[int(times.argmin())]))
    # Seed-sample uncertainty only. Not player-population confidence intervals.
    uncertainty=[];rng=np.random.default_rng(35083116);indices=rng.integers(0,16,(4000,16))
    for gap in [8.,12.]:
        j=list(itertools.product(GAPS,SESSIONS)).index((gap,12.))
        sampled=c['days'][:,:,-1,j][:,indices].mean(axis=2)
        distribution=(sampled.max(axis=0)/sampled.min(axis=0)-1)*100
        low,high=np.quantile(distribution,[.025,.975])
        uncertainty.append(dict(gap_h=gap,low_pct=float(low),high_pct=float(high)))
    result=dict(fresh_games=96,seeds=SEEDS,level100_effects=x[x.level==100].groupby('class')[FIELDS].mean().to_dict('index'),
        level100_ranges=x[x.level==100].groupby('class')[FIELDS].agg(['min','max']).to_dict('index'),
        cap_by_class=cap_by_class,scenario_summary=scenario_summary,late_game=late,iid_growth_tails=tails_rows,
        max_growth_spread20_to100_pct=float(c['progress_gap'][18:,:].max()),
        max_sale_spread20_to100_pct=float(c['sale_gap'][18:,:].max()),
        calendar_bound100_max_pct=float(((c['highs'][:,:,-1,:]/c['lows'][:,:,-1,:]-1)*100).max()),
        stability=stability,paired_seed_quantiles=uncertainty,app_source_unchanged=True,
        bag_formula_match=True,raw_proc_formula_match=True,calendar_independent_check=True,
        excluded=['growth-tails.csv: contiguous four-draw growth loop aliases low RNG bits; not a valid combat-growth sample'],
        caveats=['96 full combat trajectories are the primary evidence.',
            '60000 growth-only IID event-seed samples are supplemental sensitivity, not full gameplay or population probabilities.',
            'No strict class lock exists for HP/DEX/CHA; cap exclusivity is sample-supported, not a universal guarantee.',
            'Calendar time is a repeated charging approximation, not actual user-session replay.',
            'Sale gold per calendar day is cumulative to the target level, not total wealth or fixed-Lv100 income.',
            'Bonus planning only: no game code, database, device, or release changed in this review.'])
    # JSON keys cannot be tuple column names.
    result['level100_ranges']={cls:{f'{field}_{agg}':float(value) for (field,agg),value in stats.items()}
        for cls,stats in result['level100_ranges'].items()}
    (OUT/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    return x,comparisons,result


if __name__=='__main__':
    _,_,s=review()
    for name in ['level100_effects','cap_by_class','late_game','stability','max_growth_spread20_to100_pct','max_sale_spread20_to100_pct','calendar_bound100_max_pct','iid_growth_tails']:
        print(name,json.dumps(s[name],ensure_ascii=False,indent=2))
