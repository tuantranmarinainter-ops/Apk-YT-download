package com.tuan.ytsub

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class Opts(val mode: String, val quality: String, val subLang: String, val attach: String, val font: String)

data class OutFile(val name: String, val uri: String, val mime: String)

class Job(val id: String, val url: String, val opts: Opts) {
    @Volatile var status = "queued"          // queued | running | done | error | canceled
    @Volatile var stage = "Đang chờ…"
    @Volatile var pct: Double? = null
    @Volatile var info: JSONObject? = null
    @Volatile var error: String? = null
    @Volatile var canceled = false
    val warnings: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    val files: MutableList<OutFile> = java.util.Collections.synchronizedList(ArrayList())
    val created = System.currentTimeMillis()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("status", status); put("stage", stage)
        put("pct", pct ?: JSONObject.NULL); put("info", info ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("warnings", JSONArray(synchronized(warnings) { warnings.toList() }))
        put("files", JSONArray().also { arr ->
            synchronized(files) { files.forEach { arr.put(JSONObject().put("name", it.name).put("uri", it.uri).put("mime", it.mime)) } }
        })
    }
}

class CanceledByUser : Exception("Đã huỷ")

object Engine {
    private const val TAG = "YTSub"
    private const val VI_COLOR = "#FFE14D"
    private val LANG_NAME = mapOf("en" to "tiếng Anh", "vi" to "tiếng Việt")
    private val ISO3 = mapOf("en" to "eng", "vi" to "vie")
    private val FORMATS = mapOf(
        "best" to "bv*+ba/b",
        "1080" to "bv*[height<=1080][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=1080]+ba/b[height<=1080]/b",
        "720" to "bv*[height<=720][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=720]+ba/b[height<=720]/b",
        "480" to "bv*[height<=480][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=480]+ba/b[height<=480]/b",
    )

    lateinit var app: Context
    private val worker = Executors.newSingleThreadExecutor()
    private val misc = Executors.newCachedThreadPool()
    val jobs: MutableList<Job> = java.util.Collections.synchronizedList(ArrayList())

    @Volatile var ready = false
    @Volatile var initError: String? = null
    @Volatile var ytdlpVersion: String? = null
    @Volatile var updateMsg: String = ""
    private val readyLatch = java.util.concurrent.CountDownLatch(1)

