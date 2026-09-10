package com.nullplaying.remote

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaServerQaSharedPlayerTransportTest {
    @Test
    fun `live transport signs in with injected password and never signs up anonymously`() = runBlocking {
        val store = FakeSecureStore()
        val wire = FakeWire()
        var credentials: ArenaLiveServerQaCredentials? = ArenaLiveServerQaCredentials(
            email = "aq-live-260907abcd-a@example.invalid",
            password = "temporary-password-123",
        )
        val transport = ArenaServerQaSharedPlayerTransport(
            secureStore = store,
            wire = wire,
            onIdentityReplaced = {},
            liveServerMode = true,
            liveCredentials = { credentials.also { credentials = null } },
            nowEpochMillis = { 1_000_000L },
        )

        assertEquals(USER_ID, transport.captureSessionIdentity())
        assertEquals(ArenaServerQaEndpoint.QA_PASSWORD_SIGN_IN, wire.requests.single().endpoint)
        assertTrue(wire.requests.single().body!!.contains("@example.invalid"))
        assertFalse(wire.requests.any { it.endpoint == ArenaServerQaEndpoint.ANONYMOUS_SIGN_UP })
        assertTrue(store.session != null)
        assertTrue(store.receiptCleared)
    }

    @Test
    fun `live transport fails before the wire when ephemeral credentials are absent`() = runBlocking {
        val wire = FakeWire()
        val store = FakeSecureStore().apply {
            session = ArenaServerQaAuthSession(
                accessToken = "stale-access",
                refreshToken = "stale-refresh",
                userId = "22222222-2222-4222-8222-222222222222",
                expiresAtEpochSeconds = 99_999L,
            )
        }
        val transport = ArenaServerQaSharedPlayerTransport(
            secureStore = store,
            wire = wire,
            onIdentityReplaced = {},
            liveServerMode = true,
            liveCredentials = { null },
            nowEpochMillis = { 1_000_000L },
        )

        assertTrue(runCatching { transport.captureSessionIdentity() }.isFailure)
        assertTrue(wire.requests.isEmpty())
    }

    @Test
    fun `live transport replaces a persisted QA identity with the current handoff account`() = runBlocking {
        val oldUserId = "22222222-2222-4222-8222-222222222222"
        val store = FakeSecureStore().apply {
            session = ArenaServerQaAuthSession(
                accessToken = "old-access",
                refreshToken = "old-refresh",
                userId = oldUserId,
                expiresAtEpochSeconds = 99_999L,
            )
        }
        val wire = FakeWire()
        val transport = ArenaServerQaSharedPlayerTransport(
            secureStore = store,
            wire = wire,
            onIdentityReplaced = {},
            liveServerMode = true,
            liveCredentials = {
                ArenaLiveServerQaCredentials(
                    email = "aq-live-260907abcd-a@example.invalid",
                    password = "temporary-password-123",
                )
            },
            nowEpochMillis = { 1_000_000L },
        )

        assertEquals(USER_ID, transport.captureSessionIdentity())
        assertEquals(ArenaServerQaEndpoint.QA_PASSWORD_SIGN_IN, wire.requests.single().endpoint)
        assertFalse(wire.requests.any { it.accessToken == "old-access" })
        assertTrue(store.receiptCleared)
        assertEquals(USER_ID, store.session?.userId)
    }

    @Test
    fun `wire surface remains limited to auth and fixed profile roster RPCs`() {
        assertEquals(7, ArenaServerQaEndpoint.entries.size)
        assertFalse(isArenaServerQaEndpointAllowed("/rest/v1/user_profiles", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/auth/v1/admin/users", "POST"))
        assertFalse(isArenaServerQaEndpointAllowed("/rest/v1/rpc/sync_ranking_entries", "POST"))
    }

    @Test
    fun `only an exact missing unified function falls back to legacy publication`() = runBlocking {
        val wire = FakeWire(missingUnified = true)
        val transport = ArenaServerQaSharedPlayerTransport(
            secureStore = FakeSecureStore(),
            wire = wire,
            onIdentityReplaced = {},
            liveServerMode = true,
            liveCredentials = { ArenaLiveServerQaCredentials("qa@example.invalid", "password-123") },
            nowEpochMillis = { 1_000_000L },
        )
        transport.captureSessionIdentity()

        assertEquals(null, transport.syncPlayerNetworkProfile("{\"p_characters\":[]}"))
        assertTrue(wire.requests.any { it.endpoint == ArenaServerQaEndpoint.SYNC_PLAYER_NETWORK_PROFILE })
        assertTrue(isMissingPlayerNetworkProfileRpc(
            404,
            "{\"code\":\"PGRST202\",\"message\":\"sync_player_network_profile\"}",
        ))
        assertFalse(isMissingPlayerNetworkProfileRpc(
            403,
            "{\"code\":\"42501\",\"message\":\"sync_player_network_profile permission denied\"}",
        ))
    }

    @Test
    fun `validated unified receipt skips an exact repeat for twenty four hours`() = runBlocking {
        var now = 1_000_000L
        val store = FakeSecureStore()
        val wire = FakeWire()
        val transport = ArenaServerQaSharedPlayerTransport(
            secureStore = store,
            wire = wire,
            onIdentityReplaced = {},
            liveServerMode = true,
            liveCredentials = { ArenaLiveServerQaCredentials("qa@example.invalid", "password-123") },
            nowEpochMillis = { now },
        )
        transport.captureSessionIdentity()
        val body = """{"p_characters":[{"level":10}]}"""

        transport.syncPlayerNetworkProfile(body)
        now += 60_000L
        val repeated = requireNotNull(transport.syncPlayerNetworkProfile(body))

        assertEquals(1, wire.requests.count {
            it.endpoint == ArenaServerQaEndpoint.SYNC_PLAYER_NETWORK_PROFILE
        })
        assertTrue(repeated.contains("\"deduplicated\":true"))
        assertEquals(1_000_000L, store.unifiedReceipt?.serverNowEpochMillis)
        assertEquals(null, store.receipt)
    }

    @Test
    fun `legacy receipt cannot impersonate a unified profile commit`() = runBlocking {
        val now = 1_000_000L
        val body = """{"p_characters":[{"level":10}]}"""
        val store = FakeSecureStore().apply {
            session = ArenaServerQaAuthSession(
                accessToken = "access",
                refreshToken = "refresh",
                userId = USER_ID,
                expiresAtEpochSeconds = 99_999L,
            )
            receipt = SharedPlayerSyncReceipt(USER_ID, sha256(body), now)
        }
        val wire = FakeWire()
        val transport = ArenaServerQaSharedPlayerTransport(
            secureStore = store,
            wire = wire,
            onIdentityReplaced = {},
            nowEpochMillis = { now },
        )

        transport.syncPlayerNetworkProfile(body)

        assertEquals(1, wire.requests.count {
            it.endpoint == ArenaServerQaEndpoint.SYNC_PLAYER_NETWORK_PROFILE
        })
        assertEquals(sha256(body), store.receipt?.payloadHash)
        assertEquals(sha256(body), store.unifiedReceipt?.payloadHash)
    }

    @Test
    fun `changed unified payload waits fifteen minutes before another write`() = runBlocking {
        var now = 1_000_000L
        val store = FakeSecureStore()
        val wire = FakeWire()
        val transport = ArenaServerQaSharedPlayerTransport(
            secureStore = store,
            wire = wire,
            onIdentityReplaced = {},
            liveServerMode = true,
            liveCredentials = { ArenaLiveServerQaCredentials("qa@example.invalid", "password-123") },
            nowEpochMillis = { now },
        )
        transport.captureSessionIdentity()
        transport.syncPlayerNetworkProfile("""{"p_characters":[{"level":10}]}""")

        now += 60_000L
        val deferred = runCatching {
            transport.syncPlayerNetworkProfile("""{"p_characters":[{"level":11}]}""")
        }
        assertTrue(deferred.isFailure)
        assertEquals(1, wire.requests.count {
            it.endpoint == ArenaServerQaEndpoint.SYNC_PLAYER_NETWORK_PROFILE
        })

        now = 1_000_000L + CHANGED_SYNC_COOLDOWN_MILLIS + 1L
        transport.syncPlayerNetworkProfile("""{"p_characters":[{"level":11}]}""")
        assertEquals(2, wire.requests.count {
            it.endpoint == ArenaServerQaEndpoint.SYNC_PLAYER_NETWORK_PROFILE
        })
    }

    private class FakeSecureStore : ArenaServerQaSecureStore {
        var session: ArenaServerQaAuthSession? = null
        var receipt: SharedPlayerSyncReceipt? = null
        var unifiedReceipt: PlayerNetworkProfileSyncReceipt? = null
        var receiptCleared = false
        var unifiedReceiptCleared = false

        override fun readSession() = session
        override fun writeSession(session: ArenaServerQaAuthSession) { this.session = session }
        override fun readSyncReceipt() = receipt
        override fun writeSyncReceipt(receipt: SharedPlayerSyncReceipt) { this.receipt = receipt }
        override fun clearSyncReceipt() { receiptCleared = true; receipt = null }
        override fun readUnifiedSyncReceipt() = unifiedReceipt
        override fun writeUnifiedSyncReceipt(receipt: PlayerNetworkProfileSyncReceipt) {
            unifiedReceipt = receipt
        }
        override fun clearUnifiedSyncReceipt() {
            unifiedReceiptCleared = true
            unifiedReceipt = null
        }
    }

    private class FakeWire(
        private val missingUnified: Boolean = false,
    ) : ArenaServerQaWireExecutor {
        val requests = mutableListOf<ArenaServerQaWireRequest>()

        override suspend fun execute(request: ArenaServerQaWireRequest): String {
            requests += request
            if (request.endpoint == ArenaServerQaEndpoint.SYNC_PLAYER_NETWORK_PROFILE && missingUnified) {
                throw ArenaServerQaHttpException(
                    404,
                    "{\"code\":\"PGRST202\",\"message\":\"sync_player_network_profile\"}",
                )
            }
            return when (request.endpoint) {
                ArenaServerQaEndpoint.ANONYMOUS_SIGN_UP,
                ArenaServerQaEndpoint.ANONYMOUS_TOKEN_REFRESH,
                ArenaServerQaEndpoint.QA_PASSWORD_SIGN_IN ->
                    """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"user":{"id":"$USER_ID"}}"""
                ArenaServerQaEndpoint.ANONYMOUS_USER -> """{"id":"$USER_ID"}"""
                ArenaServerQaEndpoint.SYNC_PLAYER_NETWORK_PROFILE ->
                    """{"accepted":true,"rules_version":1,"synced_count":1,"ranking_synced_count":0,"server_now":1000000}"""
                ArenaServerQaEndpoint.SYNC_PUBLIC_PLAYER_SNAPSHOTS ->
                    """{"rules_version":1,"synced_count":1,"server_now":1000000}"""
                ArenaServerQaEndpoint.GET_DAILY_PUBLIC_PLAYER_ROSTER -> "{}"
            }
        }
    }

    private companion object {
        const val USER_ID = "11111111-1111-4111-8111-111111111111"

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
