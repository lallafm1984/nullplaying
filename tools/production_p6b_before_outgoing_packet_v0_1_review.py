#!/usr/bin/env python3
"""PD audit for P6b BEFORE_OUTGOING_PACKET passives and emulator evidence."""
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
SCREENSHOT = ROOT / "artifacts/audit/production-p6b-emulator-screen-v0.1.png"
UI_DUMP = ROOT / "artifacts/audit/production-p6b-emulator-ui-v0.1.xml"
APK_HASH = "1356b4bc00b1fc7f45ee2280cd6bafb7a236a9e92129ba2e71b27534526d810b"
SCREENSHOT_HASH = "e780adb720a9177c279fe22d9de7805a077f43ed1ef5593e15ff7f17afcbc1bb"
UI_DUMP_HASH = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
CONTRACT_HASH = "aa0bc1a3e20d3b11dc8e26c64afb5ec8754eab63c54af6c37db3fb90cef49bac"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeOutgoingPacketRuntimeP6b.kt":
        "7de21c3e8c6f6b571beac7f493f253d1c156bf3f6ccf70ec82c756310d67027e",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeOutgoingPacketRuntimeP6bTest.kt":
        "391e072e07a221a9fd863ee5840b13953e885809b1ab4934a6481adb21939f4a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt":
        "b2a85620579bc7b43ae2420ef74382f9f6ba360e8db9c412e92868c3f9f927c8",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "a18e4ca07a016e8d8f2af8ccde69477a49b9f1bc40fbd115f512386ecaf4d16b",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "56252570b3e972fa796a5639db6cc19822c00c212099900b89e68452545a768d",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDirectExecutionCarrierAdapterP5b.kt":
        "af89074913811846b32f332824781d0a4018529e5549aa6f4a9adbd8f933edfb",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5d.kt":
        "2982fe7abd40a8da5c7fe37c7d9dfe1aec64e90605a3a51388d741de3a65a34a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5m.kt":
        "66fc05d3aaf5fc124e470a26af24116d84b3b8e625cca60fb9fb161c87178bcf",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusMutationSemanticAdapterP5h.kt":
        "c58d2e9ab906ade98c68c25580d86bbe4e093da929b713b4fcbf133c002e764c",
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

    runtime = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeOutgoingPacketRuntimeP6b.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeOutgoingPacketRuntimeP6bTest.kt").read_text()
    packet = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P6B_BEFORE_OUTGOING_PACKET_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p6b-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    for name, needle in (
        ("rules", "aq.before-outgoing-packet.p6b.v0.1"),
        ("contract", CONTRACT_HASH),
        ("eleven", "V_NEXT_P6B_OUTGOING_DEFINITION_COUNT = 11"),
        ("deferred four", "deferredBeforeOutgoingIds.size == 4"),
        ("coefficient", "coefficientAddBps"),
        ("hit", "hitAddBps"),
        ("variance min", "minimumVarianceBps"),
        ("variance max", "maximumVarianceBps"),
        ("crit forbidden", "criticalForbidden"),
        ("action group", "actionValues.sum().coerceAtMost(1_500)"),
        ("magic group", "magicValues.sum().coerceAtMost(1_500)"),
        ("boss group", "bossValues.sum().coerceAtMost(1_500)"),
        ("profile hit group", "profileHitValues.sum().coerceAtMost(1_200)"),
        ("passive hit group", "passiveHitValues.sum().coerceAtMost(1_200)"),
        ("opening ledger", "ledger.committedActiveCount == 0"),
        ("curse three", ".sumOf { status -> status.stacks }.coerceAtMost(3)"),
        ("dragon risk", "actor.hpBpsP6b() < 3_000"),
        ("glass risk", "incomingDamageModifierBps = incoming.coerceIn"),
        ("heal risk", "healingReceivedModifierBps = healing.coerceIn"),
        ("recovery risk", "resourceRecoveryModifierBps = recovery.coerceIn"),
        ("receipt replay", "receiptId !in ledger.processedReceiptIds"),
        ("ledger hash", "ledgerHash"),
        ("decision hash", "decisionHash"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in runtime)
    for definition in (
        "dragonpride", "undyingobsession", "statushunter", "openingfocus", "riskpreference",
        "crackedcore", "glasscannon", "coldcalculation", "beastscent", "justiceweight", "readwind",
    ):
        check(f"definition {definition}", definition in runtime)
    for deferred in ("undeadknowledge", "abyssgaze", "elementalaffinity", "guildsecret"):
        check(f"deferred {deferred}", deferred in runtime, "off")
    check("contract documented", CONTRACT_HASH in document, CONTRACT_HASH)

    for phrase in (
        "coverage activates eleven packet safe definitions and defers four cross system contracts",
        "mage packet combines low resource and glass coefficient while cold calculation forbids critical",
        "curse and low hp offense keep fixed healing liabilities and action group cap",
        "opening and hostile target hit bonus use separate groups and opening consumes on active commit",
        "swift and low hp profile hit bonuses share twelve hundred cap",
        "justice weight applies only to paladin attack against boss",
        "healing and resource recovery penalties adjust observed gains without lowering current values",
        "packet extension raises sub cap coefficient and hit but cold ceiling removes critical upside",
        "receipt replay and wrong owner fail closed while deferred definition remains effect off",
    ):
        check(f"runtime test {phrase[:46]}", phrase in tests, "covered")

    for name, needle in (
        ("packet coefficient field", "val outgoingCoefficientAddBps: Int = 0"),
        ("packet hit field", "val outgoingHitAddBps: Int = 0"),
        ("packet min field", "val minimumVarianceBps: Int = 0"),
        ("packet max field", "val maximumVarianceBps: Int = 20_000"),
        ("packet crit field", "val outgoingCriticalForbidden: Boolean = false"),
        ("sub cap coefficient", "request.totalCoefficientBps >= 10_000"),
        ("coefficient ceiling", ".coerceAtMost(10_000)"),
        ("hit final cap", "request.outgoingHitAddBps"),
        ("variance clamp", "request.minimumVarianceBps"),
        ("crit override", "if (request.outgoingCriticalForbidden)"),
    ):
        check(name, needle in packet)

    for name, needle in (
        ("compile", "VNextBeforeOutgoingPacketRuntimeP6bCompiler.compile(registry)"),
        ("bind", "outgoingRuntime.bind"),
        ("bind failure", "PASSIVE_OUTGOING_BIND_REJECTED"),
        ("ledger failure", "PASSIVE_OUTGOING_LEDGER_REJECTED"),
        ("decision", "outgoingSession.decide"),
        ("dispatch modifier", "outgoingPacketModifier = outgoingDecision"),
        ("handled filter", "it.definitionId in resolverHandledPassiveIds"),
        ("ledger commit", "outgoingSession.commit"),
        ("healing liability", "outgoingSession.adjustHealing"),
        ("recovery liability", "outgoingSession.adjustResourceRecovery"),
        ("incoming liability", "outgoingLiabilityPlan.incomingDamageModifierBps"),
        ("content field", "val outgoingPassiveContentHash: String"),
        ("session field", "val outgoingPassiveSessionHash: String"),
        ("initial field", "val initialOutgoingPassiveLedgerHash: String"),
        ("final field", "val finalOutgoingPassiveLedgerHash: String"),
        ("count field", "val outgoingPassiveCommittedActiveCount: Int"),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)
    check(
        "resolver integration",
        "p6b outgoing packet decision reaches dispatcher and commits one ledger receipt per active" in resolver_tests,
        "covered",
    )
    for needle in (
        "setOf(125)", "minimumVarianceBps == 8_000", "it.criticalForbidden",
        "forwardedPassiveCounts.all { it == 0 }", "result.initialOutgoingPassiveLedgerHash",
        "result.outgoingPassiveCommittedActiveCount", '"P6B:coefficient=125"',
        '"P6B_LIABILITY:incoming=300"', "PASSIVE_OUTGOING_LEDGER_REJECTED",
    ):
        check(f"integration assertion {needle[:44]}", needle in resolver_tests, "covered")

    for adapter_name in (
        "VNextDirectExecutionCarrierAdapterP5b.kt", "VNextDamageStatusCarrierAdapterP5d.kt",
        "VNextMixedSurvivalCorrectionP5m.kt", "VNextStatusMutationSemanticAdapterP5h.kt",
    ):
        adapter = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle" / adapter_name).read_text()
        check(f"adapter coefficient {adapter_name}", "outgoingCoefficientAddBps" in adapter, "forwarded")
        check(f"adapter hit {adapter_name}", "outgoingHitAddBps" in adapter, "forwarded")
        check(f"adapter variance {adapter_name}", "minimumVarianceBps" in adapter, "forwarded")
        check(f"adapter critical {adapter_name}", "outgoingCriticalForbidden" in adapter, "forwarded")

    check("app unconnected", "VNextBeforeOutgoingPacketRuntimeP6b" not in app, "safe")
    check("legacy unconnected", "VNextBeforeOutgoingPacketRuntimeP6b" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (612, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == 37_623_691, str(APK.stat().st_size if APK.is_file() else 0))
    check("screenshot hash", SCREENSHOT.is_file() and sha(SCREENSHOT) == SCREENSHOT_HASH, sha(SCREENSHOT) if SCREENSHOT.is_file() else "missing")
    check("UI dump hash", UI_DUMP.is_file() and sha(UI_DUMP) == UI_DUMP_HASH, sha(UI_DUMP) if UI_DUMP.is_file() else "missing")

    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=6193", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=612", "appTests=176",
        "totalTests=788", "beforeOutgoingPacketDefinitionsEnabled=11", "totalPassiveDefinitionsEnabled=26",
        "remainingPassiveEffectsEnabled=0", "remainingPassiveDefinitions=64",
        "beforeOutgoingPacketDefinitionsClassified=15", "beforeOutgoingPacketDefinitionsDeferred=4",
        "deferredUndeadCrossResistance=true", "deferredSelectedElementState=true",
        "deferredStatusApplicationPair=true", "actionCoefficientGroupCapBps=1500",
        "magicCoefficientGroupCapBps=1500", "bossCoefficientGroupCapBps=1500",
        "profileHitGroupCapBps=1200", "passiveHitGroupCapBps=1200", "finalHitCapBps=10000",
        "subTenThousandCoefficientCeilingBps=10000", "coldCalculationCriticalForbidden=true",
        "coldCalculationMaximumVarianceBps=10000", "outgoingLedgerCommitPolicy=COMMIT_AFTER_ACTIVE",
        "receiptReplayRejected=true", "incomingLiabilityAtomic=true", "healingLiabilityAtomic=true",
        "resourceRecoveryLiabilityAtomic=true", "transactionHashIncludesOutgoingLedger=true",
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
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p6b-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p6b-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P6b PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
