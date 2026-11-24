package iecxiv.yewdroid

import android.Manifest
import android.content.ComponentName
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import kotlinx.coroutines.delay

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

    override fun onResume() {
        super.onResume()
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
    }

    override fun onDestroy() {
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
fun TerminalScreen() {
    val ctx = LocalContext.current
    val zoomFlow = remember(ctx.applicationContext) { TerminalPreferences.zoom(ctx.applicationContext) }
    val savedZoom by zoomFlow.collectAsState(initial = null)
    val historyFlow = remember(ctx.applicationContext) { TerminalPreferences.history(ctx.applicationContext) }
    val savedHistory by historyFlow.collectAsState(initial = null)
    var selectedZoom by remember { mutableStateOf<Float?>(null) }
    val currentZoom = selectedZoom ?: savedZoom
    val uiStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FG)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (currentZoom == null || savedHistory == null) {
        Box(Modifier.fillMaxSize().background(BG).systemBarsPadding(), contentAlignment = Alignment.Center) {
            Text("Cargando terminal...", style = uiStyle)
        }
        return
    }
    val fontSp = currentZoom
    val lines by TerminalHub.lines.collectAsState()
    val playing by TerminalHub.playing.collectAsState()
    val audioOn by TerminalHub.audioOn.collectAsState()
    val now by TerminalHub.nowPlaying.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val history = savedHistory.orEmpty()
    var hIdx by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = fontSp.sp,
        lineHeight = (fontSp * 1.3f).sp, color = FG
    )
    val charW = remember(measurer, style, density.density, density.fontScale) {
        (measurer.measure("M".repeat(64), style).size.width / 64f).coerceAtLeast(1f)
    }

    fun changeZoom(delta: Float) {
        val value = (fontSp + delta).coerceIn(7f, 20f)
        selectedZoom = value
        TerminalPreferences.save(ctx, value)
    }

    fun submit() {
        val line = input
        TerminalHub.sendLine(line)
        TerminalPreferences.addHistory(ctx, line)
        input = ""
        hIdx = -1
    }

    Column(Modifier.fillMaxSize().background(BG).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("YewDroid", color = ACCENT, style = uiStyle, modifier = Modifier.weight(1f))
            Text("A-", color = FG, style = uiStyle, modifier = Modifier
                .clickableNoRipple { changeZoom(-1f) }.padding(12.dp))
            Text("A+", color = FG, style = uiStyle, modifier = Modifier
                .clickableNoRipple { changeZoom(1f) }.padding(12.dp))
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp).clipToBounds()) {
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val lineH = with(density) { style.lineHeight.toPx() }.coerceAtLeast(1f)
            val cols = (wPx / charW).toInt().coerceAtLeast(1)
            val rows = (hPx / lineH).toInt().coerceAtLeast(1)
            LaunchedEffect(cols, rows, wPx > 0f && hPx > 0f) {
                if (wPx > 0f && hPx > 0f) {
                    delay(100)
                    TerminalHub.ensureStarted(ctx.applicationContext, cols, rows)
                }
            }
            LaunchedEffect(lines, cols, rows) {
                if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                items(lines.size) { i ->
                    Text(lines[i], modifier = Modifier.fillMaxWidth(), style = style, softWrap = true)
                }
            }
        }

        if (playing) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(now, color = ACCENT, style = uiStyle, maxLines = 1)
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
                Text("^C", color = FG, style = uiStyle, modifier = Modifier.padding(8.dp).clickableNoRipple { TerminalHub.interrupt() })
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                        .border(1.dp, ACCENT, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    singleLine = true,
                    textStyle = uiStyle.copy(color = Color.White),
                    cursorBrush = SolidColor(ACCENT),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { inner ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("> ", color = ACCENT, style = uiStyle)
                            Box(Modifier.weight(1f)) { inner() }
                        }
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
    this.then(Modifier.clickable(interactionSource = null, indication = null, onClick = onClick))
