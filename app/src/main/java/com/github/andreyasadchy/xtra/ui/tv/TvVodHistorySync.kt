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
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * TV: Two-way synchronization of VOD watch history & resume positions with the connected Twitch account:
 * - Pulls `currentUser.viewedVideos` on Home load so VODs started on PC, mobile, or a 2nd TV appear in
 *   "Continue watching" and keep their progress bars up to date across the app.
 * - Resolves `video.self.viewingHistory` when opening a VOD from any channel/category list.
 * - Periodically pushes VOD playback progress (`updateUserViewedVideo`) while watching and on pause/stop.
 * - Synchronizes "Remove from history" (`position = 0` on Twitch + per-account dismissal guard).
 * - Automatically falls back to 100% local behavior when logged out, in Guest mode, or when turned off.
 */
object TvVodHistorySync {

    private const val MIN_PULL_INTERVAL_MS = 20_000L
    private const val PERIODIC_PUSH_INTERVAL_MS = 45_000L
    private const val MAX_DISMISSED_ENTRIES = 120

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pullMutex = Mutex()
    @Volatile
    private var lastPullAtMs = 0L
    @Volatile
    private var lastPullAccountKey: String? = null

    private val lastPushTimeByVideo = ConcurrentHashMap<String, Long>()
    private val lastPushedSecByVideo = ConcurrentHashMap<String, Int>()

    fun isEnabled(context: Context): Boolean {
        val prefs = context.prefs()
        if (!prefs.getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) return false
        if (!prefs.getBoolean(C.TV_SYNC_VOD_HISTORY, true)) return false
        if (TvAccounts.isGuestMode(context)) return false
        return candidateHeaders(context).isNotEmpty()
    }

    private fun candidateHeaders(context: Context): List<Map<String, String>> {
        val primary = TwitchApiHelper.getGQLHeaders(context, true)
        val web = TwitchApiHelper.getGQLWebHeaders(context, true)
        val result = ArrayList<Map<String, String>>(2)
        val primaryToken = primary[C.HEADER_TOKEN]?.takeIf { it.isNotBlank() }
        if (primaryToken != null) {
            result.add(primary)
        }
        val webToken = web[C.HEADER_TOKEN]?.takeIf { it.isNotBlank() }
        if (webToken != null && (primaryToken == null || web[C.HEADER_CLIENT_ID] != primary[C.HEADER_CLIENT_ID] || webToken != primaryToken)) {
            result.add(web)
        }
        return result
    }

    private fun resolveUserId(context: Context): String? =
        context.tokenPrefs().getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }

    private fun dismissedKey(context: Context): String =
        C.TV_DISMISSED_VOD_HISTORY + TvAccounts.storageSuffix(context)

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
                    break
                }
            }
            val remote = remoteList ?: return
            lastPullAtMs = System.currentTimeMillis()
            lastPullAccountKey = accountKey
            if (remote.isEmpty()) return

            val localList = TvResumeHistory.list(appCtx)
            val localById = localList.associateBy { it.id }
            val dismissed = readDismissedMap(appCtx)
            var dismissedChanged = false

            data class Candidate(val item: TvResumeHistory.Item, val sortScore: Long)
            val mergedById = LinkedHashMap<String, Candidate>()
            val baseLegacyTime = 1_000_000L

            // Seed with existing local items first so VODs older than Twitch's top 25 stay intact
            localList.forEachIndexed { idx, item ->
                if (!TvBlacklist.isChannelBlocked(item.channelId, item.channelLogin) &&
                    !TvBlacklist.isCategoryBlocked(item.gameId, null, item.gameName)
                ) {
                    val score = if (item.updatedAtMs > 0L) item.updatedAtMs else (baseLegacyTime - idx)
                    mergedById[item.id] = Candidate(item, score)
                }
            }

            remote.forEachIndexed { remoteIdx, r ->
                if (TvBlacklist.isChannelBlocked(r.channelId, r.channelLogin)) return@forEachIndexed
                if (TvBlacklist.isCategoryBlocked(r.gameId, null, r.gameName)) return@forEachIndexed
                if (TvVideoThumbnail.isMissing(r.thumbnailUrl)) return@forEachIndexed

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
                            if (kotlin.math.abs(currentDb - remotePosMs) >= 3_000L) {
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
                    )
                    mergedById[r.id] = Candidate(mergedItem, chosenUpdatedAt)
                } else if (remoteIsNewer && (finished || chosenPosMs < 30_000L)) {
                    // Finished or reset on another device: remove from Continue Watching
                    mergedById.remove(r.id)
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
