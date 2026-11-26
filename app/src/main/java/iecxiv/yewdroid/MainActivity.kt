package iecxiv.yewdroid

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

private val BG = Color(0xFF0C0C0C)
private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private const val AUTO_HIDE_MS = 3000L

class MainActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    // The app language this instance was created with; when Settings changes it, the screen is recreated.
    private var language = AppLanguage.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        language = AppLanguage.saved(newBase)
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        future = MediaController.Builder(this, token).buildAsync()
        setContent { TerminalScreen() }
    }

    override fun onResume() {
        super.onResume()
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        if (AppLanguage.saved(this) != language) recreate()
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
    val modeFlow = remember(ctx.applicationContext) { TerminalPreferences.terminalMode(ctx.applicationContext) }
    val savedMode by modeFlow.collectAsState(initial = null)
    val headerFlow = remember(ctx.applicationContext) { TerminalPreferences.headerExpanded(ctx.applicationContext) }
    val savedHeader by headerFlow.collectAsState(initial = null)
    var selectedMode by remember { mutableStateOf<Boolean?>(null) }
    val terminalMode = selectedMode ?: savedMode
    var selectedHeader by remember { mutableStateOf<Boolean?>(null) }
    val headerExpanded = selectedHeader ?: savedHeader
    var selectedZoom by remember { mutableStateOf<Float?>(null) }
    val currentZoom = selectedZoom ?: savedZoom
    val uiStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FG)
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    fun askBatteryExemption() {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return
        val prefs = ctx.getSharedPreferences("yewdroid_prompts", Context.MODE_PRIVATE)
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName) || prefs.getBoolean("battery_prompted", false)) return
        prefs.edit().putBoolean("battery_prompted", true).apply()
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + ctx.packageName))
        try {
            batteryLauncher.launch(request)
        } catch (_: ActivityNotFoundException) {
            try {
                batteryLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askBatteryExemption()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBatteryExemption()
        }
    }
    if (currentZoom == null || savedHistory == null || terminalMode == null || headerExpanded == null) {
        Box(Modifier.fillMaxSize().background(BG).systemBarsPadding(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.main_loading), style = uiStyle)
        }
        return
    }
    val fontSp = currentZoom
    val lines by TerminalHub.lines.collectAsState()
    val playing by TerminalHub.playing.collectAsState()
    val audioOn by TerminalHub.audioOn.collectAsState()
    val now by TerminalHub.nowPlaying.collectAsState()
    val videoOn by TerminalHub.videoOn.collectAsState()
    val videoAspect by TerminalHub.videoAspect.collectAsState()
    val translationAvailable by TerminalHub.translationAvailable.collectAsState()
    val translated by TerminalHub.translated.collectAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Fullscreen follows the rotation: landscape with a video shows it fullscreen, portrait shows it inline.
    // [manualFull] is the fullscreen button used in portrait (it forces landscape until the user exits);
    // [dismissed] is the exit button used while the phone is already held in landscape.
    var manualFull by rememberSaveable { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val showVideo = playing && videoOn
    val isFull = showVideo && (manualFull || (landscape && !dismissed))
    FullscreenEffect(isFull, manualFull)
    LaunchedEffect(landscape) { if (!landscape) dismissed = false }
    LaunchedEffect(showVideo) {
        if (!showVideo) {
            manualFull = false
            dismissed = false
        }
    }
    fun enterFullscreen() {
        if (landscape) dismissed = false else manualFull = true
    }
    fun exitFullscreen() {
        manualFull = false
        if (landscape) dismissed = true
    }
    BackHandler(enabled = isFull) { exitFullscreen() }
    LaunchedEffect(playing) { if (!playing) playerOpen = false }
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
    val chevronRotation by animateFloatAsState(
        targetValue = if (headerExpanded) 180f else 0f,
        label = "headerChevron"
    )

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

    if (isFull) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            VideoSurface(Modifier.aspectRatio(videoAspect.coerceAtLeast(0.2f)))
            FullscreenControls({ exitFullscreen() }, Modifier.fillMaxSize())
        }
        return
    }
    BackHandler(enabled = playerOpen && playing && !terminalMode) { playerOpen = false }

    Column(Modifier.fillMaxSize().background(BG).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "YewDroid", color = ACCENT, style = uiStyle,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (terminalMode) {
                Text("A-", color = FG, style = uiStyle, modifier = Modifier
                    .clickableNoRipple { changeZoom(-1f) }.padding(12.dp))
                Text("A+", color = FG, style = uiStyle, modifier = Modifier
                    .clickableNoRipple { changeZoom(1f) }.padding(12.dp))
            }
            AnimatedVisibility(
                visible = headerExpanded,
                enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (terminalMode && translationAvailable) {
                        CtrlBtn(
                            Icons.Filled.Language,
                            if (translated) ACCENT else FG,
                            stringResource(R.string.main_translate_output)
                        ) {
                            TerminalHub.setTranslated(ctx, !translated)
                        }
                    }
                    // The label names the view you will go to: "Terminal" from the UI, "UI" from the terminal.
                    Text(
                        stringResource(if (terminalMode) R.string.main_ui else R.string.main_terminal),
                        color = ACCENT, style = uiStyle, maxLines = 1,
                        modifier = Modifier
                            .clickableNoRipple {
                                val value = !terminalMode
                                selectedMode = value
                                TerminalPreferences.saveTerminalMode(ctx, value)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                    CtrlBtn(Icons.Filled.Settings, description = stringResource(R.string.settings_title)) {
                        ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
                    }
                }
            }
            CtrlBtn(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                description = stringResource(
                    if (headerExpanded) R.string.header_hide_options else R.string.header_show_options
                ),
                rotation = chevronRotation
            ) {
                val value = !headerExpanded
                selectedHeader = value
                TerminalPreferences.saveHeaderExpanded(ctx, value)
            }
        }

        if (showVideo) {
            var overlay by remember { mutableStateOf(true) }
            var tick by remember { mutableIntStateOf(0) }
            var dragging by remember { mutableStateOf(false) }
            LaunchedEffect(overlay, tick, dragging) {
                if (overlay && !dragging) {
                    delay(AUTO_HIDE_MS)
                    overlay = false
                }
            }
            Box(
                Modifier.fillMaxWidth().background(Color.Black).clickableNoRipple { overlay = !overlay },
                contentAlignment = Alignment.Center
            ) {
                val ratio = videoAspect.coerceIn(1f, 2.4f)
                VideoSurface(
                    if (landscape) Modifier.height(150.dp).aspectRatio(ratio)
                    else Modifier.fillMaxWidth().aspectRatio(ratio)
                )
                if (overlay) {
                    Box(Modifier.align(Alignment.TopEnd).background(Color(0x66000000))) {
                        CtrlBtn(Icons.Filled.Fullscreen) { enterFullscreen() }
                    }
                    SeekBar(
                        Modifier.align(Alignment.BottomCenter).background(Color(0x66000000)),
                        onInteract = { tick++ },
                        onDragChange = { dragging = it }
                    )
                }
            }
            // In the UI the controls sit between the video and the tabs (the terminal has its own below).
            if (!terminalMode) PlaybackControls(audioOn, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        }

        if (terminalMode) {
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
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ClientScreen(Modifier.fillMaxSize())
                if (playing && playerOpen) {
                    PlayerScreen(onClose = { playerOpen = false }, modifier = Modifier.fillMaxSize())
                }
            }
        }

        if (playing && terminalMode) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(now, color = ACCENT, style = uiStyle, maxLines = 1)
                PlaybackControls(audioOn, Modifier.fillMaxWidth())
            }
        } else if (playing) {
            if (!playerOpen) MiniPlayer(onOpen = { playerOpen = true })
        } else if (terminalMode) {
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

/** Previous, back 10 s, play/pause, forward 10 s, next and stop. */
@Composable
private fun PlaybackControls(audioOn: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly) {
        CtrlBtn(Icons.Filled.SkipPrevious) { TerminalHub.control("prev") }
        CtrlBtn(Icons.Filled.Replay10) { TerminalHub.control("seekb") }
        CtrlBtn(if (audioOn) Icons.Filled.Pause else Icons.Filled.PlayArrow) { TerminalHub.control("toggle") }
        CtrlBtn(Icons.Filled.Forward10) { TerminalHub.control("seekf") }
        CtrlBtn(Icons.Filled.SkipNext) { TerminalHub.control("next") }
        CtrlBtn(Icons.Filled.Stop) { TerminalHub.control("stop") }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Hides the system bars while the video is fullscreen. [forceLandscape] (the fullscreen button used in
 * portrait) also locks the screen to landscape until the user exits; a fullscreen entered by rotating
 * the phone leaves the orientation to the sensor so rotating back to portrait leaves it.
 */
@Composable
private fun FullscreenEffect(on: Boolean, forceLandscape: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(on, forceLandscape) {
        val activity = ctx.findActivity()
        if (activity != null && on) {
            if (forceLandscape) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (activity != null && on) {
                if (forceLandscape) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Progress slider with elapsed and total time; also used by the native player. */
@Composable
internal fun SeekBar(
    modifier: Modifier = Modifier,
    onInteract: () -> Unit = {},
    onDragChange: (Boolean) -> Unit = {}
) {
    val position by TerminalHub.positionMs.collectAsState()
    val duration by TerminalHub.durationMs.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        onDispose { onDragChange(false) }
    }
    val label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(position), style = label)
        Slider(
            value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = {
                if (dragging == null) onDragChange(true)
                dragging = it
                onInteract()
            },
            onValueChangeFinished = {
                dragging?.let { TerminalHub.seekTo((it * duration).toLong()) }
                dragging = null
                onDragChange(false)
                onInteract()
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = ACCENT,
                activeTrackColor = ACCENT,
                inactiveTrackColor = Color(0x55FFFFFF),
                disabledThumbColor = Color(0x55FFFFFF),
                disabledActiveTrackColor = Color(0x55FFFFFF),
                disabledInactiveTrackColor = Color(0x33FFFFFF)
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Text(formatTime(duration), style = label)
    }
}

@Composable
private fun FullscreenControls(onExit: () -> Unit, modifier: Modifier) {
    val audioOn by TerminalHub.audioOn.collectAsState()
    var visible by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(visible, tick, dragging) {
        if (visible && !dragging) {
            delay(AUTO_HIDE_MS)
            visible = false
        }
    }
    fun hit(action: () -> Unit) {
        tick++
        action()
    }
    Box(modifier.clickableNoRipple { visible = !visible }) {
        if (visible) {
            Column(
                Modifier.fillMaxSize().background(Color(0x88000000)),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CtrlBtn(Icons.Filled.FullscreenExit) { hit(onExit) }
                }
                // The buttons sit right above the time bar, at the bottom of the screen.
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CtrlBtn(Icons.Filled.SkipPrevious) { hit { TerminalHub.control("prev") } }
                        CtrlBtn(Icons.Filled.Replay10) { hit { TerminalHub.control("seekb") } }
                        CtrlBtn(if (audioOn) Icons.Filled.Pause else Icons.Filled.PlayArrow) { hit { TerminalHub.control("toggle") } }
                        CtrlBtn(Icons.Filled.Forward10) { hit { TerminalHub.control("seekf") } }
                        CtrlBtn(Icons.Filled.SkipNext) { hit { TerminalHub.control("next") } }
                        CtrlBtn(Icons.Filled.Stop) { hit { TerminalHub.control("stop") } }
                    }
                    SeekBar(onInteract = { tick++ }, onDragChange = { dragging = it })
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(modifier: Modifier) {
    val context = LocalContext.current
    val view = remember { SurfaceView(context) }
    DisposableEffect(view) {
        TerminalHub.attachSurface(view)
        onDispose { TerminalHub.detachSurface(view) }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

@Composable
private fun CtrlBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = FG,
    description: String? = null,
    rotation: Float = 0f,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.rotate(rotation))
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(interactionSource = null, indication = null, onClick = onClick))
