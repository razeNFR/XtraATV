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

    /**
     * TV lists of streams: the card whose preview plays grows and the others move to make room
     * (turned off with the growth of the cards in the Xtra TV settings).
     */
    private val featureLayoutManager: TvFeatureGridLayoutManager? = if (isTv && prefs.getBoolean(C.TV_CARD_ZOOM, false)) {
        TvFeatureGridLayoutManager { parent, child, immediate -> scrollToPivot(parent, child, immediate) }.apply {
            // the grown card went back to its size: the focused card moved with the others, it is brought to its place again
            onCollapsed = {
                focusedChild?.let { scrollToPivot(this@GridRecyclerView, it, false) }
            }
        }
    } else null

    private val usesFeatureLayout get() = featureLayoutManager != null && layoutManager === featureLayoutManager

    /** Cards a row. */
    val columnCount get() = if (usesFeatureLayout) featureLayoutManager!!.spanCount else gridLayoutManager.spanCount

    /**
     * Google TV alignment: the focused row stays a quarter down the list, so a part of the rows before
     * and after it is always on screen and the focus never has to jump to a row it cannot see.
     */
    private fun scrollToPivot(parent: RecyclerView, child: View, immediate: Boolean): Boolean {
        val manager = parent.layoutManager ?: return false
        val pivot = paddingTop + ((parent.height - paddingTop - paddingBottom) * 0.22f).toInt()
        var dy = manager.getDecoratedTop(child) - pivot
        // a grown card is shown whole
        val overflow = manager.getDecoratedBottom(child) - dy - (parent.height - paddingBottom)
        if (overflow > 0) {
            dy += minOf(overflow, manager.getDecoratedTop(child) - dy - paddingTop)
        }
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

    private fun scrollToPositionWithOffset(position: Int, offset: Int) {
        if (usesFeatureLayout) {
            featureLayoutManager!!.scrollToPositionWithOffset(position, offset)
        } else {
            gridLayoutManager.scrollToPositionWithOffset(position, offset)
        }
    }

    private fun findFirstVisibleItemPosition() = if (usesFeatureLayout) featureLayoutManager!!.findFirstVisibleItemPosition() else gridLayoutManager.findFirstVisibleItemPosition()

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
                    listOfNotNull(options?.let { com.github.andreyasadchy.xtra.ui.tv.TvStreamActions.Extra(context.getString(R.string.tv_more_options), R.drawable.baseline_more_vert_black_24) { it.performClick() } })
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
                return scrollToPivot(parent, child, immediate)
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
            cardTextScale = when {
                adapter !is StreamsAdapter -> 1f
                columns >= 6 -> 0.72f
                columns == 5 -> 0.82f
                columns == 2 -> 1.15f
                else -> 1f
            }
            if (columns != tvColumns) {
                tvColumns = columns
                if (!material3) {
                    removeItemDecorationAt(0)
                }
                gridLayoutManager.spanCount = columns
                addItemDecoration(columns)
            }
            // streams: the grid where the card of the preview grows
            val feature = featureLayoutManager
            if (feature != null && adapter is StreamsAdapter) {
                feature.spanCount = columns
                if (layoutManager !== feature) {
                    layoutManager = feature
                }
            } else if (layoutManager !== gridLayoutManager) {
                layoutManager = gridLayoutManager
            }
        }
    }

    private var dataLoadedAt = 0L

    /** TV stream cards: the texts get smaller with more columns, the badges do not cover the thumbnail. */
    private var cardTextScale = 1f

    init {
        addOnChildAttachStateChangeListener(object : OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (isTv) {
                    scaleCardTexts(view)
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    private fun scaleCardTexts(card: View) {
        for (id in intArrayOf(R.id.viewers, R.id.uptime, R.id.title, R.id.username, R.id.gameName)) {
            val text = card.findViewById<android.widget.TextView>(id) ?: continue
            // the size of the layout is kept once, the scale always starts from it
            val base = (text.getTag(R.id.tvBaseTextSize) as? Float) ?: text.textSize.also { text.setTag(R.id.tvBaseTextSize, it) }
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * cardTextScale)
            text.maxLines = 1
        }
        card.findViewById<View>(R.id.userImage)?.let { image ->
            val base = (image.getTag(R.id.tvBaseTextSize) as? Int) ?: image.layoutParams.width.also { image.setTag(R.id.tvBaseTextSize, it) }
            val size = (base * cardTextScale).toInt()
            if (image.layoutParams.width != size) {
                image.layoutParams = image.layoutParams.apply { width = size; height = size }
            }
        }
    }

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
        if (event != null && isTv && event.action == KeyEvent.ACTION_DOWN && moveFromGrownCard(event.keyCode)) {
            return true
        }
        if (event != null && isTv && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val card = focusedChild
            val position = card?.let { getChildAdapterPosition(it) } ?: NO_POSITION
            if (card != null && card.hasFocus() && position != NO_POSITION) {
                val span = gridLayoutManager.spanCount
                val column = position % span
                val feature = featureLayoutManager?.takeIf { usesFeatureLayout }
                val atStart = feature?.isAtStart(position) ?: (column == 0)
                val atEnd = feature?.isAtEnd(position) ?: (column == span - 1)
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && atStart) {
                    // first column: left opens the side menu (the header is only reached with up)
                    TvHeader.findActivity(this)?.openTvMenu()
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && (atEnd || position == (adapter?.itemCount ?: 0) - 1)) {
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
        val span = columnCount
        if (usesFeatureLayout) {
            // cards of different sizes: the card right above / below, by their places on screen
            nearestCardVertically(card, down)?.let {
                it.requestFocus()
                return true
            }
        }
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
            scrollToPositionWithOffset(target, paddingTop + ((height - paddingTop - paddingBottom) * 0.22f).toInt())
            post {
                findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            }
        }
        return true
    }

    /**
     * From the grown card, the D-pad goes to the cards that were around it before it grew (its neighbours in the grid of
     * cards of the same size), not to the cards that moved around it.
     */
    private fun moveFromGrownCard(keyCode: Int): Boolean {
        val feature = featureLayoutManager?.takeIf { usesFeatureLayout } ?: return false
        val card = focusedChild ?: return false
        val position = getChildAdapterPosition(card)
        if (position == NO_POSITION || position != feature.featuredPosition) {
            return false
        }
        val span = feature.spanCount
        val count = adapter?.itemCount ?: 0
        val column = position % span
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (column == 0) {
                    TvHeader.findActivity(this)?.openTvMenu()
                    return true
                }
                position - 1
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (column == span - 1 || position + 1 >= count) return true
                position + 1
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (position < span) return TvHeader.focusHeaderOf(this)
                position - span
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when {
                    position + span < count -> position + span
                    // the last row is shorter: the last card
                    position / span < (count - 1) / span -> count - 1
                    else -> return true
                }
            }
            else -> return false
        }
        val holder = findViewHolderForAdapterPosition(target)
        if (holder != null && holder.itemView.isAttachedToWindow) {
            holder.itemView.requestFocus()
        } else {
            scrollToPositionWithOffset(target, paddingTop + ((height - paddingTop - paddingBottom) * 0.22f).toInt())
            post { findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
        return true
    }

    /** The laid out card above / below [card] whose columns cover its middle, the closest one. */
    private fun nearestCardVertically(card: View, down: Boolean): View? {
        val manager = layoutManager ?: return null
        val centerX = (manager.getDecoratedLeft(card) + manager.getDecoratedRight(card)) / 2
        val top = manager.getDecoratedTop(card)
        val bottom = manager.getDecoratedBottom(card)
        var best: View? = null
        var bestDistance = Int.MAX_VALUE
        var bestDx = Int.MAX_VALUE
        for (index in 0 until childCount) {
            val child = getChildAt(index) ?: continue
            if (child === card) continue
            val childTop = manager.getDecoratedTop(child)
            val childBottom = manager.getDecoratedBottom(child)
            val distance = if (down) childTop - bottom else top - childBottom
            if (distance < -4) continue
            val left = manager.getDecoratedLeft(child)
            val right = manager.getDecoratedRight(child)
            // the card under the middle, else the closest one of that row
            val dx = if (centerX in left..right) 0 else minOf(kotlin.math.abs(left - centerX), kotlin.math.abs(right - centerX))
            if (distance < bestDistance - 4 || (kotlin.math.abs(distance - bestDistance) <= 4 && dx < bestDx)) {
                best = child
                bestDistance = distance
                bestDx = dx
            }
        }
        return best
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
                (preview ?: TvStreamPreview(this, onCard = { card ->
                    // the card of the preview grows, the others move
                    featureLayoutManager?.takeIf { usesFeatureLayout }?.feature(card?.let { getChildAdapterPosition(it) } ?: NO_POSITION)
                }).also { preview = it }).schedule(child, channelLogin)
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
            val first = layoutManager?.findViewByPosition(findFirstVisibleItemPosition())
            if (first == null || !first.requestFocus()) {
                requestFocus()
            }
        }
    }

    /**
     * The focus left the list (header, side menu, player...): the preview stops and its card goes back to its size.
     * Moving the focus elsewhere does not clear the focus of the list, [clearChildFocus] is not called then.
     */
    private val focusWatcher = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        val preview = preview ?: return@OnGlobalFocusChangeListener
        var view: View? = newFocus
        while (view != null && view !== this) {
            view = view.parent as? View
        }
        if (view == null) {
            preview.stop()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isTv) {
            viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
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
