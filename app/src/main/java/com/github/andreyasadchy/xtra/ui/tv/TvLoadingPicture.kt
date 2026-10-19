package com.github.andreyasadchy.xtra.ui.tv

import android.widget.ImageView
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TV: picture of a stream while it loads, never a black screen when one exists: the last picture of its preview, else
 * the newest copy already loaded at once, replaced by the picture Twitch has now as soon as it comes.
 */
object TvLoadingPicture {

    /** [isCurrent]: false once the stream changed or its video came, nothing more is put in [image]. */
    fun show(image: ImageView, channelLogin: String?, thumbnail: String?, fallback: String?, scope: CoroutineScope, isCurrent: () -> Boolean): Job? {
        TvPreviewFrames.get(channelLogin)?.let { frame ->
            image.setImageBitmap(frame)
            return null
        }
        val context = image.context
        val loader = context.imageLoader
        return scope.launch {
            var fresh = false
            // a picture put here by this call (a placeholder shown before, like the last picture of the stream left by a
            // switch, does not count: it is replaced)
            var shown = false
            // the picture Twitch has now, it replaces the copy shown meanwhile
            val freshJob = launch {
                val url = TvThumbnails.withStamp(thumbnail, System.currentTimeMillis() / 1000) ?: return@launch
                val result = loader.execute(
                    ImageRequest.Builder(context).data(url)
                        .memoryCachePolicy(CachePolicy.WRITE_ONLY)
                        .diskCachePolicy(CachePolicy.WRITE_ONLY)
                        .build()
                )
                if (result is SuccessResult && isCurrent()) {
                    fresh = true
                    shown = true
                    image.setImageDrawable(result.image.asDrawable(context.resources))
                }
            }
            // meanwhile the newest copy already loaded by the cards, without the network
            val candidates = (thumbnail?.let { TvThumbnails.cachedCandidates(it) } ?: emptyList()) + listOfNotNull(fallback)
            for (url in candidates) {
                if (fresh || !isCurrent()) break
                val result = loader.execute(ImageRequest.Builder(context).data(url).networkCachePolicy(CachePolicy.DISABLED).build())
                if (result is SuccessResult) {
                    if (!fresh && isCurrent()) {
                        shown = true
                        image.setImageDrawable(result.image.asDrawable(context.resources))
                    }
                    break
                }
            }
            // nothing in the cache: the picture of the channel while the thumbnail comes
            if (!fresh && !shown && fallback != null && isCurrent()) {
                val result = loader.execute(ImageRequest.Builder(context).data(fallback).build())
                if (result is SuccessResult && !fresh && isCurrent()) {
                    image.setImageDrawable(result.image.asDrawable(context.resources))
                }
            }
            freshJob.join()
        }
    }
}
