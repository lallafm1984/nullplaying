package com.nullplaying.remote

import com.nullplaying.data.GameSnapshot
import com.nullplaying.engine.AdventureTraitCatalog
import com.nullplaying.model.HeroClass
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.SHARED_PLAYER_MAX_ACTIVE_TRAITS
import com.nullplaying.model.SHARED_PLAYER_MAX_LEVEL
import com.nullplaying.model.SHARED_PLAYER_MIN_LEVEL
import com.nullplaying.model.SHARED_PLAYER_ROSTER_LIMIT
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import com.nullplaying.model.SimpleGameState
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Exact public upload. Skills, equipment, economy, inventory and relationship data are absent. */
@Serializable
internal data class PublicPlayerSnapshotUpload(
    @SerialName("character_id") val characterId: String,
    @SerialName("slot_id") val slotId: Int,
    @SerialName("display_name") val displayName: String,
    @SerialName("hero_class") val heroClass: HeroClass,
    val level: Long,
    @SerialName("combat_power") val combatPower: Long,
    @SerialName("rules_version") val rulesVersion: Int = SHARED_PLAYER_RULES_VERSION,
    @SerialName("snapshot_version") val snapshotVersion: Int = SHARED_PLAYER_SNAPSHOT_VERSION,
    val stats: PublicPlayerStats,
    @SerialName("adventure_trait_ids") val adventureTraitIds: List<String>,
)

@Serializable
internal data class DailyPublicPlayerRosterResponse(
    @SerialName("roster_id") val rosterId: String,
    @SerialName("roster_date_utc") val rosterDateUtc: String,
    @SerialName("requester_level") val requesterLevel: Long,
    @SerialName("rules_version") val rulesVersion: Int,
    @SerialName("generated_at") val generatedAtEpochMillis: Long,
    @SerialName("valid_until") val validUntilEpochMillis: Long,
    @SerialName("server_now") val serverNowEpochMillis: Long,
    /** Raw rows are decoded independently so one malformed candidate cannot poison the envelope. */
    val snapshots: List<JsonElement> = emptyList(),
)

internal fun buildPublicPlayerSnapshotUploads(
    snapshot: GameSnapshot,
    combatPower: (SimpleGameState) -> Long,
): List<PublicPlayerSnapshotUpload>? {
    if (!snapshot.ready) return null
    val uploads = snapshot.characters.sortedBy { it.slotId }.mapNotNull { character ->
        val state = character.state
        if (state.hero.level < SHARED_PLAYER_MIN_LEVEL || state.rankingCharacterId.isBlank()) {
            return@mapNotNull null
        }
        state.toPublicPlayerSnapshotUpload(character.slotId, combatPower(state)) ?: return null
    }
    if (uploads.map(PublicPlayerSnapshotUpload::characterId).distinct().size != uploads.size) return null
    return uploads
}

private fun SimpleGameState.toPublicPlayerSnapshotUpload(
    slotId: Int,
    combatPower: Long,
): PublicPlayerSnapshotUpload? {
    val characterId = rankingCharacterId.takeIf(::isUuid) ?: return null
    if (slotId !in 1..MAX_PUBLIC_CHARACTER_SLOTS) return null
    val displayName = hero.name.trim()
    if (!displayName.isValidPublicLabel(MAX_DISPLAY_NAME_CODE_POINTS)) return null
    if (hero.level !in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL) return null
    if (combatPower !in 1L..maximumAcceptedRankingCombatPower(hero.level)) return null
    val rawStats = hero.stats
    val stats = PublicPlayerStats(
        strength = rawStats.strength,
        constitution = rawStats.constitution,
        dexterity = rawStats.dexterity,
        intelligence = rawStats.intelligence,
        wisdom = rawStats.wisdom,
        charisma = rawStats.charisma,
        maxHealth = rawStats.maxHealth,
        maxMana = rawStats.maxMana,
    )
    if (!isValidPublicPlayerStats(stats, hero.level, combatPower)) return null
    if (!isPlausiblePublicCombatPower(stats, hero.heroClass, hero.level, combatPower)) return null
    val traits = adventureTraits.owned.map { it.traitId }
    if (!isValidTraitIds(traits)) return null
    return PublicPlayerSnapshotUpload(
        characterId = characterId,
        slotId = slotId,
        displayName = displayName,
        heroClass = hero.heroClass,
        level = hero.level,
        combatPower = combatPower,
        stats = stats,
        adventureTraitIds = traits,
    )
}

