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
    private val density = resources.displayMetrics.density
    private val sizePx = (122f * density).toInt()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202B25.toInt() }
    private val trackPaint = ring(0xFF33473B.toInt())
    private val arcPaint = ring(0xFF4CAF50.toInt()).apply { strokeCap = Paint.Cap.ROUND }
    private val utcPaint = text(0xFF9FBFA9.toInt(), 11f).apply { letterSpacing = 0.15f }
    private val timePaint = text(Color.WHITE, 28f)
    private val secondsPaint = text(0xFF9FE6A6.toInt(), 15f)
    private val oval = RectF()

    private var hour = 0
    private var minute = 0
    private var second = 0

    private fun ring(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f * density
        this.color = color
    }

    private fun text(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size * density
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val center = width / 2f
        val scale = width / 122f
        val radius = 50f * scale

        canvas.drawCircle(center, center, center - scale, backgroundPaint)
        oval.set(center - radius, center - radius, center + radius, center + radius)
        canvas.drawCircle(center, center, radius, trackPaint)
        if (second > 0) canvas.drawArc(oval, -90f, second * 6f, false, arcPaint)

        canvas.drawText("UTC", center, center - 19f * scale, utcPaint)
        canvas.drawText(String.format(Locale.US, "%02d:%02d", hour, minute), center, center + 9f * scale, timePaint)
        canvas.drawText(String.format(Locale.US, ":%02d", second), center, center + 29f * scale, secondsPaint)
    }
}
