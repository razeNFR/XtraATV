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
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvActionPanel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.launch

/**
 * TV multi-stream: up to 4 live streams on screen at once. The D-pad selects a stream, whose sound is played,
 * OK opens the panel of that stream (open it in the player, its chat, change it, add a stream, its size) and back
 * leaves the multi-stream view.
 *
 * Chats: with 2 streams, the chat of a stream can be shown under it (both at once). With 3 streams, the empty cell at
 * the bottom right shows the chat of the selected stream, or the one pinned.
 */
class MultiStreamFragment : Fragment() {

    private class Tile(var stream: Stream, val view: FrameLayout, val player: ExoPlayer, val label: TextView) {
        /** Container of the chat under this stream (2 streams). */
        var chatContainer: FrameLayout? = null
        var adListener: Player.Listener? = null
    }

    private val tiles = mutableListOf<Tile>()
    private lateinit var root: FrameLayout

    /** 2 streams: logins whose chat is shown under their stream. */
    private val chatsShown = mutableSetOf<String>()

    /** 3 streams: chat of the empty cell, pinned to a stream (else it follows the selected stream). */
    private var pinnedChat: String? = null
    private var cellChatLogin: String? = null
    private var cellChat: FrameLayout? = null

    /** 2 streams: width of the first stream, in percent of the screen. */
    private var split = 50

