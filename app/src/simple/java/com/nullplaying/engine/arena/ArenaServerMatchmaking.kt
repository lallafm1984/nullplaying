package com.nullplaying.engine.arena

import com.nullplaying.engine.BattleQaMatchFactory
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.SHARED_PLAYER_MAX_LEVEL
import com.nullplaying.model.SHARED_PLAYER_MIN_LEVEL
import com.nullplaying.model.SHARED_PLAYER_ROSTER_LIMIT
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION

internal enum class ArenaOpponentSource {
    PUBLIC_ROSTER,
    LOCAL_RESERVE,
}

internal enum class ArenaServerMatchUnavailableReason {
    DISABLED,
    MISSING_ROSTER,
    INVALID_OR_EXPIRED_ROSTER,
    EMPTY_VALID_POOL,
    LOCAL_RESERVE_FAILED,
}

internal sealed interface ArenaServerMatchSelectionResult {
    data class Ready(
        val rosterId: String,
        val opponent: PublicPlayerArenaMatchInput,
        val rotationIndex: Int,
        val poolSize: Int,
        val battleSeed: Long,
        val source: ArenaOpponentSource = ArenaOpponentSource.PUBLIC_ROSTER,
        val serverPoolSize: Int = 0,
        val localPoolSize: Int = 0,
        val matchmakingProfile: ArenaMatchmakingProfile? = null,
    ) : ArenaServerMatchSelectionResult

    data class Unavailable(
        val reason: ArenaServerMatchUnavailableReason,
    ) : ArenaServerMatchSelectionResult
}

private sealed interface ArenaPublicPoolResolution {
    data class Ready(
        val rosterId: String,
        val opponents: List<PublicPlayerArenaMatchInput>,
    ) : ArenaPublicPoolResolution

    data class Unavailable(
        val reason: ArenaServerMatchUnavailableReason,
    ) : ArenaPublicPoolResolution
}

private data class ArenaOpponentPoolEntry(
    val opponent: PublicPlayerArenaMatchInput,
    val source: ArenaOpponentSource,
)

/**
 * A real roster may be used only when both rollout flags and the remote transport are active.
 * An explicit in-memory QA fixture is the sole exception and never enables network access.
 */
internal fun arenaServerMatchingEnabled(
    arenaFlagEnabled: Boolean,
    sharedTransportEnabled: Boolean,
    remoteServicesEnabled: Boolean,
    sharedPlayerQaTransportEnabled: Boolean = false,
    qaFixtureEnabled: Boolean = false,
): Boolean = qaFixtureEnabled ||
    (arenaFlagEnabled && sharedTransportEnabled &&
        (remoteServicesEnabled || sharedPlayerQaTransportEnabled))

/**
 * Selects one opponent from the already cached daily roster. The roster is never refreshed here.
 * A stable roster offset plus the completed-match sequence visits every valid entry once before
 * repeating, preventing refresh hunting and avoiding a network call per match.
 */
internal fun selectArenaServerOpponent(
    enabled: Boolean,
    roster: PublicPlayerRoster?,
    requesterCharacterId: String,
    requesterLevel: Long,
    nowEpochMillis: Long,
    completedMatchSequence: Long,
): ArenaServerMatchSelectionResult {
    val pool = resolvePublicPool(
        enabled = enabled,
        roster = roster,
        requesterCharacterId = requesterCharacterId,
        requesterLevel = requesterLevel,
        nowEpochMillis = nowEpochMillis,
        completedMatchSequence = completedMatchSequence,
    )
    if (pool is ArenaPublicPoolResolution.Unavailable) {
        return ArenaServerMatchSelectionResult.Unavailable(pool.reason)
    }
    pool as ArenaPublicPoolResolution.Ready
    val start = Math.floorMod(arenaStableHash64(pool.rosterId), pool.opponents.size.toLong())
    val offset = Math.floorMod(completedMatchSequence, pool.opponents.size.toLong())
    val index = ((start + offset) % pool.opponents.size.toLong()).toInt()
    val opponent = pool.opponents[index]
    return ArenaServerMatchSelectionResult.Ready(
        rosterId = pool.rosterId,
        opponent = opponent,
        rotationIndex = index,
        poolSize = pool.opponents.size,
        battleSeed = arenaStableHash64(
            "${pool.rosterId}|${opponent.projection.projectionId}|" +
                "$completedMatchSequence|arena-battle-v1",
        ),
        source = ArenaOpponentSource.PUBLIC_ROSTER,
        serverPoolSize = pool.opponents.size,
        localPoolSize = 0,
    )
}

