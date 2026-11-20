package com.github.andreyasadchy.xtra.ui.player

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import kotlinx.coroutines.launch

/** TV: choice of up to 6 streams (followed channels live and streams of the list) for the multi-stream view, in the row of the other streams. */
object TvMultiStreamPicker {

    const val MAX = 6

    fun show(activity: MainActivity, current: Stream?) {
        val groups = com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.list(activity)
        val coStreamers = if (current != null && !current.channelLogin.isNullOrBlank() &&
            current.channelLogin.equals(activity.currentPlayingStream()?.channelLogin, ignoreCase = true)
        ) {
            (activity.playerFragment as? PlayerFragment)?.getCoStreamers().orEmpty()
                .filter { !it.channelLogin.isNullOrBlank() && !it.channelLogin.equals(current.channelLogin, ignoreCase = true) }
                .distinctBy { it.channelLogin?.lowercase() }
                .take(MAX - 1)
        } else {
            emptyList()
        }
        if (groups.isEmpty() && coStreamers.isEmpty()) {
            openPicker(activity, current, emptyList())
            return
        }
        val actions = mutableListOf<com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action>()
        if (coStreamers.isNotEmpty() && current != null) {
            val allCoStreams = (listOf(current) + coStreamers).take(MAX)
            actions += com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                activity.getString(R.string.tv_costream_watch_multistream, allCoStreams.size),
                R.drawable.tv_ic_streamers,
            ) {
                activity.startMultiStream(allCoStreams, current)
            }
        }
        actions += com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
            activity.getString(R.string.tv_multistream_new),
            R.drawable.tv_ic_add,
        ) {
            openPicker(activity, current, coStreamers)
        }
        groups.forEach { group ->
            actions += com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                group.label,
                R.drawable.tv_ic_multistream,
            ) {
                com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.launchGroup(activity, group, current)
            }
        }
        if (groups.isNotEmpty()) {
            actions += com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                activity.getString(R.string.tv_multistream_remove_group),
                R.drawable.tv_ic_delete,
            ) {
                val deleteActions = groups.map { group ->
                    com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(group.label, R.drawable.tv_ic_delete) {
                        com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.remove(activity, group.id)
                        com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(activity, R.string.tv_multistream_group_removed, Toast.LENGTH_SHORT).show()
                    }
                }
                com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.show(
                    activity,
                    activity.getString(R.string.tv_multistream_remove_group),
                    deleteActions,
                )
            }
        }
        com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.show(
            activity,
            activity.getString(R.string.tv_multistream),
            actions,
        )
    }

    private fun openPicker(activity: MainActivity, current: Stream?, coStreamers: List<Stream> = emptyList()) {
        activity.lifecycleScope.launch {
            val gameSource = current?.takeIf { !it.gameName.isNullOrBlank() || !it.gameId.isNullOrBlank() || !it.gameSlug.isNullOrBlank() }?.let {
                { com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.game(activity, it.gameId, it.gameSlug, it.gameName) }
            }
            val followed = loadFollowed(activity)
            val sameGame = gameSource?.let { com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.load(it()) }.orEmpty()
                .sortedByDescending { it.viewerCount ?: 0 }
            // Suggested: current + co-streamers first, then followed channels live, then streams of the same category sorted by viewers and the list of the stream
            val candidates = (listOfNotNull(current) + coStreamers + followed + sameGame + activity.liveStreamsOnly(activity.streamQueue))
                .filter { !it.channelLogin.isNullOrBlank() }
                .distinctBy { it.channelLogin?.lowercase() }
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
                    onValidate = { selected -> activity.startMultiStream(selected, current) },
                ),
                // streams can be picked in the other lists too, the checked ones are kept from one tab to the other
                rows = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.rows(activity, current),
                source = gameSource,
            )
        }
    }

    /** One stream to pick (change a stream of the multi-stream or add one), the ones already shown are left out. */
    fun pickOne(activity: MainActivity, exclude: Set<String>, title: String, current: Stream? = null, onPicked: (Stream) -> Unit) {
        activity.lifecycleScope.launch {
            val gameSource = current?.takeIf { !it.gameName.isNullOrBlank() || !it.gameId.isNullOrBlank() || !it.gameSlug.isNullOrBlank() }?.let {
                { com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.game(activity, it.gameId, it.gameSlug, it.gameName) }
            }
            val followed = loadFollowed(activity)
            val sameGame = gameSource?.let { com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.load(it()) }.orEmpty()
                .sortedByDescending { it.viewerCount ?: 0 }
            // the list of the stream may be old (not loaded again during a multi-stream): only the streamers still live
            var candidates = (followed + sameGame + activity.liveStreamsOnly(activity.streamQueue))
                .filter { !it.channelLogin.isNullOrBlank() && it.channelLogin !in exclude }
                .distinctBy { it.channelLogin }
            if (candidates.isEmpty()) {
                candidates = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.load(com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.popular(activity))
                    .orEmpty()
                    .filter { !it.channelLogin.isNullOrBlank() && it.channelLogin !in exclude }
                    .distinctBy { it.channelLogin }
            }
            if (candidates.isEmpty()) {
                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(activity, R.string.tv_multistream_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            TvStreamSwitcher.show(
                activity,
                candidates,
                null,
                TvStreamSwitcher.Selection(title = title, preselected = emptySet(), max = 1, onValidate = { it.firstOrNull()?.let(onPicked) }, exclude = exclude),
                rows = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.rows(activity, current),
                source = gameSource,
            )
        }
    }

    private suspend fun loadFollowed(activity: MainActivity): List<Stream> {
        return com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.loadPages(
            { com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.followed(activity) },
            100,
        ).orEmpty()
    }
}
