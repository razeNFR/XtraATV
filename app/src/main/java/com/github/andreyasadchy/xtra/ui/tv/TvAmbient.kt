package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.util.LruCache
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV: background of the screens, a soft glow of the ambiance color (Xtra TV settings) in the corners
 * over the color of the theme. Supports fixed colors and a "dynamic" mode following focused artwork.
 */
object TvAmbient {

    private const val DEFAULT_DYNAMIC = 0xFF9147FF.toInt()
    /** In the cache: a picture that was looked at and has no color to give. */
    private const val NO_COLOR = 0

    /** Colors of the setting, "none" keeps the plain background. */
    private val COLORS = mapOf(
        "violet" to 0xFF9147FF.toInt(),
        "blue" to 0xFF3B82F6.toInt(),
        "cyan" to 0xFF06B6D4.toInt(),
        "green" to 0xFF22C55E.toInt(),
        "orange" to 0xFFF97316.toInt(),
        "red" to 0xFFEF4444.toInt(),
        "pink" to 0xFFEC4899.toInt(),
        // light shades
        "sky" to 0xFF7DD3FC.toInt(),
        "turquoise" to 0xFF2DD4BF.toInt(),
        "mint" to 0xFF6EE7B7.toInt(),
        "amber" to 0xFFFBBF24.toInt(),
        "coral" to 0xFFFB7185.toInt(),
        "lavender" to 0xFFC4B5FD.toInt(),
        "white" to 0xFFE5E7EB.toInt(),
    )

    /**
     * Color of the "dynamic" ambiance: the one of the picture focused last (hero, card, banner of a channel). It stays
     * everywhere after that (side menu, settings, screens without pictures) and is saved, the app starts again with it;
     * the violet of Twitch only until a first picture was seen.
     */
    @Volatile
    private var dynamicColor: Int = DEFAULT_DYNAMIC
    private var dynamicLoaded = false

    /** A picture already gave its color since the app started. */
    var hasSessionColor = false
        private set

    private val colorCache = LruCache<String, Int>(80)
    private var dynamicListener: ((Int) -> Unit)? = null
    private var loadJob: Job? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingPublish: Runnable? = null

    private fun loadDynamic(context: Context) {
        if (!dynamicLoaded) {
            dynamicLoaded = true
            dynamicColor = context.prefs().getInt(C.TV_AMBIENT_DYNAMIC_LAST, DEFAULT_DYNAMIC)
        }
    }

    fun isDynamic(context: Context): Boolean =
        context.prefs().getString(C.TV_AMBIENT_COLOR, "violet") == "dynamic"

    fun color(context: Context): Int? {
        val key = context.prefs().getString(C.TV_AMBIENT_COLOR, "violet") ?: "violet"
        return if (key == "dynamic") {
            loadDynamic(context)
            dynamicColor
        } else COLORS[key]
    }

    /** Color of a value of the setting (the dot in front of it in the list), null for "none". */
    fun colorOf(key: String?): Int? = if (key == "dynamic") 0xFFA855F7.toInt() else COLORS[key]

    fun setDynamicListener(listener: ((Int) -> Unit)?) {
        dynamicListener = listener
    }

    /**
     * A screen that goes away stops listening, only if it is still the one listening: a screen made again (after the
     * settings...) is created before the old one is destroyed, the old one took the listener of the new one away and
     * the background never changed color again.
     */
    fun removeDynamicListener(listener: (Int) -> Unit) {
        if (dynamicListener === listener) {
            dynamicListener = null
        }
    }