    // ------------------------------------------------------------ khởi động
    fun init(ctx: Context) {
        app = ctx.applicationContext
        misc.execute {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                ytdlpVersion = YoutubeDL.getInstance().versionName(app)
                ready = true
            } catch (e: Exception) {
                Log.e(TAG, "init", e); initError = e.message ?: e.toString()
            }
            readyLatch.countDown()
            if (ready) updateYtdlp()
        }
    }

    fun updateYtdlp() {
        misc.execute {
            updateMsg = "Đang cập nhật yt-dlp…"
            updateMsg = try {
                val st = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                ytdlpVersion = YoutubeDL.getInstance().versionName(app)
                if (st == YoutubeDL.UpdateStatus.DONE) "Đã cập nhật yt-dlp" else "yt-dlp mới nhất"
            } catch (e: Exception) {
                "Không cập nhật được yt-dlp (kiểm tra mạng)"
            }
        }
    }

    private fun waitReady() {
        readyLatch.await()
        initError?.let { throw RuntimeException("Khởi động thất bại: $it") }
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("ready", ready); put("initError", initError ?: JSONObject.NULL)
        put("ytdlp", ytdlpVersion ?: JSONObject.NULL); put("update", updateMsg)
    }

    // ------------------------------------------------------------ yt-dlp
    private fun extract(url: String): JSONObject {
        waitReady()
        val req = YoutubeDLRequest(url).addOption("--dump-single-json").addOption("--no-playlist")
        val res = YoutubeDL.getInstance().execute(req, null, false, null)
        var info = JSONObject(res.out.trim().lines().last { it.startsWith("{") })
        if (info.optString("_type") == "playlist") {
            val entries = info.optJSONArray("entries")
            info = entries?.optJSONObject(0) ?: throw RuntimeException("Playlist trống")
        }
        return info
    }

    /** Chọn track phụ đề: thủ công > tự động (en-orig) > YouTube tự dịch. */
    private fun pickTrack(info: JSONObject, lang: String): Pair<String, String>? {
        fun keys(o: JSONObject?) = o?.keys()?.asSequence()?.filter { !it.startsWith("live_chat") }?.toList() ?: emptyList()
        fun match(ks: List<String>) = ks.firstOrNull { it == lang } ?: ks.firstOrNull { it.substringBefore('-').equals(lang, true) }
        val manual = keys(info.optJSONObject("subtitles"))
        val auto = keys(info.optJSONObject("automatic_captions"))
        match(manual)?.let { return it to "manual" }
        if (lang == "en" && "en-orig" in auto) return "en-orig" to "auto"
        match(auto)?.let { return it to "auto" }
        return null
    }

    private fun summarize(info: JSONObject, url: String): JSONObject {
        val w = info.optInt("width", 0); val h = info.optInt("height", 0)
        val tracks = JSONObject()
        for (l in listOf("en", "vi")) {
            val t = pickTrack(info, l)
            tracks.put(l, if (t == null) JSONObject.NULL else JSONObject().put("key", t.first).put("kind", t.second))
        }
        return JSONObject().apply {
            put("id", info.optString("id")); put("title", info.optString("title"))
            put("uploader", info.optString("uploader", info.optString("channel")))
            put("duration", info.optDouble("duration", 0.0)); put("thumbnail", info.optString("thumbnail"))
            put("vertical", (w > 0 && h > w) || url.contains("/shorts/"))
            put("tracks", tracks)
        }
    }

    fun info(url: String): JSONObject = try {
        val i = extract(url); summarize(i, i.optString("webpage_url", url))
    } catch (e: Exception) {
        JSONObject().put("error", shortErr(e))
    }

    private fun shortErr(e: Throwable): String {
        val m = (e.message ?: e.toString()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val err = m.lastOrNull { it.startsWith("ERROR") } ?: m.lastOrNull() ?: "Lỗi không rõ"
        return err.take(400)
    }

    // ------------------------------------------------------------ hàng đợi
    fun enqueue(urls: List<String>, opts: Opts): List<String> {
        val ids = ArrayList<String>()
        for (u in urls.distinct()) {
            val job = Job(UUID.randomUUID().toString().take(10), u, opts)
            jobs.add(0, job); ids.add(job.id)
            worker.execute { run(job) }
        }
        DownloadService.start(app)
        return ids
    }

    fun active() = synchronized(jobs) { jobs.count { it.status == "queued" || it.status == "running" } }

    fun current(): Job? = synchronized(jobs) { jobs.firstOrNull { it.status == "running" } }

    fun jobsJson(): JSONArray = JSONArray().also { a -> synchronized(jobs) { jobs.forEach { a.put(it.toJson()) } } }

    fun clearFinished() = synchronized(jobs) { jobs.removeAll { it.status in setOf("done", "error", "canceled") } }

    fun cancel(id: String) {
        val j = synchronized(jobs) { jobs.firstOrNull { it.id == id } } ?: return
        j.canceled = true
        if (j.status == "queued") { j.status = "canceled"; j.stage = "Đã huỷ" }
        YoutubeDL.getInstance().destroyProcessById(j.id)
        ffmpegProc[j.id]?.destroy()
    }

    // ------------------------------------------------------------ chạy 1 job
    private fun run(job: Job) {
        if (job.canceled) return
        val o = job.opts
        val dir = File(app.cacheDir, "jobs/${job.id}").apply { mkdirs() }
        try {
            job.status = "running"; job.stage = "Đang khởi động…"; job.pct = null
            waitReady()
            job.stage = "Đang lấy thông tin video…"
            val info = extract(job.url)
            val url = info.optString("webpage_url", job.url)
            val summ = summarize(info, url)
            job.info = summ
            val name = "${safeName(info.optString("title"))} [${info.optString("id")}]"
            val duration = info.optDouble("duration", 0.0)

            // 1) phụ đề
            val cues = downloadSubs(job, info, url, dir)
            val srt = LinkedHashMap<String, File>()
            cues.forEach { (lang, c) -> srt[lang] = File(dir, "$lang.srt").apply { writeText(Subs.toSrt(c)) } }
            val en = cues["en"]; val vi = cues["vi"]
            if (en != null && vi != null) {
                srt["both"] = File(dir, "en-vi.srt").apply { writeText(Subs.toSrt(Subs.mergeBilingual(en, vi))) }
            }
            if (o.subLang != "none" && srt.isEmpty()) job.warnings.add("Không lấy được phụ đề nào.")
            checkCancel(job)

            // 2) video / audio
            var media: File? = null
            if (o.mode == "video" || o.mode == "audio") {
                val what = if (o.mode == "audio") "âm thanh" else "video"
                job.stage = "Đang tải $what…"; job.pct = 0.0
                val req = YoutubeDLRequest(url).addOption("--no-playlist")
                    .addOption("-o", File(dir, "media.%(ext)s").absolutePath)
                if (o.mode == "audio") {
                    req.addOption("-f", "ba/b").addOption("-x").addOption("--audio-format", "mp3")
                        .addOption("--audio-quality", "192K")
                } else {
                    req.addOption("-f", FORMATS[o.quality] ?: FORMATS.getValue("best"))
                        .addOption("--merge-output-format", "mp4")
                }
                YoutubeDL.getInstance().execute(req, job.id, false) { p, _, line ->
                    if (p > 0) job.pct = p.toDouble()
                    if (line.contains("[Merger]") || line.contains("[ExtractAudio]")) { job.stage = "Đang ghép file…"; job.pct = null }
                }
                checkCancel(job)
                media = dir.listFiles()?.filter { it.name.startsWith("media.") && it.extension !in setOf("part", "ytdl") }
                    ?.maxByOrNull { it.length() } ?: throw RuntimeException("Không tìm thấy file sau khi tải.")
            }

            // 3) gắn phụ đề
            if (media != null && o.mode == "video" && srt.isNotEmpty() && (o.attach == "soft" || o.attach == "burn")) {
                val src = File(dir, "src.${media.extension}"); media.renameTo(src)
                val out = File(dir, "final.mp4")
                if (o.attach == "soft") {
                    val tracks = listOf("en", "vi").filter { it in srt }
                    val a = arrayListOf("-i", src.name)
                    tracks.forEach { a += listOf("-i", srt.getValue(it).name) }
                    a += listOf("-map", "0:v", "-map", "0:a?")
                    tracks.indices.forEach { a += listOf("-map", "${it + 1}:0") }
                    a += listOf("-c", "copy", "-c:s", "mov_text")
                    tracks.forEachIndexed { i, k ->
                        a += listOf("-metadata:s:s:$i", "language=${ISO3[k]}", "-metadata:s:s:$i",
                            "title=${LANG_NAME.getValue(k).replaceFirstChar { it.uppercase() }}")
                    }
                    a += listOf("-disposition:s:0", "default", out.name)
                    ffmpeg(job, a, dir, 0.0, "Đang nhúng phụ đề…")
                } else {
                    val burnLang = if (o.subLang in srt) o.subLang else srt.keys.first()
                    val burn = File(dir, "burn.srt")
                    if (burnLang == "both" && en != null && vi != null) burn.writeText(Subs.toSrt(Subs.mergeBilingual(en, vi, VI_COLOR)))
                    else srt.getValue(burnLang).copyTo(burn, true)
                    val fontName = prepareFont(dir)
                    val vert = summ.optBoolean("vertical")
                    val size = mapOf("s" to (14 to 8), "m" to (18 to 10), "l" to (22 to 13))[o.font] ?: (18 to 10)
                    val fs = if (vert) size.second else size.first
                    val style = "FontName=$fontName,FontSize=$fs,Bold=1,PrimaryColour=&H00FFFFFF," +
                        "OutlineColour=&H00000000,BorderStyle=1,Outline=1.6,Shadow=0,MarginV=${if (vert) 60 else 18}"
                    val a = listOf("-i", src.name, "-vf", "subtitles=burn.srt:fontsdir=fonts:force_style='$style'",
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-pix_fmt", "yuv420p",
                        "-c:a", "copy", "-movflags", "+faststart", out.name)
                    ffmpeg(job, a, dir, duration, "Đang in phụ đề lên video…")
                }
                media = out
            }

            // 4) lưu vào Download/YTSub
            job.stage = "Đang lưu…"; job.pct = null
            media?.let { publish(job, it, "$name.${it.extension}") }
            for ((k, label) in listOf("en" to "en", "vi" to "vi", "both" to "en-vi")) {
                srt[k]?.let { publish(job, it, "$name.$label.srt") }
            }
            job.status = "done"; job.stage = "Xong! Đã lưu vào Download/YTSub"; job.pct = 100.0
        } catch (e: Throwable) {
            Log.e(TAG, "job", e)
            if (job.canceled || e is CanceledByUser || e is YoutubeDL.CanceledException) {
                job.status = "canceled"; job.stage = "Đã huỷ"; job.pct = null
            } else {
                var msg = shortErr(e)
                if (msg.contains("Sign in to confirm")) msg += " → YouTube đang chặn, thử lại sau hoặc đổi mạng (Wi-Fi/4G)."
                job.status = "error"; job.stage = "Lỗi"; job.error = msg; job.pct = null
            }
        } finally {
            ffmpegProc.remove(job.id)
            dir.deleteRecursively()
        }
    }

    private fun checkCancel(job: Job) { if (job.canceled) throw CanceledByUser() }

    private fun downloadSubs(job: Job, info: JSONObject, url: String, dir: File): Map<String, List<Cue>> {
        val want = when (job.opts.subLang) { "en" -> listOf("en"); "vi" -> listOf("vi"); "both" -> listOf("en", "vi"); else -> emptyList() }
        val result = LinkedHashMap<String, List<Cue>>()
        for (lang in want) {
            checkCancel(job)
            val t = pickTrack(info, lang)
            if (t == null) { job.warnings.add("Video không có phụ đề ${LANG_NAME[lang]}."); continue }
            val (key, kind) = t
            job.stage = "Đang tải phụ đề ${LANG_NAME[lang]}…"; job.pct = null
            try {
                val req = YoutubeDLRequest(url).addOption("--no-playlist").addOption("--skip-download")
                    .addOption(if (kind == "manual") "--write-subs" else "--write-auto-subs")
                    .addOption("--sub-langs", key).addOption("--sub-format", "json3/vtt/srt/best")
                    .addOption("-o", File(dir, "sub.%(ext)s").absolutePath)
                YoutubeDL.getInstance().execute(req, job.id, false, null)
            } catch (e: YoutubeDL.CanceledException) {
                throw e
            } catch (e: Exception) {
                job.warnings.add("Lỗi tải phụ đề ${LANG_NAME[lang]}: ${shortErr(e).take(150)}"); continue
            }
            val f = dir.listFiles()?.firstOrNull { it.name.startsWith("sub.$key.") }
            val c = f?.let { runCatching { Subs.load(it) }.getOrNull() } ?: emptyList()
            if (c.isEmpty()) { job.warnings.add("Phụ đề ${LANG_NAME[lang]} rỗng."); continue }
            result[lang] = c
            if (kind == "auto") job.warnings.add("Phụ đề ${LANG_NAME[lang]} là bản ${if (lang == "en") "tự động" else "YouTube tự dịch"} (có thể chưa chuẩn).")
        }
        return result
    }

    // ------------------------------------------------------------ ffmpeg
    private val ffmpegProc = java.util.concurrent.ConcurrentHashMap<String, Process>()

    private fun ffmpeg(job: Job, args: List<String>, cwd: File, duration: Double, label: String) {
        job.stage = label; job.pct = if (duration > 0) 0.0 else null
        val bin = File(app.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pk = File(app.noBackupFilesDir, "youtubedl-android/packages")
        val cmd = listOf(bin.absolutePath, "-y", "-hide_banner", "-loglevel", "error", "-progress", "pipe:1", "-nostats") + args
        val errFile = File(cwd, "ffmpeg_err.txt")
        val pb = ProcessBuilder(cmd).directory(cwd).redirectError(errFile)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", "${pk.absolutePath}/ffmpeg/usr/lib:${pk.absolutePath}/python/usr/lib")
            put("FONTCONFIG_FILE", fontConf().absolutePath)
            put("HOME", cwd.absolutePath)
            put("TMPDIR", app.cacheDir.absolutePath)
        }
        val p = pb.start()
        ffmpegProc[job.id] = p
        p.inputStream.bufferedReader().forEachLine { line ->
            if (duration > 0 && line.startsWith("out_time_us=")) {
                line.substringAfter('=').toLongOrNull()?.let { job.pct = (it / 1e6 / duration * 100).coerceIn(0.0, 99.0) }
            }
        }
        val code = p.waitFor()
        checkCancel(job)
        if (code != 0) {
            val err = runCatching { errFile.readText() }.getOrDefault("").takeLast(400)
            throw RuntimeException("FFmpeg lỗi ($code): $err")
        }
    }

    private fun fontConf(): File {
        val f = File(app.filesDir, "fonts.conf")
        if (!f.exists()) {
            File(app.cacheDir, "fccache").mkdirs()
            f.writeText("""<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig><dir>/system/fonts</dir><cachedir>${File(app.cacheDir, "fccache").absolutePath}</cachedir></fontconfig>
""")
        }
        return f
    }

    /** Chép 1 font hỗ trợ tiếng Việt vào thư mục job/fonts, trả về tên font. */
    private fun prepareFont(dir: File): String {
        val fonts = File(dir, "fonts").apply { mkdirs() }
        val sys = File("/system/fonts")
        val cands = listOf("Roboto-Regular.ttf" to "Roboto", "Roboto-Bold.ttf" to "Roboto",
            "NotoSans-Regular.ttf" to "Noto Sans", "DroidSans.ttf" to "Droid Sans")
        for ((file, family) in cands) {
            val f = File(sys, file)
            if (f.exists()) {
                runCatching { f.copyTo(File(fonts, file), true) }
                return family
            }
        }
        return "Roboto"
    }

    // ------------------------------------------------------------ lưu file
    private fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "srt" -> "application/x-subrip"
        else -> "application/octet-stream"
    }

    private fun publish(job: Job, src: File, displayName: String) {
        val mime = mimeOf(src)
        val cr = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YTSub")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("Không lưu được $displayName")
        cr.openOutputStream(uri)!!.use { os -> src.inputStream().use { it.copyTo(os) } }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        job.files.add(OutFile(displayName, uri.toString(), mime))
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "").trim(' ', '.').take(80).trim().ifEmpty { "video" }

    // ------------------------------------------------------------ mở / chia sẻ
    fun viewIntent(uri: String, mime: String) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(uri), mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(uri: String, mime: String): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = mime; putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Chia sẻ").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
