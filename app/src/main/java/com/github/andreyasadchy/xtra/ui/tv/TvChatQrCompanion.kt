package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TV: Local Wi-Fi QR Code Live Chat Companion.
 *
 * Serves a sleek mobile web app over local Wi-Fi (`http://<tv-ip>:<port>/<token>`) connected directly to the active
 * [ChatFragment] via Server-Sent Events (SSE). Viewers scan the QR code on their phone to read live chat, scroll up
 * with auto-pause, tap-to-reply, browse & search Twitch / Channel / 7TV / BTTV / FFZ / ★ Most Used emotes, and send
 * messages using Xtra TV's existing logged-in session — with zero extra login or app install.
 */
object TvChatQrCompanion {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessionUrl = MutableStateFlow<String?>(null)
    val sessionUrl: StateFlow<String?> get() = _sessionUrl

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> get() = _connectedClients

    private class SseClient(
        val socket: Socket,
        val out: BufferedOutputStream,
    ) {
        val lock = Any()
        @Volatile
        var closed = false

        fun sendEvent(event: String, jsonData: String): Boolean {
            if (closed) return false
            return synchronized(lock) {
                if (closed) return@synchronized false
                try {
                    val payload = "event: $event\ndata: $jsonData\n\n".toByteArray(Charsets.UTF_8)
                    out.write(payload)
                    out.flush()
                    true
                } catch (_: Exception) {
                    close()
                    false
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { out.close() }
            runCatching { socket.close() }
        }
    }

    private val sseClients = CopyOnWriteArrayList<SseClient>()

    private const val DEFAULT_PORT = 8765

    data class ChannelTab(val login: String, val name: String, val avatarUrl: String? = null)

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var sessionToken: String = ""
    @Volatile
    private var pairedChannelLogin: String? = null
    @Volatile
    private var activeChannelName: String = ""
    @Volatile
    private var activeChannelLogin: String = ""
    @Volatile
    private var activeChannelAvatar: String? = null
    @Volatile
    private var cachedIconPng: ByteArray? = null
    @Volatile
    private var currentChatRef: WeakReference<ChatFragment>? = null
    @Volatile
    private var activeOwnerRef: WeakReference<Any>? = null
    @Volatile
    private var multiStreamChannels: List<ChannelTab> = emptyList()
    @Volatile
    private var multiStreamChatProvider: ((String) -> ChatFragment?)? = null

    private var acceptJob: Job? = null
    private var heartbeatJob: Job? = null
    private var chatCollectorJob: Job? = null
    private var detachGraceJob: Job? = null

    fun isRunning(): Boolean = serverSocket?.isClosed == false && _sessionUrl.value != null

    /**
     * Opens the QR pairing side panel (starting the local Wi-Fi server if not already active).
     * Closing the panel keeps the companion alive in the background while watching live streams.
     */
    fun openQrPanel(
        context: Context,
        uiScope: CoroutineScope,
        chatFragment: ChatFragment?,
        channelName: String?,
        channelLogin: String?,
        channelAvatar: String? = null,
        alignEnd: Boolean = true,
        owner: Any? = null,
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (owner != null) {
            activeOwnerRef = WeakReference(owner)
        }
        detachGraceJob?.cancel()
        detachGraceJob = null
        uiScope.launch {
            val url = withContext(Dispatchers.IO) {
                ensureStarted(appCtx, chatFragment, channelName, channelLogin, channelAvatar)
            }
            if (url == null) {
                TvToast.makeText(appCtx, R.string.tv_backup_no_network, Toast.LENGTH_LONG).show()
                return@launch
            }
            val qrBitmap = withContext(Dispatchers.Default) { QrCode.encode(url, 520) }
            TvDialogs.show(context) { dismiss ->
                QrCompanionSidePanel(
                    context = context,
                    initialUrl = url,
                    initialQrBitmap = qrBitmap,
                    alignEnd = alignEnd,
                    onClose = dismiss,
                    onRegenerate = {
                        uiScope.launch {
                            val newUrl = withContext(Dispatchers.IO) { regenerateLink(appCtx) }
                            if (newUrl != null) {
                                TvToast.makeText(appCtx, R.string.tv_chat_qr_regenerated, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onDisconnect = {
                        stop()
                        TvToast.makeText(appCtx, R.string.tv_chat_qr_disconnected, Toast.LENGTH_SHORT).show()
                    },
                    onRestart = {
                        uiScope.launch {
                            val newUrl = withContext(Dispatchers.IO) {
                                ensureStarted(appCtx, chatFragment, channelName, channelLogin, channelAvatar)
                            }
                            if (newUrl == null) {
                                TvToast.makeText(appCtx, R.string.tv_backup_no_network, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun QrCompanionSidePanel(
        context: Context,
        initialUrl: String,
        initialQrBitmap: Bitmap,
        alignEnd: Boolean,
        onClose: () -> Unit,
        onRegenerate: () -> Unit,
        onDisconnect: () -> Unit,
        onRestart: () -> Unit,
    ) {
        val prefs = remember(context) { context.prefs() }
        val connected by connectedClients.collectAsState()
        val liveUrl by sessionUrl.collectAsState()
        val serverActive = liveUrl != null
        val displayUrl = liveUrl ?: initialUrl
        var qrBitmap by remember { mutableStateOf(initialQrBitmap) }
        LaunchedEffect(displayUrl) {
            if (displayUrl != initialUrl || qrBitmap !== initialQrBitmap) {
                qrBitmap = withContext(Dispatchers.Default) { QrCode.encode(displayUrl, 520) }
            }
        }
        val followState = remember {
            mutableIntStateOf(if (prefs.getBoolean(C.TV_CHAT_QR_FOLLOW_CHANNEL, true)) 1 else 0)
        }
        val autoStartState = remember {
            mutableIntStateOf(if (prefs.getBoolean(C.TV_CHAT_QR_AUTO_START, false)) 1 else 0)
        }
        val autoStartReq = remember { FocusRequester() }
        val regenReq = remember { FocusRequester() }
        val disconnectReq = remember { FocusRequester() }
        val closeRequester = remember { FocusRequester() }

        SidePanel(
            title = context.getString(R.string.tv_chat_qr_title),
            alignEnd = alignEnd,
            width = 430.dp,
            scrollable = false,
            compactHeader = true,
            footer = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FooterActionChip(
                            label = context.getString(R.string.tv_chat_qr_regenerate),
                            icon = R.drawable.tv_ic_refresh,
                            requester = regenReq,
                            rightTarget = disconnectReq,
                            upTarget = autoStartReq,
                            downTarget = closeRequester,
                            onClick = {
                                if (serverActive) onRegenerate() else onRestart()
                            },
                        )
                        FooterActionChip(
                            label = context.getString(
                                if (serverActive) R.string.tv_chat_qr_disconnect else R.string.tv_chat_qr_restart
                            ),
                            icon = if (serverActive) R.drawable.tv_ic_close else R.drawable.tv_ic_refresh,
                            requester = disconnectReq,
                            leftTarget = regenReq,
                            upTarget = autoStartReq,
                            downTarget = closeRequester,
                            onClick = {
                                if (serverActive) onDisconnect() else onRestart()
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    ButtonRow(
                        label = context.getString(R.string.close_player),
                        compact = true,
                        modifier = Modifier
                            .focusRequester(closeRequester)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                    ev.key == androidx.compose.ui.input.key.Key.DirectionUp
                                ) {
                                    runCatching { regenReq.requestFocus() }
                                    true
                                } else {
                                    false
                                }
                            },
                        onClick = onClose,
                    )
                }
            },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        Modifier
                            .size(164.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (serverActive) Color.White else Color(0xFF1F2430))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (serverActive) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = displayUrl,
                                modifier = Modifier.size(148.dp),
                            )
                        } else {
                            Text(
                                text = context.getString(R.string.tv_chat_qr_disconnected),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF9CA3AF),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    when {
                                        !serverActive -> Color(0x33EF4444)
                                        connected > 0 -> Color(0x3310B981)
                                        else -> Color(0x22FFFFFF)
                                    }
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            !serverActive -> Color(0xFFF87171)
                                            connected > 0 -> Color(0xFF34D399)
                                            else -> Color(0xFF9CA3AF)
                                        }
                                    )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    !serverActive -> context.getString(R.string.tv_chat_qr_disconnected)
                                    connected > 0 -> context.getString(R.string.tv_chat_qr_connected, connected)
                                    else -> context.getString(R.string.tv_chat_qr_waiting)
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    !serverActive -> Color(0xFFFECACA)
                                    connected > 0 -> Color(0xFFA7F3D0)
                                    else -> Color(0xFFE5E7EB)
                                },
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = context.getString(R.string.tv_chat_qr_help),
                            fontSize = 12.5.sp,
                            lineHeight = 16.5.sp,
                            color = TvColors.secondaryText,
                            textAlign = TextAlign.Start,
                            maxLines = 6,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x18FFFFFF))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = displayUrl,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                SelectorRow(
                    label = context.getString(R.string.tv_chat_qr_follow_channel),
                    options = listOf(
                        context.getString(R.string.tv_chat_delay_off),
                        context.getString(R.string.tv_option_on),
                    ),
                    selected = followState.intValue,
                    icon = R.drawable.tv_ic_refresh,
                    compact = true,
                ) {
                    followState.intValue = it
                    prefs.edit { putBoolean(C.TV_CHAT_QR_FOLLOW_CHANNEL, it == 1) }
                }
                Spacer(Modifier.height(6.dp))
                SelectorRow(
                    label = context.getString(R.string.tv_chat_qr_auto_start),
                    options = listOf(
                        context.getString(R.string.tv_chat_delay_off),
                        context.getString(R.string.tv_option_on),
                    ),
                    selected = autoStartState.intValue,
                    icon = R.drawable.tv_ic_qr,
                    compact = true,
                    modifier = Modifier.tvPanelLastRow(autoStartReq, regenReq),
                ) {
                    autoStartState.intValue = it
                    prefs.edit { putBoolean(C.TV_CHAT_QR_AUTO_START, it == 1) }
                }
            }
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos {}
                runCatching { closeRequester.requestFocus() }
            }
        }
    }

    /**
     * Called when a live [ChatFragment] attaches in the player (initial stream or channel zap).
     * Zero-cost no-op when the QR server is not running and auto-start is disabled.
     */
    fun onLiveChatAttached(
        context: Context,
        chatFragment: ChatFragment,
        channelName: String?,
        channelLogin: String?,
        channelAvatar: String? = null,
        owner: Any? = null,
    ) {
        detachGraceJob?.cancel()
        detachGraceJob = null
        if (owner != null) {
            activeOwnerRef = WeakReference(owner)
        }
        multiStreamChannels = emptyList()
        multiStreamChatProvider = null
        val appCtx = context.applicationContext
        if (!isRunning()) {
            if (appCtx.prefs().getBoolean(C.TV_CHAT_QR_AUTO_START, false)) {
                appContext = appCtx
                scope.launch {
                    ensureStarted(appCtx, chatFragment, channelName, channelLogin, channelAvatar)
                }
            }
            return
        }
        appContext = appCtx

        val prevChat = currentChatRef?.get()
        val login = channelLogin?.takeIf { it.isNotBlank() } ?: chatFragment.getQrChannelInfo().second.orEmpty()
        val name = channelName?.takeIf { it.isNotBlank() } ?: chatFragment.getQrChannelInfo().third ?: login
        val followEnabled = appCtx.prefs().getBoolean(C.TV_CHAT_QR_FOLLOW_CHANNEL, true)
        val sameChannel = login.equals(pairedChannelLogin, ignoreCase = true)

        if (!followEnabled && !pairedChannelLogin.isNullOrBlank() && login.isNotBlank() && !sameChannel) {
            stop()
            return
        }

        val channelChanged = !login.equals(activeChannelLogin, ignoreCase = true) || prevChat !== chatFragment
        activeChannelLogin = login
        activeChannelName = name
        val cleanAvatar = channelAvatar?.takeIf { it.isNotBlank() }
        if (channelChanged) {
            activeChannelAvatar = cleanAvatar
        } else if (cleanAvatar != null) {
            activeChannelAvatar = cleanAvatar
        }
        if (followEnabled && login.isNotBlank()) {
            pairedChannelLogin = login
        }
        attachChatCollectors(chatFragment)
        if (channelChanged) {
            scope.launch {
                // Give the newly attached fragment a short beat to initialize its initial history/emotes
                delay(250L)
                broadcast("channel", buildStateJson(appCtx).toString())
            }
        }
    }

    /** Updates the streamer profile picture on connected phones if it resolved asynchronously after attach. */
    fun updateChannelAvatar(channelLogin: String?, avatarUrl: String?) {
        if (!isRunning()) return
        val url = avatarUrl?.takeIf { it.isNotBlank() } ?: return
        val login = channelLogin?.takeIf { it.isNotBlank() }
        if (login != null && activeChannelLogin.isNotBlank() && !login.equals(activeChannelLogin, ignoreCase = true)) {
            return
        }
        if (url == activeChannelAvatar) return
        activeChannelAvatar = url
        scope.launch {
            broadcast(
                "avatar",
                JSONObject()
                    .put("login", activeChannelLogin)
                    .put("avatarUrl", url)
                    .toString(),
            )
        }
    }

    /**
     * Keeps the active QR phone session synchronized with all streams in Multi-Stream, exposing channel tabs
     * on the phone so the user can switch chats in 1 tap without re-scanning the QR code.
     */
    fun updateMultiStreamChannels(
        context: Context,
        channels: List<ChannelTab>,
        preferredLogin: String? = null,
        owner: Any? = null,
        chatProvider: (String) -> ChatFragment?,
    ) {
        val clean = channels.filter { it.login.isNotBlank() }.distinctBy { it.login.lowercase() }
        if (owner != null && clean.isNotEmpty()) {
            activeOwnerRef = WeakReference(owner)
        }
        multiStreamChannels = clean
        multiStreamChatProvider = if (clean.isNotEmpty()) chatProvider else null
        if (clean.isEmpty()) return
        val appCtx = context.applicationContext
        if (!isRunning()) {
            if (!appCtx.prefs().getBoolean(C.TV_CHAT_QR_AUTO_START, false)) return
            appContext = appCtx
            val target = preferredLogin?.let { p -> clean.firstOrNull { it.login.equals(p, ignoreCase = true) } }
                ?: clean.first()
            scope.launch {
                val chat = withContext(Dispatchers.Main) {
                    runCatching { chatProvider(target.login) }.getOrNull()
                }
                ensureStarted(appCtx, chat, target.name.ifBlank { target.login }, target.login, target.avatarUrl)
            }
            return
        }
        detachGraceJob?.cancel()
        detachGraceJob = null
        appContext = appCtx

        val prevLogin = activeChannelLogin
        val target = clean.firstOrNull { it.login.equals(prevLogin, ignoreCase = true) }
            ?: preferredLogin?.let { p -> clean.firstOrNull { it.login.equals(p, ignoreCase = true) } }
            ?: clean.first()
        activeChannelLogin = target.login
        activeChannelName = target.name.ifBlank { target.login }
        activeChannelAvatar = target.avatarUrl?.takeIf { it.isNotBlank() }
        pairedChannelLogin = target.login

        scope.launch {
            val chat = withContext(Dispatchers.Main) {
                runCatching { chatProvider(target.login) }.getOrNull()
            }
            if (chat != null) {
                attachChatCollectors(chat)
                delay(250L)
            }
            broadcast("channel", buildStateJson(appCtx).toString())
        }
    }

    /**
     * Automatically switches the phone's active Multi-Stream chat tab when TV focus settles on another streamer
     * (while still allowing the user to tap any other tab manually on their phone at any time).
     */
    fun onMultiStreamFocusChanged(context: Context, focusedLogin: String?) {
        if (!isRunning() || focusedLogin.isNullOrBlank()) return
        val appCtx = context.applicationContext
        if (!appCtx.prefs().getBoolean(C.TV_CHAT_QR_FOLLOW_CHANNEL, true)) return
        if (focusedLogin.equals(activeChannelLogin, ignoreCase = true)) return
        val target = multiStreamChannels.firstOrNull { it.login.equals(focusedLogin, ignoreCase = true) } ?: return
        val provider = multiStreamChatProvider ?: return
        activeChannelLogin = target.login
        activeChannelName = target.name.ifBlank { target.login }
        activeChannelAvatar = target.avatarUrl?.takeIf { it.isNotBlank() }
        pairedChannelLogin = target.login
        scope.launch {
            val prevChat = currentChatRef?.get()
            val nextChat = withContext(Dispatchers.Main) {
                runCatching { provider(target.login) }.getOrNull()
            } ?: return@launch
            attachChatCollectors(nextChat)
            if (prevChat !== nextChat) {
                delay(250L)
            }
            broadcast("channel", buildStateJson(appCtx).toString())
        }
    }

    /**
     * Schedules a short grace shutdown when the owning player view is destroyed so transitions between
     * Single Stream and Multi-Stream (or channel zapping) never kill the session prematurely.
     */
    fun scheduleDetach(owner: Any?) {
        if (!isRunning()) return
        val currentOwner = activeOwnerRef?.get()
        if (owner != null && currentOwner != null && currentOwner !== owner) return
        detachGraceJob?.cancel()
        detachGraceJob = scope.launch {
            delay(3_500L)
            val recheckOwner = activeOwnerRef?.get()
            if (owner == null || recheckOwner == null || recheckOwner === owner) {
                stop()
            }
        }
    }

    /**
     * Rotates the persistent QR secret token, immediately disconnecting any previously paired phones.
     * Reuses the active listening port so the QR code updates instantaneously.
     */
    @Synchronized
    fun regenerateLink(appCtx: Context): String? {
        val newToken = generateToken()
        appCtx.prefs().edit { putString(C.TV_CHAT_QR_TOKEN, newToken) }
        val revokeJson = JSONObject().put("reason", "revoked").toString()
        sseClients.forEach { client ->
            runCatching { client.sendEvent("closed", revokeJson) }
            client.close()
        }
        sseClients.clear()
        _connectedClients.value = 0

        val server = serverSocket
        if (server == null || server.isClosed) {
            sessionToken = newToken
            return null
        }
        val ip = findLocalIpAddress() ?: return null
        sessionToken = newToken
        val url = "http://$ip:${server.localPort}/$newToken"
        _sessionUrl.value = url
        return url
    }

    /** Immediately stops the local Wi-Fi server and closes all phone SSE connections. */
    fun stop() {
        detachGraceJob?.cancel()
        detachGraceJob = null
        chatCollectorJob?.cancel()
        chatCollectorJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        acceptJob?.cancel()
        acceptJob = null

        val endJson = JSONObject().put("reason", "closed").toString()
        sseClients.forEach { client ->
            runCatching { client.sendEvent("closed", endJson) }
            client.close()
        }
        sseClients.clear()
        _connectedClients.value = 0

        runCatching { serverSocket?.close() }
        serverSocket = null
        sessionToken = ""
        pairedChannelLogin = null
        activeChannelAvatar = null
        currentChatRef = null
        activeOwnerRef = null
        multiStreamChannels = emptyList()
        multiStreamChatProvider = null
        _sessionUrl.value = null
    }

    @Synchronized
    private fun ensureStarted(
        appCtx: Context,
        chatFragment: ChatFragment?,
        channelName: String?,
        channelLogin: String?,
        channelAvatar: String? = null,
    ): String? {
        detachGraceJob?.cancel()
        detachGraceJob = null

        val info = chatFragment?.getQrChannelInfo()
        val resolvedLogin = channelLogin?.takeIf { it.isNotBlank() } ?: info?.second.orEmpty()
        val resolvedName = channelName?.takeIf { it.isNotBlank() } ?: info?.third ?: resolvedLogin
        activeChannelLogin = resolvedLogin
        activeChannelName = resolvedName
        val cleanAvatar = channelAvatar?.takeIf { it.isNotBlank() }
        if (cleanAvatar != null) {
            activeChannelAvatar = cleanAvatar
        }
        if (resolvedLogin.isNotBlank()) {
            pairedChannelLogin = resolvedLogin
        }
        if (chatFragment != null) {
            attachChatCollectors(chatFragment)
        }

        val existingUrl = _sessionUrl.value
        if (serverSocket?.isClosed == false && existingUrl != null) {
            return existingUrl
        }

        val ip = findLocalIpAddress() ?: return null
        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(DEFAULT_PORT))
            }
        }.getOrElse {
            runCatching { ServerSocket(0) }.getOrNull()
        } ?: return null
        val token = getOrCreateToken(appCtx)
        serverSocket = server
        sessionToken = token
        val url = "http://$ip:${server.localPort}/$token"
        _sessionUrl.value = url

        acceptJob?.cancel()
        acceptJob = scope.launch {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { handleHttpClient(client, appCtx) }
            }
        }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (serverSocket?.isClosed == false) {
                delay(15_000L)
                broadcast("ping", "{}")
            }
        }
        return url
    }

    private fun attachChatCollectors(chatFragment: ChatFragment) {
        val prev = currentChatRef?.get()
        if (prev === chatFragment && chatCollectorJob?.isActive == true) return
        currentChatRef = WeakReference(chatFragment)
        chatCollectorJob?.cancel()
        chatCollectorJob = scope.launch {
            launch {
                chatFragment.observeNewMessagesForQr()?.collect { msg ->
                    val json = serializeMessage(msg, chatFragment) ?: return@collect
                    broadcast("msg", json.toString())
                }
            }
            launch {
                chatFragment.observeUpdatedMessagesForQr()?.collect { updated ->
                    val ids = JSONArray()
                    updated.forEach { m ->
                        if (m.deleted && !m.id.isNullOrBlank()) ids.put(m.id)
                    }
                    if (ids.length() > 0) {
                        broadcast("deleted", JSONObject().put("ids", ids).toString())
                    }
                }
            }
            launch {
                chatFragment.observeEmoteUpdates()?.collect {
                    delay(350L)
                    val ctx = appContext ?: return@collect
                    broadcast("emotes", JSONObject().put("emoteGroups", buildEmoteGroupsJson(ctx, chatFragment)).toString())
                }
            }
        }
    }

    private fun broadcast(event: String, jsonData: String) {
        if (sseClients.isEmpty()) {
            if (_connectedClients.value != 0) _connectedClients.value = 0
            return
        }
        val dead = mutableListOf<SseClient>()
        for (c in sseClients) {
            if (!c.sendEvent(event, jsonData)) {
                dead.add(c)
            }
        }
        if (dead.isNotEmpty()) {
            sseClients.removeAll(dead.toSet())
        }
        val count = sseClients.size
        if (_connectedClients.value != count) {
            _connectedClients.value = count
        }
    }

    private suspend fun handleHttpClient(socket: Socket, appCtx: Context) {
        var keepOpenForSse = false
        try {
            socket.soTimeout = 6_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1].substringBefore('?')

            var contentLength = 0
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim()
                    if (name.equals("Content-Length", ignoreCase = true)) {
                        contentLength = line.substring(colon + 1).trim().toIntOrNull()?.coerceIn(0, 16_384) ?: 0
                    }
                }
            }

            if (method == "GET" && (rawPath == "/icon.png" || rawPath == "/favicon.ico")) {
                val icon = getAppIconPng(appCtx)
                if (icon != null) {
                    writeResponse(socket, 200, "image/png", icon, cacheControl = "public, max-age=86400")
                } else {
                    writeResponse(socket, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
                }
                return
            }

            val token = sessionToken
            val prefix = "/$token"
            if (token.isEmpty() || (rawPath != prefix && !rawPath.startsWith("$prefix/"))) {
                writeResponse(socket, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
                return
            }
            val subPath = rawPath.removePrefix(prefix).ifEmpty { "/" }

            when {
                method == "GET" && subPath == "/" -> {
                    val html = buildMobileHtml(appCtx, token).toByteArray(Charsets.UTF_8)
                    writeResponse(socket, 200, "text/html; charset=utf-8", html)
                }
                method == "GET" && subPath == "/manifest.json" -> {
                    val manifest = buildManifestJson(token).toString().toByteArray(Charsets.UTF_8)
                    writeResponse(socket, 200, "application/manifest+json; charset=utf-8", manifest)
                }
                method == "GET" && subPath == "/icon.png" -> {
                    val icon = getAppIconPng(appCtx)
                    if (icon != null) {
                        writeResponse(socket, 200, "image/png", icon, cacheControl = "public, max-age=86400")
                    } else {
                        writeResponse(socket, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
                    }
                }
                method == "GET" && subPath == "/api/state" -> {
                    val body = buildStateJson(appCtx).toString().toByteArray(Charsets.UTF_8)
                    writeResponse(socket, 200, "application/json; charset=utf-8", body)
                }
                method == "GET" && subPath == "/api/events" -> {
                    socket.soTimeout = 0
                    val out = BufferedOutputStream(socket.getOutputStream())
                    val headers = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/event-stream; charset=utf-8\r\n")
                        append("Cache-Control: no-cache, no-store\r\n")
                        append("Connection: keep-alive\r\n")
                        append("X-Accel-Buffering: no\r\n\r\n")
                    }
                    out.write(headers.toByteArray(Charsets.UTF_8))
                    out.flush()
                    val sse = SseClient(socket, out)
                    sseClients.add(sse)
                    _connectedClients.value = sseClients.size
                    sse.sendEvent("ping", "{}")
                    keepOpenForSse = true
                    // Block until client closes socket or server disconnects
                    try {
                        while (!sse.closed && !socket.isClosed) {
                            val b = input.read()
                            if (b == -1) break
                        }
                    } catch (_: Exception) {
                    } finally {
                        sse.close()
                        sseClients.remove(sse)
                        _connectedClients.value = sseClients.size
                    }
                }
                method == "POST" && subPath == "/api/send" -> {
                    val bodyBytes = readFixedBytes(input, contentLength)
                    val reqJson = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull()
                    val text = reqJson?.optString("text", "")?.trim().orEmpty()
                    val replyId = reqJson?.optString("replyId", "")?.trim()?.takeIf { it.isNotEmpty() }
                    val chat = currentChatRef?.get()
                    if (chat == null || !chat.isAdded) {
                        writeResponse(socket, 409, "application/json; charset=utf-8", JSONObject().put("ok", false).put("error", "Chat unavailable").toString().toByteArray())
                        return
                    }
                    if (!chat.isUserLoggedInForQr()) {
                        writeResponse(socket, 403, "application/json; charset=utf-8", JSONObject().put("ok", false).put("error", "Read-only").toString().toByteArray())
                        return
                    }
                    if (text.isEmpty() || text.length > 500) {
                        writeResponse(socket, 400, "application/json; charset=utf-8", JSONObject().put("ok", false).put("error", "Invalid message").toString().toByteArray())
                        return
                    }
                    val sent = withContext(Dispatchers.Main) {
                        runCatching { chat.sendExternalMessage(text, replyId) }.getOrDefault(false)
                    }
                    writeResponse(socket, 200, "application/json; charset=utf-8", JSONObject().put("ok", sent).toString().toByteArray())
                }
                method == "POST" && subPath == "/api/channel" -> {
                    val bodyBytes = readFixedBytes(input, contentLength)
                    val reqJson = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull()
                    val login = reqJson?.optString("login", "")?.trim().orEmpty()
                    val target = multiStreamChannels.firstOrNull { it.login.equals(login, ignoreCase = true) }
                    val provider = multiStreamChatProvider
                    if (target == null || provider == null) {
                        writeResponse(socket, 404, "application/json; charset=utf-8", JSONObject().put("ok", false).toString().toByteArray())
                        return
                    }
                    val prevChat = currentChatRef?.get()
                    val nextChat = withContext(Dispatchers.Main) {
                        runCatching { provider(target.login) }.getOrNull()
                    }
                    if (nextChat == null) {
                        writeResponse(socket, 409, "application/json; charset=utf-8", JSONObject().put("ok", false).toString().toByteArray())
                        return
                    }
                    activeChannelLogin = target.login
                    activeChannelName = target.name.ifBlank { target.login }
                    activeChannelAvatar = target.avatarUrl?.takeIf { it.isNotBlank() }
                    pairedChannelLogin = target.login
                    attachChatCollectors(nextChat)
                    if (prevChat !== nextChat) {
                        delay(250L)
                    }
                    val stateJson = buildStateJson(appCtx)
                    broadcast("channel", stateJson.toString())
                    writeResponse(socket, 200, "application/json; charset=utf-8", JSONObject().put("ok", true).toString().toByteArray())
                }
                else -> {
                    writeResponse(socket, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
                }
            }
        } catch (_: Exception) {
        } finally {
            if (!keepOpenForSse) {
                runCatching { socket.close() }
            }
        }
    }

    private fun buildStateJson(appCtx: Context): JSONObject {
        val chat = currentChatRef?.get()
        val obj = JSONObject()
        obj.put("channelName", activeChannelName.ifBlank { activeChannelLogin })
        obj.put("channelLogin", activeChannelLogin)
        obj.put("avatarUrl", activeChannelAvatar.orEmpty())
        obj.put("canSend", chat?.isUserLoggedInForQr() == true)
        val chArr = JSONArray()
        multiStreamChannels.forEach { ch ->
            chArr.put(
                JSONObject()
                    .put("login", ch.login)
                    .put("name", ch.name.ifBlank { ch.login })
                    .put("avatarUrl", ch.avatarUrl.orEmpty()),
            )
        }
        obj.put("channels", chArr)
        val msgsArr = JSONArray()
        if (chat != null && chat.isAdded) {
            chat.getRecentMessagesForQr(80).forEach { msg ->
                serializeMessage(msg, chat)?.let { msgsArr.put(it) }
            }
        }
        obj.put("messages", msgsArr)
        obj.put("emoteGroups", if (chat != null && chat.isAdded) buildEmoteGroupsJson(appCtx, chat) else JSONArray())
        return obj
    }

    private fun buildEmoteGroupsJson(appCtx: Context, chat: ChatFragment): JSONArray {
        val groups = runCatching { chat.getTvEmoteGroups() }.getOrDefault(emptyList())
        val mostUsedLabel = appCtx.getString(R.string.tv_emotes_most_used)
        val arr = JSONArray()
        groups.forEach { (label, emotes) ->
            val isMost = label == mostUsedLabel
            val gObj = JSONObject()
            gObj.put("label", if (isMost) "★ $label" else label)
            gObj.put("isMostUsed", isMost)
            val listArr = JSONArray()
            emotes.take(260).forEach { e ->
                val code = e.name ?: return@forEach
                val url = e.url2x ?: e.url1x ?: e.url3x ?: e.url4x ?: return@forEach
                val eObj = JSONObject()
                eObj.put("code", code)
                eObj.put("url", url)
                eObj.put("provider", TvEmoteUsage.providerLabel(e))
                eObj.put("color", String.format("#%06X", (TvEmoteUsage.providerColorArgb(e) and 0xFFFFFFL).toInt()))
                if (isMost) {
                    eObj.put("count", TvEmoteUsage.countOf(appCtx, code))
                }
                listArr.put(eObj)
            }
            gObj.put("emotes", listArr)
            arr.put(gObj)
        }
        return arr
    }

    private fun serializeMessage(msg: ChatMessage, chat: ChatFragment): JSONObject? {
        if (msg.type == ChatMessage.REPLY_MESSAGE) return null
        val isSystem = msg.type == ChatMessage.SYSTEM_MESSAGE || msg.type == ChatMessage.NOTICE_MESSAGE
        val rawText = msg.message.orEmpty()
        val sysText = msg.systemMsg?.takeIf { it.isNotBlank() }
        if (rawText.isBlank() && sysText == null) return null

        val obj = JSONObject()
        obj.put("id", msg.id ?: "")
        obj.put("isSystem", isSystem)
        obj.put("isAction", msg.isAction)
        obj.put("deleted", msg.deleted)
        if (sysText != null) obj.put("systemMsg", sysText)
        obj.put("user", msg.userName?.takeIf { it.isNotBlank() } ?: msg.userLogin.orEmpty())
        obj.put("login", msg.userLogin.orEmpty())
        obj.put("color", msg.color?.takeIf { it.startsWith("#") } ?: "#BF94FF")
        obj.put("ts", msg.timestamp ?: System.currentTimeMillis())

        val badgesArr = JSONArray()
        msg.badges?.forEach { b ->
            chat.resolveBadgeForQr(b)?.let { (url, title) ->
                badgesArr.put(JSONObject().put("url", url).put("title", title ?: b.setId))
            }
        }
        obj.put("badges", badgesArr)

        msg.reply?.let { r ->
            val rUser = r.userName?.takeIf { it.isNotBlank() } ?: r.userLogin.orEmpty()
            if (rUser.isNotBlank() || !r.message.isNullOrBlank()) {
                obj.put(
                    "reply",
                    JSONObject()
                        .put("id", r.threadParentId ?: "")
                        .put("user", rUser)
                        .put("text", r.message.orEmpty()),
                )
            }
        }

        val thirdPartyMap = chat.getThirdPartyEmoteUrlMapForQr()
        obj.put("parts", tokenizeMessageParts(rawText, msg.emotes, thirdPartyMap))
        obj.put("rawText", rawText)
        return obj
    }

    private fun tokenizeMessageParts(
        text: String,
        twitchEmotes: List<com.github.andreyasadchy.xtra.model.chat.TwitchEmote>?,
        thirdPartyMap: Map<String, String>,
    ): JSONArray {
        val parts = JSONArray()
        if (text.isEmpty()) return parts

        fun appendPlainWithThirdParty(segment: String) {
            if (segment.isEmpty()) return
            if (thirdPartyMap.isEmpty()) {
                parts.put(JSONObject().put("t", "text").put("v", segment))
                return
            }
            val sb = StringBuilder()
            var i = 0
            while (i < segment.length) {
                if (segment[i].isWhitespace()) {
                    sb.append(segment[i])
                    i++
                } else {
                    var j = i
                    while (j < segment.length && !segment[j].isWhitespace()) j++
                    val word = segment.substring(i, j)
                    val url = thirdPartyMap[word]
                    if (url != null) {
                        if (sb.isNotEmpty()) {
                            parts.put(JSONObject().put("t", "text").put("v", sb.toString()))
                            sb.setLength(0)
                        }
                        parts.put(JSONObject().put("t", "emote").put("code", word).put("url", url))
                    } else {
                        sb.append(word)
                    }
                    i = j
                }
            }
            if (sb.isNotEmpty()) {
                parts.put(JSONObject().put("t", "text").put("v", sb.toString()))
            }
        }

        val sorted = twitchEmotes
            ?.filter { it.begin >= 0 && it.end >= it.begin && it.end < text.length }
            ?.sortedBy { it.begin }
            .orEmpty()

        var cursor = 0
        for (em in sorted) {
            if (em.begin < cursor) continue
            if (em.begin > cursor) {
                appendPlainWithThirdParty(text.substring(cursor, em.begin))
            }
            val endExclusive = (em.end + 1).coerceAtMost(text.length)
            val code = em.name?.takeIf { it.isNotBlank() } ?: text.substring(em.begin, endExclusive)
            val url = em.url2x ?: em.url1x ?: em.url3x ?: em.url4x
            if (!url.isNullOrBlank()) {
                parts.put(JSONObject().put("t", "emote").put("code", code).put("url", url))
            } else {
                parts.put(JSONObject().put("t", "text").put("v", code))
            }
            cursor = endExclusive
        }
        if (cursor < text.length) {
            appendPlainWithThirdParty(text.substring(cursor))
        }
        return parts
    }

    private fun buildMobileHtml(appCtx: Context, token: String): String {
        val i18n = JSONObject()
            .put("hint", appCtx.getString(R.string.tv_message_hint))
            .put("send", appCtx.getString(R.string.tv_send))
            .put("readonly", appCtx.getString(R.string.tv_chat_qr_readonly))
            .put("paused", appCtx.getString(R.string.tv_chat_qr_paused))
            .put("replyingTo", appCtx.getString(R.string.tv_chat_qr_replying_to, "%s"))
            .put("searchEmotes", appCtx.getString(R.string.tv_chat_qr_search_emotes))
            .put("switched", appCtx.getString(R.string.tv_chat_qr_switched, "%s"))
            .put("ended", appCtx.getString(R.string.tv_chat_qr_session_ended))
            .put("mostUsedEmpty", appCtx.getString(R.string.tv_emotes_most_used_empty))
            .put("reconnect", appCtx.getString(R.string.tv_chat_qr_reconnect))
            .toString()

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover"/>
<meta name="theme-color" content="#14131C"/>
<meta name="mobile-web-app-capable" content="yes"/>
<meta name="apple-mobile-web-app-capable" content="yes"/>
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent"/>
<meta name="apple-mobile-web-app-title" content="XtraPlus Chat"/>
<link rel="manifest" href="/$token/manifest.json"/>
<link rel="icon" type="image/png" sizes="192x192" href="/$token/icon.png"/>
<link rel="apple-touch-icon" sizes="192x192" href="/$token/icon.png"/>
<title>XtraPlus Chat</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;padding:0;height:100%;background:#0E0E13;color:#F3F2F8;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;overflow:hidden}
.app{display:flex;flex-direction:column;height:100dvh;max-width:720px;margin:0 auto;background:#0E0E13}
.hdr{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:10px 14px;background:#15141E;border-bottom:1px solid rgba(255,255,255,.09);flex-shrink:0}
.hdr-left{display:flex;align-items:center;gap:10px;min-width:0}
.av{width:34px;height:34px;border-radius:50%;background:linear-gradient(135deg,#9147FF,#5C16C5);display:flex;align-items:center;justify-content:center;font-weight:800;font-size:14px;flex-shrink:0;overflow:hidden;position:relative}
.av img{width:100%;height:100%;object-fit:cover;display:block}
.ch-wrap{min-width:0}
.ch-row{display:flex;align-items:center;gap:6px}
.ch-name{font-weight:700;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dot{width:8px;height:8px;border-radius:50%;background:#34D399;flex-shrink:0}
.dot.off{background:#F59E0B}
.ch-sub{font-size:11px;color:rgba(255,255,255,.55)}
.hdr-btns{display:flex;gap:6px;flex-shrink:0;align-items:center}
.chip{background:rgba(255,255,255,.10);border:none;color:#EEE;border-radius:9px;padding:6px 10px;font-size:12px;font-weight:700;cursor:pointer}
.chip.on{background:#9147FF;color:#fff}
.chip.rec{background:rgba(245,158,11,.22);color:#FCD34D;border:1px solid rgba(245,158,11,.42);display:none}
.chip.rec.show{display:inline-flex}
.feed-wrap{position:relative;flex:1;min-height:0;display:flex;flex-direction:column}
.feed{flex:1;overflow-y:auto;padding:10px 12px;display:flex;flex-direction:column;gap:6px;font-size:14px;line-height:1.42}
.feed.lg{font-size:16px}
.msg{padding:6px 9px;border-radius:10px;word-break:break-word;transition:background .15s}
.msg:active{background:rgba(145,71,255,.18)}
.msg.sys{background:rgba(145,71,255,.12);border-left:3px solid #9147FF;color:#DCD2F8;font-size:12.5px}
.msg.del{opacity:.38;text-decoration:line-through}
.rep-line{font-size:11.5px;color:rgba(255,255,255,.52);margin-bottom:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.bdg{height:17px;width:auto;vertical-align:middle;margin-right:4px;border-radius:3px}
.usr{font-weight:700;margin-right:5px}
.em{height:26px;width:auto;vertical-align:middle;margin:-3px 2px}
.mention{background:rgba(145,71,255,.24);color:#E5D4FF;padding:0 4px;border-radius:5px;font-weight:600}
.pause-btn{position:absolute;bottom:10px;left:50%;transform:translateX(-50%);background:#9147FF;color:#fff;border:none;border-radius:999px;padding:7px 14px;font-size:12.5px;font-weight:700;box-shadow:0 8px 24px rgba(0,0,0,.55);display:none;align-items:center;gap:6px;cursor:pointer;z-index:5}
.pause-btn.show{display:flex}
.pause-cnt{background:rgba(0,0,0,.32);padding:1px 7px;border-radius:99px;font-size:11px}
.rep-bar{display:none;align-items:center;justify-content:space-between;gap:8px;padding:7px 12px;background:rgba(145,71,255,.18);border-top:1px solid rgba(145,71,255,.35);font-size:12px;color:#E9DCFF;flex-shrink:0}
.rep-bar.show{display:flex}
.rep-bar span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.xbtn{background:rgba(255,255,255,.14);border:none;color:#fff;width:24px;height:24px;border-radius:50%;font-size:12px;cursor:pointer;flex-shrink:0}
.drawer{display:none;flex-direction:column;height:255px;background:#15141E;border-top:1px solid rgba(255,255,255,.10);flex-shrink:0}
.drawer.show{display:flex}
.d-top{padding:8px 10px 6px;border-bottom:1px solid rgba(255,255,255,.07);display:flex;flex-direction:column;gap:7px}
.d-srch-row{display:flex;gap:6px}
.d-srch{flex:1;background:rgba(255,255,255,.09);border:1px solid rgba(255,255,255,.14);border-radius:10px;padding:7px 10px;color:#fff;font-size:13px;outline:none}
.d-srch:focus{border-color:#9147FF}
.tabs{display:flex;gap:6px;overflow-x:auto;scrollbar-width:none}
.tabs::-webkit-scrollbar{display:none}
.tab{background:rgba(255,255,255,.10);border:none;color:rgba(255,255,255,.78);border-radius:99px;padding:5px 11px;font-size:12px;font-weight:700;white-space:nowrap;cursor:pointer;flex-shrink:0}
.tab.on{background:#9147FF;color:#fff}
.grid{flex:1;overflow-y:auto;padding:8px 10px;display:grid;grid-template-columns:repeat(auto-fill,minmax(62px,1fr));gap:6px;align-content:start}
.ec{position:relative;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:6px 4px;border-radius:11px;background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.08);cursor:pointer}
.ec:active{background:rgba(145,71,255,.32)}
.ec img{height:30px;max-width:48px;object-fit:contain}
.ec .ec-n{font-size:10px;color:rgba(255,255,255,.76);margin-top:3px;max-width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ec .ec-c{font-size:9.5px;background:rgba(0,0,0,.45);color:#D8B4FE;padding:0 5px;border-radius:99px;margin-top:2px;font-weight:700}
.ec .ec-dot{position:absolute;top:4px;right:4px;width:5px;height:5px;border-radius:50%}
.bar{display:flex;align-items:center;gap:8px;padding:9px 10px calc(9px + env(safe-area-inset-bottom,0px));background:#161520;border-top:1px solid rgba(255,255,255,.10);flex-shrink:0}
.ibtn{width:40px;height:40px;border-radius:50%;border:none;background:rgba(255,255,255,.11);color:#fff;display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0;font-size:18px}
.ibtn.on{background:#9147FF}
.inp{flex:1;height:40px;border-radius:999px;border:1px solid rgba(255,255,255,.14);background:rgba(255,255,255,.09);color:#fff;padding:0 14px;font-size:14px;outline:none;min-width:0}
.inp:focus{border-color:#BF94FF}
.sbtn{width:40px;height:40px;border-radius:50%;border:none;background:#9147FF;color:#fff;display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0}
.sbtn:disabled{opacity:.45}
.ro{padding:11px 14px calc(11px + env(safe-area-inset-bottom,0px));background:#161520;border-top:1px solid rgba(255,255,255,.10);text-align:center;font-size:12.5px;color:rgba(255,255,255,.65);display:none;flex-shrink:0}
.ro.show{display:block}
.ch-tabs{display:none;gap:6px;padding:7px 12px;background:#12111A;border-bottom:1px solid rgba(255,255,255,.08);overflow-x:auto;scrollbar-width:none;flex-shrink:0}
.ch-tabs::-webkit-scrollbar{display:none}
.ch-tabs.show{display:flex}
.ch-tab{background:rgba(255,255,255,.09);border:1px solid rgba(255,255,255,.10);color:rgba(255,255,255,.82);border-radius:999px;padding:5px 12px;font-size:12px;font-weight:700;white-space:nowrap;cursor:pointer;flex-shrink:0}
.ch-tab.on{background:#9147FF;border-color:#BF94FF;color:#fff}
</style>
</head>
<body>
<div class="app">
  <div class="hdr">
    <div class="hdr-left">
      <div class="av" id="av">X+</div>
      <div class="ch-wrap">
        <div class="ch-row"><span class="ch-name" id="chName">XtraPlus</span><span class="dot" id="dot"></span></div>
        <div class="ch-sub" id="chSub">XtraPlus · Wi-Fi</div>
      </div>
    </div>
    <div class="hdr-btns">
      <button class="chip rec" id="btnReconn" type="button"></button>
      <button class="chip" id="btnAa" type="button">Aa</button>
      <button class="chip on" id="btnBdg" type="button">Badges</button>
    </div>
  </div>
  <div class="ch-tabs" id="chTabs"></div>

  <div class="feed-wrap">
    <div class="feed" id="feed"></div>
    <button class="pause-btn" id="pauseBtn" type="button"><span id="pauseLbl"></span><span class="pause-cnt" id="pauseCnt">0</span></button>
  </div>

  <div class="rep-bar" id="repBar">
    <span id="repLbl"></span>
    <button class="xbtn" id="repX" type="button">✕</button>
  </div>

  <div class="drawer" id="drawer">
    <div class="d-top">
      <div class="d-srch-row">
        <input class="d-srch" id="srch" type="search" autocomplete="off"/>
        <button class="xbtn" id="drwX" type="button">✕</button>
      </div>
      <div class="tabs" id="tabs"></div>
    </div>
    <div class="grid" id="grid"></div>
  </div>

  <form class="bar" id="form">
    <button class="ibtn" id="btnEm" type="button" aria-label="Emotes">☺</button>
    <input class="inp" id="inp" type="text" maxlength="500" autocomplete="off"/>
    <button class="sbtn" id="btnSend" type="submit" aria-label="Send">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
    </button>
  </form>
  <div class="ro" id="ro"></div>
</div>
<script>
const BASE = "/$token";
const T = $i18n;
const feed = document.getElementById("feed");
const pauseBtn = document.getElementById("pauseBtn");
const pauseLbl = document.getElementById("pauseLbl");
const pauseCnt = document.getElementById("pauseCnt");
const repBar = document.getElementById("repBar");
const repLbl = document.getElementById("repLbl");
const drawer = document.getElementById("drawer");
const tabsEl = document.getElementById("tabs");
const gridEl = document.getElementById("grid");
const srchEl = document.getElementById("srch");
const formEl = document.getElementById("form");
const inpEl = document.getElementById("inp");
const btnEm = document.getElementById("btnEm");
const roEl = document.getElementById("ro");
const dotEl = document.getElementById("dot");
const chNameEl = document.getElementById("chName");
const avEl = document.getElementById("av");
const chTabsEl = document.getElementById("chTabs");
const btnReconn = document.getElementById("btnReconn");

inpEl.placeholder = T.hint;
srchEl.placeholder = T.searchEmotes;
pauseLbl.textContent = "↓ " + T.paused;
roEl.textContent = T.readonly;
btnReconn.textContent = T.reconnect;

let showBadges = true;
let paused = false;
let missed = 0;
let canSend = false;
let emoteGroups = [];
let channels = [];
let activeChannelLogin = "";
let activeChannelName = "XtraPlus";
let activeTab = 0;
let reply = null;
let es = null;
let ended = false;
let revoked = false;
let reconnTimer = null;
const msgById = new Map();

function esc(s){return (s||"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");}
function fmt(tpl,val){return tpl.replace("%s",val);}

function renderChannelTabs(){
  chTabsEl.innerHTML = "";
  if(!channels || channels.length <= 1){
    chTabsEl.classList.remove("show");
    return;
  }
  chTabsEl.classList.add("show");
  channels.forEach(ch=>{
    const b = document.createElement("button");
    b.type = "button";
    const isAct = (ch.login||"").toLowerCase() === (activeChannelLogin||"").toLowerCase();
    b.className = "ch-tab" + (isAct ? " on" : "");
    b.textContent = ch.name || ch.login;
    b.addEventListener("click", async ()=>{
      if(isAct || ended) return;
      try{
        await fetch(BASE + "/api/channel", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ login: ch.login })
        });
      }catch(_){}
    });
    chTabsEl.appendChild(b);
  });
}

function updateSendMode(){
  formEl.style.display = canSend ? "flex" : "none";
  roEl.classList.toggle("show", !canSend);
  if(!canSend){drawer.classList.remove("show");btnEm.classList.remove("on");}
}

function renderPartsHtml(parts){
  if(!parts||!parts.length) return "";
  return parts.map(p=>{
    if(p.t==="emote" && p.url){
      return '<img class="em" src="'+esc(p.url)+'" alt="'+esc(p.code)+'" title="'+esc(p.code)+'" loading="lazy"/>';
    }
    const txt = p.v||"";
    return txt.split(/(\s+)/).map(w=>{
      if(w.startsWith("@") && w.length>1) return '<span class="mention">'+esc(w)+'</span>';
      return esc(w);
    }).join("");
  }).join("");
}

function addMsg(m, bulk){
  const el = document.createElement("div");
  el.className = "msg" + (m.isSystem ? " sys" : "") + (m.deleted ? " del" : "");
  if(m.id){ el.dataset.id = m.id; msgById.set(m.id, el); }
  let html = "";
  if(m.reply && (m.reply.user || m.reply.text)){
    html += '<div class="rep-line">↳ ' + esc(fmt(T.replyingTo, "@" + m.reply.user)) + (m.reply.text ? " · " + esc(m.reply.text) : "") + '</div>';
  }
  if(m.systemMsg){
    html += '<div>' + esc(m.systemMsg) + '</div>';
  }
  if(m.user || (m.parts && m.parts.length)){
    let bHtml = "";
    if(showBadges && m.badges && m.badges.length){
      bHtml = m.badges.map(b=>'<img class="bdg" src="'+esc(b.url)+'" alt="'+esc(b.title)+'" title="'+esc(b.title)+'"/>').join("");
    }
    const uHtml = m.user ? '<span class="usr" style="color:'+esc(m.color||"#BF94FF")+'">'+esc(m.user)+'</span>' : "";
    const bodyHtml = renderPartsHtml(m.parts);
    html += '<div' + (m.isAction ? ' style="font-style:italic;color:'+esc(m.color||"#BF94FF")+'"' : '') + '>' + bHtml + uHtml + bodyHtml + '</div>';
  }
  el.innerHTML = html;
  if(!m.isSystem && m.user && canSend){
    el.addEventListener("click", ()=>{
      reply = { id: m.id||"", user: m.user, text: m.rawText||"" };
      repLbl.textContent = fmt(T.replyingTo, "@" + m.user) + (m.rawText ? " · " + m.rawText : "");
      repBar.classList.add("show");
      inpEl.focus();
    });
  }
  feed.appendChild(el);
  while(feed.children.length > 220){
    const first = feed.firstChild;
    if(first && first.dataset && first.dataset.id) msgById.delete(first.dataset.id);
    first.remove();
  }
  if(!bulk){
    if(!paused){
      feed.scrollTop = feed.scrollHeight;
    }else{
      missed++;
      pauseCnt.textContent = missed > 99 ? "99+" : String(missed);
      pauseBtn.classList.add("show");
    }
  }
}

function addNotice(text){
  const el = document.createElement("div");
  el.className = "msg sys";
  el.textContent = text;
  feed.appendChild(el);
  if(!paused) feed.scrollTop = feed.scrollHeight;
}

feed.addEventListener("scroll", ()=>{
  const dist = feed.scrollHeight - feed.scrollTop - feed.clientHeight;
  if(dist > 44){
    paused = true;
    pauseBtn.classList.add("show");
  }else{
    paused = false;
    missed = 0;
    pauseCnt.textContent = "0";
    pauseBtn.classList.remove("show");
  }
});

pauseBtn.addEventListener("click", ()=>{
  paused = false; missed = 0; pauseCnt.textContent = "0";
  pauseBtn.classList.remove("show");
  feed.scrollTo({top: feed.scrollHeight, behavior: "smooth"});
});

document.getElementById("repX").addEventListener("click", ()=>{
  reply = null; repBar.classList.remove("show");
});
document.getElementById("btnAa").addEventListener("click", (e)=>{
  const on = feed.classList.toggle("lg");
  e.currentTarget.classList.toggle("on", on);
});
document.getElementById("btnBdg").addEventListener("click", (e)=>{
  showBadges = !showBadges;
  e.currentTarget.classList.toggle("on", showBadges);
  document.querySelectorAll(".bdg").forEach(img=>{ img.style.display = showBadges ? "" : "none"; });
});

function renderTabs(){
  tabsEl.innerHTML = "";
  const q = srchEl.value.trim();
  emoteGroups.forEach((g, idx)=>{
    const b = document.createElement("button");
    b.type = "button";
    b.className = "tab" + (!q && idx === activeTab ? " on" : "");
    b.textContent = g.label;
    b.addEventListener("click", ()=>{
      srchEl.value = "";
      activeTab = idx;
      renderTabs();
      renderGrid();
    });
    tabsEl.appendChild(b);
  });
}

function renderGrid(){
  gridEl.innerHTML = "";
  const q = srchEl.value.trim().toLowerCase();
  let items = [];
  let isMost = false;
  if(q){
    const seen = new Set();
    emoteGroups.forEach(g=>{
      (g.emotes||[]).forEach(e=>{
        if(!seen.has(e.code) && (e.code.toLowerCase().includes(q) || (e.provider||"").toLowerCase().includes(q))){
          seen.add(e.code); items.push(e);
        }
      });
    });
  }else if(emoteGroups[activeTab]){
    items = emoteGroups[activeTab].emotes || [];
    isMost = !!emoteGroups[activeTab].isMostUsed;
  }
  if(!items.length){
    gridEl.innerHTML = '<div style="grid-column:1/-1;text-align:center;color:rgba(255,255,255,.45);padding:26px 8px;font-size:12.5px">' + esc(isMost ? T.mostUsedEmpty : "—") + '</div>';
    return;
  }
  const frag = document.createDocumentFragment();
  items.slice(0, 180).forEach(e=>{
    const c = document.createElement("button");
    c.type = "button";
    c.className = "ec";
    c.innerHTML = '<span class="ec-dot" style="background:'+esc(e.color||"#9147FF")+'"></span>' +
      '<img src="'+esc(e.url)+'" alt="'+esc(e.code)+'" loading="lazy"/>' +
      '<span class="ec-n">'+esc(e.code)+'</span>' +
      (isMost && e.count > 0 ? '<span class="ec-c">'+e.count+'</span>' : '');
    c.addEventListener("click", ()=>{
      const cur = inpEl.value;
      const pre = !cur || cur.endsWith(" ") ? "" : " ";
      inpEl.value = cur + pre + e.code + " ";
      inpEl.focus();
    });
    frag.appendChild(c);
  });
  gridEl.appendChild(frag);
}

btnEm.addEventListener("click", ()=>{
  const open = drawer.classList.toggle("show");
  btnEm.classList.toggle("on", open);
  if(open){ renderTabs(); renderGrid(); }
});
document.getElementById("drwX").addEventListener("click", ()=>{
  drawer.classList.remove("show"); btnEm.classList.remove("on");
});
srchEl.addEventListener("input", ()=>{ renderTabs(); renderGrid(); });

formEl.addEventListener("submit", async (e)=>{
  e.preventDefault();
  const txt = inpEl.value.trim();
  if(!txt || !canSend) return;
  const payload = { text: txt, replyId: reply ? reply.id : "" };
  inpEl.value = "";
  reply = null; repBar.classList.remove("show");
  try{
    await fetch(BASE + "/api/send", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
  }catch(_){}
});

function renderAvatar(name, url){
  const initial = (name || "X").charAt(0).toUpperCase();
  if(url){
    avEl.innerHTML = "";
    const img = document.createElement("img");
    img.src = url;
    img.alt = name || "";
    img.onerror = ()=>{ avEl.textContent = initial; };
    avEl.appendChild(img);
  }else{
    avEl.textContent = initial;
  }
}

function scheduleReconnect(delayMs){
  if(revoked) return;
  if(reconnTimer){ clearTimeout(reconnTimer); reconnTimer = null; }
  reconnTimer = setTimeout(()=>{
    reconnTimer = null;
    if(revoked) return;
    if(document.visibilityState === "hidden"){
      return;
    }
    tryReconnect();
  }, delayMs);
}

async function tryReconnect(){
  if(revoked) return;
  const ok = await loadState(false);
  if(ok){
    ended = false;
    btnReconn.classList.remove("show");
    connectSse();
  }else if(!revoked){
    btnReconn.classList.add("show");
    scheduleReconnect(5000);
  }
}

btnReconn.addEventListener("click", ()=>{ tryReconnect(); });
document.addEventListener("visibilitychange", ()=>{
  if(document.visibilityState === "visible" && (ended || dotEl.classList.contains("off")) && !revoked){
    tryReconnect();
  }
});
window.addEventListener("focus", ()=>{
  if((ended || dotEl.classList.contains("off")) && !revoked){
    tryReconnect();
  }
});

function applyState(st, isSwitch){
  const prevLogin = activeChannelLogin;
  const name = st.channelName || st.channelLogin || "XtraPlus";
  activeChannelName = name;
  activeChannelLogin = st.channelLogin || "";
  channels = st.channels || [];
  chNameEl.textContent = name;
  renderAvatar(name, st.avatarUrl || "");
  renderChannelTabs();
  canSend = !!st.canSend;
  updateSendMode();
  emoteGroups = st.emoteGroups || [];
  const firstNonEmpty = emoteGroups.findIndex(g=>g.emotes && g.emotes.length>0);
  if(activeTab >= emoteGroups.length || (!emoteGroups[activeTab]?.emotes?.length && firstNonEmpty >= 0)){
    activeTab = Math.max(0, firstNonEmpty);
  }
  const sameChannel = prevLogin && activeChannelLogin && prevLogin.toLowerCase() === activeChannelLogin.toLowerCase();
  if(!sameChannel || !isSwitch){
    feed.innerHTML = "";
    msgById.clear();
    if(isSwitch && !sameChannel) addNotice(fmt(T.switched, name));
    (st.messages||[]).forEach(m=>addMsg(m, true));
    paused = false; missed = 0; pauseBtn.classList.remove("show");
    feed.scrollTop = feed.scrollHeight;
  }
  if(drawer.classList.contains("show")){ renderTabs(); renderGrid(); }
}

async function loadState(isSwitch){
  try{
    const r = await fetch(BASE + "/api/state", { cache: "no-store" });
    if(r.status === 404){
      revoked = true;
      ended = true;
      dotEl.classList.add("off");
      btnReconn.classList.remove("show");
      canSend = false;
      updateSendMode();
      return false;
    }
    if(!r.ok) return false;
    const st = await r.json();
    applyState(st, isSwitch);
    return true;
  }catch(_){
    return false;
  }
}

function connectSse(){
  if(ended || revoked) return;
  if(es){ try{ es.close(); }catch(_){} }
  es = new EventSource(BASE + "/api/events");
  es.onopen = ()=>{
    ended = false;
    dotEl.classList.remove("off");
    btnReconn.classList.remove("show");
  };
  es.addEventListener("msg", ev=>{
    try{ addMsg(JSON.parse(ev.data), false); }catch(_){}
  });
  es.addEventListener("deleted", ev=>{
    try{
      const d = JSON.parse(ev.data);
      (d.ids||[]).forEach(id=>{
        const el = msgById.get(id);
        if(el) el.classList.add("del");
      });
    }catch(_){}
  });
  es.addEventListener("emotes", ev=>{
    try{
      const d = JSON.parse(ev.data);
      if(d.emoteGroups){
        emoteGroups = d.emoteGroups;
        if(drawer.classList.contains("show")){ renderTabs(); renderGrid(); }
      }
    }catch(_){}
  });
  es.addEventListener("avatar", ev=>{
    try{
      const d = JSON.parse(ev.data);
      if(d.avatarUrl && (!d.login || d.login.toLowerCase() === (activeChannelLogin||"").toLowerCase())){
        renderAvatar(activeChannelName, d.avatarUrl);
      }
    }catch(_){}
  });
  es.addEventListener("channel", ev=>{
    try{ applyState(JSON.parse(ev.data), true); }catch(_){ loadState(true); }
  });
  es.addEventListener("closed", ev=>{
    let reason = "closed";
    try{ reason = (JSON.parse(ev.data)||{}).reason || "closed"; }catch(_){}
    ended = true;
    dotEl.classList.add("off");
    canSend = false;
    updateSendMode();
    addNotice(T.ended);
    try{ es.close(); }catch(_){}
    if(reason === "revoked"){
      revoked = true;
      btnReconn.classList.remove("show");
    }else{
      btnReconn.classList.add("show");
      scheduleReconnect(5000);
    }
  });
  es.onerror = ()=>{
    if(revoked) return;
    dotEl.classList.add("off");
    btnReconn.classList.add("show");
    try{ es.close(); }catch(_){}
    scheduleReconnect(3000);
  };
}

tryReconnect();
</script>
</body>
</html>"""
    }

    private fun buildManifestJson(token: String): JSONObject {
        val iconPath = "/$token/icon.png"
        val icons = JSONArray()
            .put(
                JSONObject()
                    .put("src", iconPath)
                    .put("sizes", "192x192")
                    .put("type", "image/png")
                    .put("purpose", "any"),
            )
            .put(
                JSONObject()
                    .put("src", iconPath)
                    .put("sizes", "512x512")
                    .put("type", "image/png")
                    .put("purpose", "any"),
            )
        return JSONObject()
            .put("name", "XtraPlus Chat")
            .put("short_name", "XtraPlus Chat")
            .put("start_url", "/$token/")
            .put("scope", "/$token/")
            .put("display", "standalone")
            .put("background_color", "#0E0E13")
            .put("theme_color", "#14131C")
            .put("icons", icons)
    }

    private fun getAppIconPng(appCtx: Context): ByteArray? {
        cachedIconPng?.let { return it }
        return runCatching {
            val drawable = ContextCompat.getDrawable(appCtx, R.mipmap.ic_launcher)
                ?: appCtx.applicationInfo.loadIcon(appCtx.packageManager)
                ?: return@runCatching null
            val size = 192
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            bmp.recycle()
            baos.toByteArray().also { cachedIconPng = it }
        }.getOrNull()
    }

    private fun writeResponse(
        socket: Socket,
        status: Int,
        contentType: String,
        body: ByteArray,
        cacheControl: String = "no-store",
    ) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Error"
        }
        val out = BufferedOutputStream(socket.getOutputStream())
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: $cacheControl\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > 4096) return sb.toString()
        }
    }

    private fun readFixedBytes(input: BufferedInputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArrayOutputStream(length)
        val buf = ByteArray(minOf(length, 4096))
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun getOrCreateToken(appCtx: Context): String {
        val prefs = appCtx.prefs()
        val existing = prefs.getString(C.TV_CHAT_QR_TOKEN, null)?.trim()
        if (!existing.isNullOrEmpty() && existing.length >= 16 && existing.all { it in '0'..'9' || it in 'a'..'f' }) {
            return existing
        }
        val fresh = generateToken()
        prefs.edit { putString(C.TV_CHAT_QR_TOKEN, fresh) }
        return fresh
    }

    private fun generateToken(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun findLocalIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
