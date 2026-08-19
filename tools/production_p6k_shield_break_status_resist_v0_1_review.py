#!/usr/bin/env python3
"""PD audit for P6k Shield Break resistance and monster hostile status handling."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6k-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6k-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6k-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6K_SHIELD_BREAK_STATUS_RESIST_v0.1.md"
RUNTIME_PATH = ROOT / (
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextShieldBreakStatusResistRuntimeP6k.kt"
)
RUNTIME_TEST_PATH = ROOT / (
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextShieldBreakStatusResistRuntimeP6kTest.kt"
)
RESOLVER_PATH = ROOT / (
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextBattleResolverTransaction.kt"
)
RESOLVER_TEST_PATH = ROOT / (
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/"
    "VNextBattleResolverTransactionTest.kt"
)
SETTLEMENT_PATH = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt"

EXPECTED_APK_SHA = "5e5d14a500ec32662430578a7c3fe76fe750ec864b36361d8d207881bdc6c1ce"
EXPECTED_SCREEN_SHA = "06e7f7d16ebded95bf56c8dd68d5e94d78684d71b3d098322cddc0ff15852769"
EXPECTED_UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
EXPECTED_APK_SIZE = 37_778_900
FREEZE = {
    RUNTIME_PATH: "dee512414ced503a531e52cf63297185165b808a8581ea60e98aa3d5a087e9e0",
    RUNTIME_TEST_PATH: "ded16679ea97334c3878911eb8ad453599e33b3eb864ba1fedf65a018640897b",
    RESOLVER_PATH: "03c062528618850dae0aae001911d57970c932c368a0110cc16269aba933ce31",
    RESOLVER_TEST_PATH: "2d8a63339b1cf174ad8845fc5a6c124b4b2ba51d3b815afbe39c08a1c693ab4d",
    SETTLEMENT_PATH: "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def report_totals(path: Path) -> tuple[int, int, int, int]:
    values = [0, 0, 0, 0]
    for report in path.glob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            values[index] += int(root.attrib.get(key, "0"))
    return tuple(values)


def command(*args: str) -> str:
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)
    return (result.stdout + result.stderr).strip()


def parse_audit(text: str) -> dict[str, str]:
    parsed: dict[str, str] = {}
    for line in text.splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        parsed[key] = value
    return parsed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: object) -> None:
        checks.append((name, condition, str(detail)))
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")

    required = [APK, AUDIT, SCREEN, UI, DOC, *FREEZE]
    for path in required:
        check(f"file:{path.relative_to(ROOT)}", path.is_file(), path.exists())
    if not all(path.is_file() for path in required):
        return 1

    runtime = RUNTIME_PATH.read_text()
    runtime_tests = RUNTIME_TEST_PATH.read_text()
    resolver = RESOLVER_PATH.read_text()
    resolver_tests = RESOLVER_TEST_PATH.read_text()
    settlement = SETTLEMENT_PATH.read_text()
    audit_text = AUDIT.read_text()
    audit = parse_audit(audit_text)
    doc = DOC.read_text()

    runtime_anchors = {
        "rules version": "aq.shield-break-status-resist.p6k.v0.1",
        "content hash": "f0749919f5bda3f7d852aa6412cf50337ac3741075b61e375cceee3d22ae544f",
        "one definition": "V_NEXT_P6K_SHIELD_BREAK_RESIST_DEFINITION_COUNT = 1",
        "nine status carriers": "V_NEXT_P6K_MONSTER_STATUS_ABILITY_COUNT = 9",
        "apply 7000": "V_NEXT_P6K_MONSTER_STATUS_APPLY_BPS = 7_000",
        "poison duration 3": "V_NEXT_P6K_MONSTER_POISON_DURATION_ROOTS = 3",
        "poison total 3000": "V_NEXT_P6K_MONSTER_POISON_TOTAL_POWER_BPS = 3_000",
        "skill id": "aq.skill.cleric.w4.protectiveprayer",
        "token id": "token.shield-break.status-resist",
        "token tag": "PREPARED_SHIELD_BREAK_STATUS_RESIST",
        "encounter clock": 'TOKEN_CLOCK = "ENCOUNTER"',
        "foundation ready": "val foundationReady: Boolean",
        "live not ready": "val liveReady: Boolean get() = false",
        "P5w fail closed": "P5W_PASSIVE_CATALOG_REJECTED",
        "positive shield before": "frameBefore.shield > 0",
        "zero shield after": "frameAfterPacket.shield == 0",
        "alive after": "frameAfterPacket.hp > 0",
        "no refresh while armed": "shieldBroken && !ledger.tokenArmed",
        "anchor clamp": "resolvedAnchorValue.coerceIn(200, 600)",
        "one or two statuses cap": "require(applicationsOffered in 0..2)",
        "eligible hit": "applicationsOffered > 0 && hit && !barrierBlocked",
        "consumes eligible attempt": "val consumed = plan.ready",
        "stale ledger rejected": "require(plan.ledgerBeforeHash == ledger.ledgerHash)",
        "receipt replay rejected": "receiptId !in ledger.processedStatusReceiptIds",
        "encounter end reconcile": "fun reconcileEncounterEnd(",
        "orphan rejected": "ORPHAN_TOKEN_REJECTED",
        "cleric owner": 'it.ownerScope == "CLERIC"',
        "registry pattern": 'it.pattern == "SHIELD_BREAK_STATUS_RESIST"',
        "growth field": 'it.growthField == "status_resistance_add_bps"',
        "five anchors": "it.anchorValues == listOf(200, 300, 400, 500, 600)",
        "condition": 'it.conditionId == "shield_broken"',
        "host scope": 'it.hostScope == "SELF_OR_HOST_ACTION"',
        "stack group": 'it.stackGroup == "aq.stack.wave4.status_resist"',
        "stack policy": 'it.stackPolicy == "ADD_THEN_CLAMP"',
        "stack cap": "it.stackCapBps == 1_800",
        "after incoming event": "P5wPassiveEvent.AFTER_INCOMING_PACKET",
        "zero deferred": '"definitions=1|monsterStatusAbilities=9:POISON|deferred=0|"',
        "ordering frozen": "DAMAGE_PROTECTION_BREAK_ARM_STATUS_ROLL_CONSUME",
        "hostile pipeline": "internal object VNextMonsterHostileStatusPipelineP6k",
        "poison only": 'require(tags.all { it == "POISON" })',
        "source magical power": "val sourcePower = source.magicalAttack",
        "target root duration": 'clock = "TARGET_ROOT_END"',
        "refresh policy": 'stackPolicy = "UNIQUE_REFRESH_MAX"',
        "barrier blocks": "barrierBlocksPackage = true",
        "base resistance input": "targetStatusResistance = targetStatusResistance",
        "tag resistance input": "tagResistanceBpsByTag = combinedTagResistance",
        "replay apply rejected": "LifecycleApplyOutcome.REPLAY_IGNORED",
        "stored owner local": "it.copy(owner = LifecycleOwner.SELF)",
        "strict codec": "VNextStatusStateCodecP5c.encode(this)",
        "cost locked": "costLocked = true",
        "token magnitude range": "token.magnitudeBps in 200..600",
        "token remaining one": "token.remaining == 1",
        "ledger invariant": "require(tokenArmed == (tokenModifierBps > 0))",
        "ledger hashes receipts": "statusReceipts=${status.joinToString",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime:{name}", anchor in runtime, anchor)

    resolver_anchors = {
        "resolver version": "aq.battle-resolver-transaction.p6k.v0.10",
        "bind failure": "PASSIVE_SHIELD_BREAK_RESIST_BIND_REJECTED",
        "ledger failure": "PASSIVE_SHIELD_BREAK_RESIST_LEDGER_REJECTED",
        "status apply failure": "MONSTER_HOSTILE_STATUS_APPLY_REJECTED",
        "runtime compile": "VNextShieldBreakStatusResistRuntimeP6kCompiler.compile(registry)",
        "content hash": "shieldBreakResistContentHash = V_NEXT_P6K_SHIELD_BREAK_RESIST_CONTENT_HASH",
        "commit after low hp": "shieldBreakResistSession.commitIncoming(",
        "prepared from actual target": "frameAfterPacket = lowHpIncomingCommit.frameAfter",
        "status prepare": "shieldBreakResistSession.prepareHostileStatus(",
        "barrier observed": "barrierBlocked = committedResult.barrierConsumed > 0",
        "keyed roll": '"P6K_MONSTER_HOSTILE_STATUS_$statusIndex"',
        "status pipeline call": "VNextMonsterHostileStatusPipelineP6k.apply(",
        "base resistance explicit zero": "targetStatusResistance = 0",
        "tag resistance explicit empty": "targetTagResistanceBps = emptyMap()",
        "status commit": "shieldBreakResistSession.commitHostileStatus(",
        "resisted counter": 'it.endsWith(":RESISTED")',
        "miss counter": "monsterHostileStatusMissCount++",
        "barrier counter": "monsterHostileStatusBarrierBlockedCount++",
        "status receipt": 'actionId = "P6K_MONSTER_HOSTILE_STATUS"',
        "encounter reconcile": "shieldBreakResistSession.reconcileEncounterEnd(shieldBreakResistLedger, heroStatus)",
        "transaction content hash": "append(transaction.shieldBreakResistContentHash)",
        "transaction status counts": "append(transaction.monsterHostileStatusResistedCount)",
        "handled passive": "addAll(VNextShieldBreakStatusResistRuntimeP6k.supportedDefinitionIds())",
        "beneficial token": '"PREPARED_SHIELD_BREAK_STATUS_RESIST"',
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)

    unit_test_anchors = {
        "coverage": "coverage_activates_protective_prayer_nine_poison_carriers_and_zero_after_incoming_deferred",
        "five levels": "mapOf(1 to 200, 25 to 300, 50 to 400, 75 to 500, 100 to 600)",
        "invalid break cases": "absent_remaining_and_lethal_shield_cases_do_not_arm",
        "borderline resist": "armed_resistance_changes_a_borderline_poison_roll_and_consumes_even_when_resisted",
        "miss barrier retains": "miss_and_barrier_block_do_not_consume_but_the_next_eligible_package_does",
        "poison exact split": "poison_carrier_is_three_target_roots_and_thirty_percent_source_power_total",
        "expire rearm": "encounter_end_expires_an_unspent_token_and_a_later_break_can_rearm_after_consumption",
        "fail closed": "malformed_or_orphan_token_wrong_owner_stale_plan_and_replay_fail_closed",
        "defended final 6400": "assertEquals(listOf(6_400), defended.finalApplyBps)",
        "control final 7000": "assertEquals(listOf(7_000), control.finalApplyBps)",
        "periodic total 3000": "assertEquals(3_000, result.periodicTotal)",
    }
    for name, anchor in unit_test_anchors.items():
        check(f"unit-test:{name}", anchor in runtime_tests, anchor)

    integration_anchors = {
        "integration case": "p6k shield break arms protection and lowers the next monster poison apply chance once",
        "grass goblin carrier": 'it.baseMonsterId == "aq.enemy.green_hills.grass_goblin"',
        "prayer equipped": 'passiveIds = listOf("aq.skill.cleric.w4.protectiveprayer")',
        "level 50 modifier": '"ready=true|resist=400"',
        "defended chance": "assertEquals(6_600, finalApply(protectedStatus))",
        "control chance": "assertEquals(7_000, finalApply(controlStatus))",
        "content asserted": "V_NEXT_P6K_SHIELD_BREAK_RESIST_CONTENT_HASH",
        "ledger changed": "defended.initialShieldBreakResistLedgerHash",
        "armed count": "defended.protectivePrayerArmedCount >= 1",
        "consumed count": "defended.protectivePrayerConsumedCount >= 1",
        "status offer": "defended.monsterHostileStatusOfferCount >= 1",
        "break receipt": '"P6K_SHIELD_BREAK:" in it.detail',
        "transaction rehash": "VNextBattleResolverTransaction.transactionHash(defended)",
    }
    for name, anchor in integration_anchors.items():
        check(f"integration-test:{name}", anchor in resolver_tests, anchor)

    check("settlement:resolveKill remains", "fun resolveKill(" in settlement, "resolveKill")
    check("settlement:no p6k coupling", "P6k" not in settlement and "p6k" not in settlement, "uncoupled")

    for path, expected in FREEZE.items():
        check(f"freeze:{path.name}", sha(path) == expected, sha(path))
    check("artifact:apk size", APK.stat().st_size == EXPECTED_APK_SIZE, APK.stat().st_size)
    check("artifact:apk hash", sha(APK) == EXPECTED_APK_SHA, sha(APK))
    check("artifact:screen hash", sha(SCREEN) == EXPECTED_SCREEN_SHA, sha(SCREEN))
    check("artifact:ui hash", sha(UI) == EXPECTED_UI_SHA, sha(UI))
    check("artifact:png signature", SCREEN.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), SCREEN.stat().st_size)
    ui_text = UI.read_text()
    check("artifact:ui parse", ET.parse(UI).getroot().tag == "hierarchy", "hierarchy")
    check("artifact:fresh roster", "아직 캐릭터가 없습니다" in ui_text, "empty")
    check("artifact:create character", "새 캐릭터" in ui_text, "create")

    engine = report_totals(ROOT / "game-engine/build/test-results/test")
    app_debug = report_totals(ROOT / "app/build/test-results/testDebugUnitTest")
    app_release = report_totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    total = tuple(sum(values) for values in zip(engine, app_debug, app_release))
    check("reports:engine", engine == (693, 0, 0, 0), engine)
    check("reports:app debug", app_debug == (88, 0, 0, 0), app_debug)
    check("reports:app release", app_release == (88, 0, 0, 0), app_release)
    check("reports:total", total == (869, 0, 0, 0), total)

    lint_path = ROOT / "app/build/reports/lint-results-debug.xml"
    lint_root = ET.parse(lint_path).getroot()
    lint_errors = sum(1 for issue in lint_root.findall("issue") if issue.attrib.get("severity") == "Error")
    lint_warnings = sum(1 for issue in lint_root.findall("issue") if issue.attrib.get("severity") == "Warning")
    check("reports:lint errors", lint_errors == 0, lint_errors)
    check("reports:lint warnings", lint_warnings == 22, lint_warnings)

    expected_audit = {
        "verificationTarget": "android-emulator",
        "serial": "emulator-5554",
        "avdName": "alarmquest-qa",
        "androidRelease": "15",
        "apiLevel": "35",
        "packageName": "com.alarmquest",
        "versionName": "0.1.0",
        "versionCode": "1",
        "targetSdk": "36",
        "apkSizeBytes": str(EXPECTED_APK_SIZE),
        "apkSha256": EXPECTED_APK_SHA,
        "installResult": "Success",
        "pmClearResult": "Success",
        "launchState": "COLD",
        "coldTotalTimeMs": "4501",
        "coldWaitTimeMs": "4508",
        "topResumedActivity": "com.alarmquest/.MainActivity",
        "freshRosterEmpty": "true",
        "androidRuntimeFatalCount": "0",
        "emulatorScreenshotSha256": EXPECTED_SCREEN_SHA,
        "emulatorUiDumpSha256": EXPECTED_UI_SHA,
        "engineTests": "693",
        "appDebugTests": "88",
        "appReleaseTests": "88",
        "appTests": "176",
        "totalTests": "869",
        "testFailures": "0",
        "testErrors": "0",
        "testSkipped": "0",
        "lintErrors": "0",
        "lintWarnings": "22",
        "featureDefaultEnabled": "false",
        "liveSettlementEnabled": "false",
        "passiveDefinitionsClassified": "90",
        "passiveSlotCap": "3",
        "previousPassiveDefinitionsEnabled": "41",
        "shieldBreakStatusResistDefinitionsEnabled": "1",
        "totalPassiveDefinitionsEnabled": "42",
        "remainingPassiveEffectsEnabled": "0",
        "remainingPassiveDefinitions": "48",
        "afterIncomingPacketDefinitionsClassified": "7",
        "afterIncomingPacketDefinitionsEnabled": "7",
        "afterIncomingPacketDefinitionsDeferred": "0",
        "shieldBreakStatusResistRulesVersion": "aq.shield-break-status-resist.p6k.v0.1",
        "shieldBreakStatusResistContentHash": "f0749919f5bda3f7d852aa6412cf50337ac3741075b61e375cceee3d22ae544f",
        "battleResolverRulesVersion": "aq.battle-resolver-transaction.p6k.v0.10",
        "protectivePrayerEnabled": "true",
        "protectivePrayerSkillId": "aq.skill.cleric.w4.protectiveprayer",
        "protectivePrayerOwnerClass": "CLERIC",
        "protectivePrayerLv1StatusResistanceAddBps": "200",
        "protectivePrayerLv25StatusResistanceAddBps": "300",
        "protectivePrayerLv50StatusResistanceAddBps": "400",
        "protectivePrayerLv75StatusResistanceAddBps": "500",
        "protectivePrayerLv100StatusResistanceAddBps": "600",
        "protectivePrayerRegistryStackPolicy": "ADD_THEN_CLAMP",
        "protectivePrayerRegistryStackCapBps": "1800",
        "tokenClock": "ENCOUNTER",
        "tokenCostLocked": "true",
        "nextHostileStatusAttemptConsumes": "true",
        "missDoesNotConsume": "true",
        "barrierBlockDoesNotConsume": "true",
        "sameActionBreakThenStatusSupported": "true",
        "monsterAbilityDefinitionsTyped": "55",
        "monsterAbilityContractVersion": "aq.monster-ability-execution.v0.9-test",
        "monsterStatusCarrierAbilities": "9",
        "monsterStatusCarrierTag": "POISON",
        "monsterStatusBaseApplyBps": "7000",
        "monsterPoisonDurationTargetRoots": "3",
        "monsterPoisonTotalSourceMagicalAttackBps": "3000",
        "baseHeroStatusResistanceWired": "false",
        "equipmentTagResistanceWired": "false",
        "baseHeroStatusResistanceFallback": "0",
        "equipmentTagResistanceFallback": "emptyMap",
        "strictCodecRoundTrip": "true",
        "encounterEndLedgerReconciled": "true",
        "visualAssetRequired": "false",
        "designTeamImageRequested": "false",
        "monsterProductionBalanceFinal": "false",
        "campaignResolverWiredToLiveSettlement": "false",
        "legacySettlementHash": FREEZE[SETTLEMENT_PATH],
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, audit.get(key))

    doc_anchors = {
        "conditional approval": "조건부 승인",
        "42 enabled": "Passive 42종 구현",
        "48 off": "미구현 48종은 효과 OFF",
        "after incoming complete": "7/7 실행, 보류 0",
        "level cap": "캐릭터 Lv 상한 9,999",
        "skill cap": "Skill Lv100",
        "three passive slots": "Passive 3슬롯",
        "automated battle": "전투 중 버튼 입력은 없다",
        "55 test contract": "aq.monster-ability-execution.v0.9-test",
        "provisional values": "최종 production 밸런스가 아니다",
        "base status blocker": "targetStatusResistance",
        "equipment blocker": "targetTagResistanceBps",
        "live off": "라이브 ON은 금지",
        "no visual change": "디자인팀 이미지 요청과 앱 UI 변경은 하지 않았다",
        "next UI assets": "보호 기도 Passive 아이콘",
        "test total": "total 869",
        "lint": "lint errors 0 / warnings 22",
        "cold launch": "TotalTime 4501ms, WaitTime 4508ms",
        "apk hash": EXPECTED_APK_SHA,
        "runtime hash": FREEZE[RUNTIME_PATH],
        "resolver hash": FREEZE[RESOLVER_PATH],
    }
    for name, anchor in doc_anchors.items():
        check(f"doc:{name}", anchor in doc, anchor)

    if args.with_emulator:
        serial = audit["serial"]
        state = command("adb", "-s", serial, "get-state")
        release = command("adb", "-s", serial, "shell", "getprop", "ro.build.version.release")
        api = command("adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk")
        avd = command("adb", "-s", serial, "shell", "getprop", "ro.boot.qemu.avd_name")
        package = command("adb", "-s", serial, "shell", "dumpsys", "package", "com.alarmquest")
        focus = command("adb", "-s", serial, "shell", "dumpsys", "activity", "activities")
        crash = command("adb", "-s", serial, "logcat", "-d", "-b", "crash", "AndroidRuntime:E", "*:S")
        command("adb", "-s", serial, "shell", "uiautomator", "dump", "/sdcard/p6k-review.xml")
        live_ui = command("adb", "-s", serial, "exec-out", "cat", "/sdcard/p6k-review.xml")
        command("adb", "-s", serial, "shell", "rm", "/sdcard/p6k-review.xml")
        check("emulator:device", state == "device", state)
        check("emulator:release", release == "15", release)
        check("emulator:api", api == "35", api)
        check("emulator:avd", avd == "alarmquest-qa", avd)
        check("emulator:version code", "versionCode=1" in package, "versionCode=1")
        check("emulator:version name", "versionName=0.1.0" in package, "versionName=0.1.0")
        check("emulator:target sdk", "targetSdk=36" in package, "targetSdk=36")
        check("emulator:focus", "topResumedActivity" in focus and "com.alarmquest/.MainActivity" in focus, "MainActivity")
        check("emulator:no fatal", "FATAL EXCEPTION" not in crash, "fatal=0")
        check("emulator:fresh roster", "아직 캐릭터가 없습니다" in live_ui, "empty")
        check("emulator:create character", "새 캐릭터" in live_ui, "create")

    passed = sum(1 for _, ok, _ in checks if ok)
    failed = len(checks) - passed
    print(f"RESULT: PASS={passed} FAIL={failed} TOTAL={len(checks)}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
