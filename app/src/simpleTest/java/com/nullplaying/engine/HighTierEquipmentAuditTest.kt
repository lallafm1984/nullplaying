package com.nullplaying.engine

import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic saves only. Observe every action, so short-lived full sets are not missed. */
class HighTierEquipmentAuditTest {
    private val selectedClasses = System.getenv("EQUIPMENT_AUDIT_CLASS")?.let { listOf(HeroClass.valueOf(it)) }
        ?: HeroClass.entries.toList()
    private val epoch = AdventureQaFixtures.EPOCH
    private val day = 86_400_000L
    private val output = generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("docs/SESSION_HANDOFF.md")) }
        .resolve("output/verification/2026-09-15/equipment")
        .let { if (selectedClasses.size == 1) it.resolve(selectedClasses.single().name) else it }

    private class LevelStats(val level: Long, val entry4: Int, val entry5: Int) {
        var actions = 0L
        var maxHigh = entry4 + entry5
        var fullObserved = false
        val millisByHighSlots = LongArray(7)
    }

    @Test(timeout = 1_800_000L)
    fun `audit every level and every equipment transition during natural and high level progression`() {
        Files.createDirectories(output)
        val rows = output.resolve("levels.csv").toFile().bufferedWriter()
        val full = output.resolve("full-sets.csv").toFile().bufferedWriter()
        val totals = output.resolve("characters.csv").toFile().bufferedWriter()
        rows.use { levels -> full.use { sets -> totals.use { characters ->
            levels.appendLine("cohort,class,seed,level,entry_plus4,entry_plus5,exit_plus4,exit_plus5,max_high_slots,full_observed,actions,ms_0,ms_1,ms_2,ms_3,ms_4,ms_5,ms_6")
            sets.appendLine("cohort,class,seed,level,active_days,items")
            characters.appendLine("cohort,class,seed,start_level,end_level,active_days,kills,events,relationships,actions,max_high_slots,full_levels")
            fun measure(cohort: String, heroClass: HeroClass, sample: Int, start: Long, target: Long) {
                val seed = AdventureQaFixtures.finalSeeds[sample] xor (heroClass.ordinal * 97_409L)
                val engine = SimpleGameEngine(enableAdventureEvents = true,
                    enableAdventureRelationships = true, enableAdventureTraits = true)
                val roll = engine.rollStats(seed, heroClass)
                val state = if (start == 1L) engine.newGame("장비 검증", heroClass, roll.stats, roll.nextSeed, epoch)
                    else AdventureQaFixtures.game(engine, heroClass, seed, start, start)
                state.rankingCharacterId = "equipment-audit-${heroClass.name}-$sample-$start"
                fun count(rarity: String) = state.equipment.count { it.rarity == rarity }
                var stats = LevelStats(state.hero.level, count("전설"), count("신화"))
                var actionCount = 0L
                var maximum = 0
                var fullLevels = 0
                var nextRoster = epoch
                fun writeLevel(exit4: Int = count("전설"), exit5: Int = count("신화")) {
                    levels.appendLine(listOf(cohort, heroClass.name, seed, stats.level, stats.entry4, stats.entry5,
                        exit4, exit5, stats.maxHigh, stats.fullObserved, stats.actions,
                        *stats.millisByHighSlots.toTypedArray()).joinToString(","))
                    if (stats.fullObserved) fullLevels++
                    levels.flush()
                }
                while (state.hero.level < target && state.lastSettledAt - epoch < 2_000L * day) {
                    // Half of the natural cohort has a fresh synthetic relationship roster.
                    if (sample % 2 == 1 && state.lastSettledAt >= nextRoster) {
                        state.adventureRelationships.roster = AdventureQaFixtures.roster(state, state.lastSettledAt, hours = 24L)
                        nextRoster = state.lastSettledAt + day
                    }
                    val at = state.actionEndsAt
                    assertTrue("Timeline must progress: $cohort/$heroClass/$seed", at > state.lastSettledAt)
                    val before4 = count("전설")
                    val before5 = count("신화")
                    val highBefore = before4 + before5
                    stats.millisByHighSlots[highBefore] += at - state.lastSettledAt
                    val oldLevel = state.hero.level
                    engine.settleOffline(state, at)
                    actionCount++
                    stats.actions++
                    assertEquals(EquipmentSlot.entries.size, state.equipment.size)
                    val high = count("전설") + count("신화")
                    maximum = maxOf(maximum, high)
                    // Attribute post-action gear to the resulting level; keep the previous peak too.
                    if (state.hero.level != oldLevel) {
                        assertEquals(oldLevel + 1L, state.hero.level)
                        writeLevel(before4, before5)
                        stats = LevelStats(state.hero.level, count("전설"), count("신화"))
                    }
                    stats.maxHigh = maxOf(stats.maxHigh, high)
                    if (high == EquipmentSlot.entries.size && !stats.fullObserved) {
                        stats.fullObserved = true
                        sets.appendLine(listOf(cohort, heroClass.name, seed, state.hero.level,
                            (at - epoch).toDouble() / day,
                            state.equipment.joinToString("|") { "${it.slot}:${it.rarity}:L${it.acquiredAtLevel}:${it.power}" }).joinToString(","))
                        sets.flush()
                    }
                }
                writeLevel()
                characters.appendLine(listOf(cohort, heroClass.name, seed, start, state.hero.level,
                    (state.lastSettledAt - epoch).toDouble() / day, state.totalKills,
                    state.adventureJourney.completedEvents, state.adventureRelationships.totalEncounters,
                    actionCount, maximum, fullLevels).joinToString(","))
                characters.flush()
                levels.flush()
                println("Equipment audit $cohort $heroClass sample=$sample L$start..${state.hero.level} max=$maximum fullLevels=$fullLevels actions=$actionCount")
                assertEquals("Cohort must reach target", target, state.hero.level)
            }
            selectedClasses.forEach { heroClass ->
                repeat(4) { sample -> measure("natural", heroClass, sample, 1L, 150L) }
            }
            // Accelerated starts are explicitly separate from natural progression evidence.
            for (start in listOf(300L, 1_000L)) {
                selectedClasses.forEach { heroClass -> measure("synthetic_high", heroClass, 1, start, start + 5L) }
            }
        } } }
    }
}
