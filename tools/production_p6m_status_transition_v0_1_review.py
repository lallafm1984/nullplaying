#!/usr/bin/env python3
"""PD audit for P6m actual-cleanse to next-DIRECT evasion transition."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6m-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6m-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6m-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6M_STATUS_TRANSITION_v0.1.md"
RUNTIME = ROOT / (
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextStatusTransitionRuntimeP6m.kt"
)
RUNTIME_TEST = ROOT / (
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextStatusTransitionRuntimeP6mTest.kt"
)
P5H = ROOT / (
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextStatusMutationSemanticAdapterP5h.kt"
)
P5H_TEST = ROOT / (
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextStatusMutationSemanticAdapterP5hTest.kt"
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

APK_SHA = "5908e8a3121f05e4f2de5be22bf44bb0cfe18997f5cbbc8feca16ea96f6f86ed"
SCREEN_SHA = "8cac44f39942b2a05cd22510b477afcc8dd06f8adde0c9b089b354f9f528b490"
UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
APK_SIZE = 37_817_752
FREEZE = {
    RUNTIME: "8f2a5ad2d6606b56b183cb4a0c7985435a481ade3d940280f9f37110856762fc",
    RUNTIME_TEST: "9237eea65473549193a9cd9721d878006ff0aeba4a16675589a64a69d54061f4",
    P5H: "9cddf9ed487808033351216f98ea8f1df48e5bd5e123020de208c0ab16b245b6",
    P5H_TEST: "4e28174ec5026e11bf16d9cd8749f148e8d155451d499fb363ff4b2bf44f3162",
    RESOLVER: "e91f7c4c791b6e3f48082b6ff16f14b53cbecc7f3d016f055550e3b1a61d7a42",
    RESOLVER_TEST: "c15d02c9a603a616e8c858fb85961bcae5a21ecf316436e83d4a7b84a945b594",
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
    p5h = P5H.read_text()
    p5h_test = P5H_TEST.read_text()
    resolver = RESOLVER.read_text()
    resolver_test = RESOLVER_TEST.read_text()
    settlement = SETTLEMENT.read_text()
    audit = parse_audit(AUDIT.read_text())
    doc = DOC.read_text()

    runtime_anchors = {
        "rules": "aq.status-transition.p6m.v0.1",
        "content": "5d29f9a26c9f472403d44a9c5480c0a4b1d6e482d8adc2de82a847f31f5c5a7c",
        "definition count": "V_NEXT_P6M_STATUS_TRANSITION_DEFINITION_COUNT = 1",
        "evasion cap": "V_NEXT_P6M_POST_CLEANSE_EVASION_CAP_BPS = 1_200",
        "freed id": "aq.skill.world.w7.freedstep",
        "sturdy id": "aq.skill.common.w7.sturdyshoes",
        "control tags": 'setOf("STAGGER", "SILENCE", "BIND")',
        "foundation": "val foundationReady: Boolean",
        "deferred exact": "deferredStatusTransitionIds == listOf(STURDY_SHOES)",
        "live false": "val liveReady: Boolean get() = false",
        "catalog fail closed": "P5W_PASSIVE_CATALOG_REJECTED",
        "ledger replay": "receiptId !in ledger.processedReceiptIds",
        "actual filter": "removedTags.filter { it in controlTagsP6m }",
        "once arm": "!ledger.triggerObserved",
        "anchor clamp": "coerceIn(300, 700)",
        "direct": 'val direct = delivery == "DIRECT"',
        "fixed zero": "if (consumes && !fixedHit)",
        "consume direct": "val consumes = direct && ledger.evasionTokenArmed",
        "stale plan": "plan.ledgerBeforeHash == ledger.ledgerHash",
        "clear token": "fun expireEncounterEnd",
        "all owner": 'source.ownerScope != "ALL"',
        "pattern": 'source.pattern != "POST_CLEANSE_EVASION"',
        "growth": 'source.growthField != "evasion_add_bps"',
        "condition": 'source.conditionId != "self_control_removed"',
        "stack group": 'source.stackGroup != "aq.trigger.wave7.cleanse_response"',
        "shared": 'source.stackPolicy != "SHARED_LEDGER"',
        "stack cap": "source.stackCapBps != 1_200",
        "anchors": "listOf(300, 400, 500, 600, 700)",
        "tradeoff": "조우 1회·다음 enemy DIRECT 1회·고정명중에는 0",
        "event": "P5wPassiveEvent.STATUS_TRANSITION",
        "ledger hash receipts": 'processedReceiptIds.sorted().joinToString(",")',
        "manifest fixed": "fixedHit=CONSUME_ZERO",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime:{name}", anchor in runtime, anchor)

    p5h_anchors = {
        "typed field": "val removedSelfStatusTags: List<String> = emptyList()",
        "cleanse only": "mutation.outcome == P5gStatusMutationOutcome.CLEANSED",
        "committed ids": "it.instanceId in mutation.committedInstanceIds",
        "source tags": ".map(LifecycleStatus::tag).sorted()",
    }
    for name, anchor in p5h_anchors.items():
        check(f"p5h:{name}", anchor in (resolver + p5h), anchor)
    check("p5h-test:typed receipt", 'assertEquals(listOf("POISON"), result.removedSelfStatusTags)' in p5h_test, "POISON")

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6m.v0.12",
        "bind failure": "PASSIVE_STATUS_TRANSITION_BIND_REJECTED",
        "ledger failure": "PASSIVE_STATUS_TRANSITION_LEDGER_REJECTED",
        "compile": "VNextStatusTransitionRuntimeP6mCompiler.compile(registry)",
        "bind": "statusTransitionRuntime.bind(",
        "content": "statusTransitionContentHash = V_NEXT_P6M_STATUS_TRANSITION_CONTENT_HASH",
        "typed observation": "dispatched.removedSelfStatusTags.isNotEmpty()",
        "commit removal": "statusTransitionSession.commitSelfStatusRemoval(",
        "incoming prepare": "statusTransitionSession.prepareIncoming(",
        "evasion sum": "statusTransitionIncomingPlan.evasionAddBps",
        "incoming commit": "statusTransitionSession.commitIncoming(",
        "fixed policy": 'ability?.accuracyPolicy?.startsWith("FIXED") == true',
        "end expire": "statusTransitionSession.expireEncounterEnd(statusTransitionLedger)",
        "hash content": "append(transaction.statusTransitionContentHash)",
        "hash removals": "append(transaction.actualSelfControlRemovalCount)",
        "hash token": "append(transaction.finalFreedStepModifierBps)",
        "handled": "addAll(VNextStatusTransitionRuntimeP6m.supportedDefinitionIds())",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)

    unit_anchors = {
        "coverage": "coverage_enables_freed_step_and_defers_sturdy_shoes_until_hostile_movement_exists",
        "anchors": "mapOf(1 to 300, 25 to 400, 50 to 500, 75 to 600, 100 to 700)",
        "actual only": "all_five_anchors_arm_only_after_an_actual_self_control_removal",
        "once and direct": "first_control_removal_arms_once_and_next_enemy_direct_consumes_once",
        "periodic fixed": "periodic_packet_does_not_consume_but_fixed_hit_direct_consumes_with_zero_bonus",
        "end expiry": "encounter_end_expires_an_unspent_token_without_rearming_it",
        "fail closed": "replay_stale_plan_and_duplicate_loadout_fail_closed",
        "level 50": "assertEquals(500, plan.evasionAddBps)",
        "fixed zero": "assertEquals(0, fixed.evasionAddBps)",
        "single consumed": "assertEquals(1, consumed.consumedCount)",
    }
    for name, anchor in unit_anchors.items():
        check(f"unit-test:{name}", anchor in runtime_test, anchor)

    integration_anchors = {
        "case": "p6m actual self control cleanse arms and next monster direct consumes freed step",
        "active": '"aq.skill.world.w7.breakchains"',
        "passive": '"aq.skill.world.w7.freedstep"',
        "seed": 'tag = "STAGGER"',
        "dispatcher": "VNextCompositeSemanticDispatcherP5vCompiler.compile(registry)",
        "typed detail": "P6M_STATUS_TRANSITION:removed=STAGGER",
        "incoming": "P6M_INCOMING:direct=true",
        "evasion 500": '"evasion=500" in consumed.detail',
        "consume": '"consume=true" in consumed.detail',
        "content": "V_NEXT_P6M_STATUS_TRANSITION_CONTENT_HASH",
        "transition once": "assertEquals(1, result.statusTransitionCommittedCount)",
        "removal once": "assertEquals(1, result.actualSelfControlRemovalCount)",
        "arm once": "assertEquals(1, result.freedStepArmedCount)",
        "consume once": "assertEquals(1, result.freedStepConsumedCount)",
        "token false": "assertFalse(result.finalFreedStepTokenArmed)",
        "rehash": "VNextBattleResolverTransaction.transactionHash(result)",
    }
    for name, anchor in integration_anchors.items():
        check(f"integration-test:{name}", anchor in resolver_test, anchor)

    check("settlement:resolveKill", "fun resolveKill(" in settlement, "present")
    check("settlement:uncoupled", "P6m" not in settlement and "p6m" not in settlement, "uncoupled")
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
    check("reports:engine", engine == (709, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (885, 0, 0, 0), total)
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
        "coldTotalTimeMs": "4909", "coldWaitTimeMs": "4925",
        "topResumedActivity": "com.alarmquest/.MainActivity", "freshRosterEmpty": "true",
        "androidRuntimeFatalCount": "0", "emulatorScreenshotSha256": SCREEN_SHA,
        "emulatorUiDumpSha256": UI_SHA, "engineTests": "709", "appDebugTests": "88",
        "appReleaseTests": "88", "appTests": "176", "totalTests": "885",
        "testFailures": "0", "testErrors": "0", "testSkipped": "0",
        "lintErrors": "0", "lintWarnings": "22", "featureDefaultEnabled": "false",
        "liveSettlementEnabled": "false", "passiveDefinitionsClassified": "90",
        "passiveSlotCap": "3", "previousPassiveDefinitionsEnabled": "44",
        "statusTransitionDefinitionsEnabled": "1", "totalPassiveDefinitionsEnabled": "45",
        "remainingPassiveEffectsEnabled": "0", "remainingPassiveDefinitions": "45",
        "statusTransitionDefinitionsClassified": "2", "statusTransitionDefinitionsDeferred": "1",
        "statusTransitionRulesVersion": "aq.status-transition.p6m.v0.1",
        "statusTransitionContentHash": "5d29f9a26c9f472403d44a9c5480c0a4b1d6e482d8adc2de82a847f31f5c5a7c",
        "battleResolverRulesVersion": "aq.battle-resolver-transaction.p6m.v0.12",
        "freedStepEnabled": "true", "freedStepOwnerClass": "ALL",
        "freedStepLv1EvasionAddBps": "300", "freedStepLv100EvasionAddBps": "700",
        "freedStepStackCapBps": "1200", "freedStepControlTags": "BIND,SILENCE,STAGGER",
        "freedStepOncePerEncounter": "true", "freedStepNextEnemyDirectCount": "1",
        "freedStepFixedHitEvasionBps": "0", "freedStepFixedHitConsumes": "true",
        "freedStepPeriodicConsumes": "false", "freedStepEncounterEndExpires": "true",
        "sturdyShoesEnabled": "false",
        "sturdyShoesDeferredReason": "HOSTILE_MOVEMENT_OR_STAGGER_LIFECYCLE_UNAVAILABLE",
        "typedRemovedSelfStatusTags": "true", "actualCommittedInstanceReceiptRequired": "true",
        "cleansePreviewDoesNotArm": "true", "cleanseResistDoesNotArm": "true",
        "cleanseNoTargetDoesNotArm": "true", "nonControlRemovalDoesNotArm": "true",
        "secondControlRemovalDoesNotRearm": "true",
        "statusTransitionLedgerInTransactionHash": "true",
        "statusTransitionCountersInTransactionHash": "true",
        "runtimeHandledPassivesFilteredFromAdapter": "true",
        "campaignStatusCarryoverWiredToLiveCaller": "false",
        "visualAssetRequired": "false", "designTeamImageRequested": "false",
        "legacySettlementHash": FREEZE[SETTLEMENT],
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, audit.get(key))

    doc_anchors = {
        "conditional": "조건부 승인",
        "enabled 45": "Passive 45종 구현",
        "remaining 45": "미구현 45종",
        "status 1 of 2": "1/2 실행, 보류 1",
        "fixed consume": "고정명중 DIRECT: 회피 보정은 0",
        "level 9999": "Lv9,999 장기 운영 안전성",
        "sturdy reason": "몬스터 제어 상태 application pipeline",
        "design deferred": "디자인팀 이미지 요청과 앱 UI 변경은 하지 않았다",
        "next resource": "RESOURCE_TRANSITION 3종",
        "live off": "라이브 정산은 OFF",
    }
    for name, anchor in doc_anchors.items():
        check(f"doc:{name}", anchor in doc, anchor)

    if args.with_emulator:
        devices = command("adb", "devices", "-l")
        version = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest")
        focus = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities")
        crash = command("adb", "-s", "emulator-5554", "logcat", "-d", "-b", "crash", "AndroidRuntime:E", "*:S")
        check("emulator:connected", "emulator-5554" in devices and " device " in devices, devices.splitlines()[-1])
        check("emulator:version", "versionName=0.1.0" in version and "versionCode=1" in version, "0.1.0(1)")
        check("emulator:target", "targetSdk=36" in version, "36")
        check("emulator:focus", "topResumedActivity" in focus and "com.alarmquest/.MainActivity" in focus, "MainActivity")
        check("emulator:fatal", "AndroidRuntime" not in crash, crash or "0")

    passed = sum(ok for _, ok, _ in checks)
    failed = len(checks) - passed
    print(f"SUMMARY PASS={passed} FAIL={failed} TOTAL={len(checks)}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
