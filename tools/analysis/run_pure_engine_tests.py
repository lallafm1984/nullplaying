#!/usr/bin/env python3
"""Compile/run only in-memory engine tests, without Gradle or Android bootstrap."""
import json
import subprocess
from run_stat_bonus_probe import ROOT, OUT, JAVA, jar

def main():
    out=OUT/'cleric-wis-cha';out.mkdir(exist_ok=True)
    compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.20'),
              jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.20'),
              jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
              jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
              jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),
              jar('org.jetbrains','annotations','13.0')]
    runtime=[compiler[1],compiler[-1],jar('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.8.1'),
             jar('org.jetbrains.kotlinx','kotlinx-serialization-json-jvm','1.8.1'),
             jar('junit','junit','4.13.2'),jar('org.hamcrest','hamcrest-core','1.3')]
    plugin=jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.1.20')
    sources=sorted((ROOT/'app/src/simple/java/com/nullplaying/engine').glob('*.kt'))
    sources+=sorted((ROOT/'app/src/simple/java/com/nullplaying/model').glob('*.kt'))
    targets=['SimpleGameEngineTest','ClericStatContractTest']
    sources += [ROOT/f'app/src/simpleTest/java/com/nullplaying/engine/{n}.kt' for n in targets]
    for source in sources:
        text=source.read_text()
        assert not any(x in text for x in ['import android.','import androidx.room','import io.github.jan.supabase'])
    sandbox=['/usr/bin/sandbox-exec','-f',str(OUT/'offline-only.sb')]
    target=out/'pure-engine-tests.jar'
    subprocess.run(sandbox+[str(JAVA),'-Xmx768m','-cp',':'.join(compiler),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17',
        '-Xplugin='+plugin,'-classpath',':'.join(runtime),'-d',str(target),*map(str,sources)],check=True,cwd=ROOT)
    result=subprocess.run(sandbox+[str(JAVA),'-Xmx768m','-cp',':'.join([str(target),*runtime]),
        'org.junit.runner.JUnitCore',*[f'com.nullplaying.engine.{n}' for n in targets]],
        text=True,capture_output=True,cwd=ROOT,timeout=240)
    (out/'pure-engine-tests.log').write_text(result.stdout+result.stderr)
    print(result.stdout+result.stderr)
    result.check_returncode()

if __name__=='__main__':main()
