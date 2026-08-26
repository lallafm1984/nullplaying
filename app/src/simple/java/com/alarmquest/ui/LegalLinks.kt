package com.alarmquest.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import com.alarmquest.BuildConfig

internal fun openPrivacyPolicy(context: Context) {
    openWebPage(
        context = context,
        url = BuildConfig.PRIVACY_POLICY_URL,
        failureMessage = "개인정보처리방침을 열지 못했습니다.",
    )
}

internal fun openDataDeletionPage(context: Context) {
    openWebPage(
        context = context,
        url = BuildConfig.DATA_DELETION_URL,
        failureMessage = "삭제 요청 페이지를 열지 못했습니다.",
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

internal fun copyDataIdentifier(
    context: Context,
    dataIdentifier: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("NULL PLAYING data identifier", dataIdentifier)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, localized("데이터 식별 ID를 복사했습니다."), Toast.LENGTH_SHORT).show()
}
