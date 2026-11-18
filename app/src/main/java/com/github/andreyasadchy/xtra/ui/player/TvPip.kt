package com.github.andreyasadchy.xtra.ui.player

import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvAccent
import com.github.andreyasadchy.xtra.ui.tv.TvDim
import com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock
import com.github.andreyasadchy.xtra.ui.tv.TvLoadingPicture
import com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource
import com.github.andreyasadchy.xtra.ui.tv.TvThumbnails
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TV: up to 2 simultaneous live streams in floating small windows over the full screen player.
 * - With 1 small window open: OK held swaps the streams immediately, Back closes it.
 * - With 2 small windows open: OK held activates a visual D-pad window selector (Left/Right/Up/Down switches
 *   between Window 1 and Window 2, OK swaps the selected window to full screen, Back closes the selected window).
 */
@OptIn(UnstableApi::class)
class TvPip(private val activity: MainActivity, private val container: ViewGroup) {

    private inner class PipSlot(val slotIndex: Int) {
        var stream: Stream? = null
        var openedAt: Long = 0L
        var view: FrameLayout? = null
        var player: ExoPlayer? = null
        var loadJob: Job? = null
        var expUpgradeJob: Job? = null
        var experimentalVodActive = false
        var decoderSteppedDown = false
    }

    private val slots = arrayOfNulls<PipSlot>(MAX_SLOTS)

    val stream: Stream?
        get() = slots[0]?.stream ?: slots[1]?.stream

    val activeCount: Int
        get() = slots.count { it?.stream != null }

    fun activeSlotIndices(): List<Int> = slots.indices.filter { slots[it]?.stream != null }

    fun streamAt(slotIndex: Int): Stream? = slots.getOrNull(slotIndex)?.stream

    fun activeStreams(): List<Stream> = slots.mapNotNull { it?.stream }

    fun containsStream(login: String?): Boolean {
        if (login.isNullOrBlank()) return false
        return slots.any { it?.stream?.channelLogin.equals(login, ignoreCase = true) }
    }

    private var noPlayerChecks = 0
    private var selectorActive = false
    private var selectedSlot = 0
    private var selectorHintView: TextView? = null
    private var selectorCenterDown = false

    private val hideSelectorRunnable = Runnable { exitSelectorMode() }

    private val followPlayer = object : Runnable {
        override fun run() {
            val anyOpen = activeCount > 0
            val maximized = activity.isPlayerMaximized()
            slots.forEach { slot ->
                val shown = slot?.stream != null && maximized
                slot?.view?.visibility = if (shown) View.VISIBLE else View.GONE
            }
            if (!maximized && selectorActive) {
                exitSelectorMode()
            }
            if (anyOpen) {
                noPlayerChecks = if (activity.hasPlayer()) 0 else noPlayerChecks + 1
                if (noPlayerChecks >= 4) {
                    close()
                    return
                }
                container.postDelayed(this, 500L)
            }
        }
    }

    val isShown: Boolean
        get() = slots.any { it?.stream != null && it.view?.visibility == View.VISIBLE }

