package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.time.Instant

/**
 * TV: Two-way synchronization of VOD watch history & resume positions with the connected Twitch account:
 * - Pulls `currentUser.viewedVideos` on Home load so VODs started on PC, mobile, or a 2nd TV appear in
 *   "Continue watching" and keep their progress bars up to date across the app.
 * - Filters out ongoing live broadcast recordings (`status != RECORDED`, `404_processing`, active `owner.stream`)
 *   and remembers live stream sessions opened in Xtra TV so watched live broadcasts never sneak into
 *   "Continue watching" as viewed VODs unless the user explicitly opens the VOD itself.
 * - Resolves `video.self.viewingHistory` when opening a VOD from any channel/category list.
 * - Periodically pushes VOD playback progress (`updateUserViewedVideo`) while watching and on pause/stop.
 * - Synchronizes "Remove from history" (`position = 0` on Twitch + per-account dismissal guard).
 * - Automatically falls back to 100% local behavior when logged out, in Guest mode, or when turned off.
 */
object TvVodHistorySync {

    private const val MIN_PULL_INTERVAL_MS = 20_000L
    private const val PERIODIC_PUSH_INTERVAL_MS = 45_000L
    private const val LIVE_SESSION_FLUSH_INTERVAL_MS = 60_000L
    private const val STREAM_START_MATCH_WINDOW_MS = 20 * 60_000L
    private const val MAX_DISMISSED_ENTRIES = 120
    private const val MAX_LIVE_SESSIONS = 160
    private const val MAX_EXPLICIT_VODS = 200

    data class LiveSessionEntry(
        val channelId: String?,
        val channelLogin: String,
        val streamId: String?,
        val streamStartMs: Long,
        val openedAtMs: Long,
        val lastSeenAtMs: Long,
        val archiveVideoId: String?,
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pullMutex = Mutex()
    @Volatile
    private var lastPullAtMs = 0L
    @Volatile
    private var lastPullAccountKey: String? = null

    private val lastPushTimeByVideo = ConcurrentHashMap<String, Long>()
    private val lastPushedSecByVideo = ConcurrentHashMap<String, Int>()

    private var cachedLiveSessionsAccountKey: String? = null
    private var cachedLiveSessions: MutableList<LiveSessionEntry>? = null
    private var lastLiveSessionFlushAtMs = 0L

    fun isEnabled(context: Context): Boolean {
        val prefs = context.prefs()
        if (!prefs.getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) return false
        if (!prefs.getBoolean(C.TV_SYNC_VOD_HISTORY, true)) return false
        if (TvAccounts.isGuestMode(context)) return false
        return candidateHeaders(context).isNotEmpty()
    }

    private fun candidateHeaders(context: Context): List<Map<String, String>> {
        val web = TwitchApiHelper.getGQLWebHeaders(context, true)
        val primary = TwitchApiHelper.getGQLHeaders(context, true)
        val result = ArrayList<Map<String, String>>(2)
        val webToken = web[C.HEADER_TOKEN]?.takeIf { it.isNotBlank() }
        if (webToken != null) {
            result.add(web)
        }
        val primaryToken = primary[C.HEADER_TOKEN]?.takeIf { it.isNotBlank() }
        if (primaryToken != null && (webToken == null || primary[C.HEADER_CLIENT_ID] != web[C.HEADER_CLIENT_ID] || primaryToken != webToken)) {
            result.add(primary)
        }
        return result
    }

    private fun resolveUserId(context: Context): String? =
        context.tokenPrefs().getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }

    private fun dismissedKey(context: Context): String =
        C.TV_DISMISSED_VOD_HISTORY + TvAccounts.storageSuffix(context)

    private fun liveSessionsKey(context: Context): String =
        C.TV_WATCHED_LIVE_SESSIONS + TvAccounts.storageSuffix(context)

    private fun explicitOpenedKey(context: Context): String =
        C.TV_EXPLICIT_OPENED_VODS + TvAccounts.storageSuffix(context)

    private fun parseIsoMs(iso: String?): Long =
        iso?.takeIf { it.isNotBlank() }?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: 0L

