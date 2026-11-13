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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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
            .setPlaylistParserFactory(PlaylistParserFactory())
            .createMediaSource(mediaItem)
    }

    /**
     * Strips `#EXT-X-PLAYLIST-TYPE:EVENT` and `#EXT-X-START` while `#EXT-X-ENDLIST` is absent so Media3
     * treats Twitch's growing `index-dvr.m3u8` as an active live DVR window (`0 .. duration`) positioned
     * at `duration - TARGET_OFFSET_MS` instead of starting at `00:00:00`, while naturally ending once
     * `#EXT-X-ENDLIST` appears when the streamer goes offline.
     */
    @OptIn(UnstableApi::class)
    class PlaylistParserFactory : HlsPlaylistParserFactory {
        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
            WrappingParser(HlsPlaylistParser())

        override fun createPlaylistParser(
            multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?,
        ): ParsingLoadable.Parser<HlsPlaylist> =
            WrappingParser(HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

        private class WrappingParser(
            private val inner: HlsPlaylistParser,
        ) : ParsingLoadable.Parser<HlsPlaylist> {
            override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
                val text = inputStream.bufferedReader(Charsets.UTF_8).readText()
                val normalized = if (!text.contains("#EXT-X-ENDLIST")) {
                    buildString(text.length) {
                        for (line in text.lineSequence()) {
                            val trimmed = line.trimStart()
                            if (trimmed.startsWith("#EXT-X-PLAYLIST-TYPE") || trimmed.startsWith("#EXT-X-START")) {
                                continue
                            }
                            append(line)
                            append('\n')
                        }
                    }
                } else {
                    text
                }
                return inner.parse(uri, ByteArrayInputStream(normalized.toByteArray(Charsets.UTF_8)))
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
            val playlistText = fetchPlaylistIfValid(xtraModule, networkLibrary, chunkedUrl)
            if (playlistText != null) {
                val durationMs = parseExtInfDurationMs(playlistText)
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

        val (baseDir, chunkedText) = found
        val durationMs = parseExtInfDurationMs(chunkedText)
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

    private fun parseExtInfDurationMs(playlistText: String): Long {
        var totalSeconds = 0.0
        for (line in playlistText.lineSequence()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#EXTINF:")) {
                val value = trimmed.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
                if (value != null && value > 0.0) {
                    totalSeconds += value
                }
            }
        }
        return (totalSeconds * 1000.0).toLong()
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
                            val body = fetchPlaylistIfValid(xtraModule, networkLibrary, url)
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
                if (fetchPlaylistIfValid(xtraModule, networkLibrary, url) != null) spec else null
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
    ): String? = withTimeoutOrNull(4_000L) {
        try {
            when {
                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.HttpEngineTimeout()
                        val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                            url,
                            xtraModule.cronetExecutor.value,
                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    if (response.info.httpStatusCode in 200..299) {
                        response.body.decodeToString().takeIf { it.contains("#EXTM3U") }
                    } else null
                }
                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.CronetTimeout()
                        val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                            url,
                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                            xtraModule.cronetExecutor.value
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    if (response.info.httpStatusCode in 200..299) {
                        response.body.decodeToString().takeIf { it.contains("#EXTM3U") }
                    } else null
                }
                else -> {
                    xtraModule.okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                        if (response.isSuccessful) {
                            response.body.string().takeIf { it.contains("#EXTM3U") }
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
