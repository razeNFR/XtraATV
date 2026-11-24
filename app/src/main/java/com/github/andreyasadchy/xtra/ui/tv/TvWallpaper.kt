package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * TV Home & screens wallpaper engine + live-preview customization side panel:
 * - Procedural vector styles ("AMOLED Topographic", "Gaming Prisms", "Ribbons", "Aurora") that automatically adopt
 *   the active ambient color (including Dynamic ambiance crossfades).
 * - Custom image wallpaper uploaded over local Wi-Fi via phone QR code (gallery photo or direct image URL).
 * - Live-adjustable Visibility (10..100%) and Blur (0..100%), plus one-click Reset.
 */
object TvWallpaper {

    const val STYLE_DEFAULT = "default"
    const val STYLE_TOPOGRAPHIC = "topographic"
    const val STYLE_PRISMS = "prisms"
    const val STYLE_RIBBONS = "ribbons"
    const val STYLE_AURORA = "aurora"
    const val STYLE_CUSTOM = "custom"

    const val DEFAULT_VISIBILITY = 55
    const val DEFAULT_BLUR = 0

    const val CUSTOM_FILE_NAME = "tv_wallpaper_custom.jpg"
    private const val VECTOR_RENDER_W = 1280
    private const val VECTOR_RENDER_H = 720
    private const val MAX_CUSTOM_W = 1920
    private const val MAX_CUSTOM_H = 1080
    private const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024

    val STYLES = listOf(
        STYLE_DEFAULT,
        STYLE_TOPOGRAPHIC,
        STYLE_PRISMS,
        STYLE_RIBBONS,
        STYLE_AURORA,
        STYLE_CUSTOM,
    )

    @Volatile
    private var customFileStamp: Long = 0L
    @Volatile
    private var cachedCustomRawStamp: Long = -1L
    @Volatile
    private var cachedCustomRawBitmap: Bitmap? = null

    private val renderedCache = object : LruCache<String, Bitmap>(6) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun customFile(context: Context): File = File(context.applicationContext.filesDir, CUSTOM_FILE_NAME)

    fun hasCustomImage(context: Context): Boolean {
        val f = customFile(context)
        return f.isFile && f.length() > 128L
    }

    fun style(context: Context): String {
        val raw = context.prefs().getString(C.TV_WALLPAPER, STYLE_DEFAULT) ?: STYLE_DEFAULT
        if (raw !in STYLES) return STYLE_DEFAULT
        if (raw == STYLE_CUSTOM && !hasCustomImage(context)) return STYLE_DEFAULT
        return raw
    }

    fun isActive(context: Context): Boolean = style(context) != STYLE_DEFAULT

    fun visibility(context: Context): Int =
        context.prefs().getInt(C.TV_WALLPAPER_VISIBILITY, DEFAULT_VISIBILITY).coerceIn(10, 100)

    fun blur(context: Context): Int =
        context.prefs().getInt(C.TV_WALLPAPER_BLUR, DEFAULT_BLUR).coerceIn(0, 100)

    fun styleLabel(context: Context, styleKey: String): String = when (styleKey) {
        STYLE_TOPOGRAPHIC -> context.getString(R.string.tv_wallpaper_topographic)
        STYLE_PRISMS -> context.getString(R.string.tv_wallpaper_prisms)
        STYLE_RIBBONS -> context.getString(R.string.tv_wallpaper_ribbons)
        STYLE_AURORA -> context.getString(R.string.tv_wallpaper_aurora)
        STYLE_CUSTOM -> context.getString(R.string.tv_wallpaper_custom)
        else -> context.getString(R.string.tv_wallpaper_default)
    }

    fun summary(context: Context): String = styleLabel(context, style(context))

    fun invalidateCaches() {
        customFileStamp = System.nanoTime()
        cachedCustomRawStamp = -1L
        cachedCustomRawBitmap = null
        renderedCache.evictAll()
    }