    fun open(stream: Stream, targetSlot: Int? = null) {
        val login = stream.channelLogin ?: return
        if (containsStream(login)) {
            Toast.makeText(activity, R.string.tv_pip_already_shown, Toast.LENGTH_SHORT).show()
            return
        }
        val chosenIndex = when {
            targetSlot != null && targetSlot in 0 until MAX_SLOTS -> targetSlot
            slots[0]?.stream == null -> 0
            slots[1]?.stream == null -> 1
            else -> slots.filterNotNull().maxByOrNull { it.openedAt }?.slotIndex ?: 1
        }
        val slot = ensureSlot(chosenIndex)
        slot.stream = stream
        slot.openedAt = SystemClock.elapsedRealtime()
        slot.decoderSteppedDown = false

        val frame = slot.view ?: return
        updateVideoSizeCaps()
        applyLayout()
        applySound()
        applyDim()
        updateBorders()
        TvDim.removeListener(dimListener)
        TvDim.addListener(dimListener)

        frame.findViewById<TextView>(R.id.tvPipLabel)?.text = stream.channelName ?: login
        val texture = frame.findViewById<TextureView>(R.id.tvPipTexture)
        texture?.animate()?.cancel()
        texture?.alpha = 0f
        frame.findViewById<ImageView>(R.id.tvLoadingPicture)?.let { picture ->
            picture.setImageDrawable(null)
            TvLoadingPicture.show(
                picture,
                login,
                stream.thumbnail?.also { TvThumbnails.remember(stream) }?.takeUnless { stream.thumbnailURL.isNullOrBlank() },
                stream.channelImage,
                activity.lifecycleScope,
            ) { slot.stream?.channelLogin == login && (texture?.alpha ?: 1f) < 1f }
        }
        frame.visibility = if (activity.isPlayerMaximized()) View.VISIBLE else View.GONE
        noPlayerChecks = 0
        load(slot, login)
        container.removeCallbacks(followPlayer)
        container.postDelayed(followPlayer, 500L)
    }

    private fun ensureSlot(slotIndex: Int): PipSlot {
        slots[slotIndex]?.let { existing ->
            if (existing.view != null && existing.player != null) {
                return existing
            }
        }
        val slot = PipSlot(slotIndex)
        slots[slotIndex] = slot
        val context = activity
        val density = context.resources.displayMetrics.density
        val frame = FrameLayout(context).apply {
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
            elevation = (16 + slotIndex) * density
            isFocusable = false
            val texture = TextureView(context)
            addView(ImageView(context).apply {
                id = R.id.tvLoadingPicture
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            texture.id = R.id.tvPipTexture
            addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(context).apply {
                id = R.id.tvPipDim
                isFocusable = false
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        }
        slot.view = frame

        val pipLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 12_000, 1_000, 2_000)
            .setBackBuffer(0, false)
            .build()
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val texture = frame.findViewById<TextureView>(R.id.tvPipTexture)
        slot.player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(pipLoadControl)
            .setHandleAudioBecomingNoisy(false)
            .build().apply {
                volume = 0f
                if (texture != null) {
                    setVideoTextureView(texture)
                }
                trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(854, 480).build()
                addListener(object : Player.Listener {
                    private var lastSwitch = 0L

                    override fun onRenderedFirstFrame() {
                        slot.view?.findViewById<TextureView>(R.id.tvPipTexture)
                            ?.takeIf { it.alpha < 1f }
                            ?.animate()?.alpha(1f)?.setDuration(250L)?.start()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!slot.decoderSteppedDown && (
                                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                                )
                        ) {
                            slot.decoderSteppedDown = true
                            updateVideoSizeCaps()
                            slot.stream?.channelLogin?.let { load(slot, it) }
                        }
                    }

                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        if (slot.experimentalVodActive) return
                        val playlist = (currentManifest as? HlsManifest)?.mediaPlaylist ?: return
                        val ads = playlist.tags.any { it.contains("stitched-ad", true) || it.contains("X-TV-TWITCH-AD", true) }
                        val now = System.currentTimeMillis()
                        if (ads && now - lastSwitch > 20_000L && TvExperimentalAdblock.isSwapAdblockEnabled(activity)) {
                            lastSwitch = now
                            slot.stream?.channelLogin?.let { load(slot, it) }
                        }
                    }
                })
            }
        return slot
    }

