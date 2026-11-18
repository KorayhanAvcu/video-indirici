package com.korayhan.videoindirici

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Yakalanan bir akış/dosya türü. */
enum class Kind { FILE, HLS, DASH }

/** m3u8 listesinin içeriği: master mı, hangi kaliteler, hangi alt listeler. */
data class M3u8Info(
    val master: Boolean,
    val heights: List<Int> = emptyList(),
    val variants: List<String> = emptyList(),
    val renditions: List<String> = emptyList(),
    val subtitles: List<String> = emptyList(),
)

/**
 * Masaüstü eklentisinin (detect.js) ve app.py yardımcılarının Kotlin karşılığı.
 * Android'e bağımlı değildir; birim testleriyle derlemede doğrulanır.
 */
object Detect {
    private val VIDEO_EXT = mapOf(
        "mp4" to Kind.FILE, "m4v" to Kind.FILE, "webm" to Kind.FILE, "mov" to Kind.FILE,
        "mkv" to Kind.FILE, "flv" to Kind.FILE, "m3u8" to Kind.HLS, "m3u" to Kind.HLS, "mpd" to Kind.DASH,
    )
    private val SUB_EXT = mapOf("vtt" to "vtt", "webvtt" to "vtt", "srt" to "srt", "ass" to "ass", "ssa" to "ass")

    // fMP4 / TS parçaları tek başına video değildir
    private val SEGMENT = Regex(
        """(?:^|[/_.-])(?:seg|segment|chunk|frag|fragment|part|sequence|init)[_-]?\d{0,6}\.(?:mp4|m4s|m4v|ts|webm)$""",
        RegexOption.IGNORE_CASE,
    )
    private val SUB_SEGMENT = Regex(
        """(?:^|[/_.-])(?:seg|segment|chunk|frag|fragment|part|sequence|filesequence)?[_-]?\d{1,6}\.(?:vtt|webvtt)$""",
        RegexOption.IGNORE_CASE,
    )
    private val SKIP_HOST = Regex("""(^|\.)googlevideo\.com$""")
    private val LANGS = mapOf(
        "tr" to "tr", "tur" to "tr", "turkish" to "tr", "turkce" to "tr", "türkçe" to "tr", "trsub" to "tr",
        "en" to "en", "eng" to "en", "english" to "en", "ingilizce" to "en",
    )
    private val TR = Locale.forLanguageTag("tr")

    fun isHttp(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    fun hostOf(url: String): String {
        if (!url.contains("://")) return ""
        return url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
    }

    fun originOf(url: String): String {
        if (!url.contains("://")) return ""
        val scheme = url.substringBefore("://").lowercase(Locale.ROOT)
        val auth = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return "$scheme://$auth"
    }

    fun pathOf(url: String): String {
        val noQuery = url.substringBefore('#').substringBefore('?')
        val rest = noQuery.substringAfter("://", noQuery)
        val slash = rest.indexOf('/')
        return if (slash < 0) "/" else rest.substring(slash)
    }

    fun fileOf(url: String) = pathOf(url).substringAfterLast('/')

    fun extOf(url: String): String {
        val f = fileOf(url)
        return if ('.' in f) f.substringAfterLast('.').lowercase(Locale.ROOT) else ""
    }

    /** Göreli adresi tam adrese çevirir. */
    fun resolve(base: String, rel: String): String {
        val r = rel.trim()
        if (isHttp(r)) return r
        if (r.startsWith("//")) return base.substringBefore("://") + ":" + r
        if (r.startsWith("/")) return originOf(base) + r
        val dir = base.substringBefore('#').substringBefore('?').substringBeforeLast('/') + "/"
        return dir + r
    }

    /** Bir isteğin indirilecek video olup olmadığı (adres ve uzantıya göre). */
    fun classify(url: String): Kind? {
        if (!isHttp(url)) return null
        if (SKIP_HOST.containsMatchIn(hostOf(url))) return null
        val kind = VIDEO_EXT[extOf(url)] ?: return null
        if (kind == Kind.FILE && SEGMENT.containsMatchIn("/" + fileOf(url))) return null
        return kind
    }

    /** Altyazı dosyası mı? (biçim, dil) */
    fun classifySub(url: String): Pair<String, String>? {
        if (!isHttp(url)) return null
        val format = SUB_EXT[extOf(url)] ?: return null
        return format to guessLang(url)
    }

    /** Adresten altyazı dilini tahmin eder: "tr", "en" ya da "". Dosya adına yakın kelimeler önceliklidir. */
    fun guessLang(url: String): String {
        val raw = pathOf(url) + " " + url.substringAfter('?', "").substringBefore('#')
        val text = try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            raw
        }.lowercase(TR)
        val words = text.split(Regex("""[^a-zçğıöşüi̇]+""")).filter { it.isNotEmpty() }.reversed()
        for (w in words) LANGS[w]?.let { return it }
        return ""
    }

