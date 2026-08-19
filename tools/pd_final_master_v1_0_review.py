#!/usr/bin/env python3
"""Final PD audit for the AlarmQuest combat/skill vNext planning chain.

The default mode checks the frozen chain, documents and current production
boundary without mutation. --pd also executes all eleven child PD audits.
--with-gradle additionally runs the complete game-engine test suite.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_PD_FINAL_MASTER_v1.0.md"
RULES_VERSION = "aq.pd-final-master.v1.0"


@dataclass(frozen=True)
class Contract:
    stage: str
    script: str
    document: str
    expected_hash: str


CONTRACTS = (
    Contract("v0.1", "integrated_combat_simulation_v0_1_review.py", "PRODUCT_MEETING_INTEGRATED_COMBAT_SIMULATION_v0.1.md", "602eea0bb810554a3dbfcf6b13698e2210e16055bbd3b80686794861e9b98ca6"),
    Contract("registry240", "skill_registry_240_v0_2_review.py", "PRODUCT_MEETING_SKILL_REGISTRY_240_v0.2.md", "cb504ee910023ae9624e200b0c6a3ce2651552df6290a871707b26d52328d169"),
    Contract("v0.3", "skill_effect_resolver_v0_3_review.py", "PRODUCT_MEETING_SKILL_EFFECT_RESOLVER_v0.3.md", "0ec0a391c56815960e147fa114d58f21fbd88ebcf94cde715c4fb86d52e59914"),
    Contract("v0.4", "skill_prepared_reaction_v0_4_review.py", "PRODUCT_MEETING_SKILL_PREPARED_REACTION_HANDLERS_v0.4.md", "a9614831496e229ae82e50c0f7fb7bd44443bc9f7327dfa262341c0e1a975466"),
    Contract("v0.5", "skill_status_consumption_v0_5_review.py", "PRODUCT_MEETING_SKILL_STATUS_CONSUMPTION_HANDLERS_v0.5.md", "b3281e14cd88b6ada0aab97d2196229cfe8034fc7c16727a0ee2bc74aaecb48e"),
    Contract("v0.6", "skill_status_lifecycle_v0_6_review.py", "PRODUCT_MEETING_SKILL_STATUS_LIFECYCLE_v0.6.md", "68fd105264c4bf6e2c401abe5a91a81e5d477cc012168080d41dca6aa4263f6e"),
    Contract("v0.7", "skill_cost_transfer_dispel_v0_7_review.py", "PRODUCT_MEETING_SKILL_COST_TRANSFER_DISPEL_HANDLERS_v0.7.md", "8f19f0550577210d86b9715af3bac8d81b26ab52b6cd5fcb06ea1b7523f693a7"),
    Contract("v0.8", "skill_protection_death_v0_8_review.py", "PRODUCT_MEETING_SKILL_PROTECTION_DEATH_PIPELINE_v0.8.md", "034d54400987cc1e5f4e36b53b3db8c30f94c78a3568f51f0ab432716b830e8b"),
    Contract("registry242", "skill_registry_242_v0_3_review.py", "PRODUCT_MEETING_SKILL_REGISTRY_242_v0.3.md", "cda98479494d5d2b3d1279e695c26f923ce46249c8835e3e76996fccd67fbdca"),
    Contract("v0.9", "auto_battle_ai_loadout_v0_9_review.py", "PRODUCT_MEETING_AUTO_BATTLE_AI_LOADOUT_v0.9.md", "ff5ce814b003cdc17f88182e04f994e25d0aa4c04adf2d1f9a9169a4e6d21806"),
    Contract("v1.0", "combat_skill_master_v1_0_review.py", "PRODUCT_MEETING_COMBAT_SKILL_MASTER_v1.0.md", "c7d7506e1bd383b9d6a6b8904401733b25e76f7af759a6ac9f9fda2cae29a66b"),
)

SUPPORT_HASHES = {
    "registry242StatusStructure": "653ccc9795bb0cff9b37283a94f43069a29a86a2f4ef7253fb36dbe96462c300",
    "autoAiInterop": "9ba412a95bb55b79115ad1aceb9868eacd2c92ce1c303f46637c46e84a3d9f2a",
    "masterMatrix": "7be89f2126c2e2050f38b782c6769bf9dcbf26f3e6e234d52bba3819efe93c4c",
}


def chain_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "contracts": [contract.__dict__ for contract in CONTRACTS],
        "supportHashes": SUPPORT_HASHES,
        "decision": {"testOnly": "GO", "production": "CONDITIONAL", "live": "NO_GO"},
        "liveCharacterResetExecuted": False,
    }


def chain_hash() -> str:
    raw = json.dumps(chain_payload(), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def static_checks() -> list[tuple[str, bool, str]]:
    doc = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    missing_files = [
        path for contract in CONTRACTS
        for path in (f"tools/{contract.script}", contract.document)
        if not (ROOT / path).is_file()
    ]
    missing_doc_hashes = [contract.stage for contract in CONTRACTS if contract.expected_hash not in doc]
    missing_support = [name for name, value in SUPPORT_HASHES.items() if value not in doc]
    return [
        ("all eleven contract files exist", not missing_files and len(CONTRACTS) == 11, str(missing_files)),
        ("final document binds eleven runtime hashes", not missing_doc_hashes, str(missing_doc_hashes)),
        ("final document binds supporting hashes", not missing_support, str(missing_support)),
        ("final decisions are explicit", all(token in doc for token in ("test-only GO", "production 조건부 승인", "라이브 NO-GO")), "three gates"),
        ("automatic player agency boundary is retained", all(token in doc for token in ("Active `0..5` + Passive `0..3`", "CAUTIOUS/BALANCED/BOLD", "전투 중 수동")), "precombat only"),
        ("equipment skill creation remains forbidden", all(token in doc for token in ("grantedSkillDefinitionId", "extraActiveSlot", "equipmentSkillLevel")), "malicious fields listed"),
        ("reset saga is planned but not executed", all(token in doc for token in ("NOT_STARTED → DB_PURGED → READY", "지금 삭제를 실행하지 않는다", "backup v1")), "cutover gated"),
        ("final document binds chain rules", RULES_VERSION in doc, RULES_VERSION),
    ]


def source_checks() -> list[tuple[str, bool, str]]:
    settlement = read("game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt")
    loader = read("app/src/main/java/com/alarmquest/data/content/AssetContentLoader.kt")
    application = read("app/src/main/java/com/alarmquest/AlarmQuestApplication.kt")
    database = read("app/src/main/java/com/alarmquest/data/room/AlarmQuestDatabase.kt")
    entities = read("app/src/main/java/com/alarmquest/data/room/Entities.kt")
    repository = read("app/src/main/java/com/alarmquest/data/repository/GameRepository.kt")
    main_roots = [ROOT / "game-engine/src/main", ROOT / "app/src/main"]
    main_text = "\n".join(
        path.read_text(encoding="utf-8", errors="ignore")
        for root in main_roots for path in root.rglob("*")
        if path.is_file() and path.suffix in {".kt", ".json", ".xml"}
    )
    return [
        ("existing outcome gate is real", "battleOutcome == null || battleOutcome == BattleOutcome.VICTORY" in settlement and "TASK_KILL -> if (killSucceeded) resolveKill" in settlement, "null compatibility remains"),
        ("existing asset content path is real", all(token in loader for token in ('read("content/regions.json")', 'read("content/enemies.json")', 'read("content/quests.json")')) and "SettlementEngine(contentCatalog = content)" in application, "loader to engine"),
        ("existing enemy and loot pool are consumed", "contentCatalog?.enemies?.filter" in settlement and "?.lootPool" in settlement, "partial v1 wiring"),
        ("new vnext production resolver is absent", "aq.skill-registry.v0.3" not in main_text and "VNextSkillRegistry242" not in main_text and "aq.auto-battle-ai-loadout.v0.9" not in main_text, "test-only boundary"),
        ("Room remains legacy v6", re.search(r"version\s*=\s*6", database) is not None and "app_metadata" not in database, "version=6 metadata absent"),
        ("display level remains Int", re.search(r"data class CharacterEntity\([\s\S]*?val level: Int", entities) is not None, "Long migration absent"),
        ("backup v1 is still accepted", "val formatVersion: Int = 1" in repository and "require(backup.formatVersion == 1)" in repository, "v2/reset gate absent"),
        ("existing settle has an atomic Room base", "database.withTransaction" in repository and "val result = engine.settle(state, now)" in repository and "save(state)" in repository, "base retained"),
    ]


def run_child(contract: Contract) -> tuple[bool, str]:
    command = [sys.executable, str(ROOT / "tools" / contract.script), "--pd"]
    env = os.environ.copy()
    env["PYTHONPATH"] = str(ROOT / "tools")
    completed = subprocess.run(command, cwd=ROOT, env=env, text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               timeout=900)
    output = completed.stdout
    hashes = re.findall(r"canonical_sha256=([0-9a-f]{64})", output)
    passed = completed.returncode == 0 and "PD:" in output and bool(hashes) and hashes[-1] == contract.expected_hash
    detail = f"exit={completed.returncode} hash={hashes[-1] if hashes else 'missing'}"
    if not passed:
        detail += " tail=" + " | ".join(output.splitlines()[-6:])
    return passed, detail


def run_gradle() -> tuple[bool, str]:
    completed = subprocess.run(
        [str(ROOT / "gradlew"), ":game-engine:test", "--no-parallel"],
        cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        timeout=900,
    )
    passed = completed.returncode == 0 and "BUILD SUCCESSFUL" in completed.stdout
    return passed, f"exit={completed.returncode} {'BUILD SUCCESSFUL' if passed else 'BUILD FAILED'}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--with-gradle", action="store_true")
    args = parser.parse_args()
    checks = static_checks() + source_checks()
    if args.pd:
        checks.extend(
            (f"{contract.stage} child PD", *run_child(contract))
            for contract in CONTRACTS
        )
    if args.with_gradle:
        checks.append(("full game-engine regression", *run_gradle()))
    print(f"PD_FINAL: {sum(ok for _, ok, _ in checks)}/{len(checks)} PASS")
    for name, ok, detail in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"chain_sha256={chain_hash()}")
    return 0 if all(ok for _, ok, _ in checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
