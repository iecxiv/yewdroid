package iecxiv.yewdroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val BG = Color(0xFF0C0C0C)
private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val MUTED = Color(0xFF7F848E)
private val ERROR = Color(0xFFE06C75)

/** One entry of the download queue; [percent] is -1 when the stage has no measurable progress. */
data class DownloadItem(
    val id: Int,
    val title: String,
    val kind: String,
    val label: String,
    val status: String,
    val stage: String,
    val percent: Int,
    val message: String
)

data class DownloadsState(val items: List<DownloadItem> = emptyList())

/** A quality the user can pick: [key] is the audio format id or the video height. */
data class FormatOption(val key: String, val label: String, val size: Long)

data class FormatsState(
    val audio: List<FormatOption> = emptyList(),
    val video: List<FormatOption> = emptyList(),
    val error: String = ""
)

fun parseDownloadsState(raw: String): DownloadsState {
    val array = JSONObject(raw).optJSONArray("items") ?: JSONArray()
    return DownloadsState(List(array.length()) { index ->
        val item = array.getJSONObject(index)
        DownloadItem(
            id = item.optInt("id"),
            title = item.optString("title"),
            kind = item.optString("kind"),
            label = item.optString("label"),
            status = item.optString("status"),
            stage = item.optString("stage"),
            percent = item.optInt("percent"),
            message = item.optString("message")
        )
    })
}

private fun parseOptions(array: JSONArray?): List<FormatOption> =
    List(array?.length() ?: 0) { index ->
        val item = array!!.getJSONObject(index)
        FormatOption(item.optString("key"), item.optString("label"), item.optLong("size"))
    }

fun parseFormatsState(raw: String): FormatsState {
    val json = JSONObject(raw)
    return FormatsState(
        audio = parseOptions(json.optJSONArray("audio")),
        video = parseOptions(json.optJSONArray("video")),
        error = json.optString("error")
    )
}

/** Bridge to android_downloads.py; Python pushes queue changes through [update]. */
object DownloadHub {
    private val _state = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun update(json: String) {
        runCatching { _state.value = parseDownloadsState(json) }
    }

    private fun module() = Python.getInstance().getModule("android_downloads")

    suspend fun loadFormats(ytid: String): FormatsState = withContext(Dispatchers.IO) {
        runCatching { parseFormatsState(module().callAttr("formats", ytid).toString()) }
            .getOrElse { FormatsState(error = it.message.orEmpty().ifEmpty { "error" }) }
    }

    fun enqueue(ytid: String, title: String, kind: String, key: String, label: String) {
        scope.launch { runCatching { module().callAttr("enqueue", ytid, title, kind, key, label) } }
    }

    fun cancel(id: Int) {
        scope.launch { runCatching { module().callAttr("cancel", id) } }
    }

    fun clearFinished() {
        scope.launch { runCatching { module().callAttr("clear_finished") } }
    }

