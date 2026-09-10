package com.nullplaying

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nullplaying.ui.AlarmQuestApp
import com.nullplaying.ui.AlarmQuestTheme
import com.nullplaying.ui.localized

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        enterImmersiveMode()
        val alarmQuestApplication = application as AlarmQuestApplication
        setContent {
            val mobileAdsReady by alarmQuestApplication.mobileAdsReady.collectAsState()
            val adsConsentState by
                alarmQuestApplication.adsConsentState.collectAsState()
            val mobileAdsRuntimeState by
                alarmQuestApplication.mobileAdsRuntimeState.collectAsState()
            AlarmQuestTheme {
                AlarmQuestApp(
                    repository = alarmQuestApplication.gameRepository,
                    notificationPreferencesStore =
                        alarmQuestApplication.notificationPreferencesStore,
                    gameLanguageStore = alarmQuestApplication.gameLanguageStore,
                    supabaseGameService = alarmQuestApplication.supabaseGameService,
                    gameNow = alarmQuestApplication::gameNow,
                    onRetryGameInitialization = alarmQuestApplication::retryGameInitialization,
                    mobileAdsReady = mobileAdsReady,
                    adsConsentState = adsConsentState,
                    mobileAdsRuntimeState = mobileAdsRuntimeState,
                    onRetryAdsSetup = {
                        alarmQuestApplication.retryAdsSetup(this@MainActivity)
                    },
                    onOpenPrivacyOptions = {
                        alarmQuestApplication.showAdsPrivacyOptions(this@MainActivity) {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(
                                    this@MainActivity,
                                    localized("광고 개인정보 설정을 열지 못했습니다."),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    onBeginRewardedAdSession = alarmQuestApplication::beginRewardedAdSession,
                    onFinishRewardedAdSession = alarmQuestApplication::finishRewardedAdSession,
                    onEarnedOfflineAdventureReward =
                        alarmQuestApplication::enqueueEarnedOfflineAdventureReward,
                )
            }
        }
        if (BuildConfig.REMOTE_SERVICES_ENABLED) {
            alarmQuestApplication.gatherAdsConsent(this)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onStart() {
        super.onStart()
        (application as AlarmQuestApplication).onAppForegrounded()
    }

    override fun onResume() {
        super.onResume()
        (application as AlarmQuestApplication).onMainActivityResumed()
    }

    override fun onPause() {
        (application as AlarmQuestApplication).onMainActivityPaused()
        super.onPause()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            (application as AlarmQuestApplication).onAppBackgrounded()
        }
        super.onStop()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

}
