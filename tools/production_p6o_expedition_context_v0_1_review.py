#!/usr/bin/env python3
"""PD audit for P6o same-region previous-profile expedition context."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIT = ROOT / "artifacts/audit/production-p6o-emulator-verification-v0.1.txt"
SCREEN = ROOT / "artifacts/audit/production-p6o-emulator-screen-v0.1.png"
UI = ROOT / "artifacts/audit/production-p6o-emulator-ui-v0.1.xml"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P6O_EXPEDITION_CONTEXT_v0.1.md"
RUNTIME = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextExpeditionContextRuntimeP6o.kt"
RUNTIME_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextExpeditionContextRuntimeP6oTest.kt"
RESOLVER = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt"
RESOLVER_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt"
SETTLEMENT = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt"

APK_SHA = "7d79a202e5d04667b8963dc75a62931b2e794ed20e3249331b2be33c441bbf4c"
SCREEN_SHA = "66fd6a6524073b48ada871769cdc477e22dd8106d4a99987c6c200e565b4e1cf"
UI_SHA = "42c7772b8fdf4d22cbc9b7ffbcd9290705a336df9fb7769eb23bb061546c86cc"
APK_SIZE = 37_867_623
FREEZE = {
    RUNTIME: "00016048a94a1e2fff3d0a6128158cd2386abd2af0869ccfe0c6ebb6bed4fedc",
    RUNTIME_TEST: "28e57e51ee90dd4cb942bfe409adb2a9439af6f7bfc4844f53d02a2df0c0268a",
    RESOLVER: "4295f61fa5c2298efc24bcf38d766b5cac9a708510a6578eeb4ec9f657f2e849",
    RESOLVER_TEST: "3ca3096a26d71402e6110595533fb8ee5bf1475b2c16e850f855d6442e81f8c1",
    SETTLEMENT: "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def totals(path: Path) -> tuple[int, int, int, int]:
    result = [0, 0, 0, 0]
    for report in path.glob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            result[index] += int(root.attrib.get(key, "0"))
    return tuple(result)


def parse_audit(text: str) -> dict[str, str]:
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def command(*args: str) -> str:
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)
    return (result.stdout + result.stderr).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: object) -> None:
        checks.append((name, ok, str(detail)))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")

    required = [APK, AUDIT, SCREEN, UI, DOC, *FREEZE]
    for path in required:
        check(f"file:{path.relative_to(ROOT)}", path.is_file(), path.exists())
    if not all(path.is_file() for path in required):
        return 1

    runtime = RUNTIME.read_text()
    runtime_test = RUNTIME_TEST.read_text()
    resolver = RESOLVER.read_text()
    resolver_test = RESOLVER_TEST.read_text()
    settlement = SETTLEMENT.read_text()
    audit = parse_audit(AUDIT.read_text())
    doc = DOC.read_text()

    for name, anchor in {
        "rules": "aq.expedition-context.p6o.v0.1",
        "content": "f0842aa3e2a4d450b5455d1a517cc148292a5b567db1b5d3093d5a79706eccda",
        "definitions": "V_NEXT_P6O_EXPEDITION_CONTEXT_DEFINITION_COUNT = 2",
        "cap": "V_NEXT_P6O_PROFILE_HIT_CAP_BPS = 1_200",
        "combat record": "aq.skill.common.w8.combatrecord",
        "veteran eye": "aq.skill.warrior.w6.veteraneye",
        "context version": "require(version == 1",
        "context identity": "val expeditionId: String",
        "context region": "val regionId: String",
        "context index": "val encounterIndex: Int",
        "previous profile": "val previousMonsterProfile: String",
        "future consumable evidence": "val noConsumableUsed: Boolean",
        "future family evidence": "val priorSameFamilyEncounterCountInRegion: Int",
        "future weakness evidence": "val knownWeaknessFamilyIds: Set<String>",
        "sorted context": "knownWeaknessFamilyIds.sorted()",
        "deferred exact": "deferredExpeditionContextIds == deferredExpeditionContextIdsP6o.sorted()",
        "live false": "val liveReady: Boolean get() = false",
        "context required": "EXPEDITION_CONTEXT_REQUIRED",
        "region mismatch": "EXPEDITION_REGION_MISMATCH",
        "same region": "context.previousEncounterRegionId == currentRegionId",
        "same profile": "context.previousMonsterProfile == currentMonsterProfile",
        "attack gate": "if (attackAction && sameProfileConsecutiveEncounter)",
        "add clamp": ".coerceAtMost(V_NEXT_P6O_PROFILE_HIT_CAP_BPS)",
        "replay": "receiptId !in ledger.processedReceiptIds",
        "stale": "plan.ledgerBeforeHash == ledger.ledgerHash",
        "event": "P5wPassiveEvent.EXPEDITION_CONTEXT",
        "stack group": 'source.stackGroup != "aq.stack.wave6.profile_hit"',
        "anchors": "listOf(200, 400, 600, 800, 1_000)",
        "extension": "P6bOutgoingPacketModifier.withP6o",
        "combined profile": "profileHitAddBps + plan.profileHitAddBps",
    }.items():
        check(f"runtime:{name}", anchor in runtime, anchor)

    for name, anchor in {
        "version": "aq.battle-resolver-transaction.p6o.v0.14",
        "request context": "val expeditionContext: P6oExpeditionContextSnapshot? = null",
        "identity context": "request.expeditionContext?.snapshotHash.orEmpty()",
        "bind failure": "PASSIVE_EXPEDITION_CONTEXT_BIND_REJECTED",
        "ledger failure": "PASSIVE_EXPEDITION_CONTEXT_LEDGER_REJECTED",
        "compile": "VNextExpeditionContextRuntimeP6oCompiler.compile(registry)",
        "bind": "expeditionContextRuntime.bind(",
        "encounter region": "currentRegionId = request.encounter.regionId",
        "encounter profile": "currentMonsterProfile = request.encounter.profile.name",
        "basic prepare": 'expeditionContextSession.prepareOutgoing(\n                                "BASIC"',
        "active prepare": "val expeditionOutgoingPlan = expeditionContextSession.prepareOutgoing(",
        "compose": ".withP6c(afterOutgoingPlan).withP6o(expeditionOutgoingPlan)",
        "commit": "expeditionContextSession.commit(",
        "receipt": '"$receiptId:p6o-outgoing"',
        "hash snapshot": "append(transaction.expeditionContextSnapshotHash)",
        "hash count": "append(transaction.expeditionProfileHitAppliedCount)",
        "handled": "addAll(VNextExpeditionContextRuntimeP6o.supportedDefinitionIds())",
    }.items():
        check(f"resolver:{name}", anchor in resolver, anchor)

    for name, anchor in {
        "coverage": "coverage_enables_two_profile_memories_and_defers_six_cross_system_context_passives",
        "anchors": "mapOf(1 to 400, 25 to 800, 50 to 1_200, 75 to 1_200, 100 to 1_200)",
        "zero cases": "profile_or_region_change_and_non_attack_action_produce_zero_without_guessing",
        "required": "context_is_required_only_when_a_supported_memory_is_equipped_and_region_must_match",
        "stable hash": "context_hash_is_set_order_independent_and_changes_on_campaign_evidence",
        "fail closed": "replay_and_stale_plan_fail_closed_while_committed_counts_are_hashed",
        "cap assertion": "assertEquals(minOf(1_200, expected + 300), modified.profileHitAddBps)",
    }.items():
        check(f"unit:{name}", anchor in runtime_test, anchor)

    for name, anchor in {
        "case": "p6o same region consecutive profile adds and clamps two expedition memories",
        "context": "P6oExpeditionContextSnapshot(",
        "profile evidence": "previousMonsterProfile = base.encounter.profile.name",
        "observed 1200": "assertEquals(listOf(1_200), observedProfileHit)",
        "content": "V_NEXT_P6O_EXPEDITION_CONTEXT_CONTENT_HASH",
        "snapshot": "assertEquals(context.snapshotHash, result.expeditionContextSnapshotHash)",
        "commit once": "assertEquals(1, result.expeditionContextCommittedOutgoingActionCount)",
        "applied once": "assertEquals(1, result.expeditionProfileHitAppliedCount)",
        "same profile true": "assertTrue(result.sameProfileConsecutiveEncounter)",
        "detail": '"profileHit=1200" in it.detail',
        "rehash": "VNextBattleResolverTransaction.transactionHash(result)",
    }.items():
        check(f"integration:{name}", anchor in resolver_test, anchor)

    check("settlement:present", "fun resolveKill(" in settlement, "present")
    check("settlement:uncoupled", "P6o" not in settlement and "p6o" not in settlement, "uncoupled")
    for path, expected in FREEZE.items():
        check(f"freeze:{path.name}", sha(path) == expected, sha(path))
    check("artifact:apk size", APK.stat().st_size == APK_SIZE, APK.stat().st_size)
    check("artifact:apk hash", sha(APK) == APK_SHA, sha(APK))
    check("artifact:screen hash", sha(SCREEN) == SCREEN_SHA, sha(SCREEN))
    check("artifact:ui hash", sha(UI) == UI_SHA, sha(UI))
    check("artifact:png", SCREEN.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), SCREEN.stat().st_size)
    ui_text = UI.read_text()
    check("artifact:ui parse", ET.parse(UI).getroot().tag == "hierarchy", "hierarchy")
    check("artifact:empty roster", "아직 캐릭터가 없습니다" in ui_text, "empty")
    check("artifact:create", "새 캐릭터" in ui_text, "create")

    engine = totals(ROOT / "game-engine/build/test-results/test")
    debug = totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    total = tuple(sum(values) for values in zip(engine, debug, release))
    check("reports:engine", engine == (724, 0, 0, 0), engine)
    check("reports:debug", debug == (88, 0, 0, 0), debug)
    check("reports:release", release == (88, 0, 0, 0), release)
    check("reports:total", total == (900, 0, 0, 0), total)
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(1 for issue in lint.findall("issue") if issue.attrib.get("severity") == "Error")
    warnings = sum(1 for issue in lint.findall("issue") if issue.attrib.get("severity") == "Warning")
    check("reports:lint errors", errors == 0, errors)
    check("reports:lint warnings", warnings == 22, warnings)

    expected_audit = {
        "serial": "emulator-5554", "avdName": "alarmquest-qa", "androidRelease": "15",
        "apiLevel": "35", "packageName": "com.nullplaying", "versionName": "0.1.0",
        "versionCode": "1", "targetSdk": "36", "apkSizeBytes": str(APK_SIZE),
        "apkSha256": APK_SHA, "installResult": "Success", "pmClearResult": "Success",
        "launchState": "COLD", "coldTotalTimeMs": "5280", "coldWaitTimeMs": "5283",
        "topResumedActivity": "com.nullplaying/.MainActivity", "freshRosterEmpty": "true",
        "androidRuntimeFatalCount": "0", "emulatorScreenshotSha256": SCREEN_SHA,
        "emulatorUiDumpSha256": UI_SHA, "engineTests": "724", "appDebugTests": "88",
        "appReleaseTests": "88", "totalTests": "900", "testFailures": "0",
        "testErrors": "0", "testSkipped": "0", "lintErrors": "0", "lintWarnings": "22",
        "featureDefaultEnabled": "false", "liveSettlementEnabled": "false",
        "previousPassiveDefinitionsEnabled": "47", "expeditionContextDefinitionsEnabled": "2",
        "totalPassiveDefinitionsEnabled": "49", "remainingPassiveDefinitions": "41",
        "expeditionContextDefinitionsClassified": "8", "expeditionContextDefinitionsDeferred": "6",
        "expeditionContextRulesVersion": "aq.expedition-context.p6o.v0.1",
        "expeditionContextContentHash": "f0842aa3e2a4d450b5455d1a517cc148292a5b567db1b5d3093d5a79706eccda",
        "battleResolverRulesVersion": "aq.battle-resolver-transaction.p6o.v0.14",
        "combatRecordEnabled": "true", "veteranEyeEnabled": "true",
        "profileHitSharedCapBps": "1200", "campaignContextWiredToLiveCaller": "false",
        "visualAssetRequired": "false", "designTeamImageRequested": "false",
    }
    for key, expected in expected_audit.items():
        check(f"audit:{key}", audit.get(key) == expected, audit.get(key))

    for anchor in [
        "Passive 49/90", "전투 기록", "백전노장의 눈", "버전형 캠페인 문맥",
        "Lv9,999 안전성", "보류 6종", "라이브 ON 전 필수 조건",
        "디자인팀 이미지 요청과 UI 변경은 하지 않았다", "PD 조건부 승인",
        APK_SHA, FREEZE[RUNTIME], FREEZE[RESOLVER],
        "engine 724 + app debug 88 + app release 88 = total 900",
    ]:
        check(f"doc:{anchor[:28]}", anchor in doc, anchor)

    if args.with_emulator:
        devices = command("adb", "devices", "-l")
        package = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying")
        activity = command("adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities")
        check("emulator:connected", "emulator-5554" in devices and " device " in devices, devices)
        check("emulator:release", command("adb", "-s", "emulator-5554", "shell", "getprop", "ro.build.version.release") == "15", "15")
        check("emulator:api", command("adb", "-s", "emulator-5554", "shell", "getprop", "ro.build.version.sdk") == "35", "35")
        check("emulator:version", "versionName=0.1.0" in package and "versionCode=1" in package, "0.1.0(1)")
        check("emulator:target", "targetSdk=36" in package, "36")
        check("emulator:focus", "com.nullplaying/.MainActivity" in activity and "Resumed" in activity, "MainActivity")

    passed = sum(ok for _, ok, _ in checks)
    failed = len(checks) - passed
    print(f"SUMMARY PASS={passed} FAIL={failed} TOTAL={len(checks)}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
