package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

/**
 * TV: dims the streams being watched (the player, its chat, the multi-stream, the small window), not the controls nor the
 * previews of the screens that pick a stream. Its level (10 to 90 %, 50 % at first) is kept in the settings; whether it
 * is on is kept only while the app runs, it is off again when the app starts.
 */
object TvDim {

    var active = false
        private set

    private val listeners = mutableSetOf<() -> Unit>()

    fun level(context: Context) = context.prefs().getInt(C.TV_DIM_LEVEL, 50).coerceIn(10, 90)

    fun setActive(active: Boolean) {
        if (this.active != active) {
            this.active = active
            notifyChanged()
        }
    }

    fun setLevel(context: Context, level: Int) {
        context.prefs().edit().putInt(C.TV_DIM_LEVEL, level.coerceIn(10, 90)).apply()
        notifyChanged()
    }

    /** Black over the view (its foreground, when it has no other one), as dark as the level while the dimming is on. */
    fun apply(view: View) {
        val color = Color.argb(if (active) level(view.context) * 255 / 100 else 0, 0, 0, 0)
        when (val current = view.foreground) {
            is DimDrawable -> current.color = color
            null -> if (active) view.foreground = DimDrawable(color)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    private class DimDrawable(color: Int) : ColorDrawable(color)
}
