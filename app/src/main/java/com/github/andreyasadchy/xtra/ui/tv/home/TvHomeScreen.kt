package com.github.andreyasadchy.xtra.ui.tv.home

import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import android.view.TextureView
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
    val seeAll: (Int) -> Unit,
    val openMenu: () -> Unit,
    val onStreamFocused: (Stream?) -> Unit,
    /** The card of a stream lost the focus: its preview stops (the focus may go to a card without preview). */
    val onStreamUnfocused: (Stream) -> Unit = {},
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
)

class TvHomeRow<T>(val title: String, val items: List<T>, val seeAllId: Int, val icon: Int? = null)

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
    followed: TvHomeRow<Stream>?,
    popular: TvHomeRow<Stream>?,
    games: TvHomeRow<Game>?,
    previewLogin: () -> String?,
    previewPlayer: () -> ExoPlayer?,
    actions: TvHomeActions,
    resume: TvHomeRow<com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item>? = null,
    stats: TvHomeStats? = null,
    /** Popular streams of one language each (Xtra TV settings), under Popular. */
    languageRows: List<TvHomeRow<Stream>> = emptyList(),
    /** Account for the greeting at the top right (no name: not logged in). */
    account: TvHomeAccount? = null,
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
    val rows = listOfNotNull(
        followed?.takeIf { it.items.isNotEmpty() }?.let { "followed" to it },
        popular?.takeIf { it.items.isNotEmpty() }?.let { "popular" to it },
    ) + languageRows.filter { it.items.isNotEmpty() }.map { "language_" + it.title to it }
    val hasCards = rows.isNotEmpty() || resume?.items?.isNotEmpty() == true || games?.items?.isNotEmpty() == true
    // "refresh" only takes the focus with up from the first row (or while nothing is loaded): coming into the home,
    // the focus never lands on it
    val refreshRequester = remember { FocusRequester() }
    var refreshAllowed by remember { mutableStateOf(false) }
    // item of the list that has the focus (0: the first row)
    var focusedItem by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    // state of each row of streams and its cards, to put the focus back on a card
    val rowStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var refreshFocused by remember { mutableStateOf(false) }
    // the rows came while "refresh" had the focus (first load): the focus goes to the first stream
    LaunchedEffect(hasCards) {
        if (hasCards && refreshFocused && !refreshAllowed) {
            focus.focusFirst()
        }
    }
    val request = focus.request
    LaunchedEffect(request, hasCards) {
        if (request == null || !hasCards) {
            return@LaunchedEffect
        }
        // the request is cleared once the focus is placed: clearing it first restarted this effect and stopped it
        // before the focus moved (stuck on "refresh")
        val done = { if (focus.request === request) focus.request = null }
        // the card of the stream watched last (same row first, else any row), else the first stream
        val login = if (request.toLast) focus.lastLogin else null
        val target = login?.let {
            val preferred = rows.indexOfFirst { (key, row) -> key == focus.lastRow && row.items.any { s -> s.channelLogin == it } }
            val rowIndex = if (preferred >= 0) preferred else rows.indexOfFirst { (_, row) -> row.items.any { s -> s.channelLogin == it } }
            rows.getOrNull(rowIndex)?.let { (key, row) -> Triple(rowIndex, key, row.items.indexOfFirst { s -> s.channelLogin == it }) }
        }
        if (target != null) {
            val (rowIndex, key, cardIndex) = target
            listState.scrollToItem(rowIndex, if (rowIndex <= 0) 0 else -peek)
            rowStates[key]?.scrollToItem(cardIndex)
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { cardRequesters["$key/$login"]?.requestFocus() }.getOrNull() == true) {
                done()
                return@LaunchedEffect
            }
        }
        listState.scrollToItem(0)
        rows.firstOrNull()?.let { (key, _) -> rowStates[key]?.scrollToItem(0) }
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { firstCard.requestFocus() }
        done()
    }
    Column(Modifier.fillMaxSize()) {
        // always on screen above the rows: refresh, then the greeting, the picture of the account and the time (only
        // to read, the focus never stops there)
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RefreshPill(
                actions,
                Modifier
                    .focusRequester(refreshRequester)
                    .focusProperties { canFocus = refreshAllowed || !hasCards }
                    .onFocusChanged {
                        refreshFocused = it.hasFocus
                        if (!it.hasFocus) refreshAllowed = false
                    }
                    .onPreviewKeyEvent { event ->
                        // down from "refresh": back to the card of the first row it came from
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && hasCards) {
                            focus.focusLast()
                            true
                        } else false
                    }
            )
            Spacer(Modifier.weight(1f))
            Greeting(account)
        }
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollSpec) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRestorer(firstCard)
                .onPreviewKeyEvent { event ->
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
            // first load: grey cards in place of the rows, like YouTube
            val skeletons = if (followed == null && popular == null && games == null) 2 else 0
            if (skeletons > 0) {
                items(skeletons, key = { "skeleton$it" }) {
                    SkeletonRow(actions)
                }
            }
            rows.forEachIndexed { rowIndex, (key, row) ->
                item(key = key) {
                    Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = rowIndex; bringRow(rowIndex) } }) {
                        StreamRow(
                            row, previewLogin, previewPlayer, actions, if (rowIndex == 0) firstCard else null,
                            state = rowStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() },
                            cardModifier = { stream ->
                                val login = stream.channelLogin
                                if (login != null) {
                                    Modifier
                                        .focusRequester(cardRequesters.getOrPut("$key/$login") { FocusRequester() })
                                        .onFocusChanged {
                                            if (it.hasFocus) {
                                                focus.lastLogin = login
                                                focus.lastRow = key
                                            }
                                        }
                                } else Modifier
                            },
                        )
                    }
                }
            }
            // continue watching, then the statistics, over the categories
            val resumeIndex = rows.size + skeletons
            val hasResume = resume != null && resume.items.isNotEmpty()
            if (hasResume) {
                item(key = "resume") {
                    Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = resumeIndex; bringRow(resumeIndex) } }) {
                        ResumeRow(resume!!, actions, if (rows.isEmpty()) firstCard else null)
                    }
                }
            }
            val statsIndex = resumeIndex + if (hasResume) 1 else 0
            if (stats != null) {
                item(key = "stats") {
                    Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = statsIndex; bringRow(statsIndex) } }) {
                        StatsCard(stats, actions)
                    }
                }
            }
            val gamesIndex = statsIndex + if (stats != null) 1 else 0
            if (games != null && games.items.isNotEmpty()) {
                item(key = "games") {
                    Box(Modifier.onFocusChanged { if (it.hasFocus) { focusedItem = gamesIndex; bringRow(gamesIndex) } }) {
                        GameRow(games, actions, if (rows.isEmpty() && !hasResume) firstCard else null)
                    }
                }
            }
        }
    }
    }
}

