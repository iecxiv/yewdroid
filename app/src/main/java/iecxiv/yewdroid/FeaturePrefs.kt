package iecxiv.yewdroid

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.featureDataStore by preferencesDataStore(name = "feature_preferences")

/** What tapping a result puts in the queue. */
enum class QueueMode(val stored: String) {
    /** Only the video tapped. */
    NONE("none"),
    /** The video tapped and the rest of the results on screen. */
    PAGE("page"),
    /** The video tapped and the videos YouTube suggests after it. */
    RELATED("related");

    companion object {
        fun from(value: String?): QueueMode? = values().firstOrNull { it.stored == value }
    }
}

/** Settings added after the terminal preferences: the terminal switch and the queue mode. */
object FeaturePrefs {
    private val terminalEnabledKey = booleanPreferencesKey("terminal_enabled")
    private val queueModeKey = stringPreferencesKey("queue_mode")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun data(context: Context) = context.applicationContext.featureDataStore.data.catch { error ->
        if (error is IOException) {
            Log.e("FeaturePrefs", "Cannot read the settings", error)
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    /**
     * True when this install replaced an older one (the app was updated), false on a fresh install. Android keeps
     * the time of the first install and the time of the last update; they are the same on a fresh install.
     * When it cannot be read it counts as an update, which keeps the terminal on.
     */
    private fun isUpdatedInstall(context: Context): Boolean = try {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, 0)
        }
        info.lastUpdateTime - info.firstInstallTime > 2000L
    } catch (_: Exception) {
        true
    }

    /**
     * Whether the terminal exists at all; when off the app shows only the interface. Until the user chooses, it is
     * off on a fresh install and on when the app was updated from a version that always had the terminal. The first
     * answer is stored, so a later update does not change it.
     */
    fun terminalEnabled(context: Context): Flow<Boolean> {
        val app = context.applicationContext
        return data(context).map { values ->
            values[terminalEnabledKey] ?: isUpdatedInstall(app).also { saveTerminalEnabled(app, it) }
        }
    }

    fun saveTerminalEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        scope.launch {
            try {
                app.featureDataStore.edit { it[terminalEnabledKey] = enabled }
            } catch (error: IOException) {
                Log.e("FeaturePrefs", "Cannot save the terminal switch", error)
            }
        }
    }

    /**
     * The queue mode. Until one is chosen it follows the old auto-queue switch: on is the rest of the page,
     * off is only the video tapped.
     */
    fun queueMode(context: Context): Flow<QueueMode> = combine(
        data(context).map { QueueMode.from(it[queueModeKey]) },
        TerminalPreferences.clientAutoQueue(context)
    ) { chosen, legacy -> chosen ?: if (legacy) QueueMode.PAGE else QueueMode.NONE }

    fun saveQueueMode(context: Context, mode: QueueMode) {
        val app = context.applicationContext
        scope.launch {
            try {
                app.featureDataStore.edit { it[queueModeKey] = mode.stored }
            } catch (error: IOException) {
                Log.e("FeaturePrefs", "Cannot save the queue mode", error)
            }
        }
    }
}
