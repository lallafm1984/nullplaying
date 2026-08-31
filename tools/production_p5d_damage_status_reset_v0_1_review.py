#!/usr/bin/env python3
"""PD audit for P5d atomic damage/status integration and fresh-start reset."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "fbe321d59e0ba7782e59a035c47dcc1e431301cf3ee5b8870187bca01f8e08ed"
APK_SIZE = 37_029_860
CODEC_HASH = "0165eb027b43f12d0e34718defb332a065af1f3394f8dd6399392faa33f37f7f"
STATUS_HASH = "05e8e1757019da14949069e55ffecd9da56ea01b4175000fc57979edcc03bb22"
ADAPTER_HASH = "5f76e4f6451d2fec273acf6a4429327bfac2cccbb480b61ec87ab5f166bcbc75"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt":
        "c59a1b68cafe60f943559c8b2750cccc3ae23d7274668d030df8d0a57cb395ac",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusRuntimeCatalogP5c.kt":
        "32b237e5960d9f90ed961e7d8c9f5f185c70969a57c1e64edb13e985cde8f8d6",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5d.kt":
        "5613246f6b426847f5be2b16d4dbb1798dd0cfde202e04dfac6caa5fdb4f5803",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "43b6db16918d7a3ee4f0f0d14af55d2324033e2583823cafd58de62774561765",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5dTest.kt":
        "1dedd5728f2dff149efec096d6542b6e3b54fec1673635f5bf7333eb2453e1d5",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "a90296300e828740996da6e50463d97a32e3490fd41cce7c3f5a0451010536db",
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

    lifecycle = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt").read_text()
    status = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusRuntimeCatalogP5c.kt").read_text()
    adapter = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5d.kt").read_text()
    adapter_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5dTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5D_DAMAGE_STATUS_RESET_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5d-device-reset-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("codec hash pinned", CODEC_HASH in lifecycle and CODEC_HASH in document, CODEC_HASH)
    check("status catalog hash pinned", STATUS_HASH in status and STATUS_HASH in document, STATUS_HASH)
    check("adapter hash pinned", ADAPTER_HASH in adapter and ADAPTER_HASH in document, ADAPTER_HASH)
    check("status source provenance", "sourceDefinitionId" in lifecycle and "sourceDefinitionId = definitionId" in status, "preserved")
    check("periodic channel persisted", "periodicDamageChannel" in lifecycle and "periodicDamageChannel = contract.periodicDamageChannel" in status, "preserved")
    check("strict channel validation", 'setOf("NONE", "PHYSICAL", "MAGICAL", "TRUE_DAMAGE")' in lifecycle, "4 channels")
    check("physical mitigation route", '"PHYSICAL" -> physicalMitigationBps' in lifecycle, "physical")
    check("magical mitigation route", '"MAGICAL" -> magicalMitigationBps' in lifecycle, "magical")
    check("three explicit periodic channels", all(token in status for token in (
        '"aq.skill.common.v06.bleedingcut" -> "PHYSICAL"',
        '"aq.skill.common.w4.emberbottle"',
        '"aq.skill.common.w4.poisonneedle" -> "MAGICAL"',
    )), "3")

    check("P5d rules version", "aq.damage-status-adapter.p5d.v0.1" in adapter, "v0.1")
    check("three adapter contracts", "V_NEXT_P5D_DAMAGE_STATUS_DEFINITION_COUNT: Int = 3" in adapter, "3")
    check("one physical two magical", "physicalDefinitions == 1 && magicalDefinitions == 2" in adapter, "1 and 2")
    check("atomic cost before payload", adapter.index("actorAfterCost") < adapter.index("VNextCombatPacketEngine.resolve"), "ordered")
    check("barrier blocks package", "barrierBlocked = primary.barrierConsumed > 0" in adapter, "blocked")
    check("shield does not gate status", "shieldAbsorbed" in adapter and "barrierBlocked" in adapter, "separate")
    check("character attack snapshot", "actorAfterCost.physicalAttack" in adapter and "actorAfterCost.magicalAttack" in adapter, "two axes")
    check("skill level status scaling", "skillLevel = request.activePlan.skillLevel" in adapter, "1..100")
    check("keyed secondary RNG", "keyedRollBps(request.rootReceiptId, slot.slotId)" in adapter, "stable key")
    check("replay receipt gate", "REPLAY_RECEIPT" in adapter, "fail closed")
    check("passive gate", "PASSIVE_ADAPTERS_REQUIRED" in adapter, "closed")
    check("P5d live gate", "val liveReady: Boolean get() = false" in adapter, "NO-GO")

    for test_name in (
        "damage and periodic status commit as one deterministic package",
        "miss spends cost once and records no status instance",
        "barrier blocks status package while shield absorption does not",
        "immunity rejects status but preserves direct damage",
        "skill level and character attack remain independent periodic scaling axes",
        "codec retains source and channel and tick uses magical mitigation",
    ):
        check(f"test {test_name}", test_name in adapter_test, "covered")

    check("resolver status request", "actorStatusState = heroStatus" in resolver and "targetStatusState = monsterStatus" in resolver, "connected")
    check("monster immunity snapshot", ".statusImmunities.toSet()" in resolver, "connected")
    check("receipt status hashes", "actorStatusBeforeHash" in resolver and "targetStatusAfterHash" in resolver, "4 hashes")
    check("transaction status payloads", "finalHeroStatusPayload" in resolver and "finalMonsterStatusPayload" in resolver, "2 payloads")
    check("transaction hash binds status", "transaction.finalHeroStatusHash" in resolver and "transaction.finalMonsterStatusHash" in resolver, "bound")
    check("resolver integration test", "p5d adapter commits status payload and hashes" in resolver_test, "covered")

    check("resolver feature default OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("Application P5d unconnected", "VNextDamageStatusCarrierAdapterP5d" not in application, "not connected")
    check("SettlementEngine P5d unconnected", "VNextDamageStatusCarrierAdapterP5d" not in settlement, "not connected")
    check("package data reset executed", "packageDataResetExecuted=true" in document and "packageDataResetExecuted=true" in audit, "true")
    check("character reset executed", "characterResetExecuted=true" in document and "characterResetExecuted=true" in audit, "true")
    check("exact package clear", "pm clear com.nullplaying" in audit and "pmClearResult=Success" in audit, "com.nullplaying")
    check("source and documents retained", "sourceDeletionExecuted=false" in document and "designDocumentDeletionExecuted=false" in document, "retained")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals("game-engine")
    app = totals("app")
    check("engine tests clean", engine == (319, 0, 0, 0), str(engine))
    check("app tests clean", app == (176, 0, 0, 0), str(app))
    lint = (ROOT / "app/build/reports/lint-results-debug.txt").read_text()
    check("lint clean", "0 errors, 22 warnings" in lint, "0 errors, 22 existing warnings")
    check("APK exists", APK.is_file(), str(APK))
    if APK.is_file():
        check("APK hash pinned", sha256(APK) == APK_HASH, sha256(APK))
        check("APK size frozen", APK.stat().st_size == APK_SIZE, str(APK.stat().st_size))

    check("device audit APK", f"apkSha256={APK_HASH}" in audit, APK_HASH)
    check("device audit launch", "launchState=COLD" in audit and "resumedActivity=com.nullplaying/.MainActivity" in audit, "cold and resumed")
    check("device audit focus", "mFocusedApp=com.nullplaying/.MainActivity" in audit, "focused")
    check("device audit fatal zero", "androidRuntimeFatalCount=0" in audit, "0")
    check("fresh database state", "postResetDatabaseFiles=3" in audit and "freshRosterEmpty=true" in audit, "schema recreated and roster empty")

    if args.with_device:
        devices = run(["adb", "devices", "-l"])
        check("SM-S931N online", "model:SM_S931N" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        version = run(["adb", "shell", "dumpsys", "package", "com.nullplaying"])
        check("device version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        activities = run(["adb", "shell", "dumpsys", "activity", "activities"])
        focused = [line.strip() for line in activities.stdout.splitlines() if "ResumedActivity:" in line or "mFocusedApp=" in line]
        check("MainActivity focused", any("com.nullplaying/.MainActivity" in line for line in focused), " | ".join(focused))
        fatal = run(["adb", "logcat", "-d", "-t", "400", "AndroidRuntime:E", "*:S"])
        check("no AndroidRuntime fatal", not fatal.stdout.strip(), "fatal=0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5d PD: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
