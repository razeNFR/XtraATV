package com.github.andreyasadchy.xtra.ui.tv.home

import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import android.view.TextureView
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.andreyasadchy.xtra.ui.tv.tvHorizontalFade
import com.github.andreyasadchy.xtra.ui.tv.tvTopFade
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.tv.TvColors

class TvHomeActions(
    val openStream: (Stream, List<Stream>) -> Unit,
    /** OK held down on a stream card: its actions (channel, videos, category, follow). */
    val showActions: (Stream) -> Unit,
    val openGame: (Game) -> Unit,
    val showGameActions: (Game) -> Unit = {},
    val seeAll: (Int) -> Unit,
    val openMenu: () -> Unit,
    val onStreamFocused: (Stream?) -> Unit,
    /** The card of a stream lost the focus: its preview stops (the focus may go to a card without preview). */
    val onStreamUnfocused: (Stream) -> Unit = {},
    val openChannel: (Stream) -> Unit = {},
    val onHeroFocused: (Stream) -> Unit = {},
    val onHeroUnfocused: (Stream) -> Unit = {},
    /** A card with a picture that is not a stream got the focus (a video, a clip, a category), for the dynamic ambiance. */
    val onPictureFocused: (Any) -> Unit = {},
    val formatViewers: (Int) -> String,
    /** The count alone ("7.1K"), for the small cards. */
    val formatCount: (Int) -> String = { it.toString() },
    val refresh: () -> Unit,
    /** Stream cards in the width of the screen, from the settings. */
    val cardsPerRow: Int = 3,
    /** Changes at each load: the thumbnails are downloaded again. */
    val thumbnailStamp: () -> Long = { 0L },
    val refreshing: () -> Boolean = { false },
    /** "Continue watching": OK resumes the video, OK held down opens its actions. */
    val openVideo: (com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item) -> Unit = {},
    val showVideoActions: (com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item) -> Unit = {},
    val formatDuration: (Long) -> String = { "" },
    /** Row of the latest videos of the followed channels: OK plays the video, OK held down opens its actions. */
    val openFollowedVideo: (com.github.andreyasadchy.xtra.model.ui.Video) -> Unit = {},
    val showFollowedVideoActions: (com.github.andreyasadchy.xtra.model.ui.Video) -> Unit = {},
    val videoPosition: (com.github.andreyasadchy.xtra.model.ui.Video) -> Long? = { null },
    val formatVideoDuration: (Int) -> String = { "" },
    /** Row of top clips of followed channels: OK plays the clip queue, OK held down opens its actions. */
    val openClip: (com.github.andreyasadchy.xtra.model.ui.Clip, List<com.github.andreyasadchy.xtra.model.ui.Clip>) -> Unit = { _, _ -> },
    val showClipActions: (com.github.andreyasadchy.xtra.model.ui.Clip, List<com.github.andreyasadchy.xtra.model.ui.Clip>) -> Unit = { _, _ -> },
    /** Goes up at each refresh with the button once the rows came: a light sweeps over them. */
    val sweepCount: () -> Int = { 0 },
    /** OK on the card of the statistics: their page in detail. */
    val openStats: () -> Unit = {},
    /** The statistics of the badge of the top of the home (icon, text), one shown at a time. */
    val statsBadges: () -> List<Pair<Int, String>> = { emptyList() },
    /** How long a stream is live for its badge ("2 h 39"), null to show none. */
    val uptime: (com.github.andreyasadchy.xtra.model.ui.Stream) -> String? = { null },
    /** Saved multi-stream groups with at least 2 channels live: OK launches the multi-stream, OK held opens its actions. */
    val openMultiStream: (TvHomeMultiStream) -> Unit = {},
    val showMultiStreamActions: (TvHomeMultiStream) -> Unit = {},
)

class TvHomeRow<T>(
    val title: String,
    val items: List<T>,
    val seeAllId: Int,
    val icon: Int? = null,
    val onSeeAll: (() -> Unit)? = null,
)

sealed class TvHomeSection(val key: String) {
    class Skeleton(key: String) : TvHomeSection(key)
    class Streams(key: String, val row: TvHomeRow<Stream>) : TvHomeSection(key)
    class MultiStreams(key: String, val row: TvHomeRow<TvHomeMultiStream>) : TvHomeSection(key)
    class Resume(key: String, val row: TvHomeRow<com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item>) : TvHomeSection(key)
    class Videos(key: String, val row: TvHomeRow<com.github.andreyasadchy.xtra.model.ui.Video>) : TvHomeSection(key)
    class Clips(key: String, val row: TvHomeRow<com.github.andreyasadchy.xtra.model.ui.Clip>) : TvHomeSection(key)
    class Stats(key: String, val stats: TvHomeStats) : TvHomeSection(key)
    class Games(key: String, val row: TvHomeRow<Game>) : TvHomeSection(key)
}

private class HomeRowInfo(val rowIndex: Int, val key: String, val itemKeys: List<String>)

/**
 * Rows: the focus moves over the cards on screen and the row only slides when the focused card gets near an edge,
 * a part of the next card always stays in sight.
 */
@OptIn(ExperimentalFoundationApi::class)
private val PivotSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val peek = containerSize * 0.12f
        val end = offset + size
        return when {
            offset < peek -> offset - peek
            end > containerSize - peek -> end - (containerSize - peek)
            else -> 0f
        }
    }
}

