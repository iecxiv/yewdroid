package iecxiv.yewdroid

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * The language of the whole app (menus, dialogs, notifications). "system" follows the device language
 * when the app has a translation for it and falls back to the default (English) otherwise.
 *
 * It is stored in plain SharedPreferences because it is needed synchronously, before any screen exists.
 */
object AppLanguage {
    const val SYSTEM = "system"

    /** Languages the app is translated into: language tag to its name written in that language. */
    val SUPPORTED = listOf("en" to "English", "es" to "Espa\u00f1ol")

    private const val PREFS = "yewdroid_language"
    private const val KEY = "app_language"

    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

    private fun localeFor(choice: String): Locale =
        if (choice == SYSTEM) systemLocale() else Locale.forLanguageTag(choice)

    fun saved(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    /** Stores the choice and makes it the default locale at once (number and date formats follow it). */
    fun save(context: Context, choice: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice).apply()
        Locale.setDefault(localeFor(choice))
    }

    /** True when the device language is one the app is translated into. */
    fun systemSupported(): Boolean = SUPPORTED.any { it.first == systemLocale().language }

    /** Applies the saved language to [base]; call it from attachBaseContext of activities and services. */
    fun wrap(base: Context): Context {
        val choice = saved(base)
        val locale = localeFor(choice)
        Locale.setDefault(locale)
        if (choice == SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
