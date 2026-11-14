package com.github.andreyasadchy.xtra.ui.tv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HlsPlaylistParser
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.view.TvUi
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Experimental TV Adblock: plays live broadcasts via Twitch's ongoing CDN VOD (`index-dvr.m3u8`)
 * ~30s (`32_000L` ms) behind the latest recorded VOD segment, eliminating server-side live ad
 * injections and VAFT swaps with zero background live stream running.
 */
object TvExperimentalAdblock {

    /** Target offset behind the end of `index-dvr.m3u8` (~3 ten-second VOD segments of CDN margin). */
    const val TARGET_OFFSET_MS = 32_000L
    const val MIN_OFFSET_MS = 22_000L
    const val MAX_OFFSET_MS = 48_000L

    /** Minimum recorded VOD duration required to start directly on VOD at stream open. */
    const val MIN_INITIAL_VOD_DURATION_MS = 12_000L

    /** Minimum recorded VOD duration required before seamlessly upgrading a brand-new live from Swap (VAFT) to VOD. */
    const val UPGRADE_MIN_VOD_DURATION_MS = 35_000L

    /** Maximum stream uptime considered "brand-new" (~3 minutes) eligible for background VOD upgrade polling. */
    const val NEW_STREAM_MAX_UPTIME_MS = 180_000L
    const val UPGRADE_POLL_INTERVAL_MS = 10_000L
    const val UPGRADE_MAX_ATTEMPTS = 18

    /** Sliding window kept per Multi-Stream / PiP player (~3 minutes of 10s segments) to avoid parsing multi-hour playlists on every reload. */
    private const val SLIDING_WINDOW_SEGMENTS = 18
    private const val PROBE_RANGE_BYTES = 2048

    data class LiveVodOutcome(
        val vod: LiveVodResult?,
        val streamId: String?,
        val createdAtIso: String?,
        val createdAtMs: Long?,
        val streamUptimeMs: Long?,
        val channelIsLive: Boolean,
    )

    data class LiveVodResult(
        val masterUri: Uri,
        val chunkedUrl: String,
        val baseDir: String,
        val streamId: String,
        val createdAtIso: String,
        val createdAtMs: Long,
        val vodDurationMs: Long,
    )

    private data class TranscodeSpec(
        val folder: String,
        val name: String,
        val bandwidth: Int,
        val resolution: String?,
        val frameRate: String?,
    )

    private data class CachedVod(
        val baseDir: String,
        val variants: List<TranscodeSpec>,
        val createdAtIso: String,
        val createdAtMs: Long,
    )

    private val transcodeSpecs = listOf(
        TranscodeSpec("1440p60", "1440p60", 8_500_000, "2560x1440", "60.000"),
        TranscodeSpec("1440p30", "1440p30", 6_500_000, "2560x1440", "30.000"),
        TranscodeSpec("1080p60", "1080p60", 6_200_000, "1920x1080", "60.000"),
        TranscodeSpec("1080p30", "1080p30", 4_500_000, "1920x1080", "30.000"),
        TranscodeSpec("720p60", "720p60", 3_400_000, "1280x720", "60.000"),
        TranscodeSpec("720p30", "720p30", 2_370_000, "1280x720", "30.000"),
        TranscodeSpec("480p30", "480p30", 1_420_000, "852x480", "30.000"),
        TranscodeSpec("360p30", "360p30", 630_000, "640x360", "30.000"),
        TranscodeSpec("160p30", "160p30", 230_000, "284x160", "30.000"),
        TranscodeSpec("audio_only", "Audio Only", 160_000, null, null),
    )

    private val cachedVods = ConcurrentHashMap<String, CachedVod>()

