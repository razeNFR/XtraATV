package com.github.andreyasadchy.xtra.repository

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.api.json.buildJsonString
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.json.writeObject
import com.apollographql.apollo.api.parseResponse
import com.github.andreyasadchy.xtra.graphql.BadgesQuery
import com.github.andreyasadchy.xtra.graphql.ClipQuery
import com.github.andreyasadchy.xtra.graphql.ClipUrlsQuery
import com.github.andreyasadchy.xtra.graphql.EmoteQuery
import com.github.andreyasadchy.xtra.graphql.GameBoxArtQuery
import com.github.andreyasadchy.xtra.graphql.GameClipsQuery
import com.github.andreyasadchy.xtra.graphql.GameQuery
import com.github.andreyasadchy.xtra.graphql.GameStreamsQuery
import com.github.andreyasadchy.xtra.graphql.GameVideosQuery
import com.github.andreyasadchy.xtra.graphql.SearchChannelsQuery
import com.github.andreyasadchy.xtra.graphql.SearchFreeformTagsQuery
import com.github.andreyasadchy.xtra.graphql.SearchGameTagsQuery
import com.github.andreyasadchy.xtra.graphql.SearchGamesQuery
import com.github.andreyasadchy.xtra.graphql.SearchStreamsQuery
import com.github.andreyasadchy.xtra.graphql.SearchVideosQuery
import com.github.andreyasadchy.xtra.graphql.SelfFollowingGameQuery
import com.github.andreyasadchy.xtra.graphql.SelfFollowingUserQuery
import com.github.andreyasadchy.xtra.graphql.StreamPlaybackAccessTokenQuery
import com.github.andreyasadchy.xtra.graphql.TagQuery
import com.github.andreyasadchy.xtra.graphql.TeamLiveMembersQuery
import com.github.andreyasadchy.xtra.graphql.TeamMembersQuery
import com.github.andreyasadchy.xtra.graphql.TeamQuery
import com.github.andreyasadchy.xtra.graphql.TopGamesQuery
import com.github.andreyasadchy.xtra.graphql.TopStreamsQuery
import com.github.andreyasadchy.xtra.graphql.UserAboutQuery
import com.github.andreyasadchy.xtra.graphql.UserBadgesQuery
import com.github.andreyasadchy.xtra.graphql.UserChannelPageQuery
import com.github.andreyasadchy.xtra.graphql.UserChattersQuery
import com.github.andreyasadchy.xtra.graphql.UserCheerEmotesQuery
import com.github.andreyasadchy.xtra.graphql.UserClipsQuery
import com.github.andreyasadchy.xtra.graphql.UserEmotesQuery
import com.github.andreyasadchy.xtra.graphql.UserFollowedGamesQuery
import com.github.andreyasadchy.xtra.graphql.UserFollowedStreamsQuery
import com.github.andreyasadchy.xtra.graphql.UserFollowedUsersQuery
import com.github.andreyasadchy.xtra.graphql.UserFollowedVideosQuery
import com.github.andreyasadchy.xtra.graphql.UserMessageClickedQuery
import com.github.andreyasadchy.xtra.graphql.UserQuery
import com.github.andreyasadchy.xtra.graphql.UserResultIDQuery
import com.github.andreyasadchy.xtra.graphql.UserResultLoginQuery
import com.github.andreyasadchy.xtra.graphql.UserVideosQuery
import com.github.andreyasadchy.xtra.graphql.UsersLastBroadcastQuery
import com.github.andreyasadchy.xtra.graphql.UsersStreamQuery
import com.github.andreyasadchy.xtra.graphql.UsersTypeQuery
import com.github.andreyasadchy.xtra.graphql.VideoCommentsQuery
import com.github.andreyasadchy.xtra.graphql.VideoMomentsQuery
import com.github.andreyasadchy.xtra.graphql.VideoPlaybackAccessTokenQuery
import com.github.andreyasadchy.xtra.graphql.VideoQuery
import com.github.andreyasadchy.xtra.graphql.type.BadgeImageSize
import com.github.andreyasadchy.xtra.graphql.type.BroadcastType
import com.github.andreyasadchy.xtra.graphql.type.ClipsPeriod
import com.github.andreyasadchy.xtra.graphql.type.GameSort
import com.github.andreyasadchy.xtra.graphql.type.Language
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.graphql.type.VideoSort
import com.github.andreyasadchy.xtra.model.gql.ErrorResponse
import com.github.andreyasadchy.xtra.model.gql.channel.ChannelClipsResponse
import com.github.andreyasadchy.xtra.model.gql.channel.ChannelSuggestionsResponse
import com.github.andreyasadchy.xtra.model.gql.channel.ChannelVideosResponse
import com.github.andreyasadchy.xtra.model.gql.channel.ChannelViewerListResponse
import com.github.andreyasadchy.xtra.model.gql.channel.FollowUserResponse
import com.github.andreyasadchy.xtra.model.gql.chat.BadgesResponse
import com.github.andreyasadchy.xtra.model.gql.chat.ChannelCheerEmotesResponse
import com.github.andreyasadchy.xtra.model.gql.chat.ChannelPointContextResponse
import com.github.andreyasadchy.xtra.model.gql.chat.EmoteCardResponse
import com.github.andreyasadchy.xtra.model.gql.chat.GlobalCheerEmotesResponse
import com.github.andreyasadchy.xtra.model.gql.chat.ModeratorsResponse
import com.github.andreyasadchy.xtra.model.gql.chat.SendChatMessageResponse
import com.github.andreyasadchy.xtra.model.gql.chat.UserEmotesResponse
import com.github.andreyasadchy.xtra.model.gql.chat.VipsResponse
import com.github.andreyasadchy.xtra.model.gql.clip.ClipDataResponse
import com.github.andreyasadchy.xtra.model.gql.clip.ClipUrlsResponse
import com.github.andreyasadchy.xtra.model.gql.clip.ClipVideoResponse
import com.github.andreyasadchy.xtra.model.gql.followed.FollowedChannelsResponse
import com.github.andreyasadchy.xtra.model.gql.followed.FollowedGamesResponse
import com.github.andreyasadchy.xtra.model.gql.followed.FollowedStreamsResponse
import com.github.andreyasadchy.xtra.model.gql.followed.FollowedVideosResponse
import com.github.andreyasadchy.xtra.model.gql.game.GameClipsResponse
import com.github.andreyasadchy.xtra.model.gql.game.GameStreamsResponse
import com.github.andreyasadchy.xtra.model.gql.game.GameVideosResponse
import com.github.andreyasadchy.xtra.model.gql.game.GamesResponse
import com.github.andreyasadchy.xtra.model.gql.playlist.PlaybackAccessTokenResponse
import com.github.andreyasadchy.xtra.model.gql.search.SearchChannelsResponse
import com.github.andreyasadchy.xtra.model.gql.search.SearchGameTagsResponse
import com.github.andreyasadchy.xtra.model.gql.search.SearchGamesResponse
import com.github.andreyasadchy.xtra.model.gql.search.SearchStreamTagsResponse
import com.github.andreyasadchy.xtra.model.gql.search.SearchVideosResponse
import com.github.andreyasadchy.xtra.model.gql.stream.StreamsResponse
import com.github.andreyasadchy.xtra.model.gql.stream.ViewerCountResponse
import com.github.andreyasadchy.xtra.model.gql.tag.TagResponse
import com.github.andreyasadchy.xtra.model.gql.video.VideoGamesResponse
import com.github.andreyasadchy.xtra.model.gql.video.VideoMessagesResponse
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.UploadDataProviders
import java.util.concurrent.ExecutorService
import kotlin.uuid.Uuid

class GraphQLRepository(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) {

