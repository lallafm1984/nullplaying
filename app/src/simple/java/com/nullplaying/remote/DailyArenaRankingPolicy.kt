package com.nullplaying.remote

import com.nullplaying.model.HeroClass
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val ARENA_RANKING_PLACEMENT_REQUIRED = 10
internal const val ARENA_RANKING_SEASON_ID = "1"
internal const val ARENA_RANKING_START_SCORE = 1_000
internal const val ARENA_RANKING_MAX_SCORE_DELTA_PER_BATTLE = 24
internal const val ARENA_RANKING_MAX_BATTLES = 1_000_000
internal const val ARENA_RANKING_MAX_SCORE = 25_000_000
internal const val MAX_DISPLAYED_ARENA_RANK = 1_000
internal const val OUTSIDE_DISPLAYED_ARENA_RANK = MAX_DISPLAYED_ARENA_RANK + 1

@Serializable
data class ArenaRankingLocalStanding(
    val characterId: String,
    val displayName: String,
    val heroClass: HeroClass,
    val level: Long,
    val score: Int,
    val completedBattles: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    /** Trusted arena observation time for persistence and upload validation; never used to rerank the UI. */
    val observedAtEpochMillis: Long,
)

data class RemoteArenaRankingEntry(
    val rank: Int,
    val listIndex: Int,
    val accountId: String,
    val characterId: String,
    val displayName: String,
    val heroClass: HeroClass,
    val level: Long,
    val score: Int,
    val completedBattles: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val achievedAtEpochMillis: Long,
    val isMe: Boolean,
    val isProvisional: Boolean = false,
)

data class RemoteArenaRankingSnapshot(
    val requestedCharacterId: String,
    val fetchedAtEpochMillis: Long,
    val totalParticipants: Int,
    val entries: List<RemoteArenaRankingEntry>,
    val ownEntries: List<RemoteArenaRankingEntry>,
    val snapshotId: String,
    val seasonId: String,
    val settledAtEpochMillis: Long,
    val nextSettlementAtEpochMillis: Long,
    val generatedAtEpochMillis: Long,
    val rulesVersion: Int,
    val serverTimeOffsetMillis: Long,
    val nextCheckAtEpochMillis: Long = 0L,
    val isBootstrap: Boolean = false,
    val isFromCache: Boolean = false,
) {
    val myEntry: RemoteArenaRankingEntry?
        get() = ownEntries.singleOrNull { it.isMe }
}

@Serializable
internal data class DailyArenaLeaderboardResponse(
    @SerialName("snapshot_id") val snapshotId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("settled_at") val settledAtEpochMillis: Long,
    @SerialName("next_settlement_at") val nextSettlementAtEpochMillis: Long,
    @SerialName("generated_at") val generatedAtEpochMillis: Long,
    @SerialName("server_now") val serverNowEpochMillis: Long,
    @SerialName("rules_version") val rulesVersion: Int,
    @SerialName("is_bootstrap") val isBootstrap: Boolean = false,
    val unchanged: Boolean = false,
    @SerialName("t") val totalParticipants: Int = 0,
    @SerialName("e") val entries: List<CompactArenaLeaderboardRow> = emptyList(),
    @SerialName("o") val ownEntries: List<CompactArenaLeaderboardRow> = emptyList(),
)

