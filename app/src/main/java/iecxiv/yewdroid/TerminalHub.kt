package iecxiv.yewdroid

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.compose.ui.text.AnnotatedString
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Interface invoked by Python (yt_term.py). */
interface TermSink {
    fun write(s: String)
    fun play(url: String, title: String, artist: String, thumb: String, lengthSec: Int)
    fun video(on: Boolean)
    fun mode(playing: Boolean)
    fun stopPlayback()
    /** yewtube is waiting for a command (the native client refreshes its results). */
    fun idle()
}

/** Public named class so Chaquopy can call it from Python. */
class HubSink : TermSink {
    override fun write(s: String) { TerminalHub.onWrite(s) }
    override fun play(url: String, title: String, artist: String, thumb: String, lengthSec: Int) {
        TerminalHub.onPlay(url, title, artist, thumb)
    }
    override fun video(on: Boolean) { TerminalHub.onVideo(on) }
    override fun mode(playing: Boolean) { TerminalHub.playing.value = playing }
    override fun stopPlayback() { TerminalHub.onStopPlayback() }
    override fun idle() { TerminalHub.onIdle() }
}

object TerminalHub {
    private val buffer = TermBuffer()
    private val _lines = MutableStateFlow<List<AnnotatedString>>(emptyList())
    val lines: StateFlow<List<AnnotatedString>> = _lines
    private val _client = MutableStateFlow(ClientState())
    val client: StateFlow<ClientState> = _client
    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue
    val playing = MutableStateFlow(false)
    val audioOn = MutableStateFlow(false)
    val nowPlaying = MutableStateFlow("")
    val videoOn = MutableStateFlow(false)
    val videoAspect = MutableStateFlow(16f / 9f)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val translationAvailable = MutableStateFlow(false)
    val translated = MutableStateFlow(false)
    /** True once the Python session has loaded the translation catalogue (or failed to). */
    val sessionStarted = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)
    private val sizes = Channel<Pair<Int, Int>>(Channel.CONFLATED)
    @Volatile private var py: PyObject? = null
    @Volatile private var exo: ExoPlayer? = null
    @Volatile private var nextVideo = false
    @Volatile private var appContext: Context? = null
    private var surface: SurfaceView? = null
    private var started = false
    private val sink = HubSink()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED && playing.value) event("next")
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { audioOn.value = isPlaying }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoAspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            onWrite("\n\u001b[31m[player] ${error.errorCodeName}\u001b[0m\n")
            if (playing.value) event("next")
        }
    }

    private fun text(id: Int, vararg args: Any, fallback: String): String =
        appContext?.getString(id, *args) ?: fallback

    private fun tagFor(choice: String): String =
        if (choice == "system") Locale.getDefault().toLanguageTag() else choice

    fun registerPlayer(p: ExoPlayer) {
        exo = p
        p.addListener(listener)
        surface?.let { p.setVideoSurfaceView(it) }
    }

    fun unregisterPlayer() {
        exo?.removeListener(listener)
        exo = null
    }

    fun attachSurface(view: SurfaceView) {
        surface = view
        exo?.setVideoSurfaceView(view)
    }

    fun detachSurface(view: SurfaceView) {
        if (surface === view) surface = null
        exo?.clearVideoSurfaceView(view)
    }

    @Synchronized
    fun ensureStarted(ctx: Context, cols: Int, rows: Int) {
        sizes.trySend(cols to rows)
        if (started) return
        started = true
        val dir = ctx.filesDir.absolutePath
        val app = ctx.applicationContext
        appContext = app
        scope.launch {
            val m = Python.getInstance().getModule("yt_term")
            Python.getInstance().getModule("terminal_layout").callAttr("install", m)
            val enabled = runCatching { TerminalPreferences.translateOutput(app).first() }.getOrDefault(false)
            val choice = runCatching { TerminalPreferences.terminalLanguage(app).first() }.getOrDefault("system")
            val available = runCatching {
                val i18n = Python.getInstance().getModule("android_i18n")
                i18n.callAttr("install", m)
                i18n.callAttr("configure", tagFor(choice), enabled).toBoolean()
            }.getOrDefault(false)
            translationAvailable.value = available
            translated.value = enabled && available
            sessionStarted.value = true
            py = m
            val initial = sizes.tryReceive().getOrNull() ?: (cols to rows)
            m.callAttr("start", sink, dir, initial.first, initial.second)
            scope.launch { tick() }
            for ((width, height) in sizes) {
                runCatching { m.callAttr("resize", width, height) }.onFailure {
                    onWrite("\n" + text(R.string.terminal_resize_failed, it.message.orEmpty(),
                        fallback = "[terminal] Could not update the size: ${it.message.orEmpty()}") + "\n")
                }
            }
        }
    }

    fun setTranslated(ctx: Context, on: Boolean) {
        if (!translationAvailable.value) return
        translated.value = on
        TerminalPreferences.saveTranslateOutput(ctx, on)
        scope.launch {
            runCatching {
                Python.getInstance().getModule("android_i18n").callAttr("set_enabled", on)
                py?.callAttr("request_redraw")
            }
        }
    }

    /** Changes the language the terminal output is translated to ("system" or a language code). */
    fun setLanguage(ctx: Context, choice: String) {
        TerminalPreferences.saveTerminalLanguage(ctx, choice)
        if (!sessionStarted.value) return
        scope.launch {
            runCatching {
                val i18n = Python.getInstance().getModule("android_i18n")
                translationAvailable.value =
                    i18n.callAttr("configure", tagFor(choice), translated.value).toBoolean()
                py?.callAttr("request_redraw")
            }
        }
    }

    private suspend fun tick() {
        while (true) {
            delay(1000)
            if (playing.value) {
                val (posMs, durMs) = withContext(Dispatchers.Main) {
                    (exo?.currentPosition ?: 0L) to (exo?.duration ?: C.TIME_UNSET)
                }
                positionMs.value = posMs
                durationMs.value = if (durMs == C.TIME_UNSET || durMs < 0) 0L else durMs
                runCatching { py?.callAttr("progress", (posMs / 1000).toInt()) }
            }
        }
    }

    fun onWrite(s: String) {
        buffer.append(s)
        if (pending.compareAndSet(false, true)) {
            scope.launch {
                delay(40)
                pending.set(false)
                _lines.value = buffer.snapshot()
            }
        }
    }

    /** Called when yewtube waits for a command: refresh the native client's results. */
    fun onIdle() {
        scope.launch {
            _client.value = runCatching {
                parseClientState(
                    Python.getInstance().getModule("yt_client").callAttr("snapshot").toString()
                )
            }.getOrElse { _client.value.copy(busy = false, ready = true) }
        }
    }

    /** Sends a yewtube command from the native client without echoing it in the terminal. */
    fun runCommand(line: String) {
        if (py == null) return
        _client.value = _client.value.copy(busy = true)
        scope.launch { runCatching { py?.callAttr("send", line) } }
    }

    /** Reads the tracks of the current playback (called each time a track starts). */
    private fun refreshQueue() {
        scope.launch {
            runCatching {
                _queue.value = parseQueueState(
                    Python.getInstance().getModule("yt_client").callAttr("queue").toString()
                )
            }
        }
    }

    /** Skips to track [index] of the current playback queue. */
    fun jumpTo(index: Int) {
        scope.launch {
            runCatching { Python.getInstance().getModule("yt_client").callAttr("jump", index) }
        }
    }

    fun onVideo(on: Boolean) { nextVideo = on }

    fun onPlay(url: String, title: String, artist: String, thumb: String) {
        nowPlaying.value = title
        videoOn.value = nextVideo
        positionMs.value = 0L
        durationMs.value = 0L
        // yewtube stays inside playback until the track ends, so it is playing, not busy.
        _client.value = _client.value.copy(busy = false)
        refreshQueue()
        main.post {
            val p = exo
            if (p == null) {
                onWrite("\n\u001b[31m" + text(R.string.player_unavailable,
                    fallback = "[player] playback service unavailable") + "\u001b[0m\n")
                event("stop")
            } else {
                val meta = MediaMetadata.Builder().setTitle(title).setArtist(artist)
                    .setArtworkUri(if (thumb.isNotBlank()) Uri.parse(thumb) else null).build()
                p.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(meta).build())
                p.prepare()
                p.play()
            }
        }
    }

    fun onStopPlayback() {
        playing.value = false
        nowPlaying.value = ""
        videoOn.value = false
        positionMs.value = 0L
        durationMs.value = 0L
        _queue.value = QueueState()
        main.post { exo?.stop(); exo?.clearMediaItems() }
    }

    private fun event(action: String) {
        scope.launch { runCatching { py?.callAttr("player_event", action) } }
    }

    fun sendLine(line: String) {
        onWrite(line + "\n")
        scope.launch { runCatching { py?.callAttr("send", line) } }
    }

    fun interrupt() {
        if (playing.value) event("stop")
        else scope.launch { runCatching { py?.callAttr("interrupt") } }
    }

    fun seekTo(ms: Long) {
        positionMs.value = ms
        main.post { exo?.seekTo(ms) }
    }

    fun control(action: String) {
        when (action) {
            "toggle" -> main.post { exo?.let { it.playWhenReady = !it.playWhenReady } }
            "seekf" -> main.post { exo?.let { it.seekTo(it.currentPosition + 10_000) } }
            "seekb" -> main.post { exo?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } }
            else -> event(action)
        }
    }
}
