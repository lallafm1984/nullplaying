package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Test

/** Targeted automatic-choice states, not fixtures for natural character growth or class balance. */
class ArenaTurnDecisionTest {
    @Test
    fun sameTurnLethalCompletionKeepsTheTwoTurnWinningChance() {
        for (seed in listOf(0L, 1L, 42L, 998L)) {
            val result = ArenaTurnEngine.simulate(
                challenger(),
                opponent(attack("mage_t03_c03", "삼중 낙뢰", tier = 3)),
                seed,
                rules(ownMaxHp = 70.0),
            )

            assertOpening(result, ownMaxHp = 70.0, opponentCastTurns = 3)
            // At turn 2 the opponent's lethal spell has exactly two turns left. Our
            // 52.52-damage skill can defeat its remaining 51 HP in those same two turns.
            // Penalizing equality wrongly chooses a 20-damage basic that cannot win.
            val secondStart = start(result, CHALLENGER, turn = 2)
            assertEquals("seed=$seed", TWO_TURN_ATTACK, secondStart.attackId)
            assertEquals(2, secondStart.castTurns)
            assertEquals(ArenaRunStatus.COMPLETED, result.status)
            assertEquals(3, result.turns)
            assertEquals(
                setOf(CHALLENGER, OPPONENT),
                result.events.filter {
                    it.type == ArenaEventType.CAST_PROGRESS && it.turn == 3 &&
                        it.remainingTurns == 0
                }.map { it.actorId }.toSet(),
            )
            // Initiative determines the winner; the chooser must not inspect that future roll.
        }
    }

    @Test
    fun strictlyEarlierLethalCompletionStillFavorsAnImmediateAttack() {
        for (seed in listOf(0L, 1L, 42L, 998L)) {
            val result = ArenaTurnEngine.simulate(
                challenger(),
                opponent(attack("mage_t02_c02", "얼음 창", tier = 2)),
                seed,
                rules(ownMaxHp = 50.0),
            )

            assertOpening(result, ownMaxHp = 50.0, opponentCastTurns = 2)
            // The incoming 52.52 damage now lands after only one remaining turn.
            // Our two-turn skill cannot finish first, so its existing danger penalty stays.
            val secondStart = start(result, CHALLENGER, turn = 2)
            assertEquals("seed=$seed", "BASIC_ATTACK", secondStart.attackId)
            assertEquals(1, secondStart.castTurns)
            assertEquals(ArenaRunStatus.COMPLETED, result.status)
            assertEquals(2, result.turns)
            assertEquals(OPPONENT, result.winnerId)
        }
    }

    private fun assertOpening(result: ArenaTurnResult, ownMaxHp: Double, opponentCastTurns: Int) {
        assertEquals(ownMaxHp, result.fighters.getValue(CHALLENGER).maxHp, EPSILON)
        assertEquals(78.5, result.fighters.getValue(OPPONENT).maxHp, EPSILON)
        assertEquals(ONE_TURN_ATTACK, start(result, CHALLENGER, turn = 1).attackId)
        assertEquals(opponentCastTurns, start(result, OPPONENT, turn = 1).castTurns)
        val openingHit = result.events.single {
            it.type == ArenaEventType.ATTACK_HIT && it.actorId == CHALLENGER && it.turn == 1
        }
        assertEquals(27.5, openingHit.amount, EPSILON)
        assertEquals(51.0, checkNotNull(openingHit.hpAfter), EPSILON)
    }

    private fun start(result: ArenaTurnResult, actorId: String, turn: Int) = result.events.single {
        it.type == ArenaEventType.CAST_START && it.actorId == actorId && it.turn == turn
    }

    private fun challenger() = ArenaFighterInput(
        id = CHALLENGER,
        heroClass = HeroClass.WARRIOR,
        level = 5L,
        stats = stats(survival = 0.0),
        attacks = listOf(
            attack(ONE_TURN_ATTACK, "칼날 베기", tier = 1, mastery = 50),
            attack(TWO_TURN_ATTACK, "강철 베기", tier = 2),
        ),
    )

    private fun opponent(attack: ArenaAttackInput) = ArenaFighterInput(
        id = OPPONENT,
        heroClass = HeroClass.MAGE,
        level = 10L,
        stats = stats(survival = 1.0),
        attacks = listOf(attack),
    )

    private fun stats(survival: Double) = ArenaCoreStats(
        strength = 0.0,
        constitution = survival,
        dexterity = 0.0,
        intelligence = 0.0,
        wisdom = survival,
        charisma = survival,
        rawMaxHealth = 0.0,
        rawMaxMana = 100.0,
    )

    private fun attack(id: String, name: String, tier: Int, mastery: Int = 0) = ArenaAttackInput(
        id = id,
        name = name,
        tier = tier,
        masteryBonusPercent = mastery,
        sourceDamagePercentMin = 90 + (tier - 1) * 20,
        sourceDamagePercentMax = 100 + (tier - 1) * 20,
    )

    private fun rules(ownMaxHp: Double) = ArenaTurnRules(
        budget = ArenaAttackBudget.C1,
        mpMode = ArenaMpMode.FIXED_100,
        tierScaling = true,
        masteryScaling = true,
        formula = ArenaStatFormula(
            healthBase = ownMaxHp,
            healthScale = 78.5 - ownMaxHp,
            healthSurvivalShare = 1.0,
            attackBase = 20.0,
            attackScale = 0.0,
            attackOffenseShare = 0.5,
            defenseScale = 0.0,
        ),
        hitChance = 1.0,
        damageVariance = 0.0,
        tierGain = 0.01,
        masteryGain = 0.20,
        cooldownTurns = 2,
        initiativeSensitivity = 0.0,
        initiativeMaxEdge = 0.0,
    )

    private companion object {
        const val CHALLENGER = "challenger"
        const val OPPONENT = "opponent"
        const val ONE_TURN_ATTACK = "warrior_t01_c01"
        const val TWO_TURN_ATTACK = "warrior_t02_c03"
        const val EPSILON = 1e-9
    }
}
