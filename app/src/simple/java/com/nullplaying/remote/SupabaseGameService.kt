package com.nullplaying.remote

import android.content.Context
import android.content.res.Resources
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.nullplaying.BuildConfig
import com.nullplaying.data.GameSnapshot
import com.nullplaying.data.SimpleGameRepository
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
internal const val RANKING_CACHE_TTL_MILLIS = 60L * 60L * 1_000L
internal const val RANKING_SYNC_COOLDOWN_MILLIS = 5L * 60L * 1_000L

internal fun isRankingCacheFresh(fetchedAt: Long, now: Long): Boolean =
    fetchedAt in 1L..now && now - fetchedAt < RANKING_CACHE_TTL_MILLIS

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
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val authMutex = Mutex()
    private val rankingMutex = Mutex()
    private val rankingSyncMutex = Mutex()
    private val sessionLogMutex = Mutex()
    private val rankingQueueLock = Any()
    private val lifecycleStateLock = Any()
    private val sessionLogLifecycle = SessionLogLifecycle()
    private val sessionQueueLock = Any()
    private val announcementDisplayStore = AppAnnouncementDisplayStore(context)
    private var lastRankingFetchAttemptAt = 0L
    private var sessionValidatedThisProcess = false
    private var lastSessionValidationAtEpochMillis = 0L
    private val _rankingSnapshot = MutableStateFlow<RemoteRankingSnapshot?>(null)
    val rankingSnapshot: StateFlow<RemoteRankingSnapshot?> = _rankingSnapshot.asStateFlow()
    private val _rankingError = MutableStateFlow<String?>(null)
    val rankingError: StateFlow<String?> = _rankingError.asStateFlow()
    private val _connectionState = MutableStateFlow<SupabaseConnectionState>(
        if (isConfigured) SupabaseConnectionState.Connecting else SupabaseConnectionState.Disabled,
    )
    val connectionState: StateFlow<SupabaseConnectionState> = _connectionState.asStateFlow()

    private val isConfigured: Boolean
        get() = !BuildConfig.DEBUG && BuildConfig.SUPABASE_URL.startsWith("https://") &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

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
        val memorySnapshot = _rankingSnapshot.value?.forCharacter(characterId)
        if (!forceRefresh && memorySnapshot != null && isRankingCacheFresh(memorySnapshot.fetchedAtEpochMillis, now)) {
            _rankingSnapshot.value = memorySnapshot
            _rankingError.value = null
            return@withLock Result.success(memorySnapshot)
        }

        val diskSnapshot = readRankingCache(characterId)
        val cachedSnapshot = listOfNotNull(memorySnapshot, diskSnapshot)
            .maxByOrNull(RemoteRankingSnapshot::fetchedAtEpochMillis)
        if (cachedSnapshot != null) _rankingSnapshot.value = cachedSnapshot
        if (!forceRefresh && cachedSnapshot != null && isRankingCacheFresh(cachedSnapshot.fetchedAtEpochMillis, now)) {
            _rankingError.value = null
            return@withLock Result.success(cachedSnapshot)
        }
        if (
            !forceRefresh &&
            lastRankingFetchAttemptAt > 0L &&
            now >= lastRankingFetchAttemptAt &&
            now - lastRankingFetchAttemptAt < RANKING_RETRY_COOLDOWN_MILLIS
        ) {
            if (cachedSnapshot != null) return@withLock Result.success(cachedSnapshot)
            return@withLock Result.failure(IOException("랭킹 재시도 대기 중입니다"))
        }

        _rankingError.value = null
        lastRankingFetchAttemptAt = now
        Log.d(TAG, "Ranking fetch started")
        try {
            require(isConfigured) { "Supabase 연결 정보가 설정되지 않았습니다" }
            val session = ensureSession()
            val response = json.decodeFromString<CompactLeaderboardResponse>(
                request(
                    path = "/rest/v1/rpc/get_leaderboard_v2",
                    method = "POST",
                    accessToken = session.accessToken,
                    body = "{\"p_character_id\":${json.encodeToString(characterId)},\"p_limit\":1000}",
                ),
            )
            val mapped = response.entries
                .map { it.toRemote(characterId) }
                .filter { it.rank <= MAX_LEADERBOARD_RANK }
                .distinctBy(RemoteRankingEntry::characterId)
            val myEntry = response.myEntry?.toRemote(characterId)
                ?: mapped.singleOrNull { it.isMe }
            val snapshot = RemoteRankingSnapshot(
                requestedCharacterId = characterId,
                fetchedAtEpochMillis = System.currentTimeMillis(),
                totalParticipants = response.totalParticipants,
                entries = mapped,
                myEntry = myEntry,
            )
            writeRankingCache(snapshot)
            _rankingSnapshot.value = snapshot
            _rankingError.value = null
            Log.d(
                TAG,
                "Ranking fetch succeeded: entries=${snapshot.entries.size}, " +
                    "total=${snapshot.totalParticipants}",
            )
            Result.success(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _rankingError.value = error.userMessage()
            Log.w(TAG, "Ranking fetch failed", error)
            Result.failure(error)
        }
    }

    fun queueRankingSync(snapshot: GameSnapshot) {
        if (!isConfigured || !snapshot.ready) return
        val entries = mutableListOf<RankingSyncEntry>()
        for (character in snapshot.characters) {
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
                displayName = state.hero.name.trim().take(24).ifBlank { "이름 없는 모험가" },
                heroClass = state.hero.heroClass.name,
                level = state.hero.level,
                combatPower = combatPower,
            )
        }
        val encoded = json.encodeToString(entries)
        synchronized(rankingQueueLock) {
            if (encoded == preferences.getString(LAST_RANKING_SYNC_KEY, null)) {
                preferences.edit().remove(PENDING_RANKING_SYNC_KEY).commit()
                return
            }
            if (encoded == preferences.getString(PENDING_RANKING_SYNC_KEY, null)) return
            preferences.edit().putString(PENDING_RANKING_SYNC_KEY, encoded).commit()
        }
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
            fetchRanking(activeCharacterId, forceRefresh = true)
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
        val entries = cache.entries.map { it.toRemote(characterId) }
        return RemoteRankingSnapshot(
            requestedCharacterId = characterId,
            fetchedAtEpochMillis = cache.fetchedAtEpochMillis,
            totalParticipants = cache.totalParticipants,
            entries = entries,
            myEntry = entries.singleOrNull { it.isMe },
            isFromCache = true,
        )
    }

    private fun writeRankingCache(snapshot: RemoteRankingSnapshot) {
        val cache = PersistedRankingCache(
            fetchedAtEpochMillis = snapshot.fetchedAtEpochMillis,
            totalParticipants = snapshot.totalParticipants,
            entries = snapshot.entries.map { CompactLeaderboardRow.fromRemote(it) },
        )
        preferences.edit().putString(RANKING_CACHE_KEY, json.encodeToString(cache)).apply()
    }

    private fun RemoteRankingSnapshot.forCharacter(characterId: String): RemoteRankingSnapshot {
        val remapped = entries.map { entry ->
            entry.copy(isMe = entry.characterId == characterId)
        }
        return copy(
            requestedCharacterId = characterId,
            entries = remapped,
            myEntry = remapped.singleOrNull { it.isMe },
        )
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
        if (previous != null && previous.userId != replacement.userId) {
            clearRemoteStateForReplacedIdentity()
        }
        saveSession(replacement)
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

    private fun clearRemoteStateForReplacedIdentity() {
        synchronized(rankingQueueLock) {
            preferences.edit()
                .remove(PENDING_RANKING_SYNC_KEY)
                .remove(LAST_RANKING_SYNC_KEY)
                .remove(LAST_RANKING_SYNC_AT_KEY)
                .remove(RANKING_CACHE_KEY)
                .commit()
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
        lastRankingFetchAttemptAt = 0L
        _rankingSnapshot.value = null
        _rankingError.value = null
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
    ): String = try {
        check(isConfigured) { "Supabase is disabled in QA builds" }
        withTimeout(REQUEST_TIMEOUT_MILLIS) {
            runInterruptible(Dispatchers.IO) {
                val connection = URL(
                    BuildConfig.SUPABASE_URL.trimEnd('/') + path,
                ).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = method
                    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                    connection.readTimeout = READ_TIMEOUT_MILLIS
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
                    val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (code !in 200..299 && !(allowSessionLogDuplicate && isDuplicateSessionLogConflict(code, response))) {
                        throw SupabaseHttpException(code, response.take(240))
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
        const val MIN_RANKING_LEVEL = 20L
        const val MAX_LEADERBOARD_RANK = 1_000
        const val MAX_PENDING_SESSION_LOGS = 100
        const val RANKING_RETRY_COOLDOWN_MILLIS = 60_000L
        const val TAG = "AlarmQuestRanking"
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 12_000
        const val REQUEST_TIMEOUT_MILLIS = 20_000L
        const val SESSION_VALIDATION_COOLDOWN_MILLIS = 5_000L
    }
}

private class SupabaseHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IOException("Supabase 요청 실패 ($statusCode): $responseBody")

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
private data class CompactLeaderboardResponse(
    @SerialName("t") val totalParticipants: Int,
    @SerialName("e") val entries: List<CompactLeaderboardRow> = emptyList(),
    @SerialName("m") val myEntry: CompactLeaderboardRow? = null,
)

@Serializable
private data class CompactLeaderboardRow(
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
        rank = rankNumber.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
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
private data class PersistedRankingCache(
    val fetchedAtEpochMillis: Long,
    val totalParticipants: Int,
    val entries: List<CompactLeaderboardRow>,
)

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
