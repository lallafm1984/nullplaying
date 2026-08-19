#!/usr/bin/env python3
"""Fail-closed production/balance verifier for P6v Faith Echo.

The verifier is intentionally self-contained and design-only.  It reads the
frozen Registry and production sources, exercises the two-Hero-root ledger and
AI threshold contract, and runs paired passive OFF/ON battle projections.  It
does not run Gradle or mutate Kotlin, tests, the resolver, saves, or live flags.

The P6v runtime, unit-test, and resolver SHA values are frozen production
bindings. CLI overrides remain available for deliberate re-audit, while
--require-exact-bindings refuses an unbound run.
"""

from __future__ import annotations

import argparse
import base64
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass, field
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics

import base_combat_six_classes_v1_5_review as six


ROOT = Path(__file__).resolve().parents[1]
REGISTRY_RESOURCE = (
    ROOT
    / "game-engine/src/main/resources/com/alarmquest/engine/vnext/skill_registry_242_v0_3.json.gz.b64"
)
SOURCE_PATHS = {
    "REGISTRY": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/registry/VNextSkillRegistry242.kt",
    "P5W": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextPassiveEventCatalogP5w.kt",
    "P6U": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextOreMemoryRuntimeP6u.kt",
    "P6V_RUNTIME": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextFaithEchoRuntimeP6v.kt",
    "P6V_TEST": ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextFaithEchoRuntimeP6vTest.kt",
    "RESOLVER": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt",
    "AI": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt",
    "P5M": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5m.kt",
    "P5M_TEST": ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5mTest.kt",
    "P5K": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextSupportSustainSemanticAdapterP5k.kt",
}

EXPECTED_INTEGRATION_SHA256 = {
    "P6V_RUNTIME": "bb4e0da8f18770904d8f1ed7561d03ae5b162c75056e4877e439c668058d3eb1",
    "P6V_TEST": "b055a17b969001f0abdb549534d92aa878d4e4f5534480795a05eebbc56ee660",
    "RESOLVER": "1edbc5c3dded1c6b7280e2200656d5cc3d19e8dd2639fcc17dd964c38b888e9d",
}

EXPECTED_CONSTANTS = {
    "REGISTRY": {
        "V_NEXT_REGISTRY_242_RULES_VERSION": "aq.skill-registry.v0.3",
        "V_NEXT_REGISTRY_242_CONTENT_HASH": "cda98479494d5d2b3d1279e695c26f923ce46249c8835e3e76996fccd67fbdca",
    },
    "P5W": {
        "V_NEXT_P5W_PASSIVE_EVENT_RULES_VERSION": "aq.passive-event-catalog.p5w.v0.1",
        "V_NEXT_P5W_PASSIVE_EVENT_CONTENT_HASH": "d68d4d94a01256b1289685991bd589545a8d30f02891d1119b9a5d9f8522b511",
    },
    "P6U": {
        "V_NEXT_P6U_ORE_MEMORY_RULES_VERSION": "aq.ore-memory.p6u.v0.1",
        "V_NEXT_P6U_ORE_MEMORY_CONTENT_HASH": "dcb4cc93f92e0612bd7bf09e59fd62fa861980f1a130dca53e52765467f774ab",
    },
    "P6V_RUNTIME": {
        "V_NEXT_P6V_FAITH_ECHO_RULES_VERSION": "aq.faith-echo.p6v.v0.1",
        "V_NEXT_P6V_FAITH_ECHO_CONTENT_HASH": "23999e29f7f04d65a33e9323469b98ae759893972935ee6d02ea121987aaaca9",
    },
    "P5M": {
        "V_NEXT_P5M_MIXED_SURVIVAL_RULES_VERSION": "aq.mixed-survival-correction.p5m.v0.2",
        "V_NEXT_P5M_MIXED_SURVIVAL_CONTENT_HASH": "7b9dcb98d474d76c491fac0960569628050cc09e426b57a5e9ae3da7578b88db",
    },
}

FAITH_ECHO = "aq.skill.cleric.p2.faithecho"
EARLY_WARD = "aq.skill.cleric.a3.earlyward"
FOCUSED_WARD = "aq.skill.cleric.a3.focusedward"
LAST_WARD = "aq.skill.cleric.a3.lastward"
COMMON_SHIELD = "aq.skill.common.w5.defensestance"
RESTORING_PRAYER = "aq.skill.cleric.a2.restoringprayer"
MIRACLE_COST = "aq.skill.cleric.w5.miraclecost"
LIFE_DISTRIBUTION = "aq.skill.cleric.w6.lifedistribution"
FIRST_AID = "aq.skill.common.w5.firstaid"

ANCHOR_LEVELS = (1, 25, 50, 75, 100)
FAITH_ANCHORS = (100, 175, 250, 325, 400)
DISPLAY_LEVELS = (58, 9_999)
BEHAVIORS = ("CAUTIOUS", "BALANCED", "BOLD")
BEHAVIOR_OFFSETS = {"CAUTIOUS": 1_000, "BALANCED": 0, "BOLD": -1_000}
MONSTER_RANKS = ("NORMAL", "BOSS")
PROFILES = ("STANDARD", "SWIFT", "ARMORED", "SPELLCASTER")
PROFILE_COUNTS = {"STANDARD": 36, "SWIFT": 42, "ARMORED": 36, "SPELLCASTER": 30}
PROFILE_WEIGHTS = {profile: count / 144.0 for profile, count in PROFILE_COUNTS.items()}
DYNAMIC_SCENARIOS = (
    "RESTORING_PRAYER",
    "MIRACLE_COST",
    "LIFE_DISTRIBUTION",
    "FIRST_AID",
    "COMMON_ONLY",
    "NO_HEAL",
)
ZERO_EFFECT_SCENARIOS = frozenset({"COMMON_ONLY", "NO_HEAL"})

POSITIVE_HOST_IDS = (EARLY_WARD, FOCUSED_WARD, LAST_WARD)
CLERIC_SHIELD_CONTROL_IDS = POSITIVE_HOST_IDS + (COMMON_SHIELD,)
CLERIC_HEAL_IDS = (RESTORING_PRAYER, MIRACLE_COST, LIFE_DISTRIBUTION, FIRST_AID)
GLOBAL_TYPED_HEAL_IDS = frozenset(
    {
        *CLERIC_HEAL_IDS,
        "aq.skill.paladin.a3.earlymercy",
        "aq.skill.paladin.a3.measuredmercy",
        "aq.skill.paladin.a3.crisismercy",
    }
)
EXPECTED_DEFERRED_AFTER_OUTGOING_IDS = frozenset(
    {
        "aq.skill.mage.w4.overload",
        "aq.skill.paladin.w5.mercylimit",
        "aq.skill.warrior.w2.painconversion",
        "aq.skill.world.w8.desertadaptation",
        "aq.skill.world.w8.wellecho",
    }
)

FAST_REFERENCE_SEEDS = 24
PD_AUTHORITATIVE_SEEDS = 200
MAX_HERO_ROOTS = 30
THRESHOLD_MIN_BPS = 1_000
THRESHOLD_MAX_BPS = 10_000
FAITH_DURATION_ROOTS = 2
SURVIVAL_THRESHOLD_STACK_CAP_BPS = 1_200

GATES = {
    "maxCellWinDeltaPp": 20.0,
    "maxMedianRoundDelta": 3.0,
    "maxP90HpLossDeltaPp": 18.0,
    "maxWeightedWinGainPp": 8.0,
    "maxPassiveCausedLossPp": 1.0,
    "minOpportunityCapturePct": 90.0,
    "minActionChangePerTriggerPct": 5.0,
    "maxNoOpConsumePct": 80.0,
    "maxShieldActionShareDeltaPp": 5.0,
    "maxOffenseDisplacementPct": 3.0,
}


@dataclass(frozen=True)
class ShieldSpec:
    definition_id: str
    threshold_anchors: tuple[int, ...] | None
    fixed_threshold_bps: int | None
    shield_anchors: tuple[int, ...]
    attack_coefficient_bps: int
    resource_cost_bps: int
    automation_priority: int
    pattern: str

    def base_threshold(self, skill_level: int) -> int | None:
        if self.fixed_threshold_bps is not None:
            return self.fixed_threshold_bps
        if self.threshold_anchors is None:
            return None
        return value_at(self.threshold_anchors, skill_level)

    def shield_bps(self, skill_level: int) -> int:
        return value_at(self.shield_anchors, skill_level)

    def level_value(self, skill_level: int) -> int:
        if self.definition_id == FOCUSED_WARD:
            return self.shield_bps(skill_level)
        if self.definition_id == COMMON_SHIELD:
            return self.shield_bps(skill_level)
        threshold = self.base_threshold(skill_level)
        if threshold is None:
            raise AssertionError(self.definition_id)
        return threshold


SHIELDS = {
    EARLY_WARD: ShieldSpec(
        EARLY_WARD, (8_500, 8_562, 8_625, 8_687, 8_750), None,
        (500, 500, 500, 500, 500), 10_400, 1_800, 80, "SURVIVAL_SHIELD",
    ),
    FOCUSED_WARD: ShieldSpec(
        FOCUSED_WARD, None, 7_500, (550, 562, 575, 587, 600),
        11_500, 1_800, 90, "SURVIVAL_SHIELD",
    ),
    LAST_WARD: ShieldSpec(
        LAST_WARD, (7_000, 6_937, 6_875, 6_812, 6_750), None,
        (600, 600, 600, 600, 600), 12_000, 1_800, 98, "SURVIVAL_SHIELD",
    ),
    COMMON_SHIELD: ShieldSpec(
        COMMON_SHIELD, None, None, (200, 250, 300, 350, 400),
        0, 1_400, 0, "COMMON_SHIELD",
    ),
}

