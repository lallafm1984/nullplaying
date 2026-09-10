package com.nullplaying.remote

import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Narrow bridge into the app's existing authenticated Supabase session. Implementations own
 * authentication, timeout, HTTPS and HTTP error handling; this boundary cannot address arbitrary
 * REST paths.
 */
internal interface AuthenticatedSharedPlayerRpcTransport {
    suspend fun captureSessionIdentity(): String

    fun isSessionIdentityCurrent(identity: String): Boolean

    /** Null means the additive unified RPC is not installed and the legacy path must be used. */
    suspend fun syncPlayerNetworkProfile(requestBody: String): String? = null

    /** Local-only lookup against a previously validated unified success receipt. */
    fun isPlayerNetworkProfileCurrent(requestBody: String, nowEpochMillis: Long): Boolean = false

    /** Local-only publication decision, including whether a prior non-empty profile needs tombstoning. */
    fun shouldPublishPlayerNetworkProfile(requestBody: String, nowEpochMillis: Long): Boolean = true

    suspend fun syncPublicPlayerSnapshots(requestBody: String): String

    suspend fun getDailyPublicPlayerRoster(requestBody: String): String
}

/** Production wire implementation for the two reviewed shared-player RPC contracts. */
internal class SupabaseAuthenticatedSharedPlayerApi(
    private val transport: AuthenticatedSharedPlayerRpcTransport,
    private val onUnifiedRankingSynced: (List<PublicPlayerSnapshotUpload>, Long) -> Unit = { _, _ -> },
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : AuthenticatedSharedPlayerApi {
    private val publicationMutex = Mutex()

    override suspend fun captureSessionIdentity(): String =
        transport.captureSessionIdentity()

    override fun isSessionIdentityCurrent(identity: String): Boolean =
        transport.isSessionIdentityCurrent(identity)

    override fun isPlayerNetworkProfileCurrent(
        snapshots: List<PublicPlayerSnapshotUpload>,
        nowEpochMillis: Long,
    ): Boolean {
        if (!isValidPublicPlayerUploadBatch(snapshots) || nowEpochMillis <= 0L) return false
        return transport.isPlayerNetworkProfileCurrent(
            json.encodeToString(PlayerNetworkProfileRequest(snapshots)),
            nowEpochMillis,
        )
    }

    override fun shouldPublishPlayerNetworkProfile(
        snapshots: List<PublicPlayerSnapshotUpload>,
        nowEpochMillis: Long,
    ): Boolean {
        if (!isValidPublicPlayerUploadBatch(snapshots) || nowEpochMillis <= 0L) return false
        return transport.shouldPublishPlayerNetworkProfile(
            json.encodeToString(PlayerNetworkProfileRequest(snapshots)),
            nowEpochMillis,
        )
    }

    override suspend fun syncPublicPlayerSnapshots(
        snapshots: List<PublicPlayerSnapshotUpload>,
    ): SharedPlayerPublicationMode = publicationMutex.withLock {
        require(isValidPublicPlayerUploadBatch(snapshots))
        val unifiedRequest = json.encodeToString(PlayerNetworkProfileRequest(snapshots))
        val unified = transport.syncPlayerNetworkProfile(unifiedRequest)
        if (unified != null) {
            val response = json.decodeFromString<PlayerNetworkProfileResponse>(unified)
            check(response.accepted)
            check(response.rulesVersion == SHARED_PLAYER_RULES_VERSION)
            check(response.syncedCount == snapshots.size)
            check(response.rankingSyncedCount == snapshots.count { it.level >= MIN_RANKING_LEVEL })
            check(response.serverNowEpochMillis > 0L)
            onUnifiedRankingSynced(snapshots, response.serverNowEpochMillis)
            return@withLock SharedPlayerPublicationMode.UNIFIED
        }
        val response = json.decodeFromString<PublicPlayerSyncResponse>(
            transport.syncPublicPlayerSnapshots(
                json.encodeToString(PublicPlayerSyncRequest(
                    snapshots.map(LegacyPublicPlayerSnapshotUpload::fromUnified),
                )),
            ),
        )
        check(response.rulesVersion == SHARED_PLAYER_RULES_VERSION)
        check(response.syncedCount == snapshots.size)
        check(response.serverNowEpochMillis > 0L)
        SharedPlayerPublicationMode.LEGACY
    }

    override suspend fun getDailyPublicPlayerRoster(
        characterId: String,
        rulesVersion: Int,
    ): DailyPublicPlayerRosterResponse {
        require(isCanonicalUuid(characterId))
        require(rulesVersion == SHARED_PLAYER_RULES_VERSION)
        val response = transport.getDailyPublicPlayerRoster(
            json.encodeToString(PublicPlayerRosterRequest(characterId, rulesVersion)),
        )
        require(response.toByteArray(Charsets.UTF_8).size <= MAX_DAILY_ROSTER_RESPONSE_BYTES) {
            "Daily public-player roster response exceeded its size limit"
        }
        return json.decodeFromString(response)
    }
}

/** One auditable switch shared by lifecycle wiring and the authenticated transport itself. */
internal fun isSharedPlayerRemoteEnabled(
    remoteServicesEnabled: Boolean,
    debugBuild: Boolean,
    adventureSystemEnabled: Boolean,
    featureEnabled: Boolean,
    supabaseUrl: String,
    supabasePublishableKey: String,
): Boolean = remoteServicesEnabled &&
    !debugBuild &&
    adventureSystemEnabled &&
    featureEnabled &&
    supabaseUrl.startsWith("https://") &&
    supabasePublishableKey.isNotBlank() &&
    !supabasePublishableKey.startsWith("sb_secret_", ignoreCase = true) &&
    !supabasePublishableKey.contains("service_role", ignoreCase = true)

@Serializable
internal data class SharedPlayerSyncReceipt(
    val userId: String,
    val payloadHash: String,
    val syncedAtEpochMillis: Long,
)

/**
 * Proof that the additive RPC committed both shared-player and ranking projections. This receipt
 * must never be populated from the legacy shared-snapshot RPC because that endpoint does not
 * commit ranking rows.
 */
@Serializable
internal data class PlayerNetworkProfileSyncReceipt(
    val userId: String,
    val payloadHash: String,
    val syncedAtEpochMillis: Long,
    val serverNowEpochMillis: Long,
)

internal enum class SharedPlayerSyncDecision { SEND, SKIP_UNCHANGED, DEFER_CHANGED }

/** Client pressure guard; the SQL RPC independently enforces its own authenticated rate limit. */
internal fun sharedPlayerSyncDecision(
    receipt: SharedPlayerSyncReceipt?,
    userId: String,
    payloadHash: String,
    nowEpochMillis: Long,
): SharedPlayerSyncDecision {
    if (receipt == null || receipt.userId != userId || nowEpochMillis < receipt.syncedAtEpochMillis) {
        return SharedPlayerSyncDecision.SEND
    }
    val age = nowEpochMillis - receipt.syncedAtEpochMillis
    if (receipt.payloadHash == payloadHash && age < UNCHANGED_SYNC_INTERVAL_MILLIS) {
        return SharedPlayerSyncDecision.SKIP_UNCHANGED
    }
    return if (age < CHANGED_SYNC_COOLDOWN_MILLIS) {
        SharedPlayerSyncDecision.DEFER_CHANGED
    } else {
        SharedPlayerSyncDecision.SEND
    }
}

internal fun playerNetworkProfileSyncDecision(
    receipt: PlayerNetworkProfileSyncReceipt?,
    userId: String,
    payloadHash: String,
    nowEpochMillis: Long,
): SharedPlayerSyncDecision {
    val valid = receipt?.takeIf { isValidPlayerNetworkProfileSyncReceipt(it, userId) }
        ?: return SharedPlayerSyncDecision.SEND
    return sharedPlayerSyncDecision(
        receipt = SharedPlayerSyncReceipt(
            userId = valid.userId,
            payloadHash = valid.payloadHash,
            syncedAtEpochMillis = valid.syncedAtEpochMillis,
        ),
        userId = userId,
        payloadHash = payloadHash,
        nowEpochMillis = nowEpochMillis,
    )
}

internal fun isValidPlayerNetworkProfileSyncReceipt(
    receipt: PlayerNetworkProfileSyncReceipt,
    userId: String,
): Boolean = userId.isNotBlank() && receipt.userId == userId &&
    receipt.payloadHash.isNotBlank() && receipt.syncedAtEpochMillis > 0L &&
    receipt.serverNowEpochMillis > 0L

/**
 * Decides whether the complete account profile needs publication without authentication or I/O.
 * A never-published empty profile is already absent on the server; an empty profile after a prior
 * success is a real tombstone and must be sent when its payload differs.
 */
internal fun shouldPublishPlayerNetworkProfile(
    receipt: PlayerNetworkProfileSyncReceipt?,
    userId: String?,
    payloadHash: String,
    characterCount: Int,
    nowEpochMillis: Long,
): Boolean {
    require(payloadHash.isNotBlank() && characterCount in 0..MAX_PUBLIC_CHARACTER_SNAPSHOTS)
    if (userId.isNullOrBlank()) return characterCount > 0
    val sameAccountReceipt = receipt?.takeIf { it.userId == userId }
        ?: return characterCount > 0
    return playerNetworkProfileSyncDecision(
        receipt = sameAccountReceipt,
        userId = userId,
        payloadHash = payloadHash,
        nowEpochMillis = nowEpochMillis,
    ) != SharedPlayerSyncDecision.SKIP_UNCHANGED
}

internal data class PlayerNetworkProfilePayloadSummary(
    val characterCount: Int,
    val rankingCount: Int,
)

internal fun playerNetworkProfilePayloadSummary(
    requestBody: String,
    json: Json,
): PlayerNetworkProfilePayloadSummary {
    val characters = json.parseToJsonElement(requestBody).jsonObject["p_characters"]
        ?.jsonArray ?: error("Player-network profile request has no character array")
    require(characters.size <= MAX_PUBLIC_CHARACTER_SNAPSHOTS)
    val rankingCount = characters.count { row ->
        val level = row.jsonObject["level"]?.jsonPrimitive?.longOrNull
            ?: error("Player-network profile row has no valid level")
        level >= MIN_RANKING_LEVEL
    }
    return PlayerNetworkProfilePayloadSummary(characters.size, rankingCount)
}

internal fun playerNetworkProfileUploads(
    requestBody: String,
    json: Json,
): List<PublicPlayerSnapshotUpload> = json
    .decodeFromString<PlayerNetworkProfileRequest>(requestBody)
    .characters
    .also { require(isValidPublicPlayerUploadBatch(it)) }

internal fun verifiedPlayerNetworkProfileResponse(
    responseBody: String,
    expected: PlayerNetworkProfilePayloadSummary,
    json: Json,
): PlayerNetworkProfileResponse = json.decodeFromString<PlayerNetworkProfileResponse>(responseBody)
    .also { response ->
        check(response.accepted)
        check(response.rulesVersion == SHARED_PLAYER_RULES_VERSION)
        check(response.syncedCount == expected.characterCount)
        check(response.rankingSyncedCount == expected.rankingCount)
        check(response.serverNowEpochMillis > 0L)
    }

internal fun deduplicatedPlayerNetworkProfileResponse(
    receipt: PlayerNetworkProfileSyncReceipt,
    expected: PlayerNetworkProfilePayloadSummary,
): String = buildJsonObject {
    put("accepted", true)
    put("rules_version", SHARED_PLAYER_RULES_VERSION)
    put("synced_count", expected.characterCount)
    put("ranking_synced_count", expected.rankingCount)
    // Only replay a server timestamp preserved from a validated unified success.
    put("server_now", receipt.serverNowEpochMillis)
    put("deduplicated", true)
}.toString()

@Serializable
private data class PublicPlayerSyncRequest(
    @SerialName("p_snapshots") val snapshots: List<LegacyPublicPlayerSnapshotUpload>,
)

/** The live 070004/005 validator rejects unknown keys, so fallback must omit unified-only slot_id. */
@Serializable
private data class LegacyPublicPlayerSnapshotUpload(
    @SerialName("character_id") val characterId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("hero_class") val heroClass: com.nullplaying.model.HeroClass,
    val level: Long,
    @SerialName("combat_power") val combatPower: Long,
    @SerialName("rules_version") val rulesVersion: Int,
    @SerialName("snapshot_version") val snapshotVersion: Int,
    val stats: com.nullplaying.model.PublicPlayerStats,
    @SerialName("adventure_trait_ids") val adventureTraitIds: List<String>,
) {
    companion object {
        fun fromUnified(value: PublicPlayerSnapshotUpload) = LegacyPublicPlayerSnapshotUpload(
            characterId = value.characterId,
            displayName = value.displayName,
            heroClass = value.heroClass,
            level = value.level,
            combatPower = value.combatPower,
            rulesVersion = value.rulesVersion,
            snapshotVersion = value.snapshotVersion,
            stats = value.stats,
            adventureTraitIds = value.adventureTraitIds,
        )
    }
}

@Serializable
private data class PlayerNetworkProfileRequest(
    @SerialName("p_characters") val characters: List<PublicPlayerSnapshotUpload>,
)

@Serializable
private data class PublicPlayerRosterRequest(
    @SerialName("p_character_id") val characterId: String,
    @SerialName("p_rules_version") val rulesVersion: Int,
)

@Serializable
internal data class PublicPlayerSyncResponse(
    @SerialName("rules_version") val rulesVersion: Int,
    @SerialName("synced_count") val syncedCount: Int,
    @SerialName("server_now") val serverNowEpochMillis: Long,
)

@Serializable
internal data class PlayerNetworkProfileResponse(
    val accepted: Boolean,
    @SerialName("rules_version") val rulesVersion: Int,
    @SerialName("synced_count") val syncedCount: Int,
    @SerialName("ranking_synced_count") val rankingSyncedCount: Int,
    @SerialName("server_now") val serverNowEpochMillis: Long,
)

private fun isCanonicalUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString().equals(value, ignoreCase = true)
}.getOrDefault(false)

private fun isValidPublicPlayerUploadBatch(snapshots: List<PublicPlayerSnapshotUpload>): Boolean =
    snapshots.size <= MAX_PUBLIC_CHARACTER_SNAPSHOTS &&
        snapshots.map(PublicPlayerSnapshotUpload::characterId).distinct().size == snapshots.size &&
        snapshots.map(PublicPlayerSnapshotUpload::slotId).distinct().size == snapshots.size &&
        snapshots.all {
            it.slotId in 1..MAX_PUBLIC_CHARACTER_SNAPSHOTS &&
                it.rulesVersion == SHARED_PLAYER_RULES_VERSION &&
                it.snapshotVersion == SHARED_PLAYER_SNAPSHOT_VERSION &&
                isCanonicalUuid(it.characterId)
        }

private const val MAX_PUBLIC_CHARACTER_SNAPSHOTS = 3
private const val MIN_RANKING_LEVEL = 20L
internal const val MAX_DAILY_ROSTER_RESPONSE_BYTES = 32 * 1024
internal const val CHANGED_SYNC_COOLDOWN_MILLIS = 15L * 60L * 1_000L
internal const val UNCHANGED_SYNC_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
