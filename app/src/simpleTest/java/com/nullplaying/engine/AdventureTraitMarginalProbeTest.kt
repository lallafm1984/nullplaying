package com.nullplaying.engine

import com.nullplaying.model.AdventureTraitChangeKind
import com.nullplaying.model.HeroClass
import com.nullplaying.model.RecentAdventureEventType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Isolates traits from the already-enabled event and relationship systems. No live inputs or forced traits. */
class AdventureTraitMarginalProbeTest {
    private data class Scenario(val id: String, val level: Long, val depth: Long = 0L)

    @Test(timeout = 60_000L)
    fun `traits alone preserve cash accounting across ninety six paired six hour fixtures`() {
        val rows = mutableListOf<Map<String, Any>>()
        val started = System.nanoTime()
        val scenarios = listOf(Scenario("level_20", 20L), Scenario("labyrinth_100", 100L, 100L))
        scenarios.forEach { scenario ->
            HeroClass.entries.forEach { heroClass ->
                SEEDS.forEach { seed ->
                    listOf(false, true).forEach { traitsEnabled ->
                        rows += measure(scenario, heroClass, seed, traitsEnabled)
                    }
                }
            }
        }
        assertEquals(96, rows.size)
        val columns = rows.first().keys.toList()
        val qa = AdventureQaFixtures.stagingQaDirectory()
        qa.resolve("adventure-trait-marginal.csv").toFile().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(columns.joinToString(","))
            rows.forEach { row -> writer.appendLine(columns.joinToString(",") { cell(row.getValue(it)) }) }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        println("Adventure trait marginal probe: ${rows.size} rows, ${String.format(Locale.ROOT, "%.1f", elapsed)} ms")
    }

