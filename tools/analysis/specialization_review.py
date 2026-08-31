#!/usr/bin/env python3
"""Review user-selected CON specialization plus reduced CHA sale cap."""
import argparse
import hashlib
import json
import subprocess
import numpy as np
import pandas as pd
from con_mental_review import OUT as PREVIOUS,effects as old_effects
from final_class_bonus_review import CLASSES,LABELS,ordered
from progression_bonus_review import calendar
import run_progression_bonus_probe as runner

OUT=PREVIOUS/'specialization-cha15'
DEX_FLOOR=4.0

def effects(d):
    x=old_effects(d);t=((x.level-1)/99).clip(0,1)
    x['cap_h']=8+2*(x.con/150).clip(0,1)**1.2*(.15+.85*t**1.2)
    x['sale_bonus_pct']*=.75
    x['search_s']=5-(5-DEX_FLOOR)*np.minimum((x.dex/150).clip(0,1),.12+.88*t**1.4)
    return x

def check(d,seeds):
    d=ordered(d,seeds);x=effects(d)
    for a,b in [('cap_h','bonus_cap_h'),('raw_proc_pct','bonus_proc_pct'),('sale_bonus_pct','bonus_sale_pct')]:
        assert np.allclose(x[a],d[b],atol=1e-10,rtol=0),a
    assert np.allclose(5-np.floor((5-x.search_s)*1000+.5)/1000,d.bonus_search_s,atol=1e-12)
    assert x.sale_bonus_pct.between(0,15).all() and x.cap_h.between(8,10).all()
    for f in ['cap_h','raw_proc_pct','sale_bonus_pct']:
        assert (x.groupby(['class','seed_index'])[f].diff().dropna()>=-1e-10).all()
    return x

def boundary():
    runner.OUT=OUT;runner.HOOK=runner.ROOT/'tools/analysis'/('SpecializationDexBonusHooks.kt' if DEX_FLOOR==3.5 else 'SpecializationBonusHooks.kt')
    sandbox,jar,runtime=runner.prepare()
    compiler=[runner.base.jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.20'),
        runner.base.jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.20'),runner.base.jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
        runner.base.jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
        runner.base.jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),runner.base.jar('org.jetbrains','annotations','13.0')]
    target=OUT/'boundary-checks.jar'
    subprocess.run(sandbox+[str(runner.base.JAVA),'-Xmx384m','-cp',':'.join(compiler),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17',
        '-classpath',':'.join([str(jar),*runtime]),'-d',str(target),str(runner.ROOT/'tools/analysis/SpecializationBonusChecks.kt')],check=True)
    r=subprocess.run(sandbox+[str(runner.base.JAVA),'-Xmx256m','-cp',':'.join([str(target),str(jar),*runtime]),
        'com.nullplaying.engine.SpecializationBonusChecks',str(DEX_FLOOR)],capture_output=True,text=True,check=True)
    (OUT/'boundary-checks.log').write_text(r.stdout+r.stderr)
    return r.stdout.strip()

def main():
    rows=[];summaries=[];keys=['class','seed_index','level']
    identity=keys+['str','con','dex','int','wis','cha','hp','mp','bag','proc_pct','tales']
    for name,seeds in [('matched.csv',list(range(14,30))),('heldout.csv',list(range(30,38)))]:
        receipt=json.loads((OUT/(name+'.execution.json')).read_text())
        assert hashlib.sha256((OUT/name).read_bytes()).hexdigest()==receipt['output_sha256']
        raw=pd.read_csv(OUT/name);x=check(raw,seeds)
        previous=pd.read_csv(PREVIOUS/name);previous=previous[previous.level<=100]
        pd.testing.assert_frame_equal(x[identity].sort_values(keys).reset_index(drop=True),previous[identity].sort_values(keys).reset_index(drop=True),check_exact=True)
        sc,detail=calendar(x)
        sc.to_csv(OUT/(name+'.scenarios.csv'),index=False)
        detail.to_csv(OUT/(name+'.calendar.csv'),index=False)
        summaries.append(dict(cohort=name,games=len(seeds)*6,max_growth_spread_pct=float(sc.growth_spread_pct.max()),
            max_sale_spread_pct=float(sc.sale_spread_pct.max()),
            key_scenarios=sc[(sc.level==100)&sc.gap_h.isin([8,12])&sc.session_min.isin([1,12])].to_dict('records')))
        rows.append(x)
    d=pd.concat(rows,ignore_index=True)
    fields=['con','mental','cap_h','raw_proc_pct','search_s','sale_bonus_pct','bag']
    mean=d.groupby(['level','class'])[fields].mean()
    mean.to_csv(OUT/'level-class-means.csv')
    result=dict(profile=f'CON power1.2; CHA15; INT/WIS50:50; DEX{DEX_FLOOR}',games=144,rows=len(d),
        cohorts=summaries,level100={c:{f:float(mean.loc[(100,c),f]) for f in fields} for c in CLASSES},
        boundary=boundary(),stat_identity_rows=len(d),app_changed=False,db_access=False,
        old_guardrail_growth_pct=6,new_hard_guardrail_approved=False)
    (OUT/'SUMMARY.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--dex-floor',type=float,choices=[4,3.5],default=4)
    args=parser.parse_args();DEX_FLOOR=args.dex_floor
    if DEX_FLOOR==3.5:OUT=PREVIOUS/'specialization-dex35-cha15'
    main()
