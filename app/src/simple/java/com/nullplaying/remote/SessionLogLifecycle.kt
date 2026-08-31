package com.nullplaying.remote

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure lifecycle gate. Persist synchronously before allowing a background transition. */
internal class SessionLogLifecycle {
    private var foreground = false

    @Synchronized
    fun foregrounded() {
        foreground = true
    }

    @Synchronized
    fun backgrounded(enabled: Boolean = true, persist: () -> Boolean): Boolean {
        if (!foreground) return false
        if (!enabled) {
            foreground = false
            return false
        }
        if (!persist()) return false
        foreground = false
        return true
    }
}

internal object SessionLogRemotePolicy {
    const val ENABLED_KEY = "app_session_logs_enabled"
    const val DEFAULT_ENABLED = true

    fun parse(value: String): Boolean? = when (value.trim().lowercase(java.util.Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun allowed(configured: Boolean, enabled: Boolean): Boolean = configured && enabled
}

internal fun sessionLogReasonForStop(isFinishing: Boolean, isChangingConfigurations: Boolean): String? =
    if (isChangingConfigurations) null else if (isFinishing) "exit" else "background"

/** Track every in-process activity, including full-screen ads, not only MainActivity. */
internal class SessionActivityVisibility {
    private val started = mutableSetOf<Any>()

    @Synchronized
    fun started(activity: Any): Boolean = started.add(activity) && started.size == 1

    @Synchronized
    fun stopped(activity: Any, isFinishing: Boolean, isChangingConfigurations: Boolean): String? {
        if (!started.remove(activity) || started.isNotEmpty()) return null
        return sessionLogReasonForStop(isFinishing, isChangingConfigurations)
    }
}

/** Stable ID makes recovery safe if the process dies between enqueue and legacy-key removal. */
internal fun legacyBackgroundSessionEventId(encoded: String): String =
    UUID.nameUUIDFromBytes(("legacy-app-background-v1:" + encoded).toByteArray(Charsets.UTF_8)).toString()

/** Only an already stored event is an acknowledgement; other conflicts must remain queued. */
internal fun isDuplicateSessionLogConflict(status: Int, response: String): Boolean =
    status == 409 && runCatching {
        val error = Json.parseToJsonElement(response).jsonObject
        error["code"]?.jsonPrimitive?.contentOrNull == "23505" &&
            error["message"]?.jsonPrimitive?.contentOrNull?.contains("app_session_logs_pkey") == true
    }.getOrDefault(false)
