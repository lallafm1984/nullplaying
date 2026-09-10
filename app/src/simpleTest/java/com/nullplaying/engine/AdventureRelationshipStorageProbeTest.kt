package com.nullplaying.engine

import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEncounterRoster
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipContact
import com.nullplaying.model.AdventureRelationshipEquipmentSnapshot
import com.nullplaying.model.AdventureRelationshipMemory
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.SimpleGameState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM allocation/serialization evidence only. This is not Android frame or Room I/O evidence. */
class AdventureRelationshipStorageProbeTest {
    @Test(timeout = 180_000L)
    fun `maximum retained contact memories report whole save serialization size and latency`() {
        val qa = stagingQaDirectory()
        val game = maximumFixture()
        val encoded = Json.encodeToString(game)
        val restored = Json.decodeFromString<SimpleGameState>(encoded)
        assertEquals(game, restored)
        assertEquals(100, restored.adventureRelationships.contacts.size)
        assertEquals(1_600, restored.adventureRelationships.contacts.sumOf { it.memories.size })
        assertTrue(restored.adventureRelationships.recentResults.all {
            it.run.localBattleSnapshot == null && it.run.opponentBattleSnapshot == null
        })
        assertEquals(null, restored.adventureRelationships.lastResult?.run?.localBattleSnapshot)
        assertEquals(null, restored.adventureRelationships.lastResult?.run?.opponentBattleSnapshot)
        val pending = requireNotNull(restored.adventureRelationships.pending)
        assertNotNull(pending.localBattleSnapshot)
        assertNotNull(pending.opponentBattleSnapshot)
        assertTrue(Json.encodeToString(pending).length >
            Json.encodeToString(requireNotNull(restored.adventureRelationships.lastResult).run).length)
        val bytes = encoded.toByteArray(Charsets.UTF_8).size
        var sink = 0L
        repeat(20) {
            sink += Json.encodeToString(game).length
            sink += Json.decodeFromString<SimpleGameState>(encoded).adventureRelationships.contacts.size
        }
        val encodeNanos = LongArray(200)
        val decodeNanos = LongArray(200)
        repeat(200) { index ->
            val encodingStarted = System.nanoTime()
            val value = Json.encodeToString(game)
            encodeNanos[index] = System.nanoTime() - encodingStarted
            sink += value.length
            val decodingStarted = System.nanoTime()
            val decoded = Json.decodeFromString<SimpleGameState>(encoded)
            decodeNanos[index] = System.nanoTime() - decodingStarted
            sink += decoded.adventureRelationships.contacts.sumOf { it.memories.size }
        }
        assertTrue(sink > 0L)
        val relationshipBytes = Json.encodeToString(game.adventureRelationships).toByteArray(Charsets.UTF_8).size
        val rows = listOf(
            listOf("compact_state_encode", 100, 1_600, bytes, 200, medianMillis(encodeNanos), percentileMillis(encodeNanos, 0.95), encodeNanos.max().toDouble() / 1_000_000L),
            listOf("compact_state_decode", 100, 1_600, bytes, 200, medianMillis(decodeNanos), percentileMillis(decodeNanos, 0.95), decodeNanos.max().toDouble() / 1_000_000L),
            listOf("relationship_model_size", 100, 1_600, relationshipBytes, 0, "", "", ""),
        )
        qa.resolve("adventure-relationship-storage.csv").toFile().writeText(
            (listOf("operation,contacts,retained_memories,utf8_bytes,measured_iterations,median_ms,p95_ms,max_ms") +
                rows.map { it.joinToString(",") { value -> if (value is Double) String.format(Locale.ROOT, "%.6f", value) else value.toString() } })
                .joinToString("\n") + "\n")
        qa.resolve("adventure-relationship-storage-notes.txt").toFile().writeText(
            "Local JVM timing, not Android frame, battery, Room/disk write, or real device measurement.\n" +
                "Whole SimpleGameState has 100 synthetic contacts x 16 actual compact memories, plus compact recent32/lastResult and one full pending battle snapshot pair.\n" +
                "The current 50-situation catalog exposes eight battle situations initially and two more after qualifying reunions. The actual engine chooses and resolves the template run; IDs/times/history are expanded only as a maximum-capacity storage fixture.\n" +
                "Completed results carry exactly one XP, gold, or item reward and drop duplicate local/opponent fighter snapshots before storage.\n" +
                "20 warm-up encode/decode pairs followed by 200 measured pairs in alternating order; system nanoTime.\n" +
                "Median is the middle-pair average; P95 uses nearest rank. GC/JIT/machine load can affect tails; no timing pass threshold.\n" +
                "The actual compact model stores per-contact sequence/scene/approach/outcome/occurredAt/before/actual-delta/after score.\n" +
                "Contact identity/latest public snapshot/lifetime counters remain; only the unresolved pending run retains the frozen fighter pair.\n" +
                "The complete current model round-trips before timing. Prior full-history baseline CSV/notes are in before-contact-memory-compact.\n" +
                "Current bytes=$bytes; sink=$sink.\n")
    }

