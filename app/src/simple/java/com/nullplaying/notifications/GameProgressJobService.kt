package com.nullplaying.notifications

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.nullplaying.AlarmQuestApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class GameProgressJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val alarmQuestApplication = application as AlarmQuestApplication
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = currentCoroutineContext().job
            try {
                alarmQuestApplication.runBackgroundGameSettlement()
                if (runningJobs.remove(params.jobId, currentJob)) {
                    jobFinished(params, false)
                    alarmQuestApplication.notificationJobScheduler.refresh(
                        snapshot = alarmQuestApplication.gameRepository.snapshots.value,
                        appInForeground = alarmQuestApplication.gameRepository.isAppInForeground(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Background settlement failed", failure)
                if (runningJobs.remove(params.jobId, currentJob)) {
                    jobFinished(params, false)
                }
            } finally {
                runningJobs.remove(params.jobId, currentJob)
            }
        }
        runningJobs[params.jobId] = job
        job.start()
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

    private companion object {
        const val TAG = "AlarmQuestProgressJob"
    }
}
