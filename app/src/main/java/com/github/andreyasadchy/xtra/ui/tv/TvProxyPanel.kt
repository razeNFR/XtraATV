package com.github.andreyasadchy.xtra.ui.tv

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.CustomProxy

/**
 * TV: the custom proxies from the player (OK held on its button): each one turned on or off, moved up or down with the
 * arrows like in the settings. The same list as the settings: the changes are saved there when the panel closes.
 */
object TvProxyPanel {

    private class Entry(val proxy: CustomProxy, enabled: Boolean) {
        var enabled by androidx.compose.runtime.mutableStateOf(enabled)
    }

    /**
     * [onClosed]: the panel closed, with the list in its new order when something changed (null when nothing did).
     * [openSettings]: the screen of the settings of the custom proxies (to add, remove or edit one).
     */
    fun show(
        context: android.content.Context,
        proxies: List<CustomProxy>,
        openSettings: () -> Unit,
        onClosed: (List<CustomProxy>?) -> Unit,
    ) {
        val entries = mutableStateListOf<Entry>().apply { addAll(proxies.sortedBy { it.position }.map { Entry(it, it.enabled) }) }
        val original = proxies.sortedBy { it.position }.map { it.id to it.enabled }
        val dialog = TvDialogs.show(context) { dismiss ->
            SidePanel(title = context.getString(R.string.tv_proxy_panel_title), subtitle = context.getString(R.string.tv_proxy_panel_subtitle), scrollable = true) {
                val first = remember { FocusRequester() }
                entries.forEachIndexed { index, entry ->
                    androidx.compose.runtime.key(entry.proxy.id) {
                        ProxyRow(
                            label = entry.proxy.url?.let { shortUrl(it) } ?: "",
                            enabled = entry.enabled,
                            canUp = index > 0,
                            canDown = index < entries.lastIndex,
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                            onToggle = { entry.enabled = !entry.enabled },
                            onMove = { step ->
                                val target = index + step
                                if (target in entries.indices) {
                                    val moved = entries.removeAt(index)
                                    entries.add(target, moved)
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                ButtonRow(context.getString(R.string.tv_proxy_all_settings)) {
                    dismiss()
                    openSettings()
                }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            }
        }
        dialog.setOnDismissListener {
            val now = entries.map { it.proxy.id to it.enabled }
            if (now == original) {
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

    /** "https://eu.luminous.dev/live/$channel" -> "eu.luminous.dev". */
    private fun shortUrl(url: String): String {
        return (url.toUri().takeIf { it.host != null } ?: "https://$url".toUri()).host ?: url
    }

    @Composable
    private fun ProxyRow(
        label: String,
        enabled: Boolean,
        canUp: Boolean,
        canDown: Boolean,
        modifier: Modifier,
        onToggle: () -> Unit,
        onMove: (Int) -> Unit,
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
