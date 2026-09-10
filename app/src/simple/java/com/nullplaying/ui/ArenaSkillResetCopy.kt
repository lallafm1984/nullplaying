package com.nullplaying.ui

import com.nullplaying.localization.AppLanguage

internal fun arenaSkillResetLabel(refund: Int, language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "이 스킬 초기화 · ${refund}포인트 반환"
    AppLanguage.ENGLISH -> "Reset this skill · Refund $refund ${if (refund == 1) "point" else "points"}"
    AppLanguage.JAPANESE -> "このスキルをリセット · ${refund}ポイント返還"
}
