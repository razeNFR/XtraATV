package com.github.andreyasadchy.xtra.ui.settings

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference
import com.github.andreyasadchy.xtra.R

/**
 * TV: an icon in front of each setting of the right column, to find one at a glance. Chosen by its key (the setting
 * itself, else a word of its key), else by its kind (switch, list, screen). Settings that have an icon already keep it.
 */
object TvSettingIcons {

    private val keys = mapOf(
        // categories and screens
        "player_settings" to R.drawable.tv_ic_play,
        "playback_settings" to R.drawable.tv_ic_tune,
        "buffer_settings" to R.drawable.tv_ic_timer,
        "player_button_settings" to R.drawable.tv_ic_gamepad,
        "player_menu_settings" to R.drawable.tv_ic_list,
        "ui_language" to R.drawable.tv_ic_globe,
        "ui_settings" to R.drawable.tv_ic_view_quilt,
        "chat_settings" to R.drawable.tv_ic_chat,
        "xtratv_settings" to R.drawable.tv_ic_tv_small,
        "adblock_settings" to R.drawable.tv_ic_shield,
        "player_adblock" to R.drawable.tv_ic_shield,
        "custom_proxy_settings" to R.drawable.tv_ic_proxy,
        "player_use_custom_proxy" to R.drawable.tv_ic_proxy,
        "stream_proxy_settings" to R.drawable.tv_ic_proxy,
        "player_use_stream_proxy" to R.drawable.tv_ic_proxy,
        "video_swap_settings" to R.drawable.tv_ic_swap,
        "player_use_video_swap" to R.drawable.tv_ic_swap,
        "player_hide_ads" to R.drawable.tv_ic_block,
        "network_library" to R.drawable.tv_ic_wifi,
        "api_token_settings" to R.drawable.tv_ic_key,
        "debug_settings" to R.drawable.tv_ic_bug,
        "check_updates" to R.drawable.tv_ic_refresh,
        "update_settings" to R.drawable.tv_ic_cloud,
        "backup_settings" to R.drawable.tv_ic_cloud,
        "restore_settings" to R.drawable.tv_ic_history,
        "download_settings" to R.drawable.tv_ic_download,
        "about_app" to R.drawable.tv_ic_info,
        "about_github" to R.drawable.tv_ic_share,
        "about_device" to R.drawable.tv_ic_tv_small,
        // Xtra TV
        "player_frame_rate_matching" to R.drawable.tv_ic_hd,
        "tv_player_clock" to R.drawable.tv_ic_schedule,
        "tv_back_action" to R.drawable.tv_ic_replay,
        "tv_keep_sound_on_leave" to R.drawable.tv_ic_volume,
        "tv_dpad_switch_stream" to R.drawable.tv_ic_swap,
        "tv_focus_switch" to R.drawable.tv_ic_swap,
        "tv_dpad_up_down" to R.drawable.tv_ic_streamers,
        "ui_tv_preview" to R.drawable.tv_ic_play,
        "ui_tv_preview_sound" to R.drawable.tv_ic_volume,
        "tv_card_zoom" to R.drawable.tv_ic_open_full,
        "tv_hide_menu_on_launch" to R.drawable.tv_ic_view_quilt,
        "tv_home_followed_videos" to R.drawable.tv_ic_video_library,
        "tv_favorites" to R.drawable.tv_ic_star,
        "tv_ambient_color" to R.drawable.tv_ic_palette,
        "tv_home_cards" to R.drawable.tv_ic_view_quilt,
        "tv_stream_columns" to R.drawable.tv_ic_view_quilt,
        "tv_live_alerts" to R.drawable.tv_ic_bell,
        "tv_live_alerts_duration" to R.drawable.tv_ic_schedule,
        // player
        "player_defaultquality" to R.drawable.tv_ic_hd,
        "player_keep_screen_on_when_paused" to R.drawable.tv_ic_tv_small,
        "player_audio_focus" to R.drawable.tv_ic_volume,
        "player_use_videopositions" to R.drawable.tv_ic_history,
        "delete_video_positions" to R.drawable.tv_ic_delete,
        "proxy_timeout" to R.drawable.tv_ic_timer,
        "token_supported_codecs" to R.drawable.tv_ic_hd,
        // chat
        "chat_show_replies" to R.drawable.tv_ic_chat_message,
        "chat_highlight_mentions" to R.drawable.tv_ic_chat_message,
        "chat_show_usernotice" to R.drawable.tv_ic_campaign,
        "chat_show_clearmsg" to R.drawable.tv_ic_delete,
        "chat_show_clearchat" to R.drawable.tv_ic_block,
        "chat_firstmsg_visibility" to R.drawable.tv_ic_person,
        "chat_points_collect" to R.drawable.tv_ic_diamond,
        "chat_points_notify" to R.drawable.tv_ic_diamond,
        "chat_raids_show" to R.drawable.tv_ic_raid,
        "chat_raids_auto_switch" to R.drawable.tv_ic_raid,
        "chat_polls_show" to R.drawable.tv_ic_stats,
        "chat_predictions_show" to R.drawable.tv_ic_stats,
        "chat_system_message_emotes" to R.drawable.tv_ic_info,
        "chat_disable" to R.drawable.tv_ic_chat,
        "player_keep_chat_open" to R.drawable.tv_ic_chat,
        // interface
        "ui_follow_button" to R.drawable.tv_ic_heart,
        "ui_activate_notifications_when_following" to R.drawable.tv_ic_bell,
        "ui_name_display" to R.drawable.tv_ic_person,
        "delete_recent_searches" to R.drawable.tv_ic_delete,
        "import_app_downloads" to R.drawable.tv_ic_download,
    )

