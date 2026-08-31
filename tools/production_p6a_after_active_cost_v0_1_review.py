#!/usr/bin/env python3
"""PD audit for P6a AFTER_ACTIVE_COST Mana Echo and emulator evidence."""
from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
SCREENSHOT = ROOT / "artifacts/audit/production-p6a-emulator-screen-v0.1.png"
UI_DUMP = ROOT / "artifacts/audit/production-p6a-emulator-ui-v0.1.xml"
APK_HASH = "9c382e681d896af83ea409addb3942794265d6d048d9dd5442281bb4acdaaf5d"
SCREENSHOT_HASH = "87506bed8df5e00bdcb473fadc99f040ec597e22621a520810bf8a62309a959f"
UI_DUMP_HASH = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
CONTRACT_HASH = "ef678c03eb5bf9d6fe8d2d8fae89316a7cc0a1c63ed2796261710eeb04391034"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterActiveCostRuntimeP6a.kt":
        "7fcbee9807ed2ce3f445619112d66b5698c0fcc7c6b1a7e77207840bfe25c3b3",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterActiveCostRuntimeP6aTest.kt":
        "fbe8e997738a08e982161179e108fc3966c8a875213e09484242c3117c64dc27",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeActiveCostRuntimeP5z.kt":
        "98453ea0aee101664f3ab6ebad9ecbe32648f40d7925316c484fd77140b6ce02",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "03553f6f25bab527f4a41ac8c174c230720ead959cde05f91666e0b0faa1efea",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "45d9a7eb4feaa921e52f43a1373c93d43c5aa49b826e78d2d17772b812beed0a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt":
        "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=False)


