package iecxiv.yewdroid

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException

private val Context.terminalDataStore by preferencesDataStore(name = "terminal_preferences")

object TerminalPreferences {
    private const val MAX_HISTORY = 500
    /** Output language choices: "system" follows the phone, the rest are language codes with a catalogue. */
    private val languages = setOf("system", "es")
    private val fontSize = floatPreferencesKey("font_size_sp")
    private val commandHistory = stringPreferencesKey("command_history_json")
    private val terminalModeKey = booleanPreferencesKey("terminal_mode")
    private val translateKey = booleanPreferencesKey("translate_output")
    private val headerExpandedKey = booleanPreferencesKey("header_expanded")
    private val languageKey = stringPreferencesKey("terminal_language")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<Pair<Context, Float>>(Channel.CONFLATED)
    // Append commands in order: unlike zoom changes, commands must not be conflated.
    private val historyWrites = Channel<Pair<Context, String>>(Channel.UNLIMITED)
    private val modeWrites = Channel<Pair<Context, Boolean>>(Channel.CONFLATED)
    private val translateWrites = Channel<Pair<Context, Boolean>>(Channel.CONFLATED)
    private val headerWrites = Channel<Pair<Context, Boolean>>(Channel.CONFLATED)
    private val languageWrites = Channel<Pair<Context, String>>(Channel.CONFLATED)

    init {
        scope.launch {
            for ((context, size) in writes) {
                try {
                    context.terminalDataStore.edit { it[fontSize] = size }
                } catch (error: IOException) {
                    Log.e("TerminalPreferences", "Cannot save zoom", error)
                    TerminalHub.onWrite("\n" + context.getString(R.string.pref_zoom_save_failed) + "\n")
                }
            }
        }
        scope.launch {
            for ((context, line) in historyWrites) {
                try {
                    context.terminalDataStore.edit { values ->
                        val history = (decodeHistory(values[commandHistory]) + line).takeLast(MAX_HISTORY)
                        values[commandHistory] = JSONArray(history).toString()
                    }
                } catch (error: IOException) {
                    Log.e("TerminalPreferences", "Cannot save command history", error)
                    TerminalHub.onWrite("\n" + context.getString(R.string.pref_history_save_failed) + "\n")
                }
            }
        }
        scope.launch {
            for ((context, enabled) in modeWrites) {
                try {
                    context.terminalDataStore.edit { it[terminalModeKey] = enabled }
                } catch (error: IOException) {
                    Log.e("TerminalPreferences", "Cannot save terminal mode", error)
                    TerminalHub.onWrite("\n" + context.getString(R.string.pref_mode_save_failed) + "\n")
                }
            }
        }
        scope.launch {
            for ((context, enabled) in translateWrites) {
                try {
                    context.terminalDataStore.edit { it[translateKey] = enabled }
                } catch (error: IOException) {
                    Log.e("TerminalPreferences", "Cannot save output language", error)
                    TerminalHub.onWrite("\n" + context.getString(R.string.pref_language_save_failed) + "\n")
                }
            }
        }
        scope.launch {
            for ((context, expanded) in headerWrites) {
                try {
                    context.terminalDataStore.edit { it[headerExpandedKey] = expanded }
                } catch (error: IOException) {
                    Log.e("TerminalPreferences", "Cannot save header state", error)
                    TerminalHub.onWrite("\n" + context.getString(R.string.pref_header_save_failed) + "\n")
                }
            }
        }
        scope.launch {
            for ((context, choice) in languageWrites) {
                try {
                    context.terminalDataStore.edit { it[languageKey] = choice }
                } catch (error: IOException) {
                    Log.e("TerminalPreferences", "Cannot save terminal language", error)
                    TerminalHub.onWrite("\n" + context.getString(R.string.pref_terminal_language_save_failed) + "\n")
                }
            }
        }
    }

    fun zoom(context: Context): Flow<Float?> = context.applicationContext.terminalDataStore.data
        .catch { error ->
            if (error is IOException) {
                Log.e("TerminalPreferences", "Cannot read zoom", error)
                emit(emptyPreferences())
            } else throw error
        }
        .map { values ->
            val value = values[fontSize] ?: 11f
            if (value.isFinite()) value.coerceIn(7f, 20f) else 11f
        }

    fun save(context: Context, size: Float) {
        if (size.isFinite()) writes.trySend(context.applicationContext to size.coerceIn(7f, 20f))
    }

    fun terminalMode(context: Context): Flow<Boolean?> = context.applicationContext.terminalDataStore.data
        .catch { error ->
            if (error is IOException) {
                Log.e("TerminalPreferences", "Cannot read terminal mode", error)
                emit(emptyPreferences())
            } else throw error
        }
        .map { values -> values[terminalModeKey] ?: true }

    fun saveTerminalMode(context: Context, enabled: Boolean) {
        modeWrites.trySend(context.applicationContext to enabled)
    }

    fun translateOutput(context: Context): Flow<Boolean> = context.applicationContext.terminalDataStore.data
        .catch { error ->
            if (error is IOException) {
                Log.e("TerminalPreferences", "Cannot read output language", error)
                emit(emptyPreferences())
            } else throw error
        }
        .map { values -> values[translateKey] ?: false }

    fun saveTranslateOutput(context: Context, enabled: Boolean) {
        translateWrites.trySend(context.applicationContext to enabled)
    }

    fun terminalLanguage(context: Context): Flow<String> = context.applicationContext.terminalDataStore.data
        .catch { error ->
            if (error is IOException) {
                Log.e("TerminalPreferences", "Cannot read terminal language", error)
                emit(emptyPreferences())
            } else throw error
        }
        .map { values -> values[languageKey]?.takeIf { it in languages } ?: "system" }

    fun saveTerminalLanguage(context: Context, choice: String) {
        if (choice in languages) languageWrites.trySend(context.applicationContext to choice)
    }

    fun headerExpanded(context: Context): Flow<Boolean?> = context.applicationContext.terminalDataStore.data
        .catch { error ->
            if (error is IOException) {
                Log.e("TerminalPreferences", "Cannot read header state", error)
                emit(emptyPreferences())
            } else throw error
        }
        .map { values -> values[headerExpandedKey] ?: false }

    fun saveHeaderExpanded(context: Context, expanded: Boolean) {
        headerWrites.trySend(context.applicationContext to expanded)
    }

    fun history(context: Context): Flow<List<String>?> = context.applicationContext.terminalDataStore.data
        .catch { error ->
            if (error is IOException) {
                Log.e("TerminalPreferences", "Cannot read command history", error)
                TerminalHub.onWrite("\n" + context.getString(R.string.pref_history_read_failed) + "\n")
                emit(emptyPreferences())
            } else throw error
        }
        .map { values -> decodeHistory(values[commandHistory]) }

    fun addHistory(context: Context, line: String) {
        if (line.isNotBlank()) historyWrites.trySend(context.applicationContext to line)
    }

    private fun decodeHistory(raw: String?): List<String> {
        if (raw == null) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in (array.length() - MAX_HISTORY).coerceAtLeast(0) until array.length()) {
                    val line = array.opt(index) as? String
                    if (line != null && line.isNotBlank()) add(line)
                }
            }
        } catch (error: JSONException) {
            Log.e("TerminalPreferences", "Invalid command history JSON", error)
            emptyList()
        }
    }
}
