package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass

/**
 * Small arena-only offense normalization for class growth curves.
 *
 * This is fixed by class, hero growth, and Arena progression. The lower level selects the main
 * calibration band; small continuous maturity weights handle old high-level heroes starting fresh
 * Arena progression and the lower side of the Lv.19/20 matching boundary. This changes only the
 * Arena coefficient: the fighter's real stats and learned attacks remain intact. It never reads the
 * opponent, build preset, battle seed, outcome, or server refresh state. Linear interpolation
 * avoids hidden single-level exceptions.
 */
internal object ArenaClassBalance {
    fun offenseMultiplier(heroClass: HeroClass, heroLevel: Long, arenaLevel: Int): Double {
        require(heroLevel >= 1)
        require(arenaLevel >= 1)
        val effectiveLevel = minOf(heroLevel, arenaLevel.toLong())
        val base = baseOffenseMultiplier(heroClass, effectiveLevel)
        val arenaAdjustment = when (heroClass) {
            HeroClass.WARRIOR -> interpolate(
                arenaLevel.toLong(),
                listOf(
                    1L to 0.000,
                    10L to 0.004,
                    20L to -0.004,
                    25L to 0.000,
                    30L to -0.00425,
                    35L to 0.000,
                ),
            )
            HeroClass.MAGE -> interpolate(
                arenaLevel.toLong(),
                listOf(
                    1L to 0.004,
                    10L to -0.004,
                    20L to 0.004,
                    25L to 0.000,
                    30L to 0.000,
                ),
            )
            HeroClass.CLERIC -> interpolate(
                arenaLevel.toLong(),
                // The bounded transition is multiplied by heroAheadWeight, so equal-level play
                // keeps the measured base curve and Arena Lv.30+ receives no extra correction.
                listOf(
                    1L to 0.007,
                    10L to 0.002,
                    20L to 0.037,
                    25L to 0.030,
                    30L to 0.000,
                ),
            )
            HeroClass.PALADIN -> interpolate(
                arenaLevel.toLong(),
                listOf(1L to 0.000, 10L to -0.003, 20L to -0.010, 30L to 0.000),
            )
            HeroClass.ROGUE -> interpolate(
                arenaLevel.toLong(),
                listOf(
                    1L to -0.004,
                    10L to -0.010,
                    20L to -0.010,
                    25L to 0.000,
                    30L to 0.000,
                ),
            )
            HeroClass.RANGER -> interpolate(
                arenaLevel.toLong(),
                // Early Arena builds need a small reduction that disappears at Arena Lv.30.
                listOf(1L to -0.004, 10L to 0.001, 20L to -0.006, 25L to -0.020, 30L to 0.000),
            )
        }
        return base + arenaAdjustment * heroAheadWeight(heroLevel, arenaLevel) +
            maturityAdjustment(heroClass, heroLevel, arenaLevel) +
            heroBehindAdjustment(heroClass, heroLevel, arenaLevel)
    }

    internal fun baseOffenseMultiplier(heroClass: HeroClass, effectiveLevel: Long): Double {
        require(effectiveLevel >= 1)
        return when (heroClass) {
            HeroClass.WARRIOR -> interpolate(
                effectiveLevel,
                listOf(
                    1L to 1.000,
                    20L to 1.0025,
                    25L to 1.000,
                    30L to 1.0045,
                    50L to 1.000,
                    100L to 1.100,
                ),
            )
            HeroClass.MAGE -> interpolate(
                effectiveLevel,
                // Hero Lv.25 unlocks the first middle-branch attack. The small crest offsets that
                // growth-band transition, then returns to the long-run normalization at Lv.30.
                listOf(
                    1L to 1.000,
                    10L to 1.000,
                    20L to 0.993,
                    25L to 1.009,
                    30L to 0.995,
                    50L to 1.030,
                    100L to 1.010,
                ),
            )
            HeroClass.CLERIC -> interpolate(
                effectiveLevel,
                listOf(
                    1L to 1.000,
                    10L to 1.000,
                    20L to 0.976,
                    25L to 1.002,
                    30L to 1.000,
                    50L to 1.020,
                    100L to 1.000,
                ),
            )
            HeroClass.PALADIN -> interpolate(
                effectiveLevel,
                listOf(
                    1L to 1.000,
                    10L to 1.000,
                    20L to 1.010,
                    25L to 1.020,
                    30L to 1.043,
                    50L to 1.018,
                    100L to 1.000,
                ),
            )
            HeroClass.ROGUE -> interpolate(
                effectiveLevel,
                listOf(
                    1L to 1.000,
                    10L to 1.000,
                    20L to 0.993,
                    25L to 0.984,
                    30L to 0.960,
                    50L to 0.990,
                    100L to 1.000,
                ),
            )
            HeroClass.RANGER -> interpolate(
                effectiveLevel,
                listOf(
                    1L to 1.000,
                    20L to 1.000,
                    25L to 0.985,
                    30L to 0.979,
                    50L to 0.995,
                    100L to 0.895,
                ),
            )
        }
    }

