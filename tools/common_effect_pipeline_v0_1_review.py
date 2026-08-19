#!/usr/bin/env python3
"""Golden-event reference audit for the AlarmQuest common effect pipeline v0.1.

This planning model intentionally stays independent from the live Kotlin resolver.
It exercises ordering and invariants, not final skill or item coefficients.
"""

from __future__ import annotations

import argparse
import hashlib
from dataclasses import dataclass, field


def mul_bps(value: int, basis_points: int) -> int:
    assert value >= 0
    assert basis_points >= 0
    return (value * basis_points + 5_000) // 10_000


def percent_of_max(max_hp: int, basis_points: int) -> int:
    return mul_bps(max_hp, basis_points)


@dataclass
class Combatant:
    hp: int
    max_hp: int
    shield: int = 0
    barriers: int = 0
    species: str = "NONE"
    species_filter_used: bool = False
    species_guard: int = 0
    orc_guard_used: bool = False
    lineage_element: str = ""
    lineage_budget: int = 0
    statuses: set[str] = field(default_factory=set)


@dataclass(frozen=True)
class DamagePacket:
    amount: int
    raw_amount: int | None = None
    damage_type: str = "PHYSICAL"
    delivery: str = "DIRECT"
    element: str = ""
    hit_roll: int = 0
    hit_bps: int = 10_000
    hostile_status: str = ""
    status_resisted: bool = False
    hit_confirmed: bool = True
    requires_hp_damage: bool = False
    blockable: bool = True


@dataclass(frozen=True)
class DamageReceipt:
    raw_damage: int
    mitigation_reduced: int
    barrier_prevented: int
    hp_damage: int
    overkill_discarded: int
    shield_absorbed: int
    barrier_consumed: int
    species_filtered: int
    guard_absorbed: int
    status_applied: bool
    events: tuple[str, ...]


