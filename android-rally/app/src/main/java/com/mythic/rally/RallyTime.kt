package com.mythic.rally

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object RallyTime {
    /** Single adjustable calibration against the game's UTC+8 clock. */
    const val GAME_CLOCK_OFFSET_MILLIS: Long = 350L

    /** Convert device epoch time to the calibrated game epoch before display or calculation. */
    fun correctedEpochMillis(deviceEpochMillis: Long): Long = deviceEpochMillis + GAME_CLOCK_OFFSET_MILLIS

    /** The only device wall-clock read used by the shared UTC ticker. */
    fun nowMillis(): Long = correctedEpochMillis(System.currentTimeMillis())

    /** Milliseconds until the next exact epoch-second boundary. */
    fun millisUntilNextSecond(epochMillis: Long = nowMillis()): Long =
        1000L - Math.floorMod(epochMillis, 1000L)

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