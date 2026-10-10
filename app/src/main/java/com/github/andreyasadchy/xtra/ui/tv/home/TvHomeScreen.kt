package com.github.andreyasadchy.xtra.ui.tv.home

import android.view.TextureView
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    val openChannel: (Stream) -> Unit,
    val openGame: (Game) -> Unit,
    val seeAll: (Int) -> Unit,
    val openMenu: () -> Unit,
    val onStreamFocused: (Stream?) -> Unit,
    val formatViewers: (Int) -> String,
)

class TvHomeRow<T>(val title: String, val items: List<T>, val seeAllId: Int)

/** Pivot like Leanback: the focused row or card stays at the same place, with the ones before and after in sight. */
@OptIn(ExperimentalFoundationApi::class)
private val PivotSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = containerSize * 0.12f
        return if (size > containerSize - pivot) offset else offset - pivot
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvHomeScreen(
    followed: TvHomeRow<Stream>?,
    popular: TvHomeRow<Stream>?,
    games: TvHomeRow<Game>?,
    previewLogin: String?,
    previewPlayer: ExoPlayer?,
    actions: TvHomeActions,
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (followed != null && followed.items.isNotEmpty()) {
                item(key = "followed") {
                    StreamRow(followed, previewLogin, previewPlayer, actions)
                }
            }
            if (popular != null && popular.items.isNotEmpty()) {
                item(key = "popular") {
                    StreamRow(popular, previewLogin, previewPlayer, actions)
                }
            }
            if (games != null && games.items.isNotEmpty()) {
                item(key = "games") {
                    GameRow(games, actions)
                }
            }
        }
    }
}

@Composable
private fun RowTitle(title: String) {
    Text(
        title,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 8.dp)
    )
}

/** Left on the first card opens the side menu. */
private fun Modifier.leftEdge(index: Int, openMenu: () -> Unit) = onPreviewKeyEvent { event ->
    if (index == 0 && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
        openMenu()
        true
    } else false
}

@Composable
private fun StreamRow(row: TvHomeRow<Stream>, previewLogin: String?, previewPlayer: ExoPlayer?, actions: TvHomeActions) {
    Column {
        RowTitle(row.title)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.items, key = { index, item -> item.channelId ?: index }) { index, stream ->
                StreamCard(
                    stream = stream,
                    previewing = previewLogin != null && previewLogin == stream.channelLogin,
                    previewPlayer = previewPlayer,
                    actions = actions,
                    modifier = Modifier.leftEdge(index, actions.openMenu),
                    onClick = { actions.openStream(stream, row.items) },
                )
            }
            item(key = "seeAll") {
                SeeAllCard(width = 220.dp, aspect = 16f / 9f) { actions.seeAll(row.seeAllId) }
            }
        }
    }
}

@Composable
private fun GameRow(row: TvHomeRow<Game>, actions: TvHomeActions) {
    Column {
        RowTitle(row.title)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.items, key = { index, item -> item.id ?: index }) { index, game ->
                GameCard(game, actions, Modifier.leftEdge(index, actions.openMenu))
            }
            item(key = "seeAll") {
                SeeAllCard(width = 120.dp, aspect = 3f / 4f) { actions.seeAll(row.seeAllId) }
            }
        }
    }
}

/** Card zoom and frame of the focused card. */
@Composable
private fun FocusCard(
    width: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "scale")
    var longPressed by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale }
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
    stream: Stream,
    previewing: Boolean,
    previewPlayer: ExoPlayer?,
    actions: TvHomeActions,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FocusCard(
        width = 260.dp,
        modifier = modifier,
        onClick = onClick,
        onLongClick = { actions.openChannel(stream) },
        onFocus = { if (it) actions.onStreamFocused(stream) },
    ) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF26252C))
                .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            AsyncImage(
                model = stream.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (previewing && previewPlayer != null && focused) {
                AndroidView(
                    factory = { context -> TextureView(context) },
                    update = { previewPlayer.setVideoTextureView(it) },
                    onRelease = { previewPlayer.clearVideoTextureView(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE91916))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(stream.viewerCount?.let { actions.formatViewers(it) } ?: "LIVE", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = stream.channelImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF26252C))
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stream.channelName ?: stream.channelLogin ?: "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stream.title?.trim() ?: "", fontSize = 13.sp, color = TvColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!stream.gameName.isNullOrBlank()) {
                    Text(stream.gameName ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
