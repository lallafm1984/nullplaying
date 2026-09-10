package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused execution proofs for the rank-6/7 discrete support milestone. */
class ArenaSupportCountRuntimeTest {
    @Test
    fun `cleanse rank seven removes two harmful kinds while poison remains one batch`() {
        val rankSix = runCleanse(6)
        val rankSeven = runCleanse(7)

        assertEquals(setOf("accuracy"), cleanseReasons(rankSix))
        assertEquals(setOf("accuracy", "poison"), cleanseReasons(rankSeven))
        assertEquals(rankSeven, runCleanse(7))
    }

    @Test
    fun `accuracy cleanse count planner removes one at rank six and two at rank seven`() {
        val candidates = listOf(
            ArenaSupportTurnEngine.CleanseRemovalCandidate(1, "accuracy", 30.0),
            ArenaSupportTurnEngine.CleanseRemovalCandidate(2, "accuracy", 20.0),
            ArenaSupportTurnEngine.CleanseRemovalCandidate(3, "burn", 100.0),
        )

        assertEquals(
            listOf(listOf(1)),
            ArenaSupportTurnEngine.cleanseRemovalPlan(candidates, accuracyOnly = true, maximum = countAt(6)),
        )
        assertEquals(
            listOf(listOf(1), listOf(2)),
            ArenaSupportTurnEngine.cleanseRemovalPlan(candidates, accuracyOnly = true, maximum = countAt(7)),
        )

        val rankSeven = runAccuracyCleanse(7)
        assertEquals(listOf("accuracy"), rankSeven.events.filter {
            it.type == ArenaSupportEventType.STATUS_REMOVED &&
                it.actorId == "actor" && it.actionId == CLEANSE_ACCURACY
        }.mapNotNull { it.reason })
        assertEquals(rankSeven, runAccuracyCleanse(7))
    }

    @Test
    fun `reveal rank seven removes two illusion charges`() {
        val rankSix = runReveal(6)
        val rankSeven = runReveal(7)

        assertEquals(1, removalTriggers(rankSix, REVEAL).size)
        assertEquals(2, removalTriggers(rankSeven, REVEAL).size)
        assertEquals(1, illusionChargeRemovals(rankSix).size)
        assertEquals(2, illusionChargeRemovals(rankSeven).size)
        assertEquals(rankSeven, runReveal(7))
    }

    @Test
    fun `dispel rank seven removes two eligible buffs`() {
        val rankSix = runImmediateDispel(6)
        val rankSeven = runImmediateDispel(7)

        assertEquals(1, removalTriggers(rankSix, DISPEL).size)
        assertEquals(2, removalTriggers(rankSeven, DISPEL).size)
        assertEquals(rankSeven, runImmediateDispel(7))
    }

    @Test
    fun `sanctuary rank seven shortens a new harmful status by two turns`() {
        val rankSix = runSanctuary(6)
        val rankSeven = runSanctuary(7)
        fun poisonExpiry(result: ArenaSupportResult) = result.events.single {
            it.type == ArenaSupportEventType.STATUS_APPLIED &&
                it.actorId == "target" && it.targetId == "actor" && it.reason == "poison" && it.turn == 3
        }.effectExpiresAtTurn
        fun shortenedValue(result: ArenaSupportResult) = result.events.single {
            it.type == ArenaSupportEventType.SUPPORT_TRIGGERED &&
                it.actorId == "actor" && it.actionId == SANCTUARY &&
                it.reason == "harmful_duration_shortened"
        }.traitValue

        assertEquals(6, poisonExpiry(rankSix))
        assertEquals(5, poisonExpiry(rankSeven))
        assertEquals(1.0, shortenedValue(rankSix)!!, 0.0)
        assertEquals(2.0, shortenedValue(rankSeven)!!, 0.0)
        assertEquals(rankSeven, runSanctuary(7))
    }

