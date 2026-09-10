package com.nullplaying.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedAdSlotPolicyTest {
    @Test
    fun `cached rewarded ad expires before the SDK one hour boundary`() {
        assertTrue(rewardedAdCacheIsFresh(1_000L, 1_000L + REWARDED_AD_CACHE_MAX_AGE_MILLIS - 1L))
        assertFalse(rewardedAdCacheIsFresh(1_000L, 1_000L + REWARDED_AD_CACHE_MAX_AGE_MILLIS))
        assertFalse(rewardedAdCacheIsFresh(-1L, 1_000L))
        assertFalse(rewardedAdCacheIsFresh(2_000L, 1_000L))
    }

    @Test
    fun `offline slot loads only while recharge is possible`() {
        assertTrue(
            rewardedAdShouldLoad(
                mobileAdsReady = true,
                adUnitId = "offline-unit",
                benefitEligible = true,
            ),
        )
        assertFalse(
            rewardedAdShouldLoad(
                mobileAdsReady = true,
                adUnitId = "offline-unit",
                benefitEligible = false,
            ),
        )
    }

    @Test
    fun `arena preload starts at one ticket or an empty wallet and survives natural recovery`() {
        for (remaining in 2..5) {
            assertFalse(arenaRewardedAdCanPreload(21L, 0, 0, remaining))
        }
        assertTrue(arenaRewardedAdCanPreload(10L, 0, 4, 1))
        assertTrue(arenaRewardedAdCanPreload(21L, 0, 9, 0))
        for (remaining in 0..5) {
            assertTrue(arenaRewardedAdCanPreload(21L, 0, 7, remaining, preloadAlreadyStarted = true))
        }
        assertFalse(arenaRewardedAdCanPreload(9L, 0, 0, 1))
        assertFalse(arenaRewardedAdCanPreload(21L, 1, 0, 1, preloadAlreadyStarted = true))
        assertFalse(arenaRewardedAdCanPreload(21L, 0, 10, 1, preloadAlreadyStarted = true))
        assertTrue(rewardedAdShouldLoad(true, "arena-unit", true))
        assertFalse(rewardedAdShouldLoad(false, "arena-unit", true))
        assertFalse(rewardedAdShouldLoad(true, "", true))
    }

    @Test
    fun `slow SDK requests offer retry at twenty seconds and only replacement invalidates callbacks`() {
        assertTrue(rewardedAdLoadTimeoutRemainingMillis(1_000L, 1_000L) == 20_000L)
        assertTrue(rewardedAdLoadTimeoutRemainingMillis(1_000L, 20_999L) == 1L)
        assertTrue(rewardedAdLoadTimeoutRemainingMillis(1_000L, 21_000L) == 0L)
        assertTrue(rewardedAdLoadTimeoutRemainingMillis(1_000L, 60_000L) == 0L)
        assertTrue(rewardedAdRetryAllowed(RewardedLoadState.LOAD_FAILED, true))
        assertTrue(rewardedAdCallbackIsCurrent(7, 7)) // A late same-request success is still useful.
        assertFalse(rewardedAdCallbackIsCurrent(7, 8))
    }

    @Test
    fun `retry stops when slot demand disappears`() {
        assertTrue(rewardedAdRetryAllowed(RewardedLoadState.LOAD_FAILED, shouldLoad = true))
        assertTrue(rewardedAdRetryAllowed(RewardedLoadState.SHOW_FAILED, shouldLoad = true))
        assertFalse(rewardedAdRetryAllowed(RewardedLoadState.LOAD_FAILED, shouldLoad = false))
        assertFalse(rewardedAdRetryAllowed(RewardedLoadState.READY, shouldLoad = true))
    }

    @Test
    fun `callback token rejects results from a closed or replaced load request`() {
        assertTrue(rewardedAdCallbackIsCurrent(callbackRequestToken = 7, activeRequestToken = 7))
        assertFalse(rewardedAdCallbackIsCurrent(callbackRequestToken = 7, activeRequestToken = 8))
    }

    @Test
    fun `retry backoff is capped after thirty sixty one-twenty and two-forty seconds`() {
        assertTrue(rewardedAdRetryDelayMillis(0) == 30_000L)
        assertTrue(rewardedAdRetryDelayMillis(1) == 60_000L)
        assertTrue(rewardedAdRetryDelayMillis(2) == 120_000L)
        assertTrue(rewardedAdRetryDelayMillis(3) == 240_000L)
        assertTrue(rewardedAdRetryDelayMillis(4) == 300_000L)
        assertTrue(rewardedAdRetryDelayMillis(99) == 300_000L)
    }

    @Test
    fun `rapid retries are throttled but a timed out request can retry immediately`() {
        assertTrue(rewardedAdLoadThrottleDelayMillis(-1L, 1_000L) == 0L)
        assertTrue(rewardedAdLoadThrottleDelayMillis(10_000L, 10_000L) == 10_000L)
        assertTrue(rewardedAdLoadThrottleDelayMillis(10_000L, 19_999L) == 1L)
        assertTrue(rewardedAdLoadThrottleDelayMillis(10_000L, 30_000L) == 0L)
        assertTrue(rewardedAdLoadThrottleDelayMillis(40_000L, 10_000L) == 0L)
    }
}
