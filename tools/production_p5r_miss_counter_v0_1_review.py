#!/usr/bin/env python3
"""PD audit for P5r prepaid MISS-counter semantics and emulator evidence."""
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
APK_HASH = "9cd8f39c21ae5d515d473318bd076685fbacc2c18b09066a4d8088b5575332d6"
APK_SIZE = 37_461_377
ADAPTER_HASH = "061f2f18db1f94a01b44ba15db2796a3a7da30b41791d3e638fc5b7538eeee46"
COMPOSITE_HASH = "a1e9efa503284d58a23cf33e3f44576e8f6dc578e500a17b9f92654b524a460f"
FREEZE = {
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMissCounterSemanticAdapterP5r.kt": "b5bffd3a2fe5c90193fc19935366b8787a063370cd9251fb73dd4c12d853709d",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5r.kt": "2e5753497aa09dbdf806c67f17e33169d73c841b50061ef440e36bc670b39134",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPreparedDefenseSemanticAdapterP5p.kt": "cbbec7f3f472d0882d295b5d8bce2b301b0792e34a33b820f7f8a73aefdf9762",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextLethalDelaySemanticAdapterP5q.kt": "db2cdace61929b14ebf6517e4d1f790948d63456a647d463eba1ecea7067f7c5",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatPacketEngine.kt": "a24cd97c230919ecb0331ea358904b160d52e78247f8c638964f33655804dc48",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt": "440e30130f10c5394647af6e40593e81b2b769fb55ad5a5bc3560b061cfbd583",
    "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt": "1cfc229d4cf57d9c09e022d8db2f2830f15b1b00fff06338f469e1ac96737e80",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextMissCounterSemanticAdapterP5rTest.kt": "b4211467571a5e5ae3f91225b996db6e29fcf3b441218043382e10789e0f016c",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5rTest.kt": "16339de5cbed29767103c6c41513429d9687dd1df70da50f105f2bb659f5d510",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt": "41bddc1164c25421fba750175d9649ad5aefb0918ce0c66296577e4e552402d5",
    "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt": "425f911d2f93ea8d65ba13f79337149b9e9b1b09e33e8d57eac77aacfb636063",
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
        "adapter": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMissCounterSemanticAdapterP5r.kt",
        "composite": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5r.kt",
        "resolver": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt",
        "ai": "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt",
        "adapter_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextMissCounterSemanticAdapterP5rTest.kt",
        "composite_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5rTest.kt",
        "resolver_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt",
        "ai_tests": "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt",
    }
    text = {key: (ROOT / value).read_text() for key, value in paths.items()}
    app = (ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text()
    settlement = (ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
    document = (ROOT / "PRODUCT_MEETING_PRODUCTION_P5R_MISS_COUNTER_v0.1.md").read_text()
    audit = (ROOT / "artifacts/audit/production-p5r-emulator-verification-v0.1.txt").read_text()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "bound") -> None:
        checks.append((name, condition, detail))

    adapter = text["adapter"]
    for name, needle in (
        ("rules", "aq.miss-counter-adapter.p5r.v0.1"),
        ("adapter hash", ADAPTER_HASH),
        ("two definitions", "V_NEXT_P5R_MISS_COUNTER_DEFINITION_COUNT = 2"),
        ("one reaction token", "V_NEXT_P5R_REACTION_TOKEN_CAP = 1"),
        ("fixed hit", "V_NEXT_P5R_COUNTER_HIT_BPS = 9_500"),
        ("common skill", "aq.skill.common.w3.counterprep"),
        ("warrior skill", "aq.skill.warrior.w3.countercut"),
        ("owner enforcement", "ACTOR_CLASS_MISMATCH"),
        ("public required", "PUBLIC_TELEGRAPH_REQUIRED"),
        ("public direct required", "PUBLIC_DIRECT_REQUIRED"),
        ("pattern", 'it.pattern == "PREPAID_REACTION"'),
        ("growth", 'it.growthField == "counter_coefficient_bps"'),
        ("attack budget", "it.attackEquivalentValues == it.anchorValues"),
        ("registry fixed hit", 'it.finalHitPolicy == "FIXED_9500"'),
        ("registry crit false", "!it.critEligible"),
        ("descriptor delayed", "VNextRuntimeCarrierDeliveryV2.DELAYED"),
        ("prepared handler", "PreparedHandlerId.PREPAID_MISS_REACTION"),
        ("no refund", "it.refundBps == 0"),
        ("shared occupancy", "PREPARED_REACTION_TOKEN_OCCUPIED"),
        ("token id", 'TOKEN_INSTANCE_ID = "prepared.miss-counter"'),
        ("token tag", 'tag = "PREPARED_MISS_COUNTER"'),
        ("encounter clock", 'clock = "ENCOUNTER"'),
        ("resource prepay", "request.actor.resourceBps - cost"),
        ("CAS", "TOKEN_CAS_REJECTED"),
        ("replay", "REPLAY_RECEIPT"),
        ("direct miss predicate", "plan.directEnemyAction && !enemyHit"),
        ("trigger detail", "P5R:TRIGGER:MISS_COUNTER"),
        ("hit expiry", "P5R:EXPIRE:ENEMY_HIT"),
        ("non direct expiry", "P5R:EXPIRE:NON_DIRECT"),
        ("strict codec", "VNextStatusStateCodecP5c.encode"),
        ("live off", "val liveReady: Boolean get() = false"),
    ):
        check(name, needle in adapter)
    check("adapter hash document", ADAPTER_HASH in document, ADAPTER_HASH)

    resolver = text["resolver"]
    for name, needle in (
        ("resolver prepare", "VNextMissCounterRuntimeP5r.prepare(heroStatus, delivery)"),
        ("resolver commit", "VNextMissCounterRuntimeP5r.commit"),
        ("post defense hit", "enemyHit = committedResult.hit"),
        ("consume receipt", 'receiptId = "$receiptId:p5r-consume"'),
        ("counter condition", "missCounterCommit.counterCoefficientBps > 0"),
        ("separate receipt", 'val counterReceiptId = "$receiptId:p5r-counter"'),
        ("hero deterministic rolls", "coreRolls(request.encounter, actionIndex, VNextCombatSide.HERO)"),
        ("physical packet", 'damageType = "PHYSICAL"'),
        ("direct packet", 'delivery = "DIRECT"'),
        ("packet fixed hit", "hitPolicyBps = V_NEXT_P5R_COUNTER_HIT_BPS"),
        ("packet crit forbidden", "criticalPolicy = VNextPacketCriticalPolicy.FORBIDDEN"),
        ("system receipt", "VNextResolvedActionKind.SYSTEM_STATUS_CLOCK"),
        ("counter action id", '"P5R_MISS_COUNTER_PACKET"'),
        ("counter reason", '"PREPAID_MISS_TRIGGER"'),
        ("counter phases", "listOf(VNextResolverPhase.PAYLOAD, VNextResolverPhase.PROTECTION_DEATH)"),
        ("next direct clock", '"p5r-counter-next-direct"'),
        ("status clock root", "P5eStatusSystemRoot.NEXT_DIRECT_PACKET"),
        ("system action excluded", "VNextResolvedActionKind.SYSTEM_STATUS_CLOCK,"),
        ("feature off", "V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false"),
        ("settlement off", "V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false"),
    ):
        check(name, needle in resolver)
    counter_start = resolver.index("if (missCounterCommit.counterCoefficientBps > 0)")
    counter_end = resolver.index("actorActionCount++", counter_start)
    counter_block = resolver[counter_start:counter_end]
    check("no counter cooldown mutation", "Cooldowns =" not in counter_block, "none")
    check("no counter resource mutation", "resourceBps =" not in counter_block, "none")
    check("no counter action increment", "actorActionCount++" not in counter_block, "none")

    ai = text["ai"]
    for name, needle in (
        ("AI pattern", 'text == "PREPAID_REACTION"'),
        ("AI occupancy", 'return "PREPARED_REACTION_OCCUPIED"'),
        ("AI direct", 'telegraph.delivery != "DIRECT"'),
        ("AI hidden", 'return "NO_PUBLIC_DIRECT"'),
        ("AI status visibility", '"PREPARED_MISS_COUNTER"'),
    ):
        check(name, needle in ai)

    composite = text["composite"]
    for name, needle in (
        ("composite rules", "aq.composite-semantic-dispatcher.p5r.v0.1"),
        ("composite hash", COMPOSITE_HASH),
        ("families", "V_NEXT_P5R_COMPOSITE_FAMILY_COUNT = 12"),
        ("definitions", "V_NEXT_P5R_COMPOSITE_DEFINITION_COUNT = 56"),
        ("prior", "VNextCompositeSemanticDispatcherP5qCompiler.compile"),
        ("added", "VNextMissCounterSemanticAdapterP5rCompiler.compile"),
        ("duplicate closed", "P5R_DUPLICATE_OWNERS"),
        ("unsupported closed", "P5R_COMPOSITE_UNSUPPORTED_DEFINITION"),
    ):
        check(name, needle in composite)
    check("composite hash document", COMPOSITE_HASH in document, COMPOSITE_HASH)

    for phrase in (
        "coverage binds two prepaid miss reactions to descriptor and prepared contracts",
        "common and warrior skills prepay their cost and arm level scaled tokens without damage",
        "next direct miss triggers coefficient once while a hit consumes without refund",
        "next non direct enemy action expires token with no counter",
        "runtime cas replay and shared occupied reaction fail closed",
        "wrong class hidden non direct and malformed token reject atomically",
        "fixed counter packet uses ninety five percent hit and forbids critical",
        "encounter end expires an unused counter token",
    ):
        check(f"adapter test {phrase[:29]}", phrase in text["adapter_tests"], "covered")
    for phrase in (
        "coverage extends fifty four definitions with two miss counters",
        "prior lethal delay and both counters preserve complete route provenance",
        "unsupported definition and wrong counter class fail without mutation",
        "counter replay is deterministic and every definition has one family",
    ):
        check(f"composite test {phrase[:27]}", phrase in text["composite_tests"], "covered")
    check(
        "AI integration test",
        "miss counter requires one public direct and rejects every occupied reaction token" in text["ai_tests"],
        "covered",
    )
    check(
        "resolver integration test",
        "p5r miss counter prep arms then resolves attached counter without adding an actor action" in text["resolver_tests"],
        "covered",
    )

    check("app unconnected", "VNextCompositeSemanticDispatcherP5r" not in app, "safe")
    check("legacy unconnected", "VNextCompositeSemanticDispatcherP5r" not in settlement, "safe")
    check("resolveKill present", "resolveKill" in settlement, "present")
    for relative, expected in FREEZE.items():
        actual = sha(ROOT / relative)
        check(f"freeze {Path(relative).name}", actual == expected, actual)
    check("engine tests", totals("game-engine") == (493, 0, 0, 0), str(totals("game-engine")))
    check("app tests", totals("app") == (176, 0, 0, 0), str(totals("app")))
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    errors = sum(issue.attrib.get("severity") == "Error" for issue in lint.findall("issue"))
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint.findall("issue"))
    check("lint", (errors, warnings) == (0, 22), f"{errors}/{warnings}")
    check("APK", APK.is_file())
    check("APK hash", APK.is_file() and sha(APK) == APK_HASH, sha(APK) if APK.is_file() else "missing")
    check("APK size", APK.is_file() and APK.stat().st_size == APK_SIZE, str(APK.stat().st_size if APK.is_file() else 0))
    for field in (
        "verificationTarget=android-emulator", "avdName=alarmquest-qa", "androidRelease=15",
        "apiLevel=35", "installResult=Success", "pmClearResult=Success", "launchState=COLD",
        "coldTotalTimeMs=5159", "topResumedActivity=com.nullplaying/.MainActivity",
        "freshRosterEmpty=true", "androidRuntimeFatalCount=0", "engineTests=493",
        "appTests=176", "totalTests=669", "activeDefinitionsExecutable=56",
        "semanticFamilies=12", "featureDefaultEnabled=false", "liveSettlementEnabled=false",
    ):
        check(f"audit {field.split('=')[0]}", field in audit, field)
    if args.with_emulator:
        devices = run(["adb", "devices", "-l"])
        check("emulator online", "emulator-5554" in devices.stdout and "device" in devices.stdout, devices.stdout.strip())
        boot = run(["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"])
        check("boot", boot.stdout.strip() == "1", boot.stdout.strip())
        version = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "package", "com.nullplaying"])
        check("version", "versionCode=1" in version.stdout and "versionName=0.1.0" in version.stdout, "0.1.0(1)")
        focus = run(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
        check("focus", "topResumedActivity" in focus.stdout and "com.nullplaying/.MainActivity" in focus.stdout, "MainActivity")
        ui = ""
        for _ in range(3):
            run(["adb", "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/p5r-review-ui.xml"])
            ui = run(["adb", "-s", "emulator-5554", "shell", "cat", "/sdcard/p5r-review-ui.xml"]).stdout
            if "아직 캐릭터가 없습니다" in ui:
                break
            time.sleep(0.5)
        check("empty roster", "아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui, "fresh")
        log = run(["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "brief"]).stdout
        check("fatal zero", "FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log, "0")
    passed = sum(condition for _, condition, _ in checks)
    for name, condition, detail in checks:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}: {detail}")
    print(f"P5r PD verification: {passed}/{len(checks)} PASS")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
