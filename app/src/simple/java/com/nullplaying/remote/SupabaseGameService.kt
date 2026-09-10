package com.nullplaying.remote

import android.content.Context
import android.content.res.Resources
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.nullplaying.BuildConfig
import com.nullplaying.data.GameSnapshot
import com.nullplaying.data.SimpleGameRepository
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RemoteRankingEntry(
    val rank: Int,
    val listIndex: Int,
    val characterId: String,
    val displayName: String,
    val heroClass: HeroClass,
    val level: Long,
    val combatPower: Long,
    val achievedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val isMe: Boolean,
    val systemEntryCode: String? = null,
)

data class RemoteRankingSnapshot(
    val requestedCharacterId: String,
    val fetchedAtEpochMillis: Long,
    val totalParticipants: Int,
    val entries: List<RemoteRankingEntry>,
    val myEntry: RemoteRankingEntry?,
    val isFromCache: Boolean = false,
    val snapshotId: String = "",
    val settledAtEpochMillis: Long = 0L,
    val nextSettlementAtEpochMillis: Long = 0L,
    val generatedAtEpochMillis: Long = 0L,
    val ownEntries: List<RemoteRankingEntry> = emptyList(),
    val serverTimeOffsetMillis: Long = 0L,
    val nextCheckAtEpochMillis: Long = 0L,
)

data class AppUpdateNotice(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val title: String,
    val message: String,
    val updateUrl: String,
    val isRequired: Boolean,
)

data class AppAnnouncement(
    val id: Long,
    val announcementKey: String,
    val title: String,
    val message: String,
    val displayType: AppAnnouncementDisplayType,
)

sealed interface SupabaseConnectionState {
    data object Disabled : SupabaseConnectionState
    data object Connecting : SupabaseConnectionState
    data class Connected(val anonymousUserId: String) : SupabaseConnectionState
    data class Failed(val message: String) : SupabaseConnectionState
}

internal const val MAX_SUPPORTED_RANKING_LEVEL = 10_000L
internal const val RANKING_SYNC_COOLDOWN_MILLIS = 5L * 60L * 1_000L

internal fun isRankingSyncAllowed(lastSuccessfulAt: Long, now: Long): Boolean =
    lastSuccessfulAt <= 0L || now < lastSuccessfulAt ||
        now - lastSuccessfulAt >= RANKING_SYNC_COOLDOWN_MILLIS

internal fun appVersionAllowsAnnouncement(updateNotice: AppUpdateNotice?): Boolean =
    updateNotice?.isRequired != true

internal fun shouldReplaceAnonymousSession(
    statusCode: Int,
    responseBody: String,
): Boolean {
    if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) return true
    if (statusCode !in setOf(400, 403, 404)) return false
    val normalized = responseBody.lowercase(Locale.ROOT)
    return listOf(
        "user_not_found",
        "session_not_found",
        "refresh_token_not_found",
        "invalid_refresh_token",
        "invalid_grant",
        "bad_jwt",
        "user from sub claim",
        "session from session_id claim",
    ).any(normalized::contains)
}

private fun isDeletedOrRevokedAnonymousIdentity(responseBody: String): Boolean {
    val normalized = responseBody.lowercase(Locale.ROOT)
    return listOf(
        "user_not_found",
        "session_not_found",
        "user from sub claim",
        "session from session_id claim",
    ).any(normalized::contains)
}

/**
 * Exact displayed-combat-power ceiling for the current formula at each level. All classes share
 * the same 70/30 weighted-stat normalization and equipment generation ceiling.
 */
internal fun maximumAcceptedRankingCombatPower(level: Long): Long {
    if (level !in 1L..MAX_SUPPORTED_RANKING_LEVEL) return 0L

    val equipmentBenchmark = 1L + (level - 1L) * 5L
    val maximumShopPower = equipmentBenchmark + 13L
    val mythicSourcePower = (equipmentBenchmark - 10L).coerceAtLeast(0L) + 30L
    val guaranteedMythicPower = ceilingPercent(maximumShopPower, 105L)
    val maximumEquipmentPower = maxOf(mythicSourcePower, guaranteedMythicPower) + 11L
    val maximumStatPower = roundedPercent(equipmentBenchmark, 135L)
    return maximumStatPower + maximumEquipmentPower
}

private fun ceilingPercent(value: Long, percent: Long): Long =
    (value * percent + 99L) / 100L

private fun roundedPercent(value: Long, percent: Long): Long =
    (value * percent + 50L) / 100L

