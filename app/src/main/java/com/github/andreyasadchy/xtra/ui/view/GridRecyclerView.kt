package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.common.GamesAdapter
import com.github.andreyasadchy.xtra.ui.common.StreamsAdapter
import com.github.andreyasadchy.xtra.ui.common.StreamsCompactAdapter
import com.github.andreyasadchy.xtra.ui.following.channels.FollowedChannelsAdapter
import com.github.andreyasadchy.xtra.ui.following.games.FollowedGamesAdapter
import com.github.andreyasadchy.xtra.ui.saved.filters.FiltersAdapter
import com.github.andreyasadchy.xtra.ui.search.RecentSearchAdapter
import com.github.andreyasadchy.xtra.ui.search.channels.ChannelSearchAdapter
import com.github.andreyasadchy.xtra.ui.team.TeamMembersAdapter
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

class GridRecyclerView : RecyclerView {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private val prefs = context.prefs()
    private val material3 = prefs.getBoolean(C.UI_THEME_MATERIAL3, true)
    private val portraitColumns = prefs.getString(C.PORTRAIT_COLUMN_COUNT, "1")!!.toInt()
    private val landscapeColumns = prefs.getString(C.LANDSCAPE_COLUMN_COUNT, "2")!!.toInt()
    private val isTv = resources.getBoolean(R.bool.isTv)
    private var tvColumns = TV_COLUMNS_MEDIA

    private val gridLayoutManager: GridLayoutManager

    private var pressedCard: View? = null
    private var longPressed = false
    private val longPressAction = Runnable {
        pressedCard?.let { card ->
            longPressed = true
            card.isPressed = false
            // a stream card: the same actions as on the home (channel, videos, category, follow), and the options of the list
            val stream = (adapter as? StreamsAdapter)?.let { streams ->
                getChildAdapterPosition(card).takeIf { it != NO_POSITION && it < streams.itemCount }?.let { streams.peek(it) }
            } ?: (adapter as? StreamsCompactAdapter)?.let { streams ->
                getChildAdapterPosition(card).takeIf { it != NO_POSITION && it < streams.itemCount }?.let { streams.peek(it) }
            }
            val activity = context as? com.github.andreyasadchy.xtra.ui.main.MainActivity
            if (stream != null && activity != null) {
                val options = card.findViewById<View>(R.id.options)?.takeIf { it.visibility == View.VISIBLE && it.hasOnClickListeners() }
                activity.showTvStreamActions(
                    stream,
                    listOfNotNull(options?.let { com.github.andreyasadchy.xtra.ui.tv.TvStreamActions.Extra(context.getString(R.string.tv_more_options)) { it.performClick() } })
                )
            } else {
                TvCards.showMenu(card)
            }
        }
    }
    private val focusFirstItemAction = Runnable { focusFirstItemIfFocusLost() }
    private var preview: TvStreamPreview? = null
    private val dataObserver = object : AdapterDataObserver() {
        override fun onChanged() = scheduleFocusCheck()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scheduleFocusCheck()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scheduleFocusCheck()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = scheduleFocusCheck()
    }

