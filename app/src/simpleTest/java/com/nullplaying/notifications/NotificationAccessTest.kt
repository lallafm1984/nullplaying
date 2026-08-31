package com.nullplaying.notifications

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NotificationAccessTest {
    @Test
    fun `effective toggle is off when runtime permission is missing`() {
        val state = resolveNotificationSettingsUiState(
            preferenceEnabled = true,
            access = NotificationAccessState(
                runtimePermissionRequired = true,
                permissionGranted = false,
                appNotificationsEnabled = false,
                permissionRequestAttempted = false,
                shouldShowPermissionRationale = false,
            ),
        )

        assertFalse(state.checked)
        assertTrue(state.shouldRequestPermission)
        assertFalse(state.showSystemSettingsAction)
    }

    @Test
    fun `permanent denial offers system notification settings`() {
        val state = resolveNotificationSettingsUiState(
            preferenceEnabled = true,
            access = NotificationAccessState(
                runtimePermissionRequired = true,
                permissionGranted = false,
                appNotificationsEnabled = false,
                permissionRequestAttempted = true,
                shouldShowPermissionRationale = false,
            ),
        )

        assertFalse(state.checked)
        assertFalse(state.shouldRequestPermission)
        assertTrue(state.permissionPermanentlyDenied)
        assertTrue(state.showSystemSettingsAction)
    }

    @Test
    fun `disabled app notifications offer system settings even with permission`() {
        val state = resolveNotificationSettingsUiState(
            preferenceEnabled = true,
            access = NotificationAccessState(
                runtimePermissionRequired = true,
                permissionGranted = true,
                appNotificationsEnabled = false,
                permissionRequestAttempted = true,
                shouldShowPermissionRationale = false,
            ),
        )

        assertFalse(state.checked)
        assertTrue(state.appNotificationsDisabled)
        assertTrue(state.showSystemSettingsAction)
    }

    @Test
    fun `effective toggle is on only when preference and system access are on`() {
        val access = NotificationAccessState(
            runtimePermissionRequired = true,
            permissionGranted = true,
            appNotificationsEnabled = true,
            permissionRequestAttempted = true,
            shouldShowPermissionRationale = false,
        )

        assertTrue(resolveNotificationSettingsUiState(true, access).checked)
        assertFalse(resolveNotificationSettingsUiState(false, access).checked)
    }

    @Test
    fun `system settings intent targets this app notification page`() {
        val intent = appNotificationSettingsIntent("com.nullplaying")

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(
            "com.nullplaying",
            intent.getStringExtra(Settings.EXTRA_APP_PACKAGE),
        )
    }
}
