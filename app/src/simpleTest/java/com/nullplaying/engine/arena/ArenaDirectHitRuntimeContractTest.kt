package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaDirectHitRuntimeContractTest {
    @Test
    fun `a shield-only direct hit applies judgment and removes its target buff`() {
        val shieldId = "ARENA_SUP_MAGE_01"
        val judgmentId = "ARENA_SUP_PALADIN_03"
        val target = fighter(HeroClass.MAGE, "target").copy(
            supportIds = setOf(shieldId),
            supportRanks = mapOf(shieldId to 1),
            resolvedSupports = mapOf(shieldId to resolvedSupport(shieldId).copy(
                magnitude = 100.0,
                durationTurns = 10,
            )),
        )
        val actor = fighter(HeroClass.PALADIN, "actor").copy(
            supportIds = setOf(judgmentId),
            supportRanks = mapOf(judgmentId to 1),
            resolvedSupports = mapOf(judgmentId to resolvedSupport(judgmentId)),
        )
        val schedule = mapOf(
            ("actor" to 1) to "BASIC_ATTACK",
            ("actor" to 2) to judgmentId,
            ("actor" to 3) to "BASIC_ATTACK",
            ("target" to 1) to shieldId,
            ("target" to 2) to "BASIC_ATTACK",
            ("target" to 3) to "BASIC_ATTACK",
        )

        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            target,
            seed = 101L,
            schedule = schedule,
            rules = rules(3),
        )

        val shieldOnlyHit = result.events.single {
            it.type == ArenaSupportEventType.ATTACK_HIT &&
                it.actorId == "actor" && it.turn == 3
        }
        assertEquals(0.0, shieldOnlyHit.amount, 1e-9)
        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.SHIELD_ABSORBED &&
                it.actorId == "target" && it.turn == 3 && it.amount > 0
        })
        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.SUPPORT_TRIGGERED &&
                it.actorId == "actor" && it.actionId == judgmentId &&
                it.reason == "beneficial_status_removed" && it.turn == 3
        })
        assertEquals(0.0, result.fighters.getValue("target").shield, 1e-9)
    }

    @Test
    fun `a shield-only follow-up hit consumes its already-applied one-shot bonus`() {
        val shieldId = "ARENA_SUP_MAGE_01"
        val markerId = attackId(HeroClass.WARRIOR, 9)
        val strikeId = attackId(HeroClass.WARRIOR, 1)
        val actor = fighter(
            HeroClass.WARRIOR,
            "actor",
            mapOf(
                9 to resolvedAttack(HeroClass.WARRIOR, 9).copy(
                    prepareTurns = 1,
                    mpCost = 0,
                    cooldownTurns = 0,
                    damagePercent = 50,
                    effectKey = "FOLLOW_UP_MARK",
                    effectValues = mapOf("accuracy" to 0.0, "bonus" to 100.0),
                ),
                1 to resolvedAttack(HeroClass.WARRIOR, 1).copy(
                    prepareTurns = 0,
                    mpCost = 0,
                    cooldownTurns = 0,
                    damagePercent = 50,
                    effectKey = "NONE",
                    effectValues = emptyMap(),
                ),
            ),
        )
        val target = fighter(HeroClass.MAGE, "target").copy(
            supportIds = setOf(shieldId),
            supportRanks = mapOf(shieldId to 1),
            resolvedSupports = mapOf(shieldId to resolvedSupport(shieldId).copy(
                magnitude = 100.0,
                durationTurns = 10,
            )),
        )
        val schedule = buildMap {
            put("actor" to 1, markerId)
            put("actor" to 3, strikeId)
            put("actor" to 4, strikeId)
            put("target" to 1, shieldId)
            for (turn in 2..4) put("target" to turn, "BASIC_ATTACK")
        }

        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            target,
            seed = 103L,
            schedule = schedule,
            rules = rules(4),
        )

        assertEquals(1_000.0, result.fighters.getValue("target").hp, 1e-9)
        val absorbedByTurn = result.events.filter {
            it.type == ArenaSupportEventType.SHIELD_ABSORBED && it.actorId == "target"
        }.associate { it.turn to it.amount }
        assertEquals(50.0, absorbedByTurn.getValue(2), 1e-9)
        assertEquals(100.0, absorbedByTurn.getValue(3), 1e-9)
        assertEquals(50.0, absorbedByTurn.getValue(4), 1e-9)
        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.STATUS_APPLIED &&
                it.actionId == markerId && it.reason == "arena_follow_up_mark" && it.turn == 2
        })
    }

    @Test
    fun `hp-damage attack effects do not trigger when a shield absorbs the whole hit`() {
        val shieldId = "ARENA_SUP_MAGE_01"
        val suppressionId = attackId(HeroClass.WARRIOR, 5)
        val actor = fighter(
            HeroClass.WARRIOR,
            "actor",
            mapOf(5 to resolvedAttack(HeroClass.WARRIOR, 5).copy(
                prepareTurns = 1,
                mpCost = 0,
                cooldownTurns = 0,
                damagePercent = 50,
                effectKey = "ATTACK_SUPPRESSION",
                effectValues = mapOf("reduction" to 90.0),
            )),
        )
        val target = fighter(HeroClass.MAGE, "target").copy(
            supportIds = setOf(shieldId),
            supportRanks = mapOf(shieldId to 1),
            resolvedSupports = mapOf(shieldId to resolvedSupport(shieldId).copy(
                magnitude = 100.0,
                durationTurns = 10,
            )),
        )
        val schedule = mapOf(
            ("actor" to 1) to suppressionId,
            ("target" to 1) to shieldId,
            ("target" to 2) to "BASIC_ATTACK",
        )

        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            target,
            seed = 107L,
            schedule = schedule,
            rules = rules(2),
        )

        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.SHIELD_ABSORBED && it.turn == 2
        })
        assertFalse(result.events.any { it.reason == "arena_attack_suppression" })
    }

    @Test
    fun `mage counter rush shortens after an instant hit fully absorbed by a shield`() {
        val actorSupportId = "ARENA_SUP_FIGHTER_01"
        val shieldId = "ARENA_SUP_MAGE_01"
        val instantId = attackId(HeroClass.WARRIOR, 1)
        val counterId = attackId(HeroClass.MAGE, 19)
        val actor = fighter(
            HeroClass.WARRIOR,
            "actor",
            mapOf(1 to resolvedAttack(HeroClass.WARRIOR, 1).copy(
                prepareTurns = 0,
                mpCost = 0,
                cooldownTurns = 0,
                damagePercent = 50,
                effectKey = "NONE",
                effectValues = emptyMap(),
            )),
        ).copy(
            supportIds = setOf(actorSupportId),
            supportRanks = mapOf(actorSupportId to 1),
            resolvedSupports = mapOf(actorSupportId to resolvedSupport(actorSupportId)),
        )
        val victim = fighter(
            HeroClass.MAGE,
            "victim",
            mapOf(19 to resolvedAttack(HeroClass.MAGE, 19).copy(
                mpCost = 0,
                cooldownTurns = 0,
            )),
        ).copy(
            supportIds = setOf(shieldId),
            supportRanks = mapOf(shieldId to 1),
            resolvedSupports = mapOf(shieldId to resolvedSupport(shieldId).copy(
                magnitude = 100.0,
                durationTurns = 10,
            )),
        )
        val schedule = mapOf(
            ("actor" to 1) to actorSupportId,
            ("actor" to 2) to instantId,
            ("actor" to 3) to "BASIC_ATTACK",
            ("victim" to 1) to shieldId,
            ("victim" to 2) to "BASIC_ATTACK",
            ("victim" to 3) to counterId,
        )

        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            victim,
            seed = 109L,
            schedule = schedule,
            rules = rules(3),
        )

        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.ATTACK_HIT &&
                it.actorId == "actor" && it.turn == 2 && it.amount == 0.0
        })
        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.SHIELD_ABSORBED &&
                it.actorId == "victim" && it.turn == 2 && it.amount > 0
        })
        val counterStart = result.events.single {
            it.type == ArenaSupportEventType.CAST_START &&
                it.actorId == "victim" && it.actionId == counterId
        }
        assertEquals(3, counterStart.turn)
        assertEquals(2, counterStart.castTurns)
        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.STATUS_APPLIED &&
                it.actorId == "victim" && it.actionId == counterId &&
                it.reason == "counter_rush_prepare_reduced"
        })
    }

    @Test
    fun `paladin counter records every actually applied support pierce source`() {
        val cases = listOf(
            PierceCase(HeroClass.RANGER, "ARENA_SUP_RANGER_05", useBasic = false),
            PierceCase(HeroClass.RANGER, "ARENA_SUP_RANGER_04", useBasic = false),
            PierceCase(HeroClass.WARRIOR, "ARENA_SUP_FIGHTER_05", useBasic = true),
            PierceCase(HeroClass.MAGE, "ARENA_SUP_MAGE_04", useBasic = false, shieldBypass = true),
        )

        cases.forEachIndexed { index, case ->
            val result = counterResult(case, seed = 200L + index)
            assertTrue("${case.supportId} must arm the Paladin counter", result.events.any {
                it.type == ArenaSupportEventType.STATUS_APPLIED &&
                    it.actorId == "victim" && it.reason == "arena_counter_pierce" && it.turn == 4
            })
        }
    }

    @Test
    fun `pierce effect key without an applied ignore does not arm the paladin counter`() {
        val pierceAttack = resolvedAttack(HeroClass.RANGER, 8).copy(
            prepareTurns = 0,
            mpCost = 0,
            cooldownTurns = 0,
            damagePercent = 50,
            effectKey = "MITIGATION_PIERCE",
            effectValues = mapOf("ignore" to 80.0),
        )
        val actor = fighter(HeroClass.RANGER, "actor", mapOf(8 to pierceAttack))
        val victim = counterVictim(withShield = false, withMitigation = false)
        val counterId = attackId(HeroClass.PALADIN, 19)
        val schedule = mapOf(
            ("actor" to 1) to attackId(HeroClass.RANGER, 8),
            ("actor" to 2) to "BASIC_ATTACK",
            ("victim" to 1) to "BASIC_ATTACK",
            ("victim" to 2) to counterId,
        )

        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            victim,
            seed = 211L,
            schedule = schedule,
            rules = rules(2),
        )

        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.ATTACK_HIT && it.actorId == "actor" && it.turn == 1
        })
        assertFalse(result.events.any { it.reason == "arena_counter_pierce" })
    }

    private fun counterResult(case: PierceCase, seed: Long): ArenaSupportResult {
        val support = resolvedSupport(case.supportId)
        val strikeSlot = if (case.useBasic) null else 1
        val actor = fighter(
            case.heroClass,
            "actor",
            strikeSlot?.let { slot ->
                mapOf(slot to resolvedAttack(case.heroClass, slot).copy(
                    prepareTurns = 0,
                    mpCost = 0,
                    cooldownTurns = 0,
                    damagePercent = 100,
                    effectKey = "NONE",
                    effectValues = emptyMap(),
                ))
            }.orEmpty(),
        ).copy(
            supportIds = setOf(case.supportId),
            supportRanks = mapOf(case.supportId to 1),
            resolvedSupports = mapOf(case.supportId to support),
        )
        val victim = counterVictim(
            withShield = case.shieldBypass,
            withMitigation = !case.shieldBypass,
        )
        val counterId = attackId(HeroClass.PALADIN, 19)
        val strikeId = if (case.useBasic) "BASIC_ATTACK" else attackId(case.heroClass, 1)
        val defenseId = "ARENA_SUP_PALADIN_01"
        val schedule = buildMap {
            put("actor" to 1, "BASIC_ATTACK")
            put("actor" to 2, case.supportId)
            put("actor" to 3, strikeId)
            put("actor" to 4, "BASIC_ATTACK")
            put("victim" to 1, defenseId)
            put("victim" to 2, "BASIC_ATTACK")
            put("victim" to 3, "BASIC_ATTACK")
            put("victim" to 4, counterId)
        }
        return ArenaSupportTurnEngine.simulateScripted(
            actor,
            victim,
            seed = seed,
            schedule = schedule,
            rules = rules(4),
        )
    }

    private fun counterVictim(withShield: Boolean, withMitigation: Boolean): ArenaSupportInput {
        val supportId = "ARENA_SUP_PALADIN_01"
        val kind = when {
            withShield -> ArenaSupportKind.SHIELD
            withMitigation -> ArenaSupportKind.IRON
            else -> ArenaSupportKind.SKILL_GUARD
        }
        val support = resolvedSupport(supportId).copy(
            kind = kind,
            mp = 0,
            durationTurns = 10,
            charges = 0,
            magnitude = if (withShield) 100.0 else 50.0,
            conditionKey = "UTILITY",
        )
        val counter = resolvedAttack(HeroClass.PALADIN, 19).copy(
            prepareTurns = 0,
            mpCost = 0,
            cooldownTurns = 0,
            damagePercent = 50,
            effectKey = "COUNTER_PIERCE",
            effectValues = mapOf("bonus" to 45.0, "pierceReduction" to 30.0),
        )
        return fighter(HeroClass.PALADIN, "victim", mapOf(19 to counter)).copy(
            supportIds = if (withShield || withMitigation) setOf(supportId) else emptySet(),
            supportRanks = if (withShield || withMitigation) mapOf(supportId to 1) else emptyMap(),
            resolvedSupports = if (withShield || withMitigation) mapOf(supportId to support) else emptyMap(),
        )
    }

    private fun fighter(
        heroClass: HeroClass,
        id: String,
        attacks: Map<Int, ArenaResolvedAttack> = emptyMap(),
    ): ArenaSupportInput {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, id)
        val resolvedAttacks = attacks.map { (slot, resolved) ->
            val source = fixture.fighter.attacks.first { it.id == attackId(heroClass, slot) }
            source.copy(
                masteryBonusPercent = 0,
                sourceDamagePercentMin = 0,
                sourceDamagePercentMax = 0,
                arena = resolved,
            )
        }
        return ArenaSupportInput(
            fighter = fixture.fighter.copy(attacks = resolvedAttacks),
            arenaLevel = 100,
        )
    }

    private fun resolvedAttack(heroClass: HeroClass, slot: Int): ArenaResolvedAttack {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, "source-$slot")
        val source = fixture.fighter.attacks.first { it.id == attackId(heroClass, slot) }
        return requireNotNull(ArenaTurnInputAdapter.resolveAttack(source, heroClass, 1).arena)
    }

    private fun resolvedSupport(id: String, rank: Int = 1): ArenaResolvedSupport {
        val definition = requireNotNull(ArenaSupportCatalog.find(id))
        return ArenaSkillTreeCatalog.effectiveSupport(definition, rank).resolved(rank)
    }

    private fun attackId(heroClass: HeroClass, slot: Int): String =
        SkillCatalog.forClass(heroClass)[slot - 1].catalogId

    private fun rules(turns: Int) = ArenaTurnRules(
        safetyTurnLimit = turns,
        hitChance = 1.0,
        damageVariance = 0.0,
        tierScaling = false,
        masteryScaling = false,
        formula = ArenaStatFormula(
            healthBase = 1_000.0,
            healthScale = 0.0,
            attackBase = 100.0,
            attackScale = 0.0,
        ),
    )

    private data class PierceCase(
        val heroClass: HeroClass,
        val supportId: String,
        val useBasic: Boolean,
        val shieldBypass: Boolean = false,
    )
}
