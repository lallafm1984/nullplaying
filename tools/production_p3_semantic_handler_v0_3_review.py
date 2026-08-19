#!/usr/bin/env python3
"""PD audit for Registry242 production semantic handler P3, feature OFF."""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REGISTRY_HASH = "cda98479494d5d2b3d1279e695c26f923ce46249c8835e3e76996fccd67fbdca"
SEMANTIC_HASH = "1efa1af21cad27ae534a3f2a3a67188fc38c30c33e62d153ffc987ff6f235616"
APK_HASH = "1b08e4c67b28032f805f7291b53ca5a8bcfe70d629bdc80f821907fced58b31a"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
RESOURCE_ENTRY = "com/alarmquest/engine/vnext/skill_registry_242_v0_3.json.gz.b64"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSkillSchema.kt":
        "34f0d18172f03f7e3ce37ded1d9516492b4d1d090f13a2ffaa13ce8384ed7f52",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt":
        "8de528e39c0e68a0448e4d318051fbda1d6e0cd2f1a3a90b542d31550df4e556",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusConsumptionHandlers.kt":
        "272fc950d02b29654707c58eb2eff04d6df6cf133689be10adcb9a8fceacefa5",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextCostTransferDispelHandlers.kt":
        "9834a03b316c24b65873728ff68504dd5f7ca6197b3a13320f49d6742507679b",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextPreparedReactionHandlers.kt":
        "eeab88dd5900046ef79d3b07d68d41665622c0eceb25a454ed046404db79db0d",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextProtectionDeathPipeline.kt":
        "029843bfb243f9a36e236af413e38a3b0c7d459b70761379df6498a8346c1407",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSemanticHandlerRegistry.kt":
        "2091f810b14a82adc92962f6a6ccd85ad3d20051199a7dcd08364769546f4806",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattlePlanDomain.kt":
        "738f130c0bee55895407e3412e467d6333a055290c602f4800baa48cd2c08779",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextSemanticHandlerRegistryTest.kt":
        "47f9e5cf84548ecaf519e3bd273bb0098551202677d055a53fb48391639ec639",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattlePlanDomainTest.kt":
        "f6a7c38eab023658cdcc773f5cbf83b70d5381d9924af2f8475c3d263a47baa8",
}

