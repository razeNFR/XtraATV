package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import androidx.annotation.Keep
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import kotlin.math.max

/**
 * Drop-in [DefaultTimeBar] subclass that highlights muted VOD audio segments (DMCA / copyright mutes)
 * in red/coral directly over the seek bar — matching Twitch's web player while keeping chapter markers
 * and the scrubber thumb cleanly visible.
 */
@Keep
@OptIn(UnstableApi::class)
class XtraTimeBar : DefaultTimeBar {

    private var barHeightPx: Int = 0
    private var barGravity: Int = 0
    private var scrubberEnabledSizePx: Int = 0
    private var scrubberDisabledSizePx: Int = 0
    private var scrubberDraggedSizePx: Int = 0
    private var scrubberPaddingPx: Int = 0
    private var minSegmentWidthPx: Float = 0f

    private var totalDurationMs: Long = 0L
    private var currentPosMs: Long = 0L
    private var scrubberShown: Boolean = true
    private var mutedSegments: List<Pair<Long, Long>> = emptyList()

    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xDCFF4D4D.toInt()
    }
    private val segmentRect = RectF()
    private val scrubberClipPath = Path()

    constructor(context: Context) : super(context) {
        initBarMetrics(context, null, 0)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initBarMetrics(context, attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initBarMetrics(context, attrs, defStyleAttr)
    }

    private fun initBarMetrics(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        val density = context.resources.displayMetrics.density
        val defaultBarH = (4f * density + 0.5f).toInt()
        val defaultEnabled = (12f * density + 0.5f).toInt()
        val defaultDisabled = 0
        val defaultDragged = (16f * density + 0.5f).toInt()
        minSegmentWidthPx = max(3f * density, 3f)

        if (attrs != null) {
            val a = context.theme.obtainStyledAttributes(
                attrs,
                androidx.media3.ui.R.styleable.DefaultTimeBar,
                defStyleAttr,
                0,
            )
            try {
                barHeightPx = a.getDimensionPixelSize(
                    androidx.media3.ui.R.styleable.DefaultTimeBar_bar_height,
                    defaultBarH,
                )
                barGravity = a.getInt(
                    androidx.media3.ui.R.styleable.DefaultTimeBar_bar_gravity,
                    0,
                )
                scrubberEnabledSizePx = a.getDimensionPixelSize(
                    androidx.media3.ui.R.styleable.DefaultTimeBar_scrubber_enabled_size,
                    defaultEnabled,
                )
                scrubberDisabledSizePx = a.getDimensionPixelSize(
                    androidx.media3.ui.R.styleable.DefaultTimeBar_scrubber_disabled_size,
                    defaultDisabled,
                )
                scrubberDraggedSizePx = a.getDimensionPixelSize(
                    androidx.media3.ui.R.styleable.DefaultTimeBar_scrubber_dragged_size,
                    defaultDragged,
                )
            } finally {
                a.recycle()
            }
        } else {
            barHeightPx = defaultBarH
            barGravity = 0
            scrubberEnabledSizePx = defaultEnabled
            scrubberDisabledSizePx = defaultDisabled
            scrubberDraggedSizePx = defaultDragged
        }
        scrubberPaddingPx = (maxOf(scrubberDisabledSizePx, maxOf(scrubberEnabledSizePx, scrubberDraggedSizePx)) + 1) / 2
    }

    fun setMutedSegments(segments: List<Pair<Long, Long>>) {
        if (mutedSegments == segments) return
        mutedSegments = segments
        invalidate()
    }

    override fun setDuration(duration: Long) {
        totalDurationMs = duration
        super.setDuration(duration)
    }

    override fun setPosition(position: Long) {
        currentPosMs = position
        super.setPosition(position)
    }

    override fun showScrubber() {
        scrubberShown = true
        super.showScrubber()
    }

    override fun showScrubber(showAnimationDurationMs: Long) {
        scrubberShown = true
        super.showScrubber(showAnimationDurationMs)
    }

    override fun hideScrubber(disableScrubberPadding: Boolean) {
        scrubberShown = false
        super.hideScrubber(disableScrubberPadding)
    }

    override fun hideScrubber(hideAnimationDurationMs: Long) {
        scrubberShown = false
        super.hideScrubber(hideAnimationDurationMs)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val segments = mutedSegments
        val duration = totalDurationMs
        if (segments.isEmpty() || duration <= 0L) return

        val barLeft = (paddingLeft + scrubberPaddingPx).toFloat()
        val barRight = (width - paddingRight - scrubberPaddingPx).toFloat()
        val barWidth = barRight - barLeft
        if (barWidth <= 0f) return

        val barCenterY = if (barGravity == 1) {
            (height - paddingBottom) - barHeightPx / 2f
        } else {
            paddingTop + (height - paddingTop - paddingBottom) / 2f
        }
        val barTop = barCenterY - barHeightPx / 2f
        val barBottom = barTop + barHeightPx
        val corner = barHeightPx / 2f

        val saveCount = canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (scrubberShown || isPressed || isFocused) && isEnabled) {
            val activeScrubberSize = when {
                isPressed -> scrubberDraggedSizePx
                isEnabled -> scrubberEnabledSizePx
                else -> scrubberDisabledSizePx
            }
            if (activeScrubberSize > 0) {
                val frac = (currentPosMs.toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f)
                val scrubberX = barLeft + barWidth * frac
                val scrubberRadius = activeScrubberSize / 2f + resources.displayMetrics.density
                scrubberClipPath.rewind()
                scrubberClipPath.addCircle(scrubberX, barCenterY, scrubberRadius, Path.Direction.CW)
                canvas.clipOutPath(scrubberClipPath)
            }
        }

        val durDouble = duration.toDouble()
        for ((rawStart, rawEnd) in segments) {
            val s = rawStart.coerceIn(0L, duration)
            val e = rawEnd.coerceIn(s, duration)
            if (e <= s) continue
            val x1 = barLeft + (barWidth * (s.toDouble() / durDouble)).toFloat()
            val rawX2 = barLeft + (barWidth * (e.toDouble() / durDouble)).toFloat()
            val x2 = max(x1 + minSegmentWidthPx, rawX2).coerceAtMost(barRight)
            if (x2 > x1) {
                segmentRect.set(x1, barTop, x2, barBottom)
                canvas.drawRoundRect(segmentRect, corner, corner, mutedPaint)
            }
        }
        canvas.restoreToCount(saveCount)
    }
}
