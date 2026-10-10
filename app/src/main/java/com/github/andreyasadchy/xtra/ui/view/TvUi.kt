package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
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
                // no colored band at the top of the screens, the header sits on the background like on Google TV
                view.isLiftOnScroll = false
                view.stateListAnimator = null
                view.elevation = 0f
                view.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
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
            is com.google.android.material.floatingactionbutton.FloatingActionButton -> {
                if (view.id == R.id.scrollTop) {
                    // no scroll to top arrow on TV: the list follows the focus and back goes up
                    view.alpha = 0f
                    view.isFocusable = false
                    view.isClickable = false
                }
            }
            is GridRecyclerView -> {}
            is TextView -> {
                if (view.id == R.id.sortText) {
                    // the sort pill only says "Sort" while there is no sort text
                    val update = {
                        (view.parent as? View)?.findViewById<View>(R.id.sortByText)?.visibility = if (view.text.isNullOrBlank()) View.VISIBLE else View.GONE
                    }
                    update()
                    view.doAfterTextChanged { update() }
                }
            }
            is RecyclerView -> highlightRows(view)
            is TabLayout -> adaptTabs(view)
            is ViewPager2 -> adaptPager(view)
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

    /**
     * Bottom sheets (sort, filters, options...) become a panel on the right of the screen, full height,
     * like the menus of Google TV, and the focus goes inside on the checked choice.
     */
    fun adaptSheet(fragment: androidx.fragment.app.DialogFragment) {
        val dialog = fragment.dialog ?: return
        val context = dialog.context
        val density = context.resources.displayMetrics.density
        if (dialog is com.google.android.material.bottomsheet.BottomSheetDialog) {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
            if (sheet.getTag(R.id.tvRowsHighlighted) == null) {
                sheet.setTag(R.id.tvRowsHighlighted, true)
                dialog.behavior.apply {
                    skipCollapsed = true
                    isDraggable = false
                    state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                }
                (sheet.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let { params ->
                    params.width = (460 * density).toInt()
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    params.gravity = android.view.Gravity.END or android.view.Gravity.TOP
                    sheet.layoutParams = params
                }
                sheet.background = android.graphics.drawable.ColorDrawable(0xF2141318.toInt())
                sheet.setPadding((12 * density).toInt(), (24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt())
                sheet.translationX = 460 * density
                sheet.animate().translationX(0f).setDuration(180L).start()
                styleSheetViews(sheet)
                // sort, filters and choices: drawn with Compose
                val content = fragment.view
                val isChoiceSheet = fragment.javaClass.simpleName.endsWith("SortDialog") || fragment is com.github.andreyasadchy.xtra.ui.common.RadioButtonDialogFragment
                if (isChoiceSheet && content != null && sheet is ViewGroup && com.github.andreyasadchy.xtra.ui.tv.TvSheetCompose.canConvert(content)) {
                    com.github.andreyasadchy.xtra.ui.tv.TvSheetCompose.install(
                        sheet,
                        content,
                        if (fragment.javaClass.simpleName.endsWith("SortDialog")) context.getString(R.string.sort) else null
                    )
                    return
                }
            }
            focusInitial(sheet)
        } else {
            dialog.window?.decorView?.let { focusInitial(it) }
        }
    }

    /** Rows of the side panels: white text aligned on the left, like the other TV menus. */
    private fun styleSheetViews(view: View) {
        when (view) {
            is android.widget.CompoundButton -> {
                view.setTextColor(android.graphics.Color.WHITE)
                view.textSize = 16f
            }
            is android.widget.Button -> {
                view.setTextColor(android.graphics.Color.WHITE)
                view.isAllCaps = false
                view.textSize = 16f
                view.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                styleSheetViews(view.getChildAt(i))
            }
        }
    }

    /** Focus on the checked choice of a dialog, else on its first focusable view. */
    private fun focusInitial(root: View) {
        root.post {
            if (root.findFocus() != null) {
                return@post
            }
            val checked = findView(root) { it is android.widget.CompoundButton && it.isChecked && it.isShown && it.isFocusable }
            val target = checked ?: findView(root) { it.isShown && it.isFocusable && it !is ViewGroup }
                ?: findView(root) { it.isShown && it.isFocusable && it !== root }
            target?.requestFocus()
        }
    }

    private fun findView(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findView(view.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
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
