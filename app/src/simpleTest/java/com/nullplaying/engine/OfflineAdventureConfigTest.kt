package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.SimpleGameState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OfflineAdventureConfigTest {
    @Test
    fun `invalid console values are rejected without disabling adventure`() {
        for (value in listOf("", "0", "-1", "NaN", "Infinity", "1.5", "999999999999999999999")) {
            assertNull(OfflineAdventureConfig.parse(value, "12"))
            assertNull(OfflineAdventureConfig.parse("720", value))
        }
        assertNull(OfflineAdventureConfig.parse("4321", "12"))
        assertNull(OfflineAdventureConfig.parse("720", "1441"))
        assertEquals(OfflineAdventureConfig(), OfflineAdventureConfig.parse("480", "20"))
    }

    @Test
    fun `custom capacity controls new games display rewards and offline settlement`() {
        val engine = SimpleGameEngine(OfflineAdventureConfig(30, 7))
        val game = newGame(engine)
        assertEquals(1_800_000L, game.offlineAdventureMillis)
        game.offlineAdventureMillis = 900_000L
        assertEquals(0.5f, engine.offlineAdventureFraction(game))
        assertTrue(engine.grantRewardedOfflineAdventure(game, "reward-1"))
        assertEquals(1_800_000L, game.offlineAdventureMillis)
        assertFalse(engine.grantRewardedOfflineAdventure(game, "reward-1"))
        engine.settleOfflineWithOfflineAdventure(game, 3_600_000L)
        assertEquals(0L, game.offlineAdventureMillis)
        assertEquals(3_600_000L, game.lastSettledAt)
    }

    @Test
    fun `fractional charge rate survives split ticks and save restoration`() {
        val engine = SimpleGameEngine(OfflineAdventureConfig(1, 7))
        val continuous = newGame(engine).apply { offlineAdventureMillis = 0L }
        var chunked = continuous.copy()
        engine.advanceOfflineAdventureForeground(continuous, 12_347L)
        repeat(12_347) { tick ->
            engine.advanceOfflineAdventureForeground(chunked, 1L)
            if (tick == 6_123) chunked = Json.decodeFromString<SimpleGameState>(Json.encodeToString(chunked))
        }
        assertEquals(continuous.offlineAdventureMillis, chunked.offlineAdventureMillis)
        assertEquals(continuous.offlineAdventureChargeRemainder, chunked.offlineAdventureChargeRemainder)
        engine.advanceOfflineAdventureForeground(chunked, Long.MAX_VALUE)
        assertEquals(60_000L, chunked.offlineAdventureMillis)
        assertEquals(0L, chunked.offlineAdventureChargeRemainder)
    }

    @Test
    fun `changing capacity neither creates nor deletes earned time`() {
        val engine = SimpleGameEngine()
        val game = newGame(engine)
        engine.updateOfflineAdventureConfig(OfflineAdventureConfig(1_440, 24))
        engine.clampOfflineAdventureBalance(game)
        assertEquals(28_800_000L, game.offlineAdventureMillis)
        assertEquals(1f / 3f, engine.offlineAdventureFraction(game))
        engine.updateOfflineAdventureConfig(OfflineAdventureConfig(60, 10))
        engine.clampOfflineAdventureBalance(game)
        assertEquals(28_800_000L, game.offlineAdventureMillis)
        assertEquals(1f, engine.offlineAdventureFraction(game))
    }

    private fun newGame(engine: SimpleGameEngine) = engine.newGame(
        "설정 검사", HeroClass.WARRIOR, engine.rollStats(77L).stats.copy(constitution = 0L), 88L, 0L,
    )
}
