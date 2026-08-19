#!/usr/bin/env python3
"""PD audit for P5u delayed-own-root semantics and emulator evidence."""
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
SCREENSHOT = ROOT / "artifacts/audit/production-p5u-emulator-screen-v0.1.png"
APK_HASH = "1e3ee81545c37c750846f60678787565d3c555b058fcf43c3adb5d82c3fe7f4c"
SCREENSHOT_HASH = "44d7f7263b18c3a6b3e1b3c611084205825f99cd92feaa3dfcced3e74865ba35"
ADAPTER_HASH = "e40e9044e12518c18f2e6dcdfefa02a9d7193b7b4d8de9a50494ebd0b789ceb7"
COMPOSITE_HASH = "a0c37a53cec6b888490793d9896e4a1e09aa6f4787dcec4893060541b00c4af5"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedOwnRootSemanticAdapterP5u.kt": "8b6ca09a1006d9b9db653e80037ccf5b8f7e126eb992daa073b5b3163152b589",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5u.kt": "b77a755ae823475f9c46aa9a97f856cc25282bc1a77b8de7785e520cea8241ba",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedDefenseSemanticAdapterP5p.kt": "06a375d44a829c530c01752df55a35e258c010f84fb7042a7e14be4623751ed9",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLethalDelaySemanticAdapterP5q.kt": "f3423239038c44c7976223357f40eb73d338c5781acfd995902de299d3a8b80e",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMissCounterSemanticAdapterP5r.kt": "5c1a5f5851c018adf4edf69c1e5ccb47689b215db3e3c024d84fd73830718d84",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSpellReflectSemanticAdapterP5s.kt": "19794d251977ddd42ff831edd10d37f62880775074027ff9228c1576627a0d7a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedControlSemanticAdapterP5t.kt": "800e24411edeebda320f7c218a02e63766f0d3981947a978089d5592e0ec4fa6",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt": "430f64680b32aabb0b6f8e727a9af4fdda689a7b3a27f4ffe8edb4835bf0e244",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt": "c454a63fbdc3ec86dfbd6fddc2e94121ee5a00f6d474bc4c24724ab793b5750b",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedOwnRootSemanticAdapterP5uTest.kt": "b800ddd04c464d8721fe16f33246d1305e6debd646d6687942151075b98f1d44",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5uTest.kt": "fd937e402559913c6c5b18917436f380089f0aa5dc390ade6030813ec45f0315",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt": "544d3e61a3c6eff391be9d6bf4369144aeb6052c26fe9f2edbc2b7be230ea021",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt": "7e7ed98160d880548dab084b691370346dda882fd8aaabe36bd9bff392bb359d",
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
        "adapter": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedOwnRootSemanticAdapterP5u.kt",
        "composite": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5u.kt",
        "resolver": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt",
        "ai": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt",
        "adapter_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDelayedOwnRootSemanticAdapterP5uTest.kt",
        "composite_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5uTest.kt",
        "resolver_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt",
        "ai_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt",
    }
    text = {key: (ROOT / value).read_text() for key, value in paths.items()}
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5U_DELAYED_OWN_ROOT_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5u-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    adapter = text["adapter"]
    for name, needle in (
        ("rules", "aq.delayed-own-root-adapter.p5u.v0.1"),
        ("adapter hash", ADAPTER_HASH),
        ("two definitions", "V_NEXT_P5U_DELAYED_OWN_ROOT_DEFINITION_COUNT = 2"),
        ("one token", "V_NEXT_P5U_DELAY_TOKEN_CAP = 1"),
        ("scabbard", "aq.skill.external.w3.namelessscabbard"),
        ("smash", "aq.skill.warrior.w2.preparedsmash"),
        ("all owner", '"ALL"'),
        ("warrior owner", '"WARRIOR"'),
        ("delayed clamp", '"DELAYED_CLAMP_MAX"'),
        ("delayed burst", '"DELAYED_BURST"'),
        ("scabbard range", "16_000..20_000"),
        ("smash range", "18_000..20_000"),
        ("clamp max legacy", '"CLAMP_MAX"'),
        ("clamp min legacy", '"CLAMP_MIN"'),
        ("hit 9900", "9_900"),
        ("hit 6500", "6_500"),
        ("forbidden crit", "VNextPacketCriticalPolicy.FORBIDDEN"),
        ("derived crit", "VNextPacketCriticalPolicy.DERIVED"),
        ("attack equivalent", "it.attackEquivalentValues == it.anchorValues"),
        ("cooldown", "it.cooldownRoots == 5"),
        ("physical", "VNextRuntimeDamageChannelV2.PHYSICAL"),
        ("delayed delivery", "VNextRuntimeCarrierDeliveryV2.DELAYED"),
        ("prepared handler", "PreparedHandlerId.DELAYED_OWN_ROOT_PACKET"),
        ("next root trigger", 'it.triggerId == "NEXT_HERO_ROOT"'),
        ("two actor roots", "it.actorRoots == 2"),
        ("no refund", "it.refundBps == 0"),
        ("token id", 'TOKEN_INSTANCE_ID = "prepared.delayed-own-root"'),
        ("token tag", 'tag = "PREPARED_DELAYED_OWN_ROOT"'),
        ("resource prepay", "request.actor.resourceBps - cost"),
        ("zero immediate damage", "statusStateChanged = true"),
        ("shared occupancy", "PREPARED_OR_DELAYED_TOKEN_OCCUPIED"),
        ("strict codec", "VNextStatusStateCodecP5c.encode"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in adapter)

    for name, needle in (
        ("runtime prepare", "VNextDelayedOwnRootRuntimeP5u"),
        ("root commit", "commitAtHeroRoot"),
        ("token CAS", "TOKEN_CAS_REJECTED"),
        ("replay", "REPLAY_RECEIPT"),
        ("interrupt stagger", 'setOf("STAGGER", "SILENCE")'),
        ("forced root", "ownsRoot = true"),
        ("cancel", "P5U:CANCEL:INTERRUPTED"),
        ("cancel refund zero", "refund=0"),
        ("resolve", "P5U:RESOLVE:DELAYED_OWN_ROOT"),
        ("token consume", "statuses = state.statuses.filterNot"),
        ("malformed shared state", "if (hasOtherPreparedOrDelayedP5u()) return null"),
    ):
        check(name, needle in adapter)
    check("adapter hash document", ADAPTER_HASH in document, ADAPTER_HASH)

    resolver = text["resolver"]
    for name, needle in (
        ("plan at hero root", "VNextDelayedOwnRootRuntimeP5u.prepare(heroStatus)"),
        ("pending branch", "if (delayedOwnRootPlan.pending)"),
        ("forced commit", "VNextDelayedOwnRootRuntimeP5u.commitAtHeroRoot"),
        ("cancel receipt", '"DELAYED_OWN_ROOT_CANCELLED"'),
        ("resolve receipt", '"DELAYED_OWN_ROOT_RESOLUTION"'),
        ("cancel no damage", "noDamageReceipt"),
        ("physical resolution", 'damageType = "PHYSICAL"'),
        ("delayed resolution", 'delivery = "DELAYED"'),
        ("coefficient", "coefficientBps = delayedCommit.coefficientBps"),
        ("fixed hit", "hitPolicyBps = delayedCommit.hitPolicyBps"),
        ("critical policy", "criticalPolicy = delayedCommit.criticalPolicy"),
        ("charge floor", "hitFloorBps = chargePlan.hitFloorBps"),
        ("charge consume", '"p5u-charge-consumed"'),
        ("prepare no charge", 'activePlan.pattern !in setOf('),
        ("delayed patterns", '"DELAYED_BURST", "DELAYED_CLAMP_MAX"'),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)
    forced_start = resolver.index("if (delayedOwnRootPlan.pending)")
    forced_end = resolver.index("val decision = VNextAutoBattleAiPolicy.chooseAction", forced_start)
    forced = resolver[forced_start:forced_end]
    check("forced root bypasses AI", "chooseAction" not in forced, "none")
    check("cancel has no basic fallback", '"BASIC"' not in forced, "none")
    check("no cooldown grant on resolve", "heroCooldowns = heroCooldowns +" not in forced, "none")

    ai = text["ai"]
    for name, needle in (
        ("AI patterns", 'skill.pattern in setOf("DELAYED_BURST", "DELAYED_CLAMP_MAX")'),
        ("AI occupancy", 'return "PREPARED_OR_DELAYED_TOKEN_OCCUPIED"'),
        ("AI reason", 'return "DELAYED_OWN_ROOT_PREPARATION" to 625'),
        ("AI token visible", '"PREPARED_DELAYED_OWN_ROOT"'),
    ):
        check(name, needle in ai)

    composite = text["composite"]
    for name, needle in (
        ("composite rules", "aq.composite-semantic-dispatcher.p5u.v0.1"),
        ("composite hash", COMPOSITE_HASH),
        ("families", "V_NEXT_P5U_COMPOSITE_FAMILY_COUNT = 15"),
        ("definitions", "V_NEXT_P5U_COMPOSITE_DEFINITION_COUNT = 60"),
        ("prior", "VNextCompositeSemanticDispatcherP5tCompiler.compile"),
        ("added", "VNextDelayedOwnRootSemanticAdapterP5uCompiler.compile"),
        ("duplicate closed", "P5U_DUPLICATE_OWNERS"),
        ("unsupported closed", "P5U_COMPOSITE_UNSUPPORTED_DEFINITION"),
    ):
        check(name, needle in composite)
    check("composite hash document", COMPOSITE_HASH in document, COMPOSITE_HASH)

    for phrase in (
        "coverage pins two own root packets and fixed hit meanings",
        "both skills prepay one empty root and arm exact level coefficients",
        "all class scabbard is allowed while smash rejects a non warrior",
        "prepared reaction occupancy and replay fail without spending resource",
        "scabbard resolves next root at fixed ninety nine hit and forbids critical",
        "smash resolves at fixed sixty five hit with derived critical",
        "stagger or silence cancels the forced root without refund or basic fallback",
        "token CAS replay and preparation removal are deterministic",
        "unused delay token expires at encounter end",
    ):
        check(f"adapter test {phrase[:31]}", phrase in text["adapter_tests"], "covered")
    for phrase in (
        "coverage extends fifty eight definitions with two own root delays",
        "prior trap scabbard and smash preserve complete route provenance",
        "unsupported definition and wrong smash class fail without mutation",
        "delayed replay is deterministic and every definition has one family",
    ):
        check(f"composite test {phrase[:29]}", phrase in text["composite_tests"], "covered")
    check("AI integration", "own root delay is selected without telegraph and never stacks with a pending token" in text["ai_tests"], "covered")
    for phrase in (
        "p5u prepared smash pays one empty root then owns the next hero root with fixed hit",
        "p5u stagger cancels the reserved packet but still consumes the hero root",
    ):
        check(f"resolver test {phrase[:30]}", phrase in text["resolver_tests"], "covered")
    check("charge waits proof", "firstChargeConsumption.actionIndex" in text["resolver_tests"], "covered")

    check("app unconnected", "VNextCompositeSemanticDispatcherP5u" not in app, "safe")
    check("legacy unconnected", "VNextCompositeSemanticDispatcherP5u" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)
    check("engine tests", totals("game-engine") == (538, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == 37_507_512, str(APK.stat().st_size if APK.is_file() else 0))
    check("screenshot hash", SCREENSHOT.is_file() and sha(SCREENSHOT) == SCREENSHOT_HASH, sha(SCREENSHOT) if SCREENSHOT.is_file() else "missing")
    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=5518", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=538", "appTests=176",
        "totalTests=714", "activeDefinitionsExecutable=60", "semanticFamilies=15",
        "featureDefaultEnabled=false", "liveSettlementEnabled=false", f"emulatorScreenshotSha256={SCREENSHOT_HASH}",
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
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5u-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5u-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")
    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5u PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
