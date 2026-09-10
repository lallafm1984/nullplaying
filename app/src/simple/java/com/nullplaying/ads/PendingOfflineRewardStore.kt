package com.nullplaying.ads

import android.content.Context

/**
 * A tiny synchronous hand-off between the rewarded SDK callback and the application coroutine.
 * The callback returns only after the request is durable, so destroying the Activity cannot lose
 * an earned offline-adventure reward.
 */
class PendingOfflineRewardStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun enqueue(characterId: String, requestId: String): Boolean {
        if (!validIdentifier(characterId) || !validIdentifier(requestId)) return false
        val key = key(requestId)
        val existing = preferences.getString(key, null)
        if (existing != null) return existing == characterId
        return preferences.edit().putString(key, characterId).commit()
    }

    @Synchronized
    fun pending(): List<PendingOfflineReward> = preferences.all.mapNotNull { (key, value) ->
        val requestId = key.removePrefix(PENDING_PREFIX).takeIf { key.startsWith(PENDING_PREFIX) }
            ?: return@mapNotNull null
        val characterId = value as? String ?: return@mapNotNull null
        if (!validIdentifier(characterId) || !validIdentifier(requestId)) return@mapNotNull null
        PendingOfflineReward(characterId = characterId, requestId = requestId)
    }.sortedBy(PendingOfflineReward::requestId)

    @Synchronized
    fun remove(requestId: String): Boolean {
        if (!validIdentifier(requestId)) return false
        val key = key(requestId)
        if (!preferences.contains(key)) return true
        return preferences.edit().remove(key).commit()
    }

    private fun key(requestId: String): String = PENDING_PREFIX + requestId

    private fun validIdentifier(value: String): Boolean = value.isNotBlank() &&
        value.length <= MAX_IDENTIFIER_LENGTH &&
        value.none(Char::isISOControl)

    private companion object {
        const val PREFERENCES_NAME = "pending_offline_reward_grants_v1"
        const val PENDING_PREFIX = "pending:"
        const val MAX_IDENTIFIER_LENGTH = 128
    }
}

data class PendingOfflineReward(
    val characterId: String,
    val requestId: String,
)