    init {
        val columns = getColumnsForConfiguration(resources.configuration)
        gridLayoutManager = object : GridLayoutManager(context, columns) {
            /**
             * Google TV alignment: the focused row stays a quarter down the list, so a part of the rows before
             * and after it is always on screen and the focus never has to jump to a row it cannot see.
             */
            override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                if (!isTv) {
                    return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
                }
                val pivot = paddingTop + ((parent.height - paddingTop - paddingBottom) * 0.22f).toInt()
                val dy = getDecoratedTop(child) - pivot
                if (dy == 0) {
                    return false
                }
                if (immediate) {
                    parent.scrollBy(0, dy)
                } else {
                    // smooth glide like Google TV, quicker while the key is held
                    parent.smoothScrollBy(0, dy, scrollInterpolator, if (keyRepeating) 140 else 260)
                }
                return true
            }

            // the rows above and below the screen are laid out ahead, so moving to them never waits for a layout
            override fun calculateExtraLayoutSpace(state: State, extraLayoutSpace: IntArray) {
                if (isTv) {
                    extraLayoutSpace[0] = height
                    extraLayoutSpace[1] = height
                } else {
                    super.calculateExtraLayoutSpace(state, extraLayoutSpace)
                }
            }
        }
        layoutManager = gridLayoutManager
        addItemDecoration(columns)
        if (isTv) {
            // room for the zoomed focused card at the edges
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(paddingLeft, padding, paddingRight, padding * 2)
            clipToPadding = false
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
            addOnChildAttachStateChangeListener(object : OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    TvCards.prepare(view)
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    if (view == pressedCard) {
                        cancelPress()
                    }
                    if (preview?.isPreviewing(view) == true) {
                        preview?.stop()
                    }
                }
            })
        }
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        if (isTv) {
            this.adapter?.unregisterAdapterDataObserver(dataObserver)
            adapter?.registerAdapterDataObserver(dataObserver)
        }
        super.setAdapter(adapter)
        dataLoadedAt = System.currentTimeMillis()
        if (isTv) {
            scheduleFocusCheck()
            val columns = when (adapter) {
                is GamesAdapter, is FollowedGamesAdapter -> TV_COLUMNS_GAMES
                is FollowedChannelsAdapter, is ChannelSearchAdapter, is StreamsCompactAdapter, is TeamMembersAdapter -> TV_COLUMNS_CHANNELS
                is RecentSearchAdapter, is FiltersAdapter -> TV_COLUMNS_TEXT
                // streams, videos and clips: from 2 big cards to 6 small ones a row, from the settings
                else -> prefs.getString(C.TV_STREAM_COLUMNS, TV_COLUMNS_MEDIA.toString())?.toIntOrNull()?.coerceIn(2, 6) ?: TV_COLUMNS_MEDIA
            }
            if (columns != tvColumns) {
                tvColumns = columns
                if (!material3) {
                    removeItemDecorationAt(0)
                }
                gridLayoutManager.spanCount = columns
                addItemDecoration(columns)
            }
        }
    }

    private var dataLoadedAt = 0L

    /**
     * TV: lists of live streams load again when the screen comes back after a while, a streamer that stopped
     * does not stay in the list.
     */
    fun refreshIfStale(maxAge: Long = 30_000L) {
        val adapter = adapter
        if (adapter !is StreamsAdapter && adapter !is StreamsCompactAdapter) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - dataLoadedAt > maxAge) {
            dataLoadedAt = now
            (adapter as? androidx.paging.PagingDataAdapter<*, *>)?.refresh()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && isTv && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            val card = focusedChild
            if (card != null && card.hasFocus()) {
                // click on release, actions menu on long press
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            cancelPress()
                            pressedCard = card
                            longPressed = false
                            card.isPressed = true
                            postDelayed(longPressAction, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        val wasLongPressed = longPressed
                        val pressed = pressedCard
                        cancelPress()
                        if (!wasLongPressed && pressed == card) {
                            card.performClick()
                        }
                    }
                }
                return true
            }
        }
        if (event != null && isTv && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val card = focusedChild
            val position = card?.let { getChildAdapterPosition(it) } ?: NO_POSITION
            if (card != null && card.hasFocus() && position != NO_POSITION) {
                val span = gridLayoutManager.spanCount
                val column = position % span
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && column == 0) {
                    // first column: left opens the side menu (the header is only reached with up)
                    TvHeader.findActivity(this)?.openTvMenu()
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && (column == span - 1 || position == (adapter?.itemCount ?: 0) - 1)) {
                    return true
                }
            }
        }
        if (event != null && isTv && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
        ) {
            keyRepeating = event.repeatCount > 0
            if (moveFocusVertically(event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var keyRepeating = false
    private val scrollInterpolator = android.view.animation.DecelerateInterpolator(1.6f)

    /**
     * Up / down go to the card of the same column in the row above / below, even when that row is not on screen yet
     * (holding the key or pressing it fast): the list scrolls to it instead of the focus jumping somewhere else.
     * Returns false when the focus has to leave the list (up from the first row).
     */
    private fun moveFocusVertically(down: Boolean): Boolean {
        val card = focusedChild ?: return false
        val position = getChildAdapterPosition(card)
        val count = adapter?.itemCount ?: 0
        if (position == NO_POSITION || count == 0) {
            return false
        }
        val span = gridLayoutManager.spanCount
        var target = if (down) position + span else position - span
        if (target < 0) {
            // up from the first row: the header of the screen, on its first action (refresh)
            return TvHeader.focusHeaderOf(this)
        }
        if (target >= count) {
            val lastRowStart = (count - 1) / span * span
            if (down && position < lastRowStart) {
                target = count - 1
            } else {
                // end of the list: stay on the card
                return true
            }
        }
        val holder = findViewHolderForAdapterPosition(target)
        if (holder != null && holder.itemView.isAttachedToWindow) {
            holder.itemView.requestFocus()
        } else {
            gridLayoutManager.scrollToPositionWithOffset(target, paddingTop + ((height - paddingTop - paddingBottom) * 0.22f).toInt())
            post {
                findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            }
        }
        return true
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (isTv && child != null) {
            val streamsAdapter = adapter as? StreamsAdapter
            if (streamsAdapter != null) {
                val position = getChildAdapterPosition(child)
                val channelLogin = if (position != NO_POSITION && position < streamsAdapter.itemCount) {
                    streamsAdapter.peek(position)?.channelLogin
                } else null
                (preview ?: TvStreamPreview(this).also { preview = it }).schedule(child, channelLogin)
            }
        }
    }

    override fun clearChildFocus(child: View?) {
        super.clearChildFocus(child)
        preview?.stop()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            preview?.stop()
        }
    }

    private fun cancelPress() {
        removeCallbacks(longPressAction)
        pressedCard?.isPressed = false
        pressedCard = null
        longPressed = false
    }

    private fun scheduleFocusCheck() {
        removeCallbacks(focusFirstItemAction)
        postDelayed(focusFirstItemAction, 100)
    }

    /** Lists load asynchronously, give the focus to the first item when nothing has it so the D-pad never gets lost. */
    private fun focusFirstItemIfFocusLost() {
        if (!isAttachedToWindow || !isShown) {
            return
        }
        val currentFocus = rootView.findFocus()
        if (currentFocus == null || !currentFocus.isShown) {
            val first = layoutManager?.findViewByPosition(gridLayoutManager.findFirstVisibleItemPosition())
            if (first == null || !first.requestFocus()) {
                requestFocus()
            }
        }
    }

    override fun onDetachedFromWindow() {
        cancelPress()
        preview?.release()
        preview = null
        removeCallbacks(focusFirstItemAction)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!material3) {
            removeItemDecorationAt(0)
        }
        val columns = getColumnsForConfiguration(newConfig)
        gridLayoutManager.spanCount = columns
        addItemDecoration(columns)
    }

    private fun getColumnsForConfiguration(configuration: Configuration): Int {
        return if (isTv) {
            tvColumns
        } else if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            portraitColumns
        } else {
            landscapeColumns
        }
    }

    private fun addItemDecoration(columns: Int) {
        if (!material3) {
            addItemDecoration(
                if (columns <= 1) {
                    DividerItemDecoration(context, GridLayoutManager.VERTICAL)
                } else {
                    MarginItemDecoration(context.resources.getDimension(R.dimen.divider_margin).toInt(), columns)
                }
            )
        }
    }

    companion object {
        private const val TV_COLUMNS_MEDIA = 4
        private const val TV_COLUMNS_GAMES = 6
        private const val TV_COLUMNS_CHANNELS = 3
        private const val TV_COLUMNS_TEXT = 2
    }
}
