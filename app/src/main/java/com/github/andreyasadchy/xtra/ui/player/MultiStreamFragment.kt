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
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.ButtonRow
import com.github.andreyasadchy.xtra.ui.tv.FocusRow
import com.github.andreyasadchy.xtra.ui.tv.FooterActionChip
import com.github.andreyasadchy.xtra.ui.tv.PanelTabs
import com.github.andreyasadchy.xtra.ui.tv.SelectorRow
import com.github.andreyasadchy.xtra.ui.tv.SidePanel
import com.github.andreyasadchy.xtra.ui.tv.SliderRow
import com.github.andreyasadchy.xtra.ui.tv.TvColors
import com.github.andreyasadchy.xtra.ui.tv.TvDialogs
import com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamQuality
import com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamSound
import com.github.andreyasadchy.xtra.ui.tv.tvPanelFirstRow
import com.github.andreyasadchy.xtra.ui.tv.tvPanelLastRow
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.launch

/**
 * TV multi-stream: up to 6 live streams on screen at once. The D-pad selects a stream, whose sound is played (or the
 * sounds of all of them at their own volume, Sound of the panel),
 * OK opens the panel of that stream (open it in the player, its chat, change it, add a stream, its size) and back
 * leaves the multi-stream view.
 *
 * Chats: with 2 streams, the chats of both streams are shown with them, under or above each stream (each one can be
 * hidden or moved). With 3 or 5 streams, the empty cell at the bottom right shows the chat of the selected stream, or
 * the one pinned. With 3 to 6 streams, a stream can be removed.
 */
@OptIn(UnstableApi::class)
class MultiStreamFragment : Fragment() {

    private class Tile(
        var stream: Stream,
        val view: FrameLayout,
        val player: ExoPlayer,
        val label: TextView,
        val adBadge: TextView,
        val infoContainer: android.widget.LinearLayout,
        val infoTop: TextView,
        val infoTitle: TextView,
    ) {
        /** Container of the chat under this stream (2 streams). */
        var chatContainer: FrameLayout? = null
        var stateListener: Player.Listener? = null
        var adListener: Player.Listener? = null
        /** Optional per-stream manual cap for this multi-stream session (null: follows global profile). */
        var qualityOverrideHeight: Int? = null
        /** Steps under the quality of its place, after real stops of this stream (0: the full quality). */
        var qualityStepsDown = 0
        var lastDecoderStepDownAt = 0L
        var playingSince = 0L
        val stops = ArrayDeque<Long>()
        var raiseJob: kotlinx.coroutines.Job? = null
        /** Picture of the stream under its video while it loads, the video shows once its first picture comes. */
        var picture: android.widget.ImageView? = null
        var texture: TextureView? = null
        /** Playing a backup player type for the time of an ad, back to the normal one once it is over. */
        var onBackup = false
        var adMuted = false
        var adPulseAnimator: android.animation.ValueAnimator? = null
        var playJob: kotlinx.coroutines.Job? = null
        var expUpgradeJob: kotlinx.coroutines.Job? = null
        var experimentalVodActive = false
        var experimentalCreatedAtMs: Long? = null
        var backJob: kotlinx.coroutines.Job? = null
        var recoveryJob: kotlinx.coroutines.Job? = null
        var recoveryAttempt = 0
        var infoJob: kotlinx.coroutines.Job? = null
        var chatProbeJob: kotlinx.coroutines.Job? = null
        var dynamicsProcessing: android.media.audiofx.DynamicsProcessing? = null
        var offlineOverlay: FrameLayout? = null
        var offlinePollJob: kotlinx.coroutines.Job? = null
        var isOffline = false
        var resolvedChannelId: String? = null
        val effectiveChannelId: String?
            get() = stream.channelId?.takeIf { it.isNotBlank() } ?: resolvedChannelId?.takeIf { it.isNotBlank() }
    }

    private val tiles = mutableListOf<Tile>()
    private lateinit var root: FrameLayout
    private val followCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long?>>()
    private val roomStateCache = java.util.concurrent.ConcurrentHashMap<String, com.github.andreyasadchy.xtra.model.chat.RoomState>()
    private val userBadgesCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private val twitchEmotesCache = java.util.concurrent.ConcurrentHashMap<String, List<com.github.andreyasadchy.xtra.model.chat.Emote>>()
    private val thirdPartyEmotesCache = java.util.concurrent.ConcurrentHashMap<String, List<com.github.andreyasadchy.xtra.model.chat.Emote>>()

    /** 2 streams: logins whose chat is shown with their stream. */
    private val chatsShown = mutableSetOf<String>()

    /** 2 streams: logins whose chat is above their stream instead of under it. */
    private val chatsOnTop = mutableSetOf<String>()

    /** 3 or 5 streams: chat of the empty cell, pinned to a stream (else it follows the selected stream). */
    private var pinnedChat: String? = null
    private var cellChatHidden = false
    private var cellChatLogin: String? = null
    private var cellChat: FrameLayout? = null

    /**
     * Focus mode (3 to 6 streams): one stream big at the top left with its chat at the right, the others small under it.
     * Mosaic mode (5 or 6 streams, default): one stream 2/3×2/3 at the top left, the others in 1/3×1/3 cells around it.
     * OK on a small stream puts it big.
     */
    private var focusMode = false
    private var mosaic56 = true
    private var stacked2 = false
    private var swapSideChats2 = false
    private var bigLogin: String? = null

    /** 2 streams: width of the first stream, in percent of the screen. */
    private var split = 50

