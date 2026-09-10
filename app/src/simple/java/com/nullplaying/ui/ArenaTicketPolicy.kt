package com.nullplaying.ui

import com.nullplaying.model.BATTLE_TICKET_CAPACITY
import com.nullplaying.model.BATTLE_DAILY_MATCH_LIMIT

internal const val BATTLE_ENTRY_CAPACITY: Int = BATTLE_TICKET_CAPACITY
internal const val BATTLE_ENTRY_DAILY_LIMIT: Int = BATTLE_DAILY_MATCH_LIMIT
internal const val BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS: Long = 10L * 60L * 1_000L
internal const val BATTLE_ENTRY_RECOVERY_MILLIS: Long =
    BATTLE_ENTRY_CAPACITY * BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS
internal const val BATTLE_ENTRY_RECOVERY_POLICY_VERSION: Int = 6
internal const val BATTLE_REWARDED_REFILL_DAILY_LIMIT: Int = 1
internal const val ARENA_TICKET_PERSIST_RETRY_MILLIS: Long = 30_000L

internal data class BattleEntryRecoveryState(
    val remaining: Int,
    /** Start of the current ten-minute interval. An ad refill does not move this anchor. */
    val recoveryStartedAtMillis: Long,
) {
    init {
        require(remaining in 0..BATTLE_ENTRY_CAPACITY)
        require(recoveryStartedAtMillis >= 0L)
    }
}

internal data class BattleEntrySpend(
    val accepted: Boolean,
    val state: BattleEntryRecoveryState,
)

/**
 * Restores one entry per completed ten-minute interval, including time spent outside the app.
 * Five empty slots therefore take fifty minutes. A rewarded refill can set the wallet to full
 * without changing the in-progress natural-recovery anchor.
 */
internal fun recoverBattleEntries(
    remaining: Int,
    recoveryStartedAtMillis: Long,
    nowMillis: Long,
): BattleEntryRecoveryState {
    require(nowMillis >= 0L)
    val safeRemaining = remaining.coerceIn(0, BATTLE_ENTRY_CAPACITY)
    val safeAnchor = recoveryStartedAtMillis.takeIf { it > 0L }
    if (safeAnchor == null) {
        return if (safeRemaining >= BATTLE_ENTRY_CAPACITY) {
            BattleEntryRecoveryState(BATTLE_ENTRY_CAPACITY, 0L)
        } else {
            BattleEntryRecoveryState(safeRemaining, nowMillis)
        }
    }
    val elapsed = if (nowMillis >= safeAnchor) nowMillis - safeAnchor else 0L
    val recoveredIntervals = elapsed / BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS
    if (recoveredIntervals <= 0L) return BattleEntryRecoveryState(safeRemaining, safeAnchor)

    val recoveredRemaining = (safeRemaining.toLong() + recoveredIntervals)
        .coerceAtMost(BATTLE_ENTRY_CAPACITY.toLong())
        .toInt()
    if (recoveredRemaining >= BATTLE_ENTRY_CAPACITY) {
        return BattleEntryRecoveryState(BATTLE_ENTRY_CAPACITY, 0L)
    }
    return BattleEntryRecoveryState(
        remaining = recoveredRemaining,
        recoveryStartedAtMillis = safeAnchor +
            recoveredIntervals * BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
    )
}

internal fun spendBattleEntry(
    remaining: Int,
    recoveryStartedAtMillis: Long,
    nowMillis: Long,
): BattleEntrySpend {
    val current = recoverBattleEntries(remaining, recoveryStartedAtMillis, nowMillis)
    if (current.remaining <= 0) return BattleEntrySpend(false, current)
    val nextRemaining = current.remaining - 1
    return BattleEntrySpend(
        accepted = true,
        state = BattleEntryRecoveryState(
            remaining = nextRemaining,
            recoveryStartedAtMillis = current.recoveryStartedAtMillis.takeIf { it > 0L }
                ?: nowMillis,
        ),
    )
}

internal fun refillBattleEntries(): BattleEntryRecoveryState =
    BattleEntryRecoveryState(BATTLE_ENTRY_CAPACITY, 0L)

internal fun battleEntryRecoveryRemainingMillis(
    state: BattleEntryRecoveryState,
    nowMillis: Long,
): Long {
    val current = recoverBattleEntries(
        remaining = state.remaining,
        recoveryStartedAtMillis = state.recoveryStartedAtMillis,
        nowMillis = nowMillis.coerceAtLeast(0L),
    )
    if (current.remaining >= BATTLE_ENTRY_CAPACITY) return 0L
    val anchor = current.recoveryStartedAtMillis.takeIf { it > 0L } ?: nowMillis
    val elapsed = if (nowMillis >= anchor) nowMillis - anchor else 0L
    val untilNext = (BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS - elapsed)
        .coerceIn(0L, BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS)
    return untilNext
}

internal fun battleEntryRecoveryCountdownLabel(remainingMillis: Long): String {
    val millis = remainingMillis.coerceAtLeast(0L)
    val seconds = millis / 1_000L + if (millis % 1_000L == 0L) 0L else 1L
    val minutesPart = seconds / 60L
    val secondsPart = seconds % 60L
    return "%d:%02d".format(minutesPart, secondsPart)
}

internal fun arenaDailyResetCountdownLabel(remainingMillis: Long): String {
    val millis = remainingMillis.coerceAtLeast(0L)
    val seconds = millis / 1_000L + if (millis % 1_000L == 0L) 0L else 1L
    val hoursPart = seconds / (60L * 60L)
    val minutesPart = (seconds / 60L) % 60L
    val secondsPart = seconds % 60L
    return "%d:%02d:%02d".format(hoursPart, minutesPart, secondsPart)
}

internal fun battleRewardedRefillsUsed(
    storedDay: Long,
    storedCount: Int,
    today: Long,
): Int = if (storedDay == today) {
    storedCount.coerceIn(0, BATTLE_REWARDED_REFILL_DAILY_LIMIT)
} else if (today < storedDay) {
    // A local clock rollback must not reopen already consumed rewarded views.
    storedCount.coerceIn(0, BATTLE_REWARDED_REFILL_DAILY_LIMIT)
} else {
    0
}

internal fun battleRewardedRefillAvailable(
    entriesRemaining: Int,
    refillsUsed: Int,
    dailyBattlesUsed: Int = 0,
): Boolean = entriesRemaining <= 0 &&
    refillsUsed < BATTLE_REWARDED_REFILL_DAILY_LIMIT &&
    dailyBattlesUsed < BATTLE_ENTRY_DAILY_LIMIT

internal fun battleDailyEntriesUsed(
    storedDay: Long,
    storedCount: Int,
    today: Long,
): Int = if (storedDay == today) {
    storedCount.coerceIn(0, BATTLE_ENTRY_DAILY_LIMIT)
} else if (today < storedDay) {
    // A local clock rollback must not reopen the daily competitive cap.
    storedCount.coerceIn(0, BATTLE_ENTRY_DAILY_LIMIT)
} else {
    0
}

/** Rewarded refills never provide more entries than can still be used today. */
internal fun battleRewardedRefillCount(dailyBattlesUsed: Int): Int =
    (BATTLE_ENTRY_DAILY_LIMIT - dailyBattlesUsed.coerceAtLeast(0)).coerceIn(0, BATTLE_ENTRY_CAPACITY)