    /** Zero on the equal axis and approaches one smoothly after a short hero-level lead. */
    private fun heroAheadWeight(heroLevel: Long, arenaLevel: Int): Double {
        val gap = (heroLevel - arenaLevel).coerceAtLeast(0L).toDouble()
        return if (gap == 0.0) 0.0 else gap / (gap + HERO_AHEAD_BLEND_LEVELS)
    }

    /**
     * A narrow maturity bridge for the few early-Arena bands where the same class changes sides as
     * the real hero grows. Both fighters use this same continuous surface; it does not inspect a
     * build, opponent, seed, or outcome. The Arena weights fade to zero outside the measured early
     * bands, leaving the main effective-level curve authoritative elsewhere.
     */
    private fun maturityAdjustment(
        heroClass: HeroClass,
        heroLevel: Long,
        arenaLevel: Int,
    ): Double {
        if (heroLevel <= arenaLevel) return 0.0
        val arenaOneWeight = interpolate(
            arenaLevel.toLong(),
            listOf(1L to 1.0, 5L to 0.0),
        )
        val arenaTenWeight = interpolate(
            arenaLevel.toLong(),
            listOf(1L to 0.0, 10L to 1.0, 20L to 0.0),
        )
        return when (heroClass) {
            HeroClass.WARRIOR -> arenaOneWeight * interpolate(
                heroLevel,
                listOf(1L to 0.0, 10L to -0.003, 20L to 0.0),
            )
            HeroClass.ROGUE ->
                arenaOneWeight * interpolate(
                    heroLevel,
                    listOf(
                        1L to 0.0,
                        10L to -0.004,
                        20L to 0.0,
                        50L to 0.0,
                        100L to 0.004,
                    ),
                ) + arenaTenWeight * interpolate(
                    heroLevel,
                    // The Lv.19/20 release boundary needs a short bridge; fading it by Lv.25
                    // keeps mature heroes on the shared Arena Lv.10 curve.
                    listOf(1L to 0.0, 10L to 0.0, 20L to -0.007, 25L to 0.0),
                )
            HeroClass.PALADIN -> arenaTenWeight * interpolate(
                heroLevel,
                listOf(1L to 0.0, 30L to 0.0, 50L to -0.005, 100L to 0.0),
            )
            else -> 0.0
        }
    }

    /**
     * Smoothly offsets the lower side of the Lv.19/20 matchmaking boundary without changing the
     * equal-level Rogue profile used by authored behavior. The correction is zero at equal hero
     * and Arena levels and fades out of the Arena Lv.20 band, so it cannot become a hidden preset
     * or single-opponent exception.
     */
    private fun heroBehindAdjustment(
        heroClass: HeroClass,
        heroLevel: Long,
        arenaLevel: Int,
    ): Double {
        if (heroClass != HeroClass.ROGUE || heroLevel >= arenaLevel) return 0.0
        val arenaBoundaryAdjustment = interpolate(
            arenaLevel.toLong(),
            listOf(1L to 0.0, 10L to 0.0, 20L to -0.010, 25L to 0.0, 30L to 0.0),
        )
        val gap = (arenaLevel.toLong() - heroLevel).toDouble()
        return arenaBoundaryAdjustment * gap / (gap + HERO_AHEAD_BLEND_LEVELS)
    }

    private fun interpolate(level: Long, points: List<Pair<Long, Double>>): Double {
        require(level >= 1)
        val upperIndex = points.indexOfFirst { (pointLevel, _) -> level <= pointLevel }
        if (upperIndex < 0) return points.last().second
        if (upperIndex == 0) return points.first().second
        val (lowerLevel, lowerValue) = points[upperIndex - 1]
        val (upperLevel, upperValue) = points[upperIndex]
        val fraction = (level - lowerLevel).toDouble() / (upperLevel - lowerLevel).toDouble()
        return lowerValue + (upperValue - lowerValue) * fraction
    }

    private const val HERO_AHEAD_BLEND_LEVELS = 5.0
    internal const val MIN_OFFENSE_MULTIPLIER = 0.895
    internal const val MAX_OFFENSE_MULTIPLIER = 1.100
    internal const val MAX_PROFILE_ADJUSTMENT = 0.037
}
