#!/usr/bin/env python3
"""Fail-closed production audit for P6t Elemental Residue.

The default audit is read-only and never invokes Gradle.  It binds the frozen
Kotlin sources and tests, the P6t balance verifier and canonical hash, persisted
test/lint reports, the debug APK, and copied emulator evidence.  Live ADB state
is checked only when --with-emulator is explicitly requested.
"""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import importlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Callable


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextElementalResidueRuntimeP6t.kt"
RUNTIME_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextElementalResidueRuntimeP6tTest.kt"
RESOLVER = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt"
RESOLVER_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt"
BALANCE = ROOT / "tools/production_p6t_elemental_residue_v0_1_review.py"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6T_ELEMENTAL_RESIDUE_v0.1.md"
AUDIT = ROOT / "artifacts/audit/production-p6t-emulator-verification-v0.1.txt"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
SCREEN = ROOT / "artifacts/audit/production-p6t-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6t-emulator-ui-v0.1.xml"
SOURCE_SCREEN = ROOT / "artifacts/p6t-emulator/alarmquest-p6t.png"
SOURCE_UI = ROOT / "artifacts/p6t-emulator/alarmquest-p6t.xml"
REGISTRY_RESOURCE = (
    ROOT / "game-engine/src/main/resources/com/alarmquest/engine/vnext/skill_registry_242_v0_3.json.gz.b64"
)
P6C = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterOutgoingPacketRuntimeP6c.kt"
P6Q = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextOathEchoRuntimeP6q.kt"
P6R = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextShieldBreathRuntimeP6r.kt"
P6S = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextHeardPrayerRuntimeP6s.kt"
AUDIT_TOOL = Path(__file__).resolve()

P6T_RULES = "aq.elemental-residue.p6t.v0.1"
P6T_CONTENT = "d72837046be0bf48b5375ef97619551f3c549a4c75f07988dc77359c68675abf"
RESOLVER_RULES = "aq.battle-resolver-transaction.p6t.v0.20"
REGISTRY_CONTENT = "cda98479494d5d2b3d1279e695c26f923ce46249c8835e3e76996fccd67fbdca"
BALANCE_CANONICAL = "a4fc88a1c2e67b8074174de2f0210ce205f09d6be0cb17037241e2371a1c9196"
APK_SIZE = 37_400_522
APK_SHA = "5829b0b852e0d55b162374701dd4a20ec5b7ff93c537fc9e4429f1c8a803f612"
SCREEN_SHA = "54d52d7c3118178bd394aab1587fd9249603d2ebf42eac94a500108c95b56d91"
UI_SHA = "8d2715bfaeb699c6c90eef0c84f3980e74d30261e84739737ace14d1aa89c844"

FREEZE = {
    RUNTIME: "0ae43a776e2125f261189049a7217550fd7ab79947a5e6784fda5e369f801d94",
    RUNTIME_TEST: "0471332d5aa9670d5c7587c1d747440969d8357acfe2dbc4d86a2330c9683bcc",
    RESOLVER: "b1538eccb5c438050c883c0bbe6aa6bdcce65f89018e1492baf51221824402c8",
    RESOLVER_TEST: "1af6ba4e9a3361539bd7d87b049c22515e2488e2af4a7f675102c77e550e2d48",
    BALANCE: "71639a20c3ac42e5d51d7289847785a7f58a1e834cf699745ea0ba6019a39452",
    P6C: "9f29eae8cbe3947512ca36aa24a44dba005a189f3a338baf2350c0281d3b1b25",
    P6Q: "f171cd4f4e99e7c8f32799ae118c93fbb18696aa5b8426b26ff6fa3bf1c7623f",
    P6R: "d362787e24efeee9d9d57dd5238a86124223069394216cb2aff6d41f643688e8",
    P6S: "b7fd60a959f90ea6bc1aea791c9cb95a212c842d695f155111114c0558c92c39",
}

