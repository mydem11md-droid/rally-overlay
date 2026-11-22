package com.mythic.rally

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object RallyTime {
    private const val CALIBRATION_PREFERENCES = "game_clock_calibration"
    private const val CALIBRATION_KEY = "offset_millis"

    /**
     * Neutral baseline: do not advance or delay device time until a measured
     * game-vs-device offset justifies a correction.
     */
    const val DEFAULT_GAME_CLOCK_OFFSET_MILLIS: Long = 0L
    private val LEGACY_DEFAULT_OFFSETS_MILLIS = setOf(-110L, 50L)
    const val CALIBRATION_STEP_MILLIS: Long = 50L
    private const val MAX_CALIBRATION_OFFSET_MILLIS: Long = 10_000L

    @Volatile private var calibrationOffsetMillis = DEFAULT_GAME_CLOCK_OFFSET_MILLIS
    @Volatile private var calibrationPreferences: SharedPreferences? = null

    /** Load the persistent calibration before any overlay or shared ticker starts. */
    fun initialize(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(CALIBRATION_PREFERENCES, Context.MODE_PRIVATE)
        calibrationPreferences = preferences
        val storedOffset = preferences.getLong(CALIBRATION_KEY, DEFAULT_GAME_CLOCK_OFFSET_MILLIS)
        // Remove either shipped default correction; preserve user-selected non-default calibration.
        val migratedOffset = if (storedOffset in LEGACY_DEFAULT_OFFSETS_MILLIS) {
            DEFAULT_GAME_CLOCK_OFFSET_MILLIS
        } else {
            storedOffset
        }
        calibrationOffsetMillis = migratedOffset.coerceIn(-MAX_CALIBRATION_OFFSET_MILLIS, MAX_CALIBRATION_OFFSET_MILLIS)
        if (!preferences.contains(CALIBRATION_KEY) || storedOffset != calibrationOffsetMillis) {
            preferences.edit().putLong(CALIBRATION_KEY, calibrationOffsetMillis).apply()
        }
    }

    fun calibrationOffsetMillis(): Long = calibrationOffsetMillis

    /** The only setter for the shared, persisted correction. */
    fun setCalibrationOffsetMillis(value: Long): Long {
        val normalized = value.coerceIn(-MAX_CALIBRATION_OFFSET_MILLIS, MAX_CALIBRATION_OFFSET_MILLIS)
        calibrationOffsetMillis = normalized
        calibrationPreferences?.edit()?.putLong(CALIBRATION_KEY, normalized)?.apply()
        return normalized
    }

    /** Convert device epoch time to the calibrated game epoch before display or calculation. */
    fun correctedEpochMillis(deviceEpochMillis: Long): Long =
        correctedEpochMillis(deviceEpochMillis, calibrationOffsetMillis)

    internal fun correctedEpochMillis(deviceEpochMillis: Long, offsetMillis: Long): Long =
        deviceEpochMillis + offsetMillis

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