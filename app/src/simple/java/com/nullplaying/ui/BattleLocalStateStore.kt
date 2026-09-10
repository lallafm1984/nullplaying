package com.nullplaying.ui

import android.content.Context
import android.provider.Settings
import com.nullplaying.engine.arena.ArenaCharacterPointRules
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaProgressionState
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.engine.arena.ArenaSkillTreeMutation
import com.nullplaying.engine.arena.ArenaSkillTreeState
import com.nullplaying.model.HeroClass
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.remote.ArenaRankingLocalStanding
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class BattleLocalSnapshot(
    val gameEpochDay: Long = -1L,
    val entriesRemaining: Int = BATTLE_ENTRY_CAPACITY,
    val entryRecoveryStartedAtMillis: Long = 0L,
    /** V5 used ten slots; V6 keeps ten-minute recovery with five slots and ten daily entries. */
    val entryRecoveryPolicyVersion: Int = 1,
    val dailyBattleDay: Long = Long.MIN_VALUE,
    val dailyBattlesUsed: Int = 0,
    /** Highest accepted arena time. It never follows later device wall-clock edits. */
    val arenaTrustedEpochMillis: Long = 0L,
    val arenaTrustedElapsedRealtimeMillis: Long = -1L,
    val arenaTrustedBootCount: Int = -1,
    val arenaClockServerAnchored: Boolean = false,
    val rewardedRefillDay: Long = Long.MIN_VALUE,
    val rewardedRefillsUsed: Int = 0,
    val lastRewardedRefillRequestId: String = "",
    val rewardedRefillRequestIds: List<String> = emptyList(),
    val placementCompleted: Int = 0,
    val score: Int = BATTLE_START_SCORE,
    /** Trusted time when the current score was reached; stable across ranking-screen visits. */
    val scoreAchievedAtMillis: Long = 0L,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    /** Exact tenth-match aggregate, saved with the battle settlement for binding recovery. */
    val arenaRankingPlacement: ArenaRankingPlacementSnapshot? = null,
    /** Retired condition fields remain decode-only so saves from earlier QA builds still load. */
    val condition: String = "NORMAL",
    val conditionEvaluatedAtMillis: Long = 0L,
    val conditionTransitionSequence: Long = 0L,
    val conditionMomentum: Int = 0,
    val conditionWinStreak: Int = 0,
    val conditionLossStreak: Int = 0,
    val history: List<BattlePreviewHistory> = emptyList(),
    val arenaProgression: ArenaProgressionState = ArenaProgressionState(),
    /** Null in V2 saves. First successful refresh creates an empty V3 tree and refunds all points. */
    val arenaSkillTree: ArenaSkillTreeState? = null,
    /** Null in legacy saves; the first successful local refresh grants eligible supports. */
    val arenaSupportOwnership: ArenaSupportOwnership? = null,
)

@Serializable
internal data class ArenaRankingPlacementSnapshot(
    val score: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val observedAtEpochMillis: Long,
)

internal fun captureArenaRankingPlacement(
    existing: ArenaRankingPlacementSnapshot?,
    completedBattles: Int,
    score: Int,
    wins: Int,
    losses: Int,
    draws: Int,
    observedAtEpochMillis: Long,
): ArenaRankingPlacementSnapshot? {
    if (existing != null || completedBattles != BATTLE_PLACEMENT_REQUIRED ||
        wins + losses + draws != BATTLE_PLACEMENT_REQUIRED || observedAtEpochMillis <= 0L
    ) return existing
    return ArenaRankingPlacementSnapshot(
        score = score.coerceAtLeast(0),
        wins = wins,
        losses = losses,
        draws = draws,
        observedAtEpochMillis = observedAtEpochMillis,
    )
}