def resolve_damage(target: Combatant, packet: DamagePacket) -> DamageReceipt:
    assert packet.amount >= 0
    assert packet.delivery in {"DIRECT", "PERIODIC", "ENVIRONMENT", "COST"}
    raw_damage = packet.amount if packet.raw_amount is None else packet.raw_amount
    assert raw_damage >= packet.amount
    assert target.max_hp >= 1
    events: list[str] = ["DAMAGE_PACKET_READY"]
    if not packet.hit_confirmed:
        return DamageReceipt(
            raw_damage=0,
            mitigation_reduced=0,
            barrier_prevented=0,
            hp_damage=0,
            overkill_discarded=0,
            shield_absorbed=0,
            barrier_consumed=0,
            species_filtered=0,
            guard_absorbed=0,
            status_applied=False,
            events=("DAMAGE_PACKET_READY", "MISS_PACKET_SKIPPED"),
        )
    mitigation_reduced = raw_damage - packet.amount
    remaining = packet.amount
    barrier_prevented = 0
    shield_absorbed = 0
    barrier_consumed = 0
    species_filtered = 0
    guard_absorbed = 0

    direct = packet.delivery == "DIRECT"
    protection_allowed = packet.blockable

    hostile_packet = remaining > 0 or bool(packet.hostile_status)
    if direct and protection_allowed and hostile_packet and target.barriers > 0:
        target.barriers -= 1
        barrier_prevented = remaining
        remaining = 0
        barrier_consumed = 1
        events.append("BARRIER_CONSUMED")
    else:
        events.append("BARRIER_PASSED")

    if direct and protection_allowed and remaining > 0 and target.shield > 0:
        shield_absorbed = min(target.shield, remaining)
        target.shield -= shield_absorbed
        remaining -= shield_absorbed
        events.append("SHIELD_ABSORBED")
    else:
        events.append("SHIELD_PASSED")

    if direct and protection_allowed and remaining > 0:
        if (
            target.species == "DWARF"
            and packet.damage_type == "PHYSICAL"
            and not target.species_filter_used
        ):
            species_filtered = min(mul_bps(remaining, 2_000), percent_of_max(target.max_hp, 300))
            if species_filtered > 0:
                remaining -= species_filtered
                target.species_filter_used = True
                events.append("SPECIES_FILTER_DWARF")
            else:
                events.append("SPECIES_FILTER_PASSED")
        elif (
            target.species == "HALFLING"
            and packet.hit_bps - 400 <= packet.hit_roll < packet.hit_bps
            and not target.species_filter_used
        ):
            species_filtered = min(remaining, percent_of_max(target.max_hp, 300))
            remaining -= species_filtered
            target.species_filter_used = True
            events.append("SPECIES_FILTER_HALFLING")
        elif (
            target.species == "DRAGONKIN"
            and packet.damage_type != "TRUE"
            and packet.element
            and packet.element == target.lineage_element
            and target.lineage_budget > 0
        ):
            species_filtered = min(mul_bps(remaining, 2_000), target.lineage_budget)
            remaining -= species_filtered
            target.lineage_budget -= species_filtered
            events.append("SPECIES_FILTER_DRAGONKIN")
        else:
            events.append("SPECIES_FILTER_PASSED")
    else:
        events.append("SPECIES_FILTER_PASSED")

    # Guard is a numeric post-filter pool for DIRECT/PERIODIC packets only.
    # TRUE still means defense-ignoring. Other delivery types and an explicit
    # BYPASS_PROTECTION packet bypass Guard.
    guard_delivery_allowed = packet.delivery in {"DIRECT", "PERIODIC"}
    if protection_allowed and guard_delivery_allowed and remaining > 0 and target.species_guard > 0:
        guard_absorbed = min(target.species_guard, remaining)
        target.species_guard -= guard_absorbed
        remaining -= guard_absorbed
        events.append("SPECIES_GUARD_ABSORBED")
    else:
        events.append("SPECIES_GUARD_PASSED")

    before_hp = target.hp
    hp_damage = min(target.hp, remaining)
    overkill_discarded = remaining - hp_damage
    target.hp -= hp_damage
    events.append("HP_COMMITTED")

    crossed_orc_threshold = (
        target.species == "ORC"
        and not target.orc_guard_used
        and before_hp * 10_000 > target.max_hp * 3_000
        and 0 < target.hp * 10_000 <= target.max_hp * 3_000
    )
    if crossed_orc_threshold:
        target.species_guard = percent_of_max(target.max_hp, 400)
        target.orc_guard_used = True
        events.append("ORC_GUARD_CREATED_FOR_NEXT_PACKET")

    status_applied = False
    if packet.hostile_status:
        if barrier_consumed:
            events.append("HOSTILE_STATUS_BLOCKED_BY_BARRIER")
        elif target.hp <= 0:
            events.append("HOSTILE_STATUS_SKIPPED_DEAD_TARGET")
        elif packet.requires_hp_damage and hp_damage == 0:
            events.append("HOSTILE_STATUS_SKIPPED_NO_HP_DAMAGE")
        elif packet.status_resisted:
            events.append("HOSTILE_STATUS_RESISTED")
        else:
            target.statuses.add(packet.hostile_status)
            status_applied = True
            events.append("HOSTILE_STATUS_APPLIED")

    events.append("ACTUAL_HP_DAMAGE_RECORDED")
    receipt = DamageReceipt(
        raw_damage=raw_damage,
        mitigation_reduced=mitigation_reduced,
        barrier_prevented=barrier_prevented,
        hp_damage=hp_damage,
        overkill_discarded=overkill_discarded,
        shield_absorbed=shield_absorbed,
        barrier_consumed=barrier_consumed,
        species_filtered=species_filtered,
        guard_absorbed=guard_absorbed,
        status_applied=status_applied,
        events=tuple(events),
    )
    assert receipt.raw_damage == (
        receipt.mitigation_reduced
        + receipt.barrier_prevented
        + receipt.shield_absorbed
        + receipt.species_filtered
        + receipt.guard_absorbed
        + receipt.hp_damage
        + receipt.overkill_discarded
    )
    return receipt


def resolve_heal(target: Combatant, base_heal: int, modifier_bps: int = 10_000) -> tuple[int, int, tuple[str, ...]]:
    assert base_heal >= 0
    events = ["HEAL_PACKET_READY"]
    if target.hp <= 0:
        return 0, 0, ("HEAL_PACKET_READY", "HEAL_REJECTED_DEAD_TARGET")
    final_modifier = min(15_000, max(2_000, modifier_bps))
    attempted = mul_bps(base_heal, final_modifier)
    applied = min(target.max_hp - target.hp, attempted)
    target.hp += applied
    overheal = attempted - applied
    events.extend(("HEAL_MODIFIER_CLAMPED", "HP_HEALED", "OVERHEAL_RECORDED"))
    return applied, overheal, tuple(events)


