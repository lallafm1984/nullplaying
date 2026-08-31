#!/usr/bin/env python3
"""PD audit for P6r Shield Breath confirmed-Shield token and canonical Active cost."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6r-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6r-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6r-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6R_SHIELD_BREATH_v0.1.md"
RUNTIME = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextShieldBreathRuntimeP6r.kt"
RUNTIME_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextShieldBreathRuntimeP6rTest.kt"
RESOLVER = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt"
RESOLVER_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt"
COST = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLateRootPassiveRuntimeP5y.kt"
COST_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextLateRootPassiveRuntimeP5yTest.kt"
BALANCE = ROOT / "tools/skill_wave1_active5_passive3_v0_1_review.py"
SETTLEMENT = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt"

CONTENT_HASH = "ca500d4976dba53416472bc6b5b6c9cc1500ac7dc5cb2db0c9e0d404a7565182"
APK_SHA = "064132afc4ca77098e81a35d63c885de3b76a4250f00264c5a463d3f19c084b7"
SCREEN_SHA = "710b6590affa287d532d49baaa161d5a635562c737267343ab088a9f8c3334a7"
UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
APK_SIZE = 37_334_986
FREEZE = {
    RUNTIME: "d362787e24efeee9d9d57dd5238a86124223069394216cb2aff6d41f643688e8",
    RUNTIME_TEST: "c0ca868530f7e52acbdcf4247770cdfb962b9c9409a4e667acd34b5244bd9da4",
    RESOLVER: "fbe6f44e15f8b1053d0891dd9bc1e3cc3414c7181498f4eb3f1998cbadadd0cd",
    RESOLVER_TEST: "63c32b615f12b109ce340f16cc8e766cf4cae97128b859a9bd9300ba300451a1",
    COST: "3e2ce8781f1931bf4a8b1349157c8e0e3c43bdcf7194f7738b382a004874f8f0",
    COST_TEST: "c19f9473dd776f8ee0576493ef5f698120f7e292e7e8b91b7f7763bea7ec95de",
    BALANCE: "5bef0e4c4174b8ac02b48a637a131493e58f860981d00567a4b7081a0409c9be",
    SETTLEMENT: "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def totals(path: Path) -> tuple[int, int, int, int]:
    result = [0, 0, 0, 0]
    for report in path.glob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            result[index] += int(root.attrib.get(key, "0"))
    return tuple(result)


def parse_audit(text: str) -> dict[str, str]:
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def command(*args: str) -> str:
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)
    return (result.stdout + result.stderr).strip()


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
    cost = COST.read_text()
    cost_test = COST_TEST.read_text()
    balance = BALANCE.read_text()
    settlement = SETTLEMENT.read_text()
    audit = parse_audit(AUDIT.read_text())
    doc = DOC.read_text()

    runtime_anchors = {
        "rules": "aq.shield-breath.p6r.v0.1",
        "content": CONTENT_HASH,
        "definition": "V_NEXT_P6R_SHIELD_BREATH_DEFINITION_COUNT = 1",
        "skill": "aq.skill.warrior.p3.shieldbreath",
        "owner": 'source.ownerScope != "WARRIOR"',
        "pattern": 'source.pattern != "RESOURCE"',
        "growth": 'source.growthField != "resource_cost_modifier_bps"',
        "condition": 'source.conditionId != "after_shield_active"',
        "host": 'source.hostScope != "NEXT_NON_SURVIVAL_ACTIVE"',
        "group": 'source.stackGroup != "aq.stack.passive.resource_discount"',
        "policy": 'source.stackPolicy != "ADD_THEN_CLAMP"',
        "anchors": "listOf(-200, -300, -400, -500, -600)",
        "fixed": "조건부 발동=after_shield_active·미충족 시 효과0",
        "event": "P5wPassiveEvent.AFTER_OUTGOING_PACKET",
        "deferred": "deferredAfterOutgoingIds.size == 9",
        "five patterns": '"COMMON_HEAL"' in runtime and '"COMMON_SHIELD"' in runtime and
            '"SURVIVAL_BARRIER"' in runtime and '"SURVIVAL_HEAL"' in runtime and
            '"SURVIVAL_SHIELD"' in runtime,
        "classifier": "pattern.uppercase() !in P6R_SURVIVAL_PATTERNS",
        "boolean": "val shieldBreathArmed: Boolean",
        "confirmed shield": "shieldActionCommitted: Boolean",
        "consume then rearm": "val armedAfterConsume = if (plan.consumesShieldBreath) false",
        "rearm": "if (shieldActionCommitted && shieldBreathPlan != null) true",
        "stale": "plan.ledgerBeforeHash == ledger.ledgerHash",
        "replay": "receiptId !in ledger.processedReceiptIds",
        "receipt count": "receipts.size == committedActiveCount",
        "consume invariant": "consumedCount <= shieldTriggerCount",
        "shared cap": ".coerceIn(-V_NEXT_P5Z_SHARED_DISCOUNT_CAP_BPS, 0)",
        "positive floor": "positiveCostFloor=1000",
        "half up": "round=HALF_UP",
        "zero": "zeroCost=0",
        "no expiry": "expiry=NONE_REGISTRY_AUTHORITATIVE",
        "live false": "val liveReady: Boolean get() = false",
        "delayed": "delayed=CONSUME_AT_ARM_NO_REFUND",
    }
    for name, anchor in runtime_anchors.items():
        ok = anchor if isinstance(anchor, bool) else anchor in runtime
        check(f"runtime:{name}", ok, anchor)

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6r.v0.17",
        "bind failure": "PASSIVE_SHIELD_BREATH_BIND_REJECTED",
        "ledger failure": "PASSIVE_SHIELD_BREATH_LEDGER_REJECTED",
        "compile": "VNextShieldBreathRuntimeP6rCompiler.compile(registry)",
        "bind": "shieldBreathRuntime.bind(",
        "content field": "val shieldBreathContentHash: String",
        "session field": "val shieldBreathSessionHash: String",
        "ledger field": "val finalShieldBreathLedgerHash: String",
        "trigger field": "val shieldBreathTriggerCount: Int",
        "consumed field": "val shieldBreathConsumedCount: Int",
        "armed field": "val finalShieldBreathArmed: Boolean",
        "preview map": "shieldBreathPreviewByActionId",
        "preview prepare": "shieldBreathSession.prepareActive(",
        "preview hash equality": "shieldBreathPreview?.planHash != shieldBreathPlan.planHash",
        "preview cost equality": "resourceActivePlan.candidateCostBps != shieldBreathPreviewCost",
        "combined cost": "combineP6rResourceCostModifier(",
        "commit": "shieldBreathSession.commitActive(",
        "confirmed shield": "dispatched.shieldActionCommitted",
        "receipt": '"$receiptId:p6r-active"',
        "detail": '"${shieldBreathPlan.detail}|"',
        "preview detail": '"P6R_PREVIEW:plan=${shieldBreathPreview.planHash}|"',
        "hash content": "append(transaction.shieldBreathContentHash)",
        "hash armed": "append(transaction.finalShieldBreathArmed)",
        "handled": "addAll(VNextShieldBreathRuntimeP6r.supportedDefinitionIds())",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)
    check(
        "resolver:p6n commit precedes p6r commit",
        resolver.index("recordResourceTransition(resourceActiveCommit.ledgerAfter)") <
        resolver.index("val nextShieldBreathLedger = runCatching"),
        "P6n then P6r",
    )

    unit_anchors = {
        "coverage": "coverage_enables_shield_breath_and_reduces_after_outgoing_deferred_set_to_nine",
        "five anchors": "mapOf(1 to -200, 25 to -300, 50 to -400, 75 to -500, 100 to -600)",
        "survival preserve": "explicit_survival_patterns_and_basic_preserve_the_token",
        "registry exhaustive": "all_registry_actives_use_the_five_pattern_closed_set",
        "152": "assertEquals(152, classified.size)",
        "14": "assertEquals(14, excluded.size)",
        "warrior 77": "assertEquals(77, warriorAccessible.size)",
        "warrior 74": "assertEquals(74, warriorAccessible.count { it.second })",
        "hybrid": "typed_non_survival_shield_contract_consumes_the_old_token_then_rearms_a_fresh_one",
        "zero cost": "zero_cost_hp_to_resource_still_consumes_the_token",
        "preview": "ai_preview_and_selected_commit_share_one_decision",
        "minimum floor": "MINIMUM_COST_ACTIVE",
        "delayed": "delayed_non_survival_active_consumes_at_successful_arm_without_refund",
        "replay": "ledger_rejects_replay_stale_plans_and_wrong_class_loadouts",
    }
    for name, anchor in unit_anchors.items():
        check(f"unit:{name}", anchor in runtime_test, anchor)

    integration_anchors = {
        "production p5l p5u": "p6r production shield arms and delayed preview equals prepaid commit before cancel without refund",
        "production p5k": "p6r production common shield arms while basics preserve the token",
        "minimum receipt": "p6r positive minimum cost stays at one thousand in AI preview and actual receipt",
        "p6n reject": "p6r resource transition rejection occurs before confirmed shield can mutate its ledger",
        "p6j passive shield": "p6j broken banner grants post packet shield",
        "cancel": "DELAYED_OWN_ROOT_CANCELLED",
        "preview 2880": '"P6R_PREVIEW:plan=$selectedPlanHash|candidateCost=2880"',
        "actual 2880": '"committedCost=2880|combinedCost=-400"',
        "minimum 1000": '"committedCost=1000|combinedCost=-400"',
        "p6j trigger zero": "assertEquals(0, result.shieldBreathTriggerCount)",
        "reject untouched": "assertEquals(result.initialShieldBreathLedgerHash, result.finalShieldBreathLedgerHash)",
        "rehash": "VNextBattleResolverTransaction.transactionHash(result)",
    }
    for name, anchor in integration_anchors.items():
        check(f"integration:{name}", anchor in resolver_test, anchor)

    cost_anchors = {
        "canonical import": "import com.nullplaying.engine.vnext.VNextSkillContract",
        "canonical call": "VNextSkillContract.finalResourceCost(",
        "zero test": "assertEquals(0, plan.adjustResourceCost(0, -1_500))",
        "floor -600": "assertEquals(1_000, plan.adjustResourceCost(1_000, -600))",
        "floor -1500": "assertEquals(1_000, plan.adjustResourceCost(1_000, -1_500))",
        "half up": "assertEquals(1_499, plan.adjustResourceCost(1_500, -10))",
    }
    for name, anchor in cost_anchors.items():
        check(f"cost:{name}", anchor in (cost + cost_test), anchor)

    balance_anchors = {
        "start snapshot": "shield_breath_ready_at_action_start = runtime.shield_breath_ready",
        "candidate": 'runtime.shield_breath_ready and skill.automation_band != "SURVIVAL"',
        "consume old": "shield_breath_ready_at_action_start and selected.automation_band != \"SURVIVAL\"",
        "rearm": 'selected.protection_kind == "SHIELD" and has(passive_ids, "shieldbreath")',
        "pd seeds": "seeds = 200 if args.pd else args.seeds",
        "loadouts": "ALL_153600_LOADOUT_LEVEL_SUBSETS_PRESERVE_HARD_BUDGETS",
    }
    for name, anchor in balance_anchors.items():
        check(f"balance:{name}", anchor in balance, anchor)

    check("settlement:present", "fun resolveKill(" in settlement, "present")
    check("settlement:uncoupled", "P6r" not in settlement and "p6r" not in settlement, "uncoupled")
    for path, expected in FREEZE.items():
        check(f"freeze:{path.name}", sha(path) == expected, sha(path))
    check("artifact:apk size", APK.stat().st_size == APK_SIZE, APK.stat().st_size)
    check("artifact:apk hash", sha(APK) == APK_SHA, sha(APK))
    check("artifact:screen hash", sha(SCREEN) == SCREEN_SHA, sha(SCREEN))
    check("artifact:ui hash", sha(UI) == UI_SHA, sha(UI))
    check("artifact:png", SCREEN.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), SCREEN.stat().st_size)
    check("artifact:ui parse", ET.parse(UI).getroot().tag == "hierarchy", "hierarchy")
    ui_text = UI.read_text()
    check("artifact:empty roster", "아직 캐릭터가 없습니다" in ui_text, "empty")
    check("artifact:create", "새 캐릭터" in ui_text, "create")

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    total = tuple(sum(values) for values in zip(engine, debug, release))
    check("reports:engine", engine == (762, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (938, 0, 0, 0), total)
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(1 for issue in lint.findall("issue") if issue.attrib.get("severity") == "Error")
    warnings = sum(1 for issue in lint.findall("issue") if issue.attrib.get("severity") == "Warning")
    check("reports:lint errors", errors == 0, errors)
    check("reports:lint warnings", warnings == 22, warnings)

    expected_audit = {
        "serial": "emulator-5554", "avdName": "alarmquest-qa", "androidRelease": "15",
        "apiLevel": "35", "packageName": "com.nullplaying", "versionName": "0.1.0",
        "versionCode": "1", "targetSdk": "36", "apkSizeBytes": str(APK_SIZE),
        "apkSha256": APK_SHA, "installResult": "Success", "pmClearResult": "Success",
        "launchState": "COLD", "coldTotalTimeMs": "4358", "coldWaitTimeMs": "4362",
        "topResumedActivity": "com.nullplaying/.MainActivity", "freshRosterEmpty": "true",
        "androidRuntimeFatalCount": "0", "emulatorScreenshotSha256": SCREEN_SHA,
        "emulatorUiDumpSha256": UI_SHA, "engineTests": "762", "appDebugTests": "88",
        "appReleaseTests": "88", "totalTests": "938", "testFailures": "0",
        "testErrors": "0", "testSkipped": "0", "lintErrors": "0", "lintWarnings": "22",
        "featureDefaultEnabled": "false", "liveSettlementEnabled": "false",
        "previousPassiveDefinitionsEnabled": "51", "shieldBreathDefinitionsEnabled": "1",
        "totalPassiveDefinitionsEnabled": "52", "remainingPassiveDefinitions": "38",
        "shieldBreathRulesVersion": "aq.shield-breath.p6r.v0.1",
        "shieldBreathContentHash": CONTENT_HASH,
        "battleResolverRulesVersion": "aq.battle-resolver-transaction.p6r.v0.17",
        "shieldBreathConfirmedShieldTrigger": "true", "shieldBreathBasicExcluded": "true",
        "shieldBreathFivePatternClosedSet": "true", "shieldBreathRegistryActiveCount": "152",
        "shieldBreathRegistrySurvivalCount": "14", "shieldBreathRegistryNonSurvivalCount": "138",
        "shieldBreathWarriorLoadableCount": "77", "shieldBreathWarriorExcludedCount": "3",
        "shieldBreathWarriorEligibleCount": "74", "shieldBreathNoExpirySupersedesWave1": "true",
        "shieldBreathDelayedConsumeAtArm": "true", "shieldBreathCancelRefund": "false",
        "shieldBreathConsumeThenRearm": "true", "shieldBreathPassiveShieldDoesNotArm": "true",
        "shieldBreathSharedDiscountCapBps": "1500", "positiveResourceCostFloorBps": "1000",
        "positiveResourceCostRounding": "HALF_UP", "zeroResourceCostPreserved": "true",
        "balancePdSeeds": "200", "balancePdResult": "PASS_9_OF_9",
        "balanceCanonicalHash": "02246661576f1b664c902e80a09f6b39b7789b5bd2751b0da5e3c416abd0ebc3",
        "warriorMaxCellWinDeltaPp": "8.50", "warriorMaxMedianRoundDelta": "1.0",
        "warriorMaxP90HpLossDeltaPp": "2.83", "warriorWorstWeightedWinLossPp": "0.00",
        "warriorLevel100AttackCap": "112000", "visualAssetRequired": "false",
        "designTeamImageRequested": "false", "pdFinalDecision": "GO_ENGINE_SCOPE_FEATURE_OFF",
        "systemAuditP0Count": "0", "systemAuditP1Count": "0",
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, audit.get(key))

    for anchor in [
        "PD 최종 GO", "Passive: **52/90**", "방패 호흡", "consume → rearm",
        "2턴 만료", "supersede", "152 Active", "생존 14", "할인 대상 74종",
        "P6n 실제 비용 commit", "최소 1,000", "HALF_UP", "HP_TO_RESOURCE",
        "200시드", "8.50%p", "112,000 유지", "잔여 Passive: **38/90 OFF**",
        "surrogate", "디자인팀 이미지 요청은 하지 않았다", "feature OFF", APK_SHA,
        FREEZE[RUNTIME], FREEZE[RESOLVER], "engine 762 + app debug 88 + app release 88 = **938**",
    ]:
        check(f"doc:{anchor[:30]}", anchor in doc, anchor)

    if args.with_emulator:
        devices = command("adb", "devices", "-l")
        package = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying")
        activity = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities")
        crash = command("adb", "-s", "emulator-5554", "logcat", "-d", "-b", "crash")
        check("emulator:connected", "emulator-5554" in devices and " device " in devices, devices)
        check("emulator:release", command("adb", "-s", "emulator-5554", "shell", "getprop", "ro.build.version.release") == "15", "15")
        check("emulator:api", command("adb", "-s", "emulator-5554", "shell", "getprop", "ro.build.version.sdk") == "35", "35")
        check("emulator:version", "versionName=0.1.0" in package and "versionCode=1" in package, "0.1.0(1)")
        check("emulator:target", "targetSdk=36" in package, "36")
        check("emulator:focus", "com.nullplaying/.MainActivity" in activity and "Resumed" in activity, "MainActivity")
        check("emulator:crash", "FATAL EXCEPTION" not in crash, crash or "empty")

    passed = sum(ok for _, ok, _ in checks)
    failed = len(checks) - passed
    print(f"SUMMARY PASS={passed} FAIL={failed} TOTAL={len(checks)}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
