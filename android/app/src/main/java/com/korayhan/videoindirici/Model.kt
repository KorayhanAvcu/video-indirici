package com.korayhan.videoindirici

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

enum class Status { QUEUED, RUNNING, PROCESSING, DONE, ERROR, CANCELLED }

/** Sayfada yakalanan altyazı: içerik varsa (tarayıcının aldığı) tekrar indirilmez. */
data class SubSpec(
    val url: String,
    val format: String,
    val lang: String,
    val headers: Map<String, String> = emptyMap(),
    val content: ByteArray? = null,
)

data class SavedFile(val name: String, val uri: String, val mime: String)

data class DownloadJob(
    val id: String,
    val url: String,
    val title: String = "",
    val pageUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val cookies: String = "",
    val subs: List<SubSpec> = emptyList(),
    val height: Int? = null,
    val audio: Boolean = false,
    val status: Status = Status.QUEUED,
    val percent: Float = 0f,
    val eta: Long = -1,
    val speed: String = "",
    val message: String = "",
    val files: List<SavedFile> = emptyList(),
    val created: Long = System.currentTimeMillis(),
) {
    val active get() = status == Status.QUEUED || status == Status.RUNNING || status == Status.PROCESSING
}

/** Kalite seçimi: en iyi / 1080 / 720 / 480 / sadece ses. */
enum class Quality(val label: String, val height: Int?, val audio: Boolean) {
    BEST("En iyi", null, false),
    P1080("1080p", 1080, false),
    P720("720p", 720, false),
    P480("480p", 480, false),
    MP3("MP3", null, true),
}

object JobJson {
    private fun map(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    private fun obj(m: Map<String, String>) = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }

    fun toJson(j: DownloadJob) = JSONObject().apply {
        put("id", j.id); put("url", j.url); put("title", j.title); put("pageUrl", j.pageUrl)
        put("headers", obj(j.headers)); put("cookies", j.cookies)
        put("height", j.height ?: JSONObject.NULL); put("audio", j.audio)
        put("status", j.status.name); put("percent", j.percent.toDouble()); put("message", j.message)
        put("created", j.created)
        put("subs", JSONArray().apply {
            j.subs.forEach { s ->
                put(JSONObject().apply {
                    put("url", s.url); put("format", s.format); put("lang", s.lang); put("headers", obj(s.headers))
                    s.content?.let { put("content", Base64.encodeToString(it, Base64.NO_WRAP)) }
                })
            }
        })
        put("files", JSONArray().apply {
            j.files.forEach { f -> put(JSONObject().apply { put("name", f.name); put("uri", f.uri); put("mime", f.mime) }) }
        })
    }

    fun fromJson(o: JSONObject): DownloadJob {
        val subs = o.optJSONArray("subs")?.let { a ->
            (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                SubSpec(
                    s.optString("url"), s.optString("format"), s.optString("lang"), map(s.optJSONObject("headers")),
                    s.optString("content").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) },
                )
            }
        } ?: emptyList()
        val files = o.optJSONArray("files")?.let { a ->
            (0 until a.length()).map { i ->
                val f = a.getJSONObject(i); SavedFile(f.optString("name"), f.optString("uri"), f.optString("mime"))
            }
        } ?: emptyList()
        return DownloadJob(
            id = o.getString("id"), url = o.getString("url"), title = o.optString("title"),
            pageUrl = o.optString("pageUrl"), headers = map(o.optJSONObject("headers")), cookies = o.optString("cookies"),
            subs = subs, height = if (o.isNull("height")) null else o.optInt("height"), audio = o.optBoolean("audio"),
            status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.ERROR),
            percent = o.optDouble("percent", 0.0).toFloat(), message = o.optString("message"),
            files = files, created = o.optLong("created", System.currentTimeMillis()),
        )
    }
}
