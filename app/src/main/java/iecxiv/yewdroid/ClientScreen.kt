package iecxiv.yewdroid

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val BG = Color(0xFF0C0C0C)
private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val MUTED = Color(0xFF7F848E)

/** Virtual screen the yewtube session uses while the native client is shown (sets the page size). */
private const val CLIENT_COLS = 100
private const val CLIENT_ROWS = 24

/** Longest the old list stays hidden while waiting for the new one (ms). */
private const val STALE_TIMEOUT_MS = 8000L

/** Tabs of the native client. */
private enum class Section(val label: Int) {
    SEARCH(R.string.client_tab_search),
    PLAYLISTS(R.string.client_tab_playlists),
    HISTORY(R.string.client_tab_history),
    DOWNLOADS(R.string.dl_tab)
}

/**
 * What the client knew when its screen was last closed. The screen is rebuilt when switching to the terminal
 * and back, but the yewtube session (and the list it holds) lives on, so the screen must pick up from here.
 * The logic lives here, outside the composable, to keep that function small.
 */
private object ClientMemory {
    var section: Section? = null
    var query = ""
    var openedPlaylist: String? = null
    var resultsFrom: Section = Section.SEARCH
    var lastQuery = ""
    var searchKept = false
    var startupDone = false

    /** The tab to show: the one that was open, or else the one chosen in Settings. */
    fun initialSection(ctx: Context): Section {
        val remembered = section
        if (remembered != null) return remembered
        return when (UiPrefs.defaultTab(ctx)) {
            UiPrefs.TAB_PLAYLISTS -> Section.PLAYLISTS
            UiPrefs.TAB_HISTORY -> Section.HISTORY
            else -> Section.SEARCH
        }
    }

    /**
     * Commands typed in the terminal change the list yewtube holds without the client knowing: if any were
     * typed, what was remembered about that list is dropped. Returns whether any were typed.
     */
    fun takeTerminalTouch(): Boolean {
        val touched = TerminalHub.takeTerminalTouched()
        if (touched) {
            resultsFrom = Section.SEARCH
            searchKept = false
            openedPlaylist = null
        }
        return touched
    }

    /** Keeps the screen's state; the tab and typed text only if the app is not closing. */
    fun save(
        finishing: Boolean, currentSection: Section, currentQuery: String, currentPlaylist: String?,
        from: Section, last: String, kept: Boolean, started: Boolean
    ) {
        resultsFrom = from
        lastQuery = last
        searchKept = kept
        if (finishing) {
            section = null
            query = ""
            openedPlaylist = null
            startupDone = false
        } else {
            section = currentSection
            query = currentQuery
            openedPlaylist = currentPlaylist
            startupDone = started
        }
    }
}

private fun Context.activityFinishing(): Boolean {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.isFinishing
        current = current.baseContext
    }
    return false
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

/** Asks the Python session whether the search results are being kept aside (see android_views.py). */
private fun pythonKeepsSearch(): Boolean = runCatching {
    Python.getInstance().getModule("android_views").callAttr("has_stash").toBoolean()
}.getOrDefault(false)

/** The yewtube "order" option (the same one as in Settings), or null when it cannot be read. */
private fun loadOrder(): ConfigOption? = parseConfigState(
    Python.getInstance().getModule("android_settings").callAttr("snapshot").toString()
).options.firstOrNull { it.key == "ORDER" }

private fun saveOrder(value: String): Boolean = JSONObject(
    Python.getInstance().getModule("android_settings").callAttr("set_value", "ORDER", value).toString()
).optBoolean("ok")

@Composable
private fun orderName(value: String): String = stringResource(
    when (value) {
        "date" -> R.string.orderui_date
        "views" -> R.string.orderui_views
        "title" -> R.string.orderui_title
        else -> R.string.orderui_relevance
    }
)

