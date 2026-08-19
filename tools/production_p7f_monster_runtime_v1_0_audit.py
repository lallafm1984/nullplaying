#!/usr/bin/env python3
"""Fail-closed source, report, APK, and optional device audit for P7f."""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMonsterRuntimeP7f.kt"
RUNTIME_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextMonsterRuntimeP7fTest.kt"
REGISTRY = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextMonsterRegistry.kt"
RESOLVER = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt"
RESOLVER_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt"
GATEWAY = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextProductionBattleGateway.kt"
SEMANTIC_READINESS = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextSemanticRuntimeReadiness.kt"
SEMANTIC_READINESS_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextSemanticRuntimeReadinessTest.kt"
P6K = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextShieldBreakStatusResistRuntimeP6k.kt"
P7B3 = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusModifierRuntimeP7b3.kt"
P5V = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5v.kt"
PERSISTENCE_TEST = ROOT / "app/src/test/java/com/alarmquest/data/repository/VNextPersistenceRepositoryTest.kt"
APPLICATION = ROOT / "app/src/main/java/com/alarmquest/AlarmQuestApplication.kt"
REPOSITORY = ROOT / "app/src/main/java/com/alarmquest/data/repository/GameRepository.kt"
PENDING_BATTLE = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPendingBattle.kt"
PENDING_BATTLE_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextPendingBattleTest.kt"
ACTION_NAMES = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCombatActionNameCatalog.kt"
SETTLEMENT_ENGINE = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt"
SETTLEMENT_TEST = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/SettlementEngineTest.kt"
GAME_MODELS = ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/model/GameModels.kt"
WORKERS = ROOT / "app/src/main/java/com/alarmquest/background/SettlementWorkers.kt"
WIDGET = ROOT / "app/src/main/java/com/alarmquest/widget/AdventureWidgetProvider.kt"
MIGRATION_TEST = ROOT / "app/src/test/java/com/alarmquest/data/room/AlarmQuestDatabaseMigrationTest.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
APP_BUILD = ROOT / "app/build.gradle.kts"
BACKUP_RULES = ROOT / "app/src/main/res/xml/backup_rules.xml"
DATA_EXTRACTION_RULES = ROOT / "app/src/main/res/xml/data_extraction_rules.xml"
TURN_PRESENTATION = ROOT / "app/src/main/java/com/alarmquest/ui/TurnBattlePresentation.kt"
TURN_PRESENTATION_TEST = ROOT / "app/src/test/java/com/alarmquest/ui/TurnBattlePresentationTest.kt"
RESET_AUDIT = ROOT / "artifacts/audit/production-p5d-device-reset-verification-v0.1.txt"
DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_P7F_MONSTER_RUNTIME_v1.0.md"
CUTOVER_DOC = ROOT / "PRODUCT_MEETING_PRODUCTION_CUTOVER_AUDIT_v1.0.md"
RELEASE_RUNBOOK = ROOT / "PRODUCT_MEETING_RELEASE_RUNBOOK_v1.0.md"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
LINT = ROOT / "app/build/reports/lint-results-debug.xml"