/** The column does not scroll by itself for the focus: each row is brought to the same place when it gets the focus. */
@OptIn(ExperimentalFoundationApi::class)
private val NoScrollSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvHomeScreen(
    sections: List<TvHomeSection>,
    previewLogin: () -> String?,
    previewPlayer: () -> ExoPlayer?,
    actions: TvHomeActions,
    hero: Stream? = null,
    heroQueue: List<Stream> = emptyList(),
    /** Account for the greeting at the top right (no name: not logged in). */
    account: TvHomeAccount? = null,
    showGreeting: Boolean = true,
    showStatsBadge: Boolean = true,
    /** Where the focus goes when it comes into the home (side menu, player closed). */
    focus: TvHomeFocus = remember { TvHomeFocus() },
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstCard = remember { FocusRequester() }
    val peek = with(LocalDensity.current) { 56.dp.roundToPx() }
    // a row with the focus glides to the top, with the end of the row above still in sight (Google TV)
    val bringRow: (Int) -> Unit = { index ->
        scope.launch {
            listState.animateScrollToItem(index, if (index <= 0) 0 else -peek)
        }
    }
    val hasHero = hero != null
    val heroOffset = if (hasHero) 1 else 0
    // the first row is still loading: the focus waits on its first grey card, then goes to its first stream/card
    val firstIsGhost = !hasHero && sections.firstOrNull() is TvHomeSection.Skeleton
    val ghostCard = remember { FocusRequester() }
    // the focus was put on a grey card by the home (not by a key): it follows the rows as they come
    var onGhost by remember { mutableStateOf(false) }
    // a key was pressed since: the focus is where the user put it, nothing moves it any more
    var userMoved by remember { mutableStateOf(false) }
    val firstNonSkeletonSectionIndex = sections.indexOfFirst { it !is TvHomeSection.Skeleton }
    val hasCards = hasHero || firstNonSkeletonSectionIndex >= 0
    val allRows = buildList {
        if (hasHero) {
            add(HomeRowInfo(0, "hero", listOf("watch", "channel")))
        }
        sections.forEachIndexed { idx, section ->
            val listIndex = heroOffset + idx
            when (section) {
                is TvHomeSection.Skeleton -> Unit
                is TvHomeSection.Streams -> {
                    if (section.row.items.isNotEmpty()) {
                        val hasSeeAll = section.row.seeAllId != 0 || section.row.onSeeAll != null
                        val keys = section.row.items.mapIndexed { i, s -> s.channelLogin ?: "stream_$i" } +
                            if (hasSeeAll) listOf("seeAll") else emptyList()
                        add(HomeRowInfo(listIndex, section.key, keys))
                    }
                }
                is TvHomeSection.MultiStreams -> {
                    if (section.row.items.isNotEmpty()) {
                        add(HomeRowInfo(listIndex, section.key, section.row.items.map { it.group.id }))
                    }
                }
                is TvHomeSection.Resume -> {
                    if (section.row.items.isNotEmpty()) {
                        add(HomeRowInfo(listIndex, section.key, section.row.items.map { it.id }))
                    }
                }
                is TvHomeSection.Videos -> {
                    if (section.row.items.isNotEmpty()) {
                        add(HomeRowInfo(listIndex, section.key, section.row.items.mapIndexed { i, v -> v.id ?: "video$i" }))
                    }
                }
                is TvHomeSection.Clips -> {
                    if (section.row.items.isNotEmpty()) {
                        add(HomeRowInfo(listIndex, section.key, section.row.items.mapIndexed { i, c -> c.id ?: "clip$i" }))
                    }
                }
                is TvHomeSection.Stats -> {
                    add(HomeRowInfo(listIndex, section.key, listOf("stats")))
                }
                is TvHomeSection.Games -> {
                    if (section.row.items.isNotEmpty()) {
                        val hasSeeAll = section.row.seeAllId != 0 || section.row.onSeeAll != null
                        val keys = section.row.items.mapIndexed { i, g -> g.id ?: g.name ?: "game_$i" } +
                            if (hasSeeAll) listOf("seeAll") else emptyList()
                        add(HomeRowInfo(listIndex, section.key, keys))
                    }
                }
            }
        }
    }
    // "refresh" only takes the focus with up from the first row (or while nothing is loaded): coming into the home,
    // the focus never lands on it
    val refreshRequester = remember { FocusRequester() }
    var refreshAllowed by remember { mutableStateOf(false) }
    // item of the list that has the focus (0: the first row)
    var focusedItem by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    // state of each row and its cards, to put the focus back on a card
    val rowStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var refreshFocused by remember { mutableStateOf(false) }
    /** The badge of the statistics at the left of the greeting has the focus (reached from "refresh" with right). */
    var badgeFocused by remember { mutableStateOf(false) }
    // the rows came while "refresh" had the focus (first load): the focus goes to the first stream
    LaunchedEffect(hasCards) {
        if (hasCards && refreshFocused && !refreshAllowed) {
            focus.focusFirst()
        }
    }
    // the grey card had the focus and its row came (or turned out empty): the focus goes on, unless it moved meanwhile
    val firstKey = sections.firstOrNull()?.key
    LaunchedEffect(firstKey, firstIsGhost, hasCards) {
        if (!onGhost || userMoved) {
            return@LaunchedEffect
        }
        // the new first row may need a few frames to be laid out: tried again for a moment
        repeat(30) {
            androidx.compose.runtime.withFrameNanos { }
            if (userMoved) {
                return@LaunchedEffect
            }
            if (firstIsGhost) {
                if (runCatching { ghostCard.requestFocus() }.getOrNull() == true) return@LaunchedEffect
            } else if (hasCards) {
                listState.scrollToItem(0)
                if (runCatching { firstCard.requestFocus() }.getOrNull() == true) {
                    onGhost = false
                    return@LaunchedEffect
                }
            } else {
                // nothing at all (no rows, no network): "refresh" so the focus is never lost
                refreshAllowed = true
                if (runCatching { refreshRequester.requestFocus() }.getOrNull() == true) {
                    onGhost = false
                    return@LaunchedEffect
                }
            }
        }
    }
    val request = focus.request
    LaunchedEffect(request, hasCards, firstIsGhost, allRows.size) {
        if (request == null || (!hasCards && !firstIsGhost)) {
            return@LaunchedEffect
        }
        if (firstIsGhost && (!request.toLast || focus.lastRow == null)) {
            // the first row is loading: on its first grey card until it comes
            listState.scrollToItem(0)
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { ghostCard.requestFocus() }.getOrNull() == true) {
                // a new request (side menu, player closed): the home places the focus again
                userMoved = false
                onGhost = true
            }
            if (focus.request === request) focus.request = null
            return@LaunchedEffect
        }
        // the request is cleared once the focus is placed: clearing it first restarted this effect and stopped it
        // before the focus moved (stuck on "refresh")
        val done = { if (focus.request === request) focus.request = null }
        val target = if (request.toLast) {
            val wantedRow = focus.lastRow
            val wantedKey = focus.lastItemKey
            val exactInWanted = wantedRow?.let { rk ->
                allRows.find { it.key == rk }?.let { r ->
                    val idx = if (wantedKey != null) r.itemKeys.indexOf(wantedKey) else -1
                    if (idx >= 0) Triple(r, idx, r.itemKeys[idx]) else null
                }
            }
            val exactInAny = if (exactInWanted == null && wantedKey != null) {
                allRows.firstNotNullOfOrNull { r ->
                    val idx = r.itemKeys.indexOf(wantedKey)
                    if (idx >= 0) Triple(r, idx, r.itemKeys[idx]) else null
                }
            } else null
            val fallbackInWanted = if (exactInWanted == null && exactInAny == null && wantedRow != null) {
                allRows.find { it.key == wantedRow && it.itemKeys.isNotEmpty() }?.let { r ->
                    val idx = focus.lastItemIndex.coerceIn(0, r.itemKeys.lastIndex)
                    Triple(r, idx, r.itemKeys[idx])
                }
            } else null
            exactInWanted ?: exactInAny ?: fallbackInWanted
        } else null
        if (target != null) {
            val (rowInfo, cardIndex, itemKey) = target
            listState.scrollToItem(rowInfo.rowIndex, if (rowInfo.rowIndex <= 0) 0 else -peek)
            rowStates[rowInfo.key]?.scrollToItem(cardIndex)
            repeat(10) {
                androidx.compose.runtime.withFrameNanos { }
                if (runCatching { cardRequesters["${rowInfo.key}/$itemKey"]?.requestFocus() }.getOrNull() == true) {
                    focusedItem = rowInfo.rowIndex
                    done()
                    return@LaunchedEffect
                }
            }
            // the card could not take the focus (too far in its row, the row changed): the first card of the same row,
            // the focus stays on the row it was on and is never lost
            val firstRowItemKey = rowInfo.itemKeys.firstOrNull()
            if (firstRowItemKey != null && firstRowItemKey != itemKey) {
                runCatching { rowStates[rowInfo.key]?.scrollToItem(0) }
                repeat(10) {
                    androidx.compose.runtime.withFrameNanos { }
                    if (runCatching { cardRequesters["${rowInfo.key}/$firstRowItemKey"]?.requestFocus() }.getOrNull() == true) {
                        focusedItem = rowInfo.rowIndex
                        done()
                        return@LaunchedEffect
                    }
                }
            }
        }
        listState.scrollToItem(0)
        allRows.firstOrNull()?.let { rowStates[it.key]?.scrollToItem(0) }
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { firstCard.requestFocus() }
        done()
    }
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    val compactRail = remember { homeContext.prefs().getBoolean(C.TV_COMPACT_RAIL, true) }
    val startInset = if (compactRail) 16.dp else 48.dp
    val endInset = 48.dp
    LaunchedEffect(showStatsBadge) {
        if (!showStatsBadge) badgeFocused = false
    }
    Column(Modifier.fillMaxSize()) {
        // always on screen above the rows: refresh, then the optional stats pill, greeting, and time
        Row(
            Modifier.fillMaxWidth().padding(start = startInset + 12.dp, end = endInset + 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RefreshPill(
                actions,
                Modifier
                    .focusRequester(refreshRequester)
                    .focusProperties { canFocus = refreshAllowed || badgeFocused || !hasCards }
                    .onFocusChanged {
                        refreshFocused = it.hasFocus
                        if (!it.hasFocus) refreshAllowed = false
                    }
                    .onPreviewKeyEvent { event ->
                        // down from "refresh": back to the card of the first row it came from
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && hasCards) {
                            focus.focusLast()
                            true
                        } else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && (!showStatsBadge || actions.statsBadges().isEmpty())) {
                            true
                        } else false
                    }
            )
            Spacer(Modifier.weight(1f))
            if (showStatsBadge) {
                // a statistic that changes each minute, OK opens their page; reached from "refresh" (right)
                StatsBadge(
                    actions,
                    Modifier
                        .focusProperties { canFocus = refreshFocused || badgeFocused || !hasCards }
                        .onFocusChanged { badgeFocused = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && hasCards) {
                                focus.focusLast()
                                true
                            } else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                true
                            } else false
                        }
                )
                Spacer(Modifier.width(16.dp))
            }
            Greeting(account, showGreeting = showGreeting)
        }
        // refreshed with the button: a light sweeps once over the rows, like the other pages
        val sweepCount = actions.sweepCount()
        val sweep = remember { androidx.compose.animation.core.Animatable(-1f) }
        LaunchedEffect(sweepCount) {
            if (sweepCount > 0) {
                sweep.snapTo(0f)
                sweep.animateTo(1f, androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.LinearEasing))
                sweep.snapTo(-1f)
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollSpec) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // the rows that go under the bar of the top fade away
                    .tvTopFade { listState.canScrollBackward }
                    .drawWithContent {
                        drawContent()
                        val progress = sweep.value
                        if (progress >= 0f) {
                            val band = size.width * 0.35f
                            val x = -band + progress * (size.width + band * 2)
                            drawRect(
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.27f), Color.Transparent),
                                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                                    end = androidx.compose.ui.geometry.Offset(x + band, 0f),
                                )
                            )
                        }
                    }
                    .focusRestorer(if (firstIsGhost) ghostCard else firstCard)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            userMoved = true
                            onGhost = false
                        }
                        // up from the first row: "refresh"
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && focusedItem == 0) {
                            refreshAllowed = true
                            runCatching { refreshRequester.requestFocus() }
                            true
                        } else false
                    },
                contentPadding = PaddingValues(top = 0.dp, bottom = 240.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (hasHero) {
                    item(key = "hero") {
                        Box(Modifier.padding(start = startInset, end = endInset).onFocusChanged { if (it.hasFocus) { focusedItem = 0; bringRow(0) } }) {
                            HeroBanner(
                                stream = hero!!,
                                queue = heroQueue,
                                actions = actions,
                                previewLogin = previewLogin,
                                previewPlayer = previewPlayer,
                                firstRequester = firstCard,
                                watchRequester = cardRequesters.getOrPut("hero/watch") { FocusRequester() },
                                channelRequester = cardRequesters.getOrPut("hero/channel") { FocusRequester() },
                                onFocusedPill = { idx, itemKey -> focus.rememberCard("hero", itemKey, idx) },
                                onSelectedPill = { idx, itemKey -> focus.rememberCard("hero", itemKey, idx, force = true) },
                            )
                        }
                    }
                }
                sections.forEachIndexed { sectionIndex, section ->
                    val listIndex = heroOffset + sectionIndex
                    val key = section.key
                    val rowFirstCard = if (!hasHero && sectionIndex == firstNonSkeletonSectionIndex) firstCard else null
                    when (section) {
                        is TvHomeSection.Skeleton -> {
                            item(key = "${key}_skeleton") {
                                SkeletonRow(
                                    actions,
                                    startInset = startInset,
                                    firstCardModifier = if (listIndex == 0) {
                                        Modifier
                                            .focusRequester(ghostCard)
                                            .onFocusChanged { if (it.hasFocus) { if (!userMoved) onGhost = true; focusedItem = listIndex } }
                                    } else null,
                                )
                            }
                        }
                        is TvHomeSection.Streams -> {
                            item(key = key) {
                                Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = listIndex; bringRow(listIndex) } }) {
                                    StreamRow(
                                        section.row, previewLogin, previewPlayer, actions, rowFirstCard,
                                        startInset = startInset,
                                        endInset = endInset,
                                        state = rowStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() },
                                        cardModifier = { idx, itemKey ->
                                            Modifier
                                                .focusRequester(cardRequesters.getOrPut("$key/$itemKey") { FocusRequester() })
                                                .onFocusChanged {
                                                    if (it.hasFocus) {
                                                        focus.rememberCard(key, itemKey, idx)
                                                    }
                                                }
                                        },
                                        onCardSelected = { idx, itemKey -> focus.rememberCard(key, itemKey, idx, force = true) },
                                    )
                                }
                            }
                        }
                        is TvHomeSection.MultiStreams -> {
                            item(key = key) {
                                Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = listIndex; bringRow(listIndex) } }) {
                                    MultiStreamRow(
                                        section.row, actions, rowFirstCard,
                                        startInset = startInset,
                                        endInset = endInset,
                                        state = rowStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() },
                                        cardModifier = { idx, itemKey ->
                                            Modifier
                                                .focusRequester(cardRequesters.getOrPut("$key/$itemKey") { FocusRequester() })
                                                .onFocusChanged {
                                                    if (it.hasFocus) {
                                                        focus.rememberCard(key, itemKey, idx)
                                                    }
                                                }
                                        },
                                        onCardSelected = { idx, itemKey -> focus.rememberCard(key, itemKey, idx, force = true) },
                                    )
                                }
                            }
                        }
                        is TvHomeSection.Resume -> {
                            item(key = key) {
                                Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = listIndex; bringRow(listIndex) } }) {
                                    ResumeRow(
                                        section.row, actions, rowFirstCard,
                                        startInset = startInset,
                                        endInset = endInset,
                                        state = rowStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() },
                                        cardModifier = { idx, itemKey ->
                                            Modifier
                                                .focusRequester(cardRequesters.getOrPut("$key/$itemKey") { FocusRequester() })
                                                .onFocusChanged {
                                                    if (it.hasFocus) {
                                                        focus.rememberCard(key, itemKey, idx)
                                                    }
                                                }
                                        },
                                        onCardSelected = { idx, itemKey -> focus.rememberCard(key, itemKey, idx, force = true) },
                                    )
                                }
                            }
                        }
                        is TvHomeSection.Videos -> {
                            item(key = key) {
                                Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = listIndex; bringRow(listIndex) } }) {
                                    VideoRow(
                                        section.row, actions, rowFirstCard,
                                        startInset = startInset,
                                        endInset = endInset,
                                        state = rowStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() },
                                        cardModifier = { idx, itemKey ->
                                            Modifier
                                                .focusRequester(cardRequesters.getOrPut("$key/$itemKey") { FocusRequester() })
                                                .onFocusChanged {
                                                    if (it.hasFocus) {
                                                        focus.rememberCard(key, itemKey, idx)
                                                    }
                                                }
                                        },
                                        onCardSelected = { idx, itemKey -> focus.rememberCard(key, itemKey, idx, force = true) },
                                    )
                                }
                            }
                        }
                        is TvHomeSection.Clips -> {
                            item(key = key) {
                                Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = listIndex; bringRow(listIndex) } }) {
                                    ClipRow(
                                        section.row, actions, rowFirstCard,
                                        startInset = startInset,
                                        endInset = endInset,
                                        state = rowStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() },
                                        cardModifier = { idx, itemKey ->
                                            Modifier
                                                .focusRequester(cardRequesters.getOrPut("$key/$itemKey") { FocusRequester() })
                                                .onFocusChanged {
                                                    if (it.hasFocus) {
                                                        focus.rememberCard(key, itemKey, idx)
                                                    }
                                                }
                                        },
                                        onCardSelected = { idx, itemKey -> focus.rememberCard(key, itemKey, idx, force = true) },
                                    )
                                }
                            }
                        }
                        is TvHomeSection.Stats -> {
                            item(key = key) {
                                Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = listIndex; bringRow(listIndex) } }) {
                                    StatsCard(
                                        section.stats, actions,
                                        startInset = startInset,
                                        endInset = endInset,
                                        modifier = Modifier
                                            .then(if (rowFirstCard != null) Modifier.focusRequester(rowFirstCard) else Modifier)
                                            .focusRequester(cardRequesters.getOrPut("$key/stats") { FocusRequester() })
                                            .onFocusChanged {
                                                if (it.hasFocus) {
                                                    focus.rememberCard(key, "stats", 0)
                                                }
                                            },
                                    )
                                }
                            }
                        }
                        is TvHomeSection.Games -> {
                            item(key = key) {
                                Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = listIndex; bringRow(listIndex) } }) {
                                    GameRow(
                                        section.row, actions, rowFirstCard,
                                        startInset = startInset,
                                        endInset = endInset,
                                        state = rowStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() },
                                        cardModifier = { idx, itemKey ->
                                            Modifier
                                                .focusRequester(cardRequesters.getOrPut("$key/$itemKey") { FocusRequester() })
                                                .onFocusChanged {
                                                    if (it.hasFocus) {
                                                        focus.rememberCard(key, itemKey, idx)
                                                    }
                                                }
                                        },
                                        onCardSelected = { idx, itemKey -> focus.rememberCard(key, itemKey, idx, force = true) },
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

/**
 * Top spotlight ("À la une") blended seamlessly into the ambient background via multi-edge DstIn alpha feathering
 * (zero hard edges or dark cutout boxes).
 */
@Composable
private fun HeroBanner(
    stream: Stream,
    queue: List<Stream>,
    actions: TvHomeActions,
    previewLogin: () -> String?,
    previewPlayer: () -> ExoPlayer?,
    firstRequester: FocusRequester,
    watchRequester: FocusRequester,
    channelRequester: FocusRequester,
    onFocusedPill: (Int, String) -> Unit,
    onSelectedPill: (Int, String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val favorites by com.github.andreyasadchy.xtra.ui.tv.TvFavorites.items.collectAsState()
    val isFavorite = favorites.any { it.login == stream.channelLogin }
    var heroFocused by remember { mutableStateOf(false) }
    // the focus as the banner reports it: going from one of its buttons to the other may report it lost for an instant,
    // the video must not start again for that
    var heroHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(heroHasFocus) {
        if (heroHasFocus) {
            if (!heroFocused) {
                // not for a focus that only goes through the banner (coming back from the side menu to a card)
                kotlinx.coroutines.delay(120L)
                heroFocused = true
                actions.onHeroFocused(stream)
            }
        } else if (heroFocused) {
            kotlinx.coroutines.delay(200L)
            heroFocused = false
            actions.onHeroUnfocused(stream)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(256.dp)
            .onFocusChanged { heroHasFocus = it.hasFocus }
    ) {
        // High-res backdrop on the right, alpha-feathered on all four edges so it melts into any ambient background
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.72f)
                .fillMaxHeight()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0.00f to Color.Transparent,
                            0.20f to Color(0x45FFFFFF),
                            0.46f to Color(0xD9FFFFFF),
                            0.70f to Color.White,
                            0.90f to Color(0xD9FFFFFF),
                            1.00f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.14f to Color(0xCCFFFFFF),
                            0.34f to Color.White,
                            0.64f to Color(0xD9FFFFFF),
                            0.88f to Color(0x40FFFFFF),
                            1.00f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
        ) {
            AsyncImage(
                model = com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.picture(stream, stream.thumbnail),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.90f }
            )
            // the stream itself over its picture while the banner has the focus (no sound), cropped like the picture
            val player = previewPlayer()
            if (heroFocused && player != null && previewLogin() != null && previewLogin() == stream.channelLogin) {
                Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { context -> TextureView(context) },
                        update = { player.setVideoTextureView(it) },
                        onRelease = { runCatching { player.clearVideoTextureView(it) } },
                        modifier = Modifier
                            .wrapContentHeight(unbounded = true)
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .graphicsLayer { alpha = 0.90f }
                    )
                }
            }
        }

        // Stream metadata and action pills on the left
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.60f)
                .padding(start = 14.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE91916))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text("LIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                }
                Text(
                    context.getString(R.string.tv_featured_live),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                stream.viewerCount?.let { count ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x33000000))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(R.drawable.tv_ic_eye), contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(actions.formatViewers(count), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xEBFFFFFF), maxLines = 1)
                    }
                }
                actions.uptime(stream)?.let { up ->
                    Text(
                        up,
                        fontSize = 12.sp,
                        color = TvColors.secondaryText,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = stream.channelImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF26252C))
                )
                Spacer(Modifier.width(12.dp))
                if (isFavorite) {
                    Icon(
                        painterResource(R.drawable.tv_ic_star),
                        contentDescription = null,
                        tint = Color(0xFFFACC15),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    stream.channelName ?: stream.channelLogin ?: "",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!stream.title.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stream.title?.trim().orEmpty(),
                    fontSize = 15.sp,
                    color = Color(0xE6FFFFFF),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!stream.gameName.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stream.gameName.orEmpty(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroActionPill(
                    icon = R.drawable.tv_ic_play,
                    label = context.getString(R.string.watch_live),
                    primary = true,
                    modifier = Modifier
                        .leftEdge(0, actions.openMenu)
                        .focusRequester(firstRequester)
                        .focusRequester(watchRequester)
                        .onFocusChanged { if (it.isFocused) onFocusedPill(0, "watch") },
                    onClick = {
                        onSelectedPill(0, "watch")
                        actions.openStream(stream, queue.ifEmpty { listOf(stream) })
                    },
                    onLongClick = {
                        onSelectedPill(0, "watch")
                        actions.showActions(stream)
                    },
                )
                HeroActionPill(
                    icon = R.drawable.tv_ic_person,
                    label = context.getString(R.string.tv_channel_page),
                    primary = false,
                    modifier = Modifier
                        .focusRequester(channelRequester)
                        .onFocusChanged { if (it.isFocused) onFocusedPill(1, "channel") }
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight
                        },
                    onClick = {
                        onSelectedPill(1, "channel")
                        actions.openChannel(stream)
                    },
                )
            }
        }
    }
}