ELEMENTAL_RESIDUE = "aq.skill.common.w8.elementalresidue"
EXPECTED_DEFERRED = frozenset(
    {
        "aq.skill.cleric.p2.faithecho",
        "aq.skill.mage.w4.overload",
        "aq.skill.paladin.w5.mercylimit",
        "aq.skill.warrior.w2.painconversion",
        "aq.skill.world.w7.orememory",
        "aq.skill.world.w8.desertadaptation",
        "aq.skill.world.w8.wellecho",
    }
)
EXPECTED_HANDLED_AFTER_OUTGOING = frozenset(
    {
        "aq.skill.cleric.p1.heardprayer",
        ELEMENTAL_RESIDUE,
        "aq.skill.common.w8.failureanalysis",
        "aq.skill.paladin.p3.othecho",
        "aq.skill.ranger.p2.distancesense",
        "aq.skill.rogue.p3.failurestudy",
        "aq.skill.rogue.w6.vanishedtrace",
        "aq.skill.rogue.w8.greedrhythm",
        "aq.skill.warrior.p3.shieldbreath",
    }
)
AFTER_OUTGOING_CONDITIONS = frozenset(
    {
        "actual_overheal_gt_zero",
        "after_consuming_chill",
        "after_consuming_hostile_status",
        "after_heal_active",
        "after_overheal",
        "after_own_miss",
        "after_shield_active",
        "after_unshieldable_self_damage",
        "burn_damage_or_burn_action",
        "burn_or_shock_effect",
        "consecutive_hits_gte_2",
        "consecutive_own_critical",
        "first_own_miss_in_encounter",
        "same_armored_target_direct_hit",
    }
)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_json_hash(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def load_registry() -> dict:
    encoded = "".join(REGISTRY_RESOURCE.read_text(encoding="utf-8").split())
    return json.loads(gzip.decompress(base64.b64decode(encoded)))


def extract_string_constant(source: str, name: str) -> str:
    match = re.search(rf"\b{re.escape(name)}(?:\s*:\s*String)?\s*=\s*\"([^\"]+)\"", source)
    if match is None:
        raise ValueError(f"missing constant {name}")
    return match.group(1)


def totals(path: Path) -> tuple[int, int, int, int]:
    values = [0, 0, 0, 0]
    for report in path.glob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            values[index] += int(root.attrib.get(key, "0"))
    return tuple(values)


def parsed_audit() -> dict[str, str]:
    result: dict[str, str] = {}
    for line_number, raw in enumerate(AUDIT.read_text(encoding="utf-8").splitlines(), 1):
        if not raw:
            continue
        if "=" not in raw:
            raise ValueError(f"audit line {line_number} is not key=value")
        key, value = raw.split("=", 1)
        if not key or key.strip() != key or key in result:
            raise ValueError(f"invalid or duplicate audit key at line {line_number}: {key}")
        result[key] = value
    return result


def adb(*args: str) -> str:
    result = subprocess.run(
        ("adb", "-s", "emulator-5554", *args),
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    return (result.stdout + result.stderr).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: object) -> None:
        checks.append((name, ok, str(detail)))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")

    def call(name: str, fn: Callable[[], object]) -> None:
        try:
            detail = fn()
            check(name, True, "ok" if detail is None else detail)
        except Exception as error:  # fail closed with the original exception evidence
            check(name, False, f"{type(error).__name__}:{error}")

    required = [
        RUNTIME,
        RUNTIME_TEST,
        RESOLVER,
        RESOLVER_TEST,
        BALANCE,
        DOC,
        AUDIT,
        APK,
        SCREEN,
        UI,
        SOURCE_SCREEN,
        SOURCE_UI,
        REGISTRY_RESOURCE,
        P6C,
        P6Q,
        P6R,
        P6S,
        ROOT / "game-engine/build/test-results/test",
        ROOT / "app/build/test-results/testDebugUnitTest",
        ROOT / "app/build/test-results/testReleaseUnitTest",
        ROOT / "app/build/reports/lint-results-debug.xml",
    ]
    for path in required:
        check(f"file:{path.relative_to(ROOT)}", path.exists(), path.exists())
    if not all(path.exists() for path in required):
        return 1

    runtime = RUNTIME.read_text(encoding="utf-8")
    runtime_test = RUNTIME_TEST.read_text(encoding="utf-8")
    resolver = RESOLVER.read_text(encoding="utf-8")
    resolver_test = RESOLVER_TEST.read_text(encoding="utf-8")
    balance_source = BALANCE.read_text(encoding="utf-8")
    doc = DOC.read_text(encoding="utf-8")
    audit = parsed_audit()

    for path, expected in FREEZE.items():
        actual = sha(path)
        check(f"freeze:{path.name}", actual == expected, actual)

    check(
        "runtime:rules",
        extract_string_constant(runtime, "V_NEXT_P6T_ELEMENTAL_RESIDUE_RULES_VERSION") == P6T_RULES,
        P6T_RULES,
    )
    check(
        "runtime:content",
        extract_string_constant(runtime, "V_NEXT_P6T_ELEMENTAL_RESIDUE_CONTENT_HASH") == P6T_CONTENT,
        P6T_CONTENT,
    )
    runtime_anchors = {
        "definition one": "V_NEXT_P6T_ELEMENTAL_RESIDUE_DEFINITION_COUNT = 1",
        "implemented 54": "V_NEXT_P6T_IMPLEMENTED_PASSIVE_DEFINITION_COUNT = 54",
        "total 90": "V_NEXT_P6T_TOTAL_PASSIVE_DEFINITION_COUNT = 90",
        "deferred seven": "V_NEXT_P6T_DEFERRED_AFTER_OUTGOING_COUNT = 7",
        "p5h ten": "p5hConsumerActiveDefinitions == 10",
        "feature false": "val liveReady: Boolean get() = false",
        "encounter reset": 'return P6tElementalResidueBindResult(false, detail = "ENCOUNTER_RESIDUE_NOT_RESET")',
        "duplicate attach": '"P6T_ALREADY_ATTACHED"',
        "duplicate precondition": "elementalResiduePlanHash.isBlank()",
        "single clamp": "(V_NEXT_P6T_ACTION_ADD_CAP_BPS - actionBefore).coerceAtLeast(0)",
        "applied ids positive only": "listOfNotNull(ELEMENTAL_RESIDUE.takeIf { applied > 0 })",
        "applied count positive only":
            "if (p5hReceipt.residueAppliedBps > 0) 1 else 0",
        "strict hostile arm": "val hostileArmTriggered = passivePlan != null && receipt.hostileConsumeCommitted",
        "strict postcondition": "val expectedResidueAfter = when",
        "strict arm branch": "hostileArmTriggered -> residueElementForStatusP6t(receipt.primaryConsumedTag)",
        "strict consume branch": "plan.consumesExistingResidue -> null",
        "strict retain branch": "else -> ledger.residueElement",
        "strict rejection": 'return reject("TOKEN_POSTCONDITION_MISMATCH")',
        "non owner effect zero": 'return reject("NON_OWNER_MODIFIER_MISMATCH")',
        "passive absent no arm": "passivePlan != null && receipt.hostileConsumeCommitted",
        "old then new": "OLD_CONSUME_THEN_NEW_ARM",
        "shared action cap": "ACTION_ADD_COMBINED_THEN_CLAMP1500",
        "deferred formula": "deferredAfterOutgoingIds = catalogIds.minus(handled).sorted()",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime:{name}", anchor in runtime, anchor)

    check(
        "resolver:rules",
        extract_string_constant(resolver, "V_NEXT_BATTLE_RESOLVER_RULES_VERSION") == RESOLVER_RULES,
        RESOLVER_RULES,
    )
    resolver_anchors = {
        "compile": "VNextElementalResidueRuntimeP6tCompiler.compile(registry)",
        "bind": "elementalResidueRuntime.bind(",
        "prepare": "elementalResidueSession.prepareActive(",
        "typed receipt": "mutationReceipt = dispatched.statusMutationReceipt",
        "commit": "elementalResidueSession.commitActive(",
        "record": "recordElementalResidue(elementalResidueCommit.ledger)",
        "failure": "PASSIVE_ELEMENTAL_RESIDUE_LEDGER_REJECTED",
        "ownership matrix": "internal fun dispatcherOwnedPassivePlansP6t(",
        "well deferred effect zero": 'it.definitionId == "aq.skill.world.w8.wellecho"',
        "transaction content hash": "append(transaction.elementalResidueContentHash)",
        "transaction ledger hash": "append(transaction.finalElementalResidueLedgerHash)",
        "feature false": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "live false": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)
    check(
        "resolver:single withP6t",
        resolver.count(".withP6t(elementalResiduePlan)") == 1,
        resolver.count(".withP6t(elementalResiduePlan)"),
    )
    handled_start = resolver.index("private val resolverHandledPassiveIds")
    ownership_start = resolver.index("internal fun dispatcherOwnedPassivePlansP6t", handled_start)
    resolver_handled_block = resolver[handled_start:ownership_start]
    check(
        "resolver:not resolver handled",
        ELEMENTAL_RESIDUE not in resolver_handled_block,
        "dispatcher-only",
    )

    registry = load_registry()
    check("registry:canonical", canonical_json_hash(registry) == REGISTRY_CONTENT, canonical_json_hash(registry))
    check("registry:passive total", len(registry["passives"]) == 90, len(registry["passives"]))
    residue_rows = [row for row in registry["passives"] if row["definitionId"] == ELEMENTAL_RESIDUE]
    check("registry:residue exactly one", len(residue_rows) == 1, len(residue_rows))
    if len(residue_rows) == 1:
        residue = residue_rows[0]
        expected_residue = {
            "ownerScope": "ALL",
            "pattern": "CONSUME_TO_DIFFERENT_ELEMENT",
            "growthField": "different_element_attack_add_bps",
            "conditionId": "after_consuming_hostile_status",
            "hostScope": "SELF_OR_HOST_ACTION",
            "stackGroup": "aq.stack.passive.action_add",
            "stackPolicy": "ADD_THEN_CLAMP",
            "stackCapBps": 1_500,
            "anchorValues": [100, 200, 300, 400, 500],
        }
        for key, expected in expected_residue.items():
            check(f"registry:residue:{key}", residue.get(key) == expected, residue.get(key))

    after_outgoing = {
        row["definitionId"]
        for row in registry["passives"]
        if row["conditionId"] in AFTER_OUTGOING_CONDITIONS
    }
    check("coverage:after outgoing exact union", after_outgoing == EXPECTED_HANDLED_AFTER_OUTGOING | EXPECTED_DEFERRED,
          ",".join(sorted(after_outgoing)))
    observed_deferred = after_outgoing - EXPECTED_HANDLED_AFTER_OUTGOING
    check("coverage:deferred exact stable ids", observed_deferred == EXPECTED_DEFERRED,
          ",".join(sorted(observed_deferred)))
    check("coverage:deferred count", len(observed_deferred) == 7, len(observed_deferred))
    implementation_sources = "\n".join(
        path.read_text(encoding="utf-8") for path in (P6C, P6Q, P6R, P6S, RUNTIME)
    )
    for definition_id in sorted(EXPECTED_HANDLED_AFTER_OUTGOING):
        check(f"coverage:handled:{definition_id}", definition_id in implementation_sources, definition_id)
    for definition_id in sorted(EXPECTED_DEFERRED):
        check(f"coverage:deferred-doc:{definition_id}", definition_id in doc, definition_id)

    unit_anchors = {
        "coverage": "coverage_hash_ownership_and_encounter_reset_are_closed",
        "hostile arm": "hostile_commit_arms_but_self_fallback_miss_resource_and_replay_do_not_arm",
        "different miss": "different_element_miss_consumes_while_same_and_physical_actions_preserve_the_token",
        "cap nominal applied": "shared_action_cap_records_nominal_500_applied_zero_and_still_consumes",
        "applied zero count": "assertEquals(0, capped.ledger.residueAppliedCount)",
        "applied id absent": "assertTrue(ELEMENTAL_RESIDUE !in capped.modifier.appliedDefinitionIds)",
        "duplicate rejection": "assertFailsWith<IllegalArgumentException>",
        "old consume new arm": "old_token_consumes_before_burn_chill_primary_rearms_fire_and_multi_packet_arms_once",
        "none token consume": '"p6t:none:consume:all-fire"',
        "none nominal 500": "assertEquals(500, noneConsumedByAllFire.plan.elementalResidueNominalBps)",
        "audit fail closed": "non_p5h_commit_is_effect_zero_and_forged_stale_replay_evidence_leaves_ledger_unchanged",
        "equal sum": "equalSumForgery",
        "stale": "acceptedBeforeStale",
        "replay": "acceptedBeforeReplay",
    }
    for name, anchor in unit_anchors.items():
        check(f"unit:{name}", anchor in runtime_test, anchor)

    integration_anchors = {
        "shock to fire":
            "p6t production shock consume arms lightning and the next all fire host spends it once",
        "non p5h zero":
            "p6t production non p5h active commits with effect zero instead of adapter rejection",
        "six classes":
            "p6t production arm and different element consume are reachable for all six classes",
        "warrior": 'ClassCase("WARRIOR"',
        "rogue": 'ClassCase("ROGUE"',
        "ranger": 'ClassCase("RANGER"',
        "mage": 'ClassCase("MAGE"',
        "cleric": 'ClassCase("CLERIC"',
        "paladin": '"PALADIN",',
        "production dispatcher": "VNextCompositeSemanticDispatcherP5vCompiler.compile(registry)",
        "applied positive": "assertTrue(result.elementalResidueAppliedCount >= 1",
        "transaction rehash": "VNextBattleResolverTransaction.transactionHash(result)",
    }
    for name, anchor in integration_anchors.items():
        check(f"integration:{name}", anchor in resolver_test, anchor)

    sys.path.insert(0, str(ROOT / "tools"))
    balance_module = None
    try:
        balance_module = importlib.import_module("production_p6t_elemental_residue_v0_1_review")
        check("balance:import", True, BALANCE.name)
    except Exception as error:
        check("balance:import", False, f"{type(error).__name__}:{error}")
    if balance_module is not None:
        check("balance:canonical", balance_module.canonical_hash() == BALANCE_CANONICAL,
              balance_module.canonical_hash())
        for name, fn in balance_module.STATIC_CHECKS:
            call(f"balance:static:{name}", fn)
        passive_off = balance_module.expected_residue_postcondition(
            token_before=None,
            passive_equipped=False,
            hostile_consume_committed=True,
            primary_consumed_tag="BURN",
        )
        check("balance:passive off hostile no arm", passive_off is None, passive_off)
        passive_on = balance_module.expected_residue_postcondition(
            token_before=None,
            passive_equipped=True,
            hostile_consume_committed=True,
            primary_consumed_tag="BURN",
        )
        check("balance:passive on hostile arms fire", passive_on == "FIRE", passive_on)
    check("balance:applied count hardening", '"appliedCount": "INCREMENT_ONLY_WHEN_RESIDUE_APPLIED_BPS_GT_0"' in balance_source,
          "positive only")
    check("balance:duplicate hardening", '"duplicateAttach": "P6T_ALREADY_ATTACHED_REJECTED"' in balance_source,
          "duplicate rejected")
    check("balance:postcondition hardening", '"tokenPostcondition": "HOSTILE_ARM_ELSE_CONSUME_ELSE_RETAIN"' in balance_source,
          "strict postcondition")
    check("balance:passive off hardening", '"passiveOffHostileConsume": "COMMIT_ALLOWED_WITHOUT_TOKEN_ARM"' in balance_source,
          "passive off no arm")

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    total = tuple(sum(values) for values in zip(engine, debug, release))
    check("reports:engine", engine == (792, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (968, 0, 0, 0), total)
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    lint_errors = sum(node.attrib.get("severity") == "Error" for node in lint.findall("issue"))
    lint_warnings = sum(node.attrib.get("severity") == "Warning" for node in lint.findall("issue"))
    check("reports:lint errors", lint_errors == 0, lint_errors)
    check("reports:lint warnings", lint_warnings == 22, lint_warnings)

    check("artifact:apk size", APK.stat().st_size == APK_SIZE, APK.stat().st_size)
    check("artifact:apk hash", sha(APK) == APK_SHA, sha(APK))
    check("artifact:screen hash", sha(SCREEN) == SCREEN_SHA, sha(SCREEN))
    check("artifact:ui hash", sha(UI) == UI_SHA, sha(UI))
    check("artifact:source screen hash", sha(SOURCE_SCREEN) == SCREEN_SHA, sha(SOURCE_SCREEN))
    check("artifact:source ui hash", sha(SOURCE_UI) == UI_SHA, sha(SOURCE_UI))
    check("artifact:screen copied exactly", SCREEN.read_bytes() == SOURCE_SCREEN.read_bytes(), SCREEN.stat().st_size)
    check("artifact:ui copied exactly", UI.read_bytes() == SOURCE_UI.read_bytes(), UI.stat().st_size)
    check("artifact:png signature", SCREEN.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), SCREEN.stat().st_size)
    ui_root = ET.parse(UI).getroot()
    ui_text = UI.read_text(encoding="utf-8")
    check("artifact:ui hierarchy", ui_root.tag == "hierarchy", ui_root.tag)
    check("artifact:ui package", 'package="com.alarmquest"' in ui_text, "com.alarmquest")
    check("artifact:empty roster", "아직 캐릭터가 없습니다" in ui_text, "empty roster")
    check("artifact:create character", "새 캐릭터" in ui_text, "create")

    deferred_csv = ",".join(sorted(EXPECTED_DEFERRED))
    expected_audit = {
        "serial": "emulator-5554",
        "avdName": "alarmquest-qa",
        "androidRelease": "15",
        "apiLevel": "35",
        "packageName": "com.alarmquest",
        "versionName": "0.1.0",
        "versionCode": "1",
        "targetSdk": "36",
        "apkSizeBytes": str(APK_SIZE),
        "apkSha256": APK_SHA,
        "installResult": "Success",
        "pmClearResult": "Success",
        "launchState": "COLD",
        "coldLaunchTotalTimeMs": "5563",
        "coldLaunchWaitTimeMs": "5565",
        "warmLaunchState": "WARM_BRING_TO_FRONT",
        "warmBringToFrontWaitTimeMs": "199",
        "topResumedActivity": "com.alarmquest/.MainActivity",
        "warmTopResumedActivity": "com.alarmquest/.MainActivity",
        "freshRosterEmpty": "true",
        "androidRuntimeFatalCount": "0",
        "androidRuntimeErrorCount": "0",
        "choreographerSkippedFrameWarningObserved": "true",
        "emulatorScreenshotSha256": SCREEN_SHA,
        "emulatorUiDumpSha256": UI_SHA,
        "engineTests": "792",
        "appDebugTests": "88",
        "appReleaseTests": "88",
        "totalTests": "968",
        "testFailures": "0",
        "testErrors": "0",
        "testSkipped": "0",
        "lintErrors": "0",
        "lintWarnings": "22",
        "featureDefaultEnabled": "false",
        "liveSettlementEnabled": "false",
        "previousPassiveDefinitionsEnabled": "53",
        "elementalResidueDefinitionsEnabled": "1",
        "totalPassiveDefinitionsEnabled": "54",
        "remainingPassiveDefinitions": "36",
        "p6tRulesVersion": P6T_RULES,
        "p6tContentHash": P6T_CONTENT,
        "battleResolverRulesVersion": RESOLVER_RULES,
        "p5hConsumerActiveCount": "10",
        "deferredAfterOutgoingCount": "7",
        "deferredAfterOutgoingIds": deferred_csv,
        "p6tRuntimeSha256": FREEZE[RUNTIME],
        "p6tUnitTestSha256": FREEZE[RUNTIME_TEST],
        "battleResolverSha256": FREEZE[RESOLVER],
        "battleResolverTestSha256": FREEZE[RESOLVER_TEST],
        "balanceVerifierSha256": FREEZE[BALANCE],
        "balanceCanonicalSha256": BALANCE_CANONICAL,
        "duplicateWithP6tRejected": "true",
        "residueAppliedCountPositiveOnly": "true",
        "strictTokenPostcondition": "true",
        "passiveOffHostileConsumeCommitsWithoutArm": "true",
        "productionShockToDifferentElement": "true",
        "productionNonP5hEffectZero": "true",
        "sixClassProductionIntegration": "true",
        "balancePdSeeds": "200",
        "balancePdResult": "PASS_9_OF_9",
        "visualAssetRequired": "false",
        "designTeamImageRequested": "false",
        "systemAuditP0Count": "0",
        "systemAuditP1Count": "0",
        "pdFinalDecision": "GO_ENGINE_FOUNDATION_FEATURE_LIVE_OFF",
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected,
              f"observed={audit.get(key)} expected={expected}")
    audit_tool_sha = sha(AUDIT_TOOL)
    check("audit:auditToolSha256", audit.get("auditToolSha256") == audit_tool_sha,
          f"observed={audit.get('auditToolSha256')} expected={audit_tool_sha}")

    doc_anchors = {
        "implemented": "구현·검증 상태: **IMPLEMENTED / VERIFIED**",
        "54 enabled": "누적 실행 Passive: **54/90**",
        "36 off": "잔여 Passive: **36/90 OFF**",
        "feature off": "feature/live settlement: **OFF / NO-GO 유지**",
        "content": P6T_CONTENT,
        "runtime sha": FREEZE[RUNTIME],
        "unit sha": FREEZE[RUNTIME_TEST],
        "resolver sha": FREEZE[RESOLVER],
        "resolver test sha": FREEZE[RESOLVER_TEST],
        "balance sha": FREEZE[BALANCE],
        "balance canonical": BALANCE_CANONICAL,
        "apk sha": APK_SHA,
        "screen sha": SCREEN_SHA,
        "ui sha": UI_SHA,
        "tests": "792 + 88 + 88 = 968",
        "lint": "0 errors / 22 warnings",
        "six class": "6직업 production arm→consume",
        "duplicate": "P6T_ALREADY_ATTACHED",
        "positive applied": "residueAppliedBps > 0",
        "strict postcondition": "HOSTILE_ARM_ELSE_CONSUME_ELSE_RETAIN",
        "passive off": "COMMIT_ALLOWED_WITHOUT_TOKEN_ARM",
        "cold": "5,563ms",
        "warm": "199ms",
        "audit path": "production-p6t-emulator-verification-v0.1.txt",
        "no design": "디자인팀 요청은 하지 않았다",
        "final go": "PD 최종 구현 승인",
    }
    for name, anchor in doc_anchors.items():
        check(f"doc:{name}", anchor in doc, anchor)
    check("doc:audit tool sha", audit_tool_sha in doc, audit_tool_sha)
    check("doc:no pending", "PENDING" not in doc, "closed")

    static_expected = len(checks) + 4
    emulator_expected = 7
    combined_expected = static_expected + emulator_expected
    check("audit:verifierStaticChecks", audit.get("verifierStaticChecks") == str(static_expected),
          f"observed={audit.get('verifierStaticChecks')} expected={static_expected}")
    check("audit:verifierEmulatorChecks", audit.get("verifierEmulatorChecks") == str(emulator_expected),
          f"observed={audit.get('verifierEmulatorChecks')} expected={emulator_expected}")
    check("audit:verifierCombinedChecks", audit.get("verifierCombinedChecks") == str(combined_expected),
          f"observed={audit.get('verifierCombinedChecks')} expected={combined_expected}")
    expected_static_result = f"PASS_{static_expected}_OF_{static_expected}"
    check("audit:verifierStaticResult", audit.get("verifierStaticResult") == expected_static_result,
          f"observed={audit.get('verifierStaticResult')} expected={expected_static_result}")
    if len(checks) != static_expected:
        raise AssertionError((len(checks), static_expected))

    if args.with_emulator:
        devices = subprocess.run(("adb", "devices"), text=True, capture_output=True, check=False).stdout
        package = adb("shell", "dumpsys", "package", "com.alarmquest")
        activity = adb("shell", "dumpsys", "activity", "activities")
        crash = adb("logcat", "-d", "-b", "crash")
        check("emulator:device", "emulator-5554" in devices, "emulator-5554")
        check("emulator:version", "versionName=0.1.0" in package, "0.1.0")
        check("emulator:target", "targetSdk=36" in package, "36")
        check("emulator:focus", "com.alarmquest/.MainActivity" in activity, "MainActivity")
        check("emulator:android", adb("shell", "getprop", "ro.build.version.release") == "15", "15")
        check("emulator:api", adb("shell", "getprop", "ro.build.version.sdk") == "35", "35")
        check("emulator:crash", "FATAL EXCEPTION" not in crash and "AndroidRuntime" not in crash, "0")

    failed = [name for name, ok, _ in checks if not ok]
    print(f"RESULT={'PASS' if not failed else 'FAIL'} {len(checks) - len(failed)}/{len(checks)}")
    if failed:
        print("FAILED=" + ",".join(failed))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
