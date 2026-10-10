package com.github.andreyasadchy.xtra.ui.player

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.launch

/** TV: choice of up to 4 streams (followed channels live and streams of the current category) for the multi-stream view. */
object TvMultiStreamPicker {

    private const val MAX = 4

    fun show(activity: MainActivity, current: Stream?) {
        activity.lifecycleScope.launch {
            val followed = try {
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
            val candidates = (listOfNotNull(current) + followed + activity.streamQueue)
                .filter { !it.channelLogin.isNullOrBlank() }
                .distinctBy { it.channelLogin }
            if (candidates.isEmpty()) {
                return@launch
            }
            val checked = BooleanArray(candidates.size) { it == 0 && current != null }
            val labels = candidates.map { stream ->
                listOfNotNull(stream.channelName ?: stream.channelLogin, stream.gameName).joinToString(" · ")
            }.toTypedArray()
            activity.getAlertDialogBuilder()
                .setTitle(R.string.tv_multistream_pick)
                .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                    if (isChecked && checked.count { it } > MAX) {
                        checked[which] = false
                        (dialog as? androidx.appcompat.app.AlertDialog)?.listView?.setItemChecked(which, false)
                        Toast.makeText(activity, activity.getString(R.string.tv_multistream_max, MAX), Toast.LENGTH_SHORT).show()
                    } else {
                        checked[which] = isChecked
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.tv_multistream_watch) { _, _ ->
                    val selected = candidates.filterIndexed { index, _ -> checked[index] }
                    if (selected.isNotEmpty()) {
                        activity.startMultiStream(selected)
                    }
                }
                .show()
        }
    }
}