internal fun DailyPublicPlayerRosterResponse.toPublicPlayerRoster(
    requesterCharacterId: String,
    requesterLevel: Long,
    receivedAtEpochMillis: Long,
    receivedAtMonotonicMillis: Long = -1L,
    receivedAtBootCount: Int = -1,
): PublicPlayerRoster? {
    if (!isUuid(requesterCharacterId) || requesterLevel !in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL) return null
    if (!isUuid(rosterId) || rulesVersion != SHARED_PLAYER_RULES_VERSION) return null
    if (this.requesterLevel != requesterLevel) return null
    if (!rosterDateUtc.matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))) return null
    if (serverNowEpochMillis <= 0L || generatedAtEpochMillis !in 1L..serverNowEpochMillis) return null
    val remaining = validUntilEpochMillis - serverNowEpochMillis
    if (remaining !in 1L..SHARED_PLAYER_MAX_ROSTER_LIFETIME_MILLIS) return null
    val localValidUntil = safeAdd(receivedAtEpochMillis, remaining) ?: return null
    if (snapshots.size > SHARED_PLAYER_ROSTER_LIMIT) return null
    val decodedSnapshots = snapshots.mapNotNull { row ->
        runCatching { publicSnapshotRowJson.decodeFromJsonElement<PublicPlayerSnapshot>(row) }.getOrNull()
    }
    val duplicateIds = decodedSnapshots.groupingBy(PublicPlayerSnapshot::projectionId)
        .eachCount().filterValues { it > 1 }.keys
    val validSnapshots = decodedSnapshots.filter { value ->
        value.projectionId !in duplicateIds &&
            isValidPublicPlayerSnapshot(value, requesterLevel)
    }
    return PublicPlayerRoster(
        requesterCharacterId = requesterCharacterId,
        requesterLevel = requesterLevel,
        rosterId = rosterId,
        rosterDateUtc = rosterDateUtc,
        rulesVersion = rulesVersion,
        receivedAtEpochMillis = receivedAtEpochMillis,
        validUntilEpochMillis = localValidUntil,
        snapshots = validSnapshots.sortedBy(PublicPlayerSnapshot::projectionId),
        serverNowAtReceiptEpochMillis = serverNowEpochMillis,
        serverValidUntilEpochMillis = validUntilEpochMillis,
        receivedAtMonotonicMillis = receivedAtMonotonicMillis,
        receivedAtBootCount = receivedAtBootCount,
    )
}

internal fun isValidPublicPlayerSnapshot(
    value: PublicPlayerSnapshot,
    requesterLevel: Long,
): Boolean = isUuid(value.projectionId) &&
    value.displayName.isValidPublicLabel(MAX_DISPLAY_NAME_CODE_POINTS) &&
    value.level in (requesterLevel - 1L)..(requesterLevel + 1L) &&
    value.combatPower in 1L..maximumAcceptedRankingCombatPower(value.level) &&
    value.rulesVersion == SHARED_PLAYER_RULES_VERSION &&
    value.snapshotVersion == SHARED_PLAYER_SNAPSHOT_VERSION &&
    isValidPublicPlayerStats(value.stats, value.level, value.combatPower) &&
    isPlausiblePublicCombatPower(value.stats, value.heroClass, value.level, value.combatPower) &&
    isValidTraitIds(value.adventureTraitIds)

internal fun isValidPublicPlayerStats(value: PublicPlayerStats, level: Long, combatPower: Long): Boolean {
    val baseValues = value.baseValues()
    if (level !in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL || combatPower <= 0L) return false
    val maxBaseStat = level * 2L + 16L
    val maxBaseTotal = level * 2L + 106L
    val maxVitalityByLevel = 4L * level * level + 512L
    val maxVitalityByPower = combatPower * (level + 64L)
    val maxVitality = minOf(maxVitalityByLevel, maxVitalityByPower)
    return baseValues.all { it in 0L..maxBaseStat } &&
        baseValues.sum() <= maxBaseTotal &&
        value.maxHealth >= 1L && value.maxMana >= 0L &&
        value.maxHealth <= maxVitality && value.maxMana <= maxVitality - value.maxHealth
}

