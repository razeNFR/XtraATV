package com.github.andreyasadchy.xtra.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

/** [sub]: an entry of the section above it (the tabs of the followed channels under "Following"), smaller and moved in, named [label]. */
class TvMenuItem(val id: Int, val title: String, val icon: Int?, val group: Int, val sub: Boolean = false, val label: String = title)

/** State of the side menu, the activity fills it from the menu of the navigation bar. */
/** Stream playing in the mini player or as audio only, shown at the top of the side menu. */
class TvNowPlaying(val channelName: String?, val title: String?, val image: String?, val audioOnly: Boolean)

class TvMenuState {
    var items by mutableStateOf<List<TvMenuItem>>(emptyList())
    var nowPlaying by mutableStateOf<TvNowPlaying?>(null)
    var onNowPlayingClick: () -> Unit = {}
    var onNowPlayingClose: () -> Unit = {}
    var selected by mutableIntStateOf(0)
    var focusedId by mutableIntStateOf(0)
    var collapsed by mutableStateOf(false)
    internal val requesters = mutableMapOf<Int, FocusRequester>()
    /** An entry got the focus (to show its page without OK, an option of the Xtra TV settings). */
    var onFocusItem: (Int) -> Unit = {}

    /**
     * The focus is wanted in the menu while its entries are not there yet (the icon rail is unfolding): they take it
     * as soon as they are shown. Without it the first "left" opened the menu and a second one was needed to enter it.
     */
    var pendingFocus by mutableStateOf(false)

    /** Moves the focus to the screen being shown, else to the first entry. False when no entry could take it. */
    fun focusSelected(): Boolean {
        if (collapsed) return false
        val requester = requesters[selected] ?: items.firstOrNull()?.let { requesters[it.id] } ?: return false
        return runCatching { requester.requestFocus() }.getOrDefault(false)
    }

    /** Moves the focus to the entry currently hovered in the menu, else to the selected one. */
    fun focusCurrentOrSelected(): Boolean {
        val targetId = if (focusedId != 0 && requesters.containsKey(focusedId)) focusedId else selected
        val requester = requesters[targetId] ?: requesters[selected] ?: items.firstOrNull()?.let { requesters[it.id] } ?: return false
        return runCatching { requester.requestFocus() }.getOrDefault(false)
    }

    companion object {
        const val GROUP_TOP = 0
        const val GROUP_MAIN = 1
        const val GROUP_BOTTOM = 2
    }
}

/**
 * Leanback side menu: search at the top, the sections, then account and settings.
 * It scrolls with the focus, up and down stop at its ends, right goes back to the screen (handled by the activity).
 * When [TvMenuState.collapsed] is true, folds into a clean 68dp icon rail instead of disappearing completely.
 */
@Composable
fun TvSideMenu(state: TvMenuState, onClick: (Int) -> Unit) {
    val collapsed = state.collapsed
    val menuWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (collapsed) 68.dp else 260.dp,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "railWidth"
    )
    var focusedSlot by remember { mutableIntStateOf(-1) }
    if (state.pendingFocus && !collapsed) {
        // the entries were just put back (rail unfolded): the focus as soon as one of them can take it
        androidx.compose.runtime.LaunchedEffect(Unit) {
            repeat(20) {
                androidx.compose.runtime.withFrameNanos { }
                if (!state.pendingFocus || state.focusSelected()) {
                    state.pendingFocus = false
                    return@LaunchedEffect
                }
            }
            state.pendingFocus = false
        }
    }
    val items = state.items
    val playing = state.nowPlaying
    val nowPlayingAt = items.indexOfFirst { it.group != TvMenuState.GROUP_TOP }.coerceAtLeast(0)
    val nowPlayingOpenRequester = remember { FocusRequester() }
    val nowPlayingCloseRequester = remember { FocusRequester() }
    val slots = buildList {
        items.forEachIndexed { index, item ->
            if (index == nowPlayingAt && playing != null) {
                add(nowPlayingOpenRequester)
                add(nowPlayingCloseRequester)
            }
            add(state.requesters.getOrPut(item.id) { FocusRequester() })
        }
    }
    Column(
        Modifier
            .width(menuWidth)
            .fillMaxHeight()
            // opaque: the texts of the screen behind no longer show through the entries
            .background(if (collapsed) Color(0xD9141318) else Color(0xFF141318))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        if (focusedSlot > 0 && focusedSlot - 1 < slots.size) {
                            runCatching { slots[focusedSlot - 1].requestFocus() }
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        if (focusedSlot in 0 until slots.lastIndex) {
                            runCatching { slots[focusedSlot + 1].requestFocus() }
                        }
                        true
                    }
                    Key.DirectionLeft -> true
                    else -> false
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (collapsed) 10.dp else 16.dp, vertical = 28.dp),
        horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start
    ) {
        if (collapsed) {
            val activeParentId = items.find { it.id == state.selected }?.let { chosen ->
                if (chosen.sub) items.take(items.indexOf(chosen)).lastOrNull { !it.sub }?.id else chosen.id
            } ?: state.selected
            items.filter { !it.sub }.forEachIndexed { index, item ->
                if (index == 1 && playing != null) {
                    Box(
                        Modifier
                            .padding(vertical = 4.dp)
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x2EE91916)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(
                                if (playing.audioOnly) com.github.andreyasadchy.xtra.R.drawable.baseline_audiotrack_black_24
                                else com.github.andreyasadchy.xtra.R.drawable.tv_ic_live
                            ),
                            contentDescription = null,
                            tint = Color(0xFFE91916),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                if (index > 0 && items.filter { !it.sub }[index - 1].group != item.group) {
                    Box(
                        Modifier
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x22FFFFFF))
                    )
                }
                CollapsedRailEntry(item = item, selected = item.id == activeParentId)
            }
            return@Column
        }
        // "Now playing" between search and the sections
        var currentSlot = 0
        items.forEachIndexed { index, item ->
            if (index == nowPlayingAt && playing != null) {
                if (index > 0) {
                    Box(
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x26FFFFFF))
                    )
                }
                val openSlot = currentSlot++
                val closeSlot = currentSlot++
                NowPlayingCard(
                    playing = playing,
                    state = state,
                    openRequester = nowPlayingOpenRequester,
                    closeRequester = nowPlayingCloseRequester,
                    onOpenFocused = { focusedSlot = openSlot },
                    onCloseFocused = { focusedSlot = closeSlot },
                )
            }
            if (index > 0 && items[index - 1].group != item.group) {
                Box(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0x26FFFFFF))
                )
            }
            val itemSlot = currentSlot++
            val requester = state.requesters.getOrPut(item.id) { FocusRequester() }
            MenuEntry(
                item = item,
                selected = item.id == state.selected,
                modifier = Modifier
                    .focusRequester(requester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedSlot = itemSlot
                            state.focusedId = item.id
                            state.onFocusItem(item.id)
                        }
                    },
            ) {
                onClick(item.id)
            }
        }
    }
}

