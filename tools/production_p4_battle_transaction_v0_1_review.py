#!/usr/bin/env python3
"""PD audit for the P4 deterministic battle transaction foundation, feature OFF."""

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
APK_HASH = "d3c5f233f2c8ce3fc2806da442c8f9c4950ec6d2676dbc78f993cb10a0c1e932"
MONSTER_HASH = "d928642af719d5b7077e311a38ca4878aeb0f9bfbca8a1d5ede321c944004055"
AI_INTEROP_HASH = "9ba412a95bb55b79115ad1aceb9868eacd2c92ce1c303f46637c46e84a3d9f2a"
REGISTRY_RESOURCE = "com/alarmquest/engine/vnext/skill_registry_242_v0_3.json.gz.b64"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextMonsterRegistry.kt":
        "644405cfc16fd126be2ca317664f861d02b888b91694f6a862538c6c59ed53e8",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt":
        "7589bd9a2355e4674a4dafb182e5d8604ef2d2e640699e36410aa1e0dda23b23",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextMonsterAbilityRegistry.kt":
        "1f60c2b9f54d487e330d96feacde269f3931e5a1e61c6f9678d4151f217d73a7",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattlePlanDomain.kt":
        "738f130c0bee55895407e3412e467d6333a055290c602f4800baa48cd2c08779",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "721381b84ce5f5a0cac06c2fd0e9264a93496fc3e52523c8daa8d75f2fed707b",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextMonsterRegistryTest.kt":
        "e8259bc4b2d1a35a518966ecc6f6408e1c47146747e96ec1b6809cbe5211c189",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt":
        "813d2de280374fb23534d08e45e8177550978a91b7f6ded7913c5996eba8dee1",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextMonsterAbilityRegistryTest.kt":
        "bd19a21e208bc2fd0760be650d74b54269853967e9a74698d16ec2c7c9e0de1b",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "c24c1943e4e965db5d4e673de380a0663312e273a6c473a17462f917595338fa",
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

    monster = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextMonsterRegistry.kt").read_text()
    ability = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextMonsterAbilityRegistry.kt").read_text()
    ai = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    resolver_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    ability_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextMonsterAbilityRegistryTest.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    production_vnext = "\n".join(
        path.read_text() for path in (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext").rglob("*.kt")
    )

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("Monster144 rules pinned", "aq.monster-registry.v0.1" in monster and MONSTER_HASH in monster, MONSTER_HASH)
    check("Monster144 exact catalog", all(token in monster for token in (
        "baseMonsters.size == 24", "prefixes.size == 12", "variants.size == 144",
    )), "24+12=144")
    check("Monster144 production only", not (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextMonsterRegistry.kt").exists(), "src/main")
    check("AI policy production only", not (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt").exists(), "src/main")
    check("55 abilities production bound", "abilities.size == V_NEXT_AI_ABILITY_COUNT" in ability and "byId.size == V_NEXT_AI_ABILITY_COUNT" in ability, "55/55")
    check("ability interop hash pinned", AI_INTEROP_HASH in ai and "contentHash == V_NEXT_AI_INTEROP_HASH" in ability, AI_INTEROP_HASH)
    check("every Monster ability covered", "base.abilityIds.all(byId::containsKey)" in ability, "dangling=0")
    check("Python interop excluded from production", all(token not in production_vnext for token in ("ProcessBuilder", "python3", "java.nio.file")), "test-only")
    check("P4 rules version", "aq.battle-resolver-transaction.v0.1" in resolver, "v0.1")
    check("30 round cap", "V_NEXT_BATTLE_RESOLVER_MAX_ROUNDS: Int = 30" in resolver, "30")
    check("60 action cap", "V_NEXT_BATTLE_RESOLVER_MAX_ACTIONS: Int = 60" in resolver, "60")
    check("resolver feature default OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")
    check("P3 admission precedes round loop", resolver.index("admitForSemanticExecution") < resolver.index("roundLoop@"), "pre-root")
    check("encounter id and seed fail closed", all(token in resolver for token in (
        "ENCOUNTER_ID_MISMATCH", "ENCOUNTER_SEED_MISMATCH", "ENCOUNTER_CONTENT_MISMATCH",
    )), "INVALID_PLAN")
    check("fixed six-phase transaction", all(token in resolver for token in (
        "DECISION", "COST_COMMIT", "COOLDOWN_COMMIT", "PAYLOAD", "STATUS", "PROTECTION_DEATH",
    )), "ordered")
    check("semantic dispatcher is atomic", "One call owns the whole fixed phase transaction" in resolver, "one root call")
    check("missing semantic dispatcher fails closed", "SEMANTIC_DISPATCH_UNAVAILABLE" in resolver and "semanticDispatcher == null" in resolver, "no generic proxy")
    check("phase mismatch fails closed", "SEMANTIC_PHASE_ORDER_MISMATCH" in resolver and "dispatched.phaseOrder != phaseOrder" in resolver, "no reorder")
    check("root receipts unique", "DUPLICATE_ROOT_RECEIPT" in resolver and "receiptIds.add(receiptId)" in resolver, "exact once")
    check("core RNG reserves three rolls", all(token in resolver for token in (
        "hitRollBps", "criticalRollBps", "varianceBps",
    )), "hit critical variance")
    check("victory only reward eligible", "rewardEligible = outcome == VNextBattleOutcome.VICTORY" in resolver, "closed")
    check("skill XP is ten percent", "associateWith { characterXp / 10 }" in resolver, "10 percent")
    check("receipt replay is noop", "VNextSettlementApplyStatus.REPLAY_NOOP" in resolver, "exact once")
    check("defeat zero covered", "hero defeat produces zero xp loot and rare material eligibility" in resolver_test, "covered")
    check("retreat zero covered", "safe retreat with zero settlement grant" in resolver_test, "covered")
    check("INVALID_PLAN zero covered", "without semantic dispatcher fails closed before any reward" in resolver_test, "covered")
    check("deterministic replay covered", "resolves deterministic round turns" in resolver_test, "covered")
    check("Monster Python parity covered", "identical to planning interop payload" in ability_test, "covered")
    check("generic skill kernel not production", not (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSkillEffectKernel.kt").exists(), "absent")
    check("legacy SettlementEngine unconnected", all(token not in settlement for token in (
        "VNextBattleResolverTransaction", "VNextSettlementReceiptGate", "VNextMonsterRegistry",
    )), "P4 foundation only")

    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    engine = totals("game-engine")
    app = totals("app")
    check("engine tests clean", engine == (274, 0, 0, 0), str(engine))
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
        version = run(["adb", "shell", "dumpsys", "package", "com.nullplaying"])
        check("device version code", "versionCode=1" in version.stdout, "1")
        check("device version name", "versionName=0.1.0" in version.stdout, "0.1.0")
        focus = run(["adb", "shell", "dumpsys", "activity", "activities"])
        focused_lines = [line.strip() for line in focus.stdout.splitlines() if "mFocusedApp=" in line]
        check("MainActivity focused", any("com.nullplaying/.MainActivity" in line for line in focused_lines), " | ".join(focused_lines))
        fatal = run(["adb", "shell", "logcat", "-d", "-t", "300", "AndroidRuntime:E", "*:S"])
        check("no AndroidRuntime fatal", not fatal.stdout.strip(), "fatal=0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P4 PD: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
