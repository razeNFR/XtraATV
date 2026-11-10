package com.github.andreyasadchy.xtra.ui.channel.schedule

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentArgs
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvColors
import com.github.andreyasadchy.xtra.ui.tv.TvDialogs
import com.github.andreyasadchy.xtra.ui.view.TvHeader
import com.github.andreyasadchy.xtra.ui.view.TvUi
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.time.Instant

class ChannelScheduleViewModel(
    private val graphQLRepository: GraphQLRepository,
) : ViewModel() {

    val segments = MutableStateFlow<List<GraphQLRepository.ScheduleSegmentItem>?>(null)
    private var isLoading = false

    fun loadSchedule(
        channelId: String?,
        channelLogin: String?,
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
        force: Boolean = false,
    ) {
        if ((segments.value == null || force) && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                val loaded = graphQLRepository.loadChannelSchedule(
                    networkLibrary = networkLibrary,
                    headers = gqlHeaders,
                    channelId = channelId,
                    channelLogin = channelLogin,
                ).filter { item ->
                    val startMs = item.startAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: 0L
                    val endMs = item.endAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
                    if (endMs != null && endMs > 0L) {
                        endMs >= now - 15 * 60_000L
                    } else {
                        startMs >= now - 4 * 3_600_000L
                    }
                }.sortedBy { item ->
                    item.startAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: Long.MAX_VALUE
                }.take(24)
                segments.value = loaded
                isLoading = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as XtraApp
                ChannelScheduleViewModel(app.xtraModule.graphQLRepository)
            }
        }
    }
}

class ChannelScheduleFragment : BaseNetworkFragment() {

    private val args: ChannelPagerFragmentArgs by navArgs()
    private val viewModel: ChannelScheduleViewModel by viewModels { ChannelScheduleViewModel.Factory }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val isTv = TvUi.isTv(requireContext())
        val firstRequester = FocusRequester()
        lateinit var composeView: ComposeView
        composeView = TvDialogs.composeView(requireContext()) {
            ChannelScheduleContent(
                viewModel = viewModel,
                isTv = isTv,
                firstRequester = firstRequester,
                onFocusHeader = { TvHeader.focusHeaderOf(composeView) },
                onOpenMenu = { (activity as? MainActivity)?.openTvMenu() },
                onOpenCategory = { gameId, gameSlug, gameName ->
                    if (gameId.isNullOrBlank() && gameSlug.isNullOrBlank() && gameName.isNullOrBlank()) return@ChannelScheduleContent
                    if (isTv) {
                        (activity as? MainActivity)?.openTvGame(gameId, gameSlug, gameName)
                    } else {
                        val usePager = requireContext().prefs().getBoolean(C.UI_GAME_PAGER, true)
                        findNavController().navigate(
                            if (usePager) {
                                GamePagerFragmentDirections.actionGlobalGamePagerFragment(gameId = gameId, gameSlug = gameSlug, gameName = gameName)
                            } else {
                                GameMediaFragmentDirections.actionGlobalGameMediaFragment(gameId = gameId, gameSlug = gameSlug, gameName = gameName)
                            }
                        )
                    }
                },
            )
        }
        if (isTv) {
            TvHeader.registerTabContentFocus(composeView) {
                runCatching { firstRequester.requestFocus() }.isSuccess
            }
        }
        return composeView
    }

    override fun initialize() {
        viewModel.loadSchedule(
            channelId = args.channelId,
            channelLogin = args.channelLogin,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
        )
    }

    override fun onNetworkRestored() {
        viewModel.loadSchedule(
            channelId = args.channelId,
            channelLogin = args.channelLogin,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            force = true,
        )
    }
}

