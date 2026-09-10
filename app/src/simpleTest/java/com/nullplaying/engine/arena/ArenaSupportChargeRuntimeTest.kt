package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runtime proofs for the skill-tree rank-10 additional-use contract. */
class ArenaSupportChargeRuntimeTest {
    @Test
    fun `rank ten adds one use to exactly the 25 additional-use supports`() {
        val expected = setOf(
            "ARENA_SUP_FIGHTER_03",
            "ARENA_SUP_FIGHTER_04",
            "ARENA_SUP_FIGHTER_05",
            "ARENA_SUP_FIGHTER_08",
            "ARENA_SUP_FIGHTER_09",
            "ARENA_SUP_FIGHTER_10",
            "ARENA_SUP_ROGUE_01",
            "ARENA_SUP_ROGUE_03",
            "ARENA_SUP_ROGUE_04",
            "ARENA_SUP_ROGUE_05",
            "ARENA_SUP_ROGUE_09",
            "ARENA_SUP_ROGUE_11",
            "ARENA_SUP_RANGER_08",
            "ARENA_SUP_RANGER_10",
            "ARENA_SUP_MAGE_02",
            "ARENA_SUP_MAGE_07",
            "ARENA_SUP_MAGE_11",
            "ARENA_SUP_MAGE_12",
            "ARENA_SUP_CLERIC_05",
            "ARENA_SUP_CLERIC_07",
            "ARENA_SUP_PALADIN_01",
            "ARENA_SUP_PALADIN_02",
            "ARENA_SUP_PALADIN_08",
            "ARENA_SUP_PALADIN_09",
            "ARENA_SUP_PALADIN_10",
        )

        assertEquals(25, expected.size)
        val actual = ArenaSupportCatalog.values.filter { definition ->
            val rankNine = ArenaSkillTreeCatalog.effectiveSupport(definition, 9)
            val rankTen = ArenaSkillTreeCatalog.effectiveSupport(definition, 10)
            rankTen.charges == rankNine.charges + 1
        }.mapTo(linkedSetOf()) { it.id }

        assertEquals(expected, actual)
    }

    @Test
    fun `rank ten timed right survives its first trigger and is consumed by its second`() {
        val supportId = "ARENA_SUP_MAGE_07"
        val attackId = attackId(HeroClass.MAGE)
        val schedule = mapOf(
            ("actor" to 1) to supportId,
            ("actor" to 2) to attackId,
            ("actor" to 3) to "BASIC_ATTACK",
            ("actor" to 4) to attackId,
            ("target" to 1) to "BASIC_ATTACK",
            ("target" to 2) to "BASIC_ATTACK",
            ("target" to 3) to "BASIC_ATTACK",
            ("target" to 4) to "BASIC_ATTACK",
        )

        fun run(rank: Int) = ArenaSupportTurnEngine.simulateScripted(
            rankedSupportFighter(HeroClass.MAGE, "actor", supportId, rank),
            rankedAttackFighter(HeroClass.WARRIOR, "target"),
            seed = 71L,
            schedule = schedule,
            rules = rules(safetyTurnLimit = 4, attackBase = 10.0),
        )

        val rankNine = run(9)
        val rankTen = run(10)
        fun triggerTurns(result: ArenaSupportResult) = result.events.filter {
            it.type == ArenaSupportEventType.SUPPORT_TRIGGERED &&
                it.actorId == "actor" && it.actionId == supportId
        }.map { it.turn }
        fun consumedTurn(result: ArenaSupportResult) = result.events.single {
            it.type == ArenaSupportEventType.EFFECT_EXPIRED &&
                it.actorId == "actor" && it.actionId == supportId && it.reason == "consumed"
        }.turn

        assertEquals(listOf(2), triggerTurns(rankNine))
        assertEquals(2, consumedTurn(rankNine))
        assertEquals(listOf(2, 4), triggerTurns(rankTen))
        assertEquals(4, consumedTurn(rankTen))
        assertEquals(rankTen, run(10))
    }

    @Test
    fun `rank ten grants both once per duel heals a second cast`() {
        val cases = listOf(
            HeroClass.WARRIOR to "ARENA_SUP_FIGHTER_08",
            HeroClass.PALADIN to "ARENA_SUP_PALADIN_08",
        )

        for ((heroClass, supportId) in cases) {
            val rankNine = runLowHealthHeal(heroClass, supportId, 9)
            val rankTen = runLowHealthHeal(heroClass, supportId, 10)
            fun starts(result: ArenaSupportResult) = result.events.filter {
                it.type == ArenaSupportEventType.CAST_START &&
                    it.actorId == "actor" && it.actionId == supportId
            }.map { it.turn }

            assertEquals("$supportId rank 9", 1, starts(rankNine).size)
            assertEquals("$supportId rank 10", 2, starts(rankTen).size)
            assertEquals("$supportId deterministic", rankTen,
                runLowHealthHeal(heroClass, supportId, 10))
        }
    }

    private fun runLowHealthHeal(
        heroClass: HeroClass,
        supportId: String,
        rank: Int,
    ): ArenaSupportResult {
        val target = rankedAttackFighter(HeroClass.MAGE, "target")
        val targetAttack = attackId(HeroClass.MAGE)
        val schedule = buildMap {
            for (turn in 1..45) {
                put("actor" to turn, supportId)
                put("target" to turn, targetAttack)
            }
        }
        val actor = rankedSupportFighter(heroClass, "actor", supportId, rank).let { input ->
            // This case isolates the second heal charge. Ranked attacks would otherwise spend the
            // same MP pool while the scripted heal is waiting for its low-HP condition.
            input.copy(fighter = input.fighter.copy(attacks = emptyList()))
        }
        return ArenaSupportTurnEngine.simulateScripted(
            actor,
            target,
            seed = 83L,
            schedule = schedule,
            rules = rules(safetyTurnLimit = 45, attackBase = 40.0),
        )
    }

    private fun rankedSupportFighter(
        heroClass: HeroClass,
        id: String,
        supportId: String,
        supportRank: Int,
    ): ArenaSupportInput = rankedAttackFighter(heroClass, id).copy(
        supportIds = setOf(supportId),
        supportRanks = mapOf(supportId to supportRank),
    )

    private fun rankedAttackFighter(heroClass: HeroClass, id: String): ArenaSupportInput {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, id)
        val source = fixture.fighter.attacks.first { it.id == attackId(heroClass) }
        val resolved = requireNotNull(ArenaTurnInputAdapter.resolveAttack(source, heroClass, 1).arena)
        return ArenaSupportInput(
            fighter = fixture.fighter.copy(attacks = listOf(source.copy(
                masteryBonusPercent = 0,
                sourceDamagePercentMin = 0,
                sourceDamagePercentMax = 0,
                arena = resolved,
            ))),
            arenaLevel = 100,
        )
    }

    private fun attackId(heroClass: HeroClass): String =
        SkillCatalog.forClass(heroClass).first().catalogId

    private fun rules(safetyTurnLimit: Int, attackBase: Double) = ArenaTurnRules(
        safetyTurnLimit = safetyTurnLimit,
        hitChance = 1.0,
        damageVariance = 0.0,
        tierScaling = false,
        masteryScaling = false,
        formula = ArenaStatFormula(
            healthBase = 1_000.0,
            healthScale = 0.0,
            attackBase = attackBase,
            attackScale = 0.0,
        ),
    )
}