    private fun maximumFixture(): SimpleGameState {
        val engine = SimpleGameEngine(enableAdventureRelationships = true)
        val rolled = engine.rollStats(503L, HeroClass.CLERIC)
        val game = engine.newGame("저장 비용 표본", HeroClass.CLERIC, rolled.stats, rolled.nextSeed, EPOCH)
        game.hero.level = 50L
        game.rankingCharacterId = "storage-own"
        val candidate = battleCandidate("storage-person-000", "검증 모험가 000")
        game.adventureRelationships.roster = AdventureEncounterRoster(
            "storage-fixture",
            EPOCH,
            Long.MAX_VALUE,
            listOf(candidate),
        )
        val selected = generateSequence(0L) { it + 1L }.take(300).mapNotNull { attempt ->
            game.adventureRelationships.nextEncounterAt = EPOCH
            AdventureRelationshipEngine.tryBegin(game, EPOCH + attempt).also { run ->
                if (run?.battleKind == AdventureRelationshipBattleKind.NONE) {
                    game.adventureRelationships.pending = null
                }
            }
        }.first { it.battleKind != AdventureRelationshipBattleKind.NONE }
        assertNotNull(selected.localBattleSnapshot)
        assertNotNull(selected.opponentBattleSnapshot)
        val battle = requireNotNull(AdventureRelationshipBattleEngine.simulate(selected))
        val resolved = AdventureRelationshipEngine.resolveBattle(selected, battle.outcome)
        val storedTemplate = resolved.copy(localBattleSnapshot = null, opponentBattleSnapshot = null)
        val reunionSpacing = AdventureRelationshipEngine.REUNION_COOLDOWN_MILLIS + storedTemplate.durationMillis
        val storedResults = mutableListOf<AdventureRelationshipResult>()
        val contacts = (0 until 100).map { index ->
            val person = candidate.copy(characterId = "storage-person-${index.toString().padStart(3, '0')}",
                displayName = "검증 모험가 ${index.toString().padStart(3, '0')}")
            val memories = (0 until 16).map { meeting ->
                val at = EPOCH + meeting * reunionSpacing + index * 20_000L
                val run = storedTemplate.copy(sequence = index * 16L + meeting + 1L, candidate = person,
                    startedAt = at, startedActiveMillis = at - EPOCH, scoreBefore = meeting,
                    scoreDelta = 1, reunion = meeting > 0)
                val scoreAfter = (run.scoreBefore + AdventureRelationshipEngine.effectiveScoreDelta(run))
                    .coerceIn(-100, 100)
                AdventureRelationshipResult(
                    run = run,
                    occurredAt = at + run.durationMillis,
                    experienceAwarded = run.experienceReward,
                    scoreAfter = scoreAfter,
                    progressAdded = 1L,
                    goldAwarded = run.goldReward,
                    itemName = if (run.rewardKind == AdventureEventRewardKind.ITEM) "고정 전투 장비" else "",
                    itemRarity = if (run.rewardKind == AdventureEventRewardKind.ITEM) "고급" else "",
                    rewardKind = run.rewardKind,
                ).also { assertEquals(1, actualRewardKinds(it)) }
            }
            storedResults += memories
            AdventureRelationshipContact(person.characterId, memories.last().scoreAfter, 16L, memories.first().occurredAt,
                memories.last().occurredAt, memories.last().run.startedActiveMillis + selected.durationMillis,
                memories.last().run.startedActiveMillis + selected.durationMillis +
                    AdventureRelationshipEngine.REUNION_COOLDOWN_MILLIS, person,
                memories.map { result -> AdventureRelationshipMemory(result.run.sequence, result.run.sceneId,
                    result.run.approachId, result.run.outcome, result.occurredAt, result.run.scoreBefore,
                    result.scoreAfter - result.run.scoreBefore, result.scoreAfter) })
        }
        val recent = storedResults.takeLast(32)
        game.adventureRelationships.contacts = contacts
        game.adventureRelationships.recentResults = recent
        game.adventureRelationships.lastResult = recent.last()
        game.adventureRelationships.pending = selected.copy(sequence = 1_601L)
        game.adventureRelationships.totalEncounters = 1_600L
        game.adventureRelationships.sequence = 1_601L
        game.adventureRelationships.totalExperience = storedResults.sumOf { it.experienceAwarded }
        game.adventureRelationships.totalGold = storedResults.sumOf { it.goldAwarded }
        game.adventureRelationships.totalItems = storedResults.count { it.itemName.isNotBlank() }.toLong()
        game.adventureRelationships.roster = game.adventureRelationships.roster?.copy(candidates = contacts.take(64).map { it.latestSnapshot })
        return game
    }

