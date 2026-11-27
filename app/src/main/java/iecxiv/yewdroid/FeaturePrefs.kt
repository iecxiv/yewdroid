package iecxiv.yewdroid

import android.content.Context
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

    /** Whether the terminal exists at all; when off the app shows only the interface. On by default. */
    fun terminalEnabled(context: Context): Flow<Boolean> = data(context).map { it[terminalEnabledKey] ?: true }

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
