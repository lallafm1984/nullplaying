package com.alarmquest.remote

import com.alarmquest.engine.SimpleGameEngine
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
    fun `ranking cache stays fresh for less than one hour`() {
        val fetchedAt = 1_000_000L

        assertTrue(isRankingCacheFresh(fetchedAt, fetchedAt + RANKING_CACHE_TTL_MILLIS - 1L))
        assertFalse(isRankingCacheFresh(fetchedAt, fetchedAt + RANKING_CACHE_TTL_MILLIS))
        assertFalse(isRankingCacheFresh(fetchedAt, fetchedAt - 1L))
    }

    @Test
    fun `ranking uploads wait five minutes after a successful sync`() {
        val syncedAt = 1_000_000L

        assertFalse(isRankingSyncAllowed(syncedAt, syncedAt + RANKING_SYNC_COOLDOWN_MILLIS - 1L))
        assertTrue(isRankingSyncAllowed(syncedAt, syncedAt + RANKING_SYNC_COOLDOWN_MILLIS))
        assertTrue(isRankingSyncAllowed(0L, syncedAt))
    }

    @Test
    fun `short background switches remain in the same session`() {
        val backgroundedAt = 1_000_000L

        assertFalse(
            isCompletedBackgroundSession(
                backgroundedAt,
                backgroundedAt + SESSION_BACKGROUND_GRACE_MILLIS - 1L,
            ),
        )
        assertTrue(
            isCompletedBackgroundSession(
                backgroundedAt,
                backgroundedAt + SESSION_BACKGROUND_GRACE_MILLIS,
            ),
        )
    }
}
