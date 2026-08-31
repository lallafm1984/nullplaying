#!/usr/bin/env python3
"""PD audit for the P5w exhaustive passive event catalog and emulator evidence."""
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
SCREENSHOT = ROOT / "artifacts/audit/production-p5w-emulator-screen-v0.1.png"
UI_DUMP = ROOT / "artifacts/audit/production-p5w-emulator-ui-v0.1.xml"
APK_HASH = "1b2f9cfea25d23980686a26525ce9ad8764ea6878fb97bcce4ca74975309b948"
SCREENSHOT_HASH = "e67fbe0bf75c19abb097f6583a90eb2880c1c9ea1c155bb2f222dde674bbecb9"
UI_DUMP_HASH = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
CATALOG_HASH = "d68d4d94a01256b1289685991bd589545a8d30f02891d1119b9a5d9f8522b511"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPassiveEventCatalogP5w.kt": "d372626a96828f65694863303766ec1255255b5497d9eea7c31e48da4cb0b4fc",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextPassiveEventCatalogP5wTest.kt": "8ca314a07d5ee0a0e72db70a4f18667f3e7efe8641fa62533b477444c87a867f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt": "8fd94f9a30a5ea07e720ea01d29284b7f38aadfcee9b9051d52dc1643a2a29b2",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt": "bce5fb1dba972788829383becbd5c3fc57bf86cc2cc9a1775671a8ddd04b34b8",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedCastSemanticAdapterP5v.kt": "a89a1839fe88c60e81e67d2d7f91d2bafc23288097e50b512029b2aa123e7c3d",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5v.kt": "3a4bb8d8b2793e2a40516efa095c0cf5217517a1953814c9781a0f75f6ab49a6",
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

    catalog = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPassiveEventCatalogP5w.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextPassiveEventCatalogP5wTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5W_PASSIVE_EVENT_CATALOG_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5w-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    for name, needle in (
        ("rules", "aq.passive-event-catalog.p5w.v0.1"),
        ("catalog hash", CATALOG_HASH),
        ("ninety definitions", "V_NEXT_P5W_PASSIVE_DEFINITION_COUNT = 90"),
        ("seventy six patterns", "V_NEXT_P5W_PASSIVE_PATTERN_COUNT = 76"),
        ("eighty conditions", "V_NEXT_P5W_PASSIVE_CONDITION_COUNT = 80"),
        ("three slots", "V_NEXT_P5W_PASSIVE_SLOT_CAP = 3"),
        ("build event", "BUILD_COMMIT"),
        ("expedition event", "EXPEDITION_CONTEXT"),
        ("root event", "HERO_ROOT_START"),
        ("before cost", "BEFORE_ACTIVE_COST"),
        ("after cost", "AFTER_ACTIVE_COST"),
        ("before outgoing", "BEFORE_OUTGOING_PACKET"),
        ("after outgoing", "AFTER_OUTGOING_PACKET"),
        ("before incoming", "BEFORE_INCOMING_PACKET"),
        ("after incoming", "AFTER_INCOMING_PACKET"),
        ("status transition", "STATUS_TRANSITION"),
        ("resource transition", "RESOURCE_TRANSITION"),
        ("encounter end", "ENCOUNTER_END"),
        ("four stack policies", "P5wPassiveStackPolicy"),
        ("slot cap rejection", "SLOT_CAP_EXCEEDED"),
        ("duplicate rejection", "DUPLICATE_DEFINITION"),
        ("unknown rejection", "DEFINITION_UNSUPPORTED"),
        ("owner rejection", "OWNER_SCOPE_MISMATCH"),
        ("plan mismatch", "EXECUTION_PLAN_MISMATCH"),
        ("definition hash", "definitionContentHash"),
        ("slot order hash", "passivePlans.forEach"),
        ("event order", "toSortedMap(compareBy(P5wPassiveEvent::ordinal))"),
        ("known conditions", "knownConditionIds"),
        ("unknown conditions", "unknownConditionIds"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in catalog)
    check("catalog hash documented", CATALOG_HASH in document, CATALOG_HASH)

    for phrase in (
        "coverage classifies all ninety passives without proxy or unknown condition",
        "every definition retains exact registry fields and one explicit event",
        "representative build cost attack hit and boundary contracts route to fixed events",
        "three slot mage loadout is admitted with deterministic event index and hash",
        "slot order is user state and changes snapshot identity without changing event order",
        "duplicate over cap and wrong owner loadouts fail closed",
        "tampered semantic plan is rejected and empty loadout remains valid",
        "event queries are lexically deterministic and unknown ids have no fallback",
    ):
        check(f"catalog test {phrase[:35]}", phrase in tests, "covered")

    for name, needle in (
        ("resolver compiler", "VNextPassiveEventCatalogP5wCompiler.compile(registry)"),
        ("resolver admission", "passiveEventCatalog.admitLoadout"),
        ("resolver failure", "PASSIVE_EVENT_ADMISSION_REJECTED"),
        ("catalog transaction field", "passiveEventCatalogHash"),
        ("loadout transaction field", "passiveLoadoutHash"),
        ("transaction hash binding", "append(transaction.passiveEventCatalogHash)"),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)
    check(
        "resolver three slot test",
        "p5w admits a full three passive loadout before combat and hashes its event snapshot" in resolver_tests,
        "covered",
    )
    check("resolver expected hash", "expected.snapshotHash" in resolver_tests, "covered")
    check("app unconnected", "VNextPassiveEventCatalogP5w" not in app, "safe")
    check("legacy unconnected", "VNextPassiveEventCatalogP5w" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")

    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)
    check("engine tests", totals("game-engine") == (564, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == 37_546_207, str(APK.stat().st_size if APK.is_file() else 0))
    check("screenshot hash", SCREENSHOT.is_file() and sha(SCREENSHOT) == SCREENSHOT_HASH, sha(SCREENSHOT) if SCREENSHOT.is_file() else "missing")
    check("UI dump hash", UI_DUMP.is_file() and sha(UI_DUMP) == UI_DUMP_HASH, sha(UI_DUMP) if UI_DUMP.is_file() else "missing")
    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=5501", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=564", "appTests=176",
        "totalTests=740", "passiveDefinitionsClassified=90", "passivePatternsClassified=76",
        "passiveConditionsClassified=80", "passiveSlotCap=3", "passiveEffectsEnabled=0",
        "activeDefinitionsExecutable=61", "semanticFamilies=16", "featureDefaultEnabled=false",
        "liveSettlementEnabled=false", f"emulatorScreenshotSha256={SCREENSHOT_HASH}",
        f"emulatorUiDumpSha256={UI_DUMP_HASH}",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and "device" in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("boot", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying"])
        check("version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        check("target sdk", "targetSdk=36" in version.stdout, "36")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("focus", "topResumedActivity" in focus.stdout and "com.nullplaying/.MainActivity" in focus.stdout, "MainActivity")
        ui = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5w-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5w-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5w PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
