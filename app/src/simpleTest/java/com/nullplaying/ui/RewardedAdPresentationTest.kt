package com.nullplaying.ui

import com.nullplaying.ads.AdsConsentState
import com.nullplaying.ads.ConsentRefreshStatus
import com.nullplaying.ads.MobileAdsRuntimeState
import com.nullplaying.ads.PrivacyOptionsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedAdPresentationTest {
    @Test
    fun `consent failure offers setup recovery without calling a normal refusal an error`() {
        val failure = rewardDialogPresentation(
            consentState = AdsConsentState(
                refreshStatus = ConsentRefreshStatus.RETRYABLE_ERROR,
                canRequestAds = false,
            ),
            mobileAdsRuntimeState = MobileAdsRuntimeState.WAITING_FOR_CONSENT,
            rewardedLoadState = RewardedLoadState.WAITING,
        )
        val requestableChoice = rewardDialogPresentation(
            consentState = AdsConsentState(
                refreshStatus = ConsentRefreshStatus.COMPLETE,
                canRequestAds = true,
                privacyOptionsStatus = PrivacyOptionsStatus.REQUIRED,
            ),
            mobileAdsRuntimeState = MobileAdsRuntimeState.READY,
            rewardedLoadState = RewardedLoadState.LOADING,
        )

        assertEquals(RewardDialogAction.RETRY_AD_SETUP, failure.action)
        assertTrue(failure.confirmEnabled)
        assertEquals(RewardDialogAction.NONE, requestableChoice.action)
        assertFalse(requestableChoice.confirmEnabled)
    }

    @Test
    fun `mobile sdk failure offers setup recovery`() {
        val presentation = rewardDialogPresentation(
            consentState = AdsConsentState(
                refreshStatus = ConsentRefreshStatus.COMPLETE,
                canRequestAds = true,
            ),
            mobileAdsRuntimeState = MobileAdsRuntimeState.RETRYABLE_ERROR,
            rewardedLoadState = RewardedLoadState.WAITING,
        )

        assertEquals(RewardDialogAction.RETRY_AD_SETUP, presentation.action)
    }

    @Test
    fun `ad load and show failures offer an ad retry`() {
        listOf(RewardedLoadState.LOAD_FAILED, RewardedLoadState.SHOW_FAILED).forEach { state ->
            val presentation = rewardDialogPresentation(
                consentState = requestableConsent(),
                mobileAdsRuntimeState = MobileAdsRuntimeState.READY,
                rewardedLoadState = state,
            )

            assertEquals(RewardDialogAction.RETRY_AD_LOAD, presentation.action)
            assertTrue(presentation.confirmEnabled)
        }
    }

    @Test
    fun `ready ad is the only state that offers watching`() {
        val ready = rewardDialogPresentation(
            consentState = requestableConsent(),
            mobileAdsRuntimeState = MobileAdsRuntimeState.READY,
            rewardedLoadState = RewardedLoadState.READY,
        )
        val loading = rewardDialogPresentation(
            consentState = requestableConsent(),
            mobileAdsRuntimeState = MobileAdsRuntimeState.READY,
            rewardedLoadState = RewardedLoadState.LOADING,
        )

        assertEquals(RewardDialogAction.WATCH_AD, ready.action)
        assertTrue(ready.confirmEnabled)
        assertEquals(RewardDialogAction.NONE, loading.action)
        assertFalse(loading.confirmEnabled)
    }

    @Test
    fun `all dialog states explain that automatic charging continues`() {
        val presentations = listOf(
            rewardDialogPresentation(
                AdsConsentState(
                    refreshStatus = ConsentRefreshStatus.RETRYABLE_ERROR,
                    canRequestAds = false,
                ),
                MobileAdsRuntimeState.WAITING_FOR_CONSENT,
                RewardedLoadState.WAITING,
            ),
            rewardDialogPresentation(
                requestableConsent(),
                MobileAdsRuntimeState.READY,
                RewardedLoadState.LOADING,
            ),
            rewardDialogPresentation(
                requestableConsent(),
                MobileAdsRuntimeState.READY,
                RewardedLoadState.LOAD_FAILED,
            ),
            rewardDialogPresentation(
                requestableConsent(),
                MobileAdsRuntimeState.READY,
                RewardedLoadState.READY,
            ),
        )

        presentations.forEach { presentation ->
            assertTrue(
                presentation.message.contains("앱을 켜 둔 동안") ||
                    presentation.supportingMessage?.contains("앱을 켜 둔 동안") == true,
            )
        }
        assertEquals(
            "광고를 끝까지 보면\n오프라인 모험 시간이 즉시 충전됩니다.",
            presentations.last().message,
        )
        assertEquals(
            "광고를 보지 않아도 앱을 켜 둔 동안\n자동으로 충전됩니다.",
            presentations.last().supportingMessage,
        )
        assertEquals("광고 보고 모두 충전", presentations.last().confirmLabel)
    }

    @Test
    fun `arena reward dialog explains five ticket reward and ten minute refill`() {
        val ready = rewardDialogPresentation(
            consentState = requestableConsent(),
            mobileAdsRuntimeState = MobileAdsRuntimeState.READY,
            rewardedLoadState = RewardedLoadState.READY,
            benefit = RewardedBenefit.ARENA_TICKETS,
        )
        val loading = rewardDialogPresentation(
            consentState = requestableConsent(),
            mobileAdsRuntimeState = MobileAdsRuntimeState.READY,
            rewardedLoadState = RewardedLoadState.LOADING,
            benefit = RewardedBenefit.ARENA_TICKETS,
        )
        val failed = rewardDialogPresentation(
            consentState = requestableConsent(),
            mobileAdsRuntimeState = MobileAdsRuntimeState.READY,
            rewardedLoadState = RewardedLoadState.LOAD_FAILED,
            benefit = RewardedBenefit.ARENA_TICKETS,
        )

        assertEquals("광고 시청 완료 시\n출전권 5개 즉시 충전", ready.message)
        assertEquals(
            "10분 자동 충전 시간 유지\n" +
                "캐릭터별 하루 1회 · 출전권 0개일 때 이용",
            ready.supportingMessage,
        )
        assertEquals("광고로 5회 충전", ready.confirmLabel)
        assertEquals(RewardDialogAction.WATCH_AD, ready.action)
        assertEquals("광고 준비 중입니다. 진행 중인 자동 충전은 계속됩니다.", loading.message)
        assertEquals("광고를 불러오지 못했습니다. 진행 중인 자동 충전은 계속됩니다.", failed.message)
        listOf(ready.message, ready.supportingMessage, loading.message, failed.message).forEach { copy ->
            assertFalse(copy.orEmpty().contains("5분"))
        }
    }

    @Test
    fun `reward dialog displays a three ticket refill after seven daily matches`() {
        val ready = rewardDialogPresentation(
            consentState = requestableConsent(), mobileAdsRuntimeState = MobileAdsRuntimeState.READY,
            rewardedLoadState = RewardedLoadState.READY, benefit = RewardedBenefit.ARENA_TICKETS,
            arenaRefillCount = battleRewardedRefillCount(7),
        )
        assertEquals("광고로 3회 충전", ready.confirmLabel)
        assertEquals("광고 시청 완료 시\n출전권 3개 즉시 충전", ready.message)
    }

    private fun requestableConsent() = AdsConsentState(
        refreshStatus = ConsentRefreshStatus.COMPLETE,
        canRequestAds = true,
    )
}
