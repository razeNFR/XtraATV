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
import kotlinx.coroutines.withContext
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
    private val list = ArrayDeque<VideoChatMessage>()
    private var cursor: String? = null
    private var hasMorePages = true
    private var started = false
    private var isLoading = false
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var messageJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastCheckedPosition = 0L
    private var lastReloadPosition = 0L
    private var playbackSpeed: Float? = null
    private var consecutiveErrors = 0
    var isActive = true

    private sealed interface PageResult {
        data class Success(
            val messages: List<VideoChatMessage>,
            val nextCursor: String?,
            val hasNextPage: Boolean,
        ) : PageResult
        data object IntegrityRequired : PageResult
        data object Failure : PageResult
    }

    /**
     * Starts a lightweight 1s main-thread watchdog that automatically starts VOD replay as soon as
     * the player reports a position, and self-heals after long pauses, screensaver wake-ups,
     * mini-player transitions, or missed seek callbacks.
     */
    fun startWhenReady() {
        if (!isActive) return
        ensureWatchdog()
        if (!started) {
            val pos = getCurrentPosition()
            if (pos != null) {
                startAt(pos)
            }
        }
    }

    fun start() {
        if (!isActive) return
        ensureWatchdog()
        if (!started) {
            val pos = getCurrentPosition() ?: 0L
            startAt(pos)
        }
    }

    fun syncWithPlayer() {
        if (!isActive) return
        ensureWatchdog()
        val pos = getCurrentPosition() ?: return
        if (!started) {
            startAt(pos)
            return
        }
        updatePosition(pos)
        if (!isLoading && list.isNotEmpty() && messageJob?.isActive != true) {
            startJob()
        }
    }

    private fun startAt(currentPosition: Long) {
        started = true
        resetAndLoad(currentPosition, includeContext = true, clearExistingUi = true)
    }

    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true || !isActive) return
        watchdogJob = coroutineScope.launch {
            while (isActive && this@ChatReplayManager.isActive) {
                delay(1000.milliseconds)
                if (!isActive || !this@ChatReplayManager.isActive) break
                val pos = getCurrentPosition() ?: continue
                getCurrentSpeed()?.let { spd ->
                    if (playbackSpeed != spd) {
                        playbackSpeed = spd
                    }
                }
                if (!started) {
                    startAt(pos)
                    continue
                }
                val backwardJump = pos < lastCheckedPosition - 2_500L
                val forwardJump = pos - lastCheckedPosition > 20_000L
                if (backwardJump || forwardJump) {
                    resetAndLoad(pos, includeContext = true, clearExistingUi = true)
                    continue
                }
                lastCheckedPosition = pos
                if (!isLoading) {
                    if (list.isNotEmpty() && messageJob?.isActive != true) {
                        startJob()
                    } else if (list.isEmpty() && messageJob?.isActive != true) {
                        if (!cursor.isNullOrBlank() && hasMorePages) {
                            loadNextPage(loadGeneration)
                        } else if (consecutiveErrors > 0) {
                            resetAndLoad(pos, includeContext = false, clearExistingUi = false)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        loadGeneration++
        loadJob?.cancel()
        messageJob?.cancel()
        watchdogJob?.cancel()
        isLoading = false
        isActive = false
    }

    private fun messageOffsetMs(message: VideoChatMessage): Long? {
        message.offsetSeconds?.let { return it * 1000L }
        if (createdAt != null && !message.createdAt.isNullOrBlank()) {
            Instant.parseOrNull(message.createdAt)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let {
                return (it - createdAt).coerceAtLeast(0L)
            }
        }
        return null
    }

    private fun toChatMessage(message: VideoChatMessage): ChatMessage = ChatMessage(
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
        fullMsg = message.fullMsg,
    )

    private fun resetAndLoad(
        currentPosition: Long,
        includeContext: Boolean,
        clearExistingUi: Boolean,
    ) {
        loadGeneration++
        val gen = loadGeneration
        lastCheckedPosition = currentPosition
        lastReloadPosition = currentPosition
        playbackSpeed = getCurrentSpeed() ?: playbackSpeed
        loadJob?.cancel()
        messageJob?.cancel()
        isLoading = false
        list.clear()
        cursor = null
        hasMorePages = true
        consecutiveErrors = 0
        if (clearExistingUi) {
            coroutineScope.launch {
                listener.clearMessages()
            }
        }
        loadByOffset(
            targetOffsetMs = (currentPosition + startTime).coerceAtLeast(0L),
            includeContext = includeContext,
            gen = gen,
        )
    }

    private fun loadByOffset(
        targetOffsetMs: Long,
        includeContext: Boolean,
        gen: Long,
    ) {
        if (!isActive || gen != loadGeneration) return
        loadJob?.cancel()
        cursor = null
        isLoading = true
        val queryOffsetMs = if (includeContext) {
            (targetOffsetMs - CONTEXT_LOOKBACK_MS).coerceAtLeast(0L)
        } else {
            targetOffsetMs
        }
        val queryOffsetSec = (queryOffsetMs / 1000L).toInt()
        loadJob = coroutineScope.launch {
            val result = fetchPageIo(offsetSec = queryOffsetSec, pageCursor = null)
            if (!isActive || !this@ChatReplayManager.isActive || gen != loadGeneration) return@launch
            isLoading = false
            when (result) {
                PageResult.IntegrityRequired -> {
                    listener.getIntegrityToken()
                }
                PageResult.Failure -> {
                    scheduleRetry(gen, fromCursor = false, fallbackOffsetMs = targetOffsetMs)
                }
                is PageResult.Success -> {
                    consecutiveErrors = 0
                    cursor = if (result.hasNextPage && !result.nextCursor.isNullOrBlank()) result.nextCursor else null
                    hasMorePages = cursor != null
                    val nowOffsetMs = ((getCurrentPosition() ?: lastReloadPosition) + startTime).coerceAtLeast(targetOffsetMs)
                    if (includeContext) {
                        val contextItems = ArrayList<ChatMessage>()
                        val futureItems = ArrayList<VideoChatMessage>()
                        for (msg in result.messages) {
                            val off = messageOffsetMs(msg)
                            if (off != null && off < nowOffsetMs - 30_000L) {
                                continue
                            }
                            if (off == null || off <= nowOffsetMs) {
                                contextItems.add(toChatMessage(msg))
                            } else {
                                futureItems.add(msg)
                            }
                        }
                        if (contextItems.isNotEmpty()) {
                            listener.onReplayBatch(contextItems.takeLast(MAX_CONTEXT_MESSAGES))
                        }
                        messageJob?.cancel()
                        list.addAll(futureItems)
                        if (futureItems.isEmpty() && result.messages.isNotEmpty() && hasMorePages) {
                            val lastOff = result.messages.lastOrNull()?.let { messageOffsetMs(it) } ?: nowOffsetMs
                            if (nowOffsetMs - lastOff > 4_000L) {
                                // Dense chat (>100 messages inside lookback window): jump straight to current second
                                loadByOffset(targetOffsetMs = nowOffsetMs, includeContext = false, gen = gen)
                                return@launch
                            } else {
                                loadNextPage(gen)
                                return@launch
                            }
                        }
                        startJob()
                    } else {
                        val filtered = result.messages.filter { msg ->
                            val off = messageOffsetMs(msg)
                            off == null || off >= targetOffsetMs - 1_000L
                        }
                        messageJob?.cancel()
                        list.addAll(filtered)
                        startJob()
                    }
                }
            }
        }
    }

    private fun loadNextPage(gen: Long = loadGeneration) {
        val nextCursor = cursor
        if (!isActive || gen != loadGeneration || isLoading || nextCursor.isNullOrBlank() || !hasMorePages) return
        loadJob?.cancel()
        isLoading = true
        loadJob = coroutineScope.launch {
            val result = fetchPageIo(offsetSec = null, pageCursor = nextCursor)
            if (!isActive || !this@ChatReplayManager.isActive || gen != loadGeneration) return@launch
            isLoading = false
            when (result) {
                PageResult.IntegrityRequired -> {
                    listener.getIntegrityToken()
                }
                PageResult.Failure -> {
                    scheduleRetry(gen, fromCursor = true, fallbackOffsetMs = null)
                }
                is PageResult.Success -> {
                    consecutiveErrors = 0
                    cursor = if (result.hasNextPage && !result.nextCursor.isNullOrBlank()) result.nextCursor else null
                    hasMorePages = cursor != null
                    list.addAll(result.messages)
                    if (messageJob?.isActive != true) {
                        startJob()
                    }
                }
            }
        }
    }

    private fun scheduleRetry(gen: Long, fromCursor: Boolean, fallbackOffsetMs: Long?) {
        consecutiveErrors++
        val backoffMs = (minOf(consecutiveErrors, 4) * 2000L)
        loadJob = coroutineScope.launch {
            delay(backoffMs.milliseconds)
            if (!isActive || !this@ChatReplayManager.isActive || gen != loadGeneration || isLoading) return@launch
            if (fromCursor && !cursor.isNullOrBlank() && hasMorePages) {
                loadNextPage(gen)
            } else if (list.isEmpty()) {
                val retryOffsetMs = getCurrentPosition()?.let { (it + startTime).coerceAtLeast(0L) }
                    ?: fallbackOffsetMs
                    ?: (lastReloadPosition + startTime).coerceAtLeast(0L)
                loadByOffset(targetOffsetMs = retryOffsetMs, includeContext = false, gen = gen)
            }
        }
    }

    private suspend fun fetchPageIo(offsetSec: Int?, pageCursor: String?): PageResult = withContext(Dispatchers.IO) {
        try {
            val response = if (pageCursor != null) {
                graphQLRepository.loadQueryVideoComments(networkLibrary, gqlHeaders, videoId, cursor = pageCursor)
            } else {
                graphQLRepository.loadQueryVideoComments(networkLibrary, gqlHeaders, videoId, offset = offsetSec)
            }
            if (enableIntegrity && response.errors?.any { it.message == C.FAILED_INTEGRITY_CHECK } == true) {
                return@withContext PageResult.IntegrityRequired
            }
            if (response.errors.isNullOrEmpty() && response.data != null && response.data?.video == null) {
                return@withContext PageResult.Success(emptyList(), null, false)
            }
            val comments = response.data!!.video!!.comments!!
            val edges = comments.edges!!
            val messages = edges.mapNotNull { comment ->
                comment?.node?.let { item ->
                    item.message?.let { message ->
                        val chatMessage = StringBuilder()
                        val emotes = message.fragments?.mapNotNull { fragment ->
                            fragment?.text?.let { text ->
                                fragment.emote?.emoteID?.let { id ->
                                    TwitchEmote(
                                        id = id,
                                        begin = chatMessage.codePointCount(0, chatMessage.length),
                                        end = chatMessage.codePointCount(0, chatMessage.length) + text.lastIndex,
                                    )
                                }.also { chatMessage.append(text) }
                            }
                        }
                        val badges = message.userBadges?.mapNotNull { badge ->
                            badge?.setID?.let { setId ->
                                badge.version?.let { version ->
                                    Badge(setId = setId, version = version)
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
                            fullMsg = null,
                        )
                    }
                }
            }
            val next = edges.lastOrNull()?.cursor?.toString()
            val hasNext = comments.pageInfo?.hasNextPage == true && !next.isNullOrBlank()
            PageResult.Success(messages, next, hasNext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            try {
                val response = if (pageCursor != null) {
                    graphQLRepository.loadVideoMessages(networkLibrary, gqlHeaders, videoId, cursor = pageCursor)
                } else {
                    graphQLRepository.loadVideoMessages(networkLibrary, gqlHeaders, videoId, offset = offsetSec)
                }
                if (enableIntegrity && response.errors?.any { it.message == C.FAILED_INTEGRITY_CHECK } == true) {
                    return@withContext PageResult.IntegrityRequired
                }
                val video = response.data?.video
                    ?: if (response.errors.isNullOrEmpty() && response.data != null) {
                        return@withContext PageResult.Success(emptyList(), null, false)
                    } else {
                        return@withContext PageResult.Failure
                    }
                val comments = video.comments
                val messages = comments.edges.mapNotNull { comment ->
                    val item = comment.node
                    item.message?.let { message ->
                        val chatMessage = StringBuilder()
                        val emotes = message.fragments?.mapNotNull { fragment ->
                            fragment.text?.let { text ->
                                fragment.emote?.emoteID?.let { id ->
                                    TwitchEmote(
                                        id = id,
                                        begin = chatMessage.codePointCount(0, chatMessage.length),
                                        end = chatMessage.codePointCount(0, chatMessage.length) + text.lastIndex,
                                    )
                                }.also { chatMessage.append(text) }
                            }
                        }
                        val badges = message.userBadges?.mapNotNull { badge ->
                            badge.setID?.let { setId ->
                                badge.version?.let { version ->
                                    Badge(setId = setId, version = version)
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
                            fullMsg = json.encodeToString(item),
                        )
                    }
                }
                val next = comments.edges.lastOrNull()?.cursor
                val hasNext = comments.pageInfo?.hasNextPage == true && !next.isNullOrBlank()
                PageResult.Success(messages, next, hasNext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PageResult.Failure
            }
        }
    }

    private fun startJob() {
        messageJob?.cancel()
        messageJob = coroutineScope.launch {
            while (isActive && this@ChatReplayManager.isActive) {
                val message = list.firstOrNull()
                if (message == null) {
                    if (!cursor.isNullOrBlank() && hasMorePages && !isLoading) {
                        loadNextPage(loadGeneration)
                    }
                    break
                }
                val messageOffset = messageOffsetMs(message)
                if (messageOffset != null) {
                    while (isActive && this@ChatReplayManager.isActive) {
                        val pos = getCurrentPosition()
                        if (pos == null) {
                            delay(300.milliseconds)
                            continue
                        }
                        lastCheckedPosition = pos
                        val currentPosition = pos + startTime
                        if (currentPosition >= messageOffset) break
                        val speed = (playbackSpeed ?: getCurrentSpeed() ?: 1f).coerceAtLeast(0.1f)
                        val timeLeft = ((messageOffset - currentPosition) / speed).toLong()
                        val stepDelay = timeLeft.coerceIn(50L, 1500L)
                        delay(stepDelay.milliseconds)
                    }
                    if (!isActive || !this@ChatReplayManager.isActive) break
                    if (list.firstOrNull() === message) {
                        list.removeFirst()
                        listener.onChatMessage(toChatMessage(message))
                        if (list.size <= 25 && !cursor.isNullOrBlank() && hasMorePages && !isLoading) {
                            loadNextPage(loadGeneration)
                        }
                    }
                } else {
                    if (!isActive || !this@ChatReplayManager.isActive) break
                    if (list.firstOrNull() === message) {
                        list.removeFirst()
                    }
                }
            }
        }
    }

    fun updatePosition(position: Long) {
        if (!isActive) return
        ensureWatchdog()
        if (!started) {
            startAt(position)
            return
        }
        if (lastCheckedPosition == position) return
        val targetOffset = (position + startTime).coerceAtLeast(0L)
        val lastBufferedOffset = list.lastOrNull()?.let { messageOffsetMs(it) }
        val backwardSeek = position < lastCheckedPosition - 2_000L
        val largeForwardJump = position - lastCheckedPosition > 20_000L
        val cumulativeSkipPastBuffer = abs(position - lastReloadPosition) > 20_000L &&
            (lastBufferedOffset == null || targetOffset > lastBufferedOffset + 5_000L)
        val stalledAndMoved = list.isEmpty() && !isLoading && abs(position - lastReloadPosition) > 5_000L

        if (backwardSeek || largeForwardJump || cumulativeSkipPastBuffer || stalledAndMoved) {
            resetAndLoad(position, includeContext = true, clearExistingUi = true)
        } else {
            while (list.isNotEmpty()) {
                val off = messageOffsetMs(list.first())
                if (off != null && off < targetOffset - 15_000L) {
                    list.removeFirst()
                } else {
                    break
                }
            }
            lastCheckedPosition = position
            messageJob?.cancel()
            startJob()
        }
    }

    fun updateSpeed(speed: Float) {
        playbackSpeed = speed
        if (started && isActive && list.isNotEmpty()) {
            messageJob?.cancel()
            startJob()
        }
    }

    interface Listener {
        suspend fun onChatMessage(message: ChatMessage) {}
        suspend fun onReplayBatch(messages: List<ChatMessage>) {}
        suspend fun clearMessages() {}
        suspend fun getIntegrityToken() {}
    }

    companion object {
        private const val CONTEXT_LOOKBACK_MS = 12_000L
        private const val MAX_CONTEXT_MESSAGES = 35
    }
}