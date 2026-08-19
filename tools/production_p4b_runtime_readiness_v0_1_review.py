#!/usr/bin/env python3
"""PD audit for P4b semantic runtime readiness and Registry242 v0.4 draft."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "54c7cb5a79dea1e350cbb050feca9f97a3b6e6581aeabda1fb2e799fba46eb6e"
REGISTRY_RESOURCE = "com/alarmquest/engine/vnext/skill_registry_242_v0_3.json.gz.b64"
READINESS_HASH = "ec07ace2bfc9f332f738c8f577fad3aa6f749a293dadc7d719bbcac2c9d9c7f4"
SCHEMA_HASH = "23f02e39bdd659a9ed3d38cee61ff2a3489bec044c6fc8a3d63d586bbe101cf9"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSemanticRuntimeReadiness.kt":
        "1872154fd890bf41a06217f341551fcc3cf7608c2541dbfd44d2604463e178db",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextSemanticRuntimeReadinessTest.kt":
        "4faa4c68e3e433b6b0ccffd71d83906d9b9a0913da990d75d8178cdc331023ba",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSkillRuntimeSchemaV04.kt":
        "35fb98f84b063a374c7c8febc15ab57b86c44e967b06aa7950f4143d2311882f",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextSkillRuntimeSchemaV04Test.kt":
        "bf4bea70e5f5b036e3bc798e0e35864e71e43f0fa75b515ee4bd15f4d94b12f7",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSemanticHandlerRegistry.kt":
        "2091f810b14a82adc92962f6a6ccd85ad3d20051199a7dcd08364769546f4806",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "721381b84ce5f5a0cac06c2fd0e9264a93496fc3e52523c8daa8d75f2fed707b",
    "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt":
        "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def totals(module: str) -> tuple[int, int, int, int]:
    result = [0, 0, 0, 0]
    for path in (ROOT / module / "build/test-results").glob("test*/TEST-*.xml"):
        suite = ET.parse(path).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            result[index] += int(suite.attrib.get(key, 0))
    return tuple(result)


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=False)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-gradle", action="store_true")
    parser.add_argument("--with-device", action="store_true")
    args = parser.parse_args()
    if args.with_gradle:
        completed = run(["./gradlew", "test", "lintDebug", "assembleDebug"])
        if completed.returncode:
            print(completed.stdout)
            print(completed.stderr, file=sys.stderr)
            return completed.returncode

    readiness = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSemanticRuntimeReadiness.kt").read_text()
    readiness_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextSemanticRuntimeReadinessTest.kt").read_text()
    schema = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSkillRuntimeSchemaV04.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    repository = (ROOT / "app/src/main/java/com/alarmquest/data/repository/GameRepository.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P4B_RUNTIME_READINESS_GATE_v0.1.md").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("readiness rules version", "aq.semantic-runtime-readiness.v0.1" in readiness, "v0.1")
    check("readiness content hash pinned", READINESS_HASH in readiness, READINESS_HASH)
    check("Registry242 exact audit count", "registryDefinitions == 242" in readiness and "assertEquals(242, report.registryDefinitions)" in readiness_test, "242")
    check("Active exact audit count", "assertEquals(152, report.activeDefinitions)" in readiness_test, "152")
    check("Passive exact audit count", "assertEquals(90, report.passiveDefinitions)" in readiness_test, "90")
    check("ready count remains zero", "V_NEXT_RUNTIME_READY_DEFINITION_COUNT: Int = 0" in readiness and "assertEquals(0, report.readyDefinitions)" in readiness_test, "0/242")
    check("live admission fails closed", "SemanticRuntimeAdmissionStatus.NO_GO" in readiness_test and "blockedDefinitionIds.size" in readiness_test, "NO_GO")
    check("no display-name inference", "refuses to infer" in readiness and "Korean names" in readiness, "forbidden")
    for phase, count in (
        ("ACTIVE_PAYLOAD", 36),
        ("STATUS_LIFECYCLE", 45),
        ("STATUS_CONSUMPTION", 12),
        ("COST_TRANSFER_DISPEL", 26),
        ("PREPARED_REACTION", 19),
        ("PROTECTION_DEATH", 33),
        ("PASSIVE_MODIFIER", 71),
    ):
        check(f"primary phase {phase}", f"assertEquals({count}, counts[SemanticPhase.{phase}])" in readiness_test, str(count))
    for gap, count in (
        ("DAMAGE_CHANNEL_UNDECLARED", 121),
        ("EFFECT_TARGET_UNDECLARED", 147),
        ("PATTERN_OPCODE_UNIMPLEMENTED", 36),
        ("SPECIALIST_FRAME_ADAPTER_MISSING", 116),
        ("CROSS_ROOT_STATE_NOT_SERIALIZED", 20),
        ("MULTI_PACKET_CHANNEL_UNDECLARED", 2),
        ("PASSIVE_TRIGGER_EXECUTOR_MISSING", 90),
        ("PASSIVE_RUNTIME_STATE_NOT_SERIALIZED", 90),
    ):
        check(
            f"gap {gap}",
            f"assertEquals({count}, report.gapCounts[SemanticRuntimeGap.{gap}])" in readiness_test,
            str(count),
        )

    check("v0.4 draft version", "aq.skill-runtime-schema.v0.4-draft.1" in schema, "draft.1")
    check("v0.4 draft hash pinned", SCHEMA_HASH in schema and SCHEMA_HASH in document, SCHEMA_HASH)
    for field in (
        "damageChannel", "carrierTarget", "runtimeOpcode", "triggerOpcode",
        "runtimeStateSchemaVersion", "secondaryRngSlots",
    ):
        check(f"required field {field}", f'"{field}"' in schema and f'`{field}`' in document, "declared")
    check("packet channel width fail closed", "PACKET_CHANNEL_WIDTH_MISMATCH" in schema, "validated")
    check("target width fail closed", "CARRIER_TARGET_WIDTH_MISMATCH" in schema, "validated")
    check("passive trigger fail closed", "MISSING_PASSIVE_TRIGGER" in schema and "PASSIVE_NONE_TRIGGER" in schema, "validated")
    check("state codec identity validated", "INVALID_STATE_SCHEMA" in schema and '"stateless"' in schema, "validated")
    check("secondary RNG identity validated", "DUPLICATE_RNG_SLOT" in schema and "drawCount !in 1..8" in schema, "validated")
    check("P4 resolver remains default OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement remains OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("legacy SettlementEngine unconnected", all(token not in settlement for token in (
        "VNextSemanticRuntimeReadiness", "VNextBattleResolverTransaction", "VNextSettlementReceiptGate",
    )), "not connected")
    check("repository has no live battle receipt cutover", all(token not in repository for token in (
        "VNextBattleTransaction", "VNextSettlementReceipt", "VNextSemanticRuntimeReadiness",
    )), "not connected")
    check("character reset not executed", "characterResetExecuted=false" in document, "false")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals("game-engine")
    app = totals("app")
    check("engine tests clean", engine == (282, 0, 0, 0), str(engine))
    check("app tests clean", app == (176, 0, 0, 0), str(app))
    lint = (ROOT / "app/build/reports/lint-results-debug.txt").read_text()
    check("lint clean", "0 errors, 22 warnings" in lint, "0 errors, 22 existing warnings")
    check("APK exists", APK.is_file(), str(APK))
    if APK.is_file():
        check("APK hash pinned", sha256(APK) == APK_HASH, sha256(APK))
        with zipfile.ZipFile(APK) as archive:
            check("Registry242 packaged", REGISTRY_RESOURCE in archive.namelist(), REGISTRY_RESOURCE)

    if args.with_device:
        devices = run(["adb", "devices", "-l"])
        check("SM-S931N online", "model:SM_S931N" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        version = run(["adb", "shell", "dumpsys", "package", "com.alarmquest"])
        check("device version code", "versionCode=1" in version.stdout, "1")
        check("device version name", "versionName=0.1.0" in version.stdout, "0.1.0")
        focus = run(["adb", "shell", "dumpsys", "activity", "activities"])
        focused_lines = [line.strip() for line in focus.stdout.splitlines() if "mFocusedApp=" in line]
        check("MainActivity focused", any("com.alarmquest/.MainActivity" in line for line in focused_lines), " | ".join(focused_lines))
        fatal = run(["adb", "shell", "logcat", "-d", "-t", "300", "AndroidRuntime:E", "*:S"])
        check("no AndroidRuntime fatal", not fatal.stdout.strip(), "fatal=0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P4b PD: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
