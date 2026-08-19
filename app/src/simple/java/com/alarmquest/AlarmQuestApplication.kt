package com.alarmquest

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import com.alarmquest.data.FileSimpleStateBackupStore
import com.alarmquest.data.SimpleDatabase
import com.alarmquest.data.SimpleGameRepository
import com.alarmquest.engine.SimpleGameEngine
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AlarmQuestApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _mobileAdsReady = MutableStateFlow(false)

    val mobileAdsReady = _mobileAdsReady.asStateFlow()

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
        )
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            runCatching {
                MobileAds.initialize(
                    this@AlarmQuestApplication,
                    InitializationConfig.Builder(TEST_ADMOB_APP_ID).build(),
                )
            }.onSuccess {
                _mobileAdsReady.value = true
                Log.d(TAG, "Google Mobile Ads Next-Gen SDK initialized")
            }.onFailure { error ->
                Log.e(TAG, "Google Mobile Ads Next-Gen SDK initialization failed", error)
            }
        }
    }

    fun onAppForegrounded() {
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        applicationScope.launch {
            gameRepository.onAppForegrounded(wallNow, elapsedNow)
        }
    }

    fun onAppBackgrounded() {
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        applicationScope.launch {
            gameRepository.onAppBackgrounded(wallNow, elapsedNow)
        }
    }

    private companion object {
        const val TAG = "AlarmQuestAds"
        const val TEST_ADMOB_APP_ID = "ca-app-pub-3940256099942544~3347511713"
    }
}
