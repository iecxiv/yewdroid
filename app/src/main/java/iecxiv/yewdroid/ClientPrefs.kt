package iecxiv.yewdroid

import android.content.Context

/**
 * Small client preferences read synchronously at startup (so they live in SharedPreferences):
 * the tab shown when the app opens and whether the download list is cleared when the app closes.
 */
object ClientPrefs {
    private const val PREFS = "yewdroid_client"
    private const val START_TAB = "start_tab"
    private const val CLEAR_DOWNLOADS = "clear_downloads_on_close"

    /** Tabs that can be the start tab: stored name to the tab's label. */
    val START_TABS = listOf(
        "SEARCH" to R.string.client_tab_search,
        "PLAYLISTS" to R.string.client_tab_playlists,
        "HISTORY" to R.string.client_tab_history
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun startTab(context: Context): String =
        prefs(context).getString(START_TAB, "SEARCH") ?: "SEARCH"

    fun saveStartTab(context: Context, name: String) {
        prefs(context).edit().putString(START_TAB, name).apply()
    }

    fun clearDownloadsOnClose(context: Context): Boolean =
        prefs(context).getBoolean(CLEAR_DOWNLOADS, false)

    fun saveClearDownloadsOnClose(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(CLEAR_DOWNLOADS, value).apply()
    }
}
