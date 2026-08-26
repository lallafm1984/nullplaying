package com.alarmquest.localization

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the user's explicit in-game language separate from save data.
 *
 * With no saved choice (a fresh install), the supported device language is used. Devices using
 * any other language start in English. Once selected in Settings, the choice persists locally.
 */
class GameLanguageStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val sharedPreferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutableLanguage = MutableStateFlow(load())

    init {
        GameLocalization.setActiveLanguage(mutableLanguage.value)
    }

    val language: StateFlow<AppLanguage> = mutableLanguage.asStateFlow()

    fun setLanguage(language: AppLanguage) {
        val alreadyPersisted =
            sharedPreferences.getString(KEY_LANGUAGE, null) == language.languageTag
        if (alreadyPersisted && mutableLanguage.value == language) return
        sharedPreferences.edit().putString(KEY_LANGUAGE, language.languageTag).apply()
        GameLocalization.setActiveLanguage(language)
        mutableLanguage.value = language
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language.languageTag)
        }
    }

    fun hasExplicitSelection(): Boolean = sharedPreferences.contains(KEY_LANGUAGE)

    private fun load(): AppLanguage =
        AppLanguage.fromLanguageTag(sharedPreferences.getString(KEY_LANGUAGE, null))
            ?: AppLanguage.fromDevice()

    private companion object {
        const val PREFERENCES_NAME = "game_language_preferences"
        const val KEY_LANGUAGE = "language_tag"
    }
}
