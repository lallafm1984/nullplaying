package com.nullplaying.remote

import com.nullplaying.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class OfflineQaIsolationTest {
    @Test
    fun `offline QA variant disables every configured remote service`() {
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".adventurepreview"))

        assertTrue(BuildConfig.DEBUG)
        assertTrue(BuildConfig.ADVENTURE_PREVIEW_ENABLED)
        assertTrue(BuildConfig.ADVENTURE_SYSTEM_ENABLED)
        assertFalse(BuildConfig.REMOTE_SERVICES_ENABLED)
        assertFalse(BuildConfig.SHARED_PLAYER_QA_TRANSPORT_ENABLED)
        assertFalse(BuildConfig.SHARED_PLAYER_SYNC_ENABLED)
        assertFalse(BuildConfig.ARENA_SERVER_MATCHING_ENABLED)
        assertFalse(BuildConfig.BATTLE_QA_BRIDGE_ENABLED)
        assertEquals("", BuildConfig.BATTLE_QA_BRIDGE_URL)
        assertEquals("", BuildConfig.SUPABASE_URL)
        assertEquals("", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        assertEquals("", BuildConfig.SUPABASE_QA_URL)
        assertEquals("", BuildConfig.SUPABASE_QA_PUBLISHABLE_KEY)
        assertEquals("", BuildConfig.ADMOB_APP_ID)
        assertEquals("", BuildConfig.BANNER_AD_UNIT_ID)
        assertEquals("", BuildConfig.REWARDED_AD_UNIT_ID)
        assertEquals("", BuildConfig.ARENA_REWARDED_AD_UNIT_ID)
    }

    @Test
    fun `local arena reserve is available without importing remote clients`() {
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".adventurepreview"))
        val root = java.io.File("app/src/simple/java").takeIf { it.isDirectory }
            ?: java.io.File("src/simple/java")
        val panel = java.io.File(root, "com/nullplaying/ui/BattlePanel.kt").readText()
        val reserve = java.io.File(
            root,
            "com/nullplaying/engine/arena/ArenaLocalFallbackMatchmaking.kt",
        ).readText()

        assertTrue(panel.contains("selectArenaOpponent("))
        assertTrue(panel.contains("roster = if (serverMatchingAllowed)"))
        assertFalse(reserve.contains("SharedPlayerSnapshotClient"))
        assertFalse(reserve.contains("Supabase"))
        assertFalse(reserve.contains("PublicPlayerSnapshot"))
    }
}
