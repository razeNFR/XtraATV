package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ChatReplayManager(
    private val networkLibrary: String?,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val json: Json,
    private val enableIntegrity: Boolean,
    private val videoId: String,
    private val createdAt: Long?,
    private val startTime: Long,
    private val getCurrentPosition: () -> Long?,
    private val getCurrentSpeed: () -> Float?,
    private val coroutineScope: CoroutineScope,
    private val listener: Listener,
) {
    private var cursor: String? = null
    private val list = mutableListOf<VideoChatMessage>()
    private var started = false
    @Volatile
    private var isLoading = false
    private var useOffsetSeconds = false
    private var loadJob: Job? = null
    private var messageJob: Job? = null
    private var lastCheckedPosition = 0L
    private var lastReloadPosition = 0L
    private var playbackSpeed: Float? = null
    var isActive = true

    fun start() {
        if (!started) {
            started = true
            val currentPosition = getCurrentPosition() ?: 0
            lastCheckedPosition = currentPosition
            lastReloadPosition = currentPosition
            playbackSpeed = getCurrentSpeed()
            list.clear()
            cursor = null
            coroutineScope.launch {
                listener.clearMessages()
            }
            load(currentPosition + startTime)
        }
    }

    fun stop() {
        loadJob?.cancel()
        messageJob?.cancel()
        isLoading = false
        isActive = false
    }

    private fun messageOffsetMs(message: VideoChatMessage): Long? {
        val fromCreated = if (!useOffsetSeconds && createdAt != null && !message.createdAt.isNullOrBlank()) {
            Instant.parseOrNull(message.createdAt)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.minus(createdAt)
        } else {
            null
        }
        return fromCreated ?: message.offsetSeconds?.times(1000L)
    }

    private fun load(position: Long? = null) {
        loadJob?.cancel()
        if (position != null) {
            cursor = null
        }
        isLoading = true
        loadJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val response = if (position != null) {
                    graphQLRepository.loadQueryVideoComments(networkLibrary, gqlHeaders, videoId, offset = position.div(1000).toInt())
                } else {
                    graphQLRepository.loadQueryVideoComments(networkLibrary, gqlHeaders, videoId, cursor = cursor)
                }
                if (enableIntegrity) {
                    response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                        listener.getIntegrityToken()
                        isLoading = false
                        return@launch
                    }
                }
                val comments = response.data!!.video!!.comments!!
                val messages = comments.edges!!.mapNotNull { comment ->
                    comment?.node.let { item ->
                        item?.message?.let { message ->
                            val chatMessage = StringBuilder()
                            val emotes = message.fragments?.mapNotNull { fragment ->
                                fragment?.text?.let { text ->
                                    fragment.emote?.emoteID?.let { id ->
                                        TwitchEmote(
                                            id = id,
                                            begin = chatMessage.codePointCount(0, chatMessage.length),
                                            end = chatMessage.codePointCount(0, chatMessage.length) + text.lastIndex
                                        )
                                    }.also { chatMessage.append(text) }
                                }
                            }
                            val badges = message.userBadges?.mapNotNull { badge ->
                                badge?.setID?.let { setId ->
                                    badge.version?.let { version ->
                                        Badge(
                                            setId = setId,
                                            version = version,
                                        )
                                    }
                                }
                            }
                            VideoChatMessage(
                                id = item.id,
                                offsetSeconds = item.contentOffsetSeconds,
                                createdAt = item.createdAt?.toString(),
                                userId = item.commenter?.id,
                                userLogin = item.commenter?.login,
                                userName = item.commenter?.displayName,
                                message = chatMessage.toString(),
                                color = message.userColor,
                                emotes = emotes,
                                badges = badges,
                                fullMsg = null
                            )
                        }
                    }
                }
                // VideoComment createdAt is wrong on highlights
                useOffsetSeconds = if (createdAt != null) {
                    val first = messages.firstOrNull()
                    val last = messages.lastOrNull()
                    if (first != last && first?.createdAt != null && last?.createdAt != null) {
                        if (first.offsetSeconds == null || last.offsetSeconds == null || first.offsetSeconds != last.offsetSeconds) {
                            val firstCreatedAt = Instant.parseOrNull(first.createdAt)?.epochSeconds?.takeIf { it > 0 }
                            val lastCreatedAt = Instant.parseOrNull(last.createdAt)?.epochSeconds?.takeIf { it > 0 }
                            firstCreatedAt == null || lastCreatedAt == null || firstCreatedAt == lastCreatedAt
                        } else true
                    } else true
                } else true
                messageJob?.cancel()
                list.addAll(messages)
                cursor = if (comments.pageInfo?.hasNextPage != false) comments.edges.lastOrNull()?.cursor?.toString() else null
                isLoading = false
                startJob()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    val response = if (position != null) {
                        graphQLRepository.loadVideoMessages(networkLibrary, gqlHeaders, videoId, offset = position.div(1000).toInt())
                    } else {
                        graphQLRepository.loadVideoMessages(networkLibrary, gqlHeaders, videoId, cursor = cursor)
                    }
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            listener.getIntegrityToken()
                            isLoading = false
                            return@launch
                        }
                    }
                    val comments = response.data!!.video.comments
                    val messages = comments.edges.mapNotNull { comment ->
                        comment.node.let { item ->
                            item.message?.let { message ->
                                val chatMessage = StringBuilder()
                                val emotes = message.fragments?.mapNotNull { fragment ->
                                    fragment.text?.let { text ->
                                        fragment.emote?.emoteID?.let { id ->
                                            TwitchEmote(
                                                id = id,
                                                begin = chatMessage.codePointCount(0, chatMessage.length),
                                                end = chatMessage.codePointCount(0, chatMessage.length) + text.lastIndex
                                            )
                                        }.also { chatMessage.append(text) }
                                    }
                                }
                                val badges = message.userBadges?.mapNotNull { badge ->
                                    badge.setID?.let { setId ->
                                        badge.version?.let { version ->
                                            Badge(
                                                setId = setId,
                                                version = version,
                                            )
                                        }
                                    }
                                }
                                VideoChatMessage(
                                    id = item.id,
                                    offsetSeconds = item.contentOffsetSeconds,
                                    createdAt = item.createdAt,
                                    userId = item.commenter?.id,
                                    userLogin = item.commenter?.login,
                                    userName = item.commenter?.displayName,
                                    message = chatMessage.toString(),
                                    color = message.userColor,
                                    emotes = emotes,
                                    badges = badges,
                                    fullMsg = json.encodeToString(item)
                                )
                            }
                        }
                    }
                    useOffsetSeconds = if (createdAt != null) {
                        val first = messages.firstOrNull()
                        val last = messages.lastOrNull()
                        if (first != last && first?.createdAt != null && last?.createdAt != null) {
                            if (first.offsetSeconds == null || last.offsetSeconds == null || first.offsetSeconds != last.offsetSeconds) {
                                val firstCreatedAt = Instant.parseOrNull(first.createdAt)?.epochSeconds?.takeIf { it > 0 }
                                val lastCreatedAt = Instant.parseOrNull(last.createdAt)?.epochSeconds?.takeIf { it > 0 }
                                firstCreatedAt == null || lastCreatedAt == null || firstCreatedAt == lastCreatedAt
                            } else true
                        } else true
                    } else true
                    messageJob?.cancel()
                    list.addAll(messages)
                    cursor = if (comments.pageInfo?.hasNextPage != false) comments.edges.lastOrNull()?.cursor else null
                    isLoading = false
                    startJob()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    isLoading = false
                    if (this@ChatReplayManager.isActive) {
                        delay(3000.milliseconds)
                        if (this@ChatReplayManager.isActive && !isLoading && list.isEmpty()) {
                            val retryPos = getCurrentPosition()?.let { it + startTime } ?: position
                            if (retryPos != null || !cursor.isNullOrBlank()) {
                                load(if (!cursor.isNullOrBlank() && position == null) null else retryPos)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startJob() {
        messageJob = coroutineScope.launch {
            while (isActive && this@ChatReplayManager.isActive) {
                val message = list.firstOrNull()
                if (message == null) {
                    if (!cursor.isNullOrBlank() && !isLoading && this@ChatReplayManager.isActive) {
                        load()
                    }
                    break
                }
                val messageOffset = messageOffsetMs(message)
                if (messageOffset != null) {
                    var currentPosition = 0L
                    while (
                        isActive && this@ChatReplayManager.isActive &&
                        (getCurrentPosition() ?: 0).let { position ->
                            lastCheckedPosition = position
                            currentPosition = position + startTime
                            currentPosition < messageOffset
                        }
                    ) {
                        val speed = (playbackSpeed ?: 1f).coerceAtLeast(0.1f)
                        val timeLeft = ((messageOffset - currentPosition) / speed).toLong()
                        // Cap each wait step to <= 2000ms so pauses, seeks, or speed changes wake up promptly
                        val stepDelay = timeLeft.coerceIn(50L, 2000L)
                        delay(stepDelay.milliseconds)
                    }
                    if (!isActive || !this@ChatReplayManager.isActive) {
                        break
                    }
                    listener.onChatMessage(
                        ChatMessage(
                            type = ChatMessage.USER_MESSAGE,
                            id = message.id,
                            userId = message.userId,
                            userLogin = message.userLogin,
                            userName = message.userName,
                            message = message.message,
                            color = message.color,
                            emotes = message.emotes,
                            badges = message.badges,
                            bits = 0,
                            fullMsg = message.fullMsg
                        )
                    )
                    if (list.size <= 25 && !cursor.isNullOrBlank() && !isLoading) {
                        load()
                    }
                } else {
                    if (!isActive || !this@ChatReplayManager.isActive) {
                        break
                    }
                }
                list.remove(message)
            }
        }
    }

    fun updatePosition(position: Long) {
        if (!started || lastCheckedPosition == position) return
        val targetOffset = position + startTime
        val lastBufferedOffset = list.lastOrNull()?.let { messageOffsetMs(it) }
        val backwardSeek = position < lastCheckedPosition - 2_000L
        val largeForwardJump = position - lastCheckedPosition > 20_000L
        val cumulativeSkipPastBuffer = position - lastReloadPosition > 20_000L &&
            (lastBufferedOffset == null || targetOffset > lastBufferedOffset + 5_000L)
        val stalledAndMoved = list.isEmpty() && !isLoading && cursor.isNullOrBlank() && abs(position - lastReloadPosition) > 5_000L

        if (backwardSeek || largeForwardJump || cumulativeSkipPastBuffer || stalledAndMoved) {
            lastReloadPosition = position
            lastCheckedPosition = position
            loadJob?.cancel()
            messageJob?.cancel()
            isLoading = false
            cursor = null
            list.clear()
            coroutineScope.launch {
                listener.clearMessages()
            }
            load(targetOffset)
        } else {
            lastCheckedPosition = position
            messageJob?.cancel()
            startJob()
        }
    }

    fun updateSpeed(speed: Float) {
        if (started && playbackSpeed != speed) {
            playbackSpeed = speed
            messageJob?.cancel()
            startJob()
        }
    }

    interface Listener {
        suspend fun onChatMessage(message: ChatMessage) {}
        suspend fun clearMessages() {}
        suspend fun getIntegrityToken() {}
    }
}