    /** Metnin başı m3u8 / altyazı gibi görünüyor mu? (uzantısı gizlenmiş akışlar) */
    fun sniffText(head: String): String {
        val t = head.removePrefix("\uFEFF").trimStart()
        return when {
            t.startsWith("#EXTM3U") -> "hls"
            t.startsWith("WEBVTT") -> "vtt"
            t.startsWith("[Script Info]") -> "ass"
            Regex("""^\d+\s*\r?\n\d\d:\d\d:\d\d,\d{3} --> """).containsMatchIn(t) -> "srt"
            else -> ""
        }
    }

    private fun m3u8Attrs(line: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val body = line.substringAfter(':')
        Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""").findAll(body).forEach { m ->
            out[m.groupValues[1]] = if (m.groupValues[2].startsWith("\"")) m.groupValues[3] else m.groupValues[2]
        }
        return out
    }

    fun parseM3u8(text: String, base: String): M3u8Info {
        val lines = text.lines().map { it.trim() }
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) return M3u8Info(master = false)
        val heights = mutableListOf<Int>()
        val variants = mutableListOf<String>()
        for ((i, l) in lines.withIndex()) {
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            Regex("""RESOLUTION=\d+x(\d+)""").find(l)?.let { heights += it.groupValues[1].toInt() }
            lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { variants += resolve(base, it) }
        }
        val renditions = mutableListOf<String>()
        val subtitles = mutableListOf<String>()
        for (l in lines.filter { it.startsWith("#EXT-X-MEDIA:") }) {
            val a = m3u8Attrs(l)
            a["URI"]?.takeIf { it.isNotEmpty() }?.let { renditions += resolve(base, it) }
            if (a["TYPE"].equals("SUBTITLES", true)) subtitles += (a["NAME"] ?: a["LANGUAGE"] ?: "?")
        }
        return M3u8Info(true, heights.distinct().sortedDescending(), variants, renditions, subtitles)
    }

    /** İndirilecek en iyi adayı seçer: kaliteli master m3u8 > m3u8 > dash > dosya; eşitse en yenisi. */
    fun <T> pickBest(items: List<T>, kind: (T) -> Kind, info: (T) -> M3u8Info?, hidden: (T) -> Boolean): T? {
        fun score(x: T): Int {
            val i = info(x)
            return when (kind(x)) {
                Kind.HLS -> if (i?.master == true) 3000 + (i.heights.maxOrNull() ?: 0) else 2000
                Kind.DASH -> 1500
                Kind.FILE -> 1000
            }
        }
        return items.withIndex().filter { !hidden(it.value) }
            .maxWithOrNull(compareBy<IndexedValue<T>>({ score(it.value) }, { it.index }))?.value
    }

    /** Altyazılardan gönderilecekleri seçer: HLS parçaları ve kalabalık klasörler (yine parça) elenir. */
    fun <T> pickSubs(subs: List<T>, url: (T) -> String, format: (T) -> String, max: Int = 6): List<T> {
        val seen = mutableSetOf<String>()
        val groups = linkedMapOf<String, MutableList<T>>()
        for (s in subs) {
            val u = url(s)
            if (!seen.add(u)) continue
            if (SUB_SEGMENT.containsMatchIn("/" + fileOf(u))) continue
            val dir = originOf(u) + pathOf(u).substringBeforeLast('/') + "/"
            groups.getOrPut("$dir|${format(s)}") { mutableListOf() } += s
        }
        return groups.values.filter { it.size <= 3 }.flatten().take(max)
    }

    /** Paylaşılan metnin içinden ilk http(s) linkini çıkarır. */
    fun extractUrl(text: String): String? =
        Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', ';', '!', '?')

    fun extractUrls(text: String): List<String> =
        Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ';', '!', '?') }.distinct().toList()

    /**
     * Sunucu video yerine kısa bir hata metni ("security error", 403 sayfası) döndüyse o metin;
     * gerçek medya ikili veridir ve null döner.
     */
    fun junkReply(data: ByteArray): String? {
        if (data.size > 64 * 1024) return null
        val head = data.copyOfRange(0, minOf(4096, data.size))
        if (head.any { it == 0.toByte() }) return null
        val text = strictUtf8(data) ?: return null
        val clean = text.replace(Regex("<[^>]+>"), " ").replace(Regex("""\s+"""), " ").trim()
        return clean.take(120).ifEmpty { "(boş yanıt)" }
    }

    private fun strictUtf8(data: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** Altyazı baytlarını metne çevirir; Türkçe Windows-1254 SRT'leri de okur. Altyazı değilse null. */
    fun decodeSub(data: ByteArray): String? {
        val text = (strictUtf8(data) ?: String(data, Charset.forName("windows-1254"))).removePrefix("\uFEFF")
        val head = text.trimStart().take(2000)
        return if (head.startsWith("WEBVTT") || head.startsWith("[Script Info]") || "-->" in head) text else null
    }

    private val SKIP_HEADERS = setOf(
        "cookie", "host", "connection", "content-length", "content-type", "accept-encoding", "range", "if-range",
        "if-none-match", "if-modified-since", "upgrade", "transfer-encoding", "te", "keep-alive", "expect",
        "proxy-connection", "proxy-authorization",
    )
    private val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")

    /**
     * Tarayıcının isteğe eklediği başlıklar (IDM gibi aynen kopyalanır). Android WebView'in kendi eklediği
     * "X-Requested-With: <paket adı>" atılır; sayfanın eklediği "XMLHttpRequest" kalır.
     */
    fun cleanHeaders(raw: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((k, v) in raw) {
            val lk = k.lowercase(Locale.ROOT)
            if (!HEADER_NAME.matches(k) || lk in SKIP_HEADERS || lk.startsWith(":")) continue
            if (v.length > 4096 || v.any { it == '\r' || it == '\n' || it == '\u0000' }) continue
            if (lk == "x-requested-with" && v != "XMLHttpRequest") continue
            out[canonHeader(k)] = v
        }
        return out
    }

    fun canonHeader(name: String) = name.lowercase(Locale.ROOT).split('-')
        .joinToString("-") { p -> p.replaceFirstChar { it.uppercaseChar() } }

    /** host → "a=1; b=2" (CookieManager biçimi) listesinden yt-dlp'nin okuduğu Netscape cookies.txt. */
    fun netscapeCookies(byHost: Map<String, String>, nowSec: Long): String {
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        val expiry = nowSec + 86400
        for ((host, header) in byHost) {
            if (host.isEmpty()) continue
            for (pair in header.split(';')) {
                val p = pair.trim()
                if (!p.contains('=')) continue
                val name = p.substringBefore('=').trim()
                val value = p.substringAfter('=').trim()
                if (name.isEmpty() || name.any { it == '\t' || it == '\n' } || value.any { it == '\t' || it == '\n' }) continue
                sb.append(host).append("\tFALSE\t/\tFALSE\t").append(expiry).append('\t')
                    .append(name).append('\t').append(value).append('\n')
            }
        }
        return sb.toString()
    }

    /** "Cookie:" başlığı için: cookies.txt içinden bir hosta ait çerezler. */
    fun cookieHeaderFor(netscape: String, host: String): String =
        netscape.lineSequence().filter { !it.startsWith("#") && it.isNotBlank() }.map { it.split('\t') }
            .filter { it.size >= 7 && (it[0] == host || (it[0].startsWith(".") && host.endsWith(it[0]))) }
            .joinToString("; ") { "${it[5]}=${it[6]}" }

    private val BAD_CHARS = Regex("""[<>:"/\\|?*\u0000-\u001f]""")

    /** Sayfa başlığını güvenli dosya adına çevirir (yt-dlp şablonu için % kaçırılır). */
    fun safeName(title: String?): String =
        (title ?: "").replace(BAD_CHARS, " ").replace(Regex("""\s+"""), " ").trim(' ', '.')
            .take(110).trim(' ', '.').replace("%", "%%")

    /** yt-dlp biçim seçici: kalite sınırı (ör. 720) ya da en iyisi. */
    fun formatSelector(height: Int?): String {
        val h = if (height != null) "[height<=$height]" else ""
        return "bestvideo$h+bestaudio/best$h/best"
    }

    /** İndirme hatasını kısa ve anlaşılır Türkçe nota çevirir. */
    fun friendlyError(raw: String): String {
        val m = raw.lowercase(Locale.ROOT)
        val first = raw.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("ERROR:") }
            ?.removePrefix("ERROR:")?.trim() ?: raw.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: raw
        val hint = when {
            "cloudflare" in m || "http error 403" in m || "unsupported url" in m ->
                " → Bu site korumalı ya da tanınmıyor: \"Tarayıcıda aç\" ile sayfayı açıp videoyu oynat, oradan indir."
            "timed out" in m || "curl: (28)" in m -> " → Sunucuya bağlanılamadı; internetini ya da siteyi kontrol et."
            "drm" in m -> " → DRM korumalı yayın indirilemez."
            else -> ""
        }
        return (first.take(220) + hint).trim()
    }

    /** yt-dlp ilerleme satırından hız: "... at 2.31MiB/s ETA 00:12" → "2.31MiB/s" */
    fun speedOf(line: String): String =
        Regex("""at\s+([\d.]+\s*[KMG]?i?B/s)""").find(line)?.groupValues?.get(1)?.replace(" ", "") ?: ""

    val SUB_LANGS = "tr.*,tur.*,en.*,eng.*,und"
    val ISO3 = mapOf("tr" to "tur", "en" to "eng")
    val VIDEO_FILE_EXT = setOf("mp4", "m4v", "mov", "mkv", "webm")
}
