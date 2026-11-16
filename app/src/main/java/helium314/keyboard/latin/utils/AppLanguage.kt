// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import java.util.Locale

/**
 * Language of the app's own screens (settings), independent of the keyboard's typing languages.
 * Android 13+ keeps it as the per-app language (LocaleManager), so it is the same one that
 * Android's "App languages" settings show. Older versions keep it in a preference and apply it
 * to the settings activity in [AppLanguage.wrap].
 */
object AppLanguage {
    /** language tags offered in the picker; "" is the system default. Keep in sync with res/xml/locales_config.xml */
    val LANGUAGES = listOf("", "en", "sk", "cs", "de", "es", "nl", "hu")

    fun displayNameRes(tag: String) = when (tag) {
        "en" -> R.string.app_language_name_en
        "sk" -> R.string.app_language_name_sk
        "cs" -> R.string.app_language_name_cs
        "de" -> R.string.app_language_name_de
        "es" -> R.string.app_language_name_es
        "nl" -> R.string.app_language_name_nl
        "hu" -> R.string.app_language_name_hu
        else -> R.string.app_language_system
    }

    /** the chosen language tag, or "" for the system default */
    fun get(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales == null || locales.isEmpty) return ""
            val language = locales[0].language
            return if (language in LANGUAGES) language else locales[0].toLanguageTag()
        }
        return context.prefs().getString(Settings.PREF_APP_LANGUAGE, Defaults.PREF_APP_LANGUAGE) ?: ""
    }

    /** Sets the language; the settings screens are recreated so they show it right away. */
    fun set(activity: Activity, tag: String) {
        activity.prefs().edit { putString(Settings.PREF_APP_LANGUAGE, tag) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // the system restarts the app's activities with the new configuration itself
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.recreate()
        }
    }

    /** Before Android 13: [base] with the chosen language, for the settings activity's attachBaseContext. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = try {
            base.prefs().getString(Settings.PREF_APP_LANGUAGE, Defaults.PREF_APP_LANGUAGE)
        } catch (e: Exception) {
            null // storage not ready (e.g. direct boot): keep the system language
        }
        if (tag.isNullOrEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
