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

enum class StartupPhase {
    LOADING_RECORD,
    SETTLING_OFFLINE,
    SAVING_RESULT,
    READY,
    FAILED,
}

const val MAX_CHARACTER_SLOTS = 3

data class CharacterSlotSnapshot(
    val slotId: Int,
    val state: SimpleGameState,
)

data class GameSnapshot(
    val revision: Long,
    val state: SimpleGameState?,
    val characters: List<CharacterSlotSnapshot>,
    val activeSlotId: Int?,
    val ready: Boolean,
    val startupPhase: StartupPhase,
    val startupError: String? = null,
    val recoveredFromBackup: Boolean = false,
)

class SimpleGameRepository(
    private val database: SimpleDatabase,
    private val engine: SimpleGameEngine,
    private val backupStore: SimpleStateBackupStore,
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
    private val mutableSnapshots = MutableStateFlow(
        GameSnapshot(
            revision = 0L,
            state = null,
            characters = emptyList(),
            activeSlotId = null,
            ready = false,
            startupPhase = StartupPhase.LOADING_RECORD,
        ),
    )
    val snapshots: StateFlow<GameSnapshot> = mutableSnapshots.asStateFlow()

    fun rollStats(seed: Long): StatRoll = engine.rollStats(seed)

    fun experienceRequired(level: Long): Long = engine.experienceRequired(level)

    fun equipmentPrice(level: Long): Long = engine.equipmentPrice(level)

    fun displayCombatPower(state: SimpleGameState): Long =
        engine.displayCombatPower(state)

    fun combatDurationPercent(state: SimpleGameState): Int =
        engine.combatDurationPercent(state)

    fun monsterEnergyFraction(state: SimpleGameState): Float =
        engine.monsterEnergyFraction(state)

    fun offlineAdventureFraction(state: SimpleGameState): Float =
        engine.offlineAdventureFraction(state)

    fun isOfflineAdventureFull(state: SimpleGameState): Boolean =
        engine.isOfflineAdventureFull(state)

    suspend fun initialize(now: Long) = mutex.withLock {
        try {
            characterStates.clear()
            lastPersistedEntities.clear()
            activeSlotId = null
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
                        LoadedCharacter(
                            slotId = primary.id,
                            state = decodeAndSettle(primary, now),
                            sourceEntity = primary,
                            recoveredFromBackup = false,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (primaryFailure: Exception) {
                        val backup = backupStore.load(primary.id) ?: throw primaryFailure
                        LoadedCharacter(
                            slotId = primary.id,
                            state = decodeAndSettle(backup, now),
                            sourceEntity = backup,
                            recoveredFromBackup = true,
                        )
                    }
                }
            } else {
                val backups = (1..MAX_CHARACTER_SLOTS).mapNotNull { slotId ->
                    backupStore.load(slotId)?.let { backup ->
                        LoadedCharacter(
                            slotId = slotId,
                            state = decodeAndSettle(backup, now),
                            sourceEntity = backup,
                            recoveredFromBackup = true,
                        )
                    }
                }
                if (backups.isEmpty()) throw requireNotNull(primaryResult.exceptionOrNull())
                backups
            }

            if (settledCharacters.isEmpty()) {
                emitReady()
                return@withLock
            }

            emitStartup(StartupPhase.SAVING_RESULT)
            // Startup is an all-slots transaction from the UI's point of view: keep the
            // published roster empty until every owned character is settled and persisted.
            settledCharacters.forEach { loaded ->
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
            }
            activeSlotId = characterStates.keys.firstOrNull()
            emitReady(
                recoveredFromBackup = settledCharacters.any { it.recoveredFromBackup },
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
        check(characterStates.size < MAX_CHARACTER_SLOTS) {
            "All character slots are already in use"
        }
        val slotId = (1..MAX_CHARACTER_SLOTS).first { it !in characterStates }
        val game = engine.newGame(name, heroClass, stats, seed, now)
        persistSlot(slotId, game, now, emitSnapshot = false)
        activeSlotId = slotId
        emitReady()
    }

    suspend fun selectCharacter(slotId: Int, now: Long): Result<Unit> = mutex.withLock {
        try {
            val selected = characterStates[slotId]
                ?: return@withLock Result.failure(
                    IllegalArgumentException("Character slot $slotId does not exist"),
                )
            engine.settleOfflineWithOfflineAdventure(selected, now)
            persistSlot(slotId, selected, now, emitSnapshot = false)
            activeSlotId = slotId
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
            // Clear the recovery copy first. If the database delete then fails, the primary
            // record remains visible and retryable instead of a deleted hero being restored
            // from an older backup on the next launch.
            backupStore.clear(slotId)
            database.stateDao().delete(slotId)
            lastPersistedEntities.remove(slotId)
            characterStates.remove(slotId)
            if (activeSlotId == slotId) {
                activeSlotId = characterStates.keys.firstOrNull()
            }
            rewardAdInFlight = false
            emitReady()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
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
        } else if (
            current.offlineAdventureMillis != beforeOfflineAdventure &&
            elapsedRealtime - lastOfflineAdventureUiEmissionAt >= OFFLINE_ADVENTURE_UI_EMISSION_MILLIS
        ) {
            lastOfflineAdventureUiEmissionAt = elapsedRealtime
            emitRuntime(current)
        }
    }

    suspend fun onAppForegrounded(now: Long, elapsedRealtime: Long) = mutex.withLock {
        appInForeground = true
        foregroundElapsedRealtime = elapsedRealtime
        lastOfflineAdventureUiEmissionAt = elapsedRealtime
        val current = mutableSnapshots.value.state ?: return@withLock
        engine.settleOfflineWithOfflineAdventure(current, now)
        persist(current, now)
    }

    suspend fun onAppBackgrounded(now: Long, elapsedRealtime: Long) = mutex.withLock {
        if (!appInForeground) return@withLock
        val current = mutableSnapshots.value.state
        if (current != null) {
            advanceForegroundOfflineAdventure(current, elapsedRealtime)
            val elapsed = now - current.lastSettledAt
            if (elapsed >= OFFLINE_SETTLEMENT_THRESHOLD_MILLIS) {
                engine.settleOffline(current, now)
            } else {
                engine.settle(current, now)
            }
            persist(current, now)
        }
        appInForeground = false
        foregroundElapsedRealtime = elapsedRealtime
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
    ): SimpleGameState {
        val (loaded, legacy) = withContext(Dispatchers.Default) {
            val legacySnapshot = legacyAutoHuntSnapshot(entity.payload)
            json.decodeFromString<SimpleGameState>(entity.payload) to legacySnapshot
        }
        withContext(Dispatchers.Default) {
            engine.settleOfflineWithOfflineAdventure(loaded, now, legacy)
        }
        return loaded
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
    )

    private companion object {
        const val OFFLINE_SETTLEMENT_THRESHOLD_MILLIS = 5_000L
        const val OFFLINE_ADVENTURE_UI_EMISSION_MILLIS = 250L
    }
}