    @Synchronized
    private fun readDismissedMap(context: Context): LinkedHashMap<String, Long> {
        val raw = context.prefs().getString(dismissedKey(context), null) ?: return LinkedHashMap()
        return runCatching {
            val obj = JSONObject(raw)
            val entries = mutableListOf<Pair<String, Long>>()
            obj.keys().forEach { k ->
                val ts = obj.optLong(k, 0L)
                if (k.isNotBlank() && ts > 0L) entries.add(k to ts)
            }
            entries.sortByDescending { it.second }
            val map = LinkedHashMap<String, Long>()
            entries.take(MAX_DISMISSED_ENTRIES).forEach { (k, v) -> map[k] = v }
            map
        }.getOrDefault(LinkedHashMap())
    }

    @Synchronized
    private fun writeDismissedMap(context: Context, map: Map<String, Long>) {
        val obj = JSONObject()
        map.entries
            .sortedByDescending { it.value }
            .take(MAX_DISMISSED_ENTRIES)
            .forEach { (k, v) -> obj.put(k, v) }
        context.prefs().edit {
            putString(dismissedKey(context), obj.toString())
        }
    }

    @Synchronized
    fun markDismissed(context: Context, videoId: String, atMs: Long = System.currentTimeMillis()) {
        if (videoId.isBlank()) return
        val map = readDismissedMap(context)
        map[videoId] = atMs
        writeDismissedMap(context, map)
    }

    @Synchronized
    fun clearDismissed(context: Context, videoId: String) {
        if (videoId.isBlank()) return
        val map = readDismissedMap(context)
        if (map.remove(videoId) != null) {
            writeDismissedMap(context, map)
        }
    }

    @Synchronized
    fun dismissedAt(context: Context, videoId: String): Long? =
        readDismissedMap(context)[videoId]

    @Synchronized
    private fun readExplicitOpenedMap(context: Context): LinkedHashMap<String, Long> {
        val raw = context.prefs().getString(explicitOpenedKey(context), null) ?: return LinkedHashMap()
        return runCatching {
            val obj = JSONObject(raw)
            val entries = mutableListOf<Pair<String, Long>>()
            obj.keys().forEach { k ->
                val ts = obj.optLong(k, 0L)
                if (k.isNotBlank() && ts > 0L) entries.add(k to ts)
            }
            entries.sortByDescending { it.second }
            val map = LinkedHashMap<String, Long>()
            entries.take(MAX_EXPLICIT_VODS).forEach { (k, v) -> map[k] = v }
            map
        }.getOrDefault(LinkedHashMap())
    }

    @Synchronized
    private fun writeExplicitOpenedMap(context: Context, map: Map<String, Long>) {
        val obj = JSONObject()
        map.entries
            .sortedByDescending { it.value }
            .take(MAX_EXPLICIT_VODS)
            .forEach { (k, v) -> obj.put(k, v) }
        context.prefs().edit {
            putString(explicitOpenedKey(context), obj.toString())
        }
    }

    /**
     * Marks [videoId] as explicitly opened as a VOD by the user in Xtra TV so that even if its live broadcast
     * was previously watched in Xtra TV, watching this VOD now tracks and syncs normally.
     */
    @Synchronized
    fun onExplicitVodOpened(context: Context, videoId: String?, atMs: Long = System.currentTimeMillis()) {
        val cleanId = videoId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val appCtx = context.applicationContext
        val map = readExplicitOpenedMap(appCtx)
        val existing = map[cleanId]
        // Keep the earliest explicit VOD open timestamp so pre-existing live-stream history timestamps remain distinguishable
        map[cleanId] = if (existing != null && existing > 0L) minOf(existing, atMs) else atMs
        writeExplicitOpenedMap(appCtx, map)
    }

    @Synchronized
    private fun explicitOpenedAt(context: Context, videoId: String): Long? =
        readExplicitOpenedMap(context)[videoId]

