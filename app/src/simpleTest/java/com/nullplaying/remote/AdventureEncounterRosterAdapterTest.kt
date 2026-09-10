package com.nullplaying.remote

import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AdventureEncounterRosterAdapterTest {
    @Test
    fun `valid daily DTO converts server expiry to local time and excludes every owned or system row`() {
        val snapshot = applyDailyRankingResponse(
            response(entries = listOf(row("requested"), row("other-owned"), row("local-slot"),
                row("system").copy(systemEntryCode = "GATE"), row("peer")), own = listOf(row("other-owned"))),
            null, "requested", receivedAtEpochMillis = 15_000L,
        )
        val roster = AdventureEncounterRosterAdapter.fromDailyRanking(snapshot, 16_000L, setOf("local-slot"))!!
        assertEquals("daily-1", roster.snapshotId)
        assertEquals(15_000L, roster.receivedAt)
        assertEquals(95_000L, roster.validUntil) // serverNow 20,000 - localReceive 15,000 = +5,000
        assertEquals(listOf("peer"), roster.candidates.map { it.characterId })
        assertEquals(HeroClass.RANGER, roster.candidates.single().heroClass)
    }

    @Test
    fun `unchanged metadata keeps first receipt and cannot reintroduce a replacement pool`() {
        val first = applyDailyRankingResponse(response(listOf(row("original"))), null, "me", 15_000L)
        val again = applyDailyRankingResponse(
            response(listOf(row("replacement"))).copy(unchanged = true, serverNowEpochMillis = 30_000L),
            first, "me", 25_000L,
        )
        val roster = AdventureEncounterRosterAdapter.fromDailyRanking(again, 25_000L)!!
        assertEquals(15_000L, roster.receivedAt)
        assertEquals(95_000L, roster.validUntil)
        assertEquals("original", roster.candidates.single().characterId)
    }

    @Test
    fun `stale retry cache is not an encounter source even while ranking remains visible`() {
        val snapshot = snapshot().copy(nextCheckAtEpochMillis = 160_000L, isFromCache = true)
        assertFalse(AdventureEncounterRosterAdapter.fromDailyRanking(snapshot, 94_999L) == null)
        assertNull(AdventureEncounterRosterAdapter.fromDailyRanking(snapshot, 95_000L))
        assertNull(AdventureEncounterRosterAdapter.fromDailyRanking(snapshot, 120_000L))
    }

    @Test
    fun `future receipt malformed metadata and overflowing offset are rejected`() {
        val base = snapshot()
        assertNull(AdventureEncounterRosterAdapter.fromDailyRanking(base, 14_999L))
        assertNull(AdventureEncounterRosterAdapter.fromDailyRanking(base.copy(snapshotId = ""), 16_000L))
        assertNull(AdventureEncounterRosterAdapter.fromDailyRanking(base.copy(generatedAtEpochMillis = 1L), 16_000L))
        assertNull(AdventureEncounterRosterAdapter.fromDailyRanking(base.copy(serverTimeOffsetMillis = Long.MIN_VALUE), 16_000L))
    }

    @Test
    fun `malformed public candidates and every duplicate ID are discarded deterministically`() {
        val good = remote("good")
        val entries = listOf(
            good, remote("duplicate"), remote("duplicate").copy(displayName = "다른 이름"),
            remote(""), remote("bad name").copy(displayName = " "),
            remote("control").copy(displayName = "여행\n자"),
            remote("long-name").copy(displayName = "가".repeat(25)),
            remote("zero-level").copy(level = 0L), remote("overflow-level").copy(level = Long.MAX_VALUE),
            remote("negative-power").copy(combatPower = -1L), remote("overflow-power").copy(combatPower = Long.MAX_VALUE),
            remote("empty-system-code").copy(systemEntryCode = ""), remote("outside-top").copy(rank = 1_001),
            remote("flagged-mine").copy(isMe = true),
        )
        val roster = AdventureEncounterRosterAdapter.fromDailyRanking(snapshot().copy(entries = entries), 16_000L)!!
        assertEquals(listOf("good"), roster.candidates.map { it.characterId })
    }

    @Test
    fun `valid empty pool remains a valid observation and does not fabricate nearby heroes`() {
        val roster = AdventureEncounterRosterAdapter.fromDailyRanking(snapshot().copy(entries = emptyList()), 16_000L)
        assertNotNull(roster)
        assertEquals(emptyList<Any>(), roster!!.candidates)
    }

    @Test
    fun `candidate ordering is stable regardless of ranked response order`() {
        val base = snapshot().copy(entries = listOf(remote("z"), remote("a"), remote("m")))
        assertEquals(
            AdventureEncounterRosterAdapter.fromDailyRanking(base, 16_000L),
            AdventureEncounterRosterAdapter.fromDailyRanking(base.copy(entries = base.entries.reversed()), 16_000L),
        )
    }

    private fun snapshot() = applyDailyRankingResponse(response(listOf(row("peer"))), null, "me", 15_000L)

    private fun response(entries: List<CompactLeaderboardRow>, own: List<CompactLeaderboardRow> = emptyList()) =
        DailyLeaderboardResponse(
            snapshotId = "daily-1", settledAtEpochMillis = 10_000L, nextSettlementAtEpochMillis = 100_000L,
            generatedAtEpochMillis = 10_010L, serverNowEpochMillis = 20_000L,
            totalParticipants = entries.size, entries = entries, ownEntries = own,
        )

    private fun row(id: String) = CompactLeaderboardRow(
        rankNumber = 1L, listIndex = 1L, characterId = id, displayName = "길 위의 여행자",
        heroClass = "RANGER", level = 20L, combatPower = 50L, achievedAtEpochMillis = 9_000L,
    )

    private fun remote(id: String) = row(id).toRemote("me")
}
