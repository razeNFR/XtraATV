package com.github.andreyasadchy.xtra.ui.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
     * The focus is wanted in the menu while its entries are not there yet (the icon rail is unfolding) or while the
     * AndroidComposeView is acquiring View focus from a Compose page: they take it cleanly on the next frame.
     */
    var pendingFocus by mutableStateOf(false)
    var focusRequestToken by mutableIntStateOf(0)
    var suppressFocusCallback = false
    var isNowPlayingOpenFocused by mutableStateOf(false)
    internal var nowPlayingCloseFocusRequester: FocusRequester? = null

    fun focusNowPlayingClose(): Boolean {
        val req = nowPlayingCloseFocusRequester ?: return false
        return runCatching { req.requestFocus() }.getOrDefault(false)
    }

    fun requestFocusSelected() {
        focusedId = 0
        isNowPlayingOpenFocused = false
        suppressFocusCallback = true
        pendingFocus = true
        focusRequestToken++
    }

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

private data class MenuItemBounds(
    val topY: Int,
    val heightPx: Int,
    val collapsedLayout: Boolean,
)

private data class MenuPillBounds(
    val topY: Float,
    val heightPx: Float,
    val startXPx: Float,
    val cornerPx: Float,
    val collapsedLayout: Boolean,
)

private class LastPillBoundsHolder(var value: MenuPillBounds? = null)

/**
 * Leanback side menu: the account at the top, search, the sections, then the settings.
 * It scrolls with the focus, up and down stop at its ends, right goes back to the screen (handled by the activity).
 * With the icon rail of the Xtra TV settings ([TvMenuState.collapsed]) it folds into a rail of icons: the same entries,
 * their icons stay where they are and their names slide out when it opens.
 */
