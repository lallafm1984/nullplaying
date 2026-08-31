#!/usr/bin/env python3
"""PD audit for P6i region-entry first landed incoming packet Passive."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6i-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6i-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6i-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6I_REGION_FIRST_HIT_v0.1.md"
EXPECTED_APK_SHA = "c172b94be61e2601b8e04664520cc9372d62ae369cf089e77c8c6cd457246eb7"
EXPECTED_SCREEN_SHA = "5dd0c4df008df1a434e0dcd528597fd15a8c6b7fe2b823bb94cafa78dcb753ca"
EXPECTED_UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
EXPECTED_APK_SIZE = 37_726_994
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextRegionEntryFirstHitRuntimeP6i.kt":
        "0140f7a88e508f1339f29b287d57b88779ec3348cbb30e825407cee642fdb487",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextRegionEntryFirstHitRuntimeP6iTest.kt":
        "47f0c1ae9c1485c354b0e5d967813af26443e152cf02156e439b760404a35200",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "1d36806fa171b0cb32316037ff711d852817c100798f77ad394ce3788068d5ad",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "9d5b1c6fc9ce59eb6f4a3e976e6790522d4c9f306ce4eee8f7fc180197bdfb6a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextHeavyHitNextHealRuntimeP6h.kt":
        "7d807c9c4d37e47d954b722cbc57d283af668a1891a2c306c16d583843512174",
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
               "VNextRegionEntryFirstHitRuntimeP6i.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
             "VNextRegionEntryFirstHitRuntimeP6iTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
                "VNextBattleResolverTransaction.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
                      "VNextBattleResolverTransactionTest.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    audit = AUDIT.read_text()
    doc = DOC.read_text()

    runtime_anchors = {
        "rules": "aq.region-entry-first-hit.p6i.v0.1",
        "content": "5214b47843c9d0bd1f5e13dab8b5a4a2cfa31cb4d31bc97994aa2c74ef304126",
        "one definition": "V_NEXT_P6I_REGION_FIRST_HIT_DEFINITION_COUNT = 1",
        "skill id": "aq.skill.ranger.p1.roadinstinct",
        "ready receipt": "roadInstinct != null && firstEncounterAfterRegionEntry",
        "single use": "!ledger.roadInstinctConsumed",
        "anchor clamp": "resolvedAnchorValue.coerceIn(-400, -200)",
        "stale plan": "require(plan.ledgerBeforeHash == ledger.ledgerHash)",
        "replay": "receiptId !in ledger.processedReceiptIds",
        "consume landed": "val consumed = plan.ready && hit",
        "miss retained": "ledger.roadInstinctConsumed || consumed",
        "outcome audit": "committedIncomingOutcomeCount + 1",
        "consume audit": "ledger.consumedCount + if (consumed) 1 else 0",
        "receipt in session": 'append("firstEncounter=").append(firstEncounterAfterRegionEntry)',
        "owner": 'it.ownerScope == "RANGER"',
        "pattern": 'it.pattern == "SUSTAIN"',
        "growth": 'it.growthField == "incoming_damage_modifier_bps"',
        "condition": 'it.conditionId == "first_effective_hit_after_region_entry"',
        "host": 'it.hostScope == "SELF"',
        "stack group": 'it.stackGroup == "aq.stack.passive.incoming_damage"',
        "stack cap": "it.stackCapBps == 2_500",
        "anchors": "it.anchorValues.first() == -200 && it.anchorValues.last() == -400",
        "tradeoff": "조건부 발동=first_effective_hit_after_region_entry·미충족 시 효과0",
        "event": "P5wPassiveEvent.AFTER_INCOMING_PACKET",
        "three deferred": "deferredAfterIncomingIds.size == 3",
        "p6f excluded": "VNextAfterIncomingPacketRuntimeP6f.supportedDefinitionIds()",
        "p6g excluded": "VNextAfterIncomingCostBridgeP6g.supportedDefinitionIds()",
        "p6h excluded": "VNextHeavyHitNextHealRuntimeP6h.supportedDefinitionIds()",
        "hash first encounter": "firstEncounterAfterRegionEntry:firstLandedEnemyPacket",
        "hash miss retry": "missRetry:consumeOnHit",
        "hash ordering": "PREPARE_PACKET_RESOLVE_COMMIT_ON_HIT",
        "live off": "val liveReady: Boolean get() = false",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime {name}", anchor in runtime, "bound")

    for name in (
        "coverage_activates_road_instinct_and_leaves_three_after_incoming_contracts_deferred",
        "first_landed_enemy_packet_in_the_first_region_encounter_consumes_the_modifier_once",
        "miss_does_not_consume_and_the_next_landed_packet_reuses_the_same_reduction",
        "a_landed_packet_consumes_even_when_shield_or_barrier_leaves_zero_actual_hp_damage",
        "repeated_region_encounters_and_unequipped_loadouts_audit_without_inventing_reduction",
        "all_skill_anchors_are_exact_bounded_one_packet_reductions",
        "stale_plans_duplicate_receipts_and_wrong_owner_fail_closed",
    ):
        check(f"unit {name[:58]}", name in tests, "covered")
    for anchor in (
        "assertEquals(-400, plan.incomingDamageModifierBps)",
        "assertEquals(1, consumed.consumedCount)",
        "session.commit(initial, session.prepare(initial), false",
        "assertEquals(-350, retry.incomingDamageModifierBps)",
        'receiptId = "enemy:blocked"',
        "assertTrue(consumed.roadInstinctConsumed)",
        "session(equipped = true, level = 100, firstEncounter = false)",
        "session(equipped = false, level = 100, firstEncounter = true)",
        "mapOf(1 to -200, 25 to -250, 50 to -300, 75 to -350, 100 to -400)",
        "assertFailsWith<IllegalArgumentException>",
        'runtime.bind("WARRIOR", listOf(passive(100)), true)',
    ):
        check(f"unit assertion {anchor[:50]}", anchor in tests, "covered")

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6i.v0.8",
        "request field": "val firstEncounterAfterRegionEntry: Boolean = false",
        "request owner": "Campaign-owned receipt",
        "bind failure": "PASSIVE_REGION_FIRST_HIT_BIND_REJECTED",
        "ledger failure": "PASSIVE_REGION_FIRST_HIT_LEDGER_REJECTED",
        "transaction field": "val firstEncounterAfterRegionEntry: Boolean",
        "transaction id": "firstRegionEncounter=${request.firstEncounterAfterRegionEntry}",
        "compile": "VNextRegionEntryFirstHitRuntimeP6iCompiler.compile(registry)",
        "bind": "regionFirstHitRuntime.bind(",
        "bind receipt": "firstEncounterAfterRegionEntry = request.firstEncounterAfterRegionEntry",
        "initial ledger": "regionFirstHitSession.initialLedger()",
        "content": "regionFirstHitContentHash = V_NEXT_P6I_REGION_FIRST_HIT_CONTENT_HASH",
        "session": "regionFirstHitSessionHash = regionFirstHitSession.snapshotHash",
        "record": "fun recordRegionFirstHit(next: P6iRegionFirstHitLedger)",
        "prepare": "regionFirstHitSession.prepare(regionFirstHitLedger)",
        "packet modifier": "regionFirstHitPlan.incomingDamageModifierBps",
        "shared clamp": ").coerceIn(-7_000, 10_000)",
        "commit": "regionFirstHitSession.commit(",
        "commit hit": "hit = committedResult.hit",
        "detail": "regionFirstHitPlan.detail",
        "transaction content": "val regionFirstHitContentHash: String",
        "transaction session": "val regionFirstHitSessionHash: String",
        "transaction initial": "val initialRegionFirstHitLedgerHash: String",
        "transaction final": "val finalRegionFirstHitLedgerHash: String",
        "transaction outcomes": "val regionFirstHitCommittedIncomingOutcomeCount: Int",
        "transaction consume count": "val roadInstinctConsumedCount: Int",
        "transaction consume state": "val finalRoadInstinctConsumed: Boolean",
        "hash receipt": "append(transaction.firstEncounterAfterRegionEntry)",
        "hash content": "append(transaction.regionFirstHitContentHash)",
        "hash ledger": "append(transaction.finalRegionFirstHitLedgerHash)",
        "hash outcomes": "append(transaction.regionFirstHitCommittedIncomingOutcomeCount)",
        "hash consume": "append(transaction.roadInstinctConsumedCount)",
        "adapter filter": "VNextRegionEntryFirstHitRuntimeP6i.supportedDefinitionIds()",
        "p6h retained": "VNextHeavyHitNextHealRuntimeP6h.supportedDefinitionIds()",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver {name}", anchor in resolver, "bound")

    for anchor in (
        "p6i road instinct reduces only the first landed packet after a committed region entry",
        "p6i repeated region encounters keep road instinct inactive and hash the campaign receipt",
        'passiveIds = listOf("aq.skill.ranger.p1.roadinstinct")',
        ".copy(firstEncounterAfterRegionEntry = true)",
        '"P6I_FIRST_HIT:regionEntry=true|ready=true|modifier=-300"',
        "assertTrue(firstLanded.hpDamage < controlFirst.hpDamage)",
        '"P6I_FIRST_HIT:regionEntry=true|ready=false|modifier=0"',
        "assertEquals(controlSecond.hpDamage, secondLanded.hpDamage)",
        "assertEquals(V_NEXT_P6I_REGION_FIRST_HIT_CONTENT_HASH, defended.regionFirstHitContentHash)",
        "assertEquals(monsterOutcomes, defended.regionFirstHitCommittedIncomingOutcomeCount)",
        "assertEquals(1, defended.roadInstinctConsumedCount)",
        "assertTrue(defended.finalRoadInstinctConsumed)",
        '"P6I_FIRST_HIT:regionEntry=false|ready=false|modifier=0"',
        "assertEquals(0, repeated.roadInstinctConsumedCount)",
        "assertNotEquals(repeated.regionFirstHitSessionHash, first.regionFirstHitSessionHash)",
        "assertNotEquals(repeated.transactionId, first.transactionId)",
        "PASSIVE_REGION_FIRST_HIT_LEDGER_REJECTED",
        "assertEquals(defended.transactionHash, VNextBattleResolverTransaction.transactionHash(defended))",
    ):
        check(f"integration {anchor[:54]}", anchor in resolver_tests, "covered")

    check("legacy resolveKill", "fun resolveKill(" in settlement, "present")
    check("legacy unconnected runtime", "VNextRegionEntryFirstHitRuntimeP6i" not in settlement, "safe")
    check("legacy unconnected resolver", "VNextBattleResolverTransaction" not in settlement, "safe")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    app = tuple(debug[i] + release[i] for i in range(4))
    check("engine tests", engine == (673, 0, 0, 0), engine)
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
        "launchState=COLD", "coldTotalTimeMs=4988", "coldWaitTimeMs=4993",
        "topResumedActivity=com.nullplaying/.MainActivity", "freshRosterEmpty=true",
        "androidRuntimeFatalCount=0", "engineTests=673", "appTests=176", "totalTests=849",
        "testFailures=0", "lintErrors=0", "lintWarnings=22",
        "featureDefaultEnabled=false", "liveSettlementEnabled=false",
        "regionFirstHitDefinitionsEnabled=1", "totalPassiveDefinitionsEnabled=39",
        "remainingPassiveDefinitions=51", "afterIncomingPacketDefinitionsEnabled=4",
        "afterIncomingPacketDefinitionsDeferred=3",
        "regionFirstHitRulesVersion=aq.region-entry-first-hit.p6i.v0.1",
        "regionFirstHitContentHash=5214b47843c9d0bd1f5e13dab8b5a4a2cfa31cb4d31bc97994aa2c74ef304126",
        "battleResolverRulesVersion=aq.battle-resolver-transaction.p6i.v0.8",
        "roadInstinctFirstEncounterAfterRegionEntryOnly=true",
        "roadInstinctCampaignReceiptRequired=true", "roadInstinctCampaignReceiptDefault=false",
        "roadInstinctCampaignReceiptWiredToLiveCaller=false",
        "roadInstinctLv1IncomingDamageModifierBps=-200",
        "roadInstinctLv25IncomingDamageModifierBps=-250",
        "roadInstinctLv50IncomingDamageModifierBps=-300",
        "roadInstinctLv75IncomingDamageModifierBps=-350",
        "roadInstinctLv100IncomingDamageModifierBps=-400",
        "roadInstinctOneLandedPacketOnly=true", "roadInstinctMissDoesNotConsume=true",
        "roadInstinctHitConsumes=true", "roadInstinctZeroActualHpDamageHitConsumes=true",
        "roadInstinctShieldedHitConsumes=true", "roadInstinctBarrierHitConsumes=true",
        "roadInstinctRepeatedRegionEncounterInactive=true", "roadInstinctUnequippedInactive=true",
        "roadInstinctAppliesBeforePacketResolution=true", "roadInstinctCommitsAfterPacketResolution=true",
        "roadInstinctSharedIncomingDamageClamp=true", "regionEntryReceiptInTransactionId=true",
        "regionEntryReceiptInTransactionHash=true", "regionFirstHitSessionHashRecordsReceipt=true",
        "incomingReceiptReplayRejected=true", "incomingStalePlanRejected=true",
        "regionFirstHitLedgerInTransactionHash=true", "regionFirstHitIncomingOutcomeCountRecorded=true",
        "roadInstinctConsumedCountRecorded=true", "roadInstinctFinalConsumedStateRecorded=true",
        "runtimeHandledPassivesFilteredFromAdapter=true", "resourceCeilingBypass=false",
        "visualAssetRequired=false", "designTeamImageRequested=false",
        "p6hHeavyHitHealStillPinned=true", f"emulatorScreenshotSha256={EXPECTED_SCREEN_SHA}",
        f"emulatorUiDumpSha256={EXPECTED_UI_SHA}",
    )
    for field in audit_fields:
        check(f"audit {field.split('=')[0]}", field in audit, field)

    for anchor in (
        "PD 조건부 승인", "Passive 39종 구현", "미구현 51종",
        "AFTER_INCOMING_PACKET 7종 중 4종", "P6i 선정 이유",
        "승률이 43%p", "Lv100 상한을 -600에서 -400bps",
        "firstEncounterAfterRegionEntry=true", "기본값은 false",
        "캠페인 caller는 아직 연결하지 않았다",
        "MISS에는 소모하지 않아", "actual HP 피해가 0이어도 소모",
        "packet-local 보정", "-7000..10000bps", "SettlementEngine.resolveKill()",
        "Lv9999·자동 전투·사용자 관여", "최대 4%",
        "Passive 3슬롯", "engine 673 + app 176 = total 849",
        "새 화면 이미지가 필요하지 않아", "P6j",
        "라이브 ON 전 캠페인 caller",
    ):
        check(f"document {anchor[:38]}", anchor in doc, "present")

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
    print(f"P6i PD verification: {passed}/{len(checks)} PASS ({percent:.1f}%)")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
