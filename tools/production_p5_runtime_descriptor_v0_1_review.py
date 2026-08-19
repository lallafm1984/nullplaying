#!/usr/bin/env python3
"""PD audit for P5 Registry242 runtime descriptor schema coverage, feature OFF."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "5c05910c85140899a738d043200b33bf38c55e14d5ca7220a9b1c9571a6756bd"
REGISTRY_RESOURCE = "com/alarmquest/engine/vnext/skill_registry_242_v0_3.json.gz.b64"
SCHEMA_HASH = "d48fe84758e520d328172117fecfebe6c399a52ae2c1334e222de4240211e450"
CATALOG_HASH = "a37f9604a9b329e550e8d88c647e941e1e9ee0f9b39c52e1291a8ec64802b961"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSkillRuntimeSchemaV04Draft2.kt":
        "cb80914116c83368f7d9952b4ae482ef675d790752eb7429c7be8053c78ad068",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextSkillRuntimeSchemaV04Draft2Test.kt":
        "32bb4e6622b2119a2d039dd27386514c6354bfc86094480985ef2660c5d53957",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextRuntimeDescriptorCatalogP5.kt":
        "c5e423dbcfcaa909796a907b35969f1f36267c5494110bb9a5bd2ab0436221ca",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextRuntimeDescriptorCatalogP5Test.kt":
        "9fa0ff969cb3aa494d9f75dc6fd5643a0f738c86b654915fda312689e8746d5a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSemanticRuntimeReadiness.kt":
        "1872154fd890bf41a06217f341551fcc3cf7608c2541dbfd44d2604463e178db",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "721381b84ce5f5a0cac06c2fd0e9264a93496fc3e52523c8daa8d75f2fed707b",
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
    parser.add_argument("--with-device", action="store_true")
    args = parser.parse_args()
    if args.with_gradle:
        completed = run(["./gradlew", "test", "lintDebug", "assembleDebug"])
        if completed.returncode:
            print(completed.stdout)
            print(completed.stderr, file=sys.stderr)
            return completed.returncode

    schema = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSkillRuntimeSchemaV04Draft2.kt").read_text()
    catalog = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextRuntimeDescriptorCatalogP5.kt").read_text()
    test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextRuntimeDescriptorCatalogP5Test.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    repository = (ROOT / "app/src/main/java/com/alarmquest/data/repository/GameRepository.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5_RUNTIME_DESCRIPTOR_CATALOG_v0.1.md").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("draft2 rules version", "aq.skill-runtime-schema.v0.4-draft.2" in schema, "draft.2")
    check("draft2 schema hash pinned", SCHEMA_HASH in schema and SCHEMA_HASH in document, SCHEMA_HASH)
    for field in (
        "damageChannel", "carrierTarget", "carrierDelivery", "carrierPacketCount",
        "runtimeOpcode", "triggerOpcode", "runtimeStateSchemaVersion", "secondaryRngSlots",
    ):
        check(f"draft2 field {field}", f'"{field}"' in schema, "declared")
    check("effect carrier model", "data class VNextRuntimeEffectCarrierV2" in schema, "typed")
    check("mixed carrier coverage", "hybrid action can declare direct heal and status carriers" in (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextSkillRuntimeSchemaV04Draft2Test.kt").read_text(), "covered")
    check("P5 rules version", "aq.runtime-descriptor-catalog.p5.v0.1" in catalog, "v0.1")
    check("P5 catalog hash pinned", CATALOG_HASH in catalog and CATALOG_HASH in document, CATALOG_HASH)
    check("schema ready 242", "V_NEXT_P5_SCHEMA_READY_DEFINITION_COUNT: Int = 242" in catalog and "assertEquals(242, coverage.schemaReadyDefinitions)" in test, "242/242")
    check("executable adapter zero", "V_NEXT_P5_EXECUTABLE_ADAPTER_COUNT: Int = 0" in catalog and "assertEquals(0, catalog.coverage.executableAdapterDefinitions)" in test, "0/242")
    check("live admission NO_GO", "P5RuntimeAdmissionStatus.NO_GO" in test, "NO_GO")
    check("Active definitions exact", "assertEquals(152, coverage.activeDefinitions)" in test, "152")
    check("Passive definitions exact", "assertEquals(90, coverage.passiveDefinitions)" in test, "90")
    check("Active pattern contracts exact", "assertEquals(124, coverage.activePatternContracts)" in test, "124")
    check("Passive pattern contracts exact", "assertEquals(76, coverage.passivePatternContracts)" in test, "76")
    check("all descriptor schemas valid", "VNextSkillRuntimeSchemaV04Draft2.validate(it).isEmpty()" in test, "invalid=0")
    check("no name role owner inference", all(token in catalog for token in (
        "Definition names", "roles", "owner class", "never choose a channel",
    )), "explicit patterns and stable ID overrides")
    check("magical control stable-ID override", all(token in catalog for token in (
        "aq.skill.mage.a3.arcaneexposure", "aq.skill.mage.a3.powerdamping", "aq.skill.mage.a3.sensedistortion",
    )), "3 IDs")
    check("rapid jabs three packets", '"aq.skill.common.w8.rapidjabs" to 3' in catalog and "legacyPacketDriftIds" in test, "1 legacy drift -> 3")
    check("periodic channels explicit", all(token in catalog for token in (
        "aq.skill.common.v06.bleedingcut", "aq.skill.common.w4.emberbottle", "aq.skill.common.w4.poisonneedle",
    )), "3 periodic definitions")
    check("310 carriers frozen", "assertEquals(310, carriers.size)" in test, "310")
    for channel, count in (
        ("NONE", 113), ("PHYSICAL", 76), ("STATUS_ONLY", 55), ("MAGICAL", 37),
        ("HEALING", 8), ("SHIELD", 15), ("RESOURCE", 3), ("ADAPTIVE_DECLARED", 3),
    ):
        check(f"channel {channel}", f"VNextRuntimeDamageChannelV2.{channel} to {count}" in test, str(count))
    for delivery, count in (
        ("MODIFIER", 105), ("EXECUTION", 5), ("DIRECT", 98), ("STATUS_APPLICATION", 55),
        ("HEAL", 8), ("SHIELD", 13), ("CLEANSE", 3), ("RESOURCE", 3),
        ("PERIODIC", 3), ("DELAYED", 10), ("INTERRUPT", 1), ("BARRIER", 2), ("DISPEL", 4),
    ):
        check(f"delivery {delivery}", f"VNextRuntimeCarrierDeliveryV2.{delivery} to {count}" in test, str(count))
    check("259 trigger bindings", "assertEquals(259, triggerCounts.values.sum())" in test, "259")
    check("152 active root triggers", "assertEquals(152, triggerCounts[VNextRuntimeTriggerOpcodeV2.ACTIVE_ROOT_SELECTED])" in test, "152")
    check("200 unique opcodes", "assertEquals(200, catalog.descriptors.map" in test, "200")
    check("48 secondary RNG slots", "assertEquals(48, rngSlots.size)" in test, "48")
    check("P4 resolver remains default OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement remains OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("SettlementEngine unconnected", all(token not in settlement for token in (
        "VNextRuntimeDescriptorCatalogP5", "VNextBattleResolverTransaction", "VNextSettlementReceiptGate",
    )), "not connected")
    check("repository has no P5 cutover", all(token not in repository for token in (
        "VNextRuntimeDescriptorCatalogP5", "VNextBattleTransaction", "VNextSettlementReceipt",
    )), "not connected")
    check("character reset not executed", "characterResetExecuted=false" in document, "false")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals("game-engine")
    app = totals("app")
    check("engine tests clean", engine == (291, 0, 0, 0), str(engine))
    check("app tests clean", app == (176, 0, 0, 0), str(app))
    lint = (ROOT / "app/build/reports/lint-results-debug.txt").read_text()
    check("lint clean", "0 errors, 22 warnings" in lint, "0 errors, 22 existing warnings")
    check("APK exists", APK.is_file(), str(APK))
    if APK.is_file():
        check("APK hash pinned", sha256(APK) == APK_HASH, sha256(APK))
        with zipfile.ZipFile(APK) as archive:
            check("Registry242 packaged", REGISTRY_RESOURCE in archive.namelist(), REGISTRY_RESOURCE)

    if args.with_device:
        devices = run(["adb", "devices", "-l"])
        check("SM-S931N online", "model:SM_S931N" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        version = run(["adb", "shell", "dumpsys", "package", "com.alarmquest"])
        check("device version code", "versionCode=1" in version.stdout, "1")
        check("device version name", "versionName=0.1.0" in version.stdout, "0.1.0")
        focus = run(["adb", "shell", "dumpsys", "activity", "activities"])
        focused_lines = [line.strip() for line in focus.stdout.splitlines() if "mFocusedApp=" in line]
        check("MainActivity focused", any("com.alarmquest/.MainActivity" in line for line in focused_lines), " | ".join(focused_lines))
        fatal = run(["adb", "shell", "logcat", "-d", "-t", "300", "AndroidRuntime:E", "*:S"])
        check("no AndroidRuntime fatal", not fatal.stdout.strip(), "fatal=0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5 PD: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
