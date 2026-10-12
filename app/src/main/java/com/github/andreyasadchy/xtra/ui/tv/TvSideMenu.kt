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

class TvMenuItem(val id: Int, val title: String, val icon: Int?, val group: Int)

/** State of the side menu, the activity fills it from the menu of the navigation bar. */
/** Stream playing in the mini player or as audio only, shown at the top of the side menu. */
class TvNowPlaying(val channelName: String?, val title: String?, val image: String?, val audioOnly: Boolean)

class TvMenuState {
    var items by mutableStateOf<List<TvMenuItem>>(emptyList())
    var nowPlaying by mutableStateOf<TvNowPlaying?>(null)
    var onNowPlayingClick: () -> Unit = {}
    var onNowPlayingClose: () -> Unit = {}
    var selected by mutableIntStateOf(0)
    internal val requesters = mutableMapOf<Int, FocusRequester>()

    /** Moves the focus to the screen being shown, else to the first entry. */
    fun focusSelected(): Boolean {
        val requester = requesters[selected] ?: items.firstOrNull()?.let { requesters[it.id] } ?: return false
        return try {
            requester.requestFocus()
            true
        } catch (e: IllegalStateException) {
            false
        }
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
 */
@Composable
fun TvSideMenu(state: TvMenuState, onClick: (Int) -> Unit) {
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val items = state.items
    Column(
        Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(Color(0xFF141318))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> focusedIndex <= 0
                    Key.DirectionDown -> focusedIndex >= items.lastIndex
                    Key.DirectionLeft -> true
                    else -> false
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 28.dp)
    ) {
        // "Now playing" between search and the sections
        val nowPlayingAt = items.indexOfFirst { it.group != TvMenuState.GROUP_TOP }.coerceAtLeast(0)
        items.forEachIndexed { index, item ->
            val playing = state.nowPlaying
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
                NowPlayingCard(playing, state, Modifier.onFocusChanged { if (it.hasFocus) focusedIndex = index })
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
            val requester = state.requesters.getOrPut(item.id) { FocusRequester() }
            MenuEntry(
                item = item,
                selected = item.id == state.selected,
                modifier = Modifier
                    .focusRequester(requester)
                    .onFocusChanged { if (it.isFocused) focusedIndex = index },
            ) {
                onClick(item.id)
            }
        }
    }
}

/** "Now playing": OK goes back to the stream, the button under it stops it. */
@Composable
private fun NowPlayingCard(playing: TvNowPlaying, state: TvMenuState, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        NowPlayingContent(playing, state)
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Row(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (focused) TvColors.focused else Color(0x1AFFFFFF))
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
private fun NowPlayingContent(playing: TvNowPlaying, state: TvMenuState) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) TvColors.focused else Color(0x1AFFFFFF))
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
    val background = when {
        focused -> TvColors.focused
        selected -> Color(0x2EFFFFFF)
        else -> Color.Transparent
    }
    val content = when {
        focused -> TvColors.onFocused
        selected -> Color.White
        else -> Color(0xB3FFFFFF)
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.icon != null) {
            Icon(painterResource(item.icon), contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Text(
            item.title,
            fontSize = 18.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
