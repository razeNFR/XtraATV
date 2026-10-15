package com.github.andreyasadchy.xtra.ui.tv.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingSource
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.GamesDataSource
import com.github.andreyasadchy.xtra.repository.datasource.StreamsDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Rows of the TV home: followed channels live, popular streams, categories. */
class TvHomeViewModel(
    private val applicationContext: Context,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) : ViewModel() {

    val followed = MutableStateFlow<List<Stream>?>(null)
    val popular = MutableStateFlow<List<Stream>?>(null)
    val games = MutableStateFlow<List<Game>?>(null)
    /** Rows of the popular streams of one language (Xtra TV settings), in the order of the languages. */
    val languageRows = MutableStateFlow<List<Pair<String, List<Stream>>>>(emptyList())
    /** True while the rows load again, the refresh button turns. */
    val refreshing = MutableStateFlow(false)
    /** Time of the last load: added to the thumbnails, Twitch keeps the same address for a new picture. */
    val stamp = MutableStateFlow(0L)
    private var loadedAt = 0L
    private var loadedLanguages: Set<String>? = null

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
            } catch (e: Exception) {

            }
        }
    }

    init {
        // a channel added to the favorites or removed: the followed row is sorted again at once
        viewModelScope.launch {
            com.github.andreyasadchy.xtra.ui.tv.TvFavorites.items.collect {
                followed.value?.let { list ->
                    followed.value = com.github.andreyasadchy.xtra.ui.tv.TvFavorites.sort(list.sortedByDescending { stream -> stream.viewerCount })
                }
            }
        }
    }

    /** Loads the rows, again when they are older than a few minutes. */
    fun load(force: Boolean = false) {
        loadAccountImage()
        // languages of the rows changed in the settings: loaded again at once
        val rowLanguages = applicationContext.prefs().getStringSet(C.TV_HOME_LANGUAGE_ROWS, null).orEmpty().toSet()
        if (!force && loadedAt != 0L && System.currentTimeMillis() - loadedAt < 3 * 60_000L && rowLanguages == loadedLanguages) {
            return
        }
        loadedLanguages = rowLanguages
        loadedAt = System.currentTimeMillis()
        val prefs = applicationContext.prefs()
        val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        refreshing.value = true
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        jobs += viewModelScope.launch {
            followed.value = loadPage(createFollowedSource())
        }
        jobs += viewModelScope.launch {
            popular.value = loadPage(createPopularSource())
        }
        val languages = prefs.getStringSet(C.TV_HOME_LANGUAGE_ROWS, null)?.filter { it.isNotBlank() }?.sorted().orEmpty()
        jobs += viewModelScope.launch {
            languageRows.value = languages.map { language ->
                async { language to loadPage(createLanguageSource(language)) }
            }.map { it.await() }.filter { it.second.isNotEmpty() }
        }
        jobs += viewModelScope.launch {
            games.value = loadPage(
                GamesDataSource(
                    tags = null,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext),
                    graphQLRepository = graphQLRepository,
                    helixHeaders = helixHeaders,
                    helixRepository = helixRepository,
                    enableIntegrity = enableIntegrity,
                    networkLibrary = networkLibrary,
                )
            )
        }
        viewModelScope.launch {
            jobs.forEach { it.join() }
            stamp.value = System.currentTimeMillis() / 1000
            refreshing.value = false
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
            gqlQueryLanguages = languages?.mapNotNull { language -> com.github.andreyasadchy.xtra.graphql.type.Language.entries.find { it.rawValue == language } },
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
            gqlQueryLanguages = com.github.andreyasadchy.xtra.graphql.type.Language.entries.filter { it.rawValue == language },
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

    private suspend fun <T : Any> loadPage(source: PagingSource<Int, T>): List<T> {
        return try {
            (source.load(PagingSource.LoadParams.Refresh(null, 30, false)) as? PagingSource.LoadResult.Page)?.data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        val TvHomeViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                TvHomeViewModel(application.applicationContext, xtraModule.localChannelFollowsRepository, xtraModule.graphQLRepository, xtraModule.helixRepository)
            }
        }
    }
}
