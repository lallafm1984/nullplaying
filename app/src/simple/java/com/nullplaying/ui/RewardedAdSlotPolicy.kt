package com.nullplaying.ui

internal const val REWARDED_AD_CACHE_MAX_AGE_MILLIS = 55L * 60L * 1_000L
internal const val REWARDED_AD_MIN_LOAD_INTERVAL_MILLIS = 10_000L
internal const val REWARDED_AD_LOAD_TIMEOUT_MILLIS = 20_000L

internal fun rewardedAdCacheIsFresh(
    loadedAtElapsedRealtimeMillis: Long,
    nowElapsedRealtimeMillis: Long,
): Boolean = loadedAtElapsedRealtimeMillis >= 0L &&
    nowElapsedRealtimeMillis >= loadedAtElapsedRealtimeMillis &&
    nowElapsedRealtimeMillis - loadedAtElapsedRealtimeMillis < REWARDED_AD_CACHE_MAX_AGE_MILLIS

internal fun rewardedAdShouldLoad(
    mobileAdsReady: Boolean,
    adUnitId: String,
    benefitEligible: Boolean,
): Boolean = mobileAdsReady &&
    adUnitId.isNotBlank() &&
    benefitEligible

internal fun rewardedAdRetryAllowed(
    loadState: RewardedLoadState,
    shouldLoad: Boolean,
): Boolean = shouldLoad && loadState in setOf(
    RewardedLoadState.LOAD_FAILED,
    RewardedLoadState.SHOW_FAILED,
)

internal fun rewardedAdRetryDelayMillis(failedAttemptCount: Int): Long {
    val exponent = failedAttemptCount.coerceIn(0, 4)
    return (30_000L shl exponent).coerceAtMost(300_000L)
}

internal fun rewardedAdLoadThrottleDelayMillis(
    lastLoadAttemptAtElapsedRealtimeMillis: Long,
    nowElapsedRealtimeMillis: Long,
): Long {
    if (
        lastLoadAttemptAtElapsedRealtimeMillis < 0L ||
        nowElapsedRealtimeMillis < lastLoadAttemptAtElapsedRealtimeMillis
    ) {
        return 0L
    }
    val elapsed = nowElapsedRealtimeMillis - lastLoadAttemptAtElapsedRealtimeMillis
    return (REWARDED_AD_MIN_LOAD_INTERVAL_MILLIS - elapsed).coerceAtLeast(0L)
}

internal fun rewardedAdCallbackIsCurrent(
    callbackRequestToken: Int,
    activeRequestToken: Int,
): Boolean = callbackRequestToken == activeRequestToken

internal enum class RewardedShowAttempt {
    SHOWN,
    NOT_READY,
    ELIGIBILITY_LOST,
}

/** Start at the last ticket, then retain demand across natural recovery and closing the dialog. */
internal fun arenaRewardedAdCanPreload(
    heroLevel: Long,
    refillsUsed: Int,
    dailyBattlesUsed: Int,
    entriesRemaining: Int,
    preloadAlreadyStarted: Boolean = false,
): Boolean = heroLevel >= 10L &&
    refillsUsed < BATTLE_REWARDED_REFILL_DAILY_LIMIT &&
    dailyBattlesUsed < BATTLE_ENTRY_DAILY_LIMIT &&
    (entriesRemaining <= 1 || preloadAlreadyStarted)

internal fun rewardedAdLoadTimeoutRemainingMillis(startedAtMillis: Long, nowMillis: Long): Long =
    (REWARDED_AD_LOAD_TIMEOUT_MILLIS - (nowMillis - startedAtMillis).coerceAtLeast(0L))
        .coerceIn(0L, REWARDED_AD_LOAD_TIMEOUT_MILLIS)
