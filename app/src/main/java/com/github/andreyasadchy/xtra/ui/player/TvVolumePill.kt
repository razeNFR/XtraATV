package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.tv.TvAccent
import kotlin.math.roundToInt

/**
 * Compact smoked-glass volume pill anchored directly above the TV player's Volume button:
 * - Left / Right adjusts volume live in 5% steps without covering the stream.
 * - Long-press OK (or tapping the speaker icon) toggles Mute / Unmute instantly.
 * - Short OK, Down, Up, Back, or 3 seconds of inactivity closes the pill cleanly while keeping focus on Volume.
 */
class TvVolumePill(
    private val hostRoot: RelativeLayout,
    private val onVolumeChanged: (newPercent: Int) -> Unit,
    private val onToggleMute: () -> Int,
    private val onKeepHudAlive: () -> Unit,
    private val onClosed: (restoreAnchorFocus: Boolean) -> Unit,
) {

    companion object {
        private const val AUTO_CLOSE_MS = 3_000L
        private const val STEP_PERCENT = 5
    }

    private val context: Context = hostRoot.context
    private val density: Float = context.resources.displayMetrics.density
    private val scaledDensity: Float = context.resources.displayMetrics.scaledDensity

    private val pillWidthPx = (194f * density).roundToInt()
    private val bodyHeightPx = (42f * density).roundToInt()
    private val beakHeightPx = (7f * density).roundToInt()
    private val beakHalfWidthPx = 6.5f * density
    private val totalHeightPx = bodyHeightPx + beakHeightPx

    private var pillView: PillCanvasView? = null
    private var anchorView: View? = null
    private var timelineView: View? = null
    private var currentPercent: Int = 100
    private var visibleState: Boolean = false

    private var centerDownSeen = false
    private var centerLongHandled = false
    private var backDownSeen = false

    private val autoCloseRunnable = Runnable {
        dismiss(restoreAnchorFocus = true)
    }

    private val longPressMuteRunnable = Runnable {
        if (!visibleState || !centerDownSeen || centerLongHandled) return@Runnable
        centerLongHandled = true
        val updated = onToggleMute().coerceIn(0, 100)
        setVolumePercent(updated)
        scheduleAutoClose()
        onKeepHudAlive()
    }

    val isShowing: Boolean
        get() = visibleState && pillView?.visibility == View.VISIBLE

    fun show(anchor: View, timeline: View?, initialPercent: Int) {
        anchorView = anchor
        timelineView = timeline
        currentPercent = initialPercent.coerceIn(0, 100)
        centerDownSeen = false
        centerLongHandled = false
        backDownSeen = false

        val view = ensureView()
        view.beakCenterX = pillWidthPx / 2f
        view.percent = currentPercent
        view.invalidate()

        visibleState = true
        positionAboveAnchor(view, anchor, timeline)
        anchor.post {
            if (visibleState && pillView === view && anchor.isAttachedToWindow) {
                positionAboveAnchor(view, anchor, timeline)
            }
        }

        view.animate().cancel()
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.translationY += 4f * density
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .translationY(view.targetTranslationY)
                .setDuration(140L)
                .start()
        } else {
            view.alpha = 1f
            view.translationY = view.targetTranslationY
        }

        scheduleAutoClose()
        onKeepHudAlive()
    }

    fun setVolumePercent(percent: Int) {
        currentPercent = percent.coerceIn(0, 100)
        pillView?.let {
            it.percent = currentPercent
            it.invalidate()
        }
    }

    fun dismiss(restoreAnchorFocus: Boolean = false, immediate: Boolean = false) {
        val view = pillView
        hostRoot.removeCallbacks(autoCloseRunnable)
        hostRoot.removeCallbacks(longPressMuteRunnable)
        centerDownSeen = false
        centerLongHandled = false
        backDownSeen = false
        val wasVisible = visibleState
        visibleState = false
        if (view == null) return
        view.animate().cancel()
        if (immediate || !wasVisible || view.visibility != View.VISIBLE) {
            view.visibility = View.GONE
            if (wasVisible) onClosed(restoreAnchorFocus)
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(120L)
            .withEndAction {
                if (!visibleState) {
                    view.visibility = View.GONE
                }
            }
            .start()
        onClosed(restoreAnchorFocus)
    }

    fun destroy() {
        hostRoot.removeCallbacks(autoCloseRunnable)
        hostRoot.removeCallbacks(longPressMuteRunnable)
        visibleState = false
        pillView?.let { v ->
            v.animate().cancel()
            (v.parent as? ViewGroup)?.removeView(v)
        }
        pillView = null
        anchorView = null
        timelineView = null
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isShowing) return false
        val keyCode = event.keyCode
        val isSelect = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        when {
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE -> return false

            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -STEP_PERCENT else STEP_PERCENT
                    val next = (currentPercent + delta).coerceIn(0, 100)
                    if (next != currentPercent) {
                        setVolumePercent(next)
                        onVolumeChanged(next)
                    }
                    scheduleAutoClose()
                    onKeepHudAlive()
                }
                return true
            }

            isSelect -> {
                val longPressMs = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(350L, 550L)
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            centerDownSeen = true
                            centerLongHandled = false
                            hostRoot.removeCallbacks(longPressMuteRunnable)
                            hostRoot.postDelayed(longPressMuteRunnable, longPressMs)
                        } else if (centerDownSeen && !centerLongHandled && (event.repeatCount >= 1 || event.isLongPress)) {
                            hostRoot.removeCallbacks(longPressMuteRunnable)
                            centerLongHandled = true
                            val updated = onToggleMute().coerceIn(0, 100)
                            setVolumePercent(updated)
                            scheduleAutoClose()
                            onKeepHudAlive()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        hostRoot.removeCallbacks(longPressMuteRunnable)
                        val wasDown = centerDownSeen
                        val wasLong = centerLongHandled
                        centerDownSeen = false
                        centerLongHandled = false
                        if (wasDown && !wasLong && !event.isCanceled) {
                            dismiss(restoreAnchorFocus = true)
                        }
                    }
                    else -> {
                        hostRoot.removeCallbacks(longPressMuteRunnable)
                        centerDownSeen = false
                        centerLongHandled = false
                    }
                }
                return true
            }

            keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    dismiss(restoreAnchorFocus = true)
                }
                return true
            }

            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            backDownSeen = true
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        val wasDown = backDownSeen
                        backDownSeen = false
                        if (wasDown && !event.isCanceled) {
                            dismiss(restoreAnchorFocus = true)
                        }
                    }
                    else -> backDownSeen = false
                }
                return true
            }

            else -> return false
        }
    }

    private fun scheduleAutoClose() {
        hostRoot.removeCallbacks(autoCloseRunnable)
        hostRoot.postDelayed(autoCloseRunnable, AUTO_CLOSE_MS)
    }

    private fun ensureView(): PillCanvasView {
        pillView?.let { existing ->
            if (existing.parent === hostRoot) return existing
            (existing.parent as? ViewGroup)?.removeView(existing)
        }
        val created = PillCanvasView(context).apply {
            layoutParams = RelativeLayout.LayoutParams(pillWidthPx, totalHeightPx)
            elevation = 8f * density
            visibility = View.GONE
            isFocusable = false
            isClickable = true
        }
        hostRoot.addView(created)
        pillView = created
        return created
    }

    private fun positionAboveAnchor(view: PillCanvasView, anchor: View, timeline: View?) {
        val rootLoc = IntArray(2)
        val anchorLoc = IntArray(2)
        hostRoot.getLocationInWindow(rootLoc)
        anchor.getLocationInWindow(anchorLoc)

        val marginPx = 12f * density
        val anchorCenterX = (anchorLoc[0] - rootLoc[0]) + anchor.width / 2f
        val rawLeft = anchorCenterX - pillWidthPx / 2f
        val maxLeft = (hostRoot.width - pillWidthPx - marginPx).coerceAtLeast(marginPx)
        val clampedLeft = rawLeft.coerceIn(marginPx, maxLeft)

        val cornerInset = 24f * density
        view.beakCenterX = (anchorCenterX - clampedLeft).coerceIn(cornerInset, pillWidthPx - cornerInset)

        val anchorTop = (anchorLoc[1] - rootLoc[1]).toFloat()
        val bottomTipY = if (timeline != null && timeline.visibility == View.VISIBLE && timeline.height > 0) {
            val tlLoc = IntArray(2)
            timeline.getLocationInWindow(tlLoc)
            val tlTop = (tlLoc[1] - rootLoc[1]).toFloat()
            // Keep the pill body cleanly above the horizontal progress line with the beak pointing down to Volume
            minOf(tlTop + 8f * density, anchorTop - 6f * density)
        } else {
            anchorTop - 8f * density
        }
        val targetY = (bottomTipY - totalHeightPx).coerceAtLeast(marginPx)
        view.translationX = clampedLeft
        view.targetTranslationY = targetY
        view.translationY = targetY
        view.invalidate()
    }

    private inner class PillCanvasView(context: Context) : View(context) {
        var percent: Int = 100
        var beakCenterX: Float = pillWidthPx / 2f
        var targetTranslationY: Float = 0f

        private val bubblePath = Path()
        private val chevronPath = Path()
        private val trackRect = RectF()
        private val fillRect = RectF()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xEC222228.toInt()
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = 0x33FFFFFF
        }
        private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.35f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0x99FFFFFF.toInt()
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x3DFFFFFF
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF5FFFFFF.toInt()
            textSize = 14.5f * scaledDensity
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }

        private val iconSpeaker: Drawable? = ContextCompat.getDrawable(context, R.drawable.tv_ic_volume)?.mutate()
        private val iconMuted: Drawable? = ContextCompat.getDrawable(context, R.drawable.baseline_volume_off_black_24)?.mutate()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val bodyH = bodyHeightPx.toFloat()
            if (w <= 0f || bodyH <= 0f) return

            val inset = 0.75f * density
            val left = inset
            val top = inset
            val right = w - inset
            val bottom = bodyH
            val r = (bottom - top) / 2f
            val bx = beakCenterX.coerceIn(left + r + beakHalfWidthPx, right - r - beakHalfWidthPx)
            val tipY = totalHeightPx - inset

            // Seamless stadium capsule + downward pointer beak
            bubblePath.reset()
            bubblePath.moveTo(left + r, top)
            bubblePath.lineTo(right - r, top)
            bubblePath.arcTo(RectF(right - 2f * r, top, right, bottom), -90f, 180f, false)
            bubblePath.lineTo(bx + beakHalfWidthPx, bottom)
            bubblePath.lineTo(bx, tipY)
            bubblePath.lineTo(bx - beakHalfWidthPx, bottom)
            bubblePath.lineTo(left + r, bottom)
            bubblePath.arcTo(RectF(left, top, left + 2f * r, bottom), 90f, 180f, false)
            bubblePath.close()

            canvas.drawPath(bubblePath, fillPaint)
            canvas.drawPath(bubblePath, strokePaint)

            // Subtle downward chevron cue inside the beak base
            val chevHalfW = 2.6f * density
            val chevTopY = bottom - 1.4f * density
            val chevTipY = bottom + 1.6f * density
            chevronPath.reset()
            chevronPath.moveTo(bx - chevHalfW, chevTopY)
            chevronPath.lineTo(bx, chevTipY)
            chevronPath.lineTo(bx + chevHalfW, chevTopY)
            canvas.drawPath(chevronPath, chevronPaint)

            val centerY = (top + bottom) / 2f
            val padH = 14f * density
            val iconSize = (20f * density).roundToInt()
            val iconLeft = padH.roundToInt()
            val iconTop = (centerY - iconSize / 2f).roundToInt()

            val activeIcon = if (percent <= 0) iconMuted else iconSpeaker
            activeIcon?.let { d ->
                DrawableCompat.setTint(d, if (percent <= 0) 0xA6FFFFFF.toInt() else 0xF5FFFFFF.toInt())
                d.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                d.draw(canvas)
            }

            // Numeric readout on the right (fixed 28dp slot so bar width stays rock-steady)
            val textRight = w - padH
            val textSlotWidth = 28f * density
            val fm = textPaint.fontMetrics
            val textBaseline = centerY - (fm.ascent + fm.descent) / 2f
            canvas.drawText(percent.toString(), textRight, textBaseline, textPaint)

            // Rounded progress bar in the center
            val barLeft = iconLeft + iconSize + 11f * density
            val barRight = (textRight - textSlotWidth - 8f * density).coerceAtLeast(barLeft + 24f * density)
            val barH = 6f * density
            val barR = barH / 2f
            trackRect.set(barLeft, centerY - barH / 2f, barRight, centerY + barH / 2f)
            canvas.drawRoundRect(trackRect, barR, barR, trackPaint)

            if (percent > 0) {
                progressPaint.color = TvAccent.onDark
                val ratio = percent / 100f
                val fillW = (trackRect.width() * ratio).coerceAtLeast(barH)
                fillRect.set(trackRect.left, trackRect.top, (trackRect.left + fillW).coerceAtMost(trackRect.right), trackRect.bottom)
                canvas.drawRoundRect(fillRect, barR, barR, progressPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!visibleState) return false
            val padH = 14f * density
            val iconZoneRight = padH + 26f * density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && event.x <= iconZoneRight) {
                        val updated = onToggleMute().coerceIn(0, 100)
                        setVolumePercent(updated)
                    } else if (trackRect.width() > 0f && event.x >= iconZoneRight) {
                        val frac = ((event.x - trackRect.left) / trackRect.width()).coerceIn(0f, 1f)
                        val snapped = ((frac * 100f).roundToInt())
                        if (snapped != currentPercent) {
                            setVolumePercent(snapped)
                            onVolumeChanged(snapped)
                        }
                    }
                    scheduleAutoClose()
                    onKeepHudAlive()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