@Composable
private fun HeroActionPill(
    icon: Int,
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var longPressed by remember { mutableStateOf(false) }
    val bg = when {
        focused -> TvColors.focused
        primary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
        else -> Color(0x26FFFFFF)
    }
    val fg = if (focused) TvColors.onFocused else Color.White
    Row(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!isSelect || onLongClick == null) return@onPreviewKeyEvent false
                when {
                    event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 1 -> {
                        longPressed = true
                        onLongClick()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 1 -> true
                    event.type == KeyEventType.KeyUp && longPressed -> {
                        longPressed = false
                        true
                    }
                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

/**
 * Where the focus goes in the home: [focusFirst] the first stream of the first row (coming from the side menu),
 * [focusLast] the card watched or opened last, else the first card (the player closed or back from a page).
 */
class TvHomeFocus {
    class Request(val toLast: Boolean)

    var request by mutableStateOf<Request?>(null)
    var lastRow: String? = null
    var lastItemKey: String? = null
    var lastItemIndex: Int = 0

    fun rememberCard(row: String, itemKey: String, index: Int, force: Boolean = false) {
        if (force || request == null) {
            lastRow = row
            lastItemKey = itemKey
            lastItemIndex = index
        }
    }

    fun focusFirst() {
        request = Request(false)
    }

    fun focusLast() {
        request = Request(true)
    }
}

/** Account shown at the top right of the home. */
class TvHomeAccount(val name: String?, val image: String?)

@Composable
private fun Greeting(account: TvHomeAccount?, showGreeting: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // the time, again at each new minute
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = System.currentTimeMillis()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showGreeting) {
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
            val name = account?.name
            val greeting = context.getString(
                if (hour >= 18 || hour < 5) {
                    if (name != null) R.string.tv_good_evening_name else R.string.tv_good_evening
                } else {
                    if (name != null) R.string.tv_hello_name else R.string.tv_hello
                },
                name ?: ""
            )
            Text(greeting, fontSize = 15.sp, color = TvColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1
        )
    }
}

/**
 * Grey cards shown while a row of the home loads, with a light that sweeps over them (it is loading). [firstCardModifier]:
 * the first card can take the focus (the first row of the home), it shows it with a white border.
 */
@Composable
private fun SkeletonRow(actions: TvHomeActions, startInset: Dp = 0.dp, firstCardModifier: Modifier? = null) {
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val count = actions.cardsPerRow.coerceIn(2, 6)
    val cardWidth = (screenWidth - 24.dp - 16.dp * count) / (count + 0.25f)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val sweep by transition.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1300, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "sweep"
    )
    val density = LocalDensity.current
    val screenPx = with(density) { screenWidth.toPx() }
    val bandPx = screenPx * 0.35f
    // the light crosses the whole row from left to right, each card shows the part of it over the card
    val lightX = -bandPx + sweep * (screenPx + bandPx * 2)
    val base = Color.White.copy(alpha = 0.07f)
    val light = Color.White.copy(alpha = 0.18f)
    val shimmer = { left: Float ->
        androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(base, light, base),
            start = androidx.compose.ui.geometry.Offset(lightX - left, 0f),
            end = androidx.compose.ui.geometry.Offset(lightX - left + bandPx, 0f),
        )
    }
    val startPx = with(density) { (startInset + 12.dp).toPx() }
    val stepPx = with(density) { (cardWidth + 16.dp).toPx() }
    Column(Modifier.padding(start = startInset + 12.dp, top = 14.dp, bottom = 8.dp)) {
        Box(Modifier.width(220.dp).height(22.dp).clip(RoundedCornerShape(6.dp)).background(shimmer(startPx)))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(count) { index ->
                val left = startPx + index * stepPx
                val focusModifier = if (index == 0 && firstCardModifier != null) {
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    firstCardModifier
                        .leftEdge(0, actions.openMenu)
                        .focusable(interactionSource = interaction)
                        .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                } else Modifier
                Column(Modifier.width(cardWidth)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(focusModifier).clip(RoundedCornerShape(10.dp)).background(shimmer(left))
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(shimmer(left)))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.45f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(shimmer(left)))
                }
            }
        }
    }
}

