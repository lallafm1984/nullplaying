#!/usr/bin/env python3
"""P6t balance/PD overlay for the Elemental Residue passive.

This is a design-only review harness.  It binds the frozen Registry/P5g/P5h/P6b
contracts, mirrors the current old-token-before-new-token ordering, exhaustively
checks the edge-case state machine, and runs paired ON/OFF battles.  It does not
enable live content or mutate Kotlin, saves, Room, items, or skill definitions.
"""

from __future__ import annotations

import argparse
import base64
from concurrent.futures import ProcessPoolExecutor
import gzip
import hashlib
import json
import math
import os
import re
import statistics
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import base_combat_six_classes_v1_5_review as six
import skill_wave1_active5_passive3_v0_1_review as wave1


ROOT = Path(__file__).resolve().parents[1]
REGISTRY_RESOURCE = (
    ROOT
    / "game-engine/src/main/resources/com/alarmquest/engine/vnext/skill_registry_242_v0_3.json.gz.b64"
)

SOURCE_PATHS = {
    "REGISTRY": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/registry/VNextSkillRegistry242.kt",
    "P5G": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusMutationLedgerP5g.kt",
    "P5H": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextStatusMutationSemanticAdapterP5h.kt",
    "STATUS_CONSUMPTION": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextStatusConsumptionHandlers.kt",
    "P6B": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBeforeOutgoingPacketRuntimeP6b.kt",
    "P6T": ROOT / "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextElementalResidueRuntimeP6t.kt",
}

# Exact source snapshots make this overlay fail closed when semantics drift while
# the public content hashes remain unchanged.
EXPECTED_SOURCE_SHA256 = {
    "REGISTRY": "75d7cf39f8d79d47791b4688730d7decf791d557f0209589e701ae436f2ccf0f",
    "P5G": "982756338bc749e401639b1f27be3ac7d6e90507fba8930e9d11cd02bb375e1c",
    "P5H": "7d0d03d23e9c3663af00a655eca3c325ac0519c14fc080c76da5ff5d027aee15",
    "STATUS_CONSUMPTION": "780d5e59cd70cba4747934749f12acf021e139e890b5eb742ad7de3fdee3565a",
    "P6B": "4f0ce36dae9dff7c7669dab0c216947d9e6419e927eadaf8faf7cfa6681167be",
    "P6T": "0ae43a776e2125f261189049a7217550fd7ab79947a5e6784fda5e369f801d94",
}

EXPECTED_CONSTANTS = {
    "REGISTRY": {
        "V_NEXT_REGISTRY_242_RULES_VERSION": "aq.skill-registry.v0.3",
        "V_NEXT_REGISTRY_242_CONTENT_HASH": "cda98479494d5d2b3d1279e695c26f923ce46249c8835e3e76996fccd67fbdca",
    },
    "P5G": {
        "V_NEXT_P5G_STATUS_MUTATION_RULES_VERSION": "aq.status-mutation-ledger.p5g.v0.2",
        "V_NEXT_P5G_STATUS_MUTATION_CONTENT_HASH": "35aa4a37e443530a2c9efb798ae0ad581377fc6483fd63e564c9858cf626a525",
    },
    "P5H": {
        "V_NEXT_P5H_STATUS_MUTATION_ADAPTER_RULES_VERSION": "aq.status-mutation-semantic-adapter.p5h.v0.2",
        "V_NEXT_P5H_STATUS_MUTATION_ADAPTER_CONTENT_HASH": "c0a6d5e0a1bd119eae5e73276641e147dea5af9169cec3dae2a1c0fea30a69ff",
    },
    "STATUS_CONSUMPTION": {
        "V_NEXT_STATUS_CONSUMPTION_RULES_VERSION": "aq.skill-status-consumption.v0.5",
        "V_NEXT_STATUS_CONSUMPTION_CONTENT_HASH": "b3281e14cd88b6ada0aab97d2196229cfe8034fc7c16727a0ee2bc74aaecb48e",
    },
    "P6B": {
        "V_NEXT_P6B_OUTGOING_RULES_VERSION": "aq.before-outgoing-packet.p6b.v0.2",
        "V_NEXT_P6B_OUTGOING_CONTENT_HASH": "493f02ffe0f0acb27d0dfc1203f996194bc623f862bb4e4cf3129f90d40f53a8",
    },
    "P6T": {
        "V_NEXT_P6T_ELEMENTAL_RESIDUE_RULES_VERSION": "aq.elemental-residue.p6t.v0.1",
        "V_NEXT_P6T_ELEMENTAL_RESIDUE_CONTENT_HASH": "d72837046be0bf48b5375ef97619551f3c549a4c75f07988dc77359c68675abf",
    },
}

EXPECTED_WAVE1_CANONICAL_SHA256 = "02246661576f1b664c902e80a09f6b39b7789b5bd2751b0da5e3c416abd0ebc3"
ELEMENTAL_RESIDUE_ID = "aq.skill.common.w8.elementalresidue"
DRAGON_SCALE = "aq.skill.external.w2.dragonscale"
GLASS_DESERT_HEAT = "aq.skill.world.w8.glassdesertheat"
BLEEDING_CUT = "aq.skill.common.v06.bleedingcut"
PRECISE_STRIKE = "aq.skill.common.w2.precisestrike"
PASSIVE_LOADOUT = (
    "aq.skill.boss.w7.dragonpride",
    "aq.skill.boss.w8.undyingobsession",
    ELEMENTAL_RESIDUE_ID,
)

