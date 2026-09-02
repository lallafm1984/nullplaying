package com.nullplaying.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.nullplaying.BuildConfig
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Activity-backed UMP flow for consent gathering and privacy option re-entry. */
class GoogleMobileAdsConsentManager(context: Context) {
    private val appContext = context.applicationContext
    private val consentInformation =
        UserMessagingPlatform.getConsentInformation(appContext)
    private val _state = MutableStateFlow(
        readState(refreshStatus = ConsentRefreshStatus.NOT_STARTED),
    )
    private val consentGatheringInProgress = AtomicBoolean(false)
    private val latestActivity = AtomicReference<WeakReference<Activity>?>(null)

    val state = _state.asStateFlow()

    val canRequestAds: Boolean
        get() = consentInformation.canRequestAds()

    init {
        if (BuildConfig.IS_EEA_QA) {
            // Dedicated side-by-side QA builds simulate a first launch on every process start.
            // The production build constant is false, so release builds can never reset consent.
            consentInformation.reset()
        }
    }

    fun gatherConsent(
        activity: Activity,
        onComplete: (errorMessage: String?) -> Unit,
    ): Boolean {
        latestActivity.set(WeakReference(activity))
        if (!consentGatheringInProgress.compareAndSet(false, true)) return false
        publishState(refreshStatus = ConsentRefreshStatus.CHECKING)
        try {
            consentInformation.requestConsentInfoUpdate(
                activity,
                consentRequestParameters(),
                {
                    publishState(refreshStatus = ConsentRefreshStatus.CHECKING)
                    showRequiredConsentForm(onComplete)
                },
                { requestError ->
                    finishWithFailure(
                        stage = ConsentFailureStage.INFO_UPDATE,
                        message = requestError.message,
                        onComplete = onComplete,
                    )
                },
            )
        } catch (error: Exception) {
            finishWithFailure(
                stage = ConsentFailureStage.INFO_UPDATE,
                message = error.diagnosticMessage(),
                onComplete = onComplete,
            )
        }
        // A previous session may already contain a valid consent decision.
        if (consentGatheringInProgress.get()) {
            publishState(refreshStatus = ConsentRefreshStatus.CHECKING)
        }
        return true
    }

    private fun consentRequestParameters(): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
        if (!BuildConfig.IS_EEA_QA) return builder.build()

        val testDeviceHash = BuildConfig.UMP_TEST_DEVICE_HASH.trim()
        if (testDeviceHash.isEmpty()) {
            Log.w(TAG, "EEA QA is waiting for the UMP test-device hash from Logcat")
            return builder.build()
        }
        val debugSettings = ConsentDebugSettings.Builder(appContext)
            .setDebugGeography(
                ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA,
            )
            .addTestDeviceHashedId(testDeviceHash)
            .build()
        Log.i(TAG, "EEA QA consent geography override is active")
        return builder
            .setConsentDebugSettings(debugSettings)
            .build()
    }

    fun showPrivacyOptionsForm(
        activity: Activity,
        onComplete: (errorMessage: String?) -> Unit,
    ) {
        if (!consentGatheringInProgress.compareAndSet(false, true)) return
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                consentGatheringInProgress.set(false)
                publishState(
                    refreshStatus = if (formError == null) {
                        ConsentRefreshStatus.COMPLETE
                    } else {
                        ConsentRefreshStatus.RETRYABLE_ERROR
                    },
                    failureStage = formError?.let { ConsentFailureStage.PRIVACY_OPTIONS_FORM },
                    diagnosticMessage = formError?.message,
                )
                onComplete(formError?.message)
            }
        } catch (error: Exception) {
            finishWithFailure(
                stage = ConsentFailureStage.PRIVACY_OPTIONS_FORM,
                message = error.diagnosticMessage(),
                onComplete = onComplete,
            )
        }
    }

    private fun showRequiredConsentForm(onComplete: (errorMessage: String?) -> Unit) {
        val formActivity = latestUsableActivity()
        if (formActivity == null) {
            finishWithFailure(
                stage = ConsentFailureStage.REQUIRED_FORM,
                message = "No active Activity is available for the UMP consent form.",
                onComplete = onComplete,
            )
            return
        }
        try {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(formActivity) { formError ->
                consentGatheringInProgress.set(false)
                publishState(
                    refreshStatus = if (formError == null) {
                        ConsentRefreshStatus.COMPLETE
                    } else {
                        ConsentRefreshStatus.RETRYABLE_ERROR
                    },
                    failureStage = formError?.let { ConsentFailureStage.REQUIRED_FORM },
                    diagnosticMessage = formError?.message,
                )
                onComplete(formError?.message)
            }
        } catch (error: Exception) {
            finishWithFailure(
                stage = ConsentFailureStage.REQUIRED_FORM,
                message = error.diagnosticMessage(),
                onComplete = onComplete,
            )
        }
    }

    private fun finishWithFailure(
        stage: ConsentFailureStage,
        message: String,
        onComplete: (errorMessage: String?) -> Unit,
    ) {
        consentGatheringInProgress.set(false)
        publishState(
            refreshStatus = ConsentRefreshStatus.RETRYABLE_ERROR,
            failureStage = stage,
            diagnosticMessage = message,
        )
        onComplete(message)
    }

    private fun Exception.diagnosticMessage(): String =
        message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private fun latestUsableActivity(): Activity? =
        latestActivity.get()?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun publishState(
        refreshStatus: ConsentRefreshStatus,
        failureStage: ConsentFailureStage? = null,
        diagnosticMessage: String? = null,
    ) {
        val nextState = readState(
            refreshStatus = refreshStatus,
            failureStage = failureStage,
            diagnosticMessage = diagnosticMessage,
        )
        _state.value = nextState
    }

    private fun readState(
        refreshStatus: ConsentRefreshStatus,
        failureStage: ConsentFailureStage? = null,
        diagnosticMessage: String? = null,
    ): AdsConsentState = AdsConsentState(
        refreshStatus = refreshStatus,
        canRequestAds = consentInformation.canRequestAds(),
        privacyOptionsStatus = when (consentInformation.privacyOptionsRequirementStatus) {
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED ->
                PrivacyOptionsStatus.REQUIRED
            ConsentInformation.PrivacyOptionsRequirementStatus.NOT_REQUIRED ->
                PrivacyOptionsStatus.NOT_REQUIRED
            else -> PrivacyOptionsStatus.UNKNOWN
        },
        failureStage = failureStage,
        diagnosticMessage = diagnosticMessage,
    )

    private companion object {
        const val TAG = "AdsConsentManager"
    }
}
