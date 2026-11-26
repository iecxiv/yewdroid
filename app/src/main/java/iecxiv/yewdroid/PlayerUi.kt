package iecxiv.yewdroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BG = Color(0xFF0C0C0C)
private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val MUTED = Color(0xFF7F848E)

private fun lengthLabel(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

/** Compact player shown at the bottom of the native client while something plays. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val title by TerminalHub.nowPlaying.collectAsState()
    val audioOn by TerminalHub.audioOn.collectAsState()
    val position by TerminalHub.positionMs.collectAsState()
    val duration by TerminalHub.durationMs.collectAsState()
    val queue by TerminalHub.queue.collectAsState()
    val current = queue.items.getOrNull(queue.index)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().background(Color(0xFF151515)).clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF2A2A2A))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(ACCENT))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Thumbnail(current?.id.orEmpty(), Modifier.width(64.dp).height(36.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FG)
                )
                val author = current?.author.orEmpty()
                if (author.isNotEmpty()) {
                    Text(
                        author, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MUTED)
                    )
                }
            }
            IconButton(onClick = { TerminalHub.control("toggle") }) {
                Icon(
                    if (audioOn) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (audioOn) R.string.player_pause else R.string.player_play),
                    tint = FG
                )
            }
            IconButton(onClick = { TerminalHub.control("next") }) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next), tint = FG)
            }
        }
    }
}

/** Full player: artwork, progress, controls and the playback queue. Video is drawn above by the host screen. */
@Composable
fun PlayerScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val title by TerminalHub.nowPlaying.collectAsState()
    val audioOn by TerminalHub.audioOn.collectAsState()
    val videoOn by TerminalHub.videoOn.collectAsState()
    val queue by TerminalHub.queue.collectAsState()
    val current = queue.items.getOrNull(queue.index)
    LazyColumn(modifier.background(BG)) {
        item {
            Column(Modifier.fillMaxWidth()) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.player_minimize), tint = FG)
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (!videoOn) {
                        Thumbnail(current?.id.orEmpty(), Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Color.White)
                    )
                    val author = current?.author.orEmpty()
                    if (author.isNotEmpty()) {
                        Text(
                            author,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
                        )
                    }
                }
                SeekBar(Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { TerminalHub.control("prev") }) {
                        Icon(Icons.Filled.SkipPrevious, stringResource(R.string.player_previous), tint = FG)
                    }
                    IconButton(onClick = { TerminalHub.control("seekb") }) {
                        Icon(Icons.Filled.Replay10, stringResource(R.string.player_back10), tint = FG)
                    }
                    IconButton(onClick = { TerminalHub.control("toggle") }, modifier = Modifier.size(56.dp)) {
                        Icon(
                            if (audioOn) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (audioOn) R.string.player_pause else R.string.player_play),
                            tint = ACCENT,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    IconButton(onClick = { TerminalHub.control("seekf") }) {
                        Icon(Icons.Filled.Forward10, stringResource(R.string.player_forward10), tint = FG)
                    }
                    IconButton(onClick = { TerminalHub.control("next") }) {
                        Icon(Icons.Filled.SkipNext, stringResource(R.string.player_next), tint = FG)
                    }
                    IconButton(onClick = { TerminalHub.control("stop") }) {
                        Icon(Icons.Filled.Stop, stringResource(R.string.player_stop), tint = FG)
                    }
                }
                if (queue.items.size > 1) {
                    Text(
                        stringResource(R.string.player_queue),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = ACCENT),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
            }
        }
        if (queue.items.size > 1) {
            itemsIndexed(queue.items) { index, item ->
                val playingNow = index == queue.index
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (playingNow) Color(0xFF1B241B) else Color.Transparent)
                        .clickable { TerminalHub.jumpTo(index) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED),
                        modifier = Modifier.width(32.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                color = if (playingNow) ACCENT else FG
                            )
                        )
                        val detail = listOf(item.author, if (item.lengthSec > 0) lengthLabel(item.lengthSec) else "")
                            .filter { it.isNotEmpty() }.joinToString(" \u00b7 ")
                        if (detail.isNotEmpty()) {
                            Text(
                                detail,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MUTED)
                            )
                        }
                    }
                }
            }
        }
    }
}
