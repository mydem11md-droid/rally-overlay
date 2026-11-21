package com.mythic.rally

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object RallyTime {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeListeners = LinkedHashSet<(Long) -> Unit>()
    private var tickScheduled = false

    private val sharedTicker = object : Runnable {
        override fun run() {
            tickScheduled = false
            val now = nowMillis()
            timeListeners.toList().forEach { listener -> runCatching { listener(now) } }
            scheduleNextTick(now)
        }
    }

    /** The one UTC epoch source used by every clock and time calculation. */
    fun nowMillis(): Long = System.currentTimeMillis()

    /** Align every subscriber to the same epoch-second boundary. */
    fun millisUntilNextSecond(epochMillis: Long = nowMillis()): Long =
        1000L - Math.floorMod(epochMillis, 1000L)

    fun addClockListener(listener: (Long) -> Unit) {
        runOnMain {
            timeListeners.add(listener)
            listener(nowMillis())
            scheduleNextTick(nowMillis())
        }
    }

    fun removeClockListener(listener: (Long) -> Unit) {
        runOnMain {
            timeListeners.remove(listener)
            if (timeListeners.isEmpty()) {
                mainHandler.removeCallbacks(sharedTicker)
                tickScheduled = false
            }
        }
    }

    private fun scheduleNextTick(now: Long) {
        if (timeListeners.isEmpty() || tickScheduled) return
        tickScheduled = true
        mainHandler.postDelayed(sharedTicker, millisUntilNextSecond(now).coerceAtLeast(1L))
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private val utcTimeZone: TimeZone
        get() = TimeZone.getTimeZone("UTC")

    fun utcParts(epochMillis: Long = nowMillis()): IntArray {
        val calendar = Calendar.getInstance(utcTimeZone, Locale.US).apply {
            timeInMillis = epochMillis
        }
        return intArrayOf(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND)
        )
    }

    fun utcClock(epochMillis: Long = nowMillis()): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).apply {
            timeZone = utcTimeZone
        }.format(Date(epochMillis))

    fun digits(raw: String): String = raw.filter { it in '0'..'9' }.take(4)
    fun preview(raw: String): String {
        val d=digits(raw)
        if(d.length<=2)return d
        val minutes=d.dropLast(2).trimStart('0').ifEmpty { "0" }
        return minutes+":"+d.takeLast(2)
    }
    fun seconds(raw: String): Int? {
        val d=digits(raw);if(d.isEmpty())return null
        val minutes=if(d.length>2)d.dropLast(2).toIntOrNull()?:return null else 0
        val secs=if(d.length>2)d.takeLast(2).toIntOrNull()?:return null else d.toIntOrNull()?:return null
        val total=minutes*60+secs
        return total.takeIf { it<=3600 }
    }
    fun format(total: Int): String = String.format(Locale.US,"%d:%02d",total/60,total%60)
    fun normalize(raw: String): String? = seconds(raw)?.let(::format)
}