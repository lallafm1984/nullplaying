#!/usr/bin/env python3
"""Independent pre-implementation checks of new seeds and long-tail balance."""
import hashlib
import itertools
import json
from datetime import datetime
import numpy as np
import pandas as pd
from run_stat_bonus_probe import ROOT

OUT = ROOT / 'docs/audits/2026-09-01-final-stat-bonus-implementation'
SOURCE = ROOT / 'docs/audits/2026-09-01-progression-bonus-redesign/con-mental-5050/specialization-dex35-cha15'
CLASSES = ['WARRIOR', 'ROGUE', 'RANGER', 'MAGE', 'CLERIC', 'PALADIN']

def main():
    path = SOURCE / 'final-fresh-120.csv'
    receipt = json.loads(path.with_suffix('.csv.execution.json').read_text())
    assert receipt['output_sha256'] == hashlib.sha256(path.read_bytes()).hexdigest()
    d = pd.read_csv(path)
    assert len(d) == 6*8*120 and not d.duplicated(['class','seed_index','level']).any()
    index = pd.MultiIndex.from_product([CLASSES, range(38,46), range(1,121)], names=['class','seed_index','level'])
    d = d.set_index(['class','seed_index','level']).loc[index].reset_index()
    t = ((d.level-1)/99).clip(0,1)
    ceiling = .12 + .88*t**1.4
    cap = 8+2*(d.con/150).clip(0,1)**1.2*(.15+.85*t**1.2)
    proc = np.minimum(np.where(d['class']=='MAGE',35,30),d.proc_pct+15*((d['int']+d.wis)/360).clip(0,1)**1.2)
    search = 5-np.floor(1500*np.minimum((d.dex/150).clip(0,1),ceiling)+.5)/1000
    sale = 15*np.minimum((d.cha/150).clip(0,1),ceiling)
    for formula, column in [(cap,'bonus_cap_h'),(proc,'bonus_proc_pct'),(search,'bonus_search_s'),(sale,'bonus_sale_pct')]:
        assert np.allclose(formula,d[column],atol=1e-10,rtol=0), column
    assert cap.between(8,10).all() and search.between(3.5,5).all() and sale.between(0,15).all()
    assert (proc[d['class']!='MAGE']<=30).all() and (proc<=35).all()
    for field in ['con','dex','int','wis','cha','bonus_cap_h','bonus_proc_pct','bonus_sale_pct']:
        assert (d.groupby(['class','seed_index'])[field].diff().dropna()>=-1e-10).all(),field
    assert (d.groupby(['class','seed_index']).bonus_search_s.diff().dropna()<=1e-10).all()
    hundred = d[d.level==100]
    assert (hundred.loc[hundred['class']!='WARRIOR','bonus_cap_h']<10).all()
    assert (hundred.loc[~hundred['class'].isin(['ROGUE','RANGER']),'bonus_search_s']>3.5).all()
    assert (hundred.loc[~hundred['class'].isin(['CLERIC','PALADIN']),'bonus_sale_pct']<15).all()
    mage = d[d['class']=='MAGE']
    first = mage[mage.bonus_proc_pct>=35-1e-10].groupby('seed_index')['level'].min()
    assert (mage[mage.level==100].bonus_proc_pct<35).all()
    def cube(field): return d[field].to_numpy().reshape(6,8,120)
    seconds = cube('seconds'); cap3 = cube('bonus_cap_h'); gold = cube('sale_gold')
    dt = np.diff(seconds,axis=2,prepend=0)
    rows=[]
    for gap,session in itertools.product([4,6,8,9,10,12,16,24],[1,5,12]):
        fg=session/60
        factor=(gap+fg)/(np.minimum(gap,cap3*min(session/12,1))+fg)
        left=np.concatenate([factor[:,:,:1],factor[:,:,:-1]],axis=2)
        days=(np.cumsum(dt*factor,axis=2)+np.cumsum(dt*left,axis=2))/2/86400
        mean=days.mean(axis=1)
        income=gold.mean(axis=1)/np.where(mean>0,mean,np.nan)
        for level in range(20,121):
            a=mean[:,level-1]; b=income[:,level-1]
            rows.append(dict(level=level,gap_h=gap,session_min=session,
                growth_spread_pct=float((a.max()/a.min()-1)*100),
                sale_spread_pct=float((b.max()/b.min()-1)*100),
                fastest=CLASSES[int(a.argmin())],slowest=CLASSES[int(a.argmax())]))
    scenarios=pd.DataFrame(rows)
    OUT.mkdir(parents=True,exist_ok=True)
    scenarios.to_csv(OUT/'fresh-scenarios.csv',index=False)
    summary=dict(checked_at=datetime.now().astimezone().isoformat(),new_games=48,rows=len(d),
        seed_range=[38,45],level_range=[1,120],formula_checks='PASS',
        mage_first_cap_levels={str(k):int(v) for k,v in first.items()},
        max_growth_spread_20_100=float(scenarios[scenarios.level<=100].growth_spread_pct.max()),
        max_growth_spread_20_120=float(scenarios.growth_spread_pct.max()),
        max_sale_spread_20_120=float(scenarios.sale_spread_pct.max()),
        means=hundred.groupby('class')[['bonus_cap_h','bonus_proc_pct','bonus_search_s','bonus_sale_pct']].mean().to_dict('index'),
        caveats=['Synthetic seeds, not a player experiment','Calendar efficiency is a recurring-session approximation',
            'Class identity takes priority; the old 6% uniform-efficiency rule is not met',
            'Sale floating-point boundary underpayment must be fixed and tested before integration approval'])
    (OUT/'candidate-validation.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=='__main__': main()