/**
 * Builds the complete opponent pool without refreshing the network. When the server gate is off,
 * missing, expired, or has fewer than twenty valid candidates, deterministic local reserves fill
 * exactly the remaining slots. Current Arena callers also supply power/history; only candidates
 * inside that difficulty band count toward twenty. Legacy callers retain the original rotation.
 */
internal fun selectArenaOpponent(
    serverRosterEnabled: Boolean,
    roster: PublicPlayerRoster?,
    requesterCharacterId: String,
    requesterLevel: Long,
    nowEpochMillis: Long,
    completedMatchSequence: Long,
    requesterCombatPower: Long? = null,
    recentMatches: List<ArenaRecentMatch> = emptyList(),
    requesterStats: com.nullplaying.model.HeroStats? = null,
): ArenaServerMatchSelectionResult {
    if (completedMatchSequence < 0L ||
        requesterLevel !in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL
    ) {
        return ArenaServerMatchSelectionResult.Unavailable(
            ArenaServerMatchUnavailableReason.LOCAL_RESERVE_FAILED,
        )
    }
    val initialProfile = requesterCombatPower?.let {
        ArenaAdaptiveMatchmaking.profile(requesterLevel, it, recentMatches)
            ?: return ArenaServerMatchSelectionResult.Unavailable(ArenaServerMatchUnavailableReason.LOCAL_RESERVE_FAILED)
    }
    val publicResolution = resolvePublicPool(
        enabled = serverRosterEnabled,
        roster = roster,
        requesterCharacterId = requesterCharacterId,
        requesterLevel = requesterLevel,
        nowEpochMillis = nowEpochMillis,
        completedMatchSequence = completedMatchSequence,
    )
    val publicPool = (publicResolution as? ArenaPublicPoolResolution.Ready)
    val serverOpponents = publicPool?.opponents.orEmpty().filter { opponent ->
        initialProfile == null || ArenaAdaptiveMatchmaking.accepts(
            initialProfile, opponent.projection.level, opponent.projection.verifiedPower,
        )
    }
    val profile = initialProfile
    val localCount = (MINIMUM_OPPONENT_POOL_SIZE - serverOpponents.size).coerceAtLeast(0)
    val localOpponents = ArenaLocalReserveMatchmaking.build(
        requesterCharacterId = requesterCharacterId,
        requesterLevel = requesterLevel,
        count = localCount,
        forbiddenProjectionIds = serverOpponents.mapTo(mutableSetOf(requesterCharacterId)) {
            it.projection.projectionId
        },
        matchmakingProfile = profile,
        requesterStats = requesterStats,
    ) ?: return ArenaServerMatchSelectionResult.Unavailable(
        ArenaServerMatchUnavailableReason.LOCAL_RESERVE_FAILED,
    )
    if (localOpponents.size != localCount) {
        return ArenaServerMatchSelectionResult.Unavailable(
            ArenaServerMatchUnavailableReason.LOCAL_RESERVE_FAILED,
        )
    }
    val pool = serverOpponents.map {
        ArenaOpponentPoolEntry(it, ArenaOpponentSource.PUBLIC_ROSTER)
    } + localOpponents.map {
        ArenaOpponentPoolEntry(it, ArenaOpponentSource.LOCAL_RESERVE)
    }
    if (pool.size < MINIMUM_OPPONENT_POOL_SIZE ||
        pool.map { it.opponent.projection.projectionId }.distinct().size != pool.size
    ) {
        return ArenaServerMatchSelectionResult.Unavailable(
            ArenaServerMatchUnavailableReason.LOCAL_RESERVE_FAILED,
        )
    }
    val poolId = publicPool?.rosterId
        ?: "arena-local-reserve-v2|$requesterCharacterId|$requesterLevel"
    val start = Math.floorMod(arenaStableHash64(poolId), pool.size.toLong())
    val offset = Math.floorMod(completedMatchSequence, pool.size.toLong())
    val baseIndex = ((start + offset) % pool.size.toLong()).toInt()
    val recentIds = if (profile == null) emptySet() else
        ArenaAdaptiveMatchmaking.recentMatches(recentMatches, requesterLevel).take(3).map { it.opponentId }.toSet()
    val index = (pool.indices).map { (baseIndex + it) % pool.size }.firstOrNull {
        pool[it].opponent.projection.projectionId !in recentIds
    } ?: baseIndex
    val selected = pool[index]
    return ArenaServerMatchSelectionResult.Ready(
        rosterId = poolId,
        opponent = selected.opponent,
        rotationIndex = index,
        poolSize = pool.size,
        battleSeed = arenaStableHash64(
            "$poolId|${selected.opponent.projection.projectionId}|" +
                "$completedMatchSequence|arena-battle-v1",
        ),
        source = selected.source,
        serverPoolSize = serverOpponents.size,
        localPoolSize = localOpponents.size,
        matchmakingProfile = profile,
    )
}

