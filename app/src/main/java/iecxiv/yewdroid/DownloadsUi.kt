package iecxiv.yewdroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import org.json.JSONObject
import java.util.Locale

private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val MUTED = Color(0xFF7F848E)
private val ERROR = Color(0xFFE06C75)

/** One entry of the download queue, as reported by android_downloads.py. */
data class DownloadJob(
    val id: Int,
    val title: String,
    val kind: String,
    val label: String,
    val status: String,
    val stage: String,
    val progress: Float,
    val message: String
)

data class DownloadsState(val items: List<DownloadJob> = emptyList())

/** A quality the user can pick: [id] is a format id (audio) or a maximum height (video). */
data class FormatOption(val id: String, val label: String, val size: Long, val recommended: Boolean)

data class FormatList(val audio: List<FormatOption>, val video: List<FormatOption>)

fun parseDownloadsState(json: String): DownloadsState {
    val array = JSONObject(json).getJSONArray("items")
    return DownloadsState(List(array.length()) { index ->
        val item = array.getJSONObject(index)
        DownloadJob(
            id = item.getInt("id"),
            title = item.optString("title"),
            kind = item.optString("kind"),
            label = item.optString("label"),
            status = item.optString("status"),
            stage = item.optString("stage"),
            progress = item.optDouble("progress", 0.0).toFloat(),
            message = item.optString("message")
        )
    })
}

fun parseFormatList(json: String): FormatList {
    val root = JSONObject(json)
    fun options(key: String): List<FormatOption> {
        val array = root.getJSONArray(key)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            FormatOption(
                id = item.getString("id"),
                label = item.getString("label"),
                size = item.optLong("size", 0L),
                recommended = item.optBoolean("recommended", false)
            )
        }
    }
    return FormatList(options("audio"), options("video"))
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1e3)
}

@Composable
private fun stageText(stage: String): String = when (stage) {
    "video" -> stringResource(R.string.downloads_stage_video)
    "audio" -> stringResource(R.string.downloads_stage_audio)
    "merge" -> stringResource(R.string.downloads_stage_merge)
    "export" -> stringResource(R.string.downloads_stage_export)
    else -> stringResource(R.string.downloads_status_queued)
}

/** The Downloads tab: the queue with progress, and cancel / clear buttons. */
@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    val state by TerminalHub.downloads.collectAsState()
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Column(modifier) {
        if (state.items.any { it.status == "done" || it.status == "error" || it.status == "cancelled" }) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { TerminalHub.clearFinishedDownloads() },
                    colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                ) { Text(stringResource(R.string.downloads_clear), fontSize = 12.sp) }
            }
        }
        if (state.items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.downloads_empty), style = small.copy(textAlign = TextAlign.Center))
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.items.asReversed(), key = { it.id }) { job -> DownloadRow(job) }
            }
        }
    }
}

@Composable
private fun DownloadRow(job: DownloadJob) {
    val titleStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FG)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MUTED)
    val kind = stringResource(if (job.kind == "video") R.string.downloads_kind_video else R.string.downloads_kind_audio)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (job.label.isNotEmpty()) "$kind \u00b7 ${job.label}" else kind,
                    style = small
                )
            }
            if (job.status == "queued" || job.status == "running") {
                IconButton(onClick = { TerminalHub.cancelDownload(job.id) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.downloads_cancel), tint = FG)
                }
            }
        }
        when (job.status) {
            "running" -> {
                LinearProgressIndicator(
                    progress = (job.progress / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = ACCENT,
                    trackColor = Color(0xFF2A2A2A)
                )
                Text(
                    "${stageText(job.stage)} \u00b7 ${job.progress.toInt()}%",
                    style = small.copy(color = ACCENT),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            "queued" -> Text(stringResource(R.string.downloads_status_queued), style = small)
            "done" -> Text(
                stringResource(R.string.downloads_done),
                style = small.copy(color = ACCENT)
            )
            "cancelled" -> Text(stringResource(R.string.downloads_cancelled), style = small)
            else -> Text(
                stringResource(R.string.downloads_error) +
                    if (job.message.isNotEmpty()) ": ${job.message}" else "",
                style = small.copy(color = ERROR),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Lists the audio or video qualities of a video and queues the download of the one picked. */
@Composable
fun FormatDialog(
    ytid: String,
    title: String,
    kind: String,
    onStarted: () -> Unit,
    onDismiss: () -> Unit
) {
    var result by remember(ytid) { mutableStateOf<Result<FormatList>?>(null) }
    LaunchedEffect(ytid) { result = TerminalHub.loadFormats(ytid) }
    val audio = kind == "audio"
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    val option = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = FG)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B1B),
        titleContentColor = FG,
        textContentColor = FG,
        title = {
            Text(stringResource(if (audio) R.string.downloads_format_title_audio else R.string.downloads_format_title_video))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(title, style = small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                val loaded = result
                when {
                    loaded == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = ACCENT, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.downloads_format_loading), style = small)
                    }
                    loaded.isFailure -> Text(
                        stringResource(
                            R.string.downloads_format_error,
                            loaded.exceptionOrNull()?.message.orEmpty()
                                .lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                        ),
                        style = small.copy(color = ERROR)
                    )
                    else -> {
                        val list = loaded.getOrThrow().let { if (audio) it.audio else it.video }
                        if (list.isEmpty()) {
                            Text(stringResource(R.string.downloads_format_none), style = small)
                        } else {
                            list.asReversed().forEach { entry ->
                                val detail = buildString {
                                    if (entry.size > 0) append(" \u00b7 ~").append(formatSize(entry.size))
                                }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            TerminalHub.enqueueDownload(ytid, title, kind, entry.id, entry.label)
                                            onStarted()
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(entry.label + detail, style = option, modifier = Modifier.weight(1f))
                                    if (entry.recommended) {
                                        Text(
                                            stringResource(R.string.downloads_recommended),
                                            style = small.copy(color = ACCENT),
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)) {
                Text(stringResource(R.string.downloads_cancel))
            }
        }
    )
}
