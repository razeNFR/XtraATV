package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * TV content filter ("Hidden content"): hides unwanted channels, categories, and title/tag keywords
 * across feeds (Home, Popular, Categories, Recommended, Followed Live, and Player "Other streams").
 *
 * Stored per-account (`"tv_blacklist" + TvAccounts.storageSuffix(context)`) and exported/restored by [TvBackup].
 */
object TvBlacklist {

    const val FILE = "tv_blacklist"
    private const val KEY_CHANNELS = "channels"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_KEYWORDS = "keywords"

    data class BlockedChannel(
        val id: String?,
        val login: String?,
        val name: String?,
        val avatarUrl: String? = null,
    ) {
        val displayLabel: String
            get() = name?.takeIf { it.isNotBlank() } ?: login?.takeIf { it.isNotBlank() } ?: id.orEmpty()
    }

    data class BlockedCategory(
        val id: String?,
        val slug: String?,
        val name: String?,
        val boxArtUrl: String? = null,
    ) {
        val displayLabel: String
            get() = name?.takeIf { it.isNotBlank() } ?: slug?.takeIf { it.isNotBlank() } ?: id.orEmpty()
    }

    private class Snapshot(
        val channels: List<BlockedChannel>,
        val categories: List<BlockedCategory>,
        val keywords: List<String>,
    ) {
        val channelIds: Set<String> = channels.mapNotNull { it.id?.takeIf { s -> s.isNotBlank() } }.toHashSet()
        val channelLoginsLower: Set<String> = channels.mapNotNull { it.login?.trim()?.lowercase(Locale.ROOT)?.takeIf { s -> s.isNotEmpty() } }.toHashSet()
        val categoryIds: Set<String> = categories.mapNotNull { it.id?.takeIf { s -> s.isNotBlank() } }.toHashSet()
        val categorySlugsLower: Set<String> = categories.mapNotNull { it.slug?.trim()?.lowercase(Locale.ROOT)?.takeIf { s -> s.isNotEmpty() } }.toHashSet()
        val categoryNamesLower: Set<String> = categories.mapNotNull { it.name?.trim()?.lowercase(Locale.ROOT)?.takeIf { s -> s.isNotEmpty() } }.toHashSet()
        val keywordsLower: List<String> = keywords.mapNotNull { it.trim().removePrefix("#").trim().lowercase(Locale.ROOT).takeIf { s -> s.isNotEmpty() } }
        val isEmpty: Boolean = channels.isEmpty() && categories.isEmpty() && keywordsLower.isEmpty()

        companion object {
            val EMPTY = Snapshot(emptyList(), emptyList(), emptyList())
        }
    }

    @Volatile
    private var snapshot: Snapshot = Snapshot.EMPTY
    private var loadedFile: String? = null

    val channels = MutableStateFlow<List<BlockedChannel>>(emptyList())
    val categories = MutableStateFlow<List<BlockedCategory>>(emptyList())
    val keywords = MutableStateFlow<List<String>>(emptyList())

    /** Increments whenever any hidden channel, category, or keyword changes (or on account switch / backup restore). */
    val changes = MutableStateFlow(0L)

