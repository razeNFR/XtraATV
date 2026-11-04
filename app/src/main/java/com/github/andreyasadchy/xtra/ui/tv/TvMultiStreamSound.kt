package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * TV multi-stream, Sound panel: a sentence explaining the choice, the choice (selected stream only or mix) changed with
 * left / right, and in the mix the volume of each stream and a box to keep these settings for the next multi-streams.
 */
object TvMultiStreamSound {

    class Texts(
        val title: String,
        val modeLabel: String,
        val selected: String,
        val mix: String,
        val selectedDescription: String,
        val mixDescription: String,
        val remember: String,
    )

    fun show(
        context: Context,
        texts: Texts,
        mix: Boolean,
        keep: Boolean,
        streams: List<String>,
        volumeOf: (Int) -> Int,
        onMode: (Boolean) -> Unit,
        onVolume: (Int, Int) -> Unit,
        onKeep: (Boolean) -> Unit,
    ) {
        TvDialogs.show(context) { _ ->
            SidePanel(title = texts.title, scrollable = true) {
                var mixState by remember { mutableStateOf(mix) }
                var keepState by remember { mutableStateOf(keep) }
                val first = remember { FocusRequester() }
                Column {
                    Text(
                        if (mixState) texts.mixDescription else texts.selectedDescription,
                        fontSize = 15.sp,
                        color = TvColors.secondaryText,
                    )
                    Spacer(Modifier.height(14.dp))
                    SelectorRow(
                        label = texts.modeLabel,
                        options = listOf(texts.selected, texts.mix),
                        selected = if (mix) 1 else 0,
                        modifier = Modifier.focusRequester(first),
                    ) { index ->
                        mixState = index == 1
                        onMode(mixState)
                    }
                    if (mixState) {
                        streams.forEachIndexed { index, name ->
                            SliderRow(
                                label = name,
                                value = volumeOf(index),
                                range = 0..100,
                                step = 10,
                                valueText = { "$it %" },
                            ) { value ->
                                onVolume(index, value)
                            }
                        }
                        FocusRow(onClick = {
                            keepState = !keepState
                            onKeep(keepState)
                        }) { focused ->
                            val color = if (focused) TvColors.onFocused else ComposeColor.White
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CheckBox(keepState, color)
                                Spacer(Modifier.width(16.dp))
                                Text(texts.remember, fontSize = 17.sp, color = color)
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    try {
                        first.requestFocus()
                    } catch (e: IllegalStateException) {

                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun CheckBox(checked: Boolean, color: ComposeColor) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            Modifier
                .size(20.dp)
                .border(2.dp, if (checked) MaterialTheme.colorScheme.primary else color, shape)
                .background(if (checked) MaterialTheme.colorScheme.primary else ComposeColor.Transparent, shape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
