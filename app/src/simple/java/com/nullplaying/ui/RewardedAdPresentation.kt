package com.nullplaying.ui

import com.nullplaying.ads.AdsConsentState
import com.nullplaying.ads.MobileAdsRuntimeState
import com.nullplaying.ads.actions

internal enum class RewardedLoadState {
    WAITING,
    LOADING,
    READY,
    SHOWING,
    LOAD_FAILED,
    SHOW_FAILED,
}

internal enum class RewardDialogAction {
    NONE,
    RETRY_AD_SETUP,
    RETRY_AD_LOAD,
    WATCH_AD,
}

internal data class RewardDialogPresentation(
    val message: String,
    val confirmLabel: String,
    val confirmEnabled: Boolean,
    val action: RewardDialogAction,
)

internal fun adsSetupNeedsRecovery(
    consentState: AdsConsentState,
    mobileAdsRuntimeState: MobileAdsRuntimeState,
): Boolean = consentState.actions().showRecovery ||
    mobileAdsRuntimeState == MobileAdsRuntimeState.RETRYABLE_ERROR

internal fun rewardDialogPresentation(
    consentState: AdsConsentState,
    mobileAdsRuntimeState: MobileAdsRuntimeState,
    rewardedLoadState: RewardedLoadState,
): RewardDialogPresentation = when {
    adsSetupNeedsRecovery(consentState, mobileAdsRuntimeState) -> RewardDialogPresentation(
        message = "광고 설정을 불러오지 못했습니다. 오프라인 모험은 앱을 켜 둔 동안 계속 충전됩니다.",
        confirmLabel = "광고 설정 다시 연결",
        confirmEnabled = true,
        action = RewardDialogAction.RETRY_AD_SETUP,
    )
    rewardedLoadState == RewardedLoadState.READY -> RewardDialogPresentation(
        message = "오프라인 모험 시간은 앱을 켜 둔 동안 자동으로 충전됩니다. 광고 시청은 선택 사항입니다. 광고를 끝까지 보면 즉시 가득 충전됩니다.",
        confirmLabel = "광고 보고 모두 충전",
        confirmEnabled = true,
        action = RewardDialogAction.WATCH_AD,
    )
    rewardedLoadState == RewardedLoadState.LOAD_FAILED ||
        rewardedLoadState == RewardedLoadState.SHOW_FAILED -> RewardDialogPresentation(
        message = "광고를 불러오지 못했습니다. 오프라인 모험은 앱을 켜 둔 동안 계속 충전되며, 원하면 광고를 다시 시도할 수 있습니다.",
        confirmLabel = "다시 시도",
        confirmEnabled = true,
        action = RewardDialogAction.RETRY_AD_LOAD,
    )
    else -> RewardDialogPresentation(
        message = "광고를 준비하고 있습니다. 오프라인 모험은 앱을 켜 둔 동안 계속 충전됩니다.",
        confirmLabel = "준비 중",
        confirmEnabled = false,
        action = RewardDialogAction.NONE,
    )
}
