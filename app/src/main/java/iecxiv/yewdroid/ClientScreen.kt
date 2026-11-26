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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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

/** Tabs of the native client. */
private enum class Section(val label: Int) {
    SEARCH(R.string.client_tab_search),
    PLAYLISTS(R.string.client_tab_playlists),
    HISTORY(R.string.client_tab_history),
    DOWNLOADS(R.string.client_tab_downloads)
}

/**
 * yewtube selection for result n: "n-" plays n and queues the rest of the page, "n" plays only n.
 */
private fun selection(n: Int, queueRest: Boolean) = if (queueRest) "$n-" else "$n"

/** Menu actions before the downloads: label to the yewtube command for result number n. */
private val PLAY_ACTIONS: List<Pair<Int, (Int, Boolean) -> String>> = listOf(
    R.string.client_play_audio to { n, queueRest -> "${selection(n, queueRest)} -a" },
    R.string.client_play_video to { n, queueRest -> "${selection(n, queueRest)} -w" }
)

/** Menu actions after "Add to playlist". */
private val OTHER_ACTIONS: List<Pair<Int, (Int, Boolean) -> String>> = listOf(
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
    var section by rememberSaveable { mutableStateOf(Section.SEARCH) }
    var focused by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<Int?>(null) }
    var downloading by remember { mutableStateOf<Pair<ClientItem, String>?>(null) }
    var clearingHistory by remember { mutableStateOf(false) }
    val autoQueueFlow = remember(ctx.applicationContext) { TerminalPreferences.clientAutoQueue(ctx.applicationContext) }
    val savedAutoQueue by autoQueueFlow.collectAsState(initial = null)
    var selectedAutoQueue by remember { mutableStateOf<Boolean?>(null) }
    val autoQueue = selectedAutoQueue ?: savedAutoQueue
    val searchFlow = remember(ctx.applicationContext) { TerminalPreferences.searchHistory(ctx.applicationContext) }
    val searches by searchFlow.collectAsState(initial = emptyList())
    val suggestions = remember(searches, query) {
        val typed = query.trim()
        if (typed.isEmpty()) searches
        else searches.filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
    }
    val showHistory = section == Section.SEARCH && focused && suggestions.isNotEmpty()
    // While the native client is shown, playback runs in the background so commands keep working.
    DisposableEffect(Unit) {
        TerminalHub.setBackgroundPlayback(true)
        onDispose { TerminalHub.setBackgroundPlayback(false) }
    }
    LaunchedEffect(Unit) {
        TerminalHub.ensureStarted(ctx.applicationContext, CLIENT_COLS, CLIENT_ROWS)
    }

    fun search() {
        val text = query.trim().trimStart('.', '/').trim()
        if (text.isNotEmpty()) {
            focus.clearFocus()
            TerminalPreferences.addSearch(ctx, text)
            TerminalHub.runCommand("/$text")
        }
    }

    fun select(target: Section) {
        focus.clearFocus()
        focused = false
        if (target == Section.HISTORY && section != Section.HISTORY) TerminalHub.runCommand("history")
        section = target
    }

    val searchStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.White)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Section.values().forEach { tab ->
                Text(
                    stringResource(tab.label),
                    style = small.copy(
                        color = if (section == tab) ACCENT else MUTED,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).clickable { select(tab) }.padding(vertical = 10.dp)
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)))
        if (section == Section.PLAYLISTS) {
            PlaylistsScreen(
                onOpened = { section = Section.SEARCH },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else if (section == Section.DOWNLOADS) {
            DownloadsScreen(Modifier.weight(1f).fillMaxWidth())
        } else {
            if (section == Section.SEARCH) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                            .onFocusChanged { focused = it.hasFocus }
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
            }
            if (autoQueue != null) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            val value = !autoQueue
                            selectedAutoQueue = value
                            TerminalPreferences.saveClientAutoQueue(ctx, value)
                        }
                        .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
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
                        modifier = Modifier.padding(start = 10.dp).weight(1f)
                    )
                    if (section == Section.SEARCH && state.mode == "normal" && state.items.isNotEmpty()) {
                        TextButton(
                            onClick = { saving = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                        ) { Text(stringResource(R.string.client_save_playlist), fontSize = 12.sp) }
                    }
                }
            }
            if (section == Section.HISTORY) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.client_tab_history), style = small.copy(color = ACCENT), modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { clearingHistory = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                    ) { Text(stringResource(R.string.history_clear), fontSize = 12.sp) }
                }
            }
            if (state.busy || !state.ready) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = ACCENT, trackColor = Color(0xFF2A2A2A))
            }
            if (state.message.isNotEmpty()) {
                Text(state.message, style = small.copy(color = ACCENT), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            if (showHistory) {
                SearchHistoryPanel(
                    entries = suggestions,
                    onPick = { entry ->
                        query = entry
                        search()
                    },
                    onRemove = { TerminalPreferences.removeSearch(ctx, it) },
                    onClear = { TerminalPreferences.clearSearches(ctx) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else if (state.ready && state.items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (section == Section.HISTORY) R.string.history_empty else R.string.client_empty),
                        style = small.copy(textAlign = TextAlign.Center)
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(state.items, key = { "${it.n}-${it.id}" }) { item ->
                        ResultRow(
                            item,
                            playlist = state.mode == "ytpl",
                            queueRest = autoQueue ?: true,
                            onAdd = { adding = it },
                            onDownload = { kind -> downloading = item to kind }
                        )
                    }
                }
            }
            if (!showHistory && state.pages > 1) {
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
    if (saving) {
        NameDialog(
            title = stringResource(R.string.playlist_save_title),
            confirmLabel = stringResource(R.string.playlist_save_confirm),
            onConfirm = { name ->
                TerminalHub.runCommand("save $name")
                saving = false
            },
            onDismiss = { saving = false }
        )
    }
    adding?.let { number ->
        AddToPlaylistDialog(
            onPick = { name ->
                TerminalHub.runCommand("add $number $name")
                adding = null
            },
            onDismiss = { adding = null }
        )
    }
    downloading?.let { (item, kind) ->
        FormatDialog(
            ytid = item.id,
            title = item.title,
            kind = kind,
            onStarted = {
                downloading = null
                select(Section.DOWNLOADS)
            },
            onDismiss = { downloading = null }
        )
    }
    if (clearingHistory) {
        ConfirmDialog(
            title = stringResource(R.string.history_clear_confirm),
            confirmLabel = stringResource(R.string.history_clear),
            onConfirm = {
                TerminalHub.runCommand("history clear")
                clearingHistory = false
            },
            onDismiss = { clearingHistory = false }
        )
    }
}

/** Recent searches shown in place of the results while the search box has focus. */
@Composable
private fun SearchHistoryPanel(
    entries: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.client_history_title),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ACCENT),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClear, colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)) {
                Text(stringResource(R.string.client_history_clear), fontSize = 12.sp)
            }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(entries, key = { it }) { entry ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp))
                    Text(
                        entry, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = FG),
                        modifier = Modifier.weight(1f).padding(start = 10.dp, top = 10.dp, bottom = 10.dp)
                    )
                    IconButton(onClick = { onRemove(entry) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.client_history_remove),
                            tint = MUTED
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    item: ClientItem,
    playlist: Boolean,
    queueRest: Boolean,
    onAdd: (Int) -> Unit,
    onDownload: (String) -> Unit
) {
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
                    PLAY_ACTIONS.forEach { (label, command) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                menu = false
                                TerminalHub.runCommand(command(item.n, queueRest))
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.client_download_audio)) },
                        onClick = {
                            menu = false
                            onDownload("audio")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.client_download_video)) },
                        onClick = {
                            menu = false
                            onDownload("video")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.client_add)) },
                        onClick = {
                            menu = false
                            onAdd(item.n)
                        }
                    )
                    OTHER_ACTIONS.forEach { (label, command) ->
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
