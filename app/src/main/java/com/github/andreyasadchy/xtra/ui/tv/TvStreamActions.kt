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

    class Extra(val label: String, val run: () -> Unit)

    fun show(activity: MainActivity, stream: Stream, first: Extra? = null, last: List<Extra> = emptyList()) {
        val channelId = stream.channelId
        activity.lifecycleScope.launch {
            // the follow entry says what it does: the state is asked first, never for long
            val following = if (channelId != null && TvFollow.isEnabled(activity)) {
                withTimeoutOrNull(1500L) { TvFollow.isFollowing(activity, channelId) }
            } else null
            val entries = mutableListOf<Extra>()
            first?.let { entries += it }
            val name = stream.channelName ?: stream.channelLogin ?: ""
            entries += Extra(activity.getString(R.string.tv_open_channel, name)) { activity.openTvChannel(stream, null) }
            entries += Extra(activity.getString(R.string.tv_channel_videos)) { activity.openTvChannel(stream, "1") }
            if (!stream.gameName.isNullOrBlank()) {
                entries += Extra(activity.getString(R.string.tv_open_category, stream.gameName)) { activity.openTvGame(stream.gameId, stream.gameSlug, stream.gameName) }
            }
            if (channelId != null && following != null) {
                entries += Extra(activity.getString(if (following) R.string.unfollow else R.string.follow)) {
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
            entries += last
            TvDialogs.showChoices(activity, stream.title?.trim()?.takeIf { it.isNotEmpty() } ?: name, entries.map { it.label }, -1) {
                entries[it].run()
            }
        }
    }
}
