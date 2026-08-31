package com.nullplaying.data

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProgressNotificationsTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `progress event reports the character whose offline adventure was depleted`() {
        val game = newGame()
        game.offlineAdventureMillis = 5_000L
        val before = game.progressCheckpoint()
        game.offlineAdventureMillis = 0L

        val event = requireNotNull(gameProgressEventBetween(before, game))

        assertEquals("알림 검사", event.heroName)
        assertTrue(event.offlineAdventureDepleted)
    }

    @Test
    fun `offline depletion is emitted only when a positive balance crosses zero`() {
        val game = newGame()
        game.offlineAdventureMillis = 0L
        val alreadyEmpty = game.progressCheckpoint()

        assertNull(gameProgressEventBetween(alreadyEmpty, game))

        game.offlineAdventureMillis = 1L
        val positive = game.progressCheckpoint()
        game.offlineAdventureMillis = 0L
        val event = requireNotNull(gameProgressEventBetween(positive, game))
        assertTrue(event.offlineAdventureDepleted)
    }

    private fun newGame() = engine.newGame(
        name = "알림 검사",
        heroClass = HeroClass.WARRIOR,
        rolledStats = engine.rollStats(11L).stats,
        seed = 12L,
        now = 1_000L,
    )
}
