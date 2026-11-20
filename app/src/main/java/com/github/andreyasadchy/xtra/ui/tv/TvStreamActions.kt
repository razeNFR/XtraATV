package com.github.andreyasadchy.xtra.ui.tv

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
            val playerFrag = activity.playerFragment as? com.github.andreyasadchy.xtra.ui.player.PlayerFragment
            val isPlayingThis = playerFrag != null && !stream.channelLogin.isNullOrBlank() &&
                stream.channelLogin.equals(activity.currentPlayingStream()?.channelLogin, ignoreCase = true)

            val (following, resolvedPartners) = coroutineScope {
                val followDeferred = async {
                    if (channelId != null && TvFollow.isEnabled(activity)) {
                        withTimeoutOrNull(1500L) { TvFollow.isFollowing(activity, channelId) }
                    } else null
                }
                val partnersDeferred = async {
                    if (stream.channelLogin.isNullOrBlank()) {
                        emptyList()
                    } else if (isPlayingThis) {
                        val existing = playerFrag?.getCoStreamers().orEmpty()
                        existing.ifEmpty {
                            withTimeoutOrNull(1200L) {
                                TvCoStreamCache.loadForChannel(activity, channelId, stream.channelLogin, stream.title)
                            }.orEmpty()
                        }
                    } else {
                        val cached = TvCoStreamCache.getPartners(channelId, stream.channelLogin)
                        cached.ifEmpty {
                            withTimeoutOrNull(1200L) {
                                TvCoStreamCache.loadForChannel(activity, channelId, stream.channelLogin, stream.title)
                            }.orEmpty()
                        }
                    }
                }
                followDeferred.await() to partnersDeferred.await()
            }

            val entries = mutableListOf<Extra>()
            first?.let { entries += it }
            if (!stream.channelLogin.isNullOrBlank()) {
                val coStreamers = resolvedPartners
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
                    if (isPlayingThis && playerFrag?.isLiveOrDvrScreen() == true) {
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
                        val err = TvFollow.setFollowingWithMessage(activity, channelId, stream.channelLogin, stream.channelName, !following)
                        TvToast.makeText(
                            activity,
                            when {
                                err != null -> err
                                following -> activity.getString(R.string.unfollowed, name)
                                else -> activity.getString(R.string.now_following, name)
                            },
                            if (err != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
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
                        TvToast.makeText(activity, activity.getString(R.string.tv_favorite_removed, name), Toast.LENGTH_SHORT).show()
                    } else {
                        TvFavorites.add(activity, login, stream.channelName)
                        TvToast.makeText(activity, activity.getString(R.string.tv_favorite_added, name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (!stream.channelId.isNullOrBlank() || !stream.channelLogin.isNullOrBlank()) {
                val chBlocked = TvBlacklist.isChannelBlocked(stream.channelId, stream.channelLogin)
                entries += Extra(
                    activity.getString(
                        if (chBlocked) R.string.tv_blacklist_unhide_channel else R.string.tv_blacklist_hide_channel,
                        name.ifBlank { stream.channelLogin.orEmpty() },
                    ),
                    R.drawable.tv_ic_block,
                ) {
                    val label = name.ifBlank { stream.channelLogin.orEmpty() }
                    if (chBlocked) {
                        TvBlacklist.unblockChannel(activity, stream.channelId, stream.channelLogin)
                        TvToast.makeText(activity, activity.getString(R.string.tv_blacklist_channel_unhidden, label), Toast.LENGTH_SHORT).show()
                    } else {
                        TvBlacklist.blockChannel(activity, stream.channelId, stream.channelLogin, stream.channelName, stream.channelImageURL)
                        TvToast.makeText(activity, activity.getString(R.string.tv_blacklist_channel_hidden, label), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (!stream.gameName.isNullOrBlank()) {
                val gName = stream.gameName!!
                val catBlocked = TvBlacklist.isCategoryBlocked(stream.gameId, stream.gameSlug, gName)
                entries += Extra(
                    activity.getString(
                        if (catBlocked) R.string.tv_blacklist_unhide_category else R.string.tv_blacklist_hide_category,
                        gName,
                    ),
                    R.drawable.tv_ic_block,
                ) {
                    if (catBlocked) {
                        TvBlacklist.unblockCategory(activity, stream.gameId, stream.gameSlug, gName)
                        TvToast.makeText(activity, activity.getString(R.string.tv_blacklist_category_unhidden, gName), Toast.LENGTH_SHORT).show()
                    } else {
                        TvBlacklist.blockCategory(activity, stream.gameId, stream.gameSlug, gName)
                        TvToast.makeText(activity, activity.getString(R.string.tv_blacklist_category_hidden, gName), Toast.LENGTH_SHORT).show()
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
