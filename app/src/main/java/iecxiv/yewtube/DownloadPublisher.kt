package iecxiv.yewtube

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

/** Publishes only complete files. Call from the Python worker, not the UI thread. */
object DownloadPublisher {
    @JvmStatic
    fun publish(context: Context, sourcePath: String): String {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Public Downloads requires Android 10 or newer" }
        val source = File(sourcePath).canonicalFile
        require(source.isFile && source.length() > 0) { "Missing or empty source file" }
        val expectedSize = source.length()
        val extension = source.extension.lowercase(Locale.ROOT)
        val mime = when (extension) {
            "m4a" -> "audio/mp4"
            "opus", "ogg", "oga" -> "audio/ogg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/YewTube/"
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, values)) { "Cannot create public download" }
        try {
            val copied = source.inputStream().use { input ->
                checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                    val count = input.copyTo(output, 256 * 1024)
                    output.flush()
                    count
                }
            }
            check(copied == expectedSize && source.length() == expectedSize) { "Incomplete public copy" }
            val displayName = resolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: source.name
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(uri, ready, null, null) == 1) { "Cannot publish download" }
            return relativePath + displayName
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
