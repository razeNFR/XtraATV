package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.google.android.material.color.MaterialColors
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * TV dialogs written with Compose: side panels like the ones of Google TV, the focus is always inside the panel
 * and starts on the selected entry.
 */
object TvDialogs {

    fun show(context: Context, content: @Composable (dismiss: () -> Unit) -> Unit): ComponentDialog {
        val dialog = object : ComponentDialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
            // opened with OK held down (long press on a card): that press is not for the panel,
            // its repeats and its release are dropped until OK is pressed again
            private var dropHeldSelect = true

            override fun onStart() {
                super.onStart()
                TvToast.onDialogShown(this)
            }

            override fun onStop() {
                TvToast.onDialogDismissed(this)
                super.onStop()
            }

            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                val isSelect = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                if (dropHeldSelect && isSelect) {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        dropHeldSelect = false
                    } else {
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.rgb(145, 71, 255))
        dialog.setContentView(ComposeView(context).apply {
            setContent {
                TvTheme(accent) {
                    content { dialog.dismiss() }
                }
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
        }
        dialog.show()
        return dialog
    }

    /** Compose view with the TV theme, to put Compose parts in the existing screens. */
    fun composeView(context: Context, content: @Composable () -> Unit): ComposeView {
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.rgb(145, 71, 255))
        return ComposeView(context).apply {
            setContent {
                TvTheme(accent, content)
            }
        }
    }

    /**
     * Replaces a search view by the TV search bar (field + mic): the text goes through the search view,
     * so its listeners keep working.
     */
    fun replaceSearchView(searchView: androidx.appcompat.widget.SearchView, hint: String): ComposeView? {
        val parent = searchView.parent as? ViewGroup ?: return null
        val view = composeView(searchView.context) {
            TvSearchBar(
                initialQuery = searchView.query?.toString().orEmpty(),
                hint = hint,
                onQueryChange = { searchView.setQuery(it, false) },
                onSubmit = { searchView.setQuery(it, true) },
            )
        }
        view.id = com.github.andreyasadchy.xtra.R.id.tvSearchBar
        val index = parent.indexOfChild(searchView)
        searchView.visibility = android.view.View.GONE
        parent.addView(view, index + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return view
    }

    /** One choice among a list (sort, filters...), the focus starts on the current choice. */
    fun showChoices(
        context: Context,
        title: String,
        options: List<String>,
        selected: Int,
        icons: List<Int?>? = null,
        colors: List<Int?>? = null,
        subtitle: String? = null,
        onSelected: (Int) -> Unit,
    ): ComponentDialog {
        return show(context) { dismiss ->
            SidePanel(
                title = title,
                subtitle = subtitle,
                width = if (subtitle != null) 500.dp else 440.dp,
            ) {
                ChoiceList(options, selected, icons, colors) {
                    dismiss()
                    onSelected(it)
                }
            }
        }
    }
}

@Composable
fun TvTheme(accent: Int, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            // the ambiance color of the Xtra TV settings when one is chosen
            // read here: the theme (and all that uses its primary color) follows the dynamic ambiance
            primary = ComposeColor(if (TvAccent.current != null) TvAccent.onDark else accent),
            onPrimary = ComposeColor(if (TvAccent.current != null) TvAccent.onAccent else android.graphics.Color.WHITE),
            surface = ComposeColor(0xFF1C1B22),
            onSurface = ComposeColor.White,
        ),
        content = content
    )
}

object TvColors {
    val panel = ComposeColor(0xF2141318)
    val item = ComposeColor(0x14FFFFFF)
    val focused = ComposeColor(0xFFF2F2F7)
    /** Text and icons of a focused (white) item: the ambiance color, dark enough to read. */
    val onFocused get() = ComposeColor(TvAccent.onLight)
    /** The ambiance color (violet of Twitch without one). */
    val accent get() = ComposeColor(TvAccent.onDark)
    val secondaryText = ComposeColor(0xB3FFFFFF)
}