@Composable
private fun CollapsedRailEntry(item: TvMenuItem, selected: Boolean) {
    val accent = androidx.tv.material3.MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .padding(vertical = 3.dp)
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.26f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
        if (item.icon != null) {
            Icon(
                painterResource(item.icon),
                contentDescription = null,
                tint = if (selected) accent else Color(0x99FFFFFF),
                modifier = Modifier.size(23.dp)
            )
        }
    }
}

/** "Now playing": OK goes back to the stream, the button under it stops it. */
@Composable
private fun NowPlayingCard(
    playing: TvNowPlaying,
    state: TvMenuState,
    openRequester: FocusRequester,
    closeRequester: FocusRequester,
    onOpenFocused: () -> Unit,
    onCloseFocused: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        NowPlayingContent(playing, state, openRequester, onOpenFocused)
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Row(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (focused) TvColors.focused else Color(0x1AFFFFFF))
                .focusRequester(closeRequester)
                .onFocusChanged { if (it.isFocused) onCloseFocused() }
                .clickable(interactionSource = interaction, indication = null) { state.onNowPlayingClose() }
                .padding(vertical = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                androidx.compose.ui.platform.LocalContext.current.getString(com.github.andreyasadchy.xtra.R.string.tv_close_playback),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) TvColors.onFocused else Color.White,
            )
        }
    }
}

@Composable
private fun NowPlayingContent(
    playing: TvNowPlaying,
    state: TvMenuState,
    requester: FocusRequester,
    onFocused: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) TvColors.focused else Color(0x1AFFFFFF))
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clickable(interactionSource = interaction, indication = null) { state.onNowPlayingClick() }
            .padding(10.dp)
    ) {
        val content = if (focused) TvColors.onFocused else Color.White
        Text(
            androidx.compose.ui.platform.LocalContext.current.getString(com.github.andreyasadchy.xtra.R.string.tv_now_playing),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) TvColors.onFocused else Color(0xFFE91916),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF26252C)),
            contentAlignment = Alignment.Center
        ) {
            coil3.compose.AsyncImage(
                model = playing.image,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (playing.audioOnly) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(com.github.andreyasadchy.xtra.R.drawable.baseline_audiotrack_black_24), contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(playing.channelName ?: "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!playing.title.isNullOrBlank()) {
            Text(playing.title, fontSize = 12.sp, color = if (focused) TvColors.onFocused else TvColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MenuEntry(item: TvMenuItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // the section shown: a pill of the accent color, its icon in the accent color
    val accent = androidx.tv.material3.MaterialTheme.colorScheme.primary
    val background = when {
        focused -> TvColors.focused
        selected -> accent.copy(alpha = 0.26f)
        else -> Color.Transparent
    }
    val content = when {
        focused -> TvColors.onFocused
        selected -> Color.White
        else -> Color(0xB3FFFFFF)
    }
    val iconTint = if (selected && !focused) accent else content
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = if (item.sub) 28.dp else 0.dp, top = if (item.sub) 1.dp else 3.dp, bottom = if (item.sub) 1.dp else 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (item.sub) 12.dp else 16.dp, vertical = if (item.sub) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.icon != null) {
            Icon(painterResource(item.icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(if (item.sub) 18.dp else 24.dp))
            Spacer(Modifier.width(if (item.sub) 12.dp else 16.dp))
        }
        Text(
            item.label,
            fontSize = if (item.sub) 15.sp else 18.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