def consume_core_triplet(values: list[int], hit_bps: int, critical_bps: int) -> tuple[bool, bool, int, int]:
    assert len(values) >= 3
    hit_roll, critical_roll, variance_roll = values[:3]
    hit = hit_roll < hit_bps
    critical = hit and critical_roll < critical_bps
    return hit, critical, variance_roll, 3


def secondary_roll(
    encounter_seed: int,
    root_action_id: str,
    effect_instance_id: str,
    target_id: str,
    application_index: int,
    rules_version: int,
) -> int:
    payload = "|".join(
        (
            str(encounter_seed),
            root_action_id,
            effect_instance_id,
            target_id,
            str(application_index),
            str(rules_version),
        )
    ).encode("utf-8")
    return int.from_bytes(hashlib.blake2b(payload, digest_size=8).digest(), "big") % 10_000


@dataclass(frozen=True)
class EffectKey:
    phase: int
    priority: int
    stack_group: str
    source_id: str
    effect_definition_id: str
    effect_instance_id: str


def stable_effects(effects: list[EffectKey]) -> list[EffectKey]:
    instance_ids = [effect.effect_instance_id for effect in effects]
    assert len(instance_ids) == len(set(instance_ids)), "duplicate effectInstanceId"
    return sorted(
        effects,
        key=lambda effect: (
            effect.phase,
            effect.priority,
            effect.stack_group,
            effect.source_id,
            effect.effect_definition_id,
            effect.effect_instance_id,
        ),
    )


