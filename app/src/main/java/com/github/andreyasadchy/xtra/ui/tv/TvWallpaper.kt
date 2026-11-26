package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * TV Home & screens wallpaper engine + visual gallery side panel:
 * - 6 ACES-tonemapped Studio & OLED procedural shaders (Velvet Drape, Dichroic Glass, Glass Rings,
 *   Starry Night OLED, Twilight Dunes with 4x sub-pixel RGSS, Northern Lights) + 4 geometric vector styles,
 *   all dynamically adopting the active ambient color (including Dynamic ambiance crossfades).
 * - Unlimited custom images stored in `filesDir/tv_wallpapers/`, uploaded in batches or sequentially via a
 *   persistent local Wi-Fi QR phone portal (gallery multi-select + image URLs).
 * - Automatic wallpaper rotation / playlist mode (Every launch, 5 min, 10 min, 30 min, 1 h, 3 h + Shuffle)
 *   across any checked combination of built-in styles and custom photos.
 * - Live-adjustable Visibility (10..100%) and Blur (0..100%).
 */
object TvWallpaper {

    const val STYLE_DEFAULT = "default"

    // Studio & OLED shader collection
    const val STYLE_VELVET_DRAPE = "velvet_drape"
    const val STYLE_DICHROIC_GLASS = "dichroic_glass"
    const val STYLE_GLASS_RINGS = "glass_rings"
    const val STYLE_STARRY_OLED = "starry_oled"
    const val STYLE_TWILIGHT_DUNES = "twilight_dunes"
    const val STYLE_ARCTIC_AURORA = "arctic_aurora"

    // Classic geometric vector collection
    const val STYLE_TOPOGRAPHIC = "topographic"
    const val STYLE_PRISMS = "prisms"
    const val STYLE_RIBBONS = "ribbons"
    const val STYLE_AURORA = "aurora"

    // Legacy / prefix key for custom images ("custom:<fileName>")
    const val STYLE_CUSTOM = "custom"
    private const val CUSTOM_PREFIX = "custom:"

    const val DEFAULT_VISIBILITY = 55
    const val DEFAULT_BLUR = 0
    const val DEFAULT_CARD_OPACITY = 65
    const val MIN_CARD_OPACITY = 30
    const val MAX_CARD_OPACITY = 95

    const val TINT_OFF = 0
    const val TINT_SUBTLE = 1
    const val TINT_FULL = 2

    const val CUSTOM_FILE_NAME = "tv_wallpaper_custom.jpg"
    private const val WALLPAPERS_DIR_NAME = "tv_wallpapers"
    private const val THUMB_PREFIX = "thumb_"
    private const val PREF_ROTATION_LAST_MS = "tv_wallpaper_rotation_last_ms"

    private const val VECTOR_RENDER_W = 1280
    private const val VECTOR_RENDER_H = 720
    private const val THUMB_W = 320
    private const val THUMB_H = 180
    private const val MAX_CUSTOM_W = 1920
    private const val MAX_CUSTOM_H = 1080
    private const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024

    val BUILTIN_STYLES = listOf(
        STYLE_DEFAULT,
        STYLE_VELVET_DRAPE,
        STYLE_DICHROIC_GLASS,
        STYLE_GLASS_RINGS,
        STYLE_STARRY_OLED,
        STYLE_TWILIGHT_DUNES,
        STYLE_ARCTIC_AURORA,
        STYLE_TOPOGRAPHIC,
        STYLE_PRISMS,
        STYLE_RIBBONS,
        STYLE_AURORA,
    )

    private val STUDIO_STYLES = setOf(
        STYLE_VELVET_DRAPE,
        STYLE_DICHROIC_GLASS,
        STYLE_GLASS_RINGS,
        STYLE_STARRY_OLED,
        STYLE_TWILIGHT_DUNES,
        STYLE_ARCTIC_AURORA,
    )

    val ROTATION_INTERVAL_VALUES = listOf(0, -1, 5, 10, 30, 60, 180)

    data class CustomWallpaperItem(
        val fileName: String,
        val file: File,
        val thumbFile: File,
        val displayIndex: Int,
    ) {
        val styleKey: String get() = "$CUSTOM_PREFIX$fileName"
    }

    @Volatile
    private var legacyMigrated: Boolean = false
    private val uploadSeq = AtomicInteger(1)

    @Volatile
    private var cacheGeneration: Long = 1L

    private val rawCustomCache = object : LruCache<String, Bitmap>(3) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val renderedCache = object : LruCache<String, Bitmap>(8) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val previewThumbCache = object : LruCache<String, Bitmap>(28) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    private val shaderPool = Executors.newFixedThreadPool(workerCount) { r ->
        Thread(r, "TvWallpaperShader").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    private const val EXP_LUT_SIZE = 4096
    private const val EXP_LUT_MAX = 16f
    private const val EXP_LUT_SCALE = (EXP_LUT_SIZE - 1) / EXP_LUT_MAX
    private val EXP_NEG_LUT = FloatArray(EXP_LUT_SIZE) { i ->
        exp(-(i.toFloat() / EXP_LUT_SCALE).toDouble()).toFloat()
    }

    private const val ACES_LUT_SIZE = 4096
    private const val ACES_LUT_MAX = 3.5f
    private const val ACES_LUT_SCALE = (ACES_LUT_SIZE - 1) / ACES_LUT_MAX
    private val ACES_SRGB_LUT = FloatArray(ACES_LUT_SIZE) { i ->
        val x = i.toFloat() / ACES_LUT_SCALE
        val num = x * (2.51f * x + 0.03f)
        val den = x * (2.43f * x + 0.59f) + 0.14f
        val mapped = (num / den).coerceIn(0f, 1f)
        mapped.toDouble().pow(1.0 / 2.2).toFloat()
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun fastExpNeg(x: Float): Float {
        if (x <= 0f) return 1f
        if (x >= EXP_LUT_MAX) return 0f
        return EXP_NEG_LUT[(x * EXP_LUT_SCALE).toInt()]
    }

    private fun acesGamma(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= ACES_LUT_MAX) return ACES_SRGB_LUT[ACES_LUT_SIZE - 1]
        return ACES_SRGB_LUT[(x * ACES_LUT_SCALE).toInt()]
    }

    fun wallpapersDir(context: Context): File =
        File(context.applicationContext.filesDir, WALLPAPERS_DIR_NAME).apply { mkdirs() }

    fun legacyCustomFile(context: Context): File =
        File(context.applicationContext.filesDir, CUSTOM_FILE_NAME)

    fun isCustomStyleKey(key: String?): Boolean =
        key == STYLE_CUSTOM || (key != null && key.startsWith(CUSTOM_PREFIX))

    private fun ensureMigrated(context: Context) {
        if (legacyMigrated) return
        synchronized(this) {
            if (legacyMigrated) return
            val appCtx = context.applicationContext
            val legacy = legacyCustomFile(appCtx)
            if (legacy.isFile && legacy.length() > 128L) {
                val dir = wallpapersDir(appCtx)
                val migratedName = "wp_0000000000001_legacy.jpg"
                val dest = File(dir, migratedName)
                runCatching {
                    if (!dest.exists()) {
                        legacy.copyTo(dest, overwrite = true)
                    }
                    legacy.delete()
                    ensureThumbForFile(dest, File(dir, "$THUMB_PREFIX$migratedName"))
                }
                val prefs = appCtx.prefs()
                val cur = prefs.getString(C.TV_WALLPAPER, STYLE_DEFAULT)
                val migratedKey = "$CUSTOM_PREFIX$migratedName"
                if (cur == STYLE_CUSTOM) {
                    prefs.edit { putString(C.TV_WALLPAPER, migratedKey) }
                }
                val pool = prefs.getStringSet(C.TV_WALLPAPER_ROTATION_POOL, null)
                if (pool != null && STYLE_CUSTOM in pool) {
                    val updated = LinkedHashSet(pool).apply {
                        remove(STYLE_CUSTOM)
                        add(migratedKey)
                    }
                    prefs.edit { putStringSet(C.TV_WALLPAPER_ROTATION_POOL, updated) }
                }
            }
            legacyMigrated = true
        }
    }

    fun listCustomImages(context: Context): List<CustomWallpaperItem> {
        ensureMigrated(context)
        val dir = wallpapersDir(context)
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("wp_") && f.name.endsWith(".jpg") && f.length() > 128L
        }?.sortedBy { it.name }.orEmpty()
        return files.mapIndexed { idx, f ->
            CustomWallpaperItem(
                fileName = f.name,
                file = f,
                thumbFile = File(dir, "$THUMB_PREFIX${f.name}"),
                displayIndex = idx + 1,
            )
        }
    }

    fun hasCustomImage(context: Context): Boolean = listCustomImages(context).isNotEmpty()

    fun style(context: Context): String {
        ensureMigrated(context)
        val raw = context.prefs().getString(C.TV_WALLPAPER, STYLE_DEFAULT) ?: STYLE_DEFAULT
        if (raw in BUILTIN_STYLES) return raw
        if (isCustomStyleKey(raw)) {
            val customs = listCustomImages(context)
            if (customs.isEmpty()) return STYLE_DEFAULT
            if (raw == STYLE_CUSTOM) return customs.last().styleKey
            val match = customs.firstOrNull { it.styleKey == raw }
            return match?.styleKey ?: customs.last().styleKey
        }
        return STYLE_DEFAULT
    }

    val surfaceRevision = mutableIntStateOf(0)

    fun notifySurfaceChanged() {
        surfaceRevision.intValue++
    }

    fun isActive(context: Context): Boolean = style(context) != STYLE_DEFAULT

    /** Reactive Compose check: recomposes automatically when wallpaper style or card opacity changes. */
    fun isActiveReactive(context: Context): Boolean {
        surfaceRevision.intValue
        return isActive(context)
    }

    fun visibility(context: Context): Int =
        context.prefs().getInt(C.TV_WALLPAPER_VISIBILITY, DEFAULT_VISIBILITY).coerceIn(10, 100)

    fun blur(context: Context): Int =
        context.prefs().getInt(C.TV_WALLPAPER_BLUR, DEFAULT_BLUR).coerceIn(0, 100)

    fun cardOpacity(context: Context): Int =
        context.prefs().getInt(C.TV_WALLPAPER_CARD_OPACITY, DEFAULT_CARD_OPACITY).coerceIn(MIN_CARD_OPACITY, MAX_CARD_OPACITY)

    fun customTint(context: Context): Int =
        context.prefs().getInt(C.TV_WALLPAPER_CUSTOM_TINT, TINT_OFF).coerceIn(TINT_OFF, TINT_FULL)

    fun customTintLabel(context: Context, mode: Int): String = when (mode) {
        TINT_SUBTLE -> context.getString(R.string.tv_wallpaper_tint_subtle)
        TINT_FULL -> context.getString(R.string.tv_wallpaper_tint_full)
        else -> context.getString(R.string.tv_wallpaper_tint_off)
    }

    /**
     * Smoked-glass surface color for TV cards when a wallpaper is active.
     * Leaves the default dark appearance strictly untouched when [STYLE_DEFAULT] is selected.
     */
    fun cardSurfaceColor(
        context: Context,
        focused: Boolean = false,
        defaultColor: ComposeColor = ComposeColor(0xE013121C),
        defaultFocusedColor: ComposeColor = ComposeColor(0xEB1A1927),
        opacityOffset: Int = 0,
    ): ComposeColor {
        surfaceRevision.intValue
        if (!isActive(context)) {
            return if (focused) defaultFocusedColor else defaultColor
        }
        val basePct = (cardOpacity(context) + opacityOffset).coerceIn(22, 95)
        val pct = if (focused) (basePct + 14).coerceAtMost(94) else basePct
        val base = if (focused) ComposeColor(0xFF14161F) else ComposeColor(0xFF0B0C10)
        return base.copy(alpha = pct / 100f)
    }

    /** Light translucent glass pill surface for top header/hero buttons when a wallpaper is active. */
    fun pillSurfaceColor(
        context: Context,
        defaultColor: ComposeColor = ComposeColor(0xD913121C),
    ): ComposeColor {
        surfaceRevision.intValue
        if (!isActive(context)) return defaultColor
        val pct = (cardOpacity(context) * 0.56f).roundToInt().coerceIn(24, 54)
        return ComposeColor(0xFF0B0C10).copy(alpha = pct / 100f)
    }

    /** Settings column surface when a wallpaper is active (slightly higher floor for dense text legibility). */
    fun settingsPanelColor(
        context: Context,
        defaultColor: ComposeColor,
    ): ComposeColor {
        surfaceRevision.intValue
        if (!isActive(context)) return defaultColor
        val pct = (cardOpacity(context) + 10).coerceIn(48, 92)
        return ComposeColor(0xFF0B0C10).copy(alpha = pct / 100f)
    }

    /** Zero-blur specular rim brush for unfocused cards over a wallpaper (subtle highlight on top edge). */
    val cardGlassRimBrush: Brush = Brush.verticalGradient(
        listOf(ComposeColor(0x3DFFFFFF), ComposeColor(0x12FFFFFF)),
    )

    /** Zero-blur subtle top sheen inside cards over a wallpaper. */
    val cardGlassSheenBrush: Brush = Brush.verticalGradient(
        0.0f to ComposeColor(0x12FFFFFF),
        0.38f to ComposeColor.Transparent,
    )

