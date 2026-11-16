package com.github.andreyasadchy.xtra.util

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Tracks Twitch's authoritative server clock so stream latency ("Stats for nerds") and live chat
 * synchronization remain accurate even when an Android TV box has a drifted system clock.
 *
 * Calibrates with millisecond precision from `#EXT-X-TWITCH-INFO:...,SERVER-TIME="..."` in HLS
 * playlists, and falls back to RFC-1123 HTTP `Date` headers from Twitch / CDN responses.
 */
object TwitchClock {

    private const val MIN_VALID_EPOCH_MS = 1_700_000_000_000L
    private const val PRECISE_SAMPLE_TTL_MS = 180_000L
    private const val SNAP_THRESHOLD_MS = 5_000L

    private val lock = Any()

    @Volatile
    private var calibrated = false

    @Volatile
    private var hasPreciseSample = false

    @Volatile
    private var anchorServerMs = 0L

    @Volatile
    private var anchorElapsedMs = 0L

    @Volatile
    private var systemOffsetMs = 0L

    @Volatile
    private var lastPreciseElapsedMs = 0L

    private val rfc1123Format = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
    }

    /** Whether at least one authoritative Twitch/CDN clock sample has been recorded. */
    @JvmStatic
    fun isCalibrated(): Boolean = calibrated

    /** Current UTC epoch time (ms) aligned with Twitch's servers. */
    @JvmStatic
    fun nowMs(): Long {
        if (!calibrated) {
            return System.currentTimeMillis()
        }
        val elapsed = safeElapsedRealtime()
        val baseElapsed = anchorElapsedMs
        return if (elapsed > 0L && baseElapsed > 0L) {
            anchorServerMs + (elapsed - baseElapsed)
        } else {
            System.currentTimeMillis() + systemOffsetMs
        }
    }

    /** Parses `#EXT-X-TWITCH-INFO` line and extracts `SERVER-TIME="<epoch_seconds>"` if present. */
    @JvmStatic
    fun onTwitchInfoLine(line: String?) {
        if (line.isNullOrEmpty()) return
        val marker = "SERVER-TIME=\""
        val start = line.indexOf(marker)
        if (start < 0) return
        val valStart = start + marker.length
        val valEnd = line.indexOf('"', valStart)
        if (valEnd <= valStart) return
        val secs = line.substring(valStart, valEnd).toDoubleOrNull() ?: return
        val serverMs = (secs * 1000.0).roundToLong()
        if (serverMs < MIN_VALID_EPOCH_MS) return
        recordSample(serverMs, precise = true)
    }

    /** Updates fallback server clock from HTTP `Date` header on official Twitch / CDN hosts. */
    @JvmStatic
    fun onHttpResponse(url: String?, dateHeader: String?) {
        if (hasPreciseSample) return
        if (url.isNullOrEmpty() || dateHeader.isNullOrBlank()) return
        if (!isTrustedHost(url)) return
        val elapsed = safeElapsedRealtime()
        val lastPrecise = lastPreciseElapsedMs
        if (calibrated && elapsed > 0L && lastPrecise > 0L && (elapsed - lastPrecise) in 0L..PRECISE_SAMPLE_TTL_MS) {
            return
        }
        val parsedMs = try {
            rfc1123Format.get()?.parse(dateHeader.trim())?.time
        } catch (_: Exception) {
            null
        } ?: return
        if (parsedMs < MIN_VALID_EPOCH_MS) return
        // RFC-1123 Date headers truncate fractional seconds; +500ms centers the quantization interval.
        recordSample(parsedMs + 500L, precise = false)
    }

    private fun recordSample(serverMs: Long, precise: Boolean) {
        val sysNow = System.currentTimeMillis()
        val elapsed = safeElapsedRealtime()
        synchronized(lock) {
            if (!calibrated || (precise && !hasPreciseSample)) {
                anchorServerMs = serverMs
                anchorElapsedMs = elapsed
                systemOffsetMs = serverMs - sysNow
                if (precise) {
                    hasPreciseSample = true
                    if (elapsed > 0L) {
                        lastPreciseElapsedMs = elapsed
                    }
                }
                calibrated = true
                return
            }
            if (!precise && hasPreciseSample) {
                return
            }
            val predictedNow = if (elapsed > 0L && anchorElapsedMs > 0L) {
                anchorServerMs + (elapsed - anchorElapsedMs)
            } else {
                sysNow + systemOffsetMs
            }
            val delta = serverMs - predictedNow
            val adjustedServerMs = if (abs(delta) > SNAP_THRESHOLD_MS) {
                serverMs
            } else {
                val weight = if (precise) 0.30 else 0.15
                predictedNow + (delta * weight).roundToLong()
            }
            anchorServerMs = adjustedServerMs
            anchorElapsedMs = elapsed
            systemOffsetMs = adjustedServerMs - sysNow
            if (precise) {
                hasPreciseSample = true
                if (elapsed > 0L) {
                    lastPreciseElapsedMs = elapsed
                }
            }
        }
    }

    private fun isTrustedHost(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        val hostStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        var hostEnd = url.indexOf('/', hostStart)
        if (hostEnd < 0) hostEnd = url.length
        val portIdx = url.indexOf(':', hostStart)
        if (portIdx in hostStart until hostEnd) hostEnd = portIdx
        val host = url.substring(hostStart, hostEnd).lowercase(Locale.US)
        return host == "twitch.tv" ||
            host.endsWith(".twitch.tv") ||
            host.endsWith(".ttvnw.net") ||
            host.endsWith(".jtvnw.net") ||
            host.endsWith(".cloudfront.net")
    }

    private fun safeElapsedRealtime(): Long =
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            0L
        }
}
