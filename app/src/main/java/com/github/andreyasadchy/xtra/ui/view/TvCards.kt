package com.github.andreyasadchy.xtra.ui.view

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * TV helpers for list cards: a card is a single D-pad focus target, and the actions that
 * phones reach by tapping parts of the card (channel, category, options) are offered in a
 * menu opened with a long press on the select button.
 */
object TvCards {

    /**
     * Pre-blurs the category card background once in software and caches it in Coil's memory cache,
     * avoiding 20+ concurrent per-frame GPU RenderEffect blur passes while scrolling on TV.
     */
    val BlurTransformation = object : coil3.transform.Transformation() {
        override val cacheKey: String = "tv_game_bg_blur"

        override suspend fun transform(input: android.graphics.Bitmap, size: coil3.size.Size): android.graphics.Bitmap {
            val src = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                input.config == android.graphics.Bitmap.Config.HARDWARE
            ) {
                input.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return input
            } else {
                input
            }
            val mid = android.graphics.Bitmap.createScaledBitmap(src, 24, 32, true)
            val tiny = android.graphics.Bitmap.createScaledBitmap(mid, 8, 11, true)
            val blurred = android.graphics.Bitmap.createScaledBitmap(tiny, 32, 44, true)
            if (src !== input) src.recycle()
            if (mid !== input && mid !== tiny) mid.recycle()
            if (tiny !== blurred) tiny.recycle()
            return blurred
        }
    }

    fun prepare(card: View) {
        if (card.findViewById<View>(R.id.delete) != null) {
            card.isFocusable = false
            (card as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            card.foreground = null
            return
        }
        card.isFocusable = true
        (card as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (card is com.google.android.material.card.MaterialCardView) {
            if (card.strokeWidth != 0) {
                card.strokeWidth = 0
            }
            if (card.foreground !is TvCardBorderDrawable) {
                card.foreground = TvCardBorderDrawable(card)
            } else {
                card.foreground?.invalidateSelf()
            }
        }
    }

    /**
     * Zero-relayout, zero-allocation foreground border for TV MaterialCardViews:
     * draws a subtle 1dp smoked-glass rim at rest and a crisp 3dp white border when focused,
     * automatically matching each card's own corner radius (12dp media, 14dp games, 18dp channels).
     */
    private class TvCardBorderDrawable(
        private val card: com.google.android.material.card.MaterialCardView,
    ) : android.graphics.drawable.Drawable() {
        private val density = card.resources.displayMetrics.density
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }
        private val rect = android.graphics.RectF()
        private var focused = false

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val nowFocused = state.any { it == android.R.attr.state_focused }
            if (nowFocused != focused) {
                focused = nowFocused
                invalidateSelf()
                return true
            }
            return false
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val strokePx = if (focused) 2.8f * density else 1f * density
            paint.strokeWidth = strokePx
            paint.color = if (focused) android.graphics.Color.WHITE else 0x26FFFFFF
            val half = strokePx * 0.5f
            rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
            val r = (card.radius - half).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, r, r, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    fun showMenu(card: View) {
        val context = card.context
        val entries = mutableListOf<Pair<CharSequence, () -> Unit>>()
        fun add(label: CharSequence, action: () -> Unit) {
            entries.add(label to action)
        }
        val username = card.findViewById<TextView>(R.id.username) ?: card.findViewById<TextView>(R.id.userName)
        val userImage = card.findViewById<View>(R.id.userImage)
        when {
            username != null && username.isVisible && username.hasOnClickListeners() && !username.text.isNullOrBlank() ->
                add(context.getString(R.string.tv_open_channel, username.text)) { username.performClick() }
            userImage != null && userImage.isVisible && userImage.hasOnClickListeners() ->
                add(context.getString(R.string.tv_open_channel, "").trimEnd(' ', ':')) { userImage.performClick() }
        }
        val gameName = card.findViewById<TextView>(R.id.gameName)
        if (gameName != null && gameName.isVisible && gameName.hasOnClickListeners() && !gameName.text.isNullOrBlank()) {
            add(context.getString(R.string.tv_open_category, gameName.text)) { gameName.performClick() }
        }
        val options = card.findViewById<View>(R.id.options)
        if (options != null && options.isVisible && options.hasOnClickListeners()) {
            add(context.getString(R.string.tv_more_options)) { options.performClick() }
        } else if (card.isLongClickable) {
            if (entries.isEmpty()) {
                card.performLongClick()
                return
            }
            add(context.getString(R.string.tv_more_actions)) { card.performLongClick() }
        }
        if (entries.isEmpty()) {
            return
        }
        val title = card.findViewById<TextView>(R.id.title)?.takeIf { it.isVisible }?.text
            ?: card.findViewById<TextView>(R.id.gameName)?.takeIf { it.isVisible }?.text
            ?: username?.text
        MaterialAlertDialogBuilder(context).apply {
            if (!title.isNullOrBlank()) {
                setTitle(title)
            }
            setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                entries[which].second()
            }
        }.show()
    }
}
