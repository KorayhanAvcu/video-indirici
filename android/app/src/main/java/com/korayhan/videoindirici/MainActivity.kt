@file:OptIn(ExperimentalMaterial3Api::class)

package com.korayhan.videoindirici

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askPermissions()
        handleShare(intent)
        setContent { AppTheme { MainScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun askPermissions() {
        val perm = when {
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.POST_NOTIFICATIONS
            Build.VERSION.SDK_INT < 29 -> Manifest.permission.WRITE_EXTERNAL_STORAGE
            else -> null
        } ?: return
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) askPermission.launch(perm)
    }

    /** Tarayıcıda "Paylaş → Video İndirici": linki hemen indirmeye ekler. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val urls = Detect.extractUrls(text)
        if (urls.isEmpty()) {
            Toast.makeText(this, "Paylaşılan metinde link yok", Toast.LENGTH_SHORT).show()
        } else {
            val subject = i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            urls.forEach { DownloadRepo.enqueueUrl(this, it, title = if (urls.size == 1) subject else "") }
            Toast.makeText(this, "İndirmeye eklendi (${urls.size})", Toast.LENGTH_SHORT).show()
        }
        intent = Intent(this, MainActivity::class.java) // döndürmede tekrar eklenmesin
    }
}

fun Context.openBrowser(url: String = "") =
    startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url))

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val jobs by DownloadRepo.jobs.collectAsStateWithLifecycle()
    val ready by DownloadRepo.ready.collectAsStateWithLifecycle()
    val initError by DownloadRepo.initError.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var quality by remember { mutableStateOf(DownloadRepo.quality) }
    var settings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video İndirici") },
                actions = {
                    IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, contentDescription = "Ayarlar") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                if (!ready && initError == null) {
                    Text("yt-dlp hazırlanıyor (ilk açılışta biraz sürer)…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                initError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Video ya da sayfa linki (her satıra bir tane)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cb = ctx.getSystemService(ClipboardManager::class.java)
                        val clip = cb?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        if (clip.isNotBlank()) text = if (text.isBlank()) clip else text.trimEnd() + "\n" + clip
                    }) { Text("Yapıştır") }
                    Button(onClick = {
                        val urls = Detect.extractUrls(text)
                        if (urls.isEmpty()) {
                            Toast.makeText(ctx, "Link bulunamadı", Toast.LENGTH_SHORT).show()
                        } else {
                            urls.forEach { DownloadRepo.enqueueUrl(ctx, it, q = quality) }
                            text = ""
                        }
                    }) { Text("⬇ İndir") }
                    OutlinedButton(onClick = { ctx.openBrowser(Detect.extractUrl(text) ?: "") }) { Text("Tarayıcıda aç") }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(Quality.entries) { q ->
                        FilterChip(
                            selected = q == quality,
                            onClick = { quality = q; DownloadRepo.setQuality(q) },
                            label = { Text(q.label) },
                        )
                    }
                }
                Text(
                    "Korumalı sitelerde (Cloudflare doğrulaması çıkanlar) \"Tarayıcıda aç\" ile sayfayı açıp videoyu oynat; " +
                        "uygulama videoyu ve altyazıyı IDM gibi yakalar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                val running = jobs.count { it.status == Status.RUNNING || it.status == Status.PROCESSING }
                val queued = jobs.count { it.status == Status.QUEUED }
                val done = jobs.count { it.status == Status.DONE }
                val err = jobs.count { it.status == Status.ERROR }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "İndiriliyor: $running · Sırada: $queued · Tamam: $done · Hata: $err",
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { DownloadRepo.clearFinished() }) { Text("Temizle") }
                }
            }
            items(jobs, key = { it.id }) { job -> JobCard(job) }
        }
    }
    if (settings) SettingsDialog(onClose = { settings = false })
}

@Composable
private fun JobCard(job: DownloadJob) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                job.title.ifEmpty { job.files.firstOrNull()?.name ?: job.url },
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(job.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val (label, color) = when (job.status) {
                Status.QUEUED -> "Sırada" to MaterialTheme.colorScheme.onSurfaceVariant
                Status.RUNNING -> "İndiriliyor" to MaterialTheme.colorScheme.primary
                Status.PROCESSING -> "İşleniyor" to MaterialTheme.colorScheme.primary
                Status.DONE -> "Tamamlandı ✔" to Green
                Status.ERROR -> "Hata ✘" to MaterialTheme.colorScheme.error
                Status.CANCELLED -> "İptal" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (job.status == Status.RUNNING) {
                    val eta = if (job.eta > 0) " · ${job.eta / 60}:${"%02d".format(job.eta % 60)}" else ""
                    Text("%.1f%%".format(job.percent) + (if (job.speed.isNotEmpty()) " · ${job.speed}" else "") + eta,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            if (job.status == Status.RUNNING || job.status == Status.PROCESSING) {
                LinearProgressIndicator(progress = { job.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            if (job.message.isNotEmpty()) {
                Text(job.message, style = MaterialTheme.typography.bodySmall,
                    color = if (job.status == Status.DONE) Green else MaterialTheme.colorScheme.error)
            }
            if (job.status == Status.DONE && job.files.isNotEmpty()) {
                Text(Storage.displayPath(job.files.first()), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (job.active) TextButton(onClick = { DownloadRepo.cancel(job.id) }) { Text("İptal") }
                if (job.status == Status.ERROR || job.status == Status.CANCELLED) {
                    TextButton(onClick = { DownloadRepo.retry(ctx, job.id) }) { Text("Tekrar") }
                }
                if (job.status == Status.ERROR) {
                    TextButton(onClick = { ctx.openBrowser(job.pageUrl.ifEmpty { job.url }) }) { Text("Tarayıcıda aç") }
                }
                if (job.status == Status.DONE) {
                    job.files.firstOrNull()?.let { f ->
                        TextButton(onClick = {
                            val i = Storage.openIntent(f)
                            if (i == null) Toast.makeText(ctx, Storage.displayPath(f), Toast.LENGTH_LONG).show()
                            else runCatching { ctx.startActivity(i) }.onFailure {
                                Toast.makeText(ctx, "Bu dosyayı açacak uygulama yok", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Aç") }
                    }
                }
                if (!job.active) TextButton(onClick = { DownloadRepo.remove(job.id) }) { Text("Sil") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var parallel by remember { mutableFloatStateOf(DownloadRepo.parallel.toFloat()) }
    var version by remember { mutableStateOf("…") }
    var updating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        version = withContext(Dispatchers.IO) { runCatching { YoutubeDL.getInstance().version(ctx) }.getOrNull() } ?: "?"
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Tamam") } },
        title = { Text("Ayarlar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Aynı anda indirme: ${parallel.toInt()}")
                Slider(
                    value = parallel, onValueChange = { parallel = it },
                    onValueChangeFinished = { DownloadRepo.setParallel(parallel.toInt()) },
                    valueRange = 1f..DownloadRepo.MAX_PARALLEL.toFloat(), steps = DownloadRepo.MAX_PARALLEL - 2,
                )
                Text("Not: Her indirme 8 parçayı birlikte çeker; çok yüksek sayılarda bazı siteler bağlantıyı kesebilir.",
                    style = MaterialTheme.typography.bodySmall)
                Text("yt-dlp sürümü: $version")
                Button(enabled = !updating, onClick = {
                    updating = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { YoutubeDL.getInstance().updateYoutubeDL(ctx) }
                        }
                        updating = false
                        version = withContext(Dispatchers.IO) { runCatching { YoutubeDL.getInstance().version(ctx) }.getOrNull() } ?: version
                        val msg = r.fold(
                            { s -> if (s.toString() == "ALREADY_UP_TO_DATE") "Zaten güncel" else "Güncellendi" },
                            { e -> "Güncellenemedi: ${e.message}" },
                        )
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(if (updating) "Güncelleniyor…" else "yt-dlp'yi güncelle") }
                Text("Siteler değiştikçe yt-dlp güncellenir; bir site çalışmayı bırakırsa önce bunu dene.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
