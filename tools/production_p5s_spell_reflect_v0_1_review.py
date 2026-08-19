#!/usr/bin/env python3
"""PD audit for P5s spell-reflect semantics and emulator evidence."""
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
APK_HASH = "8e2e62a4e72f866116aba281c29bcb1a5ce61e09a2ea3332100f51ebd5012631"
APK_SIZE = 37_478_513
ADAPTER_HASH = "116fe0b4a977116d2f93e869686a901fd2e7d9a1eb3206e74575603f957b0772"
COMPOSITE_HASH = "a2ca0c928d6103e8c4dcb40b6cf26b225f9003b54f53600baa6ea325123b23f7"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSpellReflectSemanticAdapterP5s.kt": "5c29f5c2f37defb93b16268bde323bb20599b2e36bff144554817d7d12043325",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5s.kt": "b2a94c294ed4f5f5b290ecbade2f51e94455fb86fd15c4375a17a22ee473648f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedDefenseSemanticAdapterP5p.kt": "6e7d4b08f2f5628821473d53b1dc59708c1dab47a80f490074987d1ce41fd36e",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLethalDelaySemanticAdapterP5q.kt": "4b2f6b63447f9a1f79d012dd820fdec3ae1065bd62e22c7f1819af3dcf067162",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMissCounterSemanticAdapterP5r.kt": "32e1f423c9473cbbc726bd9e5c389d26b0bdc4846aa99f80f62ed3d8816a81fb",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt": "a24cd97c230919ecb0331ea358904b160d52e78247f8c638964f33655804dc48",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt": "c62b41074e7debfd9864c4502b76f1224447993227582ea62d20bf24f48e2ba5",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt": "6120e9996e61e1f4cb0ca59159de8caf62d8b8cece6ac3f4d84cdc188afabc20",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextSpellReflectSemanticAdapterP5sTest.kt": "d5b2bc8bd17521abec5f348efbd86da04d7f3cbd9bea365d96a2b6c65cad1a53",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5sTest.kt": "7b49c51911b7f112d0a4847a51cf64c1bb5df1b6f7dec3070980f2c8966fbca1",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt": "9c68e6ba919bb8fa403e07ed2cb21dfb3d2e8e1510863d76484f0fc6581fa9ae",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt": "bfe1308f4021b15657bc36bf2e1f2f5e7e6f41eb2ef238e079a698db4157300e",
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
        "adapter": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSpellReflectSemanticAdapterP5s.kt",
        "composite": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5s.kt",
        "resolver": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt",
        "packet": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt",
        "ai": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt",
        "adapter_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextSpellReflectSemanticAdapterP5sTest.kt",
        "composite_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5sTest.kt",
        "resolver_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt",
        "ai_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt",
    }
    text = {key: (ROOT / value).read_text() for key, value in paths.items()}
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5S_SPELL_REFLECT_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5s-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    adapter = text["adapter"]
    for name, needle in (
        ("rules", "aq.spell-reflect-adapter.p5s.v0.1"),
        ("adapter hash", ADAPTER_HASH),
        ("one definition", "V_NEXT_P5S_SPELL_REFLECT_DEFINITION_COUNT = 1"),
        ("one token", "V_NEXT_P5S_REACTION_TOKEN_CAP = 1"),
        ("mitigation", "V_NEXT_P5S_INCOMING_MODIFIER_BPS = -3_000"),
        ("spell whitelist", 'setOf("FIRE", "ICE", "LIGHTNING", "ARCANE", "HOLY")'),
        ("skill", "aq.skill.mage.w5.spellreversal"),
        ("class", 'request.actorClassId != "MAGE"'),
        ("public required", "PUBLIC_TELEGRAPH_REQUIRED"),
        ("typed direct", "PUBLIC_SPELL_DIRECT_REQUIRED"),
        ("pattern", 'it.pattern == "PREPAID_SPELL_REFLECT"'),
        ("growth", 'it.growthField == "reflected_coefficient_cap_bps"'),
        ("anchors", "it.anchorValues.first() == 4_000 && it.anchorValues.last() == 8_000"),
        ("attack budget", "it.attackEquivalentValues == it.anchorValues"),
        ("registry cost", "it.resourceCostBps == 1_600"),
        ("descriptor modifier", "VNextRuntimeCarrierDeliveryV2.MODIFIER"),
        ("descriptor no RNG", "!it.consumesCoreHitRoll && !it.critEligible"),
        ("prepared handler", "PreparedHandlerId.PREPAID_SPELL_REACTION"),
        ("prepared crit forbidden", 'it.critMode == "FORBIDDEN"'),
        ("protection family", 'it.family == "PRE_BARRIER_REFLECT"'),
        ("shared reaction", "PREPARED_REACTION_TOKEN_OCCUPIED"),
        ("token id", 'TOKEN_INSTANCE_ID = "prepared.spell-reflect"'),
        ("token tag", 'tag = "PREPARED_SPELL_REFLECT"'),
        ("encounter clock", 'clock = "ENCOUNTER"'),
        ("resource prepay", "request.actor.resourceBps - cost"),
        ("plan typed", 'delivery == "DIRECT" && damageType in V_NEXT_P5S_REFLECTABLE_SPELL_TYPES'),
        ("wait", "P5S:WAIT:NO_LANDED_SPELL_DIRECT"),
        ("CAS", "TOKEN_CAS_REJECTED"),
        ("replay", "REPLAY_RECEIPT"),
        ("hero root expiry", "P5S:EXPIRE:NEXT_HERO_ROOT_START"),
        ("strict codec", "VNextStatusStateCodecP5c.encode"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in adapter)
    for name, needle in (
        ("dedicated engine", "VNextReflectPacketEngineP5s"),
        ("source whitelist", "REFLECT_SOURCE_NOT_WHITELISTED"),
        ("recursion guard", "REFLECT_RECURSION_BLOCKED"),
        ("input guard", "REFLECT_INPUT_INVALID"),
        ("actual damage cap", "minOf(request.actualHpDamage, capDamage)"),
        ("shield absorb", "minOf(request.target.shield, offered)"),
        ("barrier untouched", "barrierCharges" not in adapter[adapter.index("internal object VNextReflectPacketEngineP5s"):adapter.index("internal data class P5sSpellReflectCommit")]),
        ("no random", "VNextCoreRolls" not in adapter),
        ("no crit", "critical" not in adapter[adapter.index("internal object VNextReflectPacketEngineP5s"):adapter.index("internal data class P5sSpellReflectCommit")]),
        ("trigger receipt", "P5S:TRIGGER:SPELL_REFLECT"),
    ):
        check(name, needle if isinstance(needle, bool) else needle in adapter)
    check("general packet reflect remains closed", "REFLECT" not in text["packet"], "dedicated-only")
    check("adapter hash document", ADAPTER_HASH in document, ADAPTER_HASH)

    resolver = text["resolver"]
    for name, needle in (
        ("root expiry function", "expireSpellReflectAtHeroRootStart"),
        ("root expiry action", 'actionId = "P5S_SPELL_REFLECT_EXPIRE"'),
        ("resolver prepare", "VNextSpellReflectRuntimeP5s.prepare(heroStatus, delivery, damageType)"),
        ("incoming modifier", "spellReflectPlan.incomingDamageModifierBps"),
        ("post packet hp", "actualHpDamage = committedResult.hpDamage"),
        ("resolver commit", "VNextSpellReflectRuntimeP5s.commit"),
        ("separate receipt", 'val reflectReceiptId = "$receiptId:p5s-reflect"'),
        ("system receipt", 'actionId = "P5S_SPELL_REFLECT_PACKET"'),
        ("system reason", 'decisionReason = "PREPAID_SPELL_DIRECT_TRIGGER"'),
        ("system phases", "listOf(VNextResolverPhase.PAYLOAD, VNextResolverPhase.PROTECTION_DEATH)"),
        ("RNG zero", "hitRollBps = 0"),
        ("crit false", "critical = false"),
        ("simultaneous flag", "reflectSimultaneousDeath"),
        ("defeat priority", "reflectSimultaneousDeath ->"),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)
    reflect_start = resolver.index("if (spellReflectCommit.triggered) {")
    reflect_end = resolver.index("if (missCounterCommit.counterCoefficientBps > 0)", reflect_start)
    reflect_block = resolver[reflect_start:reflect_end]
    check("no reflect cooldown mutation", "Cooldowns =" not in reflect_block, "none")
    check("no reflect resource mutation", "resourceBps =" not in reflect_block, "none")
    check("no reflect action increment", "actorActionCount++" not in reflect_block, "none")

    ai = text["ai"]
    for name, needle in (
        ("AI pattern", 'skill.pattern == "PREPAID_SPELL_REFLECT"'),
        ("AI shared occupancy", 'return "PREPARED_REACTION_OCCUPIED"'),
        ("AI public rejection", 'return "NO_PUBLIC_TELEGRAPH"'),
        ("AI typed spell set", 'setOf("FIRE", "ICE", "LIGHTNING", "ARCANE", "HOLY")'),
        ("AI reflect visible", '"PREPARED_SPELL_REFLECT"'),
    ):
        check(name, needle in ai)

    composite = text["composite"]
    for name, needle in (
        ("composite rules", "aq.composite-semantic-dispatcher.p5s.v0.1"),
        ("composite hash", COMPOSITE_HASH),
        ("families", "V_NEXT_P5S_COMPOSITE_FAMILY_COUNT = 13"),
        ("definitions", "V_NEXT_P5S_COMPOSITE_DEFINITION_COUNT = 57"),
        ("prior", "VNextCompositeSemanticDispatcherP5rCompiler.compile"),
        ("added", "VNextSpellReflectSemanticAdapterP5sCompiler.compile"),
        ("duplicate closed", "P5S_DUPLICATE_OWNERS"),
        ("unsupported closed", "P5S_COMPOSITE_UNSUPPORTED_DEFINITION"),
    ):
        check(name, needle in composite)
    check("composite hash document", COMPOSITE_HASH in document, COMPOSITE_HASH)

    for phrase in (
        "coverage binds spell reversal to registry descriptor prepared and protection contracts",
        "spell reversal prepays one root and arms a level scaled reflect token without damage",
        "only a reflectable spell direct receives mitigation and physical or missed spell waits",
        "landed spell reflects the lesser of actual hp damage and matk cap",
        "reflect is shieldable but never consumes direct only barrier",
        "reflect whitelist and recursion guard reject unsafe sources without mutation",
        "next hero root start and encounter end expire an unused reflect token",
        "wrong class unsafe telegraph occupied reaction and malformed token fail closed",
        "trigger cas and replay fail closed",
    ):
        check(f"adapter test {phrase[:29]}", phrase in text["adapter_tests"], "covered")
    for phrase in (
        "coverage extends fifty six definitions with one spell reflect",
        "prior miss counter and spell reversal preserve complete route provenance",
        "unsupported definition and wrong reflect class fail without mutation",
        "spell reversal replay is deterministic and every definition has one family",
    ):
        check(f"composite test {phrase[:27]}", phrase in text["composite_tests"], "covered")
    check("AI integration test", "reflect and telegraph consume only typed public observation" in text["ai_tests"], "covered")
    for phrase in (
        "p5s spell reversal mitigates one landed spell and reflects without adding an actor action",
        "p5s simultaneous lethal reflection is defeat and grants no reward",
    ):
        check(f"resolver test {phrase[:28]}", phrase in text["resolver_tests"], "covered")

    check("app unconnected", "VNextCompositeSemanticDispatcherP5s" not in app, "safe")
    check("legacy unconnected", "VNextCompositeSemanticDispatcherP5s" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)
    check("engine tests", totals("game-engine") == (508, 0, 0, 0), str(totals("game-engine")))
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
        "coldTotalTimeMs=5233", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=508",
        "appTests=176", "totalTests=684", "activeDefinitionsExecutable=57",
        "semanticFamilies=13", "featureDefaultEnabled=false", "liveSettlementEnabled=false",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)
    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and "device" in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("boot", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest"])
        check("version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("focus", "topResumedActivity" in focus.stdout and "com.alarmquest/.MainActivity" in focus.stdout, "MainActivity")
        ui = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5s-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5s-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")
    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5s PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
