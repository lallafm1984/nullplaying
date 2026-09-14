package com.nullplaying.engine

import com.nullplaying.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AdventureEventContextAndBattleTest {
    private val engine = SimpleGameEngine(enableAdventureEvents = true)

    @Test
    fun `catalog covers all six event contexts with the authored hundred event distribution`() {
        assertEquals(100, AdventureEventEngine.all.size)
        assertEquals(
            mapOf(
                AdventureEventContext.OUTBOUND_ROUTE to 18,
                AdventureEventContext.FIELD_EXPLORATION to 22,
                AdventureEventContext.PRE_COMBAT to 15,
                AdventureEventContext.POST_COMBAT to 15,
                AdventureEventContext.RETURN_ROUTE to 15,
                AdventureEventContext.TOWN_RETURN to 15,
            ),
            AdventureEventEngine.all.groupingBy { it.context }.eachCount(),
        )
        assertTrue(AdventureEventEngine.all.any { definition ->
            definition.battleRule?.let { rule ->
                listOf(rule.successGrade, rule.partialGrade, rule.failureGrade).contains(MonsterGrade.ELITE)
            } == true
        })
        assertTrue(AdventureEventEngine.all.any { definition ->
            definition.battleRule?.let { rule ->
                listOf(rule.successGrade, rule.partialGrade, rule.failureGrade).contains(MonsterGrade.BOSS)
            } == true
        })
    }

    @Test
    fun `each context interrupts and resumes its exact automatic adventure boundary`() {
        AdventureEventContext.entries.forEach { context ->
            val game = atDueBoundary(context)
            val progressBefore = game.adventureTale.activeAct().progress
            val returnsBefore = game.totalReturns

            engine.settleOffline(game, game.actionEndsAt)

            assertEquals(context.name, AdventurePhase.EVENT, game.adventurePhase)
            val started = requireNotNull(game.adventureJourney.pending)
            assertEquals(context, started.context)
            game.adventureJourney.pending = started.copy(
                battleGrade = null,
                battleRewardKind = AdventureEventBattleRewardKind.NONE,
            )

            engine.settleOffline(game, game.actionEndsAt)
            assertEquals(AdventurePhase.EVENT_RESULT, game.adventurePhase)
            val expectedProgress = if (context == AdventureEventContext.FIELD_EXPLORATION) 1L else 0L
            assertEquals(context.name, expectedProgress, game.adventureTale.activeAct().progress - progressBefore)

            engine.settleOffline(game, game.actionEndsAt)
            when (context) {
                AdventureEventContext.PRE_COMBAT,
                AdventureEventContext.OUTBOUND_ROUTE,
                AdventureEventContext.FIELD_EXPLORATION,
                AdventureEventContext.POST_COMBAT,
                -> assertEquals(context.name, AdventurePhase.COMBAT, game.adventurePhase)
                AdventureEventContext.RETURN_ROUTE -> {
                    assertEquals(AdventurePhase.RETURNING, game.adventurePhase)
                    assertEquals(returnsBefore + 1L, game.totalReturns)
                }
                AdventureEventContext.TOWN_RETURN -> assertTrue(
                    context.name,
                    game.adventurePhase in setOf(
                        AdventurePhase.SHOPPING,
                        AdventurePhase.SHOPPING_EMPTY,
                        AdventurePhase.DEPARTING,
                    ),
                )
            }
            assertNull(game.adventureJourney.eventBattle)
        }
    }

    @Test
    fun `elite and boss event victories grant exactly equipment or gold without normal combat settlement`() {
        listOf(MonsterGrade.ELITE, MonsterGrade.BOSS).forEach { grade ->
            listOf(AdventureEventBattleRewardKind.GOLD, AdventureEventBattleRewardKind.EQUIPMENT).forEach { reward ->
                val game = eventBattleReady(grade, reward)
                val experienceBefore = game.hero.experience
                val progressBefore = game.adventureTale.activeAct().progress
                val actsBefore = game.totalActs
                val talesBefore = game.totalTales
                val killsBefore = game.totalKills
                val goldBefore = game.hero.gold
                val itemsBefore = game.totalItemsFound

                engine.settleOffline(game, game.actionEndsAt)
                assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
                assertEquals(grade, game.monster.grade)
                assertFalse(game.monster.isFinalBoss)
                assertFalse(game.monster.isLabyrinthGateBoss)

                game.monster.currentEnergy = 0L
                game.combatPhase = CombatPhase.VICTORY
                engine.settleOffline(game, game.actionEndsAt)

                assertEquals(AdventurePhase.LOOTING, game.adventurePhase)
                assertEquals(experienceBefore, game.hero.experience)
                assertEquals(progressBefore, game.adventureTale.activeAct().progress)
                assertEquals(actsBefore, game.totalActs)
                assertEquals(talesBefore, game.totalTales)
                assertEquals(killsBefore + 1L, game.totalKills)
                val result = requireNotNull(game.adventureJourney.eventBattle?.result)
                when (reward) {
                    AdventureEventBattleRewardKind.GOLD -> {
                        assertTrue(game.hero.gold > goldBefore)
                        assertEquals(itemsBefore, game.totalItemsFound)
                        assertTrue(result.goldAwarded > 0L)
                        assertEquals(0, result.actualItemCount)
                    }
                    AdventureEventBattleRewardKind.EQUIPMENT -> {
                        assertEquals(goldBefore, game.hero.gold)
                        assertEquals(itemsBefore + 1L, game.totalItemsFound)
                        assertEquals(0L, result.goldAwarded)
                        assertEquals(1, result.actualItemCount)
                        assertEquals(AdventureEventItemReward.EQUIPMENT, result.run.itemReward)
                    }
                    AdventureEventBattleRewardKind.NONE -> fail("NONE is not a battle victory reward")
                }
            }
        }
    }

    @Test
    fun `event boss skips ordinary discovery and starts a sustained combat attack immediately`() {
        val game = eventBattleReady(MonsterGrade.BOSS, AdventureEventBattleRewardKind.GOLD)
        val resultEndsAt = game.actionEndsAt
        val sequenceBeforeBattle = game.actionSequence

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(CombatPhase.ATTACKING, game.combatPhase)
        assertEquals(resultEndsAt, game.actionStartedAt)
        assertEquals(
            SimpleGameEngine.ATTACK_PRESENTATION_MILLIS,
            game.actionEndsAt - game.actionStartedAt,
        )
        assertEquals(sequenceBeforeBattle + 1L, game.actionSequence)
        assertEquals(1, game.monster.attacksCompleted)
        assertEquals(game.monster.maxEnergy, game.lastMonsterEnergyBeforeAttack)
        assertTrue(game.lastDamage > 0L)
        assertTrue(game.monster.expectedAttacks > 1)
        assertTrue(game.monster.currentEnergy > 0L)
        assertNull(game.adventureJourney.eventBattle?.result)
    }

    @Test
    fun `event battle survives every restart boundary and settles one reward and one event log`() {
        var game = restore(eventBattleReady(MonsterGrade.BOSS, AdventureEventBattleRewardKind.GOLD))
        val completedEventsBeforeBattle = game.adventureJourney.completedEvents
        val recentSequence = requireNotNull(game.adventureJourney.lastResult).run.sequence

        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(CombatPhase.ATTACKING, game.combatPhase)
        assertEquals(1, game.monster.attacksCompleted)
        assertTrue(game.monster.currentEnergy > 0L)
        game = restore(game)

        game.monster.currentEnergy = 0L
        game.combatPhase = CombatPhase.VICTORY
        game = restore(game)
        val goldBefore = game.hero.gold
        val itemsBefore = game.totalItemsFound

        val victoryDelta = engine.settleOffline(game, game.actionEndsAt)

        assertEquals(AdventurePhase.LOOTING, game.adventurePhase)
        assertEquals(
            goldBefore + engine.eventBattleGoldPerLevel(AdventureEventOutcome.FAILURE) *
                game.hero.level.coerceAtLeast(1L),
            game.hero.gold,
        )
        assertEquals(itemsBefore, game.totalItemsFound)
        assertEquals(completedEventsBeforeBattle, game.adventureJourney.completedEvents)
        assertEquals(
            1,
            victoryDelta.recentEvents.count { it.type == RecentAdventureEventType.ADVENTURE_EVENT },
        )
        assertEquals(
            1,
            game.adventureJourney.recentResults.count { it.run.sequence == recentSequence },
        )
        val result = requireNotNull(game.adventureJourney.eventBattle?.result)
        assertTrue(result.battleResolved)
        assertEquals(AdventureEventRewardKind.GOLD, result.run.rewardKind)
        assertTrue(result.goldAwarded > 0L)
        assertEquals(0, result.actualItemCount)

        game = restore(game)
        val afterReward = Json.encodeToString(game)
        val repeatedDelta = engine.settleOffline(game, game.lastSettledAt)
        assertEquals(afterReward, Json.encodeToString(game))
        assertTrue(repeatedDelta.recentEvents.isEmpty())

        val resumeDelta = engine.settleOffline(game, game.actionEndsAt)
        assertNull(game.adventureJourney.eventBattle)
        assertEquals(
            0,
            resumeDelta.recentEvents.count { it.type == RecentAdventureEventType.ADVENTURE_EVENT },
        )
        assertEquals(
            1,
            game.adventureJourney.recentResults.count { it.run.sequence == recentSequence },
        )
    }

    @Test
    fun `event reward expectation remains success then partial then failure after combat time`() {
        val level = 20L
        val rows = AdventureEventEngine.all.map { definition ->
            val success = expectedRewardValue(definition, AdventureEventOutcome.SUCCESS, level)
            val partial = expectedRewardValue(definition, AdventureEventOutcome.PARTIAL, level)
            val failure = expectedRewardValue(definition, AdventureEventOutcome.FAILURE, level)
            assertTrue("${definition.id}: success=$success partial=$partial", success > partial)
            assertTrue("${definition.id}: partial=$partial failure=$failure", partial > failure)
            Triple(success, partial, failure)
        }
        val battleRows = AdventureEventEngine.all.zip(rows).filter { (definition, _) ->
            definition.battleRule != null
        }
        assertEquals(15, battleRows.size)
        assertTrue(battleRows.all { (_, values) -> values.first > values.second && values.second > values.third })
        println(
            "Event outcome value index at level $level: " +
                "success=${rows.map { it.first }.average()}, " +
                "partial=${rows.map { it.second }.average()}, " +
                "failure=${rows.map { it.third }.average()}, " +
                "battleMinGaps=" + battleRows.minOf { (_, values) ->
                    minOf(values.first - values.second, values.second - values.third)
                },
        )
    }

    private fun atDueBoundary(context: AdventureEventContext): SimpleGameState {
        val game = newGame()
        game.inventory.clear()
        if (context == AdventureEventContext.RETURN_ROUTE) {
            repeat(game.inventoryCapacity().toInt()) { index ->
                game.inventory += InventoryItem(index + 1L, "귀환 검증품 $index", "일반", "전리품", 1L)
            }
            game.totalItemsFound = game.inventory.size.toLong()
        }
        game.adventureJourney.initialized = true
        game.adventureJourney.nextEventAt = 1L
        game.adventureJourney.nextEventContext = context
        game.adventureJourney.pending = null
        game.adventureJourney.eventBattle = null
        game.actionStartedAt = 0L
        game.actionEndsAt = 1L
        game.lastSettledAt = 0L
        game.adventurePhase = when (context) {
            AdventureEventContext.OUTBOUND_ROUTE -> AdventurePhase.DEPARTING
            AdventureEventContext.TOWN_RETURN -> AdventurePhase.SELLING
            else -> AdventurePhase.LOOTING
        }
        return game
    }

    private fun eventBattleReady(
        grade: MonsterGrade,
        rewardKind: AdventureEventBattleRewardKind,
    ): SimpleGameState {
        val definition = AdventureEventEngine.all.first { candidate ->
            candidate.battleRule?.let { rule ->
                listOf(rule.successGrade, rule.partialGrade, rule.failureGrade).contains(grade)
            } == true
        }
        val outcome = AdventureEventOutcome.entries.first { definition.battleRule?.gradeFor(it) == grade }
        val game = newGame(seed = 77L + grade.ordinal)
        game.inventory.clear()
        val base = AdventureEventEngine.beginForQa(game, 0L, definition.id)
        val run = AdventureEventEngine.withOutcome(base, outcome, 91L).copy(
            startedAt = 0L,
            durationMillis = AdventureEventEngine.ACTION_MILLIS,
        )
        assertEquals(grade, run.battleGrade)
        game.adventureJourney.pending = run
        game.adventurePhase = AdventurePhase.EVENT
        game.actionStartedAt = 0L
        game.actionEndsAt = run.durationMillis
        game.lastSettledAt = 0L
        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.EVENT_RESULT, game.adventurePhase)
        game.adventureJourney.eventBattle = requireNotNull(game.adventureJourney.eventBattle).copy(
            rewardKind = rewardKind,
        )
        return game
    }

    private fun restore(game: SimpleGameState): SimpleGameState =
        Json.decodeFromString(Json.encodeToString(game))

    /**
     * QA-only comparison index using only existing economic quantities: one XP equals one gold,
     * items use their exact base sale value distribution, and one second costs level/10 gold.
     * The same 15-second event presentation is common to every outcome and cancels out.
     */
    private fun expectedRewardValue(
        definition: AdventureEventDefinition,
        outcome: AdventureEventOutcome,
        level: Long,
    ): Double {
        val grade = definition.battleRule?.gradeFor(outcome)
        val timeValuePerSecond = level.toDouble() / 10.0
        if (grade != null) {
            val equipmentChance = AdventureEventEngine.battleEquipmentRewardBasisPoints(outcome) / 10_000.0
            val maximumRarityRank = if (outcome == AdventureEventOutcome.SUCCESS) 5 else 4
            val equipmentValue = expectedEquipmentSaleValue(level, maximumRarityRank)
            val goldValue = level * engine.eventBattleGoldPerLevel(outcome).toDouble()
            val gross = equipmentChance * equipmentValue + (1.0 - equipmentChance) * goldValue
            val addedSeconds = (
                grade.minAttacks * SimpleGameEngine.ATTACK_PRESENTATION_MILLIS +
                    SimpleGameEngine.LOOT_RESULT_MILLIS
                ) / 1_000.0
            return gross - addedSeconds * timeValuePerSecond
        }
        if (outcome == AdventureEventOutcome.FAILURE) {
            return -2.0 * timeValuePerSecond
        }

        val success = outcome == AdventureEventOutcome.SUCCESS
        val equipmentChance = if (success) {
            AdventureEventEngine.EQUIPMENT_REWARD_ACCEPTED_ROLLS.toDouble() /
                AdventureEventEngine.EQUIPMENT_REWARD_ROLL_BOUND
        } else {
            0.0
        }
        val baseExperience = 16L + level * 4L
        val categoryValue = linkedMapOf(
            AdventureEventRewardKind.EXPERIENCE to if (success) baseExperience * 1.2 else baseExperience.toDouble(),
            AdventureEventRewardKind.GOLD to if (success) {
                level * definition.successGoldPerLevel.toDouble()
            } else {
                (level * definition.successGoldPerLevel / 4L).toDouble()
            },
            AdventureEventRewardKind.ITEM to expectedEventTrophySaleValue(level),
            AdventureEventRewardKind.ROUTE to (
                kotlin.math.abs(if (success) definition.successRouteDelayMillis else definition.successRouteDelayMillis / 2L) /
                    1_000.0 * EXPECTED_ROUTE_REWARD_USES * timeValuePerSecond
                ),
        )
        val weights = definition.rewardWeights
        val selectedValue = (
            categoryValue.getValue(AdventureEventRewardKind.EXPERIENCE) * weights.experience +
                categoryValue.getValue(AdventureEventRewardKind.GOLD) * weights.gold +
                categoryValue.getValue(AdventureEventRewardKind.ITEM) * weights.item +
                categoryValue.getValue(AdventureEventRewardKind.ROUTE) * weights.route
            ) / 100.0
        return equipmentChance * expectedEquipmentSaleValue(level, 5) +
            (1.0 - equipmentChance) * selectedValue
    }

    private fun expectedEquipmentSaleValue(level: Long, maximumRarityRank: Int): Double {
        val rarityCounts = listOf(
            500 to 5,
            1_000 to 4,
            50_000 to 3,
            140_000 to 2,
            300_000 to 1,
            508_500 to 0,
        )
        return rarityCounts.sumOf { (count, rank) ->
            count.toDouble() * (minOf(rank, maximumRarityRank) + 1) * 10.0 * level
        } / 1_000_000.0
    }

    private fun expectedEventTrophySaleValue(level: Long): Double {
        val rarityCounts = listOf(1 to 5, 9 to 4, 40 to 3, 200 to 2, 750 to 1)
        return rarityCounts.sumOf { (count, rank) ->
            count.toDouble() * (rank + 1) * 10.0 * level
        } / 1_000.0
    }

    private fun newGame(seed: Long = 88L): SimpleGameState = engine.newGame(
        name = "사건 문맥 검증",
        heroClass = HeroClass.WARRIOR,
        rolledStats = engine.rollStats(77L).stats,
        seed = seed,
        now = 0L,
    )

    companion object {
        private const val EXPECTED_ROUTE_REWARD_USES = 3.775
    }
}
