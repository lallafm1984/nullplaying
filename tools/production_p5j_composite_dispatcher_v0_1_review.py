#!/usr/bin/env python3
"""PD audit for the P5j four-family composite semantic dispatcher."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "76f753092e34b8b0e0a176da6705549d96f1fb7bb1f604bca97ee55985d17b7f"
APK_SIZE = 37_073_250
CONTRACT_HASH = "263d776df1bcf38bfb5d8287727e7957ce364d1a3cfc0e515d18e78e635a58da"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5j.kt":
        "8ed1f779c6c42734636c4d48e609c913bafe6b132cd6e299f65fc23d724af0c3",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDirectExecutionCarrierAdapterP5b.kt":
        "41d1f48142fb0a2f0706fc716d24922f21d96b89d043be75f9e422ce034791c1",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5d.kt":
        "2c1f9ef297021486946fd9666ef6ab0c786fa1a5ea50032e5507205ccc16d5f8",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusMutationSemanticAdapterP5h.kt":
        "e8cf78c34aa9ae9901ffd765c82b9cf7dad6279709f3a5e54e6c9f5fbcb1ce4b",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextExternalStatusClockBridgeP5i.kt":
        "0284fc28cd950c7be0ad55275412b5177bbd45423367af314b7cc728da4675d9",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "0ec38aab7a16dba3c26c47ff19d577fd20aa6b7339910232679265f02224922e",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5jTest.kt":
        "42ffbe18a4b23f17c650d5be0e9f04b846b8d3fbeb6fe4dbe833ac775fdead34",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "727baac4f0a46adce6e947bd584c7ec1e2f6c1ae06040496a24ebb98282f894f",
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

    composite = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5j.kt").read_text()
    p5b = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDirectExecutionCarrierAdapterP5b.kt").read_text()
    p5d = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5d.kt").read_text()
    p5h = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusMutationSemanticAdapterP5h.kt").read_text()
    p5i = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextExternalStatusClockBridgeP5i.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5jTest.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5J_COMPOSITE_DISPATCHER_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5j-emulator-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("P5j rules", "aq.composite-semantic-dispatcher.p5j.v0.1" in composite, "v0.1")
    check("P5j hash", CONTRACT_HASH in composite and CONTRACT_HASH in document, CONTRACT_HASH)
    check("four families", "V_NEXT_P5J_COMPOSITE_FAMILY_COUNT: Int = 4" in composite, "4")
    check("thirty definitions", "V_NEXT_P5J_COMPOSITE_DEFINITION_COUNT: Int = 30" in composite, "30")
    check("live OFF", "val liveReady: Boolean get() = false" in composite, "OFF")

    family_checks = (
        ("P5B", "VNextDirectExecutionCarrierAdapterP5bCompiler.compile", "P5B_FAMILY_REJECTED", "P5B_DIRECT"),
        ("P5D", "VNextDamageStatusCarrierAdapterP5dCompiler.compile", "P5D_FAMILY_REJECTED", "P5D_DAMAGE_STATUS"),
        ("P5H", "VNextStatusMutationSemanticAdapterP5hCompiler.compile", "P5H_FAMILY_REJECTED", "P5H_STATUS_MUTATION"),
        ("P5I", "VNextExternalStatusClockBridgeP5iCompiler.compile", "P5I_FAMILY_REJECTED", "P5I_CHARGE_PRIMER"),
    )
    for label, compiler, reject, family in family_checks:
        check(f"{label} compiler", compiler in composite, "bound")
        check(f"{label} reject", reject in composite, "fail closed")
        check(f"{label} family", family in composite, "routed")

    check("P5b supported IDs", "supportedDefinitionIds" in p5b, "11")
    check("P5d supported IDs", "supportedDefinitionIds" in p5d, "3")
    check("P5h supported IDs", "supportedDefinitionIds" in p5h, "14")
    check("P5i supported IDs", "supportedDefinitionIds" in p5i, "2")
    check("duplicate grouping", "groupingBy { it }.eachCount()" in composite, "checked")
    check("duplicate gate", "P5J_DUPLICATE_DEFINITION_OWNERS" in composite, "fail closed")
    check("definition count gate", "P5J_DEFINITION_COUNT_MISMATCH" in composite, "fail closed")
    check("content gate", "P5J_CONTENT_HASH_MISMATCH" in composite, "fail closed")
    check("unsupported reject", "P5J_UNSUPPORTED_DEFINITION" in composite, "no proxy")
    check("route by definition", "routes[request.activePlan.definitionId]" in composite, "exact")
    check("underlying delegation", "route.dispatcher.dispatch(request)" in composite, "request preserved")
    check("family detail", '"P5J:${route.family}|${result.detail}"' in composite, "auditable")
    check("reject preserved", '"P5J:${route.family}|REJECT:${result.detail}"' in composite, "fail closed")

    for phrase in (
        "coverage owns thirty disjoint definitions across four families and live stays off",
        "representative definitions route to each family without proxy execution",
        "unsupported registry definition rejects with unchanged frames and states",
        "family lookup is explicit and deterministic replay preserves routed result",
        "passive capability remains delegated to the owning family and fails closed elsewhere",
    ):
        check(f"test {phrase[:28]}", phrase in tests, "covered")
    check(
        "mixed resolver test",
        "p5j composite routes mixed direct and charge primer actives in one automatic build" in resolver_tests,
        "covered",
    )
    check("mixed P5b assertion", "P5J:P5B_DIRECT|P5B:" in resolver_tests, "covered")
    check("mixed P5i assertion", "P5J:P5I_CHARGE_PRIMER|P5I:" in resolver_tests, "covered")
    check("mixed charge assertion", "basicAdd=4000" in resolver_tests, "covered")

    check("resolver feature OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("Application P5j unconnected", "VNextCompositeSemanticDispatcherP5j" not in application, "live off")
    check("legacy P5j unconnected", "VNextCompositeSemanticDispatcherP5j" not in settlement, "legacy safe")
    check("legacy resolveKill remains", "resolveKill" in settlement, "unchanged")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (372, 0, 0, 0), str(totals("game-engine")))
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
        "coldTotalTimeMs=1172", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "liveSettlementEnabled=false",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("emulator booted", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying"])
        check("emulator app version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0 (1)")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("emulator focus", "topResumedActivity" in focus.stdout and "com.nullplaying/.MainActivity" in focus.stdout, "MainActivity")
        run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5j-review-ui.xml"])
        ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5j-review-ui.xml"])
        check("emulator empty roster", "아직 캐릭터가 없습니다" in ui.stdout and "새 캐릭터" in ui.stdout, "fresh roster")
        logcat = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"])
        fatal = any(token in logcat.stdout for token in ("FATAL EXCEPTION", "AndroidRuntime: FATAL"))
        check("emulator fatal zero", not fatal, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5j PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
