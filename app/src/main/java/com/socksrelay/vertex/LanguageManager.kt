package com.socksrelay.vertex

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Wraps AndroidX's per-app language API (`AppCompatDelegate.setApplicationLocales`),
 * which handles persistence and Activity recreation itself — there's no
 * separate "save the preference" step needed here, the framework does it.
 *
 * On Android 13+ this also integrates with the system's own
 * per-app language settings (Settings > System > Languages > App languages),
 * as long as `locales_config.xml` (referenced from the manifest via
 * `android:localeConfig`) lists the same languages.
 *
 * Only English and Spanish ship with real translations right now — see
 * `res/values-es/strings.xml`. Adding another language is: create
 * `res/values-<code>/strings.xml` with the same string keys translated,
 * add a matching `<locale>` line to `locales_config.xml`, and add it to
 * [SUPPORTED_LANGUAGES] below so it shows up in the picker.
 */
object LanguageManager {
    data class Language(val code: String, val displayName: String)

    val SUPPORTED_LANGUAGES = listOf(
        Language("en", "English"),
        Language("es", "Español")
    )

    fun setLanguage(code: String) {
        val locales = if (code == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(code)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun currentLanguageCode(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return "system"
        return locales[0]?.language ?: "system"
    }
}
