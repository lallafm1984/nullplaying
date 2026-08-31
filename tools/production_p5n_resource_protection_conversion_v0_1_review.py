#!/usr/bin/env python3
"""PD audit for P5n resource and protection conversion semantics."""
from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "0560dee847e93b1ca59f9642c8d43afe050e220687ce90b41d47f2123cb67d95"
APK_SIZE = 37_401_916
CONVERSION_HASH = "9026761f14a09e62710bed0b07dc8ed8600076921a8fce3833925e763cc2be6d"
COMPOSITE_HASH = "ac0023548f72de06fccd77801777385e8801942862c43874cde2df8b6cc0416f"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextResourceProtectionConversionP5n.kt": "9092764c0fc15e8d119938cfb60b426d5af3a9183eb49810b019fb733e5f7c8a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5n.kt": "41de59979db9005ae27374b4133a0bffbb99acd9298f24730457bb216dcee87f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5m.kt": "7f70179fa3a58ac4d932bff9d2e3eb5de8171e136fd938989cf3d5c35367b6fa",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt": "a1687db6647f9226dc4673a83d4b8471aaabe5c34c9afd7c8fdb406ffe378137",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt": "258973efceb81c94302ee9ea5dcc943f29063ebda35b2255281ffb3b865f37bc",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextResourceProtectionConversionP5nTest.kt": "4758d4e583c3f736817e5581b62e96142f93bfc7dc74d46ebca5b842d464f574",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5nTest.kt": "25833951ed0de7870d114cc427ec9c8f9efdb9154c55c360f2f20a724f5e24f2",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt": "7db63715abd8008a6cec9d1e7685acede0918b6990b7a8d686365b15bd51a536",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt": "a2e1103f7cdebf8cf64cd2560d4864f66590c540d8bad6fbdcab1a2024a0c17a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt": "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=False)