    fun background(context: Context, base: Int, overrideColor: Int? = null): Drawable {
        val color = overrideColor ?: color(context) ?: return ColorDrawable(base)
        // a light shade glows less, the background stays dark
        val light = Color.luminance(color) > 0.45f
        fun alpha(value: Int) = if (light) value * 3 / 5 else value
        val metrics = context.resources.displayMetrics
        val size = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.12f, 0f)
            gradientRadius = size * 0.75f
            colors = intArrayOf(withAlpha(color, alpha(0x4D)), withAlpha(color, alpha(0x14)), Color.TRANSPARENT)
        }
        val corner = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(1f, 1f)
            gradientRadius = size * 0.55f
            colors = intArrayOf(withAlpha(color, alpha(0x26)), Color.TRANSPARENT)
        }
        return LayerDrawable(arrayOf(ColorDrawable(base), glow, corner))
    }

    /** Smoothly crossfades [root]'s background to the new dynamic [color]. */
    fun applyDynamicToView(root: View, base: Int, color: Int) {
        val next = background(root.context, base, color)
        val previous = root.background
        if (previous != null) {
            val currentLayer = if (previous is TransitionDrawable && previous.numberOfLayers > 1) {
                previous.getDrawable(previous.numberOfLayers - 1)
            } else {
                previous
            }
            val transition = TransitionDrawable(arrayOf(currentLayer, next)).apply {
                isCrossFadeEnabled = false
            }
            root.background = transition
            transition.startTransition(420)
        } else {
            root.background = next
        }
    }

    /** A picture already drawn (the picture of a header): its color, kept when the picture has none. */
    fun updateFromBitmap(context: Context, cacheKey: String?, bitmap: Bitmap?, scope: CoroutineScope) {
        if (!isDynamic(context) || bitmap == null) return
        if (cacheKey != null) {
            colorCache.get(cacheKey)?.let { cached ->
                loadJob?.cancel()
                if (cached != NO_COLOR) publishDynamic(context, cached)
                return
            }
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val extracted = withContext(Dispatchers.Default) {
                runCatching { extractColor(bitmap) }.getOrNull()
            }
            if (cacheKey != null) {
                colorCache.put(cacheKey, extracted ?: NO_COLOR)
            }
            if (extracted != null) publishDynamic(context, extracted)
        }
    }

    fun updateFromUrl(context: Context, url: String?, scope: CoroutineScope) {
        updateFromPictures(context, scope, listOf(url))
    }

    /**
     * The one way every screen gives the picture that has the focus: its addresses, the best first (the thumbnail, then
     * the avatar of the channel). The first one that has a color gives it; when none has (grey or black pictures, no
     * network), the color of before stays.
     */
    fun updateFromPictures(context: Context, scope: CoroutineScope, urls: List<String?>) {
        if (!isDynamic(context)) return
        val list = urls.filterNot { it.isNullOrBlank() }.map { it!! }.distinct()
        if (list.isEmpty()) return
        loadJob?.cancel()
        // all known already: no loading
        var allCached = true
        for (url in list) {
            val cached = colorCache.get(url)
            if (cached == null) {
                allCached = false
                break
            }
            if (cached != NO_COLOR) {
                publishDynamic(context, cached)
                return
            }
        }
        if (allCached) return
        val app = context.applicationContext
        loadJob = scope.launch {
            for (url in list) {
                val cached = colorCache.get(url)
                val color = if (cached != null) cached else {
                    val extracted = withContext(Dispatchers.IO) {
                        runCatching {
                            val result = app.imageLoader.execute(
                                ImageRequest.Builder(app)
                                    .data(url)
                                    .size(64, 64)
                                    .allowHardware(false)
                                    .build()
                            )
                            if (result is SuccessResult) {
                                // NO_COLOR: loaded, and it has no color. null: not loaded, tried again next time
                                extractColor(result.image.asDrawable(app.resources).toBitmap(48, 48, Bitmap.Config.ARGB_8888)) ?: NO_COLOR
                            } else null
                        }.getOrNull()
                    }
                    if (extracted != null) colorCache.put(url, extracted)
                    extracted
                }
                if (color != null && color != NO_COLOR) {
                    publishDynamic(context, color)
                    return@launch
                }
            }
        }
    }

    /**
     * The item of a list that has the focus (a stream, a video, a clip, a category, a channel, a bookmark, a download):
     * its pictures. False when it is of a kind that has none known here.
     */
    fun updateFromItem(context: Context, item: Any?, scope: CoroutineScope): Boolean {
        val urls = picturesOf(item) ?: return false
        updateFromPictures(context, scope, urls)
        return true
    }

    fun picturesOf(item: Any?): List<String?>? = when (item) {
        is com.github.andreyasadchy.xtra.model.ui.Stream -> listOf(item.thumbnail?.takeUnless { item.thumbnailURL.isNullOrBlank() }, item.channelImage)
        is com.github.andreyasadchy.xtra.model.ui.Video -> listOf(item.thumbnail?.takeUnless { TvVideoThumbnail.isMissing(item.thumbnailURL) }, item.channelImage)
        is com.github.andreyasadchy.xtra.model.ui.Clip -> listOf(item.thumbnail, item.channelImage)
        is com.github.andreyasadchy.xtra.model.ui.Game -> listOf(item.boxArt)
        is com.github.andreyasadchy.xtra.model.ui.User -> listOf(item.profileImage)
        is com.github.andreyasadchy.xtra.model.ui.Bookmark -> listOf(item.thumbnail, item.userLogo)
        is com.github.andreyasadchy.xtra.model.ui.OfflineVideo -> listOf(item.thumbnail, item.channelLogo)
        is TvResumeHistory.Item -> listOf(item.thumbnail?.takeUnless { TvVideoThumbnail.isMissing(it) }, item.channelImage)
        else -> null
    }

    /**
     * The new color, for the glow of the background and the accent of the app at once. After a short stop only: moving
     * fast over a row of cards does not make the whole screen flash from one color to the next.
     */
    private fun publishDynamic(context: Context, color: Int) {
        val app = context.applicationContext
        handler.post {
            pendingPublish?.let { handler.removeCallbacks(it) }
            hasSessionColor = true
            val publish = Runnable {
                pendingPublish = null
                if (dynamicColor != color || TvAccent.current != color) {
                    dynamicColor = color
                    dynamicLoaded = true
                    app.prefs().edit().putInt(C.TV_AMBIENT_DYNAMIC_LAST, color).apply()
                    TvAccent.update(color)
                    dynamicListener?.invoke(color)
                }
            }
            pendingPublish = publish
            handler.postDelayed(publish, 220L)
        }
    }

    /**
     * The color of a picture: the one that covers the most of it. Its pixels with a color (not grey, not nearly black)
     * are sorted by hue in 24 families; the family that holds the most pixels wins, with its neighbours (a color often
     * sits across two families). A vivid pixel counts a little more than a dull one, only enough to choose between two
     * families of the same size: a small bright detail (an alert, the frame of a camera) no longer wins over the
     * background. Null when the picture has too little color to give one (grey, black): the color of before is kept.
     */
    fun extractColor(source: Bitmap): Int? {
        val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else source
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        // at most about 48 x 48 points, spread over the whole picture
        val stepX = (w / 48).coerceAtLeast(1)
        val stepY = (h / 48).coerceAtLeast(1)
        val pixels = ArrayList<Int>(2400)
        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                pixels.add(bitmap.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        return dominantColor(pixels.toIntArray())
    }

    fun dominantColor(pixels: IntArray): Int? {
        val bins = 24
        val weights = FloatArray(bins)
        val sumR = FloatArray(bins)
        val sumG = FloatArray(bins)
        val sumB = FloatArray(bins)
        val hsv = FloatArray(3)
        var total = 0
        var colored = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 180) continue
            total++
            Color.colorToHSV(pixel, hsv)
            val s = hsv[1]
            val v = hsv[2]
            if (s < 0.18f || v < 0.14f) continue
            colored++
            val bin = ((hsv[0] / (360f / bins)).toInt()).coerceIn(0, bins - 1)
            // the surface first: each pixel counts for 1, up to 1.35 for the most vivid
            val weight = 1f + 0.35f * s
            weights[bin] += weight
            sumR[bin] += Color.red(pixel) * weight
            sumG[bin] += Color.green(pixel) * weight
            sumB[bin] += Color.blue(pixel) * weight
        }
        // too little color in the picture (under 8 % of it): no color
        if (total == 0 || colored < total * 0.08f) return null
        var best = -1
        var bestWeight = 0f
        for (i in 0 until bins) {
            val around = weights[i] + 0.6f * (weights[(i + 1) % bins] + weights[(i + bins - 1) % bins])
            if (around > bestWeight) {
                bestWeight = around
                best = i
            }
        }
        if (best < 0) return null
        var r = 0f
        var g = 0f
        var b = 0f
        var weight = 0f
        for (offset in -1..1) {
            val i = (best + offset + bins) % bins
            r += sumR[i]
            g += sumG[i]
            b += sumB[i]
            weight += weights[i]
        }
        if (weight <= 0f) return null
        val average = Color.rgb((r / weight).toInt().coerceIn(0, 255), (g / weight).toInt().coerceIn(0, 255), (b / weight).toInt().coerceIn(0, 255))
        // the same hue, bright and vivid enough for a glow and an accent over the dark screens
        Color.colorToHSV(average, hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.58f, 0.92f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
