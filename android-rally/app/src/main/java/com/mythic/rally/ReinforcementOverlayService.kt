package com.mythic.rally

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ReinforcementOverlayService : Service() {
    companion object {
        const val ACTION_TOGGLE = "com.mythic.rally.REINFORCEMENT_TOGGLE"
        const val ACTION_STOP = "com.mythic.rally.REINFORCEMENT_STOP"
        const val ACTION_STATE_CHANGED = "com.mythic.rally.REINFORCEMENT_STATE_CHANGED"
        private const val CHANNEL_ID = "reinforcement_overlay"
        private const val NOTIFICATION_ID = 4202
        private const val INITIAL_SIZE_DP = 84
        private const val CIRCLE_SIZE_DP = 68
        private const val INITIAL_CIRCLE_X_DP = 8
        private const val CIRCLE_TOP_DP = 8

        @Volatile var isServiceAlive: Boolean = false
            private set
        @Volatile var isOverlayAttached: Boolean = false
            private set
    }

    private val prefs by lazy { getSharedPreferences("reinforcement_overlay", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var webView: WebView
    private lateinit var params: WindowManager.LayoutParams
    private var webViewCreated = false
    private var pageLoaded = false
    private var circleXdp = INITIAL_CIRCLE_X_DP
    private var oldCircleXPx = dp(INITIAL_CIRCLE_X_DP)
    private var trackingCircle = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startWindowX = 0
    private var startWindowY = 0
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        createWebView()
        prefs.edit().putBoolean("window_attached", false).apply()
        publishState()
    }

    private fun createWebView() {
        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                settings.forceDark = WebSettings.FORCE_DARK_OFF
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                settings.setAlgorithmicDarkeningAllowed(false)
            }
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(RallyBridge(), "RallyBridge")
        }
        webViewCreated = true

        val screen = resources.displayMetrics
        val width = dp(INITIAL_SIZE_DP).coerceAtMost(screen.widthPixels)
        val height = dp(INITIAL_SIZE_DP).coerceAtMost(screen.heightPixels)
        params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screen.widthPixels - width) / 2).coerceAtLeast(0)
            y = dp(10).coerceIn(0, max(0, screen.heightPixels - height))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        webView.setOnTouchListener { _, event -> handleCircleTouch(event) }
    }

    private fun attachWindow() {
        if (!webViewCreated || isOverlayAttached) return
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        runCatching {
            windowManager.addView(webView, params)
            isOverlayAttached = true
            prefs.edit()
                .putBoolean("window_attached", true)
                .putInt("x", params.x)
                .putInt("y", params.y)
                .apply()
            if (!pageLoaded) {
                pageLoaded = true
                webView.loadUrl("file:///android_asset/timing-overlay.html")
            }
            publishState()
        }.onFailure {
            isOverlayAttached = false
            prefs.edit().putBoolean("window_attached", false).apply()
            publishState()
            stopSelf()
        }
    }

    private fun detachWindow() {
        if (!isOverlayAttached) return

        // Collapse the page before detaching, but retain this exact WebView and its localStorage state.
        if (pageLoaded) {
            runCatching {
                webView.evaluateJavascript(
                    "(function(){var p=document.getElementById('p');if(p&&!p.hidden)toggle()})()",
                    null
                )
                webView.clearFocus()
            }
        }
        val screen = resources.displayMetrics
        val closedSize = dp(INITIAL_SIZE_DP).coerceAtMost(screen.widthPixels)
        val closedHeight = dp(INITIAL_SIZE_DP).coerceAtMost(screen.heightPixels)
        val closedCircleX = dp(INITIAL_CIRCLE_X_DP)
        params.x = (params.x + oldCircleXPx - closedCircleX)
            .coerceIn(0, max(0, screen.widthPixels - closedSize))
        params.y = params.y.coerceIn(0, max(0, screen.heightPixels - closedHeight))
        params.width = closedSize
        params.height = closedHeight
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        circleXdp = INITIAL_CIRCLE_X_DP
        oldCircleXPx = closedCircleX
        runCatching { windowManager.removeView(webView) }
        isOverlayAttached = false
        prefs.edit().putBoolean("window_attached", false)
            .putInt("x", params.x).putInt("y", params.y).apply()
        publishState()
    }

    private fun handleCircleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val circleLeft = dp(circleXdp)
                val circleTop = dp(CIRCLE_TOP_DP)
                val circleSize = dp(CIRCLE_SIZE_DP)
                trackingCircle = event.x >= circleLeft &&
                    event.x <= circleLeft + circleSize &&
                    event.y >= circleTop &&
                    event.y <= circleTop + circleSize
                if (!trackingCircle) return false

                dragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                startWindowX = params.x
                startWindowY = params.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!trackingCircle) return false
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    val screen = resources.displayMetrics
                    params.x = (startWindowX + dx).coerceIn(0, max(0, screen.widthPixels - params.width))
                    params.y = (startWindowY + dy).coerceIn(0, max(0, screen.heightPixels - params.height))
                    runCatching { windowManager.updateViewLayout(webView, params) }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!trackingCircle) return false
                val wasDragging = dragging
                trackingCircle = false
                dragging = false
                if (wasDragging) {
                    prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                } else {
                    webView.evaluateJavascript("toggle()", null)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                val wasTracking = trackingCircle
                trackingCircle = false
                dragging = false
                return wasTracking
            }
        }
        return trackingCircle
    }

    private inner class RallyBridge {
        @JavascriptInterface
        fun setLayout(open: Boolean, w: Int, h: Int, circleX: Int) {
            mainHandler.post {
                if (!webViewCreated || !isOverlayAttached) return@post

                val screen = resources.displayMetrics
                val newWidth = dp(w).coerceIn(1, screen.widthPixels.coerceAtLeast(1))
                val newHeight = dp(h).coerceIn(1, screen.heightPixels.coerceAtLeast(1))
                val newCircleX = dp(circleX)
                val proposedX = params.x + oldCircleXPx - newCircleX

                params.width = newWidth
                params.height = newHeight
                params.x = proposedX.coerceIn(0, max(0, screen.widthPixels - newWidth))
                params.y = params.y.coerceIn(0, max(0, screen.heightPixels - newHeight))
                params.flags = if (open) {
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                } else {
                    params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                }
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

                circleXdp = circleX
                oldCircleXPx = newCircleX
                runCatching { windowManager.updateViewLayout(webView, params) }
                prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "أداة التعزيزات",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ReinforcementOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("أداة التعزيزات")
            .setContentText("خدمة الأداة جاهزة")
            .setOngoing(true)
            .addAction(0, "إيقاف", stopIntent)
            .build()
    }

    private fun publishState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE, null -> {
                if (isOverlayAttached) detachWindow() else attachWindow()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (isOverlayAttached) {
            runCatching { windowManager.removeView(webView) }
        }
        isOverlayAttached = false
        if (webViewCreated) {
            runCatching { webView.removeJavascriptInterface("RallyBridge") }
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
        webViewCreated = false
        pageLoaded = false
        isServiceAlive = false
        prefs.edit().putBoolean("window_attached", false).apply()
        publishState()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}