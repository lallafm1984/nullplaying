package com.nullplaying

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.room.Room
import androidx.work.Configuration
import com.nullplaying.ads.GoogleMobileAdsConsentManager
import com.nullplaying.ads.AdsConsentState
import com.nullplaying.ads.MobileAdsInitializationGate
import com.nullplaying.ads.MobileAdsRuntimeState
import com.nullplaying.ads.PendingOfflineRewardStore
import com.nullplaying.ads.RewardedAdSessionCoordinator
import com.nullplaying.data.FileSimpleStateBackupStore
import com.nullplaying.data.RewardedOfflineGrantStatus
import com.nullplaying.data.SimpleDatabase
import com.nullplaying.data.SimpleGameRepository
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.localization.GameLanguageStore
import com.nullplaying.localization.GameLocalization
import com.nullplaying.notifications.AndroidGameProgressNotifier
import com.nullplaying.notifications.GameNotificationJobScheduler
import com.nullplaying.notifications.GameNotificationPreferencesStore
import com.nullplaying.remote.SupabaseGameService
import com.nullplaying.remote.SharedPlayerSnapshotClient
import com.nullplaying.remote.SessionActivityVisibility
import com.nullplaying.remote.OfflineAdventureRemoteConfig
import com.nullplaying.remote.isSharedPlayerRemoteEnabled
import com.nullplaying.time.TrustedGameClock
import com.nullplaying.time.TrustedTimeReading
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class AlarmQuestApplication : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _mobileAdsReady = MutableStateFlow(false)
    private val _mobileAdsRuntimeState =
        MutableStateFlow(MobileAdsRuntimeState.WAITING_FOR_CONSENT)
    private val disabledAdsConsentState = MutableStateFlow(AdsConsentState())
    private val mobileAdsInitializationGate = MobileAdsInitializationGate()
    private val mobileAdsSdkInitialized = AtomicBoolean(false)
    private var rankingSyncJob: Job? = null
    private val sessionActivityVisibility = SessionActivityVisibility()
    private val foregroundForConfig = MutableStateFlow(false)
    private val offlineAdventureRemoteConfig by lazy { OfflineAdventureRemoteConfig(this) }
    private val gameTimeMutex = Mutex()
    private val gameLifecycleMutex = Mutex()
    private val pendingOfflineRewardMutex = Mutex()
    private val rewardedAdSessionCoordinator = RewardedAdSessionCoordinator()
    private val pendingOfflineRewardStore by lazy { PendingOfflineRewardStore(this) }
    @Volatile
    private var gameInitialization: Deferred<Unit>? = null
    @Volatile
    private var mainActivityForeground = false
    @Volatile
    private var appliedMainActivityForeground = false
    @Volatile
    private var mainActivityResumed = false
    @Volatile
    private var lastServerTimeObservationElapsedRealtime = Long.MIN_VALUE

    val mobileAdsReady = _mobileAdsReady.asStateFlow()
    val mobileAdsRuntimeState = _mobileAdsRuntimeState.asStateFlow()
    val adsConsentState: StateFlow<AdsConsentState>
        get() = if (BuildConfig.REMOTE_SERVICES_ENABLED) {
            adsConsentManager.state
        } else {
            disabledAdsConsentState
        }

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

    val trustedGameClock: TrustedGameClock by lazy { TrustedGameClock(this) }

    val gameRepository: SimpleGameRepository by lazy {
        SimpleGameRepository(
            database = database,
            engine = SimpleGameEngine(
                offlineAdventureRemoteConfig.config.value,
                enableAdventureEvents = BuildConfig.ADVENTURE_SYSTEM_ENABLED,
                enableAdventureRelationships = BuildConfig.ADVENTURE_SYSTEM_ENABLED,
                enableAdventureTraits = BuildConfig.ADVENTURE_SYSTEM_ENABLED,
            ),
            backupStore = FileSimpleStateBackupStore(filesDir.resolve("save-backups")),
            progressEventSink = gameProgressNotifier,
            publicPlayerRosterRetentionEnabled = isSharedPlayerRemoteEnabled(
                remoteServicesEnabled = BuildConfig.REMOTE_SERVICES_ENABLED,
                debugBuild = BuildConfig.DEBUG,
                adventureSystemEnabled = BuildConfig.ADVENTURE_SYSTEM_ENABLED,
                featureEnabled = BuildConfig.SHARED_PLAYER_SYNC_ENABLED,
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            ),
            gameClock = trustedGameClock,
        )
    }

    val supabaseGameService: SupabaseGameService by lazy {
        SupabaseGameService(
            context = this,
            repository = gameRepository,
            sessionLoggingEnabled = { offlineAdventureRemoteConfig.sessionLogsEnabled.value },
            rankingRefreshPolicy = offlineAdventureRemoteConfig.rankingRefreshPolicy,
            trustedRankingNowEpochMillis = { trustedGameClock.nowOrNull()?.epochMillis },
        )
    }

    private val sharedPlayerSnapshotClient: SharedPlayerSnapshotClient by lazy {
        SharedPlayerSnapshotClient(
            repository = gameRepository,
            api = supabaseGameService.authenticatedSharedPlayerApi,
            bootCount = {
                Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.REMOTE_SERVICES_ENABLED) {
            registerSessionLogCallbacks()
        }
        GameLocalization.initialize(this)
        // Construct the engine with the last APPLIED policy before starting asynchronous fetches.
        gameRepository
        trustedGameClock
        gameInitialization = startGameInitialization()
        schedulePendingOfflineRewardDrain()
        if (BuildConfig.REMOTE_SERVICES_ENABLED) {
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
            applicationScope.launch {
                if (awaitGameInitialization()) supabaseGameService.initialize()
            }
            applicationScope.launch {
                combine(
                    gameRepository.snapshots.filter { it.ready }.map { snapshot ->
                        snapshot.characters.flatMap { it.state.mythicDiscoveries }
                    }.distinctUntilChanged(),
                    foregroundForConfig,
                ) { records, foreground -> if (foreground) records else emptyList() }
                    .distinctUntilChanged()
                    .collect { records ->
                        if (records.isNotEmpty()) supabaseGameService.syncMythicDiscoveries(gameRepository.snapshots.value)
                    }
            }
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
        } else {
            Log.i(TAG, "Offline QA: all remote services and SDK initialization are disabled")
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
        if (!BuildConfig.REMOTE_SERVICES_ENABLED) {
            _mobileAdsReady.value = false
            _mobileAdsRuntimeState.value = MobileAdsRuntimeState.WAITING_FOR_CONSENT
            return
        }
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
        if (!BuildConfig.REMOTE_SERVICES_ENABLED) {
            onFailure("Offline QA blocks remote ad services")
            return
        }
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
        if (!BuildConfig.REMOTE_SERVICES_ENABLED) return
        if (adsConsentManager.canRequestAds) {
            initializeMobileAdsIfAllowed()
        } else {
            _mobileAdsReady.value = false
            _mobileAdsRuntimeState.value = MobileAdsRuntimeState.WAITING_FOR_CONSENT
            gatherAdsConsent(activity)
        }
    }

    private fun initializeMobileAdsIfAllowed() {
        if (!BuildConfig.REMOTE_SERVICES_ENABLED) return
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
        mainActivityForeground = true
        scheduleGameLifecycleReconciliation()
    }

    fun onMainActivityResumed() {
        mainActivityResumed = true
        val token = rewardedAdSessionCoordinator.activeToken() ?: return
        applicationScope.launch {
            delay(RewardedAdSessionCoordinator.DEFAULT_RESUME_RECOVERY_GRACE_MILLIS)
            recoverOrphanedRewardedAdSession(token)
        }
    }

    fun onMainActivityPaused() {
        mainActivityResumed = false
        rewardedAdSessionCoordinator.hostPaused(SystemClock.elapsedRealtime())
    }

    fun beginRewardedAdSession(token: String, elapsedRealtime: Long): Boolean {
        rewardedAdSessionCoordinator.activeToken()?.let { previousToken ->
            if (rewardedAdSessionCoordinator.recoverIfOrphaned(
                    token = previousToken,
                    nowElapsedRealtimeMillis = elapsedRealtime,
                    hostResumed = mainActivityResumed,
                )
            ) {
                gameRepository.setRewardAdInFlight(false, elapsedRealtime)
            }
        }
        if (!rewardedAdSessionCoordinator.begin(token, elapsedRealtime)) return false
        gameRepository.setRewardAdInFlight(true, elapsedRealtime)
        applicationScope.launch {
            delay(RewardedAdSessionCoordinator.DEFAULT_FOREGROUND_WATCHDOG_MILLIS)
            recoverOrphanedRewardedAdSession(token)
        }
        return true
    }

    fun finishRewardedAdSession(token: String, elapsedRealtime: Long): Boolean {
        val finished = rewardedAdSessionCoordinator.terminal(token)
        if (finished) {
            gameRepository.setRewardAdInFlight(false, elapsedRealtime)
        }
        return finished
    }

    /** Persists before returning to the SDK callback, then applies in application-owned scope. */
    fun enqueueEarnedOfflineAdventureReward(characterId: String, requestId: String) {
        if (!pendingOfflineRewardStore.enqueue(characterId, requestId)) {
            Log.e(TAG, "Could not durably enqueue earned offline-adventure reward")
            applicationScope.launch {
                if (awaitGameInitialization()) {
                    gameRepository.grantRewardedOfflineAdventureForCharacter(
                        characterId = characterId,
                        now = gameNow(),
                        rewardRequestId = requestId,
                    )
                }
            }
            return
        }
        schedulePendingOfflineRewardDrain()
    }

    private fun schedulePendingOfflineRewardDrain() {
        applicationScope.launch {
            if (!awaitGameInitialization()) return@launch
            pendingOfflineRewardMutex.withLock {
                pendingOfflineRewardStore.pending().forEach { pending ->
                    val result = runCatching {
                        gameRepository.grantRewardedOfflineAdventureForCharacter(
                            characterId = pending.characterId,
                            now = gameNow(),
                            rewardRequestId = pending.requestId,
                        )
                    }
                    if (result.isSuccess) {
                        val status = result.getOrThrow()
                        if (!pendingOfflineRewardStore.remove(pending.requestId)) {
                            Log.w(TAG, "Earned offline reward applied but queue cleanup failed")
                        } else if (status != RewardedOfflineGrantStatus.APPLIED &&
                            status != RewardedOfflineGrantStatus.ALREADY_APPLIED
                        ) {
                            Log.w(TAG, "Earned offline reward closed without applying: $status")
                        }
                    } else {
                        Log.w(TAG, "Earned offline reward remains queued for retry", result.exceptionOrNull())
                    }
                }
            }
        }
    }

    private fun recoverOrphanedRewardedAdSession(token: String) {
        val elapsedRealtime = SystemClock.elapsedRealtime()
        if (rewardedAdSessionCoordinator.recoverIfOrphaned(
                token = token,
                nowElapsedRealtimeMillis = elapsedRealtime,
                hostResumed = mainActivityResumed,
            )
        ) {
            Log.w(TAG, "Recovered rewarded ad session without a terminal SDK callback")
            gameRepository.setRewardAdInFlight(false, elapsedRealtime)
        }
    }

    private suspend fun synchronizeSharedPlayersForForeground() {
        if (!supabaseGameService.sharedPlayerRemoteEnabled) return
        sharedPlayerSnapshotClient.synchronize(
            snapshot = gameRepository.snapshots.value,
            nowEpochMillis = gameNow(),
        )
            .onFailure { error -> Log.w(TAG, "Shared-player foreground sync failed", error) }
    }

    fun onAppBackgrounded() {
        mainActivityForeground = false
        foregroundForConfig.value = false
        rankingSyncJob?.cancel()
        rankingSyncJob = null
        scheduleGameLifecycleReconciliation()
    }

    private fun scheduleGameLifecycleReconciliation() {
        applicationScope.launch {
            try {
                gameLifecycleMutex.withLock {
                    while (true) {
                        val desiredForeground = mainActivityForeground
                        if (!awaitGameInitialization()) return@withLock
                        if (desiredForeground != appliedMainActivityForeground) {
                            if (desiredForeground) {
                                try {
                                    gameRepository.onAppForegrounded(
                                        gameNow(),
                                        SystemClock.elapsedRealtime(),
                                    )
                                } finally {
                                    appliedMainActivityForeground =
                                        gameRepository.isAppInForeground()
                                }
                                notificationJobScheduler.onAppForegrounded()
                                if (BuildConfig.REMOTE_SERVICES_ENABLED) {
                                    foregroundForConfig.value = true
                                    startForegroundSync()
                                }
                            } else {
                                foregroundForConfig.value = false
                                rankingSyncJob?.cancel()
                                rankingSyncJob = null
                                try {
                                    gameRepository.onAppBackgrounded(
                                        gameNow(),
                                        SystemClock.elapsedRealtime(),
                                    )
                                } finally {
                                    appliedMainActivityForeground =
                                        gameRepository.isAppInForeground()
                                }
                                trustedGameClock.checkpoint()
                                if (BuildConfig.REMOTE_SERVICES_ENABLED) {
                                    supabaseGameService.queueRankingSync(gameRepository.snapshots.value)
                                }
                                notificationJobScheduler.refresh(
                                    snapshot = gameRepository.snapshots.value,
                                    appInForeground = false,
                                )
                            }
                        }
                        if (desiredForeground == mainActivityForeground) return@withLock
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Game lifecycle reconciliation failed", failure)
            }
        }
    }

    private fun startForegroundSync() {
        rankingSyncJob?.cancel()
        rankingSyncJob = applicationScope.launch {
            try {
                // Gameplay has already resumed. Repository mutations sample the clock again under
                // their mutex, so this optional correction cannot race a queued tick.
                refreshTrustedGameTime()
                offlineAdventureRemoteConfig.refresh()
                supabaseGameService.validateAnonymousSessionForForeground()
                supabaseGameService.flushPendingPlayerNetworkProfile(gameRepository.snapshots.value)
                synchronizeSharedPlayersForForeground()
                // Arena identity fields come from the committed shared profile. Never publish a
                // client standing before that profile succeeds.
                supabaseGameService.flushPendingArenaRanking()
                supabaseGameService.queueRankingSync(gameRepository.snapshots.value)
                supabaseGameService.flushPendingRanking()
                supabaseGameService.syncMythicDiscoveries(gameRepository.snapshots.value)
                gameRepository.snapshots.value.state
                    ?.rankingCharacterId
                    ?.takeIf(String::isNotBlank)
                    ?.let { supabaseGameService.fetchRanking(it) }
                supabaseGameService.flushPendingSessionLogs()
                while (isActive) {
                    delay(RANKING_SYNC_INTERVAL_MILLIS)
                    if (!gameRepository.isAppInForeground()) break
                    applyOfflineAdventureConfig(offlineAdventureRemoteConfig.config.value)
                    // Publish the complete shared+ranking profile before the legacy fallback queue.
                    // Unified-contract accounts then clear the exact pending ranking projection and
                    // never issue a doomed standalone ranking write.
                    supabaseGameService.flushPendingPlayerNetworkProfile(gameRepository.snapshots.value)
                    synchronizeSharedPlayersForForeground()
                    supabaseGameService.flushPendingArenaRanking()
                    supabaseGameService.queueRankingSync(gameRepository.snapshots.value)
                    supabaseGameService.flushPendingRanking()
                    supabaseGameService.syncMythicDiscoveries(gameRepository.snapshots.value)
                    // Cache-aware: a long foreground session receives the next daily edition.
                    gameRepository.snapshots.value.state?.rankingCharacterId
                        ?.takeIf(String::isNotBlank)
                        ?.let { supabaseGameService.fetchRanking(it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Foreground synchronization failed", failure)
            }
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
                    config, gameNow(), SystemClock.elapsedRealtime(),
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

    /** Epoch used by every local gameplay mutation and progress animation. */
    fun gameNow(): Long = requireNotNull(trustedGameClock.nowOrNull()) {
        "Trusted game clock is not initialized"
    }.epochMillis

    @Synchronized
    fun retryGameInitialization() {
        if (gameInitialization?.isActive == true) return
        val retry = startGameInitialization()
        gameInitialization = retry
        applicationScope.launch {
            try {
                retry.await()
                schedulePendingOfflineRewardDrain()
                scheduleGameLifecycleReconciliation()
                if (BuildConfig.REMOTE_SERVICES_ENABLED) supabaseGameService.initialize()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The repository exposes the retryable startup failure to the UI.
            }
        }
    }

    suspend fun runBackgroundGameSettlement() {
        if (!awaitGameInitialization()) return
        refreshTrustedGameTime()
        gameRepository.runBackgroundSettlement(gameNow())
        trustedGameClock.checkpoint()
    }

    private fun startGameInitialization(): Deferred<Unit> = applicationScope.async {
        try {
            gameTimeMutex.withLock {
                val bootstrap = establishGameClock()
                val initialized = gameRepository.initialize(
                    now = bootstrap.reading.epochMillis,
                    trustedTime = bootstrap.reading.isServerVerified,
                    deferUnverifiedSettlement = bootstrap.deferUnverifiedSettlement,
                )
                if (!initialized) throw GameStateInitializationException()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure !is GameStateInitializationException) {
                gameRepository.reportClockInitializationFailure()
            }
            throw failure
        }
    }

    private suspend fun awaitGameInitialization(): Boolean = try {
        val initialization = gameInitialization ?: return false
        initialization.await()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun establishGameClock(): GameClockBootstrap {
        trustedGameClock.nowOrNull()?.let { current ->
            if (current.isServerVerified) return GameClockBootstrap(current)
        }
        val serverEpochMillis = supabaseGameService.fetchServerEpochMillis().getOrNull()
        if (serverEpochMillis != null) {
            trustedGameClock.installVerifiedServerObservation(serverEpochMillis)?.let {
                lastServerTimeObservationElapsedRealtime = SystemClock.elapsedRealtime()
                return GameClockBootstrap(it)
            }
        }
        trustedGameClock.nowOrNull()?.let { return GameClockBootstrap(it) }

        // After a reboot, resume from the latest durable game checkpoint and deliberately defer
        // cross-boot offline credit until server time is available. On the first trusted-clock
        // upgrade, an existing save starts from its checkpoint instead of the editable wall clock.
        val persistedAnchorEpoch = trustedGameClock.persistedAnchorOrNull()?.serverEpochMs
        val savedTimelineCeiling = gameRepository.persistedTimelineCeilingOrNull()
        val provisionalEpochMillis = listOfNotNull(
            persistedAnchorEpoch,
            savedTimelineCeiling,
        ).maxOrNull()
            ?: System.currentTimeMillis().coerceAtLeast(0L)
        val reading = requireNotNull(
            trustedGameClock.installProvisionalAnchor(provisionalEpochMillis),
        ) {
            "Could not establish a durable game clock"
        }
        return GameClockBootstrap(
            reading = reading,
            deferUnverifiedSettlement = persistedAnchorEpoch != null || savedTimelineCeiling != null,
        )
    }

    private suspend fun refreshTrustedGameTime(): Boolean = gameTimeMutex.withLock {
        val elapsedRealtime = SystemClock.elapsedRealtime()
        val lastObservation = lastServerTimeObservationElapsedRealtime
        if (
            lastObservation >= 0L &&
            elapsedRealtime >= lastObservation &&
            elapsedRealtime - lastObservation < SERVER_TIME_REFRESH_COOLDOWN_MILLIS
        ) {
            return@withLock true
        }
        val serverEpochMillis = supabaseGameService.fetchServerEpochMillis().getOrNull()
            ?: return@withLock false
        gameRepository.adoptVerifiedServerTime(trustedGameClock, serverEpochMillis).also { adopted ->
            if (adopted) {
                lastServerTimeObservationElapsedRealtime = SystemClock.elapsedRealtime()
            }
        }
    }

    private companion object {
        const val TAG = "AlarmQuestAds"
        const val RANKING_SYNC_INTERVAL_MILLIS = 5L * 60L * 1_000L
        const val SERVER_TIME_REFRESH_COOLDOWN_MILLIS = 30_000L
        const val WORK_MANAGER_MIN_JOB_ID = 10_000
        const val WORK_MANAGER_MAX_JOB_ID = 19_999
    }

    private data class GameClockBootstrap(
        val reading: TrustedTimeReading,
        val deferUnverifiedSettlement: Boolean = false,
    )

    private class GameStateInitializationException : IllegalStateException(
        "Game state initialization failed",
    )
}
