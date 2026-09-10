package com.nullplaying.time

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

/** Identifies whether an epoch anchor came from the server or a conservative fallback. */
enum class TrustedTimeConfidence {
    VERIFIED_SERVER,
    PROVISIONAL,
}

/**
 * A persisted mapping from Android's monotonic clock to Unix epoch milliseconds.
 *
 * [serverEpochMs] retains its historical storage name even for a provisional anchor. Callers must
 * inspect [confidence] before treating the value as server verified.
 */
data class TrustedTimeAnchor(
    val version: Int,
    val serverEpochMs: Long,
    val observedElapsedMs: Long,
    val bootCount: Long,
    val confidence: TrustedTimeConfidence,
)

data class TrustedTimeReading(
    val epochMillis: Long,
    val confidence: TrustedTimeConfidence,
) {
    val isServerVerified: Boolean
        get() = confidence == TrustedTimeConfidence.VERIFIED_SERVER
}

/**
 * An immutable, non-persisted proposal for installing a server observation.
 *
 * The application can first reconcile legacy game state against [proposedEpochMillis], then call
 * [TrustedGameClock.adoptVerifiedServerObservation]. Adoption fails if another anchor won the race
 * or the device rebooted between preview and adoption.
 */
class VerifiedServerTimeObservation internal constructor(
    val observedServerEpochMs: Long,
    val proposedEpochMillis: Long,
    val observedElapsedMs: Long,
    val bootCount: Long,
    val previousReading: TrustedTimeReading?,
    internal val expectedAnchor: TrustedTimeAnchor?,
) {
    val correctsProvisionalBackwards: Boolean
        get() = previousReading?.confidence == TrustedTimeConfidence.PROVISIONAL &&
            proposedEpochMillis < previousReading.epochMillis
}

fun interface ElapsedRealtimeSource {
    fun elapsedRealtimeMs(): Long
}

fun interface BootCountSource {
    fun bootCount(): Long?
}

interface TrustedTimeAnchorStore {
    fun read(): TrustedTimeAnchor?

    /** Returns false when the anchor could not be durably persisted. */
    fun write(anchor: TrustedTimeAnchor): Boolean
}

/** SharedPreferences-backed durable storage used by the Android application. */
class SharedPreferencesTrustedTimeAnchorStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) : TrustedTimeAnchorStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    override fun read(): TrustedTimeAnchor? {
        if (!REQUIRED_KEYS.all(preferences::contains)) return null

        val version = preferences.getInt(KEY_VERSION, INVALID_VERSION)
        val source = runCatching {
            TrustedTimeConfidence.valueOf(
                preferences.getString(KEY_SOURCE, null) ?: return null,
            )
        }.getOrNull() ?: return null

        return TrustedTimeAnchor(
            version = version,
            serverEpochMs = preferences.getLong(KEY_SERVER_EPOCH_MS, INVALID_TIME),
            observedElapsedMs = preferences.getLong(KEY_OBSERVED_ELAPSED_MS, INVALID_TIME),
            bootCount = preferences.getLong(KEY_BOOT_COUNT, INVALID_BOOT_COUNT),
            confidence = source,
        ).takeIf(TrustedGameClock::isSupportedAnchor)
    }

    override fun write(anchor: TrustedTimeAnchor): Boolean = preferences.edit()
        .putInt(KEY_VERSION, anchor.version)
        .putLong(KEY_SERVER_EPOCH_MS, anchor.serverEpochMs)
        .putLong(KEY_OBSERVED_ELAPSED_MS, anchor.observedElapsedMs)
        .putLong(KEY_BOOT_COUNT, anchor.bootCount)
        .putString(KEY_SOURCE, anchor.confidence.name)
        .commit()

    companion object {
        const val PREFERENCES_NAME = "trusted_game_clock"

        private const val KEY_VERSION = "anchor_version"
        private const val KEY_SERVER_EPOCH_MS = "server_epoch_ms"
        private const val KEY_OBSERVED_ELAPSED_MS = "observed_elapsed_ms"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_SOURCE = "source"
        private const val INVALID_VERSION = -1
        private const val INVALID_TIME = -1L
        private const val INVALID_BOOT_COUNT = -1L
        private val REQUIRED_KEYS = listOf(
            KEY_VERSION,
            KEY_SERVER_EPOCH_MS,
            KEY_OBSERVED_ELAPSED_MS,
            KEY_BOOT_COUNT,
            KEY_SOURCE,
        )
    }
}

