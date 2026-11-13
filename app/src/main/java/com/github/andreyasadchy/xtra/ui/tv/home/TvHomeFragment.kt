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
    private val showHeroState = mutableStateOf(true)
    private val showGreetingState = mutableStateOf(true)
    private val showStatsBadgeState = mutableStateOf(true)
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
                    else -> {
                        val langCode = viewModel.languageRows.value.entries.find { it.value === list }?.key
                        if (langCode != null) {
                            { viewModel.createLanguageSource(langCode) }
                        } else {
                            val catId = viewModel.categoryRows.value.entries.find { it.value === list }?.key
                            val spec = catId?.let { TvHomeRows.parseCategory(it) }
                            if (spec != null) {
                                { viewModel.createCategorySource(spec) }
                            } else null
                        }
                    }
                }
                (activity as? MainActivity)?.startStream(stream, list, source)
            },
            showActions = { stream ->
                stopPreview()
                (activity as? MainActivity)?.showTvStreamActions(stream)
            },
            openGame = { game ->
                stopPreview()
                navigateToGame(game)
            },
            showGameActions = { game ->
                showGameActions(game)
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
                        com.github.andreyasadchy.xtra.ui.tv.TvAmbient.updateFromItem(ctx, s, viewLifecycleOwner.lifecycleScope)
                    }
                }
            },
            openChannel = { stream ->
                stopPreview()
                (activity as? MainActivity)?.openTvChannel(stream, null)
            },
            onPictureFocused = { item ->
                // the other cards of the home (videos, clips, categories): the same dynamic ambiance as the streams
                context?.let { ctx -> com.github.andreyasadchy.xtra.ui.tv.TvAmbient.updateFromItem(ctx, item, viewLifecycleOwner.lifecycleScope) }
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
                    com.github.andreyasadchy.xtra.ui.tv.TvAmbient.updateFromItem(ctx, stream, viewLifecycleOwner.lifecycleScope)
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
            openClip = { clip, queue ->
                stopPreview()
                (activity as? MainActivity)?.startClip(clip, queue)
            },
            showClipActions = { clip, queue ->
                showClipActions(clip, queue)
            },
            openMultiStream = { item ->
                stopPreview()
                (activity as? MainActivity)?.let { act ->
                    com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.launchGroup(act, item.group)
                }
            },
            showMultiStreamActions = { item -> showMultiStreamActions(item) },
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
        syncHomePrefs(context)
        return ComposeView(context).apply {
            id = R.id.tvHome
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val showHero = showHeroState.value
                val showGreeting = showGreetingState.value
                val showStatsBadge = showStatsBadgeState.value
                val followed by viewModel.followed.collectAsState()
                val favorites by viewModel.favorites.collectAsState()
                val multiStreams by viewModel.multiStreams.collectAsState()
                val followedVideos by viewModel.followedVideos.collectAsState()
                val followedClips by viewModel.followedClips.collectAsState()
                val popular by viewModel.popular.collectAsState()
                val recommended by viewModel.recommended.collectAsState()
                val followedGames by viewModel.followedGames.collectAsState()
                val games by viewModel.games.collectAsState()
                val languageRows by viewModel.languageRows.collectAsState()
                val categoryRows by viewModel.categoryRows.collectAsState()
                val isRefreshing by viewModel.refreshing.collectAsState()
                val accountImage by viewModel.accountImage.collectAsState()
                val rowsTick by TvHomeRows.changes.collectAsState()
                val activeEntries = androidx.compose.runtime.remember(rowsTick) {
                    TvHomeRows.load(context).filter { it.enabled }
                }
                // the same picture at the top of the side menu
                androidx.compose.runtime.LaunchedEffect(accountImage) {
                    (activity as? MainActivity)?.setTvAccountImage(accountImage)
                }
                val heroQueue = if (showHero) {
                    followed?.takeIf { it.isNotEmpty() } ?: popular?.takeIf { it.isNotEmpty() } ?: emptyList()
                } else emptyList()
                val heroStream = heroQueue.firstOrNull()
                androidx.compose.runtime.LaunchedEffect(heroStream?.channelLogin) {
                    // only until a first picture got the focus: after that the color follows the focus, a row that
                    // loads again does not change it under a card
                    if (heroStream != null && !com.github.andreyasadchy.xtra.ui.tv.TvAmbient.hasSessionColor) {
                        com.github.andreyasadchy.xtra.ui.tv.TvAmbient.updateFromItem(context, heroStream, viewLifecycleOwner.lifecycleScope)
                    }
                }
                androidx.compose.runtime.LaunchedEffect(followed) {
                    followed?.let { list ->
                        com.github.andreyasadchy.xtra.ui.tv.TvLauncherChannels.syncFollowedLive(context, list)
                    }
                }
                val sections = buildList {
                    activeEntries.forEach { entry ->
                        when (entry.id) {
                            TvHomeRows.ROW_FOLLOWED -> {
                                val list = followed
                                if (list == null) {
                                    add(TvHomeSection.Skeleton(entry.id))
                                } else if (list.isNotEmpty()) {
                                    add(TvHomeSection.Streams(entry.id, TvHomeRow(getString(R.string.tv_followed_live), list, followedId, R.drawable.tv_ic_heart)))
                                }
                            }
                            TvHomeRows.ROW_FAVORITES -> {
                                val list = favorites
                                if (list == null) {
                                    add(TvHomeSection.Skeleton(entry.id))
                                } else if (list.isNotEmpty()) {
                                    add(TvHomeSection.Streams(entry.id, TvHomeRow(getString(R.string.tv_home_row_favorites), list, 0, R.drawable.tv_ic_star)))
                                }
                            }
                            TvHomeRows.ROW_MULTISTREAMS -> {
                                if (multiStreams.isNotEmpty()) {
                                    add(TvHomeSection.MultiStreams(entry.id, TvHomeRow(getString(R.string.tv_home_multistreams_row), multiStreams, 0, R.drawable.tv_ic_multistream)))
                                }
                            }
                            TvHomeRows.ROW_POPULAR -> {
                                val list = popular
                                if (list == null) {
                                    add(TvHomeSection.Skeleton(entry.id))
                                } else if (list.isNotEmpty()) {
                                    add(TvHomeSection.Streams(entry.id, TvHomeRow(getString(R.string.popular), list, R.id.rootTopFragment, R.drawable.tv_ic_trending)))
                                }
                            }
                            TvHomeRows.ROW_RECOMMENDED -> {
                                val list = recommended
                                if (list == null) {
                                    add(TvHomeSection.Skeleton(entry.id))
                                } else if (list.isNotEmpty()) {
                                    add(TvHomeSection.Streams(entry.id, TvHomeRow(getString(R.string.tv_home_row_recommended), list, 0, R.drawable.tv_ic_streamers)))
                                }
                            }
                            TvHomeRows.ROW_RESUME -> {
                                resumeItems.value.takeIf { it.isNotEmpty() }?.let { list ->
                                    add(TvHomeSection.Resume(entry.id, TvHomeRow(getString(R.string.tv_continue_watching), list, 0, R.drawable.tv_ic_history)))
                                }
                            }
                            TvHomeRows.ROW_FOLLOWED_VIDEOS -> {
                                // only full past broadcasts (ARCHIVE), and the videos still recording are left out
                                followedVideos
                                    ?.filter { video -> video.type == null || video.type.equals("ARCHIVE", ignoreCase = true) }
                                    ?.filterNot { video -> isRecording(video, followed) }
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { list ->
                                        add(TvHomeSection.Videos(entry.id, TvHomeRow(getString(R.string.tv_followed_videos_row), list, 0, R.drawable.tv_ic_video_library)))
                                    }
                            }
                            TvHomeRows.ROW_FOLLOWED_CLIPS -> {
                                followedClips?.takeIf { it.isNotEmpty() }?.let { list ->
                                    add(TvHomeSection.Clips(entry.id, TvHomeRow(getString(R.string.tv_home_row_clips), list, 0, R.drawable.tv_ic_play)))
                                }
                            }
                            TvHomeRows.ROW_FOLLOWED_GAMES -> {
                                followedGames?.takeIf { it.isNotEmpty() }?.let { list ->
                                    add(TvHomeSection.Games(entry.id, TvHomeRow(getString(R.string.tv_home_row_followed_games), list, followedId, R.drawable.tv_ic_heart)))
                                }
                            }
                            TvHomeRows.ROW_STATS -> {
                                statsState.value?.let { stats ->
                                    add(TvHomeSection.Stats(entry.id, stats))
                                }
                            }
                            TvHomeRows.ROW_GAMES -> {
                                games?.takeIf { it.isNotEmpty() }?.let { list ->
                                    add(TvHomeSection.Games(entry.id, TvHomeRow(getString(R.string.games), list, R.id.rootGamesFragment, R.drawable.tv_ic_gamepad)))
                                }
                            }
                            else -> {
                                val langCode = TvHomeRows.parseLanguage(entry.id)
                                if (langCode != null) {
                                    val streams = languageRows[langCode]
                                    if (!streams.isNullOrEmpty()) {
                                        add(TvHomeSection.Streams(entry.id, TvHomeRow(getString(R.string.tv_popular_in, languageName(langCode)), streams, 0, R.drawable.tv_ic_globe)))
                                    } else if (isRefreshing && streams == null) {
                                        add(TvHomeSection.Skeleton(entry.id))
                                    }
                                } else {
                                    val spec = TvHomeRows.parseCategory(entry.id)
                                    if (spec != null) {
                                        val streams = categoryRows[spec.id]
                                        if (!streams.isNullOrEmpty()) {
                                            add(
                                                TvHomeSection.Streams(
                                                    entry.id,
                                                    TvHomeRow(
                                                        title = spec.name,
                                                        items = streams,
                                                        seeAllId = 0,
                                                        icon = R.drawable.tv_ic_gamepad,
                                                        onSeeAll = {
                                                            stopPreview()
                                                            navigateToGame(
                                                                com.github.andreyasadchy.xtra.model.ui.Game(
                                                                    id = spec.gameId,
                                                                    slug = spec.slug.ifBlank { null },
                                                                    name = spec.name,
                                                                )
                                                            )
                                                        },
                                                    )
                                                )
                                            )
                                        } else if (isRefreshing && streams == null) {
                                            add(TvHomeSection.Skeleton(entry.id))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                TvTheme(accent) {
                    TvHomeScreen(
                        sections = sections,
                        previewLogin = { previewLogin.value },
                        previewPlayer = { previewPlayer.value },
                        actions = actions,
                        hero = heroStream,
                        heroQueue = heroQueue,
                        focus = homeFocus,
                        account = TvHomeAccount(
                            name = context.tokenPrefs().getString(C.USERNAME, null)?.takeIf { it.isNotBlank() },
                            image = accountImage,
                        ),
                        showGreeting = showGreeting,
                        showStatsBadge = showStatsBadge,
                    )
                }
            }
        }
    }

    private fun navigateToGame(game: com.github.andreyasadchy.xtra.model.ui.Game) {
        val ctx = context ?: return
        findNavController().navigate(
            if (ctx.prefs().getBoolean(C.UI_GAME_PAGER, true)) {
                GamePagerFragmentDirections.actionGlobalGamePagerFragment(gameId = game.id, gameSlug = game.slug, gameName = game.name, boxArt = game.boxArt)
            } else {
                GameMediaFragmentDirections.actionGlobalGameMediaFragment(gameId = game.id, gameSlug = game.slug, gameName = game.name, boxArt = game.boxArt)
            }
        )
    }

    private fun showGameActions(game: com.github.andreyasadchy.xtra.model.ui.Game) {
        stopPreview()
        val activity = activity as? MainActivity ?: return
        val pinned = TvHomeRows.isCategoryPinned(activity, game.id)
        val actions = buildList {
            add(
                com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                    getString(R.string.tv_open_this_category),
                    R.drawable.tv_ic_gamepad,
                ) {
                    navigateToGame(game)
                }
            )
            if (!game.id.isNullOrBlank()) {
                add(
                    com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                        getString(if (pinned) R.string.tv_home_unpin_category else R.string.tv_home_pin_category),
                        if (pinned) R.drawable.tv_ic_delete else R.drawable.tv_ic_pin,
                    ) {
                        val nowPinned = TvHomeRows.togglePinCategory(activity, game)
                        android.widget.Toast.makeText(
                            activity,
                            getString(
                                if (nowPinned) R.string.tv_home_category_pinned else R.string.tv_home_category_unpinned,
                                game.name ?: "",
                            ),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                )
            }
        }
        com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.show(activity, game.name ?: "", actions)
    }

    private fun showClipActions(clip: com.github.andreyasadchy.xtra.model.ui.Clip, queue: List<com.github.andreyasadchy.xtra.model.ui.Clip>) {
        stopPreview()
        val activity = activity as? MainActivity ?: return
        val channelLabel = clip.channelName ?: clip.channelLogin.orEmpty()
        val actions = buildList {
            add(
                com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                    getString(R.string.tv_multistream_watch),
                    R.drawable.tv_ic_play,
                ) {
                    activity.startClip(clip, queue)
                }
            )
            if (!clip.videoId.isNullOrBlank()) {
                add(
                    com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                        getString(R.string.watch_video),
                        R.drawable.tv_ic_video_library,
                    ) {
                        val offsetMs = clip.videoOffsetSeconds?.toLong()?.times(1000L)
                        activity.startVideo(
                            com.github.andreyasadchy.xtra.model.ui.Video(
                                id = clip.videoId,
                                channelId = clip.channelId,
                                channelLogin = clip.channelLogin,
                                channelName = clip.channelName,
                                channelImageURL = clip.channelImageURL,
                                gameId = clip.gameId,
                                gameSlug = clip.gameSlug,
                                gameName = clip.gameName,
                                title = clip.title,
                                createdAt = clip.videoCreatedAt ?: clip.createdAt,
                                animatedPreviewURL = clip.videoAnimatedPreviewURL,
                            ),
                            offset = offsetMs,
                            ignoreSavedPosition = offsetMs != null,
                        )
                    }
                )
            }
            if (!clip.channelLogin.isNullOrBlank() || !clip.channelId.isNullOrBlank()) {
                add(
                    com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                        getString(R.string.tv_open_channel, channelLabel),
                        R.drawable.tv_ic_person,
                    ) {
                        activity.openTvChannel(
                            Stream(
                                channelId = clip.channelId,
                                channelLogin = clip.channelLogin,
                                channelName = clip.channelName,
                                channelImageURL = clip.channelImageURL,
                            ),
                            null,
                        )
                    }
                )
            }
        }
        com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.show(activity, clip.title ?: channelLabel, actions)
    }

    private fun syncHomePrefs(context: android.content.Context) {
        val p = context.prefs()
        showHeroState.value = p.getBoolean(C.TV_HOME_HERO, true)
        showGreetingState.value = p.getBoolean(C.TV_HOME_GREETING, true)
        showStatsBadgeState.value = p.getBoolean(C.TV_HOME_STATS_BADGE, true)
    }

    override fun onResume() {
        super.onResume()
        context?.let { syncHomePrefs(it) }
        viewModel.load()
        loadLocalRows()
    }

    fun refreshIfStale(maxAgeMs: Long = 60_000L) {
        context?.let { syncHomePrefs(it) }
        viewModel.load(force = false, maxAgeMs = maxAgeMs)
        loadLocalRows()
    }

    private fun showMultiStreamActions(item: TvHomeMultiStream) {
        stopPreview()
        val activity = activity as? MainActivity ?: return
        val actions = buildList {
            add(
                com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                    getString(R.string.tv_multistream_watch),
                    R.drawable.tv_ic_multistream,
                ) {
                    com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.launchGroup(activity, item.group)
                }
            )
            add(
                com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                    getString(R.string.tv_multistream_rename),
                    R.drawable.tv_ic_edit,
                ) {
                    com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(activity) { dismiss ->
                        com.github.andreyasadchy.xtra.ui.tv.ChatComposer(
                            title = activity.getString(R.string.tv_multistream_rename),
                            hint = activity.getString(R.string.tv_multistream_name_hint),
                            sendLabel = activity.getString(android.R.string.ok),
                            initialText = item.group.label,
                        ) { newName ->
                            com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.rename(activity, item.group.id, newName)
                            dismiss()
                        }
                    }
                }
            )
            add(
                com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                    getString(R.string.tv_multistream_remove_group),
                    R.drawable.tv_ic_delete,
                ) {
                    com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups.remove(activity, item.group.id)
                    android.widget.Toast.makeText(activity, R.string.tv_multistream_group_removed, android.widget.Toast.LENGTH_SHORT).show()
                }
            )
            item.group.streams.forEach { member ->
                val resolved = item.liveStreams.find { it.channelLogin.equals(member.channelLogin, ignoreCase = true) } ?: member
                val name = resolved.channelName ?: resolved.channelLogin ?: return@forEach
                add(
                    com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                        getString(R.string.tv_open_channel, name),
                        R.drawable.tv_ic_person,
                    ) {
                        activity.openTvChannel(resolved, null)
                    }
                )
            }
        }
        com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.show(activity, item.group.label, actions)
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
        if (login != null && login == scheduledLogin && muted == scheduledMuted) {
            // already loading
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
        if (com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.suspended || !isResumed) {
            previewPlayer.value?.release()
            previewPlayer.value = null
        } else {
            previewPlayer.value?.stop()
            previewPlayer.value?.clearMediaItems()
        }
    }

    override fun onDestroyView() {
        stopPreview()
        previewPlayer.value?.release()
        previewPlayer.value = null
        super.onDestroyView()
    }
}
