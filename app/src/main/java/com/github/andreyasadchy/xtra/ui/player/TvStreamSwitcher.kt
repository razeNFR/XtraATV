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
        var currentTab = 0
        val loadedTabs = mutableMapOf<Int, List<Stream>>(0 to streams)
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
        header.addView(TextView(activity).apply {
            text = selection?.title ?: activity.getString(R.string.tv_switch_stream)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            // readable over a bright video
            setShadowLayer(8 * density, 0f, 2 * density, 0xE6000000.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // selection: "Validate (n)" over the row, up from the cards
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
            }.also { header.addView(it) }
        } else null
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // tabs of the lists: the list of the stream first, then the other rows
        val tabTitles = if (rows.isNotEmpty()) {
            listOf(activity.getString(if (selection != null) R.string.tv_switch_suggested else R.string.tv_switch_current)) + rows.map { it.title }
        } else emptyList()
        val tabViews = mutableListOf<TextView>()
        if (tabTitles.size > 1) {
            val tabsLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(horizontalPadding + (4 * density).toInt(), (8 * density).toInt(), horizontalPadding, (2 * density).toInt())
            }
            tabTitles.forEach { title ->
                tabViews += TextView(activity).apply {
                    text = title
                    textSize = 15f
                    isFocusable = true
                    setTextColor(activity.getColorStateList(R.color.tv_pill_text))
                    setBackgroundResource(R.drawable.tv_tab_chip_background)
                    val padding = (8 * density).toInt()
                    setPadding(padding * 2, padding, padding * 2, padding)
                    setShadowLayer(4 * density, 0f, 1 * density, 0x99000000.toInt())
                }.also { tabsLayout.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (10 * density).toInt() }) }
            }
            tabViews.firstOrNull()?.isSelected = true
            root.addView(android.widget.HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                addView(tabsLayout)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val recyclerView = RecyclerView(activity).apply {
            clipToPadding = false
            clipChildren = false
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
        adapter = Adapter(streams, (250 * density).toInt(), preview, checked) { stream ->
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
                adapter.streams.indexOf(stream).takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
                updateValidate()
            } else {
                dialog.dismiss()
                val row = rows.getOrNull(currentTab - 1)
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
                dialog.dismiss()
                onPip(stream)
            }
        }
        recyclerView.adapter = adapter
        // more of the list near its end (30 streams by page), for the list of the stream and the other tabs
        val pages = mutableMapOf<Int, com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Pages>()
        if (source != null && selection == null) {
            pages[0] = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Pages(source)
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
                    if (tab == 0) {
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
        tabViews.forEachIndexed { index, view ->
            // OK on a tab shows its list (loaded once); moving over the tabs changes nothing
            view.setOnClickListener {
                if (index == currentTab) {
                    recyclerView.getChildAt(0)?.requestFocus()
                    return@setOnClickListener
                }
                tabJob?.cancel()
                tabViews.forEachIndexed { i, tab -> tab.text = tabTitles[i] }
                tabJob = activity.lifecycleScope.launch {
                    val cached = loadedTabs[index]
                    val list = cached ?: run {
                        // loading: the tab says so until the list comes
                        view.text = activity.getString(R.string.tv_switch_loading, tabTitles[index])
                        rows.getOrNull(index - 1)?.let { row ->
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
            if (!dialog.isShowing || list.isEmpty() || currentTab != 0 || list.map { it.channelLogin } == adapter.streams.map { it.channelLogin }) {
                return@update
            }
            loadedTabs[0] = list
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
        updater = update
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

        class ViewHolder(val binding: FragmentStreamsListItemBinding, val badge: android.widget.ImageView) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = streams.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = FragmentStreamsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            binding.root.layoutParams = binding.root.layoutParams.apply { width = itemWidth }
            TvCards.prepare(binding.root)
            val density = parent.resources.displayMetrics.density
            // green check of a selected stream, at the top right of the card
            val badge = android.widget.ImageView(parent.context).apply {
                setImageResource(R.drawable.tv_ic_check)
                setColorFilter(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFF22C55E.toInt())
                    setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
                }
                val padding = (5 * density).toInt()
                setPadding(padding, padding, padding, padding)
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
            holder.badge.visibility = if (checked != null && stream.channelLogin in checked) View.VISIBLE else View.GONE
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
