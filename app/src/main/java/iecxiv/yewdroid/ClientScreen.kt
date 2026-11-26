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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val MUTED = Color(0xFF7F848E)

// The client runs on a virtual screen: yewtube paginates results from its size.
private const val CLIENT_COLS = 100
private const val CLIENT_ROWS = 24

private val thumbnails = LruCache<String, ImageBitmap>(120)

@Composable
fun ClientScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val state by TerminalHub.client.collectAsState()
    val playing by TerminalHub.playing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FG)
    val loading = !state.ready || (state.busy && !playing)

    LaunchedEffect(Unit) { TerminalHub.ensureStarted(ctx.applicationContext, CLIENT_COLS, CLIENT_ROWS) }
    LaunchedEffect(state.page, state.total, state.items.firstOrNull()?.id) { listState.scrollToItem(0) }

    fun search() {
        val text = query.trim().trimStart('.', '/').trim()
        if (text.isNotEmpty()) {
            keyboard?.hide()
            TerminalHub.runCommand("/$text")
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f)
                    .border(1.dp, ACCENT, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                singleLine = true,
                textStyle = style.copy(color = Color.White),
                cursorBrush = SolidColor(ACCENT),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text(stringResource(R.string.client_search_hint), style = style.copy(color = MUTED))
                        inner()
                    }
                }
            )
            IconButton(onClick = { search() }) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.client_search_hint), tint = ACCENT)
            }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = ACCENT, trackColor = Color(0xFF2A2A2A))
        }
        if (state.message.isNotEmpty()) {
            Text(
                state.message, style = style.copy(fontSize = 12.sp, color = MUTED),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        if (state.ready && state.items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                if (!loading) {
                    Text(
                        stringResource(R.string.client_empty),
                        style = style.copy(textAlign = TextAlign.Center, color = MUTED)
                    )
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                items(state.items, key = { "${it.n}-${it.id}" }) { item ->
                    ResultRow(item, state.mode == "ytpl", style)
                }
            }
        }
        if (state.pages > 1) {
            Pager(state, style)
        }
    }
}

@Composable
private fun Pager(state: ClientState, style: TextStyle) {
    val hasPrevious = state.page > 0
    val hasNext = state.page + 1 < state.pages
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = { TerminalHub.runCommand("p") }, enabled = hasPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.client_prev),
                tint = if (hasPrevious) FG else MUTED
            )
        }
        Text(stringResource(R.string.client_page, state.page + 1, state.pages), style = style)
        IconButton(onClick = { TerminalHub.runCommand("n") }, enabled = hasNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.client_next),
                tint = if (hasNext) FG else MUTED
            )
        }
    }
}

@Composable
private fun ResultRow(item: ClientItem, playlist: Boolean, style: TextStyle) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clickable { TerminalHub.runCommand(item.n.toString()) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!playlist) {
            Box(Modifier.width(120.dp).aspectRatio(16f / 9f)) {
                Thumbnail(item.id, Modifier.fillMaxSize())
                if (item.lengthSec > 0) {
                    Text(
                        formatLength(item.lengthSec),
                        color = Color.White,
                        style = style.copy(fontSize = 10.sp),
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color(0xCC000000))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = if (playlist) {
                listOf(
                    item.author,
                    if (item.count.isNotEmpty()) stringResource(R.string.client_videos_count, item.count) else ""
                )
            } else {
                listOf(item.author, item.views, item.date)
            }.filter { it.isNotEmpty() }.joinToString(" \u00b7 ")
            if (detail.isNotEmpty()) {
                Text(
                    detail, style = style.copy(fontSize = 11.sp, color = MUTED),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!playlist) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.client_more_options), tint = FG)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val actions = listOf(
                        R.string.client_play_audio to "${item.n} -a",
                        R.string.client_play_video to "${item.n} -w",
                        R.string.client_download_audio to "da ${item.n}",
                        R.string.client_download_video to "dv ${item.n}",
                        R.string.client_add to "add ${item.n}",
                        R.string.client_share to "share ${item.n}",
                        R.string.client_open_with to "openwith ${item.n}",
                        R.string.client_more_from to "u ${item.n}"
                    )
                    actions.forEach { (label, command) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                menu = false
                                TerminalHub.runCommand(command)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(id: String, modifier: Modifier) {
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
