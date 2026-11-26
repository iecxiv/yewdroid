package iecxiv.yewdroid

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val BG = Color(0xFF0C0C0C)
private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val MUTED = Color(0xFF7F848E)

/** Virtual screen the yewtube session uses while the native client is shown (sets the page size). */
private const val CLIENT_COLS = 100
private const val CLIENT_ROWS = 24

/**
 * yewtube selection for result n: "n-" plays n and queues the rest of the page, "n" plays only n.
 */
private fun selection(n: Int, queueRest: Boolean) = if (queueRest) "$n-" else "$n"

/** Per-item actions: label to the yewtube command for result number n (queueRest = auto-queue on). */
private val ITEM_ACTIONS: List<Pair<Int, (Int, Boolean) -> String>> = listOf(
    R.string.client_play_audio to { n, queueRest -> "${selection(n, queueRest)} -a" },
    R.string.client_play_video to { n, queueRest -> "${selection(n, queueRest)} -w" },
    R.string.client_download_audio to { n, _ -> "da $n" },
    R.string.client_download_video to { n, _ -> "dv $n" },
    R.string.client_add to { n, _ -> "add $n" },
    R.string.client_share to { n, _ -> "share $n" },
    R.string.client_open_with to { n, _ -> "openwith $n" },
    R.string.client_more_from to { n, _ -> "u $n" }
)

private val thumbnails = LruCache<String, ImageBitmap>(120)

@Composable
fun ClientScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val state by TerminalHub.client.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val autoQueueFlow = remember(ctx.applicationContext) { TerminalPreferences.clientAutoQueue(ctx.applicationContext) }
    val savedAutoQueue by autoQueueFlow.collectAsState(initial = null)
    var selectedAutoQueue by remember { mutableStateOf<Boolean?>(null) }
    val autoQueue = selectedAutoQueue ?: savedAutoQueue
    LaunchedEffect(Unit) {
        TerminalHub.ensureStarted(ctx.applicationContext, CLIENT_COLS, CLIENT_ROWS)
    }

    fun search() {
        val text = query.trim().trimStart('.', '/').trim()
        if (text.isNotEmpty()) {
            focus.clearFocus()
            TerminalHub.runCommand("/$text")
        }
    }

    val searchStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.White)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                    .border(1.dp, ACCENT, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                singleLine = true,
                textStyle = searchStyle,
                cursorBrush = SolidColor(ACCENT),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (query.isEmpty()) Text(stringResource(R.string.client_search_hint), style = small)
                        inner()
                    }
                }
            )
            IconButton(onClick = { search() }) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.client_search), tint = FG)
            }
        }
        if (autoQueue != null) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        val value = !autoQueue
                        selectedAutoQueue = value
                        TerminalPreferences.saveClientAutoQueue(ctx, value)
                    }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = autoQueue,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BG,
                        checkedTrackColor = ACCENT,
                        checkedBorderColor = ACCENT,
                        uncheckedThumbColor = FG,
                        uncheckedTrackColor = Color(0xFF2A2A2A),
                        uncheckedBorderColor = Color(0xFF555555)
                    )
                )
                Text(
                    stringResource(R.string.client_autoqueue),
                    style = small.copy(color = FG),
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
        if (state.busy || !state.ready) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = ACCENT, trackColor = Color(0xFF2A2A2A))
        }
        if (state.message.isNotEmpty()) {
            Text(state.message, style = small.copy(color = ACCENT), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        if (state.ready && state.items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.client_empty), style = small.copy(textAlign = TextAlign.Center))
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.items, key = { "${it.n}-${it.id}" }) { item ->
                    ResultRow(item, playlist = state.mode == "ytpl", queueRest = autoQueue ?: true)
                }
            }
        }
        if (state.pages > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val hasPrevious = state.page > 0
                val hasNext = state.page + 1 < state.pages
                IconButton(onClick = { TerminalHub.runCommand("p") }, enabled = hasPrevious) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.client_prev),
                        tint = if (hasPrevious) FG else MUTED
                    )
                }
                Text(
                    stringResource(R.string.client_page, state.page + 1, state.pages),
                    style = small.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { TerminalHub.runCommand("n") }, enabled = hasNext) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.client_next),
                        tint = if (hasNext) FG else MUTED
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(item: ClientItem, playlist: Boolean, queueRest: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val titleStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FG)
    val subtitleStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MUTED)
    val subtitle = if (playlist) {
        listOf(item.author, if (item.count.isNotEmpty()) stringResource(R.string.client_playlist_count, item.count) else "")
    } else {
        listOf(item.author, item.views, item.date)
    }.filter { it.isNotEmpty() }.joinToString(" \u00b7 ")
    Row(
        Modifier.fillMaxWidth()
            .clickable {
                // Playlists open; videos play (and queue the rest of the page when auto-queue is on).
                TerminalHub.runCommand(if (playlist) item.n.toString() else selection(item.n, queueRest))
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!playlist) {
            Box(Modifier.width(120.dp).aspectRatio(16f / 9f)) {
                Thumbnail(item.id, Modifier.fillMaxSize())
                if (item.lengthSec > 0) {
                    Text(
                        formatLength(item.lengthSec),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White),
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color(0xCC000000))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = subtitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!playlist) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.client_more_options), tint = FG)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ITEM_ACTIONS.forEach { (label, command) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                menu = false
                                TerminalHub.runCommand(command(item.n, queueRest))
                            }
                        )
                    }
                }
            }
        }
    }
}

/** YouTube thumbnail with an in-memory cache; also used by the player. */
@Composable
internal fun Thumbnail(id: String, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(thumbnails.get(id), id) {
        if (value == null && id.isNotEmpty()) {
            value = withContext(Dispatchers.IO) { fetchThumbnail(id) }?.also { thumbnails.put(id, it) }
        }
    }
    Box(modifier.background(Color(0xFF1B1B1B))) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun fetchThumbnail(id: String): ImageBitmap? = try {
    val connection = (URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
    }
    try {
        connection.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    } finally {
        connection.disconnect()
    }
} catch (_: Exception) {
    null
}

private fun formatLength(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}