    /** Focus-aware ColorStateList for XML MaterialCardView cards when a wallpaper is active. */
    fun xmlCardColorStateList(context: Context, offlineChannel: Boolean = false): android.content.res.ColorStateList {
        val basePct = if (offlineChannel) (cardOpacity(context) - 8).coerceAtLeast(24) else cardOpacity(context)
        val focusedPct = (basePct + 14).coerceAtMost(94)
        val normalColor = Color.argb((basePct * 255f / 100f).roundToInt().coerceIn(0, 255), 0x0B, 0x0C, 0x10)
        val focusedColor = Color.argb((focusedPct * 255f / 100f).roundToInt().coerceIn(0, 255), 0x14, 0x16, 0x1F)
        return android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(focusedColor, normalColor),
        )
    }

    /** Fractional alpha multiplier (0.28..1.0) for the category card shade overlay when a wallpaper is active. */
    fun categoryShadeAlpha(context: Context): Float {
        if (!isActive(context)) return 1f
        return (cardOpacity(context) / 100f * 0.72f).coerceIn(0.24f, 0.78f)
    }

    fun rotationInterval(context: Context): Int {
        val v = context.prefs().getInt(C.TV_WALLPAPER_ROTATION_INTERVAL, 0)
        return if (v in ROTATION_INTERVAL_VALUES) v else 0
    }

    fun rotationShuffle(context: Context): Boolean =
        context.prefs().getBoolean(C.TV_WALLPAPER_ROTATION_SHUFFLE, false)

    fun allSelectablePoolKeys(context: Context): List<String> {
        val builtins = BUILTIN_STYLES.filter { it != STYLE_DEFAULT }
        val customs = listCustomImages(context).map { it.styleKey }
        return builtins + customs
    }

    fun effectiveRotationPool(context: Context): List<String> {
        val validOrder = allSelectablePoolKeys(context)
        val saved = context.prefs().getStringSet(C.TV_WALLPAPER_ROTATION_POOL, null).orEmpty()
        return validOrder.filter { it in saved }
    }

    fun defaultSeedPool(context: Context): Set<String> {
        val customs = listCustomImages(context).map { it.styleKey }
        val active = style(context)
        val seed = LinkedHashSet<String>()
        if (active != STYLE_DEFAULT) seed.add(active)
        if (customs.size >= 2) {
            seed.addAll(customs)
        } else {
            seed.addAll(customs)
            seed.add(STYLE_VELVET_DRAPE)
            seed.add(STYLE_DICHROIC_GLASS)
            seed.add(STYLE_STARRY_OLED)
            seed.add(STYLE_TWILIGHT_DUNES)
            seed.add(STYLE_ARCTIC_AURORA)
        }
        return seed
    }

    /**
     * Advances wallpaper auto-rotation if configured and due.
     * Returns `true` when [C.TV_WALLPAPER] changed so the caller can crossfade the screen background.
     */
    fun advanceRotationIfDue(context: Context, onLaunch: Boolean): Boolean {
        val interval = rotationInterval(context)
        if (interval == 0) return false
        val pool = effectiveRotationPool(context)
        if (pool.size < 2) return false

        val prefs = context.prefs()
        val now = System.currentTimeMillis()
        if (interval == -1) {
            if (!onLaunch) return false
            return stepRotationToNext(context, pool, now) != null
        }

        val intervalMs = interval * 60_000L
        val last = prefs.getLong(PREF_ROTATION_LAST_MS, 0L)
        if (last <= 0L) {
            prefs.edit { putLong(PREF_ROTATION_LAST_MS, now) }
            return false
        }
        if (now - last < intervalMs) return false
        return stepRotationToNext(context, pool, now) != null
    }

    fun advanceRotationNow(context: Context): String? {
        val pool = effectiveRotationPool(context)
        if (pool.isEmpty()) return null
        if (pool.size == 1) {
            val only = pool.first()
            context.prefs().edit {
                putString(C.TV_WALLPAPER, only)
                putLong(PREF_ROTATION_LAST_MS, System.currentTimeMillis())
            }
            return only
        }
        return stepRotationToNext(context, pool, System.currentTimeMillis())
    }

    private fun stepRotationToNext(context: Context, pool: List<String>, nowMs: Long): String? {
        if (pool.isEmpty()) return null
        val prefs = context.prefs()
        val cur = style(context)
        val shuffle = rotationShuffle(context)
        val next = if (shuffle && pool.size > 1) {
            val candidates = pool.filter { it != cur }
            candidates[Random.nextInt(candidates.size)]
        } else {
            val idx = pool.indexOf(cur)
            if (idx < 0) pool.first() else pool[(idx + 1) % pool.size]
        }
        if (next == cur) {
            prefs.edit { putLong(PREF_ROTATION_LAST_MS, nowMs) }
            return null
        }
        prefs.edit {
            putString(C.TV_WALLPAPER, next)
            putLong(PREF_ROTATION_LAST_MS, nowMs)
        }
        notifySurfaceChanged()
        return next
    }

    fun rotationIntervalLabel(context: Context, value: Int): String = when (value) {
        -1 -> context.getString(R.string.tv_wallpaper_rotation_launch)
        5 -> "5 min"
        10 -> "10 min"
        30 -> "30 min"
        60 -> "1 h"
        180 -> "3 h"
        else -> context.getString(R.string.tv_chat_delay_off)
    }

    fun styleLabel(context: Context, styleKey: String): String = when {
        styleKey == STYLE_VELVET_DRAPE -> context.getString(R.string.tv_wallpaper_velvet_drape)
        styleKey == STYLE_DICHROIC_GLASS -> context.getString(R.string.tv_wallpaper_dichroic_glass)
        styleKey == STYLE_GLASS_RINGS -> context.getString(R.string.tv_wallpaper_glass_rings)
        styleKey == STYLE_STARRY_OLED -> context.getString(R.string.tv_wallpaper_starry_oled)
        styleKey == STYLE_TWILIGHT_DUNES -> context.getString(R.string.tv_wallpaper_twilight_dunes)
        styleKey == STYLE_ARCTIC_AURORA -> context.getString(R.string.tv_wallpaper_arctic_aurora)
        styleKey == STYLE_TOPOGRAPHIC -> context.getString(R.string.tv_wallpaper_topographic)
        styleKey == STYLE_PRISMS -> context.getString(R.string.tv_wallpaper_prisms)
        styleKey == STYLE_RIBBONS -> context.getString(R.string.tv_wallpaper_ribbons)
        styleKey == STYLE_AURORA -> context.getString(R.string.tv_wallpaper_aurora)
        isCustomStyleKey(styleKey) -> {
            val customs = listCustomImages(context)
            val match = customs.firstOrNull { it.styleKey == styleKey }
            val base = context.getString(R.string.tv_wallpaper_custom)
            if (customs.size > 1 && match != null) "$base #${match.displayIndex}" else base
        }
        else -> context.getString(R.string.tv_wallpaper_default)
    }

    fun summary(context: Context): String {
        val activeLabel = styleLabel(context, style(context))
        val interval = rotationInterval(context)
        val pool = effectiveRotationPool(context)
        return if (interval != 0 && pool.size >= 2) {
            "${context.getString(R.string.tv_wallpaper_rotation_badge, pool.size)} · $activeLabel"
        } else {
            activeLabel
        }
    }

    fun invalidateCaches() {
        cacheGeneration++
        rawCustomCache.evictAll()
        renderedCache.evictAll()
        previewThumbCache.evictAll()
    }

    /** Exports all custom wallpaper images (`wp_*.jpg`) for `TvBackup`. */
    fun exportCustomWallpapersForBackup(context: Context): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (item in listCustomImages(context)) {
            val bytes = runCatching { item.file.readBytes() }.getOrNull()
            if (bytes != null && bytes.size > 128) {
                out[item.fileName] = bytes
            }
        }
        return out
    }

    /** Restores multi-image `tv_wallpapers/` (and/or legacy single `tv_wallpaper_custom.jpg`) from `TvBackup`. */
    fun restoreWallpapersFromBackup(
        context: Context,
        multiFiles: Map<String, ByteArray>,
        legacySingleBytes: ByteArray?,
    ) {
        val appCtx = context.applicationContext
        val dir = wallpapersDir(appCtx)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        runCatching { legacyCustomFile(appCtx).delete() }
        legacyMigrated = true

        if (multiFiles.isNotEmpty()) {
            for ((rawName, bytes) in multiFiles) {
                if (bytes.size <= 128) continue
                val cleanName = rawName.substringAfterLast('/').takeIf {
                    it.startsWith("wp_") && it.endsWith(".jpg") && !it.contains("..")
                } ?: continue
                val dest = File(dir, cleanName)
                runCatching {
                    FileOutputStream(dest).use { it.write(bytes) }
                    ensureThumbForFile(dest, File(dir, "$THUMB_PREFIX$cleanName"))
                }
            }
        } else if (legacySingleBytes != null && legacySingleBytes.size > 128) {
            val migratedName = "wp_0000000000001_legacy.jpg"
            val dest = File(dir, migratedName)
            runCatching {
                FileOutputStream(dest).use { it.write(legacySingleBytes) }
                ensureThumbForFile(dest, File(dir, "$THUMB_PREFIX$migratedName"))
            }
            val prefs = appCtx.prefs()
            if (prefs.getString(C.TV_WALLPAPER, STYLE_DEFAULT) == STYLE_CUSTOM) {
                prefs.edit { putString(C.TV_WALLPAPER, "$CUSTOM_PREFIX$migratedName") }
            }
        }
        invalidateCaches()
    }

    fun deleteCustomImage(context: Context, fileName: String) {
        val dir = wallpapersDir(context)
        runCatching { File(dir, fileName).delete() }
        runCatching { File(dir, "$THUMB_PREFIX$fileName").delete() }
        val removedKey = "$CUSTOM_PREFIX$fileName"
        val prefs = context.prefs()
        val pool = prefs.getStringSet(C.TV_WALLPAPER_ROTATION_POOL, null)
        if (pool != null && removedKey in pool) {
            val nextPool = LinkedHashSet(pool).apply { remove(removedKey) }
            prefs.edit { putStringSet(C.TV_WALLPAPER_ROTATION_POOL, nextPool) }
        }
        val cur = prefs.getString(C.TV_WALLPAPER, STYLE_DEFAULT)
        if (cur == removedKey || cur == STYLE_CUSTOM) {
            val remaining = listCustomImages(context)
            val fallback = remaining.lastOrNull()?.styleKey ?: STYLE_DEFAULT
            prefs.edit { putString(C.TV_WALLPAPER, fallback) }
        }
        invalidateCaches()
    }

    fun deleteAllCustomImages(context: Context) {
        val dir = wallpapersDir(context)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        runCatching { legacyCustomFile(context).delete() }
        val prefs = context.prefs()
        val pool = prefs.getStringSet(C.TV_WALLPAPER_ROTATION_POOL, null)
        if (pool != null) {
            val filtered = pool.filterNot { isCustomStyleKey(it) }.toSet()
            prefs.edit { putStringSet(C.TV_WALLPAPER_ROTATION_POOL, filtered) }
        }
        if (isCustomStyleKey(prefs.getString(C.TV_WALLPAPER, STYLE_DEFAULT))) {
            prefs.edit { putString(C.TV_WALLPAPER, STYLE_DEFAULT) }
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
        val isCustom = isCustomStyleKey(activeStyle)
        val tintMode = if (isCustom) customTint(context) else TINT_OFF
        val effectiveAccent = ambientColor ?: 0xFFCBD5E1.toInt()
        val cacheKey = "$activeStyle|$baseColor|$effectiveAccent|${ambientColor != null}|$vis|$blurPct|$tintMode|$cacheGeneration"

        val bitmap = renderedCache.get(cacheKey) ?: run {
            val built = when {
                isCustom -> buildCustomWallpaperBitmap(
                    context = context,
                    styleKey = activeStyle,
                    ambientColor = ambientColor,
                    visibilityPct = vis,
                    blurPct = blurPct,
                    tintMode = tintMode,
                )
                activeStyle in STUDIO_STYLES -> buildStudioShaderBitmap(
                    styleKey = activeStyle,
                    w = VECTOR_RENDER_W,
                    h = VECTOR_RENDER_H,
                    accentColor = effectiveAccent,
                    visibilityPct = vis,
                    blurPct = blurPct,
                )
                else -> buildVectorWallpaperBitmap(
                    styleKey = activeStyle,
                    w = VECTOR_RENDER_W,
                    h = VECTOR_RENDER_H,
                    baseColor = baseColor,
                    accentColor = effectiveAccent,
                    hasAmbientGlow = ambientColor != null,
                    visibilityPct = vis,
                    blurPct = blurPct,
                )
            } ?: return null
            renderedCache.put(cacheKey, built)
            built
        }
        return CenterCropBitmapDrawable(bitmap, if (isCustom) Color.BLACK else baseColor)
    }

    /** Returns a crisp `320x180` (`16:9`) preview thumbnail bitmap for the visual gallery cards. */
    private fun getGalleryPreviewBitmap(
        context: Context,
        styleKey: String,
        accentColor: Int,
        tintMode: Int = TINT_OFF,
        visibilityPct: Int = 100,
    ): Bitmap? {
        val key = "thumb|$styleKey|$accentColor|$tintMode|$visibilityPct|$cacheGeneration"
        previewThumbCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        val baseColor = 0xFF0E0E14.toInt()
        val built: Bitmap? = when {
            isCustomStyleKey(styleKey) -> {
                val item = listCustomImages(context).firstOrNull { it.styleKey == styleKey } ?: return null
                ensureThumbForFile(item.file, item.thumbFile)
                val decoded = runCatching {
                    BitmapFactory.decodeFile(
                        if (item.thumbFile.isFile) item.thumbFile.absolutePath else item.file.absolutePath,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                    )
                }.getOrNull() ?: return null
                if (tintMode == TINT_OFF && visibilityPct >= 100) {
                    decoded
                } else {
                    val out = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                    val c = Canvas(out)
                    c.drawColor(Color.BLACK)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(buildCustomColorMatrix(visibilityPct, accentColor, tintMode))
                    }
                    c.drawBitmap(decoded, 0f, 0f, p)
                    decoded.recycle()
                    out
                }
            }
            styleKey == STYLE_DEFAULT -> {
                val bmp = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(baseColor)
                val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        THUMB_W * 0.06f, 0f, THUMB_W * 0.85f,
                        intArrayOf(withAlpha(accentColor, 0x8A), withAlpha(accentColor, 0x26), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                c.drawRect(0f, 0f, THUMB_W.toFloat(), THUMB_H.toFloat(), p1)
                val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        THUMB_W.toFloat(), THUMB_H.toFloat(), THUMB_W * 0.60f,
                        withAlpha(accentColor, 0x48), Color.TRANSPARENT, Shader.TileMode.CLAMP,
                    )
                }
                c.drawRect(0f, 0f, THUMB_W.toFloat(), THUMB_H.toFloat(), p2)
                bmp
            }
            styleKey in STUDIO_STYLES -> {
                buildStudioShaderBitmap(
                    styleKey = styleKey,
                    w = THUMB_W,
                    h = THUMB_H,
                    accentColor = accentColor,
                    visibilityPct = 78,
                    blurPct = 0,
                )
            }
            else -> {
                buildVectorWallpaperBitmap(
                    styleKey = styleKey,
                    w = THUMB_W,
                    h = THUMB_H,
                    baseColor = baseColor,
                    accentColor = accentColor,
                    hasAmbientGlow = true,
                    visibilityPct = 82,
                    blurPct = 0,
                )
            }
        }
        if (built != null) {
            previewThumbCache.put(key, built)
        }
        return built
    }

    private fun loadCustomRawBitmap(context: Context, styleKey: String): Bitmap? {
        val item = listCustomImages(context).firstOrNull { it.styleKey == styleKey } ?: return null
        val cacheKey = "${item.fileName}|${item.file.lastModified()}"
        rawCustomCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        val decoded = runCatching {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(item.file.absolutePath, opts)
        }.getOrNull() ?: return null
        rawCustomCache.put(cacheKey, decoded)
        return decoded
    }

    /**
     * Single-pass RGB ColorMatrix combining true-black luminance darkening (zero gray wash)
     * with optional 3-level ambient color recoloring (supports fixed and live Dynamic colors).
     */
    private fun buildCustomColorMatrix(
        visibilityPct: Int,
        ambientColor: Int?,
        tintMode: Int,
    ): ColorMatrix {
        val visFrac = (visibilityPct / 100f).coerceIn(0.10f, 1f)
        if (tintMode == TINT_OFF || ambientColor == null) {
            return ColorMatrix(
                floatArrayOf(
                    visFrac, 0f, 0f, 0f, 0f,
                    0f, visFrac, 0f, 0f, 0f,
                    0f, 0f, visFrac, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(ambientColor, hsv)
        val normColor = if (hsv[1] > 0.08f) {
            hsv[2] = 1f
            hsv[1] = hsv[1].coerceIn(0.35f, 1f)
            Color.HSVToColor(hsv)
        } else {
            Color.WHITE
        }
        val r0 = Color.red(normColor) / 255f
        val g0 = Color.green(normColor) / 255f
        val b0 = Color.blue(normColor) / 255f
        val tintLum = (0.2126f * r0 + 0.7152f * g0 + 0.0722f * b0).coerceAtLeast(0.32f)
        val gain = (0.72f / tintLum).coerceIn(0.95f, 1.32f)
        val tr = r0 * gain
        val tg = g0 * gain
        val tb = b0 * gain

        val mix = if (tintMode == TINT_FULL) 0.92f else 0.45f
        val keep = 1f - mix
        val lr = 0.2126f
        val lg = 0.7152f
        val lb = 0.0722f

        return ColorMatrix(
            floatArrayOf(
                visFrac * (keep + mix * tr * lr), visFrac * (mix * tr * lg), visFrac * (mix * tr * lb), 0f, 0f,
                visFrac * (mix * tg * lr), visFrac * (keep + mix * tg * lg), visFrac * (mix * tg * lb), 0f, 0f,
                visFrac * (mix * tb * lr), visFrac * (mix * tb * lg), visFrac * (keep + mix * tb * lb), 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }

    private fun buildCustomWallpaperBitmap(
        context: Context,
        styleKey: String,
        ambientColor: Int?,
        visibilityPct: Int,
        blurPct: Int,
        tintMode: Int,
    ): Bitmap? {
        val raw = loadCustomRawBitmap(context, styleKey) ?: return null
        val outW = if (blurPct > 0) VECTOR_RENDER_W else min(raw.width.coerceAtLeast(640), MAX_CUSTOM_W)
        val outH = if (blurPct > 0) VECTOR_RENDER_H else min(raw.height.coerceAtLeast(360), MAX_CUSTOM_H)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val scale = max(outW.toFloat() / raw.width.toFloat(), outH.toFloat() / raw.height.toFloat())
        val dx = (outW - raw.width * scale) * 0.5f
        val dy = (outH - raw.height * scale) * 0.5f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(buildCustomColorMatrix(visibilityPct, ambientColor, tintMode))
        }
        canvas.drawBitmap(raw, matrix, imgPaint)

        if (blurPct > 0) {
            val radius = ((blurPct / 100f) * 34f).roundToInt().coerceIn(1, 36)
            stackBlurInPlace(out, radius)
        }

        val visFrac = (visibilityPct / 100f).coerceIn(0.10f, 1f)
        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, outW * 0.36f, 0f,
                Color.argb((84 * (1.10f - visFrac * 0.65f)).roundToInt().coerceIn(24, 96), 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), vignettePaint)

        if (ambientColor != null) {
            val light = Color.luminance(ambientColor) > 0.45f
            val tintFactor = if (tintMode == TINT_FULL) 0.45f else 1f
            val glowAlpha = ((if (light) 0x22 else 0x34) * (0.45f + 0.55f * visFrac) * tintFactor).roundToInt().coerceIn(0, 96)
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

        drawReadabilityScrims(canvas, outW, outH, visibilityPct, includeLeft = false)
        return out
    }

    /**
     * Pre-bakes subtle top (header/clock/pills) and left (icon rail) dark gradients directly into the cached
     * full-screen wallpaper bitmap — 0% per-frame GPU/CPU cost on Android TV boxes.
     */
    private fun drawReadabilityScrims(
        canvas: Canvas,
        w: Int,
        h: Int,
        visibilityPct: Int,
        includeLeft: Boolean,
    ) {
        if (w < 640) return
        val visFrac = (visibilityPct / 100f).coerceIn(0.10f, 1f)
        val topAlpha = (78 * (0.72f + 0.35f * visFrac)).roundToInt().coerceIn(48, 92)
        val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h * 0.21f,
                intArrayOf(Color.argb(topAlpha, 0, 0, 0), Color.argb(topAlpha / 3, 0, 0, 0), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.21f, topPaint)
        if (includeLeft) {
            val leftAlpha = (74 * (0.72f + 0.35f * visFrac)).roundToInt().coerceIn(44, 88)
            val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, w * 0.16f, 0f,
                    intArrayOf(Color.argb(leftAlpha, 0, 0, 0), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, w * 0.16f, h.toFloat(), leftPaint)
        }
    }

    // =========================================================================
    // STUDIO & OLED PROCEDURAL SHADERS (Multi-threaded ACES filmic HDR engine)
    // =========================================================================

    private fun buildStudioShaderBitmap(
        styleKey: String,
        w: Int,
        h: Int,
        accentColor: Int,
        visibilityPct: Int,
        blurPct: Int,
    ): Bitmap {
        val acc = floatArrayOf(
            Color.red(accentColor) / 255f,
            Color.green(accentColor) / 255f,
            Color.blue(accentColor) / 255f,
        )
        val light = Color.luminance(accentColor) > 0.52f
        val visNorm = (visibilityPct / 100f).coerceIn(0.10f, 1f)
        val inten = (0.24f + 0.96f * visNorm) * (if (light) 0.80f else 1.0f)

        val pixels = IntArray(w * h)
        when (styleKey) {
            STYLE_VELVET_DRAPE -> renderVelvetDrape(pixels, w, h, acc, inten)
            STYLE_DICHROIC_GLASS -> renderDichroicGlass(pixels, w, h, acc, inten)
            STYLE_GLASS_RINGS -> renderGlassRings(pixels, w, h, acc, inten)
            STYLE_STARRY_OLED -> renderStarryOled(pixels, w, h, acc, inten)
            STYLE_TWILIGHT_DUNES -> renderTwilightDunes(pixels, w, h, acc, inten)
            STYLE_ARCTIC_AURORA -> renderArcticAurora(pixels, w, h, acc, inten)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        if (blurPct > 0) {
            val radius = ((blurPct / 100f) * 30f).roundToInt().coerceIn(1, 32)
            stackBlurInPlace(out, radius)
        }
        drawReadabilityScrims(Canvas(out), w, h, visibilityPct, includeLeft = true)
        return out
    }

    private fun runParallelRows(h: Int, block: (yStart: Int, yEnd: Int) -> Unit) {
        if (h < 220 || workerCount <= 1) {
            block(0, h)
            return
        }
        val tasks = ArrayList<Callable<Unit>>(workerCount)
        val chunk = (h + workerCount - 1) / workerCount
        var start = 0
        while (start < h) {
            val s = start
            val e = min(h, start + chunk)
            tasks.add(Callable { block(s, e) })
            start = e
        }
        shaderPool.invokeAll(tasks)
    }

    private fun shaderSmoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun shaderShiftHue(col: FloatArray, deg: Float, satMul: Float, valMul: Float): FloatArray {
        val r = col[0]
        val g = col[1]
        val b = col[2]
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val d = maxC - minC
        val sat = if (maxC > 1e-6f) d / maxC else 0f
        if (sat < 0.12f) {
            return floatArrayOf(
                (r * valMul).coerceIn(0f, 1.3f),
                (g * valMul).coerceIn(0f, 1.3f),
                (b * valMul).coerceIn(0f, 1.3f),
            )
        }
        var hue = when (maxC) {
            r -> ((g - b) / d) % 6f
            g -> ((b - r) / d) + 2f
            else -> ((r - g) / d) + 4f
        } / 6f
        if (hue < 0f) hue += 1f
        hue = (hue + deg / 360f) % 1f
        if (hue < 0f) hue += 1f

        val newS = (sat * satMul).coerceIn(0f, 1f)
        val newV = (maxC * valMul).coerceIn(0f, 1.3f)

        val h6 = hue * 6f
        val sector = floor(h6).toInt()
        val f = h6 - sector
        val p = newV * (1f - newS)
        val q = newV * (1f - newS * f)
        val t = newV * (1f - newS * (1f - f))
        return when (sector % 6) {
            0 -> floatArrayOf(newV, t, p)
            1 -> floatArrayOf(q, newV, p)
            2 -> floatArrayOf(p, newV, t)
            3 -> floatArrayOf(p, q, newV)
            4 -> floatArrayOf(t, p, newV)
            else -> floatArrayOf(newV, p, q)
        }
    }

    private fun packHdrPixel(hr: Float, hg: Float, hb: Float, x: Int, y: Int): Int {
        var h = (x * 374761393) xor (y * 668265263)
        h = (h xor (h ushr 13)) * 1274126177
        val grain = (((h ushr 16) and 0xFF) / 255f - 0.5f) * 0.011f
        val r = ((acesGamma(hr) + grain).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val g = ((acesGamma(hg) + grain).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val b = ((acesGamma(hb) + grain).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun fract(v: Float): Float = v - floor(v)

    private fun hash12(px: Float, py: Float): Float {
        var p3x = fract(px * 0.1031f)
        var p3y = fract(py * 0.1031f)
        var p3z = p3x
        val dot = p3x * (p3y + 33.33f) + p3y * (p3z + 33.33f) + p3z * (p3x + 33.33f)
        p3x += dot
        p3y += dot
        p3z += dot
        return fract((p3x + p3y) * p3z)
    }

    private fun hash22x(px: Float, py: Float): Float {
        var p3x = fract(px * 0.1031f)
        var p3y = fract(py * 0.1030f)
        var p3z = fract(px * 0.0973f)
        val dot = p3x * (p3y + 33.33f) + p3y * (p3z + 33.33f) + p3z * (p3x + 33.33f)
        p3x += dot
        p3y += dot
        p3z += dot
        return fract((p3x + p3y) * p3z)
    }

    private fun hash22y(px: Float, py: Float): Float {
        var p3x = fract(px * 0.1031f)
        var p3y = fract(py * 0.1030f)
        var p3z = fract(px * 0.0973f)
        val dot = p3x * (p3y + 33.33f) + p3y * (p3z + 33.33f) + p3z * (p3x + 33.33f)
        p3x += dot
        p3y += dot
        p3z += dot
        return fract((p3x + p3z) * p3y)
    }

    private fun vnoise(px: Float, py: Float): Float {
        val ix = floor(px)
        val iy = floor(py)
        val fx = px - ix
        val fy = py - iy
        val ux = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f)
        val uy = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)
        val a = hash12(ix, iy)
        val b = hash12(ix + 1f, iy)
        val c = hash12(ix, iy + 1f)
        val d = hash12(ix + 1f, iy + 1f)
        val ab = a + (b - a) * ux
        val cd = c + (d - c) * ux
        return ab + (cd - ab) * uy
    }

    private fun smoothFbm(startX: Float, startY: Float): Float {
        var px = startX
        var py = startY
        var v = 0f
        var amp = 0.55f
        repeat(4) {
            v += amp * vnoise(px, py)
            val nx = (0.80f * px - 0.60f * py) * 1.95f + 13.7f
            val ny = (0.60f * px + 0.80f * py) * 1.95f + 27.3f
            px = nx
            py = ny
            amp *= 0.48f
        }
        return v
    }

    /**
     * Splat-based exact equivalent of GLSL `starLayer`: iterates only over existing stars (`h >= density`)
     * and accumulates sub-pixel Gaussian core + halo into the linear HDR RGB buffer.
     */
    private fun splatStarLayer(
        hdrR: FloatArray,
        hdrG: FloatArray,
        hdrB: FloatArray,
        w: Int,
        h: Int,
        uvOffsetX: Float,
        uvOffsetY: Float,
        scale: Float,
        density: Float,
        brightness: Float,
        tintA: FloatArray,
        tintB: FloatArray,
        minUvY: Float = 0f,
        fadeUvY0: Float = 0f,
        fadeUvY1: Float = 0f,
    ) {
        val cellsX = 16f * scale
        val cellsY = 9f * scale
        val minIdX = floor(uvOffsetX * cellsX).toInt() - 1
        val maxIdX = floor((1f + uvOffsetX) * cellsX).toInt() + 1
        val minIdY = floor((minUvY + uvOffsetY) * cellsY).toInt() - 1
        val maxIdY = floor((1f + uvOffsetY) * cellsY).toInt() + 1

        val pxPerCellX = w.toFloat() / cellsX
        val pxPerCellY = h.toFloat() / cellsY
        val radiusCells = 0.72f

        for (idy in minIdY..maxIdY) {
            val fIdy = idy.toFloat()
            for (idx in minIdX..maxIdX) {
                val fIdx = idx.toFloat()
                val hv = hash12(fIdx, fIdy)
                if (hv < density) continue

                val jx = (hash22x(fIdx + 17.3f, fIdy + 17.3f) - 0.5f) * 0.66f
                val jy = (hash22y(fIdx + 17.3f, fIdy + 17.3f) - 0.5f) * 0.66f
                val starCellX = fIdx + 0.5f + jx
                val starCellY = fIdy + 0.5f + jy

                val starUvX = starCellX / cellsX - uvOffsetX
                val starUvY = starCellY / cellsY - uvOffsetY
                if (starUvX < -0.02f || starUvX > 1.02f || starUvY < minUvY - 0.02f || starUvY > 1.02f) continue

                val yFade = if (fadeUvY1 > fadeUvY0) shaderSmoothstep(fadeUvY0, fadeUvY1, starUvY) else 1f
                if (yFade <= 0.001f) continue

                val strength = (hv - density) / (1f - density)
                val coreSharpness = 95f + (42f - 95f) * strength
                val haloFactor = 0.22f * strength * strength
                val amp = (0.28f + 0.72f * strength * strength) * brightness * yFade

                val mixAB = hash12(fIdx + 91.1f, fIdy + 91.1f)
                val tintMix = 0.45f * hash12(fIdx + 43.7f, fIdy + 43.7f)
                val tr = tintA[0] + (tintB[0] - tintA[0]) * mixAB
                val tg = tintA[1] + (tintB[1] - tintA[1]) * mixAB
                val tb = tintA[2] + (tintB[2] - tintA[2]) * mixAB
                val sr = (0.92f + (tr - 0.92f) * tintMix) * amp
                val sg = (0.95f + (tg - 0.95f) * tintMix) * amp
                val sb = (1.00f + (tb - 1.00f) * tintMix) * amp

                val centerPxX = starUvX * w
                val centerPxY = (1f - starUvY) * h
                val rx = max(2, (radiusCells * pxPerCellX + 1f).toInt())
                val ry = max(2, (radiusCells * pxPerCellY + 1f).toInt())
                val x0 = (centerPxX.toInt() - rx).coerceAtLeast(0)
                val x1 = (centerPxX.toInt() + rx).coerceAtMost(w - 1)
                val y0 = (centerPxY.toInt() - ry).coerceAtLeast(0)
                val y1 = (centerPxY.toInt() + ry).coerceAtMost(h - 1)

                for (py in y0..y1) {
                    val uvY = 1f - (py + 0.5f) / h.toFloat()
                    if (uvY < minUvY) continue
                    val dyCell = (uvY + uvOffsetY) * cellsY - starCellY
                    val rowOff = py * w
                    for (px in x0..x1) {
                        val uvX = (px + 0.5f) / w.toFloat()
                        val dxCell = (uvX + uvOffsetX) * cellsX - starCellX
                        val d2 = dxCell * dxCell + dyCell * dyCell
                        if (d2 > 0.55f) continue
                        val d = sqrt(d2)
                        val intensity = fastExpNeg(d2 * coreSharpness) + fastExpNeg(d * 9.5f) * haloFactor
                        if (intensity > 1e-4f) {
                            val idxPx = rowOff + px
                            hdrR[idxPx] += sr * intensity
                            hdrG[idxPx] += sg * intensity
                            hdrB[idxPx] += sb * intensity
                        }
                    }
                }
            }
        }
    }

    /** 1. Drapé de Velours — sweeping bicolored matte-velvet folds over deep OLED black. */
    private fun renderVelvetDrape(pixels: IntArray, w: Int, h: Int, acc: FloatArray, inten: Float) {
        val cWarm = shaderShiftHue(acc, 32f, 0.95f, 1.08f)
        val cCool = shaderShiftHue(acc, -34f, 1.0f, 0.98f)

        val pxArr = FloatArray(w) { x -> ((x + 0.5f) / w.toFloat() - 0.5f) * 2.6f }
        // Precompute per-fold X terms
        val foldBaseX = Array(5) { fi ->
            val f = fi.toFloat()
            val phase = f * 0.78f - 1.1f
            FloatArray(w) { x ->
                val px = pxArr[x]
                px * 0.68f + 0.18f * cos(px * 2.9f + f * 1.1f) - phase * 0.42f
            }
        }
        val foldSinArgX = Array(5) { fi ->
            val f = fi.toFloat()
            FloatArray(w) { x -> pxArr[x] * 1.65f - f * 0.55f }
        }
        val foldBodyScaleR = Array(5) { FloatArray(w) }
        val foldBodyScaleG = Array(5) { FloatArray(w) }
        val foldBodyScaleB = Array(5) { FloatArray(w) }
        val foldCrestScaleR = Array(5) { FloatArray(w) }
        val foldCrestScaleG = Array(5) { FloatArray(w) }
        val foldCrestScaleB = Array(5) { FloatArray(w) }

        for (fi in 0 until 5) {
            val f = fi.toFloat()
            val midR = acc[0] + (cWarm[0] - acc[0]) * (f / 4f)
            val midG = acc[1] + (cWarm[1] - acc[1]) * (f / 4f)
            val midB = acc[2] + (cWarm[2] - acc[2]) * (f / 4f)
            val bodyFactor = (0.28f + 0.16f * (1f - f / 4f)) * inten
            val crestFactor = (0.26f - f * 0.032f) * inten
            for (x in 0 until w) {
                val px = pxArr[x]
                val tMix = shaderSmoothstep(-1.2f, 1.2f, px)
                val tr = cCool[0] + (midR - cCool[0]) * tMix
                val tg = cCool[1] + (midG - cCool[1]) * tMix
                val tb = cCool[2] + (midB - cCool[2]) * tMix
                val xMask = shaderSmoothstep(-1.45f, -0.1f, px + f * 0.12f) *
                    shaderSmoothstep(1.55f, 0.15f, px - f * 0.08f)
                val bm = bodyFactor * (0.35f + 0.65f * xMask)
                val cm = crestFactor * (0.30f + 0.70f * xMask)
                foldBodyScaleR[fi][x] = tr * bm
                foldBodyScaleG[fi][x] = tg * bm
                foldBodyScaleB[fi][x] = tb * bm
                foldCrestScaleR[fi][x] = (tr + (1f - tr) * 0.42f) * cm
                foldCrestScaleG[fi][x] = (tg + (1f - tg) * 0.42f) * cm
                foldCrestScaleB[fi][x] = (tb + (1f - tb) * 0.42f) * cm
            }
        }

        runParallelRows(h) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val uvY = 1f - (y + 0.5f) / h.toFloat()
                val py = (uvY - 0.5f) * 1.55f
                val pyLinear = py * 1.35f
                val cosPy = 0.5f * cos(py * 1.8f)
                val rowOff = y * w
                for (x in 0 until w) {
                    var cr = 0.0004f
                    var cg = 0.0004f
                    var cb = 0.0010f
                    for (fi in 0 until 5) {
                        val wave = pyLinear + foldBaseX[fi][x] + 0.36f * sin(foldSinArgX[fi][x] + cosPy)
                        if (wave > -0.38f) {
                            val fb = shaderSmoothstep(-0.38f, 0f, wave) * fastExpNeg(max(0f, wave) * 5.6f)
                            val fbPow = fb * sqrt(fb) // ~pow(fb, 1.35..1.5)
                            cr += foldBodyScaleR[fi][x] * fbPow
                            cg += foldBodyScaleG[fi][x] * fbPow
                            cb += foldBodyScaleB[fi][x] * fbPow
                        }
                        val absW = abs(wave)
                        if (absW < 0.55f) {
                            val crest = fastExpNeg(absW * 26f)
                            cr += foldCrestScaleR[fi][x] * crest
                            cg += foldCrestScaleG[fi][x] * crest
                            cb += foldCrestScaleB[fi][x] * crest
                        }
                    }
                    pixels[rowOff + x] = packHdrPixel(cr, cg, cb, x, y)
                }
            }
        }
    }

    /** 2. Verre Dichroïque — refractive curved glass sheets with RGB chromatic rim dispersion. */
    private fun renderDichroicGlass(pixels: IntArray, w: Int, h: Int, acc: FloatArray, inten: Float) {
        val cWarm = shaderShiftHue(acc, 38f, 0.95f, 1.1f)
        val cCool = shaderShiftHue(acc, -38f, 1.0f, 1.0f)

        val c1Arr = FloatArray(w)
        val c2Arr = FloatArray(w)
        val c3Arr = FloatArray(w)
        val t1R = FloatArray(w); val t1G = FloatArray(w); val t1B = FloatArray(w)
        val t2R = FloatArray(w); val t2G = FloatArray(w); val t2B = FloatArray(w)
        val t3R = FloatArray(w); val t3G = FloatArray(w); val t3B = FloatArray(w)
        val uxSqArr = FloatArray(w)

        for (x in 0 until w) {
            val ux = (x + 0.5f) / w.toFloat()
            c1Arr[x] = 0.14f + 0.58f * shaderSmoothstep(-0.08f, 1.08f, ux) + 0.13f * sin(ux * 3.5f + 0.4f)
            c2Arr[x] = 0.84f - 0.50f * ux + 0.15f * sin(ux * 3.9f + 2.1f)
            c3Arr[x] = 0.34f + 0.22f * sin(ux * 3.0f - 0.7f) + 0.18f * ux
            val m = shaderSmoothstep(0.08f, 0.92f, ux)
            t1R[x] = acc[0] + (cWarm[0] - acc[0]) * m
            t1G[x] = acc[1] + (cWarm[1] - acc[1]) * m
            t1B[x] = acc[2] + (cWarm[2] - acc[2]) * m
            t2R[x] = cCool[0] + (acc[0] - cCool[0]) * m
            t2G[x] = cCool[1] + (acc[1] - cCool[1]) * m
            t2B[x] = cCool[2] + (acc[2] - cCool[2]) * m
            t3R[x] = cWarm[0] + (cCool[0] - cWarm[0]) * m
            t3G[x] = cWarm[1] + (cCool[1] - cWarm[1]) * m
            t3B[x] = cWarm[2] + (cCool[2] - cWarm[2]) * m
            val dx = (ux - 0.5f) * 1.12f
            uxSqArr[x] = dx * dx
        }

        runParallelRows(h) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val uvY = 1f - (y + 0.5f) / h.toFloat()
                val dyVig = (uvY - 0.5f) * 1.22f
                val dySq = dyVig * dyVig
                val rowOff = y * w

                for (x in 0 until w) {
                    var cr = 0.0005f
                    var cg = 0.0005f
                    var cb = 0.0012f

                    // Sheet 1
                    run {
                        val d = uvY - c1Arr[x]
                        if (d > -0.04f) {
                            val body = shaderSmoothstep(-0.003f, 0.005f, d) * fastExpNeg(max(0f, d) * 7.5f)
                            val bPow = body * sqrt(body) * 0.52f * inten
                            cr += t1R[x] * bPow
                            cg += t1G[x] * bPow
                            cb += t1B[x] * bPow
                        }
                        val rr = 0.0024f / (abs(d - 0.0018f) + 0.0032f)
                        val rg = 0.0024f / (abs(d) + 0.0032f)
                        val rb = 0.0024f / (abs(d + 0.0020f) + 0.0032f)
                        val s = 0.46f * inten
                        cr += (t1R[x] * 0.45f + 0.55f) * (rr * sqrt(rr)) * s
                        cg += (t1G[x] * 0.45f + 0.55f) * (rg * sqrt(rg)) * s
                        cb += (t1B[x] * 0.45f + 0.55f) * (rb * sqrt(rb)) * s
                    }
                    // Sheet 2
                    run {
                        val d = c2Arr[x] - uvY
                        if (d > -0.04f) {
                            val body = shaderSmoothstep(-0.003f, 0.005f, d) * fastExpNeg(max(0f, d) * 6.8f)
                            val bPow = body * sqrt(body) * 0.52f * inten
                            cr += t2R[x] * bPow
                            cg += t2G[x] * bPow
                            cb += t2B[x] * bPow
                        }
                        val rr = 0.0024f / (abs(d - 0.0018f) + 0.0032f)
                        val rg = 0.0024f / (abs(d) + 0.0032f)
                        val rb = 0.0024f / (abs(d + 0.0020f) + 0.0032f)
                        val s = 0.46f * inten
                        cr += (t2R[x] * 0.45f + 0.55f) * (rr * sqrt(rr)) * s
                        cg += (t2G[x] * 0.45f + 0.55f) * (rg * sqrt(rg)) * s
                        cb += (t2B[x] * 0.45f + 0.55f) * (rb * sqrt(rb)) * s
                    }
                    // Sheet 3
                    run {
                        val d = c3Arr[x] - uvY
                        val inten3 = inten * 0.82f
                        if (d > -0.04f) {
                            val body = shaderSmoothstep(-0.003f, 0.005f, d) * fastExpNeg(max(0f, d) * 8.8f)
                            val bPow = body * sqrt(body) * 0.52f * inten3
                            cr += t3R[x] * bPow
                            cg += t3G[x] * bPow
                            cb += t3B[x] * bPow
                        }
                        val rr = 0.0024f / (abs(d - 0.0018f) + 0.0032f)
                        val rg = 0.0024f / (abs(d) + 0.0032f)
                        val rb = 0.0024f / (abs(d + 0.0020f) + 0.0032f)
                        val s = 0.46f * inten3
                        cr += (t3R[x] * 0.45f + 0.55f) * (rr * sqrt(rr)) * s
                        cg += (t3G[x] * 0.45f + 0.55f) * (rg * sqrt(rg)) * s
                        cb += (t3B[x] * 0.45f + 0.55f) * (rb * sqrt(rb)) * s
                    }

                    val vig = 0.28f + 0.72f * shaderSmoothstep(1.38f, 0.32f, sqrt(uxSqArr[x] + dySq))
                    pixels[rowOff + x] = packHdrPixel(cr * vig, cg * vig, cb * vig, x, y)
                }
            }
        }
    }

    /** 3. Ondes de Verre — off-center frosted glass concentric ripples lit from upper-left. */
    private fun renderGlassRings(pixels: IntArray, w: Int, h: Int, acc: FloatArray, inten: Float) {
        val cSec = shaderShiftHue(acc, -36f, 1.0f, 1.0f)
        val cWarm = shaderShiftHue(acc, 32f, 0.92f, 1.1f)
        val aspect = w.toFloat() / h.toFloat()

        val cosRef = cos(2.55f)
        val sinRef = sin(2.55f)
        val cosFi = FloatArray(6) { cos(it.toFloat()) }
        val sinFi = FloatArray(6) { sin(it.toFloat()) }
        val ringR = FloatArray(6)
        val ringG = FloatArray(6)
        val ringB = FloatArray(6)
        val rimColR = FloatArray(6)
        val rimColG = FloatArray(6)
        val rimColB = FloatArray(6)
        for (i in 0 until 6) {
            val t = i / 5f
            val mr = cSec[0] + (cWarm[0] - cSec[0]) * t
            val mg = cSec[1] + (cWarm[1] - cSec[1]) * t
            val mb = cSec[2] + (cWarm[2] - cSec[2]) * t
            val rr = acc[0] + (mr - acc[0]) * 0.55f
            val rg = acc[1] + (mg - acc[1]) * 0.55f
            val rb = acc[2] + (mb - acc[2]) * 0.55f
            ringR[i] = rr; ringG[i] = rg; ringB[i] = rb
            rimColR[i] = rr + (1f - rr) * 0.52f
            rimColG[i] = rg + (1f - rg) * 0.52f
            rimColB[i] = rb + (1f - rb) * 0.52f
        }

        val dxArr = FloatArray(w) { x -> ((x + 0.5f) / w.toFloat() - 0.76f) * aspect }

        runParallelRows(h) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val uvY = 1f - (y + 0.5f) / h.toFloat()
                val dy = uvY - 0.26f
                val dySq = dy * dy
                val rowOff = y * w

                for (x in 0 until w) {
                    val dx = dxArr[x]
                    val rSq = dx * dx + dySq
                    val r = sqrt(rSq).coerceAtLeast(1e-5f)
                    val invR = 1f / r
                    val cosDiff = (dx * cosRef + dy * sinRef) * invR
                    val baseArc = (0.5f + 0.5f * cosDiff).coerceIn(0f, 1f)
                    val lightArc = baseArc * sqrt(baseArc) // ~pow(baseArc, 1.5..1.6)

                    // Exact double-angle identities: sin(2*ang) = 2*dx*dy/r^2, cos(2*ang) = (dx^2 - dy^2)/r^2
                    val invRSq = invR * invR
                    val sin2A = 2f * dx * dy * invRSq
                    val cos2A = (dx * dx - dySq) * invRSq

                    var cr = 0.0003f
                    var cg = 0.0004f
                    var cb = 0.0009f

                    for (i in 0 until 6) {
                        val rad = 0.18f + i * 0.195f + 0.018f * (sin2A * cosFi[i] + cos2A * sinFi[i])
                        val dr = r - rad
                        val atten = (1f - i * 0.11f) * (0.22f + 0.78f * lightArc) * inten
                        val negDr = -dr
                        if (negDr > -0.01f) {
                            val body = shaderSmoothstep(-0.004f, 0.006f, negDr) * fastExpNeg(max(0f, negDr) * 14f)
                            val bTerm = (body * sqrt(sqrt(body))) * 0.44f * atten
                            cr += ringR[i] * bTerm
                            cg += ringG[i] * bTerm
                            cb += ringB[i] * bTerm
                        }
                        val rim = 0.0018f / (abs(dr) + 0.0028f)
                        val rTerm = (rim * sqrt(rim)) * 0.34f * atten
                        cr += rimColR[i] * rTerm
                        cg += rimColG[i] * rTerm
                        cb += rimColB[i] * rTerm
                    }
                    pixels[rowOff + x] = packHdrPixel(cr, cg, cb, x, y)
                }
            }
        }
    }

    /** 4. Nuit Étoilée OLED — pure `#000000` OLED starfield + understated diagonal Milky-Way veil (no black hole). */
    private fun renderStarryOled(pixels: IntArray, w: Int, h: Int, acc: FloatArray, inten: Float) {
        val cSec = shaderShiftHue(acc, -36f, 1.0f, 1.0f)
        val cWarm = shaderShiftHue(acc, 28f, 0.85f, 1.1f)

        // Evaluate smooth low-frequency Milky-Way dust veil on a coarse 160x90 grid and bilinearly interpolate
        val gw = 160
        val gh = 90
        val gridR = FloatArray((gw + 1) * (gh + 1))
        val gridG = FloatArray((gw + 1) * (gh + 1))
        val gridB = FloatArray((gw + 1) * (gh + 1))

        for (gy in 0..gh) {
            val uvY = 1f - gy.toFloat() / gh.toFloat()
            val mwY = uvY - 0.52f
            for (gx in 0..gw) {
                val uvX = gx.toFloat() / gw.toFloat()
                val mwX = uvX - 0.54f
                val diag = mwX * 0.58f - mwY * 0.81f
                val along = mwX * 0.81f + mwY * 0.58f
                val band = fastExpNeg(diag * diag * 9.5f) * shaderSmoothstep(0.95f, 0f, abs(along))
                if (band > 0.001f) {
                    val cloud = smoothFbm(uvX * 4.2f + 1.8f, uvY * 2.6f + 3.1f)
                    val sCloud = shaderSmoothstep(0.28f, 0.88f, cloud)
                    val nebula = band * (sCloud * sqrt(sCloud))
                    val dustLane = smoothFbm(uvX * 6.5f + 5.2f, uvY * 3.8f + 1.4f)
                    val dustAtten = 1f - 0.45f * band * shaderSmoothstep(0.52f, 0.82f, dustLane)
                    val tMix = shaderSmoothstep(-0.4f, 0.4f, along)
                    val scale = nebula * 0.16f * inten * dustAtten
                    val gi = gy * (gw + 1) + gx
                    gridR[gi] = (cSec[0] + (acc[0] - cSec[0]) * tMix) * scale
                    gridG[gi] = (cSec[1] + (acc[1] - cSec[1]) * tMix) * scale
                    gridB[gi] = (cSec[2] + (acc[2] - cSec[2]) * tMix) * scale
                }
            }
        }

        val hdrR = FloatArray(w * h)
        val hdrG = FloatArray(w * h)
        val hdrB = FloatArray(w * h)

        runParallelRows(h) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val fy = (y + 0.5f) / h.toFloat() * gh
                val iy = fy.toInt().coerceIn(0, gh - 1)
                val ty = fy - iy
                val r0 = iy * (gw + 1)
                val r1 = (iy + 1) * (gw + 1)
                val rowOff = y * w
                for (x in 0 until w) {
                    val fx = (x + 0.5f) / w.toFloat() * gw
                    val ix = fx.toInt().coerceIn(0, gw - 1)
                    val tx = fx - ix
                    val w00 = (1f - tx) * (1f - ty)
                    val w10 = tx * (1f - ty)
                    val w01 = (1f - tx) * ty
                    val w11 = tx * ty
                    val p = rowOff + x
                    hdrR[p] = gridR[r0 + ix] * w00 + gridR[r0 + ix + 1] * w10 + gridR[r1 + ix] * w01 + gridR[r1 + ix + 1] * w11
                    hdrG[p] = gridG[r0 + ix] * w00 + gridG[r0 + ix + 1] * w10 + gridG[r1 + ix] * w01 + gridG[r1 + ix + 1] * w11
                    hdrB[p] = gridB[r0 + ix] * w00 + gridB[r0 + ix + 1] * w10 + gridB[r1 + ix] * w01 + gridB[r1 + ix + 1] * w11
                }
            }
        }

        // 3 multi-scale crisp sub-pixel star layers
        splatStarLayer(
            hdrR, hdrG, hdrB, w, h, 0f, 0f, 13.5f, 0.90f, 0.55f * inten,
            floatArrayOf(acc[0] * 0.45f + 0.55f, acc[1] * 0.45f + 0.55f, acc[2] * 0.45f + 0.55f),
            floatArrayOf(cSec[0] * 0.40f + 0.60f, cSec[1] * 0.40f + 0.60f, cSec[2] * 0.40f + 0.60f),
        )
        splatStarLayer(
            hdrR, hdrG, hdrB, w, h, 0.37f, 0.19f, 7.5f, 0.945f, 1.05f * inten,
            floatArrayOf(acc[0] * 0.55f + 0.45f, acc[1] * 0.55f + 0.45f, acc[2] * 0.55f + 0.45f),
            floatArrayOf(cWarm[0] * 0.45f + 0.55f, cWarm[1] * 0.45f + 0.55f, cWarm[2] * 0.45f + 0.55f),
        )
        splatStarLayer(
            hdrR, hdrG, hdrB, w, h, 0.71f, 0.63f, 3.8f, 0.972f, 1.65f * inten,
            floatArrayOf(acc[0] * 0.50f + 0.50f, acc[1] * 0.50f + 0.50f, acc[2] * 0.50f + 0.50f),
            floatArrayOf(0.92f, 0.96f, 1.0f),
        )

        runParallelRows(h) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val p = rowOff + x
                    pixels[p] = packHdrPixel(hdrR[p], hdrG[p], hdrB[p], x, y)
                }
            }
        }
    }

    /** 5. Dunes Crépusculaires — 4x RGSS + exact slope-corrected Euclidean crest anti-aliasing (zero stair-stepping). */
    private fun renderTwilightDunes(pixels: IntArray, w: Int, h: Int, acc: FloatArray, inten: Float) {
        val cWarm = shaderShiftHue(acc, 28f, 0.92f, 1.08f)
        val skyTintR = acc[0] + (cWarm[0] - acc[0]) * 0.45f
        val skyTintG = acc[1] + (cWarm[1] - acc[1]) * 0.45f
        val skyTintB = acc[2] + (cWarm[2] - acc[2]) * 0.45f
        val orbTintR = cWarm[0] + (1f - cWarm[0]) * 0.42f
        val orbTintG = cWarm[1] + (1f - cWarm[1]) * 0.42f
        val orbTintB = cWarm[2] + (1f - cWarm[2]) * 0.42f
        val litTintR = acc[0] + (cWarm[0] - acc[0]) * 0.52f
        val litTintG = acc[1] + (cWarm[1] - acc[1]) * 0.52f
        val litTintB = acc[2] + (cWarm[2] - acc[2]) * 0.52f
        val mistTintR = acc[0] + (cWarm[0] - acc[0]) * 0.35f
        val mistTintG = acc[1] + (cWarm[1] - acc[1]) * 0.35f
        val mistTintB = acc[2] + (cWarm[2] - acc[2]) * 0.35f
        val rimTintR = cWarm[0] + (1f - cWarm[0]) * 0.52f
        val rimTintG = cWarm[1] + (1f - cWarm[1]) * 0.52f
        val rimTintB = cWarm[2] + (1f - cWarm[2]) * 0.52f

        // Splat stars into sky HDR buffer first so dunes cleanly occlude them
        val starR = FloatArray(w * h)
        val starG = FloatArray(w * h)
        val starB = FloatArray(w * h)
        splatStarLayer(
            starR, starG, starB, w, h, 0f, 0f, 9.5f, 0.955f, 0.68f * inten,
            floatArrayOf(acc[0] * 0.40f + 0.60f, acc[1] * 0.40f + 0.60f, acc[2] * 0.40f + 0.60f),
            floatArrayOf(1f, 1f, 1f),
            minUvY = 0.46f,
            fadeUvY0 = 0.46f,
            fadeUvY1 = 0.88f,
        )

        val pxUv = 1f / max(h.toFloat(), 360f)
        val aaWidth = 1.35f * pxUv
        val aspectSq = (h.toFloat() * h.toFloat()) / max(w.toFloat() * w.toFloat(), 1f)

        // 4x RGSS sub-pixel offsets (matching WebGL reference 1:1)
        val rgssDx = floatArrayOf(0.125f / w, -0.375f / w, 0.375f / w, -0.125f / w)
        val rgssDy = floatArrayOf(0.375f / h, 0.125f / h, -0.125f / h, -0.375f / h)

        // Precompute dune crestY, invGradNorm, and dWave*5.8 for all 4 dunes x 4 RGSS samples across X
        val crestPre = Array(16) { FloatArray(w) }
        val invGradPre = Array(16) { FloatArray(w) }
        val dWavePre = Array(16) { FloatArray(w) }
        val orbDxSq = FloatArray(w) { x ->
            val dx = ((x + 0.5f) / w.toFloat() - 0.64f) * 1.1f
            dx * dx
        }

        for (i in 0 until 4) {
            val fi = i.toFloat()
            val baseY = 0.44f - fi * 0.085f
            val freq = 2.1f + fi * 0.35f
            for (s in 0 until 4) {
                val slot = i * 4 + s
                val dxSub = rgssDx[s]
                for (x in 0 until w) {
                    val uvX = (x + 0.5f) / w.toFloat() + dxSub
                    val xShift = uvX * freq + fi * 2.35f
                    val wave = sin(xShift) * 0.055f + cos(xShift * 0.55f - 0.8f) * 0.042f
                    val dWave = cos(xShift) * 0.055f - sin(xShift * 0.55f - 0.8f) * 0.0231f
                    val dWaveDx = dWave * freq
                    crestPre[slot][x] = baseY + wave
                    invGradPre[slot][x] = 1f / sqrt(1f + dWaveDx * dWaveDx * aspectSq)
                    dWavePre[slot][x] = dWave * 5.8f
                }
            }
        }

        runParallelRows(h) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val baseUvY = 1f - (y + 0.5f) / h.toFloat()
                val skyGlow = fastExpNeg(max(0f, baseUvY - 0.36f) * 4.6f)
                val skyGlowPow = skyGlow * sqrt(skyGlow) * 0.42f * inten
                val baseSkyR = 0.0008f + skyTintR * skyGlowPow
                val baseSkyG = 0.0008f + skyTintG * skyGlowPow
                val baseSkyB = 0.0022f + skyTintB * skyGlowPow
                val orbDy = (baseUvY - 0.50f) * 1.9f
                val orbDySq = orbDy * orbDy
                val rowOff = y * w

                for (x in 0 until w) {
                    val pIdx = rowOff + x
                    val orb = fastExpNeg(sqrt(orbDxSq[x] + orbDySq) * 3.4f) * 0.38f * inten
                    val initR = baseSkyR + orbTintR * orb + starR[pIdx]
                    val initG = baseSkyG + orbTintG * orb + starG[pIdx]
                    val initB = baseSkyB + orbTintB * orb + starB[pIdx]

                    var sumR = 0f
                    var sumG = 0f
                    var sumB = 0f

                    for (s in 0 until 4) {
                        val uvY = baseUvY + rgssDy[s]
                        var cr = initR
                        var cg = initG
                        var cb = initB

                        for (i in 0 until 4) {
                            val slot = i * 4 + s
                            val crestY = crestPre[slot][x]
                            val orthoDist = (crestY - uvY) * invGradPre[slot][x]
                            if (orthoDist > -aaWidth) {
                                val coverage = shaderSmoothstep(-aaWidth, aaWidth, orthoDist)
                                if (coverage > 0.0005f) {
                                    val depth = i / 3f
                                    val distDown = max(0f, crestY - uvY)
                                    val spineSlope = if ((i and 1) == 0) 0.8f else -0.8f
                                    val spineOffset = dWavePre[slot][x] - distDown * spineSlope
                                    val sunSide = shaderSmoothstep(-0.18f, 0.22f, spineOffset)

                                    val darkFactor = 0.014f * (1f - depth) * inten
                                    val dR = 0.0007f + acc[0] * darkFactor
                                    val dG = 0.0007f + acc[1] * darkFactor
                                    val dB = 0.0018f + acc[2] * darkFactor

                                    val litScale = (0.28f - 0.13f * depth) * fastExpNeg(distDown * 3.8f) * inten
                                    val lR = litTintR * litScale
                                    val lG = litTintG * litScale
                                    val lB = litTintB * litScale

                                    var duneR = dR + (lR - dR) * sunSide
                                    var duneG = dG + (lG - dG) * sunSide
                                    var duneB = dB + (lB - dB) * sunSide

                                    val mist = fastExpNeg(distDown * 14f) * (1f - depth * 0.78f) * 0.11f * inten
                                    duneR += mistTintR * mist
                                    duneG += mistTintG * mist
                                    duneB += mistTintB * mist

                                    val rim = fastExpNeg(max(0f, orthoDist) * 95f) * (0.25f + 0.75f * sunSide) * (0.48f - 0.20f * depth) * inten
                                    duneR += rimTintR * rim
                                    duneG += rimTintG * rim
                                    duneB += rimTintB * rim

                                    cr += (duneR - cr) * coverage
                                    cg += (duneG - cg) * coverage
                                    cb += (duneB - cb) * coverage
                                }
                            }
                        }
                        sumR += cr
                        sumG += cg
                        sumB += cb
                    }

                    pixels[pIdx] = packHdrPixel(sumR * 0.25f, sumG * 0.25f, sumB * 0.25f, x, y)
                }
            }
        }
    }

    /** 6. Aurore Boréale — starry night sky + sweeping northern-lights curtains fading seamlessly into `#000000` black. */
    private fun renderArcticAurora(pixels: IntArray, w: Int, h: Int, acc: FloatArray, inten: Float) {
        val cUpper = shaderShiftHue(acc, -44f, 1.0f, 1.05f)
        val cSec = shaderShiftHue(acc, 26f, 0.95f, 1.0f)

        val starR = FloatArray(w * h)
        val starG = FloatArray(w * h)
        val starB = FloatArray(w * h)
        splatStarLayer(
            starR, starG, starB, w, h, 0f, 0f, 11.5f, 0.935f, 0.75f * inten,
            floatArrayOf(acc[0] * 0.40f + 0.60f, acc[1] * 0.40f + 0.60f, acc[2] * 0.40f + 0.60f),
            floatArrayOf(0.94f, 0.97f, 1.0f),
            minUvY = 0.08f,
            fadeUvY0 = 0.08f,
            fadeUvY1 = 0.55f,
        )
        splatStarLayer(
            starR, starG, starB, w, h, 0.41f, 0.23f, 5.8f, 0.968f, 1.15f * inten,
            floatArrayOf(cSec[0] * 0.45f + 0.55f, cSec[1] * 0.45f + 0.55f, cSec[2] * 0.45f + 0.55f),
            floatArrayOf(1f, 1f, 1f),
            minUvY = 0.08f,
            fadeUvY0 = 0.08f,
            fadeUvY1 = 0.55f,
        )

        // Precompute X terms for both aurora ribbons
        val arc1Y = FloatArray(w); val hFade1 = FloatArray(w); val foldPhase1 = FloatArray(w); val shimPhase1 = FloatArray(w)
        val arc2Y = FloatArray(w); val hFade2 = FloatArray(w); val foldPhase2 = FloatArray(w); val shimPhase2 = FloatArray(w)
        val bloomDxSq = FloatArray(w)

        for (x in 0 until w) {
            val uvX = (x + 0.5f) / w.toFloat()
            val bdx = (uvX - 0.52f) * 1.1f
            bloomDxSq[x] = bdx * bdx

            val rx1 = uvX - 0.5f
            arc1Y[x] = 0.44f + 0.15f * sin(rx1 * 2.5f - 0.35f) + 0.11f * rx1
            hFade1[x] = 0.38f + 0.62f * shaderSmoothstep(-0.62f, -0.14f, rx1) * shaderSmoothstep(0.64f, 0.16f, rx1)
            foldPhase1[x] = rx1 * 15f + sin(rx1 * 5.2f) * 2.5f
            shimPhase1[x] = rx1 * 36f

            val rx2 = (uvX + 0.14f) - 0.5f
            arc2Y[x] = 0.40f + 0.15f * sin(rx2 * 2.5f * 1.15f - 0.35f) + 0.11f * rx2
            hFade2[x] = 0.38f + 0.62f * shaderSmoothstep(-0.62f, -0.14f, rx2) * shaderSmoothstep(0.64f, 0.16f, rx2)
            foldPhase2[x] = rx2 * 15f * 1.15f + sin(rx2 * 5.2f) * 2.5f
            shimPhase2[x] = rx2 * 36f * 1.15f
        }

        val cBase1R = acc[0] * 0.84f + 0.16f; val cBase1G = acc[1] * 0.84f + 0.16f; val cBase1B = acc[2] * 0.84f + 0.16f
        val cBase2R = cSec[0] * 0.84f + 0.16f; val cBase2G = cSec[1] * 0.84f + 0.16f; val cBase2B = cSec[2] * 0.84f + 0.16f

        runParallelRows(h) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val uvY = 1f - (y + 0.5f) / h.toFloat()
                val skyFade = shaderSmoothstep(0f, 0.55f, uvY)
                val bottomCalm = shaderSmoothstep(0.02f, 0.34f, uvY)
                val bdy = (uvY - 0.56f) * 1.9f
                val bdySq = bdy * bdy
                val rowOff = y * w

                for (x in 0 until w) {
                    val pIdx = rowOff + x
                    val bloom = fastExpNeg(sqrt(bloomDxSq[x] + bdySq) * 2.6f) * 0.09f * inten
                    var cr = 0.0003f * skyFade + acc[0] * bloom + starR[pIdx]
                    var cg = 0.0005f * skyFade + acc[1] * bloom + starG[pIdx]
                    var cb = 0.0014f * skyFade + acc[2] * bloom + starB[pIdx]

                    if (bottomCalm > 0.001f) {
                        // Primary aurora ribbon
                        val dy1 = uvY - arc1Y[x]
                        if (dy1 > -0.16f) {
                            val env1 = shaderSmoothstep(-0.16f, 0.05f, dy1) * fastExpNeg(max(0f, dy1 - 0.02f) * 4.2f)
                            if (env1 > 0.001f) {
                                val folds1 = (0.54f + 0.46f * sin(foldPhase1[x] + dy1 * 3.2f)).coerceAtLeast(0f)
                                val shim1 = 0.76f + 0.24f * sin(shimPhase1[x] - dy1 * 6.5f)
                                val tCol1 = ((dy1 + 0.04f) * 2.8f).coerceIn(0f, 1f)
                                val w1 = env1 * (folds1 * sqrt(folds1)) * shim1 * hFade1[x] * inten * 1.32f * bottomCalm
                                cr += (cBase1R + (cUpper[0] - cBase1R) * tCol1) * w1
                                cg += (cBase1G + (cUpper[1] - cBase1G) * tCol1) * w1
                                cb += (cBase1B + (cUpper[2] - cBase1B) * tCol1) * w1
                            }
                        }

                        // Secondary aurora ribbon
                        val dy2 = (uvY - 0.07f) - arc2Y[x]
                        if (dy2 > -0.16f) {
                            val env2 = shaderSmoothstep(-0.16f, 0.05f, dy2) * fastExpNeg(max(0f, dy2 - 0.02f) * 4.2f)
                            if (env2 > 0.001f) {
                                val folds2 = (0.54f + 0.46f * sin(foldPhase2[x] + dy2 * 3.2f)).coerceAtLeast(0f)
                                val shim2 = 0.76f + 0.24f * sin(shimPhase2[x] - dy2 * 6.5f)
                                val tCol2 = ((dy2 + 0.04f) * 2.8f).coerceIn(0f, 1f)
                                val w2 = env2 * (folds2 * sqrt(folds2)) * shim2 * hFade2[x] * inten * 0.68f * bottomCalm
                                cr += (cBase2R + (cUpper[0] - cBase2R) * tCol2) * w2
                                cg += (cBase2G + (cUpper[1] - cBase2G) * tCol2) * w2
                                cb += (cBase2B + (cUpper[2] - cBase2B) * tCol2) * w2
                            }
                        }
                    }

                    pixels[pIdx] = packHdrPixel(cr, cg, cb, x, y)
                }
            }
        }
    }

    // =========================================================================
    // CLASSIC GEOMETRIC VECTOR WALLPAPERS (Canvas 2D)
    // =========================================================================

    private fun buildVectorWallpaperBitmap(
        styleKey: String,
        w: Int,
        h: Int,
        baseColor: Int,
        accentColor: Int,
        hasAmbientGlow: Boolean,
        visibilityPct: Int,
        blurPct: Int,
    ): Bitmap {
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
        drawReadabilityScrims(canvas, w, h, visibilityPct, includeLeft = true)
        return out
    }

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

        val cols = if (w <= 400) 48 else 96
        val rows = if (w <= 400) 27 else 54
        val field = Array(rows + 1) { FloatArray(cols + 1) }
        for (r in 0..rows) {
            val ny = r.toFloat() / rows.toFloat()
            for (c in 0..cols) {
                val nx = c.toFloat() / cols.toFloat()
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

        val scaleStroke = (w / 1280f).coerceAtLeast(0.55f)
        val minorAlpha = (0x46 * intensity).roundToInt().coerceIn(10, 120)
        val majorAlpha = (0x9E * intensity).roundToInt().coerceIn(22, 215)
        val glowAlpha = (0x3A * intensity).roundToInt().coerceIn(0, 95)

        val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.05f * scaleStroke
            strokeCap = Paint.Cap.ROUND
            color = withAlpha(accent, minorAlpha)
        }
        canvas.drawPath(minorPath, minorPaint)

        if (glowAlpha > 4) {
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4.2f * scaleStroke
                strokeCap = Paint.Cap.ROUND
                color = withAlpha(accent, glowAlpha)
                maskFilter = BlurMaskFilter(4.5f * scaleStroke, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawPath(majorPath, haloPaint)
        }

        val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.75f * scaleStroke
            strokeCap = Paint.Cap.ROUND
            color = withAlpha(lightenColor(accent, 0.22f), majorAlpha)
        }
        canvas.drawPath(majorPath, majorPaint)

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
        val zScale = w / 1280f

        for (r in 0..rows) {
            for (c in 0..cols) {
                val seed = (r * 73 + c * 137 + 19)
                val jx = if (c == 0 || c == cols) 0f else (hashUnit(seed) - 0.5f) * cellW * 0.68f
                val jy = if (r == 0 || r == rows) 0f else (hashUnit(seed * 31 + 7) - 0.5f) * cellH * 0.68f
                vx[r][c] = c * cellW + jx
                vy[r][c] = r * cellH + jy
                vz[r][c] = ((sin(c * 0.85f + r * 0.65f) * 42f) + (hashUnit(seed * 53 + 11) - 0.5f) * 58f) * zScale
            }
        }

        val lx = -0.52f
        val ly = -0.46f
        val lz = 0.72f

        val triPath = Path()
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * (w / 1280f).coerceAtLeast(0.6f)
            strokeJoin = Paint.Join.BEVEL
            color = withAlpha(lightenColor(accent, 0.25f), (0x2E * intensity).roundToInt().coerceIn(8, 72))
        }

        fun drawFacet(
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float,
            facetSeed: Int,
        ) {
            val ux = x2 - x1; val uy = y2 - y1; val uz = z2 - z1
            val wx = x3 - x1; val wy = y3 - y1; val wz = z3 - z1
            var nx = uy * wz - uz * wy
            var ny = uz * wx - ux * wz
            var nz = abs(ux * wy - uy * wx) * 0.012f + 1f
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
            nx /= len; ny /= len; nz /= len

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
                    drawFacet(vx[r][c], vy[r][c], vz[r][c], vx[r][c + 1], vy[r][c + 1], vz[r][c + 1], vx[r + 1][c + 1], vy[r + 1][c + 1], vz[r + 1][c + 1], seedBase)
                    drawFacet(vx[r][c], vy[r][c], vz[r][c], vx[r + 1][c + 1], vy[r + 1][c + 1], vz[r + 1][c + 1], vx[r + 1][c], vy[r + 1][c], vz[r + 1][c], seedBase + 101)
                } else {
                    drawFacet(vx[r][c], vy[r][c], vz[r][c], vx[r][c + 1], vy[r][c + 1], vz[r][c + 1], vx[r + 1][c], vy[r + 1][c], vz[r + 1][c], seedBase)
                    drawFacet(vx[r][c + 1], vy[r][c + 1], vz[r][c + 1], vx[r + 1][c + 1], vy[r + 1][c + 1], vz[r + 1][c + 1], vx[r + 1][c], vy[r + 1][c], vz[r + 1][c], seedBase + 101)
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
        val scaleStroke = (w / 1280f).coerceAtLeast(0.6f)

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
                strokeWidth = (if (idx == 1) 2.0f else 1.35f) * scaleStroke
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

        val rayCount = 34
        val blurScale = (w / 1280f).coerceAtLeast(0.35f)
        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(18f * blurScale, BlurMaskFilter.Blur.NORMAL)
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

    // =========================================================================
    // VISUAL GALLERY & CUSTOMIZATION SIDE PANEL (Compose)
    // =========================================================================

    fun showPanel(
        context: Context,
        onLiveRefresh: () -> Unit,
        onPreviewOpen: (() -> Unit)? = null,
        onPreviewClose: (() -> Unit)? = null,
        onChanged: () -> Unit = {},
    ) {
        val prefs = context.prefs()
        fun notifyAndRefresh() {
            notifySurfaceChanged()
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

    private sealed interface GalleryGridEntry {
        val id: String
        data class StyleCard(val styleKey: String, val customItem: CustomWallpaperItem? = null) : GalleryGridEntry {
            override val id: String get() = styleKey
        }
        data object AddQrCard : GalleryGridEntry {
            override val id: String get() = "__add_qr__"
        }
    }

    private sealed interface GalleryListRow {
        data class Section(val title: String) : GalleryListRow
        data class PairRow(val rowIndex: Int, val left: GalleryGridEntry, val right: GalleryGridEntry?) : GalleryListRow
    }

    @Composable
    private fun WallpaperPanelContent(
        context: Context,
        prefs: SharedPreferences,
        onNotifyAndRefresh: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var selectedTab by remember { mutableIntStateOf(0) }
        var refreshCounter by remember { mutableIntStateOf(0) }

        var currentStyle by remember(refreshCounter) { mutableStateOf(style(context)) }
        var customItems by remember(refreshCounter) { mutableStateOf(listCustomImages(context)) }
        var currentVis by remember(refreshCounter) { mutableIntStateOf(visibility(context)) }
        var currentBlur by remember(refreshCounter) { mutableIntStateOf(blur(context)) }
        var currentCardOpacity by remember(refreshCounter) { mutableIntStateOf(cardOpacity(context)) }
        var currentTint by remember(refreshCounter) { mutableIntStateOf(customTint(context)) }
        var rotInterval by remember(refreshCounter) { mutableIntStateOf(rotationInterval(context)) }
        var rotShuffle by remember(refreshCounter) { mutableStateOf(rotationShuffle(context)) }
        var rotPool by remember(refreshCounter) {
            mutableStateOf(prefs.getStringSet(C.TV_WALLPAPER_ROTATION_POOL, null)?.toSet().orEmpty())
        }

        val tabRequesters = remember { List(3) { FocusRequester() } }
        val firstContentRequester = remember { FocusRequester() }
        val footerQrRequester = remember { FocusRequester() }
        val footerResetRequester = remember { FocusRequester() }
        var lastBodyRequester by remember { mutableStateOf(firstContentRequester) }

        val ambientInt = TvAmbient.color(context) ?: TvAccent.onDark

        fun syncState() {
            refreshCounter++
            currentStyle = style(context)
            customItems = listCustomImages(context)
            currentVis = visibility(context)
            currentBlur = blur(context)
            currentCardOpacity = cardOpacity(context)
            currentTint = customTint(context)
            rotInterval = rotationInterval(context)
            rotShuffle = rotationShuffle(context)
            rotPool = prefs.getStringSet(C.TV_WALLPAPER_ROTATION_POOL, null)?.toSet().orEmpty()
        }

        fun applyStyleKey(styleKey: String) {
            currentStyle = styleKey
            prefs.edit {
                putString(C.TV_WALLPAPER, styleKey)
                putLong(PREF_ROTATION_LAST_MS, System.currentTimeMillis())
            }
            onNotifyAndRefresh()
        }

        fun togglePoolMembership(styleKey: String) {
            if (styleKey == STYLE_DEFAULT) return
            val next = LinkedHashSet(rotPool)
            if (!next.add(styleKey)) {
                next.remove(styleKey)
            }
            rotPool = next
            prefs.edit { putStringSet(C.TV_WALLPAPER_ROTATION_POOL, next) }
            onNotifyAndRefresh()
        }

        fun openQrUploader() {
            showQrUploadModal(
                context = context,
                scope = scope,
                onImageAdded = { newStyleKey ->
                    if (rotInterval != 0) {
                        val nextPool = LinkedHashSet(prefs.getStringSet(C.TV_WALLPAPER_ROTATION_POOL, null).orEmpty())
                        nextPool.add(newStyleKey)
                        prefs.edit { putStringSet(C.TV_WALLPAPER_ROTATION_POOL, nextPool) }
                    }
                    syncState()
                    onNotifyAndRefresh()
                },
                onGalleryChanged = {
                    syncState()
                    onNotifyAndRefresh()
                },
            )
        }

        fun openCardOptions(styleKey: String, customItem: CustomWallpaperItem?) {
            val label = styleLabel(context, styleKey)
            val inPool = styleKey in rotPool
            val options = buildList {
                add(context.getString(R.string.tv_wallpaper_web_send))
                if (styleKey != STYLE_DEFAULT) {
                    val rotLabel = context.getString(R.string.tv_wallpaper_rotation_toggle) +
                        if (inPool) "  ✓" else ""
                    add(rotLabel)
                }
                if (customItem != null) {
                    add(context.getString(R.string.tv_wallpaper_delete_image))
                }
            }
            val icons = buildList<Int?> {
                add(R.drawable.tv_ic_check)
                if (styleKey != STYLE_DEFAULT) add(R.drawable.tv_ic_refresh)
                if (customItem != null) add(R.drawable.tv_ic_delete)
            }
            TvDialogs.showChoices(
                context = context,
                title = label,
                options = options,
                selected = 0,
                icons = icons,
            ) { idx ->
                when {
                    idx == 0 -> applyStyleKey(styleKey)
                    styleKey != STYLE_DEFAULT && idx == 1 -> togglePoolMembership(styleKey)
                    customItem != null -> {
                        deleteCustomImage(context, customItem.fileName)
                        syncState()
                        onNotifyAndRefresh()
                    }
                }
            }
        }

        val tabs = remember(context) {
            listOf(
                R.drawable.tv_ic_image to context.getString(R.string.tv_wallpaper_tab_gallery),
                R.drawable.tv_ic_refresh to context.getString(R.string.tv_wallpaper_tab_rotation),
                R.drawable.tv_ic_tune to context.getString(R.string.tv_wallpaper_tab_adjust),
            )
        }

        SidePanel(
            title = context.getString(R.string.tv_wallpaper),
            subtitle = context.getString(R.string.tv_wallpaper_long_press_hint),
            width = 564.dp,
            scrollable = false,
            compactHeader = true,
            headerExtra = {
                PanelTabs(
                    tabs = tabs,
                    selectedTab = selectedTab,
                    tabRequesters = tabRequesters,
                    downTarget = firstContentRequester,
                    onSelect = { idx -> selectedTab = idx },
                )
            },
            footer = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FooterActionChip(
                        label = context.getString(R.string.tv_wallpaper_add_qr_card),
                        icon = R.drawable.tv_ic_wifi,
                        requester = footerQrRequester,
                        rightTarget = footerResetRequester,
                        upTarget = lastBodyRequester,
                        onClick = { openQrUploader() },
                    )
                    FooterActionChip(
                        label = context.getString(R.string.tv_reset),
                        icon = R.drawable.tv_ic_replay,
                        requester = footerResetRequester,
                        leftTarget = footerQrRequester,
                        upTarget = lastBodyRequester,
                        onClick = {
                            prefs.edit {
                                putString(C.TV_WALLPAPER, STYLE_DEFAULT)
                                putInt(C.TV_WALLPAPER_VISIBILITY, DEFAULT_VISIBILITY)
                                putInt(C.TV_WALLPAPER_BLUR, DEFAULT_BLUR)
                                putInt(C.TV_WALLPAPER_CARD_OPACITY, DEFAULT_CARD_OPACITY)
                                putInt(C.TV_WALLPAPER_CUSTOM_TINT, TINT_OFF)
                                putInt(C.TV_WALLPAPER_ROTATION_INTERVAL, 0)
                            }
                            syncState()
                            onNotifyAndRefresh()
                        },
                    )
                }
            },
        ) {
            when (selectedTab) {
                0 -> GalleryTabBody(
                    context = context,
                    currentStyle = currentStyle,
                    customItems = customItems,
                    ambientInt = ambientInt,
                    rotInterval = rotInterval,
                    rotPool = rotPool,
                    refreshCounter = refreshCounter,
                    tabRequester = tabRequesters[0],
                    firstContentRequester = firstContentRequester,
                    footerRequester = footerQrRequester,
                    onTrackBodyRequester = { lastBodyRequester = it },
                    onSelectStyle = ::applyStyleKey,
                    onLongPressCard = ::openCardOptions,
                    onOpenQr = ::openQrUploader,
                )
                1 -> RotationTabBody(
                    context = context,
                    prefs = prefs,
                    currentStyle = currentStyle,
                    customItems = customItems,
                    ambientInt = ambientInt,
                    rotInterval = rotInterval,
                    rotShuffle = rotShuffle,
                    rotPool = rotPool,
                    refreshCounter = refreshCounter,
                    tabRequester = tabRequesters[1],
                    firstContentRequester = firstContentRequester,
                    footerRequester = footerQrRequester,
                    onTrackBodyRequester = { lastBodyRequester = it },
                    onIntervalChanged = { newInt ->
                        if (newInt != 0 && rotPool.isEmpty()) {
                            val seeded = defaultSeedPool(context)
                            rotPool = seeded
                            prefs.edit { putStringSet(C.TV_WALLPAPER_ROTATION_POOL, seeded) }
                        }
                        rotInterval = newInt
                        prefs.edit {
                            putInt(C.TV_WALLPAPER_ROTATION_INTERVAL, newInt)
                            putLong(PREF_ROTATION_LAST_MS, System.currentTimeMillis())
                        }
                        onNotifyAndRefresh()
                    },
                    onShuffleChanged = { shuf ->
                        rotShuffle = shuf
                        prefs.edit { putBoolean(C.TV_WALLPAPER_ROTATION_SHUFFLE, shuf) }
                    },
                    onStepNextNow = {
                        val applied = advanceRotationNow(context)
                        if (applied != null) {
                            currentStyle = applied
                            onNotifyAndRefresh()
                        }
                    },
                    onTogglePool = ::togglePoolMembership,
                    onSetFullPool = { newSet ->
                        rotPool = newSet
                        prefs.edit { putStringSet(C.TV_WALLPAPER_ROTATION_POOL, newSet) }
                        onNotifyAndRefresh()
                    },
                )
                2 -> AdjustmentsTabBody(
                    context = context,
                    currentStyle = currentStyle,
                    ambientInt = ambientInt,
                    currentVis = currentVis,
                    currentBlur = currentBlur,
                    currentCardOpacity = currentCardOpacity,
                    currentTint = currentTint,
                    customCount = customItems.size,
                    tabRequester = tabRequesters[2],
                    firstContentRequester = firstContentRequester,
                    footerRequester = footerQrRequester,
                    onTrackBodyRequester = { lastBodyRequester = it },
                    onVisChanged = { v ->
                        currentVis = v
                        prefs.edit { putInt(C.TV_WALLPAPER_VISIBILITY, v) }
                        onNotifyAndRefresh()
                    },
                    onBlurChanged = { b ->
                        currentBlur = b
                        prefs.edit { putInt(C.TV_WALLPAPER_BLUR, b) }
                        onNotifyAndRefresh()
                    },
                    onCardOpacityChanged = { o ->
                        currentCardOpacity = o
                        prefs.edit { putInt(C.TV_WALLPAPER_CARD_OPACITY, o) }
                        onNotifyAndRefresh()
                    },
                    onTintChanged = { t ->
                        currentTint = t
                        prefs.edit { putInt(C.TV_WALLPAPER_CUSTOM_TINT, t) }
                        onNotifyAndRefresh()
                    },
                    onDeleteAllCustom = {
                        deleteAllCustomImages(context)
                        syncState()
                        onNotifyAndRefresh()
                    },
                )
            }
        }

        LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos {}
            runCatching { tabRequesters.getOrNull(selectedTab)?.requestFocus() }
        }
    }

    @Composable
    private fun GalleryTabBody(
        context: Context,
        currentStyle: String,
        customItems: List<CustomWallpaperItem>,
        ambientInt: Int,
        rotInterval: Int,
        rotPool: Set<String>,
        refreshCounter: Int,
        tabRequester: FocusRequester,
        firstContentRequester: FocusRequester,
        footerRequester: FocusRequester,
        onTrackBodyRequester: (FocusRequester) -> Unit,
        onSelectStyle: (String) -> Unit,
        onLongPressCard: (String, CustomWallpaperItem?) -> Unit,
        onOpenQr: () -> Unit,
    ) {
        val rows = remember(customItems, context) {
            val out = mutableListOf<GalleryListRow>()
            var pairIdx = 0

            out.add(GalleryListRow.Section(context.getString(R.string.tv_wallpaper_section_studio)))
            val builtinEntries = BUILTIN_STYLES.map { GalleryGridEntry.StyleCard(it) }
            builtinEntries.chunked(2).forEach { chunk ->
                out.add(GalleryListRow.PairRow(pairIdx++, chunk[0], chunk.getOrNull(1)))
            }

            val customHeader = if (customItems.isEmpty()) {
                context.getString(R.string.tv_wallpaper_section_custom)
            } else {
                "${context.getString(R.string.tv_wallpaper_section_custom)} (${customItems.size})"
            }
            out.add(GalleryListRow.Section(customHeader))
            val customEntries = customItems.map { GalleryGridEntry.StyleCard(it.styleKey, it) } + GalleryGridEntry.AddQrCard
            customEntries.chunked(2).forEach { chunk ->
                out.add(GalleryListRow.PairRow(pairIdx++, chunk[0], chunk.getOrNull(1)))
            }
            out to pairIdx
        }

        val listRows = rows.first
        val totalPairRows = rows.second
        val cardRequesters = remember(totalPairRows) {
            Array(totalPairRows) { arrayOf(FocusRequester(), FocusRequester()) }
        }
        val listState = rememberLazyListState()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .tvTopFade { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 },
            contentPadding = PaddingValues(start = 7.dp, end = 7.dp, top = 5.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(listRows.size) { idx ->
                when (val row = listRows[idx]) {
                    is GalleryListRow.Section -> {
                        Text(
                            row.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TvColors.secondaryText,
                            modifier = Modifier.padding(top = if (idx == 0) 2.dp else 8.dp, bottom = 2.dp, start = 4.dp),
                        )
                    }
                    is GalleryListRow.PairRow -> {
                        val rIdx = row.rowIndex
                        val leftReq = if (rIdx == 0) firstContentRequester else cardRequesters[rIdx][0]
                        val rightReq = cardRequesters[rIdx][1]

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.weight(1f)) {
                                GalleryEntryTile(
                                    context = context,
                                    entry = row.left,
                                    currentStyle = currentStyle,
                                    ambientInt = ambientInt,
                                    showRotationBadge = rotInterval != 0,
                                    inRotationPool = (row.left as? GalleryGridEntry.StyleCard)?.styleKey in rotPool,
                                    refreshCounter = refreshCounter,
                                    requester = leftReq,
                                    onFocused = { onTrackBodyRequester(leftReq) },
                                    onMoveUp = if (rIdx == 0) {
                                        { runCatching { tabRequester.requestFocus() }; true }
                                    } else {
                                        {
                                            val prevReq = if (rIdx - 1 == 0) firstContentRequester else cardRequesters[rIdx - 1][0]
                                            runCatching { prevReq.requestFocus() }
                                            true
                                        }
                                    },
                                    onMoveDown = if (rIdx == totalPairRows - 1) {
                                        { runCatching { footerRequester.requestFocus() }; true }
                                    } else {
                                        {
                                            runCatching { cardRequesters[rIdx + 1][0].requestFocus() }
                                            true
                                        }
                                    },
                                    onMoveLeft = { true },
                                    onMoveRight = {
                                        if (row.right != null) {
                                            runCatching { rightReq.requestFocus() }
                                        }
                                        true
                                    },
                                    onSelectStyle = onSelectStyle,
                                    onLongPressCard = onLongPressCard,
                                    onOpenQr = onOpenQr,
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                if (row.right != null) {
                                    GalleryEntryTile(
                                        context = context,
                                        entry = row.right,
                                        currentStyle = currentStyle,
                                        ambientInt = ambientInt,
                                        showRotationBadge = rotInterval != 0,
                                        inRotationPool = (row.right as? GalleryGridEntry.StyleCard)?.styleKey in rotPool,
                                        refreshCounter = refreshCounter,
                                        requester = rightReq,
                                        onFocused = { onTrackBodyRequester(rightReq) },
                                        onMoveUp = if (rIdx == 0) {
                                            { runCatching { tabRequester.requestFocus() }; true }
                                        } else {
                                            {
                                                runCatching { cardRequesters[rIdx - 1][1].requestFocus() }
                                                true
                                            }
                                        },
                                        onMoveDown = if (rIdx == totalPairRows - 1) {
                                            { runCatching { footerRequester.requestFocus() }; true }
                                        } else {
                                            {
                                                val nextHasRight = listRows
                                                    .filterIsInstance<GalleryListRow.PairRow>()
                                                    .getOrNull(rIdx + 1)?.right != null
                                                val target = if (nextHasRight) cardRequesters[rIdx + 1][1] else cardRequesters[rIdx + 1][0]
                                                runCatching { target.requestFocus() }
                                                true
                                            }
                                        },
                                        onMoveLeft = {
                                            runCatching { leftReq.requestFocus() }
                                            true
                                        },
                                        onMoveRight = { true },
                                        onSelectStyle = onSelectStyle,
                                        onLongPressCard = onLongPressCard,
                                        onOpenQr = onOpenQr,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GalleryEntryTile(
        context: Context,
        entry: GalleryGridEntry,
        currentStyle: String,
        ambientInt: Int,
        showRotationBadge: Boolean,
        inRotationPool: Boolean,
        refreshCounter: Int,
        requester: FocusRequester,
        onFocused: () -> Unit,
        onMoveUp: () -> Boolean,
        onMoveDown: () -> Boolean,
        onMoveLeft: () -> Boolean,
        onMoveRight: () -> Boolean,
        onSelectStyle: (String) -> Unit,
        onLongPressCard: (String, CustomWallpaperItem?) -> Unit,
        onOpenQr: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val scale by animateFloatAsState(if (focused) 1.035f else 1f, label = "cardScale")
        val shape = RoundedCornerShape(13.dp)
        val accentCompose = MaterialTheme.colorScheme.primary

        var holdJob by remember { mutableStateOf<Job?>(null) }
        var longTriggered by remember { mutableStateOf(false) }

        fun handleShortAction() {
            when (entry) {
                is GalleryGridEntry.AddQrCard -> onOpenQr()
                is GalleryGridEntry.StyleCard -> onSelectStyle(entry.styleKey)
            }
        }

        fun handleLongAction() {
            if (entry is GalleryGridEntry.StyleCard) {
                onLongPressCard(entry.styleKey, entry.customItem)
            } else {
                onOpenQr()
            }
        }

        val isSelected = entry is GalleryGridEntry.StyleCard && entry.styleKey == currentStyle
        val borderWidth = when {
            focused -> 2.4.dp
            isSelected -> 1.8.dp
            else -> 1.dp
        }
        val borderColor = when {
            focused -> ComposeColor.White
            isSelected -> accentCompose
            else -> ComposeColor(0x2EFFFFFF)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .focusRequester(requester)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .onPreviewKeyEvent { ev ->
                    val isSelect = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                    if (isSelect) {
                        when (ev.type) {
                            KeyEventType.KeyDown -> {
                                if (holdJob == null && !longTriggered) {
                                    val timeout = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(380L, 580L)
                                    holdJob = scope.launch {
                                        delay(timeout)
                                        longTriggered = true
                                        handleLongAction()
                                    }
                                }
                                true
                            }
                            KeyEventType.KeyUp -> {
                                val wasLong = longTriggered
                                holdJob?.cancel()
                                holdJob = null
                                longTriggered = false
                                if (!wasLong) handleShortAction()
                                true
                            }
                            else -> true
                        }
                    } else if (ev.type == KeyEventType.KeyDown) {
                        when (ev.key) {
                            Key.DirectionUp -> onMoveUp()
                            Key.DirectionDown -> onMoveDown()
                            Key.DirectionLeft -> onMoveLeft()
                            Key.DirectionRight -> onMoveRight()
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .background(ComposeColor(0xFF101018))
                .border(borderWidth, borderColor, shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { handleShortAction() },
                    onLongClick = { handleLongAction() },
                ),
        ) {
            when (entry) {
                is GalleryGridEntry.AddQrCard -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        accentCompose.copy(alpha = if (focused) 0.34f else 0.18f),
                                        ComposeColor(0xFF161522),
                                    )
                                )
                            )
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (focused) ComposeColor.White else accentCompose.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.tv_ic_wifi),
                                contentDescription = null,
                                tint = if (focused) TvColors.onFocused else ComposeColor.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            context.getString(R.string.tv_wallpaper_add_qr_card),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ComposeColor.White,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                is GalleryGridEntry.StyleCard -> {
                    val thumb = remember(entry.styleKey, ambientInt, refreshCounter) {
                        getGalleryPreviewBitmap(context, entry.styleKey, ambientInt)
                    }
                    if (thumb != null && !thumb.isRecycled) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Bottom legibility gradient + title
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(ComposeColor.Transparent, ComposeColor(0xDC08080E))
                                )
                            )
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(
                            styleLabel(context, entry.styleKey),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected || focused) FontWeight.SemiBold else FontWeight.Medium,
                            color = ComposeColor.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Top-right active badge
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(7.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(accentCompose)
                                .border(1.dp, ComposeColor.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.tv_ic_check),
                                contentDescription = null,
                                tint = ComposeColor.White,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }

                    // Top-left rotation playlist badge when rotation is enabled or item is in pool
                    if (entry.styleKey != STYLE_DEFAULT && (showRotationBadge || inRotationPool)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(7.dp)
                                .size(19.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (inRotationPool) ComposeColor(0xE614141E) else ComposeColor(0x880C0C12))
                                .border(
                                    1.dp,
                                    if (inRotationPool) accentCompose else ComposeColor(0x55FFFFFF),
                                    RoundedCornerShape(5.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (inRotationPool) {
                                Icon(
                                    painter = painterResource(R.drawable.tv_ic_check),
                                    contentDescription = null,
                                    tint = accentCompose,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RotationTabBody(
        context: Context,
        prefs: SharedPreferences,
        currentStyle: String,
        customItems: List<CustomWallpaperItem>,
        ambientInt: Int,
        rotInterval: Int,
        rotShuffle: Boolean,
        rotPool: Set<String>,
        refreshCounter: Int,
        tabRequester: FocusRequester,
        firstContentRequester: FocusRequester,
        footerRequester: FocusRequester,
        onTrackBodyRequester: (FocusRequester) -> Unit,
        onIntervalChanged: (Int) -> Unit,
        onShuffleChanged: (Boolean) -> Unit,
        onStepNextNow: () -> Unit,
        onTogglePool: (String) -> Unit,
        onSetFullPool: (Set<String>) -> Unit,
    ) {
        val intervalOptions = remember(context) {
            ROTATION_INTERVAL_VALUES.map { rotationIntervalLabel(context, it) }
        }
        val selectedIdx = ROTATION_INTERVAL_VALUES.indexOf(rotInterval).coerceAtLeast(0)
        val selectableKeys = remember(customItems) {
            BUILTIN_STYLES.filter { it != STYLE_DEFAULT } + customItems.map { it.styleKey }
        }
        val customMap = remember(customItems) { customItems.associateBy { it.styleKey } }
        val listState = rememberLazyListState()
        val lastItemRequester = remember { FocusRequester() }
        val accentCompose = MaterialTheme.colorScheme.primary

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .tvTopFade { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 },
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        ) {
            item {
                SelectorRow(
                    label = context.getString(R.string.tv_wallpaper_rotation_timer),
                    options = intervalOptions,
                    selected = selectedIdx,
                    icon = R.drawable.tv_ic_timer,
                    compact = true,
                    modifier = Modifier
                        .tvPanelFirstRow(firstContentRequester) { tabRequester }
                        .onFocusChanged { if (it.isFocused) onTrackBodyRequester(firstContentRequester) },
                ) { idx ->
                    val chosen = ROTATION_INTERVAL_VALUES.getOrElse(idx) { 0 }
                    onIntervalChanged(chosen)
                }
            }

            item {
                FocusRow(
                    compact = true,
                    onClick = { onShuffleChanged(!rotShuffle) },
                ) { focused ->
                    val textColor = if (focused) TvColors.onFocused else ComposeColor.White
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.tv_ic_swap),
                            contentDescription = null,
                            tint = if (focused) TvColors.onFocused else ComposeColor(0xD8FFFFFF),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            context.getString(R.string.tv_wallpaper_rotation_shuffle),
                            fontSize = 15.sp,
                            color = textColor,
                            modifier = Modifier.weight(1f),
                        )
                        RadioDot(checked = rotShuffle, focused = focused)
                    }
                }
            }

            if (selectableKeys.count { it in rotPool } >= 2) {
                item {
                    ButtonRow(
                        label = context.getString(R.string.tv_wallpaper_rotation_next),
                        icon = R.drawable.tv_ic_refresh,
                        compact = true,
                        onClick = onStepNextNow,
                    )
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val allChecked = selectableKeys.all { it in rotPool }
                    Box(Modifier.weight(1f)) {
                        ButtonRow(
                            label = context.getString(
                                if (allChecked) R.string.tv_wallpaper_rotation_clear_all else R.string.tv_wallpaper_rotation_select_all
                            ),
                            icon = R.drawable.tv_ic_check,
                            compact = true,
                            onClick = {
                                onSetFullPool(if (allChecked) emptySet() else selectableKeys.toSet())
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            items(selectableKeys.size) { i ->
                val key = selectableKeys[i]
                val checked = key in rotPool
                val isActiveNow = key == currentStyle
                val isLast = i == selectableKeys.lastIndex
                val thumb = remember(key, ambientInt, refreshCounter) {
                    getGalleryPreviewBitmap(context, key, ambientInt)
                }

                FocusRow(
                    compact = true,
                    modifier = if (isLast) {
                        Modifier
                            .tvPanelLastRow(lastItemRequester, footerRequester)
                            .onFocusChanged { if (it.isFocused) onTrackBodyRequester(lastItemRequester) }
                    } else {
                        Modifier
                    },
                    onClick = { onTogglePool(key) },
                ) { focused ->
                    val textColor = if (focused) TvColors.onFocused else ComposeColor.White
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(width = 48.dp, height = 28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(ComposeColor(0xFF0E0E14))
                                .border(
                                    1.dp,
                                    if (isActiveNow) accentCompose else ComposeColor(0x38FFFFFF),
                                    RoundedCornerShape(6.dp),
                                ),
                        ) {
                            if (thumb != null && !thumb.isRecycled) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Text(
                            styleLabel(context, key) + if (customMap.containsKey(key)) "" else "",
                            fontSize = 15.sp,
                            fontWeight = if (checked || focused) FontWeight.SemiBold else FontWeight.Normal,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    when {
                                        checked && focused -> TvColors.onFocused
                                        checked -> accentCompose
                                        else -> ComposeColor.Transparent
                                    }
                                )
                                .border(
                                    1.8.dp,
                                    when {
                                        focused -> TvColors.onFocused
                                        checked -> accentCompose
                                        else -> ComposeColor(0x70FFFFFF)
                                    },
                                    RoundedCornerShape(5.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (checked) {
                                Icon(
                                    painter = painterResource(R.drawable.tv_ic_check),
                                    contentDescription = null,
                                    tint = ComposeColor.White,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AdjustmentsTabBody(
        context: Context,
        currentStyle: String,
        ambientInt: Int,
        currentVis: Int,
        currentBlur: Int,
        currentCardOpacity: Int,
        currentTint: Int,
        customCount: Int,
        tabRequester: FocusRequester,
        firstContentRequester: FocusRequester,
        footerRequester: FocusRequester,
        onTrackBodyRequester: (FocusRequester) -> Unit,
        onVisChanged: (Int) -> Unit,
        onBlurChanged: (Int) -> Unit,
        onCardOpacityChanged: (Int) -> Unit,
        onTintChanged: (Int) -> Unit,
        onDeleteAllCustom: () -> Unit,
    ) {
        val listState = rememberLazyListState()
        val lastItemRequester = remember { FocusRequester() }
        val hasWallpaper = currentStyle != STYLE_DEFAULT
        val isCustom = isCustomStyleKey(currentStyle)
        val tintOptions = remember(context) {
            listOf(
                customTintLabel(context, TINT_OFF),
                customTintLabel(context, TINT_SUBTLE),
                customTintLabel(context, TINT_FULL),
            )
        }
        var previewBmp by remember(currentStyle, ambientInt, currentVis, currentTint) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(currentStyle, ambientInt, currentVis, currentTint) {
            previewBmp = if (hasWallpaper) {
                withContext(Dispatchers.Default) {
                    getGalleryPreviewBitmap(
                        context = context,
                        styleKey = currentStyle,
                        accentColor = ambientInt,
                        tintMode = if (isCustom) currentTint else TINT_OFF,
                        visibilityPct = if (isCustom) currentVis else 100,
                    )
                }
            } else {
                null
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .tvTopFade { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 },
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        ) {
            item {
                SliderRow(
                    label = context.getString(R.string.tv_wallpaper_visibility),
                    value = currentVis,
                    range = 10..100,
                    step = 5,
                    icon = R.drawable.tv_ic_oled,
                    compact = true,
                    valueText = { "$it%" },
                    modifier = Modifier
                        .tvPanelFirstRow(firstContentRequester) { tabRequester }
                        .onFocusChanged { if (it.isFocused) onTrackBodyRequester(firstContentRequester) },
                    onChange = onVisChanged,
                )
            }
            item {
                SliderRow(
                    label = context.getString(R.string.tv_wallpaper_blur),
                    value = currentBlur,
                    range = 0..100,
                    step = 5,
                    icon = R.drawable.tv_ic_tune,
                    compact = true,
                    valueText = { "$it%" },
                    modifier = if (!hasWallpaper && customCount == 0) {
                        Modifier
                            .tvPanelLastRow(lastItemRequester, footerRequester)
                            .onFocusChanged { if (it.isFocused) onTrackBodyRequester(lastItemRequester) }
                    } else {
                        Modifier
                    },
                    onChange = onBlurChanged,
                )
            }
            if (isCustom) {
                item {
                    SelectorRow(
                        label = context.getString(R.string.tv_wallpaper_custom_tint),
                        options = tintOptions,
                        selected = currentTint.coerceIn(TINT_OFF, TINT_FULL),
                        icon = R.drawable.tv_ic_palette,
                        compact = true,
                    ) { idx ->
                        onTintChanged(idx.coerceIn(TINT_OFF, TINT_FULL))
                    }
                }
            }
            if (hasWallpaper) {
                item {
                    SliderRow(
                        label = context.getString(R.string.tv_wallpaper_card_opacity),
                        value = currentCardOpacity,
                        range = MIN_CARD_OPACITY..MAX_CARD_OPACITY,
                        step = 5,
                        icon = R.drawable.tv_ic_opacity,
                        compact = true,
                        valueText = { "$it%" },
                        modifier = if (customCount == 0) {
                            Modifier
                                .tvPanelLastRow(lastItemRequester, footerRequester)
                                .onFocusChanged { if (it.isFocused) onTrackBodyRequester(lastItemRequester) }
                        } else {
                            Modifier
                        },
                        onChange = onCardOpacityChanged,
                    )
                }
                item {
                    // Compact live preview of smoked-glass card & pill translucency over the active wallpaper
                    val previewShape = RoundedCornerShape(12.dp)
                    val cardAlpha = currentCardOpacity / 100f
                    val pillAlpha = (currentCardOpacity * 0.56f).roundToInt().coerceIn(24, 54) / 100f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 6.dp)
                            .height(66.dp)
                            .clip(previewShape)
                            .background(ComposeColor(0xFF000000))
                            .border(1.dp, ComposeColor(0x26FFFFFF), previewShape),
                    ) {
                        previewBmp?.takeIf { !it.isRecycled }?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val miniCardShape = RoundedCornerShape(10.dp)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(miniCardShape)
                                    .background(ComposeColor(0xFF0B0C10).copy(alpha = cardAlpha))
                                    .background(cardGlassSheenBrush)
                                    .border(1.dp, cardGlassRimBrush, miniCardShape)
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(ComposeColor(ambientInt).copy(alpha = 0.85f)),
                                )
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = context.getString(R.string.tv_wallpaper_card_opacity),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ComposeColor.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "$currentCardOpacity%",
                                        fontSize = 10.sp,
                                        color = TvColors.secondaryText,
                                        maxLines = 1,
                                    )
                                }
                            }
                            val miniPillShape = RoundedCornerShape(18.dp)
                            Box(
                                modifier = Modifier
                                    .clip(miniPillShape)
                                    .background(ComposeColor(0xFF0B0C10).copy(alpha = pillAlpha))
                                    .border(1.dp, ComposeColor(0x33FFFFFF), miniPillShape)
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = context.getString(R.string.refresh),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ComposeColor.White,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            if (customCount > 0) {
                item {
                    ButtonRow(
                        label = context.getString(R.string.tv_wallpaper_delete_all_custom, customCount),
                        icon = R.drawable.tv_ic_delete,
                        compact = true,
                        modifier = Modifier
                            .tvPanelLastRow(lastItemRequester, footerRequester)
                            .onFocusChanged { if (it.isFocused) onTrackBodyRequester(lastItemRequester) },
                        onClick = onDeleteAllCustom,
                    )
                }
            }
        }
    }

    // =========================================================================
    // PERSISTENT MULTI-IMAGE PHONE QR UPLOAD SERVER & MODAL (STUDIO GLASS)
    // =========================================================================

    private sealed interface WebAction {
        data class Added(val styleKey: String) : WebAction
        data class Applied(val styleKey: String) : WebAction
        data object Deleted : WebAction
    }

    fun showQrUploadModal(
        context: Context,
        scope: CoroutineScope,
        onImageAdded: (newStyleKey: String) -> Unit,
        onGalleryChanged: () -> Unit = {},
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

            val sessionAddedCount = mutableIntStateOf(0)

            val dialog = TvDialogs.show(context) { dismiss ->
                val closeRequester = remember { FocusRequester() }
                val added = sessionAddedCount.intValue
                SidePanel(
                    title = context.getString(R.string.tv_wallpaper_qr_title),
                    scrollable = true,
                    compactHeader = true,
                    footer = {
                        ButtonRow(
                            label = context.getString(R.string.close_player),
                            modifier = Modifier.focusRequester(closeRequester),
                            compact = true,
                            onClick = dismiss,
                        )
                    },
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            context.getString(R.string.tv_wallpaper_qr_help),
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                            color = TvColors.secondaryText,
                        )
                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier
                                .size(184.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ComposeColor.White)
                                .padding(9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = url,
                                modifier = Modifier.size(166.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(url, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                        if (added > 0) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
                                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 14.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.tv_ic_check),
                                    contentDescription = null,
                                    tint = ComposeColor.White,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    context.getString(R.string.tv_wallpaper_qr_added_count, added),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ComposeColor.White,
                                )
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        runCatching { closeRequester.requestFocus() }
                    }
                }
            }
            // Keep 0f dim behind the QR modal too so every uploaded photo previews live behind it!
            dialog.window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
            }

            val serverJob = scope.launch(Dispatchers.IO) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) {
                        when (val action = handleWallpaperClient(client, appContext)) {
                            is WebAction.Added -> {
                                appContext.prefs().edit {
                                    putString(C.TV_WALLPAPER, action.styleKey)
                                    putLong(PREF_ROTATION_LAST_MS, System.currentTimeMillis())
                                }
                                withContext(Dispatchers.Main) {
                                    sessionAddedCount.intValue++
                                    onImageAdded(action.styleKey)
                                    TvToast.makeText(appContext, R.string.tv_wallpaper_applied, Toast.LENGTH_SHORT).show()
                                }
                            }
                            is WebAction.Applied -> {
                                appContext.prefs().edit {
                                    putString(C.TV_WALLPAPER, action.styleKey)
                                    putLong(PREF_ROTATION_LAST_MS, System.currentTimeMillis())
                                }
                                withContext(Dispatchers.Main) {
                                    onGalleryChanged()
                                    TvToast.makeText(appContext, R.string.tv_wallpaper_applied, Toast.LENGTH_SHORT).show()
                                }
                            }
                            WebAction.Deleted -> {
                                withContext(Dispatchers.Main) {
                                    onGalleryChanged()
                                    TvToast.makeText(appContext, R.string.tv_wallpaper_web_deleted, Toast.LENGTH_SHORT).show()
                                }
                            }
                            null -> Unit
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

    private fun handleWallpaperClient(socket: Socket, context: Context): WebAction? {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = s.getInputStream()
            val requestLine = readHttpLine(input) ?: return null
            var contentLength = 0
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val out = s.getOutputStream()
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val rawPath = parts.getOrNull(1).orEmpty().substringBefore('?')

            if (method == "GET" && rawPath == "/list") {
                val items = listCustomImages(context)
                val activeKey = style(context)
                val activeFile = if (activeKey.startsWith(CUSTOM_PREFIX)) activeKey.removePrefix(CUSTOM_PREFIX) else ""
                val json = buildString {
                    append("{\"active\":\"")
                    append(activeFile.replace("\"", ""))
                    append("\",\"items\":[")
                    items.forEachIndexed { idx, item ->
                        if (idx > 0) append(',')
                        append("{\"file\":\"")
                        append(item.fileName.replace("\"", ""))
                        append("\",\"index\":")
                        append(item.displayIndex)
                        append('}')
                    }
                    append("]}")
                }
                writeJsonResponse(out, 200, json)
                return null
            }

            if (method == "GET" && rawPath.startsWith("/thumb/")) {
                val fileName = rawPath.removePrefix("/thumb/")
                val valid = fileName.startsWith("wp_") &&
                    fileName.endsWith(".jpg") &&
                    !fileName.contains('/') &&
                    !fileName.contains('\\') &&
                    !fileName.contains("..")
                val item = if (valid) listCustomImages(context).firstOrNull { it.fileName == fileName } else null
                if (item == null) {
                    writePlainResponse(out, 404, "Not Found")
                    return null
                }
                ensureThumbForFile(item.file, item.thumbFile)
                val bytes = runCatching {
                    val src = if (item.thumbFile.isFile && item.thumbFile.length() > 128L) item.thumbFile else item.file
                    src.readBytes()
                }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    writeBytesResponse(out, 200, "image/jpeg", bytes, cacheHeader = "public, max-age=3600")
                } else {
                    writePlainResponse(out, 404, "Not Found")
                }
                return null
            }

            if (method == "POST" && rawPath == "/apply" && contentLength in 1..512) {
                val rawBytes = readExactBytes(input, contentLength) ?: run {
                    writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                    return null
                }
                val fileName = String(rawBytes, Charsets.UTF_8).trim()
                val item = listCustomImages(context).firstOrNull { it.fileName == fileName }
                if (item != null) {
                    writePlainResponse(out, 200, context.getString(R.string.tv_wallpaper_applied))
                    return WebAction.Applied(item.styleKey)
                }
                writePlainResponse(out, 404, "Not Found")
                return null
            }

            if (method == "POST" && rawPath == "/delete" && contentLength in 1..512) {
                val rawBytes = readExactBytes(input, contentLength) ?: run {
                    writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                    return null
                }
                val fileName = String(rawBytes, Charsets.UTF_8).trim()
                val item = listCustomImages(context).firstOrNull { it.fileName == fileName }
                if (item != null) {
                    deleteCustomImage(context, item.fileName)
                    writePlainResponse(out, 200, context.getString(R.string.tv_wallpaper_web_deleted))
                    return WebAction.Deleted
                }
                writePlainResponse(out, 404, "Not Found")
                return null
            }

            if (method == "POST" && rawPath == "/upload" && contentLength in 1..MAX_UPLOAD_BYTES) {
                val body = readExactBytes(input, contentLength) ?: run {
                    writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                    return null
                }
                val savedKey = decodeAndSaveNewCustomImage(context, body)
                if (savedKey != null) {
                    writePlainResponse(out, 200, context.getString(R.string.tv_wallpaper_applied))
                    return WebAction.Added(savedKey)
                }
                writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                return null
            }

            if (method == "POST" && rawPath == "/url" && contentLength in 1..8192) {
                val rawBytes = readExactBytes(input, contentLength) ?: run {
                    writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                    return null
                }
                val targetUrl = String(rawBytes, Charsets.UTF_8).trim()
                if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
                    writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                    return null
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

                val savedKey = if (fetched != null) decodeAndSaveNewCustomImage(context, fetched) else null
                if (savedKey != null) {
                    writePlainResponse(out, 200, context.getString(R.string.tv_wallpaper_applied))
                    return WebAction.Added(savedKey)
                }
                writePlainResponse(out, 400, context.getString(R.string.tv_wallpaper_invalid_image))
                return null
            }

            val accentInt = TvAmbient.color(context) ?: TvAccent.onDark
            val accentHex = String.format(java.util.Locale.US, "#%06X", accentInt and 0xFFFFFF)
            val accentR = Color.red(accentInt)
            val accentG = Color.green(accentInt)
            val accentB = Color.blue(accentInt)

            val pageTitle = escapeHtml(context.getString(R.string.tv_wallpaper_qr_title))
            val connectedLabel = escapeHtml(context.getString(R.string.tv_wallpaper_web_connected))
            val tabPhotos = escapeHtml(context.getString(R.string.tv_wallpaper_web_tab_photos))
            val tabUrl = escapeHtml(context.getString(R.string.tv_wallpaper_web_tab_url))
            val tabTv = escapeHtml(context.getString(R.string.tv_wallpaper_web_tab_tv))
            val pickLabel = escapeHtml(context.getString(R.string.tv_wallpaper_web_pick_file))
            val pickHint = escapeHtml(context.getString(R.string.tv_wallpaper_web_pick_hint))
            val readyLabel = escapeJs(context.getString(R.string.tv_wallpaper_web_ready))
            val clearLabel = escapeHtml(context.getString(R.string.tv_wallpaper_web_clear))
            val urlLabel = escapeHtml(context.getString(R.string.tv_wallpaper_web_paste_url))
            val urlPreviewLabel = escapeHtml(context.getString(R.string.tv_wallpaper_web_url_preview))
            val sendLabel = escapeHtml(context.getString(R.string.tv_wallpaper_web_send))
            val activeLabel = escapeJs(context.getString(R.string.tv_wallpaper_web_active))
            val emptyTvLabel = escapeJs(context.getString(R.string.tv_wallpaper_web_empty_tv))
            val customLabel = escapeJs(context.getString(R.string.tv_wallpaper_custom))
            val doneMsg = escapeJs(context.getString(R.string.tv_wallpaper_applied))
            val deletedMsg = escapeJs(context.getString(R.string.tv_wallpaper_web_deleted))
            val errMsg = escapeJs(context.getString(R.string.tv_wallpaper_invalid_image))

            val html = """
                <!DOCTYPE html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
                <meta name="theme-color" content="#0b0b12">
                <title>Xtra TV</title>
                <style>
                :root{--ac:$accentHex;--ar:$accentR;--ag:$accentG;--ab:$accentB;--bg:#0b0b12;--card:rgba(22,22,33,.78);--bd:rgba(255,255,255,.10);--t1:#f5f6fa;--t2:#9ea3b8}
                *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
                body{margin:0;min-height:100vh;background:radial-gradient(ellipse 95% 42% at 12% 0%,rgba(var(--ar),var(--ag),var(--ab),.26),transparent 72%),radial-gradient(ellipse 75% 40% at 92% 100%,rgba(var(--ar),var(--ag),var(--ab),.12),transparent 70%),var(--bg);color:var(--t1);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;padding:max(20px,env(safe-area-inset-top)) 16px max(28px,env(safe-area-inset-bottom))}
                .wrap{max-width:460px;margin:0 auto}
                .hdr{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px;padding:0 2px}
                .brand{display:flex;align-items:center;gap:11px}
                .logo{width:38px;height:38px;border-radius:12px;background:linear-gradient(135deg,rgba(var(--ar),var(--ag),var(--ab),.95),rgba(var(--ar),var(--ag),var(--ab),.55));display:flex;align-items:center;justify-content:center;font-weight:800;font-size:17px;color:#fff;box-shadow:0 6px 20px rgba(var(--ar),var(--ag),var(--ab),.35)}
                .title{font-size:18px;font-weight:700;letter-spacing:-.2px;line-height:1.15}
                .sub{font-size:12.5px;color:var(--t2);margin-top:2px}
                .wifi{display:inline-flex;align-items:center;gap:6px;padding:6px 11px;border-radius:999px;background:rgba(16,185,129,.12);border:1px solid rgba(16,185,129,.28);color:#6ee7b7;font-size:11.5px;font-weight:600;white-space:nowrap}
                .dot{width:7px;height:7px;border-radius:50%;background:#34d399;box-shadow:0 0 8px #34d399}
                .tabs{display:grid;grid-template-columns:1fr 1fr 1fr;gap:4px;padding:4px;border-radius:14px;background:rgba(255,255,255,.05);border:1px solid var(--bd);margin-bottom:14px}
                .tab{border:0;background:transparent;color:var(--t2);padding:10px 6px;border-radius:10px;font-size:13.5px;font-weight:600;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:6px;transition:all .18s ease}
                .tab.on{background:rgba(var(--ar),var(--ag),var(--ab),.24);color:#fff;box-shadow:inset 0 0 0 1px rgba(var(--ar),var(--ag),var(--ab),.55)}
                .cnt-pill{padding:1px 6px;border-radius:999px;background:rgba(255,255,255,.14);font-size:11px;font-weight:700;color:#fff}
                .card{background:var(--card);border:1px solid var(--bd);border-radius:20px;padding:18px;backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);box-shadow:0 16px 40px rgba(0,0,0,.35)}
                .pane{display:none}.pane.on{display:block}
                .drop{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;padding:28px 16px;border-radius:16px;border:1.5px dashed rgba(var(--ar),var(--ag),var(--ab),.50);background:linear-gradient(180deg,rgba(var(--ar),var(--ag),var(--ab),.12),rgba(255,255,255,.02));cursor:pointer;text-align:center;transition:transform .15s,border-color .15s}
                .drop:active{transform:scale(.985)}
                .drop-ic{width:46px;height:46px;border-radius:14px;background:rgba(var(--ar),var(--ag),var(--ab),.22);border:1px solid rgba(var(--ar),var(--ag),var(--ab),.45);display:flex;align-items:center;justify-content:center;color:#fff}
                .drop-t{font-size:15px;font-weight:700;color:#fff}
                .drop-h{font-size:12px;color:var(--t2)}
                #f{display:none}
                .qhdr{display:none;align-items:center;justify-content:space-between;margin:14px 0 8px}
                .qhdr.on{display:flex}
                .qcnt{font-size:12.5px;font-weight:600;color:#d6d9e8}
                .qclr{border:0;background:rgba(255,255,255,.08);color:var(--t2);font-size:12px;font-weight:600;padding:5px 10px;border-radius:8px;cursor:pointer}
                .qgrid{display:flex;gap:8px;overflow-x:auto;padding-bottom:4px;scrollbar-width:none}
                .qgrid::-webkit-scrollbar{display:none}
                .qitem{position:relative;flex:0 0 116px;aspect-ratio:16/9;border-radius:11px;overflow:hidden;border:1px solid rgba(255,255,255,.16);background:#000}
                .qitem img{width:100%;height:100%;object-fit:cover;display:block}
                .qrm{position:absolute;top:4px;right:4px;width:22px;height:22px;border-radius:50%;border:0;background:rgba(0,0,0,.72);color:#fff;font-size:14px;line-height:22px;padding:0;cursor:pointer}
                .prog{display:none;height:5px;border-radius:999px;background:rgba(255,255,255,.10);overflow:hidden;margin:12px 0 2px}
                .prog.on{display:block}
                .pbar{height:100%;width:0;background:linear-gradient(90deg,var(--ac),#fff);border-radius:999px;transition:width .22s ease}
                .btn{width:100%;margin-top:14px;padding:14px 18px;border:0;border-radius:13px;background:linear-gradient(135deg,var(--ac),rgba(var(--ar),var(--ag),var(--ab),.78));color:#fff;font-size:15.5px;font-weight:700;cursor:pointer;box-shadow:0 8px 24px rgba(var(--ar),var(--ag),var(--ab),.30);transition:opacity .15s,transform .15s}
                .btn:active{transform:scale(.985)}
                .btn:disabled{opacity:.42;box-shadow:none;cursor:not-allowed}
                .lbl{display:block;font-size:13.5px;font-weight:600;color:#d6d9e8;margin-bottom:8px}
                .uwrap{position:relative;margin-bottom:12px}
                .uin{width:100%;padding:13px 38px 13px 14px;border-radius:12px;border:1px solid rgba(255,255,255,.15);background:rgba(8,9,14,.85);color:#fff;font-size:14.5px;outline:none}
                .uin:focus{border-color:var(--ac);box-shadow:0 0 0 3px rgba(var(--ar),var(--ag),var(--ab),.22)}
                .uclr{position:absolute;right:8px;top:50%;transform:translateY(-50%);width:26px;height:26px;border-radius:50%;border:0;background:rgba(255,255,255,.12);color:#fff;font-size:15px;display:none;align-items:center;justify-content:center;cursor:pointer}
                .uclr.on{display:flex}
                .uprev{position:relative;width:100%;aspect-ratio:16/9;border-radius:14px;overflow:hidden;border:1px solid var(--bd);background:rgba(0,0,0,.45);display:flex;align-items:center;justify-content:center;color:var(--t2);font-size:13px}
                .uprev img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;display:none}
                .uprev.ok img{display:block}
                .uprev.ok span{display:none}
                .tvgrid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
                .tvitem{position:relative;aspect-ratio:16/9;border-radius:13px;overflow:hidden;border:1.5px solid rgba(255,255,255,.12);background:#000;cursor:pointer;transition:transform .14s,border-color .14s}
                .tvitem:active{transform:scale(.97)}
                .tvitem.act{border-color:var(--ac);box-shadow:0 0 0 1px var(--ac),0 8px 20px rgba(var(--ar),var(--ag),var(--ab),.25)}
                .tvitem img{width:100%;height:100%;object-fit:cover;display:block}
                .tvgrad{position:absolute;inset:auto 0 0 0;height:46%;background:linear-gradient(transparent,rgba(0,0,0,.85));display:flex;align-items:flex-end;justify-content:space-between;padding:7px 9px}
                .tvnm{font-size:12px;font-weight:600;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                .tvbadge{position:absolute;top:6px;left:6px;padding:3px 8px;border-radius:999px;background:var(--ac);color:#fff;font-size:10.5px;font-weight:700;letter-spacing:.2px;box-shadow:0 2px 8px rgba(0,0,0,.4)}
                .tvdel{position:absolute;top:6px;right:6px;width:26px;height:26px;border-radius:50%;border:1px solid rgba(255,255,255,.18);background:rgba(12,12,18,.78);color:#fda4af;display:flex;align-items:center;justify-content:center;cursor:pointer;padding:0}
                .tvdel:active{background:rgba(244,63,94,.85);color:#fff}
                .empty{padding:34px 14px;text-align:center;color:var(--t2);font-size:13.5px}
                .toast{position:fixed;left:50%;bottom:max(20px,env(safe-area-inset-bottom));transform:translate(-50%,16px);opacity:0;pointer-events:none;background:rgba(18,19,28,.95);border:1px solid rgba(255,255,255,.16);color:#fff;padding:11px 18px;border-radius:999px;font-size:13.5px;font-weight:600;box-shadow:0 12px 30px rgba(0,0,0,.5);transition:all .22s ease;z-index:50;max-width:90vw;text-align:center}
                .toast.on{opacity:1;transform:translate(-50%,0)}
                .toast.ok{border-color:rgba(52,211,153,.5);color:#a7f3d0}
                .toast.err{border-color:rgba(251,113,133,.5);color:#fecdd3}
                </style></head><body>
                <div class="wrap">
                  <div class="hdr">
                    <div class="brand">
                      <div class="logo">X</div>
                      <div><div class="title">Xtra TV</div><div class="sub">$pageTitle</div></div>
                    </div>
                    <div class="wifi"><span class="dot"></span>$connectedLabel</div>
                  </div>
                  <div class="tabs">
                    <button class="tab on" id="t0" onclick="goTab(0)">$tabPhotos</button>
                    <button class="tab" id="t1" onclick="goTab(1)">$tabUrl</button>
                    <button class="tab" id="t2" onclick="goTab(2)">$tabTv <span class="cnt-pill" id="tvcnt">0</span></button>
                  </div>
                  <div class="card">
                    <div class="pane on" id="p0">
                      <label class="drop" for="f">
                        <div class="drop-ic"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg></div>
                        <div class="drop-t">$pickLabel</div>
                        <div class="drop-h">$pickHint</div>
                      </label>
                      <input type="file" id="f" accept="image/*" multiple onchange="onPick(this)">
                      <div class="qhdr" id="qhdr"><span class="qcnt" id="qcnt"></span><button class="qclr" onclick="clearQ()">$clearLabel</button></div>
                      <div class="qgrid" id="qgrid"></div>
                      <div class="prog" id="prog"><div class="pbar" id="pbar"></div></div>
                      <button class="btn" id="bf" onclick="sendFiles()" disabled>$sendLabel</button>
                    </div>
                    <div class="pane" id="p1">
                      <label class="lbl" for="u">$urlLabel</label>
                      <div class="uwrap">
                        <input class="uin" type="url" id="u" placeholder="https://..." oninput="onUrlInput()">
                        <button class="uclr" id="uclr" onclick="clearUrl()">&times;</button>
                      </div>
                      <div class="uprev" id="uprev"><span>$urlPreviewLabel</span><img id="uimg" alt=""></div>
                      <button class="btn" id="bu" onclick="sendUrl()" disabled>$sendLabel</button>
                    </div>
                    <div class="pane" id="p2">
                      <div id="tvbox"></div>
                    </div>
                  </div>
                </div>
                <div class="toast" id="toast"></div>
                <script>
                var MSG_DONE='$doneMsg',MSG_DEL='$deletedMsg',MSG_ERR='$errMsg',LBL_READY='$readyLabel',LBL_ACT='$activeLabel',LBL_EMPTY='$emptyTvLabel',LBL_CUSTOM='$customLabel';
                var queue=[],toastT=null,busy=false;
                function goTab(i){
                  for(var k=0;k<3;k++){
                    document.getElementById('t'+k).className='tab'+(k===i?' on':'');
                    document.getElementById('p'+k).className='pane'+(k===i?' on':'');
                  }
                  if(i===2)loadTv();
                }
                function showToast(m,ok){
                  var e=document.getElementById('toast');
                  e.innerText=m;
                  e.className='toast on '+(ok?'ok':'err');
                  clearTimeout(toastT);
                  toastT=setTimeout(function(){e.className='toast';},2600);
                }
                function onPick(inp){
                  var list=Array.prototype.slice.call(inp.files||[]);
                  for(var i=0;i<list.length;i++){
                    queue.push({file:list[i],url:URL.createObjectURL(list[i])});
                  }
                  inp.value='';
                  renderQ();
                }
                function removeQ(idx){
                  var it=queue.splice(idx,1)[0];
                  if(it&&it.url)URL.revokeObjectURL(it.url);
                  renderQ();
                }
                function clearQ(){
                  for(var i=0;i<queue.length;i++){if(queue[i].url)URL.revokeObjectURL(queue[i].url);}
                  queue=[];
                  renderQ();
                }
                function renderQ(){
                  var g=document.getElementById('qgrid'),h=document.getElementById('qhdr'),c=document.getElementById('qcnt'),b=document.getElementById('bf');
                  g.innerHTML='';
                  if(!queue.length){h.className='qhdr';b.disabled=true;return;}
                  h.className='qhdr on';
                  c.innerText=queue.length+' '+LBL_READY;
                  b.disabled=busy;
                  for(var i=0;i<queue.length;i++){
                    var d=document.createElement('div');d.className='qitem';
                    var im=document.createElement('img');im.src=queue[i].url;d.appendChild(im);
                    var rm=document.createElement('button');rm.className='qrm';rm.innerHTML='&times;';
                    rm.setAttribute('data-i',i);
                    rm.onclick=function(){removeQ(parseInt(this.getAttribute('data-i'),10));};
                    d.appendChild(rm);
                    g.appendChild(d);
                  }
                }
                function resizeOne(file){
                  return new Promise(function(resolve){
                    if(typeof createImageBitmap!=='function'){resolve(file);return;}
                    createImageBitmap(file).then(function(bmp){
                      var mw=1920,mh=1080,w=bmp.width,h=bmp.height,s=1;
                      if(w>mw||h>mh){s=Math.max(mw/w,mh/h);if(s>1)s=1;}
                      var cw=Math.round(w*s),ch=Math.round(h*s);
                      var c=document.createElement('canvas');c.width=cw;c.height=ch;
                      c.getContext('2d').drawImage(bmp,0,0,cw,ch);
                      c.toBlob(function(b){resolve(b||file);},'image/jpeg',0.92);
                    }).catch(function(){resolve(file);});
                  });
                }
                async function sendFiles(){
                  if(!queue.length||busy)return;
                  busy=true;
                  document.getElementById('bf').disabled=true;
                  var pr=document.getElementById('prog'),pb=document.getElementById('pbar');
                  pr.className='prog on';pb.style.width='6%';
                  var items=queue.slice(),ok=0;
                  for(var i=0;i<items.length;i++){
                    try{
                      var blob=await resizeOne(items[i].file);
                      var r=await fetch('/upload',{method:'POST',body:blob});
                      if(r.ok)ok++;
                    }catch(e){}
                    pb.style.width=Math.round(((i+1)/items.length)*100)+'%';
                  }
                  busy=false;
                  setTimeout(function(){pr.className='prog';pb.style.width='0%';},450);
                  clearQ();
                  if(ok>0){
                    showToast(MSG_DONE+(items.length>1?' ('+ok+'/'+items.length+')':''),true);
                    loadTv();
                  }else{
                    showToast(MSG_ERR,false);
                  }
                }
                function onUrlInput(){
                  var u=document.getElementById('u').value.trim();
                  document.getElementById('uclr').className='uclr'+(u?' on':'');
                  var valid=/^https?:\/\/.+/i.test(u);
                  document.getElementById('bu').disabled=!valid||busy;
                  var box=document.getElementById('uprev'),im=document.getElementById('uimg');
                  if(!valid){box.className='uprev';im.removeAttribute('src');return;}
                  im.onload=function(){box.className='uprev ok';};
                  im.onerror=function(){box.className='uprev';};
                  im.src=u;
                }
                function clearUrl(){
                  document.getElementById('u').value='';
                  onUrlInput();
                }
                function sendUrl(){
                  var u=document.getElementById('u').value.trim();
                  if(!u||busy)return;
                  busy=true;document.getElementById('bu').disabled=true;
                  fetch('/url',{method:'POST',body:u}).then(function(r){
                    busy=false;
                    return r.text().then(function(t){
                      if(r.ok){clearUrl();loadTv();showToast(t||MSG_DONE,true);}
                      else{onUrlInput();showToast(t||MSG_ERR,false);}
                    });
                  }).catch(function(){busy=false;onUrlInput();showToast(MSG_ERR,false);});
                }
                function applyTv(fn){
                  fetch('/apply',{method:'POST',body:fn}).then(function(r){
                    if(r.ok){showToast(MSG_DONE,true);loadTv();}else{showToast(MSG_ERR,false);}
                  }).catch(function(){showToast(MSG_ERR,false);});
                }
                function delTv(ev,fn){
                  ev.stopPropagation();
                  fetch('/delete',{method:'POST',body:fn}).then(function(r){
                    if(r.ok){showToast(MSG_DEL,true);loadTv();}else{showToast(MSG_ERR,false);}
                  }).catch(function(){showToast(MSG_ERR,false);});
                }
                function loadTv(){
                  fetch('/list').then(function(r){return r.json();}).then(function(d){
                    var items=(d&&d.items)||[],act=(d&&d.active)||'';
                    document.getElementById('tvcnt').innerText=items.length;
                    var box=document.getElementById('tvbox');
                    if(!items.length){box.innerHTML='<div class="empty">'+LBL_EMPTY+'</div>';return;}
                    var g=document.createElement('div');g.className='tvgrid';
                    for(var i=items.length-1;i>=0;i--){
                      (function(it){
                        var isA=it.file===act;
                        var el=document.createElement('div');
                        el.className='tvitem'+(isA?' act':'');
                        el.onclick=function(){applyTv(it.file);};
                        var im=document.createElement('img');
                        im.src='/thumb/'+encodeURIComponent(it.file);
                        im.loading='lazy';
                        el.appendChild(im);
                        if(isA){
                          var bd=document.createElement('div');bd.className='tvbadge';bd.innerText=LBL_ACT;el.appendChild(bd);
                        }
                        var dl=document.createElement('button');dl.className='tvdel';
                        dl.innerHTML='<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/></svg>';
                        dl.onclick=function(ev){delTv(ev,it.file);};
                        el.appendChild(dl);
                        var gr=document.createElement('div');gr.className='tvgrad';
                        var nm=document.createElement('span');nm.className='tvnm';
                        nm.innerText=LBL_CUSTOM+' #'+it.index;
                        gr.appendChild(nm);
                        el.appendChild(gr);
                        g.appendChild(el);
                      })(items[i]);
                    }
                    box.innerHTML='';box.appendChild(g);
                  }).catch(function(){});
                }
                loadTv();
                </script></body></html>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(html)
            out.flush()
            return null
        }
    }

    private fun decodeAndSaveNewCustomImage(context: Context, bytes: ByteArray): String? {
        if (bytes.size < 32) return null
        ensureMigrated(context)
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_CUSTOM_W && bounds.outHeight / (sample * 2) >= MAX_CUSTOM_H) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

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

            val dir = wallpapersDir(context)
            val seq = uploadSeq.getAndIncrement()
            val fileName = String.format(java.util.Locale.US, "wp_%013d_%03d.jpg", System.currentTimeMillis(), seq)
            val dest = File(dir, fileName)
            val tmp = File(dir, "$fileName.tmp")
            FileOutputStream(tmp).use { fos ->
                if (!decoded.compress(Bitmap.CompressFormat.JPEG, 92, fos)) return null
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            writeBitmapThumb(decoded, File(dir, "$THUMB_PREFIX$fileName"))
            invalidateCaches()
            rawCustomCache.put("$fileName|${dest.lastModified()}", decoded)
            "$CUSTOM_PREFIX$fileName"
        }.getOrNull()
    }

    private fun ensureThumbForFile(sourceFile: File, thumbFile: File) {
        if (thumbFile.isFile && thumbFile.length() > 128L) return
        if (!sourceFile.isFile) return
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= THUMB_W && bounds.outHeight / (sample * 2) >= THUMB_H) {
                sample *= 2
            }
            val bmp = BitmapFactory.decodeFile(
                sourceFile.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: return
            writeBitmapThumb(bmp, thumbFile)
            bmp.recycle()
        }
    }

    private fun writeBitmapThumb(src: Bitmap, thumbFile: File) {
        runCatching {
            val thumb = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
            val c = Canvas(thumb)
            val scale = max(THUMB_W.toFloat() / src.width.toFloat(), THUMB_H.toFloat() / src.height.toFloat())
            val dx = (THUMB_W - src.width * scale) * 0.5f
            val dy = (THUMB_H - src.height * scale) * 0.5f
            val m = Matrix().apply {
                postScale(scale, scale)
                postTranslate(dx, dy)
            }
            c.drawBitmap(src, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            FileOutputStream(thumbFile).use { fos ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
            thumb.recycle()
        }
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

    private fun httpStatusText(status: Int): String = when (status) {
        200 -> "200 OK"
        404 -> "404 Not Found"
        else -> "400 Bad Request"
    }

    private fun writePlainResponse(out: java.io.OutputStream, status: Int, text: String) {
        writeBytesResponse(out, status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }

    private fun writeJsonResponse(out: java.io.OutputStream, status: Int, json: String) {
        writeBytesResponse(out, status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8), cacheHeader = "no-store")
    }

    private fun writeBytesResponse(
        out: java.io.OutputStream,
        status: Int,
        contentType: String,
        bytes: ByteArray,
        cacheHeader: String? = null,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(httpStatusText(status)).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (cacheHeader != null) {
            sb.append("Cache-Control: ").append(cacheHeader).append("\r\n")
        }
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
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
        return fastExpNeg(d2)
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
        repeat(2) {
            val div = 2 * r + 1
            for (y in 0 until h) {
                val rowOffset = y * w
                var sumR = 0; var sumG = 0; var sumB = 0
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
            for (x in 0 until w) {
                var sumR = 0; var sumG = 0; var sumB = 0
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
