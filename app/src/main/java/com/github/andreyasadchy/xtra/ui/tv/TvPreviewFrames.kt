package com.github.andreyasadchy.xtra.ui.tv

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.TextureView

/**
 * TV: the last picture of the live previews of the cards, kept a minute: the stream opened from a card shows it while
 * it loads (a picture of a second ago, the same as what the video will show).
 */
object TvPreviewFrames {

    private class Frame(val bitmap: Bitmap, val at: Long)

    private val frames = LinkedHashMap<String, Frame>()

    /** Keeps the picture shown by a preview that stops. */
    fun capture(channelLogin: String?, view: TextureView?) {
        if (channelLogin.isNullOrBlank() || view == null || !view.isAvailable) {
            return
        }
        val bitmap = try {
            view.getBitmap(640, 360)
        } catch (e: Exception) {
            null
        } ?: return
        synchronized(frames) {
            frames.remove(channelLogin)
            frames[channelLogin] = Frame(bitmap, SystemClock.elapsedRealtime())
            while (frames.size > 6) {
                frames.remove(frames.keys.first())
            }
        }
    }

    /** The picture of the preview of this stream, null when none was shown in the last minute. */
    fun get(channelLogin: String?): Bitmap? {
        if (channelLogin.isNullOrBlank()) return null
        return synchronized(frames) {
            frames[channelLogin]?.takeIf { SystemClock.elapsedRealtime() - it.at < 60_000L }?.bitmap
        }
    }
}
