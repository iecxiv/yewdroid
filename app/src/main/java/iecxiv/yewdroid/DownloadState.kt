package iecxiv.yewdroid

import org.json.JSONArray
import org.json.JSONObject

/** One quality the user can pick for a download. */
data class FormatOption(
    val id: String,
    val label: String,
    val sizeBytes: Long = 0L,
    val recommended: Boolean = false
)

/**
 * The format picker: loading, failed ([error]) or ready ([options]). [encoder] is the name of the
 * encoding preset audio downloads will be converted with (empty when they are kept as downloaded).
 */
data class FormatsState(
    val ytid: String,
    val title: String,
    val kind: String,
    val loading: Boolean = false,
    val options: List<FormatOption> = emptyList(),
    val error: String = "",
    val encoder: String = ""
)

/** A queued, running or finished download. [kind] is "audio" or "video". */
data class DownloadItem(
    val id: Int,
    val title: String,
    val kind: String,
    val label: String,
    val state: String,
    val percent: Float,
    val stage: String,
    val path: String,
    val error: String
) {
    val active: Boolean get() = state == "queued" || state == "running"
    val finished: Boolean get() = !active
}

data class DownloadsState(val items: List<DownloadItem> = emptyList())

/** Parses the JSON produced by android_downloads.snapshot(). */
fun parseDownloadsState(raw: String): DownloadsState {
    val array = JSONObject(raw).optJSONArray("items") ?: JSONArray()
    return DownloadsState(List(array.length()) { index ->
        val item = array.getJSONObject(index)
        DownloadItem(
            id = item.optInt("id"),
            title = item.optString("title"),
            kind = item.optString("kind"),
            label = item.optString("label"),
            state = item.optString("state"),
            percent = item.optDouble("percent", 0.0).toFloat(),
            stage = item.optString("stage"),
            path = item.optString("path"),
            error = item.optString("error")
        )
    })
}

/** Parses the JSON produced by android_downloads.formats() for the given [kind]. */
fun parseFormats(raw: String, ytid: String, title: String, kind: String): FormatsState {
    val json = JSONObject(raw)
    val array = json.optJSONArray(if (kind == "audio") "audio" else "video") ?: JSONArray()
    val options = List(array.length()) { index ->
        val item = array.getJSONObject(index)
        FormatOption(
            id = item.optString("id"),
            label = item.optString("label"),
            sizeBytes = item.optLong("size"),
            recommended = item.optBoolean("recommended")
        )
    }
    return FormatsState(
        ytid, title, kind,
        options = options,
        error = json.optString("error"),
        encoder = if (kind == "audio") json.optString("encoder") else ""
    )
}
