package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color

/**
 * TV: the color of accent of the app, the ambiance color chosen in the Xtra TV settings (violet at first): loading
 * circles, timeline, focused buttons and rows, tabs, sliders, the multi-stream row. With "None (plain background)" the
 * colors of before (white, violet of Twitch). Read when the app starts (changing the ambiance color restarts it).
 */
object TvAccent {

    /** The ambiance color, null when none is chosen. */
    @Volatile
    var current: Int? = null
        private set

    fun init(context: Context) {
        current = TvAmbient.color(context)
    }

    /** The accent, over the dark screens. */
    val color: Int
        get() = current ?: 0xFF9147FF.toInt()

    /** The accent for icons and texts on white (a focused button or row): a darker shade, always readable. */
    val onLight: Int
        get() = current?.let { darken(it, 0.35f) } ?: 0xFF111014.toInt()

    val colorList: ColorStateList
        get() = ColorStateList.valueOf(color)

    private fun darken(color: Int, amount: Float): Int {
        val keep = 1f - amount
        return Color.argb(255, (Color.red(color) * keep).toInt(), (Color.green(color) * keep).toInt(), (Color.blue(color) * keep).toInt())
    }
}
