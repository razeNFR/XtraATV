package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color

/**
 * TV: the color of accent of the app, the ambiance color chosen in the Xtra TV settings (violet at first): loading
 * circles, timeline, focused buttons and rows, tabs, sliders, the multi-stream row. With "None (plain background)" the
 * colors of before (white, violet of Twitch). Read when the app starts (changing the ambiance color restarts it).
 *
 * With the "dynamic" ambiance it follows the picture focused last, together with the glow of the background
 * ([TvAmbient] updates both at once): the Compose screens that read it are drawn again by themselves, the screens
 * made of views read it when they are made.
 */
object TvAccent {

    /** A snapshot state: the Compose screens follow its changes. */
    private val state = androidx.compose.runtime.mutableStateOf<Int?>(null)

    /** The ambiance color, null when none is chosen. */
    val current: Int?
        get() = state.value

    fun init(context: Context) {
        state.value = TvAmbient.color(context)
    }

    /** The dynamic ambiance changed color. */
    fun update(color: Int?) {
        state.value = color
    }

    /**
     * The accent for texts and icons over the dark screens: the same hue, made lighter until it reads well (a deep blue
     * or violet taken from a picture is too dark for a text).
     */
    val onDark: Int
        get() {
            val base = color
            var amount = 0f
            var result = base
            while (contrast(result, 0xFF1F1E26.toInt()) < 4.5f && amount < 0.9f) {
                amount += 0.08f
                result = lighten(base, amount)
            }
            return result
        }

    private fun lighten(color: Int, amount: Float): Int {
        fun mix(c: Int) = (c + (255 - c) * amount).toInt().coerceIn(0, 255)
        return Color.argb(255, mix(Color.red(color)), mix(Color.green(color)), mix(Color.blue(color)))
    }

    /** The accent, over the dark screens. */
    val color: Int
        get() = current ?: 0xFF9147FF.toInt()

    /** The accent for icons and texts on white (a focused button or row): a darker shade, always readable. */
    val onLight: Int
        get() = current?.let { readableOnLight(it) } ?: 0xFF111014.toInt()

    /** Text over the accent (a badge filled with it): black over a light shade, else white. */
    val onAccent: Int
        get() = if (Color.luminance(color) > 0.45f) 0xFF111014.toInt() else Color.WHITE

    /** The accent darkened until it reads well on the white of a focused item (light shades too: yellow, mint...). */
    private fun readableOnLight(color: Int): Int {
        var amount = 0.35f
        var result = darken(color, amount)
        while (contrast(result, 0xFFF2F2F7.toInt()) < 4.5f && amount < 0.9f) {
            amount += 0.05f
            result = darken(color, amount)
        }
        return result
    }

    private fun contrast(a: Int, b: Int): Float {
        val la = Color.luminance(a) + 0.05f
        val lb = Color.luminance(b) + 0.05f
        return maxOf(la, lb) / minOf(la, lb)
    }

    val colorList: ColorStateList
        get() = ColorStateList.valueOf(color)

    private fun darken(color: Int, amount: Float): Int {
        val keep = 1f - amount
        return Color.argb(255, (Color.red(color) * keep).toInt(), (Color.green(color) * keep).toInt(), (Color.blue(color) * keep).toInt())
    }
}
