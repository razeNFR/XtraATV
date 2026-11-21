package com.github.andreyasadchy.xtra.ui.tv

import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import kotlinx.coroutines.launch

/**
 * TV: the one panel of the actions of a video, the same on the home ("Continue watching", videos of the followed
 * channels) and on every list of videos (followed, search, channel, category): resume, watch from the start, remove
 * from the history, the channel, the category, then the actions of the list (bookmark, download).
 */
object TvVideoActions {

    /**
     * [positionMs]: where the video was left, null or 0 when it was never started. [finished]: it was watched to its
     * end, it starts again from the beginning.
     */
    fun show(
        activity: MainActivity,
        video: Video,
        positionMs: Long?,
        finished: Boolean = false,
        showChannel: Boolean = true,
        showGame: Boolean = false,
        extras: List<TvActionPanel.Action> = emptyList(),
        onHistoryRemoved: (() -> Unit)? = null,
    ) {
        val name = video.channelName ?: video.channelLogin ?: ""
        val started = positionMs != null && positionMs > 0L
        val actions = buildList {
            if (started && !finished) {
                add(TvActionPanel.Action(activity.getString(R.string.tv_resume), R.drawable.tv_ic_play) {
                    activity.startVideo(video, positionMs)
                })
                add(TvActionPanel.Action(activity.getString(R.string.tv_watch_from_start), R.drawable.tv_ic_replay) {
                    activity.startVideo(video, 0, ignoreSavedPosition = true)
                })
            } else {
                add(TvActionPanel.Action(activity.getString(R.string.tv_multistream_watch), R.drawable.tv_ic_play) {
                    activity.startVideo(video, if (finished) 0L else null, ignoreSavedPosition = finished)
                })
            }
            val id = video.id
            if (started && !id.isNullOrBlank()) {
                add(TvActionPanel.Action(activity.getString(R.string.tv_remove_from_history), R.drawable.tv_ic_delete) {
                    TvResumeHistory.remove(activity, id)
                    id.toLongOrNull()?.let { number ->
                        activity.lifecycleScope.launch {
                            try {
                                (activity.application as XtraApp).xtraModule.playerRepository.saveVideoPosition(VideoPosition(number, 0))
                            } catch (e: Exception) {

                            }
                            onHistoryRemoved?.invoke()
                        }
                    } ?: onHistoryRemoved?.invoke()
                })
            }
            if (showChannel && (!video.channelLogin.isNullOrBlank() || !video.channelId.isNullOrBlank())) {
                add(TvActionPanel.Action(activity.getString(R.string.tv_open_channel, name), R.drawable.tv_ic_person) {
                    activity.openTvChannel(
                        Stream(
                            channelId = video.channelId,
                            channelLogin = video.channelLogin,
                            channelName = video.channelName,
                            channelImageURL = video.channelImageURL,
                        ),
                        null
                    )
                })
            }
            if (showGame && !video.gameName.isNullOrBlank()) {
                add(TvActionPanel.Action(activity.getString(R.string.tv_open_category, video.gameName), R.drawable.tv_ic_gamepad) {
                    activity.openTvGame(video.gameId, video.gameSlug, video.gameName)
                })
            }
            addAll(extras)
        }
        TvActionPanel.show(activity, video.title?.trim()?.takeIf { it.isNotEmpty() } ?: name, actions)
    }
}
