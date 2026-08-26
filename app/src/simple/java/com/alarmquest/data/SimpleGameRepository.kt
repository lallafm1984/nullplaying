package com.alarmquest.data

import com.alarmquest.engine.SimpleGameEngine
import com.alarmquest.engine.LegacyAutoHuntSnapshot
import com.alarmquest.model.HeroClass
import com.alarmquest.model.HeroStats
import com.alarmquest.model.SimpleGameState
import com.alarmquest.model.StatRoll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

enum class StartupPhase {
    LOADING_RECORD,
    SETTLING_OFFLINE,
    SAVING_RESULT,
    READY,
    FAILED,
}

const val MAX_CHARACTER_SLOTS = 3
const val INITIAL_UNLOCKED_CHARACTER_SLOT_COUNT = 1
const val SECOND_CHARACTER_SLOT_UNLOCK_LEVEL = 20L
const val THIRD_CHARACTER_SLOT_UNLOCK_LEVEL = 50L

fun unlockedCharacterSlotCountForLevel(level: Long): Int = when {
    level >= THIRD_CHARACTER_SLOT_UNLOCK_LEVEL -> 3
    level >= SECOND_CHARACTER_SLOT_UNLOCK_LEVEL -> 2
    else -> INITIAL_UNLOCKED_CHARACTER_SLOT_COUNT
}

fun nextCharacterSlotUnlockLevel(unlockedSlotCount: Int): Long? = when (
    unlockedSlotCount.coerceIn(INITIAL_UNLOCKED_CHARACTER_SLOT_COUNT, MAX_CHARACTER_SLOTS)
) {
    1 -> SECOND_CHARACTER_SLOT_UNLOCK_LEVEL
    2 -> THIRD_CHARACTER_SLOT_UNLOCK_LEVEL
    else -> null
}

data class CharacterSlotSnapshot(
    val slotId: Int,
    val state: SimpleGameState,
)

data class GameSnapshot(
    val revision: Long,
    val state: SimpleGameState?,
    val characters: List<CharacterSlotSnapshot>,
    val activeSlotId: Int?,
    val unlockedCharacterSlotCount: Int,
    val ready: Boolean,
    val startupPhase: StartupPhase,
    val startupError: String? = null,
    val recoveredFromBackup: Boolean = false,
)

