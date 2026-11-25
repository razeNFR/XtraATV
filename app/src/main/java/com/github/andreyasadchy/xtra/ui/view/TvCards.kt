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
            applyWallpaperSurface(card)
            return
        }
        card.isFocusable = true
        (card as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        applyWallpaperSurface(card)
    }

    /**
     * Adapts XML TV cards (followed channels, categories, streams, videos, saved, recent searches) to the
     * user's configurable smoked-glass opacity ONLY when a non-default TV wallpaper is active.
     * Restores exact default XML colors when [com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.STYLE_DEFAULT] is active.
     */
    fun applyWallpaperSurface(card: View) {
        val context = card.context
        val hasWallpaper = com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.isActive(context)
        val opacity = if (hasWallpaper) com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.cardOpacity(context) else -1

        // Recent search pill rows (text + delete icon button)
        val deleteBtn = card.findViewById<View>(R.id.delete)
        if (deleteBtn != null) {
            val key = "pill|$opacity"
            if (card.getTag(R.id.tvCardSurfaceKey) == key) return
            card.setTag(R.id.tvCardSurfaceKey, key)
            val textView = card.findViewById<View>(R.id.text)
            if (hasWallpaper) {
                val pillPct = (opacity * 0.56f).toInt().coerceIn(24, 54)
                val bgColor = android.graphics.Color.argb((pillPct * 255 / 100).coerceIn(0, 255), 0x0B, 0x0C, 0x10)
                val density = context.resources.displayMetrics.density
                fun makePillBg(): android.graphics.drawable.StateListDrawable = android.graphics.drawable.StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_focused),
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(0xFFF2F2F7.toInt())
                            cornerRadius = 24f * density
                        },
                    )
                    addState(
                        intArrayOf(),
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(bgColor)
                            setStroke(maxOf(1, density.toInt()), 0x38FFFFFF)
                            cornerRadius = 24f * density
                        },
                    )
                }
                textView?.background = makePillBg()
                deleteBtn.background = makePillBg()
            } else {
                textView?.setBackgroundResource(R.drawable.tv_pill_background)
                deleteBtn.setBackgroundResource(R.drawable.tv_pill_background)
            }
            return
        }

        val materialCard = card as? com.google.android.material.card.MaterialCardView ?: return
        val isChannelCard = card.findViewById<View>(R.id.userRing) != null
        val isOfflineChannel = isChannelCard && card.findViewById<View>(R.id.liveBadge)?.visibility != View.VISIBLE
        val key = "card|$opacity|$isOfflineChannel"
        if (card.getTag(R.id.tvCardSurfaceKey) == key) return
        card.setTag(R.id.tvCardSurfaceKey, key)

        if (card.getTag(R.id.tvCardDefaultBg) == null) {
            card.setTag(R.id.tvCardDefaultBg, materialCard.cardBackgroundColor)
        }
        val shadeView = card.findViewById<View>(R.id.gameCardShade)
        if (hasWallpaper) {
            materialCard.setCardBackgroundColor(
                com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.xmlCardColorStateList(context, offlineChannel = isOfflineChannel)
            )
            shadeView?.alpha = com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.categoryShadeAlpha(context)
        } else {
            (card.getTag(R.id.tvCardDefaultBg) as? android.content.res.ColorStateList)?.let {
                materialCard.setCardBackgroundColor(it)
            }
            shadeView?.alpha = 1f
        }
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
