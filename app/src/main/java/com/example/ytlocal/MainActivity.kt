package com.example.ytlocal

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.compose.runtime.saveable.rememberSaveable

private val BG = Color(0xFF0C0C0C)
private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)

class MainActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        future = MediaController.Builder(this, token).buildAsync()
        setContent { TerminalScreen() }
    }

    override fun onDestroy() {
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
fun TerminalScreen() {
    val ctx = LocalContext.current
    val lines by TerminalHub.lines.collectAsState()
    val playing by TerminalHub.playing.collectAsState()
    val audioOn by TerminalHub.audioOn.collectAsState()
    val now by TerminalHub.nowPlaying.collectAsState()

    var fontSp by rememberSaveable { mutableFloatStateOf(11f) }
    var input by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var hIdx by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    val style = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = fontSp.sp,
        lineHeight = (fontSp * 1.3f).sp, color = FG
    )
    val charW = remember(fontSp) { measurer.measure("M".repeat(20), style).size.width / 20f }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun submit() {
        val l = input
        TerminalHub.sendLine(l)
        if (l.isNotBlank()) history.add(l)
        input = ""; hIdx = -1
    }

    Column(Modifier.fillMaxSize().background(BG).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("yewtube", color = ACCENT, style = style, modifier = Modifier.weight(1f))
            Text("A-", color = FG, style = style, modifier = Modifier.padding(8.dp).let {
                it.then(Modifier.clickableNoRipple { fontSp = (fontSp - 1f).coerceAtLeast(7f) })
            })
            Text("A+", color = FG, style = style, modifier = Modifier.padding(8.dp).let {
                it.then(Modifier.clickableNoRipple { fontSp = (fontSp + 1f).coerceAtMost(20f) })
            })
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)) {
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val lineH = with(density) { style.lineHeight.toPx() }
            val cols = (wPx / charW).toInt().coerceAtLeast(20)
            val rows = (hPx / lineH).toInt().coerceAtLeast(6)
            LaunchedEffect(cols, rows) { TerminalHub.ensureStarted(ctx.applicationContext, cols, rows) }
            LaunchedEffect(lines) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
            LazyColumn(state = listState) {
                items(lines.size) { i -> Text(lines[i], style = style, maxLines = 1, softWrap = false) }
            }
        }

        if (playing) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(now, color = ACCENT, style = style, maxLines = 1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CtrlBtn(Icons.Filled.SkipPrevious) { TerminalHub.control("prev") }
                    CtrlBtn(Icons.Filled.Replay10) { TerminalHub.control("seekb") }
                    CtrlBtn(if (audioOn) Icons.Filled.Pause else Icons.Filled.PlayArrow) { TerminalHub.control("toggle") }
                    CtrlBtn(Icons.Filled.Forward10) { TerminalHub.control("seekf") }
                    CtrlBtn(Icons.Filled.SkipNext) { TerminalHub.control("next") }
                    CtrlBtn(Icons.Filled.Stop) { TerminalHub.control("stop") }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("^C", color = FG, style = style, modifier = Modifier.padding(8.dp).clickableNoRipple { TerminalHub.interrupt() })
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    singleLine = true,
                    textStyle = style.copy(color = Color.White),
                    cursorBrush = SolidColor(ACCENT),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { inner ->
                        Row { Text("> ", color = ACCENT, style = style); inner() }
                    }
                )
                CtrlBtn(Icons.Filled.ArrowUpward) {
                    if (history.isNotEmpty()) {
                        hIdx = if (hIdx < 0) history.lastIndex else (hIdx - 1).coerceAtLeast(0)
                        input = history[hIdx]
                    }
                }
                CtrlBtn(Icons.Filled.ArrowDownward) {
                    if (hIdx >= 0) {
                        hIdx++
                        if (hIdx > history.lastIndex) { hIdx = -1; input = "" } else input = history[hIdx]
                    }
                }
                CtrlBtn(Icons.AutoMirrored.Filled.Send) { submit() }
            }
        }
    }
}

@Composable
private fun CtrlBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = null, tint = FG) }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(
        interactionSource = null, indication = null, onClick = onClick
    ))
