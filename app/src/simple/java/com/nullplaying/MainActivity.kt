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
            val privacyOptionsRequired by
                alarmQuestApplication.adsConsentManager.privacyOptionsRequired.collectAsState()
            AlarmQuestTheme {
                AlarmQuestApp(
                    repository = alarmQuestApplication.gameRepository,
                    notificationPreferencesStore =
                        alarmQuestApplication.notificationPreferencesStore,
                    gameLanguageStore = alarmQuestApplication.gameLanguageStore,
                    supabaseGameService = alarmQuestApplication.supabaseGameService,
                    mobileAdsReady = mobileAdsReady,
                    privacyOptionsRequired = privacyOptionsRequired,
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
                )
            }
        }
        alarmQuestApplication.gatherAdsConsent(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onStart() {
        super.onStart()
        (application as AlarmQuestApplication).onAppForegrounded()
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
