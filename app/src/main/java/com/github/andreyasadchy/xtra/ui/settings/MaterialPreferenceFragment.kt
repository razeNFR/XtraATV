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

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(requireContext())) {
            // settings that only make sense on a touch screen phone or tablet
            TV_HIDDEN_KEYS.forEach { findPreference<Preference>(it)?.isVisible = false }
            // back from a settings screen: the focus goes back to the entry that opened it
            focusedPositions[javaClass.name]?.let { position ->
                listView.postDelayed({
                    if (view.isAttachedToWindow) {
                        listView.scrollToPosition(position)
                        listView.post { listView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
                    }
                }, 150L)
            }
        }
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
