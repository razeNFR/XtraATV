package com.github.andreyasadchy.xtra.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

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
    private val previewWidth = (240 * density).toInt()
    private val previewHeight = (135 * density).toInt()
    private val hideAction = Runnable { hide() }

    // the hidden player takes a video decoder: it is let go when the timeline is not used anymore
    private val releaseAction = Runnable { releaseVideo() }

    init {
        frame.addView(imageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(frame, ViewGroup.LayoutParams(previewWidth, previewHeight))
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
}
