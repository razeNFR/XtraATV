package com.github.andreyasadchy.xtra.ui.tv.home

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.paging.PagingSource
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.repository.datasource.GamesDataSource
import com.github.andreyasadchy.xtra.repository.datasource.SearchGamesDataSource
import com.github.andreyasadchy.xtra.ui.tv.ButtonRow
import com.github.andreyasadchy.xtra.ui.tv.FocusRow
import com.github.andreyasadchy.xtra.ui.tv.SelectorRow
import com.github.andreyasadchy.xtra.ui.tv.SidePanel
import com.github.andreyasadchy.xtra.ui.tv.TvColors
import com.github.andreyasadchy.xtra.ui.tv.TvDialogs
import com.github.andreyasadchy.xtra.ui.tv.TvSearchBar
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Ordered, customizable rows on the TV Home screen:
 * - Reorder, show, or hide any built-in row.
 * - Optional discovery rows: Favorite channels live, Recommended channels, Top clips of followed channels, Followed categories.
 * - Custom pinned category rows (`cat:<gameId>:<langMode>:<slug>:<name>`) and per-language stream rows (`lang:<code>`).
 */
object TvHomeRows {

    const val ROW_FOLLOWED = "followed"
    const val ROW_FAVORITES = "favorites"
    const val ROW_MULTISTREAMS = "multistreams"
    const val ROW_POPULAR = "popular"
    const val ROW_RECOMMENDED = "recommended"
    const val ROW_RESUME = "resume"
    const val ROW_FOLLOWED_VIDEOS = "followed_videos"
    const val ROW_FOLLOWED_CLIPS = "followed_clips"
    const val ROW_FOLLOWED_GAMES = "followed_games"
    const val ROW_STATS = "stats"
    const val ROW_GAMES = "games"

    const val LANG_MODE_PREF = "pref"
    const val LANG_MODE_ALL = "all"

    private val BUILTIN_ORDER = listOf(
        ROW_FOLLOWED,
        ROW_FAVORITES,
        ROW_MULTISTREAMS,
        ROW_POPULAR,
        ROW_RECOMMENDED,
        ROW_RESUME,
        ROW_FOLLOWED_VIDEOS,
        ROW_FOLLOWED_CLIPS,
        ROW_FOLLOWED_GAMES,
        ROW_STATS,
        ROW_GAMES,
    )

    data class Entry(val id: String, val enabled: Boolean)

    data class CategorySpec(
        val id: String,
        val gameId: String,
        val langMode: String,
        val slug: String,
        val name: String,
    )

    val changes = MutableStateFlow(0L)

    fun buildCategoryId(game: Game, langMode: String = LANG_MODE_PREF): String? {
        val gId = game.id?.takeIf { it.isNotBlank() } ?: return null
        val mode = if (langMode == LANG_MODE_ALL) LANG_MODE_ALL else LANG_MODE_PREF
        val encodedSlug = Uri.encode(game.slug.orEmpty())
        val encodedName = Uri.encode(game.name ?: game.slug ?: gId)
        return "cat:$gId:$mode:$encodedSlug:$encodedName"
    }

    fun parseCategory(id: String): CategorySpec? {
        if (!id.startsWith("cat:")) return null
        val parts = id.split(":", limit = 5)
        if (parts.size < 5) return null
        val gameId = parts[1].takeIf { it.isNotBlank() } ?: return null
        val langMode = if (parts[2] == LANG_MODE_ALL) LANG_MODE_ALL else LANG_MODE_PREF
        val slug = Uri.decode(parts[3])
        val name = Uri.decode(parts[4]).ifBlank { slug.ifBlank { gameId } }
        return CategorySpec(id = id, gameId = gameId, langMode = langMode, slug = slug, name = name)
    }

    fun parseLanguage(id: String): String? =
        if (id.startsWith("lang:")) id.removePrefix("lang:").takeIf { it.isNotBlank() } else null

    fun isRemovable(id: String): Boolean = id.startsWith("cat:") || id.startsWith("lang:")

