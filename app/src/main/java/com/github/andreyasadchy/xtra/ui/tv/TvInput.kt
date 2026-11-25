package com.github.andreyasadchy.xtra.ui.tv

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.awaitCancellation

object TvPermissions {

    fun Context.findActivity(): ComponentActivity? {
        var context = this
        while (context is ContextWrapper) {
            if (context is ComponentActivity) {
                return context
            }
            context = context.baseContext
        }
        return null
    }

    /** Asks for the microphone the first time voice input is used. */
    fun withAudio(context: Context, block: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            block(true)
            return
        }
        val activity = context.findActivity() ?: return block(false)
        var launcher: ActivityResultLauncher<String>? = null
        launcher = activity.activityResultRegistry.register("tv_audio_${System.nanoTime()}", ActivityResultContracts.RequestPermission()) { granted ->
            launcher?.unregister()
            block(granted)
        }
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/** State of the voice input of a field, shared by the field (partial text) and the mic button (level). */
class VoiceState {
    var listening by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)
    var partial by mutableStateOf<String?>(null)
}

@Composable
fun rememberVoiceInput(state: VoiceState, onResult: (String) -> Unit): TvVoiceInput {
    val context = LocalContext.current
    val input = remember {
        TvVoiceInput(
            context,
            onListening = { state.listening = it; if (it) state.partial = null },
            onLevel = { state.level = it },
            onPartial = { state.partial = it },
            onResult = { text ->
                state.partial = null
                if (text != null) {
                    onResult(text)
                }
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose { input.destroy() }
    }
    return input
}

/**
 * Text field for the D-pad: it gets the focus like a button and the keyboard only shows with OK,
 * so moving around never opens it by accident.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    voice: VoiceState? = null,
    imeAction: ImeAction = ImeAction.Search,
    leadingIcon: Int? = R.drawable.baseline_search_black_24,
    emotes: Map<String, String> = emptyMap(),
    focusRequester: FocusRequester = remember { FocusRequester() },
    onLeft: (() -> Unit)? = null,
    onSubmit: () -> Unit,
) {
    val appContext = LocalContext.current
    val sharedPrefs = remember(appContext) { appContext.prefs() }
    var disableSoftKeyboard by remember(sharedPrefs) {
        mutableStateOf(sharedPrefs.getBoolean(C.TV_DISABLE_SOFT_KEYBOARD, false))
    }
    DisposableEffect(sharedPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == C.TV_DISABLE_SOFT_KEYBOARD) {
                disableSoftKeyboard = p.getBoolean(C.TV_DISABLE_SOFT_KEYBOARD, false)
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var editing by remember { mutableStateOf(false) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (textFieldValue.text != value) {
        textFieldValue = TextFieldValue(value, TextRange(value.length))
    }
    val editRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var editFocused by remember { mutableStateOf(false) }
    val highlighted = focused || editFocused
    val textColor = if (highlighted) TvColors.onFocused else Color.White
    BackHandler(enabled = editing && disableSoftKeyboard) {
        editing = false
        focusRequester.requestFocus()
    }
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(if (highlighted) TvColors.focused else Color(0x26FFFFFF))
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (!editing && event.type == KeyEventType.KeyDown) {
                    if (onLeft != null && event.key == Key.DirectionLeft) {
                        onLeft()
                        return@onPreviewKeyEvent true
                    }
                    if ((event.key == Key.Backspace || event.key == Key.Delete) && value.isNotEmpty()) {
                        if (event.nativeKeyEvent.repeatCount >= 2) {
                            onValueChange("")
                        } else if (emotes.isNotEmpty()) {
                            val trimmed = value.trimEnd(' ')
                            val lastSpace = trimmed.lastIndexOf(' ')
                            val lastToken = if (lastSpace >= 0) trimmed.substring(lastSpace + 1) else trimmed
                            if (trimmed.isNotEmpty() && emotes.containsKey(lastToken)) {
                                onValueChange(if (lastSpace >= 0) trimmed.substring(0, lastSpace + 1) else "")
                            } else {
                                onValueChange(value.dropLast(1))
                            }
                        } else {
                            onValueChange(value.dropLast(1))
                        }
                        return@onPreviewKeyEvent true
                    }
                    val codePoint = event.nativeKeyEvent.unicodeChar
                    if (codePoint >= 32 &&
                        event.key != Key.DirectionCenter &&
                        event.key != Key.Enter &&
                        event.key != Key.NumPadEnter
                    ) {
                        val next = value + String(Character.toChars(codePoint))
                        textFieldValue = TextFieldValue(next, TextRange(next.length))
                        onValueChange(next)
                        editing = true
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .clickable(interactionSource = interaction, indication = null) {
                textFieldValue = TextFieldValue(value, TextRange(value.length))
                editing = true
            }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(painterResource(leadingIcon), contentDescription = null, tint = textColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
            }
            val partial = voice?.partial
            when {
                voice?.listening == true -> Text(
                    partial ?: LocalContext.current.getString(R.string.tv_speak_now),
                    fontSize = 18.sp,
                    color = if (partial != null) textColor else textColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                editing -> InterceptPlatformTextInput(
                    interceptor = { request, nextHandler ->
                        if (disableSoftKeyboard) {
                            awaitCancellation()
                        } else {
                            nextHandler.startInputMethod(request)
                        }
                    }
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            textFieldValue = newValue
                            if (newValue.text != value) {
                                onValueChange(newValue.text)
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = textColor, fontSize = 18.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
                        keyboardActions = KeyboardActions(onAny = {
                            keyboard?.hide()
                            editing = false
                            focusRequester.requestFocus()
                            onSubmit()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(editRequester)
                            .onFocusChanged {
                                if (editFocused && !it.isFocused) {
                                    editing = false
                                }
                                editFocused = it.isFocused
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    if ((event.key == Key.Backspace || event.key == Key.Delete) &&
                                        event.nativeKeyEvent.repeatCount >= 2 && value.isNotEmpty()
                                    ) {
                                        textFieldValue = TextFieldValue("", TextRange.Zero)
                                        onValueChange("")
                                        return@onPreviewKeyEvent true
                                    }
                                    if (disableSoftKeyboard && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                        keyboard?.hide()
                                        editing = false
                                        focusRequester.requestFocus()
                                        onSubmit()
                                        return@onPreviewKeyEvent true
                                    }
                                    if (event.key == Key.Escape) {
                                        keyboard?.hide()
                                        editing = false
                                        focusRequester.requestFocus()
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                false
                            }
                    )
                }
                value.isNotEmpty() && emotes.isNotEmpty() -> {
                    val context = LocalContext.current
                    val (annotated, inlineContent) = remember(value, emotes) {
                        val contentMap = mutableMapOf<String, InlineTextContent>()
                        val str = buildAnnotatedString {
                            var start = 0
                            while (start < value.length) {
                                val spaceIdx = value.indexOf(' ', start)
                                if (spaceIdx == start) {
                                    append(' ')
                                    start++
                                } else {
                                    val end = if (spaceIdx < 0) value.length else spaceIdx
                                    val token = value.substring(start, end)
                                    val url = emotes[token]
                                    if (!url.isNullOrBlank()) {
                                        val inlineId = "emote_$token"
                                        if (!contentMap.containsKey(inlineId)) {
                                            contentMap[inlineId] = InlineTextContent(
                                                Placeholder(
                                                    width = 24.sp,
                                                    height = 24.sp,
                                                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                                                )
                                            ) {
                                                coil3.compose.AsyncImage(
                                                    model = coil3.request.ImageRequest.Builder(context)
                                                        .data(url)
                                                        .build(),
                                                    contentDescription = token,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                        appendInlineContent(inlineId, token)
                                    } else {
                                        append(token)
                                    }
                                    start = end
                                }
                            }
                        }
                        str to contentMap
                    }
                    BasicText(
                        text = annotated,
                        style = TextStyle(fontSize = 18.sp, color = textColor),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        inlineContent = inlineContent,
                    )
                }
                else -> Text(
                    value.ifEmpty { hint },
                    fontSize = 18.sp,
                    color = if (value.isEmpty()) textColor.copy(alpha = 0.6f) else textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    LaunchedEffect(editing, disableSoftKeyboard) {
        if (editing) {
            textFieldValue = TextFieldValue(value, TextRange(value.length))
            editRequester.requestFocus()
            if (disableSoftKeyboard) {
                keyboard?.hide()
            } else {
                keyboard?.show()
            }
        }
    }
}

/** Round icon button, white when focused. */
@Composable
fun TvIconButton(
    icon: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    active: Boolean = false,
    level: Float = 0f,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(1f, 1.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ring")
    val levelScale by animateFloatAsState(1f + level * 0.35f, label = "level")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (active) {
            // listening: a ring that breathes and grows with the voice, like the YouTube app
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer { scaleX = maxOf(ring, levelScale); scaleY = maxOf(ring, levelScale) }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape)
            )
        }
        Box(
            Modifier
                .size(size)
                .graphicsLayer { val s = if (focused) 1.08f else 1f; scaleX = s; scaleY = s }
                .clip(CircleShape)
                .background(
                    when {
                        active -> MaterialTheme.colorScheme.primary
                        focused -> TvColors.focused
                        else -> Color(0x26FFFFFF)
                    }
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(icon),
                contentDescription = contentDescription,
                tint = if (focused && !active) TvColors.onFocused else Color.White,
                modifier = Modifier.size(size * 0.45f)
            )
        }
    }
}

