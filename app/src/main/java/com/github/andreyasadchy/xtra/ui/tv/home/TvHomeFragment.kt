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
import com.github.andreyasadchy.xtra.util.tokenPrefs
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
    /** Time "refresh" was pressed (0: the rows load by themselves), for its animation and its message. */
    private var refreshClickedAt = 0L
    private val homeSweep = androidx.compose.runtime.mutableIntStateOf(0)
    private val resumeItems = mutableStateOf<List<com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item>>(emptyList())
    private val videoPositions = mutableStateOf<Map<String, Long>>(emptyMap())
    private val statsState = mutableStateOf<TvHomeStats?>(null)
    /** Kept while the app runs: the home is made again each time its section is opened from the side menu. */
    private val homeFocus get() = sharedFocus

    /** Coming from the side menu: the first stream of the first row. */
    fun focusFirstCard() {
        homeFocus.focusFirst()
    }

    /** The player closed: the card of the stream watched, else the first stream. */
    fun focusLastCard() {
        homeFocus.focusLast()
    }
    /** Picture of the focused stream behind the rows. */

    init {
        com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.register(this)
    }

    companion object {
        private val sharedFocus = TvHomeFocus()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        if (homeFocus.lastRow != null && (activity as? MainActivity)?.isTvMenuOpen() != true) {
            homeFocus.focusLast()
        }
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF9147FF.toInt())
        val truncate = context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)
        val positionOf: (com.github.andreyasadchy.xtra.model.ui.Video) -> Long? = { video ->
            val id = video.id
            if (id.isNullOrBlank()) null
            else (resumeItems.value.find { it.id == id }?.positionMs ?: videoPositions.value[id])?.takeIf { it > 0L }
        }
        val actions = TvHomeActions(
            openStream = { stream, list ->
                stopPreview()
                // the player loads the row again from time to time: the other streams and left / right stay up to date
                val source: (() -> androidx.paging.PagingSource<Int, Stream>)? = when {
                    list === viewModel.followed.value -> viewModel::createFollowedSource
                    list === viewModel.popular.value -> viewModel::createPopularSource
                    else -> viewModel.languageRows.value.find { it.second === list }?.first?.let { language -> { viewModel.createLanguageSource(language) } }
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
            onStreamUnfocused = { stream ->
                // only the preview of this card: the focus may already be on the next stream card
                if (scheduledLogin == stream.channelLogin) {
                    stopPreview()
                }
            },
            onStreamFocused = { stream ->
                schedulePreview(stream)
                stream?.let { s ->
                    context?.let { ctx ->
                        com.github.andreyasadchy.xtra.ui.tv.TvAmbient.updateFromUrl(
                            ctx,
                            s.thumbnail?.takeUnless { s.thumbnailURL.isNullOrBlank() } ?: s.channelImage,
                            viewLifecycleOwner.lifecycleScope,
                        )
                    }
                }
            },
            openChannel = { stream ->
                stopPreview()
                (activity as? MainActivity)?.openTvChannel(stream, null)
            },
            onHeroUnfocused = {
                // only the video of the banner: the focus may already be on a card, with its own preview
                if (scheduledMuted) {
                    stopPreview()
                }
            },
            onHeroFocused = { stream ->
                // the stream plays in the banner while it has the focus, always without sound
                schedulePreview(stream, muted = true)
                context?.let { ctx ->
                    com.github.andreyasadchy.xtra.ui.tv.TvAmbient.updateFromUrl(
                        ctx,
                        stream.thumbnail?.takeUnless { stream.thumbnailURL.isNullOrBlank() } ?: stream.channelImage,
                        viewLifecycleOwner.lifecycleScope,
                    )
                }
            },
            formatViewers = { count -> resources.getQuantityString(R.plurals.viewers, count, TwitchApiHelper.formatCount(count, truncate)) },
            formatCount = { count -> TwitchApiHelper.formatCount(count, true) },
            refresh = {
                stopPreview()
                refreshClickedAt = android.os.SystemClock.elapsedRealtime()
                homeRefreshing.value = true
                viewModel.load(force = true)
            },
            cardsPerRow = context.prefs().getString(C.TV_HOME_CARDS, "3")?.toIntOrNull() ?: 3,
            thumbnailStamp = { homeStamp.longValue },
            refreshing = { homeRefreshing.value },
            sweepCount = { homeSweep.intValue },
            statsBadges = { statsBadges() },
            openStats = {
                activity?.let { com.github.andreyasadchy.xtra.ui.tv.TvStatsPage.show(it) { loadStats() } }
            },
            uptime = { stream ->
                context.takeIf { it.prefs().getBoolean(C.UI_UPTIME, true) }?.let { com.github.andreyasadchy.xtra.ui.tv.TvUptime.format(it, stream.createdAt) }
            },
            openVideo = { item ->
                stopPreview()
                (activity as? MainActivity)?.startVideo(item.toVideo(), item.positionMs)
            },
            showVideoActions = { item -> showVideoActions(item) },
            openFollowedVideo = { video ->
                stopPreview()
                val pos = positionOf(video)
                val durationMs = (video.durationSeconds ?: 0) * 1000L
                val startFromBeginning = pos != null && durationMs > 0L && pos >= durationMs
                (activity as? MainActivity)?.startVideo(video, if (startFromBeginning) 0L else pos, startFromBeginning)
            },
            showFollowedVideoActions = { video ->
                showVideoActions(video.toResumeItem(positionOf(video) ?: 0L))
            },
            videoPosition = positionOf,
            formatVideoDuration = { seconds -> android.text.format.DateUtils.formatElapsedTime(seconds.toLong()) },
            formatDuration = { ms -> getString(R.string.tv_time_left, android.text.format.DateUtils.formatElapsedTime(ms / 1000)) },
        )
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stamp.collect { homeStamp.longValue = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            (requireActivity().application as XtraApp).xtraModule.playerRepository.loadVideoPositions().collect { list ->
                videoPositions.value = list.associate { it.id.toString() to it.position }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.refreshing.collect { refreshing ->
                if (refreshing) {
                    homeRefreshing.value = true
                } else if (refreshClickedAt > 0L) {
                    // refreshed with the button: its icon turns at least a moment, then a short message says it is done
                    val wait = 800L - (android.os.SystemClock.elapsedRealtime() - refreshClickedAt)
                    refreshClickedAt = 0L
                    if (wait > 0L) kotlinx.coroutines.delay(wait)
                    homeRefreshing.value = false
                    homeSweep.intValue++
                    context?.let { android.widget.Toast.makeText(it, R.string.tv_refreshed, android.widget.Toast.LENGTH_SHORT).show() }
                } else {
                    homeRefreshing.value = false
                }
            }
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
        val showHero = context.prefs().getBoolean(C.TV_HOME_HERO, true)
        return ComposeView(context).apply {
            id = R.id.tvHome
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val followed by viewModel.followed.collectAsState()
                val followedVideos by viewModel.followedVideos.collectAsState()
                val popular by viewModel.popular.collectAsState()
                val games by viewModel.games.collectAsState()
                val languageRows by viewModel.languageRows.collectAsState()
                val accountImage by viewModel.accountImage.collectAsState()
                val heroQueue = if (showHero) {
                    followed?.takeIf { it.isNotEmpty() } ?: popular?.takeIf { it.isNotEmpty() } ?: emptyList()
                } else emptyList()
                val heroStream = heroQueue.firstOrNull()
                androidx.compose.runtime.LaunchedEffect(heroStream?.channelLogin) {
                    // only until a first picture got the focus: after that the color follows the focus, a row that
                    // loads again does not change it under a card
                    if (heroStream != null && !com.github.andreyasadchy.xtra.ui.tv.TvAmbient.hasSessionColor) {
                        com.github.andreyasadchy.xtra.ui.tv.TvAmbient.updateFromUrl(
                            context,
                            heroStream.thumbnail?.takeUnless { heroStream.thumbnailURL.isNullOrBlank() } ?: heroStream.channelImage,
                            viewLifecycleOwner.lifecycleScope,
                        )
                    }
                }
                androidx.compose.runtime.LaunchedEffect(followed) {
                    followed?.let { list ->
                        com.github.andreyasadchy.xtra.ui.tv.TvLauncherChannels.syncFollowedLive(context, list)
                    }
                }
                TvTheme(accent) {
                    TvHomeScreen(
                        followed = followed?.let { TvHomeRow(getString(R.string.tv_followed_live), it, followedId, R.drawable.tv_ic_heart) },
                        popular = popular?.let { TvHomeRow(getString(R.string.popular), it, R.id.rootTopFragment, R.drawable.tv_ic_trending) },
                        games = games?.let { TvHomeRow(getString(R.string.games), it, R.id.rootGamesFragment, R.drawable.tv_ic_gamepad) },
                        previewLogin = { previewLogin.value },
                        previewPlayer = { previewPlayer.value },
                        actions = actions,
                        hero = heroStream,
                        heroQueue = heroQueue,
                        resume = resumeItems.value.takeIf { it.isNotEmpty() }?.let { TvHomeRow(getString(R.string.tv_continue_watching), it, 0, R.drawable.tv_ic_history) },
                        // the videos still recording (the live of a channel live now) are left out: the live is in the row above
                        videos = followedVideos?.takeIf { context.prefs().getBoolean(C.TV_HOME_FOLLOWED_VIDEOS, true) }
                            ?.filterNot { video -> isRecording(video, followed) }
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { TvHomeRow(getString(R.string.tv_followed_videos_row), it, 0, R.drawable.tv_ic_video_library) },
                        stats = statsState.value,
                        languageRows = languageRows.map { (language, streams) ->
                            TvHomeRow(getString(R.string.tv_popular_in, languageName(language)), streams, 0, R.drawable.tv_ic_globe)
                        },
                        focus = homeFocus,
                        account = TvHomeAccount(
                            name = context.tokenPrefs().getString(C.USERNAME, null)?.takeIf { it.isNotBlank() },
                            image = accountImage,
                        ),
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

    /** Name of a language in the language of the app ("fr" -> "Français"). */
    private fun languageName(code: String): String {
        val name = java.util.Locale.forLanguageTag(code).getDisplayLanguage(resources.configuration.locales[0])
        return name.takeIf { it.isNotBlank() && it != code }?.replaceFirstChar { it.titlecase(resources.configuration.locales[0]) } ?: code.uppercase()
    }

    /** "Continue watching" and the statistics, read on this device. */
    private fun loadLocalRows() {
        val context = context ?: return
        resumeItems.value = com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.list(context)
        com.github.andreyasadchy.xtra.ui.tv.TvLauncherChannels.syncWatchNext(context)
        loadStats()
    }

    /** The statistics of the badge of the top of the home, the ones that have something to say. */
    private fun statsBadges(): List<Pair<Int, String>> {
        val context = context ?: return emptyList()
        val today = com.github.andreyasadchy.xtra.ui.tv.TvStats.detail(context, 1)
        val week = com.github.andreyasadchy.xtra.ui.tv.TvStats.detail(context, 7)
        val time = { ms: Long -> getString(R.string.tv_stats_hours, ms / 3_600_000L, (ms / 60_000L) % 60) }
        return listOfNotNull(
            today.watchMs.takeIf { it >= 60_000L }?.let { R.drawable.tv_ic_schedule to getString(R.string.tv_badge_today, time(it)) },
            today.currentStreak.takeIf { it >= 2 }?.let { R.drawable.tv_ic_trending to getString(R.string.tv_badge_streak, it) },
            week.streamers.firstOrNull()?.let { R.drawable.tv_ic_star to getString(R.string.tv_badge_top_week, it.name) },
            week.ads.takeIf { it > 0 }?.let { R.drawable.tv_ic_shield to getString(R.string.tv_badge_ads_week, it) },
            today.streams.takeIf { it > 0 }?.let { R.drawable.tv_ic_live to getString(R.string.tv_badge_streams_today, it) },
        ).ifEmpty { listOf(R.drawable.tv_ic_bar_chart to getString(R.string.tv_stats)) }
    }

    /** A video of a channel live now, started with that live (or still without a picture): it records it. */
    private fun isRecording(video: com.github.andreyasadchy.xtra.model.ui.Video, live: List<com.github.andreyasadchy.xtra.model.ui.Stream>?): Boolean {
        val stream = live?.find { it.channelLogin != null && it.channelLogin.equals(video.channelLogin, true) } ?: return false
        if (com.github.andreyasadchy.xtra.ui.tv.TvVideoThumbnail.isMissing(video.thumbnailURL)) {
            return true
        }
        val videoStart = video.createdAt?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: return false
        val streamStart = stream.createdAt?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: return false
        return videoStart >= streamStart - 15 * 60_000L
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

    private fun com.github.andreyasadchy.xtra.model.ui.Video.toResumeItem(positionMs: Long) = com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item(
        id = id.orEmpty(),
        title = title,
        channelId = channelId,
        channelLogin = channelLogin,
        channelName = channelName,
        channelImage = channelImage,
        gameId = gameId,
        gameName = gameName,
        thumbnail = thumbnail,
        createdAt = createdAt,
        durationSeconds = durationSeconds,
        positionMs = positionMs,
        type = type,
    )

    /** OK held down on a video of "Continue watching" or "Followed videos": resume, watch from the start, remove from the history, open channel. */
    private fun showVideoActions(item: com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item) {
        stopPreview()
        val activity = activity as? MainActivity ?: return
        // the same panel as on the lists of videos
        com.github.andreyasadchy.xtra.ui.tv.TvVideoActions.show(activity, item.toVideo(), item.positionMs, onHistoryRemoved = { loadLocalRows() })
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    /** Live preview in the focused card after a short delay, like on the other lists. */
    private fun schedulePreview(stream: Stream?, muted: Boolean = false) {
        val login = stream?.channelLogin
        if (login == previewLogin.value && login != null && muted == previewMuted) {
            return
        }
        stopPreview()
        scheduledLogin = login
        scheduledMuted = muted
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
            player.volume = if (!muted && com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.hasSound(context)) 1f else 0f
            player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            player.prepare()
            player.playWhenReady = true
            previewLogin.value = login
            previewMuted = muted
            if (!muted) {
                com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.notifyPreviewSound(context, true)
            }
        }
    }

    /** Stream whose preview is loading or playing. */
    private var scheduledLogin: String? = null
    /** The preview playing is the one of the banner of the home: no sound, the stream kept behind is not lowered. */
    private var previewMuted = false
    private var scheduledMuted = false

    override fun stopPreview() {
        scheduledLogin = null
        scheduledMuted = false
        previewJob?.cancel()
        previewJob = null
        if (previewLogin.value != null && !previewMuted) {
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