    private fun battleCandidate(characterId: String, displayName: String) = AdventureEncounterCandidate(
        characterId = characterId,
        displayName = displayName,
        heroClass = HeroClass.CLERIC,
        level = 50L,
        combatPower = 500L,
        stats = HeroStats(22L, 22L, 22L, 20L, 26L, 20L, 350L, 140L),
        equipment = EquipmentSlot.entries.map { slot ->
            AdventureRelationshipEquipmentSnapshot(slot, "${slot.labelKo} 표본", 24L, "일반")
        },
    )

    private fun actualRewardKinds(result: AdventureRelationshipResult): Int = listOf(
        result.rewardKind == AdventureEventRewardKind.EXPERIENCE && result.experienceAwarded > 0L,
        result.rewardKind == AdventureEventRewardKind.GOLD && result.goldAwarded > 0L,
        result.rewardKind == AdventureEventRewardKind.ITEM && result.itemName.isNotBlank(),
    ).count { it }

    private fun medianMillis(values: LongArray): Double = values.sorted().let { (it[99].toDouble() + it[100]) / 2_000_000.0 }
    private fun percentileMillis(values: LongArray, percentile: Double): Double =
        values.sorted()[kotlin.math.ceil(values.size * percentile).toInt() - 1].toDouble() / 1_000_000L

    private fun stagingQaDirectory(): Path {
        val cwd = Paths.get(System.getProperty("user.dir")).toRealPath()
        val project = generateSequence(cwd) { it.parent }.firstOrNull {
            it.fileName?.toString() == "project" && it.parent?.fileName?.toString() == "adventure-20260906" &&
                it.parent?.parent?.fileName?.toString() == "preintegration" && Files.isRegularFile(it.resolve("app/build.gradle.kts"))
        } ?: error("Storage probe writes only into isolated preintegration staging.")
        val staging = project.parent.toRealPath()
        val qa = staging.resolve("qa")
        require(!Files.isSymbolicLink(qa))
        Files.createDirectories(qa)
        require(qa.toRealPath().parent == staging)
        return qa
    }

    companion object { private const val EPOCH = 1_800_000_000_000L }
}