    /** A word of the key and its icon, the first one found counts. */
    private val words = listOf(
        "translate" to R.drawable.tv_ic_translate,
        "downloaded_languages" to R.drawable.tv_ic_translate,
        "language" to R.drawable.tv_ic_globe,
        "emote" to R.drawable.tv_ic_mood,
        "stv" to R.drawable.tv_ic_mood,
        "bttv" to R.drawable.tv_ic_mood,
        "ffz" to R.drawable.tv_ic_mood,
        "zerowidth" to R.drawable.tv_ic_mood,
        "paints" to R.drawable.tv_ic_palette,
        "badge" to R.drawable.tv_ic_star,
        "color" to R.drawable.tv_ic_palette,
        "boldnames" to R.drawable.tv_ic_text_size,
        "timestamp" to R.drawable.tv_ic_schedule,
        "delay" to R.drawable.tv_ic_schedule,
        "size" to R.drawable.tv_ic_text_size,
        "width" to R.drawable.tv_ic_text_size,
        "gif" to R.drawable.tv_ic_image,
        "image" to R.drawable.tv_ic_image,
        "webp" to R.drawable.tv_ic_image,
        "recent" to R.drawable.tv_ic_history,
        "limit" to R.drawable.tv_ic_list,
        "websocket" to R.drawable.tv_ic_wifi,
        "ssl" to R.drawable.tv_ic_wifi,
        "pubsub" to R.drawable.tv_ic_wifi,
        "eventsub" to R.drawable.tv_ic_wifi,
        "speed" to R.drawable.tv_ic_speed,
        "buffer" to R.drawable.tv_ic_timer,
        "offset" to R.drawable.tv_ic_timer,
        "token" to R.drawable.tv_ic_key,
        "client_id" to R.drawable.tv_ic_key,
        "integrity" to R.drawable.tv_ic_key,
        "gql" to R.drawable.tv_ic_key,
        "helix" to R.drawable.tv_ic_key,
        "login" to R.drawable.tv_ic_person,
        "user" to R.drawable.tv_ic_person,
        "debug" to R.drawable.tv_ic_bug,
        "download" to R.drawable.tv_ic_download,
        "update" to R.drawable.tv_ic_cloud,
        "tabs" to R.drawable.tv_ic_view_quilt,
        "home" to R.drawable.tv_ic_home,
        "follow" to R.drawable.tv_ic_heart,
        "notification" to R.drawable.tv_ic_bell,
        "chat" to R.drawable.tv_ic_chat,
        "quality" to R.drawable.tv_ic_hd,
        "volume" to R.drawable.tv_ic_volume,
        "audio" to R.drawable.tv_ic_volume,
    )

    fun apply(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val preference = group.getPreference(i)
            if (preference is PreferenceCategory) {
                apply(preference)
                continue
            }
            if (preference.icon == null) {
                preference.setIcon(iconOf(preference))
            }
            if (preference is PreferenceGroup) {
                apply(preference)
            }
        }
    }

    private fun iconOf(preference: Preference): Int {
        val key = preference.key.orEmpty()
        keys[key]?.let { return it }
        val lower = key.lowercase()
        words.firstOrNull { lower.contains(it.first) }?.let { return it.second }
        return when (preference) {
            is TwoStatePreference -> R.drawable.tv_ic_toggle
            is ListPreference, is MultiSelectListPreference -> R.drawable.tv_ic_list
            else -> R.drawable.tv_ic_tune
        }
    }
}
