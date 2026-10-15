package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.os.SystemClock
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * TV: the access of Twitch to a stream is asked as soon as OK is pressed on it, while the player opens, instead of
 * once the player is ready. Only the plain request is done here: the player uses it only when it would do that same
 * request (no proxy, not a restart of the ad blocker), else it asks itself as before.
 */
object TvStreamPrefetch {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var login: String? = null
    private var request: Deferred<String?>? = null
    private var startedAt = 0L

    fun start(context: Context, channelLogin: String?) {
        if (channelLogin.isNullOrBlank() || !context.resources.getBoolean(com.github.andreyasadchy.xtra.R.bool.isTv)) {
            return
        }
        val prefs = context.prefs()
        // the integrity check has its own flow, the stream proxies their own access: left to the player
        if (prefs.getBoolean(C.ENABLE_INTEGRITY, false) || prefs.getBoolean(C.PLAYER_USE_STREAM_PROXY, false)) {
            return
        }
        val app = context.applicationContext as XtraApp
        val useCustomProxy = prefs.getBoolean(C.PLAYER_USE_CUSTOM_PROXY, true)
        val warmUp = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP) == C.OKHTTP
        login = channelLogin
        startedAt = SystemClock.elapsedRealtime()
        request = scope.async {
            // a custom proxy in use: the player goes through it, nothing is asked here
            if (useCustomProxy && app.xtraModule.playerRepository.getCustomProxies().any { it.enabled && !it.url.isNullOrBlank() }) {
                return@async null
            }
            val url = try {
                ExoPlayerService.loadStreamPlaylistUrl(app, app.xtraModule, channelLogin)
            } catch (e: Exception) {
                null
            }
            if (url != null && warmUp) {
                warmUpConnections(app, url)
            }
            url
        }
    }

    /**
     * The connections to the servers of Twitch (list of the qualities, then the server of the video) are opened while the
     * player opens, the player uses the same network client and finds them ready. Nothing is kept from the answers.
     */
    private fun warmUpConnections(app: XtraApp, url: String) {
        scope.async {
            try {
                val client = app.xtraModule.okHttpClient.value
                val master = client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body.string() else null
                } ?: return@async
                val variant = master.lineSequence().firstOrNull { it.startsWith("http") } ?: return@async
                client.newCall(okhttp3.Request.Builder().url(variant).head().build()).execute().close()
            } catch (e: Exception) {
            }
        }
    }

    /** The address asked for this stream a moment ago, used once; null when there is none (the player asks itself). */
    suspend fun take(channelLogin: String): String? {
        val pending = request?.takeIf { login == channelLogin && SystemClock.elapsedRealtime() - startedAt < 30_000L }
        request = null
        login = null
        return pending?.await()
    }

    fun clear() {
        request = null
        login = null
    }
}
