package com.github.andreyasadchy.xtra.util.chat

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.ui.tv.ChatComposer
import com.github.andreyasadchy.xtra.ui.tv.FocusRow
import com.github.andreyasadchy.xtra.ui.tv.SelectorRow
import com.github.andreyasadchy.xtra.ui.tv.SidePanel
import com.github.andreyasadchy.xtra.ui.tv.TvColors
import com.github.andreyasadchy.xtra.ui.tv.TvDialogs
import com.github.andreyasadchy.xtra.ui.tv.tvTopFade
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local chat bot & account blocker: filters well-known Twitch bots (plus accounts carrying Twitch's bot badge)
 * when [C.CHAT_HIDE_BOTS] is enabled, and filters any account blocked locally by the user in [C.CHAT_BLOCKED_USERS].
 */
object ChatBlocklist {

    data class BlockedUser(
        val login: String,
        val name: String? = null,
    ) {
        val displayLabel: String
            get() = if (!name.isNullOrBlank() && !name.equals(login, ignoreCase = true)) {
                "$name ($login)"
            } else {
                name?.takeIf { it.isNotBlank() } ?: login
            }
    }

    private val KNOWN_BOTS = setOf(
        "nightbot",
        "streamelements",
        "streamelementsbot",
        "fossabot",
        "moobot",
        "wizebot",
        "streamlabs",
        "soundalerts",
        "sery_bot",
        "pokemoncommunitygame",
        "kofistreambot",
        "streamstickers",
        "own3d",
        "creatisbot",
        "botrixoficial",
        "frostytoolsdotcom",
        "pretzelrocks",
        "songlistbot",
        "stay_hydrated_bot",
        "commanderroot",
        "blerp",
        "tangia",
        "crowdcontrol",
        "dixperbro",
        "lumiastream",
        "phantombot",
        "deepbot",
        "ankhbot",
        "vivbot",
        "coebot",
        "xanbot",
        "streamavatars",
        "mixitupapp",
        "firebot",
        "streamerbot",
        "botisimo",
    )

    val version = MutableStateFlow(0)

    private var cachedRaw: String? = "__uninitialized__"
    private var cachedList: List<BlockedUser> = emptyList()
    private var cachedLogins: Set<String> = emptySet()

    private var cachedHighlightRaw: String? = "__uninitialized__"
    private var cachedHighlightList: List<BlockedUser> = emptyList()
    private var cachedHighlightLogins: Set<String> = emptySet()

    fun normalizeLogin(raw: String?): String? {
        val cleaned = raw?.trim()?.removePrefix("@")?.trim()?.lowercase()
        if (cleaned.isNullOrEmpty()) return null
        return cleaned.takeIf { it.all { ch -> ch.isLetterOrDigit() || ch == '_' } }
    }

    /** Parses one or multiple Twitch usernames separated by commas, semicolons, or whitespace. */
    fun parseLogins(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        raw.split(Regex("[,;\\s]+")).forEach { token ->
            normalizeLogin(token)?.let { seen.add(it) }
        }
        return seen.toList()
    }

    private fun parseUsersJson(raw: String?): List<BlockedUser> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                val seen = HashSet<String>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val login = normalizeLogin(obj.optString("login", "")) ?: continue
                    if (seen.add(login)) {
                        val name = obj.optString("name", "").trim().takeIf { it.isNotEmpty() }
                        add(BlockedUser(login = login, name = name))
                    }
                }
            }
        } catch (_: Exception) {
            parseLogins(raw).map { BlockedUser(login = it) }
        }
    }

    private fun encodeUsersJson(users: List<BlockedUser>): String? {
        if (users.isEmpty()) return null
        val array = JSONArray()
        users.forEach { user ->
            array.put(
                JSONObject().apply {
                    put("login", user.login)
                    if (!user.name.isNullOrBlank()) {
                        put("name", user.name)
                    }
                }
            )
        }
        return array.toString()
    }

    @Synchronized
    fun list(context: Context): List<BlockedUser> {
        syncFromPrefsLocked(context)
        return cachedList
    }

    @Synchronized
    fun blockedLogins(context: Context): Set<String> {
        syncFromPrefsLocked(context)
        return cachedLogins
    }

    fun isBlocked(context: Context, login: String?): Boolean {
        val norm = normalizeLogin(login) ?: return false
        return norm in blockedLogins(context)
    }

    @Synchronized
    fun highlightedList(context: Context): List<BlockedUser> {
        syncHighlightedFromPrefsLocked(context)
        return cachedHighlightList
    }

    @Synchronized
    fun highlightedLogins(context: Context): Set<String> {
        syncHighlightedFromPrefsLocked(context)
        return cachedHighlightLogins
    }

    fun isHighlighted(context: Context, login: String?): Boolean {
        val norm = normalizeLogin(login) ?: return false
        return norm in highlightedLogins(context)
    }

    @Synchronized
    private fun syncFromPrefsLocked(context: Context) {
        val raw = context.prefs().getString(C.CHAT_BLOCKED_USERS, null)
        if (raw == cachedRaw) return
        cachedRaw = raw
        val parsed = parseUsersJson(raw)
        cachedList = parsed
        cachedLogins = parsed.mapTo(HashSet()) { it.login }
    }

    @Synchronized
    private fun syncHighlightedFromPrefsLocked(context: Context) {
        val raw = context.prefs().getString(C.CHAT_HIGHLIGHTED_USERS, null)
        if (raw == cachedHighlightRaw) return
        cachedHighlightRaw = raw
        val parsed = parseUsersJson(raw)
        cachedHighlightList = parsed
        cachedHighlightLogins = parsed.mapTo(HashSet()) { it.login }
    }

    @Synchronized
    private fun writeLocked(context: Context, users: List<BlockedUser>, bumpVersion: Boolean = true) {
        val raw = encodeUsersJson(users)
        context.prefs().edit {
            if (raw == null) remove(C.CHAT_BLOCKED_USERS) else putString(C.CHAT_BLOCKED_USERS, raw)
        }
        cachedRaw = raw
        cachedList = users
        cachedLogins = users.mapTo(HashSet()) { it.login }
        if (bumpVersion) {
            version.value++
        }
    }

    @Synchronized
    private fun writeHighlightedLocked(context: Context, users: List<BlockedUser>, bumpVersion: Boolean = true) {
        val raw = encodeUsersJson(users)
        context.prefs().edit {
            if (raw == null) remove(C.CHAT_HIGHLIGHTED_USERS) else putString(C.CHAT_HIGHLIGHTED_USERS, raw)
        }
        cachedHighlightRaw = raw
        cachedHighlightList = users
        cachedHighlightLogins = users.mapTo(HashSet()) { it.login }
        if (bumpVersion) {
            version.value++
        }
    }

    @Synchronized
    fun block(context: Context, login: String, name: String? = null): Boolean {
        val norm = normalizeLogin(login) ?: return false
        val currentHighlighted = highlightedList(context)
        if (currentHighlighted.any { it.login == norm }) {
            writeHighlightedLocked(context, currentHighlighted.filterNot { it.login == norm }, bumpVersion = false)
        }
        val current = list(context)
        if (current.any { it.login == norm }) {
            version.value++
            return false
        }
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
        writeLocked(context, listOf(BlockedUser(norm, cleanName)) + current)
        return true
    }

    @Synchronized
    fun blockMany(context: Context, rawInput: String): List<BlockedUser> {
        val logins = parseLogins(rawInput)
        if (logins.isEmpty()) return emptyList()
        val loginSet = logins.toHashSet()
        val currentHighlighted = highlightedList(context)
        if (currentHighlighted.any { it.login in loginSet }) {
            writeHighlightedLocked(context, currentHighlighted.filterNot { it.login in loginSet }, bumpVersion = false)
        }
        val current = list(context)
        val existingByLogin = current.associateBy { it.login }
        val newEntries = logins.filter { it !in existingByLogin }.map { BlockedUser(it) }
        if (newEntries.isNotEmpty()) {
            writeLocked(context, newEntries + current)
        } else {
            version.value++
        }
        return logins.map { existingByLogin[it] ?: BlockedUser(it) }
    }

    @Synchronized
    fun unblock(context: Context, login: String): Boolean {
        val norm = normalizeLogin(login) ?: return false
        val current = list(context)
        val filtered = current.filterNot { it.login == norm }
        if (filtered.size == current.size) return false
        writeLocked(context, filtered)
        return true
    }

    /** Toggles block state and returns `true` if now blocked, `false` if now unblocked. */
    @Synchronized
    fun toggle(context: Context, login: String, name: String? = null): Boolean {
        val norm = normalizeLogin(login) ?: return false
        return if (isBlocked(context, norm)) {
            unblock(context, norm)
            false
        } else {
            block(context, norm, name)
            true
        }
    }

    @Synchronized
    fun highlight(context: Context, login: String, name: String? = null): Boolean {
        val norm = normalizeLogin(login) ?: return false
        val currentBlocked = list(context)
        if (currentBlocked.any { it.login == norm }) {
            writeLocked(context, currentBlocked.filterNot { it.login == norm }, bumpVersion = false)
        }
        val current = highlightedList(context)
        if (current.any { it.login == norm }) {
            version.value++
            return false
        }
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
        writeHighlightedLocked(context, listOf(BlockedUser(norm, cleanName)) + current)
        return true
    }

    @Synchronized
    fun highlightMany(context: Context, rawInput: String): List<BlockedUser> {
        val logins = parseLogins(rawInput)
        if (logins.isEmpty()) return emptyList()
        val loginSet = logins.toHashSet()
        val currentBlocked = list(context)
        if (currentBlocked.any { it.login in loginSet }) {
            writeLocked(context, currentBlocked.filterNot { it.login in loginSet }, bumpVersion = false)
        }
        val current = highlightedList(context)
        val existingByLogin = current.associateBy { it.login }
        val newEntries = logins.filter { it !in existingByLogin }.map { BlockedUser(it) }
        if (newEntries.isNotEmpty()) {
            writeHighlightedLocked(context, newEntries + current)
        } else {
            version.value++
        }
        return logins.map { existingByLogin[it] ?: BlockedUser(it) }
    }

    @Synchronized
    fun unhighlight(context: Context, login: String): Boolean {
        val norm = normalizeLogin(login) ?: return false
        val current = highlightedList(context)
        val filtered = current.filterNot { it.login == norm }
        if (filtered.size == current.size) return false
        writeHighlightedLocked(context, filtered)
        return true
    }

    /** Toggles highlight state and returns `true` if now highlighted, `false` if highlight removed. */
    @Synchronized
    fun toggleHighlight(context: Context, login: String, name: String? = null): Boolean {
        val norm = normalizeLogin(login) ?: return false
        return if (isHighlighted(context, norm)) {
            unhighlight(context, norm)
            false
        } else {
            highlight(context, norm, name)
            true
        }
    }

    fun notifyPrefsChanged() {
        version.value++
    }

    fun isBotMessage(message: ChatMessage): Boolean {
        val login = message.userLogin?.lowercase()
        if (login != null && login in KNOWN_BOTS) {
            return true
        }
        return message.badges?.any { it.setId == "bot-badge" } == true
    }

    fun shouldFilter(
        context: Context,
        message: ChatMessage,
        channelLogin: String?,
        ownUserId: String?,
        ownLogin: String?,
    ): Boolean {
        val target = if (message.type == ChatMessage.REPLY_MESSAGE) {
            message.replyParent ?: return false
        } else {
            message
        }
        if (target.type != ChatMessage.USER_MESSAGE) {
            return false
        }
        if (!ownUserId.isNullOrBlank() && target.userId == ownUserId) {
            return false
        }
        val login = target.userLogin?.lowercase()
        if (!ownLogin.isNullOrBlank() && login != null && login == ownLogin.lowercase()) {
            return false
        }
        if (!channelLogin.isNullOrBlank() && login != null && login == channelLogin.lowercase()) {
            return false
        }
        val blocked = blockedLogins(context)
        if (login != null && login in blocked) {
            return true
        }
        val hideBots = context.prefs().getBoolean(C.CHAT_HIDE_BOTS, false)
        if (hideBots && isBotMessage(target) && (login == null || login !in highlightedLogins(context))) {
            return true
        }
        if (message.type == ChatMessage.REPLY_MESSAGE) {
            val repliedLogin = target.reply?.userLogin?.lowercase()
            if (repliedLogin != null &&
                !repliedLogin.equals(ownLogin, ignoreCase = true) &&
                !repliedLogin.equals(channelLogin, ignoreCase = true) &&
                (repliedLogin in blocked || (hideBots && repliedLogin in KNOWN_BOTS && repliedLogin !in highlightedLogins(context)))
            ) {
                return true
            }
        }
        return false
    }

    fun summary(context: Context): String {
        val count = list(context).size
        return when (count) {
            0 -> context.getString(R.string.chat_blocked_users_none)
            1 -> context.getString(R.string.chat_blocked_users_one)
            else -> context.getString(R.string.chat_blocked_users_count, count)
        }
    }

    fun highlightedSummary(context: Context): String {
        val count = highlightedList(context).size
        return when (count) {
            0 -> context.getString(R.string.chat_highlighted_users_none)
            1 -> context.getString(R.string.chat_highlighted_users_one)
            else -> context.getString(R.string.chat_highlighted_users_count, count)
        }
    }

    private val HIGHLIGHT_GOLD = Color(0xFFF5B638)

    /** TV player side panel: hide bots switch, manual username highlight/block, active chatters, highlighted users, and blocked accounts. */
    fun showPlayerBlockPanel(
        context: Context,
        alignEnd: Boolean,
        recentChatters: List<BlockedUser>,
    ) {
        TvDialogs.show(context) { _ ->
            val ver by version.collectAsState()
            val blockedNow = remember(ver) { list(context) }
            val blockedSet = remember(ver) { blockedNow.mapTo(HashSet()) { it.login } }
            val highlightedNow = remember(ver) { highlightedList(context) }
            val highlightedSet = remember(ver) { highlightedNow.mapTo(HashSet()) { it.login } }
            val addedManual = remember { mutableStateListOf<BlockedUser>() }
            var hideBots by remember {
                mutableStateOf(context.prefs().getBoolean(C.CHAT_HIDE_BOTS, false))
            }
            val recentMerged = remember(recentChatters, addedManual.toList()) {
                val seen = HashSet<String>()
                buildList {
                    addedManual.forEach { if (seen.add(it.login)) add(it) }
                    recentChatters.forEach { if (seen.add(it.login)) add(it) }
                }
            }
            val recentLogins = remember(recentMerged) { recentMerged.mapTo(HashSet()) { it.login } }
            val otherHighlighted = remember(recentLogins, highlightedNow) {
                highlightedNow.filter { it.login !in recentLogins }
            }
            val otherBlocked = remember(recentLogins, blockedNow) {
                blockedNow.filter { it.login !in recentLogins }
            }
            val hasAnyUsers = recentMerged.isNotEmpty() || otherHighlighted.isNotEmpty() || otherBlocked.isNotEmpty()
            val firstRecentFocus = remember { FocusRequester() }
            val topFocus = remember { FocusRequester() }
            val listState = rememberLazyListState()

            SidePanel(
                title = context.getString(R.string.tv_chat_manage_users),
                alignEnd = alignEnd,
                scrollable = false,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvTopFade { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 },
                ) {
                    item(key = "hide_bots") {
                        SelectorRow(
                            label = context.getString(R.string.chat_hide_bots),
                            options = listOf(
                                context.getString(R.string.tv_chat_delay_off),
                                context.getString(R.string.tv_proxies_on),
                            ),
                            selected = if (hideBots) 1 else 0,
                            modifier = Modifier.focusRequester(topFocus),
                        ) { idx ->
                            val enabled = idx == 1
                            hideBots = enabled
                            context.prefs().edit { putBoolean(C.CHAT_HIDE_BOTS, enabled) }
                            notifyPrefsChanged()
                        }
                    }
                    item(key = "add_highlight") {
                        FocusRow(
                            onClick = {
                                showTvAddHighlightComposer(context) { addedUsers ->
                                    addedUsers.asReversed().forEach { added ->
                                        addedManual.removeAll { it.login == added.login }
                                        addedManual.add(0, added)
                                    }
                                }
                            }
                        ) { focused ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painterResource(R.drawable.tv_ic_star),
                                    contentDescription = null,
                                    tint = if (focused) TvColors.onFocused else HIGHLIGHT_GOLD,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    context.getString(R.string.chat_highlight_add),
                                    fontSize = 17.sp,
                                    color = if (focused) TvColors.onFocused else Color.White,
                                )
                            }
                        }
                    }
                    item(key = "add_manual") {
                        FocusRow(
                            onClick = {
                                showTvAddUserComposer(context) { addedUsers ->
                                    addedUsers.asReversed().forEach { added ->
                                        addedManual.removeAll { it.login == added.login }
                                        addedManual.add(0, added)
                                    }
                                }
                            }
                        ) { focused ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painterResource(R.drawable.tv_ic_add),
                                    contentDescription = null,
                                    tint = if (focused) TvColors.onFocused else Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    context.getString(R.string.chat_block_add),
                                    fontSize = 17.sp,
                                    color = if (focused) TvColors.onFocused else Color.White,
                                )
                            }
                        }
                    }
                    if (recentMerged.isNotEmpty()) {
                        item(key = "header_recent") {
                            SectionLabel(context.getString(R.string.tv_chat_recent_users_manage))
                        }
                        itemsIndexed(recentMerged, key = { _, u -> "recent_${u.login}" }) { index, user ->
                            val isUserHighlighted = user.login in highlightedSet
                            val isUserBlocked = user.login in blockedSet
                            UserStatusRow(
                                user = user,
                                isHighlighted = isUserHighlighted,
                                isBlocked = isUserBlocked,
                                modifier = if (index == 0) Modifier.focusRequester(firstRecentFocus) else Modifier,
                                highlightedBadge = context.getString(R.string.chat_highlighted_badge),
                                blockedBadge = context.getString(R.string.chat_blocked_badge),
                            ) {
                                showUserActionChoices(context, user)
                            }
                        }
                    }
                    if (otherHighlighted.isNotEmpty()) {
                        item(key = "header_highlighted") {
                            SectionLabel(context.getString(R.string.chat_highlighted_users))
                        }
                        itemsIndexed(otherHighlighted, key = { _, u -> "highlighted_${u.login}" }) { index, user ->
                            val isUserHighlighted = user.login in highlightedSet
                            UserStatusRow(
                                user = user,
                                isHighlighted = isUserHighlighted,
                                isBlocked = false,
                                modifier = if (recentMerged.isEmpty() && index == 0) Modifier.focusRequester(firstRecentFocus) else Modifier,
                                highlightedBadge = context.getString(R.string.chat_highlighted_badge),
                                blockedBadge = context.getString(R.string.chat_blocked_badge),
                            ) {
                                val nowHighlighted = toggleHighlight(context, user.login, user.name)
                                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                                    context,
                                    context.getString(
                                        if (nowHighlighted) R.string.chat_user_highlighted else R.string.chat_user_unhighlighted,
                                        user.displayLabel,
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                    if (otherBlocked.isNotEmpty()) {
                        item(key = "header_blocked") {
                            SectionLabel(context.getString(R.string.chat_blocked_users))
                        }
                        itemsIndexed(otherBlocked, key = { _, u -> "blocked_${u.login}" }) { index, user ->
                            val isUserBlocked = user.login in blockedSet
                            UserStatusRow(
                                user = user,
                                isHighlighted = false,
                                isBlocked = isUserBlocked,
                                modifier = if (recentMerged.isEmpty() && otherHighlighted.isEmpty() && index == 0) Modifier.focusRequester(firstRecentFocus) else Modifier,
                                highlightedBadge = context.getString(R.string.chat_highlighted_badge),
                                blockedBadge = context.getString(R.string.chat_blocked_badge),
                            ) {
                                val nowBlocked = toggle(context, user.login, user.name)
                                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                                    context,
                                    context.getString(
                                        if (nowBlocked) R.string.chat_user_blocked else R.string.chat_user_unblocked,
                                        user.displayLabel,
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                    if (!hasAnyUsers) {
                        item(key = "empty") {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                context.getString(R.string.tv_chat_recent_empty),
                                fontSize = 14.sp,
                                color = TvColors.secondaryText,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                runCatching {
                    topFocus.requestFocus()
                }
            }
        }
    }

    private fun showUserActionChoices(context: Context, user: BlockedUser) {
        val currentlyHighlighted = isHighlighted(context, user.login)
        val currentlyBlocked = isBlocked(context, user.login)
        val options = listOf(
            context.getString(if (currentlyHighlighted) R.string.chat_unhighlight_action else R.string.chat_highlight_action),
            context.getString(if (currentlyBlocked) R.string.chat_unblock_action else R.string.chat_block_action),
        )
        val icons = listOf(
            R.drawable.tv_ic_star,
            if (currentlyBlocked) R.drawable.tv_ic_person else R.drawable.tv_ic_block,
        )
        TvDialogs.showChoices(
            context = context,
            title = user.displayLabel,
            options = options,
            selected = 0,
            icons = icons,
        ) { which ->
            when (which) {
                0 -> {
                    val nowHighlighted = toggleHighlight(context, user.login, user.name)
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                        context,
                        context.getString(
                            if (nowHighlighted) R.string.chat_user_highlighted else R.string.chat_user_unhighlighted,
                            user.displayLabel,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                1 -> {
                    val nowBlocked = toggle(context, user.login, user.name)
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                        context,
                        context.getString(
                            if (nowBlocked) R.string.chat_user_blocked else R.string.chat_user_unblocked,
                            user.displayLabel,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    /** Settings > Chat dialog/panel to view, add, and remove highlighted accounts. */
    fun showHighlightedSettingsDialog(context: Context, onChanged: () -> Unit) {
        if (context.resources.getBoolean(R.bool.isTv)) {
            val sessionUsers = ArrayList(highlightedList(context))
            val dialog = TvDialogs.show(context) { _ ->
                val ver by version.collectAsState()
                val highlightedSet = remember(ver) { highlightedLogins(context) }
                val entries = remember { mutableStateListOf<BlockedUser>().apply { addAll(sessionUsers) } }
                val firstFocus = remember { FocusRequester() }
                val listState = rememberLazyListState()

                SidePanel(
                    title = context.getString(R.string.chat_highlighted_users),
                    subtitle = highlightedSummary(context),
                    scrollable = false,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvTopFade { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 },
                    ) {
                        item(key = "add") {
                            FocusRow(
                                modifier = if (entries.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                                onClick = {
                                    showTvAddHighlightComposer(context) { addedUsers ->
                                        addedUsers.asReversed().forEach { added ->
                                            entries.removeAll { it.login == added.login }
                                            entries.add(0, added)
                                        }
                                        onChanged()
                                    }
                                },
                            ) { focused ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painterResource(R.drawable.tv_ic_star),
                                        contentDescription = null,
                                        tint = if (focused) TvColors.onFocused else HIGHLIGHT_GOLD,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        context.getString(R.string.chat_highlight_add),
                                        fontSize = 17.sp,
                                        color = if (focused) TvColors.onFocused else Color.White,
                                    )
                                }
                            }
                        }
                        if (entries.isEmpty()) {
                            item(key = "none") {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    context.getString(R.string.chat_highlighted_users_none),
                                    fontSize = 14.sp,
                                    color = TvColors.secondaryText,
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                            }
                        } else {
                            itemsIndexed(entries, key = { _, u -> u.login }) { index, user ->
                                val isUserHighlighted = user.login in highlightedSet
                                UserStatusRow(
                                    user = user,
                                    isHighlighted = isUserHighlighted,
                                    isBlocked = false,
                                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                    highlightedBadge = context.getString(R.string.chat_highlighted_badge),
                                    blockedBadge = context.getString(R.string.chat_blocked_badge),
                                ) {
                                    val nowHighlighted = toggleHighlight(context, user.login, user.name)
                                    onChanged()
                                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                                        context,
                                        context.getString(
                                            if (nowHighlighted) R.string.chat_user_highlighted else R.string.chat_user_unhighlighted,
                                            user.displayLabel,
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    runCatching { firstFocus.requestFocus() }
                }
            }
            dialog.setOnDismissListener { onChanged() }
        } else {
            showMobileHighlightedSettingsDialog(context, onChanged)
        }
    }

    private fun showMobileHighlightedSettingsDialog(context: Context, onChanged: () -> Unit) {
        val users = highlightedList(context)
        val builder = context.getAlertDialogBuilder()
            .setTitle(R.string.chat_highlighted_users)
            .setPositiveButton(R.string.chat_highlight_add) { _, _ ->
                showMobileAddHighlightDialog(context, onChanged)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (users.isEmpty()) {
            builder.setMessage(R.string.chat_highlighted_users_none)
        } else {
            val labels = users.map { it.displayLabel }.toTypedArray()
            builder.setItems(labels) { _, which ->
                val target = users.getOrNull(which) ?: return@setItems
                unhighlight(context, target.login)
                onChanged()
                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                    context,
                    context.getString(R.string.chat_user_unhighlighted, target.displayLabel),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        builder.show()
    }

    private fun showMobileAddHighlightDialog(context: Context, onChanged: () -> Unit) {
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(context).apply {
            hint = context.getString(R.string.chat_highlight_username_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        val container = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        context.getAlertDialogBuilder()
            .setTitle(R.string.chat_highlight_add)
            .setView(container)
            .setPositiveButton(R.string.chat_highlight_action) { _, _ ->
                val added = highlightMany(context, input.text?.toString().orEmpty())
                if (added.isNotEmpty()) {
                    onChanged()
                    val label = added.joinToString(", ") { it.displayLabel }
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                        context,
                        context.getString(R.string.chat_user_highlighted, label),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTvAddHighlightComposer(context: Context, onAdded: (List<BlockedUser>) -> Unit) {
        TvDialogs.show(context) { dismiss ->
            ChatComposer(
                title = context.getString(R.string.chat_highlight_add),
                hint = context.getString(R.string.chat_highlight_username_hint),
                sendLabel = context.getString(R.string.chat_highlight_action),
            ) { raw ->
                val added = highlightMany(context, raw)
                if (added.isNotEmpty()) {
                    val label = added.joinToString(", ") { it.displayLabel }
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                        context,
                        context.getString(R.string.chat_user_highlighted, label),
                        Toast.LENGTH_SHORT,
                    ).show()
                    dismiss()
                    onAdded(added)
                }
            }
        }
    }

    /** Settings > Chat dialog/panel to view, add, and unblock accounts. */
    fun showSettingsDialog(context: Context, onChanged: () -> Unit) {
        if (context.resources.getBoolean(R.bool.isTv)) {
            val sessionUsers = ArrayList(list(context))
            val dialog = TvDialogs.show(context) { _ ->
                val ver by version.collectAsState()
                val blockedSet = remember(ver) { blockedLogins(context) }
                val entries = remember { mutableStateListOf<BlockedUser>().apply { addAll(sessionUsers) } }
                val firstFocus = remember { FocusRequester() }
                val listState = rememberLazyListState()

                SidePanel(
                    title = context.getString(R.string.chat_blocked_users),
                    subtitle = summary(context),
                    scrollable = false,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvTopFade { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 },
                    ) {
                        item(key = "add") {
                            FocusRow(
                                modifier = if (entries.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                                onClick = {
                                    showTvAddUserComposer(context) { addedUsers ->
                                        addedUsers.asReversed().forEach { added ->
                                            entries.removeAll { it.login == added.login }
                                            entries.add(0, added)
                                        }
                                        onChanged()
                                    }
                                },
                            ) { focused ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painterResource(R.drawable.tv_ic_add),
                                        contentDescription = null,
                                        tint = if (focused) TvColors.onFocused else Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        context.getString(R.string.chat_block_add),
                                        fontSize = 17.sp,
                                        color = if (focused) TvColors.onFocused else Color.White,
                                    )
                                }
                            }
                        }
                        if (entries.isEmpty()) {
                            item(key = "none") {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    context.getString(R.string.chat_blocked_users_none),
                                    fontSize = 14.sp,
                                    color = TvColors.secondaryText,
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                            }
                        } else {
                            itemsIndexed(entries, key = { _, u -> u.login }) { index, user ->
                                val isUserBlocked = user.login in blockedSet
                                UserStatusRow(
                                    user = user,
                                    isHighlighted = false,
                                    isBlocked = isUserBlocked,
                                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                    highlightedBadge = context.getString(R.string.chat_highlighted_badge),
                                    blockedBadge = context.getString(R.string.chat_blocked_badge),
                                ) {
                                    val nowBlocked = toggle(context, user.login, user.name)
                                    onChanged()
                                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                                        context,
                                        context.getString(
                                            if (nowBlocked) R.string.chat_user_blocked else R.string.chat_user_unblocked,
                                            user.displayLabel,
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    runCatching { firstFocus.requestFocus() }
                }
            }
            dialog.setOnDismissListener { onChanged() }
        } else {
            showMobileSettingsDialog(context, onChanged)
        }
    }

    private fun showMobileSettingsDialog(context: Context, onChanged: () -> Unit) {
        val users = list(context)
        val builder = context.getAlertDialogBuilder()
            .setTitle(R.string.chat_blocked_users)
            .setPositiveButton(R.string.chat_block_add) { _, _ ->
                showMobileAddDialog(context, onChanged)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (users.isEmpty()) {
            builder.setMessage(R.string.chat_blocked_users_none)
        } else {
            val labels = users.map { it.displayLabel }.toTypedArray()
            builder.setItems(labels) { _, which ->
                val target = users.getOrNull(which) ?: return@setItems
                unblock(context, target.login)
                onChanged()
                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                    context,
                    context.getString(R.string.chat_user_unblocked, target.displayLabel),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        builder.show()
    }

    private fun showMobileAddDialog(context: Context, onChanged: () -> Unit) {
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(context).apply {
            hint = context.getString(R.string.chat_block_username_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        val container = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        context.getAlertDialogBuilder()
            .setTitle(R.string.chat_block_add)
            .setView(container)
            .setPositiveButton(R.string.chat_block_action) { _, _ ->
                val added = blockMany(context, input.text?.toString().orEmpty())
                if (added.isNotEmpty()) {
                    onChanged()
                    val label = added.joinToString(", ") { it.displayLabel }
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                        context,
                        context.getString(R.string.chat_user_blocked, label),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTvAddUserComposer(context: Context, onAdded: (List<BlockedUser>) -> Unit) {
        TvDialogs.show(context) { dismiss ->
            ChatComposer(
                title = context.getString(R.string.chat_block_add),
                hint = context.getString(R.string.chat_block_username_hint),
                sendLabel = context.getString(R.string.chat_block_action),
            ) { raw ->
                val added = blockMany(context, raw)
                if (added.isNotEmpty()) {
                    val label = added.joinToString(", ") { it.displayLabel }
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                        context,
                        context.getString(R.string.chat_user_blocked, label),
                        Toast.LENGTH_SHORT,
                    ).show()
                    dismiss()
                    onAdded(added)
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Column {
            Spacer(Modifier.height(14.dp))
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TvColors.secondaryText,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }

    @Composable
    private fun UserStatusRow(
        user: BlockedUser,
        isHighlighted: Boolean,
        isBlocked: Boolean,
        modifier: Modifier = Modifier,
        highlightedBadge: String,
        blockedBadge: String,
        onClick: () -> Unit,
    ) {
        FocusRow(modifier = modifier, onClick = onClick) { focused ->
            val fg = if (focused) TvColors.onFocused else Color.White
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(
                            when {
                                isHighlighted -> R.drawable.tv_ic_star
                                isBlocked -> R.drawable.tv_ic_block
                                else -> R.drawable.tv_ic_person
                            }
                        ),
                        contentDescription = null,
                        tint = when {
                            focused -> TvColors.onFocused
                            isHighlighted -> HIGHLIGHT_GOLD
                            isBlocked -> MaterialTheme.colorScheme.primary
                            else -> TvColors.secondaryText
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = user.displayLabel,
                        fontSize = 16.sp,
                        fontWeight = if (isHighlighted || isBlocked) FontWeight.SemiBold else FontWeight.Normal,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    isHighlighted -> {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = highlightedBadge,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (focused) TvColors.onFocused else HIGHLIGHT_GOLD,
                        )
                    }
                    isBlocked -> {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = blockedBadge,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (focused) TvColors.onFocused else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
