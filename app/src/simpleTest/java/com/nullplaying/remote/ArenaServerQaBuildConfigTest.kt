package com.nullplaying.remote

import com.nullplaying.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ArenaServerQaBuildConfigTest {
    @Test
    fun `arena server QA build isolates broad remote services`() {
        assumeTrue(BuildConfig.APPLICATION_ID == ARENA_SERVER_QA_APPLICATION_ID)

        assertTrue(BuildConfig.DEBUG)
        assertFalse(BuildConfig.REMOTE_SERVICES_ENABLED)
        assertTrue(BuildConfig.SHARED_PLAYER_QA_TRANSPORT_ENABLED)
        assertTrue(BuildConfig.SHARED_PLAYER_SYNC_ENABLED)
        assertTrue(BuildConfig.ADVENTURE_SYSTEM_ENABLED)
        assertTrue(BuildConfig.ARENA_SERVER_MATCHING_ENABLED)
        assertEquals("", BuildConfig.SUPABASE_URL)
        assertEquals("", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        assertEquals("", BuildConfig.ADMOB_APP_ID)
        assertEquals("", BuildConfig.BANNER_AD_UNIT_ID)
        assertEquals("", BuildConfig.REWARDED_AD_UNIT_ID)
        assertEquals("", BuildConfig.ARENA_REWARDED_AD_UNIT_ID)
        assertFalse(BuildConfig.BATTLE_QA_BRIDGE_ENABLED)
        assertEquals("", BuildConfig.BATTLE_QA_BRIDGE_URL)
    }

    @Test
    fun `runtime stays closed until both QA credentials are supplied`() {
        assumeTrue(BuildConfig.APPLICATION_ID == ARENA_SERVER_QA_APPLICATION_ID)

        val hasQaCredentials = BuildConfig.SUPABASE_QA_URL.isNotBlank() &&
            BuildConfig.SUPABASE_QA_PUBLISHABLE_KEY.isNotBlank()
        assertEquals(hasQaCredentials, ArenaServerQaRuntimeConfig.fromBuildConfig().enabled)
    }
}