/** Mic button of a field: listens in the app and puts the words in the field. */
@Composable
fun VoiceButton(
    state: VoiceState,
    input: TvVoiceInput,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    onStart: (() -> Unit)? = null,
) {
    if (!input.isAvailable) {
        return
    }
    val context = LocalContext.current
    TvIconButton(
        icon = R.drawable.tv_ic_mic,
        contentDescription = context.getString(R.string.tv_voice_input),
        modifier = modifier,
        size = size,
        active = state.listening,
        level = state.level,
    ) {
        if (state.listening) {
            input.cancel()
        } else {
            TvPermissions.withAudio(context) { granted ->
                if (granted) {
                    onStart?.invoke()
                    input.start()
                }
            }
        }
    }
}

/** Search field with the mic next to it. */
@Composable
fun TvSearchBar(
    initialQuery: String,
    hint: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onLeft: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    LaunchedEffect(initialQuery) {
        if (query != initialQuery) {
            query = initialQuery
        }
    }
    val voice = remember { VoiceState() }
    val input = rememberVoiceInput(voice) {
        // like YouTube: what was said is searched right away
        query = it
        onQueryChange(it)
        onSubmit(it)
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> if (onDown != null) {
                        onDown()
                        true
                    } else false
                    Key.DirectionUp -> true
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvTextField(
            value = query,
            onValueChange = {
                query = it
                onQueryChange(it)
            },
            hint = hint,
            voice = voice,
            focusRequester = focusRequester,
            onLeft = onLeft,
            modifier = Modifier.weight(1f),
        ) {
            onSubmit(query)
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            TvIconButton(R.drawable.tv_ic_close, LocalContext.current.getString(R.string.delete)) {
                query = ""
                onQueryChange("")
                runCatching { focusRequester.requestFocus() }
            }
        }
        Spacer(Modifier.width(14.dp))
        VoiceButton(voice, input, onStart = {
            if (query.isNotEmpty()) {
                query = ""
                onQueryChange("")
            }
        })
    }
}

