package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import kotlinx.coroutines.launch

/**
 * TV: a new version is there: its name and its changelog (the notes of the release, in a box of limited height that
 * scrolls with up / down), "Update" (focused at once) or "Ignore".
 */
object TvUpdateDialog {

    fun show(context: Context, version: String?, notes: String?, onUpdate: () -> Unit, onIgnore: () -> Unit) {
        var chosen = false
        val dialog = TvDialogs.show(context) { dismiss ->
            Content(
                title = if (!version.isNullOrBlank()) context.getString(R.string.tv_update_title_version, version) else context.getString(R.string.update_available),
                notes = notes?.takeIf { it.isNotBlank() }?.let { format(it) },
                updateLabel = context.getString(R.string.tv_update_now),
                ignoreLabel = context.getString(R.string.tv_update_ignore),
                onUpdate = {
                    chosen = true
                    dismiss()
                    onUpdate()
                },
                onIgnore = {
                    chosen = true
                    dismiss()
                    onIgnore()
                },
            )
        }
        // back: like "Ignore"
        dialog.setOnDismissListener {
            if (!chosen) {
                onIgnore()
            }
        }
    }

    /** The markdown of the notes, simply: titles in bold, list items with a bullet, **bold** words. */
    private fun format(markdown: String): AnnotatedString = buildAnnotatedString {
        val lines = markdown.replace("\r", "").lines()
        lines.forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("#") -> {
                    val title = line.trimStart('#').trim()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = if (line.startsWith("# ")) 18.sp else 16.sp, color = Color.White)) {
                        append(title)
                    }
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    append("  •  ")
                    appendInline(line.trimStart().drop(2))
                }
                else -> appendInline(line)
            }
            if (index < lines.lastIndex) append("\n")
        }
    }

    private fun AnnotatedString.Builder.appendInline(text: String) {
        val parts = text.split("**")
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(part) }
            } else {
                append(part.replace("`", ""))
            }
        }
    }

    @Composable
    private fun Content(
        title: String,
        notes: AnnotatedString?,
        updateLabel: String,
        ignoreLabel: String,
        onUpdate: () -> Unit,
        onIgnore: () -> Unit,
    ) {
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val updateRequester = remember { FocusRequester() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(0.62f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(TvColors.panel)
                    .padding(28.dp)
            ) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                if (notes != null) {
                    Spacer(Modifier.height(16.dp))
                    // the changelog: half of the screen at most, up / down scroll it
                    val scroll = rememberScrollState()
                    val scope = rememberCoroutineScope()
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = screenHeight * 0.5f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x14FFFFFF))
                            .then(if (focused) Modifier.border(2.dp, TvColors.accent, RoundedCornerShape(12.dp)) else Modifier)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        if (scroll.value > 0) {
                                            scope.launch { scroll.animateScrollBy(-220f) }
                                        }
                                        // at the top: nothing above, the key stops here
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        if (scroll.value < scroll.maxValue) {
                                            scope.launch { scroll.animateScrollBy(220f) }
                                            true
                                        } else false
                                    }
                                    else -> false
                                }
                            }
                            .focusable(interactionSource = interaction)
                            .verticalScroll(scroll)
                            .padding(18.dp)
                    ) {
                        Text(notes, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), lineHeight = 22.sp)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DialogButton(updateLabel, Modifier.focusRequester(updateRequester), onUpdate)
                    DialogButton(ignoreLabel, Modifier, onIgnore)
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { updateRequester.requestFocus() } }
    }

    @Composable
    private fun DialogButton(label: String, modifier: Modifier, onClick: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Box(
            modifier
                .clip(RoundedCornerShape(24.dp))
                .background(if (focused) TvColors.focused else Color(0x26FFFFFF))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 26.dp, vertical = 12.dp)
        ) {
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (focused) TvColors.onFocused else Color.White)
        }
    }
}
