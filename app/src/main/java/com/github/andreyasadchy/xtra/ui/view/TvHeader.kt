package com.github.andreyasadchy.xtra.ui.view

import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvColors
import com.github.andreyasadchy.xtra.ui.tv.TvDialogs
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout

/**
 * TV header of the lists, categories and channels, drawn with Compose over the original header (kept hidden, it keeps
 * all the logic): title line, then "refresh", the sections (selected with OK only) and the sort pill.
 * Up from the first row of the list lands on "refresh", down goes back to the list, left opens the side menu.
 */
object TvHeader {

    /** Title of the screen when it has no title of its own (the section of the side menu). */
    var sectionTitle: (() -> String?)? = null

    private class Action(val key: String, val label: String, val icon: Int?, val selected: Boolean = false, val run: () -> Unit)

    private class Snapshot(
        val image: android.graphics.Bitmap?,
        val roundImage: Boolean,
        val title: String?,
        val subtitle: String?,
        val stats: String?,
        val left: List<Action>,
        val tabs: List<Action>,
        val right: List<Action>,
    ) {
        fun same(other: Snapshot?): Boolean {
            return other != null && other.image === image && other.title == title && other.subtitle == subtitle && other.stats == stats &&
                    other.left.map { it.key + it.label } == left.map { it.key + it.label } &&
                    other.tabs.map { it.label + it.selected } == tabs.map { it.label + it.selected } &&
                    other.right.map { it.key + it.label } == right.map { it.key + it.label }
        }
    }

    private class Controller(val firstRequester: FocusRequester, val view: ComposeView)

