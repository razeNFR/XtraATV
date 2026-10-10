package com.github.andreyasadchy.xtra.ui.tv

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.RecyclerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider

/**
 * TV: the content of a bottom sheet (sort, filters, choices) drawn again with Compose in the side panel.
 * The original views stay in place, hidden, and keep all the logic: the Compose rows click them.
 */
object TvSheetCompose {

    private sealed class Node {
        class Header(val text: String) : Node()
        class Radio(val button: RadioButton) : Node()
        class Toggle(val button: CompoundButton) : Node()
        class ChipNode(val chip: Chip) : Node()
        class Action(val view: View, val label: String) : Node()
    }

    /** Only simple sheets are converted, the ones with lists, fields or sliders keep their views. */
    fun canConvert(view: View): Boolean {
        if (view.visibility != View.VISIBLE) {
            return true
        }
        if (view is EditText || view is RecyclerView || view is SeekBar || view is Slider) {
            return false
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (!canConvert(view.getChildAt(i))) {
                    return false
                }
            }
        }
        return true
    }

    private fun collect(view: View, out: MutableList<Node>) {
        if (view.visibility != View.VISIBLE) {
            return
        }
        when {
            view is Chip -> if (view.isCloseIconVisible) out.add(Node.ChipNode(view))
            view is RadioButton -> out.add(Node.Radio(view))
            view is CompoundButton -> out.add(Node.Toggle(view))
            view is Button -> out.add(Node.Action(view, view.text.toString()))
            view is ImageView -> if (view.isClickable) {
                // remove button next to a button (delete the saved sort)
                val neighbor = (view.parent as? ViewGroup)?.let { parent ->
                    (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull { it !== view }
                }
                val label = view.contentDescription?.toString()
                    ?: listOfNotNull(view.context.getString(R.string.delete), neighbor?.text?.toString()).joinToString(" · ")
                out.add(Node.Action(view, label))
            }
            view is TextView -> {
                val text = view.text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    out.add(if (view.isClickable) Node.Action(view, text) else Node.Header(text))
                }
            }
            view is ViewGroup && view.isClickable -> {
                val texts = mutableListOf<String>()
                fun gather(v: View) {
                    if (v.visibility != View.VISIBLE) return
                    if (v is TextView && !v.text.isNullOrBlank()) texts.add(v.text.toString().trim())
                    if (v is ViewGroup) for (i in 0 until v.childCount) gather(v.getChildAt(i))
                }
                gather(view)
                if (texts.isNotEmpty()) {
                    out.add(Node.Action(view, texts.joinToString(" · ")))
                }
            }
            view is ViewGroup -> for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
        }
    }

    /** Puts the Compose panel in [sheet] in place of [content]. */
    fun install(sheet: ViewGroup, content: View, title: String?) {
        if (sheet.findViewById<View>(R.id.tvSheetCompose) != null) {
            return
        }
        val context = sheet.context
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF9147FF.toInt())
        val version = mutableIntStateOf(0)
        val composeView = ComposeView(context).apply {
            id = R.id.tvSheetCompose
            setContent {
                TvTheme(accent) {
                    SheetContent(content, title, version)
                }
            }
        }
        content.visibility = View.INVISIBLE
        sheet.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // the content can change after a choice or when an other dialog (languages, tags) gives a result
        composeView.viewTreeObserver.addOnWindowFocusChangeListener { version.intValue++ }
    }

    @Composable
    private fun SheetContent(content: View, title: String?, version: MutableIntState) {
        // the content itself is hidden behind the panel, its children are read
        val nodes = remember(version.intValue) {
            mutableListOf<Node>().also { list ->
                if (content is ViewGroup) {
                    for (i in 0 until content.childCount) collect(content.getChildAt(i), list)
                } else {
                    collect(content, list)
                }
            }
        }
        val requesters = remember(nodes.size) { List(nodes.size) { FocusRequester() } }
        val refresh = { content.post { version.intValue++ } }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (title != null) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            }
            LazyColumn {
                itemsIndexed(nodes) { index, node ->
                    val modifier = Modifier.focusRequester(requesters[index])
                    when (node) {
                        is Node.Header -> Text(
                            node.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp)
                        )
                        is Node.Radio -> FocusRow(modifier, onClick = { node.button.performClick(); refresh() }) { focused ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioDot(node.button.isChecked, focused)
                                Spacer(Modifier.width(14.dp))
                                RowText(node.button.text.toString(), focused)
                            }
                        }
                        is Node.Toggle -> FocusRow(modifier, onClick = { node.button.performClick(); refresh() }) { focused ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CheckBox(node.button.isChecked, focused)
                                Spacer(Modifier.width(14.dp))
                                RowText(node.button.text.toString(), focused)
                            }
                        }
                        is Node.ChipNode -> FocusRow(modifier, onClick = { node.chip.performCloseIconClick(); refresh() }) { focused ->
                            RowText("✕  ${node.chip.text}", focused)
                        }
                        is Node.Action -> FocusRow(modifier, onClick = { node.view.performClick(); refresh() }) { focused ->
                            RowText(node.label, focused, bold = true)
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            val initial = nodes.indexOfFirst { it is Node.Radio && it.button.isChecked }.takeIf { it != -1 }
                ?: nodes.indexOfFirst { it !is Node.Header }.takeIf { it != -1 }
            initial?.let {
                try {
                    requesters[it].requestFocus()
                } catch (e: IllegalStateException) {
                }
            }
        }
    }

    @Composable
    private fun RowText(text: String, focused: Boolean, bold: Boolean = false) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) TvColors.onFocused else Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun CheckBox(checked: Boolean, focused: Boolean) {
        val color = if (focused) TvColors.onFocused else Color.White
        Box(
            Modifier
                .size(20.dp)
                .border(2.dp, if (checked) MaterialTheme.colorScheme.primary else color, RoundedCornerShape(4.dp))
                .then(if (checked) Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", fontSize = 14.sp, color = Color.White)
            }
        }
    }
}
