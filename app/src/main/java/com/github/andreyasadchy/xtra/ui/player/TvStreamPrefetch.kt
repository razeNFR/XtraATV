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
 * TV: the access of Twitch to a stream is asked before the player needs it: as soon as OK is pressed on it, and for
 * the streams of left / right while a stream plays. Only the plain request is done here: the player uses it only when
 * it would do that same request (no proxy, not a restart of the ad blocker), else it asks itself as before.
 */
object TvStreamPrefetch {

    private class Entry(val request: Deferred<String?>, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = mutableMapOf<String, Entry>()

    /** The access is kept 2 minutes, Twitch gives it for longer. */
    private const val MAX_AGE = 120_000L

    /** OK pressed on a stream: its access, with the connections to its servers opened while the player opens. */
    fun start(context: Context, channelLogin: String?) {
        prepare(context, channelLogin, warmUp = true)
    }

    /** The streams of left / right while a stream plays: their access only. */
    fun prepareNeighbors(context: Context, channelLogins: List<String?>) {
        val keep = channelLogins.filterNotNull().toSet()
        synchronized(entries) {
            // the streams that are no longer next to the one watched are forgotten
            entries.keys.retainAll(keep + entries.filterValues { SystemClock.elapsedRealtime() - it.at < 10_000L }.keys)
        }
        keep.forEach { prepare(context, it, warmUp = false) }
    }

    private fun prepare(context: Context, channelLogin: String?, warmUp: Boolean) {
        if (channelLogin.isNullOrBlank() || !context.resources.getBoolean(com.github.andreyasadchy.xtra.R.bool.isTv)) {
            return
        }
        val prefs = context.prefs()
        // the integrity check has its own flow, the stream proxies their own access: left to the player
        if (prefs.getBoolean(C.ENABLE_INTEGRITY, false) || prefs.getBoolean(C.PLAYER_USE_STREAM_PROXY, false)) {
            return
        }
        synchronized(entries) {
            entries[channelLogin]?.let { entry ->
                // already asked a moment ago (a stream of left / right): used as it is
                if (SystemClock.elapsedRealtime() - entry.at < MAX_AGE - 30_000L) {
                    return
                }
            }
        }
        val app = context.applicationContext as XtraApp
        val useCustomProxy = prefs.getBoolean(C.PLAYER_USE_CUSTOM_PROXY, true)
        val warm = warmUp && prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP) == C.OKHTTP
        val request = scope.async {
            // a custom proxy in use: the player goes through it, nothing is asked here
            if (useCustomProxy && app.xtraModule.playerRepository.getCustomProxies().any { it.enabled && !it.url.isNullOrBlank() }) {
                return@async null
            }
            val url = try {
                ExoPlayerService.loadStreamPlaylistUrl(app, app.xtraModule, channelLogin)
            } catch (e: Exception) {
                null
            }
            if (url != null && warm) {
                warmUpConnections(app, url)
            }
            url
        }
        synchronized(entries) {
            entries[channelLogin] = Entry(request, SystemClock.elapsedRealtime())
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
        val entry = synchronized(entries) { entries.remove(channelLogin) }
            ?.takeIf { SystemClock.elapsedRealtime() - it.at < MAX_AGE } ?: return null
        return entry.request.await()
    }

    fun forget(channelLogin: String) {
        synchronized(entries) { entries.remove(channelLogin) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
