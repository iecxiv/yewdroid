package iecxiv.yewdroid

import android.widget.Toast
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val MUTED = Color(0xFF7F848E)
private val ERROR = Color(0xFFE06C75)

/** One entry of the download queue, as reported by yt_downloads.py. */
data class DownloadJob(
    val id: Int,
    val title: String,
    val kind: String,
    val label: String,
    val state: String,
    val stage: String,
    val percent: Float,
    val detail: String
)

/** A format the user can pick before downloading. */
data class FormatOption(val id: String, val label: String, val size: Long)

data class DownloadFormats(val audio: List<FormatOption>, val video: List<FormatOption>)

internal fun parseDownloads(json: String): List<DownloadJob> {
    val array = JSONObject(json).getJSONArray("items")
    return List(array.length()) { index ->
        val o = array.getJSONObject(index)
        DownloadJob(
            id = o.getInt("id"),
            title = o.optString("title"),
            kind = o.optString("kind"),
            label = o.optString("label"),
            state = o.optString("state"),
            stage = o.optString("stage"),
            percent = o.optDouble("percent", -1.0).toFloat(),
            detail = o.optString("detail")
        )
    }
}

private fun parseOptions(array: JSONArray): List<FormatOption> = List(array.length()) { index ->
    val o = array.getJSONObject(index)
    FormatOption(o.getString("id"), o.optString("label"), o.optLong("size", 0L))
}

internal fun parseFormats(json: String): DownloadFormats {
    val root = JSONObject(json)
    return DownloadFormats(parseOptions(root.getJSONArray("audio")), parseOptions(root.getJSONArray("video")))
}

private fun formatSize(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

/** Dialog that lists the available formats of a video and queues the chosen download. */
@Composable
fun FormatDialog(ytid: String, title: String, kind: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var result by remember { mutableStateOf<Result<DownloadFormats>?>(null) }
    var selected by remember { mutableStateOf(0) }
    LaunchedEffect(ytid) { result = TerminalHub.loadFormats(ytid) }
    val loaded = result
    val options = loaded?.getOrNull()?.let { if (kind == "audio") it.audio else it.video }.orEmpty()
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = FG)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B1B),
        titleContentColor = FG,
        textContentColor = FG,
        title = { Text(stringResource(R.string.download_formats_title), fontSize = 16.sp) },
        text = {
            Column {
                Text(title, style = small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                when {
                    loaded == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = ACCENT, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.download_formats_loading), style = body)
                    }
                    loaded.isFailure -> Text(
                        stringResource(R.string.download_formats_failed, loaded.exceptionOrNull()?.message.orEmpty().lineSequence().firstOrNull().orEmpty()),
                        style = body.copy(color = ERROR)
                    )
                    options.isEmpty() -> Text(stringResource(R.string.download_formats_empty), style = body)
                    else -> Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        options.forEachIndexed { index, option ->
                            Row(
                                Modifier.fillMaxWidth().clickable { selected = index }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected == index,
                                    onClick = { selected = index },
                                    colors = RadioButtonDefaults.colors(selectedColor = ACCENT, unselectedColor = MUTED)
                                )
                                Text(
                                    option.label + if (option.size > 0) " \u00b7 " + formatSize(option.size) else "",
                                    style = body
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = options.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = ACCENT),
                onClick = {
                    val option = options[selected.coerceIn(0, options.lastIndex)]
                    TerminalHub.enqueueDownload(ytid, title, kind, option.id, option.label)
                    Toast.makeText(ctx, R.string.download_queued, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) { Text(stringResource(R.string.download_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = FG)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

/** The Downloads tab: progress and result of every queued download. */
@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    val jobs by TerminalHub.downloads.collectAsState()
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Column(modifier) {
        if (jobs.any { it.state != "queued" && it.state != "running" }) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { TerminalHub.clearFinishedDownloads() },
                    colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                ) { Text(stringResource(R.string.downloads_clear), fontSize = 12.sp) }
            }
        }
        if (jobs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.downloads_empty), style = small.copy(textAlign = TextAlign.Center))
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(jobs.asReversed(), key = { it.id }) { job -> DownloadRow(job) }
            }
        }
    }
}

@Composable
private fun DownloadRow(job: DownloadJob) {
    val titleStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FG)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MUTED)
    val active = job.state == "queued" || job.state == "running"
    val status = stringResource(
        when (job.state) {
            "queued" -> R.string.download_state_queued
            "done" -> R.string.download_state_done
            "error" -> R.string.download_state_error
            "cancelled" -> R.string.download_state_cancelled
            else -> when (job.stage) {
                "video" -> R.string.download_stage_video
                "audio" -> R.string.download_stage_audio
                "merge" -> R.string.download_stage_merge
                "export" -> R.string.download_stage_export
                else -> R.string.download_stage_starting
            }
        }
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(job.label, status).filter { it.isNotEmpty() }.joinToString(" \u00b7 "),
                    style = small.copy(color = if (job.state == "error") ERROR else if (job.state == "done") ACCENT else MUTED)
                )
            }
            if (active) {
                IconButton(onClick = { TerminalHub.cancelDownload(job.id) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.download_cancel), tint = FG)
                }
            }
        }
        if (job.state == "running") {
            Spacer(Modifier.height(4.dp))
            if (job.percent >= 0f) {
                LinearProgressIndicator(
                    progress = (job.percent / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    color = ACCENT,
                    trackColor = Color(0xFF2A2A2A)
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = ACCENT, trackColor = Color(0xFF2A2A2A))
            }
        }
        if (job.detail.isNotEmpty() && (job.state == "done" || job.state == "error")) {
            Text(job.detail, style = small, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1F1F1F)))
}
