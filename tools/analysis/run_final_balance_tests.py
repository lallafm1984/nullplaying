#!/usr/bin/env python3
"""Pure JVM regression suite; network/DB files denied, no Android bootstrap."""
import argparse
import hashlib
import json
import re
import subprocess
import time
from datetime import datetime
from run_stat_bonus_probe import ROOT, JAVA, jar

OUT = ROOT / 'docs/audits/2026-09-01-final-stat-bonus-implementation'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True, choices=['before', 'after'])
    args = parser.parse_args()
    out = OUT / args.tag
    out.mkdir(parents=True, exist_ok=True)
    sandbox_path = ROOT / 'docs/audits/2026-09-01-progression-bonus-redesign/con-mental-5050/offline-only.sb'
    sandbox = ['/usr/bin/sandbox-exec', '-f', str(sandbox_path)]
    compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.1.20'),
        jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.20'),
        jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
        jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
        jar('org.jetbrains', 'annotations', '13.0')]
    runtime = [compiler[1], compiler[-1],
        jar('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.8.1'),
        jar('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', '1.8.1'),
        jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3')]
    plugin = jar('org.jetbrains.kotlin', 'kotlin-serialization-compiler-plugin-embeddable', '2.1.20')
    sources = sorted((ROOT / 'app/src/simple/java/com/nullplaying/engine').glob('*.kt'))
    sources += sorted((ROOT / 'app/src/simple/java/com/nullplaying/model').glob('*.kt'))
    # Extract only the exact pure ranking bound used by PostgameJourneyTest.
    # Never compile/load the Supabase service or any of its dependencies.
    ranking_source = ROOT / 'app/src/simple/java/com/nullplaying/remote/SupabaseGameService.kt'
    ranking = ranking_source.read_text()
    bound = ranking[ranking.index('internal fun maximumAcceptedRankingCombatPower('):ranking.index('class SupabaseGameService(')]
    policy = ranking[ranking.index('internal const val MAX_SUPPORTED_RANKING_LEVEL'):ranking.index('internal fun appVersionAllowsAnnouncement(')]
    isolated = out / 'RankingBound.kt'
    isolated.write_text('package com.nullplaying.remote\n\n' + policy + '\n\n' + bound)
    sources.append(isolated)
    tests = sorted((ROOT / 'app/src/simpleTest/java/com/nullplaying/engine').glob('*Test.kt'))
    if args.tag == 'after':
        sources += [ROOT/'app/src/simple/java/com/nullplaying/remote/SessionLogLifecycle.kt',
            ROOT/'app/src/simple/java/com/nullplaying/data/GameProgressNotifications.kt']
        tests += [ROOT/'app/src/simpleTest/java/com/nullplaying/remote/SessionLogLifecycleTest.kt',
            ROOT/'app/src/simpleTest/java/com/nullplaying/remote/RankingSubmissionPolicyTest.kt',
            ROOT/'app/src/simpleTest/java/com/nullplaying/data/GameProgressNotificationsTest.kt']
    sources += tests
    forbidden = ['import android.', 'import androidx.', 'import io.github.jan.supabase', 'import com.google.firebase']
    for source in sources:
        assert not any(token in source.read_text() for token in forbidden), source
    # Task-specific immutable originals, including non-JVM integration files for review diffs.
    originals = [p for p in sources if p != isolated] + [ranking_source] + [ROOT / p for p in [
        'app/src/simple/java/com/nullplaying/remote/OfflineAdventureRemoteConfig.kt',
        'app/src/simple/java/com/nullplaying/ui/AlarmQuestApp.kt',
        'docs/FIREBASE_REMOTE_CONFIG.md']]
    hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in originals}
    if args.tag == 'before' and (out / 'sources.json').exists():
        previous = json.loads((out / 'sources.json').read_text())
        assert all(hashes[p] == digest for p, digest in previous.items()), 'Baseline drift'
    if args.tag == 'before':
        for source in originals:
            copy = out / 'source' / source.relative_to(ROOT)
            copy.parent.mkdir(parents=True, exist_ok=True)
            if not copy.exists():
                copy.write_bytes(source.read_bytes())
    (out / 'sources.json').write_text(json.dumps(hashes, indent=2) + '\n')
    target = out / 'pure-tests.jar'
    start = datetime.now().astimezone().isoformat()
    clock = time.monotonic()
    subprocess.run(sandbox + [str(JAVA), '-Xmx768m', '-cp', ':'.join(compiler),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
        '-Xplugin=' + plugin, '-classpath', ':'.join(runtime), '-d', str(target), *map(str, sources)],
        cwd=ROOT, check=True)
    with (out / 'tests.log').open('w') as log:
        result = subprocess.run(sandbox + [str(JAVA), '-Xmx768m', '-cp', ':'.join([str(target), *runtime]),
            'org.junit.runner.JUnitCore', *[f'com.nullplaying.{p.parent.name}.{p.stem}' for p in tests]],
            cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600)
    receipt = dict(started_at=start, elapsed_seconds=time.monotonic()-clock,
        test_classes=[p.stem for p in tests], exit_code=result.returncode,
        network='OS sandbox denied', database_files='OS sandbox denied', app_started=False)
    (out / 'execution.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print((out / 'tests.log').read_text()[-18000:])
    print(json.dumps(receipt))
    result.check_returncode()

if __name__ == '__main__':
    main()
