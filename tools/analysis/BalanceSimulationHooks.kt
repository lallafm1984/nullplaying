package com.nullplaying.engine

import com.nullplaying.model.SimpleGameState
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Analysis jar only. Never compiled into the Android application. */
object BalanceSimulationHooks {
    var enabled = false
    private var p = DoubleArray(8)
    private var cappedLinear = false
    var attacks = 0L
    var casts = 0L
    fun resetCounters() { attacks = 0L; casts = 0L }
    fun configure(csv: String) {
        enabled = csv.isNotBlank()
        if (enabled) p = csv.split(',').map(String::toDouble).toDoubleArray()
        check(!enabled || p.size == 8 || p.size == 9)
        cappedLinear = enabled && p.size == 9 && p[8] == 1.0
    }
    private fun fraction(value: Double, threshold: Double): Double =
        if (cappedLinear) (value / threshold).coerceIn(0.0, 1.0)
        else value / (value + threshold)
    fun rawProbability(state: SimpleGameState, basePercent: Int): Double {
        val mp = state.hero.stats.maxMana.toDouble()
        return (basePercent / 100.0 + if (enabled) p[2] / 100 * fraction(mp, p[3]) else 0.0)
            .coerceIn(0.0, if (enabled) (20.0 + p[2]) / 100.0 else .20)
    }
    fun proc(state: SimpleGameState, roll: Int, basePercent: Int): Boolean =
        if (enabled) roll < (rawProbability(state, basePercent) * 10000).roundToInt()
        else roll.coerceIn(0,99) < basePercent
    fun encounterMillis(state: SimpleGameState, baseMillis: Long): Long {
        if (!enabled) return baseMillis
        val dex = state.hero.stats.dexterity.toDouble()
        return baseMillis - (5000 * p[4] * fraction(dex, p[5])).roundToLong()
    }
    fun sale(state: SimpleGameState, value: Long): Long {
        if (!enabled) return value
        val cha = state.hero.stats.charisma.toDouble()
        return (value * (1 + p[6] * fraction(cha, p[7]))).toLong()
    }
}
