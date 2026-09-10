package com.nullplaying.remote

import com.nullplaying.data.CharacterSlotSnapshot
import com.nullplaying.data.GameSnapshot
import com.nullplaying.data.StartupPhase
import com.nullplaying.engine.AdventureQaFixtures
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.AdventureOwnedTrait
import com.nullplaying.model.HeroClass
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import com.nullplaying.model.toAdventureEncounterRoster
import com.nullplaying.model.toAdventureRelationshipBattleParticipantSnapshot
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPlayerSnapshotContractTest {
    private val engine = SimpleGameEngine(enableAdventureTraits = true)

    @Test
    fun `upload contains eight stats and traits but no skill equipment or economic fields`() {
        val state = AdventureQaFixtures.game(engine, HeroClass.RANGER, 71L, 10L)
        state.rankingCharacterId = UUID.randomUUID().toString()
        state.skills[0] = state.skills.first().copy(usageCount = 237L)
        state.adventureTraits.owned = listOf(AdventureOwnedTrait("L01"), AdventureOwnedTrait("R03"))

        val upload = buildPublicPlayerSnapshotUploads(snapshot(state), engine::displayCombatPower)!!.single()

        assertEquals(state.rankingCharacterId, upload.characterId)
        assertEquals(state.hero.stats.maxHealth, upload.stats.maxHealth)
        assertEquals(state.hero.stats.maxMana, upload.stats.maxMana)
        assertEquals(listOf("L01", "R03"), upload.adventureTraitIds)
        val wire = Json.encodeToString(upload)
        listOf("learned_skills", "usage_count", "equipped_items", "rarity", "gold", "inventory")
            .forEach { forbidden -> assertFalse(wire.contains(forbidden)) }
    }

    @Test
    fun `malformed identity stats and opposite traits fail closed`() {
        val missingIdentity = AdventureQaFixtures.game(engine, HeroClass.WARRIOR, 72L, 10L)
        assertNull(buildPublicPlayerSnapshotUploads(snapshot(missingIdentity), engine::displayCombatPower))

        val badHealth = AdventureQaFixtures.game(engine, HeroClass.WARRIOR, 73L, 10L).apply {
            rankingCharacterId = UUID.randomUUID().toString()
            hero.stats.maxHealth = 0L
        }
        assertNull(buildPublicPlayerSnapshotUploads(snapshot(badHealth), engine::displayCombatPower))

        val oversized = AdventureQaFixtures.game(engine, HeroClass.WARRIOR, 74L, 10L).apply {
            rankingCharacterId = UUID.randomUUID().toString()
            hero.stats.strength = 1_000L
        }
        assertNull(buildPublicPlayerSnapshotUploads(snapshot(oversized), engine::displayCombatPower))

        val opposites = AdventureQaFixtures.game(engine, HeroClass.WARRIOR, 75L, 10L).apply {
            rankingCharacterId = UUID.randomUUID().toString()
            adventureTraits.owned = listOf(AdventureOwnedTrait("L01"), AdventureOwnedTrait("L02"))
        }
        assertNull(buildPublicPlayerSnapshotUploads(snapshot(opposites), engine::displayCombatPower))
    }

    @Test
    fun `server response keeps compact identity and resolves only allowed battle fields`() {
        val requesterId = UUID.randomUUID().toString()
        val public = publicSnapshot(level = 11L)
        val response = DailyPublicPlayerRosterResponse(
            rosterId = UUID.randomUUID().toString(),
            rosterDateUtc = "2026-09-07",
            requesterLevel = 10L,
            rulesVersion = SHARED_PLAYER_RULES_VERSION,
            generatedAtEpochMillis = 90_000L,
            validUntilEpochMillis = 3_700_000L,
            serverNowEpochMillis = 100_000L,
            snapshots = listOf(Json.encodeToJsonElement(public)),
        )

        val roster = response.toPublicPlayerRoster(requesterId, 10L, 1_000L)
        assertNotNull(roster)
        assertEquals(3_601_000L, roster!!.validUntilEpochMillis)
        val candidate = roster.toAdventureEncounterRoster().candidates.single()
        assertEquals(public.projectionId, candidate.characterId)
        assertNull(candidate.stats)
        assertTrue(candidate.learnedSkills.isEmpty())
        assertTrue(candidate.equipment.isEmpty())
        assertTrue(candidate.adventureTraitIds.isEmpty())
        val resolved = roster.snapshotFor(candidate.characterId)
        assertEquals(public, resolved)
        val participant = resolved!!.toAdventureRelationshipBattleParticipantSnapshot()
        assertEquals(public.stats.maxHealth, participant.stats!!.maxHealth)
        assertTrue(participant.learnedSkills.isEmpty())
        assertTrue(participant.equipment.isEmpty())
        assertEquals(public.adventureTraitIds, participant.adventureTraitIds)
    }

    @Test
    fun `invalid rows are discarded without reroll while invalid envelope fails`() {
        val requesterId = UUID.randomUUID().toString()
        val valid = response(listOf(publicSnapshot(level = 10L)))
        assertNotNull(valid.toPublicPlayerRoster(requesterId, 10L, 1_000L))
        assertNull(valid.toPublicPlayerRoster(requesterId, 11L, 1_000L))

        val badHealth = publicSnapshot(level = 10L).let { it.copy(stats = it.stats.copy(maxHealth = 0L)) }
        assertTrue(response(listOf(badHealth)).toPublicPlayerRoster(requesterId, 10L, 1_000L)!!.snapshots.isEmpty())
        assertTrue(response(listOf(publicSnapshot(level = 12L)))
            .toPublicPlayerRoster(requesterId, 10L, 1_000L)!!.snapshots.isEmpty())
        val duplicate = publicSnapshot(level = 10L)
        assertTrue(response(listOf(duplicate, duplicate))
            .toPublicPlayerRoster(requesterId, 10L, 1_000L)!!.snapshots.isEmpty())
        assertNull(valid.copy(validUntilEpochMillis = 90_100_001L)
            .toPublicPlayerRoster(requesterId, 10L, 1_000L))
    }

    @Test
    fun `emoji uses code point and utf8 byte contract`() {
        assertTrue("별빛🌟여행자".isValidPublicLabel(24))
        assertFalse("\uD83D".isValidPublicLabel(24))
        assertFalse("줄\n바꿈".isValidPublicLabel(24))
    }

    @Test
    fun `unknown enum missing field wrong type and long overflow discard only those rows`() {
        val valid = publicSnapshot(10L)
        val malformed = listOf(
            Json.parseToJsonElement("""{"projection_id":"${UUID.randomUUID()}","hero_class":"UNKNOWN"}"""),
            Json.parseToJsonElement("""{"projection_id":"${UUID.randomUUID()}","display_name":"누락"}"""),
            Json.parseToJsonElement("""{"projection_id":3,"display_name":"타입 오류"}"""),
            Json.parseToJsonElement("""{"projection_id":"${UUID.randomUUID()}","display_name":"초과","hero_class":"RANGER","level":999999999999999999999999}"""),
        )
        val envelope = response(listOf(valid)).copy(
            snapshots = listOf(Json.encodeToJsonElement(valid)) + malformed,
        )

        val roster = envelope.toPublicPlayerRoster(UUID.randomUUID().toString(), 10L, 1_000L)

        assertEquals(listOf(valid), roster!!.snapshots)
    }

    @Test
    fun `legacy roster without receipt boot remains readable but cannot anchor competitive time`() {
        val roster = response(listOf(publicSnapshot(10L))).toPublicPlayerRoster(
            requesterCharacterId = UUID.randomUUID().toString(),
            requesterLevel = 10L,
            receivedAtEpochMillis = 1_000L,
            receivedAtMonotonicMillis = 200L,
            receivedAtBootCount = 7,
        )!!
        val encoded = Json.encodeToJsonElement(roster).let { element ->
            JsonObject(element.jsonObject.filterKeys { it != "received_at_boot_count" }).toString()
        }

        val legacy = Json.decodeFromString<PublicPlayerRoster>(encoded)

        assertEquals(-1, legacy.receivedAtBootCount)
        assertNull(legacy.trustedServerNow(nowMonotonicMillis = 300L, currentBootCount = 7))
    }

    private fun snapshot(state: com.nullplaying.model.SimpleGameState) = GameSnapshot(
        revision = 1L,
        state = state,
        characters = listOf(CharacterSlotSnapshot(1, state)),
        activeSlotId = 1,
        unlockedCharacterSlotCount = 1,
        ready = true,
        startupPhase = StartupPhase.READY,
    )

    private fun response(snapshots: List<PublicPlayerSnapshot>) = DailyPublicPlayerRosterResponse(
        rosterId = UUID.randomUUID().toString(),
        rosterDateUtc = "2026-09-07",
        requesterLevel = 10L,
        rulesVersion = SHARED_PLAYER_RULES_VERSION,
        generatedAtEpochMillis = 90_000L,
        validUntilEpochMillis = 3_700_000L,
        serverNowEpochMillis = 100_000L,
        snapshots = snapshots.map { Json.encodeToJsonElement(it) },
    )

    private fun publicSnapshot(level: Long): PublicPlayerSnapshot = PublicPlayerSnapshot(
        projectionId = UUID.randomUUID().toString(),
        displayName = "바람길 여행자",
        heroClass = HeroClass.RANGER,
        level = level,
        combatPower = 50L,
        rulesVersion = SHARED_PLAYER_RULES_VERSION,
        snapshotVersion = SHARED_PLAYER_SNAPSHOT_VERSION,
        stats = PublicPlayerStats(10L, 11L, 12L, 13L, 14L, 15L, 180L, 90L),
        adventureTraitIds = listOf("R03", "T02"),
    )
}
