#!/usr/bin/env python3
"""PD audit for P6c AFTER_OUTGOING_PACKET result ledgers and emulator evidence."""
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
SCREENSHOT = ROOT / "artifacts/audit/production-p6c-emulator-screen-v0.1.png"
UI_DUMP = ROOT / "artifacts/audit/production-p6c-emulator-ui-v0.1.xml"
APK_HASH = "acc1daf4eef31cd4dafbdc1bdd94af762a3c5e796af6830d7453174acdfc2968"
SCREENSHOT_HASH = "99b325280c7dfd474010992bbde747be978c0c62fb215c0d2256f2fba31df081"
UI_DUMP_HASH = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
CONTRACT_HASH = "3987fd9ee7b6de45fd681dab40cf356a2acdce4b0eb4011710a9fa1b28a5b075"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterOutgoingPacketRuntimeP6c.kt":
        "5a03f874fcd7cf34498252f431a8a6cff1a0038c798b3ada3db7ea10f9f57073",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterOutgoingPacketRuntimeP6cTest.kt":
        "9efbb255e54052b6e252e5bf09ebfc8d794968527bed54f18ba4924a9ca79545",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeOutgoingPacketRuntimeP6b.kt":
        "0c2e2f01b193078694bd3fa6f3e1ed59cced89a58138afcd2da89810b80c34ac",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeOutgoingPacketRuntimeP6bTest.kt":
        "0c71ce97171c617dc4d819f00c0ccc64447eb8a5a7d85723a50d2cb88a7d8206",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt":
        "b2a85620579bc7b43ae2420ef74382f9f6ba360e8db9c412e92868c3f9f927c8",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "22f2b77bed66b8195a2627414761977bb2838a987a9a7a89fa00e7a11ae90a30",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "c719da092a7e0812a734948126939d5d2b4b0e02f3e8df035cf5e8e97546c6c4",
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

    runtime = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterOutgoingPacketRuntimeP6c.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterOutgoingPacketRuntimeP6cTest.kt").read_text()
    p6b = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeOutgoingPacketRuntimeP6b.kt").read_text()
    p6b_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeOutgoingPacketRuntimeP6bTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P6C_AFTER_OUTGOING_PACKET_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p6c-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    for name, needle in (
        ("rules", "aq.after-outgoing-packet.p6c.v0.1"),
        ("contract", CONTRACT_HASH),
        ("five", "V_NEXT_P6C_AFTER_OUTGOING_DEFINITION_COUNT = 5"),
        ("deferred eleven", "deferredAfterOutgoingIds.size == 11"),
        ("analysis", "aq.skill.common.w8.failureanalysis"),
        ("distance", "aq.skill.ranger.p2.distancesense"),
        ("study", "aq.skill.rogue.p3.failurestudy"),
        ("trace", "aq.skill.rogue.w6.vanishedtrace"),
        ("greed", "aq.skill.rogue.w8.greedrhythm"),
        ("basic prepare", "fun prepareBasic"),
        ("active prepare", "fun prepareActive"),
        ("incoming prepare", "fun prepareIncomingDirect"),
        ("hero commit", "fun commitHeroOutcome"),
        ("enemy commit", "fun commitIncomingDirect"),
        ("analysis consume", "analysisReadyAfterConsume"),
        ("miss rearm", "if (missed && supportedPlans[FAILURE_ANALYSIS] != null)"),
        ("study consume", "consumeStudy"),
        ("hit streak", "consecutiveHitCount"),
        ("critical cap", ".coerceAtMost(3)"),
        ("cost cap", ".coerceIn(-900, 0)"),
        ("first miss", "firstMissNow = missed && !ledger.firstMissObserved"),
        ("direct consume", "consumesVanishedTrace"),
        ("hero replay", "receiptId !in ledger.processedHeroReceiptIds"),
        ("enemy replay", "receiptId !in ledger.processedEnemyReceiptIds"),
        ("group merge", "fun P6bOutgoingPacketModifier.withP6c"),
        ("profile cap", ".coerceAtMost(1_200)"),
        ("delayed explicit", "ACTIVE_DELAYED"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in runtime)
    check("contract documented", CONTRACT_HASH in document, CONTRACT_HASH)

    for phrase in (
        "coverage activates five outcome ledger definitions and defers eleven cross family contracts",
        "miss arms analysis and next hit check consumes while repeated miss rearms",
        "failure study arms on miss and next active consumes even when active has no packet",
        "distance sense starts on third consecutive ranger attack and resets after miss",
        "greed rhythm grows to three critical stacks and noncritical attack resets cost discount",
        "first miss arms one evasion reaction that waits through non direct and never rearms",
        "p6b and p6c hit groups combine by group then clamp at twelve hundred",
        "stale plan and duplicate hero or enemy receipts fail closed",
        "wrong owner fails while deferred after outgoing passive remains effect off",
    ):
        check(f"runtime test {phrase[:46]}", phrase in tests, "covered")

    for name, needle in (
        ("p6b group fields", "val profileHitAddBps: Int = 0"),
        ("p6b basic", "fun decideBasic"),
        ("p6b basic opening excluded", "active=BASIC"),
    ):
        check(name, needle in p6b)
    check(
        "p6b basic test",
        "basic packet uses profile and hostile hit groups without consuming first active focus" in p6b_tests,
        "covered",
    )

    for name, needle in (
        ("compile", "VNextAfterOutgoingPacketRuntimeP6cCompiler.compile(registry)"),
        ("bind", "afterOutgoingRuntime.bind"),
        ("bind failure", "PASSIVE_AFTER_OUTGOING_BIND_REJECTED"),
        ("ledger failure", "PASSIVE_AFTER_OUTGOING_LEDGER_REJECTED"),
        ("candidate cost", "afterOutgoingSession.prepareActive(candidatePlan, afterOutgoingLedger)"),
        ("candidate cost sum", "afterOutgoingPlan.activeCostModifierBps"),
        ("basic plan", "afterOutgoingSession.prepareBasic(afterOutgoingLedger)"),
        ("basic p6b", "outgoingSession.decideBasic"),
        ("basic packet", "outgoingPacketModifier = basicOutgoingDecision"),
        ("active combined", ").withP6c(afterOutgoingPlan)"),
        ("hero commit", "afterOutgoingSession.commitHeroOutcome"),
        ("incoming prepare", "afterOutgoingSession.prepareIncomingDirect"),
        ("incoming evasion", "afterOutgoingIncomingPlan.evasionAddBps"),
        ("enemy commit", "afterOutgoingSession.commitIncomingDirect"),
        ("atomic after delayed defense", "delayedCastIncomingCommit.committed"),
        ("record", "fun recordAfterOutgoing"),
        ("content field", "val afterOutgoingContentHash: String"),
        ("session field", "val afterOutgoingSessionHash: String"),
        ("initial field", "val initialAfterOutgoingLedgerHash: String"),
        ("final field", "val finalAfterOutgoingLedgerHash: String"),
        ("hero count field", "val afterOutgoingCommittedHeroOutcomeCount: Int"),
        ("trace field", "val vanishedTraceConsumedCount: Int"),
        ("hit field", "val finalConsecutiveHitCount: Int"),
        ("critical field", "val finalConsecutiveCriticalCount: Int"),
        ("handled ids", "VNextAfterOutgoingPacketRuntimeP6c.supportedDefinitionIds()"),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)
    check(
        "resolver integration",
        "p6c miss critical and enemy direct outcomes drive next hit cost and evasion ledgers" in resolver_tests,
        "covered",
    )
    for needle in (
        "listOf(2_350, 2_350, 2_303, 2_256, 2_209, 2_350)",
        "assertEquals(550, hitAdds[1])", "result.initialAfterOutgoingLedgerHash",
        "result.afterOutgoingCommittedHeroOutcomeCount", "result.vanishedTraceConsumedCount",
        '"P6C:action=aq.skill.rogue.a1.seizeopening"', '"P6C_INCOMING:evasion=500"',
        "PASSIVE_AFTER_OUTGOING_LEDGER_REJECTED",
    ):
        check(f"integration assertion {needle[:44]}", needle in resolver_tests, "covered")

    check("app unconnected", "VNextAfterOutgoingPacketRuntimeP6c" not in app, "safe")
    check("legacy unconnected", "VNextAfterOutgoingPacketRuntimeP6c" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (623, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == 37_647_822, str(APK.stat().st_size if APK.is_file() else 0))
    check("screenshot hash", SCREENSHOT.is_file() and sha(SCREENSHOT) == SCREENSHOT_HASH, sha(SCREENSHOT) if SCREENSHOT.is_file() else "missing")
    check("UI dump hash", UI_DUMP.is_file() and sha(UI_DUMP) == UI_DUMP_HASH, sha(UI_DUMP) if UI_DUMP.is_file() else "missing")

    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=5409", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=623", "appTests=176",
        "totalTests=799", "afterOutgoingPacketDefinitionsEnabled=5", "totalPassiveDefinitionsEnabled=31",
        "remainingPassiveEffectsEnabled=0", "remainingPassiveDefinitions=59",
        "afterOutgoingPacketDefinitionsClassified=16", "afterOutgoingPacketDefinitionsDeferred=11",
        "failureAnalysisNextHitCheck=true", "failureStudyNextActive=true",
        "distanceSenseThirdConsecutiveAttack=true", "greedRhythmCriticalStackCap=3",
        "greedRhythmMaximumDiscountBps=900", "vanishedTraceEncounterTriggerCap=1",
        "vanishedTraceNextEnemyDirect=true", "fixedHitBypassesEvasionBonus=true",
        "profileHitGroupCapBps=1200", "passiveHitGroupCapBps=1200",
        "sharedCostDiscountCapBps=1500", "basicOutcomeTracked=true",
        "immediateActiveOutcomeTracked=true", "delayedPacketAttachmentEnabled=false",
        "missConsumeThenRearm=true", "nonCriticalResetsCriticalStreak=true",
        "enemyDirectConsumeAfterDefenseCommit=true", "aiAndDispatchUseSameCriticalCostLedger=true",
        "afterOutgoingLedgerCommitPolicy=PREPARE_THEN_COMMIT_OUTCOME", "receiptReplayRejected=true",
        "transactionHashIncludesAfterOutgoingLedger=true", "p6bBasicPacketModifierEnabled=true",
        "unimplementedPassivesRemainAdapterVisible=true", "runtimeHandledPassivesFilteredFromAdapter=true",
        "resourceCeilingBypass=false", "visualAssetRequired=false", "featureDefaultEnabled=false",
        "liveSettlementEnabled=false", f"emulatorScreenshotSha256={SCREENSHOT_HASH}",
        f"emulatorUiDumpSha256={UI_DUMP_HASH}",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and "device" in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("boot", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest"])
        check("version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        check("target sdk", "targetSdk=36" in version.stdout, "36")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("focus", "topResumedActivity" in focus.stdout and "com.alarmquest/.MainActivity" in focus.stdout, "MainActivity")
        ui = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p6c-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p6c-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P6c PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
