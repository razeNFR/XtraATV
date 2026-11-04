package com.github.andreyasadchy.xtra.ui.tv

import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

/**
 * Twitch makes the thumbnail of a stream a few minutes after it starts (sometimes more): until then it gives a grey
 * picture with a camera. [PreviewInterceptor] puts the picture of the channel in its place, wherever a stream picture is
 * loaded (cards, stream banner of the player, picture while a stream loads), as long as Twitch has no thumbnail.
 */
object TvThumbnails {

    /** A stream younger than this may still have no thumbnail: its address is checked before being loaded. */
    private const val CHECK_MS = 30 * 60_000L
    private const val PLACEHOLDER_KEEP_MS = 60_000L

    private class Channel(val image: String?, val createdAt: Long?)
    private class Check(val placeholder: Boolean, val at: Long)

    private val channels = HashMap<String, Channel>()
    private val checks = HashMap<String, Check>()

    /** The picture of the channel and the start of its stream, used when its thumbnail does not exist yet. */
    fun remember(stream: Stream) {
        val login = stream.channelLogin?.lowercase() ?: return
        val createdAt = stream.createdAt?.let { Instant.parseOrNull(it) }?.toEpochMilliseconds()?.takeIf { it > 0 }
        synchronized(channels) {
            val previous = channels[login]
            channels[login] = Channel(stream.channelImage?.takeIf { it.isNotBlank() } ?: previous?.image, createdAt ?: previous?.createdAt)
        }
    }

    /**
     * Picture of the card: the thumbnail. Twitch keeps the same address for a new thumbnail: the minute is added to it, the
     * cards load the new one each minute instead of keeping an old copy.
     */
    fun picture(stream: Stream, thumbnail: String?): String? {
        remember(stream)
        return withStamp(thumbnail, minute())
    }

    /** Login of a stream thumbnail address of Twitch ("…/previews-ttv/live_user_<login>-1280x720.jpg"), else null. */
    private fun loginOf(url: String): String? {
        if (!url.contains("/previews-ttv/live_user_")) return null
        return url.substringAfter("live_user_").substringBefore('?').substringBeforeLast('-').lowercase().takeIf { it.isNotBlank() }
    }

    /**
     * Twitch sends a stream without a thumbnail yet to its grey picture ("404_preview"): asked without following the
     * redirection. Kept a minute. Only for a stream started less than 30 minutes ago (or of unknown start).
     */
    private suspend fun isPlaceholder(client: OkHttpClient, url: String, login: String): Boolean {
        val createdAt = synchronized(channels) { channels[login]?.createdAt }
        if (createdAt != null && System.currentTimeMillis() - createdAt > CHECK_MS) return false
        val key = url.substringBefore('?')
        synchronized(checks) {
            checks[key]?.takeIf { System.currentTimeMillis() - it.at < PLACEHOLDER_KEEP_MS }?.let { return it.placeholder }
        }
        val placeholder = withContext(Dispatchers.IO) {
            try {
                client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(url).head().build()).executeAsync().use { response ->
                        response.isRedirect && response.header("Location")?.contains("404_preview") == true
                    }
            } catch (e: Exception) {
                false
            }
        }
        synchronized(checks) { checks[key] = Check(placeholder, System.currentTimeMillis()) }
        return placeholder
    }

    /** In the image loader of the app: a stream thumbnail Twitch has not made yet is replaced by the picture of the channel. */
    class PreviewInterceptor(private val client: () -> OkHttpClient) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            val url = request.data as? String
            // a copy already loaded (without the network) is never checked
            if (url == null || request.networkCachePolicy == CachePolicy.DISABLED) return chain.proceed()
            val login = loginOf(url) ?: return chain.proceed()
            val image = synchronized(channels) { channels[login]?.image } ?: return chain.proceed()
            return if (isPlaceholder(client(), url, login)) {
                chain.withRequest(request.newBuilder().data(image).build()).proceed()
            } else {
                chain.proceed()
            }
        }
    }

    fun minute() = System.currentTimeMillis() / 60_000L

    fun withStamp(url: String?, stamp: Long): String? {
        if (url.isNullOrBlank() || url.contains("404_preview")) return url
        return url + (if (url.contains('?')) "&" else "?") + "t=" + stamp
    }

    /** Addresses the cards may have loaded for this thumbnail, the newest first (the last 20 minutes, both sizes). */
    fun cachedCandidates(thumbnail: String): List<String> {
        val variants = listOf(thumbnail, thumbnail.replace("1280x720", "640x360")).distinct()
        val now = minute()
        return (0L..20L).flatMap { back -> variants.mapNotNull { withStamp(it, now - back) } } + variants
    }
}
