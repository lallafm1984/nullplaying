#!/usr/bin/env python3
"""Snapshot compiled local test classes, then run six isolated, offline JVM cohorts.

First run ./gradlew :app:compileDebugUnitTestKotlin. No Gradle processes are started here.
The immutable class snapshot lets the app's serial Gradle verification proceed independently.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "output/verification/2026-09-15"
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
CLASSES = ["WARRIOR", "ROGUE", "RANGER", "MAGE", "CLERIC", "PALADIN"]


def jar(group, artifact, version):
    paths = list((CACHE / group / artifact / version).glob("*/*.jar"))
    assert len(paths) == 1, (artifact, len(paths))
    return str(paths[0])


def main():
    snapshot = OUT / "equipment-jvm-snapshot"
    snapshot.mkdir(parents=True, exist_ok=False)
    sources = list((ROOT / "app/src/simple/java").rglob("*.kt"))
    sources += list((ROOT / "app/src/simpleTest/java").rglob("*.kt"))
    (snapshot / "source-sha256.json").write_text(json.dumps({
        str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources
    }, indent=2))
    classpath = []
    for variant in ["debug", "debugUnitTest"]:
        target = snapshot / variant
        shutil.copytree(ROOT / "app/build/tmp/kotlin-classes" / variant, target)
        classpath.append(str(target))
    for spec in [("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.20"),
                 ("org.jetbrains", "annotations", "13.0"),
                 ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.8.1"),
                 ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.8.1"),
                 ("junit", "junit", "4.13.2"), ("org.hamcrest", "hamcrest-core", "1.3")]:
        classpath.append(jar(*spec))
    java_home = os.environ.get("JAVA_HOME") or subprocess.check_output(
        ["/usr/libexec/java_home", "-v", "17"], text=True).strip()
    command = [str(Path(java_home) / "bin/java"), "-Xmx768m", "-cp", os.pathsep.join(classpath),
               "org.junit.runner.JUnitCore", "com.nullplaying.engine.HighTierEquipmentAuditTest"]

    def run(hero_class):
        started = time.monotonic()
        with (OUT / f"equipment-{hero_class}.log").open("w") as log:
            result = subprocess.run(command, cwd=ROOT, env={**os.environ, "EQUIPMENT_AUDIT_CLASS": hero_class},
                                    stdout=log, stderr=subprocess.STDOUT, timeout=2000)
        receipt = {"class": hero_class, "exit_code": result.returncode,
                   "seconds": round(time.monotonic() - started, 2)}
        print(json.dumps(receipt), flush=True)
        return receipt

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as workers:
        receipts = list(workers.map(run, CLASSES))
    (OUT / "equipment-execution.json").write_text(json.dumps(receipts, indent=2))
    assert all(r["exit_code"] == 0 for r in receipts), receipts
    for name in ["levels.csv", "full-sets.csv", "characters.csv"]:
        parts = [(OUT / "equipment" / hero / name).read_text().splitlines() for hero in CLASSES]
        assert all(part[0] == parts[0][0] for part in parts)
        (OUT / "equipment" / name).write_text("\n".join([parts[0][0]] + [line for part in parts for line in part[1:]]) + "\n")


if __name__ == "__main__":
    main()
