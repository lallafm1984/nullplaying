package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic execution fixtures for the six V9 A02 traits, not balance sampling. */
class ArenaSupportA02Test {
    private val rules = ArenaTurnRules(
        mpMode = ArenaMpMode.FIXED_100,
        tierScaling = false,
        masteryScaling = false,
        safetyTurnLimit = 100,
        formula = ArenaStatFormula(
            healthBase = 500.0,
            healthScale = 0.0,
            attackBase = 10.0,
            attackScale = 0.0,
            defenseScale = 0.0,
        ),
        hitChance = 1.0,
        damageVariance = 0.0,
        initiativeSensitivity = 0.0,
        initiativeMaxEdge = 0.0,
    )

    @Test
    fun `warrior alternating completions prepare one basic bonus while A01 remains executable`() {
        val a01 = ArenaProgressionCatalog.WARRIOR_A01
        val a02 = ArenaProgressionCatalog.WARRIOR_A02
        val actor = fighter(
            HeroClass.WARRIOR,
            "actor",
            traitIds = listOf(a01, a02),
            attackTiers = setOf(1),
        )
        val target = fighter(HeroClass.MAGE, "target")

        val result = simulate(actor, target)
        val prepared = traitEvent(result, a02, "prepared")
        val applied = traitEvent(result, a02, "damage_bonus_applied")
        val damagingPairEnd = result.events.single { it.sequence == prepared.causeSequence }
        val hit = matchingHit(result, applied)

        assertEquals(ArenaSupportEventType.ATTACK_HIT, damagingPairEnd.type)
        assertEquals("BASIC_ATTACK", applied.actionId)
        assertEquals(damagingPairEnd.sequence, applied.causeSequence)
        assertEquals(5.0, applied.traitValue!!, 0.0)
        assertEquals(10.5, hit.amount, 1e-9)
        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.CAST_START && it.actorId == actor.fighter.id &&
                it.traitId == a01 && it.traitValue == 10.0
        })

        val reversed = actor.copy(traits = actor.traits.reversed())
        assertEquals(result, simulate(reversed, target))
    }

    @Test
    fun `rogue one turn owned attack consumes stealth with one additive A02 bonus`() {
        val a02 = ArenaProgressionCatalog.ROGUE_A02
        val actor = fighter(
            HeroClass.ROGUE,
            "actor",
            traitIds = listOf(a02),
            attackTiers = setOf(1),
            supports = setOf("ARENA_SUP_ROGUE_01"),
        )
        val result = simulate(actor, fighter(HeroClass.WARRIOR, "target"))
        val applied = traitEvent(result, a02, "stealth_damage_bonus_applied")
        val hit = matchingHit(result, applied)

        assertTrue(applied.actionId != "BASIC_ATTACK")
        assertEquals(1, applied.castTurns)
        assertEquals(5.0, applied.traitValue!!, 0.0)
        // 10 attack * 1.25 skill units * (1 + 100% stealth + 5% A02), once.
        assertEquals(25.625, hit.amount, 1e-9)
        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.EFFECT_EXPIRED &&
                it.actionId == "ARENA_SUP_ROGUE_01" && it.reason == "consumed"
        })
    }

    @Test
    fun `ranger basic completed into an enemy long cast gains the A02 direct bonus`() {
        val a02 = ArenaProgressionCatalog.RANGER_A02
        val actor = fighter(HeroClass.RANGER, "actor", traitIds = listOf(a02))
        val target = fighter(HeroClass.MAGE, "target", attackTiers = setOf(2))
        val result = simulate(actor, target)
        val applied = traitEvent(result, a02, "casting_damage_bonus_applied")
        val hit = matchingHit(result, applied)
        val enemyCast = result.events.first {
            it.type == ArenaSupportEventType.CAST_START && it.actorId == target.fighter.id
        }

        assertEquals("BASIC_ATTACK", applied.actionId)
        assertEquals(applied.turn, enemyCast.turn)
        assertEquals(2, enemyCast.castTurns)
        assertEquals(6.0, applied.traitValue!!, 0.0)
        assertEquals(10.6, hit.amount, 1e-9)
    }

    @Test
    fun `mage condensation started above half mana pays the exact A02 discounted ledger cost`() {
        val a02 = ArenaProgressionCatalog.MAGE_A02
        val actor = fighter(
            HeroClass.MAGE,
            "actor",
            traitIds = listOf(a02),
            attackTiers = setOf(3),
            supports = setOf("ARENA_SUP_MAGE_07"),
        )
        val result = simulate(actor, fighter(HeroClass.WARRIOR, "target"))
        val applied = traitEvent(result, a02, "discount_paid")
        val start = result.events.single { it.sequence == applied.causeSequence }

        assertEquals(ArenaSupportEventType.CAST_START, start.type)
        assertEquals("ARENA_SUP_MAGE_07", start.actionId)
        assertEquals(100_000, start.mpBeforeUnits)
        assertEquals(86_500, start.mpAfterUnits)
        assertEquals(13.5, start.amount, 0.0)
        assertEquals(10.0, applied.traitValue!!, 0.0)
    }

    @Test
    fun `cleric five percent actual heal prepares and spends one exact skill discount`() {
        val a02 = ArenaProgressionCatalog.CLERIC_A02
        val actor = fighter(
            HeroClass.CLERIC,
            "actor",
            traitIds = listOf(a02),
            attackTiers = setOf(1),
            supports = setOf("ARENA_SUP_CLERIC_01"),
        )
        val target = fighter(HeroClass.WARRIOR, "target", attackTiers = setOf(3))
        val result = simulate(actor, target)
        val prepared = traitEvent(result, a02, "prepared")
        val heal = result.events.single { it.sequence == prepared.causeSequence }
        val discounted = result.events.first {
            it.type == ArenaSupportEventType.CAST_START && it.actorId == actor.fighter.id &&
                it.traitId == a02
        }

        assertEquals(ArenaSupportEventType.HEAL_APPLIED, heal.type)
        assertTrue(heal.amount >= 25.0)
        assertEquals(heal.turn + 3, prepared.effectExpiresAtTurn)
        assertTrue(discounted.turn in (heal.turn + 1)..(heal.turn + 3))
        assertTrue(discounted.actionId != "BASIC_ATTACK")
        assertEquals(10.0, discounted.traitValue!!, 0.0)
        assertEquals(5_400, discounted.mpBeforeUnits!! - discounted.mpAfterUnits!!)
    }

    @Test
    fun `paladin long attack started under sacred focus pays A02 discount without changing focus accuracy`() {
        val a02 = ArenaProgressionCatalog.PALADIN_A02
        val actor = fighter(
            HeroClass.PALADIN,
            "actor",
            traitIds = listOf(a02),
            attackTiers = setOf(2),
            supports = setOf("ARENA_SUP_PALADIN_09"),
        )
        val lowAccuracyRules = rules.copy(hitChance = 0.1)
        val result = simulate(actor, fighter(HeroClass.WARRIOR, "target"), lowAccuracyRules)
        val applied = traitEvent(result, a02, "discount_paid")
        val start = result.events.single { it.sequence == applied.causeSequence }

        assertEquals(ArenaSupportEventType.CAST_START, start.type)
        assertTrue(start.actionId != "BASIC_ATTACK")
        assertEquals(2, start.castTurns)
        assertEquals(10.0, applied.traitValue!!, 0.0)
        assertEquals(7_200, start.mpBeforeUnits!! - start.mpAfterUnits!!)
        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.SUPPORT_APPLIED &&
                it.actorId == actor.fighter.id && it.actionId == "ARENA_SUP_PALADIN_09"
        })
    }

    private fun simulate(
        actor: ArenaSupportInput,
        target: ArenaSupportInput,
        appliedRules: ArenaTurnRules = rules,
    ): ArenaSupportResult {
        val actorBefore = actor.copy()
        val targetBefore = target.copy()
        val first = ArenaSupportTurnEngine.simulate(actor, target, 0L, appliedRules)
        assertEquals(first, ArenaSupportTurnEngine.simulate(actor, target, 0L, appliedRules))
        assertEquals(actorBefore, actor)
        assertEquals(targetBefore, target)
        assertEquals(ARENA_SUPPORT_RULES_VERSION, first.rulesVersion)
        return first
    }

    private fun traitEvent(
        result: ArenaSupportResult,
        traitId: String,
        reason: String,
    ): ArenaSupportEvent = assertNotNull(result.events.firstOrNull {
        it.type == ArenaSupportEventType.TRAIT_TRIGGERED &&
            it.traitId == traitId && it.reason == reason
    }).let { result.events.first {
        it.type == ArenaSupportEventType.TRAIT_TRIGGERED &&
            it.traitId == traitId && it.reason == reason
    } }

    private fun matchingHit(result: ArenaSupportResult, trait: ArenaSupportEvent): ArenaSupportEvent =
        result.events.single {
            it.type == ArenaSupportEventType.ATTACK_HIT &&
                it.actorId == trait.actorId && it.castId == trait.castId
        }

    private fun fighter(
        heroClass: HeroClass,
        id: String,
        traitIds: List<String> = emptyList(),
        attackTiers: Set<Int> = emptySet(),
        supports: Set<String> = emptySet(),
    ): ArenaSupportInput {
        val source = ArenaSupportQaFixtures.fighter(heroClass, 20, id, arenaLevel = 10)
        return source.copy(
            fighter = source.fighter.copy(
                stats = ArenaCoreStats(
                    strength = 1.0,
                    constitution = 1.0,
                    dexterity = 1.0,
                    intelligence = 1.0,
                    wisdom = 1.0,
                    charisma = 1.0,
                    rawMaxHealth = 1.0,
                    rawMaxMana = 1.0,
                ),
                attacks = source.fighter.attacks.filter { it.tier in attackTiers },
            ),
            supportIds = supports,
            traits = traitIds.map { ArenaSupportTraitRank(it, rank = 5) },
        )
    }
}
