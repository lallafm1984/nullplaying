package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaAutoBuildBalanceGuardrailTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `ten authored presets remain competitive across release balance levels`() {
        val levels = listOf(20, 25, 30)
        val classes = HeroClass.entries
        val presets = ArenaAutoBuildPreset.entries
        val failures = mutableListOf<String>()
        levels.forEach { level ->
            val presetTallies = presets.associateWith { Tally() }.toMutableMap()
            val classTallies = classes.associateWith { Tally() }.toMutableMap()
            val classPresetTallies = classes.flatMap { heroClass ->
                presets.map { preset -> (heroClass to preset) to Tally() }
            }.toMap().toMutableMap()
            val matchupTallies = mutableMapOf<Pair<HeroClass, HeroClass>, Tally>()
            val cache = mutableMapOf<List<Any>, ArenaSupportInput>()
            classes.forEachIndexed { leftClassIndex, leftClass ->
                classes.drop(leftClassIndex).forEach { rightClass ->
                    val matchup = Tally()
                    matchupTallies[leftClass to rightClass] = matchup
                    presets.forEach { leftPreset ->
                        presets.forEach { rightPreset ->
                            repeat(GROWTH_SEEDS) { growthSeed ->
                                val firstGrowthSeed = growthSeed * 2
                                val secondGrowthSeed = firstGrowthSeed + 1
                                val leftProfiles = listOf(firstGrowthSeed, secondGrowthSeed).map { profileSeed ->
                                    cache.getOrPut(
                                        listOf(level, leftClass, profileSeed, leftPreset),
                                    ) { fighter(level, leftClass, profileSeed, leftPreset) }
                                }
                                val rightProfiles = listOf(firstGrowthSeed, secondGrowthSeed).map { profileSeed ->
                                    cache.getOrPut(
                                        listOf(level, rightClass, profileSeed, rightPreset),
                                    ) { fighter(level, rightClass, profileSeed, rightPreset) }
                                }
                                repeat(BATTLE_SEEDS) { battleSeed ->
                                    repeat(2) { assignment ->
                                        val firstId = "preset-$level-${leftClass.ordinal}-${rightClass.ordinal}-" +
                                            "${leftPreset.ordinal}-${rightPreset.ordinal}-$growthSeed-$battleSeed-0"
                                        val secondId = firstId.dropLast(1) + "1"
                                        val leftId = if (assignment == 0) firstId else secondId
                                        val rightId = if (assignment == 0) secondId else firstId
                                        val leftBase = leftProfiles[assignment]
                                        val rightBase = rightProfiles[1 - assignment]
                                        val left = leftBase.copy(fighter = leftBase.fighter.copy(id = leftId))
                                        val right = rightBase.copy(fighter = rightBase.fighter.copy(id = rightId))
                                        val seed = 912_000_000_000L + level * 10_000_000L +
                                            leftClass.ordinal * 1_000_000L + rightClass.ordinal * 100_000L +
                                            leftPreset.ordinal * 10_000L + rightPreset.ordinal * 1_000L +
                                            growthSeed * 10L + battleSeed
                                        val result = ArenaSupportTurnEngine.simulate(
                                            left, right, seed, recordEvents = false,
                                        )
                                        assertEquals(ArenaRunStatus.COMPLETED, result.status)
                                        assertEquals(result, ArenaSupportTurnEngine.simulate(
                                            right, left, seed, recordEvents = false,
                                        ))
                                        presetTallies.getValue(leftPreset).record(result, leftId)
                                        presetTallies.getValue(rightPreset).record(result, rightId)
                                        classTallies.getValue(leftClass).record(result, leftId)
                                        classTallies.getValue(rightClass).record(result, rightId)
                                        classPresetTallies.getValue(leftClass to leftPreset)
                                            .record(result, leftId)
                                        classPresetTallies.getValue(rightClass to rightPreset)
                                            .record(result, rightId)
                                        matchup.record(result, leftId)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val presetRates = presetTallies.mapValues { it.value.rate() }
            val classRates = classTallies.mapValues { it.value.rate() }
            val classPresetRates = classPresetTallies.mapValues { it.value.rate() }
            val matchupRates = matchupTallies.filterKeys { it.first != it.second }
                .mapValues { it.value.rate() }
            println("arena-auto-presets level=$level preset=${presetRates.mapValues { fmt(it.value) }}")
            println("arena-auto-presets level=$level class=${classRates.mapValues { fmt(it.value) }}")
            println(
                "arena-auto-presets level=$level classPreset=" +
                    classPresetRates.mapValues { fmt(it.value) },
            )
            println("arena-auto-presets level=$level matchup=${matchupRates.mapValues { fmt(it.value) }}")

            presetRates.forEach { (preset, rate) ->
                if (rate !in 0.40..0.60) failures +=
                    "Lv.$level preset=$preset rate=${fmt(rate)} outside 0.40..0.60"
            }
            val gap = presetRates.values.max() - presetRates.values.min()
            if (gap > 0.15) failures += "Lv.$level preset gap=${fmt(gap)} above 0.15"
            classRates.forEach { (heroClass, rate) ->
                if (rate !in 0.45..0.55) failures +=
                    "Lv.$level class=$heroClass rate=${fmt(rate)} outside 0.45..0.55"
            }
            classPresetRates.forEach { (classPreset, rate) ->
                if (rate !in 0.35..0.65) failures +=
                    "Lv.$level classPreset=$classPreset rate=${fmt(rate)} outside 0.35..0.65"
            }
            matchupRates.forEach { (matchup, rate) ->
                if (rate !in 0.35..0.65) failures +=
                    "Lv.$level matchup=$matchup rate=${fmt(rate)} outside 0.35..0.65"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun fighter(
        level: Int,
        heroClass: HeroClass,
        growthSeed: Int,
        preset: ArenaAutoBuildPreset,
    ): ArenaSupportInput {
        val roll = engine.rollStats(94_000_000L + growthSeed, heroClass)
        val state = engine.newGame("preset", heroClass, roll.stats.copy(), roll.nextSeed, 1_000L)
        state.rngState = ArenaSyntheticProfileGrowth.grow(
            engine = engine,
            stats = state.hero.stats,
            heroClass = heroClass,
            targetLevel = level.toLong(),
            identitySeed = state.rngState,
        )
        state.hero.level = level.toLong()
        val power = (1L + (level - 1L) * 5L) * 2L
        val stats = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
            heroClass, level.toLong(), power, state.hero.stats,
        ))
        return requireNotNull(ArenaV6OpponentInputFactory.create(
            projectionId = "base-$heroClass-$level-$growthSeed-${preset.stableId}",
            displayName = "preset",
            heroClass = heroClass,
            level = level.toLong(),
            combatPower = power,
            stats = stats,
            learnedSkills = PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(
                heroClass, level.toLong(),
            ),
            arenaLevel = level,
            stableSeed = 95_000_000L + growthSeed * 100L + preset.ordinal,
            buildPreset = preset,
        )).combat
    }

    private data class Tally(var wins: Int = 0, var losses: Int = 0, var draws: Int = 0) {
        private val total get() = wins + losses + draws
        fun rate(): Double = (wins + draws * 0.5) / total
        fun record(result: ArenaSupportResult, fighterId: String) {
            when (result.winnerId) {
                fighterId -> wins++
                null -> draws++
                else -> losses++
            }
        }
    }

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

    private companion object {
        // Ten independent seed pairs remove the adjacent-profile bias of the former fixture.
        const val GROWTH_SEEDS = 10
        const val BATTLE_SEEDS = 2
    }
}