    private suspend fun <T: Query.Data> sendQuery(networkLibrary: String?, headers: Map<String, String>, query: Query<T>): ApolloResponse<T> = withContext(Dispatchers.IO) {
        val url = "https://gql.twitch.tv/gql"
        val body = buildJsonString {
            query.apply {
                writeObject {
                    name("variables")
                    writeObject {
                        serializeVariables(this, CustomScalarAdapters.Empty, false)
                    }
                    name("query")
                    value(document().replaceFirst(name(), "null"))
                }
            }
        }
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                response.body.inputStream().source().buffer().jsonReader().use {
                    query.parseResponse(it)
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                response.body.inputStream().source().buffer().jsonReader().use {
                    query.parseResponse(it)
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    response.body.byteStream().source().buffer().jsonReader().use {
                        query.parseResponse(it)
                    }
                }
            }
        }
    }

    private suspend fun sendPersistedQuery(networkLibrary: String?, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
        val url = "https://gql.twitch.tv/gql"
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                response.body.decodeToString()
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                response.body.decodeToString()
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    response.body.string()
                }
            }
        }
    }

    suspend fun loadQueryBadges(networkLibrary: String?, headers: Map<String, String>, quality: BadgeImageSize): ApolloResponse<BadgesQuery.Data> = withContext(Dispatchers.IO) {
        val query = BadgesQuery(Optional.Present(quality))
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryClip(networkLibrary: String?, headers: Map<String, String>, slug: String): ApolloResponse<ClipQuery.Data> = withContext(Dispatchers.IO) {
        val query = ClipQuery(slug)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryClipUrls(networkLibrary: String?, headers: Map<String, String>, slug: String): ApolloResponse<ClipUrlsQuery.Data> = withContext(Dispatchers.IO) {
        val query = ClipUrlsQuery(slug)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryEmote(networkLibrary: String?, headers: Map<String, String>, id: String): ApolloResponse<EmoteQuery.Data> = withContext(Dispatchers.IO) {
        val query = EmoteQuery(id)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryGame(networkLibrary: String?, headers: Map<String, String>, id: String? = null, slug: String? = null, name: String? = null): ApolloResponse<GameQuery.Data> = withContext(Dispatchers.IO) {
        val query = GameQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            slug = if (!slug.isNullOrBlank()) Optional.Present(slug) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryGameBoxArt(networkLibrary: String?, headers: Map<String, String>, id: String? = null, name: String? = null): ApolloResponse<GameBoxArtQuery.Data> = withContext(Dispatchers.IO) {
        val query = GameBoxArtQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryGameClips(networkLibrary: String?, headers: Map<String, String>, id: String?, slug: String?, name: String?, languages: List<Language>?, period: ClipsPeriod?, first: Int?, after: String?): ApolloResponse<GameClipsQuery.Data> = withContext(Dispatchers.IO) {
        val query = GameClipsQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            slug = if (!slug.isNullOrBlank()) Optional.Present(slug) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
            languages = Optional.Present(languages),
            period = Optional.Present(period),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryGameStreams(networkLibrary: String?, headers: Map<String, String>, id: String?, slug: String?, name: String?, sort: StreamSort?, tags: List<String>?, languages: List<Language>?, first: Int?, after: String?): ApolloResponse<GameStreamsQuery.Data> = withContext(Dispatchers.IO) {
        val query = GameStreamsQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            slug = if (!slug.isNullOrBlank()) Optional.Present(slug) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
            sort = Optional.Present(sort),
            tags = Optional.Present(tags),
            languages = Optional.Present(languages),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryGameVideos(networkLibrary: String?, headers: Map<String, String>, id: String?, slug: String?, name: String?, languages: List<String>?, sort: VideoSort?, type: List<BroadcastType>?, first: Int?, after: String?): ApolloResponse<GameVideosQuery.Data> = withContext(Dispatchers.IO) {
        val query = GameVideosQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            slug = if (!slug.isNullOrBlank()) Optional.Present(slug) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
            languages = Optional.Present(languages),
            sort = Optional.Present(sort),
            type = Optional.Present(type),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchChannels(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?, after: String?): ApolloResponse<SearchChannelsQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchChannelsQuery(
            query = query,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchFreeformTags(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?): ApolloResponse<SearchFreeformTagsQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchFreeformTagsQuery(
            query = query,
            first = Optional.Present(first),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchGames(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?, after: String?): ApolloResponse<SearchGamesQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchGamesQuery(
            query = query,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchGameTags(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?): ApolloResponse<SearchGameTagsQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchGameTagsQuery(
            query = query,
            first = Optional.Present(first),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchStreams(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?, after: String?): ApolloResponse<SearchStreamsQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchStreamsQuery(
            query = query,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchVideos(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?, after: String?): ApolloResponse<SearchVideosQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchVideosQuery(
            query = query,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryFollowingGame(networkLibrary: String?, headers: Map<String, String>, id: String? = null, slug: String? = null, name: String? = null): ApolloResponse<SelfFollowingGameQuery.Data> = withContext(Dispatchers.IO) {
        val query = SelfFollowingGameQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            slug = if (!slug.isNullOrBlank()) Optional.Present(slug) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryFollowingUser(networkLibrary: String?, headers: Map<String, String>, id: String?, login: String?): ApolloResponse<SelfFollowingUserQuery.Data> = withContext(Dispatchers.IO) {
        val query = SelfFollowingUserQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryStreamPlaybackAccessToken(networkLibrary: String?, headers: Map<String, String>, login: String, platform: String, playerType: String): ApolloResponse<StreamPlaybackAccessTokenQuery.Data> = withContext(Dispatchers.IO) {
        val query = StreamPlaybackAccessTokenQuery(login, platform, playerType)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTag(networkLibrary: String?, headers: Map<String, String>, id: String): ApolloResponse<TagQuery.Data> = withContext(Dispatchers.IO) {
        val query = TagQuery(
            id = id
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTeam(networkLibrary: String?, headers: Map<String, String>, name: String): ApolloResponse<TeamQuery.Data> = withContext(Dispatchers.IO) {
        val query = TeamQuery(
            name = name,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTeamLiveMembers(networkLibrary: String?, headers: Map<String, String>, name: String, first: Int?, after: String?): ApolloResponse<TeamLiveMembersQuery.Data> = withContext(Dispatchers.IO) {
        val query = TeamLiveMembersQuery(
            name = name,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTeamMembers(networkLibrary: String?, headers: Map<String, String>, name: String, first: Int?, after: String?): ApolloResponse<TeamMembersQuery.Data> = withContext(Dispatchers.IO) {
        val query = TeamMembersQuery(
            name = name,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTopGames(networkLibrary: String?, headers: Map<String, String>, sort: GameSort?, tags: List<String>?, first: Int?, after: String?): ApolloResponse<TopGamesQuery.Data> = withContext(Dispatchers.IO) {
        val query = TopGamesQuery(
            sort = if (sort != null) Optional.Present(sort) else Optional.Absent,
            tags = Optional.Present(tags),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTopStreams(networkLibrary: String?, headers: Map<String, String>, sort: StreamSort?, tags: List<String>?, languages: List<Language>?, first: Int?, after: String?): ApolloResponse<TopStreamsQuery.Data> = withContext(Dispatchers.IO) {
        val query = TopStreamsQuery(
            sort = Optional.Present(sort),
            tags = Optional.Present(tags),
            languages = Optional.Present(languages),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUser(networkLibrary: String?, headers: Map<String, String>, id: String? = null, login: String? = null): ApolloResponse<UserQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserAbout(networkLibrary: String?, headers: Map<String, String>, id: String?, login: String?): ApolloResponse<UserAboutQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserAboutQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserBadges(networkLibrary: String?, headers: Map<String, String>, id: String? = null, login: String? = null, quality: BadgeImageSize?): ApolloResponse<UserBadgesQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserBadgesQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
            quality = Optional.Present(quality),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserChannelPage(networkLibrary: String?, headers: Map<String, String>, id: String? = null, login: String? = null): ApolloResponse<UserChannelPageQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserChannelPageQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserChatters(networkLibrary: String?, headers: Map<String, String>, id: String? = null, login: String? = null): ApolloResponse<UserChattersQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserChattersQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserCheerEmotes(networkLibrary: String?, headers: Map<String, String>, id: String? = null, login: String? = null): ApolloResponse<UserCheerEmotesQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserCheerEmotesQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserClips(networkLibrary: String?, headers: Map<String, String>, id: String?, login: String?, period: ClipsPeriod?, first: Int?, after: String?): ApolloResponse<UserClipsQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserClipsQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
            period = Optional.Present(period),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserEmotes(networkLibrary: String?, headers: Map<String, String>): ApolloResponse<UserEmotesQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserEmotesQuery()
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserFollowedGames(networkLibrary: String?, headers: Map<String, String>, first: Int?): ApolloResponse<UserFollowedGamesQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserFollowedGamesQuery(
            first = Optional.Present(first),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserFollowedStreams(networkLibrary: String?, headers: Map<String, String>, first: Int?, after: String?): ApolloResponse<UserFollowedStreamsQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserFollowedStreamsQuery(
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserFollowedUsers(networkLibrary: String?, headers: Map<String, String>, first: Int?, after: String?): ApolloResponse<UserFollowedUsersQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserFollowedUsersQuery(
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserFollowedVideos(networkLibrary: String?, headers: Map<String, String>, sort: VideoSort?, type: List<BroadcastType>?, first: Int?, after: String?): ApolloResponse<UserFollowedVideosQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserFollowedVideosQuery(
            sort = Optional.Present(sort),
            type = Optional.Present(type),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserMessageClicked(networkLibrary: String?, headers: Map<String, String>, id: String? = null, login: String? = null, targetId: String?): ApolloResponse<UserMessageClickedQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserMessageClickedQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
            targetId = Optional.Present(targetId),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserResultID(networkLibrary: String?, headers: Map<String, String>, id: String): ApolloResponse<UserResultIDQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserResultIDQuery(id)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserResultLogin(networkLibrary: String?, headers: Map<String, String>, login: String): ApolloResponse<UserResultLoginQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserResultLoginQuery(login)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserVideos(networkLibrary: String?, headers: Map<String, String>, id: String?, login: String?, sort: VideoSort?, types: List<BroadcastType>?, first: Int?, after: String?): ApolloResponse<UserVideosQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserVideosQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
            sort = Optional.Present(sort),
            types = Optional.Present(types),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUsersLastBroadcast(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null): ApolloResponse<UsersLastBroadcastQuery.Data> = withContext(Dispatchers.IO) {
        val query = UsersLastBroadcastQuery(
            ids = if (!ids.isNullOrEmpty()) Optional.Present(ids) else Optional.Absent,
            logins = if (ids.isNullOrEmpty() && !logins.isNullOrEmpty()) Optional.Present(logins) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUsersStream(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null): ApolloResponse<UsersStreamQuery.Data> = withContext(Dispatchers.IO) {
        val query = UsersStreamQuery(
            ids = if (!ids.isNullOrEmpty()) Optional.Present(ids) else Optional.Absent,
            logins = if (!logins.isNullOrEmpty()) Optional.Present(logins) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUsersType(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null): ApolloResponse<UsersTypeQuery.Data> = withContext(Dispatchers.IO) {
        val query = UsersTypeQuery(
            ids = if (!ids.isNullOrEmpty()) Optional.Present(ids) else Optional.Absent,
            logins = if (!logins.isNullOrEmpty()) Optional.Present(logins) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryVideo(networkLibrary: String?, headers: Map<String, String>, id: String?): ApolloResponse<VideoQuery.Data> = withContext(Dispatchers.IO) {
        val query = VideoQuery(Optional.Present(id))
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryVideoComments(networkLibrary: String?, headers: Map<String, String>, videoId: String?, offset: Int? = null, cursor: String? = null): ApolloResponse<VideoCommentsQuery.Data> = withContext(Dispatchers.IO) {
        val query = VideoCommentsQuery(
            id = Optional.Present(videoId),
            first = Optional.Present(100),
            after = if (!cursor.isNullOrBlank()) Optional.Present(cursor) else Optional.Absent,
            offset = if (offset != null && cursor.isNullOrBlank()) Optional.Present(offset) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryVideoCommentsDownload(networkLibrary: String?, timeout: Long, okHttpClient: Lazy<OkHttpClient>, headers: Map<String, String>, videoId: String?, offset: Int? = null, cursor: String? = null): VideoMessagesResponse = withContext(Dispatchers.IO) {
        val url = "https://gql.twitch.tv/gql"
        val query = VideoCommentsQuery(
            id = Optional.Present(videoId),
            first = Optional.Present(100),
            after = if (!cursor.isNullOrBlank()) Optional.Present(cursor) else Optional.Absent,
            offset = if (offset != null && cursor.isNullOrBlank()) Optional.Present(offset) else Optional.Absent,
        )
        val body = buildJsonString {
            query.apply {
                writeObject {
                    name("variables")
                    writeObject {
                        serializeVariables(this, CustomScalarAdapters.Empty, false)
                    }
                    name("query")
                    value(document().replaceFirst(name(), "null"))
                }
            }
        }
        val response = when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout(timeout)
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                response.body.decodeToString()
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout(timeout)
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                response.body.decodeToString()
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    response.body.string()
                }
            }
        }
        json.decodeFromString<VideoMessagesResponse>(response)
    }

    suspend fun loadQueryVideoMoments(networkLibrary: String?, headers: Map<String, String>, videoId: String?): ApolloResponse<VideoMomentsQuery.Data> = withContext(Dispatchers.IO) {
        val query = VideoMomentsQuery(
            id = Optional.Present(videoId),
            first = Optional.Present(100),
            after = Optional.Present(null),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryVideoPlaybackAccessToken(networkLibrary: String?, headers: Map<String, String>, videoId: String, platform: String, playerType: String): ApolloResponse<VideoPlaybackAccessTokenQuery.Data> = withContext(Dispatchers.IO) {
        val query = VideoPlaybackAccessTokenQuery(videoId, platform, playerType)
        sendQuery(networkLibrary, headers, query)
    }

    fun getPlaybackAccessTokenRequestBody(login: String?, vodId: String?, platform: String?, playerType: String?): String {
        return buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9")
                    put("version", 1)
                }
            }
            put("operationName", "PlaybackAccessToken")
            putJsonObject("variables") {
                put("isLive", !login.isNullOrBlank())
                put("login", login ?: "")
                put("isVod", !vodId.isNullOrBlank())
                put("vodID", vodId ?: "")
                put("playerType", playerType)
                put("platform", platform)
            }
        }.toString()
    }

    suspend fun loadPlaybackAccessToken(networkLibrary: String?, headers: Map<String, String>, login: String? = null, vodId: String? = null, platform: String?, playerType: String?): PlaybackAccessTokenResponse = withContext(Dispatchers.IO) {
        val body = getPlaybackAccessTokenRequestBody(login, vodId, platform, playerType)
        json.decodeFromString<PlaybackAccessTokenResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadClipUrls(networkLibrary: String?, headers: Map<String, String>, slug: String?, supportedCodecs: List<String>): ClipUrlsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "c5ca7c4143e42f257b91b97b37fbb19206460f42f564a02095bb504fd0a31af8")
                    put("version", 1)
                }
            }
            put("operationName", "VideoAccessToken_Clip")
            putJsonObject("variables") {
                put("slug", slug)
                put("platform", "web")
                putJsonArray("supportedCodecs") {
                    supportedCodecs.forEach {
                        add(it)
                    }
                }
            }
        }.toString()
        json.decodeFromString<ClipUrlsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadClipData(networkLibrary: String?, headers: Map<String, String>, slug: String?): ClipDataResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "a33067cdf92191dccfb53aa86f878a2c271e6a3587a6731dc8275e5751dd133f")
                    put("version", 1)
                }
            }
            put("operationName", "ChannelClipCore")
            putJsonObject("variables") {
                put("clipSlug", slug)
            }
        }.toString()
        json.decodeFromString<ClipDataResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadClipVideo(networkLibrary: String?, headers: Map<String, String>, slug: String?): ClipVideoResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "9aa558e066a22227c5ef2c0a8fded3aaa57d35181ad15f63df25bff516253a90")
                    put("version", 1)
                }
            }
            put("operationName", "ChatClip")
            putJsonObject("variables") {
                put("clipSlug", slug)
            }
        }.toString()
        json.decodeFromString<ClipVideoResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadTag(networkLibrary: String?, headers: Map<String, String>, id: String): TagResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "bb28b8b7b08b55ce39d25ba8bfb0aa6c9fad53b8a89f8b4377f59db405c3fb26")
                    put("version", 1)
                }
            }
            put("operationName", "TagHandlerTag")
            putJsonObject("variables") {
                put("id", id)
            }
        }.toString()
        json.decodeFromString<TagResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadTopGames(networkLibrary: String?, headers: Map<String, String>, sort: String?, tags: List<String>?, limit: Int?, cursor: String?): GamesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "2f67f71ba89f3c0ed26a141ec00da1defecb2303595f5cda4298169549783d9e")
                    put("version", 1)
                }
            }
            put("operationName", "BrowsePage_AllDirectories")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("limit", limit)
                putJsonObject("options") {
                    put("sort", sort ?: "VIEWER_COUNT")
                    putJsonArray("tags") {
                        tags?.forEach {
                            add(it)
                        }
                    }
                }
            }
        }.toString()
        json.decodeFromString<GamesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadTopStreams(networkLibrary: String?, headers: Map<String, String>, sort: String?, tags: List<String>?, languages: List<String>?, limit: Int?, cursor: String?): StreamsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "fb60a7f9b2fe8f9c9a080f41585bd4564bea9d3030f4d7cb8ab7f9e99b1cee67")
                    put("version", 1)
                }
            }
            put("operationName", "BrowsePage_Popular")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("imageWidth", 50)
                put("includeCostreaming", true)
                put("limit", limit)
                putJsonObject("options") {
                    putJsonArray("broadcasterLanguages") {
                        languages?.forEach {
                            add(it)
                        }
                    }
                    putJsonArray("freeformTags") {
                        tags?.forEach {
                            add(it)
                        }
                    }
                    put("sort", sort)
                }
                put("platformType", "all")
                put("sortTypeIsRecency", sort == "RECENT")
            }
        }.toString()
        json.decodeFromString<StreamsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadGameStreams(networkLibrary: String?, headers: Map<String, String>, gameSlug: String?, sort: String?, tags: List<String>?, languages: List<String>?, limit: Int?, cursor: String?): GameStreamsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "76cb069d835b8a02914c08dc42c421d0dafda8af5b113a3f19141824b901402f")
                    put("version", 1)
                }
            }
            put("operationName", "DirectoryPage_Game")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("imageWidth", 50)
                put("includeCostreaming", true)
                put("limit", limit)
                putJsonObject("options") {
                    putJsonArray("broadcasterLanguages") {
                        languages?.forEach {
                            add(it)
                        }
                    }
                    putJsonArray("freeformTags") {
                        tags?.forEach {
                            add(it)
                        }
                    }
                    put("sort", sort)
                }
                put("slug", gameSlug)
                put("sortTypeIsRecency", sort == "RECENT")
            }
        }.toString()
        json.decodeFromString<GameStreamsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadGameVideos(networkLibrary: String?, headers: Map<String, String>, gameSlug: String?, type: String?, sort: String?, languages: List<String>?, limit: Int?, cursor: String?): GameVideosResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "d3f2564d36b095cb5bbe78a66b7f9d0618934fa3f49f5b9321d29c41cd0d2d0c")
                    put("version", 1)
                }
            }
            put("operationName", "DirectoryVideos_Game")
            putJsonObject("variables") {
                if (type != null) {
                    putJsonArray("broadcastTypes") {
                        add(type)
                    }
                }
                put("followedCursor", cursor)
                put("includePreviewBlur", false)
                putJsonArray("languages") {
                    languages?.forEach {
                        add(it)
                    }
                }
                put("slug", gameSlug)
                put("videoLimit", limit)
                put("videoSort", sort)
            }
        }.toString()
        json.decodeFromString<GameVideosResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadGameClips(networkLibrary: String?, headers: Map<String, String>, gameSlug: String?, period: String?, languages: List<String>?, limit: Int?, cursor: String?): GameClipsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "2211af9c7b963fa324a7ca1b2e2b5ad823c1cee5a73adcd8220b6eba10776e20")
                    put("version", 1)
                }
            }
            put("operationName", "ClipsCards__Game")
            putJsonObject("variables") {
                put("categorySlug", gameSlug)
                putJsonObject("criteria") {
                    put("filter", period)
                    putJsonArray("languages") {
                        languages?.forEach {
                            add(it)
                        }
                    }
                    put("shouldFilterByDiscoverySetting", false)
                }
                put("cursor", cursor)
                put("limit", limit)
            }
        }.toString()
        json.decodeFromString<GameClipsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadChannelSuggestions(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): ChannelSuggestionsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "b9660765905e84e7b6a1ed18937b49ef0569e9b2a1c8f7a40a1bf289fbe2ced6")
                    put("version", 1)
                }
            }
            put("operationName", "SideNav")
            putJsonObject("variables") {
                put("creatorAnniversariesFeature", false)
                putJsonObject("input") {
                    put("contextChannelName", channelLogin)
                    putJsonObject("recommendationContext") {
                        put("channelName", channelLogin)
                        put("clientApp", "twilight")
                        put("location", "channel")
                        put("platform", "web")
                    }
                }
                put("isLoggedIn", headers[C.HEADER_TOKEN] != null)
                put("withFreeformTags", true)
            }
        }.toString()
        val headers = if (headers["X-Device-Id"] == null) {
            headers.toMutableMap().apply {
                put("X-Device-Id", Uuid.random().toHexString())
            }
        } else headers
        json.decodeFromString<ChannelSuggestionsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadChannelVideos(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?, type: String?, sort: String?, limit: Int?, cursor: String?): ChannelVideosResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "53dc989e1d3cc360f7e4868c88eaaf37e06495b1d58c91c435e9e83643bb8c5c")
                    put("version", 1)
                }
            }
            put("operationName", "FilterableVideoTower_Videos")
            putJsonObject("variables") {
                put("broadcastType", type)
                put("channelOwnerLogin", channelLogin)
                put("cursor", cursor)
                put("includePreviewBlur", false)
                put("limit", limit)
                put("videoSort", sort)
            }
        }.toString()
        json.decodeFromString<ChannelVideosResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadChannelClips(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?, period: String?, limit: Int?, cursor: String?): ChannelClipsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "b06901ff6be502ab854ac2ceb8462a6b3bee2d76c750c20cbecb4907273bd2aa")
                    put("version", 1)
                }
            }
            put("operationName", "ClipsCards__User")
            putJsonObject("variables") {
                putJsonObject("criteria") {
                    put("filter", period)
                    put("shouldFilterByDiscoverySetting", false)
                }
                put("cursor", cursor)
                put("limit", limit)
                put("login", channelLogin)
            }
        }.toString()
        json.decodeFromString<ChannelClipsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadSearchChannels(networkLibrary: String?, headers: Map<String, String>, query: String?, cursor: String?): SearchChannelsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "7f3580f6ac6cd8aa1424cff7c974a07143827d6fa36bba1b54318fe7f0b68dc5")
                    put("version", 1)
                }
            }
            put("operationName", "SearchResultsPage_SearchResults")
            putJsonObject("variables") {
                put("includeIsDJ", true)
                putJsonObject("options") {
                    put("shouldSkipDiscoveryControl", true)
                    putJsonArray("targets") {
                        add(buildJsonObject {
                            if (cursor != null) {
                                put("cursor", cursor)
                            }
                            put("index", "CHANNEL")
                        })
                    }
                }
                put("query", query)
            }
        }.toString()
        json.decodeFromString<SearchChannelsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadSearchGames(networkLibrary: String?, headers: Map<String, String>, query: String?, cursor: String?): SearchGamesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "7f3580f6ac6cd8aa1424cff7c974a07143827d6fa36bba1b54318fe7f0b68dc5")
                    put("version", 1)
                }
            }
            put("operationName", "SearchResultsPage_SearchResults")
            putJsonObject("variables") {
                put("includeIsDJ", true)
                putJsonObject("options") {
                    put("shouldSkipDiscoveryControl", true)
                    putJsonArray("targets") {
                        add(buildJsonObject {
                            if (cursor != null) {
                                put("cursor", cursor)
                            }
                            put("index", "GAME")
                        })
                    }
                }
                put("query", query)
            }
        }.toString()
        json.decodeFromString<SearchGamesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadSearchVideos(networkLibrary: String?, headers: Map<String, String>, query: String?, cursor: String?): SearchVideosResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "7f3580f6ac6cd8aa1424cff7c974a07143827d6fa36bba1b54318fe7f0b68dc5")
                    put("version", 1)
                }
            }
            put("operationName", "SearchResultsPage_SearchResults")
            putJsonObject("variables") {
                put("includeIsDJ", true)
                putJsonObject("options") {
                    put("shouldSkipDiscoveryControl", true)
                    putJsonArray("targets") {
                        add(buildJsonObject {
                            if (cursor != null) {
                                put("cursor", cursor)
                            }
                            put("index", "VOD")
                        })
                    }
                }
                put("query", query)
            }
        }.toString()
        json.decodeFromString<SearchVideosResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFreeformTags(networkLibrary: String?, headers: Map<String, String>, query: String?, limit: Int?): SearchStreamTagsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "8bc91a618bb5f0c5f9bc19195028c9f4a6a1b8651cf5bd8e4f2408124cdf465a")
                    put("version", 1)
                }
            }
            put("operationName", "SearchFreeformTags")
            putJsonObject("variables") {
                put("first", limit)
                put("userQuery", query ?: "")
            }
        }.toString()
        json.decodeFromString<SearchStreamTagsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadGameTags(networkLibrary: String?, headers: Map<String, String>, query: String?, limit: Int?): SearchGameTagsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "b4cb189d8d17aadf29c61e9d7c7e7dcfc932e93b77b3209af5661bffb484195f")
                    put("version", 1)
                }
            }
            put("operationName", "SearchCategoryTags")
            putJsonObject("variables") {
                put("limit", limit)
                put("userQuery", query ?: "")
            }
        }.toString()
        json.decodeFromString<SearchGameTagsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadChatBadges(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): BadgesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "dd0997370fb7ca288bc52a96a9a7e3222c75c4a9a9b03df17d779666f07f7529")
                    put("version", 1)
                }
            }
            put("operationName", "ChatList_Badges")
            putJsonObject("variables") {
                put("channelLogin", channelLogin)
            }
        }.toString()
        json.decodeFromString<BadgesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadGlobalCheerEmotes(networkLibrary: String?, headers: Map<String, String>): GlobalCheerEmotesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "6a265b86f3be1c8d11bdcf32c183e106028c6171e985cc2584d15f7840f5fee6")
                    put("version", 1)
                }
            }
            put("operationName", "BitsConfigContext_Global")
        }.toString()
        json.decodeFromString<GlobalCheerEmotesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadChannelCheerEmotes(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): ChannelCheerEmotesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "d897953c76165a0d2a12b57c9c013a77b3cf02b5c153645e1e1631f763bf1eb5")
                    put("version", 1)
                }
            }
            put("operationName", "BitsConfigContext_Channel")
            putJsonObject("variables") {
                put("login", channelLogin)
            }
        }.toString()
        json.decodeFromString<ChannelCheerEmotesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    private fun getVideoMessagesRequestBody(videoId: String?, offset: Int?, cursor: String?): String {
        return buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a")
                    put("version", 1)
                }
            }
            put("operationName", "VideoCommentsByOffsetOrCursor")
            putJsonObject("variables") {
                put("videoID", videoId)
                if (!cursor.isNullOrBlank()) {
                    put("cursor", cursor)
                } else if (offset != null) {
                    put("contentOffsetSeconds", offset)
                }
            }
        }.toString()
    }

    suspend fun loadVideoMessages(networkLibrary: String?, headers: Map<String, String>, videoId: String?, offset: Int? = null, cursor: String? = null): VideoMessagesResponse = withContext(Dispatchers.IO) {
        val body = getVideoMessagesRequestBody(videoId, offset, cursor)
        json.decodeFromString<VideoMessagesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadVideoGames(networkLibrary: String?, headers: Map<String, String>, videoId: String?): VideoGamesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "71835d5ef425e154bf282453a926d99b328cdc5e32f36d3a209d0f4778b41203")
                    put("version", 1)
                }
            }
            put("operationName", "VideoPlayer_ChapterSelectButtonVideo")
            putJsonObject("variables") {
                put("videoID", videoId)
            }
        }.toString()
        json.decodeFromString<VideoGamesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    private fun parseMutedSegmentsResponse(rawText: String): List<Pair<Long, Long>>? {
        val parsed = runCatching { json.parseToJsonElement(rawText) }.getOrNull() ?: return null
        val root = (parsed as? JsonObject) ?: ((parsed as? JsonArray)?.firstOrNull() as? JsonObject) ?: return null
        val hasErrors = (root["errors"] as? JsonArray)?.isNotEmpty() == true
        val videoObj = (root["data"] as? JsonObject)?.get("video") as? JsonObject
        if (videoObj == null) {
            return if (hasErrors) null else emptyList()
        }
        if ("muteInfo" !in videoObj) {
            return null
        }
        val muteInfoObj = videoObj["muteInfo"] as? JsonObject ?: return emptyList()
        val connObj = muteInfoObj["mutedSegmentConnection"] as? JsonObject ?: return emptyList()
        val nodes = connObj["nodes"] as? JsonArray ?: return emptyList()
        val rawRanges = ArrayList<Pair<Long, Long>>(nodes.size)
        for (element in nodes) {
            val node = element as? JsonObject ?: continue
            val offsetSec = (node["offset"] as? JsonPrimitive)?.intOrNull ?: continue
            val durationSec = (node["duration"] as? JsonPrimitive)?.intOrNull ?: continue
            if (offsetSec >= 0 && durationSec > 0) {
                val startMs = offsetSec * 1000L
                val endMs = (offsetSec + durationSec) * 1000L
                rawRanges.add(startMs to endMs)
            }
        }
        if (rawRanges.isEmpty()) return emptyList()
        rawRanges.sortBy { it.first }
        val merged = ArrayList<Pair<Long, Long>>(rawRanges.size)
        for ((startMs, endMs) in rawRanges) {
            val last = merged.lastOrNull()
            if (last != null && startMs <= last.second + 2_000L) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, endMs)
            } else {
                merged.add(startMs to endMs)
            }
        }
        return merged
    }

    suspend fun loadVideoMutedSegments(
        networkLibrary: String?,
        webHeaders: Map<String, String>,
        fallbackHeaders: Map<String, String>,
        videoId: String?,
    ): List<Pair<Long, Long>>? = withContext(Dispatchers.IO) {
        if (videoId.isNullOrBlank()) return@withContext emptyList()
        val persistedBody = buildJsonObject {
            put("operationName", "VideoPlayer_MutedSegmentsAlertOverlay")
            putJsonObject("variables") {
                put("vodID", videoId)
                put("includePrivate", false)
            }
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", "c36e7400657815f4704e6063d265dff766ed8fc1590361c6d71e4368805e0b49")
                }
            }
        }.toString()

        runCatching {
            parseMutedSegmentsResponse(sendPersistedQuery(networkLibrary, webHeaders, persistedBody))
        }.getOrNull()?.let { return@withContext it }

        if (fallbackHeaders.isNotEmpty() && fallbackHeaders[C.HEADER_CLIENT_ID] != webHeaders[C.HEADER_CLIENT_ID]) {
            runCatching {
                parseMutedSegmentsResponse(sendPersistedQuery(networkLibrary, fallbackHeaders, persistedBody))
            }.getOrNull()?.let { return@withContext it }
        }

        val rawBody = buildJsonObject {
            put("operationName", "VodMuteInfo")
            put("query", "query VodMuteInfo(\$id: ID!) { video(id: \$id) { id muteInfo { mutedSegmentConnection { nodes { offset duration } } } } }")
            putJsonObject("variables") {
                put("id", videoId)
            }
        }.toString()

        runCatching {
            parseMutedSegmentsResponse(sendPersistedQuery(networkLibrary, webHeaders, rawBody))
        }.getOrNull()?.let { return@withContext it }

        val mobileHeaders = webHeaders.toMutableMap().apply {
            put(C.HEADER_CLIENT_ID, "ue6666qo983tsx6so1t0vnawi233wa")
            remove(C.HEADER_TOKEN)
        }
        runCatching {
            parseMutedSegmentsResponse(sendPersistedQuery(networkLibrary, mobileHeaders, rawBody))
        }.getOrNull()
    }

    suspend fun loadChannelViewerList(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): ChannelViewerListResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "2e71a3399875770c1e5d81a9774d9803129c44cf8f6bad64973aa0d239a88caf")
                    put("version", 1)
                }
            }
            put("operationName", "CommunityTab")
            putJsonObject("variables") {
                put("login", channelLogin)
            }
        }.toString()
        json.decodeFromString<ChannelViewerListResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadViewerCount(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): ViewerCountResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "00b11c9c428f79ae228f30080a06ffd8226a1f068d6f52fbc057cbde66e994c2")
                    put("version", 1)
                }
            }
            put("operationName", "UseViewCount")
            putJsonObject("variables") {
                put("channelLogin", channelLogin)
            }
        }.toString()
        json.decodeFromString<ViewerCountResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadEmoteCard(networkLibrary: String?, headers: Map<String, String>, emoteId: String?): EmoteCardResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "65ac84241dfe071efc00be7aebcc4064eec8164acb477a2e4a06e56dceb66013")
                    put("version", 1)
                }
            }
            put("operationName", "EmoteCard")
            putJsonObject("variables") {
                put("emoteID", emoteId)
                put("octaneEnabled", true)
                put("artistEnabled", true)
            }
        }.toString()
        json.decodeFromString<EmoteCardResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowedStreams(networkLibrary: String?, headers: Map<String, String>, limit: Int?, cursor: String?): FollowedStreamsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "de3e2d0230fdb0a1da1e91e535ad839ddedaecc2c9c462c1d35d97e7b0b425ae")
                    put("version", 1)
                }
            }
            put("operationName", "FollowingLive_CurrentUser")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("imageWidth", 50)
                put("includeCostreaming", true)
                put("limit", limit)
            }
        }.toString()
        json.decodeFromString<FollowedStreamsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowedVideos(networkLibrary: String?, headers: Map<String, String>, limit: Int?, cursor: String?): FollowedVideosResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "f2c1304cb0c2c46436ca48778c5b17d486750d7beeabe3bb9719fc47d765f2c8")
                    put("version", 1)
                }
            }
            put("operationName", "FollowedVideos_CurrentUser")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("includePreviewBlur", false)
                put("limit", limit)
            }
        }.toString()
        json.decodeFromString<FollowedVideosResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowedChannels(networkLibrary: String?, headers: Map<String, String>, limit: Int?, cursor: String?): FollowedChannelsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "eecf815273d3d949e5cf0085cc5084cd8a1b5b7b6f7990cf43cb0beadf546907")
                    put("version", 1)
                }
            }
            put("operationName", "ChannelFollows")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("limit", limit)
                put("order", "DESC")
            }
        }.toString()
        json.decodeFromString<FollowedChannelsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowedGames(networkLibrary: String?, headers: Map<String, String>, limit: Int?): FollowedGamesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "f3c5d45175d623ed3d5ff4ca4c7de379ea6a1a4852236087dc1b81b7dbfd3114")
                    put("version", 1)
                }
            }
            put("operationName", "FollowingGames_CurrentUser")
            putJsonObject("variables") {
                put("limit", limit)
                put("type", "ALL")
            }
        }.toString()
        json.decodeFromString<FollowedGamesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowUser(networkLibrary: String?, headers: Map<String, String>, userId: String?, disableNotifications: Boolean): FollowUserResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "800e7346bdf7e5278a3c1d3f21b2b56e2639928f86815677a7126b093b2fdd08")
                    put("version", 1)
                }
            }
            put("operationName", "FollowButton_FollowUser")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("disableNotifications", disableNotifications)
                    put("targetID", userId)
                }
            }
        }.toString()
        json.decodeFromString<FollowUserResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadUnfollowUser(networkLibrary: String?, headers: Map<String, String>, userId: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "f7dae976ebf41c755ae2d758546bfd176b4eeb856656098bb40e0a672ca0d880")
                    put("version", 1)
                }
            }
            put("operationName", "FollowButton_UnfollowUser")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("targetID", userId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadToggleNotificationsUser(networkLibrary: String?, headers: Map<String, String>, userId: String?, disableNotifications: Boolean): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "2319a2486246f63b13ffc0d1c317c89df177150185352791a81eb7bced0128a1")
                    put("version", 1)
                }
            }
            put("operationName", "LiveNotificationsToggle_ToggleNotifications")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("disableNotifications", disableNotifications)
                    put("targetID", userId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowGame(networkLibrary: String?, headers: Map<String, String>, gameId: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "b846b65ba4bc9a3561dbe2d069d95deed9b9e031bcfda2482d1bedd84a1c2eb3")
                    put("version", 1)
                }
            }
            put("operationName", "FollowGameButton_FollowGame")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("gameID", gameId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadUnfollowGame(networkLibrary: String?, headers: Map<String, String>, gameId: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "811e02e396ebba0664f21ff002f2eff3c6f57e8af9aedb4f4dfa77cefd0db43d")
                    put("version", 1)
                }
            }
            put("operationName", "FollowGameButton_UnfollowGame")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("gameID", gameId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadChannelPointsContext(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): ChannelPointContextResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "33884dcfeab64eb44c2d61255145643cf69ea993e0c6c2ecb0fa46238586a111")
                    put("version", 1)
                }
            }
            put("operationName", "ChannelPointsContext")
            putJsonObject("variables") {
                put("channelLogin", channelLogin)
                putJsonArray("includeGoalTypes") {
                    add("CREATOR")
                    add("BOOST")
                }
            }
        }.toString()
        json.decodeFromString<ChannelPointContextResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadClaimPoints(networkLibrary: String?, headers: Map<String, String>, channelId: String?, claimId: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "46aaeebe02c99afdf4fc97c7c0cba964124bf6b0af229395f1f6d1feed05b3d0")
                    put("version", 1)
                }
            }
            put("operationName", "ClaimCommunityPoints")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("claimID", claimId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadJoinRaid(networkLibrary: String?, headers: Map<String, String>, raidId: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "c6a332a86d1087fbbb1a8623aa01bd1313d2386e7c63be60fdb2d1901f01a4ae")
                    put("version", 1)
                }
            }
            put("operationName", "JoinRaid")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("raidID", raidId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadUserEmotes(networkLibrary: String?, headers: Map<String, String>, channelId: String?, cursor: String?): UserEmotesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "6c45e0ecaa823cc7db3ecdd1502af2223c775bdcfb0f18a3a0ce9a0b7db8ef6c")
                    put("version", 1)
                }
            }
            put("operationName", "AvailableEmotesForChannelPaginated")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("channelID", channelId)
                put("pageLimit", 350)
                put("withOwner", true)
            }
        }.toString()
        json.decodeFromString<UserEmotesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun sendMessage(networkLibrary: String?, headers: Map<String, String>, channelId: String?, message: String?, replyId: String?): SendChatMessageResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "0435464292cf380ed4b3d905e4edcb73078362e82c06367a5b2181c76c822fa2")
                    put("version", 1)
                }
            }
            put("operationName", "sendChatMessage")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("message", message)
                    put("replyParentMessageID", replyId)
                }
            }
        }.toString()
        json.decodeFromString<SendChatMessageResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun sendAnnouncement(networkLibrary: String?, headers: Map<String, String>, channelId: String?, message: String?, color: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "f9e37b572ceaca1475d8d50805ae64d6eb388faf758556b2719f44d64e5ba791")
                    put("version", 1)
                }
            }
            put("operationName", "SendAnnouncementMessage")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("message", message)
                    put("color", color)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun banUser(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetLogin: String?, duration: String? = null, reason: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "d7be2d2e1e22813c1c2f3d9d5bf7e425d815aeb09e14001a5f2c140b93f6fb67")
                    put("version", 1)
                }
            }
            put("operationName", "Chat_BanUserFromChatRoom")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("bannedUserLogin", targetLogin)
                    put("expiresIn", duration)
                    put("reason", reason)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun unbanUser(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetLogin: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "bee22da7ae03569eb9ae41ef857fd1bb75507d4984d764a81fe8775accac71bd")
                    put("version", 1)
                }
            }
            put("operationName", "Chat_UnbanUserFromChatRoom")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("bannedUserLogin", targetLogin)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun updateChatColor(networkLibrary: String?, headers: Map<String, String>, color: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "0371259a74a3db4ff4bf4473d998d8ae8e4f135b20403323691d434f2790e081")
                    put("version", 1)
                }
            }
            put("operationName", "Chat_UpdateChatColor")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("color", color)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun updateChatSettings(networkLibrary: String?, headers: Map<String, String>, channelId: String?, emote: Boolean? = null): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "6d8b11f4e29f87be5e2397dd54b2df669e9a5aacd831252d88b7b7a6616dc170")
                    put("version", 1)
                }
            }
            put("operationName", "UpdateChatSettings")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    emote?.let { put("isEmoteOnlyModeEnabled", it) }
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun setFollowersOnlyMode(networkLibrary: String?, headers: Map<String, String>, channelId: String?, duration: Int?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "0ee2e448691c84b4be72bcd1ae6c51fcf512414fe372e502fe67d3c7eaf8da31")
                    put("version", 1)
                }
            }
            put("operationName", "SetFollowersOnlyModeSetting")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("followersOnlyDurationMinutes", duration)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun setSlowMode(networkLibrary: String?, headers: Map<String, String>, channelId: String?, duration: Int?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "8cf824c7fbe97e1fbde96125bffabd5b315b7f370c2ddae317f058a7b9b1c57d")
                    put("version", 1)
                }
            }
            put("operationName", "SetSlowModeSetting")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("slowModeDurationSeconds", duration)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun createStreamMarker(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "c65f8b33e3bcccf2b16057e8f445311d213ecf8729f842ccdc71908231fa9a78")
                    put("version", 1)
                }
            }
            put("operationName", "VideoMarkersChatCommand")
            putJsonObject("variables") {
                put("channelLogin", channelLogin)
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun getModerators(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): ModeratorsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "cb912a7e0789e0f8a4c85c25041a08324475831024d03d624172b59498caf085")
                    put("version", 1)
                }
            }
            put("operationName", "Mods")
            putJsonObject("variables") {
                put("login", channelLogin)
            }
        }.toString()
        json.decodeFromString<ModeratorsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun addModerator(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetLogin: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "46da4ec4229593fe4b1bce911c75625c299638e228262ff621f80d5067695a8a")
                    put("version", 1)
                }
            }
            put("operationName", "ModUser")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("targetLogin", targetLogin)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun removeModerator(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetLogin: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "1ed42ccb3bc3a6e79f51e954a2df233827f94491fbbb9bd05b22b1aaaf219b8b")
                    put("version", 1)
                }
            }
            put("operationName", "UnmodUser")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("targetLogin", targetLogin)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun startRaid(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "f4fc7ac482599d81dfb6aa37100923c8c9edeea9ca2be854102a6339197f840a")
                    put("version", 1)
                }
            }
            put("operationName", "chatCreateRaid")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("sourceID", channelId)
                    put("targetID", targetId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun cancelRaid(networkLibrary: String?, headers: Map<String, String>, channelId: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "c388b89e7616a11a8a07b75e3d7bbe7278d37c3c46f43d7c8d4d0262edc00cd9")
                    put("version", 1)
                }
            }
            put("operationName", "chatCancelRaid")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("sourceID", channelId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun getVips(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): VipsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "612a574d07afe5db2f9e878e290225224a0b955e65b5d1235dcd4b68ff668218")
                    put("version", 1)
                }
            }
            put("operationName", "VIPs")
            putJsonObject("variables") {
                put("login", channelLogin)
            }
        }.toString()
        json.decodeFromString<VipsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun addVip(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetLogin: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "e8c397f1ed8b1fdbaa201eedac92dd189ecfb2d828985ec159d4ae77f9920170")
                    put("version", 1)
                }
            }
            put("operationName", "VIPUser")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("granteeLogin", targetLogin)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun removeVip(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetLogin: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "2ce4fcdf6667d013aa1f820010e699d1d4abdda55e26539ecf4efba8aff2d661")
                    put("version", 1)
                }
            }
            put("operationName", "UnVIPUser")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("revokeeLogin", targetLogin)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    data class ScheduleSegmentItem(
        val id: String,
        val startAt: String?,
        val endAt: String?,
        val title: String?,
        val categoryId: String?,
        val categorySlug: String?,
        val categoryName: String?,
        val boxArtUrl: String?,
    )

    suspend fun loadChannelSchedule(
        networkLibrary: String?,
        headers: Map<String, String>,
        channelId: String?,
        channelLogin: String?,
    ): List<ScheduleSegmentItem> = withContext(Dispatchers.IO) {
        val useId = !channelId.isNullOrBlank()
        if (!useId && channelLogin.isNullOrBlank()) return@withContext emptyList()
        val body = buildJsonObject {
            put(
                "query",
                if (useId) {
                    "query(\$id: ID!) { user(id: \$id, lookupType: ALL) { channel { schedule { segments { id startAt endAt title categories { id slug displayName boxArtURL(width: 144, height: 192) } } } } } }"
                } else {
                    "query(\$login: String!) { user(login: \$login, lookupType: ALL) { channel { schedule { segments { id startAt endAt title categories { id slug displayName boxArtURL(width: 144, height: 192) } } } } } }"
                }
            )
            putJsonObject("variables") {
                if (useId) put("id", channelId) else put("login", channelLogin)
            }
        }.toString()
        runCatching {
            fun JsonElement?.obj(): JsonObject? = this as? JsonObject
            fun JsonElement?.arr(): JsonArray? = this as? JsonArray
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

            val raw = sendPersistedQuery(networkLibrary, headers, body)
            val segments = json.parseToJsonElement(raw).obj()
                ?.get("data").obj()
                ?.get("user").obj()
                ?.get("channel").obj()
                ?.get("schedule").obj()
                ?.get("segments").arr()
                ?: return@runCatching emptyList()
            segments.mapNotNull { el ->
                val seg = el.obj() ?: return@mapNotNull null
                val id = seg["id"].str() ?: return@mapNotNull null
                val cat = seg["categories"].arr()?.firstOrNull().obj()
                ScheduleSegmentItem(
                    id = id,
                    startAt = seg["startAt"].str(),
                    endAt = seg["endAt"].str(),
                    title = seg["title"].str(),
                    categoryId = cat?.get("id").str(),
                    categorySlug = cat?.get("slug").str(),
                    categoryName = cat?.get("displayName").str(),
                    boxArtUrl = cat?.get("boxArtURL").str(),
                )
            }
        }.getOrDefault(emptyList())
    }

    enum class DropClaimResult {
        CLAIMED,
        ALREADY_CLAIMED,
        FAILED,
    }

    private val recentlyClaimedDropInstanceIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet())

    data class ClaimableDropItem(
        val dropInstanceId: String,
        val dropName: String?,
        val campaignName: String?,
    )

    suspend fun claimDropRewards(
        networkLibrary: String?,
        headers: Map<String, String>,
        dropInstanceId: String,
    ): DropClaimResult = withContext(Dispatchers.IO) {
        if (dropInstanceId.isBlank()) return@withContext DropClaimResult.FAILED
        val body = buildJsonObject {
            put("operationName", "DropsPage_ClaimDropRewards")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", "a455deea71bdc9015b78eb49f4acfbce8baa7ccbedd28e549bb025bd0f751930")
                }
            }
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("dropInstanceID", dropInstanceId)
                }
            }
        }.toString()
        val rawFallbackBody = buildJsonObject {
            put(
                "query",
                "mutation(\$input: ClaimDropRewardsInput!) { claimDropRewards(input: \$input) { status } }",
            )
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("dropInstanceID", dropInstanceId)
                }
            }
        }.toString()
        val result = runCatching {
            fun JsonElement?.obj(): JsonObject? = this as? JsonObject
            fun JsonElement?.arr(): JsonArray? = this as? JsonArray
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

            var raw = sendPersistedQuery(networkLibrary, headers, body)
            if (raw.contains("PersistedQueryNotFound")) {
                raw = sendPersistedQuery(networkLibrary, headers, rawFallbackBody)
            }
            val root = json.parseToJsonElement(raw).obj() ?: return@runCatching DropClaimResult.FAILED
            if (root["errors"].arr()?.isNotEmpty() == true) return@runCatching DropClaimResult.FAILED
            val dataObj = root["data"].obj() ?: return@runCatching DropClaimResult.FAILED
            if (dataObj["errors"].arr()?.isNotEmpty() == true) return@runCatching DropClaimResult.FAILED
            val claimNode = dataObj["claimDropRewards"].obj() ?: return@runCatching DropClaimResult.FAILED
            when (claimNode["status"].str()) {
                "ELIGIBLE_FOR_ALL" -> DropClaimResult.CLAIMED
                "DROP_INSTANCE_ALREADY_CLAIMED" -> DropClaimResult.ALREADY_CLAIMED
                else -> DropClaimResult.FAILED
            }
        }.getOrDefault(DropClaimResult.FAILED)
        if (result == DropClaimResult.CLAIMED || result == DropClaimResult.ALREADY_CLAIMED) {
            recentlyClaimedDropInstanceIds.add(dropInstanceId)
        }
        result
    }

    suspend fun loadClaimableDropsFromInventory(
        networkLibrary: String?,
        headers: Map<String, String>,
    ): List<ClaimableDropItem> = withContext(Dispatchers.IO) {
        fun buildInventoryBody(hash: String): String = buildJsonObject {
            put("operationName", "Inventory")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
            putJsonObject("variables") {
                put("fetchRewardCampaigns", false)
            }
        }.toString()

        runCatching {
            fun JsonElement?.obj(): JsonObject? = this as? JsonObject
            fun JsonElement?.arr(): JsonArray? = this as? JsonArray
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
            fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

            var raw = sendPersistedQuery(
                networkLibrary,
                headers,
                buildInventoryBody("d86775d0ef16a63a33ad52e80eaff963b2d5b72fada7c991504a57496e1d8e4b"),
            )
            if (raw.contains("PersistedQueryNotFound")) {
                raw = sendPersistedQuery(
                    networkLibrary,
                    headers,
                    buildInventoryBody("8337eb8541b314040b0edde0c09c5c7a2783ba1960aa9edfbf3bac16d0fec404"),
                )
            }
            val campaigns = json.parseToJsonElement(raw).obj()
                ?.get("data").obj()
                ?.get("currentUser").obj()
                ?.get("inventory").obj()
                ?.get("dropCampaignsInProgress").arr()
                ?: return@runCatching emptyList()
            val nowMs = System.currentTimeMillis()
            val claimGraceMs = 24L * 3600L * 1000L
            val results = ArrayList<ClaimableDropItem>()
            for (campaignEl in campaigns) {
                val campaign = campaignEl.obj() ?: continue
                val campaignName = campaign["name"].str()?.takeIf { it.isNotBlank() }
                val endAtStr = campaign["endAt"].str()
                val endAtMs = endAtStr?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() }
                if (endAtMs != null && nowMs > endAtMs + claimGraceMs) continue
                val drops = campaign["timeBasedDrops"].arr() ?: continue
                for (dropEl in drops) {
                    val drop = dropEl.obj() ?: continue
                    val selfObj = drop["self"].obj() ?: continue
                    val isClaimed = selfObj["isClaimed"].bool() ?: false
                    val instanceId = selfObj["dropInstanceID"].str()?.takeIf { it.isNotBlank() } ?: continue
                    if (!isClaimed && instanceId !in recentlyClaimedDropInstanceIds) {
                        results.add(
                            ClaimableDropItem(
                                dropInstanceId = instanceId,
                                dropName = drop["name"].str()?.takeIf { it.isNotBlank() },
                                campaignName = campaignName,
                            )
                        )
                    }
                }
            }
            results
        }.getOrDefault(emptyList())
    }

    data class PinnedChatItem(
        val id: String,
        val messageText: String,
        val senderName: String?,
        val senderLogin: String?,
        val pinnedByName: String?,
        val startsAtMs: Long?,
        val endsAtMs: Long?,
    )

    suspend fun loadPinnedChatMessage(
        networkLibrary: String?,
        headers: Map<String, String>,
        channelId: String?,
    ): PinnedChatItem? = withContext(Dispatchers.IO) {
        if (channelId.isNullOrBlank()) return@withContext null
        val body = buildJsonObject {
            put(
                "query",
                "query(\$id: ID!) { channel(id: \$id) { pinnedChatMessages(first: 1) { edges { node { id startsAt endsAt pinnedBy { login displayName } pinnedMessage { id content { text } sender { login displayName } } } } } } }",
            )
            putJsonObject("variables") {
                put("id", channelId)
            }
        }.toString()
        runCatching {
            fun JsonElement?.obj(): JsonObject? = this as? JsonObject
            fun JsonElement?.arr(): JsonArray? = this as? JsonArray
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

            val raw = sendPersistedQuery(networkLibrary, headers, body)
            val node = json.parseToJsonElement(raw).obj()
                ?.get("data").obj()
                ?.get("channel").obj()
                ?.get("pinnedChatMessages").obj()
                ?.get("edges").arr()
                ?.firstOrNull().obj()
                ?.get("node").obj()
                ?: return@runCatching null
            val id = node["id"].str()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val pinnedMsg = node["pinnedMessage"].obj()
            val text = pinnedMsg?.get("content").obj()?.get("text").str()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val sender = pinnedMsg?.get("sender").obj()
            val pinnedBy = node["pinnedBy"].obj()
            val startsAtMs = node["startsAt"].str()?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() }
            val endsAtMs = node["endsAt"].str()?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() }
            if (endsAtMs != null && endsAtMs > 0L && System.currentTimeMillis() >= endsAtMs) {
                return@runCatching null
            }
            PinnedChatItem(
                id = id,
                messageText = text,
                senderName = sender?.get("displayName").str()?.takeIf { it.isNotBlank() },
                senderLogin = sender?.get("login").str()?.takeIf { it.isNotBlank() },
                pinnedByName = pinnedBy?.get("displayName").str()?.takeIf { it.isNotBlank() }
                    ?: pinnedBy?.get("login").str()?.takeIf { it.isNotBlank() },
                startsAtMs = startsAtMs,
                endsAtMs = endsAtMs,
            )
        }.getOrNull()
    }

    data class BatchCoStreamEntry(
        val partnerIds: List<String>,
        val partnerLogins: List<String>,
        val fallbackCount: Int,
    )

    private val numericChannelIdRegex = Regex("^\\d{1,20}$")

    /**
     * Batched GraphQL lookup for active Twitch Stream Together (`channel.collaboration`),
     * Shared Chat (`sharedChatSession`), and official event Co-streams (`stream.costreamDetails`)
     * across multiple [channelIds] in parallel aliased chunks.
     */
    suspend fun loadBatchCoStreamInfo(
        networkLibrary: String?,
        headers: Map<String, String>,
        channelIds: List<String>,
    ): Map<String, BatchCoStreamEntry> = withContext(Dispatchers.IO) {
        val validIds = channelIds.map { it.trim() }
            .filter { numericChannelIdRegex.matches(it) }
            .distinct()
        if (validIds.isEmpty()) return@withContext emptyMap()

        fun JsonElement?.obj(): JsonObject? = this as? JsonObject
        fun JsonElement?.arr(): JsonArray? = this as? JsonArray
        fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
        fun JsonElement?.intVal(): Int? = (this as? JsonPrimitive)?.intOrNull

        val result = LinkedHashMap<String, BatchCoStreamEntry>()
        for (chunk in validIds.chunked(20)) {
            val partnerIdsByChannel = chunk.associateWith { LinkedHashSet<String>() }
            val partnerLoginsByChannel = chunk.associateWith { LinkedHashSet<String>() }
            val fallbackCountByChannel = HashMap<String, Int>()

            coroutineScope {
                // 1) Twitch Stream Together / Collaboration roster: channel(id:).collaboration.collaborators
                val collabDeferred = async {
                    runCatching {
                        val varsDecl = chunk.indices.joinToString(", ") { "\$id$it: ID!" }
                        val fields = chunk.indices.joinToString(" ") { i ->
                            "c$i: channel(id: \$id$i) { collaboration { collaborators { status user { id login } } } }"
                        }
                        val body = buildJsonObject {
                            put("query", "query CollaborationRoster($varsDecl) { $fields }")
                            putJsonObject("variables") {
                                chunk.forEachIndexed { i, id -> put("id$i", id) }
                            }
                        }.toString()
                        val raw = sendPersistedQuery(networkLibrary, headers, body)
                        val data = json.parseToJsonElement(raw).obj()?.get("data").obj() ?: return@runCatching
                        chunk.forEachIndexed { i, selfId ->
                            val collaborators = data["c$i"].obj()
                                ?.get("collaboration").obj()
                                ?.get("collaborators").arr()
                                ?: return@forEachIndexed
                            collaborators.forEach { el ->
                                val c = el.obj() ?: return@forEach
                                val st = c["status"].str()
                                if (st == null || st.equals("ACTIVE", ignoreCase = true) || st.equals("JOINED", ignoreCase = true)) {
                                    val userObj = c["user"].obj() ?: return@forEach
                                    val uid = userObj["id"].str()?.takeIf { it.isNotBlank() }
                                    val ulogin = userObj["login"].str()?.takeIf { it.isNotBlank() }
                                    if (uid != null && uid != selfId) {
                                        partnerIdsByChannel[selfId]?.add(uid)
                                        if (ulogin != null) partnerLoginsByChannel[selfId]?.add(ulogin.lowercase())
                                    }
                                }
                            }
                        }
                    }
                }

                // 2) Twitch Shared Chat session: sharedChatSession(channelID:) { status host { id } participants { status channel { id } } }
                val sharedDeferred = async {
                    runCatching {
                        val varsDecl = chunk.indices.joinToString(", ") { "\$id$it: ID!" }
                        val fields = chunk.indices.joinToString(" ") { i ->
                            "s$i: sharedChatSession(channelID: \$id$i) { status host { id } participants { status channel { id } } }"
                        }
                        val body = buildJsonObject {
                            put("query", "query SharedChatBatch($varsDecl) { $fields }")
                            putJsonObject("variables") {
                                chunk.forEachIndexed { i, id -> put("id$i", id) }
                            }
                        }.toString()
                        val raw = sendPersistedQuery(networkLibrary, headers, body)
                        val data = json.parseToJsonElement(raw).obj()?.get("data").obj() ?: return@runCatching
                        chunk.forEachIndexed { i, selfId ->
                            val session = data["s$i"].obj() ?: return@forEachIndexed
                            val status = session["status"].str()
                            if (status != null && !status.equals("ACTIVE", ignoreCase = true) && !status.equals("CREATED", ignoreCase = true)) {
                                return@forEachIndexed
                            }
                            session["host"].obj()?.get("id").str()
                                ?.takeIf { it.isNotBlank() && it != selfId }
                                ?.let { partnerIdsByChannel[selfId]?.add(it) }
                            session["participants"].arr()?.forEach { el ->
                                val p = el.obj() ?: return@forEach
                                val pStatus = p["status"].str()
                                if (pStatus == null || pStatus.equals("ACTIVE", ignoreCase = true) || pStatus.equals("JOINED", ignoreCase = true)) {
                                    p["channel"].obj()?.get("id").str()
                                        ?.takeIf { it.isNotBlank() && it != selfId }
                                        ?.let { partnerIdsByChannel[selfId]?.add(it) }
                                }
                            }
                        }
                    }
                }

                // 3) Twitch official / organizer co-streams: users(ids:) { stream { costreamDetails { costreamersCount topCostreamers { id login } } } }
                val costreamDeferred = async {
                    runCatching {
                        val body = buildJsonObject {
                            put(
                                "query",
                                "query(\$ids: [ID!]) { users(ids: \$ids) { id stream { costreamDetails { costreamersCount topCostreamers { id login } } } } }",
                            )
                            putJsonObject("variables") {
                                putJsonArray("ids") { chunk.forEach { add(it) } }
                            }
                        }.toString()
                        val raw = sendPersistedQuery(networkLibrary, headers, body)
                        val users = json.parseToJsonElement(raw).obj()
                            ?.get("data").obj()
                            ?.get("users").arr()
                            ?: return@runCatching
                        users.forEach { el ->
                            val u = el.obj() ?: return@forEach
                            val selfId = u["id"].str()?.takeIf { it.isNotBlank() } ?: return@forEach
                            val details = u["stream"].obj()?.get("costreamDetails").obj() ?: return@forEach
                            val count = details["costreamersCount"].intVal() ?: 0
                            if (count > 0) {
                                fallbackCountByChannel[selfId] = count
                            }
                            details["topCostreamers"].arr()?.forEach { tcEl ->
                                val tc = tcEl.obj() ?: return@forEach
                                val tcId = tc["id"].str()?.takeIf { it.isNotBlank() }
                                val tcLogin = tc["login"].str()?.takeIf { it.isNotBlank() }
                                if (tcId != null && tcId != selfId) {
                                    partnerIdsByChannel[selfId]?.add(tcId)
                                }
                                if (tcLogin != null) {
                                    partnerLoginsByChannel[selfId]?.add(tcLogin.lowercase())
                                }
                            }
                        }
                    }
                }

                collabDeferred.await()
                sharedDeferred.await()
                costreamDeferred.await()
            }

            chunk.forEach { id ->
                result[id] = BatchCoStreamEntry(
                    partnerIds = partnerIdsByChannel[id]?.toList().orEmpty(),
                    partnerLogins = partnerLoginsByChannel[id]?.toList().orEmpty(),
                    fallbackCount = fallbackCountByChannel[id] ?: 0,
                )
            }
        }
        result
    }

    /** Returns participant channel IDs in an active Twitch Shared Chat / Stream Together / Guest Star session for [channelId]. */
    suspend fun loadSharedChatChannelIds(
        networkLibrary: String?,
        headers: Map<String, String>,
        channelId: String?,
    ): List<String> = withContext(Dispatchers.IO) {
        val cleanId = channelId?.trim()?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val ids = LinkedHashSet<String>()
        coroutineScope {
            val batchDeferred = async {
                loadBatchCoStreamInfo(networkLibrary, headers, listOf(cleanId))[cleanId]
            }
            val guestStarDeferred = async {
                runCatching {
                    fun JsonElement?.obj(): JsonObject? = this as? JsonObject
                    fun JsonElement?.arr(): JsonArray? = this as? JsonArray
                    fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

                    val body = buildJsonObject {
                        put(
                            "query",
                            "query(\$id: ID!) { channel(id: \$id) { guestStarSessionCall { host { id } guests { user { id } } } } }",
                        )
                        putJsonObject("variables") { put("id", cleanId) }
                    }.toString()
                    val raw = sendPersistedQuery(networkLibrary, headers, body)
                    val call = json.parseToJsonElement(raw).obj()
                        ?.get("data").obj()
                        ?.get("channel").obj()
                        ?.get("guestStarSessionCall").obj()
                        ?: return@runCatching emptyList()
                    val gsIds = LinkedHashSet<String>()
                    call["host"].obj()?.get("id").str()?.takeIf { it.isNotBlank() && it != cleanId }?.let { gsIds.add(it) }
                    call["guests"].arr()?.forEach { g ->
                        g.obj()?.get("user").obj()?.get("id").str()
                            ?.takeIf { it.isNotBlank() && it != cleanId }
                            ?.let { gsIds.add(it) }
                    }
                    gsIds.toList()
                }.getOrDefault(emptyList())
            }
            batchDeferred.await()?.partnerIds?.let { ids.addAll(it) }
            ids.addAll(guestStarDeferred.await())
        }
        ids.toList()
    }

    data class CustomRewardItem(
        val id: String,
        val title: String,
        val prompt: String?,
        val cost: Int,
        val isEnabled: Boolean,
        val isPaused: Boolean,
        val isInStock: Boolean,
        val isUserInputRequired: Boolean,
        val isSubOnly: Boolean,
        val backgroundColor: String?,
        val cooldownExpiresAtMs: Long?,
        val imageUrl: String?,
    )

    data class ChannelPointsStoreData(
        val channelId: String?,
        val pointsName: String?,
        val balance: Int?,
        val availableClaimId: String?,
        val rewards: List<CustomRewardItem>,
    )

    suspend fun loadChannelPointsStore(
        networkLibrary: String?,
        headers: Map<String, String>,
        channelLogin: String?,
    ): ChannelPointsStoreData? = withContext(Dispatchers.IO) {
        if (channelLogin.isNullOrBlank()) return@withContext null
        val persistedBody = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "33884dcfeab64eb44c2d61255145643cf69ea993e0c6c2ecb0fa46238586a111")
                    put("version", 1)
                }
            }
            put("operationName", "ChannelPointsContext")
            putJsonObject("variables") {
                put("channelLogin", channelLogin)
                putJsonArray("includeGoalTypes") {
                    add("CREATOR")
                    add("BOOST")
                }
            }
        }.toString()

        val rawQueryBody = buildJsonObject {
            put(
                "query",
                "query(\$login: String!) { user(login: \$login, lookupType: ALL) { id channel { id self { communityPoints { balance availableClaim { id } } } communityPointsSettings { name isEnabled customRewards { id title prompt cost isEnabled isPaused isInStock isUserInputRequired isSubOnly backgroundColor cooldownExpiresAt image { url url2x url4x } defaultImage { url url2x url4x } } } } } }",
            )
            putJsonObject("variables") {
                put("login", channelLogin)
            }
        }.toString()

        runCatching {
            fun JsonElement?.obj(): JsonObject? = this as? JsonObject
            fun JsonElement?.arr(): JsonArray? = this as? JsonArray
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
            fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

            var raw = sendPersistedQuery(networkLibrary, headers, persistedBody)
            var dataObj = json.parseToJsonElement(raw).obj()?.get("data").obj()
            var channelObj = dataObj?.get("community").obj()?.get("channel").obj()
            if (channelObj?.get("communityPointsSettings").obj()?.get("customRewards").arr() == null) {
                val rawFallback = sendPersistedQuery(networkLibrary, headers, rawQueryBody)
                val fallbackUser = json.parseToJsonElement(rawFallback).obj()?.get("data").obj()?.get("user").obj()
                if (fallbackUser?.get("channel").obj() != null) {
                    channelObj = fallbackUser?.get("channel").obj()
                }
            }
            if (channelObj == null) return@runCatching null
            val resolvedChannelId = channelObj["id"].str()?.takeIf { it.isNotBlank() }
                ?: dataObj?.get("community").obj()?.get("id").str()?.takeIf { it.isNotBlank() }
            val selfPoints = channelObj["self"].obj()?.get("communityPoints").obj()
            val balance = selfPoints?.get("balance").int()
            val claimId = selfPoints?.get("availableClaim").obj()?.get("id").str()?.takeIf { it.isNotBlank() }
            val settings = channelObj["communityPointsSettings"].obj()
            val pointsName = settings?.get("name").str()?.takeIf { it.isNotBlank() }
            val customRewards = settings?.get("customRewards").arr()?.mapNotNull { el ->
                val r = el.obj() ?: return@mapNotNull null
                val id = r["id"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = r["title"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val cost = r["cost"].int() ?: return@mapNotNull null
                val enabled = r["isEnabled"].bool() ?: true
                if (!enabled) return@mapNotNull null
                val img = r["image"].obj()
                val defImg = r["defaultImage"].obj()
                val imageUrl = img?.get("url4x").str() ?: img?.get("url2x").str() ?: img?.get("url").str()
                    ?: defImg?.get("url4x").str() ?: defImg?.get("url2x").str() ?: defImg?.get("url").str()
                val cooldownMs = r["cooldownExpiresAt"].str()?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() }
                CustomRewardItem(
                    id = id,
                    title = title,
                    prompt = r["prompt"].str()?.takeIf { it.isNotBlank() },
                    cost = cost,
                    isEnabled = enabled,
                    isPaused = r["isPaused"].bool() ?: false,
                    isInStock = r["isInStock"].bool() ?: true,
                    isUserInputRequired = r["isUserInputRequired"].bool() ?: false,
                    isSubOnly = r["isSubOnly"].bool() ?: false,
                    backgroundColor = r["backgroundColor"].str(),
                    cooldownExpiresAtMs = cooldownMs,
                    imageUrl = imageUrl,
                )
            }?.sortedBy { it.cost } ?: emptyList()

            ChannelPointsStoreData(
                channelId = resolvedChannelId,
                pointsName = pointsName,
                balance = balance,
                availableClaimId = claimId,
                rewards = customRewards,
            )
        }.getOrNull()
    }

    data class RedeemRewardResult(
        val success: Boolean,
        val errorCode: String? = null,
    )

    suspend fun redeemCustomReward(
        networkLibrary: String?,
        headers: Map<String, String>,
        channelId: String,
        rewardId: String,
        title: String,
        cost: Int,
        prompt: String?,
        textInput: String?,
    ): RedeemRewardResult = withContext(Dispatchers.IO) {
        val txId = Uuid.random().toHexString()
        fun buildPersisted(): String = buildJsonObject {
            put("operationName", "RedeemCustomReward")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", "d56249a7adb4978898ea3412e196688d4ac3cea1c0c2dfd65561d229ea5dcc42")
                }
            }
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("cost", cost)
                    put("prompt", prompt)
                    put("rewardID", rewardId)
                    put("title", title)
                    put("transactionID", txId)
                    if (!textInput.isNullOrBlank()) {
                        put("textInput", textInput)
                    }
                }
            }
        }.toString()

        fun buildRaw(): String = buildJsonObject {
            put(
                "query",
                "mutation(\$input: RedeemCustomRewardInput!) { redeemCustomReward(input: \$input) { redemption { id } error { code } } }",
            )
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("cost", cost)
                    put("prompt", prompt)
                    put("rewardID", rewardId)
                    put("title", title)
                    put("transactionID", txId)
                    if (!textInput.isNullOrBlank()) {
                        put("textInput", textInput)
                    }
                }
            }
        }.toString()

        runCatching {
            fun JsonElement?.obj(): JsonObject? = this as? JsonObject
            fun JsonElement?.arr(): JsonArray? = this as? JsonArray
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

            var raw = sendPersistedQuery(networkLibrary, headers, buildPersisted())
            if (raw.contains("PersistedQueryNotFound")) {
                raw = sendPersistedQuery(networkLibrary, headers, buildRaw())
            }
            val root = json.parseToJsonElement(raw).obj() ?: return@runCatching RedeemRewardResult(false, "NETWORK_ERROR")
            val topErr = root["errors"].arr()?.firstOrNull().obj()?.get("message").str()
            if (!topErr.isNullOrBlank()) {
                return@runCatching RedeemRewardResult(false, topErr)
            }
            val node = root["data"].obj()?.get("redeemCustomReward").obj()
                ?: return@runCatching RedeemRewardResult(false, "UNKNOWN")
            val errCode = node["error"].obj()?.get("code").str()?.takeIf { it.isNotBlank() }
            if (errCode != null) {
                RedeemRewardResult(false, errCode)
            } else {
                RedeemRewardResult(true, null)
            }
        }.getOrDefault(RedeemRewardResult(false, "NETWORK_ERROR"))
    }

    data class DropProgressItem(
        val campaignId: String,
        val campaignName: String,
        val gameId: String? = null,
        val gameName: String?,
        val dropId: String,
        val dropName: String,
        val imageUrl: String?,
        val currentMinutes: Int,
        val requiredMinutes: Int,
        val isClaimed: Boolean,
        val dropInstanceId: String?,
        val endsAtMs: Long?,
    )

    suspend fun loadDropsProgressFromInventory(
        networkLibrary: String?,
        headers: Map<String, String>,
    ): List<DropProgressItem> = withContext(Dispatchers.IO) {
        fun buildInventoryBody(hash: String): String = buildJsonObject {
            put("operationName", "Inventory")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
            putJsonObject("variables") {
                put("fetchRewardCampaigns", false)
            }
        }.toString()

        val rawFallbackBody = buildJsonObject {
            put(
                "query",
                "query { currentUser { inventory { dropCampaignsInProgress { id name endAt game { id displayName } timeBasedDrops { id name requiredMinutesWatched benefitEdges { benefit { id name imageAssetURL } } self { isClaimed currentMinutesWatched dropInstanceID } } } } } }",
            )
        }.toString()

        runCatching {
            fun JsonElement?.obj(): JsonObject? = this as? JsonObject
            fun JsonElement?.arr(): JsonArray? = this as? JsonArray
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
            fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

            var raw = sendPersistedQuery(
                networkLibrary,
                headers,
                buildInventoryBody("d86775d0ef16a63a33ad52e80eaff963b2d5b72fada7c991504a57496e1d8e4b"),
            )
            if (raw.contains("PersistedQueryNotFound")) {
                raw = sendPersistedQuery(
                    networkLibrary,
                    headers,
                    buildInventoryBody("8337eb8541b314040b0edde0c09c5c7a2783ba1960aa9edfbf3bac16d0fec404"),
                )
            }
            if (raw.contains("PersistedQueryNotFound")) {
                raw = sendPersistedQuery(networkLibrary, headers, rawFallbackBody)
            }
            val campaigns = json.parseToJsonElement(raw).obj()
                ?.get("data").obj()
                ?.get("currentUser").obj()
                ?.get("inventory").obj()
                ?.get("dropCampaignsInProgress").arr()
                ?: return@runCatching emptyList()
            val nowMs = System.currentTimeMillis()
            val graceMs = 24L * 3600L * 1000L
            val items = ArrayList<DropProgressItem>()
            for (cEl in campaigns) {
                val c = cEl.obj() ?: continue
                val campaignId = c["id"].str() ?: ""
                val campaignName = c["name"].str()?.takeIf { it.isNotBlank() } ?: continue
                val gameObj = c["game"].obj()
                val gameId = gameObj?.get("id").str()?.takeIf { it.isNotBlank() }
                val gameName = gameObj?.let { g ->
                    g["displayName"].str()?.takeIf { it.isNotBlank() } ?: g["name"].str()?.takeIf { it.isNotBlank() }
                }
                val endAtMs = c["endAt"].str()?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() }
                if (endAtMs != null && nowMs > endAtMs + graceMs) continue
                val drops = c["timeBasedDrops"].arr() ?: continue
                for (dEl in drops) {
                    val d = dEl.obj() ?: continue
                    val dropId = d["id"].str() ?: continue
                    val required = d["requiredMinutesWatched"].int() ?: 0
                    if (required <= 0) continue
                    val selfObj = d["self"].obj()
                    val current = selfObj?.get("currentMinutesWatched").int() ?: 0
                    val isClaimed = selfObj?.get("isClaimed").bool() ?: false
                    val instanceId = selfObj?.get("dropInstanceID").str()?.takeIf { it.isNotBlank() }
                    if (isClaimed || (instanceId != null && instanceId in recentlyClaimedDropInstanceIds)) continue
                    val benefit = d["benefitEdges"].arr()?.firstOrNull().obj()?.get("benefit").obj()
                    val dropName = benefit?.get("name").str()?.takeIf { it.isNotBlank() }
                        ?: d["name"].str()?.takeIf { it.isNotBlank() }
                        ?: campaignName
                    val imageUrl = benefit?.get("imageAssetURL").str()?.takeIf { it.isNotBlank() }
                    items.add(
                        DropProgressItem(
                            campaignId = campaignId,
                            campaignName = campaignName,
                            gameId = gameId,
                            gameName = gameName,
                            dropId = dropId,
                            dropName = dropName,
                            imageUrl = imageUrl,
                            currentMinutes = current.coerceIn(0, required),
                            requiredMinutes = required,
                            isClaimed = false,
                            dropInstanceId = instanceId,
                            endsAtMs = endAtMs,
                        )
                    )
                }
            }
            items.sortedWith(
                compareByDescending<DropProgressItem> { it.dropInstanceId != null && it.currentMinutes >= it.requiredMinutes }
                    .thenByDescending { if (it.requiredMinutes > 0) it.currentMinutes.toFloat() / it.requiredMinutes else 0f }
            )
        }.getOrDefault(emptyList())
    }

    data class CreatedClipResult(
        val slug: String?,
        val url: String?,
        val error: String? = null,
    )

    suspend fun createClip(
        networkLibrary: String?,
        headers: Map<String, String>,
        broadcasterId: String,
        broadcastId: String? = null,
        videoId: String? = null,
        offsetSeconds: Double? = null,
    ): CreatedClipResult = withContext(Dispatchers.IO) {
        if (broadcasterId.isBlank()) return@withContext CreatedClipResult(null, null, "MISSING_BROADCASTER")
        fun buildInputObj() = buildJsonObject {
            put("broadcasterID", broadcasterId)
            if (!broadcastId.isNullOrBlank()) put("broadcastID", broadcastId)
            if (!videoId.isNullOrBlank()) put("videoID", videoId)
            if (offsetSeconds != null && offsetSeconds > 0.0) put("offsetSeconds", offsetSeconds)
        }
        val persistedBody = buildJsonObject {
            put("operationName", "createClip")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", "518982ccc596c0783dbfe918a72a7dbca8ef73fb7d5bf18fb48b84f1fb50ab1c")
                }
            }
            putJsonObject("variables") {
                put("input", buildInputObj())
            }
        }.toString()
        val rawMutationBody = buildJsonObject {
            put(
                "query",
                "mutation(\$input: CreateClipInput!) { createClip(input: \$input) { clip { id slug url } error { code } } }",
            )
            putJsonObject("variables") {
                put("input", buildInputObj())
            }
        }.toString()

        runCatching {
            fun JsonElement?.obj(): JsonObject? = this as? JsonObject
            fun JsonElement?.arr(): JsonArray? = this as? JsonArray
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

            var raw = sendPersistedQuery(networkLibrary, headers, persistedBody)
            if (raw.contains("PersistedQueryNotFound")) {
                raw = sendPersistedQuery(networkLibrary, headers, rawMutationBody)
            }
            val root = json.parseToJsonElement(raw).obj() ?: return@runCatching CreatedClipResult(null, null, "NETWORK_ERROR")
            val topErr = root["errors"].arr()?.firstOrNull().obj()?.get("message").str()
            val node = root["data"].obj()?.get("createClip").obj()
            val errCode = node?.get("error").obj()?.get("code").str()?.takeIf { it.isNotBlank() } ?: topErr
            val clipObj = node?.get("clip").obj()
            val slug = clipObj?.get("slug").str()?.takeIf { it.isNotBlank() }
                ?: clipObj?.get("id").str()?.takeIf { it.isNotBlank() }
            if (slug != null) {
                val shortUrl = "https://clips.twitch.tv/$slug"
                CreatedClipResult(slug = slug, url = shortUrl, error = null)
            } else {
                CreatedClipResult(null, null, errCode ?: "UNKNOWN")
            }
        }.getOrDefault(CreatedClipResult(null, null, "NETWORK_ERROR"))
    }

    data class ActiveLiveEventsData(
        val poll: com.github.andreyasadchy.xtra.model.chat.Poll? = null,
        val prediction: com.github.andreyasadchy.xtra.model.chat.Prediction? = null,
        val selfBetOutcomeId: String? = null,
        val selfBetPoints: Int = 0,
    )

    data class PollPredictionActionResult(
        val success: Boolean,
        val errorCode: String? = null,
    )

    suspend fun loadActivePollAndPrediction(
        networkLibrary: String?,
        headers: Map<String, String>,
        channelLogin: String?,
    ): ActiveLiveEventsData = withContext(Dispatchers.IO) {
        if (channelLogin.isNullOrBlank()) return@withContext ActiveLiveEventsData()
        fun JsonElement?.obj(): JsonObject? = this as? JsonObject
        fun JsonElement?.arr(): JsonArray? = this as? JsonArray
        fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
        fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

        coroutineScope {
            val pollDef = async {
                runCatching {
                    val persistedBody = buildJsonObject {
                        put("operationName", "ChannelPollContext_GetViewablePoll")
                        putJsonObject("extensions") {
                            putJsonObject("persistedQuery") {
                                put("version", 1)
                                put("sha256Hash", "e83188a3836c636393df3191665e543a03733d7c51d3ade3d85e42aa46c2bf55")
                            }
                        }
                        putJsonObject("variables") {
                            put("login", channelLogin)
                        }
                    }.toString()
                    val rawBody = buildJsonObject {
                        put(
                            "query",
                            "query(\$login: String!) { channel(name: \$login) { viewablePoll { id title status startedAt durationSeconds votes { total } choices { id title votes { total } totalVoters } settings { communityPointsVotes { isEnabled cost } } } } }",
                        )
                        putJsonObject("variables") {
                            put("login", channelLogin)
                        }
                    }.toString()

                    var raw = sendPersistedQuery(networkLibrary, headers, persistedBody)
                    if (raw.contains("PersistedQueryNotFound")) {
                        raw = sendPersistedQuery(networkLibrary, headers, rawBody)
                    }
                    val parsed = json.parseToJsonElement(raw)
                    val root = (parsed as? JsonArray)?.firstOrNull().obj() ?: parsed.obj()
                    val p = root?.get("data").obj()?.get("channel").obj()?.get("viewablePoll").obj()
                        ?: root?.get("data").obj()?.get("user").obj()?.get("viewablePoll").obj()
                        ?: return@runCatching null
                    val pollId = p["id"].str()?.takeIf { it.isNotBlank() } ?: return@runCatching null
                    val rawStatus = p["status"].str()?.uppercase() ?: "ACTIVE"
                    if (rawStatus != "ACTIVE") return@runCatching null

                    val startedAtMs = p["startedAt"].str()?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() }
                    val durationSec = p["durationSeconds"].int() ?: p["duration"].int()
                    val remainingMs = if (startedAtMs != null && durationSec != null && durationSec > 0) {
                        val rem = (startedAtMs + durationSec * 1000L) - System.currentTimeMillis()
                        if (rem <= 0L) return@runCatching null
                        rem.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    } else {
                        null
                    }

                    val choices = p["choices"].arr()?.mapNotNull { cEl ->
                        val c = cEl.obj() ?: return@mapNotNull null
                        val cId = c["id"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val cTitle = c["title"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val votes = c["votes"].obj()?.get("total").int() ?: c["totalVoters"].int() ?: 0
                        com.github.andreyasadchy.xtra.model.chat.Poll.PollChoice(
                            id = cId,
                            title = cTitle,
                            totalVotes = votes,
                        )
                    } ?: emptyList()
                    if (choices.isEmpty()) return@runCatching null
                    val totalVotes = p["votes"].obj()?.get("total").int() ?: choices.sumOf { it.totalVotes ?: 0 }
                    val cpv = p["settings"].obj()?.get("communityPointsVotes").obj()
                    val cpEnabled = cpv?.get("isEnabled").bool() ?: false
                    val cpCost = cpv?.get("cost").int() ?: 0

                    com.github.andreyasadchy.xtra.model.chat.Poll(
                        id = pollId,
                        title = p["title"].str() ?: "",
                        status = "ACTIVE",
                        choices = choices,
                        totalVotes = totalVotes,
                        remainingMilliseconds = remainingMs,
                        pointsVotingEnabled = cpEnabled,
                        pointsPerVote = cpCost,
                    )
                }.getOrNull()
            }

            val predDef = async {
                runCatching {
                    val persistedBody = buildJsonObject {
                        put("operationName", "ChannelPointsPredictionContext")
                        putJsonObject("extensions") {
                            putJsonObject("persistedQuery") {
                                put("version", 1)
                                put("sha256Hash", "beb846598256b75bd7c1fe54a80431335996153e358ca9c7837ce7bb83d7d383")
                            }
                        }
                        putJsonObject("variables") {
                            put("count", 1)
                            put("channelLogin", channelLogin)
                        }
                    }.toString()
                    val rawBody = buildJsonObject {
                        put(
                            "query",
                            "query(\$login: String!) { channel(name: \$login) { activePredictionEvents { id title status createdAt predictionWindowSeconds winningOutcome { id } outcomes { id title color totalPoints totalUsers } self { prediction { points outcome { id } } } } lockedPredictionEvents { id title status createdAt predictionWindowSeconds winningOutcome { id } outcomes { id title color totalPoints totalUsers } self { prediction { points outcome { id } } } } } }",
                        )
                        putJsonObject("variables") {
                            put("login", channelLogin)
                        }
                    }.toString()

                    var raw = sendPersistedQuery(networkLibrary, headers, persistedBody)
                    if (raw.contains("PersistedQueryNotFound")) {
                        raw = sendPersistedQuery(networkLibrary, headers, rawBody)
                    }
                    val parsed = json.parseToJsonElement(raw)
                    val root = (parsed as? JsonArray)?.firstOrNull().obj() ?: parsed.obj()
                    val chan = root?.get("data").obj()?.get("community").obj()?.get("channel").obj()
                        ?: root?.get("data").obj()?.get("channel").obj()
                        ?: return@runCatching Triple(null, null, 0)

                    val activeObj = chan["activePredictionEvents"].arr()?.firstOrNull().obj()
                    val lockedObj = chan["lockedPredictionEvents"].arr()?.firstOrNull().obj()
                    val (ev, resolvedStatus) = when {
                        activeObj != null -> activeObj to "ACTIVE"
                        lockedObj != null -> lockedObj to "LOCKED"
                        else -> return@runCatching Triple(null, null, 0)
                    }
                    val predId = ev["id"].str()?.takeIf { it.isNotBlank() } ?: return@runCatching Triple(null, null, 0)
                    val createdAtMs = ev["createdAt"].str()?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() }
                    val windowSec = ev["predictionWindowSeconds"].int()
                    // Discard stale ACTIVE event whose window expired without lock
                    if (resolvedStatus == "ACTIVE" && createdAtMs != null && windowSec != null && windowSec > 0) {
                        if (System.currentTimeMillis() > createdAtMs + (windowSec + 30L) * 1000L) {
                            return@runCatching Triple(null, null, 0)
                        }
                    }
                    val outcomes = ev["outcomes"].arr()?.mapNotNull { oEl ->
                        val o = oEl.obj() ?: return@mapNotNull null
                        val oId = o["id"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val oTitle = o["title"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        com.github.andreyasadchy.xtra.model.chat.Prediction.PredictionOutcome(
                            id = oId,
                            title = oTitle,
                            totalPoints = o["totalPoints"].int() ?: 0,
                            totalUsers = o["totalUsers"].int() ?: 0,
                            color = o["color"].str()?.takeIf { it.isNotBlank() },
                        )
                    } ?: emptyList()
                    if (outcomes.isEmpty()) return@runCatching Triple(null, null, 0)

                    var selfOutcomeId = ev["self"].obj()?.get("prediction").obj()?.get("outcome").obj()?.get("id").str()?.takeIf { it.isNotBlank() }
                    var selfPoints = ev["self"].obj()?.get("prediction").obj()?.get("points").int() ?: 0

                    if (selfOutcomeId == null && !headers[C.HEADER_TOKEN].isNullOrBlank()) {
                        runCatching {
                            val selfQuery = buildJsonObject {
                                put(
                                    "query",
                                    "query(\$l: String!) { channel(name: \$l) { activePredictionEvents { id self { prediction { points outcome { id } } } } lockedPredictionEvents { id self { prediction { points outcome { id } } } } } }",
                                )
                                putJsonObject("variables") { put("l", channelLogin) }
                            }.toString()
                            val selfRaw = sendPersistedQuery(networkLibrary, headers, selfQuery)
                            val selfChan = json.parseToJsonElement(selfRaw).obj()?.get("data").obj()?.get("channel").obj()
                            val allEvents = (selfChan?.get("activePredictionEvents").arr().orEmpty() +
                                selfChan?.get("lockedPredictionEvents").arr().orEmpty())
                            for (eEl in allEvents) {
                                val eObj = eEl.obj() ?: continue
                                if (eObj["id"].str() == predId) {
                                    val sp = eObj["self"].obj()?.get("prediction").obj()
                                    selfOutcomeId = sp?.get("outcome").obj()?.get("id").str()?.takeIf { it.isNotBlank() }
                                    selfPoints = sp?.get("points").int() ?: 0
                                    break
                                }
                            }
                        }
                    }

                    val prediction = com.github.andreyasadchy.xtra.model.chat.Prediction(
                        id = predId,
                        createdAt = createdAtMs,
                        outcomes = outcomes,
                        predictionWindowSeconds = windowSec,
                        status = resolvedStatus,
                        title = ev["title"].str() ?: "",
                        winningOutcomeId = ev["winningOutcome"].obj()?.get("id").str()?.takeIf { it.isNotBlank() },
                    )
                    Triple(prediction, selfOutcomeId, selfPoints)
                }.getOrDefault(Triple(null, null, 0))
            }

            val poll = pollDef.await()
            val (pred, selfOutcomeId, selfPoints) = predDef.await()
            ActiveLiveEventsData(
                poll = poll,
                prediction = pred,
                selfBetOutcomeId = selfOutcomeId,
                selfBetPoints = selfPoints,
            )
        }
    }

    suspend fun voteInPoll(
        networkLibrary: String?,
        headers: Map<String, String>,
        pollId: String,
        choiceId: String,
        userId: String,
        channelPointsCost: Int? = null,
    ): PollPredictionActionResult = withContext(Dispatchers.IO) {
        if (pollId.isBlank() || choiceId.isBlank() || userId.isBlank()) {
            return@withContext PollPredictionActionResult(false, "INVALID_INPUT")
        }
        val voteId = Uuid.random().toHexString()
        fun buildInputObj() = buildJsonObject {
            put("pollID", pollId)
            put("choiceID", choiceId)
            put("userID", userId)
            put("voteID", voteId)
            if (channelPointsCost != null && channelPointsCost > 0) {
                putJsonObject("tokens") {
                    put("bits", 0)
                    put("channelPoints", channelPointsCost)
                }
            } else {
                put("tokens", kotlinx.serialization.json.JsonNull)
            }
        }
        val persistedBody = buildJsonObject {
            put("operationName", "ChannelPollContext_VoteInPoll")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", "1280e27b0f3c7ae60b5714bd569771ea50635778473182e6e959e2dcfcc16e3c")
                }
            }
            putJsonObject("variables") {
                put("input", buildInputObj())
            }
        }.toString()
        val rawBody = buildJsonObject {
            put(
                "query",
                "mutation(\$input: VotePollInput!) { voteInPoll(input: \$input) { poll { id } error { code } } }",
            )
            putJsonObject("variables") {
                put("input", buildInputObj())
            }
        }.toString()

        runCatching {
            fun JsonElement?.obj(): JsonObject? = this as? JsonObject
            fun JsonElement?.arr(): JsonArray? = this as? JsonArray
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

            var raw = sendPersistedQuery(networkLibrary, headers, persistedBody)
            if (raw.contains("PersistedQueryNotFound")) {
                raw = sendPersistedQuery(networkLibrary, headers, rawBody)
            }
            val parsed = json.parseToJsonElement(raw)
            val root = (parsed as? JsonArray)?.firstOrNull().obj() ?: parsed.obj()
                ?: return@runCatching PollPredictionActionResult(false, "NETWORK_ERROR")
            val topErr = root["errors"].arr()?.firstOrNull().obj()?.get("message").str()
            if (!topErr.isNullOrBlank()) {
                return@runCatching PollPredictionActionResult(false, topErr)
            }
            val node = root["data"].obj()?.get("voteInPoll")
                ?: return@runCatching PollPredictionActionResult(false, "UNKNOWN")
            val errCode = node.obj()?.get("error").obj()?.get("code").str()?.takeIf { it.isNotBlank() }
            if (errCode != null) {
                PollPredictionActionResult(false, errCode)
            } else {
                PollPredictionActionResult(true, null)
            }
        }.getOrDefault(PollPredictionActionResult(false, "NETWORK_ERROR"))
    }

    suspend fun makePrediction(
        networkLibrary: String?,
        headers: Map<String, String>,
        eventId: String,
        outcomeId: String,
        points: Int,
    ): PollPredictionActionResult = withContext(Dispatchers.IO) {
        if (eventId.isBlank() || outcomeId.isBlank() || points < 10) {
            return@withContext PollPredictionActionResult(false, "INVALID_INPUT")
        }
        val txId = Uuid.random().toHexString()
        fun buildInputObj() = buildJsonObject {
            put("eventID", eventId)
            put("outcomeID", outcomeId)
            put("points", points)
            put("transactionID", txId)
        }
        val persistedBody = buildJsonObject {
            put("operationName", "MakePrediction")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", "b44682ecc88358817009f20e69d75081b1e58825bb40aa53d5dbadcc17c881d8")
                }
            }
            putJsonObject("variables") {
                put("input", buildInputObj())
            }
        }.toString()
        val rawBody = buildJsonObject {
            put(
                "query",
                "mutation(\$input: MakePredictionInput!) { makePrediction(input: \$input) { prediction { id } error { code } } }",
            )
            putJsonObject("variables") {
                put("input", buildInputObj())
            }
        }.toString()

        runCatching {
            fun JsonElement?.obj(): JsonObject? = this as? JsonObject
            fun JsonElement?.arr(): JsonArray? = this as? JsonArray
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

            var raw = sendPersistedQuery(networkLibrary, headers, persistedBody)
            if (raw.contains("PersistedQueryNotFound")) {
                raw = sendPersistedQuery(networkLibrary, headers, rawBody)
            }
            val parsed = json.parseToJsonElement(raw)
            val root = (parsed as? JsonArray)?.firstOrNull().obj() ?: parsed.obj()
                ?: return@runCatching PollPredictionActionResult(false, "NETWORK_ERROR")
            val topErr = root["errors"].arr()?.firstOrNull().obj()?.get("message").str()
            if (!topErr.isNullOrBlank()) {
                return@runCatching PollPredictionActionResult(false, topErr)
            }
            val node = root["data"].obj()?.get("makePrediction").obj()
                ?: return@runCatching PollPredictionActionResult(false, "UNKNOWN")
            val errCode = node["error"].obj()?.get("code").str()?.takeIf { it.isNotBlank() }
            if (errCode != null) {
                PollPredictionActionResult(false, errCode)
            } else {
                PollPredictionActionResult(true, null)
            }
        }.getOrDefault(PollPredictionActionResult(false, "NETWORK_ERROR"))
    }
}