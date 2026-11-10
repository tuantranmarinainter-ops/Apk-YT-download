package com.tuan.ytsub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Giữ app sống trong lúc tải (dịch vụ chạy nền có thông báo tiến độ). */
class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private val tick = object : Runnable {
        override fun run() {
            if (Engine.active() == 0) {
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            nm.notify(ID, build())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nm.createNotificationChannel(NotificationChannel(CH, "Tải xuống", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, build(), type)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 500)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun build() = NotificationCompat.Builder(this, CH).apply {
        val j = Engine.current()
        val n = Engine.active()
        setSmallIcon(android.R.drawable.stat_sys_download)
        setContentTitle(j?.info?.optString("title")?.takeIf { it.isNotEmpty() } ?: "YT Sub Downloader")
        setContentText((j?.stage ?: "Đang chờ…") + if (n > 1) "  ·  còn $n mục" else "")
        setOngoing(true); setOnlyAlertOnce(true); setSilent(true)
        val pct = j?.pct
        if (pct != null) setProgress(100, pct.toInt(), false) else setProgress(0, 0, true)
        setContentIntent(PendingIntent.getActivity(this@DownloadService, 0,
            Intent(this@DownloadService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
    }.build()

    companion object {
        private const val CH = "downloads"
        private const val ID = 7
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            } catch (_: Exception) { /* app đang ở nền, bỏ qua */ }
        }
    }
}
