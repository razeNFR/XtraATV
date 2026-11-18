package com.github.andreyasadchy.xtra.ui.tv

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TV: actions of a stream card, opened with a long press on OK (home, lists, "Now playing"):
 * channel, its videos, category, follow, and the actions given by the screen.
 */
object TvStreamActions {

    class Extra(val label: String, val icon: Int? = null, val run: () -> Unit)

    fun show(activity: MainActivity, stream: Stream, first: Extra? = null, last: List<Extra> = emptyList()) {
        val channelId = stream.channelId
        activity.lifecycleScope.launch {
            // the follow entry says what it does: the state is asked first, never for long
            val following = if (channelId != null && TvFollow.isEnabled(activity)) {
                withTimeoutOrNull(1500L) { TvFollow.isFollowing(activity, channelId) }
            } else null
            val entries = mutableListOf<Extra>()
            first?.let { entries += it }
            val playerFrag = activity.playerFragment as? com.github.andreyasadchy.xtra.ui.player.PlayerFragment
            if (playerFrag != null && !stream.channelLogin.isNullOrBlank() &&
                stream.channelLogin.equals(activity.currentPlayingStream()?.channelLogin, ignoreCase = true)
            ) {
                val coStreamers = playerFrag.getCoStreamers()
                    .filter { !it.channelLogin.isNullOrBlank() && !it.channelLogin.equals(stream.channelLogin, ignoreCase = true) }
                    .distinctBy { it.channelLogin?.lowercase() }
                    .take(com.github.andreyasadchy.xtra.ui.player.TvMultiStreamPicker.MAX - 1)
                if (coStreamers.isNotEmpty()) {
                    val allCoStreams = (listOf(stream) + coStreamers).take(com.github.andreyasadchy.xtra.ui.player.TvMultiStreamPicker.MAX)
                    entries += Extra(
                        activity.getString(R.string.tv_costream_watch_multistream, allCoStreams.size),
                        R.drawable.tv_ic_streamers,
                    ) {
                        activity.startMultiStream(allCoStreams, stream)
                    }
                    if (playerFrag.isLiveOrDvrScreen()) {
                        coStreamers.take(2).forEach { co ->
                            val coLogin = co.channelLogin ?: return@forEach
                            if (!activity.isStreamInTvPip(coLogin)) {
                                val coName = co.channelName ?: coLogin
                                entries += Extra(
                                    activity.getString(R.string.tv_costream_open_pip, coName),
                                    R.drawable.tv_ic_pip,
                                ) {
                                    activity.openTvPip(co)
                                }
                            }
                        }
                    }
                }
            }
            val name = stream.channelName ?: stream.channelLogin ?: ""
            entries += Extra(activity.getString(R.string.tv_open_channel, name), R.drawable.tv_ic_person) { activity.openTvChannel(stream, null) }
            entries += Extra(activity.getString(R.string.tv_channel_videos), R.drawable.tv_ic_video_library) { activity.openTvChannel(stream, "1") }
            if (!stream.gameName.isNullOrBlank()) {
                entries += Extra(activity.getString(R.string.tv_open_category, stream.gameName), R.drawable.tv_ic_gamepad) { activity.openTvGame(stream.gameId, stream.gameSlug, stream.gameName) }
            }
            if (channelId != null && following != null) {
                entries += Extra(activity.getString(if (following) R.string.unfollow else R.string.follow), if (following) R.drawable.tv_ic_heart else R.drawable.tv_ic_heart_outline) {
                    activity.lifecycleScope.launch {
                        val done = TvFollow.setFollowing(activity, channelId, stream.channelLogin, stream.channelName, !following)
                        Toast.makeText(
                            activity,
                            when {
                                !done -> activity.getString(R.string.tv_follow_failed)
                                following -> activity.getString(R.string.unfollowed, name)
                                else -> activity.getString(R.string.now_following, name)
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            // favorite: first in the followed streams when live
            stream.channelLogin?.let { login ->
                val favorite = TvFavorites.isFavorite(login)
                entries += Extra(activity.getString(if (favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add), R.drawable.tv_ic_star) {
                    if (favorite) {
                        TvFavorites.remove(activity, login)
                        Toast.makeText(activity, activity.getString(R.string.tv_favorite_removed, name), Toast.LENGTH_SHORT).show()
                    } else {
                        TvFavorites.add(activity, login, stream.channelName)
                        Toast.makeText(activity, activity.getString(R.string.tv_favorite_added, name), Toast.LENGTH_SHORT).show()
                    }
                }
                if (playerFrag != null && login.equals(activity.currentPlayingStream()?.channelLogin, ignoreCase = true)) {
                    entries += Extra(activity.getString(R.string.tv_create_clip), R.drawable.tv_ic_clip) {
                        playerFrag.triggerTvCreateClip()
                    }
                    entries += Extra(activity.getString(R.string.tv_share_qr), R.drawable.tv_ic_qr) {
                        playerFrag.showTvShareQr()
                    }
                } else {
                    entries += Extra(activity.getString(R.string.tv_create_clip), R.drawable.tv_ic_clip) {
                        TvQrShare.createAndShowClipQr(
                            context = activity,
                            broadcasterId = stream.channelId,
                            channelLogin = login,
                            broadcastId = stream.id,
                            videoId = null,
                            offsetSeconds = null,
                        )
                    }
                    entries += Extra(activity.getString(R.string.tv_share_qr), R.drawable.tv_ic_qr) {
                        TvQrShare.showQrModal(
                            context = activity,
                            title = name.ifBlank { login },
                            subtitle = activity.getString(R.string.tv_share_qr_subtitle),
                            url = "https://www.twitch.tv/$login",
                        )
                    }
                }
            }
            entries += last
            TvDialogs.showChoices(activity, stream.title?.trim()?.takeIf { it.isNotEmpty() } ?: name, entries.map { it.label }, -1, entries.map { it.icon }) {
                entries[it].run()
            }
        }
    }
}
