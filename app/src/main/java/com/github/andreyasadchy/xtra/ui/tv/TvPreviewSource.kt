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
        "web" to "embed",
        "web" to "popout",
        "web" to "site",
    )

    /** A list that plays previews: they are all stopped when a player opens. */
    fun interface Previewer {
        fun stopPreview()
    }

    private val previewers = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Previewer, Boolean>())

    fun register(previewer: Previewer) {
        previewers.add(previewer)
    }

    /** True while a player or the multi-stream is on screen: the lists behind it play no preview. */
    var suspended = false
        set(value) {
            field = value
            if (value) {
                previewers.toList().forEach { it.stopPreview() }
            }
        }

    /** Told when a preview with sound starts and stops, the stream kept in the background is silenced meanwhile. */
    var onPreviewSound: ((Boolean) -> Unit)? = null

    fun notifyPreviewSound(context: Context, playing: Boolean) {
        if (hasSound(context)) {
            onPreviewSound?.invoke(playing)
        }
    }

    /** Delay between the focus on a card and the start of its preview. */
    const val START_DELAY = 100L

    /** Player of the previews: 360p for a fast start, with the sound when the option is on. */
    fun createPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).build().also {
            it.volume = if (context.prefs().getBoolean(C.UI_TV_PREVIEW_SOUND, false)) 1f else 0f
            it.repeatMode = Player.REPEAT_MODE_OFF
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(640, 360)
                .build()
        }
    }

    fun hasSound(context: Context) = context.prefs().getBoolean(C.UI_TV_PREVIEW_SOUND, false)

    suspend fun getPlaylistUrl(context: Context, channelLogin: String): String? {
        val xtraModule = (context.applicationContext as XtraApp).xtraModule
        val prefs = context.prefs()
        val adblock = prefs.getBoolean(C.PLAYER_ADBLOCK, true)
        val types = if (adblock) PLAYER_TYPES else listOf((prefs.getString(C.TOKEN_PLATFORM, "web") ?: "web") to (prefs.getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"))
        var fallback: String? = null
        for ((platform, playerType) in types) {
            val url = try {
                xtraModule.playerRepository.loadStreamPlaylistUrl(
                    context = context,
                    networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = if (platform == "web") {
                        TwitchApiHelper.getGQLWebHeaders(context, prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true))
                    } else {
                        TwitchApiHelper.getGQLHeaders(context, prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true))
                    },
                    channelLogin = channelLogin,
                    platform = platform,
                    playerType = playerType,
                    supportedCodecs = "h264",
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
            } ?: continue
            if (!adblock) {
                return url
            }
            if (fallback == null) {
                fallback = url
            }
            if (!hasAds(xtraModule.okHttpClient.value, url)) {
                return url
            }
        }
        return fallback
    }

    /** Looks at the media playlist of the lowest quality for the marks of an ad (VAFT does the same). */
    private suspend fun hasAds(client: okhttp3.OkHttpClient, multivariantUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val multivariant = client.newCall(Request.Builder().url(multivariantUrl).build()).execute().use { it.body.string() }
            val mediaUrl = multivariant.lineSequence().lastOrNull { it.startsWith("http") } ?: return@withContext false
            val media = client.newCall(Request.Builder().url(mediaUrl).build()).execute().use { it.body.string() }
            media.contains("stitched-ad", true) || media.contains("X-TV-TWITCH-AD", true) || media.contains("twitch-stitched-ad", true)
        } catch (e: Exception) {
            false
        }
    }
}
