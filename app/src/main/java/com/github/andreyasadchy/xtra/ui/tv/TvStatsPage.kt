package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * TV: the page of the statistics, opened from their card on the home: a period (7 days, 30 days, since the start),
 * the totals, the time by day, the streamers and the categories most watched, when it is watched, the records, the ads
 * blocked and the proxies. Up / down scroll it, the period and "Reset" are at the top.
 */
object TvStatsPage {

    fun show(context: Context, onReset: () -> Unit) {
        TvDialogs.show(context) { _ -> Page(onReset) }
    }

    private fun time(context: Context, ms: Long) = context.getString(R.string.tv_stats_hours, ms / 3_600_000L, (ms / 60_000L) % 60)

    @Composable
    private fun Page(onReset: () -> Unit) {
        val context = LocalContext.current
        var period by remember { mutableIntStateOf(1) }
        var version by remember { mutableIntStateOf(0) }
        val detail = remember(period, version) {
            TvStats.detail(context, when (period) { 0 -> 7; 1 -> 30; else -> null })
        }
        val first = remember { FocusRequester() }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(TvColors.accent.copy(alpha = 0.30f).compositeOver(Color(0xFF17131F)), Color(0xFF1E1630), Color(0xFF17131F))))
        ) {
            Column(Modifier.fillMaxSize().padding(start = 60.dp, end = 60.dp, top = 26.dp)) {
                // title, period, reset
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.tv_ic_bar_chart), contentDescription = null, tint = TvColors.accent, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(context.getString(R.string.tv_stats), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        if (detail.since > 0) {
                            Text(
                                context.getString(R.string.tv_stats_since, java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(detail.since))),
                                fontSize = 12.sp, color = TvColors.secondaryText
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        // the focus stays on these buttons: up / down scroll the page
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    scope.launch { scroll.animateScrollBy(160f * context.resources.displayMetrics.density) }
                                    true
                                }
                                Key.DirectionUp -> {
                                    scope.launch { scroll.animateScrollBy(-160f * context.resources.displayMetrics.density) }
                                    true
                                }
                                else -> false
                            }
                        }
                    ) {
                        listOf(R.string.tv_stats_period_7, R.string.tv_stats_period_30, R.string.tv_stats_period_all).forEachIndexed { index, label ->
                            Chip(context.getString(label), index == period, if (index == 1) Modifier.focusRequester(first) else Modifier) { period = index }
                        }
                        Spacer(Modifier.width(14.dp))
                        Chip(context.getString(R.string.tv_stats_reset), false, Modifier) {
                            context.getAlertDialogBuilder()
                                .setTitle(context.getString(R.string.tv_stats_reset))
                                .setMessage(context.getString(R.string.tv_stats_reset_message))
                                .setPositiveButton(context.getString(R.string.tv_stats_reset)) { _, _ ->
                                    TvStats.reset(context)
                                    version++
                                    onReset()
                                }
                                .setNegativeButton(context.getString(android.R.string.cancel), null)
                                .show()
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                // the page itself, up / down scroll it (the D-pad stays on the period)
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                ) {
                    Tiles(detail)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PerDay(detail, Modifier.weight(1.55f))
                        TopStreamers(detail, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TopGames(detail, Modifier.weight(1.05f))
                        WhenWatched(detail, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Records(detail, Modifier.weight(2.2f))
                        AdsAndProxies(detail, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }

    private fun Color.compositeOver(background: Color): Color {
        val a = alpha
        return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
    }

    @Composable
    private fun Chip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Box(
            modifier
                .clip(RoundedCornerShape(20.dp))
                .background(
                    when {
                        focused -> TvColors.focused
                        selected -> Color(0x26FFFFFF)
                        else -> Color.Transparent
                    }
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 7.dp)
        ) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    focused -> TvColors.onFocused
                    selected -> TvColors.accent
                    else -> Color(0xBFFFFFFF)
                }
            )
        }
    }

    @Composable
    private fun Panel(modifier: Modifier, content: @Composable () -> Unit) {
        Column(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x14FFFFFF))
                .padding(horizontal = 18.dp, vertical = 15.dp)
        ) { content() }
    }

    @Composable
    private fun PanelTitle(title: String, extra: String? = null) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            if (extra != null) {
                Text(extra, fontSize = 12.sp, color = TvColors.secondaryText)
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    @Composable
    private fun Tiles(detail: TvStats.Detail) {
        val context = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(R.drawable.tv_ic_schedule, time(context, detail.watchMs), context.getString(R.string.tv_stats_watched))
            Tile(R.drawable.tv_ic_live, detail.streams.toString(), context.getString(R.string.tv_stats_streams))
            Tile(R.drawable.tv_ic_shield, detail.ads.toString(), context.getString(R.string.tv_stats_ads))
            Tile(R.drawable.tv_ic_forum, detail.messages.toString(), context.getString(R.string.tv_stats_messages))
            Tile(R.drawable.tv_ic_trending, context.getString(R.string.tv_stats_days, detail.bestStreak), context.getString(R.string.tv_stats_streak))
        }
    }

    @Composable
    private fun RowScope.Tile(icon: Int, value: String, label: String) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x14FFFFFF))
                .padding(horizontal = 15.dp, vertical = 13.dp)
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = TvColors.accent, modifier = Modifier.size(21.dp))
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, fontSize = 12.sp, color = TvColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun PerDay(detail: TvStats.Detail, modifier: Modifier) {
        val context = LocalContext.current
        val days = detail.lastDays
        val counted = days.filter { it > 0 }
        val average = if (counted.isEmpty()) 0L else days.sum() / days.size
        val max = (days.maxOrNull() ?: 0L).coerceAtLeast(3_600_000L)
        Panel(modifier) {
            PanelTitle(context.getString(R.string.tv_stats_per_day), context.getString(R.string.tv_stats_average, time(context, average)))
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                // the average as a dashed line
                if (average > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = (150f * (1f - average.toFloat() / max)).dp.coerceAtLeast(0.dp))
                            .height(1.dp)
                            .background(TvColors.accent.copy(alpha = 0.6f))
                    )
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    days.forEachIndexed { index, ms ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight((ms.toFloat() / max).coerceIn(0.02f, 1f))
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(if (index == days.lastIndex) TvColors.accent.copy(alpha = 0.55f) else TvColors.accent)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text(context.getString(R.string.tv_stats_last_30), fontSize = 11.sp, color = TvColors.secondaryText, modifier = Modifier.weight(1f))
                Text(context.getString(R.string.tv_stats_today), fontSize = 11.sp, color = Color.White)
            }
        }
    }

    @Composable
    private fun TopStreamers(detail: TvStats.Detail, modifier: Modifier) {
        val context = LocalContext.current
        Panel(modifier) {
            PanelTitle(context.getString(R.string.tv_stats_top_streamers))
            val list = detail.streamers.take(5)
            if (list.isEmpty()) {
                Empty()
            }
            val top = list.firstOrNull()?.ms?.coerceAtLeast(1L) ?: 1L
            list.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 9.dp)) {
                    Text("${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0x8CFFFFFF), modifier = Modifier.width(16.dp))
                    AsyncImage(
                        model = item.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF26252C))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(item.name, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(time(context, item.ms), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(Modifier.height(4.dp))
                        Bar(item.ms.toFloat() / top)
                    }
                }
            }
        }
    }

    @Composable
    private fun Bar(fraction: Float) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x1AFFFFFF))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(TvColors.accent))
        }
    }

    @Composable
    private fun Empty() {
        Text(LocalContext.current.getString(R.string.tv_stats_empty), fontSize = 13.sp, color = TvColors.secondaryText)
    }

    @Composable
    private fun TopGames(detail: TvStats.Detail, modifier: Modifier) {
        val context = LocalContext.current
        Panel(modifier) {
            PanelTitle(context.getString(R.string.tv_stats_top_games))
            if (detail.games.isEmpty()) {
                Empty()
            }
            val total = detail.games.sumOf { it.ms }.coerceAtLeast(1L)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                detail.games.forEach { game ->
                    Column(Modifier.width(72.dp)) {
                        AsyncImage(
                            model = game.image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(72.dp).height(96.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF26252C))
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(game.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${time(context, game.ms)} · ${game.ms * 100 / total} %", fontSize = 10.sp, color = TvColors.secondaryText, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        Bar(game.ms.toFloat() / total)
                    }
                }
            }
        }
    }

    @Composable
    private fun WhenWatched(detail: TvStats.Detail, modifier: Modifier) {
        val context = LocalContext.current
        val max = (detail.hours.maxOrNull() ?: 0L).coerceAtLeast(1L)
        // short names of the days, monday first
        val symbols = java.text.DateFormatSymbols.getInstance().shortWeekdays
        val days = listOf(2, 3, 4, 5, 6, 7, 1).map { symbols[it].replaceFirstChar { c -> c.uppercase() } }
        Panel(modifier) {
            PanelTitle(context.getString(R.string.tv_stats_when))
            days.forEachIndexed { day, name ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    Text(name, fontSize = 10.sp, color = TvColors.secondaryText, maxLines = 1, modifier = Modifier.width(30.dp))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (hour in 0 until 24) {
                            val value = detail.hours[day * 24 + hour].toFloat() / max
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(11.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (value <= 0f) Color(0x0FFFFFFF) else TvColors.accent.copy(alpha = (0.15f + value * 0.85f).coerceAtMost(1f)))
                            )
                        }
                    }
                }
            }
            Row(Modifier.padding(start = 30.dp, top = 2.dp)) {
                listOf("0", "6", "12", "18", "23").forEachIndexed { index, label ->
                    Text(label, fontSize = 10.sp, color = TvColors.secondaryText, modifier = Modifier.weight(1f))
                }
            }
            // the evening hours most watched and the busiest day
            val total = detail.hours.sum()
            if (total > 0) {
                val bestHour = (0 until 24).maxByOrNull { hour -> (0 until 7).sumOf { detail.hours[it * 24 + hour] } } ?: 0
                val bestDay = (0 until 7).maxByOrNull { day -> (0 until 24).sumOf { detail.hours[day * 24 + it] } } ?: 0
                val longDays = java.text.DateFormatSymbols.getInstance().weekdays
                val dayName = longDays[listOf(2, 3, 4, 5, 6, 7, 1)[bestDay]]
                Spacer(Modifier.height(8.dp))
                Text(
                    context.getString(R.string.tv_stats_when_summary, bestHour, (bestHour + 2) % 24, dayName),
                    fontSize = 12.sp,
                    color = Color(0xE6FFFFFF),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(TvColors.accent.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
    }

    @Composable
    private fun Records(detail: TvStats.Detail, modifier: Modifier) {
        val context = LocalContext.current
        val date = { ms: Long -> java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(ms)) }
        Column(modifier) {
            Text(context.getString(R.string.tv_stats_records), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Record(
                    context.getString(R.string.tv_stats_longest),
                    if (detail.longestMs > 0) time(context, detail.longestMs) else "—",
                    if (detail.longestMs > 0) listOfNotNull(detail.longestName, date(detail.longestAt)).joinToString(" · ") else null
                )
                Record(
                    context.getString(R.string.tv_stats_busiest),
                    if (detail.busiestDayMs > 0) time(context, detail.busiestDayMs) else "—",
                    if (detail.busiestDayMs > 0) date(detail.busiestDay) else null
                )
                Record(
                    context.getString(R.string.tv_stats_streak),
                    context.getString(R.string.tv_stats_days, detail.bestStreak),
                    context.getString(R.string.tv_stats_streak_current, detail.currentStreak)
                )
            }
        }
    }

    @Composable
    private fun RowScope.Record(label: String, value: String, detail: String?) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x14FFFFFF))
                .padding(horizontal = 15.dp, vertical = 13.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.tv_ic_star), contentDescription = null, tint = Color(0xFFFACC15), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 12.sp, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            if (detail != null) {
                Text(detail, fontSize = 11.sp, color = TvColors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun AdsAndProxies(detail: TvStats.Detail, modifier: Modifier) {
        val context = LocalContext.current
        Panel(modifier) {
            Text(context.getString(R.string.tv_stats_ads), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            val max = (detail.adWeeks.maxOrNull() ?: 0).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
                detail.adWeeks.forEach { count ->
                    Box(Modifier.weight(1f).fillMaxHeight((count.toFloat() / max).coerceIn(0.04f, 1f)).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(TvColors.accent))
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(context.getString(R.string.tv_stats_ads_weeks, detail.adWeeks.lastOrNull() ?: 0), fontSize = 10.sp, color = TvColors.secondaryText)
            Spacer(Modifier.height(10.dp))
            Text(context.getString(R.string.tv_stats_proxy), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            if (detail.proxies.isEmpty()) {
                Empty()
            }
            detail.proxies.forEachIndexed { index, (name, uses) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 3.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (index == 0) TvColors.accent else Color(0x66FFFFFF)))
                    Spacer(Modifier.width(7.dp))
                    Text(name, fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(context.getString(R.string.tv_stats_times, uses), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
