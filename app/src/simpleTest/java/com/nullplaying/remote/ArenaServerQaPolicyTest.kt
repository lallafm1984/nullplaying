package com.nullplaying.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaServerQaPolicyTest {
    @Test
    fun `allowlist contains only auth profile publication and roster RPCs`() {
        assertEquals(
            setOf(
                "/auth/v1/signup" to "POST",
                "/auth/v1/token?grant_type=refresh_token" to "POST",
                "/auth/v1/token?grant_type=password" to "POST",
                "/auth/v1/user" to "GET",
                "/rest/v1/rpc/sync_player_network_profile" to "POST",
                "/rest/v1/rpc/sync_public_player_snapshots" to "POST",
                "/rest/v1/rpc/get_daily_public_player_roster" to "POST",
            ),
            ArenaServerQaEndpoint.entries.map { it.path to it.method }.toSet(),
        )

        assertFalse(isArenaServerQaEndpointAllowed("/rest/v1/user_profiles", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/rest/v1/rpc/get_daily_leaderboard", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/rest/v1/rpc/sync_ranking_entries", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/rest/v1/rpc/battle_request_entry", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/rest/v1/rpc/battle_get_engine_contract", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/rest/v1/rpc/battle_settle_match", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/functions/v1/arena-battle", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/rest/v1/app_session_logs", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/auth/v1/signup?email=x", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/auth/v1/signup", "GET"))
    }

    @Test
    fun `QA transport requires every narrow feature gate and blank standard credentials`() {
        val valid = validConfig()
        assertTrue(valid.enabled)

        assertFalse(valid.copy(applicationId = "com.nullplaying").enabled)
        assertFalse(valid.copy(debugBuild = false).enabled)
        assertFalse(valid.copy(remoteServicesEnabled = true).enabled)
        assertFalse(valid.copy(transportEnabled = false).enabled)
        assertFalse(valid.copy(adventureSystemEnabled = false).enabled)
        assertFalse(valid.copy(sharedPlayerSyncEnabled = false).enabled)
        assertFalse(valid.copy(arenaServerMatchingEnabled = false).enabled)
        assertFalse(valid.copy(standardSupabaseUrl = "https://production.invalid").enabled)
        assertFalse(valid.copy(standardSupabasePublishableKey = "production-key").enabled)
        assertFalse(valid.copy(qaSupabaseUrl = "http://qa.invalid").enabled)
        assertFalse(valid.copy(qaSupabasePublishableKey = "").enabled)
        assertFalse(valid.copy(qaSupabasePublishableKey = "sb_secret_forbidden").enabled)
        assertFalse(valid.copy(qaSupabasePublishableKey = "service_role_forbidden").enabled)
    }

    private fun validConfig() = ArenaServerQaRuntimeConfig(
        applicationId = ARENA_SERVER_QA_APPLICATION_ID,
        debugBuild = true,
        remoteServicesEnabled = false,
        transportEnabled = true,
        adventureSystemEnabled = true,
        sharedPlayerSyncEnabled = true,
        arenaServerMatchingEnabled = true,
        standardSupabaseUrl = "",
        standardSupabasePublishableKey = "",
        qaSupabaseUrl = "https://abcdefghijklmnopqrst.supabase.co",
        qaSupabasePublishableKey = "sb_publishable_qa_only",
    )
}
