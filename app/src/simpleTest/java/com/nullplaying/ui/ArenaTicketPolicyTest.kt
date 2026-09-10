package com.nullplaying.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaTicketPolicyTest {
    @Test
    fun `arena entry policy uses five capacity ten minute ticks and one daily refill`() {
        assertEquals(5, BATTLE_ENTRY_CAPACITY)
        assertEquals(10, BATTLE_ENTRY_DAILY_LIMIT)
        assertEquals(600_000L, BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS)
        assertEquals(3_000_000L, BATTLE_ENTRY_RECOVERY_MILLIS)
        assertEquals(
            BATTLE_ENTRY_RECOVERY_MILLIS,
            BATTLE_ENTRY_CAPACITY * BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
        )
        assertEquals(6, BATTLE_ENTRY_RECOVERY_POLICY_VERSION)
        assertEquals(1, BATTLE_REWARDED_REFILL_DAILY_LIMIT)
        assertEquals(BattleEntryRecoveryState(5, 0L), refillBattleEntries())
    }

    @Test
    fun `offline recovery grants one entry every ten minutes and fills in fifty minutes`() {
        val anchor = 1_000_000L

        assertEquals(
            BattleEntryRecoveryState(0, anchor),
            recoverBattleEntries(0, anchor, anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS - 1L),
        )
        assertEquals(
            BattleEntryRecoveryState(1, anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS),
            recoverBattleEntries(0, anchor, anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS),
        )
        assertEquals(
            BattleEntryRecoveryState(3, anchor + 3L * BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS),
            recoverBattleEntries(0, anchor, anchor + 3L * BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS),
        )
        assertEquals(
            BattleEntryRecoveryState(BATTLE_ENTRY_CAPACITY, 0L),
            recoverBattleEntries(0, anchor, anchor + BATTLE_ENTRY_RECOVERY_MILLIS),
        )
        val oneMillisBeforeFull = recoverBattleEntries(
            0,
            anchor,
            anchor + BATTLE_ENTRY_RECOVERY_MILLIS - 1L,
        )
        assertEquals(
            BattleEntryRecoveryState(
                BATTLE_ENTRY_CAPACITY - 1,
                anchor + (BATTLE_ENTRY_CAPACITY - 1L) * BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            ),
            oneMillisBeforeFull,
        )
        assertEquals(
            1L,
            battleEntryRecoveryRemainingMillis(
                oneMillisBeforeFull,
                anchor + BATTLE_ENTRY_RECOVERY_MILLIS - 1L,
            ),
        )

        val partial = recoverBattleEntries(1, anchor, anchor + 20L * 60L * 1_000L)
        assertEquals(BattleEntryRecoveryState(3, anchor + 20L * 60L * 1_000L), partial)
        assertEquals(BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS, battleEntryRecoveryRemainingMillis(
            partial,
            anchor + 20L * 60L * 1_000L,
        ))

        val rewardedFull = BattleEntryRecoveryState(BATTLE_ENTRY_CAPACITY, anchor)
        assertEquals(
            rewardedFull,
            recoverBattleEntries(
                BATTLE_ENTRY_CAPACITY,
                anchor,
                anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS - 1L,
            ),
        )
        assertEquals(
            refillBattleEntries(),
            recoverBattleEntries(
                BATTLE_ENTRY_CAPACITY,
                anchor,
                anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            ),
        )
    }

    @Test
    fun `spending starts one recovery clock and never grants from a backward clock`() {
        val now = 2_000_000L
        val first = spendBattleEntry(BATTLE_ENTRY_CAPACITY, 0L, now)
        assertTrue(first.accepted)
        assertEquals(BattleEntryRecoveryState(4, now), first.state)

        val second = spendBattleEntry(
            remaining = first.state.remaining,
            recoveryStartedAtMillis = first.state.recoveryStartedAtMillis,
            nowMillis = now + 2L * 60L * 1_000L,
        )
        assertTrue(second.accepted)
        assertEquals(BattleEntryRecoveryState(3, now), second.state)

        val afterReward = spendBattleEntry(
            remaining = BATTLE_ENTRY_CAPACITY,
            recoveryStartedAtMillis = now,
            nowMillis = now + 2L * 60L * 1_000L,
        )
        assertTrue(afterReward.accepted)
        assertEquals(BattleEntryRecoveryState(4, now), afterReward.state)

        val empty = spendBattleEntry(0, now, now + 3L * 60L * 1_000L)
        assertFalse(empty.accepted)
        assertEquals(BattleEntryRecoveryState(0, now), empty.state)

        val backwards = recoverBattleEntries(
            remaining = 2,
            recoveryStartedAtMillis = now,
            nowMillis = now - 1L,
        )
        assertEquals(BattleEntryRecoveryState(2, now), backwards)
    }

    @Test
    fun `countdown rounds up and rewarded refill availability resets by day`() {
        assertEquals("10:00", battleEntryRecoveryCountdownLabel(BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS))
        assertEquals("9:59", battleEntryRecoveryCountdownLabel(599_000L))
        assertEquals("0:02", battleEntryRecoveryCountdownLabel(1_001L))
        assertEquals("0:01", battleEntryRecoveryCountdownLabel(1_000L))
        assertEquals("60:01", battleEntryRecoveryCountdownLabel(3_600_001L))
        assertEquals("0:01", battleEntryRecoveryCountdownLabel(1L))
        assertEquals("0:00", battleEntryRecoveryCountdownLabel(0L))
        assertEquals("0:00", battleEntryRecoveryCountdownLabel(-1L))

        val anchor = 1_000_000L
        assertEquals(
            BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            battleEntryRecoveryRemainingMillis(BattleEntryRecoveryState(0, anchor), anchor),
        )
        assertEquals(
            1L,
            battleEntryRecoveryRemainingMillis(
                BattleEntryRecoveryState(0, anchor),
                anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS - 1L,
            ),
        )
        assertEquals(
            BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            battleEntryRecoveryRemainingMillis(
                BattleEntryRecoveryState(0, anchor),
                anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            ),
        )
        assertEquals(
            0L,
            battleEntryRecoveryRemainingMillis(
                BattleEntryRecoveryState(BATTLE_ENTRY_CAPACITY - 1, anchor),
                anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            ),
        )

        assertEquals(1, battleRewardedRefillsUsed(storedDay = 7L, storedCount = 2, today = 7L))
        assertEquals(1, battleRewardedRefillsUsed(storedDay = 7L, storedCount = 8, today = 7L))
        assertEquals(0, battleRewardedRefillsUsed(storedDay = 7L, storedCount = 1, today = 8L))
        assertEquals(1, battleRewardedRefillsUsed(storedDay = 8L, storedCount = 1, today = 7L))
        assertEquals(0, battleRewardedRefillsUsed(storedDay = 7L, storedCount = -1, today = 7L))

        assertTrue(battleRewardedRefillAvailable(entriesRemaining = 0, refillsUsed = 0))
        assertFalse(battleRewardedRefillAvailable(entriesRemaining = 4, refillsUsed = 0))
        assertFalse(battleRewardedRefillAvailable(entriesRemaining = 10, refillsUsed = 0))
        assertFalse(battleRewardedRefillAvailable(entriesRemaining = 0, refillsUsed = 1))
        assertFalse(battleRewardedRefillAvailable(
            entriesRemaining = 0,
            refillsUsed = 0,
            dailyBattlesUsed = BATTLE_ENTRY_DAILY_LIMIT,
        ))

        assertEquals(10, battleDailyEntriesUsed(7L, 19, 7L))
        assertEquals(10, battleDailyEntriesUsed(7L, 99, 7L))
        assertEquals(0, battleDailyEntriesUsed(7L, 19, 8L))
        assertEquals(10, battleDailyEntriesUsed(8L, 19, 7L))
    }
}
