package com.nullplaying.remote

import com.nullplaying.model.HeroClass
import com.nullplaying.model.PublicPlayerStats
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseSharedPlayerApiTest {
    @Test
    fun `authenticated api uses fixed rpc bodies with minimal public payload`() = runBlocking {
        val transport = FakeTransport()
        val api = SupabaseAuthenticatedSharedPlayerApi(transport)
        val upload = PublicPlayerSnapshotUpload(
            characterId = UUID.randomUUID().toString(),
            slotId = 1,
            displayName = "공개 영웅",
            heroClass = HeroClass.PALADIN,
            level = 10L,
            combatPower = 92L,
            stats = PublicPlayerStats(12L, 11L, 10L, 9L, 8L, 12L, 180L, 90L),
            adventureTraitIds = listOf("G03"),
        )

        api.syncPublicPlayerSnapshots(listOf(upload))

        val body = transport.unifiedBody!!
        assertTrue(body.contains("\"p_characters\""))
        assertTrue(body.contains("\"slot_id\":1"))
        assertTrue(body.contains("\"max_health\""))
        listOf("learned_skills", "usage_count", "mastery", "equipped_items", "rarity", "gold")
            .forEach { assertFalse(body.contains(it)) }
    }

    @Test
    fun `missing additive rpc falls back without sending slot id to strict legacy validator`() = runBlocking {
        val transport = FakeTransport(unifiedAvailable = false)
        val api = SupabaseAuthenticatedSharedPlayerApi(transport)
        val upload = upload(level = 20L)

        api.syncPublicPlayerSnapshots(listOf(upload))

        assertNotNull(transport.unifiedBody)
        val legacy = requireNotNull(transport.syncBody)
        assertTrue(legacy.contains("\"p_snapshots\""))
        assertFalse(legacy.contains("slot_id"))
    }

    @Test
    fun `empty complete roster is sent through unified rpc as a deletion tombstone`() = runBlocking {
        val transport = FakeTransport(
            unifiedResponse =
                """{"accepted":true,"rules_version":1,"synced_count":0,"ranking_synced_count":0,"server_now":2000}""",
        )
        val api = SupabaseAuthenticatedSharedPlayerApi(transport)

        val publication = api.syncPublicPlayerSnapshots(emptyList())

        assertEquals(SharedPlayerPublicationMode.UNIFIED, publication)
        assertEquals("{\"p_characters\":[]}", transport.unifiedBody)
        assertTrue(transport.syncBody == null)
    }

    @Test
    fun `initial empty profile is local no-op but prior nonempty receipt requires tombstone`() {
        val emptyHash = "empty-profile-hash"
        assertFalse(
            shouldPublishPlayerNetworkProfile(
                receipt = null,
                userId = "account-a",
                payloadHash = emptyHash,
                characterCount = 0,
                nowEpochMillis = 2_000L,
            ),
        )
        assertTrue(
            shouldPublishPlayerNetworkProfile(
                receipt = PlayerNetworkProfileSyncReceipt(
                    userId = "account-a",
                    payloadHash = "previous-nonempty",
                    syncedAtEpochMillis = 1_000L,
                    serverNowEpochMillis = 1_000L,
                ),
                userId = "account-a",
                payloadHash = emptyHash,
                characterCount = 0,
                nowEpochMillis = 2_000L,
            ),
        )
    }

    @Test
    fun `unified receipt must match both server projections before ranking callback`() = runBlocking {
        var callbacks = 0
        val transport = FakeTransport(unifiedResponse =
            """{"accepted":true,"rules_version":1,"synced_count":1,"ranking_synced_count":0,"server_now":1000}""")
        val api = SupabaseAuthenticatedSharedPlayerApi(
            transport = transport,
            onUnifiedRankingSynced = { _, _ -> callbacks += 1 },
        )

        assertTrue(runCatching { api.syncPublicPlayerSnapshots(listOf(upload(level = 20L))) }.isFailure)
        assertEquals(0, callbacks)
        assertTrue(transport.syncBody == null)
    }

    @Test
    fun `oversized daily roster is rejected before decoding`() = runBlocking {
        val requesterId = UUID.randomUUID().toString()
        val oversized = " ".repeat(MAX_DAILY_ROSTER_RESPONSE_BYTES + 1)
        val api = SupabaseAuthenticatedSharedPlayerApi(FakeTransport(rosterBody = oversized))

        assertTrue(runCatching { api.getDailyPublicPlayerRoster(requesterId, 1) }.isFailure)
    }

    @Test
    fun `deployment gate is fail closed`() {
        assertTrue(isSharedPlayerRemoteEnabled(true, false, true, true, "https://project.supabase.co", "sb_publishable_test"))
        assertFalse(isSharedPlayerRemoteEnabled(true, true, true, true, "https://project.supabase.co", "sb_publishable_test"))
        assertFalse(isSharedPlayerRemoteEnabled(true, false, false, true, "https://project.supabase.co", "sb_publishable_test"))
        assertFalse(isSharedPlayerRemoteEnabled(true, false, true, false, "https://project.supabase.co", "sb_publishable_test"))
        assertFalse(isSharedPlayerRemoteEnabled(true, false, true, true, "http://project.supabase.co", "sb_publishable_test"))
        assertFalse(isSharedPlayerRemoteEnabled(true, false, true, true, "https://project.supabase.co", "sb_secret_test"))
    }

    @Test
    fun `client throttle skips unchanged day and defers rapid changed payload`() {
        val receipt = SharedPlayerSyncReceipt("account-a", "hash-a", 1_000L)
        assertEquals(SharedPlayerSyncDecision.SKIP_UNCHANGED,
            sharedPlayerSyncDecision(receipt, "account-a", "hash-a", 2_000L))
        assertEquals(SharedPlayerSyncDecision.DEFER_CHANGED,
            sharedPlayerSyncDecision(receipt, "account-a", "hash-b", 2_000L))
        assertEquals(SharedPlayerSyncDecision.SEND,
            sharedPlayerSyncDecision(receipt, "account-a", "hash-b", 901_001L))
        assertEquals(SharedPlayerSyncDecision.SEND,
            sharedPlayerSyncDecision(receipt, "account-b", "hash-a", 2_000L))
    }

    @Test
    fun `transport decodes malformed snapshot rows lazily so valid rows survive`() = runBlocking {
        val requesterId = UUID.randomUUID().toString()
        val validId = UUID.randomUUID().toString()
        val rosterId = UUID.randomUUID().toString()
        val body = """{
          "roster_id":"$rosterId","roster_date_utc":"2026-09-07","requester_level":10,
          "rules_version":1,"generated_at":90000,"valid_until":3700000,"server_now":100000,
          "snapshots":[
            {"projection_id":"$validId","display_name":"온전한 여행자","hero_class":"RANGER",
             "level":10,"combat_power":50,"rules_version":1,"snapshot_version":1,
             "stats":{"strength":10,"constitution":11,"dexterity":12,"intelligence":13,
                      "wisdom":14,"charisma":15,"max_health":180,"max_mana":90},
             "adventure_trait_ids":["T02"]},
            {"projection_id":"${UUID.randomUUID()}","display_name":"알 수 없음","hero_class":"UNKNOWN"}
          ]
        }""".trimIndent()
        val api = SupabaseAuthenticatedSharedPlayerApi(FakeTransport(rosterBody = body))

        val wire = api.getDailyPublicPlayerRoster(requesterId, 1)
        val roster = wire.toPublicPlayerRoster(requesterId, 10L, 1_000L)

        assertNotNull(roster)
        assertEquals(listOf(validId), roster!!.snapshots.map { it.projectionId })
    }

    private class FakeTransport(
        private val rosterBody: String? = null,
        private val unifiedAvailable: Boolean = true,
        private val unifiedResponse: String =
            """{"accepted":true,"rules_version":1,"synced_count":1,"ranking_synced_count":0,"server_now":1000}""",
    ) : AuthenticatedSharedPlayerRpcTransport {
        var unifiedBody: String? = null
        var syncBody: String? = null
        override suspend fun captureSessionIdentity(): String = "account-a"
        override fun isSessionIdentityCurrent(identity: String): Boolean = identity == "account-a"
        override suspend fun syncPlayerNetworkProfile(requestBody: String): String? {
            unifiedBody = requestBody
            return unifiedResponse.takeIf { unifiedAvailable }
        }
        override suspend fun syncPublicPlayerSnapshots(requestBody: String): String {
            syncBody = requestBody
            return """{"rules_version":1,"synced_count":1,"server_now":1000}"""
        }
        override suspend fun getDailyPublicPlayerRoster(requestBody: String): String =
            rosterBody ?: error("unused")
    }

    private fun upload(level: Long = 10L) = PublicPlayerSnapshotUpload(
        characterId = UUID.randomUUID().toString(),
        slotId = 1,
        displayName = "공개 영웅",
        heroClass = HeroClass.PALADIN,
        level = level,
        combatPower = if (level == 10L) 92L else 192L,
        stats = PublicPlayerStats(12L, 11L, 10L, 9L, 8L, 12L, 180L, 90L),
        adventureTraitIds = listOf("G03"),
    )
}
