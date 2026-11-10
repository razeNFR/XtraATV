package com.github.andreyasadchy.xtra.ui.tv

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R

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
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    voice: VoiceState? = null,
    imeAction: ImeAction = ImeAction.Search,
    leadingIcon: Int? = R.drawable.baseline_search_black_24,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onLeft: (() -> Unit)? = null,
    onSubmit: () -> Unit,
) {
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
    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (highlighted) TvColors.focused else Color(0x26FFFFFF))
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (!editing && event.type == KeyEventType.KeyDown) {
                    if (onLeft != null && event.key == Key.DirectionLeft) {
                        onLeft()
                        return@onPreviewKeyEvent true
                    }
                    if ((event.key == Key.Backspace || event.key == Key.Delete) && value.isNotEmpty()) {
                        onValueChange(if (event.nativeKeyEvent.repeatCount >= 2) "" else value.dropLast(1))
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
                editing -> BasicTextField(
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
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Backspace || event.key == Key.Delete) &&
                                event.nativeKeyEvent.repeatCount >= 2 && value.isNotEmpty()
                            ) {
                                textFieldValue = TextFieldValue("", TextRange.Zero)
                                onValueChange("")
                                true
                            } else false
                        }
                )
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
    LaunchedEffect(editing) {
        if (editing) {
            textFieldValue = TextFieldValue(value, TextRange(value.length))
            editRequester.requestFocus()
            keyboard?.show()
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

/** Chat message box at the top of the screen: field, mic and send. */
@Composable
fun ChatComposer(title: String, hint: String, sendLabel: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val voice = remember { VoiceState() }
    val input = rememberVoiceInput(voice) {
        text = it
    }
    val requester = remember { FocusRequester() }
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
        androidx.compose.foundation.layout.Column(
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
                    focusRequester = requester,
                    modifier = Modifier.weight(1f),
                ) {
                    onSend(text)
                }
                if (text.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    TvIconButton(R.drawable.tv_ic_close, LocalContext.current.getString(R.string.delete)) {
                        text = ""
                        runCatching { requester.requestFocus() }
                    }
                }
                Spacer(Modifier.width(14.dp))
                VoiceButton(voice, input, onStart = { text = "" })
                Spacer(Modifier.width(10.dp))
                TvIconButton(R.drawable.ic_send_black_24dp, sendLabel) {
                    onSend(text)
                }
            }
        }
    }
    LaunchedEffect(Unit) { requester.requestFocus() }
}
