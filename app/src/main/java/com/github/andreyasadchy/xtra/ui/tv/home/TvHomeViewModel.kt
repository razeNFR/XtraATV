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
    /** True while the rows load again, the refresh button turns. */
    val refreshing = MutableStateFlow(false)
    /** Time of the last load: added to the thumbnails, Twitch keeps the same address for a new picture. */
    val stamp = MutableStateFlow(0L)
    private var loadedAt = 0L

    /** Loads the rows, again when they are older than a few minutes. */
    fun load(force: Boolean = false) {
        if (!force && loadedAt != 0L && System.currentTimeMillis() - loadedAt < 3 * 60_000L) {
            return
        }
        loadedAt = System.currentTimeMillis()
        val prefs = applicationContext.prefs()
        val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        refreshing.value = true
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        jobs += viewModelScope.launch {
            followed.value = loadPage(
                FollowedStreamsDataSource(
                    userId = applicationContext.tokenPrefs().getString(C.USER_ID, null),
                    localChannelFollowsRepository = localChannelFollowsRepository,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
                    graphQLRepository = graphQLRepository,
                    helixHeaders = helixHeaders,
                    helixRepository = helixRepository,
                    enableIntegrity = enableIntegrity,
                    networkLibrary = networkLibrary,
                )
            )
        }
        // languages of the "Popular" row, chosen in the settings (all when none)
        val languages = prefs.getStringSet(C.TV_HOME_LANGUAGES, null)?.filter { it.isNotBlank() }?.ifEmpty { null }
        jobs += viewModelScope.launch {
            popular.value = loadPage(
                StreamsDataSource(
                    gqlQueryLanguages = languages?.mapNotNull { language -> com.github.andreyasadchy.xtra.graphql.type.Language.entries.find { it.rawValue == language } },
                    gqlQuerySort = StreamSort.VIEWER_COUNT,
                    gqlLanguages = languages,
                    gqlSort = "VIEWER_COUNT",
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
