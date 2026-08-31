#!/usr/bin/env python3
"""PD audit for P6g damaging-hit to persistent POWER Active cost bridge."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6g-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6g-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6g-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6G_INCOMING_COST_BRIDGE_v0.1.md"
EXPECTED_APK_SHA = "27975c3e1a977dc56793963eeffc3576bf818ab255fa3e636220c7c1d3cedfd7"
EXPECTED_SCREEN_SHA = "24d5de95ab368d7f6ca25000c304a36d9034cd1475f7913575d0c8567294c678"
EXPECTED_UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
EXPECTED_APK_SIZE = 37_698_381
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterIncomingCostBridgeP6g.kt":
        "b23538ab10d14152399d9de438c71749d0bd2958e4534e2d164a7f69c4699276",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterIncomingCostBridgeP6gTest.kt":
        "95ae47d34b74dcc54bf89854f67ea079c2ae024c7231ea9198e854222779891d",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterIncomingPacketRuntimeP6f.kt":
        "664728b05b60d09efaf134f20d124957f4f047070cb3c94a05c847a0c877b068",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "18bb4040b02851d0be1ebf88b511c920a34a5ef9a92c42ac29bb06286b72f293",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "2c4289816fd2cafee62daf510584d5c610e929b68f7bf0a3bc5d3f1714b54a93",
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

    runtime = (ROOT / next(k for k in FREEZE if k.endswith("VNextAfterIncomingCostBridgeP6g.kt"))).read_text()
    tests = (ROOT / next(k for k in FREEZE if k.endswith("VNextAfterIncomingCostBridgeP6gTest.kt"))).read_text()
    resolver = (ROOT / next(k for k in FREEZE if k.endswith("VNextBattleResolverTransaction.kt"))).read_text()
    resolver_tests = (ROOT / next(k for k in FREEZE if k.endswith("VNextBattleResolverTransactionTest.kt"))).read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    audit = AUDIT.read_text()
    doc = DOC.read_text()

    runtime_anchors = {
        "rules": "aq.after-incoming-cost-bridge.p6g.v0.1",
        "content": "c95e1474b0006feb785729690527b56e187d4506f8f029b81998792e9c160c7a",
        "one definition": "V_NEXT_P6G_INCOMING_COST_DEFINITION_COUNT = 1",
        "ready hits": "V_NEXT_P6G_SCAR_MEDAL_READY_HITS = 2",
        "skill id": "aq.skill.warrior.p1.scarmedal",
        "power excludes": 'setOf("CONTROL", "DEFENSE", "HEAL")',
        "stale plan": "require(plan.ledgerBeforeHash == ledger.ledgerHash)",
        "damage nonnegative": "require(actualHpDamage >= 0)",
        "replay": "receiptId !in ledger.processedReceiptIds",
        "damaging hit": "scarMedal != null && hit && actualHpDamage > 0",
        "hit cap": "coerceAtMost(V_NEXT_P6G_SCAR_MEDAL_READY_HITS)",
        "no reset": "ledger.damagingHitCount",
        "outcome count": "committedIncomingOutcomeCount + 1",
        "power classifier": '"ATTACK" in roles && roles.none',
        "ready gate": "ledger.damagingHitCount >= V_NEXT_P6G_SCAR_MEDAL_READY_HITS && power",
        "anchor clamp": "resolvedAnchorValue.coerceIn(-600, -300)",
        "persistent decision": "fun decideCost(",
        "owner": 'it.ownerScope == "WARRIOR"',
        "pattern": 'it.pattern == "RESOURCE"',
        "growth": 'it.growthField == "resource_cost_modifier_bps"',
        "condition": 'it.conditionId == "damaging_hits_taken_gte_2"',
        "host": 'it.hostScope == "POWER_ACTIVE"',
        "shared group": 'it.stackGroup == "aq.stack.passive.resource_discount"',
        "shared cap": "it.stackCapBps == 1_500",
        "anchors": "it.anchorValues.first() == -300 && it.anchorValues.last() == -600",
        "tradeoff": "조건부 발동=damaging_hits_taken_gte_2·미충족 시 효과0",
        "event": "P5wPassiveEvent.AFTER_INCOMING_PACKET",
        "five deferred": "deferredAfterIncomingIds.size == 5",
        "p6f excluded": "VNextAfterIncomingPacketRuntimeP6f.supportedDefinitionIds()",
        "hash power": "power=ATTACK_AND_NOT_CONTROL_DEFENSE_HEAL",
        "hash persistent": "readyAt2:persistent",
        "live off": "val liveReady: Boolean get() = false",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime {name}", anchor in runtime, "bound")

    test_names = (
        "coverage_activates_scar_medal_and_leaves_five_after_incoming_contracts_deferred",
        "two_committed_enemy_hits_with_actual_hp_damage_arm_a_persistent_discount",
        "miss_and_zero_hp_damage_do_not_arm_or_reset_earned_hit_progress",
        "power_classifier_includes_pure_attacks_and_excludes_control_defense_and_heal",
        "skill_anchors_scale_the_persistent_power_discount_without_consuming_readiness",
        "unequipped_runtime_audits_outcomes_without_inventing_hit_progress_or_cost_effects",
        "stale_plans_duplicate_receipts_and_wrong_owner_fail_closed",
    )
    for name in test_names:
        check(f"unit {name[:50]}", name in tests, "covered")
    for anchor in (
        "assertEquals(2, ledger.damagingHitCount)",
        "assertEquals(-600, first.modifierBps)",
        "assertEquals(-600, second.modifierBps)",
        "assertEquals(3, ledger.committedIncomingOutcomeCount)",
        'active("aq.skill.warrior.w2.preparedsmash").isPowerActiveP6g()',
        'active("aq.skill.common.w2.recklesssmash").isPowerActiveP6g()',
        'active("aq.skill.warrior.a3.armorbreak").isPowerActiveP6g()',
        'active("aq.skill.warrior.a2.ironstance").isPowerActiveP6g()',
        'active("aq.skill.common.w5.firstaid").isPowerActiveP6g()',
        "mapOf(1 to -300, 25 to -375, 50 to -450, 75 to -525, 100 to -600)",
        "repeat(3)",
        "assertFailsWith<IllegalArgumentException>",
        'runtime.bind("ROGUE", listOf(passive(100)))',
    ):
        check(f"unit assertion {anchor[:42]}", anchor in tests, "covered")

    resolver_anchors = {
        "version": "aq.battle-resolver-transaction.p6g.v0.6",
        "bind failure": "PASSIVE_INCOMING_COST_BIND_REJECTED",
        "ledger failure": "PASSIVE_INCOMING_COST_LEDGER_REJECTED",
        "compile": "VNextAfterIncomingCostBridgeP6gCompiler.compile(registry)",
        "bind": "incomingCostRuntime.bind(",
        "initial ledger": "incomingCostSession.initialLedger()",
        "content": "incomingCostContentHash = V_NEXT_P6G_INCOMING_COST_CONTENT_HASH",
        "session": "incomingCostSessionHash = incomingCostSession.snapshotHash",
        "preview decision": "incomingCostSession.decideCost(",
        "preview combine": "incomingCostDecision.modifierBps + afterCostDecision.modifierBps",
        "actual combine": "incomingCostDecision.modifierBps + afterCostDecision.modifierBps",
        "discount count": "scarMedalDiscountedPowerActiveCount++",
        "prepare incoming": "incomingCostSession.prepareIncoming(incomingCostLedger)",
        "commit incoming": "incomingCostSession.commitIncoming(",
        "hit result": "hit = committedResult.hit",
        "actual damage": "actualHpDamage = committedResult.hpDamage",
        "ledger final": "finalIncomingCostLedgerHash = nextIncomingCostLedger.ledgerHash",
        "outcome count": "nextIncomingCostLedger.committedIncomingOutcomeCount",
        "hit count": "finalDamagingHitCount = nextIncomingCostLedger.damagingHitCount",
        "incoming detail": "incomingCostPlan.detail",
        "cost detail": "incomingCostDecision.detail",
        "transaction content": "val incomingCostContentHash: String",
        "transaction session": "val incomingCostSessionHash: String",
        "transaction initial": "val initialIncomingCostLedgerHash: String",
        "transaction final": "val finalIncomingCostLedgerHash: String",
        "transaction outcomes": "val incomingCostCommittedOutcomeCount: Int",
        "transaction hits": "val finalDamagingHitCount: Int",
        "transaction discounted": "val scarMedalDiscountedPowerActiveCount: Int",
        "hash content": "append(transaction.incomingCostContentHash)",
        "hash session": "append(transaction.incomingCostSessionHash)",
        "hash ledger": "append(transaction.finalIncomingCostLedgerHash)",
        "hash outcomes": "append(transaction.incomingCostCommittedOutcomeCount)",
        "hash hits": "append(transaction.finalDamagingHitCount)",
        "hash discounted": "append(transaction.scarMedalDiscountedPowerActiveCount)",
        "adapter filter": "VNextAfterIncomingCostBridgeP6g.supportedDefinitionIds()",
        "shared clamp": ").coerceIn(-V_NEXT_P5Z_SHARED_DISCOUNT_CAP_BPS, 0)",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver {name}", anchor in resolver, "bound")

    integration = "p6g scar medal discounts persistent power actives after two damaging enemy hits"
    check("resolver integration", integration in resolver_tests, "covered")
    for anchor in (
        'passiveIds = listOf("aq.skill.warrior.p1.scarmedal")',
        '"P6G_COST:power=true|modifier=-450" in it.detail',
        "assertEquals(V_NEXT_P6G_INCOMING_COST_CONTENT_HASH, defended.incomingCostContentHash)",
        "assertNotEquals(defended.initialIncomingCostLedgerHash, defended.finalIncomingCostLedgerHash)",
        "assertEquals(monsterOutcomes, defended.incomingCostCommittedOutcomeCount)",
        "assertEquals(2, defended.finalDamagingHitCount)",
        "assertTrue(defended.scarMedalDiscountedPowerActiveCount >= 2)",
        "assertTrue(committedCost(discounted) < committedCost(controlSameRoot))",
        "PASSIVE_INCOMING_COST_LEDGER_REJECTED",
        "assertEquals(defended.transactionHash, VNextBattleResolverTransaction.transactionHash(defended))",
    ):
        check(f"integration {anchor[:44]}", anchor in resolver_tests, "covered")

    check("legacy resolveKill", "fun resolveKill(" in settlement, "present")
    check("legacy unconnected runtime", "VNextAfterIncomingCostBridgeP6g" not in settlement, "safe")
    check("legacy unconnected resolver", "VNextBattleResolverTransaction" not in settlement, "safe")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    app = tuple(debug[i] + release[i] for i in range(4))
    check("engine tests", engine == (649, 0, 0, 0), engine)
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
        "launchState=COLD", "coldTotalTimeMs=4843", "coldWaitTimeMs=4845",
        "topResumedActivity=com.nullplaying/.MainActivity", "freshRosterEmpty=true",
        "androidRuntimeFatalCount=0", "engineTests=649", "appTests=176", "totalTests=825",
        "testFailures=0", "lintErrors=0", "lintWarnings=22",
        "featureDefaultEnabled=false", "liveSettlementEnabled=false",
        "incomingCostBridgeDefinitionsEnabled=1", "totalPassiveDefinitionsEnabled=37",
        "remainingPassiveDefinitions=53", "afterIncomingPacketDefinitionsEnabled=2",
        "afterIncomingPacketDefinitionsDeferred=5",
        "incomingCostRulesVersion=aq.after-incoming-cost-bridge.p6g.v0.1",
        "scarMedalReadyDamagingHits=2", "scarMedalLv100CostModifierBps=-600",
        "scarMedalReadinessPersistent=true", "scarMedalReadinessConsumedByPowerActive=false",
        "scarMedalRequiresActualHpDamage=true", "scarMedalMissDoesNotCount=true",
        "scarMedalZeroHpDamageDoesNotCount=true", "scarMedalNonDamageDoesNotResetProgress=true",
        "powerRoleRequiresAttack=true", "powerRoleExcludesControl=true",
        "powerRoleExcludesDefense=true", "powerRoleExcludesHeal=true",
        "powerRoleAllowsStatus=true", "powerRoleAllowsDelayed=true", "powerRoleAllowsResource=true",
        "sharedPassiveDiscountCapBps=1500", "costPreviewAndCommitUseSameDecision=true",
        "incomingCostReceiptReplayRejected=true", "incomingCostStalePlanRejected=true",
        "incomingCostLedgerInTransactionHash=true",
        "incomingCostCommittedOutcomeCountRecorded=true", "finalDamagingHitCountRecorded=true",
        "scarMedalDiscountedPowerActiveCountRecorded=true",
        "runtimeHandledPassivesFilteredFromAdapter=true", "visualAssetRequired=false",
        f"emulatorScreenshotSha256={EXPECTED_SCREEN_SHA}", f"emulatorUiDumpSha256={EXPECTED_UI_SHA}",
    )
    for field in audit_fields:
        check(f"audit {field.split('=')[0]}", field in audit, field)

    doc_anchors = (
        "PD 조건부 승인", "Passive 37종 구현", "미구현 53종", "POWER Active 분류",
        "ATTACK 역할 보유", "CONTROL 역할 없음", "actualHpDamage>0",
        "준비도를 소비하지 않는다", "공유 상한 -1,500bps", "선택-확정 드리프트가 없다",
        "Passive 3슬롯 편성", "SettlementEngine.resolveKill()", "새 화면 이미지가 필요하지 않다",
        "P6h", "고통 공감",
    )
    for anchor in doc_anchors:
        check(f"document {anchor[:30]}", anchor in doc, "present")

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
    print(f"P6g PD verification: {passed}/{len(checks)} PASS ({percent:.1f}%)")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