/** Panel on one side of the screen, full height. */
@Composable
fun SidePanel(
    title: String,
    subtitle: String? = null,
    alignEnd: Boolean = true,
    width: Dp = 440.dp,
    // true for panels made of plain rows; lists that scroll by themselves (LazyColumn) must not be put in a scrolling panel
    scrollable: Boolean = false,
    scrollState: androidx.compose.foundation.ScrollState? = null,
    compactHeader: Boolean = false,
    headerExtra: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .width(width)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        if (alignEnd) listOf(ComposeColor(0xE6141318), TvColors.panel) else listOf(TvColors.panel, ComposeColor(0xE6141318))
                    )
                )
                .padding(horizontal = if (compactHeader) 18.dp else 32.dp, vertical = if (compactHeader) 22.dp else 36.dp)
        ) {
            Text(
                title,
                fontSize = if (compactHeader) 21.sp else 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = ComposeColor.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 14.sp, color = TvColors.secondaryText)
            }
            if (headerExtra != null) {
                Spacer(Modifier.height(10.dp))
                headerExtra()
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(if (compactHeader) 12.dp else 24.dp))
            }
            if (scrollable) {
                val effectiveScroll = scrollState ?: androidx.compose.foundation.rememberScrollState()
                // the rows scroll, the focused one is always brought into the screen
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(effectiveScroll)
                ) {
                    content()
                }
            } else if (footer != null) {
                Column(Modifier.weight(1f)) {
                    content()
                }
            } else {
                content()
            }
            if (footer != null) {
                Spacer(Modifier.height(8.dp))
                footer()
            }
        }
    }
}

/**
 * Segmented tab bar at the top of a side panel: moving D-pad Left / Right switches the active tab immediately
 * without pressing OK, clamps cleanly at the ends, and routes Down to [downTarget] and Up to [upTarget] (or stays put).
 */
