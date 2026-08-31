#!/usr/bin/env python3
"""PD audit for the P5h packet + canonical status mutation semantic adapter."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "6eb05924c04ae3531660a0521a310a453ac9c5d3801b226db4b51c23ed40f5d1"
APK_SIZE = 37_073_171
ADAPTER_HASH = "abacda84d5b8e60d49447d0047447e51330c1a0c75a9d388d20bbef05c81b78f"

FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusMutationSemanticAdapterP5h.kt":
        "e8623040317ec84815ba846b21f55272115e8cd939268f48817a35712110f8d1",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusMutationLedgerP5g.kt":
        "9eb58d80a79c3e4cc75da09fdc535e9aa57ec2ae0ae843624ad57f6a674c3e24",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusConsumptionHandlers.kt":
        "780d5e59cd70cba4747934749f12acf021e139e890b5eb742ad7de3fdee3565a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt":
        "a5dbd89112f553cbd5ce0a2479e9a479622738bbc6da24bfd09600b97bd11d63",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":
        "2cdee2b62e551bf986b48dfa60cab9ca9e9f8999e7e46031bd8986d84148187e",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusMutationSemanticAdapterP5hTest.kt":
        "7e53b7bab583933e4b1db69a1ddf0fa64012cc2de20cd3fa3484cd462332a053",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextStatusMutationLedgerP5gTest.kt":
        "eeab344b476a0c8fbe160791f385ebcdf3e9426f3d897b1820890261a1e8cc91",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt":
        "e67d9890084208b638af4457e68efae8d911659b4fb182d66c41ec43baabc479",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":
        "a88cc3df30f49001c0eeac5c3e70bf09c791a8d1909a8b1bf46a9359871381fa",
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

    adapter = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusMutationSemanticAdapterP5h.kt").read_text()
    ledger = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusMutationLedgerP5g.kt").read_text()
    consumption = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusConsumptionHandlers.kt").read_text()
    resolver = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
    ai = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt").read_text()
    adapter_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusMutationSemanticAdapterP5hTest.kt").read_text()
    resolver_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
    ai_test = (ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt").read_text()
    application = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5H_STATUS_MUTATION_SEMANTIC_ADAPTER_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5h-emulator-verification-v0.1.txt").read_text()

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        checks.append((name, condition, detail))

    check("P5h rules", "aq.status-mutation-semantic-adapter.p5h.v0.1" in adapter, "v0.1")
    check("P5h hash", ADAPTER_HASH in adapter and ADAPTER_HASH in document, ADAPTER_HASH)
    check("fourteen actives", "V_NEXT_P5H_STATUS_MUTATION_ACTIVE_COUNT: Int = 14" in adapter, "14")
    check("two passives", "V_NEXT_P5H_STATUS_MUTATION_PASSIVE_COUNT: Int = 2" in adapter, "2")
    check("physical coverage", "physicalDefinitions == 7" in adapter, "7")
    check("magical coverage", "magicalDefinitions == 6" in adapter, "6")
    check("support coverage", "supportOnlyDefinitions == 1" in adapter, "1")
    check("live OFF", "val liveReady: Boolean get() = false" in adapter, "OFF")
    check("P5c compile gate", "VNextRuntimeCorrectionLedgerP5c.compile" in adapter, "fail closed")
    check("P5g compile gate", "VNextStatusMutationLedgerP5g.compile" in adapter, "fail closed")
    check("descriptor gate", "DESCRIPTOR_CONTRACT_MISMATCH" in adapter, "fail closed")
    check("unsupported definition gate", "UNSUPPORTED_DEFINITION" in adapter, "fail closed")
    check("unsupported passive gate", "PASSIVE_ADAPTERS_REQUIRED" in adapter, "fail closed")
    check("duplicate passive gate", "PASSIVE_DEFINITION_DUPLICATED" in adapter, "fail closed")
    check("mutation replay gate", "REPLAY_RECEIPT" in adapter, "exact once")

    for definition_id in (
        "aq.skill.cleric.w2.sacredchain", "aq.skill.cleric.w4.cleanse",
        "aq.skill.external.w2.dragonscale", "aq.skill.mage.w4.chainlightning",
        "aq.skill.mage.w4.unseal", "aq.skill.paladin.w2.resolveburst",
        "aq.skill.paladin.w4.sunoath", "aq.skill.paladin.w6.cursedswordseal",
        "aq.skill.ranger.w2.rapidfire", "aq.skill.ranger.w4.stormpierce",
        "aq.skill.rogue.w2.twinknifeflurry", "aq.skill.warrior.w4.bloodwhirl",
        "aq.skill.world.w7.breakchains", "aq.skill.world.w8.glassdesertheat",
    ):
        check(f"contract {definition_id.split('.')[-1]}", definition_id in adapter, "pinned")

    check("preview miss vector", "packetHits = List(contract.packetCount) { false }" in adapter, "pure preview")
    check("packet engine", "VNextCombatPacketEngine.resolve" in adapter, "resolved")
    check("actual hit replay", "packetHits = actualHits" in adapter, "committed")
    check("barrier hit semantics", "barrierBlocksAllDamage" in adapter, "bound")
    check("packet residue coefficient", "preview.coefficientBps + preview.residueAddBps" in adapter, "bound")
    check("passive own level map", "request.passivePlans.associate { it.definitionId to it.skillLevel }" in adapter, "own level")
    check("mutation receipt detail", "mutationHash=" in adapter, "auditable")
    check("status state returned", "actorStatusAfter = mutation.state.actorStatus" in adapter, "canonical")
    check("pure support zero packet", "damageChannel == VNextRuntimeDamageChannelV2.NONE" in adapter, "no proxy damage")

    check("passive levels request", "val equippedPassiveLevels: Map<String, Int>" in ledger, "level aware")
    check("passive slot cap", "request.equippedPassiveLevels.size <= 3" in ledger, "3")
    check("passive level range", "request.equippedPassiveLevels.values.all { it in 1..100 }" in ledger, "1..100")
    check("elemental passive own level", "elementalResidueSkillLevel = request.equippedPassiveLevels" in ledger, "own level")
    check("well passive own level", "wellEchoSkillLevel = request.equippedPassiveLevels" in ledger, "own level")
    check("residue token id", '"passive.elemental_residue"' in ledger, "canonical")
    check("residue encounter clock", 'clock = "ENCOUNTER"' in ledger, "persistent")
    check("residue cost lock", "costLocked = true" in ledger, "protected")
    check("residue version CAS", "version = it[index].version + 1" in ledger, "versioned")
    check("residue consistency gate", "elementalResidueElement()).distinct().size <= 1" in ledger, "fail closed")
    check("elemental old token first", "An old residue token applies before" in consumption, "ordered")
    check("elemental own SkillLv", "request.elementalResidueSkillLevel ?: request.skillLevel" in consumption, "bound")
    check("well own SkillLv", "request.wellEchoSkillLevel ?: request.skillLevel" in consumption, "bound")

    check("AI status input hero", "selfEffects = heroStatus.toAiEffects(hero)" in resolver, "canonical")
    check("AI status input monster", "targetEffects = monsterStatus.toAiEffects(monster)" in resolver, "canonical")
    check("AI status projection", "private fun LifecycleState.toAiEffects" in resolver, "projected")
    check("AI tag subtype stacks", all(value in ai for value in ("val tag: String", "val subtype: String", "val stacks: Int")), "preserved")
    check("curse diversity gate", 'skill.pattern == "CURSE_DIVERSITY_CONSUMER"' in ai, "three subtypes")
    check("resolver state commit", "if (dispatched.statusStateChanged)" in resolver, "atomic result")
    check("resolver feature OFF", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver, "OFF")
    check("live settlement OFF", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver, "OFF")

    for phrase in (
        "rapid fire damage and poison consumption commit as one semantic result",
        "complete miss keeps reserved poison but commits cost and mutation receipt",
        "direct barrier blocks damage but a landed packet still commits status consumption",
        "pure cleanse uses deterministic secondary roll without creating one damage point",
        "curse transfer preserves canonical payload on hit and rolls back state on miss",
        "equipped passive uses its own skill level rather than the active skill level",
        "elemental residue persists between actions and spends its own passive level coefficient",
        "unsupported passive rejects before packet or status mutation",
        "same root and input produce identical packet state and mutation hash",
    ):
        check(f"test {phrase[:26]}", phrase in adapter_test, "covered")
    check(
        "resolver P5h integration test",
        "p5h mutation adapter consumes live target state and resolver commits its cooldown path" in resolver_test,
        "covered",
    )
    check(
        "AI curse diversity test",
        "curse diversity consumer enters candidates only with three distinct target subtypes" in ai_test,
        "covered",
    )

    check("Application P5h unconnected", "VNextStatusMutationSemanticAdapterP5h" not in application, "live off")
    check("legacy settlement P5h unconnected", "VNextStatusMutationSemanticAdapterP5h" not in settlement, "legacy safe")
    check("legacy resolveKill remains", "resolveKill" in settlement, "unchanged path")
    for relative, expected in FREEZE.items():
        actual = sha256(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)

    check("engine tests", totals("game-engine") == (357, 0, 0, 0), str(totals("game-engine")))
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
        "coldTotalTimeMs=4027", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "liveSettlementEnabled=false",
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
        run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5h-review-ui.xml"])
        ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5h-review-ui.xml"])
        check("emulator empty roster", "아직 캐릭터가 없습니다" in ui.stdout and "새 캐릭터" in ui.stdout, "fresh roster")
        logcat = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"])
        fatal = any(token in logcat.stdout for token in ("FATAL EXCEPTION", "AndroidRuntime: FATAL"))
        check("emulator fatal zero", not fatal, "0")

    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5h PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
