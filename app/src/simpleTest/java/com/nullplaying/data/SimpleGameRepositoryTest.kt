package com.nullplaying.data

import androidx.room.Room
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.OfflineAdventureConfig
import com.nullplaying.model.HeroClass
import com.nullplaying.model.RecentAdventureEventType
import com.nullplaying.model.SimpleGameState
import kotlinx.coroutines.flow.first
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
@Config(sdk = [35], application = android.app.Application::class)
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
    fun `recent adventure events retain the newest three hundred per character`() = runBlocking {
        val dao = database.recentAdventureEventDao()
        dao.insertAll(
            (1L..305L).map { sequence ->
                RecentAdventureEventEntity(
                    characterSlotId = 1,
                    occurredAt = sequence,
                    eventType = RecentAdventureEventType.LEVEL_UP.name,
                    previousValue = sequence,
                    currentValue = sequence + 1L,
                )
            },
        )

        dao.trimToLimit(slotId = 1, limit = RECENT_ADVENTURE_EVENT_LIMIT)

        val retained = dao.loadRecent(slotId = 1, limit = 400)
        assertEquals(RECENT_ADVENTURE_EVENT_LIMIT, retained.size)
        assertEquals(305L, retained.first().occurredAt)
        assertEquals(6L, retained.last().occurredAt)
    }

    @Test
    fun `opening recent events records the newest event as seen`() = runBlocking {
        val repository = repository()
        repository.createCharacter(
            "기록 검사",
            HeroClass.WARRIOR,
            engine.rollStats(77L).stats,
            88L,
            1_000L,
        )
        val insertedId = database.recentAdventureEventDao().insertAll(
            listOf(
                RecentAdventureEventEntity(
                    characterSlotId = 1,
                    occurredAt = 1_001L,
                    eventType = RecentAdventureEventType.LEVEL_UP.name,
                    previousValue = 1L,
                    currentValue = 2L,
                ),
            ),
        ).single()

        assertEquals(insertedId, repository.observeRecentAdventureEvents(1).first().single().id)
        repository.markRecentAdventureEventsSeen(eventId = insertedId, now = 1_002L)

        assertEquals(insertedId, repository.snapshots.value.state!!.lastSeenRecentAdventureEventId)
        val saved = Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload)
        assertEquals(insertedId, saved.lastSeenRecentAdventureEventId)
    }

    @Test
    fun `foreground settlement persists each generated mastery event`() = runBlocking {
        val repository = repository()
        repository.createCharacter(
            "숙련 기록",
            HeroClass.WARRIOR,
            engine.rollStats(77L).stats,
            88L,
            0L,
        )
        repository.onAppForegrounded(now = 0L, elapsedRealtime = 0L)

        val opening = repository.snapshots.value.state!!
        repository.tick(now = opening.actionEndsAt, elapsedRealtime = opening.actionEndsAt)
        val combat = repository.snapshots.value.state!!
        combat.skills[0] = combat.skills.single().copy(usageCount = 99L)
        combat.consecutiveBasicAttacks = Int.MAX_VALUE

        repository.tick(now = combat.actionEndsAt, elapsedRealtime = combat.actionEndsAt)

        val event = repository.observeRecentAdventureEvents(slotId = 1).first().single().event
        assertEquals(RecentAdventureEventType.SKILL_MASTERY, event.type)
        assertEquals(1L, event.previousValue)
        assertEquals(2L, event.currentValue)
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
    fun `new accounts can create only one character until level twenty unlocks the second slot`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats
        repository.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 80L, 3_000L)

        assertTrue(
            runCatching {
                repository.createCharacter("잠긴 둘째", HeroClass.ROGUE, stats, 81L, 3_001L)
            }.isFailure,
        )

        promoteActiveHero(repository, level = 20L, now = 3_002L)
        assertEquals(2, repository.snapshots.value.unlockedCharacterSlotCount)
        repository.createCharacter("해금된 둘째", HeroClass.ROGUE, stats, 82L, 3_003L)
        assertEquals(listOf("첫 번째", "해금된 둘째"), repository.snapshots.value.characters.map { it.state.hero.name })
    }

    @Test
    fun `level fifty unlock remains after every character is deleted and app restarts`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats
        repository.createCharacter("개척자", HeroClass.WARRIOR, stats, 83L, 3_010L)
        promoteActiveHero(repository, level = 50L, now = 3_011L)
        assertEquals(3, repository.snapshots.value.unlockedCharacterSlotCount)

        repository.deleteCharacter(slotId = 1)
        assertTrue(repository.snapshots.value.characters.isEmpty())
        assertEquals(3, repository.snapshots.value.unlockedCharacterSlotCount)

        val restarted = repository()
        restarted.initialize(now = 3_012L)
        assertEquals(3, restarted.snapshots.value.unlockedCharacterSlotCount)
        restarted.createCharacter("새 출발", HeroClass.CLERIC, stats, 84L, 3_013L)
        restarted.createCharacter("두 번째 새 출발", HeroClass.MAGE, stats, 85L, 3_014L)
        restarted.createCharacter("세 번째 새 출발", HeroClass.RANGER, stats, 86L, 3_015L)
        assertEquals(3, restarted.snapshots.value.characters.size)
    }

    @Test
    fun `three character slots are independent and a fourth creation is rejected`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats
        repository.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 81L, 3_100L)
        promoteActiveHero(repository, level = 50L, now = 3_101L)
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
        val rankingCharacterIds = snapshot.characters.map { it.state.rankingCharacterId }
        assertTrue(rankingCharacterIds.none(String::isBlank))
        assertEquals(3, rankingCharacterIds.distinct().size)
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
        promoteActiveHero(original, level = 50L, now = 3_501L)
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
    fun `foreground return settles every character before publishing the roster`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(177L).stats
        repository.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 178L, 10_000L)
        promoteActiveHero(repository, level = 50L, now = 10_001L)
        repository.createCharacter("둘째", HeroClass.ROGUE, stats, 179L, 10_100L)
        repository.createCharacter("셋째", HeroClass.MAGE, stats, 180L, 10_200L)
        repository.onAppBackgrounded(now = 10_300L, elapsedRealtime = 10_300L)

        repository.onAppForegrounded(now = 11_000L, elapsedRealtime = 11_000L)

        val snapshot = repository.snapshots.value
        assertTrue(snapshot.ready)
        assertEquals(StartupPhase.READY, snapshot.startupPhase)
        assertEquals(listOf(11_000L, 11_000L, 11_000L), snapshot.characters.map { it.state.lastSettledAt })
        database.stateDao().loadAllCharacterSlots().forEach { entity ->
            val persisted = Json.decodeFromString<SimpleGameState>(entity.payload)
            assertEquals(11_000L, persisted.lastSettledAt)
            assertEquals(11_000L, entity.updatedAt)
        }
    }

    @Test
    fun `selection switches the active character and catches that slot up`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats
        repository.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 91L, 4_000L)
        promoteActiveHero(repository, level = 20L, now = 4_001L)
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
    fun `selected character remains active after repository recreation`() = runBlocking {
        val original = repository()
        val stats = engine.rollStats(77L).stats
        original.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 93L, 5_100L)
        promoteActiveHero(original, level = 50L, now = 5_101L)
        original.createCharacter("둘째", HeroClass.RANGER, stats, 94L, 5_200L)
        original.createCharacter("셋째", HeroClass.MAGE, stats, 95L, 5_300L)
        original.selectCharacter(slotId = 2, now = 5_400L)

        val recreated = repository()
        recreated.initialize(now = 5_500L)

        assertEquals(2, recreated.snapshots.value.activeSlotId)
        assertEquals("둘째", recreated.snapshots.value.state!!.hero.name)
    }

    @Test
    fun `account DAO load failure restores unlocks and selection from file backup`() = runBlocking {
        val original = repository()
        val stats = engine.rollStats(77L).stats
        original.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 96L, 5_600L)
        promoteActiveHero(original, level = 50L, now = 5_601L)
        original.createCharacter("둘째", HeroClass.RANGER, stats, 97L, 5_700L)
        original.createCharacter("셋째", HeroClass.MAGE, stats, 98L, 5_800L)
        original.selectCharacter(slotId = 2, now = 5_900L)
        assertNotNull(backupStore.accountProgress)

        val databaseAccountProgressDao = database.accountProgressDao()
        val loadFailingAccountProgressDao = object : SimpleAccountProgressDao {
            override suspend fun load(): SimpleAccountProgressEntity? {
                error("simulated account DAO load failure")
            }

            override suspend fun save(entity: SimpleAccountProgressEntity) {
                databaseAccountProgressDao.save(entity)
            }
        }
        val recreated = repository(accountProgressDao = loadFailingAccountProgressDao)

        recreated.initialize(now = 6_000L)

        val snapshot = recreated.snapshots.value
        assertTrue(snapshot.ready)
        assertTrue(snapshot.recoveredFromBackup)
        assertEquals(3, snapshot.unlockedCharacterSlotCount)
        assertEquals(2, snapshot.activeSlotId)
        assertEquals("둘째", snapshot.state!!.hero.name)
    }

    @Test
    fun `newer file metadata wins after account DAO save failure`() = runBlocking {
        val original = repository()
        val stats = engine.rollStats(77L).stats
        original.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 99L, 6_100L)
        promoteActiveHero(original, level = 50L, now = 6_101L)
        original.createCharacter("둘째", HeroClass.RANGER, stats, 100L, 6_200L)
        original.createCharacter("셋째", HeroClass.MAGE, stats, 101L, 6_300L)

        val databaseAccountProgressDao = database.accountProgressDao()
        val saveFailingAccountProgressDao = object : SimpleAccountProgressDao {
            override suspend fun load(): SimpleAccountProgressEntity? =
                databaseAccountProgressDao.load()

            override suspend fun save(entity: SimpleAccountProgressEntity) {
                error("simulated account DAO save failure")
            }
        }
        val degraded = repository(accountProgressDao = saveFailingAccountProgressDao)
        degraded.initialize(now = 6_400L)

        assertTrue(degraded.selectCharacter(slotId = 2, now = 6_500L).isSuccess)
        assertEquals(3, databaseAccountProgressDao.load()!!.activeCharacterSlotId)
        assertEquals(2, backupStore.accountProgress!!.activeCharacterSlotId)

        val recreated = repository()
        recreated.initialize(now = 6_600L)

        assertTrue(recreated.snapshots.value.ready)
        assertTrue(recreated.snapshots.value.recoveredFromBackup)
        assertEquals(2, recreated.snapshots.value.activeSlotId)
        assertEquals("둘째", recreated.snapshots.value.state!!.hero.name)
    }

    @Test
    fun `deleting a middle character compacts survivors and appends the next creation`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats
        repository.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 101L, 6_000L)
        promoteActiveHero(repository, level = 50L, now = 6_001L)
        repository.createCharacter("삭제될 영웅", HeroClass.ROGUE, stats, 102L, 6_100L)
        repository.createCharacter("셋째", HeroClass.MAGE, stats, 103L, 6_200L)
        repository.selectCharacter(slotId = 3, now = 6_300L)
        assertNotNull(backupStore[3])
        database.recentAdventureEventDao().insertAll(
            listOf(
                RecentAdventureEventEntity(
                    characterSlotId = 2,
                    occurredAt = 6_250L,
                    eventType = RecentAdventureEventType.TITLE_UNLOCKED.name,
                    subjectName = "삭제될 기록",
                ),
                RecentAdventureEventEntity(
                    characterSlotId = 3,
                    occurredAt = 6_300L,
                    eventType = RecentAdventureEventType.TITLE_UNLOCKED.name,
                    subjectName = "셋째의 기록",
                ),
            ),
        )

        val result = repository.deleteCharacter(slotId = 2)

        assertTrue(result.isSuccess)
        assertEquals(listOf(1, 2), database.stateDao().loadAllCharacterSlots().map { it.id })
        assertNull(backupStore[2])
        assertNull(backupStore[3])
        assertTrue(repository.snapshots.value.ready)
        assertEquals(StartupPhase.READY, repository.snapshots.value.startupPhase)
        assertEquals(2, repository.snapshots.value.activeSlotId)
        assertEquals("셋째", repository.snapshots.value.state!!.hero.name)
        assertEquals(
            listOf("셋째의 기록"),
            database.recentAdventureEventDao().loadRecent(2, 10).map { it.subjectName },
        )
        assertTrue(database.recentAdventureEventDao().loadRecent(3, 10).isEmpty())
        assertEquals(
            listOf("첫 번째", "셋째"),
            repository.snapshots.value.characters.map { it.state.hero.name },
        )

        repository.createCharacter("새 셋째", HeroClass.CLERIC, stats, 104L, 6_400L)
        assertEquals(
            listOf("첫 번째", "셋째", "새 셋째"),
            repository.snapshots.value.characters.map { it.state.hero.name },
        )

        val restarted = repository()
        restarted.initialize(now = 6_500L)
        assertEquals(
            listOf("첫 번째", "셋째", "새 셋째"),
            restarted.snapshots.value.characters.map { it.state.hero.name },
        )
        assertEquals(3, restarted.snapshots.value.activeSlotId)
        assertEquals("새 셋째", restarted.snapshots.value.state!!.hero.name)
    }

    @Test
    fun `creating after a legacy slot gap appends after the surviving characters`() = runBlocking {
        val original = repository()
        val stats = engine.rollStats(77L).stats
        original.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 105L, 6_600L)
        promoteActiveHero(original, level = 50L, now = 6_601L)
        original.createCharacter("삭제된 둘째", HeroClass.ROGUE, stats, 106L, 6_700L)
        original.createCharacter("기존 셋째", HeroClass.MAGE, stats, 107L, 6_800L)
        database.stateDao().delete(2)

        val migrated = repository()
        migrated.initialize(now = 6_900L)
        assertEquals(listOf(1, 3), migrated.snapshots.value.characters.map { it.slotId })

        migrated.createCharacter("새 모험가", HeroClass.CLERIC, stats, 108L, 7_000L)

        assertEquals(listOf(1, 2, 3), database.stateDao().loadAllCharacterSlots().map { it.id })
        assertEquals(
            listOf("첫 번째", "기존 셋째", "새 모험가"),
            migrated.snapshots.value.characters.map { it.state.hero.name },
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

    @Test
    fun `file backup store atomically round trips account progress`() = runBlocking {
        val directory = temporaryFolder.newFolder("account-progress-backup")
        val store = FileSimpleStateBackupStore(directory)
        val accountProgress = SimpleAccountProgressEntity(
            unlockedCharacterSlots = 3,
            activeCharacterSlotId = 2,
            revision = 7L,
        )

        store.saveAccountProgress(accountProgress)

        assertEquals(accountProgress, store.loadAccountProgress())
        assertEquals("simple_account_progress.json", directory.listFiles()?.single()?.name)
    }

    @Test
    fun `background settlement emits offline depletion after the state is persisted`() = runBlocking {
        val events = mutableListOf<GameProgressEvent>()
        val repository = repository(GameProgressEventSink(events::add))
        val stats = engine.rollStats(121L).stats
        repository.createCharacter("백그라운드 알림", HeroClass.RANGER, stats, 122L, 10_000L)
        repository.snapshots.value.state!!.offlineAdventureMillis = 1L

        repository.runBackgroundSettlement(now = 10_001L)

        assertEquals(0L, repository.snapshots.value.state!!.offlineAdventureMillis)
        assertEquals(1, events.size)
        assertTrue(events.single().offlineAdventureDepleted)
        val persisted = Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload)
        assertEquals(0L, persisted.offlineAdventureMillis)
    }

    @Test
    fun `remote config waits for foreground and charges elapsed time under old rules`() = runBlocking {
        val repository = repository()
        repository.createCharacter("원격 설정", HeroClass.WARRIOR, engine.rollStats(77L).stats, 88L, 0L)
        val config = OfflineAdventureConfig(1_440, 12)
        assertFalse(repository.updateOfflineAdventureConfig(config, 0L, 0L))
        repository.onAppForegrounded(now = 0L, elapsedRealtime = 0L)
        val state = repository.snapshots.value.state!!
        state.offlineAdventureMillis = 0L
        val previousConfig = engine.offlineAdventureConfig
        val expectedCharge = engine.offlineAdventureCapacityMillis(state) / previousConfig.chargeMinutes
        assertTrue(repository.updateOfflineAdventureConfig(config, 60_000L, 60_000L))
        assertEquals(expectedCharge, repository.snapshots.value.state!!.offlineAdventureMillis)
        val saved = Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload)
        assertEquals(expectedCharge, saved.offlineAdventureMillis)
        assertEquals(config, engine.offlineAdventureConfig)
        assertEquals(
            (expectedCharge.toDouble() / engine.offlineAdventureCapacityMillis(saved)).toFloat(),
            repository.offlineAdventureFraction(saved),
            0.000001f,
        )
    }

    private fun repository(
        progressEventSink: GameProgressEventSink = NoOpGameProgressEventSink,
        accountProgressDao: SimpleAccountProgressDao = database.accountProgressDao(),
    ) = SimpleGameRepository(
        database = database,
        engine = engine,
        backupStore = backupStore,
        progressEventSink = progressEventSink,
        accountProgressDao = accountProgressDao,
    )

    private suspend fun promoteActiveHero(
        repository: SimpleGameRepository,
        level: Long,
        now: Long,
    ) {
        repository.snapshots.value.state!!.hero.level = level
        repository.onAppForegrounded(now = now, elapsedRealtime = now)
    }

    private class MemoryBackupStore : SimpleStateBackupStore {
        private val entities = mutableMapOf<Int, SimpleStateEntity>()
        var accountProgress: SimpleAccountProgressEntity? = null
            private set

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

        override suspend fun loadAccountProgress(): SimpleAccountProgressEntity? =
            accountProgress?.copy()

        override suspend fun saveAccountProgress(entity: SimpleAccountProgressEntity) {
            accountProgress = entity.copy()
        }
    }
}