EXPECTED = {
    "V_NEXT_P7F_MONSTER_RUNTIME_RULES_VERSION": "aq.monster-ability-runtime.p7f.v1.0",
    "V_NEXT_P7F_MONSTER_RUNTIME_CONTENT_HASH":
        "306211f7e52ba79d02c75a47062b6e76def7401d12a7989aaa5bb8f81a69bcf5",
    "V_NEXT_P6K_SHIELD_BREAK_RESIST_CONTENT_HASH":
        "fb653ca02de77d3c7f064bb448aa2981a5a467de56ed72768c5b9b3851c7e20a",
    "V_NEXT_P7B3_STATUS_MODIFIER_CONTENT_HASH":
        "589d77d63f8e529cff990baef8ead81a314c6b7eaae01671330e985875071e1b",
    "V_NEXT_P5V_COMPOSITE_CONTENT_HASH":
        "0916a8468ea8ff51295c05d7b5f89da9de13abd0a963ed74bc630a2eaf0dcd34",
    "V_NEXT_BATTLE_RESOLVER_RULES_VERSION":
        "aq.battle-resolver-transaction.monster-runtime-p7f.v0.32",
    "V_NEXT_BATTLE_AUDIT_RULES_VERSION": "aq.battle-audit.v1.2",
}
EXPECTED_PREFIXES = {
    "FEROCIOUS", "IRONHIDE", "QUICK", "PRECISE", "ARCANE", "VENOMOUS",
    "FROSTBOUND", "EMBER", "CURSED", "REGENERATING", "HUNGRY", "ANCIENT",
}
EXPECTED_APK_SHA256 = "33ed6e3ca13bf7f3fde883eed9a1de6664bed4af0295383b1a0b5b38c88cdcd3"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def extract_string(source: str, name: str) -> str:
    match = re.search(rf"\b{re.escape(name)}(?:\s*:\s*String)?\s*=\s*\"([^\"]+)\"", source)
    if match is None:
        raise ValueError(f"missing constant {name}")
    return match.group(1)


def report_totals(directory: Path) -> tuple[int, int, int, int]:
    totals = [0, 0, 0, 0]
    for report in directory.glob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        for index, key in enumerate(("tests", "failures", "errors", "skipped")):
            totals[index] += int(root.attrib.get(key, "0"))
    return tuple(totals)


