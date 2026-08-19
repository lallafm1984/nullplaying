#!/usr/bin/env python3
"""Reference review for AlarmQuest automatic-game player agency v0.1.

This is a design-contract validator. It does not implement the Android UI, persistence,
item economy, or combat engine.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from enum import Enum
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_AUTO_GAME_PLAYER_AGENCY_v0.1.md"


class Boundary(str, Enum):
    ACTION_COMPLETE = "ACTION_COMPLETE"
    INN_COMPLETE = "INN_COMPLETE"
    EXPEDITION_START = "EXPEDITION_START"
    SALE_START = "SALE_START"
    TOWN_TRANSACTION = "TOWN_TRANSACTION"


class ControlMode(str, Enum):
    USER_DIRECTION = "USER_DIRECTION"
    SYSTEM_EXECUTION = "SYSTEM_EXECUTION"
    SYSTEM_WITH_USER_CONFIRMATION = "SYSTEM_WITH_USER_CONFIRMATION"


@dataclass(frozen=True)
class AgencyContract:
    domain: str
    mode: ControlMode
    boundary: Boundary
    mid_combat_input: bool = False
    raw_numeric_edit: bool = False


CONTRACTS = (
    AgencyContract("quest_directive", ControlMode.USER_DIRECTION, Boundary.ACTION_COMPLETE),
    AgencyContract("skill_build", ControlMode.USER_DIRECTION, Boundary.INN_COMPLETE),
    AgencyContract("behavior_preset", ControlMode.USER_DIRECTION, Boundary.INN_COMPLETE),
    AgencyContract("equipment_loadout", ControlMode.USER_DIRECTION, Boundary.EXPEDITION_START),
    AgencyContract("loot_policy", ControlMode.USER_DIRECTION, Boundary.SALE_START),
    AgencyContract("craft_enhance", ControlMode.SYSTEM_WITH_USER_CONFIRMATION, Boundary.TOWN_TRANSACTION),
)


@dataclass(frozen=True)
class Plan:
    behavior: str = "BALANCED"
    build_revision: int = 1
    equipment_revision: int = 1
    quest_revision: int = 1
    loot_revision: int = 1


@dataclass(frozen=True)
class Snapshot:
    plan: Plan
    expedition_receipt_id: str


@dataclass
class PlanLedger:
    committed: Plan
    committed_revision: int = 1
    staged: Plan | None = None
    active: Snapshot | None = None

    def stage(self, candidate: Plan) -> None:
        self.staged = candidate

    def commit_at_inn(self, expected_previous_revision: int) -> bool:
        if self.staged is None or expected_previous_revision != self.committed_revision:
            return False
        self.committed = self.staged
        self.staged = None
        self.committed_revision += 1
        return True

    def start_expedition(self, receipt_id: str) -> Snapshot:
        self.active = Snapshot(self.committed, receipt_id)
        return self.active


@dataclass(frozen=True)
class Item:
    instance_id: str
    power: int
    locked: bool = False
    rare_material: bool = False
    build_sidegrade: bool = False
    enhancement_rank: int = 0
    destroyed: bool = False


@dataclass
class InventoryLedger:
    items: dict[str, Item]
    gold: int = 0
    materials: int = 0
    receipts: set[str] | None = None

    def __post_init__(self) -> None:
        if self.receipts is None:
            self.receipts = set()

    def sell(self, instance_id: str, receipt_id: str, value: int) -> bool:
        if receipt_id in self.receipts:
            return False
        item = self.items[instance_id]
        if item.locked or item.rare_material or item.build_sidegrade:
            return False
        self.receipts.add(receipt_id)
        del self.items[instance_id]
        self.gold += value
        return True

    def enhance(self, instance_id: str, receipt_id: str, cost: int, success: bool) -> bool:
        if receipt_id in self.receipts:
            return False
        item = self.items[instance_id]
        if item.locked or self.materials < cost:
            return False
        self.receipts.add(receipt_id)
        self.materials -= cost
        # Failure protection is a v0.1 product invariant: no destruction or rank loss.
        if success:
            self.items[instance_id] = replace(item, enhancement_rank=item.enhancement_rank + 1)
        return True


@dataclass
class TravelDirective:
    mode: str = "STORY"
    fixed_region_id: str | None = None
    applies_after_action_sequence: int = 0
    pending: bool = False

    def request(self, mode: str, region_id: str | None, visited: set[str], sequence: int) -> bool:
        if mode == "REGION_FIXED" and (region_id is None or region_id not in visited):
            return False
        if mode not in {"STORY", "REGION_FIXED"}:
            return False
        self.mode = mode
        self.fixed_region_id = region_id if mode == "REGION_FIXED" else None
        self.applies_after_action_sequence = sequence
        self.pending = True
        return True

    def apply_after_action(self, completed_sequence: int) -> bool:
        if not self.pending or completed_sequence < self.applies_after_action_sequence:
            return False
        self.pending = False
        return True


def check_document_contract() -> None:
    text = DOCUMENT.read_text(encoding="utf-8")
    required = (
        "전투 실행은 자동, 전략 선택은 유저, 반복 처리는 시스템",
        "ExpeditionPlan",
        "committedBuildRevision",
        "EquipmentInstance",
        "A1 CORE와 A2 SURVIVAL은 끌 수 없다",
        "장비 instance를 파괴하지 않는다",
        "PD 조건부 승인",
        "라이브 NO-GO",
    )
    missing = [token for token in required if token not in text]
    assert not missing, f"document contract missing: {missing}"


def check_decision_boundaries() -> None:
    assert {contract.domain for contract in CONTRACTS} == {
        "quest_directive",
        "skill_build",
        "behavior_preset",
        "equipment_loadout",
        "loot_policy",
        "craft_enhance",
    }
    assert all(not contract.mid_combat_input for contract in CONTRACTS)
    assert all(not contract.raw_numeric_edit for contract in CONTRACTS)
    assert all(contract.boundary in set(Boundary) for contract in CONTRACTS)


def check_behavior_presets() -> None:
    behavior_presets = ("CAUTIOUS", "BALANCED", "BOLD")
    assert len(behavior_presets) == 3
    assert "RECKLESS" not in behavior_presets


def check_snapshot_isolation() -> None:
    ledger = PlanLedger(Plan())
    first = ledger.start_expedition("expedition-1")
    ledger.stage(replace(ledger.committed, behavior="CAUTIOUS", build_revision=2))
    assert first.plan.behavior == "BALANCED"
    assert ledger.commit_at_inn(expected_previous_revision=1)
    assert ledger.committed.behavior == "CAUTIOUS"
    assert first.plan.behavior == "BALANCED"
    second = ledger.start_expedition("expedition-2")
    assert second.plan.behavior == "CAUTIOUS"


def check_stale_revision_fails_closed() -> None:
    ledger = PlanLedger(Plan())
    ledger.stage(replace(ledger.committed, behavior="BOLD"))
    assert not ledger.commit_at_inn(expected_previous_revision=0)
    assert ledger.committed.behavior == "BALANCED"
    assert ledger.staged is not None


def check_travel_safe_boundary() -> None:
    directive = TravelDirective()
    assert directive.request("REGION_FIXED", "green_hills", {"green_hills"}, sequence=7)
    assert directive.pending
    assert not directive.apply_after_action(6)
    assert directive.apply_after_action(7)
    assert not directive.pending


def check_locked_region_rejected() -> None:
    directive = TravelDirective()
    assert not directive.request("REGION_FIXED", "ashlands", {"green_hills"}, sequence=7)
    assert directive.mode == "STORY"
    assert not directive.pending


def check_item_protection_and_idempotence() -> None:
    inventory = InventoryLedger(
        items={
            "ordinary": Item("ordinary", 5),
            "locked": Item("locked", 10, locked=True),
            "rare": Item("rare", 1, rare_material=True),
            "sidegrade": Item("sidegrade", 8, build_sidegrade=True),
        }
    )
    assert inventory.sell("ordinary", "sale-1", 10)
    assert not inventory.sell("locked", "sale-2", 10)
    assert not inventory.sell("rare", "sale-3", 10)
    assert not inventory.sell("sidegrade", "sale-4", 10)
    assert not inventory.sell("locked", "sale-1", 10)
    assert inventory.gold == 10


def check_enhancement_failure_protection() -> None:
    inventory = InventoryLedger(
        items={"weapon": Item("weapon", 10, enhancement_rank=2)},
        materials=5,
    )
    assert inventory.enhance("weapon", "enhance-1", cost=2, success=False)
    failed = inventory.items["weapon"]
    assert failed.enhancement_rank == 2
    assert not failed.destroyed
    assert inventory.materials == 3
    assert not inventory.enhance("weapon", "enhance-1", cost=2, success=True)
    assert inventory.materials == 3
    assert inventory.enhance("weapon", "enhance-2", cost=2, success=True)
    assert inventory.items["weapon"].enhancement_rank == 3


def check_locked_item_not_enhanced() -> None:
    inventory = InventoryLedger(
        items={"locked": Item("locked", 10, locked=True, enhancement_rank=1)},
        materials=5,
    )
    assert not inventory.enhance("locked", "enhance-locked", cost=2, success=True)
    assert inventory.materials == 5
    assert inventory.items["locked"].enhancement_rank == 1


CHECKS = (
    ("document_contract", check_document_contract),
    ("decision_boundaries", check_decision_boundaries),
    ("behavior_presets", check_behavior_presets),
    ("snapshot_isolation", check_snapshot_isolation),
    ("stale_revision_fails_closed", check_stale_revision_fails_closed),
    ("travel_safe_boundary", check_travel_safe_boundary),
    ("locked_region_rejected", check_locked_region_rejected),
    ("item_protection_and_idempotence", check_item_protection_and_idempotence),
    ("enhancement_failure_protection", check_enhancement_failure_protection),
    ("locked_item_not_enhanced", check_locked_item_not_enhanced),
)


def main() -> None:
    for name, check in CHECKS:
        check()
        print(f"PASS {name}")
    print(f"PASS ({len(CHECKS)}/{len(CHECKS)}) — design contract only, live implementation not approved")


if __name__ == "__main__":
    main()