    fun isAnyProxyEnabled(context: Context): Boolean {
        val prefs = context.prefs()
        return prefs.getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false) ||
            prefs.getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
    }

    fun isExperimentalEnabled(context: Context): Boolean {
        if (!TvUi.isTv(context)) return false
        val prefs = context.prefs()
        return prefs.getBoolean(C.TV_EXPERIMENTAL_ADBLOCK, false) && !isAnyProxyEnabled(context)
    }

    /**
     * On TV, Swap Adblock (`player_adblock`) is always active and locked ON unless a Custom Proxy
     * or HTTP Stream Proxy is enabled (including when Experimental Adblock is ON, where Swap acts
     * as the automatic fallback for streams without VODs or brand-new streams).
     */
    fun isSwapAdblockEnabled(context: Context): Boolean {
        val prefs = context.prefs()
        return if (TvUi.isTv(context) && !isAnyProxyEnabled(context)) {
            true
        } else {
            prefs.getBoolean(C.PLAYER_ADBLOCK, true)
        }
    }

    /** Enforces the 3-state mutual exclusion rules in SharedPreferences on TV. */
    fun normalizePrefs(context: Context) {
        if (!TvUi.isTv(context)) return
        val prefs = context.prefs()
        val exp = prefs.getBoolean(C.TV_EXPERIMENTAL_ADBLOCK, false)
        val customProxy = prefs.getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
        val streamProxy = prefs.getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
        val swap = prefs.getBoolean(C.PLAYER_ADBLOCK, true)
        if (exp && (customProxy || streamProxy || !swap)) {
            prefs.edit {
                putBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
                putBoolean(C.PLAYER_USE_STREAM_PROXY, false)
                putBoolean(C.PLAYER_ADBLOCK, true)
            }
        } else if (!customProxy && !streamProxy && !swap) {
            prefs.edit {
                putBoolean(C.PLAYER_ADBLOCK, true)
            }
        }
    }

    fun liveConfiguration(): MediaItem.LiveConfiguration =
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(TARGET_OFFSET_MS)
            .setMinOffsetMs(MIN_OFFSET_MS)
            .setMaxOffsetMs(MAX_OFFSET_MS)
            .setMinPlaybackSpeed(1.0f)
            .setMaxPlaybackSpeed(1.0f)
            .build()

    @OptIn(UnstableApi::class)
    fun buildMediaSource(
        context: Context,
        xtraModule: XtraModule,
        uri: Uri,
        slidingWindowOnly: Boolean = false,
        createdAtMs: Long? = null,
    ): HlsMediaSource {
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val httpFactory = when {
            networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                HttpEngineDataSource.Factory(
                    xtraModule.httpEngine.value,
                    xtraModule.cronetExecutor.value,
                    null,
                    0,
                    false,
                    false,
                    null,
                    null,
                    null
                ) { false }
            }
            networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                CronetDataSource.Factory(
                    xtraModule.cronetEngine.value,
                    xtraModule.cronetExecutor.value,
                    null,
                    0,
                    false,
                    false,
                    null,
                    null,
                    null
                ) { false }
            }
            else -> {
                OkHttpDataSource.Factory(
                    xtraModule.okHttpClient.value,
                    null,
                    null,
                    null,
                    null
                ) { false }
            }
        }
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(liveConfiguration())
            .build()
        return HlsMediaSource.Factory(DefaultDataSource.Factory(context, httpFactory))
            .setPlaylistParserFactory(PlaylistParserFactory(slidingWindowOnly, createdAtMs))
            .createMediaSource(mediaItem)
    }

    /**
     * Strips `#EXT-X-PLAYLIST-TYPE:EVENT` and `#EXT-X-START` while `#EXT-X-ENDLIST` is absent so Media3
     * treats Twitch's growing `index-dvr.m3u8` as an active live DVR window (`0 .. duration`) positioned
     * at `duration - TARGET_OFFSET_MS` instead of starting at `00:00:00`, while naturally ending once
     * `#EXT-X-ENDLIST` appears when the streamer goes offline.
     *
     * When [slidingWindowOnly] is enabled (Multi-Stream & PiP, where timeline rewind is not used),
     * keeps only the latest [SLIDING_WINDOW_SEGMENTS] segments and advances `#EXT-X-MEDIA-SEQUENCE`
     * so 4 concurrent multi-hour streams never allocate or parse thousands of segments every few seconds.
     */
    @OptIn(UnstableApi::class)
    class PlaylistParserFactory(
        private val slidingWindowOnly: Boolean = false,
        private val createdAtMs: Long? = null,
    ) : HlsPlaylistParserFactory {
        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
            WrappingParser(HlsPlaylistParser(), slidingWindowOnly, createdAtMs)

        override fun createPlaylistParser(
            multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?,
        ): ParsingLoadable.Parser<HlsPlaylist> =
            WrappingParser(HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist), slidingWindowOnly, createdAtMs)

        private data class SegmentBlock(
            val lines: List<String>,
            val durationUs: Long,
            val hasDiscontinuity: Boolean,
            val hasProgramDateTime: Boolean,
            val firstKeyLine: String?,
            val lastKeyLine: String?,
            val firstMapLine: String?,
            val lastMapLine: String?,
        )

        private class WrappingParser(
            private val inner: HlsPlaylistParser,
            private val slidingWindowOnly: Boolean,
            private val createdAtMs: Long?,
        ) : ParsingLoadable.Parser<HlsPlaylist> {
            override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
                val rawBytes = inputStream.readBytes()
                if (rawBytes.isEmpty()) {
                    return inner.parse(uri, ByteArrayInputStream(rawBytes))
                }
                if (slidingWindowOnly) {
                    val trimmed = trimToSlidingWindow(rawBytes, createdAtMs)
                    if (trimmed != null) {
                        return inner.parse(uri, ByteArrayInputStream(trimmed))
                    }
                }
                // Full-window mode (full-screen player with DVR rewind): avoid copying the segment body
                val tailLen = minOf(rawBytes.size, 256)
                val tailStr = String(rawBytes, rawBytes.size - tailLen, tailLen, Charsets.UTF_8)
                if (tailStr.contains("#EXT-X-ENDLIST")) {
                    return inner.parse(uri, ByteArrayInputStream(rawBytes))
                }
                val scanLen = minOf(rawBytes.size, 4096)
                val headPreview = String(rawBytes, 0, scanLen, Charsets.UTF_8)
                val extInfCharIdx = headPreview.indexOf("#EXTINF:")
                if (extInfCharIdx > 0) {
                    val headerText = headPreview.substring(0, extInfCharIdx)
                    val headerByteLen = headerText.toByteArray(Charsets.UTF_8).size
                    if (headerByteLen <= rawBytes.size) {
                        val filteredHeader = buildString(headerText.length) {
                            for (line in headerText.lineSequence()) {
                                val trimmed = line.trimStart()
                                if (trimmed.startsWith("#EXT-X-PLAYLIST-TYPE") || trimmed.startsWith("#EXT-X-START")) {
                                    continue
                                }
                                append(line)
                                append('\n')
                            }
                        }.toByteArray(Charsets.UTF_8)
                        val combined = SequenceInputStream(
                            ByteArrayInputStream(filteredHeader),
                            ByteArrayInputStream(rawBytes, headerByteLen, rawBytes.size - headerByteLen),
                        )
                        return inner.parse(uri, combined)
                    }
                }
                // Fallback for short or non-standard playlists
                val fullText = String(rawBytes, Charsets.UTF_8)
                val normalized = buildString(fullText.length) {
                    for (line in fullText.lineSequence()) {
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("#EXT-X-PLAYLIST-TYPE") || trimmed.startsWith("#EXT-X-START")) {
                            continue
                        }
                        append(line)
                        append('\n')
                    }
                }
                return inner.parse(uri, ByteArrayInputStream(normalized.toByteArray(Charsets.UTF_8)))
            }

            private fun isSegmentScopedTag(trimmed: String): Boolean =
                trimmed.startsWith("#EXTINF:") ||
                    trimmed.startsWith("#EXT-X-DISCONTINUITY") && !trimmed.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") ||
                    trimmed.startsWith("#EXT-X-PROGRAM-DATE-TIME:") ||
                    trimmed.startsWith("#EXT-X-KEY:") ||
                    trimmed.startsWith("#EXT-X-MAP:") ||
                    trimmed.startsWith("#EXT-X-BYTERANGE:")

            private fun trimToSlidingWindow(rawBytes: ByteArray, streamCreatedAtMs: Long?): ByteArray? {
                val headCheckLen = minOf(rawBytes.size, 2048)
                val headCheck = String(rawBytes, 0, headCheckLen, Charsets.UTF_8)
                if (headCheck.contains("#EXT-X-STREAM-INF:")) {
                    // Multivariant master playlist: leave untouched
                    return null
                }
                val headerLines = ArrayList<String>(12)
                val ring = ArrayDeque<SegmentBlock>(SLIDING_WINDOW_SEGMENTS + 1)
                var baseMediaSeq = 0L
                var baseDiscSeq = 0L
                var droppedCount = 0L
                var droppedDurationUs = 0L
                var droppedDiscontinuities = 0L
                var activeKeyLine: String? = null
                var activeMapLine: String? = null
                var hasEndList = false
                var inSegments = false

                var curLines = ArrayList<String>(4)
                var curDurationUs = 0L
                var curHasDisc = false
                var curHasPdt = false
                var curFirstKey: String? = null
                var curLastKey: String? = null
                var curFirstMap: String? = null
                var curLastMap: String? = null

                BufferedReader(InputStreamReader(ByteArrayInputStream(rawBytes), Charsets.UTF_8), 8192).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val trimmed = line.trimStart()
                        if (trimmed.isEmpty()) continue
                        if (trimmed.startsWith("#EXT-X-ENDLIST")) {
                            hasEndList = true
                            continue
                        }
                        if (!inSegments && !isSegmentScopedTag(trimmed)) {
                            when {
                                trimmed.startsWith("#EXT-X-PLAYLIST-TYPE") || trimmed.startsWith("#EXT-X-START") -> continue
                                trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                                    baseMediaSeq = trimmed.removePrefix("#EXT-X-MEDIA-SEQUENCE:").trim().toLongOrNull() ?: 0L
                                }
                                trimmed.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") -> {
                                    baseDiscSeq = trimmed.removePrefix("#EXT-X-DISCONTINUITY-SEQUENCE:").trim().toLongOrNull() ?: 0L
                                }
                                else -> headerLines.add(line)
                            }
                            continue
                        }
                        inSegments = true
                        if (trimmed.startsWith("#")) {
                            curLines.add(line)
                            when {
                                trimmed.startsWith("#EXTINF:") -> {
                                    val secs = trimmed.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
                                    if (secs != null && secs > 0.0) {
                                        curDurationUs += (secs * 1_000_000.0).roundToLong()
                                    }
                                }
                                trimmed.startsWith("#EXT-X-DISCONTINUITY") && !trimmed.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> {
                                    curHasDisc = true
                                }
                                trimmed.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> {
                                    curHasPdt = true
                                }
                                trimmed.startsWith("#EXT-X-KEY:") -> {
                                    if (curFirstKey == null) curFirstKey = line
                                    curLastKey = line
                                }
                                trimmed.startsWith("#EXT-X-MAP:") -> {
                                    if (curFirstMap == null) curFirstMap = line
                                    curLastMap = line
                                }
                            }
                        } else {
                            curLines.add(line)
                            val block = SegmentBlock(
                                lines = curLines,
                                durationUs = curDurationUs,
                                hasDiscontinuity = curHasDisc,
                                hasProgramDateTime = curHasPdt,
                                firstKeyLine = curFirstKey,
                                lastKeyLine = curLastKey,
                                firstMapLine = curFirstMap,
                                lastMapLine = curLastMap,
                            )
                            if (ring.size >= SLIDING_WINDOW_SEGMENTS) {
                                val evicted = ring.removeFirst()
                                droppedCount++
                                droppedDurationUs += evicted.durationUs
                                if (evicted.hasDiscontinuity) droppedDiscontinuities++
                                if (evicted.lastKeyLine != null) activeKeyLine = evicted.lastKeyLine
                                if (evicted.lastMapLine != null) activeMapLine = evicted.lastMapLine
                            }
                            ring.addLast(block)
                            curLines = ArrayList(4)
                            curDurationUs = 0L
                            curHasDisc = false
                            curHasPdt = false
                            curFirstKey = null
                            curLastKey = null
                            curFirstMap = null
                            curLastMap = null
                        }
                    }
                }

                if (ring.isEmpty()) {
                    return null
                }

                val out = StringBuilder(2048)
                for (h in headerLines) {
                    out.append(h).append('\n')
                }
                out.append("#EXT-X-MEDIA-SEQUENCE:").append(baseMediaSeq + droppedCount).append('\n')
                val totalDiscSeq = baseDiscSeq + droppedDiscontinuities
                if (totalDiscSeq > 0L) {
                    out.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(totalDiscSeq).append('\n')
                }
                val firstKept = ring.first()
                if (activeKeyLine != null && firstKept.firstKeyLine == null) {
                    out.append(activeKeyLine).append('\n')
                }
                if (activeMapLine != null && firstKept.firstMapLine == null) {
                    out.append(activeMapLine).append('\n')
                }
                if (streamCreatedAtMs != null && streamCreatedAtMs > 0L && !firstKept.hasProgramDateTime) {
                    val firstSegEpochMs = streamCreatedAtMs + (droppedDurationUs / 1_000L)
                    out.append("#EXT-X-PROGRAM-DATE-TIME:").append(Instant.fromEpochMilliseconds(firstSegEpochMs)).append('\n')
                }
                for (seg in ring) {
                    for (l in seg.lines) {
                        out.append(l).append('\n')
                    }
                }
                if (hasEndList) {
                    out.append("#EXT-X-ENDLIST\n")
                }
                return out.toString().toByteArray(Charsets.UTF_8)
            }
        }
    }

    /**
     * Resolves the streamer's ongoing `index-dvr.m3u8` on Twitch's VOD CDN and generates a local
     * multivariant master playlist (`file://.../tv_exp_vod/master_<login>_<streamId>.m3u8`) containing
     * `Source` (`chunked`) plus all active transcode folders (`1080p60`, `720p60`, ..., `audio_only`).
     */
    suspend fun resolveLiveVod(
        context: Context,
        xtraModule: XtraModule,
        channelLogin: String,
        streamIdHint: String? = null,
        createdAtHint: String? = null,
        fallbackH264: Boolean = false,
    ): LiveVodOutcome = withContext(Dispatchers.IO) {
        val login = channelLogin.trim().lowercase()
        if (login.isEmpty()) {
            return@withContext LiveVodOutcome(null, null, null, null, null, false)
        }
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)

        var streamId = streamIdHint?.takeIf { it.isNotBlank() }
        var createdAtIso = createdAtHint?.takeIf { it.isNotBlank() }
        var createdAtMs = createdAtIso?.let { parseEpochMs(it) }

        if (streamId == null || createdAtMs == null) {
            val fetched = fetchLiveStreamMeta(context, xtraModule, networkLibrary, login)
                ?: return@withContext LiveVodOutcome(null, null, null, null, null, false)
            streamId = fetched.first
            createdAtIso = fetched.second
            createdAtMs = fetched.third
        }

        val resolvedStreamId = streamId
        val resolvedCreatedAtIso = createdAtIso ?: ""
        val resolvedCreatedAtMs = createdAtMs
        val uptimeMs = (System.currentTimeMillis() - resolvedCreatedAtMs).coerceAtLeast(0L)
        val cacheKey = "${login}_${resolvedStreamId}"

        // Fast path: reuse cached CDN baseDir & variants for this streamId if already discovered
        cachedVods[cacheKey]?.let { cached ->
            val chunkedUrl = "${cached.baseDir}/chunked/index-dvr.m3u8"
            val playlistSnippet = fetchPlaylistIfValid(xtraModule, networkLibrary, chunkedUrl, headerOnly = true)
            if (playlistSnippet != null) {
                val durationMs = parseVodDurationMs(playlistSnippet)
                val filteredVariants = selectVariants(cached.variants, fallbackH264)
                    ?: return@withContext LiveVodOutcome(null, resolvedStreamId, resolvedCreatedAtIso, resolvedCreatedAtMs, uptimeMs, true)
                val masterUri = writeMasterPlaylist(context, login, resolvedStreamId, cached.baseDir, filteredVariants, fallbackH264)
                return@withContext LiveVodOutcome(
                    vod = LiveVodResult(
                        masterUri = masterUri,
                        chunkedUrl = chunkedUrl,
                        baseDir = cached.baseDir,
                        streamId = resolvedStreamId,
                        createdAtIso = resolvedCreatedAtIso,
                        createdAtMs = resolvedCreatedAtMs,
                        vodDurationMs = durationMs,
                    ),
                    streamId = resolvedStreamId,
                    createdAtIso = resolvedCreatedAtIso,
                    createdAtMs = resolvedCreatedAtMs,
                    streamUptimeMs = uptimeMs,
                    channelIsLive = true,
                )
            } else {
                cachedVods.remove(cacheKey)
            }
        }

        val createdAtSeconds = resolvedCreatedAtMs / 1000L
        // Probe primary offsets (0, -1) first; only try wider clock-skew offsets (1, -2, 2) if needed
        val found = probeDomainsForOffsets(xtraModule, networkLibrary, login, resolvedStreamId, createdAtSeconds, listOf(0L, -1L))
            ?: probeDomainsForOffsets(xtraModule, networkLibrary, login, resolvedStreamId, createdAtSeconds, listOf(1L, -2L, 2L))

        if (found == null) {
            return@withContext LiveVodOutcome(
                vod = null,
                streamId = resolvedStreamId,
                createdAtIso = resolvedCreatedAtIso,
                createdAtMs = resolvedCreatedAtMs,
                streamUptimeMs = uptimeMs,
                channelIsLive = true,
            )
        }

        val (baseDir, chunkedSnippet) = found
        val durationMs = parseVodDurationMs(chunkedSnippet)
        val variants = probeAvailableVariants(xtraModule, networkLibrary, baseDir)
        cachedVods[cacheKey] = CachedVod(baseDir, variants, resolvedCreatedAtIso, resolvedCreatedAtMs)
        if (cachedVods.size > 32) {
            cachedVods.keys.firstOrNull { it != cacheKey }?.let { cachedVods.remove(it) }
        }

        val filteredVariants = selectVariants(variants, fallbackH264)
            ?: return@withContext LiveVodOutcome(null, resolvedStreamId, resolvedCreatedAtIso, resolvedCreatedAtMs, uptimeMs, true)
        val masterUri = writeMasterPlaylist(context, login, resolvedStreamId, baseDir, filteredVariants, fallbackH264)
        LiveVodOutcome(
            vod = LiveVodResult(
                masterUri = masterUri,
                chunkedUrl = "$baseDir/chunked/index-dvr.m3u8",
                baseDir = baseDir,
                streamId = resolvedStreamId,
                createdAtIso = resolvedCreatedAtIso,
                createdAtMs = resolvedCreatedAtMs,
                vodDurationMs = durationMs,
            ),
            streamId = resolvedStreamId,
            createdAtIso = resolvedCreatedAtIso,
            createdAtMs = resolvedCreatedAtMs,
            streamUptimeMs = uptimeMs,
            channelIsLive = true,
        )
    }

    fun parseEpochMs(iso: String?): Long? =
        iso?.takeIf { it.isNotBlank() }?.let {
            Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0L }
        }

    private fun parseVodDurationMs(playlistText: String): Long {
        var totalTagSeconds = 0.0
        var extInfSeconds = 0.0
        for (line in playlistText.lineSequence()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#EXT-X-TWITCH-TOTAL-SECS:")) {
                val totalVal = trimmed.removePrefix("#EXT-X-TWITCH-TOTAL-SECS:").trim().toDoubleOrNull()
                if (totalVal != null && totalVal > 0.0) {
                    totalTagSeconds = totalVal
                }
            } else if (trimmed.startsWith("#EXTINF:")) {
                val value = trimmed.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
                if (value != null && value > 0.0) {
                    extInfSeconds += value
                }
            }
        }
        return (maxOf(totalTagSeconds, extInfSeconds) * 1000.0).toLong()
    }

    private suspend fun fetchLiveStreamMeta(
        context: Context,
        xtraModule: XtraModule,
        networkLibrary: String?,
        login: String,
    ): Triple<String, String, Long>? {
        try {
            val gqlHeaders = TwitchApiHelper.getGQLHeaders(context)
            val response = xtraModule.graphQLRepository.loadQueryUsersStream(
                networkLibrary = networkLibrary,
                headers = gqlHeaders,
                logins = listOf(login),
            )
            val stream = response.data?.users?.firstOrNull()?.stream
            val id = stream?.id?.takeIf { it.isNotBlank() }
            val createdAt = stream?.createdAt?.toString()?.takeIf { it.isNotBlank() }
            val ms = createdAt?.let { parseEpochMs(it) }
            if (id != null && createdAt != null && ms != null) {
                return Triple(id, createdAt, ms)
            }
            if (response.data?.users != null && stream == null) {
                // Channel exists and is currently offline
                return null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        try {
            val helixHeaders = TwitchApiHelper.getHelixHeaders(context)
            if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                val item = xtraModule.helixRepository.getStreams(
                    networkLibrary = networkLibrary,
                    headers = helixHeaders,
                    logins = listOf(login),
                ).data.firstOrNull()
                val id = item?.id?.takeIf { it.isNotBlank() }
                val startedAt = item?.startedAt?.takeIf { it.isNotBlank() }
                val ms = startedAt?.let { parseEpochMs(it) }
                if (id != null && startedAt != null && ms != null) {
                    return Triple(id, startedAt, ms)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return null
    }

    private suspend fun probeDomainsForOffsets(
        xtraModule: XtraModule,
        networkLibrary: String?,
        login: String,
        streamId: String,
        createdAtSeconds: Long,
        offsets: List<Long>,
    ): Pair<String, String>? = coroutineScope {
        val semaphore = Semaphore(12)
        val found = MutableStateFlow<Pair<String, String>?>(null)
        val jobs = mutableListOf<Job>()
        val domains = TwitchApiHelper.vodDomains.reversed()

        for (offset in offsets) {
            if (found.value != null) break
            val slug = "${login}_${streamId}_${createdAtSeconds + offset}"
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(slug.toByteArray(Charsets.UTF_8))
            val hash = digest.digest().toHexString().take(20)

            for (domain in domains) {
                semaphore.acquire()
                if (found.value != null) {
                    semaphore.release()
                    break
                }
                val baseDir = "${domain}/${hash}_${slug}"
                val url = "$baseDir/chunked/index-dvr.m3u8"
                jobs.add(
                    launch(Dispatchers.IO) {
                        try {
                            val body = fetchPlaylistIfValid(xtraModule, networkLibrary, url, headerOnly = true)
                            if (body != null && found.compareAndSet(null, baseDir to body)) {
                                jobs.forEach { it.cancel() }
                            }
                        } catch (e: CancellationException) {
                            ensureActive()
                        } catch (_: Exception) {
                        }
                    }.also { job ->
                        job.invokeOnCompletion { semaphore.release() }
                    }
                )
            }
        }
        jobs.joinAll()
        found.value
    }

    private suspend fun probeAvailableVariants(
        xtraModule: XtraModule,
        networkLibrary: String?,
        baseDir: String,
    ): List<TranscodeSpec> = coroutineScope {
        val deferreds = transcodeSpecs.map { spec ->
            async(Dispatchers.IO) {
                val url = "$baseDir/${spec.folder}/index-dvr.m3u8"
                if (fetchPlaylistIfValid(xtraModule, networkLibrary, url, headerOnly = true) != null) spec else null
            }
        }
        val available = deferreds.awaitAll().filterNotNull()
        val has1440 = available.any { it.folder.startsWith("1440p") }
        val has1080 = available.any { it.folder.startsWith("1080p") }
        val sourceRes = when {
            has1440 -> "3840x2160"
            has1080 -> "2560x1440"
            else -> "1920x1080"
        }
        val sourceSpec = TranscodeSpec("chunked", "Source", 9_500_000, sourceRes, "60.000")
        listOf(sourceSpec) + available
    }

    private fun selectVariants(variants: List<TranscodeSpec>, fallbackH264: Boolean): List<TranscodeSpec>? {
        if (!fallbackH264) return variants
        val h264Safe = variants.filter { it.folder != "chunked" && !it.folder.startsWith("1440p") }
        return h264Safe.takeIf { list -> list.any { it.folder != "audio_only" } }
    }

    private fun writeMasterPlaylist(
        context: Context,
        login: String,
        streamId: String,
        baseDir: String,
        variants: List<TranscodeSpec>,
        fallbackH264: Boolean = false,
    ): Uri {
        val dir = File(context.cacheDir, "tv_exp_vod").apply { mkdirs() }
        // Prune stale master playlists older than 12 hours
        val cutoff = System.currentTimeMillis() - 12 * 3_600_000L
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
        val safeLogin = login.replace(Regex("[^a-z0-9_]"), "_")
        val safeStreamId = streamId.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val suffix = if (fallbackH264) "_h264" else ""
        val file = File(dir, "master_${safeLogin}_${safeStreamId}${suffix}.m3u8")
        val content = buildString {
            append("#EXTM3U\n")
            variants.forEachIndexed { index, spec ->
                val mediaUrl = "$baseDir/${spec.folder}/index-dvr.m3u8"
                append("#EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID=\"${spec.folder}\",NAME=\"${spec.name}\",AUTOSELECT=YES,DEFAULT=${if (index == 0) "YES" else "NO"}\n")
                append("#EXT-X-STREAM-INF:BANDWIDTH=${spec.bandwidth}")
                if (spec.resolution != null) {
                    append(",RESOLUTION=${spec.resolution}")
                }
                if (spec.frameRate != null) {
                    append(",FRAME-RATE=${spec.frameRate}")
                }
                append(",VIDEO=\"${spec.folder}\"\n")
                append(mediaUrl).append('\n')
            }
        }
        file.writeText(content, Charsets.UTF_8)
        return Uri.fromFile(file)
    }

    private suspend fun fetchPlaylistIfValid(
        xtraModule: XtraModule,
        networkLibrary: String?,
        url: String,
        headerOnly: Boolean = false,
    ): String? = withTimeoutOrNull(4_000L) {
        val rangeHeader = "bytes=0-${PROBE_RANGE_BYTES - 1}"
        try {
            when {
                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.HttpEngineTimeout()
                        val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                            url,
                            xtraModule.cronetExecutor.value,
                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                        ).apply {
                            if (headerOnly) {
                                addHeader("Range", rangeHeader)
                            }
                        }.build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    if (response.info.httpStatusCode in 200..299) {
                        val bytes = response.body
                        val len = if (headerOnly) minOf(bytes.size, PROBE_RANGE_BYTES) else bytes.size
                        String(bytes, 0, len, Charsets.UTF_8).takeIf { it.contains("#EXTM3U") }
                    } else null
                }
                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.CronetTimeout()
                        val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                            url,
                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                            xtraModule.cronetExecutor.value
                        ).apply {
                            if (headerOnly) {
                                addHeader("Range", rangeHeader)
                            }
                        }.build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    if (response.info.httpStatusCode in 200..299) {
                        val bytes = response.body
                        val len = if (headerOnly) minOf(bytes.size, PROBE_RANGE_BYTES) else bytes.size
                        String(bytes, 0, len, Charsets.UTF_8).takeIf { it.contains("#EXTM3U") }
                    } else null
                }
                else -> {
                    val request = Request.Builder().url(url).apply {
                        if (headerOnly) {
                            header("Range", rangeHeader)
                        }
                    }.build()
                    xtraModule.okHttpClient.value.newCall(request).executeAsync().use { response ->
                        if (response.isSuccessful) {
                            val text = if (headerOnly) {
                                val buf = ByteArray(PROBE_RANGE_BYTES)
                                val stream = response.body.byteStream()
                                var total = 0
                                while (total < buf.size) {
                                    val n = stream.read(buf, total, buf.size - total)
                                    if (n <= 0) break
                                    total += n
                                }
                                String(buf, 0, total, Charsets.UTF_8)
                            } else {
                                response.body.string()
                            }
                            text.takeIf { it.contains("#EXTM3U") }
                        } else null
                    }
                }
            }
        } catch (e: CancellationException) {
            ensureActive()
            null
        } catch (_: Exception) {
            null
        }
    }
}
