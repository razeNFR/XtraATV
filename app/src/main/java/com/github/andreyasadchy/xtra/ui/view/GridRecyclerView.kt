package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.common.GamesAdapter
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

    init {
        val columns = getColumnsForConfiguration(resources.configuration)
        gridLayoutManager = GridLayoutManager(context, columns)
        layoutManager = gridLayoutManager
        addItemDecoration(columns)
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        super.setAdapter(adapter)
        if (isTv) {
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
        private const val TV_COLUMNS_GAMES = 5
        private const val TV_COLUMNS_CHANNELS = 3
        private const val TV_COLUMNS_TEXT = 2
    }
}