    private fun isValidEntryId(id: String): Boolean =
        id in BUILTIN_ORDER || parseCategory(id) != null || parseLanguage(id) != null

    fun defaultEntries(context: Context, includeLegacyPrefs: Boolean = true): List<Entry> {
        val prefs = context.prefs()
        val multiEnabled = if (includeLegacyPrefs) prefs.getBoolean(C.TV_HOME_MULTISTREAM, true) else true
        val videosEnabled = if (includeLegacyPrefs) prefs.getBoolean(C.TV_HOME_FOLLOWED_VIDEOS, true) else true
        val legacyLanguages = if (includeLegacyPrefs) {
            prefs.getStringSet(C.TV_HOME_LANGUAGE_ROWS, null)
                ?.filter { it.isNotBlank() }
                ?.sorted()
                .orEmpty()
        } else {
            emptyList()
        }
        return buildList {
            add(Entry(ROW_FOLLOWED, true))
            add(Entry(ROW_FAVORITES, false))
            add(Entry(ROW_MULTISTREAMS, multiEnabled))
            add(Entry(ROW_POPULAR, true))
            legacyLanguages.forEach { code ->
                add(Entry("lang:$code", true))
            }
            add(Entry(ROW_RECOMMENDED, false))
            add(Entry(ROW_RESUME, true))
            add(Entry(ROW_FOLLOWED_VIDEOS, videosEnabled))
            add(Entry(ROW_FOLLOWED_CLIPS, false))
            add(Entry(ROW_FOLLOWED_GAMES, false))
            add(Entry(ROW_STATS, true))
            add(Entry(ROW_GAMES, true))
        }
    }

    private fun defaultEnabledForBuiltin(context: Context, id: String): Boolean = when (id) {
        ROW_FOLLOWED, ROW_POPULAR, ROW_RESUME, ROW_STATS, ROW_GAMES -> true
        ROW_MULTISTREAMS -> context.prefs().getBoolean(C.TV_HOME_MULTISTREAM, true)
        ROW_FOLLOWED_VIDEOS -> context.prefs().getBoolean(C.TV_HOME_FOLLOWED_VIDEOS, true)
        else -> false
    }

