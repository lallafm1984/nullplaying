#!/usr/bin/env python3
"""PD audit for P5t prepared-control semantics and emulator evidence."""
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
SCREENSHOT = ROOT / "artifacts/audit/production-p5t-emulator-screen-v0.1.png"
APK_HASH = "05ca6167b2e402a14066340caf76ceb54632526bc8bd079692e7fa92c90f5188"
APK_SIZE = 37_493_051
SCREENSHOT_HASH = "f4ee63a860c13fc37b2a38aa871a8a23e007026fc84cb31346ee6e854a6fc195"
ADAPTER_HASH = "6f32c4a249a438be729a7d5571af2a06dc7498e12a718eb633a9a8f060aa33a9"
COMPOSITE_HASH = "1065e52d36285afbb0b9602f808bc9852e525fd07de7598082ae5a639731cc05"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedControlSemanticAdapterP5t.kt": "7af007cc0878b6af632a8796b2d8d72629b8a91de4998f416004269927e9fecf",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5t.kt": "a5da722f6d67e9cb691a7f53788fa480e2372ee3157de0b49df1717580b20e44",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedDefenseSemanticAdapterP5p.kt": "21dabfd14c296ca59a1110928d84b5c218503fc53fd505aa780147494d0d059a",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLethalDelaySemanticAdapterP5q.kt": "fe45c360975b2927b0651e5b336f6e21bb89b4ec816fc4f110b0356f5db9833f",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMissCounterSemanticAdapterP5r.kt": "e57d5a3795ed9ee90068878c9a7b60be25cb1e9f2c682678eb11affee7148321",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSpellReflectSemanticAdapterP5s.kt": "ebfe339f0a43a862f91a5883695cd719bc6e8d07d00ec28bbcb871ccc75a4ad7",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt": "74104a81adf140ac5ab145296d3ddaeb46e6621e8cd3d6981b1e5ccc9abddad2",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt": "65cae6c46ca2a9f52495a02b289fc86755f545e8dfd2dc4af45bf64eb98c5f01",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedControlSemanticAdapterP5tTest.kt": "6ce4f1d821dbe99d3901e4634b67611fb68b5ec2a81ccd12565b4fcbe30b4fde",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5tTest.kt": "868cb8ceca1bbdd35745f5d7e48a8a7afbd222b38f1cf341b12e8634f009956e",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt": "a15cf90833e7d058d595355339b833a72ee120518e12d3b1a1f69391691f41f2",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt": "9016e9f5c0dd88fd04210de9f57bd5a4994a9e6acb784a21b87fa14da227edd7",
    "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt": "06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=False)


