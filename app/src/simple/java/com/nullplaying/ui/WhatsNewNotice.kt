package com.nullplaying.ui

import android.content.Context
import com.nullplaying.localization.AppLanguage
import com.nullplaying.remote.AppAnnouncement
import com.nullplaying.remote.AppAnnouncementDisplayType

internal const val WHATS_NEW_VERSION_CODE = 25

/** Package metadata also identifies upgrades from releases that had no notice preferences. */
@Suppress("DEPRECATION")
internal fun isUpdatedInstallation(context: Context): Boolean = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.lastUpdateTime > info.firstInstallTime
}.getOrDefault(false)

internal fun whatsNewNoticeEligible(versionCode: Int, wasUpdated: Boolean): Boolean =
    versionCode == WHATS_NEW_VERSION_CODE && wasUpdated

internal fun whatsNewNotice(language: AppLanguage): AppAnnouncement = AppAnnouncement(
    id = -WHATS_NEW_VERSION_CODE.toLong(),
    announcementKey = "local-whats-new-0.5.1-$WHATS_NEW_VERSION_CODE",
    title = when (language) {
        AppLanguage.KOREAN -> "0.5.1 업데이트"
        AppLanguage.ENGLISH -> "What’s new in 0.5.1"
        AppLanguage.JAPANESE -> "0.5.1 アップデート"
    },
    message = when (language) {
        AppLanguage.KOREAN -> "모험 사건\n마을과 사냥터, 이동 중에 다양한 사건을 만납니다.\n\n" +
            "인연\nLv.10부터 다른 모험가와 만나 다양한 관계를 쌓습니다.\n\n" +
            "모험 특성\n모험에서 쌓은 경험에 따라 장단점이 있는 특성이 생기거나 사라집니다.\n\n" +
            "결투장\nLv.10부터 스킬을 설정하고 다른 모험가의 캐릭터와 자동 결투를 즐깁니다."
        AppLanguage.ENGLISH -> "Adventure events\nDiscover new encounters in town, on the road, and in hunting grounds.\n\n" +
            "Relationships\nFrom Lv.10, meet other adventurers and build relationships.\n\n" +
            "Adventure traits\nYour experiences shape traits with strengths and drawbacks. Traits can develop or fade.\n\n" +
            "Arena\nFrom Lv.10, set up your skills and face other adventurers’ characters in automatic duels."
        AppLanguage.JAPANESE -> "冒険イベント\n町や狩り場、移動中にさまざまな出来事が待っています。\n\n" +
            "縁\nLv.10から他の冒険者と出会い、さまざまな関係を築きます。\n\n" +
            "冒険特性\n冒険での経験に応じて、長所と短所を持つ特性が身についたり、失われたりします。\n\n" +
            "闘技場\nLv.10からスキルを設定し、他の冒険者のキャラクターとの自動対戦を楽しめます。"
    },
    displayType = AppAnnouncementDisplayType.ONCE_AFTER_INSTALL,
)
