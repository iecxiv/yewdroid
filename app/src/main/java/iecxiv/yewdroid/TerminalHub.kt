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

/** Longest a play request is considered pending (ms): the first extraction of a video can take a while. */
private const val PLAY_PENDING_MS = 45_000L

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
    private val _playlists = MutableStateFlow(PlaylistsState())
    val playlists: StateFlow<PlaylistsState> = _playlists
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
    /** Shuffle chosen in the player (the tracks after the current one are mixed). */
    val shuffle = MutableStateFlow(false)
    /** Repeat chosen in the player: 0 = off, 1 = the list, 2 = the current track. */
    val repeat = MutableStateFlow(0)
    /** True from tapping a video until it starts (or fails): the client shows it and ignores more taps. */
    val loadingPlay = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)
    private val sizes = Channel<Pair<Int, Int>>(Channel.CONFLATED)
    @Volatile private var py: PyObject? = null
    @Volatile private var exo: ExoPlayer? = null
    @Volatile private var nextVideo = false
    @Volatile private var appContext: Context? = null
    /** Kept for the callers; playback now always runs in its own thread (see yt_term.py). */
    @Volatile private var background = false
    /** True once the current track was reported to Python as started (it then goes to the history). */
    @Volatile private var startReported = false
    /** True once a command was typed in the terminal: the list yewtube holds may no longer be the client's. */
    @Volatile private var terminalTouched = false
    /** Where to resume the track that is started again after the "show video" setting changed, and its title. */
    @Volatile private var resumeMs = 0L
    @Volatile private var resumeTitle = ""
    /** When the play request now pending was made (ms), to tell it from a later one. */
    @Volatile private var playPendingSince = 0L
    /** True when Android refused audio focus to a track that never started: it starts when the app is in front. */
    @Volatile private var resumeOnForeground = false
    private var surface: SurfaceView? = null
    /** True while the video is shown in the mini player, where the screen is allowed to turn off. */
    private var miniVideo = false
    private var started = false
    private val sink = HubSink()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            // "ended" (the track finished by itself) differs from "next" (the user skipped) for repeat-one.
            if (state == Player.STATE_ENDED && playing.value) event("ended")
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            audioOn.value = isPlaying
            updateKeepScreenOn()
            // The first time a track really sounds it is recorded in the history (not when it is only queued).
            if (isPlaying && playing.value && !startReported) {
                startReported = true
                scope.launch { runCatching { py?.callAttr("track_started") } }
            }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Android does not give audio focus to an app in the background, which is what happens when a slow
            // track finishes loading after the user left: the player pauses itself before it ever sounded.
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS &&
                playing.value && !startReported
            ) {
                resumeOnForeground = true
            }
        }
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

    /**
     * Keeps the screen from turning off while a video is showing and playing in the terminal, the expanded
     * player or fullscreen. It may turn off when the video is paused, the track is audio only, or the video is
     * only in the mini player. Runs on the main thread, where the views live.
     */
    private fun updateKeepScreenOn() {
        surface?.keepScreenOn = audioOn.value && videoOn.value && playing.value && !miniVideo
    }

    /** The mini player calls this while it shows the video, so the screen can turn off then. Main thread. */
    fun setMiniVideo(on: Boolean) {
        miniVideo = on
        updateKeepScreenOn()
    }

    /**
     * Moving the video to another view (mini player, expanded player, fullscreen) gives the player a new
     * surface. If the track then starts over, it is put back where it was a moment before.
     */
    fun attachSurface(view: SurfaceView) {
        surface = view
        updateKeepScreenOn()
        val p = exo ?: return
        val position = p.currentPosition
        val title = nowPlaying.value
        p.setVideoSurfaceView(view)
        keepPosition(position, title)
    }

    fun detachSurface(view: SurfaceView) {
        view.keepScreenOn = false
        if (surface === view) surface = null
        exo?.clearVideoSurfaceView(view)
    }

    private fun keepPosition(position: Long, title: String) {
        if (position < 3000L || !playing.value) return
        main.postDelayed({
            val p = exo ?: return@postDelayed
            if (playing.value && nowPlaying.value == title && p.currentPosition + 2000L < position) {
                p.seekTo(position)
            }
        }, 1000L)
    }

    /** The app is in front again: a track that Android would not let start in the background starts now. */
    fun onAppForeground() {
        if (!resumeOnForeground) return
        resumeOnForeground = false
        if (playing.value) main.post { exo?.play() }
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
            runCatching { m.callAttr("set_background", background) }
            runCatching { m.callAttr("set_play_mode", shuffle.value, repeat.value) }
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

    /** Kept for the callers; playback no longer depends on the view (see yt_term.py). */
    fun setBackgroundPlayback(on: Boolean) {
        background = on
    }

    /** Whether a terminal command was typed since the client screen was last built; reading it clears it. */
    fun takeTerminalTouched(): Boolean {
        val value = terminalTouched
        terminalTouched = false
        return value
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
            }
            applyVideoSetting()
        }
    }

    /**
     * When the "show video" setting changes (in Settings or with the set command) the track that is playing
     * is started again with the other kind of stream, resuming where it was.
     */
    private fun applyVideoSetting() {
        val module = py ?: return
        val needed = runCatching { module.callAttr("sync_video_mode").toBoolean() }.getOrDefault(false)
        if (needed) {
            resumeMs = positionMs.value
            resumeTitle = nowPlaying.value
            runCatching { module.callAttr("restream") }
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

    /** Called when yewtube waits for a command: refresh the native client's results and playlists. */
    fun onIdle() {
        scope.launch {
            _client.value = runCatching {
                parseClientState(
                    Python.getInstance().getModule("yt_client").callAttr("snapshot").toString()
                )
            }.getOrElse { _client.value.copy(busy = false, ready = true) }
            runCatching {
                _playlists.value = parsePlaylistsState(
                    Python.getInstance().getModule("yt_client").callAttr("playlists").toString()
                )
            }
        }
    }

    /** Sends a yewtube command from the native client without echoing it in the terminal. */
    fun runCommand(line: String) {
        if (py == null) return
        _client.value = _client.value.copy(busy = true)
        scope.launch { runCatching { py?.callAttr("send", line) } }
    }

    /**
     * Sends a command that starts playback. Getting the video can take many seconds the first time, and a
     * second tap meanwhile would be queued and restart the track once the first one starts, so taps are
     * ignored while one is pending. Returns false when this one was ignored.
     */
    fun runPlay(line: String): Boolean {
        if (py == null) return false
        val now = System.currentTimeMillis()
        if (loadingPlay.value && now - playPendingSince < PLAY_PENDING_MS) return false
        playPendingSince = now
        loadingPlay.value = true
        runCommand(line)
        // If neither the start nor a failure is ever reported, the request is not kept pending for ever.
        scope.launch {
            delay(PLAY_PENDING_MS)
            if (playPendingSince == now) loadingPlay.value = false
        }
        return true
    }

    /** Sends several commands in order from one coroutine, so they cannot overtake each other. */
    fun runCommands(vararg lines: String) {
        if (py == null) return
        _client.value = _client.value.copy(busy = true)
        scope.launch { for (line in lines) runCatching { py?.callAttr("send", line) } }
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

    /** Turns shuffle on or off; the queue shown is re-read because its order changes. */
    fun toggleShuffle() {
        shuffle.value = !shuffle.value
        pushMode(refresh = true)
    }

    /** Cycles the repeat mode: off, the list, the current track. */
    fun cycleRepeat() {
        repeat.value = (repeat.value + 1) % 3
        pushMode(refresh = false)
    }

    private fun pushMode(refresh: Boolean) {
        scope.launch {
            runCatching { py?.callAttr("set_play_mode", shuffle.value, repeat.value) }
            if (refresh) refreshQueue()
        }
    }

    fun onVideo(on: Boolean) { nextVideo = on }

    fun onPlay(url: String, title: String, artist: String, thumb: String) {
        // The video is in hand and starting: it is no longer a pending request.
        loadingPlay.value = false
        resumeOnForeground = false
        // The same track started again after the "show video" setting changed resumes where it was.
        val start = if (resumeMs > 0L && title == resumeTitle) resumeMs else 0L
        resumeMs = 0L
        resumeTitle = ""
        nowPlaying.value = title
        videoOn.value = nextVideo
        positionMs.value = start
        durationMs.value = 0L
        // A new track: it is reported to the history once it really starts sounding.
        startReported = false
        // yewtube is free to answer commands while a track plays, so it is playing, not busy.
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
                p.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(meta).build(), start)
                p.prepare()
                p.play()
            }
            updateKeepScreenOn()
        }
    }

    fun onStopPlayback() {
        // Playback ended, or a play request failed before starting: nothing is pending any more.
        loadingPlay.value = false
        resumeOnForeground = false
        playing.value = false
        nowPlaying.value = ""
        videoOn.value = false
        positionMs.value = 0L
        durationMs.value = 0L
        _queue.value = QueueState()
        main.post {
            exo?.stop()
            exo?.clearMediaItems()
            updateKeepScreenOn()
        }
    }

    private fun event(action: String) {
        scope.launch { runCatching { py?.callAttr("player_event", action) } }
    }

    fun sendLine(line: String) {
        terminalTouched = true
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
