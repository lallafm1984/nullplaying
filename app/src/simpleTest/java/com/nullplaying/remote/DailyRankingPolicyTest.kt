package com.nullplaying.remote

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyRankingPolicyTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val settledAt = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
    private val nextSettlement = settledAt + 3_660_000L

    @Test
    fun `response completing after account replacement cannot restore cleared own rows`() {
        val gate = DailyRankingResponseGate(Any())
        val userId = AtomicReference("account-a")
        val diskOwnRows = AtomicReference("account-a rows")
        val responseMayFinish = CountDownLatch(1)
        val responseFinished = CountDownLatch(1)
        val request = gate.capture(userId.get())
        val responseThread = Thread {
            try {
                if (responseMayFinish.await(5L, TimeUnit.SECONDS)) {
                    gate.withCurrentIdentity(request, userId::get) {
                        diskOwnRows.set("stale account-a response")
                    }
                }
            } finally {
                responseFinished.countDown()
            }
        }
        responseThread.start()
        gate.invalidate {
            userId.set("account-b")
            diskOwnRows.set("account-b cache")
        }
        responseMayFinish.countDown()

        assertTrue(responseFinished.await(5L, TimeUnit.SECONDS))
        assertEquals("account-b cache", diskOwnRows.get())
    }

    @Test
    fun `old request failure cannot overwrite replacement account status`() {
        val gate = DailyRankingResponseGate(Any())
        var userId = "account-a"
        var status = "ready"
        val request = gate.capture(userId)
        gate.invalidate { userId = "account-b"; status = "new account ready" }

        val result = gate.withCurrentIdentity(request, { userId }) { status = "old request failed" }

        assertNull(result)
        assertEquals("new account ready", status)
    }

    @Test
    fun `same-account token refresh preserves eligibility but changed user is rejected`() {
        val gate = DailyRankingResponseGate(Any())
        val request = gate.capture("account-a")

        assertEquals("published", gate.withCurrentIdentity(request, { "account-a" }) { "published" })
        assertNull(gate.withCurrentIdentity(request, { "account-b" }) { "wrong account" })
    }

    @Test
    fun `ranking cache expires at the hourly server refresh boundary`() {
        val snapshot = freshSnapshot(nextSettlement - 1_000L)

        assertTrue(isDailyRankingCacheReusable(snapshot, nextSettlement - 1L))
        assertFalse(isDailyRankingCacheReusable(snapshot, nextSettlement))
        assertFalse(isDailyRankingCacheReusable(snapshot, nextSettlement + 1L))
    }

    @Test
    fun `ranking cache stays usable until the next hourly server refresh`() {
        val snapshot = freshSnapshot(settledAt + 1_000L)

        assertTrue(isDailyRankingCacheReusable(snapshot, nextSettlement - 1L))
        assertFalse(isDailyRankingCacheReusable(snapshot, settledAt - 1L))
    }

    @Test
    fun `remote policy never refreshes before server edition and may safely delay it`() {
        val eightHourBoundary = settledAt + 8L * 60L * 60L * 1_000L
        val receivedAt = settledAt + 2_000L
        val snapshot = applyDailyRankingResponse(
            response(serverNow = receivedAt).copy(nextSettlementAtEpochMillis = eightHourBoundary),
            null,
            "own-1",
            receivedAt,
        )

        assertTrue(
            isDailyRankingCacheReusable(
                snapshot,
                eightHourBoundary - 1L,
                refreshPolicy = RankingRefreshPolicy(1L),
            ),
        )
        assertFalse(
            isDailyRankingCacheReusable(
                snapshot,
                eightHourBoundary,
                refreshPolicy = RankingRefreshPolicy(1L),
            ),
        )
        val twelveHourClientBoundary = settledAt + 12L * 60L * 60L * 1_000L
        assertTrue(
            isDailyRankingCacheReusable(
                snapshot,
                twelveHourClientBoundary - 1L,
                refreshPolicy = RankingRefreshPolicy(12L),
            ),
        )
        assertFalse(
            isDailyRankingCacheReusable(
                snapshot,
                twelveHourClientBoundary,
                refreshPolicy = RankingRefreshPolicy(12L),
            ),
        )
    }

    @Test
    fun `late download does not postpone the next server edition`() {
        val deviceReceivedAt = settledAt - 7L * 60L * 60L * 1_000L
        val serverReceivedAt = settledAt + 2_000L
        val eightHourBoundary = settledAt + 8L * 60L * 60L * 1_000L
        val snapshot = applyDailyRankingResponse(
            response(serverNow = serverReceivedAt).copy(nextSettlementAtEpochMillis = eightHourBoundary),
            null,
            "own-1",
            deviceReceivedAt,
        )

        val clientBoundary = settledAt + 12L * 60L * 60L * 1_000L
        assertTrue(
            isDailyRankingCacheReusable(
                snapshot,
                clientBoundary - 1L,
                refreshPolicy = RankingRefreshPolicy(12L),
            ),
        )
        assertFalse(
            isDailyRankingCacheReusable(
                snapshot,
                clientBoundary,
                refreshPolicy = RankingRefreshPolicy(12L),
            ),
        )
    }

    @Test
    fun `download just before cutoff still checks cutoff and preserves one minute settlement retry`() {
        val eightHourBoundary = settledAt + 8L * 60L * 60L * 1_000L
        val receivedJustBefore = eightHourBoundary - 1_000L
        val snapshot = applyDailyRankingResponse(
            response(serverNow = receivedJustBefore).copy(nextSettlementAtEpochMillis = eightHourBoundary),
            null,
            "own-1",
            receivedJustBefore,
        )

        assertTrue(isDailyRankingCacheReusable(
            snapshot,
            eightHourBoundary - 1L,
            refreshPolicy = RankingRefreshPolicy(),
        ))
        assertFalse(isDailyRankingCacheReusable(
            snapshot,
            eightHourBoundary,
            refreshPolicy = RankingRefreshPolicy(),
        ))

        val delayed = applyDailyRankingResponse(
            response(serverNow = eightHourBoundary, unchanged = true).copy(
                nextSettlementAtEpochMillis = eightHourBoundary,
            ),
            snapshot,
            "own-1",
            eightHourBoundary,
        )
        assertTrue(isDailyRankingCacheReusable(
            delayed,
            eightHourBoundary + DAILY_RANKING_RETRY_MILLIS - 1L,
            refreshPolicy = RankingRefreshPolicy(),
        ))
        assertFalse(isDailyRankingCacheReusable(
            delayed,
            eightHourBoundary + DAILY_RANKING_RETRY_MILLIS,
            refreshPolicy = RankingRefreshPolicy(),
        ))
    }

    @Test
    fun `force refresh cannot bypass a published edition or settlement retry backoff`() {
        val snapshot = freshSnapshot()
        assertTrue(isDailyRankingCacheReusable(snapshot, settledAt + 3_000L, forceRefresh = true))
        assertFalse(isDailyRankingCacheReusable(snapshot, nextSettlement, forceRefresh = true))

        val unchanged = applyDailyRankingResponse(
            response(serverNow = nextSettlement, unchanged = true), snapshot, "own-2", nextSettlement,
        )
        assertTrue(isDailyRankingCacheReusable(unchanged, nextSettlement + 59_999L, forceRefresh = true))
        assertFalse(isDailyRankingCacheReusable(unchanged, nextSettlement + 60_000L, forceRefresh = true))
    }

    @Test
    fun `unchanged metadata preserves full rows own rows and original observation time`() {
        val snapshot = freshSnapshot()
        val metadataOnly = json.decodeFromString<DailyLeaderboardResponse>(
            """{"snapshot_id":"2026-09-05","settled_at":$settledAt,
               "next_settlement_at":$nextSettlement,"generated_at":${settledAt + 500L},
               "server_now":$nextSettlement,"unchanged":true}""",
        )
        val unchanged = applyDailyRankingResponse(metadataOnly, snapshot, "own-2", nextSettlement)

        assertEquals(snapshot.fetchedAtEpochMillis, unchanged.fetchedAtEpochMillis)
        assertEquals(snapshot.totalParticipants, unchanged.totalParticipants)
        assertEquals(snapshot.entries.map { it.characterId }, unchanged.entries.map { it.characterId })
        assertEquals(3, unchanged.ownEntries.size)
        assertEquals(1_200, unchanged.myEntry?.rank)
        assertTrue(unchanged.isFromCache)
        assertEquals(nextSettlement + DAILY_RANKING_RETRY_MILLIS, unchanged.nextCheckAtEpochMillis)
    }

    @Test
    fun `legacy response gatekeepers are removed before ranking snapshot publication`() {
        val gatekeeper = row("gate-1", 1L).copy(systemEntryCode = "RANK_GATE_01")
        val firstPlayer = row("player-1", 2L)
        val mine = row("own-1", 3L)
        val response = response().copy(
            totalParticipants = 3,
            entries = listOf(gatekeeper, firstPlayer, mine),
            ownEntries = listOf(mine),
        )

        val snapshot = applyDailyRankingResponse(response, null, "own-1", settledAt + 2_000L)

        assertEquals(listOf("player-1", "own-1"), snapshot.entries.map { it.characterId })
        assertEquals(listOf(1, 2), snapshot.entries.map { it.rank })
        assertEquals(listOf(0, 1), snapshot.entries.map { it.listIndex })
        assertEquals(2, snapshot.totalParticipants)
        assertEquals(2, snapshot.myEntry?.rank)
        assertTrue(snapshot.entries.none { it.systemEntryCode != null })
    }

    @Test
    fun `legacy persisted gatekeepers are removed on restore and omitted on the next write`() {
        val gatekeeper = row("gate-1", 1L).copy(systemEntryCode = "RANK_GATE_01")
        val firstPlayer = row("player-1", 2L)
        val mine = row("own-1", 3L)
        val legacy = PersistedRankingCache(
            fetchedAtEpochMillis = settledAt + 2_000L,
            totalParticipants = 3,
            entries = listOf(gatekeeper, firstPlayer, mine),
            snapshotId = "legacy-hourly",
            settledAtEpochMillis = settledAt,
            nextSettlementAtEpochMillis = nextSettlement,
            generatedAtEpochMillis = settledAt + 500L,
            ownEntries = listOf(mine),
        )

        val restored = checkNotNull(legacy.toSnapshot("own-1"))
        val rawMine = mine.toRemote("own-1")
        val rewritten = PersistedRankingCache.fromSnapshot(
            RemoteRankingSnapshot(
                requestedCharacterId = "own-1",
                fetchedAtEpochMillis = settledAt + 2_000L,
                totalParticipants = 3,
                entries = listOf(
                    gatekeeper.toRemote("own-1"),
                    firstPlayer.toRemote("own-1"),
                    rawMine,
                ),
                myEntry = rawMine,
                snapshotId = "legacy-hourly",
                settledAtEpochMillis = settledAt,
                nextSettlementAtEpochMillis = nextSettlement,
                generatedAtEpochMillis = settledAt + 500L,
                ownEntries = listOf(rawMine),
            ),
        )

        assertEquals(listOf("player-1", "own-1"), restored.entries.map { it.characterId })
        assertEquals(listOf(1, 2), restored.entries.map { it.rank })
        assertEquals(2, restored.totalParticipants)
        assertEquals(2, restored.myEntry?.rank)
        assertEquals(listOf("player-1", "own-1"), rewritten.entries.map { it.characterId })
        assertEquals(2, rewritten.totalParticipants)
        assertTrue(rewritten.entries.none { it.systemEntryCode != null })
    }

    @Test
    fun `all own slots including ranks outside top survive disk restart and slot switching`() {
        val encoded = json.encodeToString(PersistedRankingCache.fromSnapshot(freshSnapshot()))
        val restoredCache = json.decodeFromString<PersistedRankingCache>(encoded)

        val second = checkNotNull(restoredCache.toSnapshot("own-2"))
        assertEquals(1_200, second.myEntry?.rank)
        assertTrue(second.myEntry?.isMe == true)
        assertFalse(second.entries.any { it.isMe })

        val third = second.forCharacter("own-3")
        assertEquals(4_500, third.myEntry?.rank)
        assertEquals(1, third.ownEntries.count { it.isMe })
        val first = third.forCharacter("own-1")
        assertEquals(2, first.myEntry?.rank)
        assertEquals(1, first.entries.count { it.isMe })
        assertNull(first.forCharacter("new-character").myEntry)
    }

    @Test
    fun `legacy cache without settlement metadata is rejected`() {
        val legacy = """{"fetchedAtEpochMillis":$settledAt,"totalParticipants":1,"entries":[]}"""
        val cache = json.decodeFromString<PersistedRankingCache>(legacy)

        assertNull(cache.toSnapshot("own-1"))
        assertFalse(isDailyRankingCacheReusable(null, settledAt))
    }

    @Test
    fun `unchanged retry backoff and server offset survive restart`() {
        val deviceNow = nextSettlement - 7L * 60L * 60L * 1_000L
        val updated = applyDailyRankingResponse(
            response(serverNow = nextSettlement, unchanged = true), freshSnapshot(), "own-1", deviceNow,
        )
        val encoded = json.encodeToString(PersistedRankingCache.fromSnapshot(updated))
        val restored = checkNotNull(json.decodeFromString<PersistedRankingCache>(encoded).toSnapshot("own-1"))

        assertTrue(isDailyRankingCacheReusable(restored, deviceNow + 59_999L + restored.serverTimeOffsetMillis))
        assertFalse(isDailyRankingCacheReusable(restored, deviceNow + 60_000L + restored.serverTimeOffsetMillis))
        assertEquals(updated.fetchedAtEpochMillis, restored.fetchedAtEpochMillis)
    }

    @Test
    fun `monotonic server clock reaches cutoff independently of local wall clock`() {
        val clock = DailyRankingClock(nextSettlement - 1_000L, observedElapsedRealtimeMillis = 123_000L)
        val snapshot = freshSnapshot()

        assertTrue(isDailyRankingCacheReusable(snapshot, clock.now(123_999L)))
        assertFalse(isDailyRankingCacheReusable(snapshot, clock.now(124_000L)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unchanged response cannot replace a missing full snapshot`() {
        applyDailyRankingResponse(response(unchanged = true), null, "own-1", settledAt + 1_000L)
    }

    @Test
    fun `next settlement replaces previous rows and observation time together`() {
        val nextCutoff = settledAt + 3_600_000L
        val nextResponse = response(serverNow = nextSettlement + 2_000L).copy(
            snapshotId = "utc:$nextCutoff",
            settledAtEpochMillis = nextCutoff,
            nextSettlementAtEpochMillis = nextCutoff + 3_660_000L,
            generatedAtEpochMillis = nextCutoff + 500L,
            ownEntries = listOf(row("own-2", 999L)),
        )
        val updated = applyDailyRankingResponse(nextResponse, freshSnapshot(), "own-2", nextSettlement + 3_000L)

        assertEquals("utc:$nextCutoff", updated.snapshotId)
        assertEquals(nextSettlement + 3_000L, updated.fetchedAtEpochMillis)
        assertEquals(999, updated.myEntry?.rank)
        assertEquals(1, updated.ownEntries.size)
        assertFalse(updated.isFromCache)
    }

    private fun freshSnapshot(receivedAt: Long = settledAt + 2_000L) = applyDailyRankingResponse(
        response(serverNow = receivedAt), null, "own-1", receivedAt,
    )

    private fun response(serverNow: Long = settledAt + 2_000L, unchanged: Boolean = false) = DailyLeaderboardResponse(
        snapshotId = "2026-09-05",
        settledAtEpochMillis = settledAt,
        nextSettlementAtEpochMillis = nextSettlement,
        generatedAtEpochMillis = settledAt + 500L,
        serverNowEpochMillis = serverNow,
        unchanged = unchanged,
        totalParticipants = 5_000,
        entries = if (unchanged) emptyList() else listOf(row("other", 1L), row("own-1", 2L)),
        ownEntries = if (unchanged) emptyList() else listOf(row("own-1", 2L), row("own-2", 1_200L), row("own-3", 4_500L)),
    )

    private fun row(id: String, rank: Long) = CompactLeaderboardRow(
        rankNumber = rank,
        listIndex = rank - 1L,
        characterId = id,
        displayName = id,
        heroClass = "WARRIOR",
        level = 50L,
        combatPower = 500L,
        achievedAtEpochMillis = settledAt - 1_000L,
    )
}
