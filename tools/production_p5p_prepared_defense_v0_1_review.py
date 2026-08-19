#!/usr/bin/env python3
"""PD audit for P5p prepared-defense semantics and emulator evidence."""
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
APK_HASH = "17bc174a4744ebd9cf20d94bc9292eb179198b54044f1b00d25661b462a3e5df"
APK_SIZE = 37_430_560
PREPARED_HASH = "a52723c9993424022bc1b07abc937733c4d9dd02ba53f35176090030ea00f906"
COMPOSITE_HASH = "ecbc20d5789cbb43c93bacd22b50a1c1a2f1ce5a87fb08bd1b08fb400195e20a"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedDefenseSemanticAdapterP5p.kt": "1838b39084b8e9c7fda5d2fab8e3d0e5fe37573e7ea283fd8acd0887132f3d95",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5p.kt": "24f233195c0e904550664001fbd52df388f8fa8ba2c7ef2c01eac48db92c6947",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt": "0c616004891d7b0a177cf17e5a2428352f6a6ba99f4ae0a4b3f98745d5ff9eec",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt": "4a70bc3d61342ab166c1237338fdea6c37ac1249859bdd051893b58773e7a39a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt": "78d95cf587464e0d38570edd64d80022b15cbe12fbcbcd3fd510e1e1a60870b9",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedDefenseSemanticAdapterP5pTest.kt": "8da2acba3ccabc3b7294626f8d03fb3682ed60fc184ae774a3e7b0084df57b6b",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5pTest.kt": "1ab6d64a8a354bc0221c54a75aef94ca74028e6b02569b4c3ab6cdae40857bb9",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt": "3aa37b28f24f222b6d3b18d943dd07d182e3af98372376ad6ebdd833dc59430d",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt": "75c2601ba52756855332ea6078f3a607f74c4a1770e566368ec925f96337fcf9",
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

    adapter = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedDefenseSemanticAdapterP5p.kt").read_text()
    composite = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5p.kt").read_text()
    packet = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    ai = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt").read_text()
    adapter_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedDefenseSemanticAdapterP5pTest.kt").read_text()
    composite_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5pTest.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    ai_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt").read_text()
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5P_PREPARED_DEFENSE_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5p-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("rules", "aq.prepared-defense-adapter.p5p.v0.1" in adapter, "v0.1")
    check("prepared hash", PREPARED_HASH in adapter and PREPARED_HASH in document, PREPARED_HASH)
    check("two definitions", "V_NEXT_P5P_PREPARED_DEFENSE_DEFINITION_COUNT = 2" in adapter, "2")
    check("one token", "V_NEXT_P5P_PREPARED_DEFENSE_TOKEN_CAP = 1" in adapter, "1")
    check("telegraph floor", "V_NEXT_P5P_PUBLIC_TELEGRAPH_FLOOR_BPS = 13_000" in adapter, "13000")
    check("live off", "val liveReady: Boolean get() = false" in adapter, "OFF")
    for skill, pattern, owner, handler in (
        ("sanctuary", "PREPAID_MITIGATION", "CLERIC", "PreparedHandlerId.PREPAID_MITIGATION"),
        ("animalwarning", "PREPAID_EVASION", "RANGER", "PreparedHandlerId.PREPAID_EVASION_REACTION"),
    ):
        check(f"{skill} id", f'.{skill}\"' in adapter, skill)
        check(f"{skill} pattern", f'\"{pattern}\"' in adapter, pattern)
        check(f"{skill} owner", f'\"{owner}\"' in adapter, owner)
        check(f"{skill} prepared handler", handler in adapter, handler)
    for name, needle in (
        ("legacy packet drift", "definition.hitPackets == 1 && definition.critEligible"),
        ("modifier descriptor", "VNextRuntimeCarrierDeliveryV2.MODIFIER"),
        ("class gate", "ACTOR_CLASS_MISMATCH"),
        ("public telegraph required", "PUBLIC_TELEGRAPH_REQUIRED"),
        ("public telegraph threshold", "PUBLIC_TELEGRAPH_BELOW_FLOOR"),
        ("resource atomicity", "RESOURCE_COST_UNPAYABLE"),
        ("replay gate", "REPLAY_RECEIPT"),
        ("shared instance", 'instanceId = "prepared.defense"'),
        ("encounter clock", 'clock = "ENCOUNTER"'),
        ("unsigned mitigation storage", "MITIGATION -> -request.activePlan.resolvedAnchorValue"),
        ("negative packet restoration", "incomingDamageModifierBps = (-token.magnitudeBps)"),
        ("runtime prepare", "VNextPreparedDefenseRuntimeP5p"),
        ("direct only", 'if (delivery != "DIRECT")'),
        ("cas", "TOKEN_CAS_REJECTED"),
        ("mitigation miss wait", "MITIGATION_WAIT_MISS"),
        ("evasion attempt consume", "plan.kind == P5pPreparedDefenseKind.EVASION || landed"),
        ("strict state codec", "VNextStatusStateCodecP5c.encode"),
    ):
        check(name, needle in adapter, "bound")
    for name, needle in (
        ("packet modifier field", "val incomingDamageModifierBps: Int = 0"),
        ("packet modifier range", "request.incomingDamageModifierBps in -7_000..10_000"),
        ("after defense", "10_000 - mitigationBps"),
        ("before barrier shield", "10_000 + request.incomingDamageModifierBps"),
    ):
        check(name, needle in packet, "bound")
    for name, needle in (
        ("resolver prepare", "VNextPreparedDefenseRuntimeP5p.prepare(heroStatus, delivery)"),
        ("resolver evasion", "targetEvasionAddBps = preparedPlan.evasionAddBps"),
        ("resolver mitigation", "incomingDamageModifierBps = preparedPlan.incomingDamageModifierBps"),
        ("resolver commit", "VNextPreparedDefenseRuntimeP5p.commit"),
        ("receipt consume", 'receiptId = "$receiptId:p5p-consume"'),
        ("status before hash", "preparedStatusBefore"),
        ("temporary evasion", "target = result.targetAfter.copy(evasion = target.evasion)"),
        ("beneficial prepared status", '"PREPARED_EVASION_REACTION"'),
    ):
        check(name, needle in resolver, "bound")
    for name, needle in (
        ("AI mitigation", 'text == "PREPAID_MITIGATION"'),
        ("AI animal warning", 'skill.skillId == "aq.skill.ranger.w3.animalwarning"'),
        ("AI occupied", 'return "PREPARED_DEFENSE_OCCUPIED"'),
        ("AI direct", 'telegraph.delivery != "DIRECT"'),
        ("AI threshold", "telegraph.coefficientBps < 13_000"),
        ("AI unavailable", 'return "NO_PUBLIC_TELEGRAPH"'),
        ("retreat fail closed", 'return "TWO_DIRECT_FORECAST_UNAVAILABLE"'),
    ):
        check(name, needle in ai, "bound")
    check("composite rules", "aq.composite-semantic-dispatcher.p5p.v0.1" in composite, "v0.1")
    check("composite hash", COMPOSITE_HASH in composite and COMPOSITE_HASH in document, COMPOSITE_HASH)
    check("ten families", "V_NEXT_P5P_COMPOSITE_FAMILY_COUNT = 10" in composite, "10")
    check("fifty-three definitions", "V_NEXT_P5P_COMPOSITE_DEFINITION_COUNT = 53" in composite, "53")
    check("P5o bound", "VNextCompositeSemanticDispatcherP5oCompiler.compile" in composite, "51")
    check("P5p bound", "VNextPreparedDefenseSemanticAdapterP5pCompiler.compile" in composite, "2")
    check("duplicates closed", "P5P_DUPLICATE_OWNERS" in composite, "closed")
    check("unsupported closed", "P5P_COMPOSITE_UNSUPPORTED_DEFINITION" in composite, "closed")
    for phrase in (
        "coverage binds two public telegraph defenses to prepared and modifier contracts",
        "sanctuary arms one mitigation token only for a strong public direct telegraph",
        "animal warning arms level scaled evasion and occupied token rejects atomically",
        "mitigation waits on a miss and consumes with cas on the first landed direct",
        "evasion consumes on the next direct attempt even when that attack misses",
        "incoming mitigation applies after defense and before shield without changing hit rolls",
        "encounter end expires an unused prepared defense",
        "malformed prepared token wrong class and hidden telegraph fail closed",
    ):
        check(f"adapter test {phrase[:26]}", phrase in adapter_tests, "covered")
    for phrase in (
        "coverage extends fifty-one definitions with two prepared defenses",
        "old direct and prepared mitigation preserve complete route provenance",
        "unsupported definition and wrong prepared class fail without mutation",
        "prepared evasion replay is deterministic and every definition has one family",
    ):
        check(f"composite test {phrase[:24]}", phrase in composite_tests, "covered")
    for phrase in (
        "prepared defense needs an unoccupied token and a strong public direct telegraph",
        "two direct retreat preparation stays excluded until that forecast is public",
    ):
        check(f"AI test {phrase[:28]}", phrase in ai_tests, "covered")
    check(
        "resolver integration",
        "p5p cleric prepares mitigation from public slam and monster settlement consumes or waits atomically" in resolver_tests,
        "covered",
    )
    check("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("app unconnected", "VNextCompositeSemanticDispatcherP5p" not in app, "safe")
    check("legacy unconnected", "VNextCompositeSemanticDispatcherP5p" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)
    check("engine tests", totals("game-engine") == (461, 0, 0, 0), str(totals("game-engine")))
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
        "coldTotalTimeMs=6101", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=461",
        "appTests=176", "totalTests=637", "liveSettlementEnabled=false",
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
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5p-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5p-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")
    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5p PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
