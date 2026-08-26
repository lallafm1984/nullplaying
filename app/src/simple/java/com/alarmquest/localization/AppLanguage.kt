package com.alarmquest.localization

import java.util.Locale

enum class AppLanguage(
    val languageTag: String,
    val selfName: String,
) {
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
    ;

    companion object {
        fun fromLanguageTag(languageTag: String?): AppLanguage? = when (
            languageTag
                ?.substringBefore('-')
                ?.substringBefore('_')
                ?.lowercase(Locale.ROOT)
        ) {
            ENGLISH.languageTag -> ENGLISH
            JAPANESE.languageTag -> JAPANESE
            KOREAN.languageTag -> KOREAN
            else -> null
        }

        fun fromDevice(locale: Locale = Locale.getDefault()): AppLanguage =
            fromLanguageTag(locale.toLanguageTag()) ?: ENGLISH
    }
}
