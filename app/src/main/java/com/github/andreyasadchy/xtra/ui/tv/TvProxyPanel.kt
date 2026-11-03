package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogCustomProxyEditBinding
import com.github.andreyasadchy.xtra.model.ui.CustomProxy
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * TV: the custom proxies in a side panel: each one turned on or off, moved up or down with the arrows, with its status
 * (online / offline, like the screen of the settings). From the player (OK held on its button) or from the settings,
 * where they can also be added, edited and deleted ([Manage]). The changes of order are saved when the panel closes.
 */
object TvProxyPanel {

    private class Entry(val proxy: CustomProxy) {
        var enabled by mutableStateOf(proxy.enabled)
        var url by mutableStateOf(proxy.url)
        var addQueryParams = proxy.addQueryParams
    }

    /** From the settings: a proxy added, edited or deleted is saved at once in [repository]. */
    class Manage(val scope: CoroutineScope, val repository: PlayerRepository)

    /**
     * [onClosed]: the panel closed, with the list in its new order when something changed (null when nothing did).
     * [openSettings]: the full screen of the settings of the custom proxies.
     */
    fun show(
        context: Context,
        proxies: List<CustomProxy>,
        openSettings: () -> Unit,
        manage: Manage? = null,
        onClosed: (List<CustomProxy>?) -> Unit,
    ) {
        val entries = mutableStateListOf<Entry>().apply { addAll(proxies.sortedBy { it.position }.map { Entry(it) }) }
        val original = proxies.sortedBy { it.position }.map { it.id to it.enabled }
        val modeBefore = context.prefs().getString(C.PLAYER_CUSTOM_PROXY_MODE, "0")
        val dialog = TvDialogs.show(context) { dismiss ->
            SidePanel(title = context.getString(R.string.tv_proxy_panel_title), subtitle = context.getString(R.string.tv_proxy_panel_subtitle), width = if (manage != null) 500.dp else 440.dp, scrollable = true) {
                val first = remember { FocusRequester() }
                // settings: the custom proxies on or off, first (one row for both on the screen of the settings)
                if (manage != null) {
                    var useProxies by remember { mutableStateOf(context.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, true)) }
                    Pill(Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRequester(first), {
                        useProxies = !useProxies
                        context.prefs().edit { putBoolean(C.PLAYER_USE_CUSTOM_PROXY, useProxies) }
                    }) { focused ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                context.getString(R.string.use_custom_proxies),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (focused) TvColors.onFocused else Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            TvListPanel.Switch(useProxies)
                        }
                    }
                }
                // the proxies for the whole stream, or only while an ad plays (the stream itself from Twitch)
                SelectorRow(
                    context.getString(R.string.tv_proxy_mode),
                    listOf(context.getString(R.string.tv_proxy_mode_always), context.getString(R.string.tv_proxy_mode_ads)),
                    if (context.prefs().getString(C.PLAYER_CUSTOM_PROXY_MODE, "0") == "1") 1 else 0,
                ) {
                    context.prefs().edit { putString(C.PLAYER_CUSTOM_PROXY_MODE, it.toString()) }
                }
                Spacer(Modifier.height(8.dp))
                entries.forEachIndexed { index, entry ->
                    androidx.compose.runtime.key(entry) {
                        // the status, checked again when the url changes
                        val online by produceState<Boolean?>(null, entry.url) {
                            value = null
                            value = TvProxyStatus.check(context, entry.url)
                        }
                        ProxyRow(
                            label = entry.url?.let { shortUrl(it) } ?: "",
                            online = online,
                            enabled = entry.enabled,
                            canUp = index > 0,
                            canDown = index < entries.lastIndex,
                            modifier = if (index == 0 && manage == null) Modifier.focusRequester(first) else Modifier,
                            onToggle = {
                                entry.enabled = !entry.enabled
                                if (manage != null) {
                                    entry.proxy.enabled = entry.enabled
                                    manage.scope.launch { manage.repository.updateCustomProxy(entry.proxy) }
                                }
                            },
                            onMove = { step ->
                                val target = index + step
                                if (target in entries.indices) {
                                    val moved = entries.removeAt(index)
                                    entries.add(target, moved)
                                }
                            },
                            onEdit = if (manage != null) {
                                {
                                    showEdit(context, entry.url, entry.addQueryParams, onSave = { url, addQueryParams ->
                                        TvProxyStatus.forget(entry.url)
                                        TvProxyStatus.forget(url)
                                        entry.proxy.url = url
                                        entry.proxy.addQueryParams = addQueryParams
                                        entry.addQueryParams = addQueryParams
                                        entry.url = url
                                        manage.scope.launch { manage.repository.updateCustomProxy(entry.proxy) }
                                    }, onDelete = {
                                        entries.remove(entry)
                                        manage.scope.launch { manage.repository.deleteCustomProxy(entry.proxy) }
                                    })
                                }
                            } else null,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (manage != null) {
                    // no proxy yet: the focus on "Add"
                    ButtonRow(context.getString(R.string.tv_proxy_add)) {
                        showEdit(context, null, true, onSave = { url, addQueryParams ->
                            if (!url.isNullOrBlank()) {
                                val item = CustomProxy(url = url, addQueryParams = addQueryParams, position = entries.size)
                                TvProxyStatus.forget(url)
                                manage.scope.launch {
                                    item.id = manage.repository.saveCustomProxy(item).toInt()
                                    entries.add(Entry(item))
                                }
                            }
                        }, onDelete = null)
                    }
                }
                ButtonRow(context.getString(if (manage != null) R.string.tv_proxy_full_screen else R.string.tv_proxy_all_settings)) {
                    dismiss()
                    openSettings()
                }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            }
        }
        dialog.setOnDismissListener {
            val now = entries.map { it.proxy.id to it.enabled }
            // the mode changed: as a change of the list (the stream starts again with it)
            if (now == original && context.prefs().getString(C.PLAYER_CUSTOM_PROXY_MODE, "0") == modeBefore) {
                onClosed(null)
            } else {
                onClosed(entries.mapIndexed { index, entry ->
                    entry.proxy.apply {
                        position = index
                        enabled = entry.enabled
                    }
                })
            }
        }
    }

    /** The url of a proxy to add or edit (and delete it, for an edited one), like the screen of the settings. */
    private fun showEdit(context: Context, url: String?, addQueryParams: Boolean, onSave: (String?, Boolean) -> Unit, onDelete: (() -> Unit)?) {
        val binding = DialogCustomProxyEditBinding.inflate(LayoutInflater.from(context))
        binding.editText.editText?.setText(url ?: "")
        binding.checkBox.isChecked = addQueryParams
        val builder = context.getAlertDialogBuilder()
            .setView(binding.root)
            .setPositiveButton(context.getString(android.R.string.ok)) { _, _ ->
                onSave(binding.editText.editText?.text?.toString()?.trim(), binding.checkBox.isChecked)
            }
            .setNegativeButton(context.getString(android.R.string.cancel), null)
        if (onDelete != null) {
            builder.setNeutralButton(context.getString(R.string.delete)) { _, _ ->
                val delete = context.getString(R.string.delete)
                context.getAlertDialogBuilder()
                    .setTitle(delete)
                    .setMessage(context.getString(R.string.delete_proxy_message))
                    .setPositiveButton(delete) { _, _ -> onDelete() }
                    .setNegativeButton(context.getString(android.R.string.cancel), null)
                    .show()
            }
        }
        builder.show()
    }

    /** "https://eu.luminous.dev/live/$channel" -> "eu.luminous.dev". */
    private fun shortUrl(url: String): String {
        return (url.toUri().takeIf { it.host != null } ?: "https://$url".toUri()).host ?: url
    }

    @Composable
    private fun ProxyRow(
        label: String,
        online: Boolean?,
        enabled: Boolean,
        canUp: Boolean,
        canDown: Boolean,
        modifier: Modifier,
        onToggle: () -> Unit,
        onMove: (Int) -> Unit,
        onEdit: (() -> Unit)?,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(modifier.weight(1f), onToggle) { focused ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (focused) TvColors.onFocused else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // the status: green online, red offline, grey while it is checked
                        val statusColor = when (online) {
                            true -> colorResource(R.color.online)
                            false -> colorResource(R.color.offline)
                            null -> Color(0x99FFFFFF)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                            Spacer(Modifier.size(6.dp))
                            Text(
                                when (online) {
                                    true -> androidx.compose.ui.res.stringResource(R.string.online)
                                    false -> androidx.compose.ui.res.stringResource(R.string.offline)
                                    null -> androidx.compose.ui.res.stringResource(R.string.loading)
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (focused && online == null) TvColors.onFocused.copy(alpha = 0.7f) else statusColor,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    // switch: the color of accent when on
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (enabled) TvColors.accent else Color(0x55FFFFFF)),
                        contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Box(Modifier.padding(3.dp).size(16.dp).clip(RoundedCornerShape(8.dp)).background(Color.White))
                    }
                }
            }
            if (onEdit != null) {
                Pill(Modifier, onEdit) { focused ->
                    Icon(painterResource(R.drawable.tv_ic_edit), contentDescription = null, tint = if (focused) TvColors.onFocused else Color.White, modifier = Modifier.size(20.dp))
                }
            }
            Pill(Modifier, { if (canUp) onMove(-1) }, alpha = if (canUp) 1f else 0.35f) { focused ->
                Icon(painterResource(R.drawable.tv_arrow_up), contentDescription = null, tint = if (focused) TvColors.onFocused else Color.White, modifier = Modifier.size(20.dp))
            }
            Pill(Modifier, { if (canDown) onMove(1) }, alpha = if (canDown) 1f else 0.35f) { focused ->
                Icon(painterResource(R.drawable.tv_arrow_down), contentDescription = null, tint = if (focused) TvColors.onFocused else Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }

    @Composable
    private fun Pill(modifier: Modifier, onClick: () -> Unit, alpha: Float = 1f, content: @Composable (focused: Boolean) -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Box(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) TvColors.focused else TvColors.item.copy(alpha = TvColors.item.alpha * alpha))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            content(focused)
        }
    }
}
