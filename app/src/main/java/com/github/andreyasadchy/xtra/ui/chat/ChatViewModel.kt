package com.github.andreyasadchy.xtra.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChannelPointReward
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.Chatter
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.model.chat.Raid
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.model.chat.RoomState
import com.github.andreyasadchy.xtra.model.chat.STVBadge
import com.github.andreyasadchy.xtra.model.chat.STVUser
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import com.github.andreyasadchy.xtra.model.ui.TranslatedChannel
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.ChatReadIRCSocket
import com.github.andreyasadchy.xtra.util.chat.ChatReadWebSocket
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.util.chat.TvChatNotices
import com.github.andreyasadchy.xtra.util.chat.ChatWriteIRCSocket
import com.github.andreyasadchy.xtra.util.chat.ChatWriteWebSocket
import com.github.andreyasadchy.xtra.util.chat.EventSubUtils
import com.github.andreyasadchy.xtra.util.chat.EventSubWebSocket
import com.github.andreyasadchy.xtra.util.chat.HermesWebSocket
import com.github.andreyasadchy.xtra.util.chat.PubSubUtils
import com.github.andreyasadchy.xtra.util.chat.STVEventApiUtils
import com.github.andreyasadchy.xtra.util.chat.STVEventApiWebSocket
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterOutputStream
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ChatViewModel(
    private val applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val playerRepository: PlayerRepository,
    private val json: Json,
) : ViewModel() {

    val integrity = MutableSharedFlow<String?>()

    private var chatReadIRCSocket: ChatReadIRCSocket? = null
    private var chatWriteIRCSocket: ChatWriteIRCSocket? = null
    private var chatReadWebSocket: ChatReadWebSocket? = null
    private var chatWriteWebSocket: ChatWriteWebSocket? = null
    private var chatReadJob: Job? = null
    private var chatWriteJob: Job? = null
    private var eventSub: EventSubWebSocket? = null
    private var hermesWebSocket: HermesWebSocket? = null
    private var pubSubJob: Job? = null
    private var stvEventApi: STVEventApiWebSocket? = null
    private var stvEventApiJob: Job? = null
    private var stvUserId: String? = null
    private var stvLastPresenceUpdate: Long? = null
    private val allEmotes = mutableListOf<String>()
    private var usedRaidId: String? = null
    private var usedPollId: String? = null
    private var pollTimeoutJob: Job? = null
    private var usedPredictionId: String? = null
    private var predictionTimeoutJob: Job? = null
    private var started = false
    var autoReconnect = true

    private var chatReplayManager: ChatReplayManager? = null
    private var chatReplayManagerLocal: ChatReplayManagerLocal? = null
    var hybridReplayActive = false
        private set
    private var hybridReplayVideoId: String? = null
    private val liveBackupBuffer = ArrayDeque<ChatMessage>(120)

    val recentEmotes by lazy { playerRepository.loadRecentEmotesFlow() }
    val hasRecentEmotes = MutableStateFlow(false)
    val userEmotes = mutableListOf<Emote>()
    private var loadedUserEmotes = false
    val localTwitchEmotes = mutableListOf<TwitchEmote>()
    val thirdPartyEmotes = mutableListOf<Emote>()
    val globalBadges = mutableListOf<TwitchBadge>()
    val channelBadges = mutableListOf<TwitchBadge>()
    val cheerEmotes = mutableListOf<CheerEmote>()

    val roomState = MutableStateFlow<RoomState?>(null)
    val effectiveRoomState = MutableStateFlow(RoomState("0", "-1", "0", "0", "0"))
    val userRoomBadges = MutableStateFlow<Set<String>?>(null)
    val chatRestrictionToast = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private fun mergeRoomState(update: RoomState) {
        val prev = effectiveRoomState.value
        effectiveRoomState.value = RoomState(
            emote = update.emote ?: prev.emote,
            followers = update.followers ?: prev.followers,
            unique = update.unique ?: prev.unique,
            slow = update.slow ?: prev.slow,
            subs = update.subs ?: prev.subs,
        )
    }
    val raid = MutableStateFlow<Raid?>(null)
    val raidClicked = MutableStateFlow<Raid?>(null)
    var raidClosed = false
    val poll = MutableStateFlow<Poll?>(null)
    var pollClosed = false
    val pollSecondsLeft = MutableStateFlow<Int?>(null)
    var pollTimer: Timer? = null
    val prediction = MutableStateFlow<Prediction?>(null)
    var predictionClosed = false
    val predictionSecondsLeft = MutableStateFlow<Int?>(null)
    var predictionTimer: Timer? = null
    private val _streamInfo = MutableStateFlow<PubSubUtils.StreamInfo?>(null)
    val streamInfo: StateFlow<PubSubUtils.StreamInfo?> = _streamInfo
    private val _playbackMessage = MutableStateFlow<PubSubUtils.PlaybackMessage?>(null)
    val playbackMessage: StateFlow<PubSubUtils.PlaybackMessage?> = _playbackMessage
    var streamId: String? = null
    private val rewardList = mutableListOf<ChatMessage>()
    val namePaints = mutableListOf<NamePaint>()
    val stvBadges = mutableListOf<STVBadge>()
    val personalEmoteSets = mutableMapOf<String, List<Emote>>()
    val stvUsers = mutableListOf<STVUser>()
    var channelSTVEmoteSetId: String? = null
    var userSTVEmoteSetId: String? = null
    val translateAllMessages = MutableStateFlow<Boolean?>(null)

    val reloadMessages = MutableStateFlow(false)
    val hideRaid = MutableStateFlow(false)
    val hidePoll = MutableStateFlow(false)
    val hidePrediction = MutableStateFlow(false)

    val newMessage = MutableSharedFlow<Triple<ChatMessage, Int, Int>>()
    val addMessages = MutableSharedFlow<Pair<List<ChatMessage>, Int>>()
    val removeMessages = MutableSharedFlow<Int>()
    val updateUserMessages = MutableSharedFlow<String>()
    /** Messages changed in place (deleted by a moderator...): the chat draws them again. */
    val updateMessages = MutableSharedFlow<List<ChatMessage>>()
    val userEmotesUpdated = MutableSharedFlow<Unit>()
    val thirdPartyEmotesUpdated = MutableSharedFlow<Unit>()

    private var messageLimit = 600
    val chatMessages = mutableListOf<ChatMessage>()
    val autoCompleteList = mutableListOf<Any?>()
    private val chatters = ConcurrentHashMap<String, Chatter>()

    fun startLive(networkLibrary: String?, recentMessagesUrl: String?, channelId: String?, channelLogin: String?, channelName: String?, streamId: String?) {
        if (chatReadIRCSocket == null && chatReadWebSocket == null && eventSub == null && channelLogin != null) {
            messageLimit = applicationContext.prefs().getInt(C.CHAT_LIMIT, 600)
            this.streamId = streamId
            startLiveChat(channelId, channelLogin)
            addChatter(channelName)
            loadEmotes(channelId, channelLogin)
            if (applicationContext.prefs().getBoolean(C.CHAT_RECENT, true)) {
                loadRecentMessages(networkLibrary, recentMessagesUrl, channelLogin)
            }
            val isLoggedIn = !applicationContext.tokenPrefs().getString(C.USERNAME, null).isNullOrBlank() &&
                    (!TwitchApiHelper.getGQLHeaders(applicationContext, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                            !TwitchApiHelper.getHelixHeaders(applicationContext)[C.HEADER_TOKEN].isNullOrBlank())
            if (isLoggedIn) {
                loadUserEmotes(channelId)
            }
        }
    }

    fun startReplay(channelId: String?, channelLogin: String?, chatUrl: String? = null, videoId: String? = null, createdAt: String?, startTime: Int = 0, getCurrentPosition: () -> Long?, getCurrentSpeed: () -> Float?) {
        if (chatReplayManager == null && chatReplayManagerLocal == null) {
            messageLimit = applicationContext.prefs().getInt(C.CHAT_LIMIT, 600)
            startReplayChat(videoId, createdAt, startTime, chatUrl, getCurrentPosition, getCurrentSpeed, channelId, channelLogin)
            if (videoId != null) {
                loadEmotes(channelId, channelLogin)
            }
        }
    }

    fun resumeLive(channelId: String?, channelLogin: String?) {
        if ((chatReadJob?.isActive == false) && channelLogin != null && autoReconnect) {
            startLiveChat(channelId, channelLogin)
        }
    }

    fun resumeReplay(channelId: String?, channelLogin: String?, chatUrl: String?, videoId: String?, createdAt: String?, startTime: Int, getCurrentPosition: () -> Long?, getCurrentSpeed: () -> Float?) {
        if (chatReplayManager?.isActive == false || chatReplayManagerLocal?.isActive == false) {
            startReplayChat(videoId, createdAt, startTime, chatUrl, getCurrentPosition, getCurrentSpeed, channelId, channelLogin)
        }
    }

    override fun onCleared() {
        stopLiveChat()
        stopReplayChat()
        pollSecondsLeft.value = null
        pollTimer?.cancel()
        predictionSecondsLeft.value = null
        predictionTimer?.cancel()
        super.onCleared()
    }

    private fun loadEmotes(channelId: String?, channelLogin: String?) {
        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        val emoteQuality = applicationContext.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
        val animateGifs = applicationContext.prefs().getBoolean(C.ANIMATED_EMOTES, true)
        val useWebp = applicationContext.prefs().getBoolean(C.CHAT_USE_WEBP, true)
        val enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false)
        synchronized(thirdPartyEmotes) {
            thirdPartyEmotes.clear()
        }
        val saved = savedGlobalBadges
        if (!saved.isNullOrEmpty()) {
            synchronized(globalBadges) {
                globalBadges.clear()
                globalBadges.addAll(saved)
            }
            if (!reloadMessages.value) {
                reloadMessages.value = true
            }
        } else {
            viewModelScope.launch {
                try {
                    val badges = playerRepository.loadGlobalBadges(networkLibrary, helixHeaders, gqlHeaders, emoteQuality, enableIntegrity)
                    if (badges.isNotEmpty()) {
                        savedGlobalBadges = badges
                        synchronized(globalBadges) {
                            globalBadges.clear()
                            globalBadges.addAll(badges)
                        }
                        if (!reloadMessages.value) {
                            reloadMessages.value = true
                        }
                    }
                } catch (e: Exception) {
                    if (e.message == C.FAILED_INTEGRITY_CHECK) {
                        integrity.emit("refresh")
                    }
                }
            }
        }
        if (applicationContext.prefs().getBoolean(C.CHAT_ENABLE_STV, true)) {
            val saved = savedGlobalSTVEmotes
            if (!saved.isNullOrEmpty()) {
                synchronized(thirdPartyEmotes) {
                    thirdPartyEmotes.addAll(saved)
                    thirdPartyEmotes.sortBy { it.source }
                }
                if (!reloadMessages.value) {
                    reloadMessages.value = true
                }
                viewModelScope.launch {
                    thirdPartyEmotesUpdated.emit(Unit)
                }
                synchronized(autoCompleteList) {
                    autoCompleteList.addAll(saved.filter { it !in autoCompleteList })
                }
                synchronized(allEmotes) {
                    allEmotes.addAll(saved.filter { it.name !in allEmotes }.mapNotNull { it.name })
                }
            } else {
                viewModelScope.launch {
                    val pair = try {
                        playerRepository.loadGlobalSTVEmoteSetResponse(networkLibrary) to true
                    } catch (e: Exception) {
                        try {
                            val compressedBytes = FileInputStream("${applicationContext.cacheDir}/emote_responses/global.stv").use {
                                it.readBytes()
                            }
                            val decompressedStream = ByteArrayOutputStream()
                            InflaterOutputStream(decompressedStream).use {
                                it.write(compressedBytes)
                            }
                            decompressedStream.toByteArray().decodeToString() to false
                        } catch (e: Exception) {
                            null to false
                        }
                    }
                    val response = pair.first
                    val online = pair.second
                    if (response != null) {
                        try {
                            val emotes = playerRepository.loadSTVEmoteSet(response, useWebp, true).second
                            if (emotes.isNotEmpty()) {
                                savedGlobalSTVEmotes = emotes
                                synchronized(thirdPartyEmotes) {
                                    thirdPartyEmotes.addAll(emotes)
                                    thirdPartyEmotes.sortBy { it.source }
                                }
                                if (!reloadMessages.value) {
                                    reloadMessages.value = true
                                }
                                thirdPartyEmotesUpdated.emit(Unit)
                                synchronized(autoCompleteList) {
                                    autoCompleteList.addAll(emotes.filter { it !in autoCompleteList })
                                }
                                synchronized(allEmotes) {
                                    allEmotes.addAll(emotes.filter { it.name !in allEmotes }.mapNotNull { it.name })
                                }
                                if (online) {
                                    val directory = File(applicationContext.cacheDir, "emote_responses")
                                    directory.mkdir()
                                    val compressedStream = ByteArrayOutputStream()
                                    DeflaterOutputStream(compressedStream).use {
                                        it.write(response.toByteArray())
                                    }
                                    val compressedBytes = compressedStream.toByteArray()
                                    FileOutputStream("${applicationContext.cacheDir}/emote_responses/global.stv").use {
                                        it.write(compressedBytes)
                                    }
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                }
            }
            if (!channelId.isNullOrBlank()) {
                viewModelScope.launch {
                    var response: String? = null
                    var setId: String? = null
                    var emotes: List<Emote>? = null
                    var online = false
                    try {
                        val userResponse = playerRepository.loadSTVUserResponse(networkLibrary, channelId)
                        val user = playerRepository.loadSTVUser(userResponse, useWebp)
                        val userSetId = user.first
                        val userEmotes = user.second
                        if (!userEmotes.isNullOrEmpty()) {
                            response = userResponse
                            setId = userSetId
                            emotes = userEmotes
                            online = true
                        } else {
                            if (!userSetId.isNullOrBlank()) {
                                val emoteSetResponse = playerRepository.loadSTVEmoteSetResponse(networkLibrary, userSetId)
                                val emoteSet = playerRepository.loadSTVEmoteSet(emoteSetResponse, useWebp, false)
                                response = emoteSetResponse
                                setId = userSetId
                                emotes = emoteSet.second
                                online = true
                            }
                        }
                    } catch (e: Exception) {
                        try {
                            val compressedBytes = FileInputStream("${applicationContext.cacheDir}/emote_responses/${channelId}.stv").use {
                                it.readBytes()
                            }
                            val decompressedStream = ByteArrayOutputStream()
                            InflaterOutputStream(decompressedStream).use {
                                it.write(compressedBytes)
                            }
                            val savedResponse = decompressedStream.toByteArray().decodeToString()
                            val user = playerRepository.loadSTVUser(savedResponse, useWebp)
                            val userEmotes = user.second
                            if (!userEmotes.isNullOrEmpty()) {
                                setId = user.first
                                emotes = userEmotes
                            } else {
                                val emoteSet = playerRepository.loadSTVEmoteSet(savedResponse, useWebp, false)
                                setId = emoteSet.first
                                emotes = emoteSet.second
                            }
                            response = savedResponse
                            online = false
                        } catch (e: Exception) {

                        }
                    }
                    if (response != null) {
                        try {
                            if (!emotes.isNullOrEmpty()) {
                                channelSTVEmoteSetId = setId
                                synchronized(thirdPartyEmotes) {
                                    thirdPartyEmotes.addAll(emotes)
                                    thirdPartyEmotes.sortBy { it.source }
                                }
                                if (!reloadMessages.value) {
                                    reloadMessages.value = true
                                }
                                thirdPartyEmotesUpdated.emit(Unit)
                                synchronized(autoCompleteList) {
                                    autoCompleteList.addAll(emotes.filter { it !in autoCompleteList })
                                }
                                synchronized(allEmotes) {
                                    allEmotes.addAll(emotes.filter { it.name !in allEmotes }.mapNotNull { it.name })
                                }
                                if (online) {
                                    val directory = File(applicationContext.cacheDir, "emote_responses")
                                    directory.mkdir()
                                    val files = directory.listFiles()
                                    if (files != null && files.size >= 100) {
                                        files.minBy { it.lastModified() }.delete()
                                    }
                                    val compressedStream = ByteArrayOutputStream()
                                    DeflaterOutputStream(compressedStream).use {
                                        it.write(response.toByteArray())
                                    }
                                    val compressedBytes = compressedStream.toByteArray()
                                    FileOutputStream("${applicationContext.cacheDir}/emote_responses/${channelId}.stv").use {
                                        it.write(compressedBytes)
                                    }
                                } else {
                                    onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.loaded_cached_stv_emotes)))
                                }
                            } else {
                                if (online) {
                                    File("${applicationContext.cacheDir}/emote_responses/${channelId}.stv").delete()
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                }
            }
        }
        if (applicationContext.prefs().getBoolean(C.CHAT_ENABLE_BTTV, true)) {
            val saved = savedGlobalBTTVEmotes
            if (!saved.isNullOrEmpty()) {
                synchronized(thirdPartyEmotes) {
                    thirdPartyEmotes.addAll(saved)
                    thirdPartyEmotes.sortBy { it.source }
                }
                if (!reloadMessages.value) {
                    reloadMessages.value = true
                }
                viewModelScope.launch {
                    thirdPartyEmotesUpdated.emit(Unit)
                }
                synchronized(autoCompleteList) {
                    autoCompleteList.addAll(saved.filter { it !in autoCompleteList })
                }
                synchronized(allEmotes) {
                    allEmotes.addAll(saved.filter { it.name !in allEmotes }.mapNotNull { it.name })
                }
            } else {
                viewModelScope.launch {
                    val pair = try {
                        playerRepository.loadGlobalBTTVEmotesResponse(networkLibrary) to true
                    } catch (e: Exception) {
                        try {
                            val compressedBytes = FileInputStream("${applicationContext.cacheDir}/emote_responses/global.bttv").use {
                                it.readBytes()
                            }
                            val decompressedStream = ByteArrayOutputStream()
                            InflaterOutputStream(decompressedStream).use {
                                it.write(compressedBytes)
                            }
                            decompressedStream.toByteArray().decodeToString() to false
                        } catch (e: Exception) {
                            null to false
                        }
                    }
                    val response = pair.first
                    val online = pair.second
                    if (response != null) {
                        try {
                            val emotes = playerRepository.loadGlobalBTTVEmotes(response, useWebp)
                            if (emotes.isNotEmpty()) {
                                savedGlobalBTTVEmotes = emotes
                                synchronized(thirdPartyEmotes) {
                                    thirdPartyEmotes.addAll(emotes)
                                    thirdPartyEmotes.sortBy { it.source }
                                }
                                if (!reloadMessages.value) {
                                    reloadMessages.value = true
                                }
                                thirdPartyEmotesUpdated.emit(Unit)
                                synchronized(autoCompleteList) {
                                    autoCompleteList.addAll(emotes.filter { it !in autoCompleteList })
                                }
                                synchronized(allEmotes) {
                                    allEmotes.addAll(emotes.filter { it.name !in allEmotes }.mapNotNull { it.name })
                                }
                                if (online) {
                                    val directory = File(applicationContext.cacheDir, "emote_responses")
                                    directory.mkdir()
                                    val compressedStream = ByteArrayOutputStream()
                                    DeflaterOutputStream(compressedStream).use {
                                        it.write(response.toByteArray())
                                    }
                                    val compressedBytes = compressedStream.toByteArray()
                                    FileOutputStream("${applicationContext.cacheDir}/emote_responses/global.bttv").use {
                                        it.write(compressedBytes)
                                    }
                                } else {
                                    onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.loaded_cached_bttv_emotes)))
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                }
            }
            if (!channelId.isNullOrBlank()) {
                viewModelScope.launch {
                    val pair = try {
                        playerRepository.loadBTTVEmotesResponse(networkLibrary, channelId) to true
                    } catch (e: Exception) {
                        try {
                            val compressedBytes = FileInputStream("${applicationContext.cacheDir}/emote_responses/${channelId}.bttv").use {
                                it.readBytes()
                            }
                            val decompressedStream = ByteArrayOutputStream()
                            InflaterOutputStream(decompressedStream).use {
                                it.write(compressedBytes)
                            }
                            decompressedStream.toByteArray().decodeToString() to false
                        } catch (e: Exception) {
                            null to false
                        }
                    }
                    val response = pair.first
                    val online = pair.second
                    if (response != null) {
                        try {
                            val emotes = playerRepository.loadBTTVEmotes(response, useWebp)
                            if (emotes.isNotEmpty()) {
                                synchronized(thirdPartyEmotes) {
                                    thirdPartyEmotes.addAll(emotes)
                                    thirdPartyEmotes.sortBy { it.source }
                                }
                                if (!reloadMessages.value) {
                                    reloadMessages.value = true
                                }
                                thirdPartyEmotesUpdated.emit(Unit)
                                synchronized(autoCompleteList) {
                                    autoCompleteList.addAll(emotes.filter { it !in autoCompleteList })
                                }
                                synchronized(allEmotes) {
                                    allEmotes.addAll(emotes.filter { it.name !in allEmotes }.mapNotNull { it.name })
                                }
                                if (online) {
                                    val directory = File(applicationContext.cacheDir, "emote_responses")
                                    directory.mkdir()
                                    val files = directory.listFiles()
                                    if (files != null && files.size >= 100) {
                                        files.minBy { it.lastModified() }.delete()
                                    }
                                    val compressedStream = ByteArrayOutputStream()
                                    DeflaterOutputStream(compressedStream).use {
                                        it.write(response.toByteArray())
                                    }
                                    val compressedBytes = compressedStream.toByteArray()
                                    FileOutputStream("${applicationContext.cacheDir}/emote_responses/${channelId}.bttv").use {
                                        it.write(compressedBytes)
                                    }
                                } else {
                                    onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.loaded_cached_ffz_emotes)))
                                }
                            } else {
                                if (online) {
                                    File("${applicationContext.cacheDir}/emote_responses/${channelId}.bttv").delete()
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                }
            }
        }
        if (applicationContext.prefs().getBoolean(C.CHAT_ENABLE_FFZ, true)) {
            val saved = savedGlobalFFZEmotes
            if (!saved.isNullOrEmpty()) {
                synchronized(thirdPartyEmotes) {
                    thirdPartyEmotes.addAll(saved)
                    thirdPartyEmotes.sortBy { it.source }
                }
                if (!reloadMessages.value) {
                    reloadMessages.value = true
                }
                viewModelScope.launch {
                    thirdPartyEmotesUpdated.emit(Unit)
                }
                synchronized(autoCompleteList) {
                    autoCompleteList.addAll(saved.filter { it !in autoCompleteList })
                }
                synchronized(allEmotes) {
                    allEmotes.addAll(saved.filter { it.name !in allEmotes }.mapNotNull { it.name })
                }
            } else {
                viewModelScope.launch {
                    val pair = try {
                        playerRepository.loadGlobalFFZEmotesResponse(networkLibrary) to true
                    } catch (e: Exception) {
                        try {
                            val compressedBytes = FileInputStream("${applicationContext.cacheDir}/emote_responses/global.ffz").use {
                                it.readBytes()
                            }
                            val decompressedStream = ByteArrayOutputStream()
                            InflaterOutputStream(decompressedStream).use {
                                it.write(compressedBytes)
                            }
                            decompressedStream.toByteArray().decodeToString() to false
                        } catch (e: Exception) {
                            null to false
                        }
                    }
                    val response = pair.first
                    val online = pair.second
                    if (response != null) {
                        try {
                            val emotes = playerRepository.loadGlobalFFZEmotes(response, useWebp)
                            if (emotes.isNotEmpty()) {
                                savedGlobalFFZEmotes = emotes
                                synchronized(thirdPartyEmotes) {
                                    thirdPartyEmotes.addAll(emotes)
                                    thirdPartyEmotes.sortBy { it.source }
                                }
                                if (!reloadMessages.value) {
                                    reloadMessages.value = true
                                }
                                thirdPartyEmotesUpdated.emit(Unit)
                                synchronized(autoCompleteList) {
                                    autoCompleteList.addAll(emotes.filter { it !in autoCompleteList })
                                }
                                synchronized(allEmotes) {
                                    allEmotes.addAll(emotes.filter { it.name !in allEmotes }.mapNotNull { it.name })
                                }
                                if (online) {
                                    val directory = File(applicationContext.cacheDir, "emote_responses")
                                    directory.mkdir()
                                    val compressedStream = ByteArrayOutputStream()
                                    DeflaterOutputStream(compressedStream).use {
                                        it.write(response.toByteArray())
                                    }
                                    val compressedBytes = compressedStream.toByteArray()
                                    FileOutputStream("${applicationContext.cacheDir}/emote_responses/global.ffz").use {
                                        it.write(compressedBytes)
                                    }
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                }
            }
            if (!channelId.isNullOrBlank()) {
                viewModelScope.launch {
                    val pair = try {
                        playerRepository.loadFFZEmotesResponse(networkLibrary, channelId) to true
                    } catch (e: Exception) {
                        try {
                            val compressedBytes = FileInputStream("${applicationContext.cacheDir}/emote_responses/${channelId}.ffz").use {
                                it.readBytes()
                            }
                            val decompressedStream = ByteArrayOutputStream()
                            InflaterOutputStream(decompressedStream).use {
                                it.write(compressedBytes)
                            }
                            decompressedStream.toByteArray().decodeToString() to false
                        } catch (e: Exception) {
                            null to false
                        }
                    }
                    val response = pair.first
                    val online = pair.second
                    if (response != null) {
                        try {
                            val emotes = playerRepository.loadFFZEmotes(response, useWebp)
                            if (emotes.isNotEmpty()) {
                                synchronized(thirdPartyEmotes) {
                                    thirdPartyEmotes.addAll(emotes)
                                    thirdPartyEmotes.sortBy { it.source }
                                }
                                if (!reloadMessages.value) {
                                    reloadMessages.value = true
                                }
                                thirdPartyEmotesUpdated.emit(Unit)
                                synchronized(autoCompleteList) {
                                    autoCompleteList.addAll(emotes.filter { it !in autoCompleteList })
                                }
                                synchronized(allEmotes) {
                                    allEmotes.addAll(emotes.filter { it.name !in allEmotes }.mapNotNull { it.name })
                                }
                                if (online) {
                                    val directory = File(applicationContext.cacheDir, "emote_responses")
                                    directory.mkdir()
                                    val files = directory.listFiles()
                                    if (files != null && files.size >= 100) {
                                        files.minBy { it.lastModified() }.delete()
                                    }
                                    val compressedStream = ByteArrayOutputStream()
                                    DeflaterOutputStream(compressedStream).use {
                                        it.write(response.toByteArray())
                                    }
                                    val compressedBytes = compressedStream.toByteArray()
                                    FileOutputStream("${applicationContext.cacheDir}/emote_responses/${channelId}.ffz").use {
                                        it.write(compressedBytes)
                                    }
                                }
                            } else {
                                if (online) {
                                    File("${applicationContext.cacheDir}/emote_responses/${channelId}.ffz").delete()
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                }
            }
        }
        if (!channelId.isNullOrBlank() || !channelLogin.isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    val badges = playerRepository.loadChannelBadges(networkLibrary, helixHeaders, gqlHeaders, channelId, channelLogin, emoteQuality, enableIntegrity)
                    if (badges.isNotEmpty()) {
                        synchronized(channelBadges) {
                            channelBadges.clear()
                            channelBadges.addAll(badges)
                        }
                        if (!reloadMessages.value) {
                            reloadMessages.value = true
                        }
                    }
                } catch (e: Exception) {
                    if (e.message == C.FAILED_INTEGRITY_CHECK) {
                        integrity.emit("refresh")
                    }
                }
            }
            viewModelScope.launch {
                try {
                    val emotes = playerRepository.loadCheerEmotes(networkLibrary, helixHeaders, gqlHeaders, channelId, channelLogin, animateGifs, enableIntegrity)
                    if (emotes.isNotEmpty()) {
                        synchronized(cheerEmotes) {
                            cheerEmotes.clear()
                            cheerEmotes.addAll(emotes)
                        }
                        if (!reloadMessages.value) {
                            reloadMessages.value = true
                        }
                    }
                } catch (e: Exception) {
                    if (e.message == C.FAILED_INTEGRITY_CHECK) {
                        integrity.emit("refresh")
                    }
                }
            }
        }
    }

    private fun loadUserEmotes(channelId: String?) {
        val saved = savedUserEmotes
        if (!saved.isNullOrEmpty()) {
            synchronized(userEmotes) {
                userEmotes.clear()
                userEmotes.addAll(
                    saved.sortedByDescending { it.ownerId == channelId }.map {
                        Emote(
                            name = it.name,
                            url1x = it.url1x,
                            url2x = it.url2x,
                            url3x = it.url3x,
                            url4x = it.url4x,
                            format = it.format
                        )
                    }
                )
            }
            viewModelScope.launch {
                userEmotesUpdated.emit(Unit)
            }
            synchronized(autoCompleteList) {
                autoCompleteList.addAll(saved.map {
                    Emote(
                        name = it.name,
                        url1x = it.url1x,
                        url2x = it.url2x,
                        url3x = it.url3x,
                        url4x = it.url4x,
                        format = it.format
                    )
                }.filter { it !in autoCompleteList })
            }
            synchronized(allEmotes) {
                allEmotes.addAll(saved.filter { it.name !in allEmotes }.mapNotNull { it.name })
            }
        } else {
            val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
            val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
            if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() || !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                viewModelScope.launch {
                    try {
                        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                        val accountId = applicationContext.tokenPrefs().getString(C.USER_ID, null)
                        val animateGifs =  applicationContext.prefs().getBoolean(C.ANIMATED_EMOTES, true)
                        val enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false)
                        val emotes = playerRepository.loadUserEmotes(networkLibrary, helixHeaders, gqlHeaders, channelId, accountId, animateGifs, enableIntegrity)
                        if (emotes.isNotEmpty()) {
                            val sorted = emotes.sortedByDescending { it.setId }
                            synchronized(userEmotes) {
                                userEmotes.clear()
                                userEmotes.addAll(
                                    sorted.sortedByDescending { it.ownerId == channelId }.map {
                                        Emote(
                                            name = it.name,
                                            url1x = it.url1x,
                                            url2x = it.url2x,
                                            url3x = it.url3x,
                                            url4x = it.url4x,
                                            format = it.format
                                        )
                                    }
                                )
                            }
                            userEmotesUpdated.emit(Unit)
                            synchronized(autoCompleteList) {
                                autoCompleteList.addAll(sorted.map {
                                    Emote(
                                        name = it.name,
                                        url1x = it.url1x,
                                        url2x = it.url2x,
                                        url3x = it.url3x,
                                        url4x = it.url4x,
                                        format = it.format
                                    )
                                }.filter { it !in autoCompleteList })
                            }
                            synchronized(allEmotes) {
                                allEmotes.addAll(sorted.filter { it.name !in allEmotes }.mapNotNull { it.name })
                            }
                            loadedUserEmotes = true
                        }
                    } catch (e: Exception) {
                        if (e.message == C.FAILED_INTEGRITY_CHECK) {
                            integrity.emit("refresh")
                        }
                    }
                }
            }
        }
    }

    fun loadRecentEmotes() {
        viewModelScope.launch {
            hasRecentEmotes.value = playerRepository.loadRecentEmotes().isNotEmpty()
        }
    }

    fun getEmoteBytes(chatUrl: String, localData: Pair<Long, Int>): ByteArray? {
        return if (chatUrl.toUri().scheme == ContentResolver.SCHEME_CONTENT) {
            applicationContext.contentResolver.openInputStream(chatUrl.toUri())?.bufferedReader()
        } else {
            FileInputStream(File(chatUrl)).bufferedReader()
        }?.use { fileReader ->
            val buffer = CharArray(localData.second)
            fileReader.skip(localData.first)
            fileReader.read(buffer, 0, localData.second)
            Base64.decode(buffer.concatToString(), Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }

    fun reloadEmotes(channelId: String?, channelLogin: String?) {
        savedGlobalBadges = null
        savedGlobalSTVEmotes = null
        savedGlobalBTTVEmotes = null
        savedGlobalFFZEmotes = null
        loadEmotes(channelId, channelLogin)
    }

    fun loadRecentMessages(networkLibrary: String?, recentMessagesUrl: String?, channelLogin: String) {
        if (!recentMessagesUrl.isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    val myId = ownUserId ?: applicationContext.tokenPrefs().getString(C.USER_ID, null)
                    val myLogin = ownUserLogin ?: applicationContext.tokenPrefs().getString(C.USERNAME, null)
                    val list = mutableListOf<ChatMessage>()
                    playerRepository.loadRecentMessages(networkLibrary, recentMessagesUrl, channelLogin, applicationContext.prefs().getInt(C.CHAT_RECENT_LIMIT, 100).toString()).messages.forEach { message ->
                        val ircMessage = ChatUtils.parseIRCMessage(message)
                        when (ircMessage.command) {
                            "PRIVMSG" -> ChatUtils.parseChatMessage(ircMessage)
                            "USERNOTICE" -> {
                                if (applicationContext.prefs().getBoolean(C.CHAT_SHOW_USER_NOTICE, true)) {
                                    ChatUtils.parseChatMessage(ircMessage, TvChatNotices.build(applicationContext, ircMessage.tags))
                                } else null
                            }
                            "CLEARMSG" -> {
                                // the deleted message is marked in place (grey and struck through, or hidden), no line added
                                ChatUtils.parseClearMessage(ircMessage).targetMsgId?.let { targetId ->
                                    list.find { it.id == targetId }?.deleted = true
                                }
                                null
                            }
                            "CLEARCHAT" -> {
                                val clearChat = ChatUtils.parseClearChat(applicationContext, ircMessage)
                                // timeout / ban: the messages of the user are marked deleted
                                clearChat.userLogin?.let { login -> list.forEach { if (it.userLogin.equals(login, true)) it.deleted = true } }
                                if (applicationContext.prefs().getBoolean(C.CHAT_SHOW_CLEAR_CHAT, true)) {
                                    clearChat
                                } else null
                            }
                            "NOTICE" -> ChatUtils.parseNotice(ircMessage)
                            else -> null
                        }?.let { msg ->
                            recordSeenChatter(msg, channelLogin, myId, myLogin)
                            if (!com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.shouldFilter(applicationContext, msg, channelLogin, myId, myLogin)) {
                                if (msg.reply?.message != null && showReplies()) {
                                    val replyLine = ChatMessage(
                                        type = ChatMessage.REPLY_MESSAGE,
                                        reply = msg.reply,
                                        replyParent = msg,
                                    )
                                    if (!com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.shouldFilter(applicationContext, replyLine, channelLogin, myId, myLogin)) {
                                        list.add(replyLine)
                                    }
                                }
                                list.add(msg)
                            }
                        }
                    }
                    if (list.isNotEmpty()) {
                        synchronized(chatMessages) {
                            val left = messageLimit - chatMessages.size
                            if (left > 0) {
                                val items = list.takeLast(left)
                                chatMessages.addAll(0, items)
                                Pair(items, chatMessages.lastIndex)
                            } else null
                        }.let {
                            if (it != null) {
                                addMessages.emit(it)
                            }
                        }
                    }
                } catch (e: Exception) {

                }
            }
        }
    }

    fun checkTranslateAllMessages(id: String) {
        viewModelScope.launch {
            translateAllMessages.value = playerRepository.getTranslatedChannel(id) != null
        }
    }

    fun saveTranslatedChannel(channelId: String) {
        viewModelScope.launch {
            playerRepository.saveTranslatedChannel(TranslatedChannel(channelId))
        }
    }

    fun deleteTranslatedChannel(channelId: String) {
        viewModelScope.launch {
            playerRepository.deleteTranslatedChannel(TranslatedChannel(channelId))
        }
    }

    /** Marks the messages shown or waiting for the delay of the chat as deleted, the ones shown are drawn again. */
    private suspend fun markDeleted(predicate: (ChatMessage) -> Boolean) {
        synchronized(delayedMessages) {
            delayedMessages.forEach { (message, _) -> if (predicate(message)) message.deleted = true }
        }
        val shown = synchronized(chatMessages) {
            chatMessages.filter { it.type != ChatMessage.REPLY_MESSAGE && !it.deleted && predicate(it) }.onEach { it.deleted = true }
        }
        if (shown.isNotEmpty()) {
            updateMessages.emit(shown)
        }
    }

    private fun getClearMessage(chatMessage: ChatMessage, deletedMessage: ChatMessage?, nameDisplay: String?): ChatMessage {
        val login = deletedMessage?.userLogin ?: chatMessage.userLogin
        val userName = if (deletedMessage?.userName != null && login != null && !login.equals(deletedMessage.userName, true)) {
            when (nameDisplay) {
                "0" -> "${deletedMessage.userName}(${login})"
                "1" -> deletedMessage.userName
                else -> login
            }
        } else {
            deletedMessage?.userName ?: login
        }
        val message = ContextCompat.getString(applicationContext, R.string.chat_clearmsg).format(userName, deletedMessage?.message ?: chatMessage.message)
        val messageIndex = message.indexOf(": ") + 2
        return ChatMessage(
            type = ChatMessage.USER_MESSAGE,
            userId = deletedMessage?.userId,
            userLogin = login,
            userName = deletedMessage?.userName,
            systemMsg = message,
            emotes = deletedMessage?.emotes?.map {
                TwitchEmote(
                    id = it.id,
                    begin = it.begin + messageIndex,
                    end = it.end + messageIndex
                )
            },
            timestamp = chatMessage.timestamp,
            fullMsg = chatMessage.fullMsg
        )
    }

    /**
     * Chat delay (live): what the player shows, in the time of Twitch (null: not known). Given by the chat of the player.
     */
    var programTime: (() -> Long?)? = null
    /** Forces live chat into Auto delay mode without overwriting user prefs while Experimental VOD Adblock is active. */
    var forceAutoDelay: (() -> Boolean)? = null
    /** The delay of the chat in Auto, measured on the last message shown with the picture (ms). */
    @Volatile
    var autoDelayMs = 0L
        private set
    private val delayedMessages = ArrayDeque<Pair<ChatMessage, Long>>()
    private var delayJob: Job? = null
    private var ownUserId: String? = null
    private var ownUserLogin: String? = null
    private var activeChannelLogin: String? = null
    private val seenChattersLock = Any()
    private var seenChattersChannel: String? = null
    private val seenChatters = ArrayDeque<com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.BlockedUser>()
    private val slowChatLock = Any()
    private var slowChatTokens = 1.6
    private var slowChatLastRefillAt = 0L
    private var lastSlowChatTarget: ChatMessage? = null
    private var lastSlowChatAllowed = true
    private val slowChatPriorityBadges = setOf(
        "broadcaster", "moderator", "lead_moderator", "vip", "staff", "admin", "global_mod"
    )

    /** The live chat waits for the stream: "chat_delay_mode" 0 off, 1 auto, 2 manual ("chat_delay_seconds"). */
    private fun delayMode() = applicationContext.prefs().getString(C.CHAT_DELAY_MODE, "1")?.toIntOrNull() ?: 1

    private fun effectiveDelayMode(): Int = if (forceAutoDelay?.invoke() == true) 1 else delayMode()

    fun effectiveAutoDelayMs(): Long =
        if (autoDelayMs == 0L && forceAutoDelay?.invoke() == true) {
            com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.TARGET_OFFSET_MS
        } else {
            autoDelayMs
        }

    /** Slow chat rate ("chat_slow_rate"): 0 off, or max normal messages per second (1, 2, 4, 6). */
    private fun slowChatRate() = applicationContext.prefs().getString(C.CHAT_SLOW_RATE, "0")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun resetSlowChat() {
        synchronized(slowChatLock) {
            slowChatLastRefillAt = 0L
            lastSlowChatTarget = null
        }
    }

    private fun bodyMentionsUsername(bodyLower: String, uname: String): Boolean {
        if (uname.length < 2 || bodyLower.isEmpty()) return false
        var idx = bodyLower.indexOf(uname)
        while (idx != -1) {
            val prev = if (idx > 0) bodyLower[idx - 1] else ' '
            val end = idx + uname.length
            val next = if (end < bodyLower.length) bodyLower[end] else ' '
            val validStart = idx == 0 || prev == '@' || !(prev in 'a'..'z' || prev in '0'..'9' || prev == '_')
            val validEnd = end == bodyLower.length || !(next in 'a'..'z' || next in '0'..'9' || next == '_')
            if (validStart && validEnd) return true
            idx = bodyLower.indexOf(uname, idx + 1)
        }
        return false
    }

    private fun isExemptPriorityChatMessage(msg: ChatMessage): Boolean {
        if (msg.type != ChatMessage.USER_MESSAGE) return true
        if (!msg.systemMsg.isNullOrBlank() || msg.noticeIcon != null) return true
        if (msg.reward != null || (msg.bits ?: 0) > 0 || msg.msgId == "highlighted-message") return true
        if (!ownUserId.isNullOrBlank() && msg.userId == ownUserId) return true
        val chan = activeChannelLogin
        if (!chan.isNullOrBlank() && msg.userLogin?.equals(chan, ignoreCase = true) == true) return true
        if (msg.badges?.any { it.setId in slowChatPriorityBadges } == true) return true
        val myLogin = ownUserLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (myLogin != null) {
            if (msg.userLogin?.equals(myLogin, ignoreCase = true) == true || msg.userName?.equals(myLogin, ignoreCase = true) == true) {
                return true
            }
            if (msg.reply?.userLogin?.equals(myLogin, ignoreCase = true) == true || msg.reply?.userName?.equals(myLogin, ignoreCase = true) == true) {
                return true
            }
            val bodyLower = msg.message?.lowercase()
            if (!bodyLower.isNullOrEmpty() && bodyMentionsUsername(bodyLower, myLogin)) {
                return true
            }
        }
        return false
    }

    private fun consumeSlowChatSlot(rate: Int): Boolean {
        val now = System.currentTimeMillis()
        val maxTokens = if (rate <= 1) 1.0 else 1.6
        if (slowChatLastRefillAt <= 0L || now < slowChatLastRefillAt) {
            slowChatLastRefillAt = now
            slowChatTokens = maxTokens
        } else {
            val elapsedMs = now - slowChatLastRefillAt
            slowChatLastRefillAt = now
            slowChatTokens = minOf(maxTokens, slowChatTokens + (elapsedMs * rate) / 1000.0)
        }
        return if (slowChatTokens >= 1.0) {
            slowChatTokens -= 1.0
            true
        } else {
            false
        }
    }

    private fun shouldAllowSlowChat(message: ChatMessage): Boolean {
        if (!started && chatReplayManager == null && chatReplayManagerLocal == null) return true
        val rate = slowChatRate()
        if (rate <= 0) return true
        val target = if (message.type == ChatMessage.REPLY_MESSAGE) message.replyParent ?: return true else message
        synchronized(slowChatLock) {
            if (target === lastSlowChatTarget) {
                lastSlowChatTarget = null
                return lastSlowChatAllowed
            }
            val allowed = isExemptPriorityChatMessage(target) || consumeSlowChatSlot(rate)
            if (message.type == ChatMessage.REPLY_MESSAGE) {
                lastSlowChatTarget = target
                lastSlowChatAllowed = allowed
            } else {
                lastSlowChatTarget = null
            }
            return allowed
        }
    }

    private fun recordSeenChatter(
        message: ChatMessage,
        channelLogin: String?,
        myId: String?,
        myLogin: String?,
    ) {
        if (message.type != ChatMessage.USER_MESSAGE || !message.systemMsg.isNullOrBlank() || message.noticeIcon != null) {
            return
        }
        if (!myId.isNullOrBlank() && message.userId == myId) return
        val norm = com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.normalizeLogin(message.userLogin) ?: return
        if (!myLogin.isNullOrBlank() && norm.equals(myLogin, ignoreCase = true)) return
        if (!channelLogin.isNullOrBlank() && norm.equals(channelLogin, ignoreCase = true)) return
        if (applicationContext.prefs().getBoolean(C.CHAT_HIDE_BOTS, false) &&
            com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.isBotMessage(message) &&
            !com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.isBlocked(applicationContext, norm)
        ) {
            return
        }
        val cleanName = message.userName?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(seenChattersLock) {
            if (seenChattersChannel != channelLogin) {
                seenChattersChannel = channelLogin
                seenChatters.clear()
            }
            seenChatters.removeAll { it.login == norm }
            seenChatters.addFirst(com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.BlockedUser(norm, cleanName))
            while (seenChatters.size > 40) {
                seenChatters.removeLast()
            }
        }
    }

    fun getRecentChatters(limit: Int = 40): List<com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.BlockedUser> {
        val hideBots = applicationContext.prefs().getBoolean(C.CHAT_HIDE_BOTS, false)
        val result = ArrayList<com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.BlockedUser>(limit)
        val seen = HashSet<String>()
        synchronized(chatMessages) {
            for (i in chatMessages.lastIndex downTo 0) {
                val msg = chatMessages[i]
                if (msg.type != ChatMessage.USER_MESSAGE || !msg.systemMsg.isNullOrBlank() || msg.noticeIcon != null) continue
                if (!ownUserId.isNullOrBlank() && msg.userId == ownUserId) continue
                val norm = com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.normalizeLogin(msg.userLogin) ?: continue
                if (!ownUserLogin.isNullOrBlank() && norm.equals(ownUserLogin, ignoreCase = true)) continue
                if (!activeChannelLogin.isNullOrBlank() && norm.equals(activeChannelLogin, ignoreCase = true)) continue
                if (hideBots && com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.isBotMessage(msg) &&
                    !com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.isBlocked(applicationContext, norm)
                ) continue
                if (seen.add(norm)) {
                    result.add(com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.BlockedUser(norm, msg.userName?.trim()?.takeIf { it.isNotEmpty() }))
                    if (result.size >= limit) return result
                }
            }
        }
        synchronized(seenChattersLock) {
            if (seenChattersChannel == activeChannelLogin) {
                for (entry in seenChatters) {
                    if (seen.add(entry.login)) {
                        result.add(entry)
                        if (result.size >= limit) break
                    }
                }
            }
        }
        return result
    }

    fun purgeFilteredMessages(): Boolean {
        synchronized(delayedMessages) {
            delayedMessages.removeAll { (msg, _) ->
                com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.shouldFilter(
                    applicationContext, msg, activeChannelLogin, ownUserId, ownUserLogin
                )
            }
        }
        synchronized(liveBackupBuffer) {
            liveBackupBuffer.removeAll { msg ->
                com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.shouldFilter(
                    applicationContext, msg, activeChannelLogin, ownUserId, ownUserLogin
                )
            }
        }
        return synchronized(chatMessages) {
            chatMessages.removeAll { msg ->
                com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.shouldFilter(
                    applicationContext, msg, activeChannelLogin, ownUserId, ownUserLogin
                )
            }
        }
    }

    private fun appendLiveBackup(message: ChatMessage) {
        if (message.type == ChatMessage.REPLY_MESSAGE && !showReplies()) {
            return
        }
        synchronized(liveBackupBuffer) {
            liveBackupBuffer.addLast(message)
            while (liveBackupBuffer.size > 100) {
                liveBackupBuffer.removeFirst()
            }
        }
    }

    suspend fun onMessage(message: ChatMessage, fromReplay: Boolean = false) {
        recordSeenChatter(message, activeChannelLogin, ownUserId, ownUserLogin)
        if (com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.shouldFilter(
                applicationContext, message, activeChannelLogin, ownUserId, ownUserLogin
            )
        ) {
            return
        }
        if (!shouldAllowSlowChat(message)) {
            return
        }
        // a message of the live chat (with its time of Twitch), not one of ours: shown when the stream gets there
        if (!fromReplay && started && message.timestamp != null && (message.userId == null || message.userId != ownUserId) && effectiveDelayMode() != 0) {
            synchronized(delayedMessages) {
                delayedMessages.addLast(message to System.currentTimeMillis())
            }
            startDelayLoop()
            return
        }
        if (hybridReplayActive && !fromReplay) {
            appendLiveBackup(message)
            if (message.userId != null && message.userId == ownUserId) {
                showMessage(message)
            }
            return
        }
        showMessage(message)
    }

    private fun startDelayLoop() {
        if (delayJob?.isActive == true) {
            return
        }
        delayJob = viewModelScope.launch {
            while (true) {
                val forcedAuto = forceAutoDelay?.invoke() == true
                val mode = if (forcedAuto) 1 else delayMode()
                val head = synchronized(delayedMessages) { delayedMessages.firstOrNull() }
                if (head == null) {
                    if (mode == 0) break
                    delay(100)
                    continue
                }
                val (message, arrival) = head
                val waited = System.currentTimeMillis() - arrival
                if (hybridReplayActive) {
                    if (waited >= com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.TARGET_OFFSET_MS) {
                        synchronized(delayedMessages) {
                            if (delayedMessages.firstOrNull() === head) delayedMessages.removeFirst()
                        }
                        appendLiveBackup(message)
                    } else {
                        delay(100)
                    }
                    continue
                }
                val maxWaitMs = if (forcedAuto) 120_000L else 60_000L
                val due = when {
                    // never more than maxWaitMs, and the delay turned off: everything at once
                    waited >= maxWaitMs || mode == 0 -> true
                    mode == 2 -> waited >= applicationContext.prefs().getInt(C.CHAT_DELAY_SECONDS, 5).coerceIn(1, 60) * 1000L
                    else -> {
                        val shown = try {
                            programTime?.invoke()
                        } catch (e: Exception) {
                            null
                        }
                        if (shown != null && message.timestamp != null) {
                            (shown >= message.timestamp).also { if (it) autoDelayMs = waited }
                        } else {
                            // not known (start, ad...): the last delay measured, else target offset when experimental VOD is active
                            val fallbackDelay = if (autoDelayMs > 0L) {
                                autoDelayMs
                            } else if (forcedAuto) {
                                com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.TARGET_OFFSET_MS
                            } else {
                                0L
                            }
                            waited >= fallbackDelay
                        }
                    }
                }
                if (due) {
                    synchronized(delayedMessages) {
                        if (delayedMessages.firstOrNull() === head) delayedMessages.removeFirst()
                    }
                    showMessage(message)
                } else {
                    delay(100)
                }
            }
        }
    }

    private fun clearDelayedMessages() {
        delayJob?.cancel()
        delayJob = null
        synchronized(delayedMessages) { delayedMessages.clear() }
    }

    /** The line "Replying to ..." above a reply, from the chat settings. */
    private fun showReplies() = applicationContext.prefs().getBoolean(C.CHAT_SHOW_REPLIES, true)

    private suspend fun showMessage(message: ChatMessage) {
        if (message.type == ChatMessage.REPLY_MESSAGE && !showReplies()) {
            return
        }
        synchronized(chatMessages) {
            chatMessages.add(message)
            val removeCount = if (chatMessages.size > messageLimit) {
                chatMessages.size - messageLimit
            } else 0
            if (newMessage.subscriptionCount.value > 0) {
                Triple(message, chatMessages.lastIndex, removeCount)
            } else {
                if (removeCount > 0) {
                    repeat(removeCount) {
                        chatMessages.removeAt(0)
                    }
                }
                null
            }
        }?.let {
            newMessage.emit(it)
        }
    }

    fun startLiveChat(channelId: String?, channelLogin: String) {
        stopLiveChat()
        started = true
        ownUserId = applicationContext.tokenPrefs().getString(C.USER_ID, null)
        ownUserLogin = applicationContext.tokenPrefs().getString(C.USERNAME, null)
        activeChannelLogin = channelLogin
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false)
        val accountId = applicationContext.tokenPrefs().getString(C.USER_ID, null)
        val accountLogin = applicationContext.tokenPrefs().getString(C.USERNAME, null)
        val isLoggedIn = !accountLogin.isNullOrBlank() && (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() || !helixHeaders[C.HEADER_TOKEN].isNullOrBlank())
        val usePubSub = applicationContext.prefs().getBoolean(C.CHAT_PUB_SUB_ENABLED, true)
        val showUserNotice = applicationContext.prefs().getBoolean(C.CHAT_SHOW_USER_NOTICE, true)
        val showClearMsg = applicationContext.prefs().getBoolean(C.CHAT_SHOW_CLEAR_MSG, true)
        val showClearChat = applicationContext.prefs().getBoolean(C.CHAT_SHOW_CLEAR_CHAT, true)
        val nameDisplay = applicationContext.prefs().getString(C.UI_NAME_DISPLAY, "0")
        val useApiChatMessages = applicationContext.prefs().getBoolean(C.DEBUG_API_CHAT_MESSAGES, true)
        val showWebSocketDebugInfo = applicationContext.prefs().getBoolean(C.DEBUG_WEBSOCKET_INFO, false)
        if (applicationContext.prefs().getBoolean(C.DEBUG_EVENT_SUB_CHAT, false) && !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            eventSub = EventSubWebSocket(EventSubListener(helixHeaders, channelLogin, showUserNotice, showClearChat, usePubSub, networkLibrary, isLoggedIn, accountId, channelId))
            chatReadJob = eventSub?.connect(viewModelScope)
        } else {
            val gqlToken = gqlHeaders[C.HEADER_TOKEN]?.removePrefix("OAuth ")
            val helixToken = helixHeaders[C.HEADER_TOKEN]?.removePrefix("Bearer ")
            if (applicationContext.prefs().getBoolean(C.CHAT_USE_WEBSOCKET, true)) {
                chatReadWebSocket = ChatReadWebSocket(channelLogin, ChatReadListener(channelLogin, nameDisplay, showUserNotice, showClearMsg, showClearChat, usePubSub, networkLibrary, isLoggedIn, accountId, channelId))
                chatReadJob = chatReadWebSocket?.connect(viewModelScope)
                if (isLoggedIn && (!gqlToken.isNullOrBlank() || !helixToken.isNullOrBlank())) {
                    chatWriteWebSocket = ChatWriteWebSocket(
                        userLogin = accountLogin,
                        userToken = gqlToken?.takeIf { it.isNotBlank() } ?: helixToken,
                        channelLogin = channelLogin,
                        listener = ChatWriteListener(channelId, showWebSocketDebugInfo)
                    )
                    chatWriteJob = chatWriteWebSocket?.connect(viewModelScope)
                }
            } else {
                val useSSL = applicationContext.prefs().getBoolean(C.CHAT_USE_SSL, true)
                chatReadIRCSocket = ChatReadIRCSocket(useSSL, channelLogin, ChatReadListener(channelLogin, nameDisplay, showUserNotice, showClearMsg, showClearChat, usePubSub, networkLibrary, isLoggedIn, accountId, channelId))
                chatReadJob = viewModelScope.launch(Dispatchers.IO) {
                    chatReadIRCSocket?.start()
                }
                if (isLoggedIn && (!gqlToken.isNullOrBlank() || !helixToken.isNullOrBlank())) {
                    chatWriteIRCSocket = ChatWriteIRCSocket(
                        useSSL = useSSL,
                        userLogin = accountLogin,
                        userToken = gqlToken?.takeIf { it.isNotBlank() } ?: helixToken,
                        channelLogin = channelLogin,
                        listener = ChatWriteListener(channelId, showWebSocketDebugInfo)
                    )
                    chatWriteJob = viewModelScope.launch(Dispatchers.IO) {
                        chatWriteIRCSocket?.start()
                    }
                }
            }
        }
        if (usePubSub && !channelId.isNullOrBlank()) {
            val collectPoints = applicationContext.prefs().getBoolean(C.CHAT_POINTS_COLLECT, true)
            val gqlWebClientId = applicationContext.prefs().getString(C.GQL_CLIENT_ID_WEB, "kimne78kx3ncx6brgo4mv6wki5h1ko")
            val gqlWebToken = applicationContext.tokenPrefs().getString(C.GQL_TOKEN_WEB, null)
            val notifyPoints = applicationContext.prefs().getBoolean(C.CHAT_POINTS_NOTIFY, false)
            val showRaids = applicationContext.prefs().getBoolean(C.CHAT_RAIDS_SHOW, true)
            val showPolls = applicationContext.prefs().getBoolean(C.CHAT_POLLS_SHOW, true)
            val showPredictions = applicationContext.prefs().getBoolean(C.CHAT_PREDICTIONS_SHOW, true) ||
                applicationContext.prefs().getBoolean(C.TV_CHAT_PREDICTIONS_POPUP, false)
            hermesWebSocket = HermesWebSocket(
                channelId = channelId,
                userId = accountId,
                gqlClientId = if (enableIntegrity) {
                    gqlHeaders[C.HEADER_CLIENT_ID]
                } else {
                    gqlWebClientId
                },
                gqlToken = if (enableIntegrity) {
                    gqlHeaders[C.HEADER_TOKEN]?.removePrefix("OAuth ")
                } else {
                    if (!gqlWebToken.isNullOrBlank()) {
                        gqlWebToken
                    } else null
                },
                collectPoints = collectPoints,
                showRaids = showRaids,
                showPolls = showPolls,
                showPredictions = showPredictions,
                listener = PubSubListener(channelLogin, collectPoints, notifyPoints, showRaids, showPolls, showPredictions, networkLibrary, gqlHeaders, isLoggedIn, accountId, channelId, enableIntegrity, showWebSocketDebugInfo)
            )
            pubSubJob = hermesWebSocket?.connect(viewModelScope)
        }
        val showNamePaints = applicationContext.prefs().getBoolean(C.CHAT_SHOW_PAINTS, true)
        val showSTVBadges = applicationContext.prefs().getBoolean(C.CHAT_SHOW_STV_BADGES, true)
        val showPersonalEmotes = applicationContext.prefs().getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true)
        val stvLiveUpdates = applicationContext.prefs().getBoolean(C.CHAT_STV_LIVE_UPDATES, true)
        if ((showNamePaints || showSTVBadges || showPersonalEmotes || stvLiveUpdates) && !channelId.isNullOrBlank()) {
            val useWebp = applicationContext.prefs().getBoolean(C.CHAT_USE_WEBP, true)
            stvEventApi = STVEventApiWebSocket(
                channelId = channelId,
                listener = STVEventApiListener(useWebp, showNamePaints, showSTVBadges, showPersonalEmotes, stvLiveUpdates, networkLibrary, isLoggedIn, accountId, channelId, showWebSocketDebugInfo)
            )
            stvEventApiJob = stvEventApi?.connect(viewModelScope)
            if (isLoggedIn && !accountId.isNullOrBlank()) {
                viewModelScope.launch {
                    try {
                        stvUserId = playerRepository.getSTVUser(networkLibrary, accountId).takeIf { !it.isNullOrBlank() }
                    } catch (e: Exception) {

                    }
                }
            }
        }
    }

    fun stopLiveChat() {
        if (hybridReplayActive) {
            hybridReplayActive = false
            hybridReplayVideoId = null
            chatReplayManager?.stop()
            chatReplayManager = null
        }
        synchronized(liveBackupBuffer) { liveBackupBuffer.clear() }
        clearDelayedMessages()
        resetSlowChat()
        autoDelayMs = 0L
        effectiveRoomState.value = RoomState("0", "-1", "0", "0", "0")
        userRoomBadges.value = null
        if (started) {
            started = false
            if (chatReadIRCSocket != null) {
                MainScope().launch(Dispatchers.IO) {
                    chatReadIRCSocket?.disconnect(chatReadJob)
                }
            } else {
                if (chatReadWebSocket != null) {
                    MainScope().launch(Dispatchers.IO) {
                        chatReadWebSocket?.disconnect(chatReadJob)
                    }
                } else {
                    if (eventSub != null) {
                        MainScope().launch(Dispatchers.IO) {
                            eventSub?.disconnect(chatReadJob)
                        }
                    }
                }
            }
            if (chatWriteIRCSocket != null) {
                MainScope().launch(Dispatchers.IO) {
                    chatWriteIRCSocket?.disconnect(chatWriteJob)
                }
            } else {
                if (chatWriteWebSocket != null) {
                    MainScope().launch(Dispatchers.IO) {
                        chatWriteWebSocket?.disconnect(chatWriteJob)
                    }
                }
            }
            if (hermesWebSocket != null) {
                MainScope().launch(Dispatchers.IO) {
                    hermesWebSocket?.disconnect(pubSubJob)
                }
            }
            if (stvEventApi != null) {
                MainScope().launch(Dispatchers.IO) {
                    stvEventApi?.disconnect(stvEventApiJob)
                }
            }
        }
    }

    fun isActive(): Boolean? {
        return chatReadJob?.isActive
    }

    fun disconnect() {
        stopLiveChat()
        usedRaidId = null
        raidClosed = true
        usedPollId = null
        pollClosed = true
        pollSecondsLeft.value = null
        pollTimer?.cancel()
        usedPredictionId = null
        predictionClosed = true
        predictionSecondsLeft.value = null
        predictionTimer?.cancel()
        viewModelScope.launch {
            synchronized(chatMessages) {
                val size = chatMessages.size
                chatMessages.clear()
                size
            }.let {
                removeMessages.emit(it)
            }
            onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.disconnected)))
        }
        if (!hideRaid.value) {
            hideRaid.value = true
        }
        if (!hidePoll.value) {
            hidePoll.value = true
        }
        if (!hidePrediction.value) {
            hidePrediction.value = true
        }
        roomState.value = RoomState("0", "-1", "0", "0", "0")
        autoReconnect = false
    }

    private inner class ChatReadListener(
        private val channelLogin: String,
        private val nameDisplay: String?,
        private val showUserNotice: Boolean,
        private val showClearMsg: Boolean,
        private val showClearChat: Boolean,
        private val usePubSub: Boolean,
        private val networkLibrary: String?,
        private val isLoggedIn: Boolean,
        private val accountId: String?,
        private val channelId: String?,
    ) : ChatReadWebSocket.Listener {
        override suspend fun onConnect() {
            onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.chat_join).format(channelLogin)))
        }

        override suspend fun onChatMessage(message: ChatUtils.IRCMessage, userNotice: Boolean) {
            if (userNotice && isLoggedIn) {
                val msgId = message.tags["msg-id"]
                val isSelfSub = msgId in setOf("sub", "resub", "giftpaidupgrade", "anongiftpaidupgrade", "primepaidupgrade") &&
                    ((!accountId.isNullOrBlank() && message.tags["user-id"] == accountId) ||
                        (!ownUserLogin.isNullOrBlank() && message.tags["login"].equals(ownUserLogin, true)))
                val isSelfGiftRecipient = msgId in setOf("subgift", "anonsubgift") &&
                    ((!accountId.isNullOrBlank() && message.tags["msg-param-recipient-id"] == accountId) ||
                        (!ownUserLogin.isNullOrBlank() && message.tags["msg-param-recipient-user-name"].equals(ownUserLogin, true)))
                if (isSelfSub || isSelfGiftRecipient) {
                    userRoomBadges.value = (userRoomBadges.value ?: emptySet()) + "subscriber"
                }
            }
            if (!userNotice || showUserNotice) {
                val chatMessage = ChatUtils.parseChatMessage(message, if (userNotice) TvChatNotices.build(applicationContext, message.tags) else null)
                if (chatMessage.reply?.message != null) {
                    onMessage(ChatMessage(
                        type = ChatMessage.REPLY_MESSAGE,
                        reply = chatMessage.reply,
                        replyParent = chatMessage,
                    ))
                }
                if (usePubSub && chatMessage.reward != null && !chatMessage.reward.id.isNullOrBlank()) {
                    onRewardMessage(chatMessage, networkLibrary, isLoggedIn, accountId, channelId)
                } else {
                    onChatMessage(chatMessage, networkLibrary, isLoggedIn, accountId, channelId)
                    if (chatMessage.msgId == "unraid") {
                        if (!hideRaid.value) {
                            hideRaid.value = true
                        }
                    }
                }
            }
        }

        override suspend fun onClearMessage(message: ChatUtils.IRCMessage) {
            // the deleted message is marked in place (grey and struck through, or hidden with the option off), no line added
            ChatUtils.parseClearMessage(message).targetMsgId?.let { targetId ->
                markDeleted { it.id == targetId }
            }
        }

        override suspend fun onClearChat(message: ChatUtils.IRCMessage) {
            val clearChat = ChatUtils.parseClearChat(applicationContext, message)
            // timeout / ban: the messages of the user are marked deleted
            clearChat.userLogin?.let { login -> markDeleted { it.userLogin.equals(login, true) } }
            if (showClearChat) {
                onMessage(clearChat)
            }
        }

        override suspend fun onNotice(message: ChatUtils.IRCMessage) {
            if (!isLoggedIn) {
                onMessage(ChatUtils.parseNotice(message))
            }
        }

        override suspend fun onRoomState(message: ChatUtils.IRCMessage) {
            val state = RoomState(
                emote = message.tags["emote-only"],
                followers = message.tags["followers-only"],
                unique = message.tags["r9k"],
                slow = message.tags["slow"],
                subs = message.tags["subs-only"]
            )
            roomState.value = state
            mergeRoomState(state)
        }

        override suspend fun onDisconnect(message: String, fullMsg: String?) {
            onMessage(ChatMessage(
                systemMsg = ContextCompat.getString(applicationContext, R.string.chat_disconnect).format(channelLogin, message),
                fullMsg = fullMsg
            ))
        }
    }

    private inner class ChatWriteListener(
        private val channelId: String?,
        private val showWebSocketDebugInfo: Boolean,
    ) : ChatReadWebSocket.Listener {
        override suspend fun onConnect() {
            if (showWebSocketDebugInfo) {
                onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.websocket_connected).format("Chat write socket")))
            }
        }

        override suspend fun onNotice(message: ChatUtils.IRCMessage) {
            val parsed = ChatUtils.parseNotice(message)
            onMessage(parsed)
            val msgId = message.tags["msg-id"]
            if (TvChatNotices.isRestrictionNoticeId(msgId)) {
                if (msgId == "msg_subsonly" && userRoomBadges.value == null) {
                    userRoomBadges.value = emptySet()
                }
                val followersMin = effectiveRoomState.value.followers?.toIntOrNull()?.takeIf { it > 0 }
                chatRestrictionToast.tryEmit(
                    TvChatNotices.restrictionMessage(applicationContext, msgId, parsed.systemMsg, followersMin)
                )
            }
        }

        override suspend fun onUserState(message: ChatUtils.IRCMessage) {
            val badgeSet = message.tags["badges"]
                ?.split(',')
                ?.mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }
                ?.toMutableSet()
                ?: mutableSetOf()
            if (message.tags["mod"] == "1") badgeSet.add("moderator")
            if (message.tags["subscriber"] == "1") badgeSet.add("subscriber")
            if (!message.tags["vip"].isNullOrBlank() && message.tags["vip"] != "0") badgeSet.add("vip")
            userRoomBadges.value = badgeSet
            val emoteSets = message.tags["emote-sets"]?.split(",")
            if (emoteSets != null && savedEmoteSets != emoteSets) {
                savedEmoteSets = emoteSets
                if (!loadedUserEmotes) {
                    loadEmoteSets(channelId)
                }
            }
        }

        override suspend fun onDisconnect(message: String, fullMsg: String?) {
            if (showWebSocketDebugInfo) {
                onMessage(ChatMessage(
                    systemMsg = ContextCompat.getString(applicationContext, R.string.websocket_disconnected).format("Chat write socket", message),
                    fullMsg = fullMsg
                ))
            }
        }
    }

    private inner class EventSubListener(
        private val helixHeaders: Map<String, String>,
        private val channelLogin: String,
        private val showUserNotice: Boolean,
        private val showClearChat: Boolean,
        private val usePubSub: Boolean,
        private val networkLibrary: String?,
        private val isLoggedIn: Boolean,
        private val accountId: String?,
        private val channelId: String?,
    ) : EventSubWebSocket.Listener {
        override suspend fun onConnect() {
            onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.chat_join).format(channelLogin)))
        }

        override suspend fun onWelcomeMessage(sessionId: String) {
            listOf(
                "channel.chat.clear",
                "channel.chat.message",
                "channel.chat.notification",
                "channel.chat_settings.update",
            ).forEach {
                viewModelScope.launch {
                    try {
                        helixRepository.createEventSubSubscription(networkLibrary, helixHeaders, accountId, channelId, it, sessionId)?.let {
                            onMessage(ChatMessage(systemMsg = it))
                        }
                    } catch (e: Exception) {

                    }
                }
            }
        }

        override suspend fun onChatMessage(event: JSONObject, timestamp: String?) {
            val chatMessage = EventSubUtils.parseChatMessage(event, timestamp)
            if (usePubSub && chatMessage.reward != null && !chatMessage.reward.id.isNullOrBlank()) {
                onRewardMessage(chatMessage, networkLibrary, isLoggedIn, accountId, channelId)
            } else {
                onChatMessage(chatMessage, networkLibrary, isLoggedIn, accountId, channelId)
            }
        }

        override suspend fun onUserNotice(event: JSONObject, timestamp: String?) {
            if (isLoggedIn && !accountId.isNullOrBlank()) {
                val noticeType = event.optString("notice_type")
                val isSelfSub = noticeType in setOf("sub", "resub", "gift_paid_upgrade", "prime_paid_upgrade") &&
                    event.optString("chatter_user_id") == accountId
                val isSelfGift = noticeType == "sub_gift" &&
                    event.optJSONObject("sub_gift")?.optString("recipient_user_id") == accountId
                if (isSelfSub || isSelfGift) {
                    userRoomBadges.value = (userRoomBadges.value ?: emptySet()) + "subscriber"
                }
            }
            if (showUserNotice) {
                onChatMessage(EventSubUtils.parseUserNotice(event, timestamp, TvChatNotices.build(applicationContext, event)), networkLibrary, isLoggedIn, accountId, channelId)
            }
        }

        override suspend fun onClearChat(event: JSONObject, timestamp: String?) {
            if (showClearChat) {
                onMessage(EventSubUtils.parseClearChat(applicationContext, event, timestamp))
            }
        }

        override suspend fun onRoomState(event: JSONObject, timestamp: String?) {
            val state = EventSubUtils.parseRoomState(event)
            roomState.value = state
            mergeRoomState(state)
        }

        override suspend fun onDisconnect(message: String, fullMsg: String?) {
            onMessage(ChatMessage(
                systemMsg = ContextCompat.getString(applicationContext, R.string.chat_disconnect).format(channelLogin, message),
                fullMsg = fullMsg
            ))
        }
    }

    private inner class PubSubListener(
        private val channelLogin: String,
        private val collectPoints: Boolean,
        private val notifyPoints: Boolean,
        private val showRaids: Boolean,
        private val showPolls: Boolean,
        private val showPredictions: Boolean,
        private val networkLibrary: String?,
        private val gqlHeaders: Map<String, String>,
        private val isLoggedIn: Boolean,
        private val accountId: String?,
        private val channelId: String?,
        private val enableIntegrity: Boolean,
        private val showWebSocketDebugInfo: Boolean,
    ) : HermesWebSocket.Listener {
        override suspend fun onConnect() {
            if (showWebSocketDebugInfo) {
                onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.websocket_connected).format("PubSub")))
            }
        }

        override suspend fun onPlaybackMessage(message: JSONObject) {
            val playbackMessage = PubSubUtils.parsePlaybackMessage(message)
            if (playbackMessage != null) {
                playbackMessage.live?.let {
                    if (it) {
                        onMessage(ChatMessage(
                            type = ChatMessage.NOTICE_MESSAGE,
                            systemMsg = ContextCompat.getString(applicationContext, R.string.stream_live).format(channelLogin),
                        ))
                    } else {
                        onMessage(ChatMessage(
                            type = ChatMessage.NOTICE_MESSAGE,
                            systemMsg = ContextCompat.getString(applicationContext, R.string.stream_offline).format(channelLogin),
                        ))
                    }
                }
                _playbackMessage.value = playbackMessage
            }
        }

        override suspend fun onStreamInfo(message: JSONObject) {
            _streamInfo.value = PubSubUtils.parseStreamInfo(message)
        }

        override suspend fun onRewardMessage(message: JSONObject) {
            val chatMessage = PubSubUtils.parseRewardMessage(message)
            if (!chatMessage.message.isNullOrBlank()) {
                onRewardMessage(chatMessage, networkLibrary, isLoggedIn, accountId, channelId)
            } else {
                onChatMessage(chatMessage, networkLibrary, isLoggedIn, accountId, channelId)
            }
        }

        override suspend fun onPointsEarned(message: JSONObject) {
            if (notifyPoints) {
                val result = PubSubUtils.parsePointsEarned(message)
                val points = result.first
                val messageChannelId = result.second
                if (channelId == messageChannelId) {
                    onMessage(ChatMessage(
                        type = ChatMessage.NOTICE_MESSAGE,
                        systemMsg = ContextCompat.getString(applicationContext, R.string.points_earned).format(points.pointsGained),
                        timestamp = points.timestamp,
                        fullMsg = points.fullMsg
                    ))
                }
            }
        }

        override suspend fun onClaimAvailable() {
            if (collectPoints) {
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        try {
                            val response = graphQLRepository.loadChannelPointsContext(networkLibrary, gqlHeaders, channelLogin)
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return@launch
                                }
                            }
                            response.data?.community?.channel?.self?.communityPoints?.availableClaim?.id?.let { claimId ->
                                val response = graphQLRepository.loadClaimPoints(networkLibrary, gqlHeaders, channelId, claimId)
                                if (enableIntegrity) {
                                    response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                        integrity.emit("refresh")
                                        return@launch
                                    }
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                }
            }
        }

        override suspend fun onMinuteWatched() {
            if (!streamId.isNullOrBlank()) {
                try {
                    playerRepository.sendMinuteWatched(networkLibrary, accountId, streamId, channelId, channelLogin)
                } catch (e: Exception) {

                }
            }
        }

        override suspend fun onRaidUpdate(message: JSONObject, openStream: Boolean) {
            if (showRaids) {
                PubSubUtils.onRaidUpdate(message, openStream)?.let {
                    if (it.raidId != usedRaidId) {
                        usedRaidId = it.raidId
                        raidClosed = false
                        if (collectPoints && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            viewModelScope.launch {
                                try {
                                    val response = graphQLRepository.loadJoinRaid(networkLibrary, gqlHeaders, it.raidId)
                                    if (enableIntegrity) {
                                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                            integrity.emit("refresh")
                                            return@launch
                                        }
                                    }
                                } catch (e: Exception) {

                                }
                            }
                        }
                    }
                    raid.value = it
                }
            }
        }

        override suspend fun onPollUpdate(message: JSONObject) {
            if (showPolls) {
                PubSubUtils.onPollUpdate(message)?.let {
                    if (it.id != usedPollId) {
                        usedPollId = it.id
                        pollClosed = false
                        pollTimeoutJob?.cancel()
                        if (it.remainingMilliseconds != null) {
                            val secondsLeft = it.remainingMilliseconds / 1000
                            if (secondsLeft > 0) {
                                pollSecondsLeft.value = secondsLeft
                                pollTimer?.cancel()
                                pollTimer = Timer().apply {
                                    scheduleAtFixedRate(1000, 1000) {
                                        val seconds = pollSecondsLeft.value
                                        if (seconds != null) {
                                            pollSecondsLeft.value = seconds - 1
                                            if (seconds <= 1) {
                                                this@apply.cancel()
                                            }
                                        } else {
                                            this@apply.cancel()
                                        }
                                    }
                                }
                            }
                        }
                    } else if (it.status == "COMPLETED" || it.status == "TERMINATED") {
                        pollClosed = false
                    }
                    poll.value = it
                }
            }
        }

        override suspend fun onPredictionUpdate(message: JSONObject) {
            if (showPredictions) {
                PubSubUtils.onPredictionUpdate(message)?.let {
                    if (it.id != usedPredictionId) {
                        usedPredictionId = it.id
                        predictionClosed = false
                        predictionTimeoutJob?.cancel()
                        if (it.createdAt != null && it.predictionWindowSeconds != null) {
                            val secondsLeft = ((((it.createdAt + (it.predictionWindowSeconds * 1000)) - System.currentTimeMillis())) / 1000).toInt()
                            if (secondsLeft > 0) {
                                predictionSecondsLeft.value = secondsLeft
                                predictionTimer?.cancel()
                                predictionTimer = Timer().apply {
                                    scheduleAtFixedRate(1000, 1000) {
                                        val seconds = predictionSecondsLeft.value
                                        if (seconds != null) {
                                            predictionSecondsLeft.value = seconds - 1
                                            if (seconds <= 1) {
                                                this@apply.cancel()
                                            }
                                        } else {
                                            this@apply.cancel()
                                        }
                                    }
                                }
                            }
                        }
                    } else if (it.status == "LOCKED" || it.status == "CANCEL_PENDING" || it.status == "RESOLVE_PENDING") {
                        predictionClosed = false
                    }
                    prediction.value = it
                }
            }
        }

        override suspend fun onDisconnect(message: String, fullMsg: String?) {
            if (showWebSocketDebugInfo) {
                onMessage(ChatMessage(
                    systemMsg = ContextCompat.getString(applicationContext, R.string.websocket_disconnected).format("PubSub", message),
                    fullMsg = fullMsg
                ))
            }
        }
    }

    private inner class STVEventApiListener(
        private val useWebp: Boolean,
        private val showNamePaints: Boolean,
        private val showSTVBadges: Boolean,
        private val showPersonalEmotes: Boolean,
        private val stvLiveUpdates: Boolean,
        private val networkLibrary: String?,
        private val isLoggedIn: Boolean,
        private val accountId: String?,
        private val channelId: String?,
        private val showWebSocketDebugInfo: Boolean,
    ) : STVEventApiWebSocket.Listener {
        override suspend fun onConnect() {
            if (showWebSocketDebugInfo) {
                onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.websocket_connected).format("7TV Event API")))
            }
        }

        override suspend fun onEmoteSetUpdate(body: JSONObject) {
            val result = STVEventApiUtils.parseEmoteSetUpdate(body, useWebp, channelSTVEmoteSetId)
            if (result != null) {
                if (result.channelSet) {
                    if (stvLiveUpdates) {
                        val removedEmotes = (result.removed + result.updated.map { it.first }).map { it.name }
                        val newEmotes = result.added + result.updated.map { it.second }
                        synchronized(thirdPartyEmotes) {
                            thirdPartyEmotes.removeAll { it.name in removedEmotes }
                            thirdPartyEmotes.addAll(newEmotes)
                        }
                        if (!reloadMessages.value) {
                            reloadMessages.value = true
                        }
                        viewModelScope.launch {
                            thirdPartyEmotesUpdated.emit(Unit)
                        }
                        synchronized(allEmotes) {
                            allEmotes.removeAll { it in removedEmotes }
                            allEmotes.addAll(newEmotes.filter { it.name !in allEmotes }.mapNotNull { it.name })
                        }
                    }
                } else {
                    if (showPersonalEmotes) {
                        val removedEmotes = (result.removed + result.updated.map { it.first }).map { it.name }
                        synchronized(personalEmoteSets) {
                            val existingSet = personalEmoteSets[result.setId]?.filter { it.name !in removedEmotes } ?: emptyList()
                            personalEmoteSets.remove(result.setId)
                            val set = existingSet + result.added + result.updated.map { it.second }
                            personalEmoteSets[result.setId] = set
                        }
                        if (isLoggedIn && !accountId.isNullOrBlank() && result.setId == userSTVEmoteSetId) {
                            viewModelScope.launch {
                                thirdPartyEmotesUpdated.emit(Unit)
                            }
                        }
                    }
                }
            }
        }

        override suspend fun onCosmetic(body: JSONObject) {
            val result = STVEventApiUtils.parseCosmetic(body, useWebp)
            if (result != null) {
                when (result) {
                    is STVEventApiUtils.Cosmetic.Paint -> {
                        if (showNamePaints) {
                            synchronized(namePaints) {
                                namePaints.find { it.id == result.paint.id }?.let { namePaints.remove(it) }
                                namePaints.add(result.paint)
                            }
                        }
                    }
                    is STVEventApiUtils.Cosmetic.Badge -> {
                        if (showSTVBadges) {
                            synchronized(stvBadges) {
                                stvBadges.find { it.id == result.badge.id }?.let { stvBadges.remove(it) }
                                stvBadges.add(result.badge)
                            }
                        }
                    }
                }
            }
        }

        override suspend fun onEntitlement(body: JSONObject) {
            val result = STVEventApiUtils.parseEntitlement(body)
            if (result != null) {
                when (result) {
                    is STVEventApiUtils.Entitlement.Paint -> {
                        if (showNamePaints) {
                            synchronized(stvUsers) {
                                val user = stvUsers.find { it.userId == result.userId }
                                if (user != null) {
                                    if (user.paintId != result.paintId) {
                                        user.paintId = result.paintId
                                        true
                                    } else false
                                } else {
                                    stvUsers.add(STVUser(
                                        userId = result.userId,
                                        paintId = result.paintId
                                    ))
                                    true
                                }
                            }.let {
                                if (it) {
                                    updateUserMessages.emit(result.userId)
                                }
                            }
                        }
                    }
                    is STVEventApiUtils.Entitlement.Badge -> {
                        if (showSTVBadges) {
                            synchronized(stvUsers) {
                                val user = stvUsers.find { it.userId == result.userId }
                                if (user != null) {
                                    if (user.badgeId != result.badgeId) {
                                        user.badgeId = result.badgeId
                                        true
                                    } else false
                                } else {
                                    stvUsers.add(STVUser(
                                        userId = result.userId,
                                        badgeId = result.badgeId
                                    ))
                                    true
                                }
                            }.let {
                                if (it) {
                                    updateUserMessages.emit(result.userId)
                                }
                            }
                        }
                    }
                    is STVEventApiUtils.Entitlement.EmoteSet -> {
                        if (showPersonalEmotes) {
                            synchronized(stvUsers) {
                                val user = stvUsers.find { it.userId == result.userId }
                                if (user != null) {
                                    if (user.emoteSetId != result.setId) {
                                        user.emoteSetId = result.setId
                                        true
                                    } else false
                                } else {
                                    stvUsers.add(STVUser(
                                        userId = result.userId,
                                        emoteSetId = result.setId
                                    ))
                                    true
                                }
                            }.let {
                                if (it) {
                                    updateUserMessages.emit(result.userId)
                                }
                            }
                            if (isLoggedIn && !accountId.isNullOrBlank() && result.userId == accountId) {
                                userSTVEmoteSetId = result.setId
                                viewModelScope.launch {
                                    thirdPartyEmotesUpdated.emit(Unit)
                                }
                            }
                        }
                    }
                }
            }
        }

        override suspend fun onUpdatePresence(sessionId: String) {
            onUpdatePresence(networkLibrary, sessionId, channelId, true)
        }

        override suspend fun onDisconnect(message: String, fullMsg: String?) {
            if (showWebSocketDebugInfo) {
                onMessage(ChatMessage(
                    systemMsg = ContextCompat.getString(applicationContext, R.string.websocket_disconnected).format("7TV Event API", message),
                    fullMsg = fullMsg
                ))
            }
        }
    }

    private suspend fun onChatMessage(message: ChatMessage, networkLibrary: String?, isLoggedIn: Boolean, accountId: String?, channelId: String?) {
        onMessage(message)
        addChatter(message.userName)
        if (isLoggedIn && !accountId.isNullOrBlank() && message.userId == accountId) {
            message.badges?.let { badges ->
                userRoomBadges.value = badges.mapNotNull { it.setId.takeIf(String::isNotBlank) }.toSet()
            }
            onUpdatePresence(networkLibrary, null, channelId, false)
        }
    }

    private fun addChatter(displayName: String?) {
        if (displayName != null && !chatters.containsKey(displayName)) {
            val chatter = Chatter(displayName)
            chatters[displayName] = chatter
            synchronized(autoCompleteList) {
                autoCompleteList.add(chatter)
            }
        }
    }

    private fun onUpdatePresence(networkLibrary: String?, sessionId: String?, channelId: String?, self: Boolean) {
        stvUserId?.let { stvUserId ->
            if (stvUserId.isNotBlank() && !channelId.isNullOrBlank() && (self && !sessionId.isNullOrBlank() || !self) &&
                stvLastPresenceUpdate?.let { (System.currentTimeMillis() - it) > 10000 } != false) {
                stvLastPresenceUpdate = System.currentTimeMillis()
                viewModelScope.launch {
                    try {
                        playerRepository.sendSTVPresence(networkLibrary, stvUserId, channelId, sessionId, self)
                    } catch (e: Exception) {

                    }
                }
            }
        }
    }

    private suspend fun onRewardMessage(message: ChatMessage, networkLibrary: String?, isLoggedIn: Boolean, accountId: String?, channelId: String?) {
        if (message.reward?.id != null) {
            synchronized(rewardList) {
                val item = rewardList.find { it.reward?.id == message.reward.id && it.userId == message.userId }
                if (item != null) {
                    rewardList.remove(item)
                    item
                } else {
                    rewardList.add(message)
                    null
                }
            }.let { item ->
                if (item != null) {
                    onChatMessage(ChatMessage(
                        type = ChatMessage.USER_MESSAGE,
                        id = message.id ?: item.id,
                        userId = message.userId ?: item.userId,
                        userLogin = message.userLogin ?: item.userLogin,
                        userName = message.userName ?: item.userName,
                        message = message.message ?: item.message,
                        color = message.color ?: item.color,
                        emotes = message.emotes ?: item.emotes,
                        badges = message.badges ?: item.badges,
                        isAction = message.isAction || item.isAction,
                        isFirst = message.isFirst || item.isFirst,
                        bits = message.bits ?: item.bits,
                        systemMsg = message.systemMsg ?: item.systemMsg,
                        msgId = message.msgId ?: item.msgId,
                        reward = ChannelPointReward(
                            id = message.reward.id,
                            title = message.reward.title ?: item.reward?.title,
                            cost = message.reward.cost ?: item.reward?.cost,
                            url1x = message.reward.url1x ?: item.reward?.url1x,
                            url2x = message.reward.url2x ?: item.reward?.url2x,
                            url4x = message.reward.url4x ?: item.reward?.url4x,
                        ),
                        timestamp = message.timestamp ?: item.timestamp,
                        fullMsg = message.fullMsg ?: item.fullMsg,
                    ), networkLibrary, isLoggedIn, accountId, channelId)
                }
            }
        } else {
            onChatMessage(message, networkLibrary, isLoggedIn, accountId, channelId)
        }
    }

    private fun loadEmoteSets(channelId: String?) {
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        if (!savedEmoteSets.isNullOrEmpty() && !helixHeaders[C.HEADER_CLIENT_ID].isNullOrBlank() && !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                    val animateGifs =  applicationContext.prefs().getBoolean(C.ANIMATED_EMOTES, true)
                    val emotes = mutableListOf<TwitchEmote>()
                    savedEmoteSets?.chunked(25)?.forEach { list ->
                        playerRepository.loadEmotesFromSet(networkLibrary, helixHeaders, list, animateGifs).let { emotes.addAll(it) }
                    }
                    if (emotes.isNotEmpty()) {
                        val sorted = emotes.sortedByDescending { it.setId }
                        savedUserEmotes = sorted
                        synchronized(userEmotes) {
                            userEmotes.clear()
                            userEmotes.addAll(
                                sorted.sortedByDescending { it.ownerId == channelId }.map {
                                    Emote(
                                        name = it.name,
                                        url1x = it.url1x,
                                        url2x = it.url2x,
                                        url3x = it.url3x,
                                        url4x = it.url4x,
                                        format = it.format
                                    )
                                }
                            )
                        }
                        userEmotesUpdated.emit(Unit)
                        synchronized(autoCompleteList) {
                            autoCompleteList.addAll(sorted.map {
                                Emote(
                                    name = it.name,
                                    url1x = it.url1x,
                                    url2x = it.url2x,
                                    url3x = it.url3x,
                                    url4x = it.url4x,
                                    format = it.format
                                )
                            }.filter { it !in autoCompleteList })
                        }
                        synchronized(allEmotes) {
                            allEmotes.addAll(sorted.filter { it.name !in allEmotes }.mapNotNull { it.name })
                        }
                    }
                } catch (e: Exception) {

                }
            }
        }
    }

    fun startPollTimeout(hide: () -> Unit) {
        pollTimeoutJob?.cancel()
        pollTimeoutJob = viewModelScope.launch {
            delay(20.seconds)
            hide()
        }
    }

    fun startPredictionTimeout(hide: () -> Unit) {
        predictionTimeoutJob?.cancel()
        predictionTimeoutJob = viewModelScope.launch {
            delay(20.seconds)
            hide()
        }
    }

    fun send(message: CharSequence, replyId: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, accountId: String?, channelId: String?, channelLogin: String?, useApiCommands: Boolean, useApiChatMessages: Boolean, enableIntegrity: Boolean) {
        if (!message.startsWith("/")) {
            // statistics of the TV home
            com.github.andreyasadchy.xtra.ui.tv.TvStats.addMessage(applicationContext)
        }
        if (useApiCommands) {
            viewModelScope.launch {
                try {
                    if (replyId == null && message.startsWith("/")) {
                        sendCommand(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, channelLogin, useApiChatMessages, enableIntegrity)
                    } else {
                        sendMessage(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, useApiChatMessages, enableIntegrity, replyId)
                    }
                } catch (e: Exception) {

                }
            }
        } else {
            if (replyId == null && (message == "/dc" || message == "/disconnect")) {
                disconnect()
            } else {
                viewModelScope.launch {
                    try {
                        sendMessage(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, useApiChatMessages, enableIntegrity, replyId)
                    } catch (e: Exception) {

                    }
                }
            }
        }
    }

    private suspend fun sendMessage(message: CharSequence, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, accountId: String?, channelId: String?, useApiChatMessages: Boolean, enableIntegrity: Boolean, replyId: String? = null) {
        if (useApiChatMessages) {
            if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                val response = graphQLRepository.sendMessage(networkLibrary, gqlHeaders, channelId, message.toString(), replyId)
                if (enableIntegrity) {
                    response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                        integrity.emit("refresh")
                        return
                    }
                }
                val dropReason = response.data?.sendChatMessage?.dropReason
                val dropMessage = response.data?.sendChatMessage?.dropReasonMessage
                when {
                    !dropReason.isNullOrBlank() -> {
                        if (dropReason == "SUBSCRIBERS_ONLY" && userRoomBadges.value == null) {
                            userRoomBadges.value = emptySet()
                        }
                        val followersMin = effectiveRoomState.value.followers?.toIntOrNull()?.takeIf { it > 0 }
                        val toastMsg = TvChatNotices.restrictionMessage(applicationContext, dropReason, dropMessage, followersMin)
                        chatRestrictionToast.emit(toastMsg)
                        dropMessage?.takeIf { it.isNotBlank() } ?: toastMsg
                    }
                    !response.errors.isNullOrEmpty() -> {
                        val errMsg = response.errors
                            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
                            .joinToString("; ")
                            .ifBlank { applicationContext.getString(R.string.tv_chat_restricted_generic) }
                        chatRestrictionToast.emit(TvChatNotices.restrictionMessage(applicationContext, null, errMsg))
                        errMsg
                    }
                    else -> null
                }
            } else {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val raw = helixRepository.sendMessage(networkLibrary, helixHeaders, accountId, channelId, message.toString(), replyId)
                    when {
                        raw != null && raw.startsWith("DROP:") -> {
                            val payload = raw.removePrefix("DROP:")
                            val code = payload.substringBefore('|')
                            val msg = payload.substringAfter('|', "")
                            if (code == "msg_subsonly" && userRoomBadges.value == null) {
                                userRoomBadges.value = emptySet()
                            }
                            val followersMin = effectiveRoomState.value.followers?.toIntOrNull()?.takeIf { it > 0 }
                            val toastMsg = TvChatNotices.restrictionMessage(applicationContext, code, msg, followersMin)
                            chatRestrictionToast.emit(toastMsg)
                            msg.ifBlank { toastMsg }
                        }
                        !raw.isNullOrBlank() -> {
                            chatRestrictionToast.emit(TvChatNotices.restrictionMessage(applicationContext, null, raw))
                            raw
                        }
                        else -> null
                    }
                } else null
            }?.let {
                onMessage(ChatMessage(systemMsg = it))
            }
        } else {
            chatWriteIRCSocket?.send(message, replyId) ?: chatWriteWebSocket?.send(message, replyId)
        }
        val usedEmotes = hashSetOf<RecentEmote>()
        val currentTime = System.currentTimeMillis()
        synchronized(allEmotes) {
            message.split(' ').forEach { word ->
                allEmotes.find { it == word }?.let { usedEmotes.add(RecentEmote(word, currentTime)) }
            }
        }
        if (usedEmotes.isNotEmpty()) {
            playerRepository.insertRecentEmotes(usedEmotes)
        }
    }

    private suspend fun sendCommand(message: CharSequence, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, accountId: String?, channelId: String?, channelLogin: String?, useApiChatMessages: Boolean, enableIntegrity: Boolean) {
        val command = message.toString().substringBefore(" ")
        when {
            command.startsWith("/announce", true) -> {
                val splits = message.split(" ", limit = 2)
                val color = splits.getOrNull(0)?.lowercase()?.substringAfter("/announce", "")?.takeIf { it.isNotBlank() }
                val message = splits.getOrNull(1)?.takeIf { it.isNotBlank() }
                if (message != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.sendAnnouncement(networkLibrary, gqlHeaders, channelId, message, color).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return
                                }
                            }
                        }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            helixRepository.sendAnnouncement(networkLibrary, helixHeaders, channelId, accountId, message, color)
                        } else null
                    }?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                }
            }
            command.equals("/ban", true) -> {
                val splits = message.split(" ", limit = 3)
                val targetLogin = splits.getOrNull(1)?.removePrefix("@")?.takeIf { it.isNotBlank() }
                val reason = splits.getOrNull(2)?.takeIf { it.isNotBlank() }
                if (targetLogin != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.banUser(networkLibrary, gqlHeaders, channelId, targetLogin, reason = reason).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return
                                }
                            }
                        }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            val targetId = helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = listOf(targetLogin)
                            ).data.firstOrNull()?.id
                            helixRepository.banUser(networkLibrary, helixHeaders, channelId, accountId, targetId, reason = reason)
                        } else null
                    }?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                }
            }
            command.equals("/unban", true) -> {
                val splits = message.split(" ")
                val targetLogin = splits.getOrNull(1)?.removePrefix("@")?.takeIf { it.isNotBlank() }
                if (targetLogin != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.unbanUser(networkLibrary, gqlHeaders, channelId, targetLogin).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return
                                }
                            }
                        }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            val targetId = helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = listOf(targetLogin)
                            ).data.firstOrNull()?.id
                            helixRepository.unbanUser(networkLibrary, helixHeaders, channelId, accountId, targetId)
                        } else null
                    }?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                }
            }
            command.equals("/clear", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    helixRepository.deleteMessages(networkLibrary, helixHeaders, channelId, accountId)?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                } else {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        sendMessage(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, useApiChatMessages, enableIntegrity)
                    }
                }
            }
            command.equals("/color", true) -> {
                val splits = message.split(" ")
                val color = splits.getOrNull(1)?.takeIf { it.isNotBlank() }
                if (color != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.updateChatColor(networkLibrary, gqlHeaders, color).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return
                                }
                            }
                        }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            helixRepository.updateChatColor(networkLibrary, helixHeaders, accountId, color)
                        } else null
                    }
                } else {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        helixRepository.getChatColor(networkLibrary, helixHeaders, accountId)
                    } else null
                }?.let {
                    onMessage(ChatMessage(systemMsg = it))
                }
            }
            command.equals("/commercial", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val splits = message.split(" ")
                    val length = splits.getOrNull(1)?.takeIf { it.isNotBlank() }
                    if (length != null) {
                        helixRepository.startCommercial(networkLibrary, helixHeaders, channelId, length)?.let {
                            onMessage(ChatMessage(systemMsg = it))
                        }
                    }
                } else {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        sendMessage(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, useApiChatMessages, enableIntegrity)
                    }
                }
            }
            command.equals("/delete", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val splits = message.split(" ")
                    val messageId = splits.getOrNull(1)?.takeIf { it.isNotBlank() }
                    if (messageId != null) {
                        helixRepository.deleteMessages(networkLibrary, helixHeaders, channelId, accountId, messageId)?.let {
                            onMessage(ChatMessage(systemMsg = it))
                        }
                    }
                } else {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        sendMessage(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, useApiChatMessages, enableIntegrity)
                    }
                }
            }
            command.equals("/disconnect", true) -> disconnect()
            command.equals("/emoteonly", true) -> {
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    graphQLRepository.updateChatSettings(networkLibrary, gqlHeaders, channelId, emote = true).also { response ->
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refresh")
                                return
                            }
                        }
                    }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                } else {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        helixRepository.updateChatSettings(networkLibrary, helixHeaders, channelId, accountId, emote = true)
                    } else null
                }?.let {
                    onMessage(ChatMessage(systemMsg = it))
                }
            }
            command.equals("/emoteonlyoff", true) -> {
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    graphQLRepository.updateChatSettings(networkLibrary, gqlHeaders, channelId, emote = false).also { response ->
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refresh")
                                return
                            }
                        }
                    }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                } else {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        helixRepository.updateChatSettings(networkLibrary, helixHeaders, channelId, accountId, emote = false)
                    } else null
                }?.let {
                    onMessage(ChatMessage(systemMsg = it))
                }
            }
            command.equals("/followers", true) -> {
                val splits = message.split(" ")
                val duration = splits.getOrNull(1)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    graphQLRepository.setFollowersOnlyMode(networkLibrary, gqlHeaders, channelId, duration ?: 0).also { response ->
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refresh")
                                return
                            }
                        }
                    }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                } else {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        helixRepository.updateChatSettings(networkLibrary, helixHeaders, channelId, accountId,
                            followers = true,
                            followersDuration = duration
                        )
                    } else null
                }?.let {
                    onMessage(ChatMessage(systemMsg = it))
                }
            }
            command.equals("/followersoff", true) -> {
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    graphQLRepository.setFollowersOnlyMode(networkLibrary, gqlHeaders, channelId, -1).also { response ->
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refresh")
                                return
                            }
                        }
                    }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                } else {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        helixRepository.updateChatSettings(networkLibrary, helixHeaders, channelId, accountId, followers = false)
                    } else null
                }?.let {
                    onMessage(ChatMessage(systemMsg = it))
                }
            }
            command.equals("/marker", true) -> {
                val splits = message.split(" ", limit = 2)
                val description = splits.getOrNull(1)?.takeIf { it.isNotBlank() }
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    graphQLRepository.createStreamMarker(networkLibrary, gqlHeaders, channelLogin).also { response ->
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refresh")
                                return
                            }
                        }
                    }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                } else {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        helixRepository.createStreamMarker(networkLibrary, helixHeaders, channelId, description)
                    } else null
                }?.let {
                    onMessage(ChatMessage(systemMsg = it))
                }
            }
            command.equals("/mod", true) -> {
                val splits = message.split(" ")
                val targetLogin = splits.getOrNull(1)?.removePrefix("@")?.takeIf { it.isNotBlank() }
                if (targetLogin != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.addModerator(networkLibrary, gqlHeaders, channelId, targetLogin).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return
                                }
                            }
                        }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            val targetId = helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = listOf(targetLogin)
                            ).data.firstOrNull()?.id
                            helixRepository.addModerator(networkLibrary, helixHeaders, channelId, targetId)
                        } else null
                    }?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                }
            }
            command.equals("/unmod", true) -> {
                val splits = message.split(" ")
                val targetLogin = splits.getOrNull(1)?.removePrefix("@")?.takeIf { it.isNotBlank() }
                if (targetLogin != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.removeModerator(networkLibrary, gqlHeaders, channelId, targetLogin).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return
                                }
                            }
                        }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            val targetId = helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = listOf(targetLogin)
                            ).data.firstOrNull()?.id
                            helixRepository.removeModerator(networkLibrary, helixHeaders, channelId, targetId)
                        } else null
                    }?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                }
            }
            command.equals("/mods", true) -> {
                graphQLRepository.getModerators(networkLibrary, gqlHeaders, channelLogin).also { response ->
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refresh")
                            return
                        }
                    }
                }.let {
                    onMessage(ChatMessage(systemMsg = it.data?.user?.mods?.edges?.map { it.node.login }?.toString() ?: it.toString()))
                }
            }
            command.equals("/raid", true) -> {
                val splits = message.split(" ")
                val targetLogin = splits.getOrNull(1)?.removePrefix("@")?.takeIf { it.isNotBlank() }
                if (targetLogin != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        val targetId = try {
                            graphQLRepository.loadQueryUser(networkLibrary, gqlHeaders, login = targetLogin).also { response ->
                                if (enableIntegrity) {
                                    response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                        integrity.emit("refresh")
                                        return
                                    }
                                }
                            }.data!!.user?.id
                        } catch (e: Exception) {
                            if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                                helixRepository.getUsers(
                                    networkLibrary = networkLibrary,
                                    headers = helixHeaders,
                                    logins = listOf(targetLogin)
                                ).data.firstOrNull()?.id
                            } else null
                        }
                        if (targetId != null) {
                            graphQLRepository.startRaid(networkLibrary, gqlHeaders, channelId, targetId).also { response ->
                                if (enableIntegrity) {
                                    response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                        integrity.emit("refresh")
                                        return
                                    }
                                }
                            }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                        } else null
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            val targetId = helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = listOf(targetLogin)
                            ).data.firstOrNull()?.id
                            helixRepository.startRaid(networkLibrary, helixHeaders, channelId, targetId)
                        } else null
                    }?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                }
            }
            command.equals("/unraid", true) -> {
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    graphQLRepository.cancelRaid(networkLibrary, gqlHeaders, channelId).also { response ->
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refresh")
                                return
                            }
                        }
                    }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                } else {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        helixRepository.cancelRaid(networkLibrary, helixHeaders, channelId)
                    } else null
                }?.let {
                    onMessage(ChatMessage(systemMsg = it))
                }
            }
            command.equals("/slow", true) -> {
                val splits = message.split(" ")
                val duration = if (splits.size >= 2) splits[1].toIntOrNull() else null
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    graphQLRepository.setSlowMode(networkLibrary, gqlHeaders, channelId, duration ?: 30).also { response ->
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refresh")
                                return
                            }
                        }
                    }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                } else {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        helixRepository.updateChatSettings(networkLibrary, helixHeaders, channelId, accountId,
                            slow = true,
                            slowDuration = duration
                        )
                    } else null
                }?.let {
                    onMessage(ChatMessage(systemMsg = it))
                }
            }
            command.equals("/slowoff", true) -> {
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    graphQLRepository.setSlowMode(networkLibrary, gqlHeaders, channelId, 0).also { response ->
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refresh")
                                return
                            }
                        }
                    }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                } else {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        helixRepository.updateChatSettings(networkLibrary, helixHeaders, channelId, accountId, slow = false)
                    } else null
                }?.let {
                    onMessage(ChatMessage(systemMsg = it))
                }
            }
            command.equals("/subscribers", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    helixRepository.updateChatSettings(networkLibrary, helixHeaders, channelId, accountId, subs = true)?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                } else {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        sendMessage(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, useApiChatMessages, enableIntegrity)
                    }
                }
            }
            command.equals("/subscribersoff", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    helixRepository.updateChatSettings(networkLibrary, helixHeaders, channelId, accountId, subs = false)?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                } else {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        sendMessage(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, useApiChatMessages, enableIntegrity)
                    }
                }
            }
            command.equals("/timeout", true) -> {
                val splits = message.split(" ", limit = 4)
                val targetLogin = splits.getOrNull(1)?.removePrefix("@")?.takeIf { it.isNotBlank() }
                val duration = splits.getOrNull(2)?.takeIf { it.isNotBlank() }
                val reason = splits.getOrNull(3)?.takeIf { it.isNotBlank() }
                if (targetLogin != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.banUser(networkLibrary, gqlHeaders, channelId, targetLogin, duration ?: "10m", reason).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return
                                }
                            }
                        }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            val targetId = helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = listOf(targetLogin)
                            ).data.firstOrNull()?.id
                            helixRepository.banUser(networkLibrary, helixHeaders, channelId, accountId, targetId, duration ?: "600", reason)
                        } else null
                    }?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                }
            }
            command.equals("/untimeout", true) -> {
                val splits = message.split(" ")
                val targetLogin = splits.getOrNull(1)?.removePrefix("@")?.takeIf { it.isNotBlank() }
                if (targetLogin != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.unbanUser(networkLibrary, gqlHeaders, channelId, targetLogin).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return
                                }
                            }
                        }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            val targetId = helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = listOf(targetLogin)
                            ).data.firstOrNull()?.id
                            helixRepository.unbanUser(networkLibrary, helixHeaders, channelId, accountId, targetId)
                        } else null
                    }?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                }
            }
            command.equals("/uniquechat", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    helixRepository.updateChatSettings(networkLibrary, helixHeaders, channelId, accountId, unique = true)?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                } else {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        sendMessage(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, useApiChatMessages, enableIntegrity)
                    }
                }
            }
            command.equals("/uniquechatoff", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    helixRepository.updateChatSettings(networkLibrary, helixHeaders, channelId, accountId, unique = false)?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                } else {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        sendMessage(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, useApiChatMessages, enableIntegrity)
                    }
                }
            }
            command.equals("/vip", true) -> {
                val splits = message.split(" ")
                val targetLogin = splits.getOrNull(1)?.removePrefix("@")?.takeIf { it.isNotBlank() }
                if (targetLogin != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.addVip(networkLibrary, gqlHeaders, channelId, targetLogin).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return
                                }
                            }
                        }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            val targetId = helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = listOf(targetLogin)
                            ).data.firstOrNull()?.id
                            helixRepository.addVip(networkLibrary, helixHeaders, channelId, targetId)
                        } else null
                    }?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                }
            }
            command.equals("/unvip", true) -> {
                val splits = message.split(" ")
                val targetLogin = splits.getOrNull(1)?.removePrefix("@")?.takeIf { it.isNotBlank() }
                if (targetLogin != null) {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.removeVip(networkLibrary, gqlHeaders, channelId, targetLogin).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("refresh")
                                    return
                                }
                            }
                        }.takeIf { !it.errors.isNullOrEmpty() }?.toString()
                    } else {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            val targetId = helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = listOf(targetLogin)
                            ).data.firstOrNull()?.id
                            helixRepository.removeVip(networkLibrary, helixHeaders, channelId, targetId)
                        } else null
                    }?.let {
                        onMessage(ChatMessage(systemMsg = it))
                    }
                }
            }
            command.equals("/vips", true) -> {
                graphQLRepository.getVips(networkLibrary, gqlHeaders, channelLogin).also { response ->
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refresh")
                            return
                        }
                    }
                }.let {
                    onMessage(ChatMessage(systemMsg = it.data?.user?.vips?.edges?.map { it.node.login }?.toString() ?: it.toString()))
                }
            }
            command.equals("/w", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val splits = message.split(" ", limit = 3)
                    val targetLogin = splits.getOrNull(1)?.removePrefix("@")?.takeIf { it.isNotBlank() }
                    val message = splits.getOrNull(2)?.takeIf { it.isNotBlank() }
                    if (targetLogin != null && message != null) {
                        val targetId = helixRepository.getUsers(
                            networkLibrary = networkLibrary,
                            headers = helixHeaders,
                            logins = listOf(targetLogin)
                        ).data.firstOrNull()?.id
                        helixRepository.sendWhisper(networkLibrary, helixHeaders, accountId, targetId, message)?.let {
                            onMessage(ChatMessage(systemMsg = it))
                        }
                    }
                }
            }
            else -> sendMessage(message, networkLibrary, gqlHeaders, helixHeaders, accountId, channelId, useApiChatMessages, enableIntegrity)
        }
    }

    fun startReplayChat(videoId: String?, createdAt: String?, startTime: Int, chatUrl: String?, getCurrentPosition: () -> Long?, getCurrentSpeed: () -> Float?, channelId: String?, channelLogin: String?) {
        stopReplayChat()
        ownUserId = applicationContext.tokenPrefs().getString(C.USER_ID, null)
        ownUserLogin = applicationContext.tokenPrefs().getString(C.USERNAME, null)
        activeChannelLogin = channelLogin
        if (!chatUrl.isNullOrBlank()) {
            chatReplayManagerLocal = ChatReplayManagerLocal(
                createdAt = createdAt?.toLongOrNull() ?: createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } },
                getCurrentPosition = getCurrentPosition,
                getCurrentSpeed = getCurrentSpeed,
                coroutineScope = viewModelScope,
                listener = ChatReplayListener(),
            )
            readChatFile(chatUrl, channelId, channelLogin)
        } else {
            if (!videoId.isNullOrBlank()) {
                chatReplayManager = ChatReplayManager(
                    networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
                    graphQLRepository = graphQLRepository,
                    json = json,
                    enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                    videoId = videoId,
                    createdAt = createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } },
                    startTime = startTime.times(1000L),
                    getCurrentPosition = getCurrentPosition,
                    getCurrentSpeed = getCurrentSpeed,
                    coroutineScope = viewModelScope,
                    listener = ChatReplayListener(),
                )
            }
        }
    }

    fun startReplayChatLoad() {
        chatReplayManager?.start() ?: chatReplayManagerLocal?.startLoad()
    }

    fun startHybridReplay(
        videoId: String,
        createdAt: String?,
        getCurrentPosition: () -> Long?,
        getCurrentSpeed: () -> Float?,
    ) {
        if (videoId.isBlank()) return
        if (hybridReplayActive && hybridReplayVideoId == videoId && chatReplayManager?.isActive == true) {
            return
        }
        if (!hybridReplayActive) {
            val snapshot = synchronized(chatMessages) { chatMessages.takeLast(100) }
            synchronized(liveBackupBuffer) {
                liveBackupBuffer.clear()
                liveBackupBuffer.addAll(snapshot)
            }
        }
        chatReplayManager?.stop()
        hybridReplayActive = true
        hybridReplayVideoId = videoId
        chatReplayManager = ChatReplayManager(
            networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
            graphQLRepository = graphQLRepository,
            json = json,
            enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            videoId = videoId,
            createdAt = createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } },
            startTime = 0L,
            getCurrentPosition = getCurrentPosition,
            getCurrentSpeed = getCurrentSpeed,
            coroutineScope = viewModelScope,
            listener = ChatReplayListener(),
        )
        chatReplayManager?.start()
    }

    fun stopHybridReplay() {
        if (!hybridReplayActive) return
        hybridReplayActive = false
        hybridReplayVideoId = null
        chatReplayManager?.stop()
        chatReplayManager = null
        resetSlowChat()
        val restored = synchronized(liveBackupBuffer) {
            val items = liveBackupBuffer.toList()
            liveBackupBuffer.clear()
            items
        }
        viewModelScope.launch {
            val oldSize = synchronized(chatMessages) {
                val s = chatMessages.size
                chatMessages.clear()
                s
            }
            if (oldSize > 0) {
                removeMessages.emit(oldSize)
            }
            if (restored.isNotEmpty()) {
                val inserted = synchronized(chatMessages) {
                    val items = restored.takeLast(messageLimit)
                    chatMessages.addAll(0, items)
                    Pair(items, chatMessages.lastIndex)
                }
                addMessages.emit(inserted)
            }
        }
    }

    fun stopReplayChat() {
        resetSlowChat()
        chatReplayManager?.stop() ?: chatReplayManagerLocal?.stop()
    }

    fun updatePosition(position: Long) {
        chatReplayManager?.updatePosition(position) ?: chatReplayManagerLocal?.updatePosition(position)
    }

    fun updateSpeed(speed: Float) {
        chatReplayManager?.updateSpeed(speed) ?: chatReplayManagerLocal?.updateSpeed(speed)
    }

    private inner class ChatReplayListener : ChatReplayManager.Listener {
        override suspend fun onChatMessage(message: ChatMessage) {
            onMessage(message, fromReplay = true)
        }

        override suspend fun clearMessages() {
            synchronized(chatMessages) {
                val size = chatMessages.size
                chatMessages.clear()
                size
            }.let {
                removeMessages.emit(it)
            }
        }

        override suspend fun getIntegrityToken() {
            integrity.emit("refresh")
        }
    }

    private fun readChatFile(url: String, channelId: String?, channelLogin: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nameDisplay = applicationContext.prefs().getString(C.UI_NAME_DISPLAY, "0")
                val liveMessages = mutableListOf<ChatMessage>()
                val messages = mutableListOf<VideoChatMessage>()
                var startTimeMs = 0L
                val twitchEmotes = mutableListOf<TwitchEmote>()
                val twitchBadges = mutableListOf<TwitchBadge>()
                val cheerEmotesList = mutableListOf<CheerEmote>()
                val emotes = mutableListOf<Emote>()
                if (url.toUri().scheme == ContentResolver.SCHEME_CONTENT) {
                    applicationContext.contentResolver.openInputStream(url.toUri())?.bufferedReader()
                } else {
                    FileInputStream(File(url)).bufferedReader()
                }?.use { fileReader ->
                    JsonReader(fileReader).use { reader ->
                        reader.isLenient = true
                        var position = 0L
                        var token: JsonToken
                        do {
                            token = reader.peek()
                            when (token) {
                                JsonToken.END_DOCUMENT -> {}
                                JsonToken.BEGIN_OBJECT -> {
                                    reader.beginObject().also { position += 1 }
                                    while (reader.hasNext()) {
                                        when (reader.peek()) {
                                            JsonToken.NAME -> {
                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                    "liveStartTime" -> {
                                                        val time = reader.nextString().also { position += it.length + 2 }
                                                        Instant.parseOrNull(time)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let { startTimeMs = it }
                                                    }
                                                    "liveComments" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            val message = reader.nextString().also { position += it.length + 2 + it.count { c -> c == '"' || c == '\\' } }
                                                            val ircMessage = ChatUtils.parseIRCMessage(message)
                                                            when (ircMessage.command) {
                                                                "PRIVMSG", "USERNOTICE" -> {
                                                                    val chatMessage = ChatUtils.parseChatMessage(ircMessage)
                                                                    if (chatMessage.reply?.message != null && showReplies()) {
                                                                        liveMessages.add(ChatMessage(
                                                                            type = ChatMessage.REPLY_MESSAGE,
                                                                            reply = chatMessage.reply,
                                                                            replyParent = chatMessage,
                                                                        ))
                                                                    }
                                                                    liveMessages.add(chatMessage)
                                                                }
                                                                "CLEARMSG" -> {
                                                                    val chatMessage = ChatUtils.parseClearMessage(ircMessage)
                                                                    val deletedMessage = chatMessage.targetMsgId?.let { targetId ->
                                                                        liveMessages.find { it.id == targetId }
                                                                    }
                                                                    liveMessages.add(getClearMessage(chatMessage, deletedMessage, nameDisplay))
                                                                }
                                                                "CLEARCHAT" -> liveMessages.add(ChatUtils.parseClearChat(applicationContext, ircMessage))
                                                                "NOTICE" -> liveMessages.add(ChatUtils.parseNotice(ircMessage))
                                                            }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "comments" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            reader.beginObject().also { position += 1 }
                                                            val message = StringBuilder()
                                                            var id: String? = null
                                                            var offsetSeconds: Int? = null
                                                            var createdAt: String? = null
                                                            var userId: String? = null
                                                            var userLogin: String? = null
                                                            var userName: String? = null
                                                            var color: String? = null
                                                            val emotesList = mutableListOf<TwitchEmote>()
                                                            val badgesList = mutableListOf<Badge>()
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                    "id" -> id = reader.nextString().also { position += it.length + 2 }
                                                                    "commenter" -> {
                                                                        reader.beginObject().also { position += 1 }
                                                                        while (reader.hasNext()) {
                                                                            when (reader.nextName().also { position += it.length + 3 }) {
                                                                                "id" -> userId = reader.nextString().also { position += it.length + 2 }
                                                                                "login" -> userLogin = reader.nextString().also { position += it.length + 2 }
                                                                                "displayName" -> userName = reader.nextString().also { position += it.length + 2 }
                                                                                else -> position += skipJsonValue(reader)
                                                                            }
                                                                            if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                position += 1
                                                                            }
                                                                        }
                                                                        reader.endObject().also { position += 1 }
                                                                    }
                                                                    "contentOffsetSeconds" -> offsetSeconds = reader.nextInt().also { position += it.toString().length }
                                                                    "createdAt" -> createdAt = reader.nextString().also { position += it.length + 2 }
                                                                    "message" -> {
                                                                        reader.beginObject().also { position += 1 }
                                                                        while (reader.hasNext()) {
                                                                            when (reader.nextName().also { position += it.length + 3 }) {
                                                                                "fragments" -> {
                                                                                    reader.beginArray().also { position += 1 }
                                                                                    while (reader.hasNext()) {
                                                                                        reader.beginObject().also { position += 1 }
                                                                                        var emoteId: String? = null
                                                                                        var fragmentText: String? = null
                                                                                        while (reader.hasNext()) {
                                                                                            when (reader.nextName().also { position += it.length + 3 }) {
                                                                                                "emote" -> {
                                                                                                    when (reader.peek()) {
                                                                                                        JsonToken.BEGIN_OBJECT -> {
                                                                                                            reader.beginObject().also { position += 1 }
                                                                                                            while (reader.hasNext()) {
                                                                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                                                                    "emoteID" -> emoteId = reader.nextString().also { position += it.length + 2 }
                                                                                                                    else -> position += skipJsonValue(reader)
                                                                                                                }
                                                                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                                                    position += 1
                                                                                                                }
                                                                                                            }
                                                                                                            reader.endObject().also { position += 1 }
                                                                                                        }
                                                                                                        else -> position += skipJsonValue(reader)
                                                                                                    }
                                                                                                }
                                                                                                "text" -> fragmentText = reader.nextString().also { position += it.length + 2 + it.count { c -> c == '"' || c == '\\' } }
                                                                                                else -> position += skipJsonValue(reader)
                                                                                            }
                                                                                            if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                                position += 1
                                                                                            }
                                                                                        }
                                                                                        if (fragmentText != null && !emoteId.isNullOrBlank()) {
                                                                                            emotesList.add(TwitchEmote(
                                                                                                id = emoteId,
                                                                                                begin = message.codePointCount(0, message.length),
                                                                                                end = message.codePointCount(0, message.length) + fragmentText.lastIndex
                                                                                            ))
                                                                                        }
                                                                                        message.append(fragmentText)
                                                                                        reader.endObject().also { position += 1 }
                                                                                        if (reader.peek() != JsonToken.END_ARRAY) {
                                                                                            position += 1
                                                                                        }
                                                                                    }
                                                                                    reader.endArray().also { position += 1 }
                                                                                }
                                                                                "userBadges" -> {
                                                                                    reader.beginArray().also { position += 1 }
                                                                                    while (reader.hasNext()) {
                                                                                        reader.beginObject().also { position += 1 }
                                                                                        var set: String? = null
                                                                                        var version: String? = null
                                                                                        while (reader.hasNext()) {
                                                                                            when (reader.nextName().also { position += it.length + 3 }) {
                                                                                                "setID" -> set = reader.nextString().also { position += it.length + 2 }
                                                                                                "version" -> version = reader.nextString().also { position += it.length + 2 }
                                                                                                else -> position += skipJsonValue(reader)
                                                                                            }
                                                                                            if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                                position += 1
                                                                                            }
                                                                                        }
                                                                                        if (!set.isNullOrBlank() && !version.isNullOrBlank()) {
                                                                                            badgesList.add(Badge(set, version))
                                                                                        }
                                                                                        reader.endObject().also { position += 1 }
                                                                                        if (reader.peek() != JsonToken.END_ARRAY) {
                                                                                            position += 1
                                                                                        }
                                                                                    }
                                                                                    reader.endArray().also { position += 1 }
                                                                                }
                                                                                "userColor" -> {
                                                                                    when (reader.peek()) {
                                                                                        JsonToken.STRING -> color = reader.nextString().also { position += it.length + 2 }
                                                                                        else -> position += skipJsonValue(reader)
                                                                                    }
                                                                                }
                                                                                else -> position += skipJsonValue(reader)
                                                                            }
                                                                            if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                position += 1
                                                                            }
                                                                        }
                                                                        reader.endObject().also { position += 1 }
                                                                    }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                    position += 1
                                                                }
                                                            }
                                                            messages.add(VideoChatMessage(
                                                                id = id,
                                                                offsetSeconds = offsetSeconds,
                                                                createdAt = createdAt,
                                                                userId = userId,
                                                                userLogin = userLogin,
                                                                userName = userName,
                                                                message = message.toString(),
                                                                color = color,
                                                                emotes = emotesList,
                                                                badges = badgesList,
                                                                fullMsg = null
                                                            ))
                                                            reader.endObject().also { position += 1 }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "twitchEmotes" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            reader.beginObject().also { position += 1 }
                                                            var id: String? = null
                                                            var data: Pair<Long, Int>? = null
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                    "data" -> {
                                                                        position += 1
                                                                        val length = reader.nextString().length
                                                                        data = Pair(position, length)
                                                                        position += length + 1
                                                                    }
                                                                    "id" -> id = reader.nextString().also { position += it.length + 2 }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                    position += 1
                                                                }
                                                            }
                                                            if (!id.isNullOrBlank() && data != null) {
                                                                twitchEmotes.add(TwitchEmote(
                                                                    id = id,
                                                                    localData = data
                                                                ))
                                                            }
                                                            reader.endObject().also { position += 1 }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "twitchBadges" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            reader.beginObject().also { position += 1 }
                                                            var setId: String? = null
                                                            var version: String? = null
                                                            var data: Pair<Long, Int>? = null
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                    "data" -> {
                                                                        position += 1
                                                                        val length = reader.nextString().length
                                                                        data = Pair(position, length)
                                                                        position += length + 1
                                                                    }
                                                                    "setId" -> setId = reader.nextString().also { position += it.length + 2 }
                                                                    "version" -> version = reader.nextString().also { position += it.length + 2 }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                    position += 1
                                                                }
                                                            }
                                                            if (!setId.isNullOrBlank() && !version.isNullOrBlank() && data != null) {
                                                                twitchBadges.add(TwitchBadge(
                                                                    setId = setId,
                                                                    version = version,
                                                                    localData = data
                                                                ))
                                                            }
                                                            reader.endObject().also { position += 1 }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "cheerEmotes" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            reader.beginObject().also { position += 1 }
                                                            var name: String? = null
                                                            var data: Pair<Long, Int>? = null
                                                            var minBits: Int? = null
                                                            var color: String? = null
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                    "data" -> {
                                                                        position += 1
                                                                        val length = reader.nextString().length
                                                                        data = Pair(position, length)
                                                                        position += length + 1
                                                                    }
                                                                    "name" -> name = reader.nextString().also { position += it.length + 2 }
                                                                    "minBits" -> minBits = reader.nextInt().also { position += it.toString().length }
                                                                    "color" -> {
                                                                        when (reader.peek()) {
                                                                            JsonToken.STRING -> color = reader.nextString().also { position += it.length + 2 }
                                                                            else -> position += skipJsonValue(reader)
                                                                        }
                                                                    }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                    position += 1
                                                                }
                                                            }
                                                            if (!name.isNullOrBlank() && minBits != null && data != null) {
                                                                cheerEmotesList.add(CheerEmote(
                                                                    name = name,
                                                                    localData = data,
                                                                    minBits = minBits,
                                                                    color = color
                                                                ))
                                                            }
                                                            reader.endObject().also { position += 1 }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "emotes" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            reader.beginObject().also { position += 1 }
                                                            var data: Pair<Long, Int>? = null
                                                            var name: String? = null
                                                            var isOverlayEmote = false
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                    "data" -> {
                                                                        position += 1
                                                                        val length = reader.nextString().length
                                                                        data = Pair(position, length)
                                                                        position += length + 1
                                                                    }
                                                                    "name" -> name = reader.nextString().also { position += it.length + 2 }
                                                                    "isZeroWidth" -> isOverlayEmote = reader.nextBoolean().also { position += it.toString().length }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                    position += 1
                                                                }
                                                            }
                                                            if (!name.isNullOrBlank() && data != null) {
                                                                emotes.add(Emote(
                                                                    name = name,
                                                                    localData = data,
                                                                    isOverlayEmote = isOverlayEmote
                                                                ))
                                                            }
                                                            reader.endObject().also { position += 1 }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "startTime" -> { startTimeMs = reader.nextInt().also { position += it.toString().length }.times(1000L) }
                                                    else -> position += skipJsonValue(reader)
                                                }
                                            }
                                            else -> position += skipJsonValue(reader)
                                        }
                                        if (reader.peek() != JsonToken.END_OBJECT) {
                                            position += 1
                                        }
                                    }
                                    reader.endObject().also { position += 1 }
                                }
                                else -> position += skipJsonValue(reader)
                            }
                        } while (token != JsonToken.END_DOCUMENT)
                    }
                }
                synchronized(localTwitchEmotes) {
                    localTwitchEmotes.clear()
                    localTwitchEmotes.addAll(twitchEmotes)
                }
                synchronized(channelBadges) {
                    channelBadges.clear()
                    channelBadges.addAll(twitchBadges)
                }
                synchronized(cheerEmotes) {
                    cheerEmotes.clear()
                    cheerEmotes.addAll(cheerEmotesList)
                }
                synchronized(thirdPartyEmotes) {
                    thirdPartyEmotes.clear()
                    thirdPartyEmotes.addAll(emotes)
                }
                if (emotes.isEmpty()) {
                    viewModelScope.launch {
                        loadEmotes(channelId, channelLogin)
                    }
                }
                if (liveMessages.isNotEmpty() || messages.isNotEmpty()) {
                    viewModelScope.launch {
                        chatReplayManagerLocal?.setMessages(liveMessages, messages, startTimeMs)
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    private fun skipJsonValue(reader: JsonReader): Int {
        var length = 0
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray().also { length += 1 }
                while (reader.hasNext()) {
                    when (reader.peek()) {
                        JsonToken.NAME -> length += reader.nextName().length + 3
                        else -> {
                            length += skipJsonValue(reader)
                            if (reader.peek() != JsonToken.END_ARRAY) {
                                length += 1
                            }
                        }
                    }
                }
                reader.endArray().also { length += 1 }
            }
            JsonToken.END_ARRAY -> length += 1
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject().also { length += 1 }
                while (reader.hasNext()) {
                    when (reader.peek()) {
                        JsonToken.NAME -> length += reader.nextName().length + 3
                        else -> {
                            length += skipJsonValue(reader)
                            if (reader.peek() != JsonToken.END_OBJECT) {
                                length += 1
                            }
                        }
                    }
                }
                reader.endObject().also { length += 1 }
            }
            JsonToken.END_OBJECT -> length += 1
            JsonToken.STRING -> reader.nextString().let { length += it.length + 2 + it.count { c -> c == '"' || c == '\\' } }
            JsonToken.NUMBER -> length += reader.nextString().length
            JsonToken.BOOLEAN -> length += reader.nextBoolean().toString().length
            else -> reader.skipValue()
        }
        return length
    }

    companion object {
        private var savedEmoteSets: List<String>? = null
        private var savedUserEmotes: List<TwitchEmote>? = null
        private var savedGlobalBadges: List<TwitchBadge>? = null
        private var savedGlobalSTVEmotes: List<Emote>? = null
        private var savedGlobalBTTVEmotes: List<Emote>? = null
        private var savedGlobalFFZEmotes: List<Emote>? = null

        val ChatViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                ChatViewModel(application.applicationContext, xtraModule.graphQLRepository, xtraModule.helixRepository, xtraModule.playerRepository, xtraModule.json)
            }
        }
    }
}