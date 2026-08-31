#!/usr/bin/env python3
"""PD audit for P6q Oath Echo confirmed-Heal token and next offense Active hit."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6q-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6q-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6q-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6Q_OATH_ECHO_v0.1.md"
RUNTIME = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextOathEchoRuntimeP6q.kt"
RUNTIME_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextOathEchoRuntimeP6qTest.kt"
RESOLVER = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt"
RESOLVER_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt"
BALANCE = ROOT / "tools/skill_wave1_active5_passive3_v0_1_review.py"
SETTLEMENT = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt"

APK_SHA = "d9b02520f9d01c412c1d8a80d988bad855a395b91137806ae20132bcaea4598f"
SCREEN_SHA = "5aad110124960ec6b91fc5a25c0372a8292238dbc812002a5cc5c19b684966c3"
UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
APK_SIZE = 37_318_602
FREEZE = {
    RUNTIME: "f171cd4f4e99e7c8f32799ae118c93fbb18696aa5b8426b26ff6fa3bf1c7623f",
    RUNTIME_TEST: "8c1126ec16cc2501c43748599867c87046cd781a0ec5275d4586261652d60362",
    RESOLVER: "5c056b4b5b187c690f9195ac5bf59e7cca8474e635a8f430a457dcc78d25c5a8",
    RESOLVER_TEST: "8b920c5bfcb7fd74abb211ce179057cc6fc2e3cabeb569f95b1bf6040bfb0cd7",
    BALANCE: "bef4472bac738ea8ce9d619ed28755eb2e30e1bd6528fc87493f8c9dddfbcda1",
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
    balance = BALANCE.read_text()
    settlement = SETTLEMENT.read_text()
    audit = parse_audit(AUDIT.read_text())
    doc = DOC.read_text()

    runtime_anchors = {
        "rules": "aq.oath-echo.p6q.v0.1",
        "content": "e4874d4c71a85cf624ac90caf5b69fca660de5f9430d95855c1454e636a0be30",
        "definition": "V_NEXT_P6Q_OATH_ECHO_DEFINITION_COUNT = 1",
        "cap": "V_NEXT_P6Q_PASSIVE_HIT_CAP_BPS = 1_200",
        "skill": "aq.skill.paladin.p3.othecho",
        "owner": 'source.ownerScope != "PALADIN"',
        "pattern": 'source.pattern != "ACCURACY"',
        "growth": 'source.growthField != "hit_modifier_bps"',
        "condition": 'source.conditionId != "after_heal_active"',
        "host": 'source.hostScope != "NEXT_OFFENSE_ACTIVE"',
        "group": 'source.stackGroup != "aq.stack.passive.hit"',
        "policy": 'source.stackPolicy != "ADD_THEN_CLAMP"',
        "anchors": "listOf(200, 300, 400, 500, 600)",
        "fixed": "조건부 발동=after_heal_active·미충족 시 효과0",
        "event": "P5wPassiveEvent.AFTER_OUTGOING_PACKET",
        "deferred": "deferredAfterOutgoingIds.size == 10",
        "offense": '"ATTACK" in activePlan.roles && activePlan.hitPackets > 0',
        "boolean": "val oathEchoArmed: Boolean",
        "confirmed heal": "healActionCommitted: Boolean",
        "consume then rearm": "val armedAfterConsume = if (plan.consumesOathEcho) false",
        "rearm": "if (healActionCommitted && oathEchoPlan != null) true",
        "stale": "plan.ledgerBeforeHash == ledger.ledgerHash",
        "replay": "receiptId !in ledger.processedReceiptIds",
        "receipt count": "receipts.size == committedActiveCount",
        "consume invariant": "consumedCount <= healTriggerCount",
        "passive clamp": "coerceAtMost(V_NEXT_P6Q_PASSIVE_HIT_CAP_BPS)",
        "hit recalc": "hitAddBps = profileHitAddBps + passive",
        "decision hash": '"$decisionHash|p6q=${plan.planHash}',
        "live false": "val liveReady: Boolean get() = false",
        "delayed policy": "delayed=CONSUME_AT_ARM_AND_P6D_FREEZE",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime:{name}", anchor in runtime, anchor)

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6q.v0.16",
        "bind failure": "PASSIVE_OATH_ECHO_BIND_REJECTED",
        "ledger failure": "PASSIVE_OATH_ECHO_LEDGER_REJECTED",
        "compile": "VNextOathEchoRuntimeP6qCompiler.compile(registry)",
        "bind": "oathEchoRuntime.bind(",
        "content field": "val oathEchoContentHash: String",
        "session field": "val oathEchoSessionHash: String",
        "ledger fields": "val finalOathEchoLedgerHash: String",
        "trigger count": "val oathEchoHealTriggerCount: Int",
        "consumed count": "val oathEchoConsumedCount: Int",
        "final armed": "val finalOathEchoArmed: Boolean",
        "prepare": "val oathEchoPlan = oathEchoSession.prepareActive(",
        "merge": ".withP6c(afterOutgoingPlan).withP6q(oathEchoPlan).withP6o(expeditionOutgoingPlan)",
        "commit": "oathEchoSession.commitActive(",
        "p6p before p6q": "recordCursedMresHeal(nextCursedMresHealLedger)",
        "receipt": '"$receiptId:p6q-active"',
        "detail": '"${outgoingDecision.detail}|${afterOutgoingPlan.detail}|${oathEchoPlan.detail}|"',
        "hash content": "append(transaction.oathEchoContentHash)",
        "hash armed": "append(transaction.finalOathEchoArmed)",
        "handled": "addAll(VNextOathEchoRuntimeP6q.supportedDefinitionIds())",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)
    check(
        "resolver:p6p commit precedes p6q commit",
        resolver.index("recordCursedMresHeal(nextCursedMresHealLedger)") <
        resolver.index("val nextOathEchoLedger = runCatching"),
        "P6p then P6q",
    )

    unit_anchors = {
        "coverage": "coverage_enables_oath_echo_and_reduces_after_outgoing_deferred_set_to_ten",
        "five anchors": "mapOf(1 to 200, 25 to 300, 50 to 400, 75 to 500, 100 to 600)",
        "nonpacket": "basic_non_packet_and_unconfirmed_actions_do_not_consume_or_arm_the_token",
        "hybrid": "hybrid_attack_heal_consumes_the_old_token_then_rearms_a_fresh_one",
        "cap": "passive_hit_adds_then_clamps_with_existing_p6b_and_p6c_groups",
        "delayed": "delayed_offense_consumes_at_arm_and_p6d_freezes_the_hit_modifier_without_refund",
        "replay": "ledger_rejects_replay_stale_plans_and_wrong_class_loadouts",
        "frozen hit": "assertEquals(600, frozen.passiveHitAddBps)",
        "wrong owner": 'compiled.runtime.bind("WARRIOR"',
    }
    for name, anchor in unit_anchors.items():
        check(f"unit:{name}", anchor in runtime_test, anchor)

    integration_anchors = {
        "basic preserve": "p6q confirmed heal arms oath echo and basics preserve it for the next offense active",
        "cancel": "p6q delayed offense freezes oath hit at arm and cancellation gives no token refund",
        "production heal": "p6q production paladin heal adapter arms on typed zero heal",
        "invalid heal": "p6q invalid typed heal modifier fails before oath ledger mutation",
        "resolve": "p6q delayed offense resolves with the arm frozen oath modifier exactly once",
        "cancel reason": "DELAYED_OWN_ROOT_CANCELLED",
        "resolve reason": "DELAYED_OWN_ROOT_RESOLUTION",
        "cancel frozen": 'assertTrue("passiveHit=400" in cancelled.detail)',
        "resolve frozen": 'assertTrue("passiveHit=400" in resolved.detail)',
        "invalid untouched": "assertEquals(result.initialOathEchoLedgerHash, result.finalOathEchoLedgerHash)",
        "rehash": "VNextBattleResolverTransaction.transactionHash(result)",
    }
    for name, anchor in integration_anchors.items():
        check(f"integration:{name}", anchor in resolver_test, anchor)

    balance_anchors = {
        "start snapshot": "oath_echo_ready_at_action_start = runtime.paladin_oath_echo_ready",
        "old token hit": 'has(passive_ids, "othecho") and oath_echo_ready_at_action_start',
        "consume": "runtime.paladin_oath_echo_ready = False",
        "rearm": 'selected.protection_kind == "HEAL" and has(passive_ids, "othecho")',
        "pd seeds": "seeds = 200 if args.pd else args.seeds",
        "loadouts": "ALL_153600_LOADOUT_LEVEL_SUBSETS_PRESERVE_HARD_BUDGETS",
    }
    for name, anchor in balance_anchors.items():
        check(f"balance:{name}", anchor in balance, anchor)

    check("settlement:present", "fun resolveKill(" in settlement, "present")
    check("settlement:uncoupled", "P6q" not in settlement and "p6q" not in settlement, "uncoupled")
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
    check("reports:engine", engine == (748, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (924, 0, 0, 0), total)
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
        "launchState": "COLD", "coldTotalTimeMs": "3688", "coldWaitTimeMs": "3692",
        "topResumedActivity": "com.nullplaying/.MainActivity", "freshRosterEmpty": "true",
        "androidRuntimeFatalCount": "0", "emulatorScreenshotSha256": SCREEN_SHA,
        "emulatorUiDumpSha256": UI_SHA, "engineTests": "748", "appDebugTests": "88",
        "appReleaseTests": "88", "totalTests": "924", "testFailures": "0",
        "testErrors": "0", "testSkipped": "0", "lintErrors": "0", "lintWarnings": "22",
        "featureDefaultEnabled": "false", "liveSettlementEnabled": "false",
        "previousPassiveDefinitionsEnabled": "50", "oathEchoDefinitionsEnabled": "1",
        "totalPassiveDefinitionsEnabled": "51", "remainingPassiveDefinitions": "39",
        "oathEchoRulesVersion": "aq.oath-echo.p6q.v0.1",
        "oathEchoContentHash": "e4874d4c71a85cf624ac90caf5b69fca660de5f9430d95855c1454e636a0be30",
        "battleResolverRulesVersion": "aq.battle-resolver-transaction.p6q.v0.16",
        "oathEchoConfirmedHealTrigger": "true", "oathEchoBasicExcluded": "true",
        "oathEchoDelayedFrozen": "true", "oathEchoCancelRefund": "false",
        "oathEchoConsumeThenRearm": "true", "oathEchoPassiveHitCapBps": "1200",
        "balancePdSeeds": "200", "balancePdResult": "PASS_9_OF_9",
        "balanceCanonicalHash": "02246661576f1b664c902e80a09f6b39b7789b5bd2751b0da5e3c416abd0ebc3",
        "paladinMaxCellWinDeltaPp": "2.50", "paladinMaxMedianRoundDelta": "1.0",
        "paladinMaxP90HpLossDeltaPp": "1.65", "paladinWorstWeightedWinLossPp": "0.10",
        "visualAssetRequired": "false", "designTeamImageRequested": "false",
        "pdFinalDecision": "GO_ENGINE_SCOPE_FEATURE_OFF", "systemAuditP0Count": "0",
        "systemAuditP1Count": "0",
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, audit.get(key))

    for anchor in [
        "PD 최종 GO", "Passive: **51/90**", "맹세의 잔향", "consume → rearm",
        "지연 해소와 지연 취소", "200시드", "2.50%p", "108,000 유지", "잔여 Passive: **39/90 OFF**",
        "디자인팀 이미지 요청은 하지 않았다", "feature OFF", APK_SHA, FREEZE[RUNTIME], FREEZE[RESOLVER],
        "engine 748 + app debug 88 + app release 88 = **924**",
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