/**
 * Converts a server epoch observation into a clock that is unaffected by device wall-clock edits.
 *
 * An anchor is only usable in the boot in which it was recorded and while elapsedRealtime has not
 * regressed. A server observation can always recover an unavailable clock. A provisional anchor is
 * deliberately replaceable by a lower verified server value; this lets the application repair a
 * legacy/future-tainted save without preserving an untrusted wall-clock jump. Once the anchor is
 * server verified, subsequent server refreshes never move the returned time backwards.
 */
class TrustedGameClock(
    private val store: TrustedTimeAnchorStore,
    private val elapsedRealtimeSource: ElapsedRealtimeSource,
    private val bootCountSource: BootCountSource,
) {
    constructor(context: Context) : this(
        store = SharedPreferencesTrustedTimeAnchorStore(context),
        elapsedRealtimeSource = ElapsedRealtimeSource(SystemClock::elapsedRealtime),
        bootCountSource = AndroidBootCountSource(context.applicationContext),
    )

    private val lock = Any()
    private var anchor: TrustedTimeAnchor? = readSupportedAnchor()

    /** Returns null after reboot, monotonic-clock regression, or when no usable anchor exists. */
    fun nowOrNull(): TrustedTimeReading? = synchronized(lock) {
        readingAt(anchor, currentMonotonicPoint())
    }

    /**
     * Installs a TLS/server-derived epoch observation at the current monotonic instant.
     *
     * For an already verified anchor this operation is monotonic even when an imprecise server Date
     * header is slightly behind. For a provisional anchor, the verified value is authoritative and
     * may be lower.
     */
    fun installVerifiedServerObservation(serverEpochMs: Long): TrustedTimeReading? =
        previewVerifiedServerObservation(serverEpochMs)?.let(::adoptVerifiedServerObservation)

    /** Builds a server-anchor proposal without mutating memory or SharedPreferences. */
    fun previewVerifiedServerObservation(
        serverEpochMs: Long,
    ): VerifiedServerTimeObservation? =
        synchronized(lock) {
            if (serverEpochMs < 0L) return@synchronized null
            val point = currentMonotonicPoint() ?: return@synchronized null
            val previous = readingAt(anchor, point)
            if (
                previous?.confidence == TrustedTimeConfidence.VERIFIED_SERVER &&
                serverEpochMs > saturatingAdd(
                    previous.epochMillis,
                    MAX_VERIFIED_REFRESH_FORWARD_CORRECTION_MILLIS,
                )
            ) {
                return@synchronized null
            }
            val proposedEpoch = if (
                previous?.confidence == TrustedTimeConfidence.VERIFIED_SERVER
            ) {
                maxOf(serverEpochMs, previous.epochMillis)
            } else {
                serverEpochMs
            }
            VerifiedServerTimeObservation(
                observedServerEpochMs = serverEpochMs,
                proposedEpochMillis = proposedEpoch,
                observedElapsedMs = point.elapsedRealtimeMs,
                bootCount = point.bootCount,
                previousReading = previous,
                expectedAnchor = anchor,
            )
        }

    /** Durably adopts a proposal previously returned by [previewVerifiedServerObservation]. */
    fun adoptVerifiedServerObservation(
        observation: VerifiedServerTimeObservation,
    ): TrustedTimeReading? = synchronized(lock) {
        if (anchor != observation.expectedAnchor) return@synchronized null
        val point = currentMonotonicPoint() ?: return@synchronized null
        if (point.bootCount != observation.bootCount) return@synchronized null
        if (point.elapsedRealtimeMs < observation.observedElapsedMs) return@synchronized null

        install(
            newAnchor = TrustedTimeAnchor(
                version = CURRENT_ANCHOR_VERSION,
                serverEpochMs = observation.proposedEpochMillis,
                observedElapsedMs = observation.observedElapsedMs,
                bootCount = observation.bootCount,
                confidence = TrustedTimeConfidence.VERIFIED_SERVER,
            ),
            readingPoint = point,
        )
    }

    /**
     * Installs a caller-computed conservative fallback only when the current anchor is unavailable.
     * A usable verified or provisional anchor is never replaced by this method.
     */
    fun installProvisionalAnchor(provisionalEpochMs: Long): TrustedTimeReading? =
        synchronized(lock) {
            if (provisionalEpochMs < 0L) return@synchronized null
            val point = currentMonotonicPoint() ?: return@synchronized null
            readingAt(anchor, point)?.let { return@synchronized it }
            install(
                TrustedTimeAnchor(
                    version = CURRENT_ANCHOR_VERSION,
                    serverEpochMs = provisionalEpochMs,
                    observedElapsedMs = point.elapsedRealtimeMs,
                    bootCount = point.bootCount,
                    confidence = TrustedTimeConfidence.PROVISIONAL,
                ),
                readingPoint = point,
            )
        }

    /**
     * Persists the current projected epoch as a fresh anchor without changing its confidence.
     * This is useful before backgrounding so a reboot-offline fallback starts from a recent floor.
     */
    fun checkpoint(): TrustedTimeReading? = synchronized(lock) {
        val point = currentMonotonicPoint() ?: return@synchronized null
        val current = readingAt(anchor, point) ?: return@synchronized null
        install(
            newAnchor = TrustedTimeAnchor(
                version = CURRENT_ANCHOR_VERSION,
                serverEpochMs = current.epochMillis,
                observedElapsedMs = point.elapsedRealtimeMs,
                bootCount = point.bootCount,
                confidence = current.confidence,
            ),
            readingPoint = point,
        )
    }

    /** Exposes the last durable anchor so a reboot fallback can be bounded by the caller. */
    fun persistedAnchorOrNull(): TrustedTimeAnchor? = synchronized(lock) {
        anchor
    }

    private fun install(
        newAnchor: TrustedTimeAnchor,
        readingPoint: MonotonicPoint,
    ): TrustedTimeReading? {
        if (!store.write(newAnchor)) return null
        anchor = newAnchor
        return readingAt(newAnchor, readingPoint)
    }

    private fun currentMonotonicPoint(): MonotonicPoint? {
        val elapsedRealtimeMs = runCatching(elapsedRealtimeSource::elapsedRealtimeMs)
            .getOrNull()
            ?.takeIf { it >= 0L }
            ?: return null
        val bootCount = runCatching(bootCountSource::bootCount)
            .getOrNull()
            ?.takeIf { it >= 0L }
            ?: return null
        return MonotonicPoint(elapsedRealtimeMs, bootCount)
    }

    private fun readingAt(
        candidate: TrustedTimeAnchor?,
        point: MonotonicPoint?,
    ): TrustedTimeReading? {
        if (candidate == null || point == null || !isSupportedAnchor(candidate)) return null
        if (point.bootCount != candidate.bootCount) return null
        if (point.elapsedRealtimeMs < candidate.observedElapsedMs) return null

        val elapsedDelta = point.elapsedRealtimeMs - candidate.observedElapsedMs
        return TrustedTimeReading(
            epochMillis = saturatingAdd(candidate.serverEpochMs, elapsedDelta),
            confidence = candidate.confidence,
        )
    }

    private fun readSupportedAnchor(): TrustedTimeAnchor? = runCatching(store::read)
        .getOrNull()
        ?.takeIf(::isSupportedAnchor)

    private data class MonotonicPoint(
        val elapsedRealtimeMs: Long,
        val bootCount: Long,
    )

    companion object {
        const val CURRENT_ANCHOR_VERSION = 1
        const val MAX_VERIFIED_REFRESH_FORWARD_CORRECTION_MILLIS = 5L * 60L * 1_000L

        internal fun isSupportedAnchor(anchor: TrustedTimeAnchor): Boolean =
            anchor.version == CURRENT_ANCHOR_VERSION &&
                anchor.serverEpochMs >= 0L &&
                anchor.observedElapsedMs >= 0L &&
                anchor.bootCount >= 0L

        internal fun saturatingAdd(left: Long, right: Long): Long {
            require(left >= 0L) { "left must be non-negative" }
            require(right >= 0L) { "right must be non-negative" }
            return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
        }
    }
}

/** A process cannot span a reboot, so cache the first successful boot-count read for this process. */
private class AndroidBootCountSource(context: Context) : BootCountSource {
    private val contentResolver = context.contentResolver
    @Volatile
    private var cachedBootCount: Long? = null

    override fun bootCount(): Long? = cachedBootCount ?: synchronized(this) {
        cachedBootCount ?: Settings.Global.getInt(
            contentResolver,
            Settings.Global.BOOT_COUNT,
            -1,
        ).takeIf { it >= 0 }?.toLong()?.also { cachedBootCount = it }
    }
}
