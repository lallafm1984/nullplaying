package com.nullplaying.ui

import com.nullplaying.engine.BattleQaMatchFactory
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.arena.ArenaProgressionCatalog
import com.nullplaying.engine.arena.ArenaProgressionPending
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaProgressionState
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.engine.arena.ArenaSyntheticProfileGrowth
import com.nullplaying.engine.arena.ArenaTraitAllocation
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleSeasonStanding
import com.nullplaying.model.BattleTicketState
import com.nullplaying.model.HeroClass
import com.nullplaying.model.SimpleGameState
import com.nullplaying.remote.ArenaRankingQueueState
import com.nullplaying.remote.arenaRankingQueueAfterStanding
import com.nullplaying.remote.isValidArenaRankingStanding
import com.nullplaying.remote.rememberArenaRankingPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Storage-boundary contracts. These do not simulate Android SharedPreferences commit failures. */
class ArenaProgressionPersistenceTest {
    @Test
    fun `advert rewards are capped by the remaining daily matches and cannot be duplicated`() {
        val day = 20_700L
        val now = day * 86_400_000L + 10_000L
        (0..10).forEach { used ->
            val snapshot = BattleLocalSnapshot(
                gameEpochDay = day, dailyBattleDay = day, dailyBattlesUsed = used,
                entriesRemaining = 0, entryRecoveryStartedAtMillis = now,
                entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
                rewardedRefillDay = day,
            )
            val reward = applyRewardedBattleEntryRefill(snapshot, day, now + 1L, "reward-$used")
            assertEquals(minOf(5, 10 - used), reward.entriesRemaining)
            assertEquals(if (used < 10) 1 else 0, reward.rewardedRefillsUsed)
            assertEquals(now, reward.entryRecoveryStartedAtMillis)
            assertEquals(reward, applyRewardedBattleEntryRefill(reward, day, now + 1L, "reward-$used"))
        }
        var state = BattleLocalSnapshot(
            gameEpochDay = day, dailyBattleDay = day, dailyBattlesUsed = 7,
            entriesRemaining = 0, entryRecoveryStartedAtMillis = now,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            rewardedRefillDay = day,
        )
        state = applyRewardedBattleEntryRefill(state, day, now + 1L, "seven-used")
        assertEquals(3, state.entriesRemaining)
        repeat(3) {
            val spent = spendBattleEntrySnapshot(state, day, now + 2L)
            assertTrue(spent.accepted)
            state = spent.snapshot
        }
        assertEquals(10, state.dailyBattlesUsed)
        assertEquals(0, state.entriesRemaining)
        assertFalse(spendBattleEntrySnapshot(state, day, now + 3L).accepted)
    }

    @Test
    fun `v5 wallet reduction preserves timer records and already used daily allowances`() {
        val now = 10_000_000L
        for (remaining in 0..10) {
            val old = BattleLocalSnapshot(
                entriesRemaining = remaining, entryRecoveryStartedAtMillis = now,
                entryRecoveryPolicyVersion = 5, dailyBattleDay = 20_700L,
                dailyBattlesUsed = 17, rewardedRefillDay = 20_700L, rewardedRefillsUsed = 1,
                lastRewardedRefillRequestId = "old-ad", rewardedRefillRequestIds = listOf("old-ad"),
                score = 912, wins = 8, losses = 9, placementCompleted = 17,
                arenaProgression = ArenaProgressionState(unlocked = true, growthDay = 20_700L, growthEntriesUsed = 20),
            )
            val migrated = refreshBattleEntrySnapshot(old, 20_700L, now + 1_000L)
            assertEquals(minOf(remaining, 5), migrated.entriesRemaining)
            assertEquals(now, migrated.entryRecoveryStartedAtMillis)
            assertEquals(10, migrated.dailyBattlesUsed)
            assertEquals(old.rewardedRefillRequestIds, migrated.rewardedRefillRequestIds)
            assertEquals(1, migrated.rewardedRefillsUsed)
            assertEquals(old.score, migrated.score)
            assertEquals(old.wins, migrated.wins)
            assertEquals(old.losses, migrated.losses)
            assertEquals(old.placementCompleted, migrated.placementCompleted)
            assertEquals(old.arenaProgression, migrated.arenaProgression)
            assertTrue(ArenaProgressionRules.isValid(migrated.arenaProgression))
            assertFalse(spendBattleEntrySnapshot(migrated, 20_700L, now + 1_000L).accepted)
            assertEquals(migrated, refreshBattleEntrySnapshot(migrated, 20_700L, now + 1_000L))
        }
    }

    @Test
    fun `five regular entries plus one five ticket ad refill stop at ten per day`() {
        val day = 20_700L
        val now = 10_000_000L
        var snapshot = refreshBattleEntrySnapshot(BattleLocalSnapshot(), day, now)
        repeat(5) {
            val spent = spendBattleEntrySnapshot(snapshot, day, now + it)
            assertTrue(spent.accepted)
            snapshot = spent.snapshot
        }
        assertEquals(0, snapshot.entriesRemaining)
        snapshot = applyRewardedBattleEntryRefill(snapshot, day, now + 5L, "one-ad")
        assertEquals(5, snapshot.entriesRemaining)
        repeat(5) {
            val spent = spendBattleEntrySnapshot(snapshot, day, now + 6L + it)
            assertTrue(spent.accepted)
            snapshot = spent.snapshot
        }
        assertEquals(10, snapshot.dailyBattlesUsed)
        assertEquals(0, snapshot.entriesRemaining)
        assertFalse(spendBattleEntrySnapshot(snapshot, day, now + 11L).accepted)
        assertFalse(spendBattleEntrySnapshot(snapshot, day, now + 600_000L).accepted)
        assertEquals(1, snapshot.rewardedRefillsUsed)
    }

