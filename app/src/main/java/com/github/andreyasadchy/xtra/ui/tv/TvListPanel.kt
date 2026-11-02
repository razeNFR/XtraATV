package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem

/**
 * TV: the lists of the settings (tabs, favorites...) in a side panel like the proxies of the player: each row checked or
 * not with a switch, the house of the tab opened first (only on a checked row), moved up or down with the arrows. The
 * rows under another one (shortcuts of the side menu) follow it and are only checked. A locked row (the home) stays on
 * top. Saved when the panel closes.
 */
object TvListPanel {

    private class Item(val source: SettingsDragListItem) {
        var enabled by mutableStateOf(source.enabled)
        var default by mutableStateOf(source.default)
    }

    /** A row and the rows under it, moved together. */
    private class Block(val item: Item, val children: List<Item>)

    fun show(context: Context, title: CharSequence?, list: List<SettingsDragListItem>, showDefault: Boolean, onSave: (List<SettingsDragListItem>) -> Unit) {
        val blocks = mutableStateListOf<Block>()
        var current: Block? = null
        val pendingChildren = mutableListOf<Item>()
        list.forEach { source ->
            val item = Item(source)
            if (source.parent == null) {
                current?.let { blocks.add(Block(it.item, pendingChildren.toList())) }
                pendingChildren.clear()
                current = Block(item, emptyList())
            } else {
                pendingChildren.add(item)
            }
        }
        current?.let { blocks.add(Block(it.item, pendingChildren.toList())) }
        val top = { blocks.map { it.item } }
        // the house on a checked row: if it was on an unchecked one (or nowhere), it goes to the first checked one
        if (showDefault && top().none { it.default && it.enabled }) {
            top().forEach { it.default = false }
            top().firstOrNull { it.enabled }?.default = true
        }
        val setDefault = { target: Item ->
            top().forEach { it.default = it === target }
        }
        val dialog = TvDialogs.show(context) { _ ->
            SidePanel(title = title?.toString() ?: "", scrollable = true) {
                val first = remember { FocusRequester() }
                blocks.forEachIndexed { index, block ->
                    androidx.compose.runtime.key(block.item.source.key) {
                        val item = block.item
                        val locked = item.source.locked
                        // a block cannot go above a locked row (the home stays first)
                        val minIndex = blocks.indexOfFirst { !it.item.source.locked }.coerceAtLeast(0)
                        PanelRow(
                            label = item.source.text,
                            enabled = item.enabled,
                            showSwitch = !locked,
                            house = if (showDefault) (if (item.enabled) item.default else null) else null,
                            canUp = !locked && index > minIndex,
                            canDown = !locked && index < blocks.lastIndex,
                            showArrows = !locked,
                            indent = false,
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                            onToggle = {
                                if (!locked) {
                                    item.enabled = !item.enabled
                                    if (showDefault) {
                                        if (!item.enabled && item.default) {
                                            // the tab opened first was unchecked: the house goes to the first checked tab
                                            item.default = false
                                            top().firstOrNull { it.enabled }?.default = true
                                        } else if (item.enabled && top().none { it.default && it.enabled }) {
                                            setDefault(item)
                                        }
                                    }
                                }
                            },
                            onHouse = { setDefault(item) },
                            onMove = { step ->
                                val target = index + step
                                if (target in minIndex..blocks.lastIndex) {
                                    val moved = blocks.removeAt(index)
                                    blocks.add(target, moved)
                                }
                            },
                        )
                        block.children.forEach { child ->
                            PanelRow(
                                label = child.source.text,
                                enabled = child.enabled,
                                showSwitch = true,
                                house = null,
                                canUp = false,
                                canDown = false,
                                showArrows = false,
                                indent = true,
                                modifier = Modifier,
                                onToggle = { child.enabled = !child.enabled },
                                onHouse = {},
                                onMove = {},
                            )
                        }
                    }
                }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            }
        }
        dialog.setOnDismissListener {
            val saved = blocks.flatMap { block ->
                listOf(block.item) + block.children
            }.map { item ->
                item.source.apply {
                    enabled = item.enabled
                    default = item.default
                }
            }
            onSave(saved)
        }
    }

    @Composable
    private fun PanelRow(
        label: String,
        enabled: Boolean,
        showSwitch: Boolean,
        /** null: no house on this row; true / false: the house, filled when it is the tab opened first. */
        house: Boolean?,
        canUp: Boolean,
        canDown: Boolean,
        showArrows: Boolean,
        indent: Boolean,
        modifier: Modifier,
        onToggle: () -> Unit,
        onHouse: () -> Unit,
        onMove: (Int) -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (indent) 28.dp else 0.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(modifier.weight(1f), onToggle) { focused ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        fontSize = if (indent) 14.sp else 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (focused) TvColors.onFocused else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (showSwitch) {
                        Spacer(Modifier.size(10.dp))
                        Switch(enabled)
                    }
                }
            }
            if (house != null) {
                Pill(Modifier, onHouse) { focused ->
                    Icon(
                        painterResource(if (house) R.drawable.baseline_home_black_24 else R.drawable.outline_home_black_24),
                        contentDescription = null,
                        tint = if (focused) TvColors.onFocused else if (house) TvColors.accent else Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else if (!indent && showArrows) {
                // keeps the arrows of the rows in line when a row has no house (unchecked)
                Spacer(Modifier.size(48.dp))
            }
            if (showArrows) {
                Pill(Modifier.alpha(if (canUp) 1f else 0.35f), { if (canUp) onMove(-1) }) { focused ->
                    Icon(painterResource(R.drawable.tv_arrow_up), contentDescription = null, tint = if (focused) TvColors.onFocused else Color.White, modifier = Modifier.size(20.dp))
                }
                Pill(Modifier.alpha(if (canDown) 1f else 0.35f), { if (canDown) onMove(1) }) { focused ->
                    Icon(painterResource(R.drawable.tv_arrow_down), contentDescription = null, tint = if (focused) TvColors.onFocused else Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    @Composable
    fun Switch(on: Boolean) {
        Box(
            Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (on) TvColors.accent else Color(0x55FFFFFF)),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(3.dp).size(16.dp).clip(RoundedCornerShape(8.dp)).background(Color.White))
        }
    }

    @Composable
    fun Pill(modifier: Modifier, onClick: () -> Unit, content: @Composable (focused: Boolean) -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Box(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) TvColors.focused else TvColors.item)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            content(focused)
        }
    }
}
