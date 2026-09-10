package com.nullplaying.engine

import com.nullplaying.model.*
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure codec/envelope evidence. Never accesses Room, Android backup, a real save or a server. */
class AdventureBackupPayloadBoundaryTest {
    @Test
    fun `retained social and trait fields survive production codec and local backup envelope for three slots`() {
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val game = retainedFixture()
        val beforeTraits = codec.encodeToString(game)
        fillTraitCollections(game)
        val encoded = codec.encodeToString(game)
        val restored = codec.decodeFromString<SimpleGameState>(encoded)
        assertEquals(game, restored)
        assertEquals(100, restored.adventureRelationships.contacts.size)
        assertEquals(1_600, restored.adventureRelationships.contacts.sumOf { it.memories.size })
        assertEquals(9, restored.adventureTraits.owned.size)
        assertEquals(512, restored.adventureTraits.decisions.size)
        assertEquals(128, restored.adventureTraits.recentRewardTraces.size)

        val legacyObject = JsonObject(codec.parseToJsonElement(encoded).jsonObject.filterKeys {
            it != "adventureTraits" && it != "adventureRelationships" && it != "adventureJourney"
        })
        val legacy = codec.decodeFromString<SimpleGameState>(legacyObject.toString())
        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, legacy.schemaVersion)
        assertTrue(legacy.adventureTraits.owned.isEmpty())
        assertTrue(legacy.adventureRelationships.contacts.isEmpty())