    fun readCustomBytesForBackup(context: Context): ByteArray? {
        val f = customFile(context)
        if (!f.isFile || f.length() <= 128L) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun restoreCustomBytesFromBackup(context: Context, bytes: ByteArray?) {
        val f = customFile(context)
        if (bytes == null || bytes.isEmpty()) {
            if (f.exists()) f.delete()
            invalidateCaches()
            return
        }
        runCatching {
            val tmp = File(f.parentFile, "$CUSTOM_FILE_NAME.tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
        invalidateCaches()
    }

    /**
     * Builds the full-screen background [Drawable] when a non-default wallpaper style is active,
     * or returns `null` when [STYLE_DEFAULT] is active so [TvAmbient] uses its classic corner glow.
     */
    fun createBackground(context: Context, baseColor: Int, ambientColor: Int?): Drawable? {
        val activeStyle = style(context)
        if (activeStyle == STYLE_DEFAULT) return null

        val vis = visibility(context)
        val blurPct = blur(context)
        val effectiveAccent = ambientColor ?: 0xFFCBD5E1.toInt()
        val stamp = if (activeStyle == STYLE_CUSTOM) {
            if (customFileStamp == 0L) customFile(context).lastModified() else customFileStamp
        } else 0L

        val cacheKey = "$activeStyle|$baseColor|$effectiveAccent|${ambientColor != null}|$vis|$blurPct|$stamp"
        val bitmap = renderedCache.get(cacheKey) ?: run {
            val built = when (activeStyle) {
                STYLE_CUSTOM -> buildCustomWallpaperBitmap(context, baseColor, ambientColor, vis, blurPct, stamp)
                else -> buildVectorWallpaperBitmap(activeStyle, baseColor, effectiveAccent, ambientColor != null, vis, blurPct)
            } ?: return null
            renderedCache.put(cacheKey, built)
            built
        }
        return CenterCropBitmapDrawable(bitmap, baseColor)
    }

    private fun loadCustomRawBitmap(context: Context, stamp: Long): Bitmap? {
        val cached = cachedCustomRawBitmap
        if (cached != null && !cached.isRecycled && cachedCustomRawStamp == stamp) {
            return cached
        }
        val file = customFile(context)
        if (!file.isFile || file.length() <= 128L) return null
        val decoded = runCatching {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull() ?: return null
        cachedCustomRawBitmap = decoded
        cachedCustomRawStamp = stamp
        return decoded
    }

    private fun buildCustomWallpaperBitmap(
        context: Context,
        baseColor: Int,
        ambientColor: Int?,
        visibilityPct: Int,
        blurPct: Int,
        stamp: Long,
    ): Bitmap? {
        val raw = loadCustomRawBitmap(context, stamp) ?: return null
        val outW = if (blurPct > 0) VECTOR_RENDER_W else min(raw.width.coerceAtLeast(640), MAX_CUSTOM_W)
        val outH = if (blurPct > 0) VECTOR_RENDER_H else min(raw.height.coerceAtLeast(360), MAX_CUSTOM_H)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(baseColor)

        // Center-crop raw into out
        val scale = max(outW.toFloat() / raw.width.toFloat(), outH.toFloat() / raw.height.toFloat())
        val dx = (outW - raw.width * scale) * 0.5f
        val dy = (outH - raw.height * scale) * 0.5f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(raw, matrix, imgPaint)

        if (blurPct > 0) {
            val radius = ((blurPct / 100f) * 34f).roundToInt().coerceIn(1, 36)
            stackBlurInPlace(out, radius)
        }

        // Visibility controls how brightly the photo shines through vs protective dark TV readability scrim
        val visFrac = (visibilityPct / 100f).coerceIn(0.10f, 1f)
        val scrimAlpha = ((1f - visFrac * 0.84f) * 255f).roundToInt().coerceIn(36, 232)
        canvas.drawColor(Color.argb(scrimAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)))

        // Subtle left & bottom legibility vignette so side rail and text rows always stay crisp
        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        vignettePaint.shader = LinearGradient(
            0f, 0f, outW * 0.42f, 0f,
            Color.argb((95 * (1.15f - visFrac * 0.45f)).roundToInt().coerceIn(28, 120), 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), vignettePaint)

        // Gentle ambient tint in the top-left corner when ambient color is enabled
        if (ambientColor != null) {
            val light = Color.luminance(ambientColor) > 0.45f
            val glowAlpha = ((if (light) 0x26 else 0x3C) * (0.55f + 0.45f * visFrac)).roundToInt().coerceIn(0, 110)
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    outW * 0.05f,
                    0f,
                    max(outW, outH) * 0.72f,
                    withAlpha(ambientColor, glowAlpha),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), glowPaint)
        }

        return out
    }

    private fun buildVectorWallpaperBitmap(
        styleKey: String,
        baseColor: Int,
        accentColor: Int,
        hasAmbientGlow: Boolean,
        visibilityPct: Int,
        blurPct: Int,
    ): Bitmap {
        val w = VECTOR_RENDER_W
        val h = VECTOR_RENDER_H
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val visFrac = (visibilityPct / 100f).coerceIn(0.10f, 1f)
        val light = Color.luminance(accentColor) > 0.48f
        val intensity = visFrac * (if (light) 0.76f else 1.0f)

        when (styleKey) {
            STYLE_TOPOGRAPHIC -> drawTopographic(canvas, w, h, accentColor, hasAmbientGlow, intensity)
            STYLE_PRISMS -> drawGamingPrisms(canvas, w, h, baseColor, accentColor, hasAmbientGlow, intensity)
            STYLE_RIBBONS -> drawRibbons(canvas, w, h, baseColor, accentColor, hasAmbientGlow, intensity)
            STYLE_AURORA -> drawAurora(canvas, w, h, baseColor, accentColor, intensity)
            else -> canvas.drawColor(baseColor)
        }

        if (blurPct > 0) {
            val radius = ((blurPct / 100f) * 30f).roundToInt().coerceIn(1, 32)
            stackBlurInPlace(out, radius)
        }
        return out
    }

    /**
     * Style 1 — AMOLED Topographic:
     * True pitch-black `#000000` OLED canvas with smooth marching-squares elevation contour lines
     * and subtle glowing major ridge contours in the active ambient color.
     */
    private fun drawTopographic(
        canvas: Canvas,
        w: Int,
        h: Int,
        accent: Int,
        hasAmbientGlow: Boolean,
        intensity: Float,
    ) {
        canvas.drawColor(Color.BLACK)

        if (hasAmbientGlow) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            glowPaint.shader = RadialGradient(
                w * 0.08f,
                h * 0.04f,
                w * 0.78f,
                intArrayOf(
                    withAlpha(accent, (0x36 * intensity).roundToInt().coerceIn(0, 90)),
                    withAlpha(accent, (0x10 * intensity).roundToInt().coerceIn(0, 40)),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glowPaint)

            val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w * 0.96f,
                    h * 0.94f,
                    w * 0.56f,
                    withAlpha(accent, (0x24 * intensity).roundToInt().coerceIn(0, 64)),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), cornerPaint)
        }

        val cols = 96
        val rows = 54
        val field = Array(rows + 1) { FloatArray(cols + 1) }
        for (r in 0..rows) {
            val ny = r.toFloat() / rows.toFloat()
            for (c in 0..cols) {
                val nx = c.toFloat() / cols.toFloat()
                // Smooth multi-harmonic terrain with organic ridges and valleys
                val v1 = sin(nx * 4.4f + ny * 2.6f + 0.5f) * 0.42f
                val v2 = cos(nx * 2.7f - ny * 4.9f + 1.3f) * 0.34f
                val v3 = sin((nx * nx + ny * ny) * 5.8f - 0.8f) * 0.24f
                val v4 = sin(nx * 8.2f + cos(ny * 6.4f) * 1.4f) * 0.14f
                val ridge1 = expFalloff(nx - 0.80f, ny - 0.22f, 0.16f) * 0.52f
                val ridge2 = expFalloff(nx - 0.24f, ny - 0.82f, 0.20f) * 0.44f
                field[r][c] = v1 + v2 + v3 + v4 + ridge1 - ridge2
            }
        }

        val cellW = w.toFloat() / cols.toFloat()
        val cellH = h.toFloat() / rows.toFloat()
        val levels = 13
        val minVal = -1.15f
        val maxVal = 1.25f

        val minorPath = Path()
        val majorPath = Path()

