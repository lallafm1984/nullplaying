#!/usr/bin/env python3
"""Isolated CON / equal INT-WIS engine replay. No app, device or DB startup."""
from datetime import datetime
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import run_progression_bonus_probe as runner

OUT = runner.OUT/'con-mental-5050'

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--samples',type=int,default=8)
    p.add_argument('--seed-offset',type=int,default=30)
    p.add_argument('--level',type=int,default=120)
    p.add_argument('--output',required=True)
    p.add_argument('--profile',choices=['equal-weights','specialization-cha15','specialization-dex35-cha15'],default='equal-weights')
    a=p.parse_args()
    assert 1<=a.samples<=32 and 1<=a.level<=120
    assert Path(a.output).name==a.output
    out=OUT if a.profile=='equal-weights' else OUT/a.profile
    runner.OUT=out
    hook={'equal-weights':'ConMentalBonusHooks.kt','specialization-cha15':'SpecializationBonusHooks.kt',
        'specialization-dex35-cha15':'SpecializationDexBonusHooks.kt'}[a.profile]
    runner.HOOK=runner.ROOT/'tools/analysis'/hook
    sandbox,jar,runtime=runner.prepare()
    started=datetime.now().astimezone().isoformat();clock=time.monotonic()
    output=out/a.output
    with (out/(a.output+'.log')).open('w') as log:
        subprocess.run(sandbox+[str(runner.base.JAVA),'-Xmx768m','-cp',':'.join([str(jar),*runtime]),
            'com.nullplaying.engine.StatBonusEngineProbe',str(a.samples),str(a.level),str(output),str(a.seed_offset),
            'con_mental,180','dense',''],check=True,cwd=runner.ROOT,stderr=log)
    receipt=dict(**vars(a),started_at=started,finished_at=datetime.now().astimezone().isoformat(),
        duration_seconds=time.monotonic()-clock,output_sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
        formula='CON time; 0.5 INT + 0.5 WIS added skill bonus; '+('q=0.6 CHA20' if a.profile=='equal-weights' else 'q=1.2 CHA15')+
            (' DEX3.5s' if a.profile=='specialization-dex35-cha15' else ' DEX4s'),
        network='OS sandbox denied',database_files='OS sandbox denied',app_started=False)
    (out/(a.output+'.execution.json')).write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(receipt,ensure_ascii=False))

if __name__=='__main__':main()