def totals(module: str) -> tuple[int, int, int, int]:
    values = [0, 0, 0, 0]
    for path in (ROOT / module / "build/test-results").glob("test*/TEST-*.xml"):
        root = ET.parse(path).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            values[index] += int(root.attrib.get(key, 0))
    return tuple(values)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-gradle", action="store_true")
    parser.add_argument("--with-emulator", action="store_true")
    args = parser.parse_args()
    if args.with_gradle:
        result = run(["./gradlew", "test", "lintDebug", "assembleDebug"])
        if result.returncode:
            print(result.stdout)
            print(result.stderr, file=sys.stderr)
            return result.returncode

    paths = {
        "adapter": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedControlSemanticAdapterP5t.kt",
        "composite": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5t.kt",
        "resolver": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt",
        "ai": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt",
        "adapter_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedControlSemanticAdapterP5tTest.kt",
        "composite_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5tTest.kt",
        "resolver_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt",
        "ai_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt",
    }
    text = {key: (ROOT / value).read_text() for key, value in paths.items()}
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5T_PREPARED_CONTROL_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5t-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    adapter = text["adapter"]
    for name, needle in (
        ("rules", "aq.prepared-control-adapter.p5t.v0.1"),
        ("adapter hash", ADAPTER_HASH),
        ("one definition", "V_NEXT_P5T_PREPARED_CONTROL_DEFINITION_COUNT = 1"),
        ("one token", "V_NEXT_P5T_REACTION_TOKEN_CAP = 1"),
        ("skill", "aq.skill.ranger.w3.trapsetup"),
        ("class", 'request.actorClassId != "RANGER"'),
        ("pattern", 'it.pattern == "PREPAID_CONTROL"'),
        ("growth", 'it.growthField == "stagger_apply_bps"'),
        ("anchors", "listOf(6_000, 6_750, 7_500, 8_250, 9_000)"),
        ("zero attack equivalent", "it.attackEquivalentValues.all { value -> value == 0 }"),
        ("registry cost", "it.resourceCostBps == 1_500"),
        ("registry cooldown", "it.cooldownRoots == 5"),
        ("descriptor no damage", "VNextRuntimeDamageChannelV2.NONE"),
        ("descriptor target", "VNextRuntimeCarrierTarget.CURRENT_ENEMY"),
        ("descriptor modifier", "VNextRuntimeCarrierDeliveryV2.MODIFIER"),
        ("descriptor no core RNG", "!it.consumesCoreHitRoll && !it.critEligible"),
        ("prepared handler", "PreparedHandlerId.PREPAID_CONTROL_REACTION"),
        ("shared reaction", "PREPARED_REACTION_TOKEN_OCCUPIED"),
        ("token id", 'TOKEN_INSTANCE_ID = "prepared.control-trap"'),
        ("token tag", 'tag = "PREPARED_CONTROL_TRAP"'),
        ("encounter clock", 'clock = "ENCOUNTER"'),
        ("resource prepay", "request.actor.resourceBps - cost"),
        ("strict codec", "VNextStatusStateCodecP5c.encode"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in adapter)

    for name, needle in (
        ("runtime prepare", "VNextPreparedControlRuntimeP5t"),
        ("commit start", "commitAtEnemyActionStart"),
        ("token CAS", "TOKEN_CAS_REJECTED"),
        ("replay guard", "REPLAY_RECEIPT"),
        ("attempt consume", 'receipts = actorState.receipts + actorReceiptId'),
        ("stagger tag", 'tag = "STAGGER"'),
        ("target owner", "owner = LifecycleOwner.TARGET"),
        ("roll mode", "mode = LifecycleApplyMode.ROLL"),
        ("one duration", "duration = 1"),
        ("target root clock", 'clock = "TARGET_ROOT_END"'),
        ("unique refresh", 'stackPolicy = "UNIQUE_REFRESH_MAX"'),
        ("immunity input", "immunities = targetImmunities"),
        ("status resistance", "targetStatusResistance = targetStatusResistance"),
        ("tag resistance", "tagResistanceBpsByTag = targetTagResistanceBps"),
        ("boss input", "boss = targetBoss"),
        ("no hit requirement", "hitRequired = false"),
        ("bounded roll", "rollBps.coerceIn(0, 9_999)"),
        ("immune detail", '"IMMUNE"'),
        ("resisted detail", '"RESISTED"'),
        ("consumed detail", "consumed=true"),
        ("source-specific block", "sourceDefinitionId == VNextPreparedControlSemanticAdapterP5t.DEFINITION_ID"),
        ("snapshot-specific block", "it.instanceId in eligibleInstanceIds"),
    ):
        check(name, needle in adapter)

    check("status lifecycle hash", "V_NEXT_STATUS_LIFECYCLE_CONTENT_HASH" in adapter)
    check("adapter hash document", ADAPTER_HASH in document, ADAPTER_HASH)

    resolver = text["resolver"]
    for name, needle in (
        ("block plan", "blockedByPreparedControl"),
        ("block action", '"P5T_STAGGERED_ACTION_SKIP"'),
        ("block reason", '"PREPARED_CONTROL_STAGGER"'),
        ("block cooldown contract", "cooldownTick=true|abilityCooldown=false"),
        ("block action count", "actorActionCount++"),
        ("resolver prepare", "VNextPreparedControlRuntimeP5t.prepare(heroStatus)"),
        ("secondary event", '"P5T_TRAP_STAGGER"'),
        ("secondary RNG", "DeterministicRandom.forEvent"),
        ("monster immunities", "targetImmunities = baseMonster.statusImmunities.toSet()"),
        ("monster status resistance", "request.encounter.resolvedStats.statusResistancePoints"),
        ("monster tag resistance", "request.encounter.resolvedStats.statusTagResistanceBps"),
        ("monster boss", 'request.encounter.rank.name == "BOSS"'),
        ("resolver commit", "commitAtEnemyActionStart"),
        ("separate receipt", 'val trapReceiptId = "$receiptId:p5t-trap"'),
        ("system receipt", 'actionId = "P5T_TRAP_STAGGER_APPLICATION"'),
        ("system reason", 'decisionReason = "NEXT_VALID_ENEMY_ACTION_START"'),
        ("status phase", "listOf(VNextResolverPhase.STATUS)"),
        ("receipt roll", "hitRollBps = trapRoll"),
        ("receipt applied", "hit = trapCommit.applied"),
        ("receipt no damage", "rawDamage = 0"),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
        ("AI beneficial visibility", '"PREPARED_CONTROL_TRAP"'),
    ):
        check(name, needle in resolver)
    trap_start = resolver.index("if (trapCommit.attempted) {")
    trap_end = resolver.index("val ability = monsterIntent", trap_start)
    trap_block = resolver[trap_start:trap_end]
    check("no trap resource mutation", "resourceBps =" not in trap_block, "none")
    check("no trap action increment", "actorActionCount++" not in trap_block, "none")
    check("no trap hp mutation", ".copy(hp" not in trap_block, "none")

    ai = text["ai"]
    for name, needle in (
        ("AI pattern", 'skill.pattern == "PREPAID_CONTROL"'),
        ("AI shared occupancy", 'return "PREPARED_REACTION_OCCUPIED"'),
        ("AI target stagger", 'return "TARGET_ALREADY_STAGGERED"'),
        ("AI trap visible", '"PREPARED_CONTROL_TRAP"'),
    ):
        check(name, needle in ai)
    ai_control_start = ai.index('if (skill.pattern == "PREPAID_CONTROL")')
    ai_control_end = ai.index('if ("EXECUTION" in text', ai_control_start)
    check("AI no telegraph dependency", "telegraph" not in ai[ai_control_start:ai_control_end], "none")

    composite = text["composite"]
    for name, needle in (
        ("composite rules", "aq.composite-semantic-dispatcher.p5t.v0.1"),
        ("composite hash", COMPOSITE_HASH),
        ("families", "V_NEXT_P5T_COMPOSITE_FAMILY_COUNT = 14"),
        ("definitions", "V_NEXT_P5T_COMPOSITE_DEFINITION_COUNT = 58"),
        ("prior", "VNextCompositeSemanticDispatcherP5sCompiler.compile"),
        ("added", "VNextPreparedControlSemanticAdapterP5tCompiler.compile"),
        ("duplicate closed", "P5T_DUPLICATE_OWNERS"),
        ("unsupported closed", "P5T_COMPOSITE_UNSUPPORTED_DEFINITION"),
    ):
        check(name, needle in composite)
    check("composite hash document", COMPOSITE_HASH in document, COMPOSITE_HASH)

    for phrase in (
        "coverage pins one ranger trap definition and all upstream contracts",
        "level one and level one hundred arm exact chance and pay one cost",
        "wrong class occupied token and replay fail closed without mutation",
        "normal monster uses resistance and tag resistance before deterministic roll",
        "boss hard control is halved after normal formula",
        "immunity and resistance consume the trap without refund or status",
        "only eligible trap stagger blocks a later enemy action",
        "unused trap expires at encounter end",
    ):
        check(f"adapter test {phrase[:31]}", phrase in text["adapter_tests"], "covered")
    for phrase in (
        "coverage extends fifty seven definitions with one prepared control",
        "prior spell reflect and trap preserve complete route provenance",
        "unsupported definition and wrong trap class fail without mutation",
        "trap replay is deterministic and every definition has one family",
    ):
        check(f"composite test {phrase[:29]}", phrase in text["composite_tests"], "covered")
    check(
        "AI integration test",
        "trap setup needs no telegraph but avoids occupied reactions and staggered targets" in text["ai_tests"],
        "covered",
    )
    check(
        "resolver integration test",
        "p5t trap attempt lets the current enemy act then blocks exactly the next enemy action" in text["resolver_tests"],
        "covered",
    )
    check("resolver resisted proof", 'assertTrue("RESISTED" in attempts.first().detail)' in text["resolver_tests"], "covered")
    check("resolver current action proof", 'assertNotEquals("P5T_STAGGERED_ACTION_SKIP"' in text["resolver_tests"], "covered")
    check("resolver next block proof", "blocked.actionIndex > currentEnemyAction.actionIndex" in text["resolver_tests"], "covered")

    check("app unconnected", "VNextCompositeSemanticDispatcherP5t" not in app, "safe")
    check("legacy unconnected", "VNextCompositeSemanticDispatcherP5t" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)
    check("engine tests", totals("game-engine") == (522, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK", APK.is_file())
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == APK_SIZE, str(APK.stat().st_size if APK.is_file() else 0))
    check("screenshot", SCREENSHOT.is_file())
    check(
        "screenshot hash",
        SCREENSHOT.is_file() and sha(SCREENSHOT) == SCREENSHOT_HASH,
        sha(SCREENSHOT) if SCREENSHOT.is_file() else "missing",
    )
    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=4433", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=522",
        "appTests=176", "totalTests=698", "activeDefinitionsExecutable=58",
        "semanticFamilies=14", "featureDefaultEnabled=false", "liveSettlementEnabled=false",
        f"emulatorScreenshotSha256={SCREENSHOT_HASH}",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)
    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and "device" in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("boot", boot.stdout.strip() == "1", boot.stdout.strip())
        release = run(["adb", "-s", "emulator-5554", "shell", "getprop", "ro.build.version.release"])
        sdk = run(["adb", "-s", "emulator-5554", "shell", "getprop", "ro.build.version.sdk"])
        check("android release", release.stdout.strip() == "15", release.stdout.strip())
        check("API", sdk.stdout.strip() == "35", sdk.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying"])
        check("version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        check("target sdk", "targetSdk=36" in version.stdout, "36")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("focus", "topResumedActivity" in focus.stdout and "com.nullplaying/.MainActivity" in focus.stdout, "MainActivity")
        ui = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5t-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5t-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")
    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5t PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
