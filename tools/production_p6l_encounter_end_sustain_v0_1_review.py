#!/usr/bin/env python3
"""PD audit for P6l post-victory Bandage and Field Treatment sustain."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6l-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6l-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6l-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6L_ENCOUNTER_END_SUSTAIN_v0.1.md"
RUNTIME = ROOT / (
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextEncounterEndSustainRuntimeP6l.kt"
)
RUNTIME_TEST = ROOT / (
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextEncounterEndSustainRuntimeP6lTest.kt"
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

APK_SHA = "f325f1b79ee37b41d84656013fd66e12119d5d86ea4ab3e3e23bc3c824b9f97b"
SCREEN_SHA = "1e79c3bf12b40caffa636159a271b1fcbd0bdf2ca25bc5b63f963bc364e2549c"
UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
APK_SIZE = 37_801_555
FREEZE = {
    RUNTIME: "0f6ed8a3bfc00a4f88a5a7fdaaf9d35ffac2ca3af34e9a4886acb6f4a15dc144",
    RUNTIME_TEST: "105da9759c9b77ac9127ef01f411f6fb1a718c2b9a31f75524ae069add90be09",
    RESOLVER: "7db0c801688bc1f2cd90df78dffb0c419dff82e1690900e57f7ce9d68e73fd70",
    RESOLVER_TEST: "e1f1372daa08c976c8ae74bbe86ba06e959c58fe339b14745baba4529629e245",
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
        "rules": "aq.encounter-end-sustain.p6l.v0.1",
        "content": "4505a27fd548f08b17815e4e30ad2ccdb8bc01f84753eb97efa910022ab76091",
        "definitions": "V_NEXT_P6L_ENCOUNTER_END_SUSTAIN_DEFINITION_COUNT = 2",
        "field uses cap": "V_NEXT_P6L_FIELD_TREATMENT_EXPEDITION_USE_CAP = 3",
        "field bps cap": "V_NEXT_P6L_FIELD_TREATMENT_EXPEDITION_BPS_CAP = 1_800",
        "bandage id": "aq.skill.common.w8.emergencybandage",
        "field id": "aq.skill.ranger.w5.fieldtreatment",
        "lodging clock": 'EXPEDITION_CLOCK = "EXPEDITION_LODGING"',
        "use token": "LEDGER_FIELD_TREATMENT_EXPEDITION_USES",
        "bps token": "LEDGER_FIELD_TREATMENT_EXPEDITION_BPS",
        "foundation": "val foundationReady: Boolean",
        "live false": "val liveReady: Boolean get() = false",
        "catalog fail closed": "P5W_PASSIVE_CATALOG_REJECTED",
        "receipt replay": "receiptId !in ledger.processedReceiptIds",
        "latest support": "val supportBefore = requireNotNull(status.supportLedgerViewP5k())",
        "field stale guard": "require(fieldBefore == ledger.fieldTreatment)",
        "victory alive": "outcome == VNextBattleOutcome.VICTORY && snapshotHp > 0",
        "heal ceiling": "minOf(frame.maxHp, encounterStartHp)",
        "actual positive": "val actualPositive = snapshotHp < healCeiling",
        "bandage inclusive": "snapshotHp.toLong() * 10_000 <= frame.maxHp.toLong() * 2_500",
        "field strict": "snapshotHp.toLong() * 10_000 < frame.maxHp.toLong() * 5_000",
        "field use guard": "fieldBefore.uses < V_NEXT_P6L_FIELD_TREATMENT_EXPEDITION_USE_CAP",
        "field bps guard": "fieldBefore.spentBps + fieldAnchor <= V_NEXT_P6L_FIELD_TREATMENT_EXPEDITION_BPS_CAP",
        "definition order": "}.sorted()",
        "bandage clamp": "if (definitionId == EMERGENCY_BANDAGE) 500 else 200",
        "field clamp": "if (definitionId == EMERGENCY_BANDAGE) 1_500 else 600",
        "canonical sustain": "VNextProtectionDeathPipeline.grantSustain(",
        "only actual trigger": 'resolution.outcome == "RESOLVED" && resolution.actualAmount > 0',
        "support sync": "syncSupportLedgerP5k(",
        "field sync": "syncFieldTreatmentExpeditionP6l(",
        "clock reconcile": "fun reconcileAfterEncounterClock(",
        "support invalid": "SUPPORT_LEDGER_STATE_INVALID",
        "field invalid": "FIELD_LEDGER_STATE_INVALID",
        "orphan rejected": "ORPHAN_FIELD_LEDGER_REJECTED",
        "bandage all": 'source.ownerScope != "ALL"',
        "bandage pattern": 'source.pattern != "POST_COMBAT_LOW_HP_HEAL"',
        "bandage growth": 'source.growthField != "post_combat_heal_bps"',
        "bandage condition": 'source.conditionId != "victory_and_hp_lte_2500"',
        "bandage anchors": "listOf(500, 750, 1_000, 1_250, 1_500)",
        "field ranger": 'source.ownerScope != "RANGER"',
        "field pattern": 'source.pattern != "POST_ENCOUNTER_HEAL"',
        "field growth": 'source.growthField != "heal_maxhp_bps"',
        "field condition": 'source.conditionId != "victory_and_hp_below_5000"',
        "field anchors": "listOf(200, 300, 400, 500, 600)",
        "encounter end event": "P5wPassiveEvent.ENCOUNTER_END",
        "zero deferred": "catalogIds.minus(ids).sorted()",
        "protection anchors": "VNextProtectionDeathPipeline.byId[id]?.anchors",
        "strict codec": "VNextStatusStateCodecP5c.encode(this)",
        "token pair": "if ((use == null) != (bps == null)) return null",
        "token cost locked": "use.costLocked && bps.costLocked",
        "token range": "it.spentBps >= it.uses * 200 && it.spentBps <= it.uses * 600",
        "ledger hash support": '"support=${support.sustainEncounterBps}:${support.sustainExpeditionBps}:"',
        "ledger hash receipts": '"receipts=${receipts.joinToString(",")}"',
        "modifiers none": "modifiers=none|live=false",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime:{name}", anchor in runtime, anchor)

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6l.v0.11",
        "bind failure": "PASSIVE_ENCOUNTER_END_SUSTAIN_BIND_REJECTED",
        "ledger failure": "PASSIVE_ENCOUNTER_END_SUSTAIN_LEDGER_REJECTED",
        "compile": "VNextEncounterEndSustainRuntimeP6lCompiler.compile(registry)",
        "bind": "encounterEndSustainRuntime.bind(",
        "content hash": "encounterEndSustainContentHash = V_NEXT_P6L_ENCOUNTER_END_SUSTAIN_CONTENT_HASH",
        "start hp": "val heroEncounterStartHp = buildSnapshot.frameAfter.hp",
        "commit": "encounterEndSustainSession.commitEncounterEnd(",
        "after lethal settlement": "p6l-encounter-end-sustain",
        "receipt only trigger": "val encounterEndReceiptRequired =",
        "system receipt": 'actionId = "P6L_ENCOUNTER_END_SUSTAIN"',
        "post victory reason": 'decisionReason = "POST_VICTORY_SUSTAIN"',
        "hero clock after": "P5eStatusSystemRoot.ENCOUNTER_END, \"encounter-end\"",
        "reconcile": "encounterEndSustainSession.reconcileAfterEncounterClock(",
        "transaction content": "append(transaction.encounterEndSustainContentHash)",
        "transaction actual": "append(transaction.encounterEndSustainActualHeal)",
        "transaction field": "append(transaction.finalFieldTreatmentSpentBps)",
        "handled filter": "addAll(VNextEncounterEndSustainRuntimeP6l.supportedDefinitionIds())",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)

    unit_anchors = {
        "coverage": "coverage_enables_both_encounter_end_passives_with_zero_deferred",
        "bandage anchors": "mapOf(1 to 500, 25 to 750, 50 to 1_000, 75 to 1_250, 100 to 1_500)",
        "field anchors": "mapOf(1 to 200, 25 to 300, 50 to 400, 75 to 500, 100 to 600)",
        "thresholds": "threshold_snapshot_is_inclusive_for_bandage_strict_for_field_and_victory_only",
        "start cap": "encounter_start_hp_caps_actual_heal_without_spending_a_zero_actual_second_trigger",
        "budget fallback": "shared_sustain_budget_can_reject_bandage_while_later_smaller_field_treatment_commits",
        "three uses": "field_treatment_persists_three_uses_across_encounters_and_lodging_clears_it",
        "fail closed": "codec_orphan_wrong_owner_stale_plan_and_replay_fail_closed",
        "dual final hp": "assertEquals(4_600, atQuarter.frameAfter.hp)",
        "start cap hp": "assertEquals(3_000, result.frameAfter.hp)",
        "budget field only": "assertEquals(2_600, result.frameAfter.hp)",
        "lodging zero": "assertEquals(P6lFieldTreatmentExpedition(), lodging.fieldTreatmentExpeditionP6l())",
    }
    for name, anchor in unit_anchors.items():
        check(f"unit-test:{name}", anchor in runtime_test, anchor)

    integration_anchors = {
        "case": "p6l victory snapshot applies bandage then field treatment and persists only expedition ledgers",
        "bandage equipped": '"aq.skill.common.w8.emergencybandage"',
        "field equipped": '"aq.skill.ranger.w5.fieldtreatment"',
        "post damage": "periodicPackets = listOf(8_000)",
        "true damage": 'periodicDamageChannel = "TRUE_DAMAGE"',
        "victory": "assertEquals(VNextBattleOutcome.VICTORY, result.outcome)",
        "final 3400": "assertEquals(3_400, result.finalHero.hp)",
        "content": "V_NEXT_P6L_ENCOUNTER_END_SUSTAIN_CONTENT_HASH",
        "committed once": "assertEquals(1, result.encounterEndSustainCommittedCount)",
        "bandage once": "assertEquals(1, result.emergencyBandageTriggeredCount)",
        "field once": "assertEquals(1, result.fieldTreatmentTriggeredCount)",
        "actual 1400": "assertEquals(1_400, result.encounterEndSustainActualHeal)",
        "encounter cleared": "assertEquals(0, support.sustainEncounterBps)",
        "expedition kept": "assertEquals(1_400, support.sustainExpeditionBps)",
        "field kept": "P6lFieldTreatmentExpedition(1, 400)",
        "before clock": "result.receipts.indexOf(sustain) < result.receipts.indexOfFirst",
        "rehash": "VNextBattleResolverTransaction.transactionHash(result)",
    }
    for name, anchor in integration_anchors.items():
        check(f"integration-test:{name}", anchor in resolver_test, anchor)

    check("settlement:resolveKill", "fun resolveKill(" in settlement, "present")
    check("settlement:uncoupled", "P6l" not in settlement and "p6l" not in settlement, "uncoupled")
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
    check("reports:engine", engine == (702, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (878, 0, 0, 0), total)
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
        "coldTotalTimeMs": "4349", "coldWaitTimeMs": "4362",
        "topResumedActivity": "com.alarmquest/.MainActivity", "freshRosterEmpty": "true",
        "androidRuntimeFatalCount": "0", "emulatorScreenshotSha256": SCREEN_SHA,
        "emulatorUiDumpSha256": UI_SHA, "engineTests": "702", "appDebugTests": "88",
        "appReleaseTests": "88", "appTests": "176", "totalTests": "878",
        "testFailures": "0", "testErrors": "0", "testSkipped": "0",
        "lintErrors": "0", "lintWarnings": "22", "featureDefaultEnabled": "false",
        "liveSettlementEnabled": "false", "passiveDefinitionsClassified": "90",
        "passiveSlotCap": "3", "previousPassiveDefinitionsEnabled": "42",
        "encounterEndSustainDefinitionsEnabled": "2", "totalPassiveDefinitionsEnabled": "44",
        "remainingPassiveEffectsEnabled": "0", "remainingPassiveDefinitions": "46",
        "encounterEndDefinitionsClassified": "2", "encounterEndDefinitionsEnabled": "2",
        "encounterEndDefinitionsDeferred": "0", "afterIncomingPacketDefinitionsEnabled": "7",
        "encounterEndSustainRulesVersion": "aq.encounter-end-sustain.p6l.v0.1",
        "encounterEndSustainContentHash": "4505a27fd548f08b17815e4e30ad2ccdb8bc01f84753eb97efa910022ab76091",
        "battleResolverRulesVersion": "aq.battle-resolver-transaction.p6l.v0.11",
        "emergencyBandageEnabled": "true", "emergencyBandageOwnerClass": "ALL",
        "emergencyBandageLv1HealMaxHpBps": "500", "emergencyBandageLv100HealMaxHpBps": "1500",
        "emergencyBandageThresholdInclusiveBps": "2500", "emergencyBandageVictoryOnly": "true",
        "fieldTreatmentEnabled": "true", "fieldTreatmentOwnerClass": "RANGER",
        "fieldTreatmentLv1HealMaxHpBps": "200", "fieldTreatmentLv100HealMaxHpBps": "600",
        "fieldTreatmentThresholdStrictBps": "5000", "fieldTreatmentVictoryOnly": "true",
        "fieldTreatmentExpeditionUseCap": "3", "fieldTreatmentExpeditionSpentBpsCap": "1800",
        "fieldTreatmentLedgerClock": "EXPEDITION_LODGING", "fieldTreatmentLedgerCostLocked": "true",
        "conditionUsesPostBattleHpSnapshot": "true", "executionOrder": "DEFINITION_ID",
        "sharedSustainEncounterCapBps": "3000", "sharedSustainExpeditionCapBps": "7000",
        "healCeiling": "encounterStartHp", "zeroActualDoesNotSpend": "true",
        "budgetRejectDoesNotSpend": "true", "postCombatHealConsumesNextHealActionToken": "false",
        "encounterClockRunsAfterPostCombatSustain": "true",
        "encounterClockClearsEncounterSustainLedger": "true",
        "expeditionSustainLedgerPersists": "true", "fieldTreatmentLedgerPersists": "true",
        "lodgingClearsFieldTreatmentLedger": "true", "strictCodecRoundTrip": "true",
        "malformedLedgerRejected": "true", "orphanFieldLedgerRejected": "true",
        "wrongOwnerRejected": "true", "staleFieldLedgerRejected": "true",
        "encounterEndReceiptReplayRejected": "true", "encounterEndLedgerInTransactionHash": "true",
        "encounterEndCountersInTransactionHash": "true", "runtimeHandledPassivesFilteredFromAdapter": "true",
        "campaignStatusCarryoverWiredToLiveCaller": "false",
        "expeditionLodgingClockWiredToLiveCaller": "false", "visualAssetRequired": "false",
        "designTeamImageRequested": "false", "legacySettlementHash": FREEZE[SETTLEMENT],
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, audit.get(key))

    doc_anchors = {
        "approval": "조건부 승인", "44 enabled": "Passive 44종 구현",
        "46 off": "미구현 46종은 효과 OFF", "event closed": "2/2 실행, 보류 0",
        "snapshot": "동일한 전투 종료 HP snapshot", "order": "definition ID 순",
        "level cap": "캐릭터 Lv9,999", "skill cap": "Skill Lv100",
        "passive slots": "Passive 3슬롯", "automatic": "전투는 계속 완전 자동",
        "action token boundary": "ENCOUNTER_END 시스템 효과",
        "carry blocker": "finalHeroStatusPayload", "lodging blocker": "EXPEDITION_LODGING",
        "live off": "라이브 정산은 OFF", "no image": "디자인팀 이미지 요청과 앱 UI 변경은 하지 않았다",
        "future icons": "응급 붕대·야전 치료 Passive 아이콘",
        "tests": "total 878", "lint": "lint errors 0 / warnings 22",
        "cold": "TotalTime 4349ms, WaitTime 4362ms", "apk": APK_SHA,
        "runtime freeze": FREEZE[RUNTIME], "resolver freeze": FREEZE[RESOLVER],
    }
    for name, anchor in doc_anchors.items():
        check(f"doc:{name}", anchor in doc, anchor)

    if args.with_emulator:
        serial = audit["serial"]
        state = command("adb", "-s", serial, "get-state")
        release_value = command("adb", "-s", serial, "shell", "getprop", "ro.build.version.release")
        api = command("adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk")
        avd = command("adb", "-s", serial, "shell", "getprop", "ro.boot.qemu.avd_name")
        package = command("adb", "-s", serial, "shell", "dumpsys", "package", "com.alarmquest")
        focus = command("adb", "-s", serial, "shell", "dumpsys", "activity", "activities")
        crash = command("adb", "-s", serial, "logcat", "-d", "-b", "crash", "AndroidRuntime:E", "*:S")
        command("adb", "-s", serial, "shell", "uiautomator", "dump", "/sdcard/p6l-review.xml")
        live_ui = command("adb", "-s", serial, "exec-out", "cat", "/sdcard/p6l-review.xml")
        command("adb", "-s", serial, "shell", "rm", "/sdcard/p6l-review.xml")
        check("emulator:device", state == "device", state)
        check("emulator:release", release_value == "15", release_value)
        check("emulator:api", api == "35", api)
        check("emulator:avd", avd == "alarmquest-qa", avd)
        check("emulator:version", "versionCode=1" in package and "versionName=0.1.0" in package, "0.1.0(1)")
        check("emulator:target", "targetSdk=36" in package, "36")
        check("emulator:focus", "topResumedActivity" in focus and "com.alarmquest/.MainActivity" in focus, "MainActivity")
        check("emulator:fatal", "FATAL EXCEPTION" not in crash, "0")
        check("emulator:empty", "아직 캐릭터가 없습니다" in live_ui, "empty")
        check("emulator:create", "새 캐릭터" in live_ui, "create")

    passed = sum(1 for _, ok, _ in checks if ok)
    failed = len(checks) - passed
    print(f"RESULT: PASS={passed} FAIL={failed} TOTAL={len(checks)}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
