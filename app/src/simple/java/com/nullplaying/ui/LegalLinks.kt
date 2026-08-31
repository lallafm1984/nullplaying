package com.nullplaying.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.nullplaying.BuildConfig
import com.nullplaying.localization.AppLanguage

internal fun privacyPolicyUrl(
    baseUrl: String,
    language: AppLanguage,
): String {
    val normalizedBaseUrl = baseUrl.trimEnd('/')
    return when (language) {
        AppLanguage.KOREAN -> normalizedBaseUrl
        AppLanguage.ENGLISH -> "$normalizedBaseUrl/en"
        AppLanguage.JAPANESE -> "$normalizedBaseUrl/ja"
    }
}

internal fun openPrivacyPolicy(context: Context, language: AppLanguage) {
    openWebPage(
        context = context,
        url = privacyPolicyUrl(BuildConfig.PRIVACY_POLICY_URL, language),
        failureMessage = "개인정보처리방침을 열지 못했습니다.",
    )
}

private fun openWebPage(
    context: Context,
    url: String,
    failureMessage: String,
) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
        )
    }.onFailure {
        Toast.makeText(
            context,
            localized(failureMessage),
            Toast.LENGTH_LONG,
        ).show()
    }
}
