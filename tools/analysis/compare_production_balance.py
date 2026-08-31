#!/usr/bin/env python3
"""Compare production replay with the user-selected planning candidate, including quantization."""
import hashlib
import json
import numpy as np
import pandas as pd
from run_stat_bonus_probe import ROOT
from final_class_bonus_review import ordered
from progression_bonus_review import calendar

OUT=ROOT/'docs/audits/2026-09-01-final-stat-bonus-implementation'
PLANNING=ROOT/'docs/audits/2026-09-01-progression-bonus-redesign/con-mental-5050/specialization-dex35-cha15'

def main():
    path=OUT/'production/production-14-24-L100.csv'
    receipt=json.loads(path.with_suffix('.csv.execution.json').read_text())
    assert hashlib.sha256(path.read_bytes()).hexdigest()==receipt['output_sha256']
    for name,digest in json.loads((OUT/'production/source-sha256.json').read_text()).items():
        assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,name
    keys=['class','seed_index','level']
    expected=pd.concat([pd.read_csv(PLANNING/'matched.csv'),pd.read_csv(PLANNING/'heldout.csv')]).sort_values(keys).reset_index(drop=True)
    actual=pd.read_csv(path).sort_values(keys).reset_index(drop=True)
    assert len(actual)==14400 and not actual.duplicated(keys).any()
    stable=keys+['str','con','dex','int','wis','cha','hp','mp','bag','proc_pct','tales']
    pd.testing.assert_frame_equal(actual[stable],expected[stable],check_exact=True)
    assert np.allclose(actual.bonus_proc_pct,np.floor(expected.bonus_proc_pct*100+.5)/100,rtol=0,atol=1e-12)
    assert np.allclose(actual.bonus_search_s,expected.bonus_search_s,rtol=0,atol=1e-12)
    time_error=np.abs(actual.bonus_cap_h-expected.bonus_cap_h)*3_600_000
    sale_error=np.abs(actual.bonus_sale_pct-expected.bonus_sale_pct)
    assert time_error.max()<=.50001 and sale_error.max()<=.000000051
    comparison=actual[keys].copy()
    comparison['time_change_pct']=(actual.seconds/expected.seconds.replace(0,np.nan)-1)*100
    comparison['sale_gold_change']=actual.sale_gold-expected.sale_gold
    comparison['sale_gold_change_pct']=(actual.sale_gold/expected.sale_gold.replace(0,np.nan)-1)*100
    comparison.to_csv(OUT/'production/planning-comparison.csv',index=False)
    cohorts=[]
    for label,seeds in [('matched',list(range(14,30))),('extra',list(range(30,38)))]:
        group=ordered(actual[actual.seed_index.isin(seeds)],seeds).copy()
        group['cap_h']=group.bonus_cap_h
        sc,detail=calendar(group)
        sc.to_csv(OUT/f'production/{label}-scenarios.csv',index=False)
        cohorts.append(dict(cohort=label,max_growth_spread_pct=float(sc.growth_spread_pct.max()),
            max_sale_spread_pct=float(sc.sale_spread_pct.max())))
    summary=dict(rows=len(actual),games=144,stable_fields_exact=stable,
        max_capacity_quantization_error_ms=float(time_error.max()),
        max_sale_rate_quantization_error_percentage_points=float(sale_error.max()),
        raw_proc_quantization='PASS: nearest 0.01 percentage point',
        search_time_match='PASS: exact',
        max_abs_progression_time_change_pct=float(comparison.time_change_pct.abs().max()),
        changed_sale_rows=int((comparison.sale_gold_change!=0).sum()),
        calendar_cohorts=cohorts,
        largest_time_difference=comparison.loc[comparison.time_change_pct.abs().idxmax()].to_dict(),
        level100_changes=comparison[comparison.level==100].groupby('class')[['time_change_pct','sale_gold_change','sale_gold_change_pct']].mean().to_dict('index'),
        explanation='Price uses integer fixed point instead of Double multiplication; 1-gold boundary underpayment is corrected. Other engine paths use the selected formulas.')
    (OUT/'production/COMPARISON.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=='__main__': main()
