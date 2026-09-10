package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import com.nullplaying.model.SimpleGameState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hero growth and Arena progression are independent saved values. This guard keeps the class
 * normalization tied to hero growth and exercises every realistic cross-axis combination instead
 * of assuming that hero level and Arena level advance together.
 */
class ArenaHeroArenaLevelCrossBalanceTest {
    private val engine = SimpleGameEngine()
    // Engine input is immutable and simulate copies it. Bound reuse to one sample block so
    // independent growth cohorts can expand without repeatedly rebuilding identical presets.
    private data class FighterKey(
        val heroLevel: Int, val arenaLevel: Int, val heroClass: HeroClass,
        val growthSeed: Int, val preset: ArenaAutoBuildPreset, val powerScalePermille: Int,
    )
    private val fighterCache = object : LinkedHashMap<FighterKey, ArenaSupportInput>(512, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FighterKey, ArenaSupportInput>): Boolean =
            size > 4_096
    }

    @Test
    fun `lagging arena release states remain balanced and ahead states avoid fatal skew`() {
        val failures = mutableListOf<String>()
        HERO_LEVELS.forEach { heroLevel ->
            ARENA_LEVELS.forEach { arenaLevel ->
                val classTallies = HeroClass.entries.associateWith { Tally() }.toMutableMap()
                val matchupTallies = mutableMapOf<Pair<HeroClass, HeroClass>, Tally>()
                HeroClass.entries.forEachIndexed { leftIndex, leftClass ->
                    HeroClass.entries.drop(leftIndex + 1).forEach { rightClass ->
                        val matchup = Tally()
                        matchupTallies[leftClass to rightClass] = matchup
                        ArenaAutoBuildPreset.entries.forEach { preset ->
                            repeat(GROWTH_SEEDS) { growthSeed ->
                                repeat(2) { assignment ->
                                    // Each class receives both independently rolled profiles. This
                                    // retains a paired comparison without treating one raw-stat roll
                                    // as representative of every character.
                                    val firstGrowthSeed = growthSeed * 2
                                    val secondGrowthSeed = firstGrowthSeed + 1
                                    val leftBase = fighter(
                                        heroLevel,
                                        arenaLevel,
                                        leftClass,
                                        if (assignment == 0) firstGrowthSeed else secondGrowthSeed,
                                        preset,
                                    )
                                    val rightBase = fighter(
                                        heroLevel,
                                        arenaLevel,
                                        rightClass,
                                        if (assignment == 0) secondGrowthSeed else firstGrowthSeed,
                                        preset,
                                    )
                                    val firstId = "cross-$heroLevel-$arenaLevel-${leftClass.ordinal}-" +
                                        "${rightClass.ordinal}-${preset.ordinal}-$growthSeed-0"
                                    val secondId = firstId.dropLast(1) + "1"
                                    val leftId = if (assignment == 0) firstId else secondId
                                    val rightId = if (assignment == 0) secondId else firstId
                                    val left = leftBase.copy(
                                        fighter = leftBase.fighter.copy(id = leftId),
                                    )
                                    val right = rightBase.copy(
                                        fighter = rightBase.fighter.copy(id = rightId),
                                    )
                                    val seed = 713_000_000_000L + heroLevel * 100_000_000L +
                                        arenaLevel * 1_000_000L + leftClass.ordinal * 100_000L +
                                        rightClass.ordinal * 10_000L + preset.ordinal * 100L +
                                        growthSeed
                                    val result = ArenaSupportTurnEngine.simulate(
                                        left, right, seed, recordEvents = false,
                                    )
                                    assertEquals(ArenaRunStatus.COMPLETED, result.status)
                                    assertEquals(
                                        result,
                                        ArenaSupportTurnEngine.simulate(
                                            right, left, seed, recordEvents = false,
                                        ),
                                    )
                                    classTallies.getValue(leftClass).record(result, leftId)
                                    classTallies.getValue(rightClass).record(result, rightId)
                                    matchup.record(result, leftId)
                                }
                            }
                        }
                    }
                }
                val classRates = classTallies.mapValues { it.value.rate() }
                val matchupRates = matchupTallies.mapValues { it.value.rate() }
                println(
                    "arena-cross hero=$heroLevel arena=$arenaLevel " +
                        "class=${classRates.mapValues { fmt(it.value) }} " +
                        "matchupRange=${fmt(matchupRates.values.min())}.." +
                        fmt(matchupRates.values.max()),
                )
                val laggingOrEqualArena = arenaLevel <= heroLevel
                val classBand = if (laggingOrEqualArena) 0.45..0.55 else 0.10..0.90
                val matchupBand = if (laggingOrEqualArena) 0.35..0.65 else 0.01..0.99
                classRates.forEach { (heroClass, rate) ->
                    if (rate !in classBand) failures +=
                        "hero=$heroLevel arena=$arenaLevel class=$heroClass " +
                            "rate=${fmt(rate)} outside $classBand"
                }
                matchupRates.forEach { (matchup, rate) ->
                    if (rate !in matchupBand) failures +=
                        "hero=$heroLevel arena=$arenaLevel matchup=$matchup " +
                            "rate=${fmt(rate)} outside $matchupBand"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `plus or minus one hero level matching stays symmetric and competitive`() {
        val failures = mutableListOf<String>()
        ADJACENT_SCENARIOS.forEach { (lowerHeroLevel, arenaLevel) ->
            val higherHeroLevel = lowerHeroLevel + 1
            val classTallies = HeroClass.entries.associateWith { Tally() }.toMutableMap()
            val matchupTallies = mutableMapOf<Pair<HeroClass, HeroClass>, Tally>()
            val higherLevelTally = Tally()
            HeroClass.entries.forEachIndexed { leftIndex, leftClass ->
                HeroClass.entries.drop(leftIndex + 1).forEach { rightClass ->
                    val matchup = matchupTallies.getOrPut(leftClass to rightClass) { Tally() }
                    ArenaAutoBuildPreset.entries.forEach { leftPreset ->
                        ArenaAutoBuildPreset.entries.forEach { rightPreset ->
                            repeat(ADJACENT_GROWTH_SEEDS) { growthSeed ->
                                repeat(4) { pairing ->
                                    val levelAssignment = pairing / 2
                                    val identityAssignment = pairing % 2
                                    val leftLevel = if (levelAssignment == 0) {
                                        lowerHeroLevel
                                    } else {
                                        higherHeroLevel
                                    }
                                    val rightLevel = if (levelAssignment == 0) {
                                        higherHeroLevel
                                    } else {
                                        lowerHeroLevel
                                    }
                                    val firstId = "adjacent-$lowerHeroLevel-$arenaLevel-" +
                                        "${leftClass.ordinal}-${rightClass.ordinal}-" +
                                        "${leftPreset.ordinal}-${rightPreset.ordinal}-" +
                                        "$growthSeed-$levelAssignment-0"
                                    val secondId = firstId.dropLast(1) + "1"
                                    val leftId = if (identityAssignment == 0) firstId else secondId
                                    val rightId = if (identityAssignment == 0) secondId else firstId
                                    val firstGrowthSeed = growthSeed * 2
                                    val secondGrowthSeed = firstGrowthSeed + 1
                                    val leftBase = fighter(
                                        leftLevel,
                                        arenaLevel,
                                        leftClass,
                                        if (identityAssignment == 0) firstGrowthSeed else secondGrowthSeed,
                                        leftPreset,
                                    )
                                    val rightBase = fighter(
                                        rightLevel,
                                        arenaLevel,
                                        rightClass,
                                        if (identityAssignment == 0) secondGrowthSeed else firstGrowthSeed,
                                        rightPreset,
                                    )
                                    val left = leftBase.copy(
                                        fighter = leftBase.fighter.copy(id = leftId),
                                    )
                                    val right = rightBase.copy(
                                        fighter = rightBase.fighter.copy(id = rightId),
                                    )
                                    val seed = 814_000_000_000L +
                                        lowerHeroLevel * 10_000_000L + arenaLevel * 100_000L +
                                        leftClass.ordinal * 10_000L + rightClass.ordinal * 1_000L +
                                        leftPreset.ordinal * 100L + rightPreset.ordinal * 10L +
                                        growthSeed
                                    val result = ArenaSupportTurnEngine.simulate(
                                        left, right, seed, recordEvents = false,
                                    )
                                    assertEquals(ArenaRunStatus.COMPLETED, result.status)
                                    assertEquals(
                                        result,
                                        ArenaSupportTurnEngine.simulate(
                                            right, left, seed, recordEvents = false,
                                        ),
                                    )
                                    classTallies.getValue(leftClass).record(result, leftId)
                                    classTallies.getValue(rightClass).record(result, rightId)
                                    matchup.record(result, leftId)
                                    higherLevelTally.record(
                                        result,
                                        if (leftLevel == higherHeroLevel) leftId else rightId,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val classRates = classTallies.mapValues { it.value.rate() }
            val matchupRates = matchupTallies.mapValues { it.value.rate() }
            val higherRate = higherLevelTally.rate()
            println(
                "arena-adjacent hero=$lowerHeroLevel/$higherHeroLevel arena=$arenaLevel " +
                    "higher=${fmt(higherRate)} class=${classRates.mapValues { fmt(it.value) }} " +
                    "matchupRange=${fmt(matchupRates.values.min())}..${fmt(matchupRates.values.max())}",
            )
            if (higherRate !in 0.35..0.65) failures +=
                "hero=$lowerHeroLevel/$higherHeroLevel arena=$arenaLevel " +
                    "higher=${fmt(higherRate)} outside 0.35..0.65"
            classRates.forEach { (heroClass, rate) ->
                if (rate !in 0.45..0.55) failures +=
                    "hero=$lowerHeroLevel/$higherHeroLevel arena=$arenaLevel " +
                        "class=$heroClass rate=${fmt(rate)} outside 0.45..0.55"
            }
            matchupRates.forEach { (matchup, rate) ->
                if (rate !in 0.35..0.65) failures +=
                    "hero=$lowerHeroLevel/$higherHeroLevel arena=$arenaLevel " +
                        "matchup=$matchup rate=${fmt(rate)} outside 0.35..0.65"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `random character growth remains balanced across aggregate power quantiles`() {
        val failures = mutableListOf<String>()
        POWER_SCENARIOS.forEach { (heroLevel, arenaLevel) ->
            POWER_SCALE_PERMILLE.forEach { powerPermille ->
                val classTallies = HeroClass.entries.associateWith { Tally() }.toMutableMap()
                val presetTallies = ArenaAutoBuildPreset.entries
                    .associateWith { Tally() }.toMutableMap()
                val classPresetTallies = mutableMapOf<Pair<HeroClass, ArenaAutoBuildPreset>, Tally>()
                val matchupTallies = mutableMapOf<Pair<HeroClass, HeroClass>, Tally>()
                HeroClass.entries.forEachIndexed { leftIndex, leftClass ->
                    HeroClass.entries.drop(leftIndex + 1).forEach { rightClass ->
                        val matchup = matchupTallies.getOrPut(leftClass to rightClass) { Tally() }
                        ArenaAutoBuildPreset.entries.forEach { leftPreset ->
                            repeat(POWER_GROWTH_SEED_PAIRS) { seedPair ->
                                // Ten seed pairs form a complete Latin square, so every authored
                                // family meets every other family once without a 10 x 10 explosion.
                                val rightPreset = ArenaAutoBuildPreset.entries[
                                    (leftPreset.ordinal + seedPair) % ArenaAutoBuildPreset.entries.size
                                ]
                                repeat(2) { assignment ->
                                    val firstGrowthSeed = POWER_GROWTH_SEED_OFFSET + seedPair * 2
                                    val secondGrowthSeed = firstGrowthSeed + 1
                                    val leftSeed = if (assignment == 0) {
                                        firstGrowthSeed
                                    } else {
                                        secondGrowthSeed
                                    }
                                    val rightSeed = if (assignment == 0) {
                                        secondGrowthSeed
                                    } else {
                                        firstGrowthSeed
                                    }
                                    val leftBase = fighter(
                                        heroLevel, arenaLevel, leftClass, leftSeed, leftPreset,
                                        powerPermille,
                                    )
                                    val rightBase = fighter(
                                        heroLevel, arenaLevel, rightClass, rightSeed, rightPreset,
                                        powerPermille,
                                    )
                                    val firstId = "power-$heroLevel-$arenaLevel-$powerPermille-" +
                                        "${leftClass.ordinal}-${rightClass.ordinal}-" +
                                        "${leftPreset.ordinal}-$seedPair-0"
                                    val secondId = firstId.dropLast(1) + "1"
                                    val leftId = if (assignment == 0) firstId else secondId
                                    val rightId = if (assignment == 0) secondId else firstId
                                    val left = leftBase.copy(
                                        fighter = leftBase.fighter.copy(id = leftId),
                                    )
                                    val right = rightBase.copy(
                                        fighter = rightBase.fighter.copy(id = rightId),
                                    )
                                    val battleSeed = 915_000_000_000L + heroLevel * 10_000_000L +
                                        arenaLevel * 100_000L + powerPermille * 100L +
                                        leftClass.ordinal * 10_000L + rightClass.ordinal * 1_000L +
                                        leftPreset.ordinal * 10L + seedPair
                                    val result = ArenaSupportTurnEngine.simulate(
                                        left, right, battleSeed, recordEvents = false,
                                    )
                                    assertEquals(ArenaRunStatus.COMPLETED, result.status)
                                    assertEquals(
                                        result,
                                        ArenaSupportTurnEngine.simulate(
                                            right, left, battleSeed, recordEvents = false,
                                        ),
                                    )
                                    classTallies.getValue(leftClass).record(result, leftId)
                                    classTallies.getValue(rightClass).record(result, rightId)
                                    presetTallies.getValue(leftPreset).record(result, leftId)
                                    presetTallies.getValue(rightPreset).record(result, rightId)
                                    classPresetTallies.getOrPut(leftClass to leftPreset) { Tally() }
                                        .record(result, leftId)
                                    classPresetTallies.getOrPut(rightClass to rightPreset) { Tally() }
                                        .record(result, rightId)
                                    matchup.record(result, leftId)
                                }
                            }
                        }
                    }
                }
                val classRates = classTallies.mapValues { it.value.rate() }
                val presetRates = presetTallies.mapValues { it.value.rate() }
                val classPresetRates = classPresetTallies.mapValues { it.value.rate() }
                val matchupRates = matchupTallies.mapValues { it.value.rate() }
                println(
                    "arena-power hero=$heroLevel arena=$arenaLevel scale=$powerPermille " +
                        "class=${classRates.mapValues { fmt(it.value) }} " +
                        "preset=${presetRates.mapValues { fmt(it.value) }} " +
                        "classPresetRange=${fmt(classPresetRates.values.min())}.." +
                        "${fmt(classPresetRates.values.max())} " +
                        "matchupRange=${fmt(matchupRates.values.min())}.." +
                        fmt(matchupRates.values.max()),
                )
                collectBandFailures(
                    failures, "hero=$heroLevel arena=$arenaLevel scale=$powerPermille class",
                    classRates, 0.45..0.55,
                )
                collectBandFailures(
                    failures, "hero=$heroLevel arena=$arenaLevel scale=$powerPermille preset",
                    presetRates, 0.40..0.60,
                )
                collectBandFailures(
                    failures, "hero=$heroLevel arena=$arenaLevel scale=$powerPermille classPreset",
                    classPresetRates, 0.35..0.65,
                )
                collectBandFailures(
                    failures, "hero=$heroLevel arena=$arenaLevel scale=$powerPermille matchup",
                    matchupRates, 0.35..0.65,
                )
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `random raw stats keep local public power parity and monotonic combat value`() {
        POWER_PROFILE_LEVELS.forEach { heroLevel ->
            val displayPowers = mutableListOf<Long>()
            HeroClass.entries.forEach { heroClass ->
                repeat(POWER_PROFILE_SEEDS) { profileSeed ->
                    val state = grownState(heroLevel, heroClass, POWER_PROFILE_SEED_OFFSET + profileSeed)
                    val expectedEquipmentPower = engine.expectedEquipmentCombatPower(heroLevel.toLong())
                    state.equipment.forEach { it.power = expectedEquipmentPower }
                    displayPowers += engine.displayCombatPower(state)

                    var previous: ArenaCoreStats? = null
                    POWER_SCALE_PERMILLE.forEach { powerPermille ->
                        val combatPower = combatPower(heroLevel, powerPermille)
                        val publicStats = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
                            heroClass = heroClass,
                            level = heroLevel.toLong(),
                            combatPower = combatPower,
                            rawStats = state.hero.stats,
                        ))
                        val expected = ArenaCoreStats(
                            strength = publicStats.strength.toDouble(),
                            constitution = publicStats.constitution.toDouble(),
                            dexterity = publicStats.dexterity.toDouble(),
                            intelligence = publicStats.intelligence.toDouble(),
                            wisdom = publicStats.wisdom.toDouble(),
                            charisma = publicStats.charisma.toDouble(),
                            rawMaxHealth = publicStats.maxHealth.toDouble(),
                            rawMaxMana = publicStats.maxMana.toDouble(),
                        )
                        val local = requireNotNull(ArenaTurnInputAdapter.fromStateForCombatPower(
                            state = state,
                            id = "power-parity-$heroLevel-${heroClass.ordinal}-$profileSeed",
                            effectiveCombatPower = combatPower,
                        )).fighter.stats
                        assertEquals(
                            "local/public stats diverged at level=$heroLevel class=$heroClass " +
                                "seed=$profileSeed scale=$powerPermille",
                            expected,
                            local,
                        )
                        previous?.let { lower ->
                            assertTrue(
                                "power scaling regressed a stat at level=$heroLevel class=$heroClass " +
                                    "seed=$profileSeed scale=$powerPermille lower=${lower.values()} " +
                                    "actual=${expected.values()}",
                                lower.values().zip(expected.values()).all { (low, high) -> low <= high },
                            )
                        }
                        previous = expected
                    }
                }
            }
            val sorted = displayPowers.sorted()
            println(
                "arena-random-profile level=$heroLevel count=${sorted.size} " +
                    "displayPowerP10=${percentile(sorted, 0.10)} " +
                    "displayPowerP50=${percentile(sorted, 0.50)} " +
                    "displayPowerP90=${percentile(sorted, 0.90)}",
            )
        }

        val failures = mutableListOf<String>()
        POWER_SCENARIOS.forEach { (heroLevel, arenaLevel) ->
            val scaleTallies = POWER_SCALE_PERMILLE.associateWith { Tally() }.toMutableMap()
            HeroClass.entries.forEach { heroClass ->
                ArenaAutoBuildPreset.entries.forEach { preset ->
                    repeat(POWER_GROWTH_SEED_PAIRS) { seedPair ->
                        val firstSeed = POWER_MONOTONIC_SEED_OFFSET + seedPair * 2
                        val secondSeed = firstSeed + 1
                        POWER_SCALE_PERMILLE.forEach { powerPermille ->
                            val firstId = "monotonic-$heroLevel-$arenaLevel-${heroClass.ordinal}-" +
                                "${preset.ordinal}-$seedPair-first"
                            val secondId = firstId.removeSuffix("first") + "second"
                            val firstCandidateBase = fighter(
                                heroLevel, arenaLevel, heroClass, firstSeed, preset, powerPermille,
                            )
                            val secondCandidateBase = fighter(
                                heroLevel, arenaLevel, heroClass, secondSeed, preset, powerPermille,
                            )
                            val firstReferenceBase = fighter(
                                heroLevel, arenaLevel, heroClass, firstSeed, preset,
                            )
                            val secondReferenceBase = fighter(
                                heroLevel, arenaLevel, heroClass, secondSeed, preset,
                            )
                            val firstCandidate = firstCandidateBase.copy(
                                fighter = firstCandidateBase.fighter.copy(id = firstId),
                            )
                            val secondCandidate = secondCandidateBase.copy(
                                fighter = secondCandidateBase.fighter.copy(id = secondId),
                            )
                            val firstReference = firstReferenceBase.copy(
                                fighter = firstReferenceBase.fighter.copy(id = firstId),
                            )
                            val secondReference = secondReferenceBase.copy(
                                fighter = secondReferenceBase.fighter.copy(id = secondId),
                            )
                            val battleSeed = 946_000_000_000L + heroLevel * 10_000_000L +
                                arenaLevel * 100_000L + heroClass.ordinal * 10_000L +
                                preset.ordinal * 100L + seedPair
                            val firstResult = ArenaSupportTurnEngine.simulate(
                                firstCandidate, secondReference, battleSeed, recordEvents = false,
                            )
                            val secondResult = ArenaSupportTurnEngine.simulate(
                                secondCandidate, firstReference, battleSeed, recordEvents = false,
                            )
                            assertEquals(ArenaRunStatus.COMPLETED, firstResult.status)
                            assertEquals(ArenaRunStatus.COMPLETED, secondResult.status)
                            assertEquals(
                                firstResult,
                                ArenaSupportTurnEngine.simulate(
                                    secondReference,
                                    firstCandidate,
                                    battleSeed,
                                    recordEvents = false,
                                ),
                            )
                            assertEquals(
                                secondResult,
                                ArenaSupportTurnEngine.simulate(
                                    firstReference,
                                    secondCandidate,
                                    battleSeed,
                                    recordEvents = false,
                                ),
                            )
                            scaleTallies.getValue(powerPermille).record(firstResult, firstId)
                            scaleTallies.getValue(powerPermille).record(secondResult, secondId)
                        }
                    }
                }
            }
            val rates = scaleTallies.mapValues { it.value.rate() }
            println(
                "arena-power-monotonic hero=$heroLevel arena=$arenaLevel " +
                    "rates=${rates.mapValues { fmt(it.value) }}",
            )
            val low = rates.getValue(800)
            val middle = rates.getValue(1_000)
            val high = rates.getValue(1_200)
            if (!(low < middle && middle < high)) failures +=
                "hero=$heroLevel arena=$arenaLevel expected low < middle < high but was " +
                    "${fmt(low)} < ${fmt(middle)} < ${fmt(high)}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun fighter(
        heroLevel: Int,
        arenaLevel: Int,
        heroClass: HeroClass,
        growthSeed: Int,
        preset: ArenaAutoBuildPreset,
        powerScalePermille: Int = 1_000,
    ): ArenaSupportInput = fighterCache.getOrPut(
        FighterKey(heroLevel, arenaLevel, heroClass, growthSeed, preset, powerScalePermille),
    ) {
        val state = grownState(heroLevel, heroClass, growthSeed)
        val power = combatPower(heroLevel, powerScalePermille)
        val stats = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
            heroClass, heroLevel.toLong(), power, state.hero.stats,
        ))
        requireNotNull(ArenaV6OpponentInputFactory.create(
            projectionId = "cross-$heroClass-$heroLevel-$arenaLevel-$growthSeed-${preset.stableId}",
            displayName = "cross",
            heroClass = heroClass,
            level = heroLevel.toLong(),
            combatPower = power,
            stats = stats,
            learnedSkills = PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(
                heroClass, heroLevel.toLong(),
            ),
            arenaLevel = arenaLevel,
            stableSeed = 739_000_000L + growthSeed * 100L + preset.ordinal,
            buildPreset = preset,
        )).combat
    }

    private fun grownState(
        heroLevel: Int,
        heroClass: HeroClass,
        growthSeed: Int,
    ): SimpleGameState {
        val roll = engine.rollStats(
            74_000_000L + growthSeed.toLong() * CHARACTER_SEED_STRIDE,
            heroClass,
        )
        val state = engine.newGame("cross", heroClass, roll.stats.copy(), roll.nextSeed, 1_000L)
        state.rngState = ArenaSyntheticProfileGrowth.grow(
            engine = engine,
            stats = state.hero.stats,
            heroClass = heroClass,
            targetLevel = heroLevel.toLong(),
            identitySeed = state.rngState,
        )
        state.hero.level = heroLevel.toLong()
        state.classGuidedLevelGrowths = (heroLevel - 1).toLong()
        return state
    }

    private fun combatPower(heroLevel: Int, powerScalePermille: Int): Long {
        require(powerScalePermille in 800..1_200)
        val averagePower = (1L + (heroLevel - 1L) * 5L) * 2L
        return (averagePower * powerScalePermille + 500L) / 1_000L
    }

    private fun <K> collectBandFailures(
        failures: MutableList<String>,
        label: String,
        rates: Map<K, Double>,
        band: ClosedFloatingPointRange<Double>,
    ) {
        rates.forEach { (key, rate) ->
            if (rate !in band) failures += "$label $key=${fmt(rate)} outside $band"
        }
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        require(sorted.isNotEmpty() && fraction in 0.0..1.0)
        val index = ((sorted.lastIndex * fraction) + 0.5).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private data class Tally(var wins: Int = 0, var losses: Int = 0, var draws: Int = 0) {
        private val count get() = wins + losses + draws
        fun rate(): Double = (wins + draws * 0.5) / count
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
        val HERO_LEVELS = listOf(10, 20, 25, 30, 50, 100)
        val ARENA_LEVELS = listOf(1, 10, 20, 25, 30, 50, 100)
        val ADJACENT_SCENARIOS = listOf(
            10 to 1,
            19 to 10,
            19 to 20,
            24 to 20,
            24 to 25,
            29 to 25,
            29 to 30,
            49 to 30,
            49 to 50,
            99 to 1,
            99 to 10,
            99 to 50,
            99 to 99,
            99 to 100,
        )
        const val GROWTH_SEEDS = 32
        const val ADJACENT_GROWTH_SEEDS = 16
        const val CHARACTER_SEED_STRIDE = 104_729L
        val POWER_SCENARIOS = listOf(
            20 to 20,
            25 to 25,
            30 to 30,
            100 to 1,
            100 to 10,
            100 to 30,
        )
        val POWER_PROFILE_LEVELS = listOf(20, 25, 30, 100)
        val POWER_SCALE_PERMILLE = listOf(800, 1_000, 1_200)
        // Keep the original cohort and add independent growth samples. The win-rate
        // bands are unchanged; a 100-duel class/preset cell is too noisy for tuning.
        const val POWER_GROWTH_SEED_PAIRS = 40
        const val POWER_PROFILE_SEEDS = 16
        const val POWER_GROWTH_SEED_OFFSET = 1_000
        const val POWER_PROFILE_SEED_OFFSET = 2_000
        const val POWER_MONOTONIC_SEED_OFFSET = 3_000
    }
}