internal fun ArenaRankingPlacementSnapshot.toArenaRankingStanding(
    characterId: String,
    displayName: String,
    heroClass: HeroClass,
    level: Long,
): ArenaRankingLocalStanding = ArenaRankingLocalStanding(
    characterId = characterId,
    displayName = displayName,
    heroClass = heroClass,
    level = level,
    score = score.coerceAtLeast(0),
    completedBattles = BATTLE_PLACEMENT_REQUIRED,
    wins = wins,
    losses = losses,
    draws = draws,
    observedAtEpochMillis = observedAtEpochMillis,
)

internal data class ArenaTrustedClockReading(
    val snapshot: BattleLocalSnapshot,
    val nowEpochMillis: Long,
    val epochDayUtc: Long,
    val serverAnchored: Boolean,
)

/**
 * Cheap foreground clock step for the arena UI. The one-second countdown advances only from the
 * monotonic clock and never writes the resulting timestamp by itself. [persistenceRequired] turns
 * true only when refreshing would change a durable ticket/day ledger.
 */
internal data class ArenaTicketUiClockStep(
    val snapshot: BattleLocalSnapshot,
    val nowEpochMillis: Long,
    val persistenceRequired: Boolean,
)

internal fun arenaScoreAchievedAtMillis(
    previousScore: Int,
    nextScore: Int,
    previousAchievedAtMillis: Long,
    trustedNowMillis: Long,
): Long {
    require(trustedNowMillis > 0L)
    return if (nextScore != previousScore || previousAchievedAtMillis <= 0L) {
        trustedNowMillis
    } else {
        previousAchievedAtMillis
    }
}

/**
 * Advances competitive time from a server response or same-boot elapsedRealtime. Roster expiry
 * only expires matchmaking data, not the trusted clock. After a reboot we resume from the last
 * accepted time without crediting the unverified gap; a fresh server response can fill that gap.
 * A device wall clock is consulted only for a save with no prior time evidence.
 */
internal fun advanceArenaTrustedClock(
    snapshot: BattleLocalSnapshot,
    roster: PublicPlayerRoster?,
    deviceWallNowMillis: Long,
    elapsedRealtimeMillis: Long,
    bootCount: Int,
): ArenaTrustedClockReading {
    require(deviceWallNowMillis >= 0L && elapsedRealtimeMillis >= 0L)
    val priorDay = listOf(
        snapshot.gameEpochDay,
        snapshot.dailyBattleDay,
        snapshot.rewardedRefillDay,
        snapshot.arenaProgression.growthDay,
    ).filter { it >= 0L }.maxOrNull() ?: Long.MIN_VALUE
    val priorDayStart = priorDay.takeIf { it >= 0L }?.let { day ->
        runCatching { Math.multiplyExact(day, ARENA_DAY_MILLIS) }.getOrNull()
    } ?: 0L
    val priorEpoch = maxOf(
        snapshot.arenaTrustedEpochMillis.coerceAtLeast(0L),
        snapshot.entryRecoveryStartedAtMillis.coerceAtLeast(0L),
        priorDayStart,
    )
    val serverNow = roster?.trustedServerNow(elapsedRealtimeMillis, bootCount)
    val sameBoot = snapshot.arenaTrustedBootCount >= 0 && bootCount >= 0 &&
        snapshot.arenaTrustedBootCount == bootCount &&
        snapshot.arenaTrustedElapsedRealtimeMillis in 0L..elapsedRealtimeMillis
    val localAdvance = if (sameBoot) {
        safeArenaClockAdd(
            priorEpoch,
            elapsedRealtimeMillis - snapshot.arenaTrustedElapsedRealtimeMillis,
        )
    } else null
    val candidate = when {
        serverNow != null -> maxOf(serverNow, localAdvance ?: priorEpoch)
        localAdvance != null -> localAdvance
        priorEpoch > 0L -> priorEpoch
        else -> deviceWallNowMillis
    }
    val acceptedNow = maxOf(priorEpoch, candidate)
    val acceptedDay = maxOf(priorDay, Math.floorDiv(acceptedNow, ARENA_DAY_MILLIS))
    // Re-anchor to the current boot without deriving any elapsed time from the wall clock.
    // Otherwise one reboot or a legacy save with no boot metadata would freeze recovery forever.
    val canMoveAnchor = bootCount >= 0
    val next = snapshot.copy(
        arenaTrustedEpochMillis = acceptedNow,
        arenaTrustedElapsedRealtimeMillis = if (canMoveAnchor) {
            elapsedRealtimeMillis
        } else {
            snapshot.arenaTrustedElapsedRealtimeMillis
        },
        arenaTrustedBootCount = if (canMoveAnchor) bootCount else snapshot.arenaTrustedBootCount,
        arenaClockServerAnchored = snapshot.arenaClockServerAnchored || serverNow != null,
        scoreAchievedAtMillis = when {
            snapshot.scoreAchievedAtMillis > 0L -> snapshot.scoreAchievedAtMillis
            snapshot.placementCompleted >= BATTLE_PLACEMENT_REQUIRED -> acceptedNow
            else -> 0L
        },
    )
    return ArenaTrustedClockReading(
        snapshot = next,
        nowEpochMillis = acceptedNow,
        epochDayUtc = acceptedDay,
        serverAnchored = next.arenaClockServerAnchored,
    )
}

