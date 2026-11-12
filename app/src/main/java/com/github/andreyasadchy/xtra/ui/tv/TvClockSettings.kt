package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * TV player clock configuration (#61): hidden, shown with HUD controls only, or always shown over the stream
 * with customizable corner position, size, opacity, and subtle pill background.
 */
object TvClockSettings {

    const val MODE_OFF = 0
    const val MODE_CONTROLS = 1
    const val MODE_ALWAYS = 2

    const val POS_TOP_RIGHT = 0
    const val POS_TOP_LEFT = 1
    const val POS_BOTTOM_RIGHT = 2
    const val POS_BOTTOM_LEFT = 3

    const val SIZE_SMALL = 0
    const val SIZE_NORMAL = 1
    const val SIZE_LARGE = 2

    val changes = MutableStateFlow(0L)

    fun ensureMigrated(context: Context) {
        val prefs = context.prefs()
        if (!prefs.contains(C.TV_CLOCK_MODE)) {
            val legacyEnabled = runCatching { prefs.getBoolean(C.TV_PLAYER_CLOCK, true) }.getOrDefault(true)
            prefs.edit {
                putInt(C.TV_CLOCK_MODE, if (legacyEnabled) MODE_CONTROLS else MODE_OFF)
            }
        }
    }

    fun mode(context: Context): Int {
        ensureMigrated(context)
        return context.prefs().getInt(C.TV_CLOCK_MODE, MODE_CONTROLS).coerceIn(MODE_OFF, MODE_ALWAYS)
    }

    fun position(context: Context): Int =
        context.prefs().getInt(C.TV_CLOCK_POSITION, POS_TOP_RIGHT).coerceIn(POS_TOP_RIGHT, POS_BOTTOM_LEFT)

    fun size(context: Context): Int =
        context.prefs().getInt(C.TV_CLOCK_SIZE, SIZE_NORMAL).coerceIn(SIZE_SMALL, SIZE_LARGE)

    fun sizeSp(context: Context): Float = when (size(context)) {
        SIZE_SMALL -> 16f
        SIZE_LARGE -> 28f
        else -> 22f
    }

    fun opacity(context: Context): Int =
        context.prefs().getInt(C.TV_CLOCK_OPACITY, 75).coerceIn(20, 100)

    fun hasBackground(context: Context): Boolean =
        context.prefs().getBoolean(C.TV_CLOCK_BG, false)

    fun summary(context: Context): String {
        return when (mode(context)) {
            MODE_OFF -> context.getString(R.string.tv_clock_mode_off)
            MODE_CONTROLS -> context.getString(R.string.tv_clock_mode_controls)
            else -> {
                val posLabel = when (position(context)) {
                    POS_TOP_LEFT -> context.getString(R.string.tv_clock_pos_top_left)
                    POS_BOTTOM_RIGHT -> context.getString(R.string.tv_clock_pos_bottom_right)
                    POS_BOTTOM_LEFT -> context.getString(R.string.tv_clock_pos_bottom_left)
                    else -> context.getString(R.string.tv_clock_pos_top_right)
                }
                "${context.getString(R.string.tv_clock_mode_always)} · $posLabel · ${opacity(context)}%"
            }
        }
    }

    fun apply(
        context: Context,
        hudClock: View?,
        alwaysClock: TextClock?,
        isMaximized: Boolean,
        fullHudVisible: Boolean,
    ) {
        val currentMode = mode(context)
        hudClock?.visibility = if (currentMode >= MODE_CONTROLS) View.VISIBLE else View.GONE
        val clock = alwaysClock ?: return
        val showAlways = isMaximized && currentMode == MODE_ALWAYS && !fullHudVisible
        if (!showAlways) {
            clock.visibility = View.GONE
            return
        }
        val density = context.resources.displayMetrics.density
        val hMargin = context.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        val vMargin = context.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
        val pos = position(context)
        val lp = (clock.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = when (pos) {
            POS_TOP_LEFT -> Gravity.TOP or Gravity.START
            POS_BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            POS_BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            else -> Gravity.TOP or Gravity.END
        }
        lp.marginStart = if (pos == POS_TOP_LEFT || pos == POS_BOTTOM_LEFT) hMargin else 0
        lp.marginEnd = if (pos == POS_TOP_RIGHT || pos == POS_BOTTOM_RIGHT) hMargin else 0
        lp.topMargin = if (pos == POS_TOP_RIGHT || pos == POS_TOP_LEFT) vMargin else 0
        lp.bottomMargin = if (pos == POS_BOTTOM_RIGHT || pos == POS_BOTTOM_LEFT) vMargin else 0
        clock.layoutParams = lp
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp(context))
        clock.alpha = opacity(context) / 100f
        if (hasBackground(context)) {
            val padH = (10f * density).toInt()
            val padV = (4f * density).toInt()
            clock.setPadding(padH, padV, padH, padV)
            clock.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(Color.argb(136, 0, 0, 0))
            }
        } else {
            clock.setPadding(0, 0, 0, 0)
            clock.background = null
        }
        clock.visibility = View.VISIBLE
    }

    fun showPanel(context: Context, onChanged: () -> Unit = {}) {
        ensureMigrated(context)
        val prefs = context.prefs()
        val modeOptions = listOf(
            context.getString(R.string.tv_clock_mode_off),
            context.getString(R.string.tv_clock_mode_controls),
            context.getString(R.string.tv_clock_mode_always),
        )
        val posOptions = listOf(
            context.getString(R.string.tv_clock_pos_top_right),
            context.getString(R.string.tv_clock_pos_top_left),
            context.getString(R.string.tv_clock_pos_bottom_right),
            context.getString(R.string.tv_clock_pos_bottom_left),
        )
        val sizeOptions = listOf(
            context.getString(R.string.tv_clock_size_small),
            context.getString(R.string.tv_clock_size_normal),
            context.getString(R.string.tv_clock_size_large),
        )
        val bgOptions = listOf(
            context.getString(R.string.tv_clock_bg_off),
            context.getString(R.string.tv_clock_bg_on),
        )
        fun notifyChange() {
            changes.value = System.currentTimeMillis()
            onChanged()
        }
        TvDialogs.show(context) {
            val firstFocus = remember { FocusRequester() }
            var currentMode by remember { mutableIntStateOf(mode(context)) }
            var resetTick by remember { mutableIntStateOf(0) }
            SidePanel(
                title = context.getString(R.string.tv_player_clock),
                subtitle = context.getString(R.string.tv_player_clock_summary),
                width = 480.dp,
                scrollable = true,
            ) {
                key(resetTick) {
                    SelectorRow(
                        label = context.getString(R.string.tv_clock_mode),
                        options = modeOptions,
                        selected = currentMode,
                        modifier = Modifier.focusRequester(firstFocus),
                    ) { idx ->
                        currentMode = idx
                        prefs.edit { putInt(C.TV_CLOCK_MODE, idx) }
                        notifyChange()
                    }
                    if (currentMode == MODE_ALWAYS) {
                        SelectorRow(
                            label = context.getString(R.string.tv_clock_position),
                            options = posOptions,
                            selected = position(context),
                        ) { idx ->
                            prefs.edit { putInt(C.TV_CLOCK_POSITION, idx) }
                            notifyChange()
                        }
                        SelectorRow(
                            label = context.getString(R.string.tv_clock_size),
                            options = sizeOptions,
                            selected = size(context),
                        ) { idx ->
                            prefs.edit { putInt(C.TV_CLOCK_SIZE, idx) }
                            notifyChange()
                        }
                        SliderRow(
                            label = context.getString(R.string.tv_clock_opacity),
                            value = opacity(context),
                            range = 20..100,
                            step = 5,
                            valueText = { "$it%" },
                        ) { v ->
                            prefs.edit { putInt(C.TV_CLOCK_OPACITY, v) }
                            notifyChange()
                        }
                        SelectorRow(
                            label = context.getString(R.string.tv_clock_background),
                            options = bgOptions,
                            selected = if (hasBackground(context)) 1 else 0,
                        ) { idx ->
                            prefs.edit { putBoolean(C.TV_CLOCK_BG, idx == 1) }
                            notifyChange()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ButtonRow(label = context.getString(R.string.tv_reset)) {
                        prefs.edit {
                            putInt(C.TV_CLOCK_MODE, MODE_CONTROLS)
                            putInt(C.TV_CLOCK_POSITION, POS_TOP_RIGHT)
                            putInt(C.TV_CLOCK_SIZE, SIZE_NORMAL)
                            putInt(C.TV_CLOCK_OPACITY, 75)
                            putBoolean(C.TV_CLOCK_BG, false)
                        }
                        currentMode = MODE_CONTROLS
                        resetTick++
                        notifyChange()
                    }
                }
            }
            LaunchedEffect(resetTick) {
                firstFocus.requestFocus()
            }
        }
    }
}