    private fun measure(scenario: Scenario, heroClass: HeroClass, seed: Long, traitsEnabled: Boolean): Map<String, Any> {
        val engine = SimpleGameEngine(enableAdventureEvents = true,
            enableAdventureRelationships = true, enableAdventureTraits = traitsEnabled)
        val game = AdventureQaFixtures.game(engine, heroClass, seed, scenario.level, scenario.depth)
        game.adventureRelationships.roster = AdventureQaFixtures.roster(game)
        assertTrue(game.adventureTraits.owned.isEmpty())
        val key = "${scenario.id}/${heroClass.name}/$seed/$traitsEnabled"
        val end = AdventureQaFixtures.EPOCH + 6L * AdventureQaFixtures.HOUR
        val startGold = game.hero.gold
        val startPower = engine.displayCombatPower(game)
        var xp = 0L
        var actualPurchaseCost = 0L
        var actGold = 0L
        var progress = 0L
        var steps = 0L
        var validCandidateMillis = 0L
        var heldTraitMillis = 0L
        var peakOwned = 0
        var acquired = 0L
        var lost = 0L

        fun advance(at: Long) {
            val elapsed = at - game.lastSettledAt
            assertTrue("$key: forward phase time", elapsed > 0L)
            validCandidateMillis += AdventureQaFixtures.sourceValidTimeThrough(game, at)
            heldTraitMillis += game.adventureTraits.owned.size * elapsed
            val previousXp = game.hero.experience
            val previousLevel = game.hero.level
            val previousPurchases = game.totalEquipmentPurchases
            val previousActs = game.totalActs
            val previousAct = game.adventureTale.activeAct()
            val previousRewardGold = previousAct.rewardGold
            val previousProgress = previousAct.progress
            val previousTarget = previousAct.target
            val previousActKey = "${game.adventureTale.sequence}:${game.adventureTale.currentActIndex}:${previousAct.id}"
            val settled = engine.settleOffline(game, at)
            assertTrue("$key: bounded phase loop", ++steps < 500_000L)
            assertTrue("$key: next phase ends later", game.actionEndsAt > at)
            assertEquals(at, game.lastSettledAt)
            xp += game.hero.experience - previousXp
            for (level in previousLevel until game.hero.level) xp += engine.experienceRequired(level)
            val purchases = game.totalEquipmentPurchases - previousPurchases
            assertTrue("$key: at most one purchase per phase", purchases in 0L..1L)
            if (purchases == 1L) {
                val paid = requireNotNull(game.lastShopPurchase).price
                assertTrue("$key: actual paid price", paid > 0L)
                actualPurchaseCost += paid
            }
            val completedActs = game.totalActs - previousActs
            assertTrue("$key: at most one act per phase", completedActs in 0L..1L)
            if (completedActs == 1L) actGold += previousRewardGold
            val currentAct = game.adventureTale.activeAct()
            val currentActKey = "${game.adventureTale.sequence}:${game.adventureTale.currentActIndex}:${currentAct.id}"
            progress += if (previousActKey == currentActKey) (currentAct.progress - previousProgress).coerceAtLeast(0L)
                else (previousTarget - previousProgress).coerceAtLeast(0L) + currentAct.progress
            peakOwned = maxOf(peakOwned, game.adventureTraits.owned.size)
            settled.recentEvents.filter { it.type == RecentAdventureEventType.ADVENTURE_TRAIT_CHANGED }.forEach { change ->
                when (change.contextName) {
                    AdventureTraitChangeKind.ACQUIRED.name -> acquired++
                    AdventureTraitChangeKind.REPLACED.name -> { acquired++; lost++ }
                    AdventureTraitChangeKind.LOST.name -> lost++
                    else -> Unit
                }
            }
        }

        while (game.actionEndsAt <= end) advance(game.actionEndsAt)
        if (game.lastSettledAt < end) advance(end)
        assertEquals(end, game.lastSettledAt)
        assertTrue(game.hero.gold >= 0L)
        val cashEarned = actualPurchaseCost + game.hero.gold - startGold
        assertEquals(
            "$key: reconstructed gross cash inflow",
            game.totalSaleGold + game.adventureJourney.totalGold + game.adventureRelationships.totalGold + actGold,
            cashEarned,
        )
        assertEquals("$key: natural ownership accounting", game.adventureTraits.owned.size.toLong(), acquired - lost)
        if (!traitsEnabled) {
            assertTrue(game.adventureTraits.owned.isEmpty())
            assertTrue(game.adventureTraits.actualEffectCounts.isEmpty())
        }
        assertTrue("$key: events stay enabled in both modes", game.adventureJourney.completedEvents > 0L)
        assertTrue("$key: relationship scheduler stays enabled in both modes", game.adventureRelationships.initialized)
        val bagValue = game.inventory.sumOf { AdventureQaFixtures.baselineSaleValue(engine, game, it) }
        return linkedMapOf(
            "scenario" to scenario.id, "class" to heroClass.name, "seed" to seed,
            "mode" to if (traitsEnabled) "STAGE3_TRAITS_ON" else "STAGE2_TRAITS_OFF",
            "events_enabled" to true, "relationships_enabled" to true, "traits_enabled" to traitsEnabled,
            "active_hours" to 6, "candidate_valid_hours" to validCandidateMillis.toDouble() / AdventureQaFixtures.HOUR,
            "start_level" to scenario.level, "end_level" to game.hero.level,
            "start_depth" to scenario.depth, "end_depth" to game.adventureTale.labyrinthDepth,
            "xp" to xp, "xp_per_hour" to xp / 6.0,
            "items" to game.totalItemsFound, "items_per_hour" to game.totalItemsFound / 6.0,
            "sale_items" to game.totalItemsSold, "sale_actual_gold" to game.totalSaleGold,
            "relationship_gold" to game.adventureRelationships.totalGold,
            "purchases" to game.totalEquipmentPurchases, "purchase_actual_cost" to actualPurchaseCost,
            "start_gold" to startGold, "end_gold" to game.hero.gold,
            "cash_earned" to cashEarned, "cash_earned_per_hour" to cashEarned / 6.0,
            "ending_bag_items" to game.inventory.size, "ending_bag_base_value" to bagValue,
            "earned_plus_ending_bag_base_value" to cashEarned + bagValue,
            "start_cp" to startPower, "end_cp" to engine.displayCombatPower(game),
            "loot_upgrades" to game.totalLootEquipmentEquips, "returns" to game.totalReturns,
            "kills" to game.totalKills, "progress" to progress,
            "events" to game.adventureJourney.completedEvents,
            "relationships" to game.adventureRelationships.totalEncounters,
            "trait_acquisitions" to acquired, "trait_losses" to lost,
            "owned_traits_at_end" to game.adventureTraits.owned.size, "peak_owned_traits" to peakOwned,
            "held_trait_hours" to heldTraitMillis.toDouble() / AdventureQaFixtures.HOUR,
            "trait_actual_effects" to game.adventureTraits.actualEffectCounts.values.sum(), "action_steps" to steps,
        )
    }

    private fun cell(value: Any): String {
        val text = if (value is Double) String.format(Locale.ROOT, "%.8f", value) else value.toString()
        return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"${text.replace("\"", "\"\"")}\"" else text
    }

    companion object {
        // Fixed fresh domains distinct from the 16-seed whole-expansion cohort.
        private val SEEDS = listOf(6_610_468_873_229_516_743L, -2_538_291_746_805_317_921L,
            3_791_220_458_968_174_563L, -8_121_974_051_637_428_693L)
    }
}
