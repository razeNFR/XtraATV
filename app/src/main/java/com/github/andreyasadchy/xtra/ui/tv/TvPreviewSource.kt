package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Playlist of the live previews: like the VAFT userscript, the player types that get fewer ads are tried first
 * and the first one without an ad playing is used.
 */
object TvPreviewSource {

    // autoplay first: the player type of the previews on the website, it starts fast in low quality
    private val PLAYER_TYPES = listOf(
        "android" to "autoplay",
        "web" to "picture-by-picture",
        "web" to "embed",
        "web" to "thunderdome",
        "web" to "popout",
        "web" to "site",
    )

    data class BackupPlaylist(
        val url: String,
        val adFree: Boolean,
    )

    /** A list that plays previews: they are all released when a player opens or another screen previews. */
    interface Previewer {
        fun stopPreview()
        fun releasePreview() = stopPreview()
    }

    private val previewers = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Previewer, Boolean>())

    fun register(previewer: Previewer) {
        previewers.add(previewer)
    }

    /** Releases preview ExoPlayers held by other browsing screens so at most one preview player stays in RAM. */
    fun releaseOthers(active: Previewer) {
        previewers.toList().forEach {
            if (it !== active) {
                it.releasePreview()
            }
        }
    }

    /** Releases all preview ExoPlayers immediately (e.g. when entering a real player or backgrounding the app). */
    fun releaseAll() {
        previewers.toList().forEach { it.releasePreview() }
    }

    /** True while a player or the multi-stream is on screen: the lists behind it play no preview. */
    var suspended = false
        set(value) {
            field = value
            if (value) {
                releaseAll()
            }
        }

    /** Told when a preview with sound starts and stops, the stream kept in the background is silenced meanwhile. */
    var onPreviewSound: ((Boolean) -> Unit)? = null

    fun notifyPreviewSound(context: Context, playing: Boolean) {
        if (hasSound(context)) {
            onPreviewSound?.invoke(playing)
        }
    }

    /** Default delay between the focus on a card and the start of its preview (and card expansion). */
    const val START_DELAY = 250L

    fun startDelayMs(context: Context): Long =
        (context.prefs().getString(C.TV_PREVIEW_DELAY, "250")?.toLongOrNull() ?: START_DELAY)
            .coerceIn(250L, 10_000L)

    /** Player of the previews: 360p for a fast start, with the sound when the option is on. */
    fun createPlayer(context: Context): ExoPlayer {
        val wantSound = hasSound(context)
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderersFactory)
            .setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build().also {
                it.volume = if (wantSound) 1f else 0f
                it.repeatMode = Player.REPEAT_MODE_OFF
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(640, 360)
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, !wantSound)
                    .build()
            }
    }

    fun hasSound(context: Context) = context.prefs().getBoolean(C.UI_TV_PREVIEW_SOUND, false)

    private suspend fun resolveSwapTypes(context: Context, includeSiteTail: Boolean): List<Pair<String, String>> {
        val xtraModule = (context.applicationContext as XtraApp).xtraModule
        val configured = runCatching {
            xtraModule.playerRepository.getVideoSwapItems(context)
                .filter { it.enabled && !it.platform.isNullOrBlank() && !it.playerType.isNullOrBlank() }
                .sortedBy { it.position }
                .map { it.platform!! to it.playerType!! }
        }.getOrDefault(emptyList())
        val base = (configured + com.github.andreyasadchy.xtra.repository.PlayerRepository.DEFAULT_VIDEO_SWAP).distinct()
        return if (includeSiteTail) (base + ("web" to "site")).distinct() else base
    }

    private suspend fun fetchStreamCandidateUrl(
        context: Context,
        channelLogin: String,
        platform: String,
        playerType: String,
        supportedCodecs: String?,
        includeAccountToken: Boolean,
    ): String? {
        val xtraModule = (context.applicationContext as XtraApp).xtraModule
        val prefs = context.prefs()
        return try {
            xtraModule.playerRepository.loadStreamPlaylistUrl(
                context = context,
                networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = if (platform == "web") {
                    TwitchApiHelper.getGQLWebHeaders(context, includeAccountToken)
                } else {
                    TwitchApiHelper.getGQLHeaders(context, includeAccountToken)
                },
                channelLogin = channelLogin,
                platform = platform,
                playerType = playerType,
                supportedCodecs = supportedCodecs,
                proxyPlaybackAccessToken = false,
                proxyHost = null,
                proxyPort = null,
                proxyUser = null,
                proxyPassword = null,
                proxyTimeout = null,
                enableIntegrity = false,
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getPlaylistUrl(context: Context, channelLogin: String): String? {
        val app = context.applicationContext as XtraApp
        val xtraModule = app.xtraModule
        val prefs = context.prefs()
        if (TvExperimentalAdblock.isAnyProxyEnabled(context)) {
            runCatching {
                com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.loadStreamPlaylistUrl(app, xtraModule, channelLogin)
            }.getOrNull()?.let { return it }
        }
        val adblock = TvExperimentalAdblock.isSwapAdblockEnabled(context)
        if (!adblock) {
            val platform = prefs.getString(C.TOKEN_PLATFORM, "web") ?: "web"
            val playerType = prefs.getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
            val includeToken = prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)
            return fetchStreamCandidateUrl(context, channelLogin, platform, playerType, "h264", includeToken)
        }
        val types = resolveSwapTypes(context, includeSiteTail = true)
        var fallback: String? = null
        // Fetch Video Swap candidates anonymously so Twitch does not stitch account-targeted ads onto every backup
        for ((platform, playerType) in types) {
            val url = fetchStreamCandidateUrl(
                context = context,
                channelLogin = channelLogin,
                platform = platform,
                playerType = playerType,
                supportedCodecs = "h264",
                includeAccountToken = false,
            ) ?: continue
            if (fallback == null) {
                fallback = url
            }
            if (!hasAds(xtraModule.okHttpClient.value, url)) {
                return url
            }
        }
        // Subscriber-only / restricted broadcast fallback if anonymous requests returned nothing
        if (fallback == null && prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)) {
            for ((platform, playerType) in types) {
                val url = fetchStreamCandidateUrl(
                    context = context,
                    channelLogin = channelLogin,
                    platform = platform,
                    playerType = playerType,
                    supportedCodecs = "h264",
                    includeAccountToken = true,
                ) ?: continue
                if (fallback == null) {
                    fallback = url
                }
                if (!hasAds(xtraModule.okHttpClient.value, url)) {
                    return url
                }
            }
        }
        return fallback
    }

    /**
     * Multi-stream: playlist of the backup player types while an ad plays on the normal one, autoplay first like the
     * video swap of the player (their qualities are limited, it is only for the time of the ad).
     * Requested anonymously (`includeAccountToken = false`) so Twitch does not stitch account-targeted ads onto backups.
     */
    suspend fun findBackupPlaylist(context: Context, channelLogin: String): BackupPlaylist? {
        val xtraModule = (context.applicationContext as XtraApp).xtraModule
        val prefs = context.prefs()
        val codecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")
        val types = resolveSwapTypes(context, includeSiteTail = false)
        var fallback: String? = null
        for ((platform, playerType) in types) {
            val url = fetchStreamCandidateUrl(
                context = context,
                channelLogin = channelLogin,
                platform = platform,
                playerType = playerType,
                supportedCodecs = codecs,
                includeAccountToken = false,
            ) ?: continue
            if (fallback == null) {
                fallback = url
            }
            if (!hasAds(xtraModule.okHttpClient.value, url)) {
                return BackupPlaylist(url = url, adFree = true)
            }
        }
        if (fallback == null && prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)) {
            for ((platform, playerType) in types) {
                val url = fetchStreamCandidateUrl(
                    context = context,
                    channelLogin = channelLogin,
                    platform = platform,
                    playerType = playerType,
                    supportedCodecs = codecs,
                    includeAccountToken = true,
                ) ?: continue
                if (fallback == null) {
                    fallback = url
                }
                if (!hasAds(xtraModule.okHttpClient.value, url)) {
                    return BackupPlaylist(url = url, adFree = true)
                }
            }
        }
        return fallback?.let { BackupPlaylist(url = it, adFree = false) }
    }

    suspend fun getBackupPlaylistUrl(context: Context, channelLogin: String): String? {
        return findBackupPlaylist(context, channelLogin)?.url
    }

    /** True when the stream of this playlist plays an ad now. */
    suspend fun playlistHasAds(context: Context, multivariantUrl: String): Boolean {
        return hasAds((context.applicationContext as XtraApp).xtraModule.okHttpClient.value, multivariantUrl)
    }

    /** Looks at the media playlist of the lowest quality for the marks of an ad (VAFT does the same). */
    private suspend fun hasAds(client: okhttp3.OkHttpClient, multivariantUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val multivariant = client.newCall(Request.Builder().url(multivariantUrl).build()).execute().use { it.body.string() }
            val mediaUrl = multivariant.lineSequence().lastOrNull { it.startsWith("http") } ?: return@withContext false
            val media = client.newCall(Request.Builder().url(mediaUrl).build()).execute().use { it.body.string() }
            media.contains("stitched-ad", true) ||
                media.contains("X-TV-TWITCH-AD", true) ||
                media.contains("twitch-stitched-ad", true) ||
                media.contains("COMMERCIAL-BREAK", true)
        } catch (e: Exception) {
            false
        }
    }
}