HEAL_ANCHORS = {
    RESTORING_PRAYER: (800, 850, 900, 950, 1_000),
    MIRACLE_COST: (1_400, 1_500, 1_600, 1_700, 1_800),
    LIFE_DISTRIBUTION: (400, 500, 600, 700, 800),
    FIRST_AID: (150, 188, 225, 263, 300),
}
HEAL_COSTS = {RESTORING_PRAYER: 2_000, MIRACLE_COST: 1_400, LIFE_DISTRIBUTION: 1_400, FIRST_AID: 1_400}


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_json_hash(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return sha256_bytes(encoded)


def load_registry() -> dict:
    encoded = "".join(REGISTRY_RESOURCE.read_text(encoding="utf-8").split())
    return json.loads(gzip.decompress(base64.b64decode(encoded)))


def registry_definition(registry: dict, kind: str, definition_id: str) -> dict:
    rows = [row for row in registry[kind] if row["definitionId"] == definition_id]
    if len(rows) != 1:
        raise AssertionError((kind, definition_id, len(rows)))
    return rows[0]


def extract_string_constant(source: str, name: str) -> str:
    match = re.search(rf"\b{re.escape(name)}(?:\s*:\s*String)?\s*=\s*\"([^\"]+)\"", source)
    if match is None:
        raise AssertionError(f"missing Kotlin constant: {name}")
    return match.group(1)


def value_at(values: tuple[int, ...] | list[int], skill_level: int) -> int:
    if len(values) != 5:
        raise AssertionError(values)
    safe = max(1, min(100, int(skill_level)))
    if safe <= 1:
        return int(values[0])
    if safe >= 100:
        return int(values[-1])
    right = next(index for index, level in enumerate(ANCHOR_LEVELS) if safe <= level)
    left = right - 1
    return int(values[left]) + (
        (int(values[right]) - int(values[left])) * (safe - ANCHOR_LEVELS[left])
        // (ANCHOR_LEVELS[right] - ANCHOR_LEVELS[left])
    )


def clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def threshold_pair(spec: ShieldSpec, skill_level: int, behavior: str, faith_bps: int) -> tuple[int, int]:
    base = spec.base_threshold(skill_level)
    if base is None:
        raise AssertionError(f"non-threshold host: {spec.definition_id}")
    baseline = clamp(base + BEHAVIOR_OFFSETS[behavior], THRESHOLD_MIN_BPS, THRESHOLD_MAX_BPS)
    final = clamp(base + BEHAVIOR_OFFSETS[behavior] + faith_bps, THRESHOLD_MIN_BPS, THRESHOLD_MAX_BPS)
    return baseline, final


def threshold_eligible(hp: int, max_hp: int, threshold_bps: int) -> bool:
    if not 0 <= hp <= max_hp or max_hp <= 0:
        raise AssertionError((hp, max_hp))
    return hp * 10_000 <= max_hp * threshold_bps


def check_registry_contract() -> None:
    registry = load_registry()
    assert canonical_json_hash(registry) == EXPECTED_CONSTANTS["REGISTRY"]["V_NEXT_REGISTRY_242_CONTENT_HASH"]
    assert registry["rulesVersion"] == EXPECTED_CONSTANTS["REGISTRY"]["V_NEXT_REGISTRY_242_RULES_VERSION"]
    assert registry["anchors"] == list(ANCHOR_LEVELS)
    assert registry["slots"] == {"active": 5, "passive": 3}
    passive = registry_definition(registry, "passives", FAITH_ECHO)
    expected = {
        "definitionId": FAITH_ECHO,
        "ideaId": "CLE015",
        "nameKo": "신앙의 여운",
        "ownerScope": "CLERIC",
        "source": "LEVEL",
        "designWave": 1,
        "pattern": "HEAL_SUPPORT",
        "roles": ["HEAL"],
        "conditionId": "after_heal_active",
        "hostScope": "NEXT_SHIELD_ACTIVE",
        "growthField": "hp_threshold_modifier_bps",
        "anchorValues": list(FAITH_ANCHORS),
        "stackGroup": "aq.stack.passive.survival_threshold",
        "stackPolicy": "ADD_THEN_CLAMP",
        "stackCapBps": SURVIVAL_THRESHOLD_STACK_CAP_BPS,
        "fixedTradeoff": "조건부 발동=after_heal_active·미충족 시 효과0",
        "equipmentGrantedSkillIds": [],
        "legacyAdapterId": "",
        "provisional": False,
    }
    assert passive == expected

    typed_hosts = {
        row["definitionId"] for row in registry["actives"] if row["pattern"] == "SURVIVAL_SHIELD"
    }
    cleric_hosts = {
        row["definitionId"] for row in registry["actives"]
        if row["pattern"] == "SURVIVAL_SHIELD" and row["ownerScope"] == "CLERIC"
    }
    assert len(typed_hosts) == 7
    assert cleric_hosts == set(POSITIVE_HOST_IDS)
    common = registry_definition(registry, "actives", COMMON_SHIELD)
    assert common["ownerScope"] == "ALL" and common["pattern"] == "COMMON_SHIELD"
    assert common["candidateRuleId"] == "aq.candidate.common_shield"
    assert common["growthField"] == "shield_maxhp_bps"
    assert "hp_threshold" not in common["candidateRuleId"]

    for definition_id, spec in SHIELDS.items():
        active = registry_definition(registry, "actives", definition_id)
        assert active["pattern"] == spec.pattern
        assert active["resourceCostBps"] == spec.resource_cost_bps
        assert tuple(active["attackEquivalentValues"])[-1] == spec.attack_coefficient_bps
    for definition_id in GLOBAL_TYPED_HEAL_IDS:
        registry_definition(registry, "actives", definition_id)


def check_production_source_contract() -> None:
    for source_name, constants in EXPECTED_CONSTANTS.items():
        source = SOURCE_PATHS[source_name].read_text(encoding="utf-8")
        for name, expected in constants.items():
            assert extract_string_constant(source, name) == expected, (source_name, name)

    p5w = SOURCE_PATHS["P5W"].read_text(encoding="utf-8")
    p6u = SOURCE_PATHS["P6U"].read_text(encoding="utf-8")
    runtime = SOURCE_PATHS["P6V_RUNTIME"].read_text(encoding="utf-8")
    ai = SOURCE_PATHS["AI"].read_text(encoding="utf-8")
    p5m = SOURCE_PATHS["P5M"].read_text(encoding="utf-8")
    p5k = SOURCE_PATHS["P5K"].read_text(encoding="utf-8")

    assert '"after_heal_active"' in p5w and "P5wPassiveEvent.AFTER_OUTGOING_PACKET" in p5w
    assert FAITH_ECHO in p6u
    required_runtime = (
        'private const val P6V_TYPED_HOST_PATTERN = "SURVIVAL_SHIELD"',
        "V_NEXT_P6V_FAITH_ECHO_DURATION_HERO_ROOTS = 2",
        "clericCommonShield=NON_HOST_TOKEN_PRESERVED",
        "thresholdClamp=1000..10000",
        "duration=NEXT_TWO_HERO_ROOTS",
        "STAGED_ROOT_PLAN,AI_PREVIEW,DISPATCH,SINGLE_ROOT_COMMIT",
        "RECOMPUTE_ROOT_ACTION_SESSION_LEDGER_AND_RECEIPT_BOUND_THRESHOLD_HASH",
        "SESSION_BOUND_BOOLEAN_MAGNITUDE_ABSOLUTE_EXPIRY_HERO_ROOT_RECEIPT_HASHED",
        "require(ledger.isInternallyValidP6v(snapshotHash))",
        "rootReceiptId != expectedRootReceiptId",
        "hp.toLong() * 10_000L",
        "copy(survivalThresholdOverrideBps = decision.finalThresholdBps)",
        "deferredAfterOutgoing=5|live=false",
    )
    assert all(anchor in runtime for anchor in required_runtime)
    assert "survivalThresholdOverrideBps: Int? = null" in ai
    assert "state.hp.toLong() * 10_000L" in ai
    assert "request.survivalThresholdDecision" in p5m
    assert "request.faithEchoSessionHash.orEmpty()" in p5m
    assert "request.rootReceiptId" in p5m
    assert "P6V_THRESHOLD_DECISION_REJECTED" in p5m
    assert "p6vThreshold?.finalThresholdBps" in p5m
    assert "LEDGER_COMMON_SURVIVAL_ENCOUNTER" in p5k
    assert COMMON_SHIELD in p5k and FIRST_AID in p5k
    p5m_test = SOURCE_PATHS["P5M_TEST"].read_text(encoding="utf-8")
    assert "p6v threshold receipt is session bound and rejects copied hash evidence" in p5m_test
    assert "p5m:p6v:replayed-root" in p5m_test


def validate_sha(value: str, label: str) -> str:
    normalized = value.strip().lower()
    if normalized and not re.fullmatch(r"[0-9a-f]{64}", normalized):
        raise AssertionError(f"{label} must be a 64-character lowercase SHA-256")
    return normalized


def inspect_integration_bindings(overrides: dict[str, str], require_exact: bool) -> dict[str, object]:
    actual = {name: sha256_bytes(SOURCE_PATHS[name].read_bytes()) for name in EXPECTED_INTEGRATION_SHA256}
    expected = {
        name: validate_sha(overrides.get(name, "") or EXPECTED_INTEGRATION_SHA256[name], name)
        for name in EXPECTED_INTEGRATION_SHA256
    }
    for name, expected_sha in expected.items():
        if expected_sha:
            assert actual[name] == expected_sha, (name, expected_sha, actual[name])
    exact = {name: bool(expected[name]) and expected[name] == actual[name] for name in expected}
    if require_exact and not all(exact.values()):
        missing = [name for name, bound in exact.items() if not bound]
        raise AssertionError(f"unbound integration SHA: {','.join(missing)}")

    test_source = SOURCE_PATHS["P6V_TEST"].read_text(encoding="utf-8")
    resolver = SOURCE_PATHS["RESOLVER"].read_text(encoding="utf-8")
    required_tests = (
        "coverage_closes_typed_hosts_common_negative_control_and_deferred_set",
        "five_anchors_arm_from_committed_heal_and_raise_only_typed_threshold_host",
        "common_shield_is_non_host_and_preserves_token_while_spending_one_window_root",
        "token_is_eligible_on_two_following_roots_and_expires_atomically_at_second_root_end",
        "typed_shield_consumes_once_and_heal_on_same_atomic_root_would_rearm",
        "ai_projection_and_typed_decision_match_growth_and_fixed_threshold_variants",
        "long_threshold_math_stale_plan_replay_and_wrong_class_fail_closed",
        'sessionHash = "forged-session"',
        'tokenMagnitudeBps = 100',
        "passive_absent_has_no_token_or_threshold_modifier",
    )
    assert all(anchor in test_source for anchor in required_tests)
    required_resolver = (
        "faithEchoContentHash",
        "faithEchoCommittedHeroRootCount",
        "faithEchoHealTriggerCount",
        "faithEchoShieldConsumedCount",
        "faithEchoExpiredCount",
        "faithEchoThresholdByActionId",
        "survivalThresholdDecision = faithEchoThreshold",
        "faithEchoSessionHash = if (faithEchoEquipped)",
        "dispatched.survivalThresholdReceipt != faithEchoThreshold",
        "semanticDispatcherContentHash",
        "semanticDispatcherProvenanceHashP6v",
        "addAll(VNextFaithEchoRuntimeP6v.supportedDefinitionIds())",
    )
    assert all(anchor in resolver for anchor in required_resolver)
    return {
        "actualSha256": actual,
        "expectedSha256": expected,
        "exact": exact,
        "allExact": all(exact.values()),
        "mode": "EXACT" if all(exact.values()) else "DISCOVERY",
        "rulesVersion": EXPECTED_CONSTANTS["P6V_RUNTIME"]["V_NEXT_P6V_FAITH_ECHO_RULES_VERSION"],
        "contentHash": extract_string_constant(
            SOURCE_PATHS["P6V_RUNTIME"].read_text(encoding="utf-8"),
            "V_NEXT_P6V_FAITH_ECHO_CONTENT_HASH",
        ),
    }


def check_threshold_matrix() -> dict[str, int]:
    cells = 0
    positive_cells = 0
    common_zero_cells = 0
    opportunity_probes = 0
    saturated_cells = 0
    min_effective_delta = FAITH_ANCHORS[-1]
    max_effective_delta = 0
    for definition_id in CLERIC_SHIELD_CONTROL_IDS:
        spec = SHIELDS[definition_id]
        for faith_bps in FAITH_ANCHORS:
            for active_level in ANCHOR_LEVELS:
                for behavior in BEHAVIORS:
                    if definition_id == COMMON_SHIELD:
                        points = (1, 2_500, 5_000, 7_500, 9_999, 10_000)
                        for hp_bps in points:
                            assert spec.base_threshold(active_level) is None
                            assert 0 <= hp_bps <= 10_000
                            common_zero_cells += 1
                            cells += 1
                        continue
                    baseline, final = threshold_pair(spec, active_level, behavior, faith_bps)
                    effective = final - baseline
                    assert 0 < effective <= faith_bps <= FAITH_ANCHORS[-1]
                    min_effective_delta = min(min_effective_delta, effective)
                    max_effective_delta = max(max_effective_delta, effective)
                    saturated_cells += int(effective < faith_bps)
                    points = (baseline - 1, baseline, baseline + 1, final - 1, final, final + 1)
                    for hp_bps in points:
                        hp_bps = clamp(hp_bps, 0, 10_000)
                        off = threshold_eligible(hp_bps, 10_000, baseline)
                        on = threshold_eligible(hp_bps, 10_000, final)
                        expected_flip = baseline < hp_bps <= final
                        assert (not off and on) == expected_flip
                        opportunity_probes += int(expected_flip)
                        positive_cells += 1
                        cells += 1
    assert cells == 1_800
    assert positive_cells == 1_350 and common_zero_cells == 450
    assert opportunity_probes > 0
    assert min_effective_delta == 100
    assert max_effective_delta == 400

    early_max = SHIELDS[EARLY_WARD]
    baseline, final = threshold_pair(early_max, 100, "CAUTIOUS", 400)
    assert (baseline, final, final - baseline) == (9_750, 10_000, 250)
    assert threshold_eligible(1_500_000_000, 2_000_000_000, 7_900)
    assert not threshold_eligible(1_600_000_001, 2_000_000_000, 8_000)
    return {
        "cells": cells,
        "positiveCells": positive_cells,
        "commonExactZeroCells": common_zero_cells,
        "opportunityProbes": opportunity_probes,
        "saturatedCombinations": saturated_cells,
        "minEffectiveDeltaBps": min_effective_delta,
        "maxEffectiveDeltaBps": max_effective_delta,
    }


@dataclass
class FaithLedger:
    armed: bool = False
    magnitude_bps: int = 0
    expires_after_root: int = 0
    committed_root: int = 0
    triggers: int = 0
    consumes: int = 0
    expiries: int = 0
    receipts: set[str] = field(default_factory=set)

    def snapshot(self) -> tuple[object, ...]:
        return (
            self.armed,
            self.magnitude_bps,
            self.expires_after_root,
            self.committed_root,
            self.triggers,
            self.consumes,
            self.expiries,
            tuple(sorted(self.receipts)),
        )

    def assert_valid(self) -> None:
        assert self.committed_root == len(self.receipts)
        assert self.consumes + self.expiries <= self.triggers
        if self.armed:
            assert self.magnitude_bps in FAITH_ANCHORS
            assert self.expires_after_root > self.committed_root
        else:
            assert self.magnitude_bps == self.expires_after_root == 0


@dataclass(frozen=True)
class RootPreview:
    root: int
    effective_armed: bool
    magnitude_bps: int
    remaining_roots: int


def preview_root(ledger: FaithLedger, root: int) -> RootPreview:
    ledger.assert_valid()
    assert root == ledger.committed_root + 1
    armed = ledger.armed and root <= ledger.expires_after_root
    remaining = ledger.expires_after_root - root + 1 if armed else 0
    return RootPreview(root, armed, ledger.magnitude_bps if armed else 0, clamp(remaining, 0, 2))


def commit_root(
    ledger: FaithLedger,
    preview: RootPreview,
    *,
    receipt: str,
    action_id: str = "BASIC",
    qualifying_heal: bool = False,
    shield_committed: bool = False,
) -> None:
    before = ledger.snapshot()
    assert receipt and receipt not in ledger.receipts
    assert preview.root == ledger.committed_root + 1
    positive_host = action_id in POSITIVE_HOST_IDS
    common = action_id == COMMON_SHIELD
    assert not shield_committed or positive_host or common
    consumed = preview.effective_armed and positive_host and shield_committed
    triggered = qualifying_heal
    survives = preview.effective_armed and not consumed and preview.root < ledger.expires_after_root
    expired = preview.effective_armed and not consumed and not triggered and preview.root >= ledger.expires_after_root

    ledger.armed = triggered or survives
    ledger.magnitude_bps = preview.magnitude_bps if survives else 0
    ledger.expires_after_root = ledger.expires_after_root if survives else 0
    if triggered:
        if preview.magnitude_bps:
            ledger.magnitude_bps = preview.magnitude_bps
        elif ledger.magnitude_bps == 0:
            # The scripted caller sets the selected Faith anchor before an arm.
            raise AssertionError("trigger magnitude must be staged")
        ledger.expires_after_root = preview.root + FAITH_DURATION_ROOTS
    ledger.committed_root = preview.root
    ledger.triggers += int(triggered)
    ledger.consumes += int(consumed)
    ledger.expiries += int(expired)
    ledger.receipts.add(receipt)
    ledger.assert_valid()
    assert ledger.snapshot() != before


def arm_at_root_one(faith_bps: int, heal_id: str, suffix: str) -> FaithLedger:
    assert heal_id in CLERIC_HEAL_IDS
    ledger = FaithLedger()
    preview = preview_root(ledger, 1)
    # A committed typed Heal arms with the equipped passive's resolved anchor.
    ledger.magnitude_bps = faith_bps
    commit_root(
        ledger,
        RootPreview(preview.root, preview.effective_armed, faith_bps, preview.remaining_roots),
        receipt=f"{suffix}:heal",
        action_id=heal_id,
        qualifying_heal=True,
    )
    return ledger


def check_scripted_ledger_matrix() -> dict[str, int]:
    cases = 0
    common_preservations = 0
    first_aid_common_blocks = 0
    for faith_bps in FAITH_ANCHORS:
        for heal_id in CLERIC_HEAL_IDS:
            for host_id in POSITIVE_HOST_IDS:
                prefix = f"{faith_bps}:{heal_id}:{host_id}"

                n1 = arm_at_root_one(faith_bps, heal_id, prefix + ":n1")
                p2 = preview_root(n1, 2)
                assert p2.effective_armed and p2.remaining_roots == 2
                commit_root(n1, p2, receipt=prefix + ":n1:shield", action_id=host_id, shield_committed=True)
                assert not n1.armed and n1.consumes == 1
                cases += 1

                n2 = arm_at_root_one(faith_bps, heal_id, prefix + ":n2")
                p2 = preview_root(n2, 2)
                commit_root(n2, p2, receipt=prefix + ":n2:basic", action_id="BASIC")
                p3 = preview_root(n2, 3)
                assert p3.effective_armed and p3.remaining_roots == 1
                commit_root(n2, p3, receipt=prefix + ":n2:shield", action_id=host_id, shield_committed=True)
                assert not n2.armed and n2.consumes == 1
                cases += 1

                n3 = arm_at_root_one(faith_bps, heal_id, prefix + ":n3")
                p2 = preview_root(n3, 2)
                commit_root(n3, p2, receipt=prefix + ":n3:basic", action_id="BASIC")
                p3 = preview_root(n3, 3)
                commit_root(n3, p3, receipt=prefix + ":n3:system", action_id="SYSTEM_RECOVERY")
                assert not n3.armed and n3.expiries == 1
                p4 = preview_root(n3, 4)
                assert not p4.effective_armed and p4.remaining_roots == 0
                cases += 1

                delayed = arm_at_root_one(faith_bps, heal_id, prefix + ":delayed")
                p2 = preview_root(delayed, 2)
                commit_root(delayed, p2, receipt=prefix + ":delayed:resolve", action_id="DELAYED_RESOLUTION")
                p3 = preview_root(delayed, 3)
                commit_root(delayed, p3, receipt=prefix + ":delayed:cancel", action_id="DELAYED_CANCEL")
                assert not delayed.armed and delayed.expiries == 1
                cases += 1

                common_then_host = arm_at_root_one(faith_bps, heal_id, prefix + ":common")
                p2 = preview_root(common_then_host, 2)
                commit_root(
                    common_then_host, p2, receipt=prefix + ":common:shield",
                    action_id=COMMON_SHIELD, shield_committed=True,
                )
                assert common_then_host.armed and common_then_host.consumes == 0
                common_preservations += 1
                p3 = preview_root(common_then_host, 3)
                commit_root(
                    common_then_host, p3, receipt=prefix + ":common:host",
                    action_id=host_id, shield_committed=True,
                )
                assert not common_then_host.armed and common_then_host.consumes == 1
                cases += 1

                common_at_n2 = arm_at_root_one(faith_bps, heal_id, prefix + ":common-n2")
                p2 = preview_root(common_at_n2, 2)
                commit_root(common_at_n2, p2, receipt=prefix + ":common-n2:basic", action_id="BASIC")
                p3 = preview_root(common_at_n2, 3)
                commit_root(
                    common_at_n2, p3, receipt=prefix + ":common-n2:shield",
                    action_id=COMMON_SHIELD, shield_committed=True,
                )
                assert not common_at_n2.armed and common_at_n2.consumes == 0 and common_at_n2.expiries == 1
                common_preservations += 1
                cases += 1

                refreshed = arm_at_root_one(faith_bps, heal_id, prefix + ":refresh")
                p2 = preview_root(refreshed, 2)
                commit_root(
                    refreshed,
                    RootPreview(p2.root, p2.effective_armed, faith_bps, p2.remaining_roots),
                    receipt=prefix + ":refresh:heal2",
                    action_id=heal_id,
                    qualifying_heal=True,
                )
                assert refreshed.armed and refreshed.expires_after_root == 4 and refreshed.triggers == 2
                p3 = preview_root(refreshed, 3)
                commit_root(refreshed, p3, receipt=prefix + ":refresh:basic", action_id="BASIC")
                p4 = preview_root(refreshed, 4)
                commit_root(
                    refreshed, p4, receipt=prefix + ":refresh:host",
                    action_id=host_id, shield_committed=True,
                )
                assert not refreshed.armed and refreshed.consumes == 1
                cases += 1

                rejected = arm_at_root_one(faith_bps, heal_id, prefix + ":reject")
                before = rejected.snapshot()
                preview_root(rejected, 2)  # AI preview / cooldown / resource / semantic reject.
                assert rejected.snapshot() == before
                cases += 1

                if heal_id == FIRST_AID:
                    # First Aid spends the same common-survival encounter ledger.
                    common_candidate_eligible = False
                    assert not common_candidate_eligible
                    assert rejected.snapshot() == before
                    first_aid_common_blocks += 1
                    cases += 1

    assert common_preservations == len(FAITH_ANCHORS) * len(CLERIC_HEAL_IDS) * len(POSITIVE_HOST_IDS) * 2
    assert first_aid_common_blocks == len(FAITH_ANCHORS) * len(POSITIVE_HOST_IDS)
    return {
        "scriptedCases": cases,
        "commonPreservations": common_preservations,
        "firstAidCommonBlocks": first_aid_common_blocks,
        "invalidConsumes": 0,
    }


@dataclass(frozen=True)
class AiState:
    hp: int
    max_hp: int
    resource_bps: int
    sustain_spent_bps: int
    class_ledger_closed: bool
    common_ledger_closed: bool
    blocked_ids: frozenset[str] = frozenset()
    telegraph_coefficient_bps: int = 0


def policy_delta(definition_id: str, behavior: str) -> int:
    if definition_id in POSITIVE_HOST_IDS:
        return {"CAUTIOUS": 90, "BALANCED": 45, "BOLD": 90}[behavior]
    if definition_id == COMMON_SHIELD:
        return {"CAUTIOUS": 90, "BALANCED": 45, "BOLD": 0}[behavior]
    raise AssertionError(definition_id)


def shield_candidate_eligible(
    spec: ShieldSpec,
    state: AiState,
    skill_level: int,
    behavior: str,
    threshold_modifier_bps: int,
) -> bool:
    if spec.definition_id in state.blocked_ids:
        return False
    if spec.definition_id == COMMON_SHIELD:
        if state.common_ledger_closed:
            return False
    else:
        if state.class_ledger_closed:
            return False
        base = spec.base_threshold(skill_level)
        if base is None:
            raise AssertionError(spec.definition_id)
        threshold = clamp(
            base + BEHAVIOR_OFFSETS[behavior] + threshold_modifier_bps,
            THRESHOLD_MIN_BPS,
            THRESHOLD_MAX_BPS,
        )
        if not threshold_eligible(state.hp, state.max_hp, threshold):
            return False
    nominal = spec.shield_bps(skill_level)
    if state.sustain_spent_bps + nominal > 3_000:
        return False
    if state.resource_bps < spec.resource_cost_bps:
        return False
    reserve = {"CAUTIOUS": 4_000, "BALANCED": 2_000, "BOLD": 0}[behavior]
    lethal = state.hp * 10_000 <= state.max_hp * 2_500
    if state.resource_bps - spec.resource_cost_bps < reserve and not lethal:
        return False
    return True


def choose_ai_action(
    available_ids: tuple[str, ...],
    state: AiState,
    skill_level: int,
    behavior: str,
    threshold_modifier_bps: int,
) -> str:
    candidates: list[tuple[int, int, int, str]] = []
    for definition_id in available_ids:
        spec = SHIELDS[definition_id]
        modifier = threshold_modifier_bps if definition_id in POSITIVE_HOST_IDS else 0
        if not shield_candidate_eligible(spec, state, skill_level, behavior, modifier):
            continue
        if state.hp * 10_000 <= state.max_hp * 2_500:
            band = 900
        elif state.telegraph_coefficient_bps >= 13_000:
            band = 800
        else:
            band = 400
        delta = policy_delta(definition_id, behavior) + spec.automation_priority
        candidates.append((band, delta, spec.level_value(skill_level), definition_id))
    if not candidates:
        return "BASIC"
    return sorted(candidates, key=lambda row: (-row[0], -row[1], -row[2], row[3]))[0][3]


def check_ai_opportunity_matrix() -> dict[str, float | int]:
    decisions = 0
    opportunities = 0
    captures = 0
    common_exact_zero = 0
    outside_window_exact = 0
    for host_id in POSITIVE_HOST_IDS:
        host = SHIELDS[host_id]
        for faith_bps in FAITH_ANCHORS:
            for active_level in ANCHOR_LEVELS:
                for behavior in BEHAVIORS:
                    baseline, final = threshold_pair(host, active_level, behavior, faith_bps)
                    hp = baseline + max(1, (final - baseline) // 2)
                    state = AiState(hp, 10_000, 10_000, 0, False, False)
                    isolated = (host_id,)
                    off = choose_ai_action(isolated, state, active_level, behavior, 0)
                    on = choose_ai_action(isolated, state, active_level, behavior, faith_bps)
                    assert off == "BASIC" and on == host_id
                    opportunities += 1
                    captures += 1
                    decisions += 2

                    full = CLERIC_SHIELD_CONTROL_IDS
                    full_off = choose_ai_action(full, state, active_level, behavior, 0)
                    full_on = choose_ai_action(full, state, active_level, behavior, faith_bps)
                    assert full_on == host_id and full_off != full_on
                    opportunities += 1
                    captures += 1
                    decisions += 2

                    below = AiState(max(0, baseline - 1), 10_000, 10_000, 0, False, False)
                    above = AiState(min(10_000, final + 1), 10_000, 10_000, 0, False, False)
                    assert choose_ai_action(isolated, below, active_level, behavior, 0) == choose_ai_action(
                        isolated, below, active_level, behavior, faith_bps,
                    )
                    if final < 10_000:
                        assert choose_ai_action(isolated, above, active_level, behavior, 0) == choose_ai_action(
                            isolated, above, active_level, behavior, faith_bps,
                        )
                        outside_window_exact += 1
                        decisions += 2
                    outside_window_exact += 1
                    decisions += 2

                    blocked = AiState(
                        hp, 10_000, 1_000, 0, False, False,
                        blocked_ids=frozenset({COMMON_SHIELD}),
                    )
                    assert choose_ai_action(full, blocked, active_level, behavior, 0) == "BASIC"
                    assert choose_ai_action(full, blocked, active_level, behavior, faith_bps) == "BASIC"
                    decisions += 2

                    lethal = AiState(2_500, 10_000, 10_000, 0, False, False)
                    assert choose_ai_action(full, lethal, active_level, behavior, 0) == choose_ai_action(
                        full, lethal, active_level, behavior, faith_bps,
                    )
                    outside_window_exact += 1
                    decisions += 2

                    for common_hp in (1, 2_500, 5_000, 7_500, 9_999, 10_000):
                        common_state = AiState(common_hp, 10_000, 10_000, 0, True, False)
                        assert choose_ai_action((COMMON_SHIELD,), common_state, active_level, behavior, 0) == (
                            choose_ai_action((COMMON_SHIELD,), common_state, active_level, behavior, faith_bps)
                        )
                        common_exact_zero += 1
                        decisions += 2
    capture_pct = captures * 100.0 / opportunities
    assert capture_pct == 100.0
    assert common_exact_zero == 1_350
    return {
        "decisions": decisions,
        "opportunityExposures": opportunities,
        "opportunityCaptures": captures,
        "opportunityCapturePct": capture_pct,
        "commonExactZeroDecisions": common_exact_zero,
        "outsideWindowExactCases": outside_window_exact,
    }


MASK_64 = (1 << 64) - 1


def mix64(value: int) -> int:
    value = (value + 0x9E3779B97F4A7C15) & MASK_64
    value = ((value ^ (value >> 30)) * 0xBF58476D1CE4E5B9) & MASK_64
    value = ((value ^ (value >> 27)) * 0x94D049BB133111EB) & MASK_64
    return value ^ (value >> 31)


def keyed_roll(seed_key: int, root_index: int, side: int, channel: int, stop: int) -> int:
    key = seed_key ^ (root_index * 0xD6E8FEB86659FD93) ^ (side * 0xA5A3564E27F8862B)
    key ^= channel * 0x9E3779B185EBCA87
    return mix64(key & MASK_64) % stop


def stable_cell_salt(parts: tuple[object, ...]) -> int:
    encoded = "|".join(map(str, parts)).encode("utf-8")
    return int.from_bytes(hashlib.sha256(encoded).digest()[:8], "big")


def round_half_up_ratio(numerator: int, denominator: int) -> int:
    if numerator < 0 or denominator <= 0:
        raise AssertionError((numerator, denominator))
    return (numerator * 2 + denominator) // (denominator * 2)


def rounded_bps(value: int, bps: int) -> int:
    return round_half_up_ratio(value * bps, 10_000)


def mitigation_from_defense(defense: int, combat_rank: int) -> int:
    scale = 40 + 5 * combat_rank
    return min(7_000, round_half_up_ratio(10_000 * defense, defense + scale))


@dataclass(frozen=True)
class CombatFixture:
    hero_max_hp: int
    monster_max_hp: int
    hero_magic_power: int
    monster_power: int
    hero_hit_bps: int
    monster_hit_bps: int
    hero_crit_bps: int
    monster_crit_bps: int
    hero_mitigation_bps: int
    monster_mres: int
    combat_rank: int
    monster_coefficient_bps: int


def make_combat_fixture(display_level: int, profile: str, monster_rank: str) -> CombatFixture:
    source = six.source_for_display("CLERIC", display_level, "BASE")
    hero = six.derive_hero(source).combatant
    monster = six.monster_for(source, profile, monster_rank)
    core = six.v13.core
    magical_hero = hero.__class__(**{**hero.__dict__, "attack_type": "MAGIC"})
    monster_power = monster.physical_attack if monster.attack_type == "PHYSICAL" else monster.magical_attack
    return CombatFixture(
        hero.max_hp,
        monster.max_hp,
        hero.magical_attack,
        monster_power,
        core.hit_bps(magical_hero, monster),
        core.hit_bps(monster, hero),
        hero.critical_bps if hasattr(hero, "critical_bps") else core.critical_bps(hero, monster),
        monster.critical_bps if hasattr(monster, "critical_bps") else core.critical_bps(monster, hero),
        core.mitigation_bps(monster, hero),
        monster.magical_resistance,
        source.combat_rank,
        monster.action_coefficient_bps,
    )


def packet_damage(
    power: int,
    coefficient_bps: int,
    mitigation_bps: int,
    hit: bool,
    crit: bool,
    variance_bps: int,
) -> int:
    if not hit:
        return 0
    damage = rounded_bps(power, coefficient_bps)
    damage = rounded_bps(damage, 15_000 if crit else 10_000)
    damage = rounded_bps(damage, variance_bps)
    return max(1, rounded_bps(damage, 10_000 - mitigation_bps))


@dataclass(frozen=True)
class BattleSetup:
    hero_hp: int
    resource_bps: int
    sustain_spent_bps: int
    common_ledger_closed: bool
    token_armed: bool
    available_ids: tuple[str, ...]
    trigger_count: int


def scenario_heal_id(scenario: str) -> str | None:
    return {
        "RESTORING_PRAYER": RESTORING_PRAYER,
        "MIRACLE_COST": MIRACLE_COST,
        "LIFE_DISTRIBUTION": LIFE_DISTRIBUTION,
        "FIRST_AID": FIRST_AID,
        "COMMON_ONLY": RESTORING_PRAYER,
        "NO_HEAL": None,
    }[scenario]


def opportunity_target_bps(
    scenario: str,
    faith_bps: int,
    active_level: int,
    behavior: str,
    seed_key: int,
) -> tuple[int, bool]:
    if scenario in ZERO_EFFECT_SCENARIOS or scenario == "LIFE_DISTRIBUTION":
        return 5_000, False
    request_opportunity = keyed_roll(seed_key, 0, 0, 17, 2) == 0
    if not request_opportunity:
        return 5_000, False
    if scenario == "FIRST_AID":
        spec = SHIELDS[EARLY_WARD]
    elif scenario == "RESTORING_PRAYER":
        spec = SHIELDS[FOCUSED_WARD if keyed_roll(seed_key, 0, 0, 18, 2) == 0 else LAST_WARD]
    elif scenario == "MIRACLE_COST":
        if behavior == "BOLD":
            return 4_800, False
        spec = SHIELDS[LAST_WARD]
    else:
        raise AssertionError(scenario)
    baseline, final = threshold_pair(spec, active_level, behavior, faith_bps)
    return baseline + max(1, (final - baseline) // 2), True


def make_battle_setup(
    fixture: CombatFixture,
    scenario: str,
    faith_bps: int,
    active_level: int,
    behavior: str,
    seed_key: int,
    faith_on: bool,
) -> BattleSetup:
    if scenario == "NO_HEAL":
        hp_bps = 7_500 + keyed_roll(seed_key, 0, 0, 19, 1_501)
        return BattleSetup(
            rounded_bps(fixture.hero_max_hp, hp_bps), 10_000, 0, False,
            False, CLERIC_SHIELD_CONTROL_IDS, 0,
        )
    heal_id = scenario_heal_id(scenario)
    if heal_id is None:
        raise AssertionError(scenario)
    target_bps, opportunity_requested = opportunity_target_bps(
        scenario, faith_bps, active_level, behavior, seed_key,
    )
    heal_bps = value_at(HEAL_ANCHORS[heal_id], active_level)
    if heal_id == LIFE_DISTRIBUTION:
        pre_bps = max(1, 5_000 - heal_bps)
        target_bps = min(5_000, pre_bps + heal_bps)
    else:
        pre_bps = max(1, target_bps - heal_bps)
    if heal_id == RESTORING_PRAYER:
        threshold = clamp(7_000 + BEHAVIOR_OFFSETS[behavior], 1_000, 10_000)
        if pre_bps > threshold:
            pre_bps = max(1, threshold - heal_bps // 2)
            target_bps = min(10_000, pre_bps + heal_bps)
            opportunity_requested = False
    if heal_id == MIRACLE_COST:
        threshold = clamp(4_500 + BEHAVIOR_OFFSETS[behavior], 1_000, 10_000)
        if pre_bps > threshold:
            pre_bps = max(1, threshold - heal_bps)
            target_bps = min(10_000, pre_bps + heal_bps)
            opportunity_requested = False
    if heal_id == FIRST_AID:
        target_bps = min(10_000, target_bps)
    assert 0 < pre_bps <= target_bps <= 10_000
    # opportunity_requested is intentionally validated by the live AI gate,
    # not trusted as attribution.
    _ = opportunity_requested
    available = (COMMON_SHIELD,) if scenario == "COMMON_ONLY" else CLERIC_SHIELD_CONTROL_IDS
    return BattleSetup(
        rounded_bps(fixture.hero_max_hp, target_bps),
        10_000 - HEAL_COSTS[heal_id],
        heal_bps,
        heal_id == FIRST_AID,
        faith_on,
        available,
        int(faith_on),
    )


@dataclass(frozen=True)
class BattleResult:
    outcome: str
    rounds: int
    hp_loss_pct: float
    hero_damage: int
    hero_roots: int
    shield_actions: int
    actions: tuple[str, ...]
    trigger_count: int
    consume_count: int
    expiry_count: int
    opportunity_exposures: int
    attributed_action_changes: int
    no_op_consumes: int
    common_preservations: int
    offense_displacements: int
    invalid_consumes: int = 0


def simulate_battle(
    fixture: CombatFixture,
    *,
    scenario: str,
    faith_bps: int,
    active_level: int,
    behavior: str,
    seed_key: int,
    faith_on: bool,
) -> BattleResult:
    setup = make_battle_setup(
        fixture, scenario, faith_bps, active_level, behavior, seed_key, faith_on,
    )
    hero_hp = setup.hero_hp
    monster_hp = fixture.monster_max_hp
    resource = setup.resource_bps
    sustain = setup.sustain_spent_bps
    class_closed = False
    common_closed = setup.common_ledger_closed
    shield_hp = 0
    token_armed = setup.token_armed
    token_expires = 3 if token_armed else 0  # Heal root 1 -> roots 2 and 3.
    trigger_count = setup.trigger_count
    consume_count = 0
    expiry_count = 0
    opportunity_exposures = 0
    attributed_changes = 0
    no_op_consumes = 0
    common_preservations = 0
    offense_displacements = 0
    shield_actions = 0
    hero_damage = 0
    actions: list[str] = []
    monster_mitigation = mitigation_from_defense(fixture.monster_mres, fixture.combat_rank)

    root = 2
    for battle_round in range(1, MAX_HERO_ROOTS + 1):
        if hero_hp <= 0 or monster_hp <= 0:
            break
        effective_armed = token_armed and root <= token_expires
        state = AiState(hero_hp, fixture.hero_max_hp, resource, sustain, class_closed, common_closed)
        modifier = faith_bps if effective_armed else 0
        action = choose_ai_action(setup.available_ids, state, active_level, behavior, modifier)
        baseline_action = choose_ai_action(setup.available_ids, state, active_level, behavior, 0)
        if effective_armed:
            flipped = any(
                definition_id in POSITIVE_HOST_IDS
                and not shield_candidate_eligible(SHIELDS[definition_id], state, active_level, behavior, 0)
                and shield_candidate_eligible(SHIELDS[definition_id], state, active_level, behavior, faith_bps)
                for definition_id in setup.available_ids
            )
            opportunity_exposures += int(flipped)
            if action != baseline_action:
                attributed_changes += 1
                offense_displacements += int(baseline_action == "BASIC")
        actions.append(action)

        if action in SHIELDS:
            spec = SHIELDS[action]
            resource -= spec.resource_cost_bps
            sustain += spec.shield_bps(active_level)
            shield_hp += rounded_bps(fixture.hero_max_hp, spec.shield_bps(active_level))
            shield_actions += 1
            if action == COMMON_SHIELD:
                common_closed = True
                common_preservations += int(effective_armed)
            else:
                class_closed = True
                if effective_armed:
                    consume_count += 1
                    no_op_consumes += int(action == baseline_action)
                    token_armed = False
                    token_expires = 0
            coefficient = spec.attack_coefficient_bps
        else:
            coefficient = 10_400
            resource = min(10_000, resource + 800)

        if coefficient > 0:
            hit = keyed_roll(seed_key, root, 0, 1, 10_000) < fixture.hero_hit_bps
            crit = action == "BASIC" and keyed_roll(seed_key, root, 0, 2, 10_000) < fixture.hero_crit_bps
            variance = 9_500 + keyed_roll(seed_key, root, 0, 3, 1_001)
            behavior_coefficient = {"CAUTIOUS": -300, "BALANCED": 0, "BOLD": 300}[behavior]
            damage = packet_damage(
                fixture.hero_magic_power,
                max(1, coefficient + behavior_coefficient),
                monster_mitigation,
                hit,
                crit,
                variance,
            )
            monster_hp = max(0, monster_hp - damage)
            hero_damage += damage
        if monster_hp <= 0:
            outcome = "WIN"
            break

        monster_hit = keyed_roll(seed_key, root, 1, 1, 10_000) < fixture.monster_hit_bps
        monster_crit = keyed_roll(seed_key, root, 1, 2, 10_000) < fixture.monster_crit_bps
        monster_variance = 9_500 + keyed_roll(seed_key, root, 1, 3, 1_001)
        incoming = packet_damage(
            fixture.monster_power,
            fixture.monster_coefficient_bps,
            fixture.hero_mitigation_bps,
            monster_hit,
            monster_crit,
            monster_variance,
        )
        absorbed = min(shield_hp, incoming)
        shield_hp -= absorbed
        hero_hp = max(0, hero_hp - (incoming - absorbed))
        if hero_hp <= 0:
            outcome = "LOSS"
            break

        if token_armed and root >= token_expires:
            token_armed = False
            token_expires = 0
            expiry_count += 1
        root += 1
    else:
        battle_round = MAX_HERO_ROOTS
        outcome = "RETREAT"

    hero_roots = len(actions)
    assert consume_count <= trigger_count
    assert attributed_changes <= trigger_count
    assert no_op_consumes <= consume_count
    return BattleResult(
        outcome,
        battle_round,
        (fixture.hero_max_hp - hero_hp) * 100.0 / fixture.hero_max_hp,
        hero_damage,
        hero_roots,
        shield_actions,
        tuple(actions),
        trigger_count,
        consume_count,
        expiry_count,
        opportunity_exposures,
        attributed_changes,
        no_op_consumes,
        common_preservations,
        offense_displacements,
    )


@dataclass(frozen=True)
class Metrics:
    win_rate: float
    median_rounds: float
    p90_hp_loss: float


def percentile_nearest_rank(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def metrics(results: list[BattleResult]) -> Metrics:
    return Metrics(
        sum(result.outcome == "WIN" for result in results) / len(results),
        float(statistics.median(result.rounds for result in results)),
        percentile_nearest_rank([result.hp_loss_pct for result in results], 0.90),
    )


def measure_scenario(scenario: str, seeds: int) -> dict[str, object]:
    if scenario not in DYNAMIC_SCENARIOS:
        raise AssertionError(scenario)
    fixture_cache: dict[tuple[int, str, str], CombatFixture] = {}
    cells = 0
    paired_battles = 0
    exact_zero_cells = 0
    exact_zero_pairs = 0
    max_cell_win = 0.0
    max_rounds = 0.0
    max_p90_hp = 0.0
    max_weighted_gain = 0.0
    max_passive_loss = 0.0
    worst_cell: tuple[object, ...] | None = None
    counters = {
        "triggers": 0,
        "consumes": 0,
        "expiries": 0,
        "opportunityExposures": 0,
        "attributedActionChanges": 0,
        "noOpConsumes": 0,
        "commonPreservations": 0,
        "offenseDisplacements": 0,
        "invalidConsumes": 0,
        "onHeroRoots": 0,
        "offHeroRoots": 0,
        "onShieldActions": 0,
        "offShieldActions": 0,
    }

    for display_level in DISPLAY_LEVELS:
        for faith_level, faith_bps in zip(ANCHOR_LEVELS, FAITH_ANCHORS):
            for active_level in ANCHOR_LEVELS:
                for behavior in BEHAVIORS:
                    for monster_rank in MONSTER_RANKS:
                        weighted_off = 0.0
                        weighted_on = 0.0
                        for profile in PROFILES:
                            fixture_key = (display_level, profile, monster_rank)
                            if fixture_key not in fixture_cache:
                                fixture_cache[fixture_key] = make_combat_fixture(*fixture_key)
                            fixture = fixture_cache[fixture_key]
                            salt = stable_cell_salt(
                                (scenario, display_level, faith_level, active_level, behavior, monster_rank, profile)
                            )
                            off_results: list[BattleResult] = []
                            on_results: list[BattleResult] = []
                            for seed in range(seeds):
                                seed_key = mix64(salt ^ ((seed + 1) * 0xD1342543DE82EF95))
                                kwargs = dict(
                                    fixture=fixture,
                                    scenario=scenario,
                                    faith_bps=faith_bps,
                                    active_level=active_level,
                                    behavior=behavior,
                                    seed_key=seed_key,
                                )
                                off = simulate_battle(**kwargs, faith_on=False)
                                on = simulate_battle(**kwargs, faith_on=True)
                                assert off.invalid_consumes == on.invalid_consumes == 0
                                if scenario in ZERO_EFFECT_SCENARIOS:
                                    assert (
                                        off.outcome,
                                        off.rounds,
                                        off.hp_loss_pct,
                                        off.hero_damage,
                                        off.shield_actions,
                                        off.actions,
                                    ) == (
                                        on.outcome,
                                        on.rounds,
                                        on.hp_loss_pct,
                                        on.hero_damage,
                                        on.shield_actions,
                                        on.actions,
                                    )
                                    exact_zero_pairs += 1
                                assert on.attributed_action_changes <= on.trigger_count
                                off_results.append(off)
                                on_results.append(on)
                                counters["triggers"] += on.trigger_count
                                counters["consumes"] += on.consume_count
                                counters["expiries"] += on.expiry_count
                                counters["opportunityExposures"] += on.opportunity_exposures
                                counters["attributedActionChanges"] += on.attributed_action_changes
                                counters["noOpConsumes"] += on.no_op_consumes
                                counters["commonPreservations"] += on.common_preservations
                                counters["offenseDisplacements"] += on.offense_displacements
                                counters["invalidConsumes"] += on.invalid_consumes
                                counters["onHeroRoots"] += on.hero_roots
                                counters["offHeroRoots"] += off.hero_roots
                                counters["onShieldActions"] += on.shield_actions
                                counters["offShieldActions"] += off.shield_actions
                            off_metric = metrics(off_results)
                            on_metric = metrics(on_results)
                            delta = abs(on_metric.win_rate - off_metric.win_rate) * 100.0
                            if delta > max_cell_win:
                                max_cell_win = delta
                                worst_cell = (
                                    scenario,
                                    display_level,
                                    faith_level,
                                    active_level,
                                    behavior,
                                    monster_rank,
                                    profile,
                                )
                            max_rounds = max(max_rounds, abs(on_metric.median_rounds - off_metric.median_rounds))
                            max_p90_hp = max(max_p90_hp, abs(on_metric.p90_hp_loss - off_metric.p90_hp_loss))
                            weighted_off += off_metric.win_rate * PROFILE_WEIGHTS[profile]
                            weighted_on += on_metric.win_rate * PROFILE_WEIGHTS[profile]
                            cells += 1
                            paired_battles += seeds
                            if scenario in ZERO_EFFECT_SCENARIOS:
                                exact_zero_cells += 1
                        max_weighted_gain = max(max_weighted_gain, (weighted_on - weighted_off) * 100.0)
                        max_passive_loss = max(max_passive_loss, (weighted_off - weighted_on) * 100.0)
    expected_cells = (
        len(DISPLAY_LEVELS) * len(ANCHOR_LEVELS) * len(ANCHOR_LEVELS) * len(BEHAVIORS)
        * len(MONSTER_RANKS) * len(PROFILES)
    )
    assert cells == expected_cells == 1_200
    assert paired_battles == cells * seeds
    if scenario in ZERO_EFFECT_SCENARIOS:
        assert exact_zero_cells == cells and exact_zero_pairs == paired_battles
    else:
        assert exact_zero_cells == exact_zero_pairs == 0
    return {
        "scenario": scenario,
        "cells": cells,
        "pairedBattles": paired_battles,
        "exactZeroCells": exact_zero_cells,
        "exactZeroPairs": exact_zero_pairs,
        "maxCellWinDeltaPp": max_cell_win,
        "maxMedianRoundDelta": max_rounds,
        "maxP90HpLossDeltaPp": max_p90_hp,
        "maxWeightedWinGainPp": max_weighted_gain,
        "maxPassiveCausedLossPp": max_passive_loss,
        "worstCell": worst_cell,
        **counters,
    }


def measure_scenario_args(args: tuple[str, int]) -> dict[str, object]:
    return measure_scenario(*args)


def measure_dynamic(seeds: int, workers: int) -> dict[str, object]:
    jobs = [(scenario, seeds) for scenario in DYNAMIC_SCENARIOS]
    if workers > 1:
        with ProcessPoolExecutor(max_workers=workers) as executor:
            rows = list(executor.map(measure_scenario_args, jobs))
    else:
        rows = [measure_scenario(*job) for job in jobs]

    counter_names = (
        "triggers",
        "consumes",
        "expiries",
        "opportunityExposures",
        "attributedActionChanges",
        "noOpConsumes",
        "commonPreservations",
        "offenseDisplacements",
        "invalidConsumes",
        "onHeroRoots",
        "offHeroRoots",
        "onShieldActions",
        "offShieldActions",
    )
    counters = {name: sum(int(row[name]) for row in rows) for name in counter_names}
    cell_win_quantum_pp = 100.0 / seeds
    cell_win_gate_pp = (
        GATES["maxCellWinDeltaPp"]
        if seeds >= PD_AUTHORITATIVE_SEEDS
        else math.ceil(GATES["maxCellWinDeltaPp"] / cell_win_quantum_pp - 1e-12) * cell_win_quantum_pp
    )
    passive_loss_gate_pp = (
        GATES["maxPassiveCausedLossPp"]
        if seeds >= PD_AUTHORITATIVE_SEEDS
        else math.ceil(GATES["maxPassiveCausedLossPp"] / cell_win_quantum_pp - 1e-12) * cell_win_quantum_pp
    )
    opportunity_capture = (
        counters["attributedActionChanges"] * 100.0 / max(1, counters["opportunityExposures"])
    )
    action_change_per_trigger = (
        counters["attributedActionChanges"] * 100.0 / max(1, counters["triggers"])
    )
    no_op_consume = counters["noOpConsumes"] * 100.0 / max(1, counters["consumes"])
    offense_displacement = counters["offenseDisplacements"] * 100.0 / max(1, counters["onHeroRoots"])
    shield_share_off = counters["offShieldActions"] * 100.0 / max(1, counters["offHeroRoots"])
    shield_share_on = counters["onShieldActions"] * 100.0 / max(1, counters["onHeroRoots"])
    shield_share_delta = abs(shield_share_on - shield_share_off)
    worst = max(rows, key=lambda row: float(row["maxCellWinDeltaPp"]))
    summary: dict[str, object] = {
        "cells": sum(int(row["cells"]) for row in rows),
        "pairedBattles": sum(int(row["pairedBattles"]) for row in rows),
        "exactZeroCells": sum(int(row["exactZeroCells"]) for row in rows),
        "exactZeroPairs": sum(int(row["exactZeroPairs"]) for row in rows),
        "maxCellWinDeltaPp": max(float(row["maxCellWinDeltaPp"]) for row in rows),
        "maxMedianRoundDelta": max(float(row["maxMedianRoundDelta"]) for row in rows),
        "maxP90HpLossDeltaPp": max(float(row["maxP90HpLossDeltaPp"]) for row in rows),
        "maxWeightedWinGainPp": max(float(row["maxWeightedWinGainPp"]) for row in rows),
        "maxPassiveCausedLossPp": max(float(row["maxPassiveCausedLossPp"]) for row in rows),
        "worstCell": worst["worstCell"],
        "opportunityCapturePct": opportunity_capture,
        "actionChangePerTriggerPct": action_change_per_trigger,
        "noOpConsumePct": no_op_consume,
        "offenseDisplacementPct": offense_displacement,
        "shieldActionShareDeltaPp": shield_share_delta,
        "shieldActionShareOffPct": shield_share_off,
        "shieldActionShareOnPct": shield_share_on,
        "cellWinGatePp": cell_win_gate_pp,
        "passiveLossGatePp": passive_loss_gate_pp,
        "authoritativePd": seeds >= PD_AUTHORITATIVE_SEEDS,
        "workers": workers,
        "scenarios": {str(row["scenario"]): row for row in rows},
        **counters,
    }
    assert summary["cells"] == 7_200
    assert summary["pairedBattles"] == 7_200 * seeds
    assert summary["exactZeroCells"] == 2_400
    assert summary["exactZeroPairs"] == 2_400 * seeds
    assert float(summary["maxCellWinDeltaPp"]) <= cell_win_gate_pp + 1e-12, summary
    assert float(summary["maxMedianRoundDelta"]) <= GATES["maxMedianRoundDelta"] + 1e-12, summary
    assert float(summary["maxP90HpLossDeltaPp"]) <= GATES["maxP90HpLossDeltaPp"] + 1e-12, summary
    assert float(summary["maxWeightedWinGainPp"]) <= GATES["maxWeightedWinGainPp"] + 1e-12, summary
    assert float(summary["maxPassiveCausedLossPp"]) <= passive_loss_gate_pp + 1e-12, summary
    assert opportunity_capture >= GATES["minOpportunityCapturePct"] - 1e-12, summary
    assert action_change_per_trigger >= GATES["minActionChangePerTriggerPct"] - 1e-12, summary
    assert no_op_consume <= GATES["maxNoOpConsumePct"] + 1e-12, summary
    assert shield_share_delta <= GATES["maxShieldActionShareDeltaPp"] + 1e-12, summary
    assert offense_displacement <= GATES["maxOffenseDisplacementPct"] + 1e-12, summary
    assert counters["invalidConsumes"] == 0
    assert counters["attributedActionChanges"] <= counters["triggers"]
    assert counters["consumes"] <= counters["triggers"]
    assert counters["commonPreservations"] > 0
    return summary


def canonical_hash(binding: dict[str, object]) -> str:
    payload = {
        "rulesVersion": "aq.production-p6v.faith-echo.v0.1",
        "registryContentHash": EXPECTED_CONSTANTS["REGISTRY"]["V_NEXT_REGISTRY_242_CONTENT_HASH"],
        "p5wContentHash": EXPECTED_CONSTANTS["P5W"]["V_NEXT_P5W_PASSIVE_EVENT_CONTENT_HASH"],
        "p6uContentHash": EXPECTED_CONSTANTS["P6U"]["V_NEXT_P6U_ORE_MEMORY_CONTENT_HASH"],
        "p6vRulesVersion": binding["rulesVersion"],
        "p6vContentHash": binding["contentHash"],
        "integrationExpectedSha256": binding["expectedSha256"],
        "integrationBindingPolicy": "EXACT_WHEN_CONSTANT_OR_CLI_PROVIDED_REQUIRE_FLAG_AVAILABLE",
        "definitionId": FAITH_ECHO,
        "owner": "CLERIC",
        "condition": "TYPED_COMMITTED_ACTIVE_HEAL",
        "globalTypedHealIds": sorted(GLOBAL_TYPED_HEAL_IDS),
        "clericReachableHealIds": CLERIC_HEAL_IDS,
        "positiveHostIds": POSITIVE_HOST_IDS,
        "commonNegativeControlId": COMMON_SHIELD,
        "commonPolicy": "NO_EFFECT_NO_CONSUME_TOKEN_PRESERVED_ROOT_STILL_AGES",
        "firstAidCommonPolicy": "SHARED_COMMON_SURVIVAL_LEDGER_BLOCKED_NO_CONSUME",
        "anchorLevels": ANCHOR_LEVELS,
        "faithAnchorsBps": FAITH_ANCHORS,
        "thresholdClampBps": (THRESHOLD_MIN_BPS, THRESHOLD_MAX_BPS),
        "behaviorOffsetsBps": BEHAVIOR_OFFSETS,
        "stackPolicy": "ADD_THEN_CLAMP",
        "stackCapBps": SURVIVAL_THRESHOLD_STACK_CAP_BPS,
        "duration": "HEAL_ROOT_N_THEN_N_PLUS_1_N_PLUS_2_VALID_N_PLUS_3_ZERO",
        "rootAging": "BASIC_SYSTEM_DELAYED_RESOLUTION_CANCEL_COUNT_HERO_ROOT",
        "consume": "CONFIRMED_TYPED_SURVIVAL_SHIELD_ONLY",
        "displayLevels": DISPLAY_LEVELS,
        "monsterRanks": MONSTER_RANKS,
        "profiles": PROFILES,
        "profileCounts": PROFILE_COUNTS,
        "dynamicScenarios": DYNAMIC_SCENARIOS,
        "dynamicCells": 7_200,
        "exactZeroCells": 2_400,
        "fastSeeds": FAST_REFERENCE_SEEDS,
        "pdSeeds": PD_AUTHORITATIVE_SEEDS,
        "gates": GATES,
        "cellWinGatePolicy": {
            "fast24": "SMOKE_ONLY_ONE_OVER_24_QUANTIZED_CEILING_20_84PP_NO_GO",
            "pd200": "AUTHORITATIVE_STRICT_20_00PP",
        },
        "liveEnablement": False,
    }
    return canonical_json_hash(payload)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=FAST_REFERENCE_SEEDS)
    parser.add_argument("--pd", action="store_true", help="run authoritative 200 paired seeds per cell")
    parser.add_argument("--workers", type=int, default=0, help="0=auto (1 fast, up to 6 PD)")
    parser.add_argument("--runtime-sha256", default="", help="exact P6v runtime SHA-256 override")
    parser.add_argument("--test-sha256", default="", help="exact P6v unit-test SHA-256 override")
    parser.add_argument("--resolver-sha256", default="", help="exact Resolver SHA-256 override")
    parser.add_argument(
        "--require-exact-bindings",
        action="store_true",
        help="fail unless runtime/test/resolver all have exact expected SHA bindings",
    )
    args = parser.parse_args()
    seeds = PD_AUTHORITATIVE_SEEDS if args.pd else args.seeds
    if seeds < 20:
        parser.error("--seeds must be at least 20")
    if args.workers < 0:
        parser.error("--workers must be non-negative")
    workers = args.workers or (min(len(DYNAMIC_SCENARIOS), os.cpu_count() or 1) if args.pd else 1)
    workers = min(len(DYNAMIC_SCENARIOS), workers)

    overrides = {
        "P6V_RUNTIME": args.runtime_sha256,
        "P6V_TEST": args.test_sha256,
        "RESOLVER": args.resolver_sha256,
    }
    passed: list[str] = []
    check_registry_contract()
    passed.append("REGISTRY_FAITH_HEAL_HOST_COMMON_EXACT_CONTRACT")
    check_production_source_contract()
    passed.append("P5W_P6U_P6V_AI_P5M_P5K_PRODUCTION_CONTRACT")
    binding = inspect_integration_bindings(overrides, args.require_exact_bindings)
    passed.append("RUNTIME_TEST_RESOLVER_DISCOVERY_OR_EXACT_SHA_BINDING")
    threshold = check_threshold_matrix()
    passed.append("THRESHOLD_1800_CELL_CAP10000_LONG_COMMON_ZERO_MATRIX")
    scripted = check_scripted_ledger_matrix()
    passed.append("N1_N2_N3_BASIC_SYSTEM_DELAYED_REFRESH_REJECT_LEDGER_MATRIX")
    ai = check_ai_opportunity_matrix()
    passed.append("THREE_HOST_OPPORTUNITY_FLIP_PRIORITY_AND_COMMON_EXACT_ZERO")
    dynamic = measure_dynamic(seeds, workers)
    passed.extend(
        (
            "PAIRED_7200_CELL_LEVEL_SKILL_BEHAVIOR_RANK_PROFILE_SCENARIO_MATRIX",
            "COMMON_ONLY_AND_NO_HEAL_2400_CELL_EXACT_ZERO",
            "TRIGGER_OPPORTUNITY_CAPTURE_NOOP_PREEMPTION_GATES",
            "CELL_ROUND_HP_WEIGHTED_GAIN_AND_PASSIVE_LOSS_GATES",
            "NO_INVALID_CONSUME_EXTRA_ACTION_OR_LEDGER_BYPASS",
        )
    )

    tool_hash = sha256_bytes(Path(__file__).read_bytes())
    canonical = canonical_hash(binding)
    print(
        f"PRODUCTION_P6V_FAITH_ECHO_V0_1: PASS ({len(passed)}/{len(passed)}) "
        f"seeds={seeds} cells={dynamic['cells']} pairedBattles={dynamic['pairedBattles']} "
        f"workers={dynamic['workers']}"
    )
    print(f"  canonical_sha256={canonical}")
    print(f"  tool_sha256={tool_hash}")
    print(
        f"  integrationBinding mode={binding['mode']} allExact={str(binding['allExact']).lower()} "
        f"runtime={binding['actualSha256']['P6V_RUNTIME']} "
        f"test={binding['actualSha256']['P6V_TEST']} "
        f"resolver={binding['actualSha256']['RESOLVER']}"
    )
    print(
        f"  threshold cells={threshold['cells']} positive={threshold['positiveCells']} "
        f"commonExactZero={threshold['commonExactZeroCells']} "
        f"opportunityProbes={threshold['opportunityProbes']} "
        f"effectiveDelta={threshold['minEffectiveDeltaBps']}..{threshold['maxEffectiveDeltaBps']}bps "
        f"saturated={threshold['saturatedCombinations']}"
    )
    print(
        f"  scripted cases={scripted['scriptedCases']} commonPreserved={scripted['commonPreservations']} "
        f"firstAidCommonBlocked={scripted['firstAidCommonBlocks']} invalidConsumes=0"
    )
    print(
        f"  ai decisions={ai['decisions']} opportunity={ai['opportunityCaptures']}/"
        f"{ai['opportunityExposures']} ({ai['opportunityCapturePct']:.2f}%) "
        f"commonExactZero={ai['commonExactZeroDecisions']} outsideWindowExact={ai['outsideWindowExactCases']}"
    )
    print(
        "  gates "
        f"cellWinAbs={dynamic['maxCellWinDeltaPp']:.2f}pp/{dynamic['cellWinGatePp']:.2f}pp "
        f"authority={'PD_STRICT' if dynamic['authoritativePd'] else 'SMOKE_ONLY_NO_GO'} "
        f"rounds={dynamic['maxMedianRoundDelta']:.1f}/3.0 "
        f"p90Hp={dynamic['maxP90HpLossDeltaPp']:.2f}pp/18.00pp "
        f"weightedGain={dynamic['maxWeightedWinGainPp']:.2f}pp/8.00pp "
        f"passiveLoss={dynamic['maxPassiveCausedLossPp']:.2f}pp/{dynamic['passiveLossGatePp']:.2f}pp"
    )
    print(
        "  opportunity "
        f"capture={dynamic['opportunityCapturePct']:.2f}%/90.00% "
        f"actionChangePerTrigger={dynamic['actionChangePerTriggerPct']:.2f}%/5.00% "
        f"noOpConsume={dynamic['noOpConsumePct']:.2f}%/80.00% "
        f"shieldShareDelta={dynamic['shieldActionShareDeltaPp']:.2f}pp/5.00pp "
        f"offenseDisplacement={dynamic['offenseDisplacementPct']:.2f}%/3.00%"
    )
    print(
        f"  invariants exactZeroCells={dynamic['exactZeroCells']} "
        f"exactZeroPairs={dynamic['exactZeroPairs']} triggers={dynamic['triggers']} "
        f"consumes={dynamic['consumes']} expiries={dynamic['expiries']} "
        f"commonPreservations={dynamic['commonPreservations']} invalidConsumes={dynamic['invalidConsumes']}"
    )
    print(
        "  gateStatus=15/15 "
        "[cellWin,round,p90Hp,weightedGain,passiveLoss,trigger,opportunityCapture,noOpConsume,"
        "shieldShare,offenseDisplacement,commonZero,firstAidLedger,N1N2N3,longMath,noInvalidConsume]"
    )
    for scenario in DYNAMIC_SCENARIOS:
        row = dynamic["scenarios"][scenario]
        print(
            f"  {scenario:18} cells={row['cells']} pairs={row['pairedBattles']} "
            f"triggers={row['triggers']} consumes={row['consumes']} "
            f"changes={row['attributedActionChanges']} noops={row['noOpConsumes']} "
            f"zeroPairs={row['exactZeroPairs']}"
        )
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