internal fun refreshTrustedBattleEntrySnapshot(
    snapshot: BattleLocalSnapshot,
    roster: PublicPlayerRoster?,
    deviceWallNowMillis: Long,
    elapsedRealtimeMillis: Long,
    bootCount: Int,
): BattleLocalSnapshot {
    val clock = advanceArenaTrustedClock(
        snapshot,
        roster,
        deviceWallNowMillis,
        elapsedRealtimeMillis,
        bootCount,
    )
    return refreshBattleEntrySnapshot(
        snapshot = clock.snapshot,
        today = clock.epochDayUtc,
        nowMillis = clock.nowEpochMillis,
    )
}

internal fun advanceArenaTicketUiClock(
    snapshot: BattleLocalSnapshot,
    roster: PublicPlayerRoster?,
    elapsedRealtimeMillis: Long,
    bootCount: Int,
): ArenaTicketUiClockStep {
    val clock = advanceArenaTrustedClock(
        snapshot = snapshot,
        roster = roster,
        // Initial load/restore establishes the only wall-clock fallback. Foreground ticks must
        // never gain time from a later user edit to the device clock.
        deviceWallNowMillis = snapshot.arenaTrustedEpochMillis.coerceAtLeast(0L),
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        bootCount = bootCount,
    )
    val refreshed = refreshBattleEntrySnapshot(
        snapshot = clock.snapshot,
        today = clock.epochDayUtc,
        nowMillis = clock.nowEpochMillis,
    )
    return ArenaTicketUiClockStep(
        snapshot = clock.snapshot,
        nowEpochMillis = clock.nowEpochMillis,
        persistenceRequired = arenaTicketLedgerChanged(snapshot, refreshed),
    )
}

private fun arenaTicketLedgerChanged(
    previous: BattleLocalSnapshot,
    refreshed: BattleLocalSnapshot,
): Boolean =
    previous.gameEpochDay != refreshed.gameEpochDay ||
        previous.entriesRemaining != refreshed.entriesRemaining ||
        previous.entryRecoveryStartedAtMillis != refreshed.entryRecoveryStartedAtMillis ||
        previous.entryRecoveryPolicyVersion != refreshed.entryRecoveryPolicyVersion ||
        previous.dailyBattleDay != refreshed.dailyBattleDay ||
        previous.dailyBattlesUsed != refreshed.dailyBattlesUsed ||
        previous.rewardedRefillDay != refreshed.rewardedRefillDay ||
        previous.rewardedRefillsUsed != refreshed.rewardedRefillsUsed ||
        previous.lastRewardedRefillRequestId != refreshed.lastRewardedRefillRequestId ||
        previous.rewardedRefillRequestIds != refreshed.rewardedRefillRequestIds

private fun safeArenaClockAdd(left: Long, right: Long): Long? =
    runCatching { Math.addExact(left, right) }.getOrNull()

private const val ARENA_DAY_MILLIS = 24L * 60L * 60L * 1_000L

