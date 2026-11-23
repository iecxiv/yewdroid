package iecxiv.yewtube

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.terminalDataStore by preferencesDataStore(name = "terminal_preferences")

object TerminalPreferences {
    private val fontSize = floatPreferencesKey("font_size_sp")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<Pair<Context, Float>>(Channel.CONFLATED)

    init {
        scope.launch {
            for ((context, size) in writes) {
                try {
                    context.terminalDataStore.edit { it[fontSize] = size }
                } catch (error: IOException) {
                    Log.e("TerminalPreferences", "Cannot save zoom", error)
                    TerminalHub.onWrite("\n[ajustes] No se pudo guardar el zoom.\n")
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
}
