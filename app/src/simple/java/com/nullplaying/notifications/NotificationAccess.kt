package com.nullplaying.notifications

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal data class NotificationAccessState(
    val runtimePermissionRequired: Boolean,
    val permissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val permissionRequestAttempted: Boolean,
    val shouldShowPermissionRationale: Boolean,
)

internal data class NotificationSettingsUiState(
    val checked: Boolean,
    val shouldRequestPermission: Boolean,
    val permissionDenied: Boolean,
    val permissionPermanentlyDenied: Boolean,
    val appNotificationsDisabled: Boolean,
    val showSystemSettingsAction: Boolean,
)

internal fun resolveNotificationSettingsUiState(
    preferenceEnabled: Boolean,
    access: NotificationAccessState,
): NotificationSettingsUiState {
    val permissionDenied = access.runtimePermissionRequired && !access.permissionGranted
    val permissionPermanentlyDenied = permissionDenied &&
        access.permissionRequestAttempted &&
        !access.shouldShowPermissionRationale
    val appNotificationsDisabled = access.permissionGranted && !access.appNotificationsEnabled
    val canPostNotifications = access.permissionGranted && access.appNotificationsEnabled

    return NotificationSettingsUiState(
        checked = preferenceEnabled && canPostNotifications,
        shouldRequestPermission = preferenceEnabled &&
            permissionDenied &&
            !permissionPermanentlyDenied,
        permissionDenied = preferenceEnabled && permissionDenied,
        permissionPermanentlyDenied = preferenceEnabled && permissionPermanentlyDenied,
        appNotificationsDisabled = preferenceEnabled && appNotificationsDisabled,
        showSystemSettingsAction = preferenceEnabled &&
            (permissionPermanentlyDenied || appNotificationsDisabled),
    )
}

internal fun readNotificationAccessState(
    context: Context,
    permissionRequestAttempted: Boolean,
): NotificationAccessState {
    val runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val permissionGranted = !runtimePermissionRequired ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    val activity = context.findActivity()
    val shouldShowPermissionRationale = runtimePermissionRequired &&
        !permissionGranted &&
        activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } == true

    return NotificationAccessState(
        runtimePermissionRequired = runtimePermissionRequired,
        permissionGranted = permissionGranted,
        appNotificationsEnabled = NotificationManagerCompat.from(context)
            .areNotificationsEnabled(),
        permissionRequestAttempted = permissionRequestAttempted,
        shouldShowPermissionRationale = shouldShowPermissionRationale,
    )
}

internal fun appNotificationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }

internal fun openAppNotificationSettings(context: Context): Boolean = runCatching {
    context.startActivity(
        appNotificationSettingsIntent(context.packageName).apply {
            if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}.isSuccess

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
