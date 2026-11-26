package iecxiv.yewdroid

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Expone siguiente/anterior en notificacion y pantalla de bloqueo; los delega a yewtube. */
class NavPlayer(p: Player) : ForwardingPlayer(p) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        ).build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true
    override fun seekToNext() { TerminalHub.control("next") }
    override fun seekToNextMediaItem() { TerminalHub.control("next") }
    override fun seekToPrevious() { TerminalHub.control("prev") }
    override fun seekToPreviousMediaItem() { TerminalHub.control("prev") }
}

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(AndroidStreamSources.Factory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AndroidStreamSources.report(error)
            }
        })
        TerminalHub.registerPlayer(player)
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, NavPlayer(player))
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Closing the app from the recents list ends playback and removes the notification. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (TerminalHub.playing.value) TerminalHub.control("stop")
        session?.player?.run {
            pause()
            stop()
            clearMediaItems()
        }
        stopSelf()
    }

    override fun onDestroy() {
        TerminalHub.unregisterPlayer()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
