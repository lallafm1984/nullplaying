#!/usr/bin/env python3
"""PD audit for P5c correction ledger, status runtime plans, codec, and live gates."""

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
APK_HASH = "5f40f7a90090a84c2c637de406e57bf9237bc85377b53e80defc52f583eafb6f"
CORRECTION_HASH = "18bbc151cf29f0a686e568e43692e10cce85961470f602a9d9c17ac7561073a2"
CODEC_HASH = "a6aaedb3419578e040d2259a4307ccdd617beaeef6c3e0e46b69c6307677c897"
STATUS_HASH = "5bcffc633bbb77f8fe86331299f7f673b103d1615b3a28f4f2d531aaf13898ec"
REGISTRY_RESOURCE = "com/alarmquest/engine/vnext/skill_registry_242_v0_3.json.gz.b64"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextRuntimeCorrectionLedgerP5c.kt":
        "9d8db70a53281cd22a41e618be280a80f86a97eb7e2e4d7ab64d885f0ce71df6",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextRuntimeCorrectionLedgerP5cTest.kt":
        "7714fb1ecc25a23be1751541070d40884abdc4c2e0e7e6a4db298cba210ba273",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusRuntimeCatalogP5c.kt":
        "667c5bae844270b98f4ecf8cb5f241d4b3d013271118418be3494446032d722f",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextStatusRuntimeCatalogP5cTest.kt":
        "7a16298712a8c94e1da2aefdb0b826f8887ea2c38c3f1f549784ca6756980f19",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt":
        "ec435c561405b6e743790a8b00ef0097a3db60e31fe7997dfb36140054b897f7",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "516ca95d3908a25152ec479f3476493797d60e5282da9cd94cf5cbf7faee37a0",
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

    correction = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextRuntimeCorrectionLedgerP5c.kt").read_text()
    correction_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextRuntimeCorrectionLedgerP5cTest.kt").read_text()
    status = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusRuntimeCatalogP5c.kt").read_text()
    status_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextStatusRuntimeCatalogP5cTest.kt").read_text()
    lifecycle = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    repository = (ROOT / "app/src/main/java/com/alarmquest/data/repository/GameRepository.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5C_CORRECTION_STATUS_RUNTIME_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5c-device-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("correction rules version", "aq.runtime-correction-ledger.p5c.v0.1" in correction, "v0.1")
    check("correction hash pinned", CORRECTION_HASH in correction and CORRECTION_HASH in document, CORRECTION_HASH)
    check("twenty five corrections", "V_NEXT_P5C_CORRECTION_COUNT: Int = 25" in correction and "assertEquals(25" in correction_test, "25")
    check("eighteen corrected definitions", "V_NEXT_P5C_CORRECTED_DEFINITION_COUNT: Int = 18" in correction and "assertEquals(18" in correction_test, "18")
    for kind, count in (
        ("criticalCorrections", 13), ("packetCorrections", 1), ("profileCorrections", 3),
        ("hitPolicyCorrections", 5), ("resourceCorrections", 2), ("coefficientCorrections", 1),
    ):
        check(f"correction count {kind}", f"{kind} == {count}" in correction, str(count))
    check("prepared motion modifier only", "SELF_MODIFIER_ONLY" in correction and "aq.skill.common.w3.preparedmotion" in correction, "fixed")
    check("last shot direct resource finisher", "PHYSICAL_DIRECT_RESOURCE_FINISHER" in correction and "CURRENT_TO_ZERO_AFTER_USE" in correction, "fixed")
    check("taunt proxy damage removed", "ENEMY_MODIFIER_ONLY" in correction and 'carrierId == "primary"' in correction, "fixed")
    check("rapid jabs packet correction", '"aq.skill.common.w8.rapidjabs", P5cCorrectionKind.PACKET_COUNT' in correction, "3")
    check("golem coefficient correction", "P5cCoefficientSource.ANCHOR" in correction, "anchor")
    check("wyvern fixed critical result", "FORCED_FIXED_RESULT" in correction and "fixedHitBps = 8_000" in correction, "8000")
    check("moonless automatic hit", "automaticHit = true" in correction, "automatic")
    check("corrected descriptors schema valid", "VNextSkillRuntimeSchemaV04Draft2.validate(it)" in correction, "validated")
    check("correction foundation live split", "assertTrue(catalog.coverage.foundationReady)" in correction_test and "assertFalse(catalog.coverage.liveReady)" in correction_test, "GO NO-GO")

    check("status rules version", "aq.status-runtime-catalog.p5c.v0.1" in status, "v0.1")
    check("status hash pinned", STATUS_HASH in status and STATUS_HASH in document, STATUS_HASH)
    for name, value in (("SOURCE", 51), ("APPLICATION", 55), ("ROLL", 47)):
        check(f"status {name} count", f"V_NEXT_P5C_STATUS_{name}_COUNT: Int = {value}" in status, str(value))
    check("status PERIODIC_PAYLOAD count", "V_NEXT_P5C_PERIODIC_PAYLOAD_COUNT: Int = 3" in status, "3")
    check("guaranteed seven", "guaranteedApplications == 7" in status, "7")
    check("transfer one", "transferApplications == 1" in status, "1")
    check("descriptor carrier parity", "descriptorStatusCarriers == V_NEXT_P5C_STATUS_APPLICATION_COUNT" in status and "descriptorPeriodicCarriers == V_NEXT_P5C_PERIODIC_PAYLOAD_COUNT" in status, "55 and 3")
    check("skill level interpolation", "val anchors = listOf(1, 25, 50, 75, 100)" in status, "1..100")
    check("keyed secondary RNG", '"status.${it.tag.lowercase()}.$index"' in status and "SECONDARY_RNG_SLOTS_MISMATCH" in status, "keyed")
    check("keyed RNG test", "keyed secondary RNG rejects missing and shifted status draws" in status_test, "covered")
    check("unsupported status fail closed", "STATUS_SOURCE_UNSUPPORTED" in status, "closed")
    check("periodic interpolation tests", all(token in status_test for token in ("emberbottle", "poisonneedle", "bleedingcut")), "3 payloads")

    check("codec schema version", 'V_NEXT_STATUS_STATE_SCHEMA_VERSION = "aq.state.combat.v1"' in lifecycle, "v1")
    check("codec rules version", 'V_NEXT_STATUS_STATE_CODEC_RULES_VERSION = "aq.state.combat-codec.p5c.v0.1"' in lifecycle, "v0.1")
    check("codec hash pinned", CODEC_HASH in lifecycle and CODEC_HASH in document, CODEC_HASH)
    check("strict codec", all(token in lifecycle for token in ("ignoreUnknownKeys = false", "isLenient = false", "coerceInputValues = false")), "strict")
    check("canonical state order", "statuses.sortedBy" in lifecycle and "toSortedSet()" in lifecycle, "canonical")
    check("state hash", "fun stateHash" in lifecycle, "SHA-256")
    check("codec round trip test", "state codec is canonical strict and process replay stable" in status_test, "covered")
    check("hostile and stack caps", all(token in lifecycle for token in ("V_NEXT_LIFECYCLE_HOSTILE_CAP = 6", "V_NEXT_LIFECYCLE_SHOCK_STACK_CAP = 2", "V_NEXT_LIFECYCLE_CURSE_SUBTYPE_CAP = 3")), "6 2 3")
    check("resistance floor ceiling", "V_NEXT_LIFECYCLE_APPLY_MIN_BPS = 1_000" in lifecycle and "V_NEXT_LIFECYCLE_APPLY_MAX_BPS = 9_500" in lifecycle, "1000..9500")
    check("boss control cap", "V_NEXT_LIFECYCLE_BOSS_CONTROL_MAX_BPS = 4_750" in lifecycle, "4750")
    check("barrier package gate", "LifecycleApplyOutcome.BARRIER_BLOCKED" in lifecycle, "blocked")
    check("immunity aliases", '"AIM_DISRUPTED" to "BLIND"' in lifecycle and '"SLIMED" to "BIND"' in lifecycle, "2 aliases")
    check("exact DOT split", "internal fun splitExact" in lifecycle, "front remainder")
    check("shield before HP", "val absorbed = minOf(shield, damage)" in lifecycle, "ordered")
    check("seven clock events", "internal enum class LifecycleClockEvent" in lifecycle and "EXPEDITION_LODGING" in lifecycle and "CHARGE_CONSUMED" in lifecycle, "7")
    check("clock replay gate", "replayIgnored = true" in lifecycle and "clock advancement expires linked state once" in status_test, "idempotent")
    check("linked expiry atomic", "linkedEffects = committed.linkedEffects - expiredGroups" in lifecycle, "atomic")

    check("resolver feature default OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("Application unconnected", "VNextStatusRuntimeCatalogP5c" not in application and "VNextRuntimeCorrectionLedgerP5c" not in application, "not connected")
    check("SettlementEngine unconnected", "VNextStatusRuntimeCatalogP5c" not in settlement and "VNextStatusStateCodecP5c" not in settlement, "not connected")
    check("repository default off", "V_NEXT_SKILL_BUILD_FEATURE_DEFAULT_ENABLED" in repository, "OFF")
    check("no legacy deletion", "legacyDeletionExecuted=false" in document and "legacyDeletionExecuted=false" in audit, "false")
    check("no character reset", "characterResetExecuted=false" in document and "characterResetExecuted=false" in audit, "false")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals("game-engine")
    app = totals("app")
    check("engine tests clean", engine == (311, 0, 0, 0), str(engine))
    check("app tests clean", app == (176, 0, 0, 0), str(app))
    lint = (ROOT / "app/build/reports/lint-results-debug.txt").read_text()
    check("lint clean", "0 errors, 22 warnings" in lint, "0 errors, 22 existing warnings")
    check("APK exists", APK.is_file(), str(APK))
    if APK.is_file():
        check("APK hash pinned", sha256(APK) == APK_HASH, sha256(APK))
        check("APK size frozen", APK.stat().st_size == 37_028_254, str(APK.stat().st_size))
        with zipfile.ZipFile(APK) as archive:
            check("Registry242 packaged", REGISTRY_RESOURCE in archive.namelist(), REGISTRY_RESOURCE)

    check("device audit hash", f"apkSha256={APK_HASH}" in audit, APK_HASH)
    check("device audit cold launch", "launchState=COLD" in audit and "launchTotalTimeMs=482" in audit, "482ms")
    check("device audit focus", "resumedActivity=com.alarmquest/.MainActivity" in audit and "mFocusedApp=com.alarmquest/.MainActivity" in audit, "focused")
    check("device audit fatal zero", "androidRuntimeFatalCount=0" in audit, "0")

    if args.with_device:
        devices = run(["adb", "devices", "-l"])
        check("SM-S931N online", "model:SM_S931N" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        version = run(["adb", "shell", "dumpsys", "package", "com.alarmquest"])
        check("device version code", "versionCode=1" in version.stdout, "1")
        check("device version name", "versionName=0.1.0" in version.stdout, "0.1.0")
        activities = run(["adb", "shell", "dumpsys", "activity", "activities"])
        focused = [line.strip() for line in activities.stdout.splitlines() if "ResumedActivity:" in line or "mFocusedApp=" in line]
        check("MainActivity focused", any("com.alarmquest/.MainActivity" in line for line in focused), " | ".join(focused))
        fatal = run(["adb", "logcat", "-d", "-t", "400", "AndroidRuntime:E", "*:S"])
        check("no AndroidRuntime fatal", not fatal.stdout.strip(), "fatal=0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5c PD: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
