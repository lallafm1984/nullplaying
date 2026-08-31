package com.nullplaying.remote

import android.content.Context
import java.time.Instant
import java.time.ZoneId

enum class AppAnnouncementDisplayType(val databaseValue: String) {
    ONCE_AFTER_INSTALL("once_after_install"),
    EVERY_LAUNCH("every_launch"),
    ONCE_PER_DAY("once_per_day"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): AppAnnouncementDisplayType =
            entries.singleOrNull { it.databaseValue == value } ?: EVERY_LAUNCH
    }
}

class AppAnnouncementDisplayStore(
    context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()
    private val displayedThisLaunch = mutableSetOf<String>()

    fun shouldDisplay(
        announcement: AppAnnouncement,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (announcement.announcementKey in displayedThisLaunch) return@synchronized false
        when (announcement.displayType) {
            AppAnnouncementDisplayType.EVERY_LAUNCH -> true
            AppAnnouncementDisplayType.ONCE_AFTER_INSTALL ->
                !preferences.getBoolean(onceKey(announcement.announcementKey), false)
            AppAnnouncementDisplayType.ONCE_PER_DAY ->
                preferences.getString(dayKey(announcement.announcementKey), null) !=
                    localDate(nowEpochMillis)
        }
    }

    fun markDisplayed(
        announcement: AppAnnouncement,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) = synchronized(lock) {
        displayedThisLaunch += announcement.announcementKey
        when (announcement.displayType) {
            AppAnnouncementDisplayType.EVERY_LAUNCH -> Unit
            AppAnnouncementDisplayType.ONCE_AFTER_INSTALL ->
                preferences.edit().putBoolean(onceKey(announcement.announcementKey), true).commit()
            AppAnnouncementDisplayType.ONCE_PER_DAY ->
                preferences.edit()
                    .putString(dayKey(announcement.announcementKey), localDate(nowEpochMillis))
                    .commit()
        }
    }

    private fun localDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .toLocalDate()
            .toString()

    private fun onceKey(announcementKey: String): String = "once:$announcementKey"

    private fun dayKey(announcementKey: String): String = "day:$announcementKey"

    internal companion object {
        const val PREFERENCES_NAME = "app_announcement_display"
    }
}
