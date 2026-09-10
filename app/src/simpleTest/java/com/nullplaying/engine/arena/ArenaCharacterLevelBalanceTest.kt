package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Independent normal stat rolls, all six classes and ten authored builds; no underdog modifier. */
class ArenaCharacterLevelBalanceTest {
    private val engine = SimpleGameEngine()
    private val cache = mutableMapOf<String, ArenaSupportInput>()

    @Test fun `10800 current-policy battles preserve nearby level comeback opportunities`() {
        var battles = 0
        val failures = mutableListOf<String>()
        for (upper in listOf(20, 25, 30)) for (gap in 0..2) {
            val totals = IntArray(3)
            val classes = HeroClass.entries.associateWith { IntArray(3) }
            val presets = ArenaAutoBuildPreset.entries.associateWith { IntArray(3) }
            for (heroClass in HeroClass.entries) for (preset in ArenaAutoBuildPreset.entries) repeat(20) { sample ->
                val otherClass = HeroClass.entries[(heroClass.ordinal + sample + preset.ordinal) % 6]
                val otherPreset = ArenaAutoBuildPreset.entries[(sample + 3 * preset.ordinal) % 10]
                val low = fighter(upper - gap, heroClass, sample, preset)
                val high = fighter(upper, otherClass, sample + 31, otherPreset)
                val seed = 7_310_000L + upper * 100_000L + gap * 10_000L + heroClass.ordinal * 1_000 + preset.ordinal * 20 + sample
                val result = if (sample % 2 == 0) ArenaSupportTurnEngine.simulate(low, high, seed, recordEvents = false)
                    else ArenaSupportTurnEngine.simulate(high, low, seed, recordEvents = false)
                assertEquals(ArenaRunStatus.COMPLETED, result.status)
                assertEquals(upper - gap, low.arenaLevel)
                assertEquals(upper, high.arenaLevel)
                val outcome = when (result.winnerId) { low.fighter.id -> 0; null -> 2; else -> 1 }
                totals[outcome]++; classes.getValue(heroClass)[outcome]++; presets.getValue(preset)[outcome]++
                battles++
            }
            val winRate = totals[0].toDouble() / totals.sum()
            println("character-points upper=$upper gap=$gap low=${upper-gap} wins=${totals[0]} losses=${totals[1]} draws=${totals[2]} rate=${fmt(winRate)}")
            classes.forEach { (key, tally) -> println("character-points-class upper=$upper gap=$gap class=$key tally=${tally.joinToString("/")}") }
            presets.forEach { (key, tally) -> println("character-points-preset upper=$upper gap=$gap preset=$key tally=${tally.joinToString("/")}") }
            // Broad safety guards, not an artificial target or guaranteed per-player win rate.
            val band = when (gap) { 0 -> 0.40..0.60; 1 -> 0.25..0.60; else -> 0.20..0.60 }
            if (winRate !in band) failures += "upper=$upper gap=$gap lower win rate=$winRate outside $band"
            if (totals[2] != 0) failures += "unexpected draws upper=$upper gap=$gap: ${totals[2]}"
        }
        assertEquals(10_800, battles)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun fighter(level: Int, heroClass: HeroClass, sample: Int, preset: ArenaAutoBuildPreset): ArenaSupportInput {
        val key = "$level-$heroClass-$sample-$preset"
        return cache.getOrPut(key) {
            val roll = engine.rollStats(73_000_000L + sample * 1_003L, heroClass)
            val stats = roll.stats.copy()
            ArenaSyntheticProfileGrowth.grow(engine, stats, heroClass, targetLevel = level.toLong(), identitySeed = roll.nextSeed)
            val power = (1L + (level - 1) * 5L) * 2L
            val derived = requireNotNull(PublicPlayerBattleDerivation.deriveStats(heroClass, level.toLong(), power, stats))
            requireNotNull(ArenaV6OpponentInputFactory.create(
                projectionId = key, displayName = "Balance", heroClass = heroClass, level = level.toLong(),
                combatPower = power, stats = derived,
                learnedSkills = PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(heroClass, level.toLong()),
                arenaLevel = ArenaCharacterPointRules.budget(level.toLong()), stableSeed = 73_100L + sample,
                buildPreset = preset,
            )).combat
        }
    }
    private fun fmt(value: Double) = String.format(Locale.ROOT, "%.4f", value)
}
