package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.SimpleGameState
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pure, shared rules. HP/MP growth, class aptitude and STR inventory are independent. */
object StatBonusRules {
    const val MAX_ADVENTURE_BONUS_RATIO = 0.25
    private const val SALE_SCALE = 1_000_000_000L

    private fun progress(state: SimpleGameState): Double =
        ((state.hero.level.coerceAtLeast(1L) - 1L).toDouble() / 99.0).coerceIn(0.0, 1.0)

    private fun statFraction(value: Long): Double = (value.toDouble() / 150.0).coerceIn(0.0, 1.0)

    private fun utilityRatio(state: SimpleGameState, value: Long): Double =
        minOf(statFraction(value), 0.12 + 0.88 * progress(state).pow(1.4))

    fun adventureBonusMillis(state: SimpleGameState, baseCapacityMillis: Long): Long =
        (baseCapacityMillis.coerceAtLeast(0L) * MAX_ADVENTURE_BONUS_RATIO * statFraction(state.hero.stats.constitution).pow(1.2) *
            (0.15 + 0.85 * progress(state).pow(1.2))).roundToLong()

    /** One basis point is 0.01 percentage point. The pity guarantee is separate. */
    fun skillProcBasisPoints(state: SimpleGameState, basePercent: Int): Int {
        val stats = state.hero.stats
        val mental = stats.intelligence.coerceAtLeast(0L).toDouble() * 0.5 +
            stats.wisdom.coerceAtLeast(0L).toDouble() * 0.5
        val bonus = 1_500.0 * (mental / 180.0).coerceIn(0.0, 1.0).pow(1.2)
        val cap = if (state.hero.heroClass == HeroClass.MAGE) 3_500 else 3_000
        return (basePercent.coerceIn(0, 100) * 100 + bonus).roundToInt().coerceIn(0, cap)
    }

    fun encounterSearchMillis(state: SimpleGameState): Long =
        SimpleGameEngine.ENCOUNTER_SEARCH_MILLIS -
            (1_500.0 * utilityRatio(state, state.hero.stats.dexterity)).roundToLong()

    fun encounterRevealMillis(state: SimpleGameState): Long =
        encounterSearchMillis(state) + SimpleGameEngine.ENCOUNTER_DISCOVERY_MILLIS

    private fun saleRateUnits(state: SimpleGameState): Long =
        (150_000_000.0 * utilityRatio(state, state.hero.stats.charisma)).roundToLong()

    fun saleBonusPercent(state: SimpleGameState): Double = saleRateUnits(state) / 10_000_000.0

    /** Integer floor with overflow saturation; e.g. 100 gold +15% is exactly 115. */
    fun saleValue(state: SimpleGameState, baseValue: Long): Long {
        val base = baseValue.coerceAtLeast(0L)
        val units = saleRateUnits(state)
        val bonus = base / SALE_SCALE * units + base % SALE_SCALE * units / SALE_SCALE
        return if (base > Long.MAX_VALUE - bonus) Long.MAX_VALUE else base + bonus
    }
}
