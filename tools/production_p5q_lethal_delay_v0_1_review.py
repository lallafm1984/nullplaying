#!/usr/bin/env python3
"""PD audit for P5q lethal-delay semantics and emulator evidence."""
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
APK_HASH = "6fc25e71cd6381a896a0c150b614acbff2b9790e35c43a90147313c75a5858e8"
APK_SIZE = 37_449_293
ADAPTER_HASH = "945f138f3e0b501099a1cc2c8ed9a63f968b7760433f082e2d8e542533521214"
COMPOSITE_HASH = "970d5c9ba79d8042f275d0f8268ea8e56d7614eddaf3d924ecc86c199706cfd2"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLethalDelaySemanticAdapterP5q.kt": "14a4dbc4d772b506ed52d625363f8b4e5e0cb367ef68c518dd78d5aa9be1305b",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5q.kt": "8c07610343817fa24368a2fa6a2942504e85b36c340e5e2688b0d2ca3e3a9e37",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt": "a24cd97c230919ecb0331ea358904b160d52e78247f8c638964f33655804dc48",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt": "455ea22d77bb993e070a8f7584087bd4a8591dc992b6ebc279ac00dc6dfd88ff",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusSystemRootSchedulerP5e.kt": "c0b998372f8e6492e64cd72346cc8a3e1fd7a7801c3029499d43931974b71625",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt": "344eecfbf5cfbb458b1cd3b0bfebbc147b11bfb35c4e24ad2bfe20238598d9c0",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt": "89f702e07e1907be621be3aa3b9d3717baeb5c9525b32598e095372bc190f51c",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextLethalDelaySemanticAdapterP5qTest.kt": "56b781736af2e2cf7d13664776c54f017ab65b3622d46306ed223e9e42a01b82",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5qTest.kt": "95d2c1bab0e3bcae077034326cfccd7dd24d497f29c4e0dad26be0cb250b12fc",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt": "0cde159ebcf2f1a184cc9ca8089073a70dfa725fb2e54a2ab2bfdcefa711cbbc",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt": "79a03f810f2f5a3bee9244c606ad2b8e80a62a9d0c288ffe506d06d5f03db3b6",
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
        "adapter": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLethalDelaySemanticAdapterP5q.kt",
        "composite": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5q.kt",
        "packet": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt",
        "status": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt",
        "scheduler": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusSystemRootSchedulerP5e.kt",
        "resolver": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt",
        "ai": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt",
        "adapter_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextLethalDelaySemanticAdapterP5qTest.kt",
        "composite_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5qTest.kt",
        "resolver_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt",
        "ai_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt",
    }
    text = {key: (ROOT / value).read_text() for key, value in paths.items()}
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5Q_LETHAL_DELAY_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5q-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    adapter = text["adapter"]
    for name, needle in (
        ("rules", "aq.lethal-delay-adapter.p5q.v0.1"),
        ("adapter hash", ADAPTER_HASH),
        ("one definition", "V_NEXT_P5Q_LETHAL_DELAY_DEFINITION_COUNT = 1"),
        ("one reaction", "V_NEXT_P5Q_REACTION_TOKEN_CAP = 1"),
        ("telegraph floor", "V_NEXT_P5Q_PUBLIC_TELEGRAPH_FLOOR_BPS = 13_000"),
        ("skill id", "aq.skill.cleric.w3.soulanchor"),
        ("class", 'request.actorClassId != "CLERIC"'),
        ("pattern", 'it.pattern == "PREPAID_LETHAL_DELAY"'),
        ("growth", 'it.growthField == "deferred_damage_cap_maxhp_bps"'),
        ("legacy drift", "it.hitPackets == 1 && it.critEligible"),
        ("descriptor", "VNextRuntimeCarrierDeliveryV2.MODIFIER"),
        ("prepared handler", "PreparedHandlerId.PREPAID_LETHAL_DELAY"),
        ("death pipeline", 'it.family == "LETHAL_DELAY"'),
        ("shared reaction", "PREPARED_REACTION_TOKEN_OCCUPIED"),
        ("armed token", 'tag = "PREPARED_LETHAL_DELAY"'),
        ("deferred token", 'tag = "DEFERRED_LETHAL_DAMAGE"'),
        ("self root", 'clock = "SELF_ROOT_END"'),
        ("CAS", "TOKEN_CAS_REJECTED"),
        ("overkill cap", "offeredHpDamage - deferred"),
        ("cap insufficient", "P5Q:TRIGGER:CAP_INSUFFICIENT"),
        ("COST settlement", "P5Q:SETTLE:COST"),
        ("strict codec", "VNextStatusStateCodecP5c.encode"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in adapter)
    check("adapter hash document", ADAPTER_HASH in document, ADAPTER_HASH)
    for name, source, needle in (
        ("offered damage field", "packet", "val offeredHpDamage: Int"),
        ("before hp clamp", "packet", "val offeredHpDamage = remaining"),
        ("DOT extension", "status", "lethalDelayCapBps: Int = 0"),
        ("DOT trigger", "status", "lethalDelayTriggered = true"),
        ("DOT defer", "status", "deferredDamage = candidate"),
        ("scheduler cap", "scheduler", "lethalDelayCapBps = request.lethalDelayCapBps"),
        ("scheduler receipt", "scheduler", '"P5Q:DEFER=${tick.deferredDamage}"'),
        ("resolver periodic prepare", "resolver", "VNextLethalDelayRuntimeP5q.prepare(ownerState)"),
        ("resolver periodic commit", "resolver", "commitPeriodicTrigger"),
        ("resolver direct offered", "resolver", "offeredHpDamage = result.offeredHpDamage"),
        ("resolver direct intercept", "resolver", "interceptPacket"),
        ("resolver settle", "resolver", "settleLethalDelay"),
        ("resolver settlement action", "resolver", 'actionId = "P5Q_SOUL_ANCHOR_SETTLEMENT"'),
        ("death priority", "resolver", "lethalDelaySettlementDeath"),
        ("AI pattern", "ai", 'text == "PREPAID_LETHAL_DELAY"'),
        ("AI public", "ai", 'return "NO_LETHAL_PUBLIC_TELEGRAPH"'),
        ("AI hp window", "ai", 'return "LETHAL_DELAY_HP_WINDOW"'),
        ("AI preparation priority", "ai", 'return "LETHAL_DELAY_PREPARATION" to 925'),
        ("AI heal priority", "ai", 'return "DEFERRED_DAMAGE_RECOVERY" to 950'),
    ):
        check(name, needle in text[source])
    composite = text["composite"]
    for name, needle in (
        ("composite rules", "aq.composite-semantic-dispatcher.p5q.v0.1"),
        ("composite hash", COMPOSITE_HASH),
        ("families", "V_NEXT_P5Q_COMPOSITE_FAMILY_COUNT = 11"),
        ("definitions", "V_NEXT_P5Q_COMPOSITE_DEFINITION_COUNT = 54"),
        ("prior", "VNextCompositeSemanticDispatcherP5pCompiler.compile"),
        ("added", "VNextLethalDelaySemanticAdapterP5qCompiler.compile"),
        ("duplicate closed", "P5Q_DUPLICATE_OWNERS"),
        ("unsupported closed", "P5Q_COMPOSITE_UNSUPPORTED_DEFINITION"),
    ):
        check(name, needle in composite)
    check("composite hash document", COMPOSITE_HASH in document, COMPOSITE_HASH)
    for phrase in (
        "coverage binds one lethal delay to descriptor prepared and death contracts",
        "soul anchor arms a level scaled token only for a strong public threat",
        "nonlethal packet waits and saved lethal packet becomes one deferred cost",
        "damage above cap still kills and cannot leave a deferred token",
        "healing before self root settlement can pay the deferred bypass cost",
        "periodic lethal packet uses the same cap and commits one deferred cost",
        "packet exposes post protection damage before current hp clamp",
        "unused token expires on the next eligible self root end",
        "shared prepared occupancy malformed state and wrong class fail closed",
    ):
        check(f"adapter test {phrase[:28]}", phrase in text["adapter_tests"], "covered")
    for phrase in (
        "coverage extends fifty-three definitions with one lethal delay",
        "old prepared defense and lethal delay preserve complete route provenance",
        "unsupported definition and wrong lethal delay class fail without mutation",
        "lethal delay replay is deterministic and every definition has one family",
    ):
        check(f"composite test {phrase[:26]}", phrase in text["composite_tests"], "covered")
    for phrase in (
        "soul anchor needs low hp strong public threat and an empty reaction token",
        "deferred lethal damage makes an eligible heal outrank attack before settlement",
    ):
        check(f"AI test {phrase[:30]}", phrase in text["ai_tests"], "covered")
    for phrase in (
        "p5q cleric delays a lethal monster packet heals and pays cost at self root end",
        "p5q settlement death overrides simultaneous monster defeat and blocks reward",
        "p5q seeded periodic lethal packet triggers before action and settles after that hero root",
    ):
        check(f"resolver test {phrase[:28]}", phrase in text["resolver_tests"], "covered")
    resolver = text["resolver"]
    check("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("app unconnected", "VNextCompositeSemanticDispatcherP5q" not in app, "safe")
    check("legacy unconnected", "VNextCompositeSemanticDispatcherP5q" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)
    check("engine tests", totals("game-engine") == (479, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK", APK.is_file())
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == APK_SIZE, str(APK.stat().st_size if APK.is_file() else 0))
    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=5819", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=479",
        "appTests=176", "totalTests=655", "liveSettlementEnabled=false",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)
    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("boot", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying"])
        check("version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("focus", "topResumedActivity" in focus.stdout and "com.nullplaying/.MainActivity" in focus.stdout, "MainActivity")
        ui = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5q-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5q-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")
    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5q PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
