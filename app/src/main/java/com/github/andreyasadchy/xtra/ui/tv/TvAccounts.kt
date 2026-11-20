package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.home.TvHomeRows
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * TV: Multi-Account Twitch vault + 1-click Guest Mode.
 *
 * - Saved accounts live exclusively inside `tokenPrefs()` (`tv_accounts_v1`), so `TvBackup` never exports credentials.
 * - Primary Account #1 keeps storage suffix `""` so existing `tv_favorites`, `tv_stats`, and `tv_home_rows` remain 100% intact.
 * - Secondary accounts use `"_u_<accountKey>"` and Guest mode uses `"_guest"`.
 * - Switching accounts or entering Guest mode NEVER revokes saved tokens on Twitch; tokens are only revoked when explicitly
 *   removing an account from this TV.
 */
object TvAccounts {

    private const val KEY_ACCOUNTS = "tv_accounts_v1"
    private const val KEY_PRIMARY_ID = "tv_accounts_primary_id"
    private const val KEY_GUEST_MODE = "tv_accounts_guest_mode"
    const val GUEST_SENTINEL = "__guest__"

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class SavedAccount(
        val key: String,
        val userId: String?,
        val username: String,
        val avatarUrl: String?,
        val helixToken: String?,
        val gqlToken: String?,
        val gqlToken2: String?,
        val gqlTokenWeb: String?,
        val gqlHeaders: String?,
        val integrityExpiration: Long,
        val gqlClientId2: String?,
        val gqlClientIdWeb: String?,
    )

    fun isGuestMode(context: Context): Boolean =
        context.tokenPrefs().getBoolean(KEY_GUEST_MODE, false) && activeAccountKey(context) == null

