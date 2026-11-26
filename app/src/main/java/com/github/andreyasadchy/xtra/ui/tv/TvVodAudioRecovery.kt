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
    private const val PROBE_TIMEOUT_MS = 2_000L
    private const val MIN_VALID_CONTENT_LENGTH = 512L
    private const val MERGE_GAP_MS = 500L

    private val SEGMENT_REGEX = Regex("""^(.*\/)?(\d+)-(?:un)?muted\.(mp4|m4s|ts)(\?.*)?$""", RegexOption.IGNORE_CASE)

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
        fun probe(unmutedUrl: String, plainUrl: String): SegmentMode
    }

    internal data class ParsedMutedSegment(
        val lineIndex: Int,
        val prefix: String,
        val number: String,
        val ext: String,
        val query: String,
        val startMs: Long,
        val endMs: Long,
    ) {
        val segmentKey: String get() = "$prefix$number.$ext"

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

    private val probeExecutor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "XtraVodAudioProbe").apply { isDaemon = true }
    }

    fun isEnabled(context: Context): Boolean =
        context.prefs().getBoolean(C.TV_VOD_AUDIO_RECOVERY, true)

    fun isLivePlaylistUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("usher.ttvnw.net/") || lower.contains("playlist.ttvnw.net/")
    }

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
        val probeClient = baseClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return SegmentProber { unmutedUrl, plainUrl ->
            probeInParallel(probeClient, unmutedUrl, plainUrl)
        }
    }

    private fun probeInParallel(client: OkHttpClient, unmutedUrl: String, plainUrl: String): SegmentMode {
        val fUnmuted = probeExecutor.submit(Callable { probeSingleUrl(client, unmutedUrl) })
        val fPlain = probeExecutor.submit(Callable { probeSingleUrl(client, plainUrl) })
        val unmutedOk = runCatching { fUnmuted.get(PROBE_TIMEOUT_MS + 300L, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (unmutedOk) {
            fPlain.cancel(true)
            return SegmentMode.UNMUTED
        }
        val plainOk = runCatching { fPlain.get(PROBE_TIMEOUT_MS + 300L, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        return if (plainOk) SegmentMode.PLAIN else SegmentMode.MUTED
    }

    private fun probeSingleUrl(client: OkHttpClient, url: String): Boolean {
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .get()
                .build()
        )
        return try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return false
                val lenHeader = resp.header("Content-Length")?.toLongOrNull()
                val valid = lenHeader == null || lenHeader > MIN_VALID_CONTENT_LENGTH
                // Cancel immediately after headers so we never download the media body
                call.cancel()
                valid
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pure, unit-testable VOD playlist rewriter.
     * Detects `-muted.` / `-unmuted.` segments, probes representative segments per muted range (start, mid, end),
     * rewrites segment URIs while preserving prefix/query/line-endings, populates [Session.fallbackByRewrittenUrl],
     * and updates merged real `#EXTINF` ranges on [session].
     */
    fun rewriteMediaPlaylist(
        playlistUrl: String,
        playlistText: String,
        session: Session,
        prober: SegmentProber,
    ): String {
        if (isLivePlaylistUrl(playlistUrl)) return playlistText
        if (playlistText.contains("#EXT-X-STREAM-INF:")) return playlistText
        if (!playlistText.contains("-muted.", ignoreCase = true) &&
            !playlistText.contains("-unmuted.", ignoreCase = true)
        ) {
            session.updateSegments(emptyList(), session.enabledProvider())
            return playlistText
        }

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
                    val mode = runCatching { prober.probe(unmutedAbs, plainAbs) }.getOrDefault(SegmentMode.MUTED)
                    session.segmentModes[rep.segmentKey] = mode
                    rep to mode
                }
                // Choose best working mode found in this range: prefer UNMUTED, then PLAIN, else MUTED
                val rangeMode = when {
                    repModes.any { it.second == SegmentMode.UNMUTED } -> SegmentMode.UNMUTED
                    repModes.any { it.second == SegmentMode.PLAIN } -> SegmentMode.PLAIN
                    else -> SegmentMode.MUTED
                }
                for (seg in unresolved) {
                    session.segmentModes.putIfAbsent(seg.segmentKey, rangeMode)
                }
            }
        }

        var recoveredCount = 0
        var mutedCount = 0
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
                val rewrittenAbs = resolveAbsoluteUrl(playlistUrl, rewrittenLine)
                val mutedAbs = resolveAbsoluteUrl(playlistUrl, seg.urlFor(SegmentMode.MUTED))
                session.fallbackByRewrittenUrl[rewrittenAbs] = FallbackEntry(seg.segmentKey, mutedAbs)
            } else {
                mutedCount++
            }
        }

        session.updateSegments(parsedSegments, enabled)
        runCatching {
            Log.d(TAG, "Rewrote VOD playlist: recovered=$recoveredCount muted=$mutedCount enabled=$enabled")
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
                        Log.d(TAG, "Segment fallback ($code) -> muted for ${entry.segmentKey}")
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
