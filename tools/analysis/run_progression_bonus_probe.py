#!/usr/bin/env python3
"""Bounded offline candidate replays. All mutations are analysis-output files."""
from datetime import datetime
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

import run_stat_bonus_probe as base

ROOT = base.ROOT
OUT = ROOT / 'docs/audits/2026-09-01-progression-bonus-redesign'
HOOK = ROOT / 'tools/analysis/ProgressionBonusHooks.kt'


def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    previous = ROOT/'docs/audits/2026-08-31-stat-bonus-balance/final-all-classes'
    for name, digest in json.loads((previous/'engine_source_sha256.json').read_text()).items():
        if name.startswith('app/'):
            assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest() == digest, name
    (OUT/'offline-only.sb').write_text((previous/'offline-only.sb').read_text())
    sources = sorted((ROOT/'app/src/simple/java/com/nullplaying/engine').glob('*.kt'))
    sources += sorted((ROOT/'app/src/simple/java/com/nullplaying/model').glob('*.kt'))
    original = ROOT/'app/src/simple/java/com/nullplaying/engine/SimpleGameEngine.kt'
    code = original.read_text()
    for before, after in [
        ('val rawProbability = skillProcPercent(state).toDouble() / 100.0', 'val rawProbability = BalanceSimulationHooks.rawProbability(state, skillProcPercent(state))'),
        ('procRoll.coerceIn(0, 99) < skillProcPercent(state)', 'BalanceSimulationHooks.proc(state, procRoll, skillProcPercent(state))'),
        ('val procRoll = rng.nextInt(100)', 'BalanceSimulationHooks.attacks++\n        val procRoll = rng.nextInt(10000)'),
        ('val current = state.skills[skillIndex]', 'BalanceSimulationHooks.casts++\n        val current = state.skills[skillIndex]'),
        ('val value = saleValue(sold)', 'val value = BalanceSimulationHooks.sale(state, saleValue(sold))'),
        ('safeAdd(eventAt, ENCOUNTER_REVEAL_MILLIS)', 'safeAdd(eventAt, BalanceSimulationHooks.encounterMillis(state, ENCOUNTER_REVEAL_MILLIS))'),
        ('safeAdd(state.lastSettledAt, ENCOUNTER_REVEAL_MILLIS)', 'safeAdd(state.lastSettledAt, BalanceSimulationHooks.encounterMillis(state, ENCOUNTER_REVEAL_MILLIS))'),
    ]:
        assert code.count(before) == 1, before
        code = code.replace(before, after)
    copied = OUT/'SimpleGameEngine.analysis.kt'
    copied.write_text(code)
    sources[sources.index(original)] = copied
    probe = (ROOT/'tools/analysis/StatBonusEngineProbe.kt').read_text()
    probe = probe.replace('casts,experience")', 'casts,experience,bonus_cap_h,bonus_proc_pct,bonus_search_s,bonus_sale_pct")')
    seam = 'BalanceSimulationHooks.casts, h.experience).joinToString(",")'
    assert probe.count(seam) == 1
    probe = probe.replace(seam, 'BalanceSimulationHooks.casts, h.experience,\n' +
        'BalanceSimulationHooks.offlineHours(state), BalanceSimulationHooks.rawProbability(state, engine.skillProcPercent(state))*100,\n' +
        'BalanceSimulationHooks.encounterMillis(state, 5000L)/1000.0, BalanceSimulationHooks.saleBonus(state)*100).joinToString(",")')
    probe_path = OUT/'StatBonusEngineProbe.analysis.kt'
    probe_path.write_text(probe)
    sources += [probe_path, HOOK]
    for p in sources:
        assert not any(t in p.read_text() for t in ['import android.', 'import androidx.room', 'import io.github.jan.supabase', 'import com.google.firebase'])
    compiler = [base.jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.1.20'),
        base.jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.20'), base.jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
        base.jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
        base.jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'), base.jar('org.jetbrains','annotations','13.0')]
    runtime = [compiler[1], compiler[-1], base.jar('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.8.1')]
    fingerprint = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    build = OUT/'build-receipt.json'
    jar = OUT/'progression-probe.jar'
    sandbox = ['/usr/bin/sandbox-exec', '-f', str(OUT/'offline-only.sb')]
    if not (jar.exists() and build.exists() and json.loads(build.read_text()) == fingerprint):
        subprocess.run(sandbox+[str(base.JAVA),'-Xmx768m','-cp',':'.join(compiler),
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17',
            '-classpath',':'.join(runtime),'-d',str(jar),*map(str,sources)],check=True,cwd=ROOT)
        build.write_text(json.dumps(fingerprint,indent=2)+'\n')
    return sandbox, jar, runtime


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--variant',choices=['baseline','stat_power','level_ramp','level_envelope'],required=True)
    parser.add_argument('--samples',type=int,default=8)
    parser.add_argument('--seed-offset',type=int,default=14)
    parser.add_argument('--level',type=int,default=100)
    parser.add_argument('--mage-threshold',type=float,default=8000)
    parser.add_argument('--classes',default='')
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    assert 1<=args.samples<=32 and 1<=args.level<=120
    assert Path(args.output).name==args.output
    sandbox, jar, runtime=prepare()
    started=datetime.now().astimezone().isoformat();clock=time.monotonic()
    output=OUT/args.output
    with (OUT/(args.output+'.log')).open('w') as log:
        subprocess.run(sandbox+[str(base.JAVA),'-Xmx768m','-cp',':'.join([str(jar),*runtime]),
            'com.nullplaying.engine.StatBonusEngineProbe',str(args.samples),str(args.level),str(output),str(args.seed_offset),
            f'{args.variant},{args.mage_threshold}','dense',args.classes],check=True,cwd=ROOT,stderr=log)
    receipt=dict(**vars(args),started_at=started,finished_at=datetime.now().astimezone().isoformat(),
        duration_seconds=time.monotonic()-clock,output_sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
        network='OS sandbox denied',database_files='OS sandbox denied',app_started=False)
    (OUT/(args.output+'.execution.json')).write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt,ensure_ascii=False))


if __name__=='__main__':main()
