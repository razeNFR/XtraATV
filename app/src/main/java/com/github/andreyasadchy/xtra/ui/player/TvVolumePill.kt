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
 * - Bright active focus ring + circular slider thumb + ‹ › D-pad arrows make it unmistakable that focus is inside the pill.
 * - Left / Right adjusts volume live in 5% steps without covering the stream.
 * - Long-press OK (or tapping the speaker icon) toggles Mute / Unmute instantly.
 * - Short OK, Down, Up, Back, or 3 seconds of inactivity closes the pill cleanly while restoring focus on Volume.
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
        private const val ARROW_PULSE_MS = 170L
    }

    private val context: Context = hostRoot.context
    private val density: Float = context.resources.displayMetrics.density
    private val scaledDensity: Float = context.resources.displayMetrics.scaledDensity

    private val pillWidthPx = (232f * density).roundToInt()
    private val bodyHeightPx = (44f * density).roundToInt()
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

    private val clearPulseRunnable = Runnable {
        pillView?.let {
            it.pulseSide = 0
            it.invalidate()
        }
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
        view.pulseSide = 0
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
            view.scaleX = 0.94f
            view.scaleY = 0.94f
            view.translationY += 4f * density
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(view.targetTranslationY)
                .setDuration(140L)
                .start()
        } else {
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
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

    private fun pulseArrow(side: Int) {
        pillView?.let {
            it.pulseSide = side
            it.invalidate()
        }
        hostRoot.removeCallbacks(clearPulseRunnable)
        hostRoot.postDelayed(clearPulseRunnable, ARROW_PULSE_MS)
    }

    fun dismiss(restoreAnchorFocus: Boolean = false, immediate: Boolean = false) {
        val view = pillView
        hostRoot.removeCallbacks(autoCloseRunnable)
        hostRoot.removeCallbacks(longPressMuteRunnable)
        hostRoot.removeCallbacks(clearPulseRunnable)
        centerDownSeen = false
        centerLongHandled = false
        backDownSeen = false
        val wasVisible = visibleState
        visibleState = false
        if (view == null) return
        view.pulseSide = 0
        view.animate().cancel()
        if (immediate || !wasVisible || view.visibility != View.VISIBLE) {
            view.visibility = View.GONE
            if (wasVisible) onClosed(restoreAnchorFocus)
            return
        }
        view.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
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
        hostRoot.removeCallbacks(clearPulseRunnable)
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
                    val isLeft = keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    val delta = if (isLeft) -STEP_PERCENT else STEP_PERCENT
                    val next = (currentPercent + delta).coerceIn(0, 100)
                    pulseArrow(if (isLeft) -1 else 1)
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
            elevation = 10f * density
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
            minOf(tlTop + 8f * density, anchorTop - 6f * density)
        } else {
            anchorTop - 8f * density
        }
        val targetY = (bottomTipY - totalHeightPx).coerceAtLeast(marginPx)
        view.translationX = clampedLeft
        view.targetTranslationY = targetY
        view.translationY = targetY
        view.pivotX = view.beakCenterX
        view.pivotY = totalHeightPx.toFloat()
        view.invalidate()
    }

    private inner class PillCanvasView(context: Context) : View(context) {
        var percent: Int = 100
        var beakCenterX: Float = pillWidthPx / 2f
        var targetTranslationY: Float = 0f
        var pulseSide: Int = 0

        private val bubblePath = Path()
        private val arrowPath = Path()
        private val trackRect = RectF()
        private val fillRect = RectF()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xF21B1A24.toInt()
        }
        private val haloStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.6f * density
        }
        private val focusStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.85f * density
            color = 0xF0FFFFFF.toInt()
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.75f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x42FFFFFF
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x5C000000
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val thumbCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFAFFFFFF.toInt()
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

            val accent = TvAccent.onDark
            val inset = 2.0f * density
            val left = inset
            val top = inset
            val right = w - inset
            val bottom = bodyH
            val r = (bottom - top) / 2f
            val bx = beakCenterX.coerceIn(left + r + beakHalfWidthPx, right - r - beakHalfWidthPx)
            val tipY = totalHeightPx - 1.2f * density

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

            haloStrokePaint.color = (accent and 0x00FFFFFF) or (0x4D shl 24)
            canvas.drawPath(bubblePath, haloStrokePaint)
            canvas.drawPath(bubblePath, fillPaint)
            canvas.drawPath(bubblePath, focusStrokePaint)

            val centerY = (top + bottom) / 2f
            val padH = 14f * density
            val iconSize = (20f * density).roundToInt()
            val iconLeft = padH.roundToInt()
            val iconTop = (centerY - iconSize / 2f).roundToInt()

            val activeIcon = if (percent <= 0) iconMuted else iconSpeaker
            activeIcon?.let { d ->
                val iconTint = if (percent <= 0) 0x9EFFFFFF.toInt() else accent
                DrawableCompat.setTint(d, iconTint)
                d.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                d.draw(canvas)
            }

            // Numeric readout on the right (fixed 28dp slot so bar width stays rock-steady)
            val textRight = w - padH
            val textSlotWidth = 28f * density
            val fm = textPaint.fontMetrics
            val textBaseline = centerY - (fm.ascent + fm.descent) / 2f
            canvas.drawText(percent.toString(), textRight, textBaseline, textPaint)

            // Left ‹ and Right › D-pad chevrons flanking the slider track so TV focus is self-evident
            val chevW = 4.2f * density
            val chevH = 4.8f * density
            val leftChevCx = iconLeft + iconSize + 9.5f * density
            val rightChevCx = textRight - textSlotWidth - 7.5f * density

            // Left chevron ‹
            arrowPaint.color = when {
                pulseSide < 0 -> Color.WHITE
                percent <= 0 -> 0x42FFFFFF
                else -> 0xCCFFFFFF.toInt()
            }
            arrowPaint.strokeWidth = (if (pulseSide < 0) 2.3f else 1.7f) * density
            arrowPath.reset()
            arrowPath.moveTo(leftChevCx + chevW * 0.5f, centerY - chevH)
            arrowPath.lineTo(leftChevCx - chevW * 0.5f, centerY)
            arrowPath.lineTo(leftChevCx + chevW * 0.5f, centerY + chevH)
            canvas.drawPath(arrowPath, arrowPaint)

            // Right chevron ›
            arrowPaint.color = when {
                pulseSide > 0 -> Color.WHITE
                percent >= 100 -> 0x42FFFFFF
                else -> 0xCCFFFFFF.toInt()
            }
            arrowPaint.strokeWidth = (if (pulseSide > 0) 2.3f else 1.7f) * density
            arrowPath.reset()
            arrowPath.moveTo(rightChevCx - chevW * 0.5f, centerY - chevH)
            arrowPath.lineTo(rightChevCx + chevW * 0.5f, centerY)
            arrowPath.lineTo(rightChevCx - chevW * 0.5f, centerY + chevH)
            canvas.drawPath(arrowPath, arrowPaint)

            // Rounded progress bar + crisp circular focus thumb between the ‹ and › chevrons
            val barLeft = leftChevCx + 9.5f * density
            val barRight = (rightChevCx - 9.5f * density).coerceAtLeast(barLeft + 24f * density)
            val barH = 6f * density
            val barR = barH / 2f
            trackRect.set(barLeft, centerY - barH / 2f, barRight, centerY + barH / 2f)
            canvas.drawRoundRect(trackRect, barR, barR, trackPaint)

            val ratio = (percent / 100f).coerceIn(0f, 1f)
            val thumbX = trackRect.left + trackRect.width() * ratio

            if (percent > 0) {
                progressPaint.color = accent
                val fillRight = thumbX.coerceIn(trackRect.left + barH, trackRect.right)
                fillRect.set(trackRect.left, trackRect.top, fillRight, trackRect.bottom)
                canvas.drawRoundRect(fillRect, barR, barR, progressPaint)
            }

            // Circular focus thumb (●)
            val thumbRadius = 5.6f * density
            canvas.drawCircle(thumbX, centerY, thumbRadius + 1.3f * density, thumbShadowPaint)
            thumbPaint.color = if (percent <= 0) 0xC8FFFFFF.toInt() else Color.WHITE
            canvas.drawCircle(thumbX, centerY, thumbRadius, thumbPaint)
            if (percent > 0) {
                thumbCorePaint.color = accent
                canvas.drawCircle(thumbX, centerY, 2.2f * density, thumbCorePaint)
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
                        val snapped = (frac * 100f).roundToInt()
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