        for (lvlIdx in 0 until levels) {
            val iso = minVal + (maxVal - minVal) * (lvlIdx + 0.5f) / levels.toFloat()
            val targetPath = if (lvlIdx % 3 == 1) majorPath else minorPath
            for (r in 0 until rows) {
                val y0 = r * cellH
                val y1 = y0 + cellH
                for (c in 0 until cols) {
                    val x0 = c * cellW
                    val x1 = x0 + cellW
                    val tl = field[r][c]
                    val tr = field[r][c + 1]
                    val br = field[r + 1][c + 1]
                    val bl = field[r + 1][c]

                    var mask = 0
                    if (tl >= iso) mask = mask or 8
                    if (tr >= iso) mask = mask or 4
                    if (br >= iso) mask = mask or 2
                    if (bl >= iso) mask = mask or 1
                    if (mask == 0 || mask == 15) continue

                    fun interp(a: Float, b: Float): Float {
                        val d = b - a
                        return if (abs(d) < 1e-5f) 0.5f else ((iso - a) / d).coerceIn(0f, 1f)
                    }

                    val topX = x0 + interp(tl, tr) * cellW
                    val rightY = y0 + interp(tr, br) * cellH
                    val botX = x0 + interp(bl, br) * cellW
                    val leftY = y0 + interp(tl, bl) * cellH

                    fun seg(ax: Float, ay: Float, bx: Float, by: Float) {
                        targetPath.moveTo(ax, ay)
                        targetPath.lineTo(bx, by)
                    }

                    when (mask) {
                        1, 14 -> seg(x0, leftY, botX, y1)
                        2, 13 -> seg(botX, y1, x1, rightY)
                        3, 12 -> seg(x0, leftY, x1, rightY)
                        4, 11 -> seg(topX, y0, x1, rightY)
                        5 -> {
                            seg(x0, leftY, topX, y0)
                            seg(botX, y1, x1, rightY)
                        }
                        6, 9 -> seg(topX, y0, botX, y1)
                        7, 8 -> seg(x0, leftY, topX, y0)
                        10 -> {
                            seg(topX, y0, x1, rightY)
                            seg(x0, leftY, botX, y1)
                        }
                    }
                }
            }
        }

        val minorAlpha = (0x46 * intensity).roundToInt().coerceIn(10, 120)
        val majorAlpha = (0x9E * intensity).roundToInt().coerceIn(22, 215)
        val glowAlpha = (0x3A * intensity).roundToInt().coerceIn(0, 95)

