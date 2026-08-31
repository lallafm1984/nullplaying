#!/usr/bin/env python3
"""PD audit for P5z BEFORE_ACTIVE_COST passives and emulator evidence."""
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
SCREENSHOT = ROOT / "artifacts/audit/production-p5z-emulator-screen-v0.1.png"
UI_DUMP = ROOT / "artifacts/audit/production-p5z-emulator-ui-v0.1.xml"
APK_HASH = "7bc5a978fe49675aafede2a44bfda24ce2c4888da8e31a3837aa4003afc770f6"
SCREENSHOT_HASH = "fb6eaeeb2fe01f92a2525bd6699ea4d02c651fc9c79e339cea0e48faf9b232ce"
UI_DUMP_HASH = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
CONTRACT_HASH = "b1c1b1db8d9d23ff82a9f907532940210f9c3ccc3ee2314125ee11928c11ad34"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeActiveCostRuntimeP5z.kt":
        "98453ea0aee101664f3ab6ebad9ecbe32648f40d7925316c484fd77140b6ce02",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeActiveCostRuntimeP5zTest.kt":
        "ee77104d84a862169a39a63d661d9f9baee5cc45dfa09de1d475b43fb042358f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLateRootPassiveRuntimeP5y.kt":
        "1ed3d06e665e8cf2f43cfc5975b48f3f8d273cf535de47d7aeb827ecb600e31e",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "e3be44131893f3ca1b13fb3ec30283acae90d40e8e8eaa62daae35088741adc6",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "4d7ceb6f6f4a73307114fe7d38a89f637d8c7ba35de972e278cc24deaf9b2d00",
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

    runtime = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeActiveCostRuntimeP5z.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeActiveCostRuntimeP5zTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5Z_BEFORE_ACTIVE_COST_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5z-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    for name, needle in (
        ("rules", "aq.before-active-cost.p5z.v0.1"),
        ("contract", CONTRACT_HASH),
        ("six definitions", "V_NEXT_P5Z_COST_DEFINITION_COUNT = 6"),
        ("shared cap", "V_NEXT_P5Z_SHARED_DISCOUNT_CAP_BPS = 1_500"),
        ("healing", "aq.skill.cleric.p3.healingrestraint"),
        ("saint", "aq.skill.cleric.w8.forgottensaint"),
        ("library", "aq.skill.mage.w8.ashenlibrary"),
        ("oath", "aq.skill.paladin.p1.twokingdomsoath"),
        ("isolated", "aq.skill.ranger.p3.isolatedprey"),
        ("ammo", "aq.skill.ranger.w2.ammosaving"),
        ("overheal quarter", "predictedOverheal * 4L >= nominal"),
        ("support token", "nextSupportDiscountReady"),
        ("three elements", "elementsUsed.size.coerceAtMost(3)"),
        ("alternation", "alternatesWith"),
        ("real mark", 'it.tag == "MARK" && it.stacks > 0'),
        ("fourth ranger", "(ledger.rangedActiveCount + 1) % 4 == 0"),
        ("commit after", "fun commit("),
        ("decision hash", "decisionHash"),
        ("ledger hash", "ledgerHash"),
        ("event", "P5wPassiveEvent.BEFORE_ACTIVE_COST"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in runtime)
    check("contract documented", CONTRACT_HASH in document, CONTRACT_HASH)

    for phrase in (
        "coverage pins six before active cost definitions to one shared cap",
        "healing restraint applies only when predicted overheal reaches one quarter",
        "forgotten saint arms after three distinct support tags and consumes on next support",
        "p5y and p5z discounts add once then clamp at shared fifteen hundred",
        "ashen library counts committed distinct elements up to three and nine hundred",
        "two kingdoms oath requires a committed pure offense protection alternation",
        "isolated prey requires an actual mark on the single current enemy",
        "ammo saving discounts exactly each fourth committed ranger attack without rng",
        "ledger and decision hashes replay deterministically and reject stale commit",
        "wrong owner fails closed while unrelated admitted passive invents no cost effect",
    ):
        check(f"runtime test {phrase[:42]}", phrase in tests, "covered")

    for name, needle in (
        ("compiler", "VNextBeforeActiveCostRuntimeP5zCompiler.compile(registry)"),
        ("bind", "passiveCostRuntime.bind"),
        ("bind failure", "PASSIVE_COST_BIND_REJECTED"),
        ("ledger failure", "PASSIVE_COST_LEDGER_REJECTED"),
        ("candidate decision", "passiveCostSession.decide"),
        ("level cost", 'candidatePlan.growthField == "resource_cost_bps"'),
        ("combined cap", ".coerceIn(-V_NEXT_P5Z_SHARED_DISCOUNT_CAP_BPS, 0)"),
        ("dispatch plan", "lateRootPlan.adjustActivePlan"),
        ("commit ledger", "passiveCostSession.commit"),
        ("final hash", "finalPassiveCostLedgerHash = passiveCostLedger.ledgerHash"),
        ("content field", "val passiveCostContentHash: String"),
        ("session field", "val passiveCostSessionHash: String"),
        ("initial field", "val initialPassiveCostLedgerHash: String"),
        ("final field", "val finalPassiveCostLedgerHash: String"),
        ("count field", "val passiveCostCommittedActiveCount: Int"),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)
    check(
        "resolver ammo test",
        "p5z ammo ledger discounts exactly fourth ranger active and hashes commit state" in resolver_tests,
        "covered",
    )
    for needle in (
        "baseCost * 9_600 / 10_000",
        "result.initialPassiveCostLedgerHash, result.finalPassiveCostLedgerHash",
        '"P5Z:modifier=-400"',
        "assertEquals(costs.size, result.passiveCostCommittedActiveCount)",
    ):
        check(f"integration assertion {needle[:42]}", needle in resolver_tests, "covered")

    check("app unconnected", "VNextBeforeActiveCostRuntimeP5z" not in app, "safe")
    check("legacy unconnected", "VNextBeforeActiveCostRuntimeP5z" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (594, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == 37_589_316, str(APK.stat().st_size if APK.is_file() else 0))
    check("screenshot hash", SCREENSHOT.is_file() and sha(SCREENSHOT) == SCREENSHOT_HASH, sha(SCREENSHOT) if SCREENSHOT.is_file() else "missing")
    check("UI dump hash", UI_DUMP.is_file() and sha(UI_DUMP) == UI_DUMP_HASH, sha(UI_DUMP) if UI_DUMP.is_file() else "missing")

    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=4534", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=594", "appTests=176",
        "totalTests=770", "beforeActiveCostDefinitionsEnabled=6", "totalPassiveDefinitionsEnabled=14",
        "remainingPassiveEffectsEnabled=0", "sharedDiscountCapBps=1500",
        "costLedgerCommitPolicy=COMMIT_AFTER_ACTIVE", "aiUsesSkillLevelResolvedCost=true",
        "resourceCeilingBypass=false", "visualAssetRequired=false",
        "featureDefaultEnabled=false", "liveSettlementEnabled=false",
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
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5z-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5z-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5z PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
