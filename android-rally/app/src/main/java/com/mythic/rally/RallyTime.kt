package com.mythic.rally

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object RallyTime {
    private const val CALIBRATION_PREFERENCES = "game_clock_calibration"
    private const val CALIBRATION_KEY = "offset_millis"
    private const val ANCHOR_UTC_KEY = "anchor_utc_millis"
    private const val ANCHOR_WALL_KEY = "anchor_wall_millis"
    private const val NTP_EPOCH_DELTA = 2_208_988_800L
    private val NTP_SERVERS = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")

    /**
     * Neutral baseline: do not advance or delay device time until a measured
     * game-vs-device offset justifies a correction.
     */
    const val DEFAULT_GAME_CLOCK_OFFSET_MILLIS: Long = 0L
    private val LEGACY_DEFAULT_OFFSETS_MILLIS = setOf(-110L, 50L)
    const val CALIBRATION_STEP_MILLIS: Long = 10L
    private const val MAX_CALIBRATION_OFFSET_MILLIS: Long = 10_000L

    @Volatile private var calibrationOffsetMillis = DEFAULT_GAME_CLOCK_OFFSET_MILLIS
    @Volatile private var calibrationPreferences: SharedPreferences? = null
    private data class ClockAnchor(val utcMs: Long, val elapsedMs: Long)
    @Volatile private var clockAnchor = ClockAnchor(0L, 0L)
    private val syncExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "game-clock-sntp").apply { isDaemon = true }
    }
    @Volatile private var initialized = false
    private data class SntpSample(val roundTripMs: Long, val utcAtReceiveMs: Long, val elapsedAtReceiveMs: Long)

    /** Load the persistent calibration before any overlay or shared ticker starts. */
    fun initialize(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(CALIBRATION_PREFERENCES, Context.MODE_PRIVATE)
        calibrationPreferences = preferences
        val storedOffset = preferences.getLong(CALIBRATION_KEY, DEFAULT_GAME_CLOCK_OFFSET_MILLIS)
        val wallNow = System.currentTimeMillis()
        val savedAnchor = preferences.getLong(ANCHOR_UTC_KEY, 0L)
        val savedWall = preferences.getLong(ANCHOR_WALL_KEY, 0L)
        val restoredAnchor = if (savedAnchor > 0L && savedWall > 0L && wallNow >= savedWall) {
            savedAnchor + (wallNow - savedWall)
        } else {
            savedAnchor.takeIf { it > 0L } ?: wallNow
        }
        clockAnchor = ClockAnchor(restoredAnchor, SystemClock.elapsedRealtime())
        initialized = true
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
        syncExecutor.execute { synchronizeReference() }
        syncExecutor.scheduleAtFixedRate({ synchronizeReference() }, 10, 10, TimeUnit.MINUTES)
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

    /**
     * Monotonic application-wide game clock. Wall time is used only to establish
     * or restore the anchor; all running-time progression uses elapsedRealtime.
     */
    fun nowMillis(): Long {
        if (!initialized) return correctedEpochMillis(System.currentTimeMillis())
        val anchor = clockAnchor
        return anchor.utcMs + (SystemClock.elapsedRealtime() - anchor.elapsedMs) + calibrationOffsetMillis
    }

    fun requestSync() {
        if (initialized) syncExecutor.execute { synchronizeReference() }
    }

    private fun synchronizeReference() {
        val samples = ArrayList<SntpSample>()
        NTP_SERVERS.forEach { host ->
            repeat(3) {
                runCatching { querySntp(host) }.getOrNull()?.let(samples::add)
            }
        }
        val best = samples.minByOrNull { it.roundTripMs } ?: return
        clockAnchor = ClockAnchor(best.utcAtReceiveMs, best.elapsedAtReceiveMs)
        calibrationPreferences?.edit()
            ?.putLong(ANCHOR_UTC_KEY, best.utcAtReceiveMs)
            ?.putLong(ANCHOR_WALL_KEY, System.currentTimeMillis())
            ?.apply()
    }

    private fun querySntp(host: String): SntpSample {
        val address = InetAddress.getAllByName(host).first()
        DatagramSocket().use { socket ->
            socket.soTimeout = 1500
            val request = ByteArray(48)
            request[0] = 0x23
            val wallSent = System.currentTimeMillis()
            writeTimestamp(request, 40, wallSent)
            socket.send(DatagramPacket(request, request.size, address, 123))
            val response = ByteArray(48)
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            val wallReceived = System.currentTimeMillis()
            val elapsedReceived = SystemClock.elapsedRealtime()
            val serverReceive = readTimestamp(response, 32)
            val serverTransmit = readTimestamp(response, 40)
            val roundTrip = ((wallReceived - wallSent) - (serverTransmit - serverReceive)).coerceAtLeast(0L)
            val offset = ((serverReceive - wallSent) + (serverTransmit - wallReceived)) / 2L
            return SntpSample(roundTrip, wallReceived + offset, elapsedReceived)
        }
    }

    private fun readTimestamp(packet: ByteArray, offset: Int): Long {
        val seconds = readUnsignedInt(packet, offset)
        val fraction = readUnsignedInt(packet, offset + 4)
        return (seconds - NTP_EPOCH_DELTA) * 1000L + ((fraction * 1000L) ushr 32)
    }

    private fun readUnsignedInt(packet: ByteArray, offset: Int): Long =
        ((packet[offset].toLong() and 0xffL) shl 24) or
            ((packet[offset + 1].toLong() and 0xffL) shl 16) or
            ((packet[offset + 2].toLong() and 0xffL) shl 8) or
            (packet[offset + 3].toLong() and 0xffL)

    private fun writeTimestamp(packet: ByteArray, offset: Int, epochMillis: Long) {
        val seconds = epochMillis / 1000L + NTP_EPOCH_DELTA
        val fraction = ((epochMillis % 1000L) * (1L shl 32)) / 1000L
        for (i in 0..3) {
            packet[offset + i] = (seconds ushr (24 - i * 8)).toByte()
            packet[offset + 4 + i] = (fraction ushr (24 - i * 8)).toByte()
        }
    }

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