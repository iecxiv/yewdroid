package iecxiv.yewdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the download queue (Python, android_downloads) has work and shows
 * its progress in a notification. Starts when a download is queued and stops once the queue is idle.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false
    private var stopJob: Job? = null
    private var lastUpdate = 0L
    // Ids seen active during this run: only those get a "finished" notification, once.
    private val tracked = HashSet<Int>()
    private val announced = HashSet<Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        if (intent?.action == ACTION_CANCEL) {
            DownloadHub.downloads.value.items.firstOrNull { it.state == "running" }
                ?.let { DownloadHub.cancel(it.id) }
        }
        if (!foreground) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, ongoing(null, 0),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
                )
            } catch (_: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
            foreground = true
            scope.launch { DownloadHub.downloads.collect { render(it.items) } }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits dataSync services to 6 hours in 24: stop; queued downloads stay pending. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(items: List<DownloadItem>) {
        val active = items.filter { it.active }
        active.forEach { tracked.add(it.id) }
        items.filter { it.finished && it.id in tracked && announced.add(it.id) }.forEach { announce(it) }
        if (active.isEmpty()) {
            // Short grace period: a download queued a moment ago may not be in the state yet.
            if (stopJob == null) {
                stopJob = scope.launch {
                    delay(STOP_DELAY_MS)
                    finish()
                }
            }
            return
        }
        stopJob?.cancel()
        stopJob = null
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdate < UPDATE_INTERVAL_MS) return
        lastUpdate = now
        val current = active.firstOrNull { it.state == "running" } ?: active.first()
        notificationManager().notify(NOTIFICATION_ID, ongoing(current, active.size - 1))
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.dlnotif_channel), NotificationManager.IMPORTANCE_LOW
        )
        notificationManager().createNotificationChannel(channel)
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stageText(stage: String): String = when (stage) {
        "download" -> getString(R.string.dl_stage_download)
        "video" -> getString(R.string.dl_stage_video)
        "audio" -> getString(R.string.dl_stage_audio)
        "merge" -> getString(R.string.dl_stage_merge)
        "encode" -> getString(R.string.dl_stage_encode)
        "export" -> getString(R.string.dl_stage_export)
        "cancelling" -> getString(R.string.dl_stage_cancelling)
        else -> ""
    }

    /** The ongoing notification; [item] is null until the first job shows up in the state. */
    private fun ongoing(item: DownloadItem?, waiting: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (item == null) {
            builder.setContentTitle(getString(R.string.dlnotif_preparing))
                .setProgress(0, 0, true)
            return builder.build()
        }
        val measurable = item.state == "running" &&
            (item.stage == "download" || item.stage == "video" || item.stage == "audio")
        val percent = item.percent.toInt().coerceIn(0, 100)
        val stage = stageText(item.stage)
        var text = when {
            item.state == "queued" -> getString(R.string.dl_state_queued)
            measurable -> "$stage \u00b7 $percent%"
            else -> stage
        }
        if (waiting > 0) text += " \u00b7 " + getString(R.string.dlnotif_waiting, waiting)
        builder.setContentTitle(item.title)
            .setContentText(text)
            .setProgress(100, percent, !measurable)
            .addAction(0, getString(R.string.dl_cancel), cancelIntent())
        return builder.build()
    }

    /** A normal notification for a finished download (cancelled ones are not announced). */
    private fun announce(item: DownloadItem) {
        val text = when (item.state) {
            "done" -> getString(R.string.dl_state_done, item.path)
            "error" -> getString(R.string.dl_state_error, item.error)
            else -> return
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (item.state == "done") android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(item.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        notificationManager().notify(FINISHED_BASE + item.id, notification)
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4101
        private const val FINISHED_BASE = 4200
        private const val ACTION_CANCEL = "iecxiv.yewdroid.action.CANCEL_DOWNLOAD"
        private const val STOP_DELAY_MS = 4_000L
        private const val UPDATE_INTERVAL_MS = 500L

        /** Starts the service from the foreground (a user action); failures are ignored. */
        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            }
        }
    }
}