@Serializable
internal data class CompactArenaLeaderboardRow(
    @SerialName("r") val rankNumber: Long,
    @SerialName("i") val listIndex: Long,
    @SerialName("u") val accountId: String,
    @SerialName("c") val characterId: String,
    @SerialName("n") val displayName: String,
    @SerialName("h") val heroClass: String,
    @SerialName("l") val level: Long,
    @SerialName("p") val score: Long,
    @SerialName("b") val completedBattles: Long,
    @SerialName("w") val wins: Long,
    @SerialName("x") val losses: Long,
    @SerialName("d") val draws: Long,
    @SerialName("a") val achievedAtEpochMillis: Long,
) {
    fun toRemote(currentCharacterId: String, belongsToCurrentAccount: Boolean): RemoteArenaRankingEntry = RemoteArenaRankingEntry(
        rank = rankNumber.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        listIndex = listIndex.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        accountId = accountId,
        characterId = characterId,
        displayName = displayName,
        heroClass = runCatching { HeroClass.valueOf(heroClass) }.getOrDefault(HeroClass.WARRIOR),
        level = level,
        score = score.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        completedBattles = completedBattles.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        wins = wins.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        losses = losses.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        draws = draws.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        achievedAtEpochMillis = achievedAtEpochMillis,
        isMe = belongsToCurrentAccount && characterId == currentCharacterId,
    )

    companion object {
        fun fromRemote(entry: RemoteArenaRankingEntry) = CompactArenaLeaderboardRow(
            rankNumber = entry.rank.toLong(),
            listIndex = entry.listIndex.toLong(),
            accountId = entry.accountId,
            characterId = entry.characterId,
            displayName = entry.displayName,
            heroClass = entry.heroClass.name,
            level = entry.level,
            score = entry.score.coerceAtLeast(0).toLong(),
            completedBattles = entry.completedBattles.toLong(),
            wins = entry.wins.toLong(),
            losses = entry.losses.toLong(),
            draws = entry.draws.toLong(),
            achievedAtEpochMillis = entry.achievedAtEpochMillis,
        )
    }
}

@Serializable
internal data class PersistedArenaRankingCache(
    val fetchedAtEpochMillis: Long,
    val totalParticipants: Int,
    val entries: List<CompactArenaLeaderboardRow>,
    val ownEntries: List<CompactArenaLeaderboardRow>,
    val snapshotId: String,
    val seasonId: String,
    val settledAtEpochMillis: Long,
    val nextSettlementAtEpochMillis: Long,
    val generatedAtEpochMillis: Long,
    val rulesVersion: Int,
    val serverTimeOffsetMillis: Long,
    val nextCheckAtEpochMillis: Long = 0L,
    val isBootstrap: Boolean = false,
) {
    fun toSnapshot(characterId: String): RemoteArenaRankingSnapshot? =
        RemoteArenaRankingSnapshot(
            requestedCharacterId = characterId,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            totalParticipants = totalParticipants,
            entries = entries.map { it.toRemote(characterId, belongsToCurrentAccount = false) },
            ownEntries = ownEntries.map { it.toRemote(characterId, belongsToCurrentAccount = true) },
            snapshotId = snapshotId,
            seasonId = seasonId,
            settledAtEpochMillis = settledAtEpochMillis,
            nextSettlementAtEpochMillis = nextSettlementAtEpochMillis,
            generatedAtEpochMillis = generatedAtEpochMillis,
            rulesVersion = rulesVersion,
            serverTimeOffsetMillis = serverTimeOffsetMillis,
            nextCheckAtEpochMillis = nextCheckAtEpochMillis,
            isBootstrap = isBootstrap,
            isFromCache = true,
        ).takeIf(RemoteArenaRankingSnapshot::hasDailySettlement)

    companion object {
        fun fromSnapshot(snapshot: RemoteArenaRankingSnapshot) = PersistedArenaRankingCache(
            fetchedAtEpochMillis = snapshot.fetchedAtEpochMillis,
            totalParticipants = snapshot.totalParticipants,
            entries = snapshot.entries.map(CompactArenaLeaderboardRow::fromRemote),
            ownEntries = snapshot.ownEntries.map(CompactArenaLeaderboardRow::fromRemote),
            snapshotId = snapshot.snapshotId,
            seasonId = snapshot.seasonId,
            settledAtEpochMillis = snapshot.settledAtEpochMillis,
            nextSettlementAtEpochMillis = snapshot.nextSettlementAtEpochMillis,
            generatedAtEpochMillis = snapshot.generatedAtEpochMillis,
            rulesVersion = snapshot.rulesVersion,
            serverTimeOffsetMillis = snapshot.serverTimeOffsetMillis,
            nextCheckAtEpochMillis = snapshot.nextCheckAtEpochMillis,
            isBootstrap = snapshot.isBootstrap,
        )
    }
}

