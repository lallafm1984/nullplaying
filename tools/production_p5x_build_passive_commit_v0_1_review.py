#!/usr/bin/env python3
"""PD audit for P5x BUILD_COMMIT passive effects and emulator evidence."""
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
SCREENSHOT = ROOT / "artifacts/audit/production-p5x-emulator-screen-v0.1.png"
UI_DUMP = ROOT / "artifacts/audit/production-p5x-emulator-ui-v0.1.xml"
APK_HASH = "af3322858d548ad43a39fbe8c8b9a9be393a22ca01225be5c49cdeea81c6ab8d"
SCREENSHOT_HASH = "f9e866652847901e69e027ee382deb350e3e622b84c0e4576bca4aea787a9556"
UI_DUMP_HASH = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
CONTRACT_HASH = "4af100881f8bdc4a943ea057e8bff2caf4e34ae276d6fca4478fa3bf045761c6"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBuildPassiveCommitP5x.kt":
        "7c8db555764aa81e39a0aeb200d11157dfa561fcfa4c2267ffddf9cd44cea2c0",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBuildPassiveCommitP5xTest.kt":
        "fb4209d274a5b0dec62156f3316eb8176525a3ba1c16f44112883ddbba15111f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "763514b21d95afc8b3c2fd889dce1e70d5be27c2f25c22f5e204ebdef44f5ffc",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "375f46c703ac1b6aceb5c089c3cc06781d733c0addee155fb257df5ac53e8dc7",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPassiveEventCatalogP5w.kt":
        "d372626a96828f65694863303766ec1255255b5497d9eea7c31e48da4cb0b4fc",
    "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt":
        "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
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

    adapter = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBuildPassiveCommitP5x.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBuildPassiveCommitP5xTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5X_BUILD_PASSIVE_COMMIT_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5x-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    for name, needle in (
        ("rules", "aq.build-passive-commit.p5x.v0.1"),
        ("contract hash", CONTRACT_HASH),
        ("five definitions", "V_NEXT_P5X_BUILD_PASSIVE_DEFINITION_COUNT = 5"),
        ("armor none", "NONE"),
        ("armor light", "LIGHT"),
        ("armor heavy", "HEAVY"),
        ("giant heart", "aq.skill.boss.w7.giantslowheart"),
        ("stone bones", "aq.skill.monster.w7.stonebones"),
        ("plate prayer", "aq.skill.paladin.p2.plateprayer"),
        ("light footwork", "aq.skill.rogue.p2.lightfootwork"),
        ("heavy mastery", "aq.skill.warrior.p2.heavyarmormastery"),
        ("giant hp", "maxHpAdd += plan.resolvedAnchorValue"),
        ("giant pdef", "physicalDefense += 300"),
        ("giant speed cost", "speed -= 1_000"),
        ("stone speed cost", "speed -= 700"),
        ("stone evasion cost", "evasion -= 300"),
        ("armor speed cost", "speed -= 300"),
        ("light pdef cost", "physicalDefense -= 300"),
        ("hp no heal", "hp = baseFrame.hp.coerceAtMost(maxHpAfter)"),
        ("applied list", "appliedDefinitionIds"),
        ("inactive list", "inactiveDefinitionIds"),
        ("snapshot hash", "snapshotHash"),
        ("p5w admission", "passiveCatalog.admitLoadout"),
        ("build event", "P5wPassiveEvent.BUILD_COMMIT"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in adapter)
    check("contract documented", CONTRACT_HASH in document, CONTRACT_HASH)

    for phrase in (
        "coverage pins five build stat passives with exact events and tradeoffs",
        "giant heart adds max hp and pdef but never heals and pays speed penalty",
        "giant and stone combine fixed penalties while stone pdef remains highest contract",
        "heavy armor mastery activates only on committed heavy profile",
        "plate prayer and light footwork apply benefit and fixed cost atomically",
        "full three slot warrior build commits all eligible changes once",
        "unrelated passive remains admitted but build commit does not invent an effect",
        "wrong owner and replay inputs fail or hash deterministically",
    ):
        check(f"adapter test {phrase[:38]}", phrase in tests, "covered")

    for name, needle in (
        ("request armor", "val armorProfile: P5xArmorProfile = P5xArmorProfile.NONE"),
        ("commit compiler", "VNextBuildPassiveCommitP5xCompiler.compile(registry)"),
        ("commit call", "buildPassiveCommitter.commit"),
        ("fail closed", "PASSIVE_BUILD_COMMIT_REJECTED"),
        ("start frame", "var hero = buildSnapshot.frameAfter"),
        ("initiative frame", "buildSnapshot.frameAfter.speed > monsterStart.speed"),
        ("transaction armor", "val armorProfile: P5xArmorProfile"),
        ("transaction snapshot", "val passiveBuildSnapshotHash: String"),
        ("transaction applied", "val appliedBuildPassiveIds: List<String>"),
        ("transaction inactive", "val inactiveBuildPassiveIds: List<String>"),
        ("transaction hash armor", "append(transaction.armorProfile)"),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)
    check(
        "resolver integration test",
        "p5x commits three build passives before initiative without granting hp" in resolver_tests,
        "covered",
    )
    for needle in (
        "assertEquals(11_000, first.finalHero.maxHp)",
        "assertTrue(first.finalHero.hp <= 10_000)",
        "assertEquals(8_000, first.finalHero.speed)",
        "assertEquals(VNextCombatSide.MONSTER, first.initiativeOrder.first())",
    ):
        check(f"resolver assertion {needle[:34]}", needle in resolver_tests, "covered")

    check("app unconnected", "VNextBuildPassiveCommitP5x" not in app, "safe")
    check("legacy unconnected", "VNextBuildPassiveCommitP5x" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (573, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == 37_557_918, str(APK.stat().st_size if APK.is_file() else 0))
    check("screenshot hash", SCREENSHOT.is_file() and sha(SCREENSHOT) == SCREENSHOT_HASH, sha(SCREENSHOT) if SCREENSHOT.is_file() else "missing")
    check("UI dump hash", UI_DUMP.is_file() and sha(UI_DUMP) == UI_DUMP_HASH, sha(UI_DUMP) if UI_DUMP.is_file() else "missing")

    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=4276", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=573", "appTests=176",
        "totalTests=749", "buildPassiveDefinitionsEnabled=5", "remainingPassiveEffectsEnabled=0",
        "buildCommitBeforeInitiative=true", "maxHpIncreaseHeals=false",
        "armorProfiles=NONE,LIGHT,HEAVY", "visualAssetRequired=false",
        "featureDefaultEnabled=false", "liveSettlementEnabled=false",
        f"emulatorScreenshotSha256={SCREENSHOT_HASH}", f"emulatorUiDumpSha256={UI_DUMP_HASH}",
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
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5x-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5x-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5x PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