    fun refresh() {
        scope.launch { runCatching { module().callAttr("refresh") } }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes >= 1_000_000_000L -> "~%.1f GB".format(bytes / 1_000_000_000.0)
    else -> "~%.0f MB".format(bytes / 1_000_000.0)
}

/** Dialog that lists the real formats of a video and queues the chosen download. */
@Composable
internal fun FormatDialog(item: ClientItem, kind: String, onQueued: () -> Unit, onDismiss: () -> Unit) {
    val formats by produceState<FormatsState?>(null, item.id) { value = DownloadHub.loadFormats(item.id) }
    val loaded = formats
    val options = loaded?.let { if (kind == "audio") it.audio else it.video }.orEmpty()
    var selected by remember { mutableStateOf<String?>(null) }
    val current = selected ?: options.firstOrNull()?.key
    val noOptions = loaded != null && options.isEmpty()
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    val row = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = FG)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B1B),
        title = {
            Text(
                stringResource(if (kind == "audio") R.string.download_choose_audio else R.string.download_choose_video),
                style = row.copy(fontSize = 16.sp)
            )
        },
        text = {
            Column {
                Text(item.title, style = small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                when {
                    loaded == null -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = ACCENT, trackColor = Color(0xFF2A2A2A))
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.download_loading), style = small)
                    }
                    noOptions -> Text(stringResource(R.string.download_formats_failed), style = row)
                    else -> Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        options.forEach { option ->
                            Row(
                                Modifier.fillMaxWidth().clickable { selected = option.key }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = option.key == current,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = ACCENT, unselectedColor = MUTED)
                                )
                                Text(option.label, style = row, modifier = Modifier.padding(start = 10.dp).weight(1f))
                                Text(formatSize(option.size), style = small)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = loaded != null,
                colors = ButtonDefaults.textButtonColors(contentColor = ACCENT),
                onClick = {
                    val option = options.firstOrNull { it.key == current }
                    DownloadHub.enqueue(item.id, item.title, kind, option?.key.orEmpty(), option?.label.orEmpty())
                    onQueued()
                }
            ) { Text(stringResource(if (noOptions) R.string.download_best else R.string.download_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MUTED)) {
                Text(stringResource(R.string.download_cancel))
            }
        }
    )
}

/** Tab with one row per download: stage, progress, result and a cancel button. */
@Composable
internal fun DownloadsScreen(modifier: Modifier = Modifier) {
    val state by DownloadHub.state.collectAsState()
    LaunchedEffect(Unit) { DownloadHub.refresh() }
    val hasFinished = state.items.any { it.status == "done" || it.status == "error" || it.status == "cancelled" }
    Column(modifier) {
        if (hasFinished) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { DownloadHub.clearFinished() },
                    colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                ) { Text(stringResource(R.string.downloads_clear), fontSize = 12.sp) }
            }
        }
        if (state.items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.downloads_empty),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED, textAlign = TextAlign.Center)
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.items.reversed(), key = { it.id }) { DownloadRow(it) }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem) {
    val title = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FG)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MUTED)
    val kind = stringResource(if (item.kind == "audio") R.string.download_kind_audio else R.string.download_kind_video)
    val head = listOf(kind, item.label).filter { it.isNotEmpty() }.joinToString(" \u00b7 ")
    val running = item.status == "running"
    val stageText = when (item.stage) {
        "audio" -> stringResource(R.string.download_stage_audio)
        "video" -> stringResource(R.string.download_stage_video)
        "merging" -> stringResource(R.string.download_stage_merging)
        "exporting" -> stringResource(R.string.download_stage_exporting)
        else -> ""
    }
    val status = when (item.status) {
        "queued" -> stringResource(R.string.download_queued)
        "running" -> if (item.percent in 0..100 && (item.stage == "audio" || item.stage == "video")) {
            "$stageText ${item.percent}%"
        } else stageText
        "done" -> stringResource(R.string.download_done, item.message)
        "cancelled" -> stringResource(R.string.download_cancelled)
        else -> stringResource(R.string.download_failed, item.message)
    }
    val cancellable = item.status == "queued" ||
        (running && (item.stage == "audio" || item.stage == "video" || item.stage.isEmpty()))
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(head, style = small, maxLines = 1)
            }
            if (cancellable) {
                TextButton(
                    onClick = { DownloadHub.cancel(item.id) },
                    colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                ) { Text(stringResource(R.string.download_cancel), fontSize = 12.sp) }
            }
        }
        if (running) {
            Spacer(Modifier.height(4.dp))
            if (item.percent in 0..100 && (item.stage == "audio" || item.stage == "video")) {
                LinearProgressIndicator(
                    progress = { item.percent / 100f },
                    modifier = Modifier.fillMaxWidth(), color = ACCENT, trackColor = Color(0xFF2A2A2A)
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = ACCENT, trackColor = Color(0xFF2A2A2A))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            status,
            style = small.copy(color = if (item.status == "error") ERROR else if (item.status == "done") ACCENT else MUTED),
            maxLines = 4, overflow = TextOverflow.Ellipsis
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1B1B1B)))
}
