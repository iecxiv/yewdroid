package iecxiv.yewdroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

object AndroidClipboard {
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun copy(context: Context, text: String) {
        require(text.isNotEmpty()) { "Nothing to copy" }
        val task = FutureTask<Unit> {
            val clipboard = checkNotNull(
                context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ) { "Clipboard unavailable" }
            clipboard.setPrimaryClip(ClipData.newPlainText("YewDroid", text))
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run()
        else check(main.post(task)) { "Cannot access clipboard on main thread" }
        try {
            task.get(5, TimeUnit.SECONDS)
        } catch (error: Exception) {
            task.cancel(false)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
    }
}
