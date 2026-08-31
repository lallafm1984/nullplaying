package com.nullplaying.remote

internal const val MAX_SUPPORTED_RANKING_LEVEL = 10_000L

internal fun maximumAcceptedRankingCombatPower(level: Long): Long {
    if (level !in 1L..MAX_SUPPORTED_RANKING_LEVEL) return 0L

    val equipmentBenchmark = 1L + (level - 1L) * 5L
    val maximumShopPower = equipmentBenchmark + 13L
    val mythicSourcePower = (equipmentBenchmark - 10L).coerceAtLeast(0L) + 30L
    val guaranteedMythicPower = ceilingPercent(maximumShopPower, 105L)
    val maximumEquipmentPower = maxOf(mythicSourcePower, guaranteedMythicPower) + 11L
    val maximumStatPower = roundedPercent(equipmentBenchmark, 135L)
    return maximumStatPower + maximumEquipmentPower
}

private fun ceilingPercent(value: Long, percent: Long): Long =
    (value * percent + 99L) / 100L

private fun roundedPercent(value: Long, percent: Long): Long =
    (value * percent + 50L) / 100L

