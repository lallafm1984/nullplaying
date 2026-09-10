package com.nullplaying.remote

import com.nullplaying.model.HeroClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaRankingSubmissionPolicyTest {
    private val json = Json { encodeDefaults = true }
    private val first = standing(
        "10000000-0000-4000-8000-000000000001",
        score = 1_000,
        observedAt = 1_000L,
    )

    @Test
    fun `negative score is rejected before it can enter the upload queue`() {
        val invalid = first.copy(score = -1)

        assertFalse(isValidArenaRankingStanding(invalid))
        assertTrue(
            runCatching {
                arenaRankingPendingAfterQueue(emptyList(), emptyList(), invalid)
            }.isFailure,
        )
    }

    @Test
    fun `queue keeps latest downward score and ignores display-only changes`() {
        val lower = first.copy(score = 988, losses = 3, wins = 6, observedAtEpochMillis = 2_000L)
        val queued = arenaRankingPendingAfterQueue(emptyList(), listOf(first), lower)
        assertEquals(listOf(lower), queued)

        val renamedOnly = first.copy(displayName = "Renamed", observedAtEpochMillis = 9_000L)
        assertTrue(arenaRankingPendingAfterQueue(listOf(first), listOf(first), renamedOnly).isEmpty())
    }

    @Test
    fun `queue keeps at most three characters and success clears only exact payload`() {
        val second = standing("20000000-0000-4000-8000-000000000002", 1_010, 2_000L)
        val third = standing("30000000-0000-4000-8000-000000000003", 1_020, 3_000L)
        val fourth = standing("40000000-0000-4000-8000-000000000004", 1_030, 4_000L)
        val pending = listOf(first, second, third).let {
            arenaRankingPendingAfterQueue(it, emptyList(), fourth)
        }
        assertEquals(listOf(second, third, fourth), pending)

        val changedAfterSend = second.copy(score = 999, losses = 3, wins = 6)
        val remaining = listOf(changedAfterSend, third).filterNot {
            it.characterId == second.characterId && sameArenaRankingServerPayload(it, second)
        }
        assertEquals(listOf(changedAfterSend, third), remaining)
        assertEquals(second, arenaRankingCommittedAfterSuccess(emptyList(), second).single())
    }

    @Test
    fun `client cooldown honors server retry boundary and clock rollback`() {
        val syncedAt = 1_000_000L
        val retryAt = syncedAt + 20_000L
        assertFalse(isArenaRankingSyncAllowed(syncedAt, syncedAt + 1L, retryAt))
        assertFalse(isArenaRankingSyncAllowed(syncedAt, retryAt - 1L, retryAt))
        assertFalse(isArenaRankingSyncAllowed(syncedAt, syncedAt - 1L, 0L))
        assertTrue(
            isArenaRankingSyncAllowed(
                syncedAt,
                syncedAt + ARENA_RANKING_SYNC_COOLDOWN_MILLIS,
                retryAt,
            ),
        )
    }

    @Test
    fun `http 429 body becomes bounded durable retry evidence`() {
        val body = """{"accepted":false,"server_now":2000000,"season_id":"1",
            "rules_version":1,"rate_limited":true,"retry_after_seconds":600}"""
        val parsed = decodeArenaRankingRateLimit(429, body)
        assertEquals(2_000_000L, parsed?.serverNowEpochMillis)
        assertEquals(2_600_000L, arenaRankingRetryNotBefore(2_000_000L, parsed?.retryAfterSeconds))
        assertNull(decodeArenaRankingRateLimit(500, body))
        assertNull(decodeArenaRankingRateLimit(429, "{}"))
        assertNull(decodeArenaRankingRateLimit(429, "x".repeat(8 * 1024 + 1)))
    }

    @Test
    fun `terminal invalid payload is suppressed while a changed durable record can queue`() {
        val invalid = response(errorCode = "score_movement_limit")
        assertEquals(
            ArenaRankingSyncDisposition.SUPPRESS_PAYLOAD,
            arenaRankingSyncDisposition(invalid),
        )
        assertTrue(arenaRankingPendingAfterResponse(listOf(first), first, invalid).isEmpty())
        val handled = arenaRankingCommittedAfterSuccess(emptyList(), first)
        assertTrue(arenaRankingPendingAfterQueue(emptyList(), handled, first).isEmpty())

        val nextBattle = first.copy(
            score = 1_012,
            completedBattles = 11,
            wins = 8,
            observedAtEpochMillis = 2_000L,
        )
        assertEquals(listOf(nextBattle), arenaRankingPendingAfterQueue(emptyList(), handled, nextBattle))
    }

    @Test
    fun `season character binding limit remains pending for a later reusable slot`() {
        val limited = response(errorCode = "season_character_limit")

        assertEquals(
            ArenaRankingSyncDisposition.RETRY_LATER,
            arenaRankingSyncDisposition(limited),
        )
        assertEquals(listOf(first), arenaRankingPendingAfterResponse(listOf(first), first, limited))
        assertEquals(
            limited.serverNowEpochMillis + ARENA_RANKING_SYNC_COOLDOWN_MILLIS,
            arenaRankingRetryNotBeforeFor(limited),
        )
    }

    @Test
    fun `offline match ten survives queue reload and is sent before the latest match twenty`() {
        val latest = standing(
            characterId = first.characterId,
            score = 1_120,
            observedAt = 2_000L,
            completedBattles = 20,
            wins = 14,
            losses = 4,
            draws = 2,
        )
        val afterTen = arenaRankingQueueAfterStanding(ArenaRankingQueueState(), emptyList(), first)
        val restarted = json.decodeFromString<ArenaRankingQueueState>(json.encodeToString(afterTen))
        val offlineTwenty = arenaRankingQueueAfterStanding(restarted, emptyList(), latest)

        assertEquals(listOf(first), offlineTwenty.pending)
        assertEquals(listOf(latest), offlineTwenty.deferred)
        assertEquals(listOf(first), offlineTwenty.placementArchive)

        val afterPlacementAccepted = arenaRankingQueueAfterAccepted(offlineTwenty, first)
        assertEquals(listOf(latest), afterPlacementAccepted.pending)
        assertTrue(afterPlacementAccepted.deferred.isEmpty())
        assertEquals(listOf(first), afterPlacementAccepted.placementArchive)

        val replayRejected = arenaRankingQueueAfterSuppressed(offlineTwenty, first)
        assertEquals(listOf(latest), replayRejected.pending)
        assertEquals(listOf(first), replayRejected.placementArchive)
    }

    @Test
    fun `replacement rejection promotes archived placement and defers the rejected latest aggregate`() {
        val latest = standing(
            characterId = first.characterId,
            score = 1_120,
            observedAt = 2_000L,
            completedBattles = 20,
            wins = 14,
            losses = 4,
            draws = 2,
        )
        val source = ArenaRankingQueueState(
            pending = listOf(latest),
            placementArchive = listOf(first),
        )

        assertEquals(
            ArenaRankingSyncDisposition.RECOVER_PLACEMENT,
            arenaRankingSyncDisposition(response(errorCode = "replacement_requires_placement")),
        )
        val recovered = requireNotNull(arenaRankingQueueAfterPlacementRequired(source, latest))
        assertEquals(listOf(first), recovered.pending)
        assertEquals(listOf(latest), recovered.deferred)
        assertEquals(
            recovered,
            arenaRankingQueueAfterStanding(recovered, emptyList(), latest),
        )
        assertEquals(listOf(latest), arenaRankingQueueAfterAccepted(recovered, first).pending)
        assertNull(arenaRankingQueueAfterPlacementRequired(source.copy(placementArchive = emptyList()), latest))
    }

    @Test
    fun `profile rejection stays pending and daily or account limits use long server backoff`() {
        assertEquals(
            ArenaRankingSyncDisposition.RETRY_PROFILE,
            arenaRankingSyncDisposition(response(errorCode = "eligible_owned_profile_required")),
        )
        assertEquals(
            listOf(first),
            arenaRankingPendingAfterResponse(
                listOf(first),
                first,
                response(errorCode = "eligible_owned_profile_required"),
            ),
        )
        val twoDays = response(errorCode = "account_age_limit", retryAfterSeconds = 172_800L)
        assertEquals(ArenaRankingSyncDisposition.RETRY_LATER, arenaRankingSyncDisposition(twoDays))
        assertEquals(listOf(first), arenaRankingPendingAfterResponse(listOf(first), first, twoDays))
        assertEquals(174_800_000L, arenaRankingRetryNotBeforeFor(twoDays))

        val daily = response(errorCode = "daily_match_limit", retryAfterSeconds = 60_000L)
        assertEquals(62_000_000L, arenaRankingRetryNotBeforeFor(daily))
        assertEquals(listOf(first), removeExactArenaRankingPending(listOf(first), first.copy(score = 999)))
    }

    @Test
    fun `trusted sync receipt requires text season and matching rules version`() {
        assertTrue(isTrustedArenaRankingSyncResponse(response(accepted = true)))
        assertFalse(isTrustedArenaRankingSyncResponse(response(accepted = true).copy(seasonId = "")))
        assertFalse(isTrustedArenaRankingSyncResponse(response(accepted = true).copy(seasonId = "2")))
        assertFalse(isTrustedArenaRankingSyncResponse(response(accepted = true).copy(rulesVersion = 2)))
    }

    @Test
    fun `contradictory success or rejection receipt cannot clear a pending standing`() {
        val damagedSuccess = response(accepted = true).copy(
            invalid = true,
            errorCode = "invalid_standing",
        )
        val unexplainedRejection = response()
        val missingLongBackoff = response(errorCode = "account_age_limit")

        assertFalse(isTrustedArenaRankingSyncResponse(damagedSuccess))
        assertFalse(isTrustedArenaRankingSyncResponse(unexplainedRejection))
        assertFalse(isTrustedArenaRankingSyncResponse(missingLongBackoff))
        assertEquals(
            listOf(first),
            arenaRankingPendingAfterResponse(listOf(first), first, damagedSuccess),
        )
        assertEquals(
            listOf(first),
            arenaRankingPendingAfterResponse(listOf(first), first, unexplainedRejection),
        )
    }

    private fun response(
        accepted: Boolean = false,
        errorCode: String? = null,
        retryAfterSeconds: Long? = null,
    ) = ArenaRankingSyncResponse(
        accepted = accepted,
        serverNowEpochMillis = 2_000_000L,
        seasonId = "1",
        rulesVersion = 1,
        invalid = !accepted && errorCode != null,
        errorCode = errorCode,
        retryAfterSeconds = retryAfterSeconds,
    )

    private fun standing(
        characterId: String,
        score: Int,
        observedAt: Long,
        completedBattles: Int = 10,
        wins: Int = 7,
        losses: Int = 2,
        draws: Int = 1,
    ) =
        ArenaRankingLocalStanding(
            characterId = characterId,
            displayName = "Hero",
            heroClass = HeroClass.WARRIOR,
            level = 10,
            score = score,
            completedBattles = completedBattles,
            wins = wins,
            losses = losses,
            draws = draws,
            observedAtEpochMillis = observedAt,
        )
}
