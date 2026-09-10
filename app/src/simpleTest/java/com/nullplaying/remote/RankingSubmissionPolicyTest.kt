package com.nullplaying.remote

import com.nullplaying.engine.SimpleGameEngine
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingSubmissionPolicyTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `accepted combat power ceiling scales with level`() {
        assertEquals(42L, maximumAcceptedRankingCombatPower(1L))
        assertEquals(49L, maximumAcceptedRankingCombatPower(2L))
        assertEquals(57L, maximumAcceptedRankingCombatPower(3L))
        assertEquals(257L, maximumAcceptedRankingCombatPower(20L))
        assertEquals(615L, maximumAcceptedRankingCombatPower(50L))
        assertEquals(1_216L, maximumAcceptedRankingCombatPower(100L))
        assertEquals(2_416L, maximumAcceptedRankingCombatPower(200L))
        assertEquals(12_016L, maximumAcceptedRankingCombatPower(1_000L))
        assertEquals(120_016L, maximumAcceptedRankingCombatPower(10_000L))
        assertEquals(0L, maximumAcceptedRankingCombatPower(0L))
        assertEquals(0L, maximumAcceptedRankingCombatPower(10_001L))
    }

    @Test
    fun `ceiling matches the current theoretical displayed maximum`() {
        listOf(20L, 50L, 100L, 200L, 1_000L, 10_000L).forEach { level ->
            val equipmentBenchmark = engine.expectedEquipmentCombatPower(level)
            val maximumStatPower = (equipmentBenchmark * 135L + 50L) / 100L
            val maximumEquipmentPower = engine.lootEquipmentPowerForRoll(
                level = level,
                rarity = "신화",
                roll = 11,
            )
            val theoreticalGeneratedPower = maximumStatPower + maximumEquipmentPower

            assertTrue(
                "level=$level theoretical=$theoreticalGeneratedPower",
                maximumAcceptedRankingCombatPower(level) == theoreticalGeneratedPower,
            )
        }
    }

    @Test
    fun `ranking uploads wait five minutes after a successful sync`() {
        val syncedAt = 1_000_000L

        assertFalse(isRankingSyncAllowed(syncedAt, syncedAt + RANKING_SYNC_COOLDOWN_MILLIS - 1L))
        assertTrue(isRankingSyncAllowed(syncedAt, syncedAt + RANKING_SYNC_COOLDOWN_MILLIS))
        assertTrue(isRankingSyncAllowed(0L, syncedAt))
    }

    @Test
    fun `short background switches are recorded without a five minute grace period`() {
        val lifecycle = SessionLogLifecycle()
        lifecycle.foregrounded()
        assertTrue(lifecycle.backgrounded { true })
        lifecycle.foregrounded()
        assertTrue(lifecycle.backgrounded { true })
    }

    @Test
    fun `verified unified server time suppresses only the exact committed ranking payload`() {
        val verifiedServerNow = 9_876L
        val identical = unifiedRankingReceiptMutation("payload-a", "payload-a", verifiedServerNow)
        assertEquals("payload-a", identical.lastPayload)
        assertEquals(verifiedServerNow, identical.lastSuccessfulAtEpochMillis)
        assertEquals(null, identical.pendingPayload)

        val changed = unifiedRankingReceiptMutation("payload-b", "payload-a", verifiedServerNow)
        assertEquals("payload-b", changed.pendingPayload)
        assertEquals(verifiedServerNow, changed.lastSuccessfulAtEpochMillis)
    }

    @Test
    fun `queueing the last committed ranking never removes a different newer pending payload`() {
        assertEquals(
            "newer-pending",
            rankingPendingPayloadAfterQueue(
                lastSuccessfulPayload = "last-committed",
                pendingPayload = "newer-pending",
                candidatePayload = "last-committed",
            ),
        )
        assertEquals(
            null,
            rankingPendingPayloadAfterQueue(
                lastSuccessfulPayload = "last-committed",
                pendingPayload = "last-committed",
                candidatePayload = "last-committed",
            ),
        )
        assertEquals(
            "latest-candidate",
            rankingPendingPayloadAfterQueue(
                lastSuccessfulPayload = "last-committed",
                pendingPayload = "older-pending",
                candidatePayload = "latest-candidate",
            ),
        )
        assertEquals(
            null,
            rankingPendingPayloadAfterQueue(
                lastSuccessfulPayload = null,
                pendingPayload = null,
                candidatePayload = "[]",
            ),
        )
    }

    @Test
    fun `legacy deletion evidence recovers empty profile while a new empty account stays local`() {
        assertFalse(
            shouldRecoverLegacyEmptyPlayerNetworkProfile(
                characterCount = 0,
                pendingRankingPayload = null,
                lastRankingHasEntries = false,
                hasLegacySharedReceipt = false,
            ),
        )
        assertTrue(
            shouldRecoverLegacyEmptyPlayerNetworkProfile(
                characterCount = 0,
                pendingRankingPayload = "[]",
                lastRankingHasEntries = false,
                hasLegacySharedReceipt = false,
            ),
        )
        assertTrue(
            shouldRecoverLegacyEmptyPlayerNetworkProfile(
                characterCount = 0,
                pendingRankingPayload = null,
                lastRankingHasEntries = true,
                hasLegacySharedReceipt = false,
            ),
        )
        assertFalse(
            shouldRecoverLegacyEmptyPlayerNetworkProfile(
                characterCount = 1,
                pendingRankingPayload = "[]",
                lastRankingHasEntries = true,
                hasLegacySharedReceipt = true,
            ),
        )
    }

    @Test
    fun `foreground unified commit followed by ranking queue leaves no legacy write`() {
        val rankingPayload = "[{\"slot_id\":1,\"level\":20}]"
        val afterUnified = unifiedRankingReceiptMutation(
            pendingPayload = rankingPayload,
            unifiedPayload = rankingPayload,
            verifiedServerNowEpochMillis = 8_000L,
        )
        val afterForegroundQueue = rankingPendingPayloadAfterQueue(
            lastSuccessfulPayload = afterUnified.lastPayload,
            pendingPayload = afterUnified.pendingPayload,
            candidatePayload = rankingPayload,
        )
        var legacyWriteCalls = 0
        if (afterForegroundQueue != null) legacyWriteCalls += 1

        assertEquals(null, afterForegroundQueue)
        assertEquals(0, legacyWriteCalls)
    }

    @Test
    fun `unified contract preserves changed pending ranking without a doomed legacy write`() {
        assertFalse(
            shouldAttemptLegacyRankingWrite(
                sharedPlayerRemoteEnabled = true,
                evidence = PlayerNetworkProfileCapabilityEvidence(
                    PlayerNetworkProfileCapability.UNIFIED,
                ),
                pendingPayload = "changed-ranking",
            ),
        )
        assertTrue(
            shouldAttemptLegacyRankingWrite(
                sharedPlayerRemoteEnabled = false,
                evidence = PlayerNetworkProfileCapabilityEvidence(
                    PlayerNetworkProfileCapability.UNKNOWN,
                ),
                pendingPayload = "changed-ranking",
            ),
        )
        assertFalse(
            shouldAttemptLegacyRankingWrite(
                sharedPlayerRemoteEnabled = false,
                evidence = PlayerNetworkProfileCapabilityEvidence(
                    PlayerNetworkProfileCapability.UNKNOWN,
                ),
                pendingPayload = null,
            ),
        )
    }

    @Test
    fun `transient unified failure keeps ranking pending and performs zero legacy writes`() {
        val pending = "changed-ranking"
        val allowed = shouldAttemptLegacyRankingWrite(
            sharedPlayerRemoteEnabled = true,
            evidence = PlayerNetworkProfileCapabilityEvidence(
                PlayerNetworkProfileCapability.UNKNOWN,
            ),
            pendingPayload = pending,
        )
        assertFalse(allowed)
        assertEquals("changed-ranking", pending)

        assertFalse(
            shouldAttemptLegacyRankingWrite(
                sharedPlayerRemoteEnabled = true,
                evidence = PlayerNetworkProfileCapabilityEvidence(
                    PlayerNetworkProfileCapability.LEGACY_RPC_MISSING,
                ),
                pendingPayload = pending,
            ),
        )
        assertTrue(
            shouldAttemptLegacyRankingWrite(
                sharedPlayerRemoteEnabled = true,
                evidence = PlayerNetworkProfileCapabilityEvidence(
                    capability = PlayerNetworkProfileCapability.LEGACY_SHARED_COMMITTED,
                    committedLegacyRankingPayload = pending,
                ),
                pendingPayload = pending,
            ),
        )
        assertFalse(
            shouldAttemptLegacyRankingWrite(
                sharedPlayerRemoteEnabled = true,
                evidence = PlayerNetworkProfileCapabilityEvidence(
                    capability = PlayerNetworkProfileCapability.LEGACY_SHARED_COMMITTED,
                    committedLegacyRankingPayload = "older-ranking",
                ),
                pendingPayload = pending,
            ),
        )
    }

    @Test
    fun `exact missing capability is account isolated and reprobed after twenty four hours`() {
        val detectedAt = 1_000L
        val receipt = LegacyPlayerNetworkProfileCapabilityReceipt(
            userId = "account-a",
            detectedAtEpochMillis = detectedAt,
            lastPublishedUnifiedPayloadHash = "profile-a",
            lastPublishedRankingPayload = "ranking-a",
            lastPublishedAtEpochMillis = detectedAt,
        )
        assertTrue(
            isLegacyPlayerNetworkProfileCapabilityActive(
                receipt,
                "account-a",
                detectedAt + UNCHANGED_SYNC_INTERVAL_MILLIS - 1L,
            ),
        )
        assertFalse(
            isLegacyPlayerNetworkProfileCapabilityActive(
                receipt,
                "account-a",
                detectedAt + UNCHANGED_SYNC_INTERVAL_MILLIS,
            ),
        )
        assertFalse(isLegacyPlayerNetworkProfileCapabilityActive(receipt, "account-b", detectedAt + 1L))
        assertTrue(isLegacyPlayerNetworkProfileCapabilityActive(receipt, "account-a", detectedAt - 1L))
    }

    @Test
    fun `validated unified receipt wins over a coexisting stale legacy capability`() {
        val unified = PlayerNetworkProfileSyncReceipt(
            userId = "account-a",
            payloadHash = "unified-profile",
            syncedAtEpochMillis = 2_000L,
            serverNowEpochMillis = 2_000L,
        )
        val legacy = LegacyPlayerNetworkProfileCapabilityReceipt(
            userId = "account-a",
            detectedAtEpochMillis = 1_000L,
            lastPublishedUnifiedPayloadHash = "legacy-profile",
            lastPublishedRankingPayload = "legacy-ranking",
            lastPublishedAtEpochMillis = 1_000L,
        )

        assertEquals(
            PlayerNetworkProfileCapabilityEvidence(PlayerNetworkProfileCapability.UNIFIED),
            persistedPlayerNetworkProfileCapabilityEvidence(
                unifiedReceipt = unified,
                legacyReceipt = legacy,
                userId = "account-a",
                nowEpochMillis = 2_500L,
            ),
        )
    }

    @Test
    fun `legacy capability skips unchanged payload and defers changed payload locally`() {
        val publishedAt = 1_000L
        val receipt = LegacyPlayerNetworkProfileCapabilityReceipt(
            userId = "account-a",
            detectedAtEpochMillis = publishedAt,
            lastPublishedUnifiedPayloadHash = "profile-a",
            lastPublishedRankingPayload = "ranking-a",
            lastPublishedAtEpochMillis = publishedAt,
        )
        assertFalse(
            shouldPublishDuringLegacyPlayerNetworkProfileCapability(
                receipt,
                "account-a",
                "profile-a",
                publishedAt + 5L * 60L * 1_000L,
            ),
        )
        assertFalse(
            shouldPublishDuringLegacyPlayerNetworkProfileCapability(
                receipt,
                "account-a",
                "profile-b",
                publishedAt + CHANGED_SYNC_COOLDOWN_MILLIS - 1L,
            ),
        )
        assertTrue(
            shouldPublishDuringLegacyPlayerNetworkProfileCapability(
                receipt,
                "account-a",
                "profile-b",
                publishedAt + CHANGED_SYNC_COOLDOWN_MILLIS,
            ),
        )
    }

    @Test
    fun `completed empty roster tombstone clears only its exact queued mutation`() {
        val tombstone = "[]"
        assertEquals(
            null,
            pendingPlayerNetworkProfileAfterSuccess(tombstone, tombstone),
        )
        assertEquals(
            "[new-character]",
            pendingPlayerNetworkProfileAfterSuccess("[new-character]", tombstone),
        )
    }

    @Test
    fun `foreground reconciliation replaces a stale queued roster without creating a fresh queue`() {
        assertEquals(
            "[current-profile]",
            pendingPlayerNetworkProfileAfterForegroundReconcile(
                currentPendingPayload = "[]",
                currentCompletePayload = "[current-profile]",
            ),
        )
        assertEquals(
            "[]",
            pendingPlayerNetworkProfileAfterForegroundReconcile(
                currentPendingPayload = "[stale-profile]",
                currentCompletePayload = "[]",
            ),
        )
        assertEquals(
            null,
            pendingPlayerNetworkProfileAfterForegroundReconcile(
                currentPendingPayload = null,
                currentCompletePayload = "[]",
            ),
        )
    }

    @Test
    fun `daily ranking response accepts its byte boundary and rejects one byte more`() {
        val exact = ByteArray(MAX_DAILY_RANKING_RESPONSE_BYTES) { 'x'.code.toByte() }
        assertEquals(
            MAX_DAILY_RANKING_RESPONSE_BYTES,
            readBoundedUtf8Response(
                ByteArrayInputStream(exact),
                MAX_DAILY_RANKING_RESPONSE_BYTES,
            ).toByteArray(Charsets.UTF_8).size,
        )
        val oversized = ByteArray(MAX_DAILY_RANKING_RESPONSE_BYTES + 1) { 'x'.code.toByte() }
        assertTrue(runCatching {
            readBoundedUtf8Response(
                ByteArrayInputStream(oversized),
                MAX_DAILY_RANKING_RESPONSE_BYTES,
            )
        }.isFailure)
    }
}
