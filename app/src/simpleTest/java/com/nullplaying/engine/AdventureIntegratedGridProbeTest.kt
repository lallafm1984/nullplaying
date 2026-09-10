package com.nullplaying.engine

import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.CombatPhase
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.RecentAdventureEventType
import com.nullplaying.model.SimpleGameState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Broad final integration grid; separate artifacts preserve the stage-1 baseline measurements. */
class AdventureIntegratedGridProbeTest {
    private data class Scenario(val id: String, val level: Long, val depth: Long = 0L)

    @Test(timeout = 240_000L)
    fun `all stages and classes settle with natural traits and independent event reward randomness`() {
        val rows = mutableListOf<Map<String, Any>>()
        val qa = AdventureQaFixtures.stagingQaDirectory()
        try {
            SCENARIOS.forEach { scenario -> HeroClass.entries.forEach { heroClass -> SEEDS.forEach { seed ->
                listOf(false, true).forEach { enabled -> rows += measure(scenario, heroClass, seed, enabled) }
            } } }
            assertEquals(240, rows.size)
        } finally {
            qa.resolve("adventure-integrated-grid.csv").toFile().bufferedWriter().use { writer ->
                if (rows.isNotEmpty()) {
                    val columns = rows.first().keys.toList()
                    writer.appendLine(columns.joinToString(","))
                    rows.forEach { row -> writer.appendLine(columns.joinToString(",") { column ->
                        val value = row.getValue(column)
                        if (value is Double) String.format(Locale.ROOT, "%.8f", value) else value.toString()
                    }) }
                }
            }
            qa.resolve("adventure-integrated-grid-notes.txt").toFile().writeText(
                "Synthetic broad integration grid, not live server or calendar-time evidence.\n" +
                    "Six classes x four fresh fixed seeds x Lv1/20/50/heroLv60 labyrinth1/heroLv100 labyrinth100 x OFF/ON x 2 active hours.\n" +
                    "ON enables incidents, relationships and naturally formed traits together; no forced holdings.\n" +
                    "OFF is checked against the default engine bulk settlement, including combat RNG, hero, equipment, inventory and action clocks.\n" +
                    "Each incident/relationship reward boundary must leave the combat RNG unchanged. Later different actions can naturally diverge.\n" +
                    "Equipment counts use real receipts; gold earned excludes purchases and ending bag is a separate baseline CHA-adjusted estimate.\n" +
                    "Rows=${rows.size}/240; seeds=${SEEDS.joinToString(";")}.\n")
        }
    }

