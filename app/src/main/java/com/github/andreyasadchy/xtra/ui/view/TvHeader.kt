package com.github.andreyasadchy.xtra.ui.view

import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvAmbient
import com.github.andreyasadchy.xtra.ui.tv.TvColors
import com.github.andreyasadchy.xtra.ui.tv.TvDialogs
import com.github.andreyasadchy.xtra.util.prefs
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
    /** Icon of the section of the side menu shown, in front of the title of its screen (null on the screens opened from it). */
    var sectionIcon: (() -> Int?)? = null

    private class Action(val key: String, val label: String, val icon: Int?, val selected: Boolean = false, val run: () -> Unit)

    private class Snapshot(
        val image: android.graphics.Bitmap?,
        val bannerUrl: String?,
        val roundImage: Boolean,
        val isSearch: Boolean,
        val searchQuery: String,
        val title: String?,
        val titleIcon: Int?,
        val subtitle: String?,
        val stats: String?,
        val left: List<Action>,
        val tabs: List<Action>,
        /** Right after the tabs: refresh. */
        val afterTabs: List<Action>,
        val right: List<Action>,
    ) {
        fun same(other: Snapshot?): Boolean {
            return other != null && other.image === image && other.bannerUrl == bannerUrl && other.isSearch == isSearch &&
                    other.searchQuery == searchQuery && other.title == title && other.titleIcon == titleIcon && other.subtitle == subtitle && other.stats == stats &&
                    other.left.map { it.key + it.label } == left.map { it.key + it.label } &&
                    other.tabs.map { it.label + it.selected } == tabs.map { it.label + it.selected } &&
                    other.afterTabs.map { it.key } == afterTabs.map { it.key } &&
                    other.right.map { it.key + it.label + it.icon } == right.map { it.key + it.label + it.icon }
        }
    }

    private class Controller(val firstRequester: FocusRequester, val tabRequester: FocusRequester, val searchRequester: FocusRequester, val view: ComposeView)

    /** Headers made of a toolbar with tabs, a sort bar, a search bar or a title block are replaced by the TV Compose header. */
    fun install(appBar: AppBarLayout) {
        if (appBar.findViewById<View>(R.id.tvHeaderCompose) != null) {
            return
        }
        val searchView = appBar.findViewById<androidx.appcompat.widget.SearchView>(R.id.searchView)
        val hasContent = searchView != null || appBar.findViewById<View>(R.id.tabLayout) != null || appBar.findViewById<View>(R.id.sortBar) != null ||
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
        val tabRequester = FocusRequester()
        val searchRequester = FocusRequester()
        val view = TvDialogs.composeView(appBar.context) {
            val snapshot = state.value
            if (snapshot != null) {
                Header(snapshot, firstRequester, tabRequester, searchRequester, appBar)
            }
        }
        view.id = R.id.tvHeaderCompose
        view.tag = Controller(firstRequester, tabRequester, searchRequester, view)
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
        searchView?.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.let { input ->
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { update() }
            })
        }
        // the actions of the hidden toolbar change without a new layout (follow / unfollow): read again every second
        val ticker = object : Runnable {
            override fun run() {
                if (appBar.isAttachedToWindow) {
                    update()
                    appBar.postDelayed(this, 1000L)
                }
            }
        }
        appBar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                appBar.removeCallbacks(ticker)
                appBar.postDelayed(ticker, 1000L)
            }
            override fun onViewDetachedFromWindow(v: View) = appBar.removeCallbacks(ticker).let { }
        })
        appBar.postDelayed(ticker, 1000L)
        (appBar.findViewById<View>(R.id.tabLayout) as? TabLayout)?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) = update()
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        appBar.post { update() }
    }

    /**
     * Up from the first row of a list: the tab shown of the header of its screen (never another tab: with "pages without
     * OK" it would change the page), else its first action (or the search bar when there are no pills).
     */
    fun focusHeaderOf(view: View): Boolean {
        val root = screenRoot(view)
        val header = root.findViewById<ComposeView>(R.id.tvHeaderCompose) ?: return false
        val controller = header.tag as? Controller ?: return false
        if (!header.isShown) {
            return false
        }
        header.requestFocus()
        if (runCatching { controller.tabRequester.requestFocus() }.getOrDefault(false)) {
            return true
        }
        return try {
            controller.firstRequester.requestFocus()
            true
        } catch (e: IllegalStateException) {
            try {
                controller.searchRequester.requestFocus()
                true
            } catch (_: IllegalStateException) {
                false
            }
        }
    }

    /** Registers a focus-entry callback on a Compose tab root so D-pad Down from [TvHeader] enters it cleanly. */
    fun registerTabContentFocus(composeView: ComposeView, onFocusFirst: () -> Boolean) {
        composeView.setTag(R.id.tvHeaderCompose, onFocusFirst)
    }

    private fun focusVisibleTabContent(root: View): Boolean {
        TvUi.findVisibleGrid(root)?.let { grid ->
            if (TvUi.focusFirstVisibleItem(grid)) return true
        }
        var focused = false
        fun scan(v: View) {
            if (focused || !v.isShown || v.id == R.id.tvHeaderCompose) return
            val callback = v.getTag(R.id.tvHeaderCompose)
            if (callback is Function0<*>) {
                v.requestFocus()
                if (callback.invoke() == true) {
                    focused = true
                    return
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) scan(v.getChildAt(i))
            }
        }
        scan(root)
        return focused
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
        val searchView = appBar.findViewById<androidx.appcompat.widget.SearchView>(R.id.searchView)
        val isSearch = searchView != null
        val searchQuery = searchView?.query?.toString().orEmpty()
        val isChannel = appBar.findViewById<View>(R.id.userName) != null
        val bannerUrl = if (isChannel) {
            appBar.findViewById<View>(R.id.bannerImage)?.takeIf { visible(it) }?.getTag(R.id.bannerImage) as? String
        } else null
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
        val title = if (isSearch) {
            null
        } else if (isChannel) {
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
        if (isChannel) {
            // channel page: follow the streamer, no refresh (its lists follow the channel)
            toolbar?.menu?.findItem(R.id.followButton)?.takeIf { it.isVisible }?.let { item ->
                val following = item.title?.toString() == context.getString(R.string.unfollow)
                left.add(Action(
                    "follow",
                    item.title?.toString() ?: context.getString(R.string.follow),
                    if (following) R.drawable.baseline_favorite_black_24 else R.drawable.baseline_favorite_border_black_24
                ) { toolbar.menu.performIdentifierAction(R.id.followButton, 0) })
            }
        }
        // refresh at the right of the tabs (at the left of the screen when it has no tabs), never in channel or search screens
        val afterTabs = if (!isChannel && !isSearch) {
            listOf(Action("refresh", context.getString(R.string.refresh), R.drawable.tv_ic_refresh) { TvUi.refreshVisibleList(appBar) })
        } else emptyList()
        val tabs = (appBar.findViewById<View>(R.id.tabLayout) as? TabLayout)?.takeIf { visible(it) && it.tabCount > 1 }?.let { tabLayout ->
            (0 until tabLayout.tabCount).mapNotNull { index ->
                tabLayout.getTabAt(index)?.let { tab ->
                    Action("tab$index", tab.text?.toString() ?: "", null, tab.isSelected) {
                        screenRoot(appBar).findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)?.setCurrentItem(index, false)
                        tab.select()
                    }
                }
            }
        } ?: emptyList()
        val right = mutableListOf<Action>()
        if (!isSearch) {
            toolbar?.menu?.let { menu ->
                for (i in 0 until menu.size()) {
                    val item = menu.getItem(i)
                    if (item.isVisible && !item.title.isNullOrBlank() && item.itemId != R.id.search && item.itemId != R.id.settings && item.itemId != R.id.login && item.itemId != R.id.followButton && right.size < 6) {
                        // channel page: notifications, share and download as icons only (a full bell when on), the row
                        // stays on the screen with all its tabs
                        val icon = when (item.itemId) {
                            R.id.toggleNotifications -> if (item.title?.toString() == context.getString(R.string.disable_notifications)) R.drawable.tv_ic_bell else R.drawable.tv_ic_bell_outline
                            R.id.share -> R.drawable.tv_ic_share
                            R.id.download -> R.drawable.tv_ic_download
                            else -> null
                        }
                        right.add(Action("menu${item.itemId}", if (icon != null) "" else item.title.toString(), icon) { menu.performIdentifierAction(item.itemId, 0) })
                    }
                }
            }
        }
        appBar.findViewById<View>(R.id.sortBar)?.takeIf { visible(it) }?.let { sortBar ->
            val sortText = sortBar.findViewById<TextView>(R.id.sortText)?.text?.toString()?.takeIf { it.isNotBlank() }
            val filters = sortBar.findViewById<TextView>(R.id.filtersText)?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()?.takeIf { it.isNotBlank() }
            // "Sort: viewers ↓ · language": what it does, then what the list is sorted by
            val sortValue = listOfNotNull(sortText?.let { compactSort(context, it) }, filters).joinToString(" · ")
            // French puts a space before the colon
            val colon = if (context.resources.configuration.locales[0].language == "fr") " : " else ": "
            val sortLabel = context.getString(R.string.sort).let { if (sortValue.isEmpty()) it else it + colon + sortValue }
            right.add(Action("sort", sortLabel, R.drawable.baseline_sort_black_24) { sortBar.performClick() })
        }
        // a main screen of the side menu (no picture of its own): the icon of its entry in front of its title
        val titleIcon = if (image == null && !isChannel && !isSearch) sectionIcon?.invoke() else null
        return Snapshot(image, bannerUrl, isChannel, isSearch, searchQuery, title, titleIcon, subtitle, stats, left, tabs, afterTabs, right)
    }

    /**
     * The sort pill short: "Sort by: Viewers (descending)" -> "Viewers ↓", "Sort by: Last broadcast. Order: Descending"
     * -> "Last broadcast ↓", "Sort by: Time. Period: Week" -> "Time · Week" (the icon of the pill says it is the sort).
     */
    private fun compactSort(context: android.content.Context, text: String): String {
        val descending = context.getString(R.string.descending)
        val ascending = context.getString(R.string.ascending)
        fun arrow(order: String): String? = when {
            order.trim().equals(descending, true) -> "↓"
            order.trim().equals(ascending, true) -> "↑"
            else -> null
        }
        fun match(res: Int): MatchResult? = runCatching {
            val format = Regex.escape(context.getString(res))
                .replace("%1\$s", "\\E(.+?)\\Q")
                .replace("%2\$s", "\\E(.+?)\\Q")
            Regex("^$format$").find(text.trim())
        }.getOrNull()
        // "Viewers (descending)" -> "Viewers ↓"
        fun criterion(value: String): String {
            val inBrackets = Regex("^(.+?)\\s*\\((.+)\\)$").find(value.trim())
            return inBrackets?.let { found -> arrow(found.groupValues[2])?.let { "${found.groupValues[1]} $it" } } ?: value.trim()
        }
        match(R.string.sort_and_order)?.let { return "${criterion(it.groupValues[1])} ${arrow(it.groupValues[2]) ?: it.groupValues[2].trim()}" }
        match(R.string.sort_and_period)?.let { return "${criterion(it.groupValues[1])} · ${it.groupValues[2].trim()}" }
        match(R.string.sort_and_type)?.let { return "${criterion(it.groupValues[1])} · ${it.groupValues[2].trim()}" }
        match(R.string.sort_by)?.let { return criterion(it.groupValues[1]) }
        return text
    }

    @Composable
    private fun Clock() {
        val context = LocalContext.current
        var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
                now = System.currentTimeMillis()
            }
        }
        Text(
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1
        )
    }

    @Composable
    private fun Header(snapshot: Snapshot, firstRequester: FocusRequester, tabRequester: FocusRequester, searchRequester: FocusRequester, appBar: AppBarLayout) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val all = snapshot.left + snapshot.tabs + snapshot.afterTabs + snapshot.right
        if (snapshot.isSearch) {
            LaunchedEffect(Unit) {
                runCatching { searchRequester.requestFocus() }
            }
        }
        LaunchedEffect(snapshot.bannerUrl, snapshot.image) {
            when {
                !snapshot.bannerUrl.isNullOrBlank() -> TvAmbient.updateFromUrl(context, snapshot.bannerUrl, scope)
                snapshot.image != null -> TvAmbient.updateFromBitmap(context, snapshot.title, snapshot.image, scope)
            }
        }
        Box(Modifier.fillMaxWidth()) {
            if (!snapshot.bannerUrl.isNullOrBlank()) {
                // Feathered channel banner: dissolves softly on all 4 edges into any ambient background
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0.0f to Color.Transparent,
                                    0.15f to Color.Black,
                                    0.85f to Color.Black,
                                    1.0f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.14f to Color.Black,
                                    0.58f to Color.Black,
                                    1.0f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(snapshot.bannerUrl)
                            .crossfade(300)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.38f,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    0.0f to Color(0xBF0E0E10),
                                    0.55f to Color(0x590E0E10),
                                    1.0f to Color.Transparent,
                                )
                            )
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
                // a screen with tabs and no action of its own (followed, categories...): its sort goes on the line of the
                // title, at the left of the time. No line only for it, and it is reached with "up" from the tabs
                val titleActions = if (snapshot.tabs.isNotEmpty() && snapshot.left.isEmpty() && !snapshot.isSearch) snapshot.right else emptyList()
                val titleActionRequester = remember { FocusRequester() }
                // always the height of a line with the sort on it: the page does not move when a tab that has a sort
                // is shown after one that has none
                Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (snapshot.isSearch) {
                        val searchView = remember(appBar) { appBar.findViewById<androidx.appcompat.widget.SearchView>(R.id.searchView) }
                        com.github.andreyasadchy.xtra.ui.tv.TvSearchBar(
                            initialQuery = snapshot.searchQuery,
                            hint = appBar.context.getString(R.string.search),
                            modifier = Modifier.weight(1f),
                            focusRequester = searchRequester,
                            onLeft = { findActivity(appBar)?.openTvMenu() },
                            onDown = {
                                val movedToPills = if (all.isNotEmpty()) {
                                    runCatching { firstRequester.requestFocus(); true }.getOrDefault(false)
                                } else false
                                if (!movedToPills) {
                                    focusVisibleTabContent(screenRoot(appBar))
                                }
                            },
                            onQueryChange = { searchView?.setQuery(it, false) },
                            onSubmit = { searchView?.setQuery(it, true) },
                        )
                    } else {
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
                        if (snapshot.titleIcon != null && snapshot.title != null) {
                            Icon(painterResource(snapshot.titleIcon), contentDescription = null, tint = TvColors.accent, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
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
                    titleActions.forEachIndexed { index, action ->
                        Spacer(Modifier.width(10.dp))
                        Pill(
                            action = action,
                            isTab = false,
                            // it takes no height in the line of the title (drawn centered on it): the line, and the
                            // page under it, are exactly the same with or without the sort
                            modifier = Modifier
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                                    layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
                                }
                                .then(if (index == titleActions.lastIndex) Modifier.focusRequester(titleActionRequester) else Modifier)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionUp -> true
                                        Key.DirectionLeft -> index == 0
                                        Key.DirectionRight -> index == titleActions.lastIndex
                                        Key.DirectionDown -> {
                                            // back on the tab shown, else in the page
                                            if (runCatching { tabRequester.requestFocus() }.getOrDefault(false) != true) {
                                                focusVisibleTabContent(screenRoot(appBar))
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        )
                    }
                    // the time at the right of the title line, like on the home (the title stops before it)
                    Spacer(Modifier.width(16.dp))
                    Clock()
                }
                if (all.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    // 2 lines when the screen has tabs: its actions first (they go to the next line when they do not fit,
                    // the sort at the end of the line), then the tabs alone. The sort is reached without crossing a tab:
                    // with "pages without OK" crossing one changed the page, and the sort of the page went away with it
                    val tabsLine = snapshot.tabs + snapshot.afterTabs
                    val hasTabs = snapshot.tabs.isNotEmpty()
                    val actionsLine = when {
                        titleActions.isNotEmpty() -> emptyList()
                        hasTabs -> snapshot.left + snapshot.right
                        else -> all
                    }
                    val firstKey = (actionsLine.firstOrNull() ?: tabsLine.firstOrNull())?.key
                    val tabTarget = snapshot.tabs.firstOrNull { it.selected } ?: snapshot.tabs.firstOrNull()
                    // top of each action on the screen: the ones of the last line go down to the tab shown
                    val tops = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
                    if (actionsLine.isNotEmpty()) {
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        if (snapshot.isSearch) {
                                            runCatching { searchRequester.requestFocus() }
                                            true
                                        } else false
                                    }
                                    else -> false
                                }
                            },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            // with tabs: bell, share... follow the actions, only the sort goes to the end of the line
                            val rightStart = if (hasTabs) {
                                actionsLine.indexOfFirst { it.key == "sort" }
                            } else snapshot.left.size + snapshot.tabs.size + snapshot.afterTabs.size
                            actionsLine.forEachIndexed { index, action ->
                                if (index == rightStart && snapshot.right.isNotEmpty()) {
                                    Spacer(Modifier.weight(1f))
                                }
                                val isTab = !hasTabs && index >= snapshot.left.size && index < snapshot.left.size + snapshot.tabs.size
                                Pill(
                                    action = action,
                                    isTab = isTab,
                                    modifier = (if (action.key == firstKey) Modifier.focusRequester(firstRequester) else Modifier)
                                        .onGloballyPositioned { tops[action.key] = it.positionInRoot().y.toInt() }
                                        .onPreviewKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (event.key) {
                                                // left on the first action: the side menu
                                                Key.DirectionLeft -> if (index == 0) {
                                                    findActivity(appBar)?.openTvMenu()
                                                    true
                                                } else false
                                                Key.DirectionRight -> index == actionsLine.lastIndex
                                                Key.DirectionUp -> {
                                                    // the first line of actions: nothing above it (but the search bar)
                                                    val top = tops[action.key]
                                                    val first = actionsLine.mapNotNull { tops[it.key] }.minOrNull()
                                                    !snapshot.isSearch && (top == null || first == null || top <= first)
                                                }
                                                Key.DirectionDown -> {
                                                    val top = tops[action.key]
                                                    val last = actionsLine.mapNotNull { tops[it.key] }.maxOrNull()
                                                    if (top == null || last == null || top >= last) {
                                                        // the last line of actions: the tab shown, else the page
                                                        if (!hasTabs || tabTarget == null || runCatching { tabRequester.requestFocus() }.getOrDefault(false) != true) {
                                                            focusVisibleTabContent(screenRoot(appBar))
                                                        }
                                                        true
                                                    } else false
                                                }
                                                else -> false
                                            }
                                        }
                                )
                            }
                        }
                    }
                    if (hasTabs) {
                        if (actionsLine.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(
                            Modifier.onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> focusVisibleTabContent(screenRoot(appBar))
                                    Key.DirectionUp -> {
                                        when {
                                            // the sort on the line of the title
                                            titleActions.isNotEmpty() -> {
                                                runCatching { titleActionRequester.requestFocus() }
                                                true
                                            }
                                            // the actions above: the nearest one
                                            actionsLine.isNotEmpty() -> false
                                            snapshot.isSearch -> {
                                                runCatching { searchRequester.requestFocus() }
                                                true
                                            }
                                            else -> true
                                        }
                                    }
                                    else -> false
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            tabsLine.forEachIndexed { index, action ->
                                val isTab = index < snapshot.tabs.size
                                var modifier: Modifier = Modifier
                                if (action.key == firstKey) modifier = modifier.focusRequester(firstRequester)
                                if (action === tabTarget) modifier = modifier.focusRequester(tabRequester)
                                Pill(
                                    action = action,
                                    isTab = isTab,
                                    modifier = modifier.onPreviewKeyEvent { event ->
                                        if (index == 0 && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                            findActivity(appBar)?.openTvMenu()
                                            true
                                        } else if (index == tabsLine.lastIndex && event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                            true
                                        } else false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Pill(action: Action, isTab: Boolean, modifier: Modifier) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        // option of the Xtra TV settings: a tab shows its page after a short stop on it, without OK
        if (isTab && !action.selected) {
            val context = LocalContext.current
            val focusSwitch = remember { context.prefs().getBoolean(com.github.andreyasadchy.xtra.util.C.TV_FOCUS_SWITCH, false) }
            if (focusSwitch) {
                LaunchedEffect(focused) {
                    if (focused) {
                        // a short stop on the tab: going over it to another one does not load its page
                        kotlinx.coroutines.delay(250L)
                        action.run()
                    }
                }
            }
        }
        // refresh: its icon turns while the list loads again (at least a moment, so it is always seen)
        val turning = action.key == "refresh" && TvUi.refreshing.value
        val rotation = if (turning) {
            val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "refresh")
            transition.animateFloat(
                0f, 360f,
                androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.LinearEasing)),
                label = "rotation"
            ).value
        } else 0f
        if (isTab) {
            // a tab: its name, a line of the color of accent under the one shown (wider with the focus). No pill: the
            // tabs read as the parts of the screen, not as buttons of a phone app
            val line by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (focused) 1f else if (action.selected) 0.6f else 0f,
                animationSpec = androidx.compose.animation.core.tween(160),
                label = "tabLine"
            )
            Column(
                modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (focused) Color(0x24FFFFFF) else Color.Transparent)
                    .clickable(interactionSource = interaction, indication = null) { action.run() }
                    .padding(start = 14.dp, end = 14.dp, top = 7.dp, bottom = 5.dp)
                    .width(androidx.compose.foundation.layout.IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    action.label,
                    fontSize = 17.sp,
                    fontWeight = if (action.selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (action.selected || focused) Color.White else TvColors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 360.dp)
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth(line.coerceAtLeast(0.01f))
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (line > 0.02f) TvColors.accent else Color.Transparent)
                )
            }
            return
        }
        // the sort: what the list is sorted by, read as a text of the header; a button only with the focus
        val quiet = action.key == "sort"
        val background = when {
            focused -> TvColors.focused
            quiet -> Color.Transparent
            else -> Color(0x26FFFFFF)
        }
        val content = when {
            focused -> TvColors.onFocused
            quiet -> Color(0xD9FFFFFF)
            else -> Color.White
        }
        Row(
            modifier
                .clip(RoundedCornerShape(if (quiet) 10.dp else 24.dp))
                .background(background)
                .clickable(interactionSource = interaction, indication = null) { action.run() }
                .padding(horizontal = if (quiet) 12.dp else 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (action.icon != null) {
                Icon(
                    painterResource(action.icon),
                    contentDescription = null,
                    tint = if (turning && !focused) TvColors.accent else if (quiet && !focused) TvColors.accent else content,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }
                )
                if (action.label.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                }
            }
            if (action.label.isNotEmpty()) Text(
                action.label,
                fontSize = 15.sp,
                fontWeight = if (quiet && !focused) FontWeight.Medium else FontWeight.SemiBold,
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
