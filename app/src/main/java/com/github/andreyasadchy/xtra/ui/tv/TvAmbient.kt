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

    fun updateFromBitmap(context: Context, cacheKey: String?, bitmap: Bitmap?, scope: CoroutineScope) {
        if (!isDynamic(context) || bitmap == null) return
        if (cacheKey != null) {
            colorCache.get(cacheKey)?.let { cached ->
                loadJob?.cancel()
                publishDynamic(context, cached)
                return
            }
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val extracted = withContext(Dispatchers.Default) {
                runCatching { extractColor(bitmap) }.getOrDefault(DEFAULT_DYNAMIC)
            }
            if (cacheKey != null) {
                colorCache.put(cacheKey, extracted)
            }
            publishDynamic(context, extracted)
        }
    }

    fun updateFromUrl(context: Context, url: String?, scope: CoroutineScope) {
        if (!isDynamic(context) || url.isNullOrBlank()) return
        colorCache.get(url)?.let { cached ->
            loadJob?.cancel()
            publishDynamic(context, cached)
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val extracted = withContext(Dispatchers.IO) {
                runCatching {
                    val result = context.imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(url)
                            .size(64, 64)
                            .allowHardware(false)
                            .build()
                    )
                    if (result is SuccessResult) {
                        val bmp = result.image.asDrawable(context.resources).toBitmap(48, 48, Bitmap.Config.ARGB_8888)
                        extractColor(bmp)
                    } else null
                }.getOrNull()
            } ?: return@launch
            colorCache.put(url, extracted)
            publishDynamic(context, extracted)
        }
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

    /** Fast hue-bucketed vibrant color extraction suitable for dark TV backgrounds. */
    fun extractColor(source: Bitmap): Int {
        val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return DEFAULT_DYNAMIC
        } else source
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return DEFAULT_DYNAMIC
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 16).coerceAtLeast(1)
        val weights = FloatArray(12)
        val sumR = FloatArray(12)
        val sumG = FloatArray(12)
        val sumB = FloatArray(12)
        val hsv = FloatArray(3)
        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 180) {
                    Color.colorToHSV(pixel, hsv)
                    val s = hsv[1]
                    val v = hsv[2]
                    if (s >= 0.20f && v in 0.16f..0.96f) {
                        val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                        val weight = s * (1f - kotlin.math.abs(v - 0.6f) * 0.7f).coerceAtLeast(0.2f)
                        weights[bin] += weight
                        sumR[bin] += Color.red(pixel) * weight
                        sumG[bin] += Color.green(pixel) * weight
                        sumB[bin] += Color.blue(pixel) * weight
                    }
                }
                x += stepX
            }
            y += stepY
        }
        var bestBin = -1
        var bestWeight = 0f
        for (i in weights.indices) {
            if (weights[i] > bestWeight) {
                bestWeight = weights[i]
                bestBin = i
            }
        }
        if (bestBin < 0 || bestWeight <= 0.01f) return DEFAULT_DYNAMIC
        val avg = Color.rgb(
            (sumR[bestBin] / bestWeight).toInt().coerceIn(0, 255),
            (sumG[bestBin] / bestWeight).toInt().coerceIn(0, 255),
            (sumB[bestBin] / bestWeight).toInt().coerceIn(0, 255),
        )
        Color.colorToHSV(avg, hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.58f, 0.92f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