    @Test
    fun `status guard rank seven blocks two new harmful statuses`() {
        val rankSix = runStatusGuard(6)
        val rankSeven = runStatusGuard(7)
        fun blocked(result: ArenaSupportResult) = result.events.filter {
            it.type == ArenaSupportEventType.STATUS_BLOCKED &&
                it.actorId == "actor" && it.actionId == STATUS_GUARD
        }
        fun poisonApplied(result: ArenaSupportResult) = result.events.filter {
            it.type == ArenaSupportEventType.STATUS_APPLIED &&
                it.targetId == "actor" && it.reason == "poison" && it.turn >= 3
        }

        assertEquals(1, blocked(rankSix).size)
        assertEquals(1, poisonApplied(rankSix).size)
        assertEquals(2, blocked(rankSeven).size)
        assertTrue(poisonApplied(rankSeven).isEmpty())
        assertEquals(rankSeven, runStatusGuard(7))
    }

    @Test
    fun `judgment and seal remove their full count on one hit then expire`() {
        val cases = listOf(
            HeroClass.PALADIN to JUDGMENT,
            HeroClass.CLERIC to SEAL,
        )
        for ((heroClass, supportId) in cases) {
            val rankSix = runTriggeredDispel(heroClass, supportId, 6)
            val rankSeven = runTriggeredDispel(heroClass, supportId, 7)

            assertEquals("$supportId rank 6", 1, removalTriggers(rankSix, supportId).size)
            assertEquals("$supportId rank 7", 2, removalTriggers(rankSeven, supportId).size)
            assertEquals("$supportId one hit", setOf(5), removalTriggers(rankSeven, supportId).map { it.turn }.toSet())
            assertEquals("$supportId consumed", listOf(5), rankSeven.events.filter {
                it.type == ArenaSupportEventType.EFFECT_EXPIRED &&
                    it.actorId == "actor" && it.actionId == supportId && it.reason == "consumed"
            }.map { it.turn })
            assertEquals("$supportId deterministic", rankSeven, runTriggeredDispel(heroClass, supportId, 7))
        }
    }

    @Test
    fun `truth spends one charge per stealth attack and rank ten keeps the third charge`() {
        val rankNine = runTruthAgainstStealth(9)
        val rankTen = runTruthAgainstStealth(10)
        fun triggers(result: ArenaSupportResult) = result.events.filter {
            it.type == ArenaSupportEventType.SUPPORT_TRIGGERED &&
                it.actorId == "actor" && it.actionId == TRUTH &&
                it.reason == "stealth_bonus_weakened"
        }
        fun consumed(result: ArenaSupportResult) = result.events.filter {
            it.type == ArenaSupportEventType.EFFECT_EXPIRED &&
                it.actorId == "actor" && it.actionId == TRUTH && it.reason == "consumed"
        }

        assertEquals(listOf(3, 4), triggers(rankNine).map { it.turn })
        assertEquals(listOf(3, 4), triggers(rankTen).map { it.turn })
        assertEquals(listOf(4), consumed(rankNine).map { it.turn })
        assertTrue(consumed(rankTen).isEmpty())
        assertEquals(rankTen, runTruthAgainstStealth(10))
    }

    private fun runCleanse(rank: Int): ArenaSupportResult {
        val actor = fighter(HeroClass.CLERIC, "actor", mapOf(CLEANSE to rank))
        val target = fighter(HeroClass.ROGUE, "target", mapOf(POISON_COAT to 1, SMOKE to 1))
        val schedule = mapOf(
            ("actor" to 1) to BASIC,
            ("actor" to 2) to BASIC,
            ("actor" to 3) to BASIC,
            ("actor" to 4) to CLEANSE,
            ("target" to 1) to POISON_COAT,
            ("target" to 2) to BASIC,
            ("target" to 3) to SMOKE,
            ("target" to 4) to BASIC,
        )
        return simulate(actor, target, schedule, 4)
    }

    private fun runAccuracyCleanse(rank: Int): ArenaSupportResult {
        val actor = fighter(HeroClass.WARRIOR, "actor", mapOf(CLEANSE_ACCURACY to rank))
        val target = fighter(HeroClass.ROGUE, "target", mapOf(SMOKE to 1))
        val schedule = mapOf(
            ("actor" to 1) to BASIC,
            ("actor" to 2) to CLEANSE_ACCURACY,
            ("target" to 1) to SMOKE,
            ("target" to 2) to BASIC,
        )
        return simulate(actor, target, schedule, 2)
    }

