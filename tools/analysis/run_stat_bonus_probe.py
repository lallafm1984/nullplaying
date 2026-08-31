#!/usr/bin/env python3
"""Compile only Kotlin engine/model source from existing caches, offline.

No Gradle, Android SDK/emulator, app bootstrap, DB, credentials or network.
Java compilation and execution are bounded to a 768 MB heap and OS-network
and database-file deny sandbox. Outputs go only to the named audit directory.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/audits/2026-08-31-stat-bonus-balance"
JAVA = Path("/Users/lim/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java")
CACHE = Path("/Users/lim/.gradle/caches/modules-2/files-2.1")


def jar(group, artifact, version):
    paths = list((CACHE / group / artifact / version).glob("*/*.jar"))
    assert len(paths) == 1, (artifact, paths)
    return str(paths[0])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--level", type=int, default=20)
    parser.add_argument("--skip-compile", action="store_true")
    parser.add_argument("--output", default="engine_trajectories.csv")
    parser.add_argument("--seed-offset", type=int, default=0)
    parser.add_argument("--parameters", type=Path)
    parser.add_argument("--dense", action="store_true")
    parser.add_argument("--classes", nargs='+', choices=['WARRIOR','ROGUE','RANGER','MAGE','CLERIC','PALADIN'])
    args = parser.parse_args()
    assert Path(args.output).name == args.output
    assert 1 <= args.samples <= 32 and 1 <= args.level <= 100
    assert 0 <= args.seed_offset <= 100_000
    compiler_jars = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.1.20"),
                     jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.20"),
                     jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                     jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                     jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
                     jar("org.jetbrains", "annotations", "13.0")]
    runtime_jars = [compiler_jars[1], compiler_jars[-1],
                    jar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.8.1")]
    sources = sorted((ROOT / "app/src/simple/java/com/nullplaying/engine").glob("*.kt"))
    sources += sorted((ROOT / "app/src/simple/java/com/nullplaying/model").glob("*.kt"))
    sources.append(ROOT / "tools/analysis/StatBonusEngineProbe.kt")
    sources.append(ROOT / "tools/analysis/BalanceSimulationHooks.kt")
    for path in sources:
        content = path.read_text()
        assert not any(x in content for x in ["import android.", "import androidx.room", "import com.google.firebase", "import io.github.jan.supabase"]), path
    provenance = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    (OUT / "engine_source_sha256.json").write_text(json.dumps(provenance, indent=2) + "\n")
    # Mechanical instrumentation of an ANALYSIS COPY. Assert each exact source
    # seam so upstream drift fails loudly instead of silently simulating a lie.
    original = ROOT / "app/src/simple/java/com/nullplaying/engine/SimpleGameEngine.kt"
    instrumented = original.read_text()
    replacements = [
        ('val rawProbability = skillProcPercent(state).toDouble() / 100.0',
         'val rawProbability = BalanceSimulationHooks.rawProbability(state, skillProcPercent(state))'),
        ('procRoll.coerceIn(0, 99) < skillProcPercent(state)',
         'BalanceSimulationHooks.proc(state, procRoll, skillProcPercent(state))'),
        ('val procRoll = rng.nextInt(100)',
         'BalanceSimulationHooks.attacks++\n        val procRoll = rng.nextInt(if (BalanceSimulationHooks.enabled) 10000 else 100)'),
        ('val current = state.skills[skillIndex]',
         'BalanceSimulationHooks.casts++\n        val current = state.skills[skillIndex]'),
        ('val value = saleValue(sold)', 'val value = BalanceSimulationHooks.sale(state, saleValue(sold))'),
        ('safeAdd(eventAt, ENCOUNTER_REVEAL_MILLIS)',
         'safeAdd(eventAt, BalanceSimulationHooks.encounterMillis(state, ENCOUNTER_REVEAL_MILLIS))'),
        ('safeAdd(state.lastSettledAt, ENCOUNTER_REVEAL_MILLIS)',
         'safeAdd(state.lastSettledAt, BalanceSimulationHooks.encounterMillis(state, ENCOUNTER_REVEAL_MILLIS))'),
    ]
    for before, after in replacements:
        assert instrumented.count(before) == 1, before
        instrumented = instrumented.replace(before, after)
    copy_path = OUT / 'SimpleGameEngine.analysis.kt'
    copy_path.write_text(instrumented)
    sources[sources.index(original)] = copy_path
    sandbox = ["/usr/bin/sandbox-exec", "-f", str(OUT / "offline-only.sb")]
    target = OUT / "engine-probe.jar"
    fingerprint = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    build_receipt = OUT / 'engine_build_sha256.json'
    if args.skip_compile:
        assert target.exists() and build_receipt.exists(), 'Compile once before --skip-compile'
        assert json.loads(build_receipt.read_text()) == fingerprint, 'Source changed; rerun without --skip-compile'
    if not args.skip_compile:
        subprocess.run(sandbox + [str(JAVA), "-Xmx768m", "-cp", ":".join(compiler_jars),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", "17", "-classpath", ":".join(runtime_jars), "-d", str(target),
            *map(str, sources)], check=True, cwd=ROOT)
        build_receipt.write_text(json.dumps(fingerprint, indent=2) + '\n')
    params = ''
    if args.parameters:
        p = json.loads(args.parameters.read_text())
        params = ','.join(str(p[k]) for k in ['hp_hours','hp_k','mp_pp','mp_k','dex_max','dex_k','cha_max','cha_k'])
        if p.get('curve') == 'linear_cap': params += ',1'
    subprocess.run(sandbox + [str(JAVA), "-Xmx768m", "-cp", ":".join([str(target), *runtime_jars]),
        "com.nullplaying.engine.StatBonusEngineProbe", str(args.samples), str(args.level),
        str(OUT / args.output), str(args.seed_offset), params, 'dense' if args.dense else '', ','.join(args.classes or [])], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
