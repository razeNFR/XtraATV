package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val sprites = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()
    private var lastPosition = -1L
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
    private val previewWidth = (240 * density).toInt()
    private val previewHeight = (135 * density).toInt()
    private val hideAction = Runnable { hide() }

    // the hidden player takes a video decoder: it is let go when the timeline is not used anymore
    private val releaseAction = Runnable { releaseVideo() }

    init {
        frame.addView(imageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(
            chapterLabel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        container.addView(frame, ViewGroup.LayoutParams(previewWidth, previewHeight))
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
    private var textureView: TextureView? = null
    private var videoLoading = false

    /**
     * [infoUrl] is the storyboard description of the video (seekPreviewsURL), the sprites are next to it.
     * [refresh]: the video is still being recorded, its storyboards grow, they are loaded again from time to time.
     * [videoPlaylist]: playlist of the video, its frames are used when the storyboard is not there.
     */
    fun load(infoUrl: String?, refresh: Boolean = false, videoPlaylist: String? = null) {
        this.infoUrl = infoUrl
        this.refresh = refresh
        this.videoFallback = videoPlaylist
        if (infoUrl != null) {
            loadBoard()
        } else {
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
        val candidates = buildList {
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
                if (frame.isShown && lastPosition >= 0) {
                    showVideo(lastPosition)
                }
            }
        }
    }

    private fun log(message: String) {
        if (com.github.andreyasadchy.xtra.BuildConfig.DEBUG) {
            android.util.Log.d("XtraTv", "seekPreview $message")
        }
    }

    fun show(position: Long, duration: Long) {
        container.removeCallbacks(hideAction)
        container.removeCallbacks(releaseAction)
        if (duration <= 0) {
            return
        }
        lastPosition = position
        if (videoBase != null) {
            showVideo(position)
            place(position, duration)
            return
        }
        if (infoUrl != null && (board == null || (refresh && System.currentTimeMillis() - boardLoadedAt > 60_000L))) {
            loadBoard()
        }
        val board = board ?: return
        if (board.interval <= 0) {
            return
        }
        val perImage = board.cols * board.rows
        val index = (position / 1000.0 / board.interval).toInt()
        val url = board.images.getOrNull(index / perImage)
        if (url == null) {
            // past the end of the storyboard of a growing video: the frames of the video
            if (refresh) {
                useVideo()
            }
            return hide()
        }
        val cell = index % perImage
        val sprite = sprites[url]
        if (sprite != null) {
            val x = (cell % board.cols) * board.width
            val y = (cell / board.cols) * board.height
            if (x + board.width <= sprite.width && y + board.height <= sprite.height) {
                imageView.setImageBitmap(Bitmap.createBitmap(sprite, x, y, board.width, board.height))
            }
        } else if (loading.add(url)) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            if (response.isSuccessful) BitmapFactory.decodeStream(response.body.byteStream()) else null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                loading.remove(url)
                if (bitmap != null) {
                    sprites[url] = bitmap
                    if (frame.isShown) {
                        show(lastPosition, duration)
                    }
                }
            }
        }
        place(position, duration)
    }

    private fun showVideo(position: Long) {
        val url = videoBase ?: return
        val player = videoPlayer ?: ExoPlayer.Builder(container.context).build().also { player ->
            val texture = TextureView(container.context)
            frame.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            textureView = texture
            imageView.visibility = View.GONE
            player.volume = 0f
            player.playWhenReady = false
            player.repeatMode = Player.REPEAT_MODE_OFF
            // the key frames of the segments: the frame comes fast, a few seconds from the chosen position at most
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            player.setVideoTextureView(texture)
            player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            player.prepare()
            videoPlayer = player
        }
        val windowDuration = player.duration.takeIf { it > 0 }
        player.seekTo(if (windowDuration != null) position.coerceIn(0, windowDuration) else position)
    }

    /** Above the timeline, following the scrubber. */
    private fun place(position: Long, duration: Long) {
        val activeChapter = chapters.lastOrNull { (it.vodPosition ?: 0).toLong() <= position }?.name?.takeIf { it.isNotBlank() }
        if (activeChapter != null) {
            chapterLabel.text = activeChapter
            chapterLabel.visibility = View.VISIBLE
        } else {
            chapterLabel.visibility = View.GONE
        }
        val barLocation = IntArray(2)
        val containerLocation = IntArray(2)
        timeBar.getLocationInWindow(barLocation)
        container.getLocationInWindow(containerLocation)
        val scrubberX = barLocation[0] - containerLocation[0] + timeBar.width * (position.toFloat() / duration)
        frame.translationX = (scrubberX - previewWidth / 2f).coerceIn(0f, (container.width - previewWidth).toFloat().coerceAtLeast(0f))
        frame.translationY = barLocation[1] - containerLocation[1] - previewHeight - 12 * density
        frame.visibility = View.VISIBLE
    }

    fun hide() {
        container.removeCallbacks(hideAction)
        frame.visibility = View.GONE
        if (videoPlayer != null) {
            container.removeCallbacks(releaseAction)
            container.postDelayed(releaseAction, 15_000L)
        }
    }

    private fun releaseVideo() {
        videoPlayer?.release()
        videoPlayer = null
        textureView?.let { frame.removeView(it) }
        textureView = null
    }

    /** After a short press the thumbnail stays a moment, long enough to see it. */
    fun hideLater(delay: Long = 1500L) {
        container.removeCallbacks(hideAction)
        if (frame.isShown) {
            container.postDelayed(hideAction, delay)
        }
    }

    fun release() {
        container.removeCallbacks(hideAction)
        container.removeCallbacks(releaseAction)
        releaseVideo()
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
