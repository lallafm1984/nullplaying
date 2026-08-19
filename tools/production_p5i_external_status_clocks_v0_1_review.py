#!/usr/bin/env python3
"""PD audit for P5i charge consumption and expedition lodging clocks."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "772fa729548b02cd2f7a2e02430399853219f67964fa636f9b0f1d23170a28f9"
APK_SIZE = 37_073_250
CONTRACT_HASH = "c9871d62100b56fa1309650ebdcdb5f0bccb6ff0142b4582c02278bff4aba0ea"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextExternalStatusClockBridgeP5i.kt":
        "f5d46cbd6b087fc72a04ce852767b3ca7523d84d8e23fb55d93f34b7eb21fa0f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt":
        "1c102a3d60de27fdb3c23e0a138b00c4ecf7957af5685708f841e91dc3e62e1b",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt":
        "ba0f8e63a0932ec4731b36af7cd3945da0d1ff445d92f3b9442cff92c10bdb3a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "0ec38aab7a16dba3c26c47ff19d577fd20aa6b7339910232679265f02224922e",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDirectExecutionCarrierAdapterP5b.kt":
        "6eff1b06d587c3865559f649fd8c6c20eff1b8be573fdf29b41bd71655204822",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5d.kt":
        "91d30a6a4ab92c89ccdc8edffbf2ea89dbe017ebd1919e1c6aaa5dc9e107dde6",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusMutationSemanticAdapterP5h.kt":
        "15cb9f567f14c12f0da5fd1005717fe0d2ce73d51e158de50241038087693ad2",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextExternalStatusClockBridgeP5iTest.kt":
        "78cb3085b4382a8c26aaae2e0bd8e319d761ff528d2d360084a361c9b4828283",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "c241b06665a5f5aae6760211efbae8f2b280663857a1cf66630b336330b9dabe",
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

    bridge = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextExternalStatusClockBridgeP5i.kt").read_text()
    packet = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt").read_text()
    lifecycle = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    p5b = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDirectExecutionCarrierAdapterP5b.kt").read_text()
    p5d = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5d.kt").read_text()
    p5h = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusMutationSemanticAdapterP5h.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextExternalStatusClockBridgeP5iTest.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5I_EXTERNAL_STATUS_CLOCKS_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5i-emulator-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("P5i rules", "aq.external-status-clocks.p5i.v0.1" in bridge, "v0.1")
    check("P5i hash", CONTRACT_HASH in bridge and CONTRACT_HASH in document, CONTRACT_HASH)
    check("two primers", "V_NEXT_P5I_CHARGE_PRIMER_COUNT: Int = 2" in bridge, "2")
    check("two charge tags", "V_NEXT_P5I_CHARGE_TAG_COUNT: Int = 2" in bridge, "2")
    check("live OFF", "val liveReady: Boolean get() = false" in bridge, "OFF")
    check("blessing contract", "aq.skill.cleric.w2.blessing" in bridge and "HIT_FLOOR_ACTIVE_ANCHOR" in bridge, "pinned")
    check("battle cry contract", "aq.skill.common.w2.battlecry" in bridge and "BASIC_COEFFICIENT_STATUS_MAGNITUDE" in bridge, "pinned")
    check("P5c correction gate", "VNextRuntimeCorrectionLedgerP5c.compile" in bridge, "fail closed")
    check("P5c status gate", "VNextStatusRuntimeCatalogP5c.compile" in bridge, "fail closed")
    check("descriptor gate", "P5I_DESCRIPTOR_CONTRACT_MISMATCH" in bridge, "fail closed")
    check("status gate", "P5I_STATUS_CONTRACT_MISMATCH" in bridge, "fail closed")
    check("unsupported definition", "UNSUPPORTED_DEFINITION" in bridge, "fail closed")
    check("unsupported passive", "PASSIVE_ADAPTERS_REQUIRED" in bridge, "fail closed")
    check("primer replay", "REPLAY_RECEIPT" in bridge, "exact once")
    check("zero damage primer", "hpDamage" not in bridge.split("class VNextChargePrimerSemanticAdapterP5i", 1)[1].split("companion object", 1)[0], "support only")
    check("blessing active anchor", "activePlan.resolvedAnchorValue" in bridge, "SkillLv bound")
    check("battle cry magnitude", "application.magnitudeBps" in bridge, "SkillLv bound")
    check("two charges first apply", 'application.stackPolicy == "TWO_CHARGES"' in lifecycle, "stacks=2")

    check("charge reservation instance", "val instanceId: String" in bridge, "bound")
    check("charge reservation version", "val expectedVersion: Int" in bridge, "CAS")
    check("charge reservation tag", "val tag: String" in bridge, "bound")
    check("blessing attack gate", 'it.tag == "BLESSING"' in bridge and "takeIf { attackAction }" in bridge, "attack only")
    check("focus basic gate", 'it.tag == "BASIC_FOCUS"' in bridge and "P5iCommittedActionKind.BASIC" in bridge, "BASIC only")
    check("unknown charge fail closed", "filterNot(::validCharge)" in bridge, "invalid plan")
    check("blessing value range", "status.magnitudeBps in 8_000..9_500" in bridge, "bounded")
    check("focus value range", "status.magnitudeBps in 3_500..4_500" in bridge, "bounded")
    check("stack remaining invariant", "status.stacks == status.remaining" in bridge, "bounded")
    check("post host CAS", "eligibleAfterCommittedAction" in bridge and "it.version == reservation.expectedVersion" in bridge, "double spend blocked")

    check("packet v0.2", "aq.combat-packet.p5i.v0.2" in packet, "v0.2")
    check("packet hit floor field", "val hitFloorBps: Int?" in packet, "optional")
    check("packet floor range", "request.hitFloorBps in 0..10_000" in packet, "bounded")
    check("packet max floor", "maxOf(baseHitBps, request.hitFloorBps ?: 0)" in packet, "never lowers")
    check("semantic request floor", "val attackHitFloorBps: Int?" in resolver, "root snapshot")
    check("P5b floor", "hitFloorBps = request.attackHitFloorBps" in p5b, "wired")
    check("P5d floor", "hitFloorBps = request.attackHitFloorBps" in p5d, "wired")
    check("P5h floor", "hitFloorBps = request.attackHitFloorBps" in p5h, "wired")
    check("BASIC coefficient add", "10_000 + chargePlan.basicCoefficientAddBps" in resolver, "wired")
    check("BASIC floor", "hitFloorBps = chargePlan.hitFloorBps" in resolver, "wired")
    check("Active attack classification", "attackAction = activePlan.hitPackets > 0" in resolver, "wired")
    check("charge system root", "P5eStatusSystemRoot.CHARGE_CONSUMED" in resolver, "exact once root")
    check("charge receipt detail", "P5I:hitFloor=" in resolver, "auditable")

    check("lodging request ids", "val expeditionId: String" in bridge and "val lodgingReceiptId: String" in bridge, "durable boundary")
    check("lodging root namespace", '"${request.expeditionId}:lodging:${request.lodgingReceiptId}"' in bridge, "exact once")
    check("lodging root", "P5eStatusSystemRoot.EXPEDITION_LODGING" in bridge, "wired")
    check("lodging eligible snapshot", 'it.clock == "EXPEDITION_LODGING"' in bridge, "isolated")
    check("lodging state hashes", "beforeHash" in bridge and "afterHash" in bridge, "auditable")
    check("lodging receipt hash", "val receiptHash: String" in bridge, "bound")

    for phrase in (
        "coverage pins two primers two charge tags and keeps live off",
        "blessing stores its active skill hit floor as two canonical charges",
        "battle cry keeps its own status magnitude and refreshes exactly two charges",
        "charge policy separates attack hit floor from basic coefficient and rejects unknown clocks",
        "one active root consumes only reserved blessing while basic focus stays intact",
        "host semantic version change prevents generic charge double consumption",
        "hit floor raises a low accuracy attack without changing automatic or derived formulas",
        "lodging receipt expires only expedition lodging costs and replay is exact once",
    ):
        check(f"test {phrase[:27]}", phrase in tests, "covered")
    check(
        "resolver P5i integration test",
        "p5i battle cry primes two basic charges and resolver consumes them on committed basics" in resolver_tests,
        "covered",
    )

    check("resolver feature OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("Application P5i unconnected", "VNextExternalStatusClockBridgeP5i" not in application, "Room no-go")
    check("legacy settlement P5i unconnected", "VNextExternalStatusClockBridgeP5i" not in settlement, "legacy safe")
    check("legacy resolveKill remains", "resolveKill" in settlement, "unchanged")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (366, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint_root = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    lint_errors = sum(issue.attrib.get("severity") == "Error" for issue in lint_root.findall("issue"))
    lint_warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint_root.findall("issue"))
    check("lint", (lint_errors, lint_warnings) == (0, 22), f"{lint_errors} errors, {lint_warnings} warnings")
    check("APK exists", APK.is_file(), str(APK))
    if APK.is_file():
        check("APK hash", sha256(APK) == APK_HASH, sha256(APK))
        check("APK size", APK.stat().st_size == APK_SIZE, str(APK.stat().st_size))

    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=1006", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "liveSettlementEnabled=false",
        "roomStatusPersistenceEnabled=false",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("emulator booted", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest"])
        check("emulator app version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0 (1)")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("emulator focus", "topResumedActivity" in focus.stdout and "com.alarmquest/.MainActivity" in focus.stdout, "MainActivity")
        run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5i-review-ui.xml"])
        ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5i-review-ui.xml"])
        check("emulator empty roster", "아직 캐릭터가 없습니다" in ui.stdout and "새 캐릭터" in ui.stdout, "fresh roster")
        logcat = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"])
        fatal = any(token in logcat.stdout for token in ("FATAL EXCEPTION", "AndroidRuntime: FATAL"))
        check("emulator fatal zero", not fatal, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5i PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
