package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
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
                }
            }
            is GridRecyclerView -> {}
            is RecyclerView -> highlightRows(view)
            is TabLayout -> adaptTabs(view)
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

    /** Tabs are selected as soon as they get the focus, and show a clear focus frame. */
    private fun adaptTabs(tabLayout: TabLayout) {
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
