package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout

/** Adjustments applied to every screen on TV. */
object TvUi {

    fun isTv(context: Context) = context.resources.getBoolean(R.bool.isTv)

    /**
     * Headers never scroll away (the D-pad could not bring them back), list rows get a clearly visible
     * focus highlight and, in the main screen, the toolbar actions moved to the navigation rail are hidden.
     */
    fun adapt(view: View, hideToolbarActions: Boolean) {
        when (view) {
            is AppBarLayout -> {
                for (i in 0 until view.childCount) {
                    (view.getChildAt(i).layoutParams as? AppBarLayout.LayoutParams)?.scrollFlags = 0
                }
                view.setExpanded(true, false)
            }
            is MaterialToolbar -> {
                if (hideToolbarActions) {
                    view.menu.findItem(R.id.search)?.isVisible = false
                    view.menu.findItem(R.id.settings)?.isVisible = false
                    view.menu.findItem(R.id.login)?.isVisible = false
                    // a toolbar left with nothing to show is just an empty band on TV
                    view.post {
                        val hasVisibleItems = (0 until view.menu.size()).any { view.menu.getItem(it).isVisible }
                        if (view.title.isNullOrBlank() && view.navigationIcon == null && !hasVisibleItems && view.childCount <= 1) {
                            view.visibility = View.GONE
                        }
                    }
                }
            }
            is GridRecyclerView -> {}
            is RecyclerView -> highlightRows(view)
            is TabLayout -> adaptTabs(view)
            is ViewPager2 -> adaptPager(view)
        }
        if (view.id == R.id.sortBar) {
            // clickable bar without any focus feedback of its own
            view.foreground = view.context.getDrawable(R.drawable.tv_row_focus)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                adapt(view.getChildAt(i), hideToolbarActions)
            }
        }
    }

    /**
     * Pages next to the current one stay laid out off screen: the D-pad must not be able to move
     * the focus into them, it would be lost on an invisible page.
     */
    private fun adaptPager(pager: ViewPager2) {
        val recyclerView = pager.getChildAt(0) as? RecyclerView ?: return
        fun update() {
            for (i in 0 until recyclerView.childCount) {
                val page = recyclerView.getChildAt(i) as? ViewGroup ?: continue
                val current = recyclerView.getChildAdapterPosition(page) == pager.currentItem
                page.descendantFocusability = if (current) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                update()
            }
        })
        recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                recyclerView.post { update() }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    /** Tabs are selected as soon as they get the focus, and show a clear focus frame. */
    private fun adaptTabs(tabLayout: TabLayout) {
        // TV look: left aligned pills
        tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
        tabLayout.tabGravity = TabLayout.GRAVITY_START
        tabLayout.setSelectedTabIndicator(R.drawable.tv_tab_indicator)
        tabLayout.setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_STRETCH)
        tabLayout.isTabIndicatorFullWidth = true
        tabLayout.tabRippleColor = null
        tabLayout.setSelectedTabIndicatorColor(MaterialColors.getColor(tabLayout, com.google.android.material.R.attr.colorSecondaryContainer))
        tabLayout.setTabTextColors(
            MaterialColors.getColor(tabLayout, android.R.attr.textColorSecondary),
            MaterialColors.getColor(tabLayout, com.google.android.material.R.attr.colorOnSecondaryContainer)
        )
        val strip = tabLayout.getChildAt(0) as? ViewGroup ?: return
        fun adaptTab(tab: View) {
            if (tab.foreground == null) {
                tab.foreground = tab.context.getDrawable(R.drawable.tv_row_focus)
            }
            tab.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus && !v.isSelected) {
                    v.performClick()
                }
            }
        }
        for (i in 0 until strip.childCount) {
            adaptTab(strip.getChildAt(i))
        }
        strip.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                child?.let { adaptTab(it) }
            }

            override fun onChildViewRemoved(parent: View?, child: View?) {}
        })
    }

    /** Dialog content: every clickable row or button gets a clearly visible focus frame. */
    fun adaptDialog(view: View) {
        if (view.isClickable && view.isFocusable && view.foreground == null && view !is RecyclerView) {
            view.foreground = view.context.getDrawable(R.drawable.tv_row_focus)
        }
        if (view is RecyclerView) {
            highlightRows(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                adaptDialog(view.getChildAt(i))
            }
        }
    }

    /** The list of the screen currently shown (the visible page of a pager), to give it the focus. */
    fun findVisibleGrid(root: View): GridRecyclerView? {
        if (!root.isShown) {
            return null
        }
        if (root is GridRecyclerView) {
            val rect = android.graphics.Rect()
            return root.takeIf { it.getGlobalVisibleRect(rect) && rect.width() > it.width / 2 }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findVisibleGrid(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Focuses the first visible item of the list, returns false when there is nothing to focus. */
    fun focusFirstItem(grid: GridRecyclerView): Boolean {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child.isShown && child.requestFocus()) {
                return true
            }
        }
        return false
    }

    fun highlightRows(recyclerView: RecyclerView) {
        if (recyclerView.getTag(R.id.tvRowsHighlighted) != null) {
            return
        }
        recyclerView.setTag(R.id.tvRowsHighlighted, true)
        recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (view.foreground == null) {
                    view.foreground = view.context.getDrawable(R.drawable.tv_row_focus)
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }
}
