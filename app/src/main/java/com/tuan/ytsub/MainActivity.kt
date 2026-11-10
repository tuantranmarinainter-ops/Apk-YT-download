package com.tuan.ytsub

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var pageReady = false
    private var pendingShare: String? = null
    private val pool = Executors.newCachedThreadPool()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0f1115")
        window.navigationBarColor = Color.parseColor("#0f1115")
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0f1115"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pendingShare?.let { deliverShare(it) }
                pendingShare = null
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val text = when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        } ?: return
        if (pageReady) deliverShare(text) else pendingShare = text
    }

    private fun deliverShare(text: String) {
        web.evaluateJavascript("window.onShared(${JSONObject.quote(text)})", null)
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    inner class Bridge {
        @JavascriptInterface fun status(): String = Engine.statusJson().toString()

        @JavascriptInterface fun jobs(): String = Engine.jobsJson().toString()

        @JavascriptInterface fun info(url: String, cb: String) {
            pool.execute {
                val r = Engine.info(url)
                js("window.__cb(${JSONObject.quote(cb)}, ${r})")
            }
        }

        @JavascriptInterface fun download(json: String): String {
            val d = JSONObject(json)
            val urls = Regex("https?://\\S+").findAll(d.optString("urls")).map { it.value }.toList()
            if (urls.isEmpty()) return JSONObject().put("error", "Chưa có link hợp lệ.").toString()
            val ids = Engine.enqueue(urls, Opts(
                mode = d.optString("mode", "video"), quality = d.optString("quality", "1080"),
                subLang = d.optString("sub_lang", "both"), attach = d.optString("attach", "srt"),
                font = d.optString("font", "m")))
            return JSONObject().put("ids", org.json.JSONArray(ids)).toString()
        }

        @JavascriptInterface fun cancel(id: String) = Engine.cancel(id)

        @JavascriptInterface fun clear() { Engine.clearFinished() }

        @JavascriptInterface fun updateYtdlp() = Engine.updateYtdlp()

        @JavascriptInterface fun open(uri: String, mime: String) {
            try { startActivity(Engine.viewIntent(uri, mime)) }
            catch (e: ActivityNotFoundException) { toast("Không có app mở được loại file này — hãy dùng Chia sẻ") }
        }

        @JavascriptInterface fun share(uri: String, mime: String) {
            try { startActivity(Engine.shareIntent(uri, mime)) } catch (e: Exception) { toast("Không chia sẻ được") }
        }

        @JavascriptInterface fun openFolder() {
            try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { toast("Mở app Quản lý file → Download/YTSub") }
        }

        @JavascriptInterface fun paste(): String {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            return cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }
    }
}