    private fun fileName(context: Context): String = FILE + TvAccounts.storageSuffix(context)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(fileName(context), Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context) {
        val currentFile = fileName(context)
        if (loadedFile == currentFile) return
        loadedFile = currentFile
        val sp = prefs(context)

        val parsedChannels = runCatching {
            val arr = JSONArray(sp.getString(KEY_CHANNELS, null) ?: "[]")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() }
                    val login = o.optString("login").takeIf { it.isNotBlank() }
                    val name = o.optString("name").takeIf { it.isNotBlank() }
                    val avatar = o.optString("avatar").takeIf { it.isNotBlank() }
                    if (id != null || login != null) {
                        add(BlockedChannel(id = id, login = login, name = name, avatarUrl = avatar))
                    }
                }
            }
        }.getOrDefault(emptyList())

        val parsedCategories = runCatching {
            val arr = JSONArray(sp.getString(KEY_CATEGORIES, null) ?: "[]")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() }
                    val slug = o.optString("slug").takeIf { it.isNotBlank() }
                    val name = o.optString("name").takeIf { it.isNotBlank() }
                    val boxArt = o.optString("boxArt").takeIf { it.isNotBlank() }
                    if (id != null || slug != null || name != null) {
                        add(BlockedCategory(id = id, slug = slug, name = name, boxArtUrl = boxArt))
                    }
                }
            }
        }.getOrDefault(emptyList())

        val parsedKeywords = runCatching {
            val arr = JSONArray(sp.getString(KEY_KEYWORDS, null) ?: "[]")
            buildList<String> {
                for (i in 0 until arr.length()) {
                    val kw = arr.optString(i).trim().removePrefix("#").trim()
                    if (kw.isNotEmpty() && this.none { s: String -> s.equals(kw, ignoreCase = true) }) {
                        add(kw)
                    }
                }
            }
        }.getOrDefault(emptyList())

        applySnapshot(parsedChannels, parsedCategories, parsedKeywords, bumpChange = false)
    }

    @Synchronized
    fun reload(context: Context) {
        loadedFile = null
        load(context)
        changes.value = changes.value + 1L
    }

    private fun applySnapshot(
        newChannels: List<BlockedChannel>,
        newCategories: List<BlockedCategory>,
        newKeywords: List<String>,
        bumpChange: Boolean,
    ) {
        snapshot = Snapshot(newChannels, newCategories, newKeywords)
        channels.value = newChannels
        categories.value = newCategories
        keywords.value = newKeywords
        if (bumpChange) {
            changes.value = changes.value + 1L
        }
    }

    @Synchronized
    private fun saveAll(
        context: Context,
        newChannels: List<BlockedChannel>,
        newCategories: List<BlockedCategory>,
        newKeywords: List<String>,
    ) {
        applySnapshot(newChannels, newCategories, newKeywords, bumpChange = true)
        val chArr = JSONArray().apply {
            newChannels.forEach { ch ->
                put(
                    JSONObject()
                        .put("id", ch.id.orEmpty())
                        .put("login", ch.login.orEmpty())
                        .put("name", ch.name.orEmpty())
                        .put("avatar", ch.avatarUrl.orEmpty())
                )
            }
        }
        val catArr = JSONArray().apply {
            newCategories.forEach { cat ->
                put(
                    JSONObject()
                        .put("id", cat.id.orEmpty())
                        .put("slug", cat.slug.orEmpty())
                        .put("name", cat.name.orEmpty())
                        .put("boxArt", cat.boxArtUrl.orEmpty())
                )
            }
        }
        val kwArr = JSONArray().apply {
            newKeywords.forEach { put(it) }
        }
        prefs(context).edit {
            putString(KEY_CHANNELS, chArr.toString())
            putString(KEY_CATEGORIES, catArr.toString())
            putString(KEY_KEYWORDS, kwArr.toString())
        }
    }

    fun totalCount(context: Context): Int {
        load(context)
        val snap = snapshot
        return snap.channels.size + snap.categories.size + snap.keywords.size
    }

    fun isChannelBlocked(channelId: String?, channelLogin: String?): Boolean {
        val snap = snapshot
        if (snap.channels.isEmpty()) return false
        if (!channelId.isNullOrBlank() && channelId in snap.channelIds) return true
        if (!channelLogin.isNullOrBlank() && channelLogin.trim().lowercase(Locale.ROOT) in snap.channelLoginsLower) return true
        return false
    }

    fun isCategoryBlocked(gameId: String?, gameSlug: String?, gameName: String?): Boolean {
        val snap = snapshot
        if (snap.categories.isEmpty()) return false
        if (!gameId.isNullOrBlank() && gameId in snap.categoryIds) return true
        if (!gameSlug.isNullOrBlank() && gameSlug.trim().lowercase(Locale.ROOT) in snap.categorySlugsLower) return true
        if (!gameName.isNullOrBlank() && gameName.trim().lowercase(Locale.ROOT) in snap.categoryNamesLower) return true
        return false
    }

    fun isStreamBlocked(stream: Stream, ignoreCategory: Boolean = false): Boolean {
        val snap = snapshot
        if (snap.isEmpty) return false
        if (!stream.channelId.isNullOrBlank() && stream.channelId in snap.channelIds) return true
        if (!stream.channelLogin.isNullOrBlank() && stream.channelLogin.trim().lowercase(Locale.ROOT) in snap.channelLoginsLower) return true
        if (!ignoreCategory) {
            if (!stream.gameId.isNullOrBlank() && stream.gameId in snap.categoryIds) return true
            if (!stream.gameSlug.isNullOrBlank() && stream.gameSlug!!.trim().lowercase(Locale.ROOT) in snap.categorySlugsLower) return true
            if (!stream.gameName.isNullOrBlank() && stream.gameName!!.trim().lowercase(Locale.ROOT) in snap.categoryNamesLower) return true
        }
        if (snap.keywordsLower.isNotEmpty()) {
            val titleLower = stream.title?.lowercase(Locale.ROOT)
            if (!titleLower.isNullOrEmpty() && snap.keywordsLower.any { kw -> titleLower.contains(kw) }) {
                return true
            }
            val tags = stream.tags
            if (!tags.isNullOrEmpty()) {
                for (tag in tags) {
                    val tagLower = tag.trim().removePrefix("#").lowercase(Locale.ROOT)
                    if (tagLower.isNotEmpty() && snap.keywordsLower.any { kw -> tagLower.contains(kw) }) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun filterStreams(list: List<Stream>, ignoreCategory: Boolean = false): List<Stream> {
        if (snapshot.isEmpty || list.isEmpty()) return list
        return list.filterNot { isStreamBlocked(it, ignoreCategory) }
    }

    fun filterGames(list: List<Game>): List<Game> {
        if (snapshot.categories.isEmpty() || list.isEmpty()) return list
        return list.filterNot { isCategoryBlocked(it.id, it.slug, it.name) }
    }

    @Synchronized
    fun blockChannel(context: Context, id: String?, login: String?, name: String?, avatarUrl: String? = null) {
        load(context)
        if (id.isNullOrBlank() && login.isNullOrBlank()) return
        if (isChannelBlocked(id, login)) return
        val snap = snapshot
        val updated = listOf(BlockedChannel(id = id, login = login, name = name, avatarUrl = avatarUrl)) + snap.channels
        saveAll(context, updated, snap.categories, snap.keywords)
    }

    @Synchronized
    fun unblockChannel(context: Context, id: String?, login: String?) {
        load(context)
        val snap = snapshot
        val loginLower = login?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val updated = snap.channels.filterNot { ch ->
            (!id.isNullOrBlank() && ch.id == id) ||
                (loginLower != null && ch.login?.trim()?.lowercase(Locale.ROOT) == loginLower)
        }
        if (updated.size != snap.channels.size) {
            saveAll(context, updated, snap.categories, snap.keywords)
        }
    }

    @Synchronized
    fun blockCategory(context: Context, id: String?, slug: String?, name: String?, boxArtUrl: String? = null) {
        load(context)
        if (id.isNullOrBlank() && slug.isNullOrBlank() && name.isNullOrBlank()) return
        if (isCategoryBlocked(id, slug, name)) return
        val snap = snapshot
        val updated = listOf(BlockedCategory(id = id, slug = slug, name = name, boxArtUrl = boxArtUrl)) + snap.categories
        saveAll(context, snap.channels, updated, snap.keywords)
    }

    @Synchronized
    fun unblockCategory(context: Context, id: String?, slug: String?, name: String?) {
        load(context)
        val snap = snapshot
        val slugLower = slug?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val nameLower = name?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val updated = snap.categories.filterNot { cat ->
            (!id.isNullOrBlank() && cat.id == id) ||
                (slugLower != null && cat.slug?.trim()?.lowercase(Locale.ROOT) == slugLower) ||
                (nameLower != null && cat.name?.trim()?.lowercase(Locale.ROOT) == nameLower)
        }
        if (updated.size != snap.categories.size) {
            saveAll(context, snap.channels, updated, snap.keywords)
        }
    }

    @Synchronized
    fun addKeyword(context: Context, rawInput: String): Int {
        load(context)
        val tokens = rawInput.split(',', '\n')
            .map { it.trim().removePrefix("#").trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return 0
        val snap = snapshot
        val updated = snap.keywords.toMutableList()
        var added = 0
        for (token in tokens.asReversed()) {
            if (updated.none { it.equals(token, ignoreCase = true) }) {
                updated.add(0, token)
                added++
            }
        }
        if (added > 0) {
            saveAll(context, snap.channels, snap.categories, updated)
        }
        return added
    }

    @Synchronized
    fun removeKeyword(context: Context, keyword: String) {
        load(context)
        val snap = snapshot
        val updated = snap.keywords.filterNot { it.equals(keyword, ignoreCase = true) }
        if (updated.size != snap.keywords.size) {
            saveAll(context, snap.channels, snap.categories, updated)
        }
    }

    @Synchronized
    fun clearTab(context: Context, tab: Int) {
        load(context)
        val snap = snapshot
        when (tab) {
            0 -> if (snap.channels.isNotEmpty()) saveAll(context, emptyList(), snap.categories, snap.keywords)
            1 -> if (snap.categories.isNotEmpty()) saveAll(context, snap.channels, emptyList(), snap.keywords)
            2 -> if (snap.keywords.isNotEmpty()) saveAll(context, snap.channels, snap.categories, emptyList())
        }
    }

    fun summary(context: Context): String {
        load(context)
        val snap = snapshot
        val total = snap.channels.size + snap.categories.size + snap.keywords.size
        return if (total == 0) {
            context.getString(R.string.tv_blacklist_summary_empty)
        } else {
            context.getString(R.string.tv_blacklist_summary_count, total)
        }
    }

    /** Opens the 3-tab "Hidden content" side panel (Channels, Categories, Keywords / Tags). */
    fun showManagerPanel(context: Context, initialTab: Int = 0, onChanged: (() -> Unit)? = null) {
        load(context)
        val dialog = TvDialogs.show(context) { dismiss ->
            ManagerPanelContent(initialTab = initialTab, onDismiss = dismiss)
        }
        if (onChanged != null) {
            dialog.setOnDismissListener { onChanged() }
        }
    }

    @Composable
    private fun ManagerPanelContent(initialTab: Int, onDismiss: () -> Unit) {
        val context = LocalContext.current
        val channelList by channels.collectAsState()
        val categoryList by categories.collectAsState()
        val keywordList by keywords.collectAsState()

        var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
        var keywordDraft by remember { mutableStateOf("") }
        val tabRequesters = remember { List(3) { FocusRequester() } }
        val keywordInputRequester = remember { FocusRequester() }
        val voiceState = remember { VoiceState() }
        val voiceInput = rememberVoiceInput(voiceState) { spoken ->
            val added = addKeyword(context, spoken)
            if (added > 0) {
                TvToast.makeText(context, context.getString(R.string.tv_blacklist_keyword_added, spoken.trim()), Toast.LENGTH_SHORT).show()
            }
        }

        val tabTitles = listOf(
            "${context.getString(R.string.channels)} (${channelList.size})",
            "${context.getString(R.string.games)} (${categoryList.size})",
            "${context.getString(R.string.tv_blacklist_tab_keywords)} (${keywordList.size})",
        )

        LaunchedEffect(Unit) {
            runCatching { tabRequesters[selectedTab].requestFocus() }
        }

        SidePanel(
            title = context.getString(R.string.tv_blacklist_title),
            subtitle = context.getString(R.string.tv_blacklist_subtitle),
            width = 560.dp,
            footer = {
                val activeCount = when (selectedTab) {
                    0 -> channelList.size
                    1 -> categoryList.size
                    else -> keywordList.size
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (activeCount >= 2) {
                        FocusRow(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                TvDialogs.showChoices(
                                    context = context,
                                    title = context.getString(R.string.tv_blacklist_clear_confirm),
                                    options = listOf(
                                        context.getString(R.string.yes),
                                        context.getString(R.string.no),
                                    ),
                                    selected = 1,
                                ) { choice ->
                                    if (choice == 0) {
                                        clearTab(context, selectedTab)
                                    }
                                }
                            },
                        ) { focused ->
                            Text(
                                text = context.getString(R.string.tv_blacklist_clear_tab),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (focused) TvColors.onFocused else Color(0xFFFF8A80),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                    ButtonRow(
                        label = context.getString(R.string.close_player),
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss,
                    )
                }
            },
        ) {
            // 3 pill tabs (Channels / Categories / Keywords & Tags)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabTitles.forEachIndexed { idx, label ->
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    val selected = idx == selectedTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(tabRequesters[idx])
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (idx > 0) {
                                            selectedTab = idx - 1
                                            runCatching { tabRequesters[idx - 1].requestFocus() }
                                        }
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        if (idx < 2) {
                                            selectedTab = idx + 1
                                            runCatching { tabRequesters[idx + 1].requestFocus() }
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                when {
                                    focused -> TvColors.focused
                                    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                    else -> TvColors.item
                                }
                            )
                            .clickable(interactionSource = interaction, indication = null) {
                                selectedTab = idx
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (focused) TvColors.onFocused else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    if (channelList.isEmpty()) {
                        EmptyBlacklistState(
                            icon = R.drawable.tv_ic_person,
                            message = context.getString(R.string.tv_blacklist_empty_channels),
                            hint = context.getString(R.string.tv_blacklist_hint_cards),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(channelList, key = { _, ch -> ch.id ?: ch.login ?: ch.displayLabel }) { _, ch ->
                                FocusRow(
                                    onClick = {
                                        unblockChannel(context, ch.id, ch.login)
                                        TvToast.makeText(
                                            context,
                                            context.getString(R.string.tv_blacklist_channel_unhidden, ch.displayLabel),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                ) { focused ->
                                    val textColor = if (focused) TvColors.onFocused else Color.White
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        val avatar = ch.avatarUrl?.let { TwitchApiHelper.getProfileImage(it) ?: it }
                                        if (!avatar.isNullOrBlank()) {
                                            AsyncImage(
                                                model = avatar,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape),
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0x26FFFFFF)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.tv_ic_person),
                                                    contentDescription = null,
                                                    tint = textColor,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = ch.displayLabel,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = textColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val subLogin = ch.login?.takeIf { !it.equals(ch.displayLabel, ignoreCase = true) }
                                            if (subLogin != null) {
                                                Text(
                                                    text = "@$subLogin",
                                                    fontSize = 12.sp,
                                                    color = if (focused) TvColors.onFocused.copy(alpha = 0.75f) else TvColors.secondaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Icon(
                                            painter = painterResource(R.drawable.tv_ic_delete),
                                            contentDescription = context.getString(R.string.delete),
                                            tint = textColor,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    if (categoryList.isEmpty()) {
                        EmptyBlacklistState(
                            icon = R.drawable.tv_ic_gamepad,
                            message = context.getString(R.string.tv_blacklist_empty_categories),
                            hint = context.getString(R.string.tv_blacklist_hint_cards),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(categoryList, key = { _, cat -> cat.id ?: cat.slug ?: cat.displayLabel }) { _, cat ->
                                FocusRow(
                                    onClick = {
                                        unblockCategory(context, cat.id, cat.slug, cat.name)
                                        TvToast.makeText(
                                            context,
                                            context.getString(R.string.tv_blacklist_category_unhidden, cat.displayLabel),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                ) { focused ->
                                    val textColor = if (focused) TvColors.onFocused else Color.White
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        val art = cat.boxArtUrl?.let { TwitchApiHelper.getGameBoxArt(it) ?: it }
                                        if (!art.isNullOrBlank()) {
                                            AsyncImage(
                                                model = art,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .width(30.dp)
                                                    .height(40.dp)
                                                    .clip(RoundedCornerShape(6.dp)),
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .width(30.dp)
                                                    .height(40.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0x26FFFFFF)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.tv_ic_gamepad),
                                                    contentDescription = null,
                                                    tint = textColor,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            text = cat.displayLabel,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = textColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Icon(
                                            painter = painterResource(R.drawable.tv_ic_delete),
                                            contentDescription = context.getString(R.string.delete),
                                            tint = textColor,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    val submitKeyword = {
                        val trimmed = keywordDraft.trim()
                        if (trimmed.isNotEmpty()) {
                            val added = addKeyword(context, trimmed)
                            keywordDraft = ""
                            if (added > 0) {
                                TvToast.makeText(
                                    context,
                                    context.getString(R.string.tv_blacklist_keyword_added, trimmed.removePrefix("#").trim()),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TvTextField(
                            value = keywordDraft,
                            onValueChange = { keywordDraft = it },
                            hint = context.getString(R.string.tv_blacklist_keyword_hint),
                            voice = voiceState,
                            imeAction = ImeAction.Done,
                            leadingIcon = R.drawable.tv_ic_block,
                            focusRequester = keywordInputRequester,
                            modifier = Modifier.weight(1f),
                            onSubmit = submitKeyword,
                        )
                        Spacer(Modifier.width(10.dp))
                        VoiceButton(voiceState, voiceInput)
                        Spacer(Modifier.width(10.dp))
                        TvIconButton(
                            icon = R.drawable.tv_ic_add,
                            contentDescription = context.getString(R.string.tv_blacklist_keyword_add),
                            onClick = submitKeyword,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    if (keywordList.isEmpty()) {
                        EmptyBlacklistState(
                            icon = R.drawable.tv_ic_block,
                            message = context.getString(R.string.tv_blacklist_empty_keywords),
                            hint = context.getString(R.string.tv_blacklist_hint_keywords),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(keywordList, key = { _, kw -> kw.lowercase(Locale.ROOT) }) { _, kw ->
                                FocusRow(
                                    onClick = {
                                        removeKeyword(context, kw)
                                        TvToast.makeText(
                                            context,
                                            context.getString(R.string.tv_blacklist_keyword_removed, kw),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                ) { focused ->
                                    val textColor = if (focused) TvColors.onFocused else Color.White
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.tv_ic_block),
                                            contentDescription = null,
                                            tint = textColor.copy(alpha = 0.85f),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            text = kw,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = textColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Icon(
                                            painter = painterResource(R.drawable.tv_ic_delete),
                                            contentDescription = context.getString(R.string.delete),
                                            tint = textColor,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyBlacklistState(icon: Int, message: String, hint: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0x18FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = TvColors.secondaryText,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = message,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    fontSize = 13.sp,
                    color = TvColors.secondaryText,
                )
            }
        }
    }
}
