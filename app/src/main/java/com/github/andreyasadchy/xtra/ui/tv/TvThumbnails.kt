package com.github.andreyasadchy.xtra.ui.tv

import com.github.andreyasadchy.xtra.model.ui.Stream
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Twitch makes the thumbnail of a stream a few minutes after it starts: until then it gives a grey picture with a camera.
 * The picture of the channel is shown in its place.
 */
object TvThumbnails {

    private const val FRESH_MS = 5 * 60_000L

    fun isFresh(stream: Stream): Boolean {
        val createdAt = stream.createdAt?.let { Instant.parseOrNull(it) }?.toEpochMilliseconds()?.takeIf { it > 0 } ?: return false
        return Clock.System.now().toEpochMilliseconds() - createdAt in 0 until FRESH_MS
    }

    /**
     * Picture of the card: the thumbnail, or the picture of the channel while the thumbnail is not made yet. Twitch keeps
     * the same address for a new thumbnail: the minute is added to it, the cards load the new one each minute instead
     * of keeping an old copy.
     */
    fun picture(stream: Stream, thumbnail: String?): String? {
        return if (isFresh(stream) && !stream.channelImage.isNullOrBlank()) stream.channelImage else withStamp(thumbnail, minute())
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
