#!/usr/bin/env python3
"""PD audit for P6j LOW_HP expedition Shield and next Shield/Heal Passives."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6j-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6j-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6j-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6J_LOW_HP_EXPEDITION_v0.1.md"
EXPECTED_APK_SHA = "f0bf06f774e5e6f721ba68623187c08920fcd0876ab5c65171679c22c3583e3b"
EXPECTED_SCREEN_SHA = "a2a04d3b4c43ffab862c69e118c9e16c61451a5c17e208684144bfc693b927f6"
EXPECTED_UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
EXPECTED_APK_SIZE = 37_752_522
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLowHpExpeditionRuntimeP6j.kt":
        "56652db061276a78d62399b822edc13a8558933db137d041e3b72fbee1d60e1a",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextLowHpExpeditionRuntimeP6jTest.kt":
        "93d42e55bfd89e1bc4a49a4bf973edb1a197497df2909e7d308a15c4dbb5a5b4",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "89d4562265fd968f6deda39e8ecd39d2194908360bc133e3a96ef894027bb0b1",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "bbf94c959011c1253185389808c4758a92301667e96666f1d80376a09732a330",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSupportSustainSemanticAdapterP5k.kt":
        "095825d61639166277d43c6c4738a4d883744e3cd9b78e950d80b242a24c8400",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextClassSurvivalSemanticAdapterP5l.kt":
        "3716a78d5ee0a52dd834f07b1855c1c026737fc83060615fef23d3851fa9aa1e",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5m.kt":
        "d1d71ba4b81d45337ced2d788d13feb75deec72d6e817421e9ad825dfd06f8d1",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextResourceProtectionConversionP5n.kt":
        "6d86c799762fe3ba8c09752f9ebc932d19390a045b60e1a48dab846c424260f9",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSpecialSustainSemanticAdapterP5o.kt":
        "a0dd867bd5338a05183d11560f606203200160047b2675b3a2cf9a6fee1f469c",
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
               "VNextLowHpExpeditionRuntimeP6j.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
             "VNextLowHpExpeditionRuntimeP6jTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
                "VNextBattleResolverTransaction.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
                      "VNextBattleResolverTransactionTest.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    adapters = {
        key: (ROOT / f"game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/{name}").read_text()
        for key, name in {
            "P5K": "VNextSupportSustainSemanticAdapterP5k.kt",
            "P5L": "VNextClassSurvivalSemanticAdapterP5l.kt",
            "P5M": "VNextMixedSurvivalCorrectionP5m.kt",
            "P5N": "VNextResourceProtectionConversionP5n.kt",
            "P5O": "VNextSpecialSustainSemanticAdapterP5o.kt",
        }.items()
    }
    audit = AUDIT.read_text()
    doc = DOC.read_text()

    runtime_anchors = {
        "rules": "aq.low-hp-expedition.p6j.v0.1",
        "content": "1a9efee2eec763458748bccaa7235a9c81f9d5f83a632ae16d4c10f8b2d1e9a4",
        "definitions": "V_NEXT_P6J_LOW_HP_EXPEDITION_DEFINITION_COUNT = 2",
        "threshold": "V_NEXT_P6J_LOW_HP_THRESHOLD_BPS = 3_000",
        "support cap": "V_NEXT_P6J_SUPPORT_MODIFIER_CAP_BPS = 500",
        "banner id": "aq.skill.warrior.w4.brokenbanner",
        "name id": "aq.skill.paladin.w4.protectedname",
        "prayer deferred": "aq.skill.cleric.w4.protectiveprayer",
        "banner ledger": "ledger.low-hp.broken-banner.expedition",
        "name ledger": "ledger.low-hp.protected-name.expedition",
        "lodging clock": 'EXPEDITION_CLOCK = "EXPEDITION_LODGING"',
        "banner consumed": "val brokenBannerConsumed: Boolean = false",
        "name phases": "require(protectedNamePhase in 0..2)",
        "name armed": "protectedNamePhase == 1",
        "name consumed": "protectedNamePhase == 2",
        "cross from above": "before.hp.toLong() * 10_000 >",
        "cross to low": "after.hp.toLong() * 10_000 <=",
        "alive before after": "before.hp > 0 && after.hp > 0",
        "same max hp": "before.maxHp == after.maxHp",
        "banner clamp": "resolvedAnchorValue.coerceIn(300, 700)",
        "shared sustain": "VNextProtectionDeathPipeline.grantSustain(",
        "budget spent": "first qualifying entry spends the expedition trigger",
        "banner state": "brokenBannerConsumed = true",
        "name arm": "protectedNamePhase = 1",
        "name consume": "protectedNamePhase = 2",
        "support prepared": "prepareSupportModifier",
        "shield receipt": "shieldActionCommitted",
        "heal receipt": "healActionCommitted",
        "exclusive support": "require(!(shieldActionCommitted && healActionCommitted))",
        "modifier validate": "appliedModifierBps == plan.modifierBps",
        "round once": "10_000 + healModifierBps + supportModifierBps",
        "strict codec": "VNextStatusStateCodecP5c.encode(this)",
        "cost locked": "costLocked = true",
        "event": "P5wPassiveEvent.AFTER_INCOMING_PACKET",
        "one deferred": "deferredAfterIncomingIds == listOf(PROTECTIVE_PRAYER)",
        "live off": "val liveReady: Boolean get() = false",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime {name}", anchor in runtime, "bound")

    for name in (
        "coverage_activates_two_low_hp_passives_and_leaves_only_protective_prayer_deferred",
        "crossing_from_above_thirty_percent_to_alive_low_hp_grants_banner_shield_once",
        "starting_low_exactly_at_threshold_and_lethal_packets_do_not_create_an_entry",
        "shared_sustain_cap_rejection_spends_the_first_entry_without_bypassing_the_cap",
        "protected_name_arms_on_entry_ignores_non_support_then_modifies_and_consumes_next_shield",
        "shield_and_heal_modifier_add_with_heavy_hit_scalar_and_round_exactly_once",
        "expedition_tokens_survive_codec_and_encounter_end_but_lodging_clears_them",
        "malformed_tokens_wrong_owner_stale_plans_and_duplicate_receipts_fail_closed",
    ):
        check(f"unit {name[:64]}", name in tests, "covered")
    for anchor in (
        "assertEquals(700, first.brokenBannerShieldActual)",
        "assertFalse(startedLow.lowHpEntry)",
        "assertFalse(lethal.lowHpEntry)",
        'assertEquals("BUDGET_REJECTED", result.brokenBannerOutcome)',
        "assertTrue(result.ledgerAfter.expedition.brokenBannerConsumed)",
        "assertEquals(400, firstPlan.modifierBps)",
        "assertFalse(nonSupport.consumed)",
        "assertTrue(consumed.consumed)",
        "assertEquals(1_111, applySustainModifiersP6j(1_001, 600, 500))",
        "LifecycleClockEvent.ENCOUNTER_END",
        "LifecycleClockEvent.EXPEDITION_LODGING",
        "assertEquals(null, malformed.lowHpExpeditionLedgerViewP6j())",
        "assertFailsWith<IllegalArgumentException>",
    ):
        check(f"unit assertion {anchor[:58]}", anchor in tests, "covered")

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6j.v0.9",
        "bind failure": "PASSIVE_LOW_HP_EXPEDITION_BIND_REJECTED",
        "ledger failure": "PASSIVE_LOW_HP_EXPEDITION_LEDGER_REJECTED",
        "compile": "VNextLowHpExpeditionRuntimeP6jCompiler.compile(registry)",
        "bind": "lowHpExpeditionRuntime.bind(",
        "initial status": "initialStatus = request.heroStatusState",
        "content": "V_NEXT_P6J_LOW_HP_EXPEDITION_CONTENT_HASH",
        "record": "fun recordLowHpExpedition(next: P6jLowHpExpeditionLedger)",
        "support prepare": "prepareSupportModifier(",
        "request scalar": "outgoingSupportModifierBps = lowHpSupportPlan.modifierBps",
        "support validate": "dispatched.supportModifierAppliedBps != lowHpSupportPlan.modifierBps",
        "support commit": "commitSupportAction(",
        "incoming commit": "lowHpExpeditionSession.commitIncoming(",
        "packet before": "frameBefore = hero",
        "packet after": "frameAfterPacket = committedResult.target",
        "status after": "statusAfterPacket = delayedCastIncomingCommit.stateAfter",
        "frame commit": "hero = lowHpIncomingCommit.frameAfter",
        "status commit": "heroStatus = lowHpIncomingCommit.statusAfter",
        "transaction content": "val lowHpExpeditionContentHash: String",
        "transaction session": "val lowHpExpeditionSessionHash: String",
        "transaction initial": "val initialLowHpExpeditionLedgerHash: String",
        "transaction final": "val finalLowHpExpeditionLedgerHash: String",
        "transaction incoming": "val lowHpCommittedIncomingOutcomeCount: Int",
        "transaction entry": "val lowHpEntryCount: Int",
        "transaction banner": "val brokenBannerTriggeredCount: Int",
        "transaction armed": "val protectedNameArmedCount: Int",
        "transaction consumed": "val protectedNameConsumedCount: Int",
        "transaction final banner": "val finalBrokenBannerConsumed: Boolean",
        "transaction final name": "val finalProtectedNamePhase: Int",
        "hash content": "append(transaction.lowHpExpeditionContentHash)",
        "hash ledger": "append(transaction.finalLowHpExpeditionLedgerHash)",
        "hash entry": "append(transaction.lowHpEntryCount)",
        "adapter filter": "VNextLowHpExpeditionRuntimeP6j.supportedDefinitionIds()",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver {name}", anchor in resolver, "bound")

    for anchor in (
        "p6j broken banner grants post packet shield on the first living thirty percent crossing",
        "p6j protected name survives attack roots then modifies and consumes the next committed heal",
        "p6j rejects a dispatcher that omits the prepared support modifier",
        'passiveIds = listOf("aq.skill.warrior.w4.brokenbanner")',
        'passiveIds = listOf("aq.skill.paladin.w4.protectedname")',
        '"P6J_LOW_HP:entry=true|banner=true"',
        "assertEquals(50_000, trigger.targetAfter.shield)",
        "assertTrue(300 in supportModifiers",
        '"P6J_SUPPORT:ready=true|modifier=300"',
        "assertEquals(1, result.protectedNameConsumedCount)",
        "PASSIVE_LOW_HP_EXPEDITION_LEDGER_REJECTED",
        "VNextBattleResolverTransaction.transactionHash(result)",
    ):
        check(f"integration {anchor[:62]}", anchor in resolver_tests, "covered")

    for name, source in adapters.items():
        check(f"adapter {name} scalar", "applySustainModifiersP6j(" in source, "bound")
        check(f"adapter {name} receipt", "supportModifierAppliedBps" in source, "bound")
    for name in ("P5K", "P5L", "P5M", "P5O"):
        check(f"adapter {name} shield", "shieldActionCommitted" in adapters[name], "bound")
    check("adapter P5N shield", "shieldActionCommitted = true" in adapters["P5N"], "bound")

    check("legacy resolveKill", "fun resolveKill(" in settlement, "present")
    check("legacy unconnected runtime", "VNextLowHpExpeditionRuntimeP6j" not in settlement, "safe")
    check("legacy unconnected resolver", "VNextBattleResolverTransaction" not in settlement, "safe")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    app = tuple(debug[i] + release[i] for i in range(4))
    check("engine tests", engine == (684, 0, 0, 0), engine)
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
        "launchState=COLD", "coldTotalTimeMs=6177", "coldWaitTimeMs=6180",
        "topResumedActivity=com.alarmquest/.MainActivity", "freshRosterEmpty=true",
        "androidRuntimeFatalCount=0", "engineTests=684", "appTests=176", "totalTests=860",
        "testFailures=0", "testErrors=0", "testSkipped=0", "lintErrors=0", "lintWarnings=22",
        "featureDefaultEnabled=false", "liveSettlementEnabled=false",
        "lowHpExpeditionDefinitionsEnabled=2", "totalPassiveDefinitionsEnabled=41",
        "remainingPassiveDefinitions=49", "afterIncomingPacketDefinitionsEnabled=6",
        "afterIncomingPacketDefinitionsDeferred=1",
        "lowHpExpeditionRulesVersion=aq.low-hp-expedition.p6j.v0.1",
        "lowHpExpeditionContentHash=1a9efee2eec763458748bccaa7235a9c81f9d5f83a632ae16d4c10f8b2d1e9a4",
        "battleResolverRulesVersion=aq.battle-resolver-transaction.p6j.v0.9",
        "lowHpThresholdBps=3000", "lowHpRequiresCrossingFromAbove=true",
        "lowHpRequiresAliveAfterPacket=true", "lowHpStartingBelowDoesNotTrigger=true",
        "lowHpLethalPacketDoesNotTrigger=true", "lowHpUsesActualHpAfterProtection=true",
        "brokenBannerLv1ShieldMaxHpBps=300", "brokenBannerLv100ShieldMaxHpBps=700",
        "brokenBannerSharedSustainEncounterCapBps=3000",
        "brokenBannerSharedSustainExpeditionCapBps=7000",
        "brokenBannerBudgetRejectConsumesTrigger=true", "brokenBannerOncePerExpedition=true",
        "protectedNameLv1SupportModifierBps=100", "protectedNameLv100SupportModifierBps=500",
        "protectedNameNextCommittedShieldOrHealOnly=true", "protectedNameAttackDoesNotConsume=true",
        "protectedNameResourceDoesNotConsume=true", "protectedNameBarrierDoesNotConsume=true",
        "protectedNameCarriesAcrossEncounter=true", "protectedNameCombinedHealModifierCapBps=1100",
        "supportModifierRoundOnce=true", "supportModifierAdapters=P5K,P5L,P5M,P5N,P5O",
        "expeditionLedgerClock=EXPEDITION_LODGING", "expeditionLedgerCostLocked=true",
        "encounterEndPreservesExpeditionLedger=true", "lodgingClearsExpeditionLedger=true",
        "strictCodecRoundTrip=true", "malformedLedgerRejected=true",
        "dispatcherModifierMismatchRejected=true", "lowHpLedgerInTransactionHash=true",
        "campaignStatusCarryoverWiredToLiveCaller=false",
        "expeditionLodgingClockWiredToLiveCaller=false",
        "visualAssetRequired=false", "designTeamImageRequested=false",
        f"apkSha256={EXPECTED_APK_SHA}", f"apkSizeBytes={EXPECTED_APK_SIZE}",
        f"emulatorScreenshotSha256={EXPECTED_SCREEN_SHA}", f"emulatorUiDumpSha256={EXPECTED_UI_SHA}",
    )
    for field in audit_fields:
        check(f"audit {field.split('=')[0]}", field in audit, "recorded")

    for anchor in (
        "조건부 승인 — 부서진 군기의 맹세·지켜낸 이름",
        "Passive 41종 구현",
        "packet 이전 실제 HP > MaxHP 30%",
        "공유 예산이 이미 가득 찼으면 Shield는 0",
        "현재 합산 최대는 +1,100bps",
        "P5k·P5l·P5m·P5n·P5o",
        "EXPEDITION_LODGING",
        "engine 684 + app 176 = total 860",
        "디자인팀 이미지 요청을 생략",
        "finalHeroStatusPayload",
        "P6k는 AFTER_INCOMING_PACKET의 마지막 미구현 `보호 기도`",
    ):
        check(f"doc {anchor[:54]}", anchor in doc, "recorded")

    if args.with_emulator:
        devices = command("adb", "devices", "-l")
        package = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest")
        activity = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities")
        fatal = command("adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief", "*:E")
        ui = UI.read_text()
        check("emulator connected", "emulator-5554" in devices and "device" in devices, devices.splitlines()[-1])
        check("emulator version", "versionName=0.1.0" in package and "versionCode=1" in package, "0.1.0(1)")
        check("emulator target", "targetSdk=36" in package, "36")
        check("emulator focus", "topResumedActivity" in activity and "com.alarmquest/.MainActivity" in activity, "MainActivity")
        check("emulator fresh roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "empty")
        check("emulator fatal", "FATAL EXCEPTION" not in fatal and "AndroidRuntime: FATAL" not in fatal, "0")

    failed = [name for name, ok, _ in checks if not ok]
    print(f"\nRESULT: {len(checks) - len(failed)}/{len(checks)} PASS")
    if failed:
        print("FAILED: " + ", ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