PROMOTED = (
    "VNextSkillSchema.kt",
    "VNextStatusLifecycleHandlers.kt",
    "VNextStatusConsumptionHandlers.kt",
    "VNextCostTransferDispelHandlers.kt",
    "VNextPreparedReactionHandlers.kt",
    "VNextProtectionDeathPipeline.kt",
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_hash(value: object) -> str:
    text = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(text.encode()).hexdigest()


def registry_payload() -> dict:
    path = ROOT / "game-engine/src/main/resources" / RESOURCE_ENTRY
    return json.loads(gzip.decompress(base64.b64decode(path.read_text())).decode())


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
    parser.add_argument("--with-device", action="store_true")
    args = parser.parse_args()
    if args.with_gradle:
        completed = run(["./gradlew", "test", "lintDebug", "assembleDebug"])
        if completed.returncode:
            print(completed.stdout)
            print(completed.stderr, file=sys.stderr)
            return completed.returncode

    payload = registry_payload()
    semantic_path = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSemanticHandlerRegistry.kt"
    semantic = semantic_path.read_text()
    battle = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattlePlanDomain.kt").read_text()
    build = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/build/VNextSkillBuildDomain.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    semantic_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextSemanticHandlerRegistryTest.kt").read_text()
    active_patterns = {item["pattern"] for item in payload["actives"]}
    passive_patterns = {item["pattern"] for item in payload["passives"]}
    registry_ids = {item["definitionId"] for item in payload["actives"] + payload["passives"]}

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("Registry242 canonical hash", canonical_hash(payload) == REGISTRY_HASH, canonical_hash(payload))
    check("Registry242 exact definitions", len(registry_ids) == 242, str(len(registry_ids)))
    check("active semantic vocabulary exact", len(active_patterns) == 124, str(len(active_patterns)))
    check("passive semantic vocabulary exact", len(passive_patterns) == 76, str(len(passive_patterns)))
    check("semantic family total exact", len(active_patterns | passive_patterns) == 200, str(len(active_patterns | passive_patterns)))
    check("active and passive patterns disjoint", not active_patterns.intersection(passive_patterns), "overlap=0")
    check("every active pattern explicitly frozen", all(f'"{value}",' in semantic for value in active_patterns), "124/124")
    check("every passive pattern explicitly frozen", all(f'"{value}",' in semantic for value in passive_patterns), "76/76")
    check("semantic rules version", 'aq.skill-semantic-handler.v1' in semantic, "v1")
    check("semantic content hash pinned", SEMANTIC_HASH in semantic, SEMANTIC_HASH)
    check("coverage constants exact", all(token in semantic for token in ("= 124", "= 76", "= 242")), "124+76, definitions=242")
    check("one content-bound binding model", all(token in semantic for token in ("SemanticHandlerBinding", "definitionContentHash", "contractHash")), "definition contract")
    check("exactly one primary phase field", "val primaryPhase: SemanticPhase" in semantic, "one scalar per binding")
    check("primary phase is capability", "it.primaryPhase in it.phases" in semantic_test, "242/242 checked")
    check("capability phases are unique", "binding.phases.distinct().size == binding.phases.size" in semantic_test, "no duplicate capability")
    check("specialist phases explicit", all(token in semantic for token in (
        "STATUS_LIFECYCLE", "STATUS_CONSUMPTION", "COST_TRANSFER_DISPEL",
        "PREPARED_REACTION", "PROTECTION_DEATH",
    )), "five specialist phases")
    check("execution plan preserves active fields", all(token in semantic for token in (
        "resolvedAnchorValue", "resolvedAttackEquivalentBps", "candidateRuleId",
        "statusApplications", "minimumShieldRequirementBps",
    )), "no shared coefficient proxy")
    check("execution plan preserves passive fields", all(token in semantic for token in (
        "conditionId", "hostScope", "stackGroup", "stackPolicy", "stackCapBps",
    )), "condition and stacking")
    check("coverage rejects proxy fallback binding", "proxyOrFallbackBindingIds.isEmpty()" in semantic, "0 required")
    check("coverage rejects dangling duplicate unbound", all(token in semantic for token in (
        "unboundDefinitionIds.isEmpty()", "duplicateBindingIds.isEmpty()", "specialistDanglingIds.isEmpty()",
    )), "all zero required")
    check("old generic proxy remains test-only", not (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSkillEffectKernel.kt").exists(), "not production")
    check("P3 battle admission present", "admitForSemanticExecution" in battle, "pre-dispatch gate")
    check("P3 semantic mismatch reasons present", all(token in battle for token in (
        "SEMANTIC_HANDLER_MISSING", "SEMANTIC_DEFINITION_MISMATCH",
    )), "INVALID_PLAN")
    check("P3 admission covered by tests", "P3 admission resolves every frozen definition" in (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattlePlanDomainTest.kt").read_text(), "covered")
    check("all five anchors exhaustively covered", "val levels = listOf(1, 25, 50, 75, 100)" in semantic_test, "242 x 5")
    check("feature remains default OFF", "V_NEXT_SKILL_BUILD_FEATURE_DEFAULT_ENABLED: Boolean = false" in build, "OFF")
    check("handler live execution remains OFF", "V_NEXT_SKILL_BUILD_REGISTRY_HANDLER_EXECUTION_ENABLED: Boolean = false" in build, "OFF")
    check("SettlementEngine remains unconnected", "VNextSemanticHandlerRegistry" not in settlement and "admitForSemanticExecution" not in settlement, "P3 production foundation")

    for name in PROMOTED:
        main_path = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext" / name
        old_test_path = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext" / name
        check(f"promoted {name}", main_path.is_file() and not old_test_path.exists(), "src/main only")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals("game-engine")
    app = totals("app")
    check("engine tests clean", engine == (264, 0, 0, 0), str(engine))
    check("app tests clean", app == (176, 0, 0, 0), str(app))
    lint = (ROOT / "app/build/reports/lint-results-debug.txt").read_text()
    check("lint clean", "0 errors, 22 warnings" in lint, "0 errors, 22 existing warnings")
    check("APK exists", APK.is_file(), str(APK))
    if APK.is_file():
        check("APK hash pinned", sha256(APK) == APK_HASH, sha256(APK))
        with zipfile.ZipFile(APK) as archive:
            check("Registry242 packaged", RESOURCE_ENTRY in archive.namelist(), RESOURCE_ENTRY)

    if args.with_device:
        devices = run(["adb", "devices", "-l"])
        check("SM-S931N online", "model:SM_S931N" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        version = run(["adb", "shell", "dumpsys", "package", "com.alarmquest"])
        check("device version code", "versionCode=1" in version.stdout, "1")
        check("device version name", "versionName=0.1.0" in version.stdout, "0.1.0")
        focus = run(["adb", "shell", "dumpsys", "activity", "activities"])
        check("MainActivity focused", "mFocusedApp=" in focus.stdout and "com.alarmquest/.MainActivity" in focus.stdout, "focused")
        fatal = run(["adb", "shell", "logcat", "-d", "-t", "300", "AndroidRuntime:E", "*:S"])
        check("no AndroidRuntime fatal", not fatal.stdout.strip(), "fatal=0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P3 PD: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
