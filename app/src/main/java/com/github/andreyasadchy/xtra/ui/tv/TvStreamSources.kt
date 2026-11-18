package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.paging.PagingSource
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.graphql.type.Language
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.GameStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.StreamsDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs

/**
 * TV: lists of live streams the row of the other streams of the player can switch to (followed, popular, a language,
 * the category of the stream), with the sources that load them again later.
 */
object TvStreamSources {

    const val KIND_COSTREAM = "costream"

    /** [kind]: "costream", "followed", "favorites", "recommended", "popular", "language:<code>" or "game:<id or slug>", to find the row a list comes from. */
    class Row(
        val title: String,
        val source: () -> PagingSource<Int, Stream>,
        val kind: String? = null,
        val iconRes: Int? = null,
        val preloaded: List<Stream>? = null,
    )

    /**
     * The row the list of a stream comes from, made with the same kind of source (followed, recommended, popular in the
     * languages of Popular, popular in one language, a category): -1 when none of the rows shows that list.
     */
    fun originRow(context: Context, source: (() -> PagingSource<Int, Stream>)?, rows: List<Row>): Int {
        val origin = runCatching { source?.invoke() }.getOrNull() ?: return -1
        val kind = when (origin) {
            is FollowedStreamsDataSource -> "followed"
            is StreamsDataSource -> {
                if (!origin.tags.isNullOrEmpty()) {
                    return -1
                }
                val languages = origin.gqlLanguages?.filter { it.isNotBlank() }?.sorted().orEmpty()
                val popularLanguages = context.prefs().getStringSet(C.TV_HOME_LANGUAGES, null)?.filter { it.isNotBlank() }?.sorted().orEmpty()
                when {
                    origin.gqlSort == "RELEVANCE" && languages == popularLanguages -> "recommended"
                    origin.gqlSort != null && origin.gqlSort != "VIEWER_COUNT" -> return -1
                    languages == popularLanguages -> "popular"
                    languages.size == 1 -> "language:" + languages.first()
                    else -> return -1
                }
            }
            is GameStreamsDataSource -> "game:" + (origin.gameId ?: origin.gameSlug ?: origin.gameName ?: return -1)
            else -> return -1
        }
        return rows.indexOfFirst { it.kind == kind }
    }

