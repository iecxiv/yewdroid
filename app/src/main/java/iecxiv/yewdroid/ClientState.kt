package iecxiv.yewdroid

import org.json.JSONArray
import org.json.JSONObject

/** One result row: a video (mode "normal") or a YouTube playlist (mode "ytpl"). */
data class ClientItem(
    val n: Int,
    val id: String,
    val title: String,
    val lengthSec: Int = 0,
    val author: String = "",
    val views: String = "",
    val date: String = "",
    val count: String = ""
)

data class ClientState(
    val mode: String = "normal",
    val page: Int = 0,
    val pages: Int = 0,
    val total: Int = 0,
    val items: List<ClientItem> = emptyList(),
    val message: String = "",
    val busy: Boolean = false,
    val ready: Boolean = false,
    /** Counts the snapshots taken, so an identical list read again is still told apart. */
    val seq: Int = 0
)

/** One track of the playback queue. */
data class QueueItem(
    val id: String,
    val title: String,
    val lengthSec: Int = 0,
    val author: String = ""
)

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val index: Int = 0
)

/** A saved local playlist; [n] is the number the open/play/rmp commands use. */
data class PlaylistItem(
    val n: Int,
    val name: String,
    val count: Int = 0,
    val duration: String = ""
)

data class PlaylistsState(val items: List<PlaylistItem> = emptyList())

/** Parses the JSON produced by yt_client.snapshot(). */
fun parseClientState(raw: String): ClientState {
    val json = JSONObject(raw)
    val array = json.optJSONArray("items") ?: JSONArray()
    val items = List(array.length()) { index ->
        val item = array.getJSONObject(index)
        ClientItem(
            n = item.optInt("n"),
            id = item.optString("id"),
            title = item.optString("title"),
            lengthSec = item.optInt("length"),
            author = item.optString("author"),
            views = item.optString("views"),
            date = item.optString("date"),
            count = item.optString("count")
        )
    }
    return ClientState(
        mode = json.optString("mode", "normal"),
        page = json.optInt("page"),
        pages = json.optInt("pages"),
        total = json.optInt("total"),
        items = items,
        message = json.optString("message"),
        busy = false,
        ready = true,
        seq = json.optInt("seq")
    )
}

/** Parses the JSON produced by yt_client.queue(). */
fun parseQueueState(raw: String): QueueState {
    val json = JSONObject(raw)
    val array = json.optJSONArray("items") ?: JSONArray()
    val items = List(array.length()) { index ->
        val item = array.getJSONObject(index)
        QueueItem(
            id = item.optString("id"),
            title = item.optString("title"),
            lengthSec = item.optInt("length"),
            author = item.optString("author")
        )
    }
    return QueueState(items = items, index = json.optInt("index"))
}

/** Parses the JSON produced by yt_client.playlists(). */
fun parsePlaylistsState(raw: String): PlaylistsState {
    val array = JSONObject(raw).optJSONArray("items") ?: JSONArray()
    return PlaylistsState(List(array.length()) { index ->
        val item = array.getJSONObject(index)
        PlaylistItem(
            n = item.optInt("n"),
            name = item.optString("name"),
            count = item.optInt("count"),
            duration = item.optString("duration")
        )
    })
}
