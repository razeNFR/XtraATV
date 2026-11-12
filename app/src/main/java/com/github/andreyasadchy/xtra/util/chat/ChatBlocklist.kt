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

    fun normalizeLogin(raw: String?): String? {
        val cleaned = raw?.trim()?.removePrefix("@")?.trim()?.lowercase()
        if (cleaned.isNullOrEmpty()) return null
        return cleaned.takeIf { it.all { ch -> ch.isLetterOrDigit() || ch == '_' } }
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
    private fun syncFromPrefsLocked(context: Context) {
        val raw = context.prefs().getString(C.CHAT_BLOCKED_USERS, null)
        if (raw == cachedRaw) return
        cachedRaw = raw
        if (raw.isNullOrBlank()) {
            cachedList = emptyList()
            cachedLogins = emptySet()
            return
        }
        val parsed = try {
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
            emptyList()
        }
        cachedList = parsed
        cachedLogins = parsed.mapTo(HashSet()) { it.login }
    }

    @Synchronized
    private fun writeLocked(context: Context, users: List<BlockedUser>) {
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
        val raw = if (users.isEmpty()) null else array.toString()
        context.prefs().edit {
            if (raw == null) remove(C.CHAT_BLOCKED_USERS) else putString(C.CHAT_BLOCKED_USERS, raw)
        }
        cachedRaw = raw
        cachedList = users
        cachedLogins = users.mapTo(HashSet()) { it.login }
        version.value++
    }

    @Synchronized
    fun block(context: Context, login: String, name: String? = null): Boolean {
        val norm = normalizeLogin(login) ?: return false
        val current = list(context)
        if (current.any { it.login == norm }) return false
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
        writeLocked(context, listOf(BlockedUser(norm, cleanName)) + current)
        return true
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
        if (hideBots && isBotMessage(target)) {
            return true
        }
        if (message.type == ChatMessage.REPLY_MESSAGE) {
            val repliedLogin = target.reply?.userLogin?.lowercase()
            if (repliedLogin != null &&
                !repliedLogin.equals(ownLogin, ignoreCase = true) &&
                !repliedLogin.equals(channelLogin, ignoreCase = true) &&
                (repliedLogin in blocked || (hideBots && repliedLogin in KNOWN_BOTS))
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

    /** TV player side panel: hide bots switch, manual username block, recent chatters in chat, and blocked accounts. */
    fun showPlayerBlockPanel(
        context: Context,
        alignEnd: Boolean,
        recentChatters: List<BlockedUser>,
    ) {
        TvDialogs.show(context) { _ ->
            val ver by version.collectAsState()
            val blockedNow = remember(ver) { list(context) }
            val blockedSet = remember(ver) { blockedNow.mapTo(HashSet()) { it.login } }
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
            val otherBlocked = remember(recentMerged, blockedNow) {
                val recentLogins = recentMerged.mapTo(HashSet()) { it.login }
                blockedNow.filter { it.login !in recentLogins }
            }
            val firstRecentFocus = remember { FocusRequester() }
            val topFocus = remember { FocusRequester() }
            val listState = rememberLazyListState()

            SidePanel(
                title = context.getString(R.string.tv_chat_block_users),
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
                            modifier = if (recentMerged.isEmpty() && otherBlocked.isEmpty()) Modifier.focusRequester(topFocus) else Modifier,
                        ) { idx ->
                            val enabled = idx == 1
                            hideBots = enabled
                            context.prefs().edit { putBoolean(C.CHAT_HIDE_BOTS, enabled) }
                            notifyPrefsChanged()
                        }
                    }
                    item(key = "add_manual") {
                        FocusRow(
                            onClick = {
                                showTvAddUserComposer(context) { added ->
                                    addedManual.removeAll { it.login == added.login }
                                    addedManual.add(0, added)
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
                            SectionLabel(context.getString(R.string.tv_chat_recent_users))
                        }
                        itemsIndexed(recentMerged, key = { _, u -> "recent_${u.login}" }) { index, user ->
                            val isUserBlocked = user.login in blockedSet
                            UserToggleRow(
                                user = user,
                                isBlocked = isUserBlocked,
                                modifier = if (index == 0) Modifier.focusRequester(firstRecentFocus) else Modifier,
                                blockedBadge = context.getString(R.string.chat_blocked_badge),
                            ) {
                                val nowBlocked = toggle(context, user.login, user.name)
                                Toast.makeText(
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
                    if (otherBlocked.isNotEmpty()) {
                        item(key = "header_blocked") {
                            SectionLabel(context.getString(R.string.chat_blocked_users))
                        }
                        itemsIndexed(otherBlocked, key = { _, u -> "blocked_${u.login}" }) { index, user ->
                            val isUserBlocked = user.login in blockedSet
                            UserToggleRow(
                                user = user,
                                isBlocked = isUserBlocked,
                                modifier = if (recentMerged.isEmpty() && index == 0) Modifier.focusRequester(firstRecentFocus) else Modifier,
                                blockedBadge = context.getString(R.string.chat_blocked_badge),
                            ) {
                                val nowBlocked = toggle(context, user.login, user.name)
                                Toast.makeText(
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
                    if (recentMerged.isEmpty() && otherBlocked.isEmpty()) {
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
                    if (recentMerged.isNotEmpty() || otherBlocked.isNotEmpty()) {
                        firstRecentFocus.requestFocus()
                    } else {
                        topFocus.requestFocus()
                    }
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
                                    showTvAddUserComposer(context) { added ->
                                        entries.removeAll { it.login == added.login }
                                        entries.add(0, added)
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
                                UserToggleRow(
                                    user = user,
                                    isBlocked = isUserBlocked,
                                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                    blockedBadge = context.getString(R.string.chat_blocked_badge),
                                ) {
                                    val nowBlocked = toggle(context, user.login, user.name)
                                    onChanged()
                                    Toast.makeText(
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
            .setNegativeButton(R.string.cancel, null)
        if (users.isEmpty()) {
            builder.setMessage(R.string.chat_blocked_users_none)
        } else {
            val labels = users.map { it.displayLabel }.toTypedArray()
            builder.setItems(labels) { _, which ->
                val target = users.getOrNull(which) ?: return@setItems
                unblock(context, target.login)
                onChanged()
                Toast.makeText(
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
                val norm = normalizeLogin(input.text?.toString())
                if (norm != null) {
                    block(context, norm)
                    onChanged()
                    Toast.makeText(
                        context,
                        context.getString(R.string.chat_user_blocked, norm),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTvAddUserComposer(context: Context, onAdded: (BlockedUser) -> Unit) {
        TvDialogs.show(context) { dismiss ->
            ChatComposer(
                title = context.getString(R.string.chat_block_add),
                hint = context.getString(R.string.chat_block_username_hint),
                sendLabel = context.getString(R.string.chat_block_action),
            ) { raw ->
                val norm = normalizeLogin(raw)
                if (norm != null) {
                    block(context, norm)
                    val entry = BlockedUser(norm)
                    Toast.makeText(
                        context,
                        context.getString(R.string.chat_user_blocked, entry.displayLabel),
                        Toast.LENGTH_SHORT,
                    ).show()
                    dismiss()
                    onAdded(entry)
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
    private fun UserToggleRow(
        user: BlockedUser,
        isBlocked: Boolean,
        modifier: Modifier = Modifier,
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
                        painterResource(if (isBlocked) R.drawable.tv_ic_block else R.drawable.tv_ic_person),
                        contentDescription = null,
                        tint = when {
                            focused -> TvColors.onFocused
                            isBlocked -> MaterialTheme.colorScheme.primary
                            else -> TvColors.secondaryText
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = user.displayLabel,
                        fontSize = 16.sp,
                        fontWeight = if (isBlocked) FontWeight.SemiBold else FontWeight.Normal,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isBlocked) {
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
