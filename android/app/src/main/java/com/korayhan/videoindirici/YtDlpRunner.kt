package com.korayhan.videoindirici

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

private class Cancelled : Exception()
private class JunkReply(val text: String) : Exception(text)

/** Hazırlanan altyazı: metin ya da neden inmediği. */
private data class SubResult(val lang: String, val format: String, val text: String?, val error: String)

/** Bir indirme işini baştan sona yürütür (masaüstündeki app.py _run_job karşılığı). */
object YtDlpRunner {
    private const val MAX_SUB = 5 * 1024 * 1024

    fun run(ctx: Context, id: String) {
        val job = DownloadRepo.get(id) ?: return
        if (job.status == Status.CANCELLED) return
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val work = File(base, "gecici/${job.id}").apply { deleteRecursively(); mkdirs() }
        try {
            DownloadRepo.update(id) { it.copy(status = Status.RUNNING, percent = 0f, message = "", speed = "") }
            // 1) Altyazılar videodan ÖNCE: süreli linkler sırada beklerken ölmesin
            val subs = if (job.audio) emptyList() else job.subs.map { prepareSub(job, it) }
            val cookieFile = job.cookies.takeIf { it.isNotBlank() }?.let { File(work, "cookies.txt").apply { writeText(it) } }

            // 2) yt-dlp; akışın altyazısı inmezse videoyu kaybetme: altyazısız tekrar dene
            var note = ""
            try {
                execute(job, buildRequest(job, work, cookieFile, streamSubs = true))
            } catch (e: Exception) {
                if (isCancelled(id)) throw Cancelled()
                val msg = e.message ?: ""
                if (job.audio || !msg.contains("subtitles", ignoreCase = true)) throw e
                note = "Akışın altyazısı indirilemedi, video altyazısız indi"
                execute(job, buildRequest(job, work, cookieFile, streamSubs = false))
            }
            if (isCancelled(id)) throw Cancelled()
            DownloadRepo.update(id) { it.copy(status = Status.PROCESSING, percent = 100f, speed = "", eta = -1) }

            // 3) Sunucu video yerine "security error" gibi bir yazı döndüyse başarılı sayma
            var results = outputs(work)
            if (results.isEmpty()) throw Exception("Dosya oluşmadı")
            for (f in results) {
                if (f.length() <= 64 * 1024) Detect.junkReply(f.readBytes())?.let { throw JunkReply(it) }
            }

            // 4) Sayfada yakalanan altyazıları yanına kaydet ve videoya göm
            val video = results.firstOrNull { it.extension.lowercase() in Detect.VIDEO_FILE_EXT }
            if (video != null && subs.isNotEmpty()) {
                val subNote = saveSubs(ctx, video, subs)
                note = listOf(subNote, note).filter { it.isNotEmpty() }.joinToString(" · ")
            }

            // 5) İndirilenler/Video Indirici
            results = outputs(work).sortedBy { if (it.extension.lowercase() in Detect.VIDEO_FILE_EXT) 0 else 1 }
            val saved = results.map { Storage.publish(ctx, it) }
            DownloadRepo.update(id) { it.copy(status = Status.DONE, files = saved, message = note, percent = 100f) }
            Notifier.done(ctx, DownloadRepo.get(id) ?: job)
        } catch (c: Cancelled) {
            DownloadRepo.update(id) { it.copy(status = Status.CANCELLED, speed = "", message = "İptal edildi") }
        } catch (j: JunkReply) {
            DownloadRepo.update(id) {
                it.copy(status = Status.ERROR, speed = "", message = "Sunucu video yerine şunu döndü: \"${j.text}\". " +
                    "Link süreli/tek kullanımlık olabilir: sayfayı Tarayıcıda aç, videoyu oynat ve oradan tekrar indir.")
            }
        } catch (e: Throwable) {
            if (isCancelled(id)) {
                DownloadRepo.update(id) { it.copy(status = Status.CANCELLED, speed = "", message = "İptal edildi") }
            } else {
                DownloadRepo.update(id) {
                    it.copy(status = Status.ERROR, speed = "", message = Detect.friendlyError(e.message ?: e.toString()))
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun isCancelled(id: String) = DownloadRepo.get(id)?.status == Status.CANCELLED

    private fun execute(job: DownloadJob, req: YoutubeDLRequest) {
        YoutubeDL.getInstance().execute(req, job.id) { progress, eta, line ->
            DownloadRepo.progress(job.id, progress, eta, Detect.speedOf(line))
        }
    }

    private fun outputs(work: File): List<File> = work.listFiles().orEmpty().filter {
        it.isFile && it.name != "cookies.txt" && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
            !it.name.endsWith(".temp") && !it.name.contains(".part-Frag")
    }

    private fun buildRequest(job: DownloadJob, work: File, cookieFile: File?, streamSubs: Boolean): YoutubeDLRequest {
        val r = YoutubeDLRequest(job.url)
        val name = Detect.safeName(job.title)
        val tag = job.id.take(6)
        val template = if (name.isNotEmpty()) "$name [$tag].%(ext)s" else "%(title).100B [$tag].%(ext)s"
        r.addOption("-o", File(work, template).absolutePath)
        r.addOption("--no-mtime")
        r.addOption("--no-playlist")
        r.addOption("--concurrent-fragments", 8)
        r.addOption("--socket-timeout", 60)
        r.addOption("--retries", 5)
        r.addOption("--fragment-retries", 10)
        r.addOption("--extractor-retries", 3)
        if (job.audio) {
            r.addOption("-f", "bestaudio/best")
            r.addOption("-x")
            r.addOption("--audio-format", "mp3")
        } else {
            r.addOption("-f", Detect.formatSelector(job.height))
            r.addOption("--merge-output-format", "mp4")
            if (streamSubs) {
                // Akışın kendi altyazıları (HLS altyazı kanalı, YouTube...): Türkçe/İngilizce, videoya gömülür
                r.addOption("--write-subs")
                r.addOption("--sub-langs", Detect.SUB_LANGS)
                r.addOption("--embed-subs")
            }
        }
        // IDM gibi: tarayıcının gönderdiği başlıklar aynen
        val cmds = mutableListOf<String>()
        for ((k, v) in job.headers) {
            cmds += "--add-header"
            cmds += "$k:$v"
        }
        if (cmds.isNotEmpty()) r.addCommands(cmds)
        cookieFile?.let { r.addOption("--cookies", it.absolutePath) }
        return r
    }

    /** Tarayıcının aldığı içerik varsa onu kullanır; yoksa altyazıyı tarayıcının başlıklarıyla indirir. */
    private fun prepareSub(job: DownloadJob, s: SubSpec): SubResult {
        s.content?.let { bytes ->
            Detect.decodeSub(bytes)?.let { return SubResult(s.lang, s.format, it, "") }
        }
        return try {
            val c = URL(s.url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            for ((k, v) in s.headers) c.setRequestProperty(k, v)
            job.headers["User-Agent"]?.let { if (!s.headers.containsKey("User-Agent")) c.setRequestProperty("User-Agent", it) }
            Detect.cookieHeaderFor(job.cookies, Detect.hostOf(s.url)).takeIf { it.isNotEmpty() }
                ?.let { c.setRequestProperty("Cookie", it) }
            val code = c.responseCode
            if (code >= 400) return SubResult(s.lang, s.format, null, "HTTP $code")
            val data = c.inputStream.use { inp -> inp.readNBytesCompat(MAX_SUB + 1) }
            if (data.size > MAX_SUB) return SubResult(s.lang, s.format, null, "dosya çok büyük")
            val text = Detect.decodeSub(data)
                ?: return SubResult(s.lang, s.format, null, "altyazı değil: " + (Detect.junkReply(data) ?: "?").take(40))
            SubResult(s.lang, s.format, text, "")
        } catch (e: java.net.SocketTimeoutException) {
            SubResult(s.lang, s.format, null, "zaman aşımı")
        } catch (e: Exception) {
            SubResult(s.lang, s.format, null, e.javaClass.simpleName)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (out.size() <= max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Altyazıları videonun yanına (film.tr.vtt) yazar, sonra ffmpeg ile videoya gömmeyi dener. */
    private fun saveSubs(ctx: Context, video: File, subs: List<SubResult>): String {
        val saved = mutableListOf<Pair<File, String>>()
        val failed = mutableListOf<String>()
        for (s in subs) {
            if (s.text == null) {
                failed += "${s.lang.ifEmpty { "?" }.uppercase()}: ${s.error.ifEmpty { "indirilemedi" }}"
                continue
            }
            val tag = s.lang.ifEmpty { "und" }
            var f = File(video.parentFile, "${video.nameWithoutExtension}.$tag.${s.format}")
            var n = 2
            while (f.exists()) {
                f = File(video.parentFile, "${video.nameWithoutExtension}.$tag.$n.${s.format}")
                n++
            }
            f.writeText(s.text, Charsets.UTF_8)
            saved += f to s.lang
        }
        val failNote = if (failed.isNotEmpty()) " (indirilemeyen — ${failed.joinToString("; ")})" else ""
        if (saved.isEmpty()) return "Altyazı bulundu ama indirilemedi — ${failed.joinToString("; ")}"
        val langs = saved.map { it.second.ifEmpty { "?" }.uppercase() }.distinct().sorted().joinToString(", ")
        return if (FfmpegTools.embed(ctx, video, saved)) {
            "Altyazı ($langs) videoya gömüldü ve yanına da kaydedildi$failNote"
        } else {
            "Altyazı ($langs) videonun yanına kaydedildi$failNote"
        }
    }
}

/** youtubedl-android'in telefona açtığı ffmpeg'i doğrudan çalıştırır (altyazı gömme). */
object FfmpegTools {
    private fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    private fun env(ctx: Context): String {
        val packages = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        return listOf("python/usr/lib", "ffmpeg/usr/lib").joinToString(":") { File(packages, it).absolutePath }
    }

    private fun run(ctx: Context, args: List<String>, timeoutMin: Long): Pair<Int, String> {
        val pb = ProcessBuilder(listOf(binary(ctx).absolutePath) + args).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = env(ctx)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMin, TimeUnit.MINUTES)) {
            p.destroy()
            return -1 to out
        }
        return p.exitValue() to out
    }

    private fun countSubStreams(ctx: Context, video: File): Int = runCatching {
        val (_, out) = run(ctx, listOf("-hide_banner", "-i", video.absolutePath), 1)
        out.lines().count { Regex("""Stream #\d+:\d+.*: Subtitle:""").containsMatchIn(it) }
    }.getOrDefault(0)

    fun embed(ctx: Context, video: File, subs: List<Pair<File, String>>): Boolean {
        val ext = video.extension.lowercase()
        if (subs.isEmpty() || ext !in setOf("mp4", "m4v", "mov", "mkv") || !binary(ctx).exists()) return false
        return runCatching {
            val existing = countSubStreams(ctx, video)
            val tmp = File(video.parentFile, "${video.nameWithoutExtension}.altyazili.$ext")
            val args = mutableListOf("-y", "-loglevel", "error", "-i", video.absolutePath)
            subs.forEach { args += listOf("-i", it.first.absolutePath) }
            args += listOf("-map", "0")
            subs.indices.forEach { args += listOf("-map", "${it + 1}") }
            args += listOf("-c", "copy", "-c:s", if (ext == "mkv") "copy" else "mov_text")
            subs.forEachIndexed { i, (_, lang) ->
                args += listOf("-metadata:s:s:${existing + i}", "language=${Detect.ISO3[lang] ?: "und"}")
            }
            args += tmp.absolutePath
            val (code, _) = run(ctx, args, 10)
            if (code == 0 && tmp.length() > 0) {
                video.delete()
                tmp.renameTo(video)
            } else {
                tmp.delete()
                false
            }
        }.getOrDefault(false)
    }
}
