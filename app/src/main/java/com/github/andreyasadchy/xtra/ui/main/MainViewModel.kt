package com.github.andreyasadchy.xtra.ui.main

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.HttpEngine
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.CustomProxy
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.StreamProxy
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.model.ui.VideoSwap
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.io.File
import java.io.FileOutputStream
import java.util.Timer
import java.util.concurrent.ExecutorService
import kotlin.math.max
import kotlin.time.Instant

class MainViewModel(
    private val applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val playerRepository: PlayerRepository,
    private val offlineVideosRepository: OfflineVideosRepository,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val authRepository: AuthRepository,
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) : ViewModel() {

    val integrity = MutableSharedFlow<String?>()
    var loadingIntegrityToken = false

    val checkNetworkStatus = MutableStateFlow(false)
    val checkCellularStatus = MutableStateFlow(false)
    val isNetworkAvailable = MutableStateFlow<Boolean?>(null)

    var isPlayerOpened = false
    val playbackStates = MutableSharedFlow<List<PlaybackState>>()
    var loadingPlaybackStates = false
    val startDownloadService = MutableSharedFlow<Pair<Int, Boolean>>()

    var sleepTimer: Timer? = null
    var sleepTimerEndTime = 0L

    val video = MutableStateFlow<Pair<Video?, Long?>?>(null)
    val clip = MutableStateFlow<Clip?>(null)
    val user = MutableStateFlow<User?>(null)
    val game = MutableStateFlow<Pair<Game?, String?>?>(null)
    val tag = MutableStateFlow<Tag?>(null)

    val updateUrl = MutableSharedFlow<String?>()
    var updateSize: Long? = null
    var updateVersion: String? = null
    var updateNotes: String? = null
    var updateJob: Job? = null
    val updateProgress = MutableSharedFlow<Int>()
    val closeUpdateDialog = MutableSharedFlow<Boolean>()

    fun savePlaybackState(item: PlaybackState, then: (() -> Unit)? = null) {
        viewModelScope.launch {
            playerRepository.savePlaybackStates(listOf(item))
            then?.invoke()
        }
    }

    fun getPlaybackStates() {
        if (!loadingPlaybackStates) {
            loadingPlaybackStates = true
            viewModelScope.launch {
                playbackStates.emit(playerRepository.getPlaybackStates())
            }.invokeOnCompletion {
                loadingPlaybackStates = false
            }
        }
    }

    suspend fun getWaitingDownloads(): List<OfflineVideo> {
        return offlineVideosRepository.getWaitingDownloads()
    }

    fun loadVideo(videoId: String?, offset: Long?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (video.value == null) {
            viewModelScope.launch {
                val item = try {
                    val response = graphQLRepository.loadQueryVideo(networkLibrary, gqlHeaders, videoId)
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refresh")
                            return@launch
                        }
                    }
                    response.data!!.let { item ->
                        item.video?.let {
                            Video(
                                id = videoId,
                                channelId = it.owner?.id,
                                channelLogin = it.owner?.login,
                                channelName = it.owner?.displayName,
                                channelImageURL = it.owner?.profileImageURL,
                                gameId = it.game?.id,
                                gameSlug = it.game?.slug,
                                gameName = it.game?.displayName,
                                title = it.title,
                                thumbnailURL = it.previewThumbnailURL,
                                createdAt = it.createdAt?.toString(),
                                durationSeconds = it.lengthSeconds,
                                type = it.broadcastType?.toString(),
                                animatedPreviewURL = it.animatedPreviewURL,
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        try {
                            helixRepository.getVideos(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                ids = videoId?.let { listOf(it) }
                            ).data.firstOrNull()?.let {
                                Video(
                                    id = it.id,
                                    channelId = it.channelId,
                                    channelLogin = it.channelLogin,
                                    channelName = it.channelName,
                                    title = it.title,
                                    thumbnailURL = it.thumbnailURL,
                                    createdAt = it.createdAt,
                                    viewCount = it.viewCount,
                                    durationSeconds = it.duration?.let { duration -> TwitchApiHelper.getDuration(duration) },
                                )
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                }
                video.value = item to offset
            }
        }
    }

    fun saveVideoPosition(id: Long, position: Long) {
        viewModelScope.launch {
            playerRepository.saveVideoPosition(VideoPosition(id, position))
        }
    }

    suspend fun savePosition(id: Long, position: Long) {
        playerRepository.saveVideoPosition(VideoPosition(id, position))
    }

    fun saveOfflineVideoPosition(id: Int, position: Long) {
        viewModelScope.launch {
            offlineVideosRepository.updatePosition(id, position)
        }
    }

    fun loadClip(clipId: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (clip.value == null) {
            viewModelScope.launch {
                clip.value = try {
                    val response = graphQLRepository.loadQueryClip(networkLibrary, gqlHeaders, clipId!!)
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refresh")
                            return@launch
                        }
                    }
                    response.data!!.clip?.let {
                        Clip(
                            id = clipId,
                            channelId = it.broadcaster?.id,
                            channelLogin = it.broadcaster?.login,
                            channelName = it.broadcaster?.displayName,
                            channelImageURL = it.broadcaster?.profileImageURL,
                            gameId = it.game?.id,
                            gameSlug = it.game?.slug,
                            gameName = it.game?.displayName,
                            title = it.title,
                            thumbnailURL = it.thumbnailURL,
                            createdAt = it.createdAt?.toString(),
                            durationSeconds = it.durationSeconds,
                            videoId = it.video?.id,
                            videoOffsetSeconds = if (it.videoOffsetSeconds != null && it.durationSeconds != null) {
                                max(it.videoOffsetSeconds - it.durationSeconds, 0)
                            } else {
                                it.videoOffsetSeconds
                            },
                            videoCreatedAt = it.video?.createdAt?.toString(),
                            videoAnimatedPreviewURL = it.video?.animatedPreviewURL,
                        )
                    }
                } catch (e: Exception) {
                    try {
                        val user = try {
                            graphQLRepository.loadClipData(networkLibrary, gqlHeaders, clipId).data?.clip
                        } catch (e: Exception) {
                            null
                        }
                        val clip = graphQLRepository.loadClipVideo(networkLibrary, gqlHeaders, clipId).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return@launch
                                }
                            }
                        }.data?.clip
                        Clip(
                            id = clipId,
                            channelId = user?.broadcaster?.id,
                            channelLogin = user?.broadcaster?.login,
                            channelName = user?.broadcaster?.displayName,
                            channelImageURL = user?.broadcaster?.profileImageURL,
                            durationSeconds = clip?.durationSeconds,
                            videoId = clip?.video?.id,
                            videoOffsetSeconds = (clip?.videoOffsetSeconds ?: user?.videoOffsetSeconds).let {
                                if (it != null && clip?.durationSeconds != null) {
                                    max(it - clip.durationSeconds, 0)
                                } else {
                                    it
                                }
                            },
                        )
                    } catch (e: Exception) {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            try {
                                helixRepository.getClips(
                                    networkLibrary = networkLibrary,
                                    headers = helixHeaders,
                                    ids = clipId?.let { listOf(it) }
                                ).data.firstOrNull()?.let {
                                    Clip(
                                        id = it.id,
                                        channelId = it.channelId,
                                        channelName = it.channelName,
                                        gameId = it.gameId,
                                        title = it.title,
                                        thumbnailURL = it.thumbnailURL,
                                        createdAt = it.createdAt,
                                        viewCount = it.viewCount,
                                        durationSeconds = it.duration?.toInt(),
                                        videoId = it.videoId,
                                        videoOffsetSeconds = if (it.vodOffset != null && it.duration != null) {
                                            max(it.vodOffset - it.duration.toInt(), 0)
                                        } else {
                                            it.vodOffset
                                        },
                                    )
                                }
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }
                }
            }
        }
    }

    fun loadUser(login: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (user.value == null) {
            viewModelScope.launch {
                user.value = try {
                    val response = graphQLRepository.loadQueryUser(networkLibrary, gqlHeaders, login = login)
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refresh")
                            return@launch
                        }
                    }
                    response.data!!.user?.let {
                        User(
                            id = it.id,
                            login = it.login,
                            name = it.displayName,
                            profileImageURL = it.profileImageURL,
                        )
                    }
                } catch (e: Exception) {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        try {
                            helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = login?.let { listOf(it) }
                            ).data.firstOrNull()?.let {
                                User(
                                    id = it.id,
                                    login = it.login,
                                    name = it.displayName,
                                    profileImageURL = it.profileImageURL,
                                    type = it.type,
                                    broadcasterType = it.broadcasterType,
                                    createdAt = it.createdAt,
                                )
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                }
            }
        }
    }

    fun loadGame(gameSlug: String? = null, gameName: String? = null, tag: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (game.value == null) {
            viewModelScope.launch {
                game.value = try {
                    val response = graphQLRepository.loadQueryGame(
                        networkLibrary = networkLibrary,
                        headers = gqlHeaders,
                        slug = gameSlug,
                        name = gameName.takeIf { gameSlug.isNullOrBlank() },
                    )
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refresh")
                            return@launch
                        }
                    }
                    response.data!!.game?.let {
                        Game(
                            id = it.id,
                            slug = it.slug,
                            name = it.displayName,
                            boxArtURL = it.boxArtURL,
                        )
                    }
                } catch (e: Exception) {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank() && !gameName.isNullOrBlank()) {
                        try {
                            helixRepository.getGames(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                names = listOf(gameName)
                            ).data.firstOrNull()?.let {
                                Game(
                                    id = it.id,
                                    name = it.name,
                                    boxArtURL = it.boxArtURL,
                                )
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                } to tag
            }
        }
    }

    fun loadTag(tagId: String, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (tag.value == null) {
            viewModelScope.launch {
                tag.value = try {
                    val response = graphQLRepository.loadQueryTag(networkLibrary, gqlHeaders, tagId)
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refresh")
                            return@launch
                        }
                    }
                    response.data!!.contentTag?.let {
                        Tag(
                            id = tagId,
                            name = it.localizedName,
                        )
                    }
                } catch (e: Exception) {
                    try {
                        val response = graphQLRepository.loadTag(networkLibrary, gqlHeaders, tagId)
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refresh")
                                return@launch
                            }
                        }
                        response.data!!.contentTag.let {
                            Tag(
                                id = tagId,
                                name = it.localizedName,
                            )
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }

    fun downloadStream(networkLibrary: String?, filesDir: String, id: String?, title: String?, createdAt: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, downloadPath: String, quality: String, downloadChat: Boolean, downloadChatEmotes: Boolean, wifiOnly: Boolean) {
        viewModelScope.launch {
            if (!channelLogin.isNullOrBlank()) {
                val downloadedThumbnail = id.takeIf { !it.isNullOrBlank() }?.let { id ->
                    thumbnail.takeIf { !it.isNullOrBlank() }?.let { url ->
                        File(filesDir, "thumbnails").mkdir()
                        val path = filesDir + File.separator + "thumbnails" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.HttpEngineTimeout()
                                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.CronetTimeout()
                                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                                cronetExecutor.value
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                            if (response.isSuccessful) {
                                                FileOutputStream(path).use { outputStream ->
                                                    response.body.byteStream().use { inputStream ->
                                                        inputStream.copyTo(outputStream)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }
                        path
                    }
                }
                val downloadedLogo = channelId.takeIf { !it.isNullOrBlank() }?.let { id ->
                    channelImage.takeIf { !it.isNullOrBlank() }?.let { url ->
                        File(filesDir, "profile_pics").mkdir()
                        val path = filesDir + File.separator + "profile_pics" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.HttpEngineTimeout()
                                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.CronetTimeout()
                                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                                cronetExecutor.value
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                            if (response.isSuccessful) {
                                                FileOutputStream(path).use { outputStream ->
                                                    response.body.byteStream().use { inputStream ->
                                                        inputStream.copyTo(outputStream)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }
                        path
                    }
                }
                val waitForWifi = if (wifiOnly) {
                    val connectivityManager = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                } else false
                val videoId = offlineVideosRepository.save(
                    OfflineVideo(
                        name = title,
                        channelId = channelId,
                        channelLogin = channelLogin,
                        channelName = channelName,
                        channelLogo = downloadedLogo,
                        thumbnail = downloadedThumbnail,
                        gameId = gameId,
                        gameSlug = gameSlug,
                        gameName = gameName,
                        uploadDate = createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } },
                        downloadDate = System.currentTimeMillis(),
                        downloadPath = downloadPath,
                        status = if (waitForWifi) {
                            OfflineVideo.STATUS_WAITING_FOR_WIFI
                        } else {
                            OfflineVideo.STATUS_PENDING
                        },
                        quality = if (!quality.contains("Audio", true)) quality else "audio",
                        downloadChat = downloadChat,
                        downloadChatEmotes = downloadChatEmotes,
                        live = true
                    )
                ).toInt()
                if (!waitForWifi) {
                    startDownloadService.emit(Pair(videoId, true))
                }
            }
        }
    }

    fun downloadVideo(networkLibrary: String?, filesDir: String, id: String?, title: String?, createdAt: String?, type: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, url: String, downloadPath: String, quality: String, from: Long, to: Long, downloadChat: Boolean, downloadChatEmotes: Boolean, playlistToFile: Boolean, wifiOnly: Boolean) {
        viewModelScope.launch {
            val downloadedThumbnail = id.takeIf { !it.isNullOrBlank() }?.let { id ->
                thumbnail.takeIf { !it.isNullOrBlank() }?.let { url ->
                    File(filesDir, "thumbnails").mkdir()
                    val path = filesDir + File.separator + "thumbnails" + File.separator + id
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            when {
                                networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.HttpEngineTimeout()
                                        val request = httpEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            cronetExecutor.value,
                                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        FileOutputStream(path).use {
                                            it.write(response.body)
                                        }
                                    }
                                }
                                networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.CronetTimeout()
                                        val request = cronetEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                            cronetExecutor.value
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        FileOutputStream(path).use {
                                            it.write(response.body)
                                        }
                                    }
                                }
                                else -> {
                                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            FileOutputStream(path).use { outputStream ->
                                                response.body.byteStream().use { inputStream ->
                                                    inputStream.copyTo(outputStream)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                    path
                }
            }
            val downloadedLogo = channelId.takeIf { !it.isNullOrBlank() }?.let { id ->
                channelImage.takeIf { !it.isNullOrBlank() }?.let { url ->
                    File(filesDir, "profile_pics").mkdir()
                    val path = filesDir + File.separator + "profile_pics" + File.separator + id
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            when {
                                networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.HttpEngineTimeout()
                                        val request = httpEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            cronetExecutor.value,
                                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        FileOutputStream(path).use {
                                            it.write(response.body)
                                        }
                                    }
                                }
                                networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.CronetTimeout()
                                        val request = cronetEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                            cronetExecutor.value
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        FileOutputStream(path).use {
                                            it.write(response.body)
                                        }
                                    }
                                }
                                else -> {
                                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            FileOutputStream(path).use { outputStream ->
                                                response.body.byteStream().use { inputStream ->
                                                    inputStream.copyTo(outputStream)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                    path
                }
            }
            val waitForWifi = if (wifiOnly) {
                val connectivityManager = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } else false
            val videoId = offlineVideosRepository.save(
                OfflineVideo(
                    sourceUrl = url,
                    name = title,
                    channelId = channelId,
                    channelLogin = channelLogin,
                    channelName = channelName,
                    channelLogo = downloadedLogo,
                    thumbnail = downloadedThumbnail,
                    gameId = gameId,
                    gameSlug = gameSlug,
                    gameName = gameName,
                    uploadDate = createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } },
                    downloadDate = System.currentTimeMillis(),
                    downloadPath = downloadPath,
                    fromTime = from,
                    toTime = to,
                    status = if (waitForWifi) {
                        OfflineVideo.STATUS_WAITING_FOR_WIFI
                    } else {
                        OfflineVideo.STATUS_PENDING
                    },
                    type = type,
                    videoId = id,
                    quality = if (!quality.contains("Audio", true)) quality else "audio",
                    downloadChat = downloadChat,
                    downloadChatEmotes = downloadChatEmotes,
                    playlistToFile = playlistToFile
                )
            ).toInt()
            if (!waitForWifi) {
                startDownloadService.emit(Pair(videoId, false))
            }
        }
    }

    fun downloadClip(networkLibrary: String?, filesDir: String, clipId: String?, title: String?, createdAt: String?, durationSeconds: Int?, videoId: String?, videoOffsetSeconds: Int?, videoCreatedAt: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, url: String, downloadPath: String, quality: String, downloadChat: Boolean, downloadChatEmotes: Boolean, wifiOnly: Boolean) {
        viewModelScope.launch {
            val downloadedThumbnail = clipId.takeIf { !it.isNullOrBlank() }?.let { id ->
                thumbnail.takeIf { !it.isNullOrBlank() }?.let { url ->
                    File(filesDir, "thumbnails").mkdir()
                    val path = filesDir + File.separator + "thumbnails" + File.separator + id
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            when {
                                networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.HttpEngineTimeout()
                                        val request = httpEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            cronetExecutor.value,
                                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        FileOutputStream(path).use {
                                            it.write(response.body)
                                        }
                                    }
                                }
                                networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.CronetTimeout()
                                        val request = cronetEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                            cronetExecutor.value
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        FileOutputStream(path).use {
                                            it.write(response.body)
                                        }
                                    }
                                }
                                else -> {
                                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            FileOutputStream(path).use { outputStream ->
                                                response.body.byteStream().use { inputStream ->
                                                    inputStream.copyTo(outputStream)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                    path
                }
            }
            val downloadedLogo = channelId.takeIf { !it.isNullOrBlank() }?.let { id ->
                channelImage.takeIf { !it.isNullOrBlank() }?.let { url ->
                    File(filesDir, "profile_pics").mkdir()
                    val path = filesDir + File.separator + "profile_pics" + File.separator + id
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            when {
                                networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.HttpEngineTimeout()
                                        val request = httpEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            cronetExecutor.value,
                                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        FileOutputStream(path).use {
                                            it.write(response.body)
                                        }
                                    }
                                }
                                networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.CronetTimeout()
                                        val request = cronetEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                            cronetExecutor.value
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        FileOutputStream(path).use {
                                            it.write(response.body)
                                        }
                                    }
                                }
                                else -> {
                                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            FileOutputStream(path).use { outputStream ->
                                                response.body.byteStream().use { inputStream ->
                                                    inputStream.copyTo(outputStream)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                    path
                }
            }
            val waitForWifi = if (wifiOnly) {
                val connectivityManager = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } else false
            val videoId = offlineVideosRepository.save(
                OfflineVideo(
                    sourceUrl = url,
                    sourceStartPosition = videoOffsetSeconds?.toLong()?.times(1000L),
                    name = title,
                    channelId = channelId,
                    channelLogin = channelLogin,
                    channelName = channelName,
                    channelLogo = downloadedLogo,
                    thumbnail = downloadedThumbnail,
                    gameId = gameId,
                    gameSlug = gameSlug,
                    gameName = gameName,
                    duration = durationSeconds?.times(1000L),
                    uploadDate = createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } },
                    downloadDate = System.currentTimeMillis(),
                    downloadPath = downloadPath,
                    status = if (waitForWifi) {
                        OfflineVideo.STATUS_WAITING_FOR_WIFI
                    } else {
                        OfflineVideo.STATUS_PENDING
                    },
                    videoId = videoId,
                    videoCreatedAt = videoCreatedAt,
                    clipId = clipId,
                    quality = if (!quality.contains("Audio", true)) quality else "audio",
                    downloadChat = downloadChat,
                    downloadChatEmotes = downloadChatEmotes
                )
            ).toInt()
            if (!waitForWifi) {
                startDownloadService.emit(Pair(videoId, false))
            }
        }
    }

    fun validate(networkLibrary: String?, gqlHeaders: Map<String, String>, gqlWebClientId: String?, gqlWebToken: String?, helixHeaders: Map<String, String>, accountId: String?, accountLogin: String?, activity: Activity) {
        viewModelScope.launch {
            try {
                val helixToken = helixHeaders[C.HEADER_TOKEN]
                if (!helixToken.isNullOrBlank()) {
                    val response = authRepository.validate(networkLibrary, helixToken)
                    if (response.clientId.isNotBlank() && response.clientId == helixHeaders[C.HEADER_CLIENT_ID]) {
                        if ((!response.userId.isNullOrBlank() && response.userId != accountId) || (!response.login.isNullOrBlank() && response.login != accountLogin)) {
                            activity.tokenPrefs().edit {
                                putString(C.USER_ID, response.userId?.takeIf { it.isNotBlank() } ?: accountId)
                                putString(C.USERNAME, response.login?.takeIf { it.isNotBlank() } ?: accountLogin)
                            }
                        }
                    } else {
                        throw IllegalStateException("401")
                    }
                }
                val gqlToken = gqlHeaders[C.HEADER_TOKEN]
                if (!gqlToken.isNullOrBlank()) {
                    val response = authRepository.validate(networkLibrary, gqlToken)
                    if (response.clientId.isNotBlank() && (response.clientId == gqlHeaders[C.HEADER_CLIENT_ID] || response.clientId == gqlWebClientId)) {
                        if ((!response.userId.isNullOrBlank() && response.userId != accountId) || (!response.login.isNullOrBlank() && response.login != accountLogin)) {
                            activity.tokenPrefs().edit {
                                putString(C.USER_ID, response.userId?.takeIf { it.isNotBlank() } ?: accountId)
                                putString(C.USERNAME, response.login?.takeIf { it.isNotBlank() } ?: accountLogin)
                            }
                        }
                    } else {
                        throw IllegalStateException("401")
                    }
                }
                if (!gqlWebToken.isNullOrBlank() && gqlWebToken != gqlToken) {
                    val response = authRepository.validate(networkLibrary, gqlWebToken)
                    if (response.clientId.isNotBlank() && response.clientId == gqlWebClientId) {
                        if ((!response.userId.isNullOrBlank() && response.userId != accountId) || (!response.login.isNullOrBlank() && response.login != accountLogin)) {
                            activity.tokenPrefs().edit {
                                putString(C.USER_ID, response.userId?.takeIf { it.isNotBlank() } ?: accountId)
                                putString(C.USERNAME, response.login?.takeIf { it.isNotBlank() } ?: accountLogin)
                            }
                        }
                    } else {
                        throw IllegalStateException("401")
                    }
                }
            } catch (e: Exception) {
                if (e is IllegalStateException && e.message == "401") {
                    Toast.makeText(activity, R.string.token_expired, Toast.LENGTH_LONG).show()
                    (activity as? MainActivity)?.logoutResultLauncher?.launch(Intent(activity, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_EXPIRED, true))
                }
            }
        }
        TwitchApiHelper.checkedValidation = true
    }

    fun checkUpdates(networkLibrary: String?, url: String, lastChecked: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            updateUrl.emit(
                try {
                    val requestUrl = resolveUpdateRequestUrl(url)
                    val rawBody = when {
                        networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                            val response = suspendCancellableCoroutine { continuation ->
                                val timeout = NetworkUtils.HttpEngineTimeout()
                                val request = httpEngine.value!!.newUrlRequestBuilder(
                                    requestUrl,
                                    cronetExecutor.value,
                                    NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                ).build()
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
                                    requestUrl,
                                    NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                    cronetExecutor.value
                                ).build()
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
                            okHttpClient.value.newCall(Request.Builder().url(requestUrl).build()).executeAsync().use { response ->
                                response.body.string()
                            }
                        }
                    }
                    parseUpdatePayload(json, rawBody, lastChecked)?.let { parsed ->
                        updateSize = parsed.size
                        updateVersion = parsed.version
                        updateNotes = parsed.notes
                        parsed.downloadUrl
                    }
                } catch (e: Exception) {
                    null
                }
            )
        }
        TwitchApiHelper.checkedUpdates = true
    }

    fun downloadUpdate(networkLibrary: String?, url: String) {
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val progressListener = NetworkUtils.ProgressListener { bytesRead ->
                    runBlocking {
                        updateProgress.emit(bytesRead)
                    }
                }
                val response = when {
                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.HttpEngineTimeout()
                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                url,
                                cronetExecutor.value,
                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout, progressListener)
                            ).build()
                            timeout.start(request, continuation)
                            request.start()
                            continuation.invokeOnCancellation {
                                request.cancel()
                                timeout.stop()
                            }
                        }
                        if (response.info.httpStatusCode in 200..299) {
                            response.body
                        } else null
                    }
                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.CronetTimeout()
                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                url,
                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout, progressListener),
                                cronetExecutor.value
                            ).build()
                            timeout.start(request, continuation)
                            request.start()
                            continuation.invokeOnCancellation {
                                request.cancel()
                                timeout.stop()
                            }
                        }
                        if (response.info.httpStatusCode in 200..299) {
                            response.body
                        } else null
                    }
                    else -> {
                        okHttpClient.value.newBuilder().apply {
                            addNetworkInterceptor(NetworkUtils.ProgressInterceptor(progressListener))
                        }.build().newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                            if (response.isSuccessful) {
                                response.body.bytes()
                            } else null
                        }
                    }
                }
                if (response != null && response.isNotEmpty()) {
                    val packageInstaller = applicationContext.packageManager.packageInstaller
                    val sessionId = packageInstaller.createSession(
                        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    )
                    val session = packageInstaller.openSession(sessionId)
                    session.openWrite("package", 0, response.size.toLong()).use {
                        it.write(response)
                    }
                    session.commit(
                        PendingIntent.getActivity(
                            applicationContext,
                            0,
                            Intent(applicationContext, MainActivity::class.java).apply {
                                setAction(MainActivity.INTENT_INSTALL_UPDATE)
                            },
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        ).intentSender
                    )
                    session.close()
                }
            } catch (e: Exception) {

            }
            closeUpdateDialog.emit(true)
        }
    }

    fun deleteOldImages() {
        viewModelScope.launch(Dispatchers.IO) {
            localChannelFollowsRepository.deleteOldImages()
        }
    }

    /** 1.2.9: a public proxy added to the lists of the users who already have the app, once, at the end. */
    fun addDefaultProxy(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val proxies = playerRepository.getCustomProxies()
            val host = url.substringBefore('/')
            if (proxies.none { it.url?.contains(host, true) == true }) {
                playerRepository.saveCustomProxy(CustomProxy(url, true, (proxies.maxOfOrNull { it.position } ?: -1) + 1, true))
            }
        }
    }

    fun updateProxies(oldProxy: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            playerRepository.saveCustomProxies(
                listOf(
                    Pair("eu.luminous.dev/live/\$channel", true),
                    Pair("eu2.luminous.dev/live/\$channel", true),
                    Pair("eu3.luminous.dev/live/\$channel", true),
                    Pair("as.luminous.dev/live/\$channel", true),
                    Pair(NADEKO_PROXY, true),
                    Pair("lb-eu.cdn-perfprod.com/live/\$channel", true),
                    Pair("lb-eu2.cdn-perfprod.com/live/\$channel", true),
                    Pair("lb-eu3.cdn-perfprod.com/live/\$channel", true),
                    Pair("lb-eu4.cdn-perfprod.com/live/\$channel", true),
                    Pair("lb-eu5.cdn-perfprod.com/live/\$channel", true),
                    Pair("lb-na.cdn-perfprod.com/live/\$channel", false),
                    Pair("lb-sa.cdn-perfprod.com/live/\$channel", false),
                    Pair("lb-as.cdn-perfprod.com/live/\$channel", false),
                ).let { list ->
                    if (!oldProxy.isNullOrBlank() &&
                        oldProxy.toUri().host != "api.ttv.lol" &&
                        oldProxy.substringAfter("://").substringBefore('?').let { url ->
                            list.find { it.first == url } == null
                        }
                    ) {
                        listOf(Pair(oldProxy, true)) + list
                    } else {
                        list
                    }
                }.mapIndexed { index, pair ->
                    CustomProxy(pair.first, true, index, pair.second)
                }
            )
        }
    }

    fun deleteOldProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = playerRepository.getCustomProxies().sortedBy { it.position }.toMutableList()
            val item = list.find { it.url?.toUri()?.host == "api.ttv.lol" }
            if (item != null) {
                list.remove(item)
                val items = list.onEachIndexed { index, proxy ->
                    proxy.position = index
                }
                playerRepository.deleteCustomProxy(item)
                playerRepository.updateCustomProxies(items)
            }
        }
    }

    fun updateStreamProxies(host: String?, port: Int?, username: String?, password: String?, proxyPlaybackAccessToken: Boolean, proxyMultivariantPlaylist: Boolean, proxyMediaPlaylist: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            playerRepository.saveStreamProxies(
                mutableListOf<StreamProxy>().apply {
                    if (!host.isNullOrBlank() && port != null) {
                        add(StreamProxy(host, port, username, password, proxyPlaybackAccessToken, proxyMultivariantPlaylist, proxyMediaPlaylist, 0))
                        if (host != "firefox.api.cdn-perfprod.com" || port != 2023) {
                            add(StreamProxy("firefox.api.cdn-perfprod.com", 2023, position = 1))
                        }
                    } else {
                        add(StreamProxy("firefox.api.cdn-perfprod.com", 2023, position = 0))
                    }
                }
            )
        }
    }

    fun setDefaultVideoSwapItems() {
        viewModelScope.launch(Dispatchers.IO) {
            playerRepository.saveVideoSwapItems(
                listOf(
                    Triple("android", "autoplay", true),
                    Triple("web", "embed", false),
                    Triple("web", "popout", false),
                ).mapIndexed { index, triple ->
                    VideoSwap(triple.first, triple.second, index, triple.third)
                }
            )
        }
    }

    companion object {
        /** Public proxy of nadeko, in the default list since 1.2.9. */
        const val NADEKO_PROXY = "twitch-al.nadeko.net/live/\$channel"

        /** True when the release [remoteVersion] on GitHub is newer than the installed [installedVersion]. */
        fun isNewerVersion(remoteVersion: String?, installedVersion: String = com.github.andreyasadchy.xtra.BuildConfig.VERSION_NAME): Boolean {
            if (remoteVersion.isNullOrBlank()) return false
            val regex = Regex("""\d+(?:\.\d+)+""")
            val remote = regex.find(remoteVersion)?.value?.split('.')?.mapNotNull { it.toIntOrNull() }
            val local = regex.find(installedVersion)?.value?.split('.')?.mapNotNull { it.toIntOrNull() }
            if (!remote.isNullOrEmpty() && !local.isNullOrEmpty()) {
                val maxLen = maxOf(remote.size, local.size)
                for (i in 0 until maxLen) {
                    val r = remote.getOrElse(i) { 0 }
                    val l = local.getOrElse(i) { 0 }
                    if (r != l) return r > l
                }
                return false
            }
            val cleanRemote = remoteVersion.trim().removePrefix("Xtra TV").trim().removePrefix("v").removeSuffix("-DEBUG")
            val cleanLocal = installedVersion.trim().removePrefix("v").removeSuffix("-DEBUG")
            return cleanRemote != cleanLocal
        }

        class ParsedUpdate(
            val downloadUrl: String,
            val size: Long?,
            val version: String?,
            val notes: String?,
        )

        /** Rewrites GitHub `/releases/latest` URLs to `/releases?per_page=15` so missed intermediate release notes can be shown. */
        fun resolveUpdateRequestUrl(url: String): String =
            url.trim().replace(Regex("""/releases/latest/?$"""), "/releases?per_page=15")

        /** Parses either a GitHub `/releases` JSON array or a single `/releases/latest` JSON object and concatenates all newer release notes. */
        fun parseUpdatePayload(
            json: Json,
            rawBody: String,
            lastChecked: Long,
            installedVersion: String = com.github.andreyasadchy.xtra.BuildConfig.VERSION_NAME,
        ): ParsedUpdate? {
            val element = json.parseToJsonElement(rawBody)
            val releases: List<JsonObject> = when (element) {
                is JsonArray -> element.mapNotNull { it as? JsonObject }.filter {
                    it["draft"]?.jsonPrimitive?.booleanOrNull != true &&
                        it["prerelease"]?.jsonPrimitive?.booleanOrNull != true
                }
                is JsonObject -> listOf(element)
                else -> emptyList()
            }
            var targetRelease: JsonObject? = null
            var targetAsset: JsonObject? = null
            var targetIdx = -1
            for ((idx, rel) in releases.withIndex()) {
                val apk = rel["assets"]?.jsonArray?.firstOrNull {
                    (it as? JsonObject)?.get("content_type")?.jsonPrimitive?.contentOrNull == "application/vnd.android.package-archive"
                } as? JsonObject
                if (apk != null) {
                    targetRelease = rel
                    targetAsset = apk
                    targetIdx = idx
                    break
                }
            }
            val latest = targetRelease ?: return null
            val asset = targetAsset ?: return null
            val version = (latest["tag_name"] ?: latest["name"])?.jsonPrimitive?.contentOrNull
            val updatedAt = asset["updated_at"]?.jsonPrimitive?.contentOrNull?.let {
                Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }
            }
            val hasUpdate = if (version != null) {
                isNewerVersion(version, installedVersion)
            } else {
                updatedAt != null && updatedAt > lastChecked
            }
            if (!hasUpdate) return null
            val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null

            val missedReleases = mutableListOf<JsonObject>()
            for (rel in releases.drop(targetIdx)) {
                val relVer = (rel["tag_name"] ?: rel["name"])?.jsonPrimitive?.contentOrNull
                if (missedReleases.isEmpty()) {
                    missedReleases.add(rel)
                } else if (relVer != null && isNewerVersion(relVer, installedVersion)) {
                    missedReleases.add(rel)
                } else if (relVer != null) {
                    break
                }
            }

            val installFooterRegex = Regex("""\n+#{1,4}\s*Install\b[\s\S]*$""", RegexOption.IGNORE_CASE)
            val combinedNotes = missedReleases.mapNotNull { rel ->
                val raw = rel["body"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val cleaned = raw.replace(installFooterRegex, "").trim().ifEmpty { raw }
                val relTitle = (rel["name"] ?: rel["tag_name"])?.jsonPrimitive?.contentOrNull?.trim()
                if (!cleaned.startsWith("#") && !relTitle.isNullOrBlank()) {
                    "# $relTitle\n\n$cleaned"
                } else {
                    cleaned
                }
            }.joinToString("\n\n").takeIf { it.isNotBlank() }

            return ParsedUpdate(
                downloadUrl = downloadUrl,
                size = asset["size"]?.jsonPrimitive?.longOrNull,
                version = (latest["name"] ?: latest["tag_name"])?.jsonPrimitive?.contentOrNull,
                notes = combinedNotes,
            )
        }

        val MainViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                MainViewModel(application.applicationContext, xtraModule.graphQLRepository, xtraModule.helixRepository, xtraModule.playerRepository, xtraModule.offlineVideosRepository, xtraModule.localChannelFollowsRepository, xtraModule.authRepository, xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient, xtraModule.json)
            }
        }
    }
}