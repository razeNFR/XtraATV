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

    private const val FAILED_KEEP_MS = 10 * 60_000L

    private val failed = HashMap<String, Long>()

    /**
     * Player: whether a custom proxy can be reached at all (its "/ping" answers, whatever it says). Only no answer (no
     * connection, timeout) counts as offline: a proxy without "/ping" is still tried.
     */
    suspend fun reachable(context: Context, proxyUrl: String): Boolean = withContext(Dispatchers.IO) {
        val module = (context.applicationContext as XtraApp).xtraModule
        val url = (proxyUrl.toUri().takeIf { it.host != null } ?: "https://$proxyUrl".toUri()).buildUpon().apply {
            path("ping")
            clearQuery()
        }.build().toString()
        try {
            module.okHttpClient.value.newBuilder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(url).build()).executeAsync().use { true }
        } catch (e: Exception) {
            false
        }
    }

    /** Player: a custom proxy offline or failing, not tried again for 10 minutes. */
    fun markFailed(host: String) {
        synchronized(failed) { failed[host] = System.currentTimeMillis() }
    }

    fun isFailed(host: String): Boolean = synchronized(failed) {
        failed[host]?.let { System.currentTimeMillis() - it < FAILED_KEEP_MS } == true
    }

    /**
     * Short name of a custom proxy for the status of the ad blocker: "eu.luminous.dev" gives "Proxy EU Luminous",
     * "twitch-al.nadeko.net" gives "Proxy Nadeko".
     */
    fun displayName(host: String): String {
        val parts = host.removePrefix("www.").split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return "Proxy $host"
        val name = parts[parts.size - 2].replaceFirstChar { it.uppercase() }
        val region = parts.first().takeIf { parts.size > 2 && it.length <= 3 }?.uppercase()
        return listOfNotNull("Proxy", region, name).joinToString(" ")
    }

    /** A proxy edited or added: its status is checked again. */
    fun forget(proxyUrl: String?) {
        synchronized(results) { results.remove(proxyUrl) }
    }
}