/** Time remaining until the stored arena ledger's next UTC day boundary. */
internal fun arenaDailyResetRemainingMillis(
    dailyBattleDay: Long,
    trustedNowMillis: Long,
): Long {
    if (dailyBattleDay < 0L) return 0L
    val nextDay = runCatching { Math.addExact(dailyBattleDay, 1L) }.getOrNull() ?: return 0L
    val resetAtMillis = runCatching { Math.multiplyExact(nextDay, ARENA_DAY_MILLIS) }
        .getOrNull() ?: return 0L
    val safeNow = trustedNowMillis.coerceAtLeast(0L)
    return if (safeNow >= resetAtMillis) 0L else resetAtMillis - safeNow
}

/** Migrates former recovery policies once, then applies one ticket per ten minutes. */
internal fun refreshBattleEntrySnapshot(
    snapshot: BattleLocalSnapshot,
    today: Long,
    nowMillis: Long,
): BattleLocalSnapshot {
    // Only V1-V4 receive the historical migration refill. V5 -> V6 clamps the smaller wallet
    // while preserving the active interval and today's consumed match/ad allowance.
    val legacyRecoveryPolicy = snapshot.entryRecoveryPolicyVersion < 5
    val recovered = if (legacyRecoveryPolicy) {
        refillBattleEntries()
    } else {
        recoverBattleEntries(
            remaining = snapshot.entriesRemaining,
            recoveryStartedAtMillis = snapshot.entryRecoveryStartedAtMillis,
            nowMillis = nowMillis,
        )
    }
    val nextRewardDay = if (today > snapshot.rewardedRefillDay) {
        today
    } else {
        snapshot.rewardedRefillDay
    }
    val rewardedIds = if (today > snapshot.rewardedRefillDay) {
        emptyList()
    } else {
        (snapshot.rewardedRefillRequestIds + snapshot.lastRewardedRefillRequestId)
            .filter(String::isNotBlank)
            .distinct()
            .takeLast(BATTLE_REWARDED_REFILL_DAILY_LIMIT)
    }
    val nextBattleDay = if (today > snapshot.dailyBattleDay) today else snapshot.dailyBattleDay
    return snapshot.copy(
        gameEpochDay = today,
        entriesRemaining = recovered.remaining,
        entryRecoveryStartedAtMillis = recovered.recoveryStartedAtMillis,
        entryRecoveryPolicyVersion = BATTLE_ENTRY_RECOVERY_POLICY_VERSION,
        dailyBattleDay = nextBattleDay,
        dailyBattlesUsed = battleDailyEntriesUsed(
            storedDay = snapshot.dailyBattleDay,
            storedCount = snapshot.dailyBattlesUsed,
            today = today,
        ),
        rewardedRefillDay = nextRewardDay,
        rewardedRefillsUsed = battleRewardedRefillsUsed(
            storedDay = snapshot.rewardedRefillDay,
            storedCount = snapshot.rewardedRefillsUsed,
            today = today,
        ),
        lastRewardedRefillRequestId = rewardedIds.lastOrNull().orEmpty(),
        rewardedRefillRequestIds = rewardedIds,
    )
}

internal data class BattleEntrySnapshotSpend(
    val accepted: Boolean,
    val snapshot: BattleLocalSnapshot,
)

/** Atomically enforces both wallet availability and the per-character daily match cap. */
internal fun spendBattleEntrySnapshot(
    snapshot: BattleLocalSnapshot,
    today: Long,
    nowMillis: Long,
): BattleEntrySnapshotSpend {
    val current = refreshBattleEntrySnapshot(snapshot, today, nowMillis)
    if (current.dailyBattlesUsed >= BATTLE_ENTRY_DAILY_LIMIT) {
        return BattleEntrySnapshotSpend(false, current)
    }
    val spent = spendBattleEntry(
        remaining = current.entriesRemaining,
        recoveryStartedAtMillis = current.entryRecoveryStartedAtMillis,
        nowMillis = nowMillis,
    )
    if (!spent.accepted) return BattleEntrySnapshotSpend(false, current)
    return BattleEntrySnapshotSpend(
        accepted = true,
        snapshot = current.copy(
            entriesRemaining = spent.state.remaining,
            entryRecoveryStartedAtMillis = spent.state.recoveryStartedAtMillis,
            dailyBattlesUsed = current.dailyBattlesUsed + 1,
        ),
    )
}

