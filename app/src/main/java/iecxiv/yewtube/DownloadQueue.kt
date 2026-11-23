package iecxiv.yewtube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.chaquo.python.Python
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Named public bridge for Chaquopy calls from the download thread. */
class DownloadProgress(
    private val stopping: () -> Boolean,
    private val show: (String, Int) -> Unit
) {
    private var lastUpdate = 0L
    fun cancelled(): Boolean = stopping()
    fun update(title: String, percent: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdate >= 1000 || percent == 100) {
            lastUpdate = now
            show(title, percent)
        }
    }
}

object DownloadQueue {
    const val JOB = 7301
    const val NOTICE = 7302
    private const val CHANNEL = "yewtube_downloads"
    private const val UNIQUE = "yewtube_persistent_downloads"

    @JvmStatic
    fun schedule(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 34) {
            scheduleUidt(app)
        } else {
            val request = OneTimeWorkRequest.Builder(DownloadQueueWorker::class.java)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
        }
    }

    @RequiresApi(34)
    private fun scheduleUidt(context: Context) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val job = JobInfo.Builder(JOB, ComponentName(context, DownloadJobService::class.java))
            .setUserInitiated(true).setRequiredNetwork(request).setPersisted(true)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        check(context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS) {
            "Android no admitio la descarga. Abre YewTube y usa downloads resume."
        }
    }

    @JvmStatic
    fun cancel(context: Context) {
        val app = context.applicationContext
        Python.getInstance().getModule("android_download_queue")
            .callAttr("cancel", app.filesDir.absolutePath)
        if (Build.VERSION.SDK_INT >= 34) app.getSystemService(JobScheduler::class.java).cancel(JOB)
        WorkManager.getInstance(app).cancelUniqueWork(UNIQUE).result.get()
    }

    fun execute(context: Context, progress: DownloadProgress): String =
        Python.getInstance().getModule("android_download_queue")
            .callAttr("run", context.filesDir.absolutePath, progress).toString()

    fun notification(context: Context, title: String, percent: Int): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Descargas de YewTube", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getBroadcast(context, 0, Intent(context, DownloadCancelReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Descargas de YewTube").setContentText(title.take(150))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar cola", cancel).build()
    }
}

@RequiresApi(34)
class DownloadJobService : JobService() {
    private val tokens = IdentityHashMap<JobParameters, AtomicBoolean>()
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        val stop = AtomicBoolean(false)
        synchronized(tokens) { tokens[params] = stop }
        setNotification(params, DownloadQueue.NOTICE,
            DownloadQueue.notification(this, "Preparando cola", -1), JOB_END_NOTIFICATION_POLICY_REMOVE)
        val progress = DownloadProgress({ stop.get() }) { title, percent ->
            if (!stop.get()) getSystemService(NotificationManager::class.java)
                .notify(DownloadQueue.NOTICE, DownloadQueue.notification(this, title, percent))
        }
        Thread({
            var retry = false
            try {
                retry = DownloadQueue.execute(this, progress) == "retry"
            } catch (error: Exception) {
                Log.e("YewTubeDownloads", "Cola guardada; usa downloads resume", error)
            } finally {
                main.post {
                    synchronized(tokens) { tokens.remove(params) }
                    if (!stop.get()) jobFinished(params, retry)
                }
            }
        }, "yewtube-downloads").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(tokens) { tokens.remove(params) }?.set(true)
        return true
    }

    override fun onDestroy() {
        synchronized(tokens) { tokens.values.forEach { it.set(true) }; tokens.clear() }
        super.onDestroy()
    }
}

/** Android 8-13 foreground WorkManager fallback. */
class DownloadQueueWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private fun foreground(title: String, percent: Int): ForegroundInfo {
        val notice = DownloadQueue.notification(applicationContext, title, percent)
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(DownloadQueue.NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(DownloadQueue.NOTICE, notice)
    }

    override fun doWork(): Result {
        return try {
            setForegroundAsync(foreground("Preparando cola", -1)).get()
            val progress = DownloadProgress({ isStopped }) { title, percent ->
                if (!isStopped) setForegroundAsync(foreground(title, percent))
            }
            when (DownloadQueue.execute(applicationContext, progress)) {
                "retry", "stopped" -> Result.retry()
                else -> Result.success()
            }
        } catch (error: Exception) {
            Log.e("YewTubeDownloads", "Cola guardada; usa downloads resume", error)
            Result.failure()
        }
    }
}

class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                DownloadQueue.cancel(context)
            } catch (error: Exception) {
                Log.e("YewTubeDownloads", "No se pudo cancelar la cola", error)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
