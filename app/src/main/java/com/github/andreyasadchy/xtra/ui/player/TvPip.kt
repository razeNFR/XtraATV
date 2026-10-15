package com.github.andreyasadchy.xtra.ui.player

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TV: a second live stream in a small window at the top right of the full screen player, without sound
 * (opened by holding OK on a stream of the row of the other streams).
 * While it is shown: OK held swaps the two streams, back closes it, OK alone still opens the controls of the player.
 * It hides with the player (mini player, browsing) and closes with it.
 */
class TvPip(private val activity: MainActivity, private val container: ViewGroup) {

    var stream: Stream? = null
        private set

    private var view: FrameLayout? = null
    private var player: ExoPlayer? = null
    private var loadJob: Job? = null
    private var noPlayerChecks = 0
    private val followPlayer = object : Runnable {
        override fun run() {
            val shown = stream != null && activity.isPlayerMaximized()
            view?.visibility = if (shown) View.VISIBLE else View.GONE
            if (stream != null) {
                // the player closed (not just changing stream): the small window closes too
                noPlayerChecks = if (activity.hasPlayer()) 0 else noPlayerChecks + 1
                if (noPlayerChecks >= 4) {
                    close()
                    return
                }
                container.postDelayed(this, 500L)
            }
        }
    }

    val isShown get() = stream != null && view?.visibility == View.VISIBLE

    fun open(stream: Stream) {
        val login = stream.channelLogin ?: return
        this.stream = stream
        val context = activity
        val density = context.resources.displayMetrics.density
        val frame = view ?: FrameLayout(context).apply {
            val width = (context.resources.displayMetrics.widthPixels * 0.3f).toInt()
            layoutParams = FrameLayout.LayoutParams(width, width * 9 / 16, Gravity.TOP or Gravity.END).apply {
                val margin = context.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
                setMargins(margin, context.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical), margin, margin)
            }
            setBackgroundColor(Color.BLACK)
            foreground = GradientDrawable().apply {
                setStroke((3 * density).toInt(), Color.WHITE)
                cornerRadius = 12 * density
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 12 * density)
                }
            }
            clipToOutline = true
            elevation = 16 * density
            isFocusable = false
            val texture = TextureView(context)
            addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(TextView(context).apply {
                id = R.id.tvPipLabel
                setTextColor(Color.WHITE)
                textSize = 13f
                setBackgroundResource(R.drawable.tv_badge_background)
                val padding = (5 * density).toInt()
                setPadding(padding * 2, padding, padding * 2, padding)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                val margin = (8 * density).toInt()
                setMargins(margin, margin, margin, margin)
            })
            container.addView(this)
            view = this
            player = ExoPlayer.Builder(context).build().apply {
                volume = 0f
                setVideoTextureView(texture)
                trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(854, 480).build()
                addListener(object : Player.Listener {
                    private var lastSwitch = 0L
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        // ad blocker like the multi-stream: an ad started, the stream of another player type
                        val playlist = (currentManifest as? androidx.media3.exoplayer.hls.HlsManifest)?.mediaPlaylist ?: return
                        val ads = playlist.tags.any { it.contains("stitched-ad", true) || it.contains("X-TV-TWITCH-AD", true) }
                        val now = System.currentTimeMillis()
                        if (ads && now - lastSwitch > 20_000L && activity.prefs().getBoolean(com.github.andreyasadchy.xtra.util.C.PLAYER_ADBLOCK, true)) {
                            lastSwitch = now
                            this@TvPip.stream?.channelLogin?.let { load(it) }
                        }
                    }
                })
            }
        }
        frame.findViewById<TextView>(R.id.tvPipLabel)?.text = stream.channelName ?: login
        frame.visibility = View.VISIBLE
        noPlayerChecks = 0
        load(login)
        container.removeCallbacks(followPlayer)
        container.postDelayed(followPlayer, 500L)
    }

    private fun load(login: String) {
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            val url = TvPreviewSource.getPlaylistUrl(activity, login)
            val player = player
            if (url != null && player != null && stream?.channelLogin == login) {
                player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                player.prepare()
                player.playWhenReady = true
            }
        }
    }

    fun close() {
        stream = null
        loadJob?.cancel()
        loadJob = null
        container.removeCallbacks(followPlayer)
        player?.release()
        player = null
        view?.let { container.removeView(it) }
        view = null
    }

    private var okDown = false
    private var swapped = false

    /**
     * Keys while the small window is shown over the full screen player: OK held swaps the streams, back closes it.
     * True when the key was used here.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (!isShown) {
            okDown = false
            return false
        }
        val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        when {
            event.keyCode == KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    close()
                }
                return true
            }
            isOk && activity.isPlayerFocusedWithoutControls() -> {
                when {
                    event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                        okDown = true
                        swapped = false
                    }
                    event.action == KeyEvent.ACTION_DOWN && event.repeatCount >= 1 -> {
                        if (okDown && !swapped) {
                            swapped = true
                            swap()
                        }
                    }
                    event.action == KeyEvent.ACTION_UP -> {
                        val shortPress = okDown && !swapped
                        okDown = false
                        if (shortPress) {
                            // OK alone: the controls of the player, as without the small window
                            activity.dispatchPlayerKey(KeyEvent(KeyEvent.ACTION_DOWN, event.keyCode))
                            activity.dispatchPlayerKey(KeyEvent(KeyEvent.ACTION_UP, event.keyCode))
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    /** The stream of the small window goes full screen, the one that was there goes into the small window. */
    private fun swap() {
        val small = stream ?: return
        val main = activity.currentPlayingStream()
        activity.startStream(small)
        if (main?.channelLogin != null) {
            open(main)
        } else {
            close()
        }
    }

    companion object {
        fun showHint(activity: MainActivity) {
            Toast.makeText(activity, R.string.tv_pip_hint, Toast.LENGTH_LONG).show()
        }
    }
}
