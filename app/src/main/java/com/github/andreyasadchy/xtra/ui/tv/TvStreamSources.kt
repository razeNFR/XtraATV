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

    class Row(val title: String, val source: () -> PagingSource<Int, Stream>)

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
     * Streams of a category, in the languages chosen on the page of that category (else the ones of the "default"
     * category, else the default languages of the Xtra TV settings), like its page.
     */
    fun game(context: Context, gameId: String?, gameSlug: String?, gameName: String?): PagingSource<Int, Stream> {
        val app = context.applicationContext as XtraApp
        val prefs = context.prefs()
        return LazySource {
            val sort = gameId?.let { runCatching { app.xtraModule.gameSortRepository.getById(it) }.getOrNull() }
                ?: runCatching { app.xtraModule.gameSortRepository.getById("default") }.getOrNull()
            val languages = sort?.streamLanguages?.split(',')?.filter { it.isNotBlank() }?.ifEmpty { null }
                ?: prefs.getStringSet(C.TV_DEFAULT_LANGUAGES, null)?.filter { it.isNotBlank() }?.ifEmpty { null }
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

    /**
     * Rows the row of the other streams can show besides its own list: followed (when logged in), popular, the rows by
     * language of the home, the category of the stream watched.
     */
    fun rows(context: Context, current: Stream?): List<Row> {
        val rows = mutableListOf<Row>()
        val loggedIn = !TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(context)[C.HEADER_TOKEN].isNullOrBlank()
        if (loggedIn) {
            rows += Row(context.getString(com.github.andreyasadchy.xtra.R.string.tv_followed_live)) { followed(context) }
        }
        rows += Row(context.getString(com.github.andreyasadchy.xtra.R.string.popular)) { popular(context) }
        context.prefs().getStringSet(C.TV_HOME_LANGUAGE_ROWS, null)?.filter { it.isNotBlank() }?.sorted()?.forEach { language ->
            rows += Row(context.getString(com.github.andreyasadchy.xtra.R.string.tv_popular_in, languageName(context, language))) { popular(context, listOf(language)) }
        }
        if (current != null && (!current.gameId.isNullOrBlank() || !current.gameSlug.isNullOrBlank()) && !current.gameName.isNullOrBlank()) {
            rows += Row(current.gameName!!) { game(context, current.gameId, current.gameSlug, current.gameName) }
        }
        return rows
    }
}
