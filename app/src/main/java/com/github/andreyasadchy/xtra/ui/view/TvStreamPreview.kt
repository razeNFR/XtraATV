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
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TV: muted low quality live preview played inside the thumbnail of the focused stream card.
 */
class TvStreamPreview(private val recyclerView: GridRecyclerView) {

    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null
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
        if (channelLogin.isNullOrBlank() || !recyclerView.context.prefs().getBoolean(C.UI_TV_PREVIEW, true)) {
            return
        }
        pendingCard = card
        pendingLogin = channelLogin
        recyclerView.postDelayed(startAction, START_DELAY)
    }

    private fun start() {
        val card = pendingCard as? MaterialCardView ?: return
        val login = pendingLogin ?: return
        pendingCard = null
        pendingLogin = null
        val thumbnail = card.findViewById<View>(R.id.thumbnail)?.takeIf { it.isShown && it.width > 0 } ?: return
        val owner = recyclerView.findViewTreeLifecycleOwner() ?: return
        this.card = card
        loadJob = owner.lifecycleScope.launch {
            val context = recyclerView.context
            val url = try {
                (context.applicationContext as XtraApp).xtraModule.playerRepository.loadStreamPlaylistUrl(
                    context = context,
                    networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(context, context.prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                    channelLogin = login,
                    platform = context.prefs().getString(C.TOKEN_PLATFORM, "web"),
                    playerType = context.prefs().getString(C.TOKEN_PLAYER_TYPE, "site"),
                    supportedCodecs = "h264",
                    proxyPlaybackAccessToken = false,
                    proxyHost = null,
                    proxyPort = null,
                    proxyUser = null,
                    proxyPassword = null,
                    proxyTimeout = null,
                    enableIntegrity = false,
                )
            } catch (e: Exception) {
                null
            }
            if (url == null || this@TvStreamPreview.card != card || !card.hasFocus()) {
                return@launch
            }
            val textureView = TextureView(context).apply {
                alpha = 0f
                isFocusable = false
            }
            card.addView(textureView, FrameLayout.LayoutParams(thumbnail.width, thumbnail.height))
            this@TvStreamPreview.textureView = textureView
            val player = player ?: ExoPlayer.Builder(context).build().also {
                it.volume = 0f
                it.repeatMode = Player.REPEAT_MODE_OFF
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(854, 480)
                    .build()
                player = it
            }
            player.clearVideoSurface()
            player.setVideoTextureView(textureView)
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    player.removeListener(this)
                    textureView.animate().alpha(1f).setDuration(300).start()
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
        card = null
    }

    fun release() {
        stop()
        player?.release()
        player = null
    }

    fun isPreviewing(view: View) = card == view || pendingCard == view

    companion object {
        private const val START_DELAY = 1200L
    }
}
