package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.ui.graphics.Color as ComposeColor

/** TV: side panel of actions with an icon each, and an optional slider under them (multi-stream panel). */
object TvActionPanel {

    class Action(val label: String, val icon: Int, val run: () -> Unit)

    class Slider(val label: String, val value: Int, val range: IntRange, val step: Int, val valueText: (Int) -> String, val onChange: (Int) -> Unit)

    fun show(context: Context, title: String, actions: List<Action>, slider: Slider? = null) {
        TvDialogs.show(context) { dismiss ->
            SidePanel(title = title, scrollable = true) {
                val first = remember { FocusRequester() }
                Column {
                    actions.forEachIndexed { index, action ->
                        FocusRow(
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                            onClick = {
                                dismiss()
                                action.run()
                            }
                        ) { focused ->
                            val color = if (focused) TvColors.onFocused else ComposeColor.White
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(action.icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(16.dp))
                                Text(action.label, fontSize = 17.sp, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (slider != null) {
                        SliderRow(
                            label = slider.label,
                            value = slider.value,
                            range = slider.range,
                            step = slider.step,
                            valueText = slider.valueText,
                            modifier = if (actions.isEmpty()) Modifier.focusRequester(first) else Modifier,
                            onChange = slider.onChange,
                        )
                    }
                }
                LaunchedEffect(Unit) {
                    if (actions.isNotEmpty() || slider != null) {
                        try {
                            first.requestFocus()
                        } catch (e: IllegalStateException) {

                        }
                    }
                }
            }
        }
    }
}
