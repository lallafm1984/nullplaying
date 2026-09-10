package com.nullplaying.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nullplaying.data.SimpleGameRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
internal data class ArenaServerQaAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val expiresAtEpochSeconds: Long,
)

/** Supplied by the ADB-only live QA entry point and retained in process memory only. */
internal data class ArenaLiveServerQaCredentials(
    val email: String,
    val password: String,
) {
    val valid: Boolean
        get() = email.length in 3..320 && '@' in email && password.length in 8..1_024
}

internal interface ArenaServerQaSecureStore {
    fun readSession(): ArenaServerQaAuthSession?

    fun writeSession(session: ArenaServerQaAuthSession)

    fun readSyncReceipt(): SharedPlayerSyncReceipt?

    fun writeSyncReceipt(receipt: SharedPlayerSyncReceipt)

    fun clearSyncReceipt()

    fun readUnifiedSyncReceipt(): PlayerNetworkProfileSyncReceipt?

    fun writeUnifiedSyncReceipt(receipt: PlayerNetworkProfileSyncReceipt)

    fun clearUnifiedSyncReceipt()
}

/**
 * The QA package owns a separate Android Keystore key and private preferences file. Tokens never
 * enter the normal application's Supabase preferences and cannot be restored onto another device.
 */
internal class AndroidKeystoreArenaServerQaSecureStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : ArenaServerQaSecureStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val keyAlias = "${context.packageName}.arena_shared_player_qa.v1"
    private val associatedData = context.packageName.toByteArray(Charsets.UTF_8)
    private val lock = Any()

    override fun readSession(): ArenaServerQaAuthSession? = synchronized(lock) {
        readEncrypted(SESSION_ENTRY)?.let { encoded ->
            runCatching { json.decodeFromString<ArenaServerQaAuthSession>(encoded) }
                .getOrElse {
                    preferences.edit().remove(SESSION_ENTRY).commit()
                    null
                }
        }
    }

    override fun writeSession(session: ArenaServerQaAuthSession) = synchronized(lock) {
        writeEncrypted(SESSION_ENTRY, json.encodeToString(session))
    }

    override fun readSyncReceipt(): SharedPlayerSyncReceipt? = synchronized(lock) {
        readEncrypted(RECEIPT_ENTRY)?.let { encoded ->
            runCatching { json.decodeFromString<SharedPlayerSyncReceipt>(encoded) }
                .getOrElse {
                    preferences.edit().remove(RECEIPT_ENTRY).commit()
                    null
                }
        }
    }

    override fun writeSyncReceipt(receipt: SharedPlayerSyncReceipt) = synchronized(lock) {
        writeEncrypted(RECEIPT_ENTRY, json.encodeToString(receipt))
    }

    override fun clearSyncReceipt() = synchronized(lock) {
        check(preferences.edit().remove(RECEIPT_ENTRY).commit()) {
            "Could not clear Arena Server QA sync receipt"
        }
    }

    override fun readUnifiedSyncReceipt(): PlayerNetworkProfileSyncReceipt? = synchronized(lock) {
        readEncrypted(UNIFIED_RECEIPT_ENTRY)?.let { encoded ->
            runCatching { json.decodeFromString<PlayerNetworkProfileSyncReceipt>(encoded) }
                .getOrElse {
                    preferences.edit().remove(UNIFIED_RECEIPT_ENTRY).commit()
                    null
                }
        }
    }

    override fun writeUnifiedSyncReceipt(receipt: PlayerNetworkProfileSyncReceipt) =
        synchronized(lock) {
            writeEncrypted(UNIFIED_RECEIPT_ENTRY, json.encodeToString(receipt))
        }

    override fun clearUnifiedSyncReceipt() = synchronized(lock) {
        check(preferences.edit().remove(UNIFIED_RECEIPT_ENTRY).commit()) {
            "Could not clear Arena Server QA unified sync receipt"
        }
    }

    private fun readEncrypted(entry: String): String? {
        val encoded = preferences.getString(entry, null) ?: return null
        return runCatching {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            require(envelope.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES)
            val buffer = ByteBuffer.wrap(envelope)
            require(buffer.get().toInt() == ENVELOPE_VERSION)
            val ivSize = buffer.get().toInt() and 0xff
            require(ivSize in 12..16 && buffer.remaining() > ivSize)
            val iv = ByteArray(ivSize).also(buffer::get)
            val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData + entry.toByteArray(Charsets.UTF_8))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrElse {
            preferences.edit().remove(entry).commit()
            null
        }
    }

    private fun writeEncrypted(entry: String, plaintext: String) {
        require(plaintext.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData + entry.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val envelope = ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size)
            .put(ENVELOPE_VERSION.toByte())
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        check(preferences.edit().putString(
            entry,
            Base64.encodeToString(envelope, Base64.NO_WRAP),
        ).commit()) { "Could not persist Arena Server QA authentication state" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "arena_shared_player_qa_secure_v1"
        const val SESSION_ENTRY = "encrypted_anonymous_session"
        const val RECEIPT_ENTRY = "encrypted_sync_receipt"
        const val UNIFIED_RECEIPT_ENTRY = "encrypted_unified_sync_receipt"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENVELOPE_VERSION = 1
        const val MAX_PLAINTEXT_BYTES = 32 * 1024
        const val MIN_ENVELOPE_BYTES = 30
        const val MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + 64
    }
}