/** Cross-checks raw stats against power while allowing an undisclosed legal item contribution. */
internal fun isPlausiblePublicCombatPower(
    stats: PublicPlayerStats,
    heroClass: HeroClass,
    level: Long,
    combatPower: Long,
): Boolean {
    if (level !in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL) return false
    val values = stats.baseValues()
    val weightedStatTenths = values[heroClass.primaryStatIndex] * 7L +
        values[heroClass.secondaryStatIndex] * 3L
    val benchmark = publicEquipmentBenchmark(level)
    val legacyExpected = 315L + (level - 1L) * 9L
    val guidedExpected = 315L + (level - 1L) * 20L
    fun normalized(expectedThirtieths: Long): Long {
        if (weightedStatTenths <= 0L) return 0L
        val ratio = weightedStatTenths.toDouble() * 3.0 / expectedThirtieths.toDouble()
        val deviation = ratio - 1.0
        val multiplier = 1.0 + deviation / (1.0 + abs(deviation) / STAT_SOFT_CAP_RANGE)
        return (benchmark.toDouble() * multiplier).roundToLong().coerceAtLeast(0L)
    }
    val endpointPowers = listOf(normalized(legacyExpected), normalized(guidedExpected))
    val minimum = (endpointPowers.min() - 1L).coerceAtLeast(1L)
    val maximum = endpointPowers.max() + maximumPublicEquipmentContribution(level) + 1L
    return combatPower in minimum..maximum
}

internal fun publicEquipmentBenchmark(level: Long): Long = 1L + (level - 1L) * 5L

internal fun publicExpectedCombatPower(level: Long): Long = publicEquipmentBenchmark(level) * 2L

private fun maximumPublicEquipmentContribution(level: Long): Long {
    val benchmark = publicEquipmentBenchmark(level)
    val maximumShop = benchmark + 13L
    val mythicSource = (benchmark - 10L).coerceAtLeast(0L) + 30L
    val guaranteedMythic = (maximumShop * 105L + 99L) / 100L
    return maxOf(mythicSource, guaranteedMythic) + 11L
}

private fun PublicPlayerStats.baseValues(): List<Long> = listOf(
    strength, constitution, dexterity, intelligence, wisdom, charisma,
)

private fun isValidTraitIds(values: List<String>): Boolean {
    if (values.size > SHARED_PLAYER_MAX_ACTIVE_TRAITS || values.distinct().size != values.size) return false
    val definitions = values.map { AdventureTraitCatalog.find(it) ?: return false }
    val owned = values.toSet()
    return definitions.none { it.oppositeId.isNotBlank() && it.oppositeId in owned }
}

internal fun String.isValidPublicLabel(maxCodePoints: Int): Boolean {
    if (isBlank() || this != trim()) return false
    if (codePointCount(0, length) !in 1..maxCodePoints) return false
    if (toByteArray(Charsets.UTF_8).size > maxCodePoints * UTF8_MAX_BYTES_PER_CODE_POINT) return false
    return codePoints().allMatch { codePoint ->
        codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code &&
            !Character.isISOControl(codePoint)
    }
}

private fun isUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString().equals(value, ignoreCase = true)
}.getOrDefault(false)

private fun safeAdd(left: Long, right: Long): Long? =
    runCatching { Math.addExact(left, right) }.getOrNull()

private const val MAX_DISPLAY_NAME_CODE_POINTS = 24
private const val MAX_PUBLIC_CHARACTER_SLOTS = 3
private const val UTF8_MAX_BYTES_PER_CODE_POINT = 4
private const val STAT_SOFT_CAP_RANGE = 0.35
private const val SHARED_PLAYER_MAX_ROSTER_LIFETIME_MILLIS = 25L * 60L * 60L * 1_000L
private val publicSnapshotRowJson = Json { ignoreUnknownKeys = true }
