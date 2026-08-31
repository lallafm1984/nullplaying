#!/usr/bin/env python3
"""PD audit for P5l class A2 survival actions and 39-Active composite."""

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
APK_HASH = "e6e7705dd5c392fe818f189205f02466db19845e130069bd8a6dde137e7eb0fc"
APK_SIZE = 37_368_053
SURVIVAL_HASH = "65bc04c3420e51eeff4a2d9896262768f8daed2297bfd092a3082e92f2204f38"
COMPOSITE_HASH = "2ef70be9f50cb0324f27956e4a1bfb1eba6e286a677243683d130109d3922d10"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextClassSurvivalSemanticAdapterP5l.kt":
        "13e9d7f5b6df0da0150e550ebf48abcacada70e3f3a40bd7315020a455c65549",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5l.kt":
        "a0ab4a44d45f1c0bab87bb94704194ea9d85bd97b7cbe1017c6a63110b6a0219",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSupportSustainSemanticAdapterP5k.kt":
        "4702b9e6398e3b7f5fcfb9c6e7b8c97cbbb23b954528b75925929c5e04a240e7",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "3d2e67181f9a8d433a44887869545ec49dce7b5f8d5e1596f351f210835ac91b",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt":
        "a4156fde68b539684e158ab6be98a8edd0405ba506072c7aeb9da0f65ddfc324",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextClassSurvivalSemanticAdapterP5lTest.kt":
        "318e21814d8cbade5daafdeb95d32354153a082c21f91d039ab4a9859af0f852",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5lTest.kt":
        "c7a7cf5080c6548cc070e7fa6ac79559a045b60178d259135fbcd4d5f4f84f68",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "ff49ba3a0d13b6ad2c442474b65639c83b64916060d3b38fa47e735987d8d607",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt":
        "68b61486108aac7b46928bd85a2a3cd5df4afa1eb7230e7f6e334beb73fb3779",
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

    survival = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextClassSurvivalSemanticAdapterP5l.kt").read_text()
    composite = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5l.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    ai = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt").read_text()
    tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextClassSurvivalSemanticAdapterP5lTest.kt").read_text()
    composite_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5lTest.kt").read_text()
    resolver_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    ai_tests = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5L_CLASS_SURVIVAL_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5l-emulator-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("survival rules", "aq.class-survival-adapter.p5l.v0.1" in survival, "v0.1")
    check("survival hash", SURVIVAL_HASH in survival and SURVIVAL_HASH in document, SURVIVAL_HASH)
    check("six definitions", "V_NEXT_P5L_CLASS_SURVIVAL_DEFINITION_COUNT: Int = 6" in survival, "6")
    check("six tokens", "V_NEXT_P5L_CLASS_LEDGER_TOKEN_COUNT: Int = 6" in survival, "6")
    check("survival live OFF", "val liveReady: Boolean get() = false" in survival, "OFF")

    definitions = (
        ("WARRIOR", "aq.skill.warrior.a2.ironstance", "SHIELD", "6_500, 1, 3, false"),
        ("ROGUE", "aq.skill.rogue.a2.narrowescape", "BARRIER", "null, 1, 2, false"),
        ("RANGER", "aq.skill.ranger.a2.coverstance", "SHIELD", "7_000, 1, 3, true"),
        ("MAGE", "aq.skill.mage.a2.arcaneveil", "SHIELD", "6_500, 1, 2, true"),
        ("CLERIC", "aq.skill.cleric.a2.restoringprayer", "HEAL", "7_000, 2, 5, true"),
        ("PALADIN", "aq.skill.paladin.a2.guardianoath", "SHIELD", "6_500, 1, 3, false"),
    )
    for owner, definition_id, kind, cap_fragment in definitions:
        check(f"{owner} ID", definition_id in survival, definition_id)
        check(f"{owner} owner", f'"{owner}", P5lProtectionKind.{kind}' in survival, owner)
        check(f"{owner} caps", cap_fragment in survival, cap_fragment)

    token_rows = (
        ("shield encounter", "ledger.class-shield.encounter", "LEDGER_CLASS_SHIELD_ENCOUNTER", "ENCOUNTER"),
        ("shield expedition", "ledger.class-shield.expedition", "LEDGER_CLASS_SHIELD_EXPEDITION", "EXPEDITION_LODGING"),
        ("heal encounter", "ledger.class-heal.encounter", "LEDGER_CLASS_HEAL_ENCOUNTER", "ENCOUNTER"),
        ("heal expedition", "ledger.class-heal.expedition", "LEDGER_CLASS_HEAL_EXPEDITION", "EXPEDITION_LODGING"),
        ("barrier encounter", "ledger.class-barrier.encounter", "LEDGER_CLASS_BARRIER_ENCOUNTER", "ENCOUNTER"),
        ("barrier expedition", "ledger.class-barrier.expedition", "LEDGER_CLASS_BARRIER_EXPEDITION", "EXPEDITION_LODGING"),
    )
    for label, instance_id, tag, clock in token_rows:
        check(f"{label} ID", instance_id in survival, instance_id)
        check(f"{label} tag", tag in survival, tag)
        check(f"{label} clock", f'"{clock}"' in survival, clock)

    check("pure A2 registry gate", "definition.attackEquivalentValues.all { it == 0 }" in survival, "zero attack")
    check("zero packet gate", "definition.hitPackets == 0" in survival, "zero packets")
    check("descriptor single carrier", "descriptor.carriers.singleOrNull()" in survival, "strict")
    check("protection family gate", "protection.family == contract.protectionFamily" in survival, "strict")
    check("actor class gate", "ACTOR_CLASS_MISMATCH" in survival, "fail closed")
    check("behavior gate", "BEHAVIOR_POLICY_INVALID" in survival, "fail closed")
    check("phase gate", "PHASE_ORDER_MISMATCH" in survival, "fail closed")
    check("support ledger admission", "supportLedgerViewP5k()" in survival, "canonical")
    check("class ledger admission", "classProtectionLedgerViewP5l()" in survival, "canonical")
    check("class cap reject", "CLASS_PROTECTION_LEDGER_REJECTED" in survival, "before cost")
    check("resource precheck", "RESOURCE_COST_UNPAYABLE" in survival, "before cost")
    check("HP threshold", "HP_THRESHOLD_REJECTED" in survival, "rechecked")
    check("heal missing floor", "MISSING_HP_FLOOR_REJECTED" in survival, "5 percent")
    check("barrier capacity", "BARRIER_CAPACITY_REJECTED" in survival, "bounded")
    check("expedition attrition", "resourceCeilingBps - attritionBps" in survival, "atomic")
    check("encounter HP ceiling", "actorEncounterStartHp" in survival, "bounded heal")
    check("sustain pipeline", "VNextProtectionDeathPipeline.grantSustain" in survival, "heal shield")
    check("barrier one", "barrierCharges = protectionState.barrierCharges + 1" in survival, "one charge")
    check("dual ledger commit", "syncSupportLedgerP5k" in survival and "syncClassProtectionLedgerP5l" in survival, "atomic")
    check("cost locked", "costLocked = true" in survival, "tokens")
    check("monotonic version", "version = (current?.version ?: 0) + 1" in survival, "CAS")

    check("AI metadata candidate", "val candidateRuleId: String" in ai, "typed")
    check("AI metadata growth", "val growthField: String" in ai, "typed")
    check("resolver binds candidate", "candidateRuleId = definition.candidateRuleId" in resolver, "bound")
    check("resolver binds growth", "growthField = definition.growthField" in resolver, "bound")
    check("resolver binds behavior", "actorBehaviorPolicy = request.battlePlan.committedBuild.behaviorPolicy.name" in resolver, "bound")
    check("resolver binds class", "actorClassId = request.battlePlan.committedBuild.heroClassId" in resolver, "bound")
    check("AI survival profiles", 'setOf("SURVIVAL_SHIELD", "SURVIVAL_HEAL", "SURVIVAL_BARRIER")' in ai, "three")
    check("AI behavior threshold", "BehaviorPolicy.CAUTIOUS -> 1_000" in ai and "BehaviorPolicy.BOLD -> -1_000" in ai, "offset")
    check("AI class ledger", 'return "CLASS_PROTECTION_LEDGER" to ""' in ai, "excluded")
    check("AI sustain budget", 'return "SUSTAIN_BUDGET" to ""' in ai, "excluded")

    check("composite rules", "aq.composite-semantic-dispatcher.p5l.v0.1" in composite, "v0.1")
    check("composite hash", COMPOSITE_HASH in composite and COMPOSITE_HASH in document, COMPOSITE_HASH)
    check("six families", "V_NEXT_P5L_COMPOSITE_FAMILY_COUNT: Int = 6" in composite, "6")
    check("thirty-nine definitions", "V_NEXT_P5L_COMPOSITE_DEFINITION_COUNT: Int = 39" in composite, "39")
    check("composite live OFF", "val liveReady: Boolean get() = false" in composite, "OFF")
    check("P5k compiler bound", "VNextCompositeSemanticDispatcherP5kCompiler.compile" in composite, "33")
    check("P5l compiler bound", "VNextClassSurvivalSemanticAdapterP5lCompiler.compile" in composite, "6")
    check("duplicate gate", "P5L_DUPLICATE_DEFINITION_OWNERS" in composite, "fail closed")
    check("count gate", "P5L_DEFINITION_COUNT_MISMATCH" in composite, "fail closed")
    check("hash gate", "P5L_CONTENT_HASH_MISMATCH" in composite, "fail closed")
    check("definition route", "routes[request.activePlan.definitionId]" in composite, "exact")
    check("unsupported reject", "P5L_COMPOSITE_UNSUPPORTED_DEFINITION" in composite, "no proxy")
    check("nested provenance", '"P5L_COMPOSITE:${route.family}|${result.detail}"' in composite, "auditable")

    for phrase in (
        "coverage pins six pure A2 survival actives and six class ledger tokens",
        "warrior shield pays encounter resource and writes class plus sustain ledgers",
        "expedition ranger shield lowers both resource and ceiling by committed cost",
        "rogue skill level expands only threshold and grants exactly one barrier",
        "behavior offset is enforced again inside the adapter",
        "cleric heal respects encounter start ceiling and expedition attrition",
        "class kind shared generation cap rejects another shield without spending",
        "encounter clock clears local counts while lodging clears class and sustain expedition counts",
        "wrong class and malformed class ledger both fail before cost",
    ):
        check(f"adapter test {phrase[:27]}", phrase in tests, "covered")
    for phrase in (
        "coverage extends thirty-three definitions with six disjoint class survival definitions",
        "direct common support and class survival routes preserve every nested provenance",
        "unsupported definition and wrong class both fail closed with unchanged states",
        "same class survival request is deterministic and retains exact definition ownership",
    ):
        check(f"composite test {phrase[:25]}", phrase in composite_tests, "covered")
    check("resolver mixed test", "p5l composite selects one cautious class shield then continues direct combat" in resolver_tests, "covered")
    check("resolver encounter expiry", '"LEDGER_CLASS_SHIELD_ENCOUNTER" !in first.finalHeroStatusPayload' in resolver_tests, "covered")
    check("resolver expedition carry", '"LEDGER_CLASS_SHIELD_EXPEDITION" in first.finalHeroStatusPayload' in resolver_tests, "covered")
    check("AI threshold test", "class survival threshold uses growth axis and committed behavior policy" in ai_tests, "covered")
    check("AI ledger test", "class protection ledger blocks regeneration and rogue level only expands barrier threshold" in ai_tests, "covered")

    check("A3 descriptor no-go documented", "A3 descriptor 불일치" in document and "NO-GO (descriptor 교정 필요)" in document, "explicit")
    check("resolver feature OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("Application P5l unconnected", "VNextCompositeSemanticDispatcherP5l" not in application, "live off")
    check("legacy P5l unconnected", "VNextCompositeSemanticDispatcherP5l" not in settlement, "legacy safe")
    check("legacy resolveKill remains", "resolveKill" in settlement, "unchanged")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (401, 0, 0, 0), str(totals("game-engine")))
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
        "coldTotalTimeMs=968", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=401",
        "appTests=176", "totalTests=577", "liveSettlementEnabled=false",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("emulator booted", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying"])
        check("emulator app version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0 (1)")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("emulator focus", "topResumedActivity" in focus.stdout and "com.nullplaying/.MainActivity" in focus.stdout, "MainActivity")
        ui_text = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5l-review-ui.xml"])
            ui_text = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5l-review-ui.xml"]).stdout
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
    print(f"P5l PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