    fun activeAccountKey(context: Context): String? {
        val tokens = context.tokenPrefs()
        val hasToken = !tokens.getString(C.GQL_TOKEN2, null).isNullOrBlank() ||
            !tokens.getString(C.GQL_TOKEN, null).isNullOrBlank() ||
            !tokens.getString(C.TOKEN, null).isNullOrBlank() ||
            !tokens.getString(C.GQL_TOKEN_WEB, null).isNullOrBlank()
        if (!hasToken) return null
        val uid = tokens.getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }
        val uname = tokens.getString(C.USERNAME, null)?.takeIf { it.isNotBlank() }
        return uid ?: uname?.let { "login:${it.lowercase()}" }
    }

    /**
     * Storage suffix for per-account TV preferences (`TvFavorites`, `TvStats`, `TvHomeRows`).
     * Primary Account #1 always returns `""` for 100% compatibility with existing installs and `TvBackup`.
     */
    fun storageSuffix(context: Context): String {
        val tokens = context.tokenPrefs()
        val active = activeAccountKey(context)
        if (active == null) {
            return if (tokens.getBoolean(KEY_GUEST_MODE, false)) "_guest" else ""
        }
        val primary = tokens.getString(KEY_PRIMARY_ID, null)?.takeIf { it.isNotBlank() }
        if (primary == null || active == primary) return ""
        val sanitized = active.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "_u_$sanitized"
    }

    @Synchronized
    fun listAccounts(context: Context): List<SavedAccount> {
        val raw = context.tokenPrefs().getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key").takeIf { it.isNotBlank() } ?: continue
                    val username = o.optString("username").takeIf { it.isNotBlank() } ?: continue
                    add(
                        SavedAccount(
                            key = key,
                            userId = o.optString("userId").takeIf { it.isNotBlank() },
                            username = username,
                            avatarUrl = o.optString("avatarUrl").takeIf { it.isNotBlank() },
                            helixToken = o.optString("helixToken").takeIf { it.isNotBlank() },
                            gqlToken = o.optString("gqlToken").takeIf { it.isNotBlank() },
                            gqlToken2 = o.optString("gqlToken2").takeIf { it.isNotBlank() },
                            gqlTokenWeb = o.optString("gqlTokenWeb").takeIf { it.isNotBlank() },
                            gqlHeaders = o.optString("gqlHeaders").takeIf { it.isNotBlank() },
                            integrityExpiration = o.optLong("integrityExpiration", 0L),
                            gqlClientId2 = o.optString("gqlClientId2").takeIf { it.isNotBlank() },
                            gqlClientIdWeb = o.optString("gqlClientIdWeb").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveAccounts(context: Context, list: List<SavedAccount>) {
        val arr = JSONArray()
        list.forEach { acc ->
            arr.put(
                JSONObject().apply {
                    put("key", acc.key)
                    put("userId", acc.userId ?: "")
                    put("username", acc.username)
                    put("avatarUrl", acc.avatarUrl ?: "")
                    put("helixToken", acc.helixToken ?: "")
                    put("gqlToken", acc.gqlToken ?: "")
                    put("gqlToken2", acc.gqlToken2 ?: "")
                    put("gqlTokenWeb", acc.gqlTokenWeb ?: "")
                    put("gqlHeaders", acc.gqlHeaders ?: "")
                    put("integrityExpiration", acc.integrityExpiration)
                    put("gqlClientId2", acc.gqlClientId2 ?: "")
                    put("gqlClientIdWeb", acc.gqlClientIdWeb ?: "")
                },
            )
        }
        context.tokenPrefs().edit {
            putString(KEY_ACCOUNTS, arr.toString())
        }
    }

    /**
     * Snapshots the currently active Twitch account in `tokenPrefs()` into the saved accounts vault.
     * Automatically registers the first logged-in account as Primary Account #1 (`storageSuffix == ""`).
     */
    @Synchronized
    fun syncCurrentFromPrefs(context: Context, avatarUrl: String? = null) {
        val tokens = context.tokenPrefs()
        val prefs = context.prefs()
        val activeKey = activeAccountKey(context) ?: return
        val uid = tokens.getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }
        val uname = tokens.getString(C.USERNAME, null)?.takeIf { it.isNotBlank() } ?: uid ?: return

        val currentList = listAccounts(context).toMutableList()
        val existingIdx = currentList.indexOfFirst {
            it.key == activeKey || (uid != null && it.userId == uid) || it.username.equals(uname, ignoreCase = true)
        }
        val existing = currentList.getOrNull(existingIdx)

        val updated = SavedAccount(
            key = activeKey,
            userId = uid ?: existing?.userId,
            username = uname,
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() } ?: existing?.avatarUrl,
            helixToken = tokens.getString(C.TOKEN, null)?.takeIf { it.isNotBlank() },
            gqlToken = tokens.getString(C.GQL_TOKEN, null)?.takeIf { it.isNotBlank() },
            gqlToken2 = tokens.getString(C.GQL_TOKEN2, null)?.takeIf { it.isNotBlank() },
            gqlTokenWeb = tokens.getString(C.GQL_TOKEN_WEB, null)?.takeIf { it.isNotBlank() },
            gqlHeaders = tokens.getString(C.GQL_HEADERS, null)?.takeIf { it.isNotBlank() },
            integrityExpiration = tokens.getLong(C.INTEGRITY_EXPIRATION, 0L),
            gqlClientId2 = prefs.getString(C.GQL_CLIENT_ID2, null)?.takeIf { it.isNotBlank() },
            gqlClientIdWeb = prefs.getString(C.GQL_CLIENT_ID_WEB, null)?.takeIf { it.isNotBlank() },
        )

        val existingPrimary = tokens.getString(KEY_PRIMARY_ID, null)?.takeIf { it.isNotBlank() }
        if (existingPrimary == null || (existing != null && existing.key == existingPrimary && existing.key != activeKey)) {
            tokens.edit { putString(KEY_PRIMARY_ID, activeKey) }
        }
        if (tokens.getBoolean(KEY_GUEST_MODE, false)) {
            tokens.edit { putBoolean(KEY_GUEST_MODE, false) }
        }

        if (existingIdx >= 0) {
            currentList[existingIdx] = updated
        } else {
            currentList.add(updated)
        }
        saveAccounts(context, currentList)
    }

    private fun clearActiveSessionKeys(context: Context, guestMode: Boolean) {
        TwitchApiHelper.checkedValidation = false
        context.tokenPrefs().edit {
            putString(C.TOKEN, null)
            putString(C.GQL_TOKEN, null)
            putString(C.GQL_TOKEN2, null)
            putString(C.GQL_TOKEN_WEB, null)
            putString(C.GQL_HEADERS, null)
            putLong(C.INTEGRITY_EXPIRATION, 0L)
            putString(C.USER_ID, null)
            putString(C.USERNAME, null)
            putBoolean(KEY_GUEST_MODE, guestMode)
        }
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }

    private fun notifyStoresChanged(context: Context) {
        TvFavorites.reload(context)
        TvBlacklist.reload(context)
        TvStats.notifyAccountSwitched()
        TvHomeRows.notifyAccountSwitched()
    }

    @Synchronized
    fun switchToAccount(context: Context, key: String): SavedAccount? {
        syncCurrentFromPrefs(context)
        val target = listAccounts(context).find { it.key == key } ?: return null
        TwitchApiHelper.checkedValidation = false
        context.prefs().edit {
            target.gqlClientId2?.let { putString(C.GQL_CLIENT_ID2, it) }
            target.gqlClientIdWeb?.let { putString(C.GQL_CLIENT_ID_WEB, it) }
        }
        context.tokenPrefs().edit {
            putString(C.TOKEN, target.helixToken)
            putString(C.GQL_TOKEN, target.gqlToken)
            putString(C.GQL_TOKEN2, target.gqlToken2)
            putString(C.GQL_TOKEN_WEB, target.gqlTokenWeb)
            putString(C.GQL_HEADERS, target.gqlHeaders)
            putLong(C.INTEGRITY_EXPIRATION, target.integrityExpiration)
            putString(C.USER_ID, target.userId)
            putString(C.USERNAME, target.username)
            putBoolean(KEY_GUEST_MODE, false)
        }
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        notifyStoresChanged(context)
        val appContext = context.applicationContext
        bgScope.launch {
            runCatching { TvLauncherChannels.syncFollowedLiveBackground(appContext) }
        }
        return target
    }

    @Synchronized
    fun switchToGuest(context: Context) {
        syncCurrentFromPrefs(context)
        clearActiveSessionKeys(context, guestMode = true)
        notifyStoresChanged(context)
        TvLauncherChannels.syncFollowedLive(context, emptyList())
    }

    /**
     * Snapshots the current account and clears only the active session keys without revoking tokens,
     * so `LoginActivity` can sign into an additional Twitch account cleanly.
     * Returns the previous key (`accountKey` or [GUEST_SENTINEL]) so canceling `LoginActivity` restores it.
     */
    @Synchronized
    fun prepareAddAccount(context: Context): String? {
        syncCurrentFromPrefs(context)
        val previous = activeAccountKey(context) ?: if (isGuestMode(context)) GUEST_SENTINEL else null
        clearActiveSessionKeys(context, guestMode = false)
        return previous
    }

    @Synchronized
    fun restoreAfterCanceledAdd(context: Context, previousKey: String?) {
        if (activeAccountKey(context) != null || isGuestMode(context)) return
        when {
            previousKey == GUEST_SENTINEL -> switchToGuest(context)
            !previousKey.isNullOrBlank() -> switchToAccount(context, previousKey)
        }
    }

    /**
     * Removes [key] from the saved vault, revokes ONLY its tokens on Twitch in the background,
     * and switches to the next saved account (or logged-out state if none remain).
     */
    @Synchronized
    fun removeAccount(context: Context, key: String): SavedAccount? {
        syncCurrentFromPrefs(context)
        val currentActive = activeAccountKey(context)
        val all = listAccounts(context).toMutableList()
        val idx = all.indexOfFirst { it.key == key }
        if (idx < 0) return null
        val removed = all.removeAt(idx)
        saveAccounts(context, all)

        // Revoke removed account's tokens on Twitch
        val app = context.applicationContext as XtraApp
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val helixClientId = TwitchApiHelper.getHelixHeaders(context)[C.HEADER_CLIENT_ID]
        val defaultWebClientId = context.prefs().getString(C.GQL_CLIENT_ID_WEB, "kimne78kx3ncx6brgo4mv6wki5h1ko")
        bgScope.launch {
            if (!helixClientId.isNullOrBlank() && !removed.helixToken.isNullOrBlank()) {
                runCatching {
                    app.xtraModule.authRepository.revoke(networkLibrary, "client_id=$helixClientId&token=${removed.helixToken}")
                }
            }
            val gqlCid = removed.gqlClientId2 ?: context.prefs().getString(C.GQL_CLIENT_ID2, "ue6666qo983tsx6so1t0vnawi233wa")
            val gqlTok = removed.gqlToken2 ?: removed.gqlToken
            if (!gqlCid.isNullOrBlank() && !gqlTok.isNullOrBlank() && gqlTok != removed.gqlTokenWeb) {
                runCatching {
                    app.xtraModule.authRepository.revoke(networkLibrary, "client_id=$gqlCid&token=$gqlTok")
                }
            }
            val webCid = removed.gqlClientIdWeb ?: defaultWebClientId
            if (!webCid.isNullOrBlank() && !removed.gqlTokenWeb.isNullOrBlank()) {
                runCatching {
                    app.xtraModule.authRepository.revoke(networkLibrary, "client_id=$webCid&token=${removed.gqlTokenWeb}")
                }
            }
        }

        if (currentActive == key) {
            val next = all.firstOrNull()
            if (next != null) {
                switchToAccount(context, next.key)
            } else {
                clearActiveSessionKeys(context, guestMode = false)
                notifyStoresChanged(context)
                TvLauncherChannels.syncFollowedLive(context, emptyList())
            }
        }
        return removed
    }

    fun showSwitcherPanel(
        activity: MainActivity,
        onOpenCurrentLoginMethods: () -> Unit,
        onAddAnotherAccount: () -> Unit,
    ) {
        syncCurrentFromPrefs(activity)
        TvDialogs.show(activity) { dismiss ->
            var accounts by remember { mutableStateOf(listAccounts(activity)) }
            val activeKey = activeAccountKey(activity)
            val guestActive = isGuestMode(activity)
            var optionsTarget by remember { mutableStateOf<SavedAccount?>(null) }
            val firstRequester = remember { FocusRequester() }

            val target = optionsTarget
            if (target != null) {
                val optRequester = remember { FocusRequester() }
                LaunchedEffect(target.key) {
                    runCatching { optRequester.requestFocus() }
                }
                SidePanel(
                    title = target.username,
                    scrollable = true,
                ) {
                    if (target.key != activeKey) {
                        FocusRow(
                            modifier = Modifier.focusRequester(optRequester),
                            onClick = {
                                val switched = switchToAccount(activity, target.key)
                                dismiss()
                                if (switched != null) {
                                    TvToast.makeText(
                                        activity,
                                        activity.getString(R.string.tv_accounts_switched_to, switched.username),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    activity.onTvAccountSwitched()
                                }
                            },
                        ) { focused ->
                            Text(
                                text = activity.getString(R.string.tv_accounts_switched_to, target.username),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (focused) TvColors.onFocused else ComposeColor.White,
                            )
                        }
                    }
                    FocusRow(
                        modifier = if (target.key == activeKey) Modifier.focusRequester(optRequester) else Modifier,
                        onClick = {
                            if (target.key != activeKey) {
                                switchToAccount(activity, target.key)
                            }
                            dismiss()
                            onOpenCurrentLoginMethods()
                        },
                    ) { focused ->
                        Text(
                            text = activity.getString(R.string.tv_login_title),
                            fontSize = 17.sp,
                            color = if (focused) TvColors.onFocused else ComposeColor.White,
                        )
                    }
                    FocusRow(
                        onClick = {
                            val wasActive = target.key == activeKey
                            val removed = removeAccount(activity, target.key)
                            accounts = listAccounts(activity)
                            optionsTarget = null
                            if (removed != null) {
                                TvToast.makeText(
                                    activity,
                                    activity.getString(R.string.tv_accounts_removed, removed.username),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            if (wasActive || accounts.isEmpty()) {
                                dismiss()
                                activity.onTvAccountSwitched()
                            }
                        },
                    ) { focused ->
                        Text(
                            text = activity.getString(R.string.tv_accounts_remove, target.username),
                            fontSize = 17.sp,
                            color = if (focused) TvColors.onFocused else ComposeColor(0xFFFF8A80),
                        )
                    }
                }
                return@show
            }

            LaunchedEffect(Unit) {
                runCatching { firstRequester.requestFocus() }
            }

            SidePanel(
                title = activity.getString(R.string.tv_accounts_title),
                subtitle = if (accounts.isNotEmpty()) activity.getString(R.string.tv_accounts_manage_hint) else null,
                scrollable = true,
            ) {
                accounts.forEachIndexed { index, acc ->
                    val isActive = acc.key == activeKey && !guestActive
                    val focusFirst = (isActive) || (activeKey == null && !guestActive && index == 0)
                    var longHandled by remember(acc.key) { mutableStateOf(false) }
                    FocusRow(
                        modifier = (if (focusFirst) Modifier.focusRequester(firstRequester) else Modifier)
                            .onPreviewKeyEvent { event ->
                                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                                if (!isOk) return@onPreviewKeyEvent false
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0) {
                                    if (!longHandled) {
                                        longHandled = true
                                        optionsTarget = acc
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                if (event.type == KeyEventType.KeyUp && longHandled) {
                                    longHandled = false
                                    return@onPreviewKeyEvent true
                                }
                                false
                            },
                        onClick = {
                            if (isActive) {
                                optionsTarget = acc
                            } else {
                                val switched = switchToAccount(activity, acc.key)
                                dismiss()
                                if (switched != null) {
                                    TvToast.makeText(
                                        activity,
                                        activity.getString(R.string.tv_accounts_switched_to, switched.username),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    activity.onTvAccountSwitched()
                                }
                            }
                        },
                    ) { focused ->
                        val textColor = if (focused) TvColors.onFocused else ComposeColor.White
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (!acc.avatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = acc.avatarUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(ComposeColor(0x33FFFFFF)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.baseline_person_black_24),
                                        contentDescription = null,
                                        tint = textColor,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = acc.username,
                                fontSize = 17.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isActive) {
                                Spacer(Modifier.width(10.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(
                                            if (focused) ComposeColor(0x33000000) else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        )
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = activity.getString(R.string.tv_accounts_active),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (focused) TvColors.onFocused else ComposeColor.White,
                                    )
                                }
                            }
                        }
                    }
                }

                // Guest mode (1-click)
                FocusRow(
                    modifier = if (guestActive || (accounts.isEmpty() && activeKey == null)) Modifier.focusRequester(firstRequester) else Modifier,
                    onClick = {
                        if (!guestActive) {
                            switchToGuest(activity)
                            dismiss()
                            TvToast.makeText(activity, R.string.tv_accounts_switched_guest, Toast.LENGTH_SHORT).show()
                            activity.onTvAccountSwitched()
                        } else {
                            dismiss()
                        }
                    },
                ) { focused ->
                    val textColor = if (focused) TvColors.onFocused else ComposeColor.White
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(ComposeColor(0x26FFFFFF)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.baseline_person_black_24),
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = activity.getString(R.string.tv_accounts_guest),
                                fontSize = 17.sp,
                                fontWeight = if (guestActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (guestActive) {
                            Spacer(Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        if (focused) ComposeColor(0x33000000) else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = activity.getString(R.string.tv_accounts_active),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (focused) TvColors.onFocused else ComposeColor.White,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // + Add another Twitch account
                FocusRow(
                    onClick = {
                        dismiss()
                        onAddAnotherAccount()
                    },
                ) { focused ->
                    Text(
                        text = activity.getString(R.string.tv_accounts_add),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (focused) TvColors.onFocused else ComposeColor.White,
                    )
                }
            }
        }
    }
}