@Composable
private fun ChannelScheduleContent(
    viewModel: ChannelScheduleViewModel,
    isTv: Boolean,
    firstRequester: FocusRequester,
    onFocusHeader: () -> Boolean,
    onOpenMenu: () -> Unit,
    onOpenCategory: (id: String?, slug: String?, name: String?) -> Unit,
) {
    val segments by viewModel.segments.collectAsState()
    val context = LocalContext.current
    val horizontalPad = if (isTv) 36.dp else 16.dp

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = horizontalPad, end = horizontalPad, top = 14.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val current = segments
        if (current != null && current.isEmpty()) {
            item(key = "schedule_empty") {
                ScheduleCardShell(
                    isTv = isTv,
                    isFirst = true,
                    firstRequester = firstRequester,
                    onFocusHeader = onFocusHeader,
                    onOpenMenu = onOpenMenu,
                    onClick = {},
                ) { focused ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.tv_ic_schedule),
                            contentDescription = null,
                            tint = if (focused) TvColors.onFocused else TvColors.secondaryText,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = stringResource(R.string.tv_schedule_empty),
                            fontSize = 15.sp,
                            color = if (focused) TvColors.onFocused else TvColors.secondaryText,
                        )
                    }
                }
            }
        } else if (current != null) {
            itemsIndexed(current, key = { _, seg -> seg.id }) { index, seg ->
                val timeLabel = remember(seg.startAt, seg.endAt) {
                    val startMs = seg.startAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
                    val endMs = seg.endAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
                    if (startMs != null && startMs > 0L) {
                        val datePart = TwitchApiHelper.formatDate(context, startMs)
                        val timeFmt = DateFormat.getTimeFormat(context)
                        val startTime = timeFmt.format(Date(startMs))
                        val endTime = if (endMs != null && endMs > startMs) " – ${timeFmt.format(Date(endMs))}" else ""
                        "$datePart · $startTime$endTime"
                    } else {
                        ""
                    }
                }
                ScheduleCardShell(
                    isTv = isTv,
                    isFirst = index == 0,
                    firstRequester = firstRequester,
                    onFocusHeader = onFocusHeader,
                    onOpenMenu = onOpenMenu,
                    onClick = { onOpenCategory(seg.categoryId, seg.categorySlug, seg.categoryName) },
                ) { focused ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (!seg.boxArtUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = remember(seg.boxArtUrl) {
                                    ImageRequest.Builder(context)
                                        .data(seg.boxArtUrl)
                                        .crossfade(true)
                                        .build()
                                },
                                contentDescription = seg.categoryName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(52.dp)
                                    .height(70.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(16.dp))
                        } else {
                            Box(
                                modifier = Modifier
                                    .width(52.dp)
                                    .height(70.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x1FFFFFFF)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.tv_ic_schedule),
                                    contentDescription = null,
                                    tint = if (focused) TvColors.onFocused else TvColors.accent,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            if (timeLabel.isNotEmpty()) {
                                Text(
                                    text = timeLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (focused) TvColors.onFocused else TvColors.accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                            }
                            Text(
                                text = seg.title?.takeIf { it.isNotBlank() }
                                    ?: seg.categoryName?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.tv_schedule),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (focused) TvColors.onFocused else Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!seg.categoryName.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = seg.categoryName,
                                    fontSize = 13.sp,
                                    color = if (focused) TvColors.onFocused.copy(alpha = 0.8f) else TvColors.secondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleCardShell(
    isTv: Boolean,
    isFirst: Boolean,
    firstRequester: FocusRequester,
    onFocusHeader: () -> Boolean,
    onOpenMenu: () -> Unit,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && isTv) 1.015f else 1f, label = "schedScale")
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isFirst && isTv) Modifier.focusRequester(firstRequester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused && isTv) TvColors.focused else Color(0x1AFFFFFF))
            .border(
                width = if (focused && isTv) 2.dp else 1.dp,
                color = if (focused && isTv) TvColors.accent else Color(0x1FFFFFFF),
                shape = shape,
            )
            .then(
                if (isTv) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            if (isFirst && event.key == Key.DirectionUp) {
                                return@onPreviewKeyEvent onFocusHeader()
                            }
                            if (event.key == Key.DirectionLeft) {
                                onOpenMenu()
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
                } else {
                    Modifier
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        content(focused && isTv)
    }
}
