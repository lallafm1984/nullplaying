package com.alarmquest.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GameNotificationPreferences(
    val enabled: Boolean = false,
)

class GameNotificationPreferencesStore(context: Context) {
    private val sharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutablePreferences = MutableStateFlow(load())

    val preferences: StateFlow<GameNotificationPreferences> = mutablePreferences.asStateFlow()

    fun setEnabled(enabled: Boolean) = update { copy(enabled = enabled) }

    private fun update(transform: GameNotificationPreferences.() -> GameNotificationPreferences) {
        val updated = mutablePreferences.value.transform()
        sharedPreferences.edit()
            .putBoolean(KEY_ENABLED, updated.enabled)
            .apply()
        mutablePreferences.value = updated
    }

    private fun load(): GameNotificationPreferences = GameNotificationPreferences(
        enabled = sharedPreferences.getBoolean(KEY_ENABLED, false),
    )

    private companion object {
        const val PREFERENCES_NAME = "game_notification_preferences"
        const val KEY_ENABLED = "notifications_enabled"
    }
}