/** A statistic of the TV (time today, streak of days, top streamer of the week...), the next one each minute. */
@Composable
private fun StatsBadge(actions: TvHomeActions, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var items by remember { mutableStateOf(actions.statsBadges()) }
    var index by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            items = actions.statsBadges()
            index++
        }
    }
    val item = items.takeIf { it.isNotEmpty() }?.let { it[index % it.size] } ?: return
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (focused) TvColors.focused else Color(0x1AFFFFFF))
            .clickable(interactionSource = interaction, indication = null, onClick = actions.openStats)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(item.first), contentDescription = null, tint = if (focused) TvColors.onFocused else TvColors.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(item.second, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (focused) TvColors.onFocused else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 340.dp))
    }
}

@Composable
private fun RefreshPill(actions: TvHomeActions, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .leftEdge(0, actions.openMenu)
            .clip(RoundedCornerShape(24.dp))
            .background(if (focused) TvColors.focused else Color(0x26FFFFFF))
            .clickable(interactionSource = interaction, indication = null, onClick = actions.refresh)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // turns while the rows load again
        val rotation = if (actions.refreshing()) {
            val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "refresh")
            transition.animateFloat(
                0f, 360f,
                androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.LinearEasing)),
                label = "rotation"
            ).value
        } else 0f
        Icon(
            painterResource(R.drawable.tv_ic_refresh),
            contentDescription = null,
            tint = if (focused) TvColors.onFocused else Color.White,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }
        )
        Spacer(Modifier.width(10.dp))
        Text(
            androidx.compose.ui.platform.LocalContext.current.getString(R.string.refresh),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) TvColors.onFocused else Color.White
        )
    }
}