/**
 * Where the focus goes in the home: [focusFirst] the first stream of the first row (coming from the side menu),
 * [focusLast] the card of the stream watched last, else the first stream (the player closed).
 */
class TvHomeFocus {
    class Request(val toLast: Boolean)

    var request by mutableStateOf<Request?>(null)
    var lastLogin: String? = null
    var lastRow: String? = null

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
private fun Greeting(account: TvHomeAccount?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // the time, again at each new minute
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = System.currentTimeMillis()
        }
    }
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(greeting, fontSize = 15.sp, color = TvColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
        Spacer(Modifier.width(12.dp))
        val image = account?.image
        if (name != null && image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(34.dp).clip(CircleShape)
            )
        } else {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x26FFFFFF)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.tv_ic_person), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1
        )
    }
}

/** Grey cards shown while the home loads. */
@Composable
private fun SkeletonRow(actions: TvHomeActions) {
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val count = actions.cardsPerRow.coerceIn(2, 6)
    val cardWidth = (screenWidth - 24.dp - 16.dp * count) / (count + 0.25f)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        0.06f, 0.14f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "alpha"
    )
    Column(Modifier.padding(start = 12.dp, top = 14.dp, bottom = 8.dp)) {
        Box(Modifier.width(220.dp).height(22.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = alpha)))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(count) {
                Column(Modifier.width(cardWidth)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = alpha)))
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = alpha)))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.45f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = alpha)))
                }
            }
        }
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

