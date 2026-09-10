package com.nullplaying.ui

import com.nullplaying.ads.AdsConsentState
import com.nullplaying.ads.MobileAdsRuntimeState
import com.nullplaying.ads.actions
import com.nullplaying.localization.AppLanguage

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

internal enum class RewardedBenefit {
    OFFLINE_ADVENTURE,
    ARENA_TICKETS,
}

internal data class ArenaRewardedRefillGrant(
    val identity: String,
    val requestId: String,
)

internal data class RewardDialogPresentation(
    val message: String,
    val supportingMessage: String? = null,
    val confirmLabel: String,
    val confirmEnabled: Boolean,
    val action: RewardDialogAction,
)

internal fun arenaRewardedRefillTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "출전권 충전"
    AppLanguage.ENGLISH -> "Refill Arena entries"
    AppLanguage.JAPANESE -> "出場券回復"
}

internal fun arenaRewardedRefillSupportingMessage(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "10분 자동 충전 시간 유지\n캐릭터별 하루 1회 · 출전권 0개일 때 이용"
    AppLanguage.ENGLISH -> "10-minute auto-refill stays active\nOnce daily per character · Available at 0 entries"
    AppLanguage.JAPANESE -> "10分の自動回復は継続\nキャラクターごとに1日1回・出場券0枚で利用可能"
}

internal fun adsSetupNeedsRecovery(
    consentState: AdsConsentState,
    mobileAdsRuntimeState: MobileAdsRuntimeState,
): Boolean = consentState.actions().showRecovery ||
    mobileAdsRuntimeState == MobileAdsRuntimeState.RETRYABLE_ERROR

internal fun rewardDialogPresentation(
    consentState: AdsConsentState,
    mobileAdsRuntimeState: MobileAdsRuntimeState,
    rewardedLoadState: RewardedLoadState,
    benefit: RewardedBenefit = RewardedBenefit.OFFLINE_ADVENTURE,
    arenaRefillCount: Int = BATTLE_ENTRY_CAPACITY,
): RewardDialogPresentation = when {
    adsSetupNeedsRecovery(consentState, mobileAdsRuntimeState) -> RewardDialogPresentation(
        message = when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE ->
                "광고 설정을 불러오지 못했습니다. 오프라인 모험은 앱을 켜 둔 동안 계속 충전됩니다."
            RewardedBenefit.ARENA_TICKETS ->
                "광고 설정을 불러오지 못했습니다. 진행 중인 자동 충전은 계속됩니다."
        },
        confirmLabel = "광고 설정 다시 연결",
        confirmEnabled = true,
        action = RewardDialogAction.RETRY_AD_SETUP,
    )
    rewardedLoadState == RewardedLoadState.READY -> RewardDialogPresentation(
        message = when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE ->
                "광고를 끝까지 보면\n오프라인 모험 시간이 즉시 충전됩니다."
            RewardedBenefit.ARENA_TICKETS ->
                "광고 시청 완료 시\n출전권 ${arenaRefillCount.coerceIn(1, BATTLE_ENTRY_CAPACITY)}개 즉시 충전"
        },
        supportingMessage = when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE ->
                "광고를 보지 않아도 앱을 켜 둔 동안\n자동으로 충전됩니다."
            RewardedBenefit.ARENA_TICKETS ->
                "10분 자동 충전 시간 유지\n" +
                    "캐릭터별 하루 1회 · 출전권 0개일 때 이용"
        },
        confirmLabel = when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE -> "광고 보고 모두 충전"
            RewardedBenefit.ARENA_TICKETS -> "광고로 ${arenaRefillCount.coerceIn(1, BATTLE_ENTRY_CAPACITY)}회 충전"
        },
        confirmEnabled = true,
        action = RewardDialogAction.WATCH_AD,
    )
    rewardedLoadState == RewardedLoadState.LOAD_FAILED ||
        rewardedLoadState == RewardedLoadState.SHOW_FAILED -> RewardDialogPresentation(
        message = when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE ->
                "광고를 불러오지 못했습니다. 오프라인 모험은 앱을 켜 둔 동안 계속 충전되며, 원하면 광고를 다시 시도할 수 있습니다."
            RewardedBenefit.ARENA_TICKETS ->
                "광고를 불러오지 못했습니다. 진행 중인 자동 충전은 계속됩니다."
        },
        confirmLabel = "다시 시도",
        confirmEnabled = true,
        action = RewardDialogAction.RETRY_AD_LOAD,
    )
    else -> RewardDialogPresentation(
        message = when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE ->
                "광고를 준비하고 있습니다. 오프라인 모험은 앱을 켜 둔 동안 계속 충전됩니다."
            RewardedBenefit.ARENA_TICKETS ->
                "광고 준비 중입니다. 진행 중인 자동 충전은 계속됩니다."
        },
        confirmLabel = "준비 중",
        confirmEnabled = false,
        action = RewardDialogAction.NONE,
    )
}
