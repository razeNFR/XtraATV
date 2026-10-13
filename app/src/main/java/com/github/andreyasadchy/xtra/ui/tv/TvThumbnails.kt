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

    /** Picture of the card: the thumbnail, or the picture of the channel while the thumbnail is not made yet. */
    fun picture(stream: Stream, thumbnail: String?): String? {
        return if (isFresh(stream) && !stream.channelImage.isNullOrBlank()) stream.channelImage else thumbnail
    }
}
