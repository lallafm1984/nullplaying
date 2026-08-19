#!/usr/bin/env python3
"""PD audit for P5o special sustain semantics and emulator evidence."""
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
APK_HASH = "c86a3d0a8a5f59269c73666d778686ae20661974dca288ac72f24a31a97be361"
APK_SIZE = 37_415_545
SPECIAL_HASH = "6b2f15b06edb2c96a7d78c3306ddb36bf9fc6a846b3fade01958ae2c4c2a7ab7"
COMPOSITE_HASH = "185a3d92aa6435170476917a082cb929970cfc89ce442ba6c9585efad69a006c"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSpecialSustainSemanticAdapterP5o.kt": "52180747559ed189d5bf70ee581d1decf79e4ec26e5e5ad5e3c021f5dc62d2df",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5o.kt": "6215b9f1aecf056625fa985cb1393ab89a815c3976276ff3a5148ad5e5937070",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5n.kt": "41de59979db9005ae27374b4133a0bffbb99acd9298f24730457bb216dcee87f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt": "a1687db6647f9226dc4673a83d4b8471aaabe5c34c9afd7c8fdb406ffe378137",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt": "3a49617769a97c61a66059719201b3c990f2ee67bbc7be1be4747bafb6666d51",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextSpecialSustainSemanticAdapterP5oTest.kt": "3d0aaa6d4073147a02ebd83c99d393ec526e15ff6ae42f07f74915d3d6eb6461",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5oTest.kt": "d8e25de5e8a075e6d0f3ae88265256c6237f1622ad0e94d0ad8f058278fb6d63",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt": "8a25b61f53e06aea275662007e2fc478321e282196fc061c8a70b9fd1c0da49f",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt": "4d9d43e1fd3d645a995327c079839cb7d7e8c535a15fb0c2be7d90193132fffc",
    "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt": "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=False)


