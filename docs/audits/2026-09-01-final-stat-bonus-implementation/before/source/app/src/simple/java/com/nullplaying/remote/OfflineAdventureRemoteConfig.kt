package com.nullplaying.remote

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.nullplaying.BuildConfig
import com.nullplaying.engine.OfflineAdventureConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Firebase never blocks game startup. Invalid values keep the last validated configuration. */
class OfflineAdventureRemoteConfig(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("offline_adventure_config", Context.MODE_PRIVATE)
    private val mutableConfig = MutableStateFlow(
        OfflineAdventureConfig.parse(
            preferences.getString(OfflineAdventureConfig.CAPACITY_KEY, "720").orEmpty(),
            preferences.getString(OfflineAdventureConfig.CHARGE_KEY, "12").orEmpty(),
        ) ?: OfflineAdventureConfig(),
    )
    val config = mutableConfig.asStateFlow()
    @Volatile private var remoteConfig: FirebaseRemoteConfig? = null
    private val fetching = AtomicBoolean(false)
    private var started = false

    fun start() {
        if (started) return
        started = true
        try {
            val app = FirebaseApp.initializeApp(appContext)
            if (app == null) {
                Log.w(TAG, "Firebase configuration missing; keeping offline adventure defaults/cache")
                return
            }
            val remote = FirebaseRemoteConfig.getInstance(app)
            val settings = FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(if (BuildConfig.DEBUG) 60L else 3_600L)
                .setFetchTimeoutInSeconds(10L)
                .build()
            Tasks.whenAll(
                remote.setConfigSettingsAsync(settings),
                remote.setDefaultsAsync(mapOf(
                    OfflineAdventureConfig.CAPACITY_KEY to OfflineAdventureConfig.DEFAULT_CAPACITY_MINUTES,
                    OfflineAdventureConfig.CHARGE_KEY to OfflineAdventureConfig.DEFAULT_CHARGE_MINUTES,
                )),
            ).addOnSuccessListener {
                remoteConfig = remote
                readValidatedConfig(remote)
                refresh()
                remote.addOnConfigUpdateListener(object : ConfigUpdateListener {
                    override fun onUpdate(update: ConfigUpdate) {
                        if (update.updatedKeys.none {
                            it == OfflineAdventureConfig.CAPACITY_KEY || it == OfflineAdventureConfig.CHARGE_KEY
                        }) return
                        remote.activate().addOnSuccessListener { readValidatedConfig(remote) }
                            .addOnFailureListener { Log.w(TAG, "Remote config activation failed", it) }
                    }

                    override fun onError(error: FirebaseRemoteConfigException) {
                        Log.w(TAG, "Realtime config unavailable; keeping last valid values", error)
                    }
                })
            }.addOnFailureListener { Log.w(TAG, "Remote config setup failed; keeping cached values", it) }
        } catch (error: Exception) {
            Log.w(TAG, "Remote config unavailable; keeping cached values", error)
        }
    }

    fun refresh() {
        val remote = remoteConfig ?: return
        if (!fetching.compareAndSet(false, true)) return
        remote.fetchAndActivate().addOnSuccessListener { activated ->
            readValidatedConfig(remote)
            Log.i(TAG, "Fetch succeeded: activated=$activated, capacityMinutes=${config.value.capacityMinutes}, " +
                "chargeMinutes=${config.value.chargeMinutes}")
        }.addOnFailureListener { Log.w(TAG, "Fetch failed; keeping last valid values", it) }
            .addOnCompleteListener { fetching.set(false) }
    }

    private fun readValidatedConfig(remote: FirebaseRemoteConfig) {
        val next = OfflineAdventureConfig.parse(
            remote.getString(OfflineAdventureConfig.CAPACITY_KEY),
            remote.getString(OfflineAdventureConfig.CHARGE_KEY),
        )
        if (next == null) {
            Log.w(TAG, "Invalid offline adventure parameters rejected; keeping last valid values")
            return
        }
        mutableConfig.value = next
        Log.i(TAG, "Validated config: capacityMinutes=${next.capacityMinutes}, chargeMinutes=${next.chargeMinutes}, " +
            "capacitySource=${remote.getValue(OfflineAdventureConfig.CAPACITY_KEY).source}, " +
            "chargeSource=${remote.getValue(OfflineAdventureConfig.CHARGE_KEY).source}")
    }

    /** Save only after settlement/application, so a background fetch cannot rewrite past time. */
    fun markApplied(next: OfflineAdventureConfig) {
        preferences.edit()
            .putString(OfflineAdventureConfig.CAPACITY_KEY, next.capacityMinutes.toString())
            .putString(OfflineAdventureConfig.CHARGE_KEY, next.chargeMinutes.toString())
            .apply()
    }

    companion object {
        const val TAG = "OfflineAdventureConfig"
    }
}
