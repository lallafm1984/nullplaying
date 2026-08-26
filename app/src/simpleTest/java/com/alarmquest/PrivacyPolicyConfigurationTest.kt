package com.alarmquest

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
    fun dataDeletionUsesThePublic4LTreeUrl() {
        assertEquals(
            "https://nullplaying.4ltree.com/data-deletion",
            BuildConfig.DATA_DELETION_URL,
        )
        assertTrue(BuildConfig.DATA_DELETION_URL.startsWith("https://"))
    }
}