    private fun runReveal(rank: Int): ArenaSupportResult {
        val actor = fighter(HeroClass.RANGER, "actor", mapOf(REVEAL to rank))
        val target = fighter(HeroClass.MAGE, "target", mapOf(MIRROR to 10))
        val schedule = mapOf(
            ("actor" to 1) to BASIC,
            ("actor" to 2) to BASIC,
            ("actor" to 3) to REVEAL,
            ("target" to 1) to MIRROR,
        )
        return simulate(actor, target, schedule, 3)
    }

    private fun runImmediateDispel(rank: Int): ArenaSupportResult {
        val actor = fighter(HeroClass.MAGE, "actor", mapOf(DISPEL to rank))
        val target = fighter(HeroClass.WARRIOR, "target", mapOf(IRON to 1, SHOUT to 1))
        val schedule = buffSetupSchedule(actorAction = DISPEL, actorActionTurn = 4)
        return simulate(actor, target, schedule, 4)
    }

    private fun runSanctuary(rank: Int): ArenaSupportResult {
        val actor = fighter(HeroClass.CLERIC, "actor", mapOf(SANCTUARY to rank))
        val target = fighter(HeroClass.ROGUE, "target", mapOf(POISON_COAT to 1))
        val schedule = mapOf(
            ("actor" to 1) to BASIC,
            ("actor" to 2) to SANCTUARY,
            ("actor" to 3) to BASIC,
            ("target" to 1) to POISON_COAT,
            ("target" to 3) to BASIC,
        )
        return simulate(actor, target, schedule, 3)
    }

    private fun runStatusGuard(rank: Int): ArenaSupportResult {
        val actor = fighter(HeroClass.MAGE, "actor", mapOf(STATUS_GUARD to rank))
        val target = fighter(HeroClass.ROGUE, "target", mapOf(POISON_COAT to 10))
        val schedule = mapOf(
            ("actor" to 1) to BASIC,
            ("actor" to 2) to STATUS_GUARD,
            ("actor" to 3) to BASIC,
            ("actor" to 4) to BASIC,
            ("target" to 1) to POISON_COAT,
            ("target" to 3) to BASIC,
            ("target" to 4) to BASIC,
        )
        return simulate(actor, target, schedule, 4)
    }

    private fun runTriggeredDispel(heroClass: HeroClass, supportId: String, rank: Int): ArenaSupportResult {
        val actor = fighter(heroClass, "actor", mapOf(supportId to rank))
        val target = fighter(HeroClass.WARRIOR, "target", mapOf(IRON to 1, SHOUT to 1))
        val schedule = buffSetupSchedule(actorAction = supportId, actorActionTurn = 4) + mapOf(
            ("actor" to 5) to BASIC,
            ("target" to 5) to BASIC,
        )
        return simulate(actor, target, schedule, 5)
    }

    private fun runTruthAgainstStealth(rank: Int): ArenaSupportResult =
        (1L..256L).asSequence().map { replayTruthAgainstStealth(rank, it) }.first { result ->
            result.events.any {
                it.type == ArenaSupportEventType.ATTACK_MISS &&
                    it.actorId == "target" && it.actionId == BASIC && it.turn == 2
            } && result.events.filter {
                it.type == ArenaSupportEventType.ATTACK_HIT &&
                    it.actorId == "target" && it.actionId == BASIC && it.turn in 3..4
            }.map { it.turn } == listOf(3, 4)
        }

    private fun replayTruthAgainstStealth(rank: Int, seed: Long): ArenaSupportResult {
        val actor = fighter(HeroClass.CLERIC, "actor", mapOf(TRUTH to rank))
        val target = fighter(HeroClass.ROGUE, "target", mapOf(STEALTH to 10), withAttack = true)
        val schedule = mapOf(
            ("actor" to 1) to BASIC,
            ("actor" to 2) to TRUTH,
            ("actor" to 3) to BASIC,
            ("actor" to 4) to BASIC,
            ("target" to 1) to STEALTH,
            ("target" to 2) to BASIC,
            ("target" to 3) to BASIC,
            ("target" to 4) to BASIC,
        )
        return simulate(actor, target, schedule, 4, seed, hitChance = .5)
    }

