package com.nullplaying.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsConsentStateTest {
    @Test
    fun `unknown startup state is not presented as a failure`() {
        val actions = AdsConsentState().actions()

        assertFalse(actions.showRecovery)
        assertFalse(actions.showPrivacyOptions)
        assertFalse(actions.recoveryInProgress)
    }

    @Test
    fun `failed refresh without a requestable cached decision offers recovery`() {
        val actions = AdsConsentState(
            refreshStatus = ConsentRefreshStatus.RETRYABLE_ERROR,
            canRequestAds = false,
        ).actions()

        assertTrue(actions.showRecovery)
    }

    @Test
    fun `failed refresh with a requestable cached decision does not block ads`() {
        val actions = AdsConsentState(
            refreshStatus = ConsentRefreshStatus.RETRYABLE_ERROR,
            canRequestAds = true,
        ).actions()

        assertFalse(actions.showRecovery)
    }

    @Test
    fun `completed but unresolved form offers recovery`() {
        val actions = AdsConsentState(
            refreshStatus = ConsentRefreshStatus.COMPLETE,
            canRequestAds = false,
        ).actions()

        assertTrue(actions.showRecovery)
    }

    @Test
    fun `privacy entry point follows requirement status instead of consent inference`() {
        val requiredAfterRequestableChoice = AdsConsentState(
            refreshStatus = ConsentRefreshStatus.COMPLETE,
            canRequestAds = true,
            privacyOptionsStatus = PrivacyOptionsStatus.REQUIRED,
        ).actions()
        val notRequired = AdsConsentState(
            refreshStatus = ConsentRefreshStatus.COMPLETE,
            canRequestAds = true,
            privacyOptionsStatus = PrivacyOptionsStatus.NOT_REQUIRED,
        ).actions()

        assertTrue(requiredAfterRequestableChoice.showPrivacyOptions)
        assertFalse(notRequired.showPrivacyOptions)
    }

    @Test
    fun `privacy entry point waits for the current refresh to finish`() {
        val checking = AdsConsentState(
            refreshStatus = ConsentRefreshStatus.CHECKING,
            canRequestAds = true,
            privacyOptionsStatus = PrivacyOptionsStatus.REQUIRED,
        ).actions()

        assertFalse(checking.showPrivacyOptions)
    }

    @Test
    fun `checking disables repeated recovery attempts`() {
        val actions = AdsConsentState(
            refreshStatus = ConsentRefreshStatus.CHECKING,
        ).actions()

        assertTrue(actions.recoveryInProgress)
        assertFalse(actions.showRecovery)
    }
}
