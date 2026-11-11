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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.wrapContentWidth
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
    /** The entry of the account, shown at the top of the menu with the picture and the name of the account logged in. */
    var accountId by mutableIntStateOf(0)
    var accountName by mutableStateOf<String?>(null)
    var accountImage by mutableStateOf<String?>(null)
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
 * Leanback side menu: the account at the top, search, the sections, then the settings.
 * It scrolls with the focus, up and down stop at its ends, right goes back to the screen (handled by the activity).
 * With the icon rail of the Xtra TV settings ([TvMenuState.collapsed]) it folds into a rail of icons: the same entries,
 * their icons stay where they are and their names slide out when it opens.
 */
@Composable
fun TvSideMenu(state: TvMenuState, onClick: (Int) -> Unit) {
    val collapsed = state.collapsed
    // 0: the rail of icons, 1: the menu open
    val open by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (collapsed) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "menuOpen"
    )
    // as wide as its longest name needs, no more (the names differ with the language and the shortcuts shown)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fullWidth = remember(state.items, state.accountName, density) {
        fun width(text: String, size: Int) = with(density) {
            measurer.measure(text, androidx.compose.ui.text.TextStyle(fontSize = size.sp, fontWeight = FontWeight.SemiBold), maxLines = 1).size.width.toDp()
        }
        var widest = 0.dp
        state.items.forEach { item ->
            val needed = if (item.id == state.accountId) {
                ENTRY_HEIGHT + 10.dp + maxOf(width(state.accountName ?: item.label, 17), if (state.accountName != null) width(item.label, 12) else 0.dp) + 14.dp
            } else {
                (if (item.sub) 30.dp else 0.dp) + ENTRY_HEIGHT + 8.dp + width(item.label, if (item.sub) 15 else 17) + 16.dp
            }
            if (needed > widest) widest = needed
        }
        (widest + MENU_PADDING * 2).coerceIn(176.dp, 300.dp)
    }
    val menuWidth = RAIL_WIDTH + (fullWidth - RAIL_WIDTH) * open
    // the entry that has the focus, by its requester: its place among the entries is looked up when a key comes. A place
    // kept as a number went wrong when the menu opened with a stream kept playing: the focus came before the "now
    // playing" card was put in, the number was 2 short and up / down jumped over entries
    var focusedRequester by remember { mutableStateOf<FocusRequester?>(null) }
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
    // the account first (its picture and its name at the top), then the entries in their order; the shortcuts under an
    // entry only in the menu open
    val account = state.items.find { it.id == state.accountId }
    val items = listOfNotNull(account) + state.items.filter { it !== account && (!collapsed || !it.sub) }
    val playing = state.nowPlaying
    val nowPlayingAt = items.indexOfFirst { it !== account && it.group != TvMenuState.GROUP_TOP }.coerceAtLeast(0)
    val nowPlayingOpenRequester = remember { FocusRequester() }
    val nowPlayingCloseRequester = remember { FocusRequester() }
    val slots = buildList {
        items.forEachIndexed { index, item ->
            if (index == nowPlayingAt && playing != null && !collapsed) {
                add(nowPlayingOpenRequester)
                add(nowPlayingCloseRequester)
            }
            add(state.requesters.getOrPut(item.id) { FocusRequester() })
        }
    }
    // the entry of the section shown, for a shortcut hidden in the rail: its section
    val activeId = if (collapsed) {
        state.items.find { it.id == state.selected }?.let { chosen ->
            if (chosen.sub) state.items.take(state.items.indexOf(chosen)).lastOrNull { !it.sub }?.id else chosen.id
        } ?: state.selected
    } else state.selected
    val accent = androidx.tv.material3.MaterialTheme.colorScheme.primary
    val hasAmbient = TvAccent.current != null
    val ambient = if (hasAmbient) Color(TvAccent.color) else accent
    // 100% transparent as an icon rail (open == 0f) so the ambiance glow passes directly under the icons;
    // tinted with the ambiance color when unfolded so labels stay readable over the content
    val top = androidx.compose.ui.graphics.lerp(Color(0xFF15141B), ambient, if (hasAmbient) 0.32f else 0.15f)
    val bottom = androidx.compose.ui.graphics.lerp(Color(0xFF100F15), ambient, if (hasAmbient) 0.14f else 0.05f)
    val alpha = 0.92f * open
    Box(
        Modifier
            .width(menuWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(top.copy(alpha = alpha), bottom.copy(alpha = alpha))))
    ) {
        Column(
            Modifier
                // laid out at the width of the open menu whatever the width shown: nothing moves nor wraps while it opens
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .width(fullWidth)
                .fillMaxHeight()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            val slot = slots.indexOf(focusedRequester)
                            if (slot > 0) {
                                runCatching { slots[slot - 1].requestFocus() }
                            }
                            true
                        }
                        Key.DirectionDown -> {
                            val slot = slots.indexOf(focusedRequester)
                            if (slot in 0 until slots.lastIndex) {
                                runCatching { slots[slot + 1].requestFocus() }
                            }
                            true
                        }
                        Key.DirectionLeft -> true
                        else -> false
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MENU_PADDING, vertical = 24.dp)
        ) {
            items.forEachIndexed { index, item ->
                if (index == nowPlayingAt && playing != null) {
                    Spacer(Modifier.height(10.dp))
                    if (collapsed) {
                        // the stream kept playing, as a red dot of the rail
                        Box(
                            Modifier
                                .size(ENTRY_HEIGHT)
                                .clip(RoundedCornerShape(14.dp))
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
                    } else {
                        NowPlayingCard(
                            playing = playing,
                            state = state,
                            openRequester = nowPlayingOpenRequester,
                            closeRequester = nowPlayingCloseRequester,
                            onOpenFocused = { focusedRequester = nowPlayingOpenRequester },
                            onCloseFocused = { focusedRequester = nowPlayingCloseRequester },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                // air between the groups (account, search, sections, settings), no line
                if (index > 0 && !item.sub) {
                    val previous = items[index - 1]
                    val newGroup = previous === account || previous.group != item.group
                    Spacer(Modifier.height(if (newGroup) 18.dp else 4.dp))
                }
                val requester = state.requesters.getOrPut(item.id) { FocusRequester() }
                val entryModifier = Modifier
                    .focusRequester(requester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedRequester = requester
                            state.focusedId = item.id
                            state.onFocusItem(item.id)
                        }
                    }
                if (item === account) {
                    AccountEntry(item, state, open, fullWidth, entryModifier) { onClick(item.id) }
                } else {
                    MenuEntry(item, item.id == activeId, open, fullWidth, entryModifier) { onClick(item.id) }
                }
            }
        }
    }
}

private val RAIL_WIDTH = 68.dp
private val MENU_PADDING = 12.dp
/** Height of an entry, and width of the square that holds its icon (the entry itself in the rail). */
private val ENTRY_HEIGHT = 44.dp

/** The account at the top of the menu: its picture and its name, the entry that opens the account screen. */
@Composable
private fun AccountEntry(item: TvMenuItem, state: TvMenuState, open: Float, menuWidth: androidx.compose.ui.unit.Dp, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val name = state.accountName
    val image = state.accountImage
    val content = if (focused) TvColors.onFocused else Color.White
    val full = menuWidth - MENU_PADDING * 2
    Row(
        modifier
            .width(ENTRY_HEIGHT + (full - ENTRY_HEIGHT) * open)
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) TvColors.focused else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(ENTRY_HEIGHT), contentAlignment = Alignment.Center) {
            if (name != null && image != null) {
                coil3.compose.AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                )
            } else {
                Box(
                    Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (focused) Color(0x1F000000) else Color(0x26FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.icon != null) {
                        Icon(painterResource(item.icon), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Column(Modifier.padding(start = 10.dp, end = 12.dp).graphicsLayer { alpha = open }) {
            Text(
                name ?: item.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            if (name != null) {
                Text(
                    item.label,
                    fontSize = 12.sp,
                    color = if (focused) TvColors.onFocused else TvColors.secondaryText,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
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


/**
 * An entry. [open] from 0 (the rail: only its icon, in a square) to 1 (the menu: its icon at the same place, its name
 * next to it). The section shown: a pill of the accent color with a bar at its left, its icon in the accent color.
 */
@Composable
private fun MenuEntry(item: TvMenuItem, selected: Boolean, open: Float, menuWidth: androidx.compose.ui.unit.Dp, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = androidx.tv.material3.MaterialTheme.colorScheme.primary
    val background = when {
        focused -> TvColors.focused
        selected -> accent.copy(alpha = 0.22f)
        else -> Color.Transparent
    }
    val content = when {
        focused -> TvColors.onFocused
        selected -> Color.White
        else -> Color(0xB8FFFFFF)
    }
    val iconTint = if (selected && !focused) accent else content
    val indent = if (item.sub) 30.dp else 0.dp
    val height = if (item.sub) 38.dp else ENTRY_HEIGHT
    val full = menuWidth - MENU_PADDING * 2 - indent
    Row(
        modifier
            .padding(start = indent)
            .width(ENTRY_HEIGHT + (full - ENTRY_HEIGHT) * open)
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = ENTRY_HEIGHT, height = height), contentAlignment = Alignment.Center) {
            if (selected && !focused) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(4.dp)
                        .height(if (item.sub) 16.dp else 22.dp)
                        .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                        .background(accent)
                )
            }
            if (item.icon != null) {
                Icon(painterResource(item.icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(if (item.sub) 18.dp else 24.dp))
            }
        }
        Text(
            item.label,
            fontSize = if (item.sub) 15.sp else 17.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, end = 12.dp).graphicsLayer { alpha = open }
        )
    }
}
