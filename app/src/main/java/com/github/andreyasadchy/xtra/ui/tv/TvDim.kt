package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

/**
 * TV: dims and/or warms (Night Shift) the streams being watched (the player, its chat, the multi-stream, the small window),
 * not the controls nor the previews of the screens that pick a stream.
 * Dimming level (0 to 90 %, 50 % at first) and warm Night Shift level (0 to 80 %, 0 % at first) are kept in settings;
 * whether the filter is active is kept while the app runs and starts off when the app launches.
 */
object TvDim {

    var active = false
        private set

    private val listeners = mutableSetOf<() -> Unit>()

    fun level(context: Context): Int = context.prefs().getInt(C.TV_DIM_LEVEL, 50).coerceIn(0, 90)

    fun warmLevel(context: Context): Int = context.prefs().getInt(C.TV_DIM_WARM_LEVEL, 0).coerceIn(0, 80)

    fun hasAnyFilter(context: Context): Boolean = level(context) > 0 || warmLevel(context) > 0

    fun isEffectivelyOn(context: Context): Boolean = active && hasAnyFilter(context)

    fun setActive(active: Boolean) {
        if (this.active != active) {
            this.active = active
            notifyChanged()
        }
    }

    /** Short-press toggle on the HUD button: ensures at least 50 % dimming if both sliders were set to 0 %. */
    fun toggle(context: Context) {
        if (!active) {
            if (!hasAnyFilter(context)) {
                context.prefs().edit().putInt(C.TV_DIM_LEVEL, 50).apply()
            }
            active = true
        } else {
            active = false
        }
        notifyChanged()
    }

    fun setLevel(context: Context, level: Int) {
        context.prefs().edit().putInt(C.TV_DIM_LEVEL, level.coerceIn(0, 90)).apply()
        notifyChanged()
    }

    fun setWarmLevel(context: Context, warm: Int) {
        context.prefs().edit().putInt(C.TV_DIM_WARM_LEVEL, warm.coerceIn(0, 80)).apply()
        notifyChanged()
    }

    /** Applies the warm Night Shift tint first, then black dimming over it so deep blacks stay dark at night. */
    fun apply(view: View) {
        val context = view.context
        val dAlpha = if (active) level(context) * 255 / 100 else 0
        val wAlpha = if (active) warmLevel(context) * 135 / 100 else 0
        val visible = dAlpha > 0 || wAlpha > 0
        when (val current = view.foreground) {
            is FilterDrawable -> current.update(dAlpha, wAlpha)
            null -> if (visible) view.foreground = FilterDrawable(dAlpha, wAlpha)
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

    private class FilterDrawable(
        private var dimAlpha: Int,
        private var warmAlpha: Int,
    ) : Drawable() {
        private val paint = Paint()

        fun update(newDimAlpha: Int, newWarmAlpha: Int) {
            if (dimAlpha != newDimAlpha || warmAlpha != newWarmAlpha) {
                dimAlpha = newDimAlpha
                warmAlpha = newWarmAlpha
                invalidateSelf()
            }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            if (warmAlpha > 0) {
                paint.color = Color.argb(warmAlpha, 255, 138, 20)
                canvas.drawRect(b, paint)
            }
            if (dimAlpha > 0) {
                paint.color = Color.argb(dimAlpha, 0, 0, 0)
                canvas.drawRect(b, paint)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