/** Applies one earned rewarded-ad refill exactly once and never charges a view that is already full. */
internal fun applyRewardedBattleEntryRefill(
    snapshot: BattleLocalSnapshot,
    today: Long,
    nowMillis: Long,
    requestId: String,
): BattleLocalSnapshot {
    val current = refreshBattleEntrySnapshot(snapshot, today, nowMillis)
    val refillTarget = battleRewardedRefillCount(current.dailyBattlesUsed)
    if (
        requestId.isBlank() ||
        requestId in current.rewardedRefillRequestIds ||
        current.entriesRemaining >= refillTarget ||
        current.dailyBattlesUsed >= BATTLE_ENTRY_DAILY_LIMIT ||
        current.rewardedRefillsUsed >= BATTLE_REWARDED_REFILL_DAILY_LIMIT
    ) {
        return current
    }
    val requestIds = (current.rewardedRefillRequestIds + requestId)
        .distinct()
        .takeLast(BATTLE_REWARDED_REFILL_DAILY_LIMIT)
    return current.copy(
        entriesRemaining = refillTarget,
        // Rewarded charging must not move or cancel the natural ten-minute interval.
        entryRecoveryStartedAtMillis = current.entryRecoveryStartedAtMillis,
        rewardedRefillDay = maxOf(current.rewardedRefillDay, today),
        rewardedRefillsUsed = current.rewardedRefillsUsed + 1,
        lastRewardedRefillRequestId = requestId,
        rewardedRefillRequestIds = requestIds,
    )
}

/**
 * Idempotent V2 -> V3 allocation migration. The old 144-trait allocation remains readable for
 * old battle records but is never guessed into a new node; arena XP and settlement fields stay in
 * [BattleLocalSnapshot.arenaProgression] byte-for-byte.
 */
internal fun initializeArenaSkillTree(
    snapshot: BattleLocalSnapshot,
    heroClass: HeroClass,
): BattleLocalSnapshot {
    val initialized = ArenaSkillTreeRules.initialize(snapshot.arenaSkillTree, heroClass)
    return if (initialized == snapshot.arenaSkillTree) snapshot else snapshot.copy(arenaSkillTree = initialized)
}

/**
 * Refunds only allocations that exceed the current hero-level budget or cannot belong to the
 * saved fighter. Legacy fixtures retain their old budget until migrated; tickets, scores,
 * battle records and the settlement ledger are never rewritten by allocation reconciliation.
 */
internal fun reconcileArenaSkillTreeForFighter(
    snapshot: BattleLocalSnapshot,
    heroClass: HeroClass,
    ownedAttackIds: Set<String>,
    heroLevel: Long? = null,
    allowLowLevelQa: Boolean = false,
): BattleLocalSnapshot {
    val initialized = initializeArenaSkillTree(snapshot, heroClass)
    val tree = requireNotNull(initialized.arenaSkillTree)
    val arenaLevel = if (initialized.arenaProgression.characterLevelPoints)
        ArenaCharacterPointRules.combatBudget(requireNotNull(heroLevel), allowLowLevelQa).coerceAtLeast(1)
        else ArenaProgressionRules.levelFromXp(initialized.arenaProgression.totalXp)
    if (ArenaSkillTreeRules.validate(tree, heroClass, arenaLevel, ownedAttackIds)) return initialized
    val reset = ArenaSkillTreeRules.reset(tree, heroClass, editingEnabled = true)
    require(reset.accepted) { "Invalid arena skill tree could not be reset" }
    return initialized.copy(arenaSkillTree = reset.state)
}

