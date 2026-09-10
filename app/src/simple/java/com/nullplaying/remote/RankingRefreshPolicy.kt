package com.nullplaying.remote

/**
 * Minimum cadence of published leaderboard editions observed by one client.
 *
 * The server remains authoritative for the actual UTC publication boundary. Remote Config may
 * only make a client refresh less often; it cannot force reads faster than the protected server
 * cadence.
 */
data class RankingRefreshPolicy(
    val intervalHours: Long = DEFAULT_INTERVAL_HOURS,
) {
    init {
        require(intervalHours in MIN_INTERVAL_HOURS..MAX_INTERVAL_HOURS)
    }

    val intervalMillis: Long
        get() = intervalHours * 60L * 60L * 1_000L

    companion object {
        const val INTERVAL_HOURS_KEY = "ranking_refresh_hours"
        const val DEFAULT_INTERVAL_HOURS = 1L
        const val MIN_INTERVAL_HOURS = 1L
        const val MAX_INTERVAL_HOURS = 24L

        fun parse(rawHours: String): RankingRefreshPolicy? {
            val hours = rawHours.trim().toLongOrNull() ?: return null
            return hours.takeIf { it in MIN_INTERVAL_HOURS..MAX_INTERVAL_HOURS }
                ?.let(::RankingRefreshPolicy)
        }
    }
}

internal fun earliestRankingRefreshAt(
    settledAtEpochMillis: Long,
    serverNextSettlementAtEpochMillis: Long,
    policy: RankingRefreshPolicy,
): Long {
    // Align the client cadence to the edition cutoff. A device that downloads just before the next
    // cutoff must still observe that cutoff on time, and an unchanged response must retain its
    // one-minute publication retry instead of starting another full policy interval.
    val clientMinimum = saturatingAddRankingTime(settledAtEpochMillis, policy.intervalMillis)
    return maxOf(serverNextSettlementAtEpochMillis, clientMinimum)
}

internal fun saturatingAddRankingTime(left: Long, right: Long): Long = when {
    right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
    else -> left + right
}

internal fun saturatingSubtractRankingTime(left: Long, right: Long): Long = when {
    right > 0L && left < Long.MIN_VALUE + right -> Long.MIN_VALUE
    right < 0L && left > Long.MAX_VALUE + right -> Long.MAX_VALUE
    else -> left - right
}