class SimpleGameRepository(
    private val database: SimpleDatabase,
    private val engine: SimpleGameEngine,
    private val backupStore: SimpleStateBackupStore,
    private val progressEventSink: GameProgressEventSink = NoOpGameProgressEventSink,
    private val accountProgressDao: SimpleAccountProgressDao = database.accountProgressDao(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    @Volatile
    private var appInForeground = false
    @Volatile
    private var rewardAdInFlight = false
    @Volatile
    private var foregroundElapsedRealtime = 0L
    private var lastOfflineAdventureUiEmissionAt = 0L
    private val characterStates = linkedMapOf<Int, SimpleGameState>()
    private val lastPersistedEntities = mutableMapOf<Int, SimpleStateEntity>()
    private var activeSlotId: Int? = null
    private var accountProgress = SimpleAccountProgressEntity()
    private var unlockedCharacterSlotCount = INITIAL_UNLOCKED_CHARACTER_SLOT_COUNT
    private val mutableSnapshots = MutableStateFlow(
        GameSnapshot(
            revision = 0L,
            state = null,
            characters = emptyList(),
            activeSlotId = null,
            unlockedCharacterSlotCount = INITIAL_UNLOCKED_CHARACTER_SLOT_COUNT,
            ready = false,
            startupPhase = StartupPhase.LOADING_RECORD,
        ),
    )
    val snapshots: StateFlow<GameSnapshot> = mutableSnapshots.asStateFlow()

    fun rollStats(seed: Long, heroClass: HeroClass = HeroClass.WARRIOR): StatRoll =
        engine.rollStats(seed, heroClass)

    fun initialStatsForClass(stats: HeroStats, heroClass: HeroClass): HeroStats =
        engine.initialStatsForClass(stats, heroClass)

    fun experienceRequired(level: Long): Long = engine.experienceRequired(level)

    fun displayCombatPower(state: SimpleGameState): Long =
        engine.displayCombatPower(state)

    fun monsterEnergyFraction(state: SimpleGameState): Float =
        engine.monsterEnergyFraction(state)

    fun offlineAdventureFraction(state: SimpleGameState): Float =
        engine.offlineAdventureFraction(state)

    fun isOfflineAdventureFull(state: SimpleGameState): Boolean =
        engine.isOfflineAdventureFull(state)

    fun isAppInForeground(): Boolean = appInForeground

    suspend fun initialize(now: Long) = mutex.withLock {
        try {
            characterStates.clear()
            lastPersistedEntities.clear()
            activeSlotId = null
            val loadedAccountProgress = loadAccountProgress()
            accountProgress = loadedAccountProgress.entity
            unlockedCharacterSlotCount = accountProgress.unlockedCharacterSlots
            emitStartup(StartupPhase.LOADING_RECORD)
            val primaryResult = try {
                Result.success(database.stateDao().loadAllCharacterSlots())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            emitStartup(StartupPhase.SETTLING_OFFLINE)
            val settledCharacters = if (primaryResult.isSuccess) {
                primaryResult.getOrThrow().map { primary ->
                    try {
                        val settled = decodeAndSettle(primary, now)
                        LoadedCharacter(
                            slotId = primary.id,
                            state = settled.state,
                            sourceEntity = primary,
                            recoveredFromBackup = false,
                            progressEvent = settled.progressEvent,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (primaryFailure: Exception) {
                        val backup = backupStore.load(primary.id) ?: throw primaryFailure
                        val settled = decodeAndSettle(backup, now)
                        LoadedCharacter(
                            slotId = primary.id,
                            state = settled.state,
                            sourceEntity = backup,
                            recoveredFromBackup = true,
                            progressEvent = settled.progressEvent,
                        )
                    }
                }
            } else {
                val backups = (1..MAX_CHARACTER_SLOTS).mapNotNull { slotId ->
                    backupStore.load(slotId)?.let { backup ->
                        val settled = decodeAndSettle(backup, now)
                        LoadedCharacter(
                            slotId = slotId,
                            state = settled.state,
                            sourceEntity = backup,
                            recoveredFromBackup = true,
                            progressEvent = settled.progressEvent,
                        )
                    }
                }
                if (backups.isEmpty()) throw requireNotNull(primaryResult.exceptionOrNull())
                backups
            }

            if (settledCharacters.isEmpty()) {
                updateActiveCharacterSlot(null)
                persistAccountProgress(accountProgress)
                emitReady(recoveredFromBackup = loadedAccountProgress.recoveredFromBackup)
                return@withLock
            }

            // Old installations exposed all three slots before unlock progress existed. Keep the
            // capacity already granted to an existing roster, then retain it even after deletes.
            recordUnlockedCharacterSlots(
                maxOf(
                    settledCharacters.size,
                    settledCharacters.maxOf { unlockedCharacterSlotCountForLevel(it.state.hero.level) },
                ),
            )

            emitStartup(StartupPhase.SAVING_RESULT)
            // Startup is an all-slots transaction from the UI's point of view: keep the
            // published roster empty until every owned character is settled and persisted.
            settledCharacters.forEach { loaded ->
                if (loaded.state.rankingCharacterId.isBlank()) {
                    loaded.state.rankingCharacterId = UUID.randomUUID().toString()
                }
                if (!loaded.recoveredFromBackup) {
                    lastPersistedEntities[loaded.slotId] = loaded.sourceEntity
                }
                persistSlot(
                    slotId = loaded.slotId,
                    state = loaded.state,
                    now = now,
                    rotateBackup = !loaded.recoveredFromBackup,
                    emitSnapshot = false,
                )
                loaded.progressEvent?.let(progressEventSink::onGameProgress)
            }
            val restoredActiveSlotId = accountProgress.activeCharacterSlotId
                ?.takeIf(characterStates::containsKey)
                ?: characterStates.keys.firstOrNull()
            updateActiveCharacterSlot(restoredActiveSlotId)
            // Mirror primary metadata into the independent file on every successful startup.
            // This also best-effort repairs Room after loading the fallback copy.
            persistAccountProgress(accountProgress)
            emitReady(
                recoveredFromBackup = loadedAccountProgress.recoveredFromBackup ||
                    settledCharacters.any { it.recoveredFromBackup },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A broken or temporarily unreadable save must never look like a new character.
            // Keep the database row intact and let the player retry the startup load.
            emit(
                state = null,
                ready = false,
                startupPhase = StartupPhase.FAILED,
                startupError = "저장된 모험 기록을 불러오지 못했습니다.",
            )
        }
    }

    suspend fun createCharacter(
        name: String,
        heroClass: HeroClass,
        stats: HeroStats,
        seed: Long,
        now: Long,
    ) = mutex.withLock {
        check(characterStates.size < unlockedCharacterSlotCount) {
            "No unlocked character slot is available"
        }
        compactExistingCharacterSlotsIfNeeded()
        val slotId = characterStates.size + 1
        val game = engine.newGame(name, heroClass, stats, seed, now)
        game.rankingCharacterId = UUID.randomUUID().toString()
        persistSlot(slotId, game, now, emitSnapshot = false)
        updateActiveCharacterSlot(slotId)
        emitReady()
    }

    suspend fun selectCharacter(slotId: Int, now: Long): Result<Unit> = mutex.withLock {
        try {
            val selected = characterStates[slotId]
                ?: return@withLock Result.failure(
                    IllegalArgumentException("Character slot $slotId does not exist"),
                )
            val checkpoint = selected.progressCheckpoint()
            engine.settleOfflineWithOfflineAdventure(selected, now)
            persistSlot(slotId, selected, now, emitSnapshot = false)
            publishProgressEvent(checkpoint, selected)
            updateActiveCharacterSlot(slotId)
            emitReady()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun deleteCharacter(slotId: Int): Result<Unit> = mutex.withLock {
        try {
            // Compacted slots must not inherit a deleted or differently numbered character's
            // recovery copy. The next save recreates a matching backup for each affected slot.
            (slotId..MAX_CHARACTER_SLOTS).forEach { affectedSlotId ->
                backupStore.clear(affectedSlotId)
            }
            database.stateDao().deleteAndCompactCharacterSlots(slotId)
            compactCharacterSlots(deletedSlotId = slotId)
            updateActiveCharacterSlot(activeSlotId)
            rewardAdInFlight = false
            emitReady()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private suspend fun compactExistingCharacterSlotsIfNeeded() {
        val currentSlotIds = characterStates.keys.sorted()
        val compactedSlotIds = (1..characterStates.size).toList()
        if (currentSlotIds == compactedSlotIds) return

        val firstGap = compactedSlotIds.first { it !in currentSlotIds }
        (firstGap..MAX_CHARACTER_SLOTS).forEach { affectedSlotId ->
            backupStore.clear(affectedSlotId)
        }
        database.stateDao().compactCharacterSlots()
        compactCharacterSlots()
    }

    private fun compactCharacterSlots(deletedSlotId: Int? = null) {
        val survivingSlotIds = characterStates.keys
            .filterNot { it == deletedSlotId }
            .sorted()
        val compactedSlotIds = survivingSlotIds.mapIndexed { index, oldSlotId ->
            oldSlotId to index + 1
        }.toMap()
        val previousActiveSlotId = activeSlotId
        val compactedCharacters = linkedMapOf<Int, SimpleGameState>()
        survivingSlotIds.forEach { oldSlotId ->
            compactedCharacters.getOrPut(compactedSlotIds.getValue(oldSlotId)) {
                characterStates.getValue(oldSlotId)
            }
        }
        val compactedPersistedEntities = mutableMapOf<Int, SimpleStateEntity>()
        survivingSlotIds.forEach { oldSlotId ->
            lastPersistedEntities[oldSlotId]?.let { entity ->
                val compactedSlotId = compactedSlotIds.getValue(oldSlotId)
                compactedPersistedEntities[compactedSlotId] = entity.copy(id = compactedSlotId)
            }
        }

        characterStates.clear()
        characterStates.putAll(compactedCharacters)
        lastPersistedEntities.clear()
        lastPersistedEntities.putAll(compactedPersistedEntities)
        activeSlotId = if (previousActiveSlotId == deletedSlotId) {
            characterStates.keys.firstOrNull()
        } else {
            previousActiveSlotId?.let(compactedSlotIds::get)
        }
    }

    suspend fun tick(now: Long, elapsedRealtime: Long) = mutex.withLock {
        if (!appInForeground) return@withLock
        val current = mutableSnapshots.value.state ?: return@withLock
        val beforeActionSequence = current.actionSequence
        val beforeMonsterId = current.monster.id
        val beforeAdventurePhase = current.adventurePhase
        val beforePhase = current.combatPhase
        val beforeOfflineAdventure = current.offlineAdventureMillis
        val progressCheckpoint = current.progressCheckpoint()
        advanceForegroundOfflineAdventure(current, elapsedRealtime)
        val elapsed = now - current.lastSettledAt
        val delta = if (elapsed >= OFFLINE_SETTLEMENT_THRESHOLD_MILLIS) {
            engine.settleOffline(current, now)
        } else {
            engine.settle(current, now)
        }
        if (
            current.actionSequence != beforeActionSequence ||
            current.monster.id != beforeMonsterId ||
            current.adventurePhase != beforeAdventurePhase ||
            current.combatPhase != beforePhase ||
            delta.defeatedMonsters > 0L
        ) {
            persist(current, now)
            publishProgressEvent(progressCheckpoint, current)
        } else if (
            current.offlineAdventureMillis != beforeOfflineAdventure &&
            elapsedRealtime - lastOfflineAdventureUiEmissionAt >= OFFLINE_ADVENTURE_UI_EMISSION_MILLIS
        ) {
            lastOfflineAdventureUiEmissionAt = elapsedRealtime
            emitRuntime(current)
        }
    }

    suspend fun onAppForegrounded(now: Long, elapsedRealtime: Long) = mutex.withLock {
        if (appInForeground) {
            foregroundElapsedRealtime = elapsedRealtime
            return@withLock
        }
        appInForeground = true
        foregroundElapsedRealtime = elapsedRealtime
        lastOfflineAdventureUiEmissionAt = elapsedRealtime
        if (characterStates.isEmpty()) return@withLock

        // Returning from Home can restore the remembered roster immediately. Publish a loading
        // state first, settle every owned character, then reveal one coherent roster snapshot.
        emitStartup(StartupPhase.SETTLING_OFFLINE)
        try {
            characterStates.entries.sortedBy { it.key }.forEach { (slotId, state) ->
                val checkpoint = state.progressCheckpoint()
                engine.settleOfflineWithOfflineAdventure(state, now)
                persistSlot(
                    slotId = slotId,
                    state = state,
                    now = now,
                    emitSnapshot = false,
                )
                publishProgressEvent(checkpoint, state)
            }
        } finally {
            // A storage or settlement failure must not leave the retained UI permanently stuck
            // on the resume-loading screen. The caller still receives the original exception.
            emitReady()
        }
    }

    suspend fun onAppBackgrounded(now: Long, elapsedRealtime: Long) = mutex.withLock {
        if (!appInForeground) return@withLock
        val current = mutableSnapshots.value.state
        if (current != null) {
            val checkpoint = current.progressCheckpoint()
            advanceForegroundOfflineAdventure(current, elapsedRealtime)
            val elapsed = now - current.lastSettledAt
            if (elapsed >= OFFLINE_SETTLEMENT_THRESHOLD_MILLIS) {
                engine.settleOffline(current, now)
            } else {
                engine.settle(current, now)
            }
            persist(current, now)
            publishProgressEvent(checkpoint, current)
        }
        appInForeground = false
        foregroundElapsedRealtime = elapsedRealtime
    }

    suspend fun runBackgroundSettlement(now: Long) {
        if (!snapshots.value.ready) {
            initialize(now)
            return
        }
        mutex.withLock {
            if (appInForeground || characterStates.isEmpty()) return@withLock
            characterStates.entries.sortedBy { it.key }.forEach { (slotId, state) ->
                val checkpoint = state.progressCheckpoint()
                engine.settleOfflineWithOfflineAdventure(state, now)
                persistSlot(
                    slotId = slotId,
                    state = state,
                    now = now,
                    emitSnapshot = false,
                )
                publishProgressEvent(checkpoint, state)
            }
            emitReady()
        }
    }

    fun setRewardAdInFlight(inFlight: Boolean, elapsedRealtime: Long) {
        rewardAdInFlight = inFlight
        foregroundElapsedRealtime = elapsedRealtime
    }

    suspend fun grantRewardedOfflineAdventure(now: Long, rewardRequestId: String): Boolean =
        mutex.withLock {
            val current = mutableSnapshots.value.state ?: return@withLock false
            val granted = engine.grantRewardedOfflineAdventure(current, rewardRequestId)
            if (granted) persist(current, now)
            granted
        }

    private fun advanceForegroundOfflineAdventure(
        state: SimpleGameState,
        elapsedRealtime: Long,
    ) {
        val elapsed = (elapsedRealtime - foregroundElapsedRealtime).coerceAtLeast(0L)
        foregroundElapsedRealtime = elapsedRealtime
        if (rewardAdInFlight) return
        engine.advanceOfflineAdventureForeground(state, elapsed)
    }

    private fun legacyAutoHuntSnapshot(payload: String): LegacyAutoHuntSnapshot? {
        val objectValue = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return null
        val schemaVersion = objectValue["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
        if (schemaVersion >= 9) return null
        return LegacyAutoHuntSnapshot(
            schemaVersion = schemaVersion,
            chargeMillis = objectValue["autoHuntChargeMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
            activeUntil = objectValue["autoHuntActiveUntil"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    private suspend fun decodeAndSettle(
        entity: SimpleStateEntity,
        now: Long,
    ): SettledState {
        val (loaded, legacy) = withContext(Dispatchers.Default) {
            val legacySnapshot = legacyAutoHuntSnapshot(entity.payload)
            json.decodeFromString<SimpleGameState>(entity.payload) to legacySnapshot
        }
        val checkpoint = loaded.progressCheckpoint()
        withContext(Dispatchers.Default) {
            engine.settleOfflineWithOfflineAdventure(loaded, now, legacy)
        }
        return SettledState(
            state = loaded,
            progressEvent = gameProgressEventBetween(checkpoint, loaded),
        )
    }

    private fun publishProgressEvent(
        checkpoint: GameProgressCheckpoint,
        state: SimpleGameState,
    ) {
        gameProgressEventBetween(checkpoint, state)?.let(progressEventSink::onGameProgress)
    }

    private suspend fun persist(
        state: SimpleGameState,
        now: Long,
        rotateBackup: Boolean = true,
        recoveredFromBackup: Boolean = false,
    ) {
        val slotId = requireNotNull(activeSlotId) { "No active character slot" }
        persistSlot(
            slotId = slotId,
            state = state,
            now = now,
            rotateBackup = rotateBackup,
            recoveredFromBackup = recoveredFromBackup,
        )
    }

    private suspend fun persistSlot(
        slotId: Int,
        state: SimpleGameState,
        now: Long,
        rotateBackup: Boolean = true,
        recoveredFromBackup: Boolean = false,
        emitSnapshot: Boolean = true,
    ) {
        require(slotId in 1..MAX_CHARACTER_SLOTS) { "Unsupported character slot $slotId" }
        recordUnlockedCharacterSlots(unlockedCharacterSlotCountForLevel(state.hero.level))
        val payload = withContext(Dispatchers.Default) {
            json.encodeToString(state)
        }
        val nextEntity = SimpleStateEntity(
            id = slotId,
            payload = payload,
            updatedAt = now,
        )
        if (rotateBackup) {
            lastPersistedEntities[slotId]?.let { previous -> backupStore.save(previous) }
        }
        database.stateDao().save(nextEntity)
        lastPersistedEntities[slotId] = nextEntity
        // The engine mutates its working state for fast catch-up. Emit a detached object so
        // Compose cannot skip a header or panel just because the old object reference survived.
        val detached = withContext(Dispatchers.Default) {
            json.decodeFromString<SimpleGameState>(payload)
        }
        characterStates[slotId] = detached
        if (emitSnapshot) {
            emitReady(recoveredFromBackup = recoveredFromBackup)
        }
    }

    private fun emitRuntime(state: SimpleGameState) {
        val slotId = activeSlotId ?: return
        characterStates[slotId] = state.copy()
        emitReady()
    }

    private fun emitStartup(phase: StartupPhase) {
        emit(
            state = null,
            ready = false,
            startupPhase = phase,
        )
    }

    private fun emitReady(recoveredFromBackup: Boolean = false) {
        emit(
            state = activeSlotId?.let(characterStates::get),
            ready = true,
            startupPhase = StartupPhase.READY,
            recoveredFromBackup = recoveredFromBackup,
        )
    }

    private fun emit(
        state: SimpleGameState?,
        ready: Boolean,
        startupPhase: StartupPhase,
        startupError: String? = null,
        recoveredFromBackup: Boolean = false,
    ) {
        val current = mutableSnapshots.value
        mutableSnapshots.value = GameSnapshot(
            revision = current.revision + 1L,
            state = state,
            characters = characterStates.entries
                .sortedBy { it.key }
                .map { (slotId, characterState) ->
                    CharacterSlotSnapshot(slotId = slotId, state = characterState)
                },
            activeSlotId = activeSlotId,
            unlockedCharacterSlotCount = unlockedCharacterSlotCount,
            ready = ready,
            startupPhase = startupPhase,
            startupError = startupError,
            recoveredFromBackup = recoveredFromBackup,
        )
    }

    private data class LoadedCharacter(
        val slotId: Int,
        val state: SimpleGameState,
        val sourceEntity: SimpleStateEntity,
        val recoveredFromBackup: Boolean,
        val progressEvent: GameProgressEvent?,
    )

    private data class SettledState(
        val state: SimpleGameState,
        val progressEvent: GameProgressEvent?,
    )

    private data class LoadedAccountProgress(
        val entity: SimpleAccountProgressEntity,
        val recoveredFromBackup: Boolean,
    )

    private suspend fun loadAccountProgress(): LoadedAccountProgress {
        val primaryResult = try {
            Result.success(accountProgressDao.load()?.let(::normalizeAccountProgress))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        val backupResult = try {
            Result.success(backupStore.loadAccountProgress()?.let(::normalizeAccountProgress))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        val primary = primaryResult.getOrNull()
        val backup = backupResult.getOrNull()
        val shouldUseBackup = backup != null &&
            (primary == null || backup.revision > primary.revision)

        return when {
            shouldUseBackup -> LoadedAccountProgress(
                entity = requireNotNull(backup),
                recoveredFromBackup = true,
            )
            primary != null -> LoadedAccountProgress(
                entity = primary,
                recoveredFromBackup = false,
            )
            primaryResult.isFailure -> throw requireNotNull(primaryResult.exceptionOrNull())
            backupResult.isFailure -> throw requireNotNull(backupResult.exceptionOrNull())
            else -> LoadedAccountProgress(
                entity = SimpleAccountProgressEntity(),
                recoveredFromBackup = false,
            )
        }
    }

    private fun normalizeAccountProgress(
        entity: SimpleAccountProgressEntity,
    ): SimpleAccountProgressEntity = entity.copy(
        id = 1,
        unlockedCharacterSlots = entity.unlockedCharacterSlots.coerceIn(
            INITIAL_UNLOCKED_CHARACTER_SLOT_COUNT,
            MAX_CHARACTER_SLOTS,
        ),
        activeCharacterSlotId = entity.activeCharacterSlotId
            ?.takeIf { it in 1..MAX_CHARACTER_SLOTS },
        revision = entity.revision.coerceAtLeast(0L),
    )

    private suspend fun persistAccountProgress(entity: SimpleAccountProgressEntity) {
        val normalized = normalizeAccountProgress(entity)
        // The independent file is required for this small piece of account state. Room remains
        // the primary fast path, but a transient DAO write failure must not discard a selection.
        backupStore.saveAccountProgress(normalized)
        try {
            accountProgressDao.save(normalized)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A later load compares revisions and repairs Room from this durable file copy.
        }
        accountProgress = normalized
        unlockedCharacterSlotCount = normalized.unlockedCharacterSlots
    }

    private suspend fun updateActiveCharacterSlot(slotId: Int?) {
        require(slotId == null || slotId in characterStates) {
            "Character slot $slotId does not exist"
        }
        if (accountProgress.activeCharacterSlotId != slotId) {
            persistAccountProgress(
                accountProgress.copy(
                    activeCharacterSlotId = slotId,
                    revision = nextAccountProgressRevision(),
                ),
            )
        }
        activeSlotId = slotId
    }

    private suspend fun recordUnlockedCharacterSlots(candidate: Int) {
        val updated = candidate.coerceIn(
            INITIAL_UNLOCKED_CHARACTER_SLOT_COUNT,
            MAX_CHARACTER_SLOTS,
        )
        if (updated <= unlockedCharacterSlotCount) return
        persistAccountProgress(
            accountProgress.copy(
                unlockedCharacterSlots = updated,
                revision = nextAccountProgressRevision(),
            ),
        )
    }

    private fun nextAccountProgressRevision(): Long =
        if (accountProgress.revision == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            accountProgress.revision + 1L
        }

    private companion object {
        const val OFFLINE_SETTLEMENT_THRESHOLD_MILLIS = 5_000L
        const val OFFLINE_ADVENTURE_UI_EMISSION_MILLIS = 250L
    }
}
