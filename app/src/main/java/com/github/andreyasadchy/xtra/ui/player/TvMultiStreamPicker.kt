package com.github.andreyasadchy.xtra.ui.player

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.launch

/** TV: choice of up to 4 streams (followed channels live and streams of the list) for the multi-stream view, in the row of the other streams. */
object TvMultiStreamPicker {

    private const val MAX = 4

    fun show(activity: MainActivity, current: Stream?) {
        activity.lifecycleScope.launch {
            val candidates = (listOfNotNull(current) + loadFollowed(activity) + activity.streamQueue)
                .filter { !it.channelLogin.isNullOrBlank() }
                .distinctBy { it.channelLogin }
            if (candidates.isEmpty()) {
                return@launch
            }
            // the row of the other streams, in selection mode: OK checks a stream (green badge), "Validate" starts the multi-stream
            TvStreamSwitcher.show(
                activity,
                candidates,
                current?.channelLogin,
                TvStreamSwitcher.Selection(
                    title = activity.getString(R.string.tv_multistream_select_title, MAX),
                    preselected = setOfNotNull(current?.channelLogin),
                    max = MAX,
                    onValidate = { selected -> activity.startMultiStream(selected) },
                )
            )
        }
    }

    /** One stream to pick (change a stream of the multi-stream or add one), the ones already shown are left out. */
    fun pickOne(activity: MainActivity, exclude: Set<String>, title: String, onPicked: (Stream) -> Unit) {
        activity.lifecycleScope.launch {
            val candidates = (loadFollowed(activity) + activity.streamQueue)
                .filter { !it.channelLogin.isNullOrBlank() && it.channelLogin !in exclude }
                .distinctBy { it.channelLogin }
            if (candidates.isEmpty()) {
                Toast.makeText(activity, R.string.tv_multistream_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            TvStreamSwitcher.show(
                activity,
                candidates,
                null,
                TvStreamSwitcher.Selection(title = title, preselected = emptySet(), max = 1, onValidate = { it.firstOrNull()?.let(onPicked) })
            )
        }
    }

    private suspend fun loadFollowed(activity: MainActivity): List<Stream> {
        return try {
            val headers = TwitchApiHelper.getGQLHeaders(activity, true)
            if (headers[C.HEADER_TOKEN].isNullOrBlank()) {
                emptyList()
            } else {
                (activity.applicationContext as XtraApp).xtraModule.graphQLRepository.loadQueryUserFollowedStreams(
                    activity.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), headers, 100, null
                ).data?.user?.followedLiveUsers?.edges?.mapNotNull { edge ->
                    edge?.node?.let {
                        Stream(
                            id = it.stream?.id,
                            channelId = it.id,
                            channelLogin = it.login,
                            channelName = it.displayName,
                            channelImageURL = it.profileImageURL,
                            gameName = it.stream?.game?.displayName,
                            title = it.stream?.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = it.stream?.previewImageURL,
                            viewerCount = it.stream?.viewersCount,
                        )
                    }
                }.orEmpty()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
