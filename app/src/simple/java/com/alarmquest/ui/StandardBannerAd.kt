package com.alarmquest.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError

/** A fixed 320x50 test banner centered in a 60dp host at the top of the game screen. */
@Composable
internal fun StandardBannerAd(
    mobileAdsReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val adView = remember(context) { AdView(context) }
    var loadState by remember { mutableStateOf(BannerLoadState.WAITING) }

    LaunchedEffect(mobileAdsReady, adView, isPreview) {
        if (!mobileAdsReady || isPreview) return@LaunchedEffect
        loadState = BannerLoadState.LOADING
        adView.loadAd(
            BannerAdRequest.Builder(TEST_BANNER_AD_UNIT_ID, AdSize.BANNER).build(),
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    loadState = BannerLoadState.LOADED
                    val loadedSize = ad.getAdSize()
                    Log.d(TAG, "Standard test banner loaded: ${loadedSize.width}x${loadedSize.height}dp")
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    loadState = BannerLoadState.FAILED
                    Log.w(TAG, "Standard test banner failed: $adError")
                }
            },
        )
    }

    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BANNER_HOST_HEIGHT)
            .background(Color(0xFF100C16))
            .border(width = 1.dp, color = AqSurfaceHigh)
            .semantics { contentDescription = "상단 일반 테스트 배너 광고 320x50dp" },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { adView },
            modifier = Modifier
                .width(BANNER_WIDTH)
                .height(BANNER_HEIGHT),
        )
        if (loadState != BannerLoadState.LOADED) {
            Text(
                text = when (loadState) {
                    BannerLoadState.FAILED -> "TEST AD · 로드 실패"
                    BannerLoadState.LOADING -> "TEST AD · 불러오는 중"
                    else -> "TEST AD · 320×50"
                },
                color = AqMuted.copy(alpha = 0.58f),
                fontSize = 9.sp,
            )
        }
    }
}

private enum class BannerLoadState {
    WAITING,
    LOADING,
    LOADED,
    FAILED,
}

private val BANNER_HOST_HEIGHT = 60.dp
private val BANNER_WIDTH = 320.dp
private val BANNER_HEIGHT = 50.dp

private const val TAG = "AlarmQuestAds"
private const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/9214589741"
