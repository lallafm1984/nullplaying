#!/usr/bin/env python3
"""PD audit for the P5g canonical status mutation ledger."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "a12915fecfa4e833a6a7e31ef5fdb95077ab9ccba48f2907f2d16e7b835d272a"
APK_SIZE = 37_069_793
LEDGER_HASH = "6a94cfd675efbc50c710f813bb8e5871fa33632cf63647496ac15430c2494785"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusMutationLedgerP5g.kt":
        "049bcd1a089880a245d0a0ce1902f91f9e0227e324d469f9d72f94581ba5f1a4",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt":
        "3c1477724d5835f1a44ca94ee2007735872955a416ee3404439a7c11ced7dff4",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusConsumptionHandlers.kt":
        "272fc950d02b29654707c58eb2eff04d6df6cf133689be10adcb9a8fceacefa5",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextCostTransferDispelHandlers.kt":
        "9834a03b316c24b65873728ff68504dd5f7ca6197b3a13320f49d6742507679b",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextStatusMutationLedgerP5gTest.kt":
        "84a650878315f3e3f24cb41d345fd130230dc9c787339d6c6103a946211922c3",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlersTest.kt":
        "e3ed856b9d316971b414799054987f09c9f63c78d4edd09d1add2f9897aeb4e8",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextStatusConsumptionHandlersTest.kt":
        "64f08e5d5395446340c9bc3ebb24582e81d723bc6dd0407211f017a5e4f82ecf",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextCostTransferDispelHandlersTest.kt":
        "8b417710c4bef1f059c591491113a473660d74772a96c043363b517dbfbd7211",
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
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    if args.with_gradle:
        completed = run(["./gradlew", "test", "lintDebug", "assembleDebug"])
        if completed.returncode:
            print(completed.stdout)
            print(completed.stderr, file=sys.stderr)
            return completed.returncode

    ledger = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusMutationLedgerP5g.kt").read_text()
    lifecycle = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt").read_text()
    ledger_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextStatusMutationLedgerP5gTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5G_STATUS_MUTATION_LEDGER_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5g-emulator-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("P5g rules", "aq.status-mutation-ledger.p5g.v0.1" in ledger, "v0.1")
    check("P5g hash", LEDGER_HASH in ledger and LEDGER_HASH in document, LEDGER_HASH)
    check("ten consumers", "V_NEXT_P5G_STATUS_CONSUMER_ACTIVE_COUNT: Int = 10" in ledger, "10")
    check("three cleanses", "V_NEXT_P5G_STATUS_CLEANSE_ACTIVE_COUNT: Int = 3" in ledger, "3")
    check("one transfer", "V_NEXT_P5G_STATUS_TRANSFER_ACTIVE_COUNT: Int = 1" in ledger, "1")
    check("two passives", "V_NEXT_P5G_STATUS_CONSUMPTION_PASSIVE_COUNT: Int = 2" in ledger, "2")
    check("dangling gate", "danglingDefinitionIds.isEmpty()" in ledger, "fail closed")
    check("invalid contract gate", "invalidDefinitionIds.isEmpty()" in ledger, "fail closed")
    check("live OFF", "val liveReady: Boolean get() = false" in ledger, "OFF")

    for field in ("party", "instanceId", "expectedVersion", "reservedStacks"):
        check(f"reservation {field}", f"val {field}:" in ledger, "stored")
    check("actor and target states", "val actorStatus: LifecycleState" in ledger and "val targetStatus: LifecycleState" in ledger, "canonical")
    check("state codec hash", "VNextStatusStateCodecP5c.stateHash(state.actorStatus)" in ledger, "bound")
    check("receipt replay guard", "request.receiptId in state.actorStatus.receipts" in ledger, "both parties")
    check("consumer bridge", "VNextStatusConsumptionHandlers.execute" in ledger, "10 actives")
    check("cleanse bridge", "VNextStatusLifecycleHandlers.cleanse" in ledger, "3 actives")
    check("transfer branch", "executeTransfer" in ledger and 'clock = "EXPEDITION_LODGING"' in ledger, "1 active")
    check("version stack CAS", "current.version == reservation.expectedVersion" in ledger, "guarded")
    check("partial stack version", "version = current.version + 1" in ledger, "incremented")
    check("linked removal", "linkedEffects = state.linkedEffects - removedGroups" in ledger, "atomic")
    check("linked transfer", "linkedEffects = actor.linkedEffects + moved.linkedGroup" in ledger, "moved")
    check("self burn on miss", "applyDragonSelfBurn" in ledger and 'clock = "SELF_ROOT_END"' in ledger, "cost committed")
    check("passive equip gate", "ELEMENTAL_RESIDUE_ID in request.equippedPassiveIds" in ledger, "explicit")
    check("well echo equip gate", "WELL_ECHO_ID in request.equippedPassiveIds" in ledger, "explicit")
    check("passive slot cap", "request.equippedPassiveIds.size <= 3" in ledger, "3")

    check("cleanse selected IDs", "val selectedInstanceIds: List<String>" in lifecycle, "auditable")
    check("cleanse removed IDs", "val removedInstanceIds: List<String>" in lifecycle, "auditable")
    check("cleanse cost locked", "!it.costLocked" in lifecycle, "protected")
    check("cleanse priority lethal", "projectedDamageByInstance" in lifecycle and "currentHp" in lifecycle, "deterministic")
    check("cleanse linked CAS", "linkedEffects = committed.linkedEffects - linkedGroups" in lifecycle, "atomic")

    for phrase in (
        "landed consumption commits linked status while complete miss rolls reservation back",
        "partial shock consume increments version and equipped passives obey resource ceiling",
        "well echo recovers once only when an equipped action commits chill consumption",
        "cleanse uses deterministic lethal priority and commits cost receipt and linked removal",
        "failed probabilistic cleanse rolls status back but still spends its action cost",
        "curse transfer preserves payload and identity while changing owner clock and cost lock",
        "candidate and resource rejection never write a receipt or mutate state",
        "dragon self burn commits on miss and deterministic inputs freeze the same receipt hash",
    ):
        check(f"test {phrase[:24]}", phrase in ledger_test, "covered")

    for receipt_field in (
        "request.skillLevel", "request.packetHits", "request.equippedPassiveIds", "beforeHash",
        "afterHash", "coefficientBps", "packetCoefficients", "resourceSpentBps",
        "resourceRecoveredBps", "selectedTags", "finalCleanseBps",
    ):
        check(f"receipt binds {receipt_field}", receipt_field in ledger, "bound")

    check("resolver feature OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("resolver adapter unconnected", "VNextStatusMutationLedgerP5g" not in resolver, "not connected")
    check("Application unconnected", "VNextStatusMutationLedgerP5g" not in application, "not connected")
    check("legacy settlement unconnected", "VNextStatusMutationLedgerP5g" not in settlement, "not connected")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (345, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = (ROOT / "app/build/reports/lint-results-debug.txt").read_text()
    check("lint", "0 errors, 22 warnings" in lint, "0 errors, 22 existing warnings")
    check("APK exists", APK.is_file(), str(APK))
    if APK.is_file():
        check("APK hash", sha256(APK) == APK_HASH, sha256(APK))
        check("APK size", APK.stat().st_size == APK_SIZE, str(APK.stat().st_size))

    check("emulator audit", "verificationTarget=android-emulator" in audit and "avdName=alarmquest-qa" in audit, "alarmquest-qa")
    check("emulator API", "androidRelease=15" in audit and "apiLevel=35" in audit, "15 API 35")
    check("emulator install", "installResult=Success" in audit and "pmClearResult=Success" in audit, "Success")
    check("fresh roster", "freshRosterEmpty=true" in audit and "아직 캐릭터가 없습니다" in audit, "empty")
    check("cold focus", "launchState=COLD" in audit and "mFocusedApp=com.nullplaying/.MainActivity" in audit, "focused")
    check("fatal zero", "androidRuntimeFatalCount=0" in audit, "0")

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("emulator booted", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying"])
        check("emulator app version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        activities = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("emulator focus", "com.nullplaying/.MainActivity" in activities.stdout, "MainActivity")
        fatal = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-t", "500", "AndroidRuntime:E", "*:S"])
        check("emulator fatal zero", not fatal.stdout.strip(), "fatal=0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5g PD: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
