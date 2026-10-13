package com.github.andreyasadchy.xtra.ui.tv.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvTheme
import com.github.andreyasadchy.xtra.ui.tv.home.TvHomeViewModel.Companion.TvHomeViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** TV home written with Compose: rows of cards like the YouTube app, the focus is handled by Compose. */
class TvHomeFragment : Fragment(), com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.Previewer {

    private val viewModel: TvHomeViewModel by viewModels { TvHomeViewModelFactory }
    private val previewLogin = mutableStateOf<String?>(null)
    private val previewPlayer = mutableStateOf<ExoPlayer?>(null)
    private var previewJob: Job? = null
    // snapshot states: the cards that read them follow their changes
    private val homeStamp = androidx.compose.runtime.mutableLongStateOf(0L)
    private val homeRefreshing = mutableStateOf(false)
    private val resumeItems = mutableStateOf<List<com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item>>(emptyList())
    private val statsState = mutableStateOf<TvHomeStats?>(null)
    /** Picture of the focused stream behind the rows. */

    init {
        com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.register(this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF9147FF.toInt())
        val truncate = context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)
        val actions = TvHomeActions(
            openStream = { stream, list ->
                stopPreview()
                // the player loads the row again from time to time: the other streams and left / right stay up to date
                val source: (() -> androidx.paging.PagingSource<Int, Stream>)? = when {
                    list === viewModel.followed.value -> viewModel::createFollowedSource
                    list === viewModel.popular.value -> viewModel::createPopularSource
                    else -> null
                }
                (activity as? MainActivity)?.startStream(stream, list, source)
            },
            showActions = { stream ->
                stopPreview()
                (activity as? MainActivity)?.showTvStreamActions(stream)
            },
            openGame = { game ->
                stopPreview()
                findNavController().navigate(
                    if (context.prefs().getBoolean(C.UI_GAME_PAGER, true)) {
                        GamePagerFragmentDirections.actionGlobalGamePagerFragment(gameId = game.id, gameSlug = game.slug, gameName = game.name)
                    } else {
                        GameMediaFragmentDirections.actionGlobalGameMediaFragment(gameId = game.id, gameSlug = game.slug, gameName = game.name)
                    }
                )
            },
            seeAll = { id ->
                stopPreview()
                (activity as? MainActivity)?.openTvSection(id)
            },
            openMenu = {
                stopPreview()
                (activity as? MainActivity)?.openTvMenu()
            },
            onStreamFocused = {
                schedulePreview(it)
            },
            formatViewers = { count -> resources.getQuantityString(R.plurals.viewers, count, TwitchApiHelper.formatCount(count, truncate)) },
            formatCount = { count -> TwitchApiHelper.formatCount(count, true) },
            refresh = {
                stopPreview()
                viewModel.load(force = true)
            },
            cardsPerRow = context.prefs().getString(C.TV_HOME_CARDS, "3")?.toIntOrNull() ?: 3,
            thumbnailStamp = { homeStamp.longValue },
            refreshing = { homeRefreshing.value },
            openVideo = { item ->
                stopPreview()
                (activity as? MainActivity)?.startVideo(item.toVideo(), item.positionMs)
            },
            showVideoActions = { item -> showVideoActions(item) },
            formatDuration = { ms -> getString(R.string.tv_time_left, android.text.format.DateUtils.formatElapsedTime(ms / 1000)) },
        )
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stamp.collect { homeStamp.longValue = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.refreshing.collect { homeRefreshing.value = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // a video watched in the mini player over the home comes in "Continue watching" right away
            com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.changes.collect {
                context?.let { resumeItems.value = com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.list(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // the statistics follow what is watched in the mini player over the home
            com.github.andreyasadchy.xtra.ui.tv.TvStats.changes.collect { loadStats() }
        }
        val followedId = if (context.prefs().getBoolean(C.UI_FOLLOW_PAGER, true)) R.id.followPagerFragment else R.id.followMediaFragment
        return ComposeView(context).apply {
            id = R.id.tvHome
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val followed by viewModel.followed.collectAsState()
                val popular by viewModel.popular.collectAsState()
                val games by viewModel.games.collectAsState()
                TvTheme(accent) {
                    TvHomeScreen(
                        followed = followed?.let { TvHomeRow(getString(R.string.tv_followed_live), it, followedId, R.drawable.tv_ic_heart) },
                        popular = popular?.let { TvHomeRow(getString(R.string.popular), it, R.id.rootTopFragment, R.drawable.tv_ic_trending) },
                        games = games?.let { TvHomeRow(getString(R.string.games), it, R.id.rootGamesFragment, R.drawable.tv_ic_gamepad) },
                        previewLogin = { previewLogin.value },
                        previewPlayer = { previewPlayer.value },
                        actions = actions,
                        resume = resumeItems.value.takeIf { it.isNotEmpty() }?.let { TvHomeRow(getString(R.string.tv_continue_watching), it, 0, R.drawable.tv_ic_history) },
                        stats = statsState.value,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
        loadLocalRows()
    }

    /** "Continue watching" and the statistics, read on this device. */
    private fun loadLocalRows() {
        val context = context ?: return
        resumeItems.value = com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.list(context)
        loadStats()
    }

    private fun loadStats() {
        val context = context ?: return
        val stats = com.github.andreyasadchy.xtra.ui.tv.TvStats.read(context)
        val hours = stats.watchMs / 3_600_000L
        val minutes = (stats.watchMs / 60_000L) % 60
        val formatTime = { ms: Long -> getString(R.string.tv_stats_hours, ms / 3_600_000L, (ms / 60_000L) % 60) }
        statsState.value = TvHomeStats(
            title = getString(R.string.tv_stats),
            subtitle = stats.since.takeIf { it > 0 }?.let {
                getString(R.string.tv_stats_since, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)))
            },
            items = listOf(
                TvHomeStat(R.drawable.tv_ic_schedule, getString(R.string.tv_stats_hours, hours, minutes), getString(R.string.tv_stats_watched)),
                TvHomeStat(
                    R.drawable.tv_ic_star,
                    stats.topStreamer ?: "—",
                    if (stats.topStreamer != null) getString(R.string.tv_stats_top_streamer_time, formatTime(stats.topStreamerMs)) else getString(R.string.tv_stats_top_streamer)
                ),
                TvHomeStat(R.drawable.tv_ic_shield, stats.ads.toString(), getString(R.string.tv_stats_ads)),
                TvHomeStat(
                    R.drawable.tv_ic_globe,
                    stats.topProxy ?: "—",
                    if (stats.topProxy != null) getString(R.string.tv_stats_proxy_uses, stats.topProxyUses) else getString(R.string.tv_stats_proxy)
                ),
                TvHomeStat(R.drawable.tv_ic_forum, stats.messages.toString(), getString(R.string.tv_stats_messages)),
                TvHomeStat(R.drawable.tv_ic_live, stats.streams.toString(), getString(R.string.tv_stats_streams)),
            )
        )
    }

    private fun com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item.toVideo() = com.github.andreyasadchy.xtra.model.ui.Video(
        id = id,
        channelId = channelId,
        channelLogin = channelLogin,
        channelName = channelName,
        channelImageURL = channelImage,
        gameId = gameId,
        gameName = gameName,
        title = title,
        thumbnailURL = thumbnail,
        createdAt = createdAt,
        durationSeconds = durationSeconds,
        type = type,
    )

    /** OK held down on a video of "Continue watching": resume, watch from the start, remove from the history. */
    private fun showVideoActions(item: com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item) {
        stopPreview()
        val activity = activity as? MainActivity ?: return
        com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.show(
            activity,
            item.title?.trim()?.takeIf { it.isNotEmpty() } ?: item.channelName ?: "",
            listOf(
                com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(getString(R.string.tv_resume), R.drawable.tv_ic_play) {
                    activity.startVideo(item.toVideo(), item.positionMs)
                },
                com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(getString(R.string.tv_watch_from_start), R.drawable.tv_ic_replay) {
                    activity.startVideo(item.toVideo(), 0, ignoreSavedPosition = true)
                },
                com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(getString(R.string.tv_remove_from_history), R.drawable.tv_ic_delete) {
                    com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.remove(activity, item.id)
                    item.id.toLongOrNull()?.let { id ->
                        activity.lifecycleScope.launch {
                            try {
                                (activity.application as com.github.andreyasadchy.xtra.XtraApp).xtraModule.playerRepository.saveVideoPosition(
                                    com.github.andreyasadchy.xtra.model.VideoPosition(id, 0)
                                )
                            } catch (e: Exception) {

                            }
                        }
                    }
                    loadLocalRows()
                },
            )
        )
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    /** Live preview in the focused card after a short delay, like on the other lists. */
    private fun schedulePreview(stream: Stream?) {
        val login = stream?.channelLogin
        if (login == previewLogin.value && login != null) {
            return
        }
        stopPreview()
        val context = context ?: return
        if (login.isNullOrBlank() || !context.prefs().getBoolean(C.UI_TV_PREVIEW, true) || com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.suspended) {
            return
        }
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.START_DELAY)
            val url = com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.getPlaylistUrl(context, login) ?: return@launch
            if (com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.suspended) {
                return@launch
            }
            val player = previewPlayer.value ?: com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.createPlayer(context).also {
                previewPlayer.value = it
            }
            player.volume = if (com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.hasSound(context)) 1f else 0f
            player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            player.prepare()
            player.playWhenReady = true
            previewLogin.value = login
            com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.notifyPreviewSound(context, true)
        }
    }

    override fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        if (previewLogin.value != null) {
            context?.let { com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.notifyPreviewSound(it, false) }
        }
        previewLogin.value = null
        previewPlayer.value?.stop()
    }

    override fun onDestroyView() {
        stopPreview()
        previewPlayer.value?.release()
        previewPlayer.value = null
        super.onDestroyView()
    }
}
