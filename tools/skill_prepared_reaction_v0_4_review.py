#!/usr/bin/env python3
"""Dedicated prepared, delayed, and reaction handler audit for AlarmQuest v0.4.

The v0.3 resolver deliberately used conservative role fallbacks.  This module
binds the 19 action-economy-sensitive definitions to explicit trigger,
expiration, interrupt, refund, accuracy, and ledger contracts.  It remains a
test-only planning executable and never reads or mutates live characters.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, replace
from functools import lru_cache
from pathlib import Path

import base_combat_six_classes_v1_5_review as combat
import integrated_combat_simulation_v0_1_review as integrated
import monster_prefix_variants_v0_2_review as monsters
import skill_effect_resolver_v0_3_review as resolver
import skill_registry_240_v0_2_review as skills


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_PREPARED_REACTION_HANDLERS_v0.4.md"
RULES_VERSION = "aq.skill-prepared-reaction.v0.4"
DISPLAY_LEVELS = resolver.DISPLAY_LEVELS
SKILL_LEVELS = resolver.SKILL_LEVELS
REACTION_TOKEN_CAP = 1
DELAY_TOKEN_CAP = 1
DELAY_STABILITY_CAP_BPS = 1_500
TACTIC_SHARED_CAP = 2
STARFALL_CANCEL_BASE_BPS = 3_500
STARFALL_CANCEL_FLOOR_BPS = 2_000
NO_REFUND_BPS = 0


@dataclass(frozen=True)
class HandlerContract:
    skill_id: str
    kind: str
    handler_id: str
    trigger_id: str
    expiry_id: str
    actor_roots: int
    token_cap: int
    interrupt_policy: str
    cancel_base_bps: int
    cancel_floor_bps: int
    final_hit_bps: int
    crit_mode: str
    refund_bps: int
    ledger_id: str
    ledger_cap: int
    fixed_rule: str


def active(
    skill_id: str,
    handler_id: str,
    trigger_id: str,
    expiry_id: str,
    actor_roots: int,
    interrupt_policy: str = "NONE",
    cancel_base_bps: int = 0,
    cancel_floor_bps: int = 0,
    final_hit_bps: int = 0,
    crit_mode: str = "REGISTRY",
    ledger_id: str = "NONE",
    ledger_cap: int = 0,
    fixed_rule: str = "NONE",
) -> HandlerContract:
    return HandlerContract(
        skill_id, "ACTIVE", handler_id, trigger_id, expiry_id, actor_roots,
        REACTION_TOKEN_CAP if "REACTION" in handler_id or "PREPAID" in handler_id else DELAY_TOKEN_CAP,
        interrupt_policy, cancel_base_bps, cancel_floor_bps, final_hit_bps,
        crit_mode, NO_REFUND_BPS, ledger_id, ledger_cap, fixed_rule,
    )


def passive(skill_id: str, trigger_id: str, fixed_rule: str) -> HandlerContract:
    return HandlerContract(
        skill_id, "PASSIVE", "DELAY_STABILITY", trigger_id, "HOST_ACTION_END",
        0, 0, "NONE", 0, 0, 0, "NONE", 0,
        "aq.stack.wave3.delay_stability", 1, fixed_rule,
    )


CONTRACTS = (
    active(
        "aq.skill.cleric.w3.sanctuary", "PREPAID_MITIGATION", "NEXT_ENEMY_VALID_HIT",
        "FIRST_VALID_HIT_OR_ENCOUNTER_END", 1,
        fixed_rule="현재 공격·회복 0, 다음 유효 피격 피해 modifier 적용 후 token 소비",
    ),
    active(
        "aq.skill.cleric.w3.soulanchor", "PREPAID_LETHAL_DELAY", "NEXT_LETHAL_INCOMING_PACKET",
        "NEXT_HERO_ROOT_END_OR_ENCOUNTER_END", 1,
        fixed_rule="cap까지만 lethal 피해를 유예하고 다음 자기 root 종료에 미회복분 정산",
    ),
    active(
        "aq.skill.common.w3.counterprep", "PREPAID_MISS_REACTION", "NEXT_ENEMY_DIRECT_MISS",
        "NEXT_ENEMY_ACTION_END", 1, final_hit_bps=9_500, crit_mode="FORBIDDEN",
        fixed_rule="적 공격 명중이면 packet 0, MISS면 선지불 counter packet 1회",
    ),
    active(
        "aq.skill.common.w8.retreatprep", "PREPAID_EVASION_WINDOW", "NEXT_TWO_ENEMY_DIRECTS",
        "TWO_DIRECTS_OR_ENCOUNTER_END", 1, ledger_id="TACTIC_SHARED", ledger_cap=TACTIC_SHARED_CAP,
        fixed_rule="고정명중 제외, enemy DIRECT 최대2회 EVA 보정, 추가 행동 0",
    ),
    active(
        "aq.skill.external.w3.namelessscabbard", "DELAYED_OWN_ROOT_PACKET", "NEXT_HERO_ROOT",
        "RESOLVE_OR_INTERRUPT", 2, "STAGGER_OR_SILENCE", final_hit_bps=9_900, crit_mode="FORBIDDEN",
        fixed_rule="준비 중 STAGGER·SILENCE 취소, 비용·cooldown 반환 없음",
    ),
    active(
        "aq.skill.external.w4.lichclock", "DELAYED_TARGET_ROOT_PACKET", "THIRD_TARGET_ROOT_START",
        "THIRD_TARGET_ROOT_OR_CURSE_REMOVED_OR_TARGET_DEATH", 1, "CURSE_REMOVED",
        crit_mode="FORBIDDEN",
        fixed_rule="CURSE 부착 성공 시 3번째 대상 root 시작에 무명중 packet, 정화·사망 취소",
    ),
    active(
        "aq.skill.mage.w3.starfallvow", "DELAYED_CAST_PACKET", "NEXT_HERO_ROOT",
        "RESOLVE_OR_INTERRUPT", 2, "INCOMING_HIT_OR_STAGGER_OR_SILENCE",
        STARFALL_CANCEL_BASE_BPS, STARFALL_CANCEL_FLOOR_BPS,
        fixed_rule="interrupt window당 keyed roll 1회, 안정 적용 후에도 취소 최소20%",
    ),
    active(
        "aq.skill.mage.w5.spellreversal", "PREPAID_SPELL_REACTION", "NEXT_ENEMY_SPELL_DIRECT",
        "NEXT_HERO_ROOT_START_OR_TRIGGER", 1, "NONE", crit_mode="FORBIDDEN",
        fixed_rule="SPELL DIRECT 피해-3000 후 실제 피해 이하 반사, 반사·상태·치명 재귀 금지",
    ),
    active(
        "aq.skill.monster.w8.wyverndescent", "DELAYED_FIXED_CRITICAL_PACKET", "NEXT_HERO_ROOT",
        "RESOLVE_OR_STAGGER", 2, "STAGGER", final_hit_bps=8_000, crit_mode="FIXED_VISUAL_NO_MULTIPLIER",
        fixed_rule="Hit80%, CRITICAL 표시는 고정하되 추가 1.5배를 곱하지 않음",
    ),
    active(
        "aq.skill.ranger.w3.animalwarning", "PREPAID_EVASION_REACTION", "NEXT_ENEMY_DIRECT",
        "NEXT_ENEMY_DIRECT_END", 1,
        fixed_rule="예고 계수13000 이상에서만 후보, EVA stat 보정, 고정명중에는 효과0",
    ),
    active(
        "aq.skill.ranger.w3.trapsetup", "PREPAID_CONTROL_REACTION", "NEXT_ENEMY_ACTION_START",
        "NEXT_ENEMY_ACTION_START", 1,
        fixed_rule="행동 시작에 STAGGER 적용을 시도하되 현재 적 행동을 추가로 삭제하지 않음",
    ),
    active(
        "aq.skill.rogue.w5.illusionstab", "TWO_ROOT_FEINT", "CURRENT_AND_NEXT_HERO_ROOT",
        "SECOND_PACKET_OR_TARGET_DEATH", 2,
        fixed_rule="첫 root 계수3000, 다음 root 강제 소비 후 성장 packet, 각각 독립 명중",
    ),
    active(
        "aq.skill.warrior.w2.preparedsmash", "DELAYED_OWN_ROOT_PACKET", "NEXT_HERO_ROOT",
        "RESOLVE_OR_PREPARATION_REMOVED", 2, "STAGGER_OR_SILENCE_OR_DISPEL",
        final_hit_bps=6_500,
        fixed_rule="준비 해제 시 실패, 최종 Hit65%, 비용·cooldown 반환 없음",
    ),
    active(
        "aq.skill.warrior.w3.countercut", "PREPAID_MISS_REACTION", "NEXT_ENEMY_DIRECT_MISS",
        "NEXT_ENEMY_ACTION_END", 1, final_hit_bps=9_500, crit_mode="FORBIDDEN",
        fixed_rule="적 공격 MISS에만 counter packet, 명중이면 피해0",
    ),
    active(
        "aq.skill.warrior.w6.lastvanguard", "LOW_HP_PREPAID_HIT_REACTION", "DIRECT_HIT_WITHIN_TWO_ENEMY_ROOTS",
        "TWO_ENEMY_ROOTS_OR_TRIGGER", 1, crit_mode="FORBIDDEN",
        fixed_rule="HP25% 이하 후보, 생존한 DIRECT 피격 1회 반격, 2 enemy root 만료, 치명 불가",
    ),
    active(
        "aq.skill.world.w8.deadletter", "DELAYED_TARGET_ROOT_PACKET", "THIRD_TARGET_ROOT_START",
        "THIRD_TARGET_ROOT_OR_CURSE_REMOVED_OR_TARGET_DEATH", 1, "CURSE_REMOVED",
        fixed_rule="CURSE5000 부착 성공 시 3 enemy root 뒤 packet, 정화·사망 취소",
    ),
    passive(
        "aq.skill.external.w3.bellpatience", "HOST_DELAYED_ACTIVE",
        "직업 안정 Passive와 같은 그룹 HIGHEST, 즉발 Active 효과0",
    ),
    passive(
        "aq.skill.mage.w3.concentration", "HOST_DELAYED_SPELL",
        "지연 주문에만 적용, 같은 그룹 HIGHEST, 최종 취소율 최소2000",
    ),
    passive(
        "aq.skill.ranger.w3.hunterpatience", "HOST_DELAYED_ACTIVE",
        "지연 Active에만 적용, 같은 그룹 HIGHEST, 취소 면역 불가",
    ),
)
CONTRACT_BY_ID = {item.skill_id: item for item in CONTRACTS}
ACTIVE_CONTRACTS = tuple(item for item in CONTRACTS if item.kind == "ACTIVE")
PASSIVE_CONTRACTS = tuple(item for item in CONTRACTS if item.kind == "PASSIVE")

ALL_CLASS_FOR_SKILL = {
    "aq.skill.common.w3.counterprep": "WARRIOR",
    "aq.skill.common.w8.retreatprep": "RANGER",
    "aq.skill.external.w3.namelessscabbard": "MAGE",
    "aq.skill.external.w4.lichclock": "MAGE",
    "aq.skill.monster.w8.wyverndescent": "RANGER",
    "aq.skill.world.w8.deadletter": "MAGE",
}
PASSIVE_HOST = {
    "aq.skill.external.w3.bellpatience": "aq.skill.external.w3.namelessscabbard",
    "aq.skill.mage.w3.concentration": "aq.skill.mage.w3.starfallvow",
    "aq.skill.ranger.w3.hunterpatience": "aq.skill.monster.w8.wyverndescent",
}
HOST_PASSIVES = {
    "aq.skill.external.w3.namelessscabbard": ("aq.skill.external.w3.bellpatience",),
    "aq.skill.mage.w3.starfallvow": (
        "aq.skill.mage.w3.concentration", "aq.skill.external.w3.bellpatience",
    ),
    "aq.skill.monster.w8.wyverndescent": (
        "aq.skill.ranger.w3.hunterpatience", "aq.skill.external.w3.bellpatience",
    ),
    "aq.skill.warrior.w2.preparedsmash": ("aq.skill.external.w3.bellpatience",),
}


@dataclass(frozen=True)
class IntegrationSample:
    skill_id: str
    kind: str
    skill_level: int
    display_level: int
    hero_class: str
    variant_id: str
    rank: str
    profile: str
    loadout_digest: str
    triggered: bool
    interrupted: bool
    actor_roots: int
    extra_actions: int
    token_peak: int
    resource_refund_bps: int
    stability_bps: int
    resolved_coefficient_bps: int
    fallback_coefficient_bps: int
    ledger_use: int


@dataclass(frozen=True)
class BranchProbe:
    skill_id: str
    branch: str
    passed: bool


def owner_class(skill_id: str, kind: str) -> str:
    item = (skills.ACTIVE_BY_ID if kind == "ACTIVE" else skills.PASSIVE_BY_ID)[skill_id]
    if item.owner_scope != "ALL":
        return item.owner_scope
    return ALL_CLASS_FOR_SKILL.get(skill_id, "MAGE")


def snapshot_digest(snapshot: skills.SkillLoadoutSnapshot) -> str:
    raw = json.dumps(asdict(snapshot), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()[:16]


@lru_cache(maxsize=None)
def handler_loadout(skill_id: str, kind: str, skill_level: int) -> skills.SkillLoadoutSnapshot:
    hero_class = owner_class(skill_id, kind)
    host_id = skill_id if kind == "ACTIVE" else PASSIVE_HOST[skill_id]
    active_selected = [skills.ACTIVE_BY_ID[host_id]]
    active_pool = [
        item for item in skills.accessible(hero_class, "ACTIVE")
        if item.definition_id != host_id and not resolver.dedicated_tokens(item)
    ]
    active_pool.sort(key=lambda item: (-resolver.role_score(item, "OFFENSE"), item.definition_id))
    active_selected.extend(active_pool[:4])

    requested_passives = list(HOST_PASSIVES.get(host_id, ()))
    if kind == "PASSIVE" and skill_id not in requested_passives:
        requested_passives.insert(0, skill_id)
    passive_selected = [
        skills.PASSIVE_BY_ID[item_id] for item_id in requested_passives
        if skills.PASSIVE_BY_ID[item_id].owner_scope in {"ALL", hero_class}
    ]
    passive_pool = [
        item for item in skills.accessible(hero_class, "PASSIVE")
        if item.definition_id not in {selected.definition_id for selected in passive_selected}
        and item.definition_id not in CONTRACT_BY_ID
    ]
    passive_pool.sort(key=lambda item: (-resolver.role_score(item, "SUSTAIN"), item.definition_id))
    passive_selected.extend(passive_pool[: 3 - len(passive_selected)])
    return skills.commit_loadout(
        hero_class,
        {item.definition_id: skill_level for item in active_selected[:5]},
        {item.definition_id: skill_level for item in passive_selected[:3]},
        behavior_policy_id="aq.behavior.prepared.v1",
    )


def is_delayed_spell(active_skill_id: str) -> bool:
    return active_skill_id == "aq.skill.mage.w3.starfallvow"


def stability_bps(snapshot: skills.SkillLoadoutSnapshot, active_skill_id: str) -> int:
    values = []
    for entry in snapshot.passive_slots:
        if entry.skill_id not in {item.skill_id for item in PASSIVE_CONTRACTS}:
            continue
        item = skills.PASSIVE_BY_ID[entry.skill_id]
        contract = CONTRACT_BY_ID[entry.skill_id]
        if contract.trigger_id == "HOST_DELAYED_SPELL" and not is_delayed_spell(active_skill_id):
            continue
        values.append(abs(skills.value_at(item.anchor_values, entry.skill_level)))
    return min(DELAY_STABILITY_CAP_BPS, max(values, default=0))


def enemy_interrupt_tags(monster_def, variant, random) -> set[str]:
    joined = " ".join(monster_def.ability_ids).lower()
    stagger_tokens = ("stomp", "charge", "crush", "grasp", "net", "dive", "pounce", "slam")
    silence_tokens = ("bell", "sound", "curse", "spore")
    result = set()
    if any(token in joined for token in stagger_tokens) and random.chance(0.45):
        result.add("STAGGER")
    if any(token in joined for token in silence_tokens) and random.chance(0.35):
        result.add("SILENCE")
    if variant.prefix_id == "ANCIENT" and random.chance(0.20):
        result.add("STAGGER")
    return result


def hit_equivalent(coefficient: int, hit_bps: int, random) -> int:
    return coefficient if random.next_int(0, 10_000) < hit_bps else 0


def base_hit_bps(hero_stats, monster_stats) -> int:
    return combat.v13.core.hit_bps(hero_stats, monster_stats)


def fallback_equivalent(item, hero_stats, monster_stats, random) -> int:
    coefficient = skills.value_at(item.attack_equivalent_values, 100)
    if coefficient <= 0:
        return 0
    hit = base_hit_bps(hero_stats, monster_stats)
    if item.final_hit_policy == "FIXED_9500":
        hit = 9_500
    elif item.final_hit_policy == "CLAMP_MIN":
        hit = 6_500
    elif item.final_hit_policy == "CLAMP_MAX":
        hit = 9_900
    return hit_equivalent(coefficient, hit, random) // max(1, item.action_width)


def simulate_contract(contract: HandlerContract, display_level: int, variant) -> IntegrationSample:
    level = SKILL_LEVELS[display_level]
    snapshot = handler_loadout(contract.skill_id, contract.kind, level)
    hero_class = snapshot.hero_class
    source = combat.source_for_display(hero_class, display_level, "BASE")
    hero_stats = combat.derive_hero(source).combatant
    monster_def = resolver.BASE_ENEMY_BY_ID[variant.base_enemy_id]
    raw_monster = combat.monster_for(source, variant.profile, variant.rank)
    monster_stats = integrated.apply_monster_variant(raw_monster, monster_def, variant, hero_stats.attack_type, display_level)
    seed = integrated.stable_seed(RULES_VERSION, contract.skill_id, str(display_level), variant.variant_id)
    random = combat.v13.core.DeterministicRandom.for_event(seed, 404, "PREPARED_REACTION_V04", variant.variant_id)
    interrupts = enemy_interrupt_tags(monster_def, variant, random)
    enemy_is_spell = monster_stats.attack_type == "MAGIC"
    enemy_hit = random.chance(combat.v13.core.hit_bps(monster_stats, hero_stats) / 10_000)
    item = (skills.ACTIVE_BY_ID if contract.kind == "ACTIVE" else skills.PASSIVE_BY_ID)[contract.skill_id]
    host_id = contract.skill_id if contract.kind == "ACTIVE" else PASSIVE_HOST[contract.skill_id]
    stability = stability_bps(snapshot, host_id)
    triggered = False
    interrupted = False
    resolved = 0
    ledger_use = 1 if contract.ledger_id == "TACTIC_SHARED" else 0

    if contract.kind == "PASSIVE":
        triggered = stability > 0
    else:
        coefficient = skills.value_at(item.anchor_values, level)
        attack_equivalent = skills.value_at(item.attack_equivalent_values, level)
        handler = contract.handler_id
        if handler == "PREPAID_MISS_REACTION":
            triggered = not enemy_hit
            resolved = hit_equivalent(attack_equivalent, contract.final_hit_bps, random) if triggered else 0
        elif handler == "LOW_HP_PREPAID_HIT_REACTION":
            lethal_threat = variant.rank == "BOSS" or variant.prefix_id in {"FEROCIOUS", "HUNGRY"}
            triggered = enemy_hit and not lethal_threat
            resolved = hit_equivalent(attack_equivalent, base_hit_bps(hero_stats, monster_stats), random) if triggered else 0
        elif handler == "DELAYED_CAST_PACKET":
            cancel_bps = max(contract.cancel_floor_bps, contract.cancel_base_bps - stability)
            interrupted = (enemy_hit or bool(interrupts)) and random.chance(cancel_bps / 10_000)
            triggered = not interrupted
            resolved = hit_equivalent(attack_equivalent, base_hit_bps(hero_stats, monster_stats), random) if triggered else 0
        elif handler == "DELAYED_OWN_ROOT_PACKET":
            interrupted = bool(interrupts.intersection({"STAGGER", "SILENCE"}))
            triggered = not interrupted
            hit = contract.final_hit_bps or base_hit_bps(hero_stats, monster_stats)
            resolved = hit_equivalent(attack_equivalent, hit, random) if triggered else 0
        elif handler == "DELAYED_FIXED_CRITICAL_PACKET":
            interrupted = "STAGGER" in interrupts
            triggered = not interrupted
            resolved = hit_equivalent(coefficient, contract.final_hit_bps, random) if triggered else 0
        elif handler == "TWO_ROOT_FEINT":
            first = hit_equivalent(3_000, base_hit_bps(hero_stats, monster_stats), random)
            second = hit_equivalent(coefficient, base_hit_bps(hero_stats, monster_stats), random)
            triggered = True
            resolved = first + second
        elif handler == "DELAYED_TARGET_ROOT_PACKET":
            apply_bps = 5_000 if contract.skill_id.endswith("deadletter") else 5_500
            curse_applied = random.chance(apply_bps / 10_000) and "CURSE" not in monster_def.status_immunities
            triggered = curse_applied
            resolved = coefficient if curse_applied else 0
        elif handler == "PREPAID_SPELL_REACTION":
            triggered = enemy_is_spell and enemy_hit
            resolved = min(coefficient, monster_stats.action_coefficient_bps) if triggered else 0
        elif handler == "PREPAID_EVASION_REACTION":
            predicted = monster_stats.action_coefficient_bps >= 13_000
            adjusted = replace(hero_stats, evasion=integrated.mul(hero_stats.evasion, coefficient))
            triggered = predicted
            enemy_hit = random.chance(combat.v13.core.hit_bps(monster_stats, adjusted) / 10_000) if predicted else enemy_hit
        elif handler == "PREPAID_EVASION_WINDOW":
            adjusted = replace(hero_stats, evasion=integrated.mul(hero_stats.evasion, coefficient))
            triggered = any(
                not random.chance(combat.v13.core.hit_bps(monster_stats, adjusted) / 10_000)
                for _ in range(2)
            )
        elif handler == "PREPAID_CONTROL_REACTION":
            apply_bps = coefficient * (5_000 if variant.rank == "BOSS" else 10_000) // 10_000
            triggered = "STAGGER" not in monster_def.status_immunities and random.chance(apply_bps / 10_000)
        elif handler == "PREPAID_MITIGATION":
            triggered = enemy_hit
        elif handler == "PREPAID_LETHAL_DELAY":
            lethal_threat = variant.rank == "BOSS" or variant.prefix_id in {"FEROCIOUS", "HUNGRY"}
            triggered = enemy_hit and lethal_threat
        else:
            raise AssertionError(handler)

    fallback = 0
    if contract.kind == "ACTIVE":
        fallback = fallback_equivalent(item, hero_stats, monster_stats, random)
    return IntegrationSample(
        contract.skill_id, contract.kind, level, display_level, hero_class,
        variant.variant_id, variant.rank, variant.profile, snapshot_digest(snapshot),
        triggered, interrupted, contract.actor_roots, 0,
        1 if contract.kind == "ACTIVE" else 0, contract.refund_bps,
        stability, resolved // max(1, contract.actor_roots), fallback,
        ledger_use,
    )


@lru_cache(maxsize=1)
def integration_matrix() -> tuple[IntegrationSample, ...]:
    return tuple(
        simulate_contract(contract, display_level, variant)
        for contract in CONTRACTS
        for display_level in DISPLAY_LEVELS
        for variant in monsters.VARIANTS
    )


def branch_probes() -> tuple[BranchProbe, ...]:
    result = []
    for contract in CONTRACTS:
        if contract.kind == "ACTIVE":
            success = contract.actor_roots in {1, 2} and contract.token_cap == 1
            failure = contract.expiry_id != "" and contract.refund_bps == 0
            cap = contract.token_cap == 1 and contract.ledger_cap in {0, 2}
        else:
            success = contract.handler_id == "DELAY_STABILITY" and contract.trigger_id.startswith("HOST_DELAYED")
            failure = contract.trigger_id in {"HOST_DELAYED_ACTIVE", "HOST_DELAYED_SPELL"}
            cap = contract.ledger_id == "aq.stack.wave3.delay_stability" and contract.ledger_cap == 1
        result.extend((
            BranchProbe(contract.skill_id, "SUCCESS", success),
            BranchProbe(contract.skill_id, "NON_TRIGGER_OR_INTERRUPT", failure),
            BranchProbe(contract.skill_id, "CAP_AND_REFUND", cap),
        ))
    return tuple(result)


def canonical_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "effectResolverV03Hash": resolver.canonical_hash(),
        "skillRegistryHash": skills.canonical_hash(),
        "monsterRegistryHash": monsters.canonical_hash(),
        "contracts": [asdict(item) for item in CONTRACTS],
        "caps": {
            "reactionToken": REACTION_TOKEN_CAP,
            "delayToken": DELAY_TOKEN_CAP,
            "delayStabilityBps": DELAY_STABILITY_CAP_BPS,
            "tacticShared": TACTIC_SHARED_CAP,
            "starfallCancelBaseBps": STARFALL_CANCEL_BASE_BPS,
            "starfallCancelFloorBps": STARFALL_CANCEL_FLOOR_BPS,
        },
        "resourceRefundBps": NO_REFUND_BPS,
        "extraActionCount": 0,
        "manualCombatAction": False,
        "itemGrantedSkills": False,
        "productionEnabled": False,
    }


def canonical_hash() -> str:
    raw = json.dumps(canonical_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def rate(rows, predicate) -> float:
    return sum(bool(predicate(row)) for row in rows) / max(1, len(rows))


def summary(matrix: tuple[IntegrationSample, ...]) -> dict:
    by_skill = defaultdict(list)
    for row in matrix:
        by_skill[row.skill_id].append(row)
    active_rows = [row for row in matrix if row.kind == "ACTIVE"]
    return {
        "scenarioCount": len(matrix),
        "branchProbeCount": len(branch_probes()),
        "contracts": {"active": len(ACTIVE_CONTRACTS), "passive": len(PASSIVE_CONTRACTS)},
        "triggerRate": {skill_id: rate(rows, lambda row: row.triggered) for skill_id, rows in sorted(by_skill.items())},
        "interruptRate": {skill_id: rate(rows, lambda row: row.interrupted) for skill_id, rows in sorted(by_skill.items()) if any(row.kind == "ACTIVE" for row in rows)},
        "meanRootCoefficient": {
            skill_id: round(statistics.fmean(row.resolved_coefficient_bps for row in rows), 1)
            for skill_id, rows in sorted(by_skill.items()) if any(row.kind == "ACTIVE" for row in rows)
        },
        "fallbackMeanRootCoefficient": {
            skill_id: round(statistics.fmean(row.fallback_coefficient_bps for row in rows), 1)
            for skill_id, rows in sorted(by_skill.items()) if any(row.kind == "ACTIVE" for row in rows)
        },
        "maxima": {
            "actorRoots": max(row.actor_roots for row in active_rows),
            "extraActions": max(row.extra_actions for row in matrix),
            "tokenPeak": max(row.token_peak for row in matrix),
            "refundBps": max(row.resource_refund_bps for row in matrix),
            "stabilityBps": max(row.stability_bps for row in matrix),
            "tacticLedgerUse": max(row.ledger_use for row in matrix),
            "resolvedRootCoefficient": max(row.resolved_coefficient_bps for row in active_rows),
        },
        "loadoutCount": len({row.loadout_digest for row in matrix}),
        "variantCount": len({row.variant_id for row in matrix}),
    }


@lru_cache(maxsize=1)
def v03_regression():
    return resolver.run_matrix()


def checks(matrix: tuple[IntegrationSample, ...]) -> list[tuple[str, bool, str]]:
    contract_ids = {item.skill_id for item in CONTRACTS}
    expected_ids = {
        item.skill_id for item in resolver.COVERAGE
        if item.support == "CONSERVATIVE_FALLBACK"
        and set(item.dedicated_tokens).intersection({"PREPAID", "DELAYED", "PREPARED", "TWO_ROOT", "REFLECT"})
    }
    probes = branch_probes()
    report = summary(matrix)
    v03 = v03_regression()
    stability_contracts = [item for item in PASSIVE_CONTRACTS]
    starfall_rows = [row for row in matrix if row.skill_id == "aq.skill.mage.w3.starfallvow"]
    wyvern_rows = [row for row in matrix if row.skill_id == "aq.skill.monster.w8.wyverndescent"]
    return [
        ("19 dedicated contracts exact", contract_ids == expected_ids and len(ACTIVE_CONTRACTS) == 16 and len(PASSIVE_CONTRACTS) == 3,
         f"active={len(ACTIVE_CONTRACTS)}, passive={len(PASSIVE_CONTRACTS)}"),
        ("three branch probes per contract", len(probes) == 57 and all(item.passed for item in probes),
         f"passed={sum(item.passed for item in probes)}/{len(probes)}"),
        ("handler matrix exact", len(matrix) == 19 * 4 * 144 and report["variantCount"] == 144,
         f"scenarios={len(matrix)}, variants={report['variantCount']}"),
        ("every scenario has actual 5 plus 3 snapshot", all(
            len(handler_loadout(row.skill_id, row.kind, row.skill_level).active_slots) == 5
            and len(handler_loadout(row.skill_id, row.kind, row.skill_level).passive_slots) == 3
            for row in matrix), f"loadoutDigests={report['loadoutCount']}"),
        ("no extra actions token overflow or refunds", all(
            row.extra_actions == 0 and row.token_peak <= 1 and row.resource_refund_bps == 0 for row in matrix),
         str(report["maxima"])),
        ("actor roots bounded", all(row.actor_roots in {0, 1, 2} for row in matrix),
         f"max={report['maxima']['actorRoots']}"),
        ("delay stability highest only and capped", len(stability_contracts) == 3
         and all(item.ledger_id == "aq.stack.wave3.delay_stability" for item in stability_contracts)
         and report["maxima"]["stabilityBps"] <= DELAY_STABILITY_CAP_BPS,
         f"max={report['maxima']['stabilityBps']}"),
        ("starfall keeps cancel risk", 0 < rate(starfall_rows, lambda row: row.interrupted) < 0.35
         and max(row.stability_bps for row in starfall_rows) == 1_500,
         f"cancel={rate(starfall_rows, lambda row: row.interrupted):.3f}"),
        ("wyvern fixed critical budget bounded", max(row.resolved_coefficient_bps for row in wyvern_rows) <= 8_000,
         f"maxRootEquivalent={max(row.resolved_coefficient_bps for row in wyvern_rows)}"),
        ("v03 full battle regression still bounded", len(v03) == 10_368
         and max(row.rounds for row in v03) <= resolver.MAX_ROUNDS
         and max(row.max_pending for row in v03) <= resolver.MAX_PENDING_EFFECTS,
         f"battles={len(v03)}, maxRounds={max(row.rounds for row in v03)}"),
        ("hashes bind v03 skill and monster", canonical_payload()["effectResolverV03Hash"] == resolver.canonical_hash()
         and canonical_payload()["skillRegistryHash"] == skills.canonical_hash()
         and canonical_payload()["monsterRegistryHash"] == monsters.canonical_hash(),
         f"v03={resolver.canonical_hash()[:12]}"),
        ("automatic test-only contract", not canonical_payload()["manualCombatAction"]
         and not canonical_payload()["itemGrantedSkills"] and not canonical_payload()["productionEnabled"],
         "manual=false itemSkill=false production=false"),
    ]


def pd_checks(matrix: tuple[IntegrationSample, ...]) -> list[tuple[str, bool, str]]:
    result = checks(matrix)
    document = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    result.append(("document binds v04 hash", canonical_hash() in document, canonical_hash()))
    result.append(("document preserves conditional production gate", "production 조건부 승인" in document and "라이브 NO-GO" in document,
                   "explicit gate"))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()
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
