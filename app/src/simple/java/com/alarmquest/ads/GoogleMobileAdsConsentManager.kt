package com.alarmquest.ads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Activity-backed UMP flow for consent gathering and privacy option re-entry. */
class GoogleMobileAdsConsentManager(context: Context) {
    private val consentInformation =
        UserMessagingPlatform.getConsentInformation(context.applicationContext)
    private val _privacyOptionsRequired = MutableStateFlow(readPrivacyOptionsRequired())
    private val consentGatheringInProgress = AtomicBoolean(false)
    private val latestActivity = AtomicReference<WeakReference<Activity>?>(null)

    val privacyOptionsRequired = _privacyOptionsRequired.asStateFlow()

    val canRequestAds: Boolean
        get() = consentInformation.canRequestAds()

    fun gatherConsent(
        activity: Activity,
        onComplete: (errorMessage: String?) -> Unit,
    ): Boolean {
        latestActivity.set(WeakReference(activity))
        if (!consentGatheringInProgress.compareAndSet(false, true)) return false
        consentInformation.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                refreshPrivacyOptionsRequirement()
                val formActivity = latestUsableActivity()
                if (formActivity == null) {
                    consentGatheringInProgress.set(false)
                    onComplete("No active Activity is available for the UMP consent form.")
                    return@requestConsentInfoUpdate
                }
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(formActivity) { formError ->
                    consentGatheringInProgress.set(false)
                    refreshPrivacyOptionsRequirement()
                    onComplete(formError?.message)
                }
            },
            { requestError ->
                consentGatheringInProgress.set(false)
                refreshPrivacyOptionsRequirement()
                onComplete(requestError.message)
            },
        )
        // A previous session may already contain a valid consent decision.
        refreshPrivacyOptionsRequirement()
        return true
    }

    fun showPrivacyOptionsForm(
        activity: Activity,
        onComplete: (errorMessage: String?) -> Unit,
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            refreshPrivacyOptionsRequirement()
            onComplete(formError?.message)
        }
    }

    private fun latestUsableActivity(): Activity? =
        latestActivity.get()?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun refreshPrivacyOptionsRequirement() {
        _privacyOptionsRequired.value = readPrivacyOptionsRequired()
    }

    private fun readPrivacyOptionsRequired(): Boolean =
        consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
}