    fun followed(context: Context): PagingSource<Int, Stream> {
        val app = context.applicationContext as XtraApp
        val prefs = context.prefs()
        return FollowedStreamsDataSource(
            userId = context.tokenPrefs().getString(C.USER_ID, null),
            localChannelFollowsRepository = app.xtraModule.localChannelFollowsRepository,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true),
            graphQLRepository = app.xtraModule.graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(context),
            helixRepository = app.xtraModule.helixRepository,
            enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
        )
    }

    /** Favorite channels currently live, in their user-defined order. */
    fun favorites(context: Context): PagingSource<Int, Stream> {
        val appContext = context.applicationContext
        return object : PagingSource<Int, Stream>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
                if (params.key != null) {
                    return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
                }
                return try {
                    TvFavorites.load(appContext)
                    val favOrder = TvFavorites.items.value.map { it.login }
                    if (favOrder.isEmpty()) {
                        LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
                    } else {
                        val allFollowed = loadPages({ followed(appContext) }, 200).orEmpty()
                        val favStreams = allFollowed
                            .filter { it.channelLogin in favOrder }
                            .sortedBy { favOrder.indexOf(it.channelLogin) }
                        LoadResult.Page(favStreams, prevKey = null, nextKey = null)
                    }
                } catch (e: Exception) {
                    LoadResult.Error(e)
                }
            }

            override fun getRefreshKey(state: androidx.paging.PagingState<Int, Stream>): Int? = null
        }
    }

    /** "Recommended for you" streams (Twitch RELEVANCE sort with account token and persistent X-Device-Id). */
    fun recommended(context: Context, languages: List<String>? = null): PagingSource<Int, Stream> {
        val app = context.applicationContext as XtraApp
        val prefs = context.prefs()
        val list = languages ?: prefs.getStringSet(C.TV_HOME_LANGUAGES, null)?.filter { it.isNotBlank() }?.ifEmpty { null }
        return StreamsDataSource(
            gqlQueryLanguages = list?.mapNotNull { language -> Language.entries.find { it.rawValue == language } },
            gqlQuerySort = StreamSort.RELEVANCE,
            gqlLanguages = list,
            gqlSort = "RELEVANCE",
            tags = null,
            gqlHeaders = TwitchApiHelper.getGQLRecommendationHeaders(context),
            graphQLRepository = app.xtraModule.graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(context),
            helixRepository = app.xtraModule.helixRepository,
            enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
        )
    }

    /** Popular streams, in [languages] (null: the languages of Popular chosen in the settings, all when none). */
    fun popular(context: Context, languages: List<String>? = null): PagingSource<Int, Stream> {
        val app = context.applicationContext as XtraApp
        val prefs = context.prefs()
        val list = languages ?: prefs.getStringSet(C.TV_HOME_LANGUAGES, null)?.filter { it.isNotBlank() }?.ifEmpty { null }
        return StreamsDataSource(
            gqlQueryLanguages = list?.mapNotNull { language -> Language.entries.find { it.rawValue == language } },
            gqlQuerySort = StreamSort.VIEWER_COUNT,
            gqlLanguages = list,
            gqlSort = "VIEWER_COUNT",
            tags = null,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
            graphQLRepository = app.xtraModule.graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(context),
            helixRepository = app.xtraModule.helixRepository,
            enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
        )
    }

    /**
     * Streams of a category, in all languages when [langMode] is `all`, else in the languages chosen on the page of that
     * category (else the ones of the "default" category, else the default languages of the Xtra TV settings).
     */
    fun game(
        context: Context,
        gameId: String?,
        gameSlug: String?,
        gameName: String?,
        langMode: String? = null,
        fallbackLanguages: List<String>? = null,
    ): PagingSource<Int, Stream> {
        val app = context.applicationContext as XtraApp
        val prefs = context.prefs()
        return LazySource {
            val languages = if (langMode == com.github.andreyasadchy.xtra.ui.tv.home.TvHomeRows.LANG_MODE_ALL) {
                null
            } else {
                val sort = gameId?.let { runCatching { app.xtraModule.gameSortRepository.getById(it) }.getOrNull() }
                    ?: runCatching { app.xtraModule.gameSortRepository.getById("default") }.getOrNull()
                sort?.streamLanguages?.split(',')?.filter { it.isNotBlank() }?.ifEmpty { null }
                    ?: prefs.getStringSet(C.TV_DEFAULT_LANGUAGES, null)?.filter { it.isNotBlank() }?.ifEmpty { null }
                    ?: fallbackLanguages?.filter { it.isNotBlank() }?.ifEmpty { null }
            }
            GameStreamsDataSource(
                gameId = gameId,
                gameSlug = gameSlug,
                gameName = gameName,
                gqlQueryLanguages = languages?.mapNotNull { language -> Language.entries.find { it.rawValue == language } },
                gqlQuerySort = StreamSort.VIEWER_COUNT,
                gqlLanguages = languages,
                gqlSort = "VIEWER_COUNT",
                tags = null,
                gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                graphQLRepository = app.xtraModule.graphQLRepository,
                helixHeaders = TwitchApiHelper.getHelixHeaders(context),
                helixRepository = app.xtraModule.helixRepository,
                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
            )
        }
    }

    /** Live streams matching [query]. */
    fun search(context: Context, query: String): PagingSource<Int, Stream> {
        val app = context.applicationContext as XtraApp
        val prefs = context.prefs()
        return com.github.andreyasadchy.xtra.repository.datasource.SearchStreamsDataSource(
            query = query.trim(),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
            graphQLRepository = app.xtraModule.graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(context),
            helixRepository = app.xtraModule.helixRepository,
            enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
        )
    }

    /** A source made at its first load (it needs to read the database first), then used for the next pages. */
    private class LazySource(private val create: suspend () -> PagingSource<Int, Stream>) : PagingSource<Int, Stream>() {
        private var source: PagingSource<Int, Stream>? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
            val source = source ?: create().also { source = it }
            return source.load(params)
        }

        override fun getRefreshKey(state: androidx.paging.PagingState<Int, Stream>): Int? = null
    }

    /**
     * The pages of a list one after the other (30 streams each, the most Twitch gives with a language), from the same
     * source: it keeps where the last page ended.
     */
    class Pages(private val factory: () -> PagingSource<Int, Stream>) {
        private var source: PagingSource<Int, Stream>? = null
        private var nextKey: Int? = null
        private var started = false
        var hasMore = true
            private set
        var loading = false
            private set

        /** Next page, null when it fails (it can be asked again). */
        suspend fun next(): List<Stream>? {
            if (!hasMore || loading) {
                return null
            }
            loading = true
            return try {
                val source = source ?: factory().also { source = it }
                val params: PagingSource.LoadParams<Int> = if (!started) {
                    PagingSource.LoadParams.Refresh<Int>(null, PAGE_SIZE, false)
                } else {
                    PagingSource.LoadParams.Append<Int>(nextKey ?: 2, PAGE_SIZE, false)
                }
                (source.load(params) as? PagingSource.LoadResult.Page)?.let { page ->
                    started = true
                    nextKey = page.nextKey
                    hasMore = page.nextKey != null && page.data.isNotEmpty()
                    page.data.filter { !it.channelLogin.isNullOrBlank() }
                }
            } catch (e: Exception) {
                null
            } finally {
                loading = false
            }
        }
    }

    const val PAGE_SIZE = 30

    /** The first pages of a list, until [count] streams (or its end), null when the first page fails. */
    suspend fun loadPages(factory: () -> PagingSource<Int, Stream>, count: Int): List<Stream>? {
        val pages = Pages(factory)
        val list = pages.next()?.toMutableList() ?: return null
        var tries = 0
        while (list.size < count && pages.hasMore && tries++ < 10) {
            list += pages.next() ?: break
        }
        return list.distinctBy { it.channelLogin }
    }

    /** First page of a source, null when it fails. */
    suspend fun load(source: PagingSource<Int, Stream>): List<Stream>? {
        return try {
            (source.load(PagingSource.LoadParams.Refresh(null, PAGE_SIZE, false)) as? PagingSource.LoadResult.Page)?.data
                ?.filter { !it.channelLogin.isNullOrBlank() }?.distinctBy { it.channelLogin }
        } catch (e: Exception) {
            null
        }
    }

    /** Name of a language in the language of the app ("fr" -> "Français"). */
    fun languageName(context: Context, code: String): String {
        val locale = context.resources.configuration.locales[0]
        val name = java.util.Locale.forLanguageTag(code).getDisplayLanguage(locale)
        return name.takeIf { it.isNotBlank() && it != code }?.replaceFirstChar { it.titlecase(locale) } ?: code.uppercase()
    }

    private fun fixedStreams(list: List<Stream>): PagingSource<Int, Stream> = object : PagingSource<Int, Stream>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
            val data = if (params.key == null) list else emptyList()
            return LoadResult.Page(data, prevKey = null, nextKey = null)
        }

        override fun getRefreshKey(state: androidx.paging.PagingState<Int, Stream>): Int? = null
    }

    private suspend fun coStreamPartners(context: Context, current: Stream?): List<Stream> {
        if (current == null || current.channelLogin.isNullOrBlank()) return emptyList()
        val fromPlayer = (context as? com.github.andreyasadchy.xtra.ui.main.MainActivity)?.let { act ->
            if (current.channelLogin.equals(act.currentPlayingStream()?.channelLogin, ignoreCase = true)) {
                (act.playerFragment as? com.github.andreyasadchy.xtra.ui.player.PlayerFragment)?.getCoStreamers()
            } else {
                null
            }
        }.orEmpty()
        var merged = (fromPlayer + TvCoStreamCache.getPartners(current.channelId, current.channelLogin))
            .filter { !it.channelLogin.isNullOrBlank() && !it.channelLogin.equals(current.channelLogin, ignoreCase = true) && (current.channelId.isNullOrBlank() || it.channelId != current.channelId) }
            .distinctBy { it.channelLogin?.lowercase() }
        if (merged.isEmpty() && (!TvCoStreamCache.isCached(current.channelId, current.channelLogin) || TvCoStreamCache.getCount(current.channelId, current.channelLogin) > 0)) {
            merged = kotlinx.coroutines.withTimeoutOrNull(650L) {
                TvCoStreamCache.loadForChannel(context, current.channelId, current.channelLogin, current.title)
            }.orEmpty()
                .filter { !it.channelLogin.isNullOrBlank() && !it.channelLogin.equals(current.channelLogin, ignoreCase = true) && (current.channelId.isNullOrBlank() || it.channelId != current.channelId) }
                .distinctBy { it.channelLogin?.lowercase() }
        }
        return merged
    }

    /**
     * Rows the "Other streams" banner and "Multi-stream" picker show besides their own list, in the order configured
     * by the user in [TvSwitcherRows] (with "Stream Together" prepended when the current streamer is in a co-stream).
     */
    suspend fun rows(context: Context, current: Stream?): List<Row> {
        val rows = mutableListOf<Row>()
        val seenKinds = HashSet<String>()
        val coStreams = coStreamPartners(context, current)
        if (coStreams.isNotEmpty() && seenKinds.add(KIND_COSTREAM)) {
            rows += Row(
                title = context.getString(com.github.andreyasadchy.xtra.R.string.tv_costream_tab),
                source = { fixedStreams(coStreams) },
                kind = KIND_COSTREAM,
                iconRes = com.github.andreyasadchy.xtra.R.drawable.tv_ic_streamers,
                preloaded = coStreams,
            )
        }
        val gqlLoggedIn = TwitchApiHelper.isLoggedIn(context)
        val loggedIn = gqlLoggedIn || !TwitchApiHelper.getHelixHeaders(context)[C.HEADER_TOKEN].isNullOrBlank()
        val hasFollows = loggedIn || runCatching {
            (context.applicationContext as XtraApp).xtraModule.localChannelFollowsRepository.getAll().isNotEmpty()
        }.getOrDefault(false)
        TvFavorites.load(context)
        val hasFavorites = hasFollows && TvFavorites.items.value.isNotEmpty()

        for (entry in TvSwitcherRows.load(context)) {
            if (!entry.enabled) continue
            when {
                entry.id == TvSwitcherRows.ROW_FOLLOWED -> {
                    if (hasFollows && seenKinds.add("followed")) {
                        rows += Row(context.getString(com.github.andreyasadchy.xtra.R.string.tv_followed_live), { followed(context) }, "followed")
                    }
                }
                entry.id == TvSwitcherRows.ROW_FAVORITES -> {
                    if (hasFavorites && seenKinds.add("favorites")) {
                        rows += Row(context.getString(com.github.andreyasadchy.xtra.R.string.tv_home_row_favorites), { favorites(context) }, "favorites")
                    }
                }
                entry.id == TvSwitcherRows.ROW_RECOMMENDED -> {
                    if (gqlLoggedIn && seenKinds.add("recommended")) {
                        rows += Row(context.getString(com.github.andreyasadchy.xtra.R.string.tv_sort_recommended), { recommended(context) }, "recommended")
                    }
                }
                entry.id == TvSwitcherRows.ROW_POPULAR -> {
                    if (seenKinds.add("popular")) {
                        rows += Row(context.getString(com.github.andreyasadchy.xtra.R.string.popular), { popular(context) }, "popular")
                    }
                }
                entry.id == TvSwitcherRows.ROW_CURRENT_GAME -> {
                    if (current != null && !current.gameName.isNullOrBlank()) {
                        val kind = "game:" + (current.gameId ?: current.gameSlug ?: current.gameName)
                        if (seenKinds.add(kind)) {
                            rows += Row(current.gameName!!, { game(context, current.gameId, current.gameSlug, current.gameName) }, kind)
                        }
                    }
                }
                else -> {
                    val cat = com.github.andreyasadchy.xtra.ui.tv.home.TvHomeRows.parseCategory(entry.id)
                    if (cat != null) {
                        val kind = "game:${cat.gameId}"
                        if (seenKinds.add(kind)) {
                            rows += Row(
                                cat.name,
                                { game(context, cat.gameId, cat.slug.ifBlank { null }, cat.name, cat.langMode) },
                                kind,
                            )
                        }
                    } else {
                        val language = com.github.andreyasadchy.xtra.ui.tv.home.TvHomeRows.parseLanguage(entry.id)
                        if (language != null) {
                            val kind = "language:$language"
                            if (seenKinds.add(kind)) {
                                rows += Row(
                                    context.getString(com.github.andreyasadchy.xtra.R.string.tv_popular_in, languageName(context, language)),
                                    { popular(context, listOf(language)) },
                                    kind,
                                )
                            }
                        }
                    }
                }
            }
        }
        return rows
    }
}
