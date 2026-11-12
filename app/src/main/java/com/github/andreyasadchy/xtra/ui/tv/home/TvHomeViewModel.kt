package com.github.andreyasadchy.xtra.ui.tv.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingSource
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.graphql.type.BroadcastType
import com.github.andreyasadchy.xtra.graphql.type.ClipsPeriod
import com.github.andreyasadchy.xtra.graphql.type.Language
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.graphql.type.VideoSort
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.LocalGameFollowsRepository
import com.github.andreyasadchy.xtra.repository.datasource.ChannelClipsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.ChannelSuggestionsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.FollowedGamesDataSource
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.FollowedVideosDataSource
import com.github.andreyasadchy.xtra.repository.datasource.GameStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.GamesDataSource
import com.github.andreyasadchy.xtra.repository.datasource.StreamsDataSource
import com.github.andreyasadchy.xtra.ui.tv.TvFavorites
import com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamGroups
import com.github.andreyasadchy.xtra.ui.tv.TvStats
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TvHomeMultiStream(
    val group: TvMultiStreamGroups.Group,
    val liveStreams: List<Stream>,
    val totalViewers: Int,
    val sharedGame: String?,
)

/** Rows of the TV home: customizable order, visibility, discovery rows, pinned categories, and per-language rows. */
class TvHomeViewModel(
    private val applicationContext: Context,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val localGameFollowsRepository: LocalGameFollowsRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) : ViewModel() {

    val followed = MutableStateFlow<List<Stream>?>(null)
    val favorites = MutableStateFlow<List<Stream>?>(null)
    val multiStreams = MutableStateFlow<List<TvHomeMultiStream>>(emptyList())
    /** Latest videos of the followed channels (logged in only: Twitch gives them to the account). */
    val followedVideos = MutableStateFlow<List<Video>?>(null)
    val followedClips = MutableStateFlow<List<Clip>?>(null)
    val popular = MutableStateFlow<List<Stream>?>(null)
    val recommended = MutableStateFlow<List<Stream>?>(null)
    val followedGames = MutableStateFlow<List<Game>?>(null)
    val games = MutableStateFlow<List<Game>?>(null)
    /** Rows of the popular streams of one language (`lang:<code>` -> streams). */
    val languageRows = MutableStateFlow<Map<String, List<Stream>>>(emptyMap())
    /** Pinned category stream rows (`cat:...` -> streams). */
    val categoryRows = MutableStateFlow<Map<String, List<Stream>>>(emptyMap())
    /** True while the rows load again, the refresh button turns. */
    val refreshing = MutableStateFlow(false)
    /** Time of the last load: added to the thumbnails, Twitch keeps the same address for a new picture. */
    val stamp = MutableStateFlow(0L)

    private var loadedAt = 0L
    private var loadedActiveIds: List<String>? = null

    /** Picture of the account logged in, for the greeting at the top of the home. */
    val accountImage = MutableStateFlow<String?>(null)
    private var accountImageOf: String? = null

    private fun loadAccountImage() {
        val userId = applicationContext.tokenPrefs().getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }
        if (userId == null) {
            accountImage.value = null
            accountImageOf = null
            return
        }
        if (userId == accountImageOf && accountImage.value != null) {
            return
        }
        accountImageOf = userId
        viewModelScope.launch {
            try {
                val prefs = applicationContext.prefs()
                accountImage.value = graphQLRepository.loadQueryUser(
                    prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(applicationContext),
                    id = userId
                ).data?.user?.profileImageURL
            } catch (_: Exception) {
            }
        }
    }

    private fun computeFavorites(list: List<Stream>?): List<Stream>? {
        val source = list ?: return null
        val favOrder = TvFavorites.items.value.mapIndexed { idx, fav -> fav.login.lowercase() to idx }.toMap()
        if (favOrder.isEmpty()) return emptyList()
        return source
            .filter { s -> s.channelLogin?.lowercase() in favOrder }
            .sortedBy { s -> favOrder[s.channelLogin?.lowercase()] ?: Int.MAX_VALUE }
    }

    init {
        TvFavorites.load(applicationContext)
        // a channel added to the favorites or removed: the followed row and favorites row update at once
        viewModelScope.launch {
            TvFavorites.items.collect {
                followed.value?.let { list ->
                    val sorted = TvFavorites.sort(list.sortedByDescending { stream -> stream.viewerCount })
                    followed.value = sorted
                    favorites.value = computeFavorites(sorted)
                }
            }
        }
        viewModelScope.launch {
            var first = true
            TvMultiStreamGroups.changes.collect {
                if (first) {
                    first = false
                } else {
                    refreshMultiStreams()
                }
            }
        }
        viewModelScope.launch {
            var first = true
            TvHomeRows.changes.collect {
                if (first) {
                    first = false
                } else {
                    load(force = true)
                }
            }
        }
    }

    /** Loads the rows, again when they are older than [maxAgeMs]. */
    fun load(force: Boolean = false, maxAgeMs: Long = 60_000L) {
        loadAccountImage()
        val prefs = applicationContext.prefs()
        val activeEntries = TvHomeRows.load(applicationContext).filter { it.enabled }
        val activeIds = activeEntries.map { it.id }
        if (!force && loadedAt != 0L && System.currentTimeMillis() - loadedAt < maxAgeMs &&
            activeIds == loadedActiveIds
        ) {
            return
        }
        loadedActiveIds = activeIds
        loadedAt = System.currentTimeMillis()
        val activeSet = activeIds.toSet()
        val showHero = prefs.getBoolean(C.TV_HOME_HERO, true)
        val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext)
        val gqlAccountHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        refreshing.value = true
        val jobs = mutableListOf<Job>()

        val needFollowed = showHero ||
            TvHomeRows.ROW_FOLLOWED in activeSet ||
            TvHomeRows.ROW_FAVORITES in activeSet ||
            TvHomeRows.ROW_FOLLOWED_VIDEOS in activeSet ||
            TvHomeRows.ROW_RECOMMENDED in activeSet ||
            TvHomeRows.ROW_FOLLOWED_CLIPS in activeSet

        val followedJob: Job? = if (needFollowed) {
            viewModelScope.launch {
                val list = loadPage(createFollowedSource())
                followed.value = list
                favorites.value = computeFavorites(list)
            }.also { jobs += it }
        } else {
            followed.value = emptyList()
            favorites.value = emptyList()
            null
        }

        if (TvHomeRows.ROW_MULTISTREAMS in activeSet) {
            jobs += viewModelScope.launch {
                refreshMultiStreams()
            }
        } else {
            multiStreams.value = emptyList()
        }

        if (showHero || TvHomeRows.ROW_POPULAR in activeSet) {
            jobs += viewModelScope.launch {
                popular.value = loadPage(createPopularSource())
            }
        } else {
            popular.value = emptyList()
        }

        if (TvHomeRows.ROW_FOLLOWED_VIDEOS in activeSet && !gqlAccountHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            jobs += viewModelScope.launch {
                followedVideos.value = loadPage(
                    FollowedVideosDataSource(
                        gqlQueryType = BroadcastType.ARCHIVE,
                        gqlQuerySort = VideoSort.TIME,
                        gqlHeaders = gqlAccountHeaders,
                        graphQLRepository = graphQLRepository,
                        enableIntegrity = enableIntegrity,
                        networkLibrary = networkLibrary,
                    )
                )
            }
        } else {
            followedVideos.value = emptyList()
        }

        if (TvHomeRows.ROW_RECOMMENDED in activeSet) {
            jobs += viewModelScope.launch {
                followedJob?.join()
                recommended.value = loadRecommendedStreams(
                    followedStreams = followed.value.orEmpty(),
                    gqlHeaders = gqlHeaders,
                    enableIntegrity = enableIntegrity,
                    networkLibrary = networkLibrary,
                )
            }
        } else {
            recommended.value = emptyList()
        }

        if (TvHomeRows.ROW_FOLLOWED_CLIPS in activeSet) {
            jobs += viewModelScope.launch {
                followedJob?.join()
                followedClips.value = loadFollowedTopClips(
                    followedStreams = followed.value.orEmpty(),
                    gqlHeaders = gqlHeaders,
                    helixHeaders = helixHeaders,
                    enableIntegrity = enableIntegrity,
                    networkLibrary = networkLibrary,
                )
            }
        } else {
            followedClips.value = emptyList()
        }

        if (TvHomeRows.ROW_FOLLOWED_GAMES in activeSet) {
            jobs += viewModelScope.launch {
                followedGames.value = loadPage(
                    FollowedGamesDataSource(
                        localGameFollowsRepository = localGameFollowsRepository,
                        gqlHeaders = gqlAccountHeaders,
                        graphQLRepository = graphQLRepository,
                        enableIntegrity = enableIntegrity,
                        networkLibrary = networkLibrary,
                    )
                )
            }
        } else {
            followedGames.value = emptyList()
        }

        val languages = activeEntries.mapNotNull { TvHomeRows.parseLanguage(it.id) }.distinct()
        if (languages.isNotEmpty()) {
            jobs += viewModelScope.launch {
                languageRows.value = languages.map { language ->
                    async { language to loadPage(createLanguageSource(language)) }
                }.map { it.await() }.filter { it.second.isNotEmpty() }.toMap()
            }
        } else {
            languageRows.value = emptyMap()
        }

        val categories = activeEntries.mapNotNull { TvHomeRows.parseCategory(it.id) }
        if (categories.isNotEmpty()) {
            jobs += viewModelScope.launch {
                categoryRows.value = categories.map { spec ->
                    async { spec.id to loadPage(createCategorySource(spec)) }
                }.map { it.await() }.filter { it.second.isNotEmpty() }.toMap()
            }
        } else {
            categoryRows.value = emptyMap()
        }

        if (TvHomeRows.ROW_GAMES in activeSet) {
            jobs += viewModelScope.launch {
                games.value = loadPage(
                    GamesDataSource(
                        tags = null,
                        gqlHeaders = gqlHeaders,
                        graphQLRepository = graphQLRepository,
                        helixHeaders = helixHeaders,
                        helixRepository = helixRepository,
                        enableIntegrity = enableIntegrity,
                        networkLibrary = networkLibrary,
                    )
                )
            }
        } else {
            games.value = emptyList()
        }

        viewModelScope.launch {
            jobs.forEach { it.join() }
            stamp.value = System.currentTimeMillis() / 1000
            refreshing.value = false
        }
    }

    private suspend fun collectSeedLogins(followedStreams: List<Stream>, maxSeeds: Int): List<String> {
        val seeds = LinkedHashSet<String>()
        TvFavorites.items.value.forEach { fav ->
            if (fav.login.isNotBlank()) seeds.add(fav.login.trim().lowercase())
        }
        runCatching {
            TvStats.detail(applicationContext, 30).streamers.forEach { r ->
                if (r.key.isNotBlank()) seeds.add(r.key.trim().lowercase())
            }
            if (seeds.size < maxSeeds) {
                TvStats.detail(applicationContext, null).streamers.forEach { r ->
                    if (r.key.isNotBlank()) seeds.add(r.key.trim().lowercase())
                }
            }
        }
        followedStreams.forEach { s ->
            s.channelLogin?.takeIf { it.isNotBlank() }?.let { seeds.add(it.trim().lowercase()) }
        }
        if (seeds.size < maxSeeds) {
            runCatching {
                localChannelFollowsRepository.getAll().forEach { local ->
                    local.userLogin?.takeIf { it.isNotBlank() }?.let { seeds.add(it.trim().lowercase()) }
                }
            }
        }
        return seeds.take(maxSeeds)
    }

    private suspend fun loadRecommendedStreams(
        followedStreams: List<Stream>,
        gqlHeaders: Map<String, String>,
        enableIntegrity: Boolean,
        networkLibrary: String?,
    ): List<Stream> {
        val seeds = buildList {
            runCatching {
                TvStats.detail(applicationContext, 30).streamers.forEach { if (it.key.isNotBlank()) add(it.key.lowercase()) }
                TvStats.detail(applicationContext, null).streamers.forEach { if (it.key.isNotBlank()) add(it.key.lowercase()) }
            }
            TvFavorites.items.value.forEach { if (it.login.isNotBlank()) add(it.login.lowercase()) }
            followedStreams.forEach { it.channelLogin?.takeIf { l -> l.isNotBlank() }?.let { l -> add(l.lowercase()) } }
            if (isEmpty()) {
                runCatching {
                    localChannelFollowsRepository.getAll().forEach { local ->
                        local.userLogin?.takeIf { l -> l.isNotBlank() }?.let { l -> add(l.lowercase()) }
                    }
                }
            }
        }.distinct().take(2)
        if (seeds.isEmpty()) return emptyList()

        val followedLogins = followedStreams.mapNotNull { it.channelLogin?.lowercase() }.toSet()
        val rawSuggestions = mutableListOf<Stream>()
        for (seed in seeds) {
            val page = loadPage(
                ChannelSuggestionsDataSource(
                    channelLogin = seed,
                    gqlHeaders = gqlHeaders,
                    graphQLRepository = graphQLRepository,
                    enableIntegrity = enableIntegrity,
                    networkLibrary = networkLibrary,
                )
            )
            rawSuggestions += page
        }
        val deduped = LinkedHashMap<String, Stream>()
        for (s in rawSuggestions) {
            val login = s.channelLogin?.takeIf { it.isNotBlank() }?.lowercase() ?: continue
            if (login in followedLogins) continue
            if (login !in deduped) {
                deduped[login] = s
            }
        }
        if (deduped.isEmpty()) return emptyList()

        // Enrich with live previewImageURL & createdAt in one batched GQL request (fallback to static-cdn preview URL)
        val enrichedByLogin = mutableMapOf<String, Stream>()
        runCatching {
            graphQLRepository.loadQueryUsersStream(networkLibrary, gqlHeaders, logins = deduped.keys.take(30))
                .data?.users?.forEach { user ->
                    val login = user?.login ?: return@forEach
                    val st = user.stream ?: return@forEach
                    enrichedByLogin[login.lowercase()] = Stream(
                        id = st.id,
                        channelId = user.id,
                        channelLogin = login,
                        channelName = user.displayName ?: login,
                        channelImageURL = user.profileImageURL?.toString(),
                        gameId = st.game?.id,
                        gameSlug = st.game?.slug,
                        gameName = st.game?.displayName,
                        title = st.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = st.previewImageURL?.toString(),
                        createdAt = st.createdAt?.toString(),
                        viewerCount = st.viewersCount,
                        tags = st.freeformTags?.mapNotNull { it?.name },
                    )
                }
        }
        return deduped.map { (login, fallback) ->
            enrichedByLogin[login] ?: Stream(
                id = fallback.id,
                channelId = fallback.channelId,
                channelLogin = fallback.channelLogin,
                channelName = fallback.channelName,
                channelImageURL = fallback.channelImageURL,
                gameId = fallback.gameId,
                gameSlug = fallback.gameSlug,
                gameName = fallback.gameName,
                title = fallback.title,
                thumbnailURL = fallback.thumbnailURL
                    ?: "https://static-cdn.jtvnw.net/previews-ttv/live_user_${login}-{width}x{height}.jpg",
                createdAt = fallback.createdAt,
                viewerCount = fallback.viewerCount,
                tags = fallback.tags,
            )
        }.sortedByDescending { it.viewerCount ?: 0 }
    }

    private suspend fun loadFollowedTopClips(
        followedStreams: List<Stream>,
        gqlHeaders: Map<String, String>,
        helixHeaders: Map<String, String>,
        enableIntegrity: Boolean,
        networkLibrary: String?,
    ): List<Clip> {
        val seeds = collectSeedLogins(followedStreams, maxSeeds = 6)
        if (seeds.isEmpty()) return emptyList()
        val fetched = kotlinx.coroutines.coroutineScope {
            seeds.map { login ->
                async {
                    loadPage(
                        ChannelClipsDataSource(
                            channelId = null,
                            channelLogin = login,
                            gqlQueryPeriod = ClipsPeriod.LAST_WEEK,
                            gqlPeriod = "LAST_WEEK",
                            startedAt = null,
                            endedAt = null,
                            gqlHeaders = gqlHeaders,
                            graphQLRepository = graphQLRepository,
                            helixHeaders = helixHeaders,
                            helixRepository = helixRepository,
                            enableIntegrity = enableIntegrity,
                            networkLibrary = networkLibrary,
                        ),
                        size = 8,
                    )
                }
            }.flatMap { it.await() }
        }
        val seen = HashSet<String>()
        return fetched
            .filter { clip ->
                val key = clip.id ?: clip.thumbnailURL ?: return@filter false
                seen.add(key)
            }
            .sortedByDescending { it.viewCount ?: 0 }
            .take(24)
    }

    private suspend fun refreshMultiStreams() {
        val prefs = applicationContext.prefs()
        val active = TvHomeRows.load(applicationContext).any { it.id == TvHomeRows.ROW_MULTISTREAMS && it.enabled }
        if (!active) {
            multiStreams.value = emptyList()
            return
        }
        val groups = TvMultiStreamGroups.list(applicationContext)
        if (groups.isEmpty()) {
            multiStreams.value = emptyList()
            return
        }
        val allLogins = groups.flatMap { g -> g.streams.mapNotNull { it.channelLogin?.trim()?.lowercase() } }
            .filter { it.isNotEmpty() }
            .distinct()
        if (allLogins.isEmpty()) {
            multiStreams.value = emptyList()
            return
        }
        val liveByLogin = mutableMapOf<String, Stream>()
        try {
            val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
            val headers = TwitchApiHelper.getGQLHeaders(applicationContext)
            allLogins.chunked(100).forEach { chunk ->
                graphQLRepository.loadQueryUsersStream(networkLibrary, headers, logins = chunk)
                    .data?.users?.forEach { user ->
                        val login = user?.login ?: return@forEach
                        val st = user.stream ?: return@forEach
                        liveByLogin[login.lowercase()] = Stream(
                            id = st.id,
                            channelId = user.id,
                            channelLogin = login,
                            channelName = user.displayName ?: login,
                            channelImageURL = user.profileImageURL?.toString(),
                            gameId = st.game?.id,
                            gameSlug = st.game?.slug,
                            gameName = st.game?.displayName,
                            title = st.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = st.previewImageURL?.toString(),
                            createdAt = st.createdAt?.toString(),
                            viewerCount = st.viewersCount,
                            tags = st.freeformTags?.mapNotNull { it?.name },
                        )
                    }
            }
        } catch (_: Exception) {
            return
        }
        multiStreams.value = groups.mapNotNull { group ->
            val live = group.streams.mapNotNull { s -> s.channelLogin?.trim()?.lowercase()?.let { liveByLogin[it] } }
            if (live.size < 2) return@mapNotNull null
            val totalViewers = live.sumOf { it.viewerCount ?: 0 }
            val sharedGame = live.mapNotNull { it.gameName?.takeIf { g -> g.isNotBlank() } }.distinct().singleOrNull()
            TvHomeMultiStream(
                group = group,
                liveStreams = live,
                totalViewers = totalViewers,
                sharedGame = sharedGame,
            )
        }
    }

    /** Source of the "Followed live" row, also used by the player to load the list again (the other streams, left / right). */
    fun createFollowedSource(): PagingSource<Int, Stream> {
        val prefs = applicationContext.prefs()
        return FollowedStreamsDataSource(
            userId = applicationContext.tokenPrefs().getString(C.USER_ID, null),
            localChannelFollowsRepository = localChannelFollowsRepository,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
            graphQLRepository = graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
            helixRepository = helixRepository,
            enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
        )
    }

    /** Source of the "Popular" row, also used by the player to load the list again (the other streams, left / right). */
    fun createPopularSource(): PagingSource<Int, Stream> {
        val prefs = applicationContext.prefs()
        // languages of the "Popular" row, chosen in the settings (all when none)
        val languages = prefs.getStringSet(C.TV_HOME_LANGUAGES, null)?.filter { it.isNotBlank() }?.ifEmpty { null }
        return StreamsDataSource(
            gqlQueryLanguages = languages?.mapNotNull { language -> Language.entries.find { it.rawValue == language } },
            gqlQuerySort = StreamSort.VIEWER_COUNT,
            gqlLanguages = languages,
            gqlSort = "VIEWER_COUNT",
            tags = null,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext),
            graphQLRepository = graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
            helixRepository = helixRepository,
            enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
        )
    }

    /** Source of a row of the popular streams of one language. */
    fun createLanguageSource(language: String): PagingSource<Int, Stream> {
        val prefs = applicationContext.prefs()
        return StreamsDataSource(
            gqlQueryLanguages = Language.entries.filter { it.rawValue == language },
            gqlQuerySort = StreamSort.VIEWER_COUNT,
            gqlLanguages = listOf(language),
            gqlSort = "VIEWER_COUNT",
            tags = null,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext),
            graphQLRepository = graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
            helixRepository = helixRepository,
            enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
        )
    }

    /** Source of a pinned category stream row (`cat:<gameId>:<langMode>:<slug>:<name>`). */
    fun createCategorySource(spec: TvHomeRows.CategorySpec): PagingSource<Int, Stream> {
        val prefs = applicationContext.prefs()
        val languages = if (spec.langMode == TvHomeRows.LANG_MODE_PREF) {
            prefs.getStringSet(C.TV_HOME_LANGUAGES, null)?.filter { it.isNotBlank() }?.ifEmpty { null }
        } else {
            null
        }
        return GameStreamsDataSource(
            gameId = spec.gameId,
            gameSlug = spec.slug.ifBlank { null },
            gameName = spec.name,
            gqlQueryLanguages = languages?.mapNotNull { language -> Language.entries.find { it.rawValue == language } },
            gqlQuerySort = StreamSort.VIEWER_COUNT,
            gqlLanguages = languages,
            gqlSort = "VIEWER_COUNT",
            tags = null,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext),
            graphQLRepository = graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
            helixRepository = helixRepository,
            enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
        )
    }

    private suspend fun <T : Any> loadPage(source: PagingSource<Int, T>, size: Int = 30): List<T> {
        return try {
            (source.load(PagingSource.LoadParams.Refresh(null, size, false)) as? PagingSource.LoadResult.Page)?.data ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        val TvHomeViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                TvHomeViewModel(
                    application.applicationContext,
                    xtraModule.localChannelFollowsRepository,
                    xtraModule.localGameFollowsRepository,
                    xtraModule.graphQLRepository,
                    xtraModule.helixRepository,
                )
            }
        }
    }
}