    fun load(context: Context): List<Entry> {
        val prefs = context.prefs()
        val raw = prefs.getString(C.TV_HOME_ROWS, null)
        if (raw.isNullOrBlank()) {
            val initial = defaultEntries(context, includeLegacyPrefs = true)
            persist(context, initial, notify = false)
            return initial
        }
        val parsed = runCatching {
            val arr = JSONArray(raw)
            val seen = HashSet<String>()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    val enabled = obj.optBoolean("enabled", false)
                    if (isValidEntryId(id) && seen.add(id)) {
                        add(Entry(id, enabled))
                    }
                }
            }
        }.getOrNull()
        if (parsed.isNullOrEmpty()) {
            val fallback = defaultEntries(context, includeLegacyPrefs = true)
            persist(context, fallback, notify = false)
            return fallback
        }
        val existingIds = parsed.map { it.id }.toSet()
        val missingBuiltins = BUILTIN_ORDER.filter { it !in existingIds }
        if (missingBuiltins.isEmpty()) {
            return parsed
        }
        val completed = parsed.toMutableList()
        missingBuiltins.forEach { missingId ->
            completed.add(Entry(missingId, defaultEnabledForBuiltin(context, missingId)))
        }
        persist(context, completed, notify = false)
        return completed
    }

    fun save(context: Context, entries: List<Entry>) {
        persist(context, entries, notify = true)
    }

    private fun persist(context: Context, entries: List<Entry>, notify: Boolean) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("enabled", e.enabled))
        }
        val activeLangs = entries
            .filter { it.enabled && it.id.startsWith("lang:") }
            .mapNotNull { parseLanguage(it.id) }
            .toSet()
        val multiEnabled = entries.find { it.id == ROW_MULTISTREAMS }?.enabled ?: true
        val videosEnabled = entries.find { it.id == ROW_FOLLOWED_VIDEOS }?.enabled ?: true
        context.prefs().edit {
            putString(C.TV_HOME_ROWS, arr.toString())
            putStringSet(C.TV_HOME_LANGUAGE_ROWS, activeLangs)
            putBoolean(C.TV_HOME_MULTISTREAM, multiEnabled)
            putBoolean(C.TV_HOME_FOLLOWED_VIDEOS, videosEnabled)
        }
        if (notify) {
            changes.value = System.currentTimeMillis()
        }
    }

    fun syncFromLanguageRowsPref(context: Context, selectedCodes: Set<String>) {
        val cleanCodes = selectedCodes.filter { it.isNotBlank() }.toSet()
        val current = load(context).toMutableList()
        // Disable or remove unchecked language rows, enable existing checked ones, and insert new ones after Popular
        val updated = mutableListOf<Entry>()
        val presentCodes = HashSet<String>()
        for (entry in current) {
            val code = parseLanguage(entry.id)
            if (code != null) {
                if (code in cleanCodes) {
                    presentCodes.add(code)
                    updated.add(Entry(entry.id, true))
                }
                // unchecked language rows are removed cleanly
            } else {
                updated.add(entry)
            }
        }
        val toInsert = cleanCodes.filter { it !in presentCodes }.sorted()
        if (toInsert.isNotEmpty()) {
            val popularIdx = updated.indexOfFirst { it.id == ROW_POPULAR }
            val insertAt = if (popularIdx >= 0) popularIdx + 1 else updated.size
            updated.addAll(insertAt, toInsert.map { Entry("lang:$it", true) })
        }
        save(context, updated)
    }

    fun isCategoryPinned(context: Context, gameId: String?): Boolean {
        if (gameId.isNullOrBlank()) return false
        return load(context).any { it.enabled && parseCategory(it.id)?.gameId == gameId }
    }

    /**
     * Pins [game] as a live stream row on TV Home (right after Popular) or removes it if already pinned.
     * Returns true when now pinned, false when removed.
     */
    fun togglePinCategory(context: Context, game: Game, langMode: String = LANG_MODE_PREF): Boolean {
        val gId = game.id?.takeIf { it.isNotBlank() } ?: return false
        val current = load(context).toMutableList()
        val existingIdx = current.indexOfFirst { parseCategory(it.id)?.gameId == gId }
        if (existingIdx >= 0 && current[existingIdx].enabled) {
            current.removeAt(existingIdx)
            save(context, current)
            return false
        }
        val newId = buildCategoryId(game, langMode) ?: return false
        if (existingIdx >= 0) {
            current[existingIdx] = Entry(newId, true)
        } else {
            val popularIdx = current.indexOfFirst { it.id == ROW_POPULAR }
            val insertAt = if (popularIdx >= 0) popularIdx + 1 else current.size.coerceAtMost(2)
            current.add(insertAt, Entry(newId, true))
        }
        save(context, current)
        return true
    }

    fun languageDisplayName(context: Context, code: String): String {
        val appLocale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val name = Locale.forLanguageTag(code).getDisplayLanguage(appLocale)
        return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase(appLocale) }
            ?: code.uppercase()
    }

    fun rowIcon(id: String): Int = when {
        id == ROW_FOLLOWED -> R.drawable.tv_ic_heart
        id == ROW_FAVORITES -> R.drawable.tv_ic_star
        id == ROW_MULTISTREAMS -> R.drawable.tv_ic_multistream
        id == ROW_POPULAR -> R.drawable.tv_ic_trending
        id == ROW_RECOMMENDED -> R.drawable.tv_ic_streamers
        id == ROW_RESUME -> R.drawable.tv_ic_history
        id == ROW_FOLLOWED_VIDEOS -> R.drawable.tv_ic_video_library
        id == ROW_FOLLOWED_CLIPS -> R.drawable.tv_ic_play
        id == ROW_FOLLOWED_GAMES -> R.drawable.tv_ic_heart
        id == ROW_STATS -> R.drawable.tv_ic_bar_chart
        id == ROW_GAMES -> R.drawable.tv_ic_gamepad
        id.startsWith("lang:") -> R.drawable.tv_ic_globe
        id.startsWith("cat:") -> R.drawable.tv_ic_gamepad
        else -> R.drawable.tv_ic_list
    }

    fun rowTitle(context: Context, id: String, includeLangModeHint: Boolean = false): String {
        parseCategory(id)?.let { spec ->
            return if (includeLangModeHint && spec.langMode == LANG_MODE_ALL) {
                "${spec.name} (${context.getString(R.string.tv_home_cat_lang_all)})"
            } else {
                spec.name
            }
        }
        parseLanguage(id)?.let { code ->
            return context.getString(R.string.tv_popular_in, languageDisplayName(context, code))
        }
        return when (id) {
            ROW_FOLLOWED -> context.getString(R.string.tv_followed_live)
            ROW_FAVORITES -> context.getString(R.string.tv_home_row_favorites)
            ROW_MULTISTREAMS -> context.getString(R.string.tv_home_multistreams_row)
            ROW_POPULAR -> context.getString(R.string.popular)
            ROW_RECOMMENDED -> context.getString(R.string.tv_home_row_recommended)
            ROW_RESUME -> context.getString(R.string.tv_continue_watching)
            ROW_FOLLOWED_VIDEOS -> context.getString(R.string.tv_followed_videos_row)
            ROW_FOLLOWED_CLIPS -> context.getString(R.string.tv_home_row_clips)
            ROW_FOLLOWED_GAMES -> context.getString(R.string.tv_home_row_followed_games)
            ROW_STATS -> context.getString(R.string.tv_stats)
            ROW_GAMES -> context.getString(R.string.games)
            else -> id
        }
    }

    fun summary(context: Context): String {
        val activeCount = load(context).count { it.enabled }
        return context.getString(R.string.tv_home_rows_active_count, activeCount)
    }

    fun showManagerPanel(context: Context, onChanged: () -> Unit = {}) {
        TvDialogs.show(context) {
            var entries by remember { mutableStateOf(load(context)) }
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }
            var pendingFocusId by remember { mutableStateOf<String?>(entries.firstOrNull()?.id) }

            fun applyEntries(next: List<Entry>, focusId: String? = null, targetScrollIndex: Int = -1) {
                entries = next
                save(context, next)
                onChanged()
                if (focusId != null) {
                    pendingFocusId = focusId
                }
                if (targetScrollIndex >= 0) {
                    scope.launch {
                        // +2 accounts for the 2 top "Add" buttons in the LazyColumn
                        listState.animateScrollToItem((targetScrollIndex + 2 - 2).coerceAtLeast(0))
                    }
                }
            }

            fun moveEntry(fromIndex: Int, toIndex: Int) {
                if (fromIndex !in entries.indices || toIndex !in entries.indices || fromIndex == toIndex) return
                val moved = entries[fromIndex]
                val next = entries.toMutableList().apply {
                    removeAt(fromIndex)
                    add(toIndex, moved)
                }
                applyEntries(next, focusId = moved.id, targetScrollIndex = toIndex)
            }

            SidePanel(
                title = context.getString(R.string.tv_home_rows),
                subtitle = context.getString(R.string.tv_home_rows_hint),
                width = 540.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                    ) {
                        item(key = "action_add_category") {
                            FocusRow(
                                onClick = {
                                    showAddCategoryDialog(context) { updated ->
                                        entries = updated
                                        onChanged()
                                    }
                                }
                            ) { focused ->
                                val color = if (focused) TvColors.onFocused else ComposeColor.White
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painterResource(R.drawable.tv_ic_add),
                                        contentDescription = null,
                                        tint = if (focused) TvColors.onFocused else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        context.getString(R.string.tv_home_add_category),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = color,
                                    )
                                }
                            }
                        }
                        item(key = "action_add_language") {
                            FocusRow(
                                onClick = {
                                    showAddLanguageDialog(context) { updated ->
                                        entries = updated
                                        onChanged()
                                    }
                                }
                            ) { focused ->
                                val color = if (focused) TvColors.onFocused else ComposeColor.White
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painterResource(R.drawable.tv_ic_globe),
                                        contentDescription = null,
                                        tint = if (focused) TvColors.onFocused else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        context.getString(R.string.tv_home_add_language),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = color,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        itemsIndexed(entries, key = { _, item -> item.id }) { index, item ->
                            val requester = rowRequesters.getOrPut(item.id) { FocusRequester() }
                            FocusRow(
                                modifier = Modifier
                                    .focusRequester(requester)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.DirectionLeft -> {
                                                if (index > 0) moveEntry(index, index - 1)
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                if (index < entries.lastIndex) moveEntry(index, index + 1)
                                                true
                                            }
                                            else -> false
                                        }
                                    },
                                onClick = {
                                    showRowOptionsDialog(
                                        context = context,
                                        entries = entries,
                                        index = index,
                                        onUpdate = { next, focusId, scrollIdx ->
                                            applyEntries(next, focusId, scrollIdx)
                                        },
                                    )
                                },
                            ) { focused ->
                                val mainColor = when {
                                    focused -> TvColors.onFocused
                                    item.enabled -> ComposeColor.White
                                    else -> ComposeColor.White.copy(alpha = 0.45f)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Checkbox indicator
                                    Box(
                                        Modifier
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(
                                                when {
                                                    item.enabled -> MaterialTheme.colorScheme.primary
                                                    focused -> ComposeColor(0x22000000)
                                                    else -> ComposeColor.Transparent
                                                }
                                            )
                                            .border(
                                                width = 1.5.dp,
                                                color = if (item.enabled) MaterialTheme.colorScheme.primary else mainColor,
                                                shape = RoundedCornerShape(5.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (item.enabled) {
                                            Icon(
                                                painterResource(R.drawable.tv_ic_check),
                                                contentDescription = null,
                                                tint = ComposeColor.White,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Icon(
                                        painterResource(rowIcon(item.id)),
                                        contentDescription = null,
                                        tint = mainColor,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        rowTitle(context, item.id, includeLangModeHint = true),
                                        fontSize = 16.sp,
                                        fontWeight = if (item.enabled) FontWeight.Medium else FontWeight.Normal,
                                        color = mainColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (focused) "↑↓ #${index + 1}" else "#${index + 1}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (focused) TvColors.onFocused else TvColors.secondaryText,
                                    )
                                }
                            }
                        }
                        item(key = "action_reset") {
                            Spacer(Modifier.height(10.dp))
                            ButtonRow(label = context.getString(R.string.tv_reset)) {
                                val def = defaultEntries(context, includeLegacyPrefs = false)
                                applyEntries(def, focusId = def.firstOrNull()?.id, targetScrollIndex = 0)
                            }
                        }
                    }
                }
            }

            LaunchedEffect(pendingFocusId, entries) {
                val target = pendingFocusId ?: return@LaunchedEffect
                repeat(8) {
                    androidx.compose.runtime.withFrameNanos { }
                    if (runCatching { rowRequesters[target]?.requestFocus() }.getOrNull() == true) {
                        pendingFocusId = null
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    private fun showRowOptionsDialog(
        context: Context,
        entries: List<Entry>,
        index: Int,
        onUpdate: (List<Entry>, String?, Int) -> Unit,
    ) {
        val item = entries.getOrNull(index) ?: return
        data class RowOption(val label: String, val icon: Int, val run: () -> Unit)
        val options = buildList {
            add(
                RowOption(
                    label = context.getString(if (item.enabled) R.string.tv_home_row_hide else R.string.tv_home_row_show),
                    icon = if (item.enabled) R.drawable.tv_ic_block else R.drawable.tv_ic_check,
                ) {
                    val next = entries.toMutableList().also { it[index] = item.copy(enabled = !item.enabled) }
                    onUpdate(next, item.id, -1)
                }
            )
            if (index > 0) {
                add(
                    RowOption(
                        label = context.getString(R.string.tv_home_row_move_up),
                        icon = R.drawable.tv_ic_swap,
                    ) {
                        val next = entries.toMutableList().apply {
                            removeAt(index)
                            add(index - 1, item)
                        }
                        onUpdate(next, item.id, index - 1)
                    }
                )
            }
            if (index < entries.lastIndex) {
                add(
                    RowOption(
                        label = context.getString(R.string.tv_home_row_move_down),
                        icon = R.drawable.tv_ic_swap,
                    ) {
                        val next = entries.toMutableList().apply {
                            removeAt(index)
                            add(index + 1, item)
                        }
                        onUpdate(next, item.id, index + 1)
                    }
                )
            }
            if (index > 0) {
                add(
                    RowOption(
                        label = context.getString(R.string.tv_home_row_move_top),
                        icon = R.drawable.tv_ic_pin,
                    ) {
                        val next = entries.toMutableList().apply {
                            removeAt(index)
                            add(0, item)
                        }
                        onUpdate(next, item.id, 0)
                    }
                )
            }
            if (index < entries.lastIndex) {
                add(
                    RowOption(
                        label = context.getString(R.string.tv_home_row_move_bottom),
                        icon = R.drawable.tv_ic_list,
                    ) {
                        val next = entries.toMutableList().apply {
                            removeAt(index)
                            add(item)
                        }
                        onUpdate(next, item.id, entries.lastIndex)
                    }
                )
            }
            if (isRemovable(item.id)) {
                add(
                    RowOption(
                        label = context.getString(R.string.delete),
                        icon = R.drawable.tv_ic_delete,
                    ) {
                        val next = entries.toMutableList().also { it.removeAt(index) }
                        val neighborId = next.getOrNull(index.coerceAtMost(next.lastIndex.coerceAtLeast(0)))?.id
                        onUpdate(next, neighborId, -1)
                    }
                )
            }
        }
        TvDialogs.showChoices(
            context = context,
            title = rowTitle(context, item.id, includeLangModeHint = true),
            options = options.map { it.label },
            selected = 0,
            icons = options.map { it.icon },
        ) { selectedIdx ->
            options.getOrNull(selectedIdx)?.run?.invoke()
        }
    }

    private fun showAddLanguageDialog(context: Context, onUpdated: (List<Entry>) -> Unit) {
        val labels = context.resources.getStringArray(R.array.gqlUserLanguageEntries).map { it.toString() }
        val codes = context.resources.getStringArray(R.array.gqlUserLanguageValues).map { it.toString() }
        if (labels.isEmpty() || labels.size != codes.size) return
        TvDialogs.showChoices(
            context = context,
            title = context.getString(R.string.tv_home_add_language),
            options = labels,
            selected = 0,
        ) { idx ->
            val code = codes.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: return@showChoices
            val rowId = "lang:$code"
            val current = load(context).toMutableList()
            val existingIdx = current.indexOfFirst { it.id == rowId }
            if (existingIdx >= 0) {
                current[existingIdx] = Entry(rowId, true)
            } else {
                val popularIdx = current.indexOfFirst { it.id == ROW_POPULAR }
                val insertAt = if (popularIdx >= 0) popularIdx + 1 else current.size
                current.add(insertAt, Entry(rowId, true))
            }
            save(context, current)
            onUpdated(current)
        }
    }

    private fun showAddCategoryDialog(context: Context, onUpdated: (List<Entry>) -> Unit) {
        val app = context.applicationContext as XtraApp
        val xtraModule = app.xtraModule
        val prefs = context.prefs()
        val truncate = prefs.getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)
        val langOptions = listOf(
            context.getString(R.string.tv_home_cat_lang_pref),
            context.getString(R.string.tv_home_cat_lang_all),
        )
        TvDialogs.show(context) { dismiss ->
            val searchFocus = remember { FocusRequester() }
            val firstResultFocus = remember { FocusRequester() }
            var query by remember { mutableStateOf("") }
            var langIdx by remember { mutableIntStateOf(0) }
            var results by remember { mutableStateOf<List<Game>>(emptyList()) }
            var loading by remember { mutableStateOf(true) }
            val currentEntries = remember { load(context) }

            LaunchedEffect(query) {
                loading = true
                if (query.isNotBlank()) {
                    delay(300L)
                }
                val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
                val enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
                val gqlHeaders = TwitchApiHelper.getGQLHeaders(app)
                val helixHeaders = TwitchApiHelper.getHelixHeaders(app)
                val source: PagingSource<Int, Game> = if (query.isBlank()) {
                    GamesDataSource(
                        tags = null,
                        gqlHeaders = gqlHeaders,
                        graphQLRepository = xtraModule.graphQLRepository,
                        helixHeaders = helixHeaders,
                        helixRepository = xtraModule.helixRepository,
                        enableIntegrity = enableIntegrity,
                        networkLibrary = networkLibrary,
                    )
                } else {
                    SearchGamesDataSource(
                        query = query.trim(),
                        gqlHeaders = gqlHeaders,
                        graphQLRepository = xtraModule.graphQLRepository,
                        helixHeaders = helixHeaders,
                        helixRepository = xtraModule.helixRepository,
                        enableIntegrity = enableIntegrity,
                        networkLibrary = networkLibrary,
                    )
                }
                val fetched = runCatching {
                    (source.load(PagingSource.LoadParams.Refresh(null, 30, false)) as? PagingSource.LoadResult.Page)?.data
                }.getOrNull().orEmpty()
                results = fetched
                loading = false
            }

            SidePanel(
                title = context.getString(R.string.tv_home_add_category),
                width = 540.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    TvSearchBar(
                        initialQuery = query,
                        hint = context.getString(R.string.tv_home_search_category_hint),
                        focusRequester = searchFocus,
                        onQueryChange = { query = it },
                        onSubmit = { query = it },
                    )
                    SelectorRow(
                        label = context.getString(R.string.tv_home_cat_lang_label),
                        options = langOptions,
                        selected = langIdx,
                    ) { idx ->
                        langIdx = idx
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(results, key = { index, g -> g.id ?: "cat_res_$index" }) { index, game ->
                            val alreadyPinned = currentEntries.any {
                                it.enabled && parseCategory(it.id)?.gameId == game.id
                            }
                            FocusRow(
                                modifier = if (index == 0) Modifier.focusRequester(firstResultFocus) else Modifier,
                                onClick = {
                                    val mode = if (langIdx == 1) LANG_MODE_ALL else LANG_MODE_PREF
                                    val catId = buildCategoryId(game, mode) ?: return@FocusRow
                                    val next = load(context).toMutableList()
                                    val existingIdx = next.indexOfFirst { parseCategory(it.id)?.gameId == game.id }
                                    if (existingIdx >= 0) {
                                        next[existingIdx] = Entry(catId, true)
                                    } else {
                                        val popularIdx = next.indexOfFirst { it.id == ROW_POPULAR }
                                        val insertAt = if (popularIdx >= 0) popularIdx + 1 else next.size
                                        next.add(insertAt, Entry(catId, true))
                                    }
                                    save(context, next)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.tv_home_category_pinned, game.name ?: ""),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    onUpdated(next)
                                    dismiss()
                                },
                            ) { focused ->
                                val textColor = if (focused) TvColors.onFocused else ComposeColor.White
                                val subColor = if (focused) TvColors.onFocused.copy(alpha = 0.75f) else TvColors.secondaryText
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AsyncImage(
                                        model = game.boxArt,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .width(36.dp)
                                            .height(48.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(ComposeColor(0xFF26252C)),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            game.name ?: "",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = textColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        game.viewerCount?.takeIf { it > 0 }?.let { viewers ->
                                            Text(
                                                context.resources.getQuantityString(
                                                    R.plurals.viewers,
                                                    viewers,
                                                    TwitchApiHelper.formatCount(viewers, truncate),
                                                ),
                                                fontSize = 13.sp,
                                                color = subColor,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                    if (alreadyPinned) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            painterResource(R.drawable.tv_ic_check),
                                            contentDescription = null,
                                            tint = if (focused) TvColors.onFocused else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (!loading && results.isEmpty()) {
                            item(key = "empty_search") {
                                Text(
                                    context.getString(R.string.tv_home_no_category_found),
                                    fontSize = 15.sp,
                                    color = TvColors.secondaryText,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                searchFocus.requestFocus()
            }
        }
    }
}
