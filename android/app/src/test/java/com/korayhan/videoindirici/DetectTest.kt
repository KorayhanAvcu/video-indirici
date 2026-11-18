package com.korayhan.videoindirici

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectTest {
    @Test fun classifiesVideosAndSkipsSegments() {
        assertEquals(Kind.HLS, Detect.classify("https://x.com/a/master.m3u8?t=1"))
        assertEquals(Kind.DASH, Detect.classify("https://x.com/a/manifest.mpd"))
        assertEquals(Kind.FILE, Detect.classify("https://media.animerank.tv/482/ep14.mp4?e=1&s=x"))
        assertNull(Detect.classify("https://x.com/a/segment-12.mp4"))
        assertNull(Detect.classify("https://x.com/a/seg_3.ts"))
        assertNull(Detect.classify("https://x.com/a/init.mp4"))
        assertNull(Detect.classify("https://rr1.googlevideo.com/videoplayback?x.mp4"))
        assertNull(Detect.classify("blob:https://x.com/123"))
        assertNull(Detect.classify("https://x.com/sub.vtt"))
    }

    @Test fun classifiesSubtitlesWithLanguage() {
        assertEquals("vtt" to "tr", Detect.classifySub("https://x.com/subs/turkce.vtt"))
        assertEquals("srt" to "en", Detect.classifySub("https://x.com/a/eng.srt?t=1"))
        assertEquals("ass" to "", Detect.classifySub("https://x.com/a/b.ass"))
        assertNull(Detect.classifySub("https://x.com/a.mp4"))
        assertEquals("en", Detect.guessLang("https://cdn.x/tr/video/altyazi_eng.vtt"))
        assertEquals("tr", Detect.guessLang("https://cdn.x/T%C3%BCrk%C3%A7e.srt"))
        assertEquals("tr", Detect.guessLang("https://cdn.x/sub?lang=tur"))
        assertEquals("", Detect.guessLang("https://cdn.x/a.vtt"))
    }

    @Test fun sniffsDisguisedContent() {
        assertEquals("hls", Detect.sniffText("\uFEFF#EXTM3U\n#EXT-X-VERSION:3"))
        assertEquals("vtt", Detect.sniffText("  WEBVTT\n\n00:00.000 --> 00:01.000"))
        assertEquals("srt", Detect.sniffText("1\r\n00:00:01,000 --> 00:00:02,000\r\nMerhaba"))
        assertEquals("ass", Detect.sniffText("[Script Info]\nTitle: x"))
        assertEquals("", Detect.sniffText("<!doctype html>"))
    }

    @Test fun parsesMasterPlaylist() {
        val txt = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Türkçe",LANGUAGE="tr",URI="subs/tr.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Japonca",LANGUAGE="ja",URI="/audio/ja.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720,SUBTITLES="subs"
            720.txt
            #EXT-X-STREAM-INF:BANDWIDTH=2,RESOLUTION=1920x1080
            https://cdn.y/1080.m3u8
        """.trimIndent()
        val r = Detect.parseM3u8(txt, "https://c.com/v/master.txt?tok=1")
        assertTrue(r.master)
        assertEquals(listOf(1080, 720), r.heights)
        assertEquals(listOf("https://c.com/v/720.txt", "https://cdn.y/1080.m3u8"), r.variants)
        assertEquals(listOf("https://c.com/v/subs/tr.m3u8", "https://c.com/audio/ja.m3u8"), r.renditions)
        assertEquals(listOf("Türkçe"), r.subtitles)
        assertFalse(Detect.parseM3u8("#EXTM3U\n#EXTINF:4,\na.ts", "https://c/").master)
    }

    data class C(val url: String, val kind: Kind, val info: M3u8Info? = null, val hidden: Boolean = false)

    @Test fun picksBestCandidate() {
        val items = listOf(
            C("https://a/big.mp4", Kind.FILE),
            C("https://a/720.m3u8", Kind.HLS, hidden = true),
            C("https://a/master.m3u8", Kind.HLS, M3u8Info(true, listOf(1080, 720))),
            C("https://a/other.m3u8", Kind.HLS),
        )
        assertEquals("https://a/master.m3u8", Detect.pickBest(items, { it.kind }, { it.info }, { it.hidden })?.url)
        assertEquals("https://a/big.mp4", Detect.pickBest(listOf(items[0]), { it.kind }, { it.info }, { it.hidden })?.url)
        assertNull(Detect.pickBest(emptyList<C>(), { it.kind }, { it.info }, { it.hidden }))
    }

    @Test fun picksSubtitlesWithoutSegments() {
        val subs = listOf(
            "https://c/s/sub_0.vtt", "https://c/s/segment-12.vtt", "https://c/s/tr.vtt", "https://c/s/en.vtt",
            "https://c/s/tr.vtt", "https://c/x/a.vtt", "https://c/x/b.vtt", "https://c/x/c.vtt", "https://c/x/d.vtt",
        )
        assertEquals(listOf("https://c/s/tr.vtt", "https://c/s/en.vtt"), Detect.pickSubs(subs, { it }, { "vtt" }))
    }

    @Test fun extractsSharedUrl() {
        assertEquals("https://anizm.net/yu-gi-oh-gx-14-bolum-izle",
            Detect.extractUrl("Şuna bak: https://anizm.net/yu-gi-oh-gx-14-bolum-izle."))
        assertNull(Detect.extractUrl("link yok"))
        assertEquals(2, Detect.extractUrls("https://a.com/1\nhttps://b.com/2 https://a.com/1").size)
    }

    @Test fun detectsJunkReplies() {
        assertEquals("security error", Detect.junkReply("security error".toByteArray()))
        assertEquals("403 Forbidden", Detect.junkReply("<html><body><h1>403 Forbidden</h1></body></html>".toByteArray()))
        assertNull(Detect.junkReply(byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(100)))
    }

    @Test fun decodesTurkishSubtitles() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nŞimdi gidiyoruz, ağabey!\r\n"
        assertEquals(srt, Detect.decodeSub(srt.toByteArray(charset("windows-1254"))))
        assertEquals(srt, Detect.decodeSub(("\uFEFF" + srt).toByteArray()))
        assertNull(Detect.decodeSub("<html>403</html>".toByteArray()))
    }

    @Test fun cleansHeadersLikeIdm() {
        val h = Detect.cleanHeaders(mapOf(
            "referer" to "https://p/izle", "x-requested-with" to "XMLHttpRequest", "Cookie" to "a=1",
            "Range" to "bytes=0-", "Bad Name" to "x", "X-Inject" to "a\r\nEvil: 1", "sec-fetch-mode" to "cors",
        ))
        assertEquals(mapOf("Referer" to "https://p/izle", "X-Requested-With" to "XMLHttpRequest", "Sec-Fetch-Mode" to "cors"), h)
        // WebView'in kendi eklediği paket adı atılır
        assertTrue(Detect.cleanHeaders(mapOf("X-Requested-With" to "com.korayhan.videoindirici")).isEmpty())
    }

    @Test fun buildsNetscapeCookies() {
        val txt = Detect.netscapeCookies(mapOf("media.x.com" to "a=1; cf_clearance=Z=Y; bozuk"), 1000)
        assertTrue(txt.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(txt.contains("media.x.com\tFALSE\t/\tFALSE\t87400\ta\t1\n"))
        assertTrue(txt.contains("\tcf_clearance\tZ=Y\n"))
        assertEquals("a=1; cf_clearance=Z=Y", Detect.cookieHeaderFor(txt, "media.x.com"))
        assertEquals("", Detect.cookieHeaderFor(txt, "other.com"))
    }

    @Test fun miscHelpers() {
        assertEquals("Yu-Gi-Oh! GX 14. Bölüm izle Anizm", Detect.safeName("Yu-Gi-Oh! GX 14. Bölüm izle | Anizm"))
        assertEquals("100%% x", Detect.safeName("100% x"))
        assertEquals("bestvideo[height<=720]+bestaudio/best[height<=720]/best", Detect.formatSelector(720))
        assertEquals("2.31MiB/s", Detect.speedOf("[download]  12.3% of 100MiB at  2.31MiB/s ETA 00:12"))
        assertEquals("https://c.com/a/b.ts", Detect.resolve("https://c.com/a/x.m3u8?q", "b.ts"))
        assertEquals("https://c.com/b.ts", Detect.resolve("https://c.com/a/x.m3u8", "/b.ts"))
        assertEquals("media.x.com", Detect.hostOf("https://user@media.x.com:8443/a?b"))
        assertTrue(Detect.friendlyError("ERROR: [generic] Got HTTP Error 403 caused by Cloudflare").contains("Tarayıcıda aç"))
    }
}
