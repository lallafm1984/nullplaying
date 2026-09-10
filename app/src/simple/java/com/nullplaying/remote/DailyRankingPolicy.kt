package com.nullplaying.remote

import com.nullplaying.model.HeroClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val DAILY_RANKING_RETRY_MILLIS = 60_000L

internal data class DailyRankingRequestIdentity(val generation: Long, val userId: String?)

/** Identity invalidation and response publication share the cache's existing monitor. */
internal class DailyRankingResponseGate(private val monitor: Any) {
    private var generation = 0L

    fun capture(userId: String?): DailyRankingRequestIdentity = synchronized(monitor) {
        DailyRankingRequestIdentity(generation, userId)
    }

    fun invalidate(clear: () -> Unit) = synchronized(monitor) {
        generation += 1L
        clear()
    }

    fun <T> withCurrentIdentity(
        requestIdentity: DailyRankingRequestIdentity,
        currentUserId: () -> String?,
        publish: () -> T,
    ): T? = synchronized(monitor) {
        if (requestIdentity != DailyRankingRequestIdentity(generation, currentUserId())) null else publish()
    }
}

/** A forced UI refresh must not download the already published settlement again. */
internal fun isDailyRankingCacheReusable(
    snapshot: RemoteRankingSnapshot?,
    serverNowEpochMillis: Long,
    @Suppress("UNUSED_PARAMETER") forceRefresh: Boolean = false,
    refreshPolicy: RankingRefreshPolicy? = null,
): Boolean {
    if (snapshot == null || !snapshot.hasDailySettlement() ||
        serverNowEpochMillis < snapshot.settledAtEpochMillis
    ) return false
    val serverRefreshAt = maxOf(snapshot.nextSettlementAtEpochMillis, snapshot.nextCheckAtEpochMillis)
    val refreshAt = refreshPolicy?.let { policy ->
        maxOf(
            serverRefreshAt,
            earliestRankingRefreshAt(
                settledAtEpochMillis = snapshot.settledAtEpochMillis,
                serverNextSettlementAtEpochMillis = snapshot.nextSettlementAtEpochMillis,
                policy = policy,
            ),
        )
    } ?: serverRefreshAt
    return serverNowEpochMillis < refreshAt
}

internal fun RemoteRankingSnapshot.hasDailySettlement(): Boolean =
    snapshotId.isNotBlank() && settledAtEpochMillis > 0L &&
        nextSettlementAtEpochMillis > settledAtEpochMillis && generatedAtEpochMillis > 0L

internal fun RemoteRankingSnapshot.forCharacter(characterId: String): RemoteRankingSnapshot {
    val remapped = entries.map { it.copy(isMe = it.characterId == characterId) }
    val remappedOwn = ownEntries.map { it.copy(isMe = it.characterId == characterId) }
    return copy(
        requestedCharacterId = characterId,
        entries = remapped,
        ownEntries = remappedOwn,
        myEntry = remappedOwn.singleOrNull { it.isMe } ?: remapped.singleOrNull { it.isMe },
    )
}

/**
 * Removes retired system gatekeepers at the network/cache boundary. Old immutable snapshots and
 * already persisted caches can still contain them, so close their rank/list gaps before any caller
 * observes or stores the snapshot. The source rows remain untouched on the server.
 */
internal fun RemoteRankingSnapshot.withoutSystemRankingEntries(): RemoteRankingSnapshot {
    val systemEntries = (entries + ownEntries + listOfNotNull(myEntry))
        .filter { it.systemEntryCode != null }
        .distinctBy(RemoteRankingEntry::characterId)
        .toList()
    if (systemEntries.isEmpty()) return this

    fun normalize(entry: RemoteRankingEntry): RemoteRankingEntry? {
        if (entry.systemEntryCode != null) return null
        val removedRanksAhead = systemEntries.count { it.rank > 0 && it.rank < entry.rank }
        val removedRowsAhead = systemEntries.count {
            it.listIndex >= 0 && it.listIndex < entry.listIndex
        }
        return entry.copy(
            rank = if (entry.rank > 0) {
                (entry.rank - removedRanksAhead).coerceAtLeast(1)
            } else {
                entry.rank
            },
            listIndex = if (entry.listIndex >= 0) {
                (entry.listIndex - removedRowsAhead).coerceAtLeast(0)
            } else {
                entry.listIndex
            },
            systemEntryCode = null,
        )
    }

    val visibleEntries = entries.mapNotNull(::normalize)
    val visibleOwnEntries = ownEntries.mapNotNull(::normalize)
    val visibleMyEntry = visibleOwnEntries.singleOrNull {
        it.characterId == requestedCharacterId
    } ?: visibleEntries.singleOrNull {
        it.characterId == requestedCharacterId
    } ?: myEntry?.let(::normalize)?.takeIf {
        it.characterId == requestedCharacterId
    }
    return copy(
        totalParticipants = (totalParticipants - systemEntries.size)
            .coerceAtLeast(visibleEntries.size),
        entries = visibleEntries,
        ownEntries = visibleOwnEntries,
        myEntry = visibleMyEntry,
    )
}