    @Test
    fun `simultaneously due incidents and relationships cannot replace final gate bosses`() {
        listOf(Scenario("main_gate", 50L), Scenario("labyrinth_gate", 100L, 100L)).forEach { scenario ->
            val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureRelationships = true, enableAdventureTraits = true)
            val game = AdventureQaFixtures.game(engine, HeroClass.WARRIOR, SEEDS.first(), scenario.level, scenario.depth)
            game.adventureRelationships.roster = AdventureQaFixtures.roster(game)
            AdventureEventEngine.initialize(game, AdventureQaFixtures.EPOCH)
            AdventureRelationshipEngine.initialize(game, AdventureQaFixtures.EPOCH)
            game.adventureJourney.nextEventAt = AdventureQaFixtures.EPOCH
            game.adventureRelationships.nextEncounterAt = AdventureQaFixtures.EPOCH
            game.adventureTale.currentActIndex = game.adventureTale.acts.lastIndex
            game.adventureTale.acts.dropLast(1).forEach { it.progress = it.target; it.completed = true }
            game.adventureTale.activeAct().progress = game.adventureTale.activeAct().target - 1L
            game.adventurePhase = AdventurePhase.LOOTING
            val progress = game.adventureTale.activeAct().progress
            engine.settleOffline(game, game.actionEndsAt)
            assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
            assertEquals(CombatPhase.REVEAL, game.combatPhase)
            assertEquals(MonsterGrade.BOSS, game.monster.grade)
            assertTrue(game.monster.isFinalBoss)
            if (scenario.depth > 0L) assertTrue(game.monster.isLabyrinthGateBoss)
            assertEquals(progress, game.adventureTale.activeAct().progress)
            assertEquals(0L, game.adventureJourney.completedEvents)
            assertEquals(0L, game.adventureRelationships.totalEncounters)
            assertEquals(0L, game.totalKills)
        }
    }

    private fun measure(scenario: Scenario, heroClass: HeroClass, seed: Long, enabled: Boolean): Map<String, Any> {
        val engine = SimpleGameEngine(enableAdventureEvents = enabled, enableAdventureRelationships = enabled, enableAdventureTraits = enabled)
        val game = AdventureQaFixtures.game(engine, heroClass, seed, scenario.level, scenario.depth)
        if (enabled) game.adventureRelationships.roster = AdventureQaFixtures.roster(game)
        val end = AdventureQaFixtures.EPOCH + 2L * AdventureQaFixtures.HOUR
        val startCp = engine.displayCombatPower(game)
        var xp = 0L
        var earned = 0L
        var spent = 0L
        var equipment = 0L
        var steps = 0L
        var sourceValidMillis = 0L
        var rewardBoundaries = 0L
        var lastTrace = 0L
        val recentUiEvents = java.util.ArrayDeque<RecentAdventureEvent>()
        while (game.actionEndsAt <= end) {
            val at = game.actionEndsAt
            sourceValidMillis += AdventureQaFixtures.sourceValidTimeThrough(game, at)
            val oldLevel = game.hero.level
            val oldXp = game.hero.experience
            val oldGold = game.hero.gold
            val oldItems = game.totalItemsFound
            val oldBuys = game.totalEquipmentPurchases
            val oldSeed = game.rngState
            val oldPhase = game.adventurePhase
            val oldKills = game.totalKills
            val settled = engine.settleOffline(game, at)
            settled.recentEvents.forEach { event ->
                recentUiEvents.addLast(event)
                if (recentUiEvents.size > 300) recentUiEvents.removeFirst()
            }
            assertTrue("Bounded advancing action clock", ++steps < 250_000L && game.actionEndsAt > at)
            assertEquals(at, game.lastSettledAt)
            assertTrue(game.hero.gold >= 0L)
            xp += game.hero.experience - oldXp
            for (level in oldLevel until game.hero.level) xp += engine.experienceRequired(level)
            val bought = game.totalEquipmentPurchases - oldBuys
            assertTrue(bought in 0L..1L)
            val paid = if (bought > 0L) requireNotNull(game.lastShopPurchase).price else 0L
            spent += paid
            earned += game.hero.gold - oldGold + paid
            val itemDelta = game.totalItemsFound - oldItems
            assertTrue(itemDelta in 0L..2L)
            if (enabled) {
                val receipts = game.adventureTraits.recentRewardTraces.filter { it.sequence > lastTrace }
                receipts.forEach { lastTrace = maxOf(lastTrace, it.sequence) }
                assertEquals(itemDelta, receipts.count { it.actualGranted }.toLong())
                equipment += receipts.count { it.actualGranted && it.slot != null }
                val held = game.adventureTraits.owned.map { it.traitId }.toSet()
                assertEquals(held.size, game.adventureTraits.owned.size)
                AdventureTraitCatalog.all.filter { it.id in held }.forEach { assertTrue(it.oppositeId !in held) }
            } else {
                assertTrue(itemDelta <= 1L)
                if (itemDelta > 0L && game.lastLootKind == "장비") equipment++
            }
            if (oldPhase == AdventurePhase.EVENT || oldPhase == AdventurePhase.RELATIONSHIP) {
                rewardBoundaries++
                assertEquals("Separate reward streams preserve the original combat RNG", oldSeed, game.rngState)
                assertEquals("Social/incident rewards are never monster kills", oldKills, game.totalKills)
                assertTrue("An ordinary reward cannot cross a boss gate", game.adventureTale.activeAct().progress < game.adventureTale.activeAct().target)
            }
        }
        sourceValidMillis += AdventureQaFixtures.sourceValidTimeThrough(game, end)
        engine.settleOffline(game, end)
        assertEquals(end, game.lastSettledAt)
        assertTrue(game.totalKills > 0L)
        assertEquals(earned - spent, game.hero.gold)
        assertEquals(6, game.equipment.size)
        assertEquals(EquipmentSlot.entries.toSet(), game.equipment.map { it.slot }.toSet())
        assertTrue(equipment <= game.totalItemsFound)
        if (!enabled) {
            assertEquals(0L, game.adventureJourney.completedEvents)
            assertEquals(0L, game.adventureRelationships.totalEncounters)
            assertTrue(game.adventureTraits.owned.isEmpty())
            assertTrue(game.adventureTraits.opportunityCounts.isEmpty())
            val baselineEngine = SimpleGameEngine()
            val baseline = AdventureQaFixtures.game(baselineEngine, heroClass, seed, scenario.level, scenario.depth)
            baselineEngine.settleOffline(baseline, end)
            assertOriginalStateEquals(baseline, game)
        }
        val bag = game.inventory.sumOf { AdventureQaFixtures.baselineSaleValue(engine, game, it) }
        return linkedMapOf("scenario" to scenario.id, "class" to heroClass.name, "seed" to seed,
            "mode" to if (enabled) "ON" else "OFF", "active_hours" to 2,
            "candidate_valid_hours" to sourceValidMillis.toDouble() / AdventureQaFixtures.HOUR,
            "start_level" to scenario.level, "end_level" to game.hero.level, "start_depth" to scenario.depth,
            "end_depth" to game.adventureTale.labyrinthDepth, "xp" to xp, "xp_per_hour" to xp / 2.0,
            "items" to game.totalItemsFound, "equipment" to equipment, "loot_upgrades" to game.totalLootEquipmentEquips,
            "purchases" to game.totalEquipmentPurchases, "returns" to game.totalReturns,
            "cash_earned" to earned, "purchase_gold" to spent, "ending_gold" to game.hero.gold,
            "ending_bag_base_value" to bag, "earned_plus_ending_bag_value" to earned + bag,
            "start_cp" to startCp, "end_cp" to engine.displayCombatPower(game), "kills" to game.totalKills,
            "acts" to game.totalActs, "events" to game.adventureJourney.completedEvents,
            "relationships" to game.adventureRelationships.totalEncounters,
            "owned_traits_at_end" to game.adventureTraits.owned.size,
            "trait_change_count" to game.adventureTraits.changeSequence,
            "trait_actual_effects" to game.adventureTraits.actualEffectCounts.values.sum(),
            "recent_300_count" to recentUiEvents.size,
            "recent_300_trait_changes" to recentUiEvents.count { it.type == RecentAdventureEventType.ADVENTURE_TRAIT_CHANGED },
            "recent_300_trait_activations" to recentUiEvents.count { it.type == RecentAdventureEventType.ADVENTURE_TRAIT_ACTIVATED },
            "reward_rng_boundaries_checked" to rewardBoundaries, "ending_combat_rng" to game.rngState,
            "action_steps" to steps)
    }

    private fun assertOriginalStateEquals(expected: SimpleGameState, actual: SimpleGameState) {
        assertEquals(expected.rngState, actual.rngState)
        assertEquals(expected.hero, actual.hero)
        assertEquals(expected.equipment, actual.equipment)
        assertEquals(expected.inventory, actual.inventory)
        assertEquals(expected.adventureTale, actual.adventureTale)
        assertEquals(expected.adventurePhase, actual.adventurePhase)
        assertEquals(expected.actionStartedAt, actual.actionStartedAt)
        assertEquals(expected.actionEndsAt, actual.actionEndsAt)
        assertEquals(expected.totalKills, actual.totalKills)
        assertEquals(expected.totalItemsFound, actual.totalItemsFound)
        assertEquals(expected.totalEquipmentPurchases, actual.totalEquipmentPurchases)
        assertEquals(expected.totalReturns, actual.totalReturns)
    }

    companion object {
        private val SEEDS = listOf(0x1955_2984_6712_3103L, 0x2744_3695_7123_4027L, 0x3563_4786_8234_5137L, 0x4132_5877_9345_6249L)
        private val SCENARIOS = listOf(Scenario("level_1", 1L), Scenario("level_20", 20L),
            Scenario("level_50", 50L), Scenario("labyrinth_1", 60L, 1L), Scenario("labyrinth_100", 100L, 100L))
    }
}
