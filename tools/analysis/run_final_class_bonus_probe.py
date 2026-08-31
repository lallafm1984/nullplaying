#!/usr/bin/env python3
"""Fresh held-out all-class audit. Pure engine only; no app or DB bootstrap."""
from datetime import datetime
import hashlib
import json
import shutil
import sys
import time
import run_stat_bonus_probe as probe

if __name__ == '__main__':
    previous = probe.OUT / 'cleric-wis-cha'
    for name, digest in json.loads((previous/'engine_source_sha256.json').read_text()).items():
        if name.startswith('app/'):
            assert hashlib.sha256((probe.ROOT/name).read_bytes()).hexdigest() == digest, name
    probe.OUT = probe.OUT/'final-all-classes'
    probe.OUT.mkdir(exist_ok=True)
    shutil.copy2(previous/'offline-only.sb', probe.OUT/'offline-only.sb')
    common = json.loads((previous/'ten_hour_selected_parameters.json').read_text())
    mage = {**common, 'mp_pp': 15.0}
    for name, p in [('common-parameters.json', common), ('mage-parameters.json', mage)]:
        (probe.OUT/name).write_text(json.dumps(p,indent=2)+'\n')
    runs=[]
    for index, (classes, param, output) in enumerate([
        (['MAGE'], 'mage-parameters.json', 'mage-heldout.csv'),
        (['WARRIOR','ROGUE','RANGER','CLERIC','PALADIN'], 'common-parameters.json', 'others-heldout.csv'),
    ]):
        sys.argv = [__file__, '--samples','16','--level','100','--seed-offset','14','--dense',
            '--output',output,'--parameters',str(probe.OUT/param),'--classes',*classes]
        if index: sys.argv.insert(1,'--skip-compile')
        started=datetime.now().astimezone().isoformat(); clock=time.monotonic()
        probe.main()
        runs.append(dict(classes=classes,parameters=param,output=output,
            started_at=started,finished_at=datetime.now().astimezone().isoformat(),
            duration_seconds=time.monotonic()-clock,seeds=list(range(14,30))))
        (probe.OUT/'execution.json').write_text(json.dumps(runs,indent=2)+'\n')
    print('Fresh held-out cohort complete: 96 pure-engine games, seeds 14..29.')
