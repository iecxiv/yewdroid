package iecxiv.yewdroid

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import java.util.Locale

private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val MUTED = Color(0xFF7F848E)
private val ERROR = Color(0xFFE06C75)

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes >= 1L shl 30 -> String.format(Locale.getDefault(), "~%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "~%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.getDefault(), "~%d KB", bytes / 1024)
}

/** Dialog to pick the quality of an audio or video download. */
@Composable
fun FormatsDialog(state: FormatsState, onPick: (FormatOption) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = FG)
    // YouTube's "Sign in to confirm you're not a bot" answer: the cookies setting may help.
    val signInCheck = state.error.contains("not a bot", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B1B),
        title = {
            Text(
                stringResource(if (state.kind == "audio") R.string.dl_format_audio_title else R.string.dl_format_video_title),
                color = FG, fontSize = 16.sp
            )
        },
        text = {
            Column {
                Text(state.title, style = small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                when {
                    state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = ACCENT, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.dl_format_loading), style = body)
                    }
                    state.error.isNotEmpty() -> Column {
                        Text(
                            stringResource(R.string.dl_format_error, state.error),
                            style = body.copy(color = ERROR)
                        )
                        if (signInCheck) {
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.cookies_hint), style = small)
                        }
                    }
                    state.options.isEmpty() -> Text(stringResource(R.string.dl_format_none), style = body)
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(state.options, key = { it.id }) { option ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(option) }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(option.label, style = body)
                                    if (option.recommended) {
                                        Text(stringResource(R.string.dl_format_recommended), style = small.copy(color = ACCENT))
                                    }
                                }
                                Text(formatSize(option.sizeBytes), style = small)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (signInCheck) {
                TextButton(
                    onClick = {
                        onDismiss()
                        ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                ) { Text(stringResource(R.string.cookies_open_settings)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)) {
                Text(stringResource(R.string.dl_cancel))
            }
        }
    )
}

/** The Downloads tab: one row per download with progress, status and a cancel button. */
@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    val state by DownloadHub.downloads.collectAsState()
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Column(modifier) {
        if (state.items.any { it.finished }) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { DownloadHub.clearFinished() },
                    colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                ) { Text(stringResource(R.string.dl_clear), fontSize = 12.sp) }
            }
        }
        if (state.items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.dl_empty), style = small.copy(textAlign = TextAlign.Center))
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.items.asReversed(), key = { it.id }) { DownloadRow(it) }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem) {
    val title = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FG)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MUTED)
    val kind = stringResource(if (item.kind == "audio") R.string.dl_kind_audio else R.string.dl_kind_video)
    val stage = when (item.stage) {
        "download" -> stringResource(R.string.dl_stage_download)
        "video" -> stringResource(R.string.dl_stage_video)
        "audio" -> stringResource(R.string.dl_stage_audio)
        "merge" -> stringResource(R.string.dl_stage_merge)
        "export" -> stringResource(R.string.dl_stage_export)
        "cancelling" -> stringResource(R.string.dl_stage_cancelling)
        else -> ""
    }
    val status = when (item.state) {
        "queued" -> stringResource(R.string.dl_state_queued)
        "running" -> if (stage.isEmpty()) "%d%%".format(item.percent.toInt())
                     else "$stage \u00b7 %d%%".format(item.percent.toInt())
        "done" -> stringResource(R.string.dl_state_done, item.path)
        "cancelled" -> stringResource(R.string.dl_state_cancelled)
        else -> stringResource(R.string.dl_state_error, item.error)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$kind \u00b7 ${item.label}", style = small)
            if (item.state == "running") {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (item.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = ACCENT,
                    trackColor = Color(0xFF2A2A2A)
                )
            }
            Text(
                status,
                style = small.copy(color = when (item.state) {
                    "error" -> ERROR
                    "done", "running" -> ACCENT
                    else -> MUTED
                }),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (item.active) {
            IconButton(onClick = { DownloadHub.cancel(item.id) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dl_cancel_download), tint = FG)
            }
        }
    }
}
