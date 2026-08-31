package com.nullplaying

import com.nullplaying.localization.AppLanguage
import com.nullplaying.ui.privacyPolicyUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPolicyConfigurationTest {
    @Test
    fun privacyPolicyUsesThePublic4LTreeUrl() {
        assertEquals(
            "https://nullplaying.4ltree.com/privacy",
            BuildConfig.PRIVACY_POLICY_URL,
        )
        assertTrue(BuildConfig.PRIVACY_POLICY_URL.startsWith("https://"))
    }

    @Test
    fun privacyPolicyUsesTheRouteForTheSelectedAppLanguage() {
        assertEquals(
            "https://nullplaying.4ltree.com/privacy",
            privacyPolicyUrl(BuildConfig.PRIVACY_POLICY_URL, AppLanguage.KOREAN),
        )
        assertEquals(
            "https://nullplaying.4ltree.com/privacy/en",
            privacyPolicyUrl(BuildConfig.PRIVACY_POLICY_URL, AppLanguage.ENGLISH),
        )
        assertEquals(
            "https://nullplaying.4ltree.com/privacy/ja",
            privacyPolicyUrl(BuildConfig.PRIVACY_POLICY_URL, AppLanguage.JAPANESE),
        )
    }

    @Test
    fun dataDeletionUsesThePublic4LTreeUrl() {
        assertEquals(
            "https://nullplaying.4ltree.com/data-deletion",
            BuildConfig.DATA_DELETION_URL,
        )
        assertTrue(BuildConfig.DATA_DELETION_URL.startsWith("https://"))
    }
}