/**
 * A second tap can arrive before Compose disables a just-exhausted or just-maxed button. Those two
 * rejections are successful no-ops against the latest stored snapshot, not storage failures.
 */
internal fun applyArenaSkillTreeMutation(
    snapshot: BattleLocalSnapshot,
    mutation: ArenaSkillTreeMutation,
): BattleLocalSnapshot = when {
    mutation.accepted -> snapshot.copy(arenaSkillTree = mutation.state)
    isArenaSkillTreeBoundaryNoOp(mutation.error) -> snapshot
    else -> error("Arena skill-tree mutation rejected: ${mutation.error.orEmpty()}")
}

internal fun isArenaSkillTreeBoundaryNoOp(error: String?): Boolean =
    error == "point_budget" || error == "rank_max"

/** Only a previously locked, fully simulated result is evidence for crash recovery. */
internal fun recoverArenaProgression(snapshot: BattleLocalSnapshot): BattleLocalSnapshot {
    val pending = snapshot.arenaProgression.pending ?: return snapshot
    if (!ArenaProgressionRules.isValid(snapshot.arenaProgression) ||
        snapshot.history.none { it.battleId == pending.battleId }) return snapshot
    return snapshot.copy(arenaProgression =
        ArenaProgressionRules.completeBattle(snapshot.arenaProgression, pending.battleId))
}

/** Settlement guard: a battle may only consume the exact growth and V3 tree snapshots it issued. */
internal fun arenaBattleRevisionsMatch(
    snapshot: BattleLocalSnapshot,
    live: ArenaLiveBattle,
): Boolean = snapshot.arenaProgression.revision == live.progressionRevision &&
    (live.skillTreeRevision == null || snapshot.arenaSkillTree?.revision == live.skillTreeRevision)

/** Publish the new in-memory state only after durable storage acknowledged the commit. */
internal fun persistArenaProgression(
    snapshot: BattleLocalSnapshot,
    nextProgression: ArenaProgressionState,
    save: (BattleLocalSnapshot) -> Boolean,
): BattleLocalSnapshot? {
    if (!ArenaProgressionRules.isValid(nextProgression)) return null
    val next = snapshot.copy(arenaProgression = nextProgression).withArenaScoreFloor()
    next.arenaSkillTree?.let { tree ->
        val canonical = runCatching { ArenaSkillTreeRules.initialize(tree, tree.heroClass) }.getOrNull()
            ?: return null
        if (canonical != tree) return null
    }
    return next.takeIf { save(it) }
}

internal fun decodeBattleLocalSnapshot(raw: String?): BattleLocalSnapshot? =
    raw?.takeIf(String::isNotBlank)?.let {
        runCatching { BattleLocalStateStoreJson.decodeFromString<BattleLocalSnapshot>(it) }
            .getOrNull()
            ?.withArenaScoreFloor()
    }

internal fun encodeBattleLocalSnapshot(snapshot: BattleLocalSnapshot): String =
    BattleLocalStateStoreJson.encodeToString(snapshot.withArenaScoreFloor())

/** Repairs legacy or malformed local values before they can be displayed, persisted, or queued. */
internal fun BattleLocalSnapshot.withArenaScoreFloor(): BattleLocalSnapshot {
    val safeScore = score.coerceAtLeast(0)
    val safePlacement = arenaRankingPlacement?.let { placement ->
        if (placement.score >= 0) placement else placement.copy(score = 0)
    }
    return if (safeScore == score && safePlacement == arenaRankingPlacement) {
        this
    } else {
        copy(score = safeScore, arenaRankingPlacement = safePlacement)
    }
}