    @Synchronized
    private fun loadLiveSessionsLocked(context: Context): MutableList<LiveSessionEntry> {
        val key = liveSessionsKey(context)
        val cached = cachedLiveSessions
        if (cached != null && cachedLiveSessionsAccountKey == key) {
            return cached
        }
        val raw = context.prefs().getString(key, null)
        val loaded = mutableListOf<LiveSessionEntry>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val login = o.optString("l", "").trim().lowercase()
                    val cid = o.optString("c", "").trim().takeIf { it.isNotEmpty() }
                    val aid = o.optString("v", "").trim().takeIf { it.isNotEmpty() }
                    if (login.isEmpty() && cid == null && aid == null) continue
                    val opened = o.optLong("o", 0L)
                    val seen = o.optLong("u", opened)
                    if (opened <= 0L && seen <= 0L) continue
                    loaded.add(
                        LiveSessionEntry(
                            channelId = cid,
                            channelLogin = login,
                            streamId = o.optString("s", "").trim().takeIf { it.isNotEmpty() },
                            streamStartMs = o.optLong("st", 0L),
                            openedAtMs = if (opened > 0L) opened else seen,
                            lastSeenAtMs = maxOf(opened, seen),
                            archiveVideoId = aid,
                        )
                    )
                }
            }
        }
        loaded.sortByDescending { it.lastSeenAtMs }
        val trimmed = loaded.take(MAX_LIVE_SESSIONS).toMutableList()
        cachedLiveSessionsAccountKey = key
        cachedLiveSessions = trimmed
        return trimmed
    }

    @Synchronized
    private fun persistLiveSessionsLocked(context: Context, sessions: List<LiveSessionEntry>) {
        val arr = JSONArray()
        sessions
            .sortedByDescending { it.lastSeenAtMs }
            .take(MAX_LIVE_SESSIONS)
            .forEach { e ->
                val o = JSONObject()
                if (!e.channelId.isNullOrBlank()) o.put("c", e.channelId)
                if (e.channelLogin.isNotBlank()) o.put("l", e.channelLogin)
                if (!e.streamId.isNullOrBlank()) o.put("s", e.streamId)
                if (e.streamStartMs > 0L) o.put("st", e.streamStartMs)
                o.put("o", e.openedAtMs)
                o.put("u", e.lastSeenAtMs)
                if (!e.archiveVideoId.isNullOrBlank()) o.put("v", e.archiveVideoId)
                arr.put(o)
            }
        context.prefs().edit {
            putString(liveSessionsKey(context), arr.toString())
        }
        lastLiveSessionFlushAtMs = System.currentTimeMillis()
    }

    /**
     * Records or updates a live broadcast session opened/watched in Xtra TV (single player, switcher, PiP, or Multi-Stream)
     * so Twitch's auto-recorded archive VOD of that live stream will not auto-populate "Continue watching" once you leave.
     */
    @Synchronized
    fun recordLiveStreamSession(
        context: Context,
        channelId: String?,
        channelLogin: String?,
        streamId: String? = null,
        streamCreatedAt: String? = null,
        archiveVideoId: String? = null,
        forcePersist: Boolean = true,
        touchTimestamp: Boolean = true,
    ) {
        val appCtx = context.applicationContext
        val cleanCid = channelId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanLogin = channelLogin?.trim()?.lowercase().orEmpty()
        val cleanSid = streamId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanVid = archiveVideoId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanCid == null && cleanLogin.isEmpty() && cleanVid == null) return

        val now = System.currentTimeMillis()
        val startMs = parseIsoMs(streamCreatedAt)
        val sessions = loadLiveSessionsLocked(appCtx)

        val idx = sessions.indexOfFirst { s ->
            when {
                cleanVid != null && s.archiveVideoId == cleanVid -> true
                cleanSid != null && s.streamId == cleanSid -> true
                (cleanCid != null && s.channelId == cleanCid) || (cleanLogin.isNotEmpty() && s.channelLogin == cleanLogin) -> {
                    when {
                        startMs > 0L && s.streamStartMs > 0L -> abs(startMs - s.streamStartMs) <= STREAM_START_MATCH_WINDOW_MS
                        else -> now - s.lastSeenAtMs <= 45 * 60_000L
                    }
                }
                else -> false
            }
        }

        var metadataEnriched = false
        if (idx >= 0) {
            val prev = sessions[idx]
            val mergedCid = cleanCid ?: prev.channelId
            val mergedLogin = cleanLogin.ifEmpty { prev.channelLogin }
            val mergedSid = cleanSid ?: prev.streamId
            val mergedStart = if (startMs > 0L) startMs else prev.streamStartMs
            val mergedVid = cleanVid ?: prev.archiveVideoId
            metadataEnriched = mergedCid != prev.channelId ||
                mergedLogin != prev.channelLogin ||
                mergedSid != prev.streamId ||
                mergedStart != prev.streamStartMs ||
                mergedVid != prev.archiveVideoId
            val updated = LiveSessionEntry(
                channelId = mergedCid,
                channelLogin = mergedLogin,
                streamId = mergedSid,
                streamStartMs = mergedStart,
                openedAtMs = if (touchTimestamp) minOf(prev.openedAtMs, now) else prev.openedAtMs,
                lastSeenAtMs = if (touchTimestamp) maxOf(prev.lastSeenAtMs, now) else prev.lastSeenAtMs,
                archiveVideoId = mergedVid,
            )
            sessions.removeAt(idx)
            sessions.add(0, updated)
        } else {
            metadataEnriched = true
            sessions.add(
                0,
                LiveSessionEntry(
                    channelId = cleanCid,
                    channelLogin = cleanLogin,
                    streamId = cleanSid,
                    streamStartMs = startMs,
                    openedAtMs = now,
                    lastSeenAtMs = now,
                    archiveVideoId = cleanVid,
                ),
            )
            if (sessions.size > MAX_LIVE_SESSIONS) {
                sessions.subList(MAX_LIVE_SESSIONS, sessions.size).clear()
            }
        }

        if (forcePersist || metadataEnriched || now - lastLiveSessionFlushAtMs >= LIVE_SESSION_FLUSH_INTERVAL_MS) {
            persistLiveSessionsLocked(appCtx, sessions)
        }
    }

    @Synchronized
    private fun snapshotLiveSessions(context: Context): List<LiveSessionEntry> =
        loadLiveSessionsLocked(context.applicationContext).toList()

    /**
     * Filter 1: True when Twitch reports the video is still recording/processing or is the streamer's currently active live broadcast.
     * Note: a finished RECORDED VOD (including restricted/sub-only VODs) whose previewThumbnailURL is null/empty is NOT treated as live;
     * only Twitch's explicit `404_processing` placeholder or non-RECORDED status / active stream match marks it as an ongoing live recording.
     */
    private fun isOngoingLiveRecording(
        videoId: String,
        status: String?,
        thumbnailUrl: String?,
        createdAt: String?,
        ownerLiveStreamCreatedAt: String?,
        ownerLiveArchiveVideoId: String?,
    ): Boolean {
        if (!thumbnailUrl.isNullOrBlank() && thumbnailUrl.contains("404_processing")) return true
        if (!status.isNullOrBlank() && !status.equals("RECORDED", ignoreCase = true)) return true
        if (!ownerLiveArchiveVideoId.isNullOrBlank() && ownerLiveArchiveVideoId == videoId) return true
        val videoStartMs = parseIsoMs(createdAt)
        val liveStartMs = parseIsoMs(ownerLiveStreamCreatedAt)
        if (videoStartMs > 0L && liveStartMs > 0L && abs(videoStartMs - liveStartMs) <= STREAM_START_MATCH_WINDOW_MS) {
            return true
        }
        return false
    }

    /**
     * Filter 2: Matches [videoId] against live broadcast sessions watched in Xtra TV.
     */
    private fun findMatchingLiveSession(
        sessions: List<LiveSessionEntry>,
        videoId: String,
        channelId: String?,
        channelLogin: String?,
        createdAt: String?,
        durationSeconds: Int?,
        broadcastType: String?,
    ): LiveSessionEntry? {
        if (sessions.isEmpty()) return null
        sessions.firstOrNull { !it.archiveVideoId.isNullOrBlank() && it.archiveVideoId == videoId }?.let { return it }

        // Only archive VODs represent auto-recorded live broadcasts (not manual uploads or highlights)
        if (!broadcastType.isNullOrBlank() && !broadcastType.equals("ARCHIVE", ignoreCase = true)) {
            return null
        }

        val cleanCid = channelId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanLogin = channelLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (cleanCid == null && cleanLogin == null) return null

        val videoStartMs = parseIsoMs(createdAt)
        if (videoStartMs <= 0L) return null
        val durationMs = (durationSeconds ?: 0).coerceAtLeast(0) * 1000L
        val videoEndMs = if (durationMs > 0L) videoStartMs + durationMs else Long.MAX_VALUE

        return sessions.firstOrNull { s ->
            val sameChannel = (cleanCid != null && s.channelId == cleanCid) ||
                (cleanLogin != null && s.channelLogin.isNotEmpty() && s.channelLogin == cleanLogin)
            if (!sameChannel) return@firstOrNull false

            if (s.streamStartMs > 0L && abs(videoStartMs - s.streamStartMs) <= STREAM_START_MATCH_WINDOW_MS) {
                return@firstOrNull true
            }
            // Fallback when streamCreatedAt wasn't known at open time: broadcast window overlaps live viewing session
            videoStartMs <= s.lastSeenAtMs + 3 * 60_000L &&
                videoEndMs >= s.openedAtMs - 3 * 60_000L &&
                s.openedAtMs - videoStartMs <= 48 * 3600_000L
        }
    }

    /**
     * Pulls the last viewed VODs from the Twitch account and merges them into [TvResumeHistory] and Room [VideoPosition].
     */
    suspend fun pullFromTwitch(context: Context, xtraModule: XtraModule, force: Boolean = false) {
        val appCtx = context.applicationContext
        if (!isEnabled(appCtx)) return
        val headersList = candidateHeaders(appCtx)
        if (headersList.isEmpty()) return
        val accountKey = TvAccounts.activeAccountKey(appCtx)
        val now = System.currentTimeMillis()
        if (!force && accountKey == lastPullAccountKey && now - lastPullAtMs < MIN_PULL_INTERVAL_MS) {
            return
        }
        pullMutex.withLock {
            val recheckNow = System.currentTimeMillis()
            if (!force && accountKey == lastPullAccountKey && recheckNow - lastPullAtMs < MIN_PULL_INTERVAL_MS) {
                return
            }
            val networkLibrary = appCtx.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            var remoteList: List<com.github.andreyasadchy.xtra.repository.GraphQLRepository.RemoteViewedVideo>? = null
            for (hdrs in headersList) {
                val fetched = xtraModule.graphQLRepository.fetchViewedVideos(networkLibrary, hdrs, limit = 25)
                if (fetched != null) {
                    remoteList = fetched
                    if (fetched.isNotEmpty()) break
                }
            }
            val remote = remoteList ?: return
            lastPullAtMs = System.currentTimeMillis()
            lastPullAccountKey = accountKey
            if (remote.isEmpty()) return

            val localList = TvResumeHistory.list(appCtx)
            val localById = localList.associateBy { it.id }
            val dismissed = readDismissedMap(appCtx)
            val explicitOpened = readExplicitOpenedMap(appCtx)
            val liveSessions = snapshotLiveSessions(appCtx)
            var dismissedChanged = false

            data class Candidate(val item: TvResumeHistory.Item, val sortScore: Long)
            val mergedById = LinkedHashMap<String, Candidate>()
            val baseLegacyTime = 1_000_000L

            // Seed with existing local items first so VODs older than Twitch's top 25 stay intact
            // (while pruning any entry from a watched live session that was never explicitly opened as a VOD)
            localList.forEachIndexed { idx, item ->
                if (!TvBlacklist.isChannelBlocked(item.channelId, item.channelLogin) &&
                    !TvBlacklist.isCategoryBlocked(item.gameId, null, item.gameName) &&
                    (item.thumbnail.isNullOrBlank() || !item.thumbnail.contains("404_processing"))
                ) {
                    val isExplicit = explicitOpened.containsKey(item.id)
                    val matchedLive = if (!isExplicit) {
                        findMatchingLiveSession(
                            sessions = liveSessions,
                            videoId = item.id,
                            channelId = item.channelId,
                            channelLogin = item.channelLogin,
                            createdAt = item.createdAt,
                            durationSeconds = item.durationSeconds,
                            broadcastType = item.type,
                        )
                    } else null
                    val watchedLaterAsVod = matchedLive != null &&
                        item.updatedAtMs > matchedLive.lastSeenAtMs + 10 * 60_000L
                    if (matchedLive == null || watchedLaterAsVod) {
                        val score = if (item.updatedAtMs > 0L) item.updatedAtMs else (baseLegacyTime - idx)
                        mergedById[item.id] = Candidate(item, score)
                    } else {
                        item.id.toLongOrNull()?.let { idNum ->
                            runCatching { xtraModule.playerRepository.saveVideoPosition(VideoPosition(idNum, 0L)) }
                        }
                    }
                }
            }

            remote.forEachIndexed { remoteIdx, r ->
                if (TvBlacklist.isChannelBlocked(r.channelId, r.channelLogin)) return@forEachIndexed
                if (TvBlacklist.isCategoryBlocked(r.gameId, null, r.gameName)) return@forEachIndexed

                // Filter 1: Ongoing live broadcast recording (status != RECORDED, 404_processing thumbnail, or active stream)
                if (isOngoingLiveRecording(
                        videoId = r.id,
                        status = r.status,
                        thumbnailUrl = r.thumbnailUrl,
                        createdAt = r.createdAt,
                        ownerLiveStreamCreatedAt = r.ownerLiveStreamCreatedAt,
                        ownerLiveArchiveVideoId = r.ownerLiveArchiveVideoId,
                    )
                ) {
                    if (!explicitOpened.containsKey(r.id)) {
                        if (mergedById.remove(r.id) != null) {
                            r.id.toLongOrNull()?.let { idNum ->
                                runCatching { xtraModule.playerRepository.saveVideoPosition(VideoPosition(idNum, 0L)) }
                            }
                        }
                    }
                    return@forEachIndexed
                }

                // Filter 2: Live broadcast watched on this device — ignore its auto-recorded archive UNLESS
                // it was explicitly watched later as a finished VOD on another device (updatedAtMs > live end + 10 min).
                if (!explicitOpened.containsKey(r.id)) {
                    val matchedLive = findMatchingLiveSession(
                        sessions = liveSessions,
                        videoId = r.id,
                        channelId = r.channelId,
                        channelLogin = r.channelLogin,
                        createdAt = r.createdAt,
                        durationSeconds = r.durationSeconds,
                        broadcastType = r.broadcastType,
                    )
                    if (matchedLive != null) {
                        val watchedLaterOnOtherDevice = r.updatedAtMs > matchedLive.lastSeenAtMs + 10 * 60_000L
                        if (!watchedLaterOnOtherDevice) {
                            // Cache archiveVideoId without overwriting lastSeenAtMs
                            if (matchedLive.archiveVideoId != r.id) {
                                recordLiveStreamSession(
                                    context = appCtx,
                                    channelId = r.channelId ?: matchedLive.channelId,
                                    channelLogin = r.channelLogin ?: matchedLive.channelLogin,
                                    streamId = matchedLive.streamId,
                                    streamCreatedAt = r.createdAt,
                                    archiveVideoId = r.id,
                                    touchTimestamp = false,
                                )
                            }
                            if (mergedById.remove(r.id) != null) {
                                r.id.toLongOrNull()?.let { idNum ->
                                    runCatching { xtraModule.playerRepository.saveVideoPosition(VideoPosition(idNum, 0L)) }
                                }
                            }
                            return@forEachIndexed
                        }
                    }
                }

                val dismissedTs = dismissed[r.id]
                if (dismissedTs != null) {
                    if (r.updatedAtMs <= 0L || r.updatedAtMs <= dismissedTs + 5_000L) {
                        return@forEachIndexed
                    } else {
                        // Watched again on another device after dismissal
                        dismissed.remove(r.id)
                        dismissedChanged = true
                    }
                }

                val remotePosMs = r.positionSeconds * 1000L
                val local = localById[r.id]
                val durationSec = r.durationSeconds ?: local?.durationSeconds
                val durationMs = (durationSec ?: 0) * 1000L

                val remoteIsNewer = when {
                    local == null -> true
                    r.updatedAtMs > 0L && local.updatedAtMs > 0L -> r.updatedAtMs > local.updatedAtMs + 2_000L
                    else -> remotePosMs > local.positionMs
                }

                val chosenPosMs = if (remoteIsNewer) remotePosMs else local!!.positionMs
                val chosenUpdatedAt = when {
                    remoteIsNewer && r.updatedAtMs > 0L -> r.updatedAtMs
                    local != null && local.updatedAtMs > 0L -> local.updatedAtMs
                    r.updatedAtMs > 0L -> r.updatedAtMs
                    else -> baseLegacyTime - remoteIdx
                }

                // Sync Room VideoPosition when remote has a newer valid progress
                if (remoteIsNewer && remotePosMs >= 30_000L) {
                    r.id.toLongOrNull()?.let { idNum ->
                        runCatching {
                            val currentDb = xtraModule.playerRepository.getVideoPosition(idNum)?.position ?: 0L
                            if (abs(currentDb - remotePosMs) >= 3_000L) {
                                xtraModule.playerRepository.saveVideoPosition(VideoPosition(idNum, remotePosMs))
                            }
                        }
                    }
                }

                val started = chosenPosMs >= 30_000L
                val finished = durationMs > 0L && chosenPosMs > durationMs - 60_000L
                if (started && !finished) {
                    val resolvedThumb = r.thumbnailUrl?.let { TwitchApiHelper.getVideoThumbnail(it) } ?: local?.thumbnail
                    val resolvedAvatar = r.channelImageUrl?.let { TwitchApiHelper.getProfileImage(it) } ?: local?.channelImage
                    val mergedItem = TvResumeHistory.Item(
                        id = r.id,
                        title = r.title ?: local?.title,
                        channelId = r.channelId ?: local?.channelId,
                        channelLogin = r.channelLogin ?: local?.channelLogin,
                        channelName = r.channelName ?: local?.channelName,
                        channelImage = resolvedAvatar,
                        gameId = r.gameId ?: local?.gameId,
                        gameName = r.gameName ?: local?.gameName,
                        thumbnail = resolvedThumb,
                        createdAt = r.createdAt ?: local?.createdAt,
                        durationSeconds = durationSec,
                        positionMs = chosenPosMs,
                        type = r.broadcastType ?: local?.type ?: "ARCHIVE",
                        updatedAtMs = if (chosenUpdatedAt > baseLegacyTime) chosenUpdatedAt else 0L,
                        subOnly = r.subOnly || local?.subOnly == true,
                    )
                    mergedById[r.id] = Candidate(mergedItem, chosenUpdatedAt)
                } else if (remoteIsNewer && (finished || chosenPosMs < 30_000L)) {
                    // Finished or reset/removed on another device: remove from Continue Watching & clear local Room position
                    mergedById.remove(r.id)
                    if (chosenPosMs < 30_000L) {
                        r.id.toLongOrNull()?.let { idNum ->
                            runCatching { xtraModule.playerRepository.saveVideoPosition(VideoPosition(idNum, 0L)) }
                        }
                    }
                }
            }

            if (dismissedChanged) {
                writeDismissedMap(appCtx, dismissed)
            }

            val finalItems = mergedById.values
                .sortedByDescending { it.sortScore }
                .map { it.item }
                .take(20)
            TvResumeHistory.replaceAll(appCtx, finalItems)
        }
    }

    /**
     * Resolves the freshest resume position when opening a VOD with no explicit offset (`savedPosition == null`),
     * bounded to 1.2s so playback start is never delayed on slow networks.
     */
    suspend fun resolvePositionOnOpen(
        context: Context,
        xtraModule: XtraModule,
        videoId: String,
        localDbPositionMs: Long?,
    ): Long? {
        val appCtx = context.applicationContext
        if (!isEnabled(appCtx) || videoId.isBlank()) return localDbPositionMs
        val headersList = candidateHeaders(appCtx)
        if (headersList.isEmpty()) return localDbPositionMs
        val networkLibrary = appCtx.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)

        val remote = withTimeoutOrNull(1_200L) {
            var found: com.github.andreyasadchy.xtra.repository.GraphQLRepository.SingleVideoViewingHistory? = null
            for (hdrs in headersList) {
                val res = xtraModule.graphQLRepository.fetchSingleVideoViewingHistory(networkLibrary, hdrs, videoId)
                if (res != null) {
                    found = res
                    break
                }
            }
            found
        } ?: return localDbPositionMs

        // Ignore remote live-stream recording timestamps if this video is still recording
        if (isOngoingLiveRecording(
                videoId = videoId,
                status = remote.status,
                thumbnailUrl = null,
                createdAt = remote.createdAt,
                ownerLiveStreamCreatedAt = remote.ownerLiveStreamCreatedAt,
                ownerLiveArchiveVideoId = remote.ownerLiveArchiveVideoId,
            )
        ) {
            return localDbPositionMs
        }

        // If the VOD has no local progress yet and its remote timestamp came from a live session watched in Xtra TV
        // before the user explicitly opened the VOD, start cleanly instead of jumping to the live stream's exit point
        if (localDbPositionMs == null || localDbPositionMs <= 0L) {
            val matchedLive = findMatchingLiveSession(
                sessions = snapshotLiveSessions(appCtx),
                videoId = videoId,
                channelId = remote.channelId,
                channelLogin = remote.channelLogin,
                createdAt = remote.createdAt,
                durationSeconds = remote.durationSeconds,
                broadcastType = remote.broadcastType,
            )
            if (matchedLive != null) {
                val explicitTs = explicitOpenedAt(appCtx, videoId)
                if (explicitTs == null || remote.updatedAtMs <= 0L || remote.updatedAtMs <= maxOf(matchedLive.lastSeenAtMs + 10 * 60_000L, explicitTs + 2_000L)) {
                    return localDbPositionMs
                }
            }
        }

        val dismissedTs = dismissedAt(appCtx, videoId)
        if (dismissedTs != null && (remote.updatedAtMs <= 0L || remote.updatedAtMs <= dismissedTs + 5_000L)) {
            return localDbPositionMs
        }

        val remotePosMs = remote.positionSeconds * 1000L
        if (remotePosMs < 30_000L) return localDbPositionMs
        val durationMs = (remote.durationSeconds ?: 0) * 1000L
        if (durationMs > 0L && remotePosMs > durationMs - 60_000L) {
            return localDbPositionMs
        }

        val localItem = TvResumeHistory.list(appCtx).find { it.id == videoId }
        val useRemote = when {
            localDbPositionMs == null || localDbPositionMs <= 0L -> true
            localItem != null && localItem.updatedAtMs > 0L && remote.updatedAtMs > 0L ->
                remote.updatedAtMs > localItem.updatedAtMs + 2_000L
            else -> remotePosMs > localDbPositionMs + 5_000L
        }
        if (useRemote) {
            if (dismissedTs != null) clearDismissed(appCtx, videoId)
            videoId.toLongOrNull()?.let { idNum ->
                runCatching { xtraModule.playerRepository.saveVideoPosition(VideoPosition(idNum, remotePosMs)) }
            }
            return remotePosMs
        }
        return localDbPositionMs
    }

    /**
     * Pushes VOD playback position to Twitch (`updateUserViewedVideo`), throttled during continuous playback
     * and immediate when `force == true` (pause, stop, or seek-save).
     */
    fun pushPositionAsync(
        context: Context,
        xtraModule: XtraModule,
        videoId: String?,
        positionMs: Long,
        force: Boolean = false,
    ) {
        val cleanId = videoId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val appCtx = context.applicationContext
        if (positionMs >= 30_000L) {
            clearDismissed(appCtx, cleanId)
        }
        if (!isEnabled(appCtx)) return
        val userId = resolveUserId(appCtx) ?: return
        val headersList = candidateHeaders(appCtx)
        if (headersList.isEmpty()) return

        val posSec = (positionMs.coerceAtLeast(0L) / 1000L).toInt()
        if (!force && posSec < 5) return

        val now = System.currentTimeMillis()
        val lastAt = lastPushTimeByVideo[cleanId] ?: 0L
        if (!force && now - lastAt < PERIODIC_PUSH_INTERVAL_MS) {
            return
        }
        if (lastPushedSecByVideo[cleanId] == posSec && (!force || now - lastAt < 10_000L)) {
            return
        }
        lastPushTimeByVideo[cleanId] = now
        lastPushedSecByVideo[cleanId] = posSec

        val networkLibrary = appCtx.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        syncScope.launch {
            for (hdrs in headersList) {
                val ok = xtraModule.graphQLRepository.updateViewedVideoPosition(
                    networkLibrary = networkLibrary,
                    headers = hdrs,
                    userId = userId,
                    videoId = cleanId,
                    positionSeconds = posSec,
                )
                if (ok) break
            }
        }
    }

    /**
     * Removes a VOD from local history and resets its resume position to `0` on Twitch so it disappears across devices.
     */
    fun removeFromHistory(
        context: Context,
        xtraModule: XtraModule,
        videoId: String,
        onLocalDone: (() -> Unit)? = null,
    ) {
        val appCtx = context.applicationContext
        markDismissed(appCtx, videoId)
        TvResumeHistory.remove(appCtx, videoId)
        lastPushedSecByVideo[videoId] = 0
        lastPushTimeByVideo[videoId] = System.currentTimeMillis()

        syncScope.launch {
            videoId.toLongOrNull()?.let { idNum ->
                runCatching { xtraModule.playerRepository.saveVideoPosition(VideoPosition(idNum, 0L)) }
            }
            onLocalDone?.let { cb ->
                kotlinx.coroutines.withContext(Dispatchers.Main) { cb() }
            }
            if (!isEnabled(appCtx)) return@launch
            val userId = resolveUserId(appCtx) ?: return@launch
            val headersList = candidateHeaders(appCtx)
            val networkLibrary = appCtx.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            for (hdrs in headersList) {
                val ok = xtraModule.graphQLRepository.updateViewedVideoPosition(
                    networkLibrary = networkLibrary,
                    headers = hdrs,
                    userId = userId,
                    videoId = videoId,
                    positionSeconds = 0,
                )
                if (ok) break
            }
        }
    }
}
