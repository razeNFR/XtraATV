package com.github.andreyasadchy.xtra.ui.player

import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource
import com.github.andreyasadchy.xtra.ui.view.TvStreamPreview
import com.github.andreyasadchy.xtra.ui.view.TvCards
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.launch

/**
 * TV: row of the streams from the list the current stream was opened from, shown over the player
 * to switch to another stream without leaving it.
 */
object TvStreamSwitcher {

    /** Puts a newer list in the row while it is shown, null when it is not shown. */
    private var updater: ((List<Stream>) -> Unit)? = null

    /** The list of the other streams was loaded again: the row shown follows it, the focus stays on the same streamer. */
    fun update(streams: List<Stream>) {
        updater?.invoke(streams)
    }

    /**
     * Selection mode of the row (multi-stream): OK checks or unchecks a stream (green badge) up to [max],
     * the "Validate" button gives the checked ones.
     */
    class Selection(
        val title: String,
        val preselected: Set<String>,
        val max: Int,
        val onValidate: (List<Stream>) -> Unit,
        /** Streams left out of the other lists (the ones already in the multi-stream). */
        val exclude: Set<String> = emptySet(),
    )

    /**
     * [rows]: other lists the row can show (followed, popular, languages, category), picked with tabs above the cards:
     * up from the cards goes to the tabs, a tab shows its list, down goes back to the cards.
     * [onPip]: OK held on a card opens it in a small window over the stream (not in selection mode).
     */
    fun show(
        activity: MainActivity,
        streams: List<Stream>,
        currentChannelLogin: String?,
        selection: Selection? = null,
        rows: List<com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Row> = emptyList(),
        onPip: ((Stream) -> Unit)? = null,
        /** Source of [streams], to load more of them at the end of the row. */
        source: (() -> androidx.paging.PagingSource<Int, Stream>)? = null,
    ) {
        if (streams.isEmpty() && rows.isEmpty()) {
            return
        }
        val checked = selection?.preselected?.toMutableSet()
        // tab shown (0: the list given), the lists already loaded, every stream shown (the checked ones of all the tabs)
        // the list of the stream has a tab of its own when it is one of the rows (followed, popular...): that tab shows it
        // and is selected, no "This list" tab next to the same list (picking streams of a multi-stream keeps it)
        val originRow = if (selection == null) com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.originRow(activity, source, rows) else -1
        val originTab = if (originRow >= 0) originRow else 0
        // the row a tab shows, null for the list of the stream
        val rowOf: (Int) -> com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Row? = { index ->
            if (originRow >= 0) {
                if (index == originRow) null else rows.getOrNull(index)
            } else {
                rows.getOrNull(index - 1)
            }
        }
        var currentTab = originTab
        val loadedTabs = mutableMapOf<Int, List<Stream>>(originTab to streams)
        val seen = LinkedHashMap<String, Stream>()
        val dialog = AppCompatDialog(activity)
        val density = activity.resources.displayMetrics.density
        val horizontalPadding = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.tv_player_scrim_bottom)
            // no side padding here: the row goes to the edges of the screen, the cards are not cut while it scrolls
            setPadding(0, (48 * density).toInt(), 0, activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(horizontalPadding + (10 * density).toInt(), 0, horizontalPadding, (4 * density).toInt())
        }
        // multi-stream: the icon of the multi-stream before its title, numbered streams and smaller cards (the row of
        // the other streams stays as it is)
        header.addView(TextView(activity).apply {
            text = selection?.title ?: activity.getString(R.string.tv_switch_stream)
            if (selection != null) {
                val icon = activity.getDrawable(R.drawable.tv_ic_multistream)?.mutate()?.apply {
                    setTint(com.github.andreyasadchy.xtra.ui.tv.TvAccent.color)
                    setBounds(0, 0, (26 * density).toInt(), (26 * density).toInt())
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = (10 * density).toInt()
            }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            // readable over a bright video
            setShadowLayer(8 * density, 0f, 2 * density, 0xE6000000.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // selection: "Validate (n)", first on the line of the tabs (seen even with many tabs), else in the header
        val validate = if (selection != null && checked != null) {
            TextView(activity).apply {
                setTextColor(activity.getColorStateList(R.color.tv_pill_text))
                setBackgroundResource(R.drawable.tv_validate_background)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                elevation = 6 * density
                isFocusable = true
                val padding = (10 * density).toInt()
                setPadding(padding * 2, padding, padding * 2, padding)
                val icon = activity.getDrawable(R.drawable.tv_ic_check)?.mutate()?.apply {
                    setTintList(activity.getColorStateList(R.color.tv_pill_text))
                    setBounds(0, 0, (20 * density).toInt(), (20 * density).toInt())
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = (8 * density).toInt()
            }
        } else null
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // multi-stream with several streams to pick: what OK does
        if (selection != null && selection.max > 1) {
            root.addView(TextView(activity).apply {
                text = activity.getString(R.string.tv_multistream_hint, selection.max)
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 14f
                setShadowLayer(6 * density, 0f, 1 * density, 0xE6000000.toInt())
                setPadding(horizontalPadding + (10 * density).toInt(), 0, horizontalPadding, (2 * density).toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // what OK held does, over the row (not when picking the streams of a multi-stream)
        if (selection == null && onPip != null) {
            root.addView(TextView(activity).apply {
                text = activity.getString(R.string.tv_switch_pip_hint)
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 14f
                setShadowLayer(6 * density, 0f, 1 * density, 0xE6000000.toInt())
                setPadding(horizontalPadding + (10 * density).toInt(), 0, horizontalPadding, (2 * density).toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // tabs of the lists: the list of the stream first, then the other rows, and Search at the end
        val baseTitles = when {
            rows.isEmpty() -> listOf(activity.getString(if (selection != null) R.string.tv_switch_suggested else R.string.tv_switch_current))
            originRow >= 0 -> rows.map { it.title }
            else -> listOf(activity.getString(if (selection != null) R.string.tv_switch_suggested else R.string.tv_switch_current)) + rows.map { it.title }
        }
        val tabTitles = baseTitles + activity.getString(R.string.search)
        val searchTabIndex = tabTitles.lastIndex
        val tabViews = mutableListOf<TextView>()
        if (tabTitles.size > 1) {
            val tabsLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(horizontalPadding + (4 * density).toInt(), (8 * density).toInt(), horizontalPadding, (2 * density).toInt())
            }
            validate?.let { button ->
                tabsLayout.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (18 * density).toInt()
                })
            }
            tabTitles.forEachIndexed { index, title ->
                tabViews += TextView(activity).apply {
                    text = title
                    textSize = 15f
                    isFocusable = true
                    setTextColor(activity.getColorStateList(R.color.tv_pill_text))
                    setBackgroundResource(R.drawable.tv_tab_chip_background)
                    val padding = (8 * density).toInt()
                    setPadding(padding * 2, padding, padding * 2, padding)
                    setShadowLayer(4 * density, 0f, 1 * density, 0x99000000.toInt())
                    if (index == searchTabIndex) {
                        val icon = activity.getDrawable(R.drawable.baseline_search_black_24)?.mutate()?.apply {
                            setTintList(activity.getColorStateList(R.color.tv_pill_text))
                            setBounds(0, 0, (18 * density).toInt(), (18 * density).toInt())
                        }
                        setCompoundDrawablesRelative(icon, null, null, null)
                        compoundDrawablePadding = (6 * density).toInt()
                    }
                }.also { tabsLayout.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (10 * density).toInt() }) }
            }
            tabViews.getOrNull(originTab)?.isSelected = true
            root.addView(android.widget.HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength((36 * density).toInt())
                isFocusable = false
                addView(tabsLayout)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val recyclerView = RecyclerView(activity).apply {
            clipToPadding = false
            clipChildren = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength((48 * density).toInt())
            setPadding(horizontalPadding, (8 * density).toInt(), horizontalPadding, (8 * density).toInt())
        }
        // card the focus goes to once it is shown (left / right pressed faster than the row scrolls)
        var pendingTarget = RecyclerView.NO_POSITION
        recyclerView.layoutManager = object : LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false) {
            // left / right stay in the row: the next card not shown yet, the row scrolls smoothly to it and the focus
            // follows when it is there (no jump, the tabs above are never reached); at the end the last card keeps it
            override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                if (direction != View.FOCUS_RIGHT && direction != View.FOCUS_LEFT) {
                    return null
                }
                val item = findContainingItemView(focused) ?: return null
                val step = if (direction == View.FOCUS_RIGHT) 1 else -1
                val base = if (pendingTarget != RecyclerView.NO_POSITION) pendingTarget else getPosition(item)
                val target = base + step
                if (target !in 0 until itemCount) {
                    return item
                }
                val view = findViewByPosition(target)
                if (view != null && pendingTarget == RecyclerView.NO_POSITION) {
                    return view
                }
                pendingTarget = target
                recyclerView.smoothScrollToPosition(target)
                return item
            }
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                focusPending(recyclerView, false)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    focusPending(recyclerView, true)
                }
            }

            private fun focusPending(recyclerView: RecyclerView, idle: Boolean) {
                val target = pendingTarget
                if (target == RecyclerView.NO_POSITION) {
                    return
                }
                val view = recyclerView.layoutManager?.findViewByPosition(target)
                // the card fully in the row (or the scroll ended): it takes the focus
                if (view != null && (idle || (view.left >= 0 && view.right <= recyclerView.width))) {
                    pendingTarget = RecyclerView.NO_POSITION
                    view.requestFocus()
                } else if (idle) {
                    pendingTarget = RecyclerView.NO_POSITION
                }
            }
        })
        // live previews like on the lists, the stream behind is silenced while a preview with sound plays
        val preview = if (activity.prefs().getBoolean(C.UI_TV_PREVIEW, true)) {
            TvStreamPreview(recyclerView, inPlayer = true) { playing ->
                if (TvPreviewSource.hasSound(activity)) {
                    (activity.playerFragment as? PlayerFragment)?.setDucked(playing)
                }
            }
        } else null
        lateinit var adapter: Adapter
        // a multi-stream needs 2 streams at least
        val minChecked = if ((selection?.max ?: 0) > 1) 2 else 1
        val updateValidate = {
            validate?.text = activity.getString(R.string.tv_multistream_validate, checked?.size ?: 0)
            // not enough streams checked: the button is dimmed and does nothing
            validate?.alpha = if ((checked?.size ?: 0) < minChecked) 0.45f else 1f
        }
        updateValidate()
        // no tabs: "Validate" at the right of the title
        if (validate != null && validate.parent == null) {
            header.addView(validate)
        }
        // down from "Validate": the cards
        validate?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
                (recyclerView.layoutManager?.findViewByPosition((recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
                    ?: recyclerView.getChildAt(0))?.requestFocus()
                true
            } else false
        }
        // multi-stream: smaller cards, more streams in sight
        adapter = Adapter(streams, ((if (selection != null) 175 else 250) * density).toInt(), preview, checked) { stream ->
            if (selection != null && checked != null) {
                val login = stream.channelLogin ?: return@Adapter
                when {
                    login in checked -> checked.remove(login)
                    selection.max == 1 -> {
                        // one stream to pick (change or add a stream): picked at once
                        dialog.dismiss()
                        selection.onValidate(listOf(stream))
                        return@Adapter
                    }
                    checked.size >= selection.max -> {
                        android.widget.Toast.makeText(activity, activity.getString(R.string.tv_multistream_max, selection.max), android.widget.Toast.LENGTH_SHORT).show()
                        return@Adapter
                    }
                    else -> checked.add(login)
                }
                // the numbers of the other selected streams follow (one taken away: the next ones move up)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                updateValidate()
            } else {
                dialog.dismiss()
                // the info of the stream reached for a moment, like left / right
                PlayerFragment.peekHudOnStart = true
                val row = rowOf(currentTab)
                if (row != null) {
                    // a stream of another list: that list becomes the one of left / right and of this row
                    activity.startStream(stream, adapter.streams, row.source)
                } else {
                    activity.startStream(stream)
                }
            }
        }
        if (selection == null && onPip != null) {
            adapter.onLongClick = { stream ->
                if (stream.channelLogin != null && stream.channelLogin == currentChannelLogin) {
                    // the stream watched is already on the screen: no small window of it
                    android.widget.Toast.makeText(activity, R.string.tv_pip_already_shown, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    onPip(stream)
                }
            }
        }
        recyclerView.adapter = adapter
        // more of the list near its end (30 streams by page), for the list of the stream and the other tabs
        val pages = mutableMapOf<Int, com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Pages>()
        if (source != null) {
            pages[originTab] = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Pages(source)
        }
        var moreJob: kotlinx.coroutines.Job? = null
        adapter.onFocused = { position ->
            val tab = currentTab
            val tabPages = pages[tab]
            if (tabPages != null && tabPages.hasMore && moreJob?.isActive != true && position >= adapter.streams.size - 6) {
                moreJob = activity.lifecycleScope.launch {
                    val known = adapter.streams.mapNotNull { it.channelLogin }.toMutableSet()
                    val added = mutableListOf<Stream>()
                    // the list of the stream starts again from its first page: the pages already in the row are passed
                    var tries = 0
                    while (added.isEmpty() && tabPages.hasMore && tries++ < 8) {
                        val page = tabPages.next() ?: break
                        page.forEach { stream ->
                            val login = stream.channelLogin
                            if (login != null && login !in known && login !in (selection?.exclude ?: emptySet())) {
                                known += login
                                added += stream
                            }
                        }
                    }
                    if (added.isEmpty() || !dialog.isShowing || currentTab != tab) {
                        return@launch
                    }
                    val start = adapter.streams.size
                    adapter.streams = adapter.streams + added
                    loadedTabs[tab] = adapter.streams
                    added.forEach { stream -> stream.channelLogin?.let { seen.putIfAbsent(it, stream) } }
                    adapter.notifyItemRangeInserted(start, added.size)
                    if (tab == originTab && selection == null) {
                        activity.onQueueExtended(adapter.streams, source)
                    }
                }
            }
        }
        validate?.setOnClickListener {
            val logins = checked ?: return@setOnClickListener
            if (logins.size < minChecked) {
                android.widget.Toast.makeText(activity, activity.getString(R.string.tv_multistream_min, minChecked), android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            // in the order of the row, the current stream first
            val all = (adapter.streams + streams + seen.values).distinctBy { it.channelLogin }
            selection?.onValidate(all.filter { it.channelLogin in logins }.sortedBy { if (it.channelLogin == currentChannelLogin) 0 else 1 })
        }
        root.addView(recyclerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // a tab with the focus shows its list (loaded once), after a short stop on it
        var tabJob: kotlinx.coroutines.Job? = null
        var searchQuery = ""
        val showTab = { index: Int, list: List<Stream> ->
            currentTab = index
            pendingTarget = RecyclerView.NO_POSITION
            moreJob?.cancel()
            tabViews.forEachIndexed { i, view -> view.isSelected = i == index }
            preview?.stop()
            list.forEach { stream -> stream.channelLogin?.let { seen.putIfAbsent(it, stream) } }
            adapter.streams = list
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
        }
        val runSearch: (String, TextView) -> Unit = { query, view ->
            val trimmed = query.trim()
            if (trimmed.isNotEmpty()) {
                searchQuery = trimmed
                val label = "${activity.getString(R.string.search)}: $trimmed"
                tabJob?.cancel()
                tabViews.forEachIndexed { i, tab -> tab.text = if (i == searchTabIndex && searchQuery.isNotEmpty()) label else tabTitles[i] }
                tabJob = activity.lifecycleScope.launch {
                    view.text = activity.getString(R.string.tv_switch_loading, label)
                    val searchPages = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Pages {
                        com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.search(activity, trimmed)
                    }
                    pages[searchTabIndex] = searchPages
                    val list = searchPages.next()?.distinctBy { it.channelLogin }
                        ?.filter { it.channelLogin !in (selection?.exclude ?: emptySet()) }
                        ?.also { loadedTabs[searchTabIndex] = it }
                    view.text = label
                    if (!dialog.isShowing) {
                        return@launch
                    }
                    if (list.isNullOrEmpty()) {
                        android.widget.Toast.makeText(activity, if (list == null) R.string.tv_switch_error else R.string.tv_switch_empty, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        showTab(searchTabIndex, list)
                        recyclerView.post { recyclerView.layoutManager?.findViewByPosition(0)?.requestFocus() }
                    }
                }
            }
        }
        tabViews.forEachIndexed { index, view ->
            // OK on a tab shows its list (loaded once); on Search it always opens the search box
            view.setOnClickListener {
                if (index == searchTabIndex) {
                    com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(activity) { dismiss ->
                        com.github.andreyasadchy.xtra.ui.tv.ChatComposer(
                            title = activity.getString(R.string.search),
                            hint = activity.getString(R.string.search),
                            sendLabel = activity.getString(R.string.search),
                        ) { text ->
                            dismiss()
                            runSearch(text, view)
                        }
                    }
                    return@setOnClickListener
                }
                if (index == currentTab) {
                    recyclerView.getChildAt(0)?.requestFocus()
                    return@setOnClickListener
                }
                tabJob?.cancel()
                tabViews.forEachIndexed { i, tab -> tab.text = if (i == searchTabIndex && searchQuery.isNotEmpty()) "${activity.getString(R.string.search)}: $searchQuery" else tabTitles[i] }
                tabJob = activity.lifecycleScope.launch {
                    val cached = loadedTabs[index]
                    val list = cached ?: run {
                        // loading: the tab says so until the list comes
                        view.text = activity.getString(R.string.tv_switch_loading, tabTitles[index])
                        rowOf(index)?.let { row ->
                            val rowPages = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Pages(row.source)
                            pages[index] = rowPages
                            rowPages.next()?.distinctBy { it.channelLogin }
                        }?.filter { it.channelLogin !in (selection?.exclude ?: emptySet()) }?.also { loadedTabs[index] = it }
                    }
                    view.text = tabTitles[index]
                    if (!dialog.isShowing) {
                        return@launch
                    }
                    if (list.isNullOrEmpty()) {
                        android.widget.Toast.makeText(activity, if (list == null) R.string.tv_switch_error else R.string.tv_switch_empty, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        showTab(index, list)
                        // the list is there: the focus goes to its first stream
                        recyclerView.post { recyclerView.layoutManager?.findViewByPosition(0)?.requestFocus() }
                    }
                }
            }
            // down from a tab: the cards of the list shown
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    (recyclerView.layoutManager?.findViewByPosition((recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
                        ?: recyclerView.getChildAt(0))?.requestFocus()
                    true
                } else false
            }
        }
        streams.forEach { stream -> stream.channelLogin?.let { seen.putIfAbsent(it, stream) } }
        // left / right held: one card at a time at a pace the row can follow
        var lastMove = 0L
        dialog.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) &&
                event.action == android.view.KeyEvent.ACTION_DOWN && recyclerView.hasFocus()) {
                val now = android.os.SystemClock.uptimeMillis()
                if (event.repeatCount > 0 && now - lastMove < 120L) {
                    return@setOnKeyListener true
                }
                lastMove = now
            }
            false
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0f)
        }
        val currentIndex = streams.indexOfFirst { it.channelLogin != null && it.channelLogin == currentChannelLogin }.coerceAtLeast(0)
        recyclerView.scrollToPosition(currentIndex)
        dialog.setOnShowListener {
            recyclerView.post {
                val view = recyclerView.layoutManager?.findViewByPosition(currentIndex)
                if (view == null || !view.requestFocus()) {
                    recyclerView.getChildAt(0)?.requestFocus()
                }
            }
        }
        val update: (List<Stream>) -> Unit = update@{ list ->
            // only when streamers came or went or moved, not for new viewer counts: the row does not move for nothing
            if (!dialog.isShowing || list.isEmpty() || currentTab != originTab || list.map { it.channelLogin } == adapter.streams.map { it.channelLogin }) {
                return@update
            }
            loadedTabs[originTab] = list
            val old = adapter.streams
            if (list.size > old.size && list.subList(0, old.size).map { it.channelLogin } == old.map { it.channelLogin }) {
                // only more streams at the end: added after the row, the focus does not move
                adapter.streams = list
                adapter.notifyItemRangeInserted(old.size, list.size - old.size)
                return@update
            }
            val focusedLogin = recyclerView.focusedChild?.let { recyclerView.getChildAdapterPosition(it) }
                ?.let { adapter.streams.getOrNull(it)?.channelLogin }
            adapter.streams = list
            adapter.notifyDataSetChanged()
            val index = list.indexOfFirst { it.channelLogin != null && it.channelLogin == focusedLogin }
            if (index >= 0) {
                recyclerView.scrollToPosition(index)
                recyclerView.post { recyclerView.layoutManager?.findViewByPosition(index)?.requestFocus() }
            }
        }
        if (selection == null) {
            updater = update
        }
        dialog.setOnDismissListener {
            if (updater === update) {
                updater = null
            }
            preview?.release()
            tabJob?.cancel()
            moreJob?.cancel()
        }
        dialog.show()
        if (streams.isEmpty()) {
            // nothing in the list of the stream: the first other row is shown
            tabViews.getOrNull(1)?.post { tabViews[1].requestFocus(); tabViews[1].performClick() }
        }
    }

    private class Adapter(
        var streams: List<Stream>,
        private val itemWidth: Int,
        private val preview: TvStreamPreview?,
        /** Selection mode: the logins checked, they get a green badge. */
        private val checked: Set<String>?,
        private val onClick: (Stream) -> Unit,
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {

        /** OK held on a card (the small window of the player), none in selection mode. */
        var onLongClick: ((Stream) -> Unit)? = null

        /** A card got the focus (its place in the list): more of the list is loaded near its end. */
        var onFocused: ((Int) -> Unit)? = null

        class ViewHolder(val binding: FragmentStreamsListItemBinding, val badge: TextView) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = streams.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = FragmentStreamsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            binding.root.layoutParams = binding.root.layoutParams.apply { width = itemWidth }
            TvCards.prepare(binding.root)
            val density = parent.resources.displayMetrics.density
            // number of a selected stream (its place in the multi-stream), at the top right of the card, in the color of accent
            val badge = TextView(parent.context).apply {
                gravity = Gravity.CENTER
                setTextColor(com.github.andreyasadchy.xtra.ui.tv.TvAccent.onAccent)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(com.github.andreyasadchy.xtra.ui.tv.TvAccent.color)
                    setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
                }
                visibility = View.GONE
                elevation = 8 * density
            }
            (binding.root as? android.widget.FrameLayout)?.addView(badge, android.widget.FrameLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt(), Gravity.TOP or Gravity.END).apply {
                val margin = (8 * density).toInt()
                setMargins(margin, margin, margin, margin)
            })
            return ViewHolder(binding, badge)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val stream = streams[position]
            val context = holder.itemView.context
            val number = checked?.indexOf(stream.channelLogin)?.takeIf { it >= 0 }?.plus(1)
            holder.badge.visibility = if (number != null) View.VISIBLE else View.GONE
            holder.badge.text = number?.toString()
            with(holder.binding) {
                root.setOnClickListener { onClick(stream) }
                val longClick = onLongClick
                if (longClick != null) {
                    root.setOnLongClickListener {
                        longClick(stream)
                        true
                    }
                } else {
                    root.setOnLongClickListener(null)
                    root.isLongClickable = false
                }
                root.setOnFocusChangeListener { view, hasFocus ->
                    com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(title, hasFocus)
                    com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(gameName, hasFocus)
                    if (hasFocus) {
                        onFocused?.invoke(holder.bindingAdapterPosition)
                        preview?.schedule(view, stream.channelLogin)
                    } else if (preview?.isPreviewing(view) == true) {
                        preview.stop()
                    }
                }
                thumbnail.visibility = View.VISIBLE
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context).apply {
                        data(com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.picture(stream, stream.thumbnail))
                        crossfade(true)
                        target(thumbnail)
                    }.build()
                )
                if (stream.channelImage != null) {
                    userImage.visibility = View.VISIBLE
                    context.imageLoader.enqueue(
                        ImageRequest.Builder(context).apply {
                            data(stream.channelImage)
                            transformations(CircleCropTransformation())
                            crossfade(true)
                            target(userImage)
                        }.build()
                    )
                } else {
                    userImage.visibility = View.GONE
                }
                title.visibility = if (stream.title.isNullOrBlank()) View.GONE else View.VISIBLE
                title.text = stream.title?.trim()
                username.visibility = View.VISIBLE
                username.text = stream.channelName ?: stream.channelLogin
                gameName.visibility = if (stream.gameName.isNullOrBlank()) View.GONE else View.VISIBLE
                gameName.text = stream.gameName
                val count = stream.viewerCount
                if (count != null) {
                    viewers.visibility = View.VISIBLE
                    viewers.text = context.resources.getQuantityString(
                        R.plurals.viewers,
                        count,
                        TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                    )
                } else {
                    viewers.visibility = View.GONE
                }
                uptime.visibility = View.GONE
                tagsLayout.visibility = View.GONE
            }
        }
    }
}
