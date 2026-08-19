#!/usr/bin/env python3
"""PD audit for P5f monster status resistance and admission policy."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "e5a75b453772290ba8e8edaff27c89699d7e402f3bb0156d89f3fa71b77adfd7"
APK_SIZE = 37_046_178
POLICY_HASH = "21e2ecde58b37d689b7d4a82e0be44fadce3422aea237e1661cf69d7c50224aa"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextMonsterRegistry.kt":
        "adf5a1fba934a33044bf1c6dc5e5867009286818b39205edefc8ecb5de3a87c8",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt":
        "017fce1d2270410aecd01766cf46bf04130ac6f1d11c5e6056b8a2a9fe2435fa",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusRuntimeCatalogP5c.kt":
        "f5ada11fd89af964194c071307d7f0ad400f710b172d63302079b76a87f03b42",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusAdmissionPolicyP5f.kt":
        "518bb6aa34c0732c1ab5d609b94e408c5b1d1691eb76a44bf0f7235aed3e3ec3",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "1541dca8312c53b722bf16a4377018d3bb04995f72ef7f7f0e71f5e5714bf0dd",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5d.kt":
        "4b568d17392237b30bc5571922a6e240a8b84aff1e58ae8feee3b3dd6e0d712d",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextMonsterRegistryTest.kt":
        "22827ecbfaa47c5f36bae7c93ce0a610bc1c31a8f4ac93d6aa9bced69cb77030",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextStatusAdmissionPolicyP5fTest.kt":
        "e068b71a81efd7fe2c6a1cf1b4351d265cbb4844bba6bd55e8bee471ef1fc775",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5dTest.kt":
        "9bbb8a034195e7ee19d8b8961d4ab8da4ed898a4a8ed68eb659d91476c513ad9",
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

    registry = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextMonsterRegistry.kt").read_text()
    lifecycle = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt").read_text()
    runtime = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusRuntimeCatalogP5c.kt").read_text()
    policy = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusAdmissionPolicyP5f.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    adapter = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5d.kt").read_text()
    registry_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextMonsterRegistryTest.kt").read_text()
    policy_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextStatusAdmissionPolicyP5fTest.kt").read_text()
    adapter_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5dTest.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5F_STATUS_ADMISSION_RESISTANCE_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5f-emulator-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("P5f rules", "aq.status-admission-policy.p5f.v0.1" in policy, "v0.1")
    check("P5f contract hash", POLICY_HASH in policy and POLICY_HASH in document, POLICY_HASH)
    check("51 status sources", "sourceDefinitions == V_NEXT_P5C_STATUS_SOURCE_COUNT" in policy, "51")
    check("eight candidate rules", "V_NEXT_P5F_STATUS_CANDIDATE_RULE_COUNT: Int = 8" in policy, "8")
    check("seven special candidate rules", "V_NEXT_P5F_STATUS_SPECIAL_CANDIDATE_RULE_COUNT: Int = 7" in policy, "7")
    check("five modifiers", "V_NEXT_P5F_STATUS_APPLY_MODIFIER_COUNT: Int = 5" in policy, "5")
    for rule in (
        "ALWAYS", "SPELLCASTER", "TARGET_HP_LTE_2000", "TARGET_HAS_CURSE",
        "FIRST_OWN_ROOT", "ARMORED", "TARGET_HAS_BURN", "UNDEAD_FOR_STATUS",
    ):
        check(f"candidate {rule}", f'"{rule}"' in policy, "supported")
    for rule in (
        "BOSS_DELAY_CAP_ONE", "ARMORED_OR_BOSS_HALF", "SWIFT_PLUS_1000",
        "NON_SWIFT_MINUS_2000", "POISON_ELSE_AIM",
    ):
        check(f"modifier {rule}", f'"{rule}"' in policy, "supported")
    check("unsupported fail closed", "unsupportedCandidateRules.isEmpty()" in policy and "unsupportedApplyModifierRules.isEmpty()" in policy, "compile gate")
    check("policy live OFF", "val liveReady: Boolean get() = false" in policy, "OFF")

    check("base status resistance", "val statusResistancePoints: Int = 0" in registry, "explicit")
    check("base tag resistance", "val statusTagResistanceBps: Map<String, Int> = emptyMap()" in registry, "explicit")
    check("resolved status resistance", "val statusResistancePoints: Int," in registry, "resolved")
    check("resolved tag resistance", "val statusTagResistanceBps: Map<String, Int>," in registry, "resolved")
    check("source general bonus", "val sourceStatusApplyBonusBps: Int = 0" in registry, "snapshot")
    check("source tag bonus", "val sourceTagApplyBonusBps: Map<String, Int> = emptyMap()" in registry, "snapshot")
    check("general resistance clamp", "baseStats.statusResistancePoints.coerceIn(0, 100)" in registry, "0..100")
    check("tag resistance clamp", "value.coerceIn(0, 2_700)" in registry, "0..2700")
    check("prefix general clamp", ".coerceIn(0, 1_200)" in registry, "0..1200")
    for tag in ("POISON", "CHILL", "BURN", "CURSE"):
        check(f"prefix tag {tag}", f'"{tag}"' in registry, "snapshot")
    check("snapshot hash test", "encounter snapshot exposes target resistance and prefix source apply bonuses" in registry_test, "covered")

    check("per-tag lifecycle request", "tagResistanceBpsByTag" in lifecycle, "map")
    check("per-application tag lookup", "request.tagResistanceBpsByTag[application.tag]" in lifecycle, "exact tag")
    check("authored RNG slots", "val authoredPlan = catalog.executionPlan" in runtime, "stable")
    check("override shape guard", "STATUS_PLAN_OVERRIDE_SHAPE_MISMATCH" in runtime, "fail closed")
    check("authored slot lookup", "authoredApplication.tag.lowercase()" in runtime, "stable ID")
    check("candidate receipt dispatch", "candidate = request.candidate && admission.candidate" in policy, "committed no-effect receipt")
    check("modifier composition", "request.sourceModifierBps + admission.sourceModifierBps" in policy, "composed")
    check("post formula composition", "admission.postFormulaMultiplierBps / 10_000" in policy, "composed")
    check("poison aim transform", 'tag = "AIM_DISRUPTED"' in policy, "typed")

    check("resolver general resistance", "targetStatusResistance = request.encounter.resolvedStats.statusResistancePoints" in resolver, "connected")
    check("resolver tag resistance", "targetTagResistanceBps = request.encounter.resolvedStats.statusTagResistanceBps" in resolver, "connected")
    check("adapter tag resistance", "tagResistanceBpsByTag = request.targetTagResistanceBps" in adapter, "connected")
    check("resisted status keeps damage", "explicit target general and tag resistance can resist status without removing direct damage" in adapter_test, "covered")
    check("all candidate tests", "seven special candidate rules use explicit encounter and status facts" in policy_test, "covered")
    check("all modifier tests", "five apply modifiers resolve exact profile rank and status branches" in policy_test, "covered")
    check("no modifier leak", "do not leak to other profiles" in policy_test, "covered")
    check("dispatch transform test", "poison else aim transformation" in policy_test, "covered")

    check("resolver feature OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("Application unconnected", "VNextStatusAdmissionPolicyP5f" not in application, "not connected")
    check("legacy settlement unconnected", "VNextStatusAdmissionPolicyP5f" not in settlement, "not connected")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (336, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = (ROOT / "app/build/reports/lint-results-debug.txt").read_text()
    check("lint", "0 errors, 22 warnings" in lint, "0 errors, 22 existing warnings")
    check("APK exists", APK.is_file(), str(APK))
    if APK.is_file():
        check("APK hash", sha256(APK) == APK_HASH, sha256(APK))
        check("APK size", APK.stat().st_size == APK_SIZE, str(APK.stat().st_size))

    check("emulator audit", "verificationTarget=android-emulator" in audit and "avdName=alarmquest-qa" in audit, "alarmquest-qa")
    check("emulator API", "androidRelease=15" in audit and "apiLevel=35" in audit, "15 API 35")
    check("emulator install", "installResult=Success" in audit and "pmClearResult=Success" in audit, "Success")
    check("fresh roster", "freshRosterEmpty=true" in audit and "아직 캐릭터가 없습니다" in audit, "empty")
    check("cold focus", "launchState=COLD" in audit and "mFocusedApp=com.alarmquest/.MainActivity" in audit, "focused")
    check("fatal zero", "androidRuntimeFatalCount=0" in audit, "0")

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("emulator booted", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.alarmquest"])
        check("emulator app version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        activities = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("emulator focus", "com.alarmquest/.MainActivity" in activities.stdout, "MainActivity")
        fatal = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-t", "500", "AndroidRuntime:E", "*:S"])
        check("emulator fatal zero", not fatal.stdout.strip(), "fatal=0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5f PD: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
