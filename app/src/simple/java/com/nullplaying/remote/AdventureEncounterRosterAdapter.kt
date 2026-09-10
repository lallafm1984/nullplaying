package com.nullplaying.remote

import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEncounterRoster

/**
 * Read-only projection of the existing daily ranking response. The published source only has
 * ranked level-20+ heroes (top 1,000 plus the account's own rows), so this is not a nearby-player
 * search. In particular, level 1..18 heroes normally have no eligible player candidate.
 *
 * Public rows provide class, level and displayed power, not six stats, adventure traits,
 * last-seen time, account IDs or report/block state. Never infer those missing fields.
 */
internal object AdventureEncounterRosterAdapter {
    fun fromDailyRanking(
        snapshot: RemoteRankingSnapshot,
        now: Long,
        excludedCharacterIds: Set<String> = emptySet(),
    ): AdventureEncounterRoster? {
        if (!snapshot.hasDailySettlement() || snapshot.snapshotId.length > 128 ||
            snapshot.snapshotId != snapshot.snapshotId.trim() || snapshot.snapshotId.any(Char::isISOControl) ||
            snapshot.fetchedAtEpochMillis <= 0L ||
            snapshot.fetchedAtEpochMillis > now || snapshot.generatedAtEpochMillis < snapshot.settledAtEpochMillis
        ) return null
        // Ranking dates use the server clock; adventure events use the local wall-clock timeline.
        // nextCheck is deliberately ignored: a retry window must never extend encounter validity.
        val validUntil = subtractWithoutOverflow(
            snapshot.nextSettlementAtEpochMillis, snapshot.serverTimeOffsetMillis,
        ) ?: return null
        val settledAt = subtractWithoutOverflow(
            snapshot.settledAtEpochMillis, snapshot.serverTimeOffsetMillis,
        ) ?: return null
        val generatedAt = subtractWithoutOverflow(
            snapshot.generatedAtEpochMillis, snapshot.serverTimeOffsetMillis,
        ) ?: return null
        if (now < settledAt || now < generatedAt || now >= validUntil ||
            validUntil <= snapshot.fetchedAtEpochMillis
        ) return null

        val excluded = excludedCharacterIds + snapshot.requestedCharacterId +
            snapshot.ownEntries.map { it.characterId } +
            snapshot.entries.filter { it.isMe }.map { it.characterId } +
            listOfNotNull(snapshot.myEntry?.characterId)
        // Reject all copies of an ambiguous identifier instead of trusting response order.
        val duplicateIds = snapshot.entries.groupingBy { it.characterId }.eachCount()
            .filterValues { it > 1 }.keys
        val candidates = snapshot.entries.asSequence()
            .filter { it.rank in 1..1_000 && it.systemEntryCode == null }
            .filter { it.characterId !in excluded && it.characterId !in duplicateIds }
            .filter { isValidAdventureCandidate(it.characterId, it.displayName, it.level, it.combatPower) }
            .map {
                AdventureEncounterCandidate(
                    characterId = it.characterId,
                    displayName = it.displayName,
                    heroClass = it.heroClass,
                    level = it.level,
                    combatPower = it.combatPower,
                )
            }
            .sortedBy { it.characterId }
            .toList()
        return AdventureEncounterRoster(
            snapshotId = snapshot.snapshotId,
            receivedAt = snapshot.fetchedAtEpochMillis,
            validUntil = validUntil,
            candidates = candidates,
        )
    }

    private fun subtractWithoutOverflow(value: Long, offset: Long): Long? =
        runCatching { Math.subtractExact(value, offset) }.getOrNull()
}

/** Also used at the local persistence boundary, which fixtures can reach without server DTOs. */
internal fun isValidAdventureCandidate(id: String, name: String, level: Long, power: Long): Boolean =
    id.isNotBlank() && id.length <= 128 && id == id.trim() && id.none(Char::isISOControl) &&
        name.isNotBlank() && name.length <= 24 && name == name.trim() && name.none(Char::isISOControl) &&
        level in 1L..MAX_SUPPORTED_RANKING_LEVEL && power in 1L..maximumAcceptedRankingCombatPower(level)