internal data class ArenaServerQaWireRequest(
    val endpoint: ArenaServerQaEndpoint,
    val accessToken: String?,
    val body: String?,
)

internal fun interface ArenaServerQaWireExecutor {
    suspend fun execute(request: ArenaServerQaWireRequest): String
}

/** HTTPS executor whose caller cannot provide a path, method, origin, or arbitrary header. */
internal class FixedOriginArenaServerQaWireExecutor(
    private val destination: ArenaServerQaDestination,
    private val publishableKey: String,
) : ArenaServerQaWireExecutor {
    init {
        require(publishableKey.isNotBlank())
        require(!publishableKey.startsWith("sb_secret_", ignoreCase = true))
        require(!publishableKey.contains("service_role", ignoreCase = true))
    }

    override suspend fun execute(request: ArenaServerQaWireRequest): String = try {
        withTimeout(REQUEST_TIMEOUT_MILLIS) {
            runInterruptible(Dispatchers.IO) {
                val endpoint = request.endpoint
                check(isArenaServerQaEndpointAllowed(endpoint.path, endpoint.method))
                val bodyBytes = request.body?.toByteArray(Charsets.UTF_8)
                require(bodyBytes == null || bodyBytes.size <= MAX_REQUEST_BYTES)
                require((endpoint.method == "GET") == (bodyBytes == null))
                val url = URL(destination.origin + endpoint.path)
                check(url.protocol == "https" && url.host.equals(
                    "${destination.projectRef}.supabase.co",
                    ignoreCase = true,
                ) && url.port == -1)
                val connection = url.openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.requestMethod = endpoint.method
                    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                    connection.readTimeout = READ_TIMEOUT_MILLIS
                    connection.setRequestProperty("apikey", publishableKey)
                    connection.setRequestProperty(
                        "Authorization",
                        "Bearer ${request.accessToken ?: publishableKey}",
                    )
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    if (bodyBytes != null) {
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(bodyBytes.size)
                        connection.outputStream.use { it.write(bodyBytes) }
                    }
                    val statusCode = connection.responseCode
                    if (statusCode in 300..399) {
                        throw ArenaServerQaHttpException(statusCode, "")
                    }
                    val responseLimit = if (statusCode in 200..299) {
                        when (endpoint) {
                            ArenaServerQaEndpoint.GET_DAILY_PUBLIC_PLAYER_ROSTER ->
                                MAX_DAILY_ROSTER_RESPONSE_BYTES
                            ArenaServerQaEndpoint.SYNC_PLAYER_NETWORK_PROFILE ->
                                MAX_UNIFIED_PROFILE_RESPONSE_BYTES
                            else -> MAX_RESPONSE_BYTES
                        }
                    } else {
                        MAX_ERROR_RESPONSE_BYTES
                    }
                    val contentLength = connection.contentLengthLong
                    if (contentLength > responseLimit) {
                        throw IOException("Arena Server QA response exceeded its size limit")
                    }
                    val stream = if (statusCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    val response = stream?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (output.size() + read > responseLimit) {
                                throw IOException("Arena Server QA response exceeded its size limit")
                            }
                            output.write(buffer, 0, read)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }.orEmpty()
                    if (statusCode !in 200..299) {
                        throw ArenaServerQaHttpException(statusCode, response)
                    }
                    response
                } finally {
                    connection.disconnect()
                }
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw IOException("Arena Server QA request timed out", timeout)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 12_000
        const val REQUEST_TIMEOUT_MILLIS = 20_000L
        const val MAX_REQUEST_BYTES = 64 * 1024
        const val MAX_RESPONSE_BYTES = 192 * 1024
        const val MAX_ERROR_RESPONSE_BYTES = 4 * 1024
    }
}

internal class ArenaServerQaHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IOException("Arena Server QA request failed ($statusCode)")

internal class ArenaServerQaRateLimitException(
    val retryAfterSeconds: Long,
) : IOException("Arena Server QA RPC requested a bounded retry")

/** QA auth session plus the fixed publication/roster RPCs; no match RPC is expressible. */
internal class ArenaServerQaSharedPlayerTransport internal constructor(
    private val secureStore: ArenaServerQaSecureStore,
    private val wire: ArenaServerQaWireExecutor,
    private val onIdentityReplaced: suspend () -> Unit,
    private val liveServerMode: Boolean = false,
    private val liveCredentials: () -> ArenaLiveServerQaCredentials? = { null },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : AuthenticatedSharedPlayerRpcTransport {
    private val authMutex = Mutex()
    private val syncMutex = Mutex()
    @Volatile private var currentSession: ArenaServerQaAuthSession? = null
    @Volatile private var lastValidatedAtEpochMillis: Long = 0L
    private var livePasswordSignInAttempted = false

    override suspend fun captureSessionIdentity(): String = ensureSession(forceValidation = true).userId

    override fun isSessionIdentityCurrent(identity: String): Boolean =
        currentSession?.userId == identity

    override fun isPlayerNetworkProfileCurrent(
        requestBody: String,
        nowEpochMillis: Long,
    ): Boolean {
        val identity = currentSession?.userId ?: secureStore.readSession()?.userId ?: return false
        return playerNetworkProfileSyncDecision(
            receipt = secureStore.readUnifiedSyncReceipt(),
            userId = identity,
            payloadHash = sha256Hex(requestBody),
            nowEpochMillis = nowEpochMillis,
        ) == SharedPlayerSyncDecision.SKIP_UNCHANGED
    }

    override fun shouldPublishPlayerNetworkProfile(
        requestBody: String,
        nowEpochMillis: Long,
    ): Boolean {
        val identity = currentSession?.userId ?: secureStore.readSession()?.userId
        return shouldPublishPlayerNetworkProfile(
            receipt = secureStore.readUnifiedSyncReceipt(),
            userId = identity,
            payloadHash = sha256Hex(requestBody),
            characterCount = playerNetworkProfilePayloadSummary(requestBody, json).characterCount,
            nowEpochMillis = nowEpochMillis,
        )
    }

    override suspend fun syncPlayerNetworkProfile(requestBody: String): String? =
        syncMutex.withLock {
            val session = ensureSession(forceValidation = true)
            val identity = session.userId
            val now = nowEpochMillis()
            val hash = sha256Hex(requestBody)
            val expected = playerNetworkProfilePayloadSummary(requestBody, json)
            val receipt = secureStore.readUnifiedSyncReceipt()
            when (playerNetworkProfileSyncDecision(receipt, identity, hash, now)) {
                SharedPlayerSyncDecision.SKIP_UNCHANGED -> return@withLock deduplicatedPlayerNetworkProfileResponse(
                    checkNotNull(receipt),
                    expected,
                )
                SharedPlayerSyncDecision.DEFER_CHANGED ->
                    throw IOException("Public hero changes are waiting for the safe sync interval")
                SharedPlayerSyncDecision.SEND -> Unit
            }
            val response = try {
                wire.execute(ArenaServerQaWireRequest(
                    endpoint = ArenaServerQaEndpoint.SYNC_PLAYER_NETWORK_PROFILE,
                    accessToken = session.accessToken,
                    body = requestBody,
                ))
            } catch (error: ArenaServerQaHttpException) {
                if (isMissingPlayerNetworkProfileRpc(error.statusCode, error.responseBody)) {
                    return@withLock null
                }
                throw error
            }
            throwIfBoundedRateLimit(response)
            val verified = verifiedPlayerNetworkProfileResponse(response, expected, json)
            check(isSessionIdentityCurrent(identity)) {
                "Shared-player account changed during unified publication"
            }
            secureStore.writeUnifiedSyncReceipt(PlayerNetworkProfileSyncReceipt(
                userId = identity,
                payloadHash = hash,
                syncedAtEpochMillis = now,
                serverNowEpochMillis = verified.serverNowEpochMillis,
            ))
            response
        }

    override suspend fun syncPublicPlayerSnapshots(requestBody: String): String =
        syncMutex.withLock {
            val session = ensureSession(forceValidation = true)
            val identity = session.userId
            val now = nowEpochMillis()
            val hash = sha256Hex(requestBody)
            val expectedCount = json.parseToJsonElement(requestBody).jsonObject["p_snapshots"]
                ?.jsonArray?.size ?: error("Shared-player request has no snapshot array")
            when (sharedPlayerSyncDecision(
                secureStore.readSyncReceipt(), identity, hash, now,
            )) {
                SharedPlayerSyncDecision.SKIP_UNCHANGED -> return@withLock buildJsonObject {
                    put("rules_version", com.nullplaying.model.SHARED_PLAYER_RULES_VERSION)
                    put("synced_count", expectedCount)
                    put("server_now", now)
                    put("deduplicated", true)
                }.toString()
                SharedPlayerSyncDecision.DEFER_CHANGED ->
                    throw IOException("Public hero changes are waiting for the safe sync interval")
                SharedPlayerSyncDecision.SEND -> Unit
            }
            val response = wire.execute(ArenaServerQaWireRequest(
                endpoint = ArenaServerQaEndpoint.SYNC_PUBLIC_PLAYER_SNAPSHOTS,
                accessToken = session.accessToken,
                body = requestBody,
            ))
            throwIfBoundedRateLimit(response)
            val verified = json.decodeFromString<PublicPlayerSyncResponse>(response)
            check(verified.rulesVersion == com.nullplaying.model.SHARED_PLAYER_RULES_VERSION &&
                verified.syncedCount == expectedCount && verified.serverNowEpochMillis > 0L
            ) { "Shared-player sync response failed validation" }
            check(isSessionIdentityCurrent(identity)) {
                "Shared-player account changed during publication"
            }
            secureStore.writeSyncReceipt(SharedPlayerSyncReceipt(identity, hash, now))
            response
        }

    override suspend fun getDailyPublicPlayerRoster(requestBody: String): String {
        val session = ensureSession(forceValidation = true)
        val identity = session.userId
        val response = wire.execute(ArenaServerQaWireRequest(
            endpoint = ArenaServerQaEndpoint.GET_DAILY_PUBLIC_PLAYER_ROSTER,
            accessToken = session.accessToken,
            body = requestBody,
        ))
        throwIfBoundedRateLimit(response)
        check(isSessionIdentityCurrent(identity)) {
            "Shared-player account changed during roster request"
        }
        return response
    }

    private fun throwIfBoundedRateLimit(response: String) {
        val value = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
            ?: return
        val rateLimited = value["rate_limited"]?.jsonPrimitive?.content == "true"
        if (!rateLimited) return
        val retryAfter = value["retry_after_seconds"]?.jsonPrimitive?.longOrNull ?: return
        if (retryAfter in 1L..MAX_QA_RETRY_AFTER_SECONDS) {
            throw ArenaServerQaRateLimitException(retryAfter)
        }
        throw IOException("Arena Server QA RPC rate limit exceeded the retry window")
    }

    private suspend fun ensureSession(forceValidation: Boolean): ArenaServerQaAuthSession =
        authMutex.withLock {
            val nowMillis = nowEpochMillis()
            val nowSeconds = nowMillis / 1_000L
            if (liveServerMode && !livePasswordSignInAttempted) {
                livePasswordSignInAttempted = true
                val previouslyStoredUserId = currentSession?.userId ?: secureStore.readSession()?.userId
                val credentials = liveCredentials()?.takeIf(ArenaLiveServerQaCredentials::valid)
                    ?: error("Dedicated live Arena QA credentials were not supplied")
                val authenticated = authenticate(
                    endpoint = ArenaServerQaEndpoint.QA_PASSWORD_SIGN_IN,
                    body = buildJsonObject {
                        put("email", credentials.email)
                        put("password", credentials.password)
                    }.toString(),
                    fallbackUserId = null,
                )
                return@withLock activateSession(
                    authenticated,
                    replaceIdentity = previouslyStoredUserId != authenticated.userId,
                )
            }
            val stored = currentSession ?: secureStore.readSession()?.also { currentSession = it }
            if (stored != null && stored.expiresAtEpochSeconds > nowSeconds + 60L) {
                val validationDue = forceValidation &&
                    nowMillis - lastValidatedAtEpochMillis >= SESSION_VALIDATION_COOLDOWN_MILLIS
                if (!validationDue) return@withLock stored
                try {
                    validateSession(stored)
                    lastValidatedAtEpochMillis = nowMillis
                    return@withLock stored
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ArenaServerQaHttpException) {
                    if (!shouldReplaceAnonymousSession(error.statusCode, error.responseBody)) {
                        throw error
                    }
                }
            }
            val refreshed = stored?.let { tryRefreshSession(it) }
            if (refreshed != null) return@withLock activateSession(refreshed, replaceIdentity = false)
            check(!liveServerMode) {
                "Dedicated live Arena QA session could not be renewed after password sign-in"
            }
            val replacement = authenticate(
                endpoint = ArenaServerQaEndpoint.ANONYMOUS_SIGN_UP,
                body = "{}",
                fallbackUserId = null,
            )
            activateSession(replacement, replaceIdentity = true)
        }

    private suspend fun tryRefreshSession(
        stored: ArenaServerQaAuthSession,
    ): ArenaServerQaAuthSession? {
        if (stored.refreshToken.isBlank()) return null
        return try {
            authenticate(
                endpoint = ArenaServerQaEndpoint.ANONYMOUS_TOKEN_REFRESH,
                body = buildJsonObject { put("refresh_token", stored.refreshToken) }.toString(),
                fallbackUserId = stored.userId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ArenaServerQaHttpException) {
            if (shouldReplaceAnonymousSession(error.statusCode, error.responseBody)) null else throw error
        }
    }

    private suspend fun validateSession(session: ArenaServerQaAuthSession) {
        val response = wire.execute(ArenaServerQaWireRequest(
            endpoint = ArenaServerQaEndpoint.ANONYMOUS_USER,
            accessToken = session.accessToken,
            body = null,
        ))
        val userId = json.parseToJsonElement(response).jsonObject["id"]?.jsonPrimitive?.content
            ?: throw IOException("Arena Server QA identity was not returned")
        check(userId == session.userId) { "Arena Server QA identity changed" }
    }

    private suspend fun authenticate(
        endpoint: ArenaServerQaEndpoint,
        body: String,
        fallbackUserId: String?,
    ): ArenaServerQaAuthSession {
        val response = json.parseToJsonElement(wire.execute(ArenaServerQaWireRequest(
            endpoint = endpoint,
            accessToken = null,
            body = body,
        ))).jsonObject
        val accessToken = response["access_token"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN_CHARS }
            ?: error("Arena Server QA access token was not returned")
        val refreshToken = response["refresh_token"]?.jsonPrimitive?.content.orEmpty()
            .takeIf { it.length <= MAX_TOKEN_CHARS }
            ?: error("Arena Server QA refresh token exceeded its limit")
        val userId = response["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: fallbackUserId
            ?: error("Arena Server QA user ID was not returned")
        require(runCatching { UUID.fromString(userId).toString() == userId.lowercase() }
            .getOrDefault(false))
        val expiresIn = response["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
            ?.coerceIn(60L, MAX_SESSION_LIFETIME_SECONDS) ?: 3_600L
        return ArenaServerQaAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            expiresAtEpochSeconds = nowEpochMillis() / 1_000L + expiresIn,
        )
    }

    private suspend fun activateSession(
        session: ArenaServerQaAuthSession,
        replaceIdentity: Boolean,
    ): ArenaServerQaAuthSession {
        secureStore.writeSession(session)
        currentSession = session
        lastValidatedAtEpochMillis = nowEpochMillis()
        if (replaceIdentity) {
            secureStore.clearSyncReceipt()
            secureStore.clearUnifiedSyncReceipt()
            onIdentityReplaced()
        }
        return session
    }

    companion object {
        fun create(
            context: Context,
            runtimeConfig: ArenaServerQaRuntimeConfig,
            repository: SimpleGameRepository,
            liveCredentials: () -> ArenaLiveServerQaCredentials? = { null },
        ): ArenaServerQaSharedPlayerTransport {
            check(runtimeConfig.enabled) { "Arena Server QA transport is not authorized" }
            val destination = requireNotNull(
                ArenaServerQaDestination.parse(runtimeConfig.qaSupabaseUrl),
            )
            return ArenaServerQaSharedPlayerTransport(
                secureStore = AndroidKeystoreArenaServerQaSecureStore(context),
                wire = FixedOriginArenaServerQaWireExecutor(
                    destination = destination,
                    publishableKey = runtimeConfig.qaSupabasePublishableKey,
                ),
                onIdentityReplaced = {
                    repository.clearPublicPlayerRosters(System.currentTimeMillis())
                },
                liveServerMode =
                    runtimeConfig.applicationId == ARENA_LIVE_SERVER_QA_APPLICATION_ID,
                liveCredentials = liveCredentials,
            )
        }

        private const val SESSION_VALIDATION_COOLDOWN_MILLIS = 5_000L
        private const val MAX_TOKEN_CHARS = 16 * 1024
        private const val MAX_SESSION_LIFETIME_SECONDS = 24L * 60L * 60L
        private const val MAX_QA_RETRY_AFTER_SECONDS = 5L
    }
}

private const val MAX_UNIFIED_PROFILE_RESPONSE_BYTES = 8 * 1024

internal fun isMissingPlayerNetworkProfileRpc(statusCode: Int, responseBody: String): Boolean {
    if (statusCode != 404) return false
    val normalized = responseBody.lowercase()
    return "pgrst202" in normalized && "sync_player_network_profile" in normalized
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
