package com.nullplaying.ui

import com.nullplaying.model.HeroClass
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaTrustedClockTest {
    @Test
    fun `daily reset countdown uses the stored ledger UTC boundary`() {
        val ledgerDay = 20_700L
        assertEquals(DAY, arenaDailyResetRemainingMillis(ledgerDay, DAY * ledgerDay))
        assertEquals(DAY - 1_000L, arenaDailyResetRemainingMillis(
            ledgerDay,
            DAY * ledgerDay + 1_000L,
        ))
        assertEquals(1L, arenaDailyResetRemainingMillis(ledgerDay, DAY * (ledgerDay + 1L) - 1L))
        assertEquals(0L, arenaDailyResetRemainingMillis(ledgerDay, DAY * (ledgerDay + 1L)))
        assertEquals(0L, arenaDailyResetRemainingMillis(
            ledgerDay,
            DAY * (ledgerDay + 1L) + 60L * 60L * 1_000L,
        ))
        assertEquals(0L, arenaDailyResetRemainingMillis(Long.MIN_VALUE, DAY * ledgerDay))
    }

    @Test
    fun `server anchor plus elapsed realtime ignores device wall forward and backward edits`() {
        val roster = roster(serverNow = DAY * 20_700L + 1_000L, receivedElapsed = 5_000L)
        val initial = BattleLocalSnapshot(
            entriesRemaining = 0,
            entryRecoveryStartedAtMillis = DAY * 20_700L + 1_000L,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
        )

        val anchored = refreshTrustedBattleEntrySnapshot(
            initial, roster, deviceWallNowMillis = DAY, elapsedRealtimeMillis = 5_000L, bootCount = 7,
        )
        val backwardWall = refreshTrustedBattleEntrySnapshot(
            anchored, roster, deviceWallNowMillis = 1L,
            elapsedRealtimeMillis = 5_000L + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            bootCount = 7,
        )
        val futureWall = refreshTrustedBattleEntrySnapshot(
            anchored, roster, deviceWallNowMillis = Long.MAX_VALUE - 1L,
            elapsedRealtimeMillis = 5_000L + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            bootCount = 7,
        )

        assertTrue(anchored.arenaClockServerAnchored)
        assertEquals(20_700L, anchored.gameEpochDay)
        assertEquals(1, backwardWall.entriesRemaining)
        assertEquals(backwardWall, futureWall)
    }

    @Test
    fun `expired server lease still recovers tickets using same boot elapsed time`() {
        val roster = roster(
            serverNow = DAY * 20_700L + 1_000L,
            receivedElapsed = 5_000L,
            lifetime = BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS - 1L,
        )
        val anchored = refreshTrustedBattleEntrySnapshot(
            BattleLocalSnapshot(
                entriesRemaining = 0,
                entryRecoveryStartedAtMillis = DAY * 20_700L + 1_000L,
                entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
                dailyBattleDay = 20_700L,
                dailyBattlesUsed = BATTLE_ENTRY_DAILY_LIMIT,
            ),
            roster,
            deviceWallNowMillis = DAY * 20_700L,
            elapsedRealtimeMillis = 5_000L,
            bootCount = 7,
        )

        val expired = refreshTrustedBattleEntrySnapshot(
            anchored,
            roster,
            deviceWallNowMillis = DAY * 30_000L,
            elapsedRealtimeMillis = 5_000L + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            bootCount = 7,
        )

        assertEquals(1, expired.entriesRemaining)
        assertEquals(BATTLE_ENTRY_DAILY_LIMIT, expired.dailyBattlesUsed)
        assertEquals(20_700L, expired.gameEpochDay)
        assertEquals(anchored.arenaTrustedEpochMillis + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS, expired.arenaTrustedEpochMillis)
    }

    @Test
    fun `boot change without server anchor cannot create tickets or a new daily allowance`() {
        val stored = BattleLocalSnapshot(
            gameEpochDay = 20_700L,
            entriesRemaining = 0,
            entryRecoveryStartedAtMillis = DAY * 20_700L + 1_000L,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            dailyBattleDay = 20_700L,
            dailyBattlesUsed = BATTLE_ENTRY_DAILY_LIMIT,
            arenaTrustedEpochMillis = DAY * 20_700L + 1_000L,
            arenaTrustedElapsedRealtimeMillis = 50_000L,
            arenaTrustedBootCount = 7,
            arenaClockServerAnchored = true,
        )

        val rebooted = refreshTrustedBattleEntrySnapshot(
            stored,
            roster = null,
            deviceWallNowMillis = DAY * 30_000L,
            elapsedRealtimeMillis = 500L,
            bootCount = 8,
        )

        assertEquals(stored.arenaTrustedEpochMillis, rebooted.arenaTrustedEpochMillis)
        assertEquals(0, rebooted.entriesRemaining)
        assertEquals(BATTLE_ENTRY_DAILY_LIMIT, rebooted.dailyBattlesUsed)
        assertFalse(rebooted.gameEpochDay > stored.gameEpochDay)
    }

    @Test
    fun `roster monotonic anchor from an earlier boot is rejected even when new uptime is larger`() {
        val priorEpoch = DAY * 20_700L + 1_000L
        val stored = BattleLocalSnapshot(
            gameEpochDay = 20_700L,
            entriesRemaining = 0,
            entryRecoveryStartedAtMillis = priorEpoch,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            dailyBattleDay = 20_700L,
            dailyBattlesUsed = BATTLE_ENTRY_DAILY_LIMIT,
            arenaTrustedEpochMillis = priorEpoch,
            arenaTrustedElapsedRealtimeMillis = 1_000L,
            arenaTrustedBootCount = 7,
            arenaClockServerAnchored = true,
        )
        val staleRoster = roster(
            serverNow = priorEpoch,
            receivedElapsed = 1_000L,
            receivedBootCount = 7,
        )

        val rebooted = refreshTrustedBattleEntrySnapshot(
            stored,
            staleRoster,
            deviceWallNowMillis = DAY * 30_000L,
            elapsedRealtimeMillis = 1_000L + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            bootCount = 8,
        )

        assertEquals(priorEpoch, rebooted.arenaTrustedEpochMillis)
        assertEquals(0, rebooted.entriesRemaining)
        assertEquals(BATTLE_ENTRY_DAILY_LIMIT, rebooted.dailyBattlesUsed)
        assertEquals(8, rebooted.arenaTrustedBootCount)
    }

    @Test
    fun `local only clock reads wall once then advances only by same boot monotonic time`() {
        val first = refreshTrustedBattleEntrySnapshot(
            BattleLocalSnapshot(entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION),
            roster = null,
            deviceWallNowMillis = DAY * 20_700L,
            elapsedRealtimeMillis = 1_000L,
            bootCount = 3,
        )
        val next = refreshTrustedBattleEntrySnapshot(
            first,
            roster = null,
            deviceWallNowMillis = DAY * 99_999L,
            elapsedRealtimeMillis = 2_000L,
            bootCount = 3,
        )

        assertEquals(first.arenaTrustedEpochMillis + 1_000L, next.arenaTrustedEpochMillis)
        assertEquals(first.gameEpochDay, next.gameEpochDay)
        assertFalse(next.arenaClockServerAnchored)
    }

    @Test
    fun `one second UI clock requests only recovery-boundary writes over one hundred minutes`() {
        val day = 20_700L
        val anchor = DAY * day + 1_000L
        var snapshot = BattleLocalSnapshot(
            gameEpochDay = day,
            entriesRemaining = 0,
            entryRecoveryStartedAtMillis = anchor,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            dailyBattleDay = day,
            rewardedRefillDay = day,
            arenaTrustedEpochMillis = anchor,
            arenaTrustedElapsedRealtimeMillis = 0L,
            arenaTrustedBootCount = 3,
        )
        var requestedWrites = 0

        for (second in 1L..6_000L) {
            val elapsed = second * 1_000L
            val tick = advanceArenaTicketUiClock(
                snapshot = snapshot,
                roster = null,
                elapsedRealtimeMillis = elapsed,
                bootCount = 3,
            )
            snapshot = tick.snapshot
            if (tick.persistenceRequired) {
                requestedWrites += 1
                snapshot = refreshTrustedBattleEntrySnapshot(
                    snapshot = snapshot,
                    roster = null,
                    deviceWallNowMillis = Long.MAX_VALUE - 1L,
                    elapsedRealtimeMillis = elapsed,
                    bootCount = 3,
                )
            }
        }

        assertEquals(BATTLE_ENTRY_CAPACITY, requestedWrites)
        assertEquals(BATTLE_ENTRY_CAPACITY, snapshot.entriesRemaining)
        assertEquals(0L, snapshot.entryRecoveryStartedAtMillis)
    }

    @Test
    fun `UI clock requests persistence at ticket and UTC day boundaries only`() {
        val day = 20_700L
        val anchor = DAY * day + DAY - BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS
        val initial = BattleLocalSnapshot(
            gameEpochDay = day,
            entriesRemaining = 0,
            entryRecoveryStartedAtMillis = anchor,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            dailyBattleDay = day,
            dailyBattlesUsed = 9,
            rewardedRefillDay = day,
            rewardedRefillsUsed = 1,
            arenaTrustedEpochMillis = anchor,
            arenaTrustedElapsedRealtimeMillis = 1_000L,
            arenaTrustedBootCount = 4,
        )

        val beforeBoundary = advanceArenaTicketUiClock(
            initial,
            roster = null,
            elapsedRealtimeMillis = 1_000L + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS - 1L,
            bootCount = 4,
        )
        val atBoundary = advanceArenaTicketUiClock(
            beforeBoundary.snapshot,
            roster = null,
            elapsedRealtimeMillis = 1_000L + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            bootCount = 4,
        )

        assertFalse(beforeBoundary.persistenceRequired)
        assertTrue(atBoundary.persistenceRequired)
        val persisted = refreshTrustedBattleEntrySnapshot(
            atBoundary.snapshot,
            roster = null,
            deviceWallNowMillis = 1L,
            elapsedRealtimeMillis = 1_000L + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            bootCount = 4,
        )
        assertEquals(day + 1L, persisted.gameEpochDay)
        assertEquals(1, persisted.entriesRemaining)
        assertEquals(0, persisted.dailyBattlesUsed)
        assertEquals(0, persisted.rewardedRefillsUsed)
    }

    @Test
    fun `legacy placed save receives one stable trusted score timestamp`() {
        val trustedNow = DAY * 20_700L + 1_000L
        val anchor = roster(serverNow = trustedNow, receivedElapsed = 5_000L)
        val legacy = BattleLocalSnapshot(
            placementCompleted = BATTLE_PLACEMENT_REQUIRED,
            score = 1_020,
            wins = 7,
            losses = 2,
            draws = 1,
            scoreAchievedAtMillis = 0L,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
        )

        val first = advanceArenaTrustedClock(
            legacy,
            anchor,
            deviceWallNowMillis = 1L,
            elapsedRealtimeMillis = 5_000L,
            bootCount = 7,
        ).snapshot
        val later = advanceArenaTrustedClock(
            first,
            roster(serverNow = trustedNow + 60_000L, receivedElapsed = 65_000L),
            deviceWallNowMillis = Long.MAX_VALUE - 1L,
            elapsedRealtimeMillis = 65_000L,
            bootCount = 7,
        ).snapshot

        assertEquals(trustedNow, first.scoreAchievedAtMillis)
        assertEquals(first.scoreAchievedAtMillis, later.scoreAchievedAtMillis)
    }

    @Test
    fun `score achieved time changes only when durable score changes`() {
        val prior = DAY * 20_700L
        val trustedNow = prior + 60_000L

        assertEquals(prior, arenaScoreAchievedAtMillis(1_000, 1_000, prior, trustedNow))
        assertEquals(trustedNow, arenaScoreAchievedAtMillis(1_000, 1_012, prior, trustedNow))
        assertEquals(trustedNow, arenaScoreAchievedAtMillis(1_000, 1_000, 0L, trustedNow))
    }

    @Test
    fun `missing roster keeps countdown moving and grants exactly once at ten minutes`() {
        val now = DAY * 20_700L + 1_000L
        val initial = refreshTrustedBattleEntrySnapshot(
            BattleLocalSnapshot(
                entriesRemaining = 0,
                entryRecoveryStartedAtMillis = now,
                entryRecoveryPolicyVersion = 5,
            ),
            roster(now, 5_000L), now, 5_000L, 7,
        )
        val tick = advanceArenaTicketUiClock(initial, null, 6_000L, 7)
        assertEquals("9:59", battleEntryRecoveryCountdownLabel(battleEntryRecoveryRemainingMillis(
            BattleEntryRecoveryState(tick.snapshot.entriesRemaining, tick.snapshot.entryRecoveryStartedAtMillis),
            tick.nowEpochMillis,
        )))
        assertFalse(tick.persistenceRequired)
        val recovered = refreshTrustedBattleEntrySnapshot(
            tick.snapshot, null, Long.MAX_VALUE - 1L, 605_000L, 7,
        )
        assertEquals(1, recovered.entriesRemaining)
        assertEquals(recovered, refreshTrustedBattleEntrySnapshot(
            recovered, null, 1L, 605_000L, 7,
        ))
    }

    @Test
    fun `reboot resumes countdown without granting unverified offline time`() {
        val now = DAY * 20_700L + 1_000L
        for (serverAnchored in listOf(false, true)) {
            val old = BattleLocalSnapshot(
                entriesRemaining = 0, entryRecoveryStartedAtMillis = now,
                entryRecoveryPolicyVersion = 5, arenaTrustedEpochMillis = now,
                arenaTrustedElapsedRealtimeMillis = 50_000L, arenaTrustedBootCount = 7,
                arenaClockServerAnchored = serverAnchored,
                dailyBattleDay = 20_700L, dailyBattlesUsed = 10,
                rewardedRefillDay = 20_700L, rewardedRefillsUsed = 1,
            )
            val restored = refreshTrustedBattleEntrySnapshot(old, null, DAY * 30_000L, 500L, 8)
            assertEquals(now, restored.arenaTrustedEpochMillis)
            assertEquals(0, restored.entriesRemaining)
            val resumed = refreshTrustedBattleEntrySnapshot(
                restored, null, DAY * 30_000L, 500L + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS, 8,
            )
            assertEquals(1, resumed.entriesRemaining)
            assertEquals(10, resumed.dailyBattlesUsed)
            assertEquals(1, resumed.rewardedRefillsUsed)
            assertEquals(20_700L, resumed.gameEpochDay)
        }
    }

    @Test
    fun `legacy save lacking boot metadata can resume without using edited wall time`() {
        val now = DAY * 20_700L + 1_000L
        val old = BattleLocalSnapshot(
            entriesRemaining = 0, entryRecoveryStartedAtMillis = now,
            entryRecoveryPolicyVersion = 5,
        )
        val restored = refreshTrustedBattleEntrySnapshot(old, null, DAY * 30_000L, 500L, 8)
        assertEquals(now, restored.arenaTrustedEpochMillis)
        val resumed = advanceArenaTicketUiClock(restored, null, 1_500L, 8)
        assertEquals(now + 1_000L, resumed.nowEpochMillis)
    }

    @Test
    fun `missing roster crosses UTC midnight only after real elapsed time`() {
        val now = DAY * 20_701L - 1_000L
        val stored = refreshTrustedBattleEntrySnapshot(
            BattleLocalSnapshot(
                entriesRemaining = 0, entryRecoveryStartedAtMillis = now,
                entryRecoveryPolicyVersion = 5, dailyBattleDay = 20_700L,
                dailyBattlesUsed = 10, rewardedRefillDay = 20_700L, rewardedRefillsUsed = 1,
            ), roster(now, 5_000L), now, 5_000L, 7,
        )
        val before = refreshTrustedBattleEntrySnapshot(stored, null, DAY * 30_000L, 5_999L, 7)
        assertEquals(10, before.dailyBattlesUsed)
        val midnight = refreshTrustedBattleEntrySnapshot(before, null, 1L, 6_000L, 7)
        assertEquals(20_701L, midnight.gameEpochDay)
        assertEquals(0, midnight.dailyBattlesUsed)
        assertEquals(0, midnight.rewardedRefillsUsed)
        assertEquals(0, midnight.entriesRemaining)
    }

    @Test
    fun `reloaded durable snapshot catches background elapsed time with no roster or extra writes`() {
        val now = DAY * 20_700L + 1_000L
        val saved = refreshTrustedBattleEntrySnapshot(
            BattleLocalSnapshot(
                entriesRemaining = 0, entryRecoveryStartedAtMillis = now,
                entryRecoveryPolicyVersion = 5,
            ), roster(now, 5_000L), now, 5_000L, 7,
        )
        val restored = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(saved)))
        val recovered = refreshTrustedBattleEntrySnapshot(restored, null, 1L, 1_805_000L, 7)
        assertEquals(3, recovered.entriesRemaining)
        assertEquals(now + 1_800_000L, recovered.entryRecoveryStartedAtMillis)
    }

    private fun roster(
        serverNow: Long,
        receivedElapsed: Long,
        lifetime: Long = DAY,
        receivedBootCount: Int = 7,
    ) = PublicPlayerRoster(
        requesterCharacterId = REQUESTER,
        requesterLevel = 10L,
        rosterId = "88888888-8888-4888-8888-888888888888",
        rosterDateUtc = "2026-09-08",
        rulesVersion = 1,
        receivedAtEpochMillis = serverNow,
        validUntilEpochMillis = serverNow + lifetime,
        snapshots = listOf(PublicPlayerSnapshot(
            projectionId = "11111111-1111-4111-8111-111111111111",
            displayName = "Opponent",
            heroClass = HeroClass.RANGER,
            level = 10L,
            combatPower = 50L,
            rulesVersion = 1,
            snapshotVersion = 1,
            stats = PublicPlayerStats(10, 11, 12, 13, 14, 15, 180, 90),
            adventureTraitIds = emptyList(),
        )),
        serverNowAtReceiptEpochMillis = serverNow,
        serverValidUntilEpochMillis = serverNow + lifetime,
        receivedAtMonotonicMillis = receivedElapsed,
        receivedAtBootCount = receivedBootCount,
    )

    private companion object {
        const val DAY = 24L * 60L * 60L * 1_000L
        const val REQUESTER = "99999999-9999-4999-8999-999999999999"
    }
}
