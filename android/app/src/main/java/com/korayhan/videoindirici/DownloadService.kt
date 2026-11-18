package com.korayhan.videoindirici

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Uygulama arka plandayken de indirmeye devam eden ön plan servisi; aynı anda 1–30 iş. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        scope.launch {
            while (isActive) {
                runCatching { NotificationManagerCompat.from(this@DownloadService).notify(NOTIF_ID, buildNotification()) }
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        schedule()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun schedule() {
        scope.launch {
            // yt-dlp ilk açılışta hazırlanıyor olabilir
            while (!DownloadRepo.ready.value) {
                DownloadRepo.initError.value?.let { err ->
                    DownloadRepo.jobs.value.filter { it.status == Status.QUEUED }.forEach { j ->
                        DownloadRepo.update(j.id) { it.copy(status = Status.ERROR, message = err) }
                    }
                    stopIfIdle()
                    return@launch
                }
                delay(300)
            }
            synchronized(running) {
                val queued = DownloadRepo.jobs.value.filter { it.status == Status.QUEUED }.reversed() // eskiden yeniye
                for (j in queued) {
                    if (running.size >= DownloadRepo.parallel) break
                    if (!running.add(j.id)) continue
                    DownloadRepo.update(j.id) { it.copy(status = Status.RUNNING) }
                    scope.launch {
                        try {
                            YtDlpRunner.run(applicationContext, j.id)
                        } finally {
                            running.remove(j.id)
                            schedule()
                        }
                    }
                }
            }
            stopIfIdle()
        }
    }

    private fun stopIfIdle() {
        synchronized(running) {
            if (running.isEmpty() && DownloadRepo.jobs.value.none { it.status == Status.QUEUED }) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun buildNotification(): Notification {
        val jobs = DownloadRepo.jobs.value
        val active = jobs.filter { it.status == Status.RUNNING || it.status == Status.PROCESSING }
        val queued = jobs.count { it.status == Status.QUEUED }
        val avg = if (active.isEmpty()) 0 else active.map { it.percent }.average().toInt()
        val text = when {
            !DownloadRepo.ready.value -> "yt-dlp hazırlanıyor…"
            active.isEmpty() && queued == 0 -> "Bitti"
            else -> "İndiriliyor: ${active.size} · Sırada: $queued"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Video İndirici")
            .setContentText(text)
            .setProgress(100, avg, active.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1

        fun kick(ctx: Context) {
            runCatching { ctx.startDownloadService() }
        }
    }
}

/** "İndirildi" bildirimi: dokununca dosyayı açar. */
object Notifier {
    fun done(ctx: Context, job: DownloadJob) {
        val file = job.files.firstOrNull() ?: return
        val intent = Storage.openIntent(file) ?: Intent(ctx, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            ctx, job.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, App.CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("İndirildi")
            .setContentText(job.title.ifEmpty { file.name })
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(job.id.hashCode(), n) }
    }
}
