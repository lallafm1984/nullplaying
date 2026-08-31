#!/usr/bin/env python3
"""Reproducible planning analysis. All inputs are local synthetic engine replays."""
from datetime import datetime
import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import uuid
import numpy as np
import pandas as pd
import run_progression_bonus_probe as runner
from run_con_mental_probe import OUT
from progression_bonus_review import calendar,CLASSES,LABELS,BASELINE
from final_class_bonus_review import ordered

FIELDS=['cap_h','raw_proc_pct','search_s','sale_bonus_pct','bag','con','mental']

def effects(d,con_curve='ramp'):
    x=d.copy();t=((x.level-1)/99).clip(0,1)
    x['mental']=.5*x['int']+.5*x.wis
    con=(x.con/150).clip(0,1)
    h=con**.6*(.15+.85*t**1.2) if con_curve=='ramp' else np.minimum(con**.8,.02+.98*t**1.8)
    x['cap_h']=8+2*h
    x['raw_proc_pct']=np.minimum(np.where(x['class']=='MAGE',35,30),x.proc_pct+15*(x.mental/180).clip(0,1)**1.2)
    envelope=.12+.88*t**1.4
    x['search_s']=5-np.minimum((x.dex/150).clip(0,1),envelope)
    x['sale_bonus_pct']=20*np.minimum((x.cha/150).clip(0,1),envelope)
    return x

def verify(d,seeds):
    ordered(d[d.level<=100],seeds)
    x=effects(d)
    for a,b in [('cap_h','bonus_cap_h'),('raw_proc_pct','bonus_proc_pct'),('sale_bonus_pct','bonus_sale_pct')]:
        assert np.allclose(x[a],d[b],atol=1e-10,rtol=0),(a,float(abs(x[a]-d[b]).max()))
    assert np.allclose(5-np.floor((5-x.search_s)*1000+.5)/1000,d.bonus_search_s,atol=1e-12)
    for f in ['cap_h','raw_proc_pct','sale_bonus_pct']:
        assert (x.groupby(['class','seed_index'])[f].diff().dropna()>=-1e-10).all()
    assert x.cap_h.between(8,10).all() and x.search_s.between(4,5).all()
    assert x.sale_bonus_pct.between(0,20).all()
    assert (x.raw_proc_pct<=np.where(x['class']=='MAGE',35,30)+1e-10).all()
    y=x[x.level<100]
    assert (y.cap_h<10).all() and (y.search_s>4).all() and (y.sale_bonus_pct<20).all()
    return x

def dump(name,value):
    (OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')

def summarize(name,seeds):
    path=OUT/name
    receipt=json.loads((OUT/(name+'.execution.json')).read_text())
    assert hashlib.sha256(path.read_bytes()).hexdigest()==receipt['output_sha256']
    d=pd.read_csv(path);x=verify(d,seeds)
    z=ordered(x[x.level<=100],seeds)
    scenario,detail=calendar(z)
    scenario.to_csv(OUT/(name+'.scenarios.csv'),index=False)
    detail.to_csv(OUT/(name+'.calendar.csv'),index=False)
    alt_scenario,_=calendar(ordered(effects(d[d.level<=100],'min'),seeds))
    hits=[]
    for cls,field,target in [('WARRIOR','cap_h',10),('MAGE','raw_proc_pct',35),('ROGUE','search_s',4),
                             ('RANGER','search_s',4),('CLERIC','sale_bonus_pct',20),('PALADIN','sale_bonus_pct',20)]:
        y=x[x['class']==cls]
        first=y[np.isclose(y[field],target,atol=1e-10,rtol=0)].groupby('seed_index')['level'].min()
        hits.append(dict(hero_class=cls,samples=len(seeds),reached_by100=int((first<=100).sum()),
            reached_by_end=len(first),max_level=int(y.level.max()),
            first_min=int(first.min()) if len(first) else None,first_max=int(first.max()) if len(first) else None,
            first_by_seed={str(k):int(v) for k,v in first.items()}))
    return dict(file=name,rows=len(d),games=int(d.groupby(['class','seed_index']).ngroups),cap_hits=hits,
        max_growth_spread_pct=float(scenario.growth_spread_pct.max()),max_sale_spread_pct=float(scenario.sale_spread_pct.max()),
        alternate_min_curve=dict(max_growth_spread_pct=float(alt_scenario.growth_spread_pct.max()),
            max_sale_spread_pct=float(alt_scenario.sale_spread_pct.max())),
        key_scenarios=scenario[(scenario.level==100)&scenario.gap_h.isin([8,12])&scenario.session_min.isin([1,12])].to_dict('records'))

def boundaries():
    runner.OUT=OUT;runner.HOOK=runner.ROOT/'tools/analysis/ConMentalBonusHooks.kt'
    sandbox,jar,runtime=runner.prepare()
    compiler=[runner.base.jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.20'),
        runner.base.jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.20'),runner.base.jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
        runner.base.jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
        runner.base.jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),runner.base.jar('org.jetbrains','annotations','13.0')]
    target=OUT/'boundary-checks.jar'
    subprocess.run(sandbox+[str(runner.base.JAVA),'-Xmx384m','-cp',':'.join(compiler),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17',
        '-classpath',':'.join([str(jar),*runtime]),'-d',str(target),str(runner.ROOT/'tools/analysis/ConMentalBonusChecks.kt')],check=True)
    result=subprocess.run(sandbox+[str(runner.base.JAVA),'-Xmx256m','-cp',':'.join([str(target),str(jar),*runtime]),
        'com.nullplaying.engine.ConMentalBonusChecks'],capture_output=True,text=True,check=True)
    (OUT/'boundary-checks.log').write_text(result.stdout+result.stderr)
    return result.stdout.strip()

