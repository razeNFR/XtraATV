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
        val dialog = ComponentDialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
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
    fun showChoices(context: Context, title: String, options: List<String>, selected: Int, onSelected: (Int) -> Unit): ComponentDialog {
        return show(context) { dismiss ->
            SidePanel(title = title) {
                ChoiceList(options, selected) {
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
            primary = ComposeColor(accent),
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
    val onFocused = ComposeColor(0xFF111014)
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
                .padding(horizontal = 32.dp, vertical = 36.dp)
        ) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, fontSize = 14.sp, color = TvColors.secondaryText)
            }
            Spacer(Modifier.height(24.dp))
            if (scrollable) {
                // the rows scroll, the focused one is always brought into the screen
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}

/** Row that is white when it has the focus, like the menus of Google TV. */
@Composable
fun FocusRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "scale")
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) TvColors.focused else TvColors.item)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                else Modifier.focusable(interactionSource = interaction)
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        content(focused)
    }
}

@Composable
fun ChoiceList(options: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    val requester = remember { FocusRequester() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 2).coerceAtLeast(0))
    LazyColumn(state = state) {
        itemsIndexed(options) { index, option ->
            FocusRow(
                modifier = if (index == selected.coerceIn(0, options.lastIndex)) Modifier.focusRequester(requester) else Modifier,
                onClick = { onSelected(index) }
            ) { focused ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioDot(index == selected, focused)
                    Spacer(Modifier.width(16.dp))
                    Text(option, fontSize = 17.sp, color = if (focused) TvColors.onFocused else ComposeColor.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (options.isNotEmpty()) {
            requester.requestFocus()
        }
    }
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
    onChange: (Int) -> Unit,
) {
    var current by remember { mutableIntStateOf(value) }
    FocusRow(
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
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 17.sp, color = textColor, modifier = Modifier.weight(1f))
                if (focused) {
                    Text("‹  ", fontSize = 17.sp, color = textColor)
                }
                Text(valueText(current), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                if (focused) {
                    Text("  ›", fontSize = 17.sp, color = textColor)
                }
            }
            Spacer(Modifier.height(10.dp))
            val fraction = if (range.last > range.first) (current - range.first).toFloat() / (range.last - range.first) else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (focused) ComposeColor(0x33000000) else ComposeColor(0x33FFFFFF))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
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
    onChange: (Int) -> Unit,
) {
    var current by remember { mutableIntStateOf(selected) }
    FocusRow(
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 17.sp, color = textColor, modifier = Modifier.weight(1f))
            Text((if (focused) "‹  " else "") + options[current] + (if (focused) "  ›" else ""), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textColor)
        }
    }
}

@Composable
fun ButtonRow(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusRow(modifier = modifier, onClick = onClick) { focused ->
        Text(label, fontSize = 17.sp, color = if (focused) TvColors.onFocused else ComposeColor.White, modifier = Modifier.align(Alignment.Center))
    }
}
