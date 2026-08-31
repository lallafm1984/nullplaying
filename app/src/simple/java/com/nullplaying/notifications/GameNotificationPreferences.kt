package com.nullplaying.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GameNotificationPreferences(
    val enabled: Boolean = false,
    val permissionRequestAttempted: Boolean = false,
)

class GameNotificationPreferencesStore(context: Context) {
    private val sharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutablePreferences = MutableStateFlow(load())

    val preferences: StateFlow<GameNotificationPreferences> = mutablePreferences.asStateFlow()

    fun setEnabled(enabled: Boolean) = update { copy(enabled = enabled) }

    fun markPermissionRequestAttempted() = update { copy(permissionRequestAttempted = true) }

    private fun update(transform: GameNotificationPreferences.() -> GameNotificationPreferences) {
        val updated = mutablePreferences.value.transform()
        sharedPreferences.edit()
            .putBoolean(KEY_ENABLED, updated.enabled)
            .putBoolean(
                KEY_PERMISSION_REQUEST_ATTEMPTED,
                updated.permissionRequestAttempted,
            )
            .apply()
        mutablePreferences.value = updated
    }

    private fun load(): GameNotificationPreferences = GameNotificationPreferences(
        enabled = sharedPreferences.getBoolean(KEY_ENABLED, false),
        permissionRequestAttempted = sharedPreferences.getBoolean(
            KEY_PERMISSION_REQUEST_ATTEMPTED,
            false,
        ),
    )

    private companion object {
        const val PREFERENCES_NAME = "game_notification_preferences"
        const val KEY_ENABLED = "notifications_enabled"
        const val KEY_PERMISSION_REQUEST_ATTEMPTED = "notification_permission_request_attempted"
    }
}
