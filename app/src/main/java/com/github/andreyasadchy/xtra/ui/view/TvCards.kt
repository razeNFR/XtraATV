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

    fun prepare(card: View) {
        card.isFocusable = true
        (card as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
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
