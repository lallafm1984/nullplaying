#!/usr/bin/env python3
"""PD audit for P5v delayed-cast semantics, passive stability, and emulator evidence."""
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
SCREENSHOT = ROOT / "artifacts/audit/production-p5v-emulator-screen-v0.1.png"
UI_DUMP = ROOT / "artifacts/audit/production-p5v-emulator-ui-v0.1.xml"
APK_HASH = "54a9c3ece7e2b5fd438f6fbfce4bed0443e8a4bea09ae98a4090ec06a73254e7"
SCREENSHOT_HASH = "b1ecd667944d584028517475ba975cf1447d7a3f50d8c7778a55c2fba7c718ac"
UI_DUMP_HASH = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
ADAPTER_HASH = "5eabc27f7fc19bdb6dd4997ca7b4d4c98a36a994c9f3f9b19e03213f73d5300f"
COMPOSITE_HASH = "907dfc128dd4e40e75ae318568218cc6ea497ed06149b5a85874f8b14e683665"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedCastSemanticAdapterP5v.kt": "a89a1839fe88c60e81e67d2d7f91d2bafc23288097e50b512029b2aa123e7c3d",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5v.kt": "3a4bb8d8b2793e2a40516efa095c0cf5217517a1953814c9781a0f75f6ab49a6",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedDefenseSemanticAdapterP5p.kt": "3a1b643f784ea927f9cf5e33ea6fe2bf7b918d1dd6e37aec8c4df08daf86ac9f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLethalDelaySemanticAdapterP5q.kt": "322bd02dfc94f0f393dbacd3b21a86c6196e6aa47d79064c84d986fef12ab67c",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMissCounterSemanticAdapterP5r.kt": "f9201bd6e7cfe96f5a3d545da6a2b5ca5e53a15078d442077cc66b0991a0f181",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSpellReflectSemanticAdapterP5s.kt": "ef1e545586f0399f84f5930e04c4e9bcb9fdbafb350ded6e811834dc3ec231b4",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedControlSemanticAdapterP5t.kt": "8b812a0894a6cb1154a8f0752311d65406e83fe10f3f9e019009cdf802d5341f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedOwnRootSemanticAdapterP5u.kt": "26d4edb62b915c8af2fd51bb3890fba5faaef53a5af4795ec0a58c971edd0221",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt": "1266285dfe95f3f30298ab9ef2bff0dde679618d75d814d7732658d05b4bf82f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt": "9295ae42d45312a59f6a2833d1dffa9379cce462d3e1ab5e5606ecb88866da2e",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedCastSemanticAdapterP5vTest.kt": "7509af535baa889fef0e34ae4be6ab66a5a7d03fced6477d9fa56634b31e8153",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5vTest.kt": "e64fca78f726bf02dc53ae53b524a32e4aff8e0e7761199de097fdf38a4763cc",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt": "70bdc316f35d6284c9d07c7bd222237695669d263aa2c0db1e83ae2851615583",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt": "623c992d0fa9c36ea73adfca7d28d9d05895d499965fcaf62d4d44063d841841",
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

    paths = {
        "adapter": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedCastSemanticAdapterP5v.kt",
        "composite": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5v.kt",
        "resolver": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt",
        "ai": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt",
        "adapter_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedCastSemanticAdapterP5vTest.kt",
        "composite_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5vTest.kt",
        "resolver_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt",
        "ai_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt",
    }
    text = {key: (ROOT / value).read_text() for key, value in paths.items()}
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5V_DELAYED_CAST_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5v-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    adapter = text["adapter"]
    for name, needle in (
        ("rules", "aq.delayed-cast-adapter.p5v.v0.1"),
        ("adapter hash", ADAPTER_HASH),
        ("starfall", "aq.skill.mage.w3.starfallvow"),
        ("mage owner", 'request.actorClassId != "MAGE"'),
        ("coefficient", "19_000..23_000"),
        ("resource cost", "request.actor.resourceBps - cost"),
        ("magical", "VNextRuntimeDamageChannelV2.MAGICAL"),
        ("delayed", "VNextRuntimeCarrierDeliveryV2.DELAYED"),
        ("prepared handler", "PreparedHandlerId.DELAYED_CAST_PACKET"),
        ("next root", 'it.triggerId == "NEXT_HERO_ROOT"'),
        ("base cancel", "V_NEXT_STARFALL_CANCEL_BASE_BPS"),
        ("floor cancel", "V_NEXT_STARFALL_CANCEL_FLOOR_BPS"),
        ("three passives", "V_NEXT_P5V_DELAY_STABILITY_DEFINITION_COUNT = 3"),
        ("bell", "aq.skill.external.w3.bellpatience"),
        ("concentration", "aq.skill.mage.w3.concentration"),
        ("hunter", "aq.skill.ranger.w3.hunterpatience"),
        ("highest", "delayStabilityBps"),
        ("stability cap", "V_NEXT_DELAY_STABILITY_CAP_BPS"),
        ("unsupported passive closed", "PASSIVE_ADAPTERS_REQUIRED"),
        ("owner closed", "PASSIVE_OWNER_CLASS_MISMATCH"),
        ("prepared tag", "PREPARED_DELAYED_CAST"),
        ("cancelled tag", "CANCELLED_DELAYED_CAST"),
        ("strict codec", "VNextStatusStateCodecP5c.encode"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in adapter)
    for name, needle in (
        ("incoming prepare", "prepareIncomingHit"),
        ("one incoming commit", "commitAfterEnemyAction"),
        ("hit gate", "!plan.pending || !enemyHit"),
        ("strict roll", "rollBps < plan.cancelChanceBps"),
        ("cancel marker", "tag = if (cancelled)"),
        ("marker survives", "nextRootConsumed=true"),
        ("hero root", "commitAtHeroRoot"),
        ("stagger silence", 'setOf("STAGGER", "SILENCE")'),
        ("forced root", "ownsRoot = true"),
        ("no fallback", "basicFallback=false"),
        ("no refund", "refund=0"),
        ("CAS", "TOKEN_CAS_REJECTED"),
        ("replay", "REPLAY_RECEIPT"),
    ):
        check(name, needle in adapter)
    check("adapter hash document", ADAPTER_HASH in document, ADAPTER_HASH)

    resolver = text["resolver"]
    for name, needle in (
        ("hero plan", "VNextDelayedCastRuntimeP5v.prepareHeroRoot(heroStatus)"),
        ("forced branch", "else if (delayedCastPlan.pending)"),
        ("hero commit", "VNextDelayedCastRuntimeP5v.commitAtHeroRoot"),
        ("incoming plan", "VNextDelayedCastRuntimeP5v.prepareIncomingHit(heroStatus)"),
        ("keyed roll", '"P5V_STARFALL_CANCEL"'),
        ("incoming commit", "VNextDelayedCastRuntimeP5v.commitAfterEnemyAction"),
        ("roll receipt", '"P5V_DELAYED_CAST_CANCEL_ROLL"'),
        ("cancel receipt", '"DELAYED_CAST_CANCELLED"'),
        ("resolve receipt", '"DELAYED_CAST_RESOLUTION"'),
        ("magical packet", 'damageType = "MAGICAL"'),
        ("delayed packet", 'delivery = "DELAYED"'),
        ("charge resolution", '"p5v-charge-consumed"'),
        ("prepare no charge", '"DELAYED_BURST", "DELAYED_CLAMP_MAX", "DELAYED_CAST"'),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)
    forced_start = resolver.index("else if (delayedCastPlan.pending)")
    forced_end = resolver.index("val decision = VNextAutoBattleAiPolicy.chooseAction", forced_start)
    forced = resolver[forced_start:forced_end]
    check("forced bypass AI", "chooseAction" not in forced, "none")
    check("cancel no BASIC", '"BASIC"' not in forced, "none")
    check("no cooldown at resolution", "heroCooldowns = heroCooldowns +" not in forced, "none")

    ai = text["ai"]
    for name, needle in (
        ("AI delayed cast", 'skill.pattern in setOf("DELAYED_BURST", "DELAYED_CLAMP_MAX", "DELAYED_CAST")'),
        ("AI occupancy", 'return "PREPARED_OR_DELAYED_TOKEN_OCCUPIED"'),
        ("AI reason", 'skill.pattern == "DELAYED_CAST"'),
        ("AI prepared visible", '"PREPARED_DELAYED_CAST"'),
        ("AI cancelled visible", '"CANCELLED_DELAYED_CAST"'),
    ):
        check(name, needle in ai)

    composite = text["composite"]
    for name, needle in (
        ("composite rules", "aq.composite-semantic-dispatcher.p5v.v0.1"),
        ("composite hash", COMPOSITE_HASH),
        ("families", "V_NEXT_P5V_COMPOSITE_FAMILY_COUNT = 16"),
        ("definitions", "V_NEXT_P5V_COMPOSITE_DEFINITION_COUNT = 61"),
        ("prior", "VNextCompositeSemanticDispatcherP5uCompiler.compile"),
        ("added", "VNextDelayedCastSemanticAdapterP5vCompiler.compile"),
        ("stability contracts", "stabilityContracts"),
        ("duplicate closed", "P5V_DUPLICATE_OWNERS"),
        ("unsupported closed", "P5V_COMPOSITE_UNSUPPORTED_DEFINITION"),
    ):
        check(name, needle in composite)
    check("composite hash document", COMPOSITE_HASH in document, COMPOSITE_HASH)

    for phrase in (
        "coverage pins starfall and all three delay stability contracts",
        "starfall prepays an empty root and stores level coefficient with no passive stability",
        "eligible passive stability uses highest value and preserves twenty percent floor",
        "unsupported passive and wrong class stability fail before cost commit",
        "only an actual enemy hit performs one strict cancellation roll",
        "incoming hit cancellation marker consumes the next hero root without basic fallback",
        "a sustained cast resolves the exact magical coefficient on next root",
        "stagger or silence cancels at forced root with no refund",
        "incoming and hero root CAS plus receipts reject replays deterministically",
        "unused prepared or cancelled casts expire at encounter end",
    ):
        check(f"adapter test {phrase[:33]}", phrase in text["adapter_tests"], "covered")
    for phrase in (
        "coverage extends sixty actives with starfall and three stability contracts",
        "prior own root and starfall retain complete route provenance",
        "unsupported definition and wrong starfall class fail without mutation",
        "starfall replay is deterministic and every active has one family",
    ):
        check(f"composite test {phrase[:31]}", phrase in text["composite_tests"], "covered")
    check(
        "AI test",
        "delayed cast is selected without telegraph and rejects prepared or cancelled occupancy" in text["ai_tests"],
        "covered",
    )
    for phrase in (
        "p5v starfall applies highest passive stability and one hit cancel roll before forced root",
        "p5v cancelled marker survives save state and consumes exactly one hero root",
    ):
        check(f"resolver test {phrase[:34]}", phrase in text["resolver_tests"], "covered")
    check("one roll proof", "cancelRollsAtEnemyRoot.size" in text["resolver_tests"], "covered")
    check("charge waits proof", "it.actionIndex == armed.actionIndex" in text["resolver_tests"], "covered")

    check("app unconnected", "VNextCompositeSemanticDispatcherP5v" not in app, "safe")
    check("legacy unconnected", "VNextCompositeSemanticDispatcherP5v" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)
    check("engine tests", totals("game-engine") == (555, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == 37_527_274, str(APK.stat().st_size if APK.is_file() else 0))
    check("screenshot hash", SCREENSHOT.is_file() and sha(SCREENSHOT) == SCREENSHOT_HASH, sha(SCREENSHOT) if SCREENSHOT.is_file() else "missing")
    check("UI dump hash", UI_DUMP.is_file() and sha(UI_DUMP) == UI_DUMP_HASH, sha(UI_DUMP) if UI_DUMP.is_file() else "missing")
    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=5009", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=555", "appTests=176",
        "totalTests=731", "activeDefinitionsExecutable=61", "semanticFamilies=16",
        "delayStabilityContracts=3", "featureDefaultEnabled=false", "liveSettlementEnabled=false",
        f"emulatorScreenshotSha256={SCREENSHOT_HASH}", f"emulatorUiDumpSha256={UI_DUMP_HASH}",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)
    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and "device" in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("boot", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest"])
        check("version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        check("target sdk", "targetSdk=36" in version.stdout, "36")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("focus", "topResumedActivity" in focus.stdout and "com.alarmquest/.MainActivity" in focus.stdout, "MainActivity")
        ui = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5v-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5v-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")
    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5v PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
