#!/usr/bin/env python3
"""PD audit for P6e packet-safe incoming passives and emulator evidence."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6e-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6e-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6e-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6E_BEFORE_INCOMING_PACKET_v0.1.md"
EXPECTED_APK_SHA = "47ec86b31894f9183aa272621511b9f6e555ee6263e2a944a5806618f3531b45"
EXPECTED_SCREEN_SHA = "14c11ac22bedf530887fa5077d7b29b6d02c700d20ed721b19f495e05e6a0de6"
EXPECTED_UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
EXPECTED_APK_SIZE = 37_678_221
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeIncomingPacketRuntimeP6e.kt":
        "02c1533ac1765a4e92aa9f97173ea8f80e4b399c20842f145a9cf429b524e88f",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeIncomingPacketRuntimeP6eTest.kt":
        "0db158191345a070e2e9a44fd769122caa08c6af10bed94e114d36753ebb83f2",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "286223463c56eb482e7f298f2a85d98f4c3d9b6b720a7b653453dd095dea5fb8",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "2fd9c467889f80f6888b8c690a658caeecad90a9ecc6dd31d03df19dec004ccc",
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
    return subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False).stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: object) -> None:
        checks.append((name, condition, str(detail)))
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")

    runtime = (ROOT / next(k for k in FREEZE if k.endswith("VNextBeforeIncomingPacketRuntimeP6e.kt"))).read_text()
    tests = (ROOT / next(k for k in FREEZE if k.endswith("VNextBeforeIncomingPacketRuntimeP6eTest.kt"))).read_text()
    resolver = (ROOT / next(k for k in FREEZE if k.endswith("VNextBattleResolverTransaction.kt"))).read_text()
    resolver_tests = (ROOT / next(k for k in FREEZE if k.endswith("VNextBattleResolverTransactionTest.kt"))).read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    audit = AUDIT.read_text()
    doc = DOC.read_text()

    anchors = {
        "rules": "aq.before-incoming-packet.p6e.v0.1",
        "content": "6fb1ad2a689d9c513ece871414cc0dae2d765ccf498dba0c4c9a45eed6c7ff03",
        "four definitions": "V_NEXT_P6E_BEFORE_INCOMING_DEFINITION_COUNT = 4",
        "thirteen deferred": "deferredBeforeIncomingIds.size == 13",
        "shieldless": "aq.skill.common.w3.shieldlessguard",
        "alert": "aq.skill.common.w8.alertstance",
        "slime": "aq.skill.monster.w7.slimeskin",
        "sixth": "aq.skill.rogue.p1.sixthsense",
        "threshold": "coefficientBps >= 11_000",
        "fire risk": "damageType == \"FIRE\") 1_000",
        "physical only": "damageType == \"PHYSICAL\"",
        "shield zero": "target.shield == 0",
        "family cap": "coerceIn(-V_NEXT_P6E_INCOMING_REDUCTION_CAP_BPS, 0)",
        "prepare hash": "ledgerBeforeHash = ledger.ledgerHash",
        "commit receipt": "receiptId !in ledger.processedReceiptIds",
        "packet count": "committedIncomingPacketCount + 1",
        "event route": "P5wPassiveEvent.BEFORE_INCOMING_PACKET",
    }
    for name, anchor in anchors.items():
        check(name, anchor in runtime, "bound")

    test_names = (
        "coverage activates four packet safe definitions and defers thirteen cross system contracts",
        "shieldless guard turns off immediately when shield exists",
        "alert stance consumes on the first committed packet attempt only",
        "sixth sense waits for the first coefficient at or above eleven thousand",
        "slime skin reduces the first physical packet while fire vulnerability remains",
        "additive reductions clamp before fixed fire vulnerability",
        "stale plan duplicate receipt and wrong owner fail closed",
    )
    for name in test_names:
        check(f"unit {name[:44]}", name in tests, "covered")

    resolver_anchors = {
        "resolver version": "aq.battle-resolver-transaction.p6e.v0.4",
        "bind failure": "PASSIVE_BEFORE_INCOMING_BIND_REJECTED",
        "ledger failure": "PASSIVE_BEFORE_INCOMING_LEDGER_REJECTED",
        "compile": "VNextBeforeIncomingPacketRuntimeP6eCompiler.compile(registry)",
        "prepare": "beforeIncomingSession.prepare(",
        "packet modifier": "beforeIncomingPlan.incomingDamageModifierBps",
        "global cap": ").coerceIn(-7_000, 10_000)",
        "commit after defense": "beforeIncomingSession.commit(beforeIncomingLedger, beforeIncomingPlan, receiptId)",
        "receipt detail": "beforeIncomingPlan.detail.takeIf",
        "transaction content": "beforeIncomingContentHash",
        "transaction session": "beforeIncomingSessionHash",
        "transaction initial": "initialBeforeIncomingLedgerHash",
        "transaction final": "finalBeforeIncomingLedgerHash",
        "transaction count": "beforeIncomingCommittedPacketCount",
        "hash includes count": "append(transaction.beforeIncomingCommittedPacketCount)",
        "adapter filter": "VNextBeforeIncomingPacketRuntimeP6e.supportedDefinitionIds()",
        "feature off": "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false",
        "settlement off": "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false",
    }
    for name, anchor in resolver_anchors.items():
        check(name, anchor in resolver, "bound")
    integration = "p6e first incoming guards reduce the committed packet and advance one ledger receipt"
    check("resolver integration", integration in resolver_tests, "covered")
    for anchor in (
        "defendedFirst.hpDamage < controlFirst.hpDamage",
        "defended.beforeIncomingCommittedPacketCount",
        "aq.skill.common.w3.shieldlessguard",
        "aq.skill.common.w8.alertstance",
    ):
        check(f"integration {anchor[:36]}", anchor in resolver_tests, "covered")

    check("legacy resolveKill", "fun resolveKill(" in settlement, "present")
    check("legacy unconnected", "VNextBeforeIncomingPacketRuntimeP6e" not in settlement, "safe")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    app = tuple(debug[i] + release[i] for i in range(4))
    check("engine tests", engine == (635, 0, 0, 0), engine)
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
        "launchState=COLD", "coldTotalTimeMs=4771", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=635", "appTests=176",
        "totalTests=811", "beforeIncomingPacketDefinitionsEnabled=4", "totalPassiveDefinitionsEnabled=35",
        "remainingPassiveDefinitions=55", "beforeIncomingPacketDefinitionsClassified=17",
        "beforeIncomingPacketDefinitionsDeferred=13", "shieldlessTurnsOffWithShield=true",
        "alertConsumesFirstCommittedPacketAttempt=true", "slimeFireVulnerabilityBps=1000",
        "sixthSenseCoefficientThresholdBps=11000", "familyIncomingReductionCapBps=2500",
        "globalIncomingModifierMinimumBps=-7000", "incomingLedgerInTransactionHash=true",
        "runtimeHandledPassivesFilteredFromAdapter=true", "visualAssetRequired=false",
        f"emulatorScreenshotSha256={EXPECTED_SCREEN_SHA}", f"emulatorUiDumpSha256={EXPECTED_UI_SHA}",
    )
    for field in audit_fields:
        check(f"audit {field.split('=')[0]}", field in audit, field)
    check("document approval", "PD 조건부 승인" in doc, "present")
    check("document 35", "Passive 35종 구현" in doc, "present")
    check("document 55", "미구현 55종" in doc, "present")
    check("document dependency map", "잔여 55종 의존성 지도" in doc, "present")
    check("document design", "새 화면 이미지가 필요하지 않다" in doc, "present")

    if args.with_emulator:
        devices = command("adb", "devices", "-l")
        package = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest")
        focus = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities")
        fatal = command("adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief", "*:E")
        check("emulator online", "emulator-5554" in devices and " device " in devices, devices)
        check("version", "versionName=0.1.0" in package and "versionCode=1" in package, "0.1.0(1)")
        check("target sdk", "targetSdk=36" in package, "36")
        check("focus", "com.alarmquest/.MainActivity" in focus, "MainActivity")
        ui = UI.read_text()
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        check("fatal zero", "FATAL EXCEPTION" not in fatal and "AndroidRuntime" not in fatal, "0")

    passed = sum(ok for _, ok, _ in checks)
    print(f"P6e PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
