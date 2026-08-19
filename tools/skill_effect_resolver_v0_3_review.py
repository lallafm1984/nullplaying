#!/usr/bin/env python3
"""Test-only vNext skill effect resolver and PD audit for AlarmQuest.

This is a planning executable, not the production resolver.  It binds the
approved 240-skill registry to the 144 monster variants, executes real 5+3
loadouts through a bounded automatic round loop, and makes the unsupported
semantic tail explicit instead of pretending that pattern names are code.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import statistics
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field, replace
from functools import lru_cache
from pathlib import Path

import base_combat_six_classes_v1_5_review as combat
import integrated_combat_simulation_v0_1_review as integrated
import monster_prefix_variants_v0_2_review as monsters
import skill_registry_240_v0_2_review as skills
import skill_collection_level9999_v0_1_review as progression


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_EFFECT_RESOLVER_v0.3.md"
RULES_VERSION = "aq.skill-effect-resolver.v0.3"
DISPLAY_LEVELS = (1, 100, 1_000, 9_999)
SKILL_LEVELS = {1: 1, 100: 50, 1_000: 85, 9_999: 100}
BEHAVIOR_PROFILES = ("OFFENSE", "CONTROL", "SUSTAIN")
MAX_ROUNDS = 30
MAX_RESOURCE_BPS = 10_000
START_RESOURCE_BPS = 7_000
BASIC_RESOURCE_GAIN_BPS = 1_500
MAX_HOSTILE_STATUSES = 6
MAX_NEW_STATUSES_PER_ACTION = 2
MAX_PENDING_EFFECTS = 1
MAX_SHARED_LEDGER_USES = 1
SKILL_REGISTRY_HASH = skills.canonical_hash()
MONSTER_REGISTRY_HASH = monsters.canonical_hash()
BASE_ENEMY_BY_ID = {item.enemy_id: item for item in monsters.BASE_ENEMIES}

HOSTILE_STATUS_TAGS = (
    "POISON", "BURN", "BLEED", "CHILL", "CURSE", "SHOCK", "MARK",
    "STAGGER", "SILENCE", "BIND", "SUPPRESSED", "POWER_SUPPRESSED",
    "AIM_DISRUPTED", "PHYSICAL_EXPOSED", "MAGICAL_EXPOSED",
)
DOT_BPS = {"POISON": 250, "BURN": 300, "BLEED": 220}
CONTROL_TAGS = {"STAGGER", "SILENCE", "BIND", "SUPPRESSED", "POWER_SUPPRESSED"}

# These semantics need a named production handler.  The test resolver still
# executes them through a bounded conservative family fallback so every skill
# can participate in contract probes without inventing its exact final rule.
DEDICATED_TOKENS = (
    "SELF_DAMAGE", "CONSUME", "DETONAT", "DEBT", "MIMIC", "COPY", "STEAL",
    "CLEANSE", "SHIELD_TO", "RESOURCE_TO", "PREPAID", "DELAYED", "PREPARED",
    "TWO_ROOT", "EXECUTION", "REVIVE", "REFLECT", "LAST_STAND",
)

HANDLER_PRIORITY = (
    ("EXECUTION", "EXECUTION"),
    ("DELAYED", "PREPARED_PACKET"),
    ("HEAL_ATTACK", "HEAL_ATTACK"),
    ("DEFENSE_ATTACK", "GUARD_ATTACK"),
    ("CONTROL_ATTACK", "CONTROL_ATTACK"),
    ("STATUS_ATTACK", "STATUS_ATTACK"),
    ("PRECISION_ATTACK", "PRECISION_ATTACK"),
    ("RESOURCE_ATTACK", "RESOURCE_ATTACK"),
    ("ATTACK", "DIRECT_ATTACK"),
    ("HEAL", "HEAL"),
    ("DEFENSE", "DEFENSE"),
    ("CONTROL", "CONTROL"),
    ("STATUS", "STATUS"),
    ("PRECISION", "PRECISION"),
    ("RESOURCE", "RESOURCE"),
    ("EXPEDITION", "EXPEDITION"),
    ("UTILITY", "UTILITY"),
)

STYLE_WEIGHTS = {
    "OFFENSE": {"ATTACK": 8, "EXECUTION": 5, "PRECISION": 3, "DELAYED": 2, "RESOURCE": 1},
    "CONTROL": {"CONTROL": 8, "STATUS": 7, "PRECISION": 4, "ATTACK": 2, "RESOURCE": 1},
    "SUSTAIN": {"DEFENSE": 8, "HEAL": 8, "RESOURCE": 6, "ATTACK": 2, "PRECISION": 1},
}
ACTIVE_SLOT_HANDLERS = {
    "OFFENSE": ("DIRECT_ATTACK", "PRECISION_ATTACK", "STATUS_ATTACK", "RESOURCE_ATTACK", "EXECUTION"),
    "CONTROL": ("CONTROL_ATTACK", "STATUS_ATTACK", "PRECISION_ATTACK", "DIRECT_ATTACK", "CONTROL"),
    "SUSTAIN": ("DEFENSE", "HEAL", "RESOURCE", "GUARD_ATTACK", "DIRECT_ATTACK"),
}
PASSIVE_SLOT_ROLES = {
    "OFFENSE": ("ATTACK", "PRECISION", "RESOURCE"),
    "CONTROL": ("STATUS", "PRECISION", "DEFENSE"),
    "SUSTAIN": ("DEFENSE", "HEAL", "RESOURCE"),
}


@dataclass(frozen=True)
class CoverageEntry:
    skill_id: str
    kind: str
    handler_id: str
    support: str
    dedicated_tokens: tuple[str, ...]


@dataclass(frozen=True)
class PendingEffect:
    skill_id: str
    skill_level: int
    remaining_roots: int
    coefficient_bps: int
    roles: tuple[str, ...]
    applies_tags: tuple[str, ...]
    duration_roots: int
    crit_eligible: bool
    final_hit_policy: str


@dataclass
class ActorState:
    hp: int
    max_hp: int
    resource_bps: int = START_RESOURCE_BPS
    shield: int = 0
    cooldowns: dict[str, int] = field(default_factory=dict)
    statuses: dict[str, int] = field(default_factory=dict)
    pending: PendingEffect | None = None
    shared_ledgers: dict[str, int] = field(default_factory=dict)


@dataclass(frozen=True)
class PassiveEffects:
    skill_ids: tuple[str, ...]
    attack_add_bps: int
    hit_add_bps: int
    incoming_reduction_bps: int
    resource_discount_bps: int
    status_apply_add_bps: int
    heal_add_bps: int
    shield_add_bps: int
    basic_resource_gain_bps: int
    execution_threshold_bps: int
    shared_ledger_keys: tuple[str, ...]


@dataclass(frozen=True)
class ActionEvent:
    round_index: int
    actor: str
    event_type: str
    skill_id: str
    handler_id: str
    hit: bool
    damage: int
    resource_before: int
    resource_after: int
    applied_statuses: tuple[str, ...] = ()


@dataclass(frozen=True)
class BattleResult:
    display_level: int
    hero_class: str
    behavior_profile: str
    variant_id: str
    monster_rank: str
    outcome: str
    rounds: int
    hero_hp: int
    hero_attempts: int
    hero_hits: int
    max_miss_chain: int
    resource_blocks: int
    status_damage_taken: int
    failure_reason: str
    max_hero_statuses: int
    max_monster_statuses: int
    max_pending: int
    event_digest: str


def handler_id(item) -> str:
    roles = set(item.roles)
    signatures = {
        "HEAL_ATTACK": {"HEAL", "ATTACK"},
        "DEFENSE_ATTACK": {"DEFENSE", "ATTACK"},
        "CONTROL_ATTACK": {"CONTROL", "ATTACK"},
        "STATUS_ATTACK": {"STATUS", "ATTACK"},
        "PRECISION_ATTACK": {"PRECISION", "ATTACK"},
        "RESOURCE_ATTACK": {"RESOURCE", "ATTACK"},
    }
    for key, required in signatures.items():
        if required <= roles:
            return dict(HANDLER_PRIORITY)[key]
    for role, handler in HANDLER_PRIORITY:
        if role in roles:
            return handler
    return "UTILITY"


def dedicated_tokens(item) -> tuple[str, ...]:
    fields = ("pattern", "growth_field", "candidate_rule_id", "condition_id", "fixed_tradeoff")
    text = " ".join(str(getattr(item, key, "")) for key in fields).upper()
    return tuple(token for token in DEDICATED_TOKENS if token in text)


def coverage_manifest() -> tuple[CoverageEntry, ...]:
    result = []
    for kind, catalog in (("ACTIVE", skills.ACTIVES), ("PASSIVE", skills.PASSIVES)):
        for item in catalog:
            tokens = dedicated_tokens(item)
            result.append(CoverageEntry(
                skill_id=item.definition_id,
                kind=kind,
                handler_id=handler_id(item),
                support="CONSERVATIVE_FALLBACK" if tokens else "GENERIC_READY",
                dedicated_tokens=tokens,
            ))
    return tuple(result)


COVERAGE = coverage_manifest()
COVERAGE_BY_ID = {item.skill_id: item for item in COVERAGE}


def clamp(value: int, minimum: int, maximum: int) -> int:
    return max(minimum, min(maximum, value))


def scale(value: int, bps: int) -> int:
    return max(0, (value * bps + 5_000) // 10_000)


def passive_value(item, skill_level: int) -> int:
    return abs(skills.value_at(item.anchor_values, skill_level))


@lru_cache(maxsize=None)
def compile_passives(entries: tuple[skills.SkillLevelEntry, ...]) -> PassiveEffects:
    resolved = [(skills.PASSIVE_BY_ID[entry.skill_id], entry.skill_level) for entry in entries]
    by_group: dict[str, list[tuple[object, int]]] = defaultdict(list)
    for item, level in resolved:
        by_group[item.stack_group].append((item, passive_value(item, level)))

    effective: list[tuple[object, int]] = []
    ledgers = []
    for group, members in sorted(by_group.items()):
        policy = members[0][0].stack_policy
        if policy in {"UNIQUE", "HIGHEST"}:
            effective.append(max(members, key=lambda pair: (pair[1], pair[0].definition_id)))
        elif policy == "SHARED_LEDGER":
            effective.append(max(members, key=lambda pair: (pair[1], pair[0].definition_id)))
            ledgers.append(group)
        else:
            cap = min(item.stack_cap_bps for item, _ in members)
            total = min(cap, sum(value for _, value in members))
            representative = max(members, key=lambda pair: (pair[1], pair[0].definition_id))[0]
            effective.append((representative, total))

    totals = Counter()
    for item, value in effective:
        roles = set(item.roles)
        if "ATTACK" in roles:
            totals["attack"] += value
        if "PRECISION" in roles:
            totals["hit"] += value
        if "DEFENSE" in roles:
            totals["defense"] += value
            totals["shield"] += value // 2
        if "RESOURCE" in roles:
            totals["resource"] += value
            totals["basic_gain"] += min(300, value // 3)
        if "STATUS" in roles or "CONTROL" in roles:
            totals["status"] += value
        if "HEAL" in roles:
            totals["heal"] += value
        if "EXECUTION" in roles:
            totals["execution"] += value

    return PassiveEffects(
        skill_ids=tuple(sorted(entry.skill_id for entry in entries)),
        attack_add_bps=min(skills.PASSIVE_ACTION_ADD_CAP_BPS, totals["attack"]),
        hit_add_bps=min(skills.FINAL_HIT_ADD_CAP_BPS, totals["hit"]),
        incoming_reduction_bps=min(skills.INCOMING_REDUCTION_CAP_BPS, totals["defense"]),
        resource_discount_bps=min(skills.PASSIVE_RESOURCE_DISCOUNT_CAP_BPS, totals["resource"]),
        status_apply_add_bps=min(2_000, totals["status"]),
        heal_add_bps=min(2_000, totals["heal"]),
        shield_add_bps=min(1_500, totals["shield"]),
        basic_resource_gain_bps=min(500, totals["basic_gain"]),
        execution_threshold_bps=min(1_000, totals["execution"]),
        shared_ledger_keys=tuple(sorted(ledgers)),
    )


def role_score(item, profile: str) -> int:
    weights = STYLE_WEIGHTS[profile]
    score = sum(weights.get(role, 0) for role in item.roles) * 100_000
    if hasattr(item, "attack_equivalent_values"):
        score += max(item.attack_equivalent_values) * 3 - item.resource_cost_bps
    else:
        score += min(10_000, max(abs(value) for value in item.anchor_values))
    # Prefer common-kernel skills when otherwise equal, while still allowing
    # dedicated fallbacks where their role is materially stronger.
    if not dedicated_tokens(item):
        score += 1_000
    return score


@lru_cache(maxsize=None)
def build_loadout(hero_class: str, profile: str, skill_level: int) -> skills.SkillLoadoutSnapshot:
    active_pool = sorted(
        skills.accessible(hero_class, "ACTIVE"), key=lambda item: (-role_score(item, profile), item.definition_id)
    )
    active = []
    for desired in ACTIVE_SLOT_HANDLERS[profile]:
        candidate = next((item for item in active_pool if item not in active and handler_id(item) == desired), None)
        if candidate is None:
            candidate = next(item for item in active_pool if item not in active)
        active.append(candidate)

    passive_pool = sorted(
        skills.accessible(hero_class, "PASSIVE"), key=lambda item: (-role_score(item, profile), item.definition_id)
    )
    passive = []
    for desired in PASSIVE_SLOT_ROLES[profile]:
        candidate = next((item for item in passive_pool if item not in passive and desired in item.roles), None)
        if candidate is None:
            candidate = next(item for item in passive_pool if item not in passive)
        passive.append(candidate)
    return skills.commit_loadout(
        hero_class,
        {item.definition_id: skill_level for item in active},
        {item.definition_id: skill_level for item in passive},
        behavior_policy_id=f"aq.behavior.{profile.lower()}.v1",
    )


def infer_statuses(item) -> tuple[str, ...]:
    explicit = [tag for tag in item.applies_tags if tag in HOSTILE_STATUS_TAGS]
    if explicit:
        return tuple(explicit[:MAX_NEW_STATUSES_PER_ACTION])
    text = f"{item.pattern} {item.growth_field} {item.candidate_rule_id}".upper()
    found = [tag for tag in HOSTILE_STATUS_TAGS if tag in text]
    if not found and "CONTROL" in item.roles:
        found = ["STAGGER"]
    return tuple(found[:MAX_NEW_STATUSES_PER_ACTION])


def apply_statuses(
    target: ActorState,
    tags: tuple[str, ...],
    duration: int,
    immunities: set[str],
    rank: str,
    random,
    apply_add_bps: int,
) -> tuple[str, ...]:
    applied = []
    for tag in tags[:MAX_NEW_STATUSES_PER_ACTION]:
        if tag in immunities:
            continue
        base = 5_500 + apply_add_bps
        if tag in CONTROL_TAGS and rank == "BOSS":
            base = base * 5_000 // 10_000
        if not random.chance(clamp(base, 1_000, skills.STATUS_APPLY_FINAL_CAP_BPS) / 10_000):
            continue
        if tag not in target.statuses and len(target.statuses) >= MAX_HOSTILE_STATUSES:
            continue
        target.statuses[tag] = max(target.statuses.get(tag, 0), max(1, duration))
        applied.append(tag)
    return tuple(applied)


def tick_statuses(state: ActorState) -> int:
    damage = sum(scale(state.max_hp, DOT_BPS.get(tag, 0)) for tag in state.statuses)
    if damage:
        state.hp = max(0, state.hp - damage)
    expired = []
    for tag, remaining in state.statuses.items():
        if remaining <= 1:
            expired.append(tag)
        else:
            state.statuses[tag] = remaining - 1
    for tag in expired:
        del state.statuses[tag]
    return damage


def decrement_cooldowns(state: ActorState) -> None:
    for skill_id in tuple(state.cooldowns):
        remaining = state.cooldowns[skill_id] - 1
        if remaining <= 0:
            del state.cooldowns[skill_id]
        else:
            state.cooldowns[skill_id] = remaining


def damage_packet(attacker, defender, coefficient_bps: int, hit_add_bps: int, crit_eligible: bool,
                  final_hit_policy: str, random, outgoing_bps: int = 10_000) -> tuple[int, bool]:
    hit = clamp(combat.v13.core.hit_bps(attacker, defender) + hit_add_bps, 5_000, 9_900)
    if final_hit_policy == "FIXED_9500":
        hit = 9_500
    elif final_hit_policy == "CLAMP_MIN":
        hit = 6_500
    elif final_hit_policy == "CLAMP_MAX":
        hit = min(hit, 8_000)
    hit_roll = random.next_int(0, 10_000)
    crit_roll = random.next_int(0, 10_000)
    variance = random.next_int(9_500, 10_501)
    if hit_roll >= hit:
        return 0, False
    attack_power = attacker.physical_attack if attacker.attack_type == "PHYSICAL" else attacker.magical_attack
    damage = scale(attack_power, max(0, coefficient_bps))
    if crit_eligible and crit_roll < combat.v13.core.critical_bps(attacker, defender):
        damage = scale(damage, 15_000)
    damage = scale(damage, variance)
    damage = scale(damage, outgoing_bps)
    damage = max(1, scale(damage, 10_000 - combat.v13.core.mitigation_bps(attacker, defender)))
    return damage, True


def consume_damage(state: ActorState, damage: int) -> int:
    absorbed = min(state.shield, damage)
    state.shield -= absorbed
    actual = max(0, damage - absorbed)
    state.hp = max(0, state.hp - actual)
    return actual


def candidate_eligible(item, level: int, hero: ActorState, monster: ActorState) -> tuple[bool, str]:
    if item.definition_id in hero.cooldowns:
        return False, "COOLDOWN"
    if item.action_width > 1 and hero.pending is not None:
        return False, "PENDING_FULL"
    cost = skills.final_resource_cost(item, level, 0)
    debt_allowed = "DEBT" in dedicated_tokens(item) and hero.shared_ledgers.get("DEBT", 0) < MAX_SHARED_LEDGER_USES
    if cost > hero.resource_bps and not debt_allowed:
        return False, "RESOURCE"
    hp_bps = 10_000 * monster.hp // max(1, monster.max_hp)
    if "EXECUTION" in item.roles and hp_bps > 3_000:
        return False, "CONDITION"
    if "HEAL" in item.roles and "ATTACK" not in item.roles and hero.hp * 10_000 > hero.max_hp * 8_500:
        return False, "CONDITION"
    if "DEFENSE" in item.roles and "ATTACK" not in item.roles and hero.shield > scale(hero.max_hp, 1_500):
        return False, "CONDITION"
    if "RESOURCE" in item.roles and "ATTACK" not in item.roles and hero.resource_bps > 6_000:
        return False, "CONDITION"
    required = [tag for tag in item.consumes_tags if not tag.startswith("SELF_")]
    if required and not any(tag.split("_")[0] in monster.statuses for tag in required):
        return False, "CONDITION"
    return True, "READY"


def action_score(item, level: int, hero: ActorState, monster: ActorState, profile: str) -> int:
    coefficient = skills.value_at(item.attack_equivalent_values, level)
    score = coefficient * 10 + sum(STYLE_WEIGHTS[profile].get(role, 0) * 5_000 for role in item.roles)
    hero_hp_bps = 10_000 * hero.hp // max(1, hero.max_hp)
    monster_hp_bps = 10_000 * monster.hp // max(1, monster.max_hp)
    if "EXECUTION" in item.roles and monster_hp_bps <= 3_000:
        score += 200_000
    if "HEAL" in item.roles:
        score += max(0, 8_000 - hero_hp_bps) * 20
    if "DEFENSE" in item.roles and hero.shield == 0:
        score += 40_000
    if "STATUS" in item.roles and not monster.statuses:
        score += 35_000
    if "RESOURCE" in item.roles and hero.resource_bps <= 4_000:
        score += 50_000
    return score


def resolve_skill(
    item,
    level: int,
    round_index: int,
    hero: ActorState,
    monster: ActorState,
    hero_stats,
    monster_stats,
    monster_def,
    passive: PassiveEffects,
    random,
    force: bool = False,
) -> ActionEvent:
    before = hero.resource_bps
    cost = skills.final_resource_cost(item, level, passive.resource_discount_bps)
    if cost > hero.resource_bps and "DEBT" in dedicated_tokens(item):
        hero.shared_ledgers["DEBT"] = hero.shared_ledgers.get("DEBT", 0) + 1
        hero.resource_bps = 0
    else:
        hero.resource_bps = max(0, hero.resource_bps - cost)
    hero.cooldowns[item.definition_id] = item.cooldown_roots

    tokens = set(dedicated_tokens(item))
    if "SELF_DAMAGE" in tokens:
        consume_damage(hero, scale(hero.max_hp, 800))
    coefficient = clamp(skills.value_at(item.attack_equivalent_values, level) + passive.attack_add_bps, 0, 25_000)
    status_tags = infer_statuses(item)
    duration = item.duration_roots or 3
    if item.action_width > 1:
        hero.pending = PendingEffect(
            item.definition_id, level, item.action_width - 1, coefficient, item.roles,
            status_tags, duration, item.crit_eligible, item.final_hit_policy,
        )
        return ActionEvent(round_index, "HERO", "PREPARE", item.definition_id,
                           COVERAGE_BY_ID[item.definition_id].handler_id, False, 0, before, hero.resource_bps)

    consumed = 0
    for tag in item.consumes_tags:
        base = tag.removeprefix("SELF_").split("_")[0]
        target = hero if tag.startswith("SELF_") else monster
        if base in target.statuses:
            del target.statuses[base]
            consumed += 1
    if consumed:
        coefficient = min(25_000, coefficient + consumed * 1_000)

    if "HEAL" in item.roles:
        growth = abs(skills.value_at(item.anchor_values, level))
        heal_bps = clamp(500 + min(2_000, growth) + passive.heal_add_bps, 500, 3_500)
        hero.hp = min(hero.max_hp, hero.hp + scale(hero.max_hp, heal_bps))
    if "DEFENSE" in item.roles:
        growth = abs(skills.value_at(item.anchor_values, level))
        shield_bps = clamp(500 + min(1_500, growth) + passive.shield_add_bps, 500, 3_000)
        hero.shield = min(scale(hero.max_hp, 4_000), hero.shield + scale(hero.max_hp, shield_bps))
    if "RESOURCE" in item.roles:
        hero.resource_bps = min(MAX_RESOURCE_BPS, hero.resource_bps + 1_200)

    damage = 0
    hit = False
    if coefficient > 0:
        outgoing = 9_200 if "CURSE" in hero.statuses else 10_000
        if "PHYSICAL_EXPOSED" in monster.statuses and hero_stats.attack_type == "PHYSICAL":
            outgoing += 1_000
        if "MAGICAL_EXPOSED" in monster.statuses and hero_stats.attack_type == "MAGIC":
            outgoing += 1_000
        if "EXECUTION" in item.roles and monster.hp * 10_000 <= monster.max_hp * (3_000 + passive.execution_threshold_bps):
            outgoing += 2_000
        damage, hit = damage_packet(
            hero_stats, monster_stats, coefficient,
            passive.hit_add_bps + (700 if "PRECISION" in item.roles else 0),
            item.crit_eligible, item.final_hit_policy, random, outgoing,
        )
        consume_damage(monster, damage)
    applied = ()
    if force or hit or coefficient == 0:
        applied = apply_statuses(
            monster, status_tags, duration, set(monster_def.status_immunities),
            monster_def.rank, random, passive.status_apply_add_bps,
        )
    return ActionEvent(round_index, "HERO", "SKILL", item.definition_id,
                       COVERAGE_BY_ID[item.definition_id].handler_id, hit, damage,
                       before, hero.resource_bps, applied)


def resolve_pending(round_index: int, hero: ActorState, monster: ActorState, hero_stats, monster_stats,
                    monster_def, passive: PassiveEffects, random) -> ActionEvent:
    pending = hero.pending
    assert pending is not None
    if pending.remaining_roots > 1:
        hero.pending = replace(pending, remaining_roots=pending.remaining_roots - 1)
        return ActionEvent(round_index, "HERO", "WAIT", pending.skill_id, "PREPARED_PACKET",
                           False, 0, hero.resource_bps, hero.resource_bps)
    hero.pending = None
    damage, hit = damage_packet(
        hero_stats, monster_stats, pending.coefficient_bps,
        passive.hit_add_bps, pending.crit_eligible, pending.final_hit_policy, random,
        9_200 if "CURSE" in hero.statuses else 10_000,
    )
    consume_damage(monster, damage)
    applied = ()
    if hit:
        applied = apply_statuses(
            monster, pending.applies_tags, pending.duration_roots,
            set(monster_def.status_immunities), monster_def.rank, random,
            passive.status_apply_add_bps,
        )
    return ActionEvent(round_index, "HERO", "RESOLVE", pending.skill_id, "PREPARED_PACKET",
                       hit, damage, hero.resource_bps, hero.resource_bps, applied)


def monster_action(round_index: int, hero: ActorState, monster: ActorState, monster_stats,
                   hero_stats, variant, passive: PassiveEffects, random) -> ActionEvent:
    coefficient = monster_stats.action_coefficient_bps
    if variant.prefix_id == "HUNGRY" and monster.hp * 10_000 <= monster.max_hp * 3_500:
        coefficient += 1_200
    if "POWER_SUPPRESSED" in monster.statuses or "SUPPRESSED" in monster.statuses:
        coefficient = scale(coefficient, 9_000)
    damage, hit = damage_packet(monster_stats, hero_stats, coefficient, 0, True, "NORMAL", random)
    damage = scale(damage, 10_000 - passive.incoming_reduction_bps)
    actual = consume_damage(hero, damage)
    applied = []
    prefix_status = {
        "VENOMOUS": "POISON", "EMBER": "BURN", "FROSTBOUND": "CHILL", "CURSED": "CURSE",
    }.get(variant.prefix_id)
    if hit and prefix_status and random.chance(0.41):
        if prefix_status not in hero.statuses and len(hero.statuses) < MAX_HOSTILE_STATUSES:
            hero.statuses[prefix_status] = 3
            applied.append(prefix_status)
    return ActionEvent(round_index, "MONSTER", "BASIC", variant.variant_id, "MONSTER_BASIC",
                       hit, actual, 0, 0, tuple(applied))


def event_digest(events: list[ActionEvent]) -> str:
    raw = json.dumps([asdict(event) for event in events], sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()[:16]


def apply_lineage_only(hero):
    lineage = integrated.LINEAGE_ENVELOPES["BALANCED"]
    return replace(
        hero,
        max_hp=integrated.mul(hero.max_hp, lineage.hp_bps),
        physical_attack=integrated.mul(hero.physical_attack, lineage.attack_bps),
        magical_attack=integrated.mul(hero.magical_attack, lineage.attack_bps),
        physical_defense=integrated.mul(hero.physical_defense, lineage.defense_bps),
        magical_resistance=integrated.mul(hero.magical_resistance, lineage.defense_bps),
        physical_accuracy=hero.physical_accuracy + lineage.accuracy_flat,
        magical_accuracy=hero.magical_accuracy + lineage.accuracy_flat,
        evasion=integrated.mul(hero.evasion, lineage.evasion_bps),
    )


def simulate_battle(display_level: int, hero_class: str, profile: str, variant) -> BattleResult:
    skill_level = SKILL_LEVELS[display_level]
    loadout = build_loadout(hero_class, profile, skill_level)
    passive = compile_passives(loadout.passive_slots)
    source = combat.source_for_display(hero_class, display_level, "BASE")
    hero_stats = apply_lineage_only(combat.derive_hero(source).combatant)
    monster_def = BASE_ENEMY_BY_ID[variant.base_enemy_id]
    raw_monster = combat.monster_for(source, variant.profile, variant.rank)
    monster_stats = integrated.apply_monster_variant(raw_monster, monster_def, variant, hero_stats.attack_type, display_level)
    hero = ActorState(hero_stats.max_hp, hero_stats.max_hp)
    monster = ActorState(monster_stats.max_hp, monster_stats.max_hp, resource_bps=0)
    for key in passive.shared_ledger_keys:
        hero.shared_ledgers[key] = 0
    seed = integrated.stable_seed(str(display_level), hero_class, profile, variant.variant_id, SKILL_REGISTRY_HASH)
    random = combat.v13.core.DeterministicRandom.for_event(seed, 303, "SKILL_EFFECT_V03", variant.variant_id)
    active_entries = tuple((skills.ACTIVE_BY_ID[entry.skill_id], entry.skill_level) for entry in loadout.active_slots)
    events: list[ActionEvent] = []
    attempts = hits = miss_chain = max_miss_chain = resource_blocks = status_damage = 0
    max_hero_statuses = max_monster_statuses = max_pending = 0
    order = ("HERO", "MONSTER") if hero_stats.speed >= monster_stats.speed else ("MONSTER", "HERO")
    outcome = "RETREAT"

    for round_index in range(1, MAX_ROUNDS + 1):
        for actor in order:
            if hero.hp <= 0 or monster.hp <= 0:
                break
            if actor == "HERO":
                status_damage += tick_statuses(hero)
                if hero.hp <= 0:
                    break
                decrement_cooldowns(hero)
                if hero.pending is not None:
                    event = resolve_pending(round_index, hero, monster, hero_stats, monster_stats, monster_def, passive, random)
                else:
                    ready = []
                    blocked_resource = False
                    for item, level in active_entries:
                        eligible, reason = candidate_eligible(item, level, hero, monster)
                        blocked_resource = blocked_resource or reason == "RESOURCE"
                        if eligible:
                            ready.append((item, level))
                    if ready:
                        item, level = sorted(
                            ready,
                            key=lambda pair: (-action_score(pair[0], pair[1], hero, monster, profile), pair[0].definition_id),
                        )[0]
                        event = resolve_skill(item, level, round_index, hero, monster, hero_stats,
                                              monster_stats, monster_def, passive, random)
                    else:
                        resource_blocks += int(blocked_resource)
                        before = hero.resource_bps
                        coefficient = 10_000 + passive.attack_add_bps
                        damage, hit = damage_packet(hero_stats, monster_stats, coefficient,
                                                    passive.hit_add_bps, True, "NORMAL", random,
                                                    9_200 if "CURSE" in hero.statuses else 10_000)
                        consume_damage(monster, damage)
                        hero.resource_bps = min(
                            MAX_RESOURCE_BPS,
                            hero.resource_bps + BASIC_RESOURCE_GAIN_BPS + passive.basic_resource_gain_bps,
                        )
                        event = ActionEvent(round_index, "HERO", "BASIC", "aq.action.basic",
                                            "BASIC", hit, damage, before, hero.resource_bps)
                events.append(event)
                if event.event_type in {"SKILL", "RESOLVE", "BASIC"} and event.damage >= 0:
                    attempts += 1
                    if event.hit:
                        hits += 1
                        miss_chain = 0
                    elif event.damage == 0 and event.event_type != "SKILL" or event.skill_id == "aq.action.basic":
                        miss_chain += 1
                        max_miss_chain = max(max_miss_chain, miss_chain)
            else:
                tick_statuses(monster)
                if monster.hp <= 0:
                    break
                if variant.prefix_id == "REGENERATING" and round_index % 3 == 0:
                    monster.hp = min(monster.max_hp, monster.hp + scale(monster.max_hp, 500))
                events.append(monster_action(round_index, hero, monster, monster_stats, hero_stats, variant, passive, random))
            max_hero_statuses = max(max_hero_statuses, len(hero.statuses))
            max_monster_statuses = max(max_monster_statuses, len(monster.statuses))
            max_pending = max(max_pending, int(hero.pending is not None))
        if monster.hp <= 0:
            outcome = "WIN"
            break
        if hero.hp <= 0:
            outcome = "LOSS"
            break
    else:
        round_index = MAX_ROUNDS

    if outcome == "WIN":
        reason = "NONE"
    elif status_damage >= scale(hero.max_hp, 1_000):
        reason = "STATUS_COLLAPSE"
    elif max_miss_chain >= 3:
        reason = "MISS_CHAIN"
    elif outcome == "RETREAT":
        reason = "TTK_TIMEOUT"
    elif resource_blocks >= 3:
        reason = "RESOURCE_EMPTY"
    else:
        reason = "DEFEAT_MAGIC" if monster_stats.attack_type == "MAGIC" else "DEFEAT_PHYSICAL"
    return BattleResult(
        display_level, hero_class, profile, variant.variant_id, variant.rank, outcome,
        round_index, hero.hp, attempts, hits, max_miss_chain, resource_blocks,
        status_damage, reason, max_hero_statuses, max_monster_statuses, max_pending,
        event_digest(events),
    )


def probe_all_skills() -> tuple[str, ...]:
    probed = []
    source = combat.source_for_display("WARRIOR", 100, "BASE")
    hero_stats = combat.derive_hero(source).combatant
    monster_stats = combat.monster_for(source, "STANDARD", "NORMAL")
    monster_def = monsters.BASE_ENEMIES[0]
    random = combat.v13.core.DeterministicRandom.for_event(9_101, 303, "SKILL_PROBE", "all")
    empty_passive = compile_passives(())
    for item in skills.ACTIVES:
        hero = ActorState(hero_stats.max_hp // 2, hero_stats.max_hp, resource_bps=MAX_RESOURCE_BPS)
        monster = ActorState(monster_stats.max_hp // 5, monster_stats.max_hp, resource_bps=0)
        for tag in item.consumes_tags:
            base = tag.removeprefix("SELF_").split("_")[0]
            (hero if tag.startswith("SELF_") else monster).statuses[base] = 3
        event = resolve_skill(item, 100, 1, hero, monster, hero_stats, monster_stats,
                              monster_def, empty_passive, random, force=True)
        if event.skill_id == item.definition_id and 0 <= hero.resource_bps <= MAX_RESOURCE_BPS:
            probed.append(item.definition_id)
    for item in skills.PASSIVES:
        effect = compile_passives((skills.SkillLevelEntry(item.definition_id, 100),))
        if item.definition_id in effect.skill_ids:
            probed.append(item.definition_id)
    return tuple(sorted(probed))


def run_matrix() -> tuple[BattleResult, ...]:
    return tuple(
        simulate_battle(level, hero_class, profile, variant)
        for level in DISPLAY_LEVELS
        for hero_class in skills.CLASSES
        for profile in BEHAVIOR_PROFILES
        for variant in monsters.VARIANTS
    )


def canonical_payload() -> dict:
    counts = Counter((item.kind, item.support) for item in COVERAGE)
    return {
        "rulesVersion": RULES_VERSION,
        "skillRegistryHash": SKILL_REGISTRY_HASH,
        "monsterRegistryHash": MONSTER_REGISTRY_HASH,
        "limits": {
            "maxRounds": MAX_ROUNDS,
            "maxResourceBps": MAX_RESOURCE_BPS,
            "startResourceBps": START_RESOURCE_BPS,
            "basicResourceGainBps": BASIC_RESOURCE_GAIN_BPS,
            "maxHostileStatuses": MAX_HOSTILE_STATUSES,
            "maxNewStatusesPerAction": MAX_NEW_STATUSES_PER_ACTION,
            "maxPendingEffects": MAX_PENDING_EFFECTS,
            "maxSharedLedgerUses": MAX_SHARED_LEDGER_USES,
        },
        "handlerPriority": HANDLER_PRIORITY,
        "dedicatedTokens": DEDICATED_TOKENS,
        "activeSlotHandlers": ACTIVE_SLOT_HANDLERS,
        "passiveSlotRoles": PASSIVE_SLOT_ROLES,
        "coverageCounts": {f"{kind}.{support}": value for (kind, support), value in sorted(counts.items())},
        "displayLevels": DISPLAY_LEVELS,
        "skillLevels": SKILL_LEVELS,
        "behaviorProfiles": BEHAVIOR_PROFILES,
        "activeSlots": 5,
        "passiveSlots": 3,
        "manualCombatAction": False,
        "itemGrantedSkills": False,
        "lossDeletesCharacter": False,
        "productionEnabled": False,
    }


def canonical_hash() -> str:
    raw = json.dumps(canonical_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def summary(matrix: tuple[BattleResult, ...]) -> dict:
    groups = defaultdict(list)
    for row in matrix:
        groups[(row.behavior_profile, row.monster_rank)].append(row)
    return {
        "sampleCount": len(matrix),
        "outcomes": dict(Counter(row.outcome for row in matrix)),
        "failureReasons": dict(Counter(row.failure_reason for row in matrix if row.failure_reason != "NONE")),
        "winByProfileRank": {
            f"{profile}.{rank}": sum(x.outcome == "WIN" for x in rows) / len(rows)
            for (profile, rank), rows in sorted(groups.items())
        },
        "medianRounds": statistics.median(row.rounds for row in matrix),
        "maxObserved": {
            "rounds": max(row.rounds for row in matrix),
            "heroStatuses": max(row.max_hero_statuses for row in matrix),
            "monsterStatuses": max(row.max_monster_statuses for row in matrix),
            "pending": max(row.max_pending for row in matrix),
        },
        "coverage": dict(Counter(item.support for item in COVERAGE)),
    }


def checks(matrix: tuple[BattleResult, ...]) -> list[tuple[str, bool, str]]:
    probed = probe_all_skills()
    expected_skill_ids = {item.definition_id for item in skills.ACTIVES + skills.PASSIVES}
    loadouts = [
        build_loadout(hero_class, profile, SKILL_LEVELS[level])
        for level in DISPLAY_LEVELS for hero_class in skills.CLASSES for profile in BEHAVIOR_PROFILES
    ]
    replay_targets = matrix[:: max(1, len(matrix) // 24)][:24]
    replays = [
        simulate_battle(row.display_level, row.hero_class, row.behavior_profile,
                        next(v for v in monsters.VARIANTS if v.variant_id == row.variant_id))
        for row in replay_targets
    ]
    dedicated = [item for item in COVERAGE if item.support == "CONSERVATIVE_FALLBACK"]
    generic = [item for item in COVERAGE if item.support == "GENERIC_READY"]
    observed_variants = {row.variant_id for row in matrix}
    observed_levels = {row.display_level for row in matrix}
    result = summary(matrix)
    return [
        ("240 skill contract probes exact", set(probed) == expected_skill_ids and len(probed) == 240,
         f"probed={len(probed)}, expected={len(expected_skill_ids)}"),
        ("coverage truthfully split", len(generic) + len(dedicated) == 240 and len(dedicated) > 0,
         f"generic={len(generic)}, dedicatedRequired={len(dedicated)}"),
        ("all handler families bounded", all(item.handler_id in {value for _, value in HANDLER_PRIORITY} for item in COVERAGE),
         f"handlers={len(set(item.handler_id for item in COVERAGE))}"),
        ("5 plus 3 actual loadouts", len(loadouts) == 72 and all(len(x.active_slots) == 5 and len(x.passive_slots) == 3 for x in loadouts),
         f"snapshots={len(loadouts)}"),
        ("full 144 monster matrix", len(matrix) == 4 * 6 * 3 * 144 and observed_variants == {x.variant_id for x in monsters.VARIANTS},
         f"samples={len(matrix)}, variants={len(observed_variants)}"),
        ("level 1 to 9999 represented", observed_levels == set(DISPLAY_LEVELS), str(sorted(observed_levels))),
        ("round loop always terminates", all(1 <= row.rounds <= MAX_ROUNDS for row in matrix),
         f"max={result['maxObserved']['rounds']}"),
        ("status pending caps never exceeded", all(
            row.max_hero_statuses <= MAX_HOSTILE_STATUSES and row.max_monster_statuses <= MAX_HOSTILE_STATUSES
            and row.max_pending <= MAX_PENDING_EFFECTS for row in matrix), str(result["maxObserved"])),
        ("event-derived failures only", set(result["failureReasons"]) <= {
            "RESOURCE_EMPTY", "MISS_CHAIN", "STATUS_COLLAPSE", "TTK_TIMEOUT", "DEFEAT_MAGIC", "DEFEAT_PHYSICAL"
        }, str(result["failureReasons"])),
        ("deterministic replay", all(a.event_digest == b.event_digest and a.outcome == b.outcome for a, b in zip(replay_targets, replays)),
         f"replays={len(replays)}"),
        ("hashes bind skill and monster registries", canonical_payload()["skillRegistryHash"] == SKILL_REGISTRY_HASH
         and canonical_payload()["monsterRegistryHash"] == MONSTER_REGISTRY_HASH,
         f"skill={SKILL_REGISTRY_HASH[:12]}, monster={MONSTER_REGISTRY_HASH[:12]}"),
        ("automatic safe-return test-only contract", not canonical_payload()["manualCombatAction"]
         and not canonical_payload()["itemGrantedSkills"] and not canonical_payload()["lossDeletesCharacter"]
         and not canonical_payload()["productionEnabled"], "manual=false itemSkill=false deletion=false production=false"),
    ]


def pd_checks(matrix: tuple[BattleResult, ...]) -> list[tuple[str, bool, str]]:
    result = checks(matrix)
    document = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    result.append(("document binds resolver hash", canonical_hash() in document, canonical_hash()))
    result.append(("document declares dedicated-handler gate", "production 전용 handler" in document and "조건부 승인" in document,
                   "explicit production gate"))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()
    matrix = run_matrix()
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
