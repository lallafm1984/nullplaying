package com.nullplaying.engine

import com.nullplaying.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AdventureTraitRetentionCapacityTest {
    private val hour = 3_600_000L
    private fun game(ids: List<String> = listOf("C02")): SimpleGameState {
        val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)
        val rolled = engine.rollStats(71L, HeroClass.WARRIOR)
        return engine.newGame("retention", HeroClass.WARRIOR, rolled.stats, rolled.nextSeed, 0L).also { game ->
            game.adventureTraits.owned = ids.mapIndexed { i, id -> AdventureOwnedTrait(id, 0L, i + 1L, shaky = id == "C02") }
            game.adventureTraits.retentionStartedAtByTrait = ids.associateWith { 0L }
            game.adventureTraits.stableStartedAtByTrait = ids.associateWith { 0L }
            game.adventureTraits.weakenedStartedAtByTrait = mapOf("C02" to 24 * hour)
        }
    }
    private fun signs(id: String, positive: Boolean, at: Long) = List(9) {
        AdventureTraitEvidence("old:$id:$it", "context:${it % 3}", positive, "qa:retention", at)
    }
    private fun record(game: SimpleGameState, key: String, at: Long, positive: Set<String>, negative: Set<String> = emptySet()) {
        AdventureTraitEngine.observe(game, key, "context:0", positive, negative, at, "qa:retention")
        AdventureTraitEngine.finalizeEvidence(game, at)
    }
    private fun seedPassing(key: String) = (1L..1000L).first { AdventureTraitEngine.random(it, "FORMATION:$key") < 2500 }

    @Test fun `C02 cannot be lost before exactly seventy two active hours`() {
        val game = game()
        game.adventureTraits.evidence = mapOf("C02" to signs("C02", false, 60 * hour))
        record(game, "before", 72 * hour - 1L, emptySet(), setOf("C02"))
        assertEquals("C02", game.adventureTraits.owned.single().traitId)
        record(game, "boundary", 72 * hour, emptySet(), setOf("C02"))
        assertTrue(game.adventureTraits.owned.isEmpty())
        assertEquals(AdventureTraitChangeKind.LOST, game.adventureTraits.recentChanges.single().kind)
        assertFalse("C02" in game.adventureTraits.retentionStartedAtByTrait)
    }

    @Test fun `protection expiry alone cannot remove a trait`() {
        val game = game()
        record(game, "unrelated", 100 * hour, setOf("R01"))
        record(game, "support", 101 * hour, setOf("C02"))
        assertTrue(game.adventureTraits.owned.any { it.traitId == "C02" })
        assertTrue(game.adventureTraits.recentChanges.none { it.kind == AdventureTraitChangeKind.LOST })
    }

    @Test fun `seven held traits block an eighth without evicting any existing trait`() {
        val game = game(listOf("C03", "L01", "R01", "E01", "T01", "G01", "S01"))
        game.adventureTraits.evidence = mapOf("G03" to signs("G03", true, 90 * hour))
        game.adventureTraits.formationStartedAtByTrait = mapOf("G03" to 0L)
        val before = game.adventureTraits.owned
        game.adventureTraits.seed = seedPassing("full")
        record(game, "full", 100 * hour, setOf("G03"))
        assertEquals(before.toSet(), game.adventureTraits.owned.toSet())
        assertEquals(null, game.adventureTraits.opportunityCounts["FORMATION"])
    }

    @Test fun `protected opposite replacement cannot bypass cap or retention and restarts new protection`() {
        val game = game(listOf("C02", "L01", "R01", "E01", "T01", "G01", "S01"))
        game.adventureTraits.evidence = mapOf("C01" to signs("C01", true, 60 * hour), "C02" to signs("C02", false, 60 * hour))
        game.adventureTraits.formationStartedAtByTrait = mapOf("C01" to 0L)
        game.adventureTraits.seed = seedPassing("protected")
        record(game, "protected", 72 * hour - 1L, setOf("C01"), setOf("C02"))
        assertTrue(game.adventureTraits.owned.any { it.traitId == "C02" })
        assertFalse(game.adventureTraits.owned.any { it.traitId == "C01" })
        game.adventureTraits.seed = seedPassing("replace")
        record(game, "replace", 72 * hour, setOf("C01"), setOf("C02"))
        assertEquals(7, game.adventureTraits.owned.size)
        assertTrue(game.adventureTraits.owned.any { it.traitId == "C01" })
        assertFalse(game.adventureTraits.owned.any { it.traitId == "C02" })
        assertEquals(72 * hour, game.adventureTraits.retentionStartedAtByTrait["C01"])
        assertEquals(AdventureTraitChangeKind.REPLACED, game.adventureTraits.recentChanges.single().kind)
    }

    @Test fun `natural loss frees a slot and the next eligible formation fills it`() {
        val game = game(listOf("C02", "L01", "R01", "E01", "T01", "G01", "S01"))
        game.adventureTraits.evidence = mapOf("G03" to signs("G03", true, 70 * hour), "C02" to signs("C02", false, 70 * hour))
        game.adventureTraits.formationStartedAtByTrait = mapOf("G03" to 0L)
        record(game, "loss", 72 * hour, emptySet(), setOf("C02"))
        assertEquals(6, game.adventureTraits.owned.size)
        game.adventureTraits.seed = seedPassing("fill")
        record(game, "fill", 72 * hour + 1, setOf("G03"))
        assertEquals(7, game.adventureTraits.owned.size)
        assertTrue(game.adventureTraits.owned.any { it.traitId == "G03" })
    }

    @Test fun `restart pause and repeated initialization preserve retention clock`() {
        var game = game()
        game.adventureTraits.evidence = mapOf("C02" to signs("C02", false, 60 * hour))
        AdventureTraitEngine.pause(game, 1000 * hour)
        game = Json.decodeFromString(Json.encodeToString(game))
        repeat(3) { AdventureTraitEngine.initialize(game) }
        assertEquals(1000 * hour, game.adventureTraits.retentionStartedAtByTrait["C02"])
        assertEquals(0L, game.adventureTraits.owned.single().acquiredAt)
        record(game, "before", 1072 * hour - 1, emptySet(), setOf("C02"))
        assertEquals(1, game.adventureTraits.owned.size)
        record(game, "after", 1072 * hour, emptySet(), setOf("C02"))
        assertTrue(game.adventureTraits.owned.isEmpty())
    }

    @Test fun `legacy overflow is preserved without allowing additional acquisitions`() {
        val ids = listOf("C02", "L01", "R01", "E01", "T01", "G03", "G01", "S01")
        val game = game(ids)
        game.adventureTraits.retentionStartedAtByTrait = emptyMap()
        AdventureTraitEngine.initialize(game)
        assertEquals(ids.toSet(), game.adventureTraits.owned.map { it.traitId }.toSet())
        assertEquals(ids.associateWith { 0L }, game.adventureTraits.retentionStartedAtByTrait)
        game.adventureTraits.evidence = mapOf("L05" to signs("L05", true, 90 * hour))
        game.adventureTraits.formationStartedAtByTrait = mapOf("L05" to 0L)
        game.adventureTraits.seed = seedPassing("overflow")
        record(game, "overflow", 100 * hour, setOf("L05"))
        assertEquals(8, game.adventureTraits.owned.size)
        assertFalse(game.adventureTraits.owned.any { it.traitId == "L05" })
    }

    @Test(timeout = 180_000L) fun `natural seven day cohort never exceeds seven or removes within three active days`() {
        var formations = 0
        var removals = 0
        val finalCounts = mutableListOf<Int>()
        HeroClass.entries.forEach { heroClass -> repeat(4) { sample ->
            val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)
            val game = AdventureQaFixtures.game(engine, heroClass, AdventureQaFixtures.finalSeeds[sample], 20L)
            val acquiredAt = mutableMapOf<String, Long>()
            var sequence = 0L
            repeat(168) { h ->
                engine.settleOffline(game, AdventureQaFixtures.EPOCH + (h + 1L) * hour)
                val changes = game.adventureTraits.recentChanges.filter { it.sequence > sequence }
                assertEquals(game.adventureTraits.changeSequence - sequence, changes.size.toLong())
                sequence = game.adventureTraits.changeSequence
                changes.forEach { change ->
                    val removed = when (change.kind) {
                        AdventureTraitChangeKind.LOST -> change.traitId
                        AdventureTraitChangeKind.REPLACED -> change.replacedTraitId
                        else -> null
                    }
                    if (removed != null) {
                        assertTrue(change.occurredAt - acquiredAt.remove(removed)!! >= AdventureTraitEngine.MIN_RETENTION_ACTIVE_MILLIS)
                        removals++
                    }
                    if (change.kind in setOf(AdventureTraitChangeKind.ACQUIRED, AdventureTraitChangeKind.REPLACED)) {
                        acquiredAt[change.traitId] = change.occurredAt
                        formations++
                    }
                }
                assertTrue(game.adventureTraits.owned.size <= 7)
                assertEquals(acquiredAt.keys, game.adventureTraits.owned.map { it.traitId }.toSet())
            }
            finalCounts += game.adventureTraits.owned.size
        } }
        assertTrue(formations > 0)
        assertTrue(removals > 0)
        println("RETENTION_CAP characters=24 hours=168 formations=$formations removals=$removals finalCounts=${finalCounts.groupingBy { it }.eachCount()}")
    }
}
