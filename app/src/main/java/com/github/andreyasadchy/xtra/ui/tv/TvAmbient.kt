package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

/**
 * TV: background of the screens, a soft glow of the ambiance color (Xtra TV settings) in the corners
 * over the color of the theme. Drawn once, it costs nothing while browsing.
 */
object TvAmbient {

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

    fun color(context: Context): Int? {
        val key = context.prefs().getString(C.TV_AMBIENT_COLOR, "violet") ?: "violet"
        return COLORS[key]
    }

    /** Color of a value of the setting (the dot in front of it in the list), null for "none". */
    fun colorOf(key: String?): Int? = COLORS[key]

    fun background(context: Context, base: Int): Drawable {
        val color = color(context) ?: return ColorDrawable(base)
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

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
