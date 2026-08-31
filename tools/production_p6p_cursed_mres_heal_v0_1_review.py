#!/usr/bin/env python3
"""PD audit for P6p Soul Frost cursed MRES and fixed Heal liability."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6p-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6p-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6p-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6P_CURSED_MRES_HEAL_v0.1.md"
RUNTIME = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCursedMagicResistanceRuntimeP6p.kt"
RUNTIME_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCursedMagicResistanceRuntimeP6pTest.kt"
RESOLVER = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt"
RESOLVER_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt"
SETTLEMENT = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt"

APK_SHA = "a1a602b28148186b111713dd4ddc82567337b46b9233d5e961c8d4da9f57fee9"
SCREEN_SHA = "c5b9479a8e305c32ccf8b352383694913b849eb8ddb9427b53d27e21d4e034f9"
UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
APK_SIZE = 37_318_602
FREEZE = {
    RUNTIME: "c5435a5c8e234c5d3cff0b0c941c20236700e424ae4ea3d5e02a0c9add3c0e81",
    RUNTIME_TEST: "3ce02943e827f216efbf5f634b15db255107e04d35908a792a9d64135aaba22e",
    RESOLVER: "f6433bd7ce169f2c1d1990a4b0c26d2eebf38655c5e1b2d4cbe605c1769af71d",
    RESOLVER_TEST: "98da542f44b185d6cd415d340a54f3fbbdd04a3753dad6bf1cc39ab78447c348",
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
    settlement = SETTLEMENT.read_text()
    audit = parse_audit(AUDIT.read_text())
    doc = DOC.read_text()

    for name, anchor in {
        "rules": "aq.cursed-mres-heal.p6p.v0.1",
        "content": "317525d09d71e2c136a99ddab76aac56d1b65fe372228a0db58f105e6109f6f4",
        "definitions": "V_NEXT_P6P_CURSED_MRES_HEAL_DEFINITION_COUNT = 1",
        "mres cap": "V_NEXT_P6P_CURSED_MRES_CAP_BPS = 2_700",
        "heal penalty": "V_NEXT_P6P_SOUL_FROST_HEAL_PENALTY_BPS = -1_000",
        "skill": "aq.skill.monster.w8.soulfrost",
        "event": "P5wPassiveEvent.BEFORE_INCOMING_PACKET",
        "owner": 'source.ownerScope != "ALL"',
        "pattern": 'source.pattern != "CURSED_MAGIC_RESIST_FOR_HEAL"',
        "growth": 'source.growthField != "mres_add_bps"',
        "condition": 'source.conditionId != "self_has_curse"',
        "stack group": 'source.stackGroup != "aq.stack.passive.magical_resistance"',
        "policy": 'source.stackPolicy != "ADD_THEN_CLAMP"',
        "anchors": "listOf(300, 450, 600, 750, 900)",
        "fixed tradeoff": "받는 Heal 효율-1,000 고정·CURSE 제거/면역 없음",
        "deferred twelve": "deferredBeforeIncomingIds.size == 12",
        "live false": "val liveReady: Boolean get() = false",
        "curse typed": 'it.tag == "CURSE" && it.stacks > 0',
        "true excluded": 'setOf("PHYSICAL", "TRUE", "TRUE_DAMAGE")',
        "incoming prepare": "fun prepareIncoming(",
        "incoming stale": "plan.ledgerBeforeHash == ledger.ledgerHash",
        "incoming replay": "receiptId !in ledger.processedReceiptIds",
        "heal combine": "(baseHealingModifierBps + soulPenalty).coerceIn(-9_000, 0)",
        "heal floor": "applyHealingModifierP6p(observed, combined)",
        "self damage preserve": "if (observed == 0)",
        "heal commit": "fun commitHealing(",
        "heal identity": "totalEffectiveHeal + totalHealingPrevented == totalObservedHeal",
        "mres half up": "10_000L + modifierBps) + 5_000L",
        "periodic pinned": "packets=MONSTER_ACTION_AND_PERIODIC",
    }.items():
        check(f"runtime:{name}", anchor in runtime, anchor)

    for name, anchor in {
        "version": "aq.battle-resolver-transaction.p6p.v0.15",
        "bind failure": "PASSIVE_CURSED_MRES_HEAL_BIND_REJECTED",
        "ledger failure": "PASSIVE_CURSED_MRES_HEAL_LEDGER_REJECTED",
        "compile": "VNextCursedMagicResistanceRuntimeP6pCompiler.compile(registry)",
        "bind": "cursedMresHealRuntime.bind(",
        "content field": "val cursedMresHealContentHash: String",
        "packet counter": "val cursedMresCommittedIncomingPacketCount: Int",
        "heal counter": "val cursedMresCommittedHealingActionCount: Int",
        "prevented counter": "val cursedMresTotalHealingPrevented: Int",
        "direct prepare": "val cursedMresIncomingPlan = cursedMresHealSession.prepareIncoming(",
        "common mres": "targetMagicalResistanceAddBps = cursedMresIncomingPlan.magicalResistanceAddBps",
        "direct commit": '"$receiptId:p6p-incoming"',
        "periodic typed": "val periodicIncomingStatuses = if (",
        "periodic hero": "ownerSide == VNextCombatSide.HERO && root == P5eStatusSystemRoot.TARGET_ROOT_START",
        "periodic magical": 'it.periodicDamageChannel == "MAGICAL"',
        "attempt frame": "val schedulerFrame = if",
        "mres restore": "magicalResistance = ownerFrame.magicalResistance",
        "actual ticks": "val tickedStatusIds = scheduled.damageByStatus",
        "periodic receipt": '"$systemReceiptId:p6p-periodic:${status.instanceId}"',
        "periodic detail": "P6P_PERIODIC[",
        "active heal typed": "if (dispatched.healActionCommitted)",
        "active heal receipt": '"$receiptId:p6p-heal"',
        "encounter heal observed": "actorAfter = encounterEndCommit.frameAfter",
        "encounter heal receipt": '"$encounterEndReceiptId:p6p-heal"',
        "hash content": "append(transaction.cursedMresHealContentHash)",
        "hash prevented": "append(transaction.cursedMresTotalHealingPrevented)",
        "handled": "addAll(VNextCursedMagicResistanceRuntimeP6p.supportedDefinitionIds())",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }.items():
        check(f"resolver:{name}", anchor in resolver, anchor)

    for name, anchor in {
        "coverage": "coverage_enables_soul_frost_and_reduces_before_incoming_deferred_set_to_twelve",
        "five anchors": "mapOf(1 to 300, 25 to 450, 50 to 600, 75 to 750, 100 to 900)",
        "channel zero": "physical_true_and_uncursed_packets_gain_no_resistance_and_curse_is_not_removed",
        "sequential": "plate_prayer_hold_line_and_soul_frost_use_frozen_sequential_multipliers_and_soul_cap",
        "fixed heal": "fixed_heal_penalty_is_always_equipped_and_adds_to_existing_liability_once",
        "self damage": "non_heal_hp_loss_is_preserved_instead_of_being_mistaken_for_zero_healing",
        "replay": "packet_and_heal_commits_hash_counts_and_reject_replay_or_stale_plans",
        "cap reject": "V_NEXT_P6P_CURSED_MRES_CAP_BPS + 1",
    }.items():
        check(f"unit:{name}", anchor in runtime_test, anchor)

    for name, anchor in {
        "direct case": "p6p cursed hero applies soul frost only to real magical monster packets",
        "direct mres": '"mres=600" in defendedPacket.detail',
        "direct mitigation": "defendedPacket.hpDamage < controlPacket.hpDamage",
        "physical zero": 'assertFalse("P6P_INCOMING:" in physicalPacket.detail)',
        "periodic case": "p6p periodic root applies soul frost to magical tick but not physical or true tick",
        "periodic detail": '"P6P_PERIODIC[magical.p6p:P6P_INCOMING:"',
        "periodic physical zero": 'assertFalse("physical.p6p:P6P_INCOMING:"',
        "periodic true zero": 'assertFalse("true.p6p:P6P_INCOMING:"',
        "periodic counts": "assertEquals(3, defended.cursedMresCommittedIncomingPacketCount)",
        "periodic applied": "assertEquals(1, defended.cursedMresAppliedPacketCount)",
        "mres restored": "assertEquals(hero.magicalResistance, defended.finalHero.magicalResistance)",
        "active case": "p6p active heal combines soul frost and existing fixed liabilities additively",
        "active effective": "assertEquals(800, result.cursedMresTotalEffectiveHeal)",
        "encounter case": "p6p soul frost also reduces typed encounter end heals without changing sustain budgets",
        "encounter effective": "assertEquals(1_260, result.cursedMresTotalEffectiveHeal)",
        "rehash": "VNextBattleResolverTransaction.transactionHash(result)",
    }.items():
        check(f"integration:{name}", anchor in resolver_test, anchor)

    check("settlement:present", "fun resolveKill(" in settlement, "present")
    check("settlement:uncoupled", "P6p" not in settlement and "p6p" not in settlement, "uncoupled")
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
    check("reports:engine", engine == (736, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (912, 0, 0, 0), total)
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
        "launchState": "COLD", "coldTotalTimeMs": "4347", "coldWaitTimeMs": "4352",
        "topResumedActivity": "com.nullplaying/.MainActivity", "freshRosterEmpty": "true",
        "androidRuntimeFatalCount": "0", "emulatorScreenshotSha256": SCREEN_SHA,
        "emulatorUiDumpSha256": UI_SHA, "engineTests": "736", "appDebugTests": "88",
        "appReleaseTests": "88", "totalTests": "912", "testFailures": "0",
        "testErrors": "0", "testSkipped": "0", "lintErrors": "0", "lintWarnings": "22",
        "featureDefaultEnabled": "false", "liveSettlementEnabled": "false",
        "previousPassiveDefinitionsEnabled": "49", "cursedMresHealDefinitionsEnabled": "1",
        "totalPassiveDefinitionsEnabled": "50", "remainingPassiveDefinitions": "40",
        "cursedMresHealRulesVersion": "aq.cursed-mres-heal.p6p.v0.1",
        "cursedMresHealContentHash": "317525d09d71e2c136a99ddab76aac56d1b65fe372228a0db58f105e6109f6f4",
        "battleResolverRulesVersion": "aq.battle-resolver-transaction.p6p.v0.15",
        "soulFrostEnabled": "true", "soulFrostFixedHealPenaltyBps": "-1000",
        "periodicMagicalPacketsIncluded": "true", "physicalPacketsExcluded": "true",
        "trueDamagePacketsExcluded": "true", "attemptLocalMresRestored": "true",
        "visualAssetRequired": "false", "designTeamImageRequested": "false",
        "pdFinalDecision": "GO_ENGINE_SCOPE_FEATURE_OFF", "systemAuditP0Count": "0",
        "systemAuditP1Count": "0",
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, audit.get(key))

    for anchor in [
        "최종 GO(엔진 범위)", "Passive 50/90", "영혼 냉기", "마법 주기 피해",
        "ENCOUNTER_END Heal root", "Lv9,999", "잔여 40종", "디자인팀 이미지 요청",
        "라이브 ON 전 필수 조건", "PD 최종 GO", APK_SHA, FREEZE[RUNTIME],
        FREEZE[RESOLVER], "engine 736 + app debug 88 + app release 88 = total 912",
    ]:
        check(f"doc:{anchor[:28]}", anchor in doc, anchor)

    if args.with_emulator:
        devices = command("adb", "devices", "-l")
        package = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying")
        activity = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities")
        check("emulator:connected", "emulator-5554" in devices and " device " in devices, devices)
        check("emulator:release", command("adb", "-s", "emulator-5554", "shell", "getprop", "ro.build.version.release") == "15", "15")
        check("emulator:api", command("adb", "-s", "emulator-5554", "shell", "getprop", "ro.build.version.sdk") == "35", "35")
        check("emulator:version", "versionName=0.1.0" in package and "versionCode=1" in package, "0.1.0(1)")
        check("emulator:target", "targetSdk=36" in package, "36")
        check("emulator:focus", "com.nullplaying/.MainActivity" in activity and "Resumed" in activity, "MainActivity")

    passed = sum(ok for _, ok, _ in checks)
    failed = len(checks) - passed
    print(f"SUMMARY PASS={passed} FAIL={failed} TOTAL={len(checks)}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