    private val followFocus = Runnable {
        updateCellChat()
        context?.let { ctx ->
            val focusedLogin = (tiles.find { it.view.hasFocus() } ?: selectedTile)?.stream?.channelLogin
            com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.onMultiStreamFocusChanged(ctx, focusedLogin)
        }
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            goBack()
        }
    }

    private var goingBack = false

    /** A stream of a tile that plays (no error, not ended): it is live. */
    private fun Tile.isPlaying(): Boolean {
        return player.playerError == null && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
    }

    /**
     * Back: the stream the multi-stream was made from when it is still live, else the stream with the focus, else the first
     * one that plays, else the multi-stream closes like before (made from the home: it closes).
     */
    private fun goBack() {
        val activity = activity as? MainActivity ?: return
        if (goingBack) {
            return
        }
        val origin = BundleCompat.getParcelable(requireArguments(), KEY_ORIGIN, Stream::class.java)
        if (origin == null || origin.channelLogin.isNullOrBlank()) {
            activity.closeMultiStream()
            return
        }
        goingBack = true
        viewLifecycleOwner.lifecycleScope.launch {
            val originTile = tiles.find { it.stream.channelLogin == origin.channelLogin }
            val originLive = when {
                originTile != null -> originTile.isPlaying()
                // not in the multi-stream: asked to Twitch, 2.5 s at most (no answer: the next choice)
                else -> kotlinx.coroutines.withTimeoutOrNull(2500L) {
                    activity.isStreamLive(origin)
                } == true
            }
            if (view == null) {
                return@launch
            }
            val playing = tiles.filter { it.isPlaying() }
            // the stream with the focus, or the big one of the focus/mosaic mode
            val focused = playing.find { it.view.hasFocus() } ?: playing.find { (isFocusLayout() || isMosaicLayout()) && it.stream.channelLogin == bigLogin }
            val choices = buildList {
                if (originLive) add(originTile?.stream ?: origin)
                focused?.let { add(it.stream) }
                addAll(playing.map { it.stream })
            }.distinctBy { it.channelLogin }
            val next = choices.firstOrNull()
            if (next == null) {
                activity.closeMultiStream()
                return@launch
            }
            if (!originLive) {
                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(activity, getString(R.string.tv_multistream_origin_offline, origin.channelName ?: origin.channelLogin), android.widget.Toast.LENGTH_SHORT).show()
            }
            activity.closeMultiStream()
            // the next choices if this one ends at once
            activity.startStreamFromMultiStream(next, choices.drop(1))
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
        val prefs = requireContext().prefs()
        mosaic56 = prefs.getBoolean(C.TV_MULTISTREAM_MOSAIC, true)
        stacked2 = prefs.getBoolean(C.TV_MULTISTREAM_2_STACKED, false)
        swapSideChats2 = prefs.getBoolean(C.TV_MULTISTREAM_2_SWAP_CHATS, false)
        val streams = BundleCompat.getParcelableArrayList(requireArguments(), KEY_STREAMS, Stream::class.java).orEmpty().take(MAX)
        streams.forEach { addTile(it) }
        // the dimming of the streams covers the cells and their chats (the panels are over it, not dimmed)
        com.github.andreyasadchy.xtra.ui.tv.TvDim.addListener(dimListener)
        com.github.andreyasadchy.xtra.ui.tv.TvDim.apply(root)
        // 2 streams: both chats at once, once the screen is laid out
        root.post { if (view != null) showBothChats() }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutTiles() }
        root.post {
            layoutTiles()
            tiles.firstOrNull()?.view?.requestFocus()
        }
        // statistics of the home: counted once (not multiplied by the number of tiles) for the main / focused stream
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000L)
                val playing = tiles.filter { it.player.isPlaying }
                val active = playing.find { (isFocusLayout() || isMosaicLayout()) && it.stream.channelLogin == bigLogin }
                    ?: playing.find { it === selectedTile || it.view.hasFocus() }
                    ?: playing.firstOrNull()
                if (active != null) {
                    val s = active.stream
                    com.github.andreyasadchy.xtra.ui.tv.TvStats.addWatchTime(
                        requireContext(),
                        s.channelLogin,
                        s.channelName,
                        30_000L,
                        s.gameId,
                        s.gameName,
                        s.channelImage,
                    )
                }
            }
        }
        // TV "Recently watched channels": record Multi-Stream channels after 30s of actual playback
        viewLifecycleOwner.lifecycleScope.launch {
            val playMsByLogin = HashMap<String, Long>()
            val recordedLogins = HashSet<String>()
            while (true) {
                kotlinx.coroutines.delay(5_000L)
                val ctx = context ?: continue
                val currentLogins = HashSet<String>()
                val activeLogin = tiles.find {
                    it.player.isPlaying && ((isFocusLayout() || isMosaicLayout()) && it.stream.channelLogin == bigLogin)
                }?.stream?.channelLogin
                    ?: tiles.find { it.player.isPlaying && (it === selectedTile || it.view.hasFocus()) }?.stream?.channelLogin
                // Process non-active tiles first so the primary/focused tile ends up at index 0
                val orderedPlaying = tiles.filter { it.player.isPlaying }.sortedBy {
                    if (it.stream.channelLogin != null && it.stream.channelLogin.equals(activeLogin, ignoreCase = true)) 1 else 0
                }
                for (tile in orderedPlaying) {
                    val s = tile.stream
                    val key = s.channelLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
                    currentLogins.add(key)
                    val nextMs = (playMsByLogin[key] ?: 0L) + 5_000L
                    playMsByLogin[key] = nextMs
                    com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync.recordLiveStreamSession(
                        context = ctx,
                        channelId = s.channelId,
                        channelLogin = s.channelLogin,
                        streamId = s.id,
                        streamCreatedAt = s.createdAt,
                        forcePersist = false,
                    )
                    if (nextMs >= 30_000L && recordedLogins.add(key)) {
                        com.github.andreyasadchy.xtra.ui.tv.TvRecentChannels.recordWatched(
                            ctx,
                            channelId = s.channelId,
                            login = s.channelLogin,
                            displayName = s.channelName,
                            avatarUrl = s.channelImage,
                        )
                    }
                }
                playMsByLogin.keys.retainAll(currentLogins)
            }
        }
    }

    private fun addTile(stream: Stream): Tile {
        com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync.recordLiveStreamSession(
            context = requireContext(),
            channelId = stream.channelId,
            channelLogin = stream.channelLogin,
            streamId = stream.id,
            streamCreatedAt = stream.createdAt,
        )
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
        // selected stream: name, viewers, video quality, live duration and a scrolling single-line title over a soft bottom gradient
        val infoTop = TextView(requireContext()).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(4 * density, 0f, 1 * density, 0xE6000000.toInt())
        }
        val infoTitle = TextView(requireContext()).apply {
            setTextColor(0xD9FFFFFF.toInt())
            textSize = 13f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(4 * density, 0f, 1 * density, 0xE6000000.toInt())
            setPadding(0, (2 * density).toInt(), (48 * density).toInt(), 0)
        }
        val infoContainer = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xD9000000.toInt(), 0x73000000, Color.TRANSPARENT)
            )
            val hPad = (14 * density).toInt()
            setPadding(hPad, (24 * density).toInt(), hPad, (10 * density).toInt())
            visibility = View.GONE
            addView(infoTop, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(infoTitle, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val adBadge = TextView(requireContext()).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            isSingleLine = true
            setBackgroundResource(R.drawable.tv_badge_background)
            val padH = (8 * density).toInt()
            val padV = (3 * density).toInt()
            setPadding(padH, padV, padH, padV)
            val badgeText = android.text.SpannableStringBuilder()
            com.github.andreyasadchy.xtra.util.chat.TvChatNotices.appendIcon(
                requireContext(),
                badgeText,
                R.drawable.tv_ic_shield,
                com.github.andreyasadchy.xtra.ui.tv.TvAccent.color,
                this.textSize * 1.1f,
            )
            val titleStart = badgeText.length
            badgeText.append(getString(R.string.tv_adblock_ad))
            badgeText.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                titleStart,
                badgeText.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text = badgeText
            visibility = View.GONE
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
            addView(adBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                val margin = (10 * density).toInt()
                setMargins(margin, margin, margin, margin)
            })
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                val margin = (12 * density).toInt()
                setMargins(margin, margin, margin, margin)
            })
            addView(infoContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        val tileLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(6_000, 14_000, 1_000, 2_000)
            .setBackBuffer(0, false)
            .build()
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(requireContext())
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(requireContext(), renderersFactory)
            .setLoadControl(tileLoadControl)
            .setHandleAudioBecomingNoisy(false)
            .setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build().apply {
                // the sound of a film, the audio focus held by the multi-stream itself (TvPlaybackSignals)
                setAudioAttributes(com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.playerAttributes, false)
                volume = 0f
                setVideoTextureView(texture)
            }
        val tile = Tile(stream, tileView, player, label, adBadge, infoContainer, infoTop, infoTitle)
        // the frame and the name show as the others do (hidden a few seconds after the last key)
        label.alpha = chromeAlpha
        infoContainer.alpha = chromeAlpha
        tileView.foreground?.alpha = (chromeAlpha * 255).toInt()
        tile.picture = tileView.findViewById(R.id.tvLoadingPicture)
        tile.texture = texture
        if (context?.prefs()?.getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false) == true &&
            player.audioSessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET
        ) {
            tile.dynamicsProcessing = createTileDynamicsProcessing(player.audioSessionId)
        }
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                tile.dynamicsProcessing?.let {
                    runCatching { it.release() }
                    tile.dynamicsProcessing = null
                }
                if (view != null && tile in tiles && context?.prefs()?.getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false) == true) {
                    tile.dynamicsProcessing = createTileDynamicsProcessing(audioSessionId)
                }
            }

            override fun onRenderedFirstFrame() {
                // the video is there: it shows over the picture
                if (texture.alpha < 1f) {
                    texture.animate().alpha(1f).setDuration(250L).start()
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (view != null && tile in tiles) {
                    updateTileInfo(tile)
                }
            }
        })
        tiles.add(tile)
        root.addView(tileView)
        updateVideoSizes()
        updateTileInfo(tile)
        syncQrCompanionChannels(tile.stream.channelLogin)
        tileView.setOnFocusChangeListener { v, hasFocus ->
            ((v as FrameLayout).foreground as? GradientDrawable)?.setStroke((4 * density).toInt(), if (hasFocus) Color.WHITE else Color.TRANSPARENT)
            label.visibility = if (hasFocus) View.GONE else View.VISIBLE
            infoContainer.visibility = if (hasFocus) View.VISIBLE else View.GONE
            com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(infoTitle, hasFocus && chromeAlpha > 0f)
            if (hasFocus) {
                selectedTile = tile
                updateTileInfo(tile)
                applySound()
                showChrome()
                // 3 or 5 streams: the chat of the empty cell follows the selected stream, unless one is pinned
                root.removeCallbacks(followFocus)
                root.postDelayed(followFocus, 700L)
            }
        }
        tileView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                // the frame of the selected stream and the names come back at each key, hidden again a few seconds later
                showChrome()
            }
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_UP) {
                if (panelOpenedByHold) {
                    panelOpenedByHold = false
                } else if ((isFocusLayout() || isMosaicLayout()) && tile.stream.channelLogin != bigTile()?.stream?.channelLogin) {
                    // focus / mosaic mode: OK on a small stream puts it big
                    bigLogin = tile.stream.channelLogin
                    layoutTiles()
                    updateCellChat()
                } else {
                    showPanel(tile)
                }
                true
            } else if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_DOWN) {
                // OK held: the panel of the stream, also for a small stream of the focus/mosaic mode (the release of the key
                // goes to the panel then, the flag is cleared at the next press)
                if (event.repeatCount == 0) {
                    panelOpenedByHold = false
                }
                if (event.repeatCount == 1 && (isFocusLayout() || isMosaicLayout())) {
                    panelOpenedByHold = true
                    showPanel(tile)
                }
                true
            } else if (event.action == KeyEvent.ACTION_DOWN && keyCode in DIRECTIONS) {
                // the focus only moves between the streams, never out of them
                val nextTile = findNextTile(tile, keyCode)
                if (nextTile != null) {
                    nextTile.view.requestFocus()
                } else {
                    val direction = DIRECTIONS.getValue(keyCode)
                    val next = android.view.FocusFinder.getInstance().findNextFocus(root, tileView, direction)
                    if (next != null && tiles.any { it.view == next }) {
                        next.requestFocus()
                    }
                }
                true
            } else false
        }
        loadStream(tile)
        applySound()
        return tile
    }

    private fun findNextTile(tile: Tile, keyCode: Int): Tile? {
        val count = tiles.size
        if (count <= 1) return null
        if (isFocusLayout()) {
            val big = bigTile() ?: return null
            val smalls = tiles.filter { it !== big }
            if (tile === big) {
                return if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) smalls.firstOrNull() else null
            }
            val sIdx = smalls.indexOf(tile)
            if (sIdx < 0) return null
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> big
                KeyEvent.KEYCODE_DPAD_LEFT -> smalls.getOrNull(sIdx - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> smalls.getOrNull(sIdx + 1)
                else -> null
            }
        }
        if (isMosaicLayout()) {
            val big = bigTile() ?: return null
            val smalls = tiles.filter { it !== big }
            if (tile === big) {
                return when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> smalls.getOrNull(0)
                    KeyEvent.KEYCODE_DPAD_DOWN -> smalls.getOrNull(2)
                    else -> null
                }
            }
            return when (smalls.indexOf(tile)) {
                0 -> when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> big
                    KeyEvent.KEYCODE_DPAD_DOWN -> smalls.getOrNull(1)
                    else -> null
                }
                1 -> when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> big
                    KeyEvent.KEYCODE_DPAD_UP -> smalls.getOrNull(0)
                    KeyEvent.KEYCODE_DPAD_DOWN -> smalls.getOrNull(4) ?: smalls.getOrNull(3)
                    else -> null
                }
                2 -> when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> big
                    KeyEvent.KEYCODE_DPAD_RIGHT -> smalls.getOrNull(3)
                    else -> null
                }
                3 -> when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> big
                    KeyEvent.KEYCODE_DPAD_LEFT -> smalls.getOrNull(2)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> smalls.getOrNull(4)
                    else -> null
                }
                4 -> when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> smalls.getOrNull(1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> smalls.getOrNull(3)
                    else -> null
                }
                else -> null
            }
        }
        val idx = tiles.indexOf(tile)
        if (idx < 0) return null
        if (count == 2) {
            return if (stacked2) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (idx == 0) tiles.getOrNull(1) else null
                    KeyEvent.KEYCODE_DPAD_UP -> if (idx == 1) tiles.getOrNull(0) else null
                    else -> null
                }
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (idx == 0) tiles.getOrNull(1) else null
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (idx == 1) tiles.getOrNull(0) else null
                    else -> null
                }
            }
        }
        if (count == 3 || count == 4) {
            val row = idx / 2
            val col = idx % 2
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (col > 0) tiles.getOrNull(idx - 1) else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (col == 0 && idx + 1 < count) tiles.getOrNull(idx + 1) else null
                KeyEvent.KEYCODE_DPAD_DOWN -> if (row == 0) tiles.getOrNull((2 + col).coerceAtMost(count - 1)) else null
                KeyEvent.KEYCODE_DPAD_UP -> if (row == 1) tiles.getOrNull(col) else null
                else -> null
            }
        }
        if (count == 5 || count == 6) {
            val row = idx / 3
            val col = idx % 3
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (col > 0) tiles.getOrNull(idx - 1) else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (col < 2 && idx + 1 < count) tiles.getOrNull(idx + 1) else null
                KeyEvent.KEYCODE_DPAD_DOWN -> if (row == 0) tiles.getOrNull((3 + col).coerceAtMost(count - 1)) else null
                KeyEvent.KEYCODE_DPAD_UP -> if (row == 1) {
                    if (count == 5 && cellChat == null) {
                        if (col == 0) tiles.getOrNull(0) else tiles.getOrNull(2)
                    } else {
                        tiles.getOrNull(col)
                    }
                } else null
                else -> null
            }
        }
        return null
    }

    private fun stepDownOnDecoderFailure(tile: Tile, login: String): Boolean {
        val ctx = context ?: return false
        val currentHeight = tile.player.trackSelectionParameters.maxVideoHeight
        val nextCap = when {
            currentHeight > 720 -> 720
            currentHeight > 480 -> 480
            currentHeight > 360 -> 360
            currentHeight > 160 -> 160
            else -> return false
        }
        tile.qualityOverrideHeight = nextCap
        updateVideoSizes()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - tile.lastDecoderStepDownAt > 12_000L) {
            tile.lastDecoderStepDownAt = now
            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                ctx,
                getString(R.string.tv_multistream_decoder_stepdown, tile.stream.channelName ?: login),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        playStream(login, tile.player, tile, backup = tile.onBackup)
        return true
    }

    /**
     * Quality of each stream by its size on the screen, the highest one up to it (the choice of the player would stay
     * low with the low latency of Twitch): 2 streams and the big one of the focus/mosaic mode at full quality, the small ones
     * 480p, grid of 3 or 4 720p. A stream that stops for real goes one step down, and back up later.
     */
    private fun updateVideoSizes() {
        val ctx = context ?: return
        val hasBig = isFocusLayout() || isMosaicLayout()
        val big = if (hasBig) bigTile() else null
        val ladder = TvMultiStreamQuality.LADDER_DESC
        tiles.forEach { tile ->
            val override = tile.qualityOverrideHeight
            val effectiveCap = if (override != null) {
                override
            } else {
                val baseCap = TvMultiStreamQuality.capFor(
                    context = ctx,
                    streamCount = tiles.size,
                    isFocusLayout = hasBig,
                    isBigTile = hasBig && tile === big,
                )
                val startIdx = ladder.indexOf(baseCap).takeIf { it >= 0 } ?: 0
                ladder.getOrElse(startIdx + tile.qualityStepsDown) { ladder.last() }
            }
            val spec = TvMultiStreamQuality.specOf(effectiveCap)
            if (tile.player.trackSelectionParameters.maxVideoHeight != spec.maxHeight ||
                tile.player.trackSelectionParameters.maxVideoFrameRate != spec.maxFrameRate ||
                !tile.player.trackSelectionParameters.forceHighestSupportedBitrate
            ) {
                tile.player.trackSelectionParameters = tile.player.trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(Int.MAX_VALUE, spec.maxHeight)
                    .setMaxVideoFrameRate(spec.maxFrameRate)
                    .setForceHighestSupportedBitrate(true)
                    .build()
            }
        }
    }

    private fun resetQualityStepDown(tile: Tile) {
        tile.qualityStepsDown = 0
        tile.stops.clear()
        tile.raiseJob?.cancel()
    }

    /** A stream that stops twice in a minute (after 10 s of play) goes one quality down, up again after 2 min 30. */
    private fun onTileState(tile: Tile, state: Int) {
        if (tile.qualityOverrideHeight != null) return
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

    private fun isMosaicLayout() = !focusMode && mosaic56 && (tiles.size == 5 || tiles.size == 6)

    private fun setMosaic56(enabled: Boolean) {
        mosaic56 = enabled
        context?.prefs()?.edit()?.putBoolean(C.TV_MULTISTREAM_MOSAIC, enabled)?.apply()
    }

    /** Focus / mosaic mode: the big stream (the first one when none was chosen). */
    private fun bigTile() = tiles.find { it.stream.channelLogin != null && it.stream.channelLogin == bigLogin } ?: tiles.firstOrNull()

    /** Compact 3-tab Multi-Stream panel matching Chat & PiP: Display & Chat · Audio · Options + pinned footer. */
    private fun showPanel(tile: Tile) {
        val activity = activity as? MainActivity ?: return
        val context = context ?: return
        val login = tile.stream.channelLogin ?: return
        val name = tile.stream.channelName ?: login
        loadSound()
        val prefs = context.prefs()
        val chatDisabled = prefs.getBoolean(C.CHAT_DISABLE, false)
        val loggedIn = !chatDisabled && isTileChatLoggedIn(context)
        val lockMsg = if (loggedIn) computeTileChatLockMessage(tile) else null
        val tileIndex = tiles.indexOf(tile)
        val streamCount = tiles.size
        val shownAudioTiles = tiles.filter { it.stream.channelLogin != null }
        val currentStreams = tiles.map { it.stream }
        val groupId = if (streamCount >= 2) com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.idOf(currentStreams) else ""

        val rootW = (tile.view.parent as? View)?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val alignEnd = (tile.view.x + tile.view.width / 2f) <= rootW / 2f

        val tabs = listOf(
            R.drawable.tv_ic_view_quilt to getString(R.string.tv_panel_tab_display),
            R.drawable.tv_ic_volume to getString(R.string.tv_panel_tab_audio),
            R.drawable.tv_ic_tune to getString(R.string.tv_panel_tab_options),
        )
        val profileOptions = TvMultiStreamQuality.PROFILES.map { TvMultiStreamQuality.profileLabel(context, it) }
        val overrideCaps: List<Int?> = listOf(null) + TvMultiStreamQuality.CAPS
        val overrideOptions = listOf(getString(R.string.tv_multistream_quality_follow_profile)) +
            TvMultiStreamQuality.CAPS.map { TvMultiStreamQuality.capLabel(context, it) }

        TvDialogs.show(context) { dismiss ->
            val selectedTab = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
            val tabRequesters = androidx.compose.runtime.remember { List(3) { androidx.compose.ui.focus.FocusRequester() } }
            val firstRowReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val lastRowReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val footerFullReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val footerRemoveReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }

            val stackedState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(stacked2) }
            val focusState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(isFocusLayout() && bigLogin == login) }
            val mosaicState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(isMosaicLayout()) }
            val chatShown2State = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(login in chatsShown) }
            val swapChats2State = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(swapSideChats2) }
            val chatOnTop2State = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(login in chatsOnTop) }
            val cellChatHiddenState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(cellChatHidden) }
            val pinnedChatState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(pinnedChat == login) }

            val mixState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(soundMix) }
            val keepSoundState = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(prefs.getBoolean(C.TV_MULTISTREAM_SOUND_KEEP, false))
            }

            val streamOverrideState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(tile.qualityOverrideHeight) }
            val profileState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(TvMultiStreamQuality.profile(context)) }
            val groupSavedState = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(
                    groupId.isNotEmpty() && com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.isSaved(context, currentStreams)
                )
            }

            val hasSizeSlider = streamCount == 2 && !stackedState.value && tileIndex >= 0
            val isAnyFocusLayout = focusState.value || (focusMode && streamCount >= 3)
            val hasChatToggle = !chatDisabled && (streamCount == 2 || ((streamCount == 3 || streamCount == 5) && !isAnyFocusLayout))
            val hasChatSecondary = !chatDisabled && when {
                streamCount == 2 -> chatShown2State.value
                (streamCount == 3 || streamCount == 5) && !isAnyFocusLayout -> !cellChatHiddenState.value
                else -> false
            }
            val tab0Count = 1 +
                (if (hasSizeSlider) 1 else 0) +
                (if (hasChatToggle) 1 else 0) +
                (if (hasChatSecondary) 1 else 0) +
                (if (loggedIn) 1 else 0) +
                1
            val tab1Count = if (mixState.value) 1 + shownAudioTiles.size + 1 else 1
            val hasCustomCapsBtn = profileState.value == TvMultiStreamQuality.PROFILE_CUSTOM
            val hasAddStream = streamCount < MAX
            val hasSaveGroup = groupId.isNotEmpty()
            val tab2Count = 2 +
                (if (hasCustomCapsBtn) 1 else 0) +
                1 +
                (if (hasAddStream) 1 else 0) +
                (if (hasSaveGroup) 1 else 0)

            val activeTabRowCount = when (selectedTab.intValue) {
                0 -> tab0Count
                1 -> tab1Count
                else -> tab2Count
            }
            val footerUpTarget = if (activeTabRowCount <= 1) firstRowReq else lastRowReq
            val canRemoveTile = streamCount > 2

            fun androidx.compose.ui.Modifier.rowFocus(idx: Int, total: Int): androidx.compose.ui.Modifier {
                var m = this
                if (idx == 0) {
                    m = m.tvPanelFirstRow(firstRowReq) { tabRequesters[selectedTab.intValue] }
                }
                if (idx == total - 1) {
                    m = m.tvPanelLastRow(if (idx == 0) null else lastRowReq, footerFullReq)
                }
                return m
            }

            SidePanel(
                title = name,
                width = 392.dp,
                compactHeader = true,
                scrollable = true,
                alignEnd = alignEnd,
                headerExtra = {
                    PanelTabs(
                        tabs = tabs,
                        selectedTab = selectedTab.intValue,
                        tabRequesters = tabRequesters,
                        downTarget = firstRowReq,
                        onSelect = { selectedTab.intValue = it },
                    )
                },
                footer = {
                    androidx.compose.foundation.layout.Row(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        FooterActionChip(
                            label = getString(R.string.tv_multistream_open_full),
                            icon = R.drawable.tv_ic_open_full,
                            requester = footerFullReq,
                            rightTarget = if (canRemoveTile) footerRemoveReq else null,
                            upTarget = footerUpTarget,
                        ) {
                            dismiss()
                            activity.closeMultiStream()
                            activity.startStream(tile.stream)
                        }
                        if (canRemoveTile) {
                            FooterActionChip(
                                label = getString(R.string.tv_multistream_remove),
                                icon = R.drawable.tv_ic_delete,
                                requester = footerRemoveReq,
                                leftTarget = footerFullReq,
                                upTarget = footerUpTarget,
                            ) {
                                dismiss()
                                if (view != null && tiles.size > 2) {
                                    removeTile(tile)
                                }
                            }
                        }
                    }
                },
            ) {
                when (selectedTab.intValue) {
                    0 -> {
                        var r = 0
                        if (streamCount == 2) {
                            ButtonRow(
                                label = getString(if (stackedState.value) R.string.tv_multistream_2_side_by_side else R.string.tv_multistream_2_stacked),
                                icon = R.drawable.tv_ic_fullscreen,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                            ) {
                                stacked2 = !stacked2
                                stackedState.value = stacked2
                                prefs.edit().putBoolean(C.TV_MULTISTREAM_2_STACKED, stacked2).apply()
                                layoutTiles()
                            }
                            if (hasSizeSlider) {
                                SliderRow(
                                    label = getString(R.string.tv_multistream_size),
                                    value = if (tileIndex == 0) split else 100 - split,
                                    range = 30..70,
                                    step = 5,
                                    valueText = { "$it %" },
                                    icon = R.drawable.tv_ic_open_full,
                                    compact = true,
                                    modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                                ) { value ->
                                    split = if (tileIndex == 0) value else 100 - value
                                    layoutTiles()
                                }
                            }
                        } else if (streamCount == 5 || streamCount == 6) {
                            val layoutModeIdx = when {
                                focusMode && bigLogin == login -> 2
                                mosaicState.value && !focusMode -> 1
                                else -> 0
                            }
                            SelectorRow(
                                label = getString(R.string.tv_panel_tab_display),
                                options = listOf(
                                    getString(R.string.tv_multistream_grid),
                                    getString(R.string.tv_multistream_mosaic),
                                    getString(R.string.tv_multistream_focus),
                                ),
                                selected = layoutModeIdx,
                                icon = R.drawable.tv_ic_fullscreen,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                            ) { chosen ->
                                when (chosen) {
                                    0 -> {
                                        focusMode = false
                                        setMosaic56(false)
                                        focusState.value = false
                                        mosaicState.value = false
                                    }
                                    1 -> {
                                        focusMode = false
                                        setMosaic56(true)
                                        bigLogin = login
                                        focusState.value = false
                                        mosaicState.value = true
                                    }
                                    else -> {
                                        focusMode = true
                                        bigLogin = login
                                        focusState.value = true
                                        mosaicState.value = false
                                    }
                                }
                                layoutTiles()
                                updateCellChat()
                            }
                        } else {
                            val isThisFocused = focusMode && bigLogin == login
                            SelectorRow(
                                label = getString(R.string.tv_panel_tab_display),
                                options = listOf(
                                    getString(R.string.tv_multistream_grid),
                                    getString(R.string.tv_multistream_focus),
                                ),
                                selected = if (isThisFocused) 1 else 0,
                                icon = R.drawable.tv_ic_fullscreen,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                            ) { chosen ->
                                if (chosen == 1) {
                                    focusMode = true
                                    bigLogin = login
                                    focusState.value = true
                                } else {
                                    focusMode = false
                                    focusState.value = false
                                }
                                layoutTiles()
                                updateCellChat()
                            }
                        }

                        if (hasChatToggle) {
                            if (streamCount == 2) {
                                ButtonRow(
                                    label = getString(if (chatShown2State.value) R.string.tv_multistream_hide_chat else R.string.tv_multistream_show_chat),
                                    icon = R.drawable.tv_ic_chat,
                                    compact = true,
                                    modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                                ) {
                                    if (chatShown2State.value) chatsShown.remove(login) else chatsShown.add(login)
                                    chatShown2State.value = login in chatsShown
                                    updateChats()
                                }
                            } else {
                                ButtonRow(
                                    label = getString(if (cellChatHiddenState.value) R.string.tv_multistream_show_chat else R.string.tv_multistream_hide_chat),
                                    icon = R.drawable.tv_ic_chat,
                                    compact = true,
                                    modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                                ) {
                                    cellChatHidden = !cellChatHidden
                                    cellChatHiddenState.value = cellChatHidden
                                    updateCellChat()
                                }
                            }
                        }

                        if (hasChatSecondary) {
                            if (streamCount == 2) {
                                if (stackedState.value) {
                                    ButtonRow(
                                        label = getString(R.string.tv_multistream_swap_side_chats),
                                        icon = R.drawable.tv_ic_swap,
                                        compact = true,
                                        modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                                    ) {
                                        swapSideChats2 = !swapSideChats2
                                        swapChats2State.value = swapSideChats2
                                        prefs.edit().putBoolean(C.TV_MULTISTREAM_2_SWAP_CHATS, swapSideChats2).apply()
                                        layoutTiles()
                                    }
                                } else {
                                    ButtonRow(
                                        label = getString(if (chatOnTop2State.value) R.string.tv_multistream_chat_bottom else R.string.tv_multistream_chat_top),
                                        icon = R.drawable.tv_ic_forum,
                                        compact = true,
                                        modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                                    ) {
                                        if (chatOnTop2State.value) chatsOnTop.remove(login) else chatsOnTop.add(login)
                                        chatOnTop2State.value = login in chatsOnTop
                                        layoutTiles()
                                    }
                                }
                            } else {
                                ButtonRow(
                                    label = getString(if (pinnedChatState.value) R.string.tv_multistream_unpin_chat else R.string.tv_multistream_pin_chat),
                                    icon = R.drawable.tv_ic_pin,
                                    compact = true,
                                    modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                                ) {
                                    pinnedChat = if (pinnedChat == login) null else login
                                    pinnedChatState.value = pinnedChat == login
                                    updateCellChat()
                                }
                            }
                        }

                        if (loggedIn) {
                            ButtonRow(
                                label = getString(R.string.tv_write_message),
                                icon = if (lockMsg != null) R.drawable.tv_ic_chat_send_locked else R.drawable.tv_ic_chat_send,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                            ) {
                                dismiss()
                                openTileChatComposer(tile)
                            }
                        }

                        val qrConnected by com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.connectedClients.collectAsState()
                        val qrLabel = if (qrConnected > 0) {
                            "${getString(R.string.tv_chat_qr_title)} · ${getString(R.string.tv_chat_qr_connected, qrConnected)}"
                        } else {
                            getString(R.string.tv_chat_qr_title)
                        }
                        ButtonRow(
                            label = qrLabel,
                            icon = R.drawable.tv_ic_qr,
                            compact = true,
                            modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab0Count),
                        ) {
                            dismiss()
                            val qrChat = getOrAttachQrChat(login)
                            syncQrCompanionChannels(login)
                            com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.openQrPanel(
                                context = context,
                                uiScope = viewLifecycleOwner.lifecycleScope,
                                chatFragment = qrChat,
                                channelName = name,
                                channelLogin = login,
                                channelAvatar = tile.stream.channelImage,
                                alignEnd = alignEnd,
                                owner = this@MultiStreamFragment,
                            )
                        }
                    }

                    1 -> {
                        var r = 0
                        SelectorRow(
                            label = getString(R.string.tv_multistream_sound),
                            options = listOf(
                                getString(R.string.tv_multistream_sound_selected),
                                getString(R.string.tv_multistream_sound_mix),
                            ),
                            selected = if (mixState.value) 1 else 0,
                            icon = R.drawable.tv_ic_volume,
                            compact = true,
                            modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab1Count),
                        ) { idx ->
                            val nextMix = idx == 1
                            mixState.value = nextMix
                            soundMix = nextMix
                            if (prefs.getBoolean(C.TV_MULTISTREAM_SOUND_KEEP, false)) {
                                prefs.edit().putBoolean(C.TV_MULTISTREAM_SOUND_MIX, nextMix).apply()
                            }
                            applySound()
                        }
                        if (mixState.value) {
                            shownAudioTiles.forEach { audioTile ->
                                val tLogin = audioTile.stream.channelLogin.orEmpty()
                                val tName = audioTile.stream.channelName ?: tLogin
                                SliderRow(
                                    label = tName,
                                    value = streamVolume(audioTile),
                                    range = 0..100,
                                    step = 10,
                                    valueText = { "$it %" },
                                    compact = true,
                                    modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab1Count),
                                ) { value ->
                                    if (tLogin.isNotEmpty()) {
                                        sessionVolumes[tLogin] = value
                                        if (prefs.getBoolean(C.TV_MULTISTREAM_SOUND_KEEP, false)) {
                                            prefs.edit().putInt(C.TV_MULTISTREAM_VOLUME_PREFIX + tLogin, value).apply()
                                        }
                                    }
                                    applySound()
                                }
                            }
                            FocusRow(
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab1Count),
                                onClick = {
                                    val nextKeep = !keepSoundState.value
                                    keepSoundState.value = nextKeep
                                    prefs.edit().apply {
                                        putBoolean(C.TV_MULTISTREAM_SOUND_KEEP, nextKeep)
                                        putBoolean(C.TV_MULTISTREAM_SOUND_MIX, nextKeep && soundMix)
                                        if (nextKeep) {
                                            tiles.forEach { t ->
                                                t.stream.channelLogin?.let { putInt(C.TV_MULTISTREAM_VOLUME_PREFIX + it, streamVolume(t)) }
                                            }
                                        }
                                    }.apply()
                                },
                            ) { focused ->
                                val color = if (focused) TvColors.onFocused else androidx.compose.ui.graphics.Color.White
                                val shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    androidx.compose.foundation.layout.Box(
                                        androidx.compose.ui.Modifier
                                            .size(18.dp)
                                            .border(2.dp, if (keepSoundState.value) androidx.tv.material3.MaterialTheme.colorScheme.primary else color, shape)
                                            .background(
                                                if (keepSoundState.value) androidx.tv.material3.MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                                shape,
                                            ),
                                        contentAlignment = androidx.compose.ui.Alignment.Center,
                                    ) {
                                        if (keepSoundState.value) {
                                            androidx.tv.material3.Text("✓", fontSize = 12.sp, color = androidx.tv.material3.MaterialTheme.colorScheme.onPrimary)
                                        }
                                    }
                                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(10.dp))
                                    androidx.tv.material3.Text(
                                        getString(R.string.tv_multistream_sound_keep),
                                        fontSize = 15.sp,
                                        color = color,
                                        maxLines = 2,
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        var r = 0
                        val overrideIdx = overrideCaps.indexOf(streamOverrideState.value).coerceAtLeast(0)
                        SelectorRow(
                            label = getString(R.string.tv_remote_action_quality),
                            options = overrideOptions,
                            selected = overrideIdx,
                            icon = R.drawable.tv_ic_hd,
                            compact = true,
                            modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab2Count),
                        ) { idx ->
                            val nextCap = overrideCaps.getOrNull(idx)
                            streamOverrideState.value = nextCap
                            if (view != null && tile in tiles) {
                                tile.qualityOverrideHeight = nextCap
                                resetQualityStepDown(tile)
                                updateVideoSizes()
                            }
                        }

                        val profIdx = TvMultiStreamQuality.PROFILES.indexOf(profileState.value).coerceAtLeast(0)
                        SelectorRow(
                            label = getString(R.string.tv_multistream_quality_profile),
                            options = profileOptions,
                            selected = profIdx,
                            icon = R.drawable.tv_ic_tune,
                            compact = true,
                            modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab2Count),
                        ) { idx ->
                            val chosen = TvMultiStreamQuality.PROFILES.getOrElse(idx) { TvMultiStreamQuality.PROFILE_HIGH }
                            profileState.value = chosen
                            prefs.edit().putString(C.TV_MULTISTREAM_QUALITY, chosen).apply()
                            if (view != null) {
                                tiles.forEach { resetQualityStepDown(it) }
                                updateVideoSizes()
                            }
                        }

                        if (hasCustomCapsBtn) {
                            ButtonRow(
                                label = getString(R.string.tv_multistream_quality),
                                icon = R.drawable.tv_ic_settings,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab2Count),
                            ) {
                                dismiss()
                                showQualityPanel(tile)
                            }
                        }

                        ButtonRow(
                            label = getString(R.string.tv_multistream_change),
                            icon = R.drawable.tv_ic_swap,
                            compact = true,
                            modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab2Count),
                        ) {
                            dismiss()
                            TvMultiStreamPicker.pickOne(
                                activity,
                                tiles.mapNotNull { it.stream.channelLogin }.toSet(),
                                getString(R.string.tv_multistream_change),
                                tile.stream,
                            ) { picked ->
                                if (view != null) {
                                    replaceStream(tile, picked)
                                }
                            }
                        }

                        if (hasAddStream) {
                            ButtonRow(
                                label = getString(R.string.tv_multistream_add),
                                icon = R.drawable.tv_ic_add,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab2Count),
                            ) {
                                dismiss()
                                TvMultiStreamPicker.pickOne(
                                    activity,
                                    tiles.mapNotNull { it.stream.channelLogin }.toSet(),
                                    getString(R.string.tv_multistream_add),
                                    tile.stream,
                                ) { picked ->
                                    if (view != null && tiles.size < MAX) {
                                        val added = addTile(picked)
                                        updateChats()
                                        updateCellChat()
                                        added.view.requestFocus()
                                    }
                                }
                            }
                        }

                        if (hasSaveGroup) {
                            ButtonRow(
                                label = getString(if (groupSavedState.value) R.string.tv_multistream_remove_group else R.string.tv_multistream_save_group),
                                icon = if (groupSavedState.value) R.drawable.tv_ic_delete else R.drawable.tv_ic_star,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.rowFocus(r++, tab2Count),
                            ) {
                                if (groupSavedState.value) {
                                    com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.remove(context, groupId)
                                    groupSavedState.value = false
                                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, R.string.tv_multistream_group_removed, android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.save(context, currentStreams)
                                    groupSavedState.value = true
                                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, R.string.tv_multistream_group_saved, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    androidx.compose.runtime.withFrameNanos {}
                    runCatching { tabRequesters.getOrNull(selectedTab.intValue)?.requestFocus() }
                }
            }
        }
    }

    private fun isTileChatLoggedIn(context: android.content.Context): Boolean {
        return !com.github.andreyasadchy.xtra.util.TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN].isNullOrBlank() ||
            !com.github.andreyasadchy.xtra.util.TwitchApiHelper.getHelixHeaders(context)[C.HEADER_TOKEN].isNullOrBlank()
    }

    private fun findChatFragment(login: String?): ChatFragment? {
        if (login.isNullOrBlank() || !isAdded) return null
        tiles.find { it.stream.channelLogin.equals(login, ignoreCase = true) }?.chatContainer?.id?.let { id ->
            (childFragmentManager.findFragmentById(id) as? ChatFragment)?.takeIf { it.isAdded && it.view != null }?.let { return it }
        }
        if (cellChatLogin != null && cellChatLogin.equals(login, ignoreCase = true)) {
            cellChat?.id?.let { id ->
                (childFragmentManager.findFragmentById(id) as? ChatFragment)?.takeIf { it.isAdded && it.view != null }?.let { return it }
            }
        }
        if (qrCompanionChatLogin != null && qrCompanionChatLogin.equals(login, ignoreCase = true)) {
            qrCompanionChatContainer?.id?.let { id ->
                (childFragmentManager.findFragmentById(id) as? ChatFragment)?.takeIf { it.isAdded && it.view != null }?.let { return it }
            }
        }
        return null
    }

    private var qrCompanionChatContainer: FrameLayout? = null
    private var qrCompanionChatLogin: String? = null

    private fun getOrAttachQrChat(login: String): ChatFragment? {
        if (!isAdded || view == null || login.isBlank()) return null
        findChatFragment(login)?.let { return it }
        val tile = tiles.find { it.stream.channelLogin.equals(login, ignoreCase = true) } ?: return null
        val stream = tile.stream
        val container = qrCompanionChatContainer ?: FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            visibility = View.GONE
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            root.addView(this, FrameLayout.LayoutParams(1, 1))
            qrCompanionChatContainer = this
        }
        val resolvedId = tile.effectiveChannelId ?: stream.channelId
        val frag = ChatFragment.newInstance(resolvedId, stream.channelLogin, stream.channelName, stream.id)
        qrCompanionChatLogin = stream.channelLogin
        runCatching {
            childFragmentManager.beginTransaction()
                .replace(container.id, frag)
                .commitNowAllowingStateLoss()
        }
        return (childFragmentManager.findFragmentById(container.id) as? ChatFragment)?.takeIf { it.isAdded }
    }

    private fun syncQrCompanionChannels(preferredLogin: String? = null) {
        val ctx = context ?: return
        val tabs = tiles.mapNotNull { t ->
            val l = t.stream.channelLogin?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val n = t.stream.channelName?.takeIf { it.isNotBlank() } ?: l
            com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.ChannelTab(l, n, t.stream.channelImage)
        }
        com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.updateMultiStreamChannels(
            context = ctx,
            channels = tabs,
            preferredLogin = preferredLogin ?: selectedTile?.stream?.channelLogin,
            owner = this,
        ) { login ->
            getOrAttachQrChat(login)
        }
    }

    private fun computeTileChatLockMessage(tile: Tile): String? {
        val ctx = context ?: return null
        val login = tile.stream.channelLogin ?: return null
        val loginKey = login.lowercase()
        val channelId = tile.effectiveChannelId
        val userId = ctx.tokenPrefs().getString(C.USER_ID, null)
        if (!userId.isNullOrBlank() && !channelId.isNullOrBlank() && userId == channelId) {
            return null
        }
        val chat = findChatFragment(login)
        val badges = chat?.getUserRoomBadges()?.value ?: userBadgesCache[loginKey]
        if (badges != null && badges.any {
                it == "broadcaster" || it == "moderator" || it == "lead_moderator" ||
                    it == "vip" || it == "staff" || it == "admin" || it == "global_mod"
            }
        ) {
            return null
        }
        val roomState = chat?.getEffectiveRoomState()?.value ?: roomStateCache[loginKey] ?: return null
        if (roomState.subs == "1" && badges != null && "subscriber" !in badges && "founder" !in badges) {
            return com.github.andreyasadchy.xtra.util.chat.TvChatNotices.restrictionMessage(ctx, "SUBSCRIBERS_ONLY")
        }
        val followersMin = roomState.followers?.toIntOrNull()
        val followEntry = channelId?.takeIf { it.isNotBlank() }?.let { followCache[it] }
        if (followersMin != null && followersMin >= 0 && followEntry?.first == true) {
            val followedAt = followEntry.second
            if (followedAt == null) {
                return com.github.andreyasadchy.xtra.util.chat.TvChatNotices.restrictionMessage(
                    ctx,
                    "FOLLOWERS_ONLY",
                    followersMinutes = followersMin,
                )
            }
            if (followersMin > 0) {
                val unlockAt = followedAt + followersMin * 60_000L
                val now = System.currentTimeMillis()
                if (now < unlockAt) {
                    val remSec = (unlockAt - now + 999L) / 1000L
                    return com.github.andreyasadchy.xtra.util.chat.TvChatNotices.restrictionMessage(
                        ctx,
                        "msg_followersonly_followed",
                        followersMinutes = followersMin,
                        remainingSeconds = remSec,
                    )
                }
            }
        }
        return null
    }

    private fun prefetchTileChatState(tile: Tile) {
        val ctx = context ?: return
        if (ctx.prefs().getBoolean(C.CHAT_DISABLE, false) || !isTileChatLoggedIn(ctx)) {
            return
        }
        val login = tile.stream.channelLogin ?: return
        tile.chatProbeJob?.cancel()
        tile.chatProbeJob = viewLifecycleOwner.lifecycleScope.launch {
            ensureTileChatState(tile)
        }
    }

    private suspend fun ensureTileChatState(tile: Tile) {
        val ctx = context ?: return
        val login = tile.stream.channelLogin?.takeIf { it.isNotBlank() } ?: return
        val loginKey = login.lowercase()
        val app = ctx.applicationContext as com.github.andreyasadchy.xtra.XtraApp
        val networkLibrary = ctx.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = com.github.andreyasadchy.xtra.util.TwitchApiHelper.getGQLHeaders(ctx, true)
        val helixHeaders = com.github.andreyasadchy.xtra.util.TwitchApiHelper.getHelixHeaders(ctx)
        val userId = ctx.tokenPrefs().getString(C.USER_ID, null)
        var channelId = tile.effectiveChannelId
        if (channelId.isNullOrBlank()) {
            val user = runCatching {
                app.xtraModule.graphQLRepository.loadQueryUsersStream(
                    networkLibrary,
                    com.github.andreyasadchy.xtra.util.TwitchApiHelper.getGQLHeaders(ctx),
                    ids = null,
                    logins = listOf(login),
                ).data?.users?.firstOrNull()
            }.getOrNull()
            if (!user?.id.isNullOrBlank()) {
                channelId = user!!.id
                tile.resolvedChannelId = channelId
            }
        }
        if (!channelId.isNullOrBlank() && !followCache.containsKey(channelId)) {
            if (!userId.isNullOrBlank() && userId == channelId) {
                followCache[channelId] = true to 1L
            } else {
                var known = false
                val followedMs = runCatching {
                    val self = if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        runCatching {
                            app.xtraModule.graphQLRepository.loadQueryFollowingUser(
                                networkLibrary = networkLibrary,
                                headers = gqlHeaders,
                                id = channelId,
                                login = null,
                            ).data?.user?.self
                        }.getOrNull()
                    } else null
                    when {
                        self != null -> {
                            known = true
                            self.follower?.followedAt?.toString()?.let {
                                kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() ?: 1L
                            }
                        }
                        !helixHeaders[C.HEADER_TOKEN].isNullOrBlank() && !userId.isNullOrBlank() -> {
                            val first = app.xtraModule.helixRepository.getUserFollows(networkLibrary, helixHeaders, userId, channelId).data.firstOrNull()
                            known = true
                            first?.let { f -> f.followedAt?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: 1L }
                        }
                        else -> null
                    }
                }.getOrNull()
                if (known) {
                    followCache[channelId] = true to followedMs
                }
            }
        }
        if (findChatFragment(login) == null && !roomStateCache.containsKey(loginKey)) {
            val accountLogin = ctx.tokenPrefs().getString(C.USERNAME, null)
            val gqlToken = gqlHeaders[C.HEADER_TOKEN]?.removePrefix("OAuth ")?.takeIf { it.isNotBlank() }
            val helixToken = helixHeaders[C.HEADER_TOKEN]?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
            val oauthToken = gqlToken ?: helixToken
            val gotRoom = kotlinx.coroutines.CompletableDeferred<Unit>()
            val gotUser = kotlinx.coroutines.CompletableDeferred<Unit>()
            val useAuthSocket = !accountLogin.isNullOrBlank() && !oauthToken.isNullOrBlank()
            if (!useAuthSocket) {
                gotUser.complete(Unit)
            }
            val listener = object : com.github.andreyasadchy.xtra.util.chat.ChatReadWebSocket.Listener {
                override suspend fun onRoomState(message: com.github.andreyasadchy.xtra.util.chat.ChatUtils.IRCMessage) {
                    roomStateCache[loginKey] = com.github.andreyasadchy.xtra.model.chat.RoomState(
                        emote = message.tags["emote-only"],
                        followers = message.tags["followers-only"],
                        unique = message.tags["r9k"],
                        slow = message.tags["slow"],
                        subs = message.tags["subs-only"],
                    )
                    gotRoom.complete(Unit)
                }

                override suspend fun onUserState(message: com.github.andreyasadchy.xtra.util.chat.ChatUtils.IRCMessage) {
                    val badgeSet = message.tags["badges"]
                        ?.split(',')
                        ?.mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }
                        ?.toMutableSet()
                        ?: mutableSetOf()
                    if (message.tags["mod"] == "1") badgeSet.add("moderator")
                    if (message.tags["subscriber"] == "1") badgeSet.add("subscriber")
                    if (!message.tags["vip"].isNullOrBlank() && message.tags["vip"] != "0") badgeSet.add("vip")
                    userBadgesCache[loginKey] = badgeSet
                    gotUser.complete(Unit)
                }

                override suspend fun onDisconnect(message: String, fullMsg: String?) {
                    gotRoom.complete(Unit)
                    gotUser.complete(Unit)
                }
            }
            kotlinx.coroutines.supervisorScope {
                var writeSocket: com.github.andreyasadchy.xtra.util.chat.ChatWriteWebSocket? = null
                var readSocket: com.github.andreyasadchy.xtra.util.chat.ChatReadWebSocket? = null
                var job: kotlinx.coroutines.Job? = null
                try {
                    if (useAuthSocket) {
                        writeSocket = com.github.andreyasadchy.xtra.util.chat.ChatWriteWebSocket(accountLogin, oauthToken, login, listener)
                        job = writeSocket.connect(this)
                    } else {
                        readSocket = com.github.andreyasadchy.xtra.util.chat.ChatReadWebSocket(login, listener)
                        job = readSocket.connect(this)
                    }
                    kotlinx.coroutines.withTimeoutOrNull(2000L) {
                        gotRoom.await()
                        gotUser.await()
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { writeSocket?.disconnect(job) }
                    runCatching { readSocket?.disconnect(job) }
                }
            }
        }
    }

    private fun openTileChatComposer(tile: Tile) {
        val ctx = context ?: return
        val immediateLock = computeTileChatLockMessage(tile)
        if (immediateLock != null) {
            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(ctx, immediateLock, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val login = tile.stream.channelLogin ?: return
        val loginKey = login.lowercase()
        val channelId = tile.effectiveChannelId
        val chat = findChatFragment(login)
        val stateReady = !channelId.isNullOrBlank() && followCache.containsKey(channelId) &&
            (chat?.getEffectiveRoomState()?.value != null || roomStateCache.containsKey(loginKey))
        if (!stateReady) {
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.withTimeoutOrNull(1800L) {
                    ensureTileChatState(tile)
                }
                if (view == null || !isAdded || tile !in tiles) return@launch
                val lockAfterCheck = computeTileChatLockMessage(tile)
                if (lockAfterCheck != null) {
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), lockAfterCheck, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    showTileChatComposerDialog(tile)
                }
            }
            return
        }
        showTileChatComposerDialog(tile)
    }

    private fun showTileChatComposerDialog(tile: Tile) {
        val ctx = context ?: return
        val login = tile.stream.channelLogin ?: return
        val name = tile.stream.channelName?.takeIf { it.isNotBlank() } ?: login
        val chat = findChatFragment(login)
        val emoteGroupsState = androidx.compose.runtime.mutableStateOf(
            chat?.getTvEmoteGroups() ?: buildFallbackTileEmoteGroups(tile.effectiveChannelId)
        )
        if (chat == null) {
            loadFallbackTileEmotes(tile, emoteGroupsState)
        }
        val hostWindow = activity?.window
        val prevHostSoftInputMode = hostWindow?.attributes?.softInputMode
        hostWindow?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val dialog = com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(ctx) { dismiss ->
            if (chat != null) {
                androidx.compose.runtime.LaunchedEffect(chat) {
                    emoteGroupsState.value = chat.getTvEmoteGroups()
                    chat.observeEmoteUpdates()?.collect {
                        emoteGroupsState.value = chat.getTvEmoteGroups()
                    }
                }
            } else {
                androidx.compose.runtime.LaunchedEffect(tile.effectiveChannelId) {
                    com.github.andreyasadchy.xtra.ui.tv.TvEmoteUsage.revision.collect {
                        emoteGroupsState.value = buildFallbackTileEmoteGroups(tile.effectiveChannelId)
                    }
                }
            }
            val qrConnected by com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.connectedClients.collectAsState()
            com.github.andreyasadchy.xtra.ui.tv.ChatComposer(
                title = "${getString(R.string.tv_write_message)} · $name",
                hint = getString(R.string.tv_message_hint),
                sendLabel = getString(R.string.tv_send),
                emoteGroups = emoteGroupsState.value,
                pillStyle = true,
                onDismiss = dismiss,
                onOpenQr = {
                    dismiss()
                    val qrChat = getOrAttachQrChat(login)
                    syncQrCompanionChannels(login)
                    com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.openQrPanel(
                        context = ctx,
                        uiScope = viewLifecycleOwner.lifecycleScope,
                        chatFragment = qrChat,
                        channelName = name,
                        channelLogin = login,
                        channelAvatar = tile.stream.channelImage,
                        owner = this@MultiStreamFragment,
                    )
                },
                qrConnectedCount = qrConnected,
            ) { text ->
                if (text.isNotBlank()) {
                    val activeChat = findChatFragment(login)
                    if (activeChat != null) {
                        activeChat.sendExternalMessage(text)
                    } else {
                        sendDirectTileChatMessage(tile, text)
                    }
                }
                dismiss()
            }
        }
        dialog.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener {
            if (prevHostSoftInputMode != null) {
                hostWindow?.setSoftInputMode(prevHostSoftInputMode)
            }
        }
    }

    private fun buildFallbackTileEmoteGroups(channelId: String?): List<Pair<String, List<com.github.andreyasadchy.xtra.model.chat.Emote>>> {
        val ctx = context ?: return emptyList()
        val twitch = channelId?.let { twitchEmotesCache[it] }.orEmpty()
        val channelThirdParty = channelId?.let { thirdPartyEmotesCache[it] }.orEmpty()
        val mountedGroups = childFragmentManager.fragments
            .filterIsInstance<ChatFragment>()
            .firstOrNull { it.isAdded && it.view != null }
            ?.getTvEmoteGroups()
            .orEmpty()
        val fallbackTwitch = twitch.ifEmpty {
            mountedGroups.firstOrNull { it.first == "Twitch" }?.second.orEmpty()
                .filter { it.source != com.github.andreyasadchy.xtra.model.chat.Emote.CHANNEL_TWITCH }
        }
        val globalThirdParty = mountedGroups
            .firstOrNull { it.first == "7TV/BTTV/FFZ" }
            ?.second
            .orEmpty()
            .filterNot { it.isChannelEmote }
        val thirdParty = (channelThirdParty + globalThirdParty).distinctBy { it.name }
        val channelEmotes = (
            twitch.filter { it.source == com.github.andreyasadchy.xtra.model.chat.Emote.CHANNEL_TWITCH } +
                channelThirdParty
            ).distinctBy { it.name }
        val combined = (fallbackTwitch + thirdParty).distinctBy { it.name }
        val mostUsed = com.github.andreyasadchy.xtra.ui.tv.TvEmoteUsage.sortMostUsed(ctx, combined)
        return buildList {
            add(ctx.getString(R.string.tv_emotes_most_used) to mostUsed)
            if (channelEmotes.isNotEmpty()) add(ctx.getString(R.string.tv_emotes_channel) to channelEmotes)
            if (fallbackTwitch.isNotEmpty()) add("Twitch" to fallbackTwitch.distinctBy { it.name })
            if (thirdParty.isNotEmpty()) add("7TV/BTTV/FFZ" to thirdParty)
        }
    }

    private fun loadFallbackTileEmotes(
        tile: Tile,
        state: androidx.compose.runtime.MutableState<List<Pair<String, List<com.github.andreyasadchy.xtra.model.chat.Emote>>>>,
    ) {
        val ctx = context ?: return
        val login = tile.stream.channelLogin ?: return
        val app = ctx.applicationContext as com.github.andreyasadchy.xtra.XtraApp
        val prefs = ctx.prefs()
        val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = com.github.andreyasadchy.xtra.util.TwitchApiHelper.getGQLHeaders(ctx, true)
        val helixHeaders = com.github.andreyasadchy.xtra.util.TwitchApiHelper.getHelixHeaders(ctx)
        val userId = ctx.tokenPrefs().getString(C.USER_ID, null)
        val animateGifs = prefs.getBoolean(C.ANIMATED_EMOTES, true)
        val enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
        val useWebp = prefs.getBoolean(C.CHAT_USE_WEBP, true)
        val enableStv = prefs.getBoolean(C.CHAT_ENABLE_STV, true)
        val enableBttv = prefs.getBoolean(C.CHAT_ENABLE_BTTV, true)
        val enableFfz = prefs.getBoolean(C.CHAT_ENABLE_FFZ, true)
        viewLifecycleOwner.lifecycleScope.launch {
            var channelId = tile.effectiveChannelId
            if (channelId.isNullOrBlank()) {
                channelId = runCatching {
                    app.xtraModule.graphQLRepository.loadQueryUsersStream(
                        networkLibrary,
                        com.github.andreyasadchy.xtra.util.TwitchApiHelper.getGQLHeaders(ctx),
                        ids = null,
                        logins = listOf(login),
                    ).data?.users?.firstOrNull()?.id
                }.getOrNull()
                if (!channelId.isNullOrBlank()) {
                    tile.resolvedChannelId = channelId
                }
            }
            val resolvedId = channelId ?: return@launch
            if (twitchEmotesCache.containsKey(resolvedId) && thirdPartyEmotesCache.containsKey(resolvedId)) {
                state.value = buildFallbackTileEmoteGroups(resolvedId)
                return@launch
            }
            if (!twitchEmotesCache.containsKey(resolvedId)) {
                val loadedTwitch = runCatching {
                    app.xtraModule.playerRepository.loadUserEmotes(
                        networkLibrary = networkLibrary,
                        helixHeaders = helixHeaders,
                        gqlHeaders = gqlHeaders,
                        channelId = resolvedId,
                        userId = userId,
                        animateGifs = animateGifs,
                        enableIntegrity = enableIntegrity,
                    ).sortedByDescending { it.setId }
                        .sortedByDescending { it.ownerId == resolvedId }
                        .map {
                            com.github.andreyasadchy.xtra.model.chat.Emote(
                                name = it.name,
                                url1x = it.url1x,
                                url2x = it.url2x,
                                url3x = it.url3x,
                                url4x = it.url4x,
                                format = it.format,
                                source = if (it.ownerId == resolvedId) com.github.andreyasadchy.xtra.model.chat.Emote.CHANNEL_TWITCH else null,
                            )
                        }
                        .distinctBy { it.name }
                }.getOrNull()
                if (!loadedTwitch.isNullOrEmpty()) {
                    twitchEmotesCache[resolvedId] = loadedTwitch
                    if (isAdded) state.value = buildFallbackTileEmoteGroups(resolvedId)
                }
            }
            if (!thirdPartyEmotesCache.containsKey(resolvedId)) {
                val repo = app.xtraModule.playerRepository
                val channel3p = mutableListOf<com.github.andreyasadchy.xtra.model.chat.Emote>()
                if (enableStv) {
                    runCatching {
                        val userResp = repo.loadSTVUserResponse(networkLibrary, resolvedId)
                        val (setId, direct) = repo.loadSTVUser(userResp, useWebp)
                        when {
                            !direct.isNullOrEmpty() -> channel3p.addAll(direct)
                            !setId.isNullOrBlank() -> {
                                val setResp = repo.loadSTVEmoteSetResponse(networkLibrary, setId)
                                channel3p.addAll(repo.loadSTVEmoteSet(setResp, useWebp, false).second)
                            }
                        }
                    }
                }
                if (enableBttv) {
                    runCatching {
                        val resp = repo.loadBTTVEmotesResponse(networkLibrary, resolvedId)
                        channel3p.addAll(repo.loadBTTVEmotes(resp, useWebp))
                    }
                }
                if (enableFfz) {
                    runCatching {
                        val resp = repo.loadFFZEmotesResponse(networkLibrary, resolvedId)
                        channel3p.addAll(repo.loadFFZEmotes(resp, useWebp))
                    }
                }
                thirdPartyEmotesCache[resolvedId] = channel3p.distinctBy { it.name }
                if (isAdded) state.value = buildFallbackTileEmoteGroups(resolvedId)
            }
        }
    }

    private fun sendDirectTileChatMessage(tile: Tile, rawMessage: String) {
        val ctx = context ?: return
        val trimmed = rawMessage.trim()
        if (trimmed.isEmpty()) return
        val appCtx = ctx.applicationContext
        val app = appCtx as com.github.andreyasadchy.xtra.XtraApp
        val login = tile.stream.channelLogin ?: return
        val loginKey = login.lowercase()
        val initialChannelId = tile.effectiveChannelId
        val networkLibrary = appCtx.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = com.github.andreyasadchy.xtra.util.TwitchApiHelper.getGQLHeaders(appCtx, true)
        val helixHeaders = com.github.andreyasadchy.xtra.util.TwitchApiHelper.getHelixHeaders(appCtx)
        val accountId = appCtx.tokenPrefs().getString(C.USER_ID, null)
        val knownEmoteNamesSnapshot = buildSet {
            buildFallbackTileEmoteGroups(initialChannelId).forEach { (_, list) ->
                list.forEach { emote -> emote.name?.takeIf { it.isNotBlank() }?.let(::add) }
            }
        }
        if (!trimmed.startsWith("/")) {
            com.github.andreyasadchy.xtra.ui.tv.TvStats.addMessage(appCtx)
        }
        kotlinx.coroutines.MainScope().launch {
            try {
                var channelId = initialChannelId
                if (channelId.isNullOrBlank()) {
                    channelId = runCatching {
                        app.xtraModule.graphQLRepository.loadQueryUsersStream(
                            networkLibrary,
                            com.github.andreyasadchy.xtra.util.TwitchApiHelper.getGQLHeaders(appCtx),
                            ids = null,
                            logins = listOf(login),
                        ).data?.users?.firstOrNull()?.id
                    }.getOrNull()
                    if (!channelId.isNullOrBlank()) {
                        tile.resolvedChannelId = channelId
                    }
                }
                if (channelId.isNullOrBlank()) return@launch
                val followersMin = roomStateCache[loginKey]?.followers?.toIntOrNull()?.takeIf { it > 0 }
                var sentSuccess = false
                val toastMsg: String? = if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val response = app.xtraModule.graphQLRepository.sendMessage(networkLibrary, gqlHeaders, channelId, trimmed, null)
                    val dropReason = response.data?.sendChatMessage?.dropReason
                    val dropMessage = response.data?.sendChatMessage?.dropReasonMessage
                    when {
                        !dropReason.isNullOrBlank() -> {
                            if (dropReason == "SUBSCRIBERS_ONLY" && !userBadgesCache.containsKey(loginKey)) {
                                userBadgesCache[loginKey] = emptySet()
                            }
                            com.github.andreyasadchy.xtra.util.chat.TvChatNotices.restrictionMessage(appCtx, dropReason, dropMessage, followersMin)
                        }
                        !response.errors.isNullOrEmpty() -> {
                            val errMsg = response.errors
                                .mapNotNull { it.message?.takeIf(String::isNotBlank) }
                                .joinToString("; ")
                                .ifBlank { appCtx.getString(R.string.tv_chat_restricted_generic) }
                            com.github.andreyasadchy.xtra.util.chat.TvChatNotices.restrictionMessage(appCtx, null, errMsg)
                        }
                        else -> {
                            sentSuccess = true
                            null
                        }
                    }
                } else if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val raw = app.xtraModule.helixRepository.sendMessage(networkLibrary, helixHeaders, accountId, channelId, trimmed, null)
                    when {
                        raw != null && raw.startsWith("DROP:") -> {
                            val payload = raw.removePrefix("DROP:")
                            val code = payload.substringBefore('|')
                            val msg = payload.substringAfter('|', "")
                            if (code == "msg_subsonly" && !userBadgesCache.containsKey(loginKey)) {
                                userBadgesCache[loginKey] = emptySet()
                            }
                            com.github.andreyasadchy.xtra.util.chat.TvChatNotices.restrictionMessage(appCtx, code, msg, followersMin)
                        }
                        !raw.isNullOrBlank() -> com.github.andreyasadchy.xtra.util.chat.TvChatNotices.restrictionMessage(appCtx, null, raw)
                        else -> {
                            sentSuccess = true
                            null
                        }
                    }
                } else null
                if (sentSuccess) {
                    val knownNames = buildSet {
                        addAll(knownEmoteNamesSnapshot)
                        twitchEmotesCache[channelId]?.forEach { e -> e.name?.takeIf { it.isNotBlank() }?.let(::add) }
                        thirdPartyEmotesCache[channelId]?.forEach { e -> e.name?.takeIf { it.isNotBlank() }?.let(::add) }
                    }
                    com.github.andreyasadchy.xtra.ui.tv.TvEmoteUsage.recordSentMessage(appCtx, trimmed, knownNames)
                }
                if (!toastMsg.isNullOrBlank()) {
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(appCtx, toastMsg, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showQualityPanel(tile: Tile) {
        val context = context ?: return
        val name = tile.stream.channelName ?: tile.stream.channelLogin.orEmpty()
        TvMultiStreamQuality.showPanel(
            context = context,
            streamTitle = name.ifBlank { null },
            initialStreamOverride = tile.qualityOverrideHeight,
            onStreamOverride = { overrideHeight ->
                if (view != null && tile in tiles) {
                    tile.qualityOverrideHeight = overrideHeight
                    resetQualityStepDown(tile)
                    updateVideoSizes()
                }
            },
            onChanged = {
                if (view != null) {
                    tiles.forEach { resetQualityStepDown(it) }
                    updateVideoSizes()
                }
            },
        )
    }

    /** Live alert shortcut: adds [stream] directly to the active multi-stream when a slot is free. */
    fun addStreamFromAlert(stream: Stream): Boolean {
        if (view == null || tiles.size >= MAX || stream.channelLogin.isNullOrBlank()) {
            return false
        }
        if (tiles.any { it.stream.channelLogin.equals(stream.channelLogin, ignoreCase = true) }) {
            return false
        }
        val added = addTile(stream)
        updateChats()
        updateCellChat()
        added.view.requestFocus()
        return true
    }

    /** 3 or 4 streams: a stream leaves, the others take the screen (back to 2: both chats with their streams). */
    private fun removeTile(tile: Tile) {
        val login = tile.stream.channelLogin
        tile.adPulseAnimator?.cancel()
        tile.adPulseAnimator = null
        tile.stateListener?.let { tile.player.removeListener(it) }
        tile.stateListener = null
        tile.adListener?.let { tile.player.removeListener(it) }
        tile.adListener = null
        tile.recoveryJob?.cancel()
        tile.playJob?.cancel()
        tile.expUpgradeJob?.cancel()
        tile.experimentalVodActive = false
        tile.experimentalCreatedAtMs = null
        tile.backJob?.cancel()
        tile.raiseJob?.cancel()
        tile.infoJob?.cancel()
        tile.chatProbeJob?.cancel()
        tile.offlinePollJob?.cancel()
        tile.offlinePollJob = null
        tile.isOffline = false
        tile.offlineOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        tile.offlineOverlay = null
        tile.dynamicsProcessing?.let {
            runCatching { it.release() }
            tile.dynamicsProcessing = null
        }
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
        syncQrCompanionChannels()
        tiles.firstOrNull()?.view?.requestFocus()
    }

    /** The focus back on the stream selected last (else the first one), when it went anywhere else. */
    fun requestTileFocus(): Boolean {
        if (view == null) {
            return false
        }
        val tile = selectedTile?.takeIf { it in tiles } ?: tiles.firstOrNull() ?: return false
        return tile.view.requestFocus()
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
        tile.resolvedChannelId = null
        tile.qualityOverrideHeight = null
        tile.onBackup = false
        tile.adMuted = false
        tile.experimentalVodActive = false
        tile.experimentalCreatedAtMs = null
        tile.playJob?.cancel()
        tile.playJob = null
        tile.expUpgradeJob?.cancel()
        tile.expUpgradeJob = null
        tile.recoveryAttempt = 0
        tile.recoveryJob?.cancel()
        tile.recoveryJob = null
        updateAdBadge(tile)
        tile.label.text = stream.channelName ?: stream.channelLogin
        tile.stateListener?.let { tile.player.removeListener(it) }
        tile.stateListener = null
        tile.adListener?.let { tile.player.removeListener(it) }
        tile.adListener = null
        tile.player.stop()
        tile.player.clearMediaItems()
        updateVideoSizes()
        loadStream(tile)
        updateTileInfo(tile)
        // the volume of the new stream (mix)
        applySound()
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

    private data class FocusMetrics(
        val bigWidth: Int,
        val bigHeight: Int,
        val bigLeft: Int,
        val smallWidth: Int,
        val smallHeight: Int,
        val smallStart: Int,
        val smallTop: Int,
    )

    private fun computeFocusMetrics(width: Int, height: Int, smallCount: Int, chatDisabled: Boolean): FocusMetrics {
        val count = smallCount.coerceAtLeast(1)
        val smallWidth = minOf(width / count, (height / 3) * 16 / 9)
        val smallHeight = smallWidth * 9 / 16
        val topAvailableH = (height - smallHeight).coerceAtLeast(1)
        val minChatW = if (chatDisabled) 0 else width / 5
        val bigHeight = minOf(topAvailableH, (width - minChatW) * 9 / 16)
        val bigWidth = bigHeight * 16 / 9
        val bigLeft = if (chatDisabled) (width - bigWidth) / 2 else 0
        val smallStart = (width - smallWidth * count) / 2
        val smallTop = bigHeight + (height - bigHeight - smallHeight) / 2
        return FocusMetrics(bigWidth, bigHeight, bigLeft, smallWidth, smallHeight, smallStart, smallTop)
    }

    private fun layoutTiles() {
        // the quality follows the place of each stream (big or small in focus / mosaic mode)
        updateVideoSizes()
        val width = root.width
        val height = root.height
        if (width == 0 || height == 0) {
            return
        }
        val count = tiles.size
        val chatDisabled = context?.prefs()?.getBoolean(C.CHAT_DISABLE, false) == true
        if (isFocusLayout()) {
            // the big stream at the top left (16:9, sized to touch the 16:9 bottom strip with zero gap), its chat at its right
            val big = bigTile()
            val smalls = tiles.filter { it !== big }
            val m = computeFocusMetrics(width, height, smalls.size, chatDisabled)
            big?.let {
                applyParams(it.view, FrameLayout.LayoutParams(m.bigWidth, m.bigHeight).apply {
                    leftMargin = m.bigLeft
                    topMargin = 0
                })
            }
            smalls.forEachIndexed { index, tile ->
                applyParams(tile.view, FrameLayout.LayoutParams(m.smallWidth, m.smallHeight).apply {
                    leftMargin = m.smallStart + index * m.smallWidth
                    topMargin = m.smallTop
                })
            }
            layoutChats()
            return
        }
        if (isMosaicLayout()) {
            // 5 or 6 streams mosaic: 2/3×2/3 big stream at top-left, surrounded by 1/3×1/3 16:9 cells (100% screen filled)
            val hasCellChat = count == 5 && !chatDisabled && !cellChatHidden
            val big = bigTile()
            val smalls = tiles.filter { it !== big }
            val colW = width / 3
            val lastColW = width - colW * 2
            val rowH = height / 3
            val lastRowH = height - rowH * 2
            big?.let {
                applyParams(it.view, FrameLayout.LayoutParams(colW * 2, rowH * 2))
            }
            smalls.forEachIndexed { sIdx, tile ->
                val params = when (sIdx) {
                    0 -> FrameLayout.LayoutParams(lastColW, rowH).apply {
                        leftMargin = colW * 2
                        topMargin = 0
                    }
                    1 -> FrameLayout.LayoutParams(lastColW, rowH).apply {
                        leftMargin = colW * 2
                        topMargin = rowH
                    }
                    2, 3 -> {
                        val bottomIdx = sIdx - 2
                        val startLeft = if (count == 5 && !hasCellChat) (width - colW * 2) / 2 else 0
                        FrameLayout.LayoutParams(colW, lastRowH).apply {
                            leftMargin = startLeft + bottomIdx * colW
                            topMargin = rowH * 2
                        }
                    }
                    else -> FrameLayout.LayoutParams(lastColW, lastRowH).apply {
                        leftMargin = colW * 2
                        topMargin = rowH * 2
                    }
                }
                applyParams(tile.view, params)
            }
            layoutChats()
            return
        }
        tiles.forEachIndexed { index, tile ->
            val params = when (count) {
                1 -> FrameLayout.LayoutParams(width, height)
                2 -> {
                    if (stacked2) {
                        // S0undTV-style double stream: 2 16:9 streams stacked vertically in the center, full-height chats on the sides
                        val topH = height / 2
                        val tileH = if (index == 0) topH else height - topH
                        val refH = maxOf(topH, height - topH)
                        val tileW = minOf(refH * 16 / 9, width)
                        FrameLayout.LayoutParams(tileW, tileH).apply {
                            leftMargin = (width - tileW) / 2
                            topMargin = if (index == 0) 0 else topH
                        }
                    } else {
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
                }
                3, 4 -> {
                    val hasCellChat = count == 3 && !chatDisabled && !cellChatHidden
                    val row1Start = if (count == 3 && !hasCellChat) (width - width / 2) / 2 else 0
                    FrameLayout.LayoutParams(width / 2, height / 2).apply {
                        leftMargin = if (index >= 2 && count == 3) row1Start else (index % 2) * (width / 2)
                        topMargin = (index / 2) * (height / 2)
                    }
                }
                else -> {
                    // 5 or 6 streams: 3x2 grid with exact 16:9 tiles
                    val tileW = width / 3
                    val tileH = minOf(height / 2, tileW * 9 / 16)
                    val blockTop = (height - tileH * 2) / 2
                    val hasCellChat = count == 5 && !chatDisabled && !cellChatHidden
                    val row = index / 3
                    val col = index % 3
                    val row1Start = if (count == 5 && !hasCellChat) (width - tileW * 2) / 2 else 0
                    FrameLayout.LayoutParams(tileW, tileH).apply {
                        leftMargin = if (row == 1 && count == 5) row1Start + col * tileW else col * tileW
                        topMargin = blockTop + row * tileH
                    }
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

    /** 3 or 5 streams: the chat of the empty cell, the pinned one or the one of the selected stream. */
    private fun updateCellChat() {
        if (view == null) {
            return
        }
        val focused = (tiles.find { it.view.hasFocus() } ?: selectedTile?.takeIf { it in tiles } ?: tiles.firstOrNull())?.stream
        val chatDisabled = requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
        val stream = when {
            chatDisabled -> null
            // focus mode: the chat of the big stream, at its right
            isFocusLayout() -> bigTile()?.stream
            (tiles.size == 3 || tiles.size == 5) && !cellChatHidden -> tiles.find { it.stream.channelLogin == pinnedChat }?.stream ?: focused
            else -> null
        }
        val login = stream?.channelLogin
        if (login == cellChatLogin) {
            layoutTiles()
            return
        }
        cellChat?.let { removeChat(it) }
        cellChat = null
        cellChatLogin = login
        if (stream != null) {
            cellChat = addChat(stream)
        }
        layoutTiles()
    }

    private fun layoutChats() {
        val width = root.width
        val height = root.height
        if (width == 0 || height == 0) {
            return
        }
        tiles.forEachIndexed { index, tile ->
            val container = tile.chatContainer ?: return@forEachIndexed
            val tileParams = tile.view.layoutParams as? FrameLayout.LayoutParams ?: return@forEachIndexed
            if (tiles.size == 2 && stacked2) {
                val sideWidth = ((width - tileParams.width) / 2).coerceAtLeast(0)
                val onLeft = if (!swapSideChats2) index == 0 else index == 1
                val chatW = if (onLeft) sideWidth else (width - tileParams.width - sideWidth).coerceAtLeast(0)
                val chatLeft = if (onLeft) 0 else sideWidth + tileParams.width
                applyParams(container, FrameLayout.LayoutParams(chatW, height).apply {
                    leftMargin = chatLeft
                    topMargin = 0
                })
            } else {
                val onTop = tile.stream.channelLogin in chatsOnTop
                applyParams(container, FrameLayout.LayoutParams(tileParams.width, height - tileParams.height).apply {
                    leftMargin = tileParams.leftMargin
                    topMargin = if (onTop) 0 else tileParams.height
                })
            }
        }
        cellChat?.let {
            when {
                isFocusLayout() -> {
                    val smallCount = (tiles.size - 1).coerceAtLeast(1)
                    val m = computeFocusMetrics(width, height, smallCount, chatDisabled = false)
                    applyParams(it, FrameLayout.LayoutParams(width - m.bigWidth, m.bigHeight).apply {
                        leftMargin = m.bigWidth
                        topMargin = 0
                    })
                }
                tiles.size == 5 && isMosaicLayout() -> {
                    val colW = width / 3
                    val rowH = height / 3
                    applyParams(it, FrameLayout.LayoutParams(width - colW * 2, height - rowH * 2).apply {
                        leftMargin = colW * 2
                        topMargin = rowH * 2
                    })
                }
                tiles.size == 5 -> {
                    val tileW = width / 3
                    val tileH = minOf(height / 2, tileW * 9 / 16)
                    val blockTop = (height - tileH * 2) / 2
                    applyParams(it, FrameLayout.LayoutParams(width - tileW * 2, tileH).apply {
                        leftMargin = tileW * 2
                        topMargin = blockTop + tileH
                    })
                }
                else -> {
                    applyParams(it, FrameLayout.LayoutParams(width / 2, height / 2).apply {
                        leftMargin = width / 2
                        topMargin = height / 2
                    })
                }
            }
        }
    }

    fun isExperimentalVodActive(channelLogin: String?): Boolean =
        tiles.find { it.stream.channelLogin == channelLogin }?.experimentalVodActive == true

    fun shouldHoldExperimentalChat(channelLogin: String?): Boolean {
        val tile = tiles.find { it.stream.channelLogin == channelLogin } ?: return false
        if (tile.experimentalVodActive) return true
        val ctx = context ?: return false
        if (!com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(ctx)) return false
        return tile.playJob?.isActive == true || tile.player.playbackState != Player.STATE_READY
    }

    /** The chat of a stream with its delay in Auto: the time of Twitch of the picture of its tile (null when not known). */
    fun getLivePictureTime(channelLogin: String?): Long? {
        val tile = tiles.find { it.stream.channelLogin == channelLogin } ?: return null
        val player = tile.player
        val timeline = player.currentTimeline
        if (timeline.isEmpty || player.currentMediaItemIndex !in 0 until timeline.windowCount) return null
        val window = androidx.media3.common.Timeline.Window()
        timeline.getWindow(player.currentMediaItemIndex, window)
        if (window.windowStartTimeMs != androidx.media3.common.C.TIME_UNSET) {
            return window.windowStartTimeMs + player.currentPosition
        }
        if (tile.experimentalVodActive) {
            val baseMs = tile.experimentalCreatedAtMs
                ?: com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.parseEpochMs(tile.stream.createdAt)
            if (baseMs != null) {
                return baseMs + player.currentPosition.coerceAtLeast(0L)
            }
        }
        if (window.isDynamic) {
            val bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            val prefetchMs = (player.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest)
                ?.mediaPlaylist?.tags?.count { it.startsWith("#EXT-X-TWITCH-PREFETCH") }?.times(2_000L) ?: 0L
            val horizonMs = if (window.durationMs != androidx.media3.common.C.TIME_UNSET && window.durationMs > 0L) {
                (window.durationMs - prefetchMs - player.currentPosition).coerceAtLeast(0L)
            } else {
                0L
            }
            val delayMs = maxOf(horizonMs, bufferedMs).coerceAtLeast(0L)
            if (com.github.andreyasadchy.xtra.util.TwitchClock.isCalibrated() || delayMs > 0L) {
                return com.github.andreyasadchy.xtra.util.TwitchClock.nowMs() - delayMs
            }
        }
        return null
    }

    fun getStreamWatchMetadata(login: String?): com.github.andreyasadchy.xtra.ui.chat.ChatViewModel.StreamWatchMetadata? {
        if (login == null) return null
        val tile = tiles.find { it.stream.channelLogin.equals(login, true) } ?: return null
        val prefs = context?.prefs()
        val usingProxy = prefs?.getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false) == true ||
            prefs?.getBoolean(C.PLAYER_USE_STREAM_PROXY, false) == true
        return com.github.andreyasadchy.xtra.ui.chat.ChatViewModel.StreamWatchMetadata(
            streamId = tile.stream.id,
            gameId = tile.stream.gameId,
            gameName = tile.stream.gameName,
            needsPresencePing = tile.experimentalVodActive || usingProxy,
        )
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
        val resolvedId = tiles.find { it.stream.channelLogin == stream.channelLogin }?.effectiveChannelId ?: stream.channelId
        childFragmentManager.beginTransaction()
            .replace(container.id, ChatFragment.newInstance(resolvedId, stream.channelLogin, stream.channelName, stream.id))
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
                stream.thumbnail?.also { com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.remember(stream) }?.takeUnless { stream.thumbnailURL.isNullOrBlank() },
                stream.channelImage, viewLifecycleOwner.lifecycleScope
            ) { view != null && tile in tiles && tile.stream.channelLogin == login && (tile.texture?.alpha ?: 1f) < 1f }
        }
        tile.playingSince = 0L
        tile.qualityStepsDown = 0
        tile.stops.clear()
        tile.raiseJob?.cancel()
        tile.infoJob?.cancel()
        tile.chatProbeJob?.cancel()
        clearTileOfflineOverlay(tile, immediate = true)
        tile.expUpgradeJob?.cancel()
        tile.expUpgradeJob = null
        tile.experimentalVodActive = false
        tile.experimentalCreatedAtMs = null
        if (tile.effectiveChannelId.isNullOrBlank() || tile.stream.createdAt.isNullOrBlank() || tile.stream.title.isNullOrBlank() || tile.stream.viewerCount == null) {
            tile.infoJob = viewLifecycleOwner.lifecycleScope.launch {
                val context = context ?: return@launch
                val app = context.applicationContext as com.github.andreyasadchy.xtra.XtraApp
                val user = runCatching {
                    app.xtraModule.graphQLRepository.loadQueryUsersStream(
                        context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        com.github.andreyasadchy.xtra.util.TwitchApiHelper.getGQLHeaders(context),
                        ids = tile.effectiveChannelId?.let { listOf(it) },
                        logins = if (tile.effectiveChannelId.isNullOrBlank()) listOf(login) else null,
                    ).data?.users?.firstOrNull()
                }.getOrNull() ?: return@launch
                if (view == null || tile !in tiles || tile.stream.channelLogin != login) {
                    return@launch
                }
                if (tile.effectiveChannelId.isNullOrBlank() && !user.id.isNullOrBlank()) {
                    tile.resolvedChannelId = user.id
                }
                user.stream?.let { s ->
                    if (tile.stream.createdAt.isNullOrBlank()) tile.stream.createdAt = s.createdAt?.toString()
                    if (tile.stream.viewerCount == null) tile.stream.viewerCount = s.viewersCount
                    if (tile.stream.title.isNullOrBlank()) tile.stream.title = s.broadcaster?.broadcastSettings?.title
                    if (tile.stream.gameId.isNullOrBlank()) tile.stream.gameId = s.game?.id
                    if (tile.stream.gameSlug.isNullOrBlank()) tile.stream.gameSlug = s.game?.slug
                    if (tile.stream.gameName.isNullOrBlank()) tile.stream.gameName = s.game?.displayName
                    updateTileInfo(tile)
                }
                prefetchTileChatState(tile)
            }
        } else {
            prefetchTileChatState(tile)
        }
        tile.stateListener?.let { player.removeListener(it) }
        tile.stateListener = null
        tile.adListener?.let { player.removeListener(it) }
        tile.adListener = null
        tile.recoveryJob?.cancel()
        tile.recoveryJob = null
        tile.recoveryAttempt = 0
        playStream(login, player, tile)
        // the stream ended (no more playlist, or the end of it): it leaves the multi-stream after a few seconds
        val stateListener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if ((error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode == 404) {
                    onStreamEnded(tile, login)
                } else if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED
                ) {
                    if (stepDownOnDecoderFailure(tile, login)) {
                        return
                    }
                    if (!pausedByAudioFocus && !leaveMuted) {
                        scheduleTileRecovery(tile, login)
                    }
                } else if (!pausedByAudioFocus && !leaveMuted) {
                    scheduleTileRecovery(tile, login)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (view != null && tile in tiles) {
                    onTileState(tile, playbackState)
                }
                if (playbackState == Player.STATE_READY) {
                    tile.recoveryAttempt = 0
                    if (tile.isOffline) {
                        clearTileOfflineOverlay(tile)
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    onStreamEnded(tile, login)
                }
            }
        }
        tile.stateListener = stateListener
        player.addListener(stateListener)
        if (com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isSwapAdblockEnabled(requireContext())) {
            var lastSwitch = 0L
            val listener = object : Player.Listener {
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    if (tile.experimentalVodActive || view == null || tile !in tiles || tile.stream.channelLogin != login) return
                    val playlist = (player.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest)?.mediaPlaylist ?: return
                    val ads = playlist.tags.any {
                        it.contains("stitched-ad", true) ||
                            it.contains("X-TV-TWITCH-AD", true) ||
                            it.contains("twitch-stitched-ad", true) ||
                            it.contains("COMMERCIAL-BREAK", true)
                    }
                    val now = System.currentTimeMillis()
                    if (ads) {
                        if (!tile.onBackup) {
                            lastSwitch = now
                            playStream(login, player, tile, backup = true)
                        } else {
                            if (!tile.adMuted) {
                                tile.adMuted = true
                                applySound()
                            }
                            if (now - lastSwitch > 4_000L) {
                                lastSwitch = now
                                playStream(login, player, tile, backup = true)
                            }
                        }
                    } else if (tile.adMuted) {
                        tile.adMuted = false
                        applySound()
                    }
                }
            }
            tile.adListener = listener
            player.addListener(listener)
        }
    }

    private val endingLogins = mutableSetOf<String>()

    private fun clearTileOfflineOverlay(tile: Tile, immediate: Boolean = false) {
        tile.offlinePollJob?.cancel()
        tile.offlinePollJob = null
        tile.isOffline = false
        val overlay = tile.offlineOverlay ?: return
        tile.offlineOverlay = null
        overlay.animate().cancel()
        if (immediate) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        } else {
            overlay.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }
                .start()
        }
    }

    private fun attachTileOfflineOverlay(
        tile: Tile,
        info: com.github.andreyasadchy.xtra.repository.GraphQLRepository.ChannelOfflineInfo?,
        login: String,
    ) {
        val ctx = context ?: return
        if (tile.offlineOverlay != null && tile.isOffline) return
        tile.isOffline = true
        val overlay = TvOfflineOverlay.createView(
            context = ctx,
            displayName = info?.displayName ?: tile.stream.channelName?.takeIf { it.isNotBlank() } ?: login,
            profileImageUrl = info?.profileImageUrl ?: tile.stream.channelImage,
            offlineImageUrl = info?.offlineImageUrl,
            bannerImageUrl = info?.bannerImageUrl,
            compact = true,
            showWaitingHint = false,
        ).apply {
            alpha = 0f
        }
        tile.offlineOverlay?.let { prev -> (prev.parent as? ViewGroup)?.removeView(prev) }
        val insertIndex = tile.texture?.let { tex -> tile.view.indexOfChild(tex) + 1 }?.takeIf { it > 0 } ?: tile.view.childCount
        tile.view.addView(
            overlay,
            insertIndex,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        tile.offlineOverlay = overlay
        overlay.animate().alpha(1f).setDuration(220L).start()
    }

    private fun startTileOfflineLiveMonitor(tile: Tile, login: String) {
        tile.offlinePollJob?.cancel()
        tile.offlinePollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (tile.isOffline && view != null && tile in tiles && tile.stream.channelLogin == login) {
                kotlinx.coroutines.delay(18_000L)
                if (!tile.isOffline || view == null || tile !in tiles || tile.stream.channelLogin != login) {
                    return@launch
                }
                val ctx = context ?: return@launch
                val info = TvOfflineOverlay.fetchInfo(ctx, login)
                if (info?.isLive == true && tile.isOffline && view != null && tile in tiles && tile.stream.channelLogin == login) {
                    clearTileOfflineOverlay(tile)
                    loadStream(tile)
                    return@launch
                }
            }
        }
    }

    private fun onStreamEnded(tile: Tile, login: String) {
        if (tile.isOffline || !endingLogins.add(login)) {
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context
            val info = if (ctx != null) TvOfflineOverlay.fetchInfo(ctx, login) else null
            if (view == null || tile !in tiles || tile.stream.channelLogin != login) {
                endingLogins.remove(login)
                return@launch
            }
            if (info?.isLive == true) {
                endingLogins.remove(login)
                if (!tile.player.isPlaying) {
                    scheduleTileRecovery(tile, login)
                }
                return@launch
            }
            attachTileOfflineOverlay(tile, info, login)
            kotlinx.coroutines.delay(6000L)
            endingLogins.remove(login)
            if (view == null || tile !in tiles || tile.stream.channelLogin != login || tile.player.isPlaying) {
                if (tile.player.isPlaying) clearTileOfflineOverlay(tile)
                return@launch
            }
            val endAction = requireContext().prefs().getString(C.TV_STREAM_END_ACTION, "home") ?: "home"
            if (endAction == "stay") {
                // Respect "Stay on player": keep the offline screen visible in the tile and auto-reconnect if live again
                startTileOfflineLiveMonitor(tile, login)
                return@launch
            }
            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), getString(R.string.tv_multistream_stream_ended, tile.stream.channelName ?: login), android.widget.Toast.LENGTH_LONG).show()
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

    private fun scheduleTileRecovery(tile: Tile, login: String) {
        if (view == null || tile !in tiles || tile.stream.channelLogin != login || login in endingLogins) {
            return
        }
        if (tile.recoveryJob?.isActive == true) {
            return
        }
        val attempt = tile.recoveryAttempt++
        val delays = longArrayOf(2000L, 4000L, 8000L, 15_000L)
        val delayMs = delays[attempt.coerceAtMost(delays.lastIndex)]
        tile.recoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (view == null || tile !in tiles || tile.stream.channelLogin != login || pausedByAudioFocus || leaveMuted) {
                return@launch
            }
            if (tile.player.isPlaying) {
                tile.recoveryAttempt = 0
                return@launch
            }
            if (attempt >= 1) {
                val ctx = context ?: return@launch
                val app = ctx.applicationContext as com.github.andreyasadchy.xtra.XtraApp
                val user = runCatching {
                    app.xtraModule.graphQLRepository.loadQueryUsersStream(
                        ctx.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        com.github.andreyasadchy.xtra.util.TwitchApiHelper.getGQLHeaders(ctx),
                        ids = tile.effectiveChannelId?.let { listOf(it) },
                        logins = if (tile.effectiveChannelId.isNullOrBlank()) listOf(login) else null,
                    ).data?.users?.firstOrNull()
                }.getOrNull()
                if (view == null || tile !in tiles || tile.stream.channelLogin != login) {
                    return@launch
                }
                if (user != null && user.stream == null) {
                    onStreamEnded(tile, login)
                    return@launch
                }
            }
            tile.recoveryJob = null
            playStream(login, tile.player, tile, backup = tile.onBackup)
        }
    }

    /** Ad blocker on every stream, like VAFT: the player type without an ad playing. */
    /**
     * Ad blocker on every stream, like the video swap of the player: the normal player type (all the qualities), a
     * backup type (autoplay first) only while an ad plays, then back to the normal one once its playlist has no ad.
     * When Experimental VOD Adblock is enabled, resolves the streamer's ongoing `index-dvr.m3u8` at ~30 s behind live,
     * falling back automatically to Swap (VAFT) when the channel has no VOD or while a brand-new live builds its VOD.
     */
    private fun playStream(login: String, player: ExoPlayer, tile: Tile, backup: Boolean = false) {
        // a new playlist (start, ad blocker): its first seconds do not count as stops
        tile.playingSince = 0L
        tile.playJob?.cancel()
        tile.backJob?.cancel()
        if (!backup) {
            tile.expUpgradeJob?.cancel()
            tile.expUpgradeJob = null
        }
        tile.playJob = viewLifecycleOwner.lifecycleScope.launch {
            val context = context ?: return@launch
            val app = context.applicationContext as com.github.andreyasadchy.xtra.XtraApp
            if (!backup && com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(context)) {
                val outcome = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.resolveLiveVod(
                    context = context,
                    xtraModule = app.xtraModule,
                    channelLogin = login,
                    streamIdHint = tile.stream.id,
                    createdAtHint = tile.stream.createdAt,
                )
                if (view == null || tile !in tiles || tile.stream.channelLogin != login) {
                    return@launch
                }
                if (tile.stream.id.isNullOrBlank() && !outcome.streamId.isNullOrBlank()) {
                    tile.stream.id = outcome.streamId
                }
                if (tile.stream.createdAt.isNullOrBlank() && !outcome.createdAtIso.isNullOrBlank()) {
                    tile.stream.createdAt = outcome.createdAtIso
                }
                val vod = outcome.vod
                if (vod != null && vod.vodDurationMs >= com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.MIN_INITIAL_VOD_DURATION_MS) {
                    tile.experimentalVodActive = true
                    tile.experimentalCreatedAtMs = vod.createdAtMs
                    tile.onBackup = false
                    tile.adMuted = false
                    applySound()
                    updateAdBadge(tile)
                    player.setMediaSource(
                        com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.buildMediaSource(
                            context,
                            app.xtraModule,
                            vod.masterUri,
                            slidingWindowOnly = true,
                            createdAtMs = vod.createdAtMs,
                        )
                    )
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.prepare()
                    player.playWhenReady = true
                    return@launch
                }
                tile.experimentalVodActive = false
                tile.experimentalCreatedAtMs = null
                if (outcome.channelIsLive &&
                    (outcome.streamUptimeMs == null || outcome.streamUptimeMs < com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.NEW_STREAM_MAX_UPTIME_MS)
                ) {
                    scheduleTileExperimentalUpgrade(tile, login, outcome.streamId, outcome.createdAtIso)
                }
            } else if (!backup) {
                tile.experimentalVodActive = false
                tile.experimentalCreatedAtMs = null
            }
            val swapEnabled = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isSwapAdblockEnabled(context)
            val normal = if (!backup) {
                try {
                    ExoPlayerService.loadStreamPlaylistUrl(app, app.xtraModule, login, allowSwapPlus = false)
                } catch (e: Exception) {
                    null
                }
            } else null
            val normalHasAds = normal != null && swapEnabled &&
                com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.playlistHasAds(app, normal)
            val backupChoice = if (backup || normal == null || normalHasAds) {
                com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.findBackupPlaylist(app, login)
            } else null
            val url = if (!backup && normal != null && !normalHasAds) normal else backupChoice?.url
            if (url != null && view != null && tile in tiles && tile.stream.channelLogin == login) {
                tile.experimentalVodActive = false
                val usingBackup = backup || normal == null || normalHasAds
                tile.onBackup = usingBackup
                tile.adMuted = usingBackup && backupChoice?.adFree == false
                applySound()
                updateAdBadge(tile)
                player.setMediaItem(buildTileLiveMediaItem(url))
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.prepare()
                player.playWhenReady = true
                if (tile.onBackup) {
                    waitForEndOfAd(login, player, tile)
                }
            } else if (url == null && view != null && tile in tiles && tile.stream.channelLogin == login && !pausedByAudioFocus && !leaveMuted) {
                scheduleTileRecovery(tile, login)
            }
        }
    }

    private fun buildTileLiveMediaItem(url: String): MediaItem {
        val targetOffsetMs = (context?.prefs()?.getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull() ?: 2000L)
            .coerceIn(1500L, 15_000L)
        val liveConfig = MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(targetOffsetMs)
            .build()
        return MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(liveConfig)
            .build()
    }

    private fun scheduleTileExperimentalUpgrade(
        tile: Tile,
        login: String,
        streamIdHint: String?,
        createdAtHint: String?,
    ) {
        tile.expUpgradeJob?.cancel()
        tile.expUpgradeJob = viewLifecycleOwner.lifecycleScope.launch {
            for (attempt in 1..com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.UPGRADE_MAX_ATTEMPTS) {
                kotlinx.coroutines.delay(com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.UPGRADE_POLL_INTERVAL_MS)
                val ctx = context ?: return@launch
                if (view == null || tile !in tiles || tile.stream.channelLogin != login || tile.experimentalVodActive) {
                    return@launch
                }
                if (!com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(ctx)) {
                    return@launch
                }
                val app = ctx.applicationContext as com.github.andreyasadchy.xtra.XtraApp
                val next = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.resolveLiveVod(
                    context = ctx,
                    xtraModule = app.xtraModule,
                    channelLogin = login,
                    streamIdHint = streamIdHint ?: tile.stream.id,
                    createdAtHint = createdAtHint ?: tile.stream.createdAt,
                )
                if (!next.channelIsLive) {
                    return@launch
                }
                val vod = next.vod
                if (vod != null && vod.vodDurationMs >= com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.UPGRADE_MIN_VOD_DURATION_MS) {
                    if (view != null && tile in tiles && tile.stream.channelLogin == login && !tile.experimentalVodActive) {
                        tile.backJob?.cancel()
                        tile.onBackup = false
                        tile.adMuted = false
                        tile.experimentalVodActive = true
                        tile.experimentalCreatedAtMs = vod.createdAtMs
                        tile.playingSince = 0L
                        applySound()
                        updateAdBadge(tile)
                        tile.player.setMediaSource(
                            com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.buildMediaSource(
                                ctx,
                                app.xtraModule,
                                vod.masterUri,
                                slidingWindowOnly = true,
                                createdAtMs = vod.createdAtMs,
                            )
                        )
                        tile.player.repeatMode = Player.REPEAT_MODE_OFF
                        tile.player.prepare()
                        tile.player.playWhenReady = true
                        com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(ctx, R.string.tv_adblock_exp_switched, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                if (vod == null && next.streamUptimeMs != null &&
                    next.streamUptimeMs >= com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.NEW_STREAM_MAX_UPTIME_MS
                ) {
                    return@launch
                }
            }
        }
    }

    /**
     * On a backup player type:
     * - While muted because every candidate temporarily had an ad pod (`tile.adMuted`), re-probes backups every 4 s so
     *   `autoplay` / `picture-by-picture` / `embed` takes over as soon as its short pre-roll clears.
     * - Once playing clean ad-free backup video, checks the normal full-quality playlist every 15 s to switch back as soon
     *   as the ad break ends.
     */
    private fun waitForEndOfAd(login: String, player: ExoPlayer, tile: Tile) {
        tile.backJob?.cancel()
        tile.backJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(if (tile.adMuted) 4_000L else 15_000L)
                if (view == null || tile !in tiles || tile.stream.channelLogin != login || !tile.onBackup) {
                    return@launch
                }
                val app = (context?.applicationContext as? com.github.andreyasadchy.xtra.XtraApp) ?: return@launch
                if (tile.adMuted) {
                    val freshBackup = com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.findBackupPlaylist(app, login)
                    if (freshBackup != null && freshBackup.adFree && view != null && tile in tiles && tile.stream.channelLogin == login) {
                        tile.adMuted = false
                        applySound()
                        tile.playingSince = 0L
                        player.setMediaItem(buildTileLiveMediaItem(freshBackup.url))
                        player.prepare()
                        player.playWhenReady = true
                        continue
                    }
                }
                val normal = try {
                    ExoPlayerService.loadStreamPlaylistUrl(app, app.xtraModule, login, allowSwapPlus = false)
                } catch (e: Exception) {
                    null
                } ?: continue
                if (!com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.playlistHasAds(app, normal) && view != null && tile in tiles && tile.stream.channelLogin == login) {
                    tile.onBackup = false
                    tile.adMuted = false
                    applySound()
                    updateAdBadge(tile)
                    tile.playingSince = 0L
                    player.setMediaItem(buildTileLiveMediaItem(normal))
                    player.prepare()
                    player.playWhenReady = true
                    return@launch
                }
            }
        }
    }

    /** Discreet softly breathing badge in the top-right corner of a tile while an ad is being bypassed. */
    private fun updateAdBadge(tile: Tile) {
        val showBadge = requireContext().prefs().getBoolean(C.TV_SHOW_ADBLOCK_BADGE, true)
        if (tile.onBackup && showBadge) {
            if (tile.adBadge.visibility != View.VISIBLE) {
                tile.adBadge.alpha = 0.85f
                tile.adBadge.visibility = View.VISIBLE
            }
            if (tile.adPulseAnimator?.isRunning != true) {
                tile.adPulseAnimator?.cancel()
                tile.adPulseAnimator = android.animation.ValueAnimator.ofFloat(0.85f, 0.45f).apply {
                    duration = 1400L
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { tile.adBadge.alpha = it.animatedValue as Float }
                    start()
                }
            }
        } else {
            tile.adPulseAnimator?.cancel()
            tile.adPulseAnimator = null
            tile.adBadge.visibility = View.GONE
        }
    }

    override fun onStop() {
        super.onStop()
        // the streams keep playing a moment: the app left for good closes the multi-stream (MainActivity), a screen shown
        // over it for a moment (screensaver of a launcher giving up) does not cut them, nor take the audio focus away
    }

    override fun onStart() {
        super.onStart()
        pausedByAudioFocus = false
        tiles.forEach { tile ->
            val login = tile.stream.channelLogin
            if (tile.player.playbackState == Player.STATE_IDLE && login != null && tile.recoveryJob?.isActive != true) {
                scheduleTileRecovery(tile, login)
            } else {
                tile.player.play()
            }
        }
        holdPlaybackSignals()
    }

    /** TV: the app was just left (Home): every stream silent while it is kept a few seconds, the selected one heard again on return. */
    fun setLeaveMuted(muted: Boolean) {
        leaveMuted = muted
        applySound()
        if (!muted && !pausedByAudioFocus) {
            tiles.forEach { tile ->
                val login = tile.stream.channelLogin
                if (tile.player.playbackState == Player.STATE_IDLE && login != null && tile.recoveryJob?.isActive != true) {
                    scheduleTileRecovery(tile, login)
                }
            }
        }
    }

    private var leaveMuted = false
    private var pausedByAudioFocus = false

    /** The stream selected last (its sound is the one heard, unless the sounds are mixed). */
    private var selectedTile: Tile? = null

    /**
     * Sound mix: every stream at its own volume (else the selected one only), for the whole multi-stream (streams changed
     * or added too). Kept for the next multi-streams only with "Keep for the next multi-streams" (off at first).
     */
    private var soundMix = false
    private val sessionVolumes = mutableMapOf<String, Int>()
    private var soundLoaded = false

    private fun loadSound() {
        if (soundLoaded) {
            return
        }
        val prefs = context?.prefs() ?: return
        soundLoaded = true
        soundMix = prefs.getBoolean(C.TV_MULTISTREAM_SOUND_KEEP, false) && prefs.getBoolean(C.TV_MULTISTREAM_SOUND_MIX, false)
    }

    /** Sound of the streams: the selected one only, or every stream at its own volume (mix). */
    private fun applySound() {
        loadSound()
        val selected = selectedTile?.takeIf { it in tiles } ?: tiles.firstOrNull()
        tiles.forEach { tile ->
            tile.player.volume = when {
                leaveMuted || tile.adMuted -> 0f
                soundMix -> streamVolume(tile) / 100f
                tile == selected -> 1f
                else -> 0f
            }
        }
    }

    /** Volume of a stream in the mix: set in this multi-stream, else the kept one (box ticked), else 100 %. */
    private fun streamVolume(tile: Tile): Int {
        val login = tile.stream.channelLogin ?: return 100
        sessionVolumes[login]?.let { return it }
        val prefs = context?.prefs() ?: return 100
        return if (prefs.getBoolean(C.TV_MULTISTREAM_SOUND_KEEP, false)) prefs.getInt(C.TV_MULTISTREAM_VOLUME_PREFIX + login, 100) else 100
    }

    /** Sound panel: what each choice does, the selected stream only or the mix, the volumes and the box to keep them. */
    private fun showSoundPanel() {
        val context = context ?: return
        loadSound()
        val prefs = context.prefs()
        val shown = tiles.filter { it.stream.channelLogin != null }
        TvMultiStreamSound.show(
            context,
            TvMultiStreamSound.Texts(
                title = getString(R.string.tv_multistream_sound),
                modeLabel = getString(R.string.tv_multistream_sound),
                selected = getString(R.string.tv_multistream_sound_selected),
                mix = getString(R.string.tv_multistream_sound_mix),
                selectedDescription = getString(R.string.tv_multistream_sound_selected_desc),
                mixDescription = getString(R.string.tv_multistream_sound_mix_desc),
                remember = getString(R.string.tv_multistream_sound_keep),
            ),
            mix = soundMix,
            keep = prefs.getBoolean(C.TV_MULTISTREAM_SOUND_KEEP, false),
            streams = shown.map { it.stream.channelName ?: it.stream.channelLogin.orEmpty() },
            volumeOf = { index -> shown.getOrNull(index)?.let { streamVolume(it) } ?: 100 },
            onMode = { mix ->
                soundMix = mix
                if (prefs.getBoolean(C.TV_MULTISTREAM_SOUND_KEEP, false)) {
                    prefs.edit().putBoolean(C.TV_MULTISTREAM_SOUND_MIX, mix).apply()
                }
                applySound()
            },
            onVolume = { index, value ->
                shown.getOrNull(index)?.stream?.channelLogin?.let { login ->
                    sessionVolumes[login] = value
                    if (prefs.getBoolean(C.TV_MULTISTREAM_SOUND_KEEP, false)) {
                        prefs.edit().putInt(C.TV_MULTISTREAM_VOLUME_PREFIX + login, value).apply()
                    }
                }
                applySound()
            },
            onKeep = { keep ->
                prefs.edit().apply {
                    putBoolean(C.TV_MULTISTREAM_SOUND_KEEP, keep)
                    // ticked: the mix and the volumes of now are the ones of the next multi-streams; unticked: they
                    // start again with the sound of the selected stream
                    putBoolean(C.TV_MULTISTREAM_SOUND_MIX, keep && soundMix)
                    if (keep) {
                        tiles.forEach { tile ->
                            tile.stream.channelLogin?.let { putInt(C.TV_MULTISTREAM_VOLUME_PREFIX + it, streamVolume(tile)) }
                        }
                    }
                }.apply()
            },
        )
    }

    /** Frame of the selected stream and names of the streams: shown, then faded out a few seconds after the last key. */
    private var chromeAlpha = 1f
    private var chromeAnimator: android.animation.ValueAnimator? = null
    private val hideChrome = Runnable {
        chromeAnimator?.cancel()
        chromeAnimator = android.animation.ValueAnimator.ofFloat(chromeAlpha, 0f).apply {
            duration = 400L
            addUpdateListener { applyChromeAlpha(it.animatedValue as Float) }
            start()
        }
    }

    private fun showChrome() {
        if (!::root.isInitialized) {
            return
        }
        selectedTile?.takeIf { it in tiles }?.let { updateTileInfo(it) }
        chromeAnimator?.cancel()
        applyChromeAlpha(1f)
        root.removeCallbacks(hideChrome)
        root.postDelayed(hideChrome, CHROME_HIDE_MS)
    }

    private fun applyChromeAlpha(alpha: Float) {
        chromeAlpha = alpha
        tiles.forEach { tile ->
            tile.label.alpha = alpha
            tile.infoContainer.alpha = alpha
            tile.view.foreground?.alpha = (alpha * 255).toInt()
            if (tile.view.hasFocus()) {
                com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(tile.infoTitle, alpha > 0f)
            }
        }
    }

    /** Info shown on the selected stream: name, viewers, video quality, live duration and single-line scrolling title. */
    private fun updateTileInfo(tile: Tile) {
        val context = context ?: return
        val stream = tile.stream
        val name = stream.channelName ?: stream.channelLogin.orEmpty()
        val truncate = context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)
        val viewers = stream.viewerCount?.let { com.github.andreyasadchy.xtra.util.TwitchApiHelper.formatCount(it, truncate) }
        val format = tile.player.videoFormat
        val quality = format?.height?.takeIf { it > 0 }?.let { h ->
            val fps = format.frameRate.takeIf { it > 0f }?.let { kotlin.math.round(it).toInt() }
            "${h}p${fps?.takeIf { it > 30 } ?: ""}"
        }
        val uptime = com.github.andreyasadchy.xtra.ui.tv.TvUptime.format(context, stream.createdAt)
        val builder = android.text.SpannableStringBuilder(name)
        if (name.isNotEmpty()) {
            builder.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, name.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val metaStart = builder.length
        if (!viewers.isNullOrBlank()) {
            builder.append("   ● ")
            val dotIndex = builder.length - 2
            builder.setSpan(android.text.style.ForegroundColorSpan(0xFFEF4444.toInt()), dotIndex, dotIndex + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append(viewers)
        }
        if (!quality.isNullOrBlank()) {
            builder.append(if (builder.length == metaStart) "   " else "  ·  ").append(quality)
        }
        if (!uptime.isNullOrBlank()) {
            builder.append(if (builder.length == metaStart) "   " else "  ·  ").append(uptime)
        }
        if (builder.length > metaStart) {
            builder.setSpan(android.text.style.RelativeSizeSpan(0.92f), metaStart, builder.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tile.infoTop.text = builder
        val titleText = stream.title?.trim().orEmpty()
        tile.infoTitle.visibility = if (titleText.isEmpty()) View.GONE else View.VISIBLE
        if (tile.infoTitle.text.toString() != titleText) {
            tile.infoTitle.text = titleText
            com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(tile.infoTitle, tile.view.hasFocus() && chromeAlpha > 0f)
        }
    }

    /** What the system and the launchers see of the multi-stream: a playback going on (no screensaver over it). */
    private var session: android.media.session.MediaSession? = null

    private fun holdPlaybackSignals() {
        val context = context ?: return
        com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.hold(context, this) {
            pausedByAudioFocus = true
            tiles.forEach {
                it.recoveryJob?.cancel()
                it.player.pause()
            }
        }
        val session = session ?: android.media.session.MediaSession(context, "MultiStream").also { session = it }
        @Suppress("DEPRECATION")
        session.setFlags(7)
        session.setPlaybackToLocal(com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.systemAttributes)
        val metadata = android.media.MediaMetadata.Builder()
            .putText(android.media.MediaMetadata.METADATA_KEY_TITLE, tiles.joinToString(" · ") { it.stream.channelName ?: it.stream.channelLogin ?: "" })
            .build()
        session.setMetadata(metadata)
        session.setQueue(listOf(android.media.session.MediaSession.QueueItem(metadata.description, 0L)))
        session.setPlaybackState(
            android.media.session.PlaybackState.Builder()
                .setState(android.media.session.PlaybackState.STATE_PLAYING, android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_PLAY_PAUSE)
                .setActiveQueueItemId(0L)
                .build()
        )
        session.isActive = true
    }

    private fun releasePlaybackSignals() {
        context?.let { com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.release(it, this) }
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
    }

    private val dimListener: () -> Unit = { com.github.andreyasadchy.xtra.ui.tv.TvDim.apply(root) }

    override fun onDestroyView() {
        qrCompanionChatContainer?.let { runCatching { removeChat(it) } }
        qrCompanionChatContainer = null
        qrCompanionChatLogin = null
        com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.scheduleDetach(this)
        com.github.andreyasadchy.xtra.ui.tv.TvDim.removeListener(dimListener)
        root.removeCallbacks(followFocus)
        root.removeCallbacks(hideChrome)
        chromeAnimator?.cancel()
        tiles.forEach {
            it.stateListener?.let { l -> it.player.removeListener(l) }
            it.adListener?.let { l -> it.player.removeListener(l) }
            it.recoveryJob?.cancel()
            it.playJob?.cancel()
            it.expUpgradeJob?.cancel()
            it.chatProbeJob?.cancel()
            it.infoJob?.cancel()
            it.backJob?.cancel()
            it.raiseJob?.cancel()
            it.adPulseAnimator?.cancel()
            it.dynamicsProcessing?.let { dp ->
                runCatching { dp.release() }
                it.dynamicsProcessing = null
            }
            it.player.release()
        }
        tiles.clear()
        com.github.andreyasadchy.xtra.ui.tv.TvShadowAdblock.stopAll()
        releasePlaybackSignals()
        (activity as? MainActivity)?.onMultiStreamDestroyed()
        super.onDestroyView()
    }

    private fun createTileDynamicsProcessing(audioSessionId: Int): android.media.audiofx.DynamicsProcessing? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
            audioSessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET
        ) {
            return try {
                android.media.audiofx.DynamicsProcessing(0, audioSessionId, null).apply {
                    for (channelIdx in 0 until channelCount) {
                        for (bandIdx in 0 until getMbcByChannelIndex(channelIdx).bandCount) {
                            setMbcBandByChannelIndex(
                                channelIdx,
                                bandIdx,
                                getMbcBandByChannelIndex(channelIdx, bandIdx).apply {
                                    attackTime = 0f
                                    releaseTime = 0.25f
                                    ratio = 1.6f
                                    threshold = -50f
                                    kneeWidth = 40f
                                    preGain = 0f
                                    postGain = 10f
                                }
                            )
                        }
                    }
                    enabled = true
                }
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    companion object {
        private const val MAX = 6
        /** Time the frame of the selected stream and the names stay after the last key. */
        private const val CHROME_HIDE_MS = 5000L
        private val DIRECTIONS = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to View.FOCUS_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to View.FOCUS_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to View.FOCUS_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to View.FOCUS_RIGHT,
        )
        private const val KEY_STREAMS = "streams"

        private const val KEY_ORIGIN = "origin"

        /** [origin]: the stream it was made from, back goes to it. */
        fun newInstance(streams: List<Stream>, origin: Stream? = null) = MultiStreamFragment().apply {
            arguments = bundleOf(KEY_STREAMS to ArrayList(streams), KEY_ORIGIN to origin)
        }
    }
}
