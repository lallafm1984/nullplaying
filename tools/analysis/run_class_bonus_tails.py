#!/usr/bin/env python3
"""Compile bounded tail/boundary probe against the isolated engine jar."""
import subprocess
from run_stat_bonus_probe import ROOT, OUT, JAVA, jar

def main():
    out=OUT/'final-all-classes'
    compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.20'),
        jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.20'),
        jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
        jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
        jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),
        jar('org.jetbrains','annotations','13.0')]
    runtime=[str(out/'engine-probe.jar'),compiler[1],compiler[-1],
        jar('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.8.1')]
    sandbox=['/usr/bin/sandbox-exec','-f',str(out/'offline-only.sb')]
    target=out/'growth-tail-probe.jar'
    subprocess.run(sandbox+[str(JAVA),'-Xmx512m','-cp',':'.join(compiler),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17',
        '-classpath',':'.join(runtime),'-d',str(target),str(ROOT/'tools/analysis/ClassBonusTailProbe.kt')],check=True,timeout=120)
    result=subprocess.run(sandbox+[str(JAVA),'-Xmx512m','-cp',':'.join([str(target),*runtime]),
        'com.nullplaying.engine.ClassBonusTailProbe',str(out/'growth-tails-iid.csv')],text=True,capture_output=True,timeout=120)
    (out/'growth-tail-iid-checks.log').write_text(result.stdout+result.stderr)
    print(result.stdout+result.stderr); result.check_returncode()

if __name__=='__main__':main()
