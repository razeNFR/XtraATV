package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** TV: saved groups of 2 to 4 streamers to launch a multi-stream in one click when they are live. */
object TvMultiStreamGroups {

    private const val MAX_GROUPS = 8

    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    class Group(
        val id: String,
        val label: String,
        val streams: List<Stream>,
    )

    fun idOf(streams: List<Stream>): String {
        val logins = streams
            .mapNotNull { it.channelLogin?.trim()?.lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        return if (logins.size >= 2) logins.joinToString(",") else ""
    }

    @Synchronized
    fun list(context: Context): List<Group> {
        val raw = context.prefs().getString(C.TV_MULTISTREAM_GROUPS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val streamsArr = obj.optJSONArray("streams") ?: return@mapNotNull null
                val streams = (0 until streamsArr.length()).mapNotNull { j ->
                    val s = streamsArr.optJSONObject(j) ?: return@mapNotNull null
                    val login = s.optString("login").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    Stream(
                        channelId = s.optString("channelId").ifBlank { null },
                        channelLogin = login,
                        channelName = s.optString("name").ifBlank { login },
                        channelImageURL = s.optString("image").ifBlank { null },
                    )
                }.distinctBy { it.channelLogin?.lowercase() }
                val id = idOf(streams).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val label = obj.optString("label").takeIf { it.isNotBlank() }
                    ?: streams.joinToString(" + ") { it.channelName ?: it.channelLogin.orEmpty() }
                Group(id = id, label = label, streams = streams)
            }
        }.getOrDefault(emptyList())
    }

    fun isSaved(context: Context, streams: List<Stream>): Boolean {
        val id = idOf(streams)
        return id.isNotEmpty() && list(context).any { it.id == id }
    }

    @Synchronized
    fun save(context: Context, streams: List<Stream>) {
        val clean = streams
            .filter { !it.channelLogin.isNullOrBlank() }
            .distinctBy { it.channelLogin?.lowercase() }
            .take(4)
        val id = idOf(clean)
        if (id.isEmpty()) return
        val existing = list(context).find { it.id == id }
        val label = existing?.label?.takeIf { it.isNotBlank() } ?: clean.joinToString(" + ") {
            it.channelName?.takeIf { n -> n.isNotBlank() } ?: it.channelLogin.orEmpty()
        }
        val updated = listOf(Group(id, label, clean)) + list(context).filterNot { it.id == id }
        write(context, updated.take(MAX_GROUPS))
    }

    @Synchronized
    fun rename(context: Context, id: String, newLabel: String) {
        val trimmed = newLabel.trim()
        val updated = list(context).map { group ->
            if (group.id == id) {
                val fallback = group.streams.joinToString(" + ") {
                    it.channelName?.takeIf { n -> n.isNotBlank() } ?: it.channelLogin.orEmpty()
                }
                Group(group.id, trimmed.ifEmpty { fallback }, group.streams)
            } else {
                group
            }
        }
        write(context, updated)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, list(context).filterNot { it.id == id })
    }

    private fun write(context: Context, groups: List<Group>) {
        val array = JSONArray()
        groups.forEach { group ->
            val streamsArr = JSONArray()
            group.streams.forEach { s ->
                streamsArr.put(JSONObject().apply {
                    put("login", s.channelLogin ?: "")
                    put("name", s.channelName ?: "")
                    put("channelId", s.channelId ?: "")
                    put("image", s.channelImageURL ?: "")
                })
            }
            array.put(JSONObject().apply {
                put("id", group.id)
                put("label", group.label)
                put("streams", streamsArr)
            })
        }
        context.prefs().edit {
            if (groups.isEmpty()) remove(C.TV_MULTISTREAM_GROUPS)
            else putString(C.TV_MULTISTREAM_GROUPS, array.toString())
        }
        changes.value++
    }

    fun launchGroup(activity: MainActivity, group: Group, origin: Stream? = null) {
        val logins = group.streams.mapNotNull { it.channelLogin?.trim()?.lowercase() }.distinct()
        if (logins.isEmpty()) return
        activity.lifecycleScope.launch {
            val liveMap: Map<String, Stream>? = try {
                val repo = (activity.applicationContext as XtraApp).xtraModule.graphQLRepository
                val response = repo.loadQueryUsersStream(
                    activity.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(activity),
                    logins = logins,
                )
                val map = mutableMapOf<String, Stream>()
                response.data?.users?.forEach { user ->
                    val login = user?.login ?: return@forEach
                    val st = user.stream ?: return@forEach
                    map[login.lowercase()] = Stream(
                        id = st.id,
                        channelId = user.id,
                        channelLogin = login,
                        channelName = user.displayName ?: login,
                        channelImageURL = user.profileImageURL?.toString(),
                        gameId = st.game?.id,
                        gameSlug = st.game?.slug,
                        gameName = st.game?.displayName,
                        title = st.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = st.previewImageURL?.toString(),
                        createdAt = st.createdAt?.toString(),
                        viewerCount = st.viewersCount,
                        tags = st.freeformTags?.mapNotNull { it?.name },
                    )
                }
                map
            } catch (_: Exception) {
                null
            }
            val live = if (liveMap != null) {
                logins.mapNotNull { liveMap[it] }
            } else {
                group.streams
            }
            when {
                live.size >= 2 -> activity.startMultiStream(live, origin)
                live.size == 1 -> {
                    activity.closeMultiStream()
                    activity.startStream(live.first())
                }
                else -> Toast.makeText(activity, R.string.tv_multistream_group_offline, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
