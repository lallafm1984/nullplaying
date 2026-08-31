#!/usr/bin/env python3
"""Review local engine evidence and prepare the requested curve visualization."""
from pathlib import Path
import hashlib
import json
import numpy as np
import pandas as pd
from stat_bonus_balance import ROOT, OUT, CLASSES, bonuses
from validate_stat_bonus_balance import calendar_scenarios


def main():
    p=json.loads((OUT/'level100_cap_parameters.json').read_text())
    new=pd.read_csv(OUT/'level100_caps_dense.csv')
    assert len(new)==2400 and not new.isna().any().any()
    assert not new.duplicated(['class','seed_index','level']).any()
    assert (new.groupby(['class','seed_index'])['level'].count()==100).all()
    x=new.copy()
    x['hp_hours'],x['raw_proc'],x['dex_fraction'],x['cha_fraction']=bonuses(x,p)
    x['mp_pct']=100*x.raw_proc
    x['dex_seconds']=5*(1-x.dex_fraction)
    x['cha_pct']=100*x.cha_fraction
    fields=['hp_hours','mp_pct','dex_seconds','cha_pct']
    assert x.hp_hours.between(8,12).all()
    assert x.mp_pct.between(0,30).all()
    assert x.dex_seconds.between(4,5).all()
    assert x.cha_pct.between(0,20).all()
    targets={'hp_hours':(['WARRIOR'],12),'mp_pct':(['MAGE','CLERIC'],30),
             'dex_seconds':(['ROGUE','RANGER'],4),'cha_pct':(['PALADIN'],20)}
    at100=x[x.level==100]
    cap_checks={}
    for field,(classes,cap) in targets.items():
        specialized=at100[at100['class'].isin(classes)]
        other=at100[~at100['class'].isin(classes)]
        a=np.isclose(specialized[field],cap)
        b=np.isclose(other[field],cap)
        cap_checks[field]={'specialized_at_cap':int(a.sum()),'specialized_n':len(a),
                           'other_at_cap':int(b.sum()),'other_n':len(b)}
        assert a.all() and not b.any(),(field,cap_checks[field])
    means=x.groupby(['class','level'])[fields].agg(['mean','min','max'])
    payload={'samplesPerClass':4,'seeds':[2,3,4,5],'parameters':p,'series':{}}
    for cls in CLASSES:
        rows=[]
        for level,row in means.loc[cls].iterrows():
            rows.append([int(level)]+[round(float(row[(f,a)]),4) for f in fields for a in ['mean','min','max']])
        payload['series'][cls]=rows
    (OUT/'level100_plot_data.json').write_text(json.dumps(payload,separators=(',',':'))+'\n')
    means.to_csv(OUT/'level100_curve_summary.csv')
    first_caps=[]
    for field,(classes,cap) in targets.items():
        first=x[x['class'].isin(classes)&np.isclose(x[field],cap)].groupby(['class','seed_index'])['level'].min()
        for (cls,seed),lvl in first.items():
            first_caps.append(dict(effect=field,hero_class=cls,seed=int(seed),first_cap_level=int(lvl)))
    pd.DataFrame(first_caps).to_csv(OUT/'level100_first_cap_levels.csv',index=False)
    scenarios=calendar_scenarios(new,p,'selected')
    ag=scenarios.groupby(['hero_class','level','gap_h','session_min']).agg(
        days=('calendar_days','mean'),gold=('sale_gold','mean')).reset_index()
    ag['rate']=1/ag.days
    ag['sale_rate']=ag.gold/ag.days
    gaps=[]
    for key,d in ag.groupby(['level','gap_h','session_min']):
        gaps.append(dict(zip(['level','gap_h','session_min'],(int(key[0]),float(key[1]),int(key[2]))),
            progress_gap_pct=(d.rate.max()/d.rate.min()-1)*100,
            sale_gap_pct=(d.sale_rate.max()/d.sale_rate.min()-1)*100))
    pd.DataFrame(gaps).to_csv(OUT/'level100_curve_gaps.csv',index=False)
    for name,digest in json.loads((OUT/'engine_source_sha256.json').read_text()).items():
        if name.startswith('app/'): assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest
    # Preserve the previous output family exactly: new mode must not alter old curves.
    oldp=json.loads((OUT/'upper_caps_parameters.json').read_text())
    old=bonuses(new,oldp)
    assert np.allclose(old[0],8+4*new.hp/(new.hp+350))
    assert np.allclose(old[1],np.minimum(.30,new.proc_pct/100+.10*new.mp/(new.mp+750)))
    boundary_stats=np.array([0,1,149,150,151,5999,6000,6001,1e18],dtype=float)
    boundary=pd.DataFrame({s:boundary_stats for s in ['hp','mp','dex','cha']})
    boundary['proc_pct']=20.
    caps,probs,search,sales=bonuses(boundary,p)
    for a in [caps,probs,search,sales]: assert (np.diff(a)>=0).all()
    assert np.allclose(caps,8+4*np.minimum(boundary_stats/6000,1))
    assert np.allclose(probs,.20+.10*np.minimum(boundary_stats/6000,1))
    assert np.allclose(search,.20*np.minimum(boundary_stats/150,1))
    assert np.allclose(sales,.20*np.minimum(boundary_stats/150,1))
    qa={'cap_checks_at_level100':cap_checks,'source_hashes_unchanged':True,'linear_boundaries_and_old_curve_compatibility':True,
        'first_cap_level_range':[min(r['first_cap_level'] for r in first_caps),max(r['first_cap_level'] for r in first_caps)],
        'caveats':['4 paired seeds/class, not all possible RNG outcomes','calibrated thresholds are not a class lock',
                   'calendar-gap effects are time-bank conversions, not real player sessions',
                   'level100 target does not prohibit reaching cap before level100'],
        'level100_effects':at100.groupby('class')[fields].mean().round(4).to_dict('index'),
        'level100_gaps':[r for r in gaps if r['level']==100 and r['session_min']==12]}
    (OUT/'level100_curve_validation.json').write_text(json.dumps(qa,indent=2)+'\n')
    template=ROOT/'tools/analysis/level100-class-caps.template.html'
    if template.exists():
        fragment=template.read_text()
        assert fragment.count('__PLOT_DATA__')==1
        (OUT/'level100-class-caps.html').write_text(fragment.replace('__PLOT_DATA__',json.dumps(payload,separators=(',',':'))))
    print(json.dumps(qa,indent=2))


if __name__=='__main__': main()
