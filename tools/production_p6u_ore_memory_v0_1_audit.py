#!/usr/bin/env python3
"""Fail-closed production audit for P6u Ore Memory.

The default mode is read-only and binds frozen source, persisted test/lint
reports, balance evidence, APK, and emulator artifacts. Live ADB state is
checked only with --with-emulator.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextOreMemoryRuntimeP6u.kt"
RUNTIME_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextOreMemoryRuntimeP6uTest.kt"
RESOLVER = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt"
RESOLVER_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt"
BALANCE = ROOT / "tools/production_p6u_ore_memory_v0_1_review.py"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6U_ORE_MEMORY_v0.1.md"
AUDIT = ROOT / "artifacts/audit/production-p6u-emulator-verification-v0.1.txt"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
SCREEN = ROOT / "artifacts/audit/production-p6u-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6u-emulator-ui-v0.1.xml"
SOURCE_SCREEN = ROOT / "artifacts/p6u-emulator/alarmquest-p6u.png"
SOURCE_UI = ROOT / "artifacts/p6u-emulator/alarmquest-p6u.xml"
AUDIT_TOOL = Path(__file__).resolve()

P6U_RULES = "aq.ore-memory.p6u.v0.1"
P6U_CONTENT = "dcb4cc93f92e0612bd7bf09e59fd62fa861980f1a130dca53e52765467f774ab"
RESOLVER_RULES = "aq.battle-resolver-transaction.p6u.v0.21"
BALANCE_CANONICAL = "99bb79533656123391d579ffe5570b6bec882cd3db56f59b0a69cd9433e03acc"
FREEZE = {
    RUNTIME: "191f864378372bb06d6e479039b6e7ad912c8cac02c8136f577b936b1b726c89",
    RUNTIME_TEST: "49dfb74792242edb4128c637163325c76af5c65278b7926cf1b04bc320523268",
    RESOLVER: "5aedfc5df626fa6d7771fb90808e34b1375d0fc4d2e6b76bafae039f0a242cab",
    RESOLVER_TEST: "99f4a12680bce09d6fcf7a7b24d9e8b1988a273af877918ecc10177b5e34195f",
    BALANCE: "c5017e825bd2962d068c6dfaa96bffb88b90c220d0411605588ed0cd7d918a08",
}
APK_SIZE = 38_335_612
APK_SHA = "04dd4d5eaa2034ca41309f68c45538114e0d739fbf2eca1a6dcf2b33e446fc66"
SCREEN_SHA = "4b4e95bc31d023d5d2903edd5c86e4875d66a34a70cde01a78813cb25584bc5d"
UI_SHA = "8d2715bfaeb699c6c90eef0c84f3980e74d30261e84739737ace14d1aa89c844"
EXPECTED_DEFERRED = {
    "aq.skill.cleric.p2.faithecho",
    "aq.skill.mage.w4.overload",
    "aq.skill.paladin.w5.mercylimit",
    "aq.skill.warrior.w2.painconversion",
    "aq.skill.world.w8.desertadaptation",
    "aq.skill.world.w8.wellecho",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def extract_string(source: str, name: str) -> str:
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
            raise ValueError(f"invalid or duplicate key at line {line_number}: {key}")
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

    required = [
        RUNTIME, RUNTIME_TEST, RESOLVER, RESOLVER_TEST, BALANCE, DOC, AUDIT,
        APK, SCREEN, UI, SOURCE_SCREEN, SOURCE_UI,
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
    doc = DOC.read_text(encoding="utf-8")
    audit = parsed_audit()

    for path, expected in FREEZE.items():
        check(f"freeze:{path.name}", sha(path) == expected, sha(path))
    check("runtime:rules", extract_string(runtime, "V_NEXT_P6U_ORE_MEMORY_RULES_VERSION") == P6U_RULES, P6U_RULES)
    check("runtime:content", extract_string(runtime, "V_NEXT_P6U_ORE_MEMORY_CONTENT_HASH") == P6U_CONTENT, P6U_CONTENT)
    check("resolver:rules", extract_string(resolver, "V_NEXT_BATTLE_RESOLVER_RULES_VERSION") == RESOLVER_RULES, RESOLVER_RULES)

    runtime_anchors = {
        "55 of 90": "V_NEXT_P6U_IMPLEMENTED_PASSIVE_DEFINITION_COUNT = 55",
        "off 35": "V_NEXT_P6U_OFF_PASSIVE_DEFINITION_COUNT = 35",
        "handled 10": "V_NEXT_P6U_HANDLED_AFTER_OUTGOING_COUNT = 10",
        "deferred 6": "V_NEXT_P6U_DEFERRED_AFTER_OUTGOING_COUNT = 6",
        "stack 3": "V_NEXT_P6U_STACK_COUNT_CAP = 3",
        "shred 1500": "V_NEXT_P6U_PDEF_SHRED_CAP_BPS = 1_500",
        "single target": "V_NEXT_P6U_ENCOUNTER_TARGET_COUNT = 1",
        "typed primary": "hasTypedPrimaryOutgoingCarrier",
        "profile typed": "targetProfile != MonsterProfile.ARMORED",
        "canonical restored": 'return reject("CANONICAL_PDEF_NOT_RESTORED")',
        "other contributor closed": '"P6U_V01_OTHER_PDEF_CONTRIBUTOR_UNSUPPORTED"',
        "live false": "val liveReady: Boolean get() = false",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime:{name}", anchor in runtime, anchor)
    test_anchors = (
        "old_stack_applies_first_zero_and_fourth_level100_cap_1500",
        "barrier_shield_zero_hp_and_multi_packet_increment_once_per_root",
        "miss_swap_nonarmored_support_nondirect_execution_and_system_reset_exactly",
        "delayed_resolution_monster_tick_and_ai_preview_retain_exact_ledger",
        "magic_and_true_direct_build_stack_but_never_apply_pdef_shred",
        "forged_stale_replay_equal_sum_and_pdef_restore_fail_closed",
        "passive_absent_is_exact_no_op_without_receipt_or_ids",
    )
    for anchor in test_anchors:
        check(f"unit:{anchor}", anchor in runtime_test, anchor)

    resolver_anchors = {
        "compile": "VNextOreMemoryRuntimeP6uCompiler.compile(registry)",
        "bind": "oreMemoryRuntime.bind(",
        "basic": 'oreMemoryRuntime.describeBasic("BASIC")',
        "active typed": "oreMemoryRuntime.describeActive(dispatchActivePlan)",
        "recovery": "P6uHeroActionKind.SYSTEM_RECOVERY",
        "attempt pdef": "monster.copy(physicalDefense = it.preparedTargetPdef)",
        "restore pdef": "physicalDefense = monster.physicalDefense",
        "commit": "oreMemorySession.commit(",
        "handled": "addAll(VNextOreMemoryRuntimeP6u.supportedDefinitionIds())",
        "transaction": "append(transaction.oreMemoryMaximumAppliedBps)",
        "feature false": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "live false": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)
    integration_anchors = (
        "p6u production basic roots reach three stacks apply 1500 and restore canonical armored pdef",
        "p6u production full active five passive three commits for all six classes",
        "p6u production delayed arm resets once and delayed resolution creates no second receipt",
        'listOf("WARRIOR", "ROGUE", "RANGER", "MAGE", "CLERIC", "PALADIN")',
        "assertEquals(8, result.equippedSkillIds.size",
        "assertEquals(oreRequest.encounter.resolvedStats.physicalDefense, withOre.finalMonster.physicalDefense)",
        "assertEquals(result.transactionHash, VNextBattleResolverTransaction.transactionHash(result))",
    )
    for anchor in integration_anchors:
        check(f"integration:{anchor[:32]}", anchor in resolver_test, anchor)

    sys.path.insert(0, str(ROOT / "tools"))
    try:
        module = importlib.import_module("production_p6u_ore_memory_v0_1_review")
        observed = module.canonical_hash()
        check("balance:canonical", observed == BALANCE_CANONICAL, observed)
        binding = module.inspect_p6u_runtime_binding()
        check("balance:runtime", binding["sourceSha256"] == FREEZE[RUNTIME], binding["sourceSha256"])
        check("balance:test", binding["testSha256"] == FREEZE[RUNTIME_TEST], binding["testSha256"])
        check("balance:handled", len(binding["handledAfterOutgoingIds"]) == 10, len(binding["handledAfterOutgoingIds"]))
        check("balance:deferred", set(binding["deferredAfterOutgoingIds"]) == EXPECTED_DEFERRED,
              ",".join(binding["deferredAfterOutgoingIds"]))
    except Exception as error:
        check("balance:import-and-binding", False, f"{type(error).__name__}:{error}")

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    total = tuple(sum(values) for values in zip(engine, debug, release))
    check("reports:engine", engine == (805, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (981, 0, 0, 0), total)
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    lint_errors = sum(node.attrib.get("severity") == "Error" for node in lint.findall("issue"))
    lint_warnings = sum(node.attrib.get("severity") == "Warning" for node in lint.findall("issue"))
    check("reports:lint", (lint_errors, lint_warnings) == (0, 22), (lint_errors, lint_warnings))

    check("artifact:apk-size", APK.stat().st_size == APK_SIZE, APK.stat().st_size)
    check("artifact:apk-sha", sha(APK) == APK_SHA, sha(APK))
    check("artifact:screen", sha(SCREEN) == SCREEN_SHA, sha(SCREEN))
    check("artifact:ui", sha(UI) == UI_SHA, sha(UI))
    check("artifact:screen-copy", SCREEN.read_bytes() == SOURCE_SCREEN.read_bytes(), SCREEN.stat().st_size)
    check("artifact:ui-copy", UI.read_bytes() == SOURCE_UI.read_bytes(), UI.stat().st_size)
    ui_text = UI.read_text(encoding="utf-8")
    check("artifact:empty-roster", "아직 캐릭터가 없습니다" in ui_text, "empty roster")
    check("artifact:create-character", "새 캐릭터" in ui_text, "create")

    expected_audit = {
        "serial": "emulator-5554",
        "avdName": "alarmquest-qa",
        "androidRelease": "15",
        "apiLevel": "35",
        "packageName": "com.nullplaying",
        "versionName": "0.1.0",
        "versionCode": "1",
        "targetSdk": "36",
        "apkSizeBytes": str(APK_SIZE),
        "apkSha256": APK_SHA,
        "uninstallResult": "Success",
        "installResult": "Success",
        "pmClearResult": "Success",
        "launchState": "COLD",
        "coldLaunchTotalTimeMs": "5854",
        "coldLaunchWaitTimeMs": "5862",
        "warmBringToFrontWaitTimeMs": "358",
        "topResumedActivity": "com.nullplaying/.MainActivity",
        "freshRosterEmpty": "true",
        "appFatalCount": "0",
        "appErrorCount": "0",
        "emulatorScreenshotSha256": SCREEN_SHA,
        "emulatorUiDumpSha256": UI_SHA,
        "engineTests": "805",
        "appDebugTests": "88",
        "appReleaseTests": "88",
        "totalTests": "981",
        "testFailures": "0",
        "testErrors": "0",
        "testSkipped": "0",
        "lintErrors": "0",
        "lintWarnings": "22",
        "featureDefaultEnabled": "false",
        "liveSettlementEnabled": "false",
        "totalPassiveDefinitionsEnabled": "55",
        "remainingPassiveDefinitions": "35",
        "p6uRulesVersion": P6U_RULES,
        "p6uContentHash": P6U_CONTENT,
        "battleResolverRulesVersion": RESOLVER_RULES,
        "handledAfterOutgoingCount": "10",
        "deferredAfterOutgoingCount": "6",
        "p6uRuntimeSha256": FREEZE[RUNTIME],
        "p6uUnitTestSha256": FREEZE[RUNTIME_TEST],
        "battleResolverSha256": FREEZE[RESOLVER],
        "battleResolverTestSha256": FREEZE[RESOLVER_TEST],
        "balanceVerifierSha256": FREEZE[BALANCE],
        "balanceCanonicalSha256": BALANCE_CANONICAL,
        "balancePdPairs": "1728000",
        "balancePdResult": "PASS_12_OF_12",
        "sixClassFullActive5Passive3": "true",
        "canonicalPdefRestored": "true",
        "delayedResolutionNoSecondReceipt": "true",
        "passiveOffExactNoEvidence": "true",
        "visualAssetRequired": "false",
        "designTeamImageRequested": "false",
        "pdFinalDecision": "GO_ENGINE_FOUNDATION_FEATURE_LIVE_OFF",
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, f"{audit.get(key)} == {expected}")
    check("audit:tool-sha", audit.get("auditToolSha256") == sha(AUDIT_TOOL), sha(AUDIT_TOOL))
    check("doc:foundation-go", "GO_ENGINE_FOUNDATION_FEATURE_LIVE_OFF" in doc, "foundation GO")
    check("doc:tests", "981/981" in doc, "981/981")
    check("doc:pd200", "1,728,000" in doc and "PASS 12/12" in doc, "PD200")
    check("doc:no-image", "designTeamImageRequested=false" in doc, "no image")

    if args.with_emulator:
        devices = adb("devices", "-l")
        check("live:device", "emulator-5554" in devices and "device" in devices, devices)
        check("live:avd", "alarmquest-qa" in adb("emu", "avd", "name"), adb("emu", "avd", "name"))
        package = adb("shell", "dumpsys", "package", "com.nullplaying")
        check("live:version", "versionName=0.1.0" in package and "targetSdk=36" in package, "version/target")
        activity = adb("shell", "dumpsys", "activity", "activities")
        check("live:focus", "com.nullplaying/.MainActivity" in activity, "MainActivity")

    failures = [item for item in checks if not item[1]]
    print(f"SUMMARY PASS={len(checks) - len(failures)} FAIL={len(failures)} TOTAL={len(checks)}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
