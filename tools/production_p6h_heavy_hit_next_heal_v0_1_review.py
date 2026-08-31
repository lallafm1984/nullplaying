#!/usr/bin/env python3
"""PD audit for P6h heavy-hit to next-Heal runtime and shared Heal scalar."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6h-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6h-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6h-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6H_HEAVY_HIT_NEXT_HEAL_v0.1.md"
EXPECTED_APK_SHA = "c6d7a540547027c3ac5897daf8420f0186b3542e2b7d1da33433d62c4f385202"
EXPECTED_SCREEN_SHA = "77757f56b5d745c1d79dcfa274efd0c4636cecc1a8bb820ea9b17e0013c8e577"
EXPECTED_UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
EXPECTED_APK_SIZE = 37_716_219
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextHeavyHitNextHealRuntimeP6h.kt":
        "7d807c9c4d37e47d954b722cbc57d283af668a1891a2c306c16d583843512174",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextHeavyHitNextHealRuntimeP6hTest.kt":
        "9b10582d4b3afaa9148c8fdfd92ed0e650e0549237ec4b549380d36fb2486475",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "24e6a0570d09dd2c45c8c804838edc9f7b881cabc292e6c9e2e7b1cc2ece8f25",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "d54aaa7ee1fbe8d9ef20fbb90fe699b2109f0e16bd22cd99091477bc3870547a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSupportSustainSemanticAdapterP5k.kt":
        "d46431375bf9d08d8ea3d69f73b16f96bb6160698598bea5cb290dba22ae22ae",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextSupportSustainSemanticAdapterP5kTest.kt":
        "75832ce9bad6b8929fc0ef1b8dd785c26135b83350923648b35941c192e148e0",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextClassSurvivalSemanticAdapterP5l.kt":
        "1fc8e01585a44de80f3dfc453b4c6c2868d8221dad71f29e48cb51b48d9b7b50",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextClassSurvivalSemanticAdapterP5lTest.kt":
        "0865554c0758a6d48ced83025c4f16ef3a1414ace6053dbec2235f8284b9c065",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5m.kt":
        "4998c667795846e069c2cef27714b63042c05e0cc10641f820f22c84f14d0b5b",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5mTest.kt":
        "359cce2885bea9c6f6bca128a3ecbe36eabbe800fc664080c5056ee61f8573c2",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextResourceProtectionConversionP5n.kt":
        "efed85ff559eb8eed44dc7f7a90014bb4002eea3dc8ab1838dda3c4b9b6da7fe",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextResourceProtectionConversionP5nTest.kt":
        "c8df765c5713663d2166d753a4c7e4b9146b15aa5c17f72845c4bf5fd0b13968",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSpecialSustainSemanticAdapterP5o.kt":
        "2796df6ee0a72edc5aed385549eb6d0edc1c6a0f3427803c68bf7b5f0c43db37",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextSpecialSustainSemanticAdapterP5oTest.kt":
        "8a8c24ff233f9b749ffaeca65fe9e6077e4f603c13238ec11f278544bdc40a22",
    "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt":
        "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
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


def command(*args: str) -> str:
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)
    return (result.stdout + result.stderr).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: object) -> None:
        checks.append((name, condition, str(detail)))
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")

    runtime = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
               "VNextHeavyHitNextHealRuntimeP6h.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
             "VNextHeavyHitNextHealRuntimeP6hTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
                "VNextBattleResolverTransaction.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
                      "VNextBattleResolverTransactionTest.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    audit = AUDIT.read_text()
    doc = DOC.read_text()

    runtime_anchors = {
        "rules": "aq.heavy-hit-next-heal.p6h.v0.1",
        "content": "ac3c60da96c54241b4f15d30245665c108c08859ac6fa6d0d3e282280d32dd6c",
        "one definition": "V_NEXT_P6H_HEAVY_HIT_HEAL_DEFINITION_COUNT = 1",
        "threshold": "V_NEXT_P6H_HEAVY_HIT_THRESHOLD_BPS = 2_000",
        "two roots": "V_NEXT_P6H_HEAL_TOKEN_OWNER_ROOTS = 2",
        "modifier cap": "V_NEXT_P6H_HEAL_MODIFIER_CAP_BPS = 600",
        "skill id": "aq.skill.cleric.w3.painempathy",
        "stale incoming": "require(plan.ledgerBeforeHash == ledger.ledgerHash)",
        "nonnegative damage": "require(actualHpDamage >= 0)",
        "incoming replay": "receiptId !in ledger.processedIncomingReceiptIds",
        "heavy hit requires passive": "painEmpathy != null && hit",
        "integer threshold": "actualHpDamage.toLong() * 10_000L",
        "threshold comparison": "plan.targetMaxHp.toLong() * V_NEXT_P6H_HEAVY_HIT_THRESHOLD_BPS",
        "arm": "tokenArmed = if (heavyHit) true else ledger.tokenArmed",
        "refresh root": "tokenArmedAtHeroRoot = if (heavyHit) plan.heroRootCount",
        "expiry root": "plan.heroRootCount + V_NEXT_P6H_HEAL_TOKEN_OWNER_ROOTS",
        "small does not reset": "else ledger.tokenExpiresAtHeroRoot",
        "owner advance": "fun advanceOwnerRoot(",
        "owner expiry": "heroRootIndex <= ledger.tokenExpiresAtHeroRoot",
        "ready after arm": "heroRootIndex > ledger.tokenArmedAtHeroRoot",
        "ready through expiry": "heroRootIndex <= ledger.tokenExpiresAtHeroRoot",
        "modifier clamp": "resolvedAnchorValue.coerceIn(0, V_NEXT_P6H_HEAL_MODIFIER_CAP_BPS)",
        "consume once": "val consumed = plan.tokenReady && plan.modifierBps > 0",
        "heal replay": "receiptId !in ledger.processedHealReceiptIds",
        "owner": 'it.ownerScope == "CLERIC"',
        "pattern": 'it.pattern == "HEAVY_HIT_HEAL"',
        "growth": 'it.growthField == "next_heal_modifier_bps"',
        "condition": 'it.conditionId == "single_hit_gte_2000_maxhp_bps"',
        "host": 'it.hostScope == "SELF_OR_HOST_ACTION"',
        "stack group": 'it.stackGroup == "aq.stack.wave3.heal"',
        "stack cap": "it.stackCapBps == 1_800",
        "anchors": "it.anchorValues.first() == 200 && it.anchorValues.last() == 600",
        "tradeoff": "다음 Heal 1회·2턴 만료, 연속 소형 피격에 0",
        "event": "P5wPassiveEvent.AFTER_INCOMING_PACKET",
        "four deferred": "deferredAfterIncomingIds.size == 4",
        "p6f excluded": "VNextAfterIncomingPacketRuntimeP6f.supportedDefinitionIds()",
        "p6g excluded": "VNextAfterIncomingCostBridgeP6g.supportedDefinitionIds()",
        "round half up": "nominalBps.toLong() * (10_000 + modifierBps) + 5_000",
        "scalar cap": "modifierBps in 0..V_NEXT_P6H_HEAL_MODIFIER_CAP_BPS",
        "ordering hash": "COMMIT_INCOMING_ADVANCE_OWNER_ROOT_PREPARE_HEAL_DISPATCH_VALIDATE_CONSUME",
        "live off": "val liveReady: Boolean get() = false",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime {name}", anchor in runtime, "bound")

    for name in (
        "coverage_activates_pain_empathy_and_leaves_four_after_incoming_contracts_deferred",
        "exact_twenty_percent_actual_hp_damage_arms_but_one_less_does_not",
        "token_is_ready_for_the_next_two_owner_roots_and_then_expires",
        "first_committed_heal_consumes_the_token_exactly_once",
        "another_heavy_hit_refreshes_the_two_root_window_without_stacking_modifier",
        "all_skill_anchors_use_round_half_up_heal_scaling",
        "unequipped_runtime_and_wrong_owner_invent_no_token_or_modifier",
        "stale_incoming_and_heal_plans_and_duplicate_receipts_fail_closed",
    ):
        check(f"unit {name[:56]}", name in tests, "covered")
    for anchor in (
        "actualHpDamage = 1_999", "actualHpDamage = 2_000",
        "assertEquals(600, session.prepareHealModifier(armed, 4).modifierBps)",
        "assertEquals(600, session.prepareHealModifier(armed, 5).modifierBps)",
        "assertFalse(session.advanceOwnerRoot(armed, 6).tokenArmed)",
        "assertEquals(1, consumed.consumedCount)",
        "assertEquals(5, ledger.tokenExpiresAtHeroRoot)",
        "mapOf(1 to 200, 25 to 300, 50 to 400, 75 to 500, 100 to 600)",
        "assertFailsWith<IllegalArgumentException>",
        'runtime.bind("WARRIOR", listOf(passive(100)))',
    ):
        check(f"unit assertion {anchor[:48]}", anchor in tests, "covered")

    adapters = {
        "P5K": "VNextSupportSustainSemanticAdapterP5k",
        "P5L": "VNextClassSurvivalSemanticAdapterP5l",
        "P5M": "VNextMixedSurvivalCorrectionP5m",
        "P5N": "VNextResourceProtectionConversionP5n",
        "P5O": "VNextSpecialSustainSemanticAdapterP5o",
    }
    adapter_sources: dict[str, str] = {}
    adapter_tests: dict[str, str] = {}
    for label, stem in adapters.items():
        adapter_sources[label] = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle" /
                                  f"{stem}.kt").read_text()
        adapter_tests[label] = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle" /
                                f"{stem}Test.kt").read_text()
    for label, source in adapter_sources.items():
        check(f"adapter {label} request scalar", "request.outgoingHealModifierBps" in source, "bound")
        check(f"adapter {label} shared scalar", "applyHealModifierP6h(" in source, "bound")
        check(f"adapter {label} heal committed", "healActionCommitted" in source, "bound")
        check(f"adapter {label} applied modifier", "healModifierAppliedBps" in source, "bound")
        check(f"adapter {label} audit detail", "healModifier=" in source, "bound")
        check(f"adapter {label} level 600 test", "outgoingHealModifierBps = 600" in adapter_tests[label], "covered")
    adapter_test_anchors = {
        "P5K": ("first aid applies the resolver owned next heal scalar exactly once per dispatch", "5_318"),
        "P5L": ("cleric heal applies the shared next heal scalar while shield paths do not", "6_060"),
        "P5M": ("paladin mixed heal applies the shared scalar without changing its attack packet", "6_822"),
        "P5N": ("life distribution low branch applies the shared next heal scalar only to healing", "4_848"),
        "P5O": ("miracle applies the shared next heal scalar before its atomic ledger commit", "6_908"),
    }
    for label, anchors in adapter_test_anchors.items():
        for anchor in anchors:
            check(f"adapter unit {label} {anchor[:38]}", anchor in adapter_tests[label], "covered")

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6h.v0.7",
        "request field": "val outgoingHealModifierBps: Int = 0",
        "result heal flag": "val healActionCommitted: Boolean = false",
        "result applied": "val healModifierAppliedBps: Int = 0",
        "bind failure": "PASSIVE_HEAVY_HIT_HEAL_BIND_REJECTED",
        "ledger failure": "PASSIVE_HEAVY_HIT_HEAL_LEDGER_REJECTED",
        "compile": "VNextHeavyHitNextHealRuntimeP6hCompiler.compile(registry)",
        "bind": "heavyHitHealRuntime.bind(",
        "initial ledger": "heavyHitHealSession.initialLedger()",
        "content": "heavyHitHealContentHash = V_NEXT_P6H_HEAVY_HIT_HEAL_CONTENT_HASH",
        "session": "heavyHitHealSessionHash = heavyHitHealSession.snapshotHash",
        "record": "fun recordHeavyHitHeal(next: P6hHeavyHitHealLedger)",
        "advance": "heavyHitHealSession.advanceOwnerRoot(heavyHitHealLedger, heroRootCount)",
        "prepare heal": "heavyHitHealSession.prepareHealModifier(",
        "dispatch scalar": "outgoingHealModifierBps = heavyHitHealPlan.modifierBps",
        "validate nonheal": "!dispatched.healActionCommitted && dispatched.healModifierAppliedBps != 0",
        "validate heal": "dispatched.healModifierAppliedBps != heavyHitHealPlan.modifierBps",
        "commit heal": "heavyHitHealSession.commitHealAction(",
        "prepare incoming": "heavyHitHealSession.prepareIncoming(",
        "commit incoming": "heavyHitHealSession.commitIncoming(",
        "incoming hit": "hit = committedResult.hit",
        "incoming actual": "actualHpDamage = committedResult.hpDamage",
        "incoming max hp": "hero.maxHp",
        "incoming detail": "heavyHitIncomingPlan.detail",
        "heal detail": "heavyHitHealPlan.detail",
        "transaction content": "val heavyHitHealContentHash: String",
        "transaction session": "val heavyHitHealSessionHash: String",
        "transaction initial": "val initialHeavyHitHealLedgerHash: String",
        "transaction final": "val finalHeavyHitHealLedgerHash: String",
        "transaction incoming": "val heavyHitHealCommittedIncomingOutcomeCount: Int",
        "transaction heal": "val heavyHitHealCommittedHealActionCount: Int",
        "transaction armed": "val painEmpathyArmedCount: Int",
        "transaction consumed": "val painEmpathyConsumedCount: Int",
        "transaction token": "val finalPainEmpathyTokenArmed: Boolean",
        "hash content": "append(transaction.heavyHitHealContentHash)",
        "hash ledger": "append(transaction.finalHeavyHitHealLedgerHash)",
        "hash heal": "append(transaction.heavyHitHealCommittedHealActionCount)",
        "hash consumed": "append(transaction.painEmpathyConsumedCount)",
        "adapter filter": "VNextHeavyHitNextHealRuntimeP6h.supportedDefinitionIds()",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver {name}", anchor in resolver, "bound")

    for anchor in (
        "p6h pain empathy arms on a heavy hit and is consumed by the next committed heal",
        "p6h rejects a dispatcher that does not apply the prepared heal modifier",
        'passiveIds = listOf("aq.skill.cleric.w3.painempathy")',
        "healModifierAppliedBps = action.outgoingHealModifierBps",
        "assertTrue(400 in healModifiers",
        "assertEquals(V_NEXT_P6H_HEAVY_HIT_HEAL_CONTENT_HASH, result.heavyHitHealContentHash)",
        "assertEquals(monsterOutcomes, result.heavyHitHealCommittedIncomingOutcomeCount)",
        "assertTrue(result.painEmpathyArmedCount >= 1)",
        "assertTrue(result.painEmpathyConsumedCount >= 1)",
        '"P6H_INCOMING:" in it.detail',
        '"modifier=400" in it.detail',
        "PASSIVE_HEAVY_HIT_HEAL_LEDGER_REJECTED",
        "assertEquals(result.transactionHash, VNextBattleResolverTransaction.transactionHash(result))",
    ):
        check(f"integration {anchor[:52]}", anchor in resolver_tests, "covered")

    check("legacy resolveKill", "fun resolveKill(" in settlement, "present")
    check("legacy unconnected runtime", "VNextHeavyHitNextHealRuntimeP6h" not in settlement, "safe")
    check("legacy unconnected resolver", "VNextBattleResolverTransaction" not in settlement, "safe")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    app = tuple(debug[i] + release[i] for i in range(4))
    check("engine tests", engine == (664, 0, 0, 0), engine)
    check("app tests", app == (176, 0, 0, 0), app)
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot().findall("issue")
    errors = sum(i.attrib.get("severity") == "Error" for i in lint)
    warnings = sum(i.attrib.get("severity") == "Warning" for i in lint)
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK hash", sha(APK) == EXPECTED_APK_SHA, sha(APK))
    check("APK size", APK.stat().st_size == EXPECTED_APK_SIZE, APK.stat().st_size)
    check("screenshot hash", sha(SCREEN) == EXPECTED_SCREEN_SHA, sha(SCREEN))
    check("UI hash", sha(UI) == EXPECTED_UI_SHA, sha(UI))

    audit_fields = (
        "verificationTarget=android-emulator", "installResult=Success", "pmClearResult=Success",
        "launchState=COLD", "coldTotalTimeMs=4727", "coldWaitTimeMs=4731",
        "topResumedActivity=com.nullplaying/.MainActivity", "freshRosterEmpty=true",
        "androidRuntimeFatalCount=0", "engineTests=664", "appTests=176", "totalTests=840",
        "testFailures=0", "lintErrors=0", "lintWarnings=22",
        "featureDefaultEnabled=false", "liveSettlementEnabled=false",
        "heavyHitHealDefinitionsEnabled=1", "totalPassiveDefinitionsEnabled=38",
        "remainingPassiveDefinitions=52", "afterIncomingPacketDefinitionsEnabled=3",
        "afterIncomingPacketDefinitionsDeferred=4",
        "heavyHitHealRulesVersion=aq.heavy-hit-next-heal.p6h.v0.1",
        "heavyHitHealContentHash=ac3c60da96c54241b4f15d30245665c108c08859ac6fa6d0d3e282280d32dd6c",
        "battleResolverRulesVersion=aq.battle-resolver-transaction.p6h.v0.7",
        "painEmpathyHeavyHitThresholdMaxHpBps=2000", "painEmpathyUsesActualHpDamage=true",
        "painEmpathyMissDoesNotArm=true", "painEmpathyZeroHpDamageDoesNotArm=true",
        "painEmpathySmallHitDoesNotArm=true", "painEmpathySmallHitDoesNotReset=true",
        "painEmpathyRefreshesWindow=true", "painEmpathyStacksModifier=false",
        "painEmpathyOwnerRootWindow=2", "painEmpathyConsumesNextCommittedHeal=true",
        "painEmpathyNonHealDoesNotConsume=true", "painEmpathyCleanseDoesNotConsume=true",
        "painEmpathyLv1HealModifierBps=200", "painEmpathyLv25HealModifierBps=300",
        "painEmpathyLv50HealModifierBps=400", "painEmpathyLv75HealModifierBps=500",
        "painEmpathyLv100HealModifierBps=600", "painEmpathyHealModifierCapBps=600",
        "healScalarRounding=ROUND_HALF_UP", "executableHealAdapterFamilies=5",
        "executableHealDefinitionsCovered=7", "supportP5kHealCovered=true",
        "classSurvivalP5lHealCovered=true", "mixedSurvivalP5mHealsCovered=true",
        "conversionP5nHealBranchCovered=true", "specialSustainP5oHealCovered=true",
        "resolverPreparedModifierValidated=true", "malformedHealModifierFailsClosed=true",
        "incomingReceiptReplayRejected=true", "incomingStalePlanRejected=true",
        "healReceiptReplayRejected=true", "healStalePlanRejected=true",
        "ownerRootExpiryIndependentOfActionKind=true", "heavyHitHealLedgerInTransactionHash=true",
        "heavyHitHealIncomingOutcomeCountRecorded=true", "heavyHitHealCommittedHealCountRecorded=true",
        "painEmpathyArmedCountRecorded=true", "painEmpathyConsumedCountRecorded=true",
        "painEmpathyFinalTokenStateRecorded=true", "runtimeHandledPassivesFilteredFromAdapter=true",
        "resourceCeilingBypass=false", "visualAssetRequired=false",
        "designTeamImageRequested=false", "p6gIncomingCostStillPinned=true",
        f"emulatorScreenshotSha256={EXPECTED_SCREEN_SHA}", f"emulatorUiDumpSha256={EXPECTED_UI_SHA}",
    )
    for field in audit_fields:
        check(f"audit {field.split('=')[0]}", field in audit, field)

    for anchor in (
        "PD 조건부 승인", "Passive 38종 구현", "미구현 52종",
        "AFTER_INCOMING_PACKET 7종 중 3종", "최대 HP의 20%", "actualHpDamage",
        "N+1과 N+2 root", "BASIC, 자원 회복, 공격, 방어, 정화",
        "roundHalfUp", "5개 어댑터 계열·7개 실행 Heal 경로",
        "PASSIVE_HEAVY_HIT_HEAL_LEDGER_REJECTED", "SettlementEngine.resolveKill()",
        "Lv9999·밸런스 판단", "추가 배율은 +6%", "engine 664 + app 176 = total 840",
        "새 화면 이미지가 필요하지 않아", "P6i",
    ):
        check(f"document {anchor[:36]}", anchor in doc, "present")

    if args.with_emulator:
        devices = command("adb", "devices", "-l")
        package = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying")
        focus = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities")
        fatal = command("adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief", "*:E")
        check("emulator online", "emulator-5554" in devices and " device " in devices, devices)
        check("version", "versionName=0.1.0" in package and "versionCode=1" in package, "0.1.0(1)")
        check("target sdk", "targetSdk=36" in package, "36")
        check("focus", "com.nullplaying/.MainActivity" in focus, "MainActivity")
        ui = UI.read_text()
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        check("fatal zero", "FATAL EXCEPTION" not in fatal and "AndroidRuntime" not in fatal, "0")

    passed = sum(ok for _, ok, _ in checks)
    percent = (passed * 100.0 / len(checks)) if checks else 0.0
    print(f"P6h PD verification: {passed}/{len(checks)} PASS ({percent:.1f}%)")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