def totals(module: str) -> tuple[int, int, int, int]:
    result = [0, 0, 0, 0]
    for path in (ROOT / module / "build/test-results").glob("test*/TEST-*.xml"):
        root = ET.parse(path).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            result[index] += int(root.attrib.get(key, 0))
    return tuple(result)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-gradle", action="store_true")
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    if args.with_gradle:
        built = run(["./gradlew", "test", "lintDebug", "assembleDebug"])
        if built.returncode:
            print(built.stdout)
            print(built.stderr, file=sys.stderr)
            return built.returncode

    adapter = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSpecialSustainSemanticAdapterP5o.kt").read_text()
    composite = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5o.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    ai = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt").read_text()
    adapter_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextSpecialSustainSemanticAdapterP5oTest.kt").read_text()
    composite_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5oTest.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    ai_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt").read_text()
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5O_SPECIAL_SUSTAIN_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5o-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("rules", "aq.special-sustain-adapter.p5o.v0.1" in adapter, "v0.1")
    check("special hash", SPECIAL_HASH in adapter and SPECIAL_HASH in document, SPECIAL_HASH)
    check("two definitions", "V_NEXT_P5O_SPECIAL_SUSTAIN_DEFINITION_COUNT = 2" in adapter, "2")
    check("one token", "V_NEXT_P5O_SPECIAL_SUSTAIN_TOKEN_COUNT = 1" in adapter, "1")
    check("live off", "val liveReady: Boolean get() = false" in adapter, "OFF")
    for skill, pattern, owner, family in (
        ("miraclecost", "DOUBLE_TOKEN_HEAL", "CLERIC", "MAX_RESOURCE_SELF_COST"),
        ("coverally", "SHIELD_INTERCEPT", "PALADIN", "SHIELD_INTERCEPT"),
    ):
        check(f"{skill} id", f".{skill}\"" in adapter, skill)
        check(f"{skill} pattern", f'\"{pattern}\"' in adapter, pattern)
        check(f"{skill} owner", f'\"{owner}\"' in adapter, owner)
        check(f"{skill} family", family in adapter, family)
    for name, needle in (
        ("legacy packet drift", "definition.hitPackets == 1 && definition.critEligible"),
        ("ally descriptor", "VNextRuntimeCarrierTarget.LOWEST_HP_ALLY"),
        ("class gate", "ACTOR_CLASS_MISMATCH"),
        ("replay gate", "REPLAY_RECEIPT"),
        ("miracle ledger validation", "MIRACLE_LEDGER_STATE_INVALID"),
        ("miracle expedition cap", "MIRACLE_EXPEDITION_LEDGER_REJECTED"),
        ("two heal tokens", "protection.healEncounter + contract.classTokenCost > 2"),
        ("missing hp", "MISSING_HP_FLOOR_REJECTED"),
        ("cost pipeline", "VNextCostTransferDispelHandlers.execute"),
        ("ceiling seal", "maxResourceSealBps"),
        ("solo half", "declaredBps + contract.soloAmountDivisor - 1"),
        ("sustain pipeline", "VNextProtectionDeathPipeline.grantSustain"),
        ("support sync", "syncSupportLedgerP5k"),
        ("class sync", "syncClassProtectionLedgerP5l"),
        ("miracle sync", "syncMiracleP5o"),
        ("lodging clock", 'clock = "EXPEDITION_LODGING"'),
    ):
        check(name, needle in adapter, "bound")
    for name, needle in (
        ("AI miracle", 'text == "DOUBLE_TOKEN_HEAL"'),
        ("AI miracle threshold", "4_500 + behaviorOffset"),
        ("AI miracle expedition", 'return "MIRACLE_EXPEDITION_LEDGER"'),
        ("AI cover", 'text == "SHIELD_INTERCEPT"'),
        ("AI cover threshold", "6_000 + behaviorOffset"),
        ("AI half budget", "soloNominal"),
    ):
        check(name, needle in ai, "bound")
    check("composite rules", "aq.composite-semantic-dispatcher.p5o.v0.1" in composite, "v0.1")
    check("composite hash", COMPOSITE_HASH in composite and COMPOSITE_HASH in document, COMPOSITE_HASH)
    check("nine families", "V_NEXT_P5O_COMPOSITE_FAMILY_COUNT = 9" in composite, "9")
    check("fifty-one definitions", "V_NEXT_P5O_COMPOSITE_DEFINITION_COUNT = 51" in composite, "51")
    check("P5n bound", "VNextCompositeSemanticDispatcherP5nCompiler.compile" in composite, "49")
    check("P5o bound", "VNextSpecialSustainSemanticAdapterP5oCompiler.compile" in composite, "2")
    check("duplicates closed", "P5O_DUPLICATE_OWNERS" in composite, "closed")
    check("unsupported closed", "P5O_COMPOSITE_UNSUPPORTED_DEFINITION" in composite, "closed")
    for phrase in (
        "coverage binds two pure support definitions to cost protection and descriptor contracts",
        "miracle atomically heals seals resource ceiling and consumes two heal tokens",
        "miracle rejects small missing hp insufficient resource and second expedition use without mutation",
        "cover ally grants half declared shield and shares paladin shield token",
        "cover ally cannot regenerate its class token in the same encounter",
        "encounter end keeps special expedition costs and lodging clears every expedition token",
        "malformed miracle token and wrong class fail closed",
    ):
        check(f"adapter test {phrase[:26]}", phrase in adapter_tests, "covered")
    for phrase in (
        "coverage extends forty-nine definitions with two special sustain definitions",
        "old direct and special heal routes preserve complete nested provenance",
        "unsupported definition and special support class mismatch fail without mutation",
        "special shield replay is deterministic and every definition has one family",
    ):
        check(f"composite test {phrase[:24]}", phrase in composite_tests, "covered")
    for phrase in (
        "miracle conserves its expedition use and requires two free cleric heal tokens",
        "solo cover uses half shield budget and cautious policy moves its threshold",
    ):
        check(f"AI test {phrase[:28]}", phrase in ai_tests, "covered")
    check("resolver integration", "p5o cleric miracle seals ceiling once then continues direct combat" in resolver_tests, "covered")
    check("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("app unconnected", "VNextCompositeSemanticDispatcherP5o" not in app, "safe")
    check("legacy unconnected", "VNextCompositeSemanticDispatcherP5o" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)
    check("engine tests", totals("game-engine") == (446, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK", APK.is_file(), str(APK))
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == APK_SIZE, str(APK.stat().st_size if APK.is_file() else 0))
    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=4885", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=446",
        "appTests=176", "totalTests=622", "liveSettlementEnabled=false",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)
    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("boot", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest"])
        check("version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("focus", "topResumedActivity" in focus.stdout and "com.alarmquest/.MainActivity" in focus.stdout, "MainActivity")
        ui = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5o-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5o-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")
    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5o PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
