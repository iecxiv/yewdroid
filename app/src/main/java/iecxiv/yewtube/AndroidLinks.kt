package iecxiv.yewtube

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Launches only explicit user-requested YouTube link actions. */
object AndroidLinks {
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun perform(context: Context, action: String, url: String): String {
        require(action == "open" || action == "share") { "Accion no admitida" }
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && uri.host == "www.youtube.com" &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.path in setOf("/watch", "/playlist")) { "Enlace de YouTube no valido" }
        val intent = if (action == "open") {
            Intent(Intent.ACTION_VIEW, uri)
        } else {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            Intent.createChooser(send, "Compartir enlace de YewTube")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val task = FutureTask<String> {
            try {
                context.applicationContext.startActivity(intent)
            } catch (error: ActivityNotFoundException) {
                throw IllegalStateException("No hay una aplicacion compatible para esta accion", error)
            }
            if (action == "open") "Solicitud de apertura enviada a Android."
            else "Selector de compartir solicitado. El envio se confirma en la app elegida."
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run()
        else check(main.post(task)) { "No se pudo solicitar la accion a Android" }
        try {
            return task.get(5, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw IllegalStateException(error.cause?.message ?: "La accion Android fallo", error.cause)
        } catch (error: Exception) {
            task.cancel(false)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
    }
}
