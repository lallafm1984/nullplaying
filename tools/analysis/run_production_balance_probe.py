#!/usr/bin/env python3
"""Replay the production rules; the only engine instrumentation counts attacks/casts."""
import argparse
import hashlib
import json
import subprocess
import time
from datetime import datetime
from run_stat_bonus_probe import ROOT, JAVA, jar

OUT = ROOT/'docs/audits/2026-09-01-final-stat-bonus-implementation/production'

def main():
    global OUT
    p=argparse.ArgumentParser()
    p.add_argument('--samples',type=int,default=24)
    p.add_argument('--seed-offset',type=int,default=14)
    p.add_argument('--level',type=int,default=100)
    p.add_argument('--base-minutes',type=int,default=480)
    p.add_argument('--charge-minutes',type=int,default=12)
    p.add_argument('--run-name')
    a=p.parse_args()
    assert 1<=a.samples<=32 and 1<=a.level<=120
    assert 1<=a.base_minutes<=4320 and 1<=a.charge_minutes<=1440
    if a.run_name:
        assert a.run_name.replace('-','').replace('_','').isalnum()
        OUT=OUT.parent/a.run_name
    OUT.mkdir(parents=True,exist_ok=True)
    sandbox=['/usr/bin/sandbox-exec','-f',str(ROOT/'docs/audits/2026-09-01-progression-bonus-redesign/con-mental-5050/offline-only.sb')]
    sources=sorted((ROOT/'app/src/simple/java/com/nullplaying/engine').glob('*.kt'))
    sources+=sorted((ROOT/'app/src/simple/java/com/nullplaying/model').glob('*.kt'))
    for source in sources:
        snapshot=OUT/'source'/source.relative_to(ROOT)
        snapshot.parent.mkdir(parents=True,exist_ok=True)
        snapshot.write_bytes(source.read_bytes())
    original=ROOT/'app/src/simple/java/com/nullplaying/engine/SimpleGameEngine.kt'
    code=original.read_text()
    for before,after in [
        ('val procRoll = rng.nextInt(10_000)', 'BalanceSimulationHooks.attacks++\n        val procRoll = rng.nextInt(10_000)'),
        ('val current = state.skills[skillIndex]', 'BalanceSimulationHooks.casts++\n        val current = state.skills[skillIndex]')]:
        assert code.count(before)==1
        code=code.replace(before,after)
    copied=OUT/'SimpleGameEngine.counted.kt';copied.write_text(code)
    sources[sources.index(original)]=copied
    counter=OUT/'Counters.kt'
    counter.write_text('''package com.nullplaying.engine
object BalanceSimulationHooks {
    var attacks=0L; var casts=0L
    fun resetCounters(){ attacks=0L; casts=0L }
    fun configure(csv:String){ check(csv=="") }
}
''')
    probe=(ROOT/'tools/analysis/StatBonusEngineProbe.kt').read_text()
    probe=probe.replace('OfflineAdventureConfig(480, 12)',f'OfflineAdventureConfig({a.base_minutes}, {a.charge_minutes})')
    probe=probe.replace('engine.skillProcPercent(state)', 'engine.baseSkillProcPercent(state)')
    probe=probe.replace('casts,experience")','casts,experience,bonus_cap_h,bonus_proc_pct,bonus_search_s,bonus_sale_pct")')
    seam='BalanceSimulationHooks.casts, h.experience).joinToString(",")'
    assert probe.count(seam)==1
    probe=probe.replace(seam,'BalanceSimulationHooks.casts, h.experience,\n'+
        'engine.offlineAdventureCapacityMillis(state)/3_600_000.0, engine.skillProcPercent(state),\n'+
        'StatBonusRules.encounterSearchMillis(state)/1000.0, StatBonusRules.saleBonusPercent(state)).joinToString(",")')
    probe=probe.replace('capacity * 60_000L', 'engine.offlineAdventureCapacityMillis(state)')
    probe_path=OUT/'ProductionProbe.kt';probe_path.write_text(probe)
    sources += [counter,probe_path]
    for source in sources:
        assert not any(t in source.read_text() for t in ['import android.','import androidx.','import io.github.jan.supabase','import com.google.firebase'])
    compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.20'),
        jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.20'),jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
        jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
        jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),jar('org.jetbrains','annotations','13.0')]
    runtime=[compiler[1],compiler[-1],jar('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.8.1')]
    target=OUT/'production.jar'
    subprocess.run(sandbox+[str(JAVA),'-Xmx768m','-cp',':'.join(compiler),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17',
        '-classpath',':'.join(runtime),'-d',str(target),*map(str,sources)],check=True,cwd=ROOT)
    fingerprints={str(s.relative_to(ROOT)):hashlib.sha256(s.read_bytes()).hexdigest() for s in sources+[original]}
    (OUT/'source-sha256.json').write_text(json.dumps(fingerprints,indent=2)+'\n')
    name=f'production-{a.seed_offset}-{a.samples}-L{a.level}.csv';output=OUT/name
    started=datetime.now().astimezone().isoformat();clock=time.monotonic()
    with (OUT/(name+'.log')).open('w') as log:
        subprocess.run(sandbox+[str(JAVA),'-Xmx768m','-cp',':'.join([str(target),*runtime]),
            'com.nullplaying.engine.StatBonusEngineProbe',str(a.samples),str(a.level),str(output),str(a.seed_offset),'','dense',''],
            check=True,cwd=ROOT,stderr=log)
    receipt=dict(**vars(a),started_at=started,elapsed_seconds=time.monotonic()-clock,
        output_sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
        network='OS sandbox denied',database_files='OS sandbox denied',app_started=False,
        instrumentation='Only attack/cast counters; no balance replacement')
    (OUT/(name+'.execution.json')).write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt))

if __name__=='__main__': main()