private val BattleLocalStateStoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal class BattleLocalStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "battle_v01_local_state",
        Context.MODE_PRIVATE,
    )

    fun load(identity: String): BattleLocalSnapshot = synchronized(lock) {
        val raw = preferences.getString(key(identity), null)
        if (raw == null) BattleLocalSnapshot()
        else requireNotNull(decodeBattleLocalSnapshot(raw)) { "Unreadable arena save" }
    }

    fun save(identity: String, snapshot: BattleLocalSnapshot): Boolean = synchronized(lock) {
        val previous = preferences.getString(key(identity), null)
        val saved = preferences.edit()
            .putString(key(identity), encodeBattleLocalSnapshot(snapshot.withArenaScoreFloor()))
            .commit()
        // SharedPreferences also updates its memory map on a failed disk commit. Restore it so
        // a retry cannot read an unacknowledged XP grant or allocation as already completed.
        if (!saved) preferences.edit().putString(key(identity), previous).commit()
        saved
    }

    fun update(identity: String, transform: (BattleLocalSnapshot) -> BattleLocalSnapshot): BattleLocalSnapshot? =
        synchronized(lock) {
            runCatching {
                val current = load(identity)
                val next = transform(current).withArenaScoreFloor()
                if (next == current) next else persistArenaProgression(next, next.arenaProgression) {
                    save(identity, it)
                }
            }.getOrNull()
        }

    fun refreshBattleEntries(
        identity: String,
        today: Long,
        nowMillis: Long,
    ): BattleLocalSnapshot? = update(identity) { stored ->
        refreshBattleEntrySnapshot(stored, today, nowMillis)
    }

    fun refreshBattleEntriesTrusted(
        identity: String,
        roster: PublicPlayerRoster?,
        deviceWallNowMillis: Long,
        elapsedRealtimeMillis: Long,
        bootCount: Int,
    ): BattleLocalSnapshot? = update(identity) { stored ->
        refreshTrustedBattleEntrySnapshot(
            stored,
            roster,
            deviceWallNowMillis,
            elapsedRealtimeMillis,
            bootCount,
        )
    }

    fun applyRewardedBattleEntryRefill(
        identity: String,
        today: Long,
        nowMillis: Long,
        requestId: String,
    ): BattleLocalSnapshot? = update(identity) { stored ->
        applyRewardedBattleEntryRefill(stored, today, nowMillis, requestId)
    }

    fun applyRewardedBattleEntryRefillTrusted(
        identity: String,
        roster: PublicPlayerRoster?,
        deviceWallNowMillis: Long,
        elapsedRealtimeMillis: Long,
        bootCount: Int,
        requestId: String,
    ): BattleLocalSnapshot? = update(identity) { stored ->
        val current = refreshTrustedBattleEntrySnapshot(
            stored,
            roster,
            deviceWallNowMillis,
            elapsedRealtimeMillis,
            bootCount,
        )
        applyRewardedBattleEntryRefill(
            current,
            current.gameEpochDay,
            current.arenaTrustedEpochMillis,
            requestId,
        )
    }

    /** Rechecks eligibility immediately before an ad starts so an already-finished refill cannot waste it. */
    fun rewardedBattleEntryRefillAvailable(
        identity: String,
        today: Long,
        nowMillis: Long,
    ): Boolean = refreshBattleEntries(identity, today, nowMillis)?.let { snapshot ->
        battleRewardedRefillAvailable(
            entriesRemaining = snapshot.entriesRemaining,
            refillsUsed = snapshot.rewardedRefillsUsed,
            dailyBattlesUsed = snapshot.dailyBattlesUsed,
        )
    } == true

    fun rewardedBattleEntryRefillAvailableTrusted(
        identity: String,
        roster: PublicPlayerRoster?,
        deviceWallNowMillis: Long,
        elapsedRealtimeMillis: Long,
        bootCount: Int,
    ): Boolean = refreshBattleEntriesTrusted(
        identity,
        roster,
        deviceWallNowMillis,
        elapsedRealtimeMillis,
        bootCount,
    )?.let { snapshot ->
        battleRewardedRefillAvailable(
            entriesRemaining = snapshot.entriesRemaining,
            refillsUsed = snapshot.rewardedRefillsUsed,
            dailyBattlesUsed = snapshot.dailyBattlesUsed,
        )
    } == true

    private fun key(identity: String): String = "hero.$identity"

    private companion object { val lock = Any() }
}

internal fun currentArenaBootCount(context: Context): Int =
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