    private val followFocus = Runnable { updateCellChat() }

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
        val streams = BundleCompat.getParcelableArrayList(requireArguments(), KEY_STREAMS, Stream::class.java).orEmpty().take(MAX)
        streams.forEach { addTile(it) }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutTiles() }
        root.post {
            layoutTiles()
            tiles.firstOrNull()?.view?.requestFocus()
        }
    }

    private fun addTile(stream: Stream): Tile {
        val density = resources.displayMetrics.density
        val texture = TextureView(requireContext())
        val label = TextView(requireContext()).apply {
            text = stream.channelName ?: stream.channelLogin
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.tv_badge_background)
            val padding = (6 * density).toInt()
            setPadding(padding * 2, padding, padding * 2, padding)
        }
        val tileView = FrameLayout(requireContext()).apply {
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
            setVideoTextureView(texture)
        }
        val tile = Tile(stream, tileView, player, label)
        tiles.add(tile)
        root.addView(tileView)
        updateVideoSizes()
        tileView.setOnFocusChangeListener { v, hasFocus ->
            ((v as FrameLayout).foreground as? GradientDrawable)?.setStroke((4 * density).toInt(), if (hasFocus) Color.WHITE else Color.TRANSPARENT)
            if (hasFocus) {
                tiles.forEach { it.player.volume = if (it.view == v) 1f else 0f }
                // 3 streams: the chat of the empty cell follows the selected stream, unless one is pinned
                root.removeCallbacks(followFocus)
                root.postDelayed(followFocus, 700L)
            }
        }
        tileView.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_UP) {
                showPanel(tile)
                true
            } else if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_DOWN) {
                true
            } else if (event.action == KeyEvent.ACTION_DOWN && keyCode in DIRECTIONS) {
                // the focus only moves between the streams, never out of them
                val direction = DIRECTIONS.getValue(keyCode)
                val next = android.view.FocusFinder.getInstance().findNextFocus(root, tileView, direction)
                if (next != null && tiles.any { it.view == next }) {
                    next.requestFocus()
                }
                true
            } else false
        }
        loadStream(tile)
        return tile
    }

    /** Lower quality when more streams share the screen. */
    private fun updateVideoSizes() {
        tiles.forEach {
            it.player.trackSelectionParameters = it.player.trackSelectionParameters.buildUpon().apply {
                if (tiles.size > 2) setMaxVideoSize(854, 480) else setMaxVideoSize(1280, 720)
            }.build()
        }
    }

    /** Panel of a stream: open it, its chat, change it, add a stream, its size. */
    private fun showPanel(tile: Tile) {
        val activity = activity as? MainActivity ?: return
        val login = tile.stream.channelLogin ?: return
        val name = tile.stream.channelName ?: login
        val actions = mutableListOf<TvActionPanel.Action>()
        actions += TvActionPanel.Action(getString(R.string.tv_multistream_open_full), R.drawable.tv_ic_open_full) {
            activity.closeMultiStream()
            activity.startStream(tile.stream)
        }
        if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
            when (tiles.size) {
                2 -> {
                    val shown = login in chatsShown
                    actions += TvActionPanel.Action(getString(if (shown) R.string.tv_multistream_hide_chat else R.string.tv_multistream_show_chat), R.drawable.tv_ic_chat) {
                        if (shown) chatsShown.remove(login) else chatsShown.add(login)
                        updateChats()
                    }
                }
                3 -> {
                    val pinned = pinnedChat == login
                    actions += TvActionPanel.Action(getString(if (pinned) R.string.tv_multistream_unpin_chat else R.string.tv_multistream_pin_chat), R.drawable.tv_ic_pin) {
                        pinnedChat = if (pinned) null else login
                        updateCellChat()
                    }
                }
            }
        }
        actions += TvActionPanel.Action(getString(R.string.tv_multistream_change), R.drawable.tv_ic_swap) {
            TvMultiStreamPicker.pickOne(activity, tiles.mapNotNull { it.stream.channelLogin }.toSet(), getString(R.string.tv_multistream_change)) { picked ->
                if (view != null) {
                    replaceStream(tile, picked)
                }
            }
        }
        if (tiles.size < MAX) {
            actions += TvActionPanel.Action(getString(R.string.tv_multistream_add), R.drawable.tv_ic_add) {
                TvMultiStreamPicker.pickOne(activity, tiles.mapNotNull { it.stream.channelLogin }.toSet(), getString(R.string.tv_multistream_add)) { picked ->
                    if (view != null && tiles.size < MAX) {
                        val added = addTile(picked)
                        // the chats follow the new layout (under the streams at 2, in the empty cell at 3)
                        updateChats()
                        updateCellChat()
                        added.view.requestFocus()
                    }
                }
            }
        }
        // 2 streams side by side: the share of the screen of this stream
        val slider = if (tiles.size == 2) {
            val index = tiles.indexOf(tile)
            TvActionPanel.Slider(
                label = getString(R.string.tv_multistream_size),
                value = if (index == 0) split else 100 - split,
                range = 30..70,
                step = 5,
                valueText = { "$it %" },
            ) { value ->
                split = if (index == 0) value else 100 - value
                layoutTiles()
            }
        } else null
        TvActionPanel.show(requireContext(), name, actions, slider)
    }

    private fun replaceStream(tile: Tile, stream: Stream) {
        val oldLogin = tile.stream.channelLogin
        tile.stream = stream
        tile.label.text = stream.channelName ?: stream.channelLogin
        tile.adListener?.let { tile.player.removeListener(it) }
        tile.adListener = null
        tile.player.stop()
        tile.player.clearMediaItems()
        loadStream(tile)
        val newLogin = stream.channelLogin
        if (oldLogin != null && chatsShown.remove(oldLogin) && newLogin != null) {
            chatsShown.add(newLogin)
        }
        if (pinnedChat == oldLogin) {
            pinnedChat = newLogin
        }
        updateChats()
        updateCellChat()
        tile.view.requestFocus()
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
                2 -> {
                    // side by side, each in 16:9 with its share of the width; chats under them when shown
                    val first = width * split / 100
                    val tileWidth = if (index == 0) first else width - first
                    val tileHeight = minOf(tileWidth * 9 / 16, height)
                    val anyChat = chatsShown.isNotEmpty()
                    FrameLayout.LayoutParams(tileWidth, tileHeight).apply {
                        leftMargin = if (index == 0) 0 else first
                        topMargin = if (anyChat) 0 else (height - tileHeight) / 2
                    }
                }
                else -> FrameLayout.LayoutParams(width / 2, height / 2).apply {
                    leftMargin = (index % 2) * width / 2
                    topMargin = (index / 2) * height / 2
                }
            }
            applyParams(tile.view, params)
        }
        layoutChats()
    }

    private fun applyParams(view: View, params: FrameLayout.LayoutParams) {
        val current = view.layoutParams as? FrameLayout.LayoutParams
        if (current == null || current.width != params.width || current.height != params.height || current.leftMargin != params.leftMargin || current.topMargin != params.topMargin) {
            view.layoutParams = params
        }
    }

    /** 2 streams: the chats under the streams. */
    private fun updateChats() {
        tiles.forEach { tile ->
            val login = tile.stream.channelLogin
            val show = tiles.size == 2 && login != null && login in chatsShown
            val container = tile.chatContainer
            if (show) {
                if (container == null || container.tag != login) {
                    container?.let { removeChat(it) }
                    tile.chatContainer = addChat(tile.stream)
                }
            } else if (container != null) {
                removeChat(container)
                tile.chatContainer = null
            }
        }
        if (tiles.size != 2) {
            chatsShown.clear()
        }
        layoutTiles()
    }

    /** 3 streams: the chat of the empty cell, the pinned one or the one of the selected stream. */
    private fun updateCellChat() {
        if (view == null) {
            return
        }
        val focused = tiles.find { it.view.hasFocus() }?.stream
        val stream = if (tiles.size == 3 && !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
            tiles.find { it.stream.channelLogin == pinnedChat }?.stream ?: focused
        } else null
        val login = stream?.channelLogin
        if (login == cellChatLogin) {
            return
        }
        cellChat?.let { removeChat(it) }
        cellChat = null
        cellChatLogin = login
        if (stream != null) {
            cellChat = addChat(stream)
        }
        layoutChats()
    }

    private fun layoutChats() {
        val width = root.width
        val height = root.height
        if (width == 0 || height == 0) {
            return
        }
        tiles.forEach { tile ->
            val container = tile.chatContainer ?: return@forEach
            val tileParams = tile.view.layoutParams as? FrameLayout.LayoutParams ?: return@forEach
            applyParams(container, FrameLayout.LayoutParams(tileParams.width, height - tileParams.height).apply {
                leftMargin = tileParams.leftMargin
                topMargin = tileParams.height
            })
        }
        cellChat?.let {
            applyParams(it, FrameLayout.LayoutParams(width / 2, height / 2).apply {
                leftMargin = width / 2
                topMargin = height / 2
            })
        }
    }

    private fun addChat(stream: Stream): FrameLayout {
        val container = FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            tag = stream.channelLogin
            setBackgroundColor(0xFF0E0E10.toInt())
            // the chat is read only here, the focus stays on the streams
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        root.addView(container)
        childFragmentManager.beginTransaction()
            .replace(container.id, ChatFragment.newInstance(stream.channelId, stream.channelLogin, stream.channelName, stream.id))
            .commitNowAllowingStateLoss()
        return container
    }

    private fun removeChat(container: FrameLayout) {
        childFragmentManager.findFragmentById(container.id)?.let {
            childFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss()
        }
        root.removeView(container)
    }

    private fun loadStream(tile: Tile) {
        val login = tile.stream.channelLogin ?: return
        val player = tile.player
        playStream(login, player, tile)
        if (requireContext().prefs().getBoolean(C.PLAYER_ADBLOCK, true)) {
            var lastSwitch = 0L
            val listener = object : Player.Listener {
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    val playlist = (player.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest)?.mediaPlaylist ?: return
                    val ads = playlist.tags.any { it.contains("stitched-ad", true) || it.contains("X-TV-TWITCH-AD", true) }
                    val now = System.currentTimeMillis()
                    if (ads && now - lastSwitch > 20_000L && view != null) {
                        // an ad started: the stream of an other player type
                        lastSwitch = now
                        playStream(login, player, tile)
                    }
                }
            }
            tile.adListener = listener
            player.addListener(listener)
        }
    }

    /** Ad blocker on every stream, like VAFT: the player type without an ad playing. */
    private fun playStream(login: String, player: ExoPlayer, tile: Tile) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.getPlaylistUrl(requireContext(), login)
            if (url != null && view != null && tile.stream.channelLogin == login) {
                player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                player.repeatMode = Player.REPEAT_MODE_OFF
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
        root.removeCallbacks(followFocus)
        tiles.forEach { it.player.release() }
        tiles.clear()
        super.onDestroyView()
    }

    companion object {
        private const val MAX = 4
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
