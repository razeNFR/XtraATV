package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Experimental `Adblock (Swap+)` continuous Shadow HLS engine for Twitch live streams on Android TV.
 *
 * Maintains a lightweight anonymous shadow playlist session per active channel, intercepts only live
 * `.m3u8` playlists (never video segments, never VODs), classifies ad segments, and splices clean
 * shadow segments into a monotonic continuity window with `#EXT-X-DISCONTINUITY` and proper
 * `#EXT-X-DISCONTINUITY-SEQUENCE` tracking — preserving 1440p / HEVC / AV1 on the primary stream.
 */
object TvShadowAdblock {

    private const val TAG = "TvShadowAdblock"

    private const val BASE_WINDOW_SIZE = 14
    private const val MAX_WINDOW_SIZE = 24
    private const val MAX_INGEST_PER_PASS = 3
    private const val MAX_EVICT_PER_PASS = 3
    private const val SEEN_IDENTITY_LIMIT = 600
    private const val PREROLL_WAIT_MS = 3_000L
    private const val MIDROLL_WAIT_MS = 2_200L
    private const val SHADOW_REFRESH_IDLE_MS = 4_000L
    private const val SHADOW_REFRESH_HOT_MS = 1_000L
    private const val SHADOW_STALE_ON_AD_MS = 800L
    private const val SHADOW_FAST_REFRESH_TIMEOUT_MS = 1_200L
    private const val SHADOW_HOT_WINDOW_MS = 90_000L
    private const val SHADOW_MAX_AGE_MS = 15_000L
    private const val SHADOW_LEAVE_TTL_MS = 120_000L
    private const val SHADOW_MASTER_MAX_AGE_MS = 480_000L
    private const val SHADOW_REQUEST_TIMEOUT_MS = 4_000L
    private const val CLEAN_STREAK_TO_RETURN = 3
    private const val MAX_HOLD_PASSES = 3

    private val SHADOW_PLAYER_TYPES = listOf(
        "web" to "frontpage",
        "web" to "embed",
        "web" to "thunderdome",
        "android" to "autoplay",
        "web" to "picture-by-picture",
    )

    private val BRAND_AD_TITLE_REGEX = Regex("""^[A-Za-z][\w .-]{0,40}\|[\w-]{4,}$""")
    private val NUMERIC_AD_TITLE_REGEX = Regex("""^\d{6,}$""")
    private val STRONG_AD_ID_REGEX = Regex("""ID="(?:stitched-ad|ad-|preroll-|midroll-)""", RegexOption.IGNORE_CASE)

    enum class SegmentClass { CONTENT, AD, UNKNOWN }
    enum class PlaylistVerdict { MASTER, EMPTY, CLEAN, FULL_AD, PARTIAL_AD }

    data class HlsSegment(
        val uri: String,
        val durationSec: Double,
        val title: String,
        val pdtIso: String?,
        val pdtMs: Long?,
        val mapLine: String?,
        val keyLine: String?,
        val byteRangeLine: String?,
        val attachedTags: List<String>,
        val classification: SegmentClass,
    )

    data class ParsedPlaylist(
        val verdict: PlaylistVerdict,
        val headerLines: List<String>,
        val mediaSequence: Long,
        val discontinuitySequence: Long,
        val segments: List<HlsSegment>,
        val prefetchLines: List<String>,
        val activeMapLine: String?,
        val prefetchAdCount: Int = 0,
    ) {
        /** True when any `#EXT-X-TWITCH-PREFETCH` live-edge segment falls inside an ad break. */
        val hasPrefetchAd: Boolean
            get() = prefetchAdCount > 0

        /** True when ads are actively present at the live edge (matches ExoPlayerService.checkPlaylist, including prefetch). */
        val hasActiveAdAtLiveEdge: Boolean
            get() = when {
                hasPrefetchAd -> true
                verdict == PlaylistVerdict.FULL_AD -> true
                verdict == PlaylistVerdict.PARTIAL_AD -> segments.lastOrNull()?.classification == SegmentClass.AD
                else -> false
            }

        /** Returns only the trailing clean segments after the last historical ad segment. */
        fun cleanTailOnly(): ParsedPlaylist {
            val lastAdIdx = segments.indexOfLast { it.classification == SegmentClass.AD }
            if (lastAdIdx < 0) return this
            val tail = segments.drop(lastAdIdx + 1)
            return copy(
                verdict = if (tail.isEmpty()) PlaylistVerdict.EMPTY else PlaylistVerdict.CLEAN,
                mediaSequence = mediaSequence + (lastAdIdx + 1).toLong(),
                segments = tail,
                prefetchLines = emptyList(),
                prefetchAdCount = 0,
            )
        }
    }

    /** Optional timing & ad-range metadata extracted from Media3's HlsMediaPlaylist (handles #EXT-X-SKIP, PDT inheritance, and prefetch). */
    data class PlaylistTimingHint(
        val segmentPdtMs: List<Long>? = null,
        val fallbackFirstSegmentPdtMs: Long? = null,
        val extraAdRangesMs: List<Pair<Long, Long>> = emptyList(),
        val fallbackMapLine: String? = null,
        val media3LiveEdgeIsAd: Boolean = false,
        val media3AnySegmentIsAd: Boolean = false,
    )

    /** Monotonic HLS sequence bounds from previousMediaPlaylist to prevent Media3 HlsChunkSource out-of-bounds crashes. */
    data class ContinuityFloor(
        val minMediaSequence: Long = 0L,
        val minEndMediaSequence: Long = 0L,
        /** Header `#EXT-X-DISCONTINUITY-SEQUENCE` of the previous playlist (first segment's sequence). */
        val minDiscontinuitySequence: Long = 0L,
        /** Discontinuity sequence at the tail of the previous playlist, used only when anchoring a brand-new window. */
        val minDiscontinuitySequenceForReset: Long = minDiscontinuitySequence,
    )

    data class SpliceEvent(
        val sourceBefore: String,
        val sourceAfter: String,
        val lastServedPdtMs: Long?,
        val lastServedEndMs: Long?,
        val lastServedSeq: Long,
        val insertedFirstPdtMs: Long?,
        val insertedFirstSeq: Long,
        val deltaMs: Long?,
        val insertedCount: Int,
        val discontinuityEmitted: Boolean,
        val discontinuitySequence: Long,
        val primaryCodec: String?,
        val primaryContainer: String?,
        val shadowCodec: String?,
        val shadowContainer: String?,
    )

    data class IngestResult(
        val insertedCount: Int,
        val spliceEvent: SpliceEvent? = null,
    )

    data class WindowSegment(
        val uri: String,
        val durationSec: Double,
        val pdtIso: String?,
        val identityMs: Long?,
        val identityKey: String,
        val hasDiscontinuity: Boolean,
        val mapLine: String?,
        val keyLine: String?,
        val byteRangeLine: String?,
    )

    data class MasterVariant(
        val url: String,
        val height: Int,
        val bandwidth: Long,
        val width: Int = 0,
        val codecs: String = "",
        val frameRate: Double = 0.0,
    ) {
        val videoCodecFamily: String
            get() = normalizeVideoCodecFamily(codecs)
    }

    internal fun normalizeVideoCodecFamily(codecs: String?): String {
        if (codecs.isNullOrBlank()) return ""
        for (rawToken in codecs.split(',')) {
            val token = rawToken.trim().lowercase(java.util.Locale.US)
            if (token.isEmpty()) continue
            when {
                token.startsWith("avc1") || token.startsWith("avc3") || token == "h264" -> return "avc1"
                token.startsWith("hev1") || token.startsWith("hvc1") || token == "h265" || token == "hevc" -> return "hevc"
                token.startsWith("av01") || token == "av1" -> return "av01"
            }
        }
        return ""
    }

    // -------------------------------------------------------------------------
    // VOD exclusion & Master parsing
    // -------------------------------------------------------------------------