    /** Headers made of a toolbar with tabs, a sort bar or a title block are replaced, the search screen keeps its own. */
    fun install(appBar: AppBarLayout) {
        if (appBar.findViewById<View>(R.id.tvHeaderCompose) != null || appBar.findViewById<View>(R.id.searchView) != null) {
            return
        }
        val hasContent = appBar.findViewById<View>(R.id.tabLayout) != null || appBar.findViewById<View>(R.id.sortBar) != null ||
                appBar.findViewById<View>(R.id.gameName) != null || appBar.findViewById<View>(R.id.userName) != null
        if (!hasContent) {
            return
        }
        val originals = (0 until appBar.childCount).map { appBar.getChildAt(it) }
        originals.forEach { child ->
            child.layoutParams = child.layoutParams.apply { height = 0 }
            child.alpha = 0f
            if (child is ViewGroup) {
                child.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            child.isFocusable = false
        }
        val state = mutableStateOf<Snapshot?>(null)
        val firstRequester = FocusRequester()
        val view = TvDialogs.composeView(appBar.context) {
            val snapshot = state.value
            if (snapshot != null) {
                Header(snapshot, firstRequester, appBar)
            }
        }
        view.id = R.id.tvHeaderCompose
        view.tag = Controller(firstRequester, view)
        appBar.addView(view, AppBarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            scrollFlags = 0
        })
        val update = {
            val next = read(appBar)
            if (!next.same(state.value)) {
                state.value = next
            }
        }
        appBar.viewTreeObserver.addOnGlobalLayoutListener { update() }
        (appBar.findViewById<View>(R.id.tabLayout) as? TabLayout)?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) = update()
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        appBar.post { update() }
    }

    /** Up from the first row of a list: the first action of the header of its screen. */
    fun focusHeaderOf(view: View): Boolean {
        val root = screenRoot(view)
        val header = root.findViewById<ComposeView>(R.id.tvHeaderCompose) ?: return false
        val controller = header.tag as? Controller ?: return false
        if (!header.isShown) {
            return false
        }
        header.requestFocus()
        return try {
            controller.firstRequester.requestFocus()
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun screenRoot(view: View): View {
        var root: View = view
        while (true) {
            val parent = root.parent as? View ?: break
            if (parent.id == R.id.navHostFragment || parent is androidx.fragment.app.FragmentContainerView) break
            root = parent
        }
        return root
    }

    private fun visible(view: View?): Boolean {
        var v: View? = view ?: return false
        // the original header is hidden with alpha and no height: only the visibility flags count
        while (v != null && v !is AppBarLayout) {
            if (v.visibility != View.VISIBLE) return false
            v = v.parent as? View
        }
        return true
    }

    private fun text(root: View, id: Int): String? {
        val view = root.findViewById<TextView>(id) ?: return null
        return view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && visible(view) }
    }

    private fun read(appBar: AppBarLayout): Snapshot {
        val context = appBar.context
        val isChannel = appBar.findViewById<View>(R.id.userName) != null
        val imageView: ImageView? = (appBar.findViewById<ImageView?>(R.id.userImage) ?: appBar.findViewById<ImageView?>(R.id.gameImage))?.takeIf { visible(it) }
        val image = imageView?.drawable?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }?.let {
            try {
                (imageView.getTag(R.id.tvHeaderCompose) as? Pair<*, *>)?.takeIf { pair -> pair.first === it }?.second as? android.graphics.Bitmap
                    ?: it.toBitmap(it.intrinsicWidth.coerceAtMost(300), it.intrinsicHeight.coerceAtMost(400)).also { bitmap ->
                        imageView.setTag(R.id.tvHeaderCompose, it to bitmap)
                    }
            } catch (e: Exception) {
                null
            }
        }
        val toolbar = appBar.findViewById<MaterialToolbar>(R.id.toolbar)
        val title = if (isChannel) {
            text(appBar, R.id.userName)
        } else {
            text(appBar, R.id.gameName)
        } ?: toolbar?.title?.toString()?.takeIf { it.isNotBlank() } ?: sectionTitle?.invoke()
        val subtitle = if (isChannel) {
            listOfNotNull(text(appBar, R.id.title), text(appBar, R.id.gameName)).joinToString("  ·  ").takeIf { it.isNotEmpty() }
        } else null
        val stats = listOf(R.id.viewers, R.id.uptime, R.id.broadcastersCount, R.id.followers, R.id.userFollowers, R.id.lastBroadcast)
            .mapNotNull { text(appBar, it) }.joinToString("   ").takeIf { it.isNotEmpty() }
        val left = mutableListOf<Action>()
        appBar.findViewById<View>(R.id.watchLive)?.takeIf { visible(it) }?.let { button ->
            left.add(Action("watch", (button as? TextView)?.text?.toString() ?: context.getString(R.string.watch_live), R.drawable.baseline_play_arrow_black_48) { button.performClick() })
        }
        left.add(Action("refresh", context.getString(R.string.refresh), R.drawable.tv_ic_refresh) { TvUi.refreshVisibleList(appBar) })
        val tabs = (appBar.findViewById<View>(R.id.tabLayout) as? TabLayout)?.takeIf { visible(it) && it.tabCount > 1 }?.let { tabLayout ->
            (0 until tabLayout.tabCount).mapNotNull { index ->
                tabLayout.getTabAt(index)?.let { tab ->
                    Action("tab$index", tab.text?.toString() ?: "", null, tab.isSelected) { tab.select() }
                }
            }
        } ?: emptyList()
        val right = mutableListOf<Action>()
        toolbar?.menu?.let { menu ->
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item.isVisible && !item.title.isNullOrBlank() && item.itemId != R.id.search && item.itemId != R.id.settings && item.itemId != R.id.login && right.size < 3) {
                    right.add(Action("menu${item.itemId}", item.title.toString(), null) { menu.performIdentifierAction(item.itemId, 0) })
                }
            }
        }
        appBar.findViewById<View>(R.id.sortBar)?.takeIf { visible(it) }?.let { sortBar ->
            val sortText = sortBar.findViewById<TextView>(R.id.sortText)?.text?.toString()?.takeIf { it.isNotBlank() }
            val filters = sortBar.findViewById<TextView>(R.id.filtersText)?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()?.takeIf { it.isNotBlank() }
            right.add(Action("sort", listOfNotNull(sortText ?: context.getString(R.string.sort), filters).joinToString(" · "), R.drawable.baseline_sort_black_24) { sortBar.performClick() })
        }
        return Snapshot(image, isChannel, title, subtitle, stats, left, tabs, right)
    }

    @Composable
    private fun Header(snapshot: Snapshot, firstRequester: FocusRequester, appBar: AppBarLayout) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                snapshot.image?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .then(if (snapshot.roundImage) Modifier.size(52.dp).clip(CircleShape) else Modifier.size(width = 40.dp, height = 53.dp).clip(RoundedCornerShape(6.dp)))
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (snapshot.title != null) {
                            Text(snapshot.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                        if (snapshot.stats != null) {
                            Spacer(Modifier.width(16.dp))
                            Text(snapshot.stats, fontSize = 14.sp, color = TvColors.secondaryText, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                    if (snapshot.subtitle != null) {
                        Text(snapshot.subtitle, fontSize = 14.sp, color = TvColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val all = snapshot.left + snapshot.tabs + snapshot.right
            Row(
                Modifier.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            // back to the list of the screen
                            TvUi.findVisibleGrid(screenRoot(appBar))?.let { grid -> TvUi.focusFirstVisibleItem(grid) } ?: false
                        }
                        Key.DirectionUp -> true
                        else -> false
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                all.forEachIndexed { index, action ->
                    if (index == snapshot.left.size + snapshot.tabs.size && snapshot.right.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                    }
                    Pill(
                        action = action,
                        isTab = index >= snapshot.left.size && index < snapshot.left.size + snapshot.tabs.size,
                        modifier = (if (index == 0) Modifier.focusRequester(firstRequester) else Modifier).onPreviewKeyEvent { event ->
                            // left on the first action: the side menu
                            if (index == 0 && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                findActivity(appBar)?.openTvMenu()
                                true
                            } else if (index == all.lastIndex && event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                true
                            } else false
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun Pill(action: Action, isTab: Boolean, modifier: Modifier) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val background = when {
            focused -> TvColors.focused
            isTab && action.selected -> Color(0x40FFFFFF)
            isTab -> Color.Transparent
            else -> Color(0x26FFFFFF)
        }
        val content = when {
            focused -> TvColors.onFocused
            isTab && !action.selected -> TvColors.secondaryText
            else -> Color.White
        }
        Row(
            modifier
                .clip(RoundedCornerShape(24.dp))
                .background(background)
                .clickable(interactionSource = interaction, indication = null) { action.run() }
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (action.icon != null) {
                Icon(painterResource(action.icon), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                action.label,
                fontSize = 15.sp,
                fontWeight = if (isTab && action.selected || !isTab) FontWeight.SemiBold else FontWeight.Normal,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp)
            )
        }
    }

    fun findActivity(view: View): MainActivity? {
        var context = view.context
        while (context is ContextWrapper) {
            if (context is MainActivity) return context
            context = context.baseContext
        }
        return null
    }
}