ANCHOR_LEVELS = (1, 25, 50, 75, 100)
RESIDUE_ANCHORS = (100, 200, 300, 400, 500)
DISPLAY_LEVELS = (58, 9_999)
CLASSES = tuple(six.CLASSES)
CLASS_LOADOUTS = {
    "WARRIOR": {
        "actives": (DRAGON_SCALE, GLASS_DESERT_HEAT, "aq.skill.warrior.w4.bloodwhirl", BLEEDING_CUT, PRECISE_STRIKE),
        "passives": PASSIVE_LOADOUT,
    },
    "ROGUE": {
        "actives": (DRAGON_SCALE, GLASS_DESERT_HEAT, "aq.skill.rogue.w2.twinknifeflurry", BLEEDING_CUT, PRECISE_STRIKE),
        "passives": PASSIVE_LOADOUT,
    },
    "RANGER": {
        "actives": (DRAGON_SCALE, GLASS_DESERT_HEAT, "aq.skill.ranger.w2.rapidfire", "aq.skill.ranger.w4.stormpierce", PRECISE_STRIKE),
        "passives": PASSIVE_LOADOUT,
    },
    "MAGE": {
        "actives": (DRAGON_SCALE, GLASS_DESERT_HEAT, "aq.skill.mage.w4.chainlightning", "aq.skill.mage.w4.unseal", PRECISE_STRIKE),
        "passives": PASSIVE_LOADOUT,
    },
    "CLERIC": {
        "actives": (DRAGON_SCALE, GLASS_DESERT_HEAT, "aq.skill.cleric.w2.sacredchain", BLEEDING_CUT, PRECISE_STRIKE),
        "passives": PASSIVE_LOADOUT,
    },
    "PALADIN": {
        "actives": (DRAGON_SCALE, GLASS_DESERT_HEAT, "aq.skill.paladin.w2.resolveburst", BLEEDING_CUT, PRECISE_STRIKE),
        "passives": PASSIVE_LOADOUT,
    },
}
CLASS_ARM_HOST = {
    "WARRIOR": ("aq.skill.warrior.w4.bloodwhirl", "PHYSICAL", ("BLEED",)),
    "ROGUE": ("aq.skill.rogue.w2.twinknifeflurry", "PHYSICAL", ("POISON",)),
    "RANGER": (DRAGON_SCALE, "FIRE", ("BURN",)),
    "MAGE": (DRAGON_SCALE, "FIRE", ("BURN",)),
    "CLERIC": (DRAGON_SCALE, "FIRE", ("BURN",)),
    "PALADIN": (DRAGON_SCALE, "FIRE", ("BURN",)),
}
CLASS_DIFFERENT_HOST = {
    "WARRIOR": (DRAGON_SCALE, "FIRE"),
    "ROGUE": (DRAGON_SCALE, "FIRE"),
    "RANGER": ("aq.skill.ranger.w4.stormpierce", "LIGHTNING"),
    "MAGE": ("aq.skill.mage.w4.chainlightning", "LIGHTNING"),
    "CLERIC": ("aq.skill.cleric.w2.sacredchain", "HOLY"),
    "PALADIN": ("aq.skill.paladin.w2.resolveburst", "HOLY"),
}
CLASS_PHYSICAL_HOST = {
    "WARRIOR": ("aq.skill.warrior.w4.bloodwhirl", True),
    "ROGUE": ("aq.skill.rogue.w2.twinknifeflurry", True),
    "RANGER": ("aq.skill.ranger.w2.rapidfire", True),
    "MAGE": (PRECISE_STRIKE, False),
    "CLERIC": (PRECISE_STRIKE, False),
    "PALADIN": (PRECISE_STRIKE, False),
}
P5H_RUNTIME_HOST_ELEMENTS = {
    DRAGON_SCALE: "FIRE",
    GLASS_DESERT_HEAT: "FIRE",
    "aq.skill.warrior.w4.bloodwhirl": "PHYSICAL",
    "aq.skill.rogue.w2.twinknifeflurry": "PHYSICAL",
    "aq.skill.ranger.w2.rapidfire": "PHYSICAL",
    "aq.skill.ranger.w4.stormpierce": "LIGHTNING",
    "aq.skill.mage.w4.chainlightning": "LIGHTNING",
    "aq.skill.mage.w4.unseal": "ARCANE",
    "aq.skill.cleric.w2.sacredchain": "HOLY",
    "aq.skill.paladin.w2.resolveburst": "HOLY",
}
NO_EFFECT_CLASSES: tuple[str, ...] = ()
BEHAVIORS = tuple(wave1.legacy.c1.BEHAVIOR_MODES)
PROFILES = tuple(wave1.legacy.c1.PROFILES)
PROFILE_WEIGHTS = dict(wave1.legacy.c1.PROFILE_WEIGHTS)
MONSTER_RANKS = ("NORMAL", "BOSS")
ELEMENT_CASES = ("DIFFERENT", "SAME", "PHYSICAL", "MISS", "BARRIER", "MULTI_CONSUME")
ELEMENTAL_ATTACK_TAGS = frozenset({"FIRE", "ICE", "LIGHTNING", "HOLY", "ARCANE"})
ACTION_ADD_CAP_BPS = 1_500

GATES = {
    "maxCellWinDeltaPp": 20.0,
    "maxMedianRoundDelta": 3.0,
    "maxP90HpLossDeltaPp": 18.0,
    "maxWeightedWinLossPp": 8.0,
    "maxPassiveWeightedWinLossPp": 1.0,
}


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def load_registry() -> dict:
    encoded = "".join(REGISTRY_RESOURCE.read_text(encoding="utf-8").split())
    return json.loads(gzip.decompress(base64.b64decode(encoded)))


