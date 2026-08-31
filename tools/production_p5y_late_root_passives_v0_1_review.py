#!/usr/bin/env python3
"""PD audit for P5y late-root passives and emulator evidence."""
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
SCREENSHOT = ROOT / "artifacts/audit/production-p5y-emulator-screen-v0.1.png"
UI_DUMP = ROOT / "artifacts/audit/production-p5y-emulator-ui-v0.1.xml"
APK_HASH = "d060c5422eb06c8085488f87b70e51234da5e7290b63d600d9549e1d800e30a7"
SCREENSHOT_HASH = "dcb8b4bdecde95fd0048d721586aed4ca5f9f228ed2f3156d4118b28f536f74a"
UI_DUMP_HASH = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
CONTRACT_HASH = "e26ae8f916dc371b1ebcc95ca959b1877ce6cd81ba42e58c0caa5d0da2997af3"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLateRootPassiveRuntimeP5y.kt":
        "3afa02141e4c4445939213deb18a4a6d46e5331d9a62d505fcf6dd24564b245f",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextLateRootPassiveRuntimeP5yTest.kt":
        "8d0c0943531839f791b2430a433a70ca6f664f3f24743944bbc7f118d285a15c",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBuildPassiveCommitP5x.kt":
        "7c8db555764aa81e39a0aeb200d11157dfa561fcfa4c2267ffddf9cd44cea2c0",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "8316fd273a41e010f9638ab30e0fa7e0f56c05be056f2b762f0cb1ff0163d92c",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "348ec7cb4ba134ef75c108c305450e46dd8deec5412dad565544e8b4292d8a76",
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

    runtime = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLateRootPassiveRuntimeP5y.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextLateRootPassiveRuntimeP5yTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5Y_LATE_ROOT_PASSIVES_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5y-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    for name, needle in (
        ("rules", "aq.late-root-passive.p5y.v0.1"),
        ("contract hash", CONTRACT_HASH),
        ("three definitions", "V_NEXT_P5Y_LATE_ROOT_DEFINITION_COUNT = 3"),
        ("calm", "aq.skill.cleric.w2.calmbreath"),
        ("long", "aq.skill.common.w6.longfightprep"),
        ("slow", "aq.skill.common.w8.slowbreath"),
        ("calm round", "ownerRound >= 6"),
        ("late root", "heroRootIndex >= 7"),
        ("long speed", "heroRootIndex <= 6) -300"),
        ("slow speed", "heroRootIndex <= 3) -300"),
        ("cost cap", "coerceIn(-1_500, 0)"),
        ("attack cap", "coerceAtMost(10_000)"),
        ("protection", "incoming = if (long != null && heroRootIndex >= 7) -200"),
        ("resource cap", "coerceAtMost(frameAfterAction.resourceCeilingBps)"),
        ("root hash", "P5yLateRootPlan"),
        ("session hash", "P5yLateRootSession"),
        ("p5w admission", "passiveCatalog.admitLoadout"),
        ("root event", "P5wPassiveEvent.HERO_ROOT_START"),
        ("empty encounter", "encounterStartDefinitions == 0"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in runtime)
    check("contract documented", CONTRACT_HASH in document, CONTRACT_HASH)

    for phrase in (
        "coverage pins three hero root definitions and proves encounter start is empty",
        "calm breath discounts active cost only from owner round six",
        "long fight attack starts at root seven and raises only coefficients below cap",
        "long fight active plan binds low attack coefficient without changing high declarations",
        "slow breath has early speed cost gap and root seven generation bonus",
        "resource generation never bypasses current ceiling",
        "two early speed penalties add then expire at their own root boundaries",
        "owner admission and session plan hashes fail closed or replay deterministically",
    ):
        check(f"runtime test {phrase[:40]}", phrase in tests, "covered")

    for name, needle in (
        ("compiler", "VNextLateRootPassiveRuntimeP5yCompiler.compile(registry)"),
        ("bind", "lateRootRuntime.bind"),
        ("failure", "PASSIVE_LATE_ROOT_BIND_REJECTED"),
        ("root counter", "heroRootCount += 1"),
        ("round initiative", "val roundInitiative = initiativeFor"),
        ("effective speed", ".adjustSpeed(buildSnapshot.frameAfter.speed)"),
        ("AI cost", "lateRootPlan.adjustResourceCost(skill.resourceCostBps)"),
        ("dispatch cost", "lateRootPlan.adjustActivePlan(baseActivePlan)"),
        ("basic gain", "lateRootPlan.adjustResourceGain(policy.basicGainBps)"),
        ("active gain", "lateRootPlan.applyObservedResourceGeneration"),
        ("incoming protection", "lateRootDefensePlan.incomingDamageModifierBps"),
        ("content transaction", "val lateRootPassiveContentHash: String"),
        ("snapshot transaction", "val lateRootPassiveSnapshotHash: String"),
        ("count transaction", "val heroRootCount: Int"),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)
    for phrase in (
        "p5y early speed costs change round initiative and expire on exact hero roots",
        "p5y calm breath changes AI and dispatched active cost only from round six",
    ):
        check(f"resolver test {phrase[:42]}", phrase in resolver_tests, "covered")
    for needle in (
        "assertEquals(VNextCombatSide.MONSTER, actorReceipts.first { it.roundIndex == 1 }.actorSide)",
        "assertEquals(VNextCombatSide.HERO, actorReceipts.first { it.roundIndex == 4 }.actorSide)",
        "assertEquals(baseCost * 9_600 / 10_000, costs[5])",
        "assertEquals(30, result.heroRootCount)",
    ):
        check(f"integration assertion {needle[:40]}", needle in resolver_tests, "covered")

    check("app unconnected", "VNextLateRootPassiveRuntimeP5y" not in app, "safe")
    check("legacy unconnected", "VNextLateRootPassiveRuntimeP5y" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (583, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == 37_571_860, str(APK.stat().st_size if APK.is_file() else 0))
    check("screenshot hash", SCREENSHOT.is_file() and sha(SCREENSHOT) == SCREENSHOT_HASH, sha(SCREENSHOT) if SCREENSHOT.is_file() else "missing")
    check("UI dump hash", UI_DUMP.is_file() and sha(UI_DUMP) == UI_DUMP_HASH, sha(UI_DUMP) if UI_DUMP.is_file() else "missing")

    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=4340", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=583", "appTests=176",
        "totalTests=759", "buildPassiveDefinitionsEnabled=5", "lateRootPassiveDefinitionsEnabled=3",
        "totalPassiveDefinitionsEnabled=8", "remainingPassiveEffectsEnabled=0",
        "encounterStartDefinitions=0", "dynamicRoundInitiative=true",
        "lateAttackCoefficientCapBps=10000", "lateProtectionModifierBps=-200",
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
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5y-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5y-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5y PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
