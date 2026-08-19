#!/usr/bin/env python3
"""Fail-closed release audit for P6v Faith Echo foundation artifacts."""

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
RUNTIME = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextFaithEchoRuntimeP6v.kt"
RUNTIME_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextFaithEchoRuntimeP6vTest.kt"
RESOLVER = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt"
RESOLVER_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt"
P5M = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5m.kt"
P5M_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5mTest.kt"
P5V = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5v.kt"
AI = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt"
AI_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt"
BALANCE = ROOT / "tools/production_p6v_faith_echo_v0_1_review.py"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6V_FAITH_ECHO_v0.1.md"
AUDIT = ROOT / "artifacts/audit/production-p6v-emulator-verification-v0.1.txt"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
SCREEN = ROOT / "artifacts/audit/production-p6v-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6v-emulator-ui-v0.1.xml"
SOURCE_SCREEN = ROOT / "artifacts/p6v-emulator/alarmquest-p6v.png"
SOURCE_UI = ROOT / "artifacts/p6v-emulator/alarmquest-p6v.xml"

P6V_RULES = "aq.faith-echo.p6v.v0.1"
P6V_CONTENT = "23999e29f7f04d65a33e9323469b98ae759893972935ee6d02ea121987aaaca9"
P5M_RULES = "aq.mixed-survival-correction.p5m.v0.2"
P5M_CONTENT = "7b9dcb98d474d76c491fac0960569628050cc09e426b57a5e9ae3da7578b88db"
P5V_CONTENT = "5a7836f2a9e93674db4287e3f22772cc57cf85cd270bd67fd1ac5fa1a25c07ac"
RESOLVER_RULES = "aq.battle-resolver-transaction.p6v.v0.23"
BALANCE_CANONICAL = "38b40c24e2d16318024e7899a5d32388e6dd2912be2d98669ceccb8d30a32959"
FREEZE = {
    RUNTIME: "bb4e0da8f18770904d8f1ed7561d03ae5b162c75056e4877e439c668058d3eb1",
    RUNTIME_TEST: "b055a17b969001f0abdb549534d92aa878d4e4f5534480795a05eebbc56ee660",
    RESOLVER: "1edbc5c3dded1c6b7280e2200656d5cc3d19e8dd2639fcc17dd964c38b888e9d",
    RESOLVER_TEST: "1c5c0110e0bdf8e8c74c19fae8960077dd4e3f8213ee4f78d3f9e3c813930baf",
    P5M: "77f5cb66d6d75a19162f0b004be621c7379c2e8732a010f34b13e2ce20e59691",
    P5M_TEST: "5f6eeed6927cfc621d33fbae4cb036159495ca5e2cdc8524c4ce132b1ec51edb",
    P5V: "9ec885d035b608612c49e21a87eb246bd4d2a22faa8c79f53c85d6c54949801e",
    AI: "caff570e097cf0f7b6a7e59e788d730aec3b037e1a5fdf55adc0100142761c00",
    AI_TEST: "2a1937005053f934038dc908936e311c8c204c18d3c7b987df7545761407adb6",
    BALANCE: "63d0477c66561c44435a0addcc00d295d625fa85779fe2afc4cd3a0b6c4b04d0",
}
APK_SIZE = 38_366_822
APK_SHA = "d907866f21f4d29422840d4145e95a3d0d06cf1656795c5e21b0eeaf9e8efa76"
SCREEN_SHA = "bcaeaa17161cabf53caddc37911190b0b7de6489cd7c28c1e8b43f2b2bb0f490"
UI_SHA = "8d2715bfaeb699c6c90eef0c84f3980e74d30261e84739737ace14d1aa89c844"


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
        *FREEZE, P5V, DOC, AUDIT, APK, SCREEN, UI, SOURCE_SCREEN, SOURCE_UI,
        ROOT / "game-engine/build/test-results/test",
        ROOT / "app/build/test-results/testDebugUnitTest",
        ROOT / "app/build/test-results/testReleaseUnitTest",
        ROOT / "app/build/reports/lint-results-debug.xml",
    ]
    for path in required:
        check(f"file:{path.relative_to(ROOT)}", path.exists(), path.exists())
    if not all(path.exists() for path in required):
        return 1

    sources = {path: path.read_text(encoding="utf-8") for path in FREEZE if path.suffix == ".kt"}
    runtime = sources[RUNTIME]
    runtime_test = sources[RUNTIME_TEST]
    resolver = sources[RESOLVER]
    resolver_test = sources[RESOLVER_TEST]
    p5m = sources[P5M]
    p5m_test = sources[P5M_TEST]
    ai = sources[AI]
    ai_test = sources[AI_TEST]
    p5v = P5V.read_text(encoding="utf-8")
    doc = DOC.read_text(encoding="utf-8")
    audit = parsed_audit()

    for path, expected in FREEZE.items():
        check(f"freeze:{path.name}", sha(path) == expected, sha(path))
    check("runtime:rules", extract_string(runtime, "V_NEXT_P6V_FAITH_ECHO_RULES_VERSION") == P6V_RULES, P6V_RULES)
    check("runtime:content", extract_string(runtime, "V_NEXT_P6V_FAITH_ECHO_CONTENT_HASH") == P6V_CONTENT, P6V_CONTENT)
    check("p5m:rules", extract_string(p5m, "V_NEXT_P5M_MIXED_SURVIVAL_RULES_VERSION") == P5M_RULES, P5M_RULES)
    check("p5m:content", extract_string(p5m, "V_NEXT_P5M_MIXED_SURVIVAL_CONTENT_HASH") == P5M_CONTENT, P5M_CONTENT)
    check("p5v:content", extract_string(p5v, "V_NEXT_P5V_COMPOSITE_CONTENT_HASH") == P5V_CONTENT, P5V_CONTENT)
    check("resolver:rules", extract_string(resolver, "V_NEXT_BATTLE_RESOLVER_RULES_VERSION") == RESOLVER_RULES, RESOLVER_RULES)

    runtime_anchors = {
        "typed host": 'P6V_TYPED_HOST_PATTERN = "SURVIVAL_SHIELD"',
        "two roots": "V_NEXT_P6V_FAITH_ECHO_DURATION_HERO_ROOTS = 2",
        "common negative": "clericCommonShield=NON_HOST_TOKEN_PRESERVED",
        "staged root": "STAGED_ROOT_PLAN,AI_PREVIEW,DISPATCH,SINGLE_ROOT_COMMIT",
        "ledger session": "require(ledger.isInternallyValidP6v(snapshotHash))",
        "receipt replay": "rootReceiptId != expectedRootReceiptId",
        "long math": "hp.toLong() * 10_000L",
        "deferred five": "deferredAfterOutgoing=5|live=false",
        "live false": "val liveReady: Boolean get() = false",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime:{name}", anchor in runtime, anchor)
    test_anchors = (
        "coverage_closes_typed_hosts_common_negative_control_and_deferred_set",
        "five_anchors_arm_from_committed_heal_and_raise_only_typed_threshold_host",
        "token_is_eligible_on_two_following_roots_and_expires_atomically_at_second_root_end",
        "long_threshold_math_stale_plan_replay_and_wrong_class_fail_closed",
        'sessionHash = "forged-session"',
        "tokenMagnitudeBps = 100",
        "passive_absent_has_no_token_or_threshold_modifier",
    )
    for anchor in test_anchors:
        check(f"unit:{anchor[:36]}", anchor in runtime_test, anchor)

    p5m_anchors = (
        "request.faithEchoSessionHash.orEmpty()",
        "request.rootReceiptId",
        "P6V_THRESHOLD_DECISION_REJECTED",
        "survivalThresholdReceipt = p6vThreshold",
    )
    for anchor in p5m_anchors:
        check(f"p5m:{anchor[:36]}", anchor in p5m, anchor)
    for anchor in (
        "p6v threshold receipt is session bound and rejects copied hash evidence",
        "p5m:p6v:replayed-root",
        'decisionHash = "forged-hash"',
    ):
        check(f"p5m-test:{anchor[:32]}", anchor in p5m_test, anchor)

    resolver_anchors = {
        "compile": "VNextFaithEchoRuntimeP6vCompiler.compile(registry)",
        "handled": "addAll(VNextFaithEchoRuntimeP6v.supportedDefinitionIds())",
        "threshold": "survivalThresholdDecision = faithEchoThreshold",
        "session": "faithEchoSessionHash = if (faithEchoEquipped)",
        "typed echo": "dispatched.survivalThresholdReceipt != faithEchoThreshold",
        "provenance field": "semanticDispatcherContentHash",
        "official only": "dispatcher is VNextCompositeSemanticDispatcherP5v",
        "transaction hash": "append(transaction.semanticDispatcherContentHash)",
        "feature false": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "live false": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)
    for anchor in (
        "p6v committed heal opens a typed threshold window and the next cleric ward consumes it",
        "p6v common shield is a negative control that never consumes the armed token",
        "p6v forged dispatcher threshold receipt is rejected before token consumption",
        "p6v cleric production active five passive three commits without adapter leakage",
        "V_NEXT_P5V_COMPOSITE_CONTENT_HASH, result.semanticDispatcherContentHash",
        'assertEquals("", result.semanticDispatcherContentHash)',
    ):
        check(f"integration:{anchor[:34]}", anchor in resolver_test, anchor)
    check("ai:override only", "survivalThresholdOverrideBps" in ai, "typed override")
    check("ai:long math", "state.hp.toLong() * 10_000L" in ai, "Long")
    check("ai-test:long", "resolver threshold override uses long math" in ai_test, "large HP")

    sys.path.insert(0, str(ROOT / "tools"))
    try:
        module = importlib.import_module("production_p6v_faith_echo_v0_1_review")
        binding = module.inspect_integration_bindings({}, True)
        observed = module.canonical_hash(binding)
        check("balance:exact", binding["allExact"] is True, binding["mode"])
        check("balance:canonical", observed == BALANCE_CANONICAL, observed)
        check("balance:runtime", binding["actualSha256"]["P6V_RUNTIME"] == FREEZE[RUNTIME], binding["actualSha256"]["P6V_RUNTIME"])
        check("balance:test", binding["actualSha256"]["P6V_TEST"] == FREEZE[RUNTIME_TEST], binding["actualSha256"]["P6V_TEST"])
        check("balance:resolver", binding["actualSha256"]["RESOLVER"] == FREEZE[RESOLVER], binding["actualSha256"]["RESOLVER"])
    except Exception as error:
        check("balance:import-and-binding", False, f"{type(error).__name__}:{error}")

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    total = tuple(sum(values) for values in zip(engine, debug, release))
    check("reports:engine", engine == (822, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (998, 0, 0, 0), total)
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
        "serial": "emulator-5554", "avdName": "alarmquest-qa", "androidRelease": "15",
        "apiLevel": "35", "packageName": "com.alarmquest", "versionName": "0.1.0",
        "versionCode": "1", "targetSdk": "36", "apkSizeBytes": str(APK_SIZE),
        "apkSha256": APK_SHA, "uninstallResult": "Success", "installResult": "Success",
        "pmClearResult": "Success", "launchState": "COLD", "coldLaunchTotalTimeMs": "5260",
        "coldLaunchWaitTimeMs": "5263", "warmBringToFrontWaitTimeMs": "250",
        "topResumedActivity": "com.alarmquest/.MainActivity", "freshRosterEmpty": "true",
        "appFatalCount": "0", "appErrorCount": "0", "emulatorScreenshotSha256": SCREEN_SHA,
        "emulatorUiDumpSha256": UI_SHA, "engineTests": "822", "appDebugTests": "88",
        "appReleaseTests": "88", "totalTests": "998", "testFailures": "0",
        "testErrors": "0", "testSkipped": "0", "lintErrors": "0", "lintWarnings": "22",
        "featureDefaultEnabled": "false", "liveSettlementEnabled": "false",
        "totalPassiveDefinitionsEnabled": "56", "remainingPassiveDefinitions": "34",
        "handledAfterOutgoingCount": "11", "deferredAfterOutgoingCount": "5",
        "p6vRulesVersion": P6V_RULES, "p6vContentHash": P6V_CONTENT,
        "p5mRulesVersion": P5M_RULES, "p5mContentHash": P5M_CONTENT,
        "p5vCompositeContentHash": P5V_CONTENT, "battleResolverRulesVersion": RESOLVER_RULES,
        "p6vRuntimeSha256": FREEZE[RUNTIME], "p6vUnitTestSha256": FREEZE[RUNTIME_TEST],
        "battleResolverSha256": FREEZE[RESOLVER], "battleResolverTestSha256": FREEZE[RESOLVER_TEST],
        "p5mRuntimeSha256": FREEZE[P5M], "p5mUnitTestSha256": FREEZE[P5M_TEST],
        "p5vDispatcherSha256": FREEZE[P5V],
        "aiPolicySha256": FREEZE[AI], "aiPolicyTestSha256": FREEZE[AI_TEST],
        "balanceToolSha256": FREEZE[BALANCE], "balanceCanonicalSha256": BALANCE_CANONICAL,
        "balanceFastPairs": "172800", "balancePdPairs": "1440000",
        "balancePdMaxCellWinDeltaPp": "5.50", "balancePdMaxMedianRoundDelta": "1.0",
        "balancePdP90HpLossDeltaPp": "0.17", "balancePdWeightedWinGainPp": "2.29",
        "balancePdPassiveCausedLossPp": "0.69", "balancePdOpportunityCapturePct": "100.00",
        "balancePdActionChangePerTriggerPct": "19.97", "balancePdNoOpConsumePct": "75.03",
        "balancePdShieldActionShareDeltaPp": "0.00", "balancePdOffenseDisplacementPct": "0.71",
        "balancePdExactZeroPairs": "480000", "balanceInvalidConsumes": "0",
        "pdP0Count": "0", "pdP1Count": "0", "designImageRequired": "false",
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, audit.get(key, "MISSING"))
    check("audit:physical-size", audit.get("physicalSize") == "1080x2400", audit.get("physicalSize"))
    check("audit:override-size", audit.get("overrideSize") == "1080x2340", audit.get("overrideSize"))
    check("audit:override-density", audit.get("overrideDensity") == "440", audit.get("overrideDensity"))
    check("audit:no-extra-keys", set(audit) == set(expected_audit) | {"physicalSize", "overrideSize", "overrideDensity"}, sorted(set(audit) - set(expected_audit)))

    for anchor in (
        "GO_ENGINE_FOUNDATION_FEATURE_LIVE_OFF", "P0 0 / P1 0", "1,440,000",
        "998/998 PASS", "designImageRequired=false",
    ):
        if anchor == "designImageRequired=false":
            check("doc:design", "새 이미지가 필요하지 않다" in doc, "no image required")
        else:
            check(f"doc:{anchor}", anchor in doc, anchor)

    if args.with_emulator:
        devices = adb("devices", "-l")
        check("live:device", "emulator-5554" in devices and "device" in devices, devices)
        check("live:avd", "alarmquest-qa" in adb("emu", "avd", "name"), adb("emu", "avd", "name"))
        check("live:android", adb("shell", "getprop", "ro.build.version.release") == "15", "15")
        check("live:api", adb("shell", "getprop", "ro.build.version.sdk") == "35", "35")
        package = adb("shell", "dumpsys", "package", "com.alarmquest")
        check("live:package-version", "versionName=0.1.0" in package and "versionCode=1" in package, "0.1.0/1")
        check("live:target", "targetSdk=36" in package, "36")
        activity = adb("shell", "dumpsys", "activity", "activities")
        check("live:focus", "topResumedActivity" in activity and "com.alarmquest/.MainActivity" in activity, "MainActivity")
        pid = adb("shell", "pidof", "com.alarmquest")
        errors = adb("logcat", "-d", f"--pid={pid}", "-v", "brief", "*:E") if pid else "NO_PID"
        check("live:pid", pid.isdigit(), pid)
        check("live:errors", errors == "", errors or "0")

    passed = sum(ok for _, ok, _ in checks)
    print(f"PRODUCTION_P6V_FAITH_ECHO_AUDIT: {'PASS' if passed == len(checks) else 'FAIL'} ({passed}/{len(checks)})")
    print(f"  audit_tool_sha256={sha(Path(__file__))}")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
