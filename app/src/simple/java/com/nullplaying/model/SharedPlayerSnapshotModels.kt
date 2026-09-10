package com.nullplaying.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SHARED_PLAYER_RULES_VERSION = 1
const val SHARED_PLAYER_SNAPSHOT_VERSION = 1
const val SHARED_PLAYER_MIN_LEVEL = 10L
const val SHARED_PLAYER_MAX_LEVEL = 10_000L
/** Wire-limit for simultaneously owned traits; independent from the catalog's total size. */
const val SHARED_PLAYER_MAX_ACTIVE_TRAITS = 24
const val SHARED_PLAYER_ROSTER_LIMIT = 24

/** Raw arena inputs. Current HP/MP, economy, inventory and private progress are excluded. */
@Serializable
data class PublicPlayerStats(
    val strength: Long,
    val constitution: Long,
    val dexterity: Long,
    val intelligence: Long,
    val wisdom: Long,
    val charisma: Long,
    @SerialName("max_health") val maxHealth: Long,
    @SerialName("max_mana") val maxMana: Long,
)

/**
 * Public, non-economic projection returned by the server. [projectionId] is server generated and
 * stable across updates, so relationship memories and arena records can address the same person
 * without exposing the authenticated account id or the owner's local character id.
 */
@Serializable
data class PublicPlayerSnapshot(
    @SerialName("projection_id") val projectionId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("hero_class") val heroClass: HeroClass,
    val level: Long,
    @SerialName("combat_power") val combatPower: Long,
    @SerialName("rules_version") val rulesVersion: Int,
    @SerialName("snapshot_version") val snapshotVersion: Int,
    val stats: PublicPlayerStats,
    @SerialName("adventure_trait_ids") val adventureTraitIds: List<String>,
)

/** One immutable, server-selected Level +/-1 pool for a local character and UTC day. */
@Serializable
data class PublicPlayerRoster(
    @SerialName("requester_character_id") val requesterCharacterId: String,
    @SerialName("requester_level") val requesterLevel: Long,
    @SerialName("roster_id") val rosterId: String,
    @SerialName("roster_date_utc") val rosterDateUtc: String,
    @SerialName("rules_version") val rulesVersion: Int,
    @SerialName("received_at") val receivedAtEpochMillis: Long,
    @SerialName("valid_until") val validUntilEpochMillis: Long,
    val snapshots: List<PublicPlayerSnapshot>,
    /** Server clock anchor. Zero marks a legacy roster that cannot advance competitive time. */
    @SerialName("server_now_at_receipt") val serverNowAtReceiptEpochMillis: Long = 0L,
    /** Server-side expiry paired with [serverNowAtReceiptEpochMillis]. */
    @SerialName("server_valid_until") val serverValidUntilEpochMillis: Long = 0L,
    /** Monotonic device time observed with the server response. */
    @SerialName("received_at_monotonic") val receivedAtMonotonicMillis: Long = -1L,
    /** Device boot that owns [receivedAtMonotonicMillis]; absent legacy values are never trusted. */
    @SerialName("received_at_boot_count") val receivedAtBootCount: Int = -1,
) {
    fun snapshotFor(projectionId: String): PublicPlayerSnapshot? =
        snapshots.singleOrNull { it.projectionId == projectionId }

    fun isReusableFor(
        characterId: String,
        currentRequesterLevel: Long,
        nowEpochMillis: Long,
        expectedRulesVersion: Int = SHARED_PLAYER_RULES_VERSION,
    ): Boolean = requesterCharacterId == characterId &&
        requesterLevel == currentRequesterLevel &&
        requesterLevel in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL &&
        rulesVersion == expectedRulesVersion &&
        rosterId.isNotBlank() &&
        rosterDateUtc.matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) &&
        receivedAtEpochMillis in 1L..nowEpochMillis &&
        validUntilEpochMillis > nowEpochMillis &&
        snapshots.size <= SHARED_PLAYER_ROSTER_LIMIT &&
        snapshots.map(PublicPlayerSnapshot::projectionId).distinct().size == snapshots.size &&
        snapshots.all { it.level in (requesterLevel - 1L)..(requesterLevel + 1L) }

    /** Device wall-clock edits cannot extend this lease within the same boot. */
    fun trustedServerNow(nowMonotonicMillis: Long, currentBootCount: Int): Long? {
        if (serverNowAtReceiptEpochMillis <= 0L || serverValidUntilEpochMillis <= serverNowAtReceiptEpochMillis ||
            receivedAtMonotonicMillis < 0L || nowMonotonicMillis < receivedAtMonotonicMillis ||
            receivedAtBootCount < 0 || currentBootCount < 0 || receivedAtBootCount != currentBootCount
        ) return null
        val elapsed = nowMonotonicMillis - receivedAtMonotonicMillis
        if (elapsed > MAX_TRUSTED_ROSTER_AGE_MILLIS) return null
        val estimated = runCatching { Math.addExact(serverNowAtReceiptEpochMillis, elapsed) }
            .getOrNull() ?: return null
        return estimated.takeIf { it < serverValidUntilEpochMillis }
    }

    fun isTrustedReusableFor(
        characterId: String,
        currentRequesterLevel: Long,
        nowEpochMillis: Long,
        nowMonotonicMillis: Long,
        currentBootCount: Int,
        expectedRulesVersion: Int = SHARED_PLAYER_RULES_VERSION,
    ): Boolean = isReusableFor(
        characterId = characterId,
        currentRequesterLevel = currentRequesterLevel,
        nowEpochMillis = nowEpochMillis,
        expectedRulesVersion = expectedRulesVersion,
    ) && trustedServerNow(nowMonotonicMillis, currentBootCount) != null
}

private const val MAX_TRUSTED_ROSTER_AGE_MILLIS = 25L * 60L * 60L * 1_000L

/**
 * Compact compatibility view for the relationship scheduler. Contacts retain identity only;
 * battle start resolves and freezes the full data from [PublicPlayerRoster] by projection id.
 */
fun PublicPlayerRoster.toAdventureEncounterRoster(): AdventureEncounterRoster =
    AdventureEncounterRoster(
        snapshotId = rosterId,
        receivedAt = receivedAtEpochMillis,
        validUntil = validUntilEpochMillis,
        candidates = snapshots.map { snapshot ->
            AdventureEncounterCandidate(
                characterId = snapshot.projectionId,
                displayName = snapshot.displayName,
                heroClass = snapshot.heroClass,
                level = snapshot.level,
                combatPower = snapshot.combatPower,
            )
        },
    )

/** Immutable full participant input for a relationship battle. */
fun PublicPlayerSnapshot.toAdventureRelationshipBattleParticipantSnapshot() =
    AdventureRelationshipBattleParticipantSnapshot(
        characterId = projectionId,
        displayName = displayName,
        heroClass = heroClass,
        level = level,
        combatPower = combatPower,
        stats = stats.toHeroStats(),
        // Skills are reconstructed from class/level with safe local mastery rules.
        learnedSkills = emptyList(),
        // Real equipment and inventory metadata never cross the server boundary.
        equipment = emptyList(),
        adventureTraitIds = adventureTraitIds,
    )

private fun PublicPlayerStats.toHeroStats() = HeroStats(
    strength = strength,
    constitution = constitution,
    dexterity = dexterity,
    intelligence = intelligence,
    wisdom = wisdom,
    charisma = charisma,
    maxHealth = maxHealth,
    maxMana = maxMana,
)