@Composable
fun TvSideMenu(state: TvMenuState, onClick: (Int) -> Unit) {
    val collapsed = state.collapsed
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    val itemBounds = remember { mutableMapOf<Int, MenuItemBounds>() }
    val slotBounds = remember { mutableMapOf<FocusRequester, Pair<Int, Int>>() }
    val entryPillBounds = remember { mutableStateMapOf<Int, MenuPillBounds>() }
    // 0: the rail of icons, 1: the menu open — read strictly inside Layout/Draw/graphicsLayer lambdas to avoid per-frame recomposition
    val openState = animateFloatAsState(
        targetValue = if (collapsed) 0f else 1f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "menuOpen"
    )
    val settledCollapsed by remember { derivedStateOf { collapsed && openState.value <= 0.001f } }
    val openProvider: () -> Float = { openState.value }
    // as wide as its longest name needs, no more (the names differ with the language and the shortcuts shown)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val baseStyle = androidx.tv.material3.LocalTextStyle.current
    val fullWidth = remember(state.items, state.accountName, density, baseStyle) {
        fun width(text: String, size: Int) = with(density) {
            measurer.measure(
                text,
                baseStyle.merge(androidx.compose.ui.text.TextStyle(fontSize = size.sp, fontWeight = FontWeight.SemiBold)),
                maxLines = 1,
            ).size.width.toDp()
        }
        var widest = 0.dp
        state.items.forEach { item ->
            val needed = if (item.id == state.accountId) {
                ENTRY_HEIGHT + 10.dp + maxOf(width(state.accountName ?: item.label, 17), if (state.accountName != null) width(item.label, 12) else 0.dp) + 26.dp
            } else {
                (if (item.sub) 30.dp else 0.dp) + ENTRY_HEIGHT + 8.dp + width(item.label, if (item.sub) 15 else 17) + 30.dp
            }
            if (needed > widest) widest = needed
        }
        (widest + MENU_PADDING * 2).coerceIn(212.dp, 320.dp)
    }
    val verticalPadDp = 16.dp
    val verticalPadPx = with(density) { verticalPadDp.roundToPx() }
    val scrollMarginPx = with(density) { 48.dp.roundToPx() }
    val subIndentPx = with(density) { 30.dp.toPx() }
    val accountCornerPx = with(density) { 16.dp.toPx() }
    val itemCornerPx = with(density) { 14.dp.toPx() }
    // the entry that has the focus, by its requester: its place among the entries is looked up when a key comes. A place
    // kept as a number went wrong when the menu opened with a stream kept playing: the focus came before the "now
    // playing" card was put in, the number was 2 short and up / down jumped over entries
    var focusedRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var focusedEntryId by remember { mutableIntStateOf(0) }
    var menuHasFocus by remember { mutableStateOf(false) }
    var userMovedInOpenMenu by remember { mutableStateOf(false) }

    val pillTop = remember { Animatable(0f) }
    val pillHeight = remember { Animatable(0f) }
    val pillStart = remember { Animatable(0f) }
    val pillCorner = remember { Animatable(0f) }
    var lastPillItemId by remember { mutableIntStateOf(0) }
    val lastDrawnPillBounds = remember { LastPillBoundsHolder() }
    var pillAnimating by remember { mutableStateOf(false) }

    LaunchedEffect(collapsed) {
        if (collapsed) {
            state.isNowPlayingOpenFocused = false
            userMovedInOpenMenu = false
            pillAnimating = false
            lastPillItemId = 0
            lastDrawnPillBounds.value = null
            if (scrollState.value != 0) {
                scrollJob?.cancel()
                scrollJob = scope.launch {
                    scrollState.animateScrollTo(0, tween(200, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    val displayFocusId = when {
        !menuHasFocus || collapsed -> 0
        state.pendingFocus && state.selected != 0 -> state.selected
        else -> focusedEntryId
    }

    LaunchedEffect(displayFocusId, collapsed, menuHasFocus) {
        if (displayFocusId == 0 || collapsed || !menuHasFocus) {
            pillAnimating = false
            lastPillItemId = 0
            lastDrawnPillBounds.value = null
            return@LaunchedEffect
        }
        val prevId = lastPillItemId
        val fromBounds = lastDrawnPillBounds.value
        val toBounds = entryPillBounds[displayFocusId]?.takeIf { !it.collapsedLayout }
        lastPillItemId = displayFocusId
        if (prevId != 0 && prevId != displayFocusId && !state.pendingFocus && openState.value >= 0.95f && fromBounds != null && toBounds != null) {
            if (!pillAnimating) {
                pillTop.snapTo(fromBounds.topY)
                pillHeight.snapTo(fromBounds.heightPx)
                pillStart.snapTo(fromBounds.startXPx)
                pillCorner.snapTo(fromBounds.cornerPx)
            }
            pillAnimating = true
            val spec = spring<Float>(stiffness = 1400f, dampingRatio = 0.92f)
            coroutineScope {
                launch { pillTop.animateTo(toBounds.topY, spec) }
                launch { pillHeight.animateTo(toBounds.heightPx, spec) }
                launch { pillStart.animateTo(toBounds.startXPx, spec) }
                launch { pillCorner.animateTo(toBounds.cornerPx, spec) }
            }
            pillAnimating = false
        } else {
            pillAnimating = false
        }
    }

    // the account first (its picture and its name at the top), then all entries kept mounted so NowPlayingCard and
    // Following sub-entries unfold/fold smoothly without relayout jumps or image reloads
    val account = state.items.find { it.id == state.accountId }
    val items = listOfNotNull(account) + state.items.filter { it !== account }
    val playing = state.nowPlaying
    val nowPlayingAt = items.indexOfFirst { it !== account && it.group != TvMenuState.GROUP_TOP }.coerceAtLeast(0)
    val nowPlayingOpenRequester = remember { FocusRequester() }
    val nowPlayingCloseRequester = remember { FocusRequester() }
    androidx.compose.runtime.SideEffect {
        state.nowPlayingCloseFocusRequester = if (playing != null && !collapsed) nowPlayingCloseRequester else null
        if (playing == null || collapsed || !menuHasFocus) {
            state.isNowPlayingOpenFocused = false
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            state.nowPlayingCloseFocusRequester = null
            state.isNowPlayingOpenFocused = false
        }
    }
    val slots = buildList {
        items.forEachIndexed { index, item ->
            if (index == nowPlayingAt && playing != null && !collapsed) {
                add(nowPlayingOpenRequester)
            }
            if (!collapsed || !item.sub) {
                add(state.requesters.getOrPut(item.id) { FocusRequester() })
            }
        }
    }

    suspend fun scrollTargetIntoView(targetId: Int) {
        val bounds = itemBounds[targetId]?.takeIf { !it.collapsedLayout } ?: return
        val viewport = scrollState.viewportSize
        if (viewport <= 0) return
        val targetRequester = state.requesters[targetId]
        val slotIdx = if (targetRequester != null) slots.indexOf(targetRequester) else -1
        val topInScroll = bounds.topY + verticalPadPx
        val bottomInScroll = topInScroll + bounds.heightPx
        val desired = when {
            slotIdx <= 0 -> 0
            slotIdx == slots.lastIndex && scrollState.maxValue > 0 -> scrollState.maxValue
            bottomInScroll + scrollMarginPx > scrollState.value + viewport ->
                (bottomInScroll + scrollMarginPx - viewport).coerceAtMost(scrollState.maxValue)
            topInScroll - scrollMarginPx < scrollState.value ->
                (topInScroll - scrollMarginPx).coerceAtLeast(0)
            else -> scrollState.value
        }
        if (desired != scrollState.value) {
            scrollJob?.cancel()
            scrollState.scrollTo(desired)
        }
    }

    LaunchedEffect(state.focusRequestToken, collapsed) {
        if (!state.pendingFocus || collapsed) return@LaunchedEffect
        userMovedInOpenMenu = false
        try {
            var confirmedFrames = 0
            for (attempt in 0 until 24) {
                withFrameNanos { }
                if (!state.pendingFocus || collapsed) return@LaunchedEffect
                val targetId = state.selected.takeIf { id -> id != 0 && items.any { it.id == id } }
                    ?: items.firstOrNull { !it.sub }?.id
                    ?: return@LaunchedEffect
                if (itemBounds[targetId]?.collapsedLayout == false) {
                    scrollTargetIntoView(targetId)
                    val focusedOk = state.focusSelected() && focusedEntryId == targetId && menuHasFocus
                    if (focusedOk) {
                        confirmedFrames++
                        if (confirmedFrames >= 2) {
                            state.pendingFocus = false
                            state.suppressFocusCallback = false
                            break
                        }
                    } else {
                        confirmedFrames = 0
                    }
                }
            }
        } finally {
            state.suppressFocusCallback = false
            state.pendingFocus = false
        }
        // Keep the focused entry smoothly anchored in view while sub-items finish unfolding (0..200ms)
        while (!collapsed && !userMovedInOpenMenu && openState.value < 0.999f) {
            withFrameNanos { }
            if (collapsed || userMovedInOpenMenu) return@LaunchedEffect
            val followId = focusedEntryId.takeIf { it != 0 } ?: state.selected
            if (followId != 0) {
                scrollTargetIntoView(followId)
            }
        }
        if (!collapsed && !userMovedInOpenMenu) {
            withFrameNanos { }
            val followId = focusedEntryId.takeIf { it != 0 } ?: state.selected
            if (followId != 0) {
                scrollTargetIntoView(followId)
            }
        }
    }
    fun smoothKeepSlotVisible(nextIndex: Int, requester: FocusRequester) {
        val viewport = scrollState.viewportSize
        val maxScroll = scrollState.maxValue
        if (viewport <= 0 || maxScroll <= 0) return
        val desired = when {
            nextIndex <= 0 -> 0
            nextIndex >= slots.lastIndex -> maxScroll
            else -> {
                val bounds = slotBounds[requester] ?: return
                val topInScroll = bounds.first + verticalPadPx
                val bottomInScroll = topInScroll + bounds.second
                when {
                    topInScroll - scrollMarginPx < scrollState.value ->
                        (topInScroll - scrollMarginPx).coerceAtLeast(0)
                    bottomInScroll + scrollMarginPx > scrollState.value + viewport ->
                        (bottomInScroll + scrollMarginPx - viewport).coerceAtMost(maxScroll)
                    else -> scrollState.value
                }
            }
        }
        if (desired != scrollState.value) {
            scrollJob?.cancel()
            scrollJob = scope.launch {
                scrollState.animateScrollTo(desired, tween(140, easing = FastOutSlowInEasing))
            }
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
    val clipPath = remember { Path() }
    val boxWidth = if (settledCollapsed) RAIL_WIDTH else fullWidth
    Box(
        Modifier
            .width(boxWidth)
            .fillMaxHeight()
            .onFocusChanged {
                menuHasFocus = it.hasFocus
                if (!it.hasFocus) {
                    focusedRequester = null
                    focusedEntryId = 0
                    state.focusedId = 0
                    state.isNowPlayingOpenFocused = false
                    pillAnimating = false
                    lastPillItemId = 0
                    lastDrawnPillBounds.value = null
                }
            }
            .drawWithContent {
                val p = openState.value.coerceIn(0f, 1f)
                val railPx = RAIL_WIDTH.toPx()
                val visibleW = (railPx + (size.width - railPx) * p).coerceAtMost(size.width)
                val cornerPx = 22.dp.toPx()
                clipPath.rewind()
                clipPath.addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = visibleW,
                        bottom = size.height,
                        topLeftCornerRadius = CornerRadius.Zero,
                        bottomLeftCornerRadius = CornerRadius.Zero,
                        topRightCornerRadius = CornerRadius(cornerPx, cornerPx),
                        bottomRightCornerRadius = CornerRadius(cornerPx, cornerPx),
                    )
                )
                if (p > 0.001f) {
                    val bgAlpha = 0.92f * p
                    drawPath(
                        path = clipPath,
                        brush = Brush.verticalGradient(listOf(top.copy(alpha = bgAlpha), bottom.copy(alpha = bgAlpha)))
                    )
                }
                clipPath(clipPath) {
                    this@drawWithContent.drawContent()
                }
                if (p > 0.001f) {
                    drawPath(
                        path = clipPath,
                        color = Color.White.copy(alpha = 0.07f * p),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
    ) {
        Column(
            Modifier
                // laid out at the width of the open menu whatever the width shown: nothing moves nor wraps while it opens
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .width(fullWidth)
                .fillMaxHeight()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val effectiveRequester = if (focusedRequester === nowPlayingCloseRequester) nowPlayingOpenRequester else focusedRequester
                    when (event.key) {
                        Key.DirectionUp -> {
                            userMovedInOpenMenu = true
                            val slot = slots.indexOf(effectiveRequester)
                            if (slot > 0) {
                                val nextIndex = slot - 1
                                val nextRequester = slots[nextIndex]
                                runCatching { nextRequester.requestFocus() }
                                smoothKeepSlotVisible(nextIndex, nextRequester)
                            }
                            true
                        }
                        Key.DirectionDown -> {
                            userMovedInOpenMenu = true
                            val slot = slots.indexOf(effectiveRequester)
                            if (slot in 0 until slots.lastIndex) {
                                val nextIndex = slot + 1
                                val nextRequester = slots[nextIndex]
                                runCatching { nextRequester.requestFocus() }
                                smoothKeepSlotVisible(nextIndex, nextRequester)
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            if (focusedRequester === nowPlayingCloseRequester) {
                                userMovedInOpenMenu = true
                                runCatching { nowPlayingOpenRequester.requestFocus() }
                            }
                            true
                        }
                        else -> false
                    }
                }
                .verticalScroll(scrollState)
                .padding(horizontal = MENU_PADDING, vertical = verticalPadDp)
                .drawBehind {
                    val targetId = when {
                        !menuHasFocus || collapsed -> 0
                        state.pendingFocus && state.selected != 0 -> state.selected
                        else -> focusedEntryId
                    }
                    val target = if (targetId != 0) entryPillBounds[targetId]?.takeIf { !it.collapsedLayout } else null
                    if (target != null) {
                        val p = openState.value.coerceIn(0f, 1f)
                        val topY = if (pillAnimating) pillTop.value else target.topY
                        val h = if (pillAnimating) pillHeight.value else target.heightPx
                        val startX = if (pillAnimating) pillStart.value else target.startXPx
                        val corner = if (pillAnimating) pillCorner.value else target.cornerPx
                        lastDrawnPillBounds.value = MenuPillBounds(topY, h, startX, corner, collapsedLayout = false)
                        val fullRowW = (size.width - startX).coerceAtLeast(0f)
                        val minW = ENTRY_HEIGHT.toPx().coerceAtMost(fullRowW)
                        val pillW = minW + (fullRowW - minW) * p
                        drawRoundRect(
                            color = TvColors.focused,
                            topLeft = Offset(startX, topY),
                            size = Size(pillW, h),
                            cornerRadius = CornerRadius(corner, corner),
                        )
                    }
                }
        ) {
            items.forEachIndexed { index, item ->
                val hasNowPlayingHere = index == nowPlayingAt && playing != null
                if (hasNowPlayingHere) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ENTRY_HEIGHT)
                            .onGloballyPositioned { coords ->
                                val y = coords.positionInParent().y.toInt()
                                val h = coords.size.height
                                slotBounds[nowPlayingOpenRequester] = y to h
                                slotBounds[nowPlayingCloseRequester] = y to h
                            }
                    ) {
                        NowPlayingBar(
                            playing = playing,
                            state = state,
                            openRequester = nowPlayingOpenRequester,
                            closeRequester = nowPlayingCloseRequester,
                            suppressVisualFocus = collapsed || (state.pendingFocus && state.selected != 0),
                            openProvider = openProvider,
                            onOpenFocusChanged = { isFocused ->
                                if (isFocused && !collapsed && (!state.pendingFocus || state.selected == 0)) {
                                    focusedRequester = nowPlayingOpenRequester
                                    focusedEntryId = 0
                                    state.focusedId = 0
                                    state.isNowPlayingOpenFocused = true
                                } else if (!isFocused) {
                                    state.isNowPlayingOpenFocused = false
                                }
                            },
                            onCloseFocused = {
                                if (!collapsed && (!state.pendingFocus || state.selected == 0)) {
                                    focusedRequester = nowPlayingCloseRequester
                                    focusedEntryId = 0
                                    state.focusedId = 0
                                    state.isNowPlayingOpenFocused = false
                                }
                            },
                        )
                        // the stream kept playing, as a red dot of the rail (smoothly crossfaded with NowPlayingBar)
                        Box(
                            Modifier
                                .size(ENTRY_HEIGHT)
                                .graphicsLayer {
                                    alpha = (1f - openProvider().coerceIn(0f, 1f) * 2.6f).coerceIn(0f, 1f)
                                }
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
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // air between the groups (account, search, sections, settings), no line
                if (index > 0 && !item.sub && !hasNowPlayingHere) {
                    val previous = items[index - 1]
                    val newGroup = previous === account || previous.group != item.group
                    Spacer(Modifier.height(if (newGroup) 12.dp else 4.dp))
                }
                val requester = state.requesters.getOrPut(item.id) { FocusRequester() }
                val isAccount = item === account
                val hasPillBounds = !collapsed && entryPillBounds[item.id]?.collapsedLayout == false
                val visuallyFocused = item.id == displayFocusId
                val subUnfoldModifier = if (!isAccount && item.sub) {
                    Modifier
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val p = openProvider().coerceIn(0f, 1f)
                            val h = (placeable.height * p).roundToInt()
                            layout(placeable.width, h) {
                                if (h > 0) {
                                    placeable.placeRelative(0, 0)
                                }
                            }
                        }
                        .graphicsLayer { alpha = openProvider().coerceIn(0f, 1f) }
                } else {
                    Modifier
                }
                val entryModifier = Modifier
                    .onGloballyPositioned { coords ->
                        val y = coords.positionInParent().y
                        val h = coords.size.height
                        itemBounds[item.id] = MenuItemBounds(y.toInt(), h, collapsed)
                        slotBounds[requester] = y.toInt() to h
                        val pillBounds = MenuPillBounds(
                            topY = y,
                            heightPx = h.toFloat(),
                            startXPx = if (!isAccount && item.sub) subIndentPx else 0f,
                            cornerPx = if (isAccount) accountCornerPx else itemCornerPx,
                            collapsedLayout = collapsed,
                        )
                        if (entryPillBounds[item.id] != pillBounds) {
                            entryPillBounds[item.id] = pillBounds
                        }
                    }
                    .then(subUnfoldModifier)
                    .focusRequester(requester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            if (state.pendingFocus && state.selected != 0 && item.id != state.selected) {
                                return@onFocusChanged
                            }
                            focusedRequester = requester
                            focusedEntryId = item.id
                            state.focusedId = item.id
                            state.isNowPlayingOpenFocused = false
                            if (!state.suppressFocusCallback || item.id == state.selected) {
                                state.suppressFocusCallback = false
                                state.onFocusItem(item.id)
                            }
                        } else if (focusedEntryId == item.id) {
                            focusedEntryId = 0
                            if (state.focusedId == item.id) {
                                state.focusedId = 0
                            }
                        }
                    }
                val suppressVisualFocus = state.pendingFocus && state.selected != 0 && item.id != state.selected
                if (isAccount) {
                    AccountEntry(item, state, openProvider, hasPillBounds, visuallyFocused, suppressVisualFocus, entryModifier) { onClick(item.id) }
                } else {
                    MenuEntry(item, item.id == activeId, openProvider, hasPillBounds, visuallyFocused, suppressVisualFocus, entryModifier) { onClick(item.id) }
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
private fun AccountEntry(
    item: TvMenuItem,
    state: TvMenuState,
    openProvider: () -> Float,
    useGlidingPill: Boolean,
    visuallyFocused: Boolean,
    suppressVisualFocus: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var rawFocused by remember { mutableStateOf(false) }
    val focused = if (useGlidingPill) visuallyFocused else (rawFocused && !suppressVisualFocus)
    val name = state.accountName
    val image = state.accountImage
    val content = if (focused) TvColors.onFocused else Color.White
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused && !useGlidingPill) TvColors.focused else Color.Transparent)
            .onFocusChanged { rawFocused = it.isFocused }
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
        Column(Modifier.padding(start = 10.dp, end = 12.dp).graphicsLayer { alpha = openProvider() }) {
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

/** Compact single-line "Now playing" bar: OK on the left pill reopens the stream, Right focuses the inline ✕ button. */
@Composable
private fun NowPlayingBar(
    playing: TvNowPlaying,
    state: TvMenuState,
    openRequester: FocusRequester,
    closeRequester: FocusRequester,
    suppressVisualFocus: Boolean,
    openProvider: () -> Float,
    onOpenFocusChanged: (Boolean) -> Unit,
    onCloseFocused: () -> Unit,
) {
    val openInteraction = remember { MutableInteractionSource() }
    val rawOpenFocused by openInteraction.collectIsFocusedAsState()
    val openFocused = rawOpenFocused && !suppressVisualFocus
    val closeInteraction = remember { MutableInteractionSource() }
    val rawCloseFocused by closeInteraction.collectIsFocusedAsState()
    val closeFocused = rawCloseFocused && !suppressVisualFocus
    val fallbackLabel = androidx.compose.ui.platform.LocalContext.current.getString(com.github.andreyasadchy.xtra.R.string.tv_now_playing)
    Row(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = openProvider().coerceIn(0f, 1f)
                alpha = ((p - 0.14f) / 0.86f).coerceIn(0f, 1f)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contentColor = if (openFocused) TvColors.onFocused else Color.White
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(if (openFocused) TvColors.focused else Color(0x22E91916))
                .then(
                    if (openFocused) Modifier
                    else Modifier.drawBehind {
                        drawRoundRect(
                            color = Color(0x52E91916),
                            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                )
                .focusRequester(openRequester)
                .onFocusChanged { onOpenFocusChanged(it.isFocused) }
                .clickable(interactionSource = openInteraction, indication = null) { state.onNowPlayingClick() }
                .padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(ENTRY_HEIGHT), contentAlignment = Alignment.Center) {
                Box(Modifier.size(30.dp)) {
                    if (!playing.image.isNullOrBlank() && !playing.audioOnly) {
                        coil3.compose.AsyncImage(
                            model = playing.image,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color(0xFF26252C)),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (openFocused) Color(0x1F000000) else Color(0x33E91916)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(
                                    if (playing.audioOnly) com.github.andreyasadchy.xtra.R.drawable.baseline_audiotrack_black_24
                                    else com.github.andreyasadchy.xtra.R.drawable.tv_ic_live
                                ),
                                contentDescription = null,
                                tint = if (openFocused) TvColors.onFocused else Color(0xFFE91916),
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(9.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (openFocused) TvColors.focused else Color(0xFF15141B))
                            .padding(1.5.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFFE91916))
                    )
                }
            }
            Text(
                playing.channelName?.takeIf { it.isNotBlank() } ?: fallbackLabel,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .width(40.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(if (closeFocused) TvColors.focused else Color(0x1AFFFFFF))
                .focusRequester(closeRequester)
                .onFocusChanged { if (it.isFocused) onCloseFocused() }
                .clickable(interactionSource = closeInteraction, indication = null) { state.onNowPlayingClose() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(com.github.andreyasadchy.xtra.R.drawable.tv_ic_close),
                contentDescription = androidx.compose.ui.platform.LocalContext.current.getString(com.github.andreyasadchy.xtra.R.string.tv_close_playback),
                tint = if (closeFocused) TvColors.onFocused else Color(0xD9FFFFFF),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}


/**
 * An entry. [openProvider] from 0 (the rail: only its icon, in a square) to 1 (the menu: its icon at the same place,
 * its name next to it). The section shown: a pill of the accent color, its icon in the accent color.
 */
@Composable
private fun MenuEntry(
    item: TvMenuItem,
    selected: Boolean,
    openProvider: () -> Float,
    useGlidingPill: Boolean,
    visuallyFocused: Boolean,
    suppressVisualFocus: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var rawFocused by remember { mutableStateOf(false) }
    val focused = if (useGlidingPill) visuallyFocused else (rawFocused && !suppressVisualFocus)
    val accent = androidx.tv.material3.MaterialTheme.colorScheme.primary
    val selectedPillColor = accent.copy(alpha = 0.22f)
    val content = when {
        focused -> TvColors.onFocused
        selected -> Color.White
        else -> Color(0xB8FFFFFF)
    }
    val iconTint = if (selected && !focused) accent else content
    val indent = if (item.sub) 30.dp else 0.dp
    val height = if (item.sub) 38.dp else ENTRY_HEIGHT
    Row(
        modifier
            .padding(start = indent)
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .drawBehind {
                when {
                    focused && !useGlidingPill -> {
                        val p = openProvider().coerceIn(0f, 1f)
                        val minW = ENTRY_HEIGHT.toPx().coerceAtMost(size.width)
                        val pillW = minW + (size.width - minW) * p
                        val r = 14.dp.toPx()
                        drawRoundRect(
                            color = TvColors.focused,
                            size = Size(pillW, size.height),
                            cornerRadius = CornerRadius(r, r),
                        )
                    }
                    selected && !focused -> {
                        val p = openProvider().coerceIn(0f, 1f)
                        val minW = ENTRY_HEIGHT.toPx().coerceAtMost(size.width)
                        val pillW = minW + (size.width - minW) * p
                        val r = 14.dp.toPx()
                        drawRoundRect(
                            color = selectedPillColor,
                            size = Size(pillW, size.height),
                            cornerRadius = CornerRadius(r, r),
                        )
                    }
                }
            }
            .onFocusChanged { rawFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = ENTRY_HEIGHT, height = height), contentAlignment = Alignment.Center) {
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
            modifier = Modifier.padding(start = 8.dp, end = 12.dp).graphicsLayer { alpha = openProvider() }
        )
    }
}
