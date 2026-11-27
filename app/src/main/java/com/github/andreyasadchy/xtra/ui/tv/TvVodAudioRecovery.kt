package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Recovers DMCA-muted Twitch VOD audio segments across classic VODs, live DVR rewind, and Adblock (VOD)
 * when the unmuted original (`N-unmuted.<ext>` or `N.<ext>`) is still cached on CloudFront.
 *
 * Strictly ignores live streams (`usher.ttvnw.net` / `playlist.ttvnw.net`).
 * Includes per-range probing (start/mid/end), per-segment permanent fallback memory on 403/404/410,
 * real `#EXTINF` duration range tracking, and live green/red seekbar state updates.
 */
object TvVodAudioRecovery {

    private const val TAG = "TvVodAudioRecovery"
    private const val PROBE_TIMEOUT_MS = 4_000L
    private const val MIN_VALID_CONTENT_LENGTH = 512L
    private const val MERGE_GAP_MS = 500L

    private val SEGMENT_REGEX = Regex("""^(.*\/)?(\d+)-(?:un)?muted\.(mp4|m4s|ts)(\?.*)?$""", RegexOption.IGNORE_CASE)
    private val CONTENT_RANGE_TOTAL_REGEX = Regex("""/(\d+)$""")

    enum class SegmentMode {
        UNMUTED,
        PLAIN,
        MUTED,
    }

    data class AudioRange(
        val startMs: Long,
        val endMs: Long,
        val recovered: Boolean,
    )

    data class VodAudioState(
        val sessionId: Long,
        val recoveredRanges: List<Pair<Long, Long>>,
        val mutedRanges: List<Pair<Long, Long>>,
        val recoveredDurationMs: Long,
    ) {
        fun statusAt(positionMs: Long): Boolean? {
            if (recoveredRanges.any { (s, e) -> positionMs in s..e }) return true
            if (mutedRanges.any { (s, e) -> positionMs in s..e }) return false
            return null
        }
    }

    fun interface SegmentProber {
        fun probe(unmutedUrl: String, plainUrl: String, mutedUrl: String, durationMs: Long): SegmentMode
    }

    internal data class ProbeMeta(
        val ok: Boolean,
        val httpCode: Int = 0,
        val contentLength: Long? = null,
        val etag: String? = null,
    )

    internal fun isUsableProbe(candidate: ProbeMeta): Boolean =
        candidate.ok && (candidate.contentLength == null || candidate.contentLength > MIN_VALID_CONTENT_LENGTH)

    /** Prioritizes `N-unmuted.<ext>` over plain `N.<ext>`; a 403/404 on plain never causes abandonment. */
    internal fun evaluateProbeCandidates(
        unmuted: ProbeMeta,
        plain: ProbeMeta,
    ): SegmentMode = when {
        isUsableProbe(unmuted) -> SegmentMode.UNMUTED
        isUsableProbe(plain) -> SegmentMode.PLAIN
        else -> SegmentMode.MUTED
    }

    internal data class ParsedMutedSegment(
        val lineIndex: Int,
        val baseDir: String,
        val prefix: String,
        val number: String,
        val ext: String,
        val query: String,
        val startMs: Long,
        val endMs: Long,
    ) {
        val segmentKey: String get() = "$baseDir/$prefix$number.$ext"

        fun urlFor(mode: SegmentMode): String = when (mode) {
            SegmentMode.UNMUTED -> "$prefix$number-unmuted.$ext$query"
            SegmentMode.PLAIN -> "$prefix$number.$ext$query"
            SegmentMode.MUTED -> "$prefix$number-muted.$ext$query"
        }
    }

    internal data class FallbackEntry(
        val segmentKey: String,
        val mutedAbsoluteUrl: String,
    )