        val payloadBytes = encoded.toByteArray(Charsets.UTF_8).size
        val defaultBytes = Json.encodeToString(game).toByteArray(Charsets.UTF_8).size
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(game.lastSettledAt.toString().toByteArray(Charsets.UTF_8))
            update(0.toByte())
            update(encoded.toByteArray(Charsets.UTF_8))
        }.digest().joinToString("") { "%02x".format(it) }
        // Exact current FileSimpleStateBackupStore envelope fields, without any AtomicFile I/O.
        val envelope = buildJsonObject {
            put("formatVersion", 1); put("payload", encoded); put("updatedAt", game.lastSettledAt); put("checksum", digest)
        }.toString()
        val inner = codec.parseToJsonElement(envelope).jsonObject.getValue("payload").jsonPrimitive.content
        assertEquals(encoded, inner)
        assertEquals(game, codec.decodeFromString<SimpleGameState>(inner))
        val envelopeBytes = envelope.toByteArray(Charsets.UTF_8).size
        val accountEnvelope = buildJsonObject {
            put("formatVersion", 1); put("unlockedCharacterSlots", 3); put("activeCharacterSlotId", 3)
            put("revision", 1L); put("checksum", "0".repeat(64))
        }.toString().toByteArray(Charsets.UTF_8).size
        assertTrue(envelopeBytes > payloadBytes)
        val rows = listOf(
            listOf("compact_contacts_only_production_codec", beforeTraits.toByteArray(Charsets.UTF_8).size),
            listOf("contacts_and_traits_default_codec", defaultBytes),
            listOf("contacts_and_traits_production_codec", payloadBytes),
            listOf("trait_increment_in_production_payload", payloadBytes - beforeTraits.toByteArray(Charsets.UTF_8).size),
            listOf("local_backup_envelope_one_slot", envelopeBytes),
            listOf("three_primary_slot_payloads", payloadBytes * 3L),
            listOf("three_backup_envelopes_and_account_envelope", envelopeBytes * 3L + accountEnvelope),
        )
        AdventureQaFixtures.stagingQaDirectory().resolve("adventure-backup-payload-boundary.csv").toFile().writeText(
            "fixture,utf8_bytes\n" + rows.joinToString("\n") { it.joinToString(",") } + "\n")
    }

    private fun retainedFixture(): SimpleGameState {
        val game = AdventureQaFixtures.game(SimpleGameEngine(), HeroClass.CLERIC, 503L, 100L, 100L)
        val person = AdventureEncounterCandidate("00000000-0000-4000-8000-000000000000", "가".repeat(24), HeroClass.CLERIC, 100L, 1_000L)
        game.adventureRelationships.roster = AdventureEncounterRoster("retained-fixture", AdventureQaFixtures.EPOCH,
            AdventureQaFixtures.EPOCH + 86_400_000L, listOf(person))
        AdventureRelationshipEngine.initialize(game, AdventureQaFixtures.EPOCH)
        game.adventureRelationships.nextEncounterAt = AdventureQaFixtures.EPOCH
        val selected = requireNotNull(AdventureRelationshipEngine.tryBegin(game, AdventureQaFixtures.EPOCH))
        val results = mutableListOf<AdventureRelationshipResult>()
        game.adventureRelationships.contacts = (0 until 100).map { index ->
            val candidate = person.copy(characterId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}")
            val memories = (0 until 16).map { memory ->
                val at = AdventureQaFixtures.EPOCH + (index * 16L + memory) * 21_600_000L
                val run = selected.copy(sequence = index * 16L + memory + 1L, candidate = candidate,
                    startedAt = at, startedActiveMillis = at - AdventureQaFixtures.EPOCH, scoreBefore = memory, scoreDelta = 1)
                AdventureRelationshipResult(run, at + run.durationMillis, run.experienceReward, memory + 1, 1L)
            }
            results += memories
            AdventureRelationshipContact(candidate.characterId, 16, 16L, memories.first().occurredAt,
                memories.last().occurredAt, latestSnapshot = candidate, memories = memories.map { it.toMemory() })
        }
        game.adventureRelationships.pending = null
        game.adventureRelationships.recentResults = results.takeLast(32)
        game.adventureRelationships.lastResult = results.last()
        game.adventureRelationships.roster = game.adventureRelationships.roster!!.copy(
            candidates = game.adventureRelationships.contacts.take(64).map { it.latestSnapshot })
        val event = AdventureEventEngine.begin(game, game.lastSettledAt)
        game.adventureJourney.pending = event
        game.adventurePhase = AdventurePhase.EVENT
        game.actionStartedAt = game.lastSettledAt
        game.actionEndsAt = game.lastSettledAt + event.durationMillis
        val bagCount = game.inventoryCapacity().toInt() - 1
        game.inventory = (0 until bagCount).map {
            InventoryItem(it + 1L, "보관 중인 전리품 $it", "일반", "전리품", 100L)
        }.toMutableList()
        game.totalItemsFound = bagCount.toLong()
        return game
    }

    private fun fillTraitCollections(game: SimpleGameState) {
        AdventureTraitEngine.initialize(game)
        val traits = game.adventureTraits
        val now = game.lastSettledAt
        val ids = listOf("C03", "L01", "L04", "L05", "S01", "S05", "E03", "G01", "R05")
        val baseKey = "event:9223372036854775000"
        traits.owned = ids.mapIndexed { index, id -> AdventureOwnedTrait(id, now, index + 1L) }
        traits.evidence = AdventureTraitCatalog.all.associate { definition -> definition.id to (0 until 12).map {
            AdventureTraitEvidence("combat:922337203685477${it.toString().padStart(4, '0')}", "retained-monster-family", it % 2 == 0, "adventure:variety")
        } }
        traits.observedSourceKeys = (0 until 512).map { "combat:922337203685477${it.toString().padStart(4, '0')}" }
        traits.decisions = (0 until 512).map { AdventureTraitDecision("L01:event:922337203685477${it.toString().padStart(4, '0')}:reward:1", it, 10, it < 10) }
        traits.opportunityCounts = (AdventureTraitCatalog.all.map { it.id } + "FORMATION").associateWith { Long.MAX_VALUE }
        traits.procCounts = AdventureTraitCatalog.all.associate { it.id to Long.MAX_VALUE }
        traits.actualEffectCounts = traits.procCounts
        traits.recentChanges = (0 until 32).map { AdventureTraitChange(it + 1L, ids[it % ids.size], AdventureTraitChangeKind.ACQUIRED, baseKey, now, "adventure:variety") }
        traits.recentActivations = (0 until 32).map { AdventureTraitActivation(it + 1L, "L01", "$baseKey:reward:$it", 1L,
            now, AdventureTraitEffectKind.EXTRA_ITEM, 1L, 2L, 750L, "추가 전리품 저장 표본") }
        traits.visibleActivations = traits.recentActivations.takeLast(4)
        traits.recentRewardTraces = (0 until 128).map { AdventureTraitRewardTrace(it + 1L, "$baseKey:reward:$it", "PRIMARY",
            it + 1L, "획득한 장비 저장 표본", EquipmentSlot.entries[it % 6], "영웅", 500L, 510L, true, it % 2 == 0, 100L) }
        val event = requireNotNull(game.adventureJourney.pending)
        traits.source = AdventureTraitSource(baseKey, "event", "event:ruins", now, ids,
            baseEvent = event, retryRun = event.copy(approachId = "retained-alternate"))
        traits.weaponUses = (0 until 24).map { AdventureTraitWeaponUse("$baseKey:equipment:$it", EquipmentSlot.WEAPON, 500L) }
        traits.recentExperienceFamilies = listOf("family-1", "family-2", "family-3")
        traits.soldOriginalFamilies = (0 until 32).map { "monster-family-$it" }
        traits.primaryRewardOrigins = game.inventory.associate { it.id to "PRIMARY" }
        traits.primaryRewardFamilies = game.inventory.associate { it.id to "monster-family-${it.id}" }
        traits.carriedOriginalFamilies = traits.primaryRewardFamilies.values.distinct()
        traits.carriedEvidenceContexts = traits.carriedOriginalFamilies
        traits.equipmentOrigins = EquipmentSlot.entries.associateWith { "PRIMARY" }
        traits.lastBaseEquipmentPowers = game.equipment.map { it.power }
        traits.prerequisites = setOf("exploration", "packing", "carried", "sale", "considered", "empty_visit")
        traits.pendingEvidence = listOf(AdventureTraitEvidenceUpdate(baseKey, "event:ruins", ids.toSet(), emptySet(), now, "adventure:ruins"))
    }
}