    private fun buffSetupSchedule(actorAction: String, actorActionTurn: Int): Map<Pair<String, Int>, String> =
        buildMap {
            for (turn in 1..actorActionTurn) put("actor" to turn, if (turn == actorActionTurn) actorAction else BASIC)
            put("target" to 1, IRON)
            put("target" to 2, BASIC)
            put("target" to 3, SHOUT)
            put("target" to 4, BASIC)
        }

    private fun fighter(
        heroClass: HeroClass,
        id: String,
        supportRanks: Map<String, Int>,
        withAttack: Boolean = false,
    ): ArenaSupportInput {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, id)
        val attacks = if (!withAttack) emptyList() else {
            val attackId = SkillCatalog.forClass(heroClass).first().catalogId
            val source = fixture.fighter.attacks.first { it.id == attackId }
            val resolved = requireNotNull(ArenaTurnInputAdapter.resolveAttack(source, heroClass, 1).arena)
            listOf(source.copy(
                masteryBonusPercent = 0,
                sourceDamagePercentMin = 0,
                sourceDamagePercentMax = 0,
                arena = resolved,
            ))
        }
        return ArenaSupportInput(
            fighter = fixture.fighter.copy(attacks = attacks),
            supportIds = supportRanks.keys,
            supportRanks = supportRanks,
            arenaLevel = 100,
        )
    }

    private fun simulate(
        actor: ArenaSupportInput,
        target: ArenaSupportInput,
        schedule: Map<Pair<String, Int>, String>,
        turns: Int,
        seed: Long = 41L,
        hitChance: Double = 1.0,
    ): ArenaSupportResult = ArenaSupportTurnEngine.simulateScripted(
        actor,
        target,
        seed,
        schedule,
        ArenaTurnRules(
            safetyTurnLimit = turns,
            hitChance = hitChance,
            damageVariance = 0.0,
            tierScaling = false,
            masteryScaling = false,
            formula = ArenaStatFormula(
                healthBase = 1_000.0,
                healthScale = 0.0,
                attackBase = 5.0,
                attackScale = 0.0,
            ),
        ),
    )

    private fun cleanseReasons(result: ArenaSupportResult): Set<String> = result.events.filter {
        it.type == ArenaSupportEventType.STATUS_REMOVED &&
            it.actorId == "actor" && it.actionId == CLEANSE
    }.mapNotNullTo(linkedSetOf()) { it.reason }

    private fun removalTriggers(result: ArenaSupportResult, supportId: String) = result.events.filter {
        it.type == ArenaSupportEventType.SUPPORT_TRIGGERED &&
            it.actorId == "actor" && it.actionId == supportId &&
            it.reason == "beneficial_status_removed"
    }

    private fun illusionChargeRemovals(result: ArenaSupportResult) = result.events.filter {
        it.type == ArenaSupportEventType.STATUS_REMOVED &&
            it.actorId == "target" && it.actionId == MIRROR &&
            it.reason == "illusion_charge_removed"
    }

    private fun countAt(rank: Int): Int =
        ArenaSkillTreeCatalog.effectiveSupport(requireNotNull(ArenaSupportCatalog.find(CLEANSE_ACCURACY)), rank)
            .magnitude.toInt()

    private companion object {
        const val BASIC = "BASIC_ATTACK"
        const val IRON = "ARENA_SUP_FIGHTER_01"
        const val SHOUT = "ARENA_SUP_FIGHTER_02"
        const val CLEANSE_ACCURACY = "ARENA_SUP_FIGHTER_06"
        const val STEALTH = "ARENA_SUP_ROGUE_01"
        const val SMOKE = "ARENA_SUP_ROGUE_02"
        const val POISON_COAT = "ARENA_SUP_ROGUE_11"
        const val REVEAL = "ARENA_SUP_RANGER_02"
        const val MIRROR = "ARENA_SUP_MAGE_02"
        const val DISPEL = "ARENA_SUP_MAGE_05"
        const val STATUS_GUARD = "ARENA_SUP_MAGE_08"
        const val CLEANSE = "ARENA_SUP_CLERIC_03"
        const val TRUTH = "ARENA_SUP_CLERIC_07"
        const val SANCTUARY = "ARENA_SUP_CLERIC_08"
        const val SEAL = "ARENA_SUP_CLERIC_10"
        const val JUDGMENT = "ARENA_SUP_PALADIN_03"
    }
}