def canonical_json_hash(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return sha256_bytes(encoded)


def extract_string_constant(source: str, name: str) -> str:
    match = re.search(rf"\b{re.escape(name)}(?:\s*:\s*String)?\s*=\s*\"([^\"]+)\"", source)
    if match is None:
        raise AssertionError(f"missing Kotlin constant: {name}")
    return match.group(1)


def value_at(values: tuple[int, ...] | list[int], skill_level: int) -> int:
    if len(values) != len(ANCHOR_LEVELS):
        raise AssertionError("five anchor values required")
    safe = max(1, min(100, int(skill_level)))
    for index, right_level in enumerate(ANCHOR_LEVELS):
        if safe == right_level:
            return int(values[index])
        if safe < right_level:
            left_level = ANCHOR_LEVELS[index - 1]
            ratio = Decimal(safe - left_level) / Decimal(right_level - left_level)
            resolved = Decimal(values[index - 1]) + Decimal(values[index] - values[index - 1]) * ratio
            return int(resolved.quantize(Decimal("1"), rounding=ROUND_HALF_UP))
    return int(values[-1])


def registry_definition(registry: dict, kind: str, definition_id: str) -> dict:
    matches = [row for row in registry[kind] if row["definitionId"] == definition_id]
    if len(matches) != 1:
        raise AssertionError((kind, definition_id, len(matches)))
    return matches[0]


@dataclass
class ResidueState:
    token: str | None = None
    initial_tokens: int = 0
    armed: int = 0
    consumed: int = 0
    replaced: int = 0

    @classmethod
    def with_token(cls, token: str) -> "ResidueState":
        return cls(token=token, initial_tokens=1)

    def assert_conserved(self) -> None:
        live = int(self.token is not None)
        expected = self.initial_tokens + self.armed - self.consumed - self.replaced
        if live != expected or live not in (0, 1) or self.consumed > self.initial_tokens + self.armed:
            raise AssertionError(
                f"token conservation failed: live={live} expected={expected} "
                f"initial={self.initial_tokens} armed={self.armed} consumed={self.consumed} replaced={self.replaced}"
            )


@dataclass(frozen=True)
class ResidueResolution:
    residue_add_bps: int
    residue_applied_bps: int
    combined_action_add_bps: int
    token_before: str | None
    token_after: str | None
    extra_actions: int = 0
    resource_delta_bps: int = 0


def element_for_status(tag: str) -> str:
    return {"BURN": "FIRE", "CHILL": "ICE", "SHOCK": "LIGHTNING"}.get(tag, "NONE")


def expected_residue_postcondition(
    *,
    token_before: str | None,
    passive_equipped: bool,
    hostile_consume_committed: bool,
    primary_consumed_tag: str = "",
    consumes_existing_residue: bool = False,
) -> str | None:
    if passive_equipped and hostile_consume_committed:
        return element_for_status(primary_consumed_tag)
    if consumes_existing_residue:
        return None
    return token_before


def resolve_residue(
    state: ResidueState,
    *,
    equipped: bool,
    anchor_bps: int,
    host_element: str,
    hit: bool,
    consumed_hostile_tags: tuple[str, ...] = (),
    barrier_blocks_all_damage: bool = False,
    other_action_add_bps: int = 0,
) -> ResidueResolution:
    """Mirror P5g ordering: spend an old legal token, then arm from a landed consume."""
    token_before = state.token
    residue_add = 0
    if (
        equipped
        and state.token is not None
        and host_element in ELEMENTAL_ATTACK_TAGS
        and host_element != state.token
    ):
        residue_add = anchor_bps
        state.token = None
        state.consumed += 1

    # Barrier changes dealt damage, not hit/consumption commitment.
    _ = barrier_blocks_all_damage
    if equipped and hit and consumed_hostile_tags:
        if state.token is not None:
            state.replaced += 1
        state.token = element_for_status(consumed_hostile_tags[0])
        state.armed += 1

    state.assert_conserved()
    action_before = max(0, min(ACTION_ADD_CAP_BPS, other_action_add_bps))
    combined = max(0, min(ACTION_ADD_CAP_BPS, action_before + residue_add))
    return ResidueResolution(
        residue_add_bps=residue_add,
        residue_applied_bps=combined - action_before,
        combined_action_add_bps=combined,
        token_before=token_before,
        token_after=state.token,
    )


def check_registry_and_loadout() -> None:
    registry = load_registry()
    assert canonical_json_hash(registry) == EXPECTED_CONSTANTS["REGISTRY"]["V_NEXT_REGISTRY_242_CONTENT_HASH"]
    assert registry["rulesVersion"] == "aq.skill-registry.v0.3"
    assert registry["anchors"] == list(ANCHOR_LEVELS)
    assert registry["slots"] == {"active": 5, "passive": 3}
    assert registry["caps"]["passiveActionAddBps"] == ACTION_ADD_CAP_BPS
    assert registry["manualCombatActions"] is False
    assert registry["equipmentGrantedSkillFieldCount"] == 0

    expected_residue = {
        "anchorValues": list(RESIDUE_ANCHORS),
        "conditionId": "after_consuming_hostile_status",
        "definitionId": ELEMENTAL_RESIDUE_ID,
        "designWave": 8,
        "equipmentGrantedSkillIds": [],
        "fixedTradeoff": "다음 다른 원소1회·같은 원소0·상태/행동 추가 없음",
        "growthField": "different_element_attack_add_bps",
        "hostScope": "SELF_OR_HOST_ACTION",
        "ideaId": "COM047",
        "legacyAdapterId": "",
        "nameKo": "원소 잔재",
        "ownerScope": "ALL",
        "pattern": "CONSUME_TO_DIFFERENT_ELEMENT",
        "provisional": False,
        "roles": ["ATTACK"],
        "source": "COMMON",
        "stackCapBps": ACTION_ADD_CAP_BPS,
        "stackGroup": "aq.stack.passive.action_add",
        "stackPolicy": "ADD_THEN_CLAMP",
    }
    assert registry_definition(registry, "passives", ELEMENTAL_RESIDUE_ID) == expected_residue
    assert len(PASSIVE_LOADOUT) == 3 and len(set(PASSIVE_LOADOUT)) == 3
    assert all(registry_definition(registry, "passives", definition_id) for definition_id in PASSIVE_LOADOUT)
    assert all(
        registry_definition(registry, "passives", definition_id)["stackGroup"] == "aq.stack.passive.action_add"
        for definition_id in PASSIVE_LOADOUT
    )
    for class_name, loadout in CLASS_LOADOUTS.items():
        assert len(loadout["actives"]) == 5 and len(set(loadout["actives"])) == 5
        assert len(loadout["passives"]) == 3 and len(set(loadout["passives"])) == 3
        assert ELEMENTAL_RESIDUE_ID in loadout["passives"]
        assert all(
            registry_definition(registry, "actives", definition_id)["ownerScope"] in {class_name, "ALL"}
            for definition_id in loadout["actives"]
        )
        assert all(
            registry_definition(registry, "passives", definition_id)["ownerScope"] in {class_name, "ALL"}
            for definition_id in loadout["passives"]
        )
        arm_id, arm_element, hostile_tags = CLASS_ARM_HOST[class_name]
        consume_id, consume_element = CLASS_DIFFERENT_HOST[class_name]
        assert arm_id in loadout["actives"] and consume_id in loadout["actives"]
        assert P5H_RUNTIME_HOST_ELEMENTS[arm_id] == arm_element
        assert P5H_RUNTIME_HOST_ELEMENTS[consume_id] == consume_element
        assert hostile_tags and consume_element in ELEMENTAL_ATTACK_TAGS
        assert consume_element != element_for_status(hostile_tags[0])
    assert NO_EFFECT_CLASSES == ()


def check_exact_source_bindings() -> None:
    for source_name, path in SOURCE_PATHS.items():
        raw = path.read_bytes()
        assert sha256_bytes(raw) == EXPECTED_SOURCE_SHA256[source_name], source_name
        source = raw.decode("utf-8")
        for constant, expected in EXPECTED_CONSTANTS[source_name].items():
            assert extract_string_constant(source, constant) == expected, (source_name, constant)

    status_source = SOURCE_PATHS["STATUS_CONSUMPTION"].read_text(encoding="utf-8")
    p5g_source = SOURCE_PATHS["P5G"].read_text(encoding="utf-8")
    p5h_source = SOURCE_PATHS["P5H"].read_text(encoding="utf-8")
    p6b_source = SOURCE_PATHS["P6B"].read_text(encoding="utf-8")
    p6t_source = SOURCE_PATHS["P6T"].read_text(encoding="utf-8")
    assert 'listOf(100, 200, 300, 400, 500), "aq.stack.passive.action_add", 1_500' in status_source
    assert "An old residue token applies before a newly consumed status arms its successor." in status_source
    assert "spec.element in elementalAttackTags && spec.element != oldResidue" in status_source
    assert "elementForStatus(hostile.first().tag)" in status_source
    assert "extraActions = 0" in status_source
    for definition_id, element in P5H_RUNTIME_HOST_ELEMENTS.items():
        matching_lines = [line for line in status_source.splitlines() if f'active("{definition_id}"' in line]
        assert len(matching_lines) == 1, definition_id
        assert f'element = "{element}"' in matching_lines[0], (definition_id, element)
        assert definition_id in p5h_source
    assert 'state.elementalResidue in setOf(null, "NONE", "FIRE", "ICE", "LIGHTNING")' in p5g_source
    assert "request.equippedPassiveLevels.size <= 3" in p5g_source
    assert "preview.coefficientBps + preview.residueAddBps" not in p5h_source
    assert "totalCoefficientBps = preview.coefficientBps" in p5h_source
    assert "V_NEXT_P5H_STATUS_MUTATION_PASSIVE_COUNT: Int = 1" in p5h_source
    assert "actionValues.sum().coerceAtMost(1_500)" in p6b_source
    assert "magicValues.sum().coerceAtMost(1_500)" in p6b_source
    assert "actionCoefficientBeforeResidueBps = actionCoefficient" in p6b_source
    assert "actionCoefficientAddBps = actionCoefficient" in p6b_source
    assert "internal fun P6bOutgoingPacketModifier.withP6t" in p6t_source
    assert "V_NEXT_P6T_ACTION_ADD_CAP_BPS = 1_500" in p6t_source
    assert "(V_NEXT_P6T_ACTION_ADD_CAP_BPS - actionBefore).coerceAtLeast(0)" in p6t_source
    assert "actionCoefficientAddBps = actionAfter" in p6t_source
    assert "coefficientAddBps = actionAfter + magicCoefficientAddBps + bossCoefficientAddBps" in p6t_source
    assert "elementalResiduePlanHash.isBlank()" in p6t_source
    assert '"P6T_ALREADY_ATTACHED"' in p6t_source
    assert "listOfNotNull(ELEMENTAL_RESIDUE.takeIf { applied > 0 })" in p6t_source
    assert (
        "residueAppliedCount = ledger.residueAppliedCount + "
        "if (p5hReceipt.residueAppliedBps > 0) 1 else 0"
    ) in p6t_source
    assert "val hostileArmTriggered = passivePlan != null && receipt.hostileConsumeCommitted" in p6t_source
    assert "val expectedResidueAfter = when" in p6t_source
    assert "hostileArmTriggered -> residueElementForStatusP6t(receipt.primaryConsumedTag)" in p6t_source
    assert "plan.consumesExistingResidue -> null" in p6t_source
    assert "else -> ledger.residueElement" in p6t_source
    assert 'return reject("TOKEN_POSTCONDITION_MISMATCH")' in p6t_source
    assert "ACTION_ADD_COMBINED_THEN_CLAMP1500" in p6t_source
    assert wave1.canonical_hash() == EXPECTED_WAVE1_CANONICAL_SHA256


def check_anchor_interpolation() -> None:
    assert tuple(value_at(RESIDUE_ANCHORS, level) for level in ANCHOR_LEVELS) == RESIDUE_ANCHORS
    values = tuple(value_at(RESIDUE_ANCHORS, level) for level in range(1, 101))
    assert values[0] == 100 and values[-1] == 500
    assert all(left <= right for left, right in zip(values, values[1:]))
    assert value_at(RESIDUE_ANCHORS, 58) == 332
    assert value_at(RESIDUE_ANCHORS, 9_999) == 500


def check_branch_matrix() -> None:
    state = ResidueState()
    arm = resolve_residue(
        state, equipped=True, anchor_bps=500, host_element="FIRE", hit=True,
        consumed_hostile_tags=("BURN",),
    )
    assert arm.residue_add_bps == 0 and state.token == "FIRE"
    different = resolve_residue(
        state, equipped=True, anchor_bps=500, host_element="ICE", hit=True,
    )
    assert different.residue_add_bps == 500 and state.token is None

    same_state = ResidueState.with_token("FIRE")
    same = resolve_residue(same_state, equipped=True, anchor_bps=500, host_element="FIRE", hit=True)
    assert same.residue_add_bps == 0 and same_state.token == "FIRE"

    physical_state = ResidueState.with_token("FIRE")
    physical = resolve_residue(
        physical_state, equipped=True, anchor_bps=500, host_element="PHYSICAL", hit=True,
    )
    assert physical.residue_add_bps == 0 and physical_state.token == "FIRE"

    physical_replace_state = ResidueState.with_token("FIRE")
    physical_replace = resolve_residue(
        physical_replace_state, equipped=True, anchor_bps=500, host_element="PHYSICAL", hit=True,
        consumed_hostile_tags=("POISON",),
    )
    assert physical_replace.residue_add_bps == 0 and physical_replace_state.token == "NONE"
    assert physical_replace_state.replaced == 1

    miss_arm_state = ResidueState()
    resolve_residue(
        miss_arm_state, equipped=True, anchor_bps=500, host_element="FIRE", hit=False,
        consumed_hostile_tags=("BURN",),
    )
    assert miss_arm_state.token is None and miss_arm_state.armed == 0

    miss_spend_state = ResidueState.with_token("FIRE")
    miss_spend = resolve_residue(
        miss_spend_state, equipped=True, anchor_bps=500, host_element="ICE", hit=False,
    )
    assert miss_spend.residue_add_bps == 500 and miss_spend_state.token is None

    barrier_state = ResidueState.with_token("FIRE")
    barrier = resolve_residue(
        barrier_state, equipped=True, anchor_bps=500, host_element="ICE", hit=True,
        consumed_hostile_tags=("CHILL",), barrier_blocks_all_damage=True,
    )
    assert barrier.residue_add_bps == 500 and barrier_state.token == "ICE"

    multi_state = ResidueState()
    multi = resolve_residue(
        multi_state, equipped=True, anchor_bps=500, host_element="FIRE", hit=True,
        consumed_hostile_tags=("BURN", "CHILL"),
    )
    assert multi.residue_add_bps == 0 and multi_state.token == "FIRE"

    unknown_state = ResidueState()
    resolve_residue(
        unknown_state, equipped=True, anchor_bps=500, host_element="PHYSICAL", hit=True,
        consumed_hostile_tags=("POISON",),
    )
    unknown = resolve_residue(
        unknown_state, equipped=True, anchor_bps=500, host_element="ARCANE", hit=True,
    )
    assert unknown.residue_add_bps == 500 and unknown_state.token is None

    replacement_state = ResidueState.with_token("FIRE")
    replacement = resolve_residue(
        replacement_state, equipped=True, anchor_bps=500, host_element="FIRE", hit=True,
        consumed_hostile_tags=("BURN",),
    )
    assert replacement.residue_add_bps == 0 and replacement_state.token == "FIRE"
    assert replacement_state.replaced == 1

    cap_cases = []
    for existing in (0, 1_200, 1_500):
        cap_state = ResidueState.with_token("FIRE")
        cap_cases.append(resolve_residue(
            cap_state, equipped=True, anchor_bps=500, host_element="ICE", hit=True,
            other_action_add_bps=existing,
        ))
    assert [row.residue_add_bps for row in cap_cases] == [500, 500, 500]
    assert [row.residue_applied_bps for row in cap_cases] == [500, 300, 0]
    assert [row.combined_action_add_bps for row in cap_cases] == [500, 1_500, 1_500]
    assert sum(row.residue_add_bps > 0 for row in cap_cases) == 3
    assert sum(row.residue_applied_bps > 0 for row in cap_cases) == 2
    assert all(row.extra_actions == 0 and row.resource_delta_bps == 0 for row in cap_cases)

    assert expected_residue_postcondition(
        token_before=None,
        passive_equipped=False,
        hostile_consume_committed=True,
        primary_consumed_tag="BURN",
    ) is None
    assert expected_residue_postcondition(
        token_before=None,
        passive_equipped=True,
        hostile_consume_committed=True,
        primary_consumed_tag="BURN",
    ) == "FIRE"
    assert expected_residue_postcondition(
        token_before="FIRE",
        passive_equipped=True,
        hostile_consume_committed=False,
        consumes_existing_residue=True,
    ) is None
    assert expected_residue_postcondition(
        token_before="ICE",
        passive_equipped=True,
        hostile_consume_committed=False,
    ) == "ICE"


STATIC_CHECKS = (
    ("REGISTRY_EXACT_RESIDUE_AND_ACTIVE5_PASSIVE3_BINDING", check_registry_and_loadout),
    ("REGISTRY_P5G_P5H_P6B_P6T_EXACT_HASH_SINGLE_CLAMP_AND_HARDENING", check_exact_source_bindings),
    ("SKILL_LEVEL_ANCHORS_AND_L58_L9999_CLAMP", check_anchor_interpolation),
    ("SAME_DIFFERENT_PHYSICAL_MISS_BARRIER_MULTI_BRANCH_MATRIX", check_branch_matrix),
)


@dataclass(frozen=True)
class CombatFixture:
    hero_max_hp: int
    monster_max_hp: int
    hero_power: int
    monster_power: int
    hero_hit_bps: int
    monster_hit_bps: int
    hero_crit_bps: int
    monster_crit_bps: int
    hero_mitigation_bps: int
    monster_mitigation_bps: int
    hero_speed: int
    monster_speed: int
    monster_coefficient_bps: int


@dataclass(frozen=True)
class ActionFixture:
    definition_id: str
    element: str
    consumed_tags: tuple[str, ...]
    p5h_owned: bool
    forced_miss: bool = False
    barrier_blocked: bool = False


@dataclass(frozen=True)
class BattleResult:
    outcome: str
    rounds: int
    hp_loss_pct: float
    residue_events: int
    residue_applied_events: int
    residue_nominal_total_bps: int
    residue_applied_total_bps: int
    hostile_consume_triggers: int
    residue_consumed: int
    hero_actions: int
    max_action_add_bps: int
    extra_actions: int
    residue_resource_delta_bps: int


@dataclass(frozen=True)
class Metrics:
    win_rate: float
    median_rounds: float
    p90_hp_loss: float


def make_combat_fixture(class_name: str, display_level: int, profile: str, monster_rank: str) -> CombatFixture:
    source = six.source_for_display(class_name, display_level, "BASE")
    hero = six.derive_hero(source).combatant
    monster = six.monster_for(source, profile, monster_rank)
    core = six.v13.core
    hero_power = hero.physical_attack if hero.attack_type == "PHYSICAL" else hero.magical_attack
    monster_power = monster.physical_attack if monster.attack_type == "PHYSICAL" else monster.magical_attack
    return CombatFixture(
        hero_max_hp=hero.max_hp,
        monster_max_hp=monster.max_hp,
        hero_power=hero_power,
        monster_power=monster_power,
        hero_hit_bps=core.hit_bps(hero, monster),
        monster_hit_bps=core.hit_bps(monster, hero),
        hero_crit_bps=core.critical_bps(hero, monster),
        monster_crit_bps=core.critical_bps(monster, hero),
        hero_mitigation_bps=core.mitigation_bps(monster, hero),
        monster_mitigation_bps=core.mitigation_bps(hero, monster),
        hero_speed=hero.speed,
        monster_speed=monster.speed,
        monster_coefficient_bps=monster.action_coefficient_bps,
    )


def action_for(class_name: str, element_case: str, action_index: int) -> ActionFixture:
    # One actual P5h consume/follow-up pair inside a five-action auto-battle
    # window.  Other roots are admitted non-P5h physical actions, which cannot
    # spend or arm the production residue token.
    slot = action_index % 5
    arm_id, arm_element, hostile_tags = CLASS_ARM_HOST[class_name]
    consume_id, consume_element = CLASS_DIFFERENT_HOST[class_name]
    arm = ActionFixture(arm_id, arm_element, hostile_tags, True)
    consume = ActionFixture(consume_id, consume_element, (), True)

    if element_case == "MULTI_CONSUME":
        if slot == 0:
            return ActionFixture(GLASS_DESERT_HEAT, "FIRE", ("BURN", "CHILL"), True)
        if class_name in {"WARRIOR", "ROGUE"}:
            if slot == 1:
                return arm  # FIRE token is replaced by the physical consumer's NONE token.
            if slot == 2:
                return consume  # ALL FIRE then legally consumes NONE.
        elif slot == 1:
            return consume
        return ActionFixture(PRECISE_STRIKE, "PHYSICAL", (), False)

    consumer = slot == 0
    follow_up = slot == 1
    if not consumer and not follow_up:
        return ActionFixture(PRECISE_STRIKE, "PHYSICAL", (), False)
    if element_case == "DIFFERENT":
        return arm if consumer else consume
    if element_case == "SAME":
        return (
            ActionFixture(DRAGON_SCALE, "FIRE", ("BURN",), True)
            if consumer
            else ActionFixture(GLASS_DESERT_HEAT, "FIRE", (), True)
        )
    if element_case == "PHYSICAL":
        physical_id, p5h_owned = CLASS_PHYSICAL_HOST[class_name]
        return (
            ActionFixture(DRAGON_SCALE, "FIRE", ("BURN",), True)
            if consumer
            else ActionFixture(physical_id, "PHYSICAL", (), p5h_owned)
        )
    if element_case == "MISS":
        return (
            ActionFixture(arm_id, arm_element, hostile_tags, True, forced_miss=True)
            if consumer
            else consume
        )
    if element_case == "BARRIER":
        return (
            ActionFixture(arm_id, arm_element, hostile_tags, True, barrier_blocked=True)
            if consumer
            else consume
        )
    raise AssertionError(element_case)


ACTION_SEQUENCES = {
    (class_name, element_case): tuple(
        action_for(class_name, element_case, action_index) for action_index in range(5)
    )
    for class_name in CLASSES
    for element_case in ELEMENT_CASES
}


MASK_64 = (1 << 64) - 1


def mix64(value: int) -> int:
    value = (value + 0x9E3779B97F4A7C15) & MASK_64
    value = ((value ^ (value >> 30)) * 0xBF58476D1CE4E5B9) & MASK_64
    value = ((value ^ (value >> 27)) * 0x94D049BB133111EB) & MASK_64
    return value ^ (value >> 31)


def keyed_roll(seed_key: int, round_index: int, side: int, channel: int, stop: int) -> int:
    key = seed_key ^ (round_index * 0xD6E8FEB86659FD93) ^ (side * 0xA5A3564E27F8862B)
    key ^= channel * 0x9E3779B185EBCA87
    return mix64(key & MASK_64) % stop


def rounded_bps(value: int, bps: int) -> int:
    return max(0, math.floor(value * bps / 10_000.0 + 0.5))


def packet_damage(
    power: int,
    coefficient_bps: int,
    mitigation_bps: int,
    hit_bps: int,
    crit_bps: int,
    hit_roll: int,
    crit_roll: int,
    variance_bps: int,
    *,
    forced_miss: bool = False,
    barrier_blocked: bool = False,
) -> tuple[int, bool]:
    hit = not forced_miss and hit_roll < hit_bps
    if not hit:
        return 0, False
    damage = rounded_bps(power, coefficient_bps)
    damage = rounded_bps(damage, 15_000 if crit_roll < crit_bps else 10_000)
    damage = rounded_bps(damage, variance_bps)
    damage = max(1, rounded_bps(damage, 10_000 - mitigation_bps))
    return (0 if barrier_blocked else damage), True


def active_coefficient_bps(registry: dict, definition_id: str, skill_level: int) -> int:
    definition = registry_definition(registry, "actives", definition_id)
    return value_at(definition["attackEquivalentValues"], skill_level)


def other_action_add_bps(registry: dict, skill_level: int, behavior: str) -> int:
    dragon = value_at(
        registry_definition(registry, "passives", PASSIVE_LOADOUT[0])["anchorValues"], skill_level
    )
    undying = value_at(
        registry_definition(registry, "passives", PASSIVE_LOADOUT[1])["anchorValues"], skill_level
    )
    curse_stacks = {"CAUTIOUS": 0, "BALANCED": 1, "BOLD": 3}[behavior]
    return min(ACTION_ADD_CAP_BPS, dragon + undying * curse_stacks)


def simulate_battle(
    fixture: CombatFixture,
    *,
    class_name: str,
    active_coefficients: dict[str, int],
    residue_anchor: int,
    other_add: int,
    behavior: str,
    element_case: str,
    seed_key: int,
    residue_equipped: bool,
) -> BattleResult:
    hero_hp = fixture.hero_max_hp
    monster_hp = fixture.monster_max_hp
    residue = ResidueState()
    behavior_coefficient = {"CAUTIOUS": -300, "BALANCED": 0, "BOLD": 300}[behavior]
    hero_actions = 0
    residue_events = 0
    residue_applied_events = 0
    residue_nominal_total = 0
    residue_applied_total = 0
    max_action_add = 0
    extra_actions = 0
    residue_resource_delta = 0

    tie_hero = keyed_roll(seed_key, 0, 0, 0, 2) == 0
    hero_first = fixture.hero_speed > fixture.monster_speed or (
        fixture.hero_speed == fixture.monster_speed and tie_hero
    )
    order = (0, 1) if hero_first else (1, 0)

    for round_index in range(1, 31):
        for side in order:
            if hero_hp <= 0 or monster_hp <= 0:
                break
            if side == 0:
                action = ACTION_SEQUENCES[(class_name, element_case)][hero_actions % 5]
                base_coefficient = active_coefficients[action.definition_id]
                hit_roll = keyed_roll(seed_key, round_index, 0, 1, 10_000)
                crit_roll = keyed_roll(seed_key, round_index, 0, 2, 10_000)
                variance = 9_500 + keyed_roll(seed_key, round_index, 0, 3, 1_001)
                landed = not action.forced_miss and hit_roll < fixture.hero_hit_bps
                if residue_equipped and action.p5h_owned:
                    resolution = resolve_residue(
                        residue,
                        equipped=True,
                        anchor_bps=residue_anchor,
                        host_element=action.element,
                        hit=landed,
                        consumed_hostile_tags=action.consumed_tags,
                        barrier_blocks_all_damage=action.barrier_blocked,
                        other_action_add_bps=other_add,
                    )
                    combined_action_add = resolution.combined_action_add_bps
                    residue_add = resolution.residue_add_bps
                    residue_applied = resolution.residue_applied_bps
                else:
                    combined_action_add = other_add
                    residue_add = 0
                    residue_applied = 0
                final_coefficient = max(
                    1,
                    base_coefficient + behavior_coefficient + combined_action_add,
                )
                damage, landed_again = packet_damage(
                    fixture.hero_power,
                    final_coefficient,
                    fixture.monster_mitigation_bps,
                    fixture.hero_hit_bps,
                    fixture.hero_crit_bps,
                    hit_roll,
                    crit_roll,
                    variance,
                    forced_miss=action.forced_miss,
                    barrier_blocked=action.barrier_blocked,
                )
                assert landed_again == landed
                monster_hp = max(0, monster_hp - damage)
                hero_actions += 1
                residue_events += int(residue_add > 0)
                residue_applied_events += int(residue_applied > 0)
                residue_nominal_total += residue_add
                residue_applied_total += residue_applied
                max_action_add = max(max_action_add, combined_action_add)
            else:
                hit_roll = keyed_roll(seed_key, round_index, 1, 1, 10_000)
                crit_roll = keyed_roll(seed_key, round_index, 1, 2, 10_000)
                variance = 9_500 + keyed_roll(seed_key, round_index, 1, 3, 1_001)
                damage, _ = packet_damage(
                    fixture.monster_power,
                    fixture.monster_coefficient_bps,
                    fixture.hero_mitigation_bps,
                    fixture.monster_hit_bps,
                    fixture.monster_crit_bps,
                    hit_roll,
                    crit_roll,
                    variance,
                )
                hero_hp = max(0, hero_hp - damage)

        if monster_hp <= 0:
            outcome = "WIN"
            break
        if hero_hp <= 0:
            outcome = "LOSS"
            break
    else:
        round_index = 30
        outcome = "RETREAT"

    residue.assert_conserved()
    return BattleResult(
        outcome=outcome,
        rounds=round_index,
        hp_loss_pct=(fixture.hero_max_hp - hero_hp) * 100.0 / fixture.hero_max_hp,
        residue_events=residue_events,
        residue_applied_events=residue_applied_events,
        residue_nominal_total_bps=residue_nominal_total,
        residue_applied_total_bps=residue_applied_total,
        hostile_consume_triggers=residue.armed,
        residue_consumed=residue.consumed,
        hero_actions=hero_actions,
        max_action_add_bps=max_action_add,
        extra_actions=extra_actions,
        residue_resource_delta_bps=residue_resource_delta,
    )


def percentile_nearest_rank(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def metrics(results: list[BattleResult]) -> Metrics:
    return Metrics(
        win_rate=sum(result.outcome == "WIN" for result in results) / len(results),
        median_rounds=float(statistics.median(result.rounds for result in results)),
        p90_hp_loss=percentile_nearest_rank([result.hp_loss_pct for result in results], 0.90),
    )


def stable_cell_salt(parts: tuple[object, ...]) -> int:
    encoded = "|".join(map(str, parts)).encode("utf-8")
    return int.from_bytes(hashlib.sha256(encoded).digest()[:8], "big")


def measure_class_overlay(class_name: str, seeds: int) -> dict:
    if class_name not in CLASSES:
        raise AssertionError(class_name)
    registry = load_registry()
    active_coefficients_by_level = {
        skill_level: {
            definition_id: active_coefficient_bps(registry, definition_id, skill_level)
            for definition_id in CLASS_LOADOUTS[class_name]["actives"]
        }
        for skill_level in ANCHOR_LEVELS
    }
    other_add_by_level_behavior = {
        (skill_level, behavior): other_action_add_bps(registry, skill_level, behavior)
        for skill_level in ANCHOR_LEVELS
        for behavior in BEHAVIORS
    }
    residue_anchor_by_level = {
        skill_level: value_at(RESIDUE_ANCHORS, skill_level)
        for skill_level in ANCHOR_LEVELS
    }
    fixture_cache: dict[tuple[str, int, str, str], CombatFixture] = {}
    max_cell_win = 0.0
    max_rounds = 0.0
    max_hp = 0.0
    worst_weighted_loss = 0.0
    max_action_add = 0
    residue_events_by_case = {element_case: 0 for element_case in ELEMENT_CASES}
    residue_applied_events_by_case = {element_case: 0 for element_case in ELEMENT_CASES}
    residue_nominal_total_bps = 0
    residue_applied_total_bps = 0
    hostile_consume_triggers = 0
    residue_consumed = 0
    on_hero_actions = 0
    exact_no_effect_pairs = 0
    paired_battles = 0
    cells = 0

    for display_level in DISPLAY_LEVELS:
        for skill_level in ANCHOR_LEVELS:
            for behavior in BEHAVIORS:
                for monster_rank in MONSTER_RANKS:
                    for element_case in ELEMENT_CASES:
                        weighted_off = 0.0
                        weighted_on = 0.0
                        for profile in PROFILES:
                            fixture_key = (class_name, display_level, profile, monster_rank)
                            if fixture_key not in fixture_cache:
                                fixture_cache[fixture_key] = make_combat_fixture(*fixture_key)
                            fixture = fixture_cache[fixture_key]
                            salt = stable_cell_salt(
                                (
                                    class_name,
                                    display_level,
                                    skill_level,
                                    behavior,
                                    monster_rank,
                                    element_case,
                                    profile,
                                )
                            )
                            off_results = []
                            on_results = []
                            for seed in range(seeds):
                                seed_key = mix64(salt ^ ((seed + 1) * 0xD1342543DE82EF95))
                                off = simulate_battle(
                                    fixture,
                                    class_name=class_name,
                                    active_coefficients=active_coefficients_by_level[skill_level],
                                    residue_anchor=residue_anchor_by_level[skill_level],
                                    other_add=other_add_by_level_behavior[(skill_level, behavior)],
                                    behavior=behavior,
                                    element_case=element_case,
                                    seed_key=seed_key,
                                    residue_equipped=False,
                                )
                                on = simulate_battle(
                                    fixture,
                                    class_name=class_name,
                                    active_coefficients=active_coefficients_by_level[skill_level],
                                    residue_anchor=residue_anchor_by_level[skill_level],
                                    other_add=other_add_by_level_behavior[(skill_level, behavior)],
                                    behavior=behavior,
                                    element_case=element_case,
                                    seed_key=seed_key,
                                    residue_equipped=True,
                                )
                                assert off.extra_actions == on.extra_actions == 0
                                assert off.residue_resource_delta_bps == on.residue_resource_delta_bps == 0
                                assert off.max_action_add_bps <= ACTION_ADD_CAP_BPS
                                assert on.max_action_add_bps <= ACTION_ADD_CAP_BPS
                                expected_no_effect = element_case in {"SAME", "PHYSICAL", "MISS"}
                                if expected_no_effect:
                                    assert (off.outcome, off.rounds, off.hp_loss_pct) == (
                                        on.outcome,
                                        on.rounds,
                                        on.hp_loss_pct,
                                    )
                                    exact_no_effect_pairs += 1
                                off_results.append(off)
                                on_results.append(on)
                                residue_events_by_case[element_case] += on.residue_events
                                residue_applied_events_by_case[element_case] += on.residue_applied_events
                                residue_nominal_total_bps += on.residue_nominal_total_bps
                                residue_applied_total_bps += on.residue_applied_total_bps
                                hostile_consume_triggers += on.hostile_consume_triggers
                                residue_consumed += on.residue_consumed
                                on_hero_actions += on.hero_actions
                                max_action_add = max(
                                    max_action_add,
                                    off.max_action_add_bps,
                                    on.max_action_add_bps,
                                )
                            off_metric = metrics(off_results)
                            on_metric = metrics(on_results)
                            max_cell_win = max(
                                max_cell_win,
                                abs(on_metric.win_rate - off_metric.win_rate) * 100.0,
                            )
                            max_rounds = max(
                                max_rounds,
                                abs(on_metric.median_rounds - off_metric.median_rounds),
                            )
                            max_hp = max(
                                max_hp,
                                abs(on_metric.p90_hp_loss - off_metric.p90_hp_loss),
                            )
                            weighted_off += off_metric.win_rate * PROFILE_WEIGHTS[profile]
                            weighted_on += on_metric.win_rate * PROFILE_WEIGHTS[profile]
                            cells += 1
                            paired_battles += seeds
                        worst_weighted_loss = max(
                            worst_weighted_loss,
                            (weighted_off - weighted_on) * 100.0,
                        )

    expected_cells = (
        len(DISPLAY_LEVELS)
        * len(ANCHOR_LEVELS)
        * len(BEHAVIORS)
        * len(MONSTER_RANKS)
        * len(ELEMENT_CASES)
        * len(PROFILES)
    )
    assert cells == expected_cells
    assert paired_battles == expected_cells * seeds
    expected_no_effect_pairs = expected_cells * seeds // 2
    assert exact_no_effect_pairs == expected_no_effect_pairs
    assert residue_consumed > 0
    return {
        "className": class_name,
        "cells": cells,
        "pairedBattles": paired_battles,
        "maxCellWinDeltaPp": max_cell_win,
        "maxMedianRoundDelta": max_rounds,
        "maxP90HpLossDeltaPp": max_hp,
        "worstWeightedWinLossPp": worst_weighted_loss,
        "maxActionAddBps": max_action_add,
        "exactNoEffectPairs": exact_no_effect_pairs,
        "residueEventsByCase": residue_events_by_case,
        "residueAppliedEventsByCase": residue_applied_events_by_case,
        "residueNominalTotalBps": residue_nominal_total_bps,
        "residueAppliedTotalBps": residue_applied_total_bps,
        "hostileConsumeTriggers": hostile_consume_triggers,
        "residueConsumed": residue_consumed,
        "onHeroActions": on_hero_actions,
        "triggerRatePct": hostile_consume_triggers * 100.0 / max(1, on_hero_actions),
        "useRatePct": residue_consumed * 100.0 / max(1, on_hero_actions),
        "noEffect": False,
    }


def measure_class_overlay_args(args: tuple[str, int]) -> dict:
    return measure_class_overlay(*args)


def measure_overlay(seeds: int, parallel: bool = False) -> dict:
    if parallel:
        workers = max(1, min(len(CLASSES), os.cpu_count() or 1))
        with ProcessPoolExecutor(max_workers=workers) as executor:
            rows = list(executor.map(measure_class_overlay_args, ((name, seeds) for name in CLASSES)))
    else:
        rows = [measure_class_overlay(class_name, seeds) for class_name in CLASSES]

    summary = {
        "cells": sum(row["cells"] for row in rows),
        "pairedBattles": sum(row["pairedBattles"] for row in rows),
        "maxCellWinDeltaPp": max(row["maxCellWinDeltaPp"] for row in rows),
        "maxMedianRoundDelta": max(row["maxMedianRoundDelta"] for row in rows),
        "maxP90HpLossDeltaPp": max(row["maxP90HpLossDeltaPp"] for row in rows),
        "worstWeightedWinLossPp": max(row["worstWeightedWinLossPp"] for row in rows),
        "maxActionAddBps": max(row["maxActionAddBps"] for row in rows),
        "exactNoEffectPairs": sum(row["exactNoEffectPairs"] for row in rows),
        "residueEventsByCase": {
            element_case: sum(row["residueEventsByCase"][element_case] for row in rows)
            for element_case in ELEMENT_CASES
        },
        "residueAppliedEventsByCase": {
            element_case: sum(row["residueAppliedEventsByCase"][element_case] for row in rows)
            for element_case in ELEMENT_CASES
        },
        "residueNominalTotalBps": sum(row["residueNominalTotalBps"] for row in rows),
        "residueAppliedTotalBps": sum(row["residueAppliedTotalBps"] for row in rows),
        "classReachability": {
            row["className"]: {
                "hostileConsumeTriggers": row["hostileConsumeTriggers"],
                "residueUses": row["residueConsumed"],
                "heroActions": row["onHeroActions"],
                "triggerRatePct": row["triggerRatePct"],
                "useRatePct": row["useRatePct"],
                "noEffect": row["noEffect"],
            }
            for row in rows
        },
        "noEffectClasses": tuple(row["className"] for row in rows if row["noEffect"]),
        "tokenConservation": True,
        "extraActions": 0,
        "resourceDeltaBps": 0,
        "workers": len(rows) if parallel else 1,
    }

    assert summary["maxCellWinDeltaPp"] <= GATES["maxCellWinDeltaPp"] + 1e-12
    assert summary["maxMedianRoundDelta"] <= GATES["maxMedianRoundDelta"] + 1e-12
    assert summary["maxP90HpLossDeltaPp"] <= GATES["maxP90HpLossDeltaPp"] + 1e-12
    assert summary["worstWeightedWinLossPp"] <= GATES["maxWeightedWinLossPp"] + 1e-12
    assert summary["worstWeightedWinLossPp"] <= GATES["maxPassiveWeightedWinLossPp"] + 1e-12
    assert summary["maxActionAddBps"] == ACTION_ADD_CAP_BPS
    assert summary["residueEventsByCase"]["DIFFERENT"] > 0
    assert summary["residueEventsByCase"]["BARRIER"] > 0
    assert summary["residueEventsByCase"]["MULTI_CONSUME"] > 0
    assert summary["residueEventsByCase"]["SAME"] == 0
    assert summary["residueEventsByCase"]["PHYSICAL"] == 0
    assert summary["residueEventsByCase"]["MISS"] == 0
    assert 0 <= summary["residueAppliedTotalBps"] <= summary["residueNominalTotalBps"]
    assert summary["residueAppliedEventsByCase"]["SAME"] == 0
    assert summary["residueAppliedEventsByCase"]["PHYSICAL"] == 0
    assert summary["residueAppliedEventsByCase"]["MISS"] == 0
    assert summary["noEffectClasses"] == NO_EFFECT_CLASSES
    assert all(
        values["residueUses"] == 0 if values["noEffect"] else values["residueUses"] > 0
        for values in summary["classReachability"].values()
    )

    expected_cells = (
        len(CLASSES)
        * len(DISPLAY_LEVELS)
        * len(ANCHOR_LEVELS)
        * len(BEHAVIORS)
        * len(MONSTER_RANKS)
        * len(ELEMENT_CASES)
        * len(PROFILES)
    )
    assert summary["cells"] == expected_cells
    assert summary["pairedBattles"] == expected_cells * seeds
    assert summary["exactNoEffectPairs"] == expected_cells * seeds // 2
    return summary


def canonical_hash() -> str:
    payload = {
        "rulesVersion": "aq.production-p6t.elemental-residue.v0.1",
        "sourceSha256": EXPECTED_SOURCE_SHA256,
        "contentBindings": EXPECTED_CONSTANTS,
        "wave1CanonicalSha256": EXPECTED_WAVE1_CANONICAL_SHA256,
        "classLoadouts": CLASS_LOADOUTS,
        "runtimeP5hHostElements": P5H_RUNTIME_HOST_ELEMENTS,
        "classArmHost": CLASS_ARM_HOST,
        "classDifferentHost": CLASS_DIFFERENT_HOST,
        "noEffectClasses": NO_EFFECT_CLASSES,
        "actionSequences": {
            f"{class_name}:{element_case}": [
                {
                    "definitionId": action.definition_id,
                    "element": action.element,
                    "consumedTags": action.consumed_tags,
                    "p5hOwned": action.p5h_owned,
                    "forcedMiss": action.forced_miss,
                    "barrierBlocked": action.barrier_blocked,
                }
                for action in ACTION_SEQUENCES[(class_name, element_case)]
            ]
            for class_name in CLASSES
            for element_case in ELEMENT_CASES
        },
        "actionAddFixture": {
            "dragonPrideCondition": "ON",
            "undyingCurseStacksByBehavior": {"CAUTIOUS": 0, "BALANCED": 1, "BOLD": 3},
            "behaviorCoefficientBps": {"CAUTIOUS": -300, "BALANCED": 0, "BOLD": 300},
        },
        "p0p1Hardening": {
            "appliedCount": "INCREMENT_ONLY_WHEN_RESIDUE_APPLIED_BPS_GT_0",
            "duplicateAttach": "P6T_ALREADY_ATTACHED_REJECTED",
            "tokenPostcondition": "HOSTILE_ARM_ELSE_CONSUME_ELSE_RETAIN",
            "passiveOffHostileConsume": "COMMIT_ALLOWED_WITHOUT_TOKEN_ARM",
        },
        "skillAnchorLevels": ANCHOR_LEVELS,
        "residueAnchors": RESIDUE_ANCHORS,
        "displayLevels": DISPLAY_LEVELS,
        "classes": CLASSES,
        "behaviors": BEHAVIORS,
        "profiles": PROFILES,
        "profileWeights": PROFILE_WEIGHTS,
        "monsterRanks": MONSTER_RANKS,
        "elementCases": ELEMENT_CASES,
        "actionAddCapBps": ACTION_ADD_CAP_BPS,
        "gates": GATES,
        "pdSeeds": 200,
        "ordering": "OLD_TOKEN_APPLIES_BEFORE_LANDED_HOSTILE_CONSUME_ARMS_SUCCESSOR",
        "extraActions": 0,
        "resourceDeltaBps": 0,
        "liveEnablement": False,
    }
    return canonical_json_hash(payload)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=24, help="paired seeds per cell in fast mode")
    parser.add_argument("--pd", action="store_true", help="run the frozen 200-seed PD matrix")
    args = parser.parse_args()
    seeds = 200 if args.pd else args.seeds
    if seeds < 20:
        parser.error("--seeds must be at least 20")

    passed = []
    for name, check in STATIC_CHECKS:
        check()
        passed.append(name)
    dynamic = measure_overlay(seeds, parallel=args.pd)
    passed.extend(
        (
            "PAIRED_ON_OFF_ALL_LEVEL_CLASS_BEHAVIOR_RANK_PROFILE_ELEMENT_MATRIX",
            "RUNTIME_EXECUTABLE_P5H_HOSTS_AND_CLASS_REACHABILITY",
            "ACTION_ADD_1500_CAP_AND_TOKEN_CONSERVATION",
            "NO_EXTRA_ACTION_OR_RESOURCE_GENERATION",
            "CELL_ROUND_HP_WEIGHTED_AND_PASSIVE_LOSS_GATES",
        )
    )

    tool_hash = sha256_bytes(Path(__file__).read_bytes())
    print(
        f"PRODUCTION_P6T_ELEMENTAL_RESIDUE_V0_1: PASS ({len(passed)}/{len(passed)}) "
        f"seeds={seeds} cells={dynamic['cells']} pairedBattles={dynamic['pairedBattles']} "
        f"workers={dynamic['workers']}"
    )
    print(f"  canonical_sha256={canonical_hash()}")
    print(f"  tool_sha256={tool_hash}")
    print(
        "  bindings="
        + ",".join(
            f"{name}:{constants[next(key for key in constants if key.endswith('CONTENT_HASH'))][:12]}"
            for name, constants in EXPECTED_CONSTANTS.items()
        )
    )
    print(
        "  gates "
        f"cellWin={dynamic['maxCellWinDeltaPp']:.2f}pp/20.00pp "
        f"rounds={dynamic['maxMedianRoundDelta']:.1f}/3.0 "
        f"p90Hp={dynamic['maxP90HpLossDeltaPp']:.2f}pp/18.00pp "
        f"weightedLoss={dynamic['worstWeightedWinLossPp']:.2f}pp/8.00pp "
        f"passiveLoss={dynamic['worstWeightedWinLossPp']:.2f}pp/1.00pp"
    )
    print(
        "  gateStatus=9/9 "
        "[cellWin,medianRound,p90Hp,weightedLoss,passiveWeightedLoss," 
        "actionAddCap,tokenConservation,noExtraAction,noResourceDelta]"
    )
    print(
        f"  invariants actionAddMax={dynamic['maxActionAddBps']} "
        f"exactNoEffectPairs={dynamic['exactNoEffectPairs']} extraActions=0 resourceDeltaBps=0"
    )
    print(
        "  residueNominalEvents="
        + ",".join(f"{name}:{count}" for name, count in dynamic["residueEventsByCase"].items())
    )
    print(
        "  residueAppliedEvents="
        + ",".join(f"{name}:{count}" for name, count in dynamic["residueAppliedEventsByCase"].items())
        + f" nominalTotalBps={dynamic['residueNominalTotalBps']}"
        + f" appliedTotalBps={dynamic['residueAppliedTotalBps']}"
    )
    for class_name in CLASSES:
        reach = dynamic["classReachability"][class_name]
        print(
            f"  {class_name:8} trigger={reach['hostileConsumeTriggers']} "
            f"({reach['triggerRatePct']:.2f}%) use={reach['residueUses']} "
            f"({reach['useRatePct']:.2f}%) reachable={str(not reach['noEffect']).lower()}"
        )
    for name in passed:
        print(f"  PASS {name}")


if __name__ == "__main__":
    main()
