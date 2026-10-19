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
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TV: a second live stream in a small window over the full screen player (opened by holding OK on a stream of the row
 * of the other streams). Its corner, its size and the sound (big stream, small window or both with a balance) are
 * chosen in its panel, opened by the "Small window" button of the controls, and kept for the next time.
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
            layoutParams = FrameLayout.LayoutParams(0, 0)
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
            // the picture of the stream under the video while it loads
            addView(android.widget.ImageView(context).apply {
                id = R.id.tvLoadingPicture
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            texture.id = R.id.tvPipTexture
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

                    override fun onRenderedFirstFrame() {
                        // the video is there: it shows over the picture
                        view?.findViewById<TextureView>(R.id.tvPipTexture)?.takeIf { it.alpha < 1f }?.animate()?.alpha(1f)?.setDuration(250L)?.start()
                    }

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
        applyLayout()
        applySound()
        frame.findViewById<TextView>(R.id.tvPipLabel)?.text = stream.channelName ?: login
        // the newest picture of the stream until its video comes
        val texture = frame.findViewById<TextureView>(R.id.tvPipTexture)
        texture?.animate()?.cancel()
        texture?.alpha = 0f
        frame.findViewById<android.widget.ImageView>(R.id.tvLoadingPicture)?.let { picture ->
            picture.setImageDrawable(null)
            com.github.andreyasadchy.xtra.ui.tv.TvLoadingPicture.show(
                picture, login,
                stream.thumbnail?.takeUnless { com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.isFresh(stream) || stream.thumbnailURL.isNullOrBlank() },
                stream.channelImage, activity.lifecycleScope
            ) { this.stream?.channelLogin == login && (texture?.alpha ?: 1f) < 1f }
        }
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

    /** Corner and size of the small window, as chosen in its panel. */
    fun applyLayout() {
        val frame = view ?: return
        val prefs = activity.prefs()
        val metrics = activity.resources.displayMetrics
        val fraction = when (prefs.getInt(C.TV_PIP_SIZE, 1)) {
            0 -> 0.22f
            2 -> 0.4f
            else -> 0.3f
        }
        val width = (metrics.widthPixels * fraction).toInt()
        val corner = prefs.getString(C.TV_PIP_CORNER, "tr")
        val gravity = (if (corner == "bl" || corner == "br") Gravity.BOTTOM else Gravity.TOP) or
                (if (corner == "tl" || corner == "bl") Gravity.START else Gravity.END)
        val horizontal = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        val vertical = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
        frame.layoutParams = FrameLayout.LayoutParams(width, width * 9 / 16, gravity).apply {
            setMargins(horizontal, vertical, horizontal, vertical)
        }
    }

    /**
     * Sound of the two streams: the big one only, the small window only, or both with the balance between them (at
     * 0 both at full volume, towards one side the other one gets quieter).
     */
    fun applySound() {
        val prefs = activity.prefs()
        val volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
        val balance = prefs.getInt(C.TV_PIP_BALANCE, 0).coerceIn(-100, 100)
        val (main, small) = when (prefs.getString(C.TV_PIP_SOUND, "main")) {
            "pip" -> 0f to 1f
            "both" -> (if (balance > 0) 1f - balance / 100f else 1f) to (if (balance < 0) 1f + balance / 100f else 1f)
            else -> 1f to 0f
        }
        player?.volume = small * volume
        setMainMix(main)
    }

    private fun setMainMix(mix: Float) {
        if (mainMix != mix) {
            mainMix = mix
            activity.refreshPlayerVolume()
        }
    }

    fun close() {
        val wasOpen = stream != null
        stream = null
        // the big stream gets all of its sound back
        setMainMix(1f)
        if (wasOpen) {
            // its button leaves the controls, the focus does not get lost with it
            activity.onTvPipClosed()
        }
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
            // back hides the controls first (the player does it), it closes the small window only once they are hidden
            event.keyCode == KeyEvent.KEYCODE_BACK && activity.isPlayerFocusedWithoutControls() -> {
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

    /** OK held, or the button of the panel: the two streams change places. */
    fun swapStreams() {
        if (stream != null) {
            swap()
        }
    }

    companion object {
        /**
         * Share of its volume the stream of the full screen player keeps while the small window is open (1 without it),
         * read by the playback service each time it sets the volume.
         */
        @Volatile
        var mainMix = 1f
            private set

        fun showHint(activity: MainActivity) {
            Toast.makeText(activity, R.string.tv_pip_hint, Toast.LENGTH_LONG).show()
        }
    }
}