def totals(module: str) -> tuple[int, int, int, int]:
    values = [0, 0, 0, 0]
    for path in (ROOT / module / "build/test-results").glob("test*/TEST-*.xml"):
        root = ET.parse(path).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            values[index] += int(root.attrib.get(key, 0))
    return tuple(values)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-gradle", action="store_true")
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    if args.with_gradle:
        result = run(["./gradlew", "test", "lintDebug", "assembleDebug"])
        if result.returncode:
            print(result.stdout)
            print(result.stderr, file=sys.stderr)
            return result.returncode

    conversion = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextResourceProtectionConversionP5n.kt").read_text()
    composite = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5n.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    ai = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt").read_text()
    adapter_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextResourceProtectionConversionP5nTest.kt").read_text()
    composite_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5nTest.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    ai_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt").read_text()
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5N_RESOURCE_PROTECTION_CONVERSION_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5n-emulator-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("conversion rules", "aq.resource-protection-conversion.p5n.v0.1" in conversion, "v0.1")
    check("conversion hash", CONVERSION_HASH in conversion and CONVERSION_HASH in document, CONVERSION_HASH)
    check("four definitions", "V_NEXT_P5N_CONVERSION_DEFINITION_COUNT = 4" in conversion, "4")
    check("one new token", "V_NEXT_P5N_CONVERSION_TOKEN_COUNT = 1" in conversion, "1")
    check("live off", "val liveReady get() = false" in conversion, "OFF")
    definitions = (
        ("bloodprice", "HP_TO_RESOURCE", "resource_gain_bps"),
        ("lastfortress", "RESOURCE_TO_SHIELD", "shield_maxhp_bps"),
        ("protectionconversion", "SHIELD_TO_BARRIER", "minimum_shield_requirement_reduction"),
        ("lifedistribution", "HP_RESOURCE_REBALANCE", "transfer_cap_bps"),
    )
    for skill_id, family, growth in definitions:
        check(f"{skill_id} id", f".{skill_id}\"" in conversion, skill_id)
        check(f"{skill_id} family", f"CostFamily.{family}" in conversion, family)
        check(f"{skill_id} growth", f'\"{growth}\"' in conversion, growth)
    source_contracts = (
        ("class gate", "ACTOR_CLASS_MISMATCH"),
        ("passive gate", "PASSIVE_ADAPTERS_REQUIRED"),
        ("phase gate", "PHASE_ORDER_MISMATCH"),
        ("replay gate", "REPLAY_RECEIPT"),
        ("support ledger validation", "SUPPORT_LEDGER_STATE_INVALID"),
        ("class ledger validation", "CLASS_LEDGER_STATE_INVALID"),
        ("rebalance validation", "REBALANCE_LEDGER_STATE_INVALID"),
        ("rebalance limit", "REBALANCE_LEDGER_REJECTED"),
        ("barrier capacity", "BARRIER_CAPACITY_REJECTED"),
        ("cost pipeline", "VNextCostTransferDispelHandlers.execute"),
        ("sustain pipeline", "VNextProtectionDeathPipeline.grantSustain"),
        ("class ledger sync", "syncClassProtectionLedgerP5l"),
        ("support ledger sync", "syncSupportLedgerP5k"),
        ("rebalance sync", "syncRebalanceP5n"),
        ("shield full consume", "shield = 0, barrierCharges = actorAfter.barrierCharges + 1"),
        ("half hp branch", "request.actor.hp * 2 < request.actor.maxHp"),
        ("encounter heal ceiling", "request.actorEncounterStartHp ?: request.actor.maxHp"),
    )
    for name, needle in source_contracts:
        check(name, needle in conversion, "bound")

    ai_contracts = (
        ("AI hp to resource", 'text == "HP_TO_RESOURCE"'),
        ("AI resource full", 'return "RESOURCE_FULL"'),
        ("AI hp survival", 'return "HP_COST_UNPAYABLE"'),
        ("AI resource to shield", 'text == "RESOURCE_TO_SHIELD"'),
        ("AI exact full resource", 'return "FULL_RESOURCE_REQUIRED"'),
        ("AI shield to barrier", 'text == "SHIELD_TO_BARRIER"'),
        ("AI shield floor", "protectionConversionMinBps"),
        ("AI rebalance", 'text == "HP_RESOURCE_REBALANCE"'),
        ("AI half target", 'return "AT_REBALANCE_TARGET"'),
        ("AI protection ledger", "protectionLedgerGate"),
        ("AI sustain budget", "sustainBudgetGate"),
    )
    for name, needle in ai_contracts:
        check(name, needle in ai, "bound")

    check("composite rules", "aq.composite-semantic-dispatcher.p5n.v0.1" in composite, "v0.1")
    check("composite hash", COMPOSITE_HASH in composite and COMPOSITE_HASH in document, COMPOSITE_HASH)
    check("eight families", "V_NEXT_P5N_COMPOSITE_FAMILY_COUNT=8" in composite, "8")
    check("forty-nine definitions", "V_NEXT_P5N_COMPOSITE_DEFINITION_COUNT=49" in composite, "49")
    check("P5m bound", "VNextCompositeSemanticDispatcherP5mCompiler.compile" in composite, "45")
    check("P5n bound", "VNextResourceProtectionConversionP5nCompiler.compile" in composite, "4")
    check("overlap gate", "P5N_DUPLICATE_OWNERS" in composite, "closed")
    check("unsupported gate", "P5N_COMPOSITE_UNSUPPORTED_DEFINITION" in composite, "closed")

    for phrase in (
        "coverage binds four conversions one new token and three existing contract layers",
        "blood price gains only current ceiling room and pays one hundred twenty percent hp cost",
        "last fortress requires full resource then grants shared paladin shield",
        "protection conversion level floor consumes all shield and creates one shared barrier",
        "life distribution high branch pays hp for resource and low branch reserves heal ledgers",
        "rebalance target and replay ledger reject without paying twice",
        "encounter end removes rebalance and local protection tokens but keeps expedition budgets",
        "malformed rebalance token and wrong owned class fail before conversion",
    ):
        check(f"adapter test {phrase[:26]}", phrase in adapter_tests, "covered")
    for phrase in (
        "coverage extends forty-five definitions with four conversions across eight families",
        "old direct and new conversion routes preserve complete nested provenance",
        "unsupported definition and conversion class mismatch reject without frame mutation",
        "conversion replay is deterministic and every definition has exactly one family",
    ):
        check(f"composite test {phrase[:24]}", phrase in composite_tests, "covered")
    for phrase in (
        "blood price enters only with resource room and payable hp",
        "last fortress requires full resource and respects paladin shield ledgers",
        "protection conversion level lowers shield floor while barrier ledger stays authoritative",
        "life distribution rejects exact midpoint and low branch consumes heal safeguards",
    ):
        check(f"AI test {phrase[:28]}", phrase in ai_tests, "covered")
    check(
        "resolver conversion",
        "p5n paladin conversion spends full resource once then continues direct combat" in resolver_tests,
        "covered",
    )

    check("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("app unconnected", "VNextCompositeSemanticDispatcherP5n" not in app, "safe")
    check("legacy unconnected", "VNextCompositeSemanticDispatcherP5n" not in settlement, "safe")
    check("legacy resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (432, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint_root = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    lint_errors = sum(issue.attrib.get("severity") == "Error" for issue in lint_root.findall("issue"))
    lint_warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint_root.findall("issue"))
    check("lint", (lint_errors, lint_warnings) == (0, 22), f"{lint_errors}/{lint_warnings}")
    check("APK exists", APK.is_file(), str(APK))
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == APK_SIZE, str(APK.stat().st_size if APK.is_file() else 0))
    for field in (
        "verificationTarget=android-emulator",
        "avdName=alarmquest-qa",
        "androidRelease=15",
        "apiLevel=35",
        "installResult=Success",
        "pmClearResult=Success",
        "launchState=COLD",
        "coldTotalTimeMs=977",
        "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true",
        "androidRuntimeFatalCount=0",
        "engineTests=432",
        "appTests=176",
        "totalTests=608",
        "liveSettlementEnabled=false",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("emulator boot", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying"])
        check("installed version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("activity focus", "topResumedActivity" in focus.stdout and "com.nullplaying/.MainActivity" in focus.stdout, "MainActivity")
        ui = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5n-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5n-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        fatal = "FATAL EXCEPTION" in log or "AndroidRuntime: FATAL" in log
        check("fatal zero", not fatal, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5n PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
