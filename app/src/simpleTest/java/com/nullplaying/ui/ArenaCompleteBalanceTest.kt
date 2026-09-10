package com.nullplaying.ui

import com.nullplaying.engine.arena.*
import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

/** Exploratory fixed-growth sample. Win rates are reported, never asserted to equal 50%. */
class ArenaCompleteBalanceTest {
    @Test fun `equal earned budgets compare focused mixed and defensive builds by class and hero band`() {
        val supportUsed = mutableSetOf<String>()
        val traitTriggered = mutableSetOf<String>()
        val lines = mutableListOf("heroLevel\tarenaLevel\tbuild\tclass\twins\tbattles\twinRate\tmeanTurns\tbaseSpent\tenhancementSpent")
        var runs = 0
        var longest = 0
        for (heroLevel in listOf(10, 20, 50, 100)) for (arenaLevel in listOf(1, 20, 50, 100)) {
            for (profile in listOf("focused", "mixed", "defensive")) {
                val wins = IntArray(6); val battles = IntArray(6); val turns = IntArray(6)
                val fighters = HeroClass.entries.map { heroClass ->
                    val raw = ArenaCompleteIntegrationTest.input(heroClass, heroClass.name, heroLevel, arenaLevel)
                    raw.copy(traits = build(heroClass, heroLevel, arenaLevel, profile))
                }
                for (leftIndex in 0..5) for (rightIndex in leftIndex..5) for (seed in 1L..3L) {
                    var left = fighters[leftIndex]
                    var right = fighters[rightIndex]
                    if (leftIndex == rightIndex) {
                        left = left.copy(fighter = left.fighter.copy(id = "mirror-a"))
                        right = right.copy(fighter = right.fighter.copy(id = "mirror-b"))
                    }
                    val result = ArenaSupportTurnEngine.simulate(left, right, seed)
                    assertEquals("$heroLevel/$arenaLevel/$profile/$leftIndex/$rightIndex", ArenaRunStatus.COMPLETED, result.status)
                    assertTrue(result.turns < 500)
                    val mirrored = ArenaSupportTurnEngine.simulate(right, left, seed)
                    assertEquals("Input order must not change the simulation", result, mirrored)
                    runs += 2
                    longest = maxOf(longest, result.turns)
                    result.events.forEach {
                        if (it.type == ArenaSupportEventType.SUPPORT_APPLIED || it.type == ArenaSupportEventType.HEAL_APPLIED) it.actionId?.let(supportUsed::add)
                        if (it.type == ArenaSupportEventType.TRAIT_TRIGGERED) it.traitId?.let(traitTriggered::add)
                    }
                    if (leftIndex != rightIndex) {
                        battles[leftIndex]++; battles[rightIndex]++
                        turns[leftIndex] += result.turns; turns[rightIndex] += result.turns
                        if (result.winnerId != null) wins[if (result.winnerId == left.fighter.id) leftIndex else rightIndex]++
                    }
                    // Validate the real playback ledger on each matchup and each class/level/build.
                    if (seed == 1L) {
                        val timeline = buildArenaLiveTimeline(result, mapOf(left.fighter.id to "Noah", right.fighter.id to "Eve"))
                        assertTrue(timeline.logs.map { it.sequence }.zipWithNext().all { (a,b) -> a < b })
                    }
                }
                fighters.forEachIndexed { index, f ->
                    val baseSpent = f.traits.sumOf { requireNotNull(ArenaProgressionCatalog.find(it.id)).basePointCost(it.rank) }
                    val enhancementSpent = f.traits.sumOf { ArenaProgressionRules.enhancementCost(it.enhancement) }
                    assertEquals("Usable full basic budget $heroLevel/$profile/${f.fighter.heroClass}", minOf(arenaLevel,50), baseSpent)
                    assertEquals("Usable enhancement budget $heroLevel/$profile/${f.fighter.heroClass}", if (arenaLevel == 100) 50 else 0, enhancementSpent)
                    lines += listOf(heroLevel, arenaLevel, profile, f.fighter.heroClass, wins[index], battles[index],
                        String.format(Locale.ROOT,"%.4f", wins[index].toDouble()/battles[index]),
                        String.format(Locale.ROOT,"%.2f", turns[index].toDouble()/battles[index]), baseSpent, enhancementSpent).joinToString("\t")
                }
            }
        }
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        val output = File(if(cwd.name == "app") cwd.parentFile else cwd, "output/audits/2026-09-06-arena-complete/balance").apply { mkdirs() }
        File(output,"equal-budget.tsv").writeText(lines.joinToString("\n") + "\n")
        File(output,"coverage.txt").writeText("runs=$runs longestTurns=$longest\n" +
            "Observed supports=${supportUsed.size}/60\n" + "Unobserved supports=" +
            (ArenaSupportCatalog.values.map { it.id }.toSet() - supportUsed).sorted().joinToString(",") +
            "\nObserved traits=${traitTriggered.size}/144\nUnobserved traits=" +
            (ArenaProgressionCatalog.values.map { it.id }.toSet() - traitTriggered).sorted().joinToString(",") + "\n")
    }

    private fun build(heroClass: HeroClass, heroLevel: Int, arenaLevel: Int, profile: String): List<ArenaSupportTraitRank> {
        val all = ArenaProgressionCatalog.forClass(heroClass)
        val ordered = when(profile) {
            "focused" -> all
            "mixed" -> all.sortedBy { it.id.takeLast(2).toIntOrNull() ?: 8 }
            else -> all.sortedBy { when(it.branch) { "B" -> 0; "C" -> 1; else -> 2 } }
        }
        val owned = ArenaSupportCatalog.unlockedIds(heroClass,heroLevel.toLong())
        var state = ArenaProgressionState(unlocked=true,totalXp=ArenaProgressionRules.xpForLevel(arenaLevel))
        // Mixed builds spread ranks before deepening; others fill each preferred node.
        val purchases = if(profile == "mixed") (1..5).flatMap { rank -> ordered.map { it to minOf(rank,it.maxRank) } }
            else ordered.flatMap { definition -> (1..definition.maxRank).map { definition to it } }
        purchases.forEach { (definition, rank) ->
            val next=ArenaProgressionRules.allocate(state,heroClass,heroLevel.toLong(),owned,definition.id,rank,0)
            if(next.accepted) state=next.state
        }
        ordered.filterNot { it.isCore }.forEach { definition ->
            val allocation=state.allocations.firstOrNull { it.id==definition.id } ?: return@forEach
            for(stage in 1..3) {
                val next=ArenaProgressionRules.allocate(state,heroClass,heroLevel.toLong(),owned,definition.id,allocation.rank,stage)
                if(next.accepted)state=next.state
            }
        }
        return ArenaProgressionRules.toSupportTraits(state)
    }
}
