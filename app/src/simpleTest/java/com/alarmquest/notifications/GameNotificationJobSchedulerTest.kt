package com.alarmquest.notifications

import android.app.job.JobScheduler
import android.content.Context
import com.alarmquest.data.CharacterSlotSnapshot
import com.alarmquest.data.GameSnapshot
import com.alarmquest.data.StartupPhase
import com.alarmquest.engine.SimpleGameEngine
import com.alarmquest.model.HeroClass
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameNotificationJobSchedulerTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val jobScheduler: JobScheduler
        get() = context.getSystemService(JobScheduler::class.java)

    @Before
    fun setUp() {
        context.getSharedPreferences("game_notification_preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        jobScheduler.cancelAll()
    }

    @After
    fun tearDown() {
        jobScheduler.cancelAll()
    }

    @Test
    fun `enabled offline alarm schedules the depletion check and foreground cancels it`() {
        val store = GameNotificationPreferencesStore(context).apply { setEnabled(true) }
        val scheduler = GameNotificationJobScheduler(context, store)

        scheduler.refresh(snapshot = snapshot(remainingMillis = 60_000L), appInForeground = false)
        assertNotNull(
            jobScheduler.getPendingJob(GameNotificationJobScheduler.OFFLINE_DEPLETION_JOB_ID),
        )

        scheduler.onAppForegrounded()
        assertNull(
            jobScheduler.getPendingJob(GameNotificationJobScheduler.OFFLINE_DEPLETION_JOB_ID),
        )
    }

    @Test
    fun `disabled integrated notification does not leave a depletion job`() {
        val store = GameNotificationPreferencesStore(context)
        val scheduler = GameNotificationJobScheduler(context, store)

        scheduler.refresh(snapshot = snapshot(remainingMillis = 60_000L), appInForeground = false)

        assertNull(
            jobScheduler.getPendingJob(GameNotificationJobScheduler.OFFLINE_DEPLETION_JOB_ID),
        )
    }

    @Test
    fun `cold process preferences do not cancel the job before saves are loaded`() {
        val store = GameNotificationPreferencesStore(context).apply { setEnabled(true) }
        val scheduler = GameNotificationJobScheduler(context, store)
        scheduler.refresh(snapshot = snapshot(remainingMillis = 60_000L), appInForeground = false)

        scheduler.refresh(
            snapshot = GameSnapshot(
                revision = 0L,
                state = null,
                characters = emptyList(),
                activeSlotId = null,
                unlockedCharacterSlotCount = 1,
                ready = false,
                startupPhase = StartupPhase.LOADING_RECORD,
            ),
            appInForeground = false,
        )

        assertNotNull(
            jobScheduler.getPendingJob(GameNotificationJobScheduler.OFFLINE_DEPLETION_JOB_ID),
        )
    }

    private fun snapshot(remainingMillis: Long): GameSnapshot {
        val engine = SimpleGameEngine()
        val state = engine.newGame(
            name = "예약 검사",
            heroClass = HeroClass.CLERIC,
            rolledStats = engine.rollStats(31L).stats,
            seed = 32L,
            now = 1_000L,
        ).apply {
            offlineAdventureMillis = remainingMillis
        }
        return GameSnapshot(
            revision = 1L,
            state = state,
            characters = listOf(CharacterSlotSnapshot(slotId = 1, state = state)),
            activeSlotId = 1,
            unlockedCharacterSlotCount = 1,
            ready = true,
            startupPhase = StartupPhase.READY,
        )
    }
}
