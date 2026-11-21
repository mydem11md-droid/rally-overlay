package com.mythic.rally

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

class UtcClockView(context: Context) : View(context) {
    companion object {
        const val SIZE_DP = 80
    }

    private val density = resources.displayMetrics.density
    private val sizePx = (SIZE_DP * density).toInt()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202B25.toInt() }
    private val trackPaint = ring(0xFF33473B.toInt())
    private val arcPaint = ring(0xFF4CAF50.toInt()).apply { strokeCap = Paint.Cap.ROUND }
    private val utcPaint = text(0xFF9FBFA9.toInt()).apply { letterSpacing = 0.15f }
    private val timePaint = text(Color.WHITE)
    private val secondsPaint = text(0xFF9FE6A6.toInt())
    private val oval = RectF()

    private var scale = 0f
    private var ringRadius = 0f

    private var hour = 0
    private var minute = 0
    private var second = 0

    private fun ring(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
    }

    private fun text(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            val delay = 1000L - (System.currentTimeMillis() % 1000L)
            postDelayed(this, delay.coerceAtLeast(1L))
        }
    }

    private fun updateTime() {
        val now = LocalTime.now(ZoneOffset.UTC)
        hour = now.hour
        minute = now.minute
        second = now.second
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        updateTime()
        val delay = 1000L - (System.currentTimeMillis() % 1000L)
        postDelayed(ticker, delay.coerceAtLeast(1L))
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val u = w / 122f
        scale = u
        ringRadius = 50f * u

        val ringWidth = 8f * u
        trackPaint.strokeWidth = ringWidth
        arcPaint.strokeWidth = ringWidth

        utcPaint.textSize = 12f * u
        timePaint.textSize = 30f * u
        secondsPaint.textSize = 16f * u
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = width / 2f

        canvas.drawCircle(c, c, c - scale, backgroundPaint)
        oval.set(c - ringRadius, c - ringRadius, c + ringRadius, c + ringRadius)
        canvas.drawCircle(c, c, ringRadius, trackPaint)
        if (second > 0) canvas.drawArc(oval, -90f, second * 6f, false, arcPaint)

        canvas.drawText("UTC", c, c - 19f * scale, utcPaint)
        canvas.drawText(String.format(Locale.US, "%02d:%02d", hour, minute), c, c + 9f * scale, timePaint)
        canvas.drawText(String.format(Locale.US, ":%02d", second), c, c + 29f * scale, secondsPaint)
    }
}
