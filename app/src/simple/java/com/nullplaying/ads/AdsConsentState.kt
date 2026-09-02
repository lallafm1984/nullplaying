package com.nullplaying.ads

enum class ConsentRefreshStatus {
    NOT_STARTED,
    CHECKING,
    COMPLETE,
    RETRYABLE_ERROR,
}

enum class PrivacyOptionsStatus {
    UNKNOWN,
    NOT_REQUIRED,
    REQUIRED,
}

enum class ConsentFailureStage {
    INFO_UPDATE,
    REQUIRED_FORM,
    PRIVACY_OPTIONS_FORM,
}

data class AdsConsentState(
    val refreshStatus: ConsentRefreshStatus = ConsentRefreshStatus.NOT_STARTED,
    val canRequestAds: Boolean = false,
    val privacyOptionsStatus: PrivacyOptionsStatus = PrivacyOptionsStatus.UNKNOWN,
    val failureStage: ConsentFailureStage? = null,
    val diagnosticMessage: String? = null,
)

data class AdsConsentActions(
    val showRecovery: Boolean,
    val showPrivacyOptions: Boolean,
    val recoveryInProgress: Boolean,
)

fun AdsConsentState.actions(): AdsConsentActions = AdsConsentActions(
    // A completed form can still leave consent unresolved without returning an SDK error.
    // Offer the same recovery path in that case, but never treat a requestable state as failed.
    showRecovery = !canRequestAds && (
        refreshStatus == ConsentRefreshStatus.COMPLETE ||
            refreshStatus == ConsentRefreshStatus.RETRYABLE_ERROR
        ),
    showPrivacyOptions = privacyOptionsStatus == PrivacyOptionsStatus.REQUIRED &&
        refreshStatus != ConsentRefreshStatus.NOT_STARTED &&
        refreshStatus != ConsentRefreshStatus.CHECKING,
    recoveryInProgress = refreshStatus == ConsentRefreshStatus.CHECKING,
)

enum class MobileAdsRuntimeState {
    WAITING_FOR_CONSENT,
    INITIALIZING,
    READY,
    RETRYABLE_ERROR,
}