    @Test
    fun `negative legacy arena scores are repaired before local persistence or ranking recovery`() {
        val raw = """{
            "score":-9,
            "arenaRankingPlacement":{
                "score":-4,
                "wins":7,
                "losses":2,
                "draws":1,
                "observedAtEpochMillis":1000
            }
        }""".trimIndent()

        val decoded = requireNotNull(decodeBattleLocalSnapshot(raw))
        assertEquals(0, decoded.score)
        assertEquals(0, decoded.arenaRankingPlacement?.score)
        assertEquals(0, requireNotNull(decodeBattleLocalSnapshot(
            encodeBattleLocalSnapshot(BattleLocalSnapshot(score = -1)),
        )).score)

        var written: BattleLocalSnapshot? = null
        val persisted = requireNotNull(persistArenaProgression(
            snapshot = BattleLocalSnapshot(score = -3),
            nextProgression = ArenaProgressionState(),
            save = {
                written = it
                true
            },
        ))
        assertEquals(0, persisted.score)
        assertEquals(0, written?.score)

        val standing = requireNotNull(decoded.arenaRankingPlacement).toArenaRankingStanding(
            characterId = "10000000-0000-4000-8000-000000000001",
            displayName = "Hero",
            heroClass = HeroClass.WARRIOR,
            level = 10,
        )
        assertEquals(0, standing.score)
    }

    @Test
    fun `legacy arena data preserves records without retroactive arena experience`() {
        val legacy = """{
            "gameEpochDay":20699,
            "entriesRemaining":1,
            "placementCompleted":84,
            "score":755,
            "wins":28,
            "losses":55,
            "draws":1,
            "condition":"GOOD",
            "history":[]
        }""".trimIndent()

        val decoded = requireNotNull(decodeBattleLocalSnapshot(legacy))
        assertEquals(28, decoded.wins)
        assertEquals(55, decoded.losses)
        assertEquals(755, decoded.score)
        assertFalse(decoded.arenaProgression.unlocked)
        assertEquals(0L, decoded.arenaProgression.totalXp)
        assertTrue(decoded.arenaProgression.allocations.isEmpty())

        val migrated = refreshBattleEntrySnapshot(decoded, today = 20_700L, nowMillis = 5_000_000L)
        assertEquals(BATTLE_ENTRY_CAPACITY, migrated.entriesRemaining)
        assertEquals(0L, migrated.entryRecoveryStartedAtMillis)
        assertEquals(BATTLE_ENTRY_RECOVERY_POLICY_VERSION, migrated.entryRecoveryPolicyVersion)
        assertEquals(decoded.score, migrated.score)
        assertEquals(decoded.arenaProgression, migrated.arenaProgression)

        val opened = ArenaProgressionRules.initialize(decoded.arenaProgression, 10L)
        assertTrue(opened.unlocked)
        assertEquals(0L, opened.totalXp)
        assertEquals(opened, ArenaProgressionRules.initialize(opened, 10L))
        assertEquals(opened, ArenaProgressionRules.initialize(opened, 100L))
    }

