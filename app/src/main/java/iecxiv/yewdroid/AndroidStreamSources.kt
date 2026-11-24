package iecxiv.yewdroid

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.json.JSONObject

@UnstableApi
object AndroidStreamSources {
    private data class Entry(val headers: Map<String, String>, val mime: String)
    private val pending = LinkedHashMap<String, Entry>()

    @JvmStatic
    @Synchronized
    fun register(url: String, payload: String) {
        val json = JSONObject(payload)
        val input = json.optJSONObject("headers") ?: JSONObject()
        val headers = mutableMapOf<String, String>()
        val keys = input.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = input.getString(key)
            if (key.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) &&
                !value.contains('\r') && !value.contains('\n') &&
                key.lowercase() !in setOf("host", "content-length", "connection", "range")) {
                headers[key] = value
            }
        }
        pending[url] = Entry(headers.toMap(), json.optString("mime", ""))
        while (pending.size > 16) pending.remove(pending.keys.first())
    }

    @Synchronized
    private fun take(url: String): Entry = pending.remove(url) ?: Entry(emptyMap(), "")

    fun report(error: PlaybackException) {
        var cause: Throwable? = error
        var code: Int? = null
        repeat(10) {
            val current = cause
            if (current is HttpDataSource.InvalidResponseCodeException) code = current.responseCode
            cause = current?.cause
        }
        val http = code?.let { "; HTTP $it" } ?: ""
        TerminalHub.onWrite("\n[stream] ${error.errorCodeName}$http\n")
    }

    class Factory(context: Context) : MediaSource.Factory {
        private val app = context.applicationContext
        private var drm: DrmSessionManagerProvider? = null
        private var policy: LoadErrorHandlingPolicy? = null

        override fun getSupportedTypes(): IntArray = DefaultMediaSourceFactory(app).supportedTypes

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
            drm = provider
            return this
        }

        override fun setLoadErrorHandlingPolicy(value: LoadErrorHandlingPolicy): MediaSource.Factory {
            policy = value
            return this
        }

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val url = mediaItem.localConfiguration?.uri?.toString().orEmpty()
            val entry = take(url)
            val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(entry.headers)
            val factory = DefaultMediaSourceFactory(app)
                .setDataSourceFactory(DefaultDataSource.Factory(app, http))
            drm?.let { factory.setDrmSessionManagerProvider(it) }
            policy?.let { factory.setLoadErrorHandlingPolicy(it) }
            val item = if (entry.mime.isBlank()) mediaItem
                else mediaItem.buildUpon().setMimeType(entry.mime).build()
            return factory.createMediaSource(item)
        }
    }
}
