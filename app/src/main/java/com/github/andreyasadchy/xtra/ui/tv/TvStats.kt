package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * TV: statistics shown on the home (time watched, most watched streamer, ads blocked, proxy used, messages sent...).
 * Counted on this device from the version that added them, kept in their own preferences file.
 */
object TvStats {

    private const val FILE = "tv_stats"
    private const val WATCH_MS = "watch_ms"
    private const val STREAMERS = "streamers"
    private const val NAMES = "names"
    private const val ADS = "ads"
    private const val PROXIES = "proxies"
    private const val MESSAGES = "messages"
    private const val STREAMS = "streams"
    const val SINCE = "since"

    class Snapshot(
        val watchMs: Long,
        val topStreamer: String?,
        val topStreamerMs: Long,
        val ads: Int,
        val topProxy: String?,
        val topProxyUses: Int,
        val messages: Int,
        val streams: Int,
        val since: Long,
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Changes each time a statistic is counted: the home shows them again even when it never left the screen. */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

    private fun ensureSince(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains(SINCE)) {
            prefs.edit { putLong(SINCE, System.currentTimeMillis()) }
        }
    }

    @Synchronized
    fun addWatchTime(context: Context, channelLogin: String?, channelName: String?, ms: Long) {
        ensureSince(context)
        val prefs = prefs(context)
        prefs.edit {
            putLong(WATCH_MS, prefs.getLong(WATCH_MS, 0) + ms)
            if (!channelLogin.isNullOrBlank()) {
                val streamers = JSONObject(prefs.getString(STREAMERS, null) ?: "{}")
                streamers.put(channelLogin, streamers.optLong(channelLogin, 0) + ms)
                putString(STREAMERS, streamers.toString())
                if (!channelName.isNullOrBlank()) {
                    val names = JSONObject(prefs.getString(NAMES, null) ?: "{}")
                    names.put(channelLogin, channelName)
                    putString(NAMES, names.toString())
                }
            }
        }
        changes.value = System.nanoTime()
    }

    @Synchronized
    fun addAdBlocked(context: Context) = increment(context, ADS)

    @Synchronized
    fun addMessage(context: Context) = increment(context, MESSAGES)

    @Synchronized
    fun addStream(context: Context) = increment(context, STREAMS)

    /** A proxy (its host) or a backup player type of the ad blocker used for a stream. */
    @Synchronized
    fun addProxy(context: Context, name: String?) {
        if (name.isNullOrBlank()) {
            return
        }
        ensureSince(context)
        val prefs = prefs(context)
        val proxies = JSONObject(prefs.getString(PROXIES, null) ?: "{}")
        proxies.put(name, proxies.optInt(name, 0) + 1)
        prefs.edit { putString(PROXIES, proxies.toString()) }
        changes.value = System.nanoTime()
    }

    private fun increment(context: Context, key: String) {
        ensureSince(context)
        val prefs = prefs(context)
        prefs.edit { putInt(key, prefs.getInt(key, 0) + 1) }
        changes.value = System.nanoTime()
    }

    fun read(context: Context): Snapshot {
        val prefs = prefs(context)
        val streamers = JSONObject(prefs.getString(STREAMERS, null) ?: "{}")
        val names = JSONObject(prefs.getString(NAMES, null) ?: "{}")
        val top = streamers.keys().asSequence().maxByOrNull { streamers.optLong(it, 0) }
        val proxies = JSONObject(prefs.getString(PROXIES, null) ?: "{}")
        val topProxy = proxies.keys().asSequence().maxByOrNull { proxies.optInt(it, 0) }
        return Snapshot(
            watchMs = prefs.getLong(WATCH_MS, 0),
            topStreamer = top?.let { names.optString(it).takeIf { name -> name.isNotBlank() } ?: it },
            topStreamerMs = top?.let { streamers.optLong(it, 0) } ?: 0,
            ads = prefs.getInt(ADS, 0),
            topProxy = topProxy,
            topProxyUses = topProxy?.let { proxies.optInt(it, 0) } ?: 0,
            messages = prefs.getInt(MESSAGES, 0),
            streams = prefs.getInt(STREAMS, 0),
            since = prefs.getLong(SINCE, 0),
        )
    }
}

/**
 * TV: videos started and not finished, for the "Continue watching" row of the home, newest first.
 * The position itself is the one the player saves for every video.
 */
object TvResumeHistory {

    private const val FILE = "tv_resume"
    private const val ITEMS = "items"
    private const val MAX = 20

    /** Changes each time the list is written: the home shows it again even when it never left the screen (mini player). */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

    class Item(
        val id: String,
        val title: String?,
        val channelId: String?,
        val channelLogin: String?,
        val channelName: String?,
        val channelImage: String?,
        val gameId: String?,
        val gameName: String?,
        val thumbnail: String?,
        val createdAt: String?,
        val durationSeconds: Int?,
        val positionMs: Long,
        val type: String?,
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun list(context: Context): List<Item> {
        val array = JSONArray(prefs(context).getString(ITEMS, null) ?: "[]")
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                Item(
                    id = item.optString("id").takeIf { it.isNotBlank() } ?: return@let null,
                    title = item.optString("title").ifBlank { null },
                    channelId = item.optString("channelId").ifBlank { null },
                    channelLogin = item.optString("channelLogin").ifBlank { null },
                    channelName = item.optString("channelName").ifBlank { null },
                    channelImage = item.optString("channelImage").ifBlank { null },
                    gameId = item.optString("gameId").ifBlank { null },
                    gameName = item.optString("gameName").ifBlank { null },
                    thumbnail = item.optString("thumbnail").ifBlank { null },
                    createdAt = item.optString("createdAt").ifBlank { null },
                    durationSeconds = item.optInt("duration", 0).takeIf { it > 0 },
                    positionMs = item.optLong("position", 0),
                    type = item.optString("type").ifBlank { null },
                )
            }
        }
    }

    /** Saved with the position of the player: a video near its end leaves the list. */
    @Synchronized
    fun save(context: Context, item: Item) {
        val durationMs = (item.durationSeconds ?: 0) * 1000L
        val others = list(context).filter { it.id != item.id }
        val finished = durationMs > 0 && item.positionMs > durationMs - 60_000L
        val started = item.positionMs >= 30_000L
        val items = if (!finished && started) listOf(item) + others else others
        write(context, items.take(MAX))
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, list(context).filter { it.id != id })
    }

    private fun write(context: Context, items: List<Item>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title ?: "")
                put("channelId", item.channelId ?: "")
                put("channelLogin", item.channelLogin ?: "")
                put("channelName", item.channelName ?: "")
                put("channelImage", item.channelImage ?: "")
                put("gameId", item.gameId ?: "")
                put("gameName", item.gameName ?: "")
                put("thumbnail", item.thumbnail ?: "")
                put("createdAt", item.createdAt ?: "")
                put("duration", item.durationSeconds ?: 0)
                put("position", item.positionMs)
                put("type", item.type ?: "")
            })
        }
        prefs(context).edit { putString(ITEMS, array.toString()) }
        changes.value = System.nanoTime()
    }
}
