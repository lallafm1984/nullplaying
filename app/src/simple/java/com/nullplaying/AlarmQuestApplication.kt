package com.nullplaying

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.work.Configuration
import com.nullplaying.ads.GoogleMobileAdsConsentManager
import com.nullplaying.ads.MobileAdsInitializationGate
import com.nullplaying.ads.MobileAdsRuntimeState
import com.nullplaying.data.FileSimpleStateBackupStore
import com.nullplaying.data.SimpleDatabase
import com.nullplaying.data.SimpleGameRepository
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.localization.GameLanguageStore
import com.nullplaying.localization.GameLocalization
import com.nullplaying.notifications.AndroidGameProgressNotifier
import com.nullplaying.notifications.GameNotificationJobScheduler
import com.nullplaying.notifications.GameNotificationPreferencesStore
import com.nullplaying.remote.SupabaseGameService
import com.nullplaying.remote.SessionActivityVisibility
import com.nullplaying.remote.OfflineAdventureRemoteConfig
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AlarmQuestApplication : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _mobileAdsReady = MutableStateFlow(false)
    private val _mobileAdsRuntimeState =
        MutableStateFlow(MobileAdsRuntimeState.WAITING_FOR_CONSENT)
    private val mobileAdsInitializationGate = MobileAdsInitializationGate()
    private val mobileAdsSdkInitialized = AtomicBoolean(false)
    private var rankingSyncJob: Job? = null
    private val sessionActivityVisibility = SessionActivityVisibility()
    private val foregroundForConfig = MutableStateFlow(false)
    private val offlineAdventureRemoteConfig by lazy { OfflineAdventureRemoteConfig(this) }

    val mobileAdsReady = _mobileAdsReady.asStateFlow()
    val mobileAdsRuntimeState = _mobileAdsRuntimeState.asStateFlow()

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
            engine = SimpleGameEngine(offlineAdventureRemoteConfig.config.value),
            backupStore = FileSimpleStateBackupStore(filesDir.resolve("save-backups")),
            progressEventSink = gameProgressNotifier,
        )
    }

    val supabaseGameService: SupabaseGameService by lazy {
        SupabaseGameService(this, gameRepository) { offlineAdventureRemoteConfig.sessionLogsEnabled.value }
    }

    override fun onCreate() {
        super.onCreate()
        registerSessionLogCallbacks()
        GameLocalization.initialize(this)
        // Construct the engine with the last APPLIED policy before starting asynchronous fetches.
        gameRepository
        offlineAdventureRemoteConfig.start()
        applicationScope.launch {
            combine(
                offlineAdventureRemoteConfig.config,
                gameRepository.snapshots.map { it.ready }.distinctUntilChanged(),
                foregroundForConfig,
            ) { config, ready, foreground -> Triple(config, ready, foreground) }
                .collect { (config, ready, foreground) ->
                    if (ready && foreground) applyOfflineAdventureConfig(config)
                }
        }
        applicationScope.launch { supabaseGameService.initialize() }
        applicationScope.launch {
            offlineAdventureRemoteConfig.sessionLogsEnabled.collect { enabled ->
                if (enabled) supabaseGameService.flushPendingSessionLogs()
            }
        }
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
                    "privacyOptionsStatus=${adsConsentManager.state.value.privacyOptionsStatus}",
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

    fun retryAdsSetup(activity: Activity) {
        if (adsConsentManager.canRequestAds) {
            initializeMobileAdsIfAllowed()
        } else {
            _mobileAdsReady.value = false
            _mobileAdsRuntimeState.value = MobileAdsRuntimeState.WAITING_FOR_CONSENT
            gatherAdsConsent(activity)
        }
    }

    private fun initializeMobileAdsIfAllowed() {
        if (!adsConsentManager.canRequestAds) {
            _mobileAdsReady.value = false
            _mobileAdsRuntimeState.value = MobileAdsRuntimeState.WAITING_FOR_CONSENT
            return
        }
        if (mobileAdsSdkInitialized.get()) {
            _mobileAdsReady.value = true
            _mobileAdsRuntimeState.value = MobileAdsRuntimeState.READY
            return
        }
        if (!mobileAdsInitializationGate.tryStart(canRequestAds = true)) return
        _mobileAdsRuntimeState.value = MobileAdsRuntimeState.INITIALIZING
        applicationScope.launch {
            runCatching {
                MobileAds.initialize(
                    this@AlarmQuestApplication,
                    InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build(),
                )
            }.onSuccess {
                mobileAdsSdkInitialized.set(true)
                if (adsConsentManager.canRequestAds) {
                    _mobileAdsReady.value = true
                    _mobileAdsRuntimeState.value = MobileAdsRuntimeState.READY
                    Log.d(TAG, "Google Mobile Ads Next-Gen SDK initialized after UMP consent gate")
                } else {
                    _mobileAdsReady.value = false
                    _mobileAdsRuntimeState.value = MobileAdsRuntimeState.WAITING_FOR_CONSENT
                    Log.d(TAG, "Google Mobile Ads initialized; requests remain blocked by UMP")
                }
            }.onFailure { error ->
                mobileAdsInitializationGate.markFailed()
                mobileAdsSdkInitialized.set(false)
                _mobileAdsReady.value = false
                _mobileAdsRuntimeState.value = if (adsConsentManager.canRequestAds) {
                    MobileAdsRuntimeState.RETRYABLE_ERROR
                } else {
                    MobileAdsRuntimeState.WAITING_FOR_CONSENT
                }
                Log.e(TAG, "Google Mobile Ads Next-Gen SDK initialization failed", error)
            }
        }
    }

    fun onAppForegrounded() {
        notificationJobScheduler.onAppForegrounded()
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        rankingSyncJob?.cancel()
        rankingSyncJob = applicationScope.launch {
            gameRepository.onAppForegrounded(wallNow, elapsedNow)
            foregroundForConfig.value = true
            offlineAdventureRemoteConfig.refresh()
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
                applyOfflineAdventureConfig(offlineAdventureRemoteConfig.config.value)
                supabaseGameService.queueRankingSync(gameRepository.snapshots.value)
                supabaseGameService.flushPendingRanking()
            }
        }
    }

    fun onAppBackgrounded() {
        foregroundForConfig.value = false
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        rankingSyncJob?.cancel()
        rankingSyncJob = null
        applicationScope.launch {
            gameRepository.onAppBackgrounded(wallNow, elapsedNow)
            supabaseGameService.queueRankingSync(gameRepository.snapshots.value)
            notificationJobScheduler.refresh(
                snapshot = gameRepository.snapshots.value,
                appInForeground = gameRepository.isAppInForeground(),
            )
        }
    }

    private fun registerSessionLogCallbacks() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (sessionActivityVisibility.started(activity)) {
                    supabaseGameService.recordAppForegrounded()
                    applicationScope.launch { supabaseGameService.flushPendingSessionLogs() }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                recordSessionStop(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                // Fallback if a normal finish skipped stop; never duplicates an earlier stop.
                if (activity.isFinishing) recordSessionStop(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    private fun recordSessionStop(activity: Activity) {
        val reason = sessionActivityVisibility.stopped(
            activity, activity.isFinishing, activity.isChangingConfigurations,
        ) ?: return
        // All app activities are hidden. Persist synchronously before a process can be killed.
        supabaseGameService.recordAppBackgrounded(gameRepository.snapshots.value, System.currentTimeMillis(), reason)
        applicationScope.launch { supabaseGameService.flushPendingSessionLogs() }
    }

    private suspend fun applyOfflineAdventureConfig(config: com.nullplaying.engine.OfflineAdventureConfig) {
        try {
            if (gameRepository.updateOfflineAdventureConfig(
                    config, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                )
            ) {
                offlineAdventureRemoteConfig.markApplied(config)
                notificationJobScheduler.refresh(
                    snapshot = gameRepository.snapshots.value,
                    appInForeground = gameRepository.isAppInForeground(),
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(OfflineAdventureRemoteConfig.TAG, "Could not apply config; will retry on foreground", error)
        }
    }

    private companion object {
        const val TAG = "AlarmQuestAds"
        const val RANKING_SYNC_INTERVAL_MILLIS = 5L * 60L * 1_000L
        const val WORK_MANAGER_MIN_JOB_ID = 10_000
        const val WORK_MANAGER_MAX_JOB_ID = 19_999
    }
}
