package com.korayhan.videoindirici

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** İndirme listesi: arayüz ve servis bunu izler. Liste uygulama kapanınca da korunur. */
object DownloadRepo {
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs

    /** yt-dlp + Python ilk açılışta telefona açılır; hazır olunca true. */
    val ready = MutableStateFlow(false)
    val initError = MutableStateFlow<String?>(null)

    const val MAX_PARALLEL = 30
    @Volatile var parallel = 3
        private set
    @Volatile var quality = Quality.BEST
        private set

    private lateinit var app: Context
    private val lock = Any()
    private val saver = Executors.newSingleThreadExecutor()
    private val file get() = File(app.filesDir, "jobs.json")

    fun load(ctx: Context) {
        app = ctx.applicationContext
        val prefs = app.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        parallel = prefs.getInt("parallel", 3).coerceIn(1, MAX_PARALLEL)
        quality = runCatching { Quality.valueOf(prefs.getString("quality", "BEST")!!) }.getOrDefault(Quality.BEST)
        val loaded = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { JobJson.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        // Uygulama kapanırken yarıda kalanlar
        _jobs.value = loaded.map {
            if (it.active) it.copy(status = Status.CANCELLED, message = "Yarıda kaldı — Tekrar'a bas", speed = "") else it
        }
    }

    fun setParallel(n: Int) {
        parallel = n.coerceIn(1, MAX_PARALLEL)
        app.getSharedPreferences("ayarlar", Context.MODE_PRIVATE).edit().putInt("parallel", parallel).apply()
        DownloadService.kick(app)
    }

    fun setQuality(q: Quality) {
        quality = q
        app.getSharedPreferences("ayarlar", Context.MODE_PRIVATE).edit().putString("quality", q.name).apply()
    }

    fun get(id: String) = _jobs.value.find { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    fun update(id: String, persist: Boolean = true, f: (DownloadJob) -> DownloadJob) {
        synchronized(lock) { _jobs.value = _jobs.value.map { if (it.id == id) f(it) else it } }
        if (persist) save()
    }

    fun progress(id: String, percent: Float, eta: Long, speed: String) = update(id, persist = false) {
        if (it.status == Status.RUNNING) it.copy(percent = percent.coerceIn(0f, 100f), eta = eta, speed = speed) else it
    }

    fun enqueue(ctx: Context, job: DownloadJob) {
        synchronized(lock) { _jobs.value = listOf(job) + _jobs.value }
        save()
        DownloadService.kick(ctx)
    }

    /** Linkten iş oluşturur (paylaş menüsü ve kopyala-yapıştır). */
    fun enqueueUrl(ctx: Context, url: String, title: String = "", q: Quality = quality) = enqueue(
        ctx, DownloadJob(id = newId(), url = url.trim(), title = title, pageUrl = url.trim(), height = q.height, audio = q.audio),
    )

    fun cancel(id: String) {
        update(id) { if (it.active) it.copy(status = Status.CANCELLED, message = "İptal edildi", speed = "") else it }
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }

    fun retry(ctx: Context, id: String) {
        update(id) { it.copy(status = Status.QUEUED, percent = 0f, eta = -1, speed = "", message = "", files = emptyList()) }
        DownloadService.kick(ctx)
    }

    fun remove(id: String) {
        cancel(id)
        synchronized(lock) { _jobs.value = _jobs.value.filterNot { it.id == id } }
        save()
    }

    fun clearFinished() {
        synchronized(lock) { _jobs.value = _jobs.value.filter { it.active || it.status == Status.ERROR } }
        save()
    }

    private fun save() {
        val snapshot = _jobs.value
        saver.execute {
            runCatching {
                val a = JSONArray().apply { snapshot.take(300).forEach { put(JobJson.toJson(it)) } }
                val tmp = File(file.path + ".tmp")
                tmp.writeText(a.toString())
                tmp.renameTo(file)
            }
        }
    }
}

fun Context.startDownloadService() =
    ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java))