    /** Per-playback session state (shared between playlist rewriter and segment DataSource fallback). */
    class Session(
        val sessionId: Long,
        val enabledProvider: () -> Boolean,
        val publishToUi: Boolean = true,
    ) {
        internal val segmentModes = ConcurrentHashMap<String, SegmentMode>()
        internal val failedToMuted = ConcurrentHashMap.newKeySet<String>()
        internal val fallbackByRewrittenUrl = ConcurrentHashMap<String, FallbackEntry>()
        @Volatile
        internal var lastSegments: List<ParsedMutedSegment> = emptyList()
        @Volatile
        var state: VodAudioState = VodAudioState(sessionId, emptyList(), emptyList(), 0L)
            private set

        internal fun markSegmentFailed(segmentKey: String) {
            failedToMuted.add(segmentKey)
            segmentModes[segmentKey] = SegmentMode.MUTED
            recomputeState()
        }

        internal fun updateSegments(segments: List<ParsedMutedSegment>, enabled: Boolean) {
            lastSegments = segments
            recomputeState(enabled)
        }

        internal fun recomputeState(enabled: Boolean = enabledProvider()) {
            val segments = lastSegments
            if (segments.isEmpty()) {
                val empty = VodAudioState(sessionId, emptyList(), emptyList(), 0L)
                state = empty
                if (publishToUi) _activeState.value = empty
                return
            }
            val recoveredRaw = ArrayList<Pair<Long, Long>>()
            val mutedRaw = ArrayList<Pair<Long, Long>>()
            for (seg in segments) {
                val isRecovered = enabled &&
                    !failedToMuted.contains(seg.segmentKey) &&
                    (segmentModes[seg.segmentKey] ?: SegmentMode.MUTED) != SegmentMode.MUTED
                if (isRecovered) {
                    recoveredRaw.add(seg.startMs to seg.endMs)
                } else {
                    mutedRaw.add(seg.startMs to seg.endMs)
                }
            }
            val recoveredMerged = mergeRanges(recoveredRaw)
            val mutedMerged = mergeRanges(mutedRaw)
            val recoveredDurationMs = recoveredMerged.sumOf { (s, e) -> (e - s).coerceAtLeast(0L) }
            val next = VodAudioState(
                sessionId = sessionId,
                recoveredRanges = recoveredMerged,
                mutedRanges = mutedMerged,
                recoveredDurationMs = recoveredDurationMs,
            )
            state = next
            if (publishToUi) {
                _activeState.value = next
            }
        }
    }

    private val nextSessionId = java.util.concurrent.atomic.AtomicLong(1L)
    private val _activeState = MutableStateFlow<VodAudioState?>(null)
    val activeState: StateFlow<VodAudioState?> get() = _activeState

