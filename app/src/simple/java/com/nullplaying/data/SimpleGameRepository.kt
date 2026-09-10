package com.nullplaying.data

import androidx.room.withTransaction
import com.nullplaying.BuildConfig
import com.nullplaying.engine.HeroPathEngine
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.OfflineAdventureConfig
import com.nullplaying.engine.LegacyAutoHuntSnapshot
import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEncounterRoster
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.AdventureRelationshipRun
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.HeroPathMutationStatus
import com.nullplaying.model.HeroPathAllocationTarget
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.StatRoll
import com.nullplaying.model.toAdventureEncounterRoster
import com.nullplaying.remote.isValidAdventureCandidate
import com.nullplaying.model.TRUSTED_TIMELINE_PROVISIONAL_NEW
import com.nullplaying.model.TRUSTED_TIMELINE_VERIFIED
import com.nullplaying.time.TrustedGameClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

internal fun adventureQaTriggerBuildAllowed(
    debug: Boolean,
    previewEnabled: Boolean,
    remoteServicesEnabled: Boolean,
    applicationId: String,
): Boolean = debug && previewEnabled && !remoteServicesEnabled &&
    applicationId.endsWith(".adventurepreview")

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

enum class RewardedOfflineGrantStatus {
    APPLIED,
    ALREADY_APPLIED,
    INELIGIBLE,
    CHARACTER_NOT_FOUND,
}

