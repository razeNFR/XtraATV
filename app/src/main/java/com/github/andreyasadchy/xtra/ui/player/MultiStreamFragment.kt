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
 * Chats: with 2 streams, the chats of both streams are shown with them, under or above each stream (each one can be
 * hidden or moved). With 3 streams, the empty cell at the bottom right shows the chat of the selected stream, or the one
 * pinned. With 3 or 4 streams, a stream can be removed.
 */
class MultiStreamFragment : Fragment() {

    private class Tile(var stream: Stream, val view: FrameLayout, val player: ExoPlayer, val label: TextView) {
        /** Container of the chat under this stream (2 streams). */
        var chatContainer: FrameLayout? = null
        var adListener: Player.Listener? = null
        /** Steps under the quality of its place, after real stops of this stream (0: the full quality). */
        var qualityStepsDown = 0
        var playingSince = 0L
        val stops = ArrayDeque<Long>()
        var raiseJob: kotlinx.coroutines.Job? = null
        /** Picture of the stream under its video while it loads, the video shows once its first picture comes. */
        var picture: android.widget.ImageView? = null
        var texture: TextureView? = null
        /** Playing a backup player type for the time of an ad, back to the normal one once it is over. */
        var onBackup = false
        var backJob: kotlinx.coroutines.Job? = null
    }

    private val tiles = mutableListOf<Tile>()
    private lateinit var root: FrameLayout

    /** 2 streams: logins whose chat is shown with their stream. */
    private val chatsShown = mutableSetOf<String>()

    /** 2 streams: logins whose chat is above their stream instead of under it. */
    private val chatsOnTop = mutableSetOf<String>()

    /** 3 streams: chat of the empty cell, pinned to a stream (else it follows the selected stream). */
    private var pinnedChat: String? = null
    private var cellChatLogin: String? = null
    private var cellChat: FrameLayout? = null

