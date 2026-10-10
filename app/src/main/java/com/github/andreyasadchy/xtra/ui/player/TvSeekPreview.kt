package com.github.andreyasadchy.xtra.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * TV: thumbnail shown above the timeline while seeking in a VOD, cut from the storyboard
 * sprite sheets Twitch generates for every video.
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
    private val imageView = ImageView(container.context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
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
    private val previewWidth = (240 * density).toInt()
    private val previewHeight = (135 * density).toInt()

    init {
        container.addView(imageView, ViewGroup.LayoutParams(previewWidth, previewHeight))
    }

    /** [previewUrl] is the animated preview URL of the video, the storyboards are next to it. */
    fun load(previewUrl: String, videoId: String) {
        val base = previewUrl.substringBeforeLast("/")
        scope.launch {
            board = withContext(Dispatchers.IO) {
                try {
                    client.newCall(Request.Builder().url("$base/$videoId-info.json").build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@withContext null
                        }
                        val array = JSONArray(response.body.string())
                        val entries = (0 until array.length()).map { array.getJSONObject(it) }
                        val entry = entries.find { it.optString("quality") == "high" } ?: entries.firstOrNull() ?: return@withContext null
                        val images = entry.getJSONArray("images").let { list -> (0 until list.length()).map { "$base/${list.getString(it)}" } }
                        Board(entry.getInt("width"), entry.getInt("height"), entry.getInt("cols"), entry.getInt("rows"), entry.getDouble("interval"), images)
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun show(position: Long, duration: Long) {
        val board = board ?: return
        if (duration <= 0 || board.interval <= 0) {
            return
        }
        lastPosition = position
        val perImage = board.cols * board.rows
        val index = (position / 1000.0 / board.interval).toInt()
        val url = board.images.getOrNull(index / perImage) ?: board.images.lastOrNull() ?: return
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
                    if (imageView.isShown) {
                        show(lastPosition, duration)
                    }
                }
            }
        }
        // above the timeline, following the scrubber
        val barLocation = IntArray(2)
        val containerLocation = IntArray(2)
        timeBar.getLocationInWindow(barLocation)
        container.getLocationInWindow(containerLocation)
        val scrubberX = barLocation[0] - containerLocation[0] + timeBar.width * (position.toFloat() / duration)
        imageView.translationX = (scrubberX - previewWidth / 2f).coerceIn(0f, (container.width - previewWidth).toFloat().coerceAtLeast(0f))
        imageView.translationY = barLocation[1] - containerLocation[1] - previewHeight - 12 * density
        imageView.visibility = View.VISIBLE
    }

    fun hide() {
        imageView.visibility = View.GONE
    }
}
