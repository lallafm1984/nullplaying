#!/usr/bin/env python3
"""PD audit for P6n expedition resource transition contracts."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6n-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6n-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6n-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6N_RESOURCE_TRANSITION_v0.1.md"
RUNTIME = ROOT / (
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextResourceTransitionRuntimeP6n.kt"
)
RUNTIME_TEST = ROOT / (
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextResourceTransitionRuntimeP6nTest.kt"
)
RESOLVER = ROOT / (
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextBattleResolverTransaction.kt"
)
RESOLVER_TEST = ROOT / (
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextBattleResolverTransactionTest.kt"
)
SETTLEMENT = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt"

APK_SHA = "4f20d95c146704bfa1cf9aec00df3cd45076f8fa3a29267e2456c6a8daf9d0a8"
SCREEN_SHA = "ce45c900dbdaaf9c2b8f4305a3a84d9fdc912e15e16ffd499c3bf2f3ccc4747e"
UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
APK_SIZE = 37_846_479
FREEZE = {
    RUNTIME: "07732e2200a439803adc689b5dcb1a15903a3629a33f5205136b8a5042d3a4fa",
    RUNTIME_TEST: "6c269073a749099cd613061b00f621d13eb5b7b9875c98a1ac97a68f60c78815",
    RESOLVER: "756c131634a87b7779999a7d852c06b917e5e7b6328be181f69eeb7b2fb6e604",
    RESOLVER_TEST: "bb35cf530c20f2a204e104041510136d345ad293b3ef0dfe0141a21078dc5b5f",
    SETTLEMENT: "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def totals(path: Path) -> tuple[int, int, int, int]:
    values = [0, 0, 0, 0]
    for report in path.glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            values[index] += int(suite.attrib.get(key, "0"))
    return tuple(values)


def command(*args: str) -> str:
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)
    return (result.stdout + result.stderr).strip()


def parse_audit(text: str) -> dict[str, str]:
    return dict(
        line.split("=", 1)
        for line in text.splitlines()
        if line and not line.startswith("#") and "=" in line
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: object) -> None:
        checks.append((name, ok, str(detail)))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")

    required = [APK, AUDIT, SCREEN, UI, DOC, *FREEZE]
    for path in required:
        check(f"file:{path.relative_to(ROOT)}", path.is_file(), path.exists())
    if not all(path.is_file() for path in required):
        return 1

    runtime = RUNTIME.read_text()
    runtime_test = RUNTIME_TEST.read_text()
    resolver = RESOLVER.read_text()
    resolver_test = RESOLVER_TEST.read_text()
    settlement = SETTLEMENT.read_text()
    audit = parse_audit(AUDIT.read_text())
    doc = DOC.read_text()

    runtime_anchors = {
        "rules": "aq.resource-transition.p6n.v0.1",
        "content": "f97f28aed14486af474401366fac1b1330edb2187758595d86b55e953eacc9ce",
        "definition count": "V_NEXT_P6N_RESOURCE_TRANSITION_DEFINITION_COUNT = 2",
        "ordinary base": "V_NEXT_P6N_ORDINARY_ATTRITION_RATE_BPS = 5_000",
        "ordinary floor": "V_NEXT_P6N_ORDINARY_ATTRITION_MIN_RATE_BPS = 4_500",
        "protection full": "V_NEXT_P6N_PROTECTION_ATTRITION_RATE_BPS = 10_000",
        "recovery penalty": "V_NEXT_P6N_ARCHMAGE_RECOVERY_EFFICIENCY_BPS = 9_000",
        "debt cap": "V_NEXT_P6N_RESOURCE_DEBT_CAP_BPS = 3_000",
        "archmage id": "aq.skill.mage.w5.archmagedebt",
        "supply id": "aq.skill.common.w5.supplysaving",
        "insurance id": "aq.skill.rogue.w3.debtinsurance",
        "deferred exact": "deferredResourceTransitionIds == listOf(DEBT_INSURANCE)",
        "live false": "val liveReady: Boolean get() = false",
        "mage spells": 'it.ownerScope == "MAGE" && it.pattern != "HP_BACKED_DEFICIT"',
        "expedition policy": 'val expeditionPool = lifecycle == "EXPEDITION_POOL"',
        "supply anchor": "coerceIn(100, 500)",
        "floor clamp": ".coerceAtLeast(V_NEXT_P6N_ORDINARY_ATTRITION_MIN_RATE_BPS)",
        "deficit": "actualCost - actor.resourceBps",
        "first debt": "!ledger.expedition.archmageDebtUsed && ledger.expedition.debtBps == 0",
        "allowance": "coerceIn(500, 1_500)",
        "ceiling penalty": "attrition + if (borrows) deficit else 0",
        "candidate reject": "if (canCommit) currentPaymentBps else Int.MAX_VALUE",
        "dispatch current": "activePlan.copy(resourceCostBps = plan.currentPaymentBps)",
        "adapter attrition": "frameBefore.resourceCeilingBps - frameAfterDispatch.resourceCeilingBps",
        "additional only": "requiredPenalty - observedAdapterAttrition",
        "recovery efficiency": "observedRecovery.toLong() * V_NEXT_P6N_ARCHMAGE_RECOVERY_EFFICIENCY_BPS",
        "debt first": "val debtRepaid = minOf(expedition.debtBps, effectiveRecovery)",
        "recovery debt first": "val repaid = minOf(ledger.expedition.debtBps, effective)",
        "ceiling clamp": ".coerceAtMost(frame.resourceCeilingBps)",
        "lodging clock": 'private const val EXPEDITION_CLOCK = "EXPEDITION_LODGING"',
        "cost locked": "costLocked = true",
        "orphan reject": "ORPHAN_ARCHMAGE_DEBT_REJECTED",
        "replay reject": "receiptId !in ledger.processedReceiptIds",
        "stale reject": "plan.ledgerBeforeHash == ledger.ledgerHash",
        "archmage contract": 'source.stackCapBps != 3_000',
        "supply contract": 'source.stackCapBps != 1_500',
        "event": "P5wPassiveEvent.RESOURCE_TRANSITION",
        "hashed receipts": 'processedReceiptIds.sorted().joinToString(",")',
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime:{name}", anchor in runtime, anchor)

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6n.v0.13",
        "bind failure": "PASSIVE_RESOURCE_TRANSITION_BIND_REJECTED",
        "ledger failure": "PASSIVE_RESOURCE_TRANSITION_LEDGER_REJECTED",
        "compile": "VNextResourceTransitionRuntimeP6nCompiler.compile(registry)",
        "bind": "resourceTransitionRuntime.bind(",
        "content": "resourceTransitionContentHash = V_NEXT_P6N_RESOURCE_TRANSITION_CONTENT_HASH",
        "candidate prepare": "resourceTransitionSession.prepareActive(",
        "candidate cost": "resourceCostBps = resourceCandidatePlan.candidateCostBps",
        "actual prepare": "val resourceActivePlan = resourceTransitionSession.prepareActive(",
        "dispatch plan": "resourceTransitionSession.dispatchPlan(activePlan, resourceActivePlan)",
        "basic recovery": '"$receiptId:p6n-basic-recovery"',
        "system recovery": '"$receiptId:p6n-system-recovery"',
        "active commit": "resourceTransitionSession.commitActive(",
        "active receipt": '"$receiptId:p6n-active"',
        "record": "recordResourceTransition(resourceActiveCommit.ledgerAfter)",
        "hash content": "append(transaction.resourceTransitionContentHash)",
        "hash debt": "append(transaction.totalResourceDebtIncurredBps)",
        "hash final": "append(transaction.finalResourceDebtBps)",
        "handled": "addAll(VNextResourceTransitionRuntimeP6n.supportedDefinitionIds())",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)

    unit_anchors = {
        "coverage": "coverage_enables_archmage_and_supply_while_debt_insurance_waits_for_rogue_debt_receipt",
        "supply all anchors": "supply_saving_uses_all_anchors_on_ordinary_expedition_attrition_with_4500_floor",
        "protection": "heal_shield_barrier_spend_keeps_full_attrition_and_supply_does_not_apply",
        "borrow": "first_insufficient_mage_spell_borrows_with_base_attrition_plus_deficit_ceiling_penalty",
        "recovery": "recovery_loses_ten_percent_repays_debt_first_and_used_token_survives_until_lodging",
        "once": "archmage_cannot_borrow_twice_and_encounter_builders_pay_no_expedition_attrition",
        "fail closed": "malformed_orphan_replay_and_stale_ledgers_fail_closed",
        "mage count": "assertEquals(14, ready.coverage.mageSpellDefinitions)",
        "level 50 attrition": "assertEquals(940, plan.plannedAttritionBps)",
        "debt 500": "P6nExpeditionResourceView(true, 500)",
        "recovery 1350": "assertEquals(1_350, recovery.effectiveRecoveryBps)",
        "resource 850": "assertEquals(850, recovery.frameAfter.resourceBps)",
    }
    for name, anchor in unit_anchors.items():
        check(f"unit-test:{name}", anchor in runtime_test, anchor)

    integration_anchors = {
        "case": "p6n mage first insufficient spell borrows once and commits supply attrition debt",
        "active": '"aq.skill.mage.a1.arcanepulse"',
        "archmage": '"aq.skill.mage.w5.archmagedebt"',
        "supply": '"aq.skill.common.w5.supplysaving"',
        "dispatch 1500": "assertEquals(listOf(1_500), dispatchedCosts)",
        "content": "V_NEXT_P6N_RESOURCE_TRANSITION_CONTENT_HASH",
        "active once": "assertEquals(1, result.resourceTransitionCommittedActiveCount)",
        "supply once": "assertEquals(1, result.supplySavingAppliedCount)",
        "debt trigger": "assertEquals(1, result.archmageDebtTriggeredCount)",
        "penalty 1440": "assertEquals(1_440, result.totalResourceAttritionBps)",
        "debt incurred": "assertEquals(500, result.totalResourceDebtIncurredBps)",
        "debt remains": "assertEquals(500, result.finalResourceDebtBps)",
        "ceiling 8560": "assertEquals(8_560, result.finalHero.resourceCeilingBps)",
        "token": "LEDGER_ARCHMAGE_DEBT_EXPEDITION",
        "detail": '"P6N_ACTIVE:" in it.detail',
        "rehash": "VNextBattleResolverTransaction.transactionHash(result)",
    }
    for name, anchor in integration_anchors.items():
        check(f"integration-test:{name}", anchor in resolver_test, anchor)

    check("settlement:resolveKill", "fun resolveKill(" in settlement, "present")
    check("settlement:uncoupled", "P6n" not in settlement and "p6n" not in settlement, "uncoupled")
    for path, expected in FREEZE.items():
        check(f"freeze:{path.name}", sha(path) == expected, sha(path))

    check("artifact:apk size", APK.stat().st_size == APK_SIZE, APK.stat().st_size)
    check("artifact:apk hash", sha(APK) == APK_SHA, sha(APK))
    check("artifact:screen hash", sha(SCREEN) == SCREEN_SHA, sha(SCREEN))
    check("artifact:ui hash", sha(UI) == UI_SHA, sha(UI))
    check("artifact:png", SCREEN.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), SCREEN.stat().st_size)
    ui_text = UI.read_text()
    check("artifact:ui parse", ET.parse(UI).getroot().tag == "hierarchy", "hierarchy")
    check("artifact:empty roster", "아직 캐릭터가 없습니다" in ui_text, "empty")
    check("artifact:create", "새 캐릭터" in ui_text, "create")

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    total = tuple(sum(values) for values in zip(engine, debug, release))
    check("reports:engine", engine == (717, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (893, 0, 0, 0), total)
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(1 for issue in lint.findall("issue") if issue.attrib.get("severity") == "Error")
    warnings = sum(1 for issue in lint.findall("issue") if issue.attrib.get("severity") == "Warning")
    check("reports:lint errors", errors == 0, errors)
    check("reports:lint warnings", warnings == 22, warnings)

    expected_audit = {
        "verificationTarget": "android-emulator", "serial": "emulator-5554",
        "avdName": "alarmquest-qa", "androidRelease": "15", "apiLevel": "35",
        "packageName": "com.alarmquest", "versionName": "0.1.0", "versionCode": "1",
        "targetSdk": "36", "apkSizeBytes": str(APK_SIZE), "apkSha256": APK_SHA,
        "installResult": "Success", "pmClearResult": "Success", "launchState": "COLD",
        "coldTotalTimeMs": "4713", "coldWaitTimeMs": "4715",
        "topResumedActivity": "com.alarmquest/.MainActivity", "freshRosterEmpty": "true",
        "androidRuntimeFatalCount": "0", "emulatorScreenshotSha256": SCREEN_SHA,
        "emulatorUiDumpSha256": UI_SHA, "engineTests": "717", "appDebugTests": "88",
        "appReleaseTests": "88", "totalTests": "893", "testFailures": "0",
        "testErrors": "0", "testSkipped": "0", "lintErrors": "0", "lintWarnings": "22",
        "featureDefaultEnabled": "false", "liveSettlementEnabled": "false",
        "previousPassiveDefinitionsEnabled": "45", "resourceTransitionDefinitionsEnabled": "2",
        "totalPassiveDefinitionsEnabled": "47", "remainingPassiveDefinitions": "43",
        "resourceTransitionDefinitionsClassified": "3", "resourceTransitionDefinitionsDeferred": "1",
        "resourceTransitionRulesVersion": "aq.resource-transition.p6n.v0.1",
        "resourceTransitionContentHash": "f97f28aed14486af474401366fac1b1330edb2187758595d86b55e953eacc9ce",
        "battleResolverRulesVersion": "aq.battle-resolver-transaction.p6n.v0.13",
        "archmageDebtEnabled": "true", "supplySavingEnabled": "true",
        "debtInsuranceEnabled": "false", "visualAssetRequired": "false",
        "designTeamImageRequested": "false",
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, audit.get(key))

    doc_anchors = [
        "Passive 47/90", "대마법사의 부채", "보급 절약", "배신자의 보험",
        "AI 후보와 실제 commit 일치", "Lv9,999 안전성", "라이브 ON 전 필수 조건",
        "디자인팀 이미지 요청과 UI 변경은 하지 않았다", "PD 조건부 승인",
        APK_SHA, FREEZE[RUNTIME], FREEZE[RESOLVER], "engine 717 + app debug 88 + app release 88 = total 893",
    ]
    for anchor in doc_anchors:
        check(f"doc:{anchor[:28]}", anchor in doc, anchor)

    if args.with_emulator:
        devices = command("adb", "devices", "-l")
        check("emulator:connected", "emulator-5554" in devices and " device " in devices, devices)
        release_value = command("adb", "-s", "emulator-5554", "shell", "getprop", "ro.build.version.release")
        api = command("adb", "-s", "emulator-5554", "shell", "getprop", "ro.build.version.sdk")
        package = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest")
        activity = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities")
        check("emulator:release", release_value == "15", release_value)
        check("emulator:api", api == "35", api)
        check("emulator:version", "versionName=0.1.0" in package and "versionCode=1" in package, "0.1.0(1)")
        check("emulator:target", "targetSdk=36" in package, "targetSdk36")
        check("emulator:focus", "com.alarmquest/.MainActivity" in activity and "Resumed" in activity, "MainActivity")

    passed = sum(ok for _, ok, _ in checks)
    failed = len(checks) - passed
    print(f"SUMMARY PASS={passed} FAIL={failed} TOTAL={len(checks)}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
