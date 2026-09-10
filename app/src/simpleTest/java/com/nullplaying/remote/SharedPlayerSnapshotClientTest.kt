package com.nullplaying.remote

import androidx.room.Room
import com.nullplaying.data.SimpleAccountProgressEntity
import com.nullplaying.data.SimpleDatabase
import com.nullplaying.data.SimpleGameRepository
import com.nullplaying.data.SimpleStateBackupStore
import com.nullplaying.data.SimpleStateEntity
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import com.nullplaying.model.AdventureRelationshipContact
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class SharedPlayerSnapshotClientTest {
    private lateinit var database: SimpleDatabase
    private lateinit var engine: SimpleGameEngine
    private lateinit var repository: SimpleGameRepository
    private lateinit var backupStore: MemoryBackupStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            SimpleDatabase::class.java,
        ).allowMainThreadQueries().build()
        engine = SimpleGameEngine(enableAdventureRelationships = true, enableAdventureTraits = true)
        backupStore = MemoryBackupStore()
        repository = SimpleGameRepository(database, engine, backupStore)
    }

    @After fun tearDown() = database.close()

    @Test
    fun `client uploads once and twenty same day cache uses make no additional remote call`() = runBlocking {
        repository.createCharacter("공유 영웅", HeroClass.WARRIOR, engine.rollStats(41L).stats, 41L, 1_000L)
        val state = repository.snapshots.value.state!!
        state.hero.level = 10L
        val opponent = opponent(level = 11L)
        val api = FakeApi(response(opponent))
        val client = SharedPlayerSnapshotClient(repository, api)

        val first = client.synchronize(repository.snapshots.value, nowEpochMillis = 2_000L).getOrThrow()

        assertNotNull(first)
        assertEquals(1, api.rosterCalls)
        assertEquals(1, api.lastUpload!!.size)
        assertEquals(state.hero.stats.maxHealth, api.lastUpload!!.single().stats.maxHealth)
        val stored = repository.snapshots.value.state!!
        assertEquals(first, stored.publicPlayerRoster)
        assertEquals(opponent.projectionId, stored.adventureRelationships.roster!!.candidates.single().characterId)
        assertNull(stored.adventureRelationships.roster!!.candidates.single().stats)
        assertTrue(stored.adventureRelationships.roster!!.candidates.single().learnedSkills.isEmpty())
        assertEquals(opponent, repository.publicPlayerSnapshot(opponent.projectionId))

        repeat(20) { index ->
            val cached = client.synchronize(
                repository.snapshots.value,
                nowEpochMillis = 2_500L + index,
            ).getOrThrow()
            assertEquals(first, cached)
        }
        assertEquals(1, api.rosterCalls)
        assertEquals(1, api.syncCalls)
        assertEquals(1, api.captureCalls)
    }

    @Test
    fun `same day roster is reused only after a changed local profile is published`() = runBlocking {
        repository.createCharacter("공유 영웅", HeroClass.WARRIOR, engine.rollStats(48L).stats, 48L, 1_000L)
        val state = repository.snapshots.value.state!!
        state.hero.level = 10L
        val api = FakeApi(response(opponent(level = 10L)))
        val client = SharedPlayerSnapshotClient(repository, api)
        val first = client.synchronize(repository.snapshots.value, nowEpochMillis = 2_000L).getOrThrow()

        repository.snapshots.value.state!!.hero.name = "성장한 공유 영웅"
        val reused = client.synchronize(repository.snapshots.value, nowEpochMillis = 2_500L).getOrThrow()

        assertEquals(first, reused)
        assertEquals(2, api.captureCalls)
        assertEquals(2, api.syncCalls)
        assertEquals(1, api.rosterCalls)
        assertEquals("성장한 공유 영웅", api.lastUpload!!.single().displayName)
    }

    @Test
    fun `level growth publishes before replacing the incompatible cached roster`() = runBlocking {
        repository.createCharacter("성장 영웅", HeroClass.WARRIOR, engine.rollStats(49L).stats, 49L, 1_000L)
        repository.snapshots.value.state!!.hero.level = 10L
        val api = FakeApi(response(opponent(level = 10L)))
        val client = SharedPlayerSnapshotClient(repository, api)
        client.synchronize(repository.snapshots.value, nowEpochMillis = 2_000L).getOrThrow()

        repository.snapshots.value.state!!.hero.level = 11L
        api.response = response(opponent(level = 11L)).copy(requesterLevel = 11L)
        val grown = client.synchronize(repository.snapshots.value, nowEpochMillis = 2_500L).getOrThrow()

        assertEquals(11L, grown!!.requesterLevel)
        assertEquals(2, api.syncCalls)
        assertEquals(2, api.rosterCalls)
        assertEquals(11L, api.lastUpload!!.single().level)
    }

    @Test
    fun `low level active slot publishes changed eligible slots without fetching a roster`() = runBlocking {
        repository.createCharacter("공유 고레벨", HeroClass.WARRIOR, engine.rollStats(50L).stats, 50L, 1_000L)
        repository.snapshots.value.state!!.hero.level = 20L
        repository.onAppForegrounded(now = 1_050L, elapsedRealtime = 1_050L)
        repository.createCharacter("활성 저레벨", HeroClass.RANGER, engine.rollStats(51L).stats, 51L, 1_100L)
        val api = FakeApi(response(opponent(level = 10L)))
        val client = SharedPlayerSnapshotClient(repository, api)

        assertNull(client.synchronize(repository.snapshots.value, nowEpochMillis = 2_000L).getOrThrow())
        assertEquals(1, api.captureCalls)
        assertEquals(1, api.syncCalls)
        assertEquals(0, api.rosterCalls)
        assertEquals(listOf(1), api.lastUpload!!.map(PublicPlayerSnapshotUpload::slotId))

        assertNull(client.synchronize(repository.snapshots.value, nowEpochMillis = 2_500L).getOrThrow())
        assertEquals(1, api.captureCalls)
        assertEquals(1, api.syncCalls)
        assertEquals(0, api.rosterCalls)
    }

    @Test
    fun `blank id active slot does not block another eligible slot publication`() = runBlocking {
        repository.createCharacter("공유 정상", HeroClass.WARRIOR, engine.rollStats(52L).stats, 52L, 1_000L)
        repository.snapshots.value.state!!.hero.level = 20L
        repository.onAppForegrounded(now = 1_050L, elapsedRealtime = 1_050L)
        repository.createCharacter("식별자 없음", HeroClass.RANGER, engine.rollStats(53L).stats, 53L, 1_100L)
        repository.snapshots.value.state!!.apply {
            hero.level = 10L
            rankingCharacterId = ""
        }
        val api = FakeApi(response(opponent(level = 10L)))

        assertNull(
            SharedPlayerSnapshotClient(repository, api)
                .synchronize(repository.snapshots.value, nowEpochMillis = 2_000L)
                .getOrThrow(),
        )
        assertEquals(1, api.syncCalls)
        assertEquals(0, api.rosterCalls)
        assertEquals(listOf(1), api.lastUpload!!.map(PublicPlayerSnapshotUpload::slotId))
    }

    @Test
    fun `last eligible deletion publishes an empty recovery tombstone without roster fetch`() = runBlocking {
        repository.createCharacter("삭제 예정", HeroClass.WARRIOR, engine.rollStats(54L).stats, 54L, 1_000L)
        repository.snapshots.value.state!!.hero.level = 10L
        val api = FakeApi(response(opponent(level = 10L)))
        val client = SharedPlayerSnapshotClient(repository, api)
        client.synchronize(repository.snapshots.value, nowEpochMillis = 2_000L).getOrThrow()
        val slotId = repository.snapshots.value.activeSlotId!!

        repository.deleteCharacter(slotId).getOrThrow()
        assertNull(client.synchronize(repository.snapshots.value, nowEpochMillis = 2_500L).getOrThrow())

        assertEquals(2, api.syncCalls)
        assertTrue(api.lastUpload!!.isEmpty())
        assertEquals(1, api.rosterCalls)
    }

    @Test
    fun `ineligible active character performs ttl cleanup with zero authentication or rpc calls`() = runBlocking {
        repository.createCharacter("초보 영웅", HeroClass.WARRIOR, engine.rollStats(40L).stats, 40L, 1_000L)
        val initial = repository.snapshots.value.state!!
        initial.publicPlayerRoster = PublicPlayerRoster(
            requesterCharacterId = initial.rankingCharacterId,
            requesterLevel = 10L,
            rosterId = UUID.randomUUID().toString(),
            rosterDateUtc = "2026-09-07",
            rulesVersion = SHARED_PLAYER_RULES_VERSION,
            receivedAtEpochMillis = 100L,
            validUntilEpochMillis = 200L,
            snapshots = emptyList(),
        )
        val api = FakeApi(response(opponent(level = 10L)))
        val client = SharedPlayerSnapshotClient(repository, api)

        repeat(5) {
            assertNull(client.synchronize(repository.snapshots.value, nowEpochMillis = 300L).getOrThrow())
        }
        assertNull(repository.snapshots.value.state!!.publicPlayerRoster)

        repository.snapshots.value.state!!.apply {
            hero.level = 10L
            rankingCharacterId = ""
        }
        repeat(5) {
            assertNull(client.synchronize(repository.snapshots.value, nowEpochMillis = 400L).getOrThrow())
        }

        assertEquals(0, api.captureCalls)
        assertEquals(0, api.syncCalls)
        assertEquals(0, api.rosterCalls)
    }

    @Test
    fun `auth replacement cleanup removes full and compact roster together`() = runBlocking {
        repository.createCharacter("공유 영웅", HeroClass.WARRIOR, engine.rollStats(42L).stats, 42L, 1_000L)
        repository.snapshots.value.state!!.hero.level = 10L
        val remote = opponent(level = 10L)
        val api = FakeApi(response(remote))
        SharedPlayerSnapshotClient(repository, api)
            .synchronize(repository.snapshots.value, nowEpochMillis = 2_000L).getOrThrow()

        repository.clearPublicPlayerRosters(2_100L)

        assertNull(repository.snapshots.value.state!!.publicPlayerRoster)
        assertNull(repository.snapshots.value.state!!.adventureRelationships.roster)
        assertTrue(repository.publicPlayerSnapshot(remote.projectionId) == null)
    }

    @Test
    fun `expired roster purge keeps compact contact and removes raw data from both saves`() = runBlocking {
        repository.createCharacter("만료 영웅", HeroClass.WARRIOR, engine.rollStats(44L).stats, 44L, 1_000L)
        val state = repository.snapshots.value.state!!
        state.hero.level = 10L
        val remote = opponent(level = 10L)
        SharedPlayerSnapshotClient(repository, FakeApi(response(remote)))
            .synchronize(repository.snapshots.value, nowEpochMillis = 2_000L).getOrThrow()
        val stored = repository.snapshots.value.state!!
        val compact = stored.adventureRelationships.roster!!.candidates.single()
        val legacyFullContact = compact.copy(
            stats = stored.hero.stats,
            adventureTraitIds = listOf("T02"),
        )
        stored.adventureRelationships = stored.adventureRelationships.copy(
            contacts = listOf(
                AdventureRelationshipContact(
                    characterId = compact.characterId,
                    score = 31,
                    meetings = 2L,
                    latestSnapshot = legacyFullContact,
                ),
            ),
        )
        repository.selectCharacter(1, 2_100L).getOrThrow()

        repository.clearExpiredPublicPlayerRosters(3_602_000L)

        val after = repository.snapshots.value.state!!
        assertNull(after.publicPlayerRoster)
        assertNull(after.adventureRelationships.roster)
        assertEquals(31, after.adventureRelationships.contacts.single().score)
        assertNull(after.adventureRelationships.contacts.single().latestSnapshot.stats)
        assertTrue(after.adventureRelationships.contacts.single().latestSnapshot.adventureTraitIds.isEmpty())
        assertNull(savedFromRoom().publicPlayerRoster)
        assertNull(savedFromBackup().publicPlayerRoster)
        assertEquals(31, savedFromBackup().adventureRelationships.contacts.single().score)
    }

    @Test
    fun `expired roster is purged before an authentication refresh failure`() = runBlocking {
        repository.createCharacter("오프라인 영웅", HeroClass.WARRIOR, engine.rollStats(46L).stats, 46L, 1_000L)
        repository.snapshots.value.state!!.hero.level = 10L
        val remote = opponent(level = 10L)
        SharedPlayerSnapshotClient(repository, FakeApi(response(remote)))
            .synchronize(repository.snapshots.value, nowEpochMillis = 2_000L).getOrThrow()

        val failed = SharedPlayerSnapshotClient(
            repository,
            FakeApi(response(remote), failCapture = true),
        ).synchronize(repository.snapshots.value, nowEpochMillis = 3_602_000L)

        assertTrue(failed.isFailure)
        assertNull(repository.snapshots.value.state!!.publicPlayerRoster)
        assertNull(savedFromRoom().publicPlayerRoster)
        assertNull(savedFromBackup().publicPlayerRoster)
    }

    @Test
    fun `disabled feature purges a still valid persisted roster during initialization`() = runBlocking {
        repository.createCharacter("중지 영웅", HeroClass.WARRIOR, engine.rollStats(45L).stats, 45L, 1_000L)
        repository.snapshots.value.state!!.hero.level = 10L
        SharedPlayerSnapshotClient(repository, FakeApi(response(opponent(level = 10L))))
            .synchronize(repository.snapshots.value, nowEpochMillis = 2_000L).getOrThrow()

        val disabledRepository = SimpleGameRepository(
            database = database,
            engine = engine,
            backupStore = backupStore,
            publicPlayerRosterRetentionEnabled = false,
        )
        disabledRepository.initialize(2_100L)

        assertNull(disabledRepository.snapshots.value.state!!.publicPlayerRoster)
        assertNull(disabledRepository.snapshots.value.state!!.adventureRelationships.roster)
        assertNull(savedFromRoom().publicPlayerRoster)
        assertNull(savedFromBackup().publicPlayerRoster)
    }

    @Test
    fun `account cleanup requested before save initialization purges the loaded roster`() = runBlocking {
        repository.createCharacter("교체 영웅", HeroClass.WARRIOR, engine.rollStats(47L).stats, 47L, 1_000L)
        repository.snapshots.value.state!!.hero.level = 10L
        SharedPlayerSnapshotClient(repository, FakeApi(response(opponent(level = 10L))))
            .synchronize(repository.snapshots.value, nowEpochMillis = 2_000L).getOrThrow()

        val replacementRepository = SimpleGameRepository(database, engine, backupStore)
        replacementRepository.clearPublicPlayerRosters(2_050L)
        replacementRepository.initialize(2_100L)

        assertNull(replacementRepository.snapshots.value.state!!.publicPlayerRoster)
        assertNull(replacementRepository.snapshots.value.state!!.adventureRelationships.roster)
        assertNull(savedFromRoom().publicPlayerRoster)
        assertNull(savedFromBackup().publicPlayerRoster)
    }

    @Test
    fun `publication failure does not block retrieval of an existing server roster`() = runBlocking {
        repository.createCharacter("복구 영웅", HeroClass.WARRIOR, engine.rollStats(43L).stats, 43L, 1_000L)
        repository.snapshots.value.state!!.hero.level = 10L
        val api = FakeApi(response(opponent(level = 10L)), failSync = true)

        val roster = SharedPlayerSnapshotClient(repository, api)
            .synchronize(repository.snapshots.value, nowEpochMillis = 2_000L)
            .getOrThrow()

        assertNotNull(roster)
        assertEquals(1, api.syncCalls)
        assertEquals(1, api.rosterCalls)
        assertEquals(roster, repository.snapshots.value.state!!.publicPlayerRoster)
    }

    private fun response(opponent: PublicPlayerSnapshot) = DailyPublicPlayerRosterResponse(
        rosterId = UUID.randomUUID().toString(),
        rosterDateUtc = "2026-09-07",
        requesterLevel = 10L,
        rulesVersion = SHARED_PLAYER_RULES_VERSION,
        generatedAtEpochMillis = 1_900L,
        validUntilEpochMillis = 3_602_000L,
        serverNowEpochMillis = 2_000L,
        snapshots = listOf(Json.encodeToJsonElement(opponent)),
    )

    private fun opponent(level: Long) = PublicPlayerSnapshot(
        projectionId = UUID.randomUUID().toString(),
        displayName = "맞은편 여행자",
        heroClass = HeroClass.RANGER,
        level = level,
        combatPower = 50L,
        rulesVersion = SHARED_PLAYER_RULES_VERSION,
        snapshotVersion = SHARED_PLAYER_SNAPSHOT_VERSION,
        stats = PublicPlayerStats(10L, 11L, 12L, 13L, 14L, 15L, 170L, 95L),
        adventureTraitIds = listOf("T02"),
    )

    private suspend fun savedFromRoom(): SimpleGameState = Json.decodeFromString(
        database.stateDao().loadAllCharacterSlots().single().payload,
    )

    private suspend fun savedFromBackup(): SimpleGameState = Json.decodeFromString(
        requireNotNull(backupStore[1]).payload,
    )

    private class FakeApi(
        var response: DailyPublicPlayerRosterResponse,
        private val failSync: Boolean = false,
        private val failCapture: Boolean = false,
    ) : AuthenticatedSharedPlayerApi {
        var captureCalls = 0
        var syncCalls = 0
        var rosterCalls = 0
        var lastUpload: List<PublicPlayerSnapshotUpload>? = null
        var publishedProfile: List<PublicPlayerSnapshotUpload>? = null

        override suspend fun captureSessionIdentity(): String {
            captureCalls += 1
            return if (failCapture) error("authentication unavailable") else "test-session"
        }

        override fun isSessionIdentityCurrent(identity: String): Boolean = identity == "test-session"

        override fun isPlayerNetworkProfileCurrent(
            snapshots: List<PublicPlayerSnapshotUpload>,
            nowEpochMillis: Long,
        ): Boolean = publishedProfile == snapshots

        override fun shouldPublishPlayerNetworkProfile(
            snapshots: List<PublicPlayerSnapshotUpload>,
            nowEpochMillis: Long,
        ): Boolean = when (val published = publishedProfile) {
            null -> snapshots.isNotEmpty()
            else -> published != snapshots
        }

        override suspend fun syncPublicPlayerSnapshots(
            snapshots: List<PublicPlayerSnapshotUpload>,
        ): SharedPlayerPublicationMode {
            syncCalls += 1
            lastUpload = snapshots
            if (failSync) error("publication unavailable")
            publishedProfile = snapshots
            return SharedPlayerPublicationMode.UNIFIED
        }

        override suspend fun getDailyPublicPlayerRoster(
            characterId: String,
            rulesVersion: Int,
        ): DailyPublicPlayerRosterResponse {
            rosterCalls += 1
            return response
        }
    }

    private class MemoryBackupStore : SimpleStateBackupStore {
        private val states = mutableMapOf<Int, SimpleStateEntity>()
        private var account: SimpleAccountProgressEntity? = null
        operator fun get(slotId: Int): SimpleStateEntity? = states[slotId]
        override suspend fun load(slotId: Int) = states[slotId]
        override suspend fun save(entity: SimpleStateEntity) { states[entity.id] = entity }
        override suspend fun clear(slotId: Int) { states.remove(slotId) }
        override suspend fun loadAccountProgress() = account
        override suspend fun saveAccountProgress(entity: SimpleAccountProgressEntity) { account = entity }
    }
}
