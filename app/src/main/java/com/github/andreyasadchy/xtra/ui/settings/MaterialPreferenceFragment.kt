package com.github.andreyasadchy.xtra.ui.settings

import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

abstract class MaterialPreferenceFragment : PreferenceFragmentCompat() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(requireContext())) {
            // settings that only make sense on a touch screen phone or tablet: hidden before the list is shown, they no longer flash
            TV_HIDDEN_KEYS.forEach { findPreference<Preference>(it)?.isVisible = false }
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(requireContext())) {
            styleTvRows()
            val position = focusedPositions[javaClass.name]
            listView.postDelayed({
                if (view.isAttachedToWindow && listView.focusedChild == null) {
                    if (position != null) {
                        // back from a settings screen: the focus goes back to the entry that opened it
                        listView.scrollToPosition(position)
                        listView.post { listView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
                    } else {
                        // a settings screen just opened: the focus is on its first entry
                        (0 until listView.childCount).map { listView.getChildAt(it) }.firstOrNull { it.isFocusable && it.isEnabled }?.requestFocus()
                    }
                }
            }, 150L)
        }
    }

    /**
     * Google TV style rows: rounded, white with dark texts when focused. The preference list resets the background and the
     * text colors of a row each time it is bound again (a value changed, a summary updated), so the style is checked again
     * before every frame instead of only when a row comes on screen.
     */
    private fun styleTvRows() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val focusedTitle = context.getColor(com.github.andreyasadchy.xtra.R.color.tv_settings_title_focused)
        val focusedSummary = context.getColor(com.github.andreyasadchy.xtra.R.color.tv_settings_summary_focused)
        val title = android.graphics.Color.WHITE
        val summary = 0xB3FFFFFF.toInt()
        listView.clipToPadding = false
        // the rows draw their own focus, not the frame of the other lists
        listView.setTag(com.github.andreyasadchy.xtra.R.id.tvRowsHighlighted, true)
        val styleRow = { view: android.view.View ->
            keepDisabledRowFocusable(view)
            if (view.isFocusable) {
                val background = view.getTag(com.github.andreyasadchy.xtra.R.id.tvSettingsRowBackground) as? android.graphics.drawable.Drawable
                if (background == null || view.background !== background) {
                    val drawable = androidx.core.content.ContextCompat.getDrawable(context, com.github.andreyasadchy.xtra.R.drawable.tv_settings_row_background)
                    view.background = drawable
                    // drawn at once in its state, without waiting for a state change
                    drawable?.jumpToCurrentState()
                    view.setTag(com.github.andreyasadchy.xtra.R.id.tvSettingsRowBackground, drawable)
                    val padding = (20 * density).toInt()
                    view.setPaddingRelative(padding, view.paddingTop.coerceAtLeast((14 * density).toInt()), padding, view.paddingBottom.coerceAtLeast((14 * density).toInt()))
                }
                // the texts are inside a layout of the row: they do not get its focused state, their color is set here
                val focused = view.isFocused
                view.findViewById<android.widget.TextView>(android.R.id.title)?.let {
                    val color = if (focused) focusedTitle else title
                    if (it.currentTextColor != color) it.setTextColor(color)
                }
                view.findViewById<android.widget.TextView>(android.R.id.summary)?.let {
                    val color = if (focused) focusedSummary else summary
                    if (it.currentTextColor != color) it.setTextColor(color)
                }
            }
        }
        listView.addOnChildAttachStateChangeListener(object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: android.view.View) {
                styleRow(view)
            }

            override fun onChildViewDetachedFromWindow(view: android.view.View) {}
        })
        val list = listView
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            for (i in 0 until list.childCount) {
                styleRow(list.getChildAt(i))
            }
            true
        }
        // the observer of the window is shared by the settings screens: removed with the list
        list.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.viewTreeObserver.addOnPreDrawListener(listener)
            }

            override fun onViewDetachedFromWindow(v: android.view.View) {
                v.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        })
        if (list.isAttachedToWindow) {
            list.viewTreeObserver.addOnPreDrawListener(listener)
        }
    }

    /** Why a setting is grayed out, for the ones that depend on others in the code (not with a dependency). */
    protected val disabledHints = mutableMapOf<String, () -> String>()

    /**
     * TV: a grayed out setting can't get the focus, the focus jumped over it badly (pressed several times to go past).
     * Its row stays focusable, faded, and OK on it says what turns it on.
     */
    private fun keepDisabledRowFocusable(view: android.view.View) {
        val title = view.findViewById<android.widget.TextView>(android.R.id.title) ?: return
        if (title.isEnabled) {
            if (view.getTag(com.github.andreyasadchy.xtra.R.id.tvDisabledRow) == true) {
                view.setTag(com.github.andreyasadchy.xtra.R.id.tvDisabledRow, false)
                view.alpha = 1f
            }
            return
        }
        if (!view.isEnabled || view.getTag(com.github.andreyasadchy.xtra.R.id.tvDisabledRow) != true) {
            view.setTag(com.github.andreyasadchy.xtra.R.id.tvDisabledRow, true)
            view.isEnabled = true
            view.isFocusable = true
            view.alpha = 0.5f
            view.setOnClickListener {
                val preference = findByTitle(preferenceScreen, title.text?.toString())
                val hint = preference?.key?.let { disabledHints[it]?.invoke() }
                    ?: preference?.dependency?.let { findPreference<Preference>(it)?.title?.toString() }?.let { getString(com.github.andreyasadchy.xtra.R.string.setting_disabled_dependency, it) }
                    ?: getString(com.github.andreyasadchy.xtra.R.string.setting_disabled_generic)
                android.widget.Toast.makeText(requireContext(), hint, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun findByTitle(group: androidx.preference.PreferenceGroup?, title: String?): Preference? {
        if (group == null || title == null) return null
        for (i in 0 until group.preferenceCount) {
            val preference = group.getPreference(i)
            if (preference.isVisible && preference.title?.toString() == title) return preference
            if (preference is androidx.preference.PreferenceGroup) {
                findByTitle(preference, title)?.let { return it }
            }
        }
        return null
    }

    override fun onPause() {
        super.onPause()
        listView?.let { list ->
            list.focusedChild?.let { list.getChildAdapterPosition(it) }?.takeIf { it != androidx.recyclerview.widget.RecyclerView.NO_POSITION }?.let {
                focusedPositions[javaClass.name] = it
            }
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (requireContext().prefs().getBoolean(C.UI_THEME_MATERIAL3, true)) {
            when (preference) {
                is ListPreference -> showPreferenceDialog(preference, MaterialListPreference())
                is MultiSelectListPreference -> showPreferenceDialog(preference, MaterialMultiSelectListPreference())
                is EditTextPreference -> showPreferenceDialog(preference, MaterialEditTextPreference())
                else -> super.onDisplayPreferenceDialog(preference)
            }
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    @Suppress("DEPRECATION")
    private fun showPreferenceDialog(preference: Preference, fragment: DialogFragment) {
        fragment.arguments = bundleOf("key" to preference.key)
        fragment.setTargetFragment(this, 0)
        fragment.show(parentFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
    }

    companion object {
        /** Entry focused in each settings screen when it was left. */
        private val focusedPositions = mutableMapOf<String, Int>()

        val TV_HIDDEN_KEYS = listOf(
            "columnsPortrait", "columnsLandscape", "ui_scrolltop", "ui_theme_bottom_nav_color", "ui_theme_appbar_lift",
            "player_rounded_corner_padding", "player_move_freely", "player_minimize", "player_aspect", "player_fullscreen",
            "player_doubletap", "player_chatbartoggle", "chatWidth", "ui_draw_behind_cutouts",
            // phone only: picture-in-picture, background audio, headphones, mobile data, lock screen, buttons the TV player has not
            "player_picture_in_picture", "player_background_audio", "player_background_audio_locked",
            "player_disable_background_video", "player_use_background_audio_track", "player_background_audio_pip_closed",
            "player_background_audio_pip_locked", "player_handle_audio_becoming_noisy", "player_show_stream_notification_seekbar",
            "player_default_cellular_quality", "playerRewindV2", "playerForwardV2", "sleep_timer_lock", "admin_settings",
            "tv_back_minimizes_player",
        )
    }
}
