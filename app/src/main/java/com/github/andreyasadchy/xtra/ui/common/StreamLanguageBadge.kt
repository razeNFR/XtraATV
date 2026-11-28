package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * Lightweight mini-flag specification and vector [Drawable] for optional stream language badges.
 * Renders a crisp 14x10 dp rounded 2/3-stripe (or dot) flag icon beside the ISO language code.
 */
object StreamLanguageBadge {

    sealed interface FlagSpec {
        data class Vertical(val colors: IntArray) : FlagSpec
        data class Horizontal(val colors: IntArray) : FlagSpec
        data class Dot(val bgColor: Int, val dotColor: Int) : FlagSpec
    }

    fun flagSpec(code: String): FlagSpec? = when (code.uppercase()) {
        "FR" -> FlagSpec.Vertical(intArrayOf(0xFF0055A4.toInt(), 0xFFFFFFFF.toInt(), 0xFFEF4135.toInt()))
        "EN" -> FlagSpec.Horizontal(intArrayOf(0xFF012169.toInt(), 0xFFC8102E.toInt(), 0xFFFFFFFF.toInt()))
        "DE" -> FlagSpec.Horizontal(intArrayOf(0xFF111111.toInt(), 0xFFDD0000.toInt(), 0xFFFFCE00.toInt()))
        "ES" -> FlagSpec.Horizontal(intArrayOf(0xFFAA151B.toInt(), 0xFFF1BF00.toInt(), 0xFFAA151B.toInt()))
        "IT" -> FlagSpec.Vertical(intArrayOf(0xFF009246.toInt(), 0xFFFFFFFF.toInt(), 0xFFCE2B37.toInt()))
        "PT" -> FlagSpec.Vertical(intArrayOf(0xFF006600.toInt(), 0xFFFFCC00.toInt(), 0xFFFF0000.toInt()))
        "RU" -> FlagSpec.Horizontal(intArrayOf(0xFFFFFFFF.toInt(), 0xFF0039A6.toInt(), 0xFFD52B1E.toInt()))
        "UK" -> FlagSpec.Horizontal(intArrayOf(0xFF0057B7.toInt(), 0xFFFFD700.toInt()))
        "PL" -> FlagSpec.Horizontal(intArrayOf(0xFFFFFFFF.toInt(), 0xFFDC143C.toInt()))
        "NL" -> FlagSpec.Horizontal(intArrayOf(0xFFAE1C28.toInt(), 0xFFFFFFFF.toInt(), 0xFF21468B.toInt()))
        "TR" -> FlagSpec.Dot(bgColor = 0xFFE30A17.toInt(), dotColor = 0xFFFFFFFF.toInt())
        "JA" -> FlagSpec.Dot(bgColor = 0xFFFFFFFF.toInt(), dotColor = 0xFFBC002D.toInt())
        "KO" -> FlagSpec.Dot(bgColor = 0xFFFFFFFF.toInt(), dotColor = 0xFFCD2E3A.toInt())
        "ZH" -> FlagSpec.Dot(bgColor = 0xFFDE2910.toInt(), dotColor = 0xFFFFDE00.toInt())
        "AR" -> FlagSpec.Horizontal(intArrayOf(0xFF007A3D.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt()))
        "SV" -> FlagSpec.Horizontal(intArrayOf(0xFF006AA7.toInt(), 0xFFFECC00.toInt(), 0xFF006AA7.toInt()))
        "NO" -> FlagSpec.Horizontal(intArrayOf(0xFFBA0C2F.toInt(), 0xFF00205B.toInt(), 0xFFBA0C2F.toInt()))
        "FI" -> FlagSpec.Horizontal(intArrayOf(0xFFFFFFFF.toInt(), 0xFF003580.toInt(), 0xFFFFFFFF.toInt()))
        "DA" -> FlagSpec.Horizontal(intArrayOf(0xFFC8102E.toInt(), 0xFFFFFFFF.toInt(), 0xFFC8102E.toInt()))
        "CS" -> FlagSpec.Horizontal(intArrayOf(0xFFFFFFFF.toInt(), 0xFFD7141A.toInt()))
        "HU" -> FlagSpec.Horizontal(intArrayOf(0xFFCE2939.toInt(), 0xFFFFFFFF.toInt(), 0xFF477050.toInt()))
        "RO" -> FlagSpec.Vertical(intArrayOf(0xFF002B7F.toInt(), 0xFFFCD116.toInt(), 0xFFCE1126.toInt()))
        "EL" -> FlagSpec.Horizontal(intArrayOf(0xFF0D5EAF.toInt(), 0xFFFFFFFF.toInt(), 0xFF0D5EAF.toInt()))
        "TH" -> FlagSpec.Horizontal(intArrayOf(0xFFA51931.toInt(), 0xFF2D2A4A.toInt(), 0xFFA51931.toInt()))
        "VI" -> FlagSpec.Dot(bgColor = 0xFFDA251D.toInt(), dotColor = 0xFFFFFF00.toInt())
        "ID" -> FlagSpec.Horizontal(intArrayOf(0xFFCE1126.toInt(), 0xFFFFFFFF.toInt()))
        "BG" -> FlagSpec.Horizontal(intArrayOf(0xFFFFFFFF.toInt(), 0xFF00966E.toInt(), 0xFFD62612.toInt()))
        "SK" -> FlagSpec.Horizontal(intArrayOf(0xFFFFFFFF.toInt(), 0xFF0B4EA2.toInt(), 0xFFEE1C25.toInt()))
        else -> null
    }

