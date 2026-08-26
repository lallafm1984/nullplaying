package com.alarmquest.notifications

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameNotificationPreferencesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("game_notification_preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `offline depletion notification begins off`() {
        val preferences = GameNotificationPreferencesStore(context).preferences.value

        assertFalse(preferences.enabled)
    }

    @Test
    fun `offline depletion notification choice persists across store recreation`() {
        GameNotificationPreferencesStore(context).apply {
            setEnabled(true)
        }

        val restored = GameNotificationPreferencesStore(context).preferences.value
        assertTrue(restored.enabled)
    }
}
