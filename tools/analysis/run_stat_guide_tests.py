#!/usr/bin/env python3
"""Run only the stat guide's pure JVM contracts, without Android/DB/network startup."""
import hashlib
import json
import subprocess
from datetime import datetime

from run_stat_bonus_probe import JAVA, ROOT, jar


def main():
    out = ROOT / 'docs/audits/2026-09-01-stat-guide'
    out.mkdir(parents=True, exist_ok=True)
    sandbox = ['/usr/bin/sandbox-exec', '-f', str(ROOT /
        'docs/audits/2026-09-01-progression-bonus-redesign/con-mental-5050/offline-only.sb')]
    compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.1.20'),
        jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.20'),
        jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
        jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
        jar('org.jetbrains', 'annotations', '13.0')]
    runtime = [compiler[1], compiler[-1],
        jar('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.8.1'),
        jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3')]
    paths = [
        'app/src/simple/java/com/nullplaying/model/SimpleGameModels.kt',
        'app/src/simple/java/com/nullplaying/ui/StatGuideContent.kt',
        'app/src/simpleTest/java/com/nullplaying/ui/StatGuideContentTest.kt',
    ]
    sources = [ROOT / path for path in paths]
    for source in sources:
        assert not any(token in source.read_text() for token in
            ['import android.', 'import androidx.', 'import com.google.firebase', 'import io.github.jan.supabase'])
    target = out / 'stat-guide-tests.jar'
    started_at = datetime.now().astimezone().isoformat()
    subprocess.run(sandbox + [str(JAVA), '-Xmx512m', '-cp', ':'.join(compiler),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
        '-classpath', ':'.join(runtime), '-d', str(target), *map(str, sources)], cwd=ROOT, check=True)
    result = subprocess.run(sandbox + [str(JAVA), '-Xmx256m', '-cp', ':'.join([str(target), *runtime]),
        'org.junit.runner.JUnitCore', 'com.nullplaying.ui.StatGuideContentTest'],
        cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    (out / 'tests.log').write_text(result.stdout)
    observed = sources + [ROOT / name for name in [
        'app/src/simple/java/com/nullplaying/ui/StatGuide.kt',
        'app/src/simple/java/com/nullplaying/ui/AlarmQuestApp.kt',
        'app/src/simple/res/raw/localization_en.tsv',
        'app/src/simple/res/raw/localization_ja.tsv']]
    receipt = dict(started_at=started_at, exit_code=result.returncode,
        network='OS sandbox denied', database_files='OS sandbox denied', android_app_started=False,
        sha256={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in observed})
    (out / 'tests-execution.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(result.stdout)
    result.check_returncode()


if __name__ == '__main__':
    main()
