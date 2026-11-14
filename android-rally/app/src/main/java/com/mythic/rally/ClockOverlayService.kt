package com.mythic.rally

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.ServiceCompat

class ClockOverlayService : Service() {
    companion object {
        @Volatile
        var running: Boolean = false
            private set

        private const val CHANNEL_ID = "utc_clock"
        private const val NOTIFICATION_ID = 4107
    }

    private lateinit var windowManager: WindowManager
    private var clockView: UtcClockView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (clockView != null) return START_NOT_STICKY
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        running = true
        showClock()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.utc_notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(R.drawable.ic_stat_rally)
            .setContentTitle(getString(R.string.utc_notif_title))
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun showClock() {
        val prefs = getSharedPreferences("utc_clock", Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val size = (122f * density).toInt()
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - size).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - size).coerceAtLeast(0)

        val layout = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", (24f * density).toInt()).coerceIn(0, maxX)
            y = prefs.getInt("y", (200f * density).toInt()).coerceIn(0, maxY)
        }

        val view = UtcClockView(this)
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layout.x
                    startY = layout.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = (startX + event.rawX - touchX).toInt().coerceIn(0, maxX)
                    layout.y = (startY + event.rawY - touchY).toInt().coerceIn(0, maxY)
                    runCatching { windowManager.updateViewLayout(view, layout) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("x", layout.x).putInt("y", layout.y).apply()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, layout)
        clockView = view
    }

    override fun onDestroy() {
        running = false
        clockView?.let { view -> runCatching { windowManager.removeView(view) } }
        clockView = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }
}
