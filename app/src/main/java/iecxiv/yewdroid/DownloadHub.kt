package iecxiv.yewdroid

import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Download queue state for the native client; the work itself runs in Python (android_downloads). */
object DownloadHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _downloads = MutableStateFlow(DownloadsState())
    val downloads: StateFlow<DownloadsState> = _downloads
    private val _formats = MutableStateFlow<FormatsState?>(null)
    val formats: StateFlow<FormatsState?> = _formats

    private fun module() = Python.getInstance().getModule("android_downloads")

    /** Called from Python with the JSON of android_downloads.snapshot(). */
    @JvmStatic
    fun update(json: String) {
        runCatching { _downloads.value = parseDownloadsState(json) }
    }

    /** Opens the format picker for a video and loads the options yt-dlp offers for it. */
    fun loadFormats(ytid: String, title: String, kind: String) {
        _formats.value = FormatsState(ytid, title, kind, loading = true)
        scope.launch {
            val result = runCatching {
                parseFormats(module().callAttr("formats", ytid).toString(), ytid, title, kind)
            }.getOrElse { FormatsState(ytid, title, kind, error = it.message.orEmpty()) }
            val current = _formats.value
            // Ignore the answer if the picker was dismissed or replaced meanwhile.
            if (current != null && current.ytid == ytid && current.kind == kind) _formats.value = result
        }
    }

    fun dismissFormats() {
        _formats.value = null
    }

    fun start(state: FormatsState, option: FormatOption) {
        _formats.value = null
        scope.launch {
            runCatching {
                module().callAttr("enqueue", state.ytid, state.title, state.kind, option.id, option.label)
            }
        }
    }

    fun cancel(id: Int) {
        scope.launch { runCatching { module().callAttr("cancel", id) } }
    }

    fun clearFinished() {
        scope.launch { runCatching { module().callAttr("clear_finished") } }
    }
}
