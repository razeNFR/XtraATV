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
    private const val MIDROLL_WAIT_MS = 700L
    private const val SHADOW_REFRESH_IDLE_MS = 6_000L
    private const val SHADOW_REFRESH_HOT_MS = 2_000L
    private const val SHADOW_HOT_WINDOW_MS = 90_000L
    private const val SHADOW_MAX_AGE_MS = 12_000L
    private const val SHADOW_LEAVE_TTL_MS = 120_000L
    private const val SHADOW_MASTER_MAX_AGE_MS = 600_000L
    private const val SHADOW_REQUEST_TIMEOUT_MS = 4_000L
    private const val CLEAN_STREAK_TO_RETURN = 3

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
    )

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
                    variants.add(
                        MasterVariant(
                            url = absUrl,
                            height = parseResolutionHeight(trimmed),
                            bandwidth = parseBandwidth(trimmed),
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

    private fun isPortraitVariant(streamInf: String): Boolean {
        if (streamInf.contains("IVS-GROUPS", ignoreCase = true) && streamInf.contains("portrait", ignoreCase = true)) {
            return true
        }
        val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(streamInf)
        if (resMatch != null) {
            val w = resMatch.groupValues[1].toIntOrNull() ?: 0
            val h = resMatch.groupValues[2].toIntOrNull() ?: 0
            if (w > 0 && h > w) return true
        }
        return false
    }

    private fun parseResolutionHeight(streamInf: String): Int {
        val resMatch = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE).find(streamInf)
        return resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun parseBandwidth(streamInf: String): Long {
        val bwMatch = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(streamInf)
        return bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    // -------------------------------------------------------------------------
    // Media playlist parser & segment classifier (Section 5)
    // -------------------------------------------------------------------------

    fun parseAndClassifyMediaPlaylist(playlistUrl: String, text: String): ParsedPlaylist {
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
        var activeMapLine: String? = null
        var activeKeyLine: String? = null
        var inSegments = false

        var curDuration = 2.0
        var curTitle = ""
        var curPdtIso: String? = null
        var curPdtMs: Long? = null
        var curByteRange: String? = null
        val curTags = ArrayList<String>(6)

        // Pass 1: collect all ad DATERANGE windows across the entire playlist (top, middle, or bottom)
        // so segment classification matches Media3/ExoPlayerService.isAdSegment regardless of tag placement.
        var lastPdtMs: Long? = null
        var elapsedSincePdtMs = 0.0
        val adDateRanges = ArrayList<Pair<Long, Long>>(4)
        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("#EXT-X-DATERANGE:")) {
                parseAdDateRangeWindow(trimmed)?.let { adDateRanges.add(it) }
            }
        }

        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXT-X-TWITCH-PREFETCH:")) {
                val prefUri = trimmed.removePrefix("#EXT-X-TWITCH-PREFETCH:").trim()
                if (prefUri.isNotEmpty()) {
                    prefetchLines.add("#EXT-X-TWITCH-PREFETCH:${resolveAbsoluteUrl(playlistUrl, prefUri)}")
                }
                continue
            }

            if (trimmed.contains("X-TV-TWITCH-STREAM-SOURCE", ignoreCase = true) &&
                !trimmed.contains("X-TV-TWITCH-STREAM-SOURCE=\"live\"", ignoreCase = true) &&
                !trimmed.contains("X-TV-TWITCH-STREAM-SOURCE=live", ignoreCase = true)
            ) {
                streamSourceNotLive = true
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
                    else -> {
                        curTags.add(trimmed)
                    }
                }
            } else {
                val absUri = resolveAbsoluteUrl(playlistUrl, trimmed)
                val segPdtMs = curPdtMs ?: lastPdtMs?.let { it + elapsedSincePdtMs.roundToLong() }
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
        if (trimmed.startsWith("#EXT-X-START")) return null
        if (trimmed.startsWith("#EXT-X-DATERANGE:")) {
            val isAd = trimmed.contains("stitched-ad", true) ||
                trimmed.contains("X-TV-TWITCH-AD-", true) ||
                STRONG_AD_ID_REGEX.containsMatchIn(trimmed)
            if (isAd) {
                if (!trimmed.contains("STREAM-SOURCE", ignoreCase = true)) {
                    return null
                }
                return stripAdDateRangeAttributes(trimmed)
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
        var spliceCount: Int = 0
            private set

        private var lastVariantUrl: String? = null
        private var lastPrefetches: List<String> = emptyList()
        var lastCleanSnapshot: ParsedPlaylist? = null
            private set

        fun reset() {
            window.clear()
            seenOrder.clear()
            seenSet.clear()
            seenPdtMs.clear()
            mediaSequence = -1L
            discontinuitySequence = 0L
            activeSourceId = null
            currentWindowLimit = BASE_WINDOW_SIZE
            hasSeenContent = false
            consecutiveCleanPrimary = 0
            spliceCount = 0
            lastVariantUrl = null
            lastPrefetches = emptyList()
            lastCleanSnapshot = null
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
                    seenPdtMs.clear()
                    activeSourceId = null
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
        ) {
            val nonAd = segments.filter { it.classification != SegmentClass.AD }
            if (nonAd.isEmpty()) return
            hasSeenContent = true

            val firstPass = mediaSequence < 0L
            if (firstPass) {
                mediaSequence = sourceMediaSequence
                discontinuitySequence = sourceDiscontinuitySequence
            }

            val fresh = nonAd.filter { !isAlreadySeen(it) }
            if (fresh.isEmpty()) {
                lastPrefetches = if (allowPrefetches) prefetches else emptyList()
                return
            }

            val sourceChanged = activeSourceId != null && activeSourceId != sourceId
            var needDiscontinuity = sourceChanged

            val toAppend: List<HlsSegment>
            if (firstPass) {
                toAppend = fresh.takeLast(BASE_WINDOW_SIZE)
                // Mark any earlier segments in firstPass as seen too
                fresh.forEach { markSeen(it) }
            } else if (fresh.size > BASE_WINDOW_SIZE) {
                // Catch-up: keep latest 14, mark all as seen, insert discontinuity
                fresh.forEach { markSeen(it) }
                toAppend = fresh.takeLast(BASE_WINDOW_SIZE)
                needDiscontinuity = true
            } else {
                toAppend = fresh.take(MAX_INGEST_PER_PASS)
                toAppend.forEach { markSeen(it) }
            }

            if (needDiscontinuity) {
                currentWindowLimit = MAX_WINDOW_SIZE
                spliceCount++
            } else if (currentWindowLimit > BASE_WINDOW_SIZE) {
                currentWindowLimit = (currentWindowLimit - 1).coerceAtLeast(BASE_WINDOW_SIZE)
            }

            toAppend.forEachIndexed { idx, seg ->
                val disc = needDiscontinuity && idx == 0 && window.isNotEmpty()
                window.addLast(
                    WindowSegment(
                        uri = seg.uri,
                        durationSec = seg.durationSec,
                        pdtIso = seg.pdtIso,
                        identityMs = seg.pdtMs,
                        identityKey = segmentIdentityKey(seg),
                        hasDiscontinuity = disc,
                        mapLine = seg.mapLine,
                        keyLine = seg.keyLine,
                        byteRangeLine = seg.byteRangeLine,
                    )
                )
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
            if (discontinuitySequence > 0L) {
                sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuitySequence).append('\n')
            }

            var currentMap: String? = null
            var currentKey: String? = null
            window.forEachIndexed { idx, seg ->
                if (seg.hasDiscontinuity) {
                    sb.append("#EXT-X-DISCONTINUITY\n")
                    // Re-emit MAP on splice if present so ExoPlayer re-initializes codec/init segment cleanly
                    seg.mapLine?.let {
                        sb.append(it).append('\n')
                        currentMap = it
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
                if (seg.pdtIso != null) {
                    sb.append("#EXT-X-PROGRAM-DATE-TIME:").append(seg.pdtIso).append('\n')
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
            seg.pdtMs?.let { "pdt:$it" } ?: "uri:${seg.uri.substringBefore('?')}"

        private fun isAlreadySeen(seg: HlsSegment): Boolean {
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
    )

    fun processMediaPlaylistDecision(
        variantUrl: String,
        primaryParsed: ParsedPlaylist,
        primaryRawText: String,
        window: ContinuityWindow,
        shadowPlaylist: ParsedPlaylist?,
        shadowVariantUrl: String?,
        shadowFresh: Boolean,
    ): DecisionResult {
        window.onQualitySwitch(variantUrl, primaryParsed)

        val adCount = primaryParsed.segments.count { it.classification == SegmentClass.AD }
        val primaryHasAd = primaryParsed.verdict == PlaylistVerdict.FULL_AD || primaryParsed.verdict == PlaylistVerdict.PARTIAL_AD
        val onShadow = window.activeSourceId?.startsWith("shadow@") == true
        val cleanShadowAvailable = shadowFresh && shadowPlaylist != null &&
            shadowPlaylist.verdict == PlaylistVerdict.CLEAN && !shadowVariantUrl.isNullOrBlank()

        // Before any ad splice has occurred, pass clean primary playlists through untouched so native low-latency
        // prefetch and ABR quality switching are never altered; record snapshot to seed mid-rolls cleanly.
        if (!primaryHasAd && primaryParsed.verdict == PlaylistVerdict.CLEAN && window.spliceCount == 0 && !onShadow && window.isEmpty()) {
            window.recordCleanSnapshot(primaryParsed)
            return DecisionResult(primaryRawText, "primary", 0)
        }

        // First mid-roll after clean passthrough: seed ContinuityWindow from the last clean snapshot
        if (primaryHasAd && window.isEmpty()) {
            window.lastCleanSnapshot?.let { snap ->
                window.ingest(
                    sourceId = "primary",
                    segments = snap.segments,
                    sourceMediaSequence = snap.mediaSequence,
                    sourceDiscontinuitySequence = snap.discontinuitySequence,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                )
            }
        }

        if (!primaryHasAd && primaryParsed.verdict == PlaylistVerdict.CLEAN) {
            window.noteCleanPrimary(true)
        } else if (primaryHasAd) {
            window.noteCleanPrimary(false)
        }

        val sourceUsed: String
        when {
            primaryHasAd && cleanShadowAvailable -> {
                sourceUsed = "shadow"
                window.ingest(
                    sourceId = "shadow@$shadowVariantUrl",
                    segments = shadowPlaylist!!.segments,
                    sourceMediaSequence = shadowPlaylist.mediaSequence,
                    sourceDiscontinuitySequence = shadowPlaylist.discontinuitySequence,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                )
            }
            primaryHasAd && !cleanShadowAvailable && (onShadow || (primaryParsed.verdict == PlaylistVerdict.FULL_AD && !window.isEmpty())) -> {
                sourceUsed = "hold"
            }
            primaryHasAd && !cleanShadowAvailable && primaryParsed.verdict == PlaylistVerdict.PARTIAL_AD -> {
                sourceUsed = "primary-partial"
                window.ingest(
                    sourceId = "primary",
                    segments = primaryParsed.segments,
                    sourceMediaSequence = primaryParsed.mediaSequence,
                    sourceDiscontinuitySequence = primaryParsed.discontinuitySequence,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                )
            }
            !primaryHasAd && onShadow && window.consecutiveCleanPrimary < CLEAN_STREAK_TO_RETURN && cleanShadowAvailable -> {
                sourceUsed = "shadow-cooldown"
                window.ingest(
                    sourceId = "shadow@$shadowVariantUrl",
                    segments = shadowPlaylist!!.segments,
                    sourceMediaSequence = shadowPlaylist.mediaSequence,
                    sourceDiscontinuitySequence = shadowPlaylist.discontinuitySequence,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                )
            }
            !primaryHasAd && primaryParsed.verdict == PlaylistVerdict.CLEAN -> {
                sourceUsed = "primary"
                window.ingest(
                    sourceId = "primary",
                    segments = primaryParsed.segments,
                    sourceMediaSequence = primaryParsed.mediaSequence,
                    sourceDiscontinuitySequence = primaryParsed.discontinuitySequence,
                    prefetches = emptyList(),
                    allowPrefetches = false,
                )
            }
            else -> {
                sourceUsed = "fallback"
            }
        }

        if (!window.isEmpty()) {
            val emitted = window.emit(primaryParsed.headerLines, primaryParsed.activeMapLine)
            return DecisionResult(emitted, sourceUsed, if (sourceUsed == "fallback") 0 else adCount)
        }

        // Empty window fallback: never return an empty playlist that would break ExoPlayer
        if (primaryParsed.verdict == PlaylistVerdict.FULL_AD && cleanShadowAvailable) {
            window.ingest(
                sourceId = "shadow@$shadowVariantUrl",
                segments = shadowPlaylist!!.segments,
                sourceMediaSequence = shadowPlaylist.mediaSequence,
                sourceDiscontinuitySequence = shadowPlaylist.discontinuitySequence,
                prefetches = emptyList(),
                allowPrefetches = false,
            )
            return DecisionResult(window.emit(primaryParsed.headerLines, shadowPlaylist.activeMapLine), "shadow-fallback", adCount)
        }
        return DecisionResult(primaryRawText, "passthrough", 0)
    }

    // -------------------------------------------------------------------------
    // Live Shadow Session Manager (Section 6 & Section 12)
    // -------------------------------------------------------------------------

    private class ChannelState(val login: String) {
        val lock = Any()
        val window = ContinuityWindow()

        @Volatile var targetHeight: Int = 2160
        @Volatile var lastPrimaryAdAtMs: Long = 0L
        @Volatile var shadowMasterVariants: List<MasterVariant> = emptyList()
        @Volatile var shadowMasterFetchedAtMs: Long = 0L
        @Volatile var shadowVariantUrl: String? = null
        @Volatile var shadowPlayerType: String? = null
        @Volatile var shadowParsed: ParsedPlaylist? = null
        @Volatile var shadowFetchedAtMs: Long = 0L
        @Volatile var consecutiveWarmFailures: Int = 0
        @Volatile var backoffUntilMs: Long = 0L
        @Volatile var lastQualityAlignMs: Long = 0L

        var warmJob: Job? = null
        var refreshJob: Job? = null
        var closeJob: Job? = null

        fun isShadowFresh(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
            shadowParsed != null && shadowVariantUrl != null && (nowMs - shadowFetchedAtMs) < SHADOW_MAX_AGE_MS

        fun invalidateShadow() {
            shadowVariantUrl = null
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
            invalidateShadow()
            shadowMasterVariants = emptyList()
            shadowMasterFetchedAtMs = 0L
            synchronized(lock) { window.reset() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channels = ConcurrentHashMap<String, ChannelState>()
    private val variantToChannel = ConcurrentHashMap<String, String>()
    private val variantToHeight = ConcurrentHashMap<String, Int>()
    @Volatile
    private var activeDisplayedChannel: String? = null

    /** Step 1 + Step 2 on TV channel zap: schedule old channel close (2 min), reset & pre-warm new channel. */
    fun onChannelNavigated(context: Context, channelLogin: String?, multiStreamTile: Boolean = false) {
        val norm = channelLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        val appCtx = context.applicationContext
        if (!multiStreamTile) {
            val prev = activeDisplayedChannel
            if (prev != null && prev != norm) {
                channels[prev]?.let { oldState ->
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
        synchronized(state.lock) {
            state.window.reset()
        }
        warmShadowAsync(appCtx, state)
    }

    /** Stops and releases all shadow sessions (e.g. when player closes or Swap+ is disabled). */
    fun stopAll() {
        activeDisplayedChannel = null
        channels.values.forEach { it.shutdown() }
        channels.clear()
        variantToChannel.clear()
        variantToHeight.clear()
    }

    private fun warmShadowAsync(appCtx: Context, state: ChannelState) {
        val now = SystemClock.elapsedRealtime()
        if (now < state.backoffUntilMs) return
        if (state.warmJob?.isActive == true) return
        state.warmJob = scope.launch {
            val ok = warmShadowNow(appCtx, state)
            if (ok) {
                state.consecutiveWarmFailures = 0
                state.backoffUntilMs = 0L
                ensureRefreshLoop(appCtx, state)
            } else {
                state.consecutiveWarmFailures++
                val shift = (state.consecutiveWarmFailures - 1).coerceIn(0, 4)
                val waitMs = (30_000L shl shift).coerceAtMost(300_000L)
                state.backoffUntilMs = SystemClock.elapsedRealtime() + waitMs
            }
        }
    }

    private suspend fun warmShadowNow(appCtx: Context, state: ChannelState): Boolean {
        val app = appCtx as? XtraApp ?: return false
        val xtraModule = app.xtraModule
        val client = buildShadowHttpClient(xtraModule.okHttpClient.value)
        val userCodecs = appCtx.prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264") ?: "av1,h265,h264"
        val codecAttempts = if (userCodecs.equals("h264", true)) listOf("h264") else listOf(userCodecs, "h264")

        for ((platform, playerType) in SHADOW_PLAYER_TYPES) {
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
                val chosen = pickShadowVariant(variants, state.targetHeight) ?: continue
                val mediaText = fetchText(client, chosen.url) ?: continue
                val parsed = parseAndClassifyMediaPlaylist(chosen.url, mediaText)
                if (parsed.verdict == PlaylistVerdict.CLEAN) {
                    state.shadowMasterVariants = variants
                    state.shadowMasterFetchedAtMs = SystemClock.elapsedRealtime()
                    state.shadowVariantUrl = chosen.url
                    state.shadowPlayerType = "$platform/$playerType"
                    state.shadowParsed = parsed
                    state.shadowFetchedAtMs = SystemClock.elapsedRealtime()
                    runCatching {
                        Log.d(TAG, "Shadow warmed for ${state.login}: ${state.shadowPlayerType} (${chosen.height}p, codecs=$codecs)")
                    }
                    return true
                }
            }
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
                val interval = if (now - state.lastPrimaryAdAtMs < SHADOW_HOT_WINDOW_MS) {
                    SHADOW_REFRESH_HOT_MS
                } else {
                    SHADOW_REFRESH_IDLE_MS
                }
                delay(interval)
                val url = state.shadowVariantUrl
                if (url == null) {
                    warmShadowAsync(appCtx, state)
                    return@launch
                }
                val text = fetchText(client, url)
                if (text == null) {
                    state.invalidateShadow()
                    warmShadowAsync(appCtx, state)
                    return@launch
                }
                val parsed = parseAndClassifyMediaPlaylist(url, text)
                if (parsed.verdict == PlaylistVerdict.CLEAN) {
                    state.shadowParsed = parsed
                    state.shadowFetchedAtMs = SystemClock.elapsedRealtime()
                } else {
                    state.invalidateShadow()
                    warmShadowAsync(appCtx, state)
                    return@launch
                }
            }
        }
    }

    private fun maybeAlignShadowQuality(appCtx: Context, state: ChannelState, newHeight: Int) {
        if (newHeight <= 0) return
        val prevTarget = state.targetHeight
        if (abs(newHeight - prevTarget) <= 100) return
        val now = SystemClock.elapsedRealtime()
        val bigJump = prevTarget > 0 && newHeight.toFloat() / prevTarget.toFloat() >= 1.5f
        if (!bigJump && now - state.lastQualityAlignMs < 30_000L) return
        state.targetHeight = newHeight
        state.lastQualityAlignMs = now

        if (now - state.shadowMasterFetchedAtMs < SHADOW_MASTER_MAX_AGE_MS && state.shadowMasterVariants.isNotEmpty()) {
            val candidate = pickShadowVariant(state.shadowMasterVariants, newHeight) ?: return
            if (candidate.url == state.shadowVariantUrl) return
            scope.launch {
                val app = appCtx as? XtraApp ?: return@launch
                val client = buildShadowHttpClient(app.xtraModule.okHttpClient.value)
                val text = fetchText(client, candidate.url) ?: return@launch
                val parsed = parseAndClassifyMediaPlaylist(candidate.url, text)
                if (parsed.verdict == PlaylistVerdict.CLEAN) {
                    state.shadowVariantUrl = candidate.url
                    state.shadowParsed = parsed
                    state.shadowFetchedAtMs = SystemClock.elapsedRealtime()
                }
            }
        } else {
            warmShadowAsync(appCtx, state)
        }
    }

    private fun pickShadowVariant(variants: List<MasterVariant>, targetHeight: Int): MasterVariant? {
        if (variants.isEmpty()) return null
        if (targetHeight <= 0) return variants.maxByOrNull { it.bandwidth }
        return variants.minWithOrNull(
            compareBy<MasterVariant> { abs(it.height - targetHeight) }
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
        Regex("""/api/channel/hls/([A-Za-z0-9_]+)\.m3u8""", RegexOption.IGNORE_CASE).find(url)?.let {
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
    ) : HlsPlaylistParserFactory {
        private val appCtx = context.applicationContext

        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
            WrappingParser(appCtx, delegate.createPlaylistParser(), channelLoginHint, onAdHandled, shouldBypass)

        override fun createPlaylistParser(
            multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?,
        ): ParsingLoadable.Parser<HlsPlaylist> =
            WrappingParser(appCtx, delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist), channelLoginHint, onAdHandled, shouldBypass)

        private class WrappingParser(
            private val appCtx: Context,
            private val inner: ParsingLoadable.Parser<HlsPlaylist>,
            private val channelLoginHint: String?,
            private val onAdHandled: ((Boolean) -> Unit)?,
            private val shouldBypass: (() -> Boolean)?,
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
                val outputText = runCatching {
                    interceptPlaylistText(appCtx, url, text, channelLoginHint, onAdHandled, shouldBypass)
                }.onFailure { err ->
                    runCatching { Log.w(TAG, "Playlist intercept failed for $url", err) }
                }.getOrDefault(text)
                return inner.parse(uri, ByteArrayInputStream(outputText.toByteArray(Charsets.UTF_8)))
            }
        }
    }

    private fun interceptPlaylistText(
        appCtx: Context,
        url: String,
        text: String,
        channelLoginHint: String?,
        onAdHandled: ((Boolean) -> Unit)?,
        shouldBypass: (() -> Boolean)?,
    ): String {
        val login = resolveChannelLogin(url, channelLoginHint) ?: return text
        if (text.contains("#EXT-X-STREAM-INF:")) {
            val (filtered, variants) = filterMasterAndExtractVariants(url, text)
            for (v in variants) {
                variantToChannel[v.url] = login
                if (v.height > 0) variantToHeight[v.url] = v.height
            }
            val state = channels.computeIfAbsent(login) { ChannelState(it) }
            state.closeJob?.cancel()
            state.closeJob = null
            warmShadowAsync(appCtx, state)
            return filtered
        }

        val state = channels.computeIfAbsent(login) { ChannelState(it) }

        // While Classic Swap / Proxy fallback is actively covering an ad break, step aside completely
        // so the hidden main player reads raw Twitch playlists and exits the backup player right on time.
        if (shouldBypass?.invoke() == true) {
            synchronized(state.lock) {
                if (!state.window.isEmpty()) {
                    state.window.reset()
                }
            }
            onAdHandled?.invoke(false)
            return text
        }

        val variantHeight = variantToHeight[url] ?: 0
        if (variantHeight > 0) {
            maybeAlignShadowQuality(appCtx, state, variantHeight)
        }

        val parsed = parseAndClassifyMediaPlaylist(url, text)
        val hasAd = parsed.verdict == PlaylistVerdict.FULL_AD || parsed.verdict == PlaylistVerdict.PARTIAL_AD
        if (hasAd) {
            state.lastPrimaryAdAtMs = SystemClock.elapsedRealtime()
            if (!state.isShadowFresh()) {
                warmShadowAsync(appCtx, state)
                val waitBudget = if (!state.window.hasSeenContent) PREROLL_WAIT_MS else MIDROLL_WAIT_MS
                runBlocking {
                    withTimeoutOrNull(waitBudget) {
                        state.warmJob?.join()
                    }
                }
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
            )
        }
        val adBadgeActive = result.sourceUsed == "shadow" ||
            result.sourceUsed == "shadow-cooldown" ||
            result.sourceUsed == "shadow-fallback" ||
            result.sourceUsed == "hold" ||
            (hasAd && result.sourceUsed != "passthrough")
        onAdHandled?.invoke(adBadgeActive)
        if (hasAd) {
            runCatching {
                Log.i(TAG, "Ad break on $login: source=${result.sourceUsed} suppressed=${result.adSegmentsSuppressed} shadow=${state.shadowPlayerType} splices=${state.window.spliceCount}")
            }
        }
        return result.outputPlaylist
    }
}
