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
import android.view.Choreographer
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
        const val ACTION_LANGUAGE_CHANGED = "com.mythic.rally.REINFORCEMENT_LANGUAGE_CHANGED"
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
    // Service Context is not attached during Kotlin property initialization.
    private var oldCircleXPx = 0
    private var trackingCircle = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startCircleX = 0
    private var startWindowY = 0
    private var expandedPanelWidthPx = 0
    private var expandedPanelWidthCss = 300
    private var expandedLayout = false
    private var overlayFramePending = false
    private var pendingOverlayX = 0
    private var pendingOverlayY = 0
    private val overlayFrameCallback = Choreographer.FrameCallback {
        overlayFramePending = false
        if (isOverlayAttached && webViewCreated) {
            params.x = pendingOverlayX
            params.y = pendingOverlayY
            runCatching { windowManager.updateViewLayout(webView, params) }
        }
    }
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val utcTimeListener: (Long) -> Unit = { epochMillis ->
        if (pageLoaded && webViewCreated) {
            runCatching {
                webView.evaluateJavascript("window.setUtcTime && window.setUtcTime($epochMillis)", null)
            }
        }
    }
    private val calibrationListener: (Long) -> Unit = { deltaMillis ->
        if (pageLoaded && webViewCreated && deltaMillis != 0L) {
            runCatching {
                webView.evaluateJavascript("window.applyGameClockCalibrationDelta && window.applyGameClockCalibrationDelta($deltaMillis)", null)
            }
        }
    }
    private val languageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val code = intent?.getStringExtra("language") ?: return
            if (pageLoaded) {
                webView.evaluateJavascript("window.setAppLanguage && window.setAppLanguage(${org.json.JSONObject.quote(code)})", null)
            }
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        oldCircleXPx = dp(INITIAL_CIRCLE_X_DP)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        createWebView()
        val languageFilter = android.content.IntentFilter(ACTION_LANGUAGE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(languageReceiver, languageFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(languageReceiver, languageFilter)
        UtcClockTicker.addListener(utcTimeListener)
        UtcClockTicker.addCalibrationListener(calibrationListener)
        prefs.edit().remove("language_mode").putBoolean("window_attached", false).apply()
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
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageLoaded = true
                    view?.evaluateJavascript(
                        "window.setUtcTime && window.setUtcTime(${UtcClockTicker.currentTimeMillis()})",
                        null
                    )
                }
            }
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
            x = (((screen.widthPixels - width) / 2) - dp(INITIAL_CIRCLE_X_DP)).coerceAtLeast(0)
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
        Choreographer.getInstance().removeFrameCallback(overlayFrameCallback)
        overlayFramePending = false

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
        val circleScreenX = params.x + oldCircleXPx
        params.x = (circleScreenX - closedCircleX)
            .coerceIn(0, max(0, screen.widthPixels - closedSize))
        params.y = params.y.coerceIn(0, max(0, screen.heightPixels - closedHeight))
        params.width = closedSize
        params.height = closedHeight
        expandedLayout = false
        expandedPanelWidthPx = 0
        circleXdp = INITIAL_CIRCLE_X_DP
        oldCircleXPx = dp(INITIAL_CIRCLE_X_DP)
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
                startCircleX = params.x + oldCircleXPx
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
                    val targetCircleX = startCircleX + dx
                    if (expandedLayout) {
                        updateExpandedGeometry(targetCircleX, screen.widthPixels)
                    } else {
                        val circleSize = dp(CIRCLE_SIZE_DP)
                        val safeCircleX = targetCircleX.coerceIn(0, max(0, screen.widthPixels - circleSize))
                        params.x = safeCircleX - oldCircleXPx
                    }
                    params.y = (startWindowY + dy).coerceIn(0, max(0, screen.heightPixels - params.height))
                    scheduleOverlayLayout()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!trackingCircle) return false
                val wasDragging = dragging
                trackingCircle = false
                dragging = false
                if (wasDragging) {
                    flushOverlayLayout()
                    prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                } else {
                    webView.evaluateJavascript("toggle()", null)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                val wasTracking = trackingCircle
                if (dragging) flushOverlayLayout()
                trackingCircle = false
                dragging = false
                return wasTracking
            }
        }
        return trackingCircle
    }

    // Apply drag positions on display frames so the orb and its panel move as
    // one WebView window, without queuing multiple WindowManager updates per frame.
    private fun scheduleOverlayLayout() {
        pendingOverlayX = params.x
        pendingOverlayY = params.y
        if (!overlayFramePending) {
            overlayFramePending = true
            Choreographer.getInstance().postFrameCallback(overlayFrameCallback)
        }
    }

    private fun flushOverlayLayout() {
        if (overlayFramePending) {
            Choreographer.getInstance().removeFrameCallback(overlayFrameCallback)
            overlayFramePending = false
        }
        pendingOverlayX = params.x
        pendingOverlayY = params.y
        if (isOverlayAttached && webViewCreated) {
            runCatching { windowManager.updateViewLayout(webView, params) }
        }
    }

    private fun updateExpandedGeometry(circleScreenX: Int, screenWidth: Int, forceStyle: Boolean = false) {
        // Keep the orb centered over the panel. The WebView is one window, so
        // moving its LayoutParams moves the orb and panel together in one frame.
        val circleSizePx = dp(CIRCLE_SIZE_DP)
        val windowWidthPx = dp(expandedPanelWidthCss + 12)
            .coerceAtMost(screenWidth.coerceAtLeast(1))
        val circleOffsetPx = ((windowWidthPx - circleSizePx).coerceAtLeast(0)) / 2

        params.width = windowWidthPx
        params.x = (circleScreenX - circleOffsetPx)
            .coerceIn(0, max(0, screenWidth - windowWidthPx))
        circleXdp = ((expandedPanelWidthCss + 12 - CIRCLE_SIZE_DP).coerceAtLeast(0)) / 2
        oldCircleXPx = circleOffsetPx
    }

    private inner class RallyBridge {
        @JavascriptInterface
        fun getUtcTimeMillis(): Long = UtcClockTicker.currentTimeMillis()

        @JavascriptInterface
        fun getUtcDisplayTimeMillis(): Long = UtcClockTicker.currentTimeMillis()

        @JavascriptInterface
        fun getGameClockCalibrationMillis(): Long = RallyTime.calibrationOffsetMillis()

        @JavascriptInterface
        fun getGameClockCalibrationStepMillis(): Long = RallyTime.CALIBRATION_STEP_MILLIS

        @JavascriptInterface
        fun setGameClockCalibrationMillis(value: Long) {
            UtcClockTicker.setCalibrationOffsetMillis(value)
        }

        @JavascriptInterface
        fun getAppLanguage(): String = getSharedPreferences("rally_native", MODE_PRIVATE).getString("language", null)
            ?: if (java.util.Locale.getDefault().language.equals("ar", true)) "ar" else "en"

        @JavascriptInterface
        fun getToolLanguage(): String = "app"

        @JavascriptInterface
        fun setToolLanguage(value: String) {
            prefs.edit().remove("language_mode").apply()
        }

        @JavascriptInterface
        fun setLayout(open: Boolean, w: Int, h: Int, circleX: Int) {
            mainHandler.post {
                if (!webViewCreated || !isOverlayAttached) return@post

                flushOverlayLayout()
                val screen = resources.displayMetrics
                val newHeight = dp(h).coerceIn(1, screen.heightPixels.coerceAtLeast(1))
                val circleScreenX = params.x + oldCircleXPx

                if (open) {
                    expandedPanelWidthCss = (w - 12).coerceAtLeast(1)
                    expandedPanelWidthPx = dp(w)
                    expandedLayout = true
                    updateExpandedGeometry(circleScreenX, screen.widthPixels)
                } else {
                    val closedSize = dp(INITIAL_SIZE_DP).coerceAtMost(screen.widthPixels)
                    val newCircleX = dp(circleX)
                    params.width = closedSize
                    params.x = (circleScreenX - newCircleX)
                        .coerceIn(0, max(0, screen.widthPixels - closedSize))
                    expandedPanelWidthPx = 0
                    expandedLayout = false
                    circleXdp = circleX
                    oldCircleXPx = newCircleX
                }
                params.height = newHeight
                params.y = params.y.coerceIn(0, max(0, screen.heightPixels - newHeight))
                params.flags = if (open) {
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                } else {
                    params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                }
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

                runCatching { windowManager.updateViewLayout(webView, params) }
                prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
            }
        }
    }

    private fun appLanguage(): String =
        getSharedPreferences("rally_native", MODE_PRIVATE).getString("language", null)
            ?: if (java.util.Locale.getDefault().language.equals("ar", true)) "ar" else "en"

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                if (appLanguage() == "en") "Reinforcements" else "أداة التعزيزات",
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
        val english = appLanguage() == "en"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rally)
            .setContentTitle(if (english) "Reinforcements" else "أداة التعزيزات")
            .setContentText(if (english) "The tool is ready" else "خدمة الأداة جاهزة")
            .setOngoing(true)
            .addAction(0, if (english) "Stop" else "إيقاف", stopIntent)
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
        Choreographer.getInstance().removeFrameCallback(overlayFrameCallback)
        overlayFramePending = false
        runCatching { unregisterReceiver(languageReceiver) }
        UtcClockTicker.removeListener(utcTimeListener)
        UtcClockTicker.removeCalibrationListener(calibrationListener)
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