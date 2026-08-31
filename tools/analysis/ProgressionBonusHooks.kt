package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.SimpleGameState
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Standalone analysis copy only. No Android application references this file. */
object BalanceSimulationHooks {
    var enabled = false
    private var mode = "baseline"
    private var mageThreshold = 8000.0
    var attacks = 0L
    var casts = 0L
    fun resetCounters() { attacks = 0L; casts = 0L }
    fun configure(csv: String) {
        val args = csv.split(',')
        mode = args.first()
        check(mode in listOf("baseline", "stat_power", "level_ramp", "level_envelope"))
        mageThreshold = args.getOrNull(1)?.toDouble() ?: 8000.0
        check(mageThreshold > 0)
        enabled = true
    }
    private fun fraction(value: Long, threshold: Double) = (value.toDouble() / threshold).coerceIn(0.0, 1.0)
    private fun progress(state: SimpleGameState) = ((state.hero.level.coerceAtLeast(1) - 1).toDouble() / 99).coerceIn(0.0, 1.0)
    private fun ramp(state: SimpleGameState) = .4 + .6 * progress(state).pow(1.3)
    fun offlineHours(state: SimpleGameState): Double {
        val r = fraction(state.hero.stats.maxHealth, 6000.0)
        val bonus = when (mode) {
            "stat_power" -> r.pow(1.15)
            "level_ramp" -> r.pow(.8) * ramp(state)
            "level_envelope" -> minOf(r.pow(.8), .02 + .98 * progress(state).pow(1.8))
            else -> r.pow(.8)
        }
        return 8 + 2 * bonus
    }
    fun rawProbability(state: SimpleGameState, basePercent: Int): Double {
        val mage = state.hero.heroClass == HeroClass.MAGE
        val extra = if (mage) 15.0 else 10.0
        val threshold = if (mage && mode != "baseline") mageThreshold else 6000.0
        var r = fraction(state.hero.stats.maxMana, threshold)
        if ((mage && mode != "baseline") || mode == "stat_power") r = r.pow(1.15)
        if (mode == "level_ramp") r *= ramp(state)
        // Preserve the existing engine's floating-point evaluation order before
        // basis-point rounding, including exact half-boundary behavior.
        return (basePercent / 100.0 + extra / 100.0 * r).coerceIn(0.0, (20 + extra) / 100)
    }
    fun proc(state: SimpleGameState, roll: Int, basePercent: Int) =
        roll < (rawProbability(state, basePercent) * 10000).roundToInt()
    private fun utilityRatio(state: SimpleGameState, value: Long): Double {
        val r = fraction(value, 150.0)
        return when (mode) {
            "stat_power" -> r.pow(1.35)
            "level_ramp" -> r * ramp(state)
            "level_envelope" -> minOf(r, .12 + .88 * progress(state).pow(1.4))
            else -> r
        }
    }
    fun encounterMillis(state: SimpleGameState, baseMillis: Long): Long =
        baseMillis - (1000 * utilityRatio(state, state.hero.stats.dexterity)).roundToLong()
    fun saleBonus(state: SimpleGameState): Double = .2 * utilityRatio(state, state.hero.stats.charisma)
    fun sale(state: SimpleGameState, value: Long) = (value * (1 + saleBonus(state))).toLong()
}
