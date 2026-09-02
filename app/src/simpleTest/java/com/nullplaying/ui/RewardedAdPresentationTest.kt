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
            assertTrue(presentation.message.contains("앱을 켜 둔 동안"))
        }
        assertTrue(presentations.last().message.contains("자동으로 충전"))
        assertTrue(presentations.last().message.contains("선택 사항"))
        assertTrue(presentations.last().message.contains("끝까지 보면"))
    }

    private fun requestableConsent() = AdsConsentState(
        refreshStatus = ConsentRefreshStatus.COMPLETE,
        canRequestAds = true,
    )
}
