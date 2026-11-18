@file:OptIn(ExperimentalMaterial3Api::class)

package com.korayhan.videoindirici

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Uygulama içi tarayıcı: masaüstündeki eklentinin yerini alır. Sayfayı açıp (gerekirse doğrulamayı geçip)
 * videoyu oynatınca akış adresleri, altyazılar, başlıklar ve çerezler IDM gibi yakalanır.
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val sniffer = Sniffer()
    private var pageUrl by mutableStateOf("")
    private var addressText by mutableStateOf("")
    private var loading by mutableIntStateOf(0)
    private var customView by mutableStateOf<View?>(null)
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fallbackScript: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        web = WebView(this)
        setupWebView(web)
        val start = intent.getStringExtra(EXTRA_URL).orEmpty()
        addressText = start
        if (start.isNotBlank()) web.loadUrl(normalize(start))
        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> hideCustom()
                web.canGoBack() -> web.goBack()
                else -> finish()
            }
        }
        setContent { AppTheme { BrowserScreen() } }
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private fun normalize(u: String): String {
        val t = u.trim()
        return when {
            Detect.isHttp(t) -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
    }

    private fun hideCustom() {
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView(w: WebView) {
        w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(false) // reklam açılır pencereleri açılmasın
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        // WebView isteklere "X-Requested-With: <paket adı>" ekler; bazı siteler buna göre engeller
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }

        val script = assets.open("sniff_android.js").bufferedReader().use { it.readText() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(w, "VIAndroid", setOf("*")) { _, message, _, _, _ ->
                message.data?.let { sniffer.onMessage(it) }
            }
        } else {
            w.addJavascriptInterface(object {
                @JavascriptInterface
                fun postMessage(data: String) = sniffer.onMessage(data)
            }, "VIAndroid")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, script, setOf("*")) // tüm çerçevelerde, sayfadan önce
        } else {
            fallbackScript = script // eski WebView: sayfa açılırken ana çerçeveye eklenir
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                sniffer.onRequest(request.url.toString(), request.requestHeaders ?: emptyMap(), pageUrl)
                return null // isteğe dokunma, sadece gör
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https" // intent://, market:// reklam yönlendirmeleri açılmaz
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (Detect.originOf(url) + Detect.pathOf(url) != Detect.originOf(pageUrl) + Detect.pathOf(pageUrl)) {
                    sniffer.reset() // yeni sayfa: eski yakalananları temizle
                }
                pageUrl = url
                addressText = url
                fallbackScript?.let { view.evaluateJavascript(it, null) }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                pageUrl = url
                addressText = url
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loading = if (newProgress >= 100) 0 else newProgress
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customCallback = callback
            }

            override fun onHideCustomView() = hideCustom()
        }
    }

    /** Seçilen adayı indirme kuyruğuna ekler: tarayıcının başlıkları + bu sayfada görülen hostların çerezleri. */
    private fun download(item: Captured, q: Quality) {
        val ua = web.settings.userAgentString
        val headers = Detect.cleanHeaders(item.headers).toMutableMap()
        headers.putIfAbsent("Referer", pageUrl)
        headers.putIfAbsent("User-Agent", ua)

        val cm = CookieManager.getInstance()
        cm.flush()
        val urlsByHost = sniffer.hostUrls.toMutableMap()
        urlsByHost[Detect.hostOf(item.url)] = item.url
        if (pageUrl.isNotEmpty()) urlsByHost.putIfAbsent(Detect.hostOf(pageUrl), pageUrl)
        val cookies = urlsByHost.mapValues { (_, u) -> cm.getCookie(u).orEmpty() }.filterValues { it.isNotBlank() }

        val subs = sniffer.pickedSubs().map { s ->
            val h = Detect.cleanHeaders(s.headers).toMutableMap()
            h.putIfAbsent("Referer", pageUrl)
            SubSpec(s.url, s.format, s.lang, h, s.content)
        }
        DownloadRepo.enqueue(
            this,
            DownloadJob(
                id = DownloadRepo.newId(), url = item.url, title = web.title.orEmpty(), pageUrl = pageUrl,
                headers = headers, cookies = Detect.netscapeCookies(cookies, System.currentTimeMillis() / 1000),
                subs = if (q.audio) emptyList() else subs, height = q.height, audio = q.audio,
            ),
        )
        Toast.makeText(this, "İndirmeye eklendi" + if (subs.isNotEmpty() && !q.audio) " (+${subs.size} altyazı)" else "",
            Toast.LENGTH_SHORT).show()
    }

    private fun label(c: Captured): String {
        val type = when (c.kind) {
            Kind.HLS -> if (c.info?.master == true && c.info.heights.isNotEmpty())
                "Akış · ${c.info.heights.joinToString("/") { "${it}p" }}" else "Akış (m3u8)"
            Kind.DASH -> "Akış (dash)"
            Kind.FILE -> Detect.extOf(c.url).uppercase().ifEmpty { "Video" }
        }
        return "$type · ${Detect.hostOf(c.url)}" + if (c.sniffed) " · gizli" else ""
    }

    @Composable
    private fun BrowserScreen() {
        val items by sniffer.items.collectAsStateWithLifecycle()
        val subs by sniffer.subs.collectAsStateWithLifecycle()
        var sheet by remember { mutableStateOf(false) }
        var quality by remember { mutableStateOf(DownloadRepo.quality) }
        val visible = items.filter { !it.variantOf }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = addressText, onValueChange = { addressText = it }, singleLine = true,
                        modifier = Modifier.weight(1f), placeholder = { Text("Adres ya da arama") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { web.loadUrl(normalize(addressText)) }),
                    )
                    IconButton(onClick = { web.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Yenile") }
                }
                if (loading in 1..99) LinearProgressIndicator(progress = { loading / 100f }, modifier = Modifier.fillMaxWidth())
                AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
            }
            ExtendedFloatingActionButton(
                onClick = {
                    if (visible.isEmpty()) {
                        Toast.makeText(this@BrowserActivity, "Henüz video yok: videoyu birkaç saniye oynat", Toast.LENGTH_SHORT).show()
                    } else {
                        sheet = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).imePadding(),
            ) { Text("⬇ Videolar (${visible.size})" + if (subs.isNotEmpty()) " · CC" else "") }

            customView?.let { v ->
                key(v) {
                    AndroidView(factory = { v }, modifier = Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }

        if (sheet) {
            ModalBottomSheet(onDismissRequest = { sheet = false }) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bu sayfada bulunanlar", style = MaterialTheme.typography.titleMedium)
                    val picked = sniffer.pickedSubs()
                    if (picked.isNotEmpty()) {
                        Text(
                            "Altyazı: " + picked.joinToString(", ") { "${it.lang.ifEmpty { "?" }.uppercase()} · ${it.format.uppercase()}" } +
                                " — videoyla birlikte indirilir",
                            color = Green, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(Quality.entries) { q ->
                            FilterChip(selected = q == quality, onClick = { quality = q }, label = { Text(q.label) })
                        }
                    }
                    sniffer.best()?.let { best ->
                        Button(onClick = { download(best, quality); sheet = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("En iyisini indir")
                        }
                    }
                    HorizontalDivider()
                    LazyColumn {
                        items(visible.reversed(), key = { it.url }) { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(label(c), style = MaterialTheme.typography.bodyMedium)
                                    Text(Detect.fileOf(c.url), style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { download(c, quality); sheet = false }) { Text("İndir") }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
