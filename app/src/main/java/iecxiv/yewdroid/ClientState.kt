package iecxiv.yewdroid

import org.json.JSONArray
import org.json.JSONObject

/** One row of the native client: a video (mode "normal") or a YouTube playlist (mode "ytpl"). */
data class ClientItem(
    val n: Int,
    val id: String,
    val title: String,
    val lengthSec: Int,
    val author: String,
    val views: String,
    val date: String,
    val count: String
)

data class ClientState(
    val mode: String = "normal",
    val page: Int = 0,
    val pages: Int = 0,
    val total: Int = 0,
    val items: List<ClientItem> = emptyList(),
    val message: String = "",
    val busy: Boolean = false,
    val ready: Boolean = false
)

/** Reads the JSON produced by yt_client.snapshot(). */
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
        ready = true
    )
}
