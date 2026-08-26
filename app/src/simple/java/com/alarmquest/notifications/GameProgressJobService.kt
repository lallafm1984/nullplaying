package com.alarmquest.notifications

import android.app.job.JobParameters
import android.app.job.JobService
import com.alarmquest.AlarmQuestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class GameProgressJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val alarmQuestApplication = application as AlarmQuestApplication
        val job = serviceScope.launch {
            runCatching {
                alarmQuestApplication.gameRepository.runBackgroundSettlement(
                    now = System.currentTimeMillis(),
                )
            }
            runningJobs.remove(params.jobId)
            jobFinished(params, false)
            alarmQuestApplication.notificationJobScheduler.refresh(
                snapshot = alarmQuestApplication.gameRepository.snapshots.value,
                appInForeground = alarmQuestApplication.gameRepository.isAppInForeground(),
            )
        }
        runningJobs[params.jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJobs.remove(params.jobId)?.cancel()
        return false
    }

    override fun onDestroy() {
        runningJobs.values.forEach(Job::cancel)
        runningJobs.clear()
        serviceScope.cancel()
        super.onDestroy()
    }
}
