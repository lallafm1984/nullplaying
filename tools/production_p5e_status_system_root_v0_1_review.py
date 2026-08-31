#!/usr/bin/env python3
"""PD audit for P5e status system roots and emulator delivery."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "1dfcd4e658a7f0fbb02629dc62ff0c56d2b07e5e3b64e488613bc3fae8507308"
APK_SIZE = 37_032_860
CODEC_HASH = "78e59105e71311a8aa14893f728f150c01bc610351346d7a21f6bd113084b7a9"
STATUS_HASH = "e2c8ed26026370d0baa0e3d317c061ac7b1e2d035d975625c97ccc2ac573e2eb"
ROOT_HASH = "e8c68be6a7857e0a76997a369aa5c09dc3294d3b65949eb1ac26ac6571a44cca"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt":
        "b9df2f530b56be4fd772420138f86df0ee4241ea40ba294640505560cbdaf73e",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusRuntimeCatalogP5c.kt":
        "091b0fe5444273bf7c89fff3955bd3afa8e59285393c319354c9eea213852946",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusSystemRootSchedulerP5e.kt":
        "541ec40f2983cef4e71f0f68e42138bb5527a138c527e79e422dadb9420e6801",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "69922b42ffe29dd97576b4350495d64ad0959c789474bc8f59b1adf9afbb919a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5d.kt":
        "01cd40c42e1ff6978dad23b4b29e347af82391722babc509596bccea18670e7c",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusSystemRootSchedulerP5eTest.kt":
        "3b7cbf829d0be39d17e25aa309cc1833b79d3f6495d4854df5ebac86f0ee17ff",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "7eaf6226b1b1c536452e84095c5d87b5b577771c5ce543157a3e9136c2a0b01f",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5dTest.kt":
        "8f7c421e69251423ef160c0c2ed3b1aa4784f5fa7854227bc41b471ac22894f3",
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

    lifecycle = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt").read_text()
    status = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusRuntimeCatalogP5c.kt").read_text()
    scheduler = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusSystemRootSchedulerP5e.kt").read_text()
    scheduler_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusSystemRootSchedulerP5eTest.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    adapter_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextDamageStatusCarrierAdapterP5dTest.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5E_STATUS_SYSTEM_ROOT_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5e-emulator-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("codec hash", CODEC_HASH in lifecycle and CODEC_HASH in document, CODEC_HASH)
    check("status hash", STATUS_HASH in status and STATUS_HASH in document, STATUS_HASH)
    check("P5e root hash", ROOT_HASH in scheduler and ROOT_HASH in document, ROOT_HASH)
    for field in ("sourceDefinitionId", "periodicDamageChannel", "sourceCombatRank", "sourceSide"):
        check(f"provenance {field}", field in lifecycle and field in document, "stored")
    check("rank mitigation", "standardMitigationBps(it, status.sourceCombatRank)" in lifecycle, "standard formula")
    check("stronger snapshot replacement", "projectedPeriodicStrength" in lifecycle and "stronger character attack refresh" in adapter_test, "covered")
    check("tick replay receipt", "receiptId in state.receipts" in lifecycle and "replayIgnored = true" in lifecycle, "idempotent")
    check("linked DOT expiry", "linkedEffects = committed.linkedEffects - expiredGroups" in lifecycle, "atomic")

    check("P5e rules", "aq.status-system-root.p5e.v0.1" in scheduler, "v0.1")
    check("eight roots", "roots == 8" in scheduler and "P5eStatusSystemRoot.entries.size" in scheduler, "8")
    check("seven clocks", "V_NEXT_P5E_CLOCK_EVENT_COUNT: Int = 7" in scheduler, "7")
    for root in (
        "TARGET_ROOT_START", "NEXT_DIRECT_PACKET", "SOURCE_ACTION_END", "TARGET_ROOT_END",
        "SELF_ROOT_END", "ENCOUNTER_END", "EXPEDITION_LODGING", "CHARGE_CONSUMED",
    ):
        check(f"root {root}", root in scheduler, "declared")
    check("snapshot expiry guard", "eligibleInstanceIds" in scheduler, "root-start snapshot")
    check("source side guard", "status.sourceSide == request.sourceSide.name" in scheduler, "side matched")
    check("empty root no receipt", 'detail = "NO_WORK"' in scheduler and "empty root emits no receipt" in scheduler_test, "no-op")
    check("first lethal stops later", "lethal first periodic instance stops later instances" in scheduler_test, "covered")
    check("shield before HP no barrier", "shield then HP and never barrier" in scheduler_test, "covered")
    check("all clocks replay safe", "all seven clocks are executable and replay safe" in scheduler_test, "covered")

    check("resolver schema v0.2", "aq.battle-resolver-transaction.p5e.v0.2" in resolver, "v0.2")
    check("initial hero status", "heroStatusState" in resolver and "initialHeroStatusHash" in resolver, "bound")
    check("initial monster status", "monsterStatusState" in resolver and "initialMonsterStatusHash" in resolver, "bound")
    check("initial hash transaction identity", "initial state hash changes transaction identity" in resolver_test, "covered")
    check("DOT pre-action death", "before that actor receives an action" in resolver_test, "covered")
    check("system receipt kinds", "SYSTEM_STATUS_TICK" in resolver and "SYSTEM_STATUS_CLOCK" in resolver, "typed")
    check("action system count split", "systemRootCount" in resolver and "actionCount = receipts.count" in resolver, "split")
    check("direct packet clock", "dispatched.directPacketLanded" in resolver, "connected")
    check("source action clock", "P5eStatusSystemRoot.SOURCE_ACTION_END" in resolver, "connected")
    check("target root clock", "P5eStatusSystemRoot.TARGET_ROOT_END" in resolver, "connected")
    check("self root clock", "P5eStatusSystemRoot.SELF_ROOT_END" in resolver, "connected")
    check("encounter clock", "P5eStatusSystemRoot.ENCOUNTER_END" in resolver, "connected")
    check("feature OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("Application unconnected", "VNextStatusSystemRootSchedulerP5e" not in application, "not connected")
    check("legacy settlement unconnected", "VNextStatusSystemRootSchedulerP5e" not in settlement, "not connected")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (329, 0, 0, 0), str(totals("game-engine")))
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
    check("cold focus", "launchState=COLD" in audit and "mFocusedApp=com.nullplaying/.MainActivity" in audit, "focused")
    check("fatal zero", "androidRuntimeFatalCount=0" in audit, "0")

    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and " device " in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("emulator booted", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying"])
        check("emulator app version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        activities = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("emulator focus", "com.nullplaying/.MainActivity" in activities.stdout, "MainActivity")
        fatal = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-t", "500", "AndroidRuntime:E", "*:S"])
        check("emulator fatal zero", not fatal.stdout.strip(), "fatal=0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5e PD: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