    private val probeExecutor = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "XtraVodAudioProbe").apply { isDaemon = true }
    }

    fun isEnabled(context: Context): Boolean =
        context.prefs().getBoolean(C.TV_VOD_AUDIO_RECOVERY, false)

    fun isLivePlaylistUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("/api/channel/hls/") ||
            lower.contains("/api/v2/channel/hls/") ||
            lower.contains("playlist.ttvnw.net/") ||
            lower.contains("video-weaver.") ||
            lower.contains(".hls.ttvnw.net/")
    }

    private fun isLivePlaylistText(text: String): Boolean =
        text.contains("#EXT-X-TWITCH-LIVE-SEQUENCE", ignoreCase = true) ||
            text.contains("#EXT-X-TWITCH-PREFETCH:", ignoreCase = true)

    fun createSession(context: Context, publishToUi: Boolean = true): Session {
        val appCtx = context.applicationContext
        val id = nextSessionId.getAndIncrement()
        val session = Session(
            sessionId = id,
            enabledProvider = { isEnabled(appCtx) },
            publishToUi = publishToUi,
        )
        if (publishToUi) {
            _activeState.value = VodAudioState(id, emptyList(), emptyList(), 0L)
        }
        return session
    }

    fun clearActiveState() {
        _activeState.value = null
    }

    fun createOkHttpProber(baseClient: OkHttpClient): SegmentProber {
        // Strip any debug BODY logging interceptors so probes never buffer multi-MB media payloads
        val probeClient = baseClient.newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return SegmentProber { unmutedUrl, plainUrl, _, _ ->
            probeInParallel(probeClient, unmutedUrl, plainUrl)
        }
    }

    private fun probeInParallel(
        client: OkHttpClient,
        unmutedUrl: String,
        plainUrl: String,
    ): SegmentMode {
        val fUnmuted = probeExecutor.submit(Callable { probeSingleUrlMeta(client, unmutedUrl) })
        val fPlain = probeExecutor.submit(Callable { probeSingleUrlMeta(client, plainUrl) })
        val unmutedMeta = runCatching { fUnmuted.get(PROBE_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS) }
            .getOrDefault(ProbeMeta(ok = false, httpCode = -1))
        val plainMeta = runCatching { fPlain.get(PROBE_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS) }
            .getOrDefault(ProbeMeta(ok = false, httpCode = -1))
        val mode = evaluateProbeCandidates(unmutedMeta, plainMeta)
        runCatching {
            Log.i(
                TAG,
                "Probe -> $mode | unmuted=[$unmutedUrl http=${unmutedMeta.httpCode} size=${unmutedMeta.contentLength}] " +
                    "plain=[$plainUrl http=${plainMeta.httpCode} size=${plainMeta.contentLength}]",
            )
        }
        return mode
    }

    private fun probeSingleUrlMeta(client: OkHttpClient, url: String): ProbeMeta {
        // HEAD first (zero payload); fall back to 1-byte Range GET on HTTP 405
        val headResult = runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                if (resp.code == 405) return@use null
                val len = resp.header("Content-Length")?.toLongOrNull()
                if (!resp.isSuccessful) {
                    return@use ProbeMeta(ok = false, httpCode = resp.code, contentLength = len)
                }
                ProbeMeta(
                    ok = true,
                    httpCode = resp.code,
                    contentLength = len,
                    etag = resp.header("ETag")?.trim(),
                )
            }
        }.getOrNull()
        if (headResult != null) return headResult

        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val contentRange = resp.header("Content-Range")
                val totalSize = contentRange?.let { CONTENT_RANGE_TOTAL_REGEX.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    ?: resp.header("Content-Length")?.toLongOrNull()
                if (!resp.isSuccessful) {
                    return@use ProbeMeta(ok = false, httpCode = resp.code, contentLength = totalSize)
                }
                ProbeMeta(
                    ok = true,
                    httpCode = resp.code,
                    contentLength = totalSize,
                    etag = resp.header("ETag")?.trim(),
                )
            }
        }.getOrDefault(ProbeMeta(ok = false, httpCode = -1))
    }

    /**
     * Pure, unit-testable VOD playlist rewriter.
     * Detects `-muted.` / `-unmuted.` segments (`.mp4`, `.m4s`, `.ts`, with or without query),
     * probes representative segments per muted range (start, mid, end) with `UNMUTED` prioritized over `PLAIN`,
     * rewrites segment URIs while preserving prefix/query/line-endings, populates [Session.fallbackByRewrittenUrl],
     * and updates merged real `#EXTINF` ranges on [session].
     */
    fun rewriteMediaPlaylist(
        playlistUrl: String,
        playlistText: String,
        session: Session,
        prober: SegmentProber,
    ): String {
        if (isLivePlaylistUrl(playlistUrl) || isLivePlaylistText(playlistText)) return playlistText
        if (playlistText.contains("#EXT-X-STREAM-INF:")) return playlistText
        if (!playlistText.contains("-muted.", ignoreCase = true) &&
            !playlistText.contains("-unmuted.", ignoreCase = true)
        ) {
            session.updateSegments(emptyList(), session.enabledProvider())
            return playlistText
        }

        val baseDir = playlistUrl.substringBefore('?').substringBeforeLast('/')
        val lineEnding = if (playlistText.contains("\r\n")) "\r\n" else "\n"
        val rawLines = playlistText.split("\r\n", "\n").toMutableList()
        val parsedSegments = ArrayList<ParsedMutedSegment>()

        var elapsedMs = 0L
        var pendingDurMs = 0L
        for (idx in rawLines.indices) {
            val trimmed = rawLines[idx].trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXTINF:")) {
                    val secs = trimmed.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
                    if (secs != null && secs > 0.0) {
                        pendingDurMs = (secs * 1_000.0).roundToLong()
                    }
                }
                continue
            }
            val startMs = elapsedMs
            val durMs = if (pendingDurMs > 0L) pendingDurMs else 10_000L
            val endMs = startMs + durMs
            elapsedMs = endMs
            pendingDurMs = 0L

            val match = SEGMENT_REGEX.matchEntire(trimmed) ?: continue
            parsedSegments.add(
                ParsedMutedSegment(
                    lineIndex = idx,
                    baseDir = baseDir,
                    prefix = match.groupValues[1],
                    number = match.groupValues[2],
                    ext = match.groupValues[3],
                    query = match.groupValues[4],
                    startMs = startMs,
                    endMs = endMs,
                )
            )
        }

        if (parsedSegments.isEmpty()) {
            session.updateSegments(emptyList(), session.enabledProvider())
            return playlistText
        }

        val enabled = session.enabledProvider()
        if (enabled) {
            // Group contiguous muted segments (gap <= MERGE_GAP_MS) into ranges so we probe start/mid/end per range
            val ranges = ArrayList<MutableList<ParsedMutedSegment>>()
            for (seg in parsedSegments) {
                val last = ranges.lastOrNull()
                if (last != null && seg.startMs - last.last().endMs <= MERGE_GAP_MS) {
                    last.add(seg)
                } else {
                    ranges.add(mutableListOf(seg))
                }
            }

            // Collect representative probe targets for ranges that still contain unresolved segments
            val probeTasks = ArrayList<Pair<List<ParsedMutedSegment>, List<ParsedMutedSegment>>>()
            for (range in ranges) {
                val unresolved = range.filter { !session.failedToMuted.contains(it.segmentKey) && !session.segmentModes.containsKey(it.segmentKey) }
                if (unresolved.isEmpty()) continue
                val reps = linkedSetOf(
                    unresolved.first(),
                    unresolved[unresolved.size / 2],
                    unresolved.last(),
                ).toList()
                probeTasks.add(unresolved to reps)
            }

            for ((unresolved, reps) in probeTasks) {
                val repModes = reps.map { rep ->
                    val unmutedAbs = resolveAbsoluteUrl(playlistUrl, rep.urlFor(SegmentMode.UNMUTED))
                    val plainAbs = resolveAbsoluteUrl(playlistUrl, rep.urlFor(SegmentMode.PLAIN))
                    val mutedAbs = resolveAbsoluteUrl(playlistUrl, rep.urlFor(SegmentMode.MUTED))
                    val durMs = (rep.endMs - rep.startMs).coerceAtLeast(1_000L)
                    val mode = runCatching { prober.probe(unmutedAbs, plainAbs, mutedAbs, durMs) }
                        .onFailure { err ->
                            runCatching { Log.w(TAG, "VOD audio probe exception for $unmutedAbs", err) }
                        }
                        .getOrDefault(SegmentMode.MUTED)
                    session.segmentModes[rep.segmentKey] = mode
                    rep to mode
                }
                // Require majority confirmation (e.g. >= 2/3 when 3 probed) so a single cached edge segment
                // does not mark an entire muted range green only to fall back to silent -muted during playback.
                val needed = (repModes.size + 1) / 2
                val unmutedHits = repModes.count { it.second == SegmentMode.UNMUTED }
                val plainHits = repModes.count { it.second == SegmentMode.PLAIN }
                val rangeMode = when {
                    unmutedHits >= needed -> SegmentMode.UNMUTED
                    plainHits >= needed -> SegmentMode.PLAIN
                    unmutedHits + plainHits >= needed -> if (unmutedHits >= plainHits) SegmentMode.UNMUTED else SegmentMode.PLAIN
                    else -> SegmentMode.MUTED
                }
                for (seg in unresolved) {
                    session.segmentModes.putIfAbsent(seg.segmentKey, rangeMode)
                }
            }
        }

        var recoveredCount = 0
        var unmutedCount = 0
        var plainCount = 0
        var mutedCount = 0
        val firstThreeRewritten = ArrayList<String>(3)
        for (seg in parsedSegments) {
            val chosenMode = if (!enabled || session.failedToMuted.contains(seg.segmentKey)) {
                SegmentMode.MUTED
            } else {
                session.segmentModes[seg.segmentKey] ?: SegmentMode.MUTED
            }
            val rewrittenLine = seg.urlFor(chosenMode)
            rawLines[seg.lineIndex] = rewrittenLine
            if (chosenMode != SegmentMode.MUTED) {
                recoveredCount++
                if (chosenMode == SegmentMode.UNMUTED) unmutedCount++ else plainCount++
                val rewrittenAbs = resolveAbsoluteUrl(playlistUrl, rewrittenLine)
                val mutedAbs = resolveAbsoluteUrl(playlistUrl, seg.urlFor(SegmentMode.MUTED))
                session.fallbackByRewrittenUrl[rewrittenAbs] = FallbackEntry(seg.segmentKey, mutedAbs)
                if (firstThreeRewritten.size < 3) {
                    firstThreeRewritten.add(rewrittenAbs)
                }
            } else {
                mutedCount++
            }
        }

        session.updateSegments(parsedSegments, enabled)
        runCatching {
            val modeSummary = when {
                unmutedCount > 0 && plainCount == 0 -> "unmuted"
                plainCount > 0 && unmutedCount == 0 -> "plain"
                unmutedCount > 0 && plainCount > 0 -> "mixed(unmuted=$unmutedCount,plain=$plainCount)"
                else -> "muted"
            }
            Log.i(
                TAG,
                "VOD playlist rewritten ($playlistUrl): detected=${parsedSegments.size} mode=$modeSummary " +
                    "recovered=$recoveredCount (unmuted=$unmutedCount plain=$plainCount) muted=$mutedCount enabled=$enabled " +
                    "first3=${firstThreeRewritten.joinToString(", ")}",
            )
        }
        return rawLines.joinToString(lineEnding)
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

    internal fun mergeRanges(raw: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sortedBy { it.first }
        val merged = ArrayList<Pair<Long, Long>>(sorted.size)
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (i in 1 until sorted.size) {
            val (s, e) = sorted[i]
            if (s <= curEnd + MERGE_GAP_MS) {
                if (e > curEnd) curEnd = e
            } else {
                merged.add(curStart to curEnd)
                curStart = s
                curEnd = e
            }
        }
        merged.add(curStart to curEnd)
        return merged
    }

    /** Wraps any [HlsPlaylistParserFactory] to rewrite muted VOD playlists transparently. */
    @OptIn(UnstableApi::class)
    class PlaylistParserFactory(
        private val delegate: HlsPlaylistParserFactory,
        private val session: Session,
        private val prober: SegmentProber,
    ) : HlsPlaylistParserFactory {
        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
            WrappingParser(delegate.createPlaylistParser(), session, prober)

        override fun createPlaylistParser(
            multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?,
        ): ParsingLoadable.Parser<HlsPlaylist> =
            WrappingParser(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist), session, prober)

        private class WrappingParser(
            private val inner: ParsingLoadable.Parser<HlsPlaylist>,
            private val session: Session,
            private val prober: SegmentProber,
        ) : ParsingLoadable.Parser<HlsPlaylist> {
            override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
                val url = uri.toString()
                if (isLivePlaylistUrl(url)) {
                    return inner.parse(uri, inputStream)
                }
                val rawBytes = inputStream.readBytes()
                if (rawBytes.isEmpty()) {
                    return inner.parse(uri, ByteArrayInputStream(rawBytes))
                }
                val text = String(rawBytes, Charsets.UTF_8)
                val rewritten = runCatching {
                    rewriteMediaPlaylist(url, text, session, prober)
                }.onFailure { err ->
                    runCatching { Log.w(TAG, "VOD playlist rewrite failed for $url", err) }
                }.getOrDefault(text)
                return inner.parse(uri, ByteArrayInputStream(rewritten.toByteArray(Charsets.UTF_8)))
            }
        }
    }

    /**
     * Wraps a segment [DataSource.Factory] so any rewritten segment returning HTTP 403/404/410
     * transparently retries once with its `-muted.` fallback URL, permanently remembers that segment
     * as muted for future seeks, and immediately turns its seekbar range red.
     */
    @OptIn(UnstableApi::class)
    class FallbackDataSourceFactory(
        private val upstreamFactory: DataSource.Factory,
        private val session: Session,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = FallbackDataSource(upstreamFactory.createDataSource(), session)
    }

    @OptIn(UnstableApi::class)
    private class FallbackDataSource(
        private val upstream: DataSource,
        private val session: Session,
    ) : DataSource {

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val reqUrl = dataSpec.uri.toString()
            val entry = session.fallbackByRewrittenUrl[reqUrl]
            if (entry != null && session.failedToMuted.contains(entry.segmentKey)) {
                val mutedSpec = dataSpec.buildUpon().setUri(Uri.parse(entry.mutedAbsoluteUrl)).build()
                return upstream.open(mutedSpec)
            }
            return try {
                upstream.open(dataSpec)
            } catch (e: IOException) {
                val code = extractHttpStatusCode(e)
                if (entry != null && (code == 403 || code == 404 || code == 410)) {
                    runCatching { upstream.close() }
                    session.markSegmentFailed(entry.segmentKey)
                    runCatching {
                        Log.w(TAG, "VOD segment fallback (HTTP $code): $reqUrl -> ${entry.mutedAbsoluteUrl}")
                    }
                    val mutedSpec = dataSpec.buildUpon().setUri(Uri.parse(entry.mutedAbsoluteUrl)).build()
                    upstream.open(mutedSpec)
                } else {
                    throw e
                }
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() {
            upstream.close()
        }

        private fun extractHttpStatusCode(error: Throwable): Int {
            var cur: Throwable? = error
            while (cur != null) {
                if (cur is HttpDataSource.InvalidResponseCodeException) {
                    return cur.responseCode
                }
                cur = cur.cause
            }
            return 0
        }
    }
}