@Composable
fun PanelTabs(
    tabs: List<Pair<Int?, String>>,
    selectedTab: Int,
    tabRequesters: List<FocusRequester>,
    downTarget: FocusRequester,
    upTarget: FocusRequester? = null,
    onSelect: (Int) -> Unit,
) {
    val latestDown by androidx.compose.runtime.rememberUpdatedState(downTarget)
    val latestUp by androidx.compose.runtime.rememberUpdatedState(upTarget)
    val latestOnSelect by androidx.compose.runtime.rememberUpdatedState(onSelect)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { index, (icon, label) ->
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val active = index == selectedTab
            val bg = when {
                focused -> TvColors.focused
                active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                else -> ComposeColor(0x14FFFFFF)
            }
            val borderCol = when {
                focused -> ComposeColor.Transparent
                active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                else -> ComposeColor(0x1FFFFFFF)
            }
            val textCol = when {
                focused -> TvColors.onFocused
                active -> ComposeColor.White
                else -> ComposeColor(0xB8FFFFFF)
            }
            val iconCol = when {
                focused -> TvColors.onFocused
                active -> MaterialTheme.colorScheme.primary
                else -> ComposeColor(0xB8FFFFFF)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .focusRequester(tabRequesters[index])
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (index > 0) {
                                    val prev = index - 1
                                    latestOnSelect(prev)
                                    runCatching { tabRequesters[prev].requestFocus() }
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                if (index < tabs.lastIndex) {
                                    val next = index + 1
                                    latestOnSelect(next)
                                    runCatching { tabRequesters[next].requestFocus() }
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                runCatching { latestDown.requestFocus() }
                                true
                            }
                            Key.DirectionUp -> {
                                latestUp?.let { runCatching { it.requestFocus() } }
                                true
                            }
                            else -> false
                        }
                    }
                    .clip(RoundedCornerShape(999.dp))
                    .background(bg)
                    .border(1.dp, borderCol, RoundedCornerShape(999.dp))
                    .clickable(interactionSource = interaction, indication = null) {
                        latestOnSelect(index)
                        runCatching { latestDown.requestFocus() }
                    }
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                LaunchedEffect(focused) {
                    if (focused && selectedTab != index) {
                        latestOnSelect(index)
                    }
                }
                if (icon != null) {
                    androidx.tv.material3.Icon(
                        androidx.compose.ui.res.painterResource(icon),
                        contentDescription = null,
                        tint = iconCol,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (active || focused) FontWeight.SemiBold else FontWeight.Medium,
                    color = textCol,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Compact horizontal action button for pinned side-panel footers, with optional icon and explicit D-pad neighbors. */
@Composable
fun androidx.compose.foundation.layout.RowScope.FooterActionChip(
    label: String,
    icon: Int? = null,
    requester: FocusRequester? = null,
    leftTarget: FocusRequester? = null,
    rightTarget: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "footerScale")
    val textColor = if (focused) TvColors.onFocused else ComposeColor.White
    val latestLeft by androidx.compose.runtime.rememberUpdatedState(leftTarget)
    val latestRight by androidx.compose.runtime.rememberUpdatedState(rightTarget)
    val latestUp by androidx.compose.runtime.rememberUpdatedState(upTarget)
    Row(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        latestLeft?.let { runCatching { it.requestFocus() } }
                        true
                    }
                    Key.DirectionRight -> {
                        latestRight?.let { runCatching { it.requestFocus() } }
                        true
                    }
                    Key.DirectionUp -> {
                        val up = latestUp
                        if (up != null) {
                            runCatching { up.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionDown -> true
                    else -> false
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) TvColors.focused else TvColors.item)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            androidx.tv.material3.Icon(
                androidx.compose.ui.res.painterResource(icon),
                contentDescription = null,
                tint = if (focused) TvColors.onFocused else ComposeColor(0xDCFFFFFF),
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Row that is white when it has the focus, like the menus of Google TV. */
@Composable
fun FocusRow(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "scale")
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 3.dp else 4.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) TvColors.focused else TvColors.item)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                else Modifier.focusable(interactionSource = interaction)
            )
            .padding(horizontal = if (compact) 13.dp else 18.dp, vertical = if (compact) 9.dp else 14.dp)
    ) {
        content(focused)
    }
}

@Composable
fun ChoiceList(options: List<String>, selected: Int, icons: List<Int?>? = null, colors: List<Int?>? = null, onSelected: (Int) -> Unit) {
    val requester = remember { FocusRequester() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 2).coerceAtLeast(0))
    LazyColumn(state = state) {
        itemsIndexed(options) { index, option ->
            FocusRow(
                modifier = if (index == selected.coerceIn(0, options.lastIndex)) Modifier.focusRequester(requester) else Modifier,
                onClick = { onSelected(index) }
            ) { focused ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // actions (icons given): an icon in front of each one, choices: a radio dot
                    val icon = icons?.getOrNull(index)
                    if (colors != null) {
                        // colors: a dot of the color, ringed when it is the current one ("none": an empty ring)
                        ColorDot(colors.getOrNull(index), index == selected, focused)
                    } else if (icon != null) {
                        androidx.tv.material3.Icon(
                            androidx.compose.ui.res.painterResource(icon),
                            contentDescription = null,
                            tint = if (focused) TvColors.onFocused else ComposeColor.White,
                            modifier = Modifier.size(22.dp)
                        )
                    } else if (icons == null) {
                        RadioDot(index == selected, focused)
                    } else {
                        Spacer(Modifier.width(22.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(option, fontSize = 17.sp, color = if (focused) TvColors.onFocused else ComposeColor.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (options.isNotEmpty()) {
            runCatching { requester.requestFocus() }
        }
    }
}

@Composable
fun ColorDot(color: Int?, checked: Boolean, focused: Boolean) {
    val ring = if (focused) TvColors.onFocused else ComposeColor.White
    Box(
        Modifier
            .size(24.dp)
            .border(2.dp, if (checked || color == null) ring else ComposeColor.Transparent, CircleShape)
            .padding(4.dp)
            .background(if (color != null) ComposeColor(color) else ComposeColor.Transparent, CircleShape)
    )
}

@Composable
fun RadioDot(checked: Boolean, focused: Boolean) {
    val color = if (focused) TvColors.onFocused else ComposeColor.White
    Box(
        Modifier
            .size(20.dp)
            .border(2.dp, if (checked) MaterialTheme.colorScheme.primary else color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

/** Value changed with left / right, the bar shows where it is between min and max. */
@Composable
fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    valueText: (Int) -> String = { it.toString() },
    modifier: Modifier = Modifier,
    icon: Int? = null,
    compact: Boolean = false,
    onChange: (Int) -> Unit,
) {
    var current by remember(value) { mutableIntStateOf(value) }
    FocusRow(
        compact = compact,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val delta = when (event.key) {
                Key.DirectionLeft -> -step
                Key.DirectionRight -> step
                else -> return@onPreviewKeyEvent false
            }
            val newValue = (current + delta).coerceIn(range.first, range.last)
            if (newValue != current) {
                current = newValue
                onChange(newValue)
            }
            true
        }
    ) { focused ->
        val textColor = if (focused) TvColors.onFocused else ComposeColor.White
        val fontSize = if (compact) 15.sp else 17.sp
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    androidx.tv.material3.Icon(
                        androidx.compose.ui.res.painterResource(icon),
                        contentDescription = null,
                        tint = if (focused) TvColors.onFocused else ComposeColor(0xD8FFFFFF),
                        modifier = Modifier.size(if (compact) 18.dp else 20.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                }
                Text(label, fontSize = fontSize, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (focused) {
                    Text("‹  ", fontSize = fontSize, color = textColor)
                }
                Text(valueText(current), fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = textColor)
                if (focused) {
                    Text("  ›", fontSize = fontSize, color = textColor)
                }
            }
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
            val fraction = if (range.last > range.first) (current - range.first).toFloat() / (range.last - range.first) else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (compact) 5.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (focused) ComposeColor(0x33000000) else ComposeColor(0x33FFFFFF))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(if (compact) 5.dp else 6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/** Row of choices changed with left / right. */
@Composable
fun SelectorRow(
    label: String,
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    compact: Boolean = false,
    onChange: (Int) -> Unit,
) {
    var current by remember(selected, options.size) { mutableIntStateOf(selected.coerceIn(0, (options.size - 1).coerceAtLeast(0))) }
    FocusRow(
        compact = compact,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val delta = when (event.key) {
                Key.DirectionLeft -> -1
                Key.DirectionRight -> 1
                else -> return@onPreviewKeyEvent false
            }
            current = (current + delta + options.size) % options.size
            onChange(current)
            true
        },
        onClick = {
            current = (current + 1) % options.size
            onChange(current)
        }
    ) { focused ->
        val textColor = if (focused) TvColors.onFocused else ComposeColor.White
        val fontSize = if (compact) 15.sp else 17.sp
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            if (icon != null) {
                androidx.tv.material3.Icon(
                    androidx.compose.ui.res.painterResource(icon),
                    contentDescription = null,
                    tint = if (focused) TvColors.onFocused else ComposeColor(0xD8FFFFFF),
                    modifier = Modifier.size(if (compact) 18.dp else 20.dp),
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(label, fontSize = fontSize, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                (if (focused) "‹ " else "") + options[current] + (if (focused) " ›" else ""),
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (compact) 185.dp else 235.dp),
            )
        }
    }
}

@Composable
fun ButtonRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    compact: Boolean = false,
    alignStart: Boolean = icon != null,
    onClick: () -> Unit,
) {
    FocusRow(modifier = modifier, compact = compact, onClick = onClick) { focused ->
        val textColor = if (focused) TvColors.onFocused else ComposeColor.White
        val fontSize = if (compact) 15.sp else 17.sp
        if (alignStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    androidx.tv.material3.Icon(
                        androidx.compose.ui.res.painterResource(icon),
                        contentDescription = null,
                        tint = if (focused) TvColors.onFocused else ComposeColor(0xD8FFFFFF),
                        modifier = Modifier.size(if (compact) 18.dp else 20.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                }
                Text(label, fontSize = fontSize, fontWeight = FontWeight.Medium, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(modifier = Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    androidx.tv.material3.Icon(
                        androidx.compose.ui.res.painterResource(icon),
                        contentDescription = null,
                        tint = if (focused) TvColors.onFocused else ComposeColor(0xD8FFFFFF),
                        modifier = Modifier.size(if (compact) 18.dp else 20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(label, fontSize = fontSize, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A list under a header: what goes under the header fades away, it is not cut by a straight line. Only once the list
 * has scrolled ([scrolled], read when drawing), so its first row is whole at the top.
 */
fun Modifier.tvTopFade(height: Dp = 36.dp, scrolled: () -> Boolean): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (scrolled()) {
            val edge = height.toPx()
            drawRect(
                brush = Brush.verticalGradient(0f to ComposeColor.Transparent, 1f to ComposeColor.Black, startY = 0f, endY = edge),
                size = androidx.compose.ui.geometry.Size(size.width, edge),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
    }

/**
 * Horizontal row of cards: cards sliding off the start or end edge dissolve smoothly into the background
 * instead of being cut by a straight vertical line, while keeping the first/last card whole at rest.
 */
fun Modifier.tvHorizontalFade(
    startWidth: Dp = 56.dp,
    endWidth: Dp = 56.dp,
    scrolledStart: () -> Boolean,
    scrolledEnd: () -> Boolean = { false },
): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (scrolledStart()) {
            val edge = startWidth.toPx().coerceAtMost(size.width * 0.35f)
            drawRect(
                brush = Brush.horizontalGradient(0f to ComposeColor.Transparent, 1f to ComposeColor.Black, startX = 0f, endX = edge),
                size = androidx.compose.ui.geometry.Size(edge, size.height),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
        if (scrolledEnd()) {
            val edge = endWidth.toPx().coerceAtMost(size.width * 0.35f)
            val startX = size.width - edge
            drawRect(
                brush = Brush.horizontalGradient(0f to ComposeColor.Black, 1f to ComposeColor.Transparent, startX = startX, endX = size.width),
                topLeft = androidx.compose.ui.geometry.Offset(startX, 0f),
                size = androidx.compose.ui.geometry.Size(edge, size.height),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
    }