        val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.05f
            strokeCap = Paint.Cap.ROUND
            color = withAlpha(accent, minorAlpha)
        }
        canvas.drawPath(minorPath, minorPaint)

        if (glowAlpha > 4) {
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4.2f
                strokeCap = Paint.Cap.ROUND
                color = withAlpha(accent, glowAlpha)
                maskFilter = BlurMaskFilter(4.5f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawPath(majorPath, haloPaint)
        }

        val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.75f
            strokeCap = Paint.Cap.ROUND
            color = withAlpha(lightenColor(accent, 0.22f), majorAlpha)
        }
        canvas.drawPath(majorPath, majorPaint)

        // Subtle center readability vignette so contours softly recede behind main content cards
        val centerFade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.45f,
                h * 0.55f,
                w * 0.55f,
                Color.argb(96, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), centerFade)
    }

    /**
     * Style 2 — Gaming Prisms:
     * Low-poly 3D faceted crystal mesh catching directional ambient light with crisp hairline facet edges.
     */
    private fun drawGamingPrisms(
        canvas: Canvas,
        w: Int,
        h: Int,
        baseColor: Int,
        accent: Int,
        hasAmbientGlow: Boolean,
        intensity: Float,
    ) {
        val deepBase = blendColor(Color.BLACK, baseColor, 0.55f)
        canvas.drawColor(deepBase)

        val cols = 10
        val rows = 6
        val vx = Array(rows + 1) { FloatArray(cols + 1) }
        val vy = Array(rows + 1) { FloatArray(cols + 1) }
        val vz = Array(rows + 1) { FloatArray(cols + 1) }

        val cellW = w.toFloat() / cols.toFloat()
        val cellH = h.toFloat() / rows.toFloat()

        for (r in 0..rows) {
            for (c in 0..cols) {
                val seed = (r * 73 + c * 137 + 19)
                val jx = if (c == 0 || c == cols) 0f else (hashUnit(seed) - 0.5f) * cellW * 0.68f
                val jy = if (r == 0 || r == rows) 0f else (hashUnit(seed * 31 + 7) - 0.5f) * cellH * 0.68f
                vx[r][c] = c * cellW + jx
                vy[r][c] = r * cellH + jy
                vz[r][c] = (sin(c * 0.85f + r * 0.65f) * 42f) + (hashUnit(seed * 53 + 11) - 0.5f) * 58f
            }
        }

        // Light vector from top-left-front
        val lx = -0.52f
        val ly = -0.46f
        val lz = 0.72f

        val triPath = Path()
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f
            strokeJoin = Paint.Join.BEVEL
            color = withAlpha(lightenColor(accent, 0.25f), (0x2E * intensity).roundToInt().coerceIn(8, 72))
        }

        fun drawFacet(
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float,
            facetSeed: Int,
        ) {
            val ux = x2 - x1
            val uy = y2 - y1
            val uz = z2 - z1
            val wx = x3 - x1
            val wy = y3 - y1
            val wz = z3 - z1
            var nx = uy * wz - uz * wy
            var ny = uz * wx - ux * wz
            var nz = abs(ux * wy - uy * wx) * 0.012f + 1f
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
            nx /= len
            ny /= len
            nz /= len

            val ndotl = (nx * lx + ny * ly + nz * lz).coerceIn(0f, 1f)
            val cx = (x1 + x2 + x3) / (3f * w)
            val cy = (y1 + y2 + y3) / (3f * h)
            val cornerBoost = (1f - min(1f, hypotNorm(cx - 0.12f, cy - 0.14f) * 1.15f)) * 0.55f +
                (1f - min(1f, hypotNorm(cx - 0.90f, cy - 0.86f) * 1.35f)) * 0.35f

            val sparkle = if (hashUnit(facetSeed * 97 + 3) > 0.86f) 0.18f else 0f
            val tintStrength = ((0.05f + ndotl * 0.28f + cornerBoost * 0.26f + sparkle) * intensity).coerceIn(0.02f, 0.68f)

            fillPaint.color = blendColor(deepBase, accent, tintStrength)
            triPath.reset()
            triPath.moveTo(x1, y1)
            triPath.lineTo(x2, y2)
            triPath.lineTo(x3, y3)
            triPath.close()
            canvas.drawPath(triPath, fillPaint)
            canvas.drawPath(triPath, edgePaint)
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val flip = ((r + c) and 1) == 0
                val seedBase = r * 31 + c * 17
                if (flip) {
                    drawFacet(
                        vx[r][c], vy[r][c], vz[r][c],
                        vx[r][c + 1], vy[r][c + 1], vz[r][c + 1],
                        vx[r + 1][c + 1], vy[r + 1][c + 1], vz[r + 1][c + 1],
                        seedBase,
                    )
                    drawFacet(
                        vx[r][c], vy[r][c], vz[r][c],
                        vx[r + 1][c + 1], vy[r + 1][c + 1], vz[r + 1][c + 1],
                        vx[r + 1][c], vy[r + 1][c], vz[r + 1][c],
                        seedBase + 101,
                    )
                } else {
                    drawFacet(
                        vx[r][c], vy[r][c], vz[r][c],
                        vx[r][c + 1], vy[r][c + 1], vz[r][c + 1],
                        vx[r + 1][c], vy[r + 1][c], vz[r + 1][c],
                        seedBase,
                    )
                    drawFacet(
                        vx[r][c + 1], vy[r][c + 1], vz[r][c + 1],
                        vx[r + 1][c + 1], vy[r + 1][c + 1], vz[r + 1][c + 1],
                        vx[r + 1][c], vy[r + 1][c], vz[r + 1][c],
                        seedBase + 101,
                    )
                }
            }
        }

        if (hasAmbientGlow) {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w * 0.06f, 0f, w * 0.72f,
                    withAlpha(accent, (0x38 * intensity).roundToInt().coerceIn(0, 90)),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
        }
    }

    /**
     * Style 3 — Ribbons:
     * Smooth layered silk waves sweeping across the screen with soft translucent gradients and luminous crests.
     */
    private fun drawRibbons(
        canvas: Canvas,
        w: Int,
        h: Int,
        baseColor: Int,
        accent: Int,
        hasAmbientGlow: Boolean,
        intensity: Float,
    ) {
        val deepBase = blendColor(Color.BLACK, baseColor, 0.75f)
        canvas.drawColor(deepBase)

        if (hasAmbientGlow) {
            val bgGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w * 0.14f, h * 0.12f, w * 0.85f,
                    withAlpha(accent, (0x34 * intensity).roundToInt().coerceIn(0, 88)),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgGlow)
        }

        val secondaryAccent = shiftHue(accent, 24f)
        val steps = 64

        for (idx in 0 until 5) {
            val baseYFrac = 0.24f + idx * 0.145f
            val amp1 = h * (0.085f + (idx % 2) * 0.03f)
            val amp2 = h * 0.045f
            val phase = idx * 0.92f + 0.35f
            val thickness = h * (0.19f - idx * 0.015f)
            val ribbonColor = if (idx % 2 == 0) accent else secondaryAccent

            val ribbonPath = Path()
            val crestPath = Path()

            for (s in 0..steps) {
                val t = s.toFloat() / steps.toFloat()
                val x = -w * 0.05f + t * w * 1.10f
                val slope = (0.5f - t) * h * 0.22f
                val y = h * baseYFrac + slope +
                    sin(t * PI.toFloat() * 2.15f + phase) * amp1 +
                    cos(t * PI.toFloat() * 3.4f - phase * 0.7f) * amp2
                if (s == 0) {
                    ribbonPath.moveTo(x, y)
                    crestPath.moveTo(x, y)
                } else {
                    ribbonPath.lineTo(x, y)
                    crestPath.lineTo(x, y)
                }
            }
            for (s in steps downTo 0) {
                val t = s.toFloat() / steps.toFloat()
                val x = -w * 0.05f + t * w * 1.10f
                val slope = (0.5f - t) * h * 0.22f
                val twist = 0.55f + 0.45f * sin(t * PI.toFloat() * 1.6f + idx)
                val y = h * baseYFrac + slope +
                    sin(t * PI.toFloat() * 2.15f + phase) * amp1 +
                    cos(t * PI.toFloat() * 3.4f - phase * 0.7f) * amp2 +
                    thickness * twist
                ribbonPath.lineTo(x, y)
            }
            ribbonPath.close()

            val topAlpha = ((0x48 - idx * 5) * intensity).roundToInt().coerceIn(8, 128)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, h * (baseYFrac - 0.15f),
                    w * 0.65f, h * (baseYFrac + 0.30f),
                    intArrayOf(
                        withAlpha(lightenColor(ribbonColor, 0.16f), topAlpha),
                        withAlpha(ribbonColor, (topAlpha * 0.38f).roundToInt()),
                        Color.TRANSPARENT,
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPath(ribbonPath, fillPaint)

            val crestAlpha = ((0x96 - idx * 10) * intensity).roundToInt().coerceIn(16, 195)
            val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (idx == 1) 2.0f else 1.35f
                strokeCap = Paint.Cap.ROUND
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), 0f,
                    intArrayOf(
                        Color.TRANSPARENT,
                        withAlpha(lightenColor(ribbonColor, 0.30f), crestAlpha),
                        withAlpha(lightenColor(ribbonColor, 0.18f), (crestAlpha * 0.7f).roundToInt()),
                        Color.TRANSPARENT,
                    ),
                    floatArrayOf(0f, 0.28f, 0.76f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPath(crestPath, crestPaint)
        }
    }

    /**
     * Style 4 — Aurora:
     * Atmospheric northern-lights curtains & shimmering vertical rays blending the active ambient hue
     * with a harmonious analogous sky hue.
     */
    private fun drawAurora(
        canvas: Canvas,
        w: Int,
        h: Int,
        baseColor: Int,
        accent: Int,
        intensity: Float,
    ) {
        val skyTop = blendColor(Color.BLACK, accent, (0.14f * intensity).coerceIn(0.02f, 0.22f))
        val skyBottom = blendColor(Color.BLACK, baseColor, 0.72f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), skyTop, skyBottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val secondary = shiftHue(accent, 38f)
        val tertiary = shiftHue(accent, -28f)

        // Broad atmospheric nebula clouds behind the curtains
        val cloud1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.28f, h * 0.18f, w * 0.62f,
                withAlpha(accent, (0x52 * intensity).roundToInt().coerceIn(0, 130)),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), cloud1)

        val cloud2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.76f, h * 0.26f, w * 0.56f,
                withAlpha(secondary, (0x46 * intensity).roundToInt().coerceIn(0, 115)),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), cloud2)

        // Shimmering vertical aurora curtain bands
        val rayCount = 34
        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
        }
        val rayRect = RectF()

        for (i in 0 until rayCount) {
            val t = i.toFloat() / (rayCount - 1).toFloat()
            val cx = w * (-0.04f + t * 1.08f)
            val rayW = w * (0.024f + hashUnit(i * 41 + 9) * 0.036f)
            val baseY = h * (0.34f + sin(t * PI.toFloat() * 2.3f + 0.6f) * 0.12f + cos(t * PI.toFloat() * 4.1f) * 0.04f)
            val topY = (baseY - h * (0.24f + hashUnit(i * 67 + 5) * 0.22f)).coerceAtLeast(-h * 0.08f)
            val botY = baseY + h * 0.11f

            val rayHueColor = when {
                t < 0.45f -> blendColor(accent, secondary, t / 0.45f)
                else -> blendColor(secondary, tertiary, (t - 0.45f) / 0.55f)
            }
            val shimmer = 0.50f + 0.50f * sin(i * 1.7f + 0.4f)
            val peakAlpha = ((0x4A * shimmer + 0x1A) * intensity).roundToInt().coerceIn(8, 138)

            rayPaint.shader = LinearGradient(
                cx, topY, cx, botY,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(rayHueColor, (peakAlpha * 0.55f).roundToInt()),
                    withAlpha(lightenColor(rayHueColor, 0.25f), peakAlpha),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.42f, 0.78f, 1f),
                Shader.TileMode.CLAMP,
            )
            rayRect.set(cx - rayW * 0.5f, topY, cx + rayW * 0.5f, botY)
            canvas.drawOval(rayRect, rayPaint)
        }
    }

    /**
     * Opens the live-preview Wallpaper customization side panel on the right with zero screen dim
     * so changes to style, QR image, visibility, and blur are previewed live on the screen behind.
     */
    fun showPanel(
        context: Context,
        onLiveRefresh: () -> Unit,
        onPreviewOpen: (() -> Unit)? = null,
        onPreviewClose: (() -> Unit)? = null,
        onChanged: () -> Unit = {},
    ) {
        val prefs = context.prefs()
        fun notifyAndRefresh() {
            onLiveRefresh()
            onChanged()
        }

        onPreviewOpen?.invoke()

        val dialog = object : ComponentDialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
            private var dropHeldSelect = true

            override fun onStart() {
                super.onStart()
                TvToast.onDialogShown(this)
            }

            override fun onStop() {
                TvToast.onDialogDismissed(this)
                super.onStop()
            }

            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                val isSelect = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                if (dropHeldSelect && isSelect) {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        dropHeldSelect = false
                    } else {
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }

        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.rgb(145, 71, 255))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Keep 0f dim so the user previews the true brightness and colors of the wallpaper live behind the panel
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }

        dialog.setOnDismissListener {
            onPreviewClose?.invoke()
        }

        dialog.setContentView(
            androidx.compose.ui.platform.ComposeView(context).apply {
                setContent {
                    androidx.compose.runtime.CompositionLocalProvider(LocalTvDialogWindow provides dialog.window) {
                        TvTheme(accent) {
                            WallpaperPanelContent(
                                context = context,
                                prefs = prefs,
                                onNotifyAndRefresh = ::notifyAndRefresh,
                            )
                        }
                    }
                }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        dialog.show()
    }

    @Composable
    private fun WallpaperPanelContent(
        context: Context,
        prefs: android.content.SharedPreferences,
        onNotifyAndRefresh: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var currentStyle by remember { mutableStateOf(style(context)) }
        var hasCustom by remember { mutableStateOf(hasCustomImage(context)) }
        var currentVis by remember { mutableIntStateOf(visibility(context)) }
        var currentBlur by remember { mutableIntStateOf(blur(context)) }
        var resetTick by remember { mutableIntStateOf(0) }
        var customThumbStamp by remember { mutableStateOf(customFileStamp) }

        val styleRequesters = remember { STYLES.map { FocusRequester() } }
        val accentCompose = MaterialTheme.colorScheme.primary

        fun openQrUploader() {
            showQrUploadModal(context, scope) {
                hasCustom = hasCustomImage(context)
                currentStyle = STYLE_CUSTOM
                customThumbStamp = customFileStamp
                onNotifyAndRefresh()
            }
        }

        SidePanel(
            title = context.getString(R.string.tv_wallpaper),
            subtitle = context.getString(R.string.tv_wallpaper_summary),
            width = 456.dp,
            scrollable = true,
            compactHeader = true,
        ) {
            key(resetTick) {
                STYLES.forEachIndexed { idx, styleKey ->
                    val label = styleLabel(context, styleKey)
                    val checked = currentStyle == styleKey
                    FocusRow(
                        compact = true,
                        modifier = Modifier.focusRequester(styleRequesters[idx]),
                        onClick = {
                            if (styleKey == STYLE_CUSTOM && !hasCustomImage(context)) {
                                openQrUploader()
                            } else {
                                currentStyle = styleKey
                                prefs.edit { putString(C.TV_WALLPAPER, styleKey) }
                                onNotifyAndRefresh()
                            }
                        },
                    ) { focused ->
                        val textColor = if (focused) TvColors.onFocused else ComposeColor.White
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StyleMiniSwatch(
                                context = context,
                                styleKey = styleKey,
                                accent = accentCompose,
                                hasCustom = hasCustom,
                                customStamp = customThumbStamp,
                                checked = checked,
                                focused = focused,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    label,
                                    fontSize = 15.5.sp,
                                    fontWeight = if (checked || focused) FontWeight.SemiBold else FontWeight.Normal,
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (styleKey == STYLE_CUSTOM && !hasCustom) {
                                    Text(
                                        context.getString(R.string.tv_wallpaper_qr_button),
                                        fontSize = 12.sp,
                                        color = if (focused) TvColors.onFocused.copy(alpha = 0.78f) else TvColors.secondaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            RadioDot(checked = checked, focused = focused)
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                ButtonRow(
                    label = context.getString(R.string.tv_wallpaper_qr_button),
                    icon = R.drawable.tv_ic_wifi,
                    compact = true,
                    onClick = { openQrUploader() },
                )

                if (currentStyle != STYLE_DEFAULT) {
                    Spacer(Modifier.height(6.dp))
                    SliderRow(
                        label = context.getString(R.string.tv_wallpaper_visibility),
                        value = currentVis,
                        range = 10..100,
                        step = 5,
                        compact = true,
                        valueText = { "$it%" },
                    ) { v ->
                        currentVis = v
                        prefs.edit { putInt(C.TV_WALLPAPER_VISIBILITY, v) }
                        onNotifyAndRefresh()
                    }
                    SliderRow(
                        label = context.getString(R.string.tv_wallpaper_blur),
                        value = currentBlur,
                        range = 0..100,
                        step = 5,
                        compact = true,
                        valueText = { "$it%" },
                    ) { v ->
                        currentBlur = v
                        prefs.edit { putInt(C.TV_WALLPAPER_BLUR, v) }
                        onNotifyAndRefresh()
                    }
                }

                Spacer(Modifier.height(8.dp))
                ButtonRow(
                    label = context.getString(R.string.tv_reset),
                    icon = R.drawable.tv_ic_replay,
                    compact = true,
                ) {
                    customFile(context).let { if (it.exists()) it.delete() }
                    invalidateCaches()
                    prefs.edit {
                        putString(C.TV_WALLPAPER, STYLE_DEFAULT)
                        putInt(C.TV_WALLPAPER_VISIBILITY, DEFAULT_VISIBILITY)
                        putInt(C.TV_WALLPAPER_BLUR, DEFAULT_BLUR)
                    }
                    hasCustom = false
                    currentStyle = STYLE_DEFAULT
                    currentVis = DEFAULT_VISIBILITY
                    currentBlur = DEFAULT_BLUR
                    resetTick++
                    onNotifyAndRefresh()
                }
            }
        }

        LaunchedEffect(resetTick) {
            val targetIdx = STYLES.indexOf(currentStyle).coerceAtLeast(0)
            runCatching { styleRequesters[targetIdx].requestFocus() }
        }
    }

    @Composable
    private fun StyleMiniSwatch(
        context: Context,
        styleKey: String,
        accent: ComposeColor,
        hasCustom: Boolean,
        customStamp: Long,
        checked: Boolean,
        focused: Boolean,
    ) {
        val shape = RoundedCornerShape(7.dp)
        val borderColor = when {
            focused -> TvColors.onFocused.copy(alpha = 0.65f)
            checked -> accent
            else -> ComposeColor(0x38FFFFFF)
        }
        val customPreview = remember(hasCustom, customStamp) {
            if (styleKey == STYLE_CUSTOM && hasCustom) {
                loadCustomRawBitmap(context, if (customStamp == 0L) customFile(context).lastModified() else customStamp)
            } else null
        }

        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(shape)
                .background(ComposeColor(0xFF0A0A0F))
                .border(if (checked) 1.5.dp else 1.dp, borderColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (styleKey == STYLE_CUSTOM && customPreview != null) {
                Image(
                    bitmap = customPreview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                ComposeCanvas(Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    when (styleKey) {
                        STYLE_DEFAULT -> {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(accent.copy(alpha = 0.65f), ComposeColor.Transparent),
                                    center = Offset(w * 0.15f, h * 0.15f),
                                    radius = w * 0.85f,
                                ),
                                radius = w,
                                center = Offset(w * 0.15f, h * 0.15f),
                            )
                        }
                        STYLE_TOPOGRAPHIC -> {
                            drawRect(ComposeColor.Black)
                            for (i in 1..3) {
                                val r = w * (0.22f * i)
                                drawCircle(
                                    color = accent.copy(alpha = if (i == 2) 0.85f else 0.45f),
                                    radius = r,
                                    center = Offset(w * 0.82f, h * 0.25f),
                                    style = Stroke(width = if (i == 2) 1.6f else 1f),
                                )
                            }
                        }
                        STYLE_PRISMS -> {
                            drawLine(accent.copy(alpha = 0.55f), Offset(0f, h), Offset(w * 0.52f, 0f), strokeWidth = 1.2f)
                            drawLine(accent.copy(alpha = 0.45f), Offset(w * 0.52f, 0f), Offset(w, h * 0.75f), strokeWidth = 1.2f)
                            drawLine(accent.copy(alpha = 0.70f), Offset(0f, h * 0.35f), Offset(w, h * 0.65f), strokeWidth = 1.2f)
                        }
                        STYLE_RIBBONS -> {
                            drawLine(accent.copy(alpha = 0.80f), Offset(0f, h * 0.72f), Offset(w, h * 0.28f), strokeWidth = 2.5f)
                            drawLine(accent.copy(alpha = 0.45f), Offset(0f, h * 0.92f), Offset(w, h * 0.52f), strokeWidth = 2.0f)
                        }
                        STYLE_AURORA -> {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    listOf(accent.copy(alpha = 0.65f), ComposeColor(0xFF22D3EE).copy(alpha = 0.55f)),
                                )
                            )
                        }
                        STYLE_CUSTOM -> {
                            drawCircle(accent.copy(alpha = 0.45f), radius = 4.dp.toPx(), center = Offset(w * 0.5f, h * 0.5f))
                        }
                    }
                }
            }
        }
    }

    /**
     * Opens a temporary local Wi-Fi HTTP server + QR modal allowing the user to send an image file
     * or paste an image URL from their phone.
     */
    fun showQrUploadModal(
        context: Context,
        scope: CoroutineScope,
        onApplied: () -> Unit,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val ip = withContext(Dispatchers.IO) { findLocalIpAddress() }
            if (ip == null) {
                TvToast.makeText(appContext, R.string.tv_backup_no_network, Toast.LENGTH_LONG).show()
                return@launch
            }
            val server = withContext(Dispatchers.IO) { runCatching { ServerSocket(0) }.getOrNull() } ?: return@launch
            val url = "http://$ip:${server.localPort}/"
            val qrBitmap = withContext(Dispatchers.Default) { QrCode.encode(url, 520) }

            var dialogRef: ComponentDialog? = null
            val dialog = TvDialogs.show(context) { dismiss ->
                SidePanel(title = context.getString(R.string.tv_wallpaper_qr_title)) {
                    val closeRequester = remember { FocusRequester() }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            context.getString(R.string.tv_wallpaper_qr_help),
                            fontSize = 14.5.sp,
                            color = TvColors.secondaryText,
                        )
                        Spacer(Modifier.height(18.dp))
                        Box(
                            Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ComposeColor.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = url,
                                modifier = Modifier.size(200.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(url, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                        Spacer(Modifier.height(18.dp))
                        ButtonRow(
                            label = context.getString(R.string.close_player),
                            modifier = Modifier.focusRequester(closeRequester),
                            onClick = dismiss,
                        )
                    }
                    LaunchedEffect(Unit) {
                        runCatching { closeRequester.requestFocus() }
                    }
                }
            }
            dialogRef = dialog

            val serverJob = scope.launch(Dispatchers.IO) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) {
                        val applied = handleWallpaperClient(client, appContext)
                        if (applied) {
                            appContext.prefs().edit { putString(C.TV_WALLPAPER, STYLE_CUSTOM) }
                            withContext(Dispatchers.Main) {
                                onApplied()
                                TvToast.makeText(appContext, R.string.tv_wallpaper_applied, Toast.LENGTH_SHORT).show()
                                runCatching { dialogRef?.dismiss() }
                            }
                        }
                    }
                }
            }
            dialog.setOnDismissListener {
                serverJob.cancel()
                runCatching { server.close() }
            }
        }
    }

    private fun handleWallpaperClient(socket: Socket, context: Context): Boolean {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = s.getInputStream()
            val requestLine = readHttpLine(input) ?: return false
            var contentLength = 0
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val out = s.getOutputStream()

            if (requestLine.startsWith("POST /upload") && contentLength in 1..MAX_UPLOAD_BYTES) {
                val body = readExactBytes(input, contentLength) ?: run {
                    writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                    return false
                }
                val ok = decodeAndSaveCustomImage(context, body)
                if (ok) {
                    writePlainResponse(out, 200, context.getString(R.string.tv_wallpaper_applied))
                } else {
                    writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                }
                return ok
            }

            if (requestLine.startsWith("POST /url") && contentLength in 1..8192) {
                val rawBytes = readExactBytes(input, contentLength) ?: run {
                    writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                    return false
                }
                val targetUrl = String(rawBytes, Charsets.UTF_8).trim()
                if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
                    writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                    return false
                }
                val fetched = runCatching {
                    val req = Request.Builder()
                        .url(targetUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                        .header("Accept", "image/*,*/*;q=0.8")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val stream = resp.body?.byteStream() ?: return@use null
                        readBoundedStream(stream, MAX_UPLOAD_BYTES)
                    }
                }.getOrNull()

                val ok = fetched != null && decodeAndSaveCustomImage(context, fetched)
                if (ok) {
                    writePlainResponse(out, 200, context.getString(R.string.tv_wallpaper_applied))
                } else {
                    writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                }
                return ok
            }

            val pageTitle = escapeHtml(context.getString(R.string.tv_wallpaper_qr_title))
            val pickLabel = escapeHtml(context.getString(R.string.tv_wallpaper_web_pick_file))
            val urlLabel = escapeHtml(context.getString(R.string.tv_wallpaper_web_paste_url))
            val sendLabel = escapeHtml(context.getString(R.string.tv_wallpaper_web_send))
            val doneMsg = escapeJs(context.getString(R.string.tv_wallpaper_applied))
            val errMsg = escapeJs(context.getString(R.string.tv_wallpaper_invalid_image))

            val html = """
                <!DOCTYPE html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Xtra TV</title>
                <style>
                *{box-sizing:border-box}
                body{background:#0e0e13;color:#f5f5f7;font-family:-apple-system,BlinkMacSystemFont,sans-serif;margin:0;padding:28px 18px;text-align:center}
                .card{max-width:420px;margin:0 auto 18px;background:#1a1924;border:1px solid rgba(255,255,255,.12);border-radius:16px;padding:20px}
                h2{margin:0 0 6px;font-size:22px}
                p.sub{margin:0 0 22px;color:#b8b8c7;font-size:14px}
                label.lbl{display:block;font-weight:600;margin-bottom:12px;font-size:15px}
                input[type=file]{display:block;width:100%;margin:0 auto 14px;color:#ddd;font-size:14px}
                input[type=url]{width:100%;padding:12px 14px;border-radius:10px;border:1px solid rgba(255,255,255,.2);background:#101017;color:#fff;font-size:15px;margin-bottom:12px}
                button{width:100%;padding:13px 20px;background:#9147ff;color:#fff;border:0;border-radius:10px;font-weight:700;font-size:16px;cursor:pointer}
                button:disabled{opacity:.55}
                #st{margin-top:14px;font-size:14px;font-weight:600;min-height:20px;color:#c4b5fd}
                </style></head><body>
                <h2>Xtra TV</h2>
                <p class="sub">$pageTitle</p>
                <div class="card">
                  <label class="lbl">$pickLabel</label>
                  <input type="file" id="f" accept="image/*">
                  <button id="bf" onclick="sendFile()">$sendLabel</button>
                </div>
                <div class="card">
                  <label class="lbl">$urlLabel</label>
                  <input type="url" id="u" placeholder="https://...">
                  <button id="bu" onclick="sendUrl()">$sendLabel</button>
                </div>
                <div id="st"></div>
                <script>
                function setStatus(m,ok){var e=document.getElementById('st');e.style.color=ok?'#6ee7b7':'#fb7185';e.innerText=m;}
                function uploadRaw(blob){
                  fetch('/upload',{method:'POST',body:blob}).then(function(r){
                    return r.text().then(function(t){setStatus(t||'$doneMsg',r.ok);});
                  }).catch(function(){setStatus('$errMsg',false);});
                }
                function sendFile(){
                  var f=document.getElementById('f').files[0];if(!f)return;
                  setStatus('...',true);
                  if(typeof createImageBitmap==='function'){
                    createImageBitmap(f).then(function(bmp){
                      var mw=1920,mh=1080,w=bmp.width,h=bmp.height;
                      var s=Math.min(1,Math.max(mw/w,mh/h));
                      if(w>mw||h>mh){s=Math.min(mw/w,mh/h);if(w*s<mw&&h*s<mh)s=1;}
                      var cw=Math.round(w*s),ch=Math.round(h*s);
                      var c=document.createElement('canvas');c.width=cw;c.height=ch;
                      c.getContext('2d').drawImage(bmp,0,0,cw,ch);
                      c.toBlob(function(b){uploadRaw(b||f);},'image/jpeg',0.92);
                    }).catch(function(){uploadRaw(f);});
                  }else{uploadRaw(f);}
                }
                function sendUrl(){
                  var u=document.getElementById('u').value.trim();if(!u)return;
                  setStatus('...',true);
                  fetch('/url',{method:'POST',body:u}).then(function(r){
                    return r.text().then(function(t){setStatus(t||'$doneMsg',r.ok);});
                  }).catch(function(){setStatus('$errMsg',false);});
                }
                </script></body></html>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(html)
            out.flush()
            return false
        }
    }

    private fun decodeAndSaveCustomImage(context: Context, bytes: ByteArray): Boolean {
        if (bytes.size < 32) return false
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_CUSTOM_W && bounds.outHeight / (sample * 2) >= MAX_CUSTOM_H) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return false

            val exifDegrees = readJpegOrientationDegrees(bytes)
            val scale = min(
                1f,
                max(
                    MAX_CUSTOM_W.toFloat() / (if (exifDegrees == 90 || exifDegrees == 270) decoded.height else decoded.width),
                    MAX_CUSTOM_H.toFloat() / (if (exifDegrees == 90 || exifDegrees == 270) decoded.width else decoded.height),
                ),
            )
            if (exifDegrees != 0 || scale < 0.999f) {
                val m = Matrix()
                if (exifDegrees != 0) m.postRotate(exifDegrees.toFloat())
                if (scale < 0.999f) m.postScale(scale, scale)
                val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                if (transformed !== decoded) {
                    decoded.recycle()
                    decoded = transformed
                }
            }

            val dest = customFile(context)
            val tmp = File(dest.parentFile, "$CUSTOM_FILE_NAME.tmp")
            FileOutputStream(tmp).use { fos ->
                if (!decoded.compress(Bitmap.CompressFormat.JPEG, 92, fos)) return false
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            invalidateCaches()
            cachedCustomRawBitmap = decoded
            cachedCustomRawStamp = customFileStamp
            true
        }.getOrDefault(false)
    }

    /** Minimal pure-Kotlin JPEG EXIF orientation reader (tag 0x0112) so phone photos never appear rotated. */
    private fun readJpegOrientationDegrees(data: ByteArray): Int {
        if (data.size < 14 || (data[0].toInt() and 0xFF) != 0xFF || (data[1].toInt() and 0xFF) != 0xD8) return 0
        var offset = 2
        while (offset + 4 < data.size && (data[offset].toInt() and 0xFF) == 0xFF) {
            val marker = data[offset + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val segLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            if (segLen < 2 || offset + 2 + segLen > data.size) break
            if (marker == 0xE1 && segLen >= 14) {
                val exifStart = offset + 4
                if (data[exifStart] == 'E'.code.toByte() &&
                    data[exifStart + 1] == 'x'.code.toByte() &&
                    data[exifStart + 2] == 'i'.code.toByte() &&
                    data[exifStart + 3] == 'f'.code.toByte()
                ) {
                    val tiff = exifStart + 6
                    val little = data[tiff] == 'I'.code.toByte() && data[tiff + 1] == 'I'.code.toByte()
                    fun u16(p: Int): Int {
                        if (p + 2 > data.size) return 0
                        val b0 = data[p].toInt() and 0xFF
                        val b1 = data[p + 1].toInt() and 0xFF
                        return if (little) (b1 shl 8) or b0 else (b0 shl 8) or b1
                    }
                    fun u32(p: Int): Int {
                        if (p + 4 > data.size) return 0
                        return if (little) {
                            (data[p].toInt() and 0xFF) or
                                ((data[p + 1].toInt() and 0xFF) shl 8) or
                                ((data[p + 2].toInt() and 0xFF) shl 16) or
                                ((data[p + 3].toInt() and 0xFF) shl 24)
                        } else {
                            ((data[p].toInt() and 0xFF) shl 24) or
                                ((data[p + 1].toInt() and 0xFF) shl 16) or
                                ((data[p + 2].toInt() and 0xFF) shl 8) or
                                (data[p + 3].toInt() and 0xFF)
                        }
                    }
                    val ifdOffset = tiff + u32(tiff + 4)
                    val count = u16(ifdOffset)
                    for (i in 0 until count.coerceAtMost(64)) {
                        val entry = ifdOffset + 2 + i * 12
                        if (entry + 12 > data.size) break
                        if (u16(entry) == 0x0112) {
                            return when (u16(entry + 8)) {
                                3 -> 180
                                6 -> 90
                                8 -> 270
                                else -> 0
                            }
                        }
                    }
                }
            }
            offset += 2 + segLen
        }
        return 0
    }

    private fun writePlainResponse(out: java.io.OutputStream, status: Int, text: String) {
        val statusText = if (status == 200) "200 OK" else "400 Bad Request"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusText\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray? {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) return null
            read += n
        }
        return buf
    }

    private fun readBoundedStream(input: InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun readHttpLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun findLocalIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun blendColor(c1: Int, c2: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val inv = 1f - f
        val r = (Color.red(c1) * inv + Color.red(c2) * f).roundToInt().coerceIn(0, 255)
        val g = (Color.green(c1) * inv + Color.green(c2) * f).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(c1) * inv + Color.blue(c2) * f).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun lightenColor(color: Int, amount: Float): Int = blendColor(color, Color.WHITE, amount)

    private fun shiftHue(color: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + degrees + 360f) % 360f
        hsv[1] = hsv[1].coerceAtLeast(0.45f)
        hsv[2] = hsv[2].coerceAtLeast(0.72f)
        return Color.HSVToColor(hsv)
    }

    private fun hashUnit(seed: Int): Float {
        var x = seed
        x = ((x ushr 16) xor x) * 0x45d9f3b
        x = ((x ushr 16) xor x) * 0x45d9f3b
        x = (x ushr 16) xor x
        return ((x and 0x7FFFFFFF).toFloat() / Int.MAX_VALUE.toFloat())
    }

    private fun expFalloff(dx: Float, dy: Float, sigma: Float): Float {
        val d2 = (dx * dx + dy * dy) / (2f * sigma * sigma)
        return kotlin.math.exp(-d2)
    }

    private fun hypotNorm(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

    /** Fast two-pass box/stack blur approximation in-place on ARGB_8888 [bitmap]. */
    private fun stackBlurInPlace(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1) return
        val pixels = IntArray(w * h)
        val temp = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val r = radius.coerceIn(1, min(w - 1, h - 1))
        // Two-pass separable sliding-window box blur repeated twice -> smooth Gaussian-like kernel in O(W*H)
        repeat(2) {
            // Horizontal pass: pixels -> temp
            val div = 2 * r + 1
            for (y in 0 until h) {
                val rowOffset = y * w
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (i in -r..r) {
                    val p = pixels[rowOffset + i.coerceIn(0, w - 1)]
                    sumR += (p ushr 16) and 0xFF
                    sumG += (p ushr 8) and 0xFF
                    sumB += p and 0xFF
                }
                for (x in 0 until w) {
                    temp[rowOffset + x] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                    val outIdx = (x - r).coerceIn(0, w - 1)
                    val inIdx = (x + r + 1).coerceIn(0, w - 1)
                    val pOut = pixels[rowOffset + outIdx]
                    val pIn = pixels[rowOffset + inIdx]
                    sumR += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                    sumG += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                    sumB += (pIn and 0xFF) - (pOut and 0xFF)
                }
            }
            // Vertical pass: temp -> pixels
            for (x in 0 until w) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (i in -r..r) {
                    val p = temp[i.coerceIn(0, h - 1) * w + x]
                    sumR += (p ushr 16) and 0xFF
                    sumG += (p ushr 8) and 0xFF
                    sumB += p and 0xFF
                }
                for (y in 0 until h) {
                    pixels[y * w + x] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                    val outIdx = (y - r).coerceIn(0, h - 1)
                    val inIdx = (y + r + 1).coerceIn(0, h - 1)
                    val pOut = temp[outIdx * w + x]
                    val pIn = temp[inIdx * w + x]
                    sumR += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                    sumG += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                    sumB += (pIn and 0xFF) - (pOut and 0xFF)
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private class CenterCropBitmapDrawable(
        private val bitmap: Bitmap,
        private val fallbackColor: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val srcRect = Rect()
        private val dstRect = Rect()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            if (bitmap.isRecycled) {
                canvas.drawColor(fallbackColor)
                return
            }
            val bw = bitmap.width
            val bh = bitmap.height
            val dw = b.width()
            val dh = b.height()
            if (bw * dh > dw * bh) {
                val cropW = (dw.toLong() * bh / dh).toInt().coerceIn(1, bw)
                val left = (bw - cropW) / 2
                srcRect.set(left, 0, left + cropW, bh)
            } else {
                val cropH = (dh.toLong() * bw / dw).toInt().coerceIn(1, bh)
                val top = (bh - cropH) / 2
                srcRect.set(0, top, bw, top + cropH)
            }
            dstRect.set(b)
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }
}
