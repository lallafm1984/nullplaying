package com.nullplaying.data

import androidx.room.Room
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEncounterRoster
import com.nullplaying.model.AdventureEventContext
import com.nullplaying.model.AdventureRelationshipContact
import com.nullplaying.model.HeroClass
import com.nullplaying.model.SimpleGameState
import com.nullplaying.remote.AdventureEncounterRosterAdapter
import com.nullplaying.remote.CompactLeaderboardRow
import com.nullplaying.remote.DailyLeaderboardResponse
import com.nullplaying.remote.applyDailyRankingResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AdventureEncounterRepositoryTest {
    private lateinit var database: SimpleDatabase
    private lateinit var engine: SimpleGameEngine
    private lateinit var repository: SimpleGameRepository
    private lateinit var backupStore: MemoryBackupStore
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SimpleDatabase::class.java)
            .allowMainThreadQueries().build()
        engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureRelationships = true)
        backupStore = MemoryBackupStore()
        repository = SimpleGameRepository(database, engine, backupStore)
    }

    @After fun tearDown() = database.close()

    @Test
    fun `public daily DTO flows through adapter and repository with all own slots excluded`() = runBlocking {
        createHero()
        val firstId = repository.snapshots.value.state!!.rankingCharacterId
        repository.createCharacter("둘째 여행자", HeroClass.RANGER, engine.rollStats(42L).stats, 42L, 1_002L)
        val second = repository.snapshots.value.state!!
        second.hero.level = 21L
        val secondId = second.rankingCharacterId
        val response = DailyLeaderboardResponse(
            snapshotId = "dto-1", settledAtEpochMillis = 500L, nextSettlementAtEpochMillis = 100_000L,
            generatedAtEpochMillis = 600L, serverNowEpochMillis = 2_000L,
            entries = listOf(row(firstId, 20L), row(secondId, 21L), row("peer-19", 19L),
                row("peer-20", 20L), row("peer-21", 21L), row("peer-22", 22L)),
            ownEntries = listOf(row(firstId, 20L)),
        )
        val dto = applyDailyRankingResponse(response, null, firstId, 2_000L)
        val roster = AdventureEncounterRosterAdapter.fromDailyRanking(dto, 2_000L)!!
        assertTrue(repository.updateAdventureEncounterRoster(roster, 2_000L, 1_000L))
        val stored = repository.snapshots.value.characters.associate { it.slotId to it.state.adventureRelationships.roster!! }
        assertEquals(listOf("peer-19", "peer-20", "peer-21"), stored.getValue(1).candidates.map { it.characterId })
        assertEquals(listOf("peer-20", "peer-21", "peer-22"), stored.getValue(2).candidates.map { it.characterId })
        assertEquals(stored.getValue(1), saved(1).adventureRelationships.roster)
        assertEquals(stored.getValue(2), saved(2).adventureRelationships.roster)
    }

    @Test
    fun `same snapshot freezes first receive expiry and candidates across repeated reads and restart`() = runBlocking {
        createHero()
        val first = roster(candidates = listOf(candidate("first")))
        assertTrue(repository.updateAdventureEncounterRoster(first, 2_000L, 1_000L))
        assertFalse(repository.updateAdventureEncounterRoster(
            first.copy(receivedAt = 3_000L, validUntil = 200_000L, candidates = listOf(
                candidate("first").copy(displayName = "달라진 이름"), candidate("replacement"),
            )),
            3_000L, 2_000L,
        ))
        assertEquals(first, saved().adventureRelationships.roster)
        val restarted = SimpleGameRepository(database, engine, MemoryBackupStore())
        restarted.initialize(3_001L)
        assertEquals(first, restarted.snapshots.value.state!!.adventureRelationships.roster)
    }

    @Test
    fun `same daily version can remove newly excluded ownership without extending or adding candidates`() = runBlocking {
        createHero()
        val first = roster(candidates = listOf(candidate("newly-owned"), candidate("remaining")))
        assertTrue(repository.updateAdventureEncounterRoster(first, 2_000L, 1_000L))
        val sanitizedAfterAccountChange = first.copy(
            receivedAt = 3_000L, validUntil = 200_000L,
            candidates = listOf(candidate("remaining").copy(displayName = "새 이름"), candidate("new-peer")),
        )
        assertTrue(repository.updateAdventureEncounterRoster(sanitizedAfterAccountChange, 3_000L, 2_000L))
        assertEquals(first.copy(candidates = listOf(candidate("remaining"))), saved().adventureRelationships.roster)
    }

    @Test
    fun `new roster settles earlier time without its future candidates and retains generated event rows`() = runBlocking {
        createHero()
        repository.snapshots.value.state!!.offlineAdventureMillis = 600_000L
        repository.snapshots.value.state!!.adventureJourney.nextEventAt = 1_003L
        repository.snapshots.value.state!!.adventureJourney.nextEventContext = AdventureEventContext.POST_COMBAT
        repository.onAppBackgrounded(1_002L, 1L)
        val previous = saved()
        val expected = json.decodeFromString<SimpleGameState>(json.encodeToString(previous))
        val expectedDelta = engine.settleOfflineWithOfflineAdventure(expected, 180_000L)
        assertTrue(expectedDelta.recentEvents.isNotEmpty())
        val incoming = roster(receivedAt = 180_000L, validUntil = 300_000L)
        assertTrue(repository.updateAdventureEncounterRoster(incoming, 180_000L, 179_000L))
        val actual = saved()
        assertEquals(expected.actionSequence, actual.actionSequence)
        assertEquals(expected.hero.experience, actual.hero.experience)
        assertEquals(expected.adventureRelationships.totalEncounters, actual.adventureRelationships.totalEncounters)
        assertEquals(expected.adventureRelationships.contacts, actual.adventureRelationships.contacts)
        assertEquals(incoming.snapshotId, actual.adventureRelationships.roster!!.snapshotId)
        assertTrue(actual.actionSequence > previous.actionSequence)
        val eventRows = database.recentAdventureEventDao().loadRecent(1, 300)
        expectedDelta.recentEvents.forEach { event ->
            assertTrue(eventRows.any { it.occurredAt == event.occurredAt && it.eventType == event.type.name })
        }
    }

    @Test
    fun `slot candidate pool is stable bounded and validates direct fixture input`() = runBlocking {
        createHero()
        val many = (0..79).reversed().map { candidate("peer-${it.toString().padStart(3, '0')}") }
        val input = roster(candidates = many + candidate("duplicate") + candidate("duplicate") +
            candidate("out-of-range", level = 22L) + candidate("invalid", level = Long.MAX_VALUE))
        assertTrue(repository.updateAdventureEncounterRoster(input, 2_000L, 1_000L))
        val candidates = saved().adventureRelationships.roster!!.candidates
        assertEquals(64, candidates.size)
        assertEquals((0..63).map { "peer-${it.toString().padStart(3, '0')}" }, candidates.map { it.characterId })
    }

    @Test
    fun `future stale and identity-invalid input cannot alter persisted state`() = runBlocking {
        createHero()
        val before = database.stateDao().load()
        assertFalse(repository.updateAdventureEncounterRoster(roster(receivedAt = 2_001L), 2_000L, 1_000L))
        assertFalse(repository.updateAdventureEncounterRoster(roster(validUntil = 2_000L), 2_000L, 1_000L))
        assertFalse(repository.updateAdventureEncounterRoster(roster(), 2_000L, 1_000L) { false })
        assertEquals(before, database.stateDao().load())
        assertNull(repository.snapshots.value.state!!.adventureRelationships.roster)
    }

    @Test
    fun `identity invalidated during persistence rolls back roster state and recent event rows`() = runBlocking {
        createHero()
        val before = database.stateDao().loadAllCharacterSlots()
        val beforeEvents = database.recentAdventureEventDao().loadRecent(1, 300)
        var checks = 0
        val result = runCatching {
            repository.updateAdventureEncounterRoster(roster(), 2_000L, 1_000L) { ++checks < 3 }
        }
        assertTrue(result.isFailure)
        assertEquals(3, checks)
        assertEquals(before, database.stateDao().loadAllCharacterSlots())
        assertEquals(beforeEvents, database.recentAdventureEventDao().loadRecent(1, 300))
        assertNull(repository.snapshots.value.state!!.adventureRelationships.roster)
        assertTrue(repository.updateAdventureEncounterRoster(roster(), 2_000L, 1_000L))
    }

    @Test
    fun `rollback restores account unlock memory database and recovery metadata after level up`() = runBlocking {
        createHero()
        val state = repository.snapshots.value.state!!
        state.hero.level = 49L
        state.hero.experience = engine.experienceRequired(49L) - 1L
        state.offlineAdventureMillis = 600_000L
        state.adventureJourney.nextEventAt = 1_003L
        repository.onAppBackgrounded(1_002L, 1L)
        val before = saved()
        val previousAccount = database.accountProgressDao().load()
        val expected = json.decodeFromString<SimpleGameState>(json.encodeToString(before))
        engine.settleOfflineWithOfflineAdventure(expected, 180_000L)
        assertTrue(expected.hero.level >= 50L)
        assertEquals(2, repository.snapshots.value.unlockedCharacterSlotCount)
        var checks = 0
        val result = runCatching {
            repository.updateAdventureEncounterRoster(
                roster(receivedAt = 180_000L, validUntil = 300_000L), 180_000L, 179_000L,
            ) { ++checks < 3 }
        }
        assertTrue(result.isFailure)
        assertEquals(before, saved())
        assertEquals(previousAccount, database.accountProgressDao().load())
        assertEquals(previousAccount, backupStore.loadAccountProgress())
        // Re-emit without advancing past the first pending action, so leaked memory would show.
        assertTrue(repository.selectCharacter(1, 1_003L).isSuccess)
        assertEquals(2, repository.snapshots.value.unlockedCharacterSlotCount)
        val restarted = SimpleGameRepository(database, engine, backupStore)
        restarted.initialize(1_004L)
        assertEquals(2, restarted.snapshots.value.unlockedCharacterSlotCount)
    }

    @Test
    fun `new daily pool preserves relationship memories while obsolete input cannot replace newer receipt`() = runBlocking {
        createHero()
        val contact = AdventureRelationshipContact(characterId = "known", latestSnapshot = candidate("known"), meetings = 2L)
        repository.snapshots.value.state!!.adventureRelationships.contacts = listOf(contact)
        assertTrue(repository.updateAdventureEncounterRoster(roster(), 2_000L, 1_000L))
        assertTrue(repository.updateAdventureEncounterRoster(
            roster(receivedAt = 3_000L).copy(snapshotId = "daily-2", candidates = emptyList()), 3_000L, 2_000L,
        ))
        assertEquals(listOf(contact), saved().adventureRelationships.contacts)
        assertEquals(emptyList<Any>(), saved().adventureRelationships.roster!!.candidates)
        assertFalse(repository.updateAdventureEncounterRoster(roster(), 3_001L, 2_001L))
        assertEquals("daily-2", saved().adventureRelationships.roster!!.snapshotId)
    }

    private suspend fun createHero() {
        repository.createCharacter("첫째 여행자", HeroClass.WARRIOR, engine.rollStats(41L).stats, 41L, 1_000L)
        repository.snapshots.value.state!!.hero.level = 20L
        repository.onAppForegrounded(1_001L, 0L)
    }

    private suspend fun saved(slot: Int = 1): SimpleGameState =
        json.decodeFromString(database.stateDao().loadAllCharacterSlots().single { it.id == slot }.payload)

    private fun candidate(id: String, level: Long = 20L) =
        AdventureEncounterCandidate(id, "길동무", HeroClass.RANGER, level, 50L)

    private fun roster(receivedAt: Long = 2_000L, validUntil: Long = 100_000L,
        candidates: List<AdventureEncounterCandidate> = listOf(candidate("peer"))) =
        AdventureEncounterRoster("daily-1", receivedAt, validUntil, candidates)

    private fun row(id: String, level: Long) = CompactLeaderboardRow(
        rankNumber = 1L, listIndex = 1L, characterId = id, displayName = "길동무", heroClass = "RANGER",
        level = level, combatPower = 50L, achievedAtEpochMillis = 400L,
    )

    private class MemoryBackupStore : SimpleStateBackupStore {
        private val states = mutableMapOf<Int, SimpleStateEntity>()
        private var account: SimpleAccountProgressEntity? = null
        override suspend fun load(slotId: Int) = states[slotId]
        override suspend fun save(entity: SimpleStateEntity) { states[entity.id] = entity }
        override suspend fun clear(slotId: Int) { states.remove(slotId) }
        override suspend fun loadAccountProgress() = account
        override suspend fun saveAccountProgress(entity: SimpleAccountProgressEntity) { account = entity }
    }
}
