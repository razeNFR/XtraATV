package com.github.andreyasadchy.xtra.ui.view

import android.text.TextUtils
import android.widget.TextView

/**
 * TV: the title of the focused card scrolls when it is longer than the card, a second after the focus (not while going
 * through a row), and comes back to its start with "…" when the card loses the focus.
 */
object TvMarquee {

    private const val START_DELAY = 1000L

    fun update(title: TextView, focused: Boolean) {
        val start = title.getTag(com.github.andreyasadchy.xtra.R.id.tvMarqueeStart) as? Runnable
            ?: Runnable {
                if (title.layout?.getEllipsisCount(0)?.let { it > 0 } == true) {
                    title.ellipsize = TextUtils.TruncateAt.MARQUEE
                    title.marqueeRepeatLimit = -1
                    title.isSelected = true
                }
            }.also { title.setTag(com.github.andreyasadchy.xtra.R.id.tvMarqueeStart, it) }
        title.removeCallbacks(start)
        if (focused) {
            title.postDelayed(start, START_DELAY)
        } else if (title.ellipsize != TextUtils.TruncateAt.END) {
            title.isSelected = false
            title.ellipsize = TextUtils.TruncateAt.END
        }
    }
}
