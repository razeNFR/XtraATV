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
        pressedCard?.let {
            longPressed = true
            it.isPressed = false
            TvCards.showMenu(it)
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
        gridLayoutManager = GridLayoutManager(context, columns)
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
        if (isTv) {
            scheduleFocusCheck()
            val columns = when (adapter) {
                is GamesAdapter, is FollowedGamesAdapter -> TV_COLUMNS_GAMES
                is FollowedChannelsAdapter, is ChannelSearchAdapter, is StreamsCompactAdapter, is TeamMembersAdapter -> TV_COLUMNS_CHANNELS
                is RecentSearchAdapter, is FiltersAdapter -> TV_COLUMNS_TEXT
                else -> TV_COLUMNS_MEDIA
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
        return super.dispatchKeyEvent(event)
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