    private fun updateVideoSizeCaps() {
        val twoOpen = activeCount >= 2
        slots.forEach { slot ->
            val p = slot?.player ?: return@forEach
            val (maxW, maxH) = when {
                slot.decoderSteppedDown -> 426 to 240
                twoOpen -> 640 to 360
                else -> 854 to 480
            }
            if (p.trackSelectionParameters.maxVideoWidth != maxW || p.trackSelectionParameters.maxVideoHeight != maxH) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(maxW, maxH)
                    .build()
            }
        }
    }

    private fun load(slot: PipSlot, login: String) {
        slot.loadJob?.cancel()
        slot.expUpgradeJob?.cancel()
        slot.expUpgradeJob = null
        slot.experimentalVodActive = false
        slot.loadJob = activity.lifecycleScope.launch {
            val app = activity.applicationContext as XtraApp
            if (TvExperimentalAdblock.isExperimentalEnabled(activity)) {
                val currentStream = slot.stream
                val outcome = TvExperimentalAdblock.resolveLiveVod(
                    context = activity,
                    xtraModule = app.xtraModule,
                    channelLogin = login,
                    streamIdHint = currentStream?.id,
                    createdAtHint = currentStream?.createdAt,
                )
                val vod = outcome.vod
                if (vod != null && vod.vodDurationMs >= TvExperimentalAdblock.MIN_INITIAL_VOD_DURATION_MS) {
                    val activePlayer = slot.player
                    if (activePlayer != null && slot.stream?.channelLogin == login) {
                        slot.experimentalVodActive = true
                        activePlayer.setMediaSource(
                            TvExperimentalAdblock.buildMediaSource(
                                activity,
                                app.xtraModule,
                                vod.masterUri,
                                slidingWindowOnly = true,
                                createdAtMs = vod.createdAtMs,
                            )
                        )
                        activePlayer.prepare()
                        activePlayer.playWhenReady = true
                    }
                    return@launch
                }
                if (outcome.channelIsLive &&
                    (outcome.streamUptimeMs == null || outcome.streamUptimeMs < TvExperimentalAdblock.NEW_STREAM_MAX_UPTIME_MS)
                ) {
                    scheduleExperimentalUpgrade(slot, login, outcome.streamId, outcome.createdAtIso)
                }
            }
            val url = TvPreviewSource.getPlaylistUrl(activity, login)
            val activePlayer = slot.player
            if (url != null && activePlayer != null && slot.stream?.channelLogin == login) {
                slot.experimentalVodActive = false
                activePlayer.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                activePlayer.prepare()
                activePlayer.playWhenReady = true
            }
        }
    }

    private fun scheduleExperimentalUpgrade(slot: PipSlot, login: String, streamIdHint: String?, createdAtHint: String?) {
        slot.expUpgradeJob?.cancel()
        slot.expUpgradeJob = activity.lifecycleScope.launch {
            val app = activity.applicationContext as XtraApp
            for (attempt in 1..TvExperimentalAdblock.UPGRADE_MAX_ATTEMPTS) {
                delay(TvExperimentalAdblock.UPGRADE_POLL_INTERVAL_MS)
                if (slot.stream?.channelLogin != login || slot.experimentalVodActive || !TvExperimentalAdblock.isExperimentalEnabled(activity)) {
                    return@launch
                }
                val next = TvExperimentalAdblock.resolveLiveVod(
                    context = activity,
                    xtraModule = app.xtraModule,
                    channelLogin = login,
                    streamIdHint = streamIdHint ?: slot.stream?.id,
                    createdAtHint = createdAtHint ?: slot.stream?.createdAt,
                )
                if (!next.channelIsLive) return@launch
                val vod = next.vod
                if (vod != null && vod.vodDurationMs >= TvExperimentalAdblock.UPGRADE_MIN_VOD_DURATION_MS) {
                    val activePlayer = slot.player
                    if (activePlayer != null && slot.stream?.channelLogin == login && !slot.experimentalVodActive) {
                        slot.experimentalVodActive = true
                        activePlayer.setMediaSource(
                            TvExperimentalAdblock.buildMediaSource(
                                activity,
                                app.xtraModule,
                                vod.masterUri,
                                slidingWindowOnly = true,
                                createdAtMs = vod.createdAtMs,
                            )
                        )
                        activePlayer.prepare()
                        activePlayer.playWhenReady = true
                    }
                    return@launch
                }
                if (vod == null && next.streamUptimeMs != null && next.streamUptimeMs >= TvExperimentalAdblock.NEW_STREAM_MAX_UPTIME_MS) {
                    return@launch
                }
            }
        }
    }

    fun cornerForSlot(slotIndex: Int): String {
        val prefs = activity.prefs()
        return if (slotIndex == 1) {
            prefs.getString(C.TV_PIP2_CORNER, "tl") ?: "tl"
        } else {
            prefs.getString(C.TV_PIP_CORNER, "tr") ?: "tr"
        }
    }

    fun sizeForSlot(slotIndex: Int): Int {
        val prefs = activity.prefs()
        return if (slotIndex == 1) {
            prefs.getInt(C.TV_PIP2_SIZE, 1).coerceIn(0, 2)
        } else {
            prefs.getInt(C.TV_PIP_SIZE, 1).coerceIn(0, 2)
        }
    }

    private fun dimensionsForSize(sizeIndex: Int): Pair<Int, Int> {
        val metrics = activity.resources.displayMetrics
        val fraction = when (sizeIndex) {
            0 -> 0.22f
            2 -> 0.4f
            else -> 0.3f
        }
        val w = (metrics.widthPixels * fraction).toInt()
        return w to (w * 9 / 16)
    }

    /** Corner and size of each open small window, stacking vertically with a clean gap if both share the same corner. */
    fun applyLayout() {
        val density = activity.resources.displayMetrics.density
        val horizontal = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        val vertical = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
        val gap = (10 * density).toInt()

        val slot0Open = slots[0]?.stream != null
        val slot1Open = slots[1]?.stream != null
        val corner0 = cornerForSlot(0)
        val corner1 = cornerForSlot(1)
        val sameCorner = slot0Open && slot1Open && corner0 == corner1
        val (_, h0) = dimensionsForSize(sizeForSlot(0))
        val (_, h1) = dimensionsForSize(sizeForSlot(1))

        slots.forEachIndexed { idx, slot ->
            val frame = slot?.view ?: return@forEachIndexed
            if (slot.stream == null) return@forEachIndexed
            val corner = if (idx == 1) corner1 else corner0
            val (width, height) = dimensionsForSize(sizeForSlot(idx))
            val vGravity = when (corner) {
                "bl", "bc", "br" -> Gravity.BOTTOM
                "ml", "c", "mr" -> Gravity.CENTER_VERTICAL
                else -> Gravity.TOP
            }
            val hGravity = when (corner) {
                "tl", "ml", "bl" -> Gravity.START
                "tc", "c", "bc" -> Gravity.CENTER_HORIZONTAL
                else -> Gravity.END
            }
            var topM = vertical
            var bottomM = vertical
            var extraTranslationY = 0f
            if (sameCorner) {
                when (vGravity) {
                    Gravity.BOTTOM -> if (idx == 1) bottomM += h0 + gap
                    Gravity.TOP -> if (idx == 1) topM += h0 + gap
                    Gravity.CENTER_VERTICAL -> {
                        extraTranslationY = if (idx == 0) -(h1 + gap) / 2f else (h0 + gap) / 2f
                    }
                }
            }
            frame.layoutParams = FrameLayout.LayoutParams(width, height, vGravity or hGravity).apply {
                setMargins(horizontal, topM, horizontal, bottomM)
            }
            frame.animate().cancel()
            frame.translationY = controlsLift(corner) + extraTranslationY
        }
    }

    private var controlsShown = false

    fun setControlsShown(shown: Boolean) {
        if (controlsShown == shown) {
            return
        }
        controlsShown = shown
        val density = activity.resources.displayMetrics.density
        val gap = (10 * density).toInt()
        val sameCorner = slots[0]?.stream != null && slots[1]?.stream != null && cornerForSlot(0) == cornerForSlot(1)
        val (_, h0) = dimensionsForSize(sizeForSlot(0))
        val (_, h1) = dimensionsForSize(sizeForSlot(1))
        slots.forEachIndexed { idx, slot ->
            val frame = slot?.view ?: return@forEachIndexed
            if (slot.stream == null) return@forEachIndexed
            val corner = cornerForSlot(idx)
            val centerShift = if (sameCorner && (corner == "ml" || corner == "c" || corner == "mr")) {
                if (idx == 0) -(h1 + gap) / 2f else (h0 + gap) / 2f
            } else {
                0f
            }
            frame.animate().translationY(controlsLift(corner) + centerShift).setDuration(200L).start()
        }
    }

    private fun controlsLift(corner: String): Float {
        return if (controlsShown && (corner == "bl" || corner == "bc" || corner == "br")) {
            -activity.resources.getDimension(R.dimen.tv_chat_lift)
        } else {
            0f
        }
    }

    /**
     * Sound across main stream and up to 2 small windows:
     * - "main": main stream 100%
     * - "pip" / "pip1": Window 1 100% (falls back to Window 2 if only Window 2 is open, or main if closed)
     * - "pip2": Window 2 100% (falls back to main if Window 2 is closed)
     * - "both": classic 1-PiP balance slider between main and the single open small window
     * - "mix": independent volumes for Main, Window 1, and Window 2
     */
    fun applySound() {
        if (activeCount == 0) {
            setMainMix(1f)
            return
        }
        val prefs = activity.prefs()
        val masterVolume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
        val balance = prefs.getInt(C.TV_PIP_BALANCE, 0).coerceIn(-100, 100)
        val mode = prefs.getString(C.TV_PIP_SOUND, "main") ?: "main"

        val s0Open = slots[0]?.stream != null
        val s1Open = slots[1]?.stream != null

        var mainShare = 1f
        var s0Share = 0f
        var s1Share = 0f

        when (mode) {
            "pip", "pip1" -> {
                if (s0Open) {
                    mainShare = 0f
                    s0Share = 1f
                } else if (s1Open) {
                    mainShare = 0f
                    s1Share = 1f
                }
            }
            "pip2" -> {
                if (s1Open) {
                    mainShare = 0f
                    s1Share = 1f
                } else {
                    // Window 2 closed: restore main sound automatically
                    prefs.edit { putString(C.TV_PIP_SOUND, "main") }
                    mainShare = 1f
                }
            }
            "both" -> {
                mainShare = if (balance > 0) 1f - balance / 100f else 1f
                val smallShare = if (balance < 0) 1f + balance / 100f else 1f
                if (s0Open && s1Open) {
                    s0Share = smallShare
                    s1Share = smallShare
                } else if (s0Open) {
                    s0Share = smallShare
                } else if (s1Open) {
                    s1Share = smallShare
                }
            }
            "mix" -> {
                mainShare = prefs.getInt(C.TV_PIP_VOL_MAIN, 100).coerceIn(0, 100) / 100f
                s0Share = if (s0Open) prefs.getInt(C.TV_PIP_VOL_1, 80).coerceIn(0, 100) / 100f else 0f
                s1Share = if (s1Open) prefs.getInt(C.TV_PIP_VOL_2, 80).coerceIn(0, 100) / 100f else 0f
            }
            else -> {
                mainShare = 1f
            }
        }

        slots[0]?.player?.volume = if (leaveMuted) 0f else s0Share * masterVolume
        slots[1]?.player?.volume = if (leaveMuted) 0f else s1Share * masterVolume
        setMainMix(mainShare)
    }

    private fun setMainMix(mix: Float) {
        if (mainMix != mix) {
            mainMix = mix
            activity.refreshPlayerVolume()
        }
    }

    private val dimListener: () -> Unit = { applyDim() }

    private fun applyDim() {
        slots.forEach { slot ->
            slot?.view?.findViewById<View>(R.id.tvPipDim)?.let { TvDim.apply(it) }
        }
    }

    private fun updateBorders() {
        val density = activity.resources.displayMetrics.density
        slots.forEachIndexed { idx, slot ->
            val fg = slot?.view?.foreground as? GradientDrawable ?: return@forEachIndexed
            if (selectorActive && activeCount >= 2) {
                if (idx == selectedSlot) {
                    fg.setStroke((4.5f * density).toInt(), TvAccent.color)
                } else {
                    fg.setStroke((2.5f * density).toInt(), 0x77FFFFFF)
                }
            } else {
                fg.setStroke((3 * density).toInt(), Color.WHITE)
            }
        }
    }

    private fun enterSelectorMode() {
        if (activeCount < 2) return
        selectorActive = true
        selectorCenterDown = false
        backDown = false
        selectedSlot = if (slots[0]?.stream != null) 0 else 1
        updateBorders()
        showSelectorHint()
        container.removeCallbacks(hideSelectorRunnable)
        container.postDelayed(hideSelectorRunnable, SELECTOR_TIMEOUT_MS)
    }

    private fun exitSelectorMode() {
        if (!selectorActive && selectorHintView == null) return
        selectorActive = false
        selectorCenterDown = false
        container.removeCallbacks(hideSelectorRunnable)
        selectorHintView?.let { container.removeView(it) }
        selectorHintView = null
        updateBorders()
    }

    private fun showSelectorHint() {
        val density = activity.resources.displayMetrics.density
        val vertical = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
        val pill = selectorHintView ?: TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(0xE6141318.toInt())
                cornerRadius = 999 * density
                setStroke((2 * density).toInt(), TvAccent.color)
            }
            val padH = (16 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 28 * density
            isFocusable = false
            container.addView(
                this,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply {
                    topMargin = vertical
                }
            )
            selectorHintView = this
        }
        val chosenName = slots.getOrNull(selectedSlot)?.stream?.let { it.channelName ?: it.channelLogin }.orEmpty()
        val windowTitle = activity.getString(if (selectedSlot == 1) R.string.tv_pip_window_2 else R.string.tv_pip_window_1)
        val prefix = if (chosenName.isNotBlank()) "$windowTitle ($chosenName)  ·  " else "$windowTitle  ·  "
        pill.text = prefix + activity.getString(R.string.tv_pip_select_hint)
        pill.visibility = View.VISIBLE
    }

    fun closeSlot(slotIndex: Int) {
        val slot = slots.getOrNull(slotIndex) ?: return
        val hadStream = slot.stream != null
        slot.stream = null
        slot.openedAt = 0L
        slot.loadJob?.cancel()
        slot.loadJob = null
        slot.expUpgradeJob?.cancel()
        slot.expUpgradeJob = null
        slot.experimentalVodActive = false
        slot.decoderSteppedDown = false
        slot.player?.release()
        slot.player = null
        slot.view?.let { container.removeView(it) }
        slot.view = null
        slots[slotIndex] = null

        exitSelectorMode()

        if (activeCount == 0) {
            TvDim.removeListener(dimListener)
            controlsShown = false
            setMainMix(1f)
            container.removeCallbacks(followPlayer)
            if (hadStream) {
                activity.onTvPipClosed()
            }
        } else {
            updateVideoSizeCaps()
            applyLayout()
            applySound()
            updateBorders()
        }
    }

    fun closeLatest() {
        val latest = slots.filterNotNull().filter { it.stream != null }.maxByOrNull { it.openedAt }
        if (latest != null) {
            closeSlot(latest.slotIndex)
        } else {
            close()
        }
    }

    fun close() {
        val wasOpen = activeCount > 0
        exitSelectorMode()
        TvDim.removeListener(dimListener)
        for (i in 0 until MAX_SLOTS) {
            val slot = slots[i] ?: continue
            slot.stream = null
            slot.loadJob?.cancel()
            slot.expUpgradeJob?.cancel()
            slot.player?.release()
            slot.view?.let { container.removeView(it) }
            slots[i] = null
        }
        controlsShown = false
        setMainMix(1f)
        container.removeCallbacks(followPlayer)
        if (wasOpen) {
            activity.onTvPipClosed()
        }
    }

    private var backDown = false

    fun onKey(event: KeyEvent): Boolean {
        if (!isShown) {
            backDown = false
            selectorCenterDown = false
            if (selectorActive) exitSelectorMode()
            return false
        }
        if (selectorActive && activeCount >= 2) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        selectedSlot = chooseNextSelectorSlot(event.keyCode)
                        updateBorders()
                        showSelectorHint()
                        container.removeCallbacks(hideSelectorRunnable)
                        container.postDelayed(hideSelectorRunnable, SELECTOR_TIMEOUT_MS)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        selectorCenterDown = true
                    } else if (event.action == KeyEvent.ACTION_UP && selectorCenterDown) {
                        selectorCenterDown = false
                        val target = selectedSlot
                        exitSelectorMode()
                        swapSlot(target)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        backDown = true
                        return true
                    }
                    if (event.action == KeyEvent.ACTION_UP && backDown) {
                        backDown = false
                        val target = selectedSlot
                        exitSelectorMode()
                        closeSlot(target)
                        return true
                    }
                    return backDown
                }
            }
        }
        when {
            event.keyCode == KeyEvent.KEYCODE_BACK && activity.isPlayerFocusedWithoutControls() -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    backDown = true
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP && backDown) {
                    backDown = false
                    closeLatest()
                    return true
                }
                return backDown
            }
            event.keyCode == KeyEvent.KEYCODE_BACK -> {
                backDown = false
                return false
            }
        }
        return false
    }

    private fun chooseNextSelectorSlot(keyCode: Int): Int {
        val other = if (selectedSlot == 0) 1 else 0
        if (slots.getOrNull(other)?.stream == null) return selectedSlot
        val vCur = slots.getOrNull(selectedSlot)?.view ?: return other
        val vOth = slots.getOrNull(other)?.view ?: return other
        val curCx = vCur.left + vCur.width / 2
        val othCx = vOth.left + vOth.width / 2
        val curCy = vCur.top + vCur.height / 2
        val othCy = vOth.top + vOth.height / 2
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (othCx < curCx) other else if (curCx < othCx) other else other
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (othCx > curCx) other else other
            KeyEvent.KEYCODE_DPAD_UP -> if (othCy < curCy) other else other
            KeyEvent.KEYCODE_DPAD_DOWN -> if (othCy > curCy) other else other
            else -> other
        }
    }

    fun swapSlot(slotIndex: Int) {
        val target = slots.getOrNull(slotIndex) ?: return
        val small = target.stream ?: return
        val canPipMain = (activity.playerFragment as? PlayerFragment)?.isLiveOrDvrScreen() == true
        val main = if (canPipMain) (activity.dvrStream ?: activity.currentPlayingStream()) else null
        target.stream = null
        activity.startStream(small)
        if (main?.channelLogin != null) {
            open(main, targetSlot = slotIndex)
        } else {
            closeSlot(slotIndex)
        }
    }

    /** OK held, or the button of the panel: swaps immediately when 1 window is open, or opens D-pad window selector when 2 are open. */
    fun swapStreams(slotIndex: Int? = null) {
        if (slotIndex != null) {
            exitSelectorMode()
            swapSlot(slotIndex)
            return
        }
        val active = activeSlotIndices()
        when {
            active.isEmpty() -> return
            active.size == 1 -> swapSlot(active.first())
            selectorActive -> {
                val target = selectedSlot
                exitSelectorMode()
                swapSlot(target)
            }
            else -> enterSelectorMode()
        }
    }

    companion object {
        const val MAX_SLOTS = 2
        private const val SELECTOR_TIMEOUT_MS = 5_000L

        @Volatile
        var mainMix = 1f
            private set

        @Volatile
        var leaveMuted = false

        fun showHint(activity: MainActivity) {
            Toast.makeText(activity, R.string.tv_pip_hint, Toast.LENGTH_LONG).show()
        }
    }
}
