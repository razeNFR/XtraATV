package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import com.github.andreyasadchy.xtra.XtraApp
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
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight, cached sub-only VOD detector for Android TV.
 *
 * Twitch's GraphQL `Video.resourceRestriction` and `Video.self.isRestricted` currently return
 * `null` / `false` even on subscriber-only archives, whereas `videoPlaybackAccessToken` reliably
 * reports `chansub.restricted_bitrates`. We query up to 15 uncached VOD IDs in a single aliased
 * GraphQL request (~175 ms in the background) and cache the boolean result in memory.
 */
object TvSubOnlyVodCache {

    private const val BATCH_SIZE = 15
    private const val DEBOUNCE_MS = 120L
    private const val MAX_CACHE_ENTRIES = 600
    private val SAFE_ID = Regex("^[0-9]+$")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Boolean>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val pendingQueue = ConcurrentHashMap.newKeySet<String>()

    private var debounceJob: Job? = null

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> get() = _revision

    fun isSubOnly(videoId: String?): Boolean {
        if (videoId.isNullOrBlank()) return false
        return cache[videoId] == true
    }

    fun isKnown(videoId: String?): Boolean {
        if (videoId.isNullOrBlank()) return false
        return cache.containsKey(videoId)
    }

    /** Queues one or more VOD IDs for a single debounced, batched background check. */
    fun enqueue(context: Context, videoIds: Collection<String?>) {
        val appCtx = context.applicationContext
        var added = false
        for (raw in videoIds) {
            val id = raw?.trim() ?: continue
            if (id.isEmpty() || !SAFE_ID.matches(id)) continue
            if (cache.containsKey(id) || inFlight.contains(id)) continue
            if (pendingQueue.add(id)) added = true
        }
        if (!added) return
        synchronized(this) {
            if (debounceJob?.isActive == true) return
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                flushPending(appCtx)
            }
        }
    }

    fun enqueue(context: Context, videoId: String?) {
        if (!videoId.isNullOrBlank()) enqueue(context, listOf(videoId))
    }

    private suspend fun flushPending(appCtx: Context) {
        while (true) {
            val batch = mutableListOf<String>()
            val it = pendingQueue.iterator()
            while (it.hasNext() && batch.size < BATCH_SIZE) {
                val id = it.next()
                it.remove()
                if (!cache.containsKey(id) && inFlight.add(id)) {
                    batch.add(id)
                }
            }
            if (batch.isEmpty()) break
            try {
                fetchBatch(appCtx, batch)
            } finally {
                batch.forEach { inFlight.remove(it) }
            }
        }
    }

    private suspend fun fetchBatch(appCtx: Context, ids: List<String>) {
        val app = appCtx as? XtraApp ?: return
        val prefs = appCtx.prefs()
        val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val clientId = prefs.getString(C.GQL_CLIENT_ID_WEB, "kimne78kx3ncx6brgo4mv6wki5h1ko")
            ?.takeIf { it.isNotBlank() }
            ?: "kimne78kx3ncx6brgo4mv6wki5h1ko"
        val headers = mapOf(C.HEADER_CLIENT_ID to clientId)

        val aliases = buildString {
            append("query { ")
            ids.forEachIndexed { idx, id ->
                append("v")
                append(idx)
                append(": videoPlaybackAccessToken(id: \"")
                append(id)
                append("\", params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\"}) { value } ")
            }
            append("}")
        }
        val body = JSONObject().put("query", aliases).toString()

        val raw = runCatching {
            app.xtraModule.graphQLRepository.sendPersistedQuery(networkLibrary, headers, body)
        }.getOrNull() ?: return

        val data = runCatching { JSONObject(raw).optJSONObject("data") }.getOrNull() ?: return
        if (cache.size > MAX_CACHE_ENTRIES) {
            cache.clear()
        }
        var anyNewSubOnly = false
        ids.forEachIndexed { idx, id ->
            if (!data.has("v$idx")) return@forEachIndexed
            val tokenObj = data.optJSONObject("v$idx")
            if (tokenObj == null) {
                cache[id] = false
                return@forEachIndexed
            }
            val valueStr = tokenObj.optString("value", "").takeIf { it.isNotBlank() } ?: run {
                cache[id] = false
                return@forEachIndexed
            }
            val restricted = runCatching {
                val parsed = JSONObject(valueStr)
                val arr = parsed.optJSONObject("chansub")?.optJSONArray("restricted_bitrates")
                arr != null && arr.length() > 0
            }.getOrDefault(false)
            cache[id] = restricted
            if (restricted) {
                anyNewSubOnly = true
                TvResumeHistory.markSubOnly(id)
            }
        }
        if (anyNewSubOnly) {
            _revision.value++
        }
    }
}
