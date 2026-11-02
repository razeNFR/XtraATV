package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.core.net.toUri
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * TV: whether a custom proxy answers (green / red in the proxy panels), checked like the screen of the settings: its
 * "/ping" gives back an "online" value. Kept a few minutes.
 */
object TvProxyStatus {

    private class Result(val online: Boolean, val at: Long)

    private val results = HashMap<String, Result>()

    suspend fun check(context: Context, proxyUrl: String?): Boolean = withContext(Dispatchers.IO) {
        if (proxyUrl.isNullOrBlank()) {
            return@withContext false
        }
        synchronized(results) {
            results[proxyUrl]?.takeIf { System.currentTimeMillis() - it.at < 3 * 60_000L }?.let { return@withContext it.online }
        }
        val module = (context.applicationContext as XtraApp).xtraModule
        val url = (proxyUrl.toUri().takeIf { it.host != null } ?: "https://$proxyUrl".toUri()).buildUpon().apply {
            path("ping")
        }.build().toString()
        val online = try {
            module.okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                // as.luminous.dev gives {"online": false} even when it works: an answer with "online" is enough
                module.json.decodeFromString<JsonObject>(response.body.string())["online"]?.jsonPrimitive != null
            }
        } catch (e: Exception) {
            false
        }
        synchronized(results) {
            results[proxyUrl] = Result(online, System.currentTimeMillis())
        }
        online
    }

    /** A proxy edited or added: its status is checked again. */
    fun forget(proxyUrl: String?) {
        synchronized(results) { results.remove(proxyUrl) }
    }
}