@Composable
fun ClientScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val state by TerminalHub.client.collectAsState()
    val loadingTrack by TerminalHub.loadingTrack.collectAsState()
    val downloads by DownloadHub.downloads.collectAsState()
    val formats by DownloadHub.formats.collectAsState()
    val activeDownloads = downloads.items.count { it.active }
    val terminalTouched = remember { ClientMemory.takeTerminalTouch() }
    var query by rememberSaveable { mutableStateOf(ClientMemory.query) }
    // The tab chosen in Settings is the one shown when the client opens; coming back from the terminal it is
    // the tab that was open.
    var section by rememberSaveable { mutableStateOf(ClientMemory.initialSection(ctx)) }
    // Which tab produced the results yewtube currently holds (history and playlists replace the search results).
    var resultsFrom by rememberSaveable { mutableStateOf(ClientMemory.resultsFrom) }
    // The history of a startup tab set to History is loaded once the session is ready.
    var startupDone by rememberSaveable { mutableStateOf(ClientMemory.startupDone) }
    // Last search sent; repeated when the search results kept aside no longer exist (e.g. the app process restarted).
    var lastQuery by rememberSaveable { mutableStateOf(ClientMemory.lastQuery) }
    // True once the search results were put aside (viewstash) in this session.
    var searchKept by remember { mutableStateOf(ClientMemory.searchKept) }
    // Name of the saved playlist shown inside the Playlists tab, if one is open.
    var openedPlaylist by rememberSaveable { mutableStateOf(ClientMemory.openedPlaylist) }
    // The old list stays hidden until the session has taken this many snapshots (the new list is then in):
    // comparing the lists is not enough, as the new one may be identical to the old one.
    var waitSeq by remember { mutableStateOf<Int?>(null) }
    var touchedHandled by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<Int?>(null) }
    var clearingHistory by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf<ConfigOption?>(null) }
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
    val waiting = waitSeq?.let { state.seq < it } ?: false
    val hideResults = (section == Section.SEARCH && resultsFrom != Section.SEARCH) || waiting
    val shownItems = if (hideResults) emptyList() else state.items
    val shownMessage = if (hideResults) "" else state.message
    val shownPages = if (hideResults) 0 else state.pages
    // Playback runs in its own thread in every view, so nothing has to be switched here.
    DisposableEffect(Unit) {
        onDispose {
            ClientMemory.save(
                ctx.activityFinishing(), section, query, openedPlaylist,
                resultsFrom, lastQuery, searchKept, startupDone
            )
        }
    }
    LaunchedEffect(Unit) {
        TerminalHub.ensureStarted(ctx.applicationContext, CLIENT_COLS, CLIENT_ROWS)
    }
    // If the new list never arrives, show whatever there is rather than hiding it for good.
    LaunchedEffect(waitSeq) {
        if (waitSeq != null) {
            delay(STALE_TIMEOUT_MS)
            waitSeq = null
        }
    }

    fun refreshOrder() {
        scope.launch { order = withContext(Dispatchers.Default) { runCatching { loadOrder() }.getOrNull() } }
    }

    // Read the order setting once the session is ready and whenever the tab changes (Settings may have changed it).
    LaunchedEffect(state.ready, section) {
        if (state.ready) order = withContext(Dispatchers.Default) { runCatching { loadOrder() }.getOrNull() }
    }

    /**
     * Sends a command that replaces yewtube's result list. The search results are put aside first so
     * coming back to Search restores them without a new search; the old list stays hidden until the
     * new one arrives (one snapshot per command sent).
     */
    fun replaceResults(source: Section, command: String) {
        val keepSearch = resultsFrom == Section.SEARCH && source != Section.SEARCH
        if (resultsFrom != source) waitSeq = state.seq + if (keepSearch) 2 else 1
        resultsFrom = source
        if (keepSearch) {
            searchKept = true
            TerminalHub.runCommands("viewstash", command)
        } else {
            TerminalHub.runCommand(command)
        }
    }

    // A startup tab set to History needs its list loaded; back from the terminal on History the list yewtube
    // holds may be a terminal search, so the history is loaded again (that list is kept aside as the Search
    // results). Playlists and Search need nothing.
    LaunchedEffect(state.ready) {
        if (state.ready && !touchedHandled) {
            touchedHandled = true
            startupDone = true
            if (section == Section.HISTORY && resultsFrom != Section.HISTORY) {
                replaceResults(Section.HISTORY, "history")
            }
        }
    }

    fun search() {
        val text = query.trim().trimStart('.', '/').trim()
        if (text.isNotEmpty()) {
            focus.clearFocus()
            lastQuery = text
            TerminalPreferences.addSearch(ctx, text)
            replaceResults(Section.SEARCH, "/$text")
            refreshOrder()
        }
    }

    /** The order is applied when results are fetched, so a new choice repeats the current search. */
    fun pickOrder(value: String) {
        scope.launch {
            val saved = withContext(Dispatchers.Default) { runCatching { saveOrder(value) }.getOrDefault(false) }
            refreshOrder()
            if (saved && lastQuery.isNotEmpty() && resultsFrom == Section.SEARCH) {
                replaceResults(Section.SEARCH, "/$lastQuery")
            }
        }
    }

    fun select(target: Section) {
        focus.clearFocus()
        focused = false
        // Changing tab (or tapping the Playlists tab again) leaves the open playlist.
        openedPlaylist = null
        if (target == Section.HISTORY && section != Section.HISTORY) {
            replaceResults(Section.HISTORY, "history")
        } else if (target == Section.SEARCH && resultsFrom != Section.SEARCH) {
            if (searchKept || pythonKeepsSearch()) {
                // Bring the search results back from memory: no new search.
                waitSeq = state.seq + 1
                resultsFrom = Section.SEARCH
                TerminalHub.runCommand("viewrestore")
            } else if (lastQuery.isNotEmpty()) {
                // The kept results are gone (the process restarted): search again.
                replaceResults(Section.SEARCH, "/$lastQuery")
            }
        }
        section = target
    }

    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Column(modifier) {
        TabBar(
            current = section,
            activeDownloads = activeDownloads,
            onSelect = { target -> select(target) }
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)))
        if (section == Section.PLAYLISTS && openedPlaylist == null) {
            PlaylistsScreen(
                onOpen = { playlist ->
                    openedPlaylist = playlist.name
                    replaceResults(Section.PLAYLISTS, "open ${playlist.n}")
                },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else if (section == Section.DOWNLOADS) {
            DownloadsScreen(Modifier.weight(1f).fillMaxWidth())
        } else {
            if (section == Section.SEARCH) {
                SearchBox(
                    query = query,
                    onQuery = { text -> query = text },
                    onFocus = { hasFocus -> focused = hasFocus },
                    onSearch = { search() }
                )
                val currentOrder = order
                if (currentOrder != null) {
                    OrderRow(currentOrder) { value ->
                        if (value != currentOrder.value) pickOrder(value)
                    }
                }
            }
            if (section == Section.PLAYLISTS) {
                OpenedPlaylistBar(openedPlaylist.orEmpty()) { openedPlaylist = null }
            }
            if (autoQueue != null) {
                AutoQueueRow(
                    autoQueue = autoQueue,
                    showSave = section == Section.SEARCH && state.mode == "normal" && shownItems.isNotEmpty(),
                    onToggle = {
                        val value = !autoQueue
                        selectedAutoQueue = value
                        TerminalPreferences.saveClientAutoQueue(ctx, value)
                    },
                    onSave = { saving = true }
                )
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
            if (state.busy || !state.ready || waiting || loadingTrack) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = ACCENT, trackColor = Color(0xFF2A2A2A))
            }
            // The stream of a track can take many seconds to be found the first time: say so.
            if (loadingTrack) {
                Text(
                    stringResource(R.string.client_loading_track),
                    style = small.copy(color = ACCENT),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            if (shownMessage.isNotEmpty()) {
                Text(shownMessage, style = small.copy(color = ACCENT), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
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
            } else if (state.ready && shownItems.isEmpty() && !waiting) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            when (section) {
                                Section.HISTORY -> R.string.history_empty
                                Section.PLAYLISTS -> R.string.pledit_empty
                                else -> R.string.client_empty
                            }
                        ),
                        style = small.copy(textAlign = TextAlign.Center)
                    )
                }
            } else {
                val listName = if (section == Section.PLAYLISTS) openedPlaylist else null
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(shownItems, key = { "${it.n}-${it.id}" }) { item ->
                        ResultRow(
                            item,
                            playlist = state.mode == "ytpl",
                            queueRest = autoQueue ?: true,
                            onAdd = { adding = it },
                            onDownload = { kind -> DownloadHub.loadFormats(item.id, item.title, kind) },
                            onRemove = if (listName != null) {
                                { TerminalHub.runCommand("plrm $listName ${item.n}") }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
            if (!showHistory && shownPages > 1) {
                PageBar(state.page, shownPages)
            }
        }
    }
    ClientDialogs(
        saving = saving,
        onSavingChange = { saving = it },
        adding = adding,
        onAddingChange = { adding = it },
        clearingHistory = clearingHistory,
        onClearingHistoryChange = { clearingHistory = it },
        inOpenedPlaylist = section == Section.PLAYLISTS,
        openedPlaylist = openedPlaylist
    )
    formats?.let { picker ->
        FormatsDialog(
            state = picker,
            onPick = { option -> DownloadHub.start(picker, option) },
            onDismiss = { DownloadHub.dismissFormats() }
        )
    }
}

@Composable
private fun TabBar(current: Section, activeDownloads: Int, onSelect: (Section) -> Unit) {
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Section.values().forEach { tab ->
            val label = if (tab == Section.DOWNLOADS && activeDownloads > 0) {
                stringResource(R.string.dl_tab_active, activeDownloads)
            } else {
                stringResource(tab.label)
            }
            Text(
                label,
                style = small.copy(
                    color = if (current == tab) ACCENT else MUTED,
                    textAlign = TextAlign.Center
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { onSelect(tab) }.padding(vertical = 10.dp)
            )
        }
    }
}

/** The search text box with its clear (x) button and the search button. */
@Composable
private fun SearchBox(
    query: String,
    onQuery: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    onSearch: () -> Unit
) {
    val searchStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.White)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                .onFocusChanged { onFocus(it.hasFocus) }
                .border(1.dp, ACCENT, RoundedCornerShape(6.dp))
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            singleLine = true,
            textStyle = searchStyle,
            cursorBrush = SolidColor(ACCENT),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        if (query.isEmpty()) Text(stringResource(R.string.client_search_hint), style = small)
                        inner()
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.client_search_clear),
                                tint = MUTED,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        )
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.client_search), tint = FG)
        }
    }
}

