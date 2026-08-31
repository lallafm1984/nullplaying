#!/usr/bin/env python3
"""Fail-closed PD audit for P6s Heard Prayer and the P6p v0.2 Heal migration."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextHeardPrayerRuntimeP6s.kt"
RUNTIME_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextHeardPrayerRuntimeP6sTest.kt"
P6P = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCursedMagicResistanceRuntimeP6p.kt"
P6P_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCursedMagicResistanceRuntimeP6pTest.kt"
RESOLVER = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt"
RESOLVER_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt"
BALANCE = ROOT / "tools/skill_wave1_active5_passive3_v0_1_review.py"
SETTLEMENT = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6S_HEARD_PRAYER_v0.1.md"
P6P_DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6P_CURSED_MRES_HEAL_v0.2.md"
AUDIT = ROOT / "artifacts/audit/production-p6s-emulator-verification-v0.1.txt"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
SCREEN = ROOT / "artifacts/audit/production-p6s-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6s-emulator-ui-v0.1.xml"

P6S_CONTENT = "fb7d10c1c8341c0cd746091e4595d23800fc78e7a1bf04c91c53be6524cdc7fb"
P6P_CONTENT = "51e121017dea5fccc0b252bae84c1eb4e49dc4e35249e63e04ea02774152a2f1"
APK_SIZE = 37_367_754
APK_SHA = "0c6d6fdb6193289e40886a4a377d475e5406f7c32cacaf2ef4d8f9df773438b9"
SCREEN_SHA = "d81cad83cc67a70389a4e7073cd18bb8fcd057db863bd7322f59a00f41ce822e"
UI_SHA = "8d2715bfaeb699c6c90eef0c84f3980e74d30261e84739737ace14d1aa89c844"
FREEZE = {
    RUNTIME: "b7fd60a959f90ea6bc1aea791c9cb95a212c842d695f155111114c0558c92c39",
    RUNTIME_TEST: "5a054ec55e5def6ff46c87556a95f900c6036449116d82c66b86f98d64c33d9f",
    P6P: "f91b5ae251e19790f76ad330f15c75c54fb9e5d96cb7df8858e632cf0d3afe85",
    P6P_TEST: "6f1468ddadcb3cc5fae91c112bcd514d317748b7433cf6e4e1624cfcdc598a5e",
    RESOLVER: "e91afd8f3a09d0c8b09b2412df4038c9e3327588591888339813bd85dc4057b9",
    RESOLVER_TEST: "bd3fd63dc441c350ca0daf47c095650795ebbaf6ed233e8e0ae18e419340b946",
    BALANCE: "5bef0e4c4174b8ac02b48a637a131493e58f860981d00567a4b7081a0409c9be",
    SETTLEMENT: "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def totals(path: Path) -> tuple[int, int, int, int]:
    values = [0, 0, 0, 0]
    for report in path.glob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            values[index] += int(root.attrib.get(key, "0"))
    return tuple(values)


def parsed_audit() -> dict[str, str]:
    return dict(line.split("=", 1) for line in AUDIT.read_text().splitlines() if "=" in line)


def adb(*args: str) -> str:
    result = subprocess.run(("adb", "-s", "emulator-5554", *args), cwd=ROOT, text=True,
                            capture_output=True, check=False)
    return (result.stdout + result.stderr).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: object) -> None:
        checks.append((name, ok, str(detail)))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")

    required = [RUNTIME, RUNTIME_TEST, P6P, P6P_TEST, RESOLVER, RESOLVER_TEST, BALANCE,
                SETTLEMENT, DOC, P6P_DOC, AUDIT, APK, SCREEN, UI]
    for path in required:
        check(f"file:{path.relative_to(ROOT)}", path.is_file(), path.exists())
    if not all(path.is_file() for path in required):
        return 1

    runtime = RUNTIME.read_text()
    runtime_test = RUNTIME_TEST.read_text()
    p6p = P6P.read_text()
    p6p_test = P6P_TEST.read_text()
    resolver = RESOLVER.read_text()
    resolver_test = RESOLVER_TEST.read_text()
    doc = DOC.read_text()
    p6p_doc = P6P_DOC.read_text()
    balance = BALANCE.read_text()
    audit = parsed_audit()

    runtime_anchors = {
        "rules": "aq.heard-prayer.p6s.v0.1", "content": P6S_CONTENT,
        "definition count": "V_NEXT_P6S_HEARD_PRAYER_DEFINITION_COUNT = 1",
        "stable id": "aq.skill.cleric.p1.heardprayer", "owner": 'source.ownerScope != "CLERIC"',
        "condition": 'source.conditionId != "after_overheal"',
        "host": 'source.hostScope != "NEXT_SHIELD_ACTIVE"',
        "group": 'source.stackGroup != "aq.stack.passive.resource_discount"',
        "policy": 'source.stackPolicy != "ADD_THEN_CLAMP"',
        "anchors": "listOf(-200, -300, -400, -500, -600)",
        "event": "P5wPassiveEvent.AFTER_OUTGOING_PACKET", "shield 10": "shieldActiveIds.size == 10",
        "cleric 4": "clericLoadableShieldActiveIds.size == 4",
        "typed 7": "typedActiveHealProducerIds.toSet() == P6S_TYPED_ACTIVE_HEAL_IDS",
        "deferred hybrid": "P6S_DEFERRED_HYBRID_HEAL_PATTERN", "deferred eight": "deferredAfterOutgoingIds.size == 8",
        "source active": 'sourceKind: String = "ACTIVE"', "pre incoming": "preIncomingAttemptedHealHp",
        "final attempt": "attemptedHealHp", "actual": "actualHealHp", "overheal": "overhealHp",
        "factor floor": ".coerceIn(2_000, 15_000)", "half up": "roundHalfUpP6s",
        "budget separation": "budgetNominalBps", "budget positive": "positiveFactor",
        "action bind": "actionDefinitionId == committedActivePlan.definitionId",
        "target bind": "targetIdentity == committedTargetIdentity",
        "base bind": "baseHealHp == roundHalfUpP6s", "ceiling bind": "healingCeilingHp == expectedCeiling",
        "pre heal exact bind": "hpBeforeHeal == expectedHpBeforeHeal",
        "final hp bind": "hpBeforeHeal + actualHealHp == actorAfterHp",
        "producer id": "definitionId in P6S_TYPED_ACTIVE_HEAL_IDS",
        "always heal six": "P6S_ALWAYS_TYPED_ACTIVE_HEAL_IDS",
        "conditional life": "LIFE_DISTRIBUTION_IF_PRE_HEAL_HP_LT_HALF",
        "boolean": "val heardPrayerArmed: Boolean", "confirmed shield": "shieldActionCommitted: Boolean",
        "consume": "val armedAfterConsume = if (plan.consumesHeardPrayer) false",
        "rearm": "if (overhealTriggered) true else armedAfterConsume",
        "stale": "plan.ledgerBeforeHash == ledger.ledgerHash", "replay": "receiptId !in ledger.processedReceiptIds",
        "shared cap": ".coerceIn(-V_NEXT_P5Z_SHARED_DISCOUNT_CAP_BPS, 0)",
        "no expiry": "expiry=NONE_REGISTRY_AUTHORITATIVE", "active only": "scope=ACTIVE_ONLY_ENCOUNTER_END_EXCLUDED",
        "no current simultaneous": "simultaneousShieldHeal=CLOSED_SET_NONE_DEFERRED",
        "future hybrid order": "futureHybridOrder=CONSUME_THEN_REARM",
        "live false": "val liveReady: Boolean get() = false",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime:{name}", anchor in runtime, anchor)

    p6p_anchors = {
        "rules v02": "aq.cursed-mres-heal.p6p.v0.2", "content": P6P_CONTENT,
        "clamp 8000": "coerceIn(-8_000, 0)", "typed pre": "healOutcome.preIncomingAttemptedHealHp",
        "typed no recompute": "val observed = minOf(capacity, healOutcome.preIncomingAttemptedHealHp)",
        "legacy floor": "* (10_000L + modifierBps).coerceAtLeast(0L) / 10_000L",
        "typed contract": "activeHeal=TYPED_PRE_INCOMING_THEN_HALF_UP_FINAL_THEN_CAP_OVERHEAL",
        "legacy contract": "encounterEndHeal=UNTYPED_OBSERVED_THEN_FLOOR",
        "mres unchanged": "mres=300-900,ADD_THEN_CLAMP2700",
    }
    for name, anchor in p6p_anchors.items():
        check(f"p6p:{name}", anchor in p6p, anchor)

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6s.v0.19",
        "compile": "VNextHeardPrayerRuntimeP6sCompiler.compile(registry)", "bind": "heardPrayerRuntime.bind(",
        "typed producer": "val typedHealProducer = dispatchActivePlan.isTypedActiveHealProducerP6s()",
        "conditional requirement": "val typedHealReceiptRequired = dispatchActivePlan.requiresTypedActiveHealReceiptP6s(",
        "committed implies typed": "dispatched.healActionCommitted && !typedHealProducer",
        "commit outcome equivalence": "dispatched.healActionCommitted != (dispatched.healOutcome != null)",
        "required exact": "dispatched.healActionCommitted != typedHealReceiptRequired",
        "pre heal pass": "expectedHpBeforeHeal = heroFrameBeforeActive.hp",
        "frame match": "matchesCommittedActiveFrameP6s(", "max hp": "dispatched.actorAfter.maxHp != heroFrameBeforeActive.maxHp",
        "p6p wrapped": "val healingAdjustment = runCatching", "p6p failure": "PASSIVE_CURSED_MRES_HEAL_LEDGER_REJECTED",
        "p6n": "recordResourceTransition(resourceActiveCommit.ledgerAfter)", "p6s commit": "heardPrayerSession.commitActive(",
        "p6r commit": "shieldBreathSession.commitActive(", "preview map": "heardPrayerPreviewByActionId",
        "preview hash": "heardPrayerPreview?.planHash != heardPrayerPlan.planHash",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
        "hash content": "append(transaction.heardPrayerContentHash)",
        "hash ledger": "append(transaction.finalHeardPrayerLedgerHash)",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)
    check("resolver:p6n before p6s", resolver.index("recordResourceTransition(resourceActiveCommit.ledgerAfter)") <
          resolver.index("val nextHeardPrayerLedger = runCatching"), "P6n then P6s")
    check("resolver:p6s before p6r", resolver.index("val nextHeardPrayerLedger = runCatching") <
          resolver.index("val nextShieldBreathLedger = runCatching"), "P6s then P6r")

    unit_anchors = {
        "coverage": "coverage_closes_shield_hosts_typed_heal_producers_and_deferred_scopes",
        "rounding": "typed_heal_receipt_applies_all_modifiers_before_overheal_with_half_up_rounding",
        "anchors": "mapOf(1 to -200, 25 to -300, 50 to -400, 75 to -500, 100 to -600)",
        "full hp": "exact_fit_does_not_arm_full_hp_heal_arms_and_non_shield_active_preserves",
        "four hosts": "all_four_current_cleric_loadable_shield_hosts_consume_only_on_confirmed_shield",
        "shared cap": "shared_discount_caps_once_and_positive_cost_keeps_the_canonical_floor",
        "malformed": "stale_replay_untyped_heal_and_unconfirmed_shield_fail_closed_without_mutation",
        "frame receipt": "typed_receipt_must_match_selected_action_target_committed_hp_and_all_modifiers",
        "receipt policy": "typed_receipt_policy_requires_six_always_heals_and_only_the_low_hp_life_distribution_branch",
        "low before forgery": "forgedLowBeforeHighActual",
        "high before forgery": "forgedHighBeforeLowActual",
        "boundary 9500": "active_heal_boundary_keeps_applied_five_hundred",
        "legacy floor": "encounter_end_legacy_branch_keeps_one_floor_round",
    }
    combined_tests = runtime_test + p6p_test
    for name, anchor in unit_anchors.items():
        check(f"unit:{name}", anchor in combined_tests, anchor)

    integration_anchors = {
        "production": "p6s production overheal arms heard prayer and the next cleric shield consumes its previewed discount",
        "forged": "p6s rejects a typed overheal receipt fabricated for the wrong selected active",
        "equal sum forged": "p6s rejects equal sum forged heal frames with and without heard prayer",
        "mandatory without passive": "p6s requires a typed receipt from a production heal even when heard prayer is not equipped",
        "life low mandatory": "p6s low hp life distribution requires a typed receipt without heard prayer",
        "life high non heal": "p6s high hp life distribution stays committed non heal and preserves its token in both loadouts",
        "life low production": "p6s low hp life distribution commits its canonical typed heal in both loadouts",
        "equipment invariant": "p6s heard prayer equipment does not change canonical active heal hp or action selection",
        "resource reject": "p6s resource transition rejection leaves an armed heard prayer token unconsumed",
        "invalid unchanged": "assertEquals(0, result.heardPrayerCommittedActiveCount)",
        "armed unchanged": "assertTrue(result.finalHeardPrayerArmed)",
        "preview 1728": '"candidateCost=1728"', "commit 1728": '"committedCost=1728|combinedCost=-400"',
        "rehash": "VNextBattleResolverTransaction.transactionHash(result)",
    }
    for name, anchor in integration_anchors.items():
        check(f"integration:{name}", anchor in resolver_test, anchor)

    doc_anchors = {
        "53": "53/90", "37": "37/90 OFF", "active only": "sourceKind=ACTIVE",
        "always six": "항상 typed receipt를 생산해야 하는 Active Heal은 다음 6종",
        "conditional life": "생명 분배 저HP 분기", "pre heal exact": "권위 pre-Heal HP",
        "no current same action": "같은 action의 `consume → rearm`은 현재 지원 경로가 아니다",
        "martyr deferred": "deferred 1종",
        "shield four": "CLERIC이 장착 가능한 host는 정확히 4종",
        "p6p v02": "P6p v0.2", "boundary": "pre=1,000 / final=900 / actual=500 / overheal=400",
        "feature off": "feature/live OFF", "no design": "디자인팀 요청을 하지 않았다",
    }
    for name, anchor in doc_anchors.items():
        check(f"doc:{name}", anchor in doc, anchor)
    for anchor in (
        "v0.1 supersede", "aq.cursed-mres-heal.p6p.v0.2", "preIncomingAttempted=1,000",
        "항상 6종 + 생명 분배 저HP 분기", "고HP HP→자원 분기는 committed non-Heal",
        "Heal 패킷 직전의 권위 HP", "encounterEndHeal", "legacy floor",
    ):
        check(f"p6p-doc:{anchor}", anchor in p6p_doc, anchor)

    check("balance:hash", sha(BALANCE) == FREEZE[BALANCE], sha(BALANCE))
    check("balance:pd seeds", "seeds = 200 if args.pd else args.seeds" in balance, "200")
    check("settlement:uncoupled", "P6s" not in SETTLEMENT.read_text() and "p6s" not in SETTLEMENT.read_text(), "uncoupled")
    for path, expected in FREEZE.items():
        check(f"freeze:{path.name}", sha(path) == expected, sha(path))

    check("artifact:apk size", APK.stat().st_size == APK_SIZE, APK.stat().st_size)
    check("artifact:apk hash", sha(APK) == APK_SHA, sha(APK))
    check("artifact:screen hash", sha(SCREEN) == SCREEN_SHA, sha(SCREEN))
    check("artifact:ui hash", sha(UI) == UI_SHA, sha(UI))
    check("artifact:png", SCREEN.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), SCREEN.stat().st_size)
    check("artifact:ui parse", ET.parse(UI).getroot().tag == "hierarchy", "hierarchy")
    ui = UI.read_text()
    check("artifact:roster", "아직 캐릭터가 없습니다" in ui, "empty roster")
    check("artifact:create", "새 캐릭터" in ui, "create")

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    total = tuple(sum(values) for values in zip(engine, debug, release))
    check("reports:engine", engine == (783, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (959, 0, 0, 0), total)
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(x.attrib.get("severity") == "Error" for x in lint.findall("issue"))
    warnings = sum(x.attrib.get("severity") == "Warning" for x in lint.findall("issue"))
    check("reports:lint errors", errors == 0, errors)
    check("reports:lint warnings", warnings == 22, warnings)

    expected_audit = {
        "serial": "emulator-5554", "avdName": "alarmquest-qa", "androidRelease": "15", "apiLevel": "35",
        "packageName": "com.nullplaying", "versionName": "0.1.0", "versionCode": "1", "targetSdk": "36",
        "apkSizeBytes": str(APK_SIZE), "apkSha256": APK_SHA, "installResult": "Success", "pmClearResult": "Success",
        "launchState": "COLD", "topResumedActivity": "com.nullplaying/.MainActivity", "freshRosterEmpty": "true",
        "androidRuntimeFatalCount": "0", "emulatorScreenshotSha256": SCREEN_SHA, "emulatorUiDumpSha256": UI_SHA,
        "engineTests": "783", "appDebugTests": "88", "appReleaseTests": "88", "totalTests": "959",
        "testFailures": "0", "testErrors": "0", "testSkipped": "0", "lintErrors": "0", "lintWarnings": "22",
        "featureDefaultEnabled": "false", "liveSettlementEnabled": "false", "totalPassiveDefinitionsEnabled": "53",
        "remainingPassiveDefinitions": "37", "heardPrayerContentHash": P6S_CONTENT, "p6pContentHash": P6P_CONTENT,
        "typedActiveHealProducerCount": "7", "deferredHybridHealCount": "1", "registryShieldActiveCount": "10",
        "clericLoadableShieldHostCount": "4", "activeHealBoundaryActual": "500",
        "activeHealBoundaryOverheal": "400", "heardPrayerActiveOnly": "true", "malformedReceiptFailsClosed": "true",
        "typedReceiptRequiredWithoutPassive": "true", "passiveEquipmentDoesNotChangeHeal": "true",
        "alwaysTypedHealProducerCount": "6", "lifeDistributionConditionalReceipt": "true",
        "lifeDistributionHighBranchNonHeal": "true", "preHealHpExactBound": "true",
        "lifeDistributionLowCanonical": "START5200:PRE4401_4999:CEILING5000:TOTAL600",
        "equalSumForgeryFailsClosed": "true", "simultaneousShieldHealClosedSet": "NONE_DEFERRED",
        "futureHybridOrder": "CONSUME_THEN_REARM",
        "resourceRejectPreservesToken": "true", "balancePdResult": "PASS_9_OF_9",
        "visualAssetRequired": "false", "designTeamImageRequested": "false",
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, audit.get(key))

    if args.with_emulator:
        version = adb("shell", "dumpsys", "package", "com.nullplaying")
        activity = adb("shell", "dumpsys", "activity", "activities")
        crash = adb("logcat", "-d", "-b", "crash")
        check("emulator:device", "emulator-5554" in subprocess.run(("adb", "devices"), text=True,
              capture_output=True, check=False).stdout, "emulator-5554")
        check("emulator:version", "versionName=0.1.0" in version, "0.1.0")
        check("emulator:target", "targetSdk=36" in version, "36")
        check("emulator:focus", "com.nullplaying/.MainActivity" in activity, "MainActivity")
        check("emulator:android", adb("shell", "getprop", "ro.build.version.release") == "15", "15")
        check("emulator:api", adb("shell", "getprop", "ro.build.version.sdk") == "35", "35")
        check("emulator:crash", "FATAL EXCEPTION" not in crash, "0")

    failed = [name for name, ok, _ in checks if not ok]
    print(f"RESULT={'PASS' if not failed else 'FAIL'} {len(checks) - len(failed)}/{len(checks)}")
    if failed:
        print("FAILED=" + ",".join(failed))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