def main():
    a=summarize('matched.csv',list(range(14,30)));b=summarize('heldout.csv',list(range(30,38)))
    old=pd.read_csv(BASELINE);matched=pd.read_csv(OUT/'matched.csv')
    keys=['class','seed_index','level'];stats=keys+['str','con','dex','int','wis','cha','hp','mp','bag','proc_pct','tales']
    pd.testing.assert_frame_equal(old[stats].sort_values(keys).reset_index(drop=True),matched[stats].sort_values(keys).reset_index(drop=True),check_exact=True)
    all_rows=pd.concat([matched,pd.read_csv(OUT/'heldout.csv')],ignore_index=True)
    x=effects(all_rows);z=x[x.level<=100]
    means=z.groupby(['level','class'])[FIELDS].mean()
    z.groupby(['level','class'])[FIELDS].agg(['mean','min','max']).to_csv(OUT/'all-level-effects.csv')
    rows=[]
    for c in CLASSES:
        prev=None
        for l in [1,*range(10,101,10)]:
            r=dict(level=l,hero_class=c,**{f:float(means.loc[(l,c),f]) for f in FIELDS})
            r['time_bonus_min']=(r['cap_h']-8)*60
            r['time_delta_min']=None if prev is None else r['time_bonus_min']-prev['time_bonus_min']
            r['proc_delta_pp']=None if prev is None else r['raw_proc_pct']-prev['raw_proc_pct']
            rows.append(r);prev=r
    pd.DataFrame(rows).to_csv(OUT/'level-checkpoints-and-deltas.csv',index=False)
    key_summaries=[]
    for name in ['matched.csv','heldout.csv']:
        d=pd.read_csv(OUT/(name+'.calendar.csv'))
        for gap in [8,12]:
            q=d[(d.level==100)&(d.gap_h==gap)&(d.session_min==12)].set_index('hero_class')
            key_summaries.append(dict(cohort=name,gap_h=gap,
                classes=[dict(hero_class=c,days=float(q.loc[c,'calendar_days']),
                    extra_time_vs_fastest_pct=float((q.loc[c,'calendar_days']/q.calendar_days.min()-1)*100)) for c in CLASSES]))
    output=dict(formula='CON ramp, mental = 0.5 INT + 0.5 WIS, shared +15pp curve, Mage35/non-Mage30 caps',
        matched=a,heldout=b,level100={c:{f:float(means.loc[(100,c),f]) for f in FIELDS} for c in CLASSES},
        checkpoints=rows,key_summaries=key_summaries,boundary_checks=boundaries(),
        stat_and_P0_identity_rows=len(matched),comparison_columns=stats,
        safety=dict(network='OS denied',database_files='OS denied',device_registration=False,app_start=False,app_sources_unchanged=True),
        caveats=['The 50:50 weights affect the added bonus, not the class-based P0.',
            'Calendar simulation is repeated-charge time-bank conversion, not actual session replay.',
            'Synthetic path cap frequencies are not population probabilities.',
            'A stat formula does not impose an exclusive class gate on CON/DEX/CHA caps.',
            'Only additional bonus mapping changes: HP/MP growth and combat resource roles are preserved.'])
    dump('FINAL_SUMMARY.json',output)
    print(json.dumps({k:output[k] for k in ['level100','boundary_checks','matched','heldout']},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
