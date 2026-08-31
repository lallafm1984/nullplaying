#!/usr/bin/env python3
"""Merge replayed WIS/CHA cleric paths with unchanged classes; rerun selection."""
import json
import shutil
import pandas as pd
import ten_hour_balance as balance

def main():
    old=balance.OUT;new=old/'cleric-wis-cha'
    before=json.loads((old/'engine_source_sha256.json').read_text())
    after=json.loads((new/'engine_source_sha256.json').read_text())
    changed=[n for n in before if n.startswith('app/') and before[n]!=after[n]]
    assert changed==['app/src/simple/java/com/nullplaying/model/SimpleGameModels.kt'],changed
    cleric=pd.read_csv(new/'cleric_wis_cha_dense.csv')
    assert len(cleric)==1200 and set(cleric['class'])=={'CLERIC'}
    for filename,seeds in [('level100_caps_dense.csv',[2,3,4,5]),('ten_hour_independent_dense.csv',list(range(6,14)))]:
        base=pd.read_csv(old/filename)
        unchanged=base[base['class']!='CLERIC']
        replaced=cleric[cleric.seed_index.isin(seeds)]
        merged=pd.concat([unchanged,replaced],ignore_index=True).sort_values(balance.KEYS)
        assert len(merged)==len(base) and not merged.duplicated(balance.KEYS).any()
        merged.to_csv(new/filename,index=False)
    shutil.copy2(old/'ten_hour_parameters.json',new/'ten_hour_parameters.json')
    balance.OUT=new
    balance.CAP_TARGETS=[('cap_h',['WARRIOR'],10),('raw_proc_pct',['MAGE'],30),
        ('search_s',['ROGUE','RANGER'],4),('sale_bonus_pct',['CLERIC','PALADIN'],20)]
    balance.screen()
    _,_,summary=balance.validate()
    summary['cleric_stat_pair']='WIS/CHA'
    summary['replayed_changed_class_games']=12
    summary['unchanged_class_independent_paths_reused']=40
    summary['app_source_change']=changed
    summary['caveats'].append('Cleric was replayed; the unchanged other classes retain their same-seed source-verified paths.')
    (new/'ten_hour_validation.json').write_text(json.dumps(summary,indent=2)+'\n')
    print('Cleric WIS/CHA balance updated. Original WIS/INT evidence preserved in parent folder.')

if __name__=='__main__':main()