@Composable
private fun RowTitle(title: String, icon: Int? = null) {
    Row(
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 8.dp),
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
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    cardModifier: (Stream) -> Modifier = { Modifier },
) {
    Column {
        RowTitle(row.title, row.icon)
        // the cards fill the width of the screen, with a part of the next one in sight
        val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val count = actions.cardsPerRow.coerceIn(2, 6)
        val cardWidth = (screenWidth - 24.dp - 16.dp * count) / (count + 0.25f)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
        LazyRow(
            state = state,
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.items, key = { index, item -> item.channelId ?: index }) { index, stream ->
                StreamCard(
                    width = cardWidth,
                    stream = stream,
                    previewLogin = previewLogin,
                    previewPlayer = previewPlayer,
                    actions = actions,
                    modifier = Modifier.leftEdge(index, actions.openMenu).then(if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier).then(cardModifier(stream)),
                    onClick = { actions.openStream(stream, row.items) },
                )
            }
            if (row.seeAllId != 0) {
                item(key = "seeAll") {
                    SeeAllCard(width = 220.dp, aspect = 16f / 9f) { actions.seeAll(row.seeAllId) }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameRow(row: TvHomeRow<Game>, actions: TvHomeActions, firstRequester: FocusRequester?) {
    Column {
        RowTitle(row.title, row.icon)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.items, key = { index, item -> item.id ?: index }) { index, game ->
                GameCard(game, actions, Modifier.leftEdge(index, actions.openMenu).then(if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier))
            }
            item(key = "seeAll") {
                SeeAllCard(width = 120.dp, aspect = 3f / 4f) { actions.seeAll(row.seeAllId) }
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

@Composable
private fun StreamCard(
    width: Dp,
    stream: Stream,
    previewLogin: () -> String?,
    previewPlayer: () -> ExoPlayer?,
    actions: TvHomeActions,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // many cards a row: smaller texts, the count alone in the badge
    val small = width < 230.dp
    // only the card that shows the preview recomposes when the preview changes
    val previewing by remember(stream.channelLogin) { derivedStateOf { stream.channelLogin != null && previewLogin() == stream.channelLogin } }
    FocusCard(
        width = width,
        modifier = modifier,
        onClick = onClick,
        onLongClick = { actions.showActions(stream) },
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
                .then(if (focused) Modifier.shadow(18.dp, RoundedCornerShape(10.dp), ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary) else Modifier)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF26252C))
                .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            AsyncImage(
                // smaller thumbnails than the 1280x720 of the lists, the cards are small
                model = com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.picture(stream, stream.thumbnail?.replace("1280x720", "640x360")?.let { url ->
                    val stamp = actions.thumbnailStamp()
                    if (stamp > 0) "$url?t=$stamp" else url
                }),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            val player = previewPlayer()
            if (previewing && player != null && focused) {
                AndroidView(
                    factory = { context -> TextureView(context) },
                    update = { player.setVideoTextureView(it) },
                    onRelease = { player.clearVideoTextureView(it) },
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
        }
        Spacer(Modifier.height(8.dp))
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
                Text(stream.title?.trim() ?: "", fontSize = if (small) 11.sp else 13.sp, color = TvColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!stream.gameName.isNullOrBlank()) {
                    Text(stream.gameName ?: "", fontSize = if (small) 11.sp else 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun ResumeRow(row: TvHomeRow<com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item>, actions: TvHomeActions, firstRequester: FocusRequester?) {
    Column {
        RowTitle(row.title, row.icon)
        val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val count = actions.cardsPerRow.coerceIn(2, 6)
        val cardWidth = (screenWidth - 24.dp - 16.dp * count) / (count + 0.25f)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
            LazyRow(
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(row.items, key = { _, item -> item.id }) { index, item ->
                    ResumeCard(
                        width = cardWidth,
                        item = item,
                        actions = actions,
                        modifier = Modifier.leftEdge(index, actions.openMenu).then(if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResumeCard(width: Dp, item: com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item, actions: TvHomeActions, modifier: Modifier) {
    val small = width < 230.dp
    FocusCard(
        width = width,
        modifier = modifier,
        onClick = { actions.openVideo(item) },
        onLongClick = { actions.showVideoActions(item) },
    ) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .then(if (focused) Modifier.shadow(18.dp, RoundedCornerShape(10.dp), ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary) else Modifier)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF26252C))
                .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
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
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x66FFFFFF))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((item.positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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
                Text(item.title?.trim() ?: "", fontSize = if (small) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.channelName ?: item.channelLogin ?: "", fontSize = if (small) 11.sp else 13.sp, color = TvColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!item.gameName.isNullOrBlank()) {
                    Text(item.gameName, fontSize = if (small) 11.sp else 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Card of statistics: the D-pad stops on it as a whole, it has no action. */
@Composable
private fun StatsCard(stats: TvHomeStats, actions: TvHomeActions) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        RowTitle(stats.title, R.drawable.tv_ic_bar_chart)
        Column(
            Modifier
                .fillMaxWidth()
                .leftEdge(0, actions.openMenu)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = if (focused) 0.38f else 0.24f), Color(0x14FFFFFF))
                    )
                )
                .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier)
                .focusable(interactionSource = interaction)
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
            if (stats.subtitle != null) {
                Spacer(Modifier.height(10.dp))
                Text(stats.subtitle, fontSize = 12.sp, color = TvColors.secondaryText)
            }
        }
    }
}

@Composable
private fun GameCard(game: Game, actions: TvHomeActions, modifier: Modifier) {
    FocusCard(width = 120.dp, modifier = modifier, onClick = { actions.openGame(game) }) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .then(if (focused) Modifier.shadow(18.dp, RoundedCornerShape(10.dp), ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary) else Modifier)
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
        Spacer(Modifier.height(6.dp))
        Text(game.name ?: "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        game.viewerCount?.let {
            Text(actions.formatViewers(it), fontSize = 12.sp, color = TvColors.secondaryText, maxLines = 1)
        }
    }
}

@Composable
private fun SeeAllCard(width: Dp, aspect: Float, onClick: () -> Unit) {
    FocusCard(width = width, onClick = onClick) { focused ->
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
