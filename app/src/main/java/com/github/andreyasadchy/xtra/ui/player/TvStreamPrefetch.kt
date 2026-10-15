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
        // the integrity check has its own flow: left to the player
        if (context.prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
            return
        }
        val app = context.applicationContext as XtraApp
        login = channelLogin
        startedAt = SystemClock.elapsedRealtime()
        request = scope.async {
            try {
                ExoPlayerService.loadStreamPlaylistUrl(app, app.xtraModule, channelLogin)
            } catch (e: Exception) {
                null
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