    /** Returns a bounded 14x10 dp vector [Drawable] for Android Views (`TextView` compound drawable), or null. */
    fun createFlagDrawable(context: Context, code: String): Drawable? {
        val spec = flagSpec(code) ?: return null
        val density = context.resources.displayMetrics.density
        val w = (14f * density).roundToInt().coerceAtLeast(1)
        val h = (10f * density).roundToInt().coerceAtLeast(1)
        val radius = 2f * density
        return MiniFlagDrawable(spec, w, h, radius, density).apply {
            setBounds(0, 0, w, h)
        }
    }

    private class MiniFlagDrawable(
        private val spec: FlagSpec,
        private val w: Int,
        private val h: Int,
        private val radius: Float,
        density: Float,
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x38FFFFFF
            strokeWidth = 0.6f * density
        }
        private val clipPath = Path()
        private val rect = RectF()

        override fun getIntrinsicWidth(): Int = w
        override fun getIntrinsicHeight(): Int = h

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

            val save = canvas.save()
            canvas.clipPath(clipPath)
            when (spec) {
                is FlagSpec.Vertical -> {
                    val step = rect.width() / spec.colors.size
                    spec.colors.forEachIndexed { i, c ->
                        fillPaint.color = c
                        val left = rect.left + i * step
                        val right = if (i == spec.colors.lastIndex) rect.right else left + step
                        canvas.drawRect(left, rect.top, right, rect.bottom, fillPaint)
                    }
                }
                is FlagSpec.Horizontal -> {
                    val step = rect.height() / spec.colors.size
                    spec.colors.forEachIndexed { i, c ->
                        fillPaint.color = c
                        val top = rect.top + i * step
                        val bottom = if (i == spec.colors.lastIndex) rect.bottom else top + step
                        canvas.drawRect(rect.left, top, rect.right, bottom, fillPaint)
                    }
                }
                is FlagSpec.Dot -> {
                    fillPaint.color = spec.bgColor
                    canvas.drawRect(rect, fillPaint)
                    fillPaint.color = spec.dotColor
                    val r = minOf(rect.width(), rect.height()) * 0.28f
                    canvas.drawCircle(rect.centerX(), rect.centerY(), r, fillPaint)
                }
            }
            canvas.restoreToCount(save)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
