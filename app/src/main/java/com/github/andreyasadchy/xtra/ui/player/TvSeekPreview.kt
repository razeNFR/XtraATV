package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.ui.tv.FocusRow
import com.github.andreyasadchy.xtra.ui.tv.SidePanel
import com.github.andreyasadchy.xtra.ui.tv.TvColors
import com.github.andreyasadchy.xtra.ui.tv.TvDialogs
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * TV: thumbnail shown above the timeline while seeking, cut from the storyboard sprite sheets Twitch generates for every video.
 * Twitch makes the storyboards only once the broadcast is over: for a VOD still being recorded the thumbnail is a frame of the
 * video itself, from a hidden low quality player that seeks to the chosen position.
 */
class TvSeekPreview(
    private val scope: LifecycleCoroutineScope,
    private val client: OkHttpClient,
    private val container: ViewGroup,
    private val timeBar: View,
) {

    private class Board(val width: Int, val height: Int, val cols: Int, val rows: Int, val interval: Double, val images: List<String>)

    private var board: Board? = null
    private var chapters: List<Game> = emptyList()
    private val sprites = object : LinkedHashMap<String, Bitmap>(28, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 24
    }
    private var activeSpriteUrl: String? = null
    private var activeSpriteCall: Call? = null
    private var activeSpriteJob: Job? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCanvas: Canvas? = null
    private val cropSrcRect = Rect()
    private val cropDstRect = Rect()
    private var lastRenderedSpriteUrl: String? = null
    private var lastRenderedSpriteCell = -1
    private var lastPosition = -1L
    private var lastDuration = 0L
    private var lastDeltaMs: Long? = null
    private var lastTargetLabel: String? = null
    private var hasPreviewBitmap = false
    private val density = container.resources.displayMetrics.density
    private val frame = FrameLayout(container.context).apply {
        background = GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
            cornerRadius = 6 * density
        }
        val padding = (2 * density).toInt()
        setPadding(padding, padding, padding, padding)
        clipToOutline = true
        visibility = View.GONE
        elevation = 6 * density
    }
    private val imageView = ImageView(container.context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
    }
    private val chapterLabel = TextView(container.context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, 0xE0000000.toInt()),
        )
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val hPad = (8 * density).toInt()
        val topPad = (10 * density).toInt()
        val botPad = (5 * density).toInt()
        setPadding(hPad, topPad, hPad, botPad)
        elevation = 2 * density
        visibility = View.GONE
    }
    private val deltaTextView = TextView(container.context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
    }
    private val separatorTextView = TextView(container.context).apply {
        text = "  ·  "
        setTextColor(0x80FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        includeFontPadding = false
        maxLines = 1
    }
    private val targetTimeTextView = TextView(container.context).apply {
        setTextColor(0xEBFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
    }
    private val clockSeparatorTextView = TextView(container.context).apply {
        text = "  ·  "
        setTextColor(0x80FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        includeFontPadding = false
        maxLines = 1
        visibility = View.GONE
    }
    private val clockIconView = ImageView(container.context).apply {
        setImageResource(com.github.andreyasadchy.xtra.R.drawable.tv_ic_schedule)
        setColorFilter(0xC8FFFFFF.toInt())
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }
    private val localClockTextView = TextView(container.context).apply {
        setTextColor(0xEBFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
        visibility = View.GONE
    }
    private val seekInfoBox = LinearLayout(container.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(0xED14141D.toInt())
            setStroke(maxOf(1, (1.2f * density).toInt()), 0x54FFFFFF)
            cornerRadius = 16 * density
        }
        val hPad = (13 * density).toInt()
        val vPad = (5.5f * density).toInt()
        setPadding(hPad, vPad, hPad, vPad)
        visibility = View.GONE
        elevation = 8 * density
        addView(deltaTextView)
        addView(separatorTextView)
        addView(targetTimeTextView)
        addView(clockSeparatorTextView)
        val iconSize = (13.5f * density).toInt()
        addView(
            clockIconView,
            LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (4.5f * density).toInt()
            }
        )
        addView(localClockTextView)
    }
    private val previewWidth = (240 * density).toInt()
    private val previewHeight = (135 * density).toInt()
    private val hideAction = Runnable { hide() }

    // the hidden player takes a video decoder: it is let go when the timeline is not used anymore
    private val releaseAction = Runnable { releaseVideo() }

    private var startEpochMs: Long? = null

    init {
        frame.addView(imageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(
            chapterLabel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        container.addView(frame, ViewGroup.LayoutParams(previewWidth, previewHeight))
        container.addView(seekInfoBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun setStartEpochMs(epochMs: Long?) {
        val normalized = epochMs?.takeIf { it > 0L }
        if (startEpochMs == normalized) return
        startEpochMs = normalized
        if (seekInfoBox.isShown && lastPosition >= 0L && lastDuration > 0L) {
            show(lastPosition, lastDuration, lastDeltaMs, lastTargetLabel)
        }
    }

    fun setChapters(list: List<Game>?) {
        chapters = list?.filter { (it.vodPosition ?: -1) >= 0 }?.sortedBy { it.vodPosition } ?: emptyList()
    }

    private var infoUrl: String? = null
    private var boardLoadedAt = 0L
    private var boardLoading = false
    private var refresh = false

    // frames of the video, when there is no storyboard
    private var videoBase: String? = null
    private var videoFallback: String? = null
    private var videoPlayer: ExoPlayer? = null
    private var videoPlayerListener: Player.Listener? = null
    private var textureView: TextureView? = null
    private var videoLoading = false
    private var hasRenderedVideoFrame = false
    private var videoSeekInFlight = false
    private var lastVideoSeekAtMs = 0L
    private var pendingVideoSeekPos = -1L
    private val videoPumpRunnable = Runnable { flushPendingVideoSeek() }

    /**
     * [infoUrl] is the storyboard description of the video (seekPreviewsURL), the sprites are next to it.
     * [refresh]: the video is still being recorded, its storyboards grow, they are loaded again from time to time.
     * [videoPlaylist]: playlist of the video, its frames are used when the storyboard is not there.
     */
    fun load(infoUrl: String?, refresh: Boolean = false, videoPlaylist: String? = null) {
        this.infoUrl = infoUrl
        this.refresh = refresh
        if (!videoPlaylist.isNullOrBlank()) {
            this.videoFallback = videoPlaylist
        }
        if (infoUrl != null) {
            loadBoard()
        } else {
            useVideo()
        }
    }

    /** Attaches or updates a fallback DVR video playlist (e.g. Experimental Adblock ongoing VOD) when no public storyboard exists. */
    fun updateVideoFallback(videoPlaylist: String?) {
        if (videoPlaylist.isNullOrBlank() || videoPlaylist == videoFallback) return
        videoFallback = videoPlaylist
        if (board == null && !boardLoading && videoBase == null && !videoLoading) {
            useVideo()
        }
    }

    private fun loadBoard() {
        val infoUrl = infoUrl ?: return
        if (boardLoading || videoBase != null) {
            return
        }
        boardLoading = true
        val base = infoUrl.substringBeforeLast("/")
        scope.launch {
            var missing = false
            val loaded = withContext(Dispatchers.IO) {
                try {
                    client.newCall(Request.Builder().url(infoUrl).build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            log("storyboard info ${response.code} $infoUrl")
                            missing = response.code == 403 || response.code == 404
                            return@withContext null
                        }
                        val array = JSONArray(response.body.string())
                        val entries = (0 until array.length()).map { array.getJSONObject(it) }
                        val entry = entries.find { it.optString("quality") == "high" } ?: entries.firstOrNull() ?: return@withContext null
                        val images = entry.getJSONArray("images").let { list -> (0 until list.length()).map { "$base/${list.getString(it)}" } }
                        Board(entry.getInt("width"), entry.getInt("height"), entry.getInt("cols"), entry.getInt("rows"), entry.getDouble("interval"), images)
                    }
                } catch (e: Exception) {
                    log("storyboard info failed: $e")
                    null
                }
            }
            boardLoading = false
            if (loaded != null) {
                board = loaded
                boardLoadedAt = System.currentTimeMillis()
                // the last sprite of a growing video changes, it is downloaded again
                if (refresh) {
                    loaded.images.lastOrNull()?.let { sprites.remove(it) }
                }
                if (seekInfoBox.isShown && lastPosition >= 0L && lastDuration > 0L) {
                    show(lastPosition, lastDuration, lastDeltaMs, lastTargetLabel)
                }
            } else if (missing) {
                // a VOD being recorded: no storyboard until the end of the broadcast
                useVideo()
            }
        }
    }

    /** Finds a low quality playlist of the video, next to its storyboards or given by the player. */
    private fun useVideo() {
        if (videoBase != null || videoLoading) {
            return
        }
        val folder = infoUrl?.takeIf { it.contains("/storyboards/") }?.substringBefore("/storyboards/")
            ?: videoFallback?.takeIf { it.endsWith("/index-dvr.m3u8") }?.substringBeforeLast("/")?.substringBeforeLast("/")
        val candidates = LinkedHashSet<String>().apply {
            if (folder != null) {
                add("$folder/160p30/index-dvr.m3u8")
                add("$folder/360p30/index-dvr.m3u8")
                add("$folder/480p30/index-dvr.m3u8")
            }
            videoFallback?.let { add(it) }
            if (folder != null) {
                add("$folder/chunked/index-dvr.m3u8")
            }
        }
        if (candidates.isEmpty()) {
            return
        }
        videoLoading = true
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                candidates.firstOrNull { url ->
                    try {
                        client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            videoLoading = false
            log("video frames $url")
            if (url != null) {
                videoBase = url
                if ((frame.isShown || seekInfoBox.isShown) && lastPosition >= 0 && lastDuration > 0L) {
                    show(lastPosition, lastDuration, lastDeltaMs, lastTargetLabel)
                }
            }
        }
    }

    private fun log(message: String) {
        if (com.github.andreyasadchy.xtra.BuildConfig.DEBUG) {
            android.util.Log.d("XtraTv", "seekPreview $message")
        }
    }

    private fun formatSignedDelta(deltaMs: Long): String {
        val totalSeconds = (abs(deltaMs) + 500L) / 1000L
        val sign = if (deltaMs >= 0L) "+" else "-"
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%s%d:%02d:%02d", sign, hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%s%d:%02d", sign, minutes, seconds)
        }
    }

    fun show(
        position: Long,
        duration: Long,
        deltaMs: Long? = null,
        targetLabel: String? = null,
    ) {
        container.removeCallbacks(hideAction)
        container.removeCallbacks(releaseAction)
        if (duration <= 0) {
            return
        }
        lastPosition = position
        lastDuration = duration
        lastDeltaMs = deltaMs
        lastTargetLabel = targetLabel

        if (deltaMs != null) {
            deltaTextView.text = formatSignedDelta(deltaMs)
            val baseAccent = if (com.github.andreyasadchy.xtra.ui.tv.TvAccent.current != null) {
                com.github.andreyasadchy.xtra.ui.tv.TvAccent.color
            } else {
                0xFFA970FF.toInt()
            }
            deltaTextView.setTextColor(ColorUtils.blendARGB(baseAccent, Color.WHITE, 0.42f))
            targetTimeTextView.text = targetLabel ?: DateUtils.formatElapsedTime((position / 1000L).coerceAtLeast(0L))
            val baseEpoch = startEpochMs
            if (baseEpoch != null && baseEpoch > 0L) {
                localClockTextView.text = android.text.format.DateFormat.getTimeFormat(container.context)
                    .format(java.util.Date(baseEpoch + position.coerceAtLeast(0L)))
                clockSeparatorTextView.visibility = View.VISIBLE
                clockIconView.visibility = View.VISIBLE
                localClockTextView.visibility = View.VISIBLE
            } else {
                clockSeparatorTextView.visibility = View.GONE
                clockIconView.visibility = View.GONE
                localClockTextView.visibility = View.GONE
            }
            seekInfoBox.visibility = View.VISIBLE
        } else {
            seekInfoBox.visibility = View.GONE
        }

        if (videoBase != null) {
            showVideo(position)
            place(position, duration, showThumbnail = hasRenderedVideoFrame)
            return
        }
        if (infoUrl != null && (board == null || (refresh && System.currentTimeMillis() - boardLoadedAt > 60_000L))) {
            loadBoard()
        }
        val currentBoard = board
        if (currentBoard == null || currentBoard.interval <= 0) {
            if (infoUrl == null && videoFallback != null) {
                useVideo()
            }
            place(position, duration, showThumbnail = false)
            return
        }
        val perImage = currentBoard.cols * currentBoard.rows
        val index = (position / 1000.0 / currentBoard.interval).toInt()
        val url = currentBoard.images.getOrNull(index / perImage)
        if (url == null) {
            // past the end of the storyboard of a growing video: the frames of the video
            if (refresh) {
                useVideo()
            }
            place(position, duration, showThumbnail = false)
            return
        }
        val cell = index % perImage
        val sprite = sprites[url]
        if (sprite != null) {
            val x = (cell % currentBoard.cols) * currentBoard.width
            val y = (cell / currentBoard.cols) * currentBoard.height
            if (x + currentBoard.width <= sprite.width && y + currentBoard.height <= sprite.height) {
                if (url != lastRenderedSpriteUrl || cell != lastRenderedSpriteCell) {
                    val w = currentBoard.width
                    val h = currentBoard.height
                    var dstBmp = croppedBitmap
                    if (dstBmp == null || dstBmp.width != w || dstBmp.height != h) {
                        dstBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        croppedBitmap = dstBmp
                        cropCanvas = Canvas(dstBmp)
                    }
                    cropSrcRect.set(x, y, x + w, y + h)
                    cropDstRect.set(0, 0, w, h)
                    cropCanvas?.drawBitmap(sprite, cropSrcRect, cropDstRect, null)
                    imageView.setImageBitmap(dstBmp)
                    imageView.invalidate()
                    lastRenderedSpriteUrl = url
                    lastRenderedSpriteCell = cell
                }
                hasPreviewBitmap = true
            }
        } else if (activeSpriteUrl != url) {
            // LIFO priority during fast long-press scrubbing: cancel any skipped sprite sheet download
            // so the sprite sheet currently under the cursor downloads immediately.
            activeSpriteCall?.cancel()
            activeSpriteJob?.cancel()
            activeSpriteUrl = url
            val call = client.newCall(Request.Builder().url(url).build())
            activeSpriteCall = call
            activeSpriteJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        call.execute().use { response ->
                            if (response.isSuccessful) BitmapFactory.decodeStream(response.body.byteStream()) else null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (activeSpriteUrl == url) {
                    activeSpriteUrl = null
                    activeSpriteCall = null
                }
                if (bitmap != null) {
                    sprites[url] = bitmap
                    if (frame.isShown || seekInfoBox.isShown) {
                        show(lastPosition, lastDuration, lastDeltaMs, lastTargetLabel)
                    }
                }
            }
        }
        place(position, duration, showThumbnail = hasPreviewBitmap)
    }

    /** Called on key release so any throttled long-press video seek snaps immediately to the exact target position. */
    fun onScrubGestureIdle() {
        val player = videoPlayer ?: return
        val pending = pendingVideoSeekPos
        if (pending >= 0L) {
            container.removeCallbacks(videoPumpRunnable)
            pendingVideoSeekPos = -1L
            videoSeekInFlight = true
            lastVideoSeekAtMs = SystemClock.elapsedRealtime()
            val windowDuration = player.duration.takeIf { it > 0 }
            player.seekTo(if (windowDuration != null) pending.coerceIn(0, windowDuration) else pending)
        }
    }

    private fun flushPendingVideoSeek() {
        container.removeCallbacks(videoPumpRunnable)
        val player = videoPlayer ?: return
        val pending = pendingVideoSeekPos
        if (pending < 0L) {
            videoSeekInFlight = false
            return
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastVideoSeekAtMs
        val minIntervalMs = if (player.playbackState == Player.STATE_READY) 140L else 240L
        if (videoSeekInFlight && elapsed < minIntervalMs) {
            container.postDelayed(videoPumpRunnable, (minIntervalMs - elapsed).coerceAtLeast(30L))
            return
        }
        pendingVideoSeekPos = -1L
        videoSeekInFlight = true
        lastVideoSeekAtMs = now
        val windowDuration = player.duration.takeIf { it > 0 }
        player.seekTo(if (windowDuration != null) pending.coerceIn(0, windowDuration) else pending)
        container.postDelayed(videoPumpRunnable, 240L)
    }

    private fun showVideo(position: Long) {
        val url = videoBase ?: return
        val player = videoPlayer ?: run {
            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(container.context)
                .setEnableDecoderFallback(true)
            ExoPlayer.Builder(container.context, renderersFactory)
                .setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                .build().also { player ->
                    val texture = TextureView(container.context)
                    frame.addView(texture, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    textureView = texture
                    imageView.visibility = View.GONE
                    player.volume = 0f
                    player.playWhenReady = false
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                        .build()
                    // the key frames of the segments: the frame comes fast, a few seconds from the chosen position at most
                    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    val listener = object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            hasRenderedVideoFrame = true
                            videoSeekInFlight = false
                            if (seekInfoBox.isShown && lastPosition >= 0L && lastDuration > 0L) {
                                place(lastPosition, lastDuration, showThumbnail = true)
                            }
                            if (pendingVideoSeekPos >= 0L) {
                                flushPendingVideoSeek()
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                if (!hasRenderedVideoFrame && seekInfoBox.isShown && lastPosition >= 0L && lastDuration > 0L) {
                                    hasRenderedVideoFrame = true
                                    place(lastPosition, lastDuration, showThumbnail = true)
                                }
                                videoSeekInFlight = false
                                if (pendingVideoSeekPos >= 0L) {
                                    flushPendingVideoSeek()
                                }
                            }
                        }
                    }
                    videoPlayerListener = listener
                    player.addListener(listener)
                    player.setVideoTextureView(texture)
                    player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                    player.prepare()
                    videoPlayer = player
                }
        }
        val windowDuration = player.duration.takeIf { it > 0 }
        val targetPos = if (windowDuration != null) position.coerceIn(0, windowDuration) else position
        val now = SystemClock.elapsedRealtime()
        if (!videoSeekInFlight || now - lastVideoSeekAtMs >= 240L) {
            container.removeCallbacks(videoPumpRunnable)
            pendingVideoSeekPos = -1L
            videoSeekInFlight = true
            lastVideoSeekAtMs = now
            player.seekTo(targetPos)
            container.postDelayed(videoPumpRunnable, 240L)
        } else {
            // Coalesce 50ms key-repeats during long-press so ExoPlayer finishes decoding the current keyframe
            // before jumping to the latest position.
            pendingVideoSeekPos = targetPos
        }
    }

    /** Above the timeline, following the scrubber. */
    private fun place(position: Long, duration: Long, showThumbnail: Boolean) {
        val barLocation = IntArray(2)
        val containerLocation = IntArray(2)
        timeBar.getLocationInWindow(barLocation)
        container.getLocationInWindow(containerLocation)
        val barLeft = (barLocation[0] - containerLocation[0] + timeBar.paddingLeft).toFloat()
        val barTrackWidth = (timeBar.width - timeBar.paddingLeft - timeBar.paddingRight).coerceAtLeast(1).toFloat()
        val frac = (position.toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f)
        val scrubberX = barLeft + barTrackWidth * frac

        var nextBottomY = (barLocation[1] - containerLocation[1]).toFloat() - 8f * density
        if (seekInfoBox.visibility == View.VISIBLE) {
            seekInfoBox.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val pillW = seekInfoBox.measuredWidth.coerceAtLeast(1)
            val pillH = seekInfoBox.measuredHeight.coerceAtLeast(1)
            val edgeMargin = 16f * density
            val maxPillX = (container.width - pillW - edgeMargin).coerceAtLeast(edgeMargin)
            seekInfoBox.translationX = (scrubberX - pillW / 2f).coerceIn(edgeMargin, maxPillX)
            val pillY = nextBottomY - pillH
            seekInfoBox.translationY = pillY
            nextBottomY = pillY - 6f * density
        }

        if (showThumbnail) {
            val activeChapter = chapters.lastOrNull { (it.vodPosition ?: 0).toLong() <= position }?.name?.takeIf { it.isNotBlank() }
            if (activeChapter != null) {
                chapterLabel.text = activeChapter
                chapterLabel.visibility = View.VISIBLE
            } else {
                chapterLabel.visibility = View.GONE
            }
            frame.translationX = (scrubberX - previewWidth / 2f).coerceIn(0f, (container.width - previewWidth).toFloat().coerceAtLeast(0f))
            frame.translationY = nextBottomY - previewHeight
            frame.visibility = View.VISIBLE
        } else {
            frame.visibility = View.GONE
        }
    }

    fun hide() {
        container.removeCallbacks(hideAction)
        container.removeCallbacks(videoPumpRunnable)
        pendingVideoSeekPos = -1L
        videoSeekInFlight = false
        frame.visibility = View.GONE
        seekInfoBox.visibility = View.GONE
        if (videoPlayer != null) {
            container.removeCallbacks(releaseAction)
            container.postDelayed(releaseAction, 15_000L)
        }
    }

    private fun releaseVideo() {
        container.removeCallbacks(videoPumpRunnable)
        pendingVideoSeekPos = -1L
        videoSeekInFlight = false
        hasRenderedVideoFrame = false
        videoPlayerListener?.let { videoPlayer?.removeListener(it) }
        videoPlayerListener = null
        videoPlayer?.release()
        videoPlayer = null
        textureView?.let { frame.removeView(it) }
        textureView = null
        imageView.visibility = View.VISIBLE
    }

    /** After a short press the thumbnail stays a moment, long enough to see it. */
    fun hideLater(delay: Long = 1200L) {
        container.removeCallbacks(hideAction)
        if (frame.isShown || seekInfoBox.isShown) {
            container.postDelayed(hideAction, delay)
        }
    }

    fun release() {
        container.removeCallbacks(hideAction)
        container.removeCallbacks(releaseAction)
        container.removeCallbacks(videoPumpRunnable)
        activeSpriteCall?.cancel()
        activeSpriteCall = null
        activeSpriteJob?.cancel()
        activeSpriteJob = null
        activeSpriteUrl = null
        hide()
        releaseVideo()
        sprites.clear()
        croppedBitmap = null
        cropCanvas = null
        lastRenderedSpriteUrl = null
        lastRenderedSpriteCell = -1
    }

    companion object {
        fun showChaptersPanel(
            context: Context,
            title: String,
            chapters: List<Game>,
            currentPositionMs: Long,
            onSeek: (Long) -> Unit,
        ) {
            if (chapters.isEmpty()) return
            val activeIndex = chapters.indexOfLast { (it.vodPosition ?: 0).toLong() <= currentPositionMs }.coerceAtLeast(0)
            TvDialogs.show(context) { dismiss ->
                SidePanel(title = title, width = 460.dp) {
                    val focusRequester = remember { FocusRequester() }
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (activeIndex - 2).coerceAtLeast(0))
                    val localCtx = LocalContext.current
                    LazyColumn(state = listState) {
                        itemsIndexed(chapters) { index, game ->
                            val isActive = index == activeIndex
                            val startSec = (game.vodPosition ?: 0).toLong() / 1000L
                            val timeText = DateUtils.formatElapsedTime(startSec)
                            val durText = game.vodDuration?.div(1000)?.takeIf { it > 0 }?.toString()?.let {
                                TwitchApiHelper.getDurationFromSeconds(localCtx, it)
                            }
                            FocusRow(
                                modifier = if (index == activeIndex) Modifier.focusRequester(focusRequester) else Modifier,
                                onClick = {
                                    dismiss()
                                    onSeek((game.vodPosition ?: 0).toLong())
                                },
                            ) { focused ->
                                val titleColor = if (focused) TvColors.onFocused else ComposeColor.White
                                val subColor = if (focused) TvColors.onFocused.copy(alpha = 0.78f) else TvColors.secondaryText
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!game.boxArt.isNullOrBlank()) {
                                        AsyncImage(
                                            model = remember(game.boxArt) {
                                                ImageRequest.Builder(localCtx)
                                                    .data(game.boxArt)
                                                    .crossfade(true)
                                                    .build()
                                            },
                                            contentDescription = game.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .width(38.dp)
                                                .height(50.dp)
                                                .clip(RoundedCornerShape(6.dp)),
                                        )
                                        Spacer(Modifier.width(14.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = game.name ?: title,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = titleColor,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = if (!durText.isNullOrBlank()) "$timeText · $durText" else timeText,
                                            fontSize = 13.sp,
                                            color = subColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (isActive) {
                                        Spacer(Modifier.width(10.dp))
                                        Box(
                                            Modifier
                                                .size(9.dp)
                                                .background(if (focused) TvColors.onFocused else TvColors.accent, CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        runCatching { focusRequester.requestFocus() }
                    }
                }
            }
        }
    }
}