def totals(module: str) -> tuple[int, int, int, int]:
    values = [0, 0, 0, 0]
    for path in (ROOT / module / "build/test-results").glob("test*/TEST-*.xml"):
        root = ET.parse(path).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            values[index] += int(root.attrib.get(key, 0))
    return tuple(values)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-gradle", action="store_true")
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    if args.with_gradle:
        result = run(["./gradlew", "test", "lintDebug", "assembleDebug"])
        if result.returncode:
            print(result.stdout)
            print(result.stderr, file=sys.stderr)
            return result.returncode

    runtime = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterActiveCostRuntimeP6a.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterActiveCostRuntimeP6aTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P6A_AFTER_ACTIVE_COST_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p6a-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    for name, needle in (
        ("rules", "aq.after-active-cost.p6a.v0.1"),
        ("contract", CONTRACT_HASH),
        ("one definition", "V_NEXT_P6A_AFTER_COST_DEFINITION_COUNT = 1"),
        ("threshold", "V_NEXT_P6A_MANA_ECHO_TRIGGER_COST_BPS = 2_000"),
        ("mana echo", "aq.skill.mage.p2.manaecho"),
        ("after event", "P5wPassiveEvent.AFTER_ACTIVE_COST"),
        ("mage host", 'ownerScope == "MAGE"'),
        ("single token", "tokenReady: Boolean"),
        ("non mage waits", "activePlan.definitionId in mageActiveIds"),
        ("level value", "manaEchoPlan.resolvedAnchorValue.coerceIn(-600, 0)"),
        ("paid threshold", "committedCostBps >= V_NEXT_P6A_MANA_ECHO_TRIGGER_COST_BPS"),
        ("consume then rearm", "readyAfterConsume || rearm"),
        ("unique receipt", "receiptId !in ledger.processedReceiptIds"),
        ("receipt ledger", "processedReceiptIds = ledger.processedReceiptIds + receiptId"),
        ("decision hash", "decisionHash"),
        ("ledger hash", "ledgerHash"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in runtime)
    check("contract documented", CONTRACT_HASH in document, CONTRACT_HASH)

    for phrase in (
        "coverage pins one mana echo after cost contract and mage active host set",
        "committed cost below two thousand never arms mana echo",
        "committed cost at threshold arms exactly one next mage token",
        "ready token waits through non mage active and is consumed by mage active",
        "consume happens before rearm and uses discounted committed cost",
        "receipt replay and stale decision fail closed with deterministic hashes",
        "wrong owner fails while unrelated admitted passive leaves ledger inert",
    ):
        check(f"runtime test {phrase[:44]}", phrase in tests, "covered")

    for name, needle in (
        ("compiler", "VNextAfterActiveCostRuntimeP6aCompiler.compile(registry)"),
        ("bind", "afterCostRuntime.bind"),
        ("bind failure", "PASSIVE_AFTER_COST_BIND_REJECTED"),
        ("ledger failure", "PASSIVE_AFTER_COST_LEDGER_REJECTED"),
        ("candidate decision", "afterCostSession.decide(candidatePlan, afterCostLedger)"),
        ("dispatch decision", "afterCostSession.decide(baseActivePlan, afterCostLedger)"),
        ("combined cap", ").coerceIn(-V_NEXT_P5Z_SHARED_DISCOUNT_CAP_BPS, 0)"),
        ("dispatch plan", "lateRootPlan.adjustActivePlan"),
        ("committed cost", 'activePlan.growthField == "resource_cost_bps"'),
        ("after commit", "afterCostSession.commit"),
        ("receipt id", "receiptId,"),
        ("final hash", "finalAfterCostLedgerHash = afterCostLedger.ledgerHash"),
        ("content field", "val afterCostContentHash: String"),
        ("session field", "val afterCostSessionHash: String"),
        ("initial field", "val initialAfterCostLedgerHash: String"),
        ("final field", "val finalAfterCostLedgerHash: String"),
        ("count field", "val afterCostCommittedActiveCount: Int"),
        ("armed field", "val manaEchoArmedCount: Int"),
        ("consumed field", "val manaEchoConsumedCount: Int"),
        ("receipt detail", '"committedCost=$committedCostBps|combinedCost=$combinedCostModifier"'),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)

    check(
        "resolver integration test",
        "p6a mana echo alternates threshold arm and discounted next mage cost" in resolver_tests,
        "covered",
    )
    for needle in (
        "listOf(2_000, 1_920, 2_000, 1_920)",
        "result.initialAfterCostLedgerHash, result.finalAfterCostLedgerHash",
        '"P6A:modifier=-400"',
        "assertEquals(costs.size, result.afterCostCommittedActiveCount)",
        "assertEquals(costs.size / 2, result.manaEchoArmedCount)",
        "assertEquals(costs.size / 2, result.manaEchoConsumedCount)",
        "VNextBattleFailureReason.PASSIVE_AFTER_COST_LEDGER_REJECTED",
    ):
        check(f"integration assertion {needle[:42]}", needle in resolver_tests, "covered")

    check("app unconnected", "VNextAfterActiveCostRuntimeP6a" not in app, "safe")
    check("legacy unconnected", "VNextAfterActiveCostRuntimeP6a" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (602, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == 37_600_368, str(APK.stat().st_size if APK.is_file() else 0))
    check("screenshot hash", SCREENSHOT.is_file() and sha(SCREENSHOT) == SCREENSHOT_HASH, sha(SCREENSHOT) if SCREENSHOT.is_file() else "missing")
    check("UI dump hash", UI_DUMP.is_file() and sha(UI_DUMP) == UI_DUMP_HASH, sha(UI_DUMP) if UI_DUMP.is_file() else "missing")

    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=5533", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=602", "appTests=176",
        "totalTests=778", "afterActiveCostDefinitionsEnabled=1", "totalPassiveDefinitionsEnabled=15",
        "remainingPassiveEffectsEnabled=0", "remainingPassiveDefinitions=75",
        "sharedDiscountCapBps=1500", "manaEchoTriggerPaidCostBps=2000",
        "manaEchoTokenCapacity=1", "manaEchoHostScope=NEXT_MAGIC_ACTIVE",
        "manaEchoNonMageWait=true", "manaEchoCommitOrder=CONSUME_THEN_REARM",
        "manaEchoUsesDiscountedCommittedCost=true", "costLedgerCommitPolicy=COMMIT_AFTER_ACTIVE",
        "aiAndDispatchUseSameDecision=true", "receiptReplayRejected=true",
        "transactionHashIncludesAfterCostLedger=true", "resourceCeilingBypass=false",
        "visualAssetRequired=false", "featureDefaultEnabled=false", "liveSettlementEnabled=false",
        f"emulatorScreenshotSha256={SCREENSHOT_HASH}", f"emulatorUiDumpSha256={UI_DUMP_HASH}",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and "device" in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("boot", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying"])
        check("version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        check("target sdk", "targetSdk=36" in version.stdout, "36")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("focus", "topResumedActivity" in focus.stdout and "com.nullplaying/.MainActivity" in focus.stdout, "MainActivity")
        ui = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p6a-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p6a-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P6a PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
