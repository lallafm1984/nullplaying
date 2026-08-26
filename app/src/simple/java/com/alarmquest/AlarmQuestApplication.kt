package com.alarmquest

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.work.Configuration
import com.alarmquest.ads.GoogleMobileAdsConsentManager
import com.alarmquest.ads.MobileAdsInitializationGate
import com.alarmquest.data.FileSimpleStateBackupStore
import com.alarmquest.data.SimpleDatabase
import com.alarmquest.data.SimpleGameRepository
import com.alarmquest.engine.SimpleGameEngine
import com.alarmquest.localization.GameLanguageStore
import com.alarmquest.localization.GameLocalization
import com.alarmquest.notifications.AndroidGameProgressNotifier
import com.alarmquest.notifications.GameNotificationJobScheduler
import com.alarmquest.notifications.GameNotificationPreferencesStore
import com.alarmquest.remote.SupabaseGameService
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AlarmQuestApplication : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _mobileAdsReady = MutableStateFlow(false)
    private val mobileAdsInitializationGate = MobileAdsInitializationGate()
    private var rankingSyncJob: Job? = null

    val mobileAdsReady = _mobileAdsReady.asStateFlow()

    val adsConsentManager: GoogleMobileAdsConsentManager by lazy {
        GoogleMobileAdsConsentManager(this)
    }

    override fun getWorkManagerConfiguration(): Configuration =
        Configuration.Builder()
            .setJobSchedulerJobIdRange(
                WORK_MANAGER_MIN_JOB_ID,
                WORK_MANAGER_MAX_JOB_ID,
            )
            .build()

    val notificationPreferencesStore: GameNotificationPreferencesStore by lazy {
        GameNotificationPreferencesStore(this)
    }

    val gameLanguageStore: GameLanguageStore by lazy {
        GameLanguageStore(this)
    }

    private val gameProgressNotifier: AndroidGameProgressNotifier by lazy {
        AndroidGameProgressNotifier(this, notificationPreferencesStore, gameLanguageStore)
    }

    val notificationJobScheduler: GameNotificationJobScheduler by lazy {
        GameNotificationJobScheduler(this, notificationPreferencesStore)
    }

    val database: SimpleDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            SimpleDatabase::class.java,
            "alarmquest.db",
        ).build()
    }

    val gameRepository: SimpleGameRepository by lazy {
        SimpleGameRepository(
            database = database,
            engine = SimpleGameEngine(),
            backupStore = FileSimpleStateBackupStore(filesDir.resolve("save-backups")),
            progressEventSink = gameProgressNotifier,
        )
    }

    val supabaseGameService: SupabaseGameService by lazy {
        SupabaseGameService(this, gameRepository)
    }

    override fun onCreate() {
        super.onCreate()
        GameLocalization.initialize(this)
        applicationScope.launch { supabaseGameService.initialize() }
        applicationScope.launch {
            gameRepository.snapshots
                .filter { it.ready }
                .mapNotNull { snapshot ->
                    snapshot.state?.rankingCharacterId?.takeIf(String::isNotBlank)
                }
                .distinctUntilChanged()
                .collect { characterId ->
                    supabaseGameService.fetchRanking(characterId)
                }
        }
        gameProgressNotifier
        applicationScope.launch {
            gameLanguageStore.language.collectLatest {
                gameProgressNotifier.refreshLocalizedChannel()
            }
        }
        applicationScope.launch {
            notificationPreferencesStore.preferences.collectLatest {
                val snapshot = gameRepository.snapshots.value
                val appInForeground = gameRepository.isAppInForeground()
                if (snapshot.ready || appInForeground) {
                    notificationJobScheduler.refresh(
                        snapshot = snapshot,
                        appInForeground = appInForeground,
                    )
                }
            }
        }
    }

    fun gatherAdsConsent(activity: Activity) {
        val requestStarted = adsConsentManager.gatherConsent(activity) { errorMessage ->
            if (errorMessage != null) {
                Log.w(TAG, "UMP consent gathering failed: $errorMessage")
            }
            Log.d(
                TAG,
                "UMP flow completed: canRequestAds=${adsConsentManager.canRequestAds}, " +
                    "privacyOptionsRequired=${adsConsentManager.privacyOptionsRequired.value}",
            )
            initializeMobileAdsIfAllowed()
        }
        Log.d(
            TAG,
            if (requestStarted) {
                "Requesting UMP consent information update"
            } else {
                "UMP consent update already in progress; using the latest Activity"
            },
        )
        // The cached status may already permit requests before the network callback returns.
        initializeMobileAdsIfAllowed()
    }

    fun showAdsPrivacyOptions(
        activity: Activity,
        onFailure: (String) -> Unit,
    ) {
        adsConsentManager.showPrivacyOptionsForm(activity) { errorMessage ->
            if (errorMessage != null) {
                Log.w(TAG, "UMP privacy options failed: $errorMessage")
                onFailure(errorMessage)
            }
            Log.d(
                TAG,
                "UMP privacy options closed: canRequestAds=${adsConsentManager.canRequestAds}",
            )
            initializeMobileAdsIfAllowed()
        }
    }

    private fun initializeMobileAdsIfAllowed() {
        if (!mobileAdsInitializationGate.tryStart(adsConsentManager.canRequestAds)) return
        applicationScope.launch {
            runCatching {
                MobileAds.initialize(
                    this@AlarmQuestApplication,
                    InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build(),
                )
            }.onSuccess {
                _mobileAdsReady.value = true
                Log.d(TAG, "Google Mobile Ads Next-Gen SDK initialized after UMP consent gate")
            }.onFailure { error ->
                mobileAdsInitializationGate.markFailed()
                Log.e(TAG, "Google Mobile Ads Next-Gen SDK initialization failed", error)
            }
        }
    }

    fun onAppForegrounded() {
        notificationJobScheduler.onAppForegrounded()
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        supabaseGameService.recordAppForegrounded(wallNow)
        rankingSyncJob?.cancel()
        rankingSyncJob = applicationScope.launch {
            gameRepository.onAppForegrounded(wallNow, elapsedNow)
            supabaseGameService.validateAnonymousSessionForForeground()
            supabaseGameService.queueRankingSync(gameRepository.snapshots.value)
            supabaseGameService.flushPendingRanking()
            gameRepository.snapshots.value.state
                ?.rankingCharacterId
                ?.takeIf(String::isNotBlank)
                ?.let { supabaseGameService.fetchRanking(it) }
            supabaseGameService.flushPendingSessionLogs()
            while (isActive) {
                delay(RANKING_SYNC_INTERVAL_MILLIS)
                if (!gameRepository.isAppInForeground()) break
                supabaseGameService.queueRankingSync(gameRepository.snapshots.value)
                supabaseGameService.flushPendingRanking()
            }
        }
    }

    fun onAppBackgrounded() {
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        rankingSyncJob?.cancel()
        rankingSyncJob = null
        supabaseGameService.recordAppBackgrounded(gameRepository.snapshots.value, wallNow)
        applicationScope.launch {
            gameRepository.onAppBackgrounded(wallNow, elapsedNow)
            supabaseGameService.queueRankingSync(gameRepository.snapshots.value)
            notificationJobScheduler.refresh(
                snapshot = gameRepository.snapshots.value,
                appInForeground = gameRepository.isAppInForeground(),
            )
        }
    }

    private companion object {
        const val TAG = "AlarmQuestAds"
        const val RANKING_SYNC_INTERVAL_MILLIS = 5L * 60L * 1_000L
        const val WORK_MANAGER_MIN_JOB_ID = 10_000
        const val WORK_MANAGER_MAX_JOB_ID = 19_999
    }
}
