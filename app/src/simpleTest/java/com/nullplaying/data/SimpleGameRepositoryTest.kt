package com.nullplaying.data

import androidx.room.Room
import com.nullplaying.BuildConfig
import com.nullplaying.engine.AdventureEventEngine
import com.nullplaying.engine.HeroPathCatalog
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.OfflineAdventureConfig
import com.nullplaying.model.HeroPathAllocationTarget
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathMutationStatus
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.CorrespondenceRecord
import com.nullplaying.model.CorrespondenceReplyIntent
import com.nullplaying.model.CorrespondenceStatus
import com.nullplaying.model.CorrespondenceTopic
import com.nullplaying.model.HeroClass
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.RecentAdventureEventType
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.TRUSTED_TIMELINE_LEGACY
import com.nullplaying.model.TRUSTED_TIMELINE_PROVISIONAL_NEW
import com.nullplaying.model.TRUSTED_TIMELINE_VERIFIED
import com.nullplaying.time.BootCountSource
import com.nullplaying.time.ElapsedRealtimeSource
import com.nullplaying.time.TrustedGameClock
import com.nullplaying.time.TrustedTimeAnchor
import com.nullplaying.time.TrustedTimeAnchorStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    fun `application owned earned reward targets its character and survives a retry`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(91L).stats
        repository.createCharacter("광고 보상", HeroClass.RANGER, stats, 92L, 1_000L)
        val characterId = repository.snapshots.value.state!!.rankingCharacterId
        repository.snapshots.value.state!!.offlineAdventureMillis = 0L

        assertEquals(
            RewardedOfflineGrantStatus.APPLIED,
            repository.grantRewardedOfflineAdventureForCharacter(
                characterId = characterId,
                now = 1_001L,
                rewardRequestId = "durable-request",
            ),
        )
        val persisted = Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload)
        assertEquals(engine.offlineAdventureCapacityMillis(persisted), persisted.offlineAdventureMillis)
        assertTrue("durable-request" in persisted.rewardedOfflineRequestIds)

        assertEquals(
            RewardedOfflineGrantStatus.ALREADY_APPLIED,
            repository.grantRewardedOfflineAdventureForCharacter(
                characterId = characterId,
                now = 1_002L,
                rewardRequestId = "durable-request",
            ),
        )
    }

    @Test
    fun `verified startup rebases a future legacy save without granting or removing progress`() =
        runBlocking {
            val future = engine.newGame(
                "미래 기록",
                HeroClass.WARRIOR,
                engine.rollStats(77L).stats,
                88L,
                500_000L,
            ).apply {
                trustedTimelineVersion = TRUSTED_TIMELINE_LEGACY
                actionStartedAt = 498_000L
                lastSettledAt = 500_000L
                actionEndsAt = 504_000L
                hero.experience = 123L
                hero.gold = 456L
                totalKills = 7L
                totalActs = 8L
                offlineAdventureMillis = 9_000L
            }
            val encodedFuture = Json { encodeDefaults = true }.encodeToString(future)
            assertEquals(9_000L, Json.decodeFromString<SimpleGameState>(encodedFuture).offlineAdventureMillis)
            database.stateDao().save(
                SimpleStateEntity(payload = encodedFuture, updatedAt = 500_000L),
            )
            database.recentAdventureEventDao().insertAll(
                listOf(
                    RecentAdventureEventEntity(
                        characterSlotId = 1,
                        occurredAt = 499_000L,
                        eventType = RecentAdventureEventType.LEVEL_UP.name,
                    ),
                ),
            )

            val repository = repository()
            repository.initialize(now = 100_000L, trustedTime = true)

            val migrated = repository.snapshots.value.state!!
            assertEquals(98_000L, migrated.actionStartedAt)
            assertEquals(104_000L, migrated.actionEndsAt)
            assertEquals(100_000L, migrated.lastSettledAt)
            assertEquals(TRUSTED_TIMELINE_VERIFIED, migrated.trustedTimelineVersion)
            assertEquals(123L, migrated.hero.experience)
            assertEquals(456L, migrated.hero.gold)
            assertEquals(7L, migrated.totalKills)
            assertEquals(8L, migrated.totalActs)
            assertEquals(9_000L, migrated.offlineAdventureMillis)
            assertEquals(
                100_000L,
                database.recentAdventureEventDao().loadRecent(slotId = 1, limit = 1).single().occurredAt,
            )
        }

    @Test
    fun `actual v17 payload without trusted timeline field upgrades normally`() = runBlocking {
        val legacy = engine.newGame(
            "v17 기록",
            HeroClass.WARRIOR,
            engine.rollStats(77L).stats,
            88L,
            10_000L,
        ).apply {
            offlineAdventureMillis = 5_000L
        }
        val v17Payload = Json { encodeDefaults = true }
            .encodeToString(legacy)
            .replace("\"trustedTimelineVersion\":0,", "")
        assertFalse(v17Payload.contains("trustedTimelineVersion"))
        database.stateDao().save(SimpleStateEntity(payload = v17Payload, updatedAt = 10_000L))

        val repository = repository()
        repository.initialize(now = 12_000L, trustedTime = true)

        val upgraded = requireNotNull(repository.snapshots.value.state)
        assertEquals(TRUSTED_TIMELINE_VERIFIED, upgraded.trustedTimelineVersion)
        assertEquals(12_000L, upgraded.lastSettledAt)
        assertEquals(3_000L, upgraded.offlineAdventureMillis)
    }

    @Test
    fun `first offline v18 launch never settles an existing save from provisional wall time`() =
        runBlocking {
            val legacy = engine.newGame(
                "오프라인 업그레이드",
                HeroClass.WARRIOR,
                engine.rollStats(77L).stats,
                88L,
                1_000L,
            ).apply {
                schemaVersion = 7
                hero.gold = 123L
                totalKills = 4L
                totalActs = 5L
            }
            database.stateDao().save(
                SimpleStateEntity(
                    payload = Json { encodeDefaults = true }.encodeToString(legacy),
                    updatedAt = 1_000L,
                ),
            )
            val clock = TrustedGameClock(
                store = MemoryTrustedTimeAnchorStore(),
                elapsedRealtimeSource = ElapsedRealtimeSource { 10L },
                bootCountSource = BootCountSource { 7L },
            )
            assertNotNull(clock.installProvisionalAnchor(5_000_000L))
            val repository = repository(gameClock = clock)

            repository.initialize(
                now = 5_000_000L,
                trustedTime = false,
                deferUnverifiedSettlement = true,
            )

            val deferred = requireNotNull(repository.snapshots.value.state)
            assertEquals(5_000_000L, deferred.lastSettledAt)
            assertEquals(123L, deferred.hero.gold)
            assertEquals(4L, deferred.totalKills)
            assertEquals(5L, deferred.totalActs)
            assertEquals(TRUSTED_TIMELINE_LEGACY, deferred.trustedTimelineVersion)
        }

    @Test
    fun `persisted timeline ceiling uses the newest character checkpoint`() = runBlocking {
        val json = Json { encodeDefaults = true }
        val newer = engine.newGame(
            "새 시각",
            HeroClass.WARRIOR,
            engine.rollStats(77L).stats,
            88L,
            20_000L,
        )
        val older = engine.newGame(
            "옛 시각",
            HeroClass.ROGUE,
            engine.rollStats(78L).stats,
            89L,
            10_000L,
        )
        database.stateDao().save(SimpleStateEntity(id = 1, payload = json.encodeToString(newer), updatedAt = 99_000L))
        database.stateDao().save(SimpleStateEntity(id = 2, payload = json.encodeToString(older), updatedAt = 98_000L))

        assertEquals(20_000L, repository().persistedTimelineCeilingOrNull())
    }

    @Test
    fun `verified startup gives a normal legacy save only its bank limited offline gap`() =
        runBlocking {
            val legacy = engine.newGame(
                "정상 기록",
                HeroClass.WARRIOR,
                engine.rollStats(77L).stats,
                88L,
                1_000L,
            ).apply {
                trustedTimelineVersion = TRUSTED_TIMELINE_LEGACY
                offlineAdventureMillis = 5_000L
            }
            val encodedLegacy = Json { encodeDefaults = true }.encodeToString(legacy)
            assertEquals(5_000L, Json.decodeFromString<SimpleGameState>(encodedLegacy).offlineAdventureMillis)
            database.stateDao().save(
                SimpleStateEntity(payload = encodedLegacy, updatedAt = 1_000L),
            )
            database.recentAdventureEventDao().insertAll(
                listOf(
                    RecentAdventureEventEntity(
                        characterSlotId = 1,
                        occurredAt = 99_000L,
                        eventType = RecentAdventureEventType.LEVEL_UP.name,
                    ),
                ),
            )

            val repository = repository()
            repository.initialize(now = 3_000L, trustedTime = true)

            val migrated = repository.snapshots.value.state!!
            assertEquals(3_000L, migrated.lastSettledAt)
            assertEquals(3_000L, migrated.offlineAdventureMillis)
            assertEquals(TRUSTED_TIMELINE_VERIFIED, migrated.trustedTimelineVersion)
            assertEquals(
                3_000L,
                database.recentAdventureEventDao().loadRecent(1, 1).single().occurredAt,
            )
        }

    @Test
    fun `provisional new character adopts server time without treating epoch gap as offline time`() =
        runBlocking {
            var elapsed = 10L
            val clockStore = MemoryTrustedTimeAnchorStore()
            val clock = TrustedGameClock(
                store = clockStore,
                elapsedRealtimeSource = ElapsedRealtimeSource { elapsed },
                bootCountSource = BootCountSource { 7L },
            )
            assertNotNull(clock.installProvisionalAnchor(1_000L))
            val repository = repository()
            repository.initialize(now = 1_000L, trustedTime = false)
            repository.createCharacter(
                "임시 시각",
                HeroClass.WARRIOR,
                engine.rollStats(77L).stats,
                88L,
                1_000L,
            )
            val before = repository.snapshots.value.state!!.copy(
                hero = repository.snapshots.value.state!!.hero.copy(),
            )
            assertEquals(TRUSTED_TIMELINE_PROVISIONAL_NEW, before.trustedTimelineVersion)

            elapsed = 20L
            assertTrue(repository.adoptVerifiedServerTime(clock, serverEpochMillis = 5_000_000L))

            val adopted = repository.snapshots.value.state!!
            assertEquals(5_000_000L, adopted.lastSettledAt)
            assertEquals(TRUSTED_TIMELINE_VERIFIED, adopted.trustedTimelineVersion)
            assertEquals(before.hero.experience, adopted.hero.experience)
            assertEquals(before.hero.gold, adopted.hero.gold)
            assertEquals(before.totalKills, adopted.totalKills)
            assertEquals(before.totalActs, adopted.totalActs)
            assertEquals(before.offlineAdventureMillis, adopted.offlineAdventureMillis)
        }

    @Test
    fun `queued mutation ignores a stale provisional sample after server correction`() =
        runBlocking {
            var elapsed = 10L
            val clock = TrustedGameClock(
                store = MemoryTrustedTimeAnchorStore(),
                elapsedRealtimeSource = ElapsedRealtimeSource { elapsed },
                bootCountSource = BootCountSource { 7L },
            )
            assertNotNull(clock.installProvisionalAnchor(5_000_000L))
            val repository = repository(gameClock = clock)
            repository.initialize(now = 5_000_000L, trustedTime = false)
            repository.createCharacter(
                "경쟁 조건 검사",
                HeroClass.WARRIOR,
                engine.rollStats(77L).stats,
                88L,
                5_000_000L,
            )
            repository.onAppForegrounded(now = 5_000_000L, elapsedRealtime = 10L)

            val staleCallerSample = 5_000_000L
            elapsed = 20L
            assertTrue(repository.adoptVerifiedServerTime(clock, serverEpochMillis = 100_000L))
            val corrected = repository.snapshots.value.state!!
            val killsBeforeQueuedTick = corrected.totalKills
            val actsBeforeQueuedTick = corrected.totalActs

            // This simulates a UI tick that sampled the old provisional epoch before waiting for
            // the repository mutex. Production must re-read the bound clock inside that mutex.
            repository.tick(now = staleCallerSample, elapsedRealtime = 20L)

            val afterQueuedTick = repository.snapshots.value.state!!
            assertEquals(100_000L, afterQueuedTick.lastSettledAt)
            assertEquals(killsBeforeQueuedTick, afterQueuedTick.totalKills)
            assertEquals(actsBeforeQueuedTick, afterQueuedTick.totalActs)
        }

    @Test
    fun `foreground wall clock jump advances adventure and trait active time by monotonic elapsed only`() =
        runBlocking {
            engine = SimpleGameEngine(
                enableAdventureEvents = true,
                enableAdventureRelationships = true,
                enableAdventureTraits = true,
            )
            var monotonicNow = 10L
            val clock = TrustedGameClock(
                store = MemoryTrustedTimeAnchorStore(),
                elapsedRealtimeSource = ElapsedRealtimeSource { monotonicNow },
                bootCountSource = BootCountSource { 7L },
            )
            assertNotNull(clock.installVerifiedServerObservation(100_000L))
            val repository = repository(gameClock = clock)
            repository.initialize(now = 100_000L, trustedTime = true)
            repository.createCharacter(
                "벽시계 조작 검사",
                HeroClass.WARRIOR,
                engine.rollStats(77L).stats,
                88L,
                100_000L,
            )
            repository.onAppForegrounded(now = 100_000L, elapsedRealtime = monotonicNow)
            val initial = requireNotNull(repository.snapshots.value.state)
            initial.adventureJourney.initialized = true
            initial.adventureJourney.nextEventAt = 110_000L
            initial.adventureTraits.owned = listOf(
                com.nullplaying.model.AdventureOwnedTrait(
                    traitId = "G01",
                    acquiredAt = 99_000L,
                    acquisitionSequence = 1L,
                ),
            )
            initial.adventureTraits.stableStartedAtByTrait = mapOf("G01" to 99_000L)

            monotonicNow += 1_000L
            repository.tick(
                now = 100_000L + 8L * 60L * 60L * 1_000L,
                elapsedRealtime = monotonicNow,
            )

            val advanced = requireNotNull(repository.snapshots.value.state)
            assertEquals(101_000L, advanced.lastSettledAt)
            assertEquals(110_000L, advanced.adventureJourney.nextEventAt)
            assertEquals(
                2_000L,
                advanced.lastSettledAt -
                    advanced.adventureTraits.stableStartedAtByTrait.getValue("G01"),
            )
            assertEquals(99_000L, advanced.adventureTraits.owned.single().acquiredAt)
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
    fun `adventure qa trigger build gate requires the isolated offline package`() {
        assertTrue(adventureQaTriggerBuildAllowed(true, true, false, "com.nullplaying.adventurepreview"))
        assertFalse(adventureQaTriggerBuildAllowed(false, true, false, "com.nullplaying.adventurepreview"))
        assertFalse(adventureQaTriggerBuildAllowed(true, false, false, "com.nullplaying.adventurepreview"))
        assertFalse(adventureQaTriggerBuildAllowed(true, true, true, "com.nullplaying.adventurepreview"))
        assertFalse(adventureQaTriggerBuildAllowed(true, true, false, "com.nullplaying"))
        assertFalse(adventureQaTriggerBuildAllowed(true, true, false, "com.nullplaying.battleqa"))
    }

    @Test
    fun `offline qa queued event survives repository persistence and restart`() = runBlocking {
        assumeTrue(
            adventureQaTriggerBuildAllowed(
                BuildConfig.DEBUG,
                BuildConfig.ADVENTURE_PREVIEW_ENABLED,
                BuildConfig.REMOTE_SERVICES_ENABLED,
                BuildConfig.APPLICATION_ID,
            ),
        )
        engine = SimpleGameEngine(enableAdventureEvents = true)
        val repository = repository()
        repository.createCharacter(
            "사건 순회",
            HeroClass.WARRIOR,
            engine.rollStats(77L).stats,
            88L,
            1_000L,
        )
        val firstBoundary = repository.snapshots.value.state!!.actionEndsAt

        assertTrue(repository.queueAdventureEventForQa("bridge", now = firstBoundary))
        assertFalse(repository.queueAdventureEventForQa("rescue", now = firstBoundary))
        assertEquals(AdventurePhase.COMBAT, repository.snapshots.value.state!!.adventurePhase)
        assertEquals("bridge", repository.snapshots.value.state!!.adventureJourney.qaQueuedEventId)
        assertEquals("bridge", repository.snapshots.value.state!!.adventureJourney.qaSequenceCursorEventId)
        assertEquals(
            "bridge",
            Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload)
                .adventureJourney.qaQueuedEventId,
        )

        val restarted = repository()
        restarted.initialize(now = firstBoundary)

        assertEquals("bridge", restarted.snapshots.value.state!!.adventureJourney.qaQueuedEventId)
        assertEquals("bridge", restarted.snapshots.value.state!!.adventureJourney.qaSequenceCursorEventId)
        assertEquals(0L, restarted.snapshots.value.state!!.adventureJourney.completedEvents)
        assertEquals(AdventureEventEngine.all.first().id, "bridge")
    }

    @Test
    fun `retired letters remain unchanged across settlement restart and character slots`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(77L).stats
        repository.createCharacter("기존 영웅", HeroClass.WARRIOR, stats, 88L, 1_000L)
        promoteActiveHero(repository, level = 20L, now = 1_001L)
        val first = repository.snapshots.value.state!!
        first.correspondence.baselineEstablished = true
        CorrespondenceStatus.entries.forEach { status ->
            first.correspondence.records += CorrespondenceRecord(
                id = "archived-${status.name}",
                sourceEventKey = "archived-${status.name}",
                occurredAt = 1_001L,
                topic = CorrespondenceTopic.SKILL_LEARNED,
                status = status,
                replyIntent = CorrespondenceReplyIntent.EXPERIMENT,
                decisionDueActionSequence = 1L,
                decisionRulesVersion = 1,
                decisionSeed = 123L,
            )
        }
        first.correspondence.disposition.curiosity.score = 7
        first.actionSequence = 500L
        val archived = Json.encodeToString(first.correspondence)
        database.stateDao().save(SimpleStateEntity(payload = Json.encodeToString(first), updatedAt = 1_001L))
        val reopened = repository()
        reopened.initialize(now = 1_002L)
        reopened.onAppForegrounded(now = 1_003L, elapsedRealtime = 1_003L)
        val persisted = Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload)
        assertEquals(archived, Json.encodeToString(persisted.correspondence))

        reopened.createCharacter("새 영웅", HeroClass.RANGER, stats, 89L, 1_004L)
        val second = reopened.snapshots.value.state!!
        assertTrue(second.correspondence.records.isEmpty())
        assertFalse(second.correspondence.baselineEstablished)
        val restarted = repository()
        restarted.initialize(now = 1_005L)
        restarted.selectCharacter(slotId = 1, now = 1_006L)
        val restored = restarted.snapshots.value.state!!
        assertEquals("기존 영웅", restored.hero.name)
        assertEquals(20L, restored.hero.level)
        assertEquals(archived, Json.encodeToString(restored.correspondence))
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
        opening.correspondence.baselineEstablished = true
        repository.tick(now = opening.actionEndsAt, elapsedRealtime = opening.actionEndsAt)
        val combat = repository.snapshots.value.state!!
        combat.skills[0] = combat.skills.single().copy(usageCount = 99L)
        combat.consecutiveBasicAttacks = Int.MAX_VALUE

        repository.tick(now = combat.actionEndsAt, elapsedRealtime = combat.actionEndsAt)

        val event = repository.observeRecentAdventureEvents(slotId = 1).first().single().event
        assertEquals(RecentAdventureEventType.SKILL_MASTERY, event.type)
        assertEquals(1L, event.previousValue)
        assertEquals(2L, event.currentValue)
        assertTrue(repository.snapshots.value.state!!.correspondence.records.isEmpty())
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

        assertFalse(repository.initialize(now = 3_001L))

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
    fun `failed multi-slot foreground catch-up never enables unlimited ticking`() = runBlocking {
        val repository = repository()
        val stats = engine.rollStats(177L).stats
        repository.createCharacter("첫 번째", HeroClass.WARRIOR, stats, 178L, 10_000L)
        repository.snapshots.value.state!!.hero.level = 50L
        repository.onAppForegrounded(now = 10_001L, elapsedRealtime = 10_001L)
        repository.onAppBackgrounded(now = 10_002L, elapsedRealtime = 10_002L)
        repository.createCharacter("둘째", HeroClass.ROGUE, stats, 179L, 10_100L)
        repository.createCharacter("셋째", HeroClass.MAGE, stats, 180L, 10_200L)
        val activeBefore = requireNotNull(repository.snapshots.value.state).lastSettledAt
        backupStore.failSaveForSlotId = 2

        assertTrue(
            runCatching {
                repository.onAppForegrounded(now = 1_000_000L, elapsedRealtime = 20_000L)
            }.isFailure,
        )
        assertFalse(repository.isAppInForeground())

        repository.tick(now = 2_000_000L, elapsedRealtime = 20_001L)
        assertEquals(activeBefore, repository.snapshots.value.state!!.lastSettledAt)
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
    fun `hero path allocation rejects a stale revision without memory or database mutation`() = runBlocking {
        val repository = repository()
        repository.createCharacter("스타일 검사", HeroClass.WARRIOR, engine.rollStats(201L).stats, 202L, 1_000L)
        promoteActiveHero(repository, level = 100L, now = 2_000L)
        val beforeState = repository.snapshots.value.state!!.heroPath
        val beforeEntity = database.stateDao().load()!!
        val branch = HeroPathCatalog.branchesFor(beforeState.heroClass).first().branch
        val target = HeroPathAllocationTarget(
            expectedRevision = beforeState.revision - 1L,
            nodeRanks = branchRanks(branch, points = 1),
        )

        val status = repository.applyHeroPathAllocation(target, now = 2_001L)

        assertEquals(HeroPathMutationStatus.STALE_REVISION, status)
        assertEquals(beforeState, repository.snapshots.value.state!!.heroPath)
        assertEquals(beforeEntity, database.stateDao().load())
    }

    @Test
    fun `hero path A-B replacement is one atomic persisted allocation`() = runBlocking {
        val repository = repository()
        repository.createCharacter("선택 교체", HeroClass.WARRIOR, engine.rollStats(211L).stats, 212L, 3_000L)
        promoteActiveHero(repository, level = 100L, now = 3_100L)
        val initial = repository.snapshots.value.state!!.heroPath
        val branch = HeroPathCatalog.branchesFor(initial.heroClass).first().branch
        val choiceA = HeroPathCatalog.nodesFor(branch).first { it.slot == HeroPathNodeSlot.CHOICE_A }.nodeId
        val choiceB = HeroPathCatalog.nodesFor(branch).first { it.slot == HeroPathNodeSlot.CHOICE_B }.nodeId

        assertEquals(
            HeroPathMutationStatus.APPLIED,
            repository.applyHeroPathAllocation(
                HeroPathAllocationTarget(initial.revision, branchRanks(branch, 5, choiceB = false)),
                now = 3_101L,
            ),
        )
        val withA = repository.snapshots.value.state!!.heroPath
        assertTrue(withA.traits.any { it.traitId == choiceA })
        assertFalse(withA.traits.any { it.traitId == choiceB })

        assertEquals(
            HeroPathMutationStatus.APPLIED,
            repository.applyHeroPathAllocation(
                HeroPathAllocationTarget(withA.revision, branchRanks(branch, 5, choiceB = true)),
                now = 3_102L,
            ),
        )
        val withB = repository.snapshots.value.state!!.heroPath
        assertEquals(withA.revision + 1L, withB.revision)
        assertFalse(withB.traits.any { it.traitId == choiceA })
        assertTrue(withB.traits.any { it.traitId == choiceB })
        assertEquals(withB, Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload).heroPath)
    }

    @Test
    fun `hero path core replacement removes the old core in the same persist`() = runBlocking {
        val repository = repository()
        repository.createCharacter("핵심 교체", HeroClass.WARRIOR, engine.rollStats(221L).stats, 222L, 4_000L)
        promoteActiveHero(repository, level = 100L, now = 4_100L)
        val initial = repository.snapshots.value.state!!.heroPath
        val branches = HeroPathCatalog.branchesFor(initial.heroClass).map { it.branch }
        val firstRanks = branchRanks(branches[0], 10)
        val firstCore = coreId(firstRanks)

        assertEquals(
            HeroPathMutationStatus.APPLIED,
            repository.applyHeroPathAllocation(
                HeroPathAllocationTarget(initial.revision, firstRanks, activeCoreNodeId = firstCore),
                now = 4_101L,
            ),
        )
        val first = repository.snapshots.value.state!!.heroPath
        val secondRanks = branchRanks(branches[1], 10)
        val secondCore = coreId(secondRanks)
        assertEquals(
            HeroPathMutationStatus.APPLIED,
            repository.applyHeroPathAllocation(
                HeroPathAllocationTarget(first.revision, secondRanks, activeCoreNodeId = secondCore),
                now = 4_102L,
            ),
        )

        val replaced = repository.snapshots.value.state!!.heroPath
        assertEquals(secondCore, replaced.activeCoreTraitId)
        assertFalse(replaced.traits.any { it.traitId == firstCore })
        assertEquals(10L, replaced.spentPoints)
        assertEquals(replaced, Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload).heroPath)
    }

    @Test
    fun `invalid hero path target rolls back memory database and revision`() = runBlocking {
        val repository = repository()
        repository.createCharacter("롤백 검사", HeroClass.ROGUE, engine.rollStats(231L).stats, 232L, 5_000L)
        promoteActiveHero(repository, level = 100L, now = 5_100L)
        val initial = repository.snapshots.value.state!!.heroPath
        val branch = HeroPathCatalog.branchesFor(initial.heroClass).first().branch
        val valid = branchRanks(branch, 5)
        assertEquals(
            HeroPathMutationStatus.APPLIED,
            repository.applyHeroPathAllocation(HeroPathAllocationTarget(initial.revision, valid), now = 5_101L),
        )
        val beforeState = repository.snapshots.value.state!!.heroPath
        val beforeEntity = database.stateDao().load()!!
        val choiceB = HeroPathCatalog.nodesFor(branch).first { it.slot == HeroPathNodeSlot.CHOICE_B }
        val bothChoices = valid + (choiceB.nodeId to 1)

        val status = repository.applyHeroPathAllocation(
            HeroPathAllocationTarget(beforeState.revision, bothChoices),
            now = 5_102L,
        )

        assertEquals(HeroPathMutationStatus.CHOICE_CONFLICT, status)
        assertEquals(beforeState, repository.snapshots.value.state!!.heroPath)
        assertEquals(beforeEntity, database.stateDao().load())
    }

    @Test
    fun `hero path allocation survives repository restart exactly`() = runBlocking {
        val repository = repository()
        repository.createCharacter("재시작 검사", HeroClass.MAGE, engine.rollStats(241L).stats, 242L, 6_000L)
        promoteActiveHero(repository, level = 100L, now = 6_100L)
        val initial = repository.snapshots.value.state!!.heroPath
        val branch = HeroPathCatalog.branchesFor(initial.heroClass).first().branch
        val ranks = branchRanks(branch, 10)
        assertEquals(
            HeroPathMutationStatus.APPLIED,
            repository.applyHeroPathAllocation(
                HeroPathAllocationTarget(initial.revision, ranks, activeCoreNodeId = coreId(ranks)),
                now = 6_101L,
            ),
        )
        val committed = repository.snapshots.value.state!!.heroPath

        val restarted = repository()
        restarted.initialize(now = 6_102L)

        assertEquals(committed, restarted.snapshots.value.state!!.heroPath)
        assertEquals(committed, Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload).heroPath)
    }

    @Test
    fun `allocation and reset racing on one revision yield one commit and one stale rejection`() = runBlocking {
        val repository = repository()
        repository.createCharacter("경쟁 검사", HeroClass.PALADIN, engine.rollStats(251L).stats, 252L, 7_000L)
        promoteActiveHero(repository, level = 100L, now = 7_100L)
        val initial = repository.snapshots.value.state!!.heroPath
        val branch = HeroPathCatalog.branchesFor(initial.heroClass).first().branch
        val choiceA = branchRanks(branch, 5, choiceB = false)
        assertEquals(
            HeroPathMutationStatus.APPLIED,
            repository.applyHeroPathAllocation(HeroPathAllocationTarget(initial.revision, choiceA), now = 7_101L),
        )
        val revision = repository.snapshots.value.state!!.heroPath.revision
        val choiceB = branchRanks(branch, 5, choiceB = true)

        val allocation = async {
            repository.applyHeroPathAllocation(HeroPathAllocationTarget(revision, choiceB), now = 7_102L)
        }
        val reset = async { repository.resetHeroPath(expectedRevision = revision, now = 7_103L) }
        val outcomes = listOf(allocation.await(), reset.await())

        assertEquals(1, outcomes.count { it == HeroPathMutationStatus.APPLIED })
        assertEquals(1, outcomes.count { it == HeroPathMutationStatus.STALE_REVISION })
        val finalState = repository.snapshots.value.state!!.heroPath
        assertEquals(revision + 1L, finalState.revision)
        assertEquals(finalState, Json.decodeFromString<SimpleGameState>(database.stateDao().load()!!.payload).heroPath)
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

    private fun branchRanks(
        branch: HeroPathBranch,
        points: Int,
        choiceB: Boolean = false,
    ): Map<String, Int> {
        require(points in 0..10)
        val nodes = HeroPathCatalog.nodesFor(branch).associateBy { it.slot }
        val pointOrder = listOf(
            HeroPathNodeSlot.FOUNDATION_A,
            HeroPathNodeSlot.FOUNDATION_B,
            HeroPathNodeSlot.FOUNDATION_A,
            if (choiceB) HeroPathNodeSlot.CHOICE_B else HeroPathNodeSlot.CHOICE_A,
            HeroPathNodeSlot.FOUNDATION_B,
            HeroPathNodeSlot.SPECIAL_A,
            HeroPathNodeSlot.SPECIAL_B,
            HeroPathNodeSlot.ADVANCED_TACTIC,
            HeroPathNodeSlot.ADVANCED_TACTIC,
            HeroPathNodeSlot.CORE,
        )
        return pointOrder.take(points)
            .map { nodes.getValue(it).nodeId }
            .groupingBy { it }
            .eachCount()
    }

    private fun coreId(ranks: Map<String, Int>): String = ranks.keys.single {
        HeroPathCatalog.byNodeId.getValue(it).slot == HeroPathNodeSlot.CORE
    }

    private fun repository(
        progressEventSink: GameProgressEventSink = NoOpGameProgressEventSink,
        accountProgressDao: SimpleAccountProgressDao = database.accountProgressDao(),
        gameClock: TrustedGameClock? = null,
    ) = SimpleGameRepository(
        database = database,
        engine = engine,
        backupStore = backupStore,
        progressEventSink = progressEventSink,
        accountProgressDao = accountProgressDao,
        gameClock = gameClock,
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
        var failSaveForSlotId: Int? = null
        var accountProgress: SimpleAccountProgressEntity? = null
            private set

        operator fun get(slotId: Int): SimpleStateEntity? = entities[slotId]

        operator fun set(slotId: Int, entity: SimpleStateEntity) {
            entities[slotId] = entity.copy(id = slotId)
        }

        override suspend fun load(slotId: Int): SimpleStateEntity? = entities[slotId]?.copy()

        override suspend fun save(entity: SimpleStateEntity) {
            if (entity.id == failSaveForSlotId) error("simulated character backup failure")
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

    private class MemoryTrustedTimeAnchorStore : TrustedTimeAnchorStore {
        private var anchor: TrustedTimeAnchor? = null

        override fun read(): TrustedTimeAnchor? = anchor

        override fun write(anchor: TrustedTimeAnchor): Boolean {
            this.anchor = anchor
            return true
        }
    }
}
