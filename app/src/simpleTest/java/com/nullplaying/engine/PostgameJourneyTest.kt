package com.nullplaying.engine

import com.nullplaying.model.CompletedTaleRecord
import com.nullplaying.model.HeroClass
import com.nullplaying.model.SIMPLE_GAME_SCHEMA_VERSION
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.TaleKind
import com.nullplaying.remote.maximumAcceptedRankingCombatPower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostgameJourneyTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `level twenty guidance unlock stays inside the intended early progression window`() {
        val expectedUnlockMinutes = mapOf(
            HeroClass.WARRIOR to 10_052L,
            HeroClass.ROGUE to 10_269L,
            HeroClass.RANGER to 10_475L,
            HeroClass.MAGE to 10_060L,
            HeroClass.CLERIC to 10_298L,
            HeroClass.PALADIN to 10_061L,
        )
        HeroClass.entries.forEach { heroClass ->
            val game = newGame(heroClass)
            var resolvedActions = 0

            while (game.hero.level < 20L && resolvedActions < 2_000_000) {
                engine.settleOffline(game, game.actionEndsAt)
                resolvedActions += 1
            }

            assertEquals("$heroClass unlock level", 20L, game.hero.level)
            assertTrue("$heroClass resolvedActions=$resolvedActions", resolvedActions < 2_000_000)
            val unlockMinutes = game.lastSettledAt / 60_000L
            assertEquals("$heroClass unlockMinutes", expectedUnlockMinutes[heroClass], unlockMinutes)
            println(
                "guidanceUnlock class=$heroClass minutes=$unlockMinutes " +
                    "hours=${game.lastSettledAt / 3_600_000.0} " +
                    "kills=${game.totalKills} tales=${game.totalTales}",
            )
        }
    }

    @Test
    fun `stat bonus classes preserve authored milestones and reach level one hundred around depth eighty seven`() {
        // Fixed-seed production replay, with the approved skill/search/sale bonuses enabled.
        val labyrinthQuarters = mapOf(HeroClass.WARRIOR to 216, HeroClass.ROGUE to 214,
            HeroClass.RANGER to 215, HeroClass.MAGE to 212, HeroClass.CLERIC to 215, HeroClass.PALADIN to 215)
        val level100Quarters = mapOf(HeroClass.WARRIOR to 644, HeroClass.ROGUE to 626,
            HeroClass.RANGER to 620, HeroClass.MAGE to 620, HeroClass.CLERIC to 634, HeroClass.PALADIN to 643)
        HeroClass.entries.forEach { heroClass ->
            val game = newGame(heroClass)
            var firstLabyrinthQuarter: Int? = null
            var levelOneHundredQuarter: Int? = null
            var depthAtLevelOneHundred: Long? = null

            for (quarter in 190..240) {
                engine.settleOffline(game, quarter * QUARTER_DAY_MILLIS)
                if (firstLabyrinthQuarter == null && game.adventureTale.kind == TaleKind.LABYRINTH) {
                    firstLabyrinthQuarter = quarter
                }
            }
            for (quarter in 620..656) {
                engine.settleOffline(game, quarter * QUARTER_DAY_MILLIS)
                if (levelOneHundredQuarter == null && game.hero.level >= 100L) {
                    levelOneHundredQuarter = quarter
                    depthAtLevelOneHundred = game.labyrinthDepthCompleted
                }
            }

            assertEquals("$heroClass first labyrinth", labyrinthQuarters[heroClass], firstLabyrinthQuarter)
            assertEquals("$heroClass level 100", level100Quarters[heroClass], levelOneHundredQuarter)
            assertEquals(18, game.completedTaleHistory.count { it.kind == TaleKind.EPILOGUE })
            assertTrue(
                "$heroClass depth at level 100=$depthAtLevelOneHundred",
                depthAtLevelOneHundred in 84L..90L,
            )
            val displayedPower = engine.displayCombatPower(game)
            assertTrue(
                "$heroClass level=${game.hero.level} power=$displayedPower",
                displayedPower <= maximumAcceptedRankingCombatPower(game.hero.level),
            )
        }
    }

    @Test
    fun `reference warrior reaches the labyrinth near day fifty four and level one hundred near day one hundred sixty one`() {
        val game = newGame()

        engine.settleOffline(game, 52L * DAY_MILLIS)
        assertEquals(TaleKind.EPILOGUE, game.adventureTale.kind)

        engine.settleOffline(game, 54L * DAY_MILLIS)
        assertEquals(56L, game.hero.level)
        assertEquals(TaleKind.LABYRINTH, game.adventureTale.kind)
        assertEquals(1L, game.adventureTale.labyrinthDepth)
        assertEquals(
            game.labyrinthDepthCompleted + 1L,
            game.adventureTale.labyrinthDepth,
        )

        engine.settleOffline(game, 160L * DAY_MILLIS)
        assertTrue(game.hero.level < 100L)

        engine.settleOffline(game, 161L * DAY_MILLIS)

        assertEquals(100L, game.hero.level)
        assertTrue(game.labyrinthDepthCompleted in 85L..87L)
        assertEquals(
            game.labyrinthDepthCompleted + 1L,
            game.adventureTale.labyrinthDepth,
        )

        val epilogueHistory = game.completedTaleHistory.filter { it.kind == TaleKind.EPILOGUE }
        assertEquals(18, epilogueHistory.size)
        assertEquals(
            AdventureTaleCatalog.epilogues.map { it.id },
            epilogueHistory.map { it.taleId },
        )

        val labyrinthHistory = game.completedTaleHistory.filter { it.kind == TaleKind.LABYRINTH }
        val gateHistory = labyrinthHistory.filter {
            LabyrinthProgression.isGateDepth(it.labyrinthDepth)
        }
        val recentHistory = labyrinthHistory.filterNot {
            LabyrinthProgression.isGateDepth(it.labyrinthDepth)
        }
        assertEquals(
            (1L..game.labyrinthDepthCompleted)
                .filter(LabyrinthProgression::isGateDepth)
                .takeLast(SimpleGameEngine.MAX_LABYRINTH_GATE_HISTORY),
            gateHistory.map { it.labyrinthDepth },
        )
        assertEquals(
            (1L..game.labyrinthDepthCompleted)
                .filterNot(LabyrinthProgression::isGateDepth)
                .takeLast(SimpleGameEngine.MAX_LABYRINTH_HISTORY),
            recentHistory.map { it.labyrinthDepth },
        )
    }

    @Test
    fun `schema thirty two repeated epilogues collapse to authored history and resume at the seventh tale`() {
        val game = newGame()
        val firstSix = AdventureTaleCatalog.epilogues.take(6)
        game.schemaVersion = 32
        game.totalTales = 37L
        game.completedTaleHistory = (
            firstSix.mapIndexed { index, definition ->
                completedRecord(definition, sequence = 25L + index)
            } +
                firstSix.mapIndexed { index, definition ->
                    completedRecord(definition, sequence = 31L + index)
                }
            ).toMutableList()
        game.adventureTale = AdventureTaleCatalog.instantiate(
            definition = firstSix.first(),
            sequence = 37L,
            heroName = game.hero.name,
            heroLevel = 53L,
            variant = AdventureTaleCatalog.variantAt(0),
        ).also { tale ->
            tale.currentActIndex = 2
            tale.acts.take(2).forEach { act ->
                act.progress = act.target
                act.completed = true
            }
            tale.acts[2].progress = 437L
        }

        engine.settleOfflineWithOfflineAdventure(game, now = game.lastSettledAt)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(AdventureTaleCatalog.epilogues[6].id, game.adventureTale.definitionId)
        assertEquals(TaleKind.EPILOGUE, game.adventureTale.kind)
        assertEquals(37L, game.adventureTale.sequence)
        assertEquals(2, game.adventureTale.currentActIndex)
        assertTrue(game.adventureTale.acts.take(2).all { it.completed && it.progress == it.target })
        assertEquals(437L, game.adventureTale.activeAct().progress)
        assertEquals(
            firstSix.map { it.id },
            game.completedTaleHistory.filter { it.kind == TaleKind.EPILOGUE }.map { it.taleId },
        )
        assertEquals(0L, game.labyrinthDepthCompleted)
    }

    private fun newGame(): SimpleGameState = newGame(HeroClass.WARRIOR)

    private fun newGame(heroClass: HeroClass): SimpleGameState = engine.newGame(
        name = "기준 전사",
        heroClass = heroClass,
        rolledStats = engine.rollStats(77L).stats,
        seed = 88L,
        now = 0L,
    )

    private fun completedRecord(
        definition: AdventureTaleDefinition,
        sequence: Long,
    ): CompletedTaleRecord {
        val tale = AdventureTaleCatalog.instantiate(
            definition = definition,
            sequence = sequence,
            heroName = "기준 전사",
            heroLevel = 53L,
            variant = AdventureTaleCatalog.variantAt(sequence.toInt()),
        )
        return CompletedTaleRecord(
            taleSequence = tale.sequence,
            taleId = tale.definitionId,
            kind = tale.kind,
            volumeNumber = tale.volumeNumber,
            chapterNumber = tale.chapterNumber,
            title = tale.title,
            summary = tale.ending,
            nextHook = tale.nextHook,
            actMemories = tale.acts.map { it.completionBody },
            completedAtLevel = 53L,
            labyrinthDepth = tale.labyrinthDepth,
        )
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        const val QUARTER_DAY_MILLIS = DAY_MILLIS / 4L
    }
}