/** The monotonic anchor prevents device clock edits from extending a live process's cache. */
internal data class DailyRankingClock(
    val serverNowEpochMillis: Long,
    val observedElapsedRealtimeMillis: Long,
) {
    fun now(elapsedRealtimeMillis: Long): Long {
        val safeServerNow = serverNowEpochMillis.coerceAtLeast(0L)
        val safeElapsedNow = elapsedRealtimeMillis.coerceAtLeast(0L)
        val safeObservedElapsed = observedElapsedRealtimeMillis.coerceAtLeast(0L)
        val elapsedDelta = if (safeElapsedNow >= safeObservedElapsed) {
            safeElapsedNow - safeObservedElapsed
        } else 0L
        return saturatingAddRankingTime(safeServerNow, elapsedDelta)
    }
}

/**
 * Resolves ranking time without trusting an editable wall clock when the application's shared
 * monotonic clock is available. The saved offset is a compatibility fallback for older/isolated
 * callers that do not inject that clock.
 */
internal fun resolveRankingNowEpochMillis(
    processClock: DailyRankingClock?,
    elapsedRealtimeMillis: Long,
    trustedGameEpochMillis: Long?,
    wallClockEpochMillis: Long,
    savedServerOffsetMillis: Long,
): Long = processClock?.now(elapsedRealtimeMillis)
    ?: trustedGameEpochMillis?.takeIf { it >= 0L }
    ?: saturatingAddRankingTime(wallClockEpochMillis, savedServerOffsetMillis)

@Serializable
internal data class DailyLeaderboardResponse(
    @SerialName("snapshot_id") val snapshotId: String,
    @SerialName("settled_at") val settledAtEpochMillis: Long,
    @SerialName("next_settlement_at") val nextSettlementAtEpochMillis: Long,
    @SerialName("generated_at") val generatedAtEpochMillis: Long,
    @SerialName("server_now") val serverNowEpochMillis: Long,
    val unchanged: Boolean = false,
    @SerialName("t") val totalParticipants: Int = 0,
    @SerialName("e") val entries: List<CompactLeaderboardRow> = emptyList(),
    @SerialName("o") val ownEntries: List<CompactLeaderboardRow> = emptyList(),
)

internal fun applyDailyRankingResponse(
    response: DailyLeaderboardResponse,
    cachedSnapshot: RemoteRankingSnapshot?,
    characterId: String,
    receivedAtEpochMillis: Long,
): RemoteRankingSnapshot {
    require(response.snapshotId.isNotBlank() && response.settledAtEpochMillis > 0L &&
        response.nextSettlementAtEpochMillis > response.settledAtEpochMillis &&
        response.generatedAtEpochMillis > 0L && response.serverNowEpochMillis > 0L) {
        "랭킹 정산 정보가 올바르지 않습니다"
    }
    val sameSnapshot = cachedSnapshot?.takeIf {
        it.hasDailySettlement() && it.snapshotId == response.snapshotId
    }
    require(!response.unchanged || sameSnapshot != null) { "저장된 랭킹 정산본이 없습니다" }
    // An unchanged response is only metadata. It must preserve every row and the first fetch time.
    val snapshot = sameSnapshot?.forCharacter(characterId) ?: RemoteRankingSnapshot(
        requestedCharacterId = characterId,
        fetchedAtEpochMillis = receivedAtEpochMillis,
        totalParticipants = response.totalParticipants,
        entries = response.entries.map { it.toRemote(characterId) }
            .filter { it.rank in 1..1_000 }
            .distinctBy(RemoteRankingEntry::characterId),
        myEntry = null,
        ownEntries = response.ownEntries.map { it.toRemote(characterId) }
            .distinctBy(RemoteRankingEntry::characterId),
    ).forCharacter(characterId)
    return snapshot.copy(
        snapshotId = response.snapshotId,
        settledAtEpochMillis = response.settledAtEpochMillis,
        nextSettlementAtEpochMillis = response.nextSettlementAtEpochMillis,
        generatedAtEpochMillis = response.generatedAtEpochMillis,
        serverTimeOffsetMillis = saturatingSubtractRankingTime(
            response.serverNowEpochMillis,
            receivedAtEpochMillis,
        ),
        // During settlement delay, check the version again in one minute, never pull full rows.
        nextCheckAtEpochMillis = if (response.serverNowEpochMillis >= response.nextSettlementAtEpochMillis) {
            saturatingAddRankingTime(response.serverNowEpochMillis, DAILY_RANKING_RETRY_MILLIS)
        } else 0L,
        isFromCache = sameSnapshot != null,
    ).withoutSystemRankingEntries()
}

