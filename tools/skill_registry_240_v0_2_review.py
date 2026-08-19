#!/usr/bin/env python3
"""Normalize all 240 designed AlarmQuest skills into one vNext registry.

The eight design waves use intentionally different planning dataclasses. This
tool preserves every declared single-axis Lv1/25/50/75/100 value and explicit
cost/cooldown, fills only absent operational fields through declared policies,
and validates Active-5/Passive-3 snapshots. It never edits live Kotlin, assets,
Room, saves, equipment, or characters.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import skill_wave1_active5_passive3_v0_1_review as wave1
import skill_wave2_combo_patterns_v0_1_review as wave2
import skill_wave3_reaction_conversion_debt_delay_v0_1_review as wave3
import skill_wave4_dot_cleanse_diversity_execution_v0_1_review as wave4
import skill_wave5_resource_protection_heal_expedition_v0_1_review as wave5
import skill_wave6_opening_longfight_precision_profiles_v0_1_review as wave6
import skill_wave7_world_monster_boss_v0_1_review as wave7
import skill_wave8_complete_240_global_review as wave8


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "PRODUCT_MEETING_SKILL_REGISTRY_240_v0.2.md"
IDEA_BANK = ROOT / "PRODUCT_MEETING_SKILL_IDEA_BANK_v0.1.md"
RULES_VERSION = "aq.skill-registry.v0.2"
ANCHORS = (1, 25, 50, 75, 100)
CLASSES = tuple(wave1.CLASSES)
ROLES = ("ATTACK", "PRECISION", "STATUS", "CONTROL", "DEFENSE", "HEAL", "RESOURCE", "EXPEDITION", "EXECUTION", "DELAYED", "UTILITY")
SOURCE_MAP = {
    "고유": "CHARACTER_UNIQUE",
    "레벨": "LEVEL",
    "직퀘": "CLASS_QUEST",
    "공용": "COMMON",
    "월드퀘": "WORLD_QUEST",
    "몬스터": "MONSTER_ARCHIVE",
    "보스": "BOSS_SECRET",
    "비밀": "ACCOUNT_SECRET",
}
SOURCE_ALIASES = {
    "BOSS": "BOSS_SECRET",
    "SECRET": "ACCOUNT_SECRET",
}
PASSIVE_RESOURCE_DISCOUNT_CAP_BPS = 1_500
PASSIVE_ACTION_ADD_CAP_BPS = 1_500
INCOMING_REDUCTION_CAP_BPS = 2_500
FINAL_HIT_ADD_CAP_BPS = 1_200
TEMP_EVASION_CAP_BPS = 1_200
STATUS_APPLY_FINAL_CAP_BPS = 9_500
ACTIVE_COST_FLOOR_BPS = 1_000
DERIVED_ACTIVE_COST_CAP_BPS = 3_500
MAX_RESOURCE_COST_BPS = 10_000


@dataclass(frozen=True)
class ActiveSkillDefinition:
    definition_id: str
    idea_id: str
    name_ko: str
    owner_scope: str
    source: str
    design_wave: int
    roles: tuple[str, ...]
    pattern: str
    growth_field: str
    anchor_values: tuple[int, int, int, int, int]
    attack_equivalent_values: tuple[int, int, int, int, int]
    resource_cost_bps: int
    cost_policy: str
    cooldown_roots: int
    cooldown_policy: str
    action_width: int
    hit_packets: int
    final_hit_policy: str
    crit_eligible: bool
    candidate_rule_id: str
    applies_tags: tuple[str, ...]
    consumes_tags: tuple[str, ...]
    duration_roots: int
    fixed_tradeoff: str


@dataclass(frozen=True)
class PassiveSkillDefinition:
    definition_id: str
    idea_id: str
    name_ko: str
    owner_scope: str
    source: str
    design_wave: int
    roles: tuple[str, ...]
    pattern: str
    growth_field: str
    anchor_values: tuple[int, int, int, int, int]
    condition_id: str
    host_scope: str
    stack_group: str
    stack_policy: str
    stack_cap_bps: int
    fixed_tradeoff: str


@dataclass(frozen=True)
class SkillLevelEntry:
    skill_id: str
    skill_level: int


@dataclass(frozen=True)
class SkillLoadoutSnapshot:
    snapshot_version: int
    rules_version: str
    content_hash: str
    hero_class: str
    active_slots: tuple[SkillLevelEntry, ...]
    passive_slots: tuple[SkillLevelEntry, ...]
    behavior_policy_id: str


def idea_bank_rows() -> dict[str, dict[str, str]]:
    rows = {}
    for line in IDEA_BANK.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^\| ([A-Z]+\d{3}) \| ([^|]+?) \| ([^|]+?) \| ([AP]) \|", line)
        if not match:
            continue
        idea_id, name_ko, source_ko, kind = match.groups()
        rows[name_ko.strip()] = {
            "idea_id": idea_id,
            "source": SOURCE_MAP[source_ko.strip()],
            "kind": kind,
        }
    return rows


IDEA_BY_NAME = idea_bank_rows()


def tuple5(values) -> tuple[int, int, int, int, int]:
    result = tuple(int(value) for value in values)
    if len(result) != 5:
        raise AssertionError(result)
    return result


def source_for(plan) -> str:
    source = getattr(plan, "source", IDEA_BY_NAME[plan.name_ko]["source"])
    return SOURCE_ALIASES.get(source, source)


def owner_for(plan) -> str:
    return getattr(plan, "owner_scope", getattr(plan, "owner_class", "ALL"))


def explicit_tags(plan, field: str) -> tuple[str, ...]:
    plural = getattr(plan, f"{field}_tags", ())
    singular = getattr(plan, f"{field}_tag", None)
    result = list(plural)
    if singular:
        result.append(singular)
    return tuple(dict.fromkeys(str(value) for value in result if value))


def attack_values(plan, wave: int) -> tuple[int, int, int, int, int]:
    if wave == 1:
        values = []
        for level in ANCHORS:
            resolved = next(item for item in wave1.active_catalog(level) if item.definition_id == plan.definition_id)
            values.append(resolved.attack_budget_equivalent_bps)
        return tuple5(values)
    explicit = getattr(plan, "attack_equivalent_values", None)
    if explicit is not None:
        return tuple5(explicit)
    growth_field = getattr(plan, "growth_field", "")
    values = tuple5(plan.values)
    if "coefficient" in growth_field:
        return values
    fixed = getattr(plan, "fallback_coefficient_bps", getattr(plan, "fixed_coefficient_bps", 0))
    return (int(fixed),) * 5


def active_width(plan) -> int:
    explicit = getattr(plan, "action_width", getattr(plan, "package_width", getattr(plan, "attack_width", None)))
    if explicit is not None:
        return max(1, int(explicit))
    pattern = getattr(plan, "pattern", "")
    return 2 if any(token in pattern for token in ("DELAYED", "PREPARED", "TWO_ROOT", "PREPAID")) else 1


def infer_roles(pattern: str, growth_field: str, attack: tuple[int, ...], source: str) -> tuple[str, ...]:
    text = f"{pattern} {growth_field}".upper()
    roles = set()
    if max(attack) > 0 or any(token in text for token in ("STRIKE", "ATTACK", "DAMAGE", "BURST", "SHOT")):
        roles.add("ATTACK")
    if any(token in text for token in ("HIT", "ACCURACY", "PRECISION", "CRIT", "AIM")):
        roles.add("PRECISION")
    if any(token in text for token in ("STATUS", "POISON", "BURN", "CHILL", "CURSE", "SHOCK", "BLEED", "EXPOSED", "SLIMED")):
        roles.add("STATUS")
    if any(token in text for token in ("STAGGER", "BIND", "SILENCE", "INTERRUPT", "SLOW", "SUPPRESS", "CONTROL")):
        roles.add("CONTROL")
    if any(token in text for token in ("SHIELD", "BARRIER", "DEFENSE", "GUARD", "MITIGATION", "REFLECT", "EVASION", "DODGE", "RESIST")):
        roles.add("DEFENSE")
    if any(token in text for token in ("HEAL", "CLEANSE", "RECOVERY_HP", "BANDAGE")):
        roles.add("HEAL")
    if any(token in text for token in ("RESOURCE", "COST", "DEBT", "MANA", "ATTRITION")):
        roles.add("RESOURCE")
    if any(token in text for token in ("EXPEDITION", "POST_COMBAT", "FINAL_ENCOUNTER", "REGION", "SAME_FAMILY", "PROFILE_MEMORY")):
        roles.add("EXPEDITION")
    if "EXECUTION" in text or "FINISH" in text:
        roles.add("EXECUTION")
    if any(token in text for token in ("DELAY", "PREPARED", "PREPAID", "REACTION", "TWO_ROOT")):
        roles.add("DELAYED")
    if source in {"WORLD_QUEST", "MONSTER_ARCHIVE", "BOSS_SECRET", "ACCOUNT_SECRET"} and not roles:
        roles.add("UTILITY")
    if not roles:
        roles.add("UTILITY")
    return tuple(role for role in ROLES if role in roles)


def explicit_cost(plan, wave: int) -> int | None:
    if wave == 1:
        return int(plan.resource_cost_bps)
    for field in ("fixed_resource_cost_bps", "fixed_cost_bps"):
        if hasattr(plan, field):
            return int(getattr(plan, field))
    return None


def derive_cost(roles: tuple[str, ...], attack: tuple[int, ...], width: int, source: str) -> int:
    maximum = max(attack)
    if maximum == 0:
        cost = 1_400
    elif maximum <= 4_000:
        cost = 1_200
    elif maximum <= 8_000:
        cost = 1_600
    elif maximum <= 12_000:
        cost = 2_000
    elif maximum <= 16_000:
        cost = 2_500
    else:
        cost = 3_000
    if width >= 2:
        cost += 300
    if "EXECUTION" in roles:
        cost += 300
    if source in {"BOSS_SECRET", "ACCOUNT_SECRET"}:
        cost += 200
    return min(DERIVED_ACTIVE_COST_CAP_BPS, max(ACTIVE_COST_FLOOR_BPS, cost))


def cooldown_for(plan, attack: tuple[int, ...], roles: tuple[str, ...], width: int) -> tuple[int, str]:
    if hasattr(plan, "cooldown_roots"):
        return int(plan.cooldown_roots), "EXPLICIT"
    if hasattr(plan, "cooldown_turns"):
        return int(plan.cooldown_turns), "EXPLICIT"
    if width >= 2 or "EXECUTION" in roles or "DELAYED" in roles:
        return 5, "DERIVED_ROLE_V0_2"
    if max(attack) == 0:
        return 4, "DERIVED_ROLE_V0_2"
    if max(attack) > 14_000:
        return 4, "DERIVED_ROLE_V0_2"
    if "STATUS" in roles or "CONTROL" in roles or "HEAL" in roles or "DEFENSE" in roles:
        return 3, "DERIVED_ROLE_V0_2"
    return 2, "DERIVED_ROLE_V0_2"


def candidate_for(plan) -> str:
    explicit = getattr(plan, "candidate_rule", getattr(plan, "condition_id", None))
    if explicit:
        return str(explicit)
    pattern = getattr(plan, "pattern", "")
    return f"aq.candidate.{pattern.lower()}"


def active_tradeoff(plan, wave: int) -> str:
    explicit = getattr(plan, "fixed_downside", "")
    if explicit:
        return explicit
    if wave == 1:
        return f"자동조건={plan.condition_id}·cost={plan.resource_cost_bps}·cooldown={plan.cooldown_turns}"
    return "조건 미충족 시 후보 제외"


def normalize_active(plan, wave: int) -> ActiveSkillDefinition:
    idea = IDEA_BY_NAME[plan.name_ko]
    growth_field = getattr(plan, "growth_field", None)
    values = getattr(plan, "values", None)
    if wave == 1:
        rank_plan = wave1.legacy.PLAN_BY_ID[plan.definition_id]
        growth_field = rank_plan.field
        values = rank_plan.values
    attack = attack_values(plan, wave)
    source = source_for(plan)
    pattern = getattr(plan, "pattern", getattr(plan, "macro_role", "WAVE1_CORE"))
    roles = infer_roles(pattern, growth_field, attack, source)
    width = active_width(plan)
    cost = explicit_cost(plan, wave)
    cost_policy = "EXPLICIT" if cost is not None else "DERIVED_THREAT_V0_2"
    if cost is None:
        cost = derive_cost(roles, attack, width, source)
    cooldown, cooldown_policy = cooldown_for(plan, attack, roles, width)
    applies = list(explicit_tags(plan, "applies"))
    consumes = list(explicit_tags(plan, "consumes"))
    if wave == 1:
        if plan.status_kind:
            applies.append(plan.status_kind)
    return ActiveSkillDefinition(
        definition_id=plan.definition_id,
        idea_id=idea["idea_id"],
        name_ko=plan.name_ko,
        owner_scope=owner_for(plan),
        source=source,
        design_wave=wave,
        roles=roles,
        pattern=pattern,
        growth_field=str(growth_field),
        anchor_values=tuple5(values),
        attack_equivalent_values=attack,
        resource_cost_bps=cost,
        cost_policy=cost_policy,
        cooldown_roots=max(1, cooldown),
        cooldown_policy=cooldown_policy,
        action_width=width,
        hit_packets=max(0, int(getattr(plan, "hit_packets", getattr(plan, "hit_packet_count", 1)))),
        final_hit_policy=str(getattr(plan, "final_hit_policy", "NORMAL")),
        crit_eligible=bool(getattr(plan, "crit_eligible", True)),
        candidate_rule_id=candidate_for(plan),
        applies_tags=tuple(dict.fromkeys(applies)),
        consumes_tags=tuple(dict.fromkeys(consumes)),
        duration_roots=max(0, int(getattr(plan, "duration_turns", getattr(plan, "status_duration_owner_turns", 0)))),
        fixed_tradeoff=active_tradeoff(plan, wave),
    )


def passive_cap(growth_field: str, values: tuple[int, ...]) -> int:
    field = growth_field.lower()
    if "resource_cost" in field or "cost_reduction" in field or "cost_modifier" in field:
        return PASSIVE_RESOURCE_DISCOUNT_CAP_BPS
    if any(token in field for token in ("attack_add", "coefficient_add", "offense")):
        return PASSIVE_ACTION_ADD_CAP_BPS
    if "incoming_damage" in field or "packet_reduction" in field:
        return INCOMING_REDUCTION_CAP_BPS
    if "hit" in field:
        return FINAL_HIT_ADD_CAP_BPS
    if "evasion" in field:
        return TEMP_EVASION_CAP_BPS
    if "status_apply" in field:
        return STATUS_APPLY_FINAL_CAP_BPS
    return min(3_000, max(1_000, max(abs(value) for value in values) * 3))


def stack_policy(stack_group: str, tradeoff: str) -> str:
    text = f"{stack_group} {tradeoff}".lower()
    if "highest" in text:
        return "HIGHEST"
    if ".trigger." in text or ".generation." in text or "shared" in text:
        return "SHARED_LEDGER"
    if ".mode." in text or stack_group.endswith(".none"):
        return "UNIQUE"
    return "ADD_THEN_CLAMP"


def normalize_passive(plan, wave: int) -> PassiveSkillDefinition:
    idea = IDEA_BY_NAME[plan.name_ko]
    growth_field = getattr(plan, "growth_field", getattr(plan, "effect_field", "unknown"))
    values = tuple5(plan.values)
    pattern = getattr(plan, "pattern", getattr(plan, "role", "PASSIVE"))
    source = source_for(plan)
    condition = str(getattr(plan, "condition_id", "always_if_equipped"))
    stack_group = str(getattr(plan, "stack_group", "aq.stack.unique"))
    tradeoff = str(getattr(plan, "fixed_tradeoff", ""))
    if wave == 1:
        downside_field = getattr(plan, "fixed_downside_field", None)
        downside_value = getattr(plan, "fixed_downside_value", 0)
        tradeoff = (
            f"{downside_field}={downside_value}" if downside_field
            else f"조건부 발동={condition}·미충족 시 효과0"
        )
    return PassiveSkillDefinition(
        definition_id=plan.definition_id,
        idea_id=idea["idea_id"],
        name_ko=plan.name_ko,
        owner_scope=owner_for(plan),
        source=source,
        design_wave=wave,
        roles=infer_roles(pattern, growth_field, (0,) * 5, source),
        pattern=pattern,
        growth_field=str(growth_field),
        anchor_values=values,
        condition_id=condition,
        host_scope=str(getattr(plan, "host_scope", "SELF_OR_HOST_ACTION")),
        stack_group=stack_group,
        stack_policy=stack_policy(stack_group, tradeoff),
        stack_cap_bps=passive_cap(growth_field, values),
        fixed_tradeoff=tradeoff or f"조건부 발동={condition}·미충족 시 효과0",
    )


WAVE_MODULES = (
    (2, wave2),
    (3, wave3),
    (4, wave4),
    (5, wave5),
    (6, wave6),
    (7, wave7),
    (8, wave8),
)


def build_registry() -> tuple[tuple[ActiveSkillDefinition, ...], tuple[PassiveSkillDefinition, ...]]:
    actives = [normalize_active(plan, 1) for plan in wave1.legacy.c1.SKILLS]
    passives = [normalize_passive(plan, 1) for plan in wave1.PASSIVES]
    for wave, module in WAVE_MODULES:
        actives.extend(normalize_active(plan, wave) for plan in module.ACTIVES)
        passives.extend(normalize_passive(plan, wave) for plan in module.PASSIVES)
    return (
        tuple(sorted(actives, key=lambda item: item.definition_id)),
        tuple(sorted(passives, key=lambda item: item.definition_id)),
    )


ACTIVES, PASSIVES = build_registry()
ACTIVE_BY_ID = {item.definition_id: item for item in ACTIVES}
PASSIVE_BY_ID = {item.definition_id: item for item in PASSIVES}


def round_half_up(value: Decimal) -> int:
    return int(value.quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def value_at(values: tuple[int, ...], level: int) -> int:
    safe = max(1, min(100, int(level)))
    for index, right in enumerate(ANCHORS):
        if safe == right:
            return values[index]
        if safe < right:
            left = ANCHORS[index - 1]
            ratio = Decimal(safe - left) / Decimal(right - left)
            return round_half_up(Decimal(values[index - 1]) + Decimal(values[index] - values[index - 1]) * ratio)
    return values[-1]


def resolved_resource_cost(skill: ActiveSkillDefinition, level: int) -> int:
    if skill.growth_field == "resource_cost_bps":
        return value_at(skill.anchor_values, level)
    return skill.resource_cost_bps


def final_resource_cost(skill: ActiveSkillDefinition, level: int, discount_bps: int) -> int:
    base = resolved_resource_cost(skill, level)
    if base <= 0:
        return 0
    bounded_discount = min(PASSIVE_RESOURCE_DISCOUNT_CAP_BPS, max(0, int(discount_bps)))
    discounted = round_half_up(Decimal(base) * Decimal(10_000 - bounded_discount) / Decimal(10_000))
    return max(ACTIVE_COST_FLOOR_BPS, discounted)


def accessible(hero_class: str, kind: str):
    catalog = ACTIVES if kind == "ACTIVE" else PASSIVES
    return tuple(item for item in catalog if item.owner_scope in {hero_class, "ALL"})


def canonical_payload() -> dict:
    return {
        "rulesVersion": RULES_VERSION,
        "wave8SourceHash": wave8.canonical_hash(),
        "anchors": ANCHORS,
        "roles": ROLES,
        "actives": [asdict(item) for item in ACTIVES],
        "passives": [asdict(item) for item in PASSIVES],
        "caps": {
            "passiveResourceDiscountBps": PASSIVE_RESOURCE_DISCOUNT_CAP_BPS,
            "passiveActionAddBps": PASSIVE_ACTION_ADD_CAP_BPS,
            "incomingReductionBps": INCOMING_REDUCTION_CAP_BPS,
            "finalHitAddBps": FINAL_HIT_ADD_CAP_BPS,
            "temporaryEvasionBps": TEMP_EVASION_CAP_BPS,
            "statusApplyFinalBps": STATUS_APPLY_FINAL_CAP_BPS,
            "activeCostFloorBps": ACTIVE_COST_FLOOR_BPS,
            "derivedActiveCostCapBps": DERIVED_ACTIVE_COST_CAP_BPS,
            "maxResourceCostBps": MAX_RESOURCE_COST_BPS,
        },
        "slots": {"active": 5, "passive": 3},
        "skillLevelCap": 100,
        "skillXpShareBps": 1_000,
        "skillXpCap": 20_000_000,
        "itemGrantedSkills": False,
        "manualCombatActions": False,
        "legacyCompatibilityRequired": False,
    }


def canonical_hash() -> str:
    raw = json.dumps(canonical_payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def commit_loadout(
    hero_class: str,
    active_levels: dict[str, int],
    passive_levels: dict[str, int],
    behavior_policy_id: str = "aq.behavior.balanced.v1",
) -> SkillLoadoutSnapshot:
    if hero_class not in CLASSES:
        raise ValueError(hero_class)
    if len(active_levels) > 5 or len(passive_levels) > 3:
        raise ValueError("Active 5 / Passive 3 slot cap")
    if set(active_levels).intersection(passive_levels):
        raise ValueError("same skill cannot occupy both kinds")
    active_access = {item.definition_id for item in accessible(hero_class, "ACTIVE")}
    passive_access = {item.definition_id for item in accessible(hero_class, "PASSIVE")}
    if not set(active_levels).issubset(active_access) or not set(passive_levels).issubset(passive_access):
        raise ValueError("class cannot access selected skill")
    if not all(1 <= level <= 100 for level in (*active_levels.values(), *passive_levels.values())):
        raise ValueError("skill level out of range")
    return SkillLoadoutSnapshot(
        snapshot_version=1,
        rules_version=RULES_VERSION,
        content_hash=canonical_hash(),
        hero_class=hero_class,
        active_slots=tuple(SkillLevelEntry(skill_id, level) for skill_id, level in sorted(active_levels.items())),
        passive_slots=tuple(SkillLevelEntry(skill_id, level) for skill_id, level in sorted(passive_levels.items())),
        behavior_policy_id=behavior_policy_id,
    )


def direction_ok(values: tuple[int, ...]) -> bool:
    return list(values) == sorted(values) or list(values) == sorted(values, reverse=True)


def checks() -> list[tuple[str, bool, str]]:
    all_skills = ACTIVES + PASSIVES
    access = {hero_class: (len(accessible(hero_class, "ACTIVE")), len(accessible(hero_class, "PASSIVE"))) for hero_class in CLASSES}
    roles = Counter(role for item in all_skills for role in item.roles)
    sources = Counter(item.source for item in all_skills)
    wave_counts = Counter(item.design_wave for item in all_skills)
    derived_costs = sum(item.cost_policy != "EXPLICIT" for item in ACTIVES)
    derived_cooldowns = sum(item.cooldown_policy != "EXPLICIT" for item in ACTIVES)
    high_power = [item for item in ACTIVES if max(item.attack_equivalent_values) > 10_000]
    loadout_examples = []
    for hero_class in CLASSES:
        active_ids = {item.definition_id: 100 for item in accessible(hero_class, "ACTIVE")[:5]}
        passive_ids = {item.definition_id: 100 for item in accessible(hero_class, "PASSIVE")[:3]}
        loadout_examples.append(commit_loadout(hero_class, active_ids, passive_ids))
    first_class = CLASSES[0]
    first_actives = accessible(first_class, "ACTIVE")[:5]
    first_passives = accessible(first_class, "PASSIVE")[:3]
    ordered = commit_loadout(
        first_class,
        {item.definition_id: 50 for item in first_actives},
        {item.definition_id: 50 for item in first_passives},
    )
    reversed_order = commit_loadout(
        first_class,
        {item.definition_id: 50 for item in reversed(first_actives)},
        {item.definition_id: 50 for item in reversed(first_passives)},
    )
    signatures = [
        ("A", item.pattern, item.growth_field, item.anchor_values, item.candidate_rule_id, item.fixed_tradeoff)
        for item in ACTIVES
    ] + [
        ("P", item.pattern, item.growth_field, item.anchor_values, item.condition_id, item.fixed_tradeoff)
        for item in PASSIVES
    ]
    return [
        ("240 catalog exact", len(ACTIVES) == 150 and len(PASSIVES) == 90 and len(all_skills) == 240,
         f"active={len(ACTIVES)}, passive={len(PASSIVES)}"),
        ("all IDs ideas and names unique", len({x.definition_id for x in all_skills}) == len({x.idea_id for x in all_skills}) == len({x.name_ko for x in all_skills}) == 240,
         f"ids={len({x.definition_id for x in all_skills})}, ideas={len({x.idea_id for x in all_skills})}, names={len({x.name_ko for x in all_skills})}"),
        ("wave composition preserved", wave_counts == Counter({1: 48, 2: 24, 3: 24, 4: 24, 5: 24, 6: 24, 7: 24, 8: 48}), str(dict(wave_counts))),
        ("source catalog preserved", sum(sources.values()) == 240 and set(sources) <= set(SOURCE_MAP.values()), str(dict(sources))),
        ("single monotone growth axis", all(len(x.anchor_values) == 5 and direction_ok(x.anchor_values) for x in all_skills), "anchors=1/25/50/75/100"),
        ("all roles represented", set(roles) == set(ROLES), str(dict(roles))),
        ("active operational fields complete", all(x.resource_cost_bps in range(0, MAX_RESOURCE_COST_BPS + 1) and x.cooldown_roots >= 1 and x.action_width >= 1 and x.candidate_rule_id and x.fixed_tradeoff for x in ACTIVES),
         f"derivedCosts={derived_costs}, derivedCooldowns={derived_cooldowns}"),
        ("high power keeps counterweight", all(x.fixed_tradeoff and (x.action_width >= 2 or x.resource_cost_bps >= 2_000 or x.final_hit_policy != "NORMAL" or not x.crit_eligible or x.candidate_rule_id != "always") for x in high_power), f"highPower={len(high_power)}"),
        ("passive condition stack and cap complete", all(x.condition_id and x.stack_group and x.stack_policy and x.stack_cap_bps > 0 and x.fixed_tradeoff for x in PASSIVES), f"passives={len(PASSIVES)}"),
        ("per class access 75 plus 45", all(value == (75, 45) for value in access.values()), str(access)),
        ("5 plus 3 snapshots content bound", all(len(x.active_slots) == 5 and len(x.passive_slots) == 3 and x.content_hash == canonical_hash() for x in loadout_examples), f"snapshots={len(loadout_examples)}"),
        ("snapshot order deterministic", ordered == reversed_order, f"hash={ordered.content_hash}"),
        ("no exact mechanical clones", len(signatures) == len(set(signatures)), f"signatures={len(signatures)}"),
        ("cost floor after max passive discount", all(final_resource_cost(x, level, PASSIVE_RESOURCE_DISCOUNT_CAP_BPS) == 0 or final_resource_cost(x, level, PASSIVE_RESOURCE_DISCOUNT_CAP_BPS) >= ACTIVE_COST_FLOOR_BPS for x in ACTIVES for level in ANCHORS), "positive final cost floor=1000"),
        ("no item skill manual combat or legacy", not canonical_payload()["itemGrantedSkills"] and not canonical_payload()["manualCombatActions"] and not canonical_payload()["legacyCompatibilityRequired"], "all=false"),
    ]


def pd_checks() -> list[tuple[str, bool, str]]:
    result = checks()
    document = DOCUMENT.read_text(encoding="utf-8") if DOCUMENT.exists() else ""
    result.append(("document binds registry hash", canonical_hash() in document, canonical_hash()))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if args.json:
        print(json.dumps(canonical_payload(), ensure_ascii=False, indent=2))
        return 0
    result = pd_checks() if args.pd else checks()
    print(f"{'PD' if args.pd else 'BALANCE'}: {sum(ok for _, ok, _ in result)}/{len(result)} PASS")
    for name, ok, detail in result:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"canonical_sha256={canonical_hash()}")
    return 0 if all(ok for _, ok, _ in result) else 1


if __name__ == "__main__":
    raise SystemExit(main())