private fun resolvePublicPool(
    enabled: Boolean,
    roster: PublicPlayerRoster?,
    requesterCharacterId: String,
    requesterLevel: Long,
    nowEpochMillis: Long,
    completedMatchSequence: Long,
): ArenaPublicPoolResolution {
    if (!enabled) {
        return ArenaPublicPoolResolution.Unavailable(ArenaServerMatchUnavailableReason.DISABLED)
    }
    val source = roster ?: return ArenaPublicPoolResolution.Unavailable(
        ArenaServerMatchUnavailableReason.MISSING_ROSTER,
    )
    val validEnvelope = completedMatchSequence >= 0L &&
        source.requesterCharacterId == requesterCharacterId &&
        source.requesterLevel == requesterLevel &&
        requesterLevel in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL &&
        source.rulesVersion == SHARED_PLAYER_RULES_VERSION &&
        source.rosterId.isNotBlank() &&
        source.rosterDateUtc.matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) &&
        source.receivedAtEpochMillis in 1L..nowEpochMillis &&
        source.validUntilEpochMillis > nowEpochMillis &&
        source.snapshots.size <= SHARED_PLAYER_ROSTER_LIMIT
    if (!validEnvelope) {
        return ArenaPublicPoolResolution.Unavailable(
            ArenaServerMatchUnavailableReason.INVALID_OR_EXPIRED_ROSTER,
        )
    }
    val valid = source.snapshots.asSequence()
        .filter { it.projectionId != requesterCharacterId }
        .filter { it.level in (requesterLevel - 1L)..(requesterLevel + 1L) }
        .mapNotNull { snapshot ->
            PublicPlayerArenaInputAdapter.fromRosterSnapshot(
                snapshot = snapshot,
                arenaLevel = ArenaCharacterPointRules.budget(snapshot.level),
                stableSeed = arenaAutoBuildAllocationSeed(snapshot.projectionId),
                receivedAtEpochMillis = source.receivedAtEpochMillis,
            )
        }
        .distinctBy { it.projection.projectionId }
        .sortedBy { it.projection.projectionId }
        // 070006 issues twenty rows. A same-day 070004/005 cache may still contain up to twenty-four;
        // keep parsing it but use the same deterministic twenty-slot arena budget.
        .take(MINIMUM_OPPONENT_POOL_SIZE)
        .toList()
    return if (valid.isEmpty()) {
        ArenaPublicPoolResolution.Unavailable(ArenaServerMatchUnavailableReason.EMPTY_VALID_POOL)
    } else {
        ArenaPublicPoolResolution.Ready(source.rosterId, valid)
    }
}

/** Replaces the old one-off synthetic envelope while preserving the real local user's snapshot. */
internal fun BattleQaMatchFactory.Match.withArenaServerOpponent(
    selected: ArenaServerMatchSelectionResult.Ready,
    opponentReferenceScore: Int,
): BattleQaMatchFactory.Match {
    val publicProjection = selected.opponent.projection
    val permanentPower = publicProjection.verifiedPower.coerceAtLeast(1L)
    return copy(
        templateId = when (selected.source) {
            ArenaOpponentSource.PUBLIC_ROSTER -> "public:${publicProjection.projectionId}"
            ArenaOpponentSource.LOCAL_RESERVE -> "local-reserve:${publicProjection.projectionId}"
        },
        request = request.copy(
            serverSeed = selected.battleSeed,
            opponent = publicProjection,
            opponentReferenceScore = selected.matchmakingProfile?.let {
                ArenaAdaptiveMatchmaking.referenceScore(it, opponentReferenceScore, publicProjection.level, permanentPower)
            } ?: opponentReferenceScore.coerceAtLeast(0),
        ),
        opponentCombatPower = permanentPower,
        opponentEffectiveCombatPower = permanentPower,
    )
}

private const val MINIMUM_OPPONENT_POOL_SIZE = 20

internal fun arenaStableHash64(value: String): Long {
    var hash = -3750763034362895579L
    value.encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toLong() and 0xffL)
        hash *= 1099511628211L
    }
    return hash
}