/** The "order by" picker under the search box. */
@Composable
private fun OrderRow(current: ConfigOption, onPick: (String) -> Unit) {
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.orderui_label), style = small)
        Box {
            Text(
                orderName(current.value) + " \u25BE",
                style = small.copy(color = ACCENT),
                modifier = Modifier.clickable { menu = true }.padding(horizontal = 8.dp, vertical = 8.dp)
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                current.options.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(orderName(value)) },
                        onClick = {
                            menu = false
                            onPick(value)
                        }
                    )
                }
            }
        }
    }
}

/** Back arrow and name of the playlist opened inside the Playlists tab. */
@Composable
private fun OpenedPlaylistBar(name: String, onBack: () -> Unit) {
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.pledit_back),
                tint = FG
            )
        }
        Text(
            name,
            style = small.copy(color = ACCENT),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The auto-queue switch, with the "save as playlist" button when there are results to save. */
@Composable
private fun AutoQueueRow(autoQueue: Boolean, showSave: Boolean, onToggle: () -> Unit, onSave: () -> Unit) {
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onToggle)
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
        if (showSave) {
            TextButton(
                onClick = onSave,
                colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
            ) { Text(stringResource(R.string.client_save_playlist), fontSize = 12.sp) }
        }
    }
}

/** Previous and next page buttons with the page counter. */
@Composable
private fun PageBar(page: Int, pages: Int) {
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val hasPrevious = page > 0
        val hasNext = page + 1 < pages
        IconButton(onClick = { TerminalHub.runCommand("p") }, enabled = hasPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.client_prev),
                tint = if (hasPrevious) FG else MUTED
            )
        }
        Text(
            stringResource(R.string.client_page, page + 1, pages),
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

/** The save-playlist, add-to-playlist and clear-history dialogs of the client. */
@Composable
private fun ClientDialogs(
    saving: Boolean,
    onSavingChange: (Boolean) -> Unit,
    adding: Int?,
    onAddingChange: (Int?) -> Unit,
    clearingHistory: Boolean,
    onClearingHistoryChange: (Boolean) -> Unit,
    inOpenedPlaylist: Boolean,
    openedPlaylist: String?
) {
    if (saving) {
        NameDialog(
            title = stringResource(R.string.playlist_save_title),
            confirmLabel = stringResource(R.string.playlist_save_confirm),
            onConfirm = { name ->
                TerminalHub.runCommand("save $name")
                onSavingChange(false)
            },
            onDismiss = { onSavingChange(false) }
        )
    }
    if (adding != null) {
        val number: Int = adding
        AddToPlaylistDialog(
            onPick = { name ->
                if (inOpenedPlaylist && name == openedPlaylist) {
                    // Adding to the playlist being shown: reload it so the new entry appears.
                    TerminalHub.runCommands("pladd $name $number", "plreload $name")
                } else {
                    TerminalHub.runCommand("pladd $name $number")
                }
                onAddingChange(null)
            },
            onDismiss = { onAddingChange(null) }
        )
    }
    if (clearingHistory) {
        ConfirmDialog(
            title = stringResource(R.string.history_clear_confirm),
            confirmLabel = stringResource(R.string.history_clear),
            onConfirm = {
                // "history clear" leaves the list on screen; showing the history again refreshes it at once.
                TerminalHub.runCommands("history clear", "history")
                onClearingHistoryChange(false)
            },
            onDismiss = { onClearingHistoryChange(false) }
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
    onDownload: (String) -> Unit,
    onRemove: (() -> Unit)? = null
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
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pledit_remove)) },
                            onClick = {
                                menu = false
                                onRemove()
                            }
                        )
                    }
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
