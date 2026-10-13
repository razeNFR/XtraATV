package com.github.andreyasadchy.xtra.ui.view

import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TV: low quality live preview played inside the thumbnail of the focused stream card.
 * [onPlaying] is told when a preview starts and stops, to lower the sound of the stream behind it.
 * [onCard] gets the card whose preview shows its first picture, then null when it stops (the card grows meanwhile).
 */
class TvStreamPreview(
    private val recyclerView: RecyclerView,
    private val inPlayer: Boolean = false,
    private val onCard: ((View?) -> Unit)? = null,
    private val onPlaying: ((Boolean) -> Unit)? = null,
) : TvPreviewSource.Previewer {

    init {
        if (!inPlayer) {
            TvPreviewSource.register(this)
        }
    }

    override fun stopPreview() = stop()

    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null
    private var thumbnailResize: Pair<View, View.OnLayoutChangeListener>? = null
    private var card: View? = null
    private var loadJob: Job? = null
    private var pendingCard: View? = null
    private var pendingLogin: String? = null
    private val startAction = Runnable { start() }

    fun schedule(card: View, channelLogin: String?) {
        if (card == this.card || card == pendingCard) {
            return
        }
        stop()
        if (channelLogin.isNullOrBlank() || !recyclerView.context.prefs().getBoolean(C.UI_TV_PREVIEW, true) || (!inPlayer && TvPreviewSource.suspended)) {
            return
        }
        pendingCard = card
        pendingLogin = channelLogin
        recyclerView.postDelayed(startAction, TvPreviewSource.START_DELAY)
    }

    private fun start() {
        val card = pendingCard as? MaterialCardView ?: return
        val login = pendingLogin ?: return
        pendingCard = null
        pendingLogin = null
        // the focus already left the card
        if (!card.hasFocus()) return
        val thumbnail = card.findViewById<View>(R.id.thumbnail)?.takeIf { it.isShown && it.width > 0 } ?: return
        val owner = recyclerView.findViewTreeLifecycleOwner() ?: return
        this.card = card
        loadJob = owner.lifecycleScope.launch {
            val context = recyclerView.context
            val url = TvPreviewSource.getPlaylistUrl(context, login)
            if (url == null || this@TvStreamPreview.card != card || !card.hasFocus() || (!inPlayer && TvPreviewSource.suspended)) {
                return@launch
            }
            val textureView = TextureView(context).apply {
                alpha = 0f
                isFocusable = false
            }
            card.addView(textureView, FrameLayout.LayoutParams(thumbnail.width, thumbnail.height))
            this@TvStreamPreview.textureView = textureView
            // the card grows: the picture follows the thumbnail
            val resize = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val params = textureView.layoutParams
                if (params.width != view.width || params.height != view.height) {
                    params.width = view.width
                    params.height = view.height
                    textureView.layoutParams = params
                }
            }
            thumbnail.addOnLayoutChangeListener(resize)
            thumbnailResize = thumbnail to resize
            val player = player ?: TvPreviewSource.createPlayer(context).also { player = it }
            player.volume = if (TvPreviewSource.hasSound(context)) 1f else 0f
            player.clearVideoSurface()
            player.setVideoTextureView(textureView)
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    player.removeListener(this)
                    if (this@TvStreamPreview.card != card) {
                        // another preview took the player meanwhile
                        return
                    }
                    if (!card.hasFocus()) {
                        // the focus moved away while the preview was loading
                        stop()
                        return
                    }
                    textureView.animate().alpha(1f).setDuration(300).start()
                    onCard?.invoke(card)
                    onPlaying?.invoke(true)
                    if (!inPlayer) {
                        TvPreviewSource.notifyPreviewSound(context, true)
                    }
                }
            })
            player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun stop() {
        recyclerView.removeCallbacks(startAction)
        pendingCard = null
        pendingLogin = null
        loadJob?.cancel()
        loadJob = null
        player?.stop()
        player?.clearVideoSurface()
        textureView?.let { (it.parent as? FrameLayout)?.removeView(it) }
        textureView = null
        thumbnailResize?.let { (view, listener) -> view.removeOnLayoutChangeListener(listener) }
        thumbnailResize = null
        if (card != null) {
            onCard?.invoke(null)
            onPlaying?.invoke(false)
            if (!inPlayer) {
                TvPreviewSource.notifyPreviewSound(recyclerView.context, false)
            }
        }
        card = null
    }

    fun release() {
        stop()
        player?.release()
        player = null
    }

    fun isPreviewing(view: View) = card == view || pendingCard == view
}