    fun isVodOrExcludedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()
        return lower.contains("/vod/") ||
            lower.contains("highlight-") ||
            lower.contains(".cloudfront.net") ||
            lower.contains("vod-secure.twitch.tv") ||
            lower.contains("vod-metro.twitch.tv") ||
            lower.contains("vod-pop-secure.twitch.tv") ||
            lower.contains("index-dvr.m3u8") ||
            lower.contains("index-muted-")
    }

    fun isVodPlaylistText(text: String): Boolean =
        text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-PLAYLIST-TYPE:VOD", ignoreCase = true)

    /** Strips vertical/portrait variants (`IVS-GROUPS` portrait or height > width) and extracts variant metadata. */
    fun filterMasterAndExtractVariants(
        masterUrl: String,
        masterText: String,
    ): Pair<String, List<MasterVariant>> {
        val lines = masterText.split("\r\n", "\n")
        val out = ArrayList<String>(lines.size)
        val variants = ArrayList<MasterVariant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                val urlLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                val isPortrait = isPortraitVariant(trimmed)
                if (!isPortrait && urlLine.isNotEmpty() && !urlLine.startsWith("#")) {
                    out.add(line)
                    out.add(urlLine)
                    val absUrl = resolveAbsoluteUrl(masterUrl, urlLine)
                    val (w, h) = parseResolution(trimmed)
                    variants.add(
                        MasterVariant(
                            url = absUrl,
                            width = w,
                            height = h,
                            bandwidth = parseBandwidth(trimmed),
                            codecs = parseCodecs(trimmed),
                            frameRate = parseFrameRate(trimmed),
                        )
                    )
                }
                i += 2
                continue
            }
            if (trimmed.startsWith("#EXT-X-MEDIA:") && trimmed.contains("portrait", ignoreCase = true)) {
                i++
                continue
            }
            out.add(line)
            i++
        }
        return out.joinToString("\n") to variants
    }

    /** Keeps only `#EXT-X-STREAM-INF` variants matching [codecFamily] (e.g. `"avc1"`) plus audio/non-variant lines. */
    internal fun filterMasterToCodecFamily(masterText: String, codecFamily: String): String {
        val lines = masterText.split("\r\n", "\n")
        val out = ArrayList<String>(lines.size)
        var keptVariants = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                val urlLine = lines.getOrNull(i + 1)
                val family = normalizeVideoCodecFamily(parseCodecs(trimmed))
                if (family.isEmpty() || family == codecFamily) {
                    out.add(line)
                    if (urlLine != null) out.add(urlLine)
                    keptVariants++
                }
                i += 2
                continue
            }
            out.add(line)
            i++
        }
        return if (keptVariants > 0) out.joinToString("\n") else masterText
    }

    private fun isPortraitVariant(streamInf: String): Boolean {
        if (streamInf.contains("IVS-GROUPS", ignoreCase = true) && streamInf.contains("portrait", ignoreCase = true)) {
            return true
        }
        val (w, h) = parseResolution(streamInf)
        if (w > 0 && h > w) return true
        return false
    }

    private fun parseResolution(streamInf: String): Pair<Int, Int> {
        val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(streamInf)
        val w = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val h = resMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
        return w to h
    }

    private fun parseBandwidth(streamInf: String): Long {
        val bwMatch = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(streamInf)
        return bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    private fun parseCodecs(streamInf: String): String {
        val codecsMatch = Regex("""CODECS="([^"]+)"""", RegexOption.IGNORE_CASE).find(streamInf)
        return codecsMatch?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun parseFrameRate(streamInf: String): Double {
        val fpsMatch = Regex("""FRAME-RATE=([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE).find(streamInf)
        return fpsMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    // -------------------------------------------------------------------------
    // Media playlist parser & segment classifier (Section 5)
    // -------------------------------------------------------------------------

    fun parseAndClassifyMediaPlaylist(
        playlistUrl: String,
        text: String,
        timingHint: PlaylistTimingHint? = null,
    ): ParsedPlaylist {
        if (text.contains("#EXT-X-STREAM-INF:")) {
            return ParsedPlaylist(PlaylistVerdict.MASTER, emptyList(), 0L, 0L, emptyList(), emptyList(), null)
        }
        val lines = text.split("\r\n", "\n")
        val headerLines = ArrayList<String>(16)
        val prefetchLines = ArrayList<String>(4)
        val segments = ArrayList<HlsSegment>(24)

        var mediaSeq = 0L
        var discSeq = 0L
        var streamSourceNotLive = false
        var activeMapLine: String? = timingHint?.fallbackMapLine
        var activeKeyLine: String? = null
        var inSegments = false

        var curDuration = 2.0
        var curTitle = ""
        var curPdtIso: String? = null
        var curPdtMs: Long? = null
        var curByteRange: String? = null
        val curTags = ArrayList<String>(6)

        // Pass 1: collect all ad DATERANGE windows across the entire playlist (top, middle, or bottom)
        // plus any Media3-parsed interstitial windows so segment classification matches ExoPlayerService.isAdSegment.
        var lastPdtMs: Long? = timingHint?.fallbackFirstSegmentPdtMs
        var elapsedSincePdtMs = 0.0
        val adDateRanges = ArrayList<Pair<Long, Long>>(4 + (timingHint?.extraAdRangesMs?.size ?: 0))
        timingHint?.extraAdRangesMs?.let { adDateRanges.addAll(it) }
        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("#EXT-X-DATERANGE:")) {
                parseAdDateRangeWindow(trimmed)?.let { adDateRanges.add(it) }
            }
        }

        var prefetchAdCount = 0
        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXT-X-TWITCH-PREFETCH:")) {
                val prefUri = trimmed.removePrefix("#EXT-X-TWITCH-PREFETCH:").trim()
                if (prefUri.isNotEmpty()) {
                    val absPrefUri = resolveAbsoluteUrl(playlistUrl, prefUri)
                    // Xtra's custom HlsPlaylistParser appends #EXT-X-TWITCH-PREFETCH as 2s segments at the end
                    // of media3Playlist.segments, so ExoPlayerService.checkPlaylist inspects them as the live edge.
                    val prefIndex = segments.size + prefetchLines.size
                    val hintedPrefPdtMs = timingHint?.segmentPdtMs?.getOrNull(prefIndex)
                    val prefPdtMs = hintedPrefPdtMs ?: lastPdtMs?.let { it + elapsedSincePdtMs.roundToLong() }
                    if (hintedPrefPdtMs != null) {
                        lastPdtMs = hintedPrefPdtMs
                        elapsedSincePdtMs = 0.0
                    }
                    elapsedSincePdtMs += 2_000.0
                    val prefMidMs = prefPdtMs?.let { it + 1_000L }
                    val prefInAdRange = prefPdtMs != null && adDateRanges.any { (startMs, endMs) ->
                        prefPdtMs in startMs until endMs || (prefMidMs != null && prefMidMs in startMs until endMs)
                    }
                    val lowerPref = absPrefUri.lowercase()
                    val prefUriIsAd = lowerPref.contains("/stitched/") ||
                        lowerPref.contains("amazon-adsystem") ||
                        lowerPref.contains("/ad/") ||
                        lowerPref.contains("/ads/") ||
                        lowerPref.contains("adsegment")
                    if (prefInAdRange || prefUriIsAd) {
                        prefetchAdCount++
                    }
                    prefetchLines.add("#EXT-X-TWITCH-PREFETCH:$absPrefUri")
                }
                continue
            }

            if (trimmed.contains("X-TV-TWITCH-STREAM-SOURCE", ignoreCase = true)) {
                val isLiveSource = trimmed.contains("X-TV-TWITCH-STREAM-SOURCE=\"live\"", ignoreCase = true) ||
                    trimmed.contains("X-TV-TWITCH-STREAM-SOURCE=live", ignoreCase = true)
                streamSourceNotLive = !isLiveSource
            }

            if (!inSegments && !isSegmentScopedTag(trimmed)) {
                when {
                    trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                        mediaSeq = trimmed.removePrefix("#EXT-X-MEDIA-SEQUENCE:").trim().toLongOrNull() ?: 0L
                    }
                    trimmed.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") -> {
                        discSeq = trimmed.removePrefix("#EXT-X-DISCONTINUITY-SEQUENCE:").trim().toLongOrNull() ?: 0L
                    }
                    trimmed.startsWith("#EXT-X-START") -> Unit
                    trimmed.startsWith("#EXT-X-SKIP:") -> Unit
                    else -> {
                        cleanHeaderLine(raw)?.let { headerLines.add(it) }
                    }
                }
                continue
            }

            inSegments = true
            if (trimmed.startsWith("#")) {
                when {
                    trimmed.startsWith("#EXTINF:") -> {
                        val after = trimmed.removePrefix("#EXTINF:")
                        val commaIdx = after.indexOf(',')
                        if (commaIdx >= 0) {
                            curDuration = after.substring(0, commaIdx).trim().toDoubleOrNull()?.takeIf { it > 0.0 } ?: 2.0
                            curTitle = after.substring(commaIdx + 1).trim()
                        } else {
                            curDuration = after.trim().toDoubleOrNull()?.takeIf { it > 0.0 } ?: 2.0
                            curTitle = ""
                        }
                    }
                    trimmed.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> {
                        val iso = trimmed.removePrefix("#EXT-X-PROGRAM-DATE-TIME:").trim()
                        curPdtIso = iso
                        val parsed = parseIsoMs(iso)
                        if (parsed != null) {
                            curPdtMs = parsed
                            lastPdtMs = parsed
                            elapsedSincePdtMs = 0.0
                        }
                    }
                    trimmed.startsWith("#EXT-X-MAP:") -> {
                        activeMapLine = absolutizeQuotedUri(trimmed, playlistUrl)
                        curTags.add(activeMapLine!!)
                    }
                    trimmed.startsWith("#EXT-X-KEY:") -> {
                        activeKeyLine = absolutizeQuotedUri(trimmed, playlistUrl)
                        curTags.add(activeKeyLine!!)
                    }
                    trimmed.startsWith("#EXT-X-BYTERANGE:") -> {
                        curByteRange = trimmed
                    }
                    trimmed.startsWith("#EXT-X-PART:") ||
                        trimmed.startsWith("#EXT-X-PRELOAD-HINT:") ||
                        trimmed.startsWith("#EXT-X-RENDITION-REPORT:") ||
                        trimmed.startsWith("#EXT-X-SKIP:") -> Unit
                    else -> {
                        curTags.add(trimmed)
                    }
                }
            } else {
                val segIndex = segments.size
                val absUri = resolveAbsoluteUrl(playlistUrl, trimmed)
                val hintedPdtMs = timingHint?.segmentPdtMs?.getOrNull(segIndex)
                val segPdtMs = curPdtMs ?: hintedPdtMs ?: lastPdtMs?.let { it + elapsedSincePdtMs.roundToLong() }
                if (segPdtMs != null && curPdtMs == null && hintedPdtMs != null) {
                    lastPdtMs = hintedPdtMs
                    elapsedSincePdtMs = 0.0
                }
                val segDurMs = (curDuration * 1_000.0).roundToLong().coerceAtLeast(1L)
                elapsedSincePdtMs += curDuration * 1_000.0
                val segMidMs = segPdtMs?.let { it + segDurMs / 2L }
                val inAdDateRange = segPdtMs != null && adDateRanges.any { (startMs, endMs) ->
                    segPdtMs in startMs until endMs || (segMidMs != null && segMidMs in startMs until endMs)
                }
                val segClass = classifySegment(curTitle, absUri, curTags, streamSourceNotLive, inAdDateRange)
                segments.add(
                    HlsSegment(
                        uri = absUri,
                        durationSec = curDuration,
                        title = curTitle,
                        pdtIso = curPdtIso,
                        pdtMs = segPdtMs,
                        mapLine = activeMapLine,
                        keyLine = activeKeyLine,
                        byteRangeLine = curByteRange,
                        attachedTags = curTags.toList(),
                        classification = segClass,
                    )
                )
                curDuration = 2.0
                curTitle = ""
                curPdtIso = null
                curPdtMs = null
                curByteRange = null
                curTags.clear()
            }
        }

        // Strict parity with ExoPlayerService.checkPlaylist: if Media3's parsed live-edge segment (including
        // prefetch) is an ad and our regular tail segments did not already mark it, flag prefetchAdCount >= 1.
        if (timingHint?.media3LiveEdgeIsAd == true &&
            segments.lastOrNull()?.classification != SegmentClass.AD &&
            prefetchAdCount == 0
        ) {
            prefetchAdCount = 1
        }

        val verdict = when {
            segments.isEmpty() -> PlaylistVerdict.EMPTY
            segments.none { it.classification == SegmentClass.AD } -> PlaylistVerdict.CLEAN
            segments.all { it.classification == SegmentClass.AD } -> PlaylistVerdict.FULL_AD
            else -> PlaylistVerdict.PARTIAL_AD
        }

        return ParsedPlaylist(
            verdict = verdict,
            headerLines = headerLines,
            mediaSequence = mediaSeq,
            discontinuitySequence = discSeq,
            segments = segments,
            prefetchLines = prefetchLines,
            activeMapLine = activeMapLine,
            prefetchAdCount = prefetchAdCount,
        )
    }

    private fun parseAdDateRangeWindow(trimmed: String): Pair<Long, Long>? {
        val isAd = trimmed.contains("stitched-ad", ignoreCase = true) ||
            trimmed.contains("twitch-stitched-ad", ignoreCase = true) ||
            trimmed.contains("X-TV-TWITCH-AD-", ignoreCase = true) ||
            STRONG_AD_ID_REGEX.containsMatchIn(trimmed)
        if (!isAd) return null

        val attrs = splitAttributesRespectingQuotes(trimmed.removePrefix("#EXT-X-DATERANGE:"))
        var startMs: Long? = null
        var endMs: Long? = null
        var durationSec: Double? = null
        var plannedDurationSec: Double? = null
        for (attr in attrs) {
            val eq = attr.indexOf('=')
            if (eq <= 0) continue
            val key = attr.substring(0, eq).trim().uppercase(java.util.Locale.US)
            val rawVal = attr.substring(eq + 1).trim().trim('"')
            when (key) {
                "START-DATE" -> startMs = parseIsoMs(rawVal)
                "END-DATE" -> endMs = parseIsoMs(rawVal)
                "DURATION" -> durationSec = rawVal.toDoubleOrNull()
                "PLANNED-DURATION" -> plannedDurationSec = rawVal.toDoubleOrNull()
            }
        }
        val start = startMs ?: return null
        val end = endMs
            ?: durationSec?.takeIf { it > 0.0 }?.let { start + (it * 1_000.0).roundToLong() }
            ?: plannedDurationSec?.takeIf { it > 0.0 }?.let { start + (it * 1_000.0).roundToLong() }
            ?: return null
        return if (end > start) start to end else null
    }

    private fun isSegmentScopedTag(trimmed: String): Boolean =
        trimmed.startsWith("#EXTINF:") ||
            (trimmed.startsWith("#EXT-X-DISCONTINUITY") && !trimmed.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) ||
            trimmed.startsWith("#EXT-X-PROGRAM-DATE-TIME:") ||
            trimmed.startsWith("#EXT-X-KEY:") ||
            trimmed.startsWith("#EXT-X-MAP:") ||
            trimmed.startsWith("#EXT-X-BYTERANGE:")

    private fun classifySegment(
        title: String,
        uri: String,
        tags: List<String>,
        streamSourceNotLive: Boolean,
        inAdDateRange: Boolean = false,
    ): SegmentClass {
        val strongContent = title.equals("live", ignoreCase = true)
        val titlePrefix = title.substringBefore('|').trim()
        val knownAdBrand = titlePrefix.equals("Amazon", ignoreCase = true) ||
            titlePrefix.equals("Adform", ignoreCase = true) ||
            titlePrefix.equals("DCM", ignoreCase = true)
        var strongAd = inAdDateRange || knownAdBrand || BRAND_AD_TITLE_REGEX.matches(title)
        if (!strongAd) {
            for (tag in tags) {
                if (tag.contains("twitch-stitched-ad", true) ||
                    tag.contains("stitched-ad", true) ||
                    tag.contains("twitch-ad-quartile", true) ||
                    tag.contains("X-TV-TWITCH-AD-", true) ||
                    STRONG_AD_ID_REGEX.containsMatchIn(tag)
                ) {
                    strongAd = true
                    break
                }
            }
        }
        val lowerUri = uri.lowercase()
        val mediumAd = NUMERIC_AD_TITLE_REGEX.matches(title) ||
            lowerUri.contains("/stitched/") ||
            lowerUri.contains("amazon-adsystem") ||
            lowerUri.contains("/ad/") ||
            lowerUri.contains("/ads/") ||
            lowerUri.contains("adsegment") ||
            (streamSourceNotLive && !strongContent)

        return when {
            strongAd -> SegmentClass.AD
            strongContent -> SegmentClass.CONTENT
            mediumAd -> SegmentClass.AD
            else -> SegmentClass.UNKNOWN
        }
    }

    internal fun cleanHeaderLine(raw: String): String? {
        val trimmed = raw.trim()
        // Never leak LL-HLS delta/part control tags into synthesized full-segment playlists
        if (trimmed.startsWith("#EXT-X-START") ||
            trimmed.startsWith("#EXT-X-SKIP") ||
            trimmed.startsWith("#EXT-X-SERVER-CONTROL") ||
            trimmed.startsWith("#EXT-X-PART-INF") ||
            trimmed.startsWith("#EXT-X-PART:") ||
            trimmed.startsWith("#EXT-X-PRELOAD-HINT") ||
            trimmed.startsWith("#EXT-X-RENDITION-REPORT")
        ) {
            return null
        }
        if (trimmed.startsWith("#EXT-X-DATERANGE:")) {
            val isAd = trimmed.contains("stitched-ad", true) ||
                trimmed.contains("twitch-stitched-ad", true) ||
                trimmed.contains("X-TV-TWITCH-AD-", true) ||
                STRONG_AD_ID_REGEX.containsMatchIn(trimmed)
            if (isAd) {
                return null
            }
        }
        var cleaned = raw
        if (cleaned.contains("SUPPRESS=\"true\"", ignoreCase = true)) {
            cleaned = cleaned.replace(Regex("""SUPPRESS="true"""", RegexOption.IGNORE_CASE), "SUPPRESS=\"false\"")
        }
        if (cleaned.contains("DATA-ID=\"SUPPRESS\"", ignoreCase = true) && cleaned.contains("VALUE=\"true\"", ignoreCase = true)) {
            cleaned = cleaned.replace(Regex("""VALUE="true"""", RegexOption.IGNORE_CASE), "VALUE=\"false\"")
        }
        if (cleaned.contains("X-TV-TWITCH-STREAM-SOURCE", ignoreCase = true)) {
            cleaned = cleaned.replace(
                Regex("""X-TV-TWITCH-STREAM-SOURCE="[^"]+"""", RegexOption.IGNORE_CASE),
                "X-TV-TWITCH-STREAM-SOURCE=\"live\"",
            )
        }
        return cleaned
    }

    /**
     * Strips `CAN-SKIP-UNTIL` / `CAN-SKIP-DATERANGES` from `#EXT-X-SERVER-CONTROL` on passthrough playlists so Media3
     * requests full playlists (with `#EXT-X-PROGRAM-DATE-TIME` and `#EXT-X-MAP` intact) while keeping low-latency parts.
     */
    internal fun disableDeltaSkipping(text: String): String {
        if (!text.contains("CAN-SKIP-UNTIL", ignoreCase = true) &&
            !text.contains("CAN-SKIP-DATERANGES", ignoreCase = true)
        ) {
            return text
        }
        val hadTrailingNewline = text.endsWith("\n")
        val lines = text.split("\r\n", "\n")
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-SERVER-CONTROL:", ignoreCase = true)) {
                val prefix = trimmed.substringBefore(':') + ":"
                val attrs = splitAttributesRespectingQuotes(trimmed.substringAfter(':'))
                val kept = attrs.filterNot { attr ->
                    val key = attr.substringBefore('=').trim().uppercase(java.util.Locale.US)
                    key == "CAN-SKIP-UNTIL" || key == "CAN-SKIP-DATERANGES"
                }
                if (kept.isNotEmpty()) {
                    out.add(prefix + kept.joinToString(","))
                }
            } else {
                out.add(line)
            }
        }
        val joined = out.joinToString("\n")
        return if (hadTrailingNewline && !joined.endsWith("\n")) "$joined\n" else joined
    }

    private fun extractSkippedSegmentsCount(text: String): Int {
        val m = Regex("""#EXT-X-SKIP:[^\r\n]*SKIPPED-SEGMENTS=(\d+)""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun stripAdDateRangeAttributes(line: String): String {
        val prefix = "#EXT-X-DATERANGE:"
        val attrs = splitAttributesRespectingQuotes(line.removePrefix(prefix))
        val kept = attrs.filterNot { attr ->
            val key = attr.substringBefore('=').trim()
            key.startsWith("X-TV-TWITCH-AD-", ignoreCase = true)
        }
        return prefix + kept.joinToString(",")
    }

    private fun splitAttributesRespectingQuotes(input: String): List<String> {
        val result = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (ch in input) {
            if (ch == '"') inQuotes = !inQuotes
            if (ch == ',' && !inQuotes) {
                result.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) result.add(cur.toString())
        return result
    }

    private fun absolutizeQuotedUri(tagLine: String, baseUrl: String): String {
        val regex = Regex("""URI="([^"]+)"""")
        return regex.replace(tagLine) { m ->
            val abs = resolveAbsoluteUrl(baseUrl, m.groupValues[1])
            """URI="$abs""""
        }
    }

    private fun parseIsoMs(iso: String): Long? =
        runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

    // -------------------------------------------------------------------------
    // Continuity Window (Section 8 + DISCONTINUITY-SEQUENCE fix)
    // -------------------------------------------------------------------------

    class ContinuityWindow {
        private val window = ArrayDeque<WindowSegment>(MAX_WINDOW_SIZE + 4)
        private val seenOrder = ArrayDeque<String>(SEEN_IDENTITY_LIMIT + 16)
        private val seenSet = HashSet<String>(SEEN_IDENTITY_LIMIT * 2)
        private val seenUriOrder = ArrayDeque<String>(SEEN_IDENTITY_LIMIT + 16)
        private val seenUriSet = HashSet<String>(SEEN_IDENTITY_LIMIT * 2)
        private val seenPdtMs = ArrayDeque<Pair<Long, Long>>(SEEN_IDENTITY_LIMIT + 16) // (pdtMs, durMs)

        var mediaSequence: Long = -1L
            private set
        var discontinuitySequence: Long = 0L
            private set
        var activeSourceId: String? = null
            private set
        var currentWindowLimit: Int = BASE_WINDOW_SIZE
            private set
        var hasSeenContent: Boolean = false
            private set
        var consecutiveCleanPrimary: Int = 0
            private set
        var consecutiveHoldPasses: Int = 0
            private set
        var spliceCount: Int = 0
            private set
        var lastServedStartMs: Long? = null
            private set
        var lastServedEndMs: Long? = null
            private set
        var lastSpliceEvent: SpliceEvent? = null
            private set

        private var lastVariantUrl: String? = null
        private var lastPrefetches: List<String> = emptyList()
        var lastCleanSnapshot: ParsedPlaylist? = null
            private set

        fun reset() {
            window.clear()
            seenOrder.clear()
            seenSet.clear()
            seenUriOrder.clear()
            seenUriSet.clear()
            seenPdtMs.clear()
            mediaSequence = -1L
            discontinuitySequence = 0L
            activeSourceId = null
            currentWindowLimit = BASE_WINDOW_SIZE
            hasSeenContent = false
            consecutiveCleanPrimary = 0
            consecutiveHoldPasses = 0
            spliceCount = 0
            lastServedStartMs = null
            lastServedEndMs = null
            lastSpliceEvent = null
            lastVariantUrl = null
            lastPrefetches = emptyList()
            lastCleanSnapshot = null
        }

        fun noteHoldPass(isHold: Boolean): Int {
            consecutiveHoldPasses = if (isHold) consecutiveHoldPasses + 1 else 0
            return consecutiveHoldPasses
        }

        fun recordCleanSnapshot(parsed: ParsedPlaylist) {
            lastCleanSnapshot = parsed
            hasSeenContent = true
            consecutiveCleanPrimary++
        }

        fun onQualitySwitch(newVariantUrl: String, incoming: ParsedPlaylist) {
            if (lastVariantUrl != null && lastVariantUrl != newVariantUrl) {
                lastCleanSnapshot = null
                if (window.isNotEmpty()) {
                    // Keep monotonic mediaSequence & discontinuitySequence across quality switch
                    val oldSeqEnd = mediaSequence + window.size
                    val oldDiscCount = window.count { it.hasDiscontinuity }
                    val firstIncoming = incoming.segments.firstOrNull()
                    val matchIdx = if (firstIncoming != null) {
                        val key = segmentIdentityKey(firstIncoming)
                        window.indexOfFirst { it.identityKey == key }
                    } else -1
                    if (matchIdx >= 0) {
                        var droppedDisc = 0
                        for (i in 0 until matchIdx) {
                            if (window.elementAt(i).hasDiscontinuity) droppedDisc++
                        }
                        mediaSequence += matchIdx
                        discontinuitySequence += droppedDisc
                    } else {
                        mediaSequence = oldSeqEnd
                        discontinuitySequence += oldDiscCount
                    }
                    window.clear()
                    seenOrder.clear()
                    seenSet.clear()
                    seenUriOrder.clear()
                    seenUriSet.clear()
                    seenPdtMs.clear()
                    activeSourceId = null
                    lastServedStartMs = null
                    lastServedEndMs = null
                    currentWindowLimit = BASE_WINDOW_SIZE
                }
            }
            lastVariantUrl = newVariantUrl
        }

        fun isEmpty(): Boolean = window.isEmpty()

        fun noteCleanPrimary(isClean: Boolean) {
            if (isClean) {
                consecutiveCleanPrimary++
            } else {
                consecutiveCleanPrimary = 0
            }
        }

        fun ingest(
            sourceId: String,
            segments: List<HlsSegment>,
            sourceMediaSequence: Long,
            sourceDiscontinuitySequence: Long,
            prefetches: List<String>,
            allowPrefetches: Boolean,
            primaryCodec: String? = null,
            primaryContainer: String? = null,
            shadowCodec: String? = null,
            shadowContainer: String? = null,
        ): IngestResult {
            val nonAd = segments.filter { it.classification != SegmentClass.AD }
            if (nonAd.isEmpty()) return IngestResult(0)
            hasSeenContent = true

            val firstPass = mediaSequence < 0L
            if (firstPass) {
                mediaSequence = sourceMediaSequence.coerceAtLeast(0L)
                discontinuitySequence = sourceDiscontinuitySequence.coerceAtLeast(0L)
            }

            val prevSource = activeSourceId
            val sourceChanged = prevSource != null && prevSource != sourceId
            val prevLastStartMs = lastServedStartMs
            val prevLastEndMs = lastServedEndMs
            val prevLastSeq = if (window.isNotEmpty() && mediaSequence >= 0L) {
                mediaSequence + window.size.toLong() - 1L
            } else {
                -1L
            }

            // Primary/shadow streams from Twitch share the exact same origin segment URIs even when their
            // PROGRAM-DATE-TIME clocks drift apart by 10+ seconds over long sessions. Prefer URI-anchored
            // alignment when switching sources so clock skew can never reject valid live segments or stall ExoPlayer.
            val lastWindowUriKey = window.lastOrNull()?.let { segmentUriKey(it.uri) }
            var lastSeenUriIdx = -1
            if (!firstPass && sourceChanged) {
                for (i in nonAd.indices) {
                    if (seenUriSet.contains(segmentUriKey(nonAd[i].uri))) {
                        lastSeenUriIdx = i
                    }
                }
            }
            val uriAnchoredToWindowTail = lastSeenUriIdx >= 0 &&
                lastWindowUriKey != null &&
                segmentUriKey(nonAd[lastSeenUriIdx].uri) == lastWindowUriKey

            val fresh = ArrayList<HlsSegment>(nonAd.size)
            val rejectedByPdt = ArrayList<HlsSegment>(nonAd.size)
            if (lastSeenUriIdx >= 0) {
                for (i in (lastSeenUriIdx + 1) until nonAd.size) {
                    val seg = nonAd[i]
                    if (seenUriSet.contains(segmentUriKey(seg.uri))) continue
                    fresh.add(seg)
                }
            } else {
                // Clock-aligned selection: never serve a segment whose start precedes lastServedEndMs
                // beyond 45% of a segment's duration, and never re-serve an already seen identity.
                for (seg in nonAd) {
                    if (isAlreadySeen(seg)) continue
                    if (!firstPass && prevLastEndMs != null && seg.pdtMs != null) {
                        val segDurMs = (seg.durationSec * 1_000.0).roundToLong().coerceAtLeast(1_000L)
                        val tolMs = (segDurMs * 0.45).roundToLong().coerceAtLeast(450L)
                        if (seg.pdtMs < prevLastEndMs - tolMs) {
                            rejectedByPdt.add(seg)
                            continue
                        }
                    }
                    fresh.add(seg)
                }

                // Anti-stall guard: if switching sources and every segment was rejected by a large cross-server
                // PROGRAM-DATE-TIME clock offset (> 4s behind), re-anchor at the new source's live edge instead
                // of freezing the playlist until Media3 throws PlaylistStuckException.
                if (!firstPass && sourceChanged && fresh.isEmpty() && rejectedByPdt.isNotEmpty() && prevLastEndMs != null) {
                    val newestPdt = rejectedByPdt.last().pdtMs
                    if (newestPdt != null && newestPdt < prevLastEndMs - 4_000L) {
                        val candidates = rejectedByPdt.filterNot { seenUriSet.contains(segmentUriKey(it.uri)) }
                        fresh.addAll(candidates.takeLast(2))
                    } else {
                        rejectedByPdt.forEach { markSeen(it) }
                    }
                } else {
                    rejectedByPdt.forEach { markSeen(it) }
                }
            }

            // If the first eligible segment overlaps slightly backwards (< prevLastEndMs) while the very
            // next segment is closer (or equal) to prevLastEndMs, start from the next segment to minimize |Δ|.
            if (!firstPass && lastSeenUriIdx < 0 && prevLastEndMs != null && fresh.size >= 2) {
                val firstPdt = fresh[0].pdtMs
                val secondPdt = fresh[1].pdtMs
                if (firstPdt != null && secondPdt != null && firstPdt < prevLastEndMs) {
                    if (abs(secondPdt - prevLastEndMs) <= abs(firstPdt - prevLastEndMs)) {
                        markSeen(fresh.removeAt(0))
                    }
                }
            }

            if (fresh.isEmpty()) {
                lastPrefetches = if (allowPrefetches) prefetches else emptyList()
                return IngestResult(0)
            }

            val toAppend: List<HlsSegment>
            if (firstPass) {
                toAppend = fresh.takeLast(BASE_WINDOW_SIZE)
                if (fresh.size > toAppend.size) {
                    mediaSequence += (fresh.size - toAppend.size)
                }
                // Mark any earlier segments in firstPass as seen too
                fresh.forEach { markSeen(it) }
            } else {
                // Strictly cap to MAX_INGEST_PER_PASS (3) new segments per refresh — never bulk-dump
                toAppend = fresh.take(MAX_INGEST_PER_PASS)
                toAppend.forEach { markSeen(it) }
            }

            // Omit EXT-X-DISCONTINUITY when URI-anchored directly onto the previous segment on the exact same
            // codec/container/init-map stream: Media3 keeps its running TimestampAdjuster with zero audio glitch.
            val prevWindowTail = window.lastOrNull()
            val firstNew = toAppend.first()
            val seamlessContinuation = sourceChanged &&
                prevWindowTail != null &&
                uriAnchoredToWindowTail &&
                firstNew.mapLine == prevWindowTail.mapLine &&
                firstNew.keyLine == prevWindowTail.keyLine &&
                (primaryCodec.isNullOrEmpty() || shadowCodec.isNullOrEmpty() || primaryCodec == shadowCodec) &&
                (primaryContainer.isNullOrEmpty() || shadowContainer.isNullOrEmpty() || primaryContainer == shadowContainer)
            val needDiscontinuity = sourceChanged && !seamlessContinuation

            if (sourceChanged) {
                currentWindowLimit = MAX_WINDOW_SIZE
                spliceCount++
            } else if (currentWindowLimit > BASE_WINDOW_SIZE) {
                currentWindowLimit = (currentWindowLimit - 1).coerceAtLeast(BASE_WINDOW_SIZE)
            }

            val firstInsertedSeq = mediaSequence + window.size.toLong()
            val discEmittedOnFirst = needDiscontinuity && window.isNotEmpty()
            toAppend.forEachIndexed { idx, seg ->
                val disc = needDiscontinuity && idx == 0 && window.isNotEmpty()
                val segDurMs = (seg.durationSec * 1_000.0).roundToLong().coerceAtLeast(1L)
                val segPdtMs = seg.pdtMs ?: lastServedEndMs
                window.addLast(
                    WindowSegment(
                        uri = seg.uri,
                        durationSec = seg.durationSec,
                        pdtIso = seg.pdtIso,
                        identityMs = segPdtMs,
                        identityKey = segmentIdentityKey(seg),
                        hasDiscontinuity = disc,
                        mapLine = seg.mapLine,
                        keyLine = seg.keyLine,
                        byteRangeLine = seg.byteRangeLine,
                    )
                )
                if (segPdtMs != null) {
                    lastServedStartMs = segPdtMs
                    lastServedEndMs = segPdtMs + segDurMs
                }
            }

            activeSourceId = sourceId
            lastPrefetches = if (allowPrefetches) prefetches else emptyList()

            var evicted = 0
            while (window.size > currentWindowLimit && evicted < MAX_EVICT_PER_PASS) {
                val dropped = window.removeFirst()
                mediaSequence++
                if (dropped.hasDiscontinuity) {
                    discontinuitySequence++
                }
                evicted++
            }

            val spliceEvent = if (sourceChanged && toAppend.isNotEmpty()) {
                val firstInsertedPdt = toAppend.first().pdtMs
                val deltaMs = if (firstInsertedPdt != null && prevLastEndMs != null) {
                    firstInsertedPdt - prevLastEndMs
                } else {
                    null
                }
                SpliceEvent(
                    sourceBefore = prevSource ?: "none",
                    sourceAfter = sourceId,
                    lastServedPdtMs = prevLastStartMs,
                    lastServedEndMs = prevLastEndMs,
                    lastServedSeq = prevLastSeq,
                    insertedFirstPdtMs = firstInsertedPdt,
                    insertedFirstSeq = firstInsertedSeq,
                    deltaMs = deltaMs,
                    insertedCount = toAppend.size,
                    discontinuityEmitted = discEmittedOnFirst,
                    discontinuitySequence = discontinuitySequence,
                    primaryCodec = primaryCodec,
                    primaryContainer = primaryContainer,
                    shadowCodec = shadowCodec,
                    shadowContainer = shadowContainer,
                ).also { lastSpliceEvent = it }
            } else {
                null
            }

            return IngestResult(toAppend.size, spliceEvent)
        }

        /**
         * Enforces RFC 8216 monotonic sequence bounds against Media3's previousMediaPlaylist so
         * `mediaSequence` never regresses and `mediaSequence + window.size` never shrinks below the
         * previous playlist's live end (preventing `IndexOutOfBoundsException` in `HlsChunkSource`).
         */
        fun enforceContinuityFloor(floor: ContinuityFloor) {
            if (window.isEmpty()) return
            val minSeqForEnd = floor.minEndMediaSequence - window.size.toLong()
            val requiredSeq = maxOf(floor.minMediaSequence, minSeqForEnd)
            if (mediaSequence < requiredSeq) {
                mediaSequence = requiredSeq
            }
            if (discontinuitySequence < floor.minDiscontinuitySequence) {
                discontinuitySequence = floor.minDiscontinuitySequence
            }
        }

        fun emit(primaryHeaders: List<String>, activeMapFallback: String?): String {
            val sb = StringBuilder(2048)
            var hasExtM3u = false
            var hasVersion = false
            var hasTargetDur = false
            for (line in primaryHeaders) {
                val t = line.trim()
                if (t == "#EXTM3U") hasExtM3u = true
                if (t.startsWith("#EXT-X-VERSION:")) hasVersion = true
                if (t.startsWith("#EXT-X-TARGETDURATION:")) hasTargetDur = true
                if (t.startsWith("#EXT-X-MEDIA-SEQUENCE:") || t.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) continue
                sb.append(line).append('\n')
            }
            if (!hasExtM3u) sb.insert(0, "#EXTM3U\n")
            if (!hasVersion) sb.append("#EXT-X-VERSION:3\n")
            if (!hasTargetDur) {
                val maxDur = window.maxOfOrNull { it.durationSec }?.let { kotlin.math.ceil(it).toInt() }?.coerceAtLeast(2) ?: 6
                sb.append("#EXT-X-TARGETDURATION:").append(maxDur).append('\n')
            }
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence.coerceAtLeast(0L)).append('\n')
            if (discontinuitySequence > 0L || spliceCount > 0) {
                sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuitySequence.coerceAtLeast(0L)).append('\n')
            }
            if (spliceCount == 0 && activeSourceId?.startsWith("shadow@") == true && window.size >= 4) {
                sb.append("#EXT-X-START:TIME-OFFSET=-4.0,PRECISE=NO\n")
            }

            var currentMap: String? = null
            var currentKey: String? = null
            window.forEachIndexed { idx, seg ->
                if (seg.hasDiscontinuity) {
                    sb.append("#EXT-X-DISCONTINUITY\n")
                    // Re-emit MAP on every source splice if the new source uses fMP4 init segments
                    if (seg.mapLine != null) {
                        sb.append(seg.mapLine).append('\n')
                        currentMap = seg.mapLine
                    } else {
                        currentMap = null
                    }
                } else if (idx == 0) {
                    val firstMap = seg.mapLine ?: activeMapFallback
                    if (firstMap != null) {
                        sb.append(firstMap).append('\n')
                        currentMap = firstMap
                    }
                } else if (seg.mapLine != null && seg.mapLine != currentMap) {
                    sb.append(seg.mapLine).append('\n')
                    currentMap = seg.mapLine
                }

                if (seg.keyLine != null && seg.keyLine != currentKey) {
                    sb.append(seg.keyLine).append('\n')
                    currentKey = seg.keyLine
                }
                val pdtTag = seg.pdtIso ?: seg.identityMs?.let { Instant.fromEpochMilliseconds(it).toString() }
                if (pdtTag != null) {
                    sb.append("#EXT-X-PROGRAM-DATE-TIME:").append(pdtTag).append('\n')
                }
                if (seg.byteRangeLine != null) {
                    sb.append(seg.byteRangeLine).append('\n')
                }
                sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", seg.durationSec)).append(",live\n")
                sb.append(seg.uri).append('\n')
            }
            return sb.toString()
        }

        private fun segmentIdentityKey(seg: HlsSegment): String =
            seg.pdtMs?.let { "pdt:$it" } ?: segmentUriKey(seg.uri)

        private fun segmentUriKey(uri: String): String {
            val clean = uri.trim().substringBefore('?')
            val segMarker = clean.indexOf("/v1/segment/")
            return if (segMarker >= 0) {
                "seg:${clean.substring(segMarker + "/v1/segment/".length)}"
            } else {
                "uri:$clean"
            }
        }

        private fun isAlreadySeen(seg: HlsSegment): Boolean {
            if (seenUriSet.contains(segmentUriKey(seg.uri))) return true
            val key = segmentIdentityKey(seg)
            if (seenSet.contains(key)) return true
            val pdt = seg.pdtMs ?: return false
            val thresholdMs = (seg.durationSec * 1_000.0 * 0.45).roundToLong().coerceAtLeast(450L)
            for ((seenMs, seenDur) in seenPdtMs) {
                val tol = minOf(thresholdMs, (seenDur * 0.45).roundToLong().coerceAtLeast(450L))
                if (abs(pdt - seenMs) <= tol) {
                    return true
                }
            }
            return false
        }

        private fun markSeen(seg: HlsSegment) {
            val uriKey = segmentUriKey(seg.uri)
            if (seenUriSet.add(uriKey)) {
                seenUriOrder.addLast(uriKey)
                if (seenUriOrder.size > SEEN_IDENTITY_LIMIT) {
                    val oldestUri = seenUriOrder.removeFirst()
                    seenUriSet.remove(oldestUri)
                }
            }
            val key = segmentIdentityKey(seg)
            if (seenSet.add(key)) {
                seenOrder.addLast(key)
                if (seenOrder.size > SEEN_IDENTITY_LIMIT) {
                    val oldest = seenOrder.removeFirst()
                    seenSet.remove(oldest)
                }
            }
            val pdt = seg.pdtMs
            if (pdt != null) {
                seenPdtMs.addLast(pdt to (seg.durationSec * 1_000.0).roundToLong().coerceAtLeast(1_000L))
                if (seenPdtMs.size > SEEN_IDENTITY_LIMIT) {
                    seenPdtMs.removeFirst()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Core decision engine (Section 7 — unit-testable)
    // -------------------------------------------------------------------------

    data class DecisionResult(
        val outputPlaylist: String,
        val sourceUsed: String,
        val adSegmentsSuppressed: Int,
        val spliceEvent: SpliceEvent? = null,
    )

    fun processMediaPlaylistDecision(
        variantUrl: String,
        primaryParsed: ParsedPlaylist,
        primaryRawText: String,
        window: ContinuityWindow,
        shadowPlaylist: ParsedPlaylist?,
        shadowVariantUrl: String?,
        shadowFresh: Boolean,
        continuityFloor: ContinuityFloor? = null,
        isInitialPlaylist: Boolean = !window.hasSeenContent,
        primaryVariantMeta: MasterVariant? = null,
        shadowVariantMeta: MasterVariant? = null,
    ): DecisionResult {
        window.onQualitySwitch(variantUrl, primaryParsed)

        val adCount = primaryParsed.segments.count { it.classification == SegmentClass.AD } + primaryParsed.prefetchAdCount
        val primaryHasAd = if (isInitialPlaylist) {
            primaryParsed.verdict == PlaylistVerdict.FULL_AD ||
                primaryParsed.verdict == PlaylistVerdict.PARTIAL_AD ||
                primaryParsed.hasPrefetchAd
        } else {
            primaryParsed.hasActiveAdAtLiveEdge
        }
        val primaryCleanAtLiveEdge = !primaryHasAd &&
            (primaryParsed.verdict == PlaylistVerdict.CLEAN || primaryParsed.verdict == PlaylistVerdict.PARTIAL_AD)
        val onShadow = window.activeSourceId?.startsWith("shadow@") == true
        val primaryHasMap = primaryParsed.activeMapLine != null || window.lastCleanSnapshot?.activeMapLine != null
        val shadowHasMap = shadowPlaylist?.activeMapLine != null
        val primaryContainer = if (primaryHasMap) "fmp4" else "ts"
        val shadowContainer = if (shadowPlaylist != null) (if (shadowHasMap) "fmp4" else "ts") else null
        val primaryCodec = primaryVariantMeta?.videoCodecFamily.orEmpty()
        val shadowCodec = shadowVariantMeta?.videoCodecFamily.orEmpty()
        val containerCompatible = primaryHasMap == shadowHasMap
        val codecCompatible = primaryCodec.isEmpty() || shadowCodec.isEmpty() || primaryCodec == shadowCodec
        val cleanShadowAvailable = shadowFresh && shadowPlaylist != null &&
            shadowPlaylist.verdict == PlaylistVerdict.CLEAN &&
            !shadowVariantUrl.isNullOrBlank() &&
            containerCompatible &&
            codecCompatible

        // Before any ad splice has occurred (or right after Classic Swap finished while historical ads are still
        // ageing out of a long 60s LL-HLS sliding window), pass clean-tail primary playlists through untouched.
        if (primaryCleanAtLiveEdge && window.spliceCount == 0 && !onShadow && window.isEmpty()) {
            window.noteHoldPass(false)
            val cleanSnap = if (primaryParsed.verdict == PlaylistVerdict.CLEAN) {
                primaryParsed
            } else {
                primaryParsed.cleanTailOnly()
            }
            if (cleanSnap.segments.isNotEmpty()) {
                window.recordCleanSnapshot(cleanSnap)
            }
            return DecisionResult(disableDeltaSkipping(primaryRawText), "primary", 0)
        }

        // First mid-roll after clean passthrough: seed ContinuityWindow from the last clean snapshot
        // (including clean #EXT-X-TWITCH-PREFETCH segments already exposed to Media3 as 2s live-edge segments)
        // and bridge any newer non-ad prefix segments from primaryParsed (including when only prefetch entered the ad).
        if (primaryHasAd && window.isEmpty()) {
            window.lastCleanSnapshot?.let { snap ->
                window.ingest(
                    sourceId = "primary",
                    segments = buildCleanSnapshotSeedSegments(snap),
                    sourceMediaSequence = snap.mediaSequence,
                    sourceDiscontinuitySequence = snap.discontinuitySequence,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                    primaryCodec = primaryCodec.ifEmpty { null },
                    primaryContainer = primaryContainer,
                    shadowCodec = shadowCodec.ifEmpty { null },
                    shadowContainer = shadowContainer,
                )
            }
            if (!window.isEmpty() && (primaryParsed.verdict == PlaylistVerdict.PARTIAL_AD || primaryParsed.verdict == PlaylistVerdict.CLEAN)) {
                window.ingest(
                    sourceId = "primary",
                    segments = primaryParsed.segments,
                    sourceMediaSequence = primaryParsed.mediaSequence,
                    sourceDiscontinuitySequence = primaryParsed.discontinuitySequence,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                    primaryCodec = primaryCodec.ifEmpty { null },
                    primaryContainer = primaryContainer,
                    shadowCodec = shadowCodec.ifEmpty { null },
                    shadowContainer = shadowContainer,
                )
            }
        }

        if (primaryCleanAtLiveEdge) {
            window.noteCleanPrimary(true)
        } else if (primaryHasAd) {
            window.noteCleanPrimary(false)
        }

        val sourceUsed: String
        var spliceEvent: SpliceEvent? = null
        when {
            primaryHasAd && cleanShadowAvailable -> {
                sourceUsed = "shadow"
                window.noteHoldPass(false)
                val anchorToPrevious = window.isEmpty() && continuityFloor != null && continuityFloor.minEndMediaSequence > 0L
                val shadowStartSeq = if (anchorToPrevious) {
                    maxOf(continuityFloor!!.minMediaSequence, continuityFloor.minEndMediaSequence)
                } else {
                    shadowPlaylist!!.mediaSequence
                }
                val shadowStartDisc = if (anchorToPrevious) {
                    maxOf(shadowPlaylist!!.discontinuitySequence, continuityFloor!!.minDiscontinuitySequenceForReset + 1L)
                } else {
                    shadowPlaylist!!.discontinuitySequence
                }
                val ingestRes = window.ingest(
                    sourceId = "shadow@$shadowVariantUrl",
                    segments = shadowPlaylist!!.segments,
                    sourceMediaSequence = shadowStartSeq,
                    sourceDiscontinuitySequence = shadowStartDisc,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                    primaryCodec = primaryCodec.ifEmpty { null },
                    primaryContainer = primaryContainer,
                    shadowCodec = shadowCodec.ifEmpty { null },
                    shadowContainer = shadowContainer,
                )
                spliceEvent = ingestRes.spliceEvent
            }
            primaryHasAd && !cleanShadowAvailable && (onShadow || (primaryParsed.verdict == PlaylistVerdict.FULL_AD && !window.isEmpty())) -> {
                val holdCount = window.noteHoldPass(true)
                if (holdCount > MAX_HOLD_PASSES) {
                    // Never freeze ExoPlayer indefinitely waiting for a dead shadow: hand off cleanly to Classic Swap
                    window.reset()
                    return DecisionResult(primaryRawText, "passthrough", 0)
                }
                sourceUsed = "hold"
            }
            primaryHasAd && !cleanShadowAvailable && (primaryParsed.verdict == PlaylistVerdict.PARTIAL_AD || primaryParsed.verdict == PlaylistVerdict.CLEAN) -> {
                sourceUsed = "primary-partial"
                window.noteHoldPass(false)
                val ingestRes = window.ingest(
                    sourceId = "primary",
                    segments = primaryParsed.segments,
                    sourceMediaSequence = primaryParsed.mediaSequence,
                    sourceDiscontinuitySequence = primaryParsed.discontinuitySequence,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                    primaryCodec = primaryCodec.ifEmpty { null },
                    primaryContainer = primaryContainer,
                    shadowCodec = shadowCodec.ifEmpty { null },
                    shadowContainer = shadowContainer,
                )
                spliceEvent = ingestRes.spliceEvent
            }
            primaryCleanAtLiveEdge && onShadow && window.consecutiveCleanPrimary < CLEAN_STREAK_TO_RETURN && cleanShadowAvailable -> {
                sourceUsed = "shadow-cooldown"
                window.noteHoldPass(false)
                val ingestRes = window.ingest(
                    sourceId = "shadow@$shadowVariantUrl",
                    segments = shadowPlaylist!!.segments,
                    sourceMediaSequence = shadowPlaylist.mediaSequence,
                    sourceDiscontinuitySequence = shadowPlaylist.discontinuitySequence,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                    primaryCodec = primaryCodec.ifEmpty { null },
                    primaryContainer = primaryContainer,
                    shadowCodec = shadowCodec.ifEmpty { null },
                    shadowContainer = shadowContainer,
                )
                spliceEvent = ingestRes.spliceEvent
            }
            primaryCleanAtLiveEdge -> {
                sourceUsed = "primary"
                window.noteHoldPass(false)
                val primaryTail = if (primaryParsed.verdict == PlaylistVerdict.CLEAN) {
                    primaryParsed
                } else {
                    primaryParsed.cleanTailOnly()
                }
                val ingestRes = window.ingest(
                    sourceId = "primary",
                    segments = primaryTail.segments,
                    sourceMediaSequence = primaryTail.mediaSequence,
                    sourceDiscontinuitySequence = primaryTail.discontinuitySequence,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                    primaryCodec = primaryCodec.ifEmpty { null },
                    primaryContainer = primaryContainer,
                    shadowCodec = shadowCodec.ifEmpty { null },
                    shadowContainer = shadowContainer,
                )
                spliceEvent = ingestRes.spliceEvent
            }
            else -> {
                sourceUsed = "fallback"
                window.noteHoldPass(false)
            }
        }

        if (!window.isEmpty()) {
            continuityFloor?.let { window.enforceContinuityFloor(it) }
            val emitted = window.emit(primaryParsed.headerLines, primaryParsed.activeMapLine)
            return DecisionResult(emitted, sourceUsed, if (sourceUsed == "fallback") 0 else adCount, spliceEvent)
        }

        // Empty window fallback: never return an empty playlist that would break ExoPlayer
        if (primaryParsed.verdict == PlaylistVerdict.FULL_AD && cleanShadowAvailable) {
            window.noteHoldPass(false)
            val anchorToPrevious = continuityFloor != null && continuityFloor.minEndMediaSequence > 0L
            val shadowStartSeq = if (anchorToPrevious) {
                maxOf(continuityFloor!!.minMediaSequence, continuityFloor.minEndMediaSequence)
            } else {
                shadowPlaylist!!.mediaSequence
            }
            val shadowStartDisc = if (anchorToPrevious) {
                maxOf(shadowPlaylist!!.discontinuitySequence, continuityFloor!!.minDiscontinuitySequenceForReset + 1L)
            } else {
                shadowPlaylist!!.discontinuitySequence
            }
            val ingestRes = window.ingest(
                sourceId = "shadow@$shadowVariantUrl",
                segments = shadowPlaylist!!.segments,
                sourceMediaSequence = shadowStartSeq,
                sourceDiscontinuitySequence = shadowStartDisc,
                prefetches = emptyList(),
                allowPrefetches = false,
                primaryCodec = primaryCodec.ifEmpty { null },
                primaryContainer = primaryContainer,
                shadowCodec = shadowCodec.ifEmpty { null },
                shadowContainer = shadowContainer,
            )
            continuityFloor?.let { window.enforceContinuityFloor(it) }
            return DecisionResult(
                window.emit(primaryParsed.headerLines, shadowPlaylist.activeMapLine),
                "shadow-fallback",
                adCount,
                ingestRes.spliceEvent,
            )
        }
        return DecisionResult(primaryRawText, "passthrough", 0)
    }

    private fun buildCleanSnapshotSeedSegments(snap: ParsedPlaylist): List<HlsSegment> {
        if (snap.prefetchLines.isEmpty() || snap.prefetchAdCount > 0 || snap.segments.isEmpty()) {
            return snap.segments
        }
        val out = ArrayList<HlsSegment>(snap.segments.size + snap.prefetchLines.size)
        out.addAll(snap.segments)
        val lastSeg = snap.segments.last()
        var nextPdtMs = lastSeg.pdtMs?.let { it + (lastSeg.durationSec * 1_000.0).roundToLong().coerceAtLeast(1L) }
        for (prefLine in snap.prefetchLines) {
            val prefUri = prefLine.removePrefix("#EXT-X-TWITCH-PREFETCH:").trim()
            if (prefUri.isEmpty()) continue
            val lower = prefUri.lowercase()
            if (lower.contains("/stitched/") ||
                lower.contains("amazon-adsystem") ||
                lower.contains("/ad/") ||
                lower.contains("/ads/") ||
                lower.contains("adsegment")
            ) {
                break
            }
            val pdtMs = nextPdtMs
            out.add(
                HlsSegment(
                    uri = prefUri,
                    durationSec = 2.0,
                    title = "live",
                    pdtIso = pdtMs?.let { Instant.fromEpochMilliseconds(it).toString() },
                    pdtMs = pdtMs,
                    mapLine = snap.activeMapLine ?: lastSeg.mapLine,
                    keyLine = lastSeg.keyLine,
                    byteRangeLine = null,
                    attachedTags = emptyList(),
                    classification = SegmentClass.CONTENT,
                )
            )
            if (nextPdtMs != null) {
                nextPdtMs += 2_000L
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Live Shadow Session Manager (Section 6 & Section 12)
    // -------------------------------------------------------------------------

    private class ChannelState(val login: String) {
        val lock = Any()
        val window = ContinuityWindow()

        @Volatile var targetHeight: Int = 2160
        @Volatile var primaryVariantMeta: MasterVariant? = null
        @Volatile var forceH264: Boolean = false
        @Volatile var onCodecMismatchForceH264: (() -> Unit)? = null
        @Volatile var lastPrimaryAdAtMs: Long = 0L
        @Volatile var shadowMasterVariants: List<MasterVariant> = emptyList()
        @Volatile var shadowMasterFetchedAtMs: Long = 0L
        @Volatile var shadowVariantUrl: String? = null
        @Volatile var shadowVariantMeta: MasterVariant? = null
        @Volatile var shadowPlayerType: String? = null
        @Volatile var shadowParsed: ParsedPlaylist? = null
        @Volatile var shadowFetchedAtMs: Long = 0L
        @Volatile var consecutiveWarmFailures: Int = 0
        @Volatile var backoffUntilMs: Long = 0L
        @Volatile var lastQualityAlignMs: Long = 0L
        @Volatile var activeRollType: String? = null
        @Volatile var inAdBreak: Boolean = false
        @Volatile var adBreakStartElapsedMs: Long = 0L
        @Volatile var adBreakMaxSuppressed: Int = 0
        @Volatile var adBreakStartSplices: Int = 0
        @Volatile var adBreakRollType: String? = null

        var warmJob: Job? = null
        var refreshJob: Job? = null
        var closeJob: Job? = null

        fun isShadowFresh(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
            shadowParsed != null && shadowVariantUrl != null && (nowMs - shadowFetchedAtMs) < SHADOW_MAX_AGE_MS

        fun invalidateShadow() {
            shadowVariantUrl = null
            shadowVariantMeta = null
            shadowParsed = null
            shadowFetchedAtMs = 0L
        }

        fun shutdown() {
            warmJob?.cancel()
            refreshJob?.cancel()
            closeJob?.cancel()
            warmJob = null
            refreshJob = null
            closeJob = null
            activeRollType = null
            inAdBreak = false
            adBreakStartElapsedMs = 0L
            adBreakMaxSuppressed = 0
            adBreakStartSplices = 0
            adBreakRollType = null
            primaryVariantMeta = null
            forceH264 = false
            onCodecMismatchForceH264 = null
            invalidateShadow()
            shadowMasterVariants = emptyList()
            shadowMasterFetchedAtMs = 0L
            synchronized(lock) { window.reset() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channels = ConcurrentHashMap<String, ChannelState>()
    private val variantToChannel = ConcurrentHashMap<String, String>()
    private val variantToMeta = ConcurrentHashMap<String, MasterVariant>()
    @Volatile
    private var activeDisplayedChannel: String? = null

    /** Returns `"preroll"` or `"midroll"` while Swap+ is actively covering an ad break on [channelLogin]. */
    fun activeRollType(channelLogin: String?): String? {
        val norm = channelLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return channels[norm]?.activeRollType
    }

    /** Step 1 + Step 2 on TV channel zap: schedule old channel close (2 min), reset & pre-warm new channel. */
    fun onChannelNavigated(
        context: Context,
        channelLogin: String?,
        multiStreamTile: Boolean = false,
        forceH264: Boolean = false,
    ) {
        val norm = channelLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        val appCtx = context.applicationContext
        if (!multiStreamTile) {
            val prev = activeDisplayedChannel
            if (prev != null && prev != norm) {
                channels[prev]?.let { oldState ->
                    oldState.warmJob?.cancel()
                    oldState.warmJob = null
                    oldState.refreshJob?.cancel()
                    oldState.refreshJob = null
                    oldState.closeJob?.cancel()
                    oldState.closeJob = scope.launch {
                        delay(SHADOW_LEAVE_TTL_MS)
                        if (activeDisplayedChannel != prev) {
                            oldState.shutdown()
                            channels.remove(prev, oldState)
                        }
                    }
                }
            }
            activeDisplayedChannel = norm
        }
        val state = channels.computeIfAbsent(norm) { ChannelState(it) }
        state.closeJob?.cancel()
        state.closeJob = null
        val codecJustSwitched = forceH264 && !state.forceH264
        if (codecJustSwitched) {
            state.forceH264 = true
            state.invalidateShadow()
            state.shadowMasterVariants = emptyList()
            state.shadowMasterFetchedAtMs = 0L
        } else if (!forceH264) {
            state.forceH264 = false
        }
        state.inAdBreak = false
        state.activeRollType = null
        synchronized(state.lock) {
            state.window.reset()
        }
        if (state.isShadowFresh() && !codecJustSwitched) {
            ensureRefreshLoop(appCtx, state)
        } else {
            warmShadowAsync(appCtx, state, force = codecJustSwitched)
        }
    }

    /** Stops and releases all shadow sessions (e.g. when player closes or Swap+ is disabled). */
    fun stopAll() {
        activeDisplayedChannel = null
        channels.values.forEach { it.shutdown() }
        channels.clear()
        variantToChannel.clear()
        variantToMeta.clear()
    }

    private fun warmShadowAsync(appCtx: Context, state: ChannelState, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (force) {
            state.backoffUntilMs = 0L
        } else if (now < state.backoffUntilMs) {
            return
        }
        if (state.warmJob?.isActive == true) return
        state.warmJob = scope.launch {
            val ok = warmShadowNow(appCtx, state)
            if (ok) {
                state.consecutiveWarmFailures = 0
                state.backoffUntilMs = 0L
                ensureRefreshLoop(appCtx, state)
            } else {
                state.consecutiveWarmFailures++
                val shift = (state.consecutiveWarmFailures - 1).coerceIn(0, 3)
                val waitMs = (10_000L shl shift).coerceAtMost(60_000L)
                state.backoffUntilMs = SystemClock.elapsedRealtime() + waitMs
            }
        }
    }

    /** Fast single-GET refresh of the existing shadow media playlist (avoids 3-step GQL warm when URL is still valid). */
    private suspend fun refreshShadowVariantNow(appCtx: Context, state: ChannelState): Boolean {
        val url = state.shadowVariantUrl ?: return false
        val app = appCtx as? XtraApp ?: return false
        val client = buildShadowHttpClient(app.xtraModule.okHttpClient.value)
        val text = withTimeoutOrNull(SHADOW_FAST_REFRESH_TIMEOUT_MS) {
            fetchText(client, url)
        } ?: return false
        val parsed = parseAndClassifyMediaPlaylist(url, text)
        return if (parsed.verdict == PlaylistVerdict.CLEAN) {
            state.shadowParsed = parsed
            state.shadowFetchedAtMs = SystemClock.elapsedRealtime()
            true
        } else {
            state.invalidateShadow()
            false
        }
    }

    private suspend fun warmShadowNow(appCtx: Context, state: ChannelState): Boolean {
        val app = appCtx as? XtraApp ?: return false
        val xtraModule = app.xtraModule
        val client = buildShadowHttpClient(xtraModule.okHttpClient.value)
        val userCodecs = appCtx.prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264") ?: "av1,h265,h264"
        val primaryMeta = state.primaryVariantMeta
        val desiredFamily = if (state.forceH264) "avc1" else primaryMeta?.videoCodecFamily.orEmpty()
        val codecAttempts = when {
            state.forceH264 || desiredFamily == "avc1" || userCodecs.equals("h264", true) || userCodecs.equals("avc1", true) ->
                listOf("avc1")
            desiredFamily.isNotEmpty() ->
                listOf(userCodecs)
            else ->
                listOf(userCodecs, "avc1")
        }

        var codecMismatchCount = 0
        for ((platform, playerType) in SHADOW_PLAYER_TYPES) {
            if (desiredFamily.isNotEmpty() && desiredFamily != "avc1" && codecMismatchCount >= 2) {
                break
            }
            for (codecs in codecAttempts) {
                val masterUrl = runCatching {
                    withTimeoutOrNull(SHADOW_REQUEST_TIMEOUT_MS) {
                        xtraModule.playerRepository.loadStreamPlaylistUrl(
                            context = appCtx,
                            networkLibrary = C.OKHTTP,
                            gqlHeaders = (if (platform == "web") {
                                TwitchApiHelper.getGQLWebHeaders(appCtx, includeToken = false)
                            } else {
                                TwitchApiHelper.getGQLHeaders(appCtx, includeToken = false)
                            }).filterKeys { !it.equals(C.HEADER_TOKEN, ignoreCase = true) },
                            channelLogin = state.login,
                            platform = platform,
                            playerType = playerType,
                            supportedCodecs = codecs,
                            proxyPlaybackAccessToken = false,
                            proxyHost = null,
                            proxyPort = null,
                            proxyUser = null,
                            proxyPassword = null,
                            proxyTimeout = null,
                            enableIntegrity = false,
                        )
                    }
                }.getOrNull() ?: continue

                val masterText = fetchText(client, masterUrl) ?: continue
                val (_, variants) = filterMasterAndExtractVariants(masterUrl, masterText)
                if (variants.isEmpty()) continue
                val targetForPick = if (state.forceH264) {
                    primaryMeta?.copy(codecs = "avc1") ?: MasterVariant(url = "", height = state.targetHeight, bandwidth = 0L, codecs = "avc1")
                } else {
                    primaryMeta
                }
                val chosen = pickShadowVariant(variants, targetForPick, state.targetHeight)
                if (chosen == null) {
                    codecMismatchCount++
                    continue
                }
                val mediaText = fetchText(client, chosen.url) ?: continue
                val parsed = parseAndClassifyMediaPlaylist(chosen.url, mediaText)
                if (parsed.verdict == PlaylistVerdict.CLEAN) {
                    state.shadowMasterVariants = variants
                    state.shadowMasterFetchedAtMs = SystemClock.elapsedRealtime()
                    state.shadowVariantUrl = chosen.url
                    state.shadowVariantMeta = chosen
                    state.shadowPlayerType = "$platform/$playerType"
                    state.shadowParsed = parsed
                    state.shadowFetchedAtMs = SystemClock.elapsedRealtime()
                    runCatching {
                        Log.i(
                            TAG,
                            "Shadow warmed for ${state.login}: ${state.shadowPlayerType} " +
                                "${chosen.width}x${chosen.height}@${String.format(java.util.Locale.US, "%.0f", chosen.frameRate)} " +
                                "codec=${chosen.videoCodecFamily.ifEmpty { codecs }} container=${if (parsed.activeMapLine != null) "fmp4" else "ts"}",
                        )
                    }
                    return true
                }
            }
        }

        // Shadow could not match primary's non-H.264 codec (e.g. primary is HEVC/AV1 while anonymous shadow only offers avc1):
        // align BOTH primary and shadow to H.264 (supported_codecs=avc1) instead of switching decoders mid-ad.
        if (codecMismatchCount > 0 && desiredFamily.isNotEmpty() && desiredFamily != "avc1" && !state.forceH264) {
            state.forceH264 = true
            state.invalidateShadow()
            state.shadowMasterVariants = emptyList()
            state.shadowMasterFetchedAtMs = 0L
            runCatching {
                Log.i(
                    TAG,
                    "Shadow lacks codec=$desiredFamily on ${state.login} -> forcing H.264 (supported_codecs=avc1) on both primary and shadow",
                )
            }
            state.onCodecMismatchForceH264?.invoke()
            return warmShadowNow(appCtx, state)
        }
        return false
    }

    private fun ensureRefreshLoop(appCtx: Context, state: ChannelState) {
        if (state.refreshJob?.isActive == true) return
        state.refreshJob = scope.launch {
            val app = appCtx as? XtraApp ?: return@launch
            val client = buildShadowHttpClient(app.xtraModule.okHttpClient.value)
            while (channels[state.login] === state) {
                val now = SystemClock.elapsedRealtime()
                val inHotWindow = now - state.lastPrimaryAdAtMs < SHADOW_HOT_WINDOW_MS
                val interval = if (inHotWindow) SHADOW_REFRESH_HOT_MS else SHADOW_REFRESH_IDLE_MS
                delay(interval)
                val afterDelay = SystemClock.elapsedRealtime()
                val url = state.shadowVariantUrl
                if (url == null) {
                    warmShadowAsync(appCtx, state, force = inHotWindow)
                    return@launch
                }
                // Proactively renew shadow session before Twitch's ~15-min HLS token expires during long watches
                if (!inHotWindow && state.shadowMasterFetchedAtMs > 0L &&
                    (afterDelay - state.shadowMasterFetchedAtMs) >= SHADOW_MASTER_MAX_AGE_MS
                ) {
                    warmShadowAsync(appCtx, state)
                    return@launch
                }
                val text = fetchText(client, url)
                if (text == null) {
                    state.invalidateShadow()
                    warmShadowAsync(appCtx, state, force = inHotWindow)
                    return@launch
                }
                val parsed = parseAndClassifyMediaPlaylist(url, text)
                if (parsed.verdict == PlaylistVerdict.CLEAN) {
                    state.shadowParsed = parsed
                    state.shadowFetchedAtMs = SystemClock.elapsedRealtime()
                } else {
                    state.invalidateShadow()
                    warmShadowAsync(appCtx, state, force = inHotWindow)
                    return@launch
                }
            }
        }
    }

    private fun maybeAlignShadowVariant(appCtx: Context, state: ChannelState, primaryMeta: MasterVariant) {
        val prevMeta = state.primaryVariantMeta
        state.primaryVariantMeta = primaryMeta
        if (primaryMeta.height > 0) {
            state.targetHeight = primaryMeta.height
        }

        val currentShadow = state.shadowVariantMeta
        val primaryFamily = primaryMeta.videoCodecFamily
        val shadowFamily = currentShadow?.videoCodecFamily.orEmpty()
        val codecMismatch = primaryFamily.isNotEmpty() && shadowFamily.isNotEmpty() && primaryFamily != shadowFamily

        val resolutionChanged = prevMeta == null ||
            abs(primaryMeta.height - prevMeta.height) > 100 ||
            abs(primaryMeta.frameRate - prevMeta.frameRate) >= 10.0

        val now = SystemClock.elapsedRealtime()
        if (!codecMismatch && !resolutionChanged) return
        if (!codecMismatch && now - state.lastQualityAlignMs < 15_000L && prevMeta != null) return
        state.lastQualityAlignMs = now

        if (now - state.shadowMasterFetchedAtMs < SHADOW_MASTER_MAX_AGE_MS && state.shadowMasterVariants.isNotEmpty()) {
            val candidate = pickShadowVariant(state.shadowMasterVariants, primaryMeta, primaryMeta.height)
            if (candidate == null) {
                // Current shadow master doesn't contain primary's codec family -> re-warm (will align to avc1 if none available)
                state.invalidateShadow()
                warmShadowAsync(appCtx, state, force = true)
                return
            }
            if (candidate.url == state.shadowVariantUrl) return
            scope.launch {
                val app = appCtx as? XtraApp ?: return@launch
                val client = buildShadowHttpClient(app.xtraModule.okHttpClient.value)
                val text = fetchText(client, candidate.url) ?: return@launch
                val parsed = parseAndClassifyMediaPlaylist(candidate.url, text)
                if (parsed.verdict == PlaylistVerdict.CLEAN) {
                    state.shadowVariantUrl = candidate.url
                    state.shadowVariantMeta = candidate
                    state.shadowParsed = parsed
                    state.shadowFetchedAtMs = SystemClock.elapsedRealtime()
                }
            }
        } else if (codecMismatch) {
            state.invalidateShadow()
            warmShadowAsync(appCtx, state, force = true)
        } else {
            warmShadowAsync(appCtx, state)
        }
    }

    internal fun pickShadowVariant(
        variants: List<MasterVariant>,
        targetMeta: MasterVariant? = null,
        targetHeight: Int = targetMeta?.height ?: 0,
    ): MasterVariant? {
        if (variants.isEmpty()) return null
        val desiredCodec = targetMeta?.videoCodecFamily.orEmpty()
        val pool = if (desiredCodec.isNotEmpty()) {
            val matched = variants.filter { it.videoCodecFamily == desiredCodec }
            if (matched.isEmpty()) return null
            matched
        } else {
            variants
        }
        val effHeight = targetMeta?.height?.takeIf { it > 0 } ?: targetHeight
        val effWidth = targetMeta?.width ?: 0
        val effFps = targetMeta?.frameRate ?: 0.0
        if (effHeight <= 0) return pool.maxByOrNull { it.bandwidth }
        return pool.minWithOrNull(
            compareBy<MasterVariant> { abs(it.height - effHeight) }
                .thenBy { if (effWidth > 0 && it.width > 0) abs(it.width - effWidth) else 0 }
                .thenBy { if (effFps > 0.0 && it.frameRate > 0.0) abs(it.frameRate - effFps) else 0.0 }
                .thenByDescending { it.bandwidth }
        )
    }

    private fun buildShadowHttpClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .connectTimeout(SHADOW_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SHADOW_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(SHADOW_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    private fun fetchText(client: OkHttpClient, url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body.string() else null
        }
    }.getOrNull()

    private fun resolveChannelLogin(url: String, fallbackLogin: String?): String? {
        variantToChannel[url]?.let { return it }
        Regex("""/api/(?:v2/)?channel/hls/([A-Za-z0-9_]+)\.m3u8""", RegexOption.IGNORE_CASE).find(url)?.let {
            return it.groupValues[1].lowercase()
        }
        return fallbackLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: activeDisplayedChannel
    }

    internal fun resolveAbsoluteUrl(baseUrl: String, relativeOrAbsolute: String): String {
        if (relativeOrAbsolute.startsWith("http://", ignoreCase = true) ||
            relativeOrAbsolute.startsWith("https://", ignoreCase = true)
        ) {
            return relativeOrAbsolute
        }
        return runCatching { URI(baseUrl).resolve(relativeOrAbsolute).toString() }
            .getOrElse {
                val baseDir = baseUrl.substringBefore('?').substringBeforeLast('/')
                "$baseDir/${relativeOrAbsolute.trimStart('/')}"
            }
    }

    private fun formatPdtForLog(pdtMs: Long?): String =
        if (pdtMs == null) "null" else runCatching { Instant.fromEpochMilliseconds(pdtMs).toString() }.getOrDefault(pdtMs.toString())

    private fun shortSourceLabel(sourceId: String): String =
        if (sourceId.startsWith("shadow@")) "shadow" else sourceId

    // -------------------------------------------------------------------------
    // Media3 HlsPlaylistParserFactory integration
    // -------------------------------------------------------------------------

    @OptIn(UnstableApi::class)
    class ShadowPlaylistParserFactory(
        private val context: Context,
        private val delegate: HlsPlaylistParserFactory,
        private val channelLoginHint: String?,
        private val onAdHandled: ((Boolean) -> Unit)? = null,
        private val shouldBypass: (() -> Boolean)? = null,
        private val onCodecMismatchForceH264: (() -> Unit)? = null,
    ) : HlsPlaylistParserFactory {
        private val appCtx = context.applicationContext

        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
            WrappingParser(appCtx, delegate.createPlaylistParser(), null, channelLoginHint, onAdHandled, shouldBypass, onCodecMismatchForceH264)

        override fun createPlaylistParser(
            multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?,
        ): ParsingLoadable.Parser<HlsPlaylist> =
            WrappingParser(
                appCtx = appCtx,
                inner = delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist),
                previousMediaPlaylist = previousMediaPlaylist,
                channelLoginHint = channelLoginHint,
                onAdHandled = onAdHandled,
                shouldBypass = shouldBypass,
                onCodecMismatchForceH264 = onCodecMismatchForceH264,
            )

        private class WrappingParser(
            private val appCtx: Context,
            private val inner: ParsingLoadable.Parser<HlsPlaylist>,
            private val previousMediaPlaylist: HlsMediaPlaylist?,
            private val channelLoginHint: String?,
            private val onAdHandled: ((Boolean) -> Unit)?,
            private val shouldBypass: (() -> Boolean)?,
            private val onCodecMismatchForceH264: (() -> Unit)?,
        ) : ParsingLoadable.Parser<HlsPlaylist> {
            override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
                val url = uri.toString()
                if (isVodOrExcludedUrl(url)) {
                    return inner.parse(uri, inputStream)
                }
                val rawBytes = inputStream.readBytes()
                if (rawBytes.isEmpty()) {
                    return inner.parse(uri, ByteArrayInputStream(rawBytes))
                }
                val text = String(rawBytes, Charsets.UTF_8)
                if (isVodPlaylistText(text)) {
                    return inner.parse(uri, ByteArrayInputStream(rawBytes))
                }
                val isMaster = text.contains("#EXT-X-STREAM-INF:")
                val preParsedMedia = if (!isMaster) {
                    runCatching {
                        inner.parse(uri, ByteArrayInputStream(rawBytes)) as? HlsMediaPlaylist
                    }.getOrNull()
                } else {
                    null
                }
                val outputText = runCatching {
                    interceptPlaylistText(
                        appCtx = appCtx,
                        url = url,
                        text = text,
                        channelLoginHint = channelLoginHint,
                        onAdHandled = onAdHandled,
                        shouldBypass = shouldBypass,
                        onCodecMismatchForceH264 = onCodecMismatchForceH264,
                        media3Playlist = preParsedMedia,
                        previousMediaPlaylist = previousMediaPlaylist,
                    )
                }.onFailure { err ->
                    if (err !is InterruptedException) {
                        runCatching { Log.w(TAG, "Playlist intercept failed for $url", err) }
                    }
                }.getOrDefault(text)
                if (preParsedMedia != null && outputText === text) {
                    return preParsedMedia
                }
                return inner.parse(uri, ByteArrayInputStream(outputText.toByteArray(Charsets.UTF_8)))
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun isMedia3AdSegment(playlist: HlsMediaPlaylist, segment: HlsMediaPlaylist.Segment): Boolean {
        val title = segment.title.substringBefore('|')
        if (title == "Amazon" || title == "Adform" || title == "DCM") {
            return true
        }
        val segmentStartTime = playlist.startTimeUs + segment.relativeStartTimeUs
        return playlist.interstitials.any { dateRange ->
            if (dateRange.id.startsWith("stitched-ad-") ||
                dateRange.clientDefinedAttributes.find { it.name == "CLASS" }?.textValue == "twitch-stitched-ad" ||
                dateRange.clientDefinedAttributes.find { it.name.startsWith("X-TV-TWITCH-AD-") } != null
            ) {
                val startTime = dateRange.startDateUnixUs
                val endTime = dateRange.endDateUnixUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }
                    ?: dateRange.durationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startTime + it }
                    ?: dateRange.plannedDurationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startTime + it }
                endTime != null && segmentStartTime in startTime..<endTime
            } else {
                false
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildTimingHintFromMedia3(
        playlistUrl: String,
        rawText: String,
        media3Playlist: HlsMediaPlaylist?,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): PlaylistTimingHint? {
        if (media3Playlist == null && previousMediaPlaylist == null) return null
        val skippedCount = extractSkippedSegmentsCount(rawText)
        val segmentPdtMs: List<Long>? = if (media3Playlist != null && media3Playlist.hasProgramDateTime) {
            val segs = media3Playlist.segments
            if (skippedCount < segs.size) {
                val out = ArrayList<Long>(segs.size - skippedCount)
                for (i in skippedCount until segs.size) {
                    out.add((media3Playlist.startTimeUs + segs[i].relativeStartTimeUs) / 1_000L)
                }
                out
            } else {
                null
            }
        } else {
            null
        }

        val fallbackFirstPdtMs: Long? = segmentPdtMs?.firstOrNull()
            ?: previousMediaPlaylist?.takeIf { it.hasProgramDateTime }?.let { it.startTimeUs / 1_000L }

        val extraAdRanges = ArrayList<Pair<Long, Long>>(4)
        media3Playlist?.interstitials?.forEach { dateRange ->
            val isAd = dateRange.id.startsWith("stitched-ad-", ignoreCase = true) ||
                dateRange.clientDefinedAttributes.any {
                    (it.name == "CLASS" && it.textValue == "twitch-stitched-ad") ||
                        it.name.startsWith("X-TV-TWITCH-AD-", ignoreCase = true)
                }
            if (isAd && dateRange.startDateUnixUs != androidx.media3.common.C.TIME_UNSET) {
                val startUs = dateRange.startDateUnixUs
                val endUs = dateRange.endDateUnixUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }
                    ?: dateRange.durationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startUs + it }
                    ?: dateRange.plannedDurationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startUs + it }
                if (endUs != null && endUs > startUs) {
                    extraAdRanges.add((startUs / 1_000L) to (endUs / 1_000L))
                }
            }
        }

        val media3LiveEdgeIsAd = media3Playlist?.segments?.lastOrNull()?.let { isMedia3AdSegment(media3Playlist, it) } == true
        val media3AnySegmentIsAd = media3LiveEdgeIsAd ||
            (media3Playlist?.segments?.any { isMedia3AdSegment(media3Playlist, it) } == true)

        val initSeg = media3Playlist?.segments?.getOrNull(skippedCount)?.initializationSegment
            ?: media3Playlist?.segments?.lastOrNull()?.initializationSegment
            ?: previousMediaPlaylist?.segments?.lastOrNull()?.initializationSegment
        val fallbackMapLine = initSeg?.url?.takeIf { it.isNotBlank() }?.let { initUrl ->
            """#EXT-X-MAP:URI="${resolveAbsoluteUrl(playlistUrl, initUrl)}""""
        }

        if (segmentPdtMs == null && fallbackFirstPdtMs == null && extraAdRanges.isEmpty() &&
            fallbackMapLine == null && !media3LiveEdgeIsAd && !media3AnySegmentIsAd
        ) {
            return null
        }
        return PlaylistTimingHint(
            segmentPdtMs = segmentPdtMs,
            fallbackFirstSegmentPdtMs = fallbackFirstPdtMs,
            extraAdRangesMs = extraAdRanges,
            fallbackMapLine = fallbackMapLine,
            media3LiveEdgeIsAd = media3LiveEdgeIsAd,
            media3AnySegmentIsAd = media3AnySegmentIsAd,
        )
    }

    @OptIn(UnstableApi::class)
    private fun buildContinuityFloor(previousMediaPlaylist: HlsMediaPlaylist?): ContinuityFloor? {
        val prev = previousMediaPlaylist ?: return null
        val prevDiscDelta = prev.segments.lastOrNull()?.relativeDiscontinuitySequence ?: 0
        return ContinuityFloor(
            minMediaSequence = prev.mediaSequence,
            minEndMediaSequence = prev.mediaSequence + prev.segments.size.toLong(),
            // Header DISCONTINUITY-SEQUENCE applies to the FIRST segment of prev, not the last!
            minDiscontinuitySequence = prev.discontinuitySequence.toLong(),
            minDiscontinuitySequenceForReset = prev.discontinuitySequence.toLong() + prevDiscDelta.toLong(),
        )
    }

    @OptIn(UnstableApi::class)
    private fun interceptPlaylistText(
        appCtx: Context,
        url: String,
        text: String,
        channelLoginHint: String?,
        onAdHandled: ((Boolean) -> Unit)?,
        shouldBypass: (() -> Boolean)?,
        onCodecMismatchForceH264: (() -> Unit)? = null,
        media3Playlist: HlsMediaPlaylist? = null,
        previousMediaPlaylist: HlsMediaPlaylist? = null,
    ): String {
        val login = resolveChannelLogin(url, channelLoginHint) ?: return text
        if (text.contains("#EXT-X-STREAM-INF:")) {
            val (filtered, variants) = filterMasterAndExtractVariants(url, text)
            for (v in variants) {
                variantToChannel[v.url] = login
                variantToMeta[v.url] = v
            }
            val state = channels.computeIfAbsent(login) { ChannelState(it) }
            state.onCodecMismatchForceH264 = onCodecMismatchForceH264
            state.closeJob?.cancel()
            state.closeJob = null

            val topVariant = variants.maxByOrNull { it.bandwidth }
            val nonAvcVariant = variants.firstOrNull { it.videoCodecFamily.isNotEmpty() && it.videoCodecFamily != "avc1" }
            val hasPrimaryAvc = variants.any { it.videoCodecFamily == "avc1" }

            // Safety net: Swap+ requests supported_codecs=avc1 upfront, but if a primary master ever returns mixed
            // codecs (e.g. HEVC/AV1 alongside H.264, regardless of bitrate ordering), strip non-H.264 variants so
            // ExoPlayer stays aligned with the H.264 shadow stream from the very first segment.
            if (nonAvcVariant != null && hasPrimaryAvc) {
                state.forceH264 = true
                val avcTop = variants.filter { it.videoCodecFamily == "avc1" }.maxByOrNull { it.bandwidth }
                if (avcTop != null) {
                    state.primaryVariantMeta = avcTop
                    if (avcTop.height > 0) state.targetHeight = avcTop.height
                }
                runCatching {
                    Log.i(
                        TAG,
                        "Swap+ aligning ${nonAvcVariant.videoCodecFamily} primary master on $login to H.264 (avc1)",
                    )
                }
                val avcFiltered = filterMasterToCodecFamily(filtered, "avc1")
                if (!state.isShadowFresh()) {
                    warmShadowAsync(appCtx, state, force = true)
                }
                return avcFiltered
            }

            if (topVariant != null && state.primaryVariantMeta == null) {
                state.primaryVariantMeta = topVariant
                if (topVariant.height > 0) state.targetHeight = topVariant.height
            }
            warmShadowAsync(appCtx, state)
            return filtered
        }

        val state = channels.computeIfAbsent(login) { ChannelState(it) }
        if (onCodecMismatchForceH264 != null) {
            state.onCodecMismatchForceH264 = onCodecMismatchForceH264
        }

        // While Classic Swap / Proxy fallback is actively covering an ad break, step aside completely
        // so the hidden main player reads raw Twitch playlists and exits the backup player right on time.
        if (shouldBypass?.invoke() == true) {
            synchronized(state.lock) {
                if (!state.window.isEmpty()) {
                    state.window.reset()
                }
            }
            state.activeRollType = null
            state.inAdBreak = false
            onAdHandled?.invoke(false)
            return text
        }

        val primaryMeta = variantToMeta[url]
        if (primaryMeta != null) {
            maybeAlignShadowVariant(appCtx, state, primaryMeta)
        }

        val timingHint = buildTimingHintFromMedia3(url, text, media3Playlist, previousMediaPlaylist)
        val continuityFloor = buildContinuityFloor(previousMediaPlaylist)
        val parsed = parseAndClassifyMediaPlaylist(url, text, timingHint)
        val isInitialPlaylist = previousMediaPlaylist == null && !state.window.hasSeenContent
        val hasAd = if (isInitialPlaylist) {
            parsed.verdict == PlaylistVerdict.FULL_AD ||
                parsed.verdict == PlaylistVerdict.PARTIAL_AD ||
                parsed.hasPrefetchAd ||
                timingHint?.media3AnySegmentIsAd == true
        } else {
            parsed.hasActiveAdAtLiveEdge
        }
        if (hasAd) {
            val now = SystemClock.elapsedRealtime()
            state.lastPrimaryAdAtMs = now
            val lastContentEndMs = synchronized(state.lock) {
                state.window.lastServedEndMs
                    ?: state.window.lastCleanSnapshot?.let { snap ->
                        val seed = buildCleanSnapshotSeedSegments(snap)
                        seed.lastOrNull()?.let { last ->
                            last.pdtMs?.let { it + (last.durationSec * 1_000.0).roundToLong() }
                        }
                    }
            }
            val shadowLastPdtMs = state.shadowParsed?.segments?.lastOrNull()?.pdtMs
            val shadowLagsLiveEdge = lastContentEndMs != null &&
                shadowLastPdtMs != null &&
                shadowLastPdtMs < lastContentEndMs - 500L
            val needsFastRefresh = state.shadowVariantUrl != null && (
                (now - state.shadowFetchedAtMs) >= SHADOW_STALE_ON_AD_MS ||
                    (shadowLagsLiveEdge && (now - state.shadowFetchedAtMs) >= 200L)
                )
            if (needsFastRefresh) {
                runBlocking {
                    refreshShadowVariantNow(appCtx, state)
                }
            }
            if (!state.isShadowFresh()) {
                warmShadowAsync(appCtx, state, force = true)
                val waitBudget = if (!state.window.hasSeenContent) PREROLL_WAIT_MS else MIDROLL_WAIT_MS
                runBlocking {
                    withTimeoutOrNull(waitBudget) {
                        state.warmJob?.join()
                    }
                }
            } else {
                ensureRefreshLoop(appCtx, state)
            }
        }

        val result = synchronized(state.lock) {
            processMediaPlaylistDecision(
                variantUrl = url,
                primaryParsed = parsed,
                primaryRawText = text,
                window = state.window,
                shadowPlaylist = state.shadowParsed,
                shadowVariantUrl = state.shadowVariantUrl,
                shadowFresh = state.isShadowFresh(),
                continuityFloor = continuityFloor,
                isInitialPlaylist = isInitialPlaylist,
                primaryVariantMeta = primaryMeta ?: state.primaryVariantMeta,
                shadowVariantMeta = state.shadowVariantMeta,
            )
        }

        result.spliceEvent?.let { ev ->
            runCatching {
                Log.i(
                    TAG,
                    "Splice on $login: ${shortSourceLabel(ev.sourceBefore)} -> ${shortSourceLabel(ev.sourceAfter)} " +
                        "lastPdt=${formatPdtForLog(ev.lastServedPdtMs)} lastEnd=${formatPdtForLog(ev.lastServedEndMs)} lastSeq=${ev.lastServedSeq} " +
                        "firstPdt=${formatPdtForLog(ev.insertedFirstPdtMs)} firstSeq=${ev.insertedFirstSeq} " +
                        "deltaMs=${ev.deltaMs ?: "null"} inserted=${ev.insertedCount} " +
                        "primary=${ev.primaryCodec ?: "?"}/${ev.primaryContainer ?: "?"} " +
                        "shadow=${ev.shadowCodec ?: "?"}/${ev.shadowContainer ?: "?"} " +
                        "disc=${ev.discontinuityEmitted} discSeq=${ev.discontinuitySequence}",
                )
            }
        }

        val adBadgeActive = result.sourceUsed == "shadow" ||
            result.sourceUsed == "shadow-cooldown" ||
            result.sourceUsed == "shadow-fallback" ||
            result.sourceUsed == "hold" ||
            (hasAd && result.sourceUsed != "passthrough")
        val nowElapsed = SystemClock.elapsedRealtime()
        if (adBadgeActive) {
            if (!state.inAdBreak) {
                state.inAdBreak = true
                state.adBreakStartElapsedMs = nowElapsed
                state.adBreakMaxSuppressed = result.adSegmentsSuppressed
                state.adBreakStartSplices = state.window.spliceCount
            } else if (result.adSegmentsSuppressed > state.adBreakMaxSuppressed) {
                state.adBreakMaxSuppressed = result.adSegmentsSuppressed
            }
            if (state.activeRollType == null || hasAd) {
                val rollTag = Regex("""X-TV-TWITCH-AD-ROLL-TYPE="?([A-Za-z]+)""", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)
                val roll = when {
                    rollTag?.contains("pre", ignoreCase = true) == true -> "preroll"
                    rollTag?.contains("mid", ignoreCase = true) == true -> "midroll"
                    state.activeRollType != null -> state.activeRollType
                    isInitialPlaylist -> "preroll"
                    else -> "midroll"
                }
                state.activeRollType = roll
                state.adBreakRollType = roll
            }
        } else {
            if (state.inAdBreak) {
                val durMs = (nowElapsed - state.adBreakStartElapsedMs).coerceAtLeast(0L)
                val breakSplices = (state.window.spliceCount - state.adBreakStartSplices).coerceAtLeast(0)
                runCatching {
                    Log.i(
                        TAG,
                        "Ad break ended on $login: roll=${state.adBreakRollType ?: "?"} durationMs=$durMs " +
                            "maxSuppressed=${state.adBreakMaxSuppressed} splices=$breakSplices " +
                            "totalSplices=${state.window.spliceCount} shadow=${state.shadowPlayerType}",
                    )
                }
                state.inAdBreak = false
                state.adBreakStartElapsedMs = 0L
                state.adBreakMaxSuppressed = 0
                state.adBreakStartSplices = 0
                state.adBreakRollType = null
            }
            state.activeRollType = null
        }
        onAdHandled?.invoke(adBadgeActive)
        if (hasAd) {
            runCatching {
                Log.i(TAG, "Ad break on $login: source=${result.sourceUsed} suppressed=${result.adSegmentsSuppressed} shadow=${state.shadowPlayerType} splices=${state.window.spliceCount}")
            }
        }
        return result.outputPlaylist
    }
}