    /**
     * Focus mode (3 or 4 streams): one stream big at the top left with its chat at the right, the others small under it.
     * OK on a small stream puts it big.
     */
    private var focusMode = false
    private var bigLogin: String? = null

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
            // the TV never goes to sleep (screensaver) while the streams of the multi-stream play: kept on while it is
            // shown, the flag of the window set by the single player is cleared when that player closes
            keepScreenOn = true
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val streams = BundleCompat.getParcelableArrayList(requireArguments(), KEY_STREAMS, Stream::class.java).orEmpty().take(MAX)
        streams.forEach { addTile(it) }
        // 2 streams: both chats at once, once the screen is laid out
        root.post { if (view != null) showBothChats() }
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
            // the picture of the stream under the video while it loads, never a black cell
            addView(android.widget.ImageView(context).apply {
                id = R.id.tvLoadingPicture
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        tile.picture = tileView.findViewById(R.id.tvLoadingPicture)
        tile.texture = texture
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // the video is there: it shows over the picture
                if (texture.alpha < 1f) {
                    texture.animate().alpha(1f).setDuration(250L).start()
                }
            }
        })
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
                if (panelOpenedByHold) {
                    panelOpenedByHold = false
                } else if (isFocusLayout() && tile.stream.channelLogin != bigTile()?.stream?.channelLogin) {
                    // focus mode: OK on a small stream puts it big
                    bigLogin = tile.stream.channelLogin
                    layoutTiles()
                    updateCellChat()
                } else {
                    showPanel(tile)
                }
                true
            } else if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_DOWN) {
                // OK held: the panel of the stream, also for a small stream of the focus mode (the release of the key
                // goes to the panel then, the flag is cleared at the next press)
                if (event.repeatCount == 0) {
                    panelOpenedByHold = false
                }
                if (event.repeatCount == 1 && isFocusLayout()) {
                    panelOpenedByHold = true
                    showPanel(tile)
                }
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

    /**
     * Quality of each stream by its size on the screen, the highest one up to it (the choice of the player would stay
     * low with the low latency of Twitch): 2 streams and the big one of the focus mode at full quality, the small ones
     * 480p, grid of 3 or 4 720p. A stream that stops for real goes one step down, and back up later.
     */
    private fun updateVideoSizes() {
        val focus = isFocusLayout()
        val big = if (focus) bigTile() else null
        tiles.forEach { tile ->
            val steps = listOf(Int.MAX_VALUE, 1080, 720, 480, 360)
            val base = when {
                focus && tile === big -> Int.MAX_VALUE
                focus -> 480
                tiles.size <= 2 -> Int.MAX_VALUE
                else -> 720
            }
            val height = steps.filter { it <= base }.getOrElse(tile.qualityStepsDown) { 360 }
            tile.player.trackSelectionParameters = tile.player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, height)
                .setForceHighestSupportedBitrate(true)
                .build()
        }
    }

    /** A stream that stops twice in a minute (after 10 s of play) goes one quality down, up again after 2 min 30. */
    private fun onTileState(tile: Tile, state: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (state == Player.STATE_READY) {
            if (tile.playingSince == 0L) {
                tile.playingSince = now
            }
        } else if (state == Player.STATE_BUFFERING && tile.playingSince != 0L && now - tile.playingSince >= 10_000L) {
            tile.stops.addLast(now)
            while (tile.stops.isNotEmpty() && now - tile.stops.first() > 60_000L) {
                tile.stops.removeFirst()
            }
            if (tile.stops.size >= 2 && tile.qualityStepsDown < 3) {
                tile.stops.clear()
                tile.qualityStepsDown++
                updateVideoSizes()
                tile.raiseJob?.cancel()
                tile.raiseJob = viewLifecycleOwner.lifecycleScope.launch {
                    while (tile.qualityStepsDown > 0) {
                        kotlinx.coroutines.delay(150_000L)
                        if (view == null || tile !in tiles) {
                            return@launch
                        }
                        tile.qualityStepsDown--
                        updateVideoSizes()
                    }
                }
            }
        }
    }

    private var panelOpenedByHold = false

    private fun isFocusLayout() = focusMode && tiles.size >= 3

    /** Focus mode: the big stream (the first one when none was chosen). */
    private fun bigTile() = tiles.find { it.stream.channelLogin != null && it.stream.channelLogin == bigLogin } ?: tiles.firstOrNull()

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
                    if (shown) {
                        // this stream only: its chat above or under it
                        val onTop = login in chatsOnTop
                        actions += TvActionPanel.Action(getString(if (onTop) R.string.tv_multistream_chat_bottom else R.string.tv_multistream_chat_top), R.drawable.tv_ic_forum) {
                            if (onTop) chatsOnTop.remove(login) else chatsOnTop.add(login)
                            layoutTiles()
                        }
                    }
                }
                3 -> if (!isFocusLayout()) {
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
        if (tiles.size >= 3) {
            if (isFocusLayout()) {
                actions += TvActionPanel.Action(getString(R.string.tv_multistream_grid), R.drawable.tv_ic_fullscreen) {
                    focusMode = false
                    layoutTiles()
                    updateCellChat()
                }
            } else {
                actions += TvActionPanel.Action(getString(R.string.tv_multistream_focus), R.drawable.tv_ic_fullscreen) {
                    focusMode = true
                    bigLogin = login
                    layoutTiles()
                    updateCellChat()
                }
            }
        }
        if (tiles.size > 2) {
            actions += TvActionPanel.Action(getString(R.string.tv_multistream_remove), R.drawable.tv_ic_delete) {
                if (view != null && tiles.size > 2) {
                    removeTile(tile)
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

    /** 3 or 4 streams: a stream leaves, the others take the screen (back to 2: both chats with their streams). */
    private fun removeTile(tile: Tile) {
        val login = tile.stream.channelLogin
        tile.adListener?.let { tile.player.removeListener(it) }
        tile.player.release()
        tile.chatContainer?.let { removeChat(it) }
        tile.chatContainer = null
        root.removeView(tile.view)
        tiles.remove(tile)
        if (pinnedChat == login) {
            pinnedChat = null
        }
        if (bigLogin == login) {
            bigLogin = null
        }
        login?.let {
            chatsShown.remove(it)
            chatsOnTop.remove(it)
        }
        updateVideoSizes()
        if (tiles.size == 2) {
            showBothChats()
        }
        updateChats()
        updateCellChat()
        tiles.firstOrNull()?.view?.requestFocus()
    }

    /** 2 streams: the chats of both streams are shown with them. */
    private fun showBothChats() {
        if (tiles.size == 2 && !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
            tiles.mapNotNullTo(chatsShown) { it.stream.channelLogin }
            updateChats()
        }
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
        if (oldLogin != null && chatsOnTop.remove(oldLogin) && newLogin != null) {
            chatsOnTop.add(newLogin)
        }
        if (pinnedChat == oldLogin) {
            pinnedChat = newLogin
        }
        if (bigLogin == oldLogin) {
            bigLogin = newLogin
        }
        updateChats()
        updateCellChat()
        tile.view.requestFocus()
    }

    private fun layoutTiles() {
        // the quality follows the place of each stream (big or small in focus mode)
        updateVideoSizes()
        val width = root.width
        val height = root.height
        if (width == 0 || height == 0) {
            return
        }
        val count = tiles.size
        if (isFocusLayout()) {
            // the big stream at the top left (2/3 of the width, 16:9), its chat at its right, the others in a row under it
            val big = bigTile()
            val bigWidth = width * 2 / 3
            val bigHeight = minOf(bigWidth * 9 / 16, height * 2 / 3)
            val smalls = tiles.filter { it !== big }
            val smallHeight = height - bigHeight
            val smallWidth = minOf(width / smalls.size.coerceAtLeast(1), smallHeight * 16 / 9)
            val start = (width - smallWidth * smalls.size) / 2
            big?.let { applyParams(it.view, FrameLayout.LayoutParams(bigWidth, bigHeight)) }
            smalls.forEachIndexed { index, tile ->
                applyParams(tile.view, FrameLayout.LayoutParams(smallWidth, smallHeight).apply {
                    leftMargin = start + index * smallWidth
                    topMargin = bigHeight
                })
            }
            layoutChats()
            return
        }
        tiles.forEachIndexed { index, tile ->
            val params = when (count) {
                1 -> FrameLayout.LayoutParams(width, height)
                2 -> {
                    // side by side, each in 16:9 with its share of the width; its chat under it, or above it
                    val first = width * split / 100
                    val tileWidth = if (index == 0) first else width - first
                    val tileHeight = minOf(tileWidth * 9 / 16, height)
                    val login = tile.stream.channelLogin
                    FrameLayout.LayoutParams(tileWidth, tileHeight).apply {
                        leftMargin = if (index == 0) 0 else first
                        topMargin = when {
                            tile.chatContainer == null -> (height - tileHeight) / 2
                            login in chatsOnTop -> height - tileHeight
                            else -> 0
                        }
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
            chatsOnTop.clear()
        }
        layoutTiles()
    }

    /** 3 streams: the chat of the empty cell, the pinned one or the one of the selected stream. */
    private fun updateCellChat() {
        if (view == null) {
            return
        }
        val focused = tiles.find { it.view.hasFocus() }?.stream
        val chatDisabled = requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
        val stream = when {
            chatDisabled -> null
            // focus mode: the chat of the big stream, at its right
            isFocusLayout() -> bigTile()?.stream
            tiles.size == 3 -> tiles.find { it.stream.channelLogin == pinnedChat }?.stream ?: focused
            else -> null
        }
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
            val onTop = tile.stream.channelLogin in chatsOnTop
            applyParams(container, FrameLayout.LayoutParams(tileParams.width, height - tileParams.height).apply {
                leftMargin = tileParams.leftMargin
                topMargin = if (onTop) 0 else tileParams.height
            })
        }
        cellChat?.let {
            if (isFocusLayout()) {
                val bigWidth = width * 2 / 3
                val bigHeight = minOf(bigWidth * 9 / 16, height * 2 / 3)
                applyParams(it, FrameLayout.LayoutParams(width - bigWidth, bigHeight).apply {
                    leftMargin = bigWidth
                    topMargin = 0
                })
            } else {
                applyParams(it, FrameLayout.LayoutParams(width / 2, height / 2).apply {
                    leftMargin = width / 2
                    topMargin = height / 2
                })
            }
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
        // the newest picture of the stream until its video comes
        tile.texture?.animate()?.cancel()
        tile.texture?.alpha = 0f
        tile.picture?.let { picture ->
            picture.setImageDrawable(null)
            val stream = tile.stream
            com.github.andreyasadchy.xtra.ui.tv.TvLoadingPicture.show(
                picture, login,
                stream.thumbnail?.takeUnless { com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.isFresh(stream) || stream.thumbnailURL.isNullOrBlank() },
                stream.channelImage, viewLifecycleOwner.lifecycleScope
            ) { view != null && tile in tiles && tile.stream.channelLogin == login && (tile.texture?.alpha ?: 1f) < 1f }
        }
        tile.playingSince = 0L
        tile.qualityStepsDown = 0
        tile.stops.clear()
        tile.raiseJob?.cancel()
        playStream(login, player, tile)
        // the stream ended (no more playlist, or the end of it): it leaves the multi-stream after a few seconds
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if ((error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode == 404) {
                    onStreamEnded(tile, login)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (view != null && tile in tiles) {
                    onTileState(tile, playbackState)
                }
                if (playbackState == Player.STATE_ENDED) {
                    onStreamEnded(tile, login)
                }
            }
        })
        if (requireContext().prefs().getBoolean(C.PLAYER_ADBLOCK, true)) {
            var lastSwitch = 0L
            val listener = object : Player.Listener {
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    val playlist = (player.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest)?.mediaPlaylist ?: return
                    val ads = playlist.tags.any { it.contains("stitched-ad", true) || it.contains("X-TV-TWITCH-AD", true) }
                    val now = System.currentTimeMillis()
                    if (ads && now - lastSwitch > 20_000L && view != null && tile.stream.channelLogin == login) {
                        // an ad started: a backup player type (autoplay first) until the ad is over
                        lastSwitch = now
                        playStream(login, player, tile, backup = true)
                    }
                }
            }
            tile.adListener = listener
            player.addListener(listener)
        }
    }

    private val endingLogins = mutableSetOf<String>()

    private fun onStreamEnded(tile: Tile, login: String) {
        if (!endingLogins.add(login)) {
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(6000L)
            endingLogins.remove(login)
            if (view == null || tile !in tiles || tile.stream.channelLogin != login || tile.player.isPlaying) {
                return@launch
            }
            android.widget.Toast.makeText(requireContext(), getString(R.string.tv_multistream_stream_ended, tile.stream.channelName ?: login), android.widget.Toast.LENGTH_LONG).show()
            if (tiles.size > 2) {
                removeTile(tile)
            } else {
                // one stream left: it goes on alone in the player
                val other = tiles.firstOrNull { it !== tile }?.stream
                val activity = activity as? MainActivity ?: return@launch
                activity.closeMultiStream()
                if (other != null) {
                    activity.startStream(other)
                }
            }
        }
    }

    /** Ad blocker on every stream, like VAFT: the player type without an ad playing. */
    /**
     * Ad blocker on every stream, like the video swap of the player: the normal player type (all the qualities), a
     * backup type (autoplay first) only while an ad plays, then back to the normal one once its playlist has no ad.
     */
    private fun playStream(login: String, player: ExoPlayer, tile: Tile, backup: Boolean = false) {
        // a new playlist (start, ad blocker): its first seconds do not count as stops
        tile.playingSince = 0L
        tile.backJob?.cancel()
        viewLifecycleOwner.lifecycleScope.launch {
            val context = context ?: return@launch
            val app = context.applicationContext as com.github.andreyasadchy.xtra.XtraApp
            val normal = if (!backup) {
                try {
                    ExoPlayerService.loadStreamPlaylistUrl(app, app.xtraModule, login)
                } catch (e: Exception) {
                    null
                }
            } else null
            val url = normal ?: com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.getBackupPlaylistUrl(app, login)
            if (url != null && view != null && tile.stream.channelLogin == login) {
                tile.onBackup = normal == null
                player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.prepare()
                player.playWhenReady = true
                if (tile.onBackup) {
                    waitForEndOfAd(login, player, tile)
                }
            }
        }
    }

    /** On a backup player type: every 30 s, back to the normal one as soon as its playlist has no ad. */
    private fun waitForEndOfAd(login: String, player: ExoPlayer, tile: Tile) {
        tile.backJob?.cancel()
        tile.backJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000L)
                if (view == null || tile !in tiles || tile.stream.channelLogin != login || !tile.onBackup) {
                    return@launch
                }
                val app = (context?.applicationContext as? com.github.andreyasadchy.xtra.XtraApp) ?: return@launch
                val normal = try {
                    ExoPlayerService.loadStreamPlaylistUrl(app, app.xtraModule, login)
                } catch (e: Exception) {
                    null
                } ?: continue
                if (!com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.playlistHasAds(app, normal) && view != null && tile in tiles && tile.stream.channelLogin == login) {
                    tile.onBackup = false
                    tile.playingSince = 0L
                    player.setMediaItem(MediaItem.Builder().setUri(normal).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                    player.prepare()
                    player.playWhenReady = true
                    return@launch
                }
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
