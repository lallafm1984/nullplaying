package com.nullplaying.ads

/**
 * Process-lifetime ownership for one full-screen rewarded ad. A matching SDK terminal callback is
 * authoritative. If that callback disappears, returning to the resumed host after a short grace
 * period (or a long foreground watchdog) releases the gameplay pause.
 */
class RewardedAdSessionCoordinator(
    private val resumeRecoveryGraceMillis: Long = DEFAULT_RESUME_RECOVERY_GRACE_MILLIS,
    private val foregroundWatchdogMillis: Long = DEFAULT_FOREGROUND_WATCHDOG_MILLIS,
) {
    private var active: Session? = null

    @Synchronized
    fun begin(token: String, nowElapsedRealtimeMillis: Long): Boolean {
        if (token.isBlank() || nowElapsedRealtimeMillis < 0L || active != null) return false
        active = Session(token = token, startedAt = nowElapsedRealtimeMillis)
        return true
    }

    @Synchronized
    fun hostPaused(nowElapsedRealtimeMillis: Long) {
        val session = active ?: return
        if (nowElapsedRealtimeMillis < session.startedAt) return
        active = session.copy(hostPausedAt = nowElapsedRealtimeMillis)
    }

    @Synchronized
    fun terminal(token: String): Boolean {
        if (active?.token != token) return false
        active = null
        return true
    }

    @Synchronized
    fun recoverIfOrphaned(
        token: String,
        nowElapsedRealtimeMillis: Long,
        hostResumed: Boolean,
    ): Boolean {
        val session = active ?: return false
        if (session.token != token || !hostResumed || nowElapsedRealtimeMillis < session.startedAt) {
            return false
        }
        val resumedAfterFullScreen = session.hostPausedAt?.let { pausedAt ->
            nowElapsedRealtimeMillis >= pausedAt &&
                nowElapsedRealtimeMillis - pausedAt >= resumeRecoveryGraceMillis
        } ?: false
        val watchdogExpired = nowElapsedRealtimeMillis - session.startedAt >= foregroundWatchdogMillis
        if (!resumedAfterFullScreen && !watchdogExpired) return false
        active = null
        return true
    }

    @Synchronized
    fun activeToken(): String? = active?.token

    private data class Session(
        val token: String,
        val startedAt: Long,
        val hostPausedAt: Long? = null,
    )

    companion object {
        const val DEFAULT_RESUME_RECOVERY_GRACE_MILLIS = 3_000L
        const val DEFAULT_FOREGROUND_WATCHDOG_MILLIS = 10L * 60L * 1_000L
    }
}
