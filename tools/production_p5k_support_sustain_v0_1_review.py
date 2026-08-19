#!/usr/bin/env python3
"""PD audit for the P5k support/sustain adapter and 33-Active composite."""

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
APK_HASH = "09c2c2ee7636f3f8ebb01935c85c457731fbc55a2bfd736864b4e3d96cf27763"
APK_SIZE = 37_347_279
SUPPORT_HASH = "32637a055f547c3b7b7fd0e9eea21447e722d296a21e0828499383f94e912435"
COMPOSITE_HASH = "21351eafe3af08a69eb99c4b1756495a3ed3f1b2fb8706a98c61da71345b0add"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSupportSustainSemanticAdapterP5k.kt":
        "44fee3eadab7ddb26b11e181cdd4b28340de0c309cee7c98d57d971c716fa12c",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5k.kt":
        "14e6ac0948c117e7cfcbb60fd4e6b0f6f314aa139d0eeb27d375bfcd40c4f526",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "a365edba93aea4c7b2b84359c7a5f0005310ca06dd526822d5d26a24637e5703",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt":
        "43019061ff029c4d77cd5feac00eba605c5450d8246651512fb909aa01fd1946",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt":
        "ba0f8e63a0932ec4731b36af7cd3945da0d1ff445d92f3b9442cff92c10bdb3a",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextSupportSustainSemanticAdapterP5kTest.kt":
        "8bcea9fbeec26a425dc14f73a59c6835513a26b506867573e131ead767c75e31",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5kTest.kt":
        "4fd5d763f081a37e6b160c2ab0aeb57cc578b48a30f67b69ff445136910ba8fd",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "e4fdba67040e9bb33d6df010ce69419d4175e1de4d9582e7cbb26aedf7f86cab",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt":
        "539d7355cd5bc96e37521fbf32455e3648ff2ec6c8f8afa6260586b334788a82",
    "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt":
        "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def totals(module: str) -> tuple[int, int, int, int]:
    result = [0, 0, 0, 0]
    for path in (ROOT / module / "build/test-results").glob("test*/TEST-*.xml"):
        suite = ET.parse(path).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            result[index] += int(suite.attrib.get(key, 0))
    return tuple(result)


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=False)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-gradle", action="store_true")
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    if args.with_gradle:
        completed = run(["./gradlew", "test", "lintDebug", "assembleDebug"])
        if completed.returncode:
            print(completed.stdout)
            print(completed.stderr, file=sys.stderr)
            return completed.returncode

    support = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSupportSustainSemanticAdapterP5k.kt").read_text()
    composite = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5k.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    ai = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt").read_text()
    support_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextSupportSustainSemanticAdapterP5kTest.kt").read_text()
    composite_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5kTest.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    ai_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5K_SUPPORT_SUSTAIN_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5k-emulator-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("support rules", "aq.support-sustain-adapter.p5k.v0.1" in support, "v0.1")
    check("support hash", SUPPORT_HASH in support and SUPPORT_HASH in document, SUPPORT_HASH)
    check("support definitions", "V_NEXT_P5K_SUPPORT_SUSTAIN_DEFINITION_COUNT: Int = 3" in support, "3")
    check("ledger tokens", "V_NEXT_P5K_SUPPORT_LEDGER_TOKEN_COUNT: Int = 5" in support, "5")
    check("support live OFF", "val liveReady: Boolean get() = false" in support, "OFF")

    definitions = (
        ("defense stance", "aq.skill.common.w5.defensestance", "P5kSupportKind.SHIELD", "COMMON_SHIELD"),
        ("first aid", "aq.skill.common.w5.firstaid", "P5kSupportKind.HEAL", "COMMON_HEAL"),
        ("focused breathing", "aq.skill.common.w5.focusedbreathing", "P5kSupportKind.RESOURCE", "COMMON_RESOURCE_RECOVERY"),
    )
    for label, definition_id, kind, pattern in definitions:
        check(f"{label} ID", definition_id in support, definition_id)
        check(f"{label} kind", kind in support, kind)
        check(f"{label} pattern", pattern in support, pattern)

    tokens = (
        ("sustain encounter", "ledger.sustain.encounter", "LEDGER_SUSTAIN_ENCOUNTER", "ENCOUNTER"),
        ("sustain expedition", "ledger.sustain.expedition", "LEDGER_SUSTAIN_EXPEDITION", "EXPEDITION_LODGING"),
        ("common encounter", "ledger.common-survival.encounter", "LEDGER_COMMON_SURVIVAL_ENCOUNTER", "ENCOUNTER"),
        ("common expedition", "ledger.common-survival.expedition", "LEDGER_COMMON_SURVIVAL_EXPEDITION", "EXPEDITION_LODGING"),
        ("resource encounter", "ledger.resource-recovery.encounter", "LEDGER_RESOURCE_RECOVERY_ENCOUNTER", "ENCOUNTER"),
    )
    for label, instance_id, tag, clock in tokens:
        check(f"{label} ID", instance_id in support, instance_id)
        check(f"{label} tag", tag in support, tag)
        check(f"{label} clock", f'"{clock}"' in support, clock)

    check("codec admission", "VNextStatusStateCodecP5c.encode(this)" in support, "validated")
    check("cost locked token", "costLocked = true" in support, "locked")
    check("token version CAS", "version = (current?.version ?: 0) + 1" in support, "monotonic")
    check("phase gate", "PHASE_ORDER_MISMATCH" in support, "fail closed")
    check("resource precheck", "RESOURCE_COST_UNPAYABLE" in support, "before commit")
    check("encounter HP bridge", "actorEncounterStartHp" in support and "actorEncounterStartHp = request.hero.hp" in resolver, "bound")
    check("sustain pipeline", "VNextProtectionDeathPipeline.grantSustain" in support, "canonical")
    check("resource pipeline", "VNextProtectionDeathPipeline.focusedBreathing" in support, "canonical")
    check("resource ledger cap", "resourceRecoveryEncounterUsed > 1" in support, "one")
    check("bad ledger reject", "SUPPORT_LEDGER_STATE_INVALID" in support, "fail closed")
    check("passive reject", "PASSIVE_ADAPTERS_REQUIRED" in support, "fail closed")

    check("AI common family", 'text in setOf("COMMON_HEAL", "COMMON_SHIELD")' in ai, "shared")
    check("AI support ledger", 'return "SUPPORT_LEDGER" to ""' in ai, "excluded")
    check("AI missing HP", 'return "NO_MISSING_HP" to ""' in ai, "excluded")
    check("AI resource ledger", 'return "RESOURCE_RECOVERY_LEDGER" to ""' in ai, "excluded")
    check("AI full resource", 'return "RESOURCE_FULL" to ""' in ai, "excluded")
    check("AI ledger tags beneficial", 'status.tag.startsWith("LEDGER_")' in resolver, "structured effect")

    check("composite rules", "aq.composite-semantic-dispatcher.p5k.v0.1" in composite, "v0.1")
    check("composite hash", COMPOSITE_HASH in composite and COMPOSITE_HASH in document, COMPOSITE_HASH)
    check("five families", "V_NEXT_P5K_COMPOSITE_FAMILY_COUNT: Int = 5" in composite, "5")
    check("thirty-three definitions", "V_NEXT_P5K_COMPOSITE_DEFINITION_COUNT: Int = 33" in composite, "33")
    check("composite live OFF", "val liveReady: Boolean get() = false" in composite, "OFF")
    check("P5j compiler bound", "VNextCompositeSemanticDispatcherP5jCompiler.compile" in composite, "30")
    check("support compiler bound", "VNextSupportSustainSemanticAdapterP5kCompiler.compile" in composite, "3")
    check("duplicate grouping", "groupingBy { it }.eachCount()" in composite, "checked")
    check("duplicate gate", "P5K_DUPLICATE_DEFINITION_OWNERS" in composite, "fail closed")
    check("count gate", "P5K_DEFINITION_COUNT_MISMATCH" in composite, "fail closed")
    check("hash gate", "P5K_CONTENT_HASH_MISMATCH" in composite, "fail closed")
    check("exact route", "routes[request.activePlan.definitionId]" in composite, "definition ID")
    check("unsupported reject", "P5K_COMPOSITE_UNSUPPORTED_DEFINITION" in composite, "no proxy")
    check("request preserved", "route.dispatcher.dispatch(request)" in composite, "delegated")
    check("reject preserved", '"P5K_COMPOSITE:${route.family}|REJECT:${result.detail}"' in composite, "auditable")

    for phrase in (
        "coverage pins three support actives five ledger tokens and keeps live off",
        "defense stance grants shield and writes encounter plus expedition shared budgets",
        "first aid cannot heal above encounter start hp and records deterministic overheal",
        "focused breathing pays its action cost recovers only to ceiling and is encounter once",
        "common survival shared cap rejects a second heal or shield without spending cost",
        "encounter end clears encounter tokens while lodging clears expedition tokens",
        "malformed canonical ledger token rejects before cost or support mutation",
    ):
        check(f"support test {phrase[:26]}", phrase in support_tests, "covered")
    for phrase in (
        "coverage extends thirty frozen definitions with three disjoint support definitions",
        "old direct route and new support route preserve nested family provenance",
        "unsupported registry definition fails closed without changing either side",
        "support replay with committed ledgers rejects and leaves prepaid frame unchanged",
    ):
        check(f"composite test {phrase[:24]}", phrase in composite_tests, "covered")
    check("resolver mixed test", "p5k composite uses support once alongside direct combat and expires encounter ledgers" in resolver_tests, "covered")
    check("resolver expedition survives", '"LEDGER_SUSTAIN_EXPEDITION" in first.finalHeroStatusPayload' in resolver_tests, "covered")
    check("resolver encounter expires", '"LEDGER_SUSTAIN_ENCOUNTER" !in first.finalHeroStatusPayload' in resolver_tests, "covered")
    check("AI exhaustion test", "common sustain and resource recovery ledgers remove exhausted support candidates" in ai_tests, "covered")

    check("resolver feature OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("Application P5k unconnected", "VNextCompositeSemanticDispatcherP5k" not in application, "live off")
    check("legacy P5k unconnected", "VNextCompositeSemanticDispatcherP5k" not in settlement, "legacy safe")
    check("legacy resolveKill remains", "resolveKill" in settlement, "unchanged")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (385, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint_root = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    lint_errors = sum(issue.attrib.get("severity") == "Error" for issue in lint_root.findall("issue"))
    lint_warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint_root.findall("issue"))
    check("lint", (lint_errors, lint_warnings) == (0, 22), f"{lint_errors} errors, {lint_warnings} warnings")
    check("APK exists", APK.is_file(), str(APK))
    if APK.is_file():
        check("APK hash", sha256(APK) == APK_HASH, sha256(APK))
        check("APK size", APK.stat().st_size == APK_SIZE, str(APK.stat().st_size))

    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=1150", "topResumedActivity=com.alarmquest/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=385",
        "appTests=176", "liveSettlementEnabled=false",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("emulator booted", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest"])
        check("emulator app version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0 (1)")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("emulator focus", "topResumedActivity" in focus.stdout and "com.alarmquest/.MainActivity" in focus.stdout, "MainActivity")
        ui_text = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5k-review-ui.xml"])
            ui_text = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5k-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui_text and "새 캐릭터" in ui_text:
                break
            time.sleep(0.5)
        check("emulator empty roster", "아직 캐릭터가 없습니다" in ui_text and "새 캐릭터" in ui_text, "fresh roster")
        logcat = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"])
        fatal = any(token in logcat.stdout for token in ("FATAL EXCEPTION", "AndroidRuntime: FATAL"))
        check("emulator fatal zero", not fatal, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5k PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
