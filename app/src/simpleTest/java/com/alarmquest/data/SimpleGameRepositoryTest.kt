package com.alarmquest.data

import androidx.room.Room
import com.alarmquest.engine.SimpleGameEngine
import com.alarmquest.model.HeroClass
import com.alarmquest.model.SimpleGameState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SimpleGameRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: SimpleDatabase
    private lateinit var backupStore: MemoryBackupStore
    private lateinit var engine: SimpleGameEngine

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            SimpleDatabase::class.java,
        ).allowMainThreadQueries().build()
        backupStore = MemoryBackupStore()
        engine = SimpleGameEngine()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `each save backs up the previous valid payload`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats

        repository.createCharacter("백업 검사", HeroClass.WARRIOR, stats, 88L, 1_000L)
        val firstSave = database.stateDao().load()
        assertNotNull(firstSave)
        assertNull(backupStore[1])

        repository.onAppForegrounded(now = 1_001L, elapsedRealtime = 0L)

        assertEquals(firstSave, backupStore[1])
        assertTrue(database.stateDao().load()!!.updatedAt > firstSave!!.updatedAt)
    }

    @Test
    fun `corrupt current payload restores the previous backup automatically`() = runBlocking {
        val original = repository()
        val stats = engine.rollStats(77L).stats
        original.createCharacter("복구될 영웅", HeroClass.CLERIC, stats, 88L, 2_000L)
        original.onAppForegrounded(now = 2_001L, elapsedRealtime = 0L)
        val validBackup = requireNotNull(backupStore[1])

        database.stateDao().save(
            SimpleStateEntity(payload = "{broken-json", updatedAt = 2_002L),
        )
        val restored = repository()
        restored.initialize(now = 2_003L)

        val snapshot = restored.snapshots.value
        assertTrue(snapshot.ready)
        assertTrue(snapshot.recoveredFromBackup)
        assertEquals("복구될 영웅", snapshot.state!!.hero.name)
        assertEquals(validBackup, backupStore[1])
        val repaired = Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload)
        assertEquals("복구될 영웅", repaired.hero.name)
    }

    @Test
    fun `unreadable current and backup never look like a new character`() = runBlocking {
        database.stateDao().save(
            SimpleStateEntity(payload = "{broken-current", updatedAt = 3_000L),
        )
        backupStore[1] = SimpleStateEntity(payload = "{broken-backup", updatedAt = 2_999L)
        val repository = repository()

        repository.initialize(now = 3_001L)

        val snapshot = repository.snapshots.value
        assertFalse(snapshot.ready)
        assertEquals(StartupPhase.FAILED, snapshot.startupPhase)
        assertNull(snapshot.state)
    }

    @Test
    fun `an existing single save remains character slot one after restart`() = runBlocking {
        val original = repository()
        val stats = engine.rollStats(77L).stats
        original.createCharacter("기존 모험가", HeroClass.PALADIN, stats, 79L, 3_050L)
        assertEquals(1, database.stateDao().load()!!.id)

        val restarted = repository()
        restarted.initialize(now = 3_051L)

        assertEquals(listOf(1), restarted.snapshots.value.characters.map { it.slotId })
        assertEquals(1, restarted.snapshots.value.activeSlotId)
        assertEquals("기존 모험가", restarted.snapshots.value.state!!.hero.name)
    }

    @Test
    fun `three character slots are independent and a fourth creation is rejected`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats
        repository.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 81L, 3_100L)
        repository.createCharacter("둘째", HeroClass.ROGUE, stats, 82L, 3_200L)
        repository.createCharacter("셋째", HeroClass.MAGE, stats, 83L, 3_300L)

        val snapshot = repository.snapshots.value
        assertEquals(listOf(1, 2, 3), snapshot.characters.map { it.slotId })
        assertEquals(
            listOf("첫 번째", "둘째", "셋째"),
            snapshot.characters.map { it.state.hero.name },
        )
        assertEquals(3, snapshot.activeSlotId)
        assertEquals("셋째", snapshot.state!!.hero.name)
        assertTrue(
            runCatching {
                repository.createCharacter("넷째", HeroClass.CLERIC, stats, 84L, 3_400L)
            }.isFailure,
        )
        assertEquals(3, database.stateDao().loadAllCharacterSlots().size)
    }

    @Test
    fun `startup settles and persists every character before publishing the ready roster`() = runBlocking {
        val original = repository()
        val stats = engine.rollStats(77L).stats
        original.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 85L, 3_500L)
        original.createCharacter("둘째", HeroClass.ROGUE, stats, 86L, 3_600L)
        original.createCharacter("셋째", HeroClass.MAGE, stats, 87L, 3_700L)
        val beforeRestart = database.stateDao().loadAllCharacterSlots()
            .associate { entity ->
                entity.id to Json.decodeFromString<SimpleGameState>(entity.payload)
            }
        val startupNow = 50_000L

        val restarted = repository()
        restarted.initialize(now = startupNow)

        val readySnapshot = restarted.snapshots.value
        assertTrue(readySnapshot.ready)
        assertEquals(StartupPhase.READY, readySnapshot.startupPhase)
        assertEquals(listOf(1, 2, 3), readySnapshot.characters.map { it.slotId })
        readySnapshot.characters.forEach { character ->
            assertTrue(beforeRestart.getValue(character.slotId).lastSettledAt < startupNow)
            assertEquals(startupNow, character.state.lastSettledAt)

            val persistedEntity = database.stateDao().loadAllCharacterSlots()
                .single { it.id == character.slotId }
            val persistedState = Json.decodeFromString<SimpleGameState>(persistedEntity.payload)
            assertEquals(startupNow, persistedEntity.updatedAt)
            assertEquals(persistedState, character.state)
        }
    }

    @Test
    fun `selection switches the active character and catches that slot up`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats
        repository.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 91L, 4_000L)
        repository.createCharacter("둘째", HeroClass.RANGER, stats, 92L, 4_100L)

        val result = repository.selectCharacter(slotId = 1, now = 5_000L)

        assertTrue(result.isSuccess)
        assertEquals(1, repository.snapshots.value.activeSlotId)
        assertEquals("첫 번째", repository.snapshots.value.state!!.hero.name)
        assertEquals(5_000L, repository.snapshots.value.state!!.lastSettledAt)
        assertEquals(
            listOf("첫 번째", "둘째"),
            repository.snapshots.value.characters.map { it.state.hero.name },
        )
    }

    @Test
    fun `deleting one slot preserves others and the vacant slot is reused`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats
        repository.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 101L, 6_000L)
        repository.createCharacter("삭제될 영웅", HeroClass.ROGUE, stats, 102L, 6_100L)
        repository.createCharacter("셋째", HeroClass.MAGE, stats, 103L, 6_200L)
        repository.selectCharacter(slotId = 2, now = 6_300L)
        assertNotNull(backupStore[2])

        val result = repository.deleteCharacter(slotId = 2)

        assertTrue(result.isSuccess)
        assertEquals(listOf(1, 3), database.stateDao().loadAllCharacterSlots().map { it.id })
        assertNull(backupStore[2])
        assertTrue(repository.snapshots.value.ready)
        assertEquals(StartupPhase.READY, repository.snapshots.value.startupPhase)
        assertEquals(1, repository.snapshots.value.activeSlotId)
        assertEquals("첫 번째", repository.snapshots.value.state!!.hero.name)
        assertEquals(
            listOf("첫 번째", "셋째"),
            repository.snapshots.value.characters.map { it.state.hero.name },
        )

        repository.createCharacter("새 둘째", HeroClass.CLERIC, stats, 104L, 6_400L)
        assertEquals(
            mapOf(1 to "첫 번째", 2 to "새 둘째", 3 to "셋째"),
            repository.snapshots.value.characters.associate { it.slotId to it.state.hero.name },
        )
    }

    @Test
    fun `deleting the last character clears primary backup and restart stays empty`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats
        repository.createCharacter("삭제될 영웅", HeroClass.ROGUE, stats, 111L, 7_000L)
        repository.onAppForegrounded(now = 7_001L, elapsedRealtime = 0L)
        assertNotNull(database.stateDao().load())
        assertNotNull(backupStore[1])

        val result = repository.deleteCharacter(slotId = 1)

        assertTrue(result.isSuccess)
        assertNull(database.stateDao().load())
        assertNull(backupStore[1])
        assertNull(repository.snapshots.value.activeSlotId)
        assertNull(repository.snapshots.value.state)
        assertTrue(repository.snapshots.value.characters.isEmpty())

        val restarted = repository()
        restarted.initialize(now = 7_002L)
        assertTrue(restarted.snapshots.value.ready)
        assertNull(restarted.snapshots.value.state)
        assertTrue(restarted.snapshots.value.characters.isEmpty())
    }

    @Test
    fun `file backup store atomically round trips a save`() = runBlocking {
        val directory = temporaryFolder.newFolder("valid-backup")
        val store = FileSimpleStateBackupStore(directory)
        val entity = SimpleStateEntity(id = 2, payload = "{\"schemaVersion\":19}", updatedAt = 8_000L)

        store.save(entity)

        assertEquals(entity, store.load(2))
        assertNull(store.load(1))
        assertEquals(1, directory.listFiles()?.size)
    }

    @Test
    fun `file backup store clear removes the recovery copy`() = runBlocking {
        val directory = temporaryFolder.newFolder("cleared-backup")
        val store = FileSimpleStateBackupStore(directory)
        store.save(SimpleStateEntity(id = 3, payload = "{\"schemaVersion\":19}", updatedAt = 8_500L))
        assertNotNull(store.load(3))

        store.clear(3)

        assertNull(store.load(3))
        assertTrue(directory.listFiles()?.isEmpty() == true)
    }

    @Test
    fun `file backup store rejects a modified backup`() = runBlocking {
        val directory = temporaryFolder.newFolder("tampered-backup")
        val store = FileSimpleStateBackupStore(directory)
        store.save(SimpleStateEntity(payload = "{\"schemaVersion\":19}", updatedAt = 9_000L))
        val backupFile = requireNotNull(directory.listFiles()).single()
        backupFile.writeText(backupFile.readText().replace("schemaVersion", "damagedVersion"))

        assertTrue(runCatching { store.load(1) }.isFailure)
    }

    private fun repository() = SimpleGameRepository(
        database = database,
        engine = engine,
        backupStore = backupStore,
    )

    private class MemoryBackupStore : SimpleStateBackupStore {
        private val entities = mutableMapOf<Int, SimpleStateEntity>()

        operator fun get(slotId: Int): SimpleStateEntity? = entities[slotId]

        operator fun set(slotId: Int, entity: SimpleStateEntity) {
            entities[slotId] = entity.copy(id = slotId)
        }

        override suspend fun load(slotId: Int): SimpleStateEntity? = entities[slotId]?.copy()

        override suspend fun save(entity: SimpleStateEntity) {
            entities[entity.id] = entity.copy()
        }

        override suspend fun clear(slotId: Int) {
            entities.remove(slotId)
        }
    }
}
