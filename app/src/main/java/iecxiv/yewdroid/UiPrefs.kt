package iecxiv.yewdroid

import android.content.Context

/**
 * Startup preferences kept in plain SharedPreferences: they are read synchronously, before any screen
 * exists, and the Python download queue reads the clear-on-close flag from the same file.
 */
object UiPrefs {
    const val TAB_SEARCH = "search"
    const val TAB_PLAYLISTS = "playlists"
    const val TAB_HISTORY = "history"

    private const val FILE = "yewdroid_ui"
    private const val KEY_TAB = "default_tab"
    private const val KEY_CLEAR = "clear_downloads_on_close"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The tab shown when the app opens: search, playlists or history. */
    fun defaultTab(context: Context): String =
        prefs(context).getString(KEY_TAB, TAB_SEARCH) ?: TAB_SEARCH

    fun saveDefaultTab(context: Context, tab: String) {
        prefs(context).edit().putString(KEY_TAB, tab).apply()
    }

    /** Whether the downloads list is emptied when the app is closed (and on the next start). */
    fun clearDownloadsOnClose(context: Context): Boolean = prefs(context).getBoolean(KEY_CLEAR, false)

    fun saveClearDownloadsOnClose(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLEAR, value).apply()
    }
}
