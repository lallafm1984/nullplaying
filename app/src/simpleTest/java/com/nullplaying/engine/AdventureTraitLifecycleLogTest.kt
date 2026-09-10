package com.nullplaying.engine

import com.nullplaying.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class AdventureTraitLifecycleLogTest {
    @Test fun `wavering and recovery stay in profile while actual trait loss enters history once after restore`() {
        val engine = SimpleGameEngine(enableAdventureTraits = true)
        val roll = engine.rollStats(173, HeroClass.WARRIOR)
        var game = engine.newGame("여행자", HeroClass.WARRIOR, roll.stats, roll.nextSeed, 0)
        game.adventureTraits.owned = listOf(AdventureOwnedTrait("L04", 0, 1))
        game.adventureTraits.stableStartedAtByTrait = mapOf("L04" to 0L)
        game.adventureTraits.oppositionStartedAtByTrait = mapOf("L04" to 0L)
        var sequence = 0L
        fun experience(positive: Boolean, at: Long): List<RecentAdventureEvent> {
            sequence++
            val source = AdventureTraitEngine.beginSource(game, "event", "context:${sequence % 3}", at)
            AdventureTraitEngine.observe(game, source.key, source.contextKey,
                if (positive) setOf("L04") else emptySet(),
                if (positive) emptySet() else setOf("L04"), at)
            AdventureTraitEngine.finalizeEvidence(game, at)
            val events = AdventureTraitEngine.drainRecentEvents(game)
            game = Json.decodeFromString<SimpleGameState>(Json.encodeToString(game))
            assertTrue(AdventureTraitEngine.drainRecentEvents(game).isEmpty())
            return events
        }
        repeat(6) { index ->
            assertTrue(experience(false, AdventureTraitEngine.STABLE_MIN_ACTIVE_MILLIS + index).isEmpty())
        }
        assertTrue(game.adventureTraits.owned.single().shaky)
        assertEquals(AdventureTraitChangeKind.WEAKENED, game.adventureTraits.recentChanges.last().kind)
        repeat(9) { index ->
            assertTrue(experience(
                true,
                AdventureTraitEngine.STABLE_MIN_ACTIVE_MILLIS +
                    AdventureTraitEngine.SHAKY_RECOVERY_MIN_ACTIVE_MILLIS + index,
            ).isEmpty())
        }
        assertFalse(game.adventureTraits.owned.single().shaky)
        assertEquals(AdventureTraitChangeKind.RECOVERED, game.adventureTraits.recentChanges.last().kind)
        val emitted = mutableListOf<RecentAdventureEvent>()
        val recoveredAt = AdventureTraitEngine.STABLE_MIN_ACTIVE_MILLIS +
            AdventureTraitEngine.SHAKY_RECOVERY_MIN_ACTIVE_MILLIS + 8L
        val weakeningOffsets = listOf(16L, 18L, 20L, 22L, 23L, 24L)
        weakeningOffsets.forEach { hour -> emitted += experience(false, recoveredAt + hour * HOUR) }
        assertTrue(game.adventureTraits.owned.single().shaky)
        repeat(3) { index -> emitted += experience(false, recoveredAt + 25L * HOUR + index) }
        emitted += experience(
            false,
            recoveredAt + AdventureTraitEngine.STABLE_MIN_ACTIVE_MILLIS +
                AdventureTraitEngine.SHAKY_LOSS_MIN_ACTIVE_MILLIS,
        )
        assertTrue(game.adventureTraits.owned.isEmpty())
        assertEquals(1, emitted.size)
        assertEquals(AdventureTraitChangeKind.LOST.name, emitted.single().contextName)
        assertEquals(game.adventureTraits.changeSequence, game.adventureTraits.reportedChangeSequence)
    }

    companion object { private const val HOUR = 3_600_000L }
}
