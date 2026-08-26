package com.alarmquest.notifications

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.alarmquest.data.GameSnapshot

class GameNotificationJobScheduler(
    context: Context,
    private val preferencesStore: GameNotificationPreferencesStore,
) {
    private val applicationContext = context.applicationContext
    private val jobScheduler = applicationContext.getSystemService(JobScheduler::class.java)
    private val serviceComponent = ComponentName(
        applicationContext,
        GameProgressJobService::class.java,
    )

    fun onAppForegrounded() {
        cancelBackgroundJobs()
    }

    fun refresh(snapshot: GameSnapshot, appInForeground: Boolean) {
        if (!snapshot.ready && !appInForeground) return
        val preferences = preferencesStore.preferences.value
        if (appInForeground || !preferences.enabled) {
            cancelBackgroundJobs()
            return
        }

        scheduleNextOfflineAdventureDepletion(snapshot)
    }

    private fun scheduleNextOfflineAdventureDepletion(snapshot: GameSnapshot) {
        val remainingMillis = snapshot.characters
            .map { it.state.offlineAdventureMillis }
            .filter { it > 0L }
            .minOrNull()
        jobScheduler.cancel(OFFLINE_DEPLETION_JOB_ID)
        if (remainingMillis == null) return

        val job = JobInfo.Builder(OFFLINE_DEPLETION_JOB_ID, serviceComponent)
            .setMinimumLatency(remainingMillis)
            .setOverrideDeadline(remainingMillis + OFFLINE_DEADLINE_FLEX_MILLIS)
            .setPersisted(true)
            .build()
        jobScheduler.schedule(job)
    }

    private fun cancelBackgroundJobs() {
        jobScheduler.cancel(OFFLINE_DEPLETION_JOB_ID)
    }

    companion object {
        internal const val OFFLINE_DEPLETION_JOB_ID = 2_102
        private const val OFFLINE_DEADLINE_FLEX_MILLIS = 5L * 60L * 1_000L
    }
}
