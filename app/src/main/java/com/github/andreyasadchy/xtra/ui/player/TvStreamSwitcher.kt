package com.github.andreyasadchy.xtra.ui.player

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
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
import com.github.andreyasadchy.xtra.ui.tv.TvAccent
import com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource
import com.github.andreyasadchy.xtra.ui.tv.TvToast
import com.github.andreyasadchy.xtra.ui.view.TvCards
import com.github.andreyasadchy.xtra.ui.view.TvStreamPreview
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
     * Selection mode of the row (multi-stream): OK checks or unchecks a stream (accent badge) up to [max],
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

    private class TabEntry(
        val title: String,
        val row: com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Row? = null,
        val isOrigin: Boolean = false,
        val isSearch: Boolean = false,
        val iconRes: Int? = null,
    )

    private fun measureFixedCardsHeight(activity: MainActivity, cardWidthPx: Int, verticalPaddingPx: Int): Int {
        return runCatching {
            val dummyParent = FrameLayout(activity)
            val sample = FragmentStreamsListItemBinding.inflate(LayoutInflater.from(activity), dummyParent, false)
            val lp = sample.root.layoutParams as? ViewGroup.MarginLayoutParams
            val topMargin = lp?.topMargin ?: 0
            val bottomMargin = lp?.bottomMargin ?: 0
            sample.thumbnail.visibility = View.VISIBLE
            sample.userImage.visibility = View.VISIBLE
            sample.title.visibility = View.VISIBLE
            sample.title.text = "Sample stream title"
            sample.username.visibility = View.VISIBLE
            sample.username.text = "Streamer"
            sample.gameName.visibility = View.VISIBLE
            sample.gameName.text = "Category"
            sample.uptime.visibility = View.GONE
            sample.tagsLayout.visibility = View.GONE
            val wSpec = View.MeasureSpec.makeMeasureSpec(cardWidthPx, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            sample.root.measure(wSpec, hSpec)
            val measured = sample.root.measuredHeight
            if (measured > 0) {
                measured + topMargin + bottomMargin + verticalPaddingPx
            } else {
                error("unmeasured")
            }
        }.getOrElse {
            val density = activity.resources.displayMetrics.density
            (cardWidthPx * 9) / 16 + (78 * density).roundToInt() + verticalPaddingPx
        }
    }

    /**
     * Styles a category pill inside the Unified Smoked-Glass Rail (Option A):
     * - [focused]: solid light pill (`#FFF2F2F7`) with high-contrast dark accent text/icon.
     * - [active] (selected while focus is down on the stream cards): rich `TvAccent` tinted pill + border + crisp white label.
     * - inactive: transparent pill inside the smoked-glass capsule rail.
     */
    private fun applyRailTabStyle(
        tab: TextView,
        icon: Drawable?,
        density: Float,
        active: Boolean,
        focused: Boolean,
        loading: Boolean,
    ) {
        val accent = TvAccent.color
        val accentOnDark = TvAccent.onDark
        val accentOnLight = TvAccent.onLight
        when {
            focused -> {
                tab.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f * density
                    setColor(0xFFF2F2F7.toInt())
                }
                tab.setTextColor(accentOnLight)
                tab.setTypeface(null, Typeface.BOLD)
                tab.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                icon?.setTint(accentOnLight)
                tab.alpha = 1f
            }
            active -> {
                val topTint = ColorUtils.blendARGB(0xFF1E192B.toInt(), accent, 0.56f)
                val bottomTint = ColorUtils.blendARGB(0xFF161320.toInt(), accent, 0.34f)
                val strokeColor = (accentOnDark and 0x00FFFFFF) or 0xDE000000.toInt()
                tab.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(topTint, bottomTint),
                ).apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f * density
                    setStroke(maxOf(1, (1.5f * density).roundToInt()), strokeColor)
                }
                tab.setTextColor(Color.WHITE)
                tab.setTypeface(null, Typeface.BOLD)
                tab.setShadowLayer(3f * density, 0f, 1f * density, 0x8C000000.toInt())
                icon?.setTint(Color.WHITE)
                tab.alpha = if (loading) 0.78f else 1f
            }
            else -> {
                tab.background = null
                val fg = 0xD4FFFFFF.toInt()
                val iconFg = 0xBFFFFFFF.toInt()
                tab.setTextColor(fg)
                tab.setTypeface(null, Typeface.NORMAL)
                tab.setShadowLayer(2f * density, 0f, 1f * density, 0x66000000)
                icon?.setTint(iconFg)
                tab.alpha = if (loading) 0.65f else 1f
            }
        }
    }

    /**
     * [rows]: other lists the row can show (Stream Together, followed, popular, languages, category), picked with tabs above the cards:
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
        val exclude = selection?.exclude.orEmpty()
        val effectiveRows = rows.filter { row ->
            row.preloaded == null || row.preloaded.any { !it.channelLogin.isNullOrBlank() && it.channelLogin !in exclude }
        }
        if (streams.isEmpty() && effectiveRows.isEmpty()) {
            return
        }
        val checked = selection?.preselected?.toMutableSet()
        // tab shown (Stream Together first when active, else the list of the stream), the lists already loaded, every stream shown
        val omitEmptyCurrentTab = selection == null && streams.isEmpty() && source == null && effectiveRows.isNotEmpty()
        val originRow = if (selection == null && !omitEmptyCurrentTab) {
            com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.originRow(activity, source, effectiveRows)
        } else {
            -1
        }
        val originFallbackTitle = activity.getString(if (selection != null) R.string.tv_switch_suggested else R.string.tv_switch_current)
        val baseEntries = when {
            effectiveRows.isEmpty() -> listOf(TabEntry(originFallbackTitle, isOrigin = true))
            selection != null -> listOf(TabEntry(originFallbackTitle, isOrigin = true)) + effectiveRows.map {
                TabEntry(it.title, row = it, iconRes = it.iconRes)
            }
            omitEmptyCurrentTab -> effectiveRows.map {
                TabEntry(it.title, row = it, iconRes = it.iconRes)
            }
            originRow >= 0 -> effectiveRows.mapIndexed { idx, r ->
                if (idx == originRow) {
                    TabEntry(r.title, row = null, isOrigin = true, iconRes = r.iconRes)
                } else {
                    TabEntry(r.title, row = r, iconRes = r.iconRes)
                }
            }
            else -> {
                val coStreamRow = effectiveRows.firstOrNull { it.kind == com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.KIND_COSTREAM }
                val restRows = effectiveRows.filter { it.kind != com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.KIND_COSTREAM }
                buildList {
                    if (coStreamRow != null) {
                        add(TabEntry(coStreamRow.title, row = coStreamRow, iconRes = coStreamRow.iconRes))
                    }
                    add(TabEntry(originFallbackTitle, isOrigin = true))
                    restRows.forEach { add(TabEntry(it.title, row = it, iconRes = it.iconRes)) }
                }
            }
        }
        val tabEntries = baseEntries + TabEntry(
            title = activity.getString(R.string.search),
            isSearch = true,
            iconRes = R.drawable.baseline_search_black_24,
        )
        val tabTitles = tabEntries.map { it.title }
        val searchTabIndex = tabEntries.indexOfFirst { it.isSearch }
        val originTab = tabEntries.indexOfFirst { it.isOrigin }
        val rowOf: (Int) -> com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Row? = { index ->
            tabEntries.getOrNull(index)?.row
        }
        val loadedTabs = mutableMapOf<Int, List<Stream>>()
        if (originTab >= 0) {
            loadedTabs[originTab] = streams
        }
        tabEntries.forEachIndexed { idx, entry ->
            entry.row?.preloaded?.let { pre ->
                loadedTabs[idx] = pre.filter { !it.channelLogin.isNullOrBlank() && it.channelLogin !in exclude }
            }
        }
        val coStreamTabIndex = if (selection == null) {
            tabEntries.indexOfFirst { it.row?.kind == com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.KIND_COSTREAM }
        } else {
            -1
        }
        val initialTab = if (coStreamTabIndex >= 0 && !loadedTabs[coStreamTabIndex].isNullOrEmpty()) {
            coStreamTabIndex
        } else {
            originTab.coerceAtLeast(0)
        }
        var currentTab = initialTab
        val initialStreams = loadedTabs[initialTab].orEmpty()
        val seen = LinkedHashMap<String, Stream>()

        // Fullscreen edge-to-edge dialog so Android 14 WindowManager never slides or resizes a WRAP_CONTENT bottom surface
        val dialog = object : ComponentDialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
            override fun onStart() {
                super.onStart()
                TvToast.onDialogShown(this)
            }

            override fun onStop() {
                TvToast.onDialogDismissed(this)
                super.onStop()
            }
        }
        val density = activity.resources.displayMetrics.density
        val horizontalPadding = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)

        val outer = FrameLayout(activity).apply {
            isFocusable = false
            setOnClickListener { dialog.dismiss() }
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = false
            setBackgroundResource(R.drawable.tv_player_scrim_bottom)
            // no side padding here: the row goes to the edges of the screen, the cards are not cut while it scrolls
            setPadding(0, (48 * density).toInt(), 0, activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical))
        }
        outer.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(horizontalPadding + (10 * density).toInt(), 0, horizontalPadding, (4 * density).toInt())
        }
        header.addView(TextView(activity).apply {
            text = selection?.title ?: activity.getString(R.string.tv_switch_stream)
            if (selection != null) {
                val icon = activity.getDrawable(R.drawable.tv_ic_multistream)?.mutate()?.apply {
                    setTint(TvAccent.color)
                    setBounds(0, 0, (26 * density).toInt(), (26 * density).toInt())
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = (10 * density).toInt()
            }
            setTextColor(Color.WHITE)
            textSize = 20f
            setShadowLayer(8 * density, 0f, 2 * density, 0xE6000000.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Multi-stream with >1 streams to pick: "Validate (n)" detached to the left of the smoked-glass tab rail
        val validate = if (selection != null && selection.max > 1 && checked != null) {
            TextView(activity).apply {
                setTextColor(activity.getColorStateList(R.color.tv_pill_text))
                setBackgroundResource(R.drawable.tv_validate_background)
                textSize = 16.5f
                setTypeface(typeface, Typeface.BOLD)
                elevation = 6 * density
                isFocusable = true
                val hPad = (18 * density).toInt()
                val vPad = (9 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                val icon = activity.getDrawable(R.drawable.tv_ic_check)?.mutate()?.apply {
                    setTintList(activity.getColorStateList(R.color.tv_pill_text))
                    setBounds(0, 0, (19 * density).toInt(), (19 * density).toInt())
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = (8 * density).toInt()
            }
        } else null

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (selection != null && selection.max > 1) {
            root.addView(TextView(activity).apply {
                text = activity.getString(R.string.tv_multistream_hint, selection.max)
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 14f
                setShadowLayer(6 * density, 0f, 1 * density, 0xE6000000.toInt())
                setPadding(horizontalPadding + (10 * density).toInt(), 0, horizontalPadding, (2 * density).toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        if (selection == null && onPip != null) {
            val hintRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(horizontalPadding + (8 * density).toInt(), (4 * density).toInt(), horizontalPadding, (4 * density).toInt())
            }
            val accentColor = TvAccent.onDark
            val hintBadge = TextView(activity).apply {
                text = activity.getString(R.string.tv_switch_pip_hint)
                setTextColor(Color.WHITE)
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f * density
                    setColor(0xE016151E.toInt())
                    setStroke(
                        maxOf(1, (1.5f * density).roundToInt()),
                        (accentColor and 0x00FFFFFF) or 0xCC000000.toInt(),
                    )
                }
                val icon = activity.getDrawable(R.drawable.tv_ic_pip)?.mutate()?.apply {
                    setTint(accentColor)
                    val iconPx = (18 * density).toInt()
                    setBounds(0, 0, iconPx, iconPx)
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = (8 * density).toInt()
                setPadding((14 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
                elevation = 4 * density
            }
            hintRow.addView(hintBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(hintRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // a multi-stream needs 2 streams at least
        val minChecked = if ((selection?.max ?: 0) > 1) 2 else 1

        // Option A: Unified Smoked-Glass Rail wrapping all category tabs
        val tabViews = mutableListOf<TextView>()
        val tabIcons = mutableListOf<Drawable?>()
        var tabsScrollView: HorizontalScrollView? = null
        var railContainer: LinearLayout? = null
        var loadingTabIndex = -1

        val refreshTabStyles = {
            tabViews.forEachIndexed { idx, tv ->
                val isActive = (!omitEmptyCurrentTab || initialStreams.isNotEmpty() || loadedTabs[currentTab] != null) && idx == currentTab
                tv.isSelected = isActive
                applyRailTabStyle(
                    tab = tv,
                    icon = tabIcons.getOrNull(idx),
                    density = density,
                    active = isActive,
                    focused = tv.hasFocus(),
                    loading = loadingTabIndex == idx,
                )
            }
        }

        if (tabEntries.size > 1) {
            val tabsOuterLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(horizontalPadding + (4 * density).toInt(), (8 * density).toInt(), horizontalPadding, (4 * density).toInt())
            }
            validate?.let { button ->
                tabsOuterLayout.addView(
                    button,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (14 * density).toInt()
                    },
                )
            }
            val railPad = (4 * density).roundToInt()
            val rail = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(railPad, railPad, railPad, railPad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f * density
                    setColor(0xEB12111A.toInt())
                    setStroke(maxOf(1, (1f * density).roundToInt()), 0x30FFFFFF)
                }
                elevation = 6 * density
            }
            railContainer = rail
            tabEntries.forEachIndexed { idx, entry ->
                val iconDrawable = entry.iconRes?.let { iconId ->
                    activity.getDrawable(iconId)?.mutate()?.apply {
                        val px = (17 * density).roundToInt()
                        setBounds(0, 0, px, px)
                    }
                }
                tabIcons += iconDrawable
                val tv = TextView(activity).apply {
                    text = entry.title
                    textSize = 14.5f
                    isSingleLine = true
                    isFocusable = true
                    gravity = Gravity.CENTER_VERTICAL
                    val hPad = (15 * density).roundToInt()
                    val vPad = (8 * density).roundToInt()
                    setPadding(hPad, vPad, hPad, vPad)
                    if (iconDrawable != null) {
                        setCompoundDrawablesRelative(iconDrawable, null, null, null)
                        compoundDrawablePadding = (6 * density).roundToInt()
                    }
                }
                tabViews += tv
                rail.addView(
                    tv,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (idx < tabEntries.lastIndex) {
                            marginEnd = (3 * density).roundToInt()
                        }
                    },
                )
            }
            tabsOuterLayout.addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            refreshTabStyles()
            tabsScrollView = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                clipChildren = false
                clipToPadding = false
                addView(tabsOuterLayout)
            }
            root.addView(tabsScrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val cardWidthPx = ((if (selection != null) 175 else 216) * density).toInt()
        val rvVerticalPad = (8 * density).toInt()
        val fixedCardsHeightPx = measureFixedCardsHeight(activity, cardWidthPx, rvVerticalPad * 2)

        val cardsContainer = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
        }
        val recyclerView = RecyclerView(activity).apply {
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
            setPadding(horizontalPadding, rvVerticalPad, horizontalPadding, rvVerticalPad)
        }
        val emptyStateView = TextView(activity).apply {
            text = activity.getString(R.string.tv_switch_empty)
            setTextColor(0xB8FFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            isFocusable = false
            setShadowLayer(4 * density, 0f, 1 * density, 0x99000000.toInt())
            visibility = View.GONE
        }
        val loadingSpinner = ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(TvAccent.onDark)
            isFocusable = false
            visibility = View.GONE
        }
        cardsContainer.addView(
            recyclerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        cardsContainer.addView(
            emptyStateView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        val spinnerSize = (30 * density).roundToInt()
        cardsContainer.addView(
            loadingSpinner,
            FrameLayout.LayoutParams(spinnerSize, spinnerSize, Gravity.CENTER),
        )

        var lastFocusedCardPos = RecyclerView.NO_POSITION
        var lastFocusedCardTab = initialTab
        var pendingTarget = RecyclerView.NO_POSITION

        val scrollTabIntoCenter = { tabView: View ->
            tabsScrollView?.let { scroll ->
                val railLeft = railContainer?.left ?: 0
                val centerInScroll = railLeft + (tabView.left + tabView.right) / 2
                val targetX = (centerInScroll - scroll.width / 2).coerceAtLeast(0)
                scroll.smoothScrollTo(targetX, 0)
            }
        }

        recyclerView.layoutManager = object : LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false) {
            override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                if (direction == View.FOCUS_UP) {
                    findContainingItemView(focused)?.let {
                        lastFocusedCardPos = getPosition(it)
                        lastFocusedCardTab = currentTab
                    }
                    // Multi-Stream priority: once at least 1 extra streamer is selected (total >= minChecked),
                    // pressing UP goes directly to the Validate button!
                    if (selection != null && validate != null && validate.isShown && (checked?.size ?: 0) >= minChecked) {
                        tabsScrollView?.smoothScrollTo(0, 0)
                        return validate
                    }
                    val activeTabView = tabViews.getOrNull(currentTab.coerceIn(0, (tabViews.size - 1).coerceAtLeast(0)))
                    if (activeTabView != null && activeTabView.isShown) {
                        scrollTabIntoCenter(activeTabView)
                        return activeTabView
                    }
                    if (validate != null && validate.isShown) {
                        tabsScrollView?.smoothScrollTo(0, 0)
                        return validate
                    }
                    return null
                }
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
                if (view != null && (idle || (view.left >= 0 && view.right <= recyclerView.width))) {
                    pendingTarget = RecyclerView.NO_POSITION
                    view.requestFocus()
                } else if (idle) {
                    pendingTarget = RecyclerView.NO_POSITION
                }
            }
        })

        val preview = if (activity.prefs().getBoolean(C.UI_TV_PREVIEW, true)) {
            TvStreamPreview(recyclerView, inPlayer = true) { playing ->
                if (TvPreviewSource.hasSound(activity)) {
                    (activity.playerFragment as? PlayerFragment)?.setDucked(playing)
                }
            }
        } else null

        lateinit var adapter: Adapter
        val updateValidate = {
            validate?.text = activity.getString(R.string.tv_multistream_validate, checked?.size ?: 0)
            validate?.alpha = if ((checked?.size ?: 0) < minChecked) 0.45f else 1f
        }
        updateValidate()
        if (validate != null && validate.parent == null) {
            header.addView(validate)
        }
        validate?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                tabsScrollView?.smoothScrollTo(0, 0)
            }
        }
        validate?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
                val remembered = if (lastFocusedCardTab == currentTab && lastFocusedCardPos != RecyclerView.NO_POSITION) {
                    recyclerView.layoutManager?.findViewByPosition(lastFocusedCardPos)?.takeIf { it.isShown }
                } else null
                (remembered
                    ?: recyclerView.layoutManager?.findViewByPosition((recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
                    ?: recyclerView.getChildAt(0))?.requestFocus()
                true
            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && event.action == android.view.KeyEvent.ACTION_DOWN) {
                val firstTab = tabViews.firstOrNull()?.takeIf { it.isShown }
                if (firstTab != null) {
                    scrollTabIntoCenter(firstTab)
                    firstTab.requestFocus()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        adapter = Adapter(initialStreams, cardWidthPx, preview, checked) { stream ->
            if (selection != null && checked != null) {
                val login = stream.channelLogin ?: return@Adapter
                when {
                    login in checked -> checked.remove(login)
                    selection.max == 1 -> {
                        dialog.dismiss()
                        selection.onValidate(listOf(stream))
                        return@Adapter
                    }
                    checked.size >= selection.max -> {
                        TvToast.makeText(activity, activity.getString(R.string.tv_multistream_max, selection.max), android.widget.Toast.LENGTH_SHORT).show()
                        return@Adapter
                    }
                    else -> checked.add(login)
                }
                if (adapter.itemCount > 0) {
                    adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SELECTION)
                }
                updateValidate()
            } else {
                if (!stream.channelLogin.isNullOrBlank() && stream.channelLogin.equals(currentChannelLogin, ignoreCase = true)) {
                    dialog.dismiss()
                    return@Adapter
                }
                dialog.dismiss()
                PlayerFragment.peekHudOnStart = true
                val row = rowOf(currentTab)
                if (row != null && row.kind != com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.KIND_COSTREAM) {
                    activity.startStream(stream, adapter.streams, row.source)
                } else {
                    activity.startStream(stream)
                }
            }
        }
        if (selection == null && onPip != null) {
            adapter.onLongClick = { stream ->
                if (stream.channelLogin != null &&
                    (stream.channelLogin.equals(currentChannelLogin, ignoreCase = true) || activity.isStreamInTvPip(stream.channelLogin))
                ) {
                    TvToast.makeText(activity, R.string.tv_pip_already_shown, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    onPip(stream)
                }
            }
        }
        recyclerView.adapter = adapter

        val pages = mutableMapOf<Int, com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Pages>()
        if (source != null && originTab >= 0) {
            pages[originTab] = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Pages(source)
        }
        val inFlightTabLoads = mutableMapOf<Int, Deferred<List<Stream>?>>()
        var preloadJob: Job? = null
        var moreJob: Job? = null

        val fetchOrAwaitTab: suspend (Int) -> List<Stream>? = fetch@{ tabIdx ->
            loadedTabs[tabIdx]?.let { return@fetch it }
            val existing = inFlightTabLoads[tabIdx]
            if (existing != null && existing.isActive) {
                return@fetch existing.await()
            }
            val row = rowOf(tabIdx) ?: return@fetch emptyList()
            val deferred = activity.lifecycleScope.async {
                val rowPages = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Pages(row.source)
                pages[tabIdx] = rowPages
                val fetched = runCatching {
                    rowPages.next()?.distinctBy { it.channelLogin }
                }.getOrNull()
                fetched?.filter { !it.channelLogin.isNullOrBlank() && it.channelLogin !in exclude }
                    ?.also { loadedTabs[tabIdx] = it }
            }
            inFlightTabLoads[tabIdx] = deferred
            try {
                deferred.await()
            } finally {
                if (inFlightTabLoads[tabIdx] === deferred) {
                    inFlightTabLoads.remove(tabIdx)
                }
            }
        }

        adapter.onFocused = { position ->
            val tab = currentTab
            val tabPages = pages[tab]
            if (tabPages != null && tabPages.hasMore && moreJob?.isActive != true && position >= adapter.streams.size - 6) {
                moreJob = activity.lifecycleScope.launch {
                    val known = adapter.streams.mapNotNull { it.channelLogin }.toMutableSet()
                    val added = mutableListOf<Stream>()
                    var tries = 0
                    while (added.isEmpty() && tabPages.hasMore && tries++ < 8) {
                        val page = tabPages.next() ?: break
                        page.forEach { stream ->
                            val login = stream.channelLogin
                            if (login != null && login !in known && login !in exclude) {
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
                TvToast.makeText(activity, activity.getString(R.string.tv_multistream_min, minChecked), android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            val all = (adapter.streams + streams + seen.values).distinctBy { it.channelLogin }
            selection?.onValidate(all.filter { it.channelLogin in logins }.sortedBy { if (it.channelLogin == currentChannelLogin) 0 else 1 })
        }

        // Fixed constant height prevents any vertical jump/slide when switching categories or hitting an empty tab
        root.addView(cardsContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fixedCardsHeightPx))

        var tabJob: Job? = null
        var focusCardsWhenLoaded = false
        var searchQuery = ""

        val restoreTabLabels = {
            tabViews.forEachIndexed { i, tab ->
                val wanted = if (i == searchTabIndex && searchQuery.isNotEmpty()) {
                    "${activity.getString(R.string.search)}: $searchQuery"
                } else {
                    tabTitles[i]
                }
                if (tab.text?.toString() != wanted) {
                    tab.text = wanted
                }
            }
            refreshTabStyles()
        }

        val focusCardFromTab = {
            val remembered = if (lastFocusedCardTab == currentTab && lastFocusedCardPos != RecyclerView.NO_POSITION) {
                recyclerView.layoutManager?.findViewByPosition(lastFocusedCardPos)?.takeIf { it.isShown }
            } else null
            val firstVisible = (recyclerView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
                ?.takeIf { it != RecyclerView.NO_POSITION }
                ?.let { recyclerView.layoutManager?.findViewByPosition(it) }
            (remembered ?: firstVisible ?: recyclerView.getChildAt(0))?.requestFocus()
        }

        val setCardsLoadingState = { loading: Boolean ->
            loadingSpinner.visibility = if (loading) View.VISIBLE else View.GONE
            recyclerView.alpha = if (loading) 0.35f else 1f
            if (loading) {
                emptyStateView.visibility = View.GONE
            }
        }

        val showTab = { index: Int, list: List<Stream> ->
            if (lastFocusedCardTab != index) {
                lastFocusedCardPos = RecyclerView.NO_POSITION
            }
            currentTab = index
            pendingTarget = RecyclerView.NO_POSITION
            moreJob?.cancel()
            preview?.stop()
            setCardsLoadingState(false)
            list.forEach { stream -> stream.channelLogin?.let { seen.putIfAbsent(it, stream) } }
            adapter.submitList(list)
            emptyStateView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            if (list.isNotEmpty()) {
                recyclerView.scrollToPosition(0)
            }
            refreshTabStyles()
        }

        val runSearch: (String, TextView) -> Unit = { query, _ ->
            val trimmed = query.trim()
            if (trimmed.isNotEmpty()) {
                searchQuery = trimmed
                tabJob?.cancel()
                loadingTabIndex = -1
                focusCardsWhenLoaded = false
                restoreTabLabels()
                tabJob = activity.lifecycleScope.launch {
                    loadingTabIndex = searchTabIndex
                    currentTab = searchTabIndex
                    refreshTabStyles()
                    setCardsLoadingState(true)
                    val searchPages = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.Pages {
                        com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.search(activity, trimmed)
                    }
                    pages[searchTabIndex] = searchPages
                    val list = runCatching {
                        searchPages.next()?.distinctBy { it.channelLogin }
                    }.getOrNull()
                        ?.filter { !it.channelLogin.isNullOrBlank() && it.channelLogin !in exclude }
                        ?.also { loadedTabs[searchTabIndex] = it }
                    loadingTabIndex = -1
                    setCardsLoadingState(false)
                    restoreTabLabels()
                    if (!dialog.isShowing) {
                        return@launch
                    }
                    if (list == null) {
                        showTab(searchTabIndex, emptyList())
                        TvToast.makeText(activity, R.string.tv_switch_error, android.widget.Toast.LENGTH_SHORT).show()
                    } else if (list.isEmpty()) {
                        showTab(searchTabIndex, emptyList())
                        TvToast.makeText(activity, R.string.tv_switch_empty, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        showTab(searchTabIndex, list)
                        recyclerView.post { recyclerView.layoutManager?.findViewByPosition(0)?.requestFocus() }
                    }
                }
            }
        }

        val selectTab: (Int, TextView, Long, Boolean) -> Unit = selectTab@{ index, _, debounceMs, focusCardsAfter ->
            if (index == searchTabIndex) {
                val cachedSearch = loadedTabs[searchTabIndex]
                if (!cachedSearch.isNullOrEmpty()) {
                    tabJob?.cancel()
                    loadingTabIndex = -1
                    setCardsLoadingState(false)
                    restoreTabLabels()
                    focusCardsWhenLoaded = focusCardsAfter
                    tabJob = activity.lifecycleScope.launch {
                        if (debounceMs > 0L) delay(debounceMs)
                        if (!dialog.isShowing) return@launch
                        showTab(searchTabIndex, cachedSearch)
                        if (focusCardsWhenLoaded) {
                            focusCardsWhenLoaded = false
                            recyclerView.post { focusCardFromTab() }
                        }
                    }
                } else {
                    tabJob?.cancel()
                    loadingTabIndex = -1
                    focusCardsWhenLoaded = false
                    setCardsLoadingState(false)
                    restoreTabLabels()
                }
                return@selectTab
            }
            if (index == currentTab && loadingTabIndex == -1 && loadedTabs[index] != null) {
                tabJob?.cancel()
                setCardsLoadingState(false)
                restoreTabLabels()
                if (focusCardsAfter && adapter.itemCount > 0) {
                    focusCardFromTab()
                }
                return@selectTab
            }
            if (loadingTabIndex == index && debounceMs == 0L) {
                if (focusCardsAfter) focusCardsWhenLoaded = true
                return@selectTab
            }
            tabJob?.cancel()
            loadingTabIndex = -1
            focusCardsWhenLoaded = focusCardsAfter
            restoreTabLabels()
            tabJob = activity.lifecycleScope.launch {
                val alreadyCached = loadedTabs[index]
                if (alreadyCached != null) {
                    // Cached tab: tiny 60ms settle while gliding across tabs, instant on click
                    if (debounceMs > 0L) delay(60L)
                    if (!dialog.isShowing) return@launch
                    showTab(index, alreadyCached)
                    if (focusCardsWhenLoaded && alreadyCached.isNotEmpty()) {
                        focusCardsWhenLoaded = false
                        recyclerView.post { focusCardFromTab() }
                    } else {
                        focusCardsWhenLoaded = false
                    }
                    return@launch
                }

                if (debounceMs > 0L) {
                    delay(debounceMs)
                }
                if (!dialog.isShowing) return@launch
                currentTab = index
                val nowCached = loadedTabs[index]
                val list = if (nowCached != null) {
                    nowCached
                } else {
                    loadingTabIndex = index
                    refreshTabStyles()
                    setCardsLoadingState(true)
                    val fetched = fetchOrAwaitTab(index)
                    loadingTabIndex = -1
                    setCardsLoadingState(false)
                    restoreTabLabels()
                    fetched
                }
                if (!dialog.isShowing) return@launch
                if (list == null) {
                    showTab(index, emptyList())
                    focusCardsWhenLoaded = false
                    TvToast.makeText(activity, R.string.tv_switch_error, android.widget.Toast.LENGTH_SHORT).show()
                } else if (list.isEmpty()) {
                    showTab(index, emptyList())
                    focusCardsWhenLoaded = false
                } else {
                    showTab(index, list)
                    if (focusCardsWhenLoaded) {
                        focusCardsWhenLoaded = false
                        recyclerView.post { focusCardFromTab() }
                    }
                }
            }
        }

        tabViews.forEachIndexed { index, view ->
            view.setOnFocusChangeListener { _, hasFocus ->
                refreshTabStyles()
                if (hasFocus) {
                    scrollTabIntoCenter(view)
                    selectTab(index, view, 120L, false)
                }
            }
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
                selectTab(index, view, 0L, true)
            }
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (index == searchTabIndex && loadedTabs[searchTabIndex].isNullOrEmpty()) {
                            if (adapter.itemCount > 0) focusCardFromTab()
                        } else if (currentTab != index || loadingTabIndex == index || loadedTabs[index] == null) {
                            selectTab(index, view, 0L, true)
                        } else if (adapter.itemCount > 0) {
                            focusCardFromTab()
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (index == 0) {
                            if (validate != null && validate.isShown) {
                                tabsScrollView?.smoothScrollTo(0, 0)
                                validate.requestFocus()
                            }
                            true
                        } else {
                            val prev = tabViews.getOrNull(index - 1)
                            if (prev != null) {
                                scrollTabIntoCenter(prev)
                                prev.requestFocus()
                            }
                            true
                        }
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val next = tabViews.getOrNull(index + 1)
                        if (next != null) {
                            scrollTabIntoCenter(next)
                            next.requestFocus()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        initialStreams.forEach { stream -> stream.channelLogin?.let { seen.putIfAbsent(it, stream) } }
        streams.forEach { stream -> stream.channelLogin?.let { seen.putIfAbsent(it, stream) } }
        emptyStateView.visibility = if (initialStreams.isEmpty() && !omitEmptyCurrentTab) View.VISIBLE else View.GONE

        var lastMove = 0L
        dialog.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) &&
                event.action == android.view.KeyEvent.ACTION_DOWN && recyclerView.hasFocus()
            ) {
                val now = android.os.SystemClock.uptimeMillis()
                if (event.repeatCount > 0 && now - lastMove < 120L) {
                    return@setOnKeyListener true
                }
                lastMove = now
            }
            false
        }

        dialog.setContentView(
            outer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            WindowInsetsControllerCompat(this, decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        val currentIndex = if (initialTab == originTab) {
            initialStreams.indexOfFirst { it.channelLogin != null && it.channelLogin == currentChannelLogin }.coerceAtLeast(0)
        } else {
            0
        }
        if (initialStreams.isNotEmpty()) {
            recyclerView.scrollToPosition(currentIndex)
        }

        dialog.setOnShowListener {
            // Silently preload uncached category tabs in the background so tab switches are immediate and jump-free
            preloadJob = activity.lifecycleScope.launch {
                for (candidate in tabEntries.indices) {
                    if (!dialog.isShowing) break
                    if (candidate == searchTabIndex || tabEntries[candidate].row == null) continue
                    if (loadedTabs[candidate] != null) continue
                    fetchOrAwaitTab(candidate)
                }
            }

            if (initialStreams.isEmpty() && omitEmptyCurrentTab) {
                tabViews.firstOrNull()?.requestFocus()
                tabJob?.cancel()
                tabJob = activity.lifecycleScope.launch {
                    setCardsLoadingState(true)
                    var chosenTab = 0
                    var list: List<Stream>? = null
                    for (candidate in tabEntries.indices) {
                        if (tabEntries[candidate].row == null) continue
                        val fetched = fetchOrAwaitTab(candidate)
                        if (!dialog.isShowing) return@launch
                        if (!fetched.isNullOrEmpty()) {
                            chosenTab = candidate
                            list = fetched
                            break
                        }
                    }
                    setCardsLoadingState(false)
                    if (!list.isNullOrEmpty()) {
                        showTab(chosenTab, list)
                        recyclerView.post { recyclerView.layoutManager?.findViewByPosition(0)?.requestFocus() }
                    } else {
                        showTab(0, emptyList())
                    }
                }
            } else {
                recyclerView.post {
                    val view = recyclerView.layoutManager?.findViewByPosition(currentIndex)
                    if (view == null || !view.requestFocus()) {
                        recyclerView.getChildAt(0)?.requestFocus()
                    }
                }
            }
        }

        val update: (List<Stream>) -> Unit = update@{ list ->
            if (!dialog.isShowing || list.isEmpty() || originTab < 0 || list.map { it.channelLogin } == loadedTabs[originTab]?.map { it.channelLogin }) {
                return@update
            }
            loadedTabs[originTab] = list
            if (currentTab != originTab) {
                return@update
            }
            val old = adapter.streams
            if (list.size > old.size && list.subList(0, old.size).map { it.channelLogin } == old.map { it.channelLogin }) {
                adapter.streams = list
                adapter.notifyItemRangeInserted(old.size, list.size - old.size)
                emptyStateView.visibility = View.GONE
                return@update
            }
            val focusedLogin = recyclerView.focusedChild?.let { recyclerView.getChildAdapterPosition(it) }
                ?.let { adapter.streams.getOrNull(it)?.channelLogin }
            adapter.submitList(list)
            emptyStateView.visibility = View.GONE
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
            preloadJob?.cancel()
            inFlightTabLoads.values.forEach { it.cancel() }
            inFlightTabLoads.clear()
            tabJob?.cancel()
            moreJob?.cancel()
        }
        dialog.show()
        if (initialStreams.isEmpty() && !omitEmptyCurrentTab) {
            val fallbackTab = (initialTab + 1).takeIf { it in 0 until searchTabIndex }
            if (fallbackTab != null) {
                tabViews.getOrNull(fallbackTab)?.post {
                    tabViews[fallbackTab].requestFocus()
                    tabViews[fallbackTab].performClick()
                }
            }
        }
    }

    private const val PAYLOAD_SELECTION = "selection_badge"

    private class Adapter(
        var streams: List<Stream>,
        private val itemWidth: Int,
        private val preview: TvStreamPreview?,
        /** Selection mode: the logins checked, they get an accent badge. */
        private val checked: Set<String>?,
        private val onClick: (Stream) -> Unit,
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {

        /** OK held on a card (the small window of the player), none in selection mode. */
        var onLongClick: ((Stream) -> Unit)? = null

        /** A card got the focus (its place in the list): more of the list is loaded near its end. */
        var onFocused: ((Int) -> Unit)? = null

        class ViewHolder(val binding: FragmentStreamsListItemBinding, val badge: TextView) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = streams.size

        fun submitList(newList: List<Stream>) {
            val oldList = streams
            if (oldList === newList) return
            if (oldList.isEmpty() && newList.isEmpty()) return
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldList.size
                override fun getNewListSize(): Int = newList.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val a = oldList[oldItemPosition]
                    val b = newList[newItemPosition]
                    return if (!a.channelLogin.isNullOrBlank() && !b.channelLogin.isNullOrBlank()) {
                        a.channelLogin.equals(b.channelLogin, ignoreCase = true)
                    } else {
                        a.id == b.id && a.channelId == b.channelId
                    }
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val a = oldList[oldItemPosition]
                    val b = newList[newItemPosition]
                    return a.title == b.title &&
                        a.gameName == b.gameName &&
                        a.viewerCount == b.viewerCount &&
                        a.thumbnailURL == b.thumbnailURL &&
                        a.channelName == b.channelName &&
                        a.channelImageURL == b.channelImageURL
                }
            })
            streams = newList
            diff.dispatchUpdatesTo(this)
        }

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.attachToRecyclerView(recyclerView) {
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.PAYLOAD_COSTREAM)
                }
            }
            com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.attachToRecyclerView(recyclerView) {
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.PAYLOAD_PLAYBACK)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = FragmentStreamsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            binding.root.layoutParams = binding.root.layoutParams.apply { width = itemWidth }
            TvCards.prepare(binding.root)
            val density = parent.resources.displayMetrics.density
            val badge = TextView(parent.context).apply {
                gravity = Gravity.CENTER
                setTextColor(TvAccent.onAccent)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(TvAccent.color)
                    setStroke((2 * density).toInt(), Color.WHITE)
                }
                visibility = View.GONE
                elevation = 8 * density
            }
            (binding.root as? FrameLayout)?.addView(
                badge,
                FrameLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt(), Gravity.TOP or Gravity.END).apply {
                    val margin = (8 * density).toInt()
                    setMargins(margin, margin, margin, margin)
                },
            )
            return ViewHolder(binding, badge)
        }

        private fun bindSelectionBadge(holder: ViewHolder, stream: Stream) {
            val number = checked?.indexOf(stream.channelLogin)?.takeIf { it >= 0 }?.plus(1)
            holder.badge.visibility = if (number != null) View.VISIBLE else View.GONE
            holder.badge.text = number?.toString()
        }

        private fun bindUsername(holder: ViewHolder, stream: Stream) {
            val context = holder.itemView.context
            val rawName: CharSequence = stream.channelName ?: stream.channelLogin.orEmpty()
            com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.enqueue(context, stream)
            val coCount = com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.getCount(stream.channelId, stream.channelLogin)
            holder.binding.username.visibility = View.VISIBLE
            holder.binding.username.text = com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.formatUsernameWithBadge(context, rawName, coCount)
        }

        private fun bindPlaybackBadge(holder: ViewHolder, stream: Stream) {
            com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.bindView(holder.binding.tvPlayingBadge, stream.channelLogin)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            val validPayloads = setOf(
                com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.PAYLOAD_COSTREAM,
                com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.PAYLOAD_PLAYBACK,
                PAYLOAD_SELECTION,
            )
            if (payloads.isNotEmpty() && payloads.all { it in validPayloads }) {
                streams.getOrNull(position)?.let { stream ->
                    if (PAYLOAD_SELECTION in payloads) {
                        bindSelectionBadge(holder, stream)
                    }
                    if (com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.PAYLOAD_COSTREAM in payloads) {
                        bindUsername(holder, stream)
                    }
                    if (com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.PAYLOAD_PLAYBACK in payloads) {
                        bindPlaybackBadge(holder, stream)
                    }
                }
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val stream = streams[position]
            val context = holder.itemView.context
            bindSelectionBadge(holder, stream)
            bindPlaybackBadge(holder, stream)
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
                bindUsername(holder, stream)
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