private val CardGlowSteps = floatArrayOf(
    1.0f, 0.068f,
    2.0f, 0.056f,
    3.1f, 0.044f,
    4.3f, 0.033f,
    5.6f, 0.023f,
    6.9f, 0.014f,
    8.2f, 0.008f,
    9.5f, 0.004f,
)

/** Symmetric, softly feathered outer halo around a focused card thumbnail (works on all APIs 24+ with zero downward bleed or clipping). */
private fun Modifier.tvCardFocusGlow(
    focused: Boolean,
    color: Color,
    cornerRadius: Dp = 10.dp,
): Modifier = if (!focused) this else this.drawBehind {
    val baseRadius = cornerRadius.toPx()
    var i = 0
    while (i < CardGlowSteps.size) {
        val outset = CardGlowSteps[i].dp.toPx()
        val alpha = CardGlowSteps[i + 1]
        val r = baseRadius + outset
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(-outset, -outset),
            size = Size(size.width + outset * 2f, size.height + outset * 2f),
            cornerRadius = CornerRadius(r, r),
        )
        i += 2
    }
}

@Composable
private fun RowTitle(title: String, icon: Int? = null, startPadding: Dp = 12.dp) {
    Row(
        modifier = Modifier.padding(start = startPadding, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/** Left on the first card opens the side menu. */
private fun Modifier.leftEdge(index: Int, openMenu: () -> Unit) = onPreviewKeyEvent { event ->
    if (index == 0 && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
        openMenu()
        true
    } else false
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamRow(
    row: TvHomeRow<Stream>,
    previewLogin: () -> String?,
    previewPlayer: () -> ExoPlayer?,
    actions: TvHomeActions,
    firstRequester: FocusRequester?,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    cardModifier: (Int, String) -> Modifier = { _, _ -> Modifier },
    onCardSelected: (Int, String) -> Unit = { _, _ -> },
) {
    Column {
        RowTitle(row.title, row.icon, startPadding = startInset + 12.dp)
        // the cards fill the width of the screen, with a part of the next one in sight
        val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val count = actions.cardsPerRow.coerceIn(2, 6)
        val cardWidth = (screenWidth - 24.dp - 16.dp * count) / (count + 0.25f)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
        LazyRow(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .tvHorizontalFade(
                    startWidth = 56.dp,
                    endWidth = 56.dp,
                    scrolledStart = { state.canScrollBackward },
                    scrolledEnd = { state.canScrollForward },
                )
                .focusRestorer(),
            contentPadding = PaddingValues(start = startInset + 12.dp, end = endInset + 12.dp, top = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.items, key = { index, item -> item.channelId ?: index }) { index, stream ->
                val itemKey = stream.channelLogin ?: "stream_$index"
                StreamCard(
                    width = cardWidth,
                    stream = stream,
                    previewLogin = previewLogin,
                    previewPlayer = previewPlayer,
                    actions = actions,
                    modifier = Modifier.leftEdge(index, actions.openMenu).then(if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier).then(cardModifier(index, itemKey)),
                    onClick = {
                        onCardSelected(index, itemKey)
                        actions.openStream(stream, row.items)
                    },
                    onLongClick = {
                        onCardSelected(index, itemKey)
                        actions.showActions(stream)
                    },
                )
            }
            if (row.seeAllId != 0 || row.onSeeAll != null) {
                item(key = "seeAll") {
                    val seeAllIndex = row.items.size
                    SeeAllCard(width = 220.dp, aspect = 16f / 9f, modifier = cardModifier(seeAllIndex, "seeAll")) {
                        onCardSelected(seeAllIndex, "seeAll")
                        row.onSeeAll?.invoke() ?: actions.seeAll(row.seeAllId)
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultiStreamRow(
    row: TvHomeRow<TvHomeMultiStream>,
    actions: TvHomeActions,
    firstRequester: FocusRequester?,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    cardModifier: (Int, String) -> Modifier = { _, _ -> Modifier },
    onCardSelected: (Int, String) -> Unit = { _, _ -> },
) {
    Column {
        RowTitle(row.title, row.icon, startPadding = startInset + 12.dp)
        val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val count = actions.cardsPerRow.coerceIn(2, 6)
        val cardWidth = (screenWidth - 24.dp - 16.dp * count) / (count + 0.25f)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
            LazyRow(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvHorizontalFade(
                        startWidth = 56.dp,
                        endWidth = 56.dp,
                        scrolledStart = { state.canScrollBackward },
                        scrolledEnd = { state.canScrollForward },
                    )
                    .focusRestorer(),
                contentPadding = PaddingValues(start = startInset + 12.dp, end = endInset + 12.dp, top = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(row.items, key = { _, item -> item.group.id }) { index, item ->
                    val itemKey = item.group.id
                    MultiStreamCard(
                        width = cardWidth,
                        item = item,
                        actions = actions,
                        modifier = Modifier
                            .leftEdge(index, actions.openMenu)
                            .then(if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier)
                            .then(cardModifier(index, itemKey)),
                        onClick = {
                            onCardSelected(index, itemKey)
                            actions.openMultiStream(item)
                        },
                        onLongClick = {
                            onCardSelected(index, itemKey)
                            actions.showMultiStreamActions(item)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MultiStreamTileThumb(stream: Stream, modifier: Modifier) {
    AsyncImage(
        model = com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.picture(stream, stream.thumbnail?.replace("1280x720", "640x360")),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(Color(0xFF26252C)),
    )
}

@Composable
private fun MultiStreamCard(
    width: Dp,
    item: TvHomeMultiStream,
    actions: TvHomeActions,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val small = width < 230.dp
    val context = androidx.compose.ui.platform.LocalContext.current
    FocusCard(
        width = width,
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = { isFocused ->
            if (isFocused) {
                item.liveStreams.firstOrNull()?.let(actions.onPictureFocused)
            }
        },
    ) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .tvCardFocusGlow(focused, MaterialTheme.colorScheme.primary)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF141318))
                .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            val tiles = item.liveStreams.take(4)
            val gap = 1.5.dp
            when (tiles.size) {
                0 -> Box(Modifier.fillMaxSize().background(Color(0xFF26252C)))
                1 -> MultiStreamTileThumb(tiles[0], Modifier.fillMaxSize())
                2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    MultiStreamTileThumb(tiles[0], Modifier.weight(1f).fillMaxHeight())
                    MultiStreamTileThumb(tiles[1], Modifier.weight(1f).fillMaxHeight())
                }
                3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    MultiStreamTileThumb(tiles[0], Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        MultiStreamTileThumb(tiles[1], Modifier.fillMaxWidth().weight(1f))
                        MultiStreamTileThumb(tiles[2], Modifier.fillMaxWidth().weight(1f))
                    }
                }
                else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        MultiStreamTileThumb(tiles[0], Modifier.weight(1f).fillMaxHeight())
                        MultiStreamTileThumb(tiles[1], Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        MultiStreamTileThumb(tiles[2], Modifier.weight(1f).fillMaxHeight())
                        MultiStreamTileThumb(tiles[3], Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFFE91916))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        context.getString(R.string.tv_multistream_live_count, item.liveStreams.size, item.group.streams.size),
                        fontSize = if (small) 10.sp else 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
                if (item.totalViewers > 0) {
                    Spacer(Modifier.width(5.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xB3000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(R.drawable.tv_ic_eye), contentDescription = null, tint = Color.White, modifier = Modifier.size(if (small) 11.dp else 12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(actions.formatCount(item.totalViewers), fontSize = if (small) 10.sp else 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x80000000))))
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Icon(
                    painterResource(R.drawable.tv_ic_multistream),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (small) 12.dp else 14.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val avatars = item.liveStreams.take(4)
            if (avatars.isNotEmpty()) {
                val avatarSize = if (small) 24.dp else 28.dp
                val step = if (small) 15.dp else 18.dp
                Box(
                    Modifier
                        .width(avatarSize + step * (avatars.size - 1))
                        .height(avatarSize)
                ) {
                    avatars.forEachIndexed { idx, s ->
                        AsyncImage(
                            model = s.channelImage,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(start = step * idx)
                                .size(avatarSize)
                                .clip(CircleShape)
                                .background(Color(0xFF141318))
                                .padding(1.5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF26252C)),
                        )
                    }
                }
                Spacer(Modifier.width(if (small) 8.dp else 10.dp))
            }
            Column {
                Text(
                    item.group.label,
                    fontSize = if (small) 13.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (focused) Modifier.titleMarquee() else Modifier,
                )
                val liveNames = item.liveStreams.joinToString(" · ") { it.channelName ?: it.channelLogin.orEmpty() }
                if (liveNames.isNotEmpty()) {
                    Text(
                        liveNames,
                        fontSize = if (small) 11.sp else 13.sp,
                        color = TvColors.secondaryText,
                        maxLines = 1,
                        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (focused) Modifier.titleMarquee() else Modifier,
                    )
                }
                if (!item.sharedGame.isNullOrBlank()) {
                    Text(
                        item.sharedGame,
                        fontSize = if (small) 11.sp else 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (focused) Modifier.titleMarquee() else Modifier,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameRow(
    row: TvHomeRow<Game>,
    actions: TvHomeActions,
    firstRequester: FocusRequester?,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    cardModifier: (Int, String) -> Modifier = { _, _ -> Modifier },
    onCardSelected: (Int, String) -> Unit = { _, _ -> },
) {
    Column {
        RowTitle(row.title, row.icon, startPadding = startInset + 12.dp)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
        LazyRow(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .tvHorizontalFade(
                    startWidth = 56.dp,
                    endWidth = 56.dp,
                    scrolledStart = { state.canScrollBackward },
                    scrolledEnd = { state.canScrollForward },
                )
                .focusRestorer(),
            contentPadding = PaddingValues(start = startInset + 12.dp, end = endInset + 12.dp, top = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.items, key = { index, item -> item.id ?: index }) { index, game ->
                val itemKey = game.id ?: game.name ?: "game_$index"
                GameCard(
                    game = game,
                    actions = actions,
                    modifier = Modifier.leftEdge(index, actions.openMenu).then(if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier).then(cardModifier(index, itemKey))
                        .onFocusChanged { if (it.hasFocus) actions.onPictureFocused(game) },
                    onClick = {
                        onCardSelected(index, itemKey)
                        actions.openGame(game)
                    },
                    onLongClick = {
                        onCardSelected(index, itemKey)
                        actions.showGameActions(game)
                    },
                )
            }
            if (row.seeAllId != 0 || row.onSeeAll != null) {
                item(key = "seeAll") {
                    val seeAllIndex = row.items.size
                    SeeAllCard(width = 120.dp, aspect = 3f / 4f, modifier = cardModifier(seeAllIndex, "seeAll")) {
                        onCardSelected(seeAllIndex, "seeAll")
                        row.onSeeAll?.invoke() ?: actions.seeAll(row.seeAllId)
                    }
                }
            }
        }
        }
    }
}

/** Focused card: the frame, and a real growth while its preview plays (the other cards move to make room). */
@Composable
private fun FocusCard(
    width: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
    /** The preview of the card plays: it grows, its row gets taller and the next cards slide away. */
    grown: () -> Boolean = { false },
    /** Growth of a card with its preview: less with few big cards a row. */
    growth: Float = 1f,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // the growth of the card can be turned off in the Xtra TV settings
    val context = androidx.compose.ui.platform.LocalContext.current
    val zoom = remember { context.prefs().getBoolean(C.TV_CARD_ZOOM, false) }
    val big = zoom && focused && growth > 1f && grown()
    val animatedWidth by androidx.compose.animation.core.animateDpAsState(
        if (big) width * growth else width,
        animationSpec = androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "width"
    )
    // once grown, the whole card is brought on screen (it may go past the right edge)
    val bringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(big) {
        if (big) {
            kotlinx.coroutines.delay(340)
            bringIntoView.bringIntoView()
        }
    }
    var longPressed by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(animatedWidth)
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { onFocus(it.isFocused) }
            .onPreviewKeyEvent { event ->
                // OK held down: actions of the card
                val isSelect = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!isSelect || onLongClick == null) return@onPreviewKeyEvent false
                when {
                    event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 1 -> {
                        longPressed = true
                        onLongClick()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 1 -> true
                    event.type == KeyEventType.KeyUp && longPressed -> {
                        longPressed = false
                        true
                    }
                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        content(focused)
    }
}

/** The title of the focused card scrolls slowly when it is too long, after a second (not while going through a row). */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.titleMarquee(): Modifier = this.basicMarquee(
    iterations = Int.MAX_VALUE,
    initialDelayMillis = 1000,
    repeatDelayMillis = 1500,
    velocity = 40.dp,
)

@Composable
private fun StreamCard(
    width: Dp,
    stream: Stream,
    previewLogin: () -> String?,
    previewPlayer: () -> ExoPlayer?,
    actions: TvHomeActions,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = { actions.showActions(stream) },
) {
    // many cards a row: smaller texts, the count alone in the badge
    val small = width < 230.dp
    // only the card that shows the preview recomposes when the preview changes
    val previewing by remember(stream.channelLogin) { derivedStateOf { stream.channelLogin != null && previewLogin() == stream.channelLogin } }
    FocusCard(
        width = width,
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = { if (it) actions.onStreamFocused(stream) else actions.onStreamUnfocused(stream) },
        grown = { previewing && previewPlayer() != null },
        // 2 cards a row: already big, they do not grow; 3: half bigger, 4 and more: twice as big
        growth = when (actions.cardsPerRow.coerceIn(2, 6)) {
            2 -> 1f
            3 -> 1.5f
            else -> 2f
        },
    ) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .tvCardFocusGlow(focused, MaterialTheme.colorScheme.primary)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF26252C))
                .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            AsyncImage(
                // smaller thumbnails than the 1280x720 of the lists, the cards are small
                // the minute is in the address (TvThumbnails): the newest thumbnail of Twitch, loaded again each minute
                model = com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.picture(stream, stream.thumbnail?.replace("1280x720", "640x360")),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            val player = previewPlayer()
            if (previewing && player != null && focused) {
                AndroidView(
                    factory = { context -> TextureView(context) },
                    update = { player.setVideoTextureView(it) },
                    onRelease = {
                        // the last picture of the preview, shown while the stream opens
                        com.github.andreyasadchy.xtra.ui.tv.TvPreviewFrames.capture(stream.channelLogin, it)
                        runCatching { player.clearVideoTextureView(it) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // badges like the player: red "LIVE", then the viewers with the eye
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFFE91916))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text("LIVE", fontSize = if (small) 10.sp else 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                }
                stream.viewerCount?.let { count ->
                    Spacer(Modifier.width(5.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xB3000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(R.drawable.tv_ic_eye), contentDescription = null, tint = Color.White, modifier = Modifier.size(if (small) 11.dp else 12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(actions.formatCount(count), fontSize = if (small) 10.sp else 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                    }
                }
            }
            // dark gradient at the bottom of the picture
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x80000000))))
            )
            // how long the stream is live, at the bottom right
            actions.uptime(stream)?.let { uptime ->
                Text(
                    uptime,
                    fontSize = if (small) 10.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = stream.channelImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (small) 26.dp else 32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF26252C))
            )
            Spacer(Modifier.width(if (small) 8.dp else 10.dp))
            Column {
                // a star before the name of a favorite channel
                val favorites by com.github.andreyasadchy.xtra.ui.tv.TvFavorites.items.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (favorites.any { it.login == stream.channelLogin }) {
                        Icon(
                            painterResource(R.drawable.tv_ic_star),
                            contentDescription = null,
                            tint = Color(0xFFFACC15),
                            modifier = Modifier.size(if (small) 13.dp else 16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(stream.channelName ?: stream.channelLogin ?: "", fontSize = if (small) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // the whole title scrolls on the focused card when it is longer than the card
                Text(
                    stream.title?.trim() ?: "",
                    fontSize = if (small) 11.sp else 13.sp,
                    color = TvColors.secondaryText,
                    maxLines = 1,
                    overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (focused) Modifier.titleMarquee() else Modifier,
                )
                if (!stream.gameName.isNullOrBlank()) {
                    Text(
                        stream.gameName ?: "",
                        fontSize = if (small) 11.sp else 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (focused) Modifier.titleMarquee() else Modifier,
                    )
                }
            }
        }
    }
}

/** One statistic of the card: icon, value, label. */
class TvHomeStat(val icon: Int, val value: String, val label: String)

class TvHomeStats(val title: String, val subtitle: String?, val items: List<TvHomeStat>)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResumeRow(
    row: TvHomeRow<com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item>,
    actions: TvHomeActions,
    firstRequester: FocusRequester?,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    cardModifier: (Int, String) -> Modifier = { _, _ -> Modifier },
    onCardSelected: (Int, String) -> Unit = { _, _ -> },
) {
    Column {
        RowTitle(row.title, row.icon, startPadding = startInset + 12.dp)
        val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val count = actions.cardsPerRow.coerceIn(2, 6)
        val cardWidth = (screenWidth - 24.dp - 16.dp * count) / (count + 0.25f)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
            LazyRow(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvHorizontalFade(
                        startWidth = 56.dp,
                        endWidth = 56.dp,
                        scrolledStart = { state.canScrollBackward },
                        scrolledEnd = { state.canScrollForward },
                    )
                    .focusRestorer(),
                contentPadding = PaddingValues(start = startInset + 12.dp, end = endInset + 12.dp, top = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(row.items, key = { _, item -> item.id }) { index, item ->
                    ResumeCard(
                        width = cardWidth,
                        item = item,
                        actions = actions,
                        modifier = Modifier.leftEdge(index, actions.openMenu).then(if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier).then(cardModifier(index, item.id))
                            .onFocusChanged { if (it.hasFocus) actions.onPictureFocused(item) },
                        onSelected = { onCardSelected(index, item.id) },
                    )
                }
            }
        }
    }
}

/** Latest videos of the followed channels. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    row: TvHomeRow<com.github.andreyasadchy.xtra.model.ui.Video>,
    actions: TvHomeActions,
    firstRequester: FocusRequester?,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    cardModifier: (Int, String) -> Modifier = { _, _ -> Modifier },
    onCardSelected: (Int, String) -> Unit = { _, _ -> },
) {
    Column {
        RowTitle(row.title, row.icon, startPadding = startInset + 12.dp)
        val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val count = actions.cardsPerRow.coerceIn(2, 6)
        val cardWidth = (screenWidth - 24.dp - 16.dp * count) / (count + 0.25f)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
            LazyRow(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvHorizontalFade(
                        startWidth = 56.dp,
                        endWidth = 56.dp,
                        scrolledStart = { state.canScrollBackward },
                        scrolledEnd = { state.canScrollForward },
                    )
                    .focusRestorer(),
                contentPadding = PaddingValues(start = startInset + 12.dp, end = endInset + 12.dp, top = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(row.items, key = { index, item -> item.id ?: "video$index" }) { index, item ->
                    val itemKey = item.id ?: "video$index"
                    FollowedVideoCard(
                        width = cardWidth,
                        video = item,
                        actions = actions,
                        modifier = Modifier.leftEdge(index, actions.openMenu).then(if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier).then(cardModifier(index, itemKey))
                            .onFocusChanged { if (it.hasFocus) actions.onPictureFocused(item) },
                        onSelected = { onCardSelected(index, itemKey) },
                    )
                }
            }
        }
    }
}

/**
 * The picture of a video; a video without one yet (Twitch gives a "?" while it records or is processed): the avatar of
 * the streamer in the middle, over a blurred copy of it.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.VideoThumbnail(thumbnail: String?, channelImage: String?) {
    if (!com.github.andreyasadchy.xtra.ui.tv.TvVideoThumbnail.isMissing(thumbnail)) {
        AsyncImage(
            model = thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    if (channelImage != null) {
        AsyncImage(
            model = channelImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(28.dp).graphicsLayer { alpha = 0.55f }
        )
    }
    Box(Modifier.fillMaxSize().background(Color(0x59000000)))
    if (channelImage != null) {
        AsyncImage(
            model = channelImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.align(Alignment.Center).fillMaxHeight(0.5f).aspectRatio(1f).clip(CircleShape)
        )
    } else {
        Icon(
            painterResource(R.drawable.tv_ic_video_library),
            contentDescription = null,
            tint = Color(0x99FFFFFF),
            modifier = Modifier.align(Alignment.Center).size(40.dp)
        )
    }
}

/** Watch progress bar inset from the bottom edge with rounded corners so it stays visible inside the focus border. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.VideoProgressBar(progress: Float) {
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x66FFFFFF))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun FollowedVideoCard(
    width: Dp,
    video: com.github.andreyasadchy.xtra.model.ui.Video,
    actions: TvHomeActions,
    modifier: Modifier,
    onSelected: () -> Unit = {},
) {
    val small = width < 230.dp
    val positionMs = actions.videoPosition(video)
    FocusCard(
        width = width,
        modifier = modifier,
        onClick = {
            onSelected()
            actions.openFollowedVideo(video)
        },
        onLongClick = {
            onSelected()
            actions.showFollowedVideoActions(video)
        },
    ) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .tvCardFocusGlow(focused, MaterialTheme.colorScheme.primary)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF26252C))
                .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            VideoThumbnail(video.thumbnail, video.channelImage)
            val seconds = video.durationSeconds ?: 0
            if (seconds > 0) {
                Text(
                    actions.formatVideoDuration(seconds),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                val durationMs = seconds * 1000L
                if (positionMs != null && positionMs > 0L) {
                    VideoProgressBar(positionMs.toFloat() / durationMs)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            video.title.orEmpty(),
            fontSize = if (small) 13.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (focused) Modifier.titleMarquee() else Modifier,
        )
        Text(
            listOfNotNull(video.channelName, video.gameName?.takeIf { it.isNotBlank() }).joinToString(" · "),
            fontSize = if (small) 12.sp else 13.sp,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ResumeCard(
    width: Dp,
    item: com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item,
    actions: TvHomeActions,
    modifier: Modifier,
    onSelected: () -> Unit = {},
) {
    val small = width < 230.dp
    FocusCard(
        width = width,
        modifier = modifier,
        onClick = {
            onSelected()
            actions.openVideo(item)
        },
        onLongClick = {
            onSelected()
            actions.showVideoActions(item)
        },
    ) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .tvCardFocusGlow(focused, MaterialTheme.colorScheme.primary)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF26252C))
                .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            VideoThumbnail(item.thumbnail, item.channelImage)
            // time left, like the cards of Google TV
            val durationMs = (item.durationSeconds ?: 0) * 1000L
            if (durationMs > 0) {
                Text(
                    actions.formatDuration((durationMs - item.positionMs).coerceAtLeast(0)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                VideoProgressBar(item.positionMs.toFloat() / durationMs)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.channelImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (small) 26.dp else 32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF26252C))
            )
            Spacer(Modifier.width(if (small) 8.dp else 10.dp))
            Column {
                Text(
                    item.title?.trim() ?: "",
                    fontSize = if (small) 13.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (focused) Modifier.titleMarquee() else Modifier,
                )
                Text(item.channelName ?: item.channelLogin ?: "", fontSize = if (small) 11.sp else 13.sp, color = TvColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!item.gameName.isNullOrBlank()) {
                    Text(
                        item.gameName,
                        fontSize = if (small) 11.sp else 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (focused) Modifier.titleMarquee() else Modifier,
                    )
                }
            }
        }
    }
}

/** Card of statistics: the D-pad stops on it as a whole, it has no action. */
@Composable
private fun StatsCard(
    stats: TvHomeStats,
    actions: TvHomeActions,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(Modifier.padding(start = startInset + 12.dp, end = endInset + 12.dp, top = 8.dp, bottom = 8.dp)) {
        RowTitle(stats.title, R.drawable.tv_ic_bar_chart)
        Spacer(Modifier.height(4.dp))
        Column(
            modifier
                .fillMaxWidth()
                .leftEdge(0, actions.openMenu)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = if (focused) 0.38f else 0.24f), Color(0x14FFFFFF))
                    )
                )
                .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier)
                // OK: the page of the statistics in detail
                .clickable(interactionSource = interaction, indication = null) { actions.openStats() }
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                stats.items.forEach { stat ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x26000000))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Icon(painterResource(stat.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(stat.value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stat.label, fontSize = 12.sp, color = TvColors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stats.subtitle ?: "", fontSize = 12.sp, color = TvColors.secondaryText, modifier = Modifier.weight(1f))
                Text(
                    androidx.compose.ui.platform.LocalContext.current.getString(R.string.tv_stats_open),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focused) Color.White else TvColors.secondaryText,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipRow(
    row: TvHomeRow<com.github.andreyasadchy.xtra.model.ui.Clip>,
    actions: TvHomeActions,
    firstRequester: FocusRequester?,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    cardModifier: (Int, String) -> Modifier = { _, _ -> Modifier },
    onCardSelected: (Int, String) -> Unit = { _, _ -> },
) {
    Column {
        RowTitle(row.title, row.icon, startPadding = startInset + 12.dp)
        val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val count = actions.cardsPerRow.coerceIn(2, 6)
        val cardWidth = (screenWidth - 24.dp - 16.dp * count) / (count + 0.25f)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
            LazyRow(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvHorizontalFade(
                        startWidth = 56.dp,
                        endWidth = 56.dp,
                        scrolledStart = { state.canScrollBackward },
                        scrolledEnd = { state.canScrollForward },
                    )
                    .focusRestorer(),
                contentPadding = PaddingValues(start = startInset + 12.dp, end = endInset + 12.dp, top = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(row.items, key = { index, item -> item.id ?: "clip$index" }) { index, clip ->
                    val itemKey = clip.id ?: "clip$index"
                    ClipCard(
                        width = cardWidth,
                        clip = clip,
                        queue = row.items,
                        actions = actions,
                        modifier = Modifier
                            .leftEdge(index, actions.openMenu)
                            .then(if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier)
                            .then(cardModifier(index, itemKey))
                            .onFocusChanged { if (it.hasFocus) actions.onPictureFocused(clip) },
                        onSelected = { onCardSelected(index, itemKey) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipCard(
    width: Dp,
    clip: com.github.andreyasadchy.xtra.model.ui.Clip,
    queue: List<com.github.andreyasadchy.xtra.model.ui.Clip>,
    actions: TvHomeActions,
    modifier: Modifier,
    onSelected: () -> Unit = {},
) {
    val small = width < 230.dp
    FocusCard(
        width = width,
        modifier = modifier,
        onClick = {
            onSelected()
            actions.openClip(clip, queue)
        },
        onLongClick = {
            onSelected()
            actions.showClipActions(clip, queue)
        },
    ) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .tvCardFocusGlow(focused, MaterialTheme.colorScheme.primary)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF26252C))
                .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            VideoThumbnail(clip.thumbnail, clip.channelImage)
            clip.viewCount?.takeIf { it > 0 }?.let { count ->
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.tv_ic_eye),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (small) 11.dp else 12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        actions.formatCount(count),
                        fontSize = if (small) 10.sp else 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
            val seconds = clip.durationSeconds ?: 0
            if (seconds > 0) {
                Text(
                    actions.formatVideoDuration(seconds),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = clip.channelImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (small) 26.dp else 32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF26252C)),
            )
            Spacer(Modifier.width(if (small) 8.dp else 10.dp))
            Column {
                Text(
                    clip.title?.trim() ?: "",
                    fontSize = if (small) 13.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (focused) Modifier.titleMarquee() else Modifier,
                )
                Text(
                    clip.channelName ?: clip.channelLogin ?: "",
                    fontSize = if (small) 11.sp else 13.sp,
                    color = TvColors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!clip.gameName.isNullOrBlank()) {
                    Text(
                        clip.gameName ?: "",
                        fontSize = if (small) 11.sp else 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (focused) Modifier.titleMarquee() else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun GameCard(
    game: Game,
    actions: TvHomeActions,
    modifier: Modifier,
    onClick: () -> Unit = { actions.openGame(game) },
    onLongClick: () -> Unit = { actions.showGameActions(game) },
) {
    FocusCard(width = 120.dp, modifier = modifier, onClick = onClick, onLongClick = onLongClick) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .tvCardFocusGlow(focused, MaterialTheme.colorScheme.primary)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF26252C))
                .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            AsyncImage(
                model = game.boxArt,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(9.dp))
        // the whole category name scrolls on the focused card when it is longer than the card
        Text(
            game.name ?: "",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (focused) Modifier.titleMarquee() else Modifier,
        )
        game.viewerCount?.let {
            Text(actions.formatViewers(it), fontSize = 12.sp, color = TvColors.secondaryText, maxLines = 1)
        }
    }
}

@Composable
private fun SeeAllCard(width: Dp, aspect: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(width = width, modifier = modifier, onClick = onClick) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(10.dp))
                .background(if (focused) TvColors.focused else Color(0x1FFFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painterResource(R.drawable.tv_arrow_forward),
                    contentDescription = null,
                    tint = if (focused) TvColors.onFocused else Color.White,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    androidx.compose.ui.platform.LocalContext.current.getString(R.string.tv_see_all),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focused) TvColors.onFocused else Color.White
                )
            }
        }
    }
}
