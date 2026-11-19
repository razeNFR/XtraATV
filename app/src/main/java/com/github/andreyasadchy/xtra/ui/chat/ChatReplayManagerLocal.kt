package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ChatReplayManagerLocal(
    private val createdAt: Long?,
    private val getCurrentPosition: () -> Long?,
    private val getCurrentSpeed: () -> Float?,
    private val coroutineScope: CoroutineScope,
    private val listener: ChatReplayManager.Listener,
) {
    private var liveMessages: List<ChatMessage>? = null
    private var messages: List<VideoChatMessage>? = null
    private var startTime = 0L
    private val liveList = ArrayDeque<ChatMessage>()
    private val list = ArrayDeque<VideoChatMessage>()
    private var started = false
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var messageJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastCheckedPosition = 0L
    private var playbackSpeed: Float? = null
    var isActive = true

    fun setMessages(newLiveMessages: List<ChatMessage>, newMessages: List<VideoChatMessage>, newStartTime: Long) {
        if (newLiveMessages.isNotEmpty()) {
            liveMessages = newLiveMessages
            if (createdAt != null) {
                startTime = newStartTime - createdAt
            }
        } else {
            messages = newMessages
            startTime = newStartTime
        }
        if (started) {
            start()
        } else {
            startWhenReady()
        }
    }

    fun startWhenReady() {
        if (!isActive) return
        ensureWatchdog()
        if (!started && (!liveMessages.isNullOrEmpty() || !messages.isNullOrEmpty())) {
            val pos = getCurrentPosition()
            if (pos != null) {
                started = true
                start()
            }
        }
    }

    fun syncWithPlayer() {
        if (!isActive) return
        ensureWatchdog()
        val pos = getCurrentPosition() ?: return
        if (!started) {
            startLoad()
            return
        }
        updatePosition(pos)
    }

    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true || !isActive) return
        watchdogJob = coroutineScope.launch {
            while (isActive && this@ChatReplayManagerLocal.isActive) {
                delay(1000.milliseconds)
                if (!isActive || !this@ChatReplayManagerLocal.isActive) break
                val pos = getCurrentPosition() ?: continue
                getCurrentSpeed()?.let { spd -> if (playbackSpeed != spd) playbackSpeed = spd }
                val hasData = !liveMessages.isNullOrEmpty() || !messages.isNullOrEmpty()
                if (!hasData) continue
                if (!started) {
                    started = true
                    start()
                    continue
                }
                if (pos < lastCheckedPosition - 2_500L || pos - lastCheckedPosition > 20_000L) {
                    updatePosition(pos)
                    continue
                }
                lastCheckedPosition = pos
                if ((liveList.isNotEmpty() || list.isNotEmpty()) && messageJob?.isActive != true && loadJob?.isActive != true) {
                    startJob()
                }
            }
        }
    }

    fun startLoad() {
        if (!isActive) return
        ensureWatchdog()
        if (!started) {
            started = true
            if (!liveMessages.isNullOrEmpty() || !messages.isNullOrEmpty()) {
                start()
            }
        }
    }

    fun start() {
        if (!isActive) return
        ensureWatchdog()
        val currentPosition = getCurrentPosition() ?: 0
        lastCheckedPosition = currentPosition
        playbackSpeed = getCurrentSpeed()
        loadGeneration++
        val gen = loadGeneration
        loadJob?.cancel()
        messageJob?.cancel()
        liveList.clear()
        list.clear()
        coroutineScope.launch {
            listener.clearMessages()
        }
        load(currentPosition + startTime, gen)
    }

    fun stop() {
        loadGeneration++
        loadJob?.cancel()
        messageJob?.cancel()
        watchdogJob?.cancel()
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

    private fun load(position: Long, gen: Long = loadGeneration) {
        loadJob?.cancel()
        val liveSource = liveMessages
        val vodSource = messages
        loadJob = coroutineScope.launch {
            val minPos = max(position - 20_000L, 0L)
            val filteredLive = if (!liveSource.isNullOrEmpty()) {
                withContext(Dispatchers.Default) {
                    liveSource.filter { message ->
                        val messageOffset = if (createdAt != null && message.timestamp != null) {
                            message.timestamp - createdAt
                        } else null
                        messageOffset != null && messageOffset >= minPos
                    }
                }
            } else emptyList()
            val filteredVod = if (liveSource.isNullOrEmpty() && !vodSource.isNullOrEmpty()) {
                withContext(Dispatchers.Default) {
                    vodSource.filter { message ->
                        val off = messageOffsetMs(message)
                        off != null && off >= minPos
                    }
                }
            } else emptyList()
            if (!isActive || !this@ChatReplayManagerLocal.isActive || gen != loadGeneration) return@launch
            messageJob?.cancel()
            liveList.clear()
            list.clear()
            if (filteredLive.isNotEmpty()) {
                liveList.addAll(filteredLive)
            } else if (filteredVod.isNotEmpty()) {
                list.addAll(filteredVod)
            }
            startJob()
        }
    }

    private fun startJob() {
        messageJob?.cancel()
        messageJob = coroutineScope.launch {
            while (isActive && this@ChatReplayManagerLocal.isActive) {
                if (!liveMessages.isNullOrEmpty()) {
                    val message = liveList.firstOrNull() ?: break
                    val messageOffset = if (createdAt != null && message.timestamp != null) {
                        message.timestamp - createdAt
                    } else null
                    if (messageOffset != null) {
                        while (isActive && this@ChatReplayManagerLocal.isActive) {
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
                            delay(timeLeft.coerceIn(50L, 1500L).milliseconds)
                        }
                        if (!isActive || !this@ChatReplayManagerLocal.isActive) break
                        if (liveList.firstOrNull() === message) {
                            liveList.removeFirst()
                            listener.onChatMessage(message)
                        }
                    } else {
                        if (!isActive || !this@ChatReplayManagerLocal.isActive) break
                        if (liveList.firstOrNull() === message) {
                            liveList.removeFirst()
                        }
                    }
                } else {
                    val message = list.firstOrNull() ?: break
                    val messageOffset = messageOffsetMs(message)
                    if (messageOffset != null) {
                        while (isActive && this@ChatReplayManagerLocal.isActive) {
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
                            delay(timeLeft.coerceIn(50L, 1500L).milliseconds)
                        }
                        if (!isActive || !this@ChatReplayManagerLocal.isActive) break
                        if (list.firstOrNull() === message) {
                            list.removeFirst()
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
                                    fullMsg = message.fullMsg,
                                )
                            )
                        }
                    } else {
                        if (!isActive || !this@ChatReplayManagerLocal.isActive) break
                        if (list.firstOrNull() === message) {
                            list.removeFirst()
                        }
                    }
                }
            }
        }
    }

    fun updatePosition(position: Long) {
        if (!isActive) return
        ensureWatchdog()
        if (!started) {
            startLoad()
            return
        }
        if ((!liveMessages.isNullOrEmpty() || !messages.isNullOrEmpty()) && lastCheckedPosition != position) {
            if (position - lastCheckedPosition !in 0..20000) {
                loadGeneration++
                val gen = loadGeneration
                loadJob?.cancel()
                messageJob?.cancel()
                liveList.clear()
                list.clear()
                coroutineScope.launch {
                    listener.clearMessages()
                }
                lastCheckedPosition = position
                load(position + startTime, gen)
            } else {
                lastCheckedPosition = position
                messageJob?.cancel()
                startJob()
            }
        }
    }

    fun updateSpeed(speed: Float) {
        playbackSpeed = speed
        if (started && isActive && (!liveList.isEmpty() || !list.isEmpty())) {
            messageJob?.cancel()
            startJob()
        }
    }
}