class SupabaseGameService(
    context: Context,
    private val repository: SimpleGameRepository,
    private val sessionLoggingEnabled: () -> Boolean = { SessionLogRemotePolicy.DEFAULT_ENABLED },
    internal val rankingRefreshPolicy: StateFlow<RankingRefreshPolicy> =
        MutableStateFlow(RankingRefreshPolicy()),
    private val trustedRankingNowEpochMillis: () -> Long? = { null },
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val authMutex = Mutex()
    private val rankingMutex = Mutex()
    private val rankingSyncMutex = Mutex()
    private val arenaRankingMutex = Mutex()
    private val arenaRankingSyncMutex = Mutex()
    private val sharedPlayerSyncMutex = Mutex()
    private val playerNetworkProfileQueueMutex = Mutex()
    private val sessionLogMutex = Mutex()
    private val rankingQueueLock = Any()
    private val arenaRankingQueueLock = Any()
    private val playerNetworkProfileCapabilityLock = Any()
    private val lifecycleStateLock = Any()
    private val sessionLogLifecycle = SessionLogLifecycle()
    private val sessionQueueLock = Any()
    private val announcementDisplayStore = AppAnnouncementDisplayStore(context)
    private var lastRankingFetchAttemptElapsedRealtime: Long? = null
    private var rankingClock: DailyRankingClock? = null
    private val rankingResponseGate = DailyRankingResponseGate(rankingQueueLock)
    private var lastArenaRankingFetchAttemptElapsedRealtime: Long? = null
    private var arenaRankingClock: DailyRankingClock? = null
    private val arenaRankingResponseGate = DailyRankingResponseGate(arenaRankingQueueLock)
    private var sessionValidatedThisProcess = false
    private var lastSessionValidationAtEpochMillis = 0L
    private var playerNetworkProfileCapabilityIdentity: String? = null
    private var playerNetworkProfileCapability = PlayerNetworkProfileCapability.UNKNOWN
    private var playerNetworkProfileLegacyDetectedAtEpochMillis = 0L
    private var playerNetworkProfilePendingUnifiedPayloadHash: String? = null
    private var playerNetworkProfilePendingRankingPayload: String? = null
    private var playerNetworkProfileCommittedLegacyRankingPayload: String? = null
    private val _rankingSnapshot = MutableStateFlow<RemoteRankingSnapshot?>(null)
    val rankingSnapshot: StateFlow<RemoteRankingSnapshot?> = _rankingSnapshot.asStateFlow()
    private val _rankingError = MutableStateFlow<String?>(null)
    val rankingError: StateFlow<String?> = _rankingError.asStateFlow()
    private val _arenaRankingSnapshot = MutableStateFlow<RemoteArenaRankingSnapshot?>(null)
    val arenaRankingSnapshot: StateFlow<RemoteArenaRankingSnapshot?> = _arenaRankingSnapshot.asStateFlow()
    private val _arenaRankingError = MutableStateFlow<String?>(null)
    val arenaRankingError: StateFlow<String?> = _arenaRankingError.asStateFlow()
    private val _connectionState = MutableStateFlow<SupabaseConnectionState>(
        if (isConfigured) SupabaseConnectionState.Connecting else SupabaseConnectionState.Disabled,
    )
    val connectionState: StateFlow<SupabaseConnectionState> = _connectionState.asStateFlow()

    private val isConfigured: Boolean
        get() = BuildConfig.REMOTE_SERVICES_ENABLED &&
            !BuildConfig.DEBUG && BuildConfig.SUPABASE_URL.startsWith("https://") &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    internal val sharedPlayerRemoteEnabled: Boolean
        get() = isSharedPlayerRemoteEnabled(
            remoteServicesEnabled = BuildConfig.REMOTE_SERVICES_ENABLED,
            debugBuild = BuildConfig.DEBUG,
            adventureSystemEnabled = BuildConfig.ADVENTURE_SYSTEM_ENABLED,
            featureEnabled = BuildConfig.SHARED_PLAYER_SYNC_ENABLED,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )

    internal val arenaRankingRemoteEnabled: Boolean
        get() = isConfigured && BuildConfig.SHARED_PLAYER_SYNC_ENABLED &&
            BuildConfig.ARENA_SERVER_MATCHING_ENABLED

    internal val authenticatedSharedPlayerApi: AuthenticatedSharedPlayerApi by lazy {
        if (!sharedPlayerRemoteEnabled) {
            DisabledSharedPlayerApi
        } else {
            SupabaseAuthenticatedSharedPlayerApi(
                transport = object : AuthenticatedSharedPlayerRpcTransport {
                override suspend fun captureSessionIdentity(): String =
                    ensureSession(forceValidation = true).userId

                override fun isSessionIdentityCurrent(identity: String): Boolean =
                    readSession()?.userId == identity

                override fun isPlayerNetworkProfileCurrent(
                    requestBody: String,
                    nowEpochMillis: Long,
                ): Boolean = isUnifiedPlayerNetworkProfileCurrent(requestBody, nowEpochMillis)

                override fun shouldPublishPlayerNetworkProfile(
                    requestBody: String,
                    nowEpochMillis: Long,
                ): Boolean = shouldPublishUnifiedPlayerNetworkProfile(requestBody, nowEpochMillis)

                override suspend fun syncPlayerNetworkProfile(requestBody: String): String? =
                    syncUnifiedPlayerNetworkProfile(requestBody)

                override suspend fun syncPublicPlayerSnapshots(requestBody: String): String =
                    syncSharedPlayerSnapshots(requestBody)

                override suspend fun getDailyPublicPlayerRoster(requestBody: String): String {
                    check(sharedPlayerRemoteEnabled) { "Shared-player transport is disabled" }
                    val session = ensureSession(forceValidation = true)
                    val identity = session.userId
                    val response = request(
                        path = "/rest/v1/rpc/get_daily_public_player_roster",
                        method = "POST",
                        accessToken = session.accessToken,
                        body = requestBody,
                        maxResponseBytes = MAX_DAILY_ROSTER_RESPONSE_BYTES,
                    )
                    check(readSession()?.userId == identity) {
                        "Shared-player account changed during roster request"
                    }
                    return response
                }
                },
                onUnifiedRankingSynced = ::markUnifiedRankingSynced,
            )
        }
    }

    private val isSessionLoggingAllowed: Boolean
        get() = SessionLogRemotePolicy.allowed(isConfigured, sessionLoggingEnabled())

    suspend fun initialize() {
        if (!isConfigured) return
        recoverLegacyBackgroundSession()
        val session = try {
            ensureSession(forceValidation = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _connectionState.value = SupabaseConnectionState.Failed(error.userMessage())
            return
        }
        _connectionState.value = SupabaseConnectionState.Connected(session.userId)
        runCatching { upsertUserProfile(ensureSession(forceValidation = true)) }
            .onFailure { Log.w(TAG, "Profile refresh failed", it) }
        runCatching { flushPendingSessionLogs(ensureSession(forceValidation = true)) }
            .onFailure { Log.w(TAG, "Pending session log upload failed", it) }
    }

    /**
     * Reads the HTTPS response Date header without requiring a database migration or user row.
     * The value is only a candidate: AlarmQuestApplication and SimpleGameRepository coordinate
     * state reconciliation before it is installed as the active game clock anchor.
     */
    suspend fun fetchServerEpochMillis(): Result<Long?> = try {
        if (!isConfigured) {
            Result.success(null)
        } else {
            var observedServerEpochMillis: Long? = null
            request(
                path = "/rest/v1/app_updates?select=platform&limit=1",
                method = "GET",
                accessToken = null,
                connectTimeoutMillis = SERVER_TIME_CONNECT_TIMEOUT_MILLIS,
                readTimeoutMillis = SERVER_TIME_READ_TIMEOUT_MILLIS,
                requestTimeoutMillis = SERVER_TIME_REQUEST_TIMEOUT_MILLIS,
                onSuccessfulServerDate = { serverEpochMillis ->
                    if (serverEpochMillis >= MIN_TRUSTED_SERVER_EPOCH_MILLIS) {
                        observedServerEpochMillis = serverEpochMillis
                    }
                },
            )
            Result.success(
                observedServerEpochMillis
                    ?: throw IOException("서버 시각 응답을 확인할 수 없습니다"),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    suspend fun validateAnonymousSessionForForeground() {
        if (!isConfigured) return
        try {
            ensureSession(forceValidation = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (_connectionState.value !is SupabaseConnectionState.Connected) {
                _connectionState.value = SupabaseConnectionState.Failed(error.userMessage())
            }
            Log.w(TAG, "Anonymous session validation failed on foreground", error)
        }
    }

    suspend fun checkForAppUpdate(): Result<AppUpdateNotice?> = runCatching {
        if (!isConfigured) return@runCatching null
        val session = ensureSession()
        val rows = json.decodeFromString<List<AppUpdateRow>>(
            request(
                path = "/rest/v1/app_updates" +
                    "?select=latest_version_code,latest_version_name,minimum_supported_version_code," +
                    "title,message,update_url,force_update" +
                    "&platform=eq.android&enabled=eq.true&limit=1",
                method = "GET",
                accessToken = session.accessToken,
            ),
        )
        val policy = rows.singleOrNull() ?: return@runCatching null
        if (BuildConfig.VERSION_CODE >= policy.latestVersionCode) return@runCatching null
        AppUpdateNotice(
            latestVersionCode = policy.latestVersionCode,
            latestVersionName = policy.latestVersionName,
            title = policy.title.ifBlank { "새 버전이 준비되었습니다" },
            message = policy.message.ifBlank { "더 안정적인 모험을 위해 앱을 업데이트해 주세요." },
            updateUrl = policy.updateUrl,
            isRequired = policy.forceUpdate || BuildConfig.VERSION_CODE < policy.minimumSupportedVersionCode,
        )
    }

    suspend fun fetchAppAnnouncement(language: AppLanguage): Result<AppAnnouncement?> = runCatching {
        if (!isConfigured) return@runCatching null
        val session = ensureSession()
        val rows = json.decodeFromString<List<AppAnnouncementRow>>(
            request(
                path = "/rest/v1/app_announcements" +
                    "?select=id,announcement_key,title,message,display_type" +
                    "&platform=eq.android" +
                    "&language_code=eq.${language.languageTag}" +
                    "&minimum_version_code=lte.${BuildConfig.VERSION_CODE}" +
                    "&or=(maximum_version_code.is.null," +
                    "maximum_version_code.gte.${BuildConfig.VERSION_CODE})" +
                    "&order=priority.desc,starts_at.desc,id.desc&limit=20",
                method = "GET",
                accessToken = session.accessToken,
            ),
        )
        rows.asSequence()
            .mapNotNull(AppAnnouncementRow::toAnnouncement)
            .firstOrNull { announcementDisplayStore.shouldDisplay(it) }
    }

    fun markAppAnnouncementDisplayed(announcement: AppAnnouncement) {
        announcementDisplayStore.markDisplayed(announcement)
    }

    suspend fun fetchRanking(
        characterId: String,
        forceRefresh: Boolean = false,
    ): Result<RemoteRankingSnapshot> = rankingMutex.withLock {
        val now = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val (cacheIdentity, cachedSnapshot) = synchronized(rankingQueueLock) {
            val identity = rankingResponseGate.capture(readSession()?.userId)
            val cached = _rankingSnapshot.value?.forCharacter(characterId) ?: readRankingCache(characterId)
            if (cached != null) _rankingSnapshot.value = cached
            identity to cached
        }
        val serverNow = resolveRankingNowEpochMillis(
            processClock = rankingClock,
            elapsedRealtimeMillis = elapsedNow,
            trustedGameEpochMillis = runCatching(trustedRankingNowEpochMillis).getOrNull(),
            wallClockEpochMillis = now,
            savedServerOffsetMillis = cachedSnapshot?.serverTimeOffsetMillis ?: 0L,
        )
        if (isDailyRankingCacheReusable(cachedSnapshot, serverNow, forceRefresh, rankingRefreshPolicy.value)) {
            val cachedResult = rankingResponseGate.withCurrentIdentity(cacheIdentity, { readSession()?.userId }) {
                // A failed refresh keeps its warning throughout the stale-cache retry window.
                val cached = checkNotNull(cachedSnapshot)
                if (serverNow < cached.nextSettlementAtEpochMillis) _rankingError.value = null
                Result.success(cached)
            } ?: return@withLock Result.failure(IOException("랭킹 계정이 변경되어 다시 확인합니다"))
            return@withLock cachedResult
        }
        val lastAttempt = lastRankingFetchAttemptElapsedRealtime
        if (lastAttempt != null && elapsedNow - lastAttempt in 0L until DAILY_RANKING_RETRY_MILLIS) {
            if (cachedSnapshot != null) return@withLock Result.success(cachedSnapshot)
            return@withLock Result.failure(IOException("랭킹 재시도 대기 중입니다"))
        }

        val started = rankingResponseGate.withCurrentIdentity(cacheIdentity, { readSession()?.userId }) {
            _rankingError.value = null
            lastRankingFetchAttemptElapsedRealtime = elapsedNow
            true
        } ?: false
        if (!started) return@withLock Result.failure(IOException("랭킹 계정이 변경되어 다시 확인합니다"))
        Log.d(TAG, "Daily ranking fetch started")
        var requestIdentity = cacheIdentity
        try {
            require(isConfigured) { "Supabase 연결 정보가 설정되지 않았습니다" }
            val session = ensureSession()
            // Session replacement clears the previous identity's cache, including all own rows.
            requestIdentity = rankingResponseGate.capture(session.userId)
            val requestCache = cachedSnapshot.takeIf { cacheIdentity == requestIdentity }
            val response = json.decodeFromString<DailyLeaderboardResponse>(
                request(
                    path = "/rest/v1/rpc/get_daily_leaderboard",
                    method = "POST",
                    accessToken = session.accessToken,
                    body = buildJsonObject {
                        requestCache?.snapshotId?.takeIf(String::isNotBlank)?.let {
                            put("p_known_snapshot_id", it)
                        }
                    }.toString(),
                    maxResponseBytes = MAX_DAILY_RANKING_RESPONSE_BYTES,
                ),
            )
            val receivedAt = System.currentTimeMillis()
            val snapshot = applyDailyRankingResponse(response, requestCache, characterId, receivedAt)
            val accepted = rankingResponseGate.withCurrentIdentity(requestIdentity, { readSession()?.userId }) {
                rankingClock = DailyRankingClock(response.serverNowEpochMillis, SystemClock.elapsedRealtime())
                lastRankingFetchAttemptElapsedRealtime = null
                writeRankingCache(snapshot)
                _rankingSnapshot.value = snapshot
                _rankingError.value = null
                true
            } ?: false
            if (!accepted) return@withLock Result.failure(IOException("랭킹 계정이 변경되어 다시 확인합니다"))
            if (requestCache?.snapshotId == snapshot.snapshotId) {
                return@withLock Result.success(snapshot)
            }
            Log.d(
                TAG,
                "Ranking fetch succeeded: entries=${snapshot.entries.size}, " +
                    "total=${snapshot.totalParticipants}",
            )
            Result.success(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Ranking fetch failed", error)
            rankingResponseGate.withCurrentIdentity(requestIdentity, { readSession()?.userId }) {
                lastRankingFetchAttemptElapsedRealtime = SystemClock.elapsedRealtime()
                _rankingError.value = error.userMessage()
                // Keep the last published day available offline; do not alter its observation time.
                val fallback = cachedSnapshot.takeIf { cacheIdentity == requestIdentity }
                if (fallback != null) {
                    val failureElapsedNow = SystemClock.elapsedRealtime()
                    val failureServerNow = resolveRankingNowEpochMillis(
                        processClock = rankingClock,
                        elapsedRealtimeMillis = failureElapsedNow,
                        trustedGameEpochMillis = runCatching(trustedRankingNowEpochMillis).getOrNull(),
                        wallClockEpochMillis = System.currentTimeMillis(),
                        savedServerOffsetMillis = fallback.serverTimeOffsetMillis,
                    )
                    val retry = fallback.copy(
                        nextCheckAtEpochMillis = saturatingAddRankingTime(
                            failureServerNow,
                            DAILY_RANKING_RETRY_MILLIS,
                        ),
                    )
                    _rankingSnapshot.value = retry
                    writeRankingCache(retry)
                    Result.success(retry)
                } else Result.failure(error)
            } ?: Result.failure(error)
        }
    }

    suspend fun fetchArenaRanking(
        characterId: String,
    ): Result<RemoteArenaRankingSnapshot> = arenaRankingMutex.withLock {
        val now = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val (cacheIdentity, cachedSnapshot) = synchronized(arenaRankingQueueLock) {
            val identity = arenaRankingResponseGate.capture(readSession()?.userId)
            val cached = _arenaRankingSnapshot.value?.forCharacter(characterId)
                ?: readArenaRankingCache(characterId)
            if (cached != null) _arenaRankingSnapshot.value = cached
            identity to cached
        }
        val serverNow = resolveRankingNowEpochMillis(
            processClock = arenaRankingClock,
            elapsedRealtimeMillis = elapsedNow,
            trustedGameEpochMillis = runCatching(trustedRankingNowEpochMillis).getOrNull(),
            wallClockEpochMillis = now,
            savedServerOffsetMillis = cachedSnapshot?.serverTimeOffsetMillis ?: 0L,
        )
        if (isDailyArenaRankingCacheReusable(cachedSnapshot, serverNow, rankingRefreshPolicy.value)) {
            val cachedResult = arenaRankingResponseGate.withCurrentIdentity(
                cacheIdentity,
                { readSession()?.userId },
            ) {
                val cached = checkNotNull(cachedSnapshot)
                if (serverNow < cached.nextSettlementAtEpochMillis) _arenaRankingError.value = null
                Result.success(cached)
            } ?: return@withLock Result.failure(IOException("결투장 랭킹 계정이 변경되어 다시 확인합니다"))
            return@withLock cachedResult
        }
        val lastAttempt = lastArenaRankingFetchAttemptElapsedRealtime
        if (lastAttempt != null && elapsedNow - lastAttempt in 0L until DAILY_RANKING_RETRY_MILLIS) {
            if (cachedSnapshot != null) return@withLock Result.success(cachedSnapshot)
            return@withLock Result.failure(IOException("결투장 랭킹 재시도 대기 중입니다"))
        }
        val started = arenaRankingResponseGate.withCurrentIdentity(
            cacheIdentity,
            { readSession()?.userId },
        ) {
            _arenaRankingError.value = null
            lastArenaRankingFetchAttemptElapsedRealtime = elapsedNow
            true
        } ?: false
        if (!started) {
            return@withLock Result.failure(IOException("결투장 랭킹 계정이 변경되어 다시 확인합니다"))
        }
        var requestIdentity = cacheIdentity
        try {
            require(arenaRankingRemoteEnabled) { "결투장 랭킹 서버가 아직 활성화되지 않았습니다" }
            val session = ensureSession()
            requestIdentity = arenaRankingResponseGate.capture(session.userId)
            val requestCache = cachedSnapshot.takeIf { cacheIdentity == requestIdentity }
            val response = json.decodeFromString<DailyArenaLeaderboardResponse>(
                request(
                    path = "/rest/v1/rpc/get_daily_arena_leaderboard",
                    method = "POST",
                    accessToken = session.accessToken,
                    body = buildJsonObject {
                        requestCache?.snapshotId?.takeIf(String::isNotBlank)?.let {
                            put("p_known_snapshot_id", it)
                        }
                    }.toString(),
                    maxResponseBytes = MAX_DAILY_RANKING_RESPONSE_BYTES,
                ),
            )
            val receivedAt = System.currentTimeMillis()
            val snapshot = applyDailyArenaRankingResponse(
                response = response,
                cachedSnapshot = requestCache,
                characterId = characterId,
                receivedAtEpochMillis = receivedAt,
            )
            val accepted = arenaRankingResponseGate.withCurrentIdentity(
                requestIdentity,
                { readSession()?.userId },
            ) {
                arenaRankingClock = DailyRankingClock(
                    response.serverNowEpochMillis,
                    SystemClock.elapsedRealtime(),
                )
                lastArenaRankingFetchAttemptElapsedRealtime = null
                writeArenaRankingCache(snapshot)
                _arenaRankingSnapshot.value = snapshot
                _arenaRankingError.value = null
                true
            } ?: false
            if (!accepted) {
                return@withLock Result.failure(IOException("결투장 랭킹 계정이 변경되어 다시 확인합니다"))
            }
            Result.success(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Arena ranking fetch failed", error)
            arenaRankingResponseGate.withCurrentIdentity(
                requestIdentity,
                { readSession()?.userId },
            ) {
                lastArenaRankingFetchAttemptElapsedRealtime = SystemClock.elapsedRealtime()
                _arenaRankingError.value = error.userMessage()
                val fallback = cachedSnapshot.takeIf { cacheIdentity == requestIdentity }
                if (fallback != null) {
                    val failureElapsedNow = SystemClock.elapsedRealtime()
                    val failureServerNow = resolveRankingNowEpochMillis(
                        processClock = arenaRankingClock,
                        elapsedRealtimeMillis = failureElapsedNow,
                        trustedGameEpochMillis = runCatching(trustedRankingNowEpochMillis).getOrNull(),
                        wallClockEpochMillis = System.currentTimeMillis(),
                        savedServerOffsetMillis = fallback.serverTimeOffsetMillis,
                    )
                    val retry = fallback.copy(
                        nextCheckAtEpochMillis = saturatingAddRankingTime(
                            failureServerNow,
                            DAILY_RANKING_RETRY_MILLIS,
                        ),
                    )
                    _arenaRankingSnapshot.value = retry
                    writeArenaRankingCache(retry)
                    Result.success(retry)
                } else Result.failure(error)
            } ?: Result.failure(error)
        }
    }

    /**
     * Supplies a monotonic wait for the open arena-ranking screen. A live response anchors this to
     * server time; a restored cache prefers the application's shared trusted clock and uses its
     * saved offset only as a compatibility fallback for isolated callers.
     */
    internal fun nextArenaRankingRefreshDelayMillis(
        snapshot: RemoteArenaRankingSnapshot,
    ): Long? {
        val elapsedNow = SystemClock.elapsedRealtime()
        val trustedNow = arenaRankingClock?.now(elapsedNow) ?: resolveRankingNowEpochMillis(
            processClock = null,
            elapsedRealtimeMillis = elapsedNow,
            trustedGameEpochMillis = runCatching(trustedRankingNowEpochMillis).getOrNull(),
            wallClockEpochMillis = System.currentTimeMillis(),
            savedServerOffsetMillis = snapshot.serverTimeOffsetMillis,
        ).also { restoredNow ->
            arenaRankingClock = DailyRankingClock(restoredNow, elapsedNow)
        }
        return dailyArenaRankingRefreshDelayMillis(snapshot, trustedNow, rankingRefreshPolicy.value)
    }

    fun queueRankingSync(snapshot: GameSnapshot) {
        if (!isConfigured || !snapshot.ready) return
        val entries = mutableListOf<RankingSyncEntry>()
        for (character in snapshot.characters.sortedBy { it.slotId }) {
            val state = character.state
            if (state.hero.level < MIN_RANKING_LEVEL || state.rankingCharacterId.isBlank()) continue
            val combatPower = repository.displayCombatPower(state)
            val maximumAcceptedPower = maximumAcceptedRankingCombatPower(state.hero.level)
            if (
                state.hero.level > MAX_SUPPORTED_RANKING_LEVEL ||
                combatPower < 0L ||
                combatPower > maximumAcceptedPower
            ) {
                Log.w(
                    TAG,
                    "Ranking sync rejected locally: level=${state.hero.level}, " +
                        "power=$combatPower, maximum=$maximumAcceptedPower",
                )
                return
            }
            entries += RankingSyncEntry(
                characterId = state.rankingCharacterId,
                slotId = character.slotId,
                displayName = canonicalRankingDisplayName(state.hero.name),
                heroClass = state.hero.heroClass.name,
                level = state.hero.level,
                combatPower = combatPower,
            )
        }
        val encoded = json.encodeToString(entries)
        queueRankingPayload(encoded)
    }

    private fun queueRankingPayload(encoded: String): Boolean =
        synchronized(rankingQueueLock) {
            val pending = preferences.getString(PENDING_RANKING_SYNC_KEY, null)
            val nextPending = rankingPendingPayloadAfterQueue(
                lastSuccessfulPayload = preferences.getString(LAST_RANKING_SYNC_KEY, null),
                pendingPayload = pending,
                candidatePayload = encoded,
            )
            if (nextPending == pending) return@synchronized true
            val editor = preferences.edit()
            if (nextPending == null) editor.remove(PENDING_RANKING_SYNC_KEY)
            else editor.putString(PENDING_RANKING_SYNC_KEY, nextPending)
            editor.commit()
        }

    suspend fun flushPendingRanking(
        force: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isConfigured) return false
        return rankingSyncMutex.withLock {
            try {
                val (encoded, lastSuccessfulAt) = synchronized(rankingQueueLock) {
                    Pair(
                        preferences.getString(PENDING_RANKING_SYNC_KEY, null),
                        preferences.getLong(LAST_RANKING_SYNC_AT_KEY, 0L),
                    )
                }
                if (encoded == null) return@withLock true
                // Once this account has committed the unified contract, changed ranking rows must
                // travel with the matching shared snapshot. A standalone write would be rejected
                // by the server's exact-roster check and only add WAL/egress pressure.
                if (!shouldAttemptLegacyRankingWrite(
                        sharedPlayerRemoteEnabled = sharedPlayerRemoteEnabled,
                        evidence = playerNetworkProfileCapabilityForCurrentSession(),
                        pendingPayload = encoded,
                    )
                ) return@withLock false
                if (!force && !isRankingSyncAllowed(lastSuccessfulAt, now)) return@withLock false
                val entries = json.decodeFromString<List<RankingSyncEntry>>(encoded)
                val session = ensureSession(forceValidation = true)
                request(
                    path = "/rest/v1/rpc/sync_ranking_entries",
                    method = "POST",
                    accessToken = session.accessToken,
                    body = "{\"p_entries\":$encoded}",
                )
                synchronized(rankingQueueLock) {
                    val editor = preferences.edit()
                        .putString(LAST_RANKING_SYNC_KEY, json.encodeToString(entries))
                        .putLong(LAST_RANKING_SYNC_AT_KEY, now)
                    if (preferences.getString(PENDING_RANKING_SYNC_KEY, null) == encoded) {
                        editor.remove(PENDING_RANKING_SYNC_KEY)
                    }
                    editor.commit()
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "Pending ranking upload failed", error)
                false
            }
        }
    }

    /**
     * Keeps the exact ten-match placement aggregate until the server has had a chance to bind the
     * character. Later offline results are retained separately so they cannot overwrite the only
     * payload that can register a new or replacement UUID.
     */
    fun queueArenaRankingSync(standing: ArenaRankingLocalStanding): Boolean {
        if (!arenaRankingRemoteEnabled || !isValidArenaRankingStanding(standing)) return false
        return synchronized(arenaRankingQueueLock) {
            val current = readArenaRankingQueueState()
            val handled = readArenaRankingStandings(LAST_ARENA_RANKING_SYNC_KEY)
            val next = arenaRankingQueueAfterStanding(current, handled, standing)
            if (next == current) return@synchronized true
            writeArenaRankingQueueState(next)
        }
    }

    /** Rehydrates match-ten evidence saved atomically with the local battle result. */
    fun rememberArenaRankingPlacement(standing: ArenaRankingLocalStanding): Boolean {
        if (!arenaRankingRemoteEnabled || !isValidArenaRankingStanding(standing) ||
            standing.completedBattles != ARENA_RANKING_PLACEMENT_REQUIRED
        ) return false
        return synchronized(arenaRankingQueueLock) {
            val current = readArenaRankingQueueState()
            val next = rememberArenaRankingPlacement(current, standing)
            if (next == current) true else writeArenaRankingQueueState(next)
        }
    }

    /**
     * The shared profile must have committed first because the server owns name, class, level and
     * character ownership. A missing profile therefore leaves this queue intact and performs no
     * arena standing write.
     */
    suspend fun flushPendingArenaRanking(
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!arenaRankingRemoteEnabled) return false
        return arenaRankingSyncMutex.withLock {
            var attemptedUserId: String? = null
            try {
                val pending = synchronized(arenaRankingQueueLock) {
                    readArenaRankingQueueState().pending
                }
                val standing = pending.minByOrNull(ArenaRankingLocalStanding::observedAtEpochMillis)
                    ?: return@withLock true
                val lastSuccessfulAt = synchronized(arenaRankingQueueLock) {
                    preferences.getLong(LAST_ARENA_RANKING_SYNC_AT_KEY, 0L)
                }
                val retryNotBefore = synchronized(arenaRankingQueueLock) {
                    preferences.getLong(ARENA_RANKING_RETRY_NOT_BEFORE_KEY, 0L)
                }
                val trustedGuardNow = arenaRankingClock?.now(SystemClock.elapsedRealtime()) ?: now
                if (!isArenaRankingSyncAllowed(lastSuccessfulAt, trustedGuardNow, retryNotBefore)) {
                    return@withLock false
                }
                val session = ensureSession(forceValidation = true)
                attemptedUserId = session.userId
                val receipt = readUnifiedPlayerNetworkProfileSyncReceipt()
                val profileMutationPending = preferences.contains(PENDING_PLAYER_NETWORK_PROFILE_KEY)
                if (profileMutationPending ||
                    receipt == null || !isValidPlayerNetworkProfileSyncReceipt(receipt, session.userId)
                ) {
                    return@withLock false
                }
                val response = json.decodeFromString<ArenaRankingSyncResponse>(
                    request(
                        path = "/rest/v1/rpc/sync_arena_ranking_entry",
                        method = "POST",
                        accessToken = session.accessToken,
                        body = buildJsonObject {
                            put("p_character_id", standing.characterId)
                            put("p_score", standing.score)
                            put("p_completed_battles", standing.completedBattles)
                            put("p_wins", standing.wins)
                            put("p_losses", standing.losses)
                            put("p_draws", standing.draws)
                            put("p_rules_version", ARENA_RANKING_RULES_VERSION)
                        }.toString(),
                        maxResponseBytes = MAX_PROFILE_RESPONSE_BYTES,
                    ),
                )
                if (readSession()?.userId != session.userId) return@withLock false
                if (!isTrustedArenaRankingSyncResponse(response)) return@withLock false
                arenaRankingClock = DailyRankingClock(
                    response.serverNowEpochMillis,
                    SystemClock.elapsedRealtime(),
                )
                when (arenaRankingSyncDisposition(response)) {
                    ArenaRankingSyncDisposition.RETRY_PROFILE -> {
                        synchronized(arenaRankingQueueLock) {
                            preferences.edit()
                                // The server could not find a current owned shared projection. A
                                // missing receipt makes the next foreground synchronization
                                // republish 006 before this standing is attempted again.
                                .remove(LAST_UNIFIED_PLAYER_PROFILE_SYNC_RECEIPT_KEY)
                                .putLong(LAST_ARENA_RANKING_SYNC_AT_KEY, response.serverNowEpochMillis)
                                .putLong(
                                    ARENA_RANKING_RETRY_NOT_BEFORE_KEY,
                                    arenaRankingRetryNotBeforeFor(response),
                                )
                                .commit()
                        }
                        return@withLock false
                    }
                    ArenaRankingSyncDisposition.RETRY_LATER -> {
                        synchronized(arenaRankingQueueLock) {
                            preferences.edit()
                                .putLong(LAST_ARENA_RANKING_SYNC_AT_KEY, response.serverNowEpochMillis)
                                .putLong(
                                    ARENA_RANKING_RETRY_NOT_BEFORE_KEY,
                                    arenaRankingRetryNotBeforeFor(response),
                                )
                                .commit()
                        }
                        return@withLock false
                    }
                    ArenaRankingSyncDisposition.RECOVER_PLACEMENT -> {
                        synchronized(arenaRankingQueueLock) {
                            val current = readArenaRankingQueueState()
                            val next = arenaRankingQueueAfterPlacementRequired(current, standing)
                            val handled = readArenaRankingStandings(LAST_ARENA_RANKING_SYNC_KEY)
                            val editor = preferences.edit()
                            if (next == null) {
                                // An install that never observed match ten cannot reconstruct that
                                // signed aggregate. Suppress only this exact impossible payload;
                                // any later durable aggregate remains independently queueable.
                                val nextPending = removeExactArenaRankingPending(current.pending, standing)
                                writeArenaRankingQueueState(
                                    current.copy(pending = nextPending),
                                    editor,
                                )
                                editor.putString(
                                    LAST_ARENA_RANKING_SYNC_KEY,
                                    json.encodeToString(arenaRankingCommittedAfterSuccess(handled, standing)),
                                )
                                editor.putLong(
                                    LAST_ARENA_RANKING_SYNC_AT_KEY,
                                    response.serverNowEpochMillis,
                                ).remove(ARENA_RANKING_RETRY_NOT_BEFORE_KEY)
                            } else {
                                // Preserve the rejected latest aggregate as deferred, and put the
                                // archived ten-match aggregate at the head of this character's lane.
                                writeArenaRankingQueueState(next, editor)
                                editor.remove(LAST_ARENA_RANKING_SYNC_AT_KEY).putLong(
                                    ARENA_RANKING_RETRY_NOT_BEFORE_KEY,
                                    arenaRankingRetryNotBefore(
                                        serverNow = response.serverNowEpochMillis,
                                        retryAfterSeconds = null,
                                        defaultDelayMillis = ARENA_RANKING_PLACEMENT_RECOVERY_DELAY_MILLIS,
                                    ),
                                )
                            }
                            editor.commit()
                        }
                        return@withLock false
                    }
                    ArenaRankingSyncDisposition.SUPPRESS_PAYLOAD -> {
                        synchronized(arenaRankingQueueLock) {
                            val current = readArenaRankingQueueState()
                            val handled = readArenaRankingStandings(LAST_ARENA_RANKING_SYNC_KEY)
                            val next = arenaRankingQueueAfterSuppressed(current, standing)
                            // Remember the rejected aggregate so reopening the screen does not
                            // upload the same permanently impossible payload every fifteen minutes.
                            // A later durable battle changes the aggregate and can queue again.
                            val nextHandled = arenaRankingCommittedAfterSuccess(handled, standing)
                            val editor = preferences.edit()
                                .putString(LAST_ARENA_RANKING_SYNC_KEY, json.encodeToString(nextHandled))
                                .putLong(LAST_ARENA_RANKING_SYNC_AT_KEY, response.serverNowEpochMillis)
                                .remove(ARENA_RANKING_RETRY_NOT_BEFORE_KEY)
                            writeArenaRankingQueueState(next, editor)
                            editor.commit()
                        }
                        return@withLock false
                    }
                    ArenaRankingSyncDisposition.ACCEPTED -> Unit
                }
                synchronized(arenaRankingQueueLock) {
                    val current = readArenaRankingQueueState()
                    val committed = readArenaRankingStandings(LAST_ARENA_RANKING_SYNC_KEY)
                    val next = arenaRankingQueueAfterAccepted(current, standing)
                    val nextCommitted = arenaRankingCommittedAfterSuccess(committed, standing)
                    val editor = preferences.edit()
                        .putString(LAST_ARENA_RANKING_SYNC_KEY, json.encodeToString(nextCommitted))
                        .putLong(LAST_ARENA_RANKING_SYNC_AT_KEY, response.serverNowEpochMillis)
                        .remove(ARENA_RANKING_RETRY_NOT_BEFORE_KEY)
                    writeArenaRankingQueueState(next, editor)
                    editor.commit()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val limited = (error as? SupabaseHttpException)?.let { failure ->
                    decodeArenaRankingRateLimit(failure.statusCode, failure.responseBody)
                }
                if (limited != null && attemptedUserId != null &&
                    readSession()?.userId == attemptedUserId
                ) {
                    arenaRankingClock = DailyRankingClock(
                        limited.serverNowEpochMillis,
                        SystemClock.elapsedRealtime(),
                    )
                    synchronized(arenaRankingQueueLock) {
                        preferences.edit()
                            .putLong(LAST_ARENA_RANKING_SYNC_AT_KEY, limited.serverNowEpochMillis)
                            .putLong(
                                ARENA_RANKING_RETRY_NOT_BEFORE_KEY,
                                arenaRankingRetryNotBefore(
                                    limited.serverNowEpochMillis,
                                    limited.retryAfterSeconds,
                                ),
                            )
                            .commit()
                    }
                }
                Log.w(TAG, "Pending arena ranking upload failed", error)
                false
            }
        }
    }

    /** The unified RPC already committed these ranking rows in the same server transaction. */
    private fun markUnifiedRankingSynced(
        snapshots: List<PublicPlayerSnapshotUpload>,
        serverNowEpochMillis: Long,
    ) {
        require(serverNowEpochMillis > 0L)
        val entries = rankingEntriesForPublicSnapshots(snapshots)
        val encoded = json.encodeToString(entries)
        synchronized(rankingQueueLock) {
            val mutation = unifiedRankingReceiptMutation(
                pendingPayload = preferences.getString(PENDING_RANKING_SYNC_KEY, null),
                unifiedPayload = encoded,
                verifiedServerNowEpochMillis = serverNowEpochMillis,
            )
            val editor = preferences.edit()
                .putString(LAST_RANKING_SYNC_KEY, mutation.lastPayload)
                .putLong(LAST_RANKING_SYNC_AT_KEY, mutation.lastSuccessfulAtEpochMillis)
            if (mutation.pendingPayload == null) {
                editor.remove(PENDING_RANKING_SYNC_KEY)
            }
            check(editor.commit()) { "Unified ranking receipt could not be persisted" }
        }
    }

    /**
     * Persists a complete-roster mutation before attempting the atomic endpoint. Empty input is a
     * real tombstone for a deleted final character. A failed/deferred request stays queued for the
     * next foreground rather than leaking a standalone ranking mutation.
     */
    suspend fun syncPlayerNetworkProfileAfterRosterMutation(snapshot: GameSnapshot) {
        if (!snapshot.ready) return
        if (!sharedPlayerRemoteEnabled) {
            syncRankingNow(snapshot)
            return
        }
        val uploads = buildPublicPlayerSnapshotUploads(snapshot, repository::displayCombatPower)
            ?: run {
                Log.w(TAG, "Complete player-network profile failed local validation")
                return
            }
        val encoded = json.encodeToString(uploads)
        playerNetworkProfileQueueMutex.withLock {
            if (!preferences.edit().putString(PENDING_PLAYER_NETWORK_PROFILE_KEY, encoded).commit()) {
                Log.w(TAG, "Player-network profile mutation could not be queued")
            }
            flushPendingPlayerNetworkProfileLocked()
        }
    }

    /** No pending payload means this path performs no authentication or network work. */
    suspend fun flushPendingPlayerNetworkProfile(
        currentSnapshot: GameSnapshot? = null,
    ): Boolean {
        if (!sharedPlayerRemoteEnabled) return false
        return playerNetworkProfileQueueMutex.withLock {
            if (currentSnapshot != null && !reconcilePendingPlayerNetworkProfileLocked(currentSnapshot)) {
                return@withLock false
            }
            flushPendingPlayerNetworkProfileLocked()
        }
    }

    /**
     * A process can stop after persisting a mutation but before publishing it. Before retrying on
     * foreground, replace that stale complete-roster payload with the current complete roster so
     * an old deletion or profile cannot overwrite newer local state and start a 15-minute defer.
     * With no pending mutation this stays a local no-op; normal receipt logic decides publication.
     */
    private fun reconcilePendingPlayerNetworkProfileLocked(snapshot: GameSnapshot): Boolean {
        val pending = preferences.getString(PENDING_PLAYER_NETWORK_PROFILE_KEY, null)
            ?: return true
        if (!snapshot.ready) return false
        val uploads = buildPublicPlayerSnapshotUploads(snapshot, repository::displayCombatPower)
            ?: return false
        val current = json.encodeToString(uploads)
        val reconciled = pendingPlayerNetworkProfileAfterForegroundReconcile(
            currentPendingPayload = pending,
            currentCompletePayload = current,
        ) ?: return true
        if (reconciled == pending) return true
        return preferences.edit()
            .putString(PENDING_PLAYER_NETWORK_PROFILE_KEY, reconciled)
            .commit()
    }

    private suspend fun flushPendingPlayerNetworkProfileLocked(): Boolean {
        val encoded = preferences.getString(PENDING_PLAYER_NETWORK_PROFILE_KEY, null)
            ?: return true
        val uploads = runCatching {
            json.decodeFromString<List<PublicPlayerSnapshotUpload>>(encoded)
        }.getOrNull()
        if (uploads == null) {
            preferences.edit().remove(PENDING_PLAYER_NETWORK_PROFILE_KEY).commit()
            Log.w(TAG, "Malformed pending player-network profile was quarantined")
            return false
        }
        return try {
            val identity = authenticatedSharedPlayerApi.captureSessionIdentity()
            val publication = authenticatedSharedPlayerApi.syncPublicPlayerSnapshots(uploads)
            check(authenticatedSharedPlayerApi.isSessionIdentityCurrent(identity)) {
                "Shared-player account changed during queued profile publication"
            }
            if (publication == SharedPlayerPublicationMode.LEGACY) {
                val rankingPayload = json.encodeToString(rankingEntriesForPublicSnapshots(uploads))
                if (!queueRankingPayload(rankingPayload)) return false
            }
            val currentPending = preferences.getString(PENDING_PLAYER_NETWORK_PROFILE_KEY, null)
            val remainingPending = pendingPlayerNetworkProfileAfterSuccess(
                currentPendingPayload = currentPending,
                sentPayload = encoded,
            )
            if (remainingPending != currentPending) {
                check(preferences.edit().remove(PENDING_PLAYER_NETWORK_PROFILE_KEY).commit()) {
                    "Committed player-network profile tombstone could not be dequeued"
                }
            }
            if (publication == SharedPlayerPublicationMode.LEGACY) {
                flushPendingRanking(force = true)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Queued player-network profile publication deferred", error)
            false
        }
    }

    suspend fun syncRankingNow(snapshot: GameSnapshot) {
        if (!isConfigured || !snapshot.ready) return
        queueRankingSync(snapshot)
        if (!flushPendingRanking(force = true)) return
        val activeCharacterId = snapshot.state
            ?.rankingCharacterId
            ?.takeIf(String::isNotBlank)
        if (activeCharacterId == null) {
            _rankingSnapshot.value = null
            _rankingError.value = null
        } else {
            fetchRanking(activeCharacterId)
        }
    }

    fun recordAppBackgrounded(
        snapshot: GameSnapshot,
        backgroundedAt: Long = System.currentTimeMillis(),
        reason: String = "background",
    ): Boolean {
        if (!isConfigured) return false
        require(reason in setOf("background", "exit", "replaced"))
        val state = snapshot.state
        val pending = PendingBackgroundSession(
            backgroundedAt = backgroundedAt,
            reason = reason,
            slotId = snapshot.activeSlotId,
            heroLevel = state?.hero?.level,
            combatPower = state?.let(repository::displayCombatPower),
            totalActs = state?.totalActs,
            totalKills = state?.totalKills,
            appVersion = BuildConfig.VERSION_NAME,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        )
        return synchronized(lifecycleStateLock) {
            sessionLogLifecycle.backgrounded(isSessionLoggingAllowed) { enqueueSessionLog(pending.toSessionLog()) }
        }
    }

    fun recordAppForegrounded() {
        if (!isConfigured) return
        synchronized(lifecycleStateLock) {
            recoverLegacyBackgroundSession()
            sessionLogLifecycle.foregrounded()
        }
    }

    private fun recoverLegacyBackgroundSession() {
        if (!isSessionLoggingAllowed) return
        synchronized(lifecycleStateLock) {
            val pending = readPendingBackgroundSession() ?: return
            val eventId = legacyBackgroundSessionEventId(json.encodeToString(pending))
            if (enqueueSessionLog(pending.toSessionLog(eventId))) {
                preferences.edit().remove(PENDING_BACKGROUND_SESSION_KEY).commit()
            }
        }
    }

    suspend fun flushPendingSessionLogs() {
        if (!isSessionLoggingAllowed) return
        recoverLegacyBackgroundSession()
        if (readPendingSessionLogs().isEmpty()) return
        try {
            flushPendingSessionLogs(ensureSession(forceValidation = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Session log upload deferred; keeping durable queue", error)
        }
    }

    private suspend fun flushPendingSessionLogs(session: AuthSession) = sessionLogMutex.withLock {
        while (true) {
            if (!isSessionLoggingAllowed) return@withLock
            val pending = readPendingSessionLogs().firstOrNull() ?: return@withLock
            request(
                path = "/rest/v1/app_session_logs",
                method = "POST",
                accessToken = session.accessToken,
                body = json.encodeToString(pending.toInsert(session.userId)),
                prefer = "return=minimal",
                allowSessionLogDuplicate = true,
            )
            if (!removePendingSessionLog(pending.eventId)) return@withLock
        }
    }

    private fun readPendingBackgroundSession(): PendingBackgroundSession? =
        preferences.getString(PENDING_BACKGROUND_SESSION_KEY, null)?.let { encoded ->
            runCatching { json.decodeFromString<PendingBackgroundSession>(encoded) }.getOrNull()
        }

    private fun enqueueSessionLog(pending: PendingSessionLog): Boolean =
        synchronized(sessionQueueLock) {
            val logs = readPendingSessionLogs()
                .filterNot { it.eventId == pending.eventId }
                .plus(pending)
                .takeLast(MAX_PENDING_SESSION_LOGS)
            writePendingSessionLogs(logs)
        }

    private fun removePendingSessionLog(eventId: String): Boolean =
        synchronized(sessionQueueLock) {
            writePendingSessionLogs(readPendingSessionLogs().filterNot { it.eventId == eventId })
        }

    private fun readPendingSessionLogs(): List<PendingSessionLog> = synchronized(sessionQueueLock) {
        val queued = preferences.getString(PENDING_LOGS_KEY, null)?.let { encoded ->
            runCatching { json.decodeFromString<List<PendingSessionLog>>(encoded) }.getOrNull()
        }.orEmpty()
        val legacy = preferences.getString(PENDING_LOG_KEY, null)?.let { encoded ->
            runCatching { json.decodeFromString<PendingSessionLog>(encoded) }.getOrNull()
        }
        buildList {
            addAll(queued)
            if (legacy != null && queued.none { it.eventId == legacy.eventId }) add(legacy)
        }.takeLast(MAX_PENDING_SESSION_LOGS)
    }

    private fun writePendingSessionLogs(logs: List<PendingSessionLog>): Boolean =
        synchronized(sessionQueueLock) {
            val editor = preferences.edit().remove(PENDING_LOG_KEY)
            if (logs.isEmpty()) {
                editor.remove(PENDING_LOGS_KEY)
            } else {
                editor.putString(PENDING_LOGS_KEY, json.encodeToString(logs))
            }
            editor.commit().also { saved ->
                if (!saved) Log.w(TAG, "Could not persist session log queue")
            }
        }

    private fun readRankingCache(characterId: String): RemoteRankingSnapshot? {
        val encoded = preferences.getString(RANKING_CACHE_KEY, null) ?: return null
        val cache = runCatching { json.decodeFromString<PersistedRankingCache>(encoded) }
            .getOrElse {
                preferences.edit().remove(RANKING_CACHE_KEY).apply()
                return null
            }
        return cache.toSnapshot(characterId).also { snapshot ->
            if (snapshot == null) preferences.edit().remove(RANKING_CACHE_KEY).apply()
        }
    }

    private fun writeRankingCache(snapshot: RemoteRankingSnapshot) {
        val cache = PersistedRankingCache.fromSnapshot(snapshot)
        preferences.edit().putString(RANKING_CACHE_KEY, json.encodeToString(cache)).apply()
    }

    private fun readArenaRankingCache(characterId: String): RemoteArenaRankingSnapshot? {
        val encoded = preferences.getString(ARENA_RANKING_CACHE_KEY, null) ?: return null
        val cache = runCatching { json.decodeFromString<PersistedArenaRankingCache>(encoded) }
            .getOrElse {
                preferences.edit().remove(ARENA_RANKING_CACHE_KEY).apply()
                return null
            }
        return cache.toSnapshot(characterId).also { snapshot ->
            if (snapshot == null) preferences.edit().remove(ARENA_RANKING_CACHE_KEY).apply()
        }
    }

    private fun writeArenaRankingCache(snapshot: RemoteArenaRankingSnapshot) {
        val cache = PersistedArenaRankingCache.fromSnapshot(snapshot)
        preferences.edit().putString(ARENA_RANKING_CACHE_KEY, json.encodeToString(cache)).apply()
    }

    private fun readArenaRankingStandings(key: String): List<ArenaRankingLocalStanding> =
        preferences.getString(key, null)?.let { encoded ->
            runCatching { json.decodeFromString<List<ArenaRankingLocalStanding>>(encoded) }
                .getOrElse {
                    preferences.edit().remove(key).apply()
                    emptyList()
                }
        }.orEmpty().filter(::isValidArenaRankingStanding).take(3)

    private fun readArenaRankingQueueState() = ArenaRankingQueueState(
        pending = readArenaRankingStandings(PENDING_ARENA_RANKING_SYNC_KEY),
    ).let { legacy ->
        preferences.getString(ARENA_RANKING_QUEUE_STATE_KEY, null)?.let { encoded ->
            runCatching { json.decodeFromString<ArenaRankingQueueState>(encoded) }
                .getOrElse {
                    preferences.edit().remove(ARENA_RANKING_QUEUE_STATE_KEY).apply()
                    legacy
                }
        } ?: legacy
    }.normalized()

    private fun writeArenaRankingQueueState(
        state: ArenaRankingQueueState,
        editor: SharedPreferences.Editor? = null,
    ): Boolean {
        val target = editor ?: preferences.edit()
        target.putString(ARENA_RANKING_QUEUE_STATE_KEY, json.encodeToString(state.normalized()))
        // Remove the v1 single-list queue after its contents have been folded into the v2 state.
        target.remove(PENDING_ARENA_RANKING_SYNC_KEY)
        return if (editor == null) target.commit() else true
    }

    /**
     * Authenticated fixed-path publication with a persistent client pressure guard. The database
     * RPC repeats rate limiting and validation; this layer mainly avoids needless foreground WAL.
     */
    private suspend fun syncUnifiedPlayerNetworkProfile(requestBody: String): String? =
        sharedPlayerSyncMutex.withLock {
            check(sharedPlayerRemoteEnabled) { "Shared-player transport is disabled" }
            val session = ensureSession(forceValidation = true)
            val identity = session.userId
            val now = System.currentTimeMillis()
            val payloadHash = sha256Hex(requestBody)
            val expected = playerNetworkProfilePayloadSummary(requestBody, json)
            val rankingPayload = json.encodeToString(
                rankingEntriesForPublicSnapshots(playerNetworkProfileUploads(requestBody, json)),
            )
            val receipt = readUnifiedPlayerNetworkProfileSyncReceipt()
            if (receipt != null && isValidPlayerNetworkProfileSyncReceipt(receipt, identity)) {
                // A validated unified success is stronger evidence than any stale negative-cache
                // key left by a crash or failed preferences cleanup.
                preferences.edit().remove(LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY).apply()
            } else {
                val legacyCapability = readActiveLegacyPlayerNetworkProfileCapability(identity, now)
                if (legacyCapability != null) {
                    noteLegacyPlayerNetworkProfileRoute(
                        identity = identity,
                        detectedAtEpochMillis = legacyCapability.detectedAtEpochMillis,
                        unifiedPayloadHash = payloadHash,
                        rankingPayload = rankingPayload,
                    )
                    // Negative capability cache: proceed directly to the legacy shared endpoint.
                    return@withLock null
                }
            }
            when (playerNetworkProfileSyncDecision(receipt, identity, payloadHash, now)) {
                SharedPlayerSyncDecision.SKIP_UNCHANGED -> {
                    updatePlayerNetworkProfileCapability(identity, PlayerNetworkProfileCapability.UNIFIED)
                    return@withLock deduplicatedPlayerNetworkProfileResponse(
                        checkNotNull(receipt),
                        expected,
                    )
                }
                SharedPlayerSyncDecision.DEFER_CHANGED -> {
                    updatePlayerNetworkProfileCapability(identity, PlayerNetworkProfileCapability.UNIFIED)
                    throw IOException("공개 영웅 정보 변경은 잠시 후 동기화됩니다")
                }
                SharedPlayerSyncDecision.SEND -> Unit
            }
            // A fresh attempt closes the legacy ranking gate. Only an exact missing-function
            // response followed by a successful legacy shared write may reopen it.
            updatePlayerNetworkProfileCapability(identity, PlayerNetworkProfileCapability.UNKNOWN)
            val response = try {
                request(
                    path = "/rest/v1/rpc/sync_player_network_profile",
                    method = "POST",
                    accessToken = session.accessToken,
                    body = requestBody,
                    maxResponseBytes = MAX_PROFILE_RESPONSE_BYTES,
                )
            } catch (error: SupabaseHttpException) {
                if (isMissingPlayerNetworkProfileRpc(error.statusCode, error.responseBody)) {
                    recordLegacyPlayerNetworkProfileMissing(
                        identity = identity,
                        detectedAtEpochMillis = now,
                        unifiedPayloadHash = payloadHash,
                        rankingPayload = rankingPayload,
                    )
                    return@withLock null
                }
                throw error
            }
            val verified = verifiedPlayerNetworkProfileResponse(response, expected, json)
            check(readSession()?.userId == identity) {
                "Shared-player account changed during unified publication"
            }
            val receiptSaved = preferences.edit()
                .putString(
                    LAST_UNIFIED_PLAYER_PROFILE_SYNC_RECEIPT_KEY,
                    json.encodeToString(PlayerNetworkProfileSyncReceipt(
                    userId = identity,
                    payloadHash = payloadHash,
                    syncedAtEpochMillis = now,
                    serverNowEpochMillis = verified.serverNowEpochMillis,
                    )),
                )
                .remove(LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY)
                .commit()
            if (!receiptSaved) Log.w(TAG, "Unified player-profile sync receipt could not be persisted")
            updatePlayerNetworkProfileCapability(identity, PlayerNetworkProfileCapability.UNIFIED)
            response
        }

    private suspend fun syncSharedPlayerSnapshots(requestBody: String): String =
        sharedPlayerSyncMutex.withLock {
            check(sharedPlayerRemoteEnabled) { "Shared-player transport is disabled" }
            val session = ensureSession(forceValidation = true)
            val identity = session.userId
            val now = System.currentTimeMillis()
            val payloadHash = sha256Hex(requestBody)
            val receipt = readSharedPlayerSyncReceipt()
            when (sharedPlayerSyncDecision(receipt, identity, payloadHash, now)) {
                SharedPlayerSyncDecision.SKIP_UNCHANGED -> {
                    val count = json.parseToJsonElement(requestBody).jsonObject["p_snapshots"]
                        ?.jsonArray?.size ?: error("Shared-player request has no snapshot array")
                    promoteLegacyPlayerNetworkProfileCapability(
                        identity = identity,
                        publishedAtEpochMillis = checkNotNull(receipt).syncedAtEpochMillis,
                    )
                    return@withLock buildJsonObject {
                        put("rules_version", com.nullplaying.model.SHARED_PLAYER_RULES_VERSION)
                        put("synced_count", count)
                        put("server_now", now)
                        put("deduplicated", true)
                    }.toString()
                }
                SharedPlayerSyncDecision.DEFER_CHANGED ->
                    throw IOException("공개 영웅 정보 변경은 잠시 후 동기화됩니다")
                SharedPlayerSyncDecision.SEND -> Unit
            }
            val response = request(
                path = "/rest/v1/rpc/sync_public_player_snapshots",
                method = "POST",
                accessToken = session.accessToken,
                body = requestBody,
            )
            val expectedCount = json.parseToJsonElement(requestBody).jsonObject["p_snapshots"]
                ?.jsonArray?.size ?: error("Shared-player request has no snapshot array")
            val verified = json.decodeFromString<PublicPlayerSyncResponse>(response)
            check(verified.rulesVersion == com.nullplaying.model.SHARED_PLAYER_RULES_VERSION &&
                verified.syncedCount == expectedCount && verified.serverNowEpochMillis > 0L
            ) { "Shared-player sync response failed validation" }
            check(readSession()?.userId == identity) {
                "Shared-player account changed during publication"
            }
            val receiptSaved = preferences.edit().putString(
                LAST_SHARED_PLAYER_SYNC_RECEIPT_KEY,
                json.encodeToString(SharedPlayerSyncReceipt(identity, payloadHash, now)),
            ).commit()
            if (!receiptSaved) Log.w(TAG, "Shared-player sync receipt could not be persisted")
            promoteLegacyPlayerNetworkProfileCapability(
                identity = identity,
                publishedAtEpochMillis = now,
            )
            response
        }

    private fun readSharedPlayerSyncReceipt(): SharedPlayerSyncReceipt? {
        val encoded = preferences.getString(LAST_SHARED_PLAYER_SYNC_RECEIPT_KEY, null) ?: return null
        return runCatching { json.decodeFromString<SharedPlayerSyncReceipt>(encoded) }.getOrElse {
            preferences.edit().remove(LAST_SHARED_PLAYER_SYNC_RECEIPT_KEY).apply()
            null
        }
    }

    private fun readUnifiedPlayerNetworkProfileSyncReceipt(): PlayerNetworkProfileSyncReceipt? {
        val encoded = preferences.getString(
            LAST_UNIFIED_PLAYER_PROFILE_SYNC_RECEIPT_KEY,
            null,
        ) ?: return null
        return runCatching { json.decodeFromString<PlayerNetworkProfileSyncReceipt>(encoded) }
            .getOrElse {
                preferences.edit().remove(LAST_UNIFIED_PLAYER_PROFILE_SYNC_RECEIPT_KEY).apply()
                null
            }
    }

    private fun isUnifiedPlayerNetworkProfileCurrent(
        requestBody: String,
        nowEpochMillis: Long,
    ): Boolean {
        val identity = readSession()?.userId ?: return false
        return playerNetworkProfileSyncDecision(
            receipt = readUnifiedPlayerNetworkProfileSyncReceipt(),
            userId = identity,
            payloadHash = sha256Hex(requestBody),
            nowEpochMillis = nowEpochMillis,
        ) == SharedPlayerSyncDecision.SKIP_UNCHANGED
    }

    private fun shouldPublishUnifiedPlayerNetworkProfile(
        requestBody: String,
        nowEpochMillis: Long,
    ): Boolean {
        val identity = readSession()?.userId
        val payloadHash = sha256Hex(requestBody)
        val characterCount = playerNetworkProfilePayloadSummary(requestBody, json).characterCount
        if (identity != null) {
            val unifiedReceipt = readUnifiedPlayerNetworkProfileSyncReceipt()
            if (unifiedReceipt != null &&
                isValidPlayerNetworkProfileSyncReceipt(unifiedReceipt, identity)
            ) {
                preferences.edit().remove(LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY).apply()
                return shouldPublishPlayerNetworkProfile(
                    receipt = unifiedReceipt,
                    userId = identity,
                    payloadHash = payloadHash,
                    characterCount = characterCount,
                    nowEpochMillis = nowEpochMillis,
                )
            }
            readActiveLegacyPlayerNetworkProfileCapability(identity, nowEpochMillis)?.let { legacy ->
                return shouldPublishDuringLegacyPlayerNetworkProfileCapability(
                    receipt = legacy,
                    userId = identity,
                    payloadHash = payloadHash,
                    nowEpochMillis = nowEpochMillis,
                )
            }
        }
        val ordinaryDecision = shouldPublishPlayerNetworkProfile(
            receipt = readUnifiedPlayerNetworkProfileSyncReceipt(),
            userId = identity,
            payloadHash = payloadHash,
            characterCount = characterCount,
            nowEpochMillis = nowEpochMillis,
        )
        if (ordinaryDecision || characterCount != 0) return ordinaryDecision
        val pendingRanking = preferences.getString(PENDING_RANKING_SYNC_KEY, null)
        val lastRankingHasEntries = preferences.getString(LAST_RANKING_SYNC_KEY, null)?.let { encoded ->
            runCatching { json.parseToJsonElement(encoded).jsonArray.isNotEmpty() }.getOrDefault(false)
        } == true
        return shouldRecoverLegacyEmptyPlayerNetworkProfile(
            characterCount = characterCount,
            pendingRankingPayload = pendingRanking,
            lastRankingHasEntries = lastRankingHasEntries,
            hasLegacySharedReceipt = readSharedPlayerSyncReceipt() != null,
        )
    }

    private fun playerNetworkProfileCapabilityForCurrentSession(): PlayerNetworkProfileCapabilityEvidence {
        val identity = readSession()?.userId
            ?: return PlayerNetworkProfileCapabilityEvidence(PlayerNetworkProfileCapability.UNKNOWN)
        val unifiedReceipt = readUnifiedPlayerNetworkProfileSyncReceipt()
        val legacyReceipt = readActiveLegacyPlayerNetworkProfileCapability(
            identity,
            System.currentTimeMillis(),
        )
        val persistedEvidence = persistedPlayerNetworkProfileCapabilityEvidence(
            unifiedReceipt = unifiedReceipt,
            legacyReceipt = legacyReceipt,
            userId = identity,
            nowEpochMillis = System.currentTimeMillis(),
        )
        if (persistedEvidence.capability == PlayerNetworkProfileCapability.UNIFIED) {
            preferences.edit().remove(LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY).apply()
            return persistedEvidence
        }
        synchronized(playerNetworkProfileCapabilityLock) {
            if (playerNetworkProfileCapabilityIdentity == identity) {
                return PlayerNetworkProfileCapabilityEvidence(
                    capability = playerNetworkProfileCapability,
                    committedLegacyRankingPayload = playerNetworkProfileCommittedLegacyRankingPayload,
                )
            }
        }
        return persistedEvidence
    }

    private fun updatePlayerNetworkProfileCapability(
        identity: String,
        capability: PlayerNetworkProfileCapability,
    ) {
        synchronized(playerNetworkProfileCapabilityLock) {
            playerNetworkProfileCapabilityIdentity = identity
            playerNetworkProfileCapability = capability
            playerNetworkProfileLegacyDetectedAtEpochMillis = 0L
            playerNetworkProfilePendingUnifiedPayloadHash = null
            playerNetworkProfilePendingRankingPayload = null
            playerNetworkProfileCommittedLegacyRankingPayload = null
        }
    }

    private fun noteLegacyPlayerNetworkProfileRoute(
        identity: String,
        detectedAtEpochMillis: Long,
        unifiedPayloadHash: String,
        rankingPayload: String,
    ) {
        synchronized(playerNetworkProfileCapabilityLock) {
            playerNetworkProfileCapabilityIdentity = identity
            playerNetworkProfileCapability = PlayerNetworkProfileCapability.LEGACY_RPC_MISSING
            playerNetworkProfileLegacyDetectedAtEpochMillis = detectedAtEpochMillis
            playerNetworkProfilePendingUnifiedPayloadHash = unifiedPayloadHash
            playerNetworkProfilePendingRankingPayload = rankingPayload
            playerNetworkProfileCommittedLegacyRankingPayload = null
        }
    }

    private fun recordLegacyPlayerNetworkProfileMissing(
        identity: String,
        detectedAtEpochMillis: Long,
        unifiedPayloadHash: String,
        rankingPayload: String,
    ) {
        noteLegacyPlayerNetworkProfileRoute(
            identity,
            detectedAtEpochMillis,
            unifiedPayloadHash,
            rankingPayload,
        )
        val saved = preferences.edit().putString(
            LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY,
            json.encodeToString(LegacyPlayerNetworkProfileCapabilityReceipt(
                userId = identity,
                detectedAtEpochMillis = detectedAtEpochMillis,
            )),
        ).remove(LAST_UNIFIED_PLAYER_PROFILE_SYNC_RECEIPT_KEY).commit()
        if (!saved) Log.w(TAG, "Legacy player-profile capability could not be persisted")
    }

    private fun promoteLegacyPlayerNetworkProfileCapability(
        identity: String,
        publishedAtEpochMillis: Long,
    ) {
        val promoted = synchronized(playerNetworkProfileCapabilityLock) {
            if (playerNetworkProfileCapabilityIdentity == identity &&
                playerNetworkProfileCapability == PlayerNetworkProfileCapability.LEGACY_RPC_MISSING
            ) {
                playerNetworkProfileCapability = PlayerNetworkProfileCapability.LEGACY_SHARED_COMMITTED
                playerNetworkProfileCommittedLegacyRankingPayload =
                    playerNetworkProfilePendingRankingPayload
                LegacyPlayerNetworkProfileCapabilityReceipt(
                    userId = identity,
                    detectedAtEpochMillis = playerNetworkProfileLegacyDetectedAtEpochMillis,
                    lastPublishedUnifiedPayloadHash = playerNetworkProfilePendingUnifiedPayloadHash,
                    lastPublishedRankingPayload = playerNetworkProfilePendingRankingPayload,
                    lastPublishedAtEpochMillis = publishedAtEpochMillis,
                )
            } else {
                null
            }
        }
        if (promoted != null) {
            val saved = preferences.edit().putString(
                LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY,
                json.encodeToString(promoted),
            ).commit()
            if (!saved) Log.w(TAG, "Legacy player-profile publication receipt could not be persisted")
        }
    }

    private fun readLegacyPlayerNetworkProfileCapability(): LegacyPlayerNetworkProfileCapabilityReceipt? {
        val encoded = preferences.getString(
            LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY,
            null,
        ) ?: return null
        return runCatching {
            json.decodeFromString<LegacyPlayerNetworkProfileCapabilityReceipt>(encoded)
        }.getOrElse {
            preferences.edit().remove(LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY).apply()
            null
        }
    }

    private fun readActiveLegacyPlayerNetworkProfileCapability(
        identity: String,
        nowEpochMillis: Long,
    ): LegacyPlayerNetworkProfileCapabilityReceipt? {
        val receipt = readLegacyPlayerNetworkProfileCapability() ?: return null
        if (isLegacyPlayerNetworkProfileCapabilityActive(receipt, identity, nowEpochMillis)) {
            return receipt
        }
        if (receipt.userId == identity) {
            preferences.edit().remove(LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY).apply()
        }
        return null
    }

    private suspend fun upsertUserProfile(session: AuthSession) {
        // Keep profile device_locale tied to the device, not the user's per-app language override.
        val systemLocales = Resources.getSystem().configuration.locales
        val locale = if (systemLocales.isEmpty) Locale.getDefault() else systemLocales[0]
        val countryCode = locale.country.uppercase(Locale.ROOT).takeIf { it.length == 2 }
        val languageCode = locale.language.lowercase(Locale.ROOT).takeIf { it.isNotBlank() } ?: "und"
        val profile = UserProfileUpsert(
            userId = session.userId,
            countryCode = countryCode,
            countrySource = if (countryCode == null) "unknown" else "device_locale",
            languageCode = languageCode,
            timezone = TimeZone.getDefault().id.take(64),
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            osVersion = Build.VERSION.RELEASE.orEmpty().take(32),
            deviceManufacturer = Build.MANUFACTURER.orEmpty().take(64),
            deviceModel = Build.MODEL.orEmpty().take(96),
            lastSeenAt = isoInstant(System.currentTimeMillis()),
        )
        request(
            path = "/rest/v1/user_profiles?on_conflict=user_id",
            method = "POST",
            accessToken = session.accessToken,
            body = json.encodeToString(profile),
            prefer = "resolution=merge-duplicates,return=minimal",
        )
    }

    private suspend fun ensureSession(forceValidation: Boolean = false): AuthSession = authMutex.withLock {
        val nowMillis = System.currentTimeMillis()
        val nowSeconds = nowMillis / 1_000L
        val stored = readSession()
        if (stored != null && stored.expiresAtEpochSeconds > nowSeconds + 60L) {
            val validationDue = !sessionValidatedThisProcess ||
                forceValidation &&
                nowMillis - lastSessionValidationAtEpochMillis >= SESSION_VALIDATION_COOLDOWN_MILLIS
            if (!validationDue) return@withLock stored
            try {
                validateSession(stored)
                markSessionActive(stored, nowMillis)
                return@withLock stored
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SupabaseHttpException) {
                if (!shouldReplaceAnonymousSession(error.statusCode, error.responseBody)) throw error
                Log.i(TAG, "Stored anonymous session was deleted, revoked, or invalid; recovering")
                if (!isDeletedOrRevokedAnonymousIdentity(error.responseBody)) {
                    val refreshed = tryRefreshSession(stored)
                    if (refreshed != null) {
                        saveSession(refreshed)
                        markSessionActive(refreshed, nowMillis)
                        return@withLock refreshed
                    }
                }
                return@withLock createReplacementSession(stored)
            }
        }
        val refreshed = stored?.let { tryRefreshSession(it) }
        if (refreshed != null) {
            saveSession(refreshed)
            markSessionActive(refreshed, nowMillis)
            return@withLock refreshed
        }
        createReplacementSession(stored)
    }

    private suspend fun tryRefreshSession(stored: AuthSession): AuthSession? {
        val refreshToken = stored.refreshToken.takeIf(String::isNotBlank) ?: return null
        return try {
            authenticate(
                path = "/auth/v1/token?grant_type=refresh_token",
                body = "{\"refresh_token\":${json.encodeToString(refreshToken)}}",
                fallbackUserId = stored.userId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SupabaseHttpException) {
            if (shouldReplaceAnonymousSession(error.statusCode, error.responseBody)) null else throw error
        }
    }

    private suspend fun validateSession(session: AuthSession) {
        val response = json.parseToJsonElement(
            request(
                path = "/auth/v1/user",
                method = "GET",
                accessToken = session.accessToken,
            ),
        ).jsonObject
        val remoteUserId = response["id"]?.jsonPrimitive?.content
            ?: throw IOException("익명 사용자 ID를 확인할 수 없습니다")
        if (remoteUserId != session.userId) {
            throw IOException("저장된 익명 사용자와 서버 사용자가 일치하지 않습니다")
        }
    }

    private suspend fun createReplacementSession(previous: AuthSession?): AuthSession {
        val replacement = authenticate(
            path = "/auth/v1/signup",
            body = "{}",
            fallbackUserId = null,
        )
        val identityChanged = previous != null && previous.userId != replacement.userId
        // Publish the new identity first. Any old in-flight response then fails its identity check;
        // the cleanup immediately below removes data that may have committed just before this write.
        saveSession(replacement)
        if (identityChanged) {
            clearRemoteStateForReplacedIdentity()
        }
        markSessionActive(replacement, System.currentTimeMillis())
        return replacement
    }

    private fun markSessionActive(
        session: AuthSession,
        validatedAtEpochMillis: Long,
    ) {
        sessionValidatedThisProcess = true
        lastSessionValidationAtEpochMillis = validatedAtEpochMillis
        _connectionState.value = SupabaseConnectionState.Connected(session.userId)
    }

    private suspend fun clearRemoteStateForReplacedIdentity() {
        rankingResponseGate.invalidate {
            preferences.edit()
                .remove(PENDING_RANKING_SYNC_KEY)
                .remove(LAST_RANKING_SYNC_KEY)
                .remove(LAST_RANKING_SYNC_AT_KEY)
                .remove(RANKING_CACHE_KEY)
                .commit()
            lastRankingFetchAttemptElapsedRealtime = null
            rankingClock = null
            _rankingSnapshot.value = null
            _rankingError.value = null
        }
        arenaRankingResponseGate.invalidate {
            preferences.edit()
                .remove(PENDING_ARENA_RANKING_SYNC_KEY)
                .remove(ARENA_RANKING_QUEUE_STATE_KEY)
                .remove(LAST_ARENA_RANKING_SYNC_KEY)
                .remove(LAST_ARENA_RANKING_SYNC_AT_KEY)
                .remove(ARENA_RANKING_RETRY_NOT_BEFORE_KEY)
                .remove(ARENA_RANKING_CACHE_KEY)
                .commit()
            lastArenaRankingFetchAttemptElapsedRealtime = null
            arenaRankingClock = null
            _arenaRankingSnapshot.value = null
            _arenaRankingError.value = null
        }
        synchronized(sessionQueueLock) {
            preferences.edit()
                .remove(PENDING_LOG_KEY)
                .remove(PENDING_LOGS_KEY)
                .commit()
        }
        synchronized(lifecycleStateLock) {
            preferences.edit().remove(PENDING_BACKGROUND_SESSION_KEY).commit()
        }
        preferences.edit()
            .remove(LAST_SHARED_PLAYER_SYNC_RECEIPT_KEY)
            .remove(LAST_UNIFIED_PLAYER_PROFILE_SYNC_RECEIPT_KEY)
            .remove(LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY)
            .commit()
        synchronized(playerNetworkProfileCapabilityLock) {
            playerNetworkProfileCapabilityIdentity = null
            playerNetworkProfileCapability = PlayerNetworkProfileCapability.UNKNOWN
            playerNetworkProfileLegacyDetectedAtEpochMillis = 0L
            playerNetworkProfilePendingUnifiedPayloadHash = null
            playerNetworkProfilePendingRankingPayload = null
            playerNetworkProfileCommittedLegacyRankingPayload = null
        }
        repository.clearPublicPlayerRosters(System.currentTimeMillis())
    }

    private suspend fun authenticate(
        path: String,
        body: String,
        fallbackUserId: String?,
    ): AuthSession {
        val response = json.parseToJsonElement(request(path, "POST", null, body)).jsonObject
        val accessToken = response["access_token"]?.jsonPrimitive?.content ?: error("인증 토큰이 없습니다")
        val refreshToken = response["refresh_token"]?.jsonPrimitive?.content ?: ""
        val expiresIn = response["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        val userId = response["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: fallbackUserId?.takeIf(String::isNotBlank)
            ?: error("익명 사용자 ID가 없습니다")
        return AuthSession(accessToken, refreshToken, userId, System.currentTimeMillis() / 1000L + expiresIn)
    }

    private suspend fun request(
        path: String,
        method: String,
        accessToken: String?,
        body: String? = null,
        prefer: String? = null,
        allowSessionLogDuplicate: Boolean = false,
        maxResponseBytes: Int? = null,
        connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
        requestTimeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
        onSuccessfulServerDate: ((Long) -> Unit)? = null,
    ): String = try {
        check(isConfigured) { "Supabase is disabled in QA builds" }
        withTimeout(requestTimeoutMillis) {
            runInterruptible(Dispatchers.IO) {
                val connection = URL(
                    BuildConfig.SUPABASE_URL.trimEnd('/') + path,
                ).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = method
                    connection.connectTimeout = connectTimeoutMillis
                    connection.readTimeout = readTimeoutMillis
                    connection.setRequestProperty("Cache-Control", "no-cache")
                    connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                    connection.setRequestProperty(
                        "Authorization",
                        "Bearer ${accessToken ?: BuildConfig.SUPABASE_PUBLISHABLE_KEY}",
                    )
                    connection.setRequestProperty("Content-Type", "application/json")
                    if (prefer != null) connection.setRequestProperty("Prefer", prefer)
                    if (body != null) {
                        connection.doOutput = true
                        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                    }
                    val code = connection.responseCode
                    if (code in 200..299) {
                        connection.date.takeIf { it > 0L }?.let { serverDate ->
                            onSuccessfulServerDate?.invoke(serverDate)
                        }
                    }
                    val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.use { input ->
                            readBoundedUtf8Response(input, maxResponseBytes ?: Int.MAX_VALUE)
                        }.orEmpty()
                    if (code !in 200..299 && !(allowSessionLogDuplicate && isDuplicateSessionLogConflict(code, response))) {
                        throw SupabaseHttpException(code, response)
                    }
                    response
                } finally {
                    connection.disconnect()
                }
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw IOException("랭킹 서버 응답 시간이 초과되었습니다", timeout)
    }

    private fun saveSession(session: AuthSession) {
        preferences.edit().putString(SESSION_KEY, json.encodeToString(session)).apply()
    }

    private fun readSession(): AuthSession? = preferences.getString(SESSION_KEY, null)?.let { encoded ->
        runCatching { json.decodeFromString<AuthSession>(encoded) }.getOrNull()
    }

    private fun Throwable.userMessage(): String = message?.take(240) ?: "네트워크 연결을 확인해 주세요"

    private companion object {
        const val PREFERENCES_NAME = "supabase_game"
        const val SESSION_KEY = "auth_session"
        const val PENDING_LOG_KEY = "pending_session_log"
        const val PENDING_LOGS_KEY = "pending_session_logs"
        const val PENDING_BACKGROUND_SESSION_KEY = "pending_background_session"
        const val PENDING_RANKING_SYNC_KEY = "pending_ranking_sync"
        const val LAST_RANKING_SYNC_KEY = "last_ranking_sync"
        const val LAST_RANKING_SYNC_AT_KEY = "last_ranking_sync_at"
        const val RANKING_CACHE_KEY = "ranking_cache_v3"
        const val PENDING_ARENA_RANKING_SYNC_KEY = "pending_arena_ranking_sync_v1"
        const val ARENA_RANKING_QUEUE_STATE_KEY = "arena_ranking_queue_state_v2"
        const val LAST_ARENA_RANKING_SYNC_KEY = "last_arena_ranking_sync_v1"
        const val LAST_ARENA_RANKING_SYNC_AT_KEY = "last_arena_ranking_sync_at_v1"
        const val ARENA_RANKING_RETRY_NOT_BEFORE_KEY = "arena_ranking_retry_not_before_v1"
        const val ARENA_RANKING_CACHE_KEY = "arena_ranking_cache_v1"
        const val PENDING_PLAYER_NETWORK_PROFILE_KEY = "pending_player_network_profile_v1"
        const val LAST_SHARED_PLAYER_SYNC_RECEIPT_KEY = "shared_player_sync_receipt_v1"
        const val LAST_UNIFIED_PLAYER_PROFILE_SYNC_RECEIPT_KEY =
            "player_network_profile_sync_receipt_v1"
        const val LEGACY_PLAYER_NETWORK_PROFILE_CAPABILITY_KEY =
            "legacy_player_network_profile_capability_v1"
        const val MIN_RANKING_LEVEL = 20L
        const val MAX_PENDING_SESSION_LOGS = 100
        const val TAG = "AlarmQuestRanking"
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 12_000
        const val REQUEST_TIMEOUT_MILLIS = 20_000L
        const val MAX_PROFILE_RESPONSE_BYTES = 8 * 1024
        const val SERVER_TIME_CONNECT_TIMEOUT_MILLIS = 2_500
        const val SERVER_TIME_READ_TIMEOUT_MILLIS = 2_500
        const val SERVER_TIME_REQUEST_TIMEOUT_MILLIS = 6_000L
        const val MIN_TRUSTED_SERVER_EPOCH_MILLIS = 1_704_067_200_000L // 2024-01-01 UTC
        const val SESSION_VALIDATION_COOLDOWN_MILLIS = 5_000L
    }
}

internal data class UnifiedRankingReceiptMutation(
    val lastPayload: String,
    val lastSuccessfulAtEpochMillis: Long,
    val pendingPayload: String?,
)

/** A stale queue attempt may clear only its own pending payload, never a newer pending update. */
internal fun rankingPendingPayloadAfterQueue(
    lastSuccessfulPayload: String?,
    pendingPayload: String?,
    candidatePayload: String,
): String? {
    require(candidatePayload.isNotBlank())
    return when {
        candidatePayload == "[]" && lastSuccessfulPayload == null && pendingPayload == null -> null
        candidatePayload == lastSuccessfulPayload ->
            pendingPayload.takeUnless { it == candidatePayload }
        candidatePayload == pendingPayload -> pendingPayload
        else -> candidatePayload
    }
}

/**
 * Upgrade recovery for deletions queued by the ranking-only client. A truly new empty account has
 * none of this evidence and remains a zero-auth/zero-profile-request path.
 */
internal fun shouldRecoverLegacyEmptyPlayerNetworkProfile(
    characterCount: Int,
    pendingRankingPayload: String?,
    lastRankingHasEntries: Boolean,
    hasLegacySharedReceipt: Boolean,
): Boolean = characterCount == 0 &&
    (pendingRankingPayload != null || lastRankingHasEntries || hasLegacySharedReceipt)

internal enum class PlayerNetworkProfileCapability {
    UNKNOWN,
    UNIFIED,
    LEGACY_RPC_MISSING,
    LEGACY_SHARED_COMMITTED,
}

internal data class PlayerNetworkProfileCapabilityEvidence(
    val capability: PlayerNetworkProfileCapability,
    val committedLegacyRankingPayload: String? = null,
)

@Serializable
internal data class LegacyPlayerNetworkProfileCapabilityReceipt(
    val userId: String,
    val detectedAtEpochMillis: Long,
    val lastPublishedUnifiedPayloadHash: String? = null,
    val lastPublishedRankingPayload: String? = null,
    val lastPublishedAtEpochMillis: Long = 0L,
)

internal fun persistedPlayerNetworkProfileCapabilityEvidence(
    unifiedReceipt: PlayerNetworkProfileSyncReceipt?,
    legacyReceipt: LegacyPlayerNetworkProfileCapabilityReceipt?,
    userId: String,
    nowEpochMillis: Long,
): PlayerNetworkProfileCapabilityEvidence {
    if (unifiedReceipt != null && isValidPlayerNetworkProfileSyncReceipt(unifiedReceipt, userId)) {
        return PlayerNetworkProfileCapabilityEvidence(PlayerNetworkProfileCapability.UNIFIED)
    }
    if (!isLegacyPlayerNetworkProfileCapabilityActive(legacyReceipt, userId, nowEpochMillis)) {
        return PlayerNetworkProfileCapabilityEvidence(PlayerNetworkProfileCapability.UNKNOWN)
    }
    val committedRanking = legacyReceipt?.lastPublishedRankingPayload
    return if (committedRanking != null) {
        PlayerNetworkProfileCapabilityEvidence(
            capability = PlayerNetworkProfileCapability.LEGACY_SHARED_COMMITTED,
            committedLegacyRankingPayload = committedRanking,
        )
    } else {
        PlayerNetworkProfileCapabilityEvidence(PlayerNetworkProfileCapability.LEGACY_RPC_MISSING)
    }
}

internal fun isLegacyPlayerNetworkProfileCapabilityActive(
    receipt: LegacyPlayerNetworkProfileCapabilityReceipt?,
    userId: String,
    nowEpochMillis: Long,
): Boolean {
    val valid = receipt?.takeIf {
        it.userId.isNotBlank() && it.detectedAtEpochMillis > 0L
    } ?: return false
    if (valid.userId != userId || nowEpochMillis <= 0L) return false
    // A wall-clock rollback cannot force an early capability reprobe.
    if (nowEpochMillis < valid.detectedAtEpochMillis) return true
    return nowEpochMillis - valid.detectedAtEpochMillis < UNCHANGED_SYNC_INTERVAL_MILLIS
}

internal fun shouldPublishDuringLegacyPlayerNetworkProfileCapability(
    receipt: LegacyPlayerNetworkProfileCapabilityReceipt,
    userId: String,
    payloadHash: String,
    nowEpochMillis: Long,
): Boolean {
    require(isLegacyPlayerNetworkProfileCapabilityActive(receipt, userId, nowEpochMillis))
    val publishedHash = receipt.lastPublishedUnifiedPayloadHash ?: return true
    val publishedAt = receipt.lastPublishedAtEpochMillis.takeIf { it > 0L } ?: return true
    if (nowEpochMillis < publishedAt) return false
    val age = nowEpochMillis - publishedAt
    return if (publishedHash == payloadHash) {
        age >= UNCHANGED_SYNC_INTERVAL_MILLIS
    } else {
        age >= CHANGED_SYNC_COOLDOWN_MILLIS
    }
}

/**
 * Ranking-only fallback is available when the feature is off, or after this process observed the
 * exact missing unified RPC and then committed the matching complete roster through the legacy
 * shared endpoint. Transient unified/legacy failures remain fail closed with pending data intact.
 */
internal fun shouldAttemptLegacyRankingWrite(
    sharedPlayerRemoteEnabled: Boolean,
    evidence: PlayerNetworkProfileCapabilityEvidence,
    pendingPayload: String?,
): Boolean = pendingPayload != null &&
    (!sharedPlayerRemoteEnabled ||
        evidence.capability == PlayerNetworkProfileCapability.LEGACY_SHARED_COMMITTED &&
        evidence.committedLegacyRankingPayload == pendingPayload)

/** A completed request may remove only the exact complete-roster mutation it sent. */
internal fun pendingPlayerNetworkProfileAfterSuccess(
    currentPendingPayload: String?,
    sentPayload: String,
): String? {
    require(sentPayload.isNotBlank())
    return currentPendingPayload.takeUnless { it == sentPayload }
}

/** Replaces only an existing queued complete roster; a fresh empty account remains a zero-call. */
internal fun pendingPlayerNetworkProfileAfterForegroundReconcile(
    currentPendingPayload: String?,
    currentCompletePayload: String,
): String? {
    require(currentCompletePayload.isNotBlank())
    return if (currentPendingPayload == null) null else currentCompletePayload
}

/** Clears only the exact pending projection committed by the unified transaction. */
internal fun unifiedRankingReceiptMutation(
    pendingPayload: String?,
    unifiedPayload: String,
    verifiedServerNowEpochMillis: Long,
): UnifiedRankingReceiptMutation {
    require(unifiedPayload.isNotBlank() && verifiedServerNowEpochMillis > 0L)
    return UnifiedRankingReceiptMutation(
        lastPayload = unifiedPayload,
        lastSuccessfulAtEpochMillis = verifiedServerNowEpochMillis,
        pendingPayload = pendingPayload.takeUnless { it == unifiedPayload },
    )
}

private fun rankingEntriesForPublicSnapshots(
    snapshots: List<PublicPlayerSnapshotUpload>,
): List<RankingSyncEntry> = snapshots.asSequence()
    .filter { it.level >= 20L }
    .sortedBy(PublicPlayerSnapshotUpload::slotId)
    .map { snapshot ->
        RankingSyncEntry(
            characterId = snapshot.characterId,
            slotId = snapshot.slotId,
            displayName = canonicalRankingDisplayName(snapshot.displayName),
            heroClass = snapshot.heroClass.name,
            level = snapshot.level,
            combatPower = snapshot.combatPower,
        )
    }
    .toList()

internal const val ARENA_RANKING_RULES_VERSION = 1
internal const val ARENA_RANKING_SYNC_COOLDOWN_MILLIS = 15L * 60L * 1_000L
internal const val ARENA_RANKING_PLACEMENT_RECOVERY_DELAY_MILLIS = 5_000L
internal const val ARENA_RANKING_MAX_RETRY_MILLIS = 366L * 24L * 60L * 60L * 1_000L
private val arenaRankingReceiptJson = Json { ignoreUnknownKeys = true }

internal fun isArenaRankingSyncAllowed(
    lastSuccessfulOrLimitedAt: Long,
    now: Long,
    retryNotBefore: Long = 0L,
): Boolean = (retryNotBefore <= 0L || now >= retryNotBefore) &&
    (lastSuccessfulOrLimitedAt <= 0L || now >= lastSuccessfulOrLimitedAt &&
        now - lastSuccessfulOrLimitedAt >= ARENA_RANKING_SYNC_COOLDOWN_MILLIS)

internal fun arenaRankingRetryNotBefore(
    serverNow: Long,
    retryAfterSeconds: Long?,
    defaultDelayMillis: Long = ARENA_RANKING_SYNC_COOLDOWN_MILLIS,
): Long {
    if (serverNow <= 0L) return Long.MAX_VALUE
    val retryMillis = retryAfterSeconds
        ?.coerceAtLeast(1L)
        ?.let { seconds -> runCatching { Math.multiplyExact(seconds, 1_000L) }.getOrNull() }
        ?.coerceAtMost(ARENA_RANKING_MAX_RETRY_MILLIS)
        ?: defaultDelayMillis
    return runCatching { Math.addExact(serverNow, retryMillis) }.getOrDefault(Long.MAX_VALUE)
}

internal enum class ArenaRankingSyncDisposition {
    ACCEPTED,
    SUPPRESS_PAYLOAD,
    RECOVER_PLACEMENT,
    RETRY_PROFILE,
    RETRY_LATER,
}

internal fun isTrustedArenaRankingSyncResponse(response: ArenaRankingSyncResponse): Boolean {
    if (response.serverNowEpochMillis <= 0L || response.seasonId != ARENA_RANKING_SEASON_ID ||
        response.rulesVersion != ARENA_RANKING_RULES_VERSION
    ) return false
    return when {
        response.accepted -> !response.rateLimited && !response.dailyLimit && !response.invalid &&
            response.errorCode == null && response.retryAfterSeconds == null
        response.rateLimited -> !response.invalid && response.errorCode == null &&
            response.retryAfterSeconds != null && response.retryAfterSeconds > 0L
        else -> response.invalid && !response.dailyLimit && !response.errorCode.isNullOrBlank() &&
            when (response.errorCode) {
                "daily_match_limit", "account_age_limit" ->
                    response.retryAfterSeconds != null && response.retryAfterSeconds > 0L
                else -> true
            }
    }
}

internal fun arenaRankingSyncDisposition(
    response: ArenaRankingSyncResponse,
): ArenaRankingSyncDisposition = when {
    response.accepted -> ArenaRankingSyncDisposition.ACCEPTED
    response.errorCode == "eligible_owned_profile_required" ->
        ArenaRankingSyncDisposition.RETRY_PROFILE
    response.errorCode == "replacement_requires_placement" ->
        ArenaRankingSyncDisposition.RECOVER_PLACEMENT
    response.errorCode in setOf(
        "invalid_standing",
        "record_decrease",
        "score_movement_limit",
    ) -> ArenaRankingSyncDisposition.SUPPRESS_PAYLOAD
    else -> ArenaRankingSyncDisposition.RETRY_LATER
}

internal fun arenaRankingRetryNotBeforeFor(response: ArenaRankingSyncResponse): Long {
    val defaultDelay = if (response.errorCode == "invalid_account_time") {
        24L * 60L * 60L * 1_000L
    } else {
        ARENA_RANKING_SYNC_COOLDOWN_MILLIS
    }
    return arenaRankingRetryNotBefore(
        serverNow = response.serverNowEpochMillis,
        retryAfterSeconds = response.retryAfterSeconds,
        defaultDelayMillis = defaultDelay,
    )
}

internal fun decodeArenaRankingRateLimit(
    statusCode: Int,
    responseBody: String,
): ArenaRankingSyncResponse? {
    if (statusCode != 429 || responseBody.length > 8 * 1024) return null
    val parsed = runCatching {
        arenaRankingReceiptJson.decodeFromString<ArenaRankingSyncResponse>(responseBody)
    }.getOrNull() ?: return null
    return parsed.takeIf {
        !it.accepted && it.rateLimited && isTrustedArenaRankingSyncResponse(it)
    }
}

internal fun removeExactArenaRankingPending(
    pending: List<ArenaRankingLocalStanding>,
    sent: ArenaRankingLocalStanding,
): List<ArenaRankingLocalStanding> = pending.filterNot {
    it.characterId == sent.characterId && sameArenaRankingServerPayload(it, sent)
}

internal fun arenaRankingPendingAfterResponse(
    pending: List<ArenaRankingLocalStanding>,
    sent: ArenaRankingLocalStanding,
    response: ArenaRankingSyncResponse,
): List<ArenaRankingLocalStanding> {
    if (!isTrustedArenaRankingSyncResponse(response)) return pending
    return when (arenaRankingSyncDisposition(response)) {
        ArenaRankingSyncDisposition.ACCEPTED,
        ArenaRankingSyncDisposition.SUPPRESS_PAYLOAD -> removeExactArenaRankingPending(pending, sent)
        ArenaRankingSyncDisposition.RECOVER_PLACEMENT,
        ArenaRankingSyncDisposition.RETRY_PROFILE,
        ArenaRankingSyncDisposition.RETRY_LATER -> pending
    }
}

@Serializable
internal data class ArenaRankingQueueState(
    val pending: List<ArenaRankingLocalStanding> = emptyList(),
    val deferred: List<ArenaRankingLocalStanding> = emptyList(),
    val placementArchive: List<ArenaRankingLocalStanding> = emptyList(),
) {
    fun normalized(): ArenaRankingQueueState = copy(
        pending = normalizedArenaRankingLane(pending),
        deferred = normalizedArenaRankingLane(deferred),
        placementArchive = normalizedArenaRankingLane(
            placementArchive.filter { it.completedBattles == ARENA_RANKING_PLACEMENT_REQUIRED },
        ),
    )
}

private fun normalizedArenaRankingLane(
    standings: List<ArenaRankingLocalStanding>,
): List<ArenaRankingLocalStanding> = standings.asSequence()
    .filter(::isValidArenaRankingStanding)
    .groupBy(ArenaRankingLocalStanding::characterId)
    .values
    .map { candidates ->
        candidates.maxWithOrNull(
            compareBy<ArenaRankingLocalStanding> { it.completedBattles }
                .thenBy { it.observedAtEpochMillis },
        ) ?: error("Empty arena ranking lane")
    }
    .sortedBy(ArenaRankingLocalStanding::observedAtEpochMillis)
    .takeLast(3)

private fun upsertArenaRankingLane(
    standings: List<ArenaRankingLocalStanding>,
    candidate: ArenaRankingLocalStanding,
): List<ArenaRankingLocalStanding> = normalizedArenaRankingLane(
    standings.filterNot { it.characterId == candidate.characterId } + candidate,
)

private fun removeArenaRankingCharacter(
    standings: List<ArenaRankingLocalStanding>,
    characterId: String,
): List<ArenaRankingLocalStanding> = standings.filterNot { it.characterId == characterId }

private fun latestArenaRankingStanding(
    first: ArenaRankingLocalStanding?,
    second: ArenaRankingLocalStanding,
): ArenaRankingLocalStanding = listOfNotNull(first, second).maxWithOrNull(
    compareBy<ArenaRankingLocalStanding> { it.completedBattles }
        .thenBy { it.observedAtEpochMillis },
) ?: second

internal fun rememberArenaRankingPlacement(
    state: ArenaRankingQueueState,
    placement: ArenaRankingLocalStanding,
): ArenaRankingQueueState {
    require(isValidArenaRankingStanding(placement))
    require(placement.completedBattles == ARENA_RANKING_PLACEMENT_REQUIRED)
    val current = state.normalized()
    val existing = current.placementArchive.firstOrNull { it.characterId == placement.characterId }
    val archive = if (existing == null) {
        upsertArenaRankingLane(current.placementArchive, placement)
    } else {
        current.placementArchive
    }
    return current.copy(placementArchive = archive).normalized()
}

/**
 * Preserves a pending match-ten registration while keeping only the latest later aggregate in a
 * deferred lane. This is durable queue policy; network timing cannot overwrite placement evidence.
 */
internal fun arenaRankingQueueAfterStanding(
    state: ArenaRankingQueueState,
    handled: List<ArenaRankingLocalStanding>,
    candidate: ArenaRankingLocalStanding,
): ArenaRankingQueueState {
    require(isValidArenaRankingStanding(candidate))
    var next = if (candidate.completedBattles == ARENA_RANKING_PLACEMENT_REQUIRED) {
        rememberArenaRankingPlacement(state, candidate)
    } else state.normalized()
    val pendingPlacement = next.pending.firstOrNull {
        it.characterId == candidate.characterId &&
            it.completedBattles == ARENA_RANKING_PLACEMENT_REQUIRED
    }
    if (pendingPlacement != null) {
        if (!sameArenaRankingServerPayload(pendingPlacement, candidate)) {
            val currentDeferred = next.deferred.firstOrNull { it.characterId == candidate.characterId }
            next = next.copy(
                deferred = upsertArenaRankingLane(
                    next.deferred,
                    latestArenaRankingStanding(currentDeferred, candidate),
                ),
            )
        }
        return next.normalized()
    }
    if (handled.any { sameArenaRankingServerPayload(it, candidate) }) {
        return next.copy(
            pending = removeExactArenaRankingPending(next.pending, candidate),
            deferred = removeArenaRankingCharacter(next.deferred, candidate.characterId),
        ).normalized()
    }
    return next.copy(
        pending = upsertArenaRankingLane(next.pending, candidate),
        deferred = removeArenaRankingCharacter(next.deferred, candidate.characterId),
    ).normalized()
}

internal fun arenaRankingQueueAfterAccepted(
    state: ArenaRankingQueueState,
    sent: ArenaRankingLocalStanding,
): ArenaRankingQueueState {
    var pending = removeExactArenaRankingPending(state.pending, sent)
    val deferred = state.deferred.firstOrNull { it.characterId == sent.characterId }
    if (deferred != null && !sameArenaRankingServerPayload(deferred, sent)) {
        pending = upsertArenaRankingLane(pending, deferred)
    }
    return state.copy(
        pending = pending,
        deferred = removeArenaRankingCharacter(state.deferred, sent.characterId),
    ).normalized()
}

internal fun arenaRankingQueueAfterSuppressed(
    state: ArenaRankingQueueState,
    sent: ArenaRankingLocalStanding,
): ArenaRankingQueueState {
    val withoutSent = state.copy(
        pending = removeExactArenaRankingPending(state.pending, sent),
    )
    return arenaRankingQueueAfterAccepted(withoutSent, sent)
}

/** Returns null only when this install never durably observed the exact tenth-match aggregate. */
internal fun arenaRankingQueueAfterPlacementRequired(
    state: ArenaRankingQueueState,
    rejected: ArenaRankingLocalStanding,
): ArenaRankingQueueState? {
    val placement = state.placementArchive.firstOrNull {
        it.characterId == rejected.characterId &&
            it.completedBattles == ARENA_RANKING_PLACEMENT_REQUIRED
    } ?: return null
    if (sameArenaRankingServerPayload(placement, rejected)) return null
    val currentDeferred = state.deferred.firstOrNull { it.characterId == rejected.characterId }
    return state.copy(
        pending = upsertArenaRankingLane(
            removeArenaRankingCharacter(state.pending, rejected.characterId),
            placement,
        ),
        deferred = upsertArenaRankingLane(
            state.deferred,
            latestArenaRankingStanding(currentDeferred, rejected),
        ),
    ).normalized()
}

internal fun sameArenaRankingServerPayload(
    left: ArenaRankingLocalStanding,
    right: ArenaRankingLocalStanding,
): Boolean = left.characterId == right.characterId && left.score == right.score &&
    left.completedBattles == right.completedBattles && left.wins == right.wins &&
    left.losses == right.losses && left.draws == right.draws

internal fun arenaRankingPendingAfterQueue(
    pending: List<ArenaRankingLocalStanding>,
    committed: List<ArenaRankingLocalStanding>,
    candidate: ArenaRankingLocalStanding,
): List<ArenaRankingLocalStanding> {
    require(isValidArenaRankingStanding(candidate))
    val existingPending = pending.filter(::isValidArenaRankingStanding)
    val lastCommitted = committed.firstOrNull { it.characterId == candidate.characterId }
    if (lastCommitted != null && sameArenaRankingServerPayload(lastCommitted, candidate)) {
        return existingPending.filterNot { it.characterId == candidate.characterId }.take(3)
    }
    return (existingPending.filterNot { it.characterId == candidate.characterId } + candidate)
        .sortedBy(ArenaRankingLocalStanding::observedAtEpochMillis)
        .takeLast(3)
}

internal fun arenaRankingCommittedAfterSuccess(
    committed: List<ArenaRankingLocalStanding>,
    sent: ArenaRankingLocalStanding,
): List<ArenaRankingLocalStanding> =
    (committed.filterNot { it.characterId == sent.characterId } + sent)
        .sortedByDescending(ArenaRankingLocalStanding::observedAtEpochMillis)
        .take(3)

internal const val MAX_DAILY_RANKING_RESPONSE_BYTES = 512 * 1024

internal fun readBoundedUtf8Response(input: InputStream, maxResponseBytes: Int): String {
    require(maxResponseBytes >= 0)
    val output = ByteArrayOutputStream(minOf(maxResponseBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > maxResponseBytes - output.size()) {
            throw IOException("Supabase response exceeded its size limit")
        }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun canonicalRankingDisplayName(value: String): String {
    val trimmed = value.trim().ifBlank { return "이름 없는 모험가" }
    val codePointCount = trimmed.codePointCount(0, trimmed.length)
    if (codePointCount <= 24) return trimmed
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, 24))
}

private class SupabaseHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IOException("Supabase 요청 실패 ($statusCode): ${responseBody.take(240)}")

@Serializable
private data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val expiresAtEpochSeconds: Long,
)

@Serializable
private data class RankingSyncEntry(
    @SerialName("character_id") val characterId: String,
    @SerialName("slot_id") val slotId: Int,
    @SerialName("display_name") val displayName: String,
    @SerialName("hero_class") val heroClass: String,
    val level: Long,
    @SerialName("combat_power") val combatPower: Long,
)

@Serializable
internal data class ArenaRankingSyncResponse(
    val accepted: Boolean,
    @SerialName("server_now") val serverNowEpochMillis: Long,
    @SerialName("season_id") val seasonId: String,
    @SerialName("rules_version") val rulesVersion: Int,
    val deduplicated: Boolean = false,
    @SerialName("rate_limited") val rateLimited: Boolean = false,
    @SerialName("daily_limit") val dailyLimit: Boolean = false,
    val invalid: Boolean = false,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long? = null,
)

@Serializable
private data class UserProfileUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("country_code") val countryCode: String?,
    @SerialName("country_source") val countrySource: String,
    @SerialName("language_code") val languageCode: String,
    val timezone: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("app_version_code") val appVersionCode: Int,
    @SerialName("os_version") val osVersion: String,
    @SerialName("device_manufacturer") val deviceManufacturer: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
)

@Serializable
private data class AppUpdateRow(
    @SerialName("latest_version_code") val latestVersionCode: Int,
    @SerialName("latest_version_name") val latestVersionName: String,
    @SerialName("minimum_supported_version_code") val minimumSupportedVersionCode: Int,
    val title: String,
    val message: String,
    @SerialName("update_url") val updateUrl: String,
    @SerialName("force_update") val forceUpdate: Boolean,
)

@Serializable
private data class AppAnnouncementRow(
    val id: Long,
    @SerialName("announcement_key") val announcementKey: String,
    val title: String,
    val message: String,
    @SerialName("display_type") val displayType: String,
) {
    fun toAnnouncement(): AppAnnouncement? {
        val normalizedTitle = title.trim()
        val normalizedMessage = message.trim()
        if (normalizedTitle.isEmpty() || normalizedMessage.isEmpty()) return null
        return AppAnnouncement(
            id = id,
            announcementKey = announcementKey,
            title = normalizedTitle,
            message = normalizedMessage,
            displayType = AppAnnouncementDisplayType.fromDatabaseValue(displayType),
        )
    }
}

@Serializable
private data class PendingBackgroundSession(
    val backgroundedAt: Long,
    val reason: String,
    val slotId: Int?,
    val heroLevel: Long?,
    val combatPower: Long?,
    val totalActs: Long?,
    val totalKills: Long?,
    val appVersion: String,
    val deviceModel: String,
) {
    fun toSessionLog(eventId: String = UUID.randomUUID().toString()) = PendingSessionLog(
        eventId = eventId,
        endedAt = isoInstant(backgroundedAt),
        reason = reason,
        slotId = slotId,
        heroLevel = heroLevel,
        combatPower = combatPower,
        totalActs = totalActs,
        totalKills = totalKills,
        appVersion = appVersion,
        deviceModel = deviceModel,
    )
}

@Serializable
private data class PendingSessionLog(
    val eventId: String,
    val endedAt: String,
    val reason: String,
    val slotId: Int?,
    val heroLevel: Long?,
    val combatPower: Long?,
    val totalActs: Long?,
    val totalKills: Long?,
    val appVersion: String,
    val deviceModel: String,
) {
    fun toInsert(userId: String) = SessionLogInsert(
        eventId, userId, endedAt, reason, slotId, heroLevel, combatPower,
        totalActs, totalKills, appVersion, deviceModel,
    )
}

@Serializable
private data class SessionLogInsert(
    @SerialName("event_id") val eventId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("ended_at") val endedAt: String,
    val reason: String,
    @SerialName("slot_id") val slotId: Int?,
    @SerialName("hero_level") val heroLevel: Long?,
    @SerialName("combat_power") val combatPower: Long?,
    @SerialName("total_acts") val totalActs: Long?,
    @SerialName("total_kills") val totalKills: Long?,
    @SerialName("app_version") val appVersion: String,
    @SerialName("device_model") val deviceModel: String,
)

private fun isoInstant(epochMillis: Long): String = java.time.Instant.ofEpochMilli(epochMillis).toString()

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