    @Test
    fun `pending growth and the settled battle proof survive one serialized snapshot`() {
        val pendingId = "arena-pending-001"
        val progression = ArenaProgressionState(
            unlocked = true,
            totalXp = 300L,
            revision = 7L,
            growthDay = 20_700L,
            growthEntriesUsed = 2,
            pending = ArenaProgressionPending(pendingId, 20_700L, true),
            settledBattleIds = listOf("arena-completed-001"),
        )
        val snapshot = BattleLocalSnapshot(
            gameEpochDay = 20_700L,
            entriesRemaining = 1,
            entryRecoveryStartedAtMillis = 8_000_000L,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            dailyBattleDay = 20_700L,
            dailyBattlesUsed = 9,
            rewardedRefillDay = 20_700L,
            rewardedRefillsUsed = 1,
            lastRewardedRefillRequestId = "reward-request-1",
            rewardedRefillRequestIds = listOf("reward-request-1"),
            placementCompleted = 2,
            score = 1_022,
            wins = 2,
            history = listOf(history(pendingId)),
            arenaProgression = progression,
        )

        val restored = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(snapshot)))
        assertEquals(snapshot, restored)
        assertEquals(pendingId, restored.arenaProgression.pending?.battleId)
        assertTrue(restored.history.any { it.battleId == pendingId })
        assertEquals(300L, restored.arenaProgression.totalXp)
        assertEquals(8_000_000L, restored.entryRecoveryStartedAtMillis)
        assertEquals(9, restored.dailyBattlesUsed)
        assertEquals(1, restored.rewardedRefillsUsed)
        assertEquals("reward-request-1", restored.lastRewardedRefillRequestId)
        assertEquals(listOf("reward-request-1"), restored.rewardedRefillRequestIds)
    }

    @Test
    fun `midnight alone never refills entries and ten minute ticks continue offline`() {
        val anchor = 10_000_000L
        val empty = BattleLocalSnapshot(
            gameEpochDay = 20_700L,
            entriesRemaining = 0,
            entryRecoveryStartedAtMillis = anchor,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
        )

        val beforeInterval = refreshBattleEntrySnapshot(
            empty,
            today = 20_701L,
            nowMillis = anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS - 1L,
        )
        assertEquals(20_701L, beforeInterval.gameEpochDay)
        assertEquals(0, beforeInterval.entriesRemaining)

        val oneRecovered = refreshBattleEntrySnapshot(
            beforeInterval,
            today = 20_701L,
            nowMillis = anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
        )
        assertEquals(1, oneRecovered.entriesRemaining)
        assertEquals(anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            oneRecovered.entryRecoveryStartedAtMillis)

        val afterInterval = refreshBattleEntrySnapshot(
            oneRecovered,
            today = 20_701L,
            nowMillis = anchor + BATTLE_ENTRY_RECOVERY_MILLIS,
        )
        assertEquals(BATTLE_ENTRY_CAPACITY, afterInterval.entriesRemaining)
        assertEquals(0L, afterInterval.entryRecoveryStartedAtMillis)
    }

    @Test
    fun `old recovery policy saves receive one friendly full refill`() {
        listOf(1, 2, 3, 4).forEach { oldPolicyVersion ->
            val old = BattleLocalSnapshot(
                entriesRemaining = 2,
                entryRecoveryStartedAtMillis = 4_000_000L,
                entryRecoveryPolicyVersion = oldPolicyVersion,
            )

            val migrated = refreshBattleEntrySnapshot(old, today = 20_700L, nowMillis = 4_100_000L)
            assertEquals(BATTLE_ENTRY_CAPACITY, migrated.entriesRemaining)
            assertEquals(0L, migrated.entryRecoveryStartedAtMillis)
            assertEquals(BATTLE_ENTRY_RECOVERY_POLICY_VERSION, migrated.entryRecoveryPolicyVersion)

            val spent = spendBattleEntry(
                migrated.entriesRemaining,
                migrated.entryRecoveryStartedAtMillis,
                nowMillis = 5_000_000L,
            )
            assertEquals(BattleEntryRecoveryState(BATTLE_ENTRY_CAPACITY - 1, 5_000_000L), spent.state)
        }

        val currentPolicy = BattleLocalSnapshot(
            entriesRemaining = 2,
            entryRecoveryStartedAtMillis = 4_000_000L,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
        )
        val unchanged = refreshBattleEntrySnapshot(
            currentPolicy,
            today = 20_700L,
            nowMillis = 4_100_000L,
        )
        assertEquals(2, unchanged.entriesRemaining)
        assertEquals(4_000_000L, unchanged.entryRecoveryStartedAtMillis)
    }

    @Test
    fun `rewarded entry refill is idempotent capped and never charges a full bar`() {
        val today = 20_700L
        val anchor = 10_000_000L
        val empty = BattleLocalSnapshot(
            gameEpochDay = today,
            entriesRemaining = 0,
            entryRecoveryStartedAtMillis = anchor,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            rewardedRefillDay = today,
        )

        val adNow = anchor + 2L * 60L * 1_000L
        val first = applyRewardedBattleEntryRefill(empty, today, adNow, "reward-1")
        assertEquals(BATTLE_ENTRY_CAPACITY, first.entriesRemaining)
        assertEquals(1, first.rewardedRefillsUsed)
        assertEquals(anchor, first.entryRecoveryStartedAtMillis)
        assertEquals(listOf("reward-1"), first.rewardedRefillRequestIds)
        assertEquals(first, applyRewardedBattleEntryRefill(first, today, anchor, "reward-1"))
        assertEquals(first, applyRewardedBattleEntryRefill(first, today, anchor, "stale-while-full"))

        val spentAfterAd = spendBattleEntry(
            first.entriesRemaining,
            first.entryRecoveryStartedAtMillis,
            adNow,
        )
        assertEquals(BattleEntryRecoveryState(BATTLE_ENTRY_CAPACITY - 1, anchor), spentAfterAd.state)
        assertEquals(
            refillBattleEntries(),
            recoverBattleEntries(
                spentAfterAd.state.remaining,
                spentAfterAd.state.recoveryStartedAtMillis,
                anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            ),
        )

        val delayedDuplicate = applyRewardedBattleEntryRefill(
            first.copy(entriesRemaining = 0),
            today,
            anchor + 1L,
            "reward-1",
        )
        assertEquals(0, delayedDuplicate.entriesRemaining)
        assertEquals(1, delayedDuplicate.rewardedRefillsUsed)

        var state = first.copy(entriesRemaining = 0, entryRecoveryStartedAtMillis = anchor)
        val capped = applyRewardedBattleEntryRefill(
            state.copy(entriesRemaining = 0, entryRecoveryStartedAtMillis = anchor),
            today,
            anchor + 2L,
            "reward-2",
        )
        assertEquals(BATTLE_REWARDED_REFILL_DAILY_LIMIT, capped.rewardedRefillsUsed)
        assertEquals(0, capped.entriesRemaining)

        val nextDay = applyRewardedBattleEntryRefill(capped, today + 1L, anchor + 4L, "next-day")
        assertEquals(1, nextDay.rewardedRefillsUsed)
        assertEquals(BATTLE_ENTRY_CAPACITY, nextDay.entriesRemaining)
        assertEquals(anchor, nextDay.entryRecoveryStartedAtMillis)

        val naturalDeadline = refreshBattleEntrySnapshot(
            first,
            today = today,
            nowMillis = anchor + BATTLE_ENTRY_RECOVERY_MILLIS,
        )
        assertEquals(BATTLE_ENTRY_CAPACITY, naturalDeadline.entriesRemaining)
        assertEquals(0L, naturalDeadline.entryRecoveryStartedAtMillis)

        val naturalRefillDuringAd = applyRewardedBattleEntryRefill(
            snapshot = empty,
            today = today,
            nowMillis = anchor + BATTLE_ENTRY_RECOVERY_MILLIS,
            requestId = "natural-refill-won",
        )
        assertEquals(BATTLE_ENTRY_CAPACITY, naturalRefillDuringAd.entriesRemaining)
        assertEquals(0L, naturalRefillDuringAd.entryRecoveryStartedAtMillis)
        assertEquals(0, naturalRefillDuringAd.rewardedRefillsUsed)
        assertTrue(naturalRefillDuringAd.rewardedRefillRequestIds.isEmpty())

        val oneNaturalTicketDuringAd = applyRewardedBattleEntryRefill(
            snapshot = empty,
            today = today,
            nowMillis = anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            requestId = "natural-tick-before-earned-callback",
        )
        assertEquals(BATTLE_ENTRY_CAPACITY, oneNaturalTicketDuringAd.entriesRemaining)
        assertEquals(
            anchor + BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS,
            oneNaturalTicketDuringAd.entryRecoveryStartedAtMillis,
        )
        assertEquals(1, oneNaturalTicketDuringAd.rewardedRefillsUsed)

        val rollback = refreshBattleEntrySnapshot(state, today - 1L, anchor + 3L)
        assertEquals(BATTLE_REWARDED_REFILL_DAILY_LIMIT, rollback.rewardedRefillsUsed)
        assertEquals(today, rollback.rewardedRefillDay)
    }

    @Test
    fun `rewarded refill limit is tracked independently for each character`() {
        val today = 20_700L
        val firstAnchor = 10_000_000L
        val secondAnchor = 20_000_000L
        val firstCharacter = BattleLocalSnapshot(
            entriesRemaining = 0,
            entryRecoveryStartedAtMillis = firstAnchor,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            rewardedRefillDay = today,
            rewardedRefillsUsed = BATTLE_REWARDED_REFILL_DAILY_LIMIT,
            rewardedRefillRequestIds = listOf("a"),
            lastRewardedRefillRequestId = "a",
        )
        val secondCharacter = BattleLocalSnapshot(
            entriesRemaining = 0,
            entryRecoveryStartedAtMillis = secondAnchor,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            rewardedRefillDay = today,
        )

        val firstRejected = applyRewardedBattleEntryRefill(
            firstCharacter,
            today,
            firstAnchor + 1L,
            "d",
        )
        val secondAccepted = applyRewardedBattleEntryRefill(
            secondCharacter,
            today,
            secondAnchor + 1L,
            "other-a",
        )

        assertEquals(0, firstRejected.entriesRemaining)
        assertEquals(BATTLE_REWARDED_REFILL_DAILY_LIMIT, firstRejected.rewardedRefillsUsed)
        assertEquals(BATTLE_ENTRY_CAPACITY, secondAccepted.entriesRemaining)
        assertEquals(1, secondAccepted.rewardedRefillsUsed)
        assertEquals(secondAnchor, secondAccepted.entryRecoveryStartedAtMillis)
    }

    @Test
    fun `daily match cap is atomic per character and only resets on a forward day`() {
        val today = 20_700L
        val anchor = 30_000_000L
        val almostCapped = BattleLocalSnapshot(
            entriesRemaining = BATTLE_ENTRY_CAPACITY,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            dailyBattleDay = today,
            dailyBattlesUsed = BATTLE_ENTRY_DAILY_LIMIT - 1,
        )

        val finalEntry = spendBattleEntrySnapshot(almostCapped, today, anchor)
        assertTrue(finalEntry.accepted)
        assertEquals(BATTLE_ENTRY_DAILY_LIMIT, finalEntry.snapshot.dailyBattlesUsed)
        assertEquals(BATTLE_ENTRY_CAPACITY - 1, finalEntry.snapshot.entriesRemaining)

        val rejected = spendBattleEntrySnapshot(finalEntry.snapshot, today, anchor + 1L)
        assertFalse(rejected.accepted)
        assertEquals(finalEntry.snapshot.entriesRemaining, rejected.snapshot.entriesRemaining)
        assertEquals(BATTLE_ENTRY_DAILY_LIMIT, rejected.snapshot.dailyBattlesUsed)

        val rollback = spendBattleEntrySnapshot(finalEntry.snapshot, today - 1L, anchor + 2L)
        assertFalse(rollback.accepted)
        assertEquals(today, rollback.snapshot.dailyBattleDay)
        assertEquals(BATTLE_ENTRY_DAILY_LIMIT, rollback.snapshot.dailyBattlesUsed)

        val nextDay = spendBattleEntrySnapshot(finalEntry.snapshot, today + 1L, anchor + 3L)
        assertTrue(nextDay.accepted)
        assertEquals(today + 1L, nextDay.snapshot.dailyBattleDay)
        assertEquals(1, nextDay.snapshot.dailyBattlesUsed)
        assertEquals(BATTLE_ENTRY_CAPACITY - 2, nextDay.snapshot.entriesRemaining)
    }

    @Test
    fun `daily cap blocks rewarded refill without erasing carried tickets`() {
        val today = 20_700L
        val cappedEmpty = BattleLocalSnapshot(
            entriesRemaining = 0,
            entryRecoveryStartedAtMillis = 40_000_000L,
            entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
            dailyBattleDay = today,
            dailyBattlesUsed = BATTLE_ENTRY_DAILY_LIMIT,
            rewardedRefillDay = today,
        )

        val rejected = applyRewardedBattleEntryRefill(
            cappedEmpty,
            today,
            40_000_001L,
            "over-daily-cap",
        )
        assertEquals(0, rejected.entriesRemaining)
        assertEquals(0, rejected.rewardedRefillsUsed)
        assertTrue(rejected.rewardedRefillRequestIds.isEmpty())

        val carried = refreshBattleEntrySnapshot(
            cappedEmpty.copy(entriesRemaining = 7),
            today + 1L,
            40_000_001L,
        )
        assertEquals(BATTLE_ENTRY_CAPACITY, carried.entriesRemaining)
        assertEquals(0, carried.dailyBattlesUsed)
    }

    @Test
    fun `allocation and completed id ledger survive reload independently of replay history`() {
        val progression = ArenaProgressionState(
            unlocked = true,
            totalXp = 30_500L,
            revision = 11L,
            allocations = listOf(ArenaTraitAllocation("AT9_WARRIOR_A01", 3, 0)),
            growthDay = 20_700L,
            growthEntriesUsed = 3,
            settledBattleIds = (1..20).map { "completed-$it" },
        )
        val snapshot = BattleLocalSnapshot(
            history = (11..20).map { history("completed-$it") },
            arenaProgression = progression,
        )

        val restored = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(snapshot)))
        assertEquals(progression, restored.arenaProgression)
        assertEquals(10, restored.history.size)
        assertEquals(20, restored.arenaProgression.settledBattleIds.size)
        assertNull(restored.arenaProgression.pending)
    }

    @Test
    fun `exact tenth match ranking aggregate survives the same local snapshot reload`() {
        val placement = requireNotNull(
            captureArenaRankingPlacement(
                existing = null,
                completedBattles = 10,
                score = 1_072,
                wins = 7,
                losses = 2,
                draws = 1,
                observedAtEpochMillis = 1_788_796_800_000L,
            ),
        )
        val settled = BattleLocalSnapshot(
            placementCompleted = 10,
            score = placement.score,
            scoreAchievedAtMillis = placement.observedAtEpochMillis,
            wins = placement.wins,
            losses = placement.losses,
            draws = placement.draws,
            arenaRankingPlacement = placement,
        )

        val restarted = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(settled)))
        assertEquals(placement, restarted.arenaRankingPlacement)
        val recoveredStanding = requireNotNull(restarted.arenaRankingPlacement).toArenaRankingStanding(
            characterId = "10000000-0000-4000-8000-000000000001",
            displayName = "Hero",
            heroClass = HeroClass.WARRIOR,
            level = 10,
        )
        assertTrue(isValidArenaRankingStanding(recoveredStanding))
        val rehydratedQueue = rememberArenaRankingPlacement(ArenaRankingQueueState(), recoveredStanding)
        assertEquals(
            listOf(recoveredStanding),
            arenaRankingQueueAfterStanding(rehydratedQueue, emptyList(), recoveredStanding).pending,
        )
        assertEquals(
            placement,
            captureArenaRankingPlacement(
                existing = restarted.arenaRankingPlacement,
                completedBattles = 20,
                score = 1_120,
                wins = 14,
                losses = 4,
                draws = 2,
                observedAtEpochMillis = placement.observedAtEpochMillis + 60_000L,
            ),
        )
    }

    @Test
    fun `updating old score and condition fields with copy retains separate growth ledger`() {
        val progression = ArenaProgressionState(
            unlocked = true,
            totalXp = 1_800L,
            revision = 4L,
            allocations = listOf(ArenaTraitAllocation("AT9_WARRIOR_A01", 2, 0)),
            growthDay = 20_700L,
            growthEntriesUsed = 3,
            pending = ArenaProgressionPending("pending-save", 20_700L, false),
        )
        val old = BattleLocalSnapshot(arenaProgression = progression)
        val otherFieldsUpdated = old.copy(
            score = 1_040,
            condition = "BAD",
            gameEpochDay = 20_701L,
            entriesRemaining = 3,
        )

        val restored = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(otherFieldsUpdated)))
        assertEquals(progression, restored.arenaProgression)
        assertEquals(20_700L, restored.arenaProgression.pending?.issuedDay)
        assertEquals(20_701L, restored.gameEpochDay)
    }

    @Test
    fun `unknown future snapshot fields do not erase current progression`() {
        val snapshot = BattleLocalSnapshot(arenaProgression = ArenaProgressionState(
            unlocked = true,
            totalXp = 600L,
            revision = 2L,
        ))
        val encoded = encodeBattleLocalSnapshot(snapshot)
        val futureCompatible = encoded.dropLast(1) + ",\"futureArenaVisualVersion\":42}"

        assertNotNull(decodeBattleLocalSnapshot(futureCompatible))
        assertEquals(snapshot, decodeBattleLocalSnapshot(futureCompatible))
    }

    @Test
    fun `truncated snapshot is rejected instead of yielding partial awarded growth`() {
        val encoded = encodeBattleLocalSnapshot(BattleLocalSnapshot(arenaProgression =
            ArenaProgressionState(unlocked = true, totalXp = 100L)))

        assertNull(decodeBattleLocalSnapshot(encoded.dropLast(5)))
        assertNull(decodeBattleLocalSnapshot(""))
        assertNull(decodeBattleLocalSnapshot(null))
    }

    @Test
    fun `reloaded reservation pays once only when its own completion arrives`() {
        val opened = ArenaProgressionRules.initialize(ArenaProgressionState(), 10L)
        val reserved = ArenaProgressionRules.reserveBattle(opened, "persisted-result", 20_700L)
        val restored = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(
            BattleLocalSnapshot(arenaProgression = reserved, history = listOf(history("persisted-result"))),
        )))
        assertEquals(0L, restored.arenaProgression.totalXp)

        assertEquals(restored.arenaProgression,
            ArenaProgressionRules.completeBattle(restored.arenaProgression, "unrelated-result"))
        val completed = ArenaProgressionRules.completeBattle(restored.arenaProgression, "persisted-result")
        val completedReload = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(
            restored.copy(arenaProgression = completed),
        ))).arenaProgression

        assertEquals(100L, completedReload.totalXp)
        assertNull(completedReload.pending)
        assertEquals(completedReload,
            ArenaProgressionRules.completeBattle(completedReload, "persisted-result"))
        assertEquals(completedReload,
            ArenaProgressionRules.reserveBattle(completedReload, "persisted-result", 20_701L))
    }

    @Test
    fun `every allowed daily match survives reload with experience and only over-cap practice is zero`() {
        var snapshot = BattleLocalSnapshot(arenaProgression =
            ArenaProgressionRules.initialize(ArenaProgressionState(), 10L))
        repeat(ArenaProgressionRules.DAILY_GROWTH_ENTRIES + 2) { index ->
            val id = "reload-day-battle-$index"
            val reserved = ArenaProgressionRules.reserveBattle(snapshot.arenaProgression, id, 20_700L)
            snapshot = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(snapshot.copy(
                arenaProgression = reserved,
                history = listOf(history(id)) + snapshot.history,
            ))))
            assertEquals(
                index < ArenaProgressionRules.DAILY_GROWTH_ENTRIES,
                snapshot.arenaProgression.pending?.xpEligible,
            )
            snapshot = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(snapshot.copy(
                arenaProgression = ArenaProgressionRules.completeBattle(snapshot.arenaProgression, id),
            ))))
        }

        assertEquals(2_000L, snapshot.arenaProgression.totalXp)
        assertEquals(ArenaProgressionRules.DAILY_GROWTH_ENTRIES, snapshot.arenaProgression.growthEntriesUsed)
        assertEquals(ArenaProgressionRules.DAILY_GROWTH_ENTRIES, snapshot.arenaProgression.settledBattleIds.size)
        assertEquals(0, ArenaProgressionRules.view(snapshot.arenaProgression, 20_700L).growthRemaining)
        assertEquals(
            ArenaProgressionRules.DAILY_GROWTH_ENTRIES,
            ArenaProgressionRules.view(snapshot.arenaProgression, 20_701L).growthRemaining,
        )
    }

    @Test
    fun `legacy three-match quota save remains valid and can earn the remaining official matches`() {
        val legacy = BattleLocalSnapshot(
            gameEpochDay = 20_700L,
            arenaProgression = ArenaProgressionState(
                unlocked = true,
                totalXp = 300L,
                revision = 7L,
                growthDay = 20_700L,
                growthEntriesUsed = 3,
                settledBattleIds = listOf("legacy-one", "legacy-two", "legacy-three"),
            ),
        )
        val restored = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(legacy)))
        assertTrue(ArenaProgressionRules.isValid(restored.arenaProgression))
        assertEquals(
            ArenaProgressionRules.DAILY_GROWTH_ENTRIES - 3,
            ArenaProgressionRules.view(restored.arenaProgression, 20_700L).growthRemaining,
        )

        val reserved = ArenaProgressionRules.reserveBattle(
            restored.arenaProgression,
            "first-after-upgrade",
            20_700L,
        )
        assertEquals(true, reserved.pending?.xpEligible)
        val completed = ArenaProgressionRules.completeBattle(reserved, "first-after-upgrade")
        assertEquals(400L, completed.totalXp)
        assertEquals(4, completed.growthEntriesUsed)
        assertEquals(4, completed.settledBattleIds.size)
    }

    @Test
    fun `saved pending completion across midnight never charges the next day`() {
        val opened = ArenaProgressionRules.initialize(ArenaProgressionState(), 10L)
        val reserved = ArenaProgressionRules.reserveBattle(opened, "yesterday-pending", 20_700L)
        val restored = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(
            BattleLocalSnapshot(gameEpochDay = 20_701L, arenaProgression = reserved),
        ))).arenaProgression
        val completed = ArenaProgressionRules.completeBattle(restored, "yesterday-pending")

        assertEquals(100L, completed.totalXp)
        assertEquals(20_700L, completed.growthDay)
        assertEquals(1, completed.growthEntriesUsed)
        assertEquals(
            ArenaProgressionRules.DAILY_GROWTH_ENTRIES,
            ArenaProgressionRules.view(completed, 20_701L).growthRemaining,
        )
        val todayFirst = ArenaProgressionRules.reserveBattle(completed, "today-first", 20_701L)
        assertEquals(20_701L, todayFirst.growthDay)
        assertEquals(1, todayFirst.growthEntriesUsed)
        assertEquals(true, todayFirst.pending?.xpEligible)
    }

    @Test
    fun `recovery requires the exact already settled battle history proof`() {
        val opened = ArenaProgressionRules.initialize(ArenaProgressionState(), 10L)
        val pending = ArenaProgressionRules.reserveBattle(opened, "recover-only-this", 20_700L)
        val withoutProof = BattleLocalSnapshot(arenaProgression = pending)
        val wrongProof = withoutProof.copy(history = listOf(history("different-battle")))

        assertEquals(withoutProof, recoverArenaProgression(withoutProof))
        assertEquals(wrongProof, recoverArenaProgression(wrongProof))

        val withProof = withoutProof.copy(history = listOf(history("recover-only-this")))
        val recovered = recoverArenaProgression(withProof)
        assertEquals(100L, recovered.arenaProgression.totalXp)
        assertNull(recovered.arenaProgression.pending)
        assertEquals(withProof.history, recovered.history)
        assertEquals(withProof.score, recovered.score)
        assertEquals(recovered, recoverArenaProgression(recovered))
        assertEquals(recovered, recoverArenaProgression(requireNotNull(
            decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(recovered)),
        )))
    }

    @Test
    fun `failed progression commit never returns a publishable new state`() {
        val opened = ArenaProgressionRules.initialize(ArenaProgressionState(), 10L)
        val pending = ArenaProgressionRules.reserveBattle(opened, "commit-failure", 20_700L)
        val snapshot = BattleLocalSnapshot(score = 1_025, wins = 2,
            history = listOf(history("commit-failure")), arenaProgression = pending)
        val next = ArenaProgressionRules.completeBattle(pending, "commit-failure")
        var calls = 0
        var attempted: BattleLocalSnapshot? = null

        val failed = persistArenaProgression(snapshot, next) { proposed ->
            calls++
            attempted = proposed
            false
        }

        assertNull(failed)
        assertEquals(1, calls)
        assertEquals(snapshot.copy(arenaProgression = next), attempted)
        assertEquals(0L, snapshot.arenaProgression.totalXp)
        assertNotNull(snapshot.arenaProgression.pending)
        val retried = persistArenaProgression(snapshot, next) { true }
        assertEquals(snapshot.copy(arenaProgression = next), retried)
        assertEquals(100L, retried?.arenaProgression?.totalXp)
    }

    @Test
    fun `successful allocation commit preserves historical scores tickets and pending independent fields`() {
        val original = BattleLocalSnapshot(
            gameEpochDay = 20_700L,
            entriesRemaining = 2,
            placementCompleted = 17,
            score = 923,
            wins = 9,
            losses = 8,
            condition = "BAD",
            history = listOf(history("old-match")),
            arenaProgression = ArenaProgressionRules.initialize(ArenaProgressionState(), 10L),
        )
        val purchased = ArenaProgressionRules.allocate(
            original.arenaProgression, HeroClass.WARRIOR, 10L,
            ArenaSupportTurnEngine.initialSupportIds(HeroClass.WARRIOR).toSet(),
            ArenaProgressionCatalog.initialTrait(HeroClass.WARRIOR).id, 1, 0,
        )
        assertTrue(purchased.accepted)
        val saved = persistArenaProgression(original, purchased.state) { true }

        assertEquals(original.copy(arenaProgression = purchased.state), saved)
        assertEquals(0L, saved?.arenaProgression?.totalXp)
        assertEquals(1, saved?.arenaProgression?.allocations?.single()?.rank)
    }

    @Test
    fun `prepared battle freezes purchased traits arena level and owned attack stats`() {
        val character = fighter()
        val first = ArenaProgressionCatalog.initialTrait(character.hero.heroClass)
        val progression = ArenaProgressionState(
            unlocked = true,
            totalXp = ArenaProgressionRules.xpForLevel(5),
            revision = 5L,
            allocations = listOf(ArenaTraitAllocation(first.id, 3)))
        val prepared = prepare(character, progression)
        val live = requireNotNull(prepared.first.supportBattle)
        val frozen = live.user
        val originalTraits = frozen.traits.toList()
        val originalAttacks = frozen.fighter.attacks.toList()
        val originalStats = frozen.fighter.stats
        val originalSimulation = live.simulation

        assertEquals(5, frozen.arenaLevel)
        assertEquals(first.id, originalTraits.single().id)
        assertEquals(3, originalTraits.single().rank)
        assertEquals(ArenaProgressionRules.xpForLevel(5), progression.totalXp) // preparation is not entry settlement
        assertNull(progression.pending)
        character.skills.clear()
        character.hero.stats.strength += 100
        val reset = ArenaProgressionRules.reset(progression)
        assertTrue(reset.accepted)
        assertTrue(reset.state.allocations.isEmpty())

        assertEquals(originalTraits, live.user.traits)
        assertEquals(originalAttacks, live.user.fighter.attacks)
        assertEquals(originalStats, live.user.fighter.stats)
        assertEquals(originalSimulation, live.simulation)
    }

    @Test
    fun `npc growth budget depends on earned points not how many player spent`() {
        val character = fighter()
        val traitId = ArenaProgressionCatalog.initialTrait(character.hero.heroClass).id
        val unspent = ArenaProgressionState(
            unlocked = true,
            totalXp = ArenaProgressionRules.xpForLevel(5),
            revision = 1L,
        )
        val invested = unspent.copy(allocations = listOf(ArenaTraitAllocation(traitId, 5)), revision = 2L)
        val first = requireNotNull(prepare(character, unspent).first.supportBattle)
        val second = requireNotNull(prepare(character, invested).first.supportBattle)

        assertTrue(first.user.traits.isEmpty())
        assertEquals(5, second.user.traits.single().rank)
        assertEquals(5, first.opponent.arenaLevel)
        assertEquals(first.opponent, second.opponent)
        assertEquals(5, first.opponent.traits.single().rank)
    }

    @Test
    fun `npc spends only earned budgets and actual owned support conditions across all levels`() {
        HeroClass.entries.forEach { heroClass ->
            listOf(10L, 20L, 100L).forEach { heroLevel ->
                listOf(1, 5, 10, 20, 50, 51, 52, 60, 70, 100).forEach { level ->
                    val traits = arenaNpcTraitAllocation(heroClass, level, heroLevel)
                    val state = ArenaProgressionState(unlocked = true,
                        totalXp = ArenaProgressionRules.xpForLevel(level),
                        allocations = traits.map { ArenaTraitAllocation(it.id, it.rank, it.enhancement) })
                    assertTrue("$heroClass/$heroLevel/$level", ArenaProgressionRules.isValid(state))
                    val view = ArenaProgressionRules.view(state, 0)
                    assertEquals(minOf(level, 50), view.baseSpent)
                    assertTrue(view.enhancementSpent <= maxOf(0, level - 50))
                    assertTrue(traits.all { trait -> ArenaProgressionCatalog.find(trait.id)!!.requiredSupportIds.all {
                        it in com.nullplaying.engine.arena.ArenaSupportCatalog.unlockedIds(heroClass, heroLevel)
                    } })
                }
            }
        }
    }

    @Test
    fun `trait popup names expose all 24 executable traits while class name compatibility remains A01`() {
        HeroClass.entries.forEach { heroClass ->
            val definitions = arenaProgressionUiDefinitions(heroClass)
            assertEquals(ArenaProgressionCatalog.forClass(heroClass), definitions)
            assertEquals(24, definitions.size)
            assertTrue(definitions.all { it.heroClass == heroClass })
            definitions.forEach { definition ->
                assertEquals(definition.nameKo,
                    arenaProgressionTraitName(definition, AppLanguage.KOREAN))
                assertEquals(definition.nameEn,
                    arenaProgressionTraitName(definition, AppLanguage.ENGLISH))
                assertEquals(definition.nameJa,
                    arenaProgressionTraitName(definition, AppLanguage.JAPANESE))
            }
            val initial = definitions.first()
            assertEquals(initial.nameKo, arenaProgressionTraitName(heroClass, AppLanguage.KOREAN))
            assertEquals(initial.nameEn, arenaProgressionTraitName(heroClass, AppLanguage.ENGLISH))
            assertEquals(initial.nameJa, arenaProgressionTraitName(heroClass, AppLanguage.JAPANESE))
        }
    }

    @Test
    fun `every displayed standard trait rank shows the executable final effect value`() {
        HeroClass.entries.forEach { heroClass ->
            val definitions = arenaProgressionUiDefinitions(heroClass)
            assertEquals(ArenaProgressionCatalog.forClass(heroClass), definitions)
            definitions.filterNot { it.isCore }.forEach { definition ->
                (1..5).forEach { rank ->
                    (0..3).forEach { stage ->
                        val amount = String.format(Locale.ROOT, "%.2f", definition.value(rank, stage))
                            .trimEnd('0').trimEnd('.')
                        AppLanguage.entries.forEach { language ->
                            val label = arenaProgressionTraitEffect(definition, rank, stage, language)
                            assertTrue(
                                "${definition.id} rank=$rank stage=$stage $language: " +
                                    "$label should contain $amount",
                                label.contains(amount),
                            )
                        }
                    }
                }
            }
            // Kept only for callers that have not migrated from one trait per class.
            val initial = definitions.first()
            (1..5).forEach { rank ->
                (0..3).forEach { stage ->
                    val amount = String.format(Locale.ROOT, "%.2f", initial.value(rank, stage))
                        .trimEnd('0').trimEnd('.')
                    AppLanguage.entries.forEach { language ->
                        val label = arenaProgressionTraitEffect(heroClass, rank, stage, language)
                        assertTrue("${initial.id} rank=$rank stage=$stage $language: $label should contain $amount",
                            label.contains(amount))
                    }
                }
            }
        }
    }

    @Test
    fun `accuracy and healing labels use catalog units in all languages`() {
        AppLanguage.entries.forEach { language ->
            val accuracy = ArenaProgressionCatalog.initialTrait(HeroClass.ROGUE)
            val healing = ArenaProgressionCatalog.initialTrait(HeroClass.CLERIC)
            assertEquals(accuracy.effectText(language.languageTag, 1, 1),
                arenaProgressionTraitEffect(HeroClass.ROGUE, 1, 1, language))
            assertEquals(healing.effectText(language.languageTag, 1, 3),
                arenaProgressionTraitEffect(HeroClass.CLERIC, 1, 3, language))
        }
        assertEquals("미습득", arenaProgressionTraitEffect(HeroClass.WARRIOR, 0, 0, AppLanguage.KOREAN))
    }

    private fun prepare(state: SimpleGameState, progression: ArenaProgressionState) = requireNotNull(
        prepareArenaBattle(
            state,
            BattleQaMatchFactory.createMatch(
                state, 1_000L, BattleGuidance.BALANCED, 1_000, 0,
                "123e4567-e89b-42d3-a456-426614174499", 41L, 1_000L,
            ),
            BattleSeasonStanding(),
            BattleTicketState(20_700L, 3),
            BattleStance.BALANCED,
            AppLanguage.KOREAN,
            progression = progression,
        ),
    )

    private fun fighter(): SimpleGameState {
        val engine = SimpleGameEngine()
        val roll = engine.rollStats(71L, HeroClass.WARRIOR)
        return engine.newGame("성장저장검수", HeroClass.WARRIOR, roll.stats.copy(), roll.nextSeed, 1_000L).apply {
            rngState = ArenaSyntheticProfileGrowth.grow(
                engine, hero.stats, hero.heroClass, targetLevel = 10L, identitySeed = rngState,
            )
            hero.level = 10L
        }
    }

    private fun history(id: String) = BattlePreviewHistory(
        battleId = id,
        userName = "저장검수",
        opponentName = "노아",
        opponentClass = "메이지",
        opponentLevel = 10L,
        resultLabel = "승리",
        pointDelta = 12,
        summary = "노아와의 결투에서 승리했다.",
        narrativeLines = listOf("노아와의 결투에서 승리했다."),
        skillNames = emptyList(),
        equipment = emptyList(),
        traitNames = emptyList(),
        completedAtMillis = 1_000L,
        narrativeSource = "arena_local",
    )
}