def run_golden_scenarios() -> list[str]:
    passed: list[str] = []

    hit, critical, variance, consumed = consume_core_triplet([9_950, 10, 5_000], 9_000, 2_000)
    assert not hit and not critical and variance == 5_000 and consumed == 3
    miss_target = Combatant(hp=100, max_hp=100, barriers=1)
    miss = resolve_damage(
        miss_target,
        DamagePacket(amount=40, hostile_status="POISON", hit_confirmed=hit),
    )
    assert miss.hp_damage == 0 and not miss.status_applied and miss_target.barriers == 1
    passed.append("MISS_CONSUMES_HIT_CRIT_VARIANCE")

    barrier_target = Combatant(hp=100, max_hp=100, shield=30, barriers=1)
    barrier = resolve_damage(
        barrier_target,
        DamagePacket(amount=40, hostile_status="VULNERABLE"),
    )
    assert (barrier.hp_damage, barrier.barrier_consumed, barrier_target.shield) == (0, 1, 30)
    assert not barrier.status_applied and "HOSTILE_STATUS_BLOCKED_BY_BARRIER" in barrier.events
    passed.append("BARRIER_CANCELS_PACKET_AND_ATTACHED_STATUS")

    zero_damage_target = Combatant(hp=100, max_hp=100, barriers=1)
    zero_damage = resolve_damage(
        zero_damage_target,
        DamagePacket(amount=0, hostile_status="VULNERABLE"),
    )
    assert zero_damage.barrier_consumed == 1 and not zero_damage.status_applied
    assert zero_damage_target.barriers == 0
    passed.append("BARRIER_BLOCKS_ZERO_DAMAGE_HOSTILE_PACKET")

    shield_target = Combatant(hp=100, max_hp=100, shield=40, species="DWARF")
    shield = resolve_damage(shield_target, DamagePacket(amount=30, hostile_status="POISON"))
    assert shield.hp_damage == 0 and shield.shield_absorbed == 30
    assert not shield_target.species_filter_used and shield.status_applied
    passed.append("SHIELD_PRECEDES_SPECIES_BUT_DOES_NOT_CANCEL_STATUS")

    hp_required_target = Combatant(hp=100, max_hp=100, shield=30)
    hp_required = resolve_damage(
        hp_required_target,
        DamagePacket(amount=20, hostile_status="POISON", requires_hp_damage=True),
    )
    assert hp_required.hp_damage == 0 and not hp_required.status_applied
    assert "HOSTILE_STATUS_SKIPPED_NO_HP_DAMAGE" in hp_required.events
    passed.append("REQUIRES_HP_DAMAGE_STATUS_SKIPS_ZERO_HP_DAMAGE")

    dwarf_target = Combatant(hp=100, max_hp=100, shield=10, species="DWARF")
    dwarf = resolve_damage(dwarf_target, DamagePacket(amount=30))
    assert dwarf.shield_absorbed == 10 and dwarf.species_filtered == 3 and dwarf.hp_damage == 17
    assert dwarf.events.index("SHIELD_ABSORBED") < dwarf.events.index("SPECIES_FILTER_DWARF")
    passed.append("DWARF_FILTERS_REMAINING_DIRECT_PHYSICAL_HP_DAMAGE")

    halfling_target = Combatant(hp=100, max_hp=100, species="HALFLING")
    halfling = resolve_damage(
        halfling_target,
        DamagePacket(amount=3, hit_roll=8_750, hit_bps=9_000, hostile_status="VULNERABLE"),
    )
    assert halfling.species_filtered == 3 and halfling.hp_damage == 0 and halfling.status_applied
    passed.append("HALFLING_FILTER_IS_NOT_A_MISS")

    dragon_target = Combatant(
        hp=100,
        max_hp=100,
        species="DRAGONKIN",
        lineage_element="FIRE",
        lineage_budget=4,
    )
    dragon_first = resolve_damage(dragon_target, DamagePacket(amount=15, damage_type="MAGICAL", element="FIRE"))
    dragon_second = resolve_damage(dragon_target, DamagePacket(amount=15, damage_type="MAGICAL", element="FIRE"))
    assert dragon_first.species_filtered == 3 and dragon_second.species_filtered == 1
    assert dragon_target.lineage_budget == 0
    passed.append("DRAGONKIN_USES_ENCOUNTER_BUDGET")

    orc_target = Combatant(hp=35, max_hp=100, species="ORC")
    orc_cross = resolve_damage(orc_target, DamagePacket(amount=10))
    assert orc_cross.hp_damage == 10 and orc_target.hp == 25 and orc_target.species_guard == 4
    assert orc_cross.guard_absorbed == 0
    orc_next = resolve_damage(orc_target, DamagePacket(amount=10))
    assert orc_next.guard_absorbed == 4 and orc_next.hp_damage == 6 and orc_target.species_guard == 0
    passed.append("ORC_GUARD_STARTS_WITH_NEXT_PACKET")

    periodic_dwarf = Combatant(hp=50, max_hp=100, shield=20, barriers=2, species="DWARF")
    periodic = resolve_damage(
        periodic_dwarf,
        DamagePacket(amount=8, damage_type="TRUE", delivery="PERIODIC"),
    )
    assert periodic_dwarf.shield == 20 and periodic_dwarf.barriers == 2
    assert not periodic_dwarf.species_filter_used and periodic.guard_absorbed == 0 and periodic.hp_damage == 8
    periodic_orc = Combatant(
        hp=50,
        max_hp=100,
        species="ORC",
        species_guard=3,
        orc_guard_used=True,
    )
    guarded_periodic = resolve_damage(
        periodic_orc,
        DamagePacket(amount=8, damage_type="TRUE", delivery="PERIODIC"),
    )
    assert guarded_periodic.guard_absorbed == 3 and guarded_periodic.hp_damage == 5
    passed.append("PERIODIC_BYPASSES_BARRIER_SHIELD_DIRECT_FILTER")

    for delivery in ("ENVIRONMENT", "COST"):
        delivery_target = Combatant(
            hp=40,
            max_hp=100,
            species="ORC",
            species_guard=5,
            orc_guard_used=True,
        )
        delivery_receipt = resolve_damage(
            delivery_target,
            DamagePacket(amount=8, damage_type="TRUE", delivery=delivery),
        )
        assert delivery_receipt.guard_absorbed == 0
        assert delivery_receipt.hp_damage == 8
        assert delivery_target.species_guard == 5
    reflect_target = Combatant(hp=40, max_hp=100, species_guard=5, orc_guard_used=True)
    try:
        resolve_damage(
            reflect_target,
            DamagePacket(amount=8, damage_type="TRUE", delivery="REFLECT"),
        )
    except AssertionError:
        pass
    else:
        raise AssertionError("inactive REFLECT delivery was accepted")
    assert reflect_target.hp == 40 and reflect_target.species_guard == 5
    passed.append("ENVIRONMENT_COST_BYPASS_GUARD_AND_REFLECT_IS_REJECTED")

    bypass_cases = (
        Combatant(hp=100, max_hp=100, shield=20, barriers=1, species="DWARF"),
        Combatant(hp=100, max_hp=100, shield=20, barriers=1, species="HALFLING"),
        Combatant(
            hp=100,
            max_hp=100,
            shield=20,
            barriers=1,
            species="DRAGONKIN",
            lineage_element="FIRE",
            lineage_budget=4,
        ),
        Combatant(
            hp=100,
            max_hp=100,
            shield=20,
            barriers=1,
            species="ORC",
            species_guard=10,
            orc_guard_used=True,
        ),
    )
    for bypass_target in bypass_cases:
        before_budget = bypass_target.lineage_budget
        before_guard = bypass_target.species_guard
        bypass = resolve_damage(
            bypass_target,
            DamagePacket(
                amount=20,
                damage_type="MAGICAL" if bypass_target.species == "DRAGONKIN" else "PHYSICAL",
                element="FIRE",
                hit_roll=8_800,
                hit_bps=9_000,
                blockable=False,
            ),
        )
        assert bypass.hp_damage == 20 and bypass.species_filtered == 0 and bypass.guard_absorbed == 0
        assert bypass_target.shield == 20 and bypass_target.barriers == 1
        assert bypass_target.lineage_budget == before_budget and bypass_target.species_guard == before_guard
    passed.append("UNBLOCKABLE_DIRECT_BYPASSES_ALL_FOUR_SPECIES_PROTECTIONS")

    dead_target = Combatant(hp=5, max_hp=100)
    lethal = resolve_damage(dead_target, DamagePacket(amount=20, raw_amount=35, hostile_status="POISON"))
    assert lethal.hp_damage == 5 and not lethal.status_applied
    assert lethal.mitigation_reduced == 15 and lethal.overkill_discarded == 15
    assert "HOSTILE_STATUS_SKIPPED_DEAD_TARGET" in lethal.events
    passed.append("DAMAGE_LEDGER_PRESERVES_MITIGATION_AND_OVERKILL")

    heal_target = Combatant(hp=90, max_hp=100, shield=7, barriers=1, species="ORC", orc_guard_used=True)
    applied, overheal, _ = resolve_heal(heal_target, base_heal=20)
    assert applied == 10 and overheal == 10 and heal_target.shield == 7 and heal_target.barriers == 1
    assert heal_target.orc_guard_used
    passed.append("OVERHEAL_DOES_NOT_CREATE_PROTECTION_OR_REARM_TRAIT")

    dead_heal_target = Combatant(hp=0, max_hp=100)
    applied, overheal, events = resolve_heal(dead_heal_target, base_heal=50)
    assert applied == 0 and overheal == 0 and events[-1] == "HEAL_REJECTED_DEAD_TARGET"
    passed.append("HEAL_DOES_NOT_RESURRECT")

    roll_a = secondary_roll(77, "action.9", "instance.poison", "target.a", 0, 1)
    roll_a_replay = secondary_roll(77, "action.9", "instance.poison", "target.a", 0, 1)
    roll_b = secondary_roll(77, "action.9", "instance.poison", "target.b", 0, 1)
    assert roll_a == roll_a_replay and roll_a != roll_b
    assert consume_core_triplet([100, 200, 300], 9_000, 2_000) == (True, True, 300, 3)
    passed.append("SECONDARY_RNG_IS_TARGET_KEYED_AND_CORE_INDEPENDENT")

    effects = [
        EffectKey(30, 0, "species", "source.b", "effect.z", "instance.z"),
        EffectKey(20, 5, "shield", "source.a", "effect.shared", "instance.b"),
        EffectKey(20, -5, "barrier", "source.c", "effect.c", "instance.c"),
        EffectKey(20, -5, "barrier", "source.a", "effect.a", "instance.a"),
        EffectKey(30, 0, "species", "source.c", "effect.shared", "instance.shared.c"),
    ]
    forward = [effect.effect_instance_id for effect in stable_effects(effects)]
    reverse = [effect.effect_instance_id for effect in stable_effects(list(reversed(effects)))]
    assert forward == reverse == [
        "instance.a",
        "instance.c",
        "instance.b",
        "instance.z",
        "instance.shared.c",
    ]
    try:
        stable_effects([effects[0], effects[0]])
    except AssertionError:
        pass
    else:
        raise AssertionError("duplicate effectInstanceId was accepted")
    passed.append("EFFECT_DEFINITIONS_CAN_REPEAT_BUT_INSTANCE_IDS_CANNOT")

    return passed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", action="store_true", help="run and print the complete reference gate")
    args = parser.parse_args()

    passed = run_golden_scenarios()
    print(f"COMMON_EFFECT_PIPELINE_V0_1: PASS ({len(passed)}/{len(passed)})")
    for name in passed:
        print(f"  PASS {name}")
    if args.pd:
        print("REFERENCE_GATE: PASS (ordering/determinism only; skill-item balance remains pending)")


if __name__ == "__main__":
    main()
