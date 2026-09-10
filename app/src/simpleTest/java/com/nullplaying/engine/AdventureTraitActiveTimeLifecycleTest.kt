package com.nullplaying.engine

import com.nullplaying.model.AdventureOwnedTrait
import com.nullplaying.model.AdventureTraitChangeKind
import com.nullplaying.model.AdventureTraitEvidence
import com.nullplaying.model.AdventureTraitState
import com.nullplaying.model.HeroClass
import com.nullplaying.model.SimpleGameState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureTraitActiveTimeLifecycleTest {
    @Test
    fun `formation waits for eight active hours after sufficient diverse evidence`() {
        val game = game()
        game.adventureTraits.seed = passingSeed("FORMATION:formation:9")
        repeat(8) { index ->
            val at = if (index == 7) AdventureTraitEngine.FORMATION_MIN_ACTIVE_MILLIS - 1L else index * HOUR
            record(game, "formation:${index + 1}", "G03", positive = true, at = at, context = "reward:${index % 3}")
        }
        assertFalse(game.adventureTraits.owned.any { it.traitId == "G03" })
        assertEquals(0L, game.adventureTraits.opportunityCounts["FORMATION"] ?: 0L)

        record(
            game,
            "formation:9",
            "G03",
            positive = true,
            at = AdventureTraitEngine.FORMATION_MIN_ACTIVE_MILLIS,
            context = "reward:2",
        )
        assertEquals(listOf("G03"), game.adventureTraits.owned.map { it.traitId })
        assertEquals(AdventureTraitChangeKind.ACQUIRED, game.adventureTraits.recentChanges.single().kind)
    }

    @Test
    fun `weakening recovery and loss honor each active time boundary`() {
        val weakening = owned("G01", shaky = false).also { game ->
            game.adventureTraits.stableStartedAtByTrait = mapOf("G01" to 0L)
            game.adventureTraits.oppositionStartedAtByTrait = mapOf("G01" to 0L)
            game.adventureTraits.evidence = mapOf("G01" to fiveEvidence(positive = false))
        }
        record(weakening, "weak:6", "G01", false,
            AdventureTraitEngine.STABLE_MIN_ACTIVE_MILLIS - 1L, "opposition:2")
        assertFalse(weakening.adventureTraits.owned.single().shaky)
        record(weakening, "weak:7", "G01", false,
            AdventureTraitEngine.STABLE_MIN_ACTIVE_MILLIS, "opposition:0")
        assertTrue(weakening.adventureTraits.owned.single().shaky)
        assertEquals(AdventureTraitChangeKind.WEAKENED, weakening.adventureTraits.recentChanges.single().kind)

        val recovery = owned("G01", shaky = true).also { game ->
            game.adventureTraits.weakenedStartedAtByTrait = mapOf("G01" to 0L)
            game.adventureTraits.evidence = mapOf(
                "G01" to List(3) { index -> evidence("recover:negative:$index", "opposition:$index", false) } +
                    List(7) { index -> evidence("recover:positive:$index", "support:${index % 3}", true) },
            )
        }
        record(recovery, "recover:8", "G01", true,
            AdventureTraitEngine.SHAKY_RECOVERY_MIN_ACTIVE_MILLIS - 1L, "support:1")
        assertTrue(recovery.adventureTraits.owned.single().shaky)
        record(recovery, "recover:9", "G01", true,
            AdventureTraitEngine.SHAKY_RECOVERY_MIN_ACTIVE_MILLIS, "support:2")
        assertFalse(recovery.adventureTraits.owned.single().shaky)
        assertEquals(AdventureTraitChangeKind.RECOVERED, recovery.adventureTraits.recentChanges.single().kind)

        val loss = owned("G01", shaky = true).also { game ->
            game.adventureTraits.weakenedStartedAtByTrait = mapOf("G01" to 0L)
            game.adventureTraits.evidence = mapOf(
                "G01" to List(9) { index -> evidence("loss:$index", "opposition:${index % 3}", false) },
            )
        }
        record(loss, "loss:10", "G01", false,
            AdventureTraitEngine.SHAKY_LOSS_MIN_ACTIVE_MILLIS - 1L, "opposition:0")
        assertTrue(loss.adventureTraits.owned.single().shaky)
        record(loss, "loss:11", "G01", false,
            AdventureTraitEngine.SHAKY_LOSS_MIN_ACTIVE_MILLIS, "opposition:1")
        assertTrue(loss.adventureTraits.owned.isEmpty())
        assertEquals(AdventureTraitChangeKind.LOST, loss.adventureTraits.recentChanges.single().kind)
    }

    @Test
    fun `uncovered pause rebases every lifecycle clock and cannot mature formation`() {
        val pause = 24L * HOUR
        val game = game().also { state ->
            state.adventureTraits.seed = passingSeed("FORMATION:pause:9")
            state.adventureTraits.formationStartedAtByTrait = mapOf("G03" to 0L)
            state.adventureTraits.lastFormationAt = -AdventureTraitEngine.FORMATION_COOLDOWN_ACTIVE_MILLIS
            state.adventureTraits.stableStartedAtByTrait = mapOf("G01" to 1L)
            state.adventureTraits.oppositionStartedAtByTrait = mapOf("G01" to 2L)
            state.adventureTraits.weakenedStartedAtByTrait = mapOf("G01" to 3L)
            state.adventureTraits.evidence = mapOf(
                "G03" to List(7) { index -> evidence("pause:old:$index", "reward:${index % 3}", true) },
            )
        }
        AdventureTraitEngine.pause(game, pause)
        assertEquals(pause, game.adventureTraits.formationStartedAtByTrait["G03"])
        assertEquals(0L, game.adventureTraits.lastFormationAt)
        assertEquals(pause + 1L, game.adventureTraits.stableStartedAtByTrait["G01"])
        assertEquals(pause + 2L, game.adventureTraits.oppositionStartedAtByTrait["G01"])
        assertEquals(pause + 3L, game.adventureTraits.weakenedStartedAtByTrait["G01"])

        record(game, "pause:8", "G03", true,
            pause + AdventureTraitEngine.FORMATION_MIN_ACTIVE_MILLIS - 1L, "reward:1")
        assertTrue(game.adventureTraits.owned.isEmpty())
        record(game, "pause:9", "G03", true,
            pause + AdventureTraitEngine.FORMATION_MIN_ACTIVE_MILLIS, "reward:2")
        assertEquals(listOf("G03"), game.adventureTraits.owned.map { it.traitId })
    }

    @Test
    fun `successful formation starts one shared twenty four active hour cooldown`() {
        val game = game().also { state ->
            state.adventureTraits.lastFormationAt = 0L
            state.adventureTraits.formationStartedAtByTrait = mapOf("G03" to 0L)
            state.adventureTraits.evidence = mapOf(
                "G03" to List(7) { index -> evidence("cooldown:old:$index", "reward:${index % 3}", true) },
            )
        }
        game.adventureTraits.seed = passingSeed("FORMATION:cooldown:blocked")
        record(game, "cooldown:blocked", "G03", true,
            AdventureTraitEngine.FORMATION_COOLDOWN_ACTIVE_MILLIS - 1L, "reward:1")
        assertTrue(game.adventureTraits.owned.isEmpty())
        assertEquals(0L, game.adventureTraits.opportunityCounts["FORMATION"] ?: 0L)

        game.adventureTraits.seed = passingSeed("FORMATION:cooldown:allowed")
        record(game, "cooldown:allowed", "G03", true,
            AdventureTraitEngine.FORMATION_COOLDOWN_ACTIVE_MILLIS, "reward:2")
        assertEquals(listOf("G03"), game.adventureTraits.owned.map { it.traitId })
        assertEquals(AdventureTraitEngine.FORMATION_COOLDOWN_ACTIVE_MILLIS,
            game.adventureTraits.lastFormationAt)
    }

    @Test
    fun `legacy trait state without lifecycle clocks uses safe empty defaults`() {
        val decoded = Json.decodeFromString<AdventureTraitState>("{}")
        assertTrue(decoded.formationStartedAtByTrait.isEmpty())
        assertEquals(null, decoded.lastFormationAt)
        assertTrue(decoded.stableStartedAtByTrait.isEmpty())
        assertTrue(decoded.oppositionStartedAtByTrait.isEmpty())
        assertTrue(decoded.weakenedStartedAtByTrait.isEmpty())
    }

    private fun record(
        game: SimpleGameState,
        key: String,
        traitId: String,
        positive: Boolean,
        at: Long,
        context: String,
    ) {
        AdventureTraitEngine.observe(
            game,
            key,
            context,
            if (positive) setOf(traitId) else emptySet(),
            if (positive) emptySet() else setOf(traitId),
            at,
            "qa:lifecycle",
        )
        AdventureTraitEngine.finalizeEvidence(game, at)
    }

    private fun fiveEvidence(positive: Boolean): List<AdventureTraitEvidence> =
        List(5) { index -> evidence("old:$index", "opposition:${index % 3}", positive) }

    private fun evidence(key: String, context: String, positive: Boolean): AdventureTraitEvidence =
        AdventureTraitEvidence(key, context, positive, "qa:lifecycle")

    private fun owned(id: String, shaky: Boolean): SimpleGameState = game().also {
        it.adventureTraits.owned = listOf(AdventureOwnedTrait(id, shaky = shaky))
    }

    private fun game(): SimpleGameState {
        val engine = SimpleGameEngine(enableAdventureTraits = true)
        val rolled = engine.rollStats(71L, HeroClass.WARRIOR)
        return engine.newGame("active-time-lifecycle", HeroClass.WARRIOR, rolled.stats, rolled.nextSeed, 0L)
    }

    private fun passingSeed(key: String): Long = (1L..2_000_000L).first {
        AdventureTraitEngine.random(it, key) < 2_500
    }

    companion object { private const val HOUR = 3_600_000L }
}