@Serializable
internal data class CompactLeaderboardRow(
    @SerialName("r") val rankNumber: Long,
    @SerialName("i") val listIndex: Long,
    @SerialName("c") val characterId: String,
    @SerialName("n") val displayName: String,
    @SerialName("h") val heroClass: String,
    @SerialName("l") val level: Long,
    @SerialName("p") val combatPower: Long,
    @SerialName("a") val achievedAtEpochMillis: Long,
    @SerialName("s") val systemEntryCode: String? = null,
) {
    fun toRemote(currentCharacterId: String) = RemoteRankingEntry(
        rank = rankNumber.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        listIndex = listIndex.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        characterId = characterId,
        displayName = displayName,
        heroClass = runCatching { HeroClass.valueOf(heroClass) }.getOrDefault(HeroClass.WARRIOR),
        level = level,
        combatPower = combatPower,
        achievedAtEpochMillis = achievedAtEpochMillis,
        updatedAtEpochMillis = achievedAtEpochMillis,
        isMe = characterId == currentCharacterId,
        systemEntryCode = systemEntryCode,
    )

    companion object {
        fun fromRemote(entry: RemoteRankingEntry) = CompactLeaderboardRow(
            rankNumber = entry.rank.toLong(),
            listIndex = entry.listIndex.toLong(),
            characterId = entry.characterId,
            displayName = entry.displayName,
            heroClass = entry.heroClass.name,
            level = entry.level,
            combatPower = entry.combatPower,
            achievedAtEpochMillis = entry.achievedAtEpochMillis,
            systemEntryCode = entry.systemEntryCode,
        )
    }
}

@Serializable
internal data class PersistedRankingCache(
    val fetchedAtEpochMillis: Long,
    val totalParticipants: Int,
    val entries: List<CompactLeaderboardRow>,
    val snapshotId: String = "",
    val settledAtEpochMillis: Long = 0L,
    val nextSettlementAtEpochMillis: Long = 0L,
    val generatedAtEpochMillis: Long = 0L,
    val ownEntries: List<CompactLeaderboardRow> = emptyList(),
    val serverTimeOffsetMillis: Long = 0L,
    val nextCheckAtEpochMillis: Long = 0L,
) {
    fun toSnapshot(characterId: String): RemoteRankingSnapshot? = RemoteRankingSnapshot(
        requestedCharacterId = characterId,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        totalParticipants = totalParticipants,
        entries = entries.map { it.toRemote(characterId) },
        myEntry = null,
        isFromCache = true,
        snapshotId = snapshotId,
        settledAtEpochMillis = settledAtEpochMillis,
        nextSettlementAtEpochMillis = nextSettlementAtEpochMillis,
        generatedAtEpochMillis = generatedAtEpochMillis,
        ownEntries = ownEntries.map { it.toRemote(characterId) },
        serverTimeOffsetMillis = serverTimeOffsetMillis,
        nextCheckAtEpochMillis = nextCheckAtEpochMillis,
    ).takeIf { it.hasDailySettlement() }
        ?.forCharacter(characterId)
        ?.withoutSystemRankingEntries()

    companion object {
        fun fromSnapshot(snapshot: RemoteRankingSnapshot): PersistedRankingCache {
            val visible = snapshot.withoutSystemRankingEntries()
            return PersistedRankingCache(
                fetchedAtEpochMillis = visible.fetchedAtEpochMillis,
                totalParticipants = visible.totalParticipants,
                entries = visible.entries.map { CompactLeaderboardRow.fromRemote(it) },
                snapshotId = visible.snapshotId,
                settledAtEpochMillis = visible.settledAtEpochMillis,
                nextSettlementAtEpochMillis = visible.nextSettlementAtEpochMillis,
                generatedAtEpochMillis = visible.generatedAtEpochMillis,
                ownEntries = visible.ownEntries.map { CompactLeaderboardRow.fromRemote(it) },
                serverTimeOffsetMillis = visible.serverTimeOffsetMillis,
                nextCheckAtEpochMillis = visible.nextCheckAtEpochMillis,
            )
        }
    }
}
