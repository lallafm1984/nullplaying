package com.nullplaying.ui

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BattleLocalStateStoreIsolationTest {
    private lateinit var context: Context
    private lateinit var store: BattleLocalStateStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences().edit().clear().commit()
        store = BattleLocalStateStore(context)
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun `ticket recovery and rewarded refill ledgers are isolated by character identity`() {
        val today = 20_700L
        val fighterIdentity = "fighter-slot-1"
        val mageIdentity = "mage-slot-2"
        val fighterAnchor = 1_000_000L
        val mageAnchor = 2_000_000L

        assertTrue(store.save(fighterIdentity, emptySnapshot(today, fighterAnchor).copy(
            dailyBattleDay = today,
            dailyBattlesUsed = 7,
        )))
        assertTrue(store.save(mageIdentity, emptySnapshot(today, mageAnchor)))

        requireNotNull(store.applyRewardedBattleEntryRefill(
            identity = fighterIdentity,
            today = today,
            nowMillis = fighterAnchor + 1L,
            requestId = "shared-request",
        ))
        exhaust(fighterIdentity)
        val fighterSecondRefill = requireNotNull(store.applyRewardedBattleEntryRefill(
            fighterIdentity,
            today,
            fighterAnchor + 2L,
            "fighter-request-2",
        ))

        assertEquals(0, fighterSecondRefill.entriesRemaining)
        assertEquals(fighterAnchor, fighterSecondRefill.entryRecoveryStartedAtMillis)
        assertEquals(BATTLE_REWARDED_REFILL_DAILY_LIMIT, fighterSecondRefill.rewardedRefillsUsed)
        assertEquals(listOf("shared-request"), fighterSecondRefill.rewardedRefillRequestIds)
        assertEquals(7, fighterSecondRefill.dailyBattlesUsed)

        val untouchedMage = store.load(mageIdentity)
        assertEquals(0, untouchedMage.entriesRemaining)
        assertEquals(mageAnchor, untouchedMage.entryRecoveryStartedAtMillis)
        assertEquals(0, untouchedMage.rewardedRefillsUsed)
        assertEquals(0, untouchedMage.dailyBattlesUsed)
        assertTrue(untouchedMage.rewardedRefillRequestIds.isEmpty())

        var mage = requireNotNull(store.applyRewardedBattleEntryRefill(
            identity = mageIdentity,
            today = today,
            nowMillis = mageAnchor + 1L,
            requestId = "shared-request",
        ))
        assertEquals(BATTLE_ENTRY_CAPACITY, mage.entriesRemaining)
        assertEquals(mageAnchor, mage.entryRecoveryStartedAtMillis)
        assertEquals(1, mage.rewardedRefillsUsed)
        assertEquals(listOf("shared-request"), mage.rewardedRefillRequestIds)

        mage = exhaust(mageIdentity)
        val mageDuplicate = requireNotNull(store.applyRewardedBattleEntryRefill(
            identity = mageIdentity,
            today = today,
            nowMillis = mageAnchor + 2L,
            requestId = "shared-request",
        ))
        assertEquals(0, mageDuplicate.entriesRemaining)
        assertEquals(1, mageDuplicate.rewardedRefillsUsed)
        assertEquals(listOf("shared-request"), mageDuplicate.rewardedRefillRequestIds)
        assertEquals(mageAnchor, mageDuplicate.entryRecoveryStartedAtMillis)

        val persistedFighter = store.load(fighterIdentity)
        assertEquals(0, persistedFighter.entriesRemaining)
        assertEquals(fighterAnchor, persistedFighter.entryRecoveryStartedAtMillis)
        assertEquals(BATTLE_REWARDED_REFILL_DAILY_LIMIT, persistedFighter.rewardedRefillsUsed)
        assertEquals(fighterSecondRefill.rewardedRefillRequestIds, persistedFighter.rewardedRefillRequestIds)
        assertEquals(7, persistedFighter.dailyBattlesUsed)
    }

    @Test
    fun `one hundred minute foreground countdown commits only at five ticket boundaries`() {
        val identity = "countdown-write-contract"
        val day = 20_700L
        val dayMillis = 24L * 60L * 60L * 1_000L
        val anchor = day * dayMillis + 1_000L
        assertTrue(store.save(
            identity,
            BattleLocalSnapshot(
                gameEpochDay = day,
                entriesRemaining = 0,
                entryRecoveryStartedAtMillis = anchor,
                entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
                dailyBattleDay = day,
                rewardedRefillDay = day,
                arenaTrustedEpochMillis = anchor,
                arenaTrustedElapsedRealtimeMillis = 0L,
                arenaTrustedBootCount = 7,
                arenaClockServerAnchored = true,
            ),
        ))
        var commitCallbacks = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "hero.$identity") commitCallbacks += 1
        }
        preferences().registerOnSharedPreferenceChangeListener(listener)

        var memorySnapshot = store.load(identity)
        var storeRefreshCalls = 0
        try {
            for (second in 1L..6_000L) {
                val elapsed = second * 1_000L
                val tick = advanceArenaTicketUiClock(
                    snapshot = memorySnapshot,
                    roster = null,
                    elapsedRealtimeMillis = elapsed,
                    bootCount = 7,
                )
                memorySnapshot = tick.snapshot
                if (tick.persistenceRequired) {
                    storeRefreshCalls += 1
                    memorySnapshot = requireNotNull(store.refreshBattleEntriesTrusted(
                        identity = identity,
                        roster = null,
                        // Alternating wall-clock edits prove the write scheduler still uses the
                        // monotonic trusted clock established in the save.
                        deviceWallNowMillis = if (second % 2L == 0L) 1L else Long.MAX_VALUE - 1L,
                        elapsedRealtimeMillis = elapsed,
                        bootCount = 7,
                    ))
                }
            }
        } finally {
            preferences().unregisterOnSharedPreferenceChangeListener(listener)
        }

        assertEquals(BATTLE_ENTRY_CAPACITY, storeRefreshCalls)
        assertEquals(BATTLE_ENTRY_CAPACITY, commitCallbacks)
        assertEquals(BATTLE_ENTRY_CAPACITY, memorySnapshot.entriesRemaining)
        // Once the five-slot wallet is full at fifty minutes, later clock-only UI ticks
        // must not write preferences. The ticket ledger stays identical on disk.
        assertEquals(memorySnapshot.copy(
            arenaTrustedEpochMillis = anchor + BATTLE_ENTRY_RECOVERY_MILLIS,
            arenaTrustedElapsedRealtimeMillis = BATTLE_ENTRY_RECOVERY_MILLIS,
        ), store.load(identity))
    }

    private fun emptySnapshot(today: Long, anchor: Long): BattleLocalSnapshot = BattleLocalSnapshot(
        gameEpochDay = today,
        entriesRemaining = 0,
        entryRecoveryStartedAtMillis = anchor,
        entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
        rewardedRefillDay = today,
    )

    private fun exhaust(identity: String): BattleLocalSnapshot = requireNotNull(store.update(identity) {
        it.copy(entriesRemaining = 0)
    })

    private fun preferences() = context.getSharedPreferences(
        "battle_v01_local_state",
        Context.MODE_PRIVATE,
    )
}
