package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/**
 * TV cache & debounced batch resolver for Twitch Stream Together / Shared Chat / Co-stream sessions.
 * Powers `+1`, `+2`, ... badges next to streamer names on Home, Stream Lists, the Streamer Switcher,
 * and on the Player's Multi-Stream button, plus instant 1-click Multi-Stream partner lists.
 */
object TvCoStreamCache {

    const val PAYLOAD_COSTREAM = "payload_costream"
    private const val TTL_MS = 90_000L
    private const val DEBOUNCE_MS = 85L
    private val mentionRegex = Regex("@([A-Za-z0-9_]{3,25})")

    data class Entry(
        val count: Int,
        val partners: List<Stream>,
        val fetchedAt: Long,
    )

    private data class PendingChannel(
        val channelId: String,
        val channelLogin: String?,
        val title: String?,
    )

    private val byId = ConcurrentHashMap<String, Entry>()
    private val byLogin = ConcurrentHashMap<String, Entry>()
    private val pending = ConcurrentHashMap<String, PendingChannel>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Increments whenever cached co-stream entries change (observed by Compose & Player). */
    val version = MutableStateFlow(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val batchLock = Any()
    private var batchJob: Job? = null

    fun getCount(channelId: String?, channelLogin: String?, @Suppress("UNUSED_PARAMETER") tick: Int = 0): Int {
        if (!channelId.isNullOrBlank()) {
            byId[channelId]?.let { return it.count }
        }
        if (!channelLogin.isNullOrBlank()) {
            byLogin[channelLogin.lowercase()]?.let { return it.count }
        }
        return 0
    }

    fun getPartners(channelId: String?, channelLogin: String?): List<Stream> {
        if (!channelId.isNullOrBlank()) {
            byId[channelId]?.partners?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (!channelLogin.isNullOrBlank()) {
            byLogin[channelLogin.lowercase()]?.partners?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    fun isCached(channelId: String?, channelLogin: String?): Boolean {
        val now = System.currentTimeMillis()
        if (!channelId.isNullOrBlank()) {
            byId[channelId]?.takeIf { now - it.fetchedAt < TTL_MS }?.let { return true }
        }
        if (!channelLogin.isNullOrBlank()) {
            byLogin[channelLogin.lowercase()]?.takeIf { now - it.fetchedAt < TTL_MS }?.let { return true }
        }
        return false
    }

    /** Updates cached live co-stream partners (e.g. from Player or ChatViewModel) and notifies UI. */
    fun putPartners(channelId: String?, channelLogin: String?, partners: List<Stream>, clearIfEmpty: Boolean = false) {
        val cleanPartners = partners
            .filter { !it.channelLogin.isNullOrBlank() && !it.channelLogin.equals(channelLogin, ignoreCase = true) && it.channelId != channelId }
            .distinctBy { it.channelLogin?.lowercase() }
        val now = System.currentTimeMillis()
        val prevCount = getCount(channelId, channelLogin)
        val effectiveCount = when {
            cleanPartners.isNotEmpty() -> maxOf(cleanPartners.size, prevCount)
            clearIfEmpty -> 0
            else -> prevCount
        }
        val entry = Entry(count = effectiveCount, partners = cleanPartners, fetchedAt = now)
        if (!channelId.isNullOrBlank()) {
            byId[channelId] = entry
        }
        if (!channelLogin.isNullOrBlank()) {
            byLogin[channelLogin.lowercase()] = entry
        }
        if (prevCount != effectiveCount || cleanPartners.isNotEmpty()) {
            notifyChanged()
        }
    }

    /** Schedules a lightweight debounced batch lookup for a stream card shown on Home / Lists / Switcher. */
    fun enqueue(context: Context, stream: Stream?) {
        if (stream == null) return
        enqueue(context, stream.channelId, stream.channelLogin, stream.title)
    }

    fun enqueue(context: Context, channelId: String?, channelLogin: String?, title: String? = null) {
        val id = channelId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        val existing = byId[id]
        if (existing != null && now - existing.fetchedAt < TTL_MS) {
            if (!channelLogin.isNullOrBlank()) {
                byLogin.putIfAbsent(channelLogin.lowercase(), existing)
            }
            return
        }
        if (id in inFlight) return
        val appContext = context.applicationContext
        pending[id] = PendingChannel(id, channelLogin, title)
        synchronized(batchLock) {
            if (batchJob?.isActive != true) {
                batchJob = scope.launch {
                    delay(DEBOUNCE_MS)
                    flushPending(appContext)
                }
            }
        }
    }

    private suspend fun flushPending(appContext: Context) {
        while (true) {
            val batch = ArrayList<PendingChannel>(24)
            val iter = pending.entries.iterator()
            while (iter.hasNext() && batch.size < 20) {
                val next = iter.next()
                iter.remove()
                if (inFlight.add(next.key)) {
                    batch.add(next.value)
                }
            }
            if (batch.isEmpty()) break
            try {
                resolveBatch(appContext, batch)
            } finally {
                batch.forEach { inFlight.remove(it.channelId) }
            }
        }
    }

    private suspend fun resolveBatch(appContext: Context, batch: List<PendingChannel>) {
        val app = appContext as? XtraApp ?: return
        val repo = app.xtraModule.graphQLRepository
        val netLib = appContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val headers = TwitchApiHelper.getGQLHeaders(appContext)

        val ids = batch.map { it.channelId }
        val gqlMap = runCatching {
            repo.loadBatchCoStreamInfo(netLib, headers, ids)
        }.getOrDefault(emptyMap())

        val allPartnerIds = LinkedHashSet<String>()
        val allPartnerLogins = LinkedHashSet<String>()
        val titleMentionsByChannel = HashMap<String, List<String>>()

        for (item in batch) {
            val info = gqlMap[item.channelId]
            info?.partnerIds?.forEach { pid ->
                if (pid != item.channelId) allPartnerIds.add(pid)
            }
            val mentions = extractMentions(item.title, item.channelLogin)
            if (mentions.isNotEmpty()) {
                titleMentionsByChannel[item.channelId] = mentions
                allPartnerLogins.addAll(mentions)
            }
        }

        val liveById = HashMap<String, Stream>()
        val liveByLogin = HashMap<String, Stream>()

        if (allPartnerIds.isNotEmpty()) {
            for (idChunk in allPartnerIds.toList().chunked(35)) {
                runCatching {
                    val resp = repo.loadQueryUsersStream(netLib, headers, ids = idChunk)
                    resp.data?.users?.forEach { u ->
                        val st = u?.stream ?: return@forEach
                        val uid = u.id?.takeIf { it.isNotBlank() } ?: return@forEach
                        val ulogin = u.login?.takeIf { it.isNotBlank() } ?: return@forEach
                        val mapped = Stream(
                            id = st.id,
                            channelId = uid,
                            channelLogin = ulogin,
                            channelName = u.displayName ?: ulogin,
                            channelImageURL = u.profileImageURL,
                            gameId = st.game?.id,
                            gameSlug = st.game?.slug,
                            gameName = st.game?.displayName,
                            title = st.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = st.previewImageURL,
                            createdAt = st.createdAt?.toString(),
                            viewerCount = st.viewersCount,
                            tags = st.freeformTags?.mapNotNull { it?.name },
                        )
                        liveById[uid] = mapped
                        liveByLogin[ulogin.lowercase()] = mapped
                    }
                }
            }
        }

        val missingLogins = allPartnerLogins.filter { it !in liveByLogin }
        if (missingLogins.isNotEmpty()) {
            for (loginChunk in missingLogins.chunked(35)) {
                runCatching {
                    val resp = repo.loadQueryUsersStream(netLib, headers, logins = loginChunk)
                    resp.data?.users?.forEach { u ->
                        val st = u?.stream ?: return@forEach
                        val uid = u.id?.takeIf { it.isNotBlank() } ?: return@forEach
                        val ulogin = u.login?.takeIf { it.isNotBlank() } ?: return@forEach
                        val mapped = Stream(
                            id = st.id,
                            channelId = uid,
                            channelLogin = ulogin,
                            channelName = u.displayName ?: ulogin,
                            channelImageURL = u.profileImageURL,
                            gameId = st.game?.id,
                            gameSlug = st.game?.slug,
                            gameName = st.game?.displayName,
                            title = st.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = st.previewImageURL,
                            createdAt = st.createdAt?.toString(),
                            viewerCount = st.viewersCount,
                            tags = st.freeformTags?.mapNotNull { it?.name },
                        )
                        liveById[uid] = mapped
                        liveByLogin[ulogin.lowercase()] = mapped
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        var anyCountChanged = false
        for (item in batch) {
            val info = gqlMap[item.channelId]
            val partnersMap = LinkedHashMap<String, Stream>()
            info?.partnerIds?.forEach { pid ->
                val s = liveById[pid]
                val k = s?.channelLogin?.lowercase()
                if (s != null && k != null && pid != item.channelId && !k.equals(item.channelLogin, ignoreCase = true)) {
                    partnersMap[k] = s
                }
            }
            info?.partnerLogins?.forEach { plogin ->
                val s = liveByLogin[plogin.lowercase()]
                val k = s?.channelLogin?.lowercase()
                if (s != null && k != null && s.channelId != item.channelId && !k.equals(item.channelLogin, ignoreCase = true)) {
                    partnersMap[k] = s
                }
            }
            titleMentionsByChannel[item.channelId]?.forEach { mlogin ->
                val s = liveByLogin[mlogin.lowercase()]
                val k = s?.channelLogin?.lowercase()
                if (s != null && k != null && s.channelId != item.channelId && !k.equals(item.channelLogin, ignoreCase = true)) {
                    partnersMap[k] = s
                }
            }
            val partners = partnersMap.values.toList()
            val count = maxOf(partners.size, info?.fallbackCount ?: 0)
            val prev = byId[item.channelId]?.count ?: 0
            val entry = Entry(count = count, partners = partners, fetchedAt = now)
            byId[item.channelId] = entry
            if (!item.channelLogin.isNullOrBlank()) {
                byLogin[item.channelLogin.lowercase()] = entry
            }
            if (count != prev) {
                anyCountChanged = true
            }
        }
        if (anyCountChanged) {
            notifyChanged()
        }
    }

    /** Immediate full resolution for a single channel (used when opening a stream in Player or long-press actions). */
    suspend fun loadForChannel(
        context: Context,
        channelId: String?,
        channelLogin: String?,
        title: String? = null,
    ): List<Stream> = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? XtraApp ?: return@withContext emptyList()
        val repo = app.xtraModule.graphQLRepository
        val netLib = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val headers = TwitchApiHelper.getGQLHeaders(context)

        var resolvedId = channelId?.trim()?.takeIf { it.isNotBlank() }
        var resolvedTitle = title
        if ((resolvedId == null || resolvedTitle.isNullOrBlank()) && !channelLogin.isNullOrBlank()) {
            runCatching {
                val selfUser = repo.loadQueryUsersStream(netLib, headers, logins = listOf(channelLogin)).data?.users?.firstOrNull()
                if (resolvedId == null) {
                    resolvedId = selfUser?.id?.takeIf { it.isNotBlank() }
                }
                if (resolvedTitle.isNullOrBlank()) {
                    resolvedTitle = selfUser?.stream?.broadcaster?.broadcastSettings?.title
                }
            }
        }

        val partnerIds = if (resolvedId != null) {
            repo.loadSharedChatChannelIds(netLib, headers, resolvedId)
        } else {
            emptyList()
        }
        val mentions = extractMentions(resolvedTitle, channelLogin)

        val partnersMap = LinkedHashMap<String, Stream>()
        if (partnerIds.isNotEmpty()) {
            runCatching {
                val resp = repo.loadQueryUsersStream(netLib, headers, ids = partnerIds.distinct().take(12))
                resp.data?.users?.forEach { u ->
                    val st = u?.stream ?: return@forEach
                    val uid = u.id?.takeIf { it.isNotBlank() } ?: return@forEach
                    val ulogin = u.login?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (uid != resolvedId && !ulogin.equals(channelLogin, ignoreCase = true)) {
                        partnersMap[ulogin.lowercase()] = Stream(
                            id = st.id,
                            channelId = uid,
                            channelLogin = ulogin,
                            channelName = u.displayName ?: ulogin,
                            channelImageURL = u.profileImageURL,
                            gameId = st.game?.id,
                            gameSlug = st.game?.slug,
                            gameName = st.game?.displayName,
                            title = st.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = st.previewImageURL,
                            createdAt = st.createdAt?.toString(),
                            viewerCount = st.viewersCount,
                            tags = st.freeformTags?.mapNotNull { it?.name },
                        )
                    }
                }
            }
        }
        val missingMentions = mentions.filter { it !in partnersMap }
        if (missingMentions.isNotEmpty()) {
            runCatching {
                val resp = repo.loadQueryUsersStream(netLib, headers, logins = missingMentions)
                resp.data?.users?.forEach { u ->
                    val st = u?.stream ?: return@forEach
                    val uid = u.id?.takeIf { it.isNotBlank() } ?: return@forEach
                    val ulogin = u.login?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (uid != resolvedId && !ulogin.equals(channelLogin, ignoreCase = true)) {
                        partnersMap[ulogin.lowercase()] = Stream(
                            id = st.id,
                            channelId = uid,
                            channelLogin = ulogin,
                            channelName = u.displayName ?: ulogin,
                            channelImageURL = u.profileImageURL,
                            gameId = st.game?.id,
                            gameSlug = st.game?.slug,
                            gameName = st.game?.displayName,
                            title = st.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = st.previewImageURL,
                            createdAt = st.createdAt?.toString(),
                            viewerCount = st.viewersCount,
                            tags = st.freeformTags?.mapNotNull { it?.name },
                        )
                    }
                }
            }
        }

        val list = partnersMap.values.toList()
        putPartners(resolvedId, channelLogin, list)
        list
    }

    private fun extractMentions(title: String?, selfLogin: String?): List<String> {
        if (title.isNullOrBlank()) return emptyList()
        return mentionRegex.findAll(title)
            .map { it.groupValues[1].lowercase() }
            .filter { !it.equals(selfLogin, ignoreCase = true) }
            .distinct()
            .take(6)
            .toList()
    }

    private fun notifyChanged() {
        version.value = version.value + 1
        mainHandler.post {
            listeners.forEach { it.invoke() }
        }
    }

    fun attachToRecyclerView(recyclerView: RecyclerView, onUpdate: () -> Unit) {
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                listeners.add(onUpdate)
            }

            override fun onViewDetachedFromWindow(v: View) {
                listeners.remove(onUpdate)
            }
        }
        recyclerView.addOnAttachStateChangeListener(attachListener)
        if (recyclerView.isAttachedToWindow) {
            listeners.add(onUpdate)
        }
    }

    /** Formats a streamer username with an inline Twitch-style `+1` / `+2` pill badge when [count] > 0. */
    fun formatUsernameWithBadge(context: Context, baseText: CharSequence, count: Int): CharSequence {
        if (count <= 0 || baseText.isEmpty()) return baseText
        val density = context.resources.displayMetrics.density
        val badgeLabel = "+$count"
        return SpannableStringBuilder(baseText).apply {
            append(" ")
            val start = length
            append(badgeLabel)
            setSpan(
                CoStreamPillSpan(badgeLabel, density),
                start,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private class CoStreamPillSpan(
        private val label: String,
        private val density: Float,
    ) : ReplacementSpan() {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x33FFFFFF
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = 0x55FFFFFF
        }
        private val rect = RectF()

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            val oldSize = paint.textSize
            val oldTypeface = paint.typeface
            paint.textSize = oldSize * 0.80f
            paint.typeface = Typeface.create(oldTypeface, Typeface.BOLD)
            val textW = paint.measureText(label)
            paint.textSize = oldSize
            paint.typeface = oldTypeface
            val padH = 5f * density
            val gapStart = 3f * density
            return (gapStart + textW + padH * 2f).roundToInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val oldSize = paint.textSize
            val oldColor = paint.color
            val oldTypeface = paint.typeface

            paint.textSize = oldSize * 0.80f
            paint.typeface = Typeface.create(oldTypeface, Typeface.BOLD)
            val textW = paint.measureText(label)
            val fm = paint.fontMetrics

            val gapStart = 3f * density
            val padH = 5f * density
            val padV = 1.5f * density
            val pillW = textW + padH * 2f
            val textH = fm.descent - fm.ascent
            val pillH = textH + padV * 2f

            val lineCenterY = (top + bottom) / 2f
            val left = x + gapStart
            val pillTop = lineCenterY - pillH / 2f
            rect.set(left, pillTop, left + pillW, pillTop + pillH)
            val radius = 5f * density

            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)

            paint.color = Color.WHITE
            val baseline = lineCenterY - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, left + padH, baseline, paint)

            paint.textSize = oldSize
            paint.color = oldColor
            paint.typeface = oldTypeface
        }
    }

    /**
     * Overlay badge drawable (`+1`, `+2`, ...) drawn at the top-right corner of the Player's Multi-Stream button.
     */
    class MultiStreamBadgeDrawable(private val density: Float) : Drawable() {
        var count: Int = 0
            set(value) {
                if (field != value) {
                    field = value
                    invalidateSelf()
                }
            }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = 0xE6141318.toInt()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.5f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            if (count <= 0) return
            val b = bounds
            if (b.isEmpty) return

            val label = "+$count"
            val accentBg = if (TvAccent.current != null) TvAccent.color else 0xFF9146FF.toInt()
            val accentFg = if (TvAccent.current != null) TvAccent.onAccent else Color.WHITE
            fillPaint.color = accentBg
            textPaint.color = accentFg

            val textW = textPaint.measureText(label)
            val padH = 4.5f * density
            val pillH = 16.5f * density
            val pillW = maxOf(pillH, textW + padH * 2f)

            val right = b.right - 2f * density
            val top = b.top + 2f * density
            val left = right - pillW
            val bottom = top + pillH
            rect.set(left, top, right, bottom)
            val radius = pillH / 2f

            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.drawRoundRect(rect, radius, radius, haloPaint)

            val fm = textPaint.fontMetrics
            val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, rect.centerX(), baseline, textPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            textPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
