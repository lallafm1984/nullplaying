package com.nullplaying.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nullplaying.MainActivity
import com.nullplaying.R
import com.nullplaying.data.GameProgressEvent
import com.nullplaying.data.GameProgressEventSink
import com.nullplaying.localization.GameLanguageStore
import com.nullplaying.localization.GameLocalization

class AndroidGameProgressNotifier(
    context: Context,
    private val preferencesStore: GameNotificationPreferencesStore,
    private val languageStore: GameLanguageStore,
) : GameProgressEventSink {
    private val applicationContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(applicationContext)

    init {
        createNotificationChannel()
    }

    override fun onGameProgress(event: GameProgressEvent) {
        val preferences = preferencesStore.preferences.value
        if (!preferences.enabled || !canPostNotifications()) return

        if (event.offlineAdventureDepleted) {
            val language = languageStore.language.value
            postNotification(
                OFFLINE_ADVENTURE_NOTIFICATION_ID,
                GameLocalization.translate("오프라인 모험 충전 소진", language),
                GameLocalization.translatePreserving(
                    text = "${event.heroName}의 오프라인 모험 시간이 모두 소진되었습니다.",
                    language = language,
                    protectedValues = listOf(event.heroName),
                ),
            )
        }
    }

    fun canPostNotifications(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return permissionGranted && notificationManager.areNotificationsEnabled()
    }

    fun refreshLocalizedChannel() {
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(id: Int, title: String, text: String) {
        if (!canPostNotifications()) return
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            id,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        try {
            notificationManager.notify(id, notification)
        } catch (_: SecurityException) {
            // The permission may be revoked between the explicit check and this call.
        }
    }

    private fun createNotificationChannel() {
        val language = languageStore.language.value
        val localizedName = GameLocalization.translate("오프라인 모험 소진 알림", language)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedName,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = localizedName
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "game_progress"
        const val OFFLINE_ADVENTURE_NOTIFICATION_ID = 1_102
    }
}
