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

    fun game(context: Context, gameId: String?, gameSlug: String?, gameName: String?): PagingSource<Int, Stream> {
        val app = context.applicationContext as XtraApp
        val prefs = context.prefs()
        return GameStreamsDataSource(
            gameId = gameId,
            gameSlug = gameSlug,
            gameName = gameName,
            gqlQueryLanguages = null,
            gqlQuerySort = StreamSort.VIEWER_COUNT,
            gqlLanguages = null,
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

    /** First page of a source, null when it fails. */
    suspend fun load(source: PagingSource<Int, Stream>): List<Stream>? {
        return try {
            (source.load(PagingSource.LoadParams.Refresh(null, 40, false)) as? PagingSource.LoadResult.Page)?.data
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
