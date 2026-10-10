package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Playlist of the muted live previews: like the VAFT userscript, the player types that get fewer ads are tried first
 * and the first one without an ad playing is used.
 */
object TvPreviewSource {

    private val PLAYER_TYPES = listOf(
        "web" to "embed",
        "web" to "popout",
        "android" to "autoplay",
        "web" to "site",
    )

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
