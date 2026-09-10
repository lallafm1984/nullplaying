package com.nullplaying.engine

import com.nullplaying.model.AdventureTraitChange
import com.nullplaying.model.AdventureTraitChangeKind
import com.nullplaying.model.HeroClass
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Natural lifecycle probe. Uses only the deterministic local engine and never remote services. */
class AdventureTraitLongHorizonProbeTest {
    private data class Scenario(val id: String, val level: Long, val depth: Long = 0L)
    private data class Snapshot(
        val scenario: String,
        val heroClass: String,
        val hours: Int,
        val owned: Int,
        val acquired: Int,
        val weakened: Int,
        val recovered: Int,
        val lost: Int,
        val replaced: Int,
        val actualEffects: Long,
        val earliestAcquisitionHours: Double?,
        val medianLostLifetimeHours: Double?,
    )

    @Test(timeout = 240_000L)
    fun `natural traits remain reciprocal and measurable through twelve to one hundred sixty eight hours`() {
        val rows = mutableListOf<Snapshot>()
        val allChanges = mutableListOf<AdventureTraitChange>()
        val allLostLifetimes = mutableListOf<Long>()
        SCENARIOS.forEach { scenario ->
            HeroClass.entries.forEach { heroClass ->
                repeat(SAMPLES_PER_CLASS_SCENARIO) { sample ->
                val engine = SimpleGameEngine(
                    enableAdventureEvents = true,
                    enableAdventureRelationships = true,
                    enableAdventureTraits = true,
                )
                val seed = BASE_SEED + heroClass.ordinal * 97_409L + scenario.level * 1_009L +
                    (sample + 1L) * 7_046_029L
                val game = AdventureQaFixtures.game(engine, heroClass, seed, scenario.level, scenario.depth)
                game.adventureRelationships.roster = AdventureQaFixtures.roster(game, hours = 24L)
                val changes = mutableListOf<AdventureTraitChange>()
                val activeAcquisitions = mutableMapOf<String, Long>()
                val lostLifetimes = mutableListOf<Long>()
                var lastChangeSequence = game.adventureTraits.changeSequence

                for (hour in 1..MAX_HOURS) {
                    if (hour > 1 && (hour - 1) % 24 == 0) {
                        val receivedAt = AdventureQaFixtures.EPOCH + (hour - 1L) * AdventureQaFixtures.HOUR
                        game.adventureRelationships.roster = AdventureQaFixtures.roster(game, receivedAt, hours = 24L)
                    }
                    engine.settleOffline(game, AdventureQaFixtures.EPOCH + hour * AdventureQaFixtures.HOUR)
                    val newChanges = game.adventureTraits.recentChanges
                        .filter { it.sequence > lastChangeSequence }
                        .sortedBy { it.sequence }
                    assertEquals(
                        "$scenario/$heroClass hour $hour compact history lost lifecycle records",
                        game.adventureTraits.changeSequence - lastChangeSequence,
                        newChanges.size.toLong(),
                    )
                    newChanges.forEach { change ->
                        when (change.kind) {
                            AdventureTraitChangeKind.ACQUIRED -> activeAcquisitions[change.traitId] = change.occurredAt
                            AdventureTraitChangeKind.REPLACED -> {
                                activeAcquisitions.remove(change.replacedTraitId)?.let { acquiredAt ->
                                    lostLifetimes += change.occurredAt - acquiredAt
                                    allLostLifetimes += change.occurredAt - acquiredAt
                                }
                                activeAcquisitions[change.traitId] = change.occurredAt
                            }
                            AdventureTraitChangeKind.LOST -> activeAcquisitions.remove(change.traitId)?.let { acquiredAt ->
                                lostLifetimes += change.occurredAt - acquiredAt
                                allLostLifetimes += change.occurredAt - acquiredAt
                            }
                            AdventureTraitChangeKind.WEAKENED,
                            AdventureTraitChangeKind.RECOVERED,
                            -> Unit
                        }
                    }
                    changes += newChanges
                    allChanges += newChanges
                    lastChangeSequence = game.adventureTraits.changeSequence

                    val held = game.adventureTraits.owned.map { it.traitId }.toSet()
                    assertEquals(held.size, game.adventureTraits.owned.size)
                    assertTrue(held.all { AdventureTraitCatalog.find(it) != null })
                    AdventureTraitCatalog.all.filter { it.id in held }.forEach { definition ->
                        assertTrue("Opposite traits coexisted: ${definition.id}/${definition.oppositeId}",
                            definition.oppositeId !in held)
                    }

                    if (hour in HORIZONS) {
                        val counts = AdventureTraitChangeKind.entries.associateWith { kind ->
                            changes.count { it.kind == kind }
                        }
                        val acquired = counts.getValue(AdventureTraitChangeKind.ACQUIRED) +
                            counts.getValue(AdventureTraitChangeKind.REPLACED)
                        val lost = counts.getValue(AdventureTraitChangeKind.LOST) +
                            counts.getValue(AdventureTraitChangeKind.REPLACED)
                        assertEquals("Natural ownership accounting", game.adventureTraits.owned.size, acquired - lost)
                        rows += Snapshot(
                            scenario = scenario.id,
                            heroClass = heroClass.name,
                            hours = hour,
                            owned = game.adventureTraits.owned.size,
                            acquired = acquired,
                            weakened = counts.getValue(AdventureTraitChangeKind.WEAKENED),
                            recovered = counts.getValue(AdventureTraitChangeKind.RECOVERED),
                            lost = lost,
                            replaced = counts.getValue(AdventureTraitChangeKind.REPLACED),
                            actualEffects = game.adventureTraits.actualEffectCounts.values.sum(),
                            earliestAcquisitionHours = changes
                                .filter { it.kind == AdventureTraitChangeKind.ACQUIRED || it.kind == AdventureTraitChangeKind.REPLACED }
                                .minOfOrNull { (it.occurredAt - AdventureQaFixtures.EPOCH).toDouble() / AdventureQaFixtures.HOUR },
                            medianLostLifetimeHours = lostLifetimes.sorted().takeIf { it.isNotEmpty() }
                                ?.let { it[it.size / 2].toDouble() / AdventureQaFixtures.HOUR },
                        )
                    }
                }
                }
            }
        }

        assertEquals(SCENARIOS.size * HeroClass.entries.size * SAMPLES_PER_CLASS_SCENARIO * HORIZONS.size, rows.size)
        assertTrue("No natural trait formed in 48 hours", rows.filter { it.hours == 48 }.any { it.acquired > 0 })
        assertTrue("No owned trait produced a real effect in 168 hours", rows.filter { it.hours == 168 }.any { it.actualEffects > 0L })
        assertTrue("No weakening evidence was exercised", allChanges.any { it.kind == AdventureTraitChangeKind.WEAKENED })
        assertTrue("No loss or replacement path was exercised",
            allChanges.any { it.kind == AdventureTraitChangeKind.LOST || it.kind == AdventureTraitChangeKind.REPLACED })
        val finalRows = rows.filter { it.hours == MAX_HOURS }
        val firstAcquisitionMedian = median(finalRows.mapNotNull { it.earliestAcquisitionHours })
        val finalOwnedMedian = median(finalRows.map { it.owned.toDouble() })
        assertTrue("First acquisition escaped its intended active-time band: $firstAcquisitionMedian",
            firstAcquisitionMedian in 8.0..9.0)
        assertTrue("Forty-eight-hour cohort removed a newly formed trait before its lifetime floor",
            rows.filter { it.hours == 48 }.all { it.lost == 0 })
        assertTrue("Final ownership is too sparse or saturated: $finalOwnedMedian", finalOwnedMedian in 5.0..7.0)
        assertTrue("Lifecycle churn exceeded the seven-day guardrail: ${allChanges.size}", allChanges.size <= 500)
        assertTrue("A completed trait lifetime crossed the 48-hour active minimum: $allLostLifetimes",
            allLostLifetimes.all { it >= AdventureTraitEngine.STABLE_MIN_ACTIVE_MILLIS +
                AdventureTraitEngine.SHAKY_LOSS_MIN_ACTIVE_MILLIS })

        val qa = AdventureQaFixtures.stagingQaDirectory()
        qa.resolve("adventure-trait-long-horizon.csv").toFile().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("scenario,class,active_hours,owned,acquired,weakened,recovered,lost,replaced,actual_effects,earliest_acquisition_hours,median_lost_lifetime_hours")
            rows.forEach { row ->
                writer.appendLine(listOf(
                    row.scenario, row.heroClass, row.hours, row.owned, row.acquired, row.weakened,
                    row.recovered, row.lost, row.replaced, row.actualEffects,
                    decimal(row.earliestAcquisitionHours), decimal(row.medianLostLifetimeHours),
                ).joinToString(","))
            }
        }
        qa.resolve("adventure-trait-long-horizon-notes.txt").toFile().writeText(
            "Synthetic deterministic active-adventure probe; no production, server, or calendar-time data.\n" +
                "Six classes x main Lv20/labyrinth Lv100 x four fixed seeds each; cumulative checkpoints 12h, 24h, 48h, 72h, 168h.\n" +
                "Formation: 8/12 positive signs, at least 3 contexts, at most 3 opposing signs, 8 active hours, then a 25% formation decision and 24 active-hour global cooldown after acquisition.\n" +
                "Weakening: 4/12 opposing signs from 3 contexts after 24 stable active hours and 8 active hours of opposition.\n" +
                "Recovery: fresh support, at most 3 opposing signs, 3 contexts, and 8 active hours after weakening.\n" +
                "Loss/replacement: 6/12 opposing signs from 3 contexts and 24 active hours after weakening; earliest complete held lifetime is 48 active hours.\n" +
                "Rows=${rows.size}; catalog=${AdventureTraitCatalog.all.size}; opposite_pairs=${AdventureTraitCatalog.all.map { setOf(it.id, it.oppositeId) }.toSet().size}.\n",
        )
        println("Adventure trait long horizon rows=${rows.size}; changes=${allChanges.size}")
    }

    private fun decimal(value: Double?): String = value?.let {
        String.format(Locale.ROOT, "%.4f", it)
    }.orEmpty()

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    companion object {
        private const val MAX_HOURS = 168
        private const val SAMPLES_PER_CLASS_SCENARIO = 4
        private val HORIZONS = setOf(12, 24, 48, 72, 168)
        private const val BASE_SEED = 0x4A19_7053_2C61_8801L
        private val SCENARIOS = listOf(
            Scenario("main_level_20", 20L),
            Scenario("labyrinth_100", 100L, 100L),
        )
    }
}
