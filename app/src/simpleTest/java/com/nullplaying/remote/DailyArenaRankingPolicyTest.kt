package com.nullplaying.remote

import com.nullplaying.model.HeroClass
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyArenaRankingPolicyTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val settledAt = 1_788_796_800_000L
    private val nextSettlement = settledAt + 4_680_000L
    private val me = "10000000-0000-4000-8000-000000000001"

    @Test
    fun `compact daily contract decodes text season rules and safe own identity`() {
        val response = json.decodeFromString<DailyArenaLeaderboardResponse>(
            """{"snapshot_id":"arena-1","season_id":"1","rules_version":1,
                "settled_at":$settledAt,"next_settlement_at":$nextSettlement,
                "generated_at":${settledAt + 1000},"server_now":${settledAt + 2000},"t":2,
                "e":[${rowJson(me, "account-me", 1, 1100)},
                     ${rowJson(me, "account-other", 2, 1090)}],
                "o":[${rowJson(me, "account-me", 1, 1100)}]}""",
        )
        val snapshot = applyDailyArenaRankingResponse(response, null, me, settledAt + 2_500L)

        assertEquals("1", snapshot.seasonId)
        assertEquals(1, snapshot.rulesVersion)
        assertFalse(snapshot.entries.first().isMe)
        assertTrue(snapshot.ownEntries.single().isMe)
        assertEquals("account-me", snapshot.myEntry?.accountId)
        assertTrue(
            runCatching {
                json.decodeFromString<DailyArenaLeaderboardResponse>(
                    """{"snapshot_id":"arena-1","season_id":1,"rules_version":1,
                        "settled_at":$settledAt,"next_settlement_at":$nextSettlement,
                        "generated_at":${settledAt + 1_000L},"server_now":${settledAt + 2_000L}}""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `negative remote score is clamped before display and cache persistence`() {
        val compact = CompactArenaLeaderboardRow(
            rankNumber = 1,
            listIndex = 0,
            accountId = "account-me",
            characterId = me,
            displayName = "Hero",
            heroClass = HeroClass.WARRIOR.name,
            level = 10,
            score = -7,
            completedBattles = 10,
            wins = 7,
            losses = 2,
            draws = 1,
            achievedAtEpochMillis = settledAt,
        )
        assertEquals(0, compact.toRemote(me, belongsToCurrentAccount = true).score)

        val negativeRemote = row(me, account = "account-me", rank = 1, score = -5)
        assertEquals(0L, CompactArenaLeaderboardRow.fromRemote(negativeRemote).score)
    }

    @Test
    fun `latest local arena score is inserted into the published comparison field`() {
        val firstReceived = row("20000000-0000-4000-8000-000000000007", rank = 7, score = 1_010)
        val secondReceived = row("20000000-0000-4000-8000-000000000002", rank = 2, score = 1_100)
        val settledMine = row(me, account = "account-me", rank = 321, score = 930).copy(isMe = true)
        val source = snapshot(
            entries = listOf(firstReceived, secondReceived),
            ownEntries = listOf(settledMine),
            total = 4_000,
        )

        val display = requireNotNull(buildArenaRankingDisplaySnapshot(source, local(score = 1_150)))

        assertEquals(listOf(me, secondReceived.characterId, firstReceived.characterId), display.entries.map { it.characterId })
        assertEquals(source.totalParticipants, display.totalParticipants)
        assertEquals(1, display.myEntry?.rank)
        assertEquals(1_150, display.myEntry?.score)
        assertTrue(display.myEntry?.isProvisional == true)
        assertEquals(source.snapshotId, display.snapshotId)
        assertEquals(source.settledAtEpochMillis, display.settledAtEpochMillis)
    }

    @Test
    fun `new local results immediately change own rank and record`() {
        val settledMine = row(me, account = "account-me", rank = 3, score = 1_000).copy(isMe = true)
        val source = snapshot(
            entries = listOf(
                row("20000000-0000-4000-8000-000000000001", rank = 1, score = 1_200),
                row("20000000-0000-4000-8000-000000000002", rank = 3, score = 900),
            ),
            ownEntries = listOf(settledMine),
            total = 3,
        )

        val display = requireNotNull(
            buildArenaRankingDisplaySnapshot(
                source,
                local(score = 1_150, wins = 8, losses = 2, draws = 0),
            ),
        )

        assertEquals(2, display.myEntry?.rank)
        assertEquals(1_150, display.myEntry?.score)
        assertEquals(8, display.myEntry?.wins)
        assertTrue(display.myEntry?.isProvisional == true)
    }

    @Test
    fun `newly placed local character receives a provisional rank and participant`() {
        val external = listOf(
            row("20000000-0000-4000-8000-000000000001", rank = 1, score = 1_100),
            row("20000000-0000-4000-8000-000000000002", rank = 2, score = 1_050),
        )
        val source = snapshot(external, emptyList(), total = 2)

        val display = requireNotNull(buildArenaRankingDisplaySnapshot(source, local(score = 1_080)))

        assertEquals(me, display.myEntry?.characterId)
        assertEquals(2, display.myEntry?.rank)
        assertEquals(3, display.totalParticipants)
        assertEquals(listOf(external[0].characterId, me, external[1].characterId), display.entries.map { it.characterId })
    }

    @Test
    fun `inactive account character is not substituted for the requested character`() {
        val inactiveCharacterId = "10000000-0000-4000-8000-000000000002"
        val inactiveOwn = row(inactiveCharacterId, account = "account-me", rank = 18, score = 990)
        val source = snapshot(entries = emptyList(), ownEntries = listOf(inactiveOwn), total = 20)

        val display = requireNotNull(buildArenaRankingDisplaySnapshot(source, local(score = 1_000)))

        assertEquals(me, display.myEntry?.characterId)
        assertEquals(OUTSIDE_DISPLAYED_ARENA_RANK, display.myEntry?.rank)
        assertEquals(21, display.totalParticipants)
        assertEquals(source.ownEntries, listOf(inactiveOwn))
    }

    @Test
    fun `arena rank ties use score and record while achieved time only orders the rows`() {
        val tied = row(
            "20000000-0000-4000-8000-000000000001",
            rank = 1,
            score = 1_100,
            wins = 7,
            losses = 2,
            draws = 1,
            achievedAt = 500L,
        )
        val source = snapshot(entries = listOf(tied), ownEntries = emptyList(), total = 1)

        val display = requireNotNull(buildArenaRankingDisplaySnapshot(source, local(score = 1_100, achievedAt = 100L)))

        assertEquals(listOf(me, tied.characterId), display.entries.map { it.characterId })
        assertEquals(listOf(1, 1), display.entries.map { it.rank })
        assertEquals(1, display.myEntry?.rank)
    }

    @Test
    fun `local score below a truncated server field is shown only as outside`() {
        val top = (1..997).map { rank ->
            row(
                characterId = "20000000-0000-4000-8000-${rank.toString().padStart(12, '0')}",
                rank = rank,
                score = 3_000 - rank,
            )
        }
        val settledMine = row(me, account = "account-me", rank = 4_000, score = 1_000).copy(isMe = true)
        val source = snapshot(entries = top, ownEntries = listOf(settledMine), total = 4_000)

        val display = requireNotNull(buildArenaRankingDisplaySnapshot(source, local(score = 1_010)))

        assertEquals(OUTSIDE_DISPLAYED_ARENA_RANK, display.myEntry?.rank)
        assertEquals(-1, display.myEntry?.listIndex)
        assertEquals(4_000, display.totalParticipants)
        assertEquals(top, display.entries)
    }

    @Test
    fun `local validation failure does not rewrite or hide a valid server settlement`() {
        val serverMine = row(me, account = "account-me", rank = 4, score = 1_030).copy(isMe = true)
        val source = snapshot(entries = emptyList(), ownEntries = listOf(serverMine), total = 10)

        val display = requireNotNull(
            buildArenaRankingDisplaySnapshot(source, local(level = 9, score = 50_000)),
        )

        assertEquals(serverMine, display.myEntry)
        assertEquals(10, display.totalParticipants)
    }

    @Test
    fun `snapshot for another active character is rejected`() {
        val source = snapshot(entries = emptyList(), ownEntries = emptyList(), total = 0).copy(
            requestedCharacterId = "10000000-0000-4000-8000-000000000099",
        )

        assertNull(buildArenaRankingDisplaySnapshot(source, local(score = 1_000)))
    }

    @Test
    fun `level ten standing is eligible while incomplete or inconsistent placement is not`() {
        assertTrue(isValidArenaRankingStanding(local(level = 10, score = 1_000)))
        assertFalse(isValidArenaRankingStanding(local(level = 9, score = 1_000)))
        assertFalse(isValidArenaRankingStanding(local(score = 1_000).copy(completedBattles = 9)))
        assertFalse(isValidArenaRankingStanding(local(score = 1_000).copy(wins = 8, losses = 0)))
        assertFalse(isValidArenaRankingStanding(local(score = 759)))
        assertFalse(isValidArenaRankingStanding(local(score = 1_241)))
    }

    @Test
    fun `unchanged response and persisted cache keep rows and settlement metadata`() {
        val source = snapshot(
            entries = listOf(row("20000000-0000-4000-8000-000000000001", rank = 1, score = 1_100)),
            ownEntries = listOf(row(me, account = "account-me", rank = 2, score = 1_000).copy(isMe = true)),
            total = 2,
        )
        val response = DailyArenaLeaderboardResponse(
            snapshotId = source.snapshotId,
            seasonId = source.seasonId,
            settledAtEpochMillis = settledAt,
            nextSettlementAtEpochMillis = nextSettlement,
            generatedAtEpochMillis = settledAt + 1_000L,
            serverNowEpochMillis = nextSettlement,
            rulesVersion = 1,
            unchanged = true,
        )
        val unchanged = applyDailyArenaRankingResponse(response, source, me, nextSettlement)
        val restored = PersistedArenaRankingCache.fromSnapshot(unchanged).toSnapshot(me)

        assertEquals(source.entries, unchanged.entries)
        assertEquals(source.ownEntries, unchanged.ownEntries)
        assertEquals(nextSettlement + DAILY_RANKING_RETRY_MILLIS, unchanged.nextCheckAtEpochMillis)
        assertEquals(source.snapshotId, restored?.snapshotId)
        assertTrue(restored?.isFromCache == true)
        assertNull(PersistedArenaRankingCache.fromSnapshot(source.copy(rulesVersion = 2)).toSnapshot(me))
        assertNull(PersistedArenaRankingCache.fromSnapshot(source.copy(seasonId = "2")).toSnapshot(me))
        assertTrue(
            runCatching {
                applyDailyArenaRankingResponse(response.copy(seasonId = "2"), source, me, nextSettlement)
            }.isFailure,
        )
    }

    @Test
    fun `open screen waits for settlement and then follows the one minute retry boundary`() {
        val source = snapshot(entries = emptyList(), ownEntries = emptyList(), total = 0)

        assertEquals(
            10_000L,
            dailyArenaRankingRefreshDelayMillis(source, source.nextSettlementAtEpochMillis - 10_000L),
        )
        assertEquals(
            0L,
            dailyArenaRankingRefreshDelayMillis(source, source.nextSettlementAtEpochMillis),
        )
        val delayed = source.copy(nextCheckAtEpochMillis = source.nextSettlementAtEpochMillis + 60_000L)
        assertEquals(
            45_000L,
            dailyArenaRankingRefreshDelayMillis(delayed, source.nextSettlementAtEpochMillis + 15_000L),
        )
        assertNull(dailyArenaRankingRefreshDelayMillis(source.copy(seasonId = "2"), settledAt))
    }

    @Test
    fun `arena open screen and fetch gate share the remote refresh boundary`() {
        val eightHourServerBoundary = settledAt + 8L * 60L * 60L * 1_000L
        val source = snapshot(entries = emptyList(), ownEntries = emptyList(), total = 0).copy(
            fetchedAtEpochMillis = settledAt + 2_000L,
            nextSettlementAtEpochMillis = eightHourServerBoundary,
        )
        val twelveHourPolicy = RankingRefreshPolicy(12L)
        val clientBoundary = source.settledAtEpochMillis + twelveHourPolicy.intervalMillis

        assertTrue(
            isDailyArenaRankingCacheReusable(
                source,
                clientBoundary - 1L,
                refreshPolicy = twelveHourPolicy,
            ),
        )
        assertFalse(
            isDailyArenaRankingCacheReusable(
                source,
                clientBoundary,
                refreshPolicy = twelveHourPolicy,
            ),
        )
        assertEquals(
            1L,
            dailyArenaRankingRefreshDelayMillis(
                source,
                clientBoundary - 1L,
                refreshPolicy = twelveHourPolicy,
            ),
        )
        assertEquals(
            0L,
            dailyArenaRankingRefreshDelayMillis(
                source,
                clientBoundary,
                refreshPolicy = twelveHourPolicy,
            ),
        )
    }

    @Test
    fun `arena download just before cutoff keeps cutoff and one minute publication retry`() {
        val cutoff = settledAt + RankingRefreshPolicy().intervalMillis
        val receivedJustBefore = cutoff - 1_000L
        val source = snapshot(entries = emptyList(), ownEntries = emptyList(), total = 0).copy(
            fetchedAtEpochMillis = receivedJustBefore,
            nextSettlementAtEpochMillis = cutoff,
        )

        assertEquals(
            1L,
            dailyArenaRankingRefreshDelayMillis(
                source,
                cutoff - 1L,
                RankingRefreshPolicy(),
            ),
        )
        assertEquals(
            0L,
            dailyArenaRankingRefreshDelayMillis(source, cutoff, RankingRefreshPolicy()),
        )

        val delayed = source.copy(nextCheckAtEpochMillis = cutoff + DAILY_RANKING_RETRY_MILLIS)
        assertEquals(
            1L,
            dailyArenaRankingRefreshDelayMillis(
                delayed,
                cutoff + DAILY_RANKING_RETRY_MILLIS - 1L,
                RankingRefreshPolicy(),
            ),
        )
        assertEquals(
            0L,
            dailyArenaRankingRefreshDelayMillis(
                delayed,
                cutoff + DAILY_RANKING_RETRY_MILLIS,
                RankingRefreshPolicy(),
            ),
        )
    }

    @Test
    fun `general and arena caches use the same decision table at every allowed policy boundary`() {
        val serverBoundary = settledAt + 8L * 60L * 60L * 1_000L
        val arena = snapshot(entries = emptyList(), ownEntries = emptyList(), total = 0).copy(
            nextSettlementAtEpochMillis = serverBoundary,
        )
        val general = RemoteRankingSnapshot(
            requestedCharacterId = me,
            fetchedAtEpochMillis = settledAt + 2_000L,
            totalParticipants = 0,
            entries = emptyList(),
            myEntry = null,
            snapshotId = "general-1",
            settledAtEpochMillis = settledAt,
            nextSettlementAtEpochMillis = serverBoundary,
            generatedAtEpochMillis = settledAt + 1_000L,
        )
        val observedTimes = listOf(
            settledAt,
            serverBoundary - 1L,
            serverBoundary,
            settledAt + 12L * 60L * 60L * 1_000L,
            settledAt + 24L * 60L * 60L * 1_000L,
        )

        listOf(1L, 8L, 24L).forEach { hours ->
            val policy = RankingRefreshPolicy(hours)
            observedTimes.forEach { now ->
                assertEquals(
                    "hours=$hours now=$now",
                    isDailyRankingCacheReusable(general, now, refreshPolicy = policy),
                    isDailyArenaRankingCacheReusable(arena, now, refreshPolicy = policy),
                )
            }
        }
    }

    private fun snapshot(
        entries: List<RemoteArenaRankingEntry>,
        ownEntries: List<RemoteArenaRankingEntry>,
        total: Int,
    ) = RemoteArenaRankingSnapshot(
        requestedCharacterId = me,
        fetchedAtEpochMillis = settledAt + 2_000L,
        totalParticipants = total,
        entries = entries,
        ownEntries = ownEntries,
        snapshotId = "arena-1",
        seasonId = "1",
        settledAtEpochMillis = settledAt,
        nextSettlementAtEpochMillis = nextSettlement,
        generatedAtEpochMillis = settledAt + 1_000L,
        rulesVersion = 1,
        serverTimeOffsetMillis = 0L,
    )

    private fun local(
        level: Long = 10,
        score: Int,
        wins: Int = 7,
        losses: Int = 2,
        draws: Int = 1,
        achievedAt: Long = 250L,
    ) = ArenaRankingLocalStanding(
        characterId = me,
        displayName = "Local Hero",
        heroClass = HeroClass.WARRIOR,
        level = level,
        score = score,
        completedBattles = wins + losses + draws,
        wins = wins,
        losses = losses,
        draws = draws,
        observedAtEpochMillis = achievedAt,
    )

    private fun row(
        characterId: String,
        account: String = "account-$characterId",
        rank: Int,
        score: Int,
        wins: Int = 7,
        losses: Int = 2,
        draws: Int = 1,
        achievedAt: Long = rank.toLong(),
    ) = RemoteArenaRankingEntry(
        rank = rank,
        listIndex = rank - 1,
        accountId = account,
        characterId = characterId,
        displayName = "Hero $rank",
        heroClass = HeroClass.MAGE,
        level = 10,
        score = score,
        completedBattles = wins + losses + draws,
        wins = wins,
        losses = losses,
        draws = draws,
        achievedAtEpochMillis = achievedAt,
        isMe = false,
    )

    private fun rowJson(characterId: String, account: String, rank: Int, score: Int) =
        """{"r":$rank,"i":${rank - 1},"u":"$account","c":"$characterId",
            "n":"Hero $rank","h":"MAGE","l":10,"p":$score,"b":10,
            "w":7,"x":2,"d":1,"a":${settledAt - rank}}"""
}
