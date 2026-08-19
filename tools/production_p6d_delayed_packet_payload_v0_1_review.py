#!/usr/bin/env python3
"""PD audit for P6d delayed packet ownership, regression evidence, and emulator state."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6d-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6d-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6d-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6D_DELAYED_PACKET_PAYLOAD_v0.1.md"

EXPECTED_APK_SHA = "9260e52995e667f4c5424e370fcc36e36e6b13e22c6843d6abe6dbfbe2f6e463"
EXPECTED_SCREEN_SHA = "09f8ee52abb3c74d8a727a4419b4325346ea40feec4e201756498b573f89fb26"
EXPECTED_UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
EXPECTED_APK_SIZE = 37_662_214

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedPacketPayloadP6d.kt":
        "f1c0742dd770c041724120c9a40e90c32bd0d14991deaf44b4b93112d28bc389",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedPacketPayloadP6dTest.kt":
        "0af875c10b45c78c880280806b29b97f2aed547731edc5e87720530de6c6edb3",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterOutgoingPacketRuntimeP6c.kt":
        "9f29eae8cbe3947512ca36aa24a44dba005a189f3a338baf2350c0281d3b1b25",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextAfterOutgoingPacketRuntimeP6cTest.kt":
        "9efbb255e54052b6e252e5bf09ebfc8d794968527bed54f18ba4924a9ca79545",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedOwnRootSemanticAdapterP5u.kt":
        "49dced0ac427835f22ed2d35092258a06df221df91c64fa25db44d94db162d02",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedCastSemanticAdapterP5v.kt":
        "e1dd7033c1dacd06e411b102816bb7e6fdd0a7b7f4d7f95ff0c32c165b9b4f33",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "b5442dee3160baf6e0121c5e9b945e6f7a4f6e619f061941b5c91bc4727ee205",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "0ac3fa9edc9689bfbced7987f2d5b838541f2a43888e9b73a3bba99870c25787",
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
    return subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False).stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: object) -> None:
        checks.append((name, condition, str(detail)))
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")

    payload = (ROOT / next(k for k in FREEZE if k.endswith("VNextDelayedPacketPayloadP6d.kt"))).read_text()
    payload_test = (ROOT / next(k for k in FREEZE if k.endswith("VNextDelayedPacketPayloadP6dTest.kt"))).read_text()
    p6c = (ROOT / next(k for k in FREEZE if k.endswith("VNextAfterOutgoingPacketRuntimeP6c.kt"))).read_text()
    p5u = (ROOT / next(k for k in FREEZE if k.endswith("VNextDelayedOwnRootSemanticAdapterP5u.kt"))).read_text()
    p5v = (ROOT / next(k for k in FREEZE if k.endswith("VNextDelayedCastSemanticAdapterP5v.kt"))).read_text()
    resolver = (ROOT / next(k for k in FREEZE if k.endswith("VNextBattleResolverTransaction.kt"))).read_text()
    resolver_test = (ROOT / next(k for k in FREEZE if k.endswith("VNextBattleResolverTransactionTest.kt"))).read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    audit = AUDIT.read_text()
    doc = DOC.read_text()

    payload_anchors = {
        "P6d rules": "aq.delayed-packet-payload.p6d.v0.1",
        "P6d content": "1c415083b66a7e1675505c70cb44feb2d9f34c8a93680a20fc40995d592f7f7c",
        "strict JSON": "ignoreUnknownKeys = false",
        "base64 envelope": 'private const val PREFIX = "P6D1."',
        "source receipt": "val sourceReceiptId: String",
        "coefficient payload": "val coefficientAddBps: Int",
        "hit groups": "val profileHitAddBps: Int",
        "variance payload": "val minimumVarianceBps: Int",
        "critical payload": "val criticalForbidden: Boolean",
        "P6c ledger hash": "val afterLedgerBeforeHash: String",
        "P6c plan hash": "val afterPlanHash: String",
        "payload hash": "val payloadHash: String",
        "tamper validation": "payload.payloadHash == payloadHash(payload)",
        "delayed only": "P6cHeroActionKind.ACTIVE_DELAYED",
        "content pin": "fun contentPinned(): Boolean",
    }
    for name, anchor in payload_anchors.items():
        check(name, anchor in payload, "bound")

    p6c_anchors = {
        "P6c v0.2": "aq.after-outgoing-packet.p6c.v0.2",
        "delayed attack": "val delayedAttack = delayed",
        "delayed study": "P6cHeroActionKind.ACTIVE_DELAYED,",
        "delayed commit": "fun commitDelayedHeroOutcome(",
        "enemy drift meaning check": "frozenPlan.sameDecisionP6c(currentPlan)",
        "cancel no miss": "consecutiveHitCount = ledger.consecutiveHitCount",
        "cancel consumes analysis": "currentPlan.consumesFailureAnalysis",
        "delayed attachment contract": "delayedAttachment=true",
    }
    for name, anchor in p6c_anchors.items():
        check(name, anchor in p6c, "bound")

    adapter_anchors = {
        "P5u creates payload": (p5u, "VNextDelayedPacketPayloadP6d.create("),
        "P5u stores payload": (p5u, "linkedGroup = delayedPacketPayload"),
        "P5u restores payload": (p5u, "delayedPacketPayload = token.linkedGroup"),
        "P5v creates payload": (p5v, "VNextDelayedPacketPayloadP6d.create("),
        "P5v stores payload": (p5v, "linkedGroup = delayedPacketPayload"),
        "P5v restores payload": (p5v, "delayedPacketPayload = token.linkedGroup"),
    }
    for name, (source, anchor) in adapter_anchors.items():
        check(name, anchor in source, "bound")

    resolver_anchors = {
        "resolver p6d version": "aq.battle-resolver-transaction.p6d.v0.3",
        "content pin gate": "VNextDelayedPacketPayloadP6d.contentPinned()",
        "payload failure": "DELAYED_PACKET_PAYLOAD_REJECTED",
        "dispatch freezes P6c": "afterOutgoingPlan = afterOutgoingPlan",
        "arm skips result": "actionKind != P6cHeroActionKind.ACTIVE_DELAYED",
        "P5u modifier replay": "outgoingPacketModifier = delayedOutgoingModifier",
        "P5v modifier replay": "outgoingPacketModifier = delayedOutgoingModifier",
        "result commit": "commitDelayedHeroOutcome(",
        "receipt exposes hash": "P6D:payload=",
        "transaction content": "append(V_NEXT_P6D_DELAYED_PACKET_CONTENT_HASH)",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(name, anchor in resolver, "bound")

    test_names = (
        "content contract and strict payload round trip preserve the arm decision",
        "one changed envelope byte or source owner is rejected",
        "enemy direct receipt drift cannot replace a frozen delayed hit decision",
        "cancelled delayed packet consumes reserved next action tokens without recording a miss",
    )
    for name in test_names:
        check(f"unit {name[:42]}", name in payload_test, "covered")
    integration_names = (
        "p5u prepared smash pays one empty root then owns the next hero root with fixed hit",
        "p5u payloadless injected token is rejected before a cancelled root can settle",
        "p5v starfall applies highest passive stability and one hit cancel roll before forced root",
        "p5v payloadless saved marker is rejected before it consumes a hero root",
    )
    for name in integration_names:
        check(f"integration {name[:42]}", name in resolver_test, "covered")
    check("integration P6b carried", "P6B:coefficient=[1-9][0-9]*" in resolver_test, "covered")
    check("integration payload receipt", '"P6D:payload=" in resolved.detail' in resolver_test, "covered")

    check("legacy resolveKill present", "fun resolveKill(" in settlement, "present")
    check("legacy P6d unconnected", "VNextDelayedPacketPayloadP6d" not in settlement, "safe")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals(ROOT / "game-engine/build/test-results/test")
    app_debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    app_release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    app = tuple(app_debug[i] + app_release[i] for i in range(4))
    check("engine tests", engine == (627, 0, 0, 0), engine)
    check("app tests", app == (176, 0, 0, 0), app)

    lint_root = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    issues = lint_root.findall("issue")
    lint_errors = sum(i.attrib.get("severity") == "Error" for i in issues)
    lint_warnings = sum(i.attrib.get("severity") == "Warning" for i in issues)
    check("lint", (lint_errors, lint_warnings) == (0, 22), f"{lint_errors}/{lint_warnings}")
    check("APK hash", sha(APK) == EXPECTED_APK_SHA, sha(APK))
    check("APK size", APK.stat().st_size == EXPECTED_APK_SIZE, APK.stat().st_size)
    check("screenshot hash", sha(SCREEN) == EXPECTED_SCREEN_SHA, sha(SCREEN))
    check("UI dump hash", sha(UI) == EXPECTED_UI_SHA, sha(UI))

    required_audit = (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=4822", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=627", "appTests=176",
        "totalTests=803", "delayedPacketDefinitionsEnabled=3", "outgoingModifierFrozenAtArm=true",
        "afterOutgoingPlanFrozenAtArm=true", "enemyDirectReceiptDriftAllowed=true",
        "enemyDirectMayReplaceFrozenDecision=false", "cancelRecordsMiss=false",
        "payloadTamperRejected=true", "payloadlessInjectedTokenRejected=true",
        "transactionHashIncludesDelayedPacketContent=true", "visualAssetRequired=false",
        f"emulatorScreenshotSha256={EXPECTED_SCREEN_SHA}", f"emulatorUiDumpSha256={EXPECTED_UI_SHA}",
    )
    for field in required_audit:
        check(f"audit {field.split('=')[0]}", field in audit, field)

    check("document conditional approval", "PD 조건부 승인" in doc, "present")
    check("document 31 enabled", "Passive 구현 수는 **31종**" in doc, "present")
    check("document remaining 59", "미구현 Passive 59종" in doc, "present")
    check("document design decision", "새 이미지 제작은 요청하지 않았다" in doc, "present")

    if args.with_emulator:
        devices = command("adb", "devices", "-l")
        package = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest")
        focus = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities")
        fatal = command("adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief", "*:E")
        check("emulator online", "emulator-5554" in devices and " device " in devices, devices)
        check("version", "versionName=0.1.0" in package and "versionCode=1" in package, "0.1.0(1)")
        check("target sdk", "targetSdk=36" in package, "36")
        check("focus", "com.alarmquest/.MainActivity" in focus, "MainActivity")
        ui_text = UI.read_text()
        check("empty roster", "아직 캐릭터가 없습니다" in ui_text and "새 캐릭터" in ui_text, "fresh")
        check("fatal zero", "FATAL EXCEPTION" not in fatal and "AndroidRuntime" not in fatal, "0")

    passed = sum(ok for _, ok, _ in checks)
    print(f"P6d PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
