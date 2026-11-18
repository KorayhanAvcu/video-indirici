package com.korayhan.videoindirici

import android.util.Base64
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Captured(
    val url: String,
    val kind: Kind,
    val headers: Map<String, String>,
    val time: Long = System.currentTimeMillis(),
    val info: M3u8Info? = null,
    val variantOf: Boolean = false,
    val sniffed: Boolean = false,
)

data class CapturedSub(
    val url: String,
    val format: String,
    val lang: String,
    val headers: Map<String, String>,
    val content: ByteArray? = null,
)

/** Uygulama içi tarayıcının gördüğü videolar ve altyazılar (masaüstü eklentisindeki background.js karşılığı). */
class Sniffer {
    val items = MutableStateFlow<List<Captured>>(emptyList())
    val subs = MutableStateFlow<List<CapturedSub>>(emptyList())

    /** host → o hosttan görülen son adres (çerezleri doğru yoldan almak için) */
    val hostUrls = ConcurrentHashMap<String, String>()
    private val urlHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val pool = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()

    fun reset() {
        synchronized(lock) {
            items.value = emptyList()
            subs.value = emptyList()
        }
        hostUrls.clear()
        urlHeaders.clear()
    }

    /** WebView her isteği buraya bildirir (arka plan iş parçacığında). */
    fun onRequest(url: String, headers: Map<String, String>, pageUrl: String) {
        if (!Detect.isHttp(url)) return
        if (urlHeaders.size > 400) urlHeaders.clear()
        val h = headers.toMutableMap()
        if (h.keys.none { it.equals("Referer", true) } && pageUrl.isNotEmpty()) h["Referer"] = pageUrl
        urlHeaders[url] = h
        Detect.classifySub(url)?.let { (format, lang) ->
            hostUrls[Detect.hostOf(url)] = url
            addSub(CapturedSub(url, format, lang, h))
            return
        }
        val kind = Detect.classify(url) ?: return
        hostUrls[Detect.hostOf(url)] = url
        addItem(Captured(url, kind, h))
    }

    /** sniff_android.js'in mesajı: {kind, url, text|b64} */
    fun onMessage(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val url = o.optString("url")
        if (!Detect.isHttp(url)) return
        val kind = o.optString("kind")
        val headers = urlHeaders[url] ?: emptyMap()
        hostUrls[Detect.hostOf(url)] = url
        val content: ByteArray? = when {
            o.has("b64") -> runCatching { Base64.decode(o.getString("b64"), Base64.DEFAULT) }.getOrNull()
            o.has("text") -> o.getString("text").toByteArray()
            else -> null
        }
        when (kind) {
            "hls" -> {
                val info = content?.let { Detect.parseM3u8(String(it), url) }
                addItem(Captured(url, Kind.HLS, headers, info = info, sniffed = true))
            }
            "vtt", "srt", "ass" -> addSub(CapturedSub(url, kind, Detect.guessLang(url), headers, content))
        }
    }

    private fun addSub(s: CapturedSub) = synchronized(lock) {
        val old = subs.value.find { it.url == s.url }
        subs.value = if (old == null) {
            (subs.value + s).takeLast(20)
        } else {
            // Önce ağdan (başlıklarıyla), sonra sayfadan (içeriğiyle) görülebilir: birleştir
            subs.value.map {
                if (it.url == s.url) it.copy(content = s.content ?: it.content, headers = it.headers.ifEmpty { s.headers }) else it
            }
        }
    }

    private fun addItem(c: Captured) {
        synchronized(lock) {
            val old = items.value.find { it.url == c.url }
            val hidden = items.value.any { m ->
                m.info?.master == true && (c.url in m.info.variants || c.url in m.info.renditions)
            }
            items.value = if (old == null) {
                (items.value + c.copy(variantOf = hidden)).takeLast(40)
            } else {
                items.value.map {
                    if (it.url == c.url) it.copy(info = c.info ?: it.info, sniffed = it.sniffed || c.sniffed,
                        headers = it.headers.ifEmpty { c.headers }) else it
                }
            }
            c.info?.let { applyMaster(c.url, it) }
        }
        // m3u8'in içeriği koklamayla gelmediyse kendimiz okuyup kaliteleri çıkaralım (biraz bekleyip)
        if (c.kind == Kind.HLS && c.info == null) {
            pool.schedule(Runnable { inspect(c.url) }, 1500, TimeUnit.MILLISECONDS)
        }
    }

    private fun applyMaster(url: String, info: M3u8Info) {
        if (!info.master) return
        val hide = (info.variants + info.renditions).toSet()
        items.value = items.value.map { if (it.url != url && it.url in hide) it.copy(variantOf = true) else it }
    }

    private fun inspect(url: String) {
        val item = items.value.find { it.url == url } ?: return
        if (item.info != null) return
        val text = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            item.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            if (c.responseCode >= 400) null else c.inputStream.use { it.readBytes() }.take(1 shl 20).toByteArray()
        }.getOrNull() ?: return
        val body = String(text)
        if (Detect.sniffText(body.take(64)) != "hls") return
        val info = Detect.parseM3u8(body, url)
        synchronized(lock) {
            items.value = items.value.map { if (it.url == url) it.copy(info = info) else it }
            applyMaster(url, info)
        }
    }

    fun visible(): List<Captured> = items.value.filter { !it.variantOf }

    fun best(): Captured? = Detect.pickBest(items.value, { it.kind }, { it.info }, { it.variantOf })

    fun pickedSubs(): List<CapturedSub> = Detect.pickSubs(subs.value, { it.url }, { it.format })
}