internal fun RemoteArenaRankingSnapshot.hasDailySettlement(): Boolean =
    snapshotId.isNotBlank() && seasonId == ARENA_RANKING_SEASON_ID && rulesVersion == 1 && settledAtEpochMillis > 0L &&
        nextSettlementAtEpochMillis > settledAtEpochMillis && generatedAtEpochMillis > 0L

internal fun RemoteArenaRankingSnapshot.forCharacter(characterId: String): RemoteArenaRankingSnapshot = copy(
    requestedCharacterId = characterId,
    entries = entries.map { it.copy(isMe = false) },
    ownEntries = ownEntries.map { it.copy(isMe = it.characterId == characterId) },
)

internal fun isDailyArenaRankingCacheReusable(
    snapshot: RemoteArenaRankingSnapshot?,
    serverNowEpochMillis: Long,
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

internal fun dailyArenaRankingRefreshDelayMillis(
    snapshot: RemoteArenaRankingSnapshot,
    serverNowEpochMillis: Long,
    refreshPolicy: RankingRefreshPolicy? = null,
): Long? {
    if (!snapshot.hasDailySettlement() || serverNowEpochMillis <= 0L) return null
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
    return if (serverNowEpochMillis >= refreshAt) 0L else refreshAt - serverNowEpochMillis
}

internal fun applyDailyArenaRankingResponse(
    response: DailyArenaLeaderboardResponse,
    cachedSnapshot: RemoteArenaRankingSnapshot?,
    characterId: String,
    receivedAtEpochMillis: Long,
): RemoteArenaRankingSnapshot {
    require(response.snapshotId.isNotBlank() && response.seasonId == ARENA_RANKING_SEASON_ID &&
        response.rulesVersion == 1 &&
        response.settledAtEpochMillis > 0L &&
        response.nextSettlementAtEpochMillis > response.settledAtEpochMillis &&
        response.generatedAtEpochMillis > 0L && response.serverNowEpochMillis > 0L) {
        "결투장 랭킹 정산 정보가 올바르지 않습니다"
    }
    val sameSnapshot = cachedSnapshot?.takeIf {
        it.hasDailySettlement() && it.snapshotId == response.snapshotId && it.seasonId == response.seasonId
    }
    require(!response.unchanged || sameSnapshot != null) { "저장된 결투장 랭킹 정산본이 없습니다" }
    val snapshot = sameSnapshot?.forCharacter(characterId) ?: RemoteArenaRankingSnapshot(
        requestedCharacterId = characterId,
        fetchedAtEpochMillis = receivedAtEpochMillis,
        totalParticipants = response.totalParticipants.coerceAtLeast(0),
        entries = response.entries.map { it.toRemote(characterId, belongsToCurrentAccount = false) }
            .filter { it.rank in 1..MAX_DISPLAYED_ARENA_RANK }
            .distinctBy { it.accountId to it.characterId },
        ownEntries = response.ownEntries.map { it.toRemote(characterId, belongsToCurrentAccount = true) }
            .distinctBy { it.accountId to it.characterId },
        snapshotId = response.snapshotId,
        seasonId = response.seasonId,
        settledAtEpochMillis = response.settledAtEpochMillis,
        nextSettlementAtEpochMillis = response.nextSettlementAtEpochMillis,
        generatedAtEpochMillis = response.generatedAtEpochMillis,
        rulesVersion = response.rulesVersion,
        serverTimeOffsetMillis = saturatingSubtractRankingTime(
            response.serverNowEpochMillis,
            receivedAtEpochMillis,
        ),
        isBootstrap = response.isBootstrap,
    ).forCharacter(characterId)
    return snapshot.copy(
        settledAtEpochMillis = response.settledAtEpochMillis,
        nextSettlementAtEpochMillis = response.nextSettlementAtEpochMillis,
        generatedAtEpochMillis = response.generatedAtEpochMillis,
        rulesVersion = response.rulesVersion,
        serverTimeOffsetMillis = saturatingSubtractRankingTime(
            response.serverNowEpochMillis,
            receivedAtEpochMillis,
        ),
        nextCheckAtEpochMillis = if (response.serverNowEpochMillis >= response.nextSettlementAtEpochMillis) {
            saturatingAddRankingTime(response.serverNowEpochMillis, DAILY_RANKING_RETRY_MILLIS)
        } else 0L,
        isBootstrap = response.isBootstrap,
        isFromCache = sameSnapshot != null,
    )
}

internal fun isValidArenaRankingStanding(standing: ArenaRankingLocalStanding): Boolean {
    val record = standing.wins.toLong() + standing.losses.toLong() + standing.draws.toLong()
    val canonicalCharacterId = runCatching { UUID.fromString(standing.characterId) }.getOrNull()
    val completed = standing.completedBattles.toLong()
    val minimumScore = maxOf(
        0L,
        ARENA_RANKING_START_SCORE.toLong() -
            ARENA_RANKING_MAX_SCORE_DELTA_PER_BATTLE.toLong() * completed,
    )
    val maximumScore = ARENA_RANKING_START_SCORE.toLong() +
        ARENA_RANKING_MAX_SCORE_DELTA_PER_BATTLE.toLong() * completed
    return canonicalCharacterId?.toString()?.equals(standing.characterId, ignoreCase = true) == true &&
        standing.displayName.isNotBlank() &&
        standing.level in 10L..MAX_SUPPORTED_RANKING_LEVEL &&
        standing.score in 0..ARENA_RANKING_MAX_SCORE &&
        standing.score.toLong() in minimumScore..maximumScore &&
        standing.completedBattles in ARENA_RANKING_PLACEMENT_REQUIRED..ARENA_RANKING_MAX_BATTLES &&
        standing.wins in 0..ARENA_RANKING_MAX_BATTLES &&
        standing.losses in 0..ARENA_RANKING_MAX_BATTLES &&
        standing.draws in 0..ARENA_RANKING_MAX_BATTLES &&
        record == standing.completedBattles.toLong() && standing.observedAtEpochMillis > 0L
}

internal data class ArenaRankingDisplaySnapshot(
    val snapshotId: String,
    val settledAtEpochMillis: Long,
    val nextSettlementAtEpochMillis: Long,
    val totalParticipants: Int,
    val entries: List<RemoteArenaRankingEntry>,
    val myEntry: RemoteArenaRankingEntry?,
)

private val arenaRankingDisplayOrder =
    compareByDescending<RemoteArenaRankingEntry> { it.score }
        .thenByDescending { it.wins }
        .thenBy { it.losses }
        .thenByDescending { it.draws }
        .thenBy { it.achievedAtEpochMillis }
        .thenBy { it.characterId }
        .thenBy { it.accountId }

private val arenaRankingBandOrder =
    compareByDescending<RemoteArenaRankingEntry> { it.score }
        .thenByDescending { it.wins }
        .thenBy { it.losses }
        .thenByDescending { it.draws }

internal fun rankArenaRankingCandidates(
    candidates: List<RemoteArenaRankingEntry>,
): List<RemoteArenaRankingEntry> {
    val sorted = candidates.sortedWith(arenaRankingDisplayOrder)
    var previous: RemoteArenaRankingEntry? = null
    var previousRank = 0
    return sorted.mapIndexed { index, candidate ->
        val rank = if (previous != null && arenaRankingBandOrder.compare(previous, candidate) == 0) {
            previousRank
        } else {
            index + 1
        }
        previous = candidate
        previousRank = rank
        candidate.copy(rank = rank, listIndex = index)
    }
}

/**
 * The server snapshot supplies the comparison field. The active character's latest local standing
 * replaces its settled row so the page moves immediately after a battle, like the adventurer
 * ranking. A rank is only stated when the downloaded field proves it; a locally weaker character
 * below a truncated field uses the outside sentinel instead of inventing an exact place.
 */
internal fun buildArenaRankingDisplaySnapshot(
    snapshot: RemoteArenaRankingSnapshot,
    local: ArenaRankingLocalStanding,
): ArenaRankingDisplaySnapshot? {
    if (!snapshot.hasDailySettlement() || snapshot.requestedCharacterId != local.characterId) return null
    val settledMine = snapshot.ownEntries.firstOrNull { it.characterId == local.characterId }
        ?: snapshot.entries.firstOrNull { it.characterId == local.characterId }
    if (!isValidArenaRankingStanding(local)) {
        return ArenaRankingDisplaySnapshot(
            snapshotId = snapshot.snapshotId,
            settledAtEpochMillis = snapshot.settledAtEpochMillis,
            nextSettlementAtEpochMillis = snapshot.nextSettlementAtEpochMillis,
            totalParticipants = snapshot.totalParticipants,
            entries = snapshot.entries,
            myEntry = snapshot.myEntry,
        )
    }

    val rankingChanged = settledMine == null ||
        settledMine.score != local.score ||
        settledMine.completedBattles != local.completedBattles ||
        settledMine.wins != local.wins ||
        settledMine.losses != local.losses ||
        settledMine.draws != local.draws
    val localEntry = RemoteArenaRankingEntry(
        rank = 0,
        listIndex = -1,
        accountId = settledMine?.accountId ?: local.characterId,
        characterId = local.characterId,
        displayName = local.displayName,
        heroClass = local.heroClass,
        level = local.level,
        score = local.score,
        completedBattles = local.completedBattles,
        wins = local.wins,
        losses = local.losses,
        draws = local.draws,
        achievedAtEpochMillis = if (rankingChanged) {
            local.observedAtEpochMillis
        } else {
            requireNotNull(settledMine).achievedAtEpochMillis
        },
        isMe = true,
        isProvisional = rankingChanged,
    )
    val external = snapshot.entries
        .filterNot { it.characterId == local.characterId }
        .distinctBy { it.accountId to it.characterId }
        .map { it.copy(isMe = false, isProvisional = false) }
    val ranked = rankArenaRankingCandidates(external + localEntry)
    val calculatedMine = checkNotNull(ranked.firstOrNull { it.characterId == local.characterId })
    val worstKnownExternal = external.maxWithOrNull(arenaRankingDisplayOrder)
    val allParticipantsKnown = snapshot.totalParticipants <= ranked.size
    val exactRankIsProven = allParticipantsKnown ||
        (worstKnownExternal != null && arenaRankingBandOrder.compare(localEntry, worstKnownExternal) <= 0)
    val localMine = when {
        !rankingChanged -> checkNotNull(settledMine).let { unchangedMine ->
            localEntry.copy(
                rank = unchangedMine.rank,
                listIndex = unchangedMine.listIndex,
                isProvisional = false,
            )
        }
        exactRankIsProven -> calculatedMine
        else -> calculatedMine.copy(
            rank = OUTSIDE_DISPLAYED_ARENA_RANK,
            listIndex = -1,
        )
    }
    val displayEntries = if (rankingChanged && exactRankIsProven) {
        ranked.take(MAX_DISPLAYED_ARENA_RANK)
    } else {
        snapshot.entries
    }
    val wasAlreadyPublished = settledMine != null
    return ArenaRankingDisplaySnapshot(
        snapshotId = snapshot.snapshotId,
        settledAtEpochMillis = snapshot.settledAtEpochMillis,
        nextSettlementAtEpochMillis = snapshot.nextSettlementAtEpochMillis,
        totalParticipants = maxOf(
            snapshot.totalParticipants + if (wasAlreadyPublished) 0 else 1,
            ranked.size,
        ),
        entries = displayEntries,
        myEntry = localMine,
    )
}
