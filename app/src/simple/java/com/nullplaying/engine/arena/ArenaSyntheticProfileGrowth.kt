package com.nullplaying.engine.arena

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats

/**
 * Deterministic level growth for disposable arena projections.
 *
 * Real saved heroes keep using the shared gameplay RNG. Synthetic profiles instead receive an
 * independently mixed seed for every absolute hero level. Reusing the four-call LCG continuation
 * from one growth step as the next step's input can lock the primary/secondary choice to one side,
 * which creates impossible-looking opponents after many levels.
 */
internal object ArenaSyntheticProfileGrowth {
    fun grow(
        engine: SimpleGameEngine,
        stats: HeroStats,
        heroClass: HeroClass,
        fromLevel: Long = 1L,
        targetLevel: Long,
        identitySeed: Long,
    ): Long {
        require(fromLevel >= 1L) { "Synthetic growth must start at level 1 or later" }
        require(targetLevel >= fromLevel) { "Synthetic target level must not go backwards" }
        if (targetLevel == fromLevel) return identitySeed

        var level = fromLevel + 1L
        var finalStepState = mixedSeed(identitySeed, heroClass, fromLevel)
        while (level <= targetLevel) {
            finalStepState = engine.applyClassGuidedGrowth(
                stats = stats,
                heroClass = heroClass,
                rngSeed = mixedSeed(identitySeed, heroClass, level),
            )
            level++
        }
        // A projected copy occasionally needs a stable continuation token. It is deliberately
        // domain-separated from every growth-step seed and is never written to a real hero save.
        return mix64(finalStepState xor identitySeed xor targetLevel xor CONTINUATION_DOMAIN)
    }

    private fun mixedSeed(identitySeed: Long, heroClass: HeroClass, absoluteLevel: Long): Long =
        mix64(
            identitySeed xor GROWTH_DOMAIN xor
                (heroClass.ordinal.toLong() * CLASS_DOMAIN) xor
                (absoluteLevel * LEVEL_DOMAIN),
        )

    private fun mix64(input: Long): Long {
        var value = input + GOLDEN_GAMMA
        value = (value xor (value ushr 30)) * MIX_MULTIPLIER_ONE
        value = (value xor (value ushr 27)) * MIX_MULTIPLIER_TWO
        return value xor (value ushr 31)
    }

    private const val GOLDEN_GAMMA = -7046029254386353131L
    private const val MIX_MULTIPLIER_ONE = -4658895280553007687L
    private const val MIX_MULTIPLIER_TWO = -7723592293110705685L
    private const val GROWTH_DOMAIN = 0x27D4_EB2F_1656_67C5L
    private const val CLASS_DOMAIN = 0x1656_67B1_9E37_79F9L
    private const val LEVEL_DOMAIN = -4417276706812531889L
    private const val CONTINUATION_DOMAIN = 0x4F1B_BCDC_6762_0A5DL
}
