package com.mythic.rally

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ReinforcementOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var webView: WebView
    private lateinit var params: WindowManager.LayoutParams
    private val prefs by lazy { getSharedPreferences("reinforcement_overlay", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var expanded = false
    private var dragging = false
    private var dragEligible = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
    }

    private fun createOverlay() {
        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(OverlayBridge(), "AndroidOverlay")
            loadUrl("file:///android_asset/reinforcement.html")
        }

        params = WindowManager.LayoutParams(
            dp(COLLAPSED_SIZE),
            dp(COLLAPSED_SIZE),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = prefs.getInt("x", (resources.displayMetrics.widthPixels - dp(COLLAPSED_SIZE)) / 2).coerceAtLeast(0)
            y = prefs.getInt("y", -dp(10))
        }

        webView.setOnTouchListener { _, event -> handleWindowDrag(event) }
        windowManager.addView(webView, params)
    }

    private fun handleWindowDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val bubbleLeft = if (expanded) (params.width - dp(COLLAPSED_SIZE)) / 2 else 0
                dragEligible = event.x >= bubbleLeft && event.x <= bubbleLeft + dp(COLLAPSED_SIZE) &&
                    event.y >= 0 && event.y <= dp(COLLAPSED_SIZE)
                if (!dragEligible) return false
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                dragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragEligible) return false
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                if (dragging) {
                    val width = if (expanded) dp(EXPANDED_WIDTH) else dp(COLLAPSED_SIZE)
                    val height = if (expanded) expandedHeight() else dp(COLLAPSED_SIZE)
                    params.x = (startX + dx).coerceIn(0, max(0, resources.displayMetrics.widthPixels - width))
                    params.y = (startY + dy).coerceIn(-dp(10), max(0, resources.displayMetrics.heightPixels - height))
                    windowManager.updateViewLayout(webView, params)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                dragEligible = false
                if (wasDragging) {
                    prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    return true
                }
            }
        }
        return false
    }

    private inner class OverlayBridge {
        @JavascriptInterface
        fun setExpanded(open: Boolean) {
            mainHandler.post {
                if (expanded == open || !::webView.isInitialized) return@post
                expanded = open
                val centerX = params.x + if (open) dp(COLLAPSED_SIZE) / 2 else dp(EXPANDED_WIDTH) / 2
                val topY = params.y
                params.width = if (open) dp(EXPANDED_WIDTH) else dp(COLLAPSED_SIZE)
                params.height = if (open) expandedHeight() else dp(COLLAPSED_SIZE)
                params.x = (centerX - params.width / 2).coerceIn(0, max(0, resources.displayMetrics.widthPixels - params.width))
                params.y = topY.coerceIn(-dp(10), max(0, resources.displayMetrics.heightPixels - params.height))
                params.flags = if (open) {
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                } else {
                    params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                }
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                windowManager.updateViewLayout(webView, params)
                webView.post {
                    webView.evaluateJavascript(
                        "(function(){var r=document.getElementById('w').getBoundingClientRect(),p=document.getElementById('p'),W=68,pw=Math.min(300,innerWidth-16),l=Math.min(Math.max(8,r.left+W/2-pw/2),innerWidth-pw-8);p.style.left=(l-r.left)+'px';p.style.right='auto';if(r.top+W/2>innerHeight/2){p.style.bottom=(W+8)+'px';p.style.top='auto'}else{p.style.top=(W+8)+'px';p.style.bottom='auto'}})()",
                        null
                    )
                }
                prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
            }
        }
    }

    private fun expandedHeight(): Int {
        val available = resources.displayMetrics.heightPixels - dp(24)
        return min(dp(EXPANDED_HEIGHT), max(dp(300), available))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "أداة التعزيزات", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, ReinforcementOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("أداة التعزيزات")
            .setContentText("الأداة العائمة تعمل")
            .setOngoing(true)
            .addAction(0, "إغلاق", stopIntent)
            .build()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            runCatching { windowManager.removeView(webView) }
            webView.removeJavascriptInterface("AndroidOverlay")
            webView.destroy()
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.mythic.rally.REINFORCEMENT_STOP"
        private const val CHANNEL_ID = "reinforcement_overlay"
        private const val NOTIFICATION_ID = 4202
        private const val COLLAPSED_SIZE = 68
        private const val EXPANDED_WIDTH = 316
        private const val EXPANDED_HEIGHT = 600
    }
}