/** Accent-tinted send icon button that still flips to bright white on focus for unmistakable TV D-pad feedback. */
@Composable
private fun TvSendPillButton(
    contentDescription: String,
    ready: Boolean,
    size: Dp = 46.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "sendScale")
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(
                when {
                    focused -> TvColors.focused
                    ready -> MaterialTheme.colorScheme.primary
                    else -> Color(0x28FFFFFF)
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_send_black_24dp),
            contentDescription = contentDescription,
            tint = if (focused) TvColors.onFocused else Color.White,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/** Shared TV emote drawer with category tabs (including lifetime Most Used counts), provider color dots, and live hover metadata. */
@Composable
private fun TvEmoteDrawer(
    emoteGroups: List<Pair<String, List<Emote>>>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    hoveredEmote: Emote?,
    onHoverEmote: (Emote?) -> Unit,
    onPickEmote: (String) -> Unit,
    pillStyle: Boolean,
) {
    val context = LocalContext.current
    val mostUsedLabel = remember(context) { context.getString(R.string.tv_emotes_most_used) }
    val focusSwitch = remember(context) { context.prefs().getBoolean(C.TV_FOCUS_SWITCH, false) }
    val safeTab = selectedTab.coerceIn(0, emoteGroups.lastIndex)
    val activeGroup = emoteGroups[safeTab]
    val activeEmotes = activeGroup.second
    val isMostUsedTab = activeGroup.first == mostUsedLabel
    val cols = if (pillStyle) 8 else 9
    val rows = remember(activeEmotes, cols) { activeEmotes.chunked(cols) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            emoteGroups.forEachIndexed { idx, (tabLabel, _) ->
                val tabInteraction = remember { MutableInteractionSource() }
                val tabFocused by tabInteraction.collectIsFocusedAsState()
                val isSelected = idx == safeTab
                val isMost = tabLabel == mostUsedLabel
                LaunchedEffect(tabFocused, focusSwitch) {
                    if (tabFocused && focusSwitch && selectedTab != idx) {
                        kotlinx.coroutines.delay(150L)
                        onSelectTab(idx)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            when {
                                tabFocused -> TvColors.focused
                                isSelected -> MaterialTheme.colorScheme.primary
                                else -> Color(0x22FFFFFF)
                            }
                        )
                        .clickable(interactionSource = tabInteraction, indication = null) {
                            onSelectTab(idx)
                        }
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isMost) "★ $tabLabel" else tabLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (tabFocused) TvColors.onFocused else Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
        val preview = hoveredEmote
        if (preview != null && !preview.name.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            val usageCount = TvEmoteUsage.countOf(context, preview.name)
            val provider = TvEmoteUsage.providerLabel(preview)
            val dotColor = Color(TvEmoteUsage.providerColorArgb(preview))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1EFFFFFF))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = preview.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = " · $provider",
                    fontSize = 12.sp,
                    color = TvColors.secondaryText,
                    maxLines = 1,
                )
                if (usageCount > 0) {
                    Text(
                        text = " · ${context.getString(R.string.tv_emotes_uses_count, TvEmoteUsage.formatCount(usageCount))}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (activeEmotes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (pillStyle) 168.dp else 180.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = context.getString(R.string.tv_emotes_most_used_empty),
                fontSize = 14.sp,
                color = TvColors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    } else {
        val cellHeight = if (isMostUsedTab) 66.dp else 52.dp
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (pillStyle) 208.dp else 216.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows.size, key = { "emote_row_${safeTab}_$it" }) { rowIdx ->
                val row = rows[rowIdx]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { emote ->
                        val code = emote.name ?: return@forEach
                        val url = emote.url2x ?: emote.url1x ?: emote.url3x ?: emote.url4x
                        val cellInteraction = remember { MutableInteractionSource() }
                        val cellFocused by cellInteraction.collectIsFocusedAsState()
                        LaunchedEffect(cellFocused) {
                            if (cellFocused) onHoverEmote(emote)
                        }
                        val scale by animateFloatAsState(if (cellFocused) 1.08f else 1f, label = "emoteScale")
                        val usageCount = if (isMostUsedTab) TvEmoteUsage.countOf(context, code) else 0
                        val dotColor = Color(TvEmoteUsage.providerColorArgb(emote))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(cellHeight)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (cellFocused) TvColors.focused else Color(0x18FFFFFF))
                                .border(
                                    width = 1.dp,
                                    color = if (cellFocused) Color.Transparent else Color(0x1AFFFFFF),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable(interactionSource = cellInteraction, indication = null) {
                                    onPickEmote(code)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(5.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(dotColor.copy(alpha = if (cellFocused) 0.95f else 0.78f))
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                            ) {
                                if (!url.isNullOrBlank()) {
                                    coil3.compose.AsyncImage(
                                        model = remember(url) {
                                            coil3.request.ImageRequest.Builder(context)
                                                .data(url)
                                                .size(72, 72)
                                                .build()
                                        },
                                        contentDescription = code,
                                        modifier = Modifier.size(if (isMostUsedTab) 32.dp else 34.dp),
                                    )
                                } else {
                                    Text(
                                        text = code,
                                        fontSize = 11.sp,
                                        color = if (cellFocused) TvColors.onFocused else Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isMostUsedTab && usageCount > 0) {
                                    Spacer(Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (cellFocused) Color(0x22000000) else Color(0x52000000))
                                            .padding(horizontal = 6.dp, vertical = 1.dp),
                                    ) {
                                        Text(
                                            text = TvEmoteUsage.formatCount(usageCount),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (cellFocused) TvColors.onFocused else Color(0xFFEAE8F6),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    repeat(cols - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Chat message composer:
 * - When [pillStyle] is true (live stream & multi-stream chat), renders as a compact smoked-glass pill
 *   anchored above the bottom HUD (matching [TvVolumePill]) with an upward-opening Emotes drawer.
 * - When [pillStyle] is false (channel switcher / generic dialog), keeps the top-anchored panel layout.
 */
@Composable
fun ChatComposer(
    title: String,
    hint: String,
    sendLabel: String,
    emoteGroups: List<Pair<String, List<Emote>>> = emptyList(),
    initialText: String = "",
    pillStyle: Boolean = false,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var showEmotes by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var hasUserPickedTab by remember { mutableStateOf(false) }
    var hoveredEmote by remember { mutableStateOf<Emote?>(null) }
    var backConsumedForEmotes by remember { mutableStateOf(false) }
    val voice = remember { VoiceState() }
    val input = rememberVoiceInput(voice) {
        text = it
    }
    val requester = remember { FocusRequester() }
    val emoteBtnRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val emoteMap = remember(emoteGroups) {
        buildMap {
            emoteGroups.forEach { (_, list) ->
                list.forEach { emote ->
                    val code = emote.name
                    val url = emote.url2x ?: emote.url1x ?: emote.url3x ?: emote.url4x
                    if (!code.isNullOrBlank() && !url.isNullOrBlank() && !containsKey(code)) {
                        put(code, url)
                    }
                }
            }
        }
    }

    // Default to Most Used when populated, otherwise first non-empty tab so the user immediately sees emotes.
    LaunchedEffect(showEmotes, emoteGroups) {
        if (showEmotes && !hasUserPickedTab && emoteGroups.isNotEmpty()) {
            val firstNonEmpty = emoteGroups.indexOfFirst { it.second.isNotEmpty() }
            if (firstNonEmpty >= 0) {
                selectedTab = firstNonEmpty
            }
        }
    }

    val closeEmotesKeyModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.key == Key.Back || event.key == Key.Escape) {
            if (event.type == KeyEventType.KeyDown && showEmotes) {
                backConsumedForEmotes = true
                showEmotes = false
                hoveredEmote = null
                runCatching { emoteBtnRequester.requestFocus() }
                return@onPreviewKeyEvent true
            }
            if (event.type == KeyEventType.KeyUp && backConsumedForEmotes) {
                backConsumedForEmotes = false
                return@onPreviewKeyEvent true
            }
        }
        false
    }

    if (pillStyle) {
        val channelBadge = remember(title) {
            if (title.contains("·")) title.substringAfter("·").trim().takeIf { it.isNotEmpty() } else null
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(closeEmotesKeyModifier)
                .padding(bottom = 78.dp, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (showEmotes && emoteGroups.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .width(780.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xF0171620))
                            .border(1.25.dp, Color(0x3AFFFFFF), RoundedCornerShape(22.dp))
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        TvEmoteDrawer(
                            emoteGroups = emoteGroups,
                            selectedTab = selectedTab,
                            onSelectTab = {
                                hasUserPickedTab = true
                                selectedTab = it
                                hoveredEmote = null
                            },
                            hoveredEmote = hoveredEmote,
                            onHoverEmote = { hoveredEmote = it },
                            onPickEmote = { code ->
                                val prefix = if (text.isEmpty() || text.endsWith(" ")) "" else " "
                                text = "$text$prefix$code "
                            },
                            pillStyle = true,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(
                    modifier = Modifier
                        .width(780.dp)
                        .clip(RoundedCornerShape(34.dp))
                        .background(Color(0xF21B1A24))
                        .border(1.5.dp, Color(0x46FFFFFF), RoundedCornerShape(34.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (channelBadge != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = channelBadge,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    TvTextField(
                        value = text,
                        onValueChange = { text = it },
                        hint = hint,
                        height = 46.dp,
                        voice = voice,
                        imeAction = ImeAction.Send,
                        leadingIcon = null,
                        emotes = emoteMap,
                        focusRequester = requester,
                        modifier = Modifier.weight(1f),
                    ) {
                        onSend(text)
                    }
                    if (text.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        TvIconButton(
                            icon = R.drawable.tv_ic_close,
                            contentDescription = context.getString(R.string.delete),
                            size = 46.dp,
                        ) {
                            text = ""
                            runCatching { requester.requestFocus() }
                        }
                    }
                    if (emoteGroups.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        TvIconButton(
                            icon = R.drawable.tv_ic_mood,
                            contentDescription = context.getString(R.string.room_emote),
                            modifier = Modifier.focusRequester(emoteBtnRequester),
                            size = 46.dp,
                            active = showEmotes,
                        ) {
                            showEmotes = !showEmotes
                            if (!showEmotes) hoveredEmote = null
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    VoiceButton(voice, input, size = 46.dp, onStart = { text = "" })
                    Spacer(Modifier.width(8.dp))
                    TvSendPillButton(
                        contentDescription = sendLabel,
                        ready = text.isNotBlank(),
                        size = 46.dp,
                    ) {
                        onSend(text)
                    }
                }
                // Subtle smoked-glass pointer beak beneath the pill, matching TvVolumePill
                Canvas(
                    modifier = Modifier
                        .width(18.dp)
                        .height(8.dp)
                ) {
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width / 2f, size.height)
                        close()
                    }
                    drawPath(path, color = Color(0xF21B1A24))
                    val stroke = 1.5.dp.toPx()
                    drawLine(
                        color = Color(0x46FFFFFF),
                        start = Offset(0f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = stroke,
                    )
                    drawLine(
                        color = Color(0x46FFFFFF),
                        start = Offset(size.width, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = stroke,
                    )
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(closeEmotesKeyModifier)
                .padding(top = 48.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .width(760.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(TvColors.panel)
                    .padding(24.dp)
            ) {
                Text(title, fontSize = 22.sp, color = Color.White)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvTextField(
                        value = text,
                        onValueChange = { text = it },
                        hint = hint,
                        voice = voice,
                        imeAction = ImeAction.Send,
                        leadingIcon = null,
                        emotes = emoteMap,
                        focusRequester = requester,
                        modifier = Modifier.weight(1f),
                    ) {
                        onSend(text)
                    }
                    if (text.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        TvIconButton(R.drawable.tv_ic_close, context.getString(R.string.delete)) {
                            text = ""
                            runCatching { requester.requestFocus() }
                        }
                    }
                    if (emoteGroups.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        TvIconButton(
                            icon = R.drawable.tv_ic_mood,
                            contentDescription = context.getString(R.string.room_emote),
                            modifier = Modifier.focusRequester(emoteBtnRequester),
                            active = showEmotes,
                        ) {
                            showEmotes = !showEmotes
                            if (!showEmotes) hoveredEmote = null
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    VoiceButton(voice, input, onStart = { text = "" })
                    Spacer(Modifier.width(10.dp))
                    TvIconButton(R.drawable.ic_send_black_24dp, sendLabel) {
                        onSend(text)
                    }
                }
                if (showEmotes && emoteGroups.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    TvEmoteDrawer(
                        emoteGroups = emoteGroups,
                        selectedTab = selectedTab,
                        onSelectTab = {
                            hasUserPickedTab = true
                            selectedTab = it
                            hoveredEmote = null
                        },
                        hoveredEmote = hoveredEmote,
                        onHoverEmote = { hoveredEmote = it },
                        onPickEmote = { code ->
                            val prefix = if (text.isEmpty() || text.endsWith(" ")) "" else " "
                            text = "$text$prefix$code "
                        },
                        pillStyle = false,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { requester.requestFocus() }
}
