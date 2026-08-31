#!/usr/bin/env python3
"""PD audit for P5b direct/execution adapter and shared combat packets."""

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
APK_HASH = "1360c320eabe39103e2c68cbcbb25ed4181ee44e202e5c9abe692de40c507f35"
REGISTRY_RESOURCE = "com/alarmquest/engine/vnext/skill_registry_242_v0_3.json.gz.b64"
ADAPTER_HASH = "30236992c6c168274cb017091a7bbbc6e2dec03e957559978bcc43315fc1f41e"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt":
        "677a9d5c8f36259c98e14b4a7ab4b4221c47e074bcf369fadf73bd0e7c89a1ee",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDirectExecutionCarrierAdapterP5b.kt":
        "cd777ae8c7bc20024994c7652c2125f2d0ce5cbb08db5be26ddb8327f1c69cd1",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "516ca95d3908a25152ec479f3476493797d60e5282da9cd94cf5cbf7faee37a0",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDirectExecutionCarrierAdapterP5bTest.kt":
        "2a6807c52a88a66e68d91b043b89accfdecdeb22cc09343c744e6e4e40f02ddb",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "0a88b281283ca6053a05ecec4690d4fcb7ada4f0b93d6e19ee509ae7768e7252",
    "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt":
        "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}

DIRECT_IDS = (
    "aq.skill.cleric.a1.sacredradiance",
    "aq.skill.mage.a1.arcanepulse",
    "aq.skill.paladin.a1.convictionstrike",
    "aq.skill.ranger.a1.steadyshot",
    "aq.skill.rogue.a1.seizeopening",
    "aq.skill.warrior.a1.frontlinedrive",
    "aq.skill.common.w2.precisestrike",
    "aq.skill.common.w8.rapidjabs",
    "aq.skill.monster.w7.golemshard",
)
EXECUTION_IDS = (
    "aq.skill.common.w4.certainfinish",
    "aq.skill.boss.w7.executionbell",
)


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

    packet = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt").read_text()
    adapter = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDirectExecutionCarrierAdapterP5b.kt").read_text()
    adapter_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDirectExecutionCarrierAdapterP5bTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    repository = (ROOT / "app/src/main/java/com/alarmquest/data/repository/GameRepository.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5B_DIRECT_EXECUTION_ADAPTER_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5b-device-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("packet rules version", "aq.combat-packet.p5b.v0.1" in packet, "v0.1")
    check("critical multiplier 1.5", "CRITICAL_MULTIPLIER_BPS: Int = 15_000" in packet, "15000")
    check("mitigation cap 70 percent", "MITIGATION_CAP_BPS: Int = 7_000" in packet, "7000")
    check("fixed damage channels", all(token in packet for token in (
        "VNextRuntimeDamageChannelV2.PHYSICAL", "VNextRuntimeDamageChannelV2.MAGICAL",
        "VNextRuntimeDamageChannelV2.TRUE_DAMAGE",
    )), "physical magical true")
    check("character frame attack axis", "val attack = when" in packet and "request.actor.physicalAttack" in packet, "frame")
    check("skill coefficient axis", "totalCoefficientBps" in packet and "splitCoefficient" in packet, "SkillLv input")
    check("one root RNG contract", "reserve one root hit/critical/variance roll" in packet, "deterministic")
    check("packet coefficient conserved", "val remainder = totalBps % packetCount" in packet, "integer split")
    check("barrier per direct packet", "VNextRuntimeCarrierDeliveryV2.EXECUTION" in packet and "barrierCharges - 1" in packet, "charge")
    check("shield sequential absorb", "target.shield - shield" in packet, "packet order")
    check("resolver delegates common damage", "VNextCombatPacketEngine.resolve" in resolver, "single formula")
    check("adapter rules version", "aq.direct-execution-adapter.p5b.v0.1" in adapter, "v0.1")
    check("adapter hash pinned", ADAPTER_HASH in adapter and ADAPTER_HASH in document, ADAPTER_HASH)
    check("adapter definition count", "V_NEXT_P5B_DIRECT_ADAPTER_DEFINITION_COUNT: Int = 11" in adapter, "11")
    check("direct count exact", "directDefinitions == 9" in adapter, "9")
    check("execution count exact", "executionDefinitions == 2" in adapter, "2")
    for definition_id in DIRECT_IDS + EXECUTION_IDS:
        check(f"contract {definition_id}", definition_id in adapter and definition_id in document, "pinned")
    check("six explicit corrections", "legacyCorrectionIds" in adapter and "assertEquals(6, coverage.legacyCorrectionIds.size)" in adapter_test, "6")
    check("rapid jabs three packet correction", "rapidjabs:carrierPacketCount=3" in adapter, "3")
    check("golem fixed hit correction", "golemshard:fixedHitBps=6000" in adapter, "6000")
    check("execution bell crit correction", "executionbell:critEligible=false" in adapter, "forbidden")
    check("compiled catalog gate", "VNextDirectExecutionCarrierAdapterP5bCompiler" in adapter and "P5_DESCRIPTOR_CATALOG_REJECTED" in adapter, "fail closed")
    check("unknown definitions rejected", 'request.rejected("UNSUPPORTED_DEFINITION")' in adapter, "closed")
    check("passives rejected", 'request.rejected("PASSIVE_ADAPTERS_REQUIRED")' in adapter, "5+3 NO-GO")
    check("phase mismatch rejected", 'request.rejected("PHASE_ORDER_MISMATCH")' in adapter, "six phases")
    check("adaptive hybrid rejected test", "unsupported adaptive hybrid and passive loadouts reject" in adapter_test, "covered")
    check("skill resource level scaling", 'growthField == "resource_cost_bps"' in adapter, "SkillLv")
    check("miss cost test", "level scaled resource cost is committed once even on a miss" in adapter_test, "covered")
    check("character skill dual scaling test", "character attack and skill coefficient as separate scaling axes" in adapter_test, "covered")
    check("physical magical channel test", "physical and magical carriers use their own attack defense" in adapter_test, "covered")
    check("multi packet protection test", "three packet jab splits total coefficient" in adapter_test, "covered")
    check("execution threshold test", "execution checks threshold and remains blockable by barrier" in adapter_test, "covered")
    check("end to end resolver test", "complete resolver transaction" in resolver_test and "receipt.detail.startsWith(\"P5B:\")" in resolver_test, "AI to receipt")
    check("deterministic transaction replay", "assertEquals(first, replay)" in resolver_test, "same seed")
    check("foundation ready live false", "assertTrue(compiled.coverage.foundationReady)" in adapter_test and "assertFalse(compiled.coverage.liveReady)" in adapter_test, "GO/NO-GO split")
    check("resolver feature default OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("Application unconnected", "VNextDirectExecutionCarrierAdapterP5b" not in application and "VNextBattleResolverTransaction" not in application, "not connected")
    check("SettlementEngine unconnected", "VNextDirectExecutionCarrierAdapterP5b" not in settlement and "VNextCombatPacketEngine" not in settlement, "not connected")
    check("repository remains default off", "V_NEXT_SKILL_BUILD_FEATURE_DEFAULT_ENABLED" in repository, "OFF")
    check("no legacy deletion executed", "legacyDeletionExecuted=false" in document and "legacyDeletionExecuted=false" in audit, "false")
    check("no character reset executed", "characterResetExecuted=false" in document and "characterResetExecuted=false" in audit, "false")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals("game-engine")
    app = totals("app")
    check("engine tests clean", engine == (300, 0, 0, 0), str(engine))
    check("app tests clean", app == (176, 0, 0, 0), str(app))
    lint = (ROOT / "app/build/reports/lint-results-debug.txt").read_text()
    check("lint clean", "0 errors, 22 warnings" in lint, "0 errors, 22 existing warnings")
    check("APK exists", APK.is_file(), str(APK))
    if APK.is_file():
        check("APK hash pinned", sha256(APK) == APK_HASH, sha256(APK))
        check("APK size frozen", APK.stat().st_size == 36_986_432, str(APK.stat().st_size))
        with zipfile.ZipFile(APK) as archive:
            check("Registry242 packaged", REGISTRY_RESOURCE in archive.namelist(), REGISTRY_RESOURCE)

    check("device audit hash", f"apkSha256={APK_HASH}" in audit, APK_HASH)
    check("device audit cold launch", "launchState=COLD" in audit and "launchTotalTimeMs=505" in audit, "505ms")
    check("device audit focus", "topResumedActivity=com.nullplaying/.MainActivity" in audit and "mFocusedApp=com.nullplaying/.MainActivity" in audit, "focused")
    check("device audit fatal zero", "androidRuntimeFatalCount=0" in audit, "0")

    if args.with_device:
        devices = run(["adb", "devices", "-l"])
        check("SM-S931N online", "model:SM_S931N" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        version = run(["adb", "shell", "dumpsys", "package", "com.nullplaying"])
        check("device version code", "versionCode=1" in version.stdout, "1")
        check("device version name", "versionName=0.1.0" in version.stdout, "0.1.0")
        activities = run(["adb", "shell", "dumpsys", "activity", "activities"])
        focused = [line.strip() for line in activities.stdout.splitlines() if "topResumedActivity=" in line or "mFocusedApp=" in line]
        check("MainActivity focused", any("com.nullplaying/.MainActivity" in line for line in focused), " | ".join(focused))
        fatal = run(["adb", "logcat", "-d", "-t", "400", "AndroidRuntime:E", "*:S"])
        check("no AndroidRuntime fatal", not fatal.stdout.strip(), "fatal=0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5b PD: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
