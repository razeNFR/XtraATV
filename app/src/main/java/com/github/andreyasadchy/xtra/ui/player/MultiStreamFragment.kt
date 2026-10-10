package com.github.andreyasadchy.xtra.ui.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.launch

/**
 * TV multi-stream: up to 4 live streams on screen at once. The D-pad selects a stream, whose sound
 * is played, select opens it in the normal player and back leaves the multi-stream view.
 */
class MultiStreamFragment : Fragment() {

    private class Tile(val stream: Stream, val view: FrameLayout, val player: ExoPlayer)

    private val tiles = mutableListOf<Tile>()
    private lateinit var root: FrameLayout

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            (activity as? MainActivity)?.closeMultiStream()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, backCallback)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = FrameLayout(requireContext()).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val streams = BundleCompat.getParcelableArrayList(requireArguments(), KEY_STREAMS, Stream::class.java).orEmpty().take(4)
        val density = resources.displayMetrics.density
        streams.forEach { stream ->
            val texture = TextureView(requireContext())
            val label = TextView(requireContext()).apply {
                text = stream.channelName ?: stream.channelLogin
                setTextColor(Color.WHITE)
                textSize = 14f
                setBackgroundResource(R.drawable.tv_badge_background)
                val padding = (6 * density).toInt()
                setPadding(padding * 2, padding, padding * 2, padding)
            }
            val tile = FrameLayout(requireContext()).apply {
                isFocusable = true
                foreground = GradientDrawable().apply {
                    setStroke((4 * density).toInt(), Color.TRANSPARENT)
                }
                addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                    val margin = (12 * density).toInt()
                    setMargins(margin, margin, margin, margin)
                })
            }
            val player = ExoPlayer.Builder(requireContext()).build().apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
                    if (streams.size > 2) setMaxVideoSize(854, 480) else setMaxVideoSize(1280, 720)
                }.build()
                setVideoTextureView(texture)
            }
            tiles.add(Tile(stream, tile, player))
            root.addView(tile)
            tile.setOnFocusChangeListener { v, hasFocus ->
                ((v as FrameLayout).foreground as? GradientDrawable)?.setStroke((4 * density).toInt(), if (hasFocus) Color.WHITE else Color.TRANSPARENT)
                if (hasFocus) {
                    tiles.forEach { it.player.volume = if (it.view == v) 1f else 0f }
                }
            }
            tile.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_UP) {
                    (activity as? MainActivity)?.let {
                        it.closeMultiStream()
                        it.startStream(stream)
                    }
                    true
                } else if (event.action == KeyEvent.ACTION_DOWN && keyCode in DIRECTIONS) {
                    // the focus only moves between the streams, never out of them (2 streams side by side: up / down do nothing)
                    val direction = DIRECTIONS.getValue(keyCode)
                    val next = android.view.FocusFinder.getInstance().findNextFocus(root, tile, direction)
                    if (next != null && tiles.any { it.view == next }) {
                        next.requestFocus()
                    }
                    true
                } else false
            }
            loadStream(stream, player)
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutTiles() }
        root.post {
            layoutTiles()
            tiles.firstOrNull()?.view?.requestFocus()
        }
    }

    private fun layoutTiles() {
        val width = root.width
        val height = root.height
        if (width == 0 || height == 0) {
            return
        }
        val count = tiles.size
        tiles.forEachIndexed { index, tile ->
            val params = when (count) {
                1 -> FrameLayout.LayoutParams(width, height)
                2 -> FrameLayout.LayoutParams(width / 2, height / 2).apply {
                    leftMargin = index * width / 2
                    topMargin = height / 4
                }
                else -> FrameLayout.LayoutParams(width / 2, height / 2).apply {
                    leftMargin = (index % 2) * width / 2
                    topMargin = (index / 2) * height / 2
                }
            }
            val current = tile.view.layoutParams as? FrameLayout.LayoutParams
            if (current == null || current.width != params.width || current.height != params.height || current.leftMargin != params.leftMargin || current.topMargin != params.topMargin) {
                tile.view.layoutParams = params
            }
        }
    }

    private fun loadStream(stream: Stream, player: ExoPlayer) {
        val login = stream.channelLogin ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            // ad blocker on every stream, like VAFT: the player type without an ad playing
            val url = com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.getPlaylistUrl(context, login)
            if (url != null && view != null) {
                player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.prepare()
                player.playWhenReady = true
            }
        }
        if (requireContext().prefs().getBoolean(C.PLAYER_ADBLOCK, true)) {
            var lastSwitch = 0L
            player.addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    val playlist = (player.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest)?.mediaPlaylist ?: return
                    val ads = playlist.tags.any { it.contains("stitched-ad", true) || it.contains("X-TV-TWITCH-AD", true) }
                    val now = System.currentTimeMillis()
                    if (ads && now - lastSwitch > 20_000L && view != null) {
                        // an ad started: the stream of an other player type
                        lastSwitch = now
                        loadStream(stream, player, listen = false)
                    }
                }
            })
        }
    }

    private fun loadStream(stream: Stream, player: ExoPlayer, listen: Boolean) {
        val login = stream.channelLogin ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val url = com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.getPlaylistUrl(requireContext(), login)
            if (url != null && view != null) {
                player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                player.prepare()
                player.playWhenReady = true
            }
        }
    }

    override fun onStop() {
        super.onStop()
        tiles.forEach { it.player.pause() }
    }

    override fun onStart() {
        super.onStart()
        tiles.forEach { it.player.play() }
    }

    override fun onDestroyView() {
        tiles.forEach { it.player.release() }
        tiles.clear()
        super.onDestroyView()
    }

    companion object {
        private val DIRECTIONS = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to View.FOCUS_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to View.FOCUS_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to View.FOCUS_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to View.FOCUS_RIGHT,
        )
        private const val KEY_STREAMS = "streams"

        fun newInstance(streams: List<Stream>) = MultiStreamFragment().apply {
            arguments = bundleOf(KEY_STREAMS to ArrayList(streams))
        }
    }
}