def command(*args: str) -> tuple[int, str]:
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)
    return result.returncode, (result.stdout + result.stderr).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-emulator", action="store_true")
    parser.add_argument("--require-physical", action="store_true")
    args = parser.parse_args()
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, passed: bool, detail: object) -> None:
        checks.append((name, passed, str(detail)))
        print(f"[{'PASS' if passed else 'FAIL'}] {name}: {detail}")

    required = [
        RUNTIME, RUNTIME_TEST, REGISTRY, RESOLVER, RESOLVER_TEST, GATEWAY,
        SEMANTIC_READINESS, SEMANTIC_READINESS_TEST, P6K, P7B3, P5V,
        APPLICATION, REPOSITORY, PENDING_BATTLE, PENDING_BATTLE_TEST, ACTION_NAMES,
        SETTLEMENT_ENGINE, SETTLEMENT_TEST, GAME_MODELS, WORKERS, WIDGET,
        PERSISTENCE_TEST, MIGRATION_TEST, MANIFEST, APP_BUILD, BACKUP_RULES,
        DATA_EXTRACTION_RULES, TURN_PRESENTATION, TURN_PRESENTATION_TEST,
        RESET_AUDIT, DOC, CUTOVER_DOC, RELEASE_RUNBOOK, APK, LINT,
        ROOT / "game-engine/build/test-results/test",
        ROOT / "app/build/test-results/testDebugUnitTest",
        ROOT / "app/build/test-results/testReleaseUnitTest",
    ]
    for path in required:
        check(f"file:{path.relative_to(ROOT)}", path.exists(), path.exists())
    if not all(path.exists() for path in required):
        print("RESULT=FAIL")
        return 1

    runtime = RUNTIME.read_text(encoding="utf-8")
    runtime_test = RUNTIME_TEST.read_text(encoding="utf-8")
    registry = REGISTRY.read_text(encoding="utf-8")
    resolver = RESOLVER.read_text(encoding="utf-8")
    resolver_test = RESOLVER_TEST.read_text(encoding="utf-8")
    gateway = GATEWAY.read_text(encoding="utf-8")
    semantic_readiness = SEMANTIC_READINESS.read_text(encoding="utf-8")
    semantic_readiness_test = SEMANTIC_READINESS_TEST.read_text(encoding="utf-8")
    application = APPLICATION.read_text(encoding="utf-8")
    repository = REPOSITORY.read_text(encoding="utf-8")
    pending_battle = PENDING_BATTLE.read_text(encoding="utf-8")
    pending_battle_test = PENDING_BATTLE_TEST.read_text(encoding="utf-8")
    action_names = ACTION_NAMES.read_text(encoding="utf-8")
    settlement_engine = SETTLEMENT_ENGINE.read_text(encoding="utf-8")
    settlement_test = SETTLEMENT_TEST.read_text(encoding="utf-8")
    game_models = GAME_MODELS.read_text(encoding="utf-8")
    workers = WORKERS.read_text(encoding="utf-8")
    widget = WIDGET.read_text(encoding="utf-8")
    persistence_test = PERSISTENCE_TEST.read_text(encoding="utf-8")
    migration_test = MIGRATION_TEST.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")
    app_build = APP_BUILD.read_text(encoding="utf-8")
    backup_rules = BACKUP_RULES.read_text(encoding="utf-8")
    data_extraction_rules = DATA_EXTRACTION_RULES.read_text(encoding="utf-8")
    turn_presentation = TURN_PRESENTATION.read_text(encoding="utf-8")
    turn_presentation_test = TURN_PRESENTATION_TEST.read_text(encoding="utf-8")
    release_runbook = RELEASE_RUNBOOK.read_text(encoding="utf-8")
    reset_audit = RESET_AUDIT.read_text(encoding="utf-8")

    sources = {
        RUNTIME: runtime,
        P6K: P6K.read_text(encoding="utf-8"),
        P7B3: P7B3.read_text(encoding="utf-8"),
        P5V: P5V.read_text(encoding="utf-8"),
        RESOLVER: resolver,
        PENDING_BATTLE: pending_battle,
    }
    constant_owner = {
        "V_NEXT_P7F_MONSTER_RUNTIME_RULES_VERSION": RUNTIME,
        "V_NEXT_P7F_MONSTER_RUNTIME_CONTENT_HASH": RUNTIME,
        "V_NEXT_P6K_SHIELD_BREAK_RESIST_CONTENT_HASH": P6K,
        "V_NEXT_P7B3_STATUS_MODIFIER_CONTENT_HASH": P7B3,
        "V_NEXT_P5V_COMPOSITE_CONTENT_HASH": P5V,
        "V_NEXT_BATTLE_RESOLVER_RULES_VERSION": RESOLVER,
        "V_NEXT_BATTLE_AUDIT_RULES_VERSION": PENDING_BATTLE,
    }
    for name, expected in EXPECTED.items():
        observed = extract_string(sources[constant_owner[name]], name)
        check(f"constant:{name}", observed == expected, observed)

    contracts_block = runtime.split("val contracts: List<P7fMonsterAbilityContract> = listOf(", 1)[1]
    contracts_block = contracts_block.split("\n    val byId:", 1)[0]
    ability_ids = re.findall(r"\bability\(\"([^\"]+)\"", contracts_block)
    registry_block = registry.split("val baseMonsters: List<BaseMonsterDefinition> = listOf(", 1)[1]
    registry_block = registry_block.split("\n    val prefixes:", 1)[0]
    referenced_ids = set(re.findall(r"\babilities\s*=\s*listOf\(([^)]*)\)", registry_block))
    referenced_ids = {
        ability_id
        for group in referenced_ids
        for ability_id in re.findall(r'"([^"]+)"', group)
    }
    prefix_block = registry.split("val prefixes: List<MonsterPrefixDefinition> = listOf(", 1)[1]
    prefix_block = prefix_block.split("\n    val variants:", 1)[0]
    prefix_ids = set(re.findall(r'\bprefix\("([^"]+)"', prefix_block))
    check("p7f:ability-count", len(ability_ids) == 55 and len(set(ability_ids)) == 55, len(ability_ids))
    check("p7f:registry-coverage", set(ability_ids) == referenced_ids, f"runtime={len(set(ability_ids))},registry={len(referenced_ids)}")
    check("p7f:prefix-count-and-ids", prefix_ids == EXPECTED_PREFIXES, ",".join(sorted(prefix_ids)))

    runtime_anchors = {
        "typed role": "P7fMonsterAbilityRole",
        "typed utility": "P7fMonsterUtility",
        "typed status": "P7fMonsterStatusSpec",
        "fixed accuracy": 'startsWith("FIXED_")',
        "execution hp gate": "targetHpAtOrBelowBps",
        "prefix status package": "effectiveStatusSpecs",
        "prefix root regen": "monsterRootIndex % 3 == 0",
        "ability root regen": 'sourceDefinitionId.startsWith("aq.skill.monster.")',
        "ancient boss cap": 'encounter.prefixId == "ANCIENT"',
        "receipt idempotency": "P7F:UTILITY:REPLAY",
    }
    for name, anchor in runtime_anchors.items():
        check(f"runtime:{name}", anchor in runtime, anchor)

    resolver_anchors = {
        "content identity": "V_NEXT_P7F_MONSTER_RUNTIME_CONTENT_HASH",
        "candidate": "VNextMonsterRuntimeP7f.candidate",
        "fixed hit": "VNextMonsterRuntimeP7f.hitPolicyBps",
        "utility": "VNextMonsterRuntimeP7f.commitUtility",
        "prefix root": "VNextMonsterRuntimeP7f.commitPrefixRoot",
        "typed statuses": "effectiveStatusSpecs",
        "receipt evidence": "P7F:ABILITY=",
    }
    for name, anchor in resolver_anchors.items():
        check(f"resolver:{name}", anchor in resolver, anchor)
    check(
        "cutover:application-wiring",
        "VNextRuntimeFeatures.productionCutover(" in application and
        "liveCombatEnabled = BuildConfig.AQ_VNEXT_COMBAT_ENABLED" in application,
        "shipping application uses named cutover",
    )
    check(
        "cutover:atomic-feature-contract",
        all(anchor in repository for anchor in (
            "fun productionCutover(liveCombatEnabled: Boolean = true): VNextRuntimeFeatures",
            "skillBuildEnabled = true",
            "combatSettlementEnabled = liveCombatEnabled",
            "characterDataEpochGateEnabled = true",
            "combatKillSwitchActive = !liveCombatEnabled",
            "val productionCutoverEnabled: Boolean",
        )),
        "build persistence + authenticated combat",
    )
    check(
        "cutover:gateway-readiness-gate",
        all(anchor in gateway for anchor in (
            "VNextSkillCombatReadinessAudit.audit(registry)",
            "readiness.allRegisteredSkillsExecutable",
            "VNextProductionBattleRejectReason.RUNTIME_NOT_READY",
            "VNextBattleResolverFeatures(enabled = true)",
        )),
        "Registry242 readiness before resolver",
    )
    check(
        "cutover:compatibility-admission-ready",
        all(anchor in semantic_readiness for anchor in (
            "V_NEXT_RUNTIME_READY_DEFINITION_COUNT: Int = 242",
            "VNextSkillCombatReadinessAudit.audit(registry)",
            "reportHash = authoritative.contentHash",
        )) and all(anchor in semantic_readiness_test for anchor in (
            "assertEquals(242, report.readyDefinitions)",
            "SemanticRuntimeAdmissionStatus.READY",
            "assertTrue(report.liveReady)",
        )),
        "legacy P4b gate delegates to 242/242 authority",
    )
    check(
        "cutover:character-epoch-gate",
        all(anchor in repository for anchor in (
            "suspend fun characterDataEpochReady()",
            "metadata.requiredCharacterDataEpoch == VNEXT_REQUIRED_CHARACTER_DATA_EPOCH",
            'metadata.characterResetState == "READY"',
            "metadata.creationRulesVersion == AQ_CHARACTER_CREATION_RULES_VERSION",
            "metadata.backupFormatVersion == VNEXT_REQUIRED_BACKUP_FORMAT_VERSION",
        )) and "characterDataEpochReady()" in workers and "characterDataEpochReady()" in widget and
        "shipping entrypoints fail closed until exact character epoch metadata is ready" in persistence_test,
        "Repository + worker + widget fail closed before epoch READY",
    )
    check(
        "cutover:battle-audit-receipt",
        all(anchor in pending_battle for anchor in (
            "data class VNextBattleAuditReceipt(",
            "data class VNextRootAuditReceipt(",
            "VNextBattleAuditReceiptCodec",
            "decisionReason = receipt.decisionReason",
            "excludedReasons = receipt.excludedReasons.toSortedMap()",
            "finalHeroHpBps = (",
            "finalHeroResourceBps = transaction.finalHero.resourceBps",
            "actorAfter = receipt.actorAfter.toAuditFrame()",
            "finalMonster = transaction.finalMonster.toAuditFrame()",
            "V_NEXT_BATTLE_AUDIT_V11_RULES_VERSION -> v11Hash(value)",
            "V_NEXT_BATTLE_AUDIT_LEGACY_RULES_VERSION -> legacyHash(value)",
            "fun decode(payload: String): VNextBattleAuditReceipt?",
            "matchesSettlement",
        )) and all(anchor in repository for anchor in (
            'VNEXT_RECEIPT_BATTLE_AUDIT = "BATTLE_AUDIT"',
            "audit = VNextBattleAuditReceiptCodec.create(completed.transaction)",
            "Receipt collision during battle audit persistence",
        )) and all(anchor in persistence_test for anchor in (
            'getVNextReceiptsByType(characterId, "BATTLE_AUDIT")',
            "pending battle survives close reopen and consumes its frozen receipt once",
            "actual repository settle applies one authenticated kill without legacy xp duplication",
            "vNextCombatTelemetrySnapshot()",
        )) and all(anchor in pending_battle_test for anchor in (
            "legacy v1 audit remains verifiable after v11 telemetry fields are added",
            '"aq.battle-audit.v1.0"',
        )) and all(anchor in repository for anchor in (
            "suspend fun vNextCombatTelemetrySnapshot(",
            "invalidRateBps",
            "telemetryPercentiles",
        )),
        "v1.2 frame audit, v1.1/v1.0 verification, atomic persistence, and local percentiles",
    )
    check(
        "cutover:battle-audit-ui-reasons",
        all(anchor in turn_presentation for anchor in (
            "fun TurnBattlePresentation.withVNextAudit(",
            "VNextRootAuditReceipt.auditActionPresentation(",
            "val root = visibleRoots[auditIndex]",
            "damage = hpDamage",
            "recentActions = visibleRoots",
            "withAuditFrames(root)",
            "VNextCombatActionNameCatalog.nameKo(actionKind, actionId)",
            "runtimeDecisionSummary",
            "runtimeExclusionSummary",
            'runtimeReasonLabel(root.decisionReason)',
            'runtimeReasonLabel(code)',
        )) and all(anchor in turn_presentation_test for anchor in (
            "production audit exposes selected and all distinct exclusion codes",
            'assertEquals("root:1", action.key)',
            'assertEquals(90, action.damage)',
            'contains("[SURVIVAL_PRIORITY]")',
            'contains("재사용 대기 [COOLDOWN]")',
            'contains("보존 자원 부족 [RESOURCE_RESERVED]")',
            "production audit owns invalid replay defeat and safe retreat terminal wording",
            "vNext damage skill name action count and hp bars share the same audited roots",
        )) and all(anchor in action_names for anchor in (
            "val monsterAbilityNames: Map<String, String>",
            "monsterAbilityNames.keys == VNextMonsterRuntimeP7f.byId.keys",
            "skillRegistry?.nameKo(actionId)",
        )),
        "audit roots own action identity/order/damage/hp frames/names/reasons and terminal wording",
    )
    check(
        "cutover:vnext-hp-outcome-boundary",
        all(anchor in settlement_engine for anchor in (
            "val finalHeroHp: Int? = null",
            "val finalHeroMaxHp: Int? = null",
            "VNextBattleOutcome.VICTORY -> scaledHp.coerceAtLeast(1)",
            "VNextBattleOutcome.DEFEAT -> 0",
        )) and all(anchor in repository for anchor in (
            "lastCompletedVNextBattle = trustedPending",
            "finalHeroHp = trustedPending.finalHeroHp",
            "finalHeroMaxHp = trustedPending.finalHeroMaxHp",
        )) and "var lastCompletedVNextBattle: VNextPendingBattle? = null" in game_models and
        all(anchor in settlement_test for anchor in (
            "vNext one hp victory cannot floor to zero or enter defeat return",
            "vNext zero hp defeat overrides a legacy victory and enters safe return",
        )),
        "exact 1 HP survives legacy projection; authoritative 0 HP defeat always returns",
    )
    backup_paths = (
        'path="alarmquest.db"',
        'path="datastore/onboarding.preferences_pb"',
        'path="datastore/notification_settings.preferences_pb"',
    )
    import_anchors = (
        "ACTION_OPEN_DOCUMENT",
        "ACTION_GET_CONTENT",
        "ActivityResultContracts.OpenDocument",
        "FileProvider",
    )
    app_kotlin = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ROOT / "app/src/main/java").rglob("*.kt")
    )
    check(
        "cutover:android-backup-allowlist",
        all(anchor in manifest for anchor in (
            'android:allowBackup="true"',
            'android:fullBackupContent="@xml/backup_rules"',
            'android:dataExtractionRules="@xml/data_extraction_rules"',
        )) and backup_rules.count("<include ") == 3 and
        data_extraction_rules.count("<include ") == 6 and
        all(backup_rules.count(path) == 1 for path in backup_paths) and
        all(data_extraction_rules.count(path) == 2 for path in backup_paths) and
        'disableIfNoEncryptionCapabilities="true"' in data_extraction_rules and
        "<exclude " not in backup_rules and "<exclude " not in data_extraction_rules,
        "only Room format-v2 state and two user preference DataStores are eligible",
    )
    check(
        "cutover:explicit-import-disabled",
        not any(anchor in manifest or anchor in app_kotlin for anchor in import_anchors),
        "no file/document import surface in the v1 shipping application",
    )
    check(
        "cutover:combat-build-kill-switch",
        all(anchor in app_build for anchor in (
            'gradleProperty("aqVNextCombatEnabled")',
            'buildConfigField("boolean", "AQ_VNEXT_COMBAT_ENABLED"',
        )) and all(anchor in application for anchor in (
            "VNextRuntimeFeatures.productionCutover(",
            "liveCombatEnabled = BuildConfig.AQ_VNEXT_COMBAT_ENABLED",
        )) and all(anchor in repository for anchor in (
            "fun productionCutover(liveCombatEnabled: Boolean = true)",
            "if (!liveCombatEnabled) state.adventure.retention.activeVNextBattle = null",
            "action.payloadType == SettlementEngine.TASK_KILL && liveCombatEnabled",
            "outcome = VNextBattleOutcome.INVALID_PLAN",
        )) and all(anchor in persistence_test for anchor in (
            "combat kill switch fails closed without rewards while leaving repository data available",
            "VNextRuntimeFeatures.productionCutover(liveCombatEnabled = false)",
        )) and all(anchor in release_runbook for anchor in (
            "-PaqVNextCombatEnabled=true",
            "-PaqVNextCombatEnabled=false",
            "보상 0, 안전 귀환",
            "재활성화",
        )),
        "disabled hotfix keeps data/non-combat paths and rejects kill rewards",
    )

    for anchor in (
        "explicit production ledger covers all fifty five referenced ability ids and pins hash",
        "execution candidate is gated by current public hp and fixed accuracy is executable",
        "support utility is receipt idempotent and regenerating prefix fires every third own root",
        "ability regeneration heals on following own roots and typed prefix status persists canonically",
    ):
        check(f"unit:{anchor[:42]}", anchor in runtime_test, anchor)
    for anchor in (
        "p7f fixed accuracy and support utility execute through production receipts",
        "p7f execution policy is unavailable above threshold and selected at low public hp",
        "p7f regenerating prefix heals on the third monster root with receipt evidence",
        "30 rounds or 60 actions end in safe retreat with zero settlement grant",
    ):
        check(f"integration:{anchor[:42]}", anchor in resolver_test, anchor)
    check(
        "r5:all-loadouts-reopen",
        "one plus one partial full and empty builds survive committed database reopen" in persistence_test,
        "1+1,partial,5+3,0+0",
    )
    check(
        "r8:migration-reset-rehearsal",
        all(anchor in migration_test for anchor in (
            "migration 6 to 11 purges legacy characters",
            "migration 7 to 11 performs the authorized clean character cutover",
            "migration 8 to 11 rejects the last legacy backup generation",
            "migration 10 to 11 preserves current characters",
            "new database callback seeds ready AQ character metadata",
        )),
        "Room schema 6/7/8/10 -> 11 and fresh database",
    )
    check(
        "r8:historical-physical-reset-evidence",
        all(anchor in reset_audit for anchor in (
            "deviceModel=SM_S931N", "pmClearResult=Success", "freshRosterEmpty=true",
            "resumedActivity=com.alarmquest/.MainActivity", "androidRuntimeFatalCount=0",
        )),
        "SM-S931N reset rehearsal baseline",
    )

    engine = report_totals(ROOT / "game-engine/build/test-results/test")
    debug = report_totals(ROOT / "app/build/test-results/testDebugUnitTest")
    release = report_totals(ROOT / "app/build/test-results/testReleaseUnitTest")
    check("reports:engine", engine == (1066, 0, 0, 0), engine)
    check("reports:app-debug", debug == (125, 0, 0, 0), debug)
    check("reports:app-release", release == (125, 0, 0, 0), release)
    lint_root = ET.parse(LINT).getroot()
    lint_errors = sum(issue.attrib.get("severity") == "Error" for issue in lint_root.findall("issue"))
    lint_warnings = sum(issue.attrib.get("severity") == "Warning" for issue in lint_root.findall("issue"))
    check("reports:lint-errors", lint_errors == 0, f"errors={lint_errors},warnings={lint_warnings}")
    check("artifact:apk-sha256", sha256(APK) == EXPECTED_APK_SHA256, sha256(APK))
    check("artifact:apk-size", APK.stat().st_size == 39_466_621, APK.stat().st_size)

    code, devices_output = command("adb", "devices")
    connected = [line.split()[0] for line in devices_output.splitlines()[1:] if line.endswith("\tdevice")]
    physical = [serial for serial in connected if not serial.startswith("emulator-")]
    check("device:adb", code == 0, devices_output.replace("\n", "; "))
    if args.with_emulator:
        emulator = "emulator-5554"
        check("device:emulator-connected", emulator in connected, connected)
        _, package = command("adb", "-s", emulator, "shell", "dumpsys", "package", "com.alarmquest")
        _, focus = command("adb", "-s", emulator, "shell", "dumpsys", "activity", "activities")
        _, crashes = command("adb", "-s", emulator, "logcat", "-d", "-b", "crash")
        check("device:version", "versionName=0.1.0" in package and "versionCode=1" in package, "0.1.0(1)")
        check("device:focus", "topResumedActivity" in focus and "com.alarmquest/.MainActivity" in focus, "com.alarmquest/.MainActivity")
        check("device:crash-buffer", "com.alarmquest" not in crashes, "no AlarmQuest crash")
    if args.require_physical:
        check("device:physical", bool(physical), physical)
    else:
        print(f"[INFO] device:physical: {physical or 'not connected; not required by this audit run'}")

    print("SOURCE_SHA256")
    for path in (RUNTIME, RESOLVER, GATEWAY, SEMANTIC_READINESS, P6K, P7B3, P5V,
                 PENDING_BATTLE, ACTION_NAMES, SETTLEMENT_ENGINE, GAME_MODELS,
                 APPLICATION, REPOSITORY, WORKERS, WIDGET, PERSISTENCE_TEST):
        print(f"{path.relative_to(ROOT)}={sha256(path)}")
    failed = [name for name, passed, _ in checks if not passed]
    print(f"CHECKS={len(checks)} FAILURES={len(failed)}")
    print(f"RESULT={'PASS' if not failed else 'FAIL'}")
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
