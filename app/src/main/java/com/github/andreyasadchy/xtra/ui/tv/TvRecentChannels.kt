package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * TV: Last 20 unique live channels watched for at least 30 seconds, newest first.
 * Stored per-account in `context.prefs()` (`tv_recent_channels` + `TvAccounts.storageSuffix`) so it is
 * automatically isolated across multi-accounts / guest mode and included in `TvBackup`.
 */
object TvRecentChannels {

    const val MAX = 20
    const val FILTER_ALL = "all"
    const val FILTER_UNFOLLOWED = "unfollowed"

    data class Entry(
        val channelId: String?,
        val login: String,
        val displayName: String,
        val avatarUrl: String?,
        val watchedAt: Long,
    )

    data class ResolvedItem(
        val entry: Entry,
        val liveStream: Stream?,
    ) {
        val login: String get() = liveStream?.channelLogin ?: entry.login
        val channelId: String? get() = liveStream?.channelId ?: entry.channelId
        val displayName: String get() = liveStream?.channelName?.takeIf { it.isNotBlank() } ?: entry.displayName
        val avatarUrl: String?
            get() = liveStream?.channelImage
                ?: entry.avatarUrl?.takeIf { it.isNotBlank() }?.let { TwitchApiHelper.getProfileImage(it) }
        val isLive: Boolean get() = liveStream != null
    }

    val changes = MutableStateFlow(0L)

    private fun storageKey(context: Context): String =
        C.TV_RECENT_CHANNELS + TvAccounts.storageSuffix(context)

    fun notifyAccountSwitched() {
        changes.value = System.nanoTime()
    }

    fun filterMode(context: Context): String {
        val raw = context.prefs().getString(C.TV_RECENT_CHANNELS_FILTER, FILTER_ALL)
        return if (raw == FILTER_UNFOLLOWED) FILTER_UNFOLLOWED else FILTER_ALL
    }

    fun setFilterMode(context: Context, mode: String) {
        val normalized = if (mode == FILTER_UNFOLLOWED) FILTER_UNFOLLOWED else FILTER_ALL
        context.prefs().edit {
            putString(C.TV_RECENT_CHANNELS_FILTER, normalized)
        }
        changes.value = System.nanoTime()
    }

    @Synchronized
    private fun readRaw(context: Context): List<Entry> {
        val raw = context.prefs().getString(storageKey(context), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val seen = HashSet<String>()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val login = obj.optString("login").trim().takeIf { it.isNotEmpty() } ?: continue
                    if (!seen.add(login.lowercase())) continue
                    val channelId = obj.optString("channelId").trim().ifEmpty { null }
                    val displayName = obj.optString("displayName").trim().ifEmpty { login }
                    val avatarUrl = obj.optString("avatarUrl").trim().ifEmpty { null }
                    val watchedAt = obj.optLong("watchedAt", 0L)
                    add(
                        Entry(
                            channelId = channelId,
                            login = login,
                            displayName = displayName,
                            avatarUrl = avatarUrl,
                            watchedAt = watchedAt,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun list(context: Context): List<Entry> =
        readRaw(context)
            .filterNot { TvBlacklist.isChannelBlocked(it.channelId, it.login) }
            .take(MAX)

    @Synchronized
    fun count(context: Context): Int = list(context).size

    @Synchronized
    fun recordWatched(
        context: Context,
        channelId: String?,
        login: String?,
        displayName: String?,
        avatarUrl: String?,
    ) {
        val cleanLogin = login?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (TvBlacklist.isChannelBlocked(channelId, cleanLogin)) return
        val existingList = readRaw(context)
        val previous = existingList.find { it.login.equals(cleanLogin, ignoreCase = true) }
        val merged = Entry(
            channelId = channelId?.takeIf { it.isNotBlank() } ?: previous?.channelId,
            login = cleanLogin,
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.displayName ?: cleanLogin,
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() } ?: previous?.avatarUrl,
            watchedAt = System.currentTimeMillis(),
        )
        val updated = (listOf(merged) + existingList.filterNot { it.login.equals(cleanLogin, ignoreCase = true) })
            .take(MAX)
        write(context, updated, notify = true)
    }

    @Synchronized
    fun updateMetadataIfPresent(
        context: Context,
        updatesByLowerLogin: Map<String, Triple<String?, String?, String?>>,
    ) {
        if (updatesByLowerLogin.isEmpty()) return
        val current = readRaw(context)
        if (current.isEmpty()) return
        var modified = false
        val updated = current.map { entry ->
            val meta = updatesByLowerLogin[entry.login.lowercase()] ?: return@map entry
            val nextId = meta.first?.takeIf { it.isNotBlank() } ?: entry.channelId
            val nextName = meta.second?.takeIf { it.isNotBlank() } ?: entry.displayName
            val nextAvatar = meta.third?.takeIf { it.isNotBlank() } ?: entry.avatarUrl
            if (nextId != entry.channelId || nextName != entry.displayName || nextAvatar != entry.avatarUrl) {
                modified = true
                entry.copy(channelId = nextId, displayName = nextName, avatarUrl = nextAvatar)
            } else {
                entry
            }
        }
        if (modified) {
            write(context, updated, notify = false)
        }
    }

    @Synchronized
    fun remove(context: Context, login: String) {
        val current = readRaw(context)
        val updated = current.filterNot { it.login.equals(login, ignoreCase = true) }
        if (updated.size != current.size) {
            write(context, updated, notify = true)
        }
    }

    @Synchronized
    fun clear(context: Context) {
        context.prefs().edit {
            remove(storageKey(context))
        }
        changes.value = System.nanoTime()
    }

    private fun write(context: Context, entries: List<Entry>, notify: Boolean) {
        val arr = JSONArray()
        entries.take(MAX).forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("channelId", e.channelId ?: "")
                    put("login", e.login)
                    put("displayName", e.displayName)
                    put("avatarUrl", e.avatarUrl ?: "")
                    put("watchedAt", e.watchedAt)
                }
            )
        }
        context.prefs().edit {
            putString(storageKey(context), arr.toString())
        }
        if (notify) {
            changes.value = System.nanoTime()
        }
    }
}
