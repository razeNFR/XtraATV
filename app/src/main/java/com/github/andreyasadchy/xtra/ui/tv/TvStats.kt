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
    // 1.2.7: by day (for the periods of the page of the statistics), by category, by hour of the week, longest session
    private const val DAYS = "days"
    private const val STREAMER_DAYS = "streamer_days"
    private const val GAME_DAYS = "game_days"
    private const val GAME_IDS = "game_ids"
    private const val AVATARS = "avatars"
    private const val HOURS = "hours"
    private const val ADS_DAYS = "ads_days"
    private const val STREAMS_DAYS = "streams_days"
    private const val MESSAGES_DAYS = "messages_days"
    private const val LONGEST_MS = "longest_ms"
    private const val LONGEST_LOGIN = "longest_login"
    private const val LONGEST_AT = "longest_at"
    private const val KEEP_DAYS = 400

    /** The session going on: the same channel watched without a stop of more than 2 minutes. */
    private var sessionLogin: String? = null
    private var sessionMs = 0L
    private var sessionLastAt = 0L

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
    fun addWatchTime(
        context: Context,
        channelLogin: String?,
        channelName: String?,
        ms: Long,
        gameId: String? = null,
        gameName: String? = null,
        channelImage: String? = null,
    ) {
        ensureSince(context)
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val day = dayKey(now)
        prefs.edit {
            putLong(WATCH_MS, prefs.getLong(WATCH_MS, 0) + ms)
            // by day
            putString(DAYS, addTo(JSONObject(prefs.getString(DAYS, null) ?: "{}"), day, ms).let { prune(it) }.toString())
            // by hour of the week (monday 0h = 0)
            val hours = JSONArray(prefs.getString(HOURS, null) ?: "[]")
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val weekday = (calendar.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
            val index = weekday * 24 + calendar.get(java.util.Calendar.HOUR_OF_DAY)
            val filled = JSONArray().apply { for (i in 0 until 168) put(if (i < hours.length()) hours.optLong(i, 0) else 0L) }
            filled.put(index, filled.optLong(index, 0) + ms)
            putString(HOURS, filled.toString())
            if (!channelLogin.isNullOrBlank()) {
                val streamers = JSONObject(prefs.getString(STREAMERS, null) ?: "{}")
                streamers.put(channelLogin, streamers.optLong(channelLogin, 0) + ms)
                putString(STREAMERS, streamers.toString())
                putString(STREAMER_DAYS, addToDay(JSONObject(prefs.getString(STREAMER_DAYS, null) ?: "{}"), day, channelLogin, ms).toString())
                if (!channelName.isNullOrBlank()) {
                    val names = JSONObject(prefs.getString(NAMES, null) ?: "{}")
                    names.put(channelLogin, channelName)
                    putString(NAMES, names.toString())
                }
                if (!channelImage.isNullOrBlank()) {
                    val avatars = JSONObject(prefs.getString(AVATARS, null) ?: "{}")
                    avatars.put(channelLogin, channelImage)
                    putString(AVATARS, avatars.toString())
                }
                // the longest session: the same channel watched without a stop
                if (channelLogin == sessionLogin && now - sessionLastAt < 120_000L) {
                    sessionMs += ms
                } else {
                    sessionLogin = channelLogin
                    sessionMs = ms
                }
                sessionLastAt = now
                if (sessionMs > prefs.getLong(LONGEST_MS, 0)) {
                    putLong(LONGEST_MS, sessionMs)
                    putString(LONGEST_LOGIN, channelLogin)
                    putLong(LONGEST_AT, now)
                }
            }
            if (!gameName.isNullOrBlank()) {
                putString(GAME_DAYS, addToDay(JSONObject(prefs.getString(GAME_DAYS, null) ?: "{}"), day, gameName, ms).toString())
                if (!gameId.isNullOrBlank()) {
                    val ids = JSONObject(prefs.getString(GAME_IDS, null) ?: "{}")
                    ids.put(gameName, gameId)
                    putString(GAME_IDS, ids.toString())
                }
            }
        }
        changes.value = System.nanoTime()
    }

    @Synchronized
    fun addAdBlocked(context: Context) = increment(context, ADS, ADS_DAYS)

    @Synchronized
    fun addMessage(context: Context) = increment(context, MESSAGES, MESSAGES_DAYS)

    @Synchronized
    fun addStream(context: Context) = increment(context, STREAMS, STREAMS_DAYS)

    /** "2026-11-03", the day of the TV. */
    private fun dayKey(time: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(time))

    private fun addTo(json: JSONObject, key: String, value: Long): JSONObject = json.put(key, json.optLong(key, 0) + value)

    private fun addToDay(json: JSONObject, day: String, key: String, value: Long): JSONObject {
        val entry = json.optJSONObject(day) ?: JSONObject()
        json.put(day, addTo(entry, key, value))
        return prune(json)
    }

    /** Only the last [KEEP_DAYS] days are kept. */
    private fun prune(json: JSONObject): JSONObject {
        if (json.length() > KEEP_DAYS) {
            json.keys().asSequence().toList().sorted().dropLast(KEEP_DAYS).forEach { json.remove(it) }
        }
        return json
    }

    /** Everything counted forgotten (the button of the page of the statistics), counted again from now. */
    @Synchronized
    fun reset(context: Context) {
        prefs(context).edit { clear() }
        sessionLogin = null
        sessionMs = 0L
        ensureSince(context)
        changes.value = System.nanoTime()
    }

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

    private fun increment(context: Context, key: String, daysKey: String? = null) {
        ensureSince(context)
        val prefs = prefs(context)
        prefs.edit {
            putInt(key, prefs.getInt(key, 0) + 1)
            if (daysKey != null) {
                putString(daysKey, prune(addTo(JSONObject(prefs.getString(daysKey, null) ?: "{}"), dayKey(System.currentTimeMillis()), 1)).toString())
            }
        }
        changes.value = System.nanoTime()
    }

    class Ranked(val key: String, val name: String, val ms: Long, val image: String?)

    /** The page of the statistics, over the last [days] days (null: since the start). */
    class Detail(
        val watchMs: Long,
        val streams: Int,
        val ads: Int,
        val messages: Int,
        val bestStreak: Int,
        val currentStreak: Int,
        /** Time by day, the last 30 days, oldest first. */
        val lastDays: List<Long>,
        val streamers: List<Ranked>,
        val games: List<Ranked>,
        /** 168 values: monday 0h to sunday 23h, since the start. */
        val hours: List<Long>,
        val longestMs: Long,
        val longestName: String?,
        val longestAt: Long,
        val busiestDayMs: Long,
        val busiestDay: Long,
        /** Ads blocked by week, the last 8 weeks, oldest first. */
        val adWeeks: List<Int>,
        val topProxy: String?,
        val topProxyUses: Int,
        val proxies: List<Pair<String, Int>>,
        val since: Long,
    )

    fun detail(context: Context, days: Int?): Detail {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        val keys = days?.let { count -> (0 until count).map { dayKey(now - it * dayMs) }.toSet() }
        fun inPeriod(day: String) = keys == null || day in keys
        fun sumDays(name: String): Long {
            val json = JSONObject(prefs.getString(name, null) ?: "{}")
            return json.keys().asSequence().filter { inPeriod(it) }.sumOf { json.optLong(it, 0) }
        }
        val dayTimes = JSONObject(prefs.getString(DAYS, null) ?: "{}")
        val names = JSONObject(prefs.getString(NAMES, null) ?: "{}")
        val avatars = JSONObject(prefs.getString(AVATARS, null) ?: "{}")
        val gameIds = JSONObject(prefs.getString(GAME_IDS, null) ?: "{}")
        fun ranked(name: String): List<Pair<String, Long>> {
            val json = JSONObject(prefs.getString(name, null) ?: "{}")
            val totals = HashMap<String, Long>()
            json.keys().asSequence().filter { inPeriod(it) }.forEach { day ->
                json.optJSONObject(day)?.let { entry -> entry.keys().forEach { key -> totals[key] = (totals[key] ?: 0L) + entry.optLong(key, 0) } }
            }
            return totals.entries.sortedByDescending { it.value }.map { it.key to it.value }
        }
        // since the start: the totals counted before the days were (1.2.7), the days only for the periods
        val streamerTotals = if (days == null) {
            val json = JSONObject(prefs.getString(STREAMERS, null) ?: "{}")
            json.keys().asSequence().map { it to json.optLong(it, 0) }.sortedByDescending { it.second }.toList()
        } else ranked(STREAMER_DAYS)
        val streak = { ->
            var best = 0
            var current = 0
            var length = 0
            val sorted = dayTimes.keys().asSequence().filter { dayTimes.optLong(it, 0) > 0 }.toSortedSet()
            var previous: Long? = null
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            sorted.forEach { key ->
                val time = format.parse(key)?.time ?: return@forEach
                length = if (previous != null && Math.round((time - previous!!) / dayMs.toDouble()) == 1L) length + 1 else 1
                best = maxOf(best, length)
                previous = time
            }
            // the run going on counts if it reaches today or yesterday
            if (previous != null && Math.round((format.parse(dayKey(now))!!.time - previous!!) / dayMs.toDouble()) <= 1L) {
                current = length
            }
            best to current
        }()
        val busiest = dayTimes.keys().asSequence().maxByOrNull { dayTimes.optLong(it, 0) }
        val adDays = JSONObject(prefs.getString(ADS_DAYS, null) ?: "{}")
        val proxies = JSONObject(prefs.getString(PROXIES, null) ?: "{}")
        val proxyList = proxies.keys().asSequence().map { it to proxies.optInt(it, 0) }.sortedByDescending { it.second }.toList()
        val hours = JSONArray(prefs.getString(HOURS, null) ?: "[]")
        return Detail(
            watchMs = if (days == null) prefs.getLong(WATCH_MS, 0) else sumDays(DAYS),
            streams = if (days == null) prefs.getInt(STREAMS, 0) else sumDays(STREAMS_DAYS).toInt(),
            ads = if (days == null) prefs.getInt(ADS, 0) else sumDays(ADS_DAYS).toInt(),
            messages = if (days == null) prefs.getInt(MESSAGES, 0) else sumDays(MESSAGES_DAYS).toInt(),
            bestStreak = streak.first,
            currentStreak = streak.second,
            lastDays = (29 downTo 0).map { dayTimes.optLong(dayKey(now - it * dayMs), 0) },
            streamers = streamerTotals.take(10).map { (login, ms) ->
                Ranked(login, names.optString(login).takeIf { it.isNotBlank() } ?: login, ms, avatars.optString(login).takeIf { it.isNotBlank() })
            },
            games = ranked(GAME_DAYS).take(5).map { (game, ms) ->
                Ranked(game, game, ms, gameIds.optString(game).takeIf { it.isNotBlank() }?.let { "https://static-cdn.jtvnw.net/ttv-boxart/$it-144x192.jpg" })
            },
            hours = (0 until 168).map { if (it < hours.length()) hours.optLong(it, 0) else 0L },
            longestMs = prefs.getLong(LONGEST_MS, 0),
            longestName = prefs.getString(LONGEST_LOGIN, null)?.let { login -> names.optString(login).takeIf { it.isNotBlank() } ?: login },
            longestAt = prefs.getLong(LONGEST_AT, 0),
            busiestDayMs = busiest?.let { dayTimes.optLong(it, 0) } ?: 0L,
            busiestDay = busiest?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(it)?.time } ?: 0L,
            adWeeks = (7 downTo 0).map { week -> (0 until 7).sumOf { day -> adDays.optInt(dayKey(now - (week * 7 + day) * dayMs), 0) } },
            topProxy = proxyList.firstOrNull()?.first,
            topProxyUses = proxyList.firstOrNull()?.second ?: 0,
            proxies = proxyList.take(3),
            since = prefs.getLong(SINCE, 0),
        )
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
