package com.tuan.ytsub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Tự cập nhật app từ GitHub Releases của repo Apk-YT-download. */
object Updater {
    private const val API = "https://api.github.com/repos/tuantranmarinainter-ops/Apk-YT-download/releases/latest"
    private val pool = Executors.newSingleThreadExecutor()

    @Volatile var state = "idle"      // idle | checking | latest | available | downloading | ready | error
    @Volatile var latestCode = 0L
    @Volatile var notes = ""
    @Volatile var pct = 0
    @Volatile var error: String? = null
    @Volatile private var apkUrl: String? = null
    private var apkFile: File? = null

    fun currentCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    fun json(ctx: Context): JSONObject = JSONObject().apply {
        put("state", state); put("current", currentCode(ctx)); put("currentName", currentName(ctx))
        put("latest", latestCode); put("notes", notes); put("pct", pct); put("error", error ?: JSONObject.NULL)
    }

    private fun get(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "YTSub-Android")
        }

    fun check(ctx: Context) {
        if (state == "checking" || state == "downloading") return
        state = "checking"; error = null
        pool.execute {
            try {
                val c = get(API)
                if (c.responseCode != 200) throw RuntimeException("GitHub trả về mã ${c.responseCode}")
                val r = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                latestCode = r.optString("tag_name").substringAfterLast('-').toLongOrNull() ?: 0
                notes = r.optString("name")
                val want = if (Build.SUPPORTED_ABIS.firstOrNull()?.contains("arm64") == true) "-arm64.apk" else "-arm32.apk"
                val assets = r.optJSONArray("assets")
                apkUrl = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(want) }?.optString("browser_download_url")
                state = if (latestCode > currentCode(ctx) && apkUrl != null) "available" else "latest"
            } catch (e: Exception) {
                state = "error"; error = "Không kiểm tra được cập nhật: ${e.message}"
            }
        }
    }

    fun download(ctx: Context) {
        val url = apkUrl ?: return
        if (state == "downloading") return
        state = "downloading"; pct = 0; error = null
        pool.execute {
            try {
                val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
                val f = File(dir, "YTSub-$latestCode.apk")
                var c = get(url)
                // GitHub chuyển hướng sang máy chủ khác (khác host) → tự theo
                var hops = 0
                while (c.responseCode in 300..399 && hops++ < 5) {
                    val loc = c.getHeaderField("Location") ?: break
                    c.disconnect(); c = get(loc)
                }
                if (c.responseCode != 200) throw RuntimeException("Tải về lỗi mã ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) pct = (done * 100 / total).toInt()
                        }
                    }
                }
                apkFile = f; pct = 100; state = "ready"
            } catch (e: Exception) {
                state = "error"; error = "Tải bản cập nhật lỗi: ${e.message}"
            }
        }
    }

    /** Mở trình cài đặt của Android. Trả về false nếu cần người dùng cấp quyền cài app trước. */
    fun install(ctx: Context): Boolean {
        val f = apkFile ?: return true
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }
}
