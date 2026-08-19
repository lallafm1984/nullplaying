#!/usr/bin/env python3
"""AlarmQuest v0.6 status apply/refresh/resist/tick/cleanse audit.

Planning and test only.  This closes the lifecycle around the v0.5 atomic
consumer without touching live saves, Room, production Kotlin, or assets.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from dataclasses import asdict, dataclass, replace
from functools import lru_cache
from pathlib import Path

import base_combat_six_classes_v1_5_review as combat
import monster_prefix_variants_v0_2_review as monsters
import skill_effect_resolver_v0_3_review as resolver
import skill_registry_240_v0_2_review as skills
import skill_status_consumption_v0_5_review as consumer
import skill_wave4_dot_cleanse_diversity_execution_v0_1_review as wave4


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_STATUS_LIFECYCLE_v0.6.md"
KOTLIN_CONTRACT = ROOT / "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextStatusLifecycleHandlers.kt"
RULES_VERSION = "aq.skill-status-lifecycle.v0.6"
DISPLAY_LEVELS = resolver.DISPLAY_LEVELS
SKILL_LEVELS = resolver.SKILL_LEVELS
SCENARIO_MODES = ("SUCCESS_ROLL", "ROLL_FAIL", "MISS", "BARRIER", "IMMUNE_OR_CAP", "REFRESH")

HOSTILE_STATUS_CAP = 6
NEW_STATUS_PER_ACTION_CAP = 2
SHOCK_STACK_CAP = 2
CURSE_SUBTYPE_CAP = 3
APPLY_MIN_BPS = 1_000
APPLY_MAX_BPS = 9_500
BOSS_CONTROL_MULTIPLIER_BPS = 5_000
BOSS_CONTROL_MAX_BPS = 4_750
STATUS_RESIST_POINT_BPS = 40
SOURCE_APPLY_BONUS_CAP_BPS = 1_200
TARGET_GENERAL_RESIST_CAP_BPS = 1_800
TARGET_CONTROL_RESIST_CAP_BPS = 2_700
CLEANSE_MIN_BPS = 5_000
CLEANSE_MAX_BPS = 10_000
CLEANSE_PER_ROOT_CAP = 2

DEFAULT_APPLY = (9_000,) * 5
GUARANTEED = (10_000,) * 5
HOSTILE_TAGS = frozenset({
    "BLEED", "BURN", "POISON", "CHILL", "SHOCK", "CURSE", "MARK", "EXPOSED",
    "PHYSICAL_EXPOSED", "MAGICAL_EXPOSED", "STAGGER", "SILENCE", "BIND",
    "SUPPRESSED", "POWER_SUPPRESSED", "AIM_DISRUPTED", "SLIMED",
})
HARD_CONTROL_TAGS = frozenset({"STAGGER", "SILENCE", "BIND"})
DOT_TAGS = frozenset({"BLEED", "BURN", "POISON"})
IMMUNITY_ALIASES = {"AIM_DISRUPTED": "BLIND", "SLIMED": "BIND"}


@dataclass(frozen=True)
class Application:
    tag: str
    owner: str = "TARGET"
    apply_values: tuple[int, int, int, int, int] = DEFAULT_APPLY
    mode: str = "ROLL"
    duration: int = 1
    clock: str = "TARGET_ROOT_END"
    stack_policy: str = "UNIQUE_REFRESH_MAX"
    subtype: str = ""
    periodic_values: tuple[int, int, int, int, int] = (0, 0, 0, 0, 0)
    periodic_ticks: int = 0
    periodic_mode: str = "NONE"
    magnitude_values: tuple[int, int, int, int, int] = (0, 0, 0, 0, 0)
    cost_locked: bool = False
    linked_group: str = ""


@dataclass(frozen=True)
class SourceContract:
    skill_id: str
    name_ko: str
    source_kind: str
    applications: tuple[Application, ...]
    hit_required: bool = True
    barrier_policy: str = "BLOCK_PACKAGE"
    candidate_rule: str = "ALWAYS"
    apply_modifier_rule: str = "NONE"
    provisional: bool = False
    fixed_rule: str = ""


def anchors(skill_id: str) -> tuple[int, int, int, int, int]:
    return tuple(skills.ACTIVE_BY_ID[skill_id].anchor_values)


def magnitude(skill_id: str) -> tuple[int, int, int, int, int]:
    return tuple(skills.ACTIVE_BY_ID[skill_id].anchor_values)


def app(tag: str, **kwargs) -> Application:
    return Application(tag=tag, **kwargs)


def src(skill_id: str, applications, **kwargs) -> SourceContract:
    item = skills.ACTIVE_BY_ID.get(skill_id)
    name = item.name_ko if item else kwargs.pop("name_ko")
    kind = "PROVISIONAL" if kwargs.get("provisional") else "PLAYER"
    return SourceContract(skill_id, name, kind, tuple(applications), **kwargs)


SOURCES = (
    # Explicit registry applications.
    src("aq.skill.cleric.w2.blessing", [app("BLESSING", owner="SELF", mode="GUARANTEED", duration=2, clock="CHARGES", stack_policy="TWO_CHARGES")], hit_required=False, barrier_policy="IGNORE", fixed_rule="다음 공격 명중 바닥 2회"),
    src("aq.skill.common.w2.battlecry", [app("BASIC_FOCUS", owner="SELF", mode="GUARANTEED", duration=2, clock="CHARGES", stack_policy="TWO_CHARGES", magnitude_values=anchors("aq.skill.common.w2.battlecry"))], hit_required=False, barrier_policy="IGNORE"),
    src("aq.skill.common.w4.emberbottle", [app("BURN", duration=3, periodic_values=anchors("aq.skill.common.w4.emberbottle"), periodic_ticks=3, periodic_mode="SOURCE_POWER_COEFFICIENT")]),
    src("aq.skill.common.w4.poisonneedle", [app("POISON", duration=4, periodic_values=anchors("aq.skill.common.w4.poisonneedle"), periodic_ticks=4, periodic_mode="SOURCE_POWER_COEFFICIENT")]),
    src("aq.skill.external.w4.lichclock", [app("CURSE", subtype="LICH_CLOCK", duration=3)], hit_required=False, barrier_policy="IGNORE", fixed_rule="CURSE와 delayed event를 같은 linked receipt로 저장"),
    src("aq.skill.mage.a3.arcaneexposure", [app("MAGICAL_EXPOSED", duration=3)]),
    src("aq.skill.mage.a3.powerdamping", [app("POWER_SUPPRESSED", duration=3, magnitude_values=magnitude("aq.skill.mage.a3.powerdamping"))]),
    src("aq.skill.mage.a3.sensedistortion", [app("AIM_DISRUPTED", duration=3, magnitude_values=magnitude("aq.skill.mage.a3.sensedistortion"))]),
    src("aq.skill.mage.w2.fireball", [app("BURN", duration=1, clock="SOURCE_ACTION_END")], fixed_rule="BURN tag primer, 별도 DOT 예산 0"),
    src("aq.skill.mage.w2.frostspear", [app("CHILL", duration=2)]),
    src("aq.skill.paladin.w2.sacredbrand", [app("MARK", duration=1, clock="SOURCE_ACTION_END")]),
    src("aq.skill.paladin.w4.kinglessknight", [app("SUPPRESSED", duration=2, magnitude_values=magnitude("aq.skill.paladin.w4.kinglessknight"))], candidate_rule="TARGET_HP_LTE_2000"),
    src("aq.skill.ranger.a3.disruptmark", [app("AIM_DISRUPTED", duration=3)]),
    src("aq.skill.ranger.a3.markweakness", [app("PHYSICAL_EXPOSED", duration=3)]),
    src("aq.skill.ranger.a3.suppressmark", [app("POWER_SUPPRESSED", duration=3)]),
    src("aq.skill.ranger.w2.poisonarrow", [app("POISON", duration=1, clock="SOURCE_ACTION_END")], fixed_rule="POISON tag primer, 별도 DOT 예산 0"),
    src("aq.skill.ranger.w4.starreadingshot", [app("SILENCE", apply_values=anchors("aq.skill.ranger.w4.starreadingshot"), duration=1)], candidate_rule="SPELLCASTER"),
    src("aq.skill.rogue.a3.blursight", [app("AIM_DISRUPTED", duration=2)]),
    src("aq.skill.rogue.a3.exposegap", [app("PHYSICAL_EXPOSED", duration=2)]),
    src("aq.skill.rogue.a3.wristcheck", [app("POWER_SUPPRESSED", duration=2)]),
    src("aq.skill.rogue.w2.poisoncoat", [app("POISON", duration=1, clock="SOURCE_ACTION_END")], fixed_rule="POISON tag primer, 별도 DOT 예산 0"),
    src("aq.skill.rogue.w4.smokescreen", [
        app("AIM_DISRUPTED", duration=2, magnitude_values=anchors("aq.skill.rogue.w4.smokescreen"), linked_group="SMOKESCREEN"),
        app("AIM_DISRUPTED", owner="SELF", mode="GUARANTEED", duration=2, magnitude_values=anchors("aq.skill.rogue.w4.smokescreen"), linked_group="SMOKESCREEN"),
    ], hit_required=False, barrier_policy="IGNORE"),
    src("aq.skill.warrior.a3.armorbreak", [app("PHYSICAL_EXPOSED", duration=3)]),
    src("aq.skill.warrior.a3.breakresolve", [app("POWER_SUPPRESSED", duration=3)]),
    src("aq.skill.warrior.a3.sightpressure", [app("AIM_DISRUPTED", duration=3)]),
    src("aq.skill.warrior.w4.shieldpush", [app("STAGGER", apply_values=anchors("aq.skill.warrior.w4.shieldpush"), duration=1)], fixed_rule="SELF Shield 300 선소비, 상태 실패 환불 0"),

    # Semantic applications omitted from appliesTags in registry v0.2.
    src("aq.skill.boss.w7.giantstomp", [app("STAGGER", apply_values=anchors("aq.skill.boss.w7.giantstomp"), duration=1)], apply_modifier_rule="BOSS_DELAY_CAP_ONE"),
    src("aq.skill.cleric.w6.silentrelic", [app("SILENCE", apply_values=anchors("aq.skill.cleric.w6.silentrelic"), duration=1)], candidate_rule="SPELLCASTER"),
    src("aq.skill.common.w7.pushback", [app("STAGGER", apply_values=anchors("aq.skill.common.w7.pushback"), duration=1)], apply_modifier_rule="ARMORED_OR_BOSS_HALF"),
    src("aq.skill.common.w8.coolingbottle", [app("CHILL", apply_values=anchors("aq.skill.common.w8.coolingbottle"), duration=2)]),
    src("aq.skill.common.w8.silencethrow", [app("SILENCE", apply_values=anchors("aq.skill.common.w8.silencethrow"), duration=1)], candidate_rule="SPELLCASTER"),
    src("aq.skill.common.w8.throwingnet", [app("BIND", apply_values=anchors("aq.skill.common.w8.throwingnet"), duration=1)], apply_modifier_rule="SWIFT_PLUS_1000"),
    src("aq.skill.common.w8.weaknesswatch", [app("EXPOSED", apply_values=anchors("aq.skill.common.w8.weaknesswatch"), duration=1, clock="NEXT_DIRECT_PACKET")], hit_required=False, barrier_policy="IGNORE"),
    src("aq.skill.monster.w7.mushroomspore", [app("POISON", apply_values=anchors("aq.skill.monster.w7.mushroomspore"), duration=3)], apply_modifier_rule="POISON_ELSE_AIM", fixed_rule="POISON 존재 시 AIM_DISRUPTED로 tag 대체, 동시 2상태 금지"),
    src("aq.skill.monster.w7.slimeshot", [app("SLIMED", duration=2, magnitude_values=anchors("aq.skill.monster.w7.slimeshot"))]),
    src("aq.skill.monster.w7.spiderweb", [app("BIND", apply_values=anchors("aq.skill.monster.w7.spiderweb"), duration=1)], apply_modifier_rule="NON_SWIFT_MINUS_2000", fixed_rule="다음 root 전 FIRE 행동으로 조기 제거 가능"),
    src("aq.skill.monster.w8.wraithgrasp", [app("CURSE", apply_values=(6_000,) * 5, subtype="WRAITH_GRASP", duration=3)]),
    src("aq.skill.paladin.w3.shieldbash", [app("STAGGER", duration=1, magnitude_values=anchors("aq.skill.paladin.w3.shieldbash"))], fixed_rule="Shield 500 선소비 후 적용 시도"),
    src("aq.skill.paladin.w6.judgmentcharge", [app("STAGGER", apply_values=(5_000,) * 5, duration=1)], candidate_rule="FIRST_OWN_ROOT"),
    src("aq.skill.warrior.w5.earthsplitter", [app("STAGGER", apply_values=anchors("aq.skill.warrior.w5.earthsplitter"), duration=1)], candidate_rule="ARMORED"),
    src("aq.skill.world.w7.blackwell", [app("CHILL", apply_values=anchors("aq.skill.world.w7.blackwell"), duration=2)], candidate_rule="TARGET_HAS_BURN", fixed_rule="BURN 1stack 소비와 CHILL 적용을 같은 transaction으로 처리"),
    src("aq.skill.world.w8.deadletter", [app("CURSE", apply_values=(5_000,) * 5, subtype="DEAD_LETTER", duration=3, linked_group="DEAD_LETTER")], hit_required=False, barrier_policy="IGNORE"),
    src("aq.skill.world.w8.frozentorch", [
        app("BURN", apply_values=anchors("aq.skill.world.w8.frozentorch"), duration=3),
        app("CHILL", apply_values=anchors("aq.skill.world.w8.frozentorch"), duration=2),
    ], fixed_rule="동일 carrier 뒤 독립 roll 2회, 행동당 신규 상태 상한 2"),
    src("aq.skill.world.w8.lostbell", [
        app("CURSE", apply_values=anchors("aq.skill.world.w8.lostbell"), subtype="LOST_BELL", duration=3),
        app("STAGGER", apply_values=(5_000,) * 5, duration=1),
    ], candidate_rule="UNDEAD_FOR_STATUS", fixed_rule="생물은 carrier 2,000만, 상태 시도 0"),
    src("aq.skill.world.w8.poisonmist", [
        app("POISON", apply_values=anchors("aq.skill.world.w8.poisonmist"), duration=3, linked_group="POISON_MIST"),
        app("AIM_DISRUPTED", owner="SELF", mode="GUARANTEED", duration=3, magnitude_values=(1_000,) * 5, linked_group="POISON_MIST"),
    ], fixed_rule="자기 Hit penalty는 target POISON 제거 또는 조우 종료까지"),
    src("aq.skill.external.w5.phoenixash", [app("BURN", owner="SELF", mode="GUARANTEED", duration=1, clock="EXPEDITION_LODGING", cost_locked=True, subtype="PHOENIX_COST")], hit_required=False, barrier_policy="IGNORE"),
    src("aq.skill.secret.w7.abyssaleye", [app("CURSE", owner="SELF", mode="GUARANTEED", duration=1, clock="ENCOUNTER", cost_locked=True, subtype="ABYSSAL_EYE_COST")], hit_required=False, barrier_policy="IGNORE"),
    src("aq.skill.paladin.w6.cursedswordseal", [app("CURSE", owner="SELF", mode="TRANSFER", duration=1, clock="EXPEDITION_LODGING", cost_locked=True)], candidate_rule="TARGET_HAS_CURSE", barrier_policy="IGNORE", fixed_rule="instanceId/subtype/magnitude를 유지해 SELF로 이전"),
    src("aq.skill.external.w2.dragonscale", [app("BURN", owner="SELF", mode="GUARANTEED", duration=3, clock="SELF_ROOT_END")], hit_required=False, barrier_policy="IGNORE", fixed_rule="v0.5 ON_USE 대가, 상태 저항 우회"),

    # Required gap fixes; not yet written into the 240 registry.
    src("aq.skill.common.v06.bleedingcut", [app("BLEED", apply_values=(8_500,) * 5, duration=3, periodic_values=(3_000, 3_500, 4_000, 4_500, 5_000), periodic_ticks=3, periodic_mode="SOURCE_POWER_COEFFICIENT")], name_ko="출혈 베기", provisional=True, fixed_rule="carrier 5,000·cost1,500·CD3, 피의 회전 primer"),
    src("aq.skill.common.v06.staticflask", [app("SHOCK", apply_values=(4_500, 5_375, 6_250, 7_125, 8_000), duration=3, stack_policy="STACK_TWO")], name_ko="정전기 병", provisional=True, fixed_rule="carrier 4,000·cost1,200·CD3, SHOCK +1stack"),
)
SOURCE_BY_ID = {item.skill_id: item for item in SOURCES}
PROVISIONAL_IDS = frozenset(item.skill_id for item in SOURCES if item.provisional)


MONSTER_PREFIX_SOURCES = (
    SourceContract("aq.monster.prefix.venomous", "맹독의", "MONSTER_PREFIX", (app("POISON", apply_values=(6_600,) * 5, duration=3, periodic_values=(900,) * 5, periodic_ticks=3, periodic_mode="TARGET_MAX_HP_BPS"),)),
    SourceContract("aq.monster.prefix.ember", "잿불의", "MONSTER_PREFIX", (app("BURN", apply_values=(6_600,) * 5, duration=3, periodic_values=(900,) * 5, periodic_ticks=3, periodic_mode="TARGET_MAX_HP_BPS"),)),
    SourceContract("aq.monster.prefix.frostbound", "서리결의", "MONSTER_PREFIX", (app("CHILL", apply_values=(6_600,) * 5, duration=2),)),
    SourceContract("aq.monster.prefix.cursed", "저주받은", "MONSTER_PREFIX", (app("CURSE", apply_values=(6_600,) * 5, subtype="PREFIX_CURSE", duration=3, magnitude_values=(800,) * 5),)),
)


@dataclass(frozen=True)
class StatusInstance:
    instance_id: str
    tag: str
    owner: str
    subtype: str
    stacks: int
    magnitude_bps: int
    remaining: int
    clock: str
    periodic_packets: tuple[int, ...]
    periodic_mode: str
    source_power: int
    cost_locked: bool
    linked_group: str
    version: int = 1


@dataclass(frozen=True)
class LifecycleState:
    statuses: tuple[StatusInstance, ...] = ()
    linked_effects: tuple[str, ...] = ()
    receipts: frozenset[str] = frozenset()


@dataclass(frozen=True)
class ApplyResult:
    state: LifecycleState
    outcome: str
    applied: tuple[str, ...]
    refreshed: tuple[str, ...]
    rejected: tuple[str, ...]
    final_apply_bps: tuple[int, ...]
    new_instances: int
    receipt: str


def value_at(values, level):
    return skills.value_at(tuple(values), level)


def split_exact(total: int, count: int) -> tuple[int, ...]:
    if count <= 0:
        return ()
    quotient, remainder = divmod(total, count)
    return tuple(quotient + (1 if index < remainder else 0) for index in range(count))


def final_apply_bps(declared: int, target_status_resistance: int, source_bonus_bps: int = 0,
                    tag_resist_bps: int = 0, boss_hard_control: bool = False,
                    source_modifier_bps: int = 0) -> int:
    source_bonus = max(0, min(SOURCE_APPLY_BONUS_CAP_BPS, source_bonus_bps))
    tag_resist_cap = TARGET_CONTROL_RESIST_CAP_BPS if boss_hard_control else TARGET_GENERAL_RESIST_CAP_BPS
    tag_resist = max(0, min(tag_resist_cap, tag_resist_bps))
    raw = declared + source_bonus + source_modifier_bps - STATUS_RESIST_POINT_BPS * max(0, target_status_resistance) - tag_resist
    bounded = max(APPLY_MIN_BPS, min(APPLY_MAX_BPS, raw))
    if boss_hard_control:
        return max(APPLY_MIN_BPS, min(BOSS_CONTROL_MAX_BPS, bounded * BOSS_CONTROL_MULTIPLIER_BPS // 10_000))
    return bounded


def is_immune(tag: str, immunities: set[str]) -> bool:
    return tag in immunities or IMMUNITY_ALIASES.get(tag) in immunities


def owner_hostile_count(statuses: tuple[StatusInstance, ...], owner: str) -> int:
    return sum(item.owner == owner and item.tag in HOSTILE_TAGS for item in statuses)


def identity(item: StatusInstance) -> tuple[str, str, str]:
    return item.owner, item.tag, item.subtype if item.tag == "CURSE" else ""


def application_identity(application: Application) -> tuple[str, str, str]:
    return application.owner, application.tag, application.subtype if application.tag == "CURSE" else ""


def merge_status(statuses: tuple[StatusInstance, ...], incoming: StatusInstance, policy: str) -> tuple[tuple[StatusInstance, ...], str]:
    current_index = next((index for index, item in enumerate(statuses) if identity(item) == identity(incoming)), None)
    if current_index is not None:
        current = statuses[current_index]
        if policy == "STACK_TWO":
            merged = replace(current, stacks=min(SHOCK_STACK_CAP, current.stacks + 1), remaining=max(current.remaining, incoming.remaining), version=current.version + 1)
        elif policy == "TWO_CHARGES":
            merged = replace(current, stacks=2, remaining=2, version=current.version + 1)
        else:
            current_periodic = sum(current.periodic_packets)
            incoming_periodic = sum(incoming.periodic_packets)
            current_strength = (current_periodic, current.magnitude_bps)
            incoming_strength = (incoming_periodic, incoming.magnitude_bps)
            if incoming_strength > current_strength:
                merged = replace(incoming, instance_id=current.instance_id, version=current.version + 1)
            elif incoming_strength == current_strength and incoming.remaining > current.remaining:
                merged = replace(current, remaining=incoming.remaining, periodic_packets=incoming.periodic_packets or current.periodic_packets, version=current.version + 1)
            else:
                return statuses, "WEAKER_IGNORED"
        result = list(statuses)
        result[current_index] = merged
        return tuple(result), "REFRESHED"
    if incoming.tag == "CURSE":
        curse_subtypes = {item.subtype for item in statuses if item.owner == incoming.owner and item.tag == "CURSE"}
        if len(curse_subtypes) >= CURSE_SUBTYPE_CAP:
            return statuses, "CURSE_SUBTYPE_CAP"
    if incoming.tag in HOSTILE_TAGS and owner_hostile_count(statuses, incoming.owner) >= HOSTILE_STATUS_CAP:
        return statuses, "HOSTILE_CAP"
    return statuses + (incoming,), "APPLIED"


def source_modifier(source: SourceContract, variant) -> int:
    profile, rank = variant.profile, variant.rank
    if source.apply_modifier_rule == "SWIFT_PLUS_1000" and profile == "SWIFT":
        return 1_000
    if source.apply_modifier_rule == "NON_SWIFT_MINUS_2000" and profile != "SWIFT":
        return -2_000
    return 0


def multiplier_after_formula(source: SourceContract, variant) -> int:
    # The skill's BOSS wording points to the shared boss-control half; it is
    # not multiplied a second time.  ARMORED non-boss targets take this half.
    if source.apply_modifier_rule == "ARMORED_OR_BOSS_HALF" and variant.profile == "ARMORED" and variant.rank != "BOSS":
        return 5_000
    return 10_000


def candidate_allowed(source: SourceContract, variant, state: LifecycleState) -> bool:
    base = BASE_BY_ID[variant.base_enemy_id]
    if source.candidate_rule == "SPELLCASTER":
        return variant.profile == "SPELLCASTER"
    if source.candidate_rule == "ARMORED":
        return variant.profile == "ARMORED"
    if source.candidate_rule == "UNDEAD_FOR_STATUS":
        return base.family == "UNDEAD"
    if source.candidate_rule == "TARGET_HAS_BURN":
        return any(item.owner == "TARGET" and item.tag == "BURN" for item in state.statuses)
    if source.candidate_rule == "TARGET_HAS_CURSE":
        return any(item.owner == "TARGET" and item.tag == "CURSE" for item in state.statuses)
    return True


BASE_BY_ID = {item.enemy_id: item for item in monsters.BASE_ENEMIES}


def execute_apply(state: LifecycleState, source: SourceContract, skill_level: int, variant,
                  receipt: str, rolls_bps: tuple[int, ...], hit: bool = True,
                  barrier: bool = False, source_bonus_bps: int = 0,
                  tag_resist_bps: int = 0, force_immunities: set[str] | None = None,
                  source_power: int = 1_000, target_status_resistance: int = 20) -> ApplyResult:
    if receipt in state.receipts:
        return ApplyResult(state, "REPLAY_IGNORED", (), (), (), (), 0, receipt)
    if not candidate_allowed(source, variant, state):
        return ApplyResult(state, "NOT_CANDIDATE", (), (), (), (), 0, receipt)
    committed = replace(state, receipts=state.receipts | {receipt})
    if source.hit_required and not hit:
        return ApplyResult(committed, "MISS", (), (), tuple(item.tag for item in source.applications), (), 0, receipt)
    if barrier and source.barrier_policy == "BLOCK_PACKAGE":
        return ApplyResult(committed, "BARRIER_BLOCKED", (), (), tuple(item.tag for item in source.applications), (), 0, receipt)

    immunities = set(BASE_BY_ID[variant.base_enemy_id].status_immunities)
    if force_immunities is not None:
        immunities |= force_immunities
    statuses = committed.statuses
    applied, refreshed, rejected, chances = [], [], [], []
    new_count = 0
    for index, application in enumerate(source.applications[:NEW_STATUS_PER_ACTION_CAP]):
        actual = application
        if source.apply_modifier_rule == "POISON_ELSE_AIM" and any(
            item.owner == "TARGET" and item.tag == "POISON" for item in statuses
        ):
            actual = replace(application, tag="AIM_DISRUPTED", periodic_values=(0,) * 5, periodic_ticks=0, periodic_mode="NONE")
        if actual.owner == "TARGET" and is_immune(actual.tag, immunities):
            rejected.append(f"{actual.tag}:IMMUNE")
            chances.append(0)
            continue
        if actual.mode in {"GUARANTEED", "TRANSFER"}:
            chance = 10_000
        else:
            declared = value_at(actual.apply_values, skill_level)
            boss_control = variant.rank == "BOSS" and actual.tag in HARD_CONTROL_TAGS
            chance = final_apply_bps(
                declared,
                target_status_resistance,
                source_bonus_bps,
                tag_resist_bps + (1_000 if variant.prefix_id == "ANCIENT" and actual.tag in HARD_CONTROL_TAGS else 0),
                boss_control,
                source_modifier(source, variant),
            )
            chance = max(APPLY_MIN_BPS, min(APPLY_MAX_BPS, chance * multiplier_after_formula(source, variant) // 10_000))
        chances.append(chance)
        roll = rolls_bps[index] if index < len(rolls_bps) else 9_999
        if actual.mode == "ROLL" and roll >= chance:
            rejected.append(f"{actual.tag}:RESISTED")
            continue
        periodic_total = value_at(actual.periodic_values, skill_level)
        packets = split_exact(periodic_total, actual.periodic_ticks)
        incoming = StatusInstance(
            instance_id=f"{receipt}:{index}", tag=actual.tag, owner=actual.owner,
            subtype=actual.subtype, stacks=1,
            magnitude_bps=value_at(actual.magnitude_values, skill_level),
            remaining=actual.duration, clock=actual.clock,
            periodic_packets=packets, periodic_mode=actual.periodic_mode,
            source_power=source_power, cost_locked=actual.cost_locked,
            linked_group=actual.linked_group,
        )
        before = statuses
        statuses, result = merge_status(statuses, incoming, actual.stack_policy)
        if result == "APPLIED":
            applied.append(actual.tag)
            new_count += 1
        elif result == "REFRESHED":
            refreshed.append(actual.tag)
        else:
            rejected.append(f"{actual.tag}:{result}")
        assert new_count <= NEW_STATUS_PER_ACTION_CAP

    outcome = "APPLIED" if applied else "REFRESHED" if refreshed else "NO_STATUS"
    return ApplyResult(replace(committed, statuses=statuses), outcome, tuple(applied), tuple(refreshed), tuple(rejected), tuple(chances), new_count, receipt)


@dataclass(frozen=True)
class TickResult:
    state: LifecycleState
    hp: int
    shield: int
    damage_by_status: tuple[tuple[str, int], ...]
    dead: bool


def tick_target_root_start(state: LifecycleState, hp: int, max_hp: int, shield: int,
                           mitigation_bps: int = 2_000, dot_reduction_bps: int = 0) -> TickResult:
    statuses = list(state.statuses)
    damage_log = []
    for index, status in sorted(enumerate(statuses), key=lambda pair: pair[1].instance_id):
        if not status.periodic_packets:
            continue
        coefficient = status.periodic_packets[0]
        if status.periodic_mode == "TARGET_MAX_HP_BPS":
            damage = max_hp * coefficient // 10_000
        else:
            raw = status.source_power * coefficient // 10_000
            damage = raw * (10_000 - max(0, min(8_000, mitigation_bps))) // 10_000
        damage = damage * (10_000 - max(0, min(2_400, dot_reduction_bps))) // 10_000
        absorbed = min(shield, damage)
        shield -= absorbed
        actual = max(0, damage - absorbed)
        hp = max(0, hp - actual)
        damage_log.append((status.instance_id, actual))
        rest = status.periodic_packets[1:]
        statuses[index] = replace(status, periodic_packets=rest, remaining=max(0, status.remaining - 1), version=status.version + 1)
        if hp <= 0:
            break
    statuses = [item for item in statuses if item.periodic_packets or item.periodic_mode == "NONE"]
    return TickResult(replace(state, statuses=tuple(statuses)), hp, shield, tuple(damage_log), hp <= 0)


@dataclass(frozen=True)
class CleanseContract:
    skill_id: str
    name_ko: str
    count_cap: int
    power_values: tuple[int, int, int, int, int]
    allowed_tags: tuple[str, ...]
    guaranteed: bool
    carrier_success_values: tuple[int, int, int, int, int]
    carrier_fallback_bps: int


CLEANSES = (
    CleanseContract("aq.skill.cleric.w4.cleanse", "정화", 1, anchors("aq.skill.cleric.w4.cleanse"), (), False, (0,) * 5, 0),
    CleanseContract("aq.skill.paladin.w4.sunoath", "태양의 맹세", 2, GUARANTEED, ("BURN", "CURSE"), True, anchors("aq.skill.paladin.w4.sunoath"), 6_500),
    CleanseContract("aq.skill.world.w7.breakchains", "포로의 사슬 끊기", 1, GUARANTEED, tuple(sorted(HARD_CONTROL_TAGS)), True, anchors("aq.skill.world.w7.breakchains"), 3_000),
)


@dataclass(frozen=True)
class CleanseResult:
    state: LifecycleState
    outcome: str
    selected: tuple[str, ...]
    removed: tuple[str, ...]
    final_cleanse_bps: int
    carrier_coefficient_bps: int


def cleanse_priority(item: StatusInstance, current_hp: int) -> tuple:
    next_damage = item.periodic_packets[0] if item.periodic_packets else 0
    return (
        -int(next_damage >= current_hp and next_damage > 0),
        -int(item.tag in HARD_CONTROL_TAGS),
        -sum(item.periodic_packets),
        -item.remaining,
        item.instance_id,
    )


def execute_cleanse(state: LifecycleState, contract: CleanseContract, skill_level: int,
                    receipt: str, roll_bps: int, current_hp: int,
                    source_bonus_bps: int = 0, cleanse_seal_bps: int = 0,
                    cleanse_immune_ids: frozenset[str] = frozenset()) -> CleanseResult:
    if receipt in state.receipts:
        return CleanseResult(state, "REPLAY_IGNORED", (), (), 0, 0)
    committed = replace(state, receipts=state.receipts | {receipt})
    eligible = [item for item in committed.statuses if item.owner == "SELF" and item.tag in HOSTILE_TAGS
                and not item.cost_locked and item.instance_id not in cleanse_immune_ids]
    if contract.allowed_tags:
        eligible = [item for item in eligible if item.tag in contract.allowed_tags]
    selected = tuple(sorted(eligible, key=lambda item: cleanse_priority(item, current_hp))[:contract.count_cap])
    if not selected:
        return CleanseResult(committed, "NO_TARGET", (), (), 0, contract.carrier_fallback_bps)
    power = value_at(contract.power_values, skill_level)
    chance = 10_000 if contract.guaranteed else max(CLEANSE_MIN_BPS, min(CLEANSE_MAX_BPS, power + source_bonus_bps - cleanse_seal_bps))
    if roll_bps >= chance:
        return CleanseResult(committed, "RESISTED", tuple(item.tag for item in selected), (), chance, contract.carrier_fallback_bps)
    selected_ids = {item.instance_id for item in selected}
    linked = {item.linked_group for item in selected if item.linked_group}
    remaining = tuple(item for item in committed.statuses if item.instance_id not in selected_ids)
    effects = tuple(item for item in committed.linked_effects if item not in linked)
    carrier = value_at(contract.carrier_success_values, skill_level) if any(contract.carrier_success_values) else 0
    return CleanseResult(replace(committed, statuses=remaining, linked_effects=effects), "CLEANSED",
                         tuple(item.tag for item in selected), tuple(item.tag for item in selected), chance, carrier)


@lru_cache(maxsize=None)
def loadout_for(skill_id: str, skill_level: int):
    actual_id = "aq.skill.common.w4.emberbottle" if skill_id in PROVISIONAL_IDS else skill_id
    item = skills.ACTIVE_BY_ID[actual_id]
    hero_class = item.owner_scope if item.owner_scope != "ALL" else "MAGE"
    selected = [actual_id]
    pool = [entry for entry in skills.accessible(hero_class, "ACTIVE") if entry.definition_id not in selected]
    pool.sort(key=lambda entry: (-resolver.role_score(entry, "CONTROL"), entry.definition_id))
    selected.extend(entry.definition_id for entry in pool[:4])
    passive_pool = sorted(skills.accessible(hero_class, "PASSIVE"), key=lambda entry: (-resolver.role_score(entry, "SUSTAIN"), entry.definition_id))
    return skills.commit_loadout(hero_class, {entry: skill_level for entry in selected[:5]},
                                 {entry.definition_id: skill_level for entry in passive_pool[:3]},
                                 "aq.behavior.status-lifecycle.v1")


@dataclass(frozen=True)
class Scenario:
    source_id: str
    display_level: int
    variant_id: str
    mode: str
    outcome: str
    applied_count: int
    refreshed_count: int
    rejected_count: int
    min_chance_bps: int
    max_chance_bps: int
    hostile_peak: int
    active_slots: int
    passive_slots: int


def simulate(source: SourceContract, display_level: int, variant, mode: str) -> Scenario:
    level = SKILL_LEVELS[display_level]
    state = LifecycleState()
    if mode == "REFRESH":
        seed_app = source.applications[0]
        seeded = StatusInstance("seed", seed_app.tag, seed_app.owner, seed_app.subtype, 1, 0, 1,
                                seed_app.clock, (), "NONE", 1_000, seed_app.cost_locked, seed_app.linked_group)
        state = LifecycleState((seeded,))
    elif mode == "IMMUNE_OR_CAP":
        cap_tags = ("BLEED", "BURN", "POISON", "CHILL", "MARK", "EXPOSED")
        fillers = tuple(StatusInstance(f"cap.{index}", cap_tags[index], "TARGET", "", 1, 0, 2,
                                       "TARGET_ROOT_END", (), "NONE", 1_000, False, "") for index in range(HOSTILE_STATUS_CAP))
        state = LifecycleState(fillers)
    rolls = (0, 0) if mode in {"SUCCESS_ROLL", "BARRIER", "IMMUNE_OR_CAP", "REFRESH"} else (9_999, 9_999)
    result = execute_apply(
        state, source, level, variant,
        f"{source.skill_id}:{display_level}:{variant.variant_id}:{mode}", rolls,
        hit=mode != "MISS", barrier=mode == "BARRIER",
        source_bonus_bps=SOURCE_APPLY_BONUS_CAP_BPS if mode == "SUCCESS_ROLL" else 0,
        force_immunities={source.applications[0].tag} if mode == "IMMUNE_OR_CAP" and source.applications[0].owner == "TARGET" else None,
        target_status_resistance=wave4.wave1.legacy.c1.monster_status_resistance(
            combat.source_for_display("MAGE", display_level).combat_rank,
            variant.profile,
        ),
    )
    snapshot = loadout_for(source.skill_id, level)
    chances = result.final_apply_bps or (0,)
    return Scenario(source.skill_id, display_level, variant.variant_id, mode, result.outcome,
                    len(result.applied), len(result.refreshed), len(result.rejected),
                    min(chances), max(chances),
                    max(owner_hostile_count(result.state.statuses, "TARGET"), owner_hostile_count(result.state.statuses, "SELF")),
                    len(snapshot.active_slots), len(snapshot.passive_slots))


@lru_cache(maxsize=1)
def application_matrix() -> tuple[Scenario, ...]:
    return tuple(simulate(source, display, variant, mode)
                 for source in SOURCES for display in DISPLAY_LEVELS
                 for variant in monsters.VARIANTS for mode in SCENARIO_MODES)


@dataclass(frozen=True)
class CleanseScenario:
    skill_id: str
    display_level: int
    variant_id: str
    mode: str
    outcome: str
    selected_count: int
    removed_count: int
    cost_locked_remaining: bool
    linked_effect_remaining: bool
    active_slots: int
    passive_slots: int


CLEANSE_MODES = ("SUCCESS", "RESIST_OR_GUARANTEED", "NO_TARGET", "COST_LOCKED", "LETHAL_PRIORITY")


def simulate_cleanse(contract: CleanseContract, display_level: int, variant, mode: str) -> CleanseScenario:
    level = SKILL_LEVELS[display_level]
    burn = sample_status("a.burn", "BURN", periodic=(3_000,), remaining=1, linked="DOT_A")
    stagger = sample_status("b.stagger", "STAGGER", remaining=1)
    locked = sample_status("c.locked", "CURSE", remaining=9, cost_locked=True)
    if mode == "NO_TARGET":
        state = LifecycleState()
    elif mode == "COST_LOCKED":
        state = LifecycleState((locked,))
    else:
        state = LifecycleState((stagger, locked, burn), ("DOT_A",))
    roll = 9_999 if mode == "RESIST_OR_GUARANTEED" else 0
    result = execute_cleanse(
        state, contract, level,
        f"{contract.skill_id}:{display_level}:{variant.variant_id}:{mode}",
        roll, current_hp=2_000 if mode == "LETHAL_PRIORITY" else 10_000,
        cleanse_seal_bps=5_000 if mode == "RESIST_OR_GUARANTEED" else 0,
    )
    snapshot = loadout_for(contract.skill_id, level)
    return CleanseScenario(
        contract.skill_id, display_level, variant.variant_id, mode, result.outcome,
        len(result.selected), len(result.removed),
        any(item.cost_locked for item in result.state.statuses),
        "DOT_A" in result.state.linked_effects,
        len(snapshot.active_slots), len(snapshot.passive_slots),
    )


@lru_cache(maxsize=1)
def cleanse_matrix() -> tuple[CleanseScenario, ...]:
    return tuple(simulate_cleanse(contract, display, variant, mode)
                 for contract in CLEANSES for display in DISPLAY_LEVELS
                 for variant in monsters.VARIANTS for mode in CLEANSE_MODES)


def sample_status(instance_id: str, tag: str, periodic=(0,), remaining=2, cost_locked=False,
                  linked="") -> StatusInstance:
    packets = tuple(periodic) if periodic != (0,) else ()
    return StatusInstance(instance_id, tag, "SELF", "", 1, 0, remaining, "TARGET_ROOT_END",
                          packets, "SOURCE_POWER_COEFFICIENT" if packets else "NONE", 10_000,
                          cost_locked, linked)


def branch_probes() -> tuple[tuple[str, bool], ...]:
    variant = monsters.VARIANTS[0]
    probes = []
    burn = SOURCE_BY_ID["aq.skill.common.w4.emberbottle"]
    full = execute_apply(LifecycleState(), burn, 100, variant, "burn:full", (0,), source_power=10_000)
    miss = execute_apply(LifecycleState(), burn, 100, variant, "burn:miss", (0,), hit=False)
    barrier = execute_apply(LifecycleState(), burn, 100, variant, "burn:barrier", (0,), barrier=True)
    replay = execute_apply(full.state, burn, 100, variant, "burn:full", (0,))
    ticked = tick_target_root_start(full.state, hp=20_000, max_hp=20_000, shield=2_000, mitigation_bps=2_000)
    probes.extend((
        ("DOT_SPLIT_EXACT", sum(full.state.statuses[0].periodic_packets) == 7_000 and len(full.state.statuses[0].periodic_packets) == 3),
        ("MISS_NO_STATUS", not miss.state.statuses),
        ("BARRIER_BLOCKS_CARRIER_AND_STATUS", not barrier.state.statuses),
        ("REPLAY_IDEMPOTENT", replay.outcome == "REPLAY_IGNORED" and replay.state == full.state),
        ("DOT_USES_SHIELD_NOT_BARRIER", ticked.shield < 2_000 and ticked.hp == 20_000),
    ))
    shock = SOURCE_BY_ID["aq.skill.common.v06.staticflask"]
    first = execute_apply(LifecycleState(), shock, 100, variant, "shock:1", (0,))
    second = execute_apply(first.state, shock, 100, variant, "shock:2", (0,))
    third = execute_apply(second.state, shock, 100, variant, "shock:3", (0,))
    probes.extend((
        ("SHOCK_STACK_TWO", second.state.statuses[0].stacks == 2),
        ("SHOCK_THIRD_REFRESH_ONLY", third.state.statuses[0].stacks == 2),
    ))
    curse_sources = ("aq.skill.external.w4.lichclock", "aq.skill.world.w8.deadletter", "aq.skill.world.w8.lostbell")
    undead_variant = next(item for item in monsters.VARIANTS if BASE_BY_ID[item.base_enemy_id].family == "UNDEAD")
    curse_state = LifecycleState()
    for index, source_id in enumerate(curse_sources):
        curse_state = execute_apply(curse_state, SOURCE_BY_ID[source_id], 100, undead_variant,
                                    f"curse:{index}", (0, 0)).state
    probes.append(("THREE_CURSE_SUBTYPES", len({item.subtype for item in curse_state.statuses if item.tag == "CURSE"}) == 3))
    lethal = sample_status("a.burn", "BURN", periodic=(3_000,), remaining=1, linked="DOT_A")
    control = sample_status("b.stagger", "STAGGER", remaining=1)
    locked = sample_status("c.cost", "CURSE", remaining=9, cost_locked=True)
    cleanse_state = LifecycleState((control, locked, lethal), ("DOT_A",))
    cleaned = execute_cleanse(cleanse_state, CLEANSES[0], 100, "cleanse:1", 0, current_hp=2_000)
    sun = execute_cleanse(cleanse_state, CLEANSES[1], 100, "cleanse:sun", 0, current_hp=10_000)
    probes.extend((
        ("LETHAL_DOT_FIRST", cleaned.removed == ("BURN",)),
        ("LINKED_EVENT_CAS_REMOVED", "DOT_A" not in cleaned.state.linked_effects),
        ("COST_LOCKED_SURVIVES", any(item.instance_id == "c.cost" for item in cleaned.state.statuses)),
        ("SUN_OATH_FILTER_AND_CAP", sun.removed == ("BURN",) and len(sun.removed) <= 2),
    ))
    immune_cleanse = execute_cleanse(
        LifecycleState((control,)), CLEANSES[2], 100, "cleanse:immune", 0,
        current_hp=10_000, cleanse_immune_ids=frozenset({control.instance_id}),
    )
    probes.append(("EXPLICIT_CLEANSE_IMMUNITY_RESPECTED", immune_cleanse.outcome == "NO_TARGET"))
    bleeding = SOURCE_BY_ID["aq.skill.common.v06.bleedingcut"]
    bleed_result = execute_apply(LifecycleState(), bleeding, 100, variant, "bleed:new", (0,))
    probes.extend((
        ("BLEED_GAP_FIXED", bleed_result.applied == ("BLEED",)),
        ("SHOCK_GAP_FIXED", first.applied == ("SHOCK",)),
        ("NEW_STATUS_PER_ACTION_CAP", all(len(item.applications) <= 2 for item in SOURCES)),
        ("CLEANSE_ROOT_CAP", max(item.count_cap for item in CLEANSES) <= CLEANSE_PER_ROOT_CAP),
    ))
    return tuple(probes)


def canonical_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "consumerV05Hash": consumer.canonical_hash(),
        "skillRegistryHash": skills.canonical_hash(),
        "monsterRegistryHash": monsters.canonical_hash(),
        "sources": [asdict(item) for item in SOURCES],
        "monsterPrefixSources": [asdict(item) for item in MONSTER_PREFIX_SOURCES],
        "cleanses": [asdict(item) for item in CLEANSES],
        "caps": {
            "hostile": HOSTILE_STATUS_CAP, "newPerAction": NEW_STATUS_PER_ACTION_CAP,
            "shockStacks": SHOCK_STACK_CAP, "curseSubtypes": CURSE_SUBTYPE_CAP,
            "applyMinBps": APPLY_MIN_BPS, "applyMaxBps": APPLY_MAX_BPS,
            "bossControlMultiplierBps": BOSS_CONTROL_MULTIPLIER_BPS,
            "bossControlMaxBps": BOSS_CONTROL_MAX_BPS,
            "statusResistPointBps": STATUS_RESIST_POINT_BPS,
            "cleansePerRoot": CLEANSE_PER_ROOT_CAP,
        },
        "applyFormula": "clamp(declared+sourceBonus+sourceModifier-40*statusResistance-tagResistance,1000,9500);bossHardControl*5000",
        "dotOrder": "TARGET_ROOT_START_STATUS_ID_ASC_SHIELD_THEN_HP",
        "cleanseImmunityRespected": True,
        "manualCombatAction": False,
        "itemGrantedSkills": False,
        "productionEnabled": False,
    }


def canonical_hash() -> str:
    raw = json.dumps(canonical_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def summary(matrix, cleanse_rows=None):
    cleanse_rows = cleanse_matrix() if cleanse_rows is None else cleanse_rows
    return {
        "sourceContracts": len(SOURCES),
        "existingSources": len(SOURCES) - len(PROVISIONAL_IDS),
        "provisionalGapFixes": len(PROVISIONAL_IDS),
        "monsterPrefixSources": len(MONSTER_PREFIX_SOURCES),
        "cleanseContracts": len(CLEANSES),
        "applicationScenarios": len(matrix),
        "cleanseScenarios": len(cleanse_rows),
        "totalScenarios": len(matrix) + len(cleanse_rows),
        "branchProbes": len(branch_probes()),
        "variants": len({row.variant_id for row in matrix}),
        "outcomes": dict(sorted(Counter(row.outcome for row in matrix).items())),
        "maxHostile": max(row.hostile_peak for row in matrix),
        "maxChanceBps": max(row.max_chance_bps for row in matrix),
        "minPositiveChanceBps": min(row.min_chance_bps for row in matrix if row.min_chance_bps > 0),
    }


def checks(matrix, cleanse_rows=None):
    cleanse_rows = cleanse_matrix() if cleanse_rows is None else cleanse_rows
    report = summary(matrix, cleanse_rows)
    probes = branch_probes()
    current_ids = set(skills.ACTIVE_BY_ID)
    existing_ids = set(SOURCE_BY_ID) - PROVISIONAL_IDS
    return [
        ("fifty one lifecycle sources exact", len(SOURCES) == 51 and len(SOURCE_BY_ID) == 51 and len(PROVISIONAL_IDS) == 2, f"sources={len(SOURCES)} provisional={len(PROVISIONAL_IDS)}"),
        ("all forty nine current sources exist", existing_ids <= current_ids and len(existing_ids) == 49, f"current={len(existing_ids)}"),
        ("application matrix exact", len(matrix) == 51 * 4 * 144 * 6 and report["variants"] == 144, f"scenarios={len(matrix)} variants={report['variants']}"),
        ("cleanse matrix exact", len(cleanse_rows) == 3 * 4 * 144 * 5, f"scenarios={len(cleanse_rows)}"),
        ("all scenarios use five plus three snapshot", all(
            row.active_slots == 5 and row.passive_slots == 3 for row in (*matrix, *cleanse_rows)
        ), "active=5 passive=3"),
        ("all lifecycle branch probes pass", len(probes) == 17 and all(ok for _, ok in probes), f"passed={sum(ok for _, ok in probes)}/{len(probes)}"),
        ("rolled status chance bounded and self costs guaranteed", all(
            final_apply_bps(value_at(application.apply_values, level), 0) <= APPLY_MAX_BPS
            for source in SOURCES for application in source.applications if application.mode == "ROLL"
            for level in (1, 25, 50, 75, 100)
        ) and all(row.max_chance_bps <= 10_000 and row.min_chance_bps >= 0 for row in matrix), f"rolledCap={APPLY_MAX_BPS} guaranteed=10000"),
        ("hostile cap never exceeded", report["maxHostile"] <= HOSTILE_STATUS_CAP, f"max={report['maxHostile']}"),
        ("new statuses per action bounded", all(len(source.applications) <= NEW_STATUS_PER_ACTION_CAP for source in SOURCES), "max=2"),
        ("dot declarations split exactly", all(sum(split_exact(value_at(app.periodic_values, 100), app.periodic_ticks)) == value_at(app.periodic_values, 100) for source in SOURCES for app in source.applications if app.periodic_ticks), "remainder=front exact"),
        ("shock and curse caps fixed", SHOCK_STACK_CAP == 2 and CURSE_SUBTYPE_CAP == 3, "shock=2 curse=3"),
        ("cleanse contracts exact and capped", len(CLEANSES) == 3 and max(item.count_cap for item in CLEANSES) == 2, "cleanse=3 rootCap=2"),
        ("cost locked never cleansed", all(row.cost_locked_remaining for row in cleanse_rows if row.mode == "COST_LOCKED"), "removed=0"),
        ("bleed and shock producer gaps closed", PROVISIONAL_IDS == {"aq.skill.common.v06.bleedingcut", "aq.skill.common.v06.staticflask"}, ",".join(sorted(PROVISIONAL_IDS))),
        ("v05 and registries bound", canonical_payload()["consumerV05Hash"] == consumer.canonical_hash() and canonical_payload()["monsterRegistryHash"] == monsters.canonical_hash(), consumer.canonical_hash()[:12]),
        ("automatic test only contract", not canonical_payload()["manualCombatAction"] and not canonical_payload()["itemGrantedSkills"] and not canonical_payload()["productionEnabled"], "manual=false itemSkill=false production=false"),
    ]


def pd_checks(matrix, cleanse_rows=None):
    result = checks(matrix, cleanse_rows)
    document = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    kotlin = KOTLIN_CONTRACT.read_text(encoding="utf-8") if KOTLIN_CONTRACT.exists() else ""
    result.extend((
        ("document binds v06 hash", canonical_hash() in document, canonical_hash()),
        ("kotlin binds v06 hash", canonical_hash() in kotlin, canonical_hash()),
        ("document keeps production gate", "production 조건부 승인" in document and "라이브 NO-GO" in document, "explicit gate"),
    ))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()
    matrix = application_matrix()
    cleanse_rows = cleanse_matrix()
    if args.summary:
        print(json.dumps(summary(matrix, cleanse_rows), ensure_ascii=False, indent=2, sort_keys=True))
        print(f"canonical_sha256={canonical_hash()}")
        return 0
    result = pd_checks(matrix, cleanse_rows) if args.pd else checks(matrix, cleanse_rows)
    print(f"{'PD' if args.pd else 'BALANCE'}: {sum(ok for _, ok, _ in result)}/{len(result)} PASS")
    for name, ok, detail in result:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"canonical_sha256={canonical_hash()}")
    return 0 if all(ok for _, ok, _ in result) else 1


if __name__ == "__main__":
    raise SystemExit(main())
