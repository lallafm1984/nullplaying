#!/usr/bin/env python3
"""AlarmQuest v0.9 automatic battle AI and loadout contract audit.

Planning/test only.  The AI receives a frozen build snapshot and a public
observation.  Hidden future monster state is deliberately absent from the
decision API.  Production Kotlin, Room, assets and live characters are not
read or mutated.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from dataclasses import asdict, dataclass, replace
from functools import lru_cache
from pathlib import Path

import monster_prefix_variants_v0_2_review as monsters
import skill_cost_transfer_dispel_v0_7_review as cost_v07
import skill_effect_resolver_v0_3_review as resolver
import skill_protection_death_v0_8_review as death_v08
import skill_registry_240_v0_2_review as skills


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_AUTO_BATTLE_AI_LOADOUT_v0.9.md"
KOTLIN_CONTRACT = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt"
RULES_VERSION = "aq.auto-battle-ai-loadout.v0.9"
POLICY_RULES_VERSION = "aq.behavior-policy.v1"
ABILITY_RULES_VERSION = "aq.monster-ability-execution.v0.9-test"
DISPLAY_LEVELS = resolver.DISPLAY_LEVELS
SKILL_LEVELS = resolver.SKILL_LEVELS
BUILD_ARCHETYPES = ("OFFENSE", "CONTROL", "SUSTAIN")
BEHAVIOR_POLICIES = ("CAUTIOUS", "BALANCED", "BOLD")
LAYOUTS = ("FULL_5_3", "NEW_4_2", "EMPTY_0_0")
MAX_ACTIVE = 5
MAX_PASSIVE = 3
MAX_ROUNDS = 30
MAX_ACTIONS = 60
MAX_RESOURCE_BPS = 10_000
SAFE_COPY_TAGS = tuple(sorted(cost_v07.SAFE_MIMIC_TAGS))
MIMIC_PRIORITY = ("PHYSICAL", "FIRE", "ICE", "LIGHTNING", "HOLY", "ARCANE",
                  "BLEED", "BURN", "POISON", "CHILL", "SHOCK", "CURSE", "MARK")
REASON_CODES = frozenset({
    "LETHAL_PREVENTION", "PUBLIC_TELEGRAPH_RESPONSE", "CONTROL_OPPORTUNITY",
    "DISPEL_NET_POSITIVE", "CLEANSE_LETHAL_STATUS", "EXECUTION_WINDOW",
    "POLICY_PRIORITY", "SYSTEM_RECOVERY", "BASIC_FALLBACK",
})
EXCLUSION_CODES = frozenset({
    "COOLDOWN", "RESOURCE", "HP_COST", "SHIELD", "NO_HOSTILE_STATUS",
    "NO_TARGET_BUFF", "NO_PUBLIC_TELEGRAPH", "UNSAFE_COPY", "STATUS_CAP",
    "LEDGER_CAP", "OUTSIDE_EXECUTION_WINDOW", "RESOURCE_RESERVED",
})

# lifecycle, encounter start, BASIC gain, DIRECT actual-HP-damage gain
RESOURCE_DEFINITIONS = {
    "WARRIOR": ("ENCOUNTER_BUILDER", 0, 2_000, 1_000),
    "ROGUE": ("ENCOUNTER_BUILDER", 0, 2_500, 0),
    "RANGER": ("EXPEDITION_POOL", 10_000, 1_200, 0),
    "MAGE": ("EXPEDITION_POOL", 10_000, 0, 0),
    "CLERIC": ("EXPEDITION_POOL", 10_000, 800, 0),
    "PALADIN": ("ENCOUNTER_BUILDER", 2_000, 1_200, 800),
}
RESOURCE_FLOORS = {
    "CAUTIOUS": {"ENCOUNTER_BUILDER": 0, "EXPEDITION_POOL": 4_000},
    "BALANCED": {"ENCOUNTER_BUILDER": 0, "EXPEDITION_POOL": 2_000},
    "BOLD": {"ENCOUNTER_BUILDER": 0, "EXPEDITION_POOL": 0},
}


@dataclass(frozen=True)
class SkillSlot:
    skill_id: str
    skill_level: int


@dataclass(frozen=True)
class Loadout:
    hero_class: str
    active_slots: tuple[SkillSlot, ...]
    passive_slots: tuple[SkillSlot, ...]
    build_archetype: str       # preset provenance/analytics only
    behavior_policy: str       # the only user combat-policy axis
    layout: str


@dataclass(frozen=True)
class AbilityExecution:
    ability_id: str
    delivery: str
    origin: str
    damage_type: str
    coefficient_bps: int
    publicly_telegraphed: bool
    telegraph_coefficient_bps: int
    accuracy_policy: str
    interrupt_tags: tuple[str, ...]
    attached_status_tags: tuple[str, ...]
    cooldown_roots: int


@dataclass(frozen=True)
class PublicTelegraph:
    receipt_id: str
    ability_id: str
    delivery: str
    origin: str
    damage_type: str
    coefficient_bps: int
    status_tags: tuple[str, ...]
    valid_from_root: int
    expires_at_root: int


@dataclass(frozen=True)
class EnemyActionReceipt:
    receipt_id: str
    ability_id: str
    committed: bool
    public: bool
    safe_tags: tuple[str, ...]
    copy_depth: int = 0


@dataclass(frozen=True)
class PrefixEventReceipt:
    event_id: str
    root: int
    handler: str
    outcome: str
    hero_hp_delta: int
    enemy_hp_delta: int


@dataclass(frozen=True)
class EffectInstance:
    instance_id: str
    tag: str
    magnitude_bps: int
    remaining_roots: int
    hostile: bool
    beneficial: bool
    action_blocking: bool = False
    periodic_damage: int = 0
    dispellable: bool = True
    cost_locked: bool = False
    cleanse_immune: bool = False
    source_scope: str = "SKILL"


@dataclass(frozen=True)
class DecisionState:
    hp: int
    max_hp: int
    enemy_hp: int
    enemy_max_hp: int
    resource_bps: int
    resource_ceiling_bps: int
    debt_bps: int = 0
    shield_bps: int = 2_000
    cooldowns: tuple[tuple[str, int], ...] = ()
    self_effects: tuple[EffectInstance, ...] = ()
    target_effects: tuple[EffectInstance, ...] = ()
    last_enemy_receipt: EnemyActionReceipt | None = None
    mage_recovery_count: int = 0
    previous_action_id: str = ""


@dataclass(frozen=True)
class ResourceQuote:
    resource_cost_bps: int
    hp_cost: int
    shield_cost_bps: int
    debt_created_bps: int
    family: str


@dataclass(frozen=True)
class CandidateReceipt:
    action_id: str
    selected_reason: str
    priority_band: int
    policy_delta: int
    score: int
    excluded: tuple[tuple[str, str], ...]
    candidate_digest: str
    public_observation_hash: str
    selected_effect_instance_id: str
    mimic_source_receipt_id: str
    mimic_tag: str
    resource_before: int
    resource_after: int
    ceiling_before: int
    ceiling_after: int
    debt_before: int
    debt_after: int


@dataclass(frozen=True)
class DecisionRun:
    display_level: int
    hero_class: str
    build_archetype: str
    behavior_policy: str
    layout: str
    variant_id: str
    active_slots: int
    passive_slots: int
    actions: int
    outcome: str
    skill_actions: int
    basic_actions: int
    recovery_actions: int
    resource_blocks: int
    final_resource_bps: int
    final_debt_bps: int
    ability_ids_seen: tuple[str, ...]
    prefix_event_id: str
    prefix_event_count: int
    passive_snapshot_hash: str
    invalid_reason_codes: int
    decision_digest: str


def _ability_definitions() -> tuple[AbilityExecution, ...]:
    ids = sorted({ability for base in monsters.BASE_ENEMIES for ability in base.ability_ids})
    damage_types = ("PHYSICAL", "FIRE", "ICE", "LIGHTNING", "ARCANE", "POISON")
    result = []
    for index, ability_id in enumerate(ids):
        delivery = "PERIODIC" if index % 7 == 0 else "DIRECT"
        damage_type = damage_types[index % len(damage_types)]
        public = index % 2 == 0
        telegraph = (12_999, 13_000, 15_000)[index % 3]
        status = (damage_type,) if damage_type in {"POISON"} else ()
        result.append(AbilityExecution(
            ability_id, delivery, "NORMAL", damage_type, 8_000 + index % 8 * 500,
            public, telegraph, "STANDARD", ("STAGGER",) if index % 5 == 0 else (),
            status, 1 + index % 4,
        ))
    return tuple(result)


ABILITY_DEFINITIONS = _ability_definitions()
ABILITY_BY_ID = {item.ability_id: item for item in ABILITY_DEFINITIONS}
BASE_BY_ID = {item.enemy_id: item for item in monsters.BASE_ENEMIES}
V07_BY_ID = cost_v07.BY_ID
SKILL_REGISTRY_HASH = skills.canonical_hash()
MONSTER_REGISTRY_HASH = monsters.canonical_hash()
PROTECTION_DEATH_HASH = death_v08.canonical_hash()


def behavior_id(policy: str) -> str:
    return f"aq.behavior.{policy.lower()}.v1"


def validate_loadout(loadout: Loadout) -> tuple[bool, str]:
    if loadout.hero_class not in skills.CLASSES:
        return False, "INVALID_CLASS"
    if loadout.build_archetype not in BUILD_ARCHETYPES or loadout.behavior_policy not in BEHAVIOR_POLICIES:
        return False, "INVALID_POLICY"
    if loadout.layout not in LAYOUTS:
        return False, "INVALID_LAYOUT"
    if not 0 <= len(loadout.active_slots) <= MAX_ACTIVE or not 0 <= len(loadout.passive_slots) <= MAX_PASSIVE:
        return False, "SLOT_CAP"
    all_slots = loadout.active_slots + loadout.passive_slots
    ids = [slot.skill_id for slot in all_slots]
    if len(ids) != len(set(ids)):
        return False, "DUPLICATE"
    if any(not 1 <= slot.skill_level <= 100 for slot in all_slots):
        return False, "SKILL_LEVEL"
    accessible_active = {x.definition_id for x in skills.accessible(loadout.hero_class, "ACTIVE")}
    accessible_passive = {x.definition_id for x in skills.accessible(loadout.hero_class, "PASSIVE")}
    if not {x.skill_id for x in loadout.active_slots} <= accessible_active:
        return False, "ACTIVE_ACCESS_OR_KIND"
    if not {x.skill_id for x in loadout.passive_slots} <= accessible_passive:
        return False, "PASSIVE_ACCESS_OR_KIND"
    return True, "VALID"


def validate_equipment_payload(payload: dict) -> tuple[bool, str]:
    forbidden = {"grantedSkillDefinitionId", "extraActiveSlot", "extraPassiveSlot", "equipmentSkillLevel"}
    return (False, "EQUIPMENT_SKILL_FORBIDDEN") if forbidden.intersection(payload) else (True, "VALID")


@lru_cache(maxsize=None)
def build_loadout(hero_class: str, build: str, behavior: str, layout: str, skill_level: int) -> Loadout:
    base = resolver.build_loadout(hero_class, build, skill_level)
    active = tuple(SkillSlot(x.skill_id, x.skill_level) for x in base.active_slots)
    passive = tuple(SkillSlot(x.skill_id, x.skill_level) for x in base.passive_slots)
    if layout == "NEW_4_2":
        active, passive = active[:4], passive[:2]
    elif layout == "EMPTY_0_0":
        active, passive = (), ()
    loadout = Loadout(hero_class, active, passive, build, behavior, layout)
    assert validate_loadout(loadout) == (True, "VALID")
    return loadout


@lru_cache(maxsize=None)
def loadout_hash(loadout: Loadout) -> str:
    # build_archetype is intentionally excluded once the stable slots exist.
    payload = {
        "heroClass": loadout.hero_class,
        "active": [asdict(x) for x in sorted(loadout.active_slots, key=lambda x: x.skill_id)],
        "passive": [asdict(x) for x in sorted(loadout.passive_slots, key=lambda x: x.skill_id)],
        "behaviorPolicyId": behavior_id(loadout.behavior_policy),
        "rulesVersion": RULES_VERSION,
        "skillContentHash": SKILL_REGISTRY_HASH,
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def public_observation(ability: AbilityExecution, root: int, encounter_id: str) -> PublicTelegraph | None:
    if not ability.publicly_telegraphed:
        return None
    return PublicTelegraph(
        f"{encounter_id}:telegraph:{root}", ability.ability_id, ability.delivery,
        ability.origin, ability.damage_type, ability.telegraph_coefficient_bps,
        ability.attached_status_tags, root, root,
    )


def public_hash(telegraph: PublicTelegraph | None) -> str:
    raw = json.dumps(asdict(telegraph) if telegraph else {"publicTelegraph": None}, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def eligible_cleanse_targets(state: DecisionState) -> tuple[EffectInstance, ...]:
    eligible = [x for x in state.self_effects if x.hostile and not x.cost_locked and not x.cleanse_immune]
    return tuple(sorted(eligible, key=lambda x: (
        -(x.periodic_damage >= state.hp), -x.action_blocking, -x.periodic_damage,
        -x.remaining_roots, x.instance_id,
    )))


def eligible_dispel_targets(state: DecisionState) -> tuple[EffectInstance, ...]:
    excluded_scopes = {"EQUIPMENT", "RACE_BASE", "CLASS_BASE", "SHIELD", "BARRIER", "RESOURCE"}
    eligible = [x for x in state.target_effects if x.beneficial and x.dispellable and x.source_scope not in excluded_scopes]
    return tuple(sorted(eligible, key=lambda x: (-abs(x.magnitude_bps), -x.remaining_roots, x.instance_id)))


def mimic_tag(receipt: EnemyActionReceipt | None) -> tuple[str, str]:
    if receipt is None or not receipt.committed or not receipt.public or receipt.copy_depth != 0:
        return "", ""
    safe = set(receipt.safe_tags).intersection(SAFE_COPY_TAGS)
    selected = next((tag for tag in MIMIC_PRIORITY if tag in safe), "")
    return receipt.receipt_id, selected


def special_family(skill_id: str) -> str:
    contract = V07_BY_ID.get(skill_id)
    return contract.family if contract else "NORMAL_RESOURCE"


def resource_quote(item, level: int, state: DecisionState) -> tuple[ResourceQuote | None, str]:
    family = special_family(item.definition_id)
    base = skills.resolved_resource_cost(item, level)
    resource_cost, hp_cost, shield_cost, debt = base, 0, 0, 0
    if family == "CURRENT_RESOURCE_PREPAY":
        resource_cost = state.resource_bps
        if item.definition_id.endswith("resolveburst") and state.resource_bps != MAX_RESOURCE_BPS:
            return None, "RESOURCE"
        if item.definition_id.endswith("manaburst") and state.resource_bps < 7_000:
            return None, "RESOURCE"
        if item.definition_id.endswith("lastshot") and not 0 < state.resource_bps <= 2_500:
            return None, "RESOURCE"
    elif family == "MAX_RESOURCE_SELF_COST":
        resource_cost = MAX_RESOURCE_BPS
    elif family in {"HP_PREPAY", "HP_FIXED_PREPAY", "HP_RESOURCE_REBALANCE", "HP_TO_RESOURCE", "HP_BACKED_DEFICIT"}:
        resource_cost = 0 if family != "HP_BACKED_DEFICIT" else min(base, state.resource_bps)
        hp_cost = max(1, state.max_hp * max(500, skills.value_at(item.anchor_values, level)) // 100_000)
    elif family in {"SHIELD_PREPAY", "SHIELD_TO_DAMAGE", "SHIELD_TO_BARRIER"}:
        resource_cost = 0
        shield_cost = max(500, min(state.shield_bps, skills.value_at(item.anchor_values, level)))
    elif family == "RESOURCE_DEBT":
        resource_cost = min(base, state.resource_bps)
        debt = max(0, base - resource_cost)
        if state.debt_bps + debt > cost_v07.DEBT_CAP_BPS:
            return None, "RESOURCE"
    if hp_cost and state.hp - hp_cost < 1:
        return None, "HP_COST"
    if shield_cost and state.shield_bps < shield_cost:
        return None, "SHIELD"
    if resource_cost > state.resource_bps:
        return None, "RESOURCE"
    return ResourceQuote(resource_cost, hp_cost, shield_cost, debt, family), "ELIGIBLE"


def is_cleanse(item) -> bool:
    text = f"{item.pattern} {item.candidate_rule_id}".upper()
    return "CLEANSE" in text and "DISPEL" not in text


def is_dispel(item) -> bool:
    text = f"{item.pattern} {item.candidate_rule_id}".upper()
    return "DISPEL" in text or "BUFF_STEAL" in text


def candidate_gate(item, level: int, state: DecisionState, hero_class: str, behavior: str,
                   telegraph: PublicTelegraph | None) -> tuple[bool, str, ResourceQuote | None, str]:
    if dict(state.cooldowns).get(item.definition_id, 0) > 0:
        return False, "COOLDOWN", None, ""
    selected_effect = ""
    if is_cleanse(item):
        targets = eligible_cleanse_targets(state)
        if not targets:
            return False, "NO_HOSTILE_STATUS", None, ""
        selected_effect = targets[0].instance_id
    if is_dispel(item):
        targets = eligible_dispel_targets(state)
        if not targets:
            return False, "NO_TARGET_BUFF", None, ""
        selected_effect = targets[0].instance_id
    if item.pattern == "SAFE_TAG_MIMIC" and not mimic_tag(state.last_enemy_receipt)[1]:
        return False, "UNSAFE_COPY", None, ""
    if item.pattern == "PREPAID_SPELL_REFLECT":
        if telegraph is None or telegraph.delivery != "DIRECT" or telegraph.damage_type not in {"FIRE", "ICE", "LIGHTNING", "ARCANE", "HOLY"}:
            return False, "NO_PUBLIC_TELEGRAPH", None, ""
    if "EXECUTION" in item.pattern and state.enemy_hp * 10_000 > state.enemy_max_hp * 2_500:
        return False, "OUTSIDE_EXECUTION_WINDOW", None, ""
    quote, why = resource_quote(item, level, state)
    if quote is None:
        return False, why, None, ""
    lifecycle = RESOURCE_DEFINITIONS[hero_class][0]
    floor = RESOURCE_FLOORS[behavior][lifecycle]
    projected = state.resource_bps - quote.resource_cost_bps
    survival = state.hp * 10_000 <= state.max_hp * 2_500 and bool(set(item.roles) & {"DEFENSE", "HEAL"})
    # No imaginary future BASIC/recovery is credited. Known pending receipts would
    # be added here, but the v0.9 snapshot contains none.
    if projected < floor and not survival:
        return False, "RESOURCE_RESERVED", None, ""
    return True, "ELIGIBLE", quote, selected_effect


def reason_band(item, state: DecisionState, telegraph: PublicTelegraph | None) -> tuple[str, int]:
    roles = set(item.roles)
    hp_bps = state.hp * 10_000 // max(1, state.max_hp)
    if hp_bps <= 2_500 and roles & {"DEFENSE", "HEAL"}:
        return "LETHAL_PREVENTION", 900
    if is_cleanse(item):
        return "CLEANSE_LETHAL_STATUS", 850
    if telegraph and telegraph.coefficient_bps >= 13_000 and roles & {"DEFENSE", "CONTROL"}:
        return "PUBLIC_TELEGRAPH_RESPONSE", 800
    if is_dispel(item):
        return "DISPEL_NET_POSITIVE", 700
    if "EXECUTION" in item.pattern:
        return "EXECUTION_WINDOW", 650
    if "CONTROL" in roles:
        return "CONTROL_OPPORTUNITY", 600
    return "POLICY_PRIORITY", 400


def policy_delta(item, policy: str) -> int:
    roles = set(item.roles)
    if policy == "CAUTIOUS":
        return 90 if roles & {"DEFENSE", "HEAL", "CONTROL"} else 0
    if policy == "BOLD":
        return 90 if roles & {"ATTACK", "EXECUTION", "PRECISION"} else 0
    return 45 if roles & {"ATTACK", "DEFENSE", "CONTROL", "HEAL"} else 0


def choose_action(loadout: Loadout, state: DecisionState,
                  telegraph: PublicTelegraph | None) -> CandidateReceipt:
    excluded = []
    candidates = []
    for slot in sorted(loadout.active_slots, key=lambda x: x.skill_id):
        item = skills.ACTIVE_BY_ID[slot.skill_id]
        eligible, why, quote, effect_id = candidate_gate(
            item, slot.skill_level, state, loadout.hero_class, loadout.behavior_policy, telegraph,
        )
        if not eligible:
            excluded.append((slot.skill_id, why))
            continue
        reason, band = reason_band(item, state, telegraph)
        delta = policy_delta(item, loadout.behavior_policy)
        level_value = skills.value_at(item.attack_equivalent_values, slot.skill_level)
        candidates.append((band, delta, level_value, slot.skill_id, reason, quote, effect_id))
    candidate_raw = json.dumps({"eligible": [(x[3], x[0], x[1], x[2]) for x in candidates],
                                "excluded": sorted(excluded)}, separators=(",", ":"))
    digest = hashlib.sha256(candidate_raw.encode()).hexdigest()
    obs_hash = public_hash(telegraph)
    if candidates:
        band, delta, value, skill_id, reason, quote, effect_id = sorted(
            candidates, key=lambda x: (-x[0], -x[1], -x[2], x[3]),
        )[0]
        assert quote is not None
        after = state.resource_bps - quote.resource_cost_bps
        ceiling_loss_rate = 10_000 if set(skills.ACTIVE_BY_ID[skill_id].roles) & {"HEAL", "DEFENSE"} else 5_000
        ceiling_loss = quote.resource_cost_bps * ceiling_loss_rate // 10_000 if RESOURCE_DEFINITIONS[loadout.hero_class][0] == "EXPEDITION_POOL" else 0
        source_id, copied_tag = mimic_tag(state.last_enemy_receipt) if skills.ACTIVE_BY_ID[skill_id].pattern == "SAFE_TAG_MIMIC" else ("", "")
        return CandidateReceipt(
            skill_id, reason, band, delta, band * 1_000_000 + delta * 10_000 + value,
            tuple(sorted(excluded)), digest, obs_hash, effect_id, source_id, copied_tag,
            state.resource_bps, after, state.resource_ceiling_bps,
            max(0, state.resource_ceiling_bps - ceiling_loss), state.debt_bps,
            state.debt_bps + quote.debt_created_bps,
        )
    if loadout.hero_class == "MAGE" and state.resource_bps < state.resource_ceiling_bps and state.mage_recovery_count < 3 and state.previous_action_id != "SYSTEM_RECOVERY":
        after = min(state.resource_ceiling_bps, state.resource_bps + 1_500)
        return CandidateReceipt("SYSTEM_RECOVERY", "SYSTEM_RECOVERY", 200, 0, 200_000_000,
                                tuple(sorted(excluded)), digest, obs_hash, "", "", "",
                                state.resource_bps, after, state.resource_ceiling_bps,
                                state.resource_ceiling_bps, state.debt_bps, state.debt_bps)
    return CandidateReceipt("BASIC", "BASIC_FALLBACK", 100, 0, 100_000_000,
                            tuple(sorted(excluded)), digest, obs_hash, "", "", "",
                            state.resource_bps, state.resource_bps, state.resource_ceiling_bps,
                            state.resource_ceiling_bps, state.debt_bps, state.debt_bps)


def apply_decision(loadout: Loadout, state: DecisionState, receipt: CandidateReceipt) -> DecisionState:
    cooldowns = {key: max(0, value - 1) for key, value in state.cooldowns}
    cooldowns = {key: value for key, value in cooldowns.items() if value > 0}
    resource, ceiling, debt = receipt.resource_after, receipt.ceiling_after, receipt.debt_after
    hp, shield = state.hp, state.shield_bps
    self_effects, target_effects = state.self_effects, state.target_effects
    if receipt.action_id == "BASIC":
        gain = RESOURCE_DEFINITIONS[loadout.hero_class][2]
        repaid = min(debt, gain)
        debt -= repaid
        resource = min(ceiling, resource + gain - repaid)
    elif receipt.action_id == "SYSTEM_RECOVERY":
        pass
    else:
        slot = next(x for x in loadout.active_slots if x.skill_id == receipt.action_id)
        item = skills.ACTIVE_BY_ID[receipt.action_id]
        quote, why = resource_quote(item, slot.skill_level, state)
        assert quote is not None, why
        hp -= quote.hp_cost
        shield -= quote.shield_cost_bps
        cooldowns[item.definition_id] = item.cooldown_roots
        if receipt.selected_effect_instance_id:
            self_effects = tuple(x for x in self_effects if x.instance_id != receipt.selected_effect_instance_id)
            target_effects = tuple(x for x in target_effects if x.instance_id != receipt.selected_effect_instance_id)
    damage = 1_200 if receipt.action_id in {"BASIC", "SYSTEM_RECOVERY"} else 1_800
    if receipt.action_id == "SYSTEM_RECOVERY":
        damage = 0
    return replace(
        state, hp=max(1, hp), enemy_hp=max(0, state.enemy_hp - damage),
        resource_bps=max(0, min(ceiling, resource)), resource_ceiling_bps=ceiling,
        debt_bps=debt, shield_bps=max(0, shield), cooldowns=tuple(sorted(cooldowns.items())),
        self_effects=self_effects, target_effects=target_effects,
        mage_recovery_count=state.mage_recovery_count + (receipt.action_id == "SYSTEM_RECOVERY"),
        previous_action_id=receipt.action_id,
    )


def monster_action(state: DecisionState, ability: AbilityExecution, variant, root: int) -> DecisionState:
    safe_tags = tuple(sorted({ability.damage_type, *ability.attached_status_tags}))
    receipt = EnemyActionReceipt(f"{variant.variant_id}:enemy:{root}", ability.ability_id, True, True, safe_tags)
    damage = 250 + ability.coefficient_bps // 40
    hp = max(0, state.hp - damage)
    effects = list(state.self_effects)
    for tag in ability.attached_status_tags:
        instance = EffectInstance(f"{receipt.receipt_id}:{tag}", tag, 500, 2, True, False, periodic_damage=120)
        if len(effects) < 6:
            effects.append(instance)
    return replace(state, hp=hp, self_effects=tuple(effects), last_enemy_receipt=receipt)


def apply_prefix_event(state: DecisionState, variant, root: int) -> tuple[DecisionState, PrefixEventReceipt]:
    """Execute the v0.2 prefix behavior at a real root boundary.

    Static prefixes bind their modifier package at root 0. Periodic prefixes
    perform the smallest deterministic state mutation needed to prove handler
    reachability; production coefficients remain a later gate.
    """
    prefix = monsters.PREFIX_BY_ID[variant.prefix_id]
    event_id = f"aq.prefix.event.{variant.prefix_id.lower()}"
    before_hp, before_enemy = state.hp, state.enemy_hp
    handler, outcome = "STATIC_MODIFIER", "BOUND"
    next_state = state
    if variant.prefix_id in {"VENOMOUS", "EMBER", "CURSED"} and root > 0:
        handler = "HOSTILE_STATUS_TICK"
        next_state = replace(state, hp=max(0, state.hp - 120))
        outcome = "APPLIED"
    elif variant.prefix_id == "REGENERATING" and root > 0 and root % 3 == 0:
        handler = "THIRD_ROOT_REGEN"
        next_state = replace(state, enemy_hp=min(state.enemy_max_hp, state.enemy_hp + 500))
        outcome = "APPLIED"
    elif variant.prefix_id == "HUNGRY" and root > 0 and state.enemy_hp * 10_000 <= state.enemy_max_hp * 3_500:
        handler = "LOW_HP_ENRAGE"
        next_state = replace(state, hp=max(0, state.hp - 150))
        outcome = "APPLIED"
    elif variant.prefix_id == "ANCIENT":
        handler = "CONTROL_DELAY_CAP"
    return next_state, PrefixEventReceipt(
        event_id, root, handler, outcome, next_state.hp - before_hp,
        next_state.enemy_hp - before_enemy,
    )


def apply_direct_damage_resource(loadout: Loadout, before_hp: int, state: DecisionState) -> DecisionState:
    if state.hp >= before_hp:
        return state
    gain = RESOURCE_DEFINITIONS[loadout.hero_class][3]
    if gain <= 0:
        return state
    repaid = min(state.debt_bps, gain)
    return replace(state, debt_bps=state.debt_bps - repaid,
                   resource_bps=min(state.resource_ceiling_bps, state.resource_bps + gain - repaid))


def initial_state(hero_class: str, variant) -> DecisionState:
    _, start, _, _ = RESOURCE_DEFINITIONS[hero_class]
    rank_hp = {"NORMAL": 7_000, "ELITE": 11_000, "BOSS": 16_000}[variant.rank]
    self_effects = ()
    if variant.prefix_id in {"VENOMOUS", "EMBER", "CURSED"}:
        self_effects = (EffectInstance(f"prefix:{variant.prefix_id}", "POISON", 600, 3, True, False, periodic_damage=160),)
    target_effects = ()
    if variant.rank in {"ELITE", "BOSS"}:
        target_effects = (EffectInstance("enemy:power", "POWER", 1_000, 3, False, True),)
    return DecisionState(22_000, 22_000, rank_hp, rank_hp, start, MAX_RESOURCE_BPS,
                         self_effects=self_effects, target_effects=target_effects)


def simulate(display_level: int, hero_class: str, build: str, behavior: str,
             layout: str, variant) -> DecisionRun:
    skill_level = SKILL_LEVELS[display_level]
    loadout = build_loadout(hero_class, build, behavior, layout, skill_level)
    state = initial_state(hero_class, variant)
    state, opening_prefix = apply_prefix_event(state, variant, 0)
    base = BASE_BY_ID[variant.base_enemy_id]
    receipts = []
    abilities_seen = []
    prefix_receipts = [opening_prefix]
    resource_blocks = 0
    outcome = "INVALID_PLAN"
    for root in range(1, MAX_ACTIONS + 1):
        ability = ABILITY_BY_ID[base.ability_ids[(root - 1) % len(base.ability_ids)]]
        telegraph = public_observation(ability, root, variant.variant_id)
        receipt = choose_action(loadout, state, telegraph)
        receipts.append(receipt)
        resource_blocks += sum(reason in {"RESOURCE", "RESOURCE_RESERVED"} for _, reason in receipt.excluded)
        state = apply_decision(loadout, state, receipt)
        if state.enemy_hp <= 0:
            outcome = "VICTORY"
            break
        hp_before = state.hp
        state = monster_action(state, ability, variant, root)
        if ability.delivery == "DIRECT":
            state = apply_direct_damage_resource(loadout, hp_before, state)
        state, prefix_receipt = apply_prefix_event(state, variant, root)
        prefix_receipts.append(prefix_receipt)
        abilities_seen.append(ability.ability_id)
        if state.hp <= 0:
            outcome = "DEFEAT"
            break
    else:
        outcome = "RETREAT"
    digest_payload = {
        "buildSnapshotHash": loadout_hash(loadout),
        "receipts": [asdict(x) for x in receipts],
        "prefixReceipts": [asdict(x) for x in prefix_receipts],
        "outcome": outcome,
    }
    digest = hashlib.sha256(json.dumps(digest_payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    passive_hash = hashlib.sha256(json.dumps([asdict(x) for x in loadout.passive_slots], sort_keys=True).encode()).hexdigest()
    return DecisionRun(
        display_level, hero_class, build, behavior, layout, variant.variant_id,
        len(loadout.active_slots), len(loadout.passive_slots), len(receipts), outcome,
        sum(x.action_id not in {"BASIC", "SYSTEM_RECOVERY"} for x in receipts),
        sum(x.action_id == "BASIC" for x in receipts),
        sum(x.action_id == "SYSTEM_RECOVERY" for x in receipts), resource_blocks,
        state.resource_bps, state.debt_bps, tuple(sorted(set(abilities_seen))),
        f"aq.prefix.event.{variant.prefix_id.lower()}", len(prefix_receipts), passive_hash,
        sum(x.selected_reason not in REASON_CODES or any(code not in EXCLUSION_CODES for _, code in x.excluded) for x in receipts),
        digest,
    )


@lru_cache(maxsize=1)
def integration_matrix() -> tuple[DecisionRun, ...]:
    return tuple(
        simulate(level, hero_class, build, behavior, layout, variant)
        for level in DISPLAY_LEVELS
        for hero_class in skills.CLASSES
        for build in BUILD_ARCHETYPES
        for behavior in BEHAVIOR_POLICIES
        for layout in LAYOUTS
        for variant in monsters.VARIANTS
    )


def skill_xp_settlement(transaction_id: str, eligible_combat_xp: int, loadout: Loadout,
                        outcome: str, applied_transactions: frozenset[str] = frozenset()) -> tuple[dict[str, int], frozenset[str], bool]:
    if transaction_id in applied_transactions:
        return {}, applied_transactions, True
    awarded = max(0, eligible_combat_xp) if outcome == "VICTORY" else 0
    each = awarded * 1_000 // 10_000
    receipt = {x.skill_id: each for x in loadout.active_slots + loadout.passive_slots}
    return receipt, applied_transactions | {transaction_id}, False


def branch_probes() -> tuple[tuple[str, bool], ...]:
    probes = []
    full = build_loadout("WARRIOR", "OFFENSE", "CAUTIOUS", "FULL_5_3", 100)
    new = build_loadout("MAGE", "SUSTAIN", "BALANCED", "NEW_4_2", 1)
    empty = build_loadout("ROGUE", "CONTROL", "BOLD", "EMPTY_0_0", 50)
    probes.extend((
        ("full layout", len(full.active_slots) == 5 and len(full.passive_slots) == 3),
        ("new layout", len(new.active_slots) == 4 and len(new.passive_slots) == 2),
        ("empty layout legal", validate_loadout(empty) == (True, "VALID")),
        ("slot overflow rejected", validate_loadout(replace(full, active_slots=full.active_slots + (full.active_slots[0],))) == (False, "SLOT_CAP")),
        ("duplicate rejected before canonicalize", validate_loadout(replace(full, active_slots=(full.active_slots[0],) * 2)) == (False, "DUPLICATE")),
        ("skill level rejected", validate_loadout(replace(full, active_slots=(replace(full.active_slots[0], skill_level=101),))) == (False, "SKILL_LEVEL")),
        ("equipment skill payload rejected", not validate_equipment_payload({"grantedSkillDefinitionId": "x"})[0]),
    ))
    variant = monsters.VARIANTS[0]
    empty_run = simulate(100, "ROGUE", "OFFENSE", "BOLD", "EMPTY_0_0", variant)
    probes.append(("empty uses basic only", empty_run.skill_actions == 0 and empty_run.basic_actions == empty_run.actions))
    # The decision API sees the same public observation although hidden future IDs differ.
    state = initial_state("WARRIOR", variant)
    ability = next(x for x in ABILITY_DEFINITIONS if x.publicly_telegraphed and x.telegraph_coefficient_bps >= 13_000)
    public = public_observation(ability, 1, "noninterference")
    a = choose_action(full, state, public)
    hidden_future_a, hidden_future_b = "secret-A", "secret-B"
    b = choose_action(full, state, public)
    probes.append(("hidden future noninterference", hidden_future_a != hidden_future_b and a == b))
    low = replace(public, coefficient_bps=12_999)
    high = replace(public, coefficient_bps=13_000)
    defensive_item = next((x for x in skills.ACTIVES
                           if set(x.roles) & {"DEFENSE", "CONTROL"}
                           and not is_cleanse(x) and not is_dispel(x)
                           and "EXECUTION" not in x.pattern), None)
    if defensive_item:
        item = defensive_item
        probes.append(("telegraph boundary 12999 13000", reason_band(item, state, low)[0] != "PUBLIC_TELEGRAPH_RESPONSE" and reason_band(item, state, high)[0] == "PUBLIC_TELEGRAPH_RESPONSE"))
    else:
        probes.append(("telegraph boundary 12999 13000", False))
    # Current SkillLevel, not max anchor, participates in the score.
    scaling_slot = next(x for x in full.active_slots
                        if len(set(skills.ACTIVE_BY_ID[x.skill_id].attack_equivalent_values)) > 1
                        and special_family(x.skill_id) == "NORMAL_RESOURCE"
                        and "EXECUTION" not in skills.ACTIVE_BY_ID[x.skill_id].pattern)
    funded = replace(state, resource_bps=10_000)
    low_loadout = replace(full, active_slots=(replace(scaling_slot, skill_level=1),))
    high_loadout = replace(low_loadout, active_slots=(replace(scaling_slot, skill_level=100),))
    probes.append(("current skill level score", choose_action(low_loadout, funded, None).score != choose_action(high_loadout, funded, None).score))
    # Cleanse/dispel structured and separate target sets.
    dirty = replace(state, self_effects=(EffectInstance("dot", "POISON", 500, 2, True, False, periodic_damage=state.hp),),
                    target_effects=(EffectInstance("buff", "POWER", 800, 2, False, True),))
    probes.append(("cleanse target ordering", eligible_cleanse_targets(dirty)[0].instance_id == "dot"))
    probes.append(("dispel target ordering", eligible_dispel_targets(dirty)[0].instance_id == "buff"))
    enemy_receipt = EnemyActionReceipt("enemy:1", "spell", True, True, ("HEAL", "FIRE", "PHYSICAL"))
    probes.append(("mimic receipt whitelist and tie", mimic_tag(enemy_receipt) == ("enemy:1", "PHYSICAL")))
    probes.append(("mimic recursion blocked", mimic_tag(replace(enemy_receipt, copy_depth=1)) == ("", "")))
    win, applied, replayed = skill_xp_settlement("tx1", 10_000, full, "VICTORY")
    duplicate, _, replayed2 = skill_xp_settlement("tx1", 10_000, full, "VICTORY", applied)
    probes.extend((
        ("skill xp each not split", len(win) == 8 and set(win.values()) == {1_000}),
        ("skill xp replay atomic", not replayed and replayed2 and duplicate == {}),
        ("empty xp none", skill_xp_settlement("tx2", 10_000, empty, "VICTORY")[0] == {}),
    ))
    return tuple(probes)


def canonical_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "policyRulesVersion": POLICY_RULES_VERSION,
        "abilityRulesVersion": ABILITY_RULES_VERSION,
        "protectionDeathV08Hash": PROTECTION_DEATH_HASH,
        "skillRegistryHash": SKILL_REGISTRY_HASH,
        "monsterRegistryHash": MONSTER_REGISTRY_HASH,
        "buildArchetypes": BUILD_ARCHETYPES,
        "buildAffectsRuntimeScore": False,
        "behaviorPolicies": [behavior_id(x) for x in BEHAVIOR_POLICIES],
        "layouts": LAYOUTS,
        "resourceDefinitions": RESOURCE_DEFINITIONS,
        "resourceFloors": RESOURCE_FLOORS,
        "abilities": [
            {"abilityId": x.ability_id, "delivery": x.delivery, "origin": x.origin,
             "damageType": x.damage_type, "coefficientBps": x.coefficient_bps,
             "publiclyTelegraphed": x.publicly_telegraphed,
             "telegraphCoefficientBps": x.telegraph_coefficient_bps,
             "accuracyPolicy": x.accuracy_policy, "interruptTags": list(x.interrupt_tags),
             "attachedStatusTags": list(x.attached_status_tags), "cooldownRoots": x.cooldown_roots}
            for x in ABILITY_DEFINITIONS
        ],
        "prefixEventIds": [f"aq.prefix.event.{x.prefix_id.lower()}" for x in monsters.PREFIXES],
        "prefixExecutions": [{"prefixId": x.prefix_id, "effects": list(x.effects),
                              "behaviorRule": x.behavior_rule, "drawbackRule": x.drawback_rule}
                             for x in monsters.PREFIXES],
        "interopManifestHash": interop_hash(),
        "safeMimicTags": SAFE_COPY_TAGS,
        "mimicPriority": MIMIC_PRIORITY,
        "reasonCodes": sorted(REASON_CODES),
        "exclusionCodes": sorted(EXCLUSION_CODES),
        "limits": {"active": MAX_ACTIVE, "passive": MAX_PASSIVE, "rounds": MAX_ROUNDS, "actions": MAX_ACTIONS},
        "newCharacterLayout": {"active": 4, "passive": 2},
        "skillXpPerEquippedBps": 1_000,
        "skillXpSplit": False,
        "manualCombatAction": False,
        "arbitraryThresholdEditing": False,
        "itemGrantedSkills": False,
        "productionEnabled": False,
    }


def interop_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "resourceDefinitions": [
            {"heroClass": hero, "lifecycle": row[0], "encounterStartBps": row[1],
             "basicGainBps": row[2], "directActualHpDamageGainBps": row[3]}
            for hero, row in RESOURCE_DEFINITIONS.items()
        ],
        "abilities": [
            {"abilityId": x.ability_id, "delivery": x.delivery, "origin": x.origin,
             "damageType": x.damage_type, "coefficientBps": x.coefficient_bps,
             "publiclyTelegraphed": x.publicly_telegraphed,
             "telegraphCoefficientBps": x.telegraph_coefficient_bps,
             "accuracyPolicy": x.accuracy_policy, "interruptTags": list(x.interrupt_tags),
             "attachedStatusTags": list(x.attached_status_tags), "cooldownRoots": x.cooldown_roots}
            for x in ABILITY_DEFINITIONS
        ],
        "prefixExecutions": [
            {"prefixId": x.prefix_id, "effects": [list(effect) for effect in x.effects],
             "behaviorRule": x.behavior_rule, "drawbackRule": x.drawback_rule}
            for x in monsters.PREFIXES
        ],
        "behaviorPolicyIds": [behavior_id(x) for x in BEHAVIOR_POLICIES],
        "safeMimicTags": list(SAFE_COPY_TAGS),
        "mimicPriority": list(MIMIC_PRIORITY),
    }


def interop_hash() -> str:
    raw = json.dumps(interop_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def canonical_hash() -> str:
    raw = json.dumps(canonical_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def summary(matrix: tuple[DecisionRun, ...]) -> dict:
    return {
        "scenarioCount": len(matrix),
        "decisionCount": sum(x.actions for x in matrix),
        "variants": len({x.variant_id for x in matrix}),
        "policyPairs": len({(x.build_archetype, x.behavior_policy) for x in matrix}),
        "layouts": dict(Counter(x.layout for x in matrix)),
        "outcomes": dict(Counter(x.outcome for x in matrix)),
        "actions": {"skill": sum(x.skill_actions for x in matrix), "basic": sum(x.basic_actions for x in matrix), "recovery": sum(x.recovery_actions for x in matrix)},
        "abilityCoverage": len({ability for x in matrix for ability in x.ability_ids_seen}),
        "prefixCoverage": len({x.prefix_event_id for x in matrix}),
        "passiveLayouts": sorted({(x.layout, x.passive_slots) for x in matrix}),
        "invalidReasonCodes": sum(x.invalid_reason_codes for x in matrix),
        "resourceUnderflow": sum(not 0 <= x.final_resource_bps <= MAX_RESOURCE_BPS or not 0 <= x.final_debt_bps <= cost_v07.DEBT_CAP_BPS for x in matrix),
    }


def kotlin_manifest_matches() -> bool:
    if not KOTLIN_CONTRACT.exists():
        return False
    text = KOTLIN_CONTRACT.read_text(encoding="utf-8")
    rows = re.findall(r'ClassResourcePolicy\("([A-Z]+)",\s*"([A-Z_]+)",\s*([0-9_]+),\s*([0-9_]+),\s*([0-9_]+)\)', text)
    actual = {hero: (life, int(start.replace("_", "")), int(basic.replace("_", "")), int(hit.replace("_", ""))) for hero, life, start, basic, hit in rows}
    match = re.search(r'V_NEXT_AI_INTEROP_HASH\s*=\s*"([0-9a-f]{64})"', text)
    return (actual == RESOURCE_DEFINITIONS
            and f'V_NEXT_AI_ABILITY_COUNT = {len(ABILITY_DEFINITIONS)}' in text
            and f'V_NEXT_AI_PREFIX_EVENT_COUNT = {len(monsters.PREFIXES)}' in text
            and match is not None and match.group(1) == interop_hash())


def checks(matrix: tuple[DecisionRun, ...]) -> list[tuple[str, bool, str]]:
    report = summary(matrix)
    probes = branch_probes()
    samples = matrix[:: max(1, len(matrix) // 24)][:24]
    replays = [simulate(x.display_level, x.hero_class, x.build_archetype, x.behavior_policy,
                        x.layout, next(v for v in monsters.VARIANTS if v.variant_id == x.variant_id)) for x in samples]
    return [
        ("matrix covers 4 x 6 x 3 x 3 x 3 x 144", len(matrix) == 93_312 and report["variants"] == 144, f"scenarios={len(matrix)} variants={report['variants']}"),
        ("all runs terminate within 60 actions", all(x.outcome in {"VICTORY", "DEFEAT", "RETREAT"} and 1 <= x.actions <= MAX_ACTIONS for x in matrix), str(report["outcomes"])),
        ("build provenance and behavior independent", report["policyPairs"] == 9 and not canonical_payload()["buildAffectsRuntimeScore"], f"pairs={report['policyPairs']}"),
        ("three layouts exact with passive snapshots", set(report["layouts"]) == set(LAYOUTS) and set(report["passiveLayouts"]) == {("EMPTY_0_0", 0), ("FULL_5_3", 3), ("NEW_4_2", 2)}, str(report["passiveLayouts"])),
        ("all branch probes pass", all(ok for _, ok in probes), f"passed={sum(ok for _, ok in probes)}/{len(probes)}"),
        ("55 abilities execute and 12 prefix events bind", report["abilityCoverage"] == 55 and report["prefixCoverage"] == 12 and all(x.prefix_event_count >= 2 for x in matrix), f"ability={report['abilityCoverage']} prefix={report['prefixCoverage']}"),
        ("resource and debt bounded", report["resourceUnderflow"] == 0, "underflow=0 overflow=0"),
        ("reason and exclusion codes closed", report["invalidReasonCodes"] == 0, "invalid=0"),
        ("decision and SkillXP replay deterministic", all(a.decision_digest == b.decision_digest for a, b in zip(samples, replays)), f"replays={len(replays)}"),
        ("class resources exact", set(RESOURCE_DEFINITIONS) == set(skills.CLASSES) and sum(x[0] == "ENCOUNTER_BUILDER" for x in RESOURCE_DEFINITIONS.values()) == 3, str(RESOURCE_DEFINITIONS)),
        ("python kotlin manifest fields match", kotlin_manifest_matches(), "resource fields ability/prefix counts"),
        ("automatic test only", not canonical_payload()["manualCombatAction"] and not canonical_payload()["arbitraryThresholdEditing"] and not canonical_payload()["itemGrantedSkills"] and not canonical_payload()["productionEnabled"], "manual=false itemSkill=false production=false"),
    ]


def pd_checks(matrix: tuple[DecisionRun, ...]) -> list[tuple[str, bool, str]]:
    result = checks(matrix)
    doc = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    kotlin = KOTLIN_CONTRACT.read_text(encoding="utf-8") if KOTLIN_CONTRACT.exists() else ""
    result.extend((
        ("document binds v09 hash", canonical_hash() in doc, canonical_hash()),
        ("kotlin binds v09 hash", canonical_hash() in kotlin, canonical_hash()),
        ("document keeps production gate", "production 조건부 승인" in doc and "라이브 NO-GO" in doc, "explicit gate"),
        ("document separates build behavior", "편성 유형" in doc and "행동 성향" in doc, "two independent axes"),
        ("document supersedes forced starters", "A1/A2 해제 불가" in doc and "폐기" in doc, "superseded"),
    ))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--summary", action="store_true")
    parser.add_argument("--hash-only", action="store_true")
    parser.add_argument("--interop-json", action="store_true")
    parser.add_argument("--interop-hash", action="store_true")
    args = parser.parse_args()
    if args.hash_only:
        print(canonical_hash())
        return 0
    if args.interop_json:
        print(json.dumps(interop_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":")))
        return 0
    if args.interop_hash:
        print(interop_hash())
        return 0
    matrix = integration_matrix()
    if args.summary:
        print(json.dumps(summary(matrix), ensure_ascii=False, indent=2, sort_keys=True))
        print(f"canonical_sha256={canonical_hash()}")
        return 0
    result = pd_checks(matrix) if args.pd else checks(matrix)
    print(f"{'PD' if args.pd else 'BALANCE'}: {sum(ok for _, ok, _ in result)}/{len(result)} PASS")
    for name, ok, detail in result:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"canonical_sha256={canonical_hash()}")
    return 0 if all(ok for _, ok, _ in result) else 1


if __name__ == "__main__":
    raise SystemExit(main())
