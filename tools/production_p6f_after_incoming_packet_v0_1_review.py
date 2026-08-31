#!/usr/bin/env python3
"""PD audit for P6f result-side incoming passive and emulator evidence."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6f-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6f-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6f-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6F_AFTER_INCOMING_PACKET_v0.1.md"
EXPECTED_APK_SHA = "acd968058a9d0446846844884e6da51541dcbd1e0012ef20bac9be489e985030"
EXPECTED_SCREEN_SHA = "31a40586b164457a2f559f5525e7206d8c537643ae835974c9feb92b38484731"
EXPECTED_UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
EXPECTED_APK_SIZE = 37_687_812
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterIncomingPacketRuntimeP6f.kt":
        "664728b05b60d09efaf134f20d124957f4f047070cb3c94a05c847a0c877b068",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterIncomingPacketRuntimeP6fTest.kt":
        "4240226f83a70d10ddfaafebd072349ff1bc90336efa6fa6f497fe5981c6f632",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "18aa754dc95fd1022040df56a5d474b81654b6f579ff97918d1fb7cfebf0cd0e",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "da1d02de6e7ccbf903bc9038c9241251d46ffcbf80b7b13c48e2af8d82032eee",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeIncomingPacketRuntimeP6e.kt":
        "02c1533ac1765a4e92aa9f97173ea8f80e4b399c20842f145a9cf429b524e88f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedPacketPayloadP6d.kt":
        "f1c0742dd770c041724120c9a40e90c32bd0d14991deaf44b4b93112d28bc389",
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

    runtime_path = ROOT / next(k for k in FREEZE if k.endswith("VNextAfterIncomingPacketRuntimeP6f.kt"))
    test_path = ROOT / next(k for k in FREEZE if k.endswith("VNextAfterIncomingPacketRuntimeP6fTest.kt"))
    resolver_path = ROOT / next(k for k in FREEZE if k.endswith("VNextBattleResolverTransaction.kt"))
    resolver_test_path = ROOT / next(k for k in FREEZE if k.endswith("VNextBattleResolverTransactionTest.kt"))
    runtime = runtime_path.read_text()
    tests = test_path.read_text()
    resolver = resolver_path.read_text()
    resolver_tests = resolver_test_path.read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    audit = AUDIT.read_text()
    doc = DOC.read_text()

    runtime_anchors = {
        "rules": "aq.after-incoming-packet.p6f.v0.1",
        "content": "52cb4ca6d33b9b08fd441eb499e4d38a660a46a25e5079b414435fa1435394e8",
        "one definition": "V_NEXT_P6F_AFTER_INCOMING_DEFINITION_COUNT = 1",
        "stack cap": "V_NEXT_P6F_HOLD_LINE_STACK_CAP = 3",
        "defense cap": "V_NEXT_P6F_HOLD_LINE_DEFENSE_CAP_BPS = 1_000",
        "skill id": "aq.skill.warrior.w8.holdtheline",
        "direct classifier": 'val direct = delivery == "DIRECT"',
        "prior stack": "ledger.consecutiveDamagingDirectHitCount",
        "level anchor": "holdTheLine.resolvedAnchorValue",
        "family clamp": "coerceIn(0, V_NEXT_P6F_HOLD_LINE_DEFENSE_CAP_BPS)",
        "plan ledger": "ledgerBeforeHash = ledger.ledgerHash",
        "direct hit damage gate": "holdTheLine != null && plan.direct && hit && actualHpDamage > 0",
        "stack increment": "ledger.consecutiveDamagingDirectHitCount + 1",
        "stack clamp": "coerceAtMost(V_NEXT_P6F_HOLD_LINE_STACK_CAP)",
        "reset": "} else {\n            0",
        "stale reject": "require(plan.ledgerBeforeHash == ledger.ledgerHash)",
        "damage nonnegative": "require(actualHpDamage >= 0)",
        "replay reject": "receiptId !in ledger.processedReceiptIds",
        "outcome count": "committedIncomingOutcomeCount + 1",
        "owner": 'it.ownerScope == "WARRIOR"',
        "pattern": 'it.pattern == "CONSECUTIVE_HIT_DEFENSE"',
        "growth": 'it.growthField == "defense_per_stack_bps"',
        "condition": 'it.conditionId == "consecutive_incoming_direct_hit"',
        "stack group": 'it.stackGroup == "aq.stack.wave8.frontline"',
        "tradeoff": "3stack cap·회피/고정무효로 피해0이면 초기화·PDEF/MRES 동시",
        "event": "P5wPassiveEvent.AFTER_INCOMING_PACKET",
        "six deferred": "deferredAfterIncomingIds.size == 6",
        "live off": "val liveReady: Boolean get() = false",
        "hash ordering": "ordering=PREPARE_DEFENSE_THEN_COMMIT_OUTCOME",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime {name}", anchor in runtime, "bound")

    test_names = (
        "coverage activates hold the line and defers six cross system after incoming contracts",
        "three consecutive damaging direct hits grow both defense modifier stacks",
        "miss zero actual damage and non direct packets reset the streak",
        "unequipped runtime records outcomes without granting defense",
        "stale plans duplicate receipts and wrong owner fail closed",
    )
    for name in test_names:
        check(f"unit {name[:48]}", name in tests, "covered")
    for anchor in (
        "assertEquals(300 * index, plan.defenseModifierBps)",
        "assertEquals(900, capped.defenseModifierBps)",
        'Triple("DIRECT", false, 0)',
        'Triple("DIRECT", true, 0)',
        'Triple("PERIODIC", true, 100)',
        "assertEquals(0, ledger.consecutiveDamagingDirectHitCount)",
        "assertEquals(2, ledger.committedIncomingOutcomeCount)",
        "assertFailsWith<IllegalArgumentException>",
        'assertFalse(runtime.bind("ROGUE", listOf(passive())).bound)',
        'semantics.compileExecutionPlan("aq.skill.warrior.w8.holdtheline", 100)',
    ):
        check(f"unit assertion {anchor[:42]}", anchor in tests, "covered")

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6f.v0.5",
        "bind failure": "PASSIVE_AFTER_INCOMING_BIND_REJECTED",
        "ledger failure": "PASSIVE_AFTER_INCOMING_LEDGER_REJECTED",
        "compile": "VNextAfterIncomingPacketRuntimeP6fCompiler.compile(registry)",
        "bind": "afterIncomingRuntime.bind(",
        "initial ledger": "afterIncomingSession.initialLedger()",
        "content": "afterIncomingContentHash = V_NEXT_P6F_AFTER_INCOMING_CONTENT_HASH",
        "session": "afterIncomingSessionHash = afterIncomingSession.snapshotHash",
        "prepare": "afterIncomingSession.prepare(delivery, afterIncomingLedger)",
        "packet defense": "targetDefenseAddBps = afterIncomingPlan.defenseModifierBps",
        "pdef boost": "physicalDefense = addDefenseBpsP6f(target.physicalDefense, targetDefenseAddBps)",
        "mres boost": "magicalResistance = addDefenseBpsP6f(target.magicalResistance, targetDefenseAddBps)",
        "pdef restore": "physicalDefense = target.physicalDefense",
        "mres restore": "magicalResistance = target.magicalResistance",
        "commit": "afterIncomingSession.commit(",
        "hit result": "hit = committedResult.hit",
        "actual damage": "actualHpDamage = committedResult.hpDamage",
        "ledger final": "finalAfterIncomingLedgerHash = nextAfterIncomingLedger.ledgerHash",
        "outcome count": "afterIncomingCommittedOutcomeCount = nextAfterIncomingLedger.committedIncomingOutcomeCount",
        "final stack": "nextAfterIncomingLedger.consecutiveDamagingDirectHitCount",
        "receipt detail": "afterIncomingPlan.detail.takeIf",
        "transaction content": "val afterIncomingContentHash: String",
        "transaction session": "val afterIncomingSessionHash: String",
        "transaction initial": "val initialAfterIncomingLedgerHash: String",
        "transaction final": "val finalAfterIncomingLedgerHash: String",
        "transaction count": "val afterIncomingCommittedOutcomeCount: Int",
        "transaction stack": "val finalConsecutiveDamagingDirectHitCount: Int",
        "hash content": "append(transaction.afterIncomingContentHash)",
        "hash session": "append(transaction.afterIncomingSessionHash)",
        "hash ledger": "append(transaction.finalAfterIncomingLedgerHash)",
        "hash count": "append(transaction.afterIncomingCommittedOutcomeCount)",
        "hash stack": "append(transaction.finalConsecutiveDamagingDirectHitCount)",
        "adapter filter": "VNextAfterIncomingPacketRuntimeP6f.supportedDefinitionIds()",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
        "defense add long": "value.toLong() * (10_000 + modifierBps)",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver {name}", anchor in resolver, "bound")

    integration = "p6f hold the line raises packet local defenses after consecutive damaging direct hits"
    check("resolver integration", integration in resolver_tests, "covered")
    for anchor in (
        'passiveIds = listOf("aq.skill.warrior.w8.holdtheline")',
        "it.hit && it.hpDamage > 0",
        "controlReceipt.hit && controlReceipt.hpDamage > 0",
        "assertEquals(V_NEXT_P6F_AFTER_INCOMING_CONTENT_HASH, defended.afterIncomingContentHash)",
        "assertNotEquals(defended.initialAfterIncomingLedgerHash, defended.finalAfterIncomingLedgerHash)",
        "assertEquals(monsterOutcomes, defended.afterIncomingCommittedOutcomeCount)",
        "assertTrue(boosted.hpDamage < controlSameRoot.hpDamage)",
        "assertEquals(durableHero.physicalDefense, defended.finalHero.physicalDefense)",
        "assertEquals(durableHero.magicalResistance, defended.finalHero.magicalResistance)",
        "assertEquals(defended.transactionHash, VNextBattleResolverTransaction.transactionHash(defended))",
    ):
        check(f"integration {anchor[:43]}", anchor in resolver_tests, "covered")

    check("legacy resolveKill", "fun resolveKill(" in settlement, "present")
    check("legacy unconnected runtime", "VNextAfterIncomingPacketRuntimeP6f" not in settlement, "safe")
    check("legacy unconnected resolver", "VNextBattleResolverTransaction" not in settlement, "safe")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    app = tuple(debug[i] + release[i] for i in range(4))
    check("engine tests", engine == (641, 0, 0, 0), engine)
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
        "launchState=COLD", "coldTotalTimeMs=5547", "coldWaitTimeMs=6061",
        "topResumedActivity=com.nullplaying/.MainActivity", "freshRosterEmpty=true",
        "androidRuntimeFatalCount=0", "engineTests=641", "appTests=176", "totalTests=817",
        "testFailures=0", "lintErrors=0", "lintWarnings=22",
        "featureDefaultEnabled=false", "liveSettlementEnabled=false",
        "afterIncomingPacketDefinitionsEnabled=1", "totalPassiveDefinitionsEnabled=36",
        "remainingPassiveDefinitions=54", "afterIncomingPacketDefinitionsClassified=7",
        "afterIncomingPacketDefinitionsDeferred=6",
        "afterIncomingRulesVersion=aq.after-incoming-packet.p6f.v0.1",
        "holdTheLineLv100DefensePerStackBps=300", "holdTheLineStackCap=3",
        "holdTheLineFamilyDefenseCapBps=1000",
        "holdTheLineAffectsPhysicalDefense=true", "holdTheLineAffectsMagicalResistance=true",
        "missResetsConsecutiveHitStack=true", "zeroActualHpDamageResetsConsecutiveHitStack=true",
        "nonDirectPacketResetsConsecutiveHitStack=true",
        "packetLocalDefenseOnly=true", "baseDefensePersistsUnchanged=true",
        "prepareBeforePacketThenCommitOutcome=true",
        "afterIncomingReceiptReplayRejected=true", "afterIncomingStalePlanRejected=true",
        "afterIncomingLedgerInTransactionHash=true",
        "afterIncomingCommittedOutcomeCountRecorded=true",
        "finalConsecutiveDamagingDirectHitCountRecorded=true",
        "runtimeHandledPassivesFilteredFromAdapter=true", "visualAssetRequired=false",
        f"emulatorScreenshotSha256={EXPECTED_SCREEN_SHA}", f"emulatorUiDumpSha256={EXPECTED_UI_SHA}",
    )
    for field in audit_fields:
        check(f"audit {field.split('=')[0]}", field in audit, field)

    doc_anchors = (
        "PD 조건부 승인", "Passive 36종 구현", "미구현 54종", "actualHpDamage > 0",
        "첫 피격은 스택을 만드는 결과", "packet 계산용 임시 프레임",
        "사용자는 전투 중 버튼을 누르는 대신", "동일 incoming receipt ID",
        "SettlementEngine.resolveKill()", "새 화면 이미지가 필요하지 않다",
        "다음 P6g", "상처의 훈장",
    )
    for anchor in doc_anchors:
        check(f"document {anchor[:28]}", anchor in doc, "present")

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
    print(f"P6f PD verification: {passed}/{len(checks)} PASS ({percent:.1f}%)")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