class SimpleGameRepository(
    private val database: SimpleDatabase,
    private val engine: SimpleGameEngine,
    private val backupStore: SimpleStateBackupStore,
    private val progressEventSink: GameProgressEventSink = NoOpGameProgressEventSink,
    private val accountProgressDao: SimpleAccountProgressDao = database.accountProgressDao(),
    /** False in builds where authenticated shared-player transport is unavailable. */
    private val publicPlayerRosterRetentionEnabled: Boolean = true,
    private val gameClock: TrustedGameClock? = null,
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
    @Volatile
    private var publicPlayerRosterPurgeRequested = false
    private var lastOfflineAdventureUiEmissionAt = 0L
    private val characterStates = linkedMapOf<Int, SimpleGameState>()
    private val lastPersistedEntities = mutableMapOf<Int, SimpleStateEntity>()
    private var activeSlotId: Int? = null
    private var accountProgress = SimpleAccountProgressEntity()
    private var unlockedCharacterSlotCount = INITIAL_UNLOCKED_CHARACTER_SLOT_COUNT
    @Volatile
    private var timelineVerified = false
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

    /** Queues one catalog-selected event only in the isolated, network-disabled adventure QA app. */
    suspend fun queueAdventureEventForQa(eventId: String, now: Long): Boolean = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        if (!adventureQaTriggerBuildAllowed(
                debug = BuildConfig.DEBUG,
                previewEnabled = BuildConfig.ADVENTURE_PREVIEW_ENABLED,
                remoteServicesEnabled = BuildConfig.REMOTE_SERVICES_ENABLED,
                applicationId = BuildConfig.APPLICATION_ID,
            )
        ) {
            return@withLock false
        }
        val current = mutableSnapshots.value.state ?: return@withLock false
        val progressCheckpoint = current.progressCheckpoint()
        val delta = engine.settle(current, maxOf(lockedNow, current.lastSettledAt))
        val queued = engine.queueAdventureEventForQa(current, eventId)
        if (queued || delta.elapsedMillis > 0L || delta.recentEvents.isNotEmpty()) {
            persist(current, lockedNow, recentEvents = delta.recentEvents)
            publishProgressEvent(progressCheckpoint, current)
        }
        queued
    }

    fun observeRecentAdventureEvents(
        slotId: Int,
        limit: Int = RECENT_ADVENTURE_EVENT_LIMIT,
    ): Flow<List<RecentAdventureEventRecord>> =
        database.recentAdventureEventDao()
            .observeRecent(slotId, limit.coerceIn(1, RECENT_ADVENTURE_EVENT_LIMIT))
            .map { entities -> entities.mapNotNull(RecentAdventureEventEntity::toRecordOrNull) }

    suspend fun markRecentAdventureEventsSeen(eventId: Long, now: Long) = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        val current = mutableSnapshots.value.state ?: return@withLock
        if (eventId <= current.lastSeenRecentAdventureEventId) return@withLock
        current.lastSeenRecentAdventureEventId = eventId
        persist(current, lockedNow)
    }

    suspend fun chooseHeroPath(
        tokenId: String,
        offerId: String,
        expectedRevision: Long,
        now: Long,
    ): HeroPathMutationStatus = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        val current = mutableSnapshots.value.state
            ?: return@withLock HeroPathMutationStatus.UNKNOWN_TOKEN
        if (current.heroPath.revision != expectedRevision) {
            return@withLock HeroPathMutationStatus.STALE_REVISION
        }
        val mutation = HeroPathEngine.resolveMilestone(
            state = current.heroPath,
            tokenId = tokenId,
            offerId = offerId,
        )
        if (mutation.status == HeroPathMutationStatus.APPLIED) {
            current.heroPath = mutation.state
            // A long offline session can earn several milestones. Only now may the next oldest
            // choice be generated because its candidates depend on this committed selection.
            engine.reconcileHeroPath(current)
            persist(current, lockedNow)
        }
        mutation.status
    }

    suspend fun applyHeroPathDraft(
        traitIds: List<String>,
        expectedRevision: Long,
        now: Long,
    ): HeroPathMutationStatus = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        val current = mutableSnapshots.value.state
            ?: return@withLock HeroPathMutationStatus.INVALID_ALLOCATION
        if (current.heroPath.revision != expectedRevision) {
            return@withLock HeroPathMutationStatus.STALE_REVISION
        }
        val mutation = HeroPathEngine.applyDraft(current.heroPath, traitIds)
        if (mutation.status == HeroPathMutationStatus.APPLIED) {
            current.heroPath = mutation.state
            engine.reconcileHeroPath(current)
            persist(current, lockedNow)
        }
        mutation.status
    }

    /** Atomically validates, swaps the complete V2 allocation, and persists it once. */
    suspend fun applyHeroPathAllocation(
        target: HeroPathAllocationTarget,
        now: Long,
    ): HeroPathMutationStatus = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        val current = mutableSnapshots.value.state
            ?: return@withLock HeroPathMutationStatus.INVALID_ALLOCATION
        if (current.heroPath.revision != target.expectedRevision) {
            return@withLock HeroPathMutationStatus.STALE_REVISION
        }
        val mutation = HeroPathEngine.applyAllocationTarget(current.heroPath, target)
        if (mutation.status == HeroPathMutationStatus.APPLIED) {
            current.heroPath = mutation.state
            engine.reconcileHeroPath(current)
            persist(current, lockedNow)
        }
        mutation.status
    }

    suspend fun resetHeroPath(
        expectedRevision: Long,
        now: Long,
    ): HeroPathMutationStatus = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        val current = mutableSnapshots.value.state
            ?: return@withLock HeroPathMutationStatus.INVALID_ALLOCATION
        if (current.heroPath.revision != expectedRevision) {
            return@withLock HeroPathMutationStatus.STALE_REVISION
        }
        val mutation = HeroPathEngine.resetAllocation(current.heroPath)
        if (mutation.status == HeroPathMutationStatus.APPLIED) {
            current.heroPath = mutation.state
            persist(current, lockedNow)
        }
        mutation.status
    }

    /** Installs already-received public data only after finishing time with the previous roster. */
    suspend fun updateAdventureEncounterRoster(
        roster: AdventureEncounterRoster,
        now: Long,
        elapsedRealtime: Long,
        isSourceCurrent: () -> Boolean = { true },
    ): Boolean = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        if (!mutableSnapshots.value.ready || characterStates.isEmpty() || !isSourceCurrent()) return@withLock false
        if (roster.snapshotId.isBlank() || roster.snapshotId.length > 128 ||
            roster.snapshotId != roster.snapshotId.trim() || roster.snapshotId.any(Char::isISOControl) ||
            roster.receivedAt <= 0L || roster.receivedAt > lockedNow ||
            roster.validUntil <= lockedNow || roster.validUntil <= roster.receivedAt
        ) return@withLock false
        val ownedIds = characterStates.values.map { it.rankingCharacterId }.toSet()
        val duplicateIds = roster.candidates.groupingBy { it.characterId }.eachCount()
            .filterValues { it > 1 }.keys
        val validCandidates = roster.candidates.filter {
            it.characterId !in ownedIds && it.characterId !in duplicateIds &&
                isValidAdventureCandidate(it.characterId, it.displayName, it.level, it.combatPower)
        }.sortedBy { it.characterId }
        val validCandidateIds = validCandidates.map { it.characterId }.toSet()
        val eligibleSlots = characterStates.filterValues { state ->
            val previous = state.adventureRelationships.roster
            if (previous?.snapshotId == roster.snapshotId) {
                // Account ownership can change within one published day. Remove newly excluded
                // IDs, but never add rows or replace metadata/timestamps for the same version.
                previous.candidates.any { it.characterId !in validCandidateIds }
            } else previous == null || roster.receivedAt >= previous.receivedAt
        }.keys
        if (eligibleSlots.isEmpty()) return@withLock false
        val checkpoints = characterStates.mapValues { it.value.progressCheckpoint() }
        val recentEventsBySlot = mutableMapOf<Int, List<RecentAdventureEvent>>()
        // Work on detached states so readers never see a new roster before settlement commits.
        val settledStates = withContext(Dispatchers.Default) {
            characterStates.mapValues { json.decodeFromString<SimpleGameState>(json.encodeToString(it.value)) }
        }
        settledStates.forEach { (slotId, state) ->
            val delta = if (appInForeground && slotId == activeSlotId) {
                if (!rewardAdInFlight) engine.advanceOfflineAdventureForeground(
                    state, (elapsedRealtime - foregroundElapsedRealtime).coerceAtLeast(0L),
                )
                engine.settleOffline(state, lockedNow)
            } else {
                engine.settleOfflineWithOfflineAdventure(state, lockedNow)
            }
            recentEventsBySlot[slotId] = delta.recentEvents
            if (slotId in eligibleSlots) {
                val previous = state.adventureRelationships.roster
                val updatedRoster = if (previous?.snapshotId == roster.snapshotId) {
                    previous.copy(candidates = previous.candidates.filter { it.characterId in validCandidateIds })
                } else {
                    roster.copy(candidates = validCandidates.filter {
                        it.level in (state.hero.level - 1L)..(state.hero.level + 1L)
                    }.take(64))
                }
                state.adventureRelationships = state.adventureRelationships.copy(
                    roster = updatedRoster,
                )
            }
        }
        if (!isSourceCurrent()) return@withLock false
        // State and generated recent-event rows for every slot commit together.
        val previousStates = characterStates.toMap()
        val previousEntities = lastPersistedEntities.toMap()
        val previousAccountProgress = accountProgress
        val previousUnlockedSlots = unlockedCharacterSlotCount
        try {
            database.withTransaction {
                settledStates.forEach { (slotId, state) ->
                    persistSlot(
                        slotId = slotId,
                        state = state,
                        now = lockedNow,
                        emitSnapshot = false,
                        recentEvents = recentEventsBySlot[slotId].orEmpty(),
                    )
                }
                check(isSourceCurrent()) { "Ranking account changed before encounter roster commit" }
            }
        } catch (failure: Throwable) {
            characterStates.clear()
            characterStates.putAll(previousStates)
            lastPersistedEntities.clear()
            lastPersistedEntities.putAll(previousEntities)
            if (accountProgress != previousAccountProgress) {
                // Account recovery metadata is file-backed outside the Room transaction.
                withContext(NonCancellable) {
                    runCatching { backupStore.saveAccountProgress(previousAccountProgress) }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                }
            }
            accountProgress = previousAccountProgress
            unlockedCharacterSlotCount = previousUnlockedSlots
            throw failure
        }
        if (appInForeground) foregroundElapsedRealtime = elapsedRealtime
        settledStates.forEach { (slotId, state) -> publishProgressEvent(checkpoints.getValue(slotId), state) }
        emitReady()
        true
    }

    /**
     * Installs one authenticated daily roster into the matching character save. The compact
     * relationship view and full arena input commit together and share server projection IDs.
     */
    suspend fun installPublicPlayerRoster(
        roster: PublicPlayerRoster,
        now: Long,
        isSourceCurrent: () -> Boolean = { true },
    ): Boolean = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        if (!publicPlayerRosterRetentionEnabled) return@withLock false
        if (!isSourceCurrent()) return@withLock false
        val target = characterStates.entries.singleOrNull {
            it.value.rankingCharacterId == roster.requesterCharacterId
        } ?: return@withLock false
        if (!roster.isReusableFor(roster.requesterCharacterId, target.value.hero.level, lockedNow)) {
            return@withLock false
        }
        val state = target.value
        if (!isSourceCurrent()) return@withLock false
        val previous = state.publicPlayerRoster
        if (previous?.rosterId == roster.rosterId && previous == roster) return@withLock true
        state.publicPlayerRoster = roster
        state.adventureRelationships = state.adventureRelationships.copy(
            roster = roster.toAdventureEncounterRoster(),
        )
        persistSlot(target.key, state, lockedNow)
        true
    }

    /** Pure lookup boundary for relationship and arena adapters; no Android storage dependency. */
    fun publicPlayerSnapshot(projectionId: String): PublicPlayerSnapshot? {
        val matches = mutableSnapshots.value.characters.mapNotNull {
            it.state.publicPlayerRoster?.snapshotFor(projectionId)
        }.distinct()
        return matches.singleOrNull()
    }

    /** Removes auth-scoped public data after anonymous identity replacement or feature shutdown. */
    suspend fun clearPublicPlayerRosters(now: Long) = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        // Auth initialization can replace the account before save initialization finishes.
        // Retain this in-process barrier until every loaded slot has been sanitized and saved.
        publicPlayerRosterPurgeRequested = true
        val affected = characterStates.keys.filter { slotId ->
            purgePublicPlayerData(characterStates.getValue(slotId), lockedNow, force = true)
        }
        affected.forEach { slotId ->
            val state = characterStates.getValue(slotId)
            persistPurgedPublicPlayerData(slotId, state, lockedNow)
        }
        if (characterStates.isNotEmpty()) publicPlayerRosterPurgeRequested = false
        if (affected.isNotEmpty()) emitReady()
    }

    /** Drops expired raw opponent input even when the refresh request later fails. */
    suspend fun clearExpiredPublicPlayerRosters(now: Long) = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        val affected = characterStates.keys.filter { slotId ->
            purgePublicPlayerData(characterStates.getValue(slotId), lockedNow, force = false)
        }
        affected.forEach { slotId ->
            persistPurgedPublicPlayerData(slotId, characterStates.getValue(slotId), lockedNow)
        }
        if (affected.isNotEmpty()) emitReady()
    }

    suspend fun updateOfflineAdventureConfig(
        config: OfflineAdventureConfig,
        now: Long,
        elapsedRealtime: Long,
    ): Boolean = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        if (!mutableSnapshots.value.ready || !appInForeground || rewardAdInFlight) return@withLock false
        if (engine.offlineAdventureConfig == config) return@withLock true

        // Finish elapsed time under the old rules, including inactive character slots.
        val checkpoints = characterStates.mapValues { it.value.progressCheckpoint() }
        val recentEventsBySlot = mutableMapOf<Int, List<RecentAdventureEvent>>()
        val purgedSlots = mutableSetOf<Int>()
        characterStates.forEach { (slotId, state) ->
            if (purgePublicPlayerData(state, lockedNow, force = false)) purgedSlots += slotId
            val delta = if (slotId == activeSlotId) {
                advanceForegroundOfflineAdventure(state, elapsedRealtime)
                engine.settleOffline(state, lockedNow)
            } else {
                engine.settleOfflineWithOfflineAdventure(state, lockedNow)
            }
            recentEventsBySlot[slotId] = delta.recentEvents
        }
        engine.updateOfflineAdventureConfig(config)
        characterStates.entries.toList().forEach { (slotId, state) ->
            engine.clampOfflineAdventureBalance(state)
            if (slotId in purgedSlots) {
                persistPurgedPublicPlayerData(
                    slotId, state, lockedNow, recentEventsBySlot[slotId].orEmpty(),
                )
            } else {
                persistSlot(
                    slotId = slotId,
                    state = state,
                    now = lockedNow,
                    emitSnapshot = false,
                    recentEvents = recentEventsBySlot[slotId].orEmpty(),
                )
            }
            publishProgressEvent(checkpoints.getValue(slotId), state)
        }
        emitReady()
        true
    }

    suspend fun initialize(
        now: Long,
        trustedTime: Boolean = false,
        deferUnverifiedSettlement: Boolean = false,
    ): Boolean = mutex.withLock {
        try {
            val lockedNow = trustedNowInsideLock(now)
            timelineVerified = trustedTime
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
                        val settled = decodeAndSettle(
                            primary,
                            lockedNow,
                            trustedTime,
                            deferUnverifiedSettlement,
                        )
                        LoadedCharacter(
                            slotId = primary.id,
                            state = settled.state,
                            sourceEntity = primary,
                            recoveredFromBackup = false,
                            progressEvent = settled.progressEvent,
                            recentEvents = settled.recentEvents,
                            publicPlayerDataPurged = settled.publicPlayerDataPurged,
                            clampFutureRecentEventsAt = settled.clampFutureRecentEventsAt,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (primaryFailure: Exception) {
                        val backup = backupStore.load(primary.id) ?: throw primaryFailure
                        val settled = decodeAndSettle(
                            backup,
                            lockedNow,
                            trustedTime,
                            deferUnverifiedSettlement,
                        )
                        LoadedCharacter(
                            slotId = primary.id,
                            state = settled.state,
                            sourceEntity = backup,
                            recoveredFromBackup = true,
                            progressEvent = settled.progressEvent,
                            recentEvents = settled.recentEvents,
                            publicPlayerDataPurged = settled.publicPlayerDataPurged,
                            clampFutureRecentEventsAt = settled.clampFutureRecentEventsAt,
                        )
                    }
                }
            } else {
                val backups = (1..MAX_CHARACTER_SLOTS).mapNotNull { slotId ->
                    backupStore.load(slotId)?.let { backup ->
                        val settled = decodeAndSettle(
                            backup,
                            lockedNow,
                            trustedTime,
                            deferUnverifiedSettlement,
                        )
                        LoadedCharacter(
                            slotId = slotId,
                            state = settled.state,
                            sourceEntity = backup,
                            recoveredFromBackup = true,
                            progressEvent = settled.progressEvent,
                            recentEvents = settled.recentEvents,
                            publicPlayerDataPurged = settled.publicPlayerDataPurged,
                            clampFutureRecentEventsAt = settled.clampFutureRecentEventsAt,
                        )
                    }
                }
                if (backups.isEmpty()) throw requireNotNull(primaryResult.exceptionOrNull())
                backups
            }

            if (settledCharacters.isEmpty()) {
                publicPlayerRosterPurgeRequested = false
                updateActiveCharacterSlot(null)
                persistAccountProgress(accountProgress)
                emitReady(recoveredFromBackup = loadedAccountProgress.recoveredFromBackup)
                return@withLock true
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
                    now = lockedNow,
                    rotateBackup = !loaded.recoveredFromBackup && !loaded.publicPlayerDataPurged,
                    emitSnapshot = false,
                    recentEvents = loaded.recentEvents,
                    clampFutureRecentEventsAt = loaded.clampFutureRecentEventsAt,
                )
                if (loaded.publicPlayerDataPurged) {
                    // Do not leave the pre-purge payload in the fallback save.
                    backupStore.save(requireNotNull(lastPersistedEntities[loaded.slotId]))
                }
                loaded.progressEvent?.let(progressEventSink::onGameProgress)
            }
            publicPlayerRosterPurgeRequested = false
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
            true
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
            false
        }
    }

    suspend fun reportClockInitializationFailure() = mutex.withLock {
        if (mutableSnapshots.value.ready) return@withLock
        emit(
            state = null,
            ready = false,
            startupPhase = StartupPhase.FAILED,
            startupError = "안전한 게임 시각을 확인하지 못했습니다. 다시 시도해 주세요.",
        )
    }

    /**
     * Supplies a conservative first anchor when v18 starts without a server response. Existing
     * saves begin at their newest durable gameplay checkpoint, never at the editable device wall
     * clock. A later verified observation can therefore grant only the normal bank-limited gap.
     */
    suspend fun persistedTimelineCeilingOrNull(): Long? = mutex.withLock {
        val primary = runCatching { database.stateDao().loadAllCharacterSlots() }.getOrNull()
        if (primary != null) {
            if (primary.isEmpty()) return@withLock null
            return@withLock primary.maxOfOrNull(::persistedTimelineOf) ?: 0L
        }

        val backups = (1..MAX_CHARACTER_SLOTS).mapNotNull { slotId ->
            runCatching { backupStore.load(slotId) }.getOrNull()
        }
        if (backups.isEmpty()) null else backups.maxOfOrNull(::persistedTimelineOf) ?: 0L
    }

    suspend fun createCharacter(
        name: String,
        heroClass: HeroClass,
        stats: HeroStats,
        seed: Long,
        now: Long,
    ) = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        check(characterStates.size < unlockedCharacterSlotCount) {
            "No unlocked character slot is available"
        }
        compactExistingCharacterSlotsIfNeeded()
        val slotId = characterStates.size + 1
        val game = engine.newGame(name, heroClass, stats, seed, lockedNow)
        game.trustedTimelineVersion = if (timelineVerified) {
            TRUSTED_TIMELINE_VERIFIED
        } else {
            TRUSTED_TIMELINE_PROVISIONAL_NEW
        }
        if (BuildConfig.IS_EEA_QA) {
            // Dedicated device QA starts below capacity so the real rewarded-ad path can load.
            game.offlineAdventureMillis = 0L
        }
        game.rankingCharacterId = UUID.randomUUID().toString()
        persistSlot(slotId, game, lockedNow, emitSnapshot = false)
        updateActiveCharacterSlot(slotId)
        emitReady()
    }

    suspend fun selectCharacter(slotId: Int, now: Long): Result<Unit> = mutex.withLock {
        try {
            val lockedNow = trustedNowInsideLock(now)
            val selected = characterStates[slotId]
                ?: return@withLock Result.failure(
                    IllegalArgumentException("Character slot $slotId does not exist"),
                )
            val publicPlayerDataPurged = purgePublicPlayerData(selected, lockedNow, force = false)
            val checkpoint = selected.progressCheckpoint()
            val delta = engine.settleOfflineWithOfflineAdventure(selected, lockedNow)
            if (publicPlayerDataPurged) {
                persistPurgedPublicPlayerData(slotId, selected, lockedNow, delta.recentEvents)
            } else {
                persistSlot(
                    slotId = slotId,
                    state = selected,
                    now = lockedNow,
                    emitSnapshot = false,
                    recentEvents = delta.recentEvents,
                )
            }
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
        val lockedNow = trustedNowInsideLock(now)
        if (!appInForeground) return@withLock
        val current = mutableSnapshots.value.state ?: return@withLock
        val publicPlayerDataPurged = purgePublicPlayerData(current, lockedNow, force = false)
        val beforeActionSequence = current.actionSequence
        val beforeMonsterId = current.monster.id
        val beforeAdventurePhase = current.adventurePhase
        val beforePhase = current.combatPhase
        val beforeOfflineAdventure = current.offlineAdventureMillis
        val progressCheckpoint = current.progressCheckpoint()
        advanceForegroundOfflineAdventure(current, elapsedRealtime)
        val elapsed = lockedNow - current.lastSettledAt
        val delta = if (elapsed >= OFFLINE_SETTLEMENT_THRESHOLD_MILLIS) {
            engine.settleOffline(current, lockedNow)
        } else {
            engine.settle(current, lockedNow)
        }
        if (
            current.actionSequence != beforeActionSequence ||
            current.monster.id != beforeMonsterId ||
            current.adventurePhase != beforeAdventurePhase ||
            current.combatPhase != beforePhase ||
            publicPlayerDataPurged ||
            delta.defeatedMonsters > 0L ||
            delta.recentEvents.isNotEmpty()
        ) {
            if (publicPlayerDataPurged) {
                persistPurgedPublicPlayerData(
                    requireNotNull(activeSlotId), current, lockedNow, delta.recentEvents,
                )
                emitReady()
            } else {
                persist(current, lockedNow, recentEvents = delta.recentEvents)
            }
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
        val lockedNow = trustedNowInsideLock(now)
        if (appInForeground) {
            foregroundElapsedRealtime = elapsedRealtime
            return@withLock
        }
        foregroundElapsedRealtime = elapsedRealtime
        lastOfflineAdventureUiEmissionAt = elapsedRealtime
        if (characterStates.isEmpty()) {
            appInForeground = true
            return@withLock
        }

        // Returning from Home can restore the remembered roster immediately. Publish a loading
        // state first, settle every owned character, then reveal one coherent roster snapshot.
        emitStartup(StartupPhase.SETTLING_OFFLINE)
        try {
            characterStates.entries.sortedBy { it.key }.forEach { (slotId, state) ->
                val publicPlayerDataPurged = purgePublicPlayerData(state, lockedNow, force = false)
                val checkpoint = state.progressCheckpoint()
                val delta = engine.settleOfflineWithOfflineAdventure(state, lockedNow)
                if (publicPlayerDataPurged) {
                    persistPurgedPublicPlayerData(slotId, state, lockedNow, delta.recentEvents)
                } else {
                    persistSlot(
                        slotId = slotId,
                        state = state,
                        now = lockedNow,
                        emitSnapshot = false,
                        recentEvents = delta.recentEvents,
                    )
                }
                publishProgressEvent(checkpoint, state)
            }
            // Unlimited foreground settlement becomes legal only after every slot completed its
            // bank-limited catch-up and durable save.
            appInForeground = true
        } finally {
            // A storage or settlement failure must not leave the retained UI permanently stuck
            // on the resume-loading screen. The caller still receives the original exception.
            emitReady()
        }
    }

    suspend fun onAppBackgrounded(now: Long, elapsedRealtime: Long) = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        if (!appInForeground) return@withLock
        try {
            val current = mutableSnapshots.value.state
            if (current != null) {
                val publicPlayerDataPurged = purgePublicPlayerData(current, lockedNow, force = false)
                val checkpoint = current.progressCheckpoint()
                advanceForegroundOfflineAdventure(current, elapsedRealtime)
                val elapsed = lockedNow - current.lastSettledAt
                val delta = if (elapsed >= OFFLINE_SETTLEMENT_THRESHOLD_MILLIS) {
                    engine.settleOffline(current, lockedNow)
                } else {
                    engine.settle(current, lockedNow)
                }
                if (publicPlayerDataPurged) {
                    persistPurgedPublicPlayerData(
                        requireNotNull(activeSlotId), current, lockedNow, delta.recentEvents,
                    )
                    emitReady()
                } else {
                    persist(current, lockedNow, recentEvents = delta.recentEvents)
                }
                publishProgressEvent(checkpoint, current)
            }
        } finally {
            // A failed save must not leave background execution on the unlimited foreground path.
            appInForeground = false
            foregroundElapsedRealtime = elapsedRealtime
        }
    }

    suspend fun runBackgroundSettlement(now: Long) {
        if (!snapshots.value.ready) {
            val trustedTime = gameClock?.nowOrNull()?.isServerVerified ?: timelineVerified
            initialize(now, trustedTime = trustedTime)
            return
        }
        mutex.withLock {
            val lockedNow = trustedNowInsideLock(now)
            if (appInForeground || characterStates.isEmpty()) return@withLock
            characterStates.entries.sortedBy { it.key }.forEach { (slotId, state) ->
                val publicPlayerDataPurged = purgePublicPlayerData(state, lockedNow, force = false)
                val checkpoint = state.progressCheckpoint()
                val delta = engine.settleOfflineWithOfflineAdventure(state, lockedNow)
                if (publicPlayerDataPurged) {
                    persistPurgedPublicPlayerData(slotId, state, lockedNow, delta.recentEvents)
                } else {
                    persistSlot(
                        slotId = slotId,
                        state = state,
                        now = lockedNow,
                        emitSnapshot = false,
                        recentEvents = delta.recentEvents,
                    )
                }
                publishProgressEvent(checkpoint, state)
            }
            emitReady()
        }
    }

    /**
     * Atomically switches progression to a verified server epoch. The anchor is committed while
     * the repository mutex excludes the 90 ms tick, then every slot is brought to that epoch using
     * bank-limited settlement or a no-reward rebase. This prevents the clock jump from reaching
     * the foreground unlimited-settlement path.
     */
    suspend fun adoptVerifiedServerTime(
        clock: TrustedGameClock,
        serverEpochMillis: Long,
    ): Boolean = mutex.withLock {
        val proposal = clock.previewVerifiedServerObservation(serverEpochMillis)
            ?: return@withLock false
        val trustedNow = proposal.proposedEpochMillis
        clock.adoptVerifiedServerObservation(proposal) ?: return@withLock false
        timelineVerified = true

        data class ReconciledSlot(
            val slotId: Int,
            val state: SimpleGameState,
            val checkpoint: GameProgressCheckpoint,
            val recentEvents: List<RecentAdventureEvent>,
        )

        // Reconcile every in-memory state before starting I/O. If a later Room write fails, no
        // active state can observe the newly committed clock as an unlimited foreground gap.
        val reconciled = characterStates.entries.sortedBy { it.key }.map { (slotId, state) ->
            val checkpoint = state.progressCheckpoint()
            val mustRebase = state.trustedTimelineVersion == TRUSTED_TIMELINE_PROVISIONAL_NEW ||
                state.lastSettledAt > trustedNow
            val recentEvents = if (mustRebase) {
                engine.rebaseTimelineWithoutProgress(state, trustedNow)
                engine.settleOfflineWithOfflineAdventure(state, trustedNow).recentEvents
            } else {
                engine.settleOfflineWithOfflineAdventure(state, trustedNow).recentEvents
            }
            state.trustedTimelineVersion = TRUSTED_TIMELINE_VERIFIED
            ReconciledSlot(slotId, state, checkpoint, recentEvents)
        }

        reconciled.forEach { slot ->
            persistSlot(
                slotId = slot.slotId,
                state = slot.state,
                now = trustedNow,
                emitSnapshot = false,
                recentEvents = slot.recentEvents,
                clampFutureRecentEventsAt = trustedNow,
            )
            publishProgressEvent(slot.checkpoint, slot.state)
        }
        if (reconciled.isNotEmpty()) emitReady()
        true
    }

    fun setRewardAdInFlight(inFlight: Boolean, elapsedRealtime: Long) {
        // Publish the monotonic anchor before the volatile flag. A tick that observes the new flag
        // must also observe the matching anchor and cannot credit the full-screen interval.
        foregroundElapsedRealtime = elapsedRealtime
        rewardAdInFlight = inFlight
    }

    fun isRewardAdInFlight(): Boolean = rewardAdInFlight

    suspend fun grantRewardedOfflineAdventure(now: Long, rewardRequestId: String): Boolean =
        mutex.withLock {
            val lockedNow = trustedNowInsideLock(now)
            val current = mutableSnapshots.value.state ?: return@withLock false
            val granted = engine.grantRewardedOfflineAdventure(current, rewardRequestId)
            if (granted) persist(current, lockedNow)
            granted
        }

    /**
     * Applies a durable SDK-earned reward to the character that opened the ad, even if the UI was
     * recreated or another slot became active before the application worker resumed.
     */
    suspend fun grantRewardedOfflineAdventureForCharacter(
        characterId: String,
        now: Long,
        rewardRequestId: String,
    ): RewardedOfflineGrantStatus = mutex.withLock {
        val lockedNow = trustedNowInsideLock(now)
        val (slotId, current) = characterStates.entries
            .singleOrNull { it.value.rankingCharacterId == characterId }
            ?.let { it.key to it.value }
            ?: return@withLock RewardedOfflineGrantStatus.CHARACTER_NOT_FOUND
        if (
            rewardRequestId == current.lastRewardRequestId ||
            rewardRequestId in current.rewardedOfflineRequestIds
        ) return@withLock RewardedOfflineGrantStatus.ALREADY_APPLIED
        if (!engine.grantRewardedOfflineAdventure(current, rewardRequestId)) {
            return@withLock RewardedOfflineGrantStatus.INELIGIBLE
        }
        persistSlot(slotId = slotId, state = current, now = lockedNow)
        RewardedOfflineGrantStatus.APPLIED
    }

    /**
     * Mutation callers may have sampled the clock before waiting for [mutex]. Re-read it only
     * after entering the repository critical section so a concurrent server correction cannot be
     * followed by a queued mutation carrying the old provisional/future epoch.
     */
    private fun trustedNowInsideLock(callerSample: Long): Long {
        val boundClock = gameClock ?: return callerSample
        return requireNotNull(boundClock.nowOrNull()) {
            "Trusted game clock became unavailable during a repository mutation"
        }.epochMillis
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
        trustedTime: Boolean,
        deferUnverifiedSettlement: Boolean,
    ): SettledState {
        val (loaded, legacy) = withContext(Dispatchers.Default) {
            val legacySnapshot = legacyAutoHuntSnapshot(entity.payload)
            json.decodeFromString<SimpleGameState>(entity.payload) to legacySnapshot
        }
        val publicPlayerDataPurged = purgePublicPlayerData(loaded, now, force = false)
        val checkpoint = loaded.progressCheckpoint()
        var rebased = false
        val delta = withContext(Dispatchers.Default) {
            if (
                deferUnverifiedSettlement ||
                (
                    trustedTime &&
                        (loaded.trustedTimelineVersion == TRUSTED_TIMELINE_PROVISIONAL_NEW ||
                            loaded.lastSettledAt > now)
                )
            ) {
                engine.rebaseTimelineWithoutProgress(loaded, now)
                rebased = true
                engine.settleOfflineWithOfflineAdventure(loaded, now, legacy)
            } else {
                engine.settleOfflineWithOfflineAdventure(loaded, now, legacy)
            }
        }
        if (trustedTime) loaded.trustedTimelineVersion = TRUSTED_TIMELINE_VERIFIED
        return SettledState(
            state = loaded,
            progressEvent = gameProgressEventBetween(checkpoint, loaded),
            recentEvents = delta.recentEvents,
            publicPlayerDataPurged = publicPlayerDataPurged,
            clampFutureRecentEventsAt = now.takeIf { rebased || trustedTime },
        )
    }

    /**
     * Keeps compact relationship contacts and memories, but removes the expiring roster payload
     * and participant stats/traits copied into an unfinished encounter.
     */
    private fun purgePublicPlayerData(
        state: SimpleGameState,
        now: Long,
        force: Boolean,
    ): Boolean {
        val fullRoster = state.publicPlayerRoster
        val compactRoster = state.adventureRelationships.roster
        val forceClear = force || publicPlayerRosterPurgeRequested || !publicPlayerRosterRetentionEnabled
        val fullInvalid = fullRoster != null && !fullRoster.isReusableFor(
            characterId = state.rankingCharacterId,
            currentRequesterLevel = state.hero.level,
            nowEpochMillis = now,
        )
        val compactInvalid = compactRoster != null && (
            compactRoster.snapshotId.isBlank() ||
                compactRoster.receivedAt <= 0L ||
                compactRoster.receivedAt > now ||
                compactRoster.validUntil <= now ||
                compactRoster.validUntil <= compactRoster.receivedAt
            )
        val removedRosterIds = buildSet {
            if (forceClear || fullInvalid) fullRoster?.rosterId?.let { add(it) }
            if (forceClear || compactInvalid) compactRoster?.snapshotId?.let { add(it) }
        }
        var changed = false
        if ((forceClear || fullInvalid) && fullRoster != null) {
            state.publicPlayerRoster = null
            changed = true
        }
        if (
            compactRoster != null && (
                forceClear || compactInvalid ||
                    (fullInvalid && compactRoster.snapshotId == fullRoster?.rosterId)
                )
        ) {
            state.adventureRelationships = state.adventureRelationships.copy(roster = null)
            changed = true
        }
        val pending = state.adventureRelationships.pending
        if (
            pending != null &&
            (pending.localBattleSnapshot != null || pending.opponentBattleSnapshot != null) &&
            (forceClear || pending.snapshotId in removedRosterIds)
        ) {
            state.adventureRelationships = state.adventureRelationships.copy(
                pending = pending.copy(
                    localBattleSnapshot = null,
                    opponentBattleSnapshot = null,
                ),
            )
            changed = true
        }
        val relationships = state.adventureRelationships
        val compactContacts = relationships.contacts.map { contact ->
            contact.copy(latestSnapshot = compactRelationshipCandidate(contact.latestSnapshot))
        }
        val compactLastResult = relationships.lastResult?.let(::compactRelationshipResult)
        val compactRecentResults = relationships.recentResults.map(::compactRelationshipResult)
        val compactPending = relationships.pending?.let { run ->
            val compactCandidate = compactRelationshipCandidate(run.candidate)
            if (compactCandidate == run.candidate) run else run.copy(candidate = compactCandidate)
        }
        if (
            compactContacts != relationships.contacts ||
            compactLastResult != relationships.lastResult ||
            compactRecentResults != relationships.recentResults ||
            compactPending != relationships.pending
        ) {
            state.adventureRelationships = relationships.copy(
                contacts = compactContacts,
                lastResult = compactLastResult,
                recentResults = compactRecentResults,
                pending = compactPending,
            )
            changed = true
        }
        return changed
    }

    private fun compactRelationshipCandidate(candidate: AdventureEncounterCandidate) = candidate.copy(
        stats = null,
        learnedSkills = emptyList(),
        equipment = emptyList(),
        adventureTraitIds = emptyList(),
    )

    private fun compactRelationshipResult(result: AdventureRelationshipResult) = result.copy(
        run = compactRelationshipRun(result.run),
    )

    private fun compactRelationshipRun(run: AdventureRelationshipRun) = run.copy(
        candidate = compactRelationshipCandidate(run.candidate),
        localBattleSnapshot = null,
        opponentBattleSnapshot = null,
    )

    /** A privacy purge replaces both Room and the fallback file with the sanitized payload. */
    private suspend fun persistPurgedPublicPlayerData(
        slotId: Int,
        state: SimpleGameState,
        now: Long,
        recentEvents: List<RecentAdventureEvent> = emptyList(),
    ) {
        persistSlot(
            slotId = slotId,
            state = state,
            now = now,
            rotateBackup = false,
            emitSnapshot = false,
            recentEvents = recentEvents,
        )
        backupStore.save(requireNotNull(lastPersistedEntities[slotId]))
    }

    private fun persistedTimelineOf(entity: SimpleStateEntity): Long {
        val payloadCheckpoint = runCatching {
            json.parseToJsonElement(entity.payload)
                .jsonObject["lastSettledAt"]
                ?.jsonPrimitive
                ?.longOrNull
        }.getOrNull()
        return (payloadCheckpoint ?: entity.updatedAt).coerceAtLeast(0L)
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
        recentEvents: List<RecentAdventureEvent> = emptyList(),
    ) {
        val slotId = requireNotNull(activeSlotId) { "No active character slot" }
        persistSlot(
            slotId = slotId,
            state = state,
            now = now,
            rotateBackup = rotateBackup,
            recoveredFromBackup = recoveredFromBackup,
            recentEvents = recentEvents,
        )
    }

    private suspend fun persistSlot(
        slotId: Int,
        state: SimpleGameState,
        now: Long,
        rotateBackup: Boolean = true,
        recoveredFromBackup: Boolean = false,
        emitSnapshot: Boolean = true,
        recentEvents: List<RecentAdventureEvent> = emptyList(),
        clampFutureRecentEventsAt: Long? = null,
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
        database.withTransaction {
            database.stateDao().save(nextEntity)
            if (clampFutureRecentEventsAt != null) {
                database.recentAdventureEventDao().clampFutureTimestamps(
                    slotId = slotId,
                    trustedNow = clampFutureRecentEventsAt,
                )
            }
            if (recentEvents.isNotEmpty()) {
                database.recentAdventureEventDao().insertAll(
                    recentEvents.map { it.toEntity(slotId) },
                )
                database.recentAdventureEventDao().trimToLimit(
                    slotId = slotId,
                    limit = RECENT_ADVENTURE_EVENT_LIMIT,
                )
            }
        }
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
        val recentEvents: List<RecentAdventureEvent>,
        val publicPlayerDataPurged: Boolean,
        val clampFutureRecentEventsAt: Long?,
    )

    private data class SettledState(
        val state: SimpleGameState,
        val progressEvent: GameProgressEvent?,
        val recentEvents: List<RecentAdventureEvent>,
        val publicPlayerDataPurged: Boolean,
        val clampFutureRecentEventsAt: Long?,
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
