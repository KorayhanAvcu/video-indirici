package com.korayhan.videoindirici

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadRepo.load(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Tamamlanan indirmeler", NotificationManager.IMPORTANCE_DEFAULT))
        }
        // Python + yt-dlp + ffmpeg ilk açılışta telefona açılır (birkaç saniye sürebilir)
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                DownloadRepo.ready.value = true
            } catch (e: Throwable) {
                DownloadRepo.initError.value = "yt-dlp başlatılamadı: ${e.message ?: e}"
            }
        }.start()
    }

    companion object {
        const val CH_PROGRESS = "indirmeler"
        const val CH_DONE = "tamamlanan"
    }
